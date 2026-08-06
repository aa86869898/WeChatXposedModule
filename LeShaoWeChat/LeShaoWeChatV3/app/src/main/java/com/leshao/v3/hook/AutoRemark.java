package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import com.leshao.v3.Logger;
import com.leshao.v3.ContextManager;
import com.leshao.v3.CrashTrace;
import com.leshao.v3.model.ModuleConfig;

public class AutoRemark {

    private static final boolean DEBUG_NOOP = true; // v182c
    private static volatile boolean sEnabled = true;
    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static boolean autoFillFromGroupNick = true;
    public static boolean autoFillFromCard = true;
    private static ClassLoader classLoader;

    public static void hook(ClassLoader cl) {
        if (!sEnabled) return;
        ModuleConfig config = ModuleConfig.load(ContextManager.getPrefs());
        if (config == null || !config.autoRemarkEnabled) return;
        classLoader = cl;

        hookContactLoad(cl);
        hookRemarkUI(cl);
    }

    private static void hookContactLoad(ClassLoader cl) {
        try {
            Class<?> contactInfoUI = XposedHelpers.findClass(
                    "com.tencent.mm.plugin.profile.ui.ContactInfoUI", cl);

            XposedBridge.hookAllMethods(contactInfoUI, "D2", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    CrashTrace.t("AUR_D2_IN");
                    if (!DEBUG_NOOP) checkAndAutoRemark(param.thisObject);
                    CrashTrace.t("AUR_D2_OUT");
                }
            });

            XposedBridge.hookAllMethods(contactInfoUI, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    CrashTrace.t("AUR_RS_IN");
                    if (!DEBUG_NOOP) checkAndAutoRemark(param.thisObject);
                    CrashTrace.t("AUR_RS_OUT");
                }
            });
        } catch (Throwable t) {}
    }

    private static void checkAndAutoRemark(Object activity) {
        try {
            Object contact = null;
            for (String f : new String[]{"n", "mContact", "o", "p"}) {
                try {
                    contact = XposedHelpers.getObjectField(activity, f);
                    if (contact != null) break;
                } catch (Throwable ignored) {}
            }
            if (contact == null) return;

            String remark = "";
            try {
                remark = (String) XposedHelpers.getObjectField(contact, "field_conRemark");
            } catch (Throwable ignored) {}

            if (remark != null && !remark.isEmpty()) return;

            String nickname = "";
            try {
                nickname = (String) XposedHelpers.getObjectField(contact, "field_nickname");
            } catch (Throwable ignored) {}

            if (autoFillFromGroupNick && nickname != null && nickname.length() > 0) {
                try {
                    String roomNickname = (String) XposedHelpers.getObjectField(
                            activity, "roomNickname");
                    if (roomNickname != null && !roomNickname.isEmpty()) {
                        autoSetRemark(activity, roomNickname);
                        return;
                    }
                } catch (Throwable ignored) {}
            }

            if (autoFillFromCard) {
                try {
                    String alias = (String) XposedHelpers.getObjectField(contact, "field_alias");
                    if (alias != null && !alias.isEmpty() && !alias.startsWith("wxid_")) {
                        autoSetRemark(activity, alias);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {}
    }

    private static void autoSetRemark(Object activity, String newRemark) {
        try {
            XposedHelpers.callMethod(activity, "W6", newRemark);
            Logger.i("[AutoRemark] 自动备注: " + newRemark);
        } catch (Throwable t) {
            Logger.w("[AutoRemark] 设置失败: " + t.getMessage());
        }
    }

    private static void hookRemarkUI(ClassLoader cl) {
        String[] remarkClasses = {
            "com.tencent.mm.ui.contact.ModRemarkNameUI",
            "com.tencent.mm.plugin.profile.ui.ContactRemarkInfoModUI"
        };

        for (String clsName : remarkClasses) {
            try {
                Class<?> remarkUI = XposedHelpers.findClass(clsName, cl);
                XposedBridge.hookAllMethods(remarkUI, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            android.app.Activity act = (android.app.Activity) param.thisObject;
                            android.content.Intent intent = act.getIntent();
                            String username = intent.getStringExtra("Contact_User");
                            if (username == null) return;

                            String suggestedRemark = getSuggestedRemark(username);
                            if (suggestedRemark != null) {
                                fillEditText(act.getWindow().getDecorView(), suggestedRemark);
                                Logger.i("[AutoRemark] 已填入建议备注: " + suggestedRemark);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    private static String getSuggestedRemark(String username) {
        try {
            Class<?> contactStorage = VersionCompat.findMsgStorageShortClass(classLoader);
            if (contactStorage == null) return null;
            Object storage = XposedHelpers.callStaticMethod(contactStorage, "b");
            if (storage == null) return null;

            Object convStorage = XposedHelpers.callMethod(storage, "r");
            if (convStorage == null) return null;

            Object y3 = XposedHelpers.callMethod(convStorage, "p", username);
            if (y3 == null) return null;

            String nickname = (String) XposedHelpers.getObjectField(y3, "field_nickname");
            String alias = (String) XposedHelpers.getObjectField(y3, "field_alias");

            if (nickname != null && !nickname.isEmpty() && !nickname.equals(username)) {
                return nickname;
            }
            if (alias != null && !alias.isEmpty() && !alias.startsWith("wxid_")) {
                return alias;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void fillEditText(android.view.View root, String text) {
        if (root instanceof android.widget.EditText) {
            ((android.widget.EditText) root).setText(text);
            return;
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                fillEditText(vg.getChildAt(i), text);
            }
        }
    }
}
