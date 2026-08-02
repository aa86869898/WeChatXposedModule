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
    private static final String KG_PLAY = "https://wwwapi.kugou.com/yy/index.php?r=play/getdata&hash=";
    private static final String KG_PLAY_FALLBACK = "https://music.haitangw.cc/kgqq1/kg.php";
    private static final String KG_LYRIC = "https://wwwapi.kugou.com/yy/index.php?r=play/getdata&hash=";

    public static void searchKugou(String query, int page, SearchCallback cb) {
        MusicLog.i(TAG, "searchKugou q=" + query + " page=" + page);
        sExecutor.execute(() -> {
            try {
                String urlStr = KG_SEARCH + "?keyword=" + URLEncoder.encode(query, "UTF-8")
                    + "&page=" + page + "&pagesize=20&userid=0&clientver=&platform=WebFilter"
                    + "&filter=2&iscorrection=1&privilege_filter=0&area_code=1";
                String resp = httpGet(urlStr, "https://songsearch.kugou.com");
                JSONObject json = new JSONObject(resp);
                JSONObject data = json.optJSONObject("data");
                if (data == null) { MusicLog.e(TAG, "searchKugou: no data"); postError(cb, "无搜索结果"); return; }

                JSONArray lists = data.optJSONArray("lists");
                JSONObject info = data.optJSONObject("info");
                int total = info != null ? info.optInt("total", 0) : 0;

                List<Song> songs = new ArrayList<>();
                if (lists != null) {
                    for (int i = 0; i < lists.length(); i++) {
                        JSONObject item = lists.getJSONObject(i);
                        Song s = new Song();
                        s.id = item.optString("FileHash", "");
                        s.hash = item.optString("FileHash", "");
                        s.title = item.optString("SongName", item.optString("FileName", ""));
                        s.artist = item.optString("SingerName", "");
                        s.album = item.optString("AlbumName", "");
                        s.duration = item.optInt("Duration", 0);
                        s.cover = item.optString("Image", "");
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
                Log.e(TAG, "KG search error", e);
                postError(cb, "酷狗搜索失败: " + e.getMessage());
            }
        });
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
            // Primary: official Kugou API
            String playUrl = fetchKugouOfficial(hash);
            if (playUrl != null && !playUrl.isEmpty()) {
                MusicLog.i(TAG, "Kugou official OK: " + playUrl.substring(0, Math.min(80, playUrl.length())));
                final String url = playUrl;
                sHandler.post(() -> cb.onUrl(url));
                return;
            }
            // Fallback: third-party proxy
            MusicLog.i(TAG, "Kugou official failed, trying fallback...");
            playUrl = fetchKugouFallback(hash, safeLevel);
            if (playUrl != null && !playUrl.isEmpty()) {
                MusicLog.i(TAG, "Kugou fallback OK: " + playUrl.substring(0, Math.min(80, playUrl.length())));
                final String url = playUrl;
                sHandler.post(() -> cb.onUrl(url));
                return;
            }
            MusicLog.e(TAG, "All Kugou play sources failed for hash=" + hash);
            postError(cb, "获取音源失败");
        });
    }

    private static String fetchKugouOfficial(String hash) {
        try {
            if (hash == null || hash.trim().isEmpty()) return "";
            String url = KG_PLAY + hash;
            String resp = httpGet(url, "https://wwwapi.kugou.com");
            JSONObject json = new JSONObject(resp);
            JSONObject data = json.optJSONObject("data");
            if (data == null) return "";
            String playUrl = data.optString("play_url", data.optString("play_backup_url", ""));
            if (playUrl.isEmpty() || "null".equals(playUrl)) return "";
            return normalizePlayUrl(playUrl);
        } catch (Exception e) {
            MusicLog.e(TAG, "fetchKugouOfficial error", e);
            return "";
        }
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
        if (cb == null) return;
        if (hash == null || hash.isEmpty()) {
            sHandler.post(() -> cb.onLyric(""));
            return;
        }
        sExecutor.execute(() -> {
            try {
                String url = KG_LYRIC + hash;
                String resp = httpGet(url, "https://wwwapi.kugou.com");
                JSONObject json = new JSONObject(resp);
                JSONObject data = json.optJSONObject("data");
                if (data != null) {
                    String lrc = data.optString("lyrics", data.optString("lyric", ""));
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

    // ===== HTTP helpers =====

    private static String normalizePlayUrl(String url) {
        if (url == null || url.isEmpty() || "null".equals(url)) return "";
        String u = url.trim();
        if (u.startsWith("//")) u = "https:" + u;
        else if (u.startsWith("http://")) u = "https://" + u.substring(7);
        return u;
    }

    private static String httpGet(String urlStr, String referer) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        conn.setRequestProperty("Referer", referer);
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setInstanceFollowRedirects(true);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
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
