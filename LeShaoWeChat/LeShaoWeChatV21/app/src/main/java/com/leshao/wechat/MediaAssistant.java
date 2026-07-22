package com.leshao.wechat;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

public class MediaAssistant {
    private static Context ctx;
    public static void init(Context c) { ctx = c; }

    public static boolean handleCommand(String talker, String content) {
        if (!ModuleSettings.dianGeEnabled || content == null) return false;
        content = content.trim();
        if (content.startsWith("点歌 ") && ModuleSettings.ddMusicEnabled) { searchMusic(talker, content.substring(3).trim()); return true; }
        if (content.startsWith("语音点歌 ") && ModuleSettings.ddVoiceSongEnabled) { searchVoiceSong(talker, content.substring(5).trim()); return true; }
        if (content.startsWith("歌词 ") && ModuleSettings.ddLyricsEnabled) { searchLyrics(talker, content.substring(3).trim()); return true; }
        if (content.startsWith("天气 ") && ModuleSettings.ddWeatherEnabled) { searchWeather(talker, content.substring(3).trim()); return true; }
        if (content.equals("笑话") && ModuleSettings.ddFunEnabled) { tellJoke(talker); return true; }
        if (content.equals("金句") && ModuleSettings.ddFunEnabled) { tellQuote(talker); return true; }
        return false;
    }

    public static void searchMusic(final String talker, final String keyword) {
        Utils.rB(new Runnable() { public void run() {
            try {
                JSONObject song = null;
                JSONObject hg = MusicManager.searchHuangGou(keyword);
                if (hg != null && hg.optJSONArray("list") != null && hg.optJSONArray("list").length() > 0) {
                    JSONObject f = hg.optJSONArray("list").getJSONObject(0);
                    song = new JSONObject(); song.put("name", f.optString("name", "?"));
                    song.put("artist", f.optString("artist", "?")); song.put("url", "https://www.kuwo.cn/play_detail/" + f.optString("rid"));
                }
                if (song == null) {
                    JSONObject ne = MusicManager.searchNetEase(keyword);
                    if (ne != null && ne.optJSONArray("list") != null && ne.optJSONArray("list").length() > 0) {
                        JSONObject f = ne.optJSONArray("list").getJSONObject(0);
                        String artist = ""; JSONArray ar = f.optJSONArray("artists");
                        if (ar != null && ar.length() > 0) artist = ar.getJSONObject(0).optString("name", "");
                        song = new JSONObject(); song.put("name", f.optString("name", "?"));
                        song.put("artist", artist); song.put("url", "https://music.163.com/song/media/outer/url?id=" + f.optInt("id") + ".mp3");
                    }
                }
                if (song != null) WeChatHooks.sendTextMessage(talker, "🎵 " + song.optString("name") + " - " + song.optString("artist") + "\n" + song.optString("url"));
                else WeChatHooks.sendTextMessage(talker, "未找到: " + keyword);
            } catch (Exception e) {}
        }});
    }

    public static void searchVoiceSong(final String talker, final String keyword) {
        Utils.rB(new Runnable() { public void run() {
            try {
                JSONObject hg = MusicManager.searchHuangGou(keyword);
                if (hg != null && hg.optJSONArray("list") != null && hg.optJSONArray("list").length() > 0) {
                    JSONObject f = hg.optJSONArray("list").getJSONObject(0);
                    String url = MusicManager.getPlayUrl(f.optString("rid"), "kuwo");
                    if (url != null && !url.isEmpty()) {
                        String mp3 = ModuleSettings.cacheDir + "/song_" + System.currentTimeMillis() + ".mp3";
                        if (Utils.dl(url, mp3)) { WeChatHooks.sendTextMessage(talker, "🎵 已下载: " + f.optString("name") + " - " + f.optString("artist")); return; }
                    }
                }
                WeChatHooks.sendTextMessage(talker, "未找到资源: " + keyword);
            } catch (Exception e) {}
        }});
    }

    public static void searchLyrics(final String talker, final String keyword) {
        Utils.rB(new Runnable() { public void run() {
            try {
                String body = Utils.httpGet("https://api.mlwei.com/lyric/api/?key=523077333&id=" + Utils.ue(keyword) + "&fmt=json");
                String lyric = "未找到歌词";
                if (body != null) { try { lyric = new JSONObject(body).optString("lyric", lyric); } catch (Exception e) {} if (lyric.length() > 800) lyric = lyric.substring(0, 800) + "..."; }
                WeChatHooks.sendTextMessage(talker, lyric);
            } catch (Exception e) {}
        }});
    }

    public static void searchWeather(final String talker, final String city) {
        Utils.rB(new Runnable() { public void run() {
            try { String body = Utils.httpGet("https://wttr.in/" + Utils.ue(city) + "?format=3&lang=zh"); WeChatHooks.sendTextMessage(talker, "🌤 " + (body != null ? body : "查询失败")); } catch (Exception e) {}
        }});
    }

    public static void tellJoke(final String talker) {
        Utils.rB(new Runnable() { public void run() {
            try { String body = Utils.httpGet("https://api.vvhan.com/api/joke?type=json"); String joke = "笑话获取失败";
                if (body != null) try { joke = new JSONObject(body).optString("joke", joke); } catch (Exception e) {}
                WeChatHooks.sendTextMessage(talker, "😂 " + joke); } catch (Exception e) {}
        }});
    }

    public static void tellQuote(final String talker) {
        Utils.rB(new Runnable() { public void run() {
            try { String body = Utils.httpGet("https://api.vvhan.com/api/ian?type=json"); String quote = "金句获取失败";
                if (body != null) try { quote = new JSONObject(body).optJSONObject("data").optString("content", quote); } catch (Exception e) {}
                WeChatHooks.sendTextMessage(talker, "💬 " + quote); } catch (Exception e) {}
        }});
    }
}
