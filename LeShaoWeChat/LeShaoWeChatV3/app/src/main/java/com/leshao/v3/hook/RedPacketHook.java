package com.leshao.v3.hook;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class RedPacketHook {

    private static final String TAG = "RedPacket";

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
    private static volatile TextToSpeech sTts;
    private static volatile String sAnnouncedTalker = "";
    private static volatile String sAnnouncedSender = "";
    private static volatile String sAnnouncedAmount = "";
    private static volatile boolean sAutoCollectEnabled = false;

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
    public static void setAutoCollectEnabled(boolean v) { sAutoCollectEnabled = v; }

    public static void hook() {
        if (!ContextManager.isReady()) {
            LogWriter.log(TAG, "hook ABORTED: ContextManager not ready");
            return;
        }
        ClassLoader cl = ContextManager.getClassLoader();
        initTts();
        hookReceiveUIs(cl);
        hookChatListClick(cl);
        LogWriter.log(TAG, "hooks installed");
    }

    private static void initTts() {
        try {
            android.content.Context ctx = ContextManager.getAppContext();
            if (ctx == null) return;
            sTts = new TextToSpeech(ctx, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    if (status == TextToSpeech.SUCCESS) {
                        LogWriter.log(TAG, "TTS init OK");
                    }
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "TTS init err: " + t.getMessage());
        }
    }

    private static boolean shouldGrab(String talker, String sender) {
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

    private static boolean shouldGrabByContent(String content) {
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

    private static void announceGrab(Activity act, String talker, String sender, String amount) {
        if (!sTtsAnnounce || sTts == null) return;
        try {
            sAnnouncedTalker = talker;
            sAnnouncedSender = sender;
            sAnnouncedAmount = amount;
            String senderName = resolveNickname(sender);
            StringBuilder sb = new StringBuilder();
            if (talker != null && talker.endsWith("@chatroom")) sb.append("群聊");
            sb.append(senderName).append("的红包");
            if (amount != null && !amount.isEmpty()) sb.append(amount).append("元");
            sb.append("已领取");
            sTts.speak(sb.toString(), TextToSpeech.QUEUE_FLUSH, null, "redpacket_" + System.currentTimeMillis());
        } catch (Throwable t) {
            LogWriter.log(TAG, "TTS announce err: " + t.getMessage());
        }
    }

    private static String resolveNickname(String wxid) { return wxid; }

    private static int parseTimeToMin(String time) {
        try {
            String[] parts = time.split(":");
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (Throwable t) { return 0; }
    }

    private static void hookReceiveUIs(ClassLoader cl) {
        hookInitView(cl, "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNewReceiveUI", "initView");
        hookInitView(cl, "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNotHookReceiveUI", "initView");
        tryHookAndClick(cl, "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyBusiReceiveUI");
        tryHookAndClick(cl, "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyBusiReceiveUIV2");
        tryHookAndClick(cl, "com.tencent.mm.plugin.luckymoney.hk.ui.LuckyMoneyHKReceiveUI");
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
            Class<?> chattingUI = XposedHelpers.findClass("com.tencent.mm.ui.chatting.ChattingUI", cl);
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
            View amountView = findAmountView(root);
            String amount = amountView instanceof TextView
                ? ((TextView) amountView).getText().toString() : "";
            Button btn = findButtonRecursive(root);
            if (btn != null && btn.isEnabled() && isVisible(btn)) {
                btn.performClick();
                LogWriter.log(TAG, "clicked open: " + amount);
                String talker = getTalkerFromActivity(activity);
                String sender = getSenderFromActivity(activity);
                announceGrab(activity, talker, sender, amount);
            }
        } catch (Throwable ignored) {} finally {
            sProcessing.set(false);
        }
    }

    private static View findAmountView(View v) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null) {
                String s = t.toString();
                if (s.contains(".") || s.contains("元") || s.matches(".*\\d+\\..*")) return v;
            }
        }
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                View found = findAmountView(((ViewGroup) v).getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
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

    public static void release() {
        if (sTts != null) {
            sTts.stop();
            sTts.shutdown();
            sTts = null;
        }
    }

    static class ReceiveOpenHook extends XC_MethodHook {
        private final boolean mIsCreate;

        ReceiveOpenHook(boolean isCreate) { mIsCreate = isCreate; }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!sEnabled) return;
            Activity act = (Activity) param.thisObject;
            sHandler.postDelayed(new Runnable() {
                @Override
                public void run() { clickOpenButton(act); }
            }, mIsCreate ? 100 : 50);
        }
    }

    static class ChatListResumeHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!sEnabled) return;
            Activity act = (Activity) param.thisObject;
            sHandler.postDelayed(new Runnable() {
                @Override
                public void run() { scanAndClickEnvelope(act); }
            }, 300);
        }
    }
}
