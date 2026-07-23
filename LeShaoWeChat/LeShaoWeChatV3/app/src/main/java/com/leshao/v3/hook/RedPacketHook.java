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

import java.lang.reflect.Field;
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
        hookOpenResult(cl);
        LogWriter.log(TAG, "hooks installed (open result + UI auto-click)");
    }

    // ==================== TTS: onSceneEnd 播报结果 ====================
    public static void hookOpenResult(ClassLoader cl) {
        hookOnSceneEnd(cl, PKG_WECHAT + ".plugin.luckymoney.ui.LuckyMoneyNewReceiveUI");
        hookOnSceneEnd(cl, PKG_WECHAT + ".plugin.luckymoney.ui.LuckyMoneyDetailUI");
    }

    private static void hookOnSceneEnd(ClassLoader cl, String className) {
        try {
            Class<?> uiCls = cl.loadClass(className);
            XposedBridge.hookAllMethods(uiCls, "onSceneEnd",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args.length < 4) return;
                            int errType = ((Number) param.args[0]).intValue();
                            int errCode = ((Number) param.args[1]).intValue();
                            if (errType != 0 || errCode != 0) return;
                            if (!sTtsAnnounce) return;

                            Object resp = param.args[3];
                            if (resp == null) return;
                            LogWriter.log(TAG, "onSceneEnd FIRE respClass=" + resp.getClass().getSimpleName());

                            String xml = reflectToString(resp);
                            LogWriter.log(TAG, "onSceneEnd xml=" + (xml != null ? xml.substring(0, Math.min(xml.length(), 200)) : "null"));

                            String amountStr = extractTag(xml, "amount");
                            if (amountStr == null) amountStr = extractTag(xml, "receiveamount");
                            if (amountStr == null) amountStr = extractTag(xml, "totalamount");

                            String sender = extractTag(xml, "sendname");
                            String wishing = extractTag(xml, "wishing");
                            String chatroom = extractTag(xml, "chatroom");

                            if (sender == null) sender = reflectFieldByKeyword(resp, "sendname", "sender", "sendername");
                            if (wishing == null) wishing = reflectFieldByKeyword(resp, "wish", "desc", "greeting", "wishing");
                            if (chatroom == null) chatroom = rifStrByKw(resp, "chatroom", "groupid", "roomname");

                            if (amountStr == null || amountStr.isEmpty()) return;

                            String yuan = fenToYuan(amountStr);
                            TTSBroadcaster.announceRedPacket(sender, chatroom, wishing, yuan);

                        } catch (Throwable t) {
                            LogWriter.log(TAG, "onSceneEnd TTS err: " + t.getMessage());
                        }
                    }
                });
            LogWriter.log(TAG, "onSceneEnd hook OK: " + className);
        } catch (Throwable t) {
            LogWriter.log(TAG, "onSceneEnd hook FAILED: " + className + " " + t.getClass().getSimpleName());
        }
    }

    // ==================== TTS: 反射工具 ====================
    private static String reflectToString(Object obj) {
        if (obj == null) return null;
        try {
            StringBuilder sb = new StringBuilder();
            for (Field f : obj.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v instanceof String) {
                    sb.append("<").append(f.getName()).append(">")
                      .append((String) v)
                      .append("</").append(f.getName()).append(">");
                }
            }
            return sb.length() > 0 ? sb.toString() : obj.toString();
        } catch (Exception e) { return obj.toString(); }
    }

    private static String rifStrByKw(Object obj, String... names) {
        if (obj == null) return null;
        for (String name : names) {
            try {
                for (Field f : obj.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    if (f.getName().toLowerCase().contains(name.toLowerCase())) {
                        Object v = f.get(obj);
                        if (v instanceof String && !((String) v).isEmpty())
                            return (String) v;
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static String reflectFieldByKeyword(Object obj, String... names) {
        return rifStrByKw(obj, names);
    }

    private static String extractTag(String xml, String tag) {
        if (xml == null) return null;
        int s = xml.indexOf("<" + tag + ">");
        if (s < 0) return null;
        s += tag.length() + 2;
        int e = xml.indexOf("</" + tag + ">", s);
        return e > s ? xml.substring(s, e) : null;
    }

    static String fenToYuan(String fen) {
        try {
            long f = Long.parseLong(fen.trim());
            return String.format("%.2f", f / 100.0);
        } catch (NumberFormatException e) { return fen; }
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
