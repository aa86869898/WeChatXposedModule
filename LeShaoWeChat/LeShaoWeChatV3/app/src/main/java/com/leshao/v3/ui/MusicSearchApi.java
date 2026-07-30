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
    private static final String KG_PLAY = "https://music.haitangw.cc/kgqq1/kg.php";
    private static final String KG_LYRIC = "https://wwwapi.kugou.com/yy/index.php?r=play/getdata&hash=";

    public static void searchKugou(String query, int page, SearchCallback cb) {
        sExecutor.execute(() -> {
            try {
                String urlStr = KG_SEARCH + "?keyword=" + URLEncoder.encode(query, "UTF-8")
                    + "&page=" + page + "&pagesize=20&userid=0&clientver=&platform=WebFilter"
                    + "&filter=2&iscorrection=1&privilege_filter=0&area_code=1";
                String resp = httpGet(urlStr, "https://songsearch.kugou.com");
                JSONObject json = new JSONObject(resp);
                JSONObject data = json.optJSONObject("data");
                if (data == null) { postError(cb, "无搜索结果"); return; }

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
            try {
                String url = KG_PLAY + "?id=" + hash + "&type=json&level=" + level;
                String resp = httpGet(url, "https://music.haitangw.cc");
                JSONObject json = new JSONObject(resp);
                JSONObject data = json.optJSONObject("data");
                final String playUrl = data != null ? data.optString("url", "") : "";
                if (!playUrl.isEmpty()) {
                    sHandler.post(() -> cb.onUrl(playUrl));
                } else {
                    postError(cb, "无法获取酷狗播放链接");
                }
            } catch (Exception e) {
                Log.e(TAG, "KG play error", e);
                postError(cb, "获取播放链接失败: " + e.getMessage());
            }
        });
    }

    public static void getKugouLyric(String hash, LyricCallback cb) {
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

    // ===== KuWo =====
    private static final String KW_SEARCH = "https://search.kuwo.cn/r.s";
    private static final String KW_PLAY = "http://antiserver.kuwo.cn/anti.s";
    private static final String KW_LYRIC = "http://m.kuwo.cn/newh5/singles/songinfoandlrc";

    public static void searchKuwo(String query, int page, SearchCallback cb) {
        sExecutor.execute(() -> {
            try {
                int limit = 30;
                int pn = (page - 1) * limit;
                String urlStr = KW_SEARCH + "?all=" + URLEncoder.encode(query, "UTF-8")
                    + "&ft=music&pn=" + pn + "&rn=" + limit + "&rformat=json&encoding=utf8";
                String resp = httpGet(urlStr, "https://www.kuwo.cn");

                String parsed = resp.replace('\'', '"').replace("True", "true").replace("False", "false");
                JSONObject json = new JSONObject(parsed);
                JSONArray abslist = json.optJSONArray("abslist");
                int total = json.optInt("TOTAL", 0);

                List<Song> songs = new ArrayList<>();
                if (abslist != null) {
                    for (int i = 0; i < abslist.length(); i++) {
                        JSONObject item = abslist.getJSONObject(i);
                        Song s = new Song();
                        s.id = item.optString("MUSICRID", "");
                        s.hash = item.optString("MUSICRID", "");
                        s.title = item.optString("NAME", item.optString("SONGNAME", ""));
                        s.artist = item.optString("ARTIST", "");
                        s.album = item.optString("ALBUM", "");
                        s.duration = item.optInt("DURATION", 0);
                        s.cover = item.optString("PICPATH", "");
                        s.platform = 1;

                        String songId = s.id.replace("MUSIC_", "");
                        if (s.cover.isEmpty() || s.cover.equals("")) {
                            String artistPic = item.optString("web_artistpic_short", "");
                            if (artistPic != null && !artistPic.isEmpty() && !artistPic.equals("")) {
                                s.cover = "https://img4.kuwo.cn/star/starhead/" + artistPic;
                            }
                        }
                        if (s.cover.isEmpty() || s.cover.equals("")) {
                            String albumPic = item.optString("web_albumpic_short", "");
                            if (albumPic != null && !albumPic.isEmpty() && !albumPic.equals("")) {
                                s.cover = "https://img4.kuwo.cn/star/albumcover/" + albumPic;
                            }
                        }
                        if (s.cover.isEmpty() || s.cover.equals("")) {
                            int idLen = songId.length();
                            if (idLen >= 2) {
                                s.cover = "https://img4.kuwo.cn/star/albumcover/120/" + songId.substring(idLen - 2) + "/" + songId + "/" + songId + ".jpg";
                            }
                        }
                        if (!s.cover.startsWith("http") && !s.cover.isEmpty()) {
                            s.cover = "https:" + s.cover;
                        }
                        songs.add(s);
                    }
                }

                boolean hasNext = (pn + (abslist != null ? abslist.length() : 0)) < total;
                boolean hasPrev = page > 1;

                final List<Song> finalSongs = songs;
                final int finalTotal = total;
                final boolean fHasNext = hasNext;
                final boolean fHasPrev = hasPrev;
                sHandler.post(() -> cb.onResult(finalSongs, finalTotal, fHasPrev, fHasNext));
            } catch (Exception e) {
                Log.e(TAG, "KW search error", e);
                postError(cb, "酷我搜索失败: " + e.getMessage());
            }
        });
    }

    public static void getKuwoPlayUrl(String musicrid, PlayUrlCallback cb) {
        getKuwoPlayUrl(musicrid, "mp3", cb);
    }

    public static void getKuwoPlayUrl(String musicrid, String format, PlayUrlCallback cb) {
        sExecutor.execute(() -> {
            try {
                String urlStr = KW_PLAY + "?type=convert_url&rid=" + musicrid + "&format=" + format + "&response=url";
                String resp = httpGet(urlStr, "https://www.kuwo.cn");
                final String playUrl = resp.trim();
                if (playUrl.startsWith("http")) {
                    sHandler.post(() -> cb.onUrl(playUrl));
                } else {
                    postError(cb, "无法获取酷我播放链接");
                }
            } catch (Exception e) {
                Log.e(TAG, "KW play error", e);
                postError(cb, "获取播放链接失败");
            }
        });
    }

    public static void getKuwoLyric(String musicrid, LyricCallback cb) {
        sExecutor.execute(() -> {
            try {
                String songId = musicrid.replace("MUSIC_", "");
                String url = KW_LYRIC + "?musicId=" + songId;
                String resp = httpGet(url, "http://m.kuwo.cn");
                JSONObject json = new JSONObject(resp);
                if (json.optInt("status") == 200) {
                    JSONObject data = json.optJSONObject("data");
                    if (data != null) {
                        JSONArray lrclist = data.optJSONArray("lrclist");
                        if (lrclist != null && lrclist.length() > 0) {
                            StringBuilder lrc = new StringBuilder();
                            for (int i = 0; i < lrclist.length(); i++) {
                                JSONObject item = lrclist.getJSONObject(i);
                                double time = item.optDouble("time", 0);
                                String text = item.optString("lineLyric", "");
                                int min = (int) (time / 60);
                                double sec = time % 60;
                                lrc.append(String.format("[%02d:%05.2f]%s\n", min, sec, text));
                            }
                            sHandler.post(() -> cb.onLyric(lrc.toString()));
                            return;
                        }
                    }
                }
                sHandler.post(() -> cb.onLyric(""));
            } catch (Exception e) {
                Log.e(TAG, "KW lyric error", e);
                sHandler.post(() -> cb.onLyric(""));
            }
        });
    }

    // ===== HTTP helpers =====

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
