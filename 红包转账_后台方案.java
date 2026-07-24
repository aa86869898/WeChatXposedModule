/**
 * ================================================================
 *  红包播报 + 转账自动收款 — 后台方案
 *  WeChat 8.0.76 (3140)
 * ================================================================
 *
 *  【红包】纯UI兜底 — LuckyMoneyDetailUI金额直接显示在界面上
 *          Hook onResume → 读TextView金额 → TTS播报
 *
 *  【转账】后台自动收 — Intent打开详情页但立即隐藏动画
 *          onResume自动点收款 → finish → onSceneEnd回调播报
 *          使用 FLAG_ACTIVITY_NO_ANIMATION + finish() 零感知
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
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class MoneyHook {

    static Handler sHandler = new Handler(Looper.getMainLooper());
    static Class<?> sM1Cls;
    static Context sCtx;
    static boolean sCollecting = false;   // 防止重复点击
    static WeakReference<Activity> sTransferAct;

    public static void init(ClassLoader cl, Context ctx) {
        sCtx = ctx;
        try { sM1Cls = cl.loadClass("com.tencent.mm.modelbase.m1"); }
        catch (Throwable t) { XposedBridge.log("[Money] m1 fail: " + t); return; }

        hookRedPacket(cl);
        hookAutoCollect(cl);
        XposedBridge.log("[Money] init OK");
    }

    // ================================================================
    //  一、红包 — 纯UI兜底
    // ================================================================

    static void hookRedPacket(ClassLoader cl) {
        // ★ 只Hook LuckyMoneyDetailUI.onResume — 读界面上的金额数字
        try {
            Class<?> cls = cl.loadClass("com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI");
            XposedHelpers.findAndHookMethod(cls, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Activity act = (Activity) p.thisObject;
                    sHandler.postDelayed(() -> {
                        String amt = scanAmount(act.getWindow().getDecorView());
                        if (amt != null) {
                            // 从金额格式判断是否有有效金额
                            amt = amt.replaceAll("[^0-9.]", "");
                            if (!amt.isEmpty() && amt.contains(".")) {
                                try {
                                    double d = Double.parseDouble(amt);
                                    if (d > 0) {
                                        TtsEngine.speak("红包：" + String.format("%.2f", d) + "元");
                                        XposedBridge.log("[Money-RP] " + d);
                                    }
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                    }, 600);
                }
            });
            XposedBridge.log("[Money] RP-UI OK");
        } catch (Throwable t) {
            XposedBridge.log("[Money] RP-UI fail: " + t);
        }
    }

    /** 递归扫描所有TextView,找金额格式 "0.xx" */
    static String scanAmount(View root) {
        if (root instanceof TextView) {
            String t = ((TextView) root).getText().toString();
            // 匹配金额格式: 数字.数字, 且长度<15
            if (t.matches(".*\\d+\\.\\d{2}.*") && t.length() < 15) return t;
        }
        if (root instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
                String r = scanAmount(((ViewGroup) root).getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }


    // ================================================================
    //  二、转账 — 后台自动收
    // ================================================================

    /** MessageHook中检测到转账消息时调用 */
    public static void onTransferMsg(String content) {
        if (content == null) return;
        String url = extractTag(content, "url");
        if (url == null || url.isEmpty()) return;
        try {
            Intent i = new Intent();
            i.setClassName("com.tencent.mm", "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI");
            i.putExtra("key_scene", 1);
            i.putExtra("key_url", url);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            sCtx.startActivity(i);
            sCollecting = true;
            XposedBridge.log("[Money-TF] opened, url=" + url.substring(0, Math.min(50, url.length())));
        } catch (Throwable t) {
            XposedBridge.log("[Money-TF] fail: " + t);
        }
    }

    static void hookAutoCollect(ClassLoader cl) {
        try {
            Class<?> cls = cl.loadClass("com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI");

            // onResume → 保存引用 + 自动点击
            XposedHelpers.findAndHookMethod(cls, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Activity act = (Activity) p.thisObject;
                    sTransferAct = new WeakReference<>(act);
                    if (sCollecting) {
                        // 立即隐藏进入动画
                        act.overridePendingTransition(0, 0);
                        // 延迟自动点击(等UI渲染)
                        sHandler.postDelayed(() -> autoClick(act), 1200);
                    }
                }
            });

            // ★ onSceneEnd — 收款完成后播报 + 关闭页面
            XposedHelpers.findAndHookMethod(cls, "onSceneEnd",
                int.class, int.class, String.class, sM1Cls,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        int et = (int) p.args[0], ec = (int) p.args[1];
                        if (et != 0 || ec != 0) return;
                        Object resp = p.args[3];
                        double amt = readDouble(resp, "f");
                        int q = readInt(resp, "q");
                        if (amt > 0 && q == 1) {
                            String sender = readStr(resp, "m");
                            String name = sender != null ? getNick(sender) : "好友";
                            TtsEngine.speak("收到" + name + "转账" + String.format("%.2f", amt) + "元");
                            XposedBridge.log("[Money-AC] TTS: " + amt);
                            // 自动关闭
                            sHandler.postDelayed(() -> finishAct(), 300);
                            sCollecting = false;
                        }
                    }
                });

            XposedBridge.log("[Money] AC OK");
        } catch (Throwable t) {
            XposedBridge.log("[Money] AC fail: " + t);
        }
    }

    static void autoClick(Activity act) {
        try {
            View btn = findCollectBtn(act.getWindow().getDecorView());
            if (btn != null) {
                XposedBridge.log("[Money-AC] click: " + ((Button) btn).getText());
                btn.performClick();
            }
        } catch (Throwable t) {
            XposedBridge.log("[Money-AC] click err: " + t);
        }
    }

    static View findCollectBtn(View root) {
        if (root instanceof Button && root.isClickable()) {
            String t = ((Button) root).getText().toString();
            if (t.contains("收款") || t.contains("确认") || t.contains("收钱")) return root;
        }
        if (root instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
                View r = findCollectBtn(((ViewGroup) root).getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    static void finishAct() {
        if (sTransferAct != null) {
            Activity act = sTransferAct.get();
            if (act != null && !act.isFinishing()) {
                act.finish();
                act.overridePendingTransition(0, 0);
            }
        }
    }


    // ================================================================
    //  工具
    // ================================================================

    static double readDouble(Object obj, String name) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.getDouble(obj);
        } catch (Throwable t) { return -1; }
    }
    static int readInt(Object obj, String name) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(obj);
        } catch (Throwable t) { return -1; }
    }
    static String readStr(Object obj, String name) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return (String) f.get(obj);
        } catch (Throwable t) { return null; }
    }
    static String extractTag(String xml, String tag) {
        int s = xml.indexOf("<" + tag);
        if (s < 0) return null;
        int gt = xml.indexOf(">", s);
        if (gt < 0) return null;
        int e = xml.indexOf("</" + tag + ">", gt);
        if (e < 0) return null;
        String v = xml.substring(gt + 1, e);
        if (v.startsWith("<![CDATA[")) v = v.substring(9, v.indexOf("]]>"));
        return v;
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
 *  集成方式
 * ================================================================
 *
 * 1. 在 HookManager / handleLoadPackage 中:
 *      MoneyHook.init(cl, sAppContext);
 *
 * 2. 在 MsgHook.onMessage() dispatch前加:
 *      if (type == 49 && content != null && content.contains("<type>2000</type>")) {
 *          MoneyHook.onTransferMsg(content);
 *      }
 *
 * 3. 不需要额外注册 RedPacket/AutoCollect Hook,
 *    MoneyHook.init() 已统一注册。
 *
 * ================================================================
 *  流程
 * ================================================================
 *
 *  红包: 点开红包详情 → LuckyMoneyDetailUI.onResume
 *        → 600ms后读金额TextView → TTS播报
 *
 *  转账: 收到type=49+type=2000的消息
 *        → Intent打开RemittanceDetailUI (无动画)
 *        → onResume隐藏转场动画
 *        → 1200ms后找"收款"按钮点击
 *        → onSceneEnd回调(q=1) → TTS播报 + finish关闭
 *
 * ================================================================
 */
