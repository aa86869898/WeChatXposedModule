package com.leshao.v3.ui

import android.app.Dialog
import android.widget.PopupWindow
import java.util.ArrayDeque
import java.util.Deque
import java.util.Iterator
import java.util.LinkedHashMap
import java.util.Map

/**
 * v1015: 模块窗口返回栈 —— 统一管理模块弹出的所有 Dialog / PopupWindow，
 * 保证「二级返回首页、三级返回上一层」的返回语义全局一致、不遗漏。
 *
 * 全局安装见 [UiBackInstaller]：Dialog.show/dismiss 与 PopupWindow.show/dismiss
 * 自动登记/注销；Dialog.onBackPressed 与 PopupWindow 的按键分发统一走本栈。
 */
object UiBackStack {

    private val ORDER: Deque<Any> = ArrayDeque()
    private val DISMISS: MutableMap<Any, Runnable?> = LinkedHashMap()
    private val BACK: MutableMap<Any, Runnable> = LinkedHashMap()

    @JvmStatic
    @Synchronized
    fun push(window: Any?, dismiss: Runnable?) {
        push(window, dismiss, null)
    }

    @JvmStatic
    @Synchronized
    fun push(window: Any?, dismiss: Runnable?, onBack: Runnable?) {
        if (window == null) return
        prune()
        ORDER.remove(window)
        ORDER.push(window)
        DISMISS[window] = dismiss
        if (onBack != null) BACK[window] = onBack
        else BACK.remove(window)
    }

    @JvmStatic
    @Synchronized
    fun remove(window: Any?) {
        if (window == null) return
        ORDER.remove(window)
        DISMISS.remove(window)
        BACK.remove(window)
    }

    @JvmStatic
    @Synchronized
    fun peek(): Any? {
        prune()
        return ORDER.peek()
    }

    @JvmStatic
    @Synchronized
    fun size(): Int {
        prune()
        return ORDER.size
    }

    @JvmStatic
    @Synchronized
    fun isEmpty(): Boolean {
        return size() == 0
    }

    /** 弹出并关闭栈顶窗口，成功返回 true */
    @JvmStatic
    @Synchronized
    fun popTop(): Boolean {
        prune()
        val w = ORDER.poll() ?: return false
        val r = DISMISS.remove(w)
        BACK.remove(w)
        if (r != null) {
            try {
                r.run()
            } catch (ignored: Throwable) {}
        } else {
            dismissWindow(w)
        }
        return true
    }

    /** 只有当前传入窗口不是栈顶(其上方还有子窗口)时才拦截返回：返回 true 表示已消费 */
    @JvmStatic
    @Synchronized
    fun interceptDialogBack(dialog: Any?): Boolean {
        prune()
        val top = ORDER.peek()
        if (top != null && top != dialog) {
            popTop()
            return true
        }
        return false
    }

    /**
     * PopupWindow 收到返回键时调用：若栈顶就是该弹窗且注册了自定义返回动作，则执行并消费。
     * 否则返回 false，交由 PopupWindow 默认行为（关闭自身）。
     */
    @JvmStatic
    @Synchronized
    fun interceptPopupBack(): Boolean {
        prune()
        val top = ORDER.peek()
        if (top is PopupWindow) {
            val onBack = BACK[top]
            if (onBack != null) {
                try {
                    onBack.run()
                } catch (ignored: Throwable) {}
                return true
            }
        }
        return false
    }

    @JvmStatic
    @Synchronized
    fun clear() {
        ORDER.clear()
        DISMISS.clear()
        BACK.clear()
    }

    /** 清理已经不在显示的窗口，避免栈内残留已销毁窗口 */
    private fun prune() {
        try {
            val it: MutableIterator<Any> = ORDER.iterator()
            while (it.hasNext()) {
                val w = it.next()
                if (!isShowing(w)) {
                    it.remove()
                    DISMISS.remove(w)
                    BACK.remove(w)
                }
            }
        } catch (ignored: Throwable) {}
    }

    private fun isShowing(w: Any): Boolean {
        try {
            if (w is Dialog) return w.isShowing
            if (w is PopupWindow) return w.isShowing
        } catch (ignored: Throwable) {}
        return true
    }

    private fun dismissWindow(w: Any) {
        try {
            if (w is Dialog) w.dismiss()
            else if (w is PopupWindow) w.dismiss()
        } catch (ignored: Throwable) {}
    }
}