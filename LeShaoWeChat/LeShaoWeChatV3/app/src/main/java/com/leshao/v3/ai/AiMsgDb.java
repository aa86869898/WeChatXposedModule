package com.leshao.v3.ai;

import android.content.Context;
import android.database.Cursor;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.VersionCompat;

import java.lang.reflect.Method;

import de.robv.android.xposed.XposedHelpers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * 直接从微信 EnMicroMsg.db 的 message 表读取聊天记录。
 * 完全复刻 ContactRepository 已验证方案：
 *   VersionCompat.openDatabase 打开 + db.getDeclaredMethod("u", String, String[]) 查询。
 */
public class AiMsgDb {
    private static final String TAG = "AiMsgDb";

    private final ClassLoader cl;

    public AiMsgDb(ClassLoader cl) { this.cl = cl; }

    public List<MessageReader.ChatMsg> readRecent(String talker, int limit) {
        return readRecentInternal(talker, limit, true);
    }

    public List<MessageReader.ChatMsg> readRecentFull(String talker, int limit) {
        return readRecentInternal(talker, limit, false);
    }

    private List<MessageReader.ChatMsg> readRecentInternal(String talker, int limit, boolean textOnly) {
        List<MessageReader.ChatMsg> out = new ArrayList<>();
        Object db = null;
        Cursor c = null;
        try {
            db = openDb();
            if (db == null) {
                LogWriter.log(TAG, "readRecent: 打开数据库失败 talker=" + talker);
                return out;
            }
            int n = limit > 0 ? limit : 200;
            c = queryMessage(db, talker, n);
            if (c == null) {
                LogWriter.log(TAG, "readRecent: 查询返回 null talker=" + talker);
                diagnoseMessageTable(db);
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
                if (textOnly) {
                    if (type != AiConst.TYPE_TEXT) { skipType++; continue; }
                    if (content == null || content.trim().isEmpty()) { skipEmpty++; continue; }
                } else if (content == null) {
                    content = "";
                }
                String role = isSend == 1 ? "me" : "other";
                out.add(new MessageReader.ChatMsg(role, "", content, time, type));
            }
            Collections.reverse(out);
            LogWriter.log(TAG, "readRecent: 文本=" + (textOnly ? out.size() : "-")
                    + " 全部=" + out.size() + " 非文本跳过=" + skipType + " 空跳过=" + skipEmpty);
        } catch (Throwable t) {
            LogWriter.log(TAG, "readRecent 异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) {}
            closeDb(db);
        }
        return out;
    }

    private static void closeDb(Object db) {
        if (db == null) return;
        try {
            db.getClass().getMethod("close").invoke(db);
        } catch (Throwable t) {
            try { XposedHelpers.callMethod(db, "close"); } catch (Throwable ignored) {}
        }
    }

    private Cursor queryMessage(Object db, String talker, int n) {
        String[] colVariants = {"content", "msgContent"};
        for (String col : colVariants) {
            String sql = "SELECT " + col + ", createTime, isSend, type FROM message"
                    + " WHERE talker=? ORDER BY createTime DESC LIMIT " + n;
            Cursor c = rawQuery(db, sql, new String[]{talker});
            if (c != null) return c;
        }
        return null;
    }

    private void diagnoseMessageTable(Object db) {
        try {
            Cursor c = rawQuery(db, "SELECT * FROM message LIMIT 1", null);
            if (c != null) {
                String[] cols = c.getColumnNames();
                LogWriter.log(TAG, "message 表列名: " + String.join(",", cols));
                c.close();
            } else {
                LogWriter.log(TAG, "message 表 SELECT * 也返回 null");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "diagnose message 异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private Object openDb() {
        try {
            Context ctx = ContextManager.getAppContext();
            if (ctx == null) { LogWriter.log(TAG, "openDb: 无 app context"); return null; }
            long uin = getUin(ctx);
            if (uin <= 0) { LogWriter.log(TAG, "openDb: uin=0"); return null; }

            String imei = VersionCompat.getImei(cl);
            String password = md5(imei + uin).substring(0, 7);
            String base = VersionCompat.getBaseDir(cl, ctx);
            String hash = VersionCompat.getDbHash(cl, (int) uin);
            String dbPath = base + "MicroMsg/" + hash + "/EnMicroMsg.db";
            LogWriter.log(TAG, "openDb: path=" + dbPath);

            Class<?> dbCls = VersionCompat.findDbOpenerClass(cl);
            if (dbCls == null) { LogWriter.log(TAG, "openDb: dbOpener 类未找到"); return null; }
            LogWriter.log(TAG, "openDb: dbOpener=" + dbCls.getName());

            Object db = VersionCompat.openDatabase(dbCls, dbPath, password);
            if (db == null) {
                LogWriter.log(TAG, "openDb: openDatabase 返回 null");
            } else {
                LogWriter.log(TAG, "openDb: 成功 db类=" + db.getClass().getName());
            }
            return db;
        } catch (Throwable t) {
            LogWriter.log(TAG, "openDb 异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    private static Cursor rawQuery(Object db, String sql, String[] args) {
        Class<?> cls = db.getClass();
        String[] names = {"u", "rawQuery", "v", "w", "x", "y", "z", "rowQuery"};
        for (String nm : names) {
            Cursor c = tryRawByName(cls, db, nm, sql, args);
            if (c != null) { LogWriter.log(TAG, "rawQuery: 命中方法 " + nm); return c; }
        }
        dumpDbMethods(cls);
        LogWriter.log(TAG, "rawQuery: 未找到可用方法");
        return null;
    }

    private static void dumpDbMethods(Class<?> cls) {
        TreeSet<String> names = new TreeSet<>();
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
            Object r;
            if (pts.length == 2 && pts[0] == String.class && pts[1] == String[].class) {
                r = m.invoke(db, sql, args);
            } else if (pts.length == 1 && pts[0] == String.class) {
                r = m.invoke(db, sql);
            } else {
                return null;
            }
            if (r instanceof Cursor) return (Cursor) r;
            if (r != null) LogWriter.log(TAG, "invokeRaw: " + m.getName()
                    + " 返回非 Cursor 类型=" + r.getClass().getName());
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            String sig = java.util.Arrays.toString(m.getParameterTypes());
            LogWriter.log(TAG, "invokeRaw: " + m.getName() + sig + " 失败 "
                    + root.getClass().getName() + ": " + root.getMessage());
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
