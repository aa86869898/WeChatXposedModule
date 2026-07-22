package com.leshao.wechat;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.*;
import java.util.*;

public class FunctionDialogs {

    // ===== 设置弹窗 =====
    public static void showSettings(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Utils.dp(ctx, 16), Utils.dp(ctx, 16), Utils.dp(ctx, 16), Utils.dp(ctx, 16));
        root.setBackground(ThemeEngine.createCardBg(ctx, 16));

        TextView tv = new TextView(ctx);
        tv.setText("乐少助手设置"); tv.setTextSize(17);
        tv.setTextColor(ThemeEngine.thAccent()); tv.setGravity(Gravity.CENTER);
        tv.setTypeface(null, Typeface.BOLD);
        root.addView(tv); root.addView(spacer(ctx, 10));

        addSwitch(ctx, root, "总开关", ModuleSettings.masterSwitch, new SwitchCB() {
            public void onChange(boolean v) { ModuleSettings.masterSwitch = v; ModuleSettings.saveAll(); }
        });
        addSwitch(ctx, root, "显示设置页入口", ModuleSettings.entryCardVisible, new SwitchCB() {
            public void onChange(boolean v) { ModuleSettings.entryCardVisible = v; ModuleSettings.saveAll(); }
        });

        addSection(ctx, root, "TTS引擎");
        addEngineSelector(ctx, root);

        addSection(ctx, root, "播报设置");
        addSwitch(ctx, root, "播报群名称", ModuleSettings.announceGroup, new SwitchCB() {
            public void onChange(boolean v) { ModuleSettings.announceGroup = v; ModuleSettings.saveAll(); }
        });
        addSwitch(ctx, root, "播报来电", ModuleSettings.announceCall, new SwitchCB() {
            public void onChange(boolean v) { ModuleSettings.announceCall = v; ModuleSettings.saveAll(); }
        });
        addSwitch(ctx, root, "按键语音播报", ModuleSettings.keyVoiceAnnounce, new SwitchCB() {
            public void onChange(boolean v) { ModuleSettings.keyVoiceAnnounce = v; ModuleSettings.saveAll(); }
        });

        addSection(ctx, root, "API 密钥");
        addEdit(ctx, root, "配音魔方 Key", ModuleSettings.peiyinApiKey, new EditCB() {
            public void onChange(String s) { ModuleSettings.peiyinApiKey = s; ModuleSettings.saveAll(); }
        });
        addEdit(ctx, root, "DeepSeek Key", ModuleSettings.deepseekApiKey, new EditCB() {
            public void onChange(String s) { ModuleSettings.deepseekApiKey = s; ModuleSettings.saveAll(); }
        });
        addEdit(ctx, root, "火山方舟 Key", ModuleSettings.arkApiKey, new EditCB() {
            public void onChange(String s) { ModuleSettings.arkApiKey = s; ModuleSettings.saveAll(); }
        });

        root.addView(spacer(ctx, 10));

