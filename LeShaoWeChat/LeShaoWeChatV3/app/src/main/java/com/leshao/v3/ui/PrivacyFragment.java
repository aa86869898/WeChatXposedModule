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

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        root.addView(sLabel("隐私安全"));
        root.addView(switchRow("隐私保护 (截图检测/剪贴板/WebView/指纹锁定)", mCfg.privacyFeaturesEnabled, (v, on) -> {
            mCfg.privacyFeaturesEnabled = on; mCfg.save(mPrefs); PrivacyFeatures.setEnabled(on);
        }));
        root.addView(switchRow("登录设备监控", mCfg.loginMonitorEnabled, (v, on) -> {
            mCfg.loginMonitorEnabled = on; mCfg.save(mPrefs); LoginMonitor.setEnabled(on);
        }));
        root.addView(switchRow("隐藏联系人敏感字段", mCfg.hideContactFieldsEnabled, (v, on) -> {
            mCfg.hideContactFieldsEnabled = on; mCfg.save(mPrefs); HideContactFields.setEnabled(on);
        }));
        root.addView(switchRow("会话隐私保护", mCfg.convPrivacyEnabled, (v, on) -> {
            mCfg.convPrivacyEnabled = on; mCfg.save(mPrefs); ConvPrivacy.setEnabled(on);
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
