package com.leshao.v3.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Switch

/**
 * LeShaoWeChat 组件工厂 —— Material 3 规范。
 * 公开方法签名与旧版完全一致（全项目调用点零改动），内部实现切换为 M3：
 *  - 开关 52×32dp 轨道 + 16/24dp thumb 双态（M3 Switch 规范）
 *  - 卡片 filled/elevated 双型，12dp 圆角
 *  - 对话框 28dp extra-large 圆角
 *  - 按压涟漪用 M3 状态层（12% onSurface）
 */
class CandyUi private constructor() {

    companion object {
        private fun dp(ctx: Context, v: Float): Int {
            return (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
        }

        private fun dp(ctx: Context, v: Int): Int {
            return (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
        }

        /**
         * M3 开关：轨道 52×32dp（开=深蓝/关=灰），thumb 外圈圆环包裹本体。
         *
         * v1056 关键修复：改用 framework Switch。此前用 SwitchMaterial（appcompat）时，
         * 其构造会读取 appcompat 属性 ID，而模块资源未注入宿主(微信)资源表，属性 ID 与宿主
         * 资源碰撞 → 解析到 res/raw/chatfrom_voice_playing_f3.svg 并抛 Resources$NotFoundException，
         * 导致所有开关创建失败、个性化配置面板整体构建中断。
         */
        @Suppress("DEPRECATION")
        @JvmStatic
        fun newSwitch(ctx: Context): Switch {
            val sw = Switch(ctx)
            val d = ctx.resources.displayMetrics.density
            val w = (AppColors.SWITCH_WIDTH_DP * d).toInt()
            val h = (AppColors.SWITCH_HEIGHT_DP * d).toInt()
            val trackR = (AppColors.SWITCH_RADIUS_DP * d).toInt()
            val thumbOff = (20 * d).toInt()
            val thumbOn = (26 * d).toInt()
            sw.minimumWidth = w
            sw.minimumHeight = h
            sw.setPadding(0, 0, 0, 0)
            sw.textOff = ""
            sw.textOn = ""
            sw.showText = false
            try {
                // 自绘 track/thumb 精确保留原配色与尺寸；清空框架 tint 避免覆盖自定义绘制(API 21+)。
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    sw.trackTintList = null
                    sw.thumbTintList = null
                }
                // v967 关键修复: 自定义 track/thumb 为 GradientDrawable 时无 intrinsic size,
                // 必须对每个 drawable 调用 setSize() 显式提供 intrinsic 尺寸。
                val track = StateListDrawable()
                // 关闭态轨道：玻璃实底 + 发丝描边。
                val off = GradientDrawable()
                off.shape = GradientDrawable.RECTANGLE
                off.cornerRadius = trackR.toFloat()
                off.setColor(AppColors.glassRowFill())
                off.setStroke(dp(ctx, 2), AppColors.glassStroke())
                off.setSize(w, h)
                track.addState(intArrayOf(-android.R.attr.state_checked), off)
                // 打开状态轨道：主色实底，圆角胶囊形。
                val on = GradientDrawable()
                on.setColor(AppColors.primary())
                on.cornerRadius = trackR.toFloat()
                on.setSize(w, h)
                track.addState(intArrayOf(android.R.attr.state_checked), on)
                sw.trackDrawable = track

                // thumb：白色圆形 + 底部浅阴影，开/关状态一致。
                val thumb = StateListDrawable()
                thumb.addState(intArrayOf(-android.R.attr.state_checked), whiteThumb(ctx, thumbOff))
                thumb.addState(intArrayOf(android.R.attr.state_checked), whiteThumb(ctx, thumbOn))
                sw.thumbDrawable = thumb

                sw.background = null
                sw.switchMinWidth = w
            } catch (ignored: Throwable) {
            }
            return sw
        }

        /**
         * 白色圆形 thumb：底部叠加 1dp 半透明阴影层，形成浅投影。
         * 仅使用 framework drawable，兼容微信宿主进程（无 Material 依赖）。
         */
        private fun whiteThumb(ctx: Context, sizePx: Int): Drawable {
            val shadow = Math.max(1, dp(ctx, 1))
            val shadowLayer = GradientDrawable()
            shadowLayer.shape = GradientDrawable.OVAL
            shadowLayer.setColor(0x33000000)
            shadowLayer.setSize(sizePx, sizePx)

            val body = GradientDrawable()
            body.shape = GradientDrawable.OVAL
            body.setColor(0xFFFFFFFF.toInt())
            val inner = Math.max(sizePx - shadow * 2, 1)
            body.setSize(inner, inner)

            val ld = LayerDrawable(arrayOf<Drawable>(shadowLayer, body))
            ld.setLayerInset(1, shadow, 0, shadow, shadow * 2)
            return ld
        }

        /**
         * 页面根背景：M3 surface 纯色 + 28dp 圆角浮层。
         *
         * v987 统一透明化：全屏页面统一改为圆角浮层，圆角外区域由透明窗口露出宿主，
         * 不再用直角实底填满整屏（避免在状态栏/安全区露出白色实底间隔）。
         */
        @JvmStatic
        fun pageGradient(): GradientDrawable {
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.RECTANGLE
            val d = Resources.getSystem().displayMetrics.density
            gd.cornerRadius = AppColors.DIALOG_RADIUS_DP * d
            // v3.0.457 实色玻璃：半透明实底 + 发丝描边，圆角外透出底帘
            gd.setColor(AppColors.glassFill())
            gd.setStroke(Math.max(1, (1.0f * d + 0.5f).toInt()), AppColors.glassStroke())
            return gd
        }

        /** v3.0.101：主色面 · v3.0.457 实色圆角底（按钮/徽标/FAB） */
        @JvmStatic
        fun gradientBg(ctx: Context, radiusDp: Float): Drawable {
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.RECTANGLE
            gd.cornerRadius = dp(ctx, radiusDp).toFloat()
            gd.setColor(AppColors.primary())
            return gd
        }

        /** v3.0.101：糖果粉纯色圆角底，用于按钮/徽标/卡片等主色面 */
        @JvmStatic
        fun gradientBgStatic(ctx: Context, radiusDp: Float): Drawable {
            return gradientBg(ctx, radiusDp)
        }

        /** v3.0.101：顶栏/Hero · v3.0.457 实色主色栏（无渐变，仅上方圆角贴合页面浮层） */
        @JvmStatic
        fun topBarGradientBg(ctx: Context): Drawable {
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.RECTANGLE
            val r = AppColors.DIALOG_RADIUS_DP * ctx.resources.displayMetrics.density
            gd.setCornerRadii(floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f))
            gd.setColor(AppColors.primary())
            return gd
        }

        /**
         * v998: 给浮层容器附加轻微阴影(硬件层 elevation)，与 pageGradient() 的描边配合，
         * 让居中浮层与宿主画面之间产生柔和层次。
         */
        @JvmStatic
        fun elevate(v: View?) {
            if (v == null) return
            try {
                val d = v.resources.displayMetrics.density
                v.elevation = 12f * d
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    v.outlineProvider = ViewOutlineProvider.BACKGROUND
                }
            } catch (ignored: Throwable) {
            }
        }

