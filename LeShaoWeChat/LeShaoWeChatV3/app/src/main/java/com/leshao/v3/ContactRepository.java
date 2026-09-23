package com.leshao.v3;

import android.content.Context;
import android.database.Cursor;
import android.content.SharedPreferences;

import com.leshao.ai.hook.wechat.StorageHub;
import com.leshao.v3.db.DatabaseProvider;
import com.leshao.v3.hook.VersionCompat;
import com.leshao.v3.model.ContactCard;
import com.leshao.v3.model.ContactCard.Category;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XposedHelpers;

public class ContactRepository {

    private static final String TAG = "ContactRepo";
    private static volatile List<ContactCard> sFriends;
    private static volatile List<ContactCard> sGroups;
    private static volatile List<ContactCard> sServiceAccounts;
    private static volatile boolean sLoading;
    private static final Object sDbCapturedLock = new Object();
    private static volatile boolean sDbCaptured;

    /** v1025: 优先返回微信真实 ClassLoader(反查自微信运行时对象), 解决内核/存储类静态状态不共享问题 */
    private static ClassLoader runtimeCl() {
        ClassLoader real = DatabaseProvider.getRealClassLoader();
        return real != null ? real : ContextManager.getClassLoader();
    }

    /** DatabaseProvider 捕获到微信自开 DB 时回调: 唤醒等待中的加载线程并触发重载 */
    public static void onDatabaseCaptured() {
        synchronized (sDbCapturedLock) {
            sDbCaptured = true;
            sDbCapturedLock.notifyAll();
        }
        // 若当前没有加载线程, 触发一次重载
        if (!sLoading) {
            loadAsync(null, true);
        }
    }

    public static List<ContactCard> getFriends() {
        return sFriends != null ? sFriends : Collections.<ContactCard>emptyList();
    }

    public static List<ContactCard> getGroups() {
        return sGroups != null ? sGroups : Collections.<ContactCard>emptyList();
    }

    public static List<ContactCard> getServiceAccounts() {
        return sServiceAccounts != null ? sServiceAccounts : Collections.<ContactCard>emptyList();
    }

    public static List<ContactCard> getAll() {
        List<ContactCard> all = new ArrayList<>();
        if (sFriends != null) all.addAll(sFriends);
        if (sGroups != null) all.addAll(sGroups);
        if (sServiceAccounts != null) all.addAll(sServiceAccounts);
        return all;
    }

    public static void refresh() {
        sFriends = null;
        sGroups = null;
        sServiceAccounts = null;
    }

    public static void loadAsync(Runnable onDone) {
        loadAsync(onDone, false);
    }

    private static synchronized void loadAsync(Runnable onDone, boolean retry) {
        if (!retry && (sFriends != null || sGroups != null)) {
            LogWriter.log(TAG, "already loaded, " + size(sFriends) + " friends, " + size(sGroups) + " groups");
            if (onDone != null) onDone.run();
            return;
        }
        if (sLoading) {
            LogWriter.log(TAG, "loading in progress, queuing callback");
            new Thread("ContactRepoWait") {
                @Override
                public void run() {
                    try {
                        long start = System.currentTimeMillis();
                        while (sLoading && System.currentTimeMillis() - start < 30000) {
                            Thread.sleep(200);
                        }
                    } catch (InterruptedException ignored) {}
                    if (onDone != null) onDone.run();
                }
            }.start();
            return;
        }
        sLoading = true;
        new Thread("ContactRepoLoader") {
            @Override
            public void run() {
                try {
                    Context ctx = ContextManager.getAppContext();
                    if (ctx != null) loadAll(ctx);
                } catch (Throwable t) {
                    LogWriter.log(TAG, "load err: " + t.getClass().getSimpleName() + " " + t.getMessage());
                } finally {
                    sLoading = false;
                    if (onDone != null) onDone.run();
                }
            }
        }.start();
    }

    private static int size(List<?> list) {
        return list != null ? list.size() : 0;
    }

