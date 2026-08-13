package com.leshao.v3.ai;

import android.content.Context;
import android.database.Cursor;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 直接从微信 EnMicroMsg.db 的 message 表读取聊天记录。
 * 复刻自 WmChatHook/MsgExport 已验证的 rawQuery 方案，替代不稳定的 f9 反射查询。
 */
public class AiMsgDb {
    private static final String TAG = "AiMsgDb";

    private final ClassLoader cl;

    public AiMsgDb(ClassLoader cl) { this.cl = cl; }

    public List<MessageReader.ChatMsg> readRecent(String talker, int limit) {
        List<MessageReader.ChatMsg> out = new ArrayList<>();
        Cursor c = null;
        try {
            Object db = openDb();
            if (db == null) {
                LogWriter.log(TAG, "readRecent: 打开数据库失败 talker=" + talker);
                return out;
            }
            int n = limit > 0 ? limit : 200;
            c = rawQuery(db,
                    "SELECT msgContent, createTime, isSend, type FROM message WHERE talker=? ORDER BY createTime DESC LIMIT " + n,
                    new String[]{talker});
            if (c == null) {
                LogWriter.log(TAG, "readRecent: 查询返回 null talker=" + talker);
                return out;
            }
            int rows = c.getCount();
            LogWriter.log(TAG, "readRecent: 命中 " + rows + " 条 talker=" + talker);
            int skipType = 0, skipEmpty = 0;
            while (c.moveToNext()) {
                String content = c.getString(0);
                long time = c.getLong(1);
                int isSend = c.getInt(2);
                int type = c.getInt(3);
                if (type != AiConst.TYPE_TEXT) { skipType++; continue; }
                if (content == null || content.trim().isEmpty()) { skipEmpty++; continue; }
                String role = isSend == 1 ? "me" : "other";
                out.add(new MessageReader.ChatMsg(role, "", content, time));
            }
            // DESC 查询需要反转为时间正序
            Collections.reverse(out);
            LogWriter.log(TAG, "readRecent: 文本=" + out.size() + " 非文本跳过=" + skipType + " 空跳过=" + skipEmpty);
        } catch (Throwable t) {
            LogWriter.log(TAG, "readRecent 异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) {}
        }
        return out;
    }

    private Object openDb() {
        try {
            Context ctx = ContextManager.getAppContext();
            if (ctx == null) { LogWriter.log(TAG, "openDb: 无 app context"); return null; }
            long uin = getUin(ctx);
            if (uin <= 0) { LogWriter.log(TAG, "openDb: uin=0"); return null; }

            String imei = getImei();
            String password = md5(imei + uin).substring(0, 7);
            byte[] pwdBytes = password.getBytes("UTF-8");

            String base = getBaseDir(ctx);
            String hash = getDbHash((int) uin);
            String dbPath = base + "MicroMsg/" + hash + "/EnMicroMsg.db";
            LogWriter.log(TAG, "openDb: path=" + dbPath);

            try {
                Class<?> sqliteDB = cl.loadClass("com.tencent.wcdb.database.SQLiteDatabase");
                Class<?> cursorFactory = cl.loadClass("com.tencent.wcdb.database.SQLiteDatabase$CursorFactory");

                // 尝试 String 密码 + CipherSpec（5参）
                try {
                    Class<?> cipher = cl.loadClass("com.tencent.wcdb.database.SQLiteCipherSpec");
                    Method m = sqliteDB.getMethod("openDatabase", String.class, String.class, cipher,
                            cursorFactory, int.class);
                    Object db = m.invoke(null, dbPath, password, null, null, 0);
                    LogWriter.log(TAG, "openDb: 成功(CipherSpec) db类=" + db.getClass().getName());
                    return db;
                } catch (Throwable ignored) {}

                // 尝试 byte[] 密码（4参）
                try {
                    Method m = sqliteDB.getMethod("openDatabase", String.class, byte[].class,
                            cursorFactory, int.class);
                    Object db = m.invoke(null, dbPath, pwdBytes, null, 0);
                    LogWriter.log(TAG, "openDb: 成功(byte[]) db类=" + db.getClass().getName());
                    return db;
                } catch (Throwable ignored) {}

                // 尝试 byte[] 密码 + ErrorHandler（5参）
                try {
                    Class<?> errHandler = cl.loadClass("com.tencent.wcdb.database.SQLiteDatabase$DatabaseErrorHandler");
                    Method m = sqliteDB.getMethod("openDatabase", String.class, byte[].class,
                            cursorFactory, int.class, errHandler);
                    Object db = m.invoke(null, dbPath, pwdBytes, null, 0, null);
                    LogWriter.log(TAG, "openDb: 成功(byte[]+err) db类=" + db.getClass().getName());
                    return db;
                } catch (Throwable ignored) {}

                LogWriter.log(TAG, "openDb: WCDB openDatabase 所有签名均失败");
            } catch (Throwable t) {
                LogWriter.log(TAG, "openDb: WCDB 加载失败 " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "openDb 异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return null;
    }

    private String getImei() {
        try {
            Class<?> wo = cl.loadClass("wo.w0");
            Method g = wo.getDeclaredMethod("g", boolean.class);
            String s = (String) g.invoke(null, true);
            if (s != null && !s.isEmpty() && !s.equals("1234567890ABCDEF")) return s;
        } catch (Throwable ignored) {}
        try {
            Class<?> wo = cl.loadClass("wn.w0");
            Method g = wo.getDeclaredMethod("g", boolean.class);
            String s = (String) g.invoke(null, true);
            if (s != null && !s.isEmpty() && !s.equals("1234567890ABCDEF")) return s;
        } catch (Throwable ignored) {}
        return "1234567890ABCDEF";
    }

    private String getBaseDir(Context ctx) {
        try {
            Class<?> bc = cl.loadClass("mp0.b");
            String r = (String) bc.getDeclaredMethod("X").invoke(null);
            if (r != null && !r.isEmpty()) return r;
        } catch (Throwable ignored) {}
        try {
            Class<?> bc = cl.loadClass("mo0.b");
            String r = (String) bc.getDeclaredMethod("X").invoke(null);
            if (r != null && !r.isEmpty()) return r;
        } catch (Throwable ignored) {}
        return ctx.getFilesDir().getParentFile().getAbsolutePath() + "/";
    }

    private String getDbHash(int uin) {
        try {
            Class<?> hc = cl.loadClass("hm0.b0");
            String r = (String) hc.getDeclaredMethod("e", int.class).invoke(null, uin);
            if (r != null && !r.isEmpty()) return r;
        } catch (Throwable ignored) {}
        try {
            Class<?> hc = cl.loadClass("hm0.a0");
            String r = (String) hc.getDeclaredMethod("e", int.class).invoke(null, uin);
            if (r != null && !r.isEmpty()) return r;
        } catch (Throwable ignored) {}
        return md5("mm" + uin);
    }

    private static Cursor rawQuery(Object db, String sql, String[] args) {
        Class<?> cls = db.getClass();
        dumpDbMethods(cls);
        String[] names = {"rawQuery", "u", "v", "w", "x", "y", "z", "rowQuery"};
        for (String nm : names) {
            Cursor c = tryRawByName(cls, db, nm, sql, args);
            if (c != null) { LogWriter.log(TAG, "rawQuery: 命中方法 " + nm); return c; }
        }
        LogWriter.log(TAG, "rawQuery: 未找到可用 rawQuery 方法");
        return null;
    }

    private static void dumpDbMethods(Class<?> cls) {
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        for (Method m : cls.getMethods()) names.add(m.getName());
        for (Method m : cls.getDeclaredMethods()) names.add(m.getName());
        LogWriter.log(TAG, "dumpDb: 类=" + cls.getName()
                + " 父类=" + (cls.getSuperclass() == null ? "-" : cls.getSuperclass().getName())
                + " 方法数=" + names.size() + " 方法=" + String.join(",", names));
    }

    private static Cursor tryRawByName(Class<?> cls, Object db, String nm, String sql, String[] args) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(nm)) {
                Cursor c = invokeRaw(m, db, sql, args);
                if (c != null) return c;
            }
        }
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(nm)) {
                Cursor c = invokeRaw(m, db, sql, args);
                if (c != null) return c;
            }
        }
        return null;
    }

    private static Cursor invokeRaw(Method m, Object db, String sql, String[] args) {
        try {
            m.setAccessible(true);
            Class<?>[] pts = m.getParameterTypes();
            Object r = null;
            if (pts.length >= 2 && pts[0] == String.class && pts[1] == String[].class) {
                if (pts.length == 2) {
                    r = m.invoke(db, sql, args);
                } else {
                    r = m.invoke(db, sql, args, null);
                }
            } else if (pts.length == 1 && pts[0] == String.class) {
                r = m.invoke(db, sql);
            } else {
                return null;
            }
            if (r instanceof Cursor) return (Cursor) r;
            if (r != null) LogWriter.log(TAG, "invokeRaw: 方法 " + m.getName()
                    + " 返回非 Cursor 类型=" + r.getClass().getName());
        } catch (Throwable t) {
            LogWriter.log(TAG, "invokeRaw: 方法 " + m.getName() + " 失败 " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
        return null;
    }

    private static long getUin(Context ctx) {
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences("system_config_prefs", 0);
            Object uv = sp.getAll().get("default_uin");
            if (uv != null) return Long.parseLong(uv.toString());
        } catch (Throwable ignored) {}
        return 0;
    }

    private static String md5(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }
}
