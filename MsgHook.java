package com.example.leshao;

import android.os.Handler;
import android.os.Looper;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * TTS自动播报 - 最终生产版
 * 修复: 1.群聊wxid前缀  2.昵称不显示  3.性能优化
 */
public class MsgHook {

    static int sCount = 0;
    static Handler sMainHandler = new Handler(Looper.getMainLooper());

    public static void hook(ClassLoader cl) {
        try {
            Class<?> x9Cls = cl.loadClass("e01.x9");
            Class<?> e9Cls = cl.loadClass("com.tencent.mm.storage.e9");

            for (java.lang.reflect.Method m : x9Cls.getDeclaredMethods()) {
                if (m.getName().equals("n") && m.getParameterCount() == 2
                    && m.getParameterTypes()[0] == e9Cls) {
                    XposedHelpers.findAndHookMethod(x9Cls, "n", e9Cls, m.getParameterTypes()[1],
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                onMsg(p.args[0]);
                            }
                        });
                    XposedBridge.log("[MsgHook] n OK");
                    break;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[MsgHook] FAIL: " + t);
        }
    }

    static void onMsg(Object e9) {
        try {
            int type = (int) XposedHelpers.callMethod(e9, "getType");
            if (type != 1 && type != 3 && type != 34 && type != 43 && type != 48) return;

            String talker = (String) XposedHelpers.callMethod(e9, "N0");
            String content = (String) XposedHelpers.callMethod(e9, "j");
            if (content == null || content.isEmpty()) return;

            // 过滤XML元数据
            if (content.charAt(0) == '<') return;

            final String fTalker = talker;
            final String fContent = content;
            final int fType = type;
            sMainHandler.post(() -> speak(fTalker, fContent, fType));

        } catch (Throwable ignored) {}
    }

    static void speak(String talker, String content, int type) {
        try {
            // 1. 获取昵称 — 直接查 DB (不依赖 j1)
            String name = NickResolver.get(talker);

            // 2. 处理群聊
            boolean isGroup = talker.endsWith("@chatroom");

            // 3. 去wxid前缀 (群聊消息格式: wxid_xxx: 内容)
            String text = content;
            text = text.replaceFirst("^wxid_[a-z0-9]+:[\u00A0\\s]*", "");
            text = text.replaceAll("\\[\\w+\\]", ""); // 去[微笑]等emoji编码
            text = text.replaceAll("https?://\\S+", "链接");
            text = text.replace("\n", " ").trim();
            if (text.length() > 200) text = text.substring(0, 200) + "等";
            if (text.isEmpty()) return;

            String speak;
            switch (type) {
                case 1:  speak = name + "说：" + text; break;
                case 3:  speak = name + "发来一张照片"; break;
                case 34: speak = name + "发来语音"; break;
                case 43: speak = name + "发来一段视频"; break;
                case 48: speak = name + "发来定位"; break;
                default: return;
            }

            if (TtsEngine.isReady()) {
                TtsEngine.speak(speak);
            }
        } catch (Throwable t) {
            XposedBridge.log("[TTS-ERR] " + t);
        }
    }
}


/**
 * ===== 昵称解析 — 直接查微信 SQLite DB =====
 */
class NickResolver {
    static java.util.Map<String, String> cache = new java.util.HashMap<>();
    static android.database.sqlite.SQLiteDatabase sDb;
    static String sDbPath;

    static {
        try {
            // 扫 MicroMsg 目录找 EnMicroMsg.db
            java.io.File mm = new java.io.File("/data/data/com.tencent.mm/MicroMsg");
            String[] dirs = mm.list();
            if (dirs != null) {
                for (String d : dirs) {
                    if (d.length() == 32) {
                        sDbPath = mm.getAbsolutePath() + "/" + d + "/EnMicroMsg.db";
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    static String get(String talker) {
        if (talker == null || talker.isEmpty()) return "";
        String cached = cache.get(talker);
        if (cached != null) return cached;

        String name = talker;

        try {
            if (sDb == null && sDbPath != null) {
                sDb = android.database.sqlite.SQLiteDatabase.openDatabase(
                    sDbPath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            }
            if (sDb != null) {
                android.database.Cursor c = sDb.rawQuery(
                    "SELECT conRemark, nickname FROM rcontact WHERE username=? LIMIT 1",
                    new String[]{talker});
                if (c.moveToFirst()) {
                    String remark = c.getString(0);
                    String nick = c.getString(1);
                    if (remark != null && !remark.isEmpty()) name = remark;
                    else if (nick != null && !nick.isEmpty()) name = nick;
                }
                c.close();
            }
        } catch (Throwable ignored) {}

        if (name.equals(talker) && talker.endsWith("@chatroom")) {
            name = "群聊";
        }
        if (name.equals(talker) && talker.startsWith("gh_")) {
            name = "公众号";
        }
        if (name.length() > 20) name = name.substring(0, 20);

        cache.put(talker, name);
        return name;
    }
}
