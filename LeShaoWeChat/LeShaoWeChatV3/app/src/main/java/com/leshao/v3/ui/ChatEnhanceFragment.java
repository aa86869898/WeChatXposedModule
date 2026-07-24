package com.leshao.v3.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
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
        float d = getResources() != null ? getResources().getDisplayMetrics().density : 2.0f;
        final Activity act = getActivity();

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int)(16 * d), (int)(16 * d), (int)(16 * d), (int)(16 * d));

        root.addView(sLabel(d, "聊天增强"));

        LinearLayout card = makeCard(d);
        card.addView(switchRow(d, "对方正在输入提示",
                null, mCfg.typingIndicatorEnabled, (v, on) -> {
            mCfg.typingIndicatorEnabled = on; mCfg.save(mPrefs); TypingIndicator.setEnabled(on);
        }, v -> ConfigPanels.showTypingIndicator(act, mPrefs)));
        card.addView(switchRow(d, "输入框增强",
                "突破字数限制/输入框自适应", mCfg.chatFooterEnhanceEnabled, (v, on) -> {
            mCfg.chatFooterEnhanceEnabled = on; mCfg.save(mPrefs); ChatFooterEnhance.setEnabled(on);
        }, v -> ConfigPanels.showChatFooterEnhance(act, mPrefs)));
        card.addView(switchRow(d, "聊天界面自定义",
                "背景/气泡颜色/圆角/昵称", mCfg.chatUICustomEnabled, (v, on) -> {
            mCfg.chatUICustomEnabled = on; mCfg.save(mPrefs); ChatUICustom.setEnabled(on);
        }, v -> ConfigPanels.showChatUICustom(act, mPrefs)));
        card.addView(switchRow(d, "批量消息操作",
                "突破9条限制/全选反选", mCfg.batchMessageEnabled, (v, on) -> {
            mCfg.batchMessageEnabled = on; mCfg.save(mPrefs); BatchMessage.setEnabled(on);
        }, v -> ConfigPanels.showBatchMessage(act, mPrefs)));
        card.addView(switchRow(d, "定时发送消息",
                null, mCfg.scheduledSendEnabled, (v, on) -> {
            mCfg.scheduledSendEnabled = on; mCfg.save(mPrefs); ScheduledSend.setEnabled(on);
        }, v -> ConfigPanels.showScheduledSend(act, mPrefs)));
        card.addView(switchRow(d, "自动备注好友",
                "从群昵称/名片自动填充", mCfg.autoRemarkEnabled, (v, on) -> {
            mCfg.autoRemarkEnabled = on; mCfg.save(mPrefs); AutoRemark.setEnabled(on);
        }, v -> ConfigPanels.showAutoRemark(act, mPrefs)));
        card.addView(switchRow(d, "全文搜索增强",
                null, mCfg.searchEnhanceEnabled, (v, on) -> {
            mCfg.searchEnhanceEnabled = on; mCfg.save(mPrefs); SearchEnhance.setEnabled(on);
        }, v -> ConfigPanels.showSearchEnhance(act, mPrefs)));
        root.addView(card);

        root.addView(spacerV(d, 12));
        root.addView(sLabel(d, "通知增强"));

        LinearLayout card2 = makeCard(d);
        card2.addView(switchRow(d, "通知自定义",
                "快捷回复/头像/优先级", mCfg.notifyCustomEnabled, (v, on) -> {
            mCfg.notifyCustomEnabled = on; mCfg.save(mPrefs); NotifyCustom.setEnabled(on);
        }, v -> ConfigPanels.showNotifyCustom(act, mPrefs)));
        root.addView(card2);

        ScrollView sv = new ScrollView(getContext());
        sv.addView(root);
        return sv;
    }

    private LinearLayout makeCard(float d) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int)(2 * d), (int)(2 * d), (int)(2 * d), (int)(2 * d));
        card.setBackgroundColor(0xFFF5F5F5);
        return card;
    }

    private LinearLayout switchRow(float d, String title, String desc,
                                    boolean checked, CompoundButton.OnCheckedChangeListener l,
                                    View.OnClickListener config) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14 * d), (int)(12 * d), (int)(14 * d), (int)(12 * d));
        row.setBackgroundColor(0xFFFFFFFF);

        LinearLayout textCol = new LinearLayout(getContext());
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView tv = new TextView(getContext());
        tv.setText(title); tv.setTextSize(15);
        tv.setTextColor(0xFF1A1A1A); tv.setTypeface(null, Typeface.BOLD);
        textCol.addView(tv);

        if (desc != null && !desc.isEmpty()) {
            TextView dv = new TextView(getContext());
            dv.setText(desc); dv.setTextSize(12); dv.setTextColor(0xFF999999);
            dv.setPadding(0, (int)(3 * d), 0, 0);
            textCol.addView(dv);
        }

        row.addView(textCol);

        if (config != null) {
            TextView btn = new TextView(getContext());
            btn.setText("[设置]"); btn.setTextSize(12); btn.setTextColor(0xFF4A90D9);
            btn.setPadding((int)(6 * d), 0, (int)(6 * d), 0);
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
        tv.setText(t); tv.setTextSize(13); tv.setTextColor(0xFF999999);
        tv.setPadding(0, 0, 0, (int)(8 * d));
        return tv;
    }

    private View spacerV(float d, int dp) {
        View v = new View(getContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int)(dp * d)));
        return v;
    }
}
