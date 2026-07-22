package com.leshao.v3.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.leshao.v3.db.ContactRepository;
import com.leshao.v3.service.StatsCollector;
import java.util.List;
import java.util.Map;

public class StatsFragment extends Fragment {

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        root.addView(sLabel("数据统计"));

        // 联系人统计
        root.addView(infoRow("好友数", String.valueOf(ContactRepository.getFriends().size())));
        root.addView(infoRow("群聊数", String.valueOf(ContactRepository.getGroups().size())));

        // 撤回记录
        root.addView(sLabel("最近撤回记录"));
        List<String> recalls = StatsCollector.getRecallRecords();
        if (recalls.isEmpty()) {
            root.addView(tv("暂无撤回记录"));
        } else {
            int start = Math.max(0, recalls.size() - 20);
            for (int i = start; i < recalls.size(); i++) {
                root.addView(tv(recalls.get(i)));
            }
        }

        ScrollView sv = new ScrollView(getContext());
        sv.addView(root);
        return sv;
    }

    private TextView sLabel(String t) {
        TextView tv = new TextView(getContext()); tv.setText(t); tv.setTextSize(18);
        tv.setPadding(0, dp(16), 0, dp(8)); tv.getPaint().setFakeBoldText(true); return tv;
    }

    private LinearLayout infoRow(String label, String value) {
        LinearLayout row = new LinearLayout(getContext()); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView t1 = new TextView(getContext()); t1.setText(label + ": "); t1.setTextSize(14);
        t1.getPaint().setFakeBoldText(true); row.addView(t1);
        TextView t2 = new TextView(getContext()); t2.setText(value); t2.setTextSize(14);
        row.addView(t2); return row;
    }

    private TextView tv(String text) {
        TextView t = new TextView(getContext()); t.setText(text); t.setTextSize(13);
        t.setPadding(0, dp(2), 0, dp(2)); return t;
    }

    private int dp(int dp) { return (int) (dp * getResources().getDisplayMetrics().density + 0.5f); }
}
