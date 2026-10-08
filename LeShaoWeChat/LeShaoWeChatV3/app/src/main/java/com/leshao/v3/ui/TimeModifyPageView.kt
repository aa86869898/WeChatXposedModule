package com.leshao.v3.ui

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.leshao.v3.ContextManager
import com.leshao.v3.hook.ChatBubbleHook
import com.leshao.v3.ui.widgets.ColorPickerDialog
import com.leshao.v3.ui.widgets.M3Page

/**
 * 聊天时间修改设置页（微信美化 → 聊天时间修改，pageId=35，v3.0.208）。
 *
 * <p>聚合聊天时间线定制能力：</p>
 * <ul>
 *   <li>总开关：聊天时间修改。</li>
 *   <li>自定义时间线内容：Java SimpleDateFormat 语义 token（yyyy/yy/YYYY/MM/M/dd/d/DD/
 *       HH/H/hh/h/mm/ss/SSS/a/E/EEEE/w），空 = 保留微信原生。</li>
 *   <li>聊天时间线颜色：浅色/暗色两套独立配置（同自定义气泡页风格），运行时按微信深色设置自动套用。</li>
 * </ul>
 * <p>v3.0.208：原「聊天时间线颜色」从微信美化页迁移至此，并升级为浅色/暗色双套配置。</p>
 */
class TimeModifyPageView private constructor() {

