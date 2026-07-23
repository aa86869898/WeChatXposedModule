/*
 * WxDbHelper.java
 * ===============
 * 独立 Xposed 模块参考文件。复制到 Android Studio 项目即可使用。
 * 仅依赖 Xposed API（compileOnly），其余全靠反射。
 * 微信 8.0.76 实测通过。
 *
 * 核心思想：
 *   不自己引入 WCDB/SQLCipher 的 jar 包，
 *   反射调用微信进程里已加载的 ka5.f.s() ——
 *   一句话打开加密数据库，微信帮你处理所有加密细节。
 *
 * 4 个核心反射方法：
 *   ① wo.w0.g(boolean) → String           获取 IMEI
 *   ② kk.k.g(byte[])   → String           MD5 哈希
 *   ③ ka5.f.s(String,String,int,boolean) → Object  打开加密数据库 ★
 *   ④ ka5.f.u(String,String[]) → Cursor   执行 SQL 查询
 *   ⑤ ka5.f.c() → void                    关闭数据库
 *
 * 密码和路径公式：
 *   imei = wo.w0.g(true)                    // "1234567890ABCDEF"
 *   pwd  = MD5(imei + uin).substring(0, 7) // 前7位hex → "e13b6eb"
 *   base = mp0.b.X()                        // "/data/user/0/com.tencent.mm/"
 *   hash = hm0.b0.e(int)                    // MD5("mm" + uin)
 *   path = base + "MicroMsg/" + hash + "/EnMicroMsg.db"
 *
 * 联系人 type 值（8.0.76 实测）：
 *   type=4 → 好友（13617行）
 *   type=3 → 群聊
 *   type=2 → 群聊
 *   type=33 → 系统号
 *   type=2049 → 文件传输助手
 */

package com.your.module;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;

import java.lang.reflect.Method;

public final class WxDbHelper {

    private final ClassLoader mCl;
    private final Context mCtx;

    private Method mGetImei;
    private Method mMd5;
    private Method mDataDir;
    private Method mUinHash;
    private Method mOpenDb;
    private Object mDbWrapper;

    public WxDbHelper(ClassLoader classLoader, Context context) {
        this.mCl = classLoader;
        this.mCtx = context;
    }

    // ================================================================
    // 公开 API
    // ================================================================

    /** 获取 uin */
    public long getUin() {
        SharedPreferences sp = mCtx.getSharedPreferences("system_config_prefs", 0);
        Object v = sp.getAll().get("default_uin");
        if (v == null) throw new RuntimeException("uin 为空，微信未登录");
        return Long.parseLong(v.toString());
    }

    /** 获取 IMEI（取不到返回 "1234567890ABCDEF"） */
    public String getImei() {
        try {
            if (mGetImei == null) {
                Class<?> wo = mCl.loadClass("wo.w0");
                mGetImei = wo.getDeclaredMethod("g", boolean.class);
            }
            String s = (String) mGetImei.invoke(null, true);
            return (s != null && !s.isEmpty()) ? s : "1234567890ABCDEF";
        } catch (Exception e) {
            return "1234567890ABCDEF";
        }
    }

    /** 计算数据库密码（7位 hex） */
    public String calcPassword(String imei, long uin) {
        String input = imei + uin;
        try {
            if (mMd5 == null) {
                Class<?> kk = mCl.loadClass("kk.k");
                mMd5 = kk.getDeclaredMethod("g", byte[].class);
            }
            String full = (String) mMd5.invoke(null, (Object) input.getBytes("UTF-8"));
            return full.substring(0, 7);
        } catch (Exception e) {
            return md5Jdk(input).substring(0, 7);
        }
    }

    /** 计算 EnMicroMsg.db 完整路径 */
    public String getDbPath(long uin) {
        try {
            if (mDataDir == null) {
                Class<?> mp0b = mCl.loadClass("mp0.b");
                mDataDir = mp0b.getDeclaredMethod("X");
            }
            if (mUinHash == null) {
                Class<?> hm0b0 = mCl.loadClass("hm0.b0");
                mUinHash = hm0b0.getDeclaredMethod("e", int.class);
            }
            String base = (String) mDataDir.invoke(null);
            String hash = (String) mUinHash.invoke(null, (int) uin);
            return base + "MicroMsg/" + hash + "/EnMicroMsg.db";
        } catch (Exception e) {
            String base = mCtx.getFilesDir().getParentFile().getAbsolutePath() + "/";
            return base + "MicroMsg/" + md5Jdk("mm" + uin) + "/EnMicroMsg.db";
        }
    }

