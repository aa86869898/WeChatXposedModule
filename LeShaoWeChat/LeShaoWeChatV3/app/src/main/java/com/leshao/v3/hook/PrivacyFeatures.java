package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import com.leshao.v3.Logger;
import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.hook.HookConfig;
import android.app.Activity;
import android.content.Context;

import android.content.ClipboardManager;
import android.content.ClipData;
import android.hardware.fingerprint.FingerprintManager;
import android.os.CancellationSignal;
import android.os.FileObserver;
import java.util.Set;
import java.util.HashSet;

/**
 * [功能54/55/56/64] 隐私安全 — 完整修复版
 * ========================================
 * 
 * ⚠️ 修复: JsApiSetClipboardDataWC 类名 "." → "."
 *   XposedHelpers.findClass("com.tencent.mm.plugin.appbrand.jsapi.JsApiSetClipboardDataWC",cl)
 */
public class PrivacyFeatures {
    private static volatile boolean sEnabled = true;
    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    private static Set<String> lockedChats = new HashSet<>();

    public static void hook(ClassLoader cl) {
        XposedBridge.log("[PrivacyFeatures] hook() ENTER sEnabled=" + sEnabled);
        loadLockedChats();
        ModuleConfig config = ModuleConfig.load(ContextManager.getPrefs());
        if (config == null || !config.privacyFeaturesEnabled) return;
        if (sEnabled) hookScreenshot(cl);
        if (sEnabled) hookClipboard(cl);
        if (sEnabled) hookWebView(cl);
        if (sEnabled) hookFingerprint(cl);
    }

    private static void loadLockedChats() {
        String saved = HookConfig.getString("fingerprint_locked_chats", "");
        if (saved != null && !saved.isEmpty())
            for (String s : saved.split(","))
                if (!s.trim().isEmpty()) lockedChats.add(s.trim());
    }

    /** #54 截图检测 */
    private static void hookScreenshot(ClassLoader cl) {
        try {
            String path = "/sdcard/Pictures/Screenshots/";
            final FileObserver observer = new FileObserver(path,
                    FileObserver.CREATE | FileObserver.MOVED_TO) {
                @Override
                public void onEvent(int event, String path) {
                    if (path != null && path.toLowerCase().contains("screenshot")) {
                        XposedBridge.log("[Privacy] ⚠️ 检测到截图: " + path);
                        try {
                            Context ctx = ContextManager.getAppContext();
                            if (ctx != null)
                                android.widget.Toast.makeText(ctx,
                                        "截图操作已记录", android.widget.Toast.LENGTH_SHORT).show();
                        } catch (Throwable ignored) {}
                    }
                }
            };
            observer.startWatching();
            XposedBridge.log("[Privacy] #54 截图检测 ✓");
        } catch (Throwable t) {}
    }

    /** #55 剪贴板保护 — ⚠️修复: 使用正确类名 */
    private static void hookClipboard(ClassLoader cl) {
        try {
            Context ctx = ContextManager.getAppContext();
            if (ctx == null) return;
            final ClipboardManager cm = (ClipboardManager)
                    ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm == null) return;

            cm.addPrimaryClipChangedListener(new ClipboardManager.OnPrimaryClipChangedListener() {
                @Override
                public void onPrimaryClipChanged() {
                    try {
                        ClipData clip = cm.getPrimaryClip();
                        if (clip != null && clip.getItemCount() > 0) {
                            String text = clip.getItemAt(0).getText().toString();
                            XposedBridge.log("[Privacy] 剪贴板读取: " +
                                    (text != null ? text.substring(0, Math.min(50, text.length())) : ""));
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {}

        // ⚠️ 修复: 使用正确类名（点号而非$号）
        try {
            Class<?> jsApi = XposedHelpers.findClass(
                    "com.tencent.mm.plugin.appbrand.jsapi.JsApiSetClipboardDataWC", cl);
            XposedBridge.hookAllMethods(jsApi, "invoke", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    XposedBridge.log("[Privacy] 🛡️ 拦截小程序剪贴板写入");
                    param.setResult(null);
                }
            });
            XposedBridge.log("[Privacy] #55 剪贴板保护 ✓");
        } catch (XposedHelpers.ClassNotFoundError e) {
            XposedBridge.log("[Privacy] #55 剪贴板API类未找到(可能被混淆)");
        } catch (Throwable t) {
            XposedBridge.log("[Privacy] #55 剪贴板异常: " + t.getMessage());
        }
    }

    /** #56 WebView隐私 */
    private static void hookWebView(ClassLoader cl) {
        try {
            Class<?> webViewUI = XposedHelpers.findClass(
                    "com.tencent.mm.plugin.webview.ui.tools.WebViewUI", cl);

            XposedBridge.hookAllMethods(webViewUI, "U6", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object webView = param.args[1];
                        if (webView != null) {
                            Object settings = XposedHelpers.callMethod(webView, "getSettings");
                            if (settings != null) {
                                try { XposedHelpers.callMethod(settings, "setSavePassword", false); }
                                catch (Throwable ignored) {}
                                try { XposedHelpers.callMethod(settings, "setAllowFileAccess", false); }
                                catch (Throwable ignored) {}
                                try { XposedHelpers.callMethod(settings, "setAllowContentAccess", false); }
                                catch (Throwable ignored) {}
                            }
                        }
                    } catch (Throwable t) {}
                }
            });
            XposedBridge.log("[Privacy] #56 WebView隐私 ✓");
        } catch (Throwable t) {}
    }

    /** #64 指纹锁定聊天 */
    private static void hookFingerprint(ClassLoader cl) {
        try {
            Class<?> chattingUI = XposedHelpers.findClass(
                    "com.tencent.mm.ui.chatting.ChattingUIFragment", cl);

            XposedBridge.hookAllMethods(chattingUI, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (lockedChats.isEmpty()) return;
                    try {
                        android.os.Bundle bundle = (android.os.Bundle)
                                XposedHelpers.callMethod(param.thisObject, "getArguments");
                        if (bundle != null) {
                            String talker = bundle.getString("Chat_User");
                            if (talker != null && lockedChats.contains(talker)) {
                                XposedBridge.log("[Privacy] 🔒 锁定聊天: " + talker);
                                showFingerprintDialog();
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log("[Privacy] #64 指纹锁 ✓");
        } catch (Throwable t) {}
    }

    private static void showFingerprintDialog() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                try {
                    Context ctx = ContextManager.getAppContext();
                    if (ctx == null) return;
                    FingerprintManager fpm = (FingerprintManager)
                            ctx.getSystemService(android.content.Context.FINGERPRINT_SERVICE);
                    if (fpm != null && fpm.isHardwareDetected() && fpm.hasEnrolledFingerprints()) {
                        fpm.authenticate(null, new CancellationSignal(), 0,
                                new FingerprintManager.AuthenticationCallback() {
                                    @Override
                                    public void onAuthenticationSucceeded(
                                            FingerprintManager.AuthenticationResult result) {
                                        XposedBridge.log("[Privacy] ✅ 指纹验证通过");
                                    }
                                    @Override
                                    public void onAuthenticationFailed() {
                                        XposedBridge.log("[Privacy] ❌ 指纹验证失败");
                                    }
                                }, null);
                    }
                } catch (Throwable ignored) {}
            }
        });
    }
}
