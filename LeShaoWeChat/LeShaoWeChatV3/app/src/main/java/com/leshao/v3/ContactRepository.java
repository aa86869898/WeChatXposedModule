package com.leshao.v3;

import android.content.Context;
import android.database.Cursor;
import android.content.SharedPreferences;

import com.leshao.v3.hook.VersionCompat;
import com.leshao.v3.model.ContactCard;
import com.leshao.v3.model.ContactCard.Category;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ContactRepository {

    private static final String TAG = "ContactRepo";
    private static List<ContactCard> sFriends;
    private static List<ContactCard> sGroups;
    private static volatile boolean sLoading;

    public static List<ContactCard> getFriends() {
        return sFriends != null ? sFriends : Collections.<ContactCard>emptyList();
    }

    public static List<ContactCard> getGroups() {
        return sGroups != null ? sGroups : Collections.<ContactCard>emptyList();
    }

    public static List<ContactCard> getAll() {
        List<ContactCard> all = new ArrayList<>();
        if (sFriends != null) all.addAll(sFriends);
        if (sGroups != null) all.addAll(sGroups);
        return all;
    }

    public static void refresh() {
        sFriends = null;
        sGroups = null;
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
        try {
            ClassLoader cl = ContextManager.getClassLoader();
            if (cl == null) { LogWriter.log(TAG, "cl null"); return; }

            long uin = getUin(ctx);
            if (uin <= 0) { LogWriter.log(TAG, "uin=0"); return; }

            String imei = VersionCompat.getImei(cl);
            String baseDir = VersionCompat.getBaseDir(cl, ctx);
            String dbHash = VersionCompat.getDbHash(cl, (int) uin);
            String dbPath = baseDir + "MicroMsg/" + dbHash + "/EnMicroMsg.db";
            String password = md5(imei + uin).substring(0, 7);

            LogWriter.log(TAG, "opening db: " + dbPath);
            Class<?> dbCls = VersionCompat.findDbOpenerClass(cl);
            if (dbCls == null) { LogWriter.log(TAG, "dbCls null"); return; }

            db = VersionCompat.openDatabase(dbCls, dbPath, password);
            if (db == null) { LogWriter.log(TAG, "db open FAILED"); return; }
            LogWriter.log(TAG, "db opened in " + (System.currentTimeMillis() - t0) + "ms");

            // 微信 j4.t()/j4.O()/j4.K() + j4.m() 精确: 正常联系人唯一定义
            String sqlFriends = "SELECT username, nickname, alias, conRemark, pyInitial, quanPin, "
                    + "conRemarkPYFull, type, showHead, contactLabelIds, createTime "
                    + "FROM rcontact WHERE deleteFlag = 0 "
                    + "AND (type & 1) != 0 "
                    + "AND (type & 32) = 0 "
                    + "AND (type & 8) = 0 "
                    + "AND (type & 64) = 0 "
                    + "AND (verifyFlag & 8) = 0 "
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

            // 诊断: 找出混入好友列表的非正常联系人
            diagnoseContacts(db);

            LogWriter.log(TAG, "total: " + (sFriends.size() + sGroups.size())
                    + " rows in " + (System.currentTimeMillis() - t0) + "ms");

        } catch (Throwable t) {
            LogWriter.log(TAG, "loadAll err: " + t.getClass().getSimpleName() + " " + t.getMessage());
        } finally {
            if (db != null) {
                try {
                    java.lang.reflect.Method close = db.getClass().getDeclaredMethod("c");
                    close.invoke(db);
                } catch (Throwable ignored) {}
            }
        }
    }

    private static List<ContactCard> query(Object db, String sql, Category defaultCat) {
        List<ContactCard> list = new ArrayList<>();
        Cursor cursor = null;
        try {
            java.lang.reflect.Method m = db.getClass()
                    .getDeclaredMethod("u", String.class, String[].class);
            cursor = (Cursor) m.invoke(db, sql, null);
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
        try {
            java.lang.reflect.Method m = db.getClass().getDeclaredMethod("u", String.class, String[].class);
            c = (Cursor) m.invoke(db, sql, null);
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
            Cursor c2 = (Cursor) m.invoke(db, sqlDist, null);
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
