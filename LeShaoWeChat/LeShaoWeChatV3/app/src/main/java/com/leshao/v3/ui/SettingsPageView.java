package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.ContactRepository;
import com.leshao.v3.hook.AntiRecallHook;
import com.leshao.v3.hook.AntiDetectionHook;
import com.leshao.v3.hook.FriendRequestHook;
import com.leshao.v3.hook.DeleteDetect;
import com.leshao.v3.hook.StickyEnhance;
import com.leshao.v3.hook.UnreadBadge;
import com.leshao.v3.hook.TabCustom;
import com.leshao.v3.hook.CallFeatures;
import com.leshao.v3.hook.ShakeCustom;
import com.leshao.v3.model.KeywordRule;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.ui.widgets.M3Page;
import com.leshao.v3.ui.widgets.ModernButton;

/**
 * 设置页（v955 M3 重排）：分区 SectionHeader + filled card + M3 开关行。
 * 业务逻辑（ModuleConfig/prefs/Hook setEnabled/关键词规则/敏感词/系统状态）与原版完全一致。
 */
public class SettingsPageView {

    public static View create(Context ctx, Activity parentAct) {
        final SharedPreferences prefs = ContextManager.getPrefs();
        final ModuleConfig cfg = ModuleConfig.load(prefs);
        final Activity act = parentAct;

        LinearLayout root = M3Page.root(ctx);
        final LinearLayout content = root;

        // ============ 通用设置 ============
        content.addView(M3Page.section(ctx, "通用设置"));
        LinearLayout cardGeneral = M3Page.card(ctx);
        cardGeneral.addView(M3Page.switchRow(ctx, "⚡", "总开关", "关闭后所有增强功能停用",
                cfg.masterSwitch, (v, on) -> {
                    cfg.masterSwitch = on; cfg.save(prefs);
                }));
        content.addView(cardGeneral);

        // ============ 安全功能 ============
        content.addView(M3Page.section(ctx, "安全功能"));
        LinearLayout cardSecurity = M3Page.card(ctx);
        cardSecurity.addView(M3Page.switchRow(ctx, "🛡", "防撤回", "拦截消息撤回，保留聊天记录",
                cfg.antiRecall, (v, on) -> {
                    cfg.antiRecall = on; cfg.save(prefs); AntiRecallHook.setEnabled(on);
                }));
        cardSecurity.addView(M3Page.divider(ctx));
        cardSecurity.addView(M3Page.switchRow(ctx, "🥷", "反 Xposed 检测", "隐藏模块特征，防止被检测",
                cfg.antiDetection, (v, on) -> {
                    cfg.antiDetection = on; cfg.save(prefs); AntiDetectionHook.setEnabled(on);
                }));
        cardSecurity.addView(M3Page.divider(ctx));
        cardSecurity.addView(M3Page.switchRow(ctx, "🤝", "自动通过好友", "自动同意好友请求",
                cfg.autoAcceptFriend, (v, on) -> {
                    cfg.autoAcceptFriend = on; cfg.save(prefs); FriendRequestHook.setEnabled(on);
                }));
        cardSecurity.addView(M3Page.divider(ctx));
        cardSecurity.addView(M3Page.clickRow(ctx, "💬", "好友欢迎语设置", "配置自动通过后的欢迎语",
                () -> ConfigPanels.showFriendRequest(act, prefs)));
        cardSecurity.addView(editRow(ctx, "好友欢迎语", cfg.autoAcceptFriendMsg, s -> {
            cfg.autoAcceptFriendMsg = s; cfg.save(prefs);
        }));
        content.addView(cardSecurity);

        // ============ 会话与界面 ============
        content.addView(M3Page.section(ctx, "会话与界面"));
        LinearLayout cardConv = M3Page.card(ctx);
        cardConv.addView(M3Page.switchRow(ctx, "🔍", "好友删除检测", "被好友删除时提醒",
                cfg.deleteDetectEnabled, (v, on) -> {
                    cfg.deleteDetectEnabled = on; cfg.save(prefs); DeleteDetect.setEnabled(on);
                }));
        cardConv.addView(M3Page.divider(ctx));
        cardConv.addView(M3Page.switchRow(ctx, "📌", "置顶增强", "增强会话置顶能力",
                cfg.stickyEnhanceEnabled, (v, on) -> {
                    cfg.stickyEnhanceEnabled = on; cfg.save(prefs); StickyEnhance.setEnabled(on);
                }));
        cardConv.addView(M3Page.divider(ctx));
        cardConv.addView(M3Page.clickRow(ctx, "📌", "置顶增强设置", "配置置顶增强参数",
                () -> ConfigPanels.showStickyEnhance(act, prefs)));
        cardConv.addView(M3Page.divider(ctx));
        cardConv.addView(M3Page.switchRow(ctx, "🔴", "未读角标显示", "自定义未读消息角标样式",
                cfg.unreadBadgeEnabled, (v, on) -> {
                    cfg.unreadBadgeEnabled = on; cfg.save(prefs); UnreadBadge.setEnabled(on);
                }));
        cardConv.addView(M3Page.divider(ctx));
        cardConv.addView(M3Page.clickRow(ctx, "🔴", "未读角标设置", "配置角标显示参数",
                () -> ConfigPanels.showUnreadBadge(act, prefs)));
        cardConv.addView(M3Page.divider(ctx));
        cardConv.addView(M3Page.switchRow(ctx, "📑", "底部 Tab 自定义", "自定义微信底部导航栏",
                cfg.tabCustomEnabled, (v, on) -> {
                    cfg.tabCustomEnabled = on; cfg.save(prefs); TabCustom.setEnabled(on);
                }));
        cardConv.addView(M3Page.divider(ctx));
        cardConv.addView(M3Page.clickRow(ctx, "📑", "Tab 自定义设置", "配置底部 Tab 项",
                () -> ConfigPanels.showTabCustom(act, prefs)));
        content.addView(cardConv);

        // ============ 通话与数据 ============
        content.addView(M3Page.section(ctx, "通话与数据"));
        LinearLayout cardCall = M3Page.card(ctx);
        cardCall.addView(M3Page.switchRow(ctx, "📞", "通话录音/自动接听", "语音视频通话增强",
                cfg.callFeaturesEnabled, (v, on) -> {
                    cfg.callFeaturesEnabled = on; cfg.save(prefs); CallFeatures.setEnabled(on);
                }));
        cardCall.addView(M3Page.divider(ctx));
        cardCall.addView(M3Page.clickRow(ctx, "📞", "通话功能设置", "配置录音与自动接听参数",
                () -> ConfigPanels.showCallFeatures(act, prefs)));
        cardCall.addView(M3Page.divider(ctx));
        cardCall.addView(M3Page.switchRow(ctx, "📳", "摇一摇自定义", "自定义摇一摇触发动作",
                cfg.shakeCustomEnabled, (v, on) -> {
                    cfg.shakeCustomEnabled = on; cfg.save(prefs); ShakeCustom.setEnabled(on);
                }));
        cardCall.addView(M3Page.divider(ctx));
        cardCall.addView(M3Page.clickRow(ctx, "📳", "摇一摇设置", "配置摇一摇动作",
                () -> ConfigPanels.showShakeCustom(act, prefs)));
        content.addView(cardCall);

        // ============ 关键词回复 ============
        content.addView(M3Page.section(ctx, "关键词回复", "收到包含关键词的消息时自动回复"));
        LinearLayout cardKeyword = M3Page.card(ctx);
        cardKeyword.addView(M3Page.switchRow(ctx, "🔁", "关键词回复", "启用自动关键词回复",
                cfg.keywordReplyEnabled, (v, on) -> {
                    cfg.keywordReplyEnabled = on; cfg.save(prefs);
                }));
        content.addView(cardKeyword);

        final LinearLayout keywordList = new LinearLayout(ctx);
        keywordList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams kwLp = new LinearLayout.LayoutParams(-1, -2);
        kwLp.setMargins(0, dpInt(ctx, 8), 0, 0);
        keywordList.setLayoutParams(kwLp);
        refreshKeywordList(ctx, keywordList, cfg, prefs);
        content.addView(keywordList);

        LinearLayout addRow = new LinearLayout(ctx);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(-1, -2);
        addLp.setMargins(0, dpInt(ctx, 8), 0, 0);
        addRow.setLayoutParams(addLp);
        final EditText kwInput = M3Page.input(ctx, "关键词");
        kwInput.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        final EditText rpInput = M3Page.input(ctx, "回复内容");
        LinearLayout.LayoutParams rpLp = new LinearLayout.LayoutParams(0, -2, 1f);
        rpLp.setMarginStart(dpInt(ctx, 8));
        rpInput.setLayoutParams(rpLp);
        ModernButton addBtn = new ModernButton(ctx, "添加", ModernButton.STYLE_PRIMARY);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-2, -2);
        btnLp.setMarginStart(dpInt(ctx, 8));
        addBtn.setLayoutParams(btnLp);
        addBtn.onClick(() -> {
            String kw = kwInput.getText().toString().trim();
            String rp = rpInput.getText().toString().trim();
            if (!kw.isEmpty() && !rp.isEmpty()) {
                cfg.keywordRules.add(new KeywordRule(kw, rp, true));
                cfg.save(prefs);
                refreshKeywordList(ctx, keywordList, cfg, prefs);
                kwInput.setText(""); rpInput.setText("");
                M3Page.toastSuccess(ctx, "已添加规则");
            }
        });
        addRow.addView(kwInput);
        addRow.addView(rpInput);
        addRow.addView(addBtn);
        content.addView(addRow);

