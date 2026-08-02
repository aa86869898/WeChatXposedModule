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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 酷狗音乐 API 层 — 移植自 kg.js (ThomasBy2025/musicfree)
 * 支持: 搜索/排行/歌单/专辑/歌手/评论/歌词/播放
 */
public class KgApi {

    private static final String TAG = "KgApi";
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(6);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int PAGE_SIZE = 30;

    // ===== 常量 =====
    private static final String SIGN_KEY_1058 = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt";
    private static final String SIGN_KEY_URL = "OIlwieks28dk2k092lksi2UIkp";
    private static final String USER_ID = "440908392";
    private static final String TOKEN = "f7524337c1ae877929a1497cf3d5d37e5c4cb8073fc298e492a67babc376a9d4";
    private static final String APP_ID = "1005";

    // ===== 数据模型 =====

    public static class Song {
        public String id;
        public String hash;
        public String title;
        public String artist;
        public String album;
        public String cover;
        public int duration;
        public String playUrl;
        public String lyric;
        public String albumId;
        public String albumAudioId;
        public String sqHash;
        public String hash320;
        public String originHash;
    }

    public static class Playlist {
        public String id;
        public String title;
        public String cover;
        public String description;
        public int songCount;
    }

    public static class Ranking {
        public String id, title, cover;
        public int classify;
        public int songCount;
        public String updateFrequency;
    }

    public static class Album {
        public String id;
        public String title;
        public String cover;
        public String artist;
        public int songCount;
    }

    public static class Artist {
        public String id;
        public String name;
        public String avatar;
        public int songCount;
    }

    public static class Comment {
        public String id;
        public String nickName;
        public String avatar;
        public String content;
        public int likeCount;
        public long createAt;
    }

    // ===== 回调接口 =====

    public interface SongListCallback { void onResult(List<Song> songs, int total); }
    public interface PlaylistCallback { void onResult(List<Playlist> playlists); }
    public interface RankingCallback { void onResult(List<Ranking> list); void onError(String msg); }
    public interface PlaylistSongsCallback { void onResult(List<Song> songs, int total); void onError(String msg); }
    public interface StringCallback { void onResult(String data); void onError(String msg); }
    public interface SongCallback { void onResult(Song song); void onError(String msg); }
    public interface CommentsCallback { void onResult(List<Comment> comments, int total); void onError(String msg); }
    public interface AlbumsCallback { void onResult(List<Album> albums, int total); void onError(String msg); }
    public interface PlayUrlCallback { void onUrl(String url); void onError(String msg); }

    // ===== 搜索 =====

    public static void search(String keyword, int page, String type, SongListCallback cb) {
        String st;
        String sv;
        switch (type) {
            case "album":  st = "album";  sv = "v1"; break;
            case "sheet":  st = "special"; sv = "v1"; break;
            case "artist": st = "author";  sv = "v1"; break;
            case "lyric":  st = "lyric";   sv = "v1"; break;
            default:       st = "song";    sv = "v3"; break;
        }
        String url = "https://gateway.kugou.com/complexsearch/" + sv + "/search/" + st;
        String plat = "music".equals(type) ? "platform=WebFilter" : "searchsong=1";

        webSign(url, "keyword=" + keyword, plat, page, "data", new RawCallback() {
            public void onResult(JSONObject data) {
                try {
                    JSONArray lists = data.optJSONArray("lists");
                    List<Song> songs = new ArrayList<>();
                    if (lists != null) {
                        for (int i = 0; i < lists.length(); i++) {
                            JSONObject item = lists.getJSONObject(i);
                            Song s = new Song();
                            s.id = item.optString("audio_id", item.optString("id", ""));
                            s.hash = item.optString("hash", item.optString("FileHash", ""));
                            s.title = item.optString("songname", item.optString("SongName", item.optString("name", "")));
                            s.artist = item.optString("singername", item.optString("SingerName", item.optString("author_name", "")));
                            s.album = item.optString("album_name", item.optString("albumname", ""));
                            s.duration = item.optInt("duration", 0);
                            s.cover = item.optString("image", "");
                            s.albumId = item.optString("album_id", "");
                            s.albumAudioId = item.optString("album_audio_id", "");
                            songs.add(s);
                        }
                    }
                    if ("music".equals(type) && songs.size() > 0) {
                        enrichSongs(songs, enriched -> MAIN.post(() -> cb.onResult(enriched, data.optInt("total", 0))));
                    } else {
                        int total = data.optInt("total", 0);
                        final List<Song> f = songs;
                        final int ft = total;
                        MAIN.post(() -> cb.onResult(f, ft));
                    }
                } catch (Exception e) {
                    songListError(cb, e.getMessage());
                }
            }
            public void onError(String msg) { MAIN.post(() -> songListError(cb, msg)); }
        });
    }

