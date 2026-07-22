package com.leshao.wechat;

public class AnnounceManager {
    private static int msgCount = 0;

    public static void handleMessage(WeChatMsg msg) {
        if (msg == null) return;

        if (msg.isText() && msg.content != null) {
            String ct = msg.content.trim();
            if (msg.isGroup && ct.contains(":\n")) {
                int idx = ct.indexOf(":\n");
                ct = ct.substring(idx + 2).trim();
            }
            Utils.xlog("CMD check: ct=" + ct.substring(0, Math.min(60, ct.length())));
            if (ct.startsWith("菜单") || ct.equals("乐少助手") || ct.startsWith("功能设置")
                || ct.startsWith("打开面板") || ct.startsWith("叮咚设置")
                || ct.startsWith("播报设置") || ct.startsWith("语音设置")) {
                Utils.xlog("CMD match: " + ct);
                final String talker = msg.talker;
                if (MainHook.currentActivity != null && !MainHook.currentActivity.isFinishing()) {
                    MainHook.mainHandler.post(new Runnable() { public void run() {
                        ModuleUI.showMainPanel(MainHook.currentActivity);
                    }});
                    WeChatHooks.sendTextMessage(talker, "\uD83C\uDF80 乐少助手面板已打开");
                } else {
                    WeChatHooks.sendTextMessage(talker, "\uD83C\uDF80 乐少助手v2.1\n长按微信底部Tab或发送「菜单」打开面板");
                }
                return;
            }
            if (ct.startsWith("状态")) {
                if (MainHook.currentActivity != null) FunctionDialogs.showStatus(MainHook.currentActivity);
                return;
            }
            if (ct.startsWith("帮助")) {
                if (MainHook.currentActivity != null) FunctionDialogs.showHelp(MainHook.currentActivity);
                return;
            }
            if (ct.equals("开启朗读")) { ModuleSettings.masterSwitch = true; ModuleSettings.saveAll(); WeChatHooks.sendTextMessage(msg.talker, "\u2705 消息朗读已开启"); return; }
            if (ct.equals("关闭朗读")) { ModuleSettings.masterSwitch = false; ModuleSettings.saveAll(); WeChatHooks.sendTextMessage(msg.talker, "\u274C 消息朗读已关闭"); return; }
        }

        if (!ModuleSettings.masterSwitch) return;
        try {
            msgCount++;
            String talker = msg.talker;
            String senderWxid = msg.senderWxid != null ? msg.senderWxid : talker;
            if (!ModuleSettings.WHITE_LIST.isEmpty()) {
                if (!ModuleSettings.WHITE_LIST.contains(senderWxid) && !ModuleSettings.WHITE_LIST.contains(talker)) return;
            }

            if (msg.isText() && msg.content != null) {
                String ct = msg.content.trim();
                if (ModuleSettings.keywordReplyEnabled) {
                    String reply = KeywordReplyManager.check(ct);
                    if (reply != null) { WeChatHooks.sendTextMessage(talker, reply); return; }
                }
                if (MediaAssistant.handleCommand(talker, ct)) return;
                if (GroupManager.checkAndInvite(talker, ct)) return;
                if (AIToolbox.handleAICommand(talker, ct)) return;
                if (msg.isGroup) {
                    GroupManager.handleAntiAd(talker, senderWxid, ct);
                    GroupManager.handleAutoKick(talker, senderWxid, ct);
                    GroupManager.handleActivityStats(talker, senderWxid);
                }
            }

            String text = buildAnnounce(msg, talker, senderWxid);
            if (text != null && !text.isEmpty()) {
                Utils.flog("\uD83D\uDCE2 #" + msgCount + " [" + (msg.isGroup ? "群" : "私") + "] " + text.substring(0, Math.min(60, text.length())));
                if (ModuleSettings.textTruncateEnabled && text.length() > ModuleSettings.textTruncateLength)
                    text = text.substring(0, ModuleSettings.textTruncateLength) + "...";
                TTSManager.speak(text);
            }
        } catch (Throwable e) { Utils.xlog("Announce ERR: " + e.getMessage()); }
    }

