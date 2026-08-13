package com.leshao.v3.ai;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.leshao.v3.LogWriter;

public class SummaryFeature {

    private static final String TAG = "SummaryFeature";

    public static void injectSummaryButton(Activity act, ClassLoader cl) {
        try {
            if (act == null) return;
            View root = act.getWindow().getDecorView();
            ViewGroup titleBar = findTitleBar(root);
            if (titleBar == null) { LogWriter.log(TAG, "注入总结按钮: 未找到标题栏"); return; }
            if (root.getTag(0x7f000001) != null) return;
            root.setTag(0x7f000001, Boolean.TRUE);
            LogWriter.log(TAG, "注入总结按钮: titleBar=" + titleBar.getClass().getName());

            TextView btn = new TextView(act);
            btn.setText("🤖总结");
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(14);
            btn.setPadding(20, 8, 20, 8);
            btn.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.END | Gravity.CENTER_VERTICAL);
            lp.rightMargin = 8;
            btn.setOnClickListener(v -> doSummary(act, cl));
            if (titleBar instanceof FrameLayout) titleBar.addView(btn, lp);
            else titleBar.addView(btn);
        } catch (Throwable t) {
            android.util.Log.e("WxAi", "注入总结按钮失败", t);
        }
    }

    public static void doSummary(Activity act, ClassLoader cl) {
        String talker = ChatHooks.currentTalker();
        LogWriter.log(TAG, "doSummary: talker=" + talker + " chatOpen=" + ChatHooks.isChatWindowOpen());
        if (talker.isEmpty()) {
            Toast.makeText(act, "未获取到当前会话", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(act, "正在生成总结…", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            MessageReader reader = new MessageReader(cl);
            List<MessageReader.ChatMsg> msgs = reader.readRecentFull(talker, AiConfig.summaryCount());
            ChatHooks.MAIN.post(() -> {
                int textCount = MessageReader.countText(msgs);
                if (textCount == 0) {
                    Toast.makeText(act, "暂无文本消息可总结", Toast.LENGTH_SHORT).show();
                    return;
                }
                String dialogText = MessageReader.toDialogText(msgs);
                final SummaryStats stats = SummaryStats.build(msgs);
                String dataSummary = stats.toDataSummary();

                List<AiClient.ChatMessage> req = new ArrayList<>();
                req.add(new AiClient.ChatMessage("user",
                        dataSummary + "\n\n【聊天记录】\n" + dialogText));
                AiClient.chatAsync(AiConfig.promptSummary(), req, new AiClient.Callback() {
                    @Override public void onResult(String text) {
                        ChatHooks.MAIN.post(() -> SummaryReportDialog.show(act, text, stats));
                    }
                    @Override public void onError(String msg) {
                        ChatHooks.MAIN.post(() -> Toast.makeText(act, "总结失败: " + msg, Toast.LENGTH_LONG).show());
                    }
                });
            });
        }).start();
    }

    public static class SummaryStats {
        public int total, meCount, otherCount;
        public int[] hourDist = new int[24];

        public int textCount, imageCount, voiceCount, emojiCount, videoCount, appCount, otherTypeCount;
        public int meChars, otherChars, maxLen;
        public long firstTime, lastTime;
        public int gapCount, myGapCount, otherGapCount;
        public long gapSum, myGapSum, otherGapSum;
        public List<String> topKeywords = new ArrayList<>();

        public static SummaryStats build(List<MessageReader.ChatMsg> msgs) {
            SummaryStats s = new SummaryStats();
            s.total = msgs.size();
            Calendar c = Calendar.getInstance();

            for (MessageReader.ChatMsg m : msgs) {
                if (m.role.equals("me")) s.meCount++; else s.otherCount++;
                c.setTimeInMillis(m.time);
                s.hourDist[c.get(Calendar.HOUR_OF_DAY)]++;
                if (s.firstTime == 0 || m.time < s.firstTime) s.firstTime = m.time;
                if (m.time > s.lastTime) s.lastTime = m.time;

                switch (m.type) {
                    case AiConst.TYPE_TEXT: s.textCount++; break;
                    case AiConst.TYPE_IMAGE: s.imageCount++; break;
                    case AiConst.TYPE_VOICE: s.voiceCount++; break;
                    case AiConst.TYPE_EMOJI: s.emojiCount++; break;
                    case AiConst.TYPE_VIDEO: s.videoCount++; break;
                    case AiConst.TYPE_APPMSG: s.appCount++; break;
                    default: s.otherTypeCount++; break;
                }
            }

            List<MessageReader.ChatMsg> sorted = new ArrayList<>(msgs);
            sorted.sort((a, b) -> Long.compare(a.time, b.time));
            long prevTime = -1;
            String prevRole = null;
            for (MessageReader.ChatMsg m : sorted) {
                if (m.isText() && m.content != null) {
                    int len = m.content.length();
                    if (m.role.equals("me")) s.meChars += len; else s.otherChars += len;
                    if (len > s.maxLen) s.maxLen = len;
                }
                if (prevTime > 0) {
                    long gap = m.time - prevTime;
                    if (gap >= 0 && gap < 3600_000L) {
                        s.gapSum += gap; s.gapCount++;
                        if (m.role.equals("me") && "other".equals(prevRole)) { s.myGapSum += gap; s.myGapCount++; }
                        else if (m.role.equals("other") && "me".equals(prevRole)) { s.otherGapSum += gap; s.otherGapCount++; }
                    }
                }
                prevTime = m.time;
                prevRole = m.role;
            }

            s.topKeywords = extractTopKeywords(sorted, 10);
            return s;
        }

        int avgGapSec(long sum, int count) {
            return count > 0 ? (int) (sum / count / 1000) : -1;
        }

        String peakHours() {
            int best = 0, bestIdx = 0;
            for (int i = 0; i < 24; i++) if (hourDist[i] > best) { best = hourDist[i]; bestIdx = i; }
            return bestIdx + ":00-" + (bestIdx + 1) + ":00";
        }

        String fmt(long ms) {
            return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(ms));
        }

        public String spanText() {
            if (firstTime <= 0 || lastTime <= 0) return "未知";
            int days = (int) ((lastTime - firstTime) / 86_400_000L) + 1;
            return days + " 天（" + fmt(firstTime) + " ~ " + fmt(lastTime) + "）";
        }

        public String replySpeedText() {
            int myAvg = avgGapSec(myGapSum, myGapCount);
            int otherAvg = avgGapSec(otherGapSum, otherGapCount);
            if (myAvg < 0 && otherAvg < 0) return "数据不足";
            return "我 " + (myAvg >= 0 ? fmtSec(myAvg) : "未知")
                    + " / 对方 " + (otherAvg >= 0 ? fmtSec(otherAvg) : "未知");
        }

        private static String fmtSec(int sec) {
            if (sec < 60) return sec + " 秒";
            if (sec < 3600) return (sec / 60) + " 分";
            return (sec / 3600) + " 小时";
        }

        public String toDataSummary() {
            int days = (int) ((lastTime - firstTime) / 86_400_000L) + 1;
            StringBuilder sb = new StringBuilder();
            sb.append("【数据统计】\n");
            sb.append("消息总数：").append(total)
              .append("（我 ").append(meCount).append(" / 对方 ").append(otherCount).append("）\n");
            sb.append("消息类型：文本 ").append(textCount)
              .append("、图片 ").append(imageCount)
              .append("、语音 ").append(voiceCount)
              .append("、表情 ").append(emojiCount)
              .append("、视频 ").append(videoCount)
              .append("、链接/文件 ").append(appCount)
              .append("、其他 ").append(otherTypeCount).append("\n");
            sb.append("字数：我 ").append(meChars)
              .append(" 字 / 对方 ").append(otherChars)
              .append(" 字，最长消息 ").append(maxLen).append(" 字\n");
            if (firstTime > 0 && lastTime > 0) {
                sb.append("时间跨度：").append(days).append(" 天（")
                  .append(fmt(firstTime)).append(" ~ ").append(fmt(lastTime)).append("）\n");
            }
            sb.append("最活跃时段：").append(peakHours()).append("\n");
            int avg = avgGapSec(gapSum, gapCount);
            if (avg >= 0) sb.append("平均消息间隔：").append(avg).append(" 秒\n");
            int myAvg = avgGapSec(myGapSum, myGapCount);
            int otherAvg = avgGapSec(otherGapSum, otherGapCount);
            if (myAvg >= 0 || otherAvg >= 0) {
                sb.append("回复速度：我平均 ");
                sb.append(myAvg >= 0 ? myAvg + " 秒" : "未知");
                sb.append(" / 对方平均 ");
                sb.append(otherAvg >= 0 ? otherAvg + " 秒" : "未知").append("\n");
            }
            if (!topKeywords.isEmpty()) {
                sb.append("高频话题词：").append(String.join("、", topKeywords)).append("\n");
            }
            return sb.toString();
        }
    }

    private static List<String> extractTopKeywords(List<MessageReader.ChatMsg> msgs, int topN) {
        StringBuilder chinese = new StringBuilder();
        for (MessageReader.ChatMsg m : msgs) {
            if (!m.isText() || m.content == null) continue;
            for (char ch : m.content.toCharArray()) {
                if (ch >= 0x4E00 && ch <= 0x9FA5) chinese.append(ch);
            }
        }
        String text = chinese.toString();
        if (text.length() < 2) return new ArrayList<>();

        Set<Character> stop = new HashSet<>();
        String stopStr = "的了是我你他她它们在有和就不人都很到说要去也这那什么我们你们他们啊哦嗯吧么呢个一好想能会给又还只但很太真没再";
        for (char ch : stopStr.toCharArray()) stop.add(ch);

        Map<String, Integer> freq = new HashMap<>();
        for (int i = 0; i < text.length() - 1; i++) {
            char a = text.charAt(i), b = text.charAt(i + 1);
            if (stop.contains(a) || stop.contains(b)) continue;
            String bigram = String.valueOf(a) + b;
            freq.merge(bigram, 1, Integer::sum);
        }

        List<Map.Entry<String, Integer>> list = new ArrayList<>(freq.entrySet());
        list.sort((x, y) -> y.getValue() - x.getValue());
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(topN, list.size()); i++) {
            Map.Entry<String, Integer> e = list.get(i);
            if (e.getValue() < 2) break;
            out.add(e.getKey() + "(" + e.getValue() + ")");
        }
        return out;
    }

    private static ViewGroup findTitleBar(View v) {
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            String name = v.getClass().getName();
            if (name.contains("ActionBar") || name.contains("MMTitle")
                    || name.contains("Actionbar") || name.contains("TitleBar")) return g;
            for (int i = 0; i < g.getChildCount(); i++) {
                ViewGroup r = findTitleBar(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }
}
