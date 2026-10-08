package com.leshao.v3.music

import org.json.JSONArray
import org.json.JSONObject

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap

/**
 * 酷我音源客户端（第三方公益中转）。
 *
 * 元数据走酷我官方接口（https），直链走第三方中转 music.nxinxz.com/kw.php，
 * 由中转 302 跳转到酷我 CDN。支持 128K / 320K / 无损 FLAC 三档。
 *
 * 注意：仅对非版权受限曲目有效，直链为带签名的临时地址，会过期，需实时解析。
 */
final class KuwoMusicApi private constructor() {

    class Song {
        @JvmField var id: String = ""
        @JvmField var title: String? = null
        @JvmField var artist: String? = null
        @JvmField var album: String? = null
        @JvmField var artwork: String? = null
        @JvmField var formats: String? = null
        @JvmField var durationMs: Int = 0

        fun display(): String? {
            val a = artist
            if (a.isNullOrEmpty()) return title
            return title + " - " + a
        }
    }

    class Album {
        @JvmField var id: String? = null
        @JvmField var title: String? = null
        @JvmField var artist: String? = null
        @JvmField var artwork: String? = null
        @JvmField var description: String? = null
        @JvmField var date: String? = null
        @JvmField var artistId: String? = null

        fun display(): String? {
            val a = artist
            if (a.isNullOrEmpty()) return title
            return title + " - " + a
        }
    }

    class Artist {
        @JvmField var id: String? = null
        @JvmField var name: String? = null
        @JvmField var avatar: String? = null
        @JvmField var description: String? = null
        @JvmField var worksNum: Int = 0
    }

    class Playlist {
        @JvmField var id: String? = null
        @JvmField var title: String? = null
        @JvmField var artist: String? = null
        @JvmField var artwork: String? = null
        @JvmField var description: String? = null
        @JvmField var playCount: Long = 0L
        @JvmField var worksNum: Int = 0

        fun display(): String? {
            val a = artist
            if (a.isNullOrEmpty()) return title
            return title + " - " + a
        }
    }

    class Tag {
        @JvmField var id: String? = null
        @JvmField var title: String? = null
        @JvmField var digest: String? = null

        constructor()

        constructor(id: String?, title: String?, digest: String?) {
            this.id = id
            this.title = title
            this.digest = digest
        }
    }

    class Chart {
        @JvmField var id: String? = null
        @JvmField var title: String? = null
        @JvmField var description: String? = null
        @JvmField var cover: String? = null
    }

    class ChartGroup {
        @JvmField var title: String? = null
        @JvmField val charts: MutableList<Chart> = ArrayList()
    }

    interface Progress {
        fun onProgress(current: Long, total: Long)
        fun isCancelled(): Boolean
    }

    companion object {
        const val TAG = "LsOnlineMusic"

        const val Q_128 = "standard"
        const val Q_320 = "exhigh"
        const val Q_FLAC = "lossless"
        /** 自动音质:优先取最高(FLAC),不可用时逐级降档。 */
        const val Q_AUTO = "auto"

        private const val PROXY = "https://music.nxinxz.com/kw.php"
        private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

        private val COVER_CACHE: MutableMap<String, String> = ConcurrentHashMap()

        // ==================== 搜索 ====================

        @JvmStatic
        @Throws(Exception::class)
        fun search(keyword: String?, page: Int): MutableList<Song> {
            val url = "https://search.kuwo.cn/r.s?client=kt&all=" + enc(keyword) +
                    "&pn=" + Math.max(0, page - 1) +
                    "&rn=20&uid=2574109560&ver=kwplayer_ar_8.5.4.2&vipver=1" +
                    "&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1"
            val root = JSONObject(httpGet(url))
            val arr = root.optJSONArray("abslist")
            val out = ArrayList<Song>()
            if (arr == null) return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val s = Song()
                s.id = strip(o.optString("MUSICRID", ""))
                s.title = decode(o.optString("NAME", ""))
                s.artist = decode(o.optString("ARTIST", ""))
                s.album = decode(o.optString("ALBUM", ""))
                s.formats = o.optString("FORMATS", "")
                s.artwork = artwork(o.optString("web_albumpic_short", ""))
                try {
                    s.durationMs = (o.optString("DURATION", "0").toDouble() * 1000).toInt()
                } catch (ignored: Throwable) {
                }
                if (!s.id.isNullOrEmpty() && !s.title.isNullOrEmpty()) {
                    out.add(s)
                }
            }
            return out
        }

