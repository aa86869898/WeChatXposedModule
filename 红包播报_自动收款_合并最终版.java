/**
 * ================================================================
 *  红包播报 + 转账自动收款 — 合并最终版
 *  WeChat 8.0.76 (3140)
 * ================================================================
 *
 *  前提:
 *    红包自动抢 → 抢到后 LuckyMoneyDetailUI 页面自动打开 ✅ (已实现)
 *    转账延迟打开 → 收到消息后 RemittanceDetailUI 延迟打开 ✅ (已实现)
 *
 *  本类负责:
 *    红包: 页面打开后 800ms 扫描UI金额 → TTS播报
 *    转账: 页面打开后自动点"收款"按钮(带重试) → onSceneEnd回调 → TTS播报
 *
 *  无跳转 — 只负责自动点击和播报
 * ================================================================
 */

package com.example.leshao;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class MoneyHook {

    static Handler sH = new Handler(Looper.getMainLooper());
    static Class<?> sM1Cls;

    public static void init(ClassLoader cl) {
        try { sM1Cls = cl.loadClass("com.tencent.mm.modelbase.m1"); }
        catch (Throwable t) { XposedBridge.log("[Money] m1: " + t); return; }
        hookRedPacket(cl);
        hookTransfer(cl);
        XposedBridge.log("[Money] OK");
    }


    // ================================================================
    //  红包 — 页面已自动打开, 读UI金额播报
    // ================================================================

    static void hookRedPacket(ClassLoader cl) {
        try {
            Class<?> cls = cl.loadClass(
                "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI");
            XposedHelpers.findAndHookMethod(cls, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Activity act = (Activity) p.thisObject;
                    sH.postDelayed(() -> {
                        String amt = scanAmount(act.getWindow().getDecorView());
                        if (amt != null && !amt.isEmpty()) {
                            TtsEngine.speak("红包：" + amt + "元");
                            XposedBridge.log("[Money-RP] " + amt);
                        }
                    }, 800);
                }
            });
            XposedBridge.log("[Money] RP OK");
        } catch (Throwable t) {
            XposedBridge.log("[Money] RP fail: " + t);
        }
    }

    /** 递归扫描TextView, 正则匹配金额格式 "0.xx" */
    static String scanAmount(View root) {
        if (root instanceof TextView) {
            String t = ((TextView) root).getText().toString();
            java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(\\d+\\.\\d{2})").matcher(t);
            if (m.find() && t.length() < 20) return m.group(1);
        }
        if (root instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
                String r = scanAmount(((ViewGroup) root).getChildAt(i));
                if (r != null) return r;
            }
        return null;
    }


    // ================================================================
    //  转账 — 页面已延迟打开, 自动点收款 + 播报
    // ================================================================

    static void hookTransfer(ClassLoader cl) {
        try {
            Class<?> cls = cl.loadClass(
                "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI");

            // onResume → 自动点收款
            XposedHelpers.findAndHookMethod(cls, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Activity act = (Activity) p.thisObject;
                    autoClickCollect(act, 800);
                }
            });

            // onSceneEnd → TTS播报
            XposedHelpers.findAndHookMethod(cls, "onSceneEnd",
                int.class, int.class, String.class, sM1Cls,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        int et = (int) p.args[0], ec = (int) p.args[1];
                        if (et != 0 || ec != 0) return;
                        Object resp = p.args[3];
                        double amt = getDouble(resp, "f");
                        int q = getInt(resp, "q");
                        if (amt > 0 && q == 1) {
                            TtsEngine.speak("收到转账" + String.format("%.2f", amt) + "元");
                            XposedBridge.log("[Money-AC] TTS: " + amt);
                        }
                    }
                });

            XposedBridge.log("[Money] AC OK");
        } catch (Throwable t) {
            XposedBridge.log("[Money] AC fail: " + t);
        }
    }

    /** 自动点击收款按钮 — 带重试 */
    static void autoClickCollect(Activity act, int delayMs) {
        sH.postDelayed(() -> {
            try {
                List<View> candidates = new ArrayList<>();
                collectClickableViews(act.getWindow().getDecorView(), candidates);

                if (candidates.isEmpty()) {
                    XposedBridge.log("[Money-AC] no candidates, delay=" + delayMs);
                    if (delayMs < 2500) autoClickCollect(act, delayMs + 800);
                    return;
                }

                // 打印所有候选
                XposedBridge.log("[Money-AC] delay=" + delayMs + " candidates=" + candidates.size());
                for (View v : candidates) {
                    String info = v.getClass().getSimpleName() + " ";
                    if (v instanceof Button) info += "btn=[" + ((Button) v).getText() + "]";
                    else if (v instanceof TextView) info += "txt=[" + ((TextView) v).getText() + "]";
                    info += " clk=" + v.isClickable();
                    XposedBridge.log("[Money-AC]   " + info);
                }

                // 匹配: "收款" 但不含 "已收款"
                for (View v : candidates) {
                    String text = getViewText(v);
                    if (text != null && text.contains("收款") && !text.contains("已收款")) {
                        XposedBridge.log("[Money-AC] click: " + text);
                        v.performClick();
                        return;
                    }
                }

                // 匹配: "收钱"/"确认"
                for (View v : candidates) {
                    String text = getViewText(v);
                    if (text != null && (text.contains("收钱") || text.contains("确认"))) {
                        XposedBridge.log("[Money-AC] fallback click: " + text);
                        v.performClick();
                        return;
                    }
                }

                // 没找到就重试
                if (delayMs < 2500) {
                    XposedBridge.log("[Money-AC] no match, retry " + (delayMs + 800));
                    autoClickCollect(act, delayMs + 800);
                } else {
                    XposedBridge.log("[Money-AC] give up");
                }

            } catch (Throwable t) {
                XposedBridge.log("[Money-AC] err: " + t);
            }
        }, delayMs);
    }

    static void collectClickableViews(View root, List<View> out) {
        if (root == null) return;
        // 收集Button + 含关键词的TextView
        if (root.isClickable() && root.isEnabled()) {
            if (root instanceof Button) {
                out.add(root);
            } else if (root instanceof TextView) {
                String t = ((TextView) root).getText().toString();
                if (t.contains("收款") || t.contains("收钱") || t.contains("确认"))
                    out.add(root);
            }
        }
        if (root instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++)
                collectClickableViews(((ViewGroup) root).getChildAt(i), out);
    }

    static String getViewText(View v) {
        if (v instanceof Button) return ((Button) v).getText().toString();
        if (v instanceof TextView) return ((TextView) v).getText().toString();
        return null;
    }

    static double getDouble(Object o, String n) {
        try { Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); return f.getDouble(o); }
        catch (Throwable t) { return -1; }
    }
    static int getInt(Object o, String n) {
        try { Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); return f.getInt(o); }
        catch (Throwable t) { return -1; }
    }
}


/**
 * ================================================================
 *  集成方式
 * ================================================================
 *
 *  HookManager 中:
 *    MoneyHook.init(cl);
 *
 *  (不需要传 Context, 不需要跳转, 只负责点击和播报)
 *
 * ================================================================
 *  效果
 * ================================================================
 *
 *  红包:
 *    自动抢到 → LuckyMoneyDetailUI.onResume
 *    → 800ms后扫描UI金额 → TTS: "红包：0.05元"
 *
 *  转账:
 *    收到消息 → 延迟打开 RemittanceDetailUI (你已实现)
 *    → onResume → 800ms后找"收款"按钮(带重试最多到2500ms)
 *    → 找到→点击 → onSceneEnd回调(q=1)
 *    → TTS: "收到转账0.05元"
 *
 *  全程无跳转, 只负责自动点击 和 TTS播报
 * ================================================================
 */
