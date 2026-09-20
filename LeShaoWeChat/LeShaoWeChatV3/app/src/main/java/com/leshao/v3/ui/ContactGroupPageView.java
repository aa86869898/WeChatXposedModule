package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.*;
import com.leshao.v3.model.ModuleConfig;

import android.widget.Toast;

public class ContactGroupPageView {

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;
        final SharedPreferences prefs = ContextManager.getPrefs();
        final ModuleConfig cfg = ModuleConfig.load(prefs);
        final Activity act = parentAct;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.bg());
        root.setPadding((int)(16 * d), (int)(16 * d), (int)(16 * d), (int)(16 * d));

        LinearLayout cardContact = makeCard(ctx, d);
        cardContact.addView(switchRow(ctx, d, "联系人变更日志", null, cfg.contactChangeLogEnabled, (v, on) -> {
            cfg.contactChangeLogEnabled = on; cfg.save(prefs); ContactChangeLog.setEnabled(on);
        }, v -> SubPageActivity.open(act, "通讯录更新日志", 13)));
        root.addView(cardContact);

        root.addView(candyDivider(ctx, d));

        LinearLayout cardChat = makeCard(ctx, d);
        boolean recallOn = prefs != null && prefs.getBoolean("ls_recall_enabled", false);
        boolean vfOn = prefs != null && prefs.getBoolean("ls_voice_forward", false);

        cardChat.addView(switchRow(ctx, d, "消息防撤回", null, recallOn, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean("ls_recall_enabled", on).apply();
            AntiRecallHook.setEnabled(on);
        }, null));
        cardChat.addView(switchRow(ctx, d, "语音消息转发", null, vfOn, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean("ls_voice_forward", on).apply();
            VoiceForwardHook.setEnabled(on);
        }, null));
        root.addView(cardChat);

        root.addView(candyDivider(ctx, d));

        // 自动转发卡片
        boolean afOn = prefs != null && prefs.getBoolean("ls_autofw_enabled", false);
        LinearLayout cardAutoFw = makeCard(ctx, d);
        cardAutoFw.addView(switchRow(ctx, d, "自动转发", "来源消息自动转发给目标联系人/群聊", afOn, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean("ls_autofw_enabled", on).apply();
            com.leshao.v3.hook.AutoForwardHook.setEnabled(on);
            if (on) com.leshao.v3.hook.AutoForwardHook.updateConfig(prefs);
            Toast.makeText(ctx, "自动转发已" + (on ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
        }, v -> com.leshao.v3.hook.AutoForwardHook.showConfigDialog(act)));
        root.addView(cardAutoFw);

        root.addView(candyDivider(ctx, d));

        // 聊天分组卡片
        boolean chatGroupOn = prefs != null && prefs.getBoolean("ls_chat_group_enabled", true);
        LinearLayout cardGroup = makeCard(ctx, d);
        cardGroup.addView(switchRow(ctx, d, "聊天分组", null, chatGroupOn, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean("ls_chat_group_enabled", on).apply();
        }, v -> SubPageActivity.open(act, "聊天分组管理", 14)));
        root.addView(cardGroup);

        root.addView(candyDivider(ctx, d));

        // 群聊批量加好友卡片（模块内仅总开关，其余配置在群聊详情页按钮弹窗内）
        LinearLayout cardBatch = makeCard(ctx, d);
        boolean batchOn = prefs != null && prefs.getBoolean("ls_batch_add_enabled", false);
        cardBatch.addView(switchRow(ctx, d, "群聊批量加好友", "群聊详情页右上角[批量加友]菜单内配置", batchOn, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean("ls_batch_add_enabled", on).apply();
            BatchAddFriend.setEnabled(on);
            Toast.makeText(ctx, "批量加好友已" + (on ? "开启" : "关闭")
                    + "\n重启微信后生效", Toast.LENGTH_LONG).show();
        }, null));
        root.addView(cardBatch);

        root.addView(candyDivider(ctx, d));

        LinearLayout cardFakeSource = makeCard(ctx, d);
        boolean fakeSourceOn = prefs != null && prefs.getBoolean("ls_fake_add_source_enabled", false);
        int fakeScene = prefs != null ? prefs.getInt("ls_fake_add_source_scene", 10) : 10;

        // 手动构建伪装来源行（需持有subtitle引用以便选中后即时更新）
        LinearLayout rowFake = new LinearLayout(ctx);
        rowFake.setOrientation(LinearLayout.HORIZONTAL);
        rowFake.setGravity(Gravity.CENTER_VERTICAL);
        rowFake.setPadding((int)(14 * d), (int)(12 * d), (int)(14 * d), (int)(12 * d));
        rowFake.setBackgroundColor(AppColors.whiteCard());

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView tvTitle = new TextView(ctx);
        tvTitle.setText("添加好友伪装来源"); tvTitle.setTextSize(15);
        tvTitle.setTextColor(AppColors.text1()); tvTitle.setTypeface(null, Typeface.BOLD);
        textCol.addView(tvTitle);
        final TextView tvSub = new TextView(ctx);
        String initLabel = com.leshao.v3.hook.FakeAddSource.sceneName(fakeScene);
        tvSub.setText("伪装" + initLabel + "添加");
        tvSub.setTextSize(12); tvSub.setTextColor(AppColors.text2());
        tvSub.setPadding(0, (int)(3 * d), 0, 0);
        textCol.addView(tvSub);
        rowFake.addView(textCol);

        // [选择] 按钮
        TextView btn = new TextView(ctx);
        btn.setText("[选择]"); btn.setTextSize(12); btn.setTextColor(AppColors.accent());
        btn.setPadding((int)(6 * d), 0, (int)(6 * d), 0);
        btn.setPaintFlags(btn.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        btn.setOnClickListener(v -> {
            LogWriter.log("ContactGroupView", "伪装来源: 打开类型选择弹窗");
            int dlgTheme = AppColors.isDarkMode()
                    ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                    : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
            java.util.Map<Integer, String> sceneMap = com.leshao.v3.hook.FakeAddSource.SCENE_MAP;
            final Integer[] sceneKeys = sceneMap.keySet().toArray(new Integer[0]);
            final String[] sceneLabels = sceneMap.values().toArray(new String[0]);
            for (int i = 0; i < sceneLabels.length; i++) {
                sceneLabels[i] = "伪装" + sceneLabels[i] + "添加";
            }

            AlertDialog dialog = new AlertDialog.Builder(ctx, dlgTheme).create();
            LinearLayout dlgRoot = new LinearLayout(ctx);
            dlgRoot.setOrientation(LinearLayout.VERTICAL);
            dlgRoot.setPadding((int)(14*d), (int)(14*d), (int)(14*d), (int)(8*d));
            dlgRoot.setBackgroundColor(AppColors.card());

            TextView dlgTitle = new TextView(ctx);
            dlgTitle.setText("选择伪装来源");
            dlgTitle.setTextSize(16);
            dlgTitle.setTextColor(AppColors.text1());
            dlgTitle.setTypeface(null, Typeface.BOLD);
            dlgTitle.setPadding(0, 0, 0, (int)(12*d));
            dlgRoot.addView(dlgTitle);

            ScrollView sv = new ScrollView(ctx);
            LinearLayout listRoot = new LinearLayout(ctx);
            listRoot.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < sceneLabels.length; i++) {
                final int idx = i;
                TextView tv = new TextView(ctx);
                tv.setText(sceneLabels[i]);
                tv.setTextSize(14);
                tv.setTextColor(AppColors.text1());
                tv.setPadding((int)(8*d), (int)(10*d), (int)(8*d), (int)(10*d));
                tv.setOnClickListener(v2 -> {
                    int selected = sceneKeys[idx];
                    String selName = com.leshao.v3.hook.FakeAddSource.sceneName(selected);
                    if (prefs != null) prefs.edit().putInt("ls_fake_add_source_scene", selected).commit();
                    LogWriter.log("ContactGroupView", "伪装来源: 选择类型 scene=" + selected
                            + " (" + selName + ")");
                    tvSub.setText("伪装" + selName + "添加");
                    Toast.makeText(ctx, "伪装来源设为: 伪装" + selName + "添加", Toast.LENGTH_LONG).show();
                    dialog.dismiss();
                });
                listRoot.addView(tv);
            }
            sv.addView(listRoot);
            dlgRoot.addView(sv);

            TextView cancel = new TextView(ctx);
            cancel.setText("取消");
            cancel.setTextSize(14);
            cancel.setTextColor(AppColors.text2());
            cancel.setGravity(Gravity.CENTER);
            cancel.setPadding(0, (int)(12*d), 0, 0);
            cancel.setOnClickListener(v2 -> dialog.dismiss());
            dlgRoot.addView(cancel);

            dialog.setView(dlgRoot);
            dialog.show();
        });
        rowFake.addView(btn);

        Switch swFake = CandyUi.newSwitch(ctx); swFake.setChecked(fakeSourceOn);
        try { swFake.setThumbResource(android.R.drawable.btn_star_big_on); } catch (Throwable ignored) {}
        swFake.setOnCheckedChangeListener((v, on) -> {
            if (prefs != null) prefs.edit().putBoolean("ls_fake_add_source_enabled", on).apply();
            LogWriter.log("ContactGroupView", "伪装来源开关: " + on);
            Toast.makeText(ctx, "伪装来源已" + (on ? "开启" : "关闭")
                    + "\n重启微信后生效", Toast.LENGTH_LONG).show();
        });
        rowFake.addView(swFake);

        cardFakeSource.addView(rowFake);
        root.addView(cardFakeSource);

        root.addView(candyDivider(ctx, d));

        LinearLayout cardEntry = makeCard(ctx, d);
        boolean cornerMenuOn = com.leshao.v3.wm.utils.WmPrefs.isCornerMenu();
        boolean longPressMenuOn = com.leshao.v3.wm.utils.WmPrefs.isLongPressMenu();
        boolean inputButtonsOn = com.leshao.v3.wm.utils.WmPrefs.isInputButtons();

        cardEntry.addView(switchRow(ctx, d, "微信左上角菜单", null, cornerMenuOn, (v, on) -> {
            com.leshao.v3.wm.utils.WmPrefs.set("corner_menu", on);
        }, null));
        cardEntry.addView(switchRow(ctx, d, "聊天窗口长按菜单", null, longPressMenuOn, (v, on) -> {
            com.leshao.v3.wm.utils.WmPrefs.set("long_press_menu", on);
        }, null));
        cardEntry.addView(switchRow(ctx, d, "输入框功能按钮", null, inputButtonsOn, (v, on) -> {
            com.leshao.v3.wm.utils.WmPrefs.set("input_buttons", on);
        }, null));
        root.addView(cardEntry);

        return root;
    }

    private static LinearLayout subSwitch(Context ctx, SharedPreferences prefs, float d,
                                           String key, String title, String desc, boolean defVal) {
        return subSwitch(ctx, prefs, d, key, title, desc, defVal, null);
    }

    private static LinearLayout subSwitch(Context ctx, SharedPreferences prefs, float d,
                                           String key, String title, String desc, boolean defVal,
                                           View.OnClickListener config) {
        boolean checked = prefs != null ? prefs.getBoolean(key, defVal) : defVal;
        return switchRow(ctx, d, title, desc, checked, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(key, on).apply();
        }, config);
    }

    private static LinearLayout makeCard(Context ctx, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int)(2 * d), (int)(2 * d), (int)(2 * d), (int)(2 * d));
        card.setBackgroundColor(AppColors.card());
        return card;
    }

    private static LinearLayout switchRow(Context ctx, float d, String title, String desc,
                                           boolean checked, CompoundButton.OnCheckedChangeListener listener,
                                           View.OnClickListener configListener) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14 * d), (int)(12 * d), (int)(14 * d), (int)(12 * d));
        row.setBackgroundColor(AppColors.whiteCard());

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView tv = new TextView(ctx);
        tv.setText(title); tv.setTextSize(15);
        tv.setTextColor(AppColors.text1()); tv.setTypeface(null, Typeface.BOLD);
        textCol.addView(tv);

        if (desc != null && !desc.isEmpty()) {
            TextView dv = new TextView(ctx);
            dv.setText(desc); dv.setTextSize(12);
            dv.setTextColor(AppColors.text2());
            dv.setPadding(0, (int)(3 * d), 0, 0);
            textCol.addView(dv);
        }
        row.addView(textCol);

        if (configListener != null) {
            LogWriter.log("ContactGroupPageView", "switchRow [" + title + "] 显示[设置]按钮");
            TextView btn = new TextView(ctx);
            btn.setText("[设置]"); btn.setTextSize(12); btn.setTextColor(AppColors.accent());
            btn.setPadding((int)(6 * d), 0, (int)(6 * d), 0);
            btn.setPaintFlags(btn.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            btn.setOnClickListener(configListener);
            row.addView(btn);
        }

            Switch sw = CandyUi.newSwitch(ctx); sw.setChecked(checked);
        try { sw.setThumbResource(android.R.drawable.btn_star_big_on); } catch (Throwable ignored) {}
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);
        return row;
    }

    private static TextView sectionLabel(Context ctx, String text) {
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(ctx);
        tv.setText(text); tv.setTextSize(13);
        tv.setTextColor(AppColors.text2());
        tv.setPadding(0, 0, 0, (int)(8 * d));
        return tv;
    }

    private static View candyDivider(Context ctx, float d) {
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{AppColors.candyPink(), AppColors.candyYellow(), AppColors.accent(), AppColors.candyPink()});
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (int)(1.5f * d));
        lp.setMargins((int)(12 * d), (int)(6 * d), (int)(12 * d), (int)(6 * d));
        v.setLayoutParams(lp);
        v.setBackground(gd);
        return v;
    }

    private static View spacerV(Context ctx, float d, int dp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(dp * d)));
        return v;
    }
}
