package com.leshao.v3.ui;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MusicSearchApi {

    private static final String TAG = "MusicSearchApi";
    private static final ExecutorService sExecutor = Executors.newFixedThreadPool(4);
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    // ===== Cookie =====

    public static class Song {
        public String id;
        public String title;
        public String artist;
        public String album;
        public String cover;
        public int duration;
        public String playUrl;
        public String lyric;
        public int platform; // 0=KuGou, 1=KuWo
        public String hash;
        public String sqHash;
        public String hash320;
        public String originHash;
        public String albumId;
        public String albumAudioId;
    }

    public interface SearchCallback {
        void onResult(List<Song> songs, int total, boolean hasPrev, boolean hasNext);
        void onError(String msg);
    }

    public interface PlayUrlCallback {
        void onUrl(String url);
        void onError(String msg);
    }

    public interface LyricCallback {
        void onLyric(String lrc);
        void onError(String msg);
    }

    // ===== KuGou =====
    private static final String KG_SEARCH = "https://songsearch.kugou.com/song_search_v2";
    private static final String KG_PLAY_FALLBACK = "https://music.haitangw.cc/kgqq1/kg.php";
    private static final String KG_SEARCH_ALBUM = "https://msearch.kugou.com/api/v3/search/album";
    private static final String KG_SEARCH_SHEET = "http://mobilecdn.kugou.com/api/v3/search/special";
    private static final String LYRICS_SEARCH = "https://lyrics.kugou.com/search";
    private static final String LYRICS_DOWNLOAD = "https://lyrics.kugou.com/download";
    private static final String FULL_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/106.0.0.0 Safari/537.36";
    private static final String LYRICS_UA = "KuGou2012-9020-ExpandSearchManager";

    public static void searchKugou(String query, int page, SearchCallback cb) {
        MusicLog.i(TAG, "searchKugou q=" + query + " page=" + page);
        sExecutor.execute(() -> {
            try {
                String urlStr = KG_SEARCH + "?keyword=" + URLEncoder.encode(query, "UTF-8")
                    + "&page=" + page + "&pagesize=20&userid=0&clientver=&platform=WebFilter"
                    + "&filter=2&iscorrection=1&privilege_filter=0&area_code=1";
                String resp = httpGet(urlStr, "https://songsearch.kugou.com");

                if (resp == null || resp.isEmpty()) {
                    MusicLog.e(TAG, "searchKugou: empty resp");
                    postError(cb, "音源接口请求失败");
                    return;
                }
                String trimmed = resp.trim();
                if (!trimmed.startsWith("{")) {
                    MusicLog.e(TAG, "searchKugou: non-JSON resp=[" + truncated(trimmed, 200) + "]");
                    postError(cb, "音源接口请求失败");
                    return;
                }

                JSONObject json = new JSONObject(trimmed);
                JSONObject data = json.optJSONObject("data");
                if (data == null) { MusicLog.e(TAG, "searchKugou: no data"); postError(cb, "无搜索结果"); return; }

                JSONArray lists = data.optJSONArray("lists");
                int total = data.optInt("total", 0);

                List<Song> songs = new ArrayList<>();
                if (lists != null) {
                    for (int i = 0; i < lists.length(); i++) {
                        JSONObject item = lists.optJSONObject(i);
                        if (item == null) continue;
                        Song s = new Song();
                        s.id = item.optString("FileHash", "");
                        s.hash = item.optString("FileHash", "");
                        s.title = item.optString("SongName", item.optString("FileName", ""));
                        s.artist = item.optString("SingerName", "");
                        s.album = item.optString("AlbumName", "");
                        s.duration = item.optInt("Duration", 0);
                        s.cover = item.optString("Image", "");
                        s.sqHash = item.optString("SQFileHash", "");
                        s.hash320 = item.optString("HQFileHash", "");
                        s.originHash = item.optString("ResFileHash", "");
                        s.albumId = item.optString("AlbumID", "");
                        s.albumAudioId = "0";
                        s.platform = 0;
                        songs.add(s);
                    }
                }

                int pageSize = 20;
                boolean hasNext = page * pageSize < total;
                boolean hasPrev = page > 1;

                final List<Song> finalSongs = songs;
                final int finalTotal = total;
                final boolean fHasNext = hasNext;
                final boolean fHasPrev = hasPrev;
                sHandler.post(() -> cb.onResult(finalSongs, finalTotal, fHasPrev, fHasNext));
            } catch (Exception e) {
                Log.e(TAG, "KG search exception", e);
                postError(cb, e.getMessage() != null && e.getMessage().contains("受") ? e.getMessage() : "音源访问受限，请稍后重试");
            }
        });
    }

    private static String truncated(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    public static void getKugouPlayUrl(String hash, PlayUrlCallback cb) {
        getKugouPlayUrl(hash, "exhigh", cb);
    }

    public static void getKugouPlayUrl(String hash, String level, PlayUrlCallback cb) {
        sExecutor.execute(() -> {
            MusicLog.i(TAG, "getKugouPlayUrl hash=" + hash + " level=" + level);
            if (hash == null || hash.trim().isEmpty()) {
                postError(cb, "歌曲 hash 为空");
                return;
            }
            String safeLevel = level == null || level.trim().isEmpty() ? "exhigh" : level;
            String playUrl = fetchKugouFallback(hash, safeLevel);
            if (playUrl != null && !playUrl.isEmpty()) {
                MusicLog.i(TAG, "getKugouPlayUrl OK: " + playUrl.substring(0, Math.min(80, playUrl.length())));
                final String url = playUrl;
                sHandler.post(() -> cb.onUrl(url));
                return;
            }
            MusicLog.e(TAG, "Kugou play failed for hash=" + hash);
            postError(cb, "获取音源失败");
        });
    }

    private static String fetchKugouFallback(String hash, String level) {
        try {
            if (hash == null || hash.trim().isEmpty()) return "";
            if (level == null || level.trim().isEmpty()) level = "exhigh";
            String url = KG_PLAY_FALLBACK + "?id=" + hash + "&type=json&level=" + level;
            String resp = httpGet(url, "https://music.haitangw.cc");
            JSONObject json = new JSONObject(resp);
            JSONObject data = json.optJSONObject("data");
            String playUrl = data != null ? data.optString("url", "") : "";
            if (playUrl.isEmpty() || "null".equals(playUrl)) return "";
            return normalizePlayUrl(playUrl);
        } catch (Exception e) {
            MusicLog.e(TAG, "fetchKugouFallback error", e);
            return "";
        }
    }

    public static void getKugouLyric(String hash, LyricCallback cb) {
        getKugouLyric(hash, "", 0, cb);
    }

    public static void getKugouLyric(String hash, String title, int duration, LyricCallback cb) {
        if (cb == null) return;
        if (hash == null || hash.isEmpty()) {
            sHandler.post(() -> cb.onLyric(""));
            return;
        }
        sExecutor.execute(() -> {
            try {
                String safeTitle = title != null ? URLEncoder.encode(title, "UTF-8") : "";
                String searchUrl = LYRICS_SEARCH + "?ver=1&man=yes&client=pc&keyword="
                    + safeTitle + "&hash=" + hash + "&timelength=" + duration;
                String searchResp = httpGetWithHeaders(searchUrl, "https://lyrics.kugou.com",
                    new String[][]{
                        {"User-Agent", LYRICS_UA},
                        {"KG-RC", "1"},
                        {"KG-THash", "expand_search_manager.cpp:852736169:451"}
                    });
                JSONObject searchJson = new JSONObject(searchResp);
                JSONArray candidates = searchJson.optJSONArray("candidates");
                if (candidates == null || candidates.length() == 0) {
                    sHandler.post(() -> cb.onLyric(""));
                    return;
                }
                JSONObject first = candidates.optJSONObject(0);
                if (first == null) {
                    sHandler.post(() -> cb.onLyric(""));
                    return;
                }
                String lyricId = first.optString("id", "");
                String accessKey = first.optString("accesskey", first.optString("accessKey", ""));
                if (lyricId.isEmpty() || accessKey.isEmpty()) {
                    sHandler.post(() -> cb.onLyric(""));
                    return;
                }
                String dlUrl = LYRICS_DOWNLOAD + "?ver=1&client=pc&id=" + lyricId
                    + "&accesskey=" + accessKey + "&fmt=lrc&charset=utf8";
                String dlResp = httpGetWithHeaders(dlUrl, "https://lyrics.kugou.com",
                    new String[][]{
                        {"User-Agent", LYRICS_UA},
                        {"KG-RC", "1"},
                        {"KG-THash", "expand_search_manager.cpp:852736169:451"}
                    });
                JSONObject dlJson = new JSONObject(dlResp);
                String content = dlJson.optString("content", "");
                if (!content.isEmpty()) {
                    String lrc = new String(android.util.Base64.decode(content, android.util.Base64.DEFAULT), "UTF-8");
                    sHandler.post(() -> cb.onLyric(lrc));
                } else {
                    sHandler.post(() -> cb.onLyric(""));
                }
            } catch (Exception e) {
                Log.e(TAG, "KG lyric error", e);
                sHandler.post(() -> cb.onLyric(""));
            }
        });
    }

    // ===== Album/Search extensions =====

    public static void searchKugouAlbum(String query, int page, SearchCallback cb) {
        MusicLog.i(TAG, "searchKugouAlbum q=" + query + " page=" + page);
        sExecutor.execute(() -> {
            try {
                String urlStr = KG_SEARCH_ALBUM + "?version=9024&iscorrection=1&highlight=em&plat=0"
                    + "&keyword=" + URLEncoder.encode(query, "UTF-8")
                    + "&pagesize=20&page=" + page + "&sver=2&with_res_tag=0";
                String resp = httpGet(urlStr, "https://msearch.kugou.com");
                JSONObject json = new JSONObject(resp);
                JSONObject data = json.optJSONObject("data");
                if (data == null) { postError(cb, "无搜索结果"); return; }

                JSONArray info = data.optJSONArray("info");
                int total = data.optInt("total", 0);
                List<Song> songs = new ArrayList<>();
                if (info != null) {
                    for (int i = 0; i < info.length(); i++) {
                        JSONObject item = info.optJSONObject(i);
                        if (item == null) continue;
                        Song s = new Song();
                        s.id = item.optString("albumid", "");
                        s.title = item.optString("albumname", "");
                        s.artist = item.optString("singername", "");
                        s.cover = item.optString("imgurl", "").replace("{size}", "400");
                        s.platform = 0;
                        s.albumId = item.optString("albumid", "");
                        s.album = item.optString("albumname", "");
                        songs.add(s);
                    }
                }
                int pageSize = 20;
                boolean hasNext = page * pageSize < total;
                boolean hasPrev = page > 1;
                final List<Song> finalSongs = songs;
                final int finalTotal = total;
                final boolean fHasNext = hasNext;
                final boolean fHasPrev = hasPrev;
                sHandler.post(() -> cb.onResult(finalSongs, finalTotal, fHasPrev, fHasNext));
            } catch (Exception e) {
                Log.e(TAG, "KG album search error", e);
                postError(cb, "专辑搜索失败: " + e.getMessage());
            }
        });
    }

    public static void searchKugouSheet(String query, int page, SearchCallback cb) {
        MusicLog.i(TAG, "searchKugouSheet q=" + query + " page=" + page);
        sExecutor.execute(() -> {
            try {
                String urlStr = KG_SEARCH_SHEET + "?format=json"
                    + "&keyword=" + URLEncoder.encode(query, "UTF-8")
                    + "&page=" + page + "&pagesize=20&showtype=1";
                String resp = httpGet(urlStr, "http://mobilecdn.kugou.com");
                JSONObject json = new JSONObject(resp);
                JSONObject data = json.optJSONObject("data");
                if (data == null) { postError(cb, "无搜索结果"); return; }

                JSONArray info = data.optJSONArray("info");
                int total = data.optInt("total", 0);
                List<Song> songs = new ArrayList<>();
                if (info != null) {
                    for (int i = 0; i < info.length(); i++) {
                        JSONObject item = info.optJSONObject(i);
                        if (item == null) continue;
                        Song s = new Song();
                        s.id = item.optString("specialid", "");
                        s.title = item.optString("specialname", "");
                        s.artist = item.optString("nickname", "");
                        s.cover = item.optString("imgurl", "");
                        s.platform = 0;
                        s.sqHash = item.optString("specialid", "");
                        songs.add(s);
                    }
                }
                int pageSize = 20;
                boolean hasNext = page * pageSize < total;
                boolean hasPrev = page > 1;
                final List<Song> finalSongs = songs;
                final int finalTotal = total;
                final boolean fHasNext = hasNext;
                final boolean fHasPrev = hasPrev;
                sHandler.post(() -> cb.onResult(finalSongs, finalTotal, fHasPrev, fHasNext));
            } catch (Exception e) {
                Log.e(TAG, "KG sheet search error", e);
                postError(cb, "歌单搜索失败: " + e.getMessage());
            }
        });
    }

    // ===== HTTP helpers =====

    private static String httpGetWithHeaders(String urlStr, String referer, String[][] extraHeaders) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", FULL_UA);
        conn.setRequestProperty("Referer", referer);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Encoding", "gzip, deflate");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        conn.setInstanceFollowRedirects(true);
        if (extraHeaders != null) {
            for (String[] h : extraHeaders) {
                conn.setRequestProperty(h[0], h[1]);
            }
        }
        String raw;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            raw = sb.toString();
        } finally {
            conn.disconnect();
        }
        Log.e(TAG, "GET(2) url=" + urlStr);
        return ensureJson(raw, urlStr);
    }

    private static String httpGet(String urlStr, String referer) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", FULL_UA);
        conn.setRequestProperty("Referer", referer);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Encoding", "gzip, deflate");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        conn.setInstanceFollowRedirects(true);

        String raw;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            raw = sb.toString();
        } finally {
            conn.disconnect();
        }
        Log.e(TAG, "GET url=" + urlStr);
        return ensureJson(raw, urlStr);
    }

    private static String ensureJson(String raw, String urlForLog) throws Exception {
        String trimmed = raw != null ? raw.trim() : "";
        Log.e(TAG, "API resp len=" + trimmed.length() + " url=" + urlForLog + " head=[" + truncated(trimmed, 300) + "]");
        if (trimmed.isEmpty()) return trimmed;
        char first = trimmed.charAt(0);
        if (first != '{' && first != '[') {
            Log.e(TAG, "API non-JSON url=" + urlForLog + " raw=[" + truncated(trimmed, 500) + "]");
            throw new Exception("\u97F3\u6E90\u8BBF\u95EE\u53D7\u9650\uFF0C\u8BF7\u7A0D\u540E\u91CD\u8BD5");
        }
        return trimmed;
    }

    private static String normalizePlayUrl(String url) {
        if (url == null || url.isEmpty() || "null".equals(url)) return "";
        String u = url.trim();
        if (u.startsWith("//")) u = "https:" + u;
        else if (u.startsWith("http://")) u = "https://" + u.substring(7);
        return u;
    }

    private static void postError(SearchCallback cb, String msg) {
        sHandler.post(() -> cb.onError(msg));
    }

    private static void postError(PlayUrlCallback cb, String msg) {
        sHandler.post(() -> cb.onError(msg));
    }

    private static void postError(LyricCallback cb, String msg) {
        sHandler.post(() -> cb.onError(msg));
    }
}
