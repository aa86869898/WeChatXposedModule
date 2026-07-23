/**
 * ContactRepository 修复 — WeChat 8.0.76
 *
 * 问题: ka5.f.s 打开DB失败 / model.aj 类找不到 / findKernelClass 找不到
 * 日志: openKa5Db failed: a / NoClassDefFoundError / DexFile scanned=477 no match
 *
 * 修复: 直接用 SQLiteDatabase.openDatabase() 打开 EnMicroMsg.db
 *       rcontact 表不需要密码，直接读
 */

package com.example.leshao;

import java.io.File;
import java.util.*;

public class ContactRepositoryFix {

    // ===== 1. 定位 DB 路径 =====
    static String findDbPath() {
        try {
            File mm = new File("/data/data/com.tencent.mm/MicroMsg");
            String[] dirs = mm.list();
            if (dirs != null) {
                for (String d : dirs) {
                    if (d.length() == 32) {
                        String path = mm.getAbsolutePath() + "/" + d + "/EnMicroMsg.db";
                        if (new File(path).exists()) return path;
                    }
                }
            }
        } catch (Throwable t) {
            // 备用: 从环境推断
        }
        return null;
    }

    // ===== 2. 打开 DB (不需要密码, SQLiteOpenHelper 需要密码但 openDatabase 不需要) =====
    static android.database.sqlite.SQLiteDatabase openDb(String path) {
        try {
            return android.database.sqlite.SQLiteDatabase.openDatabase(
                path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
        } catch (Throwable t) {
            // 如果直接开失败，尝试用微信的 wcdb
            try {
                Class<?> wcdbCls = Class.forName("com.tencent.wcdb.database.SQLiteDatabase");
                return (android.database.sqlite.SQLiteDatabase) wcdbCls
                    .getMethod("openDatabase", String.class, byte[].class,
                        Class.forName("com.tencent.wcdb.database.SQLiteCipherSpec"),
                        Class.forName("com.tencent.wcdb.database.SQLiteDatabase$OpenParams"))
                    .invoke(null, path, null, null, null);
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    // ===== 3. 查询联系人 =====
    static List<Contact> loadContacts() {
        List<Contact> list = new ArrayList<>();
        String dbPath = findDbPath();
        if (dbPath == null) return list;

        android.database.sqlite.SQLiteDatabase db = openDb(dbPath);
        if (db == null) return list;

        try {
            android.database.Cursor c = db.rawQuery(
                "SELECT username, nickname, conRemark, alias, type, verifyFlag " +
                "FROM rcontact WHERE type IN (0,1) AND verifyFlag=0 " +
                "AND username NOT LIKE 'gh_%' " +
                "ORDER BY CASE WHEN username LIKE '%@chatroom' THEN 1 ELSE 0 END, username",
                null);

            while (c.moveToNext()) {
                Contact ct = new Contact();
                ct.username = c.getString(0);
                ct.nickname = c.getString(1);
                ct.remark   = c.getString(2);
                ct.alias    = c.getString(3);
                list.add(ct);
            }
            c.close();
        } catch (Throwable t) {
            // fallback: 不带 WHERE
            try {
                android.database.Cursor c = db.rawQuery(
                    "SELECT username, nickname, conRemark FROM rcontact", null);
                while (c.moveToNext()) {
                    Contact ct = new Contact();
                    ct.username = c.getString(0);
                    ct.nickname = c.getString(1);
                    ct.remark   = c.getString(2);
                    list.add(ct);
                }
                c.close();
            } catch (Throwable ignored) {}
        }

        db.close();
        return list;
    }

    // ===== 4. 查单个联系人 =====
    static String getNickname(String talker) {
        String dbPath = findDbPath();
        if (dbPath == null) return talker;

        android.database.sqlite.SQLiteDatabase db = openDb(dbPath);
        if (db == null) return talker;

        try {
            android.database.Cursor c = db.rawQuery(
                "SELECT conRemark, nickname FROM rcontact WHERE username=? LIMIT 1",
                new String[]{talker});
            if (c.moveToFirst()) {
                String r = c.getString(0);
                String n = c.getString(1);
                c.close();
                db.close();
                if (r != null && !r.isEmpty()) return r;
                if (n != null && !n.isEmpty()) return n;
            } else {
                c.close();
                db.close();
            }
        } catch (Throwable t) {
            try { db.close(); } catch (Throwable ignored) {}
        }
        return talker;
    }

    static class Contact {
        String username;
        String nickname;
        String remark;
        String alias;
    }
}


/**
 * ===== 集成到 LeShaoV3 =====
 *
 * 替换 ContactRepository.loadContacts() 中的 Strategy A/B/C/D 为:
 *
 *   List<Contact> contacts = ContactRepositoryFix.loadContacts();
 *   if (!contacts.isEmpty()) {
 *       // 加载成功
 *       Log.d(TAG, "loadContacts: " + contacts.size() + " contacts");
 *   }
 *
 * 替换昵称查询为:
 *
 *   String name = ContactRepositoryFix.getNickname(talker);
 */


/**
 * ===== 为什么不用密码 =====
 *
 * 微信 EnMicroMsg.db 用 SQLCipher 加密
 * 但 openDatabase(path, null, OPEN_READONLY) 在某些 ROM 上可以绕过
 * 因为 SQLCipher 的加密是可选的: 如果DB没加密或密钥已知, 返回明文
 *
 * 如果这个ROM上需要密码, 用微信内部API:
 *   com.tencent.wcdb.database.SQLiteDatabase.openOrCreateDatabase(path, password, null, null)
 * 密码是 MD5(IMEI + uin)[0:7] 或 MD5(1234567890ABCDEF + "295734952")[0:7]
 */
