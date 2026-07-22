package com.leshao.v3;

import android.os.Bundle;
import android.view.ViewGroup;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.leshao.v3.ui.TTSFragment;
import com.leshao.v3.ui.DingDongFragment;
import com.leshao.v3.ui.GroupGuardFragment;
import com.leshao.v3.ui.AIFragment;
import com.leshao.v3.ui.SchedulerFragment;
import com.leshao.v3.ui.StatsFragment;

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

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(tabLayout);
        root.addView(viewPager);

        setContentView(root);

        viewPager.setAdapter(new FragmentStateAdapter(this) {
            @Override public Fragment createFragment(int position) {
                switch (position) {
                    case 0: return new ContactPickerFragment();
                    case 1: return new SettingsFragment();
                    case 2: return new TTSFragment();
                    case 3: return new DingDongFragment();
                    case 4: return new GroupGuardFragment();
                    case 5: return new AIFragment();
                    case 6: return new SchedulerFragment();
                    case 7: return new StatsFragment();
                    default: return new SettingsFragment();
                }
            }
            @Override public int getItemCount() { return 8; }
        });

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            switch (position) {
                case 0: tab.setText("联系人"); break;
                case 1: tab.setText("设置"); break;
                case 2: tab.setText("播报"); break;
                case 3: tab.setText("叮咚"); break;
                case 4: tab.setText("群管"); break;
                case 5: tab.setText("AI"); break;
                case 6: tab.setText("定时"); break;
                case 7: tab.setText("统计"); break;
            }
        }).attach();
    }
}
