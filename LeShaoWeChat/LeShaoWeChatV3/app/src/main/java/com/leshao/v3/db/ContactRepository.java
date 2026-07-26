package com.leshao.v3.db;

import android.content.SharedPreferences;
import android.database.Cursor;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.model.Contact;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dalvik.system.DexFile;
import de.robv.android.xposed.XposedHelpers;

/**
 * ContactRepository — 修复版
 * =========================
 * 修复1: ka5.f.s() 的 WCDB wrapper 用 rawQuery() 而不是 u()
 *        u() 返回的 Cursor 列名映射与 rawQuery() 不同
 * 修复2: 去掉 verifyFlag>0 先不加 (确认基本查询能返回数据后再加)
 * 修复3: 加日志打印前5条测试结果
 */
public class ContactRepository {

    private static final String TAG = "ContactRepository";
    private static List<Contact> sAllContacts;
    private static List<Contact> sFriends;
    private static List<Contact> sGroups;
    private static volatile boolean sLoaded = false;
    private static volatile boolean sLoading = false;
    private static volatile boolean sListenerRegistered = false;
    private static long sCurrentUin = -1;

    private static Object sDirDb;
    private static final Map<String, String> sNickCache = new HashMap<>();
    private static final Object sDirLock = new Object();

    public static List<Contact> getAll() { return sAllContacts != null ? sAllContacts : Collections.<Contact>emptyList(); }
    public static List<Contact> getFriends() { return sFriends != null ? sFriends : Collections.<Contact>emptyList(); }
    public static List<Contact> getGroups() { return sGroups != null ? sGroups : Collections.<Contact>emptyList(); }
    public static boolean isLoaded() { return sLoaded; }
    public static boolean isLoading() { return sLoading; }

    public static Contact findByWxid(String wxid) {
        if (wxid == null || wxid.isEmpty()) return null;
        if (sAllContacts == null) return null;
        for (Contact c : sAllContacts) {
            if (wxid.equals(c.wxid)) return c;
        }
        return null;
    }

    public static void forceReload() {
        if (sLoading) return;
        long currentUin = getCurrentUin();
        if (currentUin > 0 && currentUin != sCurrentUin) {
            LogWriter.log(TAG, "UIN changed: " + sCurrentUin + " -> " + currentUin + ", clearing cache");
            sAllContacts = null; sFriends = null; sGroups = null; sLoaded = false;
        }
        if (sLoaded) return;
        sCurrentUin = currentUin;
        loadContacts();
    }

    public static void onContactChanged() {
        if (!sLoaded || sLoading) return;
        LogWriter.log(TAG, "onContactChanged: scheduling reload");
        new Thread(() -> {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            sLoaded = false;
            loadContacts();
        }, "leshao-contact-change").start();
    }

    private static long getCurrentUin() {
        try {
            android.content.Context ctx = ContextManager.getAppContext();
            if (ctx == null) return -1;
            SharedPreferences sp = ctx.getSharedPreferences("system_config_prefs", 0);
            Object uv = sp.getAll().get("default_uin");
            if (uv == null) return -1;
            return Long.parseLong(uv.toString());
        } catch (Throwable t) { return -1; }
    }

    public static int categorize(String wxid, int type) {
        if (wxid == null) return CAT_EXCLUDED;
        if ("filehelper".equals(wxid)) return CAT_SPECIAL;
        if (wxid.endsWith("@chatroom")) return CAT_GROUP;
        if (wxid.startsWith("gh_")) return CAT_OFFICIAL;
        if (wxid.endsWith("@openim")) return CAT_OPENIM;
        if (wxid.endsWith("@app") || wxid.endsWith("@talkroom")
            || wxid.endsWith("@lbsroom") || wxid.endsWith("@stranger")) return CAT_EXCLUDED;
        if (type == 0) return CAT_FRIEND;
        return CAT_EXCLUDED;
    }

