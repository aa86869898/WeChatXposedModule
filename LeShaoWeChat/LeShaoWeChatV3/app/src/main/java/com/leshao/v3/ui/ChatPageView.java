package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.hook.AntiRecallHook;
import com.leshao.v3.hook.VoiceForwardHook;

public class ChatPageView {

    private static final String KEY_RECALL_ENABLED = "ls_recall_enabled";

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.bg());
        root.setPadding((int)(16 * d), (int)(16 * d), (int)(16 * d), (int)(16 * d));

        SharedPreferences prefs = ContextManager.getPrefs();
        boolean recallOn = prefs != null && prefs.getBoolean(KEY_RECALL_ENABLED, false);

        // 功能卡片
        root.addView(sectionLabel(ctx, "核心功能"));

        LinearLayout card1 = makeCard(ctx, d);
        card1.addView(switchRow(ctx, d, "消息防撤回", null, recallOn, (v, on) -> {
            if (prefs != null) {
                prefs.edit().putBoolean(KEY_RECALL_ENABLED, on).apply();
            }
            AntiRecallHook.setEnabled(on);
        }));
        root.addView(card1);

        root.addView(spacerV(ctx, d, 8));
        root.addView(sectionLabel(ctx, "语音转发"));
        boolean vfOn = prefs != null && prefs.getBoolean("ls_voice_forward", false);

        LinearLayout cardVF = makeCard(ctx, d);
        cardVF.addView(switchRow(ctx, d, "语音消息转发", null, vfOn, (v, on) -> {
            if (prefs != null) {
                prefs.edit().putBoolean("ls_voice_forward", on).apply();
            }
            VoiceForwardHook.setEnabled(on);
        }));
        VoiceForwardHook.setEnabled(vfOn);
        root.addView(cardVF);

        return root;
    }

    private static LinearLayout makeCard(Context ctx, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int)(2 * d), (int)(2 * d), (int)(2 * d), (int)(2 * d));
        card.setBackgroundColor(AppColors.card());
        return card;
    }

    private static LinearLayout switchRow(Context ctx, float d, String title, String desc,
                                           boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14 * d), (int)(12 * d), (int)(14 * d), (int)(12 * d));
        row.setBackgroundColor(AppColors.whiteCard());

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(15);
        tv.setTextColor(AppColors.text1());
        tv.setTypeface(null, Typeface.BOLD);
        textCol.addView(tv);

        if (desc != null && !desc.isEmpty()) {
            TextView dv = new TextView(ctx);
            dv.setText(desc);
            dv.setTextSize(12);
            dv.setTextColor(AppColors.text2());
            dv.setPadding(0, (int)(3 * d), 0, 0);
            textCol.addView(dv);
        }

        row.addView(textCol);

        Switch sw = new Switch(ctx);
        sw.setChecked(checked);
        try {
            if (checked) {
                sw.setThumbResource(android.R.drawable.btn_star_big_on);
            }
        } catch (Throwable ignored) {}
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);

        return row;
    }

    private static TextView sectionLabel(Context ctx, String text) {
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(AppColors.text2());
        tv.setPadding(0, 0, 0, (int)(8 * d));
        return tv;
    }

    private static View spacerV(Context ctx, float d, int dp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(dp * d)));
        return v;
    }
}
