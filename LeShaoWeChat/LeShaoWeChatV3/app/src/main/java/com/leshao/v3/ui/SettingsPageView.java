package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.ContactRepository;
import com.leshao.v3.hook.AntiRecallHook;
import com.leshao.v3.hook.AntiDetectionHook;
import com.leshao.v3.hook.FriendRequestHook;
import com.leshao.v3.hook.RedPacketHook;
import com.leshao.v3.hook.DeleteDetect;
import com.leshao.v3.hook.StickyEnhance;
import com.leshao.v3.hook.UnreadBadge;
import com.leshao.v3.hook.TabCustom;
import com.leshao.v3.hook.CallFeatures;
import com.leshao.v3.hook.MsgExport;
import com.leshao.v3.hook.ChatBackup;
import com.leshao.v3.hook.ShakeCustom;
import com.leshao.v3.model.KeywordRule;
import com.leshao.v3.model.ModuleConfig;

public class SettingsPageView {

    public static View create(Context ctx, Activity parentAct) {
        final SharedPreferences prefs = ContextManager.getPrefs();
        final ModuleConfig cfg = ModuleConfig.load(prefs);
        final Activity act = parentAct;
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(d, 16), dp(d, 16), dp(d, 16), dp(d, 16));
        root.setBackground(CandyUi.pageGradient());

        root.addView(sLabel(ctx, d, "通用设置"));

        root.addView(switchRow(ctx, d, "总开关", cfg.masterSwitch, (v, on) -> {
            cfg.masterSwitch = on; cfg.save(prefs);
        }));

        root.addView(sLabel(ctx, d, "安全功能"));
        root.addView(switchRow(ctx, d, "防撤回", cfg.antiRecall, (v, on) -> {
            cfg.antiRecall = on; cfg.save(prefs); AntiRecallHook.setEnabled(on);
        }));
        root.addView(switchRow(ctx, d, "抢红包", cfg.redPacketGrab, (v, on) -> {
            cfg.redPacketGrab = on; cfg.save(prefs); RedPacketHook.setEnabled(on);
        }));
        root.addView(switchRow(ctx, d, "反Xposed检测", cfg.antiDetection, (v, on) -> {
            cfg.antiDetection = on; cfg.save(prefs); AntiDetectionHook.setEnabled(on);
        }));
        root.addView(switchRow(ctx, d, "自动通过好友", cfg.autoAcceptFriend, (v, on) -> {
            cfg.autoAcceptFriend = on; cfg.save(prefs); FriendRequestHook.setEnabled(on);
        }, v -> ConfigPanels.showFriendRequest(act, prefs)));
        root.addView(editRow(ctx, d, "好友欢迎语", cfg.autoAcceptFriendMsg, s -> {
            cfg.autoAcceptFriendMsg = s; cfg.save(prefs);
        }));

        root.addView(sLabel(ctx, d, "会话与界面"));
        root.addView(switchRow(ctx, d, "好友删除检测", cfg.deleteDetectEnabled, (v, on) -> {
            cfg.deleteDetectEnabled = on; cfg.save(prefs); DeleteDetect.setEnabled(on);
        }));
        root.addView(switchRow(ctx, d, "置顶增强", cfg.stickyEnhanceEnabled, (v, on) -> {
            cfg.stickyEnhanceEnabled = on; cfg.save(prefs); StickyEnhance.setEnabled(on);
        }, v -> ConfigPanels.showStickyEnhance(act, prefs)));
        root.addView(switchRow(ctx, d, "未读角标显示", cfg.unreadBadgeEnabled, (v, on) -> {
            cfg.unreadBadgeEnabled = on; cfg.save(prefs); UnreadBadge.setEnabled(on);
        }, v -> ConfigPanels.showUnreadBadge(act, prefs)));
        root.addView(switchRow(ctx, d, "底部Tab自定义", cfg.tabCustomEnabled, (v, on) -> {
            cfg.tabCustomEnabled = on; cfg.save(prefs); TabCustom.setEnabled(on);
        }, v -> ConfigPanels.showTabCustom(act, prefs)));