        // ============ 敏感词过滤 ============
        content.addView(M3Page.section(ctx, "敏感词过滤", "每行一个敏感词"));
        LinearLayout cardSensitive = M3Page.card(ctx);
        cardSensitive.addView(M3Page.switchRow(ctx, "🚫", "敏感词过滤", "命中敏感词的消息将被拦截",
                cfg.sensitiveFilterEnabled, (v, on) -> {
                    cfg.sensitiveFilterEnabled = on; cfg.save(prefs);
                }));
        cardSensitive.addView(M3Page.divider(ctx));
        StringBuilder swText = new StringBuilder();
        for (String w : cfg.sensitiveWords) { swText.append(w).append("\n"); }
        EditText swEdit = new EditText(ctx);
        swEdit.setText(swText.toString());
        swEdit.setTextSize(14);
        swEdit.setTextColor(AppColors.onSurface());
        swEdit.setHintTextColor(AppColors.onSurfaceVariant());
        swEdit.setHint("每行一个敏感词");
        swEdit.setMinLines(3);
        swEdit.setBackground(com.leshao.v3.ui.CandyUi.inputBg(ctx));
        int p = dpInt(ctx, 12);
        swEdit.setPadding(p, p, p, p);
        swEdit.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        swEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                cfg.sensitiveWords.clear();
                for (String line : s.toString().split("\n")) {
                    String t = line.trim();
                    if (!t.isEmpty()) cfg.sensitiveWords.add(t);
                }
                cfg.save(prefs);
            }
        });
        cardSensitive.addView(swEdit);
        content.addView(cardSensitive);

        // ============ 系统状态 ============
        content.addView(M3Page.section(ctx, "系统状态"));
        LinearLayout cardStatus = M3Page.card(ctx);
        final View dbRow = M3Page.infoRow(ctx, "联系人数据", "加载中...");
        cardStatus.addView(dbRow);
        cardStatus.addView(M3Page.divider(ctx));
        cardStatus.addView(M3Page.infoRow(ctx, "关键词规则", cfg.keywordRules.size() + " 条"));
        content.addView(cardStatus);

        final TextView dbVal = (TextView) ((android.view.ViewGroup) dbRow).getChildAt(1);
        ContactRepository.loadAsync(() ->
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        int f = ContactRepository.getFriends().size();
                        int g = ContactRepository.getGroups().size();
                        dbVal.setText("好友 " + f + " · 群聊 " + g);
                    } catch (Throwable ignored) {}
                }));

        return M3Page.scroll(ctx, content);
    }

    /** M3 行内编辑行：标题 + 输入框（保留欢迎语原逻辑） */
    private static View editRow(Context ctx, String label, String value, EditCallback cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int p = dpInt(ctx, 16);
        row.setPadding(p, dpInt(ctx, 10), p, dpInt(ctx, 10));

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(14);
        tv.setTextColor(AppColors.onSurface());
        tv.setSingleLine(true);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 0.35f));
        row.addView(tv);

        EditText et = new EditText(ctx);
        et.setText(value);
        et.setTextSize(14);
        et.setTextColor(AppColors.onSurface());
        et.setHintTextColor(AppColors.onSurfaceVariant());
        et.setSingleLine(true);
        et.setBackground(com.leshao.v3.ui.CandyUi.inputBg(ctx));
        int ep = dpInt(ctx, 10);
        et.setPadding(ep, ep, ep, ep);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(0, -2, 0.65f);
        etLp.setMarginStart(dpInt(ctx, 12));
        et.setLayoutParams(etLp);
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { cb.onChange(s.toString()); }
        });
        row.addView(et);
        return row;
    }

    private static void refreshKeywordList(Context ctx, LinearLayout keywordList,
                                           ModuleConfig cfg, SharedPreferences prefs) {
        keywordList.removeAllViews();
        if (cfg.keywordRules == null) return;
        for (int i = 0; i < cfg.keywordRules.size(); i++) {
            final int idx = i;
            KeywordRule r = cfg.keywordRules.get(i);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setBackground(com.leshao.v3.ui.CandyUi.cardBg(ctx));
            int p = dpInt(ctx, 4);
            row.setPadding(p, p, p, p);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, 0, 0, dpInt(ctx, 6));
            row.setLayoutParams(lp);

            TextView info = new TextView(ctx);
            info.setText(r.keyword + "  →  " + r.reply);
            info.setTextSize(13);
            info.setTextColor(AppColors.onSurface());
            info.setSingleLine(true);
            info.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(info);

            ModernButton del = new ModernButton(ctx, "删除", ModernButton.STYLE_TEXT);
            del.onClick(() -> {
                cfg.keywordRules.remove(idx); cfg.save(prefs);
                refreshKeywordList(ctx, keywordList, cfg, prefs);
            });
            row.addView(del);
            keywordList.addView(row);
        }
    }

    private static int dpInt(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    interface EditCallback { void onChange(String value); }
}
