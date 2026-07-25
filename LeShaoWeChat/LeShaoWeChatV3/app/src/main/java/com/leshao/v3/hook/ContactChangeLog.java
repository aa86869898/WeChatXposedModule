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
import java.lang.reflect.Field;
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
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final ConcurrentHashMap<String, ContactSnapshot> lastSnapshot = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> debounce = new ConcurrentHashMap<>();
    private static volatile boolean sFieldDumped = false;

    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static void hook(ClassLoader cl) {
        if (!sEnabled) {
            LogWriter.log(TAG, "hook SKIP: sEnabled=false");
            return;
        }
        ModuleConfig config = ModuleConfig.load(ContextManager.getPrefs());
        if (config == null || !config.contactChangeLogEnabled) {
            LogWriter.log(TAG, "hook SKIP: config.contactChangeLogEnabled="
                + (config != null ? config.contactChangeLogEnabled : "null"));
            return;
        }

        LogWriter.log(TAG, "hook START");

        try {
            Class<?> contactInfoUI = XposedHelpers.findClass(
                "com.tencent.mm.plugin.profile.ui.ContactInfoUI", cl);

            XposedBridge.hookAllMethods(contactInfoUI, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    detectChanges(param.thisObject);
                }
            });
            LogWriter.log(TAG, "hook onResume OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook onResume FAIL: " + t.getClass().getSimpleName() + " " + t.getMessage());
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
            LogWriter.log(TAG, "hook onNotifyChange OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook onNotifyChange FAIL: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }

        LogWriter.log(TAG, "hook DONE");
    }

    private static void detectChanges(Object activity) {
        try {
            Object contact = getContactField(activity);
            if (contact == null) {
                LogWriter.log(TAG, "detect: contact=null");
                return;
            }

            String username = readStringField(contact, "field_username");
            if (username == null || username.isEmpty()) {
                LogWriter.log(TAG, "detect: username empty");
                return;
            }

            long now = System.currentTimeMillis();
            Long last = debounce.get(username);
            if (last != null && (now - last) < 500) {
                return;
            }
            debounce.put(username, now);

            String nickname = readStringField(contact, "field_nickname");
            String alias   = readStringField(contact, "field_alias");
            String remark  = readStringField(contact, "field_conRemark");
            String signature = readStringField(contact, "field_signature");
            if (signature.length() == 0) signature = readStringField(contact, "signature");

            LogWriter.log(TAG, "detect: u=" + username + " n=" + trunc(nickname, 10)
                + " a=" + trunc(alias, 10) + " r=" + trunc(remark, 10)
                + " sig=" + trunc(signature, 10));

            ContactSnapshot current = new ContactSnapshot(username, nickname, alias, remark, signature);
            ContactSnapshot previous = lastSnapshot.get(username);

            if (previous != null) {
                compareAndRecord(now, username, nickname, "昵称", previous.nickname, current.nickname);
                compareAndRecord(now, username, nickname, "备注", previous.remark, current.remark);
                compareAndRecord(now, username, nickname, "微信号", previous.alias, current.alias);
                compareAndRecord(now, username, nickname, "签名", previous.signature, current.signature);
            } else {
                LogWriter.log(TAG, "first visit for " + username + ", snapshot saved");
            }

            lastSnapshot.put(username, current);
        } catch (Throwable t) {
            LogWriter.log(TAG, "detectChanges err: " + t.getClass().getSimpleName()
                + " " + t.getMessage());
        }
    }

    private static Object getContactField(Object activity) {
        for (String f : new String[]{"n", "mContact", "o", "p", "q", "r"}) {
            try {
                Object contact = XposedHelpers.getObjectField(activity, f);
                if (contact != null) {
                    LogWriter.log(TAG, "getContactField: found at field '" + f + "' class="
                        + contact.getClass().getSimpleName());
                    return contact;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static void dumpObjectFields(Object obj, String label) {
        if (obj == null || sFieldDumped) return;
        sFieldDumped = true;
        try {
            LogWriter.log(TAG, "=== field dump for " + label + " (" + obj.getClass().getName() + ") ===");
            int count = 0;
            for (Field f : obj.getClass().getDeclaredFields()) {
                if (count >= 50) break;
                f.setAccessible(true);
                try {
                    Object val = f.get(obj);
                    String valStr = val == null ? "null" : trunc(String.valueOf(val), 50);
                    LogWriter.log(TAG, "  [" + f.getType().getSimpleName() + "] " + f.getName() + " = " + valStr);
                    count++;
                } catch (Throwable ignored) {}
            }
            if (obj.getClass().getSuperclass() != null) {
                for (Field f : obj.getClass().getSuperclass().getDeclaredFields()) {
                    if (count >= 50) break;
                    f.setAccessible(true);
                    try {
                        Object val = f.get(obj);
                        String valStr = val == null ? "null" : trunc(String.valueOf(val), 50);
                        LogWriter.log(TAG, "  [SUPER:" + f.getType().getSimpleName() + "] " + f.getName() + " = " + valStr);
                        count++;
                    } catch (Throwable ignored) {}
                }
            }
            LogWriter.log(TAG, "=== field dump END (" + count + " fields) ===");
        } catch (Throwable t) {
            LogWriter.log(TAG, "dumpFields err: " + t.getMessage());
        }
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
            LogWriter.log(TAG, "saveRecord OK, total=" + all.size());
        } catch (Throwable t) {
            LogWriter.log(TAG, "saveRecord err: " + t.getClass().getSimpleName() + " " + t.getMessage());
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
            LogWriter.log(TAG, "loadRecords err: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
        return new ArrayList<>();
    }

    public static void clearRecords() {
        try {
            File f = getLogFile();
            if (f.exists()) f.delete();
            lastSnapshot.clear();
            LogWriter.log(TAG, "clearRecords OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "clearRecords err: " + t.getClass().getSimpleName() + " " + t.getMessage());
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
            LogWriter.log(TAG, "writeRecords err: " + t.getClass().getSimpleName() + " " + t.getMessage());
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
