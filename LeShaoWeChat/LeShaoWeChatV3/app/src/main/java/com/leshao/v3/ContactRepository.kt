package com.leshao.v3

import android.content.Context
import android.database.Cursor

import com.leshao.ai.hook.wechat.StorageHub
import com.leshao.v3.db.DatabaseProvider
import com.leshao.v3.hook.DexKitHelper
import com.leshao.v3.hook.VersionCompat
import com.leshao.v3.model.ContactCard
import com.leshao.v3.model.ContactCard.Category

import java.lang.reflect.Method
import java.util.ArrayList
import java.util.Collections

import de.robv.android.xposed.XposedHelpers

class ContactRepository {

    class DbRow internal constructor(
        @JvmField val cols: Array<String?>,
        @JvmField val vals: Array<String?>
    ) {
        fun get(col: String): String? {
            for (i in cols.indices) {
                if (col.equals(cols[i], ignoreCase = true)) return vals[i]
            }
            return null
        }
    }

    companion object {

        private const val TAG = "ContactRepo"

        @Volatile
        private var sFriends: List<ContactCard>? = null

        @Volatile
        private var sGroups: List<ContactCard>? = null

        @Volatile
        private var sServiceAccounts: List<ContactCard>? = null

        @Volatile
        private var sLoading = false

        private val sDbCapturedLock = Object()

        @Volatile
        private var sDbCaptured = false

        /** v1025: 优先返回微信真实 ClassLoader(反查自微信运行时对象), 解决内核/存储类静态状态不共享问题 */
        private fun runtimeCl(): ClassLoader? {
            val real = DatabaseProvider.getRealClassLoader()
            return real ?: ContextManager.getClassLoader()
        }

        /** DatabaseProvider 捕获到微信自开 DB 时回调: 唤醒等待中的加载线程并触发重载 */
        @JvmStatic
        fun onDatabaseCaptured() {
            synchronized(sDbCapturedLock) {
                sDbCaptured = true
                sDbCapturedLock.notifyAll()
            }
            // 若当前没有加载线程, 触发一次重载
            if (!sLoading) {
                loadAsync(null, true)
            }
        }

        @JvmStatic
        fun getFriends(): List<ContactCard> {
            return sFriends ?: Collections.emptyList<ContactCard>()
        }

        @JvmStatic
        fun getGroups(): List<ContactCard> {
            return sGroups ?: Collections.emptyList<ContactCard>()
        }

        @JvmStatic
        fun getServiceAccounts(): List<ContactCard> {
            return sServiceAccounts ?: Collections.emptyList<ContactCard>()
        }

        @JvmStatic
        fun getAll(): List<ContactCard> {
            val all = ArrayList<ContactCard>()
            sFriends?.let { all.addAll(it) }
            sGroups?.let { all.addAll(it) }
            sServiceAccounts?.let { all.addAll(it) }
            return all
        }

        @JvmStatic
        fun refresh() {
            // v955(问题17): 与 loadAll/enumerateRContact 的数据提交共用 ContactRepository.class 锁,
            // 避免刷新清空与加载写入交叉导致读到半更新数据或覆盖丢失。
            synchronized(ContactRepository::class.java) {
                sFriends = null
                sGroups = null
                sServiceAccounts = null
            }
        }

        @JvmStatic
        fun loadAsync(onDone: Runnable?) {
            loadAsync(onDone, false)
        }

        @Synchronized
        private fun loadAsync(onDone: Runnable?, retry: Boolean) {
            if (!retry && (sFriends != null || sGroups != null)) {
                LogWriter.log(TAG, "already loaded, " + size(sFriends) + " friends, " + size(sGroups) + " groups")
                onDone?.run()
                return
            }
            if (sLoading) {
                LogWriter.log(TAG, "loading in progress, queuing callback")
                Thread({
                    try {
                        val start = System.currentTimeMillis()
                        while (sLoading && System.currentTimeMillis() - start < 30000) {
                            Thread.sleep(200)
                        }
                    } catch (ignored: InterruptedException) {
                    }
                    onDone?.run()
                }, "ContactRepoWait").start()
                return
            }
            sLoading = true
            Thread({
                try {
                    val ctx = ContextManager.getAppContext()
                    if (ctx != null) loadAll(ctx)
                } catch (t: Throwable) {
                    LogWriter.log(TAG, "load err: " + t.javaClass.simpleName + " " + t.message)
                } finally {
                    sLoading = false
                    onDone?.run()
                }
            }, "ContactRepoLoader").start()
        }

        private fun size(list: List<*>?): Int {
            return list?.size ?: 0
        }

        private fun loadAll(ctx: Context) {
            val t0 = System.currentTimeMillis()

            var db: Any? = null
            var capturedDb = false
            try {
                val cl = runtimeCl()
                if (cl == null) {
                    LogWriter.log(TAG, "cl null")
                    return
                }

                // v1026: 首选进程内已解密句柄(参考《数据库.md》), 内核就绪后毫秒级返回,
                // 避免旧方案死等 DatabaseProvider 捕获 15s(实测从不捕获, 纯浪费)。
                val ipDeadline = System.currentTimeMillis() + 5000L
                while (db == null && System.currentTimeMillis() < ipDeadline) {
                    db = inProcessContactDb()
                    if (db != null) {
                        capturedDb = true
                        break
                    }
                    try {
                        Thread.sleep(500L)
                    } catch (ignored: InterruptedException) {
                    }
                }
                // 次选 DatabaseProvider 捕获的微信自开 DB(短等待, 不拖慢)。
                if (db == null) {
                    val capDeadline = System.currentTimeMillis() + 1500L
                    while (db == null && System.currentTimeMillis() < capDeadline) {
                        db = DatabaseProvider.getDatabase()
                        if (db != null) {
                            capturedDb = true
                            break
                        }
                        synchronized(sDbCapturedLock) {
                            if (sDbCaptured) {
                                val got = DatabaseProvider.getDatabase()
                                if (got != null) {
                                    db = got
                                    capturedDb = true
                                }
                            } else {
                                try {
                                    sDbCapturedLock.wait(300L)
                                } catch (ignored: InterruptedException) {
                                }
                            }
                        }
                    }
                }
                val curDb = db
                if (curDb == null) {
                    LogWriter.log(TAG, "in-process/DatabaseProvider 均不可用, fallback to direct open")
                } else {
                    LogWriter.log(TAG, "using in-process/captured DB: " + curDb.javaClass.name
                        + " in " + (System.currentTimeMillis() - t0) + "ms")
                }

                val uin = getUin(ctx)
                if (uin <= 0) {
                    LogWriter.log(TAG, "uin=0")
                    return
                }

                // v955(问题14): baseDir/imei/dbHash/dbPath/password 仅在裸开开启时才计算,
                // 关闭时(getBaseDir/getImei/getDbHash 反射与 md5)不再做无用计算。
                var baseDir: String? = null
                if (VersionCompat.ENABLE_RAW_DB_OPEN) {
                    baseDir = VersionCompat.getBaseDir(cl, ctx)
                    if (baseDir != null && !baseDir.endsWith("/")) baseDir += "/"
                }

                // v1020: 恢复 v980 直接 DB 打开路径（v980 实机验证有效: db opened in 29ms）。
                // v1019 曾将 enumerateRContact 提到最前、DB 改由 openEnMicroDb 爆破，实机均失败，
                // 现回退为「固定路径+固定密码 直接打开」首选，枚举与爆破降级为兜底。
                //
                // v1022: 根因=微信内核未就绪时 CsoLoader 未被初始化，kh5.f.w 直接抛
                // "Missing initialization before executing, please invoke CsoLoader.initialize first"。
                // v980 成功时内核已 init（j1.v OK）→ CsoLoader 已由微信初始化。
                // 这里打开失败时轮询重试（最多 ~20s），等待微信完成内核/CsoLoader 初始化。
                if (VersionCompat.ENABLE_RAW_DB_OPEN) {
                    val imei = VersionCompat.getImei(cl)
                    val dbHash = VersionCompat.getDbHash(cl, uin.toInt())
                    val dbPath = baseDir + "MicroMsg/" + dbHash + "/EnMicroMsg.db"
                    val password = md5(imei + uin).substring(0, 7)
                    LogWriter.log(TAG, "opening db: " + dbHash + "/EnMicroMsg.db")
                    val dbCls = VersionCompat.findDbOpenerClass(cl)

                    val openDeadline = System.currentTimeMillis() + 20000L
                    var openAttempt = 0
                    while (db == null && System.currentTimeMillis() < openDeadline) {
                        openAttempt++
                        if (openAttempt > 1) {
                            try {
                                Thread.sleep(1500L)
                            } catch (ignored: InterruptedException) {
                            }
                        }
                        if (dbCls != null) {
                            db = VersionCompat.openDatabase(dbCls, dbPath, password)
                            if (db == null) {
                                db = VersionCompat.openDatabaseWcdb(cl, dbPath, password)
                            }
                        }
                        if (db == null) {
                            LogWriter.log(TAG, "db open attempt " + openAttempt
                                    + " failed (csoReady=" + VersionCompat.isCsoLoaderReady() + "), retrying")
                        }
                    }
                } else {
                    LogWriter.log(TAG, "RAW_DB_OPEN disabled for safety, skipping direct open")
                }
                if (db == null) {
                    // 兜底1: v1019 进程内 rcontact 枚举（不依赖 DB 打开）
                    if (enumerateRContact()) {
                        LogWriter.log(TAG, "enumerateRContact ok: " + (sFriends!!.size + sGroups!!.size
                                + sServiceAccounts!!.size) + " rows in " + (System.currentTimeMillis() - t0) + "ms")
                        return
                    }
                    if (VersionCompat.ENABLE_RAW_DB_OPEN) {
                        LogWriter.log(TAG, "enumerateRContact empty/failed, openEnMicroDb fallback")
                        // 兜底2: 目录名候选化 + 密码候选爆破
                        db = VersionCompat.openEnMicroDb(cl, baseDir, uin)
                    } else {
                        LogWriter.log(TAG, "enumerateRContact empty/failed, openEnMicroDb fallback disabled for safety")
                    }
                }
                val dbh = db ?: run {
                    LogWriter.log(TAG, "db open FAILED")
                    return
                }
                LogWriter.log(TAG, "db opened in " + (System.currentTimeMillis() - t0) + "ms")

                // 微信 j4.t()/j4.O()/j4.K() + j4.m() 精确: 正常联系人唯一定义
                // verifyFlag 为验证状态(非联系人类型), 不过滤, 避免漏掉"被对方删除的单向好友"
                val sqlFriends = "SELECT username, nickname, alias, conRemark, pyInitial, quanPin, " + "conRemarkPYFull, type, showHead, contactLabelIds, createTime " + "FROM rcontact WHERE deleteFlag = 0 " + "AND (type & 1) != 0 " + "AND (type & 32) = 0 " + "AND (type & 8) = 0 " + "AND (type & 64) = 0 " + "AND username NOT LIKE '%@chatroom' " + "AND username NOT LIKE '%@im.chatroom' " + "AND username NOT LIKE '%@openim' " + "AND username NOT LIKE '%@micromsg.qq.com' " + "AND username NOT LIKE 'gh_%' " + "ORDER BY CASE WHEN length(conRemarkPYFull) > 0 " + "THEN upper(conRemarkPYFull) ELSE upper(quanPin) END ASC"

                val t1 = System.currentTimeMillis()
                val tmpFriends = query(dbh, sqlFriends, Category.FRIEND)
                synchronized(ContactRepository::class.java) { sFriends = tmpFriends }
                LogWriter.log(TAG, "friends: " + tmpFriends.size + " rows in " + (System.currentTimeMillis() - t1) + "ms")

                // 群聊
                val sqlGroups = "SELECT username, nickname, alias, conRemark, pyInitial, quanPin, " + "conRemarkPYFull, type, showHead, contactLabelIds, createTime " + "FROM rcontact WHERE deleteFlag = 0 " + "AND (username LIKE '%@chatroom' OR username LIKE '%@im.chatroom') " + "ORDER BY CASE WHEN length(conRemarkPYFull) > 0 " + "THEN upper(conRemarkPYFull) ELSE upper(quanPin) END ASC"

                val t2 = System.currentTimeMillis()
                val tmpGroups = query(dbh, sqlGroups, Category.GROUP)
                synchronized(ContactRepository::class.java) { sGroups = tmpGroups }
                LogWriter.log(TAG, "groups: " + tmpGroups.size + " rows in " + (System.currentTimeMillis() - t2) + "ms")

                // 服务号: 公众号 + 订阅号 + 服务号 (gh_ 前缀)
                val sqlService = "SELECT username, nickname, alias, conRemark, pyInitial, quanPin, " + "conRemarkPYFull, type, showHead, contactLabelIds, createTime " + "FROM rcontact WHERE deleteFlag = 0 " + "AND username LIKE 'gh_%' " + "ORDER BY CASE WHEN length(conRemarkPYFull) > 0 " + "THEN upper(conRemarkPYFull) ELSE upper(quanPin) END ASC"

                val t3 = System.currentTimeMillis()
                val tmpService = query(dbh, sqlService, Category.OFFICIAL)
                synchronized(ContactRepository::class.java) { sServiceAccounts = tmpService }
                LogWriter.log(TAG, "service: " + tmpService.size + " rows in " + (System.currentTimeMillis() - t3) + "ms")

                // 诊断: 找出混入好友列表的非正常联系人
                diagnoseContacts(dbh)
                diagnoseStarContacts(dbh)

                LogWriter.log(TAG, "total: " + (sFriends!!.size + sGroups!!.size + sServiceAccounts!!.size)
                        + " rows in " + (System.currentTimeMillis() - t0) + "ms")

            } catch (t: Throwable) {
                LogWriter.log(TAG, "loadAll err: " + t.javaClass.simpleName + " " + t.message)
            } finally {
                // v1024: 捕获的 DB 属于微信自身进程, 绝不能 close; 仅关闭模块自开的 DB
                val dbObj = db
                if (dbObj != null && !capturedDb) {
                    try {
                        val close = dbObj.javaClass.getDeclaredMethod("c")
                        close.invoke(dbObj)
                    } catch (ignored: Throwable) {
                    }
                }
            }
        }

        /**
         * v1019: 进程内 rcontact 全量枚举（主数据源，不依赖 DB 打开）。
         * <p>
         * 数据链：优先 StorageHub.rcontactStorage()；失败则按 b41.h9.d().b().r() 直连；
         * 再兜底 j1.v(tn3.c4)->h2.cj()。取到游标后按列名读取，分类与 DB SQL 对齐：
         * @chatroom→群、gh_→服务号、type 过滤→好友。
         *
         * @return 是否有任一类别数据
         */
        private fun enumerateRContact(): Boolean {
            return try {
                val storage = rcontactStorageInstance()
                if (storage == null) {
                    LogWriter.log(TAG, "enumerateRContact: rcontactStorage null")
                    return false
                }
                val cursor = XposedHelpers.callMethod(storage, "r") as Cursor?
                if (cursor == null) {
                    LogWriter.log(TAG, "enumerateRContact: cursor null")
                    return false
                }
                val friends = ArrayList<ContactCard>()
                val groups = ArrayList<ContactCard>()
                val service = ArrayList<ContactCard>()
                try {
                    while (cursor.moveToNext()) {
                        val card = readRowByNames(cursor) ?: continue
                        val u = card.username
                        if (u == null || u.isEmpty()) continue
                        if (u.endsWith("@chatroom") || u.endsWith("@im.chatroom")) {
                            card.category = Category.GROUP
                            groups.add(card)
                        } else if (u.startsWith("gh_")) {
                            card.category = Category.OFFICIAL
                            service.add(card)
                        } else {
                            val t = card.type
                            if ((t and 1) == 0 || (t and 32) != 0 || (t and 8) != 0 || (t and 64) != 0) continue
                            card.category = Category.FRIEND
                            friends.add(card)
                        }
                    }
                } finally {
                    try {
                        cursor.close()
                    } catch (ignored: Throwable) {
                    }
                }
                synchronized(ContactRepository::class.java) {
                    sFriends = friends
                    sGroups = groups
                    sServiceAccounts = service
                }
                LogWriter.log(TAG, "enumerateRContact: friends=" + friends.size
                        + " groups=" + groups.size + " service=" + service.size)
                !friends.isEmpty() || !groups.isEmpty() || !service.isEmpty()
            } catch (t: Throwable) {
                LogWriter.log(TAG, "enumerateRContact err: " + t.javaClass.simpleName + " " + t.message)
                false
            }
        }

        /** 获取 rcontact 存储实例：StorageHub → b41.h9 直连 → j1.v(tn3.c4) 兜底。 */
        private fun rcontactStorageInstance(): Any? {
            try {
                val hub = StorageHub.get()
                val s = hub.rcontactStorage()
                if (s != null) return s
            } catch (t: Throwable) {
                LogWriter.log(TAG, "rcontactStorageInstance(StorageHub) err: " + t.message)
            }
            try {
                val cl = runtimeCl()
                if (cl == null) return null
                val h9 = XposedHelpers.findClass("b41.h9", cl)
                val hub = XposedHelpers.callStaticMethod(h9, "d")
                val acc = if (hub != null) XposedHelpers.callMethod(hub, "b")
                    else XposedHelpers.callStaticMethod(h9, "b")
                if (acc != null) {
                    val rcs = XposedHelpers.callMethod(acc, "r")
                    if (rcs != null) return rcs
                }
            } catch (t: Throwable) {
                LogWriter.log(TAG, "rcontactStorageInstance(b41.h9) err: " + t.message)
            }
            return try {
                val cl = runtimeCl()
                if (cl == null) return null
                // v955(问题6): j1 类名优先取 DexKit 扫描结果, 不再硬编码 gp0.j1
                val c4 = j1ServiceLookup(cl, "tn3.c4", "tn3.d4") ?: return null
                val h2 = XposedHelpers.findClass("com.tencent.mm.plugin.messenger.foundation.h2", cl).cast(c4)
                XposedHelpers.callMethod(h2, "cj")
            } catch (t: Throwable) {
                LogWriter.log(TAG, "rcontactStorageInstance(j1.v) err: " + t.message)
                null
            }
        }

        /**
         * v955(问题6): j1 服务定位器查找。优先 DexKitHelper.getJ1ServiceClass(), 回退 gp0.j1/fp0.j1;
         * 定位方法兼容 3180 的 v(Class) 与旧版 s(Class)。任一失败返回 null。
         */
        private fun j1ServiceLookup(cl: ClassLoader, vararg keyClassNames: String?): Any? {
            var j1: Class<*>? = null
            val dexJ1 = DexKitHelper.getJ1ServiceClass()
            for (cand in arrayOf(dexJ1, "gp0.j1", "fp0.j1")) {
                if (cand == null || cand.isEmpty()) continue
                try {
                    j1 = XposedHelpers.findClass(cand, cl)
                    break
                } catch (ignored: Throwable) {
                }
            }
            if (j1 == null) return null
            var key: Class<*>? = null
            for (kn in keyClassNames) {
                if (kn == null) continue
                try {
                    key = XposedHelpers.findClass(kn, cl)
                    break
                } catch (ignored: Throwable) {
                }
            }
            if (key == null) return null
            for (mn in arrayOf("v", "s")) {
                try {
                    val r = XposedHelpers.callStaticMethod(j1, mn, key)
                    if (r != null) return r
                } catch (ignored: Throwable) {
                }
            }
            return null
        }

        /**
         * v1026: 进程内联系人 DB 句柄(参考《数据库.md》)。
         * 链路: gp0.j1.v(tn3.c4) → h2.cj() → ContactStorage(j4) → 字段 d = qf5.k0。
         * qf5.k0 是微信进程内已解密的 WCDB 句柄, 无需 CsoLoader/密码/独立打开,
         * 内核就绪后毫秒级可用(远快于等待 DatabaseProvider 捕获的 15s)。
         */
        private fun inProcessContactDb(): Any? {
            return try {
                val cl = runtimeCl() ?: return null
                // v955(问题6): j1 类名优先取 DexKit 扫描结果, 不再硬编码 gp0.j1
                val c4 = j1ServiceLookup(cl, "tn3.c4", "tn3.d4") ?: return null
                val j4 = XposedHelpers.callMethod(c4, "cj") ?: return null
                readFieldInHierarchy(j4, "d")
            } catch (t: Throwable) {
                null
            }
        }

        /** 沿继承链查字段(兼容字段声明在父类)。 */
        private fun readFieldInHierarchy(obj: Any, name: String): Any? {
            var c: Class<*>? = obj.javaClass
            while (c != null) {
                try {
                    val f = c.getDeclaredField(name)
                    f.isAccessible = true
                    return f.get(obj)
                } catch (ignored: NoSuchFieldException) {
                } catch (ignored: Throwable) {
                    return null
                }
                c = c.superclass
            }
            return null
        }

        /** 按列名读取一条 rcontact 行（j4.r() 游标列序不保证与 DB SQL 一致，故按名索引）。 */
        private fun readRowByNames(cursor: Cursor): ContactCard? {
            return try {
                val card = ContactCard()
                card.username = col(cursor, "username")
                card.nickname = col(cursor, "nickname")
                card.alias = col(cursor, "alias")
                card.conRemark = col(cursor, "conRemark")
                card.pyInitial = col(cursor, "pyInitial")
                card.quanPin = col(cursor, "quanPin")
                card.conRemarkPYFull = col(cursor, "conRemarkPYFull")
                card.type = intCol(cursor, "type")
                card.showHead = intCol(cursor, "showHead")
                card.contactLabelIds = col(cursor, "contactLabelIds")
                card.createTime = longCol(cursor, "createTime")
                card
            } catch (t: Throwable) {
                null
            }
        }

        private fun col(cursor: Cursor, name: String): String? {
            return try {
                val i = cursor.getColumnIndex(name)
                if (i < 0 || cursor.isNull(i)) return null
                cursor.getString(i)
            } catch (t: Throwable) {
                null
            }
        }

        private fun intCol(cursor: Cursor, name: String): Int {
            return try {
                val i = cursor.getColumnIndex(name)
                if (i < 0 || cursor.isNull(i)) return 0
                cursor.getInt(i)
            } catch (t: Throwable) {
                0
            }
        }

        private fun longCol(cursor: Cursor, name: String): Long {
            return try {
                val i = cursor.getColumnIndex(name)
                if (i < 0 || cursor.isNull(i)) return 0L
                cursor.getLong(i)
            } catch (t: Throwable) {
                0L
            }
        }

        private fun findQueryMethod(dbClass: Class<*>): Method? {
            // 1) 优先精确匹配: 恰好 2 个参数 (String, String[]) 的 rawQuery。
            //    参考《数据库.md》: 进程内句柄 qf5.k0 的 rawQuery 名为 B; 自开 WCDB SQLiteDatabase 为 u。
            for (name in arrayOf("B", "u")) {
                try {
                    val m = dbClass.getDeclaredMethod(name, String::class.java, Array<String>::class.java)
                    m.isAccessible = true
                    return m
                } catch (ignored: NoSuchMethodException) {
                }
            }
            // 2) 尝试其他已知名称的 2 参 (String, String[]) 签名
            val knownNames = arrayOf("rawQuery", "v", "w", "x", "y", "z", "rowQuery")
            for (name in knownNames) {
                try {
                    val m = dbClass.getDeclaredMethod(name, String::class.java, Array<String>::class.java)
                    m.isAccessible = true
                    return m
                } catch (ignored: NoSuchMethodException) {
                }
            }
            // 3) 单参数 String 签名
            for (name in arrayOf("u", "rawQuery", "v", "w", "x", "y", "z", "rowQuery")) {
                try {
                    val m = dbClass.getDeclaredMethod(name, String::class.java)
                    m.isAccessible = true
                    return m
                } catch (ignored: NoSuchMethodException) {
                }
            }
            // 4) 兜底：遍历所有方法找返回 Cursor 的，按参数数量排序优先 2 参
            // v955(问题18): 严格校验返回类型为 Cursor 且参数签名可被 invokeQuery 调用
            // (1 参 String 或 2 参 (String, String[])), 避免选中无法调用的方法。
            var best: Method? = null
            for (m in dbClass.declaredMethods) {
                if (m.returnType != Cursor::class.java) continue
                val pts = m.parameterTypes
                if (pts.size == 2 && pts[0] == String::class.java && pts[1] == Array<String>::class.java) {
                    m.isAccessible = true
                    return m
                }
                if (best == null && pts.size == 1 && pts[0] == String::class.java) {
                    best = m
                }
            }
            best?.isAccessible = true
            return best
        }

        private fun query(db: Any, sql: String, defaultCat: Category): List<ContactCard> {
            val list = ArrayList<ContactCard>()
            var cursor: Cursor? = null
            try {
                val queryMethod = findQueryMethod(db.javaClass)
                if (queryMethod == null) {
                    LogWriter.log(TAG, "query: no query method found")
                    return list
                }
                // 根据参数数量决定如何调用
                val paramTypes = queryMethod.parameterTypes
                cursor = if (paramTypes.size == 1) {
                    queryMethod.invoke(db, sql) as Cursor?
                } else {
                    queryMethod.invoke(db, sql, null) as Cursor?
                }
                if (cursor == null) return list

                while (cursor.moveToNext()) {
                    val card = ContactCard()
                    card.username = cursor.getString(0)
                    card.nickname = cursor.getString(1)
                    card.alias = cursor.getString(2)
                    card.conRemark = cursor.getString(3)
                    card.pyInitial = cursor.getString(4)
                    card.quanPin = cursor.getString(5)
                    card.conRemarkPYFull = cursor.getString(6)
                    card.type = cursor.getInt(7)
                    card.showHead = cursor.getInt(8)
                    card.contactLabelIds = cursor.getString(9)
                    card.createTime = cursor.getLong(10)
                    card.category = defaultCat
                    list.add(card)
                }
            } catch (t: Throwable) {
                LogWriter.log(TAG, "query err: " + t.javaClass.simpleName + " " + t.message)
            } finally {
                if (cursor != null) {
                    try {
                        cursor.close()
                    } catch (ignored: Throwable) {
                    }
                }
            }
            return list
        }

        private fun invokeQuery(m: Method, db: Any, sql: String): Cursor? {
            return invokeQuery(m, db, sql, null)
        }

        /**
         * v955(问题4): 支持 selectionArgs 绑定。原实现恒以 null 作为第二参,
         * 导致 "WHERE username = ?" 占位符无法绑定(仅传入 1 参时更是直接丢失参数)。
         */
        private fun invokeQuery(m: Method, db: Any, sql: String, bindArgs: Array<String>?): Cursor? {
            if (m.parameterTypes.size == 1) {
                return m.invoke(db, sql) as Cursor?
            }
            return m.invoke(db, sql, bindArgs) as Cursor?
        }

        private fun diagnoseContacts(db: Any) {
            val sql = "SELECT username, nickname, type, verifyFlag, " + "(type&1)!=0 AS b0, (type&8)!=0 AS b3, (type&32)!=0 AS b5, (type&64)!=0 AS b6 " + "FROM rcontact " + "WHERE deleteFlag=0 " + "AND (type&1)!=0 AND (type&32)=0 AND (type&8)=0 AND (type&64)=0 AND (verifyFlag&8)=0 " + "AND username NOT LIKE '%@chatroom' AND username NOT LIKE 'gh_%' " + "AND (username LIKE '%@im.chatroom' " + "  OR username LIKE '%@openim' " + "  OR username LIKE '%@micromsg.qq.com' " + "  OR username LIKE 'wxid_wi_%' " + "  OR (type&64)!=0) " + "ORDER BY username"
            var c: Cursor? = null
            var c2: Cursor? = null
            try {
                val m = findQueryMethod(db.javaClass)
                if (m == null) {
                    LogWriter.log(TAG, "DIAG err: no query method")
                    return
                }
                c = invokeQuery(m, db, sql)
                if (c == null || c.count == 0) {
                    LogWriter.log(TAG, "DIAG: no suspicious contacts — filter is clean")
                    return
                }
                LogWriter.log(TAG, "DIAG: " + c.count + " suspicious entries found:")
                while (c.moveToNext()) {
                    LogWriter.log(TAG, "DIAG: usr=" + c.getString(0)
                            + " nick=" + c.getString(1)
                            + " type=" + c.getInt(2)
                            + " vf=" + c.getInt(3)
                            + " b0=" + c.getInt(4)
                            + " b3=" + c.getInt(5)
                            + " b5=" + c.getInt(6)
                            + " b6=" + c.getInt(7))
                }

                // GROUP BY type 分布
                val sqlDist = "SELECT type, COUNT(*) AS n, " + "(type&1)!=0 AS b0, (type&8)!=0 AS b3, (type&32)!=0 AS b5, (type&64)!=0 AS b6 " + "FROM rcontact WHERE deleteFlag=0 AND username NOT LIKE '%@chatroom' " + "GROUP BY type ORDER BY n DESC"
                c2 = invokeQuery(m, db, sqlDist)
                if (c2 != null && c2.count > 0) {
                    LogWriter.log(TAG, "DIAG_TYPE: type distribution:")
                    while (c2.moveToNext()) {
                        LogWriter.log(TAG, "DIAG_TYPE: type=" + c2.getInt(0)
                                + " n=" + c2.getInt(1)
                                + " b0=" + c2.getInt(2)
                                + " b3=" + c2.getInt(3)
                                + " b5=" + c2.getInt(4)
                                + " b6=" + c2.getInt(5))
                    }
                }
                if (c2 != null) c2.close()
            } catch (t: Throwable) {
                LogWriter.log(TAG, "DIAG err: " + t.javaClass.simpleName + " " + t.message)
            } finally {
                if (c != null) {
                    try {
                        c.close()
                    } catch (ignored: Throwable) {
                    }
                }
                if (c2 != null) {
                    try {
                        c2.close()
                    } catch (ignored: Throwable) {
                    }
                }
            }
        }

        /**
         * 诊断: 统计星标联系人(type bit14=16384 或 specialFlag=1)数量,
         * 并检查它们是否都被好友 SQL 包含——用于排查"星标好友不在联系人选择器"。
         */
        private fun diagnoseStarContacts(db: Any) {
            try {
                val m = findQueryMethod(db.javaClass)
                if (m == null) {
                    LogWriter.log(TAG, "STAR_DIAG err: no query method")
                    return
                }
                val sql = "SELECT username, nickname, type FROM rcontact WHERE deleteFlag=0 AND (type & 16384) != 0"
                val c = invokeQuery(m, db, sql)
                if (c == null) return
                try {
                    val total = c.count
                    var inFriends = 0
                    val missing = StringBuilder()
                    while (c.moveToNext()) {
                        val usr = c.getString(0)
                        // 实际用线性查找
                        var found = false
                        val fs = sFriends
                        if (fs != null) {
                            for (cc in fs) {
                                if (usr == cc.username) {
                                    found = true
                                    break
                                }
                            }
                        }
                        if (found) inFriends++
                        else {
                            if (missing.length < 200) {
                                if (missing.length > 0) missing.append(",")
                                missing.append(usr)
                            }
                        }
                    }
                    LogWriter.log(TAG, "STAR_DIAG: type16384 total=" + total + " inFriends=" + inFriends + " missing=[" + missing + "]")
                } finally {
                    try {
                        c.close()
                    } catch (ignored: Throwable) {
                    }
                }
            } catch (t: Throwable) {
                LogWriter.log(TAG, "STAR_DIAG err: " + t.message)
            }
            // specialFlag 列可能不存在(版本差异), 单独尝试
            try {
                val m = findQueryMethod(db.javaClass)
                if (m == null) {
                    LogWriter.log(TAG, "STAR_DIAG: specialFlag column not present: no query method")
                    return
                }
                val c = invokeQuery(m, db, "SELECT username FROM rcontact WHERE specialFlag = 1")
                if (c != null) {
                    try {
                        val total = c.count
                        var inFriends = 0
                        while (c.moveToNext()) {
                            val usr = c.getString(0)
                            var found = false
                            val fs = sFriends
                            if (fs != null) {
                                for (cc in fs) {
                                    if (usr == cc.username) {
                                        found = true
                                        break
                                    }
                                }
                            }
                            if (found) inFriends++
                        }
                        LogWriter.log(TAG, "STAR_DIAG: specialFlag=1 total=" + total + " inFriends=" + inFriends)
                    } finally {
                        try {
                            c.close()
                        } catch (ignored: Throwable) {
                        }
                    }
                }
            } catch (t: Throwable) {
                LogWriter.log(TAG, "STAR_DIAG: specialFlag column not present: " + t.message)
            }
        }

        private fun getUin(ctx: Context): Long {
            return try {
                val sp = ctx.getSharedPreferences("system_config_prefs", 0)
                val uv = sp.all["default_uin"]
                if (uv != null) {
                    val s = uv.toString()
                    if (Regex("\\d+").matches(s)) return s.toLong()
                }
                0L
            } catch (t: Throwable) {
                0L
            }
        }

        @JvmStatic
        fun findByUsername(wxid: String?): ContactCard? {
            val fs = sFriends
            if (fs != null) {
                for (c in fs) {
                    if (wxid == c.username) return c
                }
            }
            val gs = sGroups
            if (gs != null) {
                for (c in gs) {
                    if (wxid == c.username) return c
                }
            }
            return null
        }

        /**
         * 按需查询任意联系人(含群成员/已删除好友): 从 rcontact 全表查单条, 不限 deleteFlag/type。
         * 用于群消息发送者昵称解析——群成员通常不在好友列表中, 但 rcontact 仍有其记录。
         */
        @JvmStatic
        fun queryAnyContactName(wxid: String?): String? {
            if (wxid.isNullOrEmpty()) return null
            try {
                val ctx = ContextManager.getAppContext()
                val cl = runtimeCl()
                if (ctx == null || cl == null) return null

                val uin = getUin(ctx)
                if (uin <= 0) return null

                // v1024: 优先使用捕获的微信自开 DB, 避免独立 openDatabase 触发 CsoLoader 未初始化
                var db: Any? = DatabaseProvider.getDatabase()
                val capturedDb = db != null
                if (db == null && VersionCompat.ENABLE_RAW_DB_OPEN) {
                    val baseDir = VersionCompat.getBaseDir(cl, ctx)
                    db = VersionCompat.openEnMicroDb(cl, baseDir, uin)
                }
                if (db == null) return null
                val dbh = db
                var cursor: Cursor? = null
                try {
                    val sql = "SELECT nickname, conRemark FROM rcontact WHERE username = ?"
                    val m = findQueryMethod(dbh.javaClass) ?: return null
                    // v955(问题4): 绑定 username 占位符, 避免全表扫描/语法错误
                    cursor = invokeQuery(m, dbh, sql, arrayOf(wxid))
                    if (cursor != null && cursor.moveToFirst()) {
                        val nickname = cursor.getString(0)
                        val remark = cursor.getString(1)
                        if (!remark.isNullOrEmpty()) return remark
                        if (!nickname.isNullOrEmpty()) return nickname
                    }
                } finally {
                    if (cursor != null) {
                        try {
                            cursor.close()
                        } catch (ignored: Throwable) {
                        }
                    }
                    if (!capturedDb) {
                        try {
                            val close = dbh.javaClass.getDeclaredMethod("c")
                            close.invoke(dbh)
                        } catch (ignored: Throwable) {
                        }
                    }
                }
            } catch (t: Throwable) {
                LogWriter.log(TAG, "queryAnyContactName err: " + t.javaClass.simpleName + " " + t.message)
            }
            return null
        }

        private fun md5(input: String): String {
            return try {
                val md = java.security.MessageDigest.getInstance("MD5")
                val d = md.digest(input.toByteArray(Charsets.UTF_8))
                val sb = StringBuilder()
                for (b in d) sb.append(String.format("%02x", b))
                sb.toString()
            } catch (t: Throwable) {
                ""
            }
        }

        // ==================== 文档《微信数据库直接读取》—— 通用只读查询 ====================

        @JvmStatic
        fun isDbReady(): Boolean {
            if (DatabaseProvider.isReady()) return true
            // 兜底：进程内已解密句柄 qf5.k0（DatabaseProvider 未捕获时仍可只读查询）。
            return try {
                inProcessContactDb() != null
            } catch (t: Throwable) {
                false
            }
        }

        /**
         * 直接读取微信已打开的主库（EnMicroMsg.db）。复用 ContactRepository 的 rawQuery 解析逻辑，
         * 不新开数据库、不解密、不修改。
         *
         * @param limit <=0 表示不限制
         */
        @JvmStatic
        fun rawQuery(sql: String, args: Array<String>?, limit: Int): List<DbRow> {
            val out = ArrayList<DbRow>()
            var db: Any? = DatabaseProvider.getDatabase()
            if (db == null) {
                // 兜底：复用微信进程内已解密句柄（qf5.k0），不新开库、不解密、不修改。
                db = try {
                    inProcessContactDb()
                } catch (ignored: Throwable) {
                    null
                }
            }
            if (db == null) return out
            val dbh = db
            var c: Cursor? = null
            try {
                val m = findQueryMethod(dbh.javaClass)
                if (m == null) return out
                c = invokeQuery(m, dbh, sql, args)
                if (c == null) return out
                val cols = c.getColumnNames()
                var n = 0
                while (c.moveToNext() && (limit <= 0 || n < limit)) {
                    val vals = arrayOfNulls<String>(cols.size)
                    for (i in cols.indices) {
                        try {
                            vals[i] = c.getString(i)
                        } catch (ignored: Throwable) {
                            vals[i] = null
                        }
                    }
                    out.add(DbRow(cols, vals))
                    n++
                }
            } catch (t: Throwable) {
                LogWriter.log(TAG, "rawQuery err: " + t.javaClass.simpleName + " " + t.message)
            } finally {
                if (c != null) {
                    try {
                        c.close()
                    } catch (ignored: Throwable) {
                    }
                }
            }
            return out
        }

        @JvmStatic
        fun rawQuery(sql: String): List<DbRow> {
            return rawQuery(sql, null, 0)
        }
    }
}
