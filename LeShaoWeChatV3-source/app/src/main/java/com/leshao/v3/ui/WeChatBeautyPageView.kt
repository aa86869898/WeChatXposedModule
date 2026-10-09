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
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.leshao.v3.ContextManager
import com.leshao.v3.hook.ChatBubbleHook
import com.leshao.v3.hook.GroupTitleTagHook
import com.leshao.v3.ui.widgets.ColorPickerDialog
import com.leshao.v3.ui.widgets.M3Page

/**
 * 微信美化页（主页「微信美化」卡片入口，v3.0.165）。
 *
 * <p>聚合聊天界面的外观定制能力：</p>
 * <ul>
 *   <li>自定义气泡：分别选择收到/发出消息的气泡图片（进入 [BubblePageView]）。</li>
 *   <li>聊天时间线颜色：聊天记录内时间分隔条文字颜色。</li>
 *   <li>群聊成员昵称颜色：群聊中成员昵称文字颜色。</li>
 *   <li>显示群成员头衔标签：群主/管理员/成员三角色独立配色（背景色+文字色）。</li>
 * </ul>
 * <p>均由原「联系人和群聊 → UI美化」分类迁移而来（v3.0.165 菜单重组）。</p>
 */
class WeChatBeautyPageView private constructor() {

    companion object {

        @JvmStatic
        fun create(ctx: Context, parentAct: Activity): View {
            val d = ctx.resources.displayMetrics.density
            val prefs = ContextManager.getPrefs()
            val act = parentAct

            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            root.background = CandyUi.pageGradient()
            InsetsUtil.clipRounded(root)
            root.setPadding((AppColors.SPACE_MD_DP * d).toInt(), (6 * d).toInt(),
                    (AppColors.SPACE_MD_DP * d).toInt(), (8 * d).toInt())

            // ==================== 自定义气泡 ====================
            val cardBubble = makeCard(ctx, d)
            val bubbleOn = prefs != null && prefs.getBoolean(ChatBubbleHook.K_ENABLED, false)
            cardBubble.addView(switchRow(ctx, d, "自定义气泡",
                    "分别选择收到/发出消息的气泡图片", bubbleOn,
                    { _, on ->
                        if (prefs != null) prefs.edit().putBoolean(ChatBubbleHook.K_ENABLED, on).apply()
                        ChatBubbleHook.setEnabled(on)
                        Toast.makeText(ctx, "自定义气泡已" + (if (on) "开启" else "关闭") +
                                "（重启微信后完全生效）", Toast.LENGTH_SHORT).show()
                    },
                    { SubPageActivity.open(act, "自定义气泡", 27) }))
            root.addView(cardBubble)

            root.addView(candyDivider(ctx, d))

            // ==================== 聊天时间修改（v3.0.208：原「聊天时间线颜色」迁移至此，含自定义格式+浅/暗双套颜色） ====================
            val cardTimeMod = makeCard(ctx, d)
            cardTimeMod.addView(switchRow(ctx, d, "聊天时间修改",
                    "自定义时间线内容 + 时间线颜色（浅色/暗色独立配置）",
                    ChatBubbleHook.isTimeModifyEnabled(),
                    { _, on ->
                        ChatBubbleHook.setTimeModifyEnabled(on)
                        Toast.makeText(ctx, "聊天时间修改已" + (if (on) "开启" else "关闭") +
                                (if (on) "（重新进入聊天后生效）" else ""), Toast.LENGTH_SHORT).show()
                    },
                    { SubPageActivity.open(act, "聊天时间修改", 35) }))
            root.addView(cardTimeMod)

            root.addView(candyDivider(ctx, d))

            // ==================== 群聊成员昵称颜色 ====================
            val cardNickColor = makeCard(ctx, d)
            cardNickColor.addView(colorRow(ctx, d, "群聊成员昵称颜色",
                    "修改群聊中成员昵称文字颜色",
                    ChatBubbleHook.getNickTextColor(), object : ColorPickerDialog.OnPick {
                        override fun onPick(color: Int) {
                            ChatBubbleHook.setNickTextColor(color)
                            Toast.makeText(ctx, if (color == 0) "已恢复默认昵称颜色"
                                    else "昵称颜色已设置，重新进入聊天后生效", Toast.LENGTH_SHORT).show()
                        }
                    }))
            root.addView(cardNickColor)

            root.addView(candyDivider(ctx, d))

            // ==================== 显示群成员头衔标签 ====================
            val cardGroupTitle = makeCard(ctx, d)
            cardGroupTitle.addView(switchRow(ctx, d, "显示群成员头衔标签",
                    "群聊消息昵称旁显示「群主/管理员」头衔，三角色独立配色", GroupTitleTagHook.isEnabled(),
                    { _, on ->
                        GroupTitleTagHook.setEnabled(on)
                        Toast.makeText(ctx, "群成员头衔标签已" + (if (on) "开启" else "关闭") +
                                "（重新进入群聊后生效）", Toast.LENGTH_SHORT).show()
                    }, null))
            cardGroupTitle.addView(candyDivider(ctx, d))
            cardGroupTitle.addView(colorRow(ctx, d, "群主背景色",
                    "群主头衔标签背景色（0 恢复原生）",
                    GroupTitleTagHook.getOwnerBg(), object : ColorPickerDialog.OnPick {
                        override fun onPick(color: Int) {
                            GroupTitleTagHook.setColors(color, GroupTitleTagHook.getOwnerText(),
                                    GroupTitleTagHook.getAdminBg(), GroupTitleTagHook.getAdminText(),
                                    GroupTitleTagHook.getMemberBg(), GroupTitleTagHook.getMemberText())
                            Toast.makeText(ctx, if (color == 0) "已恢复群主背景默认"
                                    else "群主背景色已设置，重新进入群聊后生效", Toast.LENGTH_SHORT).show()
                        }
                    }))
            cardGroupTitle.addView(candyDivider(ctx, d))
            cardGroupTitle.addView(colorRow(ctx, d, "群主文字色",
                    "群主头衔标签文字颜色（0 恢复原生）",
                    GroupTitleTagHook.getOwnerText(), object : ColorPickerDialog.OnPick {
                        override fun onPick(color: Int) {
                            GroupTitleTagHook.setColors(GroupTitleTagHook.getOwnerBg(), color,
                                    GroupTitleTagHook.getAdminBg(), GroupTitleTagHook.getAdminText(),
                                    GroupTitleTagHook.getMemberBg(), GroupTitleTagHook.getMemberText())
                            Toast.makeText(ctx, if (color == 0) "已恢复群主文字默认"
                                    else "群主文字色已设置，重新进入群聊后生效", Toast.LENGTH_SHORT).show()
                        }
                    }))
            cardGroupTitle.addView(candyDivider(ctx, d))
            cardGroupTitle.addView(colorRow(ctx, d, "管理员背景色",
                    "管理员头衔标签背景色（0 恢复原生）",
                    GroupTitleTagHook.getAdminBg(), object : ColorPickerDialog.OnPick {
                        override fun onPick(color: Int) {
                            GroupTitleTagHook.setColors(GroupTitleTagHook.getOwnerBg(), GroupTitleTagHook.getOwnerText(),
                                    color, GroupTitleTagHook.getAdminText(),
                                    GroupTitleTagHook.getMemberBg(), GroupTitleTagHook.getMemberText())
                            Toast.makeText(ctx, if (color == 0) "已恢复管理员背景默认"
                                    else "管理员背景色已设置，重新进入群聊后生效", Toast.LENGTH_SHORT).show()
                        }
                    }))
            cardGroupTitle.addView(candyDivider(ctx, d))
            cardGroupTitle.addView(colorRow(ctx, d, "管理员文字色",
                    "管理员头衔标签文字颜色（0 恢复原生）",
                    GroupTitleTagHook.getAdminText(), object : ColorPickerDialog.OnPick {
                        override fun onPick(color: Int) {
                            GroupTitleTagHook.setColors(GroupTitleTagHook.getOwnerBg(), GroupTitleTagHook.getOwnerText(),
                                    GroupTitleTagHook.getAdminBg(), color,
                                    GroupTitleTagHook.getMemberBg(), GroupTitleTagHook.getMemberText())
                            Toast.makeText(ctx, if (color == 0) "已恢复管理员文字默认"
                                    else "管理员文字色已设置，重新进入群聊后生效", Toast.LENGTH_SHORT).show()
                        }
                    }))
            cardGroupTitle.addView(candyDivider(ctx, d))
            cardGroupTitle.addView(colorRow(ctx, d, "成员背景色",
                    "成员头衔标签背景色（0 恢复原生）",
                    GroupTitleTagHook.getMemberBg(), object : ColorPickerDialog.OnPick {
                        override fun onPick(color: Int) {
                            GroupTitleTagHook.setColors(GroupTitleTagHook.getOwnerBg(), GroupTitleTagHook.getOwnerText(),
                                    GroupTitleTagHook.getAdminBg(), GroupTitleTagHook.getAdminText(),
                                    color, GroupTitleTagHook.getMemberText())
                            Toast.makeText(ctx, if (color == 0) "已恢复成员背景默认"
                                    else "成员背景色已设置，重新进入群聊后生效", Toast.LENGTH_SHORT).show()
                        }
                    }))
            cardGroupTitle.addView(candyDivider(ctx, d))
            cardGroupTitle.addView(colorRow(ctx, d, "成员文字色",
                    "成员头衔标签文字颜色（0 恢复原生）",
                    GroupTitleTagHook.getMemberText(), object : ColorPickerDialog.OnPick {
                        override fun onPick(color: Int) {
                            GroupTitleTagHook.setColors(GroupTitleTagHook.getOwnerBg(), GroupTitleTagHook.getOwnerText(),
                                    GroupTitleTagHook.getAdminBg(), GroupTitleTagHook.getAdminText(),
                                    GroupTitleTagHook.getMemberBg(), color)
                            Toast.makeText(ctx, if (color == 0) "已恢复成员文字默认"
                                    else "成员文字色已设置，重新进入群聊后生效", Toast.LENGTH_SHORT).show()
                        }
                    }))
            root.addView(cardGroupTitle)

            return root
        }

        // ==================== 本地构建器（与 ContactGroupPageView 同风格） ====================

        private fun makeCard(ctx: Context, d: Float): LinearLayout {
            val card = LinearLayout(ctx)
            card.orientation = LinearLayout.VERTICAL
            card.background = CandyUi.cardBg(ctx)
            InsetsUtil.clipRounded(card)
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.setMargins(0, 0, 0, (13 * d).toInt())
            card.layoutParams = lp
            return card
        }

        private fun switchRow(ctx: Context, d: Float, title: String, desc: String?,
                              checked: Boolean, listener: CompoundButton.OnCheckedChangeListener?,
                              configListener: View.OnClickListener?): LinearLayout {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            // 全局规范: 行触控区域不低于 48dp
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

            if (configListener != null) {
                val btn = TextView(ctx)
                btn.text = "[设置]"
                btn.setTextSize(12f)
                btn.setTextColor(AppColors.accent())
                btn.setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
                btn.paintFlags = btn.paintFlags or Paint.UNDERLINE_TEXT_FLAG
                CandyUi.ripple(btn, AppColors.SHAPE_FULL_DP.toFloat())
                btn.setOnClickListener(configListener)
                row.addView(btn)
            }

            val sw = CandyUi.newSwitch(ctx)
            sw.isChecked = checked
            sw.setOnCheckedChangeListener(listener)
            row.addView(sw)
            return row
        }

        /** 颜色修改行（色块预览 + [取色]，0 = 恢复微信原生）。 */
        private fun colorRow(ctx: Context, d: Float, title: String, desc: String?,
                             current: Int, onPick: ColorPickerDialog.OnPick): LinearLayout {
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

            // 色块预览
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
                ColorPickerDialog.show(ctx, title, current, true, onPick)
            }
            row.addView(btn)

            return row
        }

        private fun candyDivider(ctx: Context, d: Float): View {
            return M3Page.divider(ctx)
        }
    }
}