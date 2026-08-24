package com.leshao.v3.hook;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
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

    // 微信消息原始类型码
    private static final int TYPE_REDPACKET = 436207665;   // 0x1A000031 微信红包
    private static final int TYPE_TRANSFER = 419430449;    // 0x19000031 微信转账

    private static volatile boolean sEnabled = true;
    private static volatile boolean sPrivateEnabled = true;
    private static volatile boolean sGroupEnabled = false;
    private static volatile boolean sTransferEnabled = true; // 转账自动收款, 独立于红包开关
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

    // 已处理的红包/转账消息去重
    private static final Set<Long> sHandledMsgs = new HashSet<>();

    public static void setEnabled(boolean v) { sEnabled = v; }
    public static void setPrivateEnabled(boolean v) { sPrivateEnabled = v; }
    public static void setGroupEnabled(boolean v) { sGroupEnabled = v; }
    public static void setTransferEnabled(boolean v) { sTransferEnabled = v; }
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
        loadConfig();
        ClassLoader cl = ContextManager.getClassLoader();
        hookReceiveUIs(cl);
        hookChatListClick(cl);
        hookRedPacketUI(cl);
        hookTransferUIs(cl);
        LogWriter.log(TAG, "hooks installed (event-driven auto grab + open + TTS + transfer)");
    }

    // 启动时从 SharedPreferences 加载配置, 保证开关/范围/过滤在重启后仍生效
    private static void loadConfig() {
        try {
            android.content.SharedPreferences prefs = ContextManager.getPrefs();
            if (prefs == null) return;
            sEnabled = prefs.getBoolean("ls_redpacket_enabled", true);
            sPrivateEnabled = prefs.getBoolean("ls_rp_private", true);
            sGroupEnabled = prefs.getBoolean("ls_rp_group", false);
            sTimeFilterOn = prefs.getBoolean("ls_rp_time_on", false);
            sTimeStart = prefs.getString("ls_rp_time_start", "00:00");
            sTimeEnd = prefs.getString("ls_rp_time_end", "06:00");
            sKeywordExcludeOn = prefs.getBoolean("ls_rp_kw_exclude_on", false);
            sKeywordExclude = parseKeywordSet(prefs.getString("ls_rp_kw_exclude", ""));
            sKeywordIncludeOn = prefs.getBoolean("ls_rp_kw_include_on", false);
            sKeywordInclude = parseKeywordSet(prefs.getString("ls_rp_kw_include", ""));
            sFastPrivateWxids = parseIdSet(prefs.getString("ls_rp_fast_private", ""));
            sFastGroupIds = parseIdSet(prefs.getString("ls_rp_fast_group", ""));
            sTtsAnnounce = prefs.getBoolean("ls_rp_tts_announce", true);
            sTransferEnabled = prefs.getBoolean("ls_transfer_enabled", true);
            LogWriter.log(TAG, "config loaded: enabled=" + sEnabled + " private=" + sPrivateEnabled
                + " group=" + sGroupEnabled + " tts=" + sTtsAnnounce
                + " transfer=" + sTransferEnabled
                + " contains(enabled)=" + prefs.contains("ls_redpacket_enabled")
                + " contains(private)=" + prefs.contains("ls_rp_private"));
        } catch (Throwable t) {
            LogWriter.log(TAG, "loadConfig err: " + t);
        }
    }

    private static Set<String> parseKeywordSet(String raw) {
        Set<String> set = new HashSet<>();
        if (raw == null || raw.trim().isEmpty()) return set;
        for (String kw : raw.split("\\s+")) {
            if (!kw.trim().isEmpty()) set.add(kw.trim());
        }
        return set;
    }

    private static Set<String> parseIdSet(String raw) {
        Set<String> set = new HashSet<>();
        if (raw == null || raw.trim().isEmpty()) return set;
        for (String id : raw.split(",")) {
            if (!id.trim().isEmpty()) set.add(id.trim());
        }
        return set;
    }

    // ==================== 入口: 由 MessageHook 在新消息到达时调用 ====================
    public static boolean isRedPacketType(int rawType) {
        return rawType == TYPE_REDPACKET || rawType == 318767153; // 0x1A000031 / 0x13000031
    }

    public static boolean isTransferType(int rawType) {
        return rawType == TYPE_TRANSFER; // 0x19000031
    }

    // 诊断辅助: 判断消息内容是否像红包/转账（用于校准类型码，命中时打印 rawType）
    public static boolean looksLikeMoneyMessage(String content) {
        if (content == null) return false;
        String c = content.toLowerCase();
        return c.contains("nativeurl") || c.contains("sendid")
            || c.contains("channelid") || c.contains("transferid")
            || c.contains("luckymoney") || c.contains("remittance")
            || c.contains("hongbao");
    }

    public static void onIncomingMessage(int rawType, String talker, String content, long msgId) {
        try {
            if (isRedPacketType(rawType)) {
                if (!sEnabled) return;
                onRedPacketArrived(talker, content, msgId);
            } else if (isTransferType(rawType)) {
                if (!sTransferEnabled) return;
                onTransferArrived(talker, content, msgId);
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "onIncomingMessage err: " + t);
        }
    }

    private static void onRedPacketArrived(String talker, String content, long msgId) {
        synchronized (sHandledMsgs) {
            if (msgId != 0 && !sHandledMsgs.add(msgId)) return;
            if (sHandledMsgs.size() > 200) {
                Long oldest = sHandledMsgs.iterator().next();
                sHandledMsgs.remove(oldest);
            }
        }
        if (!shouldGrabRedPacket(talker, content)) return;
        if (isRedPacketReceived(content)) return;

        LogWriter.log(TAG, "RP arrived talker=" + talker + " msgId=" + msgId);
        LuckMoneyBackend.grabRedPacket(talker, content, msgId);
    }

    private static void onTransferArrived(String talker, String content, long msgId) {
        synchronized (sHandledMsgs) {
            if (msgId != 0 && !sHandledMsgs.add(msgId)) return;
            if (sHandledMsgs.size() > 200) {
                Long oldest = sHandledMsgs.iterator().next();
                sHandledMsgs.remove(oldest);
            }
        }
        LogWriter.log(TAG, "TRANSFER arrived talker=" + talker + " msgId=" + msgId);
        LuckMoneyBackend.grabTransfer(talker, content, msgId);
    }

    private static boolean shouldGrabRedPacket(String talker, String content) {
        boolean isGroup = talker != null && talker.endsWith("@chatroom");
        if (isGroup) {
            if (!sGroupEnabled) return false;
        } else {
            if (!sPrivateEnabled) return false;
        }
        if (sTimeFilterOn && inTimeRange()) return false;
        String wishing = extractXmlText(content, "des");
        if (sKeywordIncludeOn && !sKeywordInclude.isEmpty()) {
            boolean hit = false;
            for (String kw : sKeywordInclude) {
                if (wishing != null && wishing.contains(kw)) { hit = true; break; }
            }
            if (!hit) return false;
        }
        if (sKeywordExcludeOn && !sKeywordExclude.isEmpty()) {
            for (String kw : sKeywordExclude) {
                if (wishing != null && wishing.contains(kw)) return false;
            }
        }
        return true;
    }

    // 时间段过滤: 在 [start, end] 区间内不领取
    private static boolean inTimeRange() {
        try {
            Calendar now = Calendar.getInstance();
            int cur = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            int s = parseMinute(sTimeStart);
            int e = parseMinute(sTimeEnd);
            if (s <= e) return cur >= s && cur <= e;
            return cur >= s || cur <= e; // 跨天区间
        } catch (Throwable t) {
            return false;
        }
    }

    private static int parseMinute(String hhmm) {
        if (hhmm == null) return 0;
        String[] p = hhmm.split(":");
        try {
            return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
        } catch (Throwable t) { return 0; }
    }

    private static boolean isRedPacketReceived(String content) {
        if (content == null) return false;
        return content.contains("<isReceivies>1</isReceivies>")
            || content.contains("<isReceivies>true</isReceivies>")
            || content.contains("<receiveStatus>1</receiveStatus>");
    }

    private static String extractXmlText(String xml, String tag) {
        if (xml == null || tag == null) return null;
        int s = xml.indexOf("<" + tag + ">");
        if (s < 0) return null;
        s += tag.length() + 2;
        int e = xml.indexOf("</" + tag + ">", s);
        if (e < 0) return null;
        return xml.substring(s, e);
    }

    // ==================== TTS: LuckyMoneyDetailUI.onResume 读UI金额 ====================
    public static void hookRedPacketUI(ClassLoader cl) {
        try {
            Class<?> cls = cl.loadClass(PKG_WECHAT + ".plugin.luckymoney.ui.LuckyMoneyDetailUI");
            java.lang.reflect.Method m = cls.getDeclaredMethod("onResume");
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                                        Activity act = (Activity) p.thisObject;
                                        sHandler.postDelayed(() -> {
                                            String amt = scanAmount(act.getWindow().getDecorView());
                                            if (amt != null && !amt.isEmpty()) {
                                                LogWriter.log(TAG, "RP: " + amt);
                                                if (sTtsAnnounce) {
                                                    TTSBroadcaster.announceRedPacket("好友", null, null, amt);
                                                }
                                            }
                                        }, 800);
                    } catch (Throwable e) {
                        LogWriter.log("RedPacket", "cb err: " + e);
                    }
                }
            });
            LogWriter.log(TAG, "RP OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "RP fail: " + t);
        }
    }

    static String scanAmount(View root) {
        if (root instanceof TextView) {
            String t = ((TextView) root).getText().toString();
            java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(\\d+\\.\\d{2})").matcher(t);
            if (m.find() && t.length() < 20) return m.group(1);
        }
        if (root instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
                String r = scanAmount(((ViewGroup) root).getChildAt(i));
                if (r != null) return r;
            }
        return null;
    }

    // ==================== 领取页自动点"开" ====================
    private static void hookReceiveUIs(ClassLoader cl) {
        hookInitView(cl, PKG_WECHAT + ".plugin.luckymoney.ui.LuckyMoneyNewReceiveUI", "initView");
        hookInitView(cl, PKG_WECHAT + ".plugin.luckymoney.ui.LuckyMoneyNotHookReceiveUI", "initView");
        tryHookAndClick(cl, PKG_WECHAT + ".plugin.luckymoney.ui.LuckyMoneyBusiReceiveUI");
        tryHookAndClick(cl, PKG_WECHAT + ".plugin.luckymoney.ui.LuckyMoneyBusiReceiveUIV2");
        tryHookAndClick(cl, PKG_WECHAT + ".plugin.luckymoney.hk.ui.LuckyMoneyHKReceiveUI");
        tryHookAndClick(cl, PKG_WECHAT + ".plugin.luckymoney.hk.ui.LuckyMoneyHKBeforeDetailUI");
        tryHookAndClick(cl, PKG_WECHAT + ".plugin.luckymoney.ui.LuckyMoneyNewDetailUI");
        tryHookAndClick(cl, PKG_WECHAT + ".plugin.luckymoney.ui.LuckyMoneyBeforeDetailUI");
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
            View open = findOpenView(root);
            if (open != null && open.isEnabled() && isVisible(open)) {
                open.performClick();
                LogWriter.log(TAG, "auto-clicked open button");
            } else {
                LogWriter.log(TAG, "open button not found");
            }
        } catch (Throwable ignored) {} finally {
            sProcessing.set(false);
        }
    }

    // 查找红包领取页"开"按钮: 支持 Button/TextView/ImageView/自定义 View
    private static View findOpenView(View v) {
        if (v instanceof TextView) {
            String s = ((TextView) v).getText().toString();
            if (isOpenLabel(s)) return findClickableSelfOrParent(v);
        }
        try {
            if (v.getId() != View.NO_ID) {
                String resName = v.getResources().getResourceEntryName(v.getId());
                if (resName != null) {
                    String rl = resName.toLowerCase();
                    if (rl.contains("open") || rl.contains("receive")) {
                        return findClickableSelfOrParent(v);
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                View r = findOpenView(((ViewGroup) v).getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    private static boolean isOpenLabel(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;
        if (t.equals("开") || t.equals("拆") || t.equals("開")) return true;
        if (t.length() <= 4 && (t.contains("开") || t.contains("拆"))) return true;
        if (t.contains("领取") && t.length() <= 6) return true;
        return t.contains("Open") || t.contains("OPEN");
    }

    private static boolean isVisible(View v) {
        return v.getVisibility() == View.VISIBLE && v.getWidth() > 0 && v.getHeight() > 0;
    }

    private static View findClickableSelfOrParent(View v) {
        View cur = v;
        while (cur != null) {
            if (cur.isClickable() && isVisible(cur)) return cur;
            cur = (View) cur.getParent();
        }
        return v;
    }

    // ==================== 转账自动确认收款 + 播报 ====================
    private static void hookTransferUIs(ClassLoader cl) {
        String[] candidates = {
            PKG_WECHAT + ".plugin.remittance.ui.RemittanceDetailUI",
            PKG_WECHAT + ".plugin.collect.ui.CollectDetailUI",
        };
        for (String clsName : candidates) {
            try {
                Class<?> cls = XposedHelpers.findClass(clsName, cl);
                XposedBridge.hookAllMethods(cls, "onResume", new TransferDetailHook());
                LogWriter.log(TAG, "[OK] " + clsName + ".onResume()");
            } catch (Throwable ignored) {
                LogWriter.log(TAG, "[MISS] " + clsName);
            }
        }
    }

    static class TransferDetailHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (!sTransferEnabled) return;
                Activity act = (Activity) param.thisObject;
                sHandler.postDelayed(() -> {
                    try {
                        View root = act.getWindow().getDecorView();
                        String amt = scanAmount(root);
                        // 方案1: 反编译确认的直接确认收款方法 RemittanceDetailUI.X6()
                        // (拒绝方法为 Y6(); 底层 NetScene 为 model.n0)
                        try {
                            XposedHelpers.callMethod(param.thisObject, "X6");
                            LogWriter.log(TAG, "auto-confirm transfer via RemittanceDetailUI.X6()");
                            if (sTtsAnnounce && amt != null && !amt.isEmpty()) {
                                TTSBroadcaster.announceTransfer("好友", null, amt, null);
                            }
                            return;
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "X6() 调用失败, 回退UI点击: " + t.getMessage());
                        }
                        // 方案2: 原UI点击兜底
                        View receive = findReceiveView(root);
                        if (receive != null && receive.isEnabled() && isVisible(receive)) {
                            receive.performClick();
                            LogWriter.log(TAG, "auto-clicked confirm receive button (fallback)");
                            if (sTtsAnnounce && amt != null && !amt.isEmpty()) {
                                TTSBroadcaster.announceTransfer("好友", null, amt, null);
                            }
                        }
                    } catch (Throwable ignored) {}
                }, 600);
            } catch (Throwable ignored) {}
        }
    }

    private static View findReceiveView(View v) {
        if (v instanceof TextView) {
            String s = ((TextView) v).getText().toString();
            if (s.contains("确认收款") || s.contains("收款")) {
                return findClickableSelfOrParent(v);
            }
        }
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                View r = findReceiveView(((ViewGroup) v).getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
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
                Activity act = (Activity) param.thisObject;
                VoiceAutoPlay.notifyChattingUIResume(act);
            } catch (Throwable ignored) {}
        }
    }
}
