package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.view.Menu;
import android.view.MenuItem;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * [功能38/43] 消息批量导出 — 生产级完整实现
 * =========================================
 * 
 * 完整流程:
 *   聊天界面右上角菜单 → 点"导出聊天记录" → 后台线程读取message表
 *   → 按talker过滤 → 格式化TXT/HTML → 写入/sdcard/LeShaoV3Logs/exports/
 *   → Toast通知完成
 */
public class MsgExport {

    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final SimpleDateFormat fileSdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
    private static ClassLoader classLoader;
    private static boolean sEnabled = false;

    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static void hook(ClassLoader cl) {
        if (!sEnabled) return;
        classLoader = cl;
        hookMenu(cl);
    }

    private static void hookMenu(ClassLoader cl) {
        try {
            Class<?> chattingUI = XposedHelpers.findClass(
                    "com.tencent.mm.ui.chatting.ChattingUIFragment", cl);

            XposedBridge.hookAllMethods(chattingUI, "onCreateOptionsMenu", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Menu menu = (Menu) param.args[0];
                    if (menu != null) {
                        menu.add(0, 99980, 0, "导出聊天记录(TXT)");
                        menu.add(0, 99981, 0, "导出聊天记录(HTML)");
                    }
                }
            });

            XposedBridge.hookAllMethods(chattingUI, "onOptionsItemSelected", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    MenuItem item = (MenuItem) param.args[0];
                    int id = item.getItemId();
                    if (id == 99980 || id == 99981) {
                        String format = id == 99980 ? "txt" : "html";
                        String talker = getTalker(param.thisObject);
                        if (talker != null) {
                            final String t = talker;
                            final String f = format;
                            new Thread(new Runnable() {
                                public void run() { doExport(t, f); }
                            }).start();
                        }
                        param.setResult(true);
                    }
                }
            });
            XposedBridge.log("[MsgExport] 菜单Hook完成");
        } catch (Throwable t) {
            XposedBridge.log("[MsgExport] 菜单Hook失败: " + t.getMessage());
        }
    }

    private static String getTalker(Object fragment) {
        try {
            try {
                android.os.Bundle args = (android.os.Bundle) XposedHelpers.callMethod(fragment, "getArguments");
                if (args != null) {
                    String s = args.getString("Chat_User");
                    if (s != null) return s;
                }
            } catch (Throwable ignored) {}
            try {
                Object footer = XposedHelpers.getObjectField(fragment, "mFooter");
                if (footer != null) return (String) XposedHelpers.callMethod(footer, "getTalkerUserName");
            } catch (Throwable ignored) {}
        } catch (Throwable t) {}
        return null;
    }

    private static void doExport(String talker, String format) {
        SQLiteDatabase db = null;
        Cursor cursor = null;
        FileWriter fw = null;
        try {
            String dbPath = findDbPath();
            if (dbPath == null) {
                XposedBridge.log("[MsgExport] 找不到数据库");
                showToast("❌ 找不到数据库文件");
                return;
            }

            db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY);
            if (db == null) return;

            cursor = db.rawQuery(
                    "SELECT msgContent, createTime, isSend, type, msgId " +
                    "FROM message WHERE talker=? ORDER BY createTime ASC LIMIT 50000",
                    new String[]{talker});

            if (cursor == null || cursor.getCount() == 0) {
                XposedBridge.log("[MsgExport] 无消息");
                showToast("该会话无消息记录");
                if (db != null) db.close();
                return;
            }

            File dir = new File("/sdcard/LeShaoV3Logs/exports/");
            dir.mkdirs();
            String safeName = talker.replace("@", "_").replace("/", "_").replace(":", "_");
            File out = new File(dir, safeName + "_" + fileSdf.format(new Date()) + "." + format);
            fw = new FileWriter(out);

            if ("html".equals(format)) {
                fw.write("<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
                        "<title>" + escapeHtml(talker) + "</title>" +
                        "<style>body{font-family:sans-serif;max-width:800px;margin:auto;padding:10px}" +
                        ".me{color:#07C160;text-align:right}.other{color:#333}" +
                        ".time{font-size:10px;color:#999}.bubble{display:inline-block;max-width:70%;" +
                        "padding:8px 12px;border-radius:8px;margin:2px 0}" +
                        ".me .bubble{background:#95EC69}.other .bubble{background:#fff;border:1px solid #eee}" +
                        "</style></head><body><h2>" + escapeHtml(talker) + "</h2><hr>\n");
            }

            int count = 0;
            while (cursor.moveToNext()) {
                String content = nvl(cursor.getString(0));
                long createTime = cursor.getLong(1);
                int isSend = cursor.getInt(2);
                int type = cursor.getInt(3);

                String timeStr = sdf.format(new Date(createTime));
                String sender = isSend == 1 ? "我" : "对方";
                String typeStr = typeName(type);

                if ("html".equals(format)) {
                    String cls = isSend == 1 ? "me" : "other";
                    fw.write("<div class='" + cls + "'><div class='bubble'>" +
                            escapeHtml(content) +
                            "</div><div class='time'>" + timeStr + " [" + typeStr + "]</div></div>\n");
                } else {
                    fw.write(sender + " " + timeStr + " [" + typeStr + "]\n" + content + "\n\n");
                }
                count++;
            }

            if ("html".equals(format)) fw.write("</body></html>");
            fw.close();

            final int finalCount = count;
            final String path = out.getAbsolutePath();
            XposedBridge.log("[MsgExport] ✅ 导出完成: " + path + " (" + finalCount + "条)");
            showToast("✅ 导出完成: " + finalCount + "条消息\n" + path);
        } catch (Throwable t) {
            XposedBridge.log("[MsgExport] 失败: " + t.getMessage());
            showToast("❌ 导出失败: " + t.getMessage());
        } finally {
            try { if (cursor != null) cursor.close(); } catch (Throwable ignored) {}
            try { if (db != null) db.close(); } catch (Throwable ignored) {}
            try { if (fw != null) fw.close(); } catch (Throwable ignored) {}
        }
    }

    private static String findDbPath() {
        try {
            android.content.Context ctx = com.leshao.v3.ContextManager.getAppContext();
            if (ctx == null) return null;
            long uin = ctx.getSharedPreferences("system_config_prefs", 0).getLong("default_uin", 0);
            if (uin == 0) uin = ctx.getSharedPreferences("system_config_prefs", 0).getInt("default_uin", 0);
            if (uin == 0) {
                try {
                    Class<?> mp0b = XposedHelpers.findClass("mp0.b", classLoader);
                    String dataDir = (String) XposedHelpers.callStaticMethod(mp0b, "X");
                    if (dataDir != null) {
                        File mm = new File(dataDir, "MicroMsg");
                        if (mm.exists()) for (File f : mm.listFiles()) {
                            if (f.isDirectory()) {
                                File db = new File(f, "EnMicroMsg.db");
                                if (db.exists()) return db.getAbsolutePath();
                            }
                        }
                    }
                } catch (Throwable ignored) {}
                return null;
            }
            return "/data/data/com.tencent.mm/MicroMsg/" + md5(String.valueOf(uin)) + "/EnMicroMsg.db";
        } catch (Throwable t) { return null; }
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

    private static void showToast(final String msg) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                try {
                    android.widget.Toast.makeText(com.leshao.v3.ContextManager.getAppContext(),
                            msg, android.widget.Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {}
            }
        });
    }

    private static String nvl(String s) { return s == null ? "" : s; }
    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }
    private static String typeName(int t) {
        switch (t) { case 1: return "文本"; case 3: return "图片"; case 34: return "语音";
        case 43: return "视频"; case 47: return "表情"; case 49: return "链接"; case 10002: return "已撤回";
        default: return "类型"+t; }
    }
}
