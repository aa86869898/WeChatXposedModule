package com.leshao.v3.hook;

import android.content.Context;
import android.content.SharedPreferences;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ContactChangeRecord;
import com.leshao.v3.model.ModuleConfig;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.BufferedReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ContactChangeLog {

    private static final String TAG = "ContactChangeLog";
    private static final int MAX_RECORDS = 500;

    private static volatile boolean sEnabled = true;
    private static volatile boolean sDetectNickname  = true;
    private static volatile boolean sDetectAlias     = true;
    private static volatile boolean sDetectRemark    = true;
    private static volatile boolean sDetectAvatar    = true;

    public static void setEnabled(boolean enabled) { sEnabled = enabled; }
    public static void setDetectNickname(boolean v) { sDetectNickname = v; }
    public static void setDetectAlias(boolean v)    { sDetectAlias = v; }
    public static void setDetectRemark(boolean v)   { sDetectRemark = v; }
    public static void setDetectAvatar(boolean v)   { sDetectAvatar = v; }

    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final ConcurrentHashMap<String, ContactSnapshot> lastSnapshot = new ConcurrentHashMap<>();

    public static void hook(ClassLoader cl) {
        if (!sEnabled) return;
        ModuleConfig config = ModuleConfig.load(ContextManager.getPrefs());
        if (config == null || !config.contactChangeLogEnabled) return;

        try {
            Class<?> contactInfoUI = XposedHelpers.findClass(
                "com.tencent.mm.plugin.profile.ui.ContactInfoUI", cl);

            XposedBridge.hookAllMethods(contactInfoUI, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    detectChanges(param.thisObject);
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "onResume hook err: " + t.getMessage());
        }

        try {
            Class<?> contactInfoUI = XposedHelpers.findClass(
                "com.tencent.mm.plugin.profile.ui.ContactInfoUI", cl);
            XposedBridge.hookAllMethods(contactInfoUI, "onNotifyChange", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    detectChanges(param.thisObject);
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "onNotifyChange hook err: " + t.getMessage());
        }
    }

    private static void detectChanges(Object activity) {
        try {
            Object contact = getContactField(activity);
            if (contact == null) return;

            String username = (String) XposedHelpers.getObjectField(contact, "field_username");
            if (username == null || username.isEmpty()) return;

            String nickname = (String) XposedHelpers.getObjectField(contact, "field_nickname");
            String alias = "";
            try { alias = (String) XposedHelpers.getObjectField(contact, "field_alias"); } catch (Throwable ignored) {}
            String remark = "";
            try { remark = (String) XposedHelpers.getObjectField(contact, "field_conRemark"); } catch (Throwable ignored) {}

            ContactSnapshot current = new ContactSnapshot(username, nickname, alias, remark);
            ContactSnapshot previous = lastSnapshot.get(username);

            if (previous != null) {
                long now = System.currentTimeMillis();
                if (sDetectNickname && !nullSafeEquals(previous.nickname, current.nickname)) {
                    saveRecord(new ContactChangeRecord(now, username, nickname, "昵称",
                        previous.nickname, current.nickname));
                }
                if (sDetectRemark && !nullSafeEquals(previous.remark, current.remark)) {
                    saveRecord(new ContactChangeRecord(now, username, nickname, "备注",
                        previous.remark, current.remark));
                }
                if (sDetectAlias && !nullSafeEquals(previous.alias, current.alias)) {
                    saveRecord(new ContactChangeRecord(now, username, nickname, "微信号",
                        previous.alias, current.alias));
                }
            }

            lastSnapshot.put(username, current);
        } catch (Throwable t) {
            LogWriter.log(TAG, "detectChanges err: " + t.getMessage());
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
            LogWriter.log(TAG, "saveRecord err: " + t.getMessage());
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
            LogWriter.log(TAG, "loadRecords err: " + t.getMessage());
        }
        return new ArrayList<>();
    }

    public static void clearRecords() {
        try {
            File f = getLogFile();
            if (f.exists()) f.delete();
            lastSnapshot.clear();
        } catch (Throwable t) {
            LogWriter.log(TAG, "clearRecords err: " + t.getMessage());
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
            LogWriter.log(TAG, "writeRecords err: " + t.getMessage());
        }
    }

    private static File getLogFile() {
        Context ctx = ContextManager.getAppContext();
        if (ctx != null) {
            try {
                File dir = ctx.getExternalFilesDir(null);
                if (dir != null) {
                    dir.mkdirs();
                    return new File(dir, "contact_changes.json");
                }
            } catch (Throwable ignored) {}
        }
        return new File("/sdcard/LeShaoV3Logs/contact_changes.json");
    }

    private static class ContactSnapshot {
        String username, nickname, alias, remark;
        ContactSnapshot(String u, String n, String a, String r) {
            username = u; nickname = n; alias = a; remark = r;
        }
    }
}
