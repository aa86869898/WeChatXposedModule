package com.leshao.v3.ui.widgets;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.CandyUi;
import com.leshao.v3.ui.InsetsUtil;

/**
 * v3.0.166 自定义 HSV 取色器（任务3：去掉格子取色，改交互式取色，实时生效）。
 *
 * <p>不依赖任何第三方库：纯 View 绘制「色相条 + 饱和度/亮度二维面板 + 预览 + hex 回显」。
 * 拖动过程中实时回调 {@link OnPick#onPick(int)}（每次松手或节流后即触发），
 * 无需点「确定」即可在聊天窗口立即看到颜色变化。</p>
 *
 * <p>回调 {@code color == 0} 表示"不修改/恢复默认"。确认按钮保留，便于取到精确色后再提交。</p>
 */
public final class ColorPickerDialog {

    public interface OnPick {
        /** @param color 0 = 不修改(恢复微信原生)。实时回调时会不断携带当前色。 */
        void onPick(int color);
    }

    /** v3.0.205：实时预览回调 —— 拖动 SV 面板/色相条时高频触发（无节流原始频率）。
     *  用于即时应用到已渲染视图（不重建页面），最终确认仍走 {@link OnPick#onPick}。 */
    public interface OnPreview {
        void onPreview(int color);
    }

    private static final float HUE_BAR_DP = 26f;
    private static final float SV_PANEL_DP = 220f;
    private static final float THUMB_DP = 12f;

    private ColorPickerDialog() {}

    public static void show(Context ctx, String title, int initialColor,
                            boolean allowDefault, final OnPick cb) {
        show(ctx, title, initialColor, allowDefault, cb, null);
    }

    public static void show(Context ctx, String title, int initialColor,
                            boolean allowDefault, final OnPick cb, final OnPreview preview) {
        if (ctx == null) return;
        final float d = ctx.getResources().getDisplayMetrics().density;
        int dlgTheme = AppColors.isDarkMode()
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        final AlertDialog dialog = new AlertDialog.Builder(ctx, dlgTheme).create();

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * d);
        root.setPadding(pad, (int) (14 * d), pad, (int) (8 * d));
        root.setBackground(CandyUi.dialogBg(ctx));
        InsetsUtil.clipRounded(root);

        TextView titleTv = new TextView(ctx);
        titleTv.setText(title == null ? "选择颜色" : title);
        titleTv.setTextSize(16);
        titleTv.setTextColor(AppColors.text1());
        titleTv.setTypeface(null, Typeface.BOLD);
        titleTv.setPadding(0, 0, 0, (int) (12 * d));
        root.addView(titleTv);

        // ---- 饱和度/亮度 二维面板 + 色相条（实时绘制） ----
        final float[] hsv = new float[3];
        {
            int initial = initialColor == 0 ? 0xFF000000 : initialColor;
            Color.colorToHSV(initial, hsv);
        }

