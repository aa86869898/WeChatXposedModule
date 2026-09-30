package com.leshao.v3.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;

/**
 * v1105: 在线音乐「悬浮球」控制条。
 *
 * <p>播放音乐后关闭在线音乐页面时，在宿主 Activity 上叠加一个贴边小圆球：
 * 空闲时缩回屏幕侧边只露半圆，点击拉出，再点展开「上一首 / 播放暂停 / 下一首 / ×」；
 * 无操作 5 秒自动侧边休眠（缩回只露半圆）；无播放时自动消失。
 * × 关闭悬浮球（不停止播放），再次进入在线音乐并返回时会重新出现。</p>
 *
 * <p>外观跟随模块「葡萄气泡」主题（紫 → 淡紫 → 粉渐变），浅色/暗色模式自动适配。</p>
 *
 * <p>使用 {@link WindowManager.LayoutParams#TYPE_APPLICATION_PANEL} 挂到 Activity 的
 * WindowManager，无需系统悬浮窗权限，随 Activity 一起销毁。</p>
 */
public final class MusicFloatBall {

    private MusicFloatBall() {}

    private static final int BALL_DP = 32;
    private static final int BTN_DP  = 34;

    private static final int ST_RETRACT = 0;
    private static final int ST_OUT     = 1;
    private static final int ST_OPEN    = 2;

    /** 无操作后自动缩回侧边的等待时长。 */
    private static final long AUTO_RETRACT_MS = 5000L;

    private static int sState = ST_RETRACT;

    private static LinearLayout sRoot;
    private static LinearLayout sPanel;
    private static EqIconView sBall;
    private static IconView sPlayPause, sPrev, sNext, sClose;
    private static WindowManager sWM;
    private static Activity sAct;
    private static int sMiss;
    private static int sScheme = -1;
    private static ValueAnimator sAnim;
    private static Runnable sThemeCb;

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Runnable POLL = new Runnable() {
        @Override public void run() {
            sync();
            if (sRoot != null) H.postDelayed(this, 1500);
        }
    };
    private static final Runnable AUTO_RETRACT = () -> {
        if (sRoot != null && sState != ST_RETRACT) applyState(ST_RETRACT, true);
    };

    public static void show(Activity act) {
        if (act == null) return;
        if (Build.VERSION.SDK_INT >= 17 && act.isDestroyed()) return;
        if (act.isFinishing()) return;
        H.post(() -> {
            try {
                if (sRoot != null && sAct == act) { sync(); return; }
                if (sRoot != null) remove();
                build(act);
            } catch (Throwable ignored) {
            }
        });
    }

    public static void hide() {
        H.post(MusicFloatBall::remove);
    }

    private static void remove() {
        H.removeCallbacks(POLL);
        H.removeCallbacks(AUTO_RETRACT);
        if (sAnim != null) { try { sAnim.cancel(); } catch (Throwable ignored) {} sAnim = null; }
        if (sThemeCb != null) { try { AppColors.removeThemeListener(sThemeCb); } catch (Throwable ignored) {} sThemeCb = null; }
        View b = sRoot;
        WindowManager wm = sWM;
        sRoot = null;
        sPanel = null;
        sBall = null;
        sPlayPause = null;
        sPrev = null;
        sNext = null;
        sClose = null;
        sWM = null;
        sAct = null;
        sMiss = 0;
        sScheme = -1;
        sState = ST_RETRACT;
        try { if (wm != null && b != null) wm.removeViewImmediate(b); } catch (Throwable ignored) {}
    }

    private static void build(Activity act) {
        sAct = act;
        WindowManager wm = act.getWindowManager();
        if (wm == null) return;
        sWM = wm;
        Context ctx = act;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);

        // 控制条（默认隐藏，展开时出现在球的左侧）
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        int pp = dp(ctx, 4);
        panel.setPadding(pp, pp, pp, pp);
        panel.setElevation(dp(ctx, 8));
        panel.setVisibility(View.GONE);

        sPlayPause = iconBtn(ctx, IconView.PAUSE, v -> { OnlineMusicPageView.togglePlayback(); sync(); });
        sPrev = iconBtn(ctx, IconView.PREV, v -> OnlineMusicPageView.prevPlayback());
        sNext = iconBtn(ctx, IconView.NEXT, v -> OnlineMusicPageView.nextPlayback());
        sClose = iconBtn(ctx, IconView.CLOSE, v -> hide());
        panel.addView(sPrev);
        panel.addView(sPlayPause);
        panel.addView(sNext);
        panel.addView(sClose);

        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.rightMargin = dp(ctx, 8);
        panel.setLayoutParams(plp);

