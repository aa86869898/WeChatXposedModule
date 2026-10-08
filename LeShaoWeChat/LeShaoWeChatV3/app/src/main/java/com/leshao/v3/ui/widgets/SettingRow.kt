package com.leshao.v3.ui.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.leshao.v3.LogWriter
import com.leshao.v3.ui.AppColors
import com.leshao.v3.ui.AvatarHelper
import com.leshao.v3.ui.CandyUi

/**
 * 统一设置行：标题 + 副标题 + 尾部控件（开关 / 箭头 / 自定义）。
 * 用法：new SettingRow(ctx, "⚙", "标题", "副标题").switchOn(true, listener)
 *      new SettingRow(ctx, "👤", "标题", null).arrow(click)
 * 注：v3.0.99 起按需求去掉左侧图标，icon 参数保留仅作兼容，不再渲染。
 */
class SettingRow(ctx: Context, icon: String?, title: String?, sub: String?) : LinearLayout(ctx) {

    private val mIcon: TextView? = null
    private val mTitle: TextView
    private val mSub: TextView
    private val mTail: LinearLayout

    init {
        val d = resources.displayMetrics.density
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val h = (AppColors.ROW_HEIGHT_DP * d).toInt()
        minimumHeight = h
        setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
        isClickable = true
        isFocusable = true
        try {
            background = CandyUi.rowPressBg(ctx)
        } catch (ignored: Throwable) {}

        val textCol = LinearLayout(ctx)
        textCol.orientation = VERTICAL
        val colLp = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        addView(textCol, colLp)

        mTitle = TextView(ctx)
        mTitle.text = title
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        mTitle.typeface = Typeface.DEFAULT_BOLD
        mTitle.setTextColor(AppColors.textPrimary())
        mTitle.setSingleLine(true)
        mTitle.ellipsize = TextUtils.TruncateAt.END
        textCol.addView(mTitle)

        mSub = TextView(ctx)
        mSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        mSub.setTextColor(AppColors.textTertiary())
        mSub.setSingleLine(true)
        mSub.ellipsize = TextUtils.TruncateAt.END
        if (!TextUtils.isEmpty(sub)) {
            mSub.text = sub
            mSub.setPadding(0, (2 * d).toInt(), 0, 0)
            textCol.addView(mSub)
        }

        mTail = LinearLayout(ctx)
        mTail.orientation = HORIZONTAL
        mTail.gravity = Gravity.CENTER_VERTICAL
        addView(mTail, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    /** 用自绘 Drawable 作为图标(彩色圆底样式)，替换默认 emoji 图标位。 */
    private fun applyDrawableIcon(icon: Drawable?) {
        try {
            if (mIcon == null || icon == null) return
            val den = resources.displayMetrics.density
            val size = (36 * den + 0.5f).toInt()
            var end = (12 * den + 0.5f).toInt()
            val old = mIcon!!.layoutParams as? LayoutParams
            if (old != null) end = old.marginEnd
            val iv = ImageView(context)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.setImageDrawable(icon)
            val lp = LayoutParams(size, size)
            lp.marginEnd = end
            val idx = indexOfChild(mIcon)
            if (idx >= 0) {
                removeView(mIcon)
                addView(iv, idx, lp)
            } else {
                addView(iv, 0, lp)
            }
        } catch (ignored: Throwable) {}
    }

    /** 尾部开关；checked 初始态，listener 可空 */
    fun switchOn(checked: Boolean, listener: CompoundButton.OnCheckedChangeListener?): SettingRow {
        try {
            val sw = CandyUi.newSwitch(context)
            sw.isChecked = checked
            if (listener != null) sw.setOnCheckedChangeListener(listener)
            // v968: 固定开关为 52×32dp, 防止父容器把轨道拉伸变形
            val d = resources.displayMetrics.density
            val swW = (AppColors.SWITCH_WIDTH_DP * d + 0.5f).toInt()
            val swH = (AppColors.SWITCH_HEIGHT_DP * d + 0.5f).toInt()
            mTail.addView(sw, LayoutParams(swW, swH))
            // v967 M3 规范: 整行可点 —— 点击行进任意位置切换开关, 修复仅能点中开关
            // 才生效导致的"点按钮没反应"体验问题。
            setOnClickListener {
                try {
                    sw.toggle()
                } catch (ignored: Throwable) {}
            }
        } catch (t: Throwable) {
            LogWriter.log("SettingRow", "switchOn err: " + android.util.Log.getStackTraceString(t))
        }
        return this
    }

    /** 尾部箭头，点击行走点击 */
    fun arrow(onClick: Runnable?): SettingRow {
        try {
            val arrow = TextView(context)
            arrow.text = "›"
            arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            arrow.setTextColor(AppColors.arrow())
            mTail.addView(arrow)
            if (onClick != null) {
                setOnClickListener {
                    try {
                        onClick.run()
                    } catch (t: Throwable) {
                        LogWriter.log("SettingRow", "arrow onClick err: " + android.util.Log.getStackTraceString(t))
                    }
                }
            }
        } catch (ignored: Throwable) {}
        return this
    }

    /** 尾部自定义视图 */
    fun tail(v: View?): SettingRow {
        try {
            if (v != null) mTail.addView(v)
        } catch (ignored: Throwable) {}
        return this
    }

    /** v974: 用联系人/群真实头像替换图标位(加载失败回退首字母底色块)。 */
    fun avatar(username: String?): SettingRow {
        try {
            if (mIcon == null || TextUtils.isEmpty(username)) return this
            val d = resources.displayMetrics.density
            val size = (36 * d).toInt()
            val iv = ImageView(context)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            val fallback = AvatarHelper.letterAvatar(
                username!!.substring(0, Math.min(1, username.length)), size)
            AvatarHelper.loadAvatarAsync(iv, username, size, fallback)
            var lp = mIcon!!.layoutParams as? LayoutParams
            if (lp == null) {
                lp = LayoutParams(size, size)
                lp.marginEnd = (12 * d).toInt()
            }
            val idx = indexOfChild(mIcon)
            if (idx >= 0) {
                removeView(mIcon)
                addView(iv, idx, lp)
            } else {
                addView(iv, 0, lp)
            }
        } catch (ignored: Throwable) {}
        return this
    }

    fun setSub(s: String?): SettingRow {
        mSub.text = s
        return this
    }

    /** v1085: 副标题自定义颜色(已配置名单用绿色) */
    fun subColor(color: Int): SettingRow {
        mSub.setTextColor(color)
        return this
    }

    fun titleBold(bold: Boolean): SettingRow {
        mTitle.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        return this
    }

    companion object {
        /** 用自绘 Drawable 作为图标(彩色圆底样式)，替换默认 emoji 图标位。 */
        @JvmStatic
        fun withIconDrawable(ctx: Context, icon: Drawable?, title: String?, sub: String?): SettingRow {
            val row = SettingRow(ctx, "", title, sub)
            row.applyDrawableIcon(icon)
            return row
        }
    }
}