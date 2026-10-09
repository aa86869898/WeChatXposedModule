package com.leshao.v3.wm.utils

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.leshao.v3.LogWriter
import com.leshao.v3.ui.AppColors

/**
 * 现代化 UI 工具 — 统一 wm 悬浮面板风格
 * 圆角白卡 + 图标按钮 + 分组标签，与模块页面风格一致
 */
class WmUi private constructor() {

    companion object {

        @JvmField
        val C_BG: Int = AppColors.surface()               // M3 surface
        @JvmField
        val C_CARD: Int = AppColors.surfaceContainerLow()    // M3 filled card
        @JvmField
        val C_TEXT: Int = AppColors.onSurface()              // M3 onSurface
        @JvmField
        val C_TEXT2: Int = AppColors.onSurfaceVariant()       // M3 onSurfaceVariant
        @JvmField
        val C_ACCENT: Int = AppColors.primary()                // M3 primary
        @JvmField
        val C_ACCENT2: Int = AppColors.primaryDark()            // M3 primary 深阶
        @JvmField
        val C_GREEN: Int = AppColors.primary()
        @JvmField
        val C_RED: Int = AppColors.error()
        @JvmField
        val C_BORDER: Int = AppColors.outlineVariant()         // M3 outlineVariant

        @JvmStatic
        fun makePanel(act: Activity): LinearLayout {
            val panel = LinearLayout(act)
            panel.orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable()
            bg.setColor(C_CARD)
            bg.setCornerRadius(dp(act, AppColors.SHAPE_LG_DP).toFloat())
            bg.setStroke(dp(act, 1), C_BORDER)
            panel.background = bg
            panel.elevation = dp(act, 14).toFloat()
            return panel
        }

        @JvmStatic
        fun makeHeader(act: Activity, title: String, subtitle: String): LinearLayout {
            val head = LinearLayout(act)
            head.orientation = LinearLayout.VERTICAL
            val hb = GradientDrawable()
            hb.setColor(C_ACCENT)
            hb.setCornerRadii(floatArrayOf(dp(act, 28).toFloat(), dp(act, 28).toFloat(), dp(act, 28).toFloat(), dp(act, 28).toFloat(), 0f, 0f, 0f, 0f))
            head.background = hb
            head.setPadding(dp(act, 16), dp(act, 14), dp(act, 16), dp(act, 14))

            val tv = TextView(act)
            tv.text = title
            tv.setTextSize(18f)
            tv.setTextColor(AppColors.onPrimary())
            tv.gravity = Gravity.CENTER_VERTICAL
            head.addView(tv)

            if (subtitle != null && subtitle.isNotEmpty()) {
                val dv = TextView(act)
                dv.text = subtitle
                dv.setTextSize(12f)
                dv.setTextColor(0xB3FFFFFF.toInt())
                dv.setPadding(0, dp(act, 3), 0, 0)
                head.addView(dv)
            }
            return head
        }

        @JvmStatic
        fun makeSection(act: Activity, text: String): TextView {
            val tv = TextView(act)
            tv.text = text
            tv.setTextSize(14f)
            tv.setTextColor(C_TEXT2)
            tv.setPadding(dp(act, 16), dp(act, 12), dp(act, 16), dp(act, 4))
            return tv
        }

        @JvmStatic
        fun makeBtn(act: Activity, text: String, action: Runnable?): Button {
            val btn = Button(act)
            btn.text = text
            btn.setTextSize(14f)
            btn.isAllCaps = false
            btn.setTextColor(C_TEXT)
            btn.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            btn.setPadding(dp(act, 16), 0, dp(act, 16), 0)

            val gd = GradientDrawable()
            gd.setColor(AppColors.surfaceContainerHigh())
            gd.setCornerRadius(dp(act, AppColors.SHAPE_MD_DP).toFloat())
            btn.background = gd

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(act, 40))
            lp.setMargins(dp(act, 8), 0, dp(act, 8), dp(act, 4))
            btn.layoutParams = lp

            btn.setOnClickListener {
                try { if (action != null) action.run() } catch (ignored: Throwable) {}
            }
            return btn
        }

        @JvmStatic
        fun makePrimaryBtn(act: Activity, text: String, action: Runnable?): Button {
            val btn = makeBtn(act, text, action)
            btn.setTextColor(AppColors.onPrimary())
            val gd = GradientDrawable()
            gd.setColor(C_ACCENT)
            gd.setCornerRadius(dp(act, AppColors.SHAPE_FULL_DP).toFloat())
            btn.background = gd
            btn.gravity = Gravity.CENTER
            return btn
        }

