package com.leshao.v3.ui;

import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * v1142: 浮层层级跟踪器。
 *
 * <p>用于解决「多层弹窗叠加时背景同色、难以区分」的问题：每个浮层在
 * {@code show()} 后调用 {@link #track(Window)}（弹窗内容）或 {@link #trackView(View)}
 * 登记层级；离开时由 View attach/detach 自动注销。</p>
 *
 * <p>配合 {@link CandyUi#dialogBg}（按层级换底色 + 描边）与 {@link #applyScrim}（第 2 层
 * 起加遮罩压暗下层），让相邻层在底色、边界、明暗三个维度上都能区分。</p>
 */
public final class WindowLayer {

    private WindowLayer() {
    }

    private static final List<Object> STACK = new ArrayList<>();

    /** 当前已显示的浮层层数，等于「下一层」的层级索引（0 基）。 */
    public static synchronized int depth() {
        return STACK.size();
    }

    private static synchronized int enter(Object token) {
        STACK.remove(token);
        STACK.add(token);
        return STACK.size();
    }

    private static synchronized void exit(Object token) {
        STACK.remove(token);
    }

    private static void watch(View v, Object token, boolean applyScrim) {
        if (v == null) return;
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                int layer = enter(token) - 1;
                if (applyScrim && token instanceof Window) {
                    applyScrim((Window) token, layer);
                }
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                exit(token);
            }
        });
    }

    /** 跟踪一个 Dialog / 任意 Window；应在 {@code show()} 之后调用。 */
    public static void track(Window w) {
        if (w == null) return;
        View decor = null;
        try {
            decor = w.getDecorView();
        } catch (Throwable ignored) {
        }
        if (decor == null) return;
        watch(decor, w, true);
        if (decor.isAttachedToWindow()) {
            int layer = enter(w) - 1;
            applyScrim(w, layer);
        }
    }

    /** 跟踪一个 PopupWindow 的内容视图；应在 {@code showAtLocation()/showAsDropDown()} 之后调用。 */
    public static void trackView(View content) {
        if (content == null) return;
        watch(content, content, false);
        if (content.isAttachedToWindow()) {
            enter(content);
        }
    }

    /**
     * 设置窗口遮罩：第 1 层不加（保持宿主原样），第 2 层起按层级递增压下背景，
     * 从而让「上层清晰、下层变暗」，同时下层弹窗也被一并压暗。
     */
    public static void applyScrim(Window w, int layer) {
        if (w == null) return;
        try {
            if (layer <= 0) {
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                w.setDimAmount(0f);
            } else {
                w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                w.setDimAmount(Math.min(0.18f + 0.14f * (layer - 1), 0.5f));
            }
        } catch (Throwable ignored) {
        }
    }
}
