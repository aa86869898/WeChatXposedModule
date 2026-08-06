package com.leshao.v3.hook;

import android.content.Context;
import android.content.SharedPreferences;

import com.leshao.v3.ContextManager;
import com.leshao.v3.CrashTrace;
import com.leshao.v3.LogWriter;
import com.leshao.v3.PathUtil;
import com.leshao.v3.model.ContactChangeRecord;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ContactChangeLog {

    private static final String TAG = "ContactChangeLog";
    private static final int MAX_RECORDS = 500;

    private static volatile boolean sEnabled = true;
    private static final ConcurrentHashMap<String, ContactSnapshot> lastSnapshot = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> debounce = new ConcurrentHashMap<>();
    private static ClassLoader sCL;
    private static long sCurrentUin = -1;

    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    private static long getCurrentUin() {
        try {
            Context ctx = ContextManager.getAppContext();
            if (ctx == null) return -1;
            SharedPreferences sp = ctx.getSharedPreferences("system_config_prefs", 0);
            Object uv = sp.getAll().get("default_uin");
            if (uv == null) return -1;
            return Long.parseLong(uv.toString());
        } catch (Throwable t) { return -1; }
    }

    private static boolean isSkippableContact(String wxid) {
        if (wxid == null || wxid.isEmpty()) return true;
        if (wxid.startsWith("gh_")) return true;
        if (wxid.contains("@lbsroom")) return true;
        if (wxid.contains("@openim")) return true;
        if (wxid.startsWith("qqmail_")) return true;
        if (wxid.contains("@im.chatroom")) return true;
        if ("filehelper".equals(wxid)) return true;
        if ("weixin".equals(wxid)) return true;
        if ("notifymessage".equals(wxid)) return true;
        if ("medianote".equals(wxid)) return true;
        if ("wechat".equals(wxid)) return true;
        if ("tmessage".equals(wxid)) return true;
        if ("qmessage".equals(wxid)) return true;
        if ("newsapp".equals(wxid)) return true;
        return false;
    }

    private static void ensureUinSnapshot() {
        long uin = getCurrentUin();
        if (uin == -1) return;
        if (uin != sCurrentUin) {
            LogWriter.log(TAG, "UIN changed: " + sCurrentUin + " -> " + uin + ", clearing snapshots");
            sCurrentUin = uin;
            lastSnapshot.clear();
            sLogFile = null;
            loadSnapshots();
        }
    }

    public static void hook(ClassLoader cl) {
        if (!sEnabled) return;

        try {
            LogWriter.log(TAG, "hook installing...");
            sCL = cl;

            Class<?> vClass = VersionCompat.findVClass(cl);
            if (vClass == null) {
                LogWriter.log(TAG, "v class not found");
                return;
            }

            XposedBridge.hookAllMethods(vClass, "b", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    LogWriter.log(TAG, "[v.b] fired args=" + param.args.length);
                    try {
                        processModContact(param.args[0]);
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "[v.b] err: " + t.getClass().getSimpleName());
                    }
                }
            });

            XposedBridge.hookAllMethods(vClass, "a", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    LogWriter.log(TAG, "[v.a] fired args=" + param.args.length);
                }
            });

            try {
                Class<?> gcs = XposedHelpers.findClass(
                    "com.tencent.mm.plugin.getcontact.l11.h", cl);
                XposedBridge.hookAllMethods(gcs, "a", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length > 0 && param.args[0] instanceof String) {
                            LogWriter.log(TAG, "[GetContact.a] queued: " + param.args[0]);
                        }
                    }
                });
                XposedBridge.hookAllMethods(gcs, "b", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length > 0 && param.args[0] instanceof String) {
                            LogWriter.log(TAG, "[GetContact.b] queued: " + param.args[0]);
                        }
                    }
                });
                LogWriter.log(TAG, "GetContactService hook ok");
            } catch (Throwable t) {
                LogWriter.log(TAG, "GetContactService not found: " + t.getMessage());
            }
            LogWriter.log(TAG, "[v.b] hook ok");

            Class<?> storageClass = VersionCompat.findContactStorageClass(cl);
            if (storageClass == null) {
                LogWriter.log(TAG, "storage class not found");
                return;
            }
            for (String m : new String[]{"h0", "i0", "l0"}) {
                final String mn = m;
                XposedBridge.hookAllMethods(storageClass, mn, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        LogWriter.log(TAG, "[j4." + mn + "] fired");
                        if (param.args.length > 0 && param.args[0] != null) {
                            detectChangesFromContact(param.args[0]);
                        }
                    }
                });
            }

            Class<?> contactInfoUI = VersionCompat.findContactInfoUIClass(cl);
            if (contactInfoUI != null) {
                XposedBridge.hookAllMethods(contactInfoUI, "D2", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        CrashTrace.t("CLC_D2_IN");
                        try {
                        Object contact = getContactField(param.thisObject);
                        if (contact != null) detectChangesFromContact(contact);
                        } catch (Throwable t) {
                            CrashTrace.t("CLC_D2_ERR:" + t);
                        }
                        CrashTrace.t("CLC_D2_OUT");
                    }
                });

                XposedBridge.hookAllMethods(contactInfoUI, "onNotifyChange", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        CrashTrace.t("CLC_ONC_IN");
                        try {
                        Object contact = getContactField(param.thisObject);
                        if (contact != null) detectChangesFromContact(contact);
                        } catch (Throwable t) {
                            CrashTrace.t("CLC_ONC_ERR:" + t);
                        }
                        CrashTrace.t("CLC_ONC_OUT");
                    }
                });
            }

            LogWriter.log(TAG, "hook installed OK");
            CrashTrace.t("CLC_HOOK_INSTALLED");

            sCurrentUin = getCurrentUin();
            loadSnapshots();
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook FAIL: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private static boolean sDumpedFields = false;

    private static void processModContact(Object modContact) {
        if (!sEnabled || modContact == null) return;

        try {
            if (!sDumpedFields) {
                sDumpedFields = true;
                StringBuilder sb = new StringBuilder();
                for (java.lang.reflect.Field f : modContact.getClass().getDeclaredFields()) {
                    sb.append(f.getName()).append("(").append(f.getType().getSimpleName()).append(") ");
                }
                LogWriter.log(TAG, "[v.b] all np4 fields: " + sb.toString());
            }

            String username = fieldNg(modContact, "d");
            if (username == null || username.isEmpty()) {
                try { username = (String) XposedHelpers.getObjectField(modContact, "Z1"); } catch (Throwable ignored) {}
            }
            if (username == null || username.isEmpty()) {
                try { username = (String) XposedHelpers.getObjectField(modContact, "Z"); } catch (Throwable ignored) {}
            }
            if (username == null || username.isEmpty()) {
                LogWriter.log(TAG, "[v.b] skip: username empty (d/Z1/Z all empty)");
                return;
            }
            if (isSkippableContact(username)) return;

            ensureUinSnapshot();

            long now = System.currentTimeMillis();
            Long last = debounce.get(username);
            if (last != null && (now - last) < 5000) return;
            debounce.put(username, now);

            String nickname  = fieldNg(modContact, "e");
            String remark    = fieldNg(modContact, "v");
            int avatarHash   = 0;
            try { avatarHash = XposedHelpers.getIntField(modContact, "h"); } catch (Throwable ignored) {}

            LogWriter.log(TAG, "[v.b] uid=" + username + " nick=" + nickname + " remark=" + remark + " avatar=" + avatarHash);

            ContactSnapshot cur = new ContactSnapshot(username, nickname, remark, avatarHash, "");
            ContactSnapshot prev = lastSnapshot.get(username);

            if (prev != null) {
                buildAndSaveRecord(now, username, nickname, remark, prev, cur);
            }

            lastSnapshot.put(username, cur);
            saveSnapshots();
        } catch (Throwable t) {
            LogWriter.log(TAG, "[v.b] parse err: " + t.getClass().getSimpleName());
        }
    }

    private static String fieldNg(Object obj, String fieldName) {
        try {
            Object ew5 = XposedHelpers.getObjectField(obj, fieldName);
            if (ew5 == null) return "";
            return VersionCompat.ew5ToString(ew5, sCL);
        } catch (Throwable t) {
            return "";
        }
    }

    private static Object getContactField(Object activity) {
        for (String f : new String[]{"n", "mContact", "o", "p", "q", "r"}) {
            try {
                Object contact = XposedHelpers.getObjectField(activity, f);
                if (contact != null) return contact;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String callStringMethod(Object obj, String methodName) {
        try {
            Object result = XposedHelpers.callMethod(obj, methodName);
            return result instanceof String ? (String) result : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static int callIntMethod(Object obj, String methodName) {
        try {
            Object result = XposedHelpers.callMethod(obj, methodName);
            return result instanceof Integer ? (Integer) result : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static final boolean DEBUG_NOOP = true; // v182c: skip all logic, just trace

    private static void detectChangesFromContact(Object contact) {
        if (!sEnabled) return;
        CrashTrace.t("CLC_DEC_IN");
        if (DEBUG_NOOP) {
            CrashTrace.t("CLC_NOOP");
            CrashTrace.t("CLC_DEC_OUT");
            return;
        }

        try {
            long now = System.currentTimeMillis();

            String username = VersionCompat.getContactUsername(contact);
            if (username == null || username.isEmpty()) return;
            if (isSkippableContact(username)) return;

            ensureUinSnapshot();

            now = System.currentTimeMillis();
            Long last = debounce.get(username);
            if (last != null && (now - last) < 800) return;
            debounce.put(username, now);

            // v182b: SKIP ALL reflection to see if crash stops
            String nickname  = "";      // was: VersionCompat.getContactNickname(contact);
            CrashTrace.t("CLC_NICK_OK");
            String remark    = "";      // was: VersionCompat.getContactRemark(contact);
            CrashTrace.t("CLC_REMARK_OK");
            int avatarHash   = 0;       // was: VersionCompat.getContactAvatar(contact);
            CrashTrace.t("CLC_AVATAR_OK");

            String signature = "";      // was: VersionCompat.getContactSignature(contact);
            CrashTrace.t("CLC_SIG_OK");

            ContactSnapshot current = new ContactSnapshot(username, nickname, remark, avatarHash, signature);
            CrashTrace.t("CLC_SNAP_OK");
            ContactSnapshot previous = lastSnapshot.get(username);

            if (previous != null) {
                buildAndSaveRecord(now, username, nickname, remark, previous, current);
            }

            lastSnapshot.put(username, current);
            saveSnapshots();
        } catch (Throwable t) {
            CrashTrace.t("CLC_DEC_ERR:" + t);
            LogWriter.log(TAG, "detect err: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
        CrashTrace.t("CLC_DEC_OUT");
    }

    private static void buildAndSaveRecord(long now, String wxid, String nickname, String remark,
                                            ContactSnapshot prev, ContactSnapshot cur) {
        ContactChangeRecord r = new ContactChangeRecord();
        r.time = now;
        r.wxid = wxid;
        r.nickname = nickname;
        r.remark = remark;

        if (!nullSafeEquals(prev.nickname, cur.nickname)) {
            r.nicknameChanged = true;
            r.oldNickname = prev.nickname;
            r.newNickname = cur.nickname;
        }
        if (!nullSafeEquals(prev.remark, cur.remark)) {
            r.remarkChanged = true;
            r.oldRemark = prev.remark;
            r.newRemark = cur.remark;
        }
        if (!nullSafeEquals(prev.signature, cur.signature)) {
            r.signatureChanged = true;
            r.oldSignature = prev.signature;
            r.newSignature = cur.signature;
        }
        if (prev.avatarHash != cur.avatarHash) {
            r.avatarChanged = true;
            r.oldAvatarHash = prev.avatarHash;
            r.newAvatarHash = cur.avatarHash;
        }

        if (r.hasAnyChange()) {
            LogWriter.log(TAG, "CHANGE: nick=" + r.nicknameChanged + " remark=" + r.remarkChanged
                + " sig=" + r.signatureChanged + " avatar=" + r.avatarChanged
                + " | " + r.displayName());
            saveRecord(r);
        } else {
            LogWriter.log(TAG, "[v.b] no-change: " + wxid + " prevNick=" + prev.nickname + " curNick=" + cur.nickname
                + " prevRemark=" + prev.remark + " curRemark=" + cur.remark
                + " prevAvatar=" + prev.avatarHash + " curAvatar=" + cur.avatarHash);
        }
    }

    private static boolean nullSafeEquals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    private static void saveRecord(ContactChangeRecord record) {
        try {
            List<ContactChangeRecord> all = loadRecords();
            all.add(0, record);
            while (all.size() > MAX_RECORDS) {
                all.remove(all.size() - 1);
            }
            writeRecords(all);
        } catch (Throwable t) {
            LogWriter.log(TAG, "save err: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    public static List<ContactChangeRecord> loadRecords() {
        try {
            File f = getLogFile();
            if (!f.exists()) return new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return ContactChangeRecord.parseArray(sb.toString());
        } catch (Throwable t) {
            LogWriter.log(TAG, "load err: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
        return new ArrayList<>();
    }

    public static void clearRecords() {
        try {
            File f = getLogFile();
            if (f.exists()) f.delete();
            lastSnapshot.clear();
            File sf = getSnapshotFile();
            if (sf.exists()) sf.delete();
        } catch (Throwable t) {
            LogWriter.log(TAG, "clear err: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private static void writeRecords(List<ContactChangeRecord> list) {
        try {
            File f = getLogFile();
            f.getParentFile().mkdirs();
            FileWriter fw = new FileWriter(f);
            fw.write(ContactChangeRecord.toArrayJson(list));
            fw.close();
        } catch (Throwable t) {
            LogWriter.log(TAG, "write err: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private static File sLogFile;

    private static File getLogFile() {
        if (sLogFile != null) return sLogFile;
        String uinSuffix = sCurrentUin > 0 ? "_" + sCurrentUin : "";
        Context ctx = ContextManager.getAppContext();
        if (ctx != null) {
            File root = PathUtil.getLeshaoRootDir(ctx);
            sLogFile = new File(root, "contact_changes" + uinSuffix + ".json");
        } else {
            sLogFile = new File("/sdcard/leshao_v3_logs/contact_changes" + uinSuffix + ".json");
        }
        sLogFile.getParentFile().mkdirs();
        return sLogFile;
    }

    private static File getSnapshotFile() {
        String uinSuffix = sCurrentUin > 0 ? "_" + sCurrentUin : "";
        return new File(getLogFile().getParentFile(), "contact_snapshots" + uinSuffix + ".json");
    }

    private static void loadSnapshots() {
        try {
            File f = getSnapshotFile();
            if (!f.exists()) return;
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            org.json.JSONObject root = new org.json.JSONObject(sb.toString());
            java.util.Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                org.json.JSONObject o = root.optJSONObject(key);
                if (o != null) {
                    ContactSnapshot s = new ContactSnapshot(
                        key,
                        o.optString("n"),
                        o.optString("r"),
                        o.optInt("a"),
                        o.optString("s")
                    );
                    lastSnapshot.put(key, s);
                }
            }
            LogWriter.log(TAG, "loaded " + lastSnapshot.size() + " snapshots");
        } catch (Throwable t) {
            LogWriter.log(TAG, "load snapshot err: " + t.getClass().getSimpleName());
        }
    }

    private static volatile boolean sSavePending;
    private static final Object sSaveLock = new Object();

    private static void saveSnapshots() {
        synchronized (sSaveLock) {
            if (sSavePending) return;
            sSavePending = true;
        }
        new Thread("LeShaoSnapSaver") {
            @Override
            public void run() {
                try {
                    org.json.JSONObject root = new org.json.JSONObject();
                    for (java.util.Map.Entry<String, ContactSnapshot> e : lastSnapshot.entrySet()) {
                        org.json.JSONObject o = new org.json.JSONObject();
                        o.put("n", e.getValue().nickname != null ? e.getValue().nickname : "");
                        o.put("r", e.getValue().remark != null ? e.getValue().remark : "");
                        o.put("a", e.getValue().avatarHash);
                        o.put("s", e.getValue().signature != null ? e.getValue().signature : "");
                        root.put(e.getKey(), o);
                    }
                    File f = getSnapshotFile();
                    f.getParentFile().mkdirs();
                    FileWriter fw = new FileWriter(f);
                    fw.write(root.toString());
                    fw.close();
                } catch (Throwable t) {
                    LogWriter.log(TAG, "save snapshot err: " + t.getClass().getSimpleName());
                } finally {
                    sSavePending = false;
                }
            }
        }.start();
    }

    private static class ContactSnapshot {
        String username, nickname, remark, signature;
        int avatarHash;
        ContactSnapshot(String u, String n, String r, int a, String s) {
            username = u; nickname = n; remark = r; avatarHash = a; signature = s;
        }
    }
}
