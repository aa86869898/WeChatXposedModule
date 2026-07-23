/**
 * ================================================================
 *  红包/转账播报 + 自动收款 — 完整合并版
 * ================================================================
 *
 * 探测日志发现:
 *
 * ┌ 转账响应 (remittance.model.g1) ──────────────────────
 * │ double f  = 0.02       ← ★ 金额(元)
 * │ String m  = wxid_xxx   ← ★ 付款人
 * │ String r  = "待你收款"  → "你已收款，资金已存入零钱"
 * │ int    q  = 0          → 1 (收款后)
 * │ int    h  = 2000       ← 2000=转账, 2001=红包
 * └──────────────────────────────────────────────────────
 *
 * onSceneEnd 触发两次:
 *   第一次: q=0, r="待你收款" → 不播报
 *   第二次: q=1, r="你已收款" → 播报金额
 *
 * ================================================================
 *  文件包含:
 *   1. RedPacketHook     — 红包领取播报
 *   2. AutoCollectHook   — 转账收款播报 + 自动点击确认按钮
 *   3. 使用方法说明
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

    // ================================================================
    //  一、Hook 注册 (在 handleLoadPackage 中调用)
    // ================================================================

    public static void hookAll(ClassLoader cl) {
        hookRedPacket(cl);
        hookAutoCollect(cl);
    }

    // ── 红包 Hook ──
    static void hookRedPacket(ClassLoader cl) {
        String[] classes = {
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNewReceiveUI",
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI",
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNotHookReceiveUI",
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyBusiReceiveUI",
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyBusiReceiveUIV2",
            "com.tencent.mm.plugin.luckymoney.hk.ui.LuckyMoneyHKReceiveUI",
        };
        Class<?> m1Cls = null;
        try { m1Cls = cl.loadClass("com.tencent.mm.modelbase.m1"); } catch (Throwable ignored) {}

        for (String clsName : classes) {
            try {
                Class<?> uiCls = cl.loadClass(clsName);
                // Hook onSceneEnd(int,int,String,m1,boolean) — 5参数
                if (m1Cls != null) {
                    XposedHelpers.findAndHookMethod(uiCls, "onSceneEnd",
                        int.class, int.class, String.class, m1Cls, boolean.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) {
                                int et = (int) p.args[0];
                                int ec = (int) p.args[1];
                                if (et == 0 && ec == 0) onMoneyResult(p.args[3], "红包");
                            }
                        });
                }
                // onSceneEnd(int,int,String,m1) — 4参数
                if (m1Cls != null) {
                    XposedHelpers.findAndHookMethod(uiCls, "onSceneEnd",
                        int.class, int.class, String.class, m1Cls,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) {
                                int et = (int) p.args[0];
                                int ec = (int) p.args[1];
                                if (et == 0 && ec == 0) onMoneyResult(p.args[3], "红包");
                            }
                        });
                }
            } catch (Throwable t) {
                XposedBridge.log("[MoneyHook] " + clsName + " FAIL: " + t.getMessage());
            }
        }
    }

    // ── 转账 Hook (播报 + 自动收款) ──
    static void hookAutoCollect(ClassLoader cl) {
        try {
            Class<?> m1Cls = cl.loadClass("com.tencent.mm.modelbase.m1");
            Class<?> uiCls = cl.loadClass(
                "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI");

            // onSceneEnd(5参数)
            XposedHelpers.findAndHookMethod(uiCls, "onSceneEnd",
                int.class, int.class, String.class, m1Cls, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        int et = (int) p.args[0];
                        int ec = (int) p.args[1];
                        if (et == 0 && ec == 0) onMoneyResult(p.args[3], "转账");
                    }
                });

            // onSceneEnd(4参数)
            XposedHelpers.findAndHookMethod(uiCls, "onSceneEnd",
                int.class, int.class, String.class, m1Cls,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        int et = (int) p.args[0];
                        int ec = (int) p.args[1];
                        if (et == 0 && ec == 0) onMoneyResult(p.args[3], "转账");
                    }
                });

            // ★★★ 自动收款: Hook RemittanceDetailUI.onResume ★★★
            XposedHelpers.findAndHookMethod(uiCls, "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        autoClickConfirm((Activity) p.thisObject);
                    }
                });

            XposedBridge.log("[MoneyHook] AutoCollect OK");
        } catch (Throwable t) {
            XposedBridge.log("[MoneyHook] AutoCollect FAIL: " + t.getMessage());
        }
    }


    // ================================================================
    //  二、收款金额播报
    // ================================================================

    /**
     * 从 m1 响应对象提取金额并播报
     * @param resp   m1 响应对象 (remittance.model.g1)
     * @param type   "红包" 或 "转账"
     */
    static void onMoneyResult(Object resp, String type) {
        try {
            if (resp == null) return;

            // ── 1. 金额: double f ──
            double amount = -1;
            try {
                Field f = resp.getClass().getDeclaredField("f");
                f.setAccessible(true);
                amount = f.getDouble(resp);
            } catch (Throwable e) {
                for (Field f : resp.getClass().getDeclaredFields()) {
                    if (f.getType() == double.class) {
                        f.setAccessible(true);
                        amount = f.getDouble(resp);
                        break;
                    }
                }
            }
            if (amount <= 0) return;

            // ── 2. 状态: int q (0=待确认, 1=已确认) ──
            int q = 0;
            try {
                Field f = resp.getClass().getDeclaredField("q");
                f.setAccessible(true);
                q = f.getInt(resp);
            } catch (Throwable ignored) {}
            if (q != 1) return;  // 未确认不播报

            // ── 3. 发送者: String m ──
            String sender = null;
            try {
                Field f = resp.getClass().getDeclaredField("m");
                f.setAccessible(true);
                Object v = f.get(resp);
                if (v instanceof String && !((String) v).isEmpty()) sender = (String) v;
            } catch (Throwable ignored) {}

            // ── 4. 额外信息: String r ──
            String status = null;
            try {
                Field f = resp.getClass().getDeclaredField("r");
                f.setAccessible(true);
                Object v = f.get(resp);
                if (v instanceof String && !((String) v).isEmpty()) status = (String) v;
            } catch (Throwable ignored) {}

            // ── 5. 构造并播报 ──
            String yuan = String.format("%.2f", amount);
            String name = getNick(sender);
            String speak;
            if ("转账".equals(type)) {
                speak = "收到" + name + "转账" + yuan + "元";
            } else {
                speak = name + "的红包：" + yuan + "元";
            }
            if (status != null && !status.isEmpty()
                && !status.contains("已收款") && !status.contains("已领取")) {
                speak += "，" + status;
            }

            TtsEngine.speak(speak);
            XposedBridge.log("[MoneyHook] " + speak);

        } catch (Throwable t) {
            XposedBridge.log("[MoneyHook] ERR: " + t);
        }
    }


    // ================================================================
    //  三、自动收款 — 自动点击确认按钮
    // ================================================================

    /**
     * 延迟后在 RemittanceDetailUI 中自动点击收款按钮
     * 触发时机: onResume (页面显示)
     */
    static void autoClickConfirm(final Activity activity) {
        if (activity == null) return;

        // 延迟 800ms 确保 UI 渲染完成
        sMainHandler.postDelayed(() -> {
            try {
                View root = activity.getWindow().getDecorView();
                View btn = findConfirmButton(root);
                if (btn != null) {
                    XposedBridge.log("[MoneyHook] auto-click: " +
                        (btn instanceof TextView ? ((TextView) btn).getText() : btn.getClass().getSimpleName()));
                    btn.performClick();
                }
            } catch (Throwable t) {
                XposedBridge.log("[MoneyHook] autoClick err: " + t);
            }
        }, 800);
    }

    /**
     * 递归查找"确认收款"/"收钱"/"收款"/"确认"按钮
     */
    static View findConfirmButton(View root) {
        if (root == null) return null;

        // 检查是否是 Button/TextView
        if (root instanceof Button || root instanceof TextView) {
            CharSequence text = null;
            if (root instanceof Button) text = ((Button) root).getText();
            else text = ((TextView) root).getText();

            if (text != null) {
                String t = text.toString();
                if (t.contains("确认收款") || t.contains("收钱")
                    || t.contains("收款") || t.contains("确认")
                    || t.contains("领取")) {
                    // 确认是可点击的
                    if (root.isClickable() || root.isEnabled()) {
                        return root;
                    }
                }
            }
        }

        // 递归子 View
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View result = findConfirmButton(vg.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }


    // ================================================================
    //  四、昵称查询
    // ================================================================

    static String getNick(String talker) {
        if (talker == null || talker.isEmpty()) return "好友";
        try {
            // 复用现有的 NickResolver
            Class<?> nrCls = Class.forName("com.example.leshao.NickResolver");
            return (String) nrCls.getMethod("get", String.class).invoke(null, talker);
        } catch (Throwable t) {
            return talker;
        }
    }
}


