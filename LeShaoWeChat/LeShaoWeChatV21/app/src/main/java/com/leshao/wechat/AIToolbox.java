package com.leshao.wechat;

import android.content.Context;
import org.json.JSONObject;

public class AIToolbox {
    private static final String IMG = "https://ark.cn-beijing.volces.com/api/v3/images/generations";
    private static final String VID = "https://ark.cn-beijing.volces.com/api/v3/video/generations";
    public static void init(Context ctx) {}

    public static boolean handleAICommand(String talker, String content) {
        if (!ModuleSettings.aiToolboxEnabled || content == null) return false;
        content = content.trim();
        if ((content.startsWith("生图 ") || content.startsWith("生成图片 ")) && ModuleSettings.imageGenEnabled) {
            String p = content.replaceFirst("^(生图|生成图片) ", "").trim();
            if (!p.isEmpty()) { genImage(talker, p); return true; }
        }
        if ((content.startsWith("生视频 ") || content.startsWith("生成视频 ")) && ModuleSettings.videoGenEnabled) {
            String p = content.replaceFirst("^(生视频|生成视频) ", "").trim();
            if (!p.isEmpty()) { genVideo(talker, p); return true; }
        }
        return false;
    }

    public static void genImage(final String talker, final String prompt) {
        final String key = ModuleSettings.arkApiKey;
        if (key.isEmpty()) { WeChatHooks.sendTextMessage(talker, "请先配置火山方舟API Key"); return; }
        Utils.rB(new Runnable() { public void run() {
            try {
                JSONObject req = new JSONObject(); req.put("model", ModuleSettings.arkImageModel);
                req.put("prompt", prompt); req.put("size", ModuleSettings.arkImageSize);
                req.put("response_format", ModuleSettings.arkImageFormat); req.put("n", 1);
                String resp = Utils.httpPost(IMG, req.toString(), "application/json; charset=utf-8");
                if (resp != null) { JSONObject j = new JSONObject(resp); JSONObject d = j.optJSONObject("data");
                    if (d != null) { String url = d.optString("url", ""); if (!url.isEmpty()) {
                        String p = ModuleSettings.cacheDir + "/ai_" + System.currentTimeMillis() + ".png";
                        if (Utils.dl(url, p)) WeChatHooks.sendTextMessage(talker, "🎨 AI生图: " + prompt); } return; } }
                WeChatHooks.sendTextMessage(talker, "AI生图失败");
            } catch (Exception e) {}
        }});
    }

    public static void genVideo(final String talker, final String prompt) {
        final String key = ModuleSettings.arkApiKey;
        if (key.isEmpty()) { WeChatHooks.sendTextMessage(talker, "请先配置火山方舟API Key"); return; }
        Utils.rB(new Runnable() { public void run() {
            try {
                JSONObject req = new JSONObject(); req.put("model", ModuleSettings.arkVideoModel);
                req.put("prompt", prompt); req.put("duration", ModuleSettings.arkVideoDuration);
                req.put("resolution", ModuleSettings.arkVideoResolution);
                String resp = Utils.httpPost(VID, req.toString(), "application/json; charset=utf-8");
                if (resp != null) { JSONObject j = new JSONObject(resp); JSONObject d = j.optJSONObject("data");
                    if (d != null) { String url = d.optString("url", ""); if (!url.isEmpty()) {
                        String p = ModuleSettings.cacheDir + "/aiv_" + System.currentTimeMillis() + ".mp4";
                        if (Utils.dl(url, p)) WeChatHooks.sendTextMessage(talker, "🎬 AI生视频: " + prompt); } return; } }
                WeChatHooks.sendTextMessage(talker, "AI生视频失败");
            } catch (Exception e) {}
        }});
    }
}
