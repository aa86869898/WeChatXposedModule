package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import com.leshao.v3.Logger;
import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;

public class StickyEnhance {

    private static volatile boolean sEnabled = true;
    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static int maxSticky = 999;

    public static void hook(ClassLoader cl) {
        if (!sEnabled) return;
        ModuleConfig config = ModuleConfig.load(ContextManager.getPrefs());
        if (config == null || !config.stickyEnhanceEnabled) return;

        hookStickyLimit(cl);
        hookStickyMenu(cl);
    }

    private static void hookStickyLimit(ClassLoader cl) {
        try {
            Class<?> stickySorter = VersionCompat.findStickySorterClass(cl);
            if (stickySorter == null) return;

            for (java.lang.reflect.Method m : stickySorter.getDeclaredMethods()) {
                final String methodName = m.getName();

                if (m.getReturnType() == int.class && m.getParameterTypes().length <= 1) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                                        int val = (Integer) param.getResult();
                                                        if (val > 0 && val <= 100) {
                                                            param.setResult(maxSticky);
                                                        }
                            } catch (Throwable e) {
                                de.robv.android.xposed.XposedBridge.log("LeShaoV3 cb err: " + e);
                            }
                        }
                    });
                }

                if (m.getReturnType() == boolean.class && m.getParameterTypes().length <= 2) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                                        boolean result = (Boolean) param.getResult();
                                                        if (!result) {
                                                            param.setResult(true);
                                                            Logger.i("[Sticky] 置顶限制已绕过 (" + methodName + ")");
                                                        }
                            } catch (Throwable e) {
                                de.robv.android.xposed.XposedBridge.log("LeShaoV3 cb err: " + e);
                            }
                        }
                    });
                }
            }
        } catch (Throwable t) {
            Logger.w("[Sticky] tm2.v8失败: " + t.getMessage());
        }
    }

    private static void hookStickyMenu(ClassLoader cl) {
        try {
            Class<?> contextMenu = VersionCompat.findContextMenuClass(cl);
            if (contextMenu == null) return;

            XposedBridge.hookAllMethods(contextMenu, "onCreateContextMenu",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        ContextMenu menu = (ContextMenu) param.args[0];
                        View v = (View) param.args[1];
                        if (menu != null) {
                            menu.add(0, 99993, 0, "强制置顶");
                            menu.add(0, 99994, 0, "取消所有置顶");
                        }
                    } catch (Throwable ignored) {}
                }
            });

            XposedBridge.hookAllMethods(contextMenu, "onContextItemSelected",
                    new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        MenuItem item = (MenuItem) param.args[0];
                        if (item.getItemId() == 99993) {
                            Logger.i("[Sticky] 强制置顶");
                            param.setResult(true);
                        } else if (item.getItemId() == 99994) {
                            Logger.i("[Sticky] 取消所有置顶");
                            param.setResult(true);
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            Logger.w("[Sticky] ly3.k3失败: " + t.getMessage());
        }
    }
}
