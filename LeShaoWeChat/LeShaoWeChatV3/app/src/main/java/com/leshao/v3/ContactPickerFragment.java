package com.leshao.v3;

import android.graphics.drawable.GradientDrawable;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.leshao.v3.db.ContactRepository;
import com.leshao.v3.hook.ContactExport;
import com.leshao.v3.hook.ContactChangeLog;
import com.leshao.v3.model.Contact;
import com.leshao.v3.model.ModuleConfig;
import java.util.ArrayList;
import java.util.List;

public class ContactPickerFragment extends Fragment {

    private static final String TAG = "ContactPickerFragment";

    // 蜜桃苏打主题色（与 SettingsEntryHook 保持一致）
    static final int CLR_DARK_BG  = 0xFF221B17;
    static final int CLR_HEADING  = 0xFFFFF0E5;
    static final int CLR_BODY_TXT = 0xFFD4C5B2;
    static final int CLR_SUB_TEXT = 0xFFAA9988;
    static final int CLR_CARD_BG  = 0xC828221D;
    static final int CLR_CARD_BORDER = 0xFF48403A;
    static final int CLR_ACCENT   = 0xFFF0A080;
    private RecyclerView mRecyclerView;
    private ContactAdapter mAdapter;
    private List<Contact> mAllContacts;
    private int mTabMode = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 搜索框
        EditText searchBox = new EditText(getContext());
        searchBox.setHint("搜索联系人...");
        searchBox.setPadding(dp(8), dp(8), dp(8), dp(8));
        searchBox.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(searchBox);

        // 群聊/好友切换
        LinearLayout tabBar = new LinearLayout(getContext());
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setPadding(dp(8), dp(4), dp(8), dp(4));

        TextView tabAll = makeTab("全部", true);
        TextView tabFriend = makeTab("好友", false);
        TextView tabGroup = makeTab("群聊", false);

        mTabMode = 0;
        tabAll.setBackgroundColor(0xFF4CAF50);
        tabAll.setTextColor(0xFFFFFFFF);

        tabAll.setOnClickListener(v -> {
            mTabMode = 0;
            tabAll.setBackgroundColor(0xFF4CAF50);
            tabAll.setTextColor(0xFFFFFFFF);
            tabFriend.setBackgroundColor(0xFFE0E0E0);
            tabFriend.setTextColor(0xFF000000);
            tabGroup.setBackgroundColor(0xFFE0E0E0);
            tabGroup.setTextColor(0xFF000000);
            filterContacts(searchBox.getText().toString());
        });
        tabFriend.setOnClickListener(v -> {
            mTabMode = 1;
            tabAll.setBackgroundColor(0xFFE0E0E0);
            tabAll.setTextColor(0xFF000000);
            tabFriend.setBackgroundColor(0xFF4CAF50);
            tabFriend.setTextColor(0xFFFFFFFF);
            tabGroup.setBackgroundColor(0xFFE0E0E0);
            tabGroup.setTextColor(0xFF000000);
            filterContacts(searchBox.getText().toString());
        });
        tabGroup.setOnClickListener(v -> {
            mTabMode = 2;
            tabAll.setBackgroundColor(0xFFE0E0E0);
            tabAll.setTextColor(0xFF000000);
            tabFriend.setBackgroundColor(0xFFE0E0E0);
            tabFriend.setTextColor(0xFF000000);
            tabGroup.setBackgroundColor(0xFF4CAF50);
            tabGroup.setTextColor(0xFFFFFFFF);
            filterContacts(searchBox.getText().toString());
        });

        tabBar.addView(tabAll);
        tabBar.addView(tabFriend);
        tabBar.addView(tabGroup);
        root.addView(tabBar);

        // 联系人管理功能
        SharedPreferences prefs = ContextManager.getPrefs();
        ModuleConfig cfg = ModuleConfig.load(prefs);

        LinearLayout featRow = new LinearLayout(getContext());
        featRow.setOrientation(LinearLayout.HORIZONTAL);
        featRow.setPadding(dp(8), dp(4), dp(8), dp(4));
        featRow.addView(makeToggle("通讯录导出", cfg.contactExportEnabled, (v, on) -> {
            cfg.contactExportEnabled = on; cfg.save(prefs); ContactExport.setEnabled(on);
        }));
        featRow.addView(makeToggle("联系人变更日志", cfg.contactChangeLogEnabled, (v, on) -> {
            cfg.contactChangeLogEnabled = on; cfg.save(prefs); ContactChangeLog.setEnabled(on);
        }));
        root.addView(featRow);

        // RecyclerView
        mRecyclerView = new RecyclerView(getContext());
        mRecyclerView.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        mRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        root.addView(mRecyclerView);

