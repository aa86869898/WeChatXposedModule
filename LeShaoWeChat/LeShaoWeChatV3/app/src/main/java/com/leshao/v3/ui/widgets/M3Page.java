package com.leshao.v3.ui.widgets;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.CandyUi;
import com.leshao.v3.ui.InsetsUtil;

/**
 * M3 页面构建工具箱（v955）—— 全部页面深度重排的统一结构件：
 *  root(渐变→surface  ScrollView) / section(分区标题) / card(卡片容器)
 *  switchRow(开关行) / clickRow(导航行) / textRow(信息行) / divider(内分割线)
 *  button(按钮) / input(输入框) / spacer(间距)
 * 每个页面按 M3 规范：16dp 页边距、12dp 圆角卡片、分区标题、行高 56dp、状态层按压。
 */
public final class M3Page {

    private M3Page() {}

    private static float density(Context ctx) {
        return ctx.getResources().getDisplayMetrics().density;
    }

    private static int dp(Context ctx, float v) {
        return (int) (v * density(ctx) + 0.5f);
    }

    // ==================== 页面骨架 ====================

    /** 页面根：外层透明壳 + 内部 surface 圆角浮层（内容全部挂在内层）。 */
    public static LinearLayout root(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CandyUi.pageGradient());
        InsetsUtil.clipRounded(root);
        int m = dp(ctx, 16);
        root.setPadding(m, dp(ctx, 12), m, dp(ctx, 24));
        return root;
    }

    /** 可滚动页面容器（根已内含） */
    public static ScrollView scroll(Context ctx, LinearLayout root) {
        ScrollView sv = new ScrollView(ctx);
        sv.setFillViewport(true);
        sv.addView(InsetsUtil.host(root));
        return sv;
    }

    /** 分区标题（M3 list subheader） */
    public static View section(Context ctx, String title) {
        return section(ctx, title, null);
    }

    public static View section(Context ctx, String title, String sub) {
        return new SectionHeader(ctx, title, sub);
    }

    /** 卡片容器（M3 filled card：12dp 圆角 + surfaceContainerLow 底） */
    public static LinearLayout card(Context ctx) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(CandyUi.cardBg(ctx));
        InsetsUtil.clipRounded(card);
        int p = dp(ctx, 4);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(ctx, 8));
        card.setLayoutParams(lp);
        return card;
    }

    /** 卡片内垂直间距 */
    public static View cardSpacer(Context ctx, float dp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(ctx, dp)));
        return v;
    }

    /** M3 内分割线（左侧留出图标宽度） */
    public static View divider(Context ctx) {
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
        lp.setMargins(dp(ctx, 16), 0, 0, 0);
        v.setLayoutParams(lp);
        v.setBackgroundColor(AppColors.outlineVariant());
        return v;
    }

    // ==================== 行 ====================

    /** 开关行：图标 + 标题 + 副标题 + M3 开关 */
    public static View switchRow(Context ctx, String icon, String title, String sub,
                                 boolean checked, CompoundButton.OnCheckedChangeListener l) {
        return new SettingRow(ctx, icon, title, sub).switchOn(checked, l);
    }

    /** 导航行：图标 + 标题 + 副标题 + 箭头（点击） */
    public static View clickRow(Context ctx, String icon, String title, String sub, Runnable onClick) {
        return new SettingRow(ctx, icon, title, sub).arrow(onClick);
    }

    /** 尾部自定义控件行 */
    public static View tailRow(Context ctx, String icon, String title, String sub, View tail) {
        return new SettingRow(ctx, icon, title, sub).tail(tail);
    }

    /** 创建导航行并追加到卡片, 返回可更新副标题的行对象。 */
    public static SettingRow appendClickRow(LinearLayout card, Context ctx, String icon,
                                            String title, String sub, Runnable onClick) {
        SettingRow row = new SettingRow(ctx, icon, title, sub);
        if (onClick != null) {
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try { onClick.run(); } catch (Throwable ignored) {}
                }
            });
        }
        if (card != null) card.addView(row);
        return row;
    }

    /** 创建开关行并追加到卡片, 返回底层 Switch 以便读取状态。 */
    public static Switch appendSwitchRow(LinearLayout card, Context ctx, String icon, String title,
                                         String sub, boolean checked,
                                         CompoundButton.OnCheckedChangeListener listener) {
        final Switch sw = CandyUi.newSwitch(ctx);
        sw.setChecked(checked);
        if (listener != null) sw.setOnCheckedChangeListener(listener);
        float d = density(ctx);
        int w = (int) (AppColors.SWITCH_WIDTH_DP * d + 0.5f);
        int h = (int) (AppColors.SWITCH_HEIGHT_DP * d + 0.5f);
        SettingRow row = new SettingRow(ctx, icon, title, sub);
        row.tail(sw);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try { sw.toggle(); } catch (Throwable ignored) {}
            }
        });
        if (card != null) card.addView(row);
        return sw;
    }

    /** 底部等宽双按钮行。 */
    public static View buttonRow(Context ctx, View left, View right) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lpL = new LinearLayout.LayoutParams(0, -2, 1f);
        lpL.setMargins(0, dp(ctx, 16), dp(ctx, 4), 0);
        LinearLayout.LayoutParams lpR = new LinearLayout.LayoutParams(0, -2, 1f);
        lpR.setMargins(dp(ctx, 4), dp(ctx, 16), 0, 0);
        left.setLayoutParams(lpL);
        right.setLayoutParams(lpR);
        row.addView(left);
        row.addView(right);
        return row;
    }

    /** 纯信息行（标题 + 右侧值文字） */
    public static View infoRow(Context ctx, String title, String value) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(ctx, 16);
        row.setPadding(p, dp(ctx, 14), p, dp(ctx, 14));
        row.setBackground(CandyUi.rowPressBg(ctx));

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(AppColors.onSurface());
        t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(t);

        if (!TextUtils.isEmpty(value)) {
            TextView v = new TextView(ctx);
            v.setText(value);
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            v.setTextColor(AppColors.onSurfaceVariant());
            v.setSingleLine(true);
            v.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(v);
        }
        return row;
    }

    // ==================== 控件 ====================

    /** M3 filled button */
    public static View button(Context ctx, String text, Runnable onClick) {
        return new ModernButton(ctx, text, ModernButton.STYLE_PRIMARY).onClick(onClick);
    }

    /** M3 outlined button */
    public static View ghostButton(Context ctx, String text, Runnable onClick) {
        return new ModernButton(ctx, text, ModernButton.STYLE_GHOST).onClick(onClick);
    }

    /** M3 danger button */
    public static View dangerButton(Context ctx, String text, Runnable onClick) {
        return new ModernButton(ctx, text, ModernButton.STYLE_DANGER).onClick(onClick);
    }

    /** M3 filled text field */
    public static android.widget.EditText input(Context ctx, String hint) {
        android.widget.EditText et = new android.widget.EditText(ctx);
        et.setHint(hint);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        et.setTextColor(AppColors.onSurface());
        et.setHintTextColor(AppColors.onSurfaceVariant());
        et.setSingleLine(true);
        // 长内容(如接口地址/密钥)支持左右拖动查看, 不被截断
        et.setHorizontallyScrolling(true);
        et.setBackground(CandyUi.inputBg(ctx));
        int p = dp(ctx, 14);
        et.setPadding(p, dp(ctx, 12), p, dp(ctx, 12));
        return et;
    }

    /**
     * 为单行输入框附加「自动剔除首尾空白」能力。
     *
     * <p>从网页/聊天窗口复制密钥或接口地址时, 首尾常带空格、换行、不可换行空格(U+00A0)、
     * 全角空格(U+3000)、零宽空格(U+200B/U+FEFF) 等, 输入框内即时清理, 避免因多余字符
     * 导致鉴权失败。仅处理首尾, 不改动中间内容。</p>
     */
    public static void trimEdgesOnInput(final android.widget.EditText et) {
        if (et == null) return;
        et.setHorizontallyScrolling(true);
        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (s == null) return;
                int len = s.length();
                int start = 0, end = len;
                while (start < end && isEdgeBlank(s.charAt(start))) start++;
                while (end > start && isEdgeBlank(s.charAt(end - 1))) end--;
                if (start == 0 && end == len) return;
                CharSequence cleaned = s.subSequence(start, end);
                et.setText(cleaned);
                et.setSelection(cleaned.length());
            }
        });
    }

    /** 首尾需剔除的空白/不可见字符 */
    private static boolean isEdgeBlank(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r'
                || c == '\u00A0' || c == '\u3000' || c == '\u200B' || c == '\uFEFF';
    }

    /** 页面区块间距 */
    public static View spacer(Context ctx, float dp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(ctx, dp)));
        return v;
    }

    // ==================== 文本排版（M3 type scale） ====================

    /** 页面/弹窗标题（M3 title large · onSurface · 粗体） */
    public static TextView title(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(AppColors.onSurface());
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, 0, 0, dp(ctx, 6));
        return tv;
    }

    /** 字段标签（M3 label medium · onSurfaceVariant，置于输入框上方） */
    public static TextView fieldLabel(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        // v998: 字段标签再缩小 3dp
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tv.setTextColor(AppColors.onSurfaceVariant());
        tv.setPadding(dp(ctx, 2), dp(ctx, 6), 0, dp(ctx, 2));
        return tv;
    }

    /** 说明/提示段落（M3 body small · onSurfaceVariant） */
    public static TextView note(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        // v998: 说明段落再缩小 3dp
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        tv.setTextColor(AppColors.onSurfaceVariant());
        tv.setLineSpacing(dp(ctx, 2), 1.1f);
        tv.setPadding(dp(ctx, 2), dp(ctx, 4), dp(ctx, 2), dp(ctx, 8));
        return tv;
    }

    // ==================== 控件 ====================

    /** M3 滑杆：primary 进度/滑块 + surfaceContainerHighest 轨道 */
    public static SeekBar slider(Context ctx) {
        SeekBar sb = new SeekBar(ctx);
        try {
            sb.setProgressTintList(ColorStateList.valueOf(AppColors.primary()));
            sb.setThumbTintList(ColorStateList.valueOf(AppColors.primary()));
            sb.setProgressBackgroundTintList(
                    ColorStateList.valueOf(AppColors.surfaceContainerHighest()));
            sb.setProgressTintMode(android.graphics.PorterDuff.Mode.SRC_IN);
        } catch (Throwable ignored) {}
        sb.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8));
        return sb;
    }

    /**
     * 可勾选列表行（M3 list item）：图标 + 标题 + 副标题 + 尾部圆形勾选标记，
     * 点击整行切换勾选态并回调。</p>
     *
     * @param checked  初始勾选态
     * @param listener 勾选变化回调（可空）
     */
    public static SettingRow checkRow(Context ctx, String icon, String title, String sub,
                                      boolean checked, final CheckListener listener) {
        final SettingRow row = new SettingRow(ctx, icon, title, sub);
        final TextView mark = new TextView(ctx);
        final boolean[] state = {checked};
        int size = dp(ctx, 24);
        mark.setGravity(Gravity.CENTER);
        mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mark.setTypeface(Typeface.DEFAULT_BOLD);
        mark.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                if (state[0]) {
                    bg.setColor(AppColors.primary());
                    mark.setText("✓");
                    mark.setTextColor(AppColors.onPrimary());
                } else {
                    bg.setColor(0x00000000);
                    bg.setStroke(dp(ctx, 2), AppColors.outline());
                    mark.setText("");
                }
                mark.setBackground(bg);
            }
        };
        refresh.run();
        row.tail(mark);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state[0] = !state[0];
                refresh.run();
                if (listener != null) listener.onChanged(state[0]);
            }
        });
        return row;
    }

    /** 勾选行回调（避免依赖 CompoundButton 的 buttonView 参数）。 */
    public interface CheckListener {
        void onChanged(boolean checked);
    }

    /**
     * M3 风格复选框：primary 勾选色 + onSurface 文案，供弹窗/滚动列表内单独使用。
     * 返回原生 CheckBox 以便沿用 setChecked/isChecked/setOnCheckedChangeListener。
     */
    public static android.widget.CheckBox checkBox(Context ctx, String text) {
        android.widget.CheckBox cb = new android.widget.CheckBox(ctx);
        if (text != null) cb.setText(text);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cb.setTextColor(AppColors.onSurface());
        try {
            cb.setButtonTintList(ColorStateList.valueOf(AppColors.primary()));
        } catch (Throwable ignored) {}
        cb.setPadding(0, dp(ctx, 6), 0, dp(ctx, 6));
        return cb;
    }

    public static android.widget.CheckBox checkBox(Context ctx) {
        return checkBox(ctx, null);
    }

    /** 空状态 */
    public static View empty(Context ctx, String icon, String msg) {
        return new EmptyView(ctx, icon, msg);
    }

    /** M3 snackbar 提示 */
    public static void toast(Context ctx, String msg) {
        ToastHelper.show(ctx, msg);
    }

    public static void toastSuccess(Context ctx, String msg) {
        ToastHelper.success(ctx, msg);
    }

    public static void toastError(Context ctx, String msg) {
        ToastHelper.error(ctx, msg);
    }
}
