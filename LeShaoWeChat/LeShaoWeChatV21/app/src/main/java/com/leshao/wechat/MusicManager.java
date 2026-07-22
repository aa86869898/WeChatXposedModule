package com.leshao.wechat;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

public class MusicManager {
    private static Context ctx;
    public static void init(Context c) { ctx = c; }

    public static JSONObject searchHuangGou(String kw) {
        try {
            String body = Utils.httpGetH("https://www.kuwo.cn/api/www/search/searchMusicBykeyWord?key=" + Utils.ue(kw) + "&pn=1&rn=10",
                new String[][]{{"Referer","https://www.kuwo.cn/"},{"csrf","notoken"}});
            if (body == null) return null;
            JSONArray list = new JSONObject(body).optJSONObject("data").optJSONArray("list");
            if (list == null || list.length() == 0) return null;
            JSONObject r = new JSONObject(); r.put("list", list); r.put("source", "kuwo"); return r;
        } catch (Exception e) { return null; }
    }

    public static JSONObject searchDouyinMusic(String kw) {
        try {
            String body = Utils.httpGet("https://api.vvhan.com/api/douyin/music?keyword=" + Utils.ue(kw));
            if (body == null) return null;
            JSONObject j = new JSONObject(body);
            return j.optBoolean("success") ? j : null;
        } catch (Exception e) { return null; }
    }

    public static JSONObject searchLanGou(String kw) {
        try {
            JSONObject r = new JSONObject(); r.put("keyword", kw); r.put("source", "kugou");
            return r;
        } catch (Exception e) { return null; }
    }

    public static String getPlayUrl(String rid, String source) {
        try {
            if ("kuwo".equals(source)) {
                String body = Utils.httpGetH("https://www.kuwo.cn/api/v1/www/music/playUrl?mid=" + rid + "&type=convert_url3",
                    new String[][]{{"Referer","https://www.kuwo.cn/"},{"csrf","notoken"}});
                if (body != null) {
                    JSONObject j = new JSONObject(body);
                    if (j.optInt("code") == 200) { String url = j.optJSONObject("data").optString("url", ""); if (url.startsWith("//")) url = "https:" + url; return url; }
                }
            }
        } catch (Exception e) {}
        return null;
    }

    public static JSONObject searchNetEase(String kw) {
        try {
            String body = Utils.httpGetH("https://music.163.com/api/search/get?s=" + Utils.ue(kw) + "&type=1&limit=10",
                new String[][]{{"Referer","https://music.163.com/"}});
            if (body == null) return null;
            JSONArray songs = new JSONObject(body).optJSONObject("result").optJSONArray("songs");
            if (songs == null || songs.length() == 0) return null;
            JSONObject r = new JSONObject(); r.put("list", songs); r.put("source", "netease"); return r;
        } catch (Exception e) { return null; }
    }
}
