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

package com.leshao.v3;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.leshao.v3.hook.VersionCompat;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.Method;

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
        Object db = null;
        Cursor cursor = null;
        try {
            Context ctx = ContextManager.getAppContext();
            if (ctx == null) {
                LogWriter.log(TAG, "getAllChatRooms: ctx null");
                return result;
            }

            long uin = getUin(ctx);
            if (uin <= 0) {
                LogWriter.log(TAG, "getAllChatRooms: uin=0");
                return result;
            }

            String imei = VersionCompat.getImei(cl);
            String baseDir = VersionCompat.getBaseDir(cl, ctx);
            String dbHash = VersionCompat.getDbHash(cl, (int) uin);
            String dbPath = baseDir + "MicroMsg/" + dbHash + "/EnMicroMsg.db";
            String password = md5(imei + uin).substring(0, 7);

            Class<?> dbCls = VersionCompat.findDbOpenerClass(cl);
            if (dbCls == null) {
                LogWriter.log(TAG, "getAllChatRooms: dbCls null");
                return result;
            }

            db = VersionCompat.openDatabase(dbCls, dbPath, password);
            if (db == null) {
                LogWriter.log(TAG, "getAllChatRooms: db open FAILED");
                return result;
            }

            String sql = "SELECT username FROM rcontact WHERE deleteFlag = 0 "
                    + "AND username LIKE '%@chatroom' "
                    + "AND username NOT LIKE '%@im.chatroom'";
            java.lang.reflect.Method u = db.getClass().getDeclaredMethod("u", String.class, String[].class);
            cursor = (Cursor) u.invoke(db, sql, null);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String username = cursor.getString(0);
                    if (username != null) {
                        result.add(username);
                    }
                }
            }
            LogWriter.log(TAG, "found " + result.size() + " chatrooms from rcontact DB");

        } catch (Throwable e) {
            LogWriter.log(TAG, "getAllChatRooms FAILED: " + e.getClass().getSimpleName()
                + ": " + e.getMessage());
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (Throwable ignored) {}
            }
            if (db != null) {
                try {
                    java.lang.reflect.Method close = db.getClass().getDeclaredMethod("c");
                    close.invoke(db);
                } catch (Throwable ignored) {}
            }
        }
        return result;
    }

    private static long getUin(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences("system_config_prefs", 0);
            Object uv = sp.getAll().get("default_uin");
            if (uv != null) {
                String s = uv.toString();
                if (s.matches("\\d+")) return Long.parseLong(s);
            }
            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
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
                LogWriter.log(TAG, "y3 is null for " + roomId);
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

            LogWriter.log(TAG, roomId + " T: " + currentT + " → " + newT
                    + " (" + (mute ? "免打扰" : "正常") + ")");
            return true;

        } catch (Throwable e) {
            LogWriter.log(TAG, "setOneRoom failed for " + roomId + ": " + e.getMessage());
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

            LogWriter.log(TAG, "CGI synced for " + roomId);

        } catch (Throwable e) {
            // CGI 失败不影响本地功能，仅记录日志
            LogWriter.log(TAG, "CGI sync skipped for " + roomId + " (" + e.getMessage() + ")");
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
        LogWriter.log(TAG, "muteAll done: " + count + "/" + rooms.size());
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
        LogWriter.log(TAG, "unmuteAll done: " + count + "/" + rooms.size());
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
            LogWriter.log(TAG, "hook: start");
            Class<?> launcherUIClass = XposedHelpers.findClass(
                    "com.tencent.mm.ui.LauncherUI", cl);

            Method methodOnCreate = launcherUIClass.getDeclaredMethod("onCreate",
                    android.os.Bundle.class);
            XposedBridge.hookMethod(methodOnCreate, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context ctx = (Context) param.thisObject;
                            registerBroadcastReceiver(cl, ctx);
                        }
                    });

            LogWriter.log(TAG, "hook: LauncherUI.onCreate hooked OK");

        } catch (Throwable e) {
            LogWriter.log(TAG, "hook: FAILED - " + e.getClass().getSimpleName()
                + ": " + e.getMessage());
        }
    }

    // ==================== 广播接收器 ====================

    private static final String ACTION_MUTE_ALL   = "com.leshao.v3.MUTE_ALL";
    private static final String ACTION_UNMUTE_ALL = "com.leshao.v3.UNMUTE_ALL";
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

        LogWriter.log(TAG, "broadcast receiver registered for MUTE_ALL / UNMUTE_ALL");
    }
}
