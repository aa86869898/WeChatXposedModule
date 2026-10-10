package com.leshao.v3.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.leshao.v3.hook.ChatBubbleHook
import com.leshao.v3.ui.widgets.ColorPickerDialog
import com.leshao.v3.ui.widgets.M3Page

/**
 * 自定义气泡设置页：浅色/暗色两套独立配置，每套含
 * 「自己文字颜色 / 自己气泡 / 对方文字颜色 / 对方气泡」四行，按顺序平铺。
 * v3.0.204：两区用一行小标题栏隔开，实时生效；语音/位置/名片/链接等其它消息
 * 文字统一跟随当前模式字色。
 * 图片经 SAF 复制到微信私有目录后由 [ChatBubbleHook] 在气泡解析层替换。
 */
class BubblePageView {

    companion object {

        @JvmStatic
        fun create(ctx: Context, parentAct: Activity): View {
            val d = ctx.resources.displayMetrics.density

            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            root.background = CandyUi.pageGradient()
            InsetsUtil.clipRounded(root)
            root.setPadding((AppColors.SPACE_MD_DP * d).toInt(), (6 * d).toInt(),
                    (AppColors.SPACE_MD_DP * d).toInt(), (8 * d).toInt())

            root.addView(M3Page.section(ctx, "自定义气泡",
                    "分别设置对方/自己消息的气泡图片与文字颜色，浅色/暗色各一套独立配置"))
            root.addView(M3Page.spacer(ctx, 2f))

            val card = makeCard(ctx, d)

            // ==================== 浅色模式区 ====================
            card.addView(buildThemeHeader(ctx, d, "浅色模式"))
            card.addView(buildColorRow(ctx, parentAct, d, "自己文字颜色", ChatBubbleHook.KIND_TO, ChatBubbleHook.THEME_LIGHT))
            card.addView(M3Page.divider(ctx))
            card.addView(buildPickRow(ctx, parentAct, d, "自己气泡",
                    ChatBubbleHook.getToPath(ChatBubbleHook.THEME_LIGHT), ChatBubbleHook.KIND_TO, ChatBubbleHook.THEME_LIGHT))
            card.addView(M3Page.divider(ctx))
            card.addView(buildColorRow(ctx, parentAct, d, "对方文字颜色", ChatBubbleHook.KIND_FROM, ChatBubbleHook.THEME_LIGHT))
            card.addView(M3Page.divider(ctx))
            card.addView(buildPickRow(ctx, parentAct, d, "对方气泡",
                    ChatBubbleHook.getFromPath(ChatBubbleHook.THEME_LIGHT), ChatBubbleHook.KIND_FROM, ChatBubbleHook.THEME_LIGHT))

            // ==================== 暗色模式区 ====================
            card.addView(buildThemeHeader(ctx, d, "暗色模式"))
            card.addView(buildColorRow(ctx, parentAct, d, "自己文字颜色", ChatBubbleHook.KIND_TO, ChatBubbleHook.THEME_DARK))
            card.addView(M3Page.divider(ctx))
            card.addView(buildPickRow(ctx, parentAct, d, "自己气泡",
                    ChatBubbleHook.getToPath(ChatBubbleHook.THEME_DARK), ChatBubbleHook.KIND_TO, ChatBubbleHook.THEME_DARK))
            card.addView(M3Page.divider(ctx))
            card.addView(buildColorRow(ctx, parentAct, d, "对方文字颜色", ChatBubbleHook.KIND_FROM, ChatBubbleHook.THEME_DARK))
            card.addView(M3Page.divider(ctx))
            card.addView(buildPickRow(ctx, parentAct, d, "对方气泡",
                    ChatBubbleHook.getFromPath(ChatBubbleHook.THEME_DARK), ChatBubbleHook.KIND_FROM, ChatBubbleHook.THEME_DARK))

            root.addView(card)

            val tip = TextView(ctx)
            tip.text = "微信为深色模式时自动套用「暗色模式」配置，否则套用「浅色模式」。\n" +
                    "文字颜色同时作用于语音动画/秒数、位置、名片、文章链接等其它消息文字，修改后实时生效。"
            tip.setTextSize(12f)
            tip.setTextColor(AppColors.text2())
            tip.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
            root.addView(tip)

            return root
        }

        /** 主题分区小标题栏（浅色模式 / 暗色模式）。 */
        private fun buildThemeHeader(ctx: Context, d: Float, title: String): View {
            val h = TextView(ctx)
            h.text = title
            h.setTextSize(13f)
            h.setTextColor(AppColors.accent())
            h.setTypeface(null, Typeface.BOLD)
            h.setPadding((12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
            h.setBackgroundColor(0x0A000000)
            return h
        }

        private fun buildPickRow(ctx: Context, parentAct: Activity, d: Float,
                                 title: String, currentPath: String?, kind: Int, theme: Int): View {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            row.background = CandyUi.rowPressBg(ctx)

            val textCol = LinearLayout(ctx)
            textCol.orientation = LinearLayout.VERTICAL
            textCol.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)

            val tv = TextView(ctx)
            tv.text = title
            tv.setTextSize(16f)
            tv.setTextColor(AppColors.text1())
            tv.setTypeface(null, Typeface.BOLD)
            textCol.addView(tv)

            val pathTv = TextView(ctx)
            pathTv.text = if (currentPath == null) "未设置" else java.io.File(currentPath).name
            pathTv.setTextSize(12f)
            pathTv.setTextColor(AppColors.text2())
            pathTv.setPadding(0, (2 * d).toInt(), 0, 0)
            pathTv.isSingleLine = true
            textCol.addView(pathTv)
            row.addView(textCol)

            if (currentPath != null) {
                val clearBtn = TextView(ctx)
                clearBtn.text = "清除"
                clearBtn.setTextSize(12f)
                clearBtn.setTextColor(AppColors.text2())
                clearBtn.setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
                CandyUi.ripple(clearBtn, AppColors.SHAPE_FULL_DP.toFloat())
                clearBtn.setOnClickListener {
                    ChatBubbleHook.setBubblePath(kind, theme, null)
                    Toast.makeText(ctx, "已清除", Toast.LENGTH_SHORT).show()
                    SubPageActivity.refreshCurrent(parentAct)
                }
                row.addView(clearBtn)
            }

            val btn = TextView(ctx)
            btn.text = "[选择图片]"
            btn.setTextSize(12f)
            btn.setTextColor(AppColors.accent())
            btn.setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
            CandyUi.ripple(btn, AppColors.SHAPE_FULL_DP.toFloat())
            btn.setOnClickListener { pickImage(ctx, parentAct, d, kind, theme) }
            row.addView(btn)

            return row
        }

        /** 文字颜色行（自定义色板取色，0=不修改）。v3.0.204：设置后立即刷新已渲染聊天窗口。 */
        private fun buildColorRow(ctx: Context, parentAct: Activity, d: Float,
                                  title: String, kind: Int, theme: Int): View {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            row.background = CandyUi.rowPressBg(ctx)

            val textCol = LinearLayout(ctx)
            textCol.orientation = LinearLayout.VERTICAL
            textCol.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)

            val tv = TextView(ctx)
            tv.text = title
            tv.setTextSize(16f)
            tv.setTextColor(AppColors.text1())
            tv.setTypeface(null, Typeface.BOLD)
            textCol.addView(tv)

            val cur = ChatBubbleHook.getTextColor(kind, theme)
            val sub = TextView(ctx)
            sub.text = if (cur == 0) "默认（跟随微信）" else String.format("#%06X", 0xFFFFFF and cur)
            sub.setTextSize(12f)
            sub.setTextColor(AppColors.text2())
            sub.setPadding(0, (2 * d).toInt(), 0, 0)
            textCol.addView(sub)
            row.addView(textCol)

            // 颜色预览块
            val swatch = View(ctx)
            val sz = (22 * d).toInt()
            val slp = LinearLayout.LayoutParams(sz, sz)
            slp.setMargins(0, 0, (10 * d).toInt(), 0)
            swatch.layoutParams = slp
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.RECTANGLE
            gd.setCornerRadius(6 * d)
            if (cur == 0) {
                gd.setColor(AppColors.inputBg())
                gd.setStroke((1 * d).toInt(), AppColors.outlineVariant())
            } else {
                gd.setColor(cur)
                gd.setStroke((1 * d).toInt(), 0x33000000)
            }
            swatch.background = gd
            row.addView(swatch)

            val btn = TextView(ctx)
            btn.text = "[取色]"
            btn.setTextSize(12f)
            btn.setTextColor(AppColors.accent())
            btn.setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
            CandyUi.ripple(btn, AppColors.SHAPE_FULL_DP.toFloat())
            btn.setOnClickListener {
                ColorPickerDialog.show(ctx, title,
                        ChatBubbleHook.getTextColor(kind, theme), true,
                        object : ColorPickerDialog.OnPick {
                            // 确认：写入并刷新渲染，就地重建刷新展现
                            override fun onPick(color: Int) {
                                ChatBubbleHook.setTextColor(kind, theme, color)
                                Toast.makeText(ctx, if (color == 0) "已恢复默认文字颜色" else "文字颜色已设置，已实时生效",
                                        Toast.LENGTH_SHORT).show()
                                SubPageActivity.refreshCurrent(parentAct)
                            }
                        },
                        object : ColorPickerDialog.OnPreview {
                            // v3.0.270：拖动预览只就地刷新本行色块，不更新全局内存/聊天渲染，
                            // 避免取色器取消后聊天文字色停留在预览值无法回退（ColorPickerDialog 无取消回调）。
                            // 确认后由 onPick → setTextColor 持久化并刷新聊天。
                            override fun onPreview(color: Int) {
                                updateRowPreview(d, swatch, sub, color)
                            }
                        })
            }
            row.addView(btn)

            return row
        }

        private fun pickImage(ctx: Context, parentAct: Activity, d: Float, kind: Int, theme: Int) {
            ChatBubbleHook.pickBubbleImage(parentAct, kind, ChatBubbleHook.BubblePickCallback { path ->
                if (path != null) {
                    ChatBubbleHook.setBubblePath(kind, theme, path)
                    Toast.makeText(ctx, "气泡图片已设置，新消息实时生效（已显示的重新进入聊天后更新）",
                            Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, "未选择图片", Toast.LENGTH_SHORT).show()
                }
                SubPageActivity.refreshCurrent(parentAct)
            })
        }

        private fun makeCard(ctx: Context, d: Float): LinearLayout {
            val card = LinearLayout(ctx)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(0, 0, 0, 0)
            card.background = CandyUi.cardBg(ctx)
            InsetsUtil.clipRounded(card)
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.setMargins(0, 0, 0, (13 * d).toInt())
            card.layoutParams = lp
            return card
        }

        /** v3.0.207：拖动取色实时就地刷新本行颜色预览块 + hex 文本（不重建页面）。
         *  color==0 表示恢复默认：显示占位背景 + 「默认（跟随微信）」。 */
        private fun updateRowPreview(d: Float, swatch: View, sub: TextView, color: Int) {
            try {
                val gd = GradientDrawable()
                gd.shape = GradientDrawable.RECTANGLE
                gd.setCornerRadius(6 * d)
                if (color == 0) {
                    gd.setColor(AppColors.inputBg())
                    gd.setStroke((1 * d).toInt(), AppColors.outlineVariant())
                    sub.text = "默认（跟随微信）"
                } else {
                    gd.setColor(color)
                    gd.setStroke((1 * d).toInt(), 0x33000000)
                    sub.text = String.format("#%06X", 0xFFFFFF and color)
                }
                swatch.background = gd
            } catch (ignored: Throwable) {}
        }
    }
}