        @JvmStatic
        fun makeDivider(act: Activity): View {
            val v = View(act)
            v.setBackgroundColor(C_BORDER)
            v.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(act, 1))
            return v
        }

        /**
         * 创建全屏透明遮罩，内含悬浮面板。
         * 点击面板外的空白区域自动关闭（通过传入的 onDismiss 回调）。
         * 面板自身仍可正常点击操作。
         */
        @JvmStatic
        fun makeOverlay(act: Activity, panel: View, onDismiss: Runnable?): View {
            val overlay = FrameLayout(act)
            overlay.setBackgroundColor(AppColors.bgMask())
            overlay.isClickable = true
            overlay.isFocusable = true
            overlay.setOnClickListener { if (onDismiss != null) onDismiss.run() }
            overlay.addView(panel, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.RIGHT))
            return overlay
        }

        /** 在 WindowManager 中添加全屏遮罩（含面板） */
        @JvmStatic
        fun overlayParams(act: Activity): WindowManager.LayoutParams {
            return WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT)
        }

        @JvmStatic
        fun dp(act: Activity, px: Int): Int {
            return (px * act.resources.displayMetrics.density + 0.5f).toInt()
        }

        @JvmStatic
        fun dp(act: Activity, px: Float): Int {
            return (px * act.resources.displayMetrics.density + 0.5f).toInt()
        }
    }

    // ===== 可拖动浮动按钮 =====
    class DragFloat(
        act: Activity,
        wm: WindowManager,
        text: String,
        bgColor: Int,
        prefKey: String,
        onTap: Runnable?
    ) {
        val btn: Button
        val wp: WindowManager.LayoutParams
        private var mDownX = 0f
        private var mDownY = 0f
        private var mInitX = 0
        private var mInitY = 0
        private var mDownTime = 0L
        private val mView: View
        private val mWM: WindowManager
        private val mPrefKey: String
        private val mOnTap: Runnable?
        private var mAdded = false

        init {
            mWM = wm
            mPrefKey = prefKey
            mOnTap = onTap
            val v = Button(act)
            v.text = text
            v.setTextSize(16f)
            v.setTextColor(Color.WHITE)
            v.gravity = Gravity.CENTER
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(bgColor)
            v.background = bg
            mView = v
            btn = v

            val sz = dp(act, 48)
            wp = WindowManager.LayoutParams(sz, sz,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT)
            wp.gravity = Gravity.TOP or Gravity.LEFT

            val sw = act.resources.displayMetrics.widthPixels
            val sh = act.resources.displayMetrics.heightPixels
            val defX = sw - sz - dp(act, 16)
            val defY = (sh * 0.33f).toInt()
            val lx = WmPrefs.getInt(prefKey + "_x", -1)
            val ly = WmPrefs.getInt(prefKey + "_y", -1)
            if (lx < 0 || lx > sw || ly < 0 || ly > sh) {
                wp.x = defX
                wp.y = defY
            } else {
                wp.x = lx
                wp.y = ly
            }

            v.setOnTouchListener { _, e -> onTouch(v, e) }
        }

        private fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    mDownX = e.rawX
                    mDownY = e.rawY
                    mInitX = wp.x
                    mInitY = wp.y
                    mDownTime = System.currentTimeMillis()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    wp.x = (mInitX.toFloat() + e.rawX - mDownX).toInt()
                    wp.y = (mInitY.toFloat() + e.rawY - mDownY).toInt()
                    try { mWM.updateViewLayout(mView, wp) } catch (ignored: Exception) {}
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val dur = System.currentTimeMillis() - mDownTime
                    val dx = Math.abs(wp.x - mInitX)
                    val dy = Math.abs(wp.y - mInitY)
                    LogWriter.log("DragFloat", "tap detect dur=" + dur + " dx=" + dx + " dy=" + dy + " onTap=" + (mOnTap != null))
                    if (dur < 300 && dx < 15 && dy < 15 && mOnTap != null) {
                        LogWriter.log("DragFloat", "tap FIRE")
                        try { mOnTap.run() } catch (t: Throwable) { LogWriter.log("DragFloat", "tap err: " + t.message) }
                    }
                    WmPrefs.setInt(mPrefKey + "_x", wp.x)
                    WmPrefs.setInt(mPrefKey + "_y", wp.y)
                    return true
                }
            }
            return false
        }

        fun addToWindow() {
            if (mAdded) return
            try {
                mWM.addView(mView, wp)
                mAdded = true
            } catch (ignored: Throwable) {}
        }

        fun removeFromWindow() {
            if (!mAdded) return
            try { mWM.removeView(mView) } catch (ignored: Exception) {}
            mAdded = false
        }
    }
}