package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import com.leshao.v3.Logger;
import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ContactChangeLog {

    private static volatile boolean sEnabled = true;
    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final ConcurrentHashMap<String, ContactSnapshot> lastSnapshot = new ConcurrentHashMap<>();
    private static File logFile = new File("/sdcard/LeShaoV3Logs/contact_changes.log");
    private static boolean detectNickname = true;
    private static boolean detectSignature = true;
    private static boolean detectAvatar = true;
    private static boolean detectRemark = true;

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
        } catch (Throwable t) {}

        try {
            Class<?> contactInfoUI = XposedHelpers.findClass(
                    "com.tencent.mm.plugin.profile.ui.ContactInfoUI", cl);
            XposedBridge.hookAllMethods(contactInfoUI, "onNotifyChange", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    detectChanges(param.thisObject);
                }
            });
        } catch (Throwable t) {}
    }

    private static void detectChanges(Object activity) {
        try {
            Object contact = getContactField(activity);
            if (contact == null) return;

            String username = (String) XposedHelpers.getObjectField(contact, "field_username");
            String nickname = (String) XposedHelpers.getObjectField(contact, "field_nickname");
            String alias = (String) XposedHelpers.getObjectField(contact, "field_alias");
            String remark = "";
            try {
                remark = (String) XposedHelpers.getObjectField(contact, "field_conRemark");
            } catch (Throwable ignored) {}

            ContactSnapshot current = new ContactSnapshot(username, nickname, alias, remark);
            ContactSnapshot previous = lastSnapshot.get(username);

            if (previous != null) {
                StringBuilder changes = new StringBuilder();

                if (detectNickname && !nullSafeEquals(previous.nickname, current.nickname)) {
                    changes.append(" 昵称: ").append(previous.nickname)
                            .append(" -> ").append(current.nickname);
                }
                if (detectRemark && !nullSafeEquals(previous.remark, current.remark)) {
                    changes.append(" 备注: ").append(previous.remark)
                            .append(" -> ").append(current.remark);
                }
                if (detectSignature && !nullSafeEquals(previous.alias, current.alias)) {
                    changes.append(" 微信号: ").append(previous.alias)
                            .append(" -> ").append(current.alias);
                }

                if (changes.length() > 0) {
                    String msg = sdf.format(new Date()) + " | " + username +
                            " (" + current.nickname + ")" + changes;
                    Logger.i("[ContactChange] " + msg);
                    writeLog(msg);
                }
            }

            lastSnapshot.put(username, current);
        } catch (Throwable t) {
            Logger.w("[ContactChange] 异常: " + t.getMessage());
        }
    }

    private static Object getContactField(Object activity) {
        for (String f : new String[]{"n", "mContact", "o", "p", "q"}) {
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

    private static void writeLog(String msg) {
        try {
            logFile.getParentFile().mkdirs();
            FileWriter fw = new FileWriter(logFile, true);
            fw.write(msg + "\n");
            fw.close();
        } catch (Throwable ignored) {}
    }

    private static class ContactSnapshot {
        String username, nickname, alias, remark;
        ContactSnapshot(String u, String n, String a, String r) {
            username = u; nickname = n; alias = a; remark = r;
        }
    }
}
