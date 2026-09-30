package com.leshao.v3.music;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 酷我音源客户端（第三方公益中转）。
 *
 * <p>元数据走酷我官方接口（https），直链走第三方中转 {@code music.nxinxz.com/kw.php}，
 * 由中转 302 跳转到酷我 CDN。支持 128K / 320K / 无损 FLAC 三档。</p>
 *
 * <p>注意：仅对非版权受限曲目有效，直链为带签名的临时地址，会过期，需实时解析。</p>
 */
public final class KuwoMusicApi {

    public static final String TAG = "LsOnlineMusic";

    public static final String Q_128 = "standard";
    public static final String Q_320 = "exhigh";
    public static final String Q_FLAC = "lossless";
    /** 自动音质:优先取最高(FLAC),不可用时逐级降档。 */
    public static final String Q_AUTO = "auto";

    private static final String PROXY = "https://music.nxinxz.com/kw.php";
    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    private KuwoMusicApi() {}

    public static final class Song {
        public String id;
        public String title;
        public String artist;
        public String album;
        public String artwork;
        public String formats;
        public int durationMs;

        public String display() {
            return (artist == null || artist.isEmpty()) ? title : (title + " - " + artist);
        }
    }

    public static final class Album {
        public String id;
        public String title;
        public String artist;
        public String artwork;
        public String description;
        public String date;
        public String artistId;

        public String display() {
            return (artist == null || artist.isEmpty()) ? title : (title + " - " + artist);
        }
    }

    public static final class Artist {
        public String id;
        public String name;
        public String avatar;
        public String description;
        public int worksNum;
    }

    public static final class Playlist {
        public String id;
        public String title;
        public String artist;
        public String artwork;
        public String description;
        public long playCount;
        public int worksNum;

        public String display() {
            return (artist == null || artist.isEmpty()) ? title : (title + " - " + artist);
        }
    }

    public static final class Tag {
        public String id;
        public String title;
        public String digest;
        public Tag() {}
        public Tag(String id, String title, String digest) {
            this.id = id; this.title = title; this.digest = digest;
        }
    }

    public static final class Chart {
        public String id;
        public String title;
        public String description;
        public String cover;
    }

    public static final class ChartGroup {
        public String title;
        public List<Chart> charts = new ArrayList<>();
    }

    public interface Progress {
        void onProgress(long current, long total);
        boolean isCancelled();
    }

    // ==================== 搜索 ====================

