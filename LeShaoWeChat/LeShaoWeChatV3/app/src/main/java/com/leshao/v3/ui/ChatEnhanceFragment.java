package com.leshao.v3.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.leshao.v3.ContextManager;
import com.leshao.v3.hook.TypingIndicator;
import com.leshao.v3.hook.ChatFooterEnhance;
import com.leshao.v3.hook.ChatUICustom;
import com.leshao.v3.hook.BatchMessage;
import com.leshao.v3.hook.ScheduledSend;
import com.leshao.v3.hook.AutoRemark;
import com.leshao.v3.hook.SearchEnhance;
import com.leshao.v3.hook.NotifyCustom;
import com.leshao.v3.model.ModuleConfig;

public class ChatEnhanceFragment extends Fragment {

    private ModuleConfig mCfg;
    private SharedPreferences mPrefs;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mPrefs = ContextManager.getPrefs();
        mCfg = ModuleConfig.load(mPrefs);

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        root.addView(sLabel("聊天增强"));

        root.addView(switchRow("对方正在输入提示", mCfg.typingIndicatorEnabled, (v, on) -> {
            mCfg.typingIndicatorEnabled = on; mCfg.save(mPrefs); TypingIndicator.setEnabled(on);
        }));
        root.addView(switchRow("输入框增强", mCfg.chatFooterEnhanceEnabled, (v, on) -> {
            mCfg.chatFooterEnhanceEnabled = on; mCfg.save(mPrefs); ChatFooterEnhance.setEnabled(on);
        }));
        root.addView(switchRow("聊天界面自定义", mCfg.chatUICustomEnabled, (v, on) -> {
            mCfg.chatUICustomEnabled = on; mCfg.save(mPrefs); ChatUICustom.setEnabled(on);
        }));
        root.addView(switchRow("批量消息操作", mCfg.batchMessageEnabled, (v, on) -> {
            mCfg.batchMessageEnabled = on; mCfg.save(mPrefs); BatchMessage.setEnabled(on);
        }));
        root.addView(switchRow("定时发送消息", mCfg.scheduledSendEnabled, (v, on) -> {
            mCfg.scheduledSendEnabled = on; mCfg.save(mPrefs); ScheduledSend.setEnabled(on);
        }));
        root.addView(switchRow("自动备注好友", mCfg.autoRemarkEnabled, (v, on) -> {
            mCfg.autoRemarkEnabled = on; mCfg.save(mPrefs); AutoRemark.setEnabled(on);
        }));
        root.addView(switchRow("全文搜索增强", mCfg.searchEnhanceEnabled, (v, on) -> {
            mCfg.searchEnhanceEnabled = on; mCfg.save(mPrefs); SearchEnhance.setEnabled(on);
        }));

        root.addView(sLabel("通知增强"));
        root.addView(switchRow("通知快捷回复/头像/优先级", mCfg.notifyCustomEnabled, (v, on) -> {
            mCfg.notifyCustomEnabled = on; mCfg.save(mPrefs); NotifyCustom.setEnabled(on);
        }));

        ScrollView sv = new ScrollView(getContext());
        sv.addView(root);
        return sv;
    }

    private TextView sLabel(String t) {
        TextView tv = new TextView(getContext()); tv.setText(t); tv.setTextSize(18);
        tv.setPadding(0, dp(12), 0, dp(6)); tv.getPaint().setFakeBoldText(true); return tv;
    }

    private LinearLayout switchRow(String label, boolean checked, CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = new LinearLayout(getContext()); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView tv = new TextView(getContext()); tv.setText(label); tv.setTextSize(14);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(getContext()); sw.setChecked(checked); sw.setOnCheckedChangeListener(l);
        row.addView(sw); return row;
    }

    private int dp(int dp) {
        float d = getResources() != null ? getResources().getDisplayMetrics().density : 2.0f;
        return (int) (dp * d + 0.5f);
    }
}
