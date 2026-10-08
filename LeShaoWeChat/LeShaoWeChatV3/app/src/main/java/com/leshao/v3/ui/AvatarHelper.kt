package com.leshao.v3.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.widget.ImageView

import com.leshao.v3.ContextManager
import com.leshao.v3.LogWriter

import java.io.File
import java.io.InputStream
import java.lang.reflect.Modifier
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import de.robv.android.xposed.XposedHelpers

class AvatarHelper {

    companion object {

        private const val TAG = "AvatarHelper"

        private var sAccountDir: String? = null

        @Volatile
        private var sInited = false

        @Volatile
        private var sCachedJ1Class: Class<*>? = null

        @Volatile
        private var sJ1InitDone = false

        private const val MAX_CACHE = 80

        private val sCache: MutableMap<String, Bitmap?> = Collections.synchronizedMap(
            object : LinkedHashMap<String, Bitmap?>() {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>): Boolean {
                    return size > MAX_CACHE
                }
            })

        private val sMain = Handler(Looper.getMainLooper())

        private val sIo: ExecutorService = Executors.newFixedThreadPool(2)

        private fun getWeChatCL(): ClassLoader? {
            return ContextManager.getClassLoader()
        }

        private fun ensureInit() {
            if (sInited) return
            synchronized(AvatarHelper::class.java) {
                if (sInited) return
                try {
                    val ctx = ContextManager.getAppContext() ?: return

                    val cl = getWeChatCL()
                    if (cl != null) initJ1OnMainThread(cl)

                    val dir = findAccountDir(ctx)
                    if (!dir.isNullOrEmpty()) {
                        var d = dir
                        if (!d.endsWith("/")) d += "/"
                        sAccountDir = d
                        sInited = true
                        LogWriter.log(TAG, "init OK, dir=$d")
                    } else {
                        sAccountDir = dir
                        LogWriter.log(TAG, "init ERR: cannot find account dir")
                    }
                } catch (e: Throwable) {
                    LogWriter.log(TAG, "init err: " + e.message)
                }
            }
        }

        private fun findAccountDir(ctx: Context): String? {
            var dir: String?

            // 数据库.md: 内核已解密句柄 qf5.k0.getPath() 给出 EnMicroMsg.db 路径,
            // 其父目录即账号目录(含 avatar/), 最可靠且不依赖混淆方法名更名。
            dir = tryDbPathDir()
            if (dir != null && testDir(dir)) return dir

            dir = tryMethodA()
            if (dir != null && testDir(dir)) return dir

            dir = tryMethodB(ctx)
            if (dir != null && testDir(dir)) return dir

            dir = tryFallback(ctx)
            if (dir != null && testDir(dir)) return dir

            return null
        }

        /**
         * 经内核链路取账号目录:
         * gp0.j1.v(tn3.c4) -> h2.cj() -> ContactStorage(j4).d -> qf5.k0.getPath()
         * getPath() 返回 .../MicroMsg/<hash>/EnMicroMsg.db, 父目录即账号目录。
         */
        private fun tryDbPathDir(): String? {
            return try {
                val cl = getWeChatCL() ?: return null
                val kernel = cl.loadClass("gp0.j1")
                val v = kernel.getDeclaredMethod("v", Class::class.java)
                v.isAccessible = true
                val plugin = v.invoke(null, cl.loadClass("tn3.c4")) ?: return null
                val storage = plugin.javaClass.getMethod("cj").invoke(plugin) ?: return null
                val dField = storage.javaClass.getDeclaredField("d")
                dField.isAccessible = true
                val db = dField.get(storage) ?: return null
                val path = db.javaClass.getMethod("getPath").invoke(db)
                if (path !is String || path.isEmpty()) return null
                val dbFile = File(path)
                val dir = dbFile.parentFile
                if (dir != null && dir.isDirectory) {
                    LogWriter.log(TAG, "DbPath dir: " + dir.absolutePath)
                    dir.absolutePath + "/"
                } else {
                    null
                }
            } catch (ignored: Throwable) {
                null
            }
        }

