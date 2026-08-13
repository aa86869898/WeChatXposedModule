package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
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
import com.leshao.v3.hook.PrivacyFeatures;
import com.leshao.v3.hook.LoginMonitor;
import com.leshao.v3.hook.HideContactFields;
import com.leshao.v3.hook.ConvPrivacy;
import com.leshao.v3.model.ModuleConfig;

public class PrivacyFragment extends Fragment {

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
        root.setPadding((int)(8 * d), (int)(16 * d), (int)(8 * d), (int)(16 * d));

        root.addView(sLabel(d, "隐私安全"));

        LinearLayout card = makeCard(d);
        card.addView(switchRow(d, "隐私保护 (截图检测/剪贴板/WebView/指纹锁定)",
                null, mCfg.privacyFeaturesEnabled, (v, on) -> {
            mCfg.privacyFeaturesEnabled = on; mCfg.save(mPrefs); PrivacyFeatures.setEnabled(on);
        }, null));
        card.addView(switchRow(d, "登录设备监控",
                null, mCfg.loginMonitorEnabled, (v, on) -> {
            mCfg.loginMonitorEnabled = on; mCfg.save(mPrefs); LoginMonitor.setEnabled(on);
        }, null));
        card.addView(switchRow(d, "隐藏联系人敏感字段",
                null, mCfg.hideContactFieldsEnabled, (v, on) -> {
            mCfg.hideContactFieldsEnabled = on; mCfg.save(mPrefs); HideContactFields.setEnabled(on);
        }, v -> ConfigPanels.showHideContactFields(act, mPrefs)));
        card.addView(switchRow(d, "会话隐私保护",
                null, mCfg.convPrivacyEnabled, (v, on) -> {
            mCfg.convPrivacyEnabled = on; mCfg.save(mPrefs); ConvPrivacy.setEnabled(on);
        }, v -> ConfigPanels.showConvPrivacy(act, mPrefs)));
        root.addView(card);

        ScrollView sv = new ScrollView(getContext());
        sv.addView(root);
        return sv;
    }

    private LinearLayout makeCard(float d) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int)(2 * d), (int)(2 * d), (int)(2 * d), (int)(2 * d));
        card.setBackgroundColor(AppColors.bg());
        return card;
    }

    private LinearLayout switchRow(float d, String title, String desc,
                                    boolean checked, CompoundButton.OnCheckedChangeListener l,
                                    View.OnClickListener config) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14 * d), (int)(12 * d), (int)(14 * d), (int)(12 * d));
        row.setBackgroundColor(AppColors.card());

        LinearLayout textCol = new LinearLayout(getContext());
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView tv = new TextView(getContext());
        tv.setText(title); tv.setTextSize(15);
        tv.setTextColor(AppColors.text1()); tv.setTypeface(null, Typeface.BOLD);
        textCol.addView(tv);

        if (desc != null && !desc.isEmpty()) {
            TextView dv = new TextView(getContext());
            dv.setText(desc); dv.setTextSize(12); dv.setTextColor(AppColors.text2());
            dv.setPadding(0, (int)(3 * d), 0, 0);
            textCol.addView(dv);
        }

        row.addView(textCol);

        if (config != null) {
            TextView btn = new TextView(getContext());
            btn.setText("[设置]"); btn.setTextSize(12); btn.setTextColor(AppColors.accent());
            btn.setPadding((int)(6 * d), 0, (int)(6 * d), 0);
            btn.setPaintFlags(btn.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            btn.setOnClickListener(config);
            row.addView(btn);
        }

        Switch sw = new Switch(getContext()); sw.setChecked(checked);
        sw.setOnCheckedChangeListener(l);
        row.addView(sw);
        return row;
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

    private static TextView sLabel(float d, String text) {
        TextView tv = new TextView(ContextManager.getAppContext());
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(AppColors.text2());
        tv.setPadding(0, 0, 0, (int)(8 * d));
        return tv;
    }
}
