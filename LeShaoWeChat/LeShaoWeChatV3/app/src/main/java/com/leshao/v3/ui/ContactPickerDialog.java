package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.leshao.v3.ContactRepository;
import com.leshao.v3.model.ContactCard;
import com.leshao.v3.model.ContactCard.Category;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ContactPickerDialog {

    public static final int MODE_FRIEND = 0;
    public static final int MODE_GROUP = 1;

    public interface OnContactsSelected {
        void onSelected(Set<String> wxids, String display);
    }

    public static void show(Activity parentAct, String currentIds, int initialMode, OnContactsSelected callback) {
        if (parentAct == null || parentAct.isFinishing()) return;

        ContactRepository.loadAsync(() -> {
            List<ContactCard> all;
            switch (initialMode) {
                case MODE_FRIEND: all = ContactRepository.getFriends(); break;
                case MODE_GROUP:
                default:          all = ContactRepository.getGroups(); break;
            }
            if (all == null || all.isEmpty()) {
                parentAct.runOnUiThread(() -> {
                    android.widget.Toast.makeText(parentAct, "\u901a\u8baf\u5f55\u672a\u52a0\u8f7d",
                            android.widget.Toast.LENGTH_LONG).show();
                });
                return;
            }

            final List<ContactCard> contacts = new ArrayList<>(all);
            Collections.sort(contacts, Comparator.comparing(c -> c.sortKey()));

            final Set<String> selected = new LinkedHashSet<>();
            if (currentIds != null && !currentIds.isEmpty()) {
                for (String id : currentIds.split(",")) {
                    String trimmed = id.trim();
                    if (!trimmed.isEmpty()) selected.add(trimmed);
                }
            }

            parentAct.runOnUiThread(() -> showDialog(parentAct, contacts, selected, initialMode, callback));
        });
    }

    private static void showDialog(Activity act, List<ContactCard> items,
                                   Set<String> selected, int initialMode,
                                   OnContactsSelected callback) {
        int p20 = dp(act, 20);
        int p16 = dp(act, 16);
        int p12 = dp(act, 12);
        int p8 = dp(act, 8);
        int p4 = dp(act, 4);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setMinimumHeight(dp(act, 520));

        // Title
        TextView title = new TextView(act);
        title.setText("\u9009\u62e9\u8054\u7cfb\u4eba");
        title.setTextSize(16);
        title.setTextColor(AppColors.TEXT_TITLE);
        title.setPadding(p16, p16, p16, p12);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        // Tab bar: 好友 | 群聊 with counts
        // 计数必须基于全部联系人, 而非 initialMode 单列表(否则好友模式时群计数恒为 0)
        int friendCount = 0, groupCount = 0;
        for (ContactCard c : ContactRepository.getFriends()) {
            if (c.category == Category.FRIEND) friendCount++;
        }
        for (ContactCard c : ContactRepository.getGroups()) {
            if (c.category == Category.GROUP) groupCount++;
        }
        final int fFriendCount = friendCount, fGroupCount = groupCount;

        final int[] currentTab = {initialMode};
        LinearLayout tabs = new LinearLayout(act);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER);
        tabs.setPadding(p12, 0, p12, p12);

        TextView tabFriend = buildTab(act, "\u597d\u53cb(" + fFriendCount + ")", currentTab[0] == MODE_FRIEND);
        TextView tabGroup = buildTab(act, "\u7fa4\u804a(" + fGroupCount + ")", currentTab[0] == MODE_GROUP);
        tabs.addView(tabFriend);
        tabs.addView(space(act, p20));
        tabs.addView(tabGroup);
        root.addView(tabs);

        // Search
        EditText search = new EditText(act);
        search.setHint("\u641c\u7d22...");
        search.setHintTextColor(AppColors.TEXT_NOTE);
        search.setTextSize(14);
        search.setTextColor(AppColors.TEXT_BODY);
        search.setPadding(p16, p10(act), p16, p10(act));
        search.setBackground(roundBg(act, AppColors.INPUT_BG, dp(act, 20)));
        search.setSingleLine(true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.setMargins(p16, 0, p16, p8);
        root.addView(search, slp);

        // List container
        LinearLayout listRoot = new LinearLayout(act);
        listRoot.setOrientation(LinearLayout.VERTICAL);
        listRoot.setPadding(p12, 0, p12, 0);

        ScrollView sv = new ScrollView(act);
        sv.addView(listRoot);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        List<ContactCard>[] filteredHolder = new List[]{items};

        Runnable[] refreshHolder = new Runnable[1];
        refreshHolder[0] = () -> {
            listRoot.removeAllViews();
            String f = search.getText().toString().toLowerCase().trim();

            // Filter by tab
            if (currentTab[0] == MODE_FRIEND) {
                filteredHolder[0] = ContactRepository.getFriends();
            } else {
                filteredHolder[0] = ContactRepository.getGroups();
            }
            if (filteredHolder[0] == null) filteredHolder[0] = Collections.emptyList();

            int count = 0;
            for (ContactCard c : filteredHolder[0]) {
                if (!f.isEmpty() && !matchesFilter(c, f)) continue;
                String wxid = c.username;
                listRoot.addView(buildRow(act, c, selected.contains(wxid), () -> {
                    if (selected.contains(wxid)) selected.remove(wxid);
                    else selected.add(wxid);
                    refreshHolder[0].run();
                }));
                count++;
            }
            if (count == 0) {
                TextView empty = new TextView(act);
                empty.setText("\u65e0\u5339\u914d\u8054\u7cfb\u4eba");
                empty.setTextSize(14);
                empty.setTextColor(AppColors.TEXT_NOTE);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(0, dp(act, 40), 0, 0);
                listRoot.addView(empty);
            }
            title.setText("\u5df2\u9009 " + selected.size() + " \u4eba");
        };

        tabFriend.setOnClickListener(v -> {
            currentTab[0] = MODE_FRIEND;
            updateTabs(act, tabFriend, tabGroup, MODE_FRIEND);
            refreshHolder[0].run();
        });
        tabGroup.setOnClickListener(v -> {
            currentTab[0] = MODE_GROUP;
            updateTabs(act, tabFriend, tabGroup, MODE_GROUP);
            refreshHolder[0].run();
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refreshHolder[0].run(); }
        });

        refreshHolder[0].run();

        // Bottom bar: toggle + buttons
        LinearLayout bottomBar = new LinearLayout(act);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(p16, p8, p16, p16);

        LinearLayout btns = new LinearLayout(act);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.leftMargin = p4;
        btnLp.rightMargin = p4;

        int btnRadius = dp(act, 20);
        TextView cancel = dialogBtn(act, "\u53d6\u6d88", AppColors.TEXT_NOTE, AppColors.DIVIDER, btnRadius);
        TextView toggleAll = new TextView(act);
        toggleAll.setTextSize(13);
        toggleAll.setTextColor(AppColors.WHITE_TEXT);
        toggleAll.setPadding(dp(act, 18), dp(act, 8), dp(act, 18), dp(act, 8));
        toggleAll.setBackground(roundBg(act, AppColors.ACCENT, btnRadius));
        toggleAll.setGravity(Gravity.CENTER);
        toggleAll.setPaintFlags(toggleAll.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        TextView confirm = dialogBtn(act, "\u786e\u5b9a", AppColors.WHITE_TEXT, AppColors.ACCENT, btnRadius);

        btns.addView(cancel, btnLp);
        btns.addView(space(act, p16));
        btns.addView(toggleAll, btnLp);
        btns.addView(space(act, p16));
        btns.addView(confirm, btnLp);
        bottomBar.addView(btns);
        root.addView(bottomBar);

        AlertDialog dialog = new AlertDialog.Builder(act)
                .setView(root)
                .setCancelable(true)
                .create();

        cancel.setOnClickListener(v -> {
            // 取消: 不回调, 保留原有配置(避免把已保存的白/黑名单清空)
            dialog.dismiss();
        });

        confirm.setOnClickListener(v -> {
            if (callback != null) {
                StringBuilder sb = new StringBuilder();
                int i = 0;
                for (String wid : selected) {
                    if (i++ > 0) sb.append(", ");
                    ContactCard found = findCard(items, wid);
                    if (found == null) found = findCard(ContactRepository.getAll(), wid);
                    sb.append(found != null ? found.displayName() : wid);
                    if (i >= 4 && i < selected.size()) {
                        sb.append("...\u7b49" + selected.size() + "");
                        break;
                    }
                }
                callback.onSelected(new LinkedHashSet<>(selected), sb.toString());
            }
            dialog.dismiss();
        });

        toggleAll.setOnClickListener(v -> {
            List<ContactCard> source = filteredHolder[0];
            if (source == null) return;
            String f = search.getText().toString().toLowerCase().trim();
            List<String> visibleIds = new ArrayList<>();
            for (ContactCard c : source) {
                if (!f.isEmpty() && !matchesFilter(c, f)) continue;
                visibleIds.add(c.username);
            }
            boolean allSelected = !visibleIds.isEmpty() && selected.containsAll(visibleIds);
            if (allSelected) {
                selected.removeAll(visibleIds);
            } else {
                selected.addAll(visibleIds);
            }
            refreshHolder[0].run();
        });

        dialog.setOnCancelListener(d -> {
            // 返回键取消: 不回调, 保留原有配置
        });

        // Update toggle text + confirm button in refresh
        Runnable origRefresh = refreshHolder[0];
        refreshHolder[0] = () -> {
            origRefresh.run();
            List<ContactCard> source = filteredHolder[0];
            if (source == null) return;
            String f = search.getText().toString().toLowerCase().trim();
            List<String> visibleIds = new ArrayList<>();
            for (ContactCard c : source) {
                if (!f.isEmpty() && !matchesFilter(c, f)) continue;
                visibleIds.add(c.username);
            }
            boolean allSelected = !visibleIds.isEmpty() && selected.containsAll(visibleIds);
            toggleAll.setText(allSelected ? "\u53d6\u6d88\u5168\u9009" : "\u5168\u9009");
            confirm.setText("\u786e\u5b9a (" + selected.size() + ")");
        };

        dialog.show();
    }

    private static void updateTabs(Activity act, TextView tFriend, TextView tGroup, int mode) {
        int radius = dp(act, 20);
        boolean friendActive = mode == MODE_FRIEND;
        tFriend.setTextColor(friendActive ? AppColors.WHITE_TEXT : AppColors.TEXT_BODY);
        tFriend.setBackground(roundBg(act, friendActive ? AppColors.ACCENT : AppColors.DIVIDER, radius));
        tGroup.setTextColor(friendActive ? AppColors.TEXT_BODY : AppColors.WHITE_TEXT);
        tGroup.setBackground(roundBg(act, friendActive ? AppColors.DIVIDER : AppColors.ACCENT, radius));
    }

    private static TextView buildTab(Activity act, String text, boolean selected) {
        int radius = dp(act, 20);
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(selected ? AppColors.WHITE_TEXT : AppColors.TEXT_BODY);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(act, 18), dp(act, 8), dp(act, 18), dp(act, 8));
        tv.setBackground(roundBg(act, selected ? AppColors.ACCENT : AppColors.DIVIDER, radius));
        tv.setPaintFlags(tv.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        return tv;
    }

    private static ContactCard findCard(List<ContactCard> items, String wxid) {
        for (ContactCard c : items) {
            if (wxid.equals(c.username)) return c;
        }
        return null;
    }

    private static LinearLayout buildRow(Activity act, ContactCard c, boolean checked, Runnable onToggle) {
        int p8 = dp(act, 8);
        int p12 = dp(act, 12);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(p12, p6(act), p12, p6(act));

        // Styled checkbox
        ImageView cb = new ImageView(act);
        cb.setImageDrawable(makeCheckbox(act, checked));
        cb.setScaleType(ImageView.ScaleType.CENTER);
        LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(
                dp(act, 28), dp(act, 28));
        cblp.setMargins(0, 0, p8, 0);
        row.addView(cb, cblp);

        // Avatar
        int avatarSize = dp(act, 40);
        ImageView avatar = new ImageView(act);
        Bitmap fallback = letterAvatar(act, c.sortKey().substring(0, 1), avatarSize);
        AvatarHelper.loadAvatarAsync(avatar, c.username, avatarSize, fallback);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        alp.setMargins(0, 0, p12, 0);
        row.addView(avatar, alp);

        // Name only
        LinearLayout textCol = new LinearLayout(act);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(act);
        name.setText(c.displayName());
        name.setTextSize(14);
        name.setTextColor(AppColors.TEXT_TITLE);
        textCol.addView(name);

        row.addView(textCol, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        row.setOnClickListener(v -> onToggle.run());
        return row;
    }

    private static Drawable makeCheckbox(Activity act, boolean checked) {
        int size = dp(act, 22);
        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bm);

        if (checked) {
            android.graphics.LinearGradient lg = new android.graphics.LinearGradient(
                    0, 0, size, size,
                    new int[]{AppColors.ACCENT, 0xFF1976D2, 0xFF0D47A1},
                    new float[]{0f, 0.5f, 1f},
                    android.graphics.Shader.TileMode.CLAMP);
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setShader(lg);
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1, fill);

            Paint check = new Paint(Paint.ANTI_ALIAS_FLAG);
            check.setColor(Color.WHITE);
            check.setStrokeWidth(dp(act, 2.2f));
            check.setStyle(Paint.Style.STROKE);
            check.setStrokeCap(Paint.Cap.ROUND);
            check.setStrokeJoin(Paint.Join.ROUND);
            float cx = size * 0.32f, cy = size * 0.52f;
            float mx = size * 0.46f, my = size * 0.66f;
            float ex = size * 0.72f, ey = size * 0.35f;
            canvas.drawLine(cx, cy, mx, my, check);
            canvas.drawLine(mx, my, ex, ey, check);
        } else {
            Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(AppColors.DIVIDER);
            stroke.setStrokeWidth(dp(act, 2));
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1, stroke);
        }

        return new android.graphics.drawable.BitmapDrawable(act.getResources(), bm);
    }

    private static boolean matchesFilter(ContactCard c, String q) {
        if (c.displayName().toLowerCase().contains(q)) return true;
        if (c.username != null && c.username.toLowerCase().contains(q)) return true;
        if (c.alias != null && c.alias.toLowerCase().contains(q)) return true;
        return false;
    }

    private static Bitmap letterAvatar(Activity act, String letter, int size) {
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

    private static TextView dialogBtn(Activity act, String text, int textColor, int bgColor, int radius) {
        TextView btn = new TextView(act);
        btn.setText(text);
        btn.setTextSize(14);
        btn.setTextColor(textColor);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(act, 18), dp(act, 8), dp(act, 18), dp(act, 8));
        btn.setBackground(roundBg(act, bgColor, radius));
        btn.setPaintFlags(btn.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        return btn;
    }

    private static View space(Activity act, int w) {
        View v = new View(act);
        v.setLayoutParams(new ViewGroup.LayoutParams(w, 1));
        return v;
    }

    private static GradientDrawable roundBg(Activity act, int color, int radius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radius);
        return gd;
    }

    private static int dp(Activity act, float px) { return (int) (px * act.getResources().getDisplayMetrics().density + 0.5f); }
    private static int p6(Activity act) { return dp(act, 6); }
    private static int p10(Activity act) { return dp(act, 10); }
}