        // 圆球（声波音柱图标 + 三色渐变）
        EqIconView ball = new EqIconView(ctx);
        int bs = dp(ctx, BALL_DP);
        ball.setLayoutParams(new LinearLayout.LayoutParams(bs, bs));
        ball.setElevation(dp(ctx, 6));
        ball.setOnClickListener(v -> {
            if (sState == ST_RETRACT) applyState(ST_OUT, true);
            else if (sState == ST_OUT) applyState(ST_OPEN, true);
            else applyState(ST_RETRACT, true);
        });

        root.addView(panel);
        root.addView(ball);

        // 拖动：仅垂直移动；未超过阈值时交还子 View 处理点击
        DragTouch drag = new DragTouch();
        root.setOnTouchListener(drag);
        panel.setOnTouchListener(drag);
        ball.setOnTouchListener(drag);
        for (int i = 0; i < panel.getChildCount(); i++) {
            panel.getChildAt(i).setOnTouchListener(drag);
        }

        sRoot = root;
        sPanel = panel;
        sBall = ball;
        sState = ST_RETRACT;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.RIGHT;
        lp.x = dp(ctx, BALL_DP / 2f);           // 缩回侧边：只露半圆
        lp.y = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.42f);

        try {
            wm.addView(root, lp);
        } catch (Throwable t) {
            sRoot = null;
            sPanel = null;
            sBall = null;
            sPlayPause = null;
            sPrev = null;
            sNext = null;
            sClose = null;
            sWM = null;
            sAct = null;
            return;
        }

        applyColors();

        sThemeCb = () -> H.post(MusicFloatBall::applyColors);
        try { AppColors.addThemeListener(sThemeCb); } catch (Throwable ignored) {}

        sMiss = 0;
        H.removeCallbacks(POLL);
        H.postDelayed(POLL, 1500);
        sync();
    }

    /** 按当前深浅模式重刷配色（葡萄气泡主题）。 */
    private static void applyColors() {
        EqIconView ball = sBall;
        if (ball == null) return;
        Context ctx = ball.getContext();
        try {
            GradientDrawable bg = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR, AppColors.gradientColors());
            bg.setShape(GradientDrawable.OVAL);
            ball.setBackground(bg);

            LinearLayout panel = sPanel;
            if (panel != null) {
                GradientDrawable pg = new GradientDrawable();
                pg.setColor(AppColors.surfaceContainerHighest());
                pg.setCornerRadius(dp(ctx, 20));
                pg.setStroke(dp(ctx, 1), AppColors.outlineVariant());
                panel.setBackground(pg);
            }
            if (sPrev != null) sPrev.setColor(AppColors.primary());
            if (sPlayPause != null) sPlayPause.setColor(AppColors.primary());
            if (sNext != null) sNext.setColor(AppColors.primary());
            if (sClose != null) sClose.setColor(AppColors.tertiary());
            sScheme = AppColors.getSchemeVersion();
        } catch (Throwable ignored) {}
    }

    /** 切换三态：缩回(贴边) / 拉出(整圆) / 展开(控制条)。 */
    private static void applyState(int state, boolean animate) {
        sState = state;
        LinearLayout panel = sPanel;
        View root = sRoot;
        if (panel == null || root == null || sWM == null) return;
        panel.setVisibility(state == ST_OPEN ? View.VISIBLE : View.GONE);
        if (state == ST_RETRACT) H.removeCallbacks(AUTO_RETRACT);
        else scheduleAutoRetract();
        if (!(root.getLayoutParams() instanceof WindowManager.LayoutParams)) return;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) root.getLayoutParams();
        final int target = state == ST_RETRACT ? dp(root.getContext(), BALL_DP / 2f) : 0;
        if (!animate) {
            lp.x = target;
            try { sWM.updateViewLayout(root, lp); } catch (Throwable ignored) {}
            return;
        }
        if (sAnim != null) { try { sAnim.cancel(); } catch (Throwable ignored) {} }
        ValueAnimator a = ValueAnimator.ofInt(lp.x, target);
        a.setDuration(180);
        a.addUpdateListener(an -> {
            if (sRoot != root || sWM == null) return;
            lp.x = (int) an.getAnimatedValue();
            try { sWM.updateViewLayout(root, lp); } catch (Throwable ignored) {}
        });
        sAnim = a;
        a.start();
    }

    private static void scheduleAutoRetract() {
        H.removeCallbacks(AUTO_RETRACT);
        if (sRoot != null) H.postDelayed(AUTO_RETRACT, AUTO_RETRACT_MS);
    }

    private static void sync() {
        IconView pp = sPlayPause;
        if (pp == null) return;
        if (AppColors.getSchemeVersion() != sScheme) applyColors();
        if (!OnlineMusicPageView.hasPlayback()) {
            if (++sMiss > 3) remove();
            return;
        }
        sMiss = 0;
        pp.setType(OnlineMusicPageView.playbackPaused() ? IconView.PLAY : IconView.PAUSE);
    }

    private static IconView iconBtn(Context ctx, int type, View.OnClickListener cb) {
        IconView v = new IconView(ctx, type, AppColors.primary());
        int s = dp(ctx, BTN_DP);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.setMargins(dp(ctx, 1), 0, dp(ctx, 1), 0);
        v.setLayoutParams(lp);
        v.setOnClickListener(cb);
        return v;
    }

    /** 拖动：位移超过阈值判定为拖动(取消点击)，否则交给子 View 的点击。仅垂直移动。 */
    private static final class DragTouch implements View.OnTouchListener {
        private float downRawY;
        private int startY;
        private boolean dragging;

        @Override public boolean onTouch(View v, MotionEvent e) {
            View root = sRoot;
            if (root == null || sWM == null
                    || !(root.getLayoutParams() instanceof WindowManager.LayoutParams)) {
                return false;
            }
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) root.getLayoutParams();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawY = e.getRawY();
                    startY = lp.y;
                    dragging = false;
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    float dy = e.getRawY() - downRawY;
                    if (!dragging && Math.abs(dy) > 10) dragging = true;
                    if (!dragging) return false;
                    scheduleAutoRetract();
                    int h = root.getContext().getResources().getDisplayMetrics().heightPixels;
                    int maxY = h - dp(root.getContext(), BALL_DP) - dp(root.getContext(), 8);
                    lp.y = Math.max(dp(root.getContext(), 8),
                            Math.min(maxY, startY + (int) dy));
                    try { sWM.updateViewLayout(root, lp); } catch (Throwable ignored) {}
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) scheduleAutoRetract();
                    return dragging;
                default:
                    return false;
            }
        }
    }

    /** 圆球图标：4 根白色声波音柱（居中缩小，避免过满）。 */
    private static final class EqIconView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private static final float[] RATIOS = {0.42f, 0.92f, 0.62f, 0.30f};
        /** 图标占圆球直径比例。 */
        private static final float ICON_SCALE = 0.52f;

        EqIconView(Context ctx) {
            super(ctx);
            p.setColor(Color.WHITE);
            p.setStyle(Paint.Style.FILL);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float icon = Math.min(w, h) * ICON_SCALE;
            float barW = icon * 0.16f;
            float gap = icon * 0.13f;
            float total = RATIOS.length * barW + (RATIOS.length - 1) * gap;
            float x = (w - total) / 2f;
            float cy = h / 2f;
            float r = barW / 2f;
            for (float ratio : RATIOS) {
                float bh = icon * ratio;
                float top = cy - bh / 2f;
                c.drawRoundRect(new RectF(x, top, x + barW, top + bh), r, r, p);
                x += barW + gap;
            }
        }
    }

    /** 控制条按钮图标：上一首 / 播放 / 暂停 / 下一首 / 关闭。 */
    private static final class IconView extends View {
        static final int PREV = 0, PLAY = 1, PAUSE = 2, NEXT = 3, CLOSE = 4;

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int type;

        IconView(Context ctx, int type, int color) {
            super(ctx);
            this.type = type;
            p.setColor(color);
            p.setStyle(Paint.Style.FILL);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        void setType(int type) {
            if (this.type != type) {
                this.type = type;
                invalidate();
            }
        }

        void setColor(int color) {
            p.setColor(color);
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight());
            if (s <= 0) return;
            float pad = s * 0.27f;
            float l = pad, t = pad, r = s - pad, b = s - pad;
            float cx = s / 2f, cy = s / 2f;
            float stroke = s * 0.11f;
            switch (type) {
                case PLAY:
                    p.setStyle(Paint.Style.FILL);
                    c.drawPath(tri(l, t, l, b, r, cy), p);
                    break;
                case PAUSE: {
                    p.setStyle(Paint.Style.FILL);
                    float bw = s * 0.14f;
                    float left = cx - bw * 1.15f, right = cx + bw * 0.15f;
                    float rr = bw / 2f;
                    c.drawRoundRect(new RectF(left, t, left + bw, b), rr, rr, p);
                    c.drawRoundRect(new RectF(right, t, right + bw, b), rr, rr, p);
                    break;
                }
                case PREV:
                    p.setStyle(Paint.Style.FILL);
                    c.drawPath(tri(r, t, r, b, l, cy), p);
                    c.drawRoundRect(new RectF(l, t, l + stroke, b), stroke / 2f, stroke / 2f, p);
                    break;
                case NEXT:
                    p.setStyle(Paint.Style.FILL);
                    c.drawPath(tri(l, t, l, b, r, cy), p);
                    c.drawRoundRect(new RectF(r - stroke, t, r, b), stroke / 2f, stroke / 2f, p);
                    break;
                case CLOSE:
                default:
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(stroke);
                    c.drawLine(l, t, r, b, p);
                    c.drawLine(r, t, l, b, p);
                    break;
            }
        }

        private static android.graphics.Path tri(float x1, float y1, float x2, float y2,
                                                 float x3, float y3) {
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            path.lineTo(x3, y3);
            path.close();
            return path;
        }
    }

    private static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
