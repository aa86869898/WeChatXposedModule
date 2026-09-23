package com.leshao.v3.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * 系统栏安全区统一工具（v987）。
 *
 * <p>以 {@code AiAssistantPanel} 原有的 inset 读取逻辑为样板提取：
 * 顶部优先取根窗口 WindowInsets，失败回退 {@code status_bar_height} 资源（再回退 24dp）；
 * 底部优先取根窗口 WindowInsets，失败回退 {@code navigation_bar_height} 资源（再回退 48dp）。</p>
 *
 * <p>页面 / 弹窗 / PopupWindow 统一调用本工具，避免内容落在状态栏或导航栏下方被裁切。</p>
 */
public final class InsetsUtil {

    private InsetsUtil() {
    }

    public static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 状态栏高度(px)，取系统资源，失败回退 24dp。 */
    public static int statusBarHeight(Context ctx) {
        try {
            int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return ctx.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {
        }
        return dp(ctx, 24);
    }

    /** 底部系统栏(导航栏/手势条)高度(px)，取系统资源，失败回退 48dp。 */
    public static int navigationBarHeight(Context ctx) {
        try {
            int id = ctx.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (id > 0) {
                int h = ctx.getResources().getDimensionPixelSize(id);
                if (h > 0) return h;
            }
        } catch (Throwable ignored) {
        }
        return dp(ctx, 48);
    }

    /** 顶部安全区(px)：优先根窗口 WindowInsets，回退状态栏资源高度。 */
    public static int topInset(Context ctx, View anchor) {
        try {
            if (anchor != null && anchor.isAttachedToWindow()) {
                WindowInsetsCompat wi = ViewCompat.getRootWindowInsets(anchor);
                if (wi != null) {
                    int t = wi.getInsets(WindowInsetsCompat.Type.statusBars()).top;
                    if (t > 0) return t;
                }
            }
        } catch (Throwable ignored) {
        }
        return statusBarHeight(ctx);
    }

    /** 底部安全区(px)：优先根窗口 WindowInsets，回退导航栏资源高度。 */
    public static int bottomInset(Context ctx, View anchor) {
        try {
            if (anchor != null && anchor.isAttachedToWindow()) {
                WindowInsetsCompat wi = ViewCompat.getRootWindowInsets(anchor);
                if (wi != null) {
                    int b = wi.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
                    if (b > 0) return b;
                }
            }
        } catch (Throwable ignored) {
        }
        return navigationBarHeight(ctx);
    }

    /** 给已有视图追加顶部内边距（保留原内边距）。 */
    public static void padTop(View v, int extraPx) {
        if (v == null || extraPx <= 0) return;
        v.setPadding(v.getPaddingLeft(), v.getPaddingTop() + extraPx,
                v.getPaddingRight(), v.getPaddingBottom());
    }

    /** 给已有视图追加底部内边距（保留原内边距）。 */
    public static void padBottom(View v, int extraPx) {
        if (v == null || extraPx <= 0) return;
        v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                v.getPaddingRight(), v.getPaddingBottom() + extraPx);
    }

    /** 让圆角容器裁切子视图，避免内部直角背景从圆角边缘漏出。 */
    public static void clipRounded(View v) {
        try {
            v.setClipToOutline(true);
        } catch (Throwable ignored) {
        }
    }

