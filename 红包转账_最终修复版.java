/**
 * ================================================================
 *  红包/转账播报 + 自动收款 — 最终修复版
 *  WeChat 8.0.76 (版号 3140)
 * ================================================================
 *
 *  【根因与修复】
 *  22:04日志: tts FAILED: ...#onSceneEnd[...]#exact
 *  → 用 #exact 匹配导致所有 Hook 失败
 *  → 换回 findAndHookMethod (21:49日志中 probe OK 的方式)
 *
 *  【字段确认 (21:50探测日志)】
 *  respClass: com.tencent.mm.plugin.remittance.model.g1
 *    double f = 0.02       ← 金额(元)
 *    String m = wxid_xxx   ← 付款人
 *    int    q = 0→1        ← 0=待收, 1=已收
 *    int    h = 2000/2001  ← 2000=转账, 2001=红包
 *    String r              ← 状态文字
 *
 *  【onSceneEnd 触发两次】
 *  第一次: q=0, r="待你收款"   → 不播报
 *  第二次: q=1, r="你已收款"   → 播报金额
 *
 *  【自动收款】
 *  RemittanceDetailUI.onResume() → 延迟遍历子 View
 *  → 找到文字含"确认收款"的按钮 → performClick()
 *
 * ================================================================
 *  使用方法: 将此文件替换 MoneyHook.java
 *  在 HookManager 中调用: MoneyHook.hookAll(cl);
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
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class MoneyHook {

    static Handler sMainHandler = new Handler(Looper.getMainLooper());
    static Class<?> sM1Cls;

    // ================================================================
    //  入口
    // ================================================================

    public static void hookAll(ClassLoader cl) {
        try { sM1Cls = cl.loadClass("com.tencent.mm.modelbase.m1"); }
        catch (Throwable t) { XposedBridge.log("[MoneyHook] m1 FAIL: " + t); return; }

        hookRedPacket(cl);
        hookAutoCollect(cl);
        XposedBridge.log("[MoneyHook] all hooks OK");
    }

    // ================================================================
    //  红包 Hook (6个UI类)
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
                // onSceneEnd(int, int, String, m1) — 4参数 ★
                XposedHelpers.findAndHookMethod(uiCls, "onSceneEnd",
                    int.class, int.class, String.class, sM1Cls,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            int et = (int) p.args[0];
                            int ec = (int) p.args[1];
                            if (et == 0 && ec == 0) onMoneyResult(p.args[3], "红包");
                        }
                    });
                XposedBridge.log("[MoneyHook] 红包 OK: " + clsName);
            } catch (Throwable t) {
                XposedBridge.log("[MoneyHook] 红包 FAIL: " + clsName);
            }
        }
    }

    // ================================================================
    //  转账 Hook (播报 + 自动收款)
    // ================================================================

    static void hookAutoCollect(ClassLoader cl) {
        try {
            Class<?> uiCls = cl.loadClass(
                "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI");

            // ── onSceneEnd(int, int, String, m1) — 4参数 ★ ──
            XposedHelpers.findAndHookMethod(uiCls, "onSceneEnd",
                int.class, int.class, String.class, sM1Cls,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        int et = (int) p.args[0];
                        int ec = (int) p.args[1];
                        if (et == 0 && ec == 0) onMoneyResult(p.args[3], "转账");
                    }
                });

            // ── 自动收款: onResume — 递归找按钮点击 ──
            XposedHelpers.findAndHookMethod(uiCls, "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        autoClickConfirm((Activity) p.thisObject);
                    }
                });

            XposedBridge.log("[MoneyHook] 转账 OK");
        } catch (Throwable t) {
            XposedBridge.log("[MoneyHook] 转账 FAIL: " + t.getMessage());
        }
    }


    // ================================================================
    //  金额播报逻辑
    // ================================================================

    static void onMoneyResult(Object resp, String type) {
        try {
            if (resp == null) return;

            // 1. 金额: double f
            double amount = -1;
            try {
                Field f = resp.getClass().getDeclaredField("f");
                f.setAccessible(true);
                if (f.getType() == double.class) amount = f.getDouble(resp);
            } catch (Throwable e) {
                for (Field f : resp.getClass().getDeclaredFields()) {
                    if (f.getType() == double.class) { f.setAccessible(true); amount = f.getDouble(resp); break; }
                }
            }
            if (amount <= 0) return;

            // 2. 状态: int q — 0=待确认, 1=已确认
            int q = 0;
            try { Field f = resp.getClass().getDeclaredField("q"); f.setAccessible(true); q = f.getInt(resp); }
            catch (Throwable ignored) {}
            if (q != 1) return;

            // 3. 发送者: String m
            String sender = null;
            try {
                Field f = resp.getClass().getDeclaredField("m"); f.setAccessible(true);
                Object v = f.get(resp);
                if (v instanceof String && !((String) v).isEmpty()) sender = (String) v;
            } catch (Throwable ignored) {}

            // 4. 播报
            String yuan = String.format("%.2f", amount);
            String name = getNick(sender);
            String speak = "转账".equals(type)
                ? "收到" + name + "转账" + yuan + "元"
                : name + "的红包：" + yuan + "元";

            TtsEngine.speak(speak);
            XposedBridge.log("[MoneyHook] " + speak);

        } catch (Throwable t) {
            XposedBridge.log("[MoneyHook] ERR: " + t);
        }
    }


    // ================================================================
    //  自动收款 — 遍历 View 树找确认按钮
    // ================================================================

    static void autoClickConfirm(final Activity activity) {
        if (activity == null) return;
        sMainHandler.postDelayed(() -> {
            try {
                View btn = findBtn(activity.getWindow().getDecorView(),
                    "确认收款", "收钱", "收款", "确认", "领取");
                if (btn != null) {
                    XposedBridge.log("[MoneyHook] 自动点击: " +
                        (btn instanceof TextView ? ((TextView) btn).getText() : btn.getClass().getSimpleName()));
                    btn.performClick();
                }
            } catch (Throwable t) {
                XposedBridge.log("[MoneyHook] autoClick err: " + t);
            }
        }, 800);
    }

    static View findBtn(View root, String... keywords) {
        if (root == null) return null;
        if (root instanceof Button || root instanceof TextView) {
            CharSequence text = null;
            if (root instanceof Button) text = ((Button) root).getText();
            else text = ((TextView) root).getText();
            if (text != null) {
                String t = text.toString();
                for (String kw : keywords)
                    if (t.contains(kw) && (root.isClickable() || root.isEnabled()))
                        return root;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View r = findBtn(vg.getChildAt(i), keywords);
                if (r != null) return r;
            }
        }
        return null;
    }


    // ================================================================
    //  昵称查询
    // ================================================================

    static String getNick(String talker) {
        if (talker == null || talker.isEmpty()) return "好友";
        try {
            Class<?> nr = Class.forName("com.example.leshao.NickResolver");
            return (String) nr.getMethod("get", String.class).invoke(null, talker);
        } catch (Throwable t) {
            return talker;
        }
    }
}


/**
 * ================================================================
 *  修改清单 (相对于之前版本)
 * ================================================================
 *
 *  1. ❌ 删除所有 findMethodExact / #exact 调用
 *     → 这是 22:04 日志中所有 Hook 失败的根因
 *
 *  2. ✅ 只保留 findAndHookMethod + 4参数
 *     → onSceneEnd(int, int, String, m1)
 *     → 这是 21:49 日志中 probe OK 的方式
 *
 *  3. ✅ 金额: double f (已经是元, 不需要 ÷100)
 *  4. ✅ 状态: int q (1才播报)
 *  5. ✅ 发送者: String m
 *  6. ✅ 自动收款: onResume → 800ms延迟 → 找"确认收款"按钮 → click
 *
 * ================================================================
 *  验证方式
 * ================================================================
 *
 *  日志应出现:
 *    [MoneyHook] 红包 OK: com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNewReceiveUI
 *    [MoneyHook] 红包 OK: com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI
 *    ...
 *    [MoneyHook] 转账 OK
 *    [MoneyHook] all hooks OK
 *
 *  点开转账卡片后:
 *    [MoneyHook] 自动点击: 确认收款
 *    [MoneyHook] 收到乐少转账0.01元
 *
 * ================================================================
 */