        /** M3 Expressive filled 卡片 · v3.0.456 玻璃卡：受光渐变 + 玻璃描边 */
        @JvmStatic
        fun cardBg(ctx: Context): GradientDrawable {
            return glassPanel(ctx, AppColors.SHAPE_CARD_DP.toFloat())
        }

        /** M3 Expressive elevated 卡片 · v3.0.456 玻璃卡（同 filled，描边恒显） */
        @JvmStatic
        fun cardElevatedBg(ctx: Context): GradientDrawable {
            return glassPanel(ctx, AppColors.SHAPE_CARD_DP.toFloat())
        }

        /** M3 Expressive 行背景 · v3.0.457 玻璃行：实色行填充 + 发丝描边 */
        @JvmStatic
        fun rowBg(ctx: Context): GradientDrawable {
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.RECTANGLE
            gd.cornerRadius = dp(ctx, AppColors.SHAPE_LG_DP).toFloat()
            gd.setColor(AppColors.glassRowFill())
            gd.setStroke(dp(ctx, 1), blend(AppColors.glassStroke(), 0x00FFFFFF, 0.35f))
            return gd
        }

        /** M3 Expressive 指定圆角卡片 · v3.0.456 玻璃卡（供页面自定义卡片半径时使用） */
        @JvmStatic
        fun cardBgRadius(ctx: Context, radiusDp: Float): GradientDrawable {
            return glassPanel(ctx, radiusDp)
        }