    public static List<Song> search(String keyword, int page) throws Exception {
        String url = "https://search.kuwo.cn/r.s?client=kt&all=" + enc(keyword)
                + "&pn=" + Math.max(0, page - 1)
                + "&rn=20&uid=2574109560&ver=kwplayer_ar_8.5.4.2&vipver=1"
                + "&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1";
        JSONObject root = new JSONObject(httpGet(url));
        JSONArray arr = root.optJSONArray("abslist");
        List<Song> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Song s = new Song();
            s.id = strip(o.optString("MUSICRID", ""));
            s.title = decode(o.optString("NAME", ""));
            s.artist = decode(o.optString("ARTIST", ""));
            s.album = decode(o.optString("ALBUM", ""));
            s.formats = o.optString("FORMATS", "");
            s.artwork = artwork(o.optString("web_albumpic_short", ""));
            try {
                s.durationMs = (int) (Double.parseDouble(o.optString("DURATION", "0")) * 1000);
            } catch (Throwable ignored) {}
            if (s.id != null && !s.id.isEmpty() && s.title != null && !s.title.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** 搜索专辑。 */
    public static List<Album> searchAlbums(String keyword, int page) throws Exception {
        String url = "https://search.kuwo.cn/r.s?client=kt&all=" + enc(keyword)
                + "&pn=" + Math.max(0, page - 1)
                + "&rn=20&ft=album&itemset=web_2013&encoding=utf8&rformat=json&pcjson=1";
        JSONObject root = new JSONObject(httpGet(url));
        JSONArray arr = root.optJSONArray("albumlist");
        List<Album> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Album a = new Album();
            a.id = o.optString("albumid", "");
            a.title = decode(o.optString("name", ""));
            a.artist = decode(o.optString("artist", ""));
            a.artwork = httpsArt(o.optString("img", ""));
            if (a.artwork == null || a.artwork.isEmpty()) a.artwork = artwork(o.optString("pic", ""));
            a.description = decode(o.optString("info", ""));
            a.date = o.optString("pub", "");
            a.artistId = o.optString("artistid", "");
            if (!a.id.isEmpty() && !a.title.isEmpty()) out.add(a);
        }
        return out;
    }

    /** 搜索歌手。 */
    public static List<Artist> searchArtists(String keyword, int page) throws Exception {
        String url = "https://search.kuwo.cn/r.s?client=kt&all=" + enc(keyword)
                + "&pn=" + Math.max(0, page - 1)
                + "&rn=20&ft=artist&itemset=web_2013&encoding=utf8&rformat=json&pcjson=1";
        JSONObject root = new JSONObject(httpGet(url));
        JSONArray arr = root.optJSONArray("abslist");
        List<Artist> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Artist a = new Artist();
            a.id = o.optString("ARTISTID", "");
            a.name = decode(o.optString("ARTIST", ""));
            // hts_PICPATH 已经是完整 URL；PICPATH 是短路径(需走 starheads 目录)
            a.avatar = httpsArt(o.optString("hts_PICPATH", ""));
            if (a.avatar == null || a.avatar.isEmpty()) {
                String p = o.optString("PICPATH", "");
                if (p != null && !p.isEmpty()) {
                    a.avatar = "https://img2.kuwo.cn/star/starheads/" + p;
                }
            }
            a.description = decode(o.optString("desc", ""));
            try { a.worksNum = Integer.parseInt(o.optString("SONGNUM", "0")); } catch (Throwable ignored) {}
            if (!a.id.isEmpty() && !a.name.isEmpty()) out.add(a);
        }
        return out;
    }