    /**
     * ★ 核心：打开加密数据库
     * @return ka5.f 实例（内部包装了 WCDB SQLiteDatabase）
     */
    public Object openDatabase(String dbPath, String password) {
        try {
            if (mOpenDb == null) {
                Class<?> ka5f = mCl.loadClass("ka5.f");
                mOpenDb = ka5f.getDeclaredMethod("s",
                        String.class, String.class, int.class, boolean.class);
            }
            Object db = mOpenDb.invoke(null, dbPath, password, 0, true);
            if (db != null) {
                Cursor c = rawQuery(db,
                        "SELECT name FROM sqlite_master WHERE type='table' AND name='rcontact'");
                if (c != null && c.moveToFirst()) {
                    c.close();
                    mDbWrapper = db;
                    return db;
                }
                if (c != null) c.close();
                closeDatabase(db);
            }
        } catch (Exception e) {
            throw new RuntimeException("打开数据库失败: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * 枚举多个 IMEI/密码候选，自动尝试直到打开成功
     * 候选顺序：① wo.w0.g(true)  ② "1234567890ABCDEF"  ③ 用户额外传入的
     */
    public Object openDatabaseWithRetry(String dbPath, long uin, String... extraImeis) {
        String[] candidates = new String[(extraImeis != null ? extraImeis.length : 0) + 2];
        candidates[0] = getImei();
        candidates[1] = "1234567890ABCDEF";
        if (extraImeis != null) System.arraycopy(extraImeis, 0, candidates, 2, extraImeis.length);
        for (String imei : candidates) {
            if (imei == null || imei.isEmpty()) continue;
            try {
                Object db = openDatabase(dbPath, calcPassword(imei, uin));
                if (db != null) return db;
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** 执行 SQL 查询，返回 Cursor */
    public Cursor rawQuery(Object db, String sql) {
        try {
            Method u = db.getClass().getDeclaredMethod("u", String.class, String[].class);
            return (Cursor) u.invoke(db, sql, null);
        } catch (Exception e) {
            throw new RuntimeException("查询失败: " + e.getMessage(), e);
        }
    }

    /** 关闭数据库 */
    public void closeDatabase(Object db) {
        try {
            Method c = db.getClass().getDeclaredMethod("c");
            c.invoke(db);
        } catch (Exception ignored) {}
    }

    /**
     * 一键操作：打开 → 查联系人 → 关闭
     * @param callback 每行回调
     * @param limit    最多行数
     */
    public void dumpContacts(ContactCallback callback, int limit) {
        long uin = getUin();
        String dbPath = getDbPath(uin);
        Object db = openDatabaseWithRetry(dbPath, uin);
        if (db == null) { callback.onError("数据库打开失败"); return; }
        try {
            Cursor c = rawQuery(db,
                    "SELECT username, nickname, alias, conRemark, pyInitial, quanPin, " +
                    "type, verifyFlag, chatroomFlag, deleteFlag, createTime " +
                    "FROM rcontact WHERE type=4 AND deleteFlag=0 " +
                    "ORDER BY CASE WHEN length(conRemarkPYFull) > 0 " +
                    "THEN upper(conRemarkPYFull) ELSE upper(quanPin) END ASC " +
                    "LIMIT " + limit);
            if (c == null) { callback.onError("查询返回 null"); return; }
            int count = 0;
            while (c.moveToNext()) {
                ContactBean bean = new ContactBean();
                bean.username = c.getString(c.getColumnIndex("username"));
                bean.nickname = c.getString(c.getColumnIndex("nickname"));
                bean.alias = c.getString(c.getColumnIndex("alias"));
                bean.conRemark = c.getString(c.getColumnIndex("conRemark"));
                bean.type = c.getInt(c.getColumnIndex("type"));
                count++;
                callback.onContact(count, bean);
            }
            c.close();
            callback.onComplete(count);
        } catch (Exception e) {
            callback.onError(e.getMessage());
        } finally {
            closeDatabase(db);
        }
    }

    // ================================================================
    // 内部工具
    // ================================================================

    private static String md5Jdk(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    // ================================================================
    // 数据类 & 回调
    // ================================================================

    public static class ContactBean {
        public String username;
        public String nickname;
        public String alias;
        public String conRemark;
        public int type;

        public String displayName() {
            if (conRemark != null && !conRemark.isEmpty()) return conRemark;
            if (alias != null && !alias.isEmpty()) return alias;
            return nickname != null ? nickname : username;
        }

        @Override
        public String toString() {
            return displayName() + " (" + username + ")";
        }
    }

    public interface ContactCallback {
        void onContact(int index, ContactBean bean);
        void onComplete(int total);
        void onError(String msg);
    }
}