        /** M3 Expressive filter chip · v3.0.457 实色药丸：选中=主色实底白字 / 未选中=玻璃底 + 描边 */
        @JvmStatic
        fun pillBg(selected: Boolean, ctx: Context): Drawable {
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.RECTANGLE
            gd.cornerRadius = dp(ctx, AppColors.SHAPE_FULL_DP).toFloat()
            if (selected) {
                gd.setColor(AppColors.primary())
                return gd
            }
            gd.setColor(AppColors.glassRowFill())
            gd.setStroke(dp(ctx, 1), AppColors.glassStroke())
            return gd
        }

        /** M3 标签底：primaryContainer 底 + 8dp 圆角 */
        @JvmStatic
        fun tagPinkBg(ctx: Context): GradientDrawable {
            return roundedRect(AppColors.primaryContainer(), dp(ctx, AppColors.SHAPE_SM_DP))
        }

        /** M3 标签底：tertiaryContainer 底 + 8dp 圆角 */
        @JvmStatic
        fun tagYellowBg(ctx: Context): GradientDrawable {
            return roundedRect(AppColors.tertiaryContainer(), dp(ctx, AppColors.SHAPE_SM_DP))
        }

        /** M3 filled text field · v3.0.457 玻璃输入框：实底 + 发丝描边 */
        @JvmStatic
        fun inputBg(ctx: Context): GradientDrawable {
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.RECTANGLE
            gd.cornerRadius = dp(ctx, AppColors.SHAPE_INPUT_DP).toFloat()
            gd.setColor(AppColors.glassFillSolid())
            gd.setStroke(dp(ctx, 1), AppColors.glassStroke())
            return gd
        }

        /** M3 filled button · v3.0.457 实色主钮 + 深压态 + 状态层涟漪 */
        @JvmStatic
        fun buttonBg(ctx: Context): Drawable {
            val sd = StateListDrawable()
            val r = dp(ctx, AppColors.SHAPE_MD_DP).toFloat()
            val pressed = GradientDrawable()
            pressed.shape = GradientDrawable.RECTANGLE
            pressed.cornerRadius = r
            pressed.setColor(blend(AppColors.primary(), 0xFF000000.toInt(), 0.22f))
            val normal = GradientDrawable()
            normal.shape = GradientDrawable.RECTANGLE
            normal.cornerRadius = r
            normal.setColor(AppColors.primary())
            sd.addState(intArrayOf(android.R.attr.state_pressed), pressed)
            sd.addState(intArrayOf(), normal)
            return rippleWrap(ctx, sd, AppColors.stateLayerOnPrimary())
        }

        /** M3 outlined button · v3.0.457 玻璃幽灵钮：半透明实底 + 发丝描边 + 涟漪 */
        @JvmStatic
        fun buttonGhostBg(ctx: Context): Drawable {
            val sd = StateListDrawable()
            val pressed = GradientDrawable()
            pressed.shape = GradientDrawable.RECTANGLE
            pressed.cornerRadius = dp(ctx, AppColors.SHAPE_MD_DP).toFloat()
            pressed.setColor(AppColors.stateLayerPressed())
            val normal = GradientDrawable()
            normal.shape = GradientDrawable.RECTANGLE
            normal.cornerRadius = dp(ctx, AppColors.SHAPE_MD_DP).toFloat()
            normal.setColor(AppColors.glassFill())
            normal.setStroke(dp(ctx, 1), AppColors.glassStroke())
            sd.addState(intArrayOf(android.R.attr.state_pressed), pressed)
            sd.addState(intArrayOf(), normal)
            return rippleWrap(ctx, sd, AppColors.stateLayerPressed())
        }