        final AlertDialog dlg = new AlertDialog.Builder(ctx).setView(root).setCancelable(true).create();
        Button btnClose = ThemeEngine.createBtn(ctx, "关闭");
        btnClose.setBackground(ThemeEngine.createOutlineBtnBg(ctx, 8));
        btnClose.setTextColor(ThemeEngine.thText());
        ThemeEngine.addClickAnim(btnClose);
        btnClose.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { safeDismiss(dlg); } });
        root.addView(btnClose);

        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(ThemeEngine.thBg()));
            w.setLayout((int)(ctx.getResources().getDisplayMetrics().widthPixels * 0.9), -2);
            w.setGravity(Gravity.CENTER);
        }
        dlg.show();
    }

    // ===== 状态弹窗 =====
    public static void showStatus(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Utils.dp(ctx, 16), Utils.dp(ctx, 16), Utils.dp(ctx, 16), Utils.dp(ctx, 16));
        root.setBackground(ThemeEngine.createCardBg(ctx, 16));

        TextView tv = new TextView(ctx);
        tv.setText("系统状态"); tv.setTextSize(16);
        tv.setTextColor(ThemeEngine.thAccent()); tv.setGravity(Gravity.CENTER);
        tv.setTypeface(null, Typeface.BOLD);
        root.addView(tv); root.addView(spacer(ctx, 8));

        String[] labels = {
            "总开关: " + (ModuleSettings.masterSwitch ? "开" : "关"),
            "TTS引擎: " + ModuleSettings.ttsEngine,
            "配音魔方: " + (ModuleSettings.peiyinApiKey.isEmpty() ? "未配置" : "已配置"),
            "Wusound: " + (ModuleSettings.wusoundApiKey.isEmpty() ? "未配置" : "已配置"),
            "DeepSeek: " + (ModuleSettings.deepseekApiKey.isEmpty() ? "未配置" : "已配置"),
            "火山方舟: " + (ModuleSettings.arkApiKey.isEmpty() ? "未配置" : "已配置"),
            "叮咚助手: " + (ModuleSettings.dianGeEnabled ? "开" : "关"),
            "防撤回: " + (ModuleSettings.recallLogEnabled ? "开" : "关"),
            "抢红包: " + (ModuleSettings.redPacketGrabEnabled ? "开" : "关"),
            "免打扰: " + (ModuleSettings.quietEnabled ? ModuleSettings.quietStart + "-" + ModuleSettings.quietEnd : "关"),
            "白名单: " + ModuleSettings.WHITE_LIST.size() + " 个",
            "微信版本: " + MainHook.wxVersionName,
            "模块版本: v2.1",
        };
        for (String l : labels) {
            TextView row = new TextView(ctx);
            row.setText(l); row.setTextSize(12); row.setTextColor(ThemeEngine.thText());
            row.setPadding(0, Utils.dp(ctx, 3), 0, Utils.dp(ctx, 3));
            root.addView(row);
        }

        root.addView(spacer(ctx, 10));

        final AlertDialog dlg = new AlertDialog.Builder(ctx).setView(root).setCancelable(true).create();
        Button btnClose = ThemeEngine.createBtn(ctx, "关闭");
        btnClose.setBackground(ThemeEngine.createOutlineBtnBg(ctx, 8));
        btnClose.setTextColor(ThemeEngine.thText());
        ThemeEngine.addClickAnim(btnClose);
        btnClose.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { safeDismiss(dlg); } });
        root.addView(btnClose);

        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(ThemeEngine.thBg()));
            w.setLayout((int)(ctx.getResources().getDisplayMetrics().widthPixels * 0.85), -2);
        }
        dlg.show();
    }

    // ===== 帮助弹窗 =====
    public static void showHelp(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Utils.dp(ctx, 16), Utils.dp(ctx, 16), Utils.dp(ctx, 16), Utils.dp(ctx, 16));
        root.setBackground(ThemeEngine.createCardBg(ctx, 16));

        TextView tv = new TextView(ctx);
        tv.setText("使用帮助"); tv.setTextSize(16);
        tv.setTextColor(ThemeEngine.thAccent()); tv.setGravity(Gravity.CENTER);
        tv.setTypeface(null, Typeface.BOLD);
        root.addView(tv); root.addView(spacer(ctx, 8));

        String[] helps = {
            "播报命令:",
            "  发送「功能设置」→ 打开设置面板",
            "  发送「状态」→ 查看系统状态",
            "  发送「帮助」→ 查看此帮助",
            "",
            "叮咚命令:",
            "  发送「点歌 歌名」→ 搜索歌曲",
            "  发送「歌词 歌名」→ 搜索歌词",
            "  发送「天气 城市」→ 查询天气",
            "  发送「笑话」→ 随机笑话",
            "  发送「金句」→ 名言金句",
            "",
            "AI命令:",
            "  发送「生图 描述」→ AI生成图片",
            "  发送「生视频 描述」→ AI生成视频",
            "  发送「翻译 文本」→ 翻译成中文",
            "  发送「摘要 文本」→ 一句话总结",
            "",
            "其他:",
            "  发送「#tts 文本」→ 朗读文字",
            "  微信设置页顶端入口→打开控制面板",
        };
        for (String h : helps) {
            TextView row = new TextView(ctx);
            row.setText(h); row.setTextSize(11);
            row.setTextColor(h.startsWith("  ") ? ThemeEngine.thText2() : ThemeEngine.thText());
            row.setPadding(0, Utils.dp(ctx, 1), 0, Utils.dp(ctx, 1));
            root.addView(row);
        }

        root.addView(spacer(ctx, 10));

        final AlertDialog dlg = new AlertDialog.Builder(ctx).setView(root).setCancelable(true).create();
        Button btnClose = ThemeEngine.createBtn(ctx, "关闭");
        btnClose.setBackground(ThemeEngine.createOutlineBtnBg(ctx, 8));
        btnClose.setTextColor(ThemeEngine.thText());
        ThemeEngine.addClickAnim(btnClose);
        btnClose.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { safeDismiss(dlg); } });
        root.addView(btnClose);

        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(ThemeEngine.thBg()));
            w.setLayout((int)(ctx.getResources().getDisplayMetrics().widthPixels * 0.9), -2);
        }
        dlg.show();
    }

    // ===== 定时调度弹窗 (NumberPicker) =====
    public static void showSchedule(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Utils.dp(ctx, 16), Utils.dp(ctx, 16), Utils.dp(ctx, 16), Utils.dp(ctx, 16));
        root.setBackground(ThemeEngine.createCardBg(ctx, 16));

        TextView tv = new TextView(ctx);
        tv.setText("定时调度"); tv.setTextSize(16);
        tv.setTextColor(ThemeEngine.thAccent()); tv.setGravity(Gravity.CENTER);
        tv.setTypeface(null, Typeface.BOLD);
        root.addView(tv); root.addView(spacer(ctx, 8));

        TextView tvTime = new TextView(ctx);
        tvTime.setText("运行时间范围:"); tvTime.setTextSize(12); tvTime.setTextColor(ThemeEngine.thText2());
        root.addView(tvTime);

        LinearLayout timeRow = new LinearLayout(ctx);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);

        final NumberPicker npStartH = new NumberPicker(ctx);
        npStartH.setMinValue(0); npStartH.setMaxValue(23); npStartH.setValue(ModuleSettings.scheduleHour);
        npStartH.setFormatter(new NumberPicker.Formatter() { public String format(int v) { return String.format("%02d", v); } });
        timeRow.addView(npStartH);

        TextView colon1 = new TextView(ctx); colon1.setText(":"); colon1.setTextSize(14);
        colon1.setTextColor(ThemeEngine.thText()); colon1.setPadding(Utils.dp(ctx, 1), 0, Utils.dp(ctx, 1), 0);
        timeRow.addView(colon1);

        final NumberPicker npStartM = new NumberPicker(ctx);
        npStartM.setMinValue(0); npStartM.setMaxValue(59); npStartM.setValue(ModuleSettings.scheduleMin);
        npStartM.setFormatter(new NumberPicker.Formatter() { public String format(int v) { return String.format("%02d", v); } });
        timeRow.addView(npStartM);

        TextView dash = new TextView(ctx); dash.setText(" - "); dash.setTextSize(13);
        dash.setTextColor(ThemeEngine.thAccent()); dash.setPadding(Utils.dp(ctx, 2), 0, Utils.dp(ctx, 2), 0);
        timeRow.addView(dash);

        final NumberPicker npEndH = new NumberPicker(ctx);
        npEndH.setMinValue(0); npEndH.setMaxValue(23); npEndH.setValue(ModuleSettings.scheduleEndHour);
        npEndH.setFormatter(new NumberPicker.Formatter() { public String format(int v) { return String.format("%02d", v); } });
        timeRow.addView(npEndH);

        TextView colon2 = new TextView(ctx); colon2.setText(":"); colon2.setTextSize(14);
        colon2.setTextColor(ThemeEngine.thText()); colon2.setPadding(Utils.dp(ctx, 1), 0, Utils.dp(ctx, 1), 0);
        timeRow.addView(colon2);

        final NumberPicker npEndM = new NumberPicker(ctx);
        npEndM.setMinValue(0); npEndM.setMaxValue(59); npEndM.setValue(ModuleSettings.scheduleEndMin);
        npEndM.setFormatter(new NumberPicker.Formatter() { public String format(int v) { return String.format("%02d", v); } });
        timeRow.addView(npEndM);

        root.addView(timeRow);
        root.addView(spacer(ctx, 8));

        addSwitch(ctx, root, "启用定时调度", ModuleSettings.scheduleEnabled, new SwitchCB() {
            public void onChange(boolean v) { ModuleSettings.scheduleEnabled = v; ModuleSettings.saveAll(); }
        });

        root.addView(spacer(ctx, 10));

        final AlertDialog dlg = new AlertDialog.Builder(ctx).setView(root).setCancelable(true).create();

        Button btnSave = ThemeEngine.createBtn(ctx, "保存");
        btnSave.setBackground(ThemeEngine.createPrimaryBtnBg(ctx, 8));
        btnSave.setTextColor(ThemeEngine.thWhite());
        ThemeEngine.addClickAnim(btnSave);
        btnSave.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            ModuleSettings.scheduleHour = npStartH.getValue();
            ModuleSettings.scheduleMin = npStartM.getValue();
            ModuleSettings.scheduleEndHour = npEndH.getValue();
            ModuleSettings.scheduleEndMin = npEndM.getValue();
            ModuleSettings.saveAll();
            Utils.t(ctx, "已保存");
        }});
        root.addView(btnSave);
        root.addView(spacer(ctx, 4));

        Button btnClose = ThemeEngine.createBtn(ctx, "关闭");
        btnClose.setBackground(ThemeEngine.createOutlineBtnBg(ctx, 8));
        btnClose.setTextColor(ThemeEngine.thText());
        ThemeEngine.addClickAnim(btnClose);
        btnClose.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { safeDismiss(dlg); } });
        root.addView(btnClose);

        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(ThemeEngine.thBg()));
            w.setLayout((int)(ctx.getResources().getDisplayMetrics().widthPixels * 0.88), -2);
        }
        dlg.show();
    }

    // ===== 欢迎设置弹窗 (RadioGroup模式) =====
    public static void showWelcome(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Utils.dp(ctx, 16), Utils.dp(ctx, 16), Utils.dp(ctx, 16), Utils.dp(ctx, 16));
        root.setBackground(ThemeEngine.createCardBg(ctx, 16));

        TextView tv = new TextView(ctx);
        tv.setText("入群欢迎设置"); tv.setTextSize(16);
        tv.setTextColor(ThemeEngine.thAccent()); tv.setGravity(Gravity.CENTER);
        tv.setTypeface(null, Typeface.BOLD);
        root.addView(tv); root.addView(spacer(ctx, 8));

        addSwitch(ctx, root, "启用入群欢迎", ModuleSettings.welcomeEnabled, new SwitchCB() {
            public void onChange(boolean v) { ModuleSettings.welcomeEnabled = v; ModuleSettings.saveAll(); }
        });

        TextView tvTypeLabel = new TextView(ctx);
        tvTypeLabel.setText("欢迎类型:"); tvTypeLabel.setTextSize(12); tvTypeLabel.setTextColor(ThemeEngine.thText2());
        tvTypeLabel.setPadding(0, Utils.dp(ctx, 8), 0, Utils.dp(ctx, 4));
        root.addView(tvTypeLabel);

        RadioGroup rg = new RadioGroup(ctx);
        rg.setOrientation(LinearLayout.HORIZONTAL);
        final String[] typeNames = {"文字", "图片", "语音", "视频"};
        for (int i = 0; i < 4; i++) {
            RadioButton rb = new RadioButton(ctx);
            rb.setText(typeNames[i]); rb.setTextSize(10); rb.setTextColor(ThemeEngine.thText());
            rb.setId(i); rb.setPadding(Utils.dp(ctx, 2), 0, Utils.dp(ctx, 8), 0);
            rg.addView(rb);
        }
        rg.check(ModuleSettings.welcomeType);
        rg.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                ModuleSettings.welcomeType = checkedId;
                ModuleSettings.saveAll();
            }
        });
        root.addView(rg);

        TextView tip = new TextView(ctx);
        tip.setText("图片/语音/视频须放于 乐少助手AI存储文件 目录,命名 欢迎语.png/.jpg/.mp3/.silk/.mp4");
        tip.setTextSize(9); tip.setTextColor(ThemeEngine.thText2());
        tip.setPadding(0, Utils.dp(ctx, 4), 0, 0);
        root.addView(tip);

        root.addView(spacer(ctx, 8));

        final EditText etMsg = new EditText(ctx);
        etMsg.setText(ModuleSettings.welcomeMsg);
        ThemeEngine.styleInput(etMsg);
        etMsg.setHint("文字欢迎语内容");
        root.addView(etMsg);

        root.addView(spacer(ctx, 4));

        // 文件选择器 - 选择图片/语音/视频欢迎文件
        Button btnFilePicker = ThemeEngine.createBtn(ctx, "选择文件");
        btnFilePicker.setBackground(ThemeEngine.createOutlineBtnBg(ctx, 8));
        btnFilePicker.setTextColor(ThemeEngine.thAccent());
        ThemeEngine.addClickAnim(btnFilePicker);
        btnFilePicker.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            try {
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                String[] mimeTypes;
                int type = ModuleSettings.welcomeType;
                if (type == 1) mimeTypes = new String[]{"image/*"};
                else if (type == 2) mimeTypes = new String[]{"audio/*"};
                else mimeTypes = new String[]{"video/*"};
                intent.putExtra(android.content.Intent.EXTRA_MIME_TYPES, mimeTypes);
                intent.setFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                ctx.startActivity(intent);
                Utils.t(ctx, "请在文件管理器中选择欢迎文件");
            } catch (Throwable e) {
                Utils.t(ctx, "请手动将 " + (ModuleSettings.welcomeType == 1 ? "图片" : ModuleSettings.welcomeType == 2 ? "语音" : "视频") + " 放入 乐少助手AI存储文件 目录, 命名为 欢迎语");
            }
        }});
        root.addView(btnFilePicker);

        root.addView(spacer(ctx, 10));

        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);

        final AlertDialog dlg = new AlertDialog.Builder(ctx).setView(root).setCancelable(true).create();

        Button btnSave = ThemeEngine.createBtn(ctx, "保存");
        btnSave.setBackground(ThemeEngine.createPrimaryBtnBg(ctx, 8));
        btnSave.setTextColor(ThemeEngine.thWhite());
        ThemeEngine.addClickAnim(btnSave);
        btnSave.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            ModuleSettings.welcomeMsg = etMsg.getText().toString();
            ModuleSettings.saveAll();
            Utils.t(ctx, "欢迎设置已保存");
        }});

        Button btnDisable = ThemeEngine.createBtn(ctx, "禁用");
        btnDisable.setBackground(ThemeEngine.createOutlineBtnBg(ctx, 8));
        btnDisable.setTextColor(ThemeEngine.thRed());
        ThemeEngine.addClickAnim(btnDisable);
        btnDisable.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            ModuleSettings.welcomeEnabled = false;
            ModuleSettings.saveAll();
            Utils.t(ctx, "入群欢迎已禁用");
            safeDismiss(dlg);
        }});

        Button btnCancel = ThemeEngine.createBtn(ctx, "取消");
        btnCancel.setBackground(ThemeEngine.createOutlineBtnBg(ctx, 8));
        btnCancel.setTextColor(ThemeEngine.thText());
        ThemeEngine.addClickAnim(btnCancel);
        btnCancel.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { safeDismiss(dlg); } });

        btnRow.addView(btnSave);
        btnRow.addView(spacer(ctx, 6));
        btnRow.addView(btnDisable);
        btnRow.addView(spacer(ctx, 6));
        btnRow.addView(btnCancel);
        root.addView(btnRow);

        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(ThemeEngine.thBg()));
            w.setLayout((int)(ctx.getResources().getDisplayMetrics().widthPixels * 0.88), -2);
        }
        dlg.show();
    }

    // ===== 黑名单弹窗 =====
    public static void showBlacklist(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Utils.dp(ctx, 16), Utils.dp(ctx, 16), Utils.dp(ctx, 16), Utils.dp(ctx, 16));
        root.setBackground(ThemeEngine.createCardBg(ctx, 16));

        TextView tv = new TextView(ctx);
        tv.setText("全局黑名单"); tv.setTextSize(17);
        tv.setTextColor(ThemeEngine.thAccent()); tv.setGravity(Gravity.CENTER);
        tv.setTypeface(null, Typeface.BOLD);
        root.addView(tv); root.addView(spacer(ctx, 8));

        addSwitch(ctx, root, "启用黑名单(进群自动移出)", ModuleSettings.blacklistEnabled, new SwitchCB() {
            public void onChange(boolean v) { ModuleSettings.blacklistEnabled = v; ModuleSettings.saveAll(); }
        });

        root.addView(spacer(ctx, 4));

        if (ModuleSettings.blacklistMap.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("(黑名单为空)"); empty.setTextSize(11);
            empty.setTextColor(ThemeEngine.thText2()); empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Utils.dp(ctx, 12), 0, Utils.dp(ctx, 12));
            root.addView(empty);
        } else {
            for (String wxid : ModuleSettings.blacklistMap.keySet()) {
                org.json.JSONObject o = ModuleSettings.blacklistMap.get(wxid);
                String name = o != null ? o.optString("name", wxid) : wxid;
                String reason = o != null ? o.optString("reason", "") : "";
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, Utils.dp(ctx, 2), 0, Utils.dp(ctx, 2));
                TextView t = new TextView(ctx);
                t.setText(name + (reason.isEmpty() ? "" : " (" + reason + ")")); t.setTextSize(10); t.setTextColor(ThemeEngine.thText());
                t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(t);
                Button del = ThemeEngine.createBtn(ctx, "移除");
                del.setTextSize(9);
                del.setTextColor(ThemeEngine.thWhite());
                del.setBackground(ThemeEngine.createDangerBtnBg(ctx, 3));
                del.setPadding(Utils.dp(ctx, 6), Utils.dp(ctx, 1), Utils.dp(ctx, 6), Utils.dp(ctx, 1));
                ThemeEngine.addClickAnim(del);
                final String fwxid = wxid;
                del.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                    ModuleSettings.blacklistMap.remove(fwxid); ModuleSettings.saveAll(); Utils.t(ctx, "已从黑名单移除");
                }});
                row.addView(del);
                root.addView(row);
            }
        }

        root.addView(spacer(ctx, 10));

        final AlertDialog dlg = new AlertDialog.Builder(ctx).setView(root).setCancelable(true).create();
        Button btnClose = ThemeEngine.createBtn(ctx, "关闭");
        btnClose.setBackground(ThemeEngine.createOutlineBtnBg(ctx, 8));
        btnClose.setTextColor(ThemeEngine.thText());
        ThemeEngine.addClickAnim(btnClose);
        btnClose.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { safeDismiss(dlg); } });
        root.addView(btnClose);

        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(ThemeEngine.thBg()));
            w.setLayout((int)(ctx.getResources().getDisplayMetrics().widthPixels * 0.88), -2);
        }
        dlg.show();
    }

    // ===== UI 工具 =====
    public interface SwitchCB { void onChange(boolean v); }
    public interface EditCB { void onChange(String s); }

    private static void addSection(Context ctx, LinearLayout root, String title) {
        LinearLayout hdr = new LinearLayout(ctx);
        hdr.setPadding(Utils.dp(ctx, 12), Utils.dp(ctx, 6), Utils.dp(ctx, 12), Utils.dp(ctx, 6));
        GradientDrawable hbg = new GradientDrawable(); hbg.setCornerRadius(Utils.dp(ctx, 8)); hbg.setColor(ThemeEngine.thCard());
        hdr.setBackground(hbg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Utils.dp(ctx, 8); hdr.setLayoutParams(lp);
        TextView tv = new TextView(ctx); tv.setText(title); tv.setTextSize(13);
        tv.setTextColor(ThemeEngine.thAccent()); tv.setTypeface(null, Typeface.BOLD);
        hdr.addView(tv); root.addView(hdr);
    }

    private static void addSwitch(Context ctx, LinearLayout panel, String label, boolean checked, final SwitchCB cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Utils.dp(ctx, 3), 0, Utils.dp(ctx, 3));
        TextView tv = new TextView(ctx); tv.setText(label); tv.setTextSize(12); tv.setTextColor(ThemeEngine.thText());
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1)); row.addView(tv);
        Switch sw = new Switch(ctx); sw.setChecked(checked);
        ThemeEngine.styleSwitch(sw, checked, ctx);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton btn, boolean isChecked) {
                ThemeEngine.styleSwitch(sw, isChecked, ctx); if (cb != null) cb.onChange(isChecked);
            }
        });
        row.addView(sw); panel.addView(row);
    }

    private static void addEdit(Context ctx, LinearLayout panel, String label, String value, final EditCB cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Utils.dp(ctx, 2), 0, Utils.dp(ctx, 2));
        TextView tv = new TextView(ctx); tv.setText(label + ": "); tv.setTextSize(10); tv.setTextColor(ThemeEngine.thText2());
        row.addView(tv);
        final EditText et = new EditText(ctx);
        et.setText(value != null ? value : "");
        ThemeEngine.styleInput(et);
        et.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        et.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) { if (!hasFocus && cb != null) cb.onChange(et.getText().toString()); }
        });
        row.addView(et); panel.addView(row);
    }

    private static void addEngineSelector(Context ctx, LinearLayout panel) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Utils.dp(ctx, 3), 0, Utils.dp(ctx, 3));
        TextView tv = new TextView(ctx); tv.setText("引擎: "); tv.setTextSize(12); tv.setTextColor(ThemeEngine.thText()); row.addView(tv);
        final String[] vals = {"system", "peiyin", "wusound"};
        final String[] names = {"系统", "魔方", "悟声"};
        for (int i = 0; i < 3; i++) {
            final String val = vals[i];
            Button btn = ThemeEngine.createBtn(ctx, names[i]);
            btn.setTextSize(10);
            btn.setTextColor(ModuleSettings.ttsEngine.equals(val) ? ThemeEngine.thWhite() : ThemeEngine.thText());
            btn.setBackground(ModuleSettings.ttsEngine.equals(val) ? ThemeEngine.createPrimaryBtnBg(ctx, 4) : ThemeEngine.createOutlineBtnBg(ctx, 4));
            btn.setPadding(Utils.dp(ctx, 6), Utils.dp(ctx, 2), Utils.dp(ctx, 6), Utils.dp(ctx, 2));
            ThemeEngine.addClickAnim(btn);
            btn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { ModuleSettings.ttsEngine = val; ModuleSettings.saveAll(); } });
            row.addView(btn);
        }
        panel.addView(row);
    }

    private static View spacer(Context ctx, int h) {
        View v = new View(ctx); v.setLayoutParams(new LinearLayout.LayoutParams(-1, Utils.dp(ctx, h))); return v;
    }

    private static void safeDismiss(AlertDialog d) {
        try { if (d != null && d.isShowing()) d.dismiss(); } catch (Exception e) {}
    }
}
