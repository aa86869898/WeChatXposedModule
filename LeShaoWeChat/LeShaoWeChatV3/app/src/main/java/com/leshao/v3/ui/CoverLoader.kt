package com.leshao.v3.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.leshao.v3.ContextManager
import com.leshao.v3.LogWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.HashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 封面图加载器：内存 LruCache + 磁盘缓存 + 网络下载，异步回调切回主线程。
 *
 * 仅用于展示型图片（在线音乐封面等），不做圆角裁剪，由调用方用 ImageView
 * 的缩放/父容器 outline 处理。资源受限场景下也不会无限占内存。
 */
object CoverLoader {

    private const val TAG = "CoverLoader"
    private const val UA = "Mozilla/5.0 (Linux; Android 13)"
    private const val DEFAULT_SIZE = 512

    private val IO: ExecutorService = Executors.newFixedThreadPool(3)
    private val MAIN = Handler(Looper.getMainLooper())

    /** 同一 URL 正在下载时的等待者；下载完成后统一回调，避免后续请求拿不到图。 */
    private val PENDING: MutableMap<String, MutableList<BitmapCallback>> =
        Collections.synchronizedMap(HashMap())

    @Volatile
    private var sCache: LruCache<String, Bitmap>? = null

    fun interface BitmapCallback {
        fun onBitmap(bm: Bitmap?)
    }

    private fun cache(): LruCache<String, Bitmap> {
        var c = sCache
        if (c == null) {
            synchronized(CoverLoader::class.java) {
                c = sCache
                if (c == null) {
                    val maxKb = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
                    c = object : LruCache<String, Bitmap>(maxKb) {
                        override fun sizeOf(key: String, value: Bitmap): Int {
                            return value.byteCount / 1024
                        }
                    }
                    sCache = c
                }
            }
        }
        return c!!
    }

    /** 加载并绑定到 ImageView；内部用 tag 防止复用错位。 */
    @JvmStatic
    fun load(url: String?, iv: ImageView?) {
        if (iv == null) return
        if (url == null || url.isEmpty()) {
            iv.tag = ""
            iv.setImageDrawable(null)
            return
        }
        iv.tag = url
        val hit = cache().get(url)
        if (hit != null && !hit.isRecycled) {
            iv.setImageBitmap(hit)
            return
        }
        val w = iv.width
        loadBitmap(url, if (w > 0) w else 320) { bm ->
            if (bm != null && !bm.isRecycled && url == iv.tag) {
                iv.setImageBitmap(bm)
            }
        }
    }

    /** 加载指定尺寸的位图（max*2 像素解码上限），主线程回调。 */
    @JvmStatic
    fun loadBitmap(url: String?, sizePx: Int, cb: BitmapCallback?) {
        if (url == null || url.isEmpty()) {
            cb?.onBitmap(null)
            return
        }
        val c = cache()
        val hit = c.get(url)
        if (hit != null && !hit.isRecycled) {
            cb?.onBitmap(hit)
            return
        }
        val target = if (sizePx > 0) sizePx else DEFAULT_SIZE
        var owner: Boolean
        synchronized(PENDING) {
            var waiters = PENDING[url]
            owner = waiters == null
            if (owner) {
                waiters = ArrayList()
                PENDING[url] = waiters
            }
            if (cb != null) waiters!!.add(cb)
        }
        if (!owner) return
        IO.execute {
            var bm: Bitmap? = null
            try {
                bm = loadSync(url, target)
            } catch (t: Throwable) {
                LogWriter.log(TAG, "load fail $url: ${t.message}")
            }
            val out = bm
            if (out != null && !out.isRecycled) c.put(url, out)
            val waiters: List<BitmapCallback>?
            synchronized(PENDING) {
                waiters = PENDING.remove(url)
            }
            if (waiters != null) {
                for (w in waiters) {
                    MAIN.post { w.onBitmap(out) }
                }
            }
        }
    }

    @Throws(Exception::class)
    private fun loadSync(url: String, target: Int): Bitmap? {
        val disk = diskFile(url)
        if (disk != null && disk.isFile && disk.length() > 0) {
            val b = decodeFile(disk, target)
            if (b != null) return b
        }
        var conn: HttpURLConnection? = null
        var ins: InputStream? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept-Encoding", "identity")
            val code = conn.responseCode
            if (code < 200 || code >= 300) {
                LogWriter.log(TAG, "http $code $url")
                return null
            }
            ins = conn.inputStream
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(16384)
            var n: Int
            while (ins.read(buf).also { n = it } > 0) bos.write(buf, 0, n)
            val data = bos.toByteArray()
            if (disk != null) {
                try {
                    val p = disk.parentFile
                    if (p != null && !p.exists()) p.mkdirs()
                    val fo = FileOutputStream(disk)
                    fo.write(data)
                    fo.close()
                    pruneDisk(p)
                } catch (ignored: Throwable) {}
            }
            val decoded = decodeBytes(data, target)
            LogWriter.log(TAG, "ok " + data.size + "B -> "
                + (if (decoded == null) "null" else decoded.width.toString() + "x" + decoded.height)
                + " " + url)
            return decoded
        } finally {
            if (ins != null) {
                try {
                    ins.close()
                } catch (ignored: Throwable) {}
            }
            if (conn != null) {
                try {
                    conn.disconnect()
                } catch (ignored: Throwable) {}
            }
        }
    }

    private fun decodeBytes(data: ByteArray, target: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(data, 0, data.size, o)
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = sampleFor(o.outWidth, o.outHeight, target)
        return BitmapFactory.decodeByteArray(data, 0, data.size, o2)
    }

    private fun decodeFile(f: File, target: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(f.absolutePath, o)
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = sampleFor(o.outWidth, o.outHeight, target)
        return BitmapFactory.decodeFile(f.absolutePath, o2)
    }

    private fun sampleFor(w: Int, h: Int, target: Int): Int {
        var sample = 1
        val cap = target * 2
        while (w / sample > cap || h / sample > cap) sample *= 2
        return sample
    }

    private fun diskFile(url: String): File? {
        try {
            val ctx = ContextManager.getAppContext()
            if (ctx == null) return null
            var dir = ctx.getExternalFilesDir("covers")
            if (dir == null) return null
            if (!dir.exists()) dir.mkdirs()
            return File(dir, md5(url) + ".img")
        } catch (t: Throwable) {
            return null
        }
    }

    /** 磁盘封面缓存上限; 超出按 lastModified 升序淘汰最旧。 */
    private const val MAX_DISK_FILES = 200

    private fun pruneDisk(dir: File?) {
        try {
            if (dir == null || !dir.isDirectory()) return
            val files = dir.listFiles() ?: return
            if (files.size <= MAX_DISK_FILES) return
            files.sortWith(Comparator { a, b -> java.lang.Long.compare(b.lastModified(), a.lastModified()) })
            for (i in MAX_DISK_FILES until files.size) {
                try {
                    files[i].delete()
                } catch (ignored: Throwable) {}
            }
        } catch (ignored: Throwable) {}
    }

    private fun md5(s: String): String {
        try {
            val md = MessageDigest.getInstance("MD5")
            val d = md.digest(s.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder()
            for (b in d) sb.append(String.format("%02x", b))
            return sb.toString()
        } catch (t: Throwable) {
            return Integer.toHexString(s.hashCode())
        }
    }
}