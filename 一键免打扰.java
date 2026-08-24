/*
 * ChatRoomMuteHelper.java
 * ============================================================
 * 微信所有群聊一键免打扰/取消免打扰 — Xposed 模块核心类
 * 验证版本: 微信 8.0.76 (3141)
 * 
 * 文件位置（手机内）：
 *   /storage/emulated/0/Android/media/com.tencent.mm/LSPilot/Plugin/ChatRoomMuteExport/main.java
 * 
 * 集成到你的 Xposed 模块步骤：
 *   1. 复制此类到你的模块源码目录
 *   2. 修改包名 com.your.module 为你自己的包名
 *   3. 在 handleLoadPackage 中调用 ChatRoomMuteHelper.hook(lpparam.classLoader)
 *
 * 触发方式：
 *   adb shell am broadcast -a com.your.module.MUTE_ALL       # 全部免打扰
 *   adb shell am broadcast -a com.your.module.UNMUTE_ALL     # 取消全部免打扰
 * ============================================================
 */

package com.your.module;  // ← 改成你的模块包名

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 微信所有群聊一键免打扰/取消免打扰
 *
 * 关键调用链：
 *   j1.s(c4.class).lj().q(null)           → 获取所有会话列表
 *   username.endsWith("@chatroom")         → 过滤群聊
 *   j1.s(c4.class).ij().n(roomId, true)    → 获取 y3 对象
 *   y3.J2(0) / y3.J2(1)                    → 写 f2.T 字段 (0=免打扰, 1=正常)
 *   j1.s(c4.class).ij().p0(roomId, y3)     → 持久化到 SQLite
 *   n0.c(fd0.e.class).hj(roomId).h(...).b() → CGI 同步服务器 (可选)
 *
 * 逆向分析位置:
 *   ChatroomInfoUI.onPreferenceTreeClick (line ~1310)
 *   ChatroomInfoUI.initView → k7() 判断免打扰状态
 *   un.p.s7() → ChatRoomOperationUIC 设置免打扰 UI
 */
public class ChatRoomMuteHelper {

    private static final String TAG = "ChatRoomMute";
    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();

    // ==================== 获取所有群聊 ====================

