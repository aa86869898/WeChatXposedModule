package com.leshao.v3.ui.widgets;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.CandyUi;

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

    /** 页面根：竖向 LinearLayout（surface 底 + 16dp 左右边距 + 底部留白） */
    public static LinearLayout root(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CandyUi.pageGradient());
        int m = dp(ctx, 16);
        root.setPadding(m, dp(ctx, 12), m, dp(ctx, 24));
        return root;
    }

    /** 可滚动页面容器（根已内含） */
    public static ScrollView scroll(Context ctx, LinearLayout root) {
        ScrollView sv = new ScrollView(ctx);
        sv.setFillViewport(true);
        sv.addView(root);
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
