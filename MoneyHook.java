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
        catch (Throwable t) { XposedBridge.log("[M] m1: " + t); return; }
        hookRP(cl);
        hookTF(cl);
        XposedBridge.log("[M] OK");
    }

    /* ====== 红包: UI金额扫描 ====== */
    static void hookRP(ClassLoader cl) {
        try {
            Class<?> c = cl.loadClass("com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI");
            XposedHelpers.findAndHookMethod(c, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Activity a = (Activity) p.thisObject;
                    sH.postDelayed(() -> {
                        String amt = scanAmount(a.getWindow().getDecorView());
                        if (amt != null) {
                            TtsEngine.speak("红包：" + amt + "元");
                            XposedBridge.log("[M-RP] " + amt);
                        }
                    }, 1000);
                }
            });
            XposedBridge.log("[M] RP OK");
        } catch (Throwable t) { XposedBridge.log("[M] RP: " + t); }
    }

    static String scanAmount(View r) {
        if (r instanceof TextView) {
            String t = ((TextView) r).getText().toString();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+\\.\\d{2})").matcher(t);
            if (m.find() && t.length() < 25) return m.group(1);
        }
        if (r instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) r).getChildCount(); i++) {
                String s = scanAmount(((ViewGroup) r).getChildAt(i));
                if (s != null) return s;
            }
        return null;
    }

    /* ====== 转账: 延迟1000ms自动点收款 ====== */
    static void hookTF(ClassLoader cl) {
        try {
            Class<?> c = cl.loadClass("com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI");

            XposedHelpers.findAndHookMethod(c, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    clickCollect((Activity) p.thisObject, 1000);
                }
            });

            XposedHelpers.findAndHookMethod(c, "onSceneEnd",
                int.class, int.class, String.class, sM1Cls,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        int et = (int) p.args[0], ec = (int) p.args[1];
                        if (et != 0 || ec != 0) return;
                        double amt = getDouble(p.args[3], "f");
                        int q = getInt(p.args[3], "q");
                        if (amt > 0 && q == 1) {
                            TtsEngine.speak("收到转账" + String.format("%.2f", amt) + "元");
                            XposedBridge.log("[M-TF] " + amt);
                        }
                    }
                });
            XposedBridge.log("[M] TF OK");
        } catch (Throwable t) { XposedBridge.log("[M] TF: " + t); }
    }

    static void clickCollect(Activity a, int delayMs) {
        sH.postDelayed(() -> {
            List<View> btns = new ArrayList<>();
            collectButtons(a.getWindow().getDecorView(), btns);
            XposedBridge.log("[M-TF] delay=" + delayMs + " btns=" + btns.size());
            for (View v : btns) {
                XposedBridge.log("[M-TF]   " + v.getClass().getSimpleName()
                    + " [" + getBtnText(v) + "] clk=" + v.isClickable());
            }
            for (View v : btns) {
                String t = getBtnText(v);
                if (t != null && t.contains("收款") && !t.contains("已")) {
                    XposedBridge.log("[M-TF] click: " + t);
                    v.performClick();
                    return;
                }
            }
            if (delayMs < 4000) clickCollect(a, delayMs + 1500);
            else XposedBridge.log("[M-TF] give up");
        }, delayMs);
    }

    static void collectButtons(View r, List<View> out) {
        if (r == null) return;
        if (r.isClickable() && r.isEnabled() && r instanceof Button) out.add(r);
        if (r instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) r).getChildCount(); i++)
                collectButtons(((ViewGroup) r).getChildAt(i), out);
    }

    static String getBtnText(View v) {
        if (v instanceof Button) return ((Button) v).getText().toString();
        if (v instanceof TextView) return ((TextView) v).getText().toString();
        return "";
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
