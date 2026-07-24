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
import com.leshao.v3.hook.SnsFeatures;
import com.leshao.v3.model.ModuleConfig;

public class SnsFragment extends Fragment {

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

        root.addView(sLabel("朋友圈增强"));

        LinearLayout cardMain = makeCard(d);
        cardMain.addView(switchRow(d, "启用朋友圈增强",
                "关闭后所有子功能均不生效", mCfg.snsFeaturesEnabled, (v, on) -> {
            mCfg.snsFeaturesEnabled = on; mCfg.save(mPrefs); SnsFeatures.setEnabled(on);
        }, null));
        root.addView(cardMain);

        root.addView(spacerV(d, 12));
        root.addView(sLabel("功能开关"));

        LinearLayout cardSub = makeCard(d);
        cardSub.addView(subSwitch(d, "sns_ad_block", "去广告", "隐藏朋友圈中的广告内容", true));
        cardSub.addView(subSwitch(d, "sns_forward", "转发与复制", "支持转发到聊天和复制文字内容", true));
        cardSub.addView(subSwitch(d, "sns_fake_like", "假点赞", "强制点赞结果返回成功", false));
        cardSub.addView(subSwitch(d, "sns_time_edit", "时间修改", "修改朋友圈发布时间的偏移量", false,
                v -> ConfigPanels.showSnsTimeOffset(act, mPrefs)));
        cardSub.addView(subSwitch(d, "sns_video_quality", "视频画质增强", "解锁朋友圈视频的高画质播放", true));
        cardSub.addView(subSwitch(d, "sns_long_video", "长视频", "解除朋友圈视频时长限制", true));
        root.addView(cardSub);

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

    private LinearLayout subSwitch(float d, String key, String title, String desc, boolean defVal) {
        return subSwitch(d, key, title, desc, defVal, null);
    }

    private LinearLayout subSwitch(float d, String key, String title, String desc, boolean defVal,
                                    View.OnClickListener config) {
        boolean checked = mPrefs != null ? mPrefs.getBoolean(key, defVal) : defVal;
        return switchRow(d, title, desc, checked, (v, on) -> {
            if (mPrefs != null) mPrefs.edit().putBoolean(key, on).apply();
        }, config);
    }

    private TextView sLabel(String t) {
        float d = getResources() != null ? getResources().getDisplayMetrics().density : 2.0f;
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