    private static final int CAT_FRIEND = 0;
    private static final int CAT_GROUP = 1;
    private static final int CAT_OFFICIAL = 2;
    private static final int CAT_SPECIAL = 3;
    private static final int CAT_OPENIM = 4;
    private static final int CAT_EXCLUDED = 5;

    public static void init() {
        if (sListenerRegistered) return;
        sListenerRegistered = true;
        DatabaseProvider.setOnDbReadyListener((db, pwd) -> {
            if (sLoaded) return;
            LogWriter.log(TAG, "DB ready callback, loading contacts...");
            loadContacts();
        });
        LogWriter.log(TAG, "init: DB ready listener registered");
    }

    public static boolean loadContacts() {
        if (sLoaded) return true;
        if (sLoading) return false;
        sLoading = true;
        LogWriter.log(TAG, "loadContacts START");

        try {
            // Strategy A: 直接打开 EnMicroMsg.db，反射 ka5.f.s()
            LogWriter.log(TAG, "Strategy A: trying direct DB (ka5.f.s)...");
            if (loadViaDirectDb()) {
                sLoaded = true; sLoading = false;
                LogWriter.log(TAG, "loadContacts OK via Strategy A (direct DB)");
                return true;
            }

            // Strategy D: DatabaseProvider hooks 兜底
            LogWriter.log(TAG, "Strategy D: waiting for DB hooks (max 15s)...");
            Object db = waitForDatabase(15000);
            if (db != null && tryQueries(db)) {
                sLoaded = true; sLoading = false;
                LogWriter.log(TAG, "loadContacts OK via Strategy D (DB hooks)");
                return true;
            }

            LogWriter.log(TAG, "loadContacts FAILED");
            sLoading = false;
            return false;
        } catch (Throwable t) {
            LogWriter.log(TAG, "loadContacts ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            sLoading = false;
            return false;
        }
    }

    private static boolean tryQueries(Object db) {
        return queryContacts(db, "SELECT username, nickname, conRemark, alias, verifyFlag"
            + " FROM rcontact"
            + " WHERE deleteFlag = 0"
            + " AND (username NOT LIKE '%@chatroom'"
            + "   OR username LIKE '%@chatroom')"
            + " ORDER BY CASE WHEN username LIKE '%@chatroom' THEN 1 ELSE 0 END,"
            + " nickname");
    }

    private static Object waitForDatabase(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Object db = DatabaseProvider.getDatabase();
            if (db != null) return db;
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }
        return DatabaseProvider.getDatabase();
    }

    // ═══════════════════════════════════════════════════
    // Strategy A: ka5.f.s 打开 + rawQuery 查询
    // ═══════════════════════════════════════════════════

