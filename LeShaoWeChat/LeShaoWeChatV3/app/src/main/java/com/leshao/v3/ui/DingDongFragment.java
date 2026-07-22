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
import com.leshao.v3.model.ModuleConfig;

public class DingDongFragment extends Fragment {

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

        root.addView(sLabel("叮咚助手"));

        root.addView(switchRow("启用叮咚", mCfg.dianGeEnabled, (v, on) -> {
            mCfg.dianGeEnabled = on; mCfg.save(mPrefs);
        }));

        root.addView(sLabel("指令说明"));
        root.addView(infoRow("点歌 歌名", "搜索并返回歌曲信息"));
        root.addView(infoRow("天气 城市名", "查询天气"));
        root.addView(infoRow("笑话", "随机冷笑话"));
        root.addView(infoRow("金句", "每日一句"));
        root.addView(infoRow("视频 链接", "视频解析(开发中)"));

        ScrollView sv = new ScrollView(getContext());
        sv.addView(root);
        return sv;
    }

    private TextView sLabel(String text) {
        TextView tv = new TextView(getContext()); tv.setText(text); tv.setTextSize(18);
        tv.setPadding(0, dp(16), 0, dp(8)); tv.getPaint().setFakeBoldText(true);
        return tv;
    }

    private LinearLayout switchRow(String label, boolean checked, CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = new LinearLayout(getContext()); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView tv = new TextView(getContext()); tv.setText(label); tv.setTextSize(14);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(getContext()); sw.setChecked(checked); sw.setOnCheckedChangeListener(l);
        row.addView(sw); return row;
    }

    private LinearLayout infoRow(String cmd, String desc) {
        LinearLayout row = new LinearLayout(getContext()); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(4), 0, dp(4));
        TextView t1 = new TextView(getContext()); t1.setText(cmd); t1.setTextSize(13);
        t1.getPaint().setFakeBoldText(true);
        row.addView(t1);
        TextView t2 = new TextView(getContext()); t2.setText(" - " + desc); t2.setTextSize(13);
        t2.setTextColor(0xFF666666);
        row.addView(t2);
        return row;
    }

    private int dp(int dp) { return (int) (dp * getResources().getDisplayMetrics().density + 0.5f); }
    interface SelectorCallback { void onSelect(int index); }
}