    /** 将窗口背景设为透明（圆角容器外由透明窗口露出宿主，避免实底间隔）。 */
    public static void transparentWindow(Window w) {
        if (w == null) return;
        try {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        } catch (Throwable ignored) {
        }
        // v1015: 统一弹窗进出动画（淡入淡出+缩放），让弹窗/子窗口过渡更丝滑
        try {
            w.setWindowAnimations(android.R.style.Animation_Dialog);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 清空系统窗口与 DécorView 外壳背景（Activity 用），只保留内部 M3Page 圆角浮层。
     *
     * <p>v988 关键修复：仅把 windowBackground 设为透明并不够——系统 DécorView 外壳本身
     * 仍带一层实白背景，会盖在圆角卡片四角外侧，导致视觉上看不到透明效果。
     * 必须调用 {@code setBackgroundDrawable(null)} 并清空 DécorView 背景，
     * 同时把状态栏/导航栏底色置透明（否则状态栏区域仍是一段实色间隔）。</p>
     */
    public static void clearWindowShell(Window w) {
        if (w == null) return;
        try {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        } catch (Throwable ignored) {
        }
        // v1015: 统一进出动画
        try {
            w.setWindowAnimations(android.R.style.Animation_Dialog);
        } catch (Throwable ignored) {
        }
        try {
            View decor = w.getDecorView();
            if (decor != null) decor.setBackground(new ColorDrawable(Color.TRANSPARENT));
        } catch (Throwable ignored) {
        }
        try {
            w.setDimAmount(0f);
        } catch (Throwable ignored) {
        }
        try {
            w.setStatusBarColor(Color.TRANSPARENT);
        } catch (Throwable ignored) {
        }
        try {
            w.setNavigationBarColor(Color.TRANSPARENT);
        } catch (Throwable ignored) {
        }
    }

    /** 清空 Activity 系统窗口外壳背景（window.setBackgroundDrawable(null) + DécorView）。 */
    public static void clearWindowShell(Activity a) {
        if (a == null) return;
        try {
            clearWindowShell(a.getWindow());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 清空 AlertDialog 系统外壳背景：窗口 + DécorView + alert 面板(parentPanel 等)。
     *
     * <p>v988 关键修复：全屏 AlertDialog 的 parentPanel 由 dialog 主题提供实底背景，
     * 单改 windowBackground 无法清掉，白色外壳会压在圆角卡片四角与安全区外。
     * 另外 dialog 主题为非悬浮主题时窗口表面不透明，清空背景后会露出黑底，
     * 因此显式把窗口像素格式设为半透明，让圆角外侧透出宿主。</p>
     */
    public static void clearDialogShell(Dialog d) {
        if (d == null) return;
        Window w = null;
        try {
            w = d.getWindow();
        } catch (Throwable ignored) {
        }
        clearWindowShell(w);
        try {
            if (w != null) {
                WindowCompat.setDecorFitsSystemWindows(w, false);
                w.setFormat(android.graphics.PixelFormat.TRANSLUCENT);
                w.setNavigationBarDividerColor(Color.TRANSPARENT);
            }
        } catch (Throwable ignored) {
        }
        try {
            View decor = w == null ? null : w.getDecorView();
            Context ctx = d.getContext();
            if (decor != null && ctx != null) {
                String[] ids = {"parentPanel", "topPanel", "contentPanel",
                        "customPanel", "buttonPanel", "scrollView", "content"};
                for (String name : ids) {
                    int id = ctx.getResources().getIdentifier(name, "id", "android");
                    if (id == 0) continue;
                    View v = decor.findViewById(id);
                    if (v != null) v.setBackground(null);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 将窗口背景设为透明（Dialog 重载，并清空 alert 面板实底外壳）。 */
    public static void transparentWindow(Dialog d) {
        clearDialogShell(d);
    }

    /** 将窗口背景设为透明（Activity 重载）。 */
    public static void transparentWindow(Activity a) {
        if (a == null) return;
        try {
            clearWindowShell(a.getWindow());
        } catch (Throwable ignored) {
        }
    }

    /** 将视图设为统一圆角浮层底（surface + 28dp 圆角 + 裁切）。 */
    public static void sheet(View v) {
        if (v == null) return;
        try {
            v.setBackground(CandyUi.pageGradient());
            clipRounded(v);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 用透明外层容器包裹圆角浮层（强制层级改造）。
     *
     * <p>外层 {@link FrameLayout} 背景恒为透明，只负责布局；圆角渐变由内部
     * 传入的 sheet 绘制。这样圆角弧线以外（含屏幕四角、状态栏/导航栏侧边）
     * 100% 无颜料，直接露出宿主微信画面。</p>
     */
    public static ViewGroup host(View sheet) {
        if (sheet == null) return null;
        FrameLayout outer = new FrameLayout(sheet.getContext());
        outer.setBackground(null);
        outer.addView(sheet, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return outer;
    }

    /**
     * v998: 将页面/弹窗改造为「居中浮层窗口」。
     *
     * <p>不再全屏贴顶铺满：窗口仍为全屏透明，内容外层 {@link FrameLayout} 通过内边距留白，
     * 内部 sheet 以屏幕比例固定尺寸并 {@code CENTER} 居中，配合
     * {@link CandyUi#pageGradient()} 的描边与 {@link CandyUi#elevate(View)} 的阴影，
     * 形成悬浮卡片观感。系统状态栏/导航栏区域露出宿主画面。</p>
     *
     * @param d     目标弹窗（可为空，仅做容器包装时）
     * @param sheet 圆角浮层本体（页面根 / 弹窗根）
     * @param wRatio 宽度占屏比 0~1
     * @param hRatio 高度占屏比 0~1
     */
    public static ViewGroup window(Dialog d, View sheet, float wRatio, float hRatio) {
        Context ctx = sheet.getContext();
        FrameLayout outer = new FrameLayout(ctx);
        outer.setBackground(null);
        outer.setClipChildren(false);
        outer.setClipToPadding(false);
        FrameLayout.LayoutParams outerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        outer.setLayoutParams(outerLp);
        int pad = dp(ctx, 12);
        outer.setPadding(pad, pad, pad, pad);

        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int w = wRatio > 0 ? (int) (dm.widthPixels * wRatio) : FrameLayout.LayoutParams.WRAP_CONTENT;
        int h = hRatio > 0 ? (int) (dm.heightPixels * hRatio) : FrameLayout.LayoutParams.WRAP_CONTENT;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = Gravity.CENTER;
        CandyUi.elevate(sheet);
        outer.addView(sheet, lp);

        if (d != null) center(d, wRatio, hRatio);
        return outer;
    }

    /**
     * v998: 将弹窗窗口设为「居中浮层」。
     *
     * <p>关键点：窗口本身按屏幕比例固定尺寸并 {@code CENTER} 居中（而非全屏 MATCH_PARENT），
     * 这样无论系统 decor / 内容 FrameLayout 如何布局，弹窗都稳定出现在屏幕正中间，
     * 不会贴顶。比例传入 &lt;=0 表示该方向 {@code WRAP_CONTENT}（用于短内容弹窗）。</p>
     */
    public static void center(Dialog d, float wRatio, float hRatio) {
        if (d == null) return;
        Window win = null;
        try { win = d.getWindow(); } catch (Throwable ignored) {}
        if (win == null) return;
        Context ctx = d.getContext();
        int pad = dp(ctx, 12);
        try { win.setGravity(Gravity.CENTER); } catch (Throwable ignored) {}
        try {
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int w = wRatio > 0 ? (int) (dm.widthPixels * wRatio) + 2 * pad
                    : ViewGroup.LayoutParams.WRAP_CONTENT;
            int h = hRatio > 0 ? (int) (dm.heightPixels * hRatio) + 2 * pad
                    : ViewGroup.LayoutParams.WRAP_CONTENT;
            win.setLayout(w, h);
        } catch (Throwable ignored) {}
        clearDialogShell(d);
    }

    /** v998: 仅设置窗口居中重力并清理外壳（尺寸由调用方或内容自适应）。 */
    public static void center(Dialog d) {
        if (d == null) return;
        Window win = null;
        try { win = d.getWindow(); } catch (Throwable ignored) {}
        if (win == null) return;
        try { win.setGravity(Gravity.CENTER); } catch (Throwable ignored) {}
        clearDialogShell(d);
    }

    /** Activity 内容边到边 + 自动按系统栏追加内边距（setContentView 之后调用）。 */
    public static void applyActivityInsets(Activity activity, View content) {
        if (activity == null || content == null) return;
        Window w = null;
        try {
            w = activity.getWindow();
        } catch (Throwable ignored) {
        }
        install(w, content);
    }

    /** Dialog 内容边到边 + 自动按系统栏追加内边距（create 之后、show 前后皆可）。 */
    public static void applyDialogInsets(Dialog dialog, View content) {
        if (dialog == null || content == null) return;
        Window w = null;
        try {
            w = dialog.getWindow();
        } catch (Throwable ignored) {
        }
        install(w, content);
    }

    /**
     * 边到边 + 将系统栏安全区转为内容视图的<b>外边距</b>（而非内边距）。
     *
     * <p>v987 统一透明化关键：若用内边距，内容视图自身的不透明背景会覆盖状态栏/导航栏区域，
     * 在圆角浮层外露出实底间隔；改为外边距后，安全区由透明窗口露出宿主，
     * 系统状态栏保持安卓原生外观，圆角浮层从状态栏下方开始。</p>
     */
    private static final java.util.WeakHashMap<View, int[]> BASE_MARGIN = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<View, int[]> INSET_PX = new java.util.WeakHashMap<>();

    private static void install(Window window, final View content) {
        if (window == null || content == null) return;
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false);
        } catch (Throwable ignored) {
        }
        final int baseLeft = content.getPaddingLeft();
        final int baseTop = content.getPaddingTop();
        final int baseRight = content.getPaddingRight();
        final int baseBottom = content.getPaddingBottom();
        final Context ctx = content.getContext();

        int[] inset = INSET_PX.get(content);
        if (inset == null) {
            // 同步兜底：部分弹窗主题下 WindowInsets 不派发或返回 0，
            // 先用系统资源高度把内容顶到状态栏下方，避免页面底色压到状态栏。
            inset = new int[]{statusBarHeight(ctx), navigationBarHeight(ctx)};
            INSET_PX.put(content, inset);
        }
        final int[] insetRef = inset;

        final Runnable apply = new Runnable() {
            @Override
            public void run() {
                ViewGroup.LayoutParams lp = content.getLayoutParams();
                if (lp == null) {
                    lp = new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT);
                }
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                    int[] base = BASE_MARGIN.get(content);
                    if (base == null) {
                        base = new int[]{mlp.leftMargin, mlp.topMargin,
                                mlp.rightMargin, mlp.bottomMargin};
                        BASE_MARGIN.put(content, base);
                    }
                    mlp.setMargins(base[0], base[1] + insetRef[0],
                            base[2], base[3] + insetRef[1]);
                    content.setLayoutParams(mlp);
                } else {
                    content.setPadding(baseLeft, baseTop + insetRef[0],
                            baseRight, baseBottom + insetRef[1]);
                }
            }
        };

        apply.run();
        try {
            ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
                int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
                int bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
                // 仅在系统给出有效值时更新，否则保留同步兜底高度。
                if (top > 0) insetRef[0] = top;
                if (bottom > 0) insetRef[1] = bottom;
                apply.run();
                return insets;
            });
            ViewCompat.requestApplyInsets(content);
        } catch (Throwable ignored) {
        }
    }
}
