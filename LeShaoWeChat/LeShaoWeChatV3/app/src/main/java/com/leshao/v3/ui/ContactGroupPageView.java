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
import android.widget.TextView;

import android.widget.Switch;
import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.*;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.ui.widgets.ColorPickerDialog;
import com.leshao.v3.ui.widgets.M3Page;

import android.widget.Toast;

public class ContactGroupPageView {

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;
        final SharedPreferences prefs = ContextManager.getPrefs();
        final ModuleConfig cfg = ModuleConfig.load(prefs);
        final Activity act = parentAct;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CandyUi.pageGradient());
        InsetsUtil.clipRounded(root);
        root.setPadding((int)(AppColors.SPACE_MD_DP * d), (int)(6 * d),
                (int)(AppColors.SPACE_MD_DP * d), (int)(8 * d));

        // ==================== 转发类 ====================
        root.addView(M3Page.section(ctx, "乐少转发",
                "语音/收藏/自动转发、突破9人上限、原生转发按钮替换、万群定时群发"));

        // 语音消息转发
        LinearLayout cardChat = makeCard(ctx, d);
        boolean vfOn = HookConfig.isEnabled("voice_forward");

        // v998: 移除"消息防撤回"功能入口
        cardChat.addView(switchRow(ctx, d, "语音消息转发", null, vfOn, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean("voice_forward", on).apply();
            VoiceForwardHook.setEnabled(on);
        }, null));
        root.addView(cardChat);

        root.addView(candyDivider(ctx, d));

        // 收藏语音转发：文档《收藏语音转发WeChat_FavVoice_Forward_Analysis.md》路线A
        boolean favVoiceOn = prefs != null && prefs.getBoolean(FavVoiceForwardHook.K_ENABLED, false);
        LinearLayout cardFavVoice = makeCard(ctx, d);
        cardFavVoice.addView(switchRow(ctx, d, "收藏语音转发",
                "收藏的语音长按可转发给联系人/群聊", favVoiceOn,
                (v, on) -> {
                    if (prefs != null) prefs.edit().putBoolean(FavVoiceForwardHook.K_ENABLED, on).apply();
                    FavVoiceForwardHook.setEnabled(on);
                    Toast.makeText(ctx, "收藏语音转发已" + (on ? "开启" : "关闭"),
                            Toast.LENGTH_SHORT).show();
                }, null));
        root.addView(cardFavVoice);

        root.addView(candyDivider(ctx, d));

        // v3.0.206: 聊天页面收藏语音转发（聊天窗口「+」→ 收藏选择页，长按/单击语音直接转发给当前聊天）
        boolean chatFavVoiceOn = prefs != null
                && prefs.getBoolean(ChatFavVoiceHook.K_ENABLED, false);
        LinearLayout cardChatFavVoice = makeCard(ctx, d);
        cardChatFavVoice.addView(switchRow(ctx, d, "聊天页面收藏语音转发",
                "聊天「+」→收藏选择页中，单击/长按语音可直接转发给当前聊天", chatFavVoiceOn,
                (v, on) -> {
                    if (prefs != null) prefs.edit().putBoolean(ChatFavVoiceHook.K_ENABLED, on).apply();
                    ChatFavVoiceHook.setEnabled(on);
                    Toast.makeText(ctx, "聊天页面收藏语音转发已" + (on ? "开启" : "关闭"),
                            Toast.LENGTH_SHORT).show();
                }, null));
        root.addView(cardChatFavVoice);

        root.addView(candyDivider(ctx, d));

        // v3.0.89: 突破转发/群发多选联系人 9 人上限（文档《微信突破转发群发9个联系人上限》方案A）
        boolean forwardLimitOn = prefs != null
                && prefs.getBoolean(ForwardLimitHook.K_ENABLED, false);
        LinearLayout cardForwardLimit = makeCard(ctx, d);
        cardForwardLimit.addView(switchRow(ctx, d, "去你妈只能选9个对象",
                "转发/群发多选联系人时突破 9 人上限", forwardLimitOn,
                (v, on) -> {
                    if (prefs != null) prefs.edit().putBoolean(ForwardLimitHook.K_ENABLED, on).apply();
                    ForwardLimitHook.setEnabled(on);
                    Toast.makeText(ctx, (on ? "已开启" : "已关闭")
                                    + "突破9人上限（频繁大群发易触发风控，请注意频率）",
                            Toast.LENGTH_LONG).show();
                }, null));
        root.addView(cardForwardLimit);

        root.addView(candyDivider(ctx, d));

        // 微信原生转发按钮替换：长按消息菜单「转发」/ 多选左下角「转发」使用模块联系人选择器
        boolean wxFwdReplaceOn = prefs != null
                && prefs.getBoolean(WxForwardReplaceHook.K_ENABLED, false);
        LinearLayout cardWxFwdReplace = makeCard(ctx, d);
        cardWxFwdReplace.addView(switchRow(ctx, d, "微信原生转发按钮替换",
                "长按消息「转发」/多选左下角「转发」改用模块联系人选择器（不限制人数）", wxFwdReplaceOn,
                (v, on) -> {
                    if (prefs != null) prefs.edit().putBoolean(WxForwardReplaceHook.K_ENABLED, on).apply();
                    WxForwardReplaceHook.setEnabled(on);
                    Toast.makeText(ctx, "微信原生转发按钮替换已" + (on ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
                }, null));
        root.addView(cardWxFwdReplace);

        root.addView(candyDivider(ctx, d));

        // ==================== 消息增强类 ====================
        root.addView(M3Page.section(ctx, "消息增强",
                "防撤回、长按菜单净化、消息伪装"));

        // v1146: 消息防撤回（严格实现文档《WeChat_AntiRevoke_Reverse.md》H1/H3 方案）
        boolean antiRevokeOn = prefs != null && prefs.getBoolean(AntiRecallHook.K_MASTER, true);
        LinearLayout cardAntiRevoke = makeCard(ctx, d);
        cardAntiRevoke.addView(switchRow(ctx, d, "消息防撤回",
                "拦截服务端撤回改写，原消息继续显示（保留微信原生提示）", antiRevokeOn,
                (v, on) -> {
                    if (prefs != null) prefs.edit().putBoolean(AntiRecallHook.K_MASTER, on).apply();
                    AntiRecallHook.setEnabled(on);
                    Toast.makeText(ctx, "消息防撤回已" + (on ? "开启" : "关闭")
                            + "（重启微信后完全生效）", Toast.LENGTH_SHORT).show();
                },
                v -> AntiRecallHook.showConfigDialog(act)));
        root.addView(cardAntiRevoke);

        root.addView(candyDivider(ctx, d));

        // v1110: 消息长按菜单净化入口
        LinearLayout cardMsgMenu = makeCard(ctx, d);
        cardMsgMenu.addView(M3Page.clickRow(ctx, "\uD83E\uDDF9", "去你妈的消息长按菜单",
                "勾选要移除的微信原生按钮",
                () -> SubPageActivity.open(act, "去你妈的消息长按菜单", 21)));
        root.addView(cardMsgMenu);

        root.addView(candyDivider(ctx, d));

        // 消息伪装（原"更多功能"卡片拆出，归入消息增强）
        LinearLayout cardMsgForge = makeCard(ctx, d);
        cardMsgForge.addView(switchRow(ctx, d, "消息伪装",
                "文本伪装成系统消息 / 名片 / 链接卡片", MsgForgeHook.isEnabled(),
                (v, on) -> {
                    MsgForgeHook.setEnabled(on);
                    Toast.makeText(ctx, "消息伪装已" + (on ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
                },
                v -> SubPageActivity.open(act, "消息伪装", 23)));
        root.addView(cardMsgForge);

        // ==================== 红包装备类 ====================
        root.addView(M3Page.section(ctx, "红包装备",
                "自动抢红包"));

        // 自动抢红包（原"更多功能"卡片拆出）
        LinearLayout cardRedPacket = makeCard(ctx, d);
        cardRedPacket.addView(switchRow(ctx, d, "自动抢红包",
                "纯后台自动领取群红包", RedPacketHook.isEnabled(),
                (v, on) -> {
                    RedPacketHook.setEnabled(on);
                    Toast.makeText(ctx, "自动抢红包已" + (on ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
                },
                v -> SubPageActivity.open(act, "自动抢红包", 24)));
        root.addView(cardRedPacket);

        // ==================== 聊天类 ====================
        root.addView(M3Page.section(ctx, "聊天增强",
                "聊天分组、输入框快捷按钮"));

        // 聊天分组卡片
        boolean chatGroupOn = prefs != null && prefs.getBoolean("ls_chat_group_enabled", true);
        LinearLayout cardGroup = makeCard(ctx, d);
        cardGroup.addView(switchRow(ctx, d, "聊天分组", null, chatGroupOn, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean("ls_chat_group_enabled", on).apply();
            // v998: 开关变化后立即显示/隐藏聊天列表顶部的分组栏
            ChatGroupUiInjector.onEnabledChanged();
        }, v -> SubPageActivity.open(act, "聊天分组管理", 14)));
        root.addView(cardGroup);

        root.addView(candyDivider(ctx, d));

        // 输入框快捷按钮（原"更多功能"卡片拆出）
        LinearLayout cardFooter = makeCard(ctx, d);
        cardFooter.addView(switchRow(ctx, d, "输入框快捷按钮",
                "聊天输入框上方常驻一排按钮", ChatFooterBarHook.isEnabled(),
                (v, on) -> {
                    ChatFooterBarHook.setEnabled(on);
                    Toast.makeText(ctx, "输入框快捷按钮已" + (on ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
                },
                v -> SubPageActivity.open(act, "输入框快捷按钮", 26)));
        root.addView(cardFooter);

        root.addView(candyDivider(ctx, d));

        // 一键拉群（文档《微信_一键邀请联系人进多群_逆向分析.md》）
        LinearLayout cardBatchInvite = makeCard(ctx, d);
        cardBatchInvite.addView(switchRow(ctx, d, "一键拉群",
                "选好友→选群聊→按随机延迟逐群邀请",
                com.leshao.v3.hook.BatchInviteConfig.isEnabled(),
                (v, on) -> {
                    com.leshao.v3.hook.BatchInviteConfig.setEnabled(on);
                    Toast.makeText(ctx, "一键拉群已" + (on ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
                },
                v -> com.leshao.v3.hook.BatchInviteConfig.showConfigDialog(act)));
        root.addView(cardBatchInvite);

        // ==================== UI美化类 ====================
        root.addView(M3Page.section(ctx, "UI美化",
                "微信左上角菜单、聊天窗口长按菜单入口开关"));

        // v3.0.165: 自定义气泡 / 聊天时间线颜色 / 群聊成员昵称颜色 / 显示群成员头衔标签
        // 已迁移至主页「微信美化」分类，此处仅保留微信原生菜单入口开关
        LinearLayout cardEntry = makeCard(ctx, d);
        boolean cornerMenuOn = com.leshao.v3.wm.utils.WmPrefs.isCornerMenu();
        boolean longPressMenuOn = com.leshao.v3.wm.utils.WmPrefs.isLongPressMenu();

        cardEntry.addView(switchRow(ctx, d, "微信左上角菜单", null, cornerMenuOn, (v, on) -> {
            com.leshao.v3.wm.utils.WmPrefs.set("corner_menu", on);
        }, null));
        cardEntry.addView(switchRow(ctx, d, "聊天窗口长按菜单", null, longPressMenuOn, (v, on) -> {
            com.leshao.v3.wm.utils.WmPrefs.set("long_press_menu", on);
        }, null));
        root.addView(cardEntry);

        // ==================== 系统工具类 ====================
        root.addView(M3Page.section(ctx, "系统工具",
                "数据库直读"));

        // 数据库直读（原"更多功能"卡片拆出）
        LinearLayout cardDb = makeCard(ctx, d);
        cardDb.addView(M3Page.clickRow(ctx, "\uD83D\uDDC4", "数据库直读",
                "直接只读查询微信主库（联系人/群/消息）",
                () -> SubPageActivity.open(act, "数据库直读", 25)));
        root.addView(cardDb);

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
        card.setPadding(0, 0, 0, 0);
        card.setBackground(CandyUi.cardBg(ctx));
        InsetsUtil.clipRounded(card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, (int)(13 * d));
        card.setLayoutParams(lp);
        return card;
    }

    private static LinearLayout switchRow(Context ctx, float d, String title, String desc,
                                           boolean checked, CompoundButton.OnCheckedChangeListener listener,
                                           View.OnClickListener configListener) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // 全局规范: 行触控区域不低于 48dp
        row.setMinimumHeight((int)(48 * d));
        row.setPadding((int)(12 * d), (int)(10 * d), (int)(12 * d), (int)(10 * d));
        row.setBackground(CandyUi.rowBg(ctx));
        InsetsUtil.clipRounded(row);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView tv = new TextView(ctx);
        tv.setText(title); tv.setTextSize(16);
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
            CandyUi.ripple(btn, AppColors.SHAPE_FULL_DP);
            btn.setOnClickListener(configListener);
            row.addView(btn);
        }

        Switch sw = CandyUi.newSwitch(ctx); sw.setChecked(checked);
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);
        return row;
    }

    /** v3.0.150：颜色修改行（色块预览 + [取色]，0 = 恢复微信原生）。 */
    private static LinearLayout colorRow(Context ctx, float d, String title, String desc,
                                         int current, ColorPickerDialog.OnPick onPick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight((int)(48 * d));
        row.setPadding((int)(12 * d), (int)(10 * d), (int)(12 * d), (int)(10 * d));
        row.setBackground(CandyUi.rowBg(ctx));
        InsetsUtil.clipRounded(row);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView tv = new TextView(ctx);
        tv.setText(title); tv.setTextSize(16);
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

        // 色块预览
        int sz = (int)(22 * d);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(sz, sz);
        slp.setMargins(0, 0, (int)(10 * d), 0);
        View swatch = new View(ctx);
        swatch.setLayoutParams(slp);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius((int)(6 * d));
        if (current == 0) {
            gd.setColor(AppColors.inputBg());
            gd.setStroke((int)(1 * d), AppColors.outlineVariant());
        } else {
            gd.setColor(current);
            gd.setStroke((int)(1 * d), 0x33000000);
        }
        swatch.setBackground(gd);
        row.addView(swatch);

        TextView btn = new TextView(ctx);
        btn.setText("[取色]");
        btn.setTextSize(12);
        btn.setTextColor(AppColors.accent());
        btn.setPadding((int)(6 * d), 0, (int)(6 * d), 0);
        btn.setPaintFlags(btn.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        CandyUi.ripple(btn, AppColors.SHAPE_FULL_DP);
        btn.setOnClickListener(v ->
                ColorPickerDialog.show(ctx, title, current, true, onPick));
        row.addView(btn);

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
        return M3Page.divider(ctx);
    }

    private static View spacerV(Context ctx, float d, int dp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(dp * d)));
        return v;
    }
}