/**
 * ================================================================
 *  五、使用方法
 * ================================================================
 *
 * 1. 把 MoneyHook.java 和现有的 NickResolver.java / TtsEngine.java
 *    放在同一个 com.example.leshao 包下
 *
 * 2. 在 HookManager 或 handleLoadPackage 中调用:
 *    MoneyHook.hookAll(lpparam.classLoader);
 *
 * 3. 不需要单独注册 RedPacket 和 AutoCollect 的 Hook,
 *    MoneyHook.hookAll() 会一次性注册所有 Hook
 *
 * 4. 自动收款原理:
 *    RemittanceDetailUI.onResume() 触发时,
 *    延迟 800ms 递归遍历所有子 View,
 *    找到文字含"确认收款"/"收钱"/"收款"/"确认"/"领取"的按钮,
 *    自动调用 performClick()
 *
 * 5. 如果自动收款仍不生效,
 *    在 autoClickConfirm() 里加日志打印所有 Button 的文字,
 *    判断是延迟不够还是文字不匹配,
 *    然后调整 delay 毫秒数或文字关键词
 *
 * 6. 金额播报原理:
 *    onSceneEnd 回调中 double f = 金额(元),
 *    只播报 q==1 的情况(已确认收款/已领取红包)
 *
 * ================================================================
 *  六、字段对照速查
 * ================================================================
 *
 * respClass: com.tencent.mm.plugin.remittance.model.g1
 *
 * double f = 0.02      金额(元)
 * String m = wxid_xxx  付款人
 * String r             状态文字
 * int    q = 0→1       0=待确认, 1=已确认
 * int    h = 2000/2001 2000=转账, 2001=红包
 * String d             交易单号
 *
 * ================================================================
 */