    private static void loadAll(Context ctx) {
        long t0 = System.currentTimeMillis();

        Object db = null;
        boolean capturedDb = false;
        try {
            ClassLoader cl = runtimeCl();
            if (cl == null) { LogWriter.log(TAG, "cl null"); return; }

            // v1026: 首选进程内已解密句柄(参考《数据库.md》), 内核就绪后毫秒级返回,
            // 避免旧方案死等 DatabaseProvider 捕获 15s(实测从不捕获, 纯浪费)。
            long ipDeadline = System.currentTimeMillis() + 5000L;
            while (db == null && System.currentTimeMillis() < ipDeadline) {
                db = inProcessContactDb();
                if (db != null) { capturedDb = true; break; }
                try { Thread.sleep(500L); } catch (InterruptedException ignored) {}
            }
            // 次选 DatabaseProvider 捕获的微信自开 DB(短等待, 不拖慢)。
            if (db == null) {
                long capDeadline = System.currentTimeMillis() + 1500L;
                while (db == null && System.currentTimeMillis() < capDeadline) {
                    db = DatabaseProvider.getDatabase();
                    if (db != null) { capturedDb = true; break; }
                    synchronized (sDbCapturedLock) {
                        if (!sDbCaptured) {
                            try { sDbCapturedLock.wait(300L); }
                            catch (InterruptedException ignored) {}
                        } else {
                            db = DatabaseProvider.getDatabase();
                            if (db != null) { capturedDb = true; break; }
                        }
                    }
                }
            }
            if (db == null) {
                LogWriter.log(TAG, "in-process/DatabaseProvider 均不可用, fallback to direct open");
            } else {
                LogWriter.log(TAG, "using in-process/captured DB: " + db.getClass().getName()
                    + " in " + (System.currentTimeMillis() - t0) + "ms");
            }

            long uin = getUin(ctx);
            if (uin <= 0) { LogWriter.log(TAG, "uin=0"); return; }
            String baseDir = VersionCompat.getBaseDir(cl, ctx);
            if (!baseDir.endsWith("/")) baseDir += "/";

            // v1020: 恢复 v980 直接 DB 打开路径（v980 实机验证有效: db opened in 29ms）。
            // v1019 曾将 enumerateRContact 提到最前、DB 改由 openEnMicroDb 爆破，实机均失败，
            // 现回退为「固定路径+固定密码 直接打开」首选，枚举与爆破降级为兜底。
            //
            // v1022: 根因=微信内核未就绪时 CsoLoader 未被初始化，kh5.f.w 直接抛
            // "Missing initialization before executing, please invoke CsoLoader.initialize first"。
            // v980 成功时内核已 init（j1.v OK）→ CsoLoader 已由微信初始化。
            // 这里打开失败时轮询重试（最多 ~20s），等待微信完成内核/CsoLoader 初始化。
            String imei = VersionCompat.getImei(cl);
            String dbHash = VersionCompat.getDbHash(cl, (int) uin);
            String dbPath = baseDir + "MicroMsg/" + dbHash + "/EnMicroMsg.db";
            String password = md5(imei + uin).substring(0, 7);

            LogWriter.log(TAG, "opening db: " + dbPath);
            Class<?> dbCls = VersionCompat.findDbOpenerClass(cl);

            long openDeadline = System.currentTimeMillis() + 20000L;
            int openAttempt = 0;
            while (db == null && System.currentTimeMillis() < openDeadline) {
                openAttempt++;
                if (openAttempt > 1) {
                    try { Thread.sleep(1500L); } catch (InterruptedException ignored) {}
                }
                if (dbCls != null) {
                    db = VersionCompat.openDatabase(dbCls, dbPath, password);
                    if (db == null) {
                        db = VersionCompat.openDatabaseWcdb(cl, dbPath, password);
                    }
                }
                if (db == null) {
                    LogWriter.log(TAG, "db open attempt " + openAttempt
                            + " failed (csoReady=" + VersionCompat.isCsoLoaderReady() + "), retrying");
                }
            }
            if (db == null) {
                // 兜底1: v1019 进程内 rcontact 枚举（不依赖 DB 打开）
                if (enumerateRContact()) {
                    LogWriter.log(TAG, "enumerateRContact ok: " + (sFriends.size() + sGroups.size()
                            + sServiceAccounts.size()) + " rows in " + (System.currentTimeMillis() - t0) + "ms");
                    return;
                }
                LogWriter.log(TAG, "enumerateRContact empty/failed, openEnMicroDb fallback");
                // 兜底2: 目录名候选化 + 密码候选爆破
                db = VersionCompat.openEnMicroDb(cl, baseDir, uin);
            }
            if (db == null) { LogWriter.log(TAG, "db open FAILED"); return; }
            LogWriter.log(TAG, "db opened in " + (System.currentTimeMillis() - t0) + "ms");

            // 微信 j4.t()/j4.O()/j4.K() + j4.m() 精确: 正常联系人唯一定义
            // verifyFlag 为验证状态(非联系人类型), 不过滤, 避免漏掉"被对方删除的单向好友"
            String sqlFriends = "SELECT username, nickname, alias, conRemark, pyInitial, quanPin, "
                    + "conRemarkPYFull, type, showHead, contactLabelIds, createTime "
                    + "FROM rcontact WHERE deleteFlag = 0 "
                    + "AND (type & 1) != 0 "
                    + "AND (type & 32) = 0 "
                    + "AND (type & 8) = 0 "
                    + "AND (type & 64) = 0 "
                    + "AND username NOT LIKE '%@chatroom' "
                    + "AND username NOT LIKE '%@im.chatroom' "
                    + "AND username NOT LIKE '%@openim' "
                    + "AND username NOT LIKE '%@micromsg.qq.com' "
                    + "AND username NOT LIKE 'gh_%' "
                    + "ORDER BY CASE WHEN length(conRemarkPYFull) > 0 "
                    + "THEN upper(conRemarkPYFull) ELSE upper(quanPin) END ASC";

            long t1 = System.currentTimeMillis();
            sFriends = query(db, sqlFriends, Category.FRIEND);
            LogWriter.log(TAG, "friends: " + sFriends.size() + " rows in " + (System.currentTimeMillis() - t1) + "ms");

            // 群聊
            String sqlGroups = "SELECT username, nickname, alias, conRemark, pyInitial, quanPin, "
                    + "conRemarkPYFull, type, showHead, contactLabelIds, createTime "
                    + "FROM rcontact WHERE deleteFlag = 0 "
                    + "AND username LIKE '%@chatroom' "
                    + "AND username NOT LIKE '%@im.chatroom' "
                    + "ORDER BY CASE WHEN length(conRemarkPYFull) > 0 "
                    + "THEN upper(conRemarkPYFull) ELSE upper(quanPin) END ASC";

            long t2 = System.currentTimeMillis();
            sGroups = query(db, sqlGroups, Category.GROUP);
            LogWriter.log(TAG, "groups: " + sGroups.size() + " rows in " + (System.currentTimeMillis() - t2) + "ms");

            // 服务号: 公众号 + 订阅号 + 服务号 (gh_ 前缀)
            String sqlService = "SELECT username, nickname, alias, conRemark, pyInitial, quanPin, "
                    + "conRemarkPYFull, type, showHead, contactLabelIds, createTime "
                    + "FROM rcontact WHERE deleteFlag = 0 "
                    + "AND username LIKE 'gh_%' "
                    + "ORDER BY CASE WHEN length(conRemarkPYFull) > 0 "
                    + "THEN upper(conRemarkPYFull) ELSE upper(quanPin) END ASC";

            long t3 = System.currentTimeMillis();
            sServiceAccounts = query(db, sqlService, Category.OFFICIAL);
            LogWriter.log(TAG, "service: " + sServiceAccounts.size() + " rows in " + (System.currentTimeMillis() - t3) + "ms");

            // 诊断: 找出混入好友列表的非正常联系人
            diagnoseContacts(db);
            diagnoseStarContacts(db);

            LogWriter.log(TAG, "total: " + (sFriends.size() + sGroups.size() + sServiceAccounts.size())
                    + " rows in " + (System.currentTimeMillis() - t0) + "ms");

        } catch (Throwable t) {
            LogWriter.log(TAG, "loadAll err: " + t.getClass().getSimpleName() + " " + t.getMessage());
        } finally {
            // v1024: 捕获的 DB 属于微信自身进程, 绝不能 close; 仅关闭模块自开的 DB
            if (db != null && !capturedDb) {
                try {
                    java.lang.reflect.Method close = db.getClass().getDeclaredMethod("c");
                    close.invoke(db);
                } catch (Throwable ignored) {}
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
    private static boolean enumerateRContact() {
        try {
            Object storage = rcontactStorageInstance();
            if (storage == null) {
                LogWriter.log(TAG, "enumerateRContact: rcontactStorage null");
                return false;
            }
            Cursor cursor = (Cursor) XposedHelpers.callMethod(storage, "r");
            if (cursor == null) {
                LogWriter.log(TAG, "enumerateRContact: cursor null");
                return false;
            }
            List<ContactCard> friends = new ArrayList<>();
            List<ContactCard> groups = new ArrayList<>();
            List<ContactCard> service = new ArrayList<>();
            try {
                while (cursor.moveToNext()) {
                    ContactCard card = readRowByNames(cursor);
                    if (card == null || card.username == null || card.username.isEmpty()) continue;
                    String u = card.username;
                    if (u.endsWith("@chatroom")) {
                        card.category = Category.GROUP;
                        groups.add(card);
                    } else if (u.startsWith("gh_")) {
                        card.category = Category.OFFICIAL;
                        service.add(card);
                    } else {
                        int t = card.type;
                        if ((t & 1) == 0 || (t & 32) != 0 || (t & 8) != 0 || (t & 64) != 0) continue;
                        card.category = Category.FRIEND;
                        friends.add(card);
                    }
                }
            } finally {
                try { cursor.close(); } catch (Throwable ignored) {}
            }
            sFriends = friends;
            sGroups = groups;
            sServiceAccounts = service;
            LogWriter.log(TAG, "enumerateRContact: friends=" + friends.size()
                    + " groups=" + groups.size() + " service=" + service.size());
            return !friends.isEmpty() || !groups.isEmpty() || !service.isEmpty();
        } catch (Throwable t) {
            LogWriter.log(TAG, "enumerateRContact err: " + t.getClass().getSimpleName() + " " + t.getMessage());
            return false;
        }
    }

    /** 获取 rcontact 存储实例：StorageHub → b41.h9 直连 → j1.v(tn3.c4) 兜底。 */
    private static Object rcontactStorageInstance() {
        try {
            StorageHub hub = StorageHub.get();
            Object s = hub.rcontactStorage();
            if (s != null) return s;
        } catch (Throwable t) {
            LogWriter.log(TAG, "rcontactStorageInstance(StorageHub) err: " + t.getMessage());
        }
        try {
            ClassLoader cl = runtimeCl();
            if (cl == null) return null;
            Class<?> h9 = XposedHelpers.findClass("b41.h9", cl);
            Object hub = XposedHelpers.callStaticMethod(h9, "d");
            Object acc = hub != null ? XposedHelpers.callMethod(hub, "b")
                    : XposedHelpers.callStaticMethod(h9, "b");
            if (acc != null) {
                Object rcs = XposedHelpers.callMethod(acc, "r");
                if (rcs != null) return rcs;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "rcontactStorageInstance(b41.h9) err: " + t.getMessage());
        }
        try {
            ClassLoader cl = runtimeCl();
            if (cl == null) return null;
            Object c4 = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("gp0.j1", cl), "v",
                    XposedHelpers.findClass("tn3.c4", cl));
            Object h2 = XposedHelpers.findClass("com.tencent.mm.plugin.messenger.foundation.h2", cl).cast(c4);
            return XposedHelpers.callMethod(h2, "cj");
        } catch (Throwable t) {
            LogWriter.log(TAG, "rcontactStorageInstance(j1.v) err: " + t.getMessage());
            return null;
        }
    }

    /**
     * v1026: 进程内联系人 DB 句柄(参考《数据库.md》)。
     * 链路: gp0.j1.v(tn3.c4) → h2.cj() → ContactStorage(j4) → 字段 d = qf5.k0。
     * qf5.k0 是微信进程内已解密的 WCDB 句柄, 无需 CsoLoader/密码/独立打开,
     * 内核就绪后毫秒级可用(远快于等待 DatabaseProvider 捕获的 15s)。
     */
    private static Object inProcessContactDb() {
        try {
            ClassLoader cl = runtimeCl();
            if (cl == null) return null;
            Object c4 = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("gp0.j1", cl), "v",
                    XposedHelpers.findClass("tn3.c4", cl));
            if (c4 == null) return null;
            Object j4 = XposedHelpers.callMethod(c4, "cj");
            if (j4 == null) return null;
            return readFieldInHierarchy(j4, "d");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 沿继承链查字段(兼容字段声明在父类)。 */
    private static Object readFieldInHierarchy(Object obj, String name) {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /** 按列名读取一条 rcontact 行（j4.r() 游标列序不保证与 DB SQL 一致，故按名索引）。 */
    private static ContactCard readRowByNames(Cursor cursor) {
        try {
            ContactCard card = new ContactCard();
            card.username = col(cursor, "username");
            card.nickname = col(cursor, "nickname");
            card.alias = col(cursor, "alias");
            card.conRemark = col(cursor, "conRemark");
            card.pyInitial = col(cursor, "pyInitial");
            card.quanPin = col(cursor, "quanPin");
            card.conRemarkPYFull = col(cursor, "conRemarkPYFull");
            card.type = intCol(cursor, "type");
            card.showHead = intCol(cursor, "showHead");
            card.contactLabelIds = col(cursor, "contactLabelIds");
            card.createTime = longCol(cursor, "createTime");
            return card;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String col(Cursor cursor, String name) {
        try {
            int i = cursor.getColumnIndex(name);
            if (i < 0 || cursor.isNull(i)) return null;
            return cursor.getString(i);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int intCol(Cursor cursor, String name) {
        try {
            int i = cursor.getColumnIndex(name);
            if (i < 0 || cursor.isNull(i)) return 0;
            return cursor.getInt(i);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long longCol(Cursor cursor, String name) {
        try {
            int i = cursor.getColumnIndex(name);
            if (i < 0 || cursor.isNull(i)) return 0L;
            return cursor.getLong(i);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static java.lang.reflect.Method findQueryMethod(Class<?> dbClass) {
        // 1) 优先精确匹配: 恰好 2 个参数 (String, String[]) 的 rawQuery。
        //    参考《数据库.md》: 进程内句柄 qf5.k0 的 rawQuery 名为 B; 自开 WCDB SQLiteDatabase 为 u。
        for (String name : new String[]{"B", "u"}) {
            try {
                java.lang.reflect.Method m = dbClass.getDeclaredMethod(name, String.class, String[].class);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
        }
        // 2) 尝试其他已知名称的 2 参 (String, String[]) 签名
        String[] knownNames = {"rawQuery", "v", "w", "x", "y", "z", "rowQuery"};
        for (String name : knownNames) {
            try {
                java.lang.reflect.Method m = dbClass.getDeclaredMethod(name, String.class, String[].class);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
        }
        // 3) 单参数 String 签名
        for (String name : new String[]{"u", "rawQuery", "v", "w", "x", "y", "z", "rowQuery"}) {
            try {
                java.lang.reflect.Method m = dbClass.getDeclaredMethod(name, String.class);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
        }
        // 4) 兜底：遍历所有方法找返回 Cursor 的，按参数数量排序优先 2 参
        java.lang.reflect.Method best = null;
        for (java.lang.reflect.Method m : dbClass.getDeclaredMethods()) {
            if (m.getReturnType() == android.database.Cursor.class) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 2 && pts[0] == String.class && pts[1] == String[].class) {
                    m.setAccessible(true);
                    return m;
                }
                if (best == null && pts.length >= 1 && pts[0] == String.class) {
                    best = m;
                }
            }
        }
        if (best != null) best.setAccessible(true);
        return best;
    }

    private static List<ContactCard> query(Object db, String sql, Category defaultCat) {
        List<ContactCard> list = new ArrayList<>();
        Cursor cursor = null;
        try {
            java.lang.reflect.Method queryMethod = findQueryMethod(db.getClass());
            if (queryMethod == null) {
                LogWriter.log(TAG, "query: no query method found");
                return list;
            }
            // 根据参数数量决定如何调用
            Class<?>[] paramTypes = queryMethod.getParameterTypes();
            if (paramTypes.length == 1) {
                cursor = (Cursor) queryMethod.invoke(db, sql);
            } else {
                cursor = (Cursor) queryMethod.invoke(db, sql, null);
            }
            if (cursor == null) return list;

            while (cursor.moveToNext()) {
                ContactCard card = new ContactCard();
                card.username = cursor.getString(0);
                card.nickname = cursor.getString(1);
                card.alias = cursor.getString(2);
                card.conRemark = cursor.getString(3);
                card.pyInitial = cursor.getString(4);
                card.quanPin = cursor.getString(5);
                card.conRemarkPYFull = cursor.getString(6);
                card.type = cursor.getInt(7);
                card.showHead = cursor.getInt(8);
                card.contactLabelIds = cursor.getString(9);
                card.createTime = cursor.getLong(10);
                card.category = defaultCat;
                list.add(card);
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "query err: " + t.getClass().getSimpleName() + " " + t.getMessage());
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (Throwable ignored) {}
            }
        }
        return list;
    }

    private static Cursor invokeQuery(java.lang.reflect.Method m, Object db, String sql) throws Exception {
        if (m.getParameterTypes().length == 1) {
            return (Cursor) m.invoke(db, sql);
        }
        return (Cursor) m.invoke(db, sql, (Object) null);
    }

    private static void diagnoseContacts(Object db) {
        String sql = "SELECT username, nickname, type, verifyFlag, "
                + "(type&1)!=0 AS b0, (type&8)!=0 AS b3, (type&32)!=0 AS b5, (type&64)!=0 AS b6 "
                + "FROM rcontact "
                + "WHERE deleteFlag=0 "
                + "AND (type&1)!=0 AND (type&32)=0 AND (type&8)=0 AND (type&64)=0 AND (verifyFlag&8)=0 "
                + "AND username NOT LIKE '%@chatroom' AND username NOT LIKE 'gh_%' "
                + "AND (username LIKE '%@im.chatroom' "
                + "  OR username LIKE '%@openim' "
                + "  OR username LIKE '%@micromsg.qq.com' "
                + "  OR username LIKE 'wxid_wi_%' "
                + "  OR (type&64)!=0) "
                + "ORDER BY username";
        Cursor c = null;
        Cursor c2 = null;
        try {
            java.lang.reflect.Method m = findQueryMethod(db.getClass());
            if (m == null) { LogWriter.log(TAG, "DIAG err: no query method"); return; }
            c = invokeQuery(m, db, sql);
            if (c == null || c.getCount() == 0) {
                LogWriter.log(TAG, "DIAG: no suspicious contacts — filter is clean");
                return;
            }
            LogWriter.log(TAG, "DIAG: " + c.getCount() + " suspicious entries found:");
            while (c.moveToNext()) {
                LogWriter.log(TAG, "DIAG: usr=" + c.getString(0)
                        + " nick=" + c.getString(1)
                        + " type=" + c.getInt(2)
                        + " vf=" + c.getInt(3)
                        + " b0=" + c.getInt(4)
                        + " b3=" + c.getInt(5)
                        + " b5=" + c.getInt(6)
                        + " b6=" + c.getInt(7));
            }

            // GROUP BY type 分布
            String sqlDist = "SELECT type, COUNT(*) AS n, "
                    + "(type&1)!=0 AS b0, (type&8)!=0 AS b3, (type&32)!=0 AS b5, (type&64)!=0 AS b6 "
                    + "FROM rcontact WHERE deleteFlag=0 AND username NOT LIKE '%@chatroom' "
                    + "GROUP BY type ORDER BY n DESC";
            c2 = invokeQuery(m, db, sqlDist);
            if (c2 != null && c2.getCount() > 0) {
                LogWriter.log(TAG, "DIAG_TYPE: type distribution:");
                while (c2.moveToNext()) {
                    LogWriter.log(TAG, "DIAG_TYPE: type=" + c2.getInt(0)
                            + " n=" + c2.getInt(1)
                            + " b0=" + c2.getInt(2)
                            + " b3=" + c2.getInt(3)
                            + " b5=" + c2.getInt(4)
                            + " b6=" + c2.getInt(5));
                }
            }
            if (c2 != null) c2.close();
        } catch (Throwable t) {
            LogWriter.log(TAG, "DIAG err: " + t.getClass().getSimpleName() + " " + t.getMessage());
        } finally {
            if (c != null) {
                try { c.close(); } catch (Throwable ignored) {}
            }
            if (c2 != null) {
                try { c2.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * 诊断: 统计星标联系人(type bit14=16384 或 specialFlag=1)数量,
     * 并检查它们是否都被好友 SQL 包含——用于排查"星标好友不在联系人选择器"。
     */
    private static void diagnoseStarContacts(Object db) {
        try {
            java.lang.reflect.Method m = findQueryMethod(db.getClass());
            if (m == null) { LogWriter.log(TAG, "STAR_DIAG err: no query method"); return; }
            String sql = "SELECT username, nickname, type FROM rcontact WHERE deleteFlag=0 AND (type & 16384) != 0";
            Cursor c = (Cursor) invokeQuery(m, db, sql);
            if (c == null) return;
            try {
                int total = c.getCount();
                int inFriends = 0;
                StringBuilder missing = new StringBuilder();
                while (c.moveToNext()) {
                    String usr = c.getString(0);
                    // 实际用线性查找
                    boolean found = false;
                    if (sFriends != null) {
                        for (ContactCard cc : sFriends) {
                            if (usr.equals(cc.username)) { found = true; break; }
                        }
                    }
                    if (found) inFriends++;
                    else {
                        if (missing.length() < 200) {
                            if (missing.length() > 0) missing.append(",");
                            missing.append(usr);
                        }
                    }
                }
                LogWriter.log(TAG, "STAR_DIAG: type16384 total=" + total + " inFriends=" + inFriends + " missing=[" + missing + "]");
            } finally {
                try { c.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "STAR_DIAG err: " + t.getMessage());
        }
        // specialFlag 列可能不存在(版本差异), 单独尝试
        try {
            java.lang.reflect.Method m = findQueryMethod(db.getClass());
            if (m == null) { LogWriter.log(TAG, "STAR_DIAG: specialFlag column not present: no query method"); return; }
            Cursor c = invokeQuery(m, db,
                    "SELECT username FROM rcontact WHERE specialFlag = 1");
            if (c != null) {
                try {
                    int total = c.getCount();
                    int inFriends = 0;
                    while (c.moveToNext()) {
                        String usr = c.getString(0);
                        boolean found = false;
                        if (sFriends != null) {
                            for (ContactCard cc : sFriends) {
                                if (usr.equals(cc.username)) { found = true; break; }
                            }
                        }
                        if (found) inFriends++;
                    }
                    LogWriter.log(TAG, "STAR_DIAG: specialFlag=1 total=" + total + " inFriends=" + inFriends);
                } finally {
                    try { c.close(); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "STAR_DIAG: specialFlag column not present: " + t.getMessage());
        }
    }

    private static long getUin(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences("system_config_prefs", 0);
            Object uv = sp.getAll().get("default_uin");
            if (uv != null) {
                String s = uv.toString();
                if (s.matches("\\d+")) return Long.parseLong(s);
            }
            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static ContactCard findByUsername(String wxid) {
        if (sFriends != null) {
            for (ContactCard c : sFriends) {
                if (wxid.equals(c.username)) return c;
            }
        }
        if (sGroups != null) {
            for (ContactCard c : sGroups) {
                if (wxid.equals(c.username)) return c;
            }
        }
        return null;
    }

    /**
     * 按需查询任意联系人(含群成员/已删除好友): 从 rcontact 全表查单条, 不限 deleteFlag/type。
     * 用于群消息发送者昵称解析——群成员通常不在好友列表中, 但 rcontact 仍有其记录。
     */
    public static String queryAnyContactName(String wxid) {
        if (wxid == null || wxid.isEmpty()) return null;
        try {
            Context ctx = ContextManager.getAppContext();
            ClassLoader cl = runtimeCl();
            if (ctx == null || cl == null) return null;

            long uin = getUin(ctx);
            if (uin <= 0) return null;

            // v1024: 优先使用捕获的微信自开 DB, 避免独立 openDatabase 触发 CsoLoader 未初始化
            Object db = DatabaseProvider.getDatabase();
            boolean capturedDb = db != null;
            if (db == null) {
                String baseDir = VersionCompat.getBaseDir(cl, ctx);
                db = VersionCompat.openEnMicroDb(cl, baseDir, uin);
            }
            if (db == null) return null;
            Cursor cursor = null;
            try {
                String sql = "SELECT nickname, conRemark FROM rcontact WHERE username = ?";
                java.lang.reflect.Method m = findQueryMethod(db.getClass());
                if (m == null) return null;
                cursor = (Cursor) invokeQuery(m, db, sql);
                if (cursor != null && cursor.moveToFirst()) {
                    String nickname = cursor.getString(0);
                    String remark = cursor.getString(1);
                    if (remark != null && !remark.isEmpty()) return remark;
                    if (nickname != null && !nickname.isEmpty()) return nickname;
                }
            } finally {
                if (cursor != null) {
                    try { cursor.close(); } catch (Throwable ignored) {}
                }
                if (!capturedDb) {
                    try {
                        java.lang.reflect.Method close = db.getClass().getDeclaredMethod("c");
                        close.invoke(db);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "queryAnyContactName err: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
        return null;
    }

    private static String md5(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
