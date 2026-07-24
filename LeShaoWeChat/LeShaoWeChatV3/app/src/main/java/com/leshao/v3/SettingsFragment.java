package com.leshao.v3;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.leshao.v3.db.ContactRepository;
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
import com.leshao.v3.ui.ConfigPanels;
import com.leshao.v3.model.KeywordRule;
import com.leshao.v3.model.ModuleConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SettingsFragment extends Fragment {

    private ModuleConfig mCfg;
    private SharedPreferences mPrefs;
    private LinearLayout mKeywordList;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mPrefs = ContextManager.getPrefs();
        mCfg = ModuleConfig.load(mPrefs);
        final Activity act = getActivity();

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        root.addView(sLabel("通用设置"));

        root.addView(switchRow("总开关", mCfg.masterSwitch, (v, on) -> {
            mCfg.masterSwitch = on; mCfg.save(mPrefs);
        }));

        root.addView(sLabel("安全功能"));
        root.addView(switchRow("防撤回", mCfg.antiRecall, (v, on) -> {
            mCfg.antiRecall = on; mCfg.save(mPrefs); AntiRecallHook.setEnabled(on);
        }));
        root.addView(switchRow("抢红包", mCfg.redPacketGrab, (v, on) -> {
            mCfg.redPacketGrab = on; mCfg.save(mPrefs); RedPacketHook.setEnabled(on);
        }));
        root.addView(switchRow("反Xposed检测", mCfg.antiDetection, (v, on) -> {
            mCfg.antiDetection = on; mCfg.save(mPrefs); AntiDetectionHook.setEnabled(on);
        }));
        root.addView(switchRow("自动通过好友", mCfg.autoAcceptFriend, (v, on) -> {
            mCfg.autoAcceptFriend = on; mCfg.save(mPrefs); FriendRequestHook.setEnabled(on);
        }, v -> ConfigPanels.showFriendRequest(act, mPrefs)));
        root.addView(editRow("好友欢迎语", mCfg.autoAcceptFriendMsg, s -> {
            mCfg.autoAcceptFriendMsg = s; mCfg.save(mPrefs);
        }));

        root.addView(sLabel("会话与界面"));
        root.addView(switchRow("好友删除检测", mCfg.deleteDetectEnabled, (v, on) -> {
            mCfg.deleteDetectEnabled = on; mCfg.save(mPrefs); DeleteDetect.setEnabled(on);
        }));
        root.addView(switchRow("置顶增强", mCfg.stickyEnhanceEnabled, (v, on) -> {
            mCfg.stickyEnhanceEnabled = on; mCfg.save(mPrefs); StickyEnhance.setEnabled(on);
        }));
        root.addView(switchRow("未读角标显示", mCfg.unreadBadgeEnabled, (v, on) -> {
            mCfg.unreadBadgeEnabled = on; mCfg.save(mPrefs); UnreadBadge.setEnabled(on);
        }));
        root.addView(switchRow("底部Tab自定义", mCfg.tabCustomEnabled, (v, on) -> {
            mCfg.tabCustomEnabled = on; mCfg.save(mPrefs); TabCustom.setEnabled(on);
        }));

        root.addView(sLabel("通话与数据"));
        root.addView(switchRow("通话录音/自动接听", mCfg.callFeaturesEnabled, (v, on) -> {
            mCfg.callFeaturesEnabled = on; mCfg.save(mPrefs); CallFeatures.setEnabled(on);
        }));
        root.addView(switchRow("消息导出", mCfg.msgExportEnabled, (v, on) -> {
            mCfg.msgExportEnabled = on; mCfg.save(mPrefs); MsgExport.setEnabled(on);
        }));
        root.addView(switchRow("聊天记录备份", mCfg.chatBackupEnabled, (v, on) -> {
            mCfg.chatBackupEnabled = on; mCfg.save(mPrefs); ChatBackup.setEnabled(on);
        }));
        root.addView(switchRow("摇一摇自定义", mCfg.shakeCustomEnabled, (v, on) -> {
            mCfg.shakeCustomEnabled = on; mCfg.save(mPrefs); ShakeCustom.setEnabled(on);
        }));

        root.addView(sLabel("关键词回复"));
        mKeywordList = new LinearLayout(getContext());
        mKeywordList.setOrientation(LinearLayout.VERTICAL);
        refreshKeywordList();
        root.addView(mKeywordList);

        LinearLayout addRow = new LinearLayout(getContext());
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText kwInput = new EditText(getContext()); kwInput.setHint("关键词");
        kwInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        addRow.addView(kwInput);
        EditText rpInput = new EditText(getContext()); rpInput.setHint("回复内容");
        rpInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        addRow.addView(rpInput);
        Button addBtn = new Button(getContext()); addBtn.setText("添加");
        addBtn.setOnClickListener(v -> {
            String kw = kwInput.getText().toString().trim();
            String rp = rpInput.getText().toString().trim();
            if (!kw.isEmpty() && !rp.isEmpty()) {
                mCfg.keywordRules.add(new KeywordRule(kw, rp, true));
                mCfg.save(mPrefs);
                refreshKeywordList();
                kwInput.setText(""); rpInput.setText("");
            }
        });
        addRow.addView(addBtn);
        root.addView(addRow);

        root.addView(sLabel("敏感词 (每行一个)"));
        StringBuilder swText = new StringBuilder();
        for (String w : mCfg.sensitiveWords) { swText.append(w).append("\n"); }
        EditText swEdit = new EditText(getContext()); swEdit.setText(swText.toString());
        swEdit.setMinLines(3);
        swEdit.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                mCfg.sensitiveWords.clear();
                for (String line : s.toString().split("\n")) {
                    String t = line.trim();
                    if (!t.isEmpty()) mCfg.sensitiveWords.add(t);
                }
                mCfg.save(mPrefs);
            }
        });
        root.addView(swEdit);

        root.addView(sLabel("系统状态"));
        root.addView(infoRow("数据库", com.leshao.v3.db.DatabaseProvider.isReady() ? "已连接" : "未连接"));
        root.addView(infoRow("联系人", String.format(Locale.getDefault(),
            "好友 %d, 群聊 %d",
            ContactRepository.getFriends().size(), ContactRepository.getGroups().size())));

        ScrollView sv = new ScrollView(getContext());
        sv.addView(root);
        return sv;
    }

    private void refreshKeywordList() {
        mKeywordList.removeAllViews();
        if (mCfg.keywordRules == null) return;
        for (int i = 0; i < mCfg.keywordRules.size(); i++) {
            final int idx = i;
            KeywordRule r = mCfg.keywordRules.get(i);
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(2), 0, dp(2));
            TextView info = new TextView(getContext());
            info.setText(r.keyword + " -> " + r.reply);
            info.setTextSize(13);
            info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(info);
            Button del = new Button(getContext()); del.setText("删除");
            del.setOnClickListener(v -> {
                mCfg.keywordRules.remove(idx); mCfg.save(mPrefs); refreshKeywordList();
            });
            row.addView(del);
            mKeywordList.addView(row);
        }
    }

    private TextView sLabel(String t) {
        TextView tv = new TextView(getContext()); tv.setText(t); tv.setTextSize(18);
        tv.setPadding(0, dp(12), 0, dp(6)); tv.getPaint().setFakeBoldText(true); return tv;
    }

    private LinearLayout switchRow(String label, boolean checked, CompoundButton.OnCheckedChangeListener l) {
        return switchRow(label, checked, l, null);
    }

    private LinearLayout switchRow(String label, boolean checked, CompoundButton.OnCheckedChangeListener l,
                                    View.OnClickListener config) {
        LinearLayout row = new LinearLayout(getContext()); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView tv = new TextView(getContext()); tv.setText(label); tv.setTextSize(14);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (config != null) {
            TextView btn = new TextView(getContext());
            btn.setText("[设置]"); btn.setTextSize(12); btn.setTextColor(0xFF4A90D9);
            btn.setPadding(dp(6), 0, dp(6), 0);
            btn.setOnClickListener(config);
            row.addView(btn);
        }
        Switch sw = new Switch(getContext()); sw.setChecked(checked); sw.setOnCheckedChangeListener(l);
        row.addView(sw); return row;
    }

    private LinearLayout editRow(String label, String value, EditCallback cb) {
        LinearLayout row = new LinearLayout(getContext()); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView tv = new TextView(getContext()); tv.setText(label); tv.setTextSize(14);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.35f));
        row.addView(tv);
        EditText et = new EditText(getContext()); et.setText(value); et.setTextSize(14);
        et.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.65f));
        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { cb.onChange(s.toString()); }
        });
        row.addView(et); return row;
    }

    private LinearLayout infoRow(String label, String value) {
        LinearLayout row = new LinearLayout(getContext()); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView t1 = new TextView(getContext()); t1.setText(label + ": "); t1.setTextSize(14);
        t1.getPaint().setFakeBoldText(true); row.addView(t1);
        TextView t2 = new TextView(getContext()); t2.setText(value); t2.setTextSize(14);
        row.addView(t2); return row;
    }

    private int dp(int dp) {
        float d = getResources() != null ? getResources().getDisplayMetrics().density : 2.0f;
        return (int) (dp * d + 0.5f);
    }

    interface EditCallback { void onChange(String value); }
}
