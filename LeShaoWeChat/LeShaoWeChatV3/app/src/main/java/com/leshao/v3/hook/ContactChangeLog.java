package com.leshao.v3.hook;

import android.content.Context;

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
import java.util.Date;
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
    private static volatile long sLastDetect = 0;

    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static void hook(ClassLoader cl) {
        if (!sEnabled) return;

        try {
            LogWriter.log(TAG, "hook installing...");

            Class<?> contactInfoUI = XposedHelpers.findClass(
                "com.tencent.mm.plugin.profile.ui.ContactInfoUI", cl);

            XposedBridge.hookAllMethods(contactInfoUI, "D2", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    detectChanges(param.thisObject);
                }
            });

            XposedBridge.hookAllMethods(contactInfoUI, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    detectChanges(param.thisObject);
                }
            });

            XposedBridge.hookAllMethods(contactInfoUI, "onNotifyChange", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    detectChanges(param.thisObject);
                }
            });

            LogWriter.log(TAG, "hook installed OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook FAIL: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private static void detectChanges(Object activity) {
        try {
            long now = System.currentTimeMillis();
            if (now - sLastDetect < 600) return;
            sLastDetect = now;
            ModuleConfig config = ModuleConfig.load(ContextManager.getPrefs());
            if (config == null || !config.contactChangeLogEnabled) {
                LogWriter.log(TAG, "detect skip: config=" + (config == null ? "null" : "disabled"));
                return;
            }

            Object contact = getContactField(activity);
            if (contact == null) {
                LogWriter.log(TAG, "detect skip: contact null");
                return;
            }

            String username = readStringField(contact, "field_username");
            if (username == null || username.isEmpty()) {
                LogWriter.log(TAG, "detect skip: username empty");
                return;
            }

            now = System.currentTimeMillis();
            Long last = debounce.get(username);
            if (last != null && (now - last) < 800) return;
            debounce.put(username, now);

            String nickname  = readStringField(contact, "field_nickname");
            String alias     = readStringField(contact, "field_alias");
            String remark    = readStringField(contact, "field_conRemark");
            String signature = readStringField(contact, "field_signature");
            if (signature.isEmpty()) signature = readStringField(contact, "signature");

            ContactSnapshot current = new ContactSnapshot(username, nickname, alias, remark, signature);
            ContactSnapshot previous = lastSnapshot.get(username);

            if (previous != null) {
                compareAndRecord(now, username, nickname, "昵称", previous.nickname, current.nickname);
                compareAndRecord(now, username, nickname, "备注", previous.remark, current.remark);
                compareAndRecord(now, username, nickname, "微信号", previous.alias, current.alias);
                compareAndRecord(now, username, nickname, "签名", previous.signature, current.signature);
            }

            lastSnapshot.put(username, current);
        } catch (Throwable t) {
            LogWriter.log(TAG, "detect err: " + t.getClass().getSimpleName() + " " + t.getMessage());
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

    private static String readStringField(Object obj, String fieldName) {
        try {
            String val = (String) XposedHelpers.getObjectField(obj, fieldName);
            return val != null ? val : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void compareAndRecord(long now, String username, String nickname,
                                          String changeType, String oldVal, String newVal) {
        if (!nullSafeEquals(oldVal, newVal)) {
            LogWriter.log(TAG, "CHANGE: " + changeType + " " + trunc(oldVal, 20) + " -> " + trunc(newVal, 20));
            saveRecord(new ContactChangeRecord(now, username, nickname, changeType, oldVal, newVal));
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

    private static String trunc(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static class ContactSnapshot {
        String username, nickname, alias, remark, signature;
        ContactSnapshot(String u, String n, String a, String r, String s) {
            username = u; nickname = n; alias = a; remark = r; signature = s;
        }
    }
}