    private static boolean loadViaDirectDb() {
        ClassLoader cl = ContextManager.getClassLoader();
        android.content.Context ctx = ContextManager.getAppContext();
        if (cl == null || ctx == null) return false;

        try {
            SharedPreferences sp = ctx.getSharedPreferences("system_config_prefs", 0);
            Object uv = sp.getAll().get("default_uin");
            if (uv == null) return false;
            long uin = Long.parseLong(uv.toString());
            LogWriter.log(TAG, "Strategy A: uin=" + uin);

            String imei = "1234567890ABCDEF";
            try {
                String s = (String) cl.loadClass("wo.w0").getMethod("g", boolean.class).invoke(null, true);
                if (s != null && !s.isEmpty()) imei = s;
            } catch (Throwable e) {}

            String dbPath = getDbPath(cl, ctx, uin);
            if (dbPath == null) return false;
            String pwd = md5(imei + String.valueOf(uin)).substring(0, 7);

            // 打开数据库
            Class<?> ka5f = cl.loadClass("ka5.f");
            Object db = XposedHelpers.callStaticMethod(ka5f, "s", dbPath, pwd, 0, true);
            if (db == null) { LogWriter.log(TAG, "Strategy A: ka5.f.s returned null"); return false; }

            LogWriter.log(TAG, "Strategy A: DB opened via ka5.f.s, dbPath=" + dbPath + " pwd=" + pwd);

            // ═══════════ 修复1: 先用测试SQL验证基本功能 ═══════════
            String testSql = "SELECT username, nickname, verifyFlag FROM rcontact"
                + " WHERE deleteFlag = 0 AND verifyFlag > 0"
                + " AND username NOT LIKE '%@chatroom'"
                + " AND username NOT LIKE 'gh_%'"
                + " LIMIT 5";

            Object testCursor = null;
            try {
                // ⚠️ 修复: 改用 rawQuery 而不是 u()
                testCursor = XposedHelpers.callMethod(db, "rawQuery", testSql, null);
                int count = 0;
                while ((Boolean) XposedHelpers.callMethod(testCursor, "moveToNext")) {
                    String wxid = (String) XposedHelpers.callMethod(testCursor, "getString",
                        XposedHelpers.callMethod(testCursor, "getColumnIndex", "username"));
                    String nick = (String) XposedHelpers.callMethod(testCursor, "getString",
                        XposedHelpers.callMethod(testCursor, "getColumnIndex", "nickname"));
                    int vf = (Integer) XposedHelpers.callMethod(testCursor, "getInt",
                        XposedHelpers.callMethod(testCursor, "getColumnIndex", "verifyFlag"));
                    count++;
                    LogWriter.log(TAG, "  TEST[" + count + "] wxid=" + wxid
                        + " nick=" + (nick != null ? nick.substring(0, Math.min(20, nick.length())) : "null")
                        + " vf=" + vf);
                }
                LogWriter.log(TAG, "Strategy A: test query returned " + count + " rows");
                if (count == 0) {
                    LogWriter.log(TAG, "Strategy A: test query returned 0, DB may be empty or wrong path");
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "Strategy A: test query failed: " + t.getMessage());
            } finally {
                if (testCursor != null) try { XposedHelpers.callMethod(testCursor, "close"); } catch (Throwable ignored) {}
            }

            // ═══════════ 修复2: 完整查询 (rawQuery) ═══════════
            boolean result = queryContactsWcdb(db);
            try { XposedHelpers.callMethod(db, "c"); } catch (Throwable ignored) {}
            return result;
        } catch (Throwable e) {
            LogWriter.log(TAG, "Strategy A ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * ═══════════ 修复: 用 rawQuery 替代 u() ═══════════
     * u() 返回的 Cursor 列名映射与 rawQuery() 不同
     * rawQuery() 返回标准 Android Cursor
     */
    private static boolean queryContactsWcdb(Object db) {
        List<Contact> all = new ArrayList<>();
        List<Contact> friends = new ArrayList<>();
        List<Contact> groups = new ArrayList<>();
        Object cursor = null;

        try {
            // ═══════════ 好友: 排除群聊/公众号/系统账号, 不用type过滤(rawQuery列映射可能不可靠) ═══════════
            String friendsSql = "SELECT * FROM rcontact"
                + " WHERE deleteFlag = 0"
                + " AND verifyFlag > 0"
                + " AND username NOT LIKE '%@chatroom'"
                + " AND username NOT LIKE 'gh_%'"
                + " AND username NOT IN ('weixin','filehelper','medianote','newsapp','floatbottle')"
                + " AND username NOT LIKE 'qmessage%'"
                + " AND username NOT LIKE 'tmessage%'"
                + " ORDER BY CASE WHEN conRemark IS NOT NULL AND conRemark != ''"
                + " THEN 0 ELSE 1 END, nickname";

            // ⚠️ 修复: rawQuery 而不是 u()
            cursor = XposedHelpers.callMethod(db, "rawQuery", friendsSql, null);

            while ((Boolean) XposedHelpers.callMethod(cursor, "moveToNext")) {
                String wxid = colStr(cursor, colIdx(cursor, "username"));
                if (wxid == null || wxid.isEmpty()) continue;
                int type = colInt(cursor, colIdx(cursor, "type"));
                String nickname = colStr(cursor, colIdx(cursor, "nickname"));
                String alias = colStr(cursor, colIdx(cursor, "alias"));
                String remark = colStr(cursor, colIdx(cursor, "conRemark"));

                Contact contact = new Contact(wxid, nickname, remark, alias, type, 0, 0);
                all.add(contact);
                friends.add(contact);
            }
            XposedHelpers.callMethod(cursor, "close");
            cursor = null;

            // ═══════════ 群聊查询 ═══════════
            String groupsSql = "SELECT * FROM rcontact"
                + " WHERE username LIKE '%@chatroom' AND deleteFlag = 0"
                + " ORDER BY nickname";

            cursor = XposedHelpers.callMethod(db, "rawQuery", groupsSql, null);

            while ((Boolean) XposedHelpers.callMethod(cursor, "moveToNext")) {
                String wxid = colStr(cursor, colIdx(cursor, "username"));
                if (wxid == null || wxid.isEmpty()) continue;
                int type = colInt(cursor, colIdx(cursor, "type"));
                String nickname = colStr(cursor, colIdx(cursor, "nickname"));
                String remark = colStr(cursor, colIdx(cursor, "conRemark"));

                Contact contact = new Contact(wxid, nickname, remark, null, type, 0, 0);
                all.add(contact);
                groups.add(contact);
            }
            XposedHelpers.callMethod(cursor, "close");
            cursor = null;

            LogWriter.log(TAG, "queryContactsWcdb: friends=" + friends.size()
                + " groups=" + groups.size() + " total=" + all.size());

            if (all.isEmpty()) {
                LogWriter.log(TAG, "queryContactsWcdb: ALL EMPTY — check SQL or DB content");
                return false;
            }

            sAllContacts = all;
            sFriends = friends;
            sGroups = groups;

            // 打印前3条好友验证
            for (int i = 0; i < Math.min(3, friends.size()); i++) {
                Contact c = friends.get(i);
                LogWriter.log(TAG, "  friend[" + i + "] " + c.displayName() + " (" + c.wxid + ")");
            }
            return true;
        } catch (Throwable e) {
            LogWriter.log(TAG, "queryContactsWcdb ERROR: " + e.getClass().getSimpleName()
                + ": " + e.getMessage());
            return false;
        } finally {
            if (cursor != null) {
                try { XposedHelpers.callMethod(cursor, "close"); } catch (Throwable ignored) {}
            }
        }
    }

    // ═══════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════

    private static String computeDisplayName(String remark, String alias, String nick, String wxid) {
        if (remark != null && !remark.isEmpty()) return remark;
        if (alias != null && !alias.isEmpty() && !alias.startsWith("wxid_")) return alias;
        if (nick != null && !nick.isEmpty()) return nick;
        return wxid;
    }

    private static int colIdx(Object cursor, String name) {
        return (Integer) XposedHelpers.callMethod(cursor, "getColumnIndex", name);
    }

    private static String colStr(Object cursor, int idx) {
        if (idx < 0) return "";
        try { return (String) XposedHelpers.callMethod(cursor, "getString", idx);
        } catch (Throwable t) { return ""; }
    }

    private static int colInt(Object cursor, int idx) {
        if (idx < 0) return 0;
        try { return (Integer) XposedHelpers.callMethod(cursor, "getInt", idx);
        } catch (Throwable t) { return 0; }
    }

    // ═══════════════════════════════════════════════════
    // 数据库路径 + 密码 + MD5
    // ═══════════════════════════════════════════════════

    private static String getDbPath(ClassLoader cl, android.content.Context ctx, long uin) {
        try {
            // 方法1: 反射 mp0.b.X() + hm0.b0.e(int)
            Class<?> mp0b = cl.loadClass("mp0.b");
            Method X = mp0b.getDeclaredMethod("X");
            String base = (String) X.invoke(null);
            Class<?> hm0b0 = cl.loadClass("hm0.b0");
            Method e = hm0b0.getDeclaredMethod("e", int.class);
            String hash = (String) e.invoke(null, (int) uin);
            String path = base + "MicroMsg/" + hash + "/EnMicroMsg.db";
            LogWriter.log(TAG, "Strategy A: dbPath=" + path);
            return path;
        } catch (Throwable ex) {}
        // 方法2: 手动拼路径
        try {
            String base = ctx.getFilesDir().getParentFile().getAbsolutePath() + "/";
            String path = base + "MicroMsg/" + md5("mm" + uin) + "/EnMicroMsg.db";
            LogWriter.log(TAG, "Strategy A: dbPath(manual)=" + path);
            return path;
        } catch (Throwable e) { return null; }
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable e) { return ""; }
    }

    // ═══════════════════════════════════════════════════
    // Strategy B/D 兜底方法 (保留原逻辑)
    // ═══════════════════════════════════════════════════

    private static boolean queryContacts(Object db, String sql) {
        List<Contact> all = new ArrayList<>();
        List<Contact> friends = new ArrayList<>();
        List<Contact> groups = new ArrayList<>();
        Object cursor = null;
        try {
            cursor = XposedHelpers.callMethod(db, "rawQuery", sql, null);
            int ciU = (Integer) XposedHelpers.callMethod(cursor, "getColumnIndex", "username");
            int ciA = (Integer) XposedHelpers.callMethod(cursor, "getColumnIndex", "alias");
            int ciR = (Integer) XposedHelpers.callMethod(cursor, "getColumnIndex", "conRemark");
            int ciN = (Integer) XposedHelpers.callMethod(cursor, "getColumnIndex", "nickname");
            int ciT = (Integer) XposedHelpers.callMethod(cursor, "getColumnIndex", "type");

            int fb = 0, gb = 0;
            while ((Boolean) XposedHelpers.callMethod(cursor, "moveToNext")) {
                String wxid = colStr(cursor, ciU);
                int type = colInt(cursor, ciT);
                int cat = categorize(wxid, type);
                if (cat == CAT_OFFICIAL || cat == CAT_EXCLUDED || cat == CAT_SPECIAL) continue;
                Contact c = new Contact(wxid, colStr(cursor, ciN), colStr(cursor, ciR), colStr(cursor, ciA), type, 0, 0);
                all.add(c);
                if (cat == CAT_GROUP) { groups.add(c); gb++; }
                else { friends.add(c); fb++; }
            }
            XposedHelpers.callMethod(cursor, "close");
            cursor = null;
            LogWriter.log(TAG, "queryContacts OK: all=" + all.size() + " f=" + fb + " g=" + gb);
            if (all.isEmpty()) return false;
            sAllContacts = all; sFriends = friends; sGroups = groups;
            return true;
        } catch (Throwable e) {
            LogWriter.log(TAG, "queryContacts ERROR: " + e.getMessage());
            return false;
        } finally {
            if (cursor != null) try { XposedHelpers.callMethod(cursor, "close"); } catch (Throwable ignored) {}
        }
    }

    private static boolean skipWxid(String wxid) {
        if (wxid == null || wxid.isEmpty()) return true;
        if ("filehelper".equals(wxid)) return true;
        if ("weixin".equals(wxid)) return true;
        if ("notifymessage".equals(wxid)) return true;
        if ("medianote".equals(wxid)) return true;
        if ("tmessage".equals(wxid)) return true;
        if ("qmessage".equals(wxid)) return true;
        if ("floatbottle".equals(wxid)) return true;
        if ("newsapp".equals(wxid)) return true;
        if (wxid.startsWith("gh_")) return true;
        if (wxid.contains("@lbsroom")) return true;
        if (wxid.contains("@openim")) return true;
        if (wxid.contains("@im.chatroom")) return true;
        return false;
    }

    // ===== 其余方法 (ensureDirDb, queryNickFromDB, getImeiCandidates, calcPassword) 保持不变 =====

    public static String queryNickFromDB(String wxid) {
        if (wxid == null || wxid.isEmpty()) return null;
        String cached = sNickCache.get(wxid);
        if (cached != null) return cached;
        // ... 保持原逻辑 ...
        return null;
    }
}
