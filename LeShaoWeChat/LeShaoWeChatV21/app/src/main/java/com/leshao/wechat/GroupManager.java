package com.leshao.wechat;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public class GroupManager {
    private static final Map<String,Map<String,Integer>> vm = new HashMap<>();
    private static final Map<String,Map<String,Integer>> stats = new HashMap<>();
    private static final Map<String,String> votes = new HashMap<>();

    public static boolean checkAndInvite(String talker, String content) {
        if (!ModuleSettings.groupInviteEnabled || !WeChatHooks.isGroupChat(talker)) return false;
        String kw = ModuleSettings.groupInviteKeyword;
        if (kw != null && !kw.isEmpty() && content != null && content.contains(kw)) {
            WeChatHooks.sendTextMessage(talker, "检测到邀请关键词，自动拉人功能已就绪");
            return true;
        }
        return false;
    }

    public static void handleAntiAd(String talker, String swxid, String content) {
        if (!ModuleSettings.antiAdEnabled || content == null) return;
        for (String k : ModuleSettings.adKeywords) if (content.contains(k)) {
            WeChatHooks.sendTextMessage(talker, "[AtWx=" + swxid + "] 检测到广告内容!");
            return;
        }
    }

    public static void handleAutoKick(String talker, String swxid, String content) {
        if (!ModuleSettings.autoKickEnabled || content == null) return;
        for (String k : ModuleSettings.kickKeywords) {
            if (content.contains(k)) {
                Map<String,Integer> gm = vm.get(talker); if (gm == null) { gm = new HashMap<>(); vm.put(talker, gm); }
                Integer c = gm.get(swxid); int cnt = c == null ? 0 : c.intValue(); cnt++;
                gm.put(swxid, Integer.valueOf(cnt));
                if (cnt >= ModuleSettings.kickThreshold) {
                    WeChatHooks.sendTextMessage(talker, "[AtWx=" + swxid + "] 触发" + cnt + "次，已踢出"); gm.remove(swxid);
                } else {
                    WeChatHooks.sendTextMessage(talker, "[AtWx=" + swxid + "] 警告(" + cnt + "/" + ModuleSettings.kickThreshold + ")");
                }
                return;
            }
        }
    }

    public static void handleActivityStats(String talker, String swxid) {
        if (!ModuleSettings.announceGroup) return;
        Map<String,Integer> gm = stats.get(talker); if (gm == null) { gm = new HashMap<>(); stats.put(talker, gm); }
        Integer c = gm.get(swxid); gm.put(swxid, Integer.valueOf(c == null ? 1 : c.intValue() + 1));
    }

    // 投票
    public static String createVote(String talker, String content) {
        if (!ModuleSettings.announceGroup || !content.startsWith("投票 ")) return null;
        try {
            String body = content.substring(3).trim();
            String[] parts = body.split("\\|");
            if (parts.length < 2) return "格式: 投票 标题|选项1|选项2|...";
            String title = parts[0];
            JSONObject v = new JSONObject(); v.put("title", title); v.put("talker", talker);
            JSONArray opts = new JSONArray();
            for (int i = 1; i < parts.length; i++) opts.put(parts[i].trim());
            v.put("options", opts);
            String vid = "vote_" + talker + "_" + System.currentTimeMillis();
            votes.put(vid, v.toString());
            StringBuilder sb = new StringBuilder("📊 投票: " + title + "\n");
            for (int i = 0; i < opts.length(); i++) sb.append(i + 1).append(". ").append(opts.optString(i)).append("\n");
            sb.append("回复数字投票");
            return sb.toString();
        } catch (Exception e) { return "投票创建失败"; }
    }

    public static String handleVote(String talker, String content) {
        if (votes.isEmpty()) return null;
        for (Map.Entry<String,String> e : votes.entrySet()) {
            try {
                JSONObject v = new JSONObject(e.getValue());
                if (!talker.equals(v.optString("talker"))) continue;
                JSONArray opts = v.optJSONArray("options");
                if (opts == null) continue;
                for (int i = 0; i < opts.length(); i++) {
                    if (content.equals(String.valueOf(i + 1)) || content.equals(opts.optString(i))) {
                        JSONObject results = v.optJSONObject("results");
                        if (results == null) { results = new JSONObject(); v.put("results", results); }
                        results.put(String.valueOf(i + 1), results.optInt(String.valueOf(i + 1), 0) + 1);
                        votes.put(e.getKey(), v.toString());
                        StringBuilder sb = new StringBuilder("当前统计:\n");
                        for (int j = 0; j < opts.length(); j++) {
                            sb.append(j + 1).append(". ").append(opts.optString(j))
                              .append(": ").append(results.optInt(String.valueOf(j + 1), 0)).append("票\n");
                        }
                        return sb.toString();
                    }
                }
            } catch (Exception ex) {}
        }
        return null;
    }
}
