package com.leshao.v3.ui;

import android.app.Dialog;
import android.widget.PopupWindow;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v1015: 模块窗口返回栈 —— 统一管理模块弹出的所有 Dialog / PopupWindow，
 * 保证「二级返回首页、三级返回上一层」的返回语义全局一致、不遗漏。
 *
 * <p>全局安装见 {@link UiBackInstaller}：Dialog.show/dismiss 与 PopupWindow.show/dismiss
 * 自动登记/注销；Dialog.onBackPressed 与 PopupWindow 的按键分发统一走本栈。</p>
 */
public final class UiBackStack {

    private static final Deque<Object> ORDER = new ArrayDeque<>();
    private static final Map<Object, Runnable> DISMISS = new LinkedHashMap<>();
    private static final Map<Object, Runnable> BACK = new LinkedHashMap<>();

    private UiBackStack() {
    }

    public static synchronized void push(Object window, Runnable dismiss) {
        push(window, dismiss, null);
    }

    public static synchronized void push(Object window, Runnable dismiss, Runnable onBack) {
        if (window == null) return;
        prune();
        ORDER.remove(window);
        ORDER.push(window);
        DISMISS.put(window, dismiss);
        if (onBack != null) BACK.put(window, onBack);
        else BACK.remove(window);
    }

    public static synchronized void remove(Object window) {
        if (window == null) return;
        ORDER.remove(window);
        DISMISS.remove(window);
        BACK.remove(window);
    }

    public static synchronized Object peek() {
        prune();
        return ORDER.peek();
    }

    public static synchronized int size() {
        prune();
        return ORDER.size();
    }

    public static synchronized boolean isEmpty() {
        return size() == 0;
    }

    /** 弹出并关闭栈顶窗口，成功返回 true */
    public static synchronized boolean popTop() {
        prune();
        Object w = ORDER.poll();
        if (w == null) return false;
        Runnable r = DISMISS.remove(w);
        BACK.remove(w);
        if (r != null) {
            try { r.run(); } catch (Throwable ignored) {}
        } else {
            dismissWindow(w);
        }
        return true;
    }

    /** 只有当前传入窗口不是栈顶(其上方还有子窗口)时才拦截返回：返回 true 表示已消费 */
    public static synchronized boolean interceptDialogBack(Object dialog) {
        prune();
        Object top = ORDER.peek();
        if (top != null && top != dialog) {
            popTop();
            return true;
        }
        return false;
    }

    /**
     * PopupWindow 收到返回键时调用：若栈顶就是该弹窗且注册了自定义返回动作，则执行并消费。
     * 否则返回 false，交由 PopupWindow 默认行为（关闭自身）。
     */
    public static synchronized boolean interceptPopupBack() {
        prune();
        Object top = ORDER.peek();
        if (top instanceof PopupWindow) {
            Runnable onBack = BACK.get(top);
            if (onBack != null) {
                try { onBack.run(); } catch (Throwable ignored) {}
                return true;
            }
        }
        return false;
    }

    public static synchronized void clear() {
        ORDER.clear();
        DISMISS.clear();
        BACK.clear();
    }

    /** 清理已经不在显示的窗口，避免栈内残留已销毁窗口 */
    private static void prune() {
        try {
            Iterator<Object> it = ORDER.iterator();
            while (it.hasNext()) {
                Object w = it.next();
                if (!isShowing(w)) {
                    it.remove();
                    DISMISS.remove(w);
                    BACK.remove(w);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static boolean isShowing(Object w) {
        try {
            if (w instanceof Dialog) return ((Dialog) w).isShowing();
            if (w instanceof PopupWindow) return ((PopupWindow) w).isShowing();
        } catch (Throwable ignored) {}
        return true;
    }

    private static void dismissWindow(Object w) {
        try {
            if (w instanceof Dialog) ((Dialog) w).dismiss();
            else if (w instanceof PopupWindow) ((PopupWindow) w).dismiss();
        } catch (Throwable ignored) {}
    }
}
