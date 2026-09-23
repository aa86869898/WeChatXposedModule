package com.leshao.v3.ui;

import android.app.Dialog;
import android.view.KeyEvent;
import android.widget.PopupWindow;

import com.leshao.v3.LogWriter;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * v1015: 全局窗口返回栈安装器。
 *
 * <p>一次性 hook {@link Dialog} 与 {@link PopupWindow} 的 show/dismiss，自动登记到
 * {@link UiBackStack}；并 hook {@code Dialog.onBackPressed} 与 PopupWindow 内部
 * {@code PopupDecorView.dispatchKeyEvent}，实现「三级返回上一层」的统一返回语义。
 * 由于是全局 hook，模块内所有弹窗（含各 Hook 内临时创建的 Dialog）自动覆盖，无需逐处改造。</p>
 */
public final class UiBackInstaller {

    private static final String TAG = "UiBackInstaller";

    private UiBackInstaller() {
    }

    public static void install(ClassLoader cl) {
        hookDialog();
        hookPopupWindow(cl);
    }

    private static void hookDialog() {
        try {
            XposedBridge.hookAllMethods(Dialog.class, "show", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Dialog d = (Dialog) param.thisObject;
                        UiBackStack.push(d, d::dismiss);
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.hookAllMethods(Dialog.class, "dismiss", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try { UiBackStack.remove(param.thisObject); } catch (Throwable ignored) {}
                }
            });
            XposedBridge.hookAllMethods(Dialog.class, "onBackPressed", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        // 仅当该 Dialog 之上还有子窗口时拦截：关闭子窗口并消费返回键
                        if (UiBackStack.interceptDialogBack(param.thisObject)) {
                            param.setResult(null);
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookDialog 失败: " + t);
        }
    }

    private static void hookPopupWindow(ClassLoader cl) {
        try {
            XposedBridge.hookAllMethods(PopupWindow.class, "showAtLocation", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    register(param.thisObject);
                }
            });
            XposedBridge.hookAllMethods(PopupWindow.class, "showAsDropDown", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    register(param.thisObject);
                }
            });
            XposedBridge.hookAllMethods(PopupWindow.class, "dismiss", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try { UiBackStack.remove(param.thisObject); } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookPopupWindow 失败: " + t);
        }
        // PopupDecorView 的返回键分发：允许注册的自定义返回动作拦截
        try {
            Class<?> decor = XposedHelpers.findClass("android.widget.PopupWindow$PopupDecorView", cl);
            XposedBridge.hookAllMethods(decor, "dispatchKeyEvent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        KeyEvent ev = (KeyEvent) param.args[0];
                        if (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_BACK
                                && ev.getAction() == KeyEvent.ACTION_UP
                                && UiBackStack.interceptPopupBack()) {
                            param.setResult(Boolean.TRUE);
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookPopupDecorView 失败: " + t);
        }
    }

    private static void register(Object pw) {
        try {
            if (pw instanceof PopupWindow) {
                PopupWindow p = (PopupWindow) pw;
                UiBackStack.push(p, p::dismiss);
            }
        } catch (Throwable ignored) {}
    }
}