        /** M3 filled tonal button（危险）：error 底 + 长形微圆角 + 状态层涟漪 */
        @JvmStatic
        fun buttonDangerBg(ctx: Context): Drawable {
            val sd = StateListDrawable()
            val pressed = GradientDrawable()
            pressed.shape = GradientDrawable.RECTANGLE
            pressed.cornerRadius = dp(ctx, AppColors.SHAPE_MD_DP).toFloat()
            pressed.setColor(0xFF8C0F16.toInt())
            val normal = GradientDrawable()
            normal.shape = GradientDrawable.RECTANGLE
            normal.cornerRadius = dp(ctx, AppColors.SHAPE_MD_DP).toFloat()
            normal.setColor(AppColors.error())
            sd.addState(intArrayOf(android.R.attr.state_pressed), pressed)
            sd.addState(intArrayOf(), normal)
            return rippleWrap(ctx, sd, 0x1FFFFFFF)
        }

        /** M3 text button：透明底 + 长形微圆角涟漪 */
        @JvmStatic
        fun buttonTextBg(ctx: Context): Drawable {
            val normal = GradientDrawable()
            normal.shape = GradientDrawable.RECTANGLE
            normal.cornerRadius = dp(ctx, AppColors.SHAPE_MD_DP).toFloat()
            normal.setColor(0x00000000)
            return rippleWrap(ctx, normal, AppColors.stateLayerPressed())
        }

        /** 为任意已 setClickable 的 View 附加 M3 状态层涟漪 foreground（全圆角边界）。 */
        @JvmStatic
        fun ripple(v: View?, radiusDp: Float) {
            if (v == null) return
            try {
                val d = v.resources.displayMetrics.density
                val mask = GradientDrawable()
                mask.shape = GradientDrawable.RECTANGLE
                mask.cornerRadius = radiusDp * d
                mask.setColor(0xFFFFFFFF.toInt())
                v.foreground = RippleDrawable(
                    ColorStateList.valueOf(AppColors.stateLayerPressed()), null, mask)
            } catch (ignored: Throwable) {
            }
        }

        /** 将 StateListDrawable 包成 M3 涟漪(显式全圆角遮罩, 保证透明底按钮也有边界涟漪) */
        private fun rippleWrap(ctx: Context, content: Drawable, rippleColor: Int): Drawable {
            return try {
                val mask = GradientDrawable()
                mask.shape = GradientDrawable.RECTANGLE
                mask.cornerRadius = dp(ctx, AppColors.SHAPE_MD_DP).toFloat()
                mask.setColor(0xFFFFFFFF.toInt())
                RippleDrawable(
                    ColorStateList.valueOf(rippleColor), content, mask)
            } catch (t: Throwable) {
                content
            }
        }

        /** M3 行按压状态层：12% onSurface 涟漪 + 16dp 圆角裁剪 */
        @JvmStatic
        fun rowPressBg(ctx: Context): Drawable {
            return try {
                val mask = GradientDrawable()
                mask.shape = GradientDrawable.RECTANGLE
                mask.cornerRadius = dp(ctx, AppColors.SHAPE_LG_DP).toFloat()
                mask.setColor(AppColors.surfaceContainerLow())
                RippleDrawable(
                    ColorStateList.valueOf(AppColors.stateLayerPressed()),
                    null, mask)
            } catch (t: Throwable) {
                val gd = GradientDrawable()
                gd.shape = GradientDrawable.RECTANGLE
                gd.cornerRadius = dp(ctx, AppColors.SHAPE_LG_DP).toFloat()
                gd.setColor(AppColors.stateLayerPressed())
                gd
            }
        }

