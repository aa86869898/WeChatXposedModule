package com.leshao.v3.db;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.model.Contact;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
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

    public static List<Contact> getAll() { return sAllContacts != null ? sAllContacts : Collections.<Contact>emptyList(); }
    public static List<Contact> getFriends() { return sFriends != null ? sFriends : Collections.<Contact>emptyList(); }
    public static List<Contact> getGroups() { return sGroups != null ? sGroups : Collections.<Contact>emptyList(); }
    public static boolean isLoaded() { return sLoaded; }
    public static boolean isLoading() { return sLoading; }

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
            // Strategy A: DatabaseProvider hooks → 轮询等待（最多2秒，DB 回调会更快）
            LogWriter.log(TAG, "Strategy A: waiting for DB (max 15s)...");
            Object db = waitForDatabase(15000);
            LogWriter.log(TAG, "Strategy A: waitForDatabase returned " + (db != null ? "DB" : "null"));
            if (db != null && tryQueries(db)) {
                sLoaded = true; sLoading = false;
                LogWriter.log(TAG, "loadContacts OK via Strategy A (DB hooks)");
                return true;
            }

            // Strategy B: com.tencent.mm.model.aj 会话存储 (V21 Strategy 2)
            LogWriter.log(TAG, "Strategy B: trying model.aj...");
            if (loadViaModelAj()) {
                sLoaded = true; sLoading = false;
                LogWriter.log(TAG, "loadContacts OK via Strategy B (model.aj)");
                return true;
            }

            // Strategy C: Messaging plugin via findKernelClass (V21 Strategy 3)
            LogWriter.log(TAG, "Strategy C: trying findKernelClass...");
            if (loadViaMessagingPlugin()) {
                sLoaded = true; sLoading = false;
                LogWriter.log(TAG, "loadContacts OK via Strategy C (messaging plugin)");
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
        return queryContacts(db, "SELECT username, alias, conRemark, nickname, type FROM rcontact")
            || queryContacts(db, "SELECT username, alias, conRemark, nickname, type FROM Contact");
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

    // ===== Strategy B: com.tencent.mm.model.aj (V21 Strategy 2) =====

    private static boolean loadViaModelAj() {
        ClassLoader cl = ContextManager.getClassLoader();
        if (cl == null) return false;

        try {
            String[] ajMethods = {"getAs", "bJt", "aOJ", "aOM", "getResponse"};
            Object convStg = null;
            String ajMethodUsed = null;
            for (String mn : ajMethods) {
                try {
                    Class<?> ajCls = cl.loadClass("com.tencent.mm.model.aj");
                    convStg = XposedHelpers.callStaticMethod(ajCls, mn);
                    ajMethodUsed = mn;
                    break;
                } catch (Throwable e) {}
            }
            if (convStg == null) {
                LogWriter.log(TAG, "Strategy B: com.tencent.mm.model.aj failed to load");
                return false;
            }

            String[] getAllMethods = {"getAll", "bLw", "aOB", "values", "getMap"};
            Object allConvs = null;
            String getAllUsed = null;
            for (String mn : getAllMethods) {
                try {
                    allConvs = XposedHelpers.callMethod(convStg, mn);
                    getAllUsed = mn;
                    break;
                } catch (Throwable e) {}
            }
            if (allConvs == null) {
                LogWriter.log(TAG, "Strategy B: aj." + ajMethodUsed + "() OK but no getAll-like method found");
                return false;
            }

            List<Contact> all = new ArrayList<>();
            List<Contact> friends = new ArrayList<>();
            List<Contact> groups = new ArrayList<>();

            if (allConvs instanceof Map) {
                Map<?, ?> convMap = (Map<?, ?>) allConvs;
                LogWriter.log(TAG, "Strategy B: aj." + ajMethodUsed + "()." + getAllUsed + "() count=" + convMap.size());
                for (Object conv : convMap.values()) {
                    addConvEntry(conv, all, friends, groups);
                }
            } else if (allConvs instanceof Iterable) {
                int cnt = 0;
                for (Object conv : (Iterable<?>) allConvs) {
                    if (addConvEntry(conv, all, friends, groups)) cnt++;
                }
                LogWriter.log(TAG, "Strategy B: convList count=" + cnt);
            } else {
                LogWriter.log(TAG, "Strategy B: unsupported allConvs type: " + allConvs.getClass().getName());
                return false;
            }

            if (all.isEmpty()) return false;
            sAllContacts = all; sFriends = friends; sGroups = groups;
            LogWriter.log(TAG, "Strategy B OK: all=" + all.size() + " f=" + friends.size() + " g=" + groups.size());
            return true;
        } catch (Throwable e) {
            LogWriter.log(TAG, "Strategy B ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    private static boolean addConvEntry(Object conv, List<Contact> all, List<Contact> friends, List<Contact> groups) {
        try {
            String wxid = resolveObjWxid(conv);
            if (wxid == null) {
                for (String mn : new String[]{"getUsername", "field_username", "bLp", "getTalker"}) {
                    try {
                        Object val = XposedHelpers.callMethod(conv, mn);
                        if (val instanceof String) { wxid = (String) val; if (wxid != null && !wxid.isEmpty()) break; }
                    } catch (Throwable e) {}
                }
            }
            if (skipWxid(wxid)) return false;

            String name = resolveObjName(conv);
            if (name == null || name.isEmpty()) {
                for (String mn : new String[]{"getRemarkName", "getNickname", "getDisplayName", "getConRemark"}) {
                    try { name = (String) XposedHelpers.callMethod(conv, mn); if (name != null && !name.isEmpty()) break; }
                    catch (Throwable e) {}
                }
            }
            if (name == null || name.isEmpty()) name = wxid;

            int type = wxid.endsWith("@chatroom") ? 1 : 0;
            Contact c = new Contact(wxid, name, name, wxid, type);
            all.add(c);
            if (wxid.endsWith("@chatroom")) groups.add(c);
            else friends.add(c);
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
                if (skipWxid(wxid)) continue;
                int type = colInt(cursor, ciT);
                Contact c = new Contact(wxid, colStr(cursor, ciN), colStr(cursor, ciR), colStr(cursor, ciA), type);

                all.add(c);
                if (wxid.endsWith("@chatroom")) { groups.add(c); gb++; }
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
        String[] getters = {"getWxid", "getUsername", "getChatRoomName", "getChatroomName", "getRoomId", "getTalker"};
        for (String mn : getters) {
            try { String r = (String) obj.getClass().getMethod(mn).invoke(obj); if (r != null && !r.isEmpty()) return r; }
            catch (Throwable e) {}
        }
        String[] pubFields = {"wxid", "username", "chatroomName", "chatRoomName", "mUsername"};
        for (String fn : pubFields) {
            try { String r = (String) obj.getClass().getField(fn).get(obj); if (r != null && !r.isEmpty()) return r; }
            catch (Throwable e1) {
                try {
                    java.lang.reflect.Field f = obj.getClass().getDeclaredField(fn);
                    f.setAccessible(true);
                    String r = (String) f.get(obj); if (r != null && !r.isEmpty()) return r;
                } catch (Throwable e2) {}
            }
        }
        return null;
    }

    private static String resolveObjName(Object obj) {
        if (obj == null) return null;
        String[] getters = {"getRemarkName", "getNickname", "getDisplayName", "getConRemark", "getName"};
        for (String mn : getters) {
            try {
                Object r = obj.getClass().getMethod(mn).invoke(obj);
                if (r instanceof String && !((String) r).isEmpty()) return (String) r;
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

    private static String colStr(Object cursor, int idx) {
        if (idx < 0) return "";
        try { return (String) XposedHelpers.callMethod(cursor, "getString", idx); } catch (Throwable t) { return ""; }
    }

    private static int colInt(Object cursor, int idx) {
        if (idx < 0) return 0;
        try { return (Integer) XposedHelpers.callMethod(cursor, "getInt", idx); } catch (Throwable t) { return 0; }
    }
}
