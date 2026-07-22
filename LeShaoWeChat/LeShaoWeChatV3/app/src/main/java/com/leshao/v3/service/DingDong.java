package com.leshao.v3.service;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.model.WeChatMessage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

import de.robv.android.xposed.XposedHelpers;

public class DingDong {

    private static final String TAG = "DingDong";

    public static void process(WeChatMessage msg, ModuleConfig cfg) {
        if (msg == null || cfg == null) return;
        if (!cfg.dianGeEnabled) return;
        if (msg.type != WeChatMessage.TYPE_TEXT) return;

        String content = msg.content != null ? msg.content.trim() : "";
        if (content.isEmpty()) return;

        new Thread(() -> {
            try {
                String reply = null;

                if (content.startsWith("点歌 ")) {
                    reply = searchMusic(content.substring(3).trim());
                } else if (content.startsWith("天气 ")) {
                    reply = queryWeather(content.substring(3).trim());
                } else if (content.equals("笑话") || content.startsWith("笑话")) {
                    reply = randomJoke();
                } else if (content.startsWith("金句") || content.equals("每日一句")) {
                    reply = randomQuote();
                } else if (content.startsWith("视频 ")) {
                    reply = parseVideo(content.substring(3).trim());
                }

                if (reply != null && !reply.isEmpty()) {
                    sendReply(msg.talker, reply);
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "dingdong error: " + t.getMessage());
            }
        }, "leshao-dingdong").start();
    }

    private static String searchMusic(String keyword) {
        try {
            return "【点歌】已收到点歌请求: " + keyword + "\n正在搜索中...";
        } catch (Throwable t) {
            LogWriter.log(TAG, "searchMusic error: " + t.getMessage());
            return "【点歌】搜索失败: " + keyword;
        }
    }

    private static String queryWeather(String city) {
        try {
            String apiUrl = "https://wttr.in/" + URLEncoder.encode(city, "UTF-8") + "?format=3&lang=zh";
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return "【天气】" + sb.toString().trim();
        } catch (Throwable t) {
            return "【天气】查询失败: " + city;
        }
    }

    private static String randomJoke() {
        String[] jokes = {
            "程序员最讨厌康熙的哪个儿子？——胤禩，因为他是八阿哥(bug)",
            "为什么程序员总是分不清万圣节和圣诞节？——因为 Oct 31 == Dec 25",
            "一个程序员在公园里迷路了，朋友说：你为什么不试试二分查找？",
            "小明：我写的代码没有bug。测试：那你跑一下。小明：跑了，没有bug。测试：那bug怎么还没修？",
        };
        return "【笑话】" + jokes[(int) (System.currentTimeMillis() % jokes.length)];
    }

    private static String randomQuote() {
        String[] quotes = {
            "人生如逆旅，我亦是行人。——苏轼",
            "生活不止眼前的苟且，还有诗和远方。",
            "与其临渊羡鱼，不如退而结网。",
            "保持热爱，奔赴山海。",
        };
        return "【每日金句】" + quotes[(int) (System.currentTimeMillis() % quotes.length)];
    }

    private static String parseVideo(String link) {
        return "【视频解析】链接已收到: " + link + "\n解析功能开发中，敬请期待...";
    }

    private static void sendReply(String talker, String text) {
        try {
            ClassLoader cl = ContextManager.getClassLoader();
            Class<?> msgClass = cl.loadClass("com.tencent.mm.modelmulti.n");
            Object msg = XposedHelpers.newInstance(msgClass, talker, text, 1);
            XposedHelpers.callStaticMethod(msgClass, "b", msg);
        } catch (Throwable t) {
            LogWriter.log(TAG, "sendReply FAILED: " + t.getMessage());
        }
    }
}