        // 载入数据
        mAllContacts = ContactRepository.getAll();
        mAdapter = new ContactAdapter(filterByTab(mAllContacts, mTabMode));
        mRecyclerView.setAdapter(mAdapter);

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                filterContacts(s.toString());
            }
        });

        LogWriter.log(TAG, "loaded " + mAllContacts.size() + " items");

        return root;
    }

    private TextView makeTab(String text, boolean active) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(14);
        tv.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(4), 0);
        tv.setLayoutParams(lp);
        tv.setBackgroundColor(active ? 0xFF4CAF50 : 0xFFE0E0E0);
        tv.setTextColor(active ? 0xFFFFFFFF : 0xFF000000);
        return tv;
    }

    private LinearLayout makeToggle(String label, boolean checked, CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), 0, dp(12), 0);
        TextView tv = new TextView(getContext()); tv.setText(label); tv.setTextSize(12);
        tv.setTextColor(CLR_BODY_TXT);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(getContext()); sw.setChecked(checked); sw.setOnCheckedChangeListener(l);
        row.addView(sw); return row;
    }

    private void filterContacts(String query) {
        List<Contact> filtered = filterByTab(mAllContacts, mTabMode);
        if (!query.isEmpty()) {
            String q = query.toLowerCase();
            List<Contact> result = new ArrayList<>();
            for (Contact c : filtered) {
                if (c.displayName().toLowerCase().contains(q) || c.wxid.toLowerCase().contains(q)) {
                    result.add(c);
                }
            }
            filtered = result;
        }
        mAdapter.updateData(filtered);
    }

    private List<Contact> filterByTab(List<Contact> all, int mode) {
        List<Contact> result = new ArrayList<>();
        for (Contact c : all) {
            switch (mode) {
                case 0: result.add(c); break;
                case 1: if (!c.isGroup() && !isSkippableContact(c)) result.add(c); break;
                case 2: if (c.isGroup()) result.add(c); break;
            }
        }
        return result;
    }

    private static boolean isSkippableContact(Contact c) {
        if (c == null || c.wxid == null) return true;
        if (c.wxid.startsWith("gh_")) return true;
        if (c.wxid.startsWith("qqmail_")) return true;
        if (c.wxid.contains("@lbsroom")) return true;
        if (c.wxid.contains("@openim")) return true;
        if (c.wxid.contains("@im.chatroom")) return true;
        return false;
    }

    private int dp(int dp) {
        float d = getResources() != null ? getResources().getDisplayMetrics().density : 2.0f;
        return (int) (dp * d + 0.5f);
    }

    public static class ContactAdapter extends RecyclerView.Adapter<ContactAdapter.VH> {

        private List<Contact> mData;
        private OnItemClickListener mListener;

        public interface OnItemClickListener {
            void onItemClick(Contact contact, int position);
        }

        public ContactAdapter(List<Contact> data) { this.mData = data; }

        public void setOnItemClickListener(OnItemClickListener listener) { mListener = listener; }

        public void updateData(List<Contact> data) {
            this.mData = data;
            notifyDataSetChanged();
        }

        public List<Contact> getData() { return mData; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // 毛玻璃卡片包裹
            LinearLayout wrapper = new LinearLayout(parent.getContext());
            wrapper.setOrientation(LinearLayout.VERTICAL);
            wrapper.setPadding(dp(parent, 4), dp(parent, 3), dp(parent, 4), dp(parent, 3));

            LinearLayout item = new LinearLayout(parent.getContext());
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setPadding(dp(parent, 12), dp(parent, 8), dp(parent, 12), dp(parent, 8));

            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(CLR_CARD_BG);
            cardBg.setCornerRadius(dp(parent, 14));
            cardBg.setStroke(dp(parent, 1), CLR_CARD_BORDER);
            item.setBackground(cardBg);

            CheckBox cb = new CheckBox(parent.getContext());
            cb.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            item.addView(cb);

            LinearLayout texts = new LinearLayout(parent.getContext());
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(parent, 12), 0, 0, 0);

            TextView nameView = new TextView(parent.getContext());
            nameView.setTextSize(15);
            nameView.setTextColor(CLR_HEADING);
            TextView wxidView = new TextView(parent.getContext());
            wxidView.setTextSize(11);
            wxidView.setTextColor(CLR_SUB_TEXT);

            texts.addView(nameView);
            texts.addView(wxidView);
            item.addView(texts);

            item.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            wrapper.setTag(new Object[]{cb, nameView, wxidView});

            wrapper.addView(item);

            // 分割线
            View divider = new View(parent.getContext());
            divider.setBackgroundColor(CLR_CARD_BORDER);
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(parent, 1)));
            wrapper.addView(divider);

            return new VH(wrapper);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            Contact c = mData.get(position);
            Object[] tags = (Object[]) holder.itemView.getTag();
            CheckBox cb = (CheckBox) tags[0];
            TextView nameView = (TextView) tags[1];
            TextView wxidView = (TextView) tags[2];

            nameView.setText(c.displayName());
            wxidView.setText(c.detailInfo() + (c.isGroup() ? " [群聊]" : ""));
            cb.setOnCheckedChangeListener(null);
            cb.setChecked(false);
            cb.setOnCheckedChangeListener((btn, checked) -> { /* 后续扩展多选 */ });

            holder.itemView.setOnClickListener(v -> {
                if (mListener != null) {
                    mListener.onItemClick(c, position);
                }
            });
        }

        @Override
        public int getItemCount() { return mData.size(); }

        static class VH extends RecyclerView.ViewHolder {
            VH(View v) { super(v); }
        }

        private static int dp(ViewGroup parent, int dp) {
            float d = parent.getResources().getDisplayMetrics().density;
            return (int) (dp * d + 0.5f);
        }
    }
}
