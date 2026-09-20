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
                db = VersionCompat.openDatabaseWcdb(cl, dbPath, password);
            }
            if (db == null) {
                LogWriter.log(TAG, "getAllChatRooms: db open FAILED");
                return result;
            }

            String sql = "SELECT username FROM rcontact WHERE deleteFlag = 0 "
                    + "AND username LIKE '%@chatroom' "
                    + "AND username NOT LIKE '%@im.chatroom'";
            java.lang.reflect.Method queryMethod = findQueryMethod(db.getClass());
            if (queryMethod == null) {
                LogWriter.log(TAG, "getAllChatRooms: no query method found");
                return result;
            }
            Class<?>[] paramTypes = queryMethod.getParameterTypes();
            if (paramTypes.length == 1) {
                cursor = (Cursor) queryMethod.invoke(db, sql);
            } else {
                cursor = (Cursor) queryMethod.invoke(db, sql, null);
            }
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String username = cursor.getString(0);
                    if (username != null) {
                        result.add(username);
                    }
                }
            }
            LogWriter.log(TAG, "found " + result.size() + " chatrooms from rcontact DB via "
                    + queryMethod.getName());

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

    /** 兼容 rawQuery 方法定位: 优先 (String,String[]) 2参, 退回 1参, 兜底遍历返回 Cursor 的方法。
     *  与 ContactRepository.findQueryMethod 策略一致, 防止新版 DB opener 类方法名漂移。 */
    private static java.lang.reflect.Method findQueryMethod(Class<?> dbClass) {
        try {
            java.lang.reflect.Method m = dbClass.getDeclaredMethod("u", String.class, String[].class);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException ignored) {}
        String[] knownNames = {"rawQuery", "v", "w", "x", "y", "z", "rowQuery"};
        for (String name : knownNames) {
            try {
                java.lang.reflect.Method m = dbClass.getDeclaredMethod(name, String.class, String[].class);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
        }
        for (String name : new String[]{"u", "rawQuery", "v", "w", "x", "y", "z", "rowQuery"}) {
            try {
                java.lang.reflect.Method m = dbClass.getDeclaredMethod(name, String.class);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
        }
        java.lang.reflect.Method best = null;
        for (java.lang.reflect.Method m : dbClass.getDeclaredMethods()) {
            if (m.getReturnType() == android.database.Cursor.class) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 2 && pts[0] == String.class && pts[1] == String[].class) {
                    m.setAccessible(true);
                    return m;
                }
                if (best == null && pts.length >= 1 && pts[0] == String.class) {
                    best = m;
                }
            }
        }
        if (best != null) best.setAccessible(true);
        return best;
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
            Class<?> j1Class = findServiceLocatorClass(cl);
            Class<?> c4Class = findC4Class(cl);
            if (j1Class == null || c4Class == null) {
                LogWriter.log(TAG, "setOneRoom: j1=" + (j1Class != null) + " c4=" + (c4Class != null));
                return false;
            }
            // h2 = j1.s(c4 / v(c4) — zip 用 v, 旧版用 s
            Object h2 = null;
            for (String mn : new String[]{"v", "s"}) {
                try {
                    java.lang.reflect.Method m = null;
                    for (java.lang.reflect.Method mm : j1Class.getDeclaredMethods()) {
                        if (mm.getName().equals(mn) && mm.getParameterCount() == 1
                                && mm.getParameterTypes()[0] == Class.class) {
                            m = mm;
                            break;
                        }
                    }
                    if (m == null) continue;
                    m.setAccessible(true);
                    h2 = m.invoke(null, c4Class);
                    if (h2 != null) break;
                } catch (Throwable ignored) {}
            }
            if (h2 == null) {
                LogWriter.log(TAG, "setOneRoom: h2 null (j1=" + j1Class.getName() + ")");
                return false;
            }

            // h2.ij() → ContactStorage
            Object j4Storage = callNoArg(h2, "ij");
            if (j4Storage == null) {
                LogWriter.log(TAG, "setOneRoom: ij() null");
                return false;
            }

            // j4.n(username, true) → y3 联系人存储对象
            Object y3Obj = null;
            for (String mn : new String[]{"n", "c", "o", "e"}) {
                try {
                    for (java.lang.reflect.Method mm : j4Storage.getClass().getDeclaredMethods()) {
                        if (mm.getName().equals(mn) && mm.getParameterCount() == 2
                                && mm.getParameterTypes()[0] == String.class) {
                            mm.setAccessible(true);
                            y3Obj = mm.invoke(j4Storage, roomId, true);
                            break;
                        }
                    }
                    if (y3Obj != null) break;
                } catch (Throwable ignored) {}
            }
            if (y3Obj == null) {
                LogWriter.log(TAG, "y3 is null for " + roomId);
                return false;
            }

            // 读取 f2.T 字段 (0=免打扰, 非0=正常)
            int currentT = XposedHelpers.getIntField(y3Obj, "T");
            int newT = mute ? 0 : 1;

            if (currentT == newT) {
                // 已处于目标状态，跳过
                return false;
            }

            // 步骤1: y3.J2(newT) — 写 f2.T 字段并触发内存更新
            boolean wroteMem = tryInvoke(y3Obj, "J2", newT);
            if (!wroteMem) {
                try { XposedHelpers.setIntField(y3Obj, "T", newT); } catch (Throwable ignored) {}
            }

            // 步骤2: j4.p0(username, y3) — 持久化到 SQLite rcontact 表
            tryInvoke(j4Storage, "p0", roomId, y3Obj);

            // 步骤3: 可选 — 发 CGI 同步到微信服务器
            syncToServerCompat(cl, roomId, newT);

            LogWriter.log(TAG, roomId + " T: " + currentT + " → " + newT
                    + " (" + (mute ? "免打扰" : "正常") + ")");
            return true;

        } catch (Throwable e) {
            LogWriter.log(TAG, "setOneRoom failed for " + roomId + ": " + e.getMessage());
            return false;
        }
    }

    private static Class<?> findServiceLocatorClass(ClassLoader cl) {
        String dk = com.leshao.v3.hook.DexKitHelper.getJ1ServiceClass();
        if (dk != null && !dk.isEmpty()) {
            try { return XposedHelpers.findClass(dk, cl); } catch (Throwable ignored) {}
        }
        for (String n : new String[]{"gp0.j1", "gp0.j1.j", "hm0.j1", "fp0.j1", "fp0.j1.j"}) {
            try { return XposedHelpers.findClass(n, cl); } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Class<?> findC4Class(ClassLoader cl) {
        for (String n : new String[]{"tn3.c4", "sh3.c4"}) {
            try { return XposedHelpers.findClass(n, cl); } catch (Throwable ignored) {}
        }
        return null;
    }

    private static boolean tryInvoke(Object obj, String name, int arg) {
        try {
            for (java.lang.reflect.Method m : obj.getClass().getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == int.class) {
                    m.setAccessible(true);
                    m.invoke(obj, arg);
                    return true;
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "tryInvoke " + name + " err: " + t.getMessage());
        }
        return false;
    }

    private static boolean tryInvoke(Object obj, String name, Object a1, Object a2) {
        try {
            for (java.lang.reflect.Method m : obj.getClass().getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 2) {
                    m.setAccessible(true);
                    m.invoke(obj, a1, a2);
                    return true;
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "tryInvoke p0 err: " + t.getMessage());
            return false;
        }
        return false;
    }

    private static Object callNoArg(Object obj, String name) {
        try {
            for (java.lang.reflect.Method m : obj.getClass().getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    return m.invoke(obj);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void syncToServerCompat(ClassLoader cl, String roomId, int muteFlag) {
        try {
            Class<?> n0Class = XposedHelpers.findClass("pa5.n0", cl);
            Class<?> fd0eClass = XposedHelpers.findClass("fd0.e", cl);
            Object fd0eImpl = null;
            for (java.lang.reflect.Method m : n0Class.getDeclaredMethods()) {
                if (m.getName().equals("c") && m.getParameterCount() == 1) {
                    m.setAccessible(true);
                    fd0eImpl = m.invoke(null, fd0eClass);
                    break;
                }
            }
            if (fd0eImpl == null) { LogWriter.log(TAG, "CGI skipped: n0.c null"); return; }
            Object builder = callAny(fd0eImpl, "hj", roomId);
            if (builder == null) { LogWriter.log(TAG, "CGI skipped: hj null"); return; }
            builder = callAny(builder, "h", roomId, muteFlag, 0);
            if (builder != null) callNoArg(builder, "b");
            LogWriter.log(TAG, "CGI synced for " + roomId);
        } catch (Throwable e) {
            LogWriter.log(TAG, "CGI sync skipped for " + roomId + " (" + e.getMessage() + ")");
        }
    }

    private static Object callAny(Object obj, String name, Object... args) {
        try {
            for (java.lang.reflect.Method m : obj.getClass().getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    return m.invoke(obj, args);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ==================== 批量操作 ====================

    /**
     * 一键设置所有群聊免打扰，返回成功数量
     */
    public static int muteAll(ClassLoader cl) {
        return applyAll(cl, getAllChatRooms(cl), true);
    }

    /**
     * 一键取消所有群聊免打扰，返回成功数量
     */
    public static int unmuteAll(ClassLoader cl) {
        return applyAll(cl, getAllChatRooms(cl), false);
    }

    private static int applyAll(ClassLoader cl, List<String> rooms, boolean mute) {
        int count = 0;
        for (String roomId : rooms) {
            if (setOneRoom(cl, roomId, mute)) count++;
        }
        LogWriter.log(TAG, (mute ? "muteAll" : "unmuteAll") + " done: " + count + "/" + rooms.size());
        return count;
    }

    // ==================== 异步执行 + Toast 提示 ====================

    /**
     * 异步执行全部免打扰（不阻塞主线程），完成后弹出 Toast
     */
    public static void muteAllAsync(ClassLoader cl, Context ctx) {
        Toast.makeText(ctx, "开始设置群聊免打扰...", Toast.LENGTH_SHORT).show();
        sExecutor.execute(() -> {
            List<String> rooms = getAllChatRooms(cl);
            final int total = rooms.size();
            final int count = applyAll(cl, rooms, true);
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
        Toast.makeText(ctx, "开始取消群聊免打扰...", Toast.LENGTH_SHORT).show();
        sExecutor.execute(() -> {
            List<String> rooms = getAllChatRooms(cl);
            final int total = rooms.size();
            final int count = applyAll(cl, rooms, false);
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
                            try {
                                                        Context ctx = (Context) param.thisObject;
                                                        registerBroadcastReceiver(cl, ctx);
                            } catch (Throwable e) {
                                LogWriter.log("ChatRoomMute", "cb err: " + e);
                            }
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