    /**
     * 获取所有群聊 username 列表
     * 
     * 原理: j1.s(sh3.c4) → h2 实例 → h2.lj() → l4 (ConversationStorage)
     *       l4.q(null) 执行 SQL: SELECT username FROM rconversation
     *       过滤 @chatroom 后缀得到群聊
     */
    public static List<String> getAllChatRooms(ClassLoader cl) {
        List<String> result = new ArrayList<>();
        try {
            Class<?> c4Class = XposedHelpers.findClass("sh3.c4", cl);
            Class<?> j1Class = XposedHelpers.findClass("hm0.j1", cl);
            Object h2 = XposedHelpers.callStaticMethod(j1Class, "s", c4Class);

            // h2.lj() → com.tencent.mm.storage.l4
            Object l4Storage = XposedHelpers.callMethod(h2, "lj");

            // l4.q(null) → List<String> (所有会话 username)
            @SuppressWarnings("unchecked")
            List<String> allConv = (List<String>) XposedHelpers.callMethod(l4Storage, "q", (Object) null);

            for (String username : allConv) {
                if (username != null && username.endsWith("@chatroom")) {
                    result.add(username);
                }
            }
            XposedBridge.log(TAG + ": found " + result.size() + " chatrooms out of "
                    + (allConv != null ? allConv.size() : 0) + " conversations");
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": getAllChatRooms failed: " + e.getMessage());
        }
        return result;
    }

    // ==================== 单个群聊设置免打扰 ====================

    /**
     * 对单个群聊设置/取消免打扰
     *
     * @param cl     ClassLoader
     * @param roomId 群聊 username (如 21730591086@chatroom)
     * @param mute   true=免打扰(T=0), false=取消免打扰(T=1)
     * @return 是否实际修改（已处于目标状态返回 false）
     */
    public static boolean setOneRoom(ClassLoader cl, String roomId, boolean mute) {
        try {
            Class<?> c4Class = XposedHelpers.findClass("sh3.c4", cl);
            Class<?> j1Class = XposedHelpers.findClass("hm0.j1", cl);
            Object h2 = XposedHelpers.callStaticMethod(j1Class, "s", c4Class);

            // h2.ij() → com.tencent.mm.storage.j4 (ContactStorage)
            Object j4Storage = XposedHelpers.callMethod(h2, "ij");

            // j4.n(username, true) → y3 (联系人存储对象)
            Object y3Obj = XposedHelpers.callMethod(j4Storage, "n", roomId, true);
            if (y3Obj == null) {
                XposedBridge.log(TAG + ": y3 is null for " + roomId);
                return false;
            }

            // 读取 dm.f2.T 字段 (0=免打扰, 非0=正常)
            int currentT = XposedHelpers.getIntField(y3Obj, "T");
            int newT = mute ? 0 : 1;

            if (currentT == newT) {
                // 已处于目标状态，跳过
                return false;
            }

            // 步骤1: y3.J2(newT) — 写 f2.T 字段并触发内存更新
            // 源码: com.tencent.mm.contact.s.J2(int i)
            //       ((f2)this).T = i; ((f2)this).u = true; j2();
            XposedHelpers.callMethod(y3Obj, "J2", newT);

            // 步骤2: j4.p0(username, y3) — 持久化到 SQLite rcontact 表
            XposedHelpers.callMethod(j4Storage, "p0", roomId, y3Obj);

            // 步骤3: 可选 — 发 CGI 同步到微信服务器
            // 对应: ((fd0.e) n0.c(fd0.e.class)).hj(roomId).h(roomId, flag, 0).b()
            syncToServer(cl, roomId, newT);

            XposedBridge.log(TAG + ": " + roomId + " T: " + currentT + " → " + newT
                    + " (" + (mute ? "免打扰" : "正常") + ")");
            return true;

        } catch (Throwable e) {
            XposedBridge.log(TAG + ": setOneRoom failed for " + roomId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * CGI 同步免打扰状态到微信服务器
     * 
     * 对应 ChatroomInfoUI 中的:
     *   ((fd0.e) n0.c(fd0.e.class)).hj(this.A).h(this.A, i3, i4).b()
     * 
     * 如果此步骤失败（微信版本更新导致反射不到），不影响本地功能
     */
    private static void syncToServer(ClassLoader cl, String roomId, int muteFlag) {
        try {
            Class<?> n0Class = XposedHelpers.findClass("pa5.n0", cl);
            Class<?> fd0eClass = XposedHelpers.findClass("fd0.e", cl);
            Object fd0eImpl = XposedHelpers.callStaticMethod(n0Class, "c", fd0eClass);

            // fd0eImpl.hj(roomId) → builder
            Object builder = XposedHelpers.callMethod(fd0eImpl, "hj", roomId);

            // builder.h(roomId, isMute, defaultPushFlag) → builder
            builder = XposedHelpers.callMethod(builder, "h", roomId, muteFlag, 0);

            // builder.b() → 发送 CGI 请求
            XposedHelpers.callMethod(builder, "b");

            XposedBridge.log(TAG + ": CGI synced for " + roomId);

        } catch (Throwable e) {
            // CGI 失败不影响本地功能，仅记录日志
            XposedBridge.log(TAG + ": CGI sync skipped for " + roomId + " (" + e.getMessage() + ")");
        }
    }

    // ==================== 批量操作 ====================

    /**
     * 一键设置所有群聊免打扰，返回成功数量
     */
    public static int muteAll(ClassLoader cl) {
        List<String> rooms = getAllChatRooms(cl);
        int count = 0;
        for (String roomId : rooms) {
            if (setOneRoom(cl, roomId, true)) count++;
        }
        XposedBridge.log(TAG + ": muteAll done: " + count + "/" + rooms.size());
        return count;
    }

    /**
     * 一键取消所有群聊免打扰，返回成功数量
     */
    public static int unmuteAll(ClassLoader cl) {
        List<String> rooms = getAllChatRooms(cl);
        int count = 0;
        for (String roomId : rooms) {
            if (setOneRoom(cl, roomId, false)) count++;
        }
        XposedBridge.log(TAG + ": unmuteAll done: " + count + "/" + rooms.size());
        return count;
    }

    // ==================== 异步执行 + Toast 提示 ====================

    /**
     * 异步执行全部免打扰（不阻塞主线程），完成后弹出 Toast
     */
    public static void muteAllAsync(ClassLoader cl, Context ctx) {
        final int total = getAllChatRooms(cl).size();
        Toast.makeText(ctx, "开始设置 " + total + " 个群聊免打扰...", Toast.LENGTH_SHORT).show();
        sExecutor.execute(() -> {
            int count = muteAll(cl);
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(ctx, "√ 已免打扰 " + count + "/" + total + " 个群聊",
                            Toast.LENGTH_SHORT).show()
            );
        });
    }

    /**
     * 异步执行取消全部免打扰，完成后弹出 Toast
     */
    public static void unmuteAllAsync(ClassLoader cl, Context ctx) {
        final int total = getAllChatRooms(cl).size();
        Toast.makeText(ctx, "开始取消 " + total + " 个群聊免打扰...", Toast.LENGTH_SHORT).show();
        sExecutor.execute(() -> {
            int count = unmuteAll(cl);
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(ctx, "√ 已取消 " + count + "/" + total + " 个群聊免打扰",
                            Toast.LENGTH_SHORT).show()
            );
        });
    }

    // ==================== Xposed Hook 入口 ====================

    /**
     * 在 handleLoadPackage 中调用此方法
     *
     * 用法:
     *   if ("com.tencent.mm".equals(lpparam.packageName)) {
     *       ChatRoomMuteHelper.hook(lpparam.classLoader);
     *   }
     */
    public static void hook(ClassLoader cl) {
        try {
            Class<?> launcherUIClass = XposedHelpers.findClass(
                    "com.tencent.mm.ui.LauncherUI", cl);

            XposedHelpers.findAndHookMethod(launcherUIClass, "onCreate",
                    android.os.Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context ctx = (Context) param.thisObject;
                            registerBroadcastReceiver(cl, ctx);
                        }
                    });

            XposedBridge.log(TAG + ": hooked LauncherUI.onCreate — broadcast receiver registered");

        } catch (Throwable e) {
            XposedBridge.log(TAG + ": hook failed: " + e.getMessage());
        }
    }

    // ==================== 广播接收器 ====================

    private static final String ACTION_MUTE_ALL   = "com.your.module.MUTE_ALL";
    private static final String ACTION_UNMUTE_ALL = "com.your.module.UNMUTE_ALL";
    private static boolean sReceiverRegistered = false;

    private static void registerBroadcastReceiver(ClassLoader cl, Context ctx) {
        if (sReceiverRegistered) return;
        sReceiverRegistered = true;

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ACTION_MUTE_ALL.equals(action)) {
                    muteAllAsync(cl, context);
                } else if (ACTION_UNMUTE_ALL.equals(action)) {
                    unmuteAllAsync(cl, context);
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_MUTE_ALL);
        filter.addAction(ACTION_UNMUTE_ALL);

        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, filter);
        }

        XposedBridge.log(TAG + ": broadcast receiver registered for MUTE_ALL / UNMUTE_ALL");
    }
}
