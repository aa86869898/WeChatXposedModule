package com.leshao.v3.ai;

import android.content.Context;
import android.database.Cursor;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.VersionCompat;

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
            String imei = VersionCompat.getImei(cl);
            String password = md5(imei + uin).substring(0, 7);
            String base = VersionCompat.getBaseDir(cl, ctx);
            String hash = VersionCompat.getDbHash(cl, (int) uin);
            String dbPath = base + "MicroMsg/" + hash + "/EnMicroMsg.db";
            Class<?> dbOpener = VersionCompat.findDbOpenerClass(cl);
            if (dbOpener == null) { LogWriter.log(TAG, "openDb: dbOpener 类未找到"); return null; }
            Object db = VersionCompat.openDatabase(dbOpener, dbPath, password);
            if (db == null) LogWriter.log(TAG, "openDb: openDatabase 返回 null path=" + dbPath);
            return db;
        } catch (Throwable t) {
            LogWriter.log(TAG, "openDb 异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    private static Cursor rawQuery(Object db, String sql, String[] args) {
        for (Method m : db.getClass().getMethods()) {
            if (m.getName().equals("rawQuery") && m.getParameterCount() >= 1
                    && m.getParameterTypes()[0] == String.class) {
                try { m.setAccessible(true); return (Cursor) m.invoke(db, sql, args); }
                catch (Throwable ignored) {}
            }
        }
        for (Method m : db.getClass().getDeclaredMethods()) {
            if (m.getName().equals("rawQuery") && m.getParameterCount() >= 1
                    && m.getParameterTypes()[0] == String.class) {
                try { m.setAccessible(true); return (Cursor) m.invoke(db, sql, args); }
                catch (Throwable ignored) {}
            }
        }
        LogWriter.log(TAG, "rawQuery: 未找到 rawQuery 方法");
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
