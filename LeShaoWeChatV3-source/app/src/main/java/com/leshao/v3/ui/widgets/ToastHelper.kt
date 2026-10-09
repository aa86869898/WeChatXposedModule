package com.leshao.v3.ui.widgets

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.leshao.v3.ui.AppColors

/** 卡片式 Toast（成功/失败/普通），自动消失，主线程安全 */
object ToastHelper {

    @JvmStatic
    fun show(ctx: Context?, msg: String?) {
        show(ctx, msg, 0)
    }

    @JvmStatic
    fun success(ctx: Context?, msg: String?) {
        show(ctx, "✅ " + msg, AppColors.primary())
    }

    @JvmStatic
    fun error(ctx: Context?, msg: String?) {
        show(ctx, "❌ " + msg, AppColors.error())
    }

    @JvmStatic
    fun show(ctx: Context?, msg: String?, accent: Int) {
        if (ctx == null || msg == null) return
        try {
            val r = Runnable { makeToast(ctx, msg, accent) }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                r.run()
            } else {
                Handler(Looper.getMainLooper()).post(r)
            }
        } catch (ignored: Throwable) {}
    }

    private fun makeToast(ctx: Context, msg: String, accent: Int) {
        try {
            val d = ctx.resources.displayMetrics.density
            val card = LinearLayout(ctx)
            card.orientation = LinearLayout.HORIZONTAL
            card.gravity = Gravity.CENTER_VERTICAL
            val pad = (14 * d).toInt()
            card.setPadding(pad, pad, pad, pad)

            val tv = TextView(ctx)
            tv.text = msg
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            tv.typeface = Typeface.DEFAULT_BOLD
            tv.setTextColor(AppColors.inverseOnSurface())
            tv.maxLines = 3
            tv.ellipsize = TextUtils.TruncateAt.END
            card.addView(tv)

            val bg = GradientDrawable()
            bg.shape = GradientDrawable.RECTANGLE
            bg.setCornerRadius(AppColors.SHAPE_LG_DP * d)
            // M3 snackbar：inverseSurface 底 + inverseOnSurface 字
            bg.setColor(if (accent == 0) AppColors.inverseSurface() else accent)
            card.background = bg

            val pw = PopupWindow(card,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            pw.isFocusable = false
            pw.isOutsideTouchable = true
            val act = resolveActivity(ctx)
            if (act == null || act.isFinishing) return
            val root = act.window.decorView
            pw.showAtLocation(root, Gravity.CENTER, 0, (-40 * d).toInt())
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    pw.dismiss()
                } catch (ignored: Throwable) {}
            }, 2200)
        } catch (ignored: Throwable) {}
    }

    private fun resolveActivity(ctx: Context): Activity? {
        try {
            if (ctx is Activity) return ctx
            if (ctx is ContextWrapper) {
                return resolveActivity(ctx.baseContext)
            }
        } catch (ignored: Throwable) {}
        return null
    }
}