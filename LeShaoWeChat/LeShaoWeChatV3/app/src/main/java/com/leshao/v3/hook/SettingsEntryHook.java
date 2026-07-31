package com.leshao.v3.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class SettingsEntryHook {

    private static final String TAG = "SettingsEntryHook";
    private static boolean backPressHooked = false;
    private static Activity panelActivity;
    private static AlertDialog panelDialog;
    private static volatile boolean hooksRegistered = false;

    public SettingsEntryHook() {}

    public static void hook(ClassLoader wechatCL) {
        if (!hooksRegistered) {
            hooksRegistered = true;
            XposedBridge.log("LeShaoV3: EntryHook.hook() called");
            hookD34lF7();
            hookMvvmListData();
            hookBackPressed();
            LogWriter.log(TAG, "INIT: hooks registered ok");
        }
    }

    private static void hookD34lF7() {
        ClassLoader cl = ContextManager.getClassLoader();
        if (cl == null) {
            LogWriter.log(TAG, "classLoader null");
            return;
        }

        try {
            Class<?> d34lClass = XposedHelpers.findClass("d34.l", cl);

            XposedBridge.hookAllMethods(d34lClass, "f7", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object thisObj = param.thisObject;
                        Context ctx = (Context) XposedHelpers.callMethod(thisObj, "getActivity");
                        Activity act = getActivity(ctx);
                        if (act == null) return;
                        if (!act.getClass().getName().equals("com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI")) return;

                        SettingsInjectProvider.tryInject(act);
                    } catch (Throwable t) {
                        XposedBridge.log("LeShaoV3: f7 hook FAILED: " + t.getClass().getName() + ": " + t.getMessage());
                        LogWriter.log(TAG, "f7 hook FAILED: " + t.getClass().getName() + ": " + t.getMessage());
                    }
                }
            });

            XposedBridge.log("LeShaoV3: d34.l.f7() hook installed");
            LogWriter.log(TAG, "d34.l.f7() hook installed");
        } catch (Throwable t) {
            XposedBridge.log("LeShaoV3: hookD34lF7 FAILED: " + t.getClass().getName() + ": " + t.getMessage());
            LogWriter.log(TAG, "hookD34lF7 FAILED: " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    private static void hookMvvmListData() {
        ClassLoader cl = ContextManager.getClassLoader();
        if (cl == null) return;

        try {
            Class<?> mvvmListClass = XposedHelpers.findClass("com.tencent.mm.plugin.mvvmlist.MvvmList", cl);

            XposedBridge.hookAllMethods(mvvmListClass, "n", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        List<?> data = (List<?>) param.args[0];
                        if (data == null || data.isEmpty()) return;

                        Object lifecycleOwner = XposedHelpers.getObjectField(param.thisObject, "f");
                        if (!(lifecycleOwner instanceof Context)) return;
                        Activity act = getActivity((Context) lifecycleOwner);
                        if (act == null) return;
                        if (!act.getClass().getName().equals("com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI")) return;

                        SettingsInjectProvider.tryInject(act);
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "MvvmList.n hook err: " + t.getMessage());
                    }
                }
            });

            LogWriter.log(TAG, "MvvmList.n() hook installed");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookMvvmListData FAIL: " + t.getMessage());
        }
    }

    private static Activity getActivity(Context ctx) {
        while (ctx != null) {
            if (ctx instanceof Activity) return (Activity) ctx;
            if (ctx instanceof android.content.ContextWrapper) {
                ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
            } else break;
        }
        return null;
    }

    private static void hookBackPressed() {
        if (backPressHooked) return;
        backPressHooked = true;
        try {
            java.lang.reflect.Method m = Activity.class.getDeclaredMethod("onBackPressed");
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Activity act = (Activity) param.thisObject;
                        if (panelDialog != null && panelDialog.isShowing() && act == panelActivity) {
                            panelDialog.dismiss();
                            panelDialog = null;
                            panelActivity = null;
                            param.setResult(null);
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookBackPressed FAILED: " + t.getMessage());
        }
    }
}
