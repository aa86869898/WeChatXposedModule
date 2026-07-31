package com.leshao.v3;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.leshao.v3.ui.TTSFragment;
import com.leshao.v3.ui.GroupGuardFragment;
import com.leshao.v3.ui.AIFragment;
import com.leshao.v3.ui.StatsFragment;
import com.leshao.v3.ui.ChatEnhanceFragment;
import com.leshao.v3.ui.SnsFragment;
import com.leshao.v3.ui.PrivacyFragment;
import java.lang.reflect.Method;

public class SettingsActivity extends FragmentActivity {

    private static final String TAG = "SettingsActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LogWriter.log(TAG, "onCreate");

        ViewPager2 viewPager = new ViewPager2(this);
        viewPager.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TabLayout tabLayout = new TabLayout(this);
        tabLayout.setTabGravity(TabLayout.GRAVITY_FILL);
        tabLayout.setTabMode(TabLayout.MODE_SCROLLABLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(buildStatusHeader());

        root.addView(tabLayout);
        root.addView(viewPager);

        setContentView(root);

        viewPager.setAdapter(new FragmentStateAdapter(this) {
            @Override public Fragment createFragment(int position) {
                switch (position) {
                    case 0: return new ContactPickerFragment();
                    case 1: return new SettingsFragment();
                    case 2: return new ChatEnhanceFragment();
                    case 3: return new SnsFragment();
                    case 4: return new PrivacyFragment();
                case 5: return new TTSFragment();
                case 6: return new GroupGuardFragment();
                case 7: return new AIFragment();
                case 8: return new StatsFragment();
                default: return new SettingsFragment();
            }
        }
        @Override public int getItemCount() { return 9; }
        });

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            switch (position) {
                case 0: tab.setText("联系人"); break;
                case 1: tab.setText("设置"); break;
                case 2: tab.setText("聊天增强"); break;
                case 3: tab.setText("朋友圈"); break;
                case 4: tab.setText("隐私安全"); break;
            case 5: tab.setText("播报"); break;
            case 6: tab.setText("群管"); break;
            case 7: tab.setText("AI"); break;
            case 8: tab.setText("统计"); break;
            }
        }).attach();
    }

    private ViewGroup buildStatusHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(40, 32, 40, 24);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF07C160);
        bg.setCornerRadius(0);
        header.setBackground(bg);

        // 标题
        TextView titleTv = new TextView(this);
        titleTv.setText("微信乐少助手 V3");
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(22);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setGravity(Gravity.CENTER);
        header.addView(titleTv);

        // 模块状态
        TextView statusTv = new TextView(this);
        statusTv.setTextColor(Color.argb(220, 255, 255, 255));
        statusTv.setTextSize(13);
        statusTv.setGravity(Gravity.CENTER);
        statusTv.setPadding(0, 8, 0, 0);
        statusTv.setText(getModuleStatus());
        header.addView(statusTv);

        return header;
    }

    private String getModuleStatus() {
        boolean xposedLoaded = false;
        try {
            Class<?> xb = Class.forName("de.robv.android.xposed.XposedBridge");
            xposedLoaded = true;
            Method logMethod = xb.getMethod("log", String.class);
            logMethod.invoke(null, TAG + " XposedBridge detected");
        } catch (Throwable ignored) {
        }

        if (!xposedLoaded) {
            return "Xposed未加载 | 请在Xposed框架中激活此模块";
        }

        try {
            Class<?> hm = Class.forName("com.leshao.v3.hook.HookManager");
            Method getStats = hm.getMethod("getStats");
            String stats = (String) getStats.invoke(null);
            return stats.replace("Hook统计: ", "");
        } catch (Throwable e) {
            return "Xposed已加载 | 获取Hook状态失败";
        }
    }
}
