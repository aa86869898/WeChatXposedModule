package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;
import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;

import android.content.SharedPreferences;
import android.content.Context;
import android.database.Cursor;
import android.view.Menu;
import android.view.MenuItem;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MsgExport {

    private static final String TAG = "MsgExport";
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    private static final SimpleDateFormat fileSdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
    private static ClassLoader sCL;
    private static volatile boolean sEnabled = true;

    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static void hook(ClassLoader cl) {
        if (!sEnabled) return;
        sCL = cl;
        ModuleConfig config = ModuleConfig.load(ContextManager.getPrefs());
        if (config == null || !config.msgExportEnabled) return;
        hookMenu(cl);
    }

    private static void hookMenu(ClassLoader cl) {
        try {
            Class<?> chattingUI = VersionCompat.findChattingUIClass(cl);
            if (chattingUI == null) {
                for (String cn : new String[]{
                    "com.tencent.mm.ui.chatting.ChattingUI",
                    "com.tencent.mm.ui.chatting.BaseChattingUI",
                    "com.tencent.mm.ui.chatting.v2.ChattingUI",
                    "com.tencent.mm.ui.chatting.ChattingUIFragment",
                    "com.tencent.mm.ui.chatting.v2.ChattingUIFragment"
                }) {
                    try { chattingUI = cl.loadClass(cn); break; } catch (Throwable ignored) {}
                }
            }
            if (chattingUI == null) return;

            XposedBridge.hookAllMethods(chattingUI, "onCreateOptionsMenu", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Menu menu = (Menu) param.args[0];
                    if (menu == null) return;
                    menu.add(0, 99980, 0, "导出聊天记录(TXT)");
                    menu.add(0, 99981, 0, "导出聊天记录(HTML)");
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
                        if (talker != null && !talker.isEmpty()) {
                            final String t = talker;
                            final String f = format;
                            new Thread(() -> doExport(t, f)).start();
                        } else {
                            showToast("无法获取当前聊天对象");
                        }
                        param.setResult(true);
                    }
                }
            });
            LogWriter.log(TAG, "menu hook OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "menu hook err: " + t.getClass().getSimpleName());
        }
    }

    private static String getTalker(Object fragment) {
        try {
            try {
                Object args = XposedHelpers.callMethod(fragment, "getArguments");
                if (args instanceof android.os.Bundle) {
                    String s = ((android.os.Bundle) args).getString("Chat_User");
                    if (s != null && !s.isEmpty()) return s;
                }
            } catch (Throwable ignored) {}

            for (String fieldName : new String[]{"mFooter", "talker", "mTalker", "username", "mUsername"}) {
                try {
                    Object val = XposedHelpers.getObjectField(fragment, fieldName);
                    if (val instanceof String && !((String) val).isEmpty()) return (String) val;
                } catch (Throwable ignored) {}
            }

            try {
                Object val = XposedHelpers.callMethod(fragment, "getTalkerUserName");
                if (val instanceof String && !((String) val).isEmpty()) return (String) val;
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            LogWriter.log(TAG, "getTalker err: " + t.getMessage());
        }
        return null;
    }

    private static void doExport(String talker, String format) {
        Object db = null;
        Cursor cursor = null;
        FileWriter fw = null;
        try {
            Context appCtx = ContextManager.getAppContext();
            if (appCtx == null) {
                showToast("Context not available");
                return;
            }

            db = openWeChatDb(appCtx);
            if (db == null) {
                showToast("无法打开微信数据库");
                return;
            }

            Method rawQuery = null;
            for (String m : new String[]{"u", "rawQuery", "v", "w"}) {
                try { rawQuery = db.getClass().getDeclaredMethod(m, String.class, String[].class); break; }
                catch (Throwable ignored) {}
            }
            Method getCursor = null;
            for (String m : new String[]{"d", "getCursor", "e", "f"}) {
                try { getCursor = db.getClass().getDeclaredMethod(m); break; }
                catch (Throwable ignored) {}
            }
            if (rawQuery == null || getCursor == null) {
                showToast("数据库方法获取失败");
                return;
            }

            cursor = (Cursor) rawQuery.invoke(db,
                "SELECT msgContent, createTime, isSend, type FROM message WHERE talker=? ORDER BY createTime ASC LIMIT 50000",
                new String[]{talker});

            if (cursor == null || cursor.getCount() == 0) {
                showToast("该会话无消息记录");
                try { getCursor.invoke(db); } catch (Throwable ignored) {}
                return;
            }

            File dir = new File(appCtx.getFilesDir(), "leshao_v3_logs/exports/");
            dir.mkdirs();
            String safeName = talker.replace("@", "_").replace("/", "_").replace(":", "_");
            File out = new File(dir, safeName + "_" + fileSdf.format(new Date()) + "." + format);
            fw = new FileWriter(out);

            if ("html".equals(format)) {
                fw.write("<!DOCTYPE html><html><head><meta charset='UTF-8'>"
                    + "<title>" + escapeHtml(talker) + "</title>"
                    + "<style>body{font-family:sans-serif;max-width:800px;margin:auto;padding:10px}"
                    + ".me{color:#07C160;text-align:right}.other{color:#333}"
                    + ".time{font-size:10px;color:#999}.bubble{display:inline-block;max-width:70%;"
                    + "padding:8px 12px;border-radius:8px;margin:2px 0}"
                    + ".me .bubble{background:#95EC69}.other .bubble{background:#fff;border:1px solid #eee}"
                    + "</style></head><body><h2>" + escapeHtml(talker) + "</h2><hr>\n");
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
                    fw.write("<div class='" + cls + "'>"
                        + "<div class='bubble'>" + escapeHtml(content) + "</div>"
                        + "<div class='time'>" + timeStr + " [" + typeStr + "]</div>"
                        + "</div>\n");
                } else {
                    fw.write(sender + " " + timeStr + " [" + typeStr + "]\n" + content + "\n\n");
                }
                count++;
            }

            if ("html".equals(format)) fw.write("</body></html>");
            fw.close();

            final int finalCount = count;
            final String path = out.getAbsolutePath();
            LogWriter.log(TAG, "export done: " + path + " (" + finalCount + " msg)");
            showToast("导出完成: " + finalCount + "条消息\n" + path);

            try { getCursor.invoke(db); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            LogWriter.log(TAG, "export err: " + t.getClass().getSimpleName() + " " + t.getMessage());
            showToast("导出失败: " + t.getClass().getSimpleName());
        } finally {
            try { if (cursor != null) cursor.close(); } catch (Throwable ignored) {}
            try { if (db != null) closeDbReflect(db); } catch (Throwable ignored) {}
            try { if (fw != null) fw.close(); } catch (Throwable ignored) {}
        }
    }

    /** 遍历候选混淆方法名关闭数据库连接，避免单个方法名漂移导致连接泄漏 */
    private static void closeDbReflect(Object db) {
        for (String n : new String[]{"c", "close", "q"}) {
            try { db.getClass().getMethod(n).invoke(db); return; } catch (Throwable ignored) {}
        }
    }

    private static Object openWeChatDb(Context appCtx) {
        try {
            long uin = getUin(appCtx);
            if (uin <= 0) {
                LogWriter.log(TAG, "uin=0");
                return null;
            }

            String imei = VersionCompat.getImei(sCL);
            String password = md5(imei + uin).substring(0, 7);

            String base = VersionCompat.getBaseDir(sCL, appCtx);
            String hash = VersionCompat.getDbHash(sCL, (int) uin);
            String dbPath = base + "MicroMsg/" + hash + "/EnMicroMsg.db";

            Class<?> dbOpener = VersionCompat.findDbOpenerClass(sCL);
            if (dbOpener == null) {
                LogWriter.log(TAG, "dbOpener class not found");
                return null;
            }
            return VersionCompat.openDatabase(dbOpener, dbPath, password);
        } catch (Throwable t) {
            LogWriter.log(TAG, "openWeChatDb err: " + t.getClass().getSimpleName());
        }
        return null;
    }

    private static long getUin(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences("system_config_prefs", 0);
            Object uv = sp.getAll().get("default_uin");
            if (uv != null) return Long.parseLong(uv.toString());
        } catch (Throwable ignored) {}
        return 0;
    }

    private static void showToast(final String msg) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                android.widget.Toast.makeText(ContextManager.getAppContext(),
                        msg, android.widget.Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {}
        });
    }

    private static String md5(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }

    private static String nvl(String s) { return s == null ? "" : s; }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\n", "<br>");
    }

    private static String typeName(int t) {
        switch (t) {
            case 1: return "文本";
            case 3: return "图片";
            case 34: return "语音";
            case 43: return "视频";
            case 47: return "表情";
            case 49: return "链接";
            case 10002: return "已撤回";
            default: return "类型" + t;
        }
    }
}
