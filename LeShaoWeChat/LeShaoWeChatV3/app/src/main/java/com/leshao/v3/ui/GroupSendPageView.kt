package com.leshao.v3.ui

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

import com.leshao.v3.ContextManager
import com.leshao.v3.hook.AutoForwardHook
import com.leshao.v3.hook.ChatFooterBarHook
import com.leshao.v3.ui.widgets.M3Page
import com.leshao.v3.wm.utils.WmPrefs

/**
 * 乐少群发页（主页「乐少群发」卡片入口，v3.0.165）。
 *
 * v3.0.165 菜单重组：本页移除了原「用户信息」卡片（头像/昵称/微信号），聚合群发相关能力：
 *
 * - 乐少万群定时群发：勾选多个群 + 输入内容 + 可选定时，一键群发。
 * - 自动转发：来源消息自动转发给目标联系人/群聊。
 */
class GroupSendPageView private constructor() {

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

            // ==================== 乐少万群定时群发 ====================
            // v3.0.167：改为纯开关（不再点击进入子页）；打开后聊天输入框快捷菜单才显示「群发」入口
            val cardWanQun = makeCard(ctx, d)
            val wqOn = WmPrefs.get("batch_send", true)
            cardWanQun.addView(switchRow(ctx, d, "乐少万群定时群发",
                    "勾选多个群+定时发送", wqOn,
                    { _, on ->
                        WmPrefs.set("batch_send", on)
                        ChatFooterBarHook.refreshAfterSwitch()
                        Toast.makeText(ctx, "乐少万群定时群发已" + (if (on) "开启" else "关闭"), Toast.LENGTH_SHORT).show()
                    },
                    null))
            root.addView(cardWanQun)

            root.addView(M3Page.divider(ctx))

            // ==================== 自动转发 ====================
            val cardAutoFw = makeCard(ctx, d)
            val afOn = prefs != null && prefs.getBoolean("ls_autofw_enabled", false)
            cardAutoFw.addView(switchRow(ctx, d, "自动转发",
                    "来源消息自动转发给目标联系人/群聊", afOn,
                    { _, on ->
                        if (prefs != null) prefs.edit().putBoolean("ls_autofw_enabled", on).apply()
                        AutoForwardHook.setEnabled(on)
                        if (on) AutoForwardHook.updateConfig(prefs)
                        ChatFooterBarHook.refreshAfterSwitch()
                        Toast.makeText(ctx, "自动转发已" + (if (on) "开启" else "关闭"), Toast.LENGTH_SHORT).show()
                    },
                    { AutoForwardHook.showConfigDialog(parentAct) }))
            root.addView(cardAutoFw)

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
    }
}