    private static String buildAnnounce(WeChatMsg msg, String talker, String senderWxid) {
        String senderName = WeChatHooks.resolveSenderName(senderWxid, msg.isGroup ? talker : null);
        if (senderName == null || senderName.isEmpty()) {
            senderName = WeChatHooks.resolveObjWxid(senderWxid);
            if (senderName == null || senderName.isEmpty()) senderName = senderWxid;
        }

        if (msg.isText()) {
            if (!ModuleSettings.announceText) return null;
            String ct = msg.content != null ? Utils.stt(msg.content.trim()) : "";
            if (ct.isEmpty()) return null;
            StringBuilder p = new StringBuilder();
            if (msg.isGroup && ModuleSettings.announceGroup) {
                String gn = WeChatHooks.resolveGroupName(talker);
                if (gn != null && !gn.isEmpty()) p.append(gn).append(" ");
            }
            if (ModuleSettings.announceNickname) p.append(senderName);
            if (p.length() > 0) p.append(" 说 ");
            if (ModuleSettings.keyVoiceAnnounce) return p.toString() + ct;
            return p.length() > 0 ? p.toString() + "消息" : senderName + "发来消息";
        }
        String prefix;
        if (msg.isGroup && ModuleSettings.announceGroup) {
            String gn = WeChatHooks.resolveGroupName(talker);
            prefix = (gn != null && !gn.isEmpty()) ? gn + "的" + senderName : senderName;
        } else {
            prefix = senderName;
        }
        if (msg.isImage()) { if (!ModuleSettings.announceImage) return null; return prefix + "发来[图片]"; }
        if (msg.isVideo()) { if (!ModuleSettings.announceVideo) return null; return prefix + "发来[视频]"; }
        if (msg.isVoice()) {
            StringBuilder vp = new StringBuilder();
            if (msg.isGroup && ModuleSettings.announceGroup) {
                String gn = WeChatHooks.resolveGroupName(talker);
                if (gn != null && !gn.isEmpty()) vp.append(gn).append(" ");
            }
            if (ModuleSettings.announceNickname) vp.append(senderName);
            if (vp.length() > 0) vp.append(" 说 ");
            return vp.toString() + "语音播放";
        }
        if (msg.isCard()) { if (!ModuleSettings.announceCard) return null; return prefix + "发来[名片]"; }
        if (msg.isSticker()) { if (!ModuleSettings.announceSticker) return null; return prefix + "发来一个表情"; }
        if (msg.isRedBag()) { if (!ModuleSettings.announceRedBag) return null; return prefix + "发来[红包]"; }
        if (msg.isTransfer()) { if (!ModuleSettings.announceTransfer) return null; return prefix + "发来[转账]"; }
        if (msg.isAppMsg()) {
            if (msg.isFileMsg()) { if (!ModuleSettings.announceFile) return null; return prefix + "发来[文件]"; }
            if (msg.isLocationMsg()) {
                if (!ModuleSettings.announceLocation) return null;
                String addr = extractLocation(msg.dbContent);
                return addr != null ? prefix + "发来[位置]" + addr : prefix + "发来[位置]";
            }
            return prefix + "发来一条链接";
        }
        if (msg.isVoip()) {
            if (!ModuleSettings.announceCall) return null;
            if (msg.dbContent != null && (msg.dbContent.contains("voipfinishmsg") || msg.dbContent.contains("FinishMsg"))) return null;
            return prefix + "发起语音通话";
        }
        return null;
    }

    private static String extractLocation(String xml) {
        if (xml == null) return null;
        try {
            java.util.regex.Pattern pp = java.util.regex.Pattern.compile("poiname=\"([^\"]*)\"");
            java.util.regex.Matcher pm = pp.matcher(xml); String pn = pm.find() ? pm.group(1) : "";
            java.util.regex.Pattern lp = java.util.regex.Pattern.compile("label=\"([^\"]*)\"");
            java.util.regex.Matcher lm = lp.matcher(xml); String lb = lm.find() ? lm.group(1) : "";
            String addr = "";
            if (!pn.isEmpty()) { addr = pn.replace("(", "").replace(")", ""); if (!lb.isEmpty() && !pn.replace("(", "").replace(")", "").contains(lb)) addr += lb; }
            else if (!lb.isEmpty()) addr = lb;
            if (addr.isEmpty()) { addr = xml.replaceAll("<[^>]+>", "").replaceAll("\\s+", "").trim(); if (addr.startsWith("<?")) addr = addr.substring(addr.indexOf("?>") + 2).trim(); }
            return addr.isEmpty() ? null : Utils.stt(addr);
        } catch (Throwable e) { return null; }
    }
}
