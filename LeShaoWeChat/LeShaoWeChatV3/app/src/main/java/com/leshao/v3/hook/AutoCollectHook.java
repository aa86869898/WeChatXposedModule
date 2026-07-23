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

public class AutoCollectHook {

    private static final String TAG = "AutoCollect";
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
        hookChatFragmentResume(cl);
        hookTransferResult(cl);
        LogWriter.log(TAG, "hooks installed (transfer result + UI auto-collect)");
    }

    // ==================== TTS: onSceneEnd 播报结果 ====================
    public static void hookTransferResult(ClassLoader cl) {
        try {
            Class<?> uiCls = cl.loadClass(PKG_WECHAT + ".plugin.remittance.ui.RemittanceDetailUI");
            Class<?> m1Cls = cl.loadClass(PKG_WECHAT + ".modelbase.m1");
            XposedHelpers.findAndHookMethod(uiCls, "onSceneEnd",
                int.class, int.class, String.class, m1Cls,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            int errType = (int) param.args[0];
                            int errCode = (int) param.args[1];
                            if (errType != 0 || errCode != 0) return;
                            if (!sTtsAnnounce) return;

                            Object resp = param.args[3];
                            String xml = reflectToString(resp);

                            String feeStr = extractTag(xml, "fee");
                            String sender = extractTag(xml, "payer_username");
                            String desc = extractTag(xml, "pay_memo");

                            if (sender == null) sender = reflectFieldByKeyword(param.thisObject, "payer", "sender", "fromuser");
                            if (desc == null) desc = reflectFieldByKeyword(param.thisObject, "desc", "memo", "remark");

                            if (feeStr == null || feeStr.isEmpty()) return;

                            String yuan = RedPacketHook.fenToYuan(feeStr);
                            TTSBroadcaster.announceTransfer(sender, yuan, desc);

                        } catch (Throwable t) {
                            LogWriter.log(TAG, "onSceneEnd TTS err: " + t.getMessage());
                        }
                    }
                });
            LogWriter.log(TAG, "transfer onSceneEnd hook OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "transfer onSceneEnd hook FAILED: " + t.getClass().getSimpleName());
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

    private static String reflectFieldByKeyword(Object obj, String... names) {
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

    private static String extractTag(String xml, String tag) {
        if (xml == null) return null;
        int s = xml.indexOf("<" + tag + ">");
        if (s < 0) return null;
        s += tag.length() + 2;
        int e = xml.indexOf("</" + tag + ">", s);
        return e > s ? xml.substring(s, e) : null;
    }

    // ==================== 自动收款 (保留) ====================
    private static boolean shouldCollect(String talker, String sender) {
        if (!sEnabled) return false;
        boolean isGroup = talker != null && talker.endsWith("@chatroom");
        if (!isGroup && !sPrivateEnabled) return false;
        if (isGroup && !sGroupEnabled) return false;
        if (!isGroup && !sFastPrivateWxids.isEmpty() && !sFastPrivateWxids.contains(sender)) return false;
        if (isGroup && !sFastGroupIds.isEmpty() && !sFastGroupIds.contains(talker)) return false;
        if (sTimeFilterOn) {
            Calendar cal = Calendar.getInstance();
            int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
            int startMin = parseTimeToMin(sTimeStart);
            int endMin = parseTimeToMin(sTimeEnd);
            if (startMin <= endMin) {
                if (nowMin >= startMin && nowMin <= endMin) return false;
            } else {
                if (nowMin >= startMin || nowMin <= endMin) return false;
            }
        }
        return true;
    }

    private static boolean shouldCollectByContent(String content) {
        if (sKeywordExcludeOn && !sKeywordExclude.isEmpty()) {
            for (String kw : sKeywordExclude) {
                if (!kw.isEmpty() && content.contains(kw)) return false;
            }
        }
        if (sKeywordIncludeOn && !sKeywordInclude.isEmpty()) {
            for (String kw : sKeywordInclude) {
                if (!kw.isEmpty() && content.contains(kw)) return true;
            }
            return false;
        }
        return true;
    }

    private static int parseTimeToMin(String time) {
        try {
            String[] parts = time.split(":");
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (Throwable t) { return 0; }
    }

    private static void hookReceiveUIs(ClassLoader cl) {
        String[] uis = {
            PKG_WECHAT + ".plugin.collection.ui.CollectionMainUI",
            PKG_WECHAT + ".plugin.collection.ui.CollectionBusiUI",
            PKG_WECHAT + ".plugin.order.ui.MallTransactionUI",
            PKG_WECHAT + ".plugin.wallet.pay.ui.WalletPayUI",
        };
        for (String cls : uis) {
            tryHookActivity(cl, cls);
        }
    }

    private static void tryHookActivity(ClassLoader cl, String className) {
        try {
            Class<?> cls = XposedHelpers.findClass(className, cl);
            XposedBridge.hookAllMethods(cls, "onCreate", new ReceiveOpenHook(true));
            LogWriter.log(TAG, "[OK] " + className + ".onCreate()");
        } catch (Throwable ignored) {}
    }

    private static void hookChatFragmentResume(ClassLoader cl) {
        try {
            Class<?> chattingUI = XposedHelpers.findClass(PKG_WECHAT + ".ui.chatting.ChattingUI", cl);
            XposedBridge.hookAllMethods(chattingUI, "onResume", new ChatFragmentResumeHook());
            LogWriter.log(TAG, "[OK] ChattingUI.onResume()");
        } catch (Throwable e) {
            LogWriter.log(TAG, "[MISS] ChattingUI: " + e.getMessage());
        }
    }

    private static void clickCollectButton(Activity activity) {
        if (sProcessing.get()) return;
        sProcessing.set(true);
        try {
            View root = activity.getWindow().getDecorView();
            Button btn = findCollectButton(root);
            if (btn != null && btn.isEnabled() && isVisible(btn)) {
                String talker = getTalkerFromActivity(activity);
                String sender = getSenderFromActivity(activity);
                if (!shouldCollect(talker, sender)) { return; }
                String content = getContentFromActivity(activity);
                if (!shouldCollectByContent(content)) { return; }
                btn.performClick();
                LogWriter.log(TAG, "auto-clicked collect button");
            }
        } catch (Throwable ignored) {} finally {
            sProcessing.set(false);
        }
    }

    private static Button findCollectButton(View v) {
        if (v instanceof Button) {
            Button b = (Button) v;
            CharSequence t = b.getText();
            if (t != null) {
                String s = t.toString();
                if (s.contains("收款") || s.contains("确认") || s.contains("收下")
                    || s.contains("接收") || s.contains("Collect") || s.contains("Accept")) {
                    return b;
                }
            }
        }
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                Button r = findCollectButton(((ViewGroup) v).getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    private static boolean isVisible(View v) {
        return v.getVisibility() == View.VISIBLE && v.getWidth() > 0 && v.getHeight() > 0;
    }

    private static String getTalkerFromActivity(Activity act) {
        try {
            android.content.Intent intent = act.getIntent();
            if (intent != null) {
                String talker = intent.getStringExtra("Chat_User");
                if (talker != null) return talker;
                talker = intent.getStringExtra("key_username");
                if (talker != null) return talker;
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static String getSenderFromActivity(Activity act) { return ""; }

    private static String getContentFromActivity(Activity act) { return ""; }

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
                    public void run() { clickCollectButton(act); }
                }, mIsCreate ? 200 : 80);
            } catch (Throwable ignored) {}
        }
    }

    static class ChatFragmentResumeHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (!sEnabled) return;
                Activity act = (Activity) param.thisObject;
                sHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() { scanAndClickTransferBubble(act); }
                }, 400);
            } catch (Throwable ignored) {}
        }
    }

    private static void scanAndClickTransferBubble(Activity activity) {
        if (sProcessing.get()) return;
        sProcessing.set(true);
        try {
            View root = activity.getWindow().getDecorView();
            View bubble = findTransferBubble(root);
            if (bubble != null) {
                String talker = getTalkerFromActivity(activity);
                if (!shouldCollect(talker, "")) { return; }
                bubble.performClick();
                LogWriter.log(TAG, "auto clicked transfer bubble in chat");
            }
        } catch (Throwable ignored) {} finally {
            sProcessing.set(false);
        }
    }

    private static View findTransferBubble(View v) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null) {
                String s = t.toString();
                if (s.contains("转账") || s.contains("向你转账") || s.contains("微信转账")) {
                    return findClickableParent(v);
                }
            }
        }
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                View r = findTransferBubble(((ViewGroup) v).getChildAt(i));
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
}
