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

    // ==================== TTS: 监听 ReceiveUI 提取结果文字 ====================
    private static long sLastRedAnnounce = 0;

    public static void hookTtsCheck(ClassLoader cl) {
        hookTtsOnUI(cl, PKG_WECHAT + ".plugin.luckymoney.ui.LuckyMoneyNewReceiveUI", "initView");
        hookTtsOnUI(cl, PKG_WECHAT + ".plugin.luckymoney.ui.LuckyMoneyNotHookReceiveUI", "initView");
        hookTtsOnUI(cl, PKG_WECHAT + ".plugin.luckymoney.ui.LuckyMoneyBusiReceiveUI", "onCreate");
        hookTtsOnUI(cl, PKG_WECHAT + ".plugin.luckymoney.ui.LuckyMoneyBusiReceiveUIV2", "onCreate");
        hookTtsOnUI(cl, PKG_WECHAT + ".plugin.luckymoney.hk.ui.LuckyMoneyHKReceiveUI", "onCreate");
    }

    private static void hookTtsOnUI(ClassLoader cl, String className, String methodName) {
        try {
            Class<?> cls = XposedHelpers.findClass(className, cl);
            XposedBridge.hookAllMethods(cls, methodName, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!sTtsAnnounce) return;
                    Activity act = (Activity) param.thisObject;
                    sHandler.postDelayed(() -> checkAndAnnounce(act), 1200);
                    sHandler.postDelayed(() -> checkAndAnnounce(act), 2800);
                    sHandler.postDelayed(() -> checkAndAnnounce(act), 5000);
                }
            });
            LogWriter.log(TAG, "ttsCheck hook OK: " + className + "." + methodName + "()");
        } catch (Throwable ignored) {}
    }

    private static void checkAndAnnounce(Activity act) {
        long now = System.currentTimeMillis();
        if (now - sLastRedAnnounce < 4000) return;

        try {
            View root = act.getWindow().getDecorView();
            java.util.List<String> texts = new java.util.ArrayList<>();
            collectAllText(root, texts);

            String sender = null;
            String amountRaw = null;

            for (String t : texts) {
                if (t.isEmpty() || t.length() > 100) continue;
                LogWriter.log(TAG, "ttsCheck text=[" + t + "]");

                if (amountRaw == null) {
                    if (t.contains("元") || t.contains("¥")) {
                        amountRaw = t.replaceAll("[^\\d.]", "").trim();
                    } else if (t.matches("^\\s*[\\d,]+\\.[\\d]{2}\\s*$")) {
                        amountRaw = t.replace(",", "").trim();
                    }
                }

                if (sender == null && amountRaw != null && !t.equals(amountRaw)
                    && t.length() >= 2 && t.length() <= 20
                    && !t.startsWith("<") && !t.matches(".*\\d{6,}.*")
                    && !t.contains("元") && !t.contains("¥")) {
                    sender = t;
                }
            }

            if (amountRaw == null || amountRaw.isEmpty()) return;
            if (sender == null) sender = "好友";

            LogWriter.log(TAG, "ttsCheck OK: sender=" + sender + " amount=" + amountRaw);
            TTSBroadcaster.announceRedPacket(sender, null, null, amountRaw + "元");
            sLastRedAnnounce = now;
        } catch (Throwable t) {
            LogWriter.log(TAG, "checkAndAnnounce err: " + t.getMessage());
        }
    }

    private static void collectAllText(View view, java.util.List<String> out) {
        if (view instanceof android.widget.TextView) {
            CharSequence cs = ((android.widget.TextView) view).getText();
            if (cs != null && cs.length() > 0) out.add(cs.toString());
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                collectAllText(vg.getChildAt(i), out);
            }
        }
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
