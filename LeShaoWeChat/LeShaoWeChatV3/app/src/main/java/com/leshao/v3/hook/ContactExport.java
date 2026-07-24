package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.widget.Toast;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * [功能11] 通讯录批量导出 — 生产级完整实现
 * =========================================
 * 
 * 完整流程:
 *   1. 获取uin → 计算MD5 → 拼接DB路径
 *   2. 读取imei → 计算密码 → 打开SQLiteDatabase
 *   3. 查询rcontact表 → 遍历Cursor → 写入CSV
 *   4. Toast提示完成
 */
public class ContactExport {

    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
    private static boolean exportTriggered = false;
    private static ClassLoader classLoader;
    private static volatile boolean sEnabled = true;

    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static void hook(ClassLoader cl) {
        if (!sEnabled) return;
        ModuleConfig config = ModuleConfig.load(ContextManager.getPrefs());
        if (config == null || !config.contactExportEnabled) return;
        classLoader = cl;
        hookSelectContactUI(cl);
    }

    private static void hookSelectContactUI(ClassLoader cl) {
        try {
            Class<?> selectUI = XposedHelpers.findClass(
                    "com.tencent.mm.ui.contact.SelectContactUI", cl);
            XposedBridge.hookAllMethods(selectUI, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!exportTriggered) return;
                    exportTriggered = false;
                    new Thread(new Runnable() {
                        public void run() { exportContacts(); }
                    }).start();
                }
            });
            XposedBridge.log("[ContactExport] Hook完成");
        } catch (Throwable t) {
            XposedBridge.log("[ContactExport] Hook失败: " + t.getMessage());
        }
    }

    public static void triggerExport() { exportTriggered = true; }

    private static void exportContacts() {
        SQLiteDatabase db = null;
        Cursor cursor = null;
        FileWriter fw = null;
        try {
            String dbPath = getDbPath();
            String pwd = getDbPassword();
            if (dbPath == null || pwd == null) {
                XposedBridge.log("[ContactExport] 无法获取DB路径或密码");
                return;
            }

            File dbFile = new File(dbPath);
            if (!dbFile.exists()) {
                XposedBridge.log("[ContactExport] DB不存在: " + dbPath);
                return;
            }

            db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY);
            if (db == null) {
                XposedBridge.log("[ContactExport] 无法打开DB");
                return;
            }

            cursor = db.rawQuery(
                    "SELECT username, nickname, alias, conRemark, type, verifyFlag, " +
                    "deleteFlag, contactLabelList, chatroomFlag, sex " +
                    "FROM rcontact ORDER BY type, nickname", null);
            if (cursor == null || cursor.getCount() == 0) {
                XposedBridge.log("[ContactExport] rcontact表为空");
                if (db != null) db.close();
                return;
            }

            File outDir = new File("/sdcard/LeShaoV3Logs/");
            outDir.mkdirs();
            File outFile = new File(outDir, "contacts_" + sdf.format(new Date()) + ".csv");
            fw = new FileWriter(outFile);

            fw.write("\uFEFF");
            fw.write("序号,username,nickname,alias,备注名,type,类型说明," +
                    "verifyFlag,deleteFlag,chatroomFlag,性别\n");

            int count = 0;
            while (cursor.moveToNext()) {
                String username = nvl(cursor.getString(0));
                String nickname = nvl(cursor.getString(1));
                String alias = nvl(cursor.getString(2));
                String conRemark = nvl(cursor.getString(3));
                int type = cursor.getInt(4);
                int verifyFlag = cursor.getInt(5);
                int deleteFlag = cursor.getInt(6);
                int chatroomFlag = cursor.getInt(8);
                int sex = cursor.getInt(9);

                count++;
                fw.write(count + "," +
                        csv(username) + "," + csv(nickname) + "," +
                        csv(alias) + "," + csv(conRemark) + "," +
                        type + "," + typeName(type) + "," +
                        verifyFlag + "," + deleteFlag + "," +
                        chatroomFlag + "," + sexName(sex) + "\n");
            }

            final int finalCount = count;
            final String path = outFile.getAbsolutePath();
            XposedBridge.log("[ContactExport] ✅ 导出完成: " + path + " (" + finalCount + "人)");

            showToast("✅ 通讯录导出完成: " + finalCount + "人\n" + path);
        } catch (Throwable t) {
            XposedBridge.log("[ContactExport] 导出失败: " + t.getMessage());
        } finally {
            try { if (cursor != null) cursor.close(); } catch (Throwable ignored) {}
            try { if (db != null) db.close(); } catch (Throwable ignored) {}
            try { if (fw != null) fw.close(); } catch (Throwable ignored) {}
        }
    }

    private static String getDbPath() {
        try {
            android.content.Context ctx = getContext();
            if (ctx == null) return null;
            android.content.SharedPreferences sp = ctx.getSharedPreferences(
                    "system_config_prefs", 0);
            long uin = sp.getLong("default_uin", 0);
            if (uin == 0) {
                uin = sp.getInt("default_uin", 0);
            }
            if (uin == 0) {
                XposedBridge.log("[ContactExport] uin=0, 尝试从mp0.b获取dataDir");
                try {
                    Class<?> mp0b = XposedHelpers.findClass("mp0.b", classLoader);
                    String dataDir = (String) XposedHelpers.callStaticMethod(mp0b, "X");
                    if (dataDir != null) {
                        File microMsgDir = new File(dataDir, "MicroMsg");
                        if (microMsgDir.exists()) {
                            for (File f : microMsgDir.listFiles()) {
                                if (f.isDirectory()) {
                                    File db = new File(f, "EnMicroMsg.db");
                                    if (db.exists()) return db.getAbsolutePath();
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
                return null;
            }
            String hash = md5(String.valueOf(uin));
            return "/data/data/com.tencent.mm/MicroMsg/" + hash + "/EnMicroMsg.db";
        } catch (Throwable t) { return null; }
    }

    private static String getDbPassword() {
        try {
            String imei = "1234567890ABCDEF";
            try {
                Class<?> w0 = XposedHelpers.findClass("wo.w0", classLoader);
                Object imeiObj = XposedHelpers.callStaticMethod(w0, "g", Boolean.TRUE);
                if (imeiObj != null) imei = imeiObj.toString();
            } catch (Throwable ignored) {}

            android.content.Context ctx = getContext();
            long uin = ctx.getSharedPreferences("system_config_prefs", 0).getLong("default_uin", 0);
            if (uin == 0) uin = ctx.getSharedPreferences("system_config_prefs", 0).getInt("default_uin", 0);

            try {
                Class<?> kk = XposedHelpers.findClass("kk.k", classLoader);
                String full = (String) XposedHelpers.callStaticMethod(kk, "g",
                        (imei + uin).getBytes("UTF-8"));
                if (full != null && full.length() >= 7) return full.substring(0, 7);
            } catch (Throwable ignored) {}

            return md5(imei + uin).substring(0, 7);
        } catch (Throwable t) { return "0000000"; }
    }

    private static android.content.Context getContext() {
        return com.leshao.v3.ContextManager.getAppContext();
    }

    private static void showToast(final String msg) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                try {
                    android.content.Context ctx = getContext();
                    if (ctx != null) Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {}
            }
        });
    }

    private static String md5(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }

    private static String nvl(String s) { return s == null ? "" : s; }
    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n"))
            return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    private static String typeName(int type) {
        switch (type) {
            case 0: return "好友";
            case 1: return "对方未添加";
            case 2: return "被对方删除";
            case 3: return "已删除对方";
            case 4: return "拉黑";
            case 33: return "等待确认";
            default: return "其他(" + type + ")";
        }
    }

    private static String sexName(int sex) {
        switch (sex) {
            case 1: return "男";
            case 2: return "女";
            default: return "未知";
        }
    }
}
