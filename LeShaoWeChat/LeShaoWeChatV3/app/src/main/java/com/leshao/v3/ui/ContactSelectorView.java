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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.model.Contact;
import com.leshao.v3.wm.utils.WmReflect;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ContactSelectorView {

    private static final int COLOR_BG       = AppColors.bg();
    private static final int COLOR_CARD     = AppColors.card();
    private static final int COLOR_TEXT     = AppColors.text1();
    private static final int COLOR_TEXT2    = AppColors.text2();
    private static final int COLOR_ACCENT   = AppColors.accent();
    private static final int COLOR_DIVIDER  = AppColors.divider();
    private static final int COLOR_TAB_INACTIVE = AppColors.divider();

    private static final int COLOR_AVATAR_FRIEND = 0xFF42A5F5;
    private static final int COLOR_AVATAR_GROUP  = 0xFF26A69A;

    public static final int MODE_ALL    = 0;
    public static final int MODE_FRIEND = 1;
    public static final int MODE_GROUP  = 2;

    private Activity mActivity;
    private boolean mVoiceOnlyMode;
    private int mMode = MODE_ALL;
    private Callback mCallback;
    private boolean mFriendTab = true;
    private final Set<String> mCheckedWxids = new HashSet<>();

    public interface Callback {
        void onSelected(List<Contact> selected);
    }

    public static void show(Activity parentAct, boolean voiceOnlyMode, Callback callback) {
        new ContactSelectorView().buildAndShow(parentAct, voiceOnlyMode, MODE_ALL, callback);
    }

    public static void show(Activity parentAct, boolean voiceOnlyMode, int mode, Callback callback) {
        new ContactSelectorView().buildAndShow(parentAct, voiceOnlyMode, mode, callback);
    }

    private void buildAndShow(Activity parentAct, boolean voiceOnlyMode, int mode, Callback callback) {
        mActivity = parentAct;
        mVoiceOnlyMode = voiceOnlyMode;
        mMode = mode;
        mCallback = callback;
        if (mode == MODE_GROUP) mFriendTab = false;

        LinearLayout root = new LinearLayout(mActivity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        // ── Tab bar (hidden when single-mode) ──
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

        applyTabStyle(btnFriend, mFriendTab);
        applyTabStyle(btnGroup, !mFriendTab);

        if (mode == MODE_ALL) {
            tabBar.addView(btnFriend, tabLp);
            tabBar.addView(btnGroup, tabLp);
            root.addView(tabBar);
        }

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

        // 联系人数据源已清空，待重写
        List<Contact> sourceContacts = new ArrayList<>();
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
        GradientDrawable confirmBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{AppColors.accent(), COLOR_ACCENT});
        confirmBg.setCornerRadius(dp(6));
        btnConfirm.setBackground(confirmBg);
        btnConfirm.setPadding(dp(28), dp(10), dp(28), dp(10));
        btnConfirm.setGravity(Gravity.CENTER);

        TextView countTv = new TextView(mActivity);
        countTv.setText("0");
        countTv.setTextColor(COLOR_ACCENT);
        countTv.setTextSize(15);
        countTv.setVisibility(View.GONE);
        countTv.setPadding(dp(8), dp(10), dp(4), dp(10));

        final boolean[] selectAllOn = {false};

        LinearLayout.LayoutParams selectAllLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        bottomBar.addView(btnSelectAll, selectAllLp);
        bottomBar.addView(countTv);
        bottomBar.addView(btnConfirm);
        root.addView(bottomBar);

        adapter.setCountCallback(newCount -> {
            if (newCount > 0) {
                countTv.setVisibility(View.VISIBLE);
                countTv.setText("已选" + newCount);
            } else {
                countTv.setVisibility(View.GONE);
            }
        });

        // ── Tab switchers ──
        if (mode == MODE_ALL) {
            btnFriend.setOnClickListener(v -> switchTab(adapter, searchEdit, btnSelectAll,
                    selectAllOn, btnFriend, btnGroup, true));
            btnGroup.setOnClickListener(v -> switchTab(adapter, searchEdit, btnSelectAll,
                    selectAllOn, btnFriend, btnGroup, false));
        }

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
                .setTitle(mode == MODE_FRIEND ? "选择好友" : mode == MODE_GROUP ? "选择群聊" : "选择联系人")
                .setView(root)
                .setCancelable(true)
                .create();

        btnConfirm.setOnClickListener(v -> {
            try {
                List<Contact> selected = new ArrayList<>();
                List<Contact> source = adapter.getCurrentData();
                for (Contact c : source) {
                    if (mCheckedWxids.contains(c.wxid)) selected.add(c);
                }
                mCallback.onSelected(selected);
                dialog.dismiss();
            } catch (Throwable t) {
                try { dialog.dismiss(); } catch (Throwable ignored) {}
            }
        });

        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
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
        List<Contact> contacts = new ArrayList<>();
        adapter.updateData(contacts);
    }

    private void filterContacts(ContactAdapter adapter, String keyword) {
        List<Contact> all = new ArrayList<>();
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
        private CountCallback mCountCallback;

        interface CountCallback { void onCountChanged(int count); }

        ContactAdapter(List<Contact> data, Set<String> checkedWxids) {
            mData = new ArrayList<>(data);
            mCheckedWxids = checkedWxids;
        }

        void setCountCallback(CountCallback cb) { mCountCallback = cb; }

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

            TextView cb = new TextView(ctx);
            cb.setGravity(Gravity.CENTER);
            cb.setTextSize(13);
            cb.setTypeface(null, android.graphics.Typeface.BOLD);
            int selSize = dp(ctx, 22);
            row.addView(cb, new LinearLayout.LayoutParams(selSize, selSize));

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

            String cachedUrl = sAvatarCache.get(contact.wxid);
            if (cachedUrl == null) {
                try {
                    ClassLoader cl = com.leshao.v3.ContextManager.getClassLoader();
                    if (cl != null) {
                        String url = WmReflect.getAvatarUrl(cl, contact.wxid);
                        if (url != null && !url.isEmpty()) {
                            sAvatarCache.put(contact.wxid, url);
                            cachedUrl = url;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            if (cachedUrl != null && !cachedUrl.isEmpty()) {
                holder.avatar.loadImage(cachedUrl);
            } else {
                holder.avatar.setInitials(getInitials(contact.displayName()), contact.isGroup());
            }

            boolean checked = mCheckedWxids.contains(contact.wxid);
            android.content.Context ctx = holder.itemView.getContext();
            holder.cb.setText(checked ? "\u2714" : "");
            holder.cb.setTextColor(checked ? COLOR_ACCENT : AppColors.text3());
            GradientDrawable selBg = new GradientDrawable();
            selBg.setShape(GradientDrawable.OVAL);
            selBg.setColor(checked ? 0x332196F3 : 0x00FFFFFF);
            selBg.setStroke((int)(1.5f * dp(ctx, 1)), checked ? COLOR_ACCENT : AppColors.text3());
            holder.cb.setBackground(selBg);

            View.OnClickListener toggle = v -> {
                boolean nowChecked = !mCheckedWxids.contains(contact.wxid);
                if (nowChecked) mCheckedWxids.add(contact.wxid);
                else mCheckedWxids.remove(contact.wxid);
                holder.cb.setText(nowChecked ? "\u2714" : "");
                holder.cb.setTextColor(nowChecked ? COLOR_ACCENT : AppColors.text3());
                GradientDrawable nBg = new GradientDrawable();
                nBg.setShape(GradientDrawable.OVAL);
                nBg.setColor(nowChecked ? 0x332196F3 : 0x00FFFFFF);
                nBg.setStroke((int)(1.5f * dp(ctx, 1)), nowChecked ? COLOR_ACCENT : AppColors.text3());
                holder.cb.setBackground(nBg);
                if (mCountCallback != null) mCountCallback.onCountChanged(mCheckedWxids.size());
            };
            holder.cb.setOnClickListener(toggle);
            holder.itemView.setOnClickListener(v -> toggle.onClick(v));
        }

        private static final Map<String, String> sAvatarCache = new ConcurrentHashMap<>();

        @Override
        public int getItemCount() {
            return mData.size();
        }

        private static String getInitials(String name) {
            if (name == null || name.isEmpty()) return "?";
            return name.substring(0, 1);
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView cb;
            final AvatarView avatar;
            final TextView nameTv;
            final TextView subTv;

            VH(View itemView, TextView cb, AvatarView avatar, TextView nameTv, TextView subTv) {
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
    // AvatarView — 32dp circle with initials or loaded image
    // ========================================================================

    static class AvatarView extends View {

        private String mInitials = "";
        private boolean mIsGroup = false;
        private android.graphics.Bitmap mBitmap;
        private final Paint mBgPaint;
        private final Paint mTextPaint;
        private final android.graphics.Paint mBitmapPaint;
        private final Rect mTextBounds = new Rect();
        private String mPendingUrl;

        AvatarView(android.content.Context ctx) {
            super(ctx);
            mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mTextPaint.setColor(Color.WHITE);
            mTextPaint.setTextAlign(Paint.Align.CENTER);
            mBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mBitmapPaint.setFilterBitmap(true);
        }

        void setInitials(String initials, boolean isGroup) {
            mInitials = initials;
            mIsGroup = isGroup;
            mBitmap = null;
            mPendingUrl = null;
            invalidate();
        }

        void loadImage(String url) {
            if (url == null || url.isEmpty()) return;
            if (url.equals(mPendingUrl)) return;
            mPendingUrl = url;
            final String fUrl = url;
            new Thread(() -> {
                java.net.HttpURLConnection conn = null;
                java.io.InputStream is = null;
                try {
                    java.net.URL u = new java.net.URL(fUrl);
                    conn = (java.net.HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    is = conn.getInputStream();
                    android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is);
                    if (bmp != null && fUrl.equals(mPendingUrl)) {
                        post(() -> { mBitmap = bmp; invalidate(); });
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (is != null) try { is.close(); } catch (Exception ignored) {}
                    if (conn != null) conn.disconnect();
                }
            }).start();
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

            if (mBitmap != null) {
                // Try built-in circle helper first
                try {
                    Class<?> avatarHelper = getClass().getClassLoader().loadClass(
                        "com.tencent.mm.pluginsdk.ui.a");
                    android.graphics.Bitmap circle = android.graphics.Bitmap.createBitmap(w, h,
                            android.graphics.Bitmap.Config.ARGB_8888);
                    Canvas c2 = new Canvas(circle);
                    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                    p.setFilterBitmap(true);
                    android.graphics.Bitmap scaledBmp = android.graphics.Bitmap.createScaledBitmap(mBitmap, w, h, true);
                    canvas.save();
                    android.graphics.Path path = new android.graphics.Path();
                    path.addCircle(cx, cy, r, android.graphics.Path.Direction.CW);
                    canvas.clipPath(path);
                    canvas.drawBitmap(scaledBmp, 0, 0, mBitmapPaint);
                    canvas.restore();
                    if (scaledBmp != mBitmap) scaledBmp.recycle();
                    return;
                } catch (Throwable e) {
                    // simple circle fallback below
                }
                android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(mBitmap, w, h, true);
                canvas.save();
                android.graphics.Path path = new android.graphics.Path();
                path.addCircle(cx, cy, r, android.graphics.Path.Direction.CW);
                canvas.clipPath(path);
                canvas.drawBitmap(scaled, 0, 0, mBitmapPaint);
                canvas.restore();
                if (scaled != mBitmap) scaled.recycle();
                return;
            }

            mBgPaint.setColor(mIsGroup ? COLOR_AVATAR_GROUP : COLOR_AVATAR_FRIEND);
            canvas.drawCircle(cx, cy, r, mBgPaint);

            if (mInitials == null || mInitials.isEmpty()) return;
            String text = mInitials.length() > 1 ? mInitials.substring(0, 1) : mInitials;
            mTextPaint.getTextBounds(text, 0, text.length(), mTextBounds);
            canvas.drawText(text, cx, cy + mTextBounds.height() / 2f, mTextPaint);
        }
    }
}