    companion object {

        @JvmStatic
        fun create(ctx: Context, parentAct: Activity): View {
            val d = ctx.resources.displayMetrics.density
            val prefs = ContextManager.getPrefs()

            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            root.background = CandyUi.pageGradient()
            InsetsUtil.clipRounded(root)
            root.setPadding((AppColors.SPACE_MD_DP * d).toInt(), (6 * d).toInt(),
                    (AppColors.SPACE_MD_DP * d).toInt(), (8 * d).toInt())

            root.addView(M3Page.section(ctx, "聊天时间修改",
                    "自定义聊天记录时间分隔条显示内容与颜色，浅色/暗色各一套独立配置"))
            root.addView(M3Page.spacer(ctx, 2f))

            // ==================== 总开关 ====================
            val cardSwitch = makeCard(ctx, d)
            cardSwitch.addView(switchRow(ctx, d, "聊天时间修改",
                    "开启后按下方格式改写每条消息的时间分隔条", ChatBubbleHook.isTimeModifyEnabled(),
                    { _, on ->
                        ChatBubbleHook.setTimeModifyEnabled(on)
                        Toast.makeText(ctx, "聊天时间修改已" + (if (on) "开启" else "关闭") +
                                (if (on) "（重新进入聊天后生效）" else ""), Toast.LENGTH_SHORT).show()
                    }))
            root.addView(cardSwitch)

            root.addView(M3Page.divider(ctx))

            // ==================== 自定义时间线内容 ====================
            val cardFormat = makeCard(ctx, d)
            val fmtTitle = TextView(ctx)
            fmtTitle.text = "时间线内容格式"
            fmtTitle.setTextSize(16f)
            fmtTitle.setTextColor(AppColors.text1())
            fmtTitle.setTypeface(null, Typeface.BOLD)
            fmtTitle.setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
            cardFormat.addView(fmtTitle)

            val et = M3Page.input(ctx, "如 yyyy-MM-dd HH:mm:ss")
            val curFmt = ChatBubbleHook.getTimeFormat()
            et.setText(if (curFmt == null) "" else curFmt)
            et.setHorizontallyScrolling(true)
            cardFormat.addView(et)

            val pastedTip = TextView(ctx)
            pastedTip.text = "为空 = 保留微信原生显示\n" +
                    "yyyy=4位年 yy=2位年 YYYY=周年\n" +
                    "MM=月(补零) M=月(不补零) dd=日(补零) d=日(不补零) DD=年内第几天\n" +
                    "HH=24时(补零) H=24时(不补零) hh=12时(补零) h=12时(不补零)\n" +
                    "mm=分 ss=秒 SSS=毫秒 a=上午/下午 E=星期简写 EEEE=星期全称 w=当年第几周"
            pastedTip.setTextSize(12f)
            pastedTip.setTextColor(AppColors.text2())
            pastedTip.setLineSpacing(0f, 1.15f)
            pastedTip.setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
            cardFormat.addView(pastedTip)

            val btnRow = LinearLayout(ctx)
            btnRow.orientation = LinearLayout.HORIZONTAL
            btnRow.gravity = Gravity.END

            val previewBtn = textBtn(ctx, d, "预览", AppColors.text2(), false)
            previewBtn.setOnClickListener {
                val f = if (et.text == null) "" else et.text.toString()
                val rendered = ChatBubbleHook.formatTimeLine(System.currentTimeMillis(), f)
                Toast.makeText(ctx, if (rendered != null) ("当前时间渲染: " + rendered) else "格式为空",
                        Toast.LENGTH_SHORT).show()
            }
            btnRow.addView(previewBtn)

            val saveBtn = textBtn(ctx, d, "保存", AppColors.accent(), true)
            saveBtn.setOnClickListener {
                val f = if (et.text == null) "" else et.text.toString()
                ChatBubbleHook.setTimeFormat(f)
                if (f.trim().isEmpty()) {
                    Toast.makeText(ctx, "已恢复微信原生时间显示", Toast.LENGTH_SHORT).show()
                } else {
                    val rendered = ChatBubbleHook.formatTimeLine(System.currentTimeMillis(), f)
                    Toast.makeText(ctx, if (rendered != null) ("格式已保存，示例: " + rendered)
                            else "格式包含无效字符，请检查", Toast.LENGTH_SHORT).show()
                }
            }
            btnRow.addView(saveBtn)

            btnRow.setPadding((12 * d).toInt(), 0, (12 * d).toInt(), (10 * d).toInt())
            cardFormat.addView(btnRow)
            root.addView(cardFormat)

            root.addView(M3Page.divider(ctx))

            // ==================== 浅色模式区：时间线颜色 ====================
            val cardLt = makeCard(ctx, d)
            cardLt.addView(themeHeader(ctx, d, "浅色模式"))
            cardLt.addView(colorRow(ctx, d, "时间线文字颜色",
                    "聊天记录内时间分隔条文字颜色（0 恢复原生）",
                    ChatBubbleHook.getTimeTextColor(ChatBubbleHook.THEME_LIGHT), ChatBubbleHook.THEME_LIGHT))
            root.addView(cardLt)

            // ==================== 暗色模式区：时间线颜色 ====================
            val cardDt = makeCard(ctx, d)
            cardDt.addView(themeHeader(ctx, d, "暗色模式"))
            cardDt.addView(colorRow(ctx, d, "时间线文字颜色",
                    "微信深色模式下时间分隔条文字颜色（0 恢复原生）",
                    ChatBubbleHook.getTimeTextColor(ChatBubbleHook.THEME_DARK), ChatBubbleHook.THEME_DARK))
            root.addView(cardDt)

            return root
        }

        // ==================== 本地构建器 ====================

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

        private fun switchRow(ctx: Context, d: Float, title: String, desc: String?,
                              checked: Boolean, l: CompoundButton.OnCheckedChangeListener?): LinearLayout {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.minimumHeight = (48 * d).toInt()
            row.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            row.background = CandyUi.rowBg(ctx)
            InsetsUtil.clipRounded(row)

            val textCol = LinearLayout(ctx)
            textCol.orientation = LinearLayout.VERTICAL
            textCol.layoutParams = LinearLayout.LayoutParams(0, -2, 1.0f)

            val tv = TextView(ctx)
            tv.text = title
            tv.setTextSize(16f)
            tv.setTextColor(AppColors.text1())
            tv.setTypeface(null, Typeface.BOLD)
            textCol.addView(tv)

            if (desc != null && desc.isNotEmpty()) {
                val dv = TextView(ctx)
                dv.text = desc
                dv.setTextSize(12f)
                dv.setTextColor(AppColors.text2())
                dv.setPadding(0, (3 * d).toInt(), 0, 0)
                textCol.addView(dv)
            }
            row.addView(textCol)

            val sw = CandyUi.newSwitch(ctx)
            sw.isChecked = checked
            sw.setOnCheckedChangeListener(l)
            row.addView(sw)
            return row
        }

        /** 主题分区小标题栏（同 BubblePageView.buildThemeHeader）。 */
        private fun themeHeader(ctx: Context, d: Float, title: String): View {
            val h = TextView(ctx)
            h.text = title
            h.setTextSize(13f)
            h.setTextColor(AppColors.accent())
            h.setTypeface(null, Typeface.BOLD)
            h.setPadding((12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
            h.setBackgroundColor(0x0A000000)
            return h
        }

        /** 颜色修改行（色块预览 + [取色]，0 = 恢复微信原生）。按主题写浅/暗独立配置。 */
        private fun colorRow(ctx: Context, d: Float, title: String, desc: String?,
                             current: Int, theme: Int): LinearLayout {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.minimumHeight = (48 * d).toInt()
            row.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            row.background = CandyUi.rowBg(ctx)
            InsetsUtil.clipRounded(row)

            val textCol = LinearLayout(ctx)
            textCol.orientation = LinearLayout.VERTICAL
            textCol.layoutParams = LinearLayout.LayoutParams(0, -2, 1.0f)

            val tv = TextView(ctx)
            tv.text = title
            tv.setTextSize(16f)
            tv.setTextColor(AppColors.text1())
            tv.setTypeface(null, Typeface.BOLD)
            textCol.addView(tv)

            if (desc != null && desc.isNotEmpty()) {
                val dv = TextView(ctx)
                dv.text = desc
                dv.setTextSize(12f)
                dv.setTextColor(AppColors.text2())
                dv.setPadding(0, (3 * d).toInt(), 0, 0)
                textCol.addView(dv)
            }
            row.addView(textCol)

            val sz = (22 * d).toInt()
            val slp = LinearLayout.LayoutParams(sz, sz)
            slp.setMargins(0, 0, (10 * d).toInt(), 0)
            val swatch = View(ctx)
            swatch.layoutParams = slp
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.RECTANGLE
            gd.setCornerRadius(6 * d)
            if (current == 0) {
                gd.setColor(AppColors.inputBg())
                gd.setStroke((1 * d).toInt(), AppColors.outlineVariant())
            } else {
                gd.setColor(current)
                gd.setStroke((1 * d).toInt(), 0x33000000)
            }
            swatch.background = gd
            row.addView(swatch)

            val btn = TextView(ctx)
            btn.text = "[取色]"
            btn.setTextSize(12f)
            btn.setTextColor(AppColors.accent())
            btn.setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
            btn.paintFlags = btn.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            CandyUi.ripple(btn, AppColors.SHAPE_FULL_DP.toFloat())
            btn.setOnClickListener {
                ColorPickerDialog.show(ctx, title, current, true, object : ColorPickerDialog.OnPick {
                    override fun onPick(color: Int) {
                        ChatBubbleHook.setTimeTextColor(theme, color)
                        Toast.makeText(ctx, if (color == 0) "已恢复默认时间颜色"
                                else "时间颜色已设置，重新进入聊天后生效", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            row.addView(btn)

            return row
        }

        private fun textBtn(ctx: Context, d: Float, text: String, color: Int, bold: Boolean): TextView {
            val tv = TextView(ctx)
            tv.text = text
            tv.setTextSize(14f)
            tv.setTextColor(color)
            if (bold) tv.setTypeface(null, Typeface.BOLD)
            tv.setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
            CandyUi.ripple(tv, AppColors.SHAPE_FULL_DP.toFloat())
            return tv
        }
    }
}