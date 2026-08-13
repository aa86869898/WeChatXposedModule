package com.leshao.v3.ai;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.CandyUi;

public class AIControlFragment extends Fragment {

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        float d = getResources() != null ? getResources().getDisplayMetrics().density : 2.0f;

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int)(8 * d), (int)(16 * d), (int)(8 * d), (int)(16 * d));
        root.setBackground(CandyUi.pageGradient());

        root.addView(sLabel(d, "AI 聊天助手"));

        LinearLayout card = makeCard(d);
        card.addView(switchRow(d, "AI 总开关", "启用全部 AI 功能", AiConfig.masterEnabled(),
                (v, on) -> AiConfig.setMaster(on), null));
        card.addView(switchRow(d, "聊天总结", "总结聊天记录并生成报告", AiConfig.summaryEnabled(),
                (v, on) -> AiConfig.setSummary(on), null));
        card.addView(switchRow(d, "来消息推荐回复", "自动生成回复话术", AiConfig.replyEnabled(),
                (v, on) -> AiConfig.setReply(on), null));
        card.addView(switchRow(d, "多轮上下文记忆", "记住近期聊天上下文", AiConfig.memoryEnabled(),
                (v, on) -> AiConfig.setMemory(on), null));
        card.addView(switchRow(d, "文本润色", "AI 改写输入框内容", AiConfig.polishEnabled(),
                (v, on) -> AiConfig.setPolish(on), null));
        card.addView(switchRow(d, "情绪分析", "分析聊天内容情绪", AiConfig.emotionEnabled(),
                (v, on) -> AiConfig.setEmotion(on), null));
        card.addView(switchRow(d, "关键词/待办提取", "提取关键信息与待办", AiConfig.keywordEnabled(),
                (v, on) -> AiConfig.setKeyword(on), null));
        card.addView(switchRow(d, "自定义 Prompt 模板", "使用自定义提示词", AiConfig.customPromptEnabled(),
                (v, on) -> AiConfig.setCustomPrompt(on), null));
        root.addView(card);

        Button btn = new Button(getContext());
        btn.setText("打开完整配置面板");
        btn.setTextSize(15);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins((int)(8 * d), (int)(20 * d), (int)(8 * d), 0);
        btn.setLayoutParams(blp);
        btn.setOnClickListener(v -> {
            try {
                android.app.Activity act = getActivity();
                if (act != null) AiPanelDialog.show(act);
            } catch (Throwable ignored) {}
        });
        root.addView(btn);

        ScrollView sv = new ScrollView(getContext());
        sv.addView(root);
        return sv;
    }

    private LinearLayout makeCard(float d) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int)(2 * d), (int)(2 * d), (int)(2 * d), (int)(2 * d));
        card.setBackgroundColor(AppColors.bg());
        return card;
    }

    private LinearLayout switchRow(float d, String title, String desc,
                                    boolean checked, CompoundButton.OnCheckedChangeListener l,
                                    View.OnClickListener config) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14 * d), (int)(12 * d), (int)(14 * d), (int)(12 * d));
        row.setBackgroundColor(AppColors.card());

        LinearLayout textCol = new LinearLayout(getContext());
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView tv = new TextView(getContext());
        tv.setText(title); tv.setTextSize(15);
        tv.setTextColor(AppColors.text1()); tv.setTypeface(null, Typeface.BOLD);
        textCol.addView(tv);

        if (desc != null && !desc.isEmpty()) {
            TextView dv = new TextView(getContext());
            dv.setText(desc); dv.setTextSize(12); dv.setTextColor(AppColors.text2());
            dv.setPadding(0, (int)(3 * d), 0, 0);
            textCol.addView(dv);
        }

        row.addView(textCol);

        if (config != null) {
            TextView btn = new TextView(getContext());
            btn.setText("[设置]"); btn.setTextSize(12); btn.setTextColor(AppColors.accent());
            btn.setPadding((int)(6 * d), 0, (int)(6 * d), 0);
            btn.setPaintFlags(btn.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            btn.setOnClickListener(config);
            row.addView(btn);
        }

        Switch sw = new Switch(getContext()); sw.setChecked(checked);
        sw.setOnCheckedChangeListener(l);
        row.addView(sw);
        return row;
    }

    private TextView sLabel(float d, String t) {
        TextView tv = new TextView(getContext());
        tv.setText(t); tv.setTextSize(13); tv.setTextColor(AppColors.text2());
        tv.setPadding(0, 0, 0, (int)(8 * d));
        return tv;
    }
}
