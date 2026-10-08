package com.leshao.v3.ui

import android.view.View
import android.view.Window
import android.view.WindowManager

import java.util.ArrayList

/**
 * v1142: 浮层层级跟踪器。
 *
 * 用于解决「多层弹窗叠加时背景同色、难以区分」的问题：每个浮层在
 * [show] 后调用 [track]（弹窗内容）或 [trackView] 登记层级；离开时由 View attach/detach 自动注销。
 *
 * 配合 [CandyUi.dialogBg]（按层级换底色 + 描边）与 [applyScrim]（第 2 层
 * 起加遮罩压暗下层），让相邻层在底色、边界、明暗三个维度上都能区分。
 */
class WindowLayer private constructor() {

    companion object {

        private val STACK: MutableList<Any> = ArrayList()

        /** 当前已显示的浮层层数，等于「下一层」的层级索引（0 基）。 */
        @JvmStatic
        @Synchronized
        fun depth(): Int {
            return STACK.size
        }

        @Synchronized
        private fun enter(token: Any): Int {
            STACK.remove(token)
            STACK.add(token)
            return STACK.size
        }

        @Synchronized
        private fun exit(token: Any) {
            STACK.remove(token)
        }

        private fun watch(v: View?, token: Any, applyScrim: Boolean) {
            if (v == null) return
            v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    val layer = enter(token) - 1
                    if (applyScrim && token is Window) {
                        applyScrim(token, layer)
                    }
                }

                override fun onViewDetachedFromWindow(view: View) {
                    exit(token)
                }
            })
        }

        /** 跟踪一个 Dialog / 任意 Window；应在 [show] 之后调用。 */
        @JvmStatic
        fun track(w: Window?) {
            if (w == null) return
            var decor: View? = null
            try {
                decor = w.decorView
            } catch (ignored: Throwable) {
            }
            if (decor == null) return
            watch(decor, w, true)
            if (decor.isAttachedToWindow) {
                val layer = enter(w) - 1
                applyScrim(w, layer)
            }
        }

        /** 跟踪一个 PopupWindow 的内容视图；应在 [showAtLocation]()/[showAsDropDown]() 之后调用。 */
        @JvmStatic
        fun trackView(content: View?) {
            if (content == null) return
            watch(content, content, false)
            if (content.isAttachedToWindow) {
                enter(content)
            }
        }

        /**
         * 设置窗口遮罩：第 1 层不加（保持宿主原样），第 2 层起按层级递增压下背景，
         * 从而让「上层清晰、下层变暗」，同时下层弹窗也被一并压暗。
         */
        @JvmStatic
        fun applyScrim(w: Window?, layer: Int) {
            if (w == null) return
            try {
                if (layer <= 0) {
                    w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    w.setDimAmount(0f)
                } else {
                    w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    w.setDimAmount(Math.min(0.18f + 0.14f * (layer - 1), 0.5f))
                }
            } catch (ignored: Throwable) {
            }
        }
    }
}