        root.addView(sLabel(ctx, d, "通话与数据"));
        root.addView(switchRow(ctx, d, "通话录音/自动接听", cfg.callFeaturesEnabled, (v, on) -> {
            cfg.callFeaturesEnabled = on; cfg.save(prefs); CallFeatures.setEnabled(on);
        }, v -> ConfigPanels.showCallFeatures(act, prefs)));
        root.addView(switchRow(ctx, d, "消息导出", cfg.msgExportEnabled, (v, on) -> {
            cfg.msgExportEnabled = on; cfg.save(prefs); MsgExport.setEnabled(on);
        }));
        root.addView(switchRow(ctx, d, "聊天记录备份", cfg.chatBackupEnabled, (v, on) -> {
            cfg.chatBackupEnabled = on; cfg.save(prefs); ChatBackup.setEnabled(on);
        }));
        root.addView(switchRow(ctx, d, "摇一摇自定义", cfg.shakeCustomEnabled, (v, on) -> {
            cfg.shakeCustomEnabled = on; cfg.save(prefs); ShakeCustom.setEnabled(on);
        }, v -> ConfigPanels.showShakeCustom(act, prefs)));

        root.addView(switchRow(ctx, d, "关键词回复", cfg.keywordReplyEnabled, (v, on) -> {
            cfg.keywordReplyEnabled = on; cfg.save(prefs);
        }));
        LinearLayout keywordList = new LinearLayout(ctx);
        keywordList.setOrientation(LinearLayout.VERTICAL);
        refreshKeywordList(ctx, d, keywordList, cfg, prefs);
        root.addView(keywordList);

        LinearLayout addRow = new LinearLayout(ctx);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText kwInput = new EditText(ctx); kwInput.setHint("关键词");
        kwInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        addRow.addView(kwInput);
        EditText rpInput = new EditText(ctx); rpInput.setHint("回复内容");
        rpInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        addRow.addView(rpInput);
        Button addBtn = new Button(ctx); addBtn.setText("添加");
        addBtn.setOnClickListener(v -> {
            String kw = kwInput.getText().toString().trim();
            String rp = rpInput.getText().toString().trim();
            if (!kw.isEmpty() && !rp.isEmpty()) {
                cfg.keywordRules.add(new KeywordRule(kw, rp, true));
                cfg.save(prefs);
                refreshKeywordList(ctx, d, keywordList, cfg, prefs);
                kwInput.setText(""); rpInput.setText("");
            }
        });
        addRow.addView(addBtn);
        root.addView(addRow);

        root.addView(switchRow(ctx, d, "敏感词过滤", cfg.sensitiveFilterEnabled, (v, on) -> {
            cfg.sensitiveFilterEnabled = on; cfg.save(prefs);
        }));
        root.addView(sLabel(ctx, d, "敏感词 (每行一个)"));
        StringBuilder swText = new StringBuilder();
        for (String w : cfg.sensitiveWords) { swText.append(w).append("\n"); }
        EditText swEdit = new EditText(ctx); swEdit.setText(swText.toString());
        swEdit.setMinLines(3);
        swEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                cfg.sensitiveWords.clear();
                for (String line : s.toString().split("\n")) {
                    String t = line.trim();
                    if (!t.isEmpty()) cfg.sensitiveWords.add(t);
                }
                cfg.save(prefs);
            }
        });
        root.addView(swEdit);

        root.addView(sLabel(ctx, d, "系统状态"));
        LinearLayout dbRow = infoRow(ctx, d, "联系人数据", "加载中...");
        LinearLayout ruleRow = infoRow(ctx, d, "关键词规则", cfg.keywordRules.size() + " 条");
        root.addView(dbRow);
        root.addView(ruleRow);
        final TextView dbVal = (TextView) dbRow.getChildAt(1);
        ContactRepository.loadAsync(() ->
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        int f = ContactRepository.getFriends().size();
                        int g = ContactRepository.getGroups().size();
                        dbVal.setText("好友 " + f + " · 群聊 " + g);
                    } catch (Throwable ignored) {}
                }));

        ScrollView sv = new ScrollView(ctx);
        sv.addView(root);
        return sv;
    }

    private static void refreshKeywordList(Context ctx, float d, LinearLayout keywordList,
                                           ModuleConfig cfg, SharedPreferences prefs) {
        keywordList.removeAllViews();
        if (cfg.keywordRules == null) return;
        for (int i = 0; i < cfg.keywordRules.size(); i++) {
            final int idx = i;
            KeywordRule r = cfg.keywordRules.get(i);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(d, 2), 0, dp(d, 2));
            TextView info = new TextView(ctx);
            info.setText(r.keyword + " -> " + r.reply);
            info.setTextSize(13);
            info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(info);
            Button del = new Button(ctx); del.setText("删除");
            del.setOnClickListener(v -> {
                cfg.keywordRules.remove(idx); cfg.save(prefs);
                refreshKeywordList(ctx, d, keywordList, cfg, prefs);
            });
            row.addView(del);
            keywordList.addView(row);
        }
    }

    private static TextView sLabel(Context ctx, float d, String t) {
        TextView tv = new TextView(ctx); tv.setText(t); tv.setTextSize(18);
        tv.setPadding(0, dp(d, 12), 0, dp(d, 6)); tv.getPaint().setFakeBoldText(true); return tv;
    }

    private static LinearLayout switchRow(Context ctx, float d, String label, boolean checked,
                                          CompoundButton.OnCheckedChangeListener l) {
        return switchRow(ctx, d, label, checked, l, null);
    }

    private static LinearLayout switchRow(Context ctx, float d, String label, boolean checked,
                                          CompoundButton.OnCheckedChangeListener l,
                                          View.OnClickListener config) {
        LinearLayout row = new LinearLayout(ctx); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(d, 6), 0, dp(d, 6));
        TextView tv = new TextView(ctx); tv.setText(label); tv.setTextSize(14);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (config != null) {
            TextView btn = new TextView(ctx);
            btn.setText("[设置]"); btn.setTextSize(12); btn.setTextColor(AppColors.accent());
            btn.setPadding(dp(d, 6), 0, dp(d, 6), 0);
            btn.setOnClickListener(config);
            row.addView(btn);
        }
        Switch sw = new Switch(ctx); sw.setChecked(checked); sw.setOnCheckedChangeListener(l);
        row.addView(sw); return row;
    }

    private static LinearLayout editRow(Context ctx, float d, String label, String value, EditCallback cb) {
        LinearLayout row = new LinearLayout(ctx); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(d, 4), 0, dp(d, 4));
        TextView tv = new TextView(ctx); tv.setText(label); tv.setTextSize(14);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.35f));
        row.addView(tv);
        EditText et = new EditText(ctx); et.setText(value); et.setTextSize(14);
        et.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.65f));
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { cb.onChange(s.toString()); }
        });
        row.addView(et); return row;
    }

    private static LinearLayout infoRow(Context ctx, float d, String label, String value) {
        LinearLayout row = new LinearLayout(ctx); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(d, 4), 0, dp(d, 4));
        TextView t1 = new TextView(ctx); t1.setText(label + ": "); t1.setTextSize(14);
        t1.getPaint().setFakeBoldText(true); row.addView(t1);
        TextView t2 = new TextView(ctx); t2.setText(value); t2.setTextSize(14);
        row.addView(t2); return row;
    }

    private static int dp(float density, int dp) { return (int) (dp * density + 0.5f); }

    interface EditCallback { void onChange(String value); }
}