        private fun initJ1OnMainThread(cl: ClassLoader) {
            if (sJ1InitDone) return
            synchronized(AvatarHelper::class.java) {
                if (sJ1InitDone) return
                try {
                    // Try DexKit-discovered j1 service class first
                    val dexKitJ1 = com.leshao.v3.hook.DexKitHelper.getJ1ServiceClass()
                    if (!dexKitJ1.isNullOrEmpty()) {
                        try {
                            val cls = cl.loadClass(dexKitJ1)
                            sCachedJ1Class = cls
                            for (m in cls.declaredMethods) {
                                m.isAccessible = true
                            }
                            LogWriter.log(TAG, "j1 Class from DexKit OK: " + dexKitJ1)
                            sJ1InitDone = true
                            return
                        } catch (ignored: Throwable) {
                        }
                    }
                    // Fallback: try candidate list
                    val j1Candidates = arrayOf("gp0.j1.j", "gp0.j1", "hm0.j1")
                    for (j1Name in j1Candidates) {
                        try {
                            val cls = cl.loadClass(j1Name)
                            sCachedJ1Class = cls
                            for (m in cls.declaredMethods) {
                                m.isAccessible = true
                            }
                            LogWriter.log(TAG, "j1 Class cached on main thread OK: " + j1Name)
                            break
                        } catch (ignored: Throwable) {
                        }
                    }
                    // Register post-scan callback to upgrade to DexKit result if needed
                    if (sCachedJ1Class?.name != "gp0.j1.j") {
                        com.leshao.v3.hook.DexKitHelper.addPostScanCallback {
                            val dkJ1 = com.leshao.v3.hook.DexKitHelper.getJ1ServiceClass()
                            if (!dkJ1.isNullOrEmpty() && dkJ1 != sCachedJ1Class?.name) {
                                try {
                                    val cls = cl.loadClass(dkJ1)
                                    sCachedJ1Class = cls
                                    for (m in cls.declaredMethods) {
                                        m.isAccessible = true
                                    }
                                    LogWriter.log(TAG, "j1 Class upgraded from DexKit: " + dkJ1)
                                } catch (ignored: Throwable) {
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    LogWriter.log(TAG, "j1 Class not available: " + e.message)
                }
                sJ1InitDone = true
            }
        }

        private fun tryMethodA(): String? {
            try {
                val j1 = sCachedJ1Class ?: return null
                // Try to find a static method that returns an object with a method returning String path
                for (uMethod in j1.declaredMethods) {
                    if (!Modifier.isStatic(uMethod.modifiers)) continue
                    if (uMethod.parameterCount != 0) continue
                    try {
                        val uInstance = uMethod.invoke(null) ?: continue
                        for (hMethod in uInstance.javaClass.declaredMethods) {
                            if (hMethod.returnType == String::class.java && hMethod.parameterCount == 0) {
                                val path = hMethod.invoke(uInstance) as String?
                                if (!path.isNullOrEmpty()) {
                                    LogWriter.log(TAG, "MethodA (" + uMethod.name + "." + hMethod.name + ") OK: " + path)
                                    return path
                                }
                            }
                        }
                    } catch (ignored: Throwable) {
                    }
                }
            } catch (e: Throwable) {
                LogWriter.log(TAG, "MethodA fail: " + e.message)
            }
            return null
        }

        private fun tryMethodB(ctx: Context): String? {
            return try {
                val cl = getWeChatCL()!!
                val mp0b = cl.loadClass("mp0.b")
                val base0 = mp0b.getDeclaredMethod("X").invoke(null) as String?
                if (base0.isNullOrEmpty()) return null
                var base = base0
                if (!base.endsWith("/")) base += "/"

                val uin = getUin(ctx)
                if (uin <= 0) return null

                val hash = try {
                    val hm0b0 = cl.loadClass("hm0.b0")
                    hm0b0.getDeclaredMethod("e", Int::class.javaPrimitiveType).invoke(null, uin.toInt()) as String
                } catch (e: Throwable) {
                    md5("mm$uin")
                }

                val path = base + "MicroMsg/" + hash + "/"
                LogWriter.log(TAG, "MethodB: " + path)
                path
            } catch (e: Throwable) {
                LogWriter.log(TAG, "MethodB fail: " + e.message)
                null
            }
        }

        private fun tryFallback(ctx: Context): String? {
            return try {
                val base = "/data/user/" + (Process.myUid() / 100000) + "/com.tencent.mm/MicroMsg/"
                val microMsgDir = File(base)
                if (!microMsgDir.exists() || !microMsgDir.isDirectory) return null

                val subdirs = microMsgDir.listFiles { dir, name ->
                    name.length == 32 && File(dir, name).isDirectory
                }
                if (subdirs == null) return null

                for (sub in subdirs) {
                    val avatarDir = File(sub, "avatar")
                    if (avatarDir.exists() && avatarDir.isDirectory) {
                        val path = sub.absolutePath + "/"
                        LogWriter.log(TAG, "Fallback found: " + path)
                        return path
                    }
                }
                null
            } catch (e: Throwable) {
                LogWriter.log(TAG, "Fallback fail: " + e.message)
                null
            }
        }

        private fun testDir(dir: String?): Boolean {
            if (dir.isNullOrEmpty()) return false
            val avatarDir = File(dir + "avatar")
            return avatarDir.exists() && avatarDir.isDirectory
        }

        private fun getUin(ctx: Context): Long {
            try {
                val uv = ctx.getSharedPreferences("system_config_prefs", 0)
                    .all["default_uin"]
                if (uv != null) return uv.toString().toLong()
            } catch (ignored: Throwable) {
            }

            try {
                val cl = getWeChatCL()!!
                val y3 = cl.loadClass("y3")
                val uin = y3.getDeclaredMethod("q0").invoke(null) as Long
                if (uin > 0) return uin
            } catch (ignored: Throwable) {
            }

            try {
                val cl = getWeChatCL()!!
                val y3 = cl.loadClass("y3")
                val userInfo = y3.getDeclaredMethod("E0").invoke(null)!!
                val uin = userInfo.javaClass.getDeclaredField("b").get(userInfo) as Long
                if (uin > 0) return uin
            } catch (ignored: Throwable) {
            }

            return 0
        }

        @JvmStatic
        fun getAvatarPath(wxid: String?): String? {
            ensureInit()
            if (wxid.isNullOrEmpty()) return null
            val dir = sAccountDir ?: return null
            val m = md5(wxid)
            if (m.isEmpty()) return null
            return dir + "avatar/" + m.substring(0, 2) + "/" + m.substring(2, 4) + "/user_" + m + ".png"
        }

        @JvmStatic
        fun loadAvatar(wxid: String?, sizePx: Int): Bitmap? {
            if (wxid.isNullOrEmpty()) return null

            val cached = sCache[wxid]
            if (cached != null && !cached.isRecycled) return cached

            val target = if (sizePx > 0) sizePx else 80

            // 1) 微信内存缓存 Bitmap（头像被显示过时最快最准）
            val mem = getCachedAvatarBitmap(wxid)
            if (mem != null && !mem.isRecycled) {
                val round = makeRoundCorner(mem, target)
                sCache.put(wxid, round)
                if (mem !== round) mem.recycle()
                return round
            }

            // 2) 微信头像本地文件路径（无需自己拼 md5 目录）
            val fromPath = loadFromExactPath(getAvatarLocalPath(wxid), wxid, target)
            if (fromPath != null) return fromPath

            // 3) 自拼 md5 磁盘路径兜底
            val fromFile = loadFromLocalFile(wxid, target)
            if (fromFile != null) return fromFile

            // 4) 头像 URL（CDN）下载兜底：主微信/分身微信统一，不依赖本地缓存
            return loadFromUrl(wxid, target)
        }

        /**
         * 异步加载头像：后台线程执行 IO/网络，完成后切回主线程绑定到 ImageView。
         * fallback 先同步显示（通常是首字母占位图），避免列表项头像空白。
         */
        @JvmStatic
        fun loadAvatarAsync(iv: ImageView?, wxid: String?, sizePx: Int, fallback: Bitmap?) {
            if (iv == null || wxid.isNullOrEmpty()) return
            iv.tag = wxid
            if (fallback != null && !fallback.isRecycled) iv.setImageBitmap(fallback)
            sIo.execute {
                try {
                    val bm = loadAvatar(wxid, sizePx)
                    if (bm != null && !bm.isRecycled) {
                        sMain.post {
                            try {
                                if (wxid == iv.tag) iv.setImageBitmap(bm)
                            } catch (t: Throwable) {
                                LogWriter.log(TAG, "sMain setImage fail: " + t.message)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    LogWriter.log(TAG, "loadAvatarAsync fail for " + wxid + ": " + t.message)
                }
            }
        }

        private fun loadFromUrl(wxid: String, target: Int): Bitmap? {
            val url = getAvatarUrl(wxid, false)
            if (url.isNullOrEmpty()) return null
            var conn: HttpURLConnection? = null
            var stream: InputStream? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 8000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36")
                stream = conn.inputStream
                val bm = BitmapFactory.decodeStream(stream)
                if (bm != null && !bm.isRecycled) {
                    val round = makeRoundCorner(bm, target)
                    sCache.put(wxid, round)
                    if (bm !== round) bm.recycle()
                    return round
                }
            } catch (e: Throwable) {
                LogWriter.log(TAG, "loadFromUrl fail for " + wxid + ": " + e.message)
            } finally {
                if (stream != null) {
                    try {
                        stream.close()
                    } catch (ignored: Throwable) {
                    }
                }
                if (conn != null) {
                    try {
                        conn.disconnect()
                    } catch (ignored: Throwable) {
                    }
                }
            }
            return null
        }

        /**
         * 微信原生头像服务：com.tencent.mm.modelavatar.d1
         * 8.0.78: hj() 方法已不存在，改用 findClass 后反射遍历可用方法
         * 读内存缓存 Bitmap；头像未被 UI 显示过时返回 null。
         */
        @JvmStatic
        fun getCachedAvatarBitmap(wxid: String): Bitmap? {
            try {
                val cl = getWeChatCL() ?: return null
                val d1 = XposedHelpers.findClass("com.tencent.mm.modelavatar.d1", cl)
                // 8.0.78: hj() 不存在，尝试查找返回 Bitmap 的静态方法
                for (m in d1.declaredMethods) {
                    if (!Modifier.isStatic(m.modifiers)) continue
                    if (m.returnType != Bitmap::class.java) continue
                    if (m.parameterCount != 0) continue
                    try {
                        m.isAccessible = true
                        val r = m.invoke(null) ?: continue
                        return XposedHelpers.callMethod(r, "f", wxid, false, 0, null) as Bitmap?
                    } catch (ignored: Throwable) {
                    }
                }
                // 兜底：尝试 hj() 的旧逻辑
                try {
                    val r = XposedHelpers.callStaticMethod(d1, "hj")
                    if (r == null) return null
                    return XposedHelpers.callMethod(r, "f", wxid, false, 0, null) as Bitmap?
                } catch (ignored: NoSuchMethodError) {
                }
                return null
            } catch (t: Throwable) {
                LogWriter.log(TAG, "getCachedAvatarBitmap error: " + t.message)
                return null
            }
        }

        /**
         * 微信头像本地文件路径：com.tencent.mm.modelavatar.d1.ij() -> z -> f(username,false,false)
         * 8.0.78: ij() 可能已更名，用 try/catch 兜底
         */
        @JvmStatic
        fun getAvatarLocalPath(wxid: String): String? {
            try {
                val cl = getWeChatCL() ?: return null
                val d1 = XposedHelpers.findClass("com.tencent.mm.modelavatar.d1", cl)
                var z: Any? = null
                // v955: ij() 在 3180 已不存在(实证 NoSuchMethod), 改为枚举静态无参方法
                // 返回非空非基本类型的候选(头像存储服务), 逐个尝试 f(String,boolean,boolean)
                try {
                    z = XposedHelpers.callStaticMethod(d1, "ij")
                } catch (ignored: Throwable) {
                }
                if (z == null) {
                    for (m in d1.declaredMethods) {
                        if (!Modifier.isStatic(m.modifiers)) continue
                        if (m.parameterCount != 0) continue
                        val rt = m.returnType
                        if (rt.isPrimitive || rt == String::class.java || rt == Void::class.java) continue
                        try {
                            m.isAccessible = true
                            val cand = m.invoke(null) ?: continue
                            val p = XposedHelpers.callMethod(cand, "f", wxid, false, false) as String?
                            if (!p.isNullOrEmpty()) return p
                        } catch (ignored: Throwable) {
                        }
                    }
                    return null
                }
                return XposedHelpers.callMethod(z, "f", wxid, false, false) as String?
            } catch (t: Throwable) {
                LogWriter.log(TAG, "getAvatarLocalPath error: " + t.message)
                return null
            }
        }

        /**
         * 微信头像 URL：com.tencent.mm.modelavatar.d1.mj() -> s0 -> x0(username) -> r0
         * 大图 c()，小图 d()。
         * 8.0.78: mj() 可能已更名，用 try/catch 兜底
         */
        @JvmStatic
        fun getAvatarUrl(wxid: String?, big: Boolean): String? {
            if (wxid.isNullOrEmpty()) return null
            try {
                val cl = getWeChatCL() ?: return null
                val d1 = XposedHelpers.findClass("com.tencent.mm.modelavatar.d1", cl)

                // 1) 兼容旧版链路: d1.mj() -> s0.x0(username) -> c()/d()
                try {
                    val s0 = XposedHelpers.callStaticMethod(d1, "mj")
                    val u = resolveAvatarUrl(s0, wxid, big)
                    if (u != null) return u
                } catch (ignored: Throwable) {
                }

                // 2) 8.0.78 mj() 已更名: 枚举 d1 静态无参方法, 找到能产出 URL 的头像服务
                for (m in d1.declaredMethods) {
                    if (!Modifier.isStatic(m.modifiers)) continue
                    if (m.parameterCount != 0) continue
                    val rt = m.returnType
                    if (rt.isPrimitive || rt == String::class.java
                        || rt == Void::class.java || rt == Class::class.java) continue
                    val svc: Any?
                    try {
                        m.isAccessible = true
                        svc = m.invoke(null)
                    } catch (t: Throwable) {
                        continue
                    }
                    if (svc == null) continue
                    val u = resolveAvatarUrl(svc, wxid, big)
                    if (u != null) return u
                }
            } catch (t: Throwable) {
                LogWriter.log(TAG, "getAvatarUrl error: " + t.message)
            }
            return null
        }

        /** 在头像服务对象上查找 (String)->URL持有对象 的方法，兼容 x0/f 等更名。 */
        private fun resolveAvatarUrl(svc: Any?, wxid: String, big: Boolean): String? {
            if (svc == null) return null
            for (m in svc.javaClass.declaredMethods) {
                if (Modifier.isStatic(m.modifiers)) continue
                if (m.parameterCount != 1 || m.parameterTypes[0] != String::class.java) continue
                val rt = m.returnType
                if (rt.isPrimitive || rt == String::class.java || rt == Void::class.java) continue
                val holder: Any?
                try {
                    m.isAccessible = true
                    holder = m.invoke(svc, wxid)
                } catch (t: Throwable) {
                    continue
                }
                if (holder == null) continue
                val u = callUrlGetter(holder, big)
                if (u != null) return u
            }
            return null
        }

        /** 从头像 URL 持有对象取 c()/d()（大图/小图），兼容无参 getter 更名。 */
        private fun callUrlGetter(holder: Any, big: Boolean): String? {
            val names = if (big) arrayOf("c", "d", "a", "b", "e") else arrayOf("d", "c", "a", "b", "e")
            for (n in names) {
                try {
                    val g = holder.javaClass.getDeclaredMethod(n)
                    if (g.parameterCount != 0 || g.returnType != String::class.java) continue
                    g.isAccessible = true
                    val v = g.invoke(holder)
                    if (v is String && v.isNotEmpty()) return v
                } catch (ignored: Throwable) {
                }
            }
            return null
        }

        private fun loadFromExactPath(path: String?, wxid: String, target: Int): Bitmap? {
            if (path.isNullOrEmpty()) return null
            val f = File(path)
            if (!f.exists()) return null
            return try {
                val bm = decodeFile(path, target)
                if (bm != null) {
                    val round = makeRoundCorner(bm, target)
                    sCache.put(wxid, round)
                    if (bm !== round) bm.recycle()
                    round
                } else {
                    null
                }
            } catch (e: Throwable) {
                LogWriter.log(TAG, "load err for " + wxid + " path=" + path + ": " + e.message)
                null
            }
        }

        /**
         * 用微信官方 API 直接把真实头像绑定到 ImageView（跨进程异步加载）。
         * 这是最可靠的头像显示方式，避免依赖磁盘路径/账号目录查找。
         * @return true 表示已交给微信加载；false 表示调用失败，需回退其它方式
         */
        @JvmStatic
        fun bindAvatar(iv: ImageView?, wxid: String?): Boolean {
            if (iv == null || wxid.isNullOrEmpty()) return false
            try {
                val cl = getWeChatCL() ?: return false

                // 方案1：AnyProcessAvatarAttacher（feature.avatar.s），文档推荐跨进程绑定
                try {
                    val attacher = XposedHelpers.findClass("com.tencent.mm.feature.avatar.s", cl)
                    if (tryBind(attacher, iv, wxid, "feature.avatar.s", null)) return true
                } catch (e1: Throwable) {
                    LogWriter.log(TAG, "feature.avatar.s 不可用: " + e1.message)
                }

                // 方案2：pluginsdk.ui.a 的静态头像方法
                try {
                    val a = XposedHelpers.findClass("com.tencent.mm.pluginsdk.ui.a", cl)
                    if (tryBind(a, iv, wxid, "pluginsdk.ui.a", null)) return true
                } catch (e2: Throwable) {
                    LogWriter.log(TAG, "pluginsdk.ui.a 不可用: " + e2.message)
                }
            } catch (e: Throwable) {
                LogWriter.log(TAG, "bindAvatar fail for " + wxid + ": " + e.message)
            }
            return false
        }

        /**
         * 在指定类里寻找「(ImageView 或其父类, String)」的静态头像绑定方法并调用。
         * 优先按 preferredName 精确匹配，其次枚举所有静态方法兜底。
         */
        private fun tryBind(c: Class<*>, iv: ImageView, wxid: String, tag: String, preferredName: String?): Boolean {
            // 1) 精确方法名 + (ImageView, String)
            if (preferredName != null) {
                if (invokeExact(c, preferredName, ImageView::class.java, iv, wxid, tag)) return true
                if (invokeExact(c, preferredName, android.view.View::class.java, iv, wxid, tag)) return true
            }
            // 2) 枚举所有静态方法，匹配 (ImageView/View 兼容, String)
            for (m in c.declaredMethods) {
                if (!Modifier.isStatic(m.modifiers)) continue
                val pts = m.parameterTypes
                if (pts.size != 2) continue
                if (pts[1] != String::class.java) continue
                if (!pts[0].isAssignableFrom(ImageView::class.java)) continue
                try {
                    m.isAccessible = true
                    m.invoke(null, iv, wxid)
                    LogWriter.log(TAG, "bindAvatar OK via " + tag + "." + m.name
                            + "(" + pts[0].simpleName + ",String)")
                    return true
                } catch (inv: Throwable) {
                    LogWriter.log(TAG, "bindAvatar invoke " + tag + "." + m.name + " fail: " + inv.message)
                }
            }
            return false
        }

        private fun invokeExact(c: Class<*>, name: String, viewType: Class<*>,
                               iv: ImageView, wxid: String, tag: String): Boolean {
            val m = try {
                c.getDeclaredMethod(name, viewType, String::class.java)
            } catch (ignored: NoSuchMethodException) {
                return false
            }
            return try {
                if (Modifier.isStatic(m.modifiers)) {
                    m.isAccessible = true
                    m.invoke(null, iv, wxid)
                    LogWriter.log(TAG, "bindAvatar OK via " + tag + "." + name + "("
                            + viewType.simpleName + ",String)")
                    true
                } else {
                    false
                }
            } catch (t: Throwable) {
                LogWriter.log(TAG, "bindAvatar " + tag + "." + name + "(" + viewType.simpleName
                        + ",String) invoke fail: " + t.message)
                false
            }
        }

        private fun loadFromLocalFile(wxid: String, target: Int): Bitmap? {
            val m = md5(wxid)
            if (m.isEmpty()) return null

            ensureInit()
            val dir = sAccountDir ?: return null

            val base = dir + "avatar/" + m.substring(0, 2) + "/" + m.substring(2, 4) + "/"
            val paths = arrayOf(
                base + "user_" + m + ".png",
                base + "user_" + m + ".jpg",
                base + m + ".png",
                base + m + ".jpg",
                base + "user_" + m + ".hd"
            )

            for (path in paths) {
                val f = File(path)
                if (!f.exists()) continue
                try {
                    val bm = decodeFile(path, target)
                    if (bm != null) {
                        val round = makeRoundCorner(bm, target)
                        sCache.put(wxid, round)
                        if (bm !== round) bm.recycle()
                        return round
                    }
                } catch (e: Throwable) {
                    LogWriter.log(TAG, "load err for " + wxid + " path=" + path + ": " + e.message)
                }
            }
            return null
        }

        private fun loadFromWeChatApi(wxid: String, target: Int): Bitmap? {
            try {
                val cl = getWeChatCL() ?: return null

                // com.tencent.mm.pluginsdk.ui.a$b is WeChat's avatar display helper
                val avatarClass = cl.loadClass("com.tencent.mm.pluginsdk.ui.a\$b")
                val ctx = ContextManager.getAppContext() ?: return null

                // Try a.b.a(Context, username, ...) -> Drawable
                for (methodName in arrayOf("a", "b", "c")) {
                    try {
                        for (method in avatarClass.declaredMethods) {
                            if (method.name != methodName) continue
                            val params = method.parameterTypes
                            if (params.size < 2) continue
                            if (params[0] != Context::class.java) continue
                            if (params[1] != String::class.java) continue

                            val args = arrayOfNulls<Any>(params.size)
                            args[0] = ctx
                            args[1] = wxid
                            for (i in 2 until params.size) {
                                if (params[i] == Int::class.javaPrimitiveType) args[i] = 0
                                else if (params[i] == Boolean::class.javaPrimitiveType) args[i] = false
                                else if (params[i] == Float::class.javaPrimitiveType) args[i] = 0f
                                else args[i] = null
                            }
                            method.isAccessible = true
                            val result = method.invoke(null, *args)
                            if (result is Bitmap) {
                                val round = makeRoundCorner(result, target)
                                sCache.put(wxid, round)
                                return round
                            }
                            if (result is Drawable) {
                                val bm = drawableToBitmap(result, target)
                                if (bm != null) {
                                    val round = makeRoundCorner(bm, target)
                                    sCache.put(wxid, round)
                                    if (bm !== round) bm.recycle()
                                    return round
                                }
                            }
                            break
                        }
                    } catch (ignored: Throwable) {
                    }
                }
            } catch (e: Throwable) {
                LogWriter.log(TAG, "WeChat API load fail: " + e.message)
            }
            return null
        }

        private fun drawableToBitmap(drawable: Drawable?, size: Int): Bitmap? {
            if (drawable == null) return null
            if (drawable is BitmapDrawable) {
                return drawable.bitmap
            }
            return try {
                val bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bm)
                drawable.setBounds(0, 0, size, size)
                drawable.draw(canvas)
                bm
            } catch (e: Throwable) {
                null
            }
        }

        private fun decodeFile(path: String, target: Int): Bitmap? {
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, opts)

            var sample = 1
            while (opts.outWidth / sample > target * 2 || opts.outHeight / sample > target * 2) {
                sample *= 2
            }
            opts.inSampleSize = sample
            opts.inJustDecodeBounds = false

            return BitmapFactory.decodeFile(path, opts)
        }

        @JvmStatic
        fun makeRoundCorner(source: Bitmap?, size: Int): Bitmap? {
            if (source == null) return null
            return try {
                val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(output)
                val paint = Paint()
                paint.isAntiAlias = true
                val rect = Rect(0, 0, size, size)
                val rectF = RectF(rect)
                canvas.drawRoundRect(rectF, size / 2f, size / 2f, paint)
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                canvas.drawBitmap(source, null, rect, paint)
                output
            } catch (e: Throwable) {
                source
            }
        }

        /** 首字母占位头像(圆形底色 + 文字), 用于真实头像加载前的兜底显示。 */
        @JvmStatic
        fun letterAvatar(letter: String?, sizePx: Int): Bitmap {
            val size = if (sizePx > 0) sizePx else 80
            val text = if (letter.isNullOrEmpty()) "?" else letter
            val bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bm)
            val bg = Paint()
            bg.isAntiAlias = true
            bg.color = AppColors.primary()
            canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), size / 2f, size / 2f, bg)
            val txt = Paint()
            txt.isAntiAlias = true
            txt.color = 0xFFFFFFFF.toInt()
            txt.textSize = size * 0.45f
            txt.textAlign = Paint.Align.CENTER
            txt.isFakeBoldText = true
            val y = size / 2f - (txt.descent() + txt.ascent()) / 2f
            canvas.drawText(text.substring(0, 1).uppercase(), size / 2f, y, txt)
            return bm
        }

        @JvmStatic
        fun clearCache() {
            sCache.clear()
        }

        @JvmStatic
        fun resetInit() {
            sInited = false
            sAccountDir = null
        }

        private fun md5(input: String): String {
            return try {
                val md = MessageDigest.getInstance("MD5")
                val d = md.digest(input.toByteArray(charset("UTF-8")))
                val sb = StringBuilder()
                for (b in d) sb.append(String.format("%02x", b))
                sb.toString()
            } catch (e: Throwable) {
                ""
            }
        }
    }
}
