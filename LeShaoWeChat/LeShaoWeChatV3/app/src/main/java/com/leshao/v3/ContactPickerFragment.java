package com.leshao.v3;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.leshao.v3.model.ContactCard;
import com.leshao.v3.model.ContactCard.Category;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.AvatarHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ContactPickerFragment extends Fragment {

    private static final int TAB_ALL = 0;
    private static final int TAB_FRIEND = 1;
    private static final int TAB_GROUP = 2;

    private int currentTab = TAB_ALL;
    private String searchQuery = "";
    private List<ContactCard> allContacts = new ArrayList<>();
    private List<ContactCard> displayList = new ArrayList<>();
    private ContactAdapter adapter;
    private TextView tabAll, tabFriend, tabGroup;
    private TextView countText;
    private EditText searchInput;
    private Handler handler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return buildUi(container);
    }

    @Override
    public void onResume() {
        super.onResume();
        loadData();
    }

    private void loadData() {
        ContactRepository.loadAsync(() -> {
            if (getActivity() == null) return;
            handler.post(() -> {
                allContacts = new ArrayList<>(ContactRepository.getAll());
                if (allContacts.isEmpty()) {
                    allContacts = new ArrayList<>(ContactRepository.getFriends());
                }
                applyFilter();
            });
        });
    }

    private View buildUi(ViewGroup parent) {
        int p16 = dp(16);
        int p12 = dp(12);
        int p8 = dp(8);

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.BG_GRADIENT_START);

        // Search bar
        LinearLayout searchBar = new LinearLayout(getContext());
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setPadding(p16, p12, p16, p12);
        searchBar.setBackgroundColor(AppColors.BG_GRADIENT_START);

        searchInput = new EditText(getContext());
        searchInput.setHint("\u641c\u7d22\u8054\u7cfb\u4eba");
        searchInput.setHintTextColor(AppColors.TEXT_NOTE);
        searchInput.setTextColor(AppColors.TEXT_TITLE);
        searchInput.setTextSize(14);
        searchInput.setBackground(roundBg(AppColors.INPUT_BG, dp(22)));
        searchInput.setPadding(dp(40), dp(10), dp(16), dp(10));
        searchInput.setSingleLine(true);
        searchInput.setCompoundDrawablesWithIntrinsicBounds(
                0, 0, android.R.drawable.ic_menu_search, 0);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                searchQuery = s.toString().trim();
                applyFilter();
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(42), 1);
        slp.rightMargin = p8;
        searchBar.addView(searchInput, slp);

        // Count
        countText = new TextView(getContext());
        countText.setTextSize(11);
        countText.setTextColor(AppColors.TEXT_NOTE);
        countText.setPadding(0, 0, 0, dp(4));
        root.addView(searchBar);
        root.addView(countText);

        // Tab buttons
        LinearLayout tabs = new LinearLayout(getContext());
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(p16, 0, p16, p12);
        tabs.setBackgroundColor(AppColors.BG_GRADIENT_START);

        tabAll = makeTab(tabs, "\u5168\u90e8", TAB_ALL);
        tabFriend = makeTab(tabs, "\u597d\u53cb", TAB_FRIEND);
        tabGroup = makeTab(tabs, "\u7fa4\u804a", TAB_GROUP);
        root.addView(tabs);

        // List
        RecyclerView recycler = new RecyclerView(getContext());
        recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        recycler.setBackgroundColor(Color.WHITE);
        adapter = new ContactAdapter();
        recycler.setAdapter(adapter);
        root.addView(recycler, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        return root;
    }

    private TextView makeTab(LinearLayout parent, String label, int tab) {
        int p6 = dp(6);
        int p14 = dp(14);
        TextView tv = new TextView(getContext());
        tv.setText(label);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(p14, p6, p14, p6);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(32), 1);
        lp.setMargins(0, 0, p6, 0);
        tv.setOnClickListener(v -> {
            currentTab = tab;
            updateTabStyles();
            applyFilter();
        });
        parent.addView(tv, lp);
        return tv;
    }

    private void updateTabStyles() {
        tabAll.setBackground(roundBg(currentTab == TAB_ALL ? AppColors.ACCENT : AppColors.DIVIDER, dp(16)));
        tabAll.setTextColor(currentTab == TAB_ALL ? AppColors.WHITE_TEXT : AppColors.TEXT_BODY);
        tabFriend.setBackground(roundBg(currentTab == TAB_FRIEND ? AppColors.ACCENT : AppColors.DIVIDER, dp(16)));
        tabFriend.setTextColor(currentTab == TAB_FRIEND ? AppColors.WHITE_TEXT : AppColors.TEXT_BODY);
        tabGroup.setBackground(roundBg(currentTab == TAB_GROUP ? AppColors.ACCENT : AppColors.DIVIDER, dp(16)));
        tabGroup.setTextColor(currentTab == TAB_GROUP ? AppColors.WHITE_TEXT : AppColors.TEXT_BODY);
    }

    private void applyFilter() {
        List<ContactCard> source;
        switch (currentTab) {
            case TAB_FRIEND: source = filterByCategory(allContacts, Category.FRIEND); break;
            case TAB_GROUP:  source = filterByCategory(allContacts, Category.GROUP); break;
            default:         source = new ArrayList<>(allContacts); break;
        }

        if (searchQuery.isEmpty()) {
            Collections.sort(source, Comparator.comparing(c -> c.sortKey()));
            displayList = source;
        } else {
            displayList = new ArrayList<>();
            String q = searchQuery.toLowerCase();
            for (ContactCard c : source) {
                if (matchesSearch(c, q)) displayList.add(c);
            }
        }

        countText.setText("\u5171 " + displayList.size() + " \u4eba");
        adapter.notifyDataSetChanged();
        updateTabStyles();
    }

    private List<ContactCard> filterByCategory(List<ContactCard> list, Category cat) {
        List<ContactCard> r = new ArrayList<>();
        for (ContactCard c : list) {
            if (c.category == cat) r.add(c);
        }
        return r;
    }

    private boolean matchesSearch(ContactCard c, String q) {
        if (c.displayName().toLowerCase().contains(q)) return true;
        if (c.username != null && c.username.toLowerCase().contains(q)) return true;
        if (c.alias != null && c.alias.toLowerCase().contains(q)) return true;
        if (c.pyInitial != null && c.pyInitial.toLowerCase().contains(q)) return true;
        if (c.quanPin != null && c.quanPin.toLowerCase().contains(q)) return true;
        return false;
    }

    private class ContactAdapter extends RecyclerView.Adapter<ContactAdapter.VH> {

        @Override
        public int getItemCount() { return displayList.size(); }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int p12 = dp(12);
            int p8 = dp(8);

            LinearLayout item = new LinearLayout(getContext());
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setPadding(p12, p8, p12, p8);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setBackgroundColor(Color.WHITE);

            ImageView avatar = new ImageView(getContext());
            avatar.setId(View.generateViewId());
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(44), dp(44));
            alp.rightMargin = p12;
            item.addView(avatar, alp);

            LinearLayout textCol = new LinearLayout(getContext());
            textCol.setOrientation(LinearLayout.VERTICAL);

            TextView name = new TextView(getContext());
            name.setId(View.generateViewId());
            name.setTextSize(15);
            name.setTextColor(AppColors.TEXT_TITLE);
            textCol.addView(name);

            TextView sub = new TextView(getContext());
            sub.setId(View.generateViewId());
            sub.setTextSize(12);
            sub.setTextColor(AppColors.TEXT_NOTE);
            sub.setPadding(0, dp(2), 0, 0);
            textCol.addView(sub);

            item.addView(textCol, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            View divider = new View(getContext());
            divider.setBackgroundColor(AppColors.DIVIDER);
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));

            FrameLayout wrapper = new FrameLayout(getContext());
            wrapper.addView(item);
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            divider.setLayoutParams(new FrameLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1, Gravity.BOTTOM));
            wrapper.addView(divider);

            return new VH(wrapper, avatar, name, sub);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            ContactCard c = displayList.get(position);
            holder.name.setText(c.displayName());

            String subInfo;
            if (c.conRemark != null && !c.conRemark.isEmpty()
                    && c.conRemark.equals(c.displayName())) {
                subInfo = "\u6635\u79f0: " + (c.nickname != null ? c.nickname : "");
            } else {
                subInfo = (c.alias != null && !c.alias.isEmpty()) ? "\u5fae\u4fe1\u53f7: " + c.alias : c.username;
            }
            holder.sub.setText(subInfo);

            int size = dp(44);
            String letter = c.sortKey().substring(0, 1).toUpperCase();
            Bitmap fallback = createLetterAvatar(letter, size);
            AvatarHelper.loadAvatarAsync(holder.avatar, c.username, size, fallback);
        }

        class VH extends RecyclerView.ViewHolder {
            ImageView avatar;
            TextView name, sub;
            VH(View itemView, ImageView avatar, TextView name, TextView sub) {
                super(itemView);
                this.avatar = avatar;
                this.name = name;
                this.sub = sub;
            }
        }
    }

    private Bitmap createLetterAvatar(String letter, int size) {
        Paint paint = new Paint();
        paint.setColor(AppColors.WHITE_TEXT);
        paint.setTextSize(size * 0.45f);
        paint.setAntiAlias(true);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);

        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bm);
        Paint bgPaint = new Paint();
        bgPaint.setColor(AppColors.TEXT_NOTE);
        canvas.drawRoundRect(0, 0, size, size, size / 2f, size / 2f, bgPaint);
        float y = size / 2f - (paint.descent() + paint.ascent()) / 2f;
        canvas.drawText(letter, size / 2f, y, paint);
        return bm;
    }

    private GradientDrawable roundBg(int color, int radius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radius);
        return gd;
    }

    private int dp(int px) { return (int) (px * getResources().getDisplayMetrics().density + 0.5f); }
}
