package com.leshao.v3.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import com.leshao.v3.hook.RedPacketHook
import com.leshao.v3.ui.widgets.M3Page

/** 红包转账功能页：自动抢红包 + 自动回复 + 红包统计 + 排行榜单。 */
class RedPacketPageView private constructor() {

    companion object {

        @JvmStatic
        fun create(ctx: Context, act: Activity): View {
            val d = ctx.resources.displayMetrics.density
            val root = PageKit.pageRoot(ctx)
            RedPacketHook.updateConfig()

            // v1145: 页面顶部统一分区标题
            root.addView(M3Page.section(ctx, "红包转账",
                    "自动抢红包 · 自动回复 · 红包统计 · 排行榜单"))
            root.addView(M3Page.spacer(ctx, 2f))

            val cardSwitch = PageKit.makeCard(ctx, d)
            cardSwitch.addView(PageKit.switchRow(ctx, d, "自动抢红包",
                    "纯后台解析红包消息并自动领取", RedPacketHook.isEnabled(),
                    { _, on ->
                        RedPacketHook.setEnabled(on)
                        Toast.makeText(ctx, "自动抢红包已" + (if (on) "开启" else "关闭")
                                + "（重启微信后完全生效）", Toast.LENGTH_SHORT).show()
                    }, null))
            cardSwitch.addView(PageKit.switchRow(ctx, d, "仅抢群聊",
                    "单聊红包不领取，降低风控风险", RedPacketHook.isGroupOnly(),
                    { _, on -> RedPacketHook.setGroupOnly(on) }, null))
            cardSwitch.addView(PageKit.switchRow(ctx, d, "领取红包自动播报金额",
                    "自动领取后 TTS 播报收到金额", RedPacketHook.isVoiceEnabled(),
                    { _, on -> RedPacketHook.setVoiceEnabled(on) }, null))
            root.addView(cardSwitch)
            root.addView(PageKit.divider(ctx))

            // v3.0.561: 抢到红包自动回复（设置入口在开关左侧）
            val cardReply = PageKit.makeCard(ctx, d)
            cardReply.addView(PageKit.sectionLabel(ctx, "抢到红包自动回复"))
            cardReply.addView(PageKit.switchRow(ctx, d, "自动回复",
                    "抢到红包后随机回复一条（回复语在左侧设置）", RedPacketHook.isAutoReplyEnabled(),
                    { _, on -> RedPacketHook.setAutoReplyEnabled(on) },
                    { SubPageActivity.open(act, "红包回复语", 38) }))
            cardReply.addView(PageKit.switchRow(ctx, d, "TTS 语音回复",
                    "设置 TTS 专属回复语，勾选后随机转语音发出", RedPacketHook.isAutoReplyTts(),
                    { _, on -> RedPacketHook.setAutoReplyTts(on) },
                    { SubPageActivity.open(act, "红包TTS回复语", 39) }))
            root.addView(cardReply)
            root.addView(PageKit.divider(ctx))

            // 参数：随机延迟 + 白名单
            val cardParam = PageKit.makeCard(ctx, d)
            cardParam.addView(PageKit.sectionLabel(ctx, "参数"))
            cardParam.addView(PageKit.switchRow(ctx, d, "抢红包随机延迟",
                    "开启后按设置上限随机延迟", RedPacketHook.isDelayEnabled(),
                    { _, on -> RedPacketHook.setDelayEnabled(on) },
                    { showDelayDialog(ctx, d) }))
            cardParam.addView(makeMenuRow(ctx, d, "抢红包白名单",
                    "点击选择联系人/群聊（当前 " + whitelistCount() + " 个）") {
                openWhitelistPicker(act, ctx)
            })
            root.addView(cardParam)
            root.addView(PageKit.divider(ctx))

            // 红包统计（持久化，右侧显示金额）
            val stats = RedPacketHook.getIncomeStats()
            val cardIncome = PageKit.makeCard(ctx, d)
            cardIncome.addView(PageKit.sectionLabel(ctx, "红包统计（持久保存）"))
            cardIncome.addView(statRow(ctx, d, "总计收入", stats[0] + "元"))
            cardIncome.addView(statRow(ctx, d, "群聊收入", stats[1] + "元"))
            cardIncome.addView(statRow(ctx, d, "私聊收入", stats[2] + "元"))
            cardIncome.addView(PageKit.actionButton(ctx, "红包排行榜单") {
                SubPageActivity.open(act, "红包排行榜单", 37)
            })
            root.addView(cardIncome)
            return root
        }

        private fun whitelistCount(): Int {
            val wl = RedPacketHook.getWhitelist()
            if (wl.isNullOrEmpty()) return 0
            return wl.split(",").count { it.isNotBlank() }
        }

        private fun openWhitelistPicker(act: Activity, ctx: Context) {
            ContactPickerDialog.show(act, RedPacketHook.getWhitelist(), ContactPickerDialog.MODE_FRIEND,
                    { wxids, _ ->
                        RedPacketHook.setWhitelist(wxids.joinToString(","))
                        Toast.makeText(ctx, "白名单已保存（" + wxids.size + " 个）", Toast.LENGTH_SHORT).show()
                    }, null, "选择红包白名单联系人/群聊")
        }

        private fun showDelayDialog(ctx: Context, d: Float) {
            val et = M3Page.input(ctx, "如 2000")
            et.inputType = InputType.TYPE_CLASS_NUMBER
            et.setText(RedPacketHook.getDelayMax().toString())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (4 * d).toInt())
            val dlg = AlertDialog.Builder(ctx)
                    .setTitle("抢红包随机延迟上限（毫秒）")
                    .setView(et)
                    .setPositiveButton("保存", { _, _ ->
                        val v = et.text?.toString()?.trim()?.toIntOrNull()
                        if (v == null || v < 0) {
                            Toast.makeText(ctx, "请输入非负整数", Toast.LENGTH_SHORT).show()
                        } else {
                            RedPacketHook.setDelayMax(v)
                            Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show()
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show()
            lp.setMargins((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (4 * d).toInt())
            try { dlg.window?.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) } catch (ignored: Throwable) {}
        }

        /** 菜单行：左侧标题，右侧「点击设置」样式，整行可点击 */
        private fun makeMenuRow(ctx: Context, d: Float, title: String, desc: String,
                              onClick: View.OnClickListener?): LinearLayout {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = android.view.Gravity.CENTER_VERTICAL
            row.minimumHeight = (48 * d).toInt()
            row.setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            // 行底必须用小圆角 rowBg：卡片是 24dp 圆角，行再用 24dp 圆角会与卡片边缘叠出裁切
            row.background = com.leshao.v3.ui.CandyUi.rowBg(ctx)
            com.leshao.v3.ui.InsetsUtil.clipRounded(row)
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener(onClick)

            val textCol = LinearLayout(ctx)
            textCol.orientation = LinearLayout.VERTICAL
            textCol.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val tv = TextView(ctx)
            tv.text = title
            tv.setTextSize(16f)
            tv.setTextColor(com.leshao.v3.ui.AppColors.text1())
            tv.setTypeface(null, android.graphics.Typeface.BOLD)
            textCol.addView(tv)
            if (desc.isNotEmpty()) {
                val dv = TextView(ctx)
                dv.text = desc
                dv.setTextSize(12f)
                dv.setTextColor(com.leshao.v3.ui.AppColors.text2())
                dv.setPadding(0, (3 * d).toInt(), 0, 0)
                textCol.addView(dv)
            }
            row.addView(textCol)

            val btn = TextView(ctx)
            btn.text = "选择 >"
            btn.setTextSize(13f)
            btn.setTextColor(com.leshao.v3.ui.AppColors.accent())
            btn.setPadding((6 * d).toInt(), 0, 0, 0)
            row.addView(btn)
            return row
        }

        /** 统计行：左侧标签 + 右侧金额 */
        private fun statRow(ctx: Context, d: Float, label: String, value: String): LinearLayout {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = android.view.Gravity.CENTER_VERTICAL
            row.minimumHeight = (40 * d).toInt()
            row.setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())

            val lv = TextView(ctx)
            lv.text = label
            lv.setTextSize(14f)
            lv.setTextColor(com.leshao.v3.ui.AppColors.text1())
            val lvLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(lv, lvLp)

            val vv = TextView(ctx)
            vv.text = value
            vv.setTextSize(15f)
            vv.setTextColor(com.leshao.v3.ui.AppColors.accent())
            vv.setTypeface(null, android.graphics.Typeface.BOLD)
            row.addView(vv)
            return row
        }
    }
}
