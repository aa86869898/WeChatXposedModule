package com.leshao.v3.ai;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

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
            List<MessageReader.ChatMsg> msgs = reader.readRecent(talker, AiConfig.summaryCount());
            ChatHooks.MAIN.post(() -> {
                if (msgs.isEmpty()) {
                    Toast.makeText(act, "暂无文本消息可总结", Toast.LENGTH_SHORT).show();
                    return;
                }
                String dialogText = MessageReader.toDialogText(msgs);
                final SummaryStats stats = SummaryStats.build(msgs);

                List<AiClient.ChatMessage> req = new ArrayList<>();
                req.add(new AiClient.ChatMessage("user", dialogText));
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

        public static SummaryStats build(List<MessageReader.ChatMsg> msgs) {
            SummaryStats s = new SummaryStats();
            s.total = msgs.size();
            Calendar c = Calendar.getInstance();
            for (MessageReader.ChatMsg m : msgs) {
                if (m.role.equals("me")) s.meCount++; else s.otherCount++;
                c.setTimeInMillis(m.time);
                s.hourDist[c.get(Calendar.HOUR_OF_DAY)]++;
            }
            return s;
        }
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