        /** 搜索专辑。 */
        @JvmStatic
        @Throws(Exception::class)
        fun searchAlbums(keyword: String?, page: Int): MutableList<Album> {
            val url = "https://search.kuwo.cn/r.s?client=kt&all=" + enc(keyword) +
                    "&pn=" + Math.max(0, page - 1) +
                    "&rn=20&ft=album&itemset=web_2013&encoding=utf8&rformat=json&pcjson=1"
            val root = JSONObject(httpGet(url))
            val arr = root.optJSONArray("albumlist")
            val out = ArrayList<Album>()
            if (arr == null) return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val a = Album()
                a.id = o.optString("albumid", "")
                a.title = decode(o.optString("name", ""))
                a.artist = decode(o.optString("artist", ""))
                a.artwork = httpsArt(o.optString("img", ""))
                if (a.artwork.isNullOrEmpty()) a.artwork = artwork(o.optString("pic", ""))
                a.description = decode(o.optString("info", ""))
                a.date = o.optString("pub", "")
                a.artistId = o.optString("artistid", "")
                if (!a.id.isNullOrEmpty() && !a.title.isNullOrEmpty()) out.add(a)
            }
            return out
        }

        /** 搜索歌手。 */
        @JvmStatic
        @Throws(Exception::class)
        fun searchArtists(keyword: String?, page: Int): MutableList<Artist> {
            val url = "https://search.kuwo.cn/r.s?client=kt&all=" + enc(keyword) +
                    "&pn=" + Math.max(0, page - 1) +
                    "&rn=20&ft=artist&itemset=web_2013&encoding=utf8&rformat=json&pcjson=1"
            val root = JSONObject(httpGet(url))
            val arr = root.optJSONArray("abslist")
            val out = ArrayList<Artist>()
            if (arr == null) return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val a = Artist()
                a.id = o.optString("ARTISTID", "")
                a.name = decode(o.optString("ARTIST", ""))
                // hts_PICPATH 已经是完整 URL；PICPATH 是短路径(需走 starheads 目录)
                a.avatar = httpsArt(o.optString("hts_PICPATH", ""))
                if (a.avatar.isNullOrEmpty()) {
                    val p = o.optString("PICPATH", "")
                    if (!p.isNullOrEmpty()) {
                        a.avatar = "https://img2.kuwo.cn/star/starheads/" + p
                    }
                }
                a.description = decode(o.optString("desc", ""))
                try {
                    a.worksNum = o.optString("SONGNUM", "0").toInt()
                } catch (ignored: Throwable) {
                }
                if (!a.id.isNullOrEmpty() && !a.name.isNullOrEmpty()) out.add(a)
            }
            return out
        }

        /** 搜索歌单。 */
        @JvmStatic
        @Throws(Exception::class)
        fun searchPlaylists(keyword: String?, page: Int): MutableList<Playlist> {
            val url = "https://search.kuwo.cn/r.s?client=kt&all=" + enc(keyword) +
                    "&pn=" + Math.max(0, page - 1) +
                    "&rn=20&ft=playlist&itemset=web_2013&encoding=utf8&rformat=json&pcjson=1"
            val root = JSONObject(httpGet(url))
            val arr = root.optJSONArray("abslist")
            val out = ArrayList<Playlist>()
            if (arr == null) return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = Playlist()
                p.id = o.optString("playlistid", "")
                p.title = decode(o.optString("name", ""))
                p.artist = decode(o.optString("nickname", ""))
                p.artwork = httpsArt(o.optString("pic", ""))
                p.description = decode(o.optString("intro", ""))
                p.playCount = parseLong(o.optString("playcnt", "0"))
                try {
                    p.worksNum = o.optString("songnum", "0").toInt()
                } catch (ignored: Throwable) {
                }
                if (!p.id.isNullOrEmpty() && !p.title.isNullOrEmpty()) out.add(p)
            }
            return out
        }

        /** 歌手代表作品。 */
        @JvmStatic
        @Throws(Exception::class)
        fun artistSongs(artistId: String?, page: Int): MutableList<Song> {
            val url = "https://search.kuwo.cn/r.s?pn=" + Math.max(0, page - 1) +
                    "&rn=50&artistid=" + enc(artistId) +
                    "&stype=artist2music&sortby=0&alflac=1&show_copyright_off=1&pcmp4=1" +
                    "&encoding=utf8&plat=pc&thost=search.kuwo.cn&vipver=MUSIC_9.1.1.2_BCS2" +
                    "&devid=38668888&newver=1&pcjson=1"
            val root = JSONObject(httpGet(url))
            return songs(root.optJSONArray("musiclist"))
        }

        /** 歌手专辑列表。 */
        @JvmStatic
        @Throws(Exception::class)
        fun artistAlbums(artistId: String?, page: Int): MutableList<Album> {
            val url = "https://search.kuwo.cn/r.s?pn=" + Math.max(0, page - 1) +
                    "&rn=50&artistid=" + enc(artistId) +
                    "&stype=albumlist&sortby=0&alflac=1&show_copyright_off=1&pcmp4=1" +
                    "&encoding=utf8&plat=pc&thost=search.kuwo.cn&vipver=MUSIC_9.1.1.2_BCS2" +
                    "&devid=38668888&newver=1&pcjson=1"
            val root = JSONObject(httpGet(url))
            val arr = root.optJSONArray("albumlist")
            val out = ArrayList<Album>()
            if (arr == null) return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val a = Album()
                a.id = o.optString("albumid", "")
                a.title = decode(o.optString("name", ""))
                a.artist = decode(o.optString("artist", ""))
                a.artwork = httpsArt(o.optString("img", ""))
                if (a.artwork.isNullOrEmpty()) a.artwork = artwork(o.optString("pic", ""))
                a.date = o.optString("pub", "")
                a.artistId = o.optString("artistid", artistId)
                if (!a.id.isNullOrEmpty() && !a.title.isNullOrEmpty()) out.add(a)
            }
            return out
        }

        /** 专辑曲目。 */
        @JvmStatic
        @Throws(Exception::class)
        fun albumSongs(albumId: String?): MutableList<Song> {
            val url = "https://search.kuwo.cn/r.s?pn=0&rn=100&albumid=" + enc(albumId) +
                    "&stype=albuminfo&sortby=0&alflac=1&show_copyright_off=1&pcmp4=1" +
                    "&encoding=utf8&plat=pc&thost=search.kuwo.cn&vipver=MUSIC_9.1.1.2_BCS2" +
                    "&devid=38668888&newver=1&pcjson=1"
            val root = JSONObject(httpGet(url))
            val cover = httpsArt(root.optString("img", ""))
            val out = songs(root.optJSONArray("musiclist"))
            if (!cover.isNullOrEmpty()) {
                for (s in out) if (s.artwork.isNullOrEmpty()) s.artwork = cover
            }
            return out
        }

        /** 榜单分组（酷我 pc 排行榜）。 */
        @JvmStatic
        @Throws(Exception::class)
        fun chartGroups(): MutableList<ChartGroup> {
            val root = JSONObject(httpGet("https://wapi.kuwo.cn/api/pc/bang/list"))
            val groups = root.optJSONArray("child")
            val out = ArrayList<ChartGroup>()
            if (groups == null) return out
            for (i in 0 until groups.length()) {
                val g = groups.optJSONObject(i) ?: continue
                val cg = ChartGroup()
                cg.title = decode(g.optString("disname", g.optString("name", "")))
                val items = g.optJSONArray("child")
                if (items != null) {
                    for (j in 0 until items.length()) {
                        val o = items.optJSONObject(j) ?: continue
                        val c = Chart()
                        c.id = o.optString("sourceid", o.optString("id", ""))
                        c.title = decode(o.optString("name", ""))
                        c.description = decode(o.optString("intro", ""))
                        c.cover = httpsArt(o.optString("pic5", ""))
                        if (c.cover.isNullOrEmpty()) c.cover = httpsArt(o.optString("pic2", ""))
                        if (c.cover.isNullOrEmpty()) c.cover = httpsArt(o.optString("pic", ""))
                        if (!c.id.isNullOrEmpty() && !c.title.isNullOrEmpty()) cg.charts.add(c)
                    }
                }
                if (cg.charts.isNotEmpty()) out.add(cg)
            }
            return out
        }

        /** 榜单曲目。 */
        @JvmStatic
        @Throws(Exception::class)
        fun chartSongs(chartId: String?): MutableList<Song> {
            val url = "https://kbangserver.kuwo.cn/ksong.s?from=pc&fmt=json&pn=0&rn=100" +
                    "&type=bang&data=content&id=" + enc(chartId) +
                    "&show_copyright_off=0&pcmp4=1&isbang=1&userid=0&httpStatus=1"
            val root = JSONObject(httpGet(url))
            return songs(root.optJSONArray("musiclist"))
        }

        /** 歌单曲目（pid 为歌单数字 id）。 */
        @JvmStatic
        @Throws(Exception::class)
        fun playlistSongs(pid: String?, page: Int, rn: Int): MutableList<Song> {
            val url = "https://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=" + enc(pid) +
                    "&pn=" + Math.max(0, page - 1) + "&rn=" + rn +
                    "&encode=utf8&keyset=pl2012&vipver=MUSIC_9.1.1.2_BCS2&newver=1"
            val root = JSONObject(httpGet(url))
            return songs(root.optJSONArray("musiclist"))
        }

        /** 从歌单链接或纯数字中解析歌单 id，失败返回 null。 */
        @JvmStatic
        fun parsePlaylistId(urlLike: String?): String? {
            if (urlLike == null) return null
            val s = urlLike.trim()
            val m = java.util.regex.Pattern
                .compile("(\\d{4,})").matcher(s)
            if (m.find()) return m.group(1)
            return null
        }

        /** 推荐歌单标签；首个分组为固定热门标签。 */
        @JvmStatic
        @Throws(Exception::class)
        fun recommendTags(): MutableList<Tag> {
            val out = ArrayList<Tag>()
            out.add(Tag("1265", "经典", "10000"))
            out.add(Tag("146", "伤感", "10000"))
            out.add(Tag("35", "欧美", "10000"))
            out.add(Tag("1848", "翻唱", "10000"))
            out.add(Tag("621", "网络", "10000"))
            val url = "https://wapi.kuwo.cn/api/pc/classify/playlist/getTagList" +
                    "?cmd=rcm_keyword_playlist&user=0&prod=kwplayer_pc_9.0.5.0&vipver=9.0.5.0" +
                    "&source=kwplayer_pc_9.0.5.0&loginUid=0&loginSid=0&appUid=76039576"
            val root = JSONObject(httpGet(url))
            val groups = root.optJSONArray("data")
            if (groups == null) return out
            for (i in 0 until groups.length()) {
                val g = groups.optJSONObject(i) ?: continue
                val tags = g.optJSONArray("data") ?: continue
                for (j in 0 until tags.length()) {
                    val t = tags.optJSONObject(j) ?: continue
                    val tag = Tag(t.optString("id", ""), decode(t.optString("name", "")),
                        t.optString("digest", ""))
                    if (!tag.id.isNullOrEmpty() && !tag.title.isNullOrEmpty()) out.add(tag)
                }
            }
            return out
        }

        /** 按标签取推荐歌单；tagId 为空时取酷我热门推荐。 */
        @JvmStatic
        @Throws(Exception::class)
        fun recommendPlaylists(tagId: String?, page: Int): MutableList<Playlist> {
            val url: String
            if (tagId.isNullOrEmpty()) {
                url = "https://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList" +
                        "?loginUid=0&loginSid=0&appUid=76039576&pn=" + Math.max(0, page - 1) +
                        "&rn=20&order=hot"
            } else {
                url = "https://wapi.kuwo.cn/api/pc/classify/playlist/getTagPlayList" +
                        "?loginUid=0&loginSid=0&appUid=76039576&pn=" + Math.max(0, page - 1) +
                        "&id=" + enc(tagId) + "&rn=20"
            }
            val root = JSONObject(httpGet(url))
            val data = root.optJSONObject("data")
            val out = ArrayList<Playlist>()
            if (data == null) return out
            var arr = data.optJSONArray("data")
            if (arr == null) arr = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = Playlist()
                p.id = o.optString("id", o.optString("playlistid", ""))
                p.title = decode(o.optString("name", ""))
                p.artist = decode(o.optString("uname", o.optString("nickname", "")))
                p.artwork = httpsArt(o.optString("img", o.optString("pic", "")))
                p.playCount = parseLong(o.optString("listencnt", o.optString("playcnt", "0")))
                if (!p.id.isNullOrEmpty() && !p.title.isNullOrEmpty()) out.add(p)
            }
            return out
        }

        // ==================== 歌词 ====================

        @JvmStatic
        @Throws(Exception::class)
        fun lyric(songId: String?): String {
            val url = "https://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=" +
                    enc(songId) + "&httpStatus=1"
            val root = JSONObject(httpGet(url))
            val data = root.optJSONObject("data") ?: return ""
            val list = data.optJSONArray("lrclist") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                sb.append('[').append(o.optString("time", "")).append(']')
                    .append(decode(o.optString("lineLyric", ""))).append('\n')
            }
            return sb.toString()
        }

        // ==================== 封面 ====================

        /**
         * 按歌曲 id 解析专辑封面（酷我列表接口已不再返回封面字段，只能逐曲查询）。
         * 返回空串表示确认无封面；null 表示暂不可用。结果做内存缓存。
         */
        @JvmStatic
        fun cover(songId: String?): String? {
            if (songId.isNullOrEmpty()) return null
            val cached = COVER_CACHE[songId]
            if (cached != null) return if (cached.isEmpty()) null else cached
            // 不再用类级 synchronized 包住网络请求(会把整列表的封面解析串行化);
            // 改为并发哈希表, 允许不同歌曲并行解析, 重复请求靠 putIfAbsent 收敛。
            var url: String? = null
            try {
                val u = "https://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=" +
                        enc(songId) + "&httpStatus=1"
                val root = JSONObject(httpGet(u))
                val data = root.optJSONObject("data")
                if (data != null) {
                    val info = data.optJSONObject("songinfo")
                    if (info != null) url = coverFromPic(info.optString("pic", ""))
                }
            } catch (ignored: Throwable) {
            }
            val value = url ?: ""
            COVER_CACHE.putIfAbsent(songId, value)
            val got = COVER_CACHE[songId]
            return if (got.isNullOrEmpty()) null else got
        }

        /** http://img1.kwcdn.kuwo.cn/star/albumcover/240/s4s81/95/xxx.jpg → img4 1080 https。 */
        private fun coverFromPic(pic: String?): String? {
            if (pic.isNullOrEmpty()) return null
            val marker = "/albumcover/"
            val i = pic.indexOf(marker)
            if (i < 0) return null
            val rest = pic.substring(i + marker.length)
            val slash = rest.indexOf('/')
            if (slash < 0) return null
            return "https://img4.kuwo.cn/star/albumcover/1080" + rest.substring(slash)
        }

        // ==================== 直链 ====================

        /** 第三方中转地址（播放/下载前需再用 resolveFinalUrl 解析到酷我 CDN）。 */
        @JvmStatic
        fun streamUrl(songId: String?, level: String?): String {
            var lv = level
            if (lv.isNullOrEmpty()) lv = Q_320
            return PROXY + "?id=" + enc(songId) + "&level=" + enc(lv) + "&type=mp3"
        }

        /** 跟随 302 拿到最终的酷我 CDN 直链（优先 HEAD，避免整段下载；不支持时退化为 Range 单字节 GET）。 */
        @JvmStatic
        @Throws(Exception::class)
        fun resolveFinalUrl(url: String): String {
            var cur = url
            for (i in 0 until 6) {
                var next: String? = null
                try {
                    next = probeRedirect(cur, true)
                } catch (ignored: Throwable) {
                }
                if (next == null) next = probeRedirect(cur, false)
                if (next == cur) return cur
                cur = next ?: return cur
            }
            return cur
        }

        /**
         * 单次探测：返回重定向目标（绝对化）或原 url（非重定向）。
         *
         * @param head true=HEAD（无响应体）；false=带 Range 的单字节 GET（HEAD 不被支持时兜底）
         * @return null 表示当前方法不被服务端支持，需换另一种方法重试
         */
        @Throws(Exception::class)
        private fun probeRedirect(url: String, head: Boolean): String? {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 8000
                c.readTimeout = 10000
                c.setRequestProperty("User-Agent", UA)
                if (head) {
                    c.requestMethod = "HEAD"
                } else {
                    c.setRequestProperty("Range", "bytes=0-0")
                }
                val code = c.responseCode
                if (head && (code == 405 || code == 501)) return null
                if (code >= 300 && code < 400) {
                    var loc = c.getHeaderField("Location")
                    if (loc.isNullOrEmpty()) throw Exception("重定向缺少 Location")
                    if (loc.startsWith("/")) {
                        val u = URL(url)
                        val port = if (u.port > 0) ":" + u.port else ""
                        loc = u.protocol + "://" + u.host + port + loc
                    }
                    return loc
                }
                if (code >= 400) throw Exception("HTTP " + code)
                val ct = c.contentType
                if (ct != null && (ct.contains("html") || ct.contains("json") || ct.contains("text"))) {
                    throw Exception("非音频响应: " + ct)
                }
                return url
            } finally {
                try {
                    c.disconnect()
                } catch (ignored: Throwable) {
                }
            }
        }

        // ==================== 下载 ====================

        @JvmStatic
        @Throws(Exception::class)
        fun download(url: String, out: File, cb: Progress?): Long {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = true
                c.connectTimeout = 10000
                c.readTimeout = 20000
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Accept-Encoding", "identity")
                val code = c.responseCode
                if (code < 200 || code >= 300) throw Exception("HTTP " + code)
                val total = c.contentLengthLong
                val parent = out.parentFile
                if (parent != null && !parent.exists()) parent.mkdirs()
                val input = c.inputStream
                val fos = FileOutputStream(out)
                val buf = ByteArray(64 * 1024)
                var done = 0L
                try {
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        if (cb != null && cb.isCancelled()) throw Exception("已取消")
                        fos.write(buf, 0, n)
                        done += n
                        if (cb != null) cb.onProgress(done, total)
                    }
                    fos.flush()
                } finally {
                    try {
                        fos.close()
                    } catch (ignored: Throwable) {
                    }
                    try {
                        input.close()
                    } catch (ignored: Throwable) {
                    }
                }
                return done
            } finally {
                try {
                    c.disconnect()
                } catch (ignored: Throwable) {
                }
            }
        }

        // ==================== 工具 ====================

        @Throws(Exception::class)
        private fun httpGet(url: String): String {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 8000
                c.readTimeout = 12000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Accept-Encoding", "identity")
                val code = c.responseCode
                if (code < 200 || code >= 300) throw Exception("HTTP " + code)
                val input = c.inputStream
                val bos = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    bos.write(buf, 0, n)
                }
                input.close()
                var s = bos.toString("UTF-8")
                // 酷我 r.s 的 json 响应偶尔带 XSSI 前缀
                s = s.trim()
                if (s.startsWith("callback(")) {
                    s = s.substring("callback(".length)
                    if (s.endsWith(")")) s = s.substring(0, s.length - 1)
                }
                return s
            } finally {
                try {
                    c.disconnect()
                } catch (ignored: Throwable) {
                }
            }
        }

        private fun strip(musicRid: String?): String {
            if (musicRid == null) return ""
            return if (musicRid.startsWith("MUSIC_")) musicRid.substring(6) else musicRid
        }

        private fun artwork(shortPath: String?): String? {
            if (shortPath.isNullOrEmpty()) return null
            val idx = shortPath.indexOf('/')
            if (idx < 0) return null
            return "https://img4.kuwo.cn/star/albumcover/1080" + shortPath.substring(idx)
        }

        /** 升级 http 链接到 https（模块禁用明文流量）。 */
        private fun httpsArt(u: String?): String? {
            if (u.isNullOrEmpty()) return null
            if (u.startsWith("http://")) return "https://" + u.substring(7)
            if (u.startsWith("//")) return "https:" + u
            return if (u.startsWith("https://")) u else null
        }

        private fun parseLong(s: String): Long {
            return try {
                s.trim().toLong()
            } catch (t: Throwable) {
                0L
            }
        }

        /** 解析 musiclist 数组（兼容 musicrid 与 id 两种主键）。 */
        private fun songs(arr: JSONArray?): MutableList<Song> {
            val out = ArrayList<Song>()
            if (arr == null) return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val s = Song()
                var rid: String = o.optString("musicrid", "")
                if (rid.isEmpty()) rid = o.optString("id", o.optString("rid", ""))
                s.id = strip(rid)
                s.title = decode(o.optString("name", ""))
                s.artist = decode(o.optString("artist", ""))
                s.album = decode(o.optString("album", ""))
                s.formats = o.optString("formats", "")
                s.artwork = artwork(o.optString("web_albumpic_short", ""))
                val dur = o.optString("duration", o.optString("DURATION", ""))
                try {
                    s.durationMs = (dur.toDouble() * 1000).toInt()
                } catch (ignored: Throwable) {
                }
                if (!s.id.isNullOrEmpty() && !s.title.isNullOrEmpty()) out.add(s)
            }
            return out
        }

        private fun enc(s: String?): String {
            return try {
                URLEncoder.encode(if (s == null) "" else s, "UTF-8")
            } catch (t: Throwable) {
                ""
            }
        }

        /** 解码 HTML 实体（酷我接口返回值含 &amp; &#xxx; 等）。 */
        @JvmStatic
        fun decode(s: String?): String? {
            if (s == null || s.indexOf('&') < 0) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                if (ch == '&') {
                    val semi = s.indexOf(';', i + 1)
                    if (semi > i && semi - i <= 8) {
                        val ent = s.substring(i + 1, semi)
                        val rep = entity(ent)
                        if (rep != null) {
                            sb.append(rep)
                            i = semi + 1
                            continue
                        }
                    }
                }
                sb.append(ch)
                i++
            }
            return sb.toString()
        }

        private fun entity(ent: String): String? {
            if (ent.isEmpty()) return null
            if (ent[0] == '#') {
                return try {
                    val cp = if (ent[1] == 'x' || ent[1] == 'X')
                        Integer.parseInt(ent.substring(2), 16)
                    else
                        Integer.parseInt(ent.substring(1))
                    String(Character.toChars(cp))
                } catch (ignored: Throwable) {
                    null
                }
            }
            return when (ent) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> " "
                else -> null
            }
        }
    }
}