    /** 搜索歌单。 */
    public static List<Playlist> searchPlaylists(String keyword, int page) throws Exception {
        String url = "https://search.kuwo.cn/r.s?client=kt&all=" + enc(keyword)
                + "&pn=" + Math.max(0, page - 1)
                + "&rn=20&ft=playlist&itemset=web_2013&encoding=utf8&rformat=json&pcjson=1";
        JSONObject root = new JSONObject(httpGet(url));
        JSONArray arr = root.optJSONArray("abslist");
        List<Playlist> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Playlist p = new Playlist();
            p.id = o.optString("playlistid", "");
            p.title = decode(o.optString("name", ""));
            p.artist = decode(o.optString("nickname", ""));
            p.artwork = httpsArt(o.optString("pic", ""));
            p.description = decode(o.optString("intro", ""));
            p.playCount = parseLong(o.optString("playcnt", "0"));
            try { p.worksNum = Integer.parseInt(o.optString("songnum", "0")); } catch (Throwable ignored) {}
            if (!p.id.isEmpty() && !p.title.isEmpty()) out.add(p);
        }
        return out;
    }

    /** 歌手代表作品。 */
    public static List<Song> artistSongs(String artistId, int page) throws Exception {
        String url = "https://search.kuwo.cn/r.s?pn=" + Math.max(0, page - 1)
                + "&rn=50&artistid=" + enc(artistId)
                + "&stype=artist2music&sortby=0&alflac=1&show_copyright_off=1&pcmp4=1"
                + "&encoding=utf8&plat=pc&thost=search.kuwo.cn&vipver=MUSIC_9.1.1.2_BCS2"
                + "&devid=38668888&newver=1&pcjson=1";
        JSONObject root = new JSONObject(httpGet(url));
        return songs(root.optJSONArray("musiclist"));
    }

    /** 歌手专辑列表。 */
    public static List<Album> artistAlbums(String artistId, int page) throws Exception {
        String url = "https://search.kuwo.cn/r.s?pn=" + Math.max(0, page - 1)
                + "&rn=50&artistid=" + enc(artistId)
                + "&stype=albumlist&sortby=0&alflac=1&show_copyright_off=1&pcmp4=1"
                + "&encoding=utf8&plat=pc&thost=search.kuwo.cn&vipver=MUSIC_9.1.1.2_BCS2"
                + "&devid=38668888&newver=1&pcjson=1";
        JSONObject root = new JSONObject(httpGet(url));
        JSONArray arr = root.optJSONArray("albumlist");
        List<Album> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Album a = new Album();
            a.id = o.optString("albumid", "");
            a.title = decode(o.optString("name", ""));
            a.artist = decode(o.optString("artist", ""));
            a.artwork = httpsArt(o.optString("img", ""));
            if (a.artwork == null || a.artwork.isEmpty()) a.artwork = artwork(o.optString("pic", ""));
            a.date = o.optString("pub", "");
            a.artistId = o.optString("artistid", artistId);
            if (!a.id.isEmpty() && !a.title.isEmpty()) out.add(a);
        }
        return out;
    }

    /** 专辑曲目。 */
    public static List<Song> albumSongs(String albumId) throws Exception {
        String url = "https://search.kuwo.cn/r.s?pn=0&rn=100&albumid=" + enc(albumId)
                + "&stype=albuminfo&sortby=0&alflac=1&show_copyright_off=1&pcmp4=1"
                + "&encoding=utf8&plat=pc&thost=search.kuwo.cn&vipver=MUSIC_9.1.1.2_BCS2"
                + "&devid=38668888&newver=1&pcjson=1";
        JSONObject root = new JSONObject(httpGet(url));
        String cover = httpsArt(root.optString("img", ""));
        List<Song> out = songs(root.optJSONArray("musiclist"));
        if (cover != null && !cover.isEmpty()) {
            for (Song s : out) if (s.artwork == null || s.artwork.isEmpty()) s.artwork = cover;
        }
        return out;
    }

    /** 榜单分组（酷我 pc 排行榜）。 */
    public static List<ChartGroup> chartGroups() throws Exception {
        JSONObject root = new JSONObject(httpGet("https://wapi.kuwo.cn/api/pc/bang/list"));
        JSONArray groups = root.optJSONArray("child");
        List<ChartGroup> out = new ArrayList<>();
        if (groups == null) return out;
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g == null) continue;
            ChartGroup cg = new ChartGroup();
            cg.title = decode(g.optString("disname", g.optString("name", "")));
            JSONArray items = g.optJSONArray("child");
            if (items != null) {
                for (int j = 0; j < items.length(); j++) {
                    JSONObject o = items.optJSONObject(j);
                    if (o == null) continue;
                    Chart c = new Chart();
                    c.id = o.optString("sourceid", o.optString("id", ""));
                    c.title = decode(o.optString("name", ""));
                    c.description = decode(o.optString("intro", ""));
                    c.cover = httpsArt(o.optString("pic5", ""));
                    if (c.cover == null || c.cover.isEmpty()) c.cover = httpsArt(o.optString("pic2", ""));
                    if (c.cover == null || c.cover.isEmpty()) c.cover = httpsArt(o.optString("pic", ""));
                    if (!c.id.isEmpty() && !c.title.isEmpty()) cg.charts.add(c);
                }
            }
            if (!cg.charts.isEmpty()) out.add(cg);
        }
        return out;
    }

    /** 榜单曲目。 */
    public static List<Song> chartSongs(String chartId) throws Exception {
        String url = "https://kbangserver.kuwo.cn/ksong.s?from=pc&fmt=json&pn=0&rn=100"
                + "&type=bang&data=content&id=" + enc(chartId)
                + "&show_copyright_off=0&pcmp4=1&isbang=1&userid=0&httpStatus=1";
        JSONObject root = new JSONObject(httpGet(url));
        return songs(root.optJSONArray("musiclist"));
    }

    /** 歌单曲目（pid 为歌单数字 id）。 */
    public static List<Song> playlistSongs(String pid, int page, int rn) throws Exception {
        String url = "https://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=" + enc(pid)
                + "&pn=" + Math.max(0, page - 1) + "&rn=" + rn
                + "&encode=utf8&keyset=pl2012&vipver=MUSIC_9.1.1.2_BCS2&newver=1";
        JSONObject root = new JSONObject(httpGet(url));
        return songs(root.optJSONArray("musiclist"));
    }

    /** 从歌单链接或纯数字中解析歌单 id，失败返回 null。 */
    public static String parsePlaylistId(String urlLike) {
        if (urlLike == null) return null;
        String s = urlLike.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{4,})").matcher(s);
        if (m.find()) return m.group(1);
        return null;
    }

    /** 推荐歌单标签；首个分组为固定热门标签。 */
    public static List<Tag> recommendTags() throws Exception {
        List<Tag> out = new ArrayList<>();
        out.add(new Tag("1265", "经典", "10000"));
        out.add(new Tag("146", "伤感", "10000"));
        out.add(new Tag("35", "欧美", "10000"));
        out.add(new Tag("1848", "翻唱", "10000"));
        out.add(new Tag("621", "网络", "10000"));
        String url = "https://wapi.kuwo.cn/api/pc/classify/playlist/getTagList"
                + "?cmd=rcm_keyword_playlist&user=0&prod=kwplayer_pc_9.0.5.0&vipver=9.0.5.0"
                + "&source=kwplayer_pc_9.0.5.0&loginUid=0&loginSid=0&appUid=76039576";
        JSONObject root = new JSONObject(httpGet(url));
        JSONArray groups = root.optJSONArray("data");
        if (groups == null) return out;
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g == null) continue;
            JSONArray tags = g.optJSONArray("data");
            if (tags == null) continue;
            for (int j = 0; j < tags.length(); j++) {
                JSONObject t = tags.optJSONObject(j);
                if (t == null) continue;
                Tag tag = new Tag(t.optString("id", ""), decode(t.optString("name", "")),
                        t.optString("digest", ""));
                if (!tag.id.isEmpty() && !tag.title.isEmpty()) out.add(tag);
            }
        }
        return out;
    }

    /** 按标签取推荐歌单；tagId 为空时取酷我热门推荐。 */
    public static List<Playlist> recommendPlaylists(String tagId, int page) throws Exception {
        String url;
        if (tagId == null || tagId.isEmpty()) {
            url = "https://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList"
                    + "?loginUid=0&loginSid=0&appUid=76039576&pn=" + Math.max(0, page - 1)
                    + "&rn=20&order=hot";
        } else {
            url = "https://wapi.kuwo.cn/api/pc/classify/playlist/getTagPlayList"
                    + "?loginUid=0&loginSid=0&appUid=76039576&pn=" + Math.max(0, page - 1)
                    + "&id=" + enc(tagId) + "&rn=20";
        }
        JSONObject root = new JSONObject(httpGet(url));
        JSONObject data = root.optJSONObject("data");
        List<Playlist> out = new ArrayList<>();
        if (data == null) return out;
        JSONArray arr = data.optJSONArray("data");
        if (arr == null) arr = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Playlist p = new Playlist();
            p.id = o.optString("id", o.optString("playlistid", ""));
            p.title = decode(o.optString("name", ""));
            p.artist = decode(o.optString("uname", o.optString("nickname", "")));
            p.artwork = httpsArt(o.optString("img", o.optString("pic", "")));
            p.playCount = parseLong(o.optString("listencnt", o.optString("playcnt", "0")));
            if (!p.id.isEmpty() && !p.title.isEmpty()) out.add(p);
        }
        return out;
    }

    // ==================== 歌词 ====================

    public static String lyric(String songId) throws Exception {
        String url = "https://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId="
                + enc(songId) + "&httpStatus=1";
        JSONObject root = new JSONObject(httpGet(url));
        JSONObject data = root.optJSONObject("data");
        if (data == null) return "";
        JSONArray list = data.optJSONArray("lrclist");
        if (list == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            sb.append('[').append(o.optString("time", "")).append(']')
                    .append(decode(o.optString("lineLyric", ""))).append('\n');
        }
        return sb.toString();
    }

    // ==================== 封面 ====================

    private static final Map<String, String> COVER_CACHE = new ConcurrentHashMap<>();

    /**
     * 按歌曲 id 解析专辑封面（酷我列表接口已不再返回封面字段，只能逐曲查询）。
     * 返回空串表示确认无封面；null 表示暂不可用。结果做内存缓存。
     */
    public static String cover(String songId) {
        if (songId == null || songId.isEmpty()) return null;
        String cached = COVER_CACHE.get(songId);
        if (cached != null) return cached.isEmpty() ? null : cached;
        // 不再用类级 synchronized 包住网络请求(会把整列表的封面解析串行化);
        // 改为并发哈希表, 允许不同歌曲并行解析, 重复请求靠 putIfAbsent 收敛。
        String url = null;
        try {
            String u = "https://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId="
                    + enc(songId) + "&httpStatus=1";
            JSONObject root = new JSONObject(httpGet(u));
            JSONObject data = root.optJSONObject("data");
            if (data != null) {
                JSONObject info = data.optJSONObject("songinfo");
                if (info != null) url = coverFromPic(info.optString("pic", ""));
            }
        } catch (Throwable ignored) {
        }
        String val = url == null ? "" : url;
        COVER_CACHE.putIfAbsent(songId, val);
        String got = COVER_CACHE.get(songId);
        return (got == null || got.isEmpty()) ? null : got;
    }

    /** http://img1.kwcdn.kuwo.cn/star/albumcover/240/s4s81/95/xxx.jpg → img4 1080 https。 */
    private static String coverFromPic(String pic) {
        if (pic == null || pic.isEmpty()) return null;
        String marker = "/albumcover/";
        int i = pic.indexOf(marker);
        if (i < 0) return null;
        String rest = pic.substring(i + marker.length());
        int slash = rest.indexOf('/');
        if (slash < 0) return null;
        return "https://img4.kuwo.cn/star/albumcover/1080" + rest.substring(slash);
    }

    // ==================== 直链 ====================

    /** 第三方中转地址（播放/下载前需再用 {@link #resolveFinalUrl} 解析到酷我 CDN）。 */
    public static String streamUrl(String songId, String level) {
        if (level == null || level.isEmpty()) level = Q_320;
        return PROXY + "?id=" + enc(songId) + "&level=" + enc(level) + "&type=mp3";
    }

    /** 跟随 302 拿到最终的酷我 CDN 直链（优先 HEAD，避免整段下载；不支持时退化为 Range 单字节 GET）。 */
    public static String resolveFinalUrl(String url) throws Exception {
        String cur = url;
        for (int i = 0; i < 6; i++) {
            String next = null;
            try {
                next = probeRedirect(cur, true);
            } catch (Throwable ignored) {}
            if (next == null) next = probeRedirect(cur, false);
            if (next.equals(cur)) return cur;
            cur = next;
        }
        return cur;
    }

    /**
     * 单次探测：返回重定向目标（绝对化）或原 url（非重定向）。
     *
     * @param head true=HEAD（无响应体）；false=带 Range 的单字节 GET（HEAD 不被支持时兜底）
     * @return null 表示当前方法不被服务端支持，需换另一种方法重试
     */
    private static String probeRedirect(String url, boolean head) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", UA);
            if (head) {
                c.setRequestMethod("HEAD");
            } else {
                c.setRequestProperty("Range", "bytes=0-0");
            }
            int code = c.getResponseCode();
            if (head && (code == 405 || code == 501)) return null;
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                if (loc == null || loc.isEmpty()) throw new Exception("重定向缺少 Location");
                if (loc.startsWith("/")) {
                    URL u = new URL(url);
                    String port = u.getPort() > 0 ? (":" + u.getPort()) : "";
                    loc = u.getProtocol() + "://" + u.getHost() + port + loc;
                }
                return loc;
            }
            if (code >= 400) throw new Exception("HTTP " + code);
            String ct = c.getContentType();
            if (ct != null && (ct.contains("html") || ct.contains("json") || ct.contains("text"))) {
                throw new Exception("非音频响应: " + ct);
            }
            return url;
        } finally {
            try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    // ==================== 下载 ====================

    public static long download(String url, File out, Progress cb) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Encoding", "identity");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            long total = c.getContentLengthLong();
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            InputStream in = c.getInputStream();
            FileOutputStream fos = new FileOutputStream(out);
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            try {
                while ((n = in.read(buf)) > 0) {
                    if (cb != null && cb.isCancelled()) throw new Exception("已取消");
                    fos.write(buf, 0, n);
                    done += n;
                    if (cb != null) cb.onProgress(done, total);
                }
                fos.flush();
            } finally {
                try { fos.close(); } catch (Throwable ignored) {}
                try { in.close(); } catch (Throwable ignored) {}
            }
            return done;
        } finally {
            try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    // ==================== 工具 ====================

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Encoding", "identity");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            String s = bos.toString("UTF-8");
            // 酷我 r.s 的 json 响应偶尔带 XSSI 前缀
            s = s.trim();
            if (s.startsWith("callback(")) {
                s = s.substring("callback(".length());
                if (s.endsWith(")")) s = s.substring(0, s.length() - 1);
            }
            return s;
        } finally {
            try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static String strip(String musicRid) {
        if (musicRid == null) return "";
        return musicRid.startsWith("MUSIC_") ? musicRid.substring(6) : musicRid;
    }

    private static String artwork(String shortPath) {
        if (shortPath == null || shortPath.isEmpty()) return null;
        int idx = shortPath.indexOf('/');
        if (idx < 0) return null;
        return "https://img4.kuwo.cn/star/albumcover/1080" + shortPath.substring(idx);
    }

    /** 升级 http 链接到 https（模块禁用明文流量）。 */
    private static String httpsArt(String u) {
        if (u == null || u.isEmpty()) return null;
        if (u.startsWith("http://")) return "https://" + u.substring(7);
        if (u.startsWith("//")) return "https:" + u;
        return u.startsWith("https://") ? u : null;
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Throwable t) { return 0L; }
    }

    /** 解析 musiclist 数组（兼容 musicrid 与 id 两种主键）。 */
    private static List<Song> songs(JSONArray arr) {
        List<Song> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Song s = new Song();
            String rid = o.optString("musicrid", "");
            if (rid == null || rid.isEmpty()) rid = o.optString("id", o.optString("rid", ""));
            s.id = strip(rid);
            s.title = decode(o.optString("name", ""));
            s.artist = decode(o.optString("artist", ""));
            s.album = decode(o.optString("album", ""));
            s.formats = o.optString("formats", "");
            s.artwork = artwork(o.optString("web_albumpic_short", ""));
            String dur = o.optString("duration", o.optString("DURATION", ""));
            try { s.durationMs = (int) (Double.parseDouble(dur) * 1000); } catch (Throwable ignored) {}
            if (s.id != null && !s.id.isEmpty() && s.title != null && !s.title.isEmpty()) out.add(s);
        }
        return out;
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Throwable t) { return ""; }
    }

    /** 解码 HTML 实体（酷我接口返回值含 &amp; &#xxx; 等）。 */
    public static String decode(String s) {
        if (s == null || s.indexOf('&') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '&') {
                int semi = s.indexOf(';', i + 1);
                if (semi > i && semi - i <= 8) {
                    String ent = s.substring(i + 1, semi);
                    String rep = entity(ent);
                    if (rep != null) {
                        sb.append(rep);
                        i = semi + 1;
                        continue;
                    }
                }
            }
            sb.append(ch);
            i++;
        }
        return sb.toString();
    }

    private static String entity(String ent) {
        if (ent.isEmpty()) return null;
        if (ent.charAt(0) == '#') {
            try {
                int cp = (ent.charAt(1) == 'x' || ent.charAt(1) == 'X')
                        ? Integer.parseInt(ent.substring(2), 16)
                        : Integer.parseInt(ent.substring(1));
                return new String(Character.toChars(cp));
            } catch (Throwable ignored) {
                return null;
            }
        }
        switch (ent) {
            case "amp": return "&";
            case "lt": return "<";
            case "gt": return ">";
            case "quot": return "\"";
            case "apos": return "'";
            case "nbsp": return " ";
            default: return null;
        }
    }
}
