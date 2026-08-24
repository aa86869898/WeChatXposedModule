package com.leshao.wechat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.*;
import java.util.Set;
import java.util.HashSet;

public class ModuleUI {
    public interface SwitchCB { void onChange(boolean v); }
    public interface EditCB { void onChange(String s); }
    private static int currentTab = 0;
    private static LinearLayout tabContent;
    private static TextView[] tabViews = new TextView[4];

    public static void showMainPanel(Activity act) {
        if (act == null || act.isFinishing()) { Utils.xlog("showMainPanel 跳过: act=" + (act == null ? "null" : "finishing")); return; }

        if (cd != null && cd.isShowing()) {
            Utils.xlog("showMainPanel 复用现有Dialog, 切换Tab=" + currentTab);
            try {
                Context ctx = cd.getContext();
                for (int i = 0; i < 4; i++) {
                    if (tabViews[i] != null) {
                        tabViews[i].setTextColor(i == currentTab ? ThemeEngine.thWhite() : ThemeEngine.thText());
                        if (ctx != null) {
                            tabViews[i].setBackground(i == currentTab ? ThemeEngine.createPrimaryBtnBg(ctx, 8) : ThemeEngine.createOutlineBtnBg(ctx, 8));
                        }
                    }
                }
                if (tabContent != null) {
                    tabContent.removeAllViews();
                    if (ctx != null) {
                        if (currentTab == 0) buildTab1(ctx, tabContent);
                        else if (currentTab == 1) buildTab2(ctx, tabContent);
                        else if (currentTab == 2) buildTab3(ctx, tabContent);
                        else buildTab4(ctx, tabContent);
                    }
                }
            } catch (Throwable e) {
                Utils.xlog("showMainPanel 切换异常: " + e.getMessage());
            }
            return;
        }

        Utils.xlog("showMainPanel 开始构建...");
        try {
        final Context ctx = act;
        ScrollView sv = new ScrollView(ctx); sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Utils.dp(ctx, 16); root.setPadding(p, p, p, p);
        root.setBackground(ThemeEngine.createGlassBg(ctx, 14));

        TextView title = new TextView(ctx);
        title.setText("\uD83C\uDF80 乐少助手 v2.1");
        title.setTextSize(20); title.setTextColor(ThemeEngine.thAccent());
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, Utils.dp(ctx, 8), 0, Utils.dp(ctx, 4));
        root.addView(title);

        TextView ver = new TextView(ctx);
        ver.setText("微信多功能增强模块"); ver.setTextSize(11);
        ver.setTextColor(ThemeEngine.thText2()); ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, 0, 0, Utils.dp(ctx, 10)); root.addView(ver);

