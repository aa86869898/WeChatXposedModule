package com.leshao.v3.hook;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.service.TTSBroadcaster;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class RedPacketHook {

    private static final String TAG = "RedPacket";
    private static final String PKG_WECHAT = "com.tencent.mm";

    private static volatile boolean sEnabled = false;
    private static volatile boolean sPrivateEnabled = true;
    private static volatile boolean sGroupEnabled = false;
    private static volatile boolean sTimeFilterOn = false;
    private static volatile String sTimeStart = "00:00";
    private static volatile String sTimeEnd = "06:00";
    private static volatile boolean sKeywordExcludeOn = false;
    private static volatile Set<String> sKeywordExclude = new HashSet<>();
    private static volatile boolean sKeywordIncludeOn = false;
    private static volatile Set<String> sKeywordInclude = new HashSet<>();
    private static volatile Set<String> sFastPrivateWxids = new HashSet<>();
    private static volatile Set<String> sFastGroupIds = new HashSet<>();
    private static volatile boolean sTtsAnnounce = true;

    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean sProcessing = new AtomicBoolean(false);

    public static void setEnabled(boolean v) { sEnabled = v; }
    public static void setPrivateEnabled(boolean v) { sPrivateEnabled = v; }
    public static void setGroupEnabled(boolean v) { sGroupEnabled = v; }
    public static void setTimeFilter(boolean on, String start, String end) {
        sTimeFilterOn = on; sTimeStart = start; sTimeEnd = end;
    }
    public static void setKeywordExclude(boolean on, Set<String> keywords) {
        sKeywordExcludeOn = on; sKeywordExclude = keywords != null ? keywords : new HashSet<>();
    }
    public static void setKeywordInclude(boolean on, Set<String> keywords) {
        sKeywordIncludeOn = on; sKeywordInclude = keywords != null ? keywords : new HashSet<>();
    }
    public static void setFastPrivateWxids(Set<String> wxids) {
        sFastPrivateWxids = wxids != null ? wxids : new HashSet<>();
    }
    public static void setFastGroupIds(Set<String> ids) {
        sFastGroupIds = ids != null ? ids : new HashSet<>();
    }
    public static void setTtsAnnounce(boolean v) { sTtsAnnounce = v; }

    public static void hook() {
        if (!ContextManager.isReady()) {
            LogWriter.log(TAG, "hook ABORTED: ContextManager not ready");
            return;
        }
        ClassLoader cl = ContextManager.getClassLoader();
        hookReceiveUIs(cl);
        hookChatListClick(cl);
        hookTtsCheck(cl);
        LogWriter.log(TAG, "hooks installed (open result + UI auto-click + TTS check)");
    }

    // ==================== TTS: onSceneEnd + m1(v5) 字段提取 + UI兜底 ====================
    public static void hookTtsCheck(ClassLoader cl) {
        Class<?> m1Cls = null;
        try { m1Cls = cl.loadClass("com.tencent.mm.modelbase.m1"); } catch (Throwable ignored) {}
        if (m1Cls == null) { LogWriter.log(TAG, "m1 class not found, skip onSceneEnd"); return; }

        String[] classes = {
            PKG_WECHAT + ".plugin.luckymoney.ui.LuckyMoneyNewReceiveUI",
            PKG_WECHAT + ".plugin.luckymoney.ui.LuckyMoneyDetailUI",
            PKG_WECHAT + ".plugin.luckymoney.ui.LuckyMoneyNotHookReceiveUI",
            PKG_WECHAT + ".plugin.luckymoney.ui.LuckyMoneyBusiReceiveUI",
            PKG_WECHAT + ".plugin.luckymoney.ui.LuckyMoneyBusiReceiveUIV2",
            PKG_WECHAT + ".plugin.luckymoney.hk.ui.LuckyMoneyHKReceiveUI",
        };
        for (String clsName : classes) {
            try {
                Class<?> uiCls = cl.loadClass(clsName);
                XposedHelpers.findAndHookMethod(uiCls, "onSceneEnd",
                    int.class, int.class, String.class, m1Cls,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            int et = (int) p.args[0], ec = (int) p.args[1];
                            if (et == 0 && ec == 0) onRedPacket(p.args[3]);
                        }
                    });
                LogWriter.log(TAG, "tts onSceneEnd OK: " + clsName);
            } catch (Throwable t) {
                LogWriter.log(TAG, "tts FAILED: " + clsName + " " + t.getMessage());
            }
        }

        // 兜底: LuckyMoneyDetailUI.onResume 读 UI 金额
        try {
            Class<?> detailCls = cl.loadClass(PKG_WECHAT + ".plugin.luckymoney.ui.LuckyMoneyDetailUI");
            XposedHelpers.findAndHookMethod(detailCls, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    sHandler.postDelayed(() -> {
                        String amt = findAmountText(((Activity) p.thisObject).getWindow().getDecorView());
                        if (amt != null) {
                            LogWriter.log(TAG, "UI-fallback: " + amt);
                            TTSBroadcaster.announceRedPacket("好友", null, null, amt + "元");
                        }
                    }, 500);
                }
            });
            LogWriter.log(TAG, "UI fallback OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "UI fallback FAIL: " + t);
        }
    }

    // ── onSceneEnd 回调: v5 红包结果 ──
    static void onRedPacket(Object resp) {
        try {
            if (!sTtsAnnounce) return;
            if (resp == null) return;
            LogWriter.log(TAG, "class=" + resp.getClass().getName());
            double amount = 0;
            String sender = null;

            // 一级字段: v5.m = "0.10" (金额字符串)
            for (java.lang.reflect.Field f : resp.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                try {
                    Object v = f.get(resp);
                    if (v == null) continue;
                    if (v instanceof String && f.getName().equals("m")) {
                        try { amount = Double.parseDouble((String) v); }
                        catch (NumberFormatException ignored) {}
                    }
                    if (v instanceof String && (f.getName().contains("send") || f.getName().contains("from"))
                        && ((String) v).length() < 50) sender = (String) v;
                } catch (Exception ignored) {}
            }

            // 嵌套: v5.h (e1类型) -> amount字符串, Q字段(发送者)
            for (java.lang.reflect.Field f : resp.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                try {
                    Object nested = f.get(resp);
                    if (nested == null || nested instanceof String || nested instanceof Number) continue;
                    for (java.lang.reflect.Field nf : nested.getClass().getDeclaredFields()) {
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
                String name = sender != null ? sender : "好友";
                LogWriter.log(TAG, "TTS: " + name + "=" + yuan);
                TTSBroadcaster.announceRedPacket(name, null, null, yuan + "元");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "onRedPacket err: " + t);
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

    // ==================== 自动抢红包 (保留) ====================
    private static void hookReceiveUIs(ClassLoader cl) {
        hookInitView(cl, PKG_WECHAT + ".plugin.luckymoney.ui.LuckyMoneyNewReceiveUI", "initView");
        hookInitView(cl, PKG_WECHAT + ".plugin.luckymoney.ui.LuckyMoneyNotHookReceiveUI", "initView");
        tryHookAndClick(cl, PKG_WECHAT + ".plugin.luckymoney.ui.LuckyMoneyBusiReceiveUI");
        tryHookAndClick(cl, PKG_WECHAT + ".plugin.luckymoney.ui.LuckyMoneyBusiReceiveUIV2");
        tryHookAndClick(cl, PKG_WECHAT + ".plugin.luckymoney.hk.ui.LuckyMoneyHKReceiveUI");
    }

    private static void hookInitView(ClassLoader cl, String className, String methodName) {
        try {
            Class<?> cls = XposedHelpers.findClass(className, cl);
            XposedBridge.hookAllMethods(cls, methodName, new ReceiveOpenHook(false));
            LogWriter.log(TAG, "[OK] " + className + "." + methodName + "()");
        } catch (Throwable ignored) {}
    }

    private static void tryHookAndClick(ClassLoader cl, String className) {
        try {
            Class<?> cls = XposedHelpers.findClass(className, cl);
            XposedBridge.hookAllMethods(cls, "onCreate", new ReceiveOpenHook(true));
            LogWriter.log(TAG, "[OK] " + className + ".onCreate()");
        } catch (Throwable ignored) {}
    }

    private static void hookChatListClick(ClassLoader cl) {
        try {
            Class<?> chattingUI = XposedHelpers.findClass(PKG_WECHAT + ".ui.chatting.ChattingUI", cl);
            XposedBridge.hookAllMethods(chattingUI, "onResume", new ChatListResumeHook());
            LogWriter.log(TAG, "[OK] ChattingUI.onResume()");
        } catch (Throwable e) {
            LogWriter.log(TAG, "[MISS] ChattingUI: " + e.getMessage());
        }
    }

    private static void clickOpenButton(Activity activity) {
        if (sProcessing.get()) return;
        sProcessing.set(true);
        try {
            View root = activity.getWindow().getDecorView();
            Button btn = findButtonRecursive(root);
            if (btn != null && btn.isEnabled() && isVisible(btn)) {
                btn.performClick();
                LogWriter.log(TAG, "auto-clicked open button");
            }
        } catch (Throwable ignored) {} finally {
            sProcessing.set(false);
        }
    }

    private static Button findButtonRecursive(View v) {
        if (v instanceof Button) {
            Button b = (Button) v;
            CharSequence t = b.getText();
            if (t != null) {
                String s = t.toString();
                if (s.contains("开") || s.contains("拆") || s.contains("领取")
                    || s.contains("Open") || s.contains("OPEN")) return b;
            }
            try {
                String resName = v.getResources().getResourceEntryName(v.getId());
                if (resName != null && resName.toLowerCase().contains("open")) return b;
            } catch (Throwable ignored) {}
        }
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                Button r = findButtonRecursive(((ViewGroup) v).getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    private static boolean isVisible(View v) {
        return v.getVisibility() == View.VISIBLE && v.getWidth() > 0 && v.getHeight() > 0;
    }

    private static void scanAndClickEnvelope(Activity activity) {
        if (sProcessing.get()) return;
        sProcessing.set(true);
        try {
            View root = activity.getWindow().getDecorView();
            View envelope = findEnvelopeView(root);
            if (envelope != null) {
                envelope.performClick();
                LogWriter.log(TAG, "auto clicked red packet in chat");
            }
        } catch (Throwable ignored) {} finally {
            sProcessing.set(false);
        }
    }

    private static View findEnvelopeView(View v) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null) {
                String s = t.toString();
                if (s.contains("微信红包") || s.contains("红包")) {
                    return findClickableParent(v);
                }
            }
        }
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                View r = findEnvelopeView(((ViewGroup) v).getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    private static View findClickableParent(View v) {
        View parent = (View) v.getParent();
        while (parent != null) {
            if (parent.isClickable()) return parent;
            parent = (View) parent.getParent();
        }
        return v;
    }

    static class ReceiveOpenHook extends XC_MethodHook {
        private final boolean mIsCreate;

        ReceiveOpenHook(boolean isCreate) { mIsCreate = isCreate; }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (!sEnabled) return;
                Activity act = (Activity) param.thisObject;
                sHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() { clickOpenButton(act); }
                }, mIsCreate ? 100 : 50);
            } catch (Throwable ignored) {}
        }
    }

    static class ChatListResumeHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (!sEnabled) return;
                Activity act = (Activity) param.thisObject;
                sHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() { scanAndClickEnvelope(act); }
                }, 300);
            } catch (Throwable ignored) {}
        }
    }
}