        final SvPanel svPanel = new SvPanel(ctx);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (SV_PANEL_DP * d));
        svPanel.setLayoutParams(svLp);
        root.addView(svPanel);

        root.addView(spacer(ctx, d, 8));

        final HueBar hueBar = new HueBar(ctx);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (HUE_BAR_DP * d));
        hueBar.setLayoutParams(hLp);
        root.addView(hueBar);

        // 联动模型：hue 由 HueBar 提供，s/v 由 SvPanel 提供；任一变化重算 hsv/color 并回调
        final int[] current = {Color.HSVToColor(hsv)};
        // v3.0.206：程序内回显 hex 时的同步标志，避免 afterTextChanged 反推 hsv 把面板状态覆盖回去
        final boolean[] syncingHex = {false};
        Runnable repaint = () -> { svPanel.invalidate(); hueBar.invalidate(); };

        // ---- 预览：色块 + hex 文本 ----
        LinearLayout previewRow = new LinearLayout(ctx);
        previewRow.setOrientation(LinearLayout.HORIZONTAL);
        previewRow.setGravity(Gravity.CENTER_VERTICAL);

        final View swatch = new View(ctx);
        int sw = (int) (34 * d);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(sw, sw);
        swatch.setLayoutParams(swLp);
        previewRow.addView(swatch);

        final TextView hexTv = new TextView(ctx);
        hexTv.setTextSize(15);
        hexTv.setTextColor(AppColors.text1());
        hexTv.setPadding((int) (12 * d), 0, 0, 0);
        previewRow.addView(hexTv);
        root.addView(previewRow);

        root.addView(spacer(ctx, d, 12));

        // 十六进制输入
        final EditText hexInput = new EditText(ctx);
        hexInput.setText(toHex(current[0]));
        hexInput.setHint("#RRGGBB");
        hexInput.setTextSize(14);
        hexInput.setSingleLine(true);
        hexInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hexInput.setTextColor(AppColors.text1());
        hexInput.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(AppColors.inputBg());
        inputBg.setCornerRadius((int) (AppColors.SHAPE_INPUT_DP * d));
        inputBg.setStroke((int) (1.5f * d), AppColors.outlineVariant());
        hexInput.setBackground(inputBg);
        root.addView(hexInput);

        root.addView(spacer(ctx, d, 10));

        // 按钮行
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);

        if (allowDefault) {
            TextView def = textBtn(ctx, d, "默认", AppColors.text2(), false);
            def.setOnClickListener(v -> {
                if (cb != null) cb.onPick(0);
                dialog.dismiss();
            });
            btnRow.addView(def);
        }
        View filler = new View(ctx);
        filler.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        btnRow.addView(filler);

        TextView cancel = textBtn(ctx, d, "取消", AppColors.text2(), false);
        cancel.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(cancel);

        TextView confirm = textBtn(ctx, d, "确定", AppColors.accent(), true);
        confirm.setOnClickListener(v -> {
            int c = parseHex(hexInput.getText().toString(), current[0]);
            if (cb != null) cb.onPick(c);
            dialog.dismiss();
        });
        btnRow.addView(confirm);
        root.addView(btnRow);

        Runnable firePick = () -> {
            int c = Color.HSVToColor(hsv);
            current[0] = c;
            hexTv.setText(toHex(c));
            syncingHex[0] = true;
            try { hexInput.setText(toHex(c)); } finally { syncingHex[0] = false; }
            swatch.setBackground(swatchBg(c, d));
            // v3.0.205：拖动实时回调走 OnPreview（不重建页面），确认「确定」才走 OnPick。
            if (preview != null) { preview.onPreview(c); }
            else if (cb != null) cb.onPick(c);
        };

        svPanel.setListener(h -> {
            hsv[1] = h[0];
            hsv[2] = h[1];
            repaint.run();
            firePick.run();
        });
        hueBar.setListener(hue -> {
            hsv[0] = hue;
            // v3.0.207：同步色相到 SV 面板底色 —— SvPanel.onDraw 用内部 mHue 画 S/V 渐变，
            // 仅 invalidate 不 setHue 会导致拖色相条时面板底色纹丝不动。
            svPanel.setHue(hue);
            repaint.run();
            firePick.run();
        });
        hueBar.setHue(hsv[0]);
        svPanel.setHue(hsv[0]);
        svPanel.setSv(hsv[1], hsv[2]);

        // 输入联动预览
        hexInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                // v3.0.206：拖动 SV/色相条时 firePick 会 setText 回显 hex，
                // 程序内同步不重复回写 hsv（否则会把刚拖动的值反推覆盖回去，导致横条"点不动"、预览不变）。
                if (syncingHex[0]) return;
                Integer c = tryParse(s == null ? null : s.toString());
                if (c == null) return;
                current[0] = c;
                Color.colorToHSV(c, hsv);
                hueBar.setHue(hsv[0]);
                svPanel.setHue(hsv[0]);
                svPanel.setSv(hsv[1], hsv[2]);
                hexTv.setText(toHex(c));
                swatch.setBackground(swatchBg(c, d));
                svPanel.invalidate();
                hueBar.invalidate();
                if (preview != null) { preview.onPreview(c); }
                else if (cb != null) cb.onPick(c);
            }
        });

        swatch.setBackground(swatchBg(current[0], d));
        hexTv.setText(toHex(current[0]));

        dialog.setView(root);
        InsetsUtil.transparentWindow(dialog);
        dialog.show();
    }

    // ==================== HSV 控件 ====================

    /** 饱和度(S)/亮度(V) 二维面板。 */
    private static final class SvPanel extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mThumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mThumbStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float mHue = 0f;
        private float mS = 0f;   // 0..1
        private float mV = 1f;   // 0..1
        private Listener mListener;

        interface Listener { void onChanged(float[] sv); }

        SvPanel(Context ctx) {
            super(ctx);
            mThumb.setStyle(Paint.Style.FILL);
            mThumb.setColor(Color.WHITE);
            mThumbStroke.setStyle(Paint.Style.STROKE);
            mThumbStroke.setColor(0x55000000);
            mThumbStroke.setStrokeWidth(dp(1.5f));
        }

        private float dp(float v) {
            return v * getResources().getDisplayMetrics().density;
        }

        void setHue(float hue) {
            mHue = hue;
            invalidate();
        }

        void setSv(float s, float v) {
            mS = s;
            mV = v;
            invalidate();
        }

        void setListener(Listener l) { mListener = l; }

        private float[] xyFromSV() {
            float w = getWidth() - dp(4);
            float h = getHeight() - dp(4);
            return new float[]{dp(2) + mS * w, dp(2) + (1f - mV) * h};
        }

        private void svFromTouch(float x, float y) {
            float w = getWidth() - dp(4);
            float h = getHeight() - dp(4);
            mS = w <= 0 ? 0 : Math.max(0f, Math.min(1f, (x - dp(2)) / w));
            mV = h <= 0 ? 1 : Math.max(0f, Math.min(1f, 1f - (y - dp(2)) / h));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            // S 方向：白 → 纯色；V 方向：黑 → 纯色
            int base = Color.HSVToColor(new float[]{mHue, 1f, 1f});
            LinearGradient sg = new LinearGradient(dp(2), 0, w - dp(2), 0,
                    new int[]{Color.WHITE, base}, null, Shader.TileMode.CLAMP);
            LinearGradient vg = new LinearGradient(0, dp(2), 0, h - dp(2),
                    new int[]{0xFF000000, 0x00000000}, null, Shader.TileMode.CLAMP);
            Paint bg = new Paint();
            bg.setShader(sg);
            canvas.drawRect(dp(2), dp(2), w - dp(2), h - dp(2), bg);
            Paint vp = new Paint();
            vp.setShader(vg);
            canvas.drawRect(dp(2), dp(2), w - dp(2), h - dp(2), vp);

            float[] xy = xyFromSV();
            float r = dp(THUMB_DP / 2);
            canvas.drawCircle(xy[0], xy[1], r, mThumbStroke);
            canvas.drawCircle(xy[0], xy[1], r - dp(1.2f), mThumb);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            float x = ev.getX();
            float y = ev.getY();
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (mListener != null) {
                        com.leshao.v3.LogWriter.log("ColorPicker", "SvPanel touch DOWN at " + (int)x + "," + (int)y);
                    }
                    // fall through
                case MotionEvent.ACTION_MOVE:
                    svFromTouch(x, y);
                    invalidate();
                    // v3.0.206：回调加保护，回调异常不回传中断触摸（否则拖动会"点不动"）
                    if (mListener != null) {
                        try { mListener.onChanged(new float[]{mS, mV}); }
                        catch (Throwable ignored) {}
                    }
                    return true;
                default:
                    return super.onTouchEvent(ev);
            }
        }
    }

    /** 色相条。 */
    private static final class HueBar extends View {
        private final Paint mThumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mThumbStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float mHue = 0f;
        private Listener mListener;

        interface Listener { void onChanged(float hue); }

        HueBar(Context ctx) {
            super(ctx);
            mThumb.setStyle(Paint.Style.FILL);
            mThumb.setColor(Color.WHITE);
            mThumbStroke.setStyle(Paint.Style.STROKE);
            mThumbStroke.setColor(0x55000000);
            mThumbStroke.setStrokeWidth(dp(1.5f));
        }

        private float dp(float v) {
            return v * getResources().getDisplayMetrics().density;
        }

        void setHue(float hue) { mHue = hue; invalidate(); }

        void setListener(Listener l) { mListener = l; }

        private float thumbX() {
            float w = getWidth() - dp(2);
            return dp(1) + (mHue / 360f) * w;
        }

        private void hueFromTouch(float x) {
            float w = getWidth() - dp(2);
            mHue = w <= 0 ? 0 : Math.max(0f, Math.min(360f, ((x - dp(1)) / w) * 360f));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            int n = 12;
            int[] colors = new int[n];
            float[] stops = new float[n];
            for (int i = 0; i < n; i++) {
                stops[i] = i / (float) (n - 1);
                colors[i] = Color.HSVToColor(new float[]{stops[i] * 360f, 1f, 1f});
            }
            LinearGradient g = new LinearGradient(dp(1), 0, w - dp(1), 0,
                    colors, stops, Shader.TileMode.CLAMP);
            Paint bg = new Paint();
            bg.setShader(g);
            canvas.drawRect(dp(1), 0, w - dp(1), h, bg);
            float x = thumbX();
            float r = dp(THUMB_DP / 2);
            canvas.drawCircle(x, h / 2f, r, mThumbStroke);
            canvas.drawCircle(x, h / 2f, r - dp(1.2f), mThumb);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (mListener != null) {
                        com.leshao.v3.LogWriter.log("ColorPicker", "HueBar touch DOWN at " + (int)ev.getX());
                    }
                    // fall through
                case MotionEvent.ACTION_MOVE:
                    hueFromTouch(ev.getX());
                    invalidate();
                    // v3.0.206：回调加保护，回调异常不回传中断触摸
                    if (mListener != null) {
                        try { mListener.onChanged(mHue); }
                        catch (Throwable ignored) {}
                    }
                    return true;
                default:
                    return super.onTouchEvent(ev);
            }
        }
    }

    // ==================== 通用 ====================

    private static View spacer(Context ctx, float d, float dp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int) (dp * d)));
        return v;
    }

    private static TextView textBtn(Context ctx, float d, String text, int color, boolean bold) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), (int) (8 * d));
        CandyUi.ripple(tv, AppColors.SHAPE_FULL_DP);
        return tv;
    }

    private static GradientDrawable swatchBg(int color, float d) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(color);
        gd.setCornerRadius((int) (7 * d));
        gd.setStroke((int) (1 * d), 0x33000000);
        return gd;
    }

    private static String toHex(int color) {
        return String.format("#%06X", 0xFFFFFF & color);
    }

    private static Integer tryParse(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        if (!t.startsWith("#")) t = "#" + t;
        try {
            return Color.parseColor(t);
        } catch (Throwable t2) {
            return null;
        }
    }

    private static int parseHex(String s, int fallback) {
        Integer c = tryParse(s);
        return c != null ? c : fallback;
    }
}
