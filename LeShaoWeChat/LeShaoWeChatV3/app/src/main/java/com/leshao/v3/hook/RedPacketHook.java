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

    // ==================== TTS: onSceneEnd + m1 字段精确提取 ====================
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
                    new MoneyResultHook("红包"));
                LogWriter.log(TAG, "tts onSceneEnd OK: " + clsName);
            } catch (Throwable t) {
                LogWriter.log(TAG, "tts FAILED: " + clsName + " " + t.getMessage());
            }
        }
    }

    static class MoneyResultHook extends XC_MethodHook {
        private final String mType;
        MoneyResultHook(String type) { mType = type; }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (!sTtsAnnounce) return;
                int errType = ((Number) param.args[0]).intValue();
                int errCode = ((Number) param.args[1]).intValue();
                if (errType != 0 || errCode != 0) return;

                Object resp = param.args[3];
                if (resp == null) return;
                LogWriter.log(TAG, "respClass=" + resp.getClass().getName());

                // ── 探测 resp 一级字段 ──
                double amount = 0;
                String sender = null;

                for (java.lang.reflect.Field f : resp.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    try {
                        Object v = f.get(resp);
                        String name = f.getName();

                        if (f.getType() == double.class && f.getDouble(resp) > 0) {
                            amount = f.getDouble(resp);
                        }
                        if ((f.getType() == int.class || f.getType() == long.class)
                            && name.toLowerCase().contains("amount")) {
                            long val = f.getLong(resp);
                            if (val > 0 && val < 100000000) {
                                amount = val / 100.0;
                                LogWriter.log(TAG, "amount(int) from " + name + "=" + val);
                            }
                        }
                        if (v instanceof String && name.toLowerCase().contains("amount")) {
                            try { amount = Double.parseDouble((String) v); }
                            catch (NumberFormatException ignored) {}
                        }
                        if (v instanceof String && (name.contains("send") || name.contains("from")
                            || name.contains("user") || name.contains("payer") || name.contains("nick"))
                            && !name.contains("type") && !name.contains("id") && !name.contains("status")
                            && ((String) v).length() > 1 && ((String) v).length() < 50) {
                            sender = (String) v;
                        }
                    } catch (Exception ignored) {}
                }

                // ── 如果一级字段没找到金额，探测嵌套对象 ──
                if (amount <= 0) {
                    for (java.lang.reflect.Field f : resp.getClass().getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true);
                        try {
                            Object nested = f.get(resp);
                            if (nested == null || nested instanceof String || nested instanceof Number) continue;
                            LogWriter.log(TAG, "probing nested: " + f.getName() + " class=" + nested.getClass().getName());
                            for (java.lang.reflect.Field nf : nested.getClass().getDeclaredFields()) {
                                nf.setAccessible(true);
                                try {
                                    Object nv = nf.get(nested);
                                    if (nf.getType() == double.class && nf.getDouble(nested) > 0) {
                                        amount = nf.getDouble(nested);
                                    }
                                    if (nv instanceof String && nf.getName().toLowerCase().contains("amount")) {
                                        try { amount = Double.parseDouble((String) nv); }
                                        catch (NumberFormatException ignored) {}
                                    }
                                } catch (Exception ignored2) {}
                            }
                        } catch (Exception ignored) {}
                    }
                }

                // ── 如果没有 double 字段，尝试所有可能的金额字段 ──
                if (amount <= 0) {
                    for (java.lang.reflect.Field f : resp.getClass().getDeclaredFields()) {
                        if (f.getType() == int.class || f.getType() == long.class) {
                            f.setAccessible(true);
                            try {
                                long v = f.getLong(resp);
                                if (v > 10 && v < 100000000) {
                                    amount = v / 100.0;
                                    LogWriter.log(TAG, "guessed amount: " + f.getName() + "=" + v);
                                    break;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }

                LogWriter.log(TAG, "final amount=" + amount + " type=" + mType);
                if (amount <= 0) return;

                String yuan = String.format("%.2f", amount);
                String name = sender != null ? sender : "好友";
                LogWriter.log(TAG, "tts: type=" + mType + " sender=" + sender + " amount=" + yuan);
                TTSBroadcaster.announceRedPacket(name, null, null, yuan + "元");
            } catch (Throwable t) {
                LogWriter.log(TAG, "MoneyResultHook ERR: " + t.getMessage());
            }
        }
    }

    static String fenToYuan(String s) {
        try {
            long f = Long.parseLong(s.trim());
            if (f > 10000) return String.format("%.2f", f / 100.0);
            else if (f > 100) return String.format("%.2f", f / 100.0);
            else return String.format("%.2f", f);
        } catch (NumberFormatException e) { return s; }
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