    // ===== 排行榜 =====

    public static void getTopLists(RankingCallback cb) {
        EXEC.execute(() -> {
            try {
                String url = "http://mobilecdnbj.kugou.com/api/v3/rank/list?version=9108&plat=0&showtype=2&parentid=0&apiver=6&area_code=1&withsong=0&with_res_tag=0";
                String resp = httpGet(url, "https://m.kugou.com");
                JSONArray info = new JSONObject(resp).getJSONObject("data").getJSONArray("info");
                List<Ranking> list = new ArrayList<>();
                for (int i = 0; i < info.length(); i++) {
                    JSONObject item = info.getJSONObject(i);
                    Ranking r = new Ranking();
                    r.id = item.optString("rankid", "");
                    r.title = item.optString("rankname", "");
                    r.cover = item.optString("imgurl", "").replace("{size}", "480");
                    r.classify = item.optInt("classify", 0);
                    r.songCount = item.optInt("song_count", 0);
                    r.updateFrequency = item.optString("update_frequency", "");
                    list.add(r);
                }
                MAIN.post(() -> cb.onResult(list));
            } catch (Exception e) {
                MAIN.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    public static void getTopListDetail(String rankId, int page, PlaylistSongsCallback cb) {
        EXEC.execute(() -> {
            try {
                String url = "http://mobilecdnbj.kugou.com/api/v3/rank/song?version=9108&ranktype=0&plat=0&pagesize=" + PAGE_SIZE + "&area_code=1&page=" + page + "&volid=35050&rankid=" + rankId + "&with_res_tag=0";
                String resp = httpGet(url, "https://m.kugou.com");
                JSONObject data = new JSONObject(resp).getJSONObject("data");
                JSONArray info = data.getJSONArray("info");
                int total = data.optInt("total", 0);

                List<Song> songs = new ArrayList<>();
                List<String> hashes = new ArrayList<>();
                for (int i = 0; i < info.length(); i++) {
                    JSONObject item = info.getJSONObject(i);
                    Song s = new Song();
                    s.hash = item.optString("hash", "");
                    s.title = item.optString("filename", "").replace(".mp3", "");
                    s.artist = "";
                    s.duration = item.optInt("duration", 0);
                    songs.add(s);
                    hashes.add(s.hash);
                }
                enrichSongs(songs, enriched ->
                    MAIN.post(() -> cb.onResult(enriched, total)));
            } catch (Exception e) {
                MAIN.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    // ===== 歌单 =====

    public static void getPlaylistCategories(CategoryCallback cb) {
        EXEC.execute(() -> {
            try {
                String url = "http://www2.kugou.kugou.com/yueku/v9/special/getSpecial?is_smarty=1";
                String resp = httpGet(url, "https://www.kugou.com");
                JSONObject data = new JSONObject(resp).getJSONObject("data");
                List<Playlist> hot = new ArrayList<>();
                JSONArray hotTags = data.getJSONObject("hotTag").getJSONArray("data");
                for (int i = 0; i < hotTags.length(); i++) {
                    JSONObject t = hotTags.getJSONObject(i);
                    Playlist p = new Playlist();
                    p.id = t.optString("special_id", "");
                    p.title = t.optString("special_name", "");
                    hot.add(p);
                }
                MAIN.post(() -> cb.onResult(hot));
            } catch (Exception e) {
                MAIN.post(() -> callbackError(cb, e.getMessage()));
            }
        });
    }

    public interface CategoryCallback { void onResult(List<Playlist> hot); void onError(String msg); }

    public static void getPlaylistsByTag(String tagId, int page, PlaylistCallback cb) {
        EXEC.execute(() -> {
            try {
                String url;
                if (tagId == null || tagId.isEmpty() || " ".equals(tagId)) {
                    url = "http://www2.kugou.kugou.com/yueku/v9/special/getSpecial?is_ajax=1&cdn=cdn&t=5&pagesize=30&p=" + page;
                } else {
                    url = "http://www2.kugou.kugou.com/yueku/v9/special/getSpecial?is_ajax=1&cdn=cdn&t=5&pagesize=30&c=" + tagId.trim() + "&p=" + page;
                }
                String resp = httpGet(url, "https://www.kugou.com");
                JSONObject data = new JSONObject(resp).getJSONObject("data");
                JSONArray list = data.optJSONArray("special_db");

                List<Playlist> playlists = new ArrayList<>();
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject item = list.getJSONObject(i);
                        Playlist p = new Playlist();
                        p.id = item.optString("specialid", "");
                        p.title = item.optString("specialname", "");
                        p.cover = item.optString("img", "").replace("{size}", "480");
                        p.description = item.optString("intro", "");
                        p.songCount = item.optInt("songcount", 0);
                        playlists.add(p);
                    }
                }
                MAIN.post(() -> cb.onResult(playlists));
            } catch (Exception e) {
                MAIN.post(() -> callbackError(cb, e.getMessage()));
            }
        });
    }

    public static void getPlaylistDetail(String specialId, int page, PlaylistSongsCallback cb) {
        webSign("https://mobiles.kugou.com/api/v5/special/song_v2",
            "global_specialid=" + specialId, "specialid=" + specialId, page, "data", new RawCallback() {
            public void onResult(JSONObject data) {
                try {
                    JSONArray list = data.getJSONArray("info");
                    JSONObject info = data.optJSONObject("info");
                    int total = info != null ? info.optInt("songcount", list.length()) : list.length();

                    List<Song> songs = new ArrayList<>();
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject item = list.getJSONObject(i);
                        Song s = new Song();
                        s.hash = item.optString("hash", "");
                        s.title = item.optString("filename", "").replace(".mp3", "");
                        s.artist = "";
                        s.duration = item.optInt("duration", 0);
                        songs.add(s);
                    }
                    enrichSongs(songs, enriched ->
                        MAIN.post(() -> cb.onResult(enriched, total)));
                } catch (Exception e) {
                    MAIN.post(() -> cb.onError(e.getMessage()));
                }
            }
            public void onError(String msg) { MAIN.post(() -> cb.onError(msg)); }
        });
    }

    // ===== 歌曲详情 =====

    private static void enrichSongs(List<Song> songs, java.util.function.Consumer<List<Song>> cb) {
        if (songs.isEmpty()) { cb.accept(songs); return; }
        EXEC.execute(() -> {
            try {
                JSONArray resources = new JSONArray();
                for (Song s : songs) {
                    JSONObject res = new JSONObject();
                    res.put("id", 0);
                    res.put("type", "audio");
                    res.put("hash", s.hash);
                    resources.put(res);
                }
                JSONObject body = new JSONObject();
                body.put("relate", 1);
                body.put("userid", "2626431536");
                body.put("vip", 1);
                body.put("token", "");
                body.put("appid", 1001);
                body.put("behavior", "play");
                body.put("area_code", "1");
                body.put("clientver", "8990");
                body.put("need_hash_offset", 1);
                body.put("resource", resources);

                String resp = httpPost("https://gateway.kugou.com/v2/get_res_privilege/lite",
                    body.toString(), "https://m.kugou.com",
                    "Android712-AndroidPhone-11451-376-0-FeeCacheUpdate-wifi",
                    "media.store.kugou.com");

                    JSONArray data = new JSONObject(resp).optJSONArray("data");
                if (data != null) {
                    for (int i = 0; i < data.length() && i < songs.size(); i++) {
                        JSONObject d = data.getJSONObject(i);
                        Song s = songs.get(i);

                        String fname = d.optString("filename", d.optString("name", ""));
                        if (!fname.isEmpty()) {
                            int idx = fname.indexOf(" - ");
                            if (idx > 0) {
                                s.artist = fname.substring(0, idx);
                                s.title = fname.substring(idx + 3).replace(".mp3", "");
                            } else if (s.title == null || s.title.isEmpty()) {
                                s.title = fname.replace(".mp3", "");
                            }
                        }
                        if (s.title == null || s.title.isEmpty()) {
                            s.title = d.optString("songname", d.optString("name", ""));
                        }
                        if (s.artist == null || s.artist.isEmpty()) {
                            s.artist = d.optString("singername", d.optString("singer_name", ""));
                        }

                        s.album = d.optString("album_name", d.optString("albumname", s.album));
                        s.duration = d.optInt("duration", d.optInt("timelength", s.duration)) / 1000;

                        String cover = d.optString("album_sizable_cover", "");
                        if (cover.isEmpty()) {
                            JSONObject info = d.optJSONObject("info");
                            if (info != null) cover = info.optString("image", "");
                        }
                        s.cover = cover.replace("{size}", "480");

                        s.albumId = d.optString("album_id", "");
                        s.albumAudioId = d.optString("album_audio_id", "");
                        s.sqHash = d.optString("sqhash", "");
                        s.hash320 = d.optString("320hash", "");
                        s.originHash = d.optString("origin_hash", "");
                    }
                }
                cb.accept(songs);
            } catch (Exception e) {
                cb.accept(songs);
            }
        });
    }

    // ===== 播放链接 =====

    public static void getPlayUrl(Song song, String quality, PlayUrlCallback cb) {
        if (song == null) {
            MAIN.post(() -> cb.onError("歌曲数据为空"));
            return;
        }
        if (quality == null || quality.isEmpty()) quality = "standard";
        String hash = getQualityHash(song, quality);
        if (hash == null || hash.isEmpty()) {
            MAIN.post(() -> cb.onError("无此音质"));
            return;
        }
        hash = hash.toLowerCase();

        String br;
        switch (quality) {
            case "low":  br = "128"; break;
            case "standard": br = "320"; break;
            case "super": br = "high"; break;
            default: br = "128"; break;
        }
        String albumId = song.albumId != null ? song.albumId : "0";
        String aaId = song.albumAudioId != null ? song.albumAudioId : "0";
        String mid = randomHex(32);

        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            String keyHash = md5(hash + "57ae12eb6890223e355ccfcb74edf70d" + APP_ID + mid + USER_ID);

            String[] params = {
                "quality=" + br,
                "hash=" + hash,
                "mid=" + mid,
                "appid=" + APP_ID,
                "userid=" + USER_ID,
                "key=" + keyHash,
                "album_id=" + albumId,
                "album_audio_id=" + aaId,
                "clienttime=" + (System.currentTimeMillis() / 1000),
                "token=" + TOKEN,
                "area_code=1",
                "module=",
                "ssa_flag=is_fromtrack",
                "clientver=12029",
                "vipType=6",
                "ptype=0",
                "auth=",
                "mtype=0",
                "behavior=play",
                "pid=2",
                "dfid=-",
                "pidversion=3001",
                "secret=" + randomHex(32),
            };

            Arrays.sort(params);
            String joined = String.join("&", params);
            String noSep = String.join("", params);
            String sig = md5(SIGN_KEY_URL + noSep + SIGN_KEY_URL);
            String fullUrl = "https://gateway.kugou.com/v5/url?" + joined + "&signature=" + sig;

            String resp = getPlayUrlRaw(fullUrl);
            JSONObject body = new JSONObject(resp);
            if (body.optInt("status") == 1) {
                JSONArray urls = body.optJSONArray("url");
                if (urls != null && urls.length() > 0) {
                    String url = urls.optString(0, "");
                    if (!url.isEmpty()) {
                        MAIN.post(() -> cb.onUrl(url));
                        return;
                    }
                }
            }
            fallbackPlayUrl(song, quality, cb, 0);
        } catch (Exception e) {
            fallbackPlayUrl(song, quality, cb, 0);
        }
    }

    private static void fallbackPlayUrl(Song song, String quality, PlayUrlCallback cb, int level) {
        final String fq = (quality == null || quality.isEmpty()) ? "standard" : quality;
        EXEC.execute(() -> {
            try {
                if (song == null) {
                    MAIN.post(() -> cb.onError("歌曲数据为空"));
                    return;
                }
                String hash = song.hash != null ? song.hash : song.id;
                if (hash == null || hash.isEmpty()) {
                    MAIN.post(() -> cb.onError("歌曲 hash 为空"));
                    return;
                }
                String lxq;
                switch (fq) {
                    case "low": lxq = "128k"; break;
                    case "standard": lxq = "320k"; break;
                    case "super": lxq = "flac"; break;
                    default: lxq = "128k"; break;
                }
                String url;
                if (level == 0) {
                    url = "https://api.ikunshare.com/url?source=kg&songId=" + hash + "&quality=" + lxq;
                    String r = httpGet(url, "https://api.ikunshare.com", "lx-music-mobile/2.0.0", null);
                    JSONObject j = new JSONObject(r);
                    String pu = j.optString("url", "");
                    if (!pu.isEmpty()) { MAIN.post(() -> cb.onUrl(pu)); return; }
                }
                if (level <= 1) {
                    String lv;
                    switch (fq) {
                        case "low": lv = "standard"; break;
                        case "standard": lv = "exhigh"; break;
                        case "super": lv = "lossless"; break;
                        default: lv = "exhigh"; break;
                    }
                    url = "https://musicapi.haitangw.net/music/kg_song_kw.php?id=" + hash + "&type=json&level=" + lv;
                    String r = httpGet(url, "https://musicapi.haitangw.net");
                    JSONObject j = new JSONObject(r);
                    JSONObject d = j.optJSONObject("data");
                    if (d != null) {
                        String pu = d.optString("url", "");
                        if (!pu.isEmpty()) { MAIN.post(() -> cb.onUrl(pu)); return; }
                    }
                }
                MAIN.post(() -> cb.onError("所有音源解析失败"));
            } catch (Exception e) {
                MAIN.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    private static String getQualityHash(Song song, String quality) {
        if (song == null) return "";
        if (quality == null || quality.isEmpty()) quality = "standard";
        switch (quality) {
            case "low": return song.hash;
            case "standard": return song.hash320 != null ? song.hash320 : song.hash;
            case "super": return song.originHash != null ? song.originHash : (song.sqHash != null ? song.sqHash : song.hash);
            default: return song.hash;
        }
    }

    // ===== 歌词 =====

    public static void getLyric(String hash, StringCallback cb) {
        EXEC.execute(() -> {
            try {
                String url = "http://m.kugou.com/app/i/krc.php?cmd=100&timelength=999999&hash=" + hash;
                String r = httpGet(url, "https://m.kugou.com");
                MAIN.post(() -> cb.onResult(r));
            } catch (Exception e) {
                MAIN.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    // ===== 评论 =====

    public static void getComments(String hash, int page, CommentsCallback cb) {
        webSign("http://m.comment.service.kugou.com/r/v1/rank/topliked",
            "extdata=" + hash, "schash=" + hash, page, "list",
            new RawCallback() {
                public void onResult(JSONObject data) {
                    try {
                        JSONArray list = data.optJSONArray("list");
                        if (list == null) {
                            JSONArray arr = data.optJSONArray("data");
                            list = arr != null ? arr : new JSONArray();
                        }
                        List<Comment> comments = new ArrayList<>();
                        for (int i = 0; i < list.length(); i++) {
                            JSONObject item = list.getJSONObject(i);
                            Comment c = new Comment();
                            c.id = item.optString("id", "");
                            c.nickName = item.optString("user_name", "");
                            c.avatar = item.optString("user_pic", "");
                            c.content = item.optString("content", "");
                            c.likeCount = item.optJSONObject("like") != null ? item.optJSONObject("like").optInt("likenum", 0) : 0;
                            c.createAt = item.optLong("addtime", 0);
                            comments.add(c);
                        }
                        MAIN.post(() -> cb.onResult(comments, comments.size()));
                    } catch (Exception e) {
                        MAIN.post(() -> cb.onError(e.getMessage()));
                    }
                }
                public void onError(String msg) { MAIN.post(() -> cb.onError(msg)); }
            });
    }

    // ===== 专辑 =====

    public static void getAlbumInfo(String albumId, int page, PlaylistSongsCallback cb) {
        EXEC.execute(() -> {
            try {
                String mid = String.valueOf(System.currentTimeMillis());
                String[] params = {
                    "albumid=" + albumId, "version=1000", "plat=5",
                    "dfid=-", "mid=" + mid, "uuid=" + mid,
                    "appid=1058", "srcappid=2919", "clientver=1000",
                    "clienttime=" + mid, "pagesize=" + PAGE_SIZE, "page=" + page,
                    "userid=" + USER_ID, "token=" + TOKEN
                };
                Arrays.sort(params);
                String joined = String.join("&", params);
                String sig = md5(SIGN_KEY_1058 + joined + SIGN_KEY_1058);
                String url = "https://m3ws.kugou.com/api/v1/album/info?" + joined + "&signature=" + sig;

                String resp = httpGet(url, "https://m.kugou.com",
                    "Android712-AndroidPhone-10518-18-0-NetMusic-wifi", null);
                JSONObject data = new JSONObject(resp).getJSONObject("data");
                JSONArray list = data.getJSONArray("list");
                int total = data.optInt("songcount", list.length());

                List<Song> songs = new ArrayList<>();
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.getJSONObject(i);
                    Song s = new Song();
                    s.hash = item.optString("hash", "");
                    s.title = item.optString("filename", "").replace(".mp3", "");
                    s.artist = "";
                    s.duration = item.optInt("duration", 0);
                    songs.add(s);
                }
                enrichSongs(songs, enriched ->
                    MAIN.post(() -> cb.onResult(enriched, total)));
            } catch (Exception e) {
                MAIN.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    // ===== 推荐歌单 =====

    public static void getRecommendedPlaylists(PlaylistCallback cb) {
        getPlaylistsByTag("", 1, cb);
    }

    // ===== 热门歌手 =====

    public static void getHotArtists(PlaylistCallback cb) {
        EXEC.execute(() -> {
            try {
                String url = "http://mobilecdnbj.kugou.com/api/v3/singer/list?version=9108&plat=0&pagesize=20&sextype=1&area=0&type=1&page=1";
                String resp = httpGet(url, "https://m.kugou.com");
                JSONObject data = new JSONObject(resp).getJSONObject("data");
                JSONArray info = data.getJSONArray("info");
                List<Playlist> artists = new ArrayList<>();
                for (int i = 0; i < Math.min(10, info.length()); i++) {
                    JSONObject item = info.getJSONObject(i);
                    Playlist p = new Playlist();
                    p.id = item.optString("singerid", "");
                    p.title = item.optString("singername", "");
                    p.cover = item.optString("imgurl", "").replace("{size}", "480");
                    p.songCount = item.optInt("songcount", 0);
                    artists.add(p);
                }
                MAIN.post(() -> cb.onResult(artists));
            } catch (Exception e) {
                MAIN.post(() -> callbackError(cb, e.getMessage()));
            }
        });
    }

    // ===== 歌手 =====

    public static void getArtistSongs(String artistId, int page, SongListCallback cb) {
        webSign("https://gateway.kugou.com/openapi/kmr/v1/author/audios",
            "author_id=" + artistId, "", page, "data", new RawCallback() {
                public void onResult(JSONObject data) {
                    try {
                        JSONArray songsArr = data.optJSONArray("songs");
                        List<Song> songs = new ArrayList<>();
                        if (songsArr != null) {
                            for (int i = 0; i < songsArr.length(); i++) {
                                JSONObject info = songsArr.getJSONObject(i).optJSONObject("audio_info");
                                if (info != null) {
                                    Song s = new Song();
                                    s.hash = info.optString("hash", "");
                                    s.title = info.optString("filename", "").replace(".mp3", "");
                                    s.artist = "";
                                    s.duration = info.optInt("duration", 0);
                                    songs.add(s);
                                }
                            }
                        }
                        enrichSongs(songs, enriched ->
                            MAIN.post(() -> cb.onResult(enriched, data.optInt("total", 0))));
                    } catch (Exception e) {
                        MAIN.post(() -> songListError(cb, e.getMessage()));
                    }
                }
                public void onError(String msg) { MAIN.post(() -> songListError(cb, msg)); }
            });
    }

    public static void getArtistAlbums(String artistId, int page, AlbumsCallback cb) {
        EXEC.execute(() -> {
            try {
                String url = "http://mobilecdnbj.kugou.com/api/v3/singer/album?version=9108&plat=0&pagesize=" + PAGE_SIZE + "&page=" + page + "&singerid=" + artistId;
                String resp = httpGet(url, "https://m.kugou.com");
                JSONObject data = new JSONObject(resp).getJSONObject("data");
                JSONArray info = data.getJSONArray("info");
                int total = data.optInt("total", 0);

                List<Album> albums = new ArrayList<>();
                for (int i = 0; i < info.length(); i++) {
                    JSONObject item = info.getJSONObject(i);
                    Album a = new Album();
                    a.id = item.optString("albumid", "");
                    a.title = item.optString("albumname", "");
                    a.cover = item.optString("img", "").replace("{size}", "480");
                    a.artist = item.optString("singername", "");
                    a.songCount = item.optInt("songcount", 0);
                    albums.add(a);
                }
                MAIN.post(() -> cb.onResult(albums, total));
            } catch (Exception e) {
                MAIN.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    // ===== 内部工具 =====

    private interface RawCallback {
        void onResult(JSONObject data);
        void onError(String msg);
    }

    private static void webSign(String baseUrl, String param1, String param2, int page, String path, RawCallback cb) {
        EXEC.execute(() -> {
            try {
                String mid = String.valueOf(System.currentTimeMillis());
                List<String> params = new ArrayList<>(Arrays.asList(
                    "dfid=-", "mid=" + mid, "uuid=" + mid,
                    "appid=1058", "srcappid=2919", "clientver=1000",
                    "clienttime=" + mid, "pagesize=" + PAGE_SIZE, "page=" + page,
                    "userid=" + USER_ID, "token=" + TOKEN
                ));
                if (!param1.isEmpty()) params.add(param1);
                if (!param2.isEmpty()) params.add(param2);

                String[] arr = params.toArray(new String[0]);
                Arrays.sort(arr);
                String joined = String.join("&", arr);
                String noSep = String.join("", arr);
                String sig = md5(SIGN_KEY_1058 + noSep + SIGN_KEY_1058);
                String fullUrl = baseUrl + "?" + joined + "&signature=" + sig;

                String resp = httpGet(fullUrl, "https://m.kugou.com",
                    "Android712-AndroidPhone-10518-18-0-NetMusic-wifi", null);
                JSONObject root = new JSONObject(resp);
                JSONObject data = root.optJSONObject(path);
                if (data != null) {
                    MAIN.post(() -> cb.onResult(data));
                } else {
                    MAIN.post(() -> cb.onError("无数据"));
                }
            } catch (Exception e) {
                MAIN.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    // ===== HTTP =====

    private static String httpGet(String urlStr, String referer) throws Exception {
        return httpGet(urlStr, referer, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", null);
    }

    private static String httpGet(String urlStr, String referer, String ua, String router) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", ua);
        conn.setRequestProperty("Referer", referer);
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setRequestProperty("KG-THash", "3e5ec6b");
        conn.setRequestProperty("KG-RC", "1");
        conn.setRequestProperty("KG-RF", "00869891");
        if (router != null) conn.setRequestProperty("x-router", router);
        conn.setInstanceFollowRedirects(true);

        return readResponse(conn);
    }

    private static String httpPost(String urlStr, String body, String referer, String ua, String router) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", ua);
        conn.setRequestProperty("Referer", referer);
        conn.setRequestProperty("KG-THash", "13a3164");
        conn.setRequestProperty("KG-RC", "1");
        conn.setRequestProperty("KG-Fake", "0");
        conn.setRequestProperty("KG-RF", "00869891");
        if (router != null) conn.setRequestProperty("x-router", router);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes("UTF-8"));
            os.flush();
        }
        return readResponse(conn);
    }

    private static String readResponse(HttpURLConnection conn) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private static String getPlayUrlRaw(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "Android800-AndroidPhone-12029-56-0-starlive-ctnet(13)");
        conn.setRequestProperty("Referer", "https://m.kugou.com");
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setRequestProperty("KG-THash", "595ff94");
        conn.setRequestProperty("KG-FAKE", USER_ID);
        conn.setRequestProperty("KG-Rec", "1");
        conn.setRequestProperty("KG-RC", "1");
        conn.setRequestProperty("x-router", "tracker.kugou.com");
        conn.setInstanceFollowRedirects(true);
        return readResponse(conn);
    }

    // ===== 工具 =====

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String randomHex(int len) {
        String chars = "1234567890abcdef";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(chars.charAt((int) (Math.random() * chars.length())));
        return sb.toString();
    }

    private static void songListError(SongListCallback cb, String msg) {
        Log.e(TAG, msg);
        MAIN.post(() -> cb.onResult(new ArrayList<>(), 0));
    }

    private static void callbackError(Object cb, String msg) {
        Log.e(TAG, msg);
        if (cb instanceof PlaylistCallback) MAIN.post(() -> postEmptyPlaylists((PlaylistCallback) cb));
        else if (cb instanceof StringCallback) MAIN.post(() -> ((StringCallback) cb).onError(msg));
        else if (cb instanceof PlayUrlCallback) MAIN.post(() -> ((PlayUrlCallback) cb).onError(msg));
        else if (cb instanceof CategoryCallback) MAIN.post(() -> ((CategoryCallback) cb).onError(msg));
        else if (cb instanceof AlbumsCallback) MAIN.post(() -> ((AlbumsCallback) cb).onError(msg));
        else if (cb instanceof CommentsCallback) MAIN.post(() -> ((CommentsCallback) cb).onError(msg));
        else if (cb instanceof PlaylistSongsCallback) MAIN.post(() -> ((PlaylistSongsCallback) cb).onError(msg));
        else if (cb instanceof RankingCallback) MAIN.post(() -> ((RankingCallback) cb).onError(msg));
    }

    private static void postEmptyPlaylists(PlaylistCallback cb) { MAIN.post(() -> cb.onResult(new ArrayList<>())); }
}
