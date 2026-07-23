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

    // ==================== TTS: 扫描 onSceneEnd 全部重载 + m1 响应字段探测 ====================
    public static void hookTransferResult(ClassLoader cl) {
        probeOnSceneEnd(cl, PKG_WECHAT + ".plugin.wallet.pay.ui.WalletPayUI");
        probeOnSceneEnd(cl, PKG_WECHAT + ".plugin.collection.ui.CollectionMainUI");
    }

    private static void probeOnSceneEnd(ClassLoader cl, String className) {
        try {
            Class<?> cls = cl.loadClass(className);
            int hooked = 0;
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals("onSceneEnd")) {
                    XposedBridge.hookMethod(m, new OnSceneEndProbe());
                    hooked++;
                    LogWriter.log(TAG, "probe onSceneEnd(" + m.getParameterCount() + ") OK: " + className);
                }
            }
            Class<?> sup = cls.getSuperclass();
            while (sup != null && sup != Object.class) {
                for (java.lang.reflect.Method m : sup.getDeclaredMethods()) {
                    if (m.getName().equals("onSceneEnd")) {
                        XposedBridge.hookMethod(m, new OnSceneEndProbe());
                        hooked++;
                        LogWriter.log(TAG, "probe onSceneEnd(" + m.getParameterCount() + ") OK: super " + sup.getName());
                    }
                }
                sup = sup.getSuperclass();
            }
            if (hooked == 0) {
                LogWriter.log(TAG, "probe: NO onSceneEnd methods on " + className);
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "probe FAILED: " + className + " " + t.getMessage());
        }
    }

    static class OnSceneEndProbe extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (!sTtsAnnounce) return;
                if (param.args.length < 4) return;
                int errType = ((Number) param.args[0]).intValue();
                int errCode = ((Number) param.args[1]).intValue();
                if (errType != 0 || errCode != 0) return;

                Object resp = param.args[3];
                Object ui = param.thisObject;

                LogWriter.log(TAG, "onSceneEnd FIRE args=" + param.args.length
                    + " respClass=" + (resp != null ? resp.getClass().getSimpleName() : "null"));

                String amount = null;
                String sender = null;
                String desc = null;

                if (resp != null) {
                    for (java.lang.reflect.Field f : resp.getClass().getDeclaredFields()) {
                        f.setAccessible(true);
                        String n = f.getName().toLowerCase();
                        try {
                            Object v = f.get(resp);
                            if (v == null) continue;
                            String valStr = v instanceof byte[] ? "byte[" + ((byte[])v).length + "]"
                                : v.toString();
                            if (valStr.length() > 100) valStr = valStr.substring(0, 100) + "...";
                            LogWriter.log(TAG, "probe resp: " + f.getType().getSimpleName()
                                + " " + f.getName() + " = " + valStr);

                            if (amount == null && (n.contains("amount") || n.contains("total")
                                || n.contains("fee") || n.contains("receive") || n.contains("money")
                                || n.contains("hb") || n.contains("value"))
                                && !n.contains("req") && !n.contains("type") && !n.contains("status")) {
                                if (v instanceof String) amount = (String) v;
                                else if (v instanceof Integer || v instanceof Long) {
                                    long fen = ((Number)v).longValue();
                                    if (fen > 0 && fen < 100000000) amount = String.valueOf(fen);
                                }
                            }
                            if (sender == null && (n.contains("send") || n.contains("from")
                                || n.contains("payer") || n.contains("nick"))
                                && !n.contains("type") && !n.contains("id") && v instanceof String) {
                                String s = (String) v;
                                if (s.length() > 1 && s.length() < 50) sender = s;
                            }
                            if (desc == null && (n.contains("desc") || n.contains("remark")
                                || n.contains("memo") || n.contains("note") || n.contains("word"))
                                && v instanceof String) {
                                String s = (String) v;
                                if (s.length() > 1 && s.length() < 100) desc = s;
                            }
                        } catch (Exception ignored) {}
                    }
                    String str = resp.toString();
                    if (str != null && !str.isEmpty()) {
                        LogWriter.log(TAG, "probe resp.toString: " +
                            (str.length() > 200 ? str.substring(0, 200) + "..." : str));
                    }
                }

                if (amount == null && ui != null) {
                    for (java.lang.reflect.Field f : ui.getClass().getDeclaredFields()) {
                        f.setAccessible(true);
                        String n = f.getName().toLowerCase();
                        if (!n.contains("amount") && !n.contains("total") && !n.contains("fee")
                            && !n.contains("money")) continue;
                        try {
                            Object v = f.get(ui);
                            if (v instanceof String) amount = (String) v;
                            else if (v instanceof Number) amount = String.valueOf(((Number)v).longValue());
                            if (amount != null) {
                                LogWriter.log(TAG, "probe ui amount: " + n + "=" + amount);
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }

                if (amount != null && !amount.isEmpty()) {
                    String yuan = RedPacketHook.fenToYuan(amount);
                    String name = sender != null ? sender : "好友";
                    LogWriter.log(TAG, "probe TTS: sender=" + name + " amount=" + yuan);
                    TTSBroadcaster.announceTransfer(name, null, yuan + "元", desc);
                } else {
                    LogWriter.log(TAG, "probe FAIL: no amount found");
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "probe ERR: " + t.getMessage());
            }
        }
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