        LinearLayout tabBar = new LinearLayout(ctx);
        tabBar.setOrientation(LinearLayout.HORIZONTAL); tabBar.setGravity(Gravity.CENTER);
        String[] tabs = {"语音播报", "实用工具", "多媒体", "其它"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView tv = new TextView(ctx);
            tv.setText(tabs[i]); tv.setTextSize(11);
            tv.setTextColor(idx == currentTab ? ThemeEngine.thWhite() : ThemeEngine.thText());
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(Utils.dp(ctx, 14), Utils.dp(ctx, 8), Utils.dp(ctx, 14), Utils.dp(ctx, 8));
            tv.setBackground(idx == currentTab ? ThemeEngine.createPrimaryBtnBg(ctx, 8) : ThemeEngine.createOutlineBtnBg(ctx, 8));
            ThemeEngine.styleClickableText(tv);
            tv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { currentTab = idx; showMainPanel(act); }
            });
            tabBar.addView(tv);
            if (i < 3) { View sp = new View(ctx); sp.setLayoutParams(new LinearLayout.LayoutParams(Utils.dp(ctx, 4), 0)); tabBar.addView(sp); }
            tabViews[idx] = tv;
        }
        root.addView(tabBar);
        root.addView(ThemeEngine.spacer(ctx, 10));

        tabContent = new LinearLayout(ctx);
        tabContent.setOrientation(LinearLayout.VERTICAL);
        if (currentTab == 0) buildTab1(ctx, tabContent);
        else if (currentTab == 1) buildTab2(ctx, tabContent);
        else if (currentTab == 2) buildTab3(ctx, tabContent);
        else buildTab4(ctx, tabContent);
        root.addView(tabContent);

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL); btns.setGravity(Gravity.CENTER);
        btns.setPadding(0, Utils.dp(ctx, 12), 0, Utils.dp(ctx, 4));
        Button btnSave = ThemeEngine.createBtn(ctx, "保存配置");
        ThemeEngine.styleButton(btnSave);
        btnSave.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { ModuleSettings.saveAll(); Utils.t(ctx, "已保存"); } });
        btns.addView(btnSave);
        View s1 = new View(ctx); s1.setLayoutParams(new LinearLayout.LayoutParams(Utils.dp(ctx, 8), 0)); btns.addView(s1);
        Button btnStop = ThemeEngine.createBtn(ctx, "停止");
        ThemeEngine.styleButton(btnStop);
        btnStop.setBackground(ThemeEngine.createDangerBtnBg(ctx, 8));
        btnStop.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { TTSManager.stopAll(); Utils.t(ctx, "已停止"); } });
        btns.addView(btnStop);
        View s2 = new View(ctx); s2.setLayoutParams(new LinearLayout.LayoutParams(Utils.dp(ctx, 8), 0)); btns.addView(s2);
        Button btnSys = ThemeEngine.createBtn(ctx, "设置");
        ThemeEngine.styleButton(btnSys);
        btnSys.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { FunctionDialogs.showSettings(ctx); } });
        btns.addView(btnSys);
        View s3 = new View(ctx); s3.setLayoutParams(new LinearLayout.LayoutParams(Utils.dp(ctx, 8), 0)); btns.addView(s3);
        Button btnClose = ThemeEngine.createBtn(ctx, "关闭");
        ThemeEngine.styleButton(btnClose);
        btnClose.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { safeDismiss(cd); } });
        btns.addView(btnClose);
        root.addView(btns);

        sv.addView(root);
        AlertDialog.Builder b = new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
        b.setView(sv); b.setCancelable(true);
        final AlertDialog dlg = b.create();
        cd = dlg;
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int)(ctx.getResources().getDisplayMetrics().widthPixels * 0.94),
                        (int)(ctx.getResources().getDisplayMetrics().heightPixels * 0.88));
            w.setGravity(Gravity.CENTER);
        }
        dlg.show();
        Utils.xlog("showMainPanel 弹窗完成");
        } catch (Throwable e) {
            Utils.xlog("showMainPanel 异常: " + e.getClass().getName() + ": " + e.getMessage());
            if (e.getStackTrace().length > 0) Utils.xlog("  at " + e.getStackTrace()[0].toString());
            try { android.widget.Toast.makeText(act, "面板异常: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show(); }
            catch (Throwable e2) {}
        }
    }

    private static void buildTab1(Context ctx, LinearLayout r) {
        addSection(ctx, r, "\uD83D\uDCE2 消息播报");
        sw(ctx, r, "播报群名称", ModuleSettings.announceGroup, new SwitchCB(){public void onChange(boolean v){ModuleSettings.announceGroup=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "来电播报", ModuleSettings.announceCall, new SwitchCB(){public void onChange(boolean v){ModuleSettings.announceCall=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "位置播报", ModuleSettings.announceLocation, new SwitchCB(){public void onChange(boolean v){ModuleSettings.announceLocation=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "TTS命令模式", ModuleSettings.masterSwitch, new SwitchCB(){public void onChange(boolean v){ModuleSettings.masterSwitch=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "文本熔断(150字)", ModuleSettings.textTruncateEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.textTruncateEnabled=v;ModuleSettings.saveAll();}});
        addSection(ctx, r, "\uD83D\uDD0A 引擎");
        ed(ctx, r, "魔方Key", ModuleSettings.peiyinApiKey, new EditCB(){public void onChange(String s){ModuleSettings.peiyinApiKey=s;ModuleSettings.saveAll();}});
        ed(ctx, r, "魔方语音ID", ModuleSettings.peiyinVoiceId, new EditCB(){public void onChange(String s){ModuleSettings.peiyinVoiceId=s;ModuleSettings.saveAll();}});
        ed(ctx, r, "Wusound Key", ModuleSettings.wusoundApiKey, new EditCB(){public void onChange(String s){ModuleSettings.wusoundApiKey=s;ModuleSettings.saveAll();}});
        ed(ctx, r, "Wusound语音ID", ModuleSettings.wusoundVoiceId, new EditCB(){public void onChange(String s){ModuleSettings.wusoundVoiceId=s;ModuleSettings.saveAll();}});
        ed(ctx, r, "播报间隔(ms)", String.valueOf(ModuleSettings.announceIntervalMs), new EditCB(){public void onChange(String s){try{ModuleSettings.announceIntervalMs=Long.parseLong(s);}catch(Exception e){}ModuleSettings.saveAll();}});
        addSection(ctx, r, "\uD83D\uDC65 范围");
        sw(ctx, r, "按键语音播报", ModuleSettings.keyVoiceAnnounce, new SwitchCB(){public void onChange(boolean v){ModuleSettings.keyVoiceAnnounce=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "语音自动播放", ModuleSettings.autoPlayVoiceEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.autoPlayVoiceEnabled=v;ModuleSettings.saveAll();}});
        Button btnScope = ThemeEngine.createBtn(ctx, "选择播报范围");
        ThemeEngine.styleButton(btnScope);
        btnScope.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
            ContactSelector.show(ctx, "播报范围", ModuleSettings.WHITE_LIST, new ContactSelector.OnScopeSaved(){public void onSaved(Set<String> ids){ModuleSettings.WHITE_LIST.clear();ModuleSettings.WHITE_LIST.addAll(ids);ModuleSettings.saveAll();}});
        }});
        r.addView(btnScope);
    }

    private static void buildTab2(Context ctx, LinearLayout r) {
        addSection(ctx, r, "\uD83D\uDCF1 实用工具");
        btn(ctx, r, "抖音分享", "配置", new View.OnClickListener(){public void onClick(View v){showDouyin(ctx);}});
        btn(ctx, r, "定时群发", "配置", new View.OnClickListener(){public void onClick(View v){showMass(ctx);}});
        btn(ctx, r, "黄狗音乐", "打开", new View.OnClickListener(){public void onClick(View v){mk(ctx,"黄狗音乐","输入歌名搜索","huanggou");}});
        btn(ctx, r, "抖音音乐", "打开", new View.OnClickListener(){public void onClick(View v){mk(ctx,"抖音音乐搜索","输入歌名搜索","douyin");}});
        btn(ctx, r, "蓝狗曲库", "打开", new View.OnClickListener(){public void onClick(View v){mk(ctx,"蓝狗会员曲库","输入歌名或链接","langou");}});
        sw(ctx, r, "24小时在线点歌", ModuleSettings.dianGeEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.dianGeEnabled=v;ModuleSettings.saveAll();}});
        Button btnDdScope = ThemeEngine.createBtn(ctx, "点歌生效范围");
        ThemeEngine.styleButton(btnDdScope);
        btnDdScope.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
            ContactSelector.show(ctx, "点歌范围", ModuleSettings.ddMusicScope, new ContactSelector.OnScopeSaved(){public void onSaved(Set<String> ids){ModuleSettings.ddMusicScope.clear();ModuleSettings.ddMusicScope.addAll(ids);ModuleSettings.saveAll();Utils.t(ctx,"已选"+ids.size()+"个");}});
        }});
        r.addView(btnDdScope);
        btn(ctx, r, "视频解析", "打开", new View.OnClickListener(){public void onClick(View v){showVid(ctx);}});
        addSection(ctx, r, "⏰ 定时调度");
        Button btnSch = ThemeEngine.createBtn(ctx, "打开调度设置");
        ThemeEngine.styleButton(btnSch);
        btnSch.setOnClickListener(new View.OnClickListener(){public void onClick(View v){FunctionDialogs.showSchedule(ctx);}});
        r.addView(btnSch);
        addSection(ctx, r, "\uD83D\uDC65 群管理");
        sw(ctx, r, "入群欢迎", ModuleSettings.welcomeEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.welcomeEnabled=v;ModuleSettings.saveAll();}});
        btn(ctx, r, "欢迎语设置", "配置", new View.OnClickListener(){public void onClick(View v){FunctionDialogs.showWelcome(ctx);}});
        sw(ctx, r, "自动通过好友", ModuleSettings.autoAcceptFriend, new SwitchCB(){public void onChange(boolean v){ModuleSettings.autoAcceptFriend=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "退群提醒", ModuleSettings.leftGroupTipEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.leftGroupTipEnabled=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "关键词拉人", ModuleSettings.groupInviteEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.groupInviteEnabled=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "防广告", ModuleSettings.antiAdEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.antiAdEnabled=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "自动踢人", ModuleSettings.autoKickEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.autoKickEnabled=v;ModuleSettings.saveAll();}});
        btn(ctx, r, "黑名单管理", "打开", new View.OnClickListener(){public void onClick(View v){FunctionDialogs.showBlacklist(ctx);}});
        addSection(ctx, r, "🔇 免打扰");
        sw(ctx, r, "启用免打扰", ModuleSettings.quietEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.quietEnabled=v;ModuleSettings.saveAll();}});
        ed(ctx, r, "开始(HH:mm)", ModuleSettings.quietStart, new EditCB(){public void onChange(String s){ModuleSettings.quietStart=s;ModuleSettings.saveAll();}});
        ed(ctx, r, "结束(HH:mm)", ModuleSettings.quietEnd, new EditCB(){public void onChange(String s){ModuleSettings.quietEnd=s;ModuleSettings.saveAll();}});
    }

    private static void buildTab3(Context ctx, LinearLayout r) {
        addSection(ctx, r, "\uD83C\uDFB5 叮咚");
        sw(ctx, r, "语音点歌", ModuleSettings.ddVoiceSongEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.ddVoiceSongEnabled=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "视频提取语音", ModuleSettings.ddVideoAudioEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.ddVideoAudioEnabled=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "视频直发", ModuleSettings.ddVideoMsgEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.ddVideoMsgEnabled=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "歌词搜索", ModuleSettings.ddLyricsEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.ddLyricsEnabled=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "天气查询", ModuleSettings.ddWeatherEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.ddWeatherEnabled=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "笑话/金句", ModuleSettings.ddFunEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.ddFunEnabled=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "自触发模式", ModuleSettings.ddSelfTrigger, new SwitchCB(){public void onChange(boolean v){ModuleSettings.ddSelfTrigger=v;ModuleSettings.saveAll();}});
        addSection(ctx, r, "\uD83C\uDF10 语音中继");
        sw(ctx, r, "语音中继(自动播放)", ModuleSettings.jdyVoiceRelayEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.jdyVoiceRelayEnabled=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "播报发送者", ModuleSettings.jdyVoiceRelaySpeakSender, new SwitchCB(){public void onChange(boolean v){ModuleSettings.jdyVoiceRelaySpeakSender=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "免打扰", ModuleSettings.jdyVoiceRelayQuietEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.jdyVoiceRelayQuietEnabled=v;ModuleSettings.saveAll();}});
        addSection(ctx, r, "\uD83E\uDD16 AI");
        sw(ctx, r, "启用AI", ModuleSettings.aiToolboxEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.aiToolboxEnabled=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "AI图片生成", ModuleSettings.imageGenEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.imageGenEnabled=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "AI视频生成", ModuleSettings.videoGenEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.videoGenEnabled=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "语音转文字", ModuleSettings.voiceToTextEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.voiceToTextEnabled=v;ModuleSettings.saveAll();}});
        ed(ctx, r, "方舟Key", ModuleSettings.arkApiKey, new EditCB(){public void onChange(String s){ModuleSettings.arkApiKey=s;ModuleSettings.saveAll();}});
        addSection(ctx, r, "\uD83C\uDFAC 视频解析");
        sw(ctx, r, "视频解析开关", ModuleSettings.videoParseEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.videoParseEnabled=v;ModuleSettings.saveAll();}});
    }

    private static void buildTab4(Context ctx, LinearLayout r) {
        addSection(ctx, r, "\uD83E\uDDE0 DeepSeek");
        sw(ctx, r, "启用DeepSeek", ModuleSettings.deepseekEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.deepseekEnabled=v;ModuleSettings.saveAll();}});
        ed(ctx, r, "API Key", ModuleSettings.deepseekApiKey, new EditCB(){public void onChange(String s){ModuleSettings.deepseekApiKey=s;ModuleSettings.saveAll();}});
        sw(ctx, r, "智能聊天回复", ModuleSettings.deepseekSmartReply, new SwitchCB(){public void onChange(boolean v){ModuleSettings.deepseekSmartReply=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "翻译模式", ModuleSettings.deepseekTranslate, new SwitchCB(){public void onChange(boolean v){ModuleSettings.deepseekTranslate=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "摘要模式", ModuleSettings.deepseekSummary, new SwitchCB(){public void onChange(boolean v){ModuleSettings.deepseekSummary=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "@我回复", ModuleSettings.deepseekAtReply, new SwitchCB(){public void onChange(boolean v){ModuleSettings.deepseekAtReply=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "AI写作", ModuleSettings.deepseekWriting, new SwitchCB(){public void onChange(boolean v){ModuleSettings.deepseekWriting=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "AI问答", ModuleSettings.deepseekQA, new SwitchCB(){public void onChange(boolean v){ModuleSettings.deepseekQA=v;ModuleSettings.saveAll();}});
        addSection(ctx, r, "\uD83D\uDEE1 安全");
        sw(ctx, r, "防撤回", ModuleSettings.recallLogEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.recallLogEnabled=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "自动抢红包", ModuleSettings.redPacketGrabEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.redPacketGrabEnabled=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "敏感词过滤", ModuleSettings.sensitiveFilterEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.sensitiveFilterEnabled=v;ModuleSettings.saveAll();}});
        addSection(ctx, r, "\uD83D\uDCAC 关键词回复");
        sw(ctx, r, "启用关键词回复", ModuleSettings.keywordReplyEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.keywordReplyEnabled=v;ModuleSettings.saveAll();}});
        addSection(ctx, r, "\uD83D\uDCCA 数据");
        sw(ctx, r, "文件分类记录", ModuleSettings.fileClassifyEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.fileClassifyEnabled=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "未读消息统计", ModuleSettings.unreadStatsEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.unreadStatsEnabled=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "群活跃统计", ModuleSettings.activityStatsEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.activityStatsEnabled=v;ModuleSettings.saveAll();}});
        sw(ctx, r, "群投票", ModuleSettings.voteEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.voteEnabled=v;ModuleSettings.saveAll();}});
        addSection(ctx, r, "\uD83D\uDCC5 定时公告");
        sw(ctx, r, "启用定时公告", ModuleSettings.schedAnnounceEnabled, new SwitchCB(){public void onChange(boolean v){ModuleSettings.schedAnnounceEnabled=v;ModuleSettings.saveAll();}});
        addSection(ctx, r, "\uD83D\uDC41 入口");
        sw(ctx, r, "显示设置页卡片", ModuleSettings.entryCardVisible, new SwitchCB(){public void onChange(boolean v){ModuleSettings.entryCardVisible=v;ModuleSettings.saveAll();}});
        btn(ctx, r, "定时调度", "设置", new View.OnClickListener(){public void onClick(View v){FunctionDialogs.showSchedule(ctx);}});
        btn(ctx, r, "系统状态", "查看", new View.OnClickListener(){public void onClick(View v){FunctionDialogs.showStatus(ctx);}});
        btn(ctx, r, "使用帮助", "查看", new View.OnClickListener(){public void onClick(View v){FunctionDialogs.showHelp(ctx);}});
    }

    public static void showDingDongPanel(Activity act) {
        if (act == null || act.isFinishing()) return;
        final Context ctx = act;
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Utils.dp(ctx, 20), Utils.dp(ctx, 28), Utils.dp(ctx, 20), Utils.dp(ctx, 20));
        root.setBackground(ThemeEngine.createGlassBg(ctx, 0));

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText("XKR\u52A9\u624B");
        tvTitle.setTextSize(24); tvTitle.setTextColor(ThemeEngine.thText());
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setPadding(0, 0, 0, Utils.dp(ctx, 20));
        root.addView(tvTitle);

        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        grid.setGravity(Gravity.CENTER);

        final AlertDialog[] dlgHolder = new AlertDialog[1];

        String[][] btns1 = {{"点歌","点歌 "},{"歌词","歌词 "},{"天气","天气 "},{"笑话","笑话"}};
        String[][] btns2 = {{"金句","金句"},{"功能设置","SETTINGS"},{"状态","STATUS"},{"帮助","HELP"}};

        for (int col = 0; col < 2; col++) {
            LinearLayout colLayout = new LinearLayout(ctx);
            colLayout.setOrientation(LinearLayout.VERTICAL);
            colLayout.setGravity(Gravity.CENTER);
            String[][] colBtns = (col == 0) ? btns1 : btns2;
            for (int row = 0; row < colBtns.length; row++) {
                Button btn = ThemeEngine.createBtn(ctx, colBtns[row][0]);
                btn.setBackground(ThemeEngine.createGlassBtnBg(ctx, 10));
                btn.setTextColor(ThemeEngine.thText());
                final String action = colBtns[row][1];
                btn.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        safeDismiss(dlgHolder[0]);
                        if ("SETTINGS".equals(action)) FunctionDialogs.showSettings(ctx);
                        else if ("STATUS".equals(action)) FunctionDialogs.showStatus(ctx);
                        else if ("HELP".equals(action)) FunctionDialogs.showHelp(ctx);
                        else Utils.t(ctx, "请在聊天中输入: " + action);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Utils.dp(ctx, 100), Utils.dp(ctx, 54));
                lp.setMargins(Utils.dp(ctx, 6), Utils.dp(ctx, 6), Utils.dp(ctx, 6), Utils.dp(ctx, 6));
                colLayout.addView(btn, lp);
            }
            grid.addView(colLayout);
        }

        root.addView(grid);
        root.addView(ThemeEngine.spacer(ctx, 2));

        Button btnBack = ThemeEngine.createBtn(ctx, "返回");
        ThemeEngine.styleButton(btnBack);
        root.addView(btnBack, new LinearLayout.LayoutParams(-1, -2));

        final AlertDialog dlg = new AlertDialog.Builder(ctx).setView(root).setCancelable(true).create();
        dlgHolder[0] = dlg;
        btnBack.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { safeDismiss(dlgHolder[0]); } });
        dlg.show();
    }

    private static void mk(Context ctx, String title, String hint, final String src) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Utils.dp(ctx, 14), Utils.dp(ctx, 10), Utils.dp(ctx, 14), Utils.dp(ctx, 10));
        TextView tv = new TextView(ctx); tv.setText(title); tv.setTextSize(17);
        tv.setTextColor(ThemeEngine.thAccent()); tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, 0, 0, Utils.dp(ctx, 8)); root.addView(tv);
        final EditText et = new EditText(ctx); et.setHint(hint);
        ThemeEngine.styleInput(et);
        root.addView(et); root.addView(ThemeEngine.spacer(ctx, 8));
        Button btnS = ThemeEngine.createBtn(ctx, "搜索");
        ThemeEngine.styleButton(btnS);
        final LinearLayout ra = new LinearLayout(ctx); ra.setOrientation(LinearLayout.VERTICAL);
        btnS.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
            final String kw=et.getText().toString().trim();
            if(kw.isEmpty()){Utils.t(ctx,"请输入关键词");return;}
            Utils.rB(new Runnable(){public void run(){
                org.json.JSONObject r=null;
                if("huanggou".equals(src))r=MusicManager.searchHuangGou(kw);
                else if("douyin".equals(src))r=MusicManager.searchDouyinMusic(kw);
                final org.json.JSONObject fr=r;
                Utils.rM(new Runnable(){public void run(){
                    ra.removeAllViews();
                    if(fr!=null&&fr.optJSONArray("list")!=null){
                        org.json.JSONArray l=fr.optJSONArray("list");
                        for(int i=0;i<Math.min(l.length(),10);i++){
                            try{org.json.JSONObject it=l.getJSONObject(i);
                                TextView row=new TextView(ctx);
                                row.setText((i+1)+". "+it.optString("name",it.optString("songname","?")));
                                row.setTextSize(12);row.setTextColor(ThemeEngine.thText());
                                ra.addView(row);
                            }catch(Exception e){}
                        }
                    }else{TextView ept=new TextView(ctx);ept.setText("未找到");ept.setTextSize(12);ept.setTextColor(ThemeEngine.thText2());ra.addView(ept);}
                }});
            }});
        }});
        root.addView(btnS);root.addView(ThemeEngine.spacer(ctx,8));
        root.addView(ra);root.addView(ThemeEngine.spacer(ctx,8));
        Button btnB = ThemeEngine.createBtn(ctx, "返回");
        ThemeEngine.styleButton(btnB);
        final AlertDialog dl=new AlertDialog.Builder(ctx).setView(root).setCancelable(true).create();
        btnB.setOnClickListener(new View.OnClickListener(){public void onClick(View v){safeDismiss(dl);}});root.addView(btnB);
        Window w=dl.getWindow();if(w!=null){w.setBackgroundDrawable(new ColorDrawable(ThemeEngine.thBg()));w.setLayout((int)(ctx.getResources().getDisplayMetrics().widthPixels*0.9),-2);}dl.show();
    }

    private static void showVid(Context ctx) {
        LinearLayout root=new LinearLayout(ctx);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(Utils.dp(ctx,14),Utils.dp(ctx,10),Utils.dp(ctx,14),Utils.dp(ctx,10));
        TextView tv=new TextView(ctx);tv.setText("乐少多平台视频解析");tv.setTextSize(16);tv.setTextColor(ThemeEngine.thAccent());tv.setGravity(Gravity.CENTER);root.addView(tv);
        final EditText et=new EditText(ctx);et.setHint("粘贴视频分享链接");
        ThemeEngine.styleInput(et);
        root.addView(et);root.addView(ThemeEngine.spacer(ctx,8));
        Button btnP = ThemeEngine.createBtn(ctx, "解析");
        ThemeEngine.styleButton(btnP);
        btnP.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
            String url=et.getText().toString().trim();
            if(url.isEmpty()){Utils.t(ctx,"请输入链接");return;}
            Utils.t(ctx,"解析中...");
            Utils.rB(new Runnable(){public void run(){
                final String result=VideoParser.parse(url);
                Utils.rM(new Runnable(){public void run(){
                    if(result!=null&&!result.isEmpty()){
                        LinearLayout rl=new LinearLayout(ctx);rl.setOrientation(LinearLayout.VERTICAL);
                        rl.setPadding(Utils.dp(ctx,16),Utils.dp(ctx,14),Utils.dp(ctx,16),Utils.dp(ctx,14));
                        TextView tt=new TextView(ctx);tt.setText("解析结果");tt.setTextSize(16);
                        tt.setTextColor(ThemeEngine.thAccent());tt.setGravity(Gravity.CENTER);
                        tt.setPadding(0,0,0,Utils.dp(ctx,8));rl.addView(tt);
                        TextView rt=new TextView(ctx);rt.setText(result);rt.setTextSize(12);
                        rt.setTextColor(ThemeEngine.thText());rt.setPadding(0,Utils.dp(ctx,6),0,Utils.dp(ctx,10));rl.addView(rt);
                        Button copyBtn=ThemeEngine.createBtn(ctx,"复制");ThemeEngine.styleButton(copyBtn);
                        copyBtn.setOnClickListener(new View.OnClickListener(){public void onClick(View vv){
                            try{
                                android.content.ClipboardManager cm=(android.content.ClipboardManager)ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                                android.content.ClipData cdClip=android.content.ClipData.newPlainText("video_url",result);
                                cm.setPrimaryClip(cdClip);Utils.t(ctx,"已复制");
                            }catch(Exception e){}
                        }});rl.addView(copyBtn);
                        Button closeBtn=ThemeEngine.createBtn(ctx,"关闭");ThemeEngine.styleButton(closeBtn);
                        final AlertDialog rd=new AlertDialog.Builder(ctx).setView(rl).setCancelable(true).create();
                        closeBtn.setOnClickListener(new View.OnClickListener(){public void onClick(View vv){safeDismiss(rd);}});
                        rl.addView(closeBtn);
                        rd.show();
                    }else{Utils.t(ctx,"解析失败");}
                }});
            }});
        }});root.addView(btnP);root.addView(ThemeEngine.spacer(ctx,8));
        Button btnB = ThemeEngine.createBtn(ctx, "关闭");
        ThemeEngine.styleButton(btnB);
        final AlertDialog dl=new AlertDialog.Builder(ctx).setView(root).setCancelable(true).create();
        btnB.setOnClickListener(new View.OnClickListener(){public void onClick(View v){safeDismiss(dl);}});root.addView(btnB);
        Window w=dl.getWindow();if(w!=null){w.setBackgroundDrawable(new ColorDrawable(ThemeEngine.thBg()));w.setLayout((int)(ctx.getResources().getDisplayMetrics().widthPixels*0.9),-2);}dl.show();
    }

    private static void showMass(Context ctx) {
        LinearLayout root=new LinearLayout(ctx);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(Utils.dp(ctx,14),Utils.dp(ctx,10),Utils.dp(ctx,14),Utils.dp(ctx,10));
        TextView tv=new TextView(ctx);tv.setText("乐少定时群发");tv.setTextSize(17);tv.setTextColor(ThemeEngine.thAccent());tv.setGravity(Gravity.CENTER);tv.setPadding(0,0,0,Utils.dp(ctx,8));root.addView(tv);
        ed(ctx,root,"消息内容",ModuleSettings.massSendTextContent,new EditCB(){public void onChange(String s){ModuleSettings.massSendTextContent=s;ModuleSettings.saveAll();}});
        ed(ctx,root,"发送间隔(ms)",String.valueOf(ModuleSettings.massSendInterval),new EditCB(){public void onChange(String s){try{ModuleSettings.massSendInterval=Long.parseLong(s);}catch(Exception e){}ModuleSettings.saveAll();}});
        root.addView(ThemeEngine.spacer(ctx,8));
        Button btnS = ThemeEngine.createBtn(ctx, "保存并执行");
        ThemeEngine.styleButton(btnS);
        btnS.setOnClickListener(new View.OnClickListener(){public void onClick(View v){ModuleSettings.saveAll();MassMessenger.executeSend(0,ModuleSettings.massSendTextContent,ModuleSettings.massSendTargetWxids,ModuleSettings.massSendInterval);}});root.addView(btnS);root.addView(ThemeEngine.spacer(ctx,4));
        Button btnB = ThemeEngine.createBtn(ctx, "返回");
        ThemeEngine.styleButton(btnB);
        final AlertDialog dl=new AlertDialog.Builder(ctx).setView(root).setCancelable(true).create();
        btnB.setOnClickListener(new View.OnClickListener(){public void onClick(View v){safeDismiss(dl);}});root.addView(btnB);
        Window w=dl.getWindow();if(w!=null){w.setBackgroundDrawable(new ColorDrawable(ThemeEngine.thBg()));w.setLayout((int)(ctx.getResources().getDisplayMetrics().widthPixels*0.9),-2);}dl.show();
    }

    private static void showDouyin(Context ctx) {
        LinearLayout root=new LinearLayout(ctx);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(Utils.dp(ctx,14),Utils.dp(ctx,10),Utils.dp(ctx,14),Utils.dp(ctx,10));
        TextView tv=new TextView(ctx);tv.setText("抖音分享配置");tv.setTextSize(16);tv.setTextColor(ThemeEngine.thAccent());tv.setGravity(Gravity.CENTER);tv.setPadding(0,0,0,Utils.dp(ctx,8));root.addView(tv);
        sw(ctx,root,"自动识别抖音链接",ModuleSettings.announceDouyin,new SwitchCB(){public void onChange(boolean v){ModuleSettings.announceDouyin=v;ModuleSettings.saveAll();}});
        ed(ctx,root,"抖音CK(选填)",ModuleSettings.douyinProfileCkey,new EditCB(){public void onChange(String s){ModuleSettings.douyinProfileCkey=s;ModuleSettings.saveAll();}});
        Button btnB = ThemeEngine.createBtn(ctx, "返回");
        ThemeEngine.styleButton(btnB);
        final AlertDialog dl=new AlertDialog.Builder(ctx).setView(root).setCancelable(true).create();
        btnB.setOnClickListener(new View.OnClickListener(){public void onClick(View v){safeDismiss(dl);}});root.addView(btnB);
        Window w=dl.getWindow();if(w!=null){w.setBackgroundDrawable(new ColorDrawable(ThemeEngine.thBg()));w.setLayout((int)(ctx.getResources().getDisplayMetrics().widthPixels*0.85),-2);}dl.show();
    }

    // ===== helpers =====
    private static void addSection(Context ctx, LinearLayout r, String t) {
        LinearLayout h=new LinearLayout(ctx);h.setPadding(Utils.dp(ctx,12),Utils.dp(ctx,6),Utils.dp(ctx,12),Utils.dp(ctx,6));
        GradientDrawable g=new GradientDrawable();g.setCornerRadius(Utils.dp(ctx,8));g.setColor(ThemeEngine.thCard());h.setBackground(g);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=Utils.dp(ctx,8);h.setLayoutParams(p);
        TextView tv=new TextView(ctx);tv.setText(t);tv.setTextSize(15);tv.setTextColor(ThemeEngine.thAccent());tv.setTypeface(null,Typeface.BOLD);h.addView(tv);r.addView(h);
    }
    private static void sw(Context ctx,LinearLayout p,String l,boolean c,final SwitchCB cb){
        LinearLayout r=new LinearLayout(ctx);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(Utils.dp(ctx,16),Utils.dp(ctx,12),Utils.dp(ctx,16),Utils.dp(ctx,12));
        TextView tv=new TextView(ctx);tv.setText(l);tv.setTextSize(12);tv.setTextColor(ThemeEngine.thText());tv.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));r.addView(tv);
        Switch s=new Switch(ctx);s.setChecked(c);ThemeEngine.styleSwitch(s,c,ctx);
        s.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){public void onCheckedChanged(CompoundButton b,boolean v){ThemeEngine.styleSwitch(s,v,ctx);if(cb!=null)cb.onChange(v);}});r.addView(s);p.addView(r);
    }
    private static void ed(Context ctx,LinearLayout p,String l,String v,final EditCB cb){
        LinearLayout r=new LinearLayout(ctx);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(0,Utils.dp(ctx,2),0,Utils.dp(ctx,2));
        TextView tv=new TextView(ctx);tv.setText(l+": ");tv.setTextSize(10);tv.setTextColor(ThemeEngine.thText2());r.addView(tv);
        final EditText e=new EditText(ctx);e.setText(v!=null?v:"");
        ThemeEngine.styleInput(e);
        e.setTextSize(11);
        e.setLayoutParams(new LinearLayout.LayoutParams(0,Utils.dp(ctx,28),1));
        e.setOnFocusChangeListener(new View.OnFocusChangeListener(){public void onFocusChange(View vv,boolean h){if(!h&&cb!=null)cb.onChange(e.getText().toString());}});r.addView(e);p.addView(r);
    }
    private static void btn(Context ctx,LinearLayout p,String l,String bt,final View.OnClickListener li){
        LinearLayout r=new LinearLayout(ctx);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(0,Utils.dp(ctx,4),0,Utils.dp(ctx,4));
        TextView tv=new TextView(ctx);tv.setText(l);tv.setTextSize(12);tv.setTextColor(ThemeEngine.thText());tv.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));r.addView(tv);
        if(bt!=null&&!bt.isEmpty()){Button b=ThemeEngine.createBtn(ctx,bt);b.setTextSize(10);b.setTextColor(ThemeEngine.thText());b.setBackground(ThemeEngine.createOutlineBtnBg(ctx,4));b.setPadding(Utils.dp(ctx,12),Utils.dp(ctx,2),Utils.dp(ctx,12),Utils.dp(ctx,2));if(li!=null)b.setOnClickListener(li);r.addView(b);}p.addView(r);
    }
    private static AlertDialog cd;public static void safeDismiss(AlertDialog d){try{if(d!=null&&d.isShowing())d.dismiss();}catch(Throwable e){}}
}