        /** v1142: 按当前浮层层级自动区分底色的对话框背景（叠加时更易分辨）。 */
        @JvmStatic
        fun dialogBg(ctx: Context): GradientDrawable {
            return dialogBg(ctx, WindowLayer.depth())
        }

        /**
         * M3 对话框 · v3.0.457 磨砂玻璃：磨砂实底（不过透保可读）+ 层级混色 + 发丝描边。
         */
        @JvmStatic
        fun dialogBg(ctx: Context, layer: Int): GradientDrawable {
            var fill = AppColors.glassDialogFill()
            if (layer > 0) {
                val t = Math.min(0.06f * layer, 0.24f)
                fill = blend(fill, AppColors.primaryContainer(), t)
            }
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.RECTANGLE
            gd.cornerRadius = dp(ctx, AppColors.DIALOG_RADIUS_DP).toFloat()
            gd.setColor(fill)
            gd.setStroke(dp(ctx, 1), AppColors.glassStroke())
            return gd
        }

        /** v1142: 两色线性混色（0=全 c1，1=全 c2）。 */
        @JvmStatic
        fun blend(c1: Int, c2: Int, tIn: Float): Int {
            var t = tIn
            if (t < 0f) t = 0f
            if (t > 1f) t = 1f
            val a1 = (c1 ushr 24) and 0xFF
            val r1 = (c1 shr 16) and 0xFF
            val g1 = (c1 shr 8) and 0xFF
            val b1 = c1 and 0xFF
            val a2 = (c2 ushr 24) and 0xFF
            val r2 = (c2 shr 16) and 0xFF
            val g2 = (c2 shr 8) and 0xFF
            val b2 = c2 and 0xFF
            val a = (a1 + (a2 - a1) * t).toInt()
            val r = (r1 + (r2 - r1) * t).toInt()
            val g = (g1 + (g2 - g1) * t).toInt()
            val b = (b1 + (b2 - b1) * t).toInt()
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        /** v3.0.457 玻璃面板工厂：半透明实底 + 1dp 发丝描边（明暗双模，无渐变）。 */
        private fun glassPanel(ctx: Context, radiusDp: Float): GradientDrawable {
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.RECTANGLE
            gd.cornerRadius = dp(ctx, radiusDp).toFloat()
            gd.setColor(AppColors.glassFillSolid())
            gd.setStroke(dp(ctx, 1), AppColors.glassStroke())
            return gd
        }

        /**
         * v3.0.457 玻璃底栏：贴页底的实底玻璃条（仅上方圆角）+ 发丝描边。
         * 供页面底部操作区使用；实色不过透，保证可读。
         */
        @JvmStatic
        fun bottomBar(ctx: Context): GradientDrawable {
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.RECTANGLE
            val r = (AppColors.SHAPE_XL_DP * ctx.resources.displayMetrics.density)
            gd.setCornerRadii(floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f))
            gd.setColor(AppColors.glassFillSolid())
            gd.setStroke(dp(ctx, 1), AppColors.glassStroke())
            return gd
        }

        /** v3.0.457 玻璃搜索框：药丸形实底 + 发丝描边（供搜索输入区） */
        @JvmStatic
        fun searchBg(ctx: Context): GradientDrawable {
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.RECTANGLE
            gd.cornerRadius = dp(ctx, AppColors.SHAPE_FULL_DP).toFloat()
            gd.setColor(AppColors.glassFillSolid())
            gd.setStroke(dp(ctx, 1), AppColors.glassStroke())
            return gd
        }

        private fun roundedRect(color: Int, radius: Int): GradientDrawable {
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.RECTANGLE
            gd.cornerRadius = radius.toFloat()
            gd.setColor(color)
            return gd
        }
    }
}
