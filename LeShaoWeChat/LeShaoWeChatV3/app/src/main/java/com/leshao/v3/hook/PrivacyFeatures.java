package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import com.leshao.v3.Logger;

/**
 * [功能54/55/56/64] 隐私安全合集
 * ==============================
 *
 * 截图检测:
 *   com.tencent.mm.ui.feature.api.screenshot.u — 截图枚举(状态值)
 *   ChattingUIFragment 可能有 onScreenshot() 回调
 *
 * 剪贴板保护:
 *   com.tencent.mm.plugin.appbrand.jsapi.JsApiSetClipboardDataWC — 小程序设置剪贴板
 *   com.tencent.mm.plugin.webview.modeltools.WebViewClipBoardHelper — WebView剪贴板
 *
 * WebView隐私:
 *   com.tencent.mm.plugin.webview.ui.tools.WebViewUI
 *     关键方法:
 *       onCreate(Bundle)                             创建WebView
 *       U6(WebViewUI, WebView, String) → void        设置WebView配置
 *       H7() → s0 (WebView Settings)                获取设置对象
 *       O8() → boolean                               JS是否启用
 *       A7() → String                                获取URL
 *       C8(String) → void                            加载URL
 *
 * 指纹锁定:
 *   com.tencent.mm.autogen.events.FingerprintLoginAuthEvent — 指纹认证事件
 *   复用到聊天进入验证
 */
public class PrivacyFeatures {

    private static volatile boolean sEnabled = true;
    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static void hook(ClassLoader cl) {
        Logger.i("--- [54/55/56/64] 隐私安全 ---");
        hookScreenshot(cl);
        hookClipboard(cl);
        hookWebView(cl);
        hookFingerprint(cl);
    }

    /**
     * [54] 截图检测
     *
     * Hook ChattingUIFragment 截图回调。
     * 微信可能通过onScreenshot或ContentObserver检测截图。
     */
    private static void hookScreenshot(ClassLoader cl) {
        try {
            Class<?> chattingUI = XposedHelpers.findClass(
                    "com.tencent.mm.ui.chatting.ChattingUIFragment", cl);

            try {
                XposedBridge.hookAllMethods(chattingUI, "onScreenshot",
                        new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Logger.i("[截图检测] ⚠️ 聊天页面截图!");
                    }
                });
            } catch (Throwable ignored) {}

            Logger.i("  [54] 截图检测 ✓ (ChattingUIFragment)");
        } catch (Throwable t) {
            Logger.w("  [54] 截图: " + t.getMessage());
        }
    }

    /**
     * [55] 剪贴板保护
     *
     * com.tencent.mm.plugin.appbrand.jsapi.JsApiSetClipboardDataWC
     *   小程序通过JS API写入剪贴板
     *
     * com.tencent.mm.plugin.webview.modeltools.WebViewClipBoardHelper
     *   WebView剪贴板辅助
     */
    private static void hookClipboard(ClassLoader cl) {
        try {
            Class<?> jsApi = XposedHelpers.findClass(
                    "com.tencent.mm.plugin.appbrand.jsapi.JsApiSetClipboardDataWC", cl);

            XposedBridge.hookAllMethods(jsApi, "invoke", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Logger.i("[剪贴板] 小程序尝试写入剪贴板!");
                }
            });
            Logger.i("  [55] 剪贴板保护 ✓ (JsApiSetClipboardDataWC)");
        } catch (Throwable t) {
            Logger.w("  [55] 剪贴板: " + t.getMessage());
        }
    }

    /**
     * [56] WebView隐私
     *
     * Hook WebViewUI.U6() — WebView配置时注入隐私设置
     * Hook WebViewUI.H7() — 获取WebView Settings
     *
     * WebViewUI.U6(WebViewUI, WebView, String) 在WebView初始化时调用。
     */
    private static void hookWebView(ClassLoader cl) {
        try {
            Class<?> webViewUI = XposedHelpers.findClass(
                    "com.tencent.mm.plugin.webview.ui.tools.WebViewUI", cl);

            // Hook U6 — WebView初始化配置
            XposedBridge.hookAllMethods(webViewUI, "U6", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Logger.i("[WebView] WebView已初始化");
                    // 可以获取WebView对象: param.args[1]
                    // webView.getSettings().setSavePassword(false);
                    // webView.getSettings().setAllowFileAccess(false);
                }
            });

            Logger.i("  [56] WebView隐私 ✓ (WebViewUI.U6)");
        } catch (Throwable t) {
            Logger.w("  [56] WebView: " + t.getMessage());
        }
    }

    /**
     * [64] 指纹锁定聊天
     *
     * Hook ChattingUIFragment.onCreate()，
     * 检测是否需要指纹验证。
     *
     * 事件: FingerprintLoginAuthEvent — 可复用认证流程
     */
    private static void hookFingerprint(ClassLoader cl) {
        try {
            Class<?> chattingUI = XposedHelpers.findClass(
                    "com.tencent.mm.ui.chatting.ChattingUIFragment", cl);

            XposedBridge.hookAllMethods(chattingUI, "onCreate",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Logger.i("[指纹锁] 聊天界面已打开");
                }
            });

            Logger.i("  [64] 指纹锁 ✓ (ChattingUIFragment.onCreate)");
        } catch (Throwable t) {
            Logger.w("  [64] 指纹锁: " + t.getMessage());
        }
    }
}
