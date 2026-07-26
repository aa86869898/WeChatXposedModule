package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.leshao.v3.db.ContactRepository;
import com.leshao.v3.model.Contact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ContactPickerDialog {

    public static final int MODE_FRIEND = 0;
    public static final int MODE_GROUP = 1;
    public static final int GENDER_ALL = 0;
    public static final int GENDER_MALE = 1;
    public static final int GENDER_FEMALE = 2;

    public interface OnContactsSelected {
        void onSelected(Set<String> wxids, String display);
    }

    public static void show(Activity parentAct, String currentIds, final int initialMode,
                            final OnContactsSelected callback) {
        try {
            ContactRepository.forceReload();
            showInner(parentAct, currentIds, initialMode, callback);
        } catch (Throwable t) {
            com.leshao.v3.LogWriter.log("ContactPicker", "show CRASH: "
                + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void showInner(Activity parentAct, String currentIds, final int initialMode,
                                   final OnContactsSelected callback) {
        final Context ctx = parentAct;
        final float d = ctx.getResources().getDisplayMetrics().density;
        final Handler handler = new Handler(Looper.getMainLooper());

        final Set<String> selected = new LinkedHashSet<>();
        if (currentIds != null && !currentIds.isEmpty()) {
            for (String id : currentIds.split(",")) {
                String t = id.trim();
                if (!t.isEmpty()) selected.add(t);
            }
        }

        final List<Contact>[] holder = new List[]{null, null};
        int fCount, gCount;
        try {
            List<Contact> friends = ContactRepository.getFriends();
            List<Contact> groups = ContactRepository.getGroups();
            holder[0] = friends != null ? friends : Collections.<Contact>emptyList();
            holder[1] = groups != null ? groups : Collections.<Contact>emptyList();
            fCount = holder[0].size();
            gCount = holder[1].size();
        } catch (Throwable t) {
            holder[0] = Collections.emptyList();
            holder[1] = Collections.emptyList();
            fCount = gCount = 0;
        }
        final List<Contact> allFriends = holder[0];
        final List<Contact> allGroups = holder[1];

        final List<Contact> filteredList = new ArrayList<>();
        final int[] currentTab = { initialMode };
        final String[] currentQuery = { "" };
        final int[] currentGender = { GENDER_ALL };

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.bg());

        EditText searchBox = new EditText(ctx);
        searchBox.setHint("搜索" + (currentTab[0] == MODE_FRIEND ? "好友" : "群聊"));
        searchBox.setHintTextColor(AppColors.text2());
        searchBox.setTextSize(14);
        searchBox.setTextColor(AppColors.text1());
        searchBox.setSingleLine(true);
        searchBox.setPadding((int)(14 * d), (int)(10 * d), (int)(14 * d), (int)(10 * d));
        searchBox.setBackgroundColor(AppColors.whiteCard());
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(-1, -2);
        sblp.setMargins((int)(12 * d), (int)(10 * d), (int)(12 * d), 0);
        searchBox.setLayoutParams(sblp);
        root.addView(searchBox);

        LinearLayout tabBar = new LinearLayout(ctx);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tblp = new LinearLayout.LayoutParams(-1, -2);
        tblp.setMargins((int)(12 * d), (int)(8 * d), (int)(12 * d), 0);
        tabBar.setLayoutParams(tblp);

        final TextView[] tabViews = new TextView[2];
        final View[] tabIndicators = new View[2];
        final LinearLayout[] tabItems = new LinearLayout[2];
        for (int t = 0; t < 2; t++) {
            boolean active = (t == currentTab[0]);
            LinearLayout tabItem = new LinearLayout(ctx);
            tabItem.setOrientation(LinearLayout.VERTICAL);
            tabItem.setGravity(Gravity.CENTER);
            tabItem.setClickable(true);
            tabItem.setPadding((int)(20 * d), (int)(8 * d), (int)(20 * d), (int)(2 * d));
            tabItem.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

            TextView tabTv = new TextView(ctx);
            tabTv.setText(t == 0 ? "好友 (" + fCount + ")" : "群聊 (" + gCount + ")");
            tabTv.setTextSize(14);
            tabTv.setTextColor(active ? AppColors.accent() : AppColors.text2());
            tabTv.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            tabItem.addView(tabTv);
            tabViews[t] = tabTv;

            View indicator = new View(ctx);
            indicator.setLayoutParams(new LinearLayout.LayoutParams((int)(24 * d), (int)(2.5f * d)));
            indicator.setBackgroundColor(active ? AppColors.accent() : android.graphics.Color.TRANSPARENT);
            ((LinearLayout.LayoutParams) indicator.getLayoutParams()).setMargins(0, (int)(4 * d), 0, 0);
            tabItem.addView(indicator);
            tabIndicators[t] = indicator;

            tabItems[t] = tabItem;
            tabBar.addView(tabItem);
        }
        root.addView(tabBar);

        LinearLayout genderBar = new LinearLayout(ctx);
        genderBar.setOrientation(LinearLayout.HORIZONTAL);
        genderBar.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams gblp = new LinearLayout.LayoutParams(-1, -2);
        gblp.setMargins((int)(12 * d), (int)(4 * d), (int)(12 * d), 0);
        genderBar.setLayoutParams(gblp);

        final TextView[] genderViews = new TextView[3];
        for (int g = 0; g < 3; g++) {
            boolean active = (g == currentGender[0]);
            TextView gv = new TextView(ctx);
            gv.setText(g == 0 ? "全部" : (g == 1 ? "男" : "女"));
            gv.setTextSize(12);
            gv.setTextColor(active ? AppColors.accent() : AppColors.text2());
            gv.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            GradientDrawable gbg = new GradientDrawable();
            gbg.setCornerRadius(4 * d);
            gbg.setColor(active ? (AppColors.accent() & 0x00FFFFFF | 0x18000000) : android.graphics.Color.TRANSPARENT);
            gv.setBackground(gbg);
            gv.setPadding((int)(12 * d), (int)(4 * d), (int)(12 * d), (int)(4 * d));
            LinearLayout.LayoutParams gvlp = new LinearLayout.LayoutParams((int)(48 * d), -2);
            gv.setGravity(Gravity.CENTER);
            gv.setLayoutParams(gvlp);
            genderViews[g] = gv;
            genderBar.addView(gv);
        }
        root.addView(genderBar);

        View sep = new View(ctx);
        sep.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        sep.setBackgroundColor(AppColors.divider());
        root.addView(sep);

        int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
        int dialogH = (int)(screenH * 0.78);
        int fixedH = (int)((42 + 1 + 44 + 30 + 50) * d);
        int scrollH = Math.max(dialogH - fixedH, (int)(200 * d));

        RecyclerView recyclerView = new RecyclerView(ctx);
        recyclerView.setLayoutManager(new LinearLayoutManager(ctx));
        recyclerView.setLayoutParams(new LinearLayout.LayoutParams(-1, scrollH));

        final ContactAdapter adapter = new ContactAdapter(ctx, d, filteredList, selected);
        recyclerView.setAdapter(adapter);

        root.addView(recyclerView);

        final LinearLayout bottomBar = new LinearLayout(ctx);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding((int)(12 * d), (int)(8 * d), (int)(12 * d), (int)(8 * d));
        bottomBar.setBackgroundColor(AppColors.card());
        bottomBar.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

        final TextView selectAllBtn = new TextView(ctx);
        selectAllBtn.setText("全部勾选");
        selectAllBtn.setTextSize(13);
        selectAllBtn.setTextColor(AppColors.accent());
        selectAllBtn.setPadding((int)(10 * d), (int)(6 * d), (int)(10 * d), (int)(6 * d));
        selectAllBtn.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        selectAllBtn.setOnClickListener(v -> {
            boolean allSelected = filteredList.size() > 0;
            for (Contact c : filteredList) {
                if (!selected.contains(c.wxid)) { allSelected = false; break; }
            }
            if (allSelected) { selected.clear(); selectAllBtn.setText("全部勾选"); }
            else { for (Contact c : filteredList) selected.add(c.wxid); selectAllBtn.setText("取消勾选"); }
            updateDoneBtn(bottomBar, selected);
            rebuildList(ctx, d, filteredList, allFriends, allGroups, currentTab, currentQuery, currentGender,
                selected, adapter, selectAllBtn, bottomBar, handler);
        });
        bottomBar.addView(selectAllBtn);

        final TextView doneBtn = new TextView(ctx);
        doneBtn.setText("确定(" + selected.size() + ")");
        doneBtn.setTextSize(14);
        doneBtn.setTextColor(AppColors.whiteCard());
        doneBtn.setGravity(Gravity.CENTER);
        doneBtn.setPadding((int)(20 * d), (int)(8 * d), (int)(20 * d), (int)(8 * d));
        GradientDrawable dg = new GradientDrawable();
        dg.setCornerRadius(6 * d);
        dg.setColor(AppColors.accent());
        doneBtn.setBackground(dg);
        bottomBar.addView(doneBtn);

        root.addView(bottomBar);

        adapter.setBottomBar(bottomBar);
        adapter.setSelectAllBtn(selectAllBtn);

        for (int g = 0; g < 3; g++) {
            final int gi = g;
            genderViews[g].setOnClickListener(v -> {
                if (gi == currentGender[0]) return;
                currentGender[0] = gi;
                for (int i = 0; i < 3; i++) {
                    boolean act = (i == gi);
                    genderViews[i].setTextColor(act ? AppColors.accent() : AppColors.text2());
                    genderViews[i].setTypeface(null, act ? Typeface.BOLD : Typeface.NORMAL);
                    GradientDrawable bg = new GradientDrawable();
                    bg.setCornerRadius(4 * d);
                    bg.setColor(act ? (AppColors.accent() & 0x00FFFFFF | 0x18000000) : android.graphics.Color.TRANSPARENT);
                    genderViews[i].setBackground(bg);
                }
                rebuildList(ctx, d, filteredList, allFriends, allGroups, currentTab, currentQuery, currentGender,
                    selected, adapter, selectAllBtn, bottomBar, handler);
            });
        }

        for (int t = 0; t < 2; t++) {
            final int ti = t;
            tabItems[t].setOnClickListener(v -> {
                if (ti == currentTab[0]) return;
                currentTab[0] = ti;
                currentQuery[0] = "";
                searchBox.setText("");
                searchBox.setHint("搜索" + (ti == MODE_FRIEND ? "好友" : "群聊"));
                for (int i = 0; i < 2; i++) {
                    tabViews[i].setTextColor(i == ti ? AppColors.accent() : AppColors.text2());
                    tabViews[i].setTypeface(null, i == ti ? Typeface.BOLD : Typeface.NORMAL);
                    tabIndicators[i].setBackgroundColor(i == ti ? AppColors.accent() : android.graphics.Color.TRANSPARENT);
                }
                rebuildList(ctx, d, filteredList, allFriends, allGroups, currentTab, currentQuery, currentGender,
                    selected, adapter, selectAllBtn, bottomBar, handler);
            });
        }

        final AlertDialog[] dlgRef = new AlertDialog[1];

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                String q = s.toString().trim();
                if (!q.equals(currentQuery[0])) {
                    currentQuery[0] = q;
                    rebuildList(ctx, d, filteredList, allFriends, allGroups, currentTab, currentQuery, currentGender,
                        selected, adapter, selectAllBtn, bottomBar, handler);
                }
            }
        });

        doneBtn.setOnClickListener(v -> {
            String display = selected.size() + " 个选中";
            if (callback != null) callback.onSelected(selected, display);
            dlgRef[0].dismiss();
        });

        AlertDialog.Builder b = new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        b.setView(root);
        b.setCancelable(true);
        AlertDialog dlg = b.create();
        dlgRef[0] = dlg;
        Window w = dlg.getWindow();
        if (w != null) {
            w.setLayout((int)(ctx.getResources().getDisplayMetrics().widthPixels * 0.92),
                        (int)(ctx.getResources().getDisplayMetrics().heightPixels * 0.78));
            w.setGravity(Gravity.CENTER);
        }

        dlg.show();

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                rebuildList(ctx, d, filteredList, allFriends, allGroups, currentTab, currentQuery, currentGender,
                    selected, adapter, selectAllBtn, bottomBar, handler);
            }
        }, 150);
    }

    private static void rebuildList(Context ctx, float d, List<Contact> filteredList,
                                     List<Contact> allFriends, List<Contact> allGroups,
                                     int[] currentTab, String[] currentQuery,
                                     int[] currentGender, Set<String> selected, ContactAdapter adapter,
                                     TextView selectAllBtn, View bottomBar, Handler handler) {
        filteredList.clear();
        List<Contact> source = currentTab[0] == MODE_FRIEND ? allFriends : allGroups;
        String q = currentQuery[0].toLowerCase().trim();

        for (Contact c : source) {
            if (currentTab[0] == MODE_FRIEND && !isValidFriend(c)) continue;
            if (currentTab[0] == MODE_GROUP && !isValidGroup(c)) continue;
            if (currentTab[0] == MODE_FRIEND && currentGender[0] == GENDER_MALE && !c.isMale()) continue;
            if (currentTab[0] == MODE_FRIEND && currentGender[0] == GENDER_FEMALE && !c.isFemale()) continue;
            if (!q.isEmpty() && !matchesSearch(c, q)) continue;
            filteredList.add(c);
        }

        adapter.updateData(filteredList);
        updateDoneBtn(bottomBar, selected);
        updateSelectAllState(selectAllBtn, filteredList, selected);
    }

    private static boolean isValidFriend(Contact c) {
        if (c == null || c.wxid == null) return false;
        if (c.wxid.endsWith("@chatroom")) return false;
        if (c.wxid.startsWith("gh_")) return false;
        if (c.wxid.startsWith("qqmail_")) return false;
        if (c.wxid.contains("@lbsroom")) return false;
        if (c.wxid.contains("@openim")) return false;
        return true;
    }

    private static boolean isValidGroup(Contact c) {
        if (c == null || c.wxid == null) return false;
        return c.wxid.endsWith("@chatroom");
    }

    private static boolean matchesSearch(Contact c, String q) {
        if (q.isEmpty()) return true;
        if (c.displayName() != null && c.displayName().toLowerCase().contains(q)) return true;
        if (c.remarkName != null && c.remarkName.toLowerCase().contains(q)) return true;
        if (c.nickname != null && c.nickname.toLowerCase().contains(q)) return true;
        if (c.wxid != null && c.wxid.toLowerCase().contains(q)) return true;
        if (c.alias != null && c.alias.toLowerCase().contains(q)) return true;
        return false;
    }

    private static void updateSelectAllState(TextView btn, List<Contact> list, Set<String> selected) {
        boolean allSelected = list.size() > 0;
        for (Contact c : list) {
            if (!selected.contains(c.wxid)) { allSelected = false; break; }
        }
        btn.setText(allSelected ? "取消勾选" : "全部勾选");
    }

    private static void updateDoneBtn(View bottomBar, Set<String> selected) {
        View doneBtn = ((ViewGroup) bottomBar).getChildAt(1);
        if (doneBtn instanceof TextView) {
            ((TextView) doneBtn).setText("确定(" + selected.size() + ")");
        }
    }

    private static int getColorFromName(String name) {
        int[] colors = {0xFFE04040, 0xFF07C160, 0xFF576B95, 0xFFFFBE00, 0xFF10AEFF,
                        0xFF7B2FBE, 0xFFFF4298, 0xFFFF8C00, 0xFF00C088, 0xFF576B95};
        if (name == null || name.isEmpty()) return colors[0];
        int hash = 0;
        for (int i = 0; i < name.length(); i++) hash = hash * 31 + name.charAt(i);
        return colors[Math.abs(hash) % colors.length];
    }

    static class ContactAdapter extends RecyclerView.Adapter<ContactAdapter.VH> {

        private final Context ctx;
        private final float d;
        private List<Contact> data = new ArrayList<>();
        private final Set<String> selected;
        private final int avatarSize;
        private View bottomBar;
        private TextView selectAllBtn;

        ContactAdapter(Context ctx, float d, List<Contact> filteredList, Set<String> selected) {
            this.ctx = ctx;
            this.d = d;
            this.data = new ArrayList<>(filteredList);
            this.selected = selected;
            this.avatarSize = (int)(36 * d);
        }

        void setBottomBar(View bar) { this.bottomBar = bar; }
        void setSelectAllBtn(TextView btn) { this.selectAllBtn = btn; }

        void updateData(List<Contact> newData) {
            this.data = new ArrayList<>(newData);
            notifyDataSetChanged();
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int)(14 * d), (int)(8 * d), (int)(14 * d), (int)(8 * d));
            row.setBackgroundColor(AppColors.whiteCard());
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));

            TextView checkTv = new TextView(ctx);
            checkTv.setTextSize(16);
            checkTv.setPadding(0, 0, (int)(10 * d), 0);
            checkTv.setGravity(Gravity.CENTER);
            row.addView(checkTv);

            TextAvatarView avatar = new TextAvatarView(ctx, avatarSize);
            avatar.setLayoutParams(new LinearLayout.LayoutParams(avatarSize, avatarSize));
            row.addView(avatar);

            LinearLayout textCol = new LinearLayout(ctx);
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setPadding((int)(10 * d), 0, 0, 0);
            textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

            TextView nameTv = new TextView(ctx);
            nameTv.setTextSize(14);
            nameTv.setTextColor(AppColors.text1());
            textCol.addView(nameTv);

            TextView wxidTv = new TextView(ctx);
            wxidTv.setTextSize(11);
            wxidTv.setTextColor(AppColors.text2());
            wxidTv.setPadding(0, (int)(2 * d), 0, 0);
            wxidTv.setVisibility(View.GONE);
            textCol.addView(wxidTv);

            row.addView(textCol);

            return new VH(row, checkTv, avatar, nameTv, wxidTv);
        }

        @Override
        public void onBindViewHolder(VH holder, int position) {
            Contact c = data.get(position);
            boolean sel = selected.contains(c.wxid);
            holder.checkTv.setText(sel ? "\u2713" : "");
            holder.checkTv.setTextColor(sel ? AppColors.green() : AppColors.arrow());
            holder.nameTv.setText(c.displayName());

            holder.wxidTv.setText(c.detailInfo());
            holder.wxidTv.setVisibility(View.VISIBLE);

            holder.avatar.setLetter(c.displayName(), c.wxid);

            final String wxid = c.wxid;
            new Thread(() -> {
                Bitmap bm = AvatarHelper.loadAvatar(wxid, avatarSize);
                if (bm != null) {
                    holder.avatar.post(() -> {
                        if (wxid.equals(holder.avatar.getTag()))
                            holder.avatar.setImageBitmap(bm);
                    });
                }
            }).start();

            holder.itemView.setOnClickListener(v -> {
                if (selected.contains(wxid)) selected.remove(wxid);
                else selected.add(wxid);
                notifyItemChanged(position);
                if (bottomBar != null) updateDoneBtn(bottomBar, selected);
                if (selectAllBtn != null) updateSelectAllState(selectAllBtn, data, selected);
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView checkTv;
            TextAvatarView avatar;
            TextView nameTv;
            TextView wxidTv;

            VH(View itemView, TextView checkTv, TextAvatarView avatar, TextView nameTv, TextView wxidTv) {
                super(itemView);
                this.checkTv = checkTv;
                this.avatar = avatar;
                this.nameTv = nameTv;
                this.wxidTv = wxidTv;
            }
        }
    }

    static class TextAvatarView extends View {
        private String mLetter;
        private String mTag;
        private int mColor;
        private final Paint mBgPaint;
        private final Paint mTextPaint;
        private final int mSize;

        TextAvatarView(Context ctx, int size) {
            super(ctx);
            mSize = size;
            mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mTextPaint.setTextAlign(Paint.Align.CENTER);
            mTextPaint.setColor(0xFFFFFFFF);
            mTextPaint.setFakeBoldText(true);
            updatePaints();
        }

        void setLetter(String name, String tag) {
            mTag = tag;
            mLetter = "?";
            if (name != null && !name.isEmpty()) {
                char first = name.charAt(0);
                if (Character.isLetter(first)) mLetter = String.valueOf(first).toUpperCase();
                else if (first >= 0x4E00 && first <= 0x9FFF) mLetter = String.valueOf(first);
            }
            mColor = getColorFromName(name != null ? name : "?");
            mBgPaint.setColor(mColor);
            invalidate();
        }

        void setImageBitmap(Bitmap bm) {
            if (bm != null) {
                mLetter = null;
                mBgPaint.setShader(new BitmapShader(bm, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                invalidate();
            }
        }

        @Override
        public Object getTag() { return mTag; }

        private void updatePaints() {
            float textSize = mSize * 0.38f;
            mTextPaint.setTextSize(textSize);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = mSize / 2f;
            float cy = mSize / 2f;
            float r = mSize / 2f;
            if (mLetter != null) {
                Paint.FontMetrics fm = mTextPaint.getFontMetrics();
                float textY = (mSize / 2f) - (fm.ascent + fm.descent) / 2f;
                canvas.drawCircle(cx, cy, r, mBgPaint);
                canvas.drawText(mLetter, cx, textY, mTextPaint);
            } else {
                canvas.drawCircle(cx, cy, r, mBgPaint);
            }
        }
    }
}
