package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.hook.ThemeHook;
import com.leshao.v3.theme.MonetColorEngine;

public class ThemePageView {

    private static final int[] PRESET_COLORS = {
        0xFF2D2D2D, 0xFFF5F5F5, 0xFFFFFFFF, 0xFFEDEDED, 0xFFE8E8E8,
        0xFFFF4298, 0xFF576B95, 0xFF576B95, 0xFF191919, 0xFF888888,
        0xFF000000, 0xFFE04040, 0xFFFF8C00, 0xFFFFBE00, 0xFF10AEFF,
        0xFF7B2FBE, 0xFFFF8C00, 0xFFC73E3A, 0xFFF2F2F2, 0xFFD43C33
    };

    private static final String[] COLOR_NAMES = {
        "暗灰","暖白","纯白","浅灰","银白",
        "粉色","链接蓝","链接蓝2","深黑","浅灰字",
        "纯黑","红色","橙色","金色","天蓝",
        "紫色","橙色","暗红","灰白","微信红"
    };

    private static final String[] MONET_NAMES = {
        "TonalSpot","Neutral","Vibrant","Expressive",
        "Rainbow","FruitSalad","Monochrome","Fidelity"
    };

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;

        ScrollView sv = new ScrollView(ctx);
        sv.setBackgroundColor(AppColors.bg());

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int)(8*d), (int)(12*d), (int)(8*d), (int)(24*d));

        SharedPreferences prefs = ContextManager.getPrefs();
        boolean masterOn = prefs != null && prefs.getBoolean("ls_theme_enabled", false);

        root.addView(masterSwitchCard(ctx, parentAct, prefs, d, masterOn));
        root.addView(spacer(ctx, d, 10));

        root.addView(section(ctx, d, "主题引擎"));
        root.addView(featureRow(ctx, parentAct, d, "Monet 主题引擎", 28));

        sv.addView(root);
        return sv;
    }

    private static View masterSwitchCard(Context ctx, Activity parentAct, SharedPreferences prefs, float d, boolean on) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding((int)(16*d), (int)(14*d), (int)(16*d), (int)(14*d));
        card.setBackgroundColor(AppColors.whiteCard());

        TextView tv = new TextView(ctx);
        tv.setText("启用全局主题美化");
        tv.setTextSize(16);
        tv.setTextColor(AppColors.text1());
        tv.setTypeface(null, Typeface.BOLD);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        card.addView(tv);

        TextView star = new TextView(ctx);
        star.setText(on ? "\u2605" : "\u2606");
        star.setTextSize(26);
        star.setTextColor(on ? 0xFFFFD700 : 0xFFCCCCCC);
        star.setPadding((int)(8*d), 0, 0, 0);
        star.setTag(new Object[]{prefs, on});
        star.setOnClickListener(v -> {
            Object[] tag = (Object[]) v.getTag();
            SharedPreferences p = (SharedPreferences) tag[0];
            boolean cur = !(boolean) tag[1];
            tag[1] = cur;
            ((TextView) v).setText(cur ? "\u2605" : "\u2606");
            ((TextView) v).setTextColor(cur ? 0xFFFFD700 : 0xFFCCCCCC);
            p.edit().putBoolean("ls_theme_enabled", cur).apply();
            ThemeHook.setMasterEnabled(cur);
        });
        card.addView(star);

        return card;
    }

    private static View featureRow(Context ctx, Activity parentAct, float d, String title, int pageId) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(18*d), (int)(13*d), (int)(16*d), (int)(13*d));
        row.setBackgroundColor(AppColors.whiteCard());
        row.setOnClickListener(v -> {
            SubPageActivity.setThemeFeaturePageId(pageId);
            SubPageActivity.open(parentAct, title, 20);
        });

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(15);
        tv.setTextColor(AppColors.text1());
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        row.addView(tv);

        TextView arrow = new TextView(ctx);
        arrow.setText(">");
        arrow.setTextSize(16);
        arrow.setTextColor(AppColors.arrow());
        row.addView(arrow);

        return row;
    }

    public static View createFeatureConfigPage(Context ctx, Activity parentAct, int pageId) {
        float d = ctx.getResources().getDisplayMetrics().density;
        SharedPreferences prefs = ContextManager.getPrefs();

        ScrollView sv = new ScrollView(ctx);
        sv.setBackgroundColor(AppColors.bg());

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int)(8*d), (int)(12*d), (int)(8*d), (int)(24*d));

        switch (pageId) {
            case 21:
                buildColorConfig(ctx, parentAct, prefs, d, root,
                    "标题栏美化", "ls_theme_actionbar",
                    new String[]{"标题栏背景色","标题栏文字色"},
                    new String[]{"ls_tc_actionbar_bg","ls_tc_actionbar_title"},
                    new int[]{0xFF2D2D2D,0xFFFFFFFF},
                    v -> ThemeHook.setActionBarOn(v));
                break;
            case 22:
                buildColorConfig(ctx, parentAct, prefs, d, root,
                    "页面背景色", "ls_theme_pagebg",
                    new String[]{"页面背景色"},
                    new String[]{"ls_tc_page_bg"},
                    new int[]{0xFFF5F5F5},
                    v -> ThemeHook.setPageBgOn(v));
                break;
            case 23:
                buildColorConfig(ctx, parentAct, prefs, d, root,
                    "聊天背景色", "ls_theme_chatbg",
                    new String[]{"聊天背景色"},
                    new String[]{"ls_tc_chat_bg"},
                    new int[]{0xFFEDEDED},
                    v -> ThemeHook.setChatBgOn(v));
                break;
            case 24:
                buildColorConfig(ctx, parentAct, prefs, d, root,
                    "底部Tab美化", "ls_theme_convlist",
                    new String[]{"Tab背景色","选中色","未选中色"},
                    new String[]{"ls_tc_tab_bg","ls_tc_tab_selected","ls_tc_tab_unselected"},
                    new int[]{0xFFF7F7F7,0xFFFF4298,0xFF999999},
                    v -> ThemeHook.setConvListOn(v));
                break;
            case 25:
                buildColorConfigBubble(ctx, parentAct, prefs, d, root,
                    "自己聊天气泡",
                    new String[]{"自己气泡背景","自己文字色"},
                    new String[]{"ls_tc_bubble_self_bg","ls_tc_bubble_self_text"},
                    new int[]{0xFFFFFFFF,0xFF000000});
                break;
            case 26:
                buildColorConfigBubble(ctx, parentAct, prefs, d, root,
                    "对方聊天气泡",
                    new String[]{"对方气泡背景","对方文字色"},
                    new String[]{"ls_tc_bubble_other_bg","ls_tc_bubble_other_text"},
                    new int[]{0xFFFFFFFF,0xFF000000});
                break;
            case 27:
                buildColorConfig(ctx, parentAct, prefs, d, root,
                    "文字颜色", "ls_theme_textcolor",
                    new String[]{"主文字色","次要文字色"},
                    new String[]{"ls_tc_text_primary","ls_tc_text_secondary"},
                    new int[]{0xFF191919,0xFF888888},
                    v -> ThemeHook.setTextColorOn(v));
                break;
            case 28:
                buildMonetConfig(ctx, parentAct, prefs, d, root);
                break;
            case 29:
                buildImageBubbleConfig(ctx, parentAct, prefs, d, root);
                break;
        }

        sv.addView(root);
        return sv;
    }

    private static void buildColorConfig(Context ctx, Activity parentAct, SharedPreferences prefs,
                                          float d, LinearLayout root, String title, String toggleKey,
                                          String[] labels, String[] keys, int[] defaults,
                                          ToggleSetter setter) {
        boolean on = prefs.getBoolean(toggleKey, true);
        root.addView(toggleCard(ctx, d, title, on, v -> {
            prefs.edit().putBoolean(toggleKey, v).apply();
            setter.set(v);
        }));

        for (int i = 0; i < labels.length; i++) {
            int cur = prefs.getInt(keys[i], defaults[i]);
            root.addView(spacer(ctx, d, 4));
            root.addView(colorRow(ctx, parentAct, prefs, d, labels[i], keys[i], cur));
        }
    }

    private static void buildColorConfigBubble(Context ctx, Activity parentAct, SharedPreferences prefs,
                                                float d, LinearLayout root, String title,
                                                String[] labels, String[] keys, int[] defaults) {
        boolean bubbleOn = prefs.getBoolean("ls_theme_bubble", true);
        root.addView(toggleCard(ctx, d, title + "自定义", bubbleOn, v -> {
            prefs.edit().putBoolean("ls_theme_bubble", v).apply();
            ThemeHook.setBubbleOn(v);
        }));

        for (int i = 0; i < labels.length; i++) {
            int cur = prefs.getInt(keys[i], defaults[i]);
            root.addView(spacer(ctx, d, 4));
            root.addView(colorRow(ctx, parentAct, prefs, d, labels[i], keys[i], cur));
        }
    }

    interface ToggleSetter { void set(boolean on); }

    private static View toggleCard(Context ctx, float d, String title, boolean on, ToggleSetter ts) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(16*d), (int)(12*d), (int)(16*d), (int)(12*d));
        row.setBackgroundColor(AppColors.whiteCard());

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(14);
        tv.setTextColor(AppColors.text1());
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        row.addView(tv);

        TextView sw = new TextView(ctx);
        sw.setText(on ? "\u2605" : "\u2606");
        sw.setTextSize(26);
        sw.setTextColor(on ? 0xFFFFD700 : 0xFFCCCCCC);
        sw.setTag(new Object[]{on, ts});
        sw.setOnClickListener(v -> {
            Object[] tag = (Object[]) v.getTag();
            boolean cur = !(boolean) tag[0];
            tag[0] = cur;
            ((TextView) v).setText(cur ? "\u2605" : "\u2606");
            ((TextView) v).setTextColor(cur ? 0xFFFFD700 : 0xFFCCCCCC);
            ((ToggleSetter) tag[1]).set(cur);
        });
        row.addView(sw);

        return row;
    }

    private static View colorRow(Context ctx, Activity parentAct, SharedPreferences prefs,
                                  float d, String label, String key, int current) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(16*d), (int)(10*d), (int)(16*d), (int)(10*d));
        row.setBackgroundColor(AppColors.whiteCard());

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(14);
        tv.setTextColor(AppColors.text1());
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        row.addView(tv);

        int sz = (int)(28*d);
        View dot = new View(ctx);
        dot.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(current);
        gd.setStroke((int)(1.5f*d), 0xFFD0D0D0);
        dot.setBackground(gd);

        final int[] cur = {current};
        dot.setOnClickListener(v -> showColorPicker(ctx, parentAct, prefs, key, label, cur[0], newColor -> {
            cur[0] = newColor;
            GradientDrawable dg = (GradientDrawable) ((View) v).getBackground();
            dg.setColor(newColor);
        }));
        row.addView(dot);

        return row;
    }

    private static void showColorPicker(Context ctx, Activity parentAct, SharedPreferences prefs,
                                         String key, String label, int current, OnColorPick cb) {
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding((int)(16*d), (int)(12*d), (int)(16*d), (int)(12*d));

        int cols = 5;
        int rows = (PRESET_COLORS.length + cols - 1) / cols;
        int cellSz = (int)(42*d);
        int gap = (int)(8*d);

        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            for (int c = 0; c < cols; c++) {
                int idx = r * cols + c;
                if (idx >= PRESET_COLORS.length) break;
                int pc = PRESET_COLORS[idx];

                LinearLayout cell = new LinearLayout(ctx);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setLayoutParams(new LinearLayout.LayoutParams(cellSz, cellSz));
                ((LinearLayout.LayoutParams)cell.getLayoutParams()).setMargins(gap/2, gap/2, gap/2, gap/2);

                View dot = new View(ctx);
                GradientDrawable dg = new GradientDrawable();
                dg.setShape(GradientDrawable.OVAL);
                dg.setColor(pc);
                dg.setStroke(pc == current ? (int)(3*d) : (int)(1*d), pc == current ? AppColors.accent() : 0xFFD0D0D0);
                dot.setBackground(dg);
                dot.setLayoutParams(new LinearLayout.LayoutParams(cellSz - (int)(8*d), cellSz - (int)(8*d)));

                TextView nm = new TextView(ctx);
                nm.setText(COLOR_NAMES[idx]);
                nm.setTextSize(9);
                nm.setTextColor(AppColors.text2());
                nm.setGravity(Gravity.CENTER);

                cell.addView(dot);
                cell.addView(nm);

                final int fi = idx;
                cell.setOnClickListener(v -> {
                    int sel = PRESET_COLORS[fi];
                    prefs.edit().putInt(key, sel).apply();
                    ThemeHook.loadColors();
                    if (cb != null) cb.onPick(sel);
                    SubPageActivity.reloadThemePage();
                });
                row.addView(cell);
            }
            grid.addView(row);
        }

        EditText hex = new EditText(ctx);
        hex.setHint("#AARRGGBB");
        hex.setTextSize(13);
        hex.setSingleLine(true);
        hex.setPadding((int)(8*d), (int)(6*d), (int)(8*d), (int)(6*d));
        hex.setBackgroundColor(0xFFF0F0F0);
        hex.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        ((LinearLayout.LayoutParams) hex.getLayoutParams()).setMargins(0, (int)(12*d), 0, 0);
        grid.addView(hex);

        TextView applyBtn = new TextView(ctx);
        applyBtn.setText("输入自定义颜色");
        applyBtn.setTextSize(13);
        applyBtn.setTextColor(AppColors.accent());
        applyBtn.setGravity(Gravity.CENTER);
        applyBtn.setPadding(0, (int)(6*d), 0, 0);
        applyBtn.setOnClickListener(v -> {
            try {
                int cval = (int) Long.parseLong(hex.getText().toString().trim().replace("#", ""), 16);
                prefs.edit().putInt(key, cval).apply();
                ThemeHook.loadColors();
                if (cb != null) cb.onPick(cval);
                SubPageActivity.reloadThemePage();
            } catch (Throwable ignored) {}
        });
        grid.addView(applyBtn);

        ScrollView wrap = new ScrollView(ctx);
        wrap.addView(grid);

        AlertDialog dlg = new AlertDialog.Builder(ctx)
            .setTitle("选择颜色: " + label)
            .setView(wrap)
            .setPositiveButton("关闭", null)
            .create();
        Window w = dlg.getWindow();
        if (w != null) {
            w.setGravity(Gravity.CENTER);
        }
        dlg.show();
    }

    interface OnColorPick { void onPick(int color); }

    private static void buildMonetConfig(Context ctx, Activity parentAct, SharedPreferences prefs,
                                          float d, LinearLayout root) {
        int seedColor = prefs.getInt("ls_monet_seed", 0xFFFF4298);
        int currIdx = prefs.getInt("ls_monet_style", 0);

        TextView info = new TextView(ctx);
        info.setText("选择配色风格后自动填充所有颜色");
        info.setTextSize(12);
        info.setTextColor(AppColors.text2());
        info.setPadding(0, 0, 0, (int)(8*d));
        root.addView(info);

        root.addView(colorRow(ctx, parentAct, prefs, d, "种子色", "ls_monet_seed", seedColor));

        root.addView(spacer(ctx, d, 8));

        int cols = 4;
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            row.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            for (int c = 0; c < cols; c++) {
                int idx = r * cols + c;
                if (idx >= MONET_NAMES.length) break;

                TextView btn = new TextView(ctx);
                btn.setText(MONET_NAMES[idx]);
                btn.setTextSize(10);
                btn.setAllCaps(false);
                btn.setGravity(Gravity.CENTER);
                btn.setPadding((int)(8*d), (int)(6*d), (int)(8*d), (int)(6*d));
                if (idx == currIdx) {
                    btn.setBackgroundColor(AppColors.accent());
                    btn.setTextColor(AppColors.whiteCard());
                } else {
                    btn.setBackgroundColor(0xFFE8E0F0);
                    btn.setTextColor(AppColors.text1());
                }
                btn.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
                ((LinearLayout.LayoutParams) btn.getLayoutParams()).setMargins((int)(2*d), (int)(2*d), (int)(2*d), (int)(2*d));

                final int fi = idx;
                btn.setOnClickListener(v -> {
                    if (!prefs.getBoolean("ls_theme_enabled", false)) {
                        prefs.edit().putBoolean("ls_theme_enabled", true).apply();
                        ThemeHook.setMasterEnabled(true);
                    }
                    int s = prefs.getInt("ls_monet_seed", 0xFFFF4298);
                    MonetColorEngine.Style st = MonetColorEngine.Style.fromIndex(fi);
                    int[] lightPalette = MonetColorEngine.generate(s, st);
                    int[] darkPalette = MonetColorEngine.generateDark(s, st);
                    ThemeHook.applyMonetPalette(lightPalette, darkPalette);
                    prefs.edit().putInt("ls_monet_style", fi).apply();
                    SubPageActivity.reloadThemePage();
                });
                row.addView(btn);
            }
            root.addView(row);
        }
    }

    private static void buildImageBubbleConfig(Context ctx, Activity parentAct, SharedPreferences prefs,
                                                float d, LinearLayout root) {
        String curPath = prefs.getString("ls_bubble_image", "");
        boolean hasImg = curPath != null && !curPath.isEmpty();

        TextView hint = new TextView(ctx);
        hint.setText("选择一张图片作为聊天气泡背景");
        hint.setTextSize(12);
        hint.setTextColor(AppColors.text2());
        hint.setPadding(0, 0, 0, (int)(8*d));
        root.addView(hint);

        if (hasImg) {
            String shortPath = curPath;
            if (shortPath.contains("/")) {
                String[] parts = shortPath.split("/");
                shortPath = parts[parts.length - 1];
            }
            TextView cur = new TextView(ctx);
            cur.setText("当前: " + shortPath);
            cur.setTextSize(12);
            cur.setTextColor(AppColors.accent());
            cur.setPadding(0, 0, 0, (int)(8*d));
            root.addView(cur);
        }

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER);

        TextView pickBtn = new TextView(ctx);
        pickBtn.setText("选择文件");
        pickBtn.setTextSize(14);
        pickBtn.setTextColor(AppColors.whiteCard());
        pickBtn.setBackgroundColor(AppColors.accent());
        pickBtn.setPadding((int)(20*d), (int)(10*d), (int)(20*d), (int)(10*d));
        GradientDrawable pbg = new GradientDrawable();
        pbg.setCornerRadius(dp(d, 8));
        pbg.setColor(AppColors.accent());
        pickBtn.setBackground(pbg);
        pickBtn.setOnClickListener(v -> {
            try {
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                parentAct.startActivityForResult(intent, 9001);
            } catch (Throwable t) {
                android.widget.Toast.makeText(ctx, "无法打开文件管理器", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        btns.addView(pickBtn);

        if (hasImg) {
            TextView clearBtn = new TextView(ctx);
            clearBtn.setText("清除");
            clearBtn.setTextSize(14);
            clearBtn.setTextColor(0xFFE04040);
            clearBtn.setPadding((int)(20*d), (int)(10*d), (int)(20*d), (int)(10*d));
            clearBtn.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
            ((LinearLayout.LayoutParams) clearBtn.getLayoutParams()).setMargins((int)(12*d), 0, 0, 0);
            clearBtn.setOnClickListener(v -> ThemeHook.setBubbleImage(null));
            btns.addView(clearBtn);
        }

        root.addView(spacer(ctx, d, 6));
        root.addView(btns);
    }

    private static View section(Context ctx, float d, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(AppColors.text2());
        tv.setPadding(0, 0, 0, (int)(6*d));
        return tv;
    }

    private static View spacer(Context ctx, float d, int dpVal) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(dpVal * d)));
        return v;
    }

    private static int dp(float d, int v) { return (int)(v * d + 0.5f); }
}
