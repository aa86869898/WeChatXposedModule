package com.leshao.v3.db;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

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
            sAllContacts = null;
            sFriends = null;
            sGroups = null;
            sLoaded = false;
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
        if (type == 1 || type == 2 || type == 3 || type == 4) return CAT_EXCLUDED;
        return CAT_EXCLUDED;
    }

    private static final int CAT_FRIEND = 0;
    private static final int CAT_GROUP = 1;
    private static final int CAT_OFFICIAL = 2;
    private static final int CAT_SPECIAL = 3;
    private static final int CAT_OPENIM = 4;
    private static final int CAT_EXCLUDED = 5;
    private static final int CAT_SYSTEM = 6;
    private static final int CAT_STRANGER = 7;
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
            // Strategy A: 暂跳过（rcontact type=4非双向好友），先试WeChat自身API
            // if (loadViaDirectDb()) { ... }

            // Strategy B: 反射遍历 model.aj（纯内存，零延迟，无需 Hook）
            LogWriter.log(TAG, "Strategy B: trying model.aj reflection...");
            if (loadViaModelAj()) {
                sLoaded = true; sLoading = false;
                LogWriter.log(TAG, "loadContacts OK via Strategy B (model.aj reflection)");
                return true;
            }

            // Strategy C: Messaging plugin via findKernelClass
            LogWriter.log(TAG, "Strategy C: trying findKernelClass...");
            if (loadViaMessagingPlugin()) {
                sLoaded = true; sLoading = false;
                LogWriter.log(TAG, "loadContacts OK via Strategy C (messaging plugin)");
                return true;
            }

            // Strategy A: 直接打开 EnMicroMsg.db（兜底）
            LogWriter.log(TAG, "Strategy A: trying direct DB (ka5.f.s)...");
            if (loadViaDirectDb()) {
                sLoaded = true; sLoading = false;
                LogWriter.log(TAG, "loadContacts OK via Strategy A (direct DB)");
                return true;
            }

            // Strategy D: DatabaseProvider hooks（最终兜底）
            LogWriter.log(TAG, "Strategy D: waiting for DB hooks (max 15s)...");
            Object db = waitForDatabase(15000);
            LogWriter.log(TAG, "Strategy D: waitForDatabase returned " + (db != null ? "DB" : "null"));
            if (db != null && tryQueries(db)) {
                sLoaded = true; sLoading = false;
                LogWriter.log(TAG, "loadContacts OK via Strategy D (DB hooks)");
                return true;
            }

            LogWriter.log(TAG, "loadContacts FAILED: all strategies exhausted");
            sLoading = false;
            return false;
        } catch (Throwable t) {
            LogWriter.log(TAG, "loadContacts ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            sLoading = false;
            return false;
        }
    }

    private static boolean tryQueries(Object db) {
        return queryContacts(db, "SELECT username, nickname, conRemark, alias, type, verifyFlag"
            + " FROM rcontact"
            + " WHERE deleteFlag = 0"
            + " AND (type = 0 OR username LIKE '%@chatroom')"
            + " AND (type != 0 OR verifyFlag > 0)"
            + " ORDER BY CASE WHEN type=0 THEN 0 ELSE 1 END,"
            + " CASE WHEN username LIKE '%@chatroom' THEN 1 ELSE 0 END,"
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

    // ===== Strategy A: ka5.f.s 打开 WCDB 加密数据库，type=4好友 =====

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

            // 1. IMEI
            String imei = "1234567890ABCDEF";
            try {
                String s = (String) cl.loadClass("wo.w0").getMethod("g", boolean.class).invoke(null, true);
                if (s != null && !s.isEmpty() && !"1234567890ABCDEF".equals(s)) imei = s;
            } catch (Throwable e) {}
            LogWriter.log(TAG, "Strategy A: imei=" + imei);

            // 2. dbPath: mp0.b.X() + MicroMsg/ + hm0.b0.e(int uin) + /EnMicroMsg.db
            String dbPath = getDbPath(cl, ctx, uin);
            if (dbPath == null) return false;
            LogWriter.log(TAG, "Strategy A: dbPath=" + dbPath);

            // 3. Password: md5(imei + uin).substring(0, 7)
            String pwd = md5(imei + String.valueOf(uin)).substring(0, 7);
            LogWriter.log(TAG, "Strategy A: pwd(censored) calculated, opening...");

            // 4. Open via ka5.f.s(String dbPath, String pwd, int flags, boolean)
            Object rawDb = null;
            try {
                Method sMethod = cl.loadClass("ka5.f").getMethod("s",
                    String.class, String.class, int.class, boolean.class);
                rawDb = sMethod.invoke(null, dbPath, pwd, 0, true);
            } catch (Throwable e) {
                LogWriter.log(TAG, "Strategy A: ka5.f.s failed: " + e.getMessage());
                return false;
            }
            if (rawDb == null) { LogWriter.log(TAG, "Strategy A: ka5.f.s returned null"); return false; }

            // 5. Validate: check if rcontact exists
            try {
                Object checkCursor = rawDb.getClass().getMethod("u", String.class, String[].class)
                    .invoke(rawDb, "SELECT name FROM sqlite_master WHERE type='table' AND name='rcontact'", null);
                boolean ok = (Boolean) checkCursor.getClass().getMethod("moveToFirst").invoke(checkCursor);
                checkCursor.getClass().getMethod("close").invoke(checkCursor);
                if (!ok) { LogWriter.log(TAG, "Strategy A: rcontact table not found"); return false; }
            } catch (Throwable e) {
                LogWriter.log(TAG, "Strategy A: validate failed: " + e.getMessage());
                return false;
            }

            LogWriter.log(TAG, "Strategy A: DB opened via ka5.f.s, querying...");
            boolean result = queryContactsWcdb(rawDb);
            closeWcdb(rawDb);
            return result;
        } catch (Throwable e) {
            LogWriter.log(TAG, "Strategy A ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    private static void closeWcdb(Object db) {
        try { db.getClass().getMethod("c").invoke(db); } catch (Throwable ignored) {}
    }

    private static boolean queryContactsWcdb(Object db) {
        List<Contact> all = new ArrayList<>();
        List<Contact> friends = new ArrayList<>();
        List<Contact> groups = new ArrayList<>();

        try {
            // 诊断: 查询FriendUser, contact, verifycontact, friend_ext
            String[] diagTables = {"FriendUser", "contact", "verifycontact", "friend_ext", "rcontact"};
            for (String tbl : diagTables) {
                try {
                    String cntSql = "SELECT COUNT(*) FROM " + tbl;
                    Object c1 = db.getClass().getMethod("u", String.class, String[].class).invoke(db, cntSql, null);
                    if ((Boolean) c1.getClass().getMethod("moveToFirst").invoke(c1)) {
                        int cnt = (Integer) c1.getClass().getMethod("getInt", int.class).invoke(c1, 0);
                        LogWriter.log(TAG, "TABLE " + tbl + " rows=" + cnt);
                    }
                    c1.getClass().getMethod("close").invoke(c1);
                } catch (Throwable e) {
                    LogWriter.log(TAG, "TABLE " + tbl + " skip");
                }
            }
            // FriendUser schema + 3 sample rows
            try {
                String sq = "SELECT sql FROM sqlite_master WHERE type='table' AND name='FriendUser'";
                Object sc = db.getClass().getMethod("u", String.class, String[].class).invoke(db, sq, null);
                if ((Boolean) sc.getClass().getMethod("moveToFirst").invoke(sc)) {
                    LogWriter.log(TAG, "FriendUser schema: " + trunc((String) sc.getClass().getMethod("getString", int.class).invoke(sc, 0), 300));
                }
                sc.getClass().getMethod("close").invoke(sc);
            } catch (Throwable e) { LogWriter.log(TAG, "FriendUser schema skip"); }
            try {
                Object rc = db.getClass().getMethod("u", String.class, String[].class).invoke(db, "SELECT * FROM FriendUser LIMIT 5", null);
                int cu = (Integer) rc.getClass().getMethod("getColumnIndex", String.class).invoke(rc, "username");
                int cn = (Integer) rc.getClass().getMethod("getColumnIndex", String.class).invoke(rc, "nickname");
                int i = 0;
                while ((Boolean) rc.getClass().getMethod("moveToNext").invoke(rc) && ++i <= 3) {
                    LogWriter.log(TAG, "  F" + i + " u=" + strFromCursor(rc, cu) + " n=" + trunc(strFromCursor(rc, cn), 16));
                }
                rc.getClass().getMethod("close").invoke(rc);
            } catch (Throwable e) { LogWriter.log(TAG, "FriendUser sample skip: " + e.getMessage()); }

            // 好友: 不做verifyFlag过滤，全部type=4都取，Java侧过滤
            String friendsSql = "SELECT username, nickname, alias, conRemark, type, verifyFlag, showHead"
                + " FROM rcontact"
                + " WHERE type = 4 AND deleteFlag = 0"
                + " ORDER BY CASE WHEN conRemark IS NOT NULL AND conRemark != '' THEN 0 ELSE 1 END,"
                + " nickname"
                + " LIMIT 500";

            Object cursor = db.getClass().getMethod("u", String.class, String[].class)
                .invoke(db, friendsSql, null);

            int ciU = (Integer) cursor.getClass().getMethod("getColumnIndex", String.class).invoke(cursor, "username");
            int ciN = (Integer) cursor.getClass().getMethod("getColumnIndex", String.class).invoke(cursor, "nickname");
            int ciA = (Integer) cursor.getClass().getMethod("getColumnIndex", String.class).invoke(cursor, "alias");
            int ciR = (Integer) cursor.getClass().getMethod("getColumnIndex", String.class).invoke(cursor, "conRemark");
            int ciT = (Integer) cursor.getClass().getMethod("getColumnIndex", String.class).invoke(cursor, "type");
            int ciV = (Integer) cursor.getClass().getMethod("getColumnIndex", String.class).invoke(cursor, "verifyFlag");
            int ciSh = -1;
            try { ciSh = (Integer) cursor.getClass().getMethod("getColumnIndex", String.class).invoke(cursor, "showHead"); } catch (Throwable ignored) {}

            while ((Boolean) cursor.getClass().getMethod("moveToNext").invoke(cursor)) {
                String wxid = strFromCursor(cursor, ciU);
                if (wxid == null || wxid.isEmpty()) continue;
                String nickname = strFromCursor(cursor, ciN);
                String alias = strFromCursor(cursor, ciA);
                String remark = strFromCursor(cursor, ciR);
                int type = intFromCursor(cursor, ciT);
                int verifyFlag = intFromCursor(cursor, ciV);
                int showHead = ciSh >= 0 ? intFromCursor(cursor, ciSh) : 32;

                if (skipWxid(wxid)) continue;

                Contact contact = new Contact(wxid, nickname, remark, alias, type, 0, 0);
                contact.verifyFlag = verifyFlag;
                all.add(contact);
                friends.add(contact);
            }
            cursor.getClass().getMethod("close").invoke(cursor);
            cursor = null;

            LogWriter.log(TAG, "queryContactsWcdb friends=" + friends.size());

            // 群聊: username LIKE '%@chatroom'
            String groupsSql = "SELECT username, nickname, conRemark, type, createTime"
                + " FROM rcontact"
                + " WHERE username LIKE '%@chatroom' AND deleteFlag = 0"
                + " ORDER BY nickname ASC";

            cursor = db.getClass().getMethod("u", String.class, String[].class)
                .invoke(db, groupsSql, null);

            ciU = (Integer) cursor.getClass().getMethod("getColumnIndex", String.class).invoke(cursor, "username");
            ciN = (Integer) cursor.getClass().getMethod("getColumnIndex", String.class).invoke(cursor, "nickname");
            ciR = (Integer) cursor.getClass().getMethod("getColumnIndex", String.class).invoke(cursor, "conRemark");
            ciT = (Integer) cursor.getClass().getMethod("getColumnIndex", String.class).invoke(cursor, "type");

            while ((Boolean) cursor.getClass().getMethod("moveToNext").invoke(cursor)) {
                String wxid = strFromCursor(cursor, ciU);
                if (wxid == null || wxid.isEmpty()) continue;
                String nickname = strFromCursor(cursor, ciN);
                String remark = strFromCursor(cursor, ciR);
                int type = intFromCursor(cursor, ciT);

                String display = computeDisplayName(remark, null, nickname, wxid);
                Contact contact = new Contact(wxid, nickname, remark, null, type, 0, 0);
                all.add(contact);
                groups.add(contact);
            }
            cursor.getClass().getMethod("close").invoke(cursor);

            LogWriter.log(TAG, "queryContactsWcdb groups=" + groups.size() + " total=" + all.size());

            if (all.isEmpty()) return false;
            sAllContacts = all;
            sFriends = friends;
            sGroups = groups;
            return true;
        } catch (Throwable e) {
            LogWriter.log(TAG, "queryContactsWcdb ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    private static String strFromCursor(Object cursor, int index) throws Exception {
        return (String) cursor.getClass().getMethod("getString", int.class).invoke(cursor, index);
    }
    private static int intFromCursor(Object cursor, int index) throws Exception {
        return (Integer) cursor.getClass().getMethod("getInt", int.class).invoke(cursor, index);
    }
    private static String trunc(String s, int maxLen) {
        if (s == null) return "null";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    private static String computeDisplayName(String remark, String alias, String nick, String wxid) {
        if (remark != null && !remark.isEmpty()) return remark;
        if (alias != null && !alias.isEmpty() && !alias.startsWith("wxid_")) return alias;
        if (nick != null && !nick.isEmpty()) return nick;
        return wxid;
    }

    private static String[] getImeiCandidates(ClassLoader cl) {
        java.util.List<String> list = new java.util.ArrayList<>();
        // 候选1: wo.w0.g(true) — 设备 IMEI
        try {
            Class<?> wo = cl.loadClass("wo.w0");
            Method g = wo.getDeclaredMethod("g", boolean.class);
            String imei = (String) g.invoke(null, true);
            if (imei != null && !imei.isEmpty() && !imei.equals("1234567890ABCDEF")) {
                list.add(imei);
            }
        } catch (Throwable e) {}
        // 候选2: 微信硬编码兜底密码
        list.add("1234567890ABCDEF");
        // 候选3: IMEI 兜底值
        list.add("000000000000000");
        list.add("");
        return list.toArray(new String[0]);
    }

    private static String calcPassword(ClassLoader cl, String imei, long uin) {
        String input = imei + uin;
        // 方法1: 反射 kk.k.g(byte[]) 计算 MD5
        try {
            Class<?> kk = cl.loadClass("kk.k");
            Method g = kk.getDeclaredMethod("g", byte[].class);
            String full = (String) g.invoke(null, (Object) input.getBytes("UTF-8"));
            return full.substring(0, 7);
        } catch (Throwable e) {}
        // 方法2: JDK MD5 兜底
        return md5(input).substring(0, 7);
    }

    private static String getDbPath(ClassLoader cl, android.content.Context ctx, long uin) {
        try {
            // 方法1: 反射 mp0.b.X() + hm0.b0.e(int)
            Class<?> mp0b = cl.loadClass("mp0.b");
            Method X = mp0b.getDeclaredMethod("X");
            String base = (String) X.invoke(null);

            Class<?> hm0b0 = cl.loadClass("hm0.b0");
            Method e = hm0b0.getDeclaredMethod("e", int.class);
            String hash = (String) e.invoke(null, (int) uin);

            return base + "MicroMsg/" + hash + "/EnMicroMsg.db";
        } catch (Throwable ex) {}
        // 方法2: 手动拼接路径
        try {
            String base = ctx.getFilesDir().getParentFile().getAbsolutePath() + "/";
            return base + "MicroMsg/" + md5("mm" + uin) + "/EnMicroMsg.db";
        } catch (Throwable e) {
            return null;
        }
    }

    private static Object openKa5Db(ClassLoader cl, String dbPath, String password) {
        try {
            Class<?> ka5f = cl.loadClass("ka5.f");
            Method s = ka5f.getDeclaredMethod("s",
                    String.class, String.class, int.class, boolean.class);
            Object db = s.invoke(null, dbPath, password, 0, true);
            if (db == null) return null;

            // 验证：查询 sqlite_master 确认 rcontact 表存在
            Method u = db.getClass().getDeclaredMethod("u", String.class, String[].class);
            Cursor c = (Cursor) u.invoke(db,
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='rcontact'",
                    null);
            if (c != null && c.moveToFirst()) {
                c.close();
                LogWriter.log(TAG, "Strategy A: DB opened, rcontact table confirmed");
                return db;
            }
            if (c != null) c.close();
            // 密码不对，关闭这个库
            try { db.getClass().getMethod("c").invoke(db); } catch (Throwable ignored) {}
        } catch (Throwable e) {
            LogWriter.log(TAG, "Strategy A: openKa5Db failed: " + e.getClass().getSimpleName());
        }
        return null;
    }

    // ===== 好友过滤: type=0=好友, type=2=被删, type=4=拉黑 =====

    private static boolean queryViaKa5(Object db) {
        try {
            Method u = db.getClass().getDeclaredMethod("u", String.class, String[].class);

            String sql = "SELECT username, alias, conRemark, nickname, type, createTime"
                + " FROM rcontact"
                + " WHERE deleteFlag = 0"
                + " AND type = 0"
                + " ORDER BY CASE WHEN username LIKE '%@chatroom' THEN 1 ELSE 0 END, nickname";
            Cursor c = (Cursor) u.invoke(db, sql, null);
            if (c == null) return false;

            List<Contact> all = new ArrayList<>();
            List<Contact> friends = new ArrayList<>();
            List<Contact> groups = new ArrayList<>();

            int ciU = c.getColumnIndex("username");
            int ciA = c.getColumnIndex("alias");
            int ciR = c.getColumnIndex("conRemark");
            int ciN = c.getColumnIndex("nickname");
            int ciT = c.getColumnIndex("type");
            int ciCr = c.getColumnIndex("createTime");

            while (c.moveToNext()) {
                String wxid = c.getString(ciU);
                if (wxid == null || wxid.isEmpty()) continue;
                int type = c.getInt(ciT);

                int cat = categorize(wxid, type);
                if (cat == CAT_OFFICIAL || cat == CAT_EXCLUDED || cat == CAT_SPECIAL) continue;

                String name = c.getString(ciR);
                if (name == null || name.isEmpty()) name = c.getString(ciA);
                if (name == null || name.isEmpty()) name = c.getString(ciN);
                if (name == null || name.isEmpty()) name = wxid;

                long createTime = ciCr >= 0 ? c.getLong(ciCr) : 0;
                Contact contact = new Contact(wxid, name, name, wxid, type, 0, createTime);
                all.add(contact);
                if (cat == CAT_GROUP) groups.add(contact);
                else friends.add(contact);
            }
            c.close();

            LogWriter.log(TAG, "Strategy A: query OK, all=" + all.size()
                + " f=" + friends.size() + " g=" + groups.size());

            if (all.isEmpty()) return false;
            sAllContacts = all; sFriends = friends; sGroups = groups;
            return true;
        } catch (Throwable e) {
            LogWriter.log(TAG, "Strategy A: query ERROR: " + e.getClass().getSimpleName()
                + ": " + e.getMessage());
            return false;
        }
    }

    private static void closeKa5Db(Object db) {
        try {
            Method c = db.getClass().getDeclaredMethod("c");
            c.invoke(db);
        } catch (Throwable ignored) {}
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

    // ===== Strategy B: 反射遍历 com.tencent.mm.model.aj 自动发现方法（无需 Hook，零延迟）=====

    private static boolean loadViaModelAj() {
        ClassLoader cl = ContextManager.getClassLoader();
        if (cl == null) return false;

        try {
            Class<?> ajCls = cl.loadClass("com.tencent.mm.model.aj");
            if (ajCls == null) {
                LogWriter.log(TAG, "Strategy B: class com.tencent.mm.model.aj not found");
                return false;
            }

            // 遍历所有静态方法，逐个尝试调用，找到返回会话存储对象的方法
            java.lang.reflect.Method[] methods = ajCls.getDeclaredMethods();
            for (java.lang.reflect.Method m : methods) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterTypes().length != 0) continue;
                Class<?> rt = m.getReturnType();
                if (rt == void.class || rt == int.class || rt == long.class
                    || rt == boolean.class || rt == String.class || rt.isPrimitive()) continue;

                Object convStg;
                try {
                    convStg = m.invoke(null);
                } catch (Throwable e) { continue; }
                if (convStg == null) continue;

                // 检查这个对象是否有 getAll/values/getMap 之类的方法来获取全部会话
                Object allConvs = discoverGetAll(convStg);
                if (allConvs == null) continue;

                // 尝试从会话中提取联系人
                List<Contact> all = new ArrayList<>();
                List<Contact> friends = new ArrayList<>();
                List<Contact> groups = new ArrayList<>();

                if (allConvs instanceof Map) {
                    Map<?, ?> convMap = (Map<?, ?>) allConvs;
                    LogWriter.log(TAG, "Strategy B: found via " + m.getName() + "(), Map size=" + convMap.size());
                    for (Object conv : convMap.values()) {
                        addConvEntry(conv, all, friends, groups);
                    }
                } else if (allConvs instanceof Iterable) {
                    int cnt = 0;
                    for (Object conv : (Iterable<?>) allConvs) {
                        if (addConvEntry(conv, all, friends, groups)) cnt++;
                    }
                    LogWriter.log(TAG, "Strategy B: found via " + m.getName() + "(), Iterable size=" + cnt);
                } else {
                    continue;
                }

                if (all.isEmpty()) continue;
                sAllContacts = all; sFriends = friends; sGroups = groups;
                LogWriter.log(TAG, "Strategy B OK: all=" + all.size() + " f=" + friends.size() + " g=" + groups.size()
                    + " via method=" + m.getName());
                return true;
            }

            LogWriter.log(TAG, "Strategy B: tried " + methods.length + " static methods, none returned valid conv storage");
            return false;
        } catch (Throwable e) {
            LogWriter.log(TAG, "Strategy B ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    private static Object discoverGetAll(Object obj) {
        // 先检查常见方法名
        for (String mn : new String[]{"getAll", "bLw", "aOB", "values", "getMap",
            "bLx", "bLy", "aOC", "aOD", "getValues", "toMap", "asMap", "entrySet"}) {
            try {
                Object result = XposedHelpers.callMethod(obj, mn);
                if (result != null && (result instanceof Map || result instanceof Iterable)) {
                    return result;
                }
            } catch (Throwable e) {}
        }

        // 遍历所有无参方法
        for (java.lang.reflect.Method m : obj.getClass().getDeclaredMethods()) {
            if (m.getParameterTypes().length != 0) continue;
            Class<?> rt = m.getReturnType();
            if (rt == void.class || rt.isPrimitive()) continue;
            try {
                Object result = m.invoke(obj);
                if (result instanceof Map) return result;
                if (result instanceof Iterable && ((Iterable<?>) result).iterator().hasNext()) return result;
            } catch (Throwable e) {}
        }
        return null;
    }

    private static boolean addConvEntry(Object conv, List<Contact> all, List<Contact> friends, List<Contact> groups) {
        try {
            String wxid = resolveObjWxid(conv);
            if (skipWxid(wxid)) return false;

            if (!wxid.endsWith("@chatroom")) return false;

            String name = resolveObjName(conv);
            if (name == null || name.isEmpty()) name = wxid;

            Contact c = new Contact(wxid, name, name, wxid, 1);
            all.add(c);
            groups.add(c);
            return true;
        } catch (Throwable e) { return false; }
    }

    // ===== Strategy C: Messaging plugin via findKernelClass (V21 Strategy 3) =====

    private static boolean loadViaMessagingPlugin() {
        ClassLoader cl = ContextManager.getClassLoader();
        if (cl == null) return false;

        try {
            Class<?> kernelCls = findKernelClass();
            if (kernelCls == null) {
                LogWriter.log(TAG, "Strategy C: kernel class not found");
                return false;
            }

            String[] msgPluginClasses = {
                "com.tencent.mm.plugin.messenger.foundation.a.n",
                "com.tencent.mm.plugin.messenger.foundation.a.m",
                "com.tencent.mm.plugin.messenger.foundation.a$n",
                "com.tencent.mm.plugin.messenger.foundation.a$m",
            };
            Object msgSvc = null;
            for (String pcn : msgPluginClasses) {
                try {
                    Class<?> pcls = cl.loadClass(pcn);
                    msgSvc = XposedHelpers.callStaticMethod(kernelCls, "ax", pcls);
                    break;
                } catch (Throwable e) {}
            }
            if (msgSvc == null) {
                LogWriter.log(TAG, "Strategy C: msg service not found");
                return false;
            }

            Object convStg = null;
            for (String mn : new String[]{"getConversationStg", "bLx", "bLy", "aOC", "getConvStorage"}) {
                try { convStg = XposedHelpers.callMethod(msgSvc, mn); break; }
                catch (Throwable e) {}
            }
            if (convStg == null) {
                LogWriter.log(TAG, "Strategy C: conv storage not found");
                return false;
            }

            Object allConvs = null;
            for (String mn : new String[]{"getAll", "bLw", "aOB", "values", "getMap"}) {
                try { allConvs = XposedHelpers.callMethod(convStg, mn); break; }
                catch (Throwable e) {}
            }
            if (allConvs == null) {
                LogWriter.log(TAG, "Strategy C: allConvs not found");
                return false;
            }

            List<Contact> all = new ArrayList<>();
            List<Contact> friends = new ArrayList<>();
            List<Contact> groups = new ArrayList<>();

            if (allConvs instanceof Map) {
                Map<?, ?> convMap = (Map<?, ?>) allConvs;
                LogWriter.log(TAG, "Strategy C: Map count=" + convMap.size());
                for (Object conv : convMap.values()) {
                    addConvEntry(conv, all, friends, groups);
                }
            } else {
                LogWriter.log(TAG, "Strategy C: unsupported allConvs type: " + allConvs.getClass().getName());
                return false;
            }

            if (all.isEmpty()) return false;
            sAllContacts = all; sFriends = friends; sGroups = groups;
            LogWriter.log(TAG, "Strategy C OK: all=" + all.size() + " f=" + friends.size() + " g=" + groups.size());
            return true;
        } catch (Throwable e) {
            LogWriter.log(TAG, "Strategy C ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    private static Class<?> sCachedKernelClass = null;

    private static Class<?> findKernelClass() {
        if (sCachedKernelClass != null) return sCachedKernelClass;

        ClassLoader cl = ContextManager.getClassLoader();
        String apkPath = ContextManager.getApkPath();
        if (cl == null) return null;

        String[] names = {
            "h","g","i","j","f","e","d","c","b","a",
            "k","l","m","n","o","p","q","r","s","t","u","v","w","x","y","z",
            "aa","ab","ac","ad","ae","af","ag","ah",
            "Core","Kernel","MMCore","MMKernel","App","MMApp",
            "kernel","plugin","service","Platform","SdkPlatform",
        };
        String[] pkgs = {"com.tencent.mm.kernel.", "com.tencent.mm.app."};

        for (String pkg : pkgs) {
            for (String name : names) {
                try {
                    Class<?> c = cl.loadClass(pkg + name);
                    for (Method m : c.getDeclaredMethods()) {
                        if (Modifier.isStatic(m.getModifiers())
                            && m.getParameterTypes().length == 1
                            && m.getParameterTypes()[0] == Class.class) {
                            sCachedKernelClass = c;
                            LogWriter.log(TAG, "findKernel: FOUND " + pkg + name + " method=" + m.getName());
                            return c;
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }

        if (apkPath != null) {
            try {
                DexFile df = new DexFile(apkPath);
                Enumeration<String> entries = df.entries();
                int scanned = 0;
                while (entries.hasMoreElements()) {
                    String cn = entries.nextElement();
                    if (cn.startsWith("com.tencent.mm.kernel.") || cn.startsWith("com.tencent.mm.app.")) {
                        scanned++;
                        try {
                            Class<?> c = df.loadClass(cn, cl);
                            for (Method m : c.getDeclaredMethods()) {
                                if (Modifier.isStatic(m.getModifiers())
                                    && m.getParameterTypes().length == 1
                                    && m.getParameterTypes()[0] == Class.class) {
                                    sCachedKernelClass = c;
                                    LogWriter.log(TAG, "findKernel: DexFile FOUND " + cn + " method=" + m.getName());
                                    df.close();
                                    return c;
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                df.close();
                LogWriter.log(TAG, "findKernel: DexFile scanned=" + scanned + " no match");
            } catch (Throwable e) {
                LogWriter.log(TAG, "findKernel: DexFile error: " + e.getMessage());
            }
        }
        return null;
    }

    // ===== SQL 查询 =====

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
            LogWriter.log(TAG, "queryContacts columns: u=" + ciU + " r=" + ciR + " n=" + ciN
                + " a=" + ciA + " t=" + ciT);

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
            LogWriter.log(TAG, "queryContacts ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        } finally {
            if (cursor != null) {
                try { XposedHelpers.callMethod(cursor, "close"); } catch (Throwable ignored) {}
            }
        }
    }

    // ===== 工具方法 =====

    private static String resolveObjWxid(Object obj) {
        if (obj == null) return null;
        // 先尝试常见名称
        String[] getters = {"getWxid", "getUsername", "getChatRoomName", "getChatroomName",
            "getRoomId", "getTalker", "bLp", "aOM", "getWxId", "field_username",
            "getWxusername", "getStrangerName", "getEncryptUsername"};
        for (String mn : getters) {
            try { String r = (String) obj.getClass().getMethod(mn).invoke(obj);
                if (r != null && !r.isEmpty()) return r; }
            catch (Throwable e) {}
        }
        // 遍历所有返回 String 的无参方法
        for (java.lang.reflect.Method m : obj.getClass().getDeclaredMethods()) {
            if (m.getParameterTypes().length != 0) continue;
            if (m.getReturnType() != String.class) continue;
            try {
                String r = (String) m.invoke(obj);
                if (r != null && !r.isEmpty() && r.length() > 4) {
                    // wxid 特征: 包含 @chatroom 或长度适中
                    if (r.contains("@") || (r.length() >= 6 && r.length() <= 64)) return r;
                }
            } catch (Throwable e) {}
        }
        return null;
    }

    private static String resolveObjName(Object obj) {
        if (obj == null) return null;
        String[] getters = {"getRemarkName", "getNickname", "getDisplayName", "getConRemark",
            "getName", "getShowName", "getAlias", "field_nickname", "field_conRemark"};
        for (String mn : getters) {
            try {
                Object r = obj.getClass().getMethod(mn).invoke(obj);
                if (r instanceof String && !((String) r).isEmpty()) return (String) r;
            } catch (Throwable e) {}
        }
        // 遍历所有返回 String 的无参方法
        for (java.lang.reflect.Method m : obj.getClass().getDeclaredMethods()) {
            if (m.getParameterTypes().length != 0) continue;
            if (m.getReturnType() != String.class) continue;
            try {
                String r = (String) m.invoke(obj);
                if (r != null && !r.isEmpty() && r.length() > 1 && r.length() < 128
                    && !r.startsWith("wxid_") && !r.contains("@chatroom") && !r.contains("@")) {
                    return r;
                }
            } catch (Throwable e) {}
        }
        return null;
    }

    private static boolean skipWxid(String wxid) {
        if (wxid == null || wxid.isEmpty()) return true;
        if ("filehelper".equals(wxid)) return true;
        if ("weixin".equals(wxid)) return true;
        if ("notifymessage".equals(wxid)) return true;
        if ("medianote".equals(wxid)) return true;
        if ("wechat".equals(wxid)) return true;
        if ("tmessage".equals(wxid)) return true;
        if ("qmessage".equals(wxid)) return true;
        if ("floatbottle".equals(wxid)) return true;
        if ("newsapp".equals(wxid)) return true;
        if ("blog_app".equals(wxid)) return true;
        if ("masssendapp".equals(wxid)) return true;
        if ("meishiapp".equals(wxid)) return true;
        if ("fmessage".equals(wxid)) return true;
        if ("voipapp".equals(wxid)) return true;
        if ("officialaccounts".equals(wxid)) return true;
        if ("helper_entry".equals(wxid)) return true;
        if ("pc_share".equals(wxid)) return true;
        if ("cardpackage".equals(wxid)) return true;
        if ("googlecontact".equals(wxid)) return true;
        if ("linkedincontact".equals(wxid)) return true;
        if ("mobileconta".equals(wxid)) return true;
        if (wxid.startsWith("gh_")) return true;
        if (wxid.contains("@lbsroom")) return true;
        if (wxid.contains("@openim")) return true;
        if (wxid.contains("@im.chatroom")) return true;
        if (wxid.startsWith("qqmail_")) return true;
        return false;
    }

    public static String queryNickFromDB(String wxid) {
        if (wxid == null || wxid.isEmpty()) return null;

        String cached = sNickCache.get(wxid);
        if (cached != null) return cached;

        String name = null;
        Object db = ensureDirDb();

        if (db != null) {
            Object cursor = null;
            try {
                cursor = XposedHelpers.callMethod(db, "u",
                    "SELECT conRemark, nickname FROM rcontact WHERE username=?",
                    new String[]{wxid});
                if (cursor != null) {
                    int ciR = (Integer) XposedHelpers.callMethod(cursor, "getColumnIndex", "conRemark");
                    int ciN = (Integer) XposedHelpers.callMethod(cursor, "getColumnIndex", "nickname");
                    while ((Boolean) XposedHelpers.callMethod(cursor, "moveToNext")) {
                        String r = colStr(cursor, ciR);
                        String n = colStr(cursor, ciN);
                        if (r != null && !r.isEmpty()) name = r;
                        else if (n != null && !n.isEmpty()) name = n;
                        break;
                    }
                    XposedHelpers.callMethod(cursor, "close");
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "queryNick[dir] err: " + t.getMessage());
            } finally {
                if (cursor != null) {
                    try { XposedHelpers.callMethod(cursor, "close"); } catch (Throwable ignored) {}
                }
            }
        }

        if (name == null) {
            Object ddb = DatabaseProvider.getDatabase();
            if (ddb != null) {
                Object cursor = null;
                try {
                    cursor = XposedHelpers.callMethod(ddb, "rawQuery",
                        "SELECT conRemark, nickname FROM rcontact WHERE username=?",
                        new String[]{wxid});
                    if (cursor != null) {
                        int ciR = (Integer) XposedHelpers.callMethod(cursor, "getColumnIndex", "conRemark");
                        int ciN = (Integer) XposedHelpers.callMethod(cursor, "getColumnIndex", "nickname");
                        while ((Boolean) XposedHelpers.callMethod(cursor, "moveToNext")) {
                            String r = colStr(cursor, ciR);
                            String n = colStr(cursor, ciN);
                            if (r != null && !r.isEmpty()) name = r;
                            else if (n != null && !n.isEmpty()) name = n;
                            break;
                        }
                        XposedHelpers.callMethod(cursor, "close");
                    }
                } catch (Throwable t) {
                    LogWriter.log(TAG, "queryNick[dbp] err: " + t.getMessage());
                } finally {
                    if (cursor != null) {
                        try { XposedHelpers.callMethod(cursor, "close"); } catch (Throwable ignored) {}
                    }
                }
            }
        }

        if (name == null) name = "";
        sNickCache.put(wxid, name);
        if (name.isEmpty()) {
            LogWriter.log(TAG, "queryNick: " + truncate(wxid) + " -> (not found)");
        }
        return name.isEmpty() ? null : name;
    }

    private static Object ensureDirDb() {
        if (sDirDb != null) return sDirDb;

        ClassLoader cl = ContextManager.getClassLoader();
        if (cl == null) return null;

        synchronized (sDirLock) {
            if (sDirDb != null) return sDirDb;

            try {
                android.content.Context ctx = ContextManager.getAppContext();
                if (ctx == null) {
                    LogWriter.log(TAG, "ensureDirDb: Context is null");
                    return null;
                }

                SharedPreferences sp = ctx.getSharedPreferences("system_config_prefs", 0);
                Object uv = sp.getAll().get("default_uin");
                if (uv == null) {
                    LogWriter.log(TAG, "ensureDirDb: uin not found");
                    return null;
                }
                long uin = Long.parseLong(uv.toString());

                String dbPath = getDbPath(cl, ctx, uin);
                if (dbPath == null) {
                    LogWriter.log(TAG, "ensureDirDb: dbPath is null");
                    return null;
                }

                String[] imeiCandidates = getImeiCandidates(cl);
                for (String imei : imeiCandidates) {
                    if (imei == null || imei.isEmpty()) continue;
                    String password = calcPassword(cl, imei, uin);
                    if (password == null || password.length() != 7) continue;

                    Object db = openKa5Db(cl, dbPath, password);
                    if (db != null) {
                        sDirDb = db;
                        LogWriter.log(TAG, "ensureDirDb OK");
                        return db;
                    }
                }
                LogWriter.log(TAG, "ensureDirDb: all password candidates failed");
            } catch (Throwable t) {
                LogWriter.log(TAG, "ensureDirDb FAIL: " + t.getMessage());
            }
            return null;
        }
    }

    private static String truncate(String s) {
        return s == null ? "" : s.length() > 30 ? s.substring(0, 27) + "..." : s;
    }

    private static String colStr(Object cursor, int idx) {
        if (idx < 0) return "";
        try { return (String) XposedHelpers.callMethod(cursor, "getString", idx); } catch (Throwable t) { return ""; }
    }

    private static int colInt(Object cursor, int idx) {
        if (idx < 0) return 0;
        try { return (Integer) XposedHelpers.callMethod(cursor, "getInt", idx); } catch (Throwable t) { return 0; }
    }
}
