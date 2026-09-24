package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
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
import com.leshao.v3.ui.widgets.ModernButton;
import com.leshao.v3.ui.widgets.ModernTopBar;
import com.leshao.v3.ui.widgets.SegmentedControl;

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

        // v1025: 先立即显示加载框(DB 捕获/联系人加载完成后替换内容), 选择器秒开
        final android.app.AlertDialog[] loadingRef = new android.app.AlertDialog[1];
        try {
            android.app.AlertDialog loading = new android.app.AlertDialog.Builder(parentAct)
                    .setTitle("LeShao")
                    .setMessage("\u901a\u8baf\u5f55\u52a0\u8f7d\u4e2d\uff0c\u8bf7\u7a0d\u5019\u2026")
                    .setCancelable(true)
                    .create();
            loading.show();
            loadingRef[0] = loading;
        } catch (Throwable ignored) {}

        ContactRepository.loadAsync(() -> {
            List<ContactCard> all;
            switch (initialMode) {
                case MODE_FRIEND: all = ContactRepository.getFriends(); break;
                case MODE_GROUP:
                default:          all = ContactRepository.getGroups(); break;
            }
            if (all == null || all.isEmpty()) {
                parentAct.runOnUiThread(() -> {
                    if (loadingRef[0] != null) {
                        loadingRef[0].setMessage("\u901a\u8baf\u5f55\u672a\u52a0\u8f7d\u5b8c\u6210\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5");
                    } else {
                        android.widget.Toast.makeText(parentAct, "\u901a\u8baf\u5f55\u672a\u52a0\u8f7d",
                                android.widget.Toast.LENGTH_LONG).show();
                    }
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

            parentAct.runOnUiThread(() -> {
                if (loadingRef[0] != null) {
                    try { loadingRef[0].dismiss(); } catch (Throwable ignored) {}
                }
                showDialog(parentAct, contacts, selected, initialMode, callback);
            });
        });
    }

    private static void showDialog(Activity act, List<ContactCard> items,
                                   Set<String> selected, int initialMode,
                                   OnContactsSelected callback) {
        int p16 = dp(act, 16);
        int p12 = dp(act, 12);
        int p8 = dp(act, 8);
        int p4 = dp(act, 4);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setMinimumHeight(dp(act, 520));
        root.setBackground(CandyUi.dialogBg(act));
        InsetsUtil.clipRounded(root);

        final ModernTopBar topBar = new ModernTopBar(act, "\u9009\u62e9\u8054\u7cfb\u4eba", false, null);
        root.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

        // Tab bar: 好友 | 群聊 counts
        int friendCount = 0, groupCount = 0;
        for (ContactCard c : ContactRepository.getFriends()) {
            if (c.category == Category.FRIEND) friendCount++;
        }
        for (ContactCard c : ContactRepository.getGroups()) {
            if (c.category == Category.GROUP) groupCount++;
        }
        final int fFriendCount = friendCount, fGroupCount = groupCount;

        final int[] currentTab = {initialMode == MODE_FRIEND ? 0 : 1};
        final Runnable[] refreshHolder = new Runnable[1];
        SegmentedControl tabs = new SegmentedControl(act,
                new String[]{"\u597d\u53cb(" + fFriendCount + ")", "\u7fa4\u804a(" + fGroupCount + ")"},
                currentTab[0]);
        tabs.setOnSegmentChangedListener((index, label) -> {
            currentTab[0] = index;
            refreshHolder[0].run();
        });
        LinearLayout tabsContainer = new LinearLayout(act);
        tabsContainer.setOrientation(LinearLayout.VERTICAL);
        tabsContainer.setPadding(p12, 0, p12, p8);
        tabsContainer.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
        root.addView(tabsContainer);

        // Search
        EditText search = new EditText(act);
        search.setHint("\u641c\u7d22...");
        search.setHintTextColor(AppColors.onSurfaceVariant());
        search.setTextSize(14);
        search.setTextColor(AppColors.onSurface());
        search.setPadding(p16, p10(act), p16, p10(act));
        search.setBackground(CandyUi.inputBg(act));
        search.setSingleLine(true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.setMargins(p12, 0, p12, p8);
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

        refreshHolder[0] = () -> {
            listRoot.removeAllViews();
            String f = search.getText().toString().toLowerCase().trim();

            if (currentTab[0] == 0) {
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
                empty.setTextColor(AppColors.onSurfaceVariant());
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(0, dp(act, 40), 0, 0);
                listRoot.addView(empty);
            }
            topBar.setTitle("\u5df2\u9009 " + selected.size() + " \u4eba");
        };

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

        final ModernButton cancel = new ModernButton(act, "\u53d6\u6d88", ModernButton.STYLE_GHOST);
        final ModernButton toggleAll = new ModernButton(act, "\u5168\u9009", ModernButton.STYLE_TEXT);
        final ModernButton confirm = new ModernButton(act, "\u786e\u5b9a", ModernButton.STYLE_PRIMARY);

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

        cancel.onClick(() -> {
            dialog.dismiss();
        });

        confirm.onClick(() -> {
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

        toggleAll.onClick(() -> {
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

        InsetsUtil.transparentWindow(dialog);
        dialog.show();
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
        row.setBackground(CandyUi.rowPressBg(act));

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
        name.setTextColor(AppColors.onSurface());
        textCol.addView(name);

        row.addView(textCol, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        CandyUi.ripple(row, AppColors.SHAPE_MD_DP);
        row.setOnClickListener(v -> onToggle.run());
        return row;
    }

    private static Drawable makeCheckbox(Activity act, boolean checked) {
        int size = dp(act, 22);
        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bm);

        if (checked) {
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(AppColors.primary());
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
            stroke.setColor(AppColors.outline());
            stroke.setStrokeWidth(dp(act, 2));
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1, stroke);
        }

        return new BitmapDrawable(act.getResources(), bm);
    }

    private static boolean matchesFilter(ContactCard c, String q) {
        if (c.displayName().toLowerCase().contains(q)) return true;
        if (c.username != null && c.username.toLowerCase().contains(q)) return true;
        if (c.alias != null && c.alias.toLowerCase().contains(q)) return true;
        return false;
    }

    private static Bitmap letterAvatar(Activity act, String letter, int size) {
        Paint paint = new Paint();
        paint.setColor(Color.WHITE);
        paint.setTextSize(size * 0.45f);
        paint.setAntiAlias(true);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);

        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bm);
        Paint bgPaint = new Paint();
        bgPaint.setColor(AppColors.secondaryContainer());
        canvas.drawRoundRect(0, 0, size, size, size / 2f, size / 2f, bgPaint);
        float y = size / 2f - (paint.descent() + paint.ascent()) / 2f;
        canvas.drawText(letter, size / 2f, y, paint);
        return bm;
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
