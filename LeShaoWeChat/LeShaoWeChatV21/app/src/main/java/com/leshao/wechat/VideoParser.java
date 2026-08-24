package com.leshao.wechat;

import android.content.Context;

public class VideoParser {
    private static final String[] PLATFORMS = {"抖音","快手","微博","美拍","好看","微视","绿洲","虎牙","陌陌","今日头条","西瓜视频","彩视","比心","快影","美团","映客","开眼","最右","梨视频","皮皮虾","支付宝","六间房","皮皮搞笑","哔哩哔哩","凤凰视频","腾讯视频","全民K歌","UC大鱼","VIVO","soul","AcFun"};
    public static void init(Context ctx) {}

    public static String parse(String url) {
        try { String body = Utils.httpGet("https://api.vvhan.com/api/video/parse?url=" + Utils.ue(url));
            if (body == null) return null;
            org.json.JSONObject j = new org.json.JSONObject(body);
            if (j.optBoolean("success")) { org.json.JSONObject d = j.optJSONObject("data"); if (d != null) return d.optString("video", ""); }
        } catch (Exception e) {}
        return null;
    }

    public static void extractAudio(final String talker, final String url) {
        Utils.rB(new Runnable() { public void run() {
            String vu = parse(url);
            if (vu != null && !vu.isEmpty()) { String p = ModuleSettings.cacheDir + "/va_" + System.currentTimeMillis() + ".mp4"; if (Utils.dl(vu, p)) WeChatHooks.sendTextMessage(talker, "视频已下载，提取中..."); else WeChatHooks.sendTextMessage(talker, "下载失败"); }
            else WeChatHooks.sendTextMessage(talker, "解析失败");
        }});
    }
    public static void sendVideoDirect(final String talker, final String url) {
        Utils.rB(new Runnable() { public void run() {
            String vu = parse(url);
            if (vu != null && !vu.isEmpty()) { String p = ModuleSettings.cacheDir + "/vs_" + System.currentTimeMillis() + ".mp4"; if (Utils.dl(vu, p)) WeChatHooks.sendTextMessage(talker, "视频已下载"); else WeChatHooks.sendTextMessage(talker, "下载失败"); }
            else WeChatHooks.sendTextMessage(talker, "解析失败");
        }});
    }
    public static String getPlatforms() {
        StringBuilder sb = new StringBuilder(); for (int i = 0; i < PLATFORMS.length; i++) { if (i > 0) sb.append(" "); sb.append(PLATFORMS[i]); } return sb.toString();
    }
}
