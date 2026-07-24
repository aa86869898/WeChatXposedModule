/**
 * ================================================================
 *  红包播报 + 转账自动收款 — 零延迟完整版
 *  WeChat 8.0.76 (3140)
 * ================================================================
 *
 *  【转账自动收款】零延迟方案
 *   收到 type=49 + <type>2000</type> → 从 XML 提取 url
 *   → Intent 直接打开 RemittanceDetailUI → onResume 自动点击收款
 *
 *  【红包播报】
 *   onSceneEnd 回调 m1=v5 → 探测全字段 + 嵌套字段 (v5.h=e1)
 *   + 兜底: LuckyMoneyDetailUI.onResume 中读UI金额
 * ================================================================
 */

package com.example.leshao;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import java.lang.reflect.Field;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class MoneyHookV2 {

    static Handler sMainHandler = new Handler(Looper.getMainLooper());
    static Class<?> sM1Cls;
    static Context sContext;

    public static void hookAll(ClassLoader cl, Context ctx) {
        sContext = ctx;
        try { sM1Cls = cl.loadClass("com.tencent.mm.modelbase.m1"); }
        catch (Throwable t) { XposedBridge.log("[Money] m1 fail: " + t); return; }

        hookRedPacket(cl);
        hookAutoCollect(cl);
        XposedBridge.log("[Money] all hooks OK");
    }

    // ================================================================
    //  ★ 转账: 收到消息时直接打开详情页 (零延迟)
    // ================================================================

    /**
     * 在 MessageHook 检测到转账消息时调用
     * @param content 消息 XML
     */
    public static void onTransferMessage(String content) {
        if (content == null) return;
        String url = extractXml(content, "url");
        if (url == null || url.isEmpty()) return;
        try {
            Intent intent = new Intent();
            intent.setClassName("com.tencent.mm",
                "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI");
            intent.putExtra("key_scene", 1);
            intent.putExtra("key_url", url);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            sContext.startActivity(intent);
            XposedBridge.log("[Money] opened RemittanceDetailUI, url=" + url.substring(0, Math.min(60, url.length())));
        } catch (Throwable t) {
            XposedBridge.log("[Money] open RemittanceDetailUI fail: " + t);
        }
    }

    static String extractXml(String xml, String tag) {
        // 找 <url><![CDATA[...]]></url> 或 <url>...</url>
        int start = xml.indexOf("<" + tag);
        if (start < 0) return null;
        int gt = xml.indexOf(">", start);
        if (gt < 0) return null;
        int end = xml.indexOf("</" + tag + ">", gt);
        if (end < 0) return null;
        String val = xml.substring(gt + 1, end);
        // 去 CDATA
        if (val.startsWith("<![CDATA[")) {
            val = val.substring(9, val.indexOf("]]>"));
        }
        return val;
    }


    // ================================================================
    //  一、红包播报
    // ================================================================

    static void hookRedPacket(ClassLoader cl) {
        String[] classes = {
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNewReceiveUI",
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI",
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNotHookReceiveUI",
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyBusiReceiveUI",
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyBusiReceiveUIV2",
            "com.tencent.mm.plugin.luckymoney.hk.ui.LuckyMoneyHKReceiveUI",
        };
        for (String clsName : classes) {
            try {
                Class<?> uiCls = cl.loadClass(clsName);
                XposedHelpers.findAndHookMethod(uiCls, "onSceneEnd",
                    int.class, int.class, String.class, sM1Cls,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            int et = (int) p.args[0], ec = (int) p.args[1];
                            if (et == 0 && ec == 0) onRedPacket(p.args[3]);
                        }
                    });
            } catch (Throwable t) {
                XposedBridge.log("[Money] RP fail: " + clsName);
            }
        }

        // 兜底: read UI amount
        try {
            Class<?> detailCls = cl.loadClass("com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI");
            XposedHelpers.findAndHookMethod(detailCls, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    sMainHandler.postDelayed(() -> {
                        String amt = findAmountText(((Activity) p.thisObject).getWindow().getDecorView());
                        if (amt != null) { TtsEngine.speak("红包：" + amt + "元"); XposedBridge.log("[Money-RP-UI] " + amt); }
                    }, 500);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[Money] RP-UI fail: " + t);
        }
    }

    static void onRedPacket(Object resp) {
        try {
            if (resp == null) return;
            XposedBridge.log("[Money-RP] class=" + resp.getClass().getName());
            double amount = 0;
            String sender = null;
            for (Field f : resp.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                try {
                    Object v = f.get(resp);
                    if (v == null) continue;
                    // 一级: String m = "0.10"
                    if (v instanceof String && f.getName().equals("m")) {
                        try { amount = Double.parseDouble((String) v); }
                        catch (NumberFormatException ignored) {}
                    }
                    if (v instanceof String && (f.getName().contains("send") || f.getName().contains("from"))
                        && ((String) v).length() < 50) sender = (String) v;
                } catch (Exception ignored) {}
            }
            // 嵌套: v5.h
            for (Field f : resp.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                try {
                    Object nested = f.get(resp);
                    if (nested == null || nested instanceof String || nested instanceof Number) continue;
                    for (Field nf : nested.getClass().getDeclaredFields()) {
                        nf.setAccessible(true);
                        try {
                            Object nv = nf.get(nested);
                            if (nv == null) continue;
                            if (nv instanceof String && nf.getName().toLowerCase().contains("amount"))
                                try { amount = Double.parseDouble((String) nv); } catch (NumberFormatException ignored) {}
                            if (nv instanceof String && nf.getName().equals("Q")) sender = (String) nv;
                        } catch (Exception ignored2) {}
                    }
                } catch (Exception ignored) {}
            }
            if (amount > 0) {
                String yuan = String.format("%.2f", amount);
                String name = sender != null ? getNick(sender) : "好友";
                TtsEngine.speak(name + "的红包：" + yuan + "元");
                XposedBridge.log("[Money-RP] TTS: " + yuan + " from " + name);
            }
        } catch (Throwable t) {
            XposedBridge.log("[Money-RP] err: " + t);
        }
    }

    static String findAmountText(View root) {
        if (root instanceof TextView) {
            String t = ((TextView) root).getText().toString();
            if (t.matches(".*\\d+\\.\\d{2}.*") && t.length() < 20) return t.replaceAll("[^0-9.]", "");
        }
        if (root instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
                String r = findAmountText(((ViewGroup) root).getChildAt(i));
                if (r != null) return r;
            }
        return null;
    }


    // ================================================================
    //  二、转账播报 + 自动收款
    // ================================================================

    static void hookAutoCollect(ClassLoader cl) {
        try {
            Class<?> uiCls = cl.loadClass("com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI");
            XposedHelpers.findAndHookMethod(uiCls, "onSceneEnd",
                int.class, int.class, String.class, sM1Cls,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        int et = (int) p.args[0], ec = (int) p.args[1];
                        if (et == 0 && ec == 0) onTransfer(p.args[3]);
                    }
                });
            XposedHelpers.findAndHookMethod(uiCls, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    sMainHandler.postDelayed(() -> {
                        View btn = findCollectButton(((Activity) p.thisObject).getWindow().getDecorView());
                        if (btn != null) {
                            XposedBridge.log("[Money-AC] click: " + ((Button) btn).getText());
                            btn.performClick();
                        }
                    }, 800);
                }
            });
            XposedBridge.log("[Money] AC OK");
        } catch (Throwable t) {
            XposedBridge.log("[Money] AC fail: " + t);
        }
    }

    static void onTransfer(Object resp) {
        try {
            if (resp == null) return;
            double amount = -1; String sender = null; int q = 0;
            for (Field f : resp.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                try {
                    if (f.getType() == double.class) amount = f.getDouble(resp);
                    if (f.getName().equals("q")) q = f.getInt(resp);
                    if (f.getName().equals("m")) {
                        Object v = f.get(resp);
                        if (v instanceof String && !((String) v).isEmpty()) sender = (String) v;
                    }
                } catch (Exception ignored) {}
            }
            if (amount <= 0 || q != 1) return;
            String name = sender != null ? getNick(sender) : "好友";
            TtsEngine.speak("收到" + name + "转账" + String.format("%.2f", amount) + "元");
            XposedBridge.log("[Money-AC] TTS: " + amount);
        } catch (Throwable t) {
            XposedBridge.log("[Money-AC] err: " + t);
        }
    }

    static View findCollectButton(View root) {
        if (root instanceof Button && (root.isClickable() || root.isEnabled())) {
            String t = ((Button) root).getText().toString();
            if (t.contains("收款") || t.contains("确认收款") || t.contains("收钱")) return root;
        }
        if (root instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
                View r = findCollectButton(((ViewGroup) root).getChildAt(i));
                if (r != null) return r;
            }
        return null;
    }

    static String getNick(String talker) {
        if (talker == null || talker.isEmpty()) return "好友";
        try {
            Class<?> nr = Class.forName("com.example.leshao.NickResolver");
            return (String) nr.getMethod("get", String.class).invoke(null, talker);
        } catch (Throwable t) { return talker; }
    }
}


/**
 * ================================================================
 *  集成到 MessageHook (零延迟)
 * ================================================================
 *
 * 在 MsgHook.onMessage() 中 dispatch 前加:
 *
 * // --- 转账: 零延迟直接打开详情页 ---
 * if (type == 49 && content != null && content.contains("<type>2000</type>")) {
 *     if (isSend != 1) {  // 只处理收到的转账
 *         MoneyHookV2.onTransferMessage(content);
 *     }
 * }
 *
 * 在 HookManager 中:
 *   MoneyHookV2.hookAll(cl, sAppContext);
 *
 * ================================================================
 *  流程:
 *   收到转账消息 → 提取 url → Intent 打开 RemittanceDetailUI
 *     → onResume 自动点击收款按钮 → onSceneEnd 回调 → TTS播报
 * ================================================================
 */
