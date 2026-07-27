package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.db.ContactRepository;
import com.leshao.v3.model.Contact;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ContactSelectorView {

    private static final int COLOR_BG       = 0xFFF4F0FF;
    private static final int COLOR_CARD     = 0xFFFFFFFF;
    private static final int COLOR_TEXT     = 0xFF281838;
    private static final int COLOR_TEXT2    = 0xFF786890;
    private static final int COLOR_ACCENT   = 0xFFFF4298;
    private static final int COLOR_DIVIDER  = 0xFFE8DCF0;
    private static final int COLOR_TAB_INACTIVE = 0xFFE8E0F0;

    private static final int COLOR_AVATAR_FRIEND = 0xFF42A5F5;
    private static final int COLOR_AVATAR_GROUP  = 0xFF26A69A;

    private Activity mActivity;
    private boolean mVoiceOnlyMode;
    private Callback mCallback;
    private boolean mFriendTab = true;
    private final Set<String> mCheckedWxids = new HashSet<>();

    public interface Callback {
        void onSelected(List<Contact> selected);
    }

    public static void show(Activity parentAct, boolean voiceOnlyMode, Callback callback) {
        new ContactSelectorView().buildAndShow(parentAct, voiceOnlyMode, callback);
    }

    private void buildAndShow(Activity parentAct, boolean voiceOnlyMode, Callback callback) {
        mActivity = parentAct;
        mVoiceOnlyMode = voiceOnlyMode;
        mCallback = callback;

        LinearLayout root = new LinearLayout(mActivity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        // ── Tab bar ──
        LinearLayout tabBar = new LinearLayout(mActivity);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(0, dp(40), 1);
        tabLp.setMargins(dp(4), 0, dp(4), 0);

        Button btnFriend = new Button(mActivity);
        btnFriend.setText("好友列表");
        btnFriend.setAllCaps(false);
        btnFriend.setTextSize(14);
        btnFriend.setGravity(Gravity.CENTER);

        Button btnGroup = new Button(mActivity);
        btnGroup.setText("群聊列表");
        btnGroup.setAllCaps(false);
        btnGroup.setTextSize(14);
        btnGroup.setGravity(Gravity.CENTER);

        applyTabStyle(btnFriend, true);
        applyTabStyle(btnGroup, false);

        tabBar.addView(btnFriend, tabLp);
        tabBar.addView(btnGroup, tabLp);
        root.addView(tabBar);

        // ── Search ──
        EditText searchEdit = new EditText(mActivity);
        searchEdit.setHint("搜索名称/wxid");
        searchEdit.setHintTextColor(COLOR_TEXT2);
        searchEdit.setTextColor(COLOR_TEXT);
        searchEdit.setSingleLine();
        searchEdit.setBackgroundColor(COLOR_CARD);
        searchEdit.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.setMargins(0, dp(10), 0, dp(8));
        root.addView(searchEdit, searchLp);

        // ── RecyclerView ──
        RecyclerView recyclerView = new RecyclerView(mActivity);
        recyclerView.setLayoutManager(new LinearLayoutManager(mActivity));
        recyclerView.setBackgroundColor(COLOR_CARD);
        recyclerView.setMinimumHeight(dp(480));

        List<Contact> sourceContacts = mFriendTab
                ? ContactRepository.getFriends()
                : ContactRepository.getGroups();
        ContactAdapter adapter = new ContactAdapter(sourceContacts, mCheckedWxids);
        recyclerView.setAdapter(adapter);

        LinearLayout.LayoutParams rvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        root.addView(recyclerView, rvLp);

        // ── Search filter ──
        searchEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterContacts(adapter, s.toString().toLowerCase().trim());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // ── Bottom bar ──
        LinearLayout bottomBar = new LinearLayout(mActivity);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setPadding(0, dp(10), 0, 0);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView btnSelectAll = new TextView(mActivity);
        btnSelectAll.setText("全部勾选");
        btnSelectAll.setTextColor(COLOR_ACCENT);
        btnSelectAll.setTextSize(15);
        btnSelectAll.setPadding(dp(8), dp(10), dp(16), dp(10));
        btnSelectAll.setClickable(true);
        btnSelectAll.setFocusable(true);

        Button btnConfirm = new Button(mActivity);
        btnConfirm.setText("确定");
        btnConfirm.setTextColor(Color.WHITE);
        btnConfirm.setTextSize(15);
        btnConfirm.setAllCaps(false);
        GradientDrawable confirmBg = new GradientDrawable();
        confirmBg.setColor(COLOR_ACCENT);
        confirmBg.setCornerRadius(dp(6));
        btnConfirm.setBackground(confirmBg);
        btnConfirm.setPadding(dp(28), dp(10), dp(28), dp(10));
        btnConfirm.setGravity(Gravity.CENTER);

        final boolean[] selectAllOn = {false};

        LinearLayout.LayoutParams selectAllLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        bottomBar.addView(btnSelectAll, selectAllLp);
        bottomBar.addView(btnConfirm);
        root.addView(bottomBar);

        // ── Tab switchers ──
        btnFriend.setOnClickListener(v -> switchTab(adapter, searchEdit, btnSelectAll,
                selectAllOn, btnFriend, btnGroup, true));
        btnGroup.setOnClickListener(v -> switchTab(adapter, searchEdit, btnSelectAll,
                selectAllOn, btnFriend, btnGroup, false));

        // ── Select all / deselect all ──
        btnSelectAll.setOnClickListener(v -> {
            selectAllOn[0] = !selectAllOn[0];
            btnSelectAll.setText(selectAllOn[0] ? "取消勾选" : "全部勾选");
            List<Contact> data = adapter.getCurrentData();
            if (selectAllOn[0]) {
                for (Contact c : data) mCheckedWxids.add(c.wxid);
            } else {
                for (Contact c : data) mCheckedWxids.remove(c.wxid);
            }
            adapter.notifyDataSetChanged();
        });

        // ── Dialog ──
        AlertDialog dialog = new AlertDialog.Builder(mActivity)
                .setTitle("选择联系人")
                .setView(root)
                .setCancelable(true)
                .create();

        btnConfirm.setOnClickListener(v -> {
            List<Contact> selected = new ArrayList<>();
            List<Contact> source = mFriendTab
                    ? ContactRepository.getFriends()
                    : ContactRepository.getGroups();
            for (Contact c : source) {
                if (mCheckedWxids.contains(c.wxid)) selected.add(c);
            }
            mCallback.onSelected(selected);
            dialog.dismiss();
        });

        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, dp(600));
        }
    }

    private void switchTab(ContactAdapter adapter, EditText searchEdit,
                           TextView btnSelectAll, boolean[] selectAllOn,
                           Button btnFriend, Button btnGroup, boolean friendTab) {
        mFriendTab = friendTab;
        applyTabStyle(btnFriend, friendTab);
        applyTabStyle(btnGroup, !friendTab);
        searchEdit.setText("");
        selectAllOn[0] = false;
        btnSelectAll.setText("全部勾选");
        List<Contact> contacts = friendTab
                ? ContactRepository.getFriends()
                : ContactRepository.getGroups();
        adapter.updateData(contacts);
    }

    private void filterContacts(ContactAdapter adapter, String keyword) {
        List<Contact> all = mFriendTab
                ? ContactRepository.getFriends()
                : ContactRepository.getGroups();
        List<Contact> filtered = new ArrayList<>();
        for (Contact c : all) {
            if (keyword.isEmpty()
                    || c.displayName().toLowerCase().contains(keyword)
                    || c.wxid.toLowerCase().contains(keyword)) {
                filtered.add(c);
            }
        }
        adapter.updateData(filtered);
    }

    private void applyTabStyle(Button btn, boolean active) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(active ? COLOR_ACCENT : COLOR_TAB_INACTIVE);
        gd.setCornerRadius(dp(6));
        btn.setBackground(gd);
        btn.setTextColor(active ? Color.WHITE : COLOR_TEXT2);
    }

    private int dp(int px) {
        return (int) (px * mActivity.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ========================================================================
    // Adapter (static inner class)
    // ========================================================================

    static class ContactAdapter extends RecyclerView.Adapter<ContactAdapter.VH> {

        private List<Contact> mData;
        private final Set<String> mCheckedWxids;

        ContactAdapter(List<Contact> data, Set<String> checkedWxids) {
            mData = new ArrayList<>(data);
            mCheckedWxids = checkedWxids;
        }

        void updateData(List<Contact> newData) {
            mData = new ArrayList<>(newData);
            notifyDataSetChanged();
        }

        List<Contact> getCurrentData() {
            return mData;
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            android.content.Context ctx = parent.getContext();

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int pad = dp(ctx, 14);
            row.setPadding(pad, dp(ctx, 6), pad, dp(ctx, 6));
            row.setGravity(Gravity.CENTER_VERTICAL);

            CheckBox cb = new CheckBox(ctx);
            cb.setPadding(0, 0, dp(ctx, 10), 0);
            row.addView(cb, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            AvatarView avatar = new AvatarView(ctx);
            int size = dp(ctx, 32);
            row.addView(avatar, new LinearLayout.LayoutParams(size, size));

            LinearLayout textCol = new LinearLayout(ctx);
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setPadding(dp(ctx, 10), 0, 0, 0);
            textCol.setGravity(Gravity.CENTER_VERTICAL);

            TextView nameTv = new TextView(ctx);
            nameTv.setTextColor(COLOR_TEXT);
            nameTv.setTextSize(14);

            TextView subTv = new TextView(ctx);
            subTv.setTextColor(COLOR_TEXT2);
            subTv.setTextSize(11);

            textCol.addView(nameTv);
            textCol.addView(subTv);
            row.addView(textCol, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            return new VH(row, cb, avatar, nameTv, subTv);
        }

        @Override
        public void onBindViewHolder(VH holder, int position) {
            Contact contact = mData.get(position);
            holder.nameTv.setText(contact.displayName());
            holder.subTv.setText(contact.detailInfo());
            holder.avatar.setInitials(getInitials(contact.displayName()));
            holder.avatar.setIsGroup(contact.isGroup());

            holder.cb.setOnCheckedChangeListener(null);
            holder.cb.setChecked(mCheckedWxids.contains(contact.wxid));
            holder.cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) mCheckedWxids.add(contact.wxid);
                else mCheckedWxids.remove(contact.wxid);
            });
        }

        @Override
        public int getItemCount() {
            return mData.size();
        }

        private static String getInitials(String name) {
            if (name == null || name.isEmpty()) return "?";
            return name.substring(0, 1);
        }

        static class VH extends RecyclerView.ViewHolder {
            final CheckBox cb;
            final AvatarView avatar;
            final TextView nameTv;
            final TextView subTv;

            VH(View itemView, CheckBox cb, AvatarView avatar, TextView nameTv, TextView subTv) {
                super(itemView);
                this.cb = cb;
                this.avatar = avatar;
                this.nameTv = nameTv;
                this.subTv = subTv;
            }
        }

        private static int dp(android.content.Context ctx, int px) {
            return (int) (px * ctx.getResources().getDisplayMetrics().density + 0.5f);
        }
    }

    // ========================================================================
    // AvatarView — 32dp circle with initials
    // ========================================================================

    static class AvatarView extends View {

        private String mInitials = "";
        private boolean mIsGroup = false;
        private final Paint mBgPaint;
        private final Paint mTextPaint;
        private final Rect mTextBounds = new Rect();

        AvatarView(android.content.Context ctx) {
            super(ctx);
            mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mTextPaint.setColor(Color.WHITE);
            mTextPaint.setTextAlign(Paint.Align.CENTER);
        }

        void setInitials(String initials) {
            mInitials = initials;
            invalidate();
        }

        void setIsGroup(boolean isGroup) {
            mIsGroup = isGroup;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            mTextPaint.setTextSize(w * 0.44f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;

            int cx = w / 2, cy = h / 2;
            int r = Math.min(w, h) / 2;

            mBgPaint.setColor(mIsGroup ? COLOR_AVATAR_GROUP : COLOR_AVATAR_FRIEND);
            canvas.drawCircle(cx, cy, r, mBgPaint);

            if (mInitials == null || mInitials.isEmpty()) return;
            String text = mInitials.length() > 1 ? mInitials.substring(0, 1) : mInitials;
            mTextPaint.getTextBounds(text, 0, text.length(), mTextBounds);
            canvas.drawText(text, cx, cy + mTextBounds.height() / 2f, mTextPaint);
        }
    }
}
