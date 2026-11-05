package com.leshao.v3.ui

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import com.leshao.v3.hook.RedPacketHook
import com.leshao.v3.ui.widgets.M3Page

/** 红包自动回复语设置页：表格式展示（每行一个回复语，左侧勾选启用），支持添加/删除。 */
class RedPacketReplyPageView private constructor() {

    companion object {

        @JvmStatic
        fun create(ctx: Context, act: Activity): View {
            return create(ctx, act, false)
        }

        @JvmStatic
        fun create(ctx: Context, act: Activity, ttsMode: Boolean): View {
            val d = ctx.resources.displayMetrics.density
            val root = PageKit.pageRoot(ctx)

            root.addView(M3Page.section(ctx,
                    if (ttsMode) "红包TTS语音回复语" else "红包回复语",
                    if (ttsMode) "勾选启用的回复语，抢到红包后随机选一条转语音发送"
                    else "勾选启用的回复语，抢到红包后随机从中选一条回复"))
            root.addView(M3Page.spacer(ctx, 2f))

            val card = PageKit.makeCard(ctx, d)
            card.addView(PageKit.sectionLabel(ctx, "回复语列表（点击勾选启用）"))

            // 列表容器（表格式）
            val listContainer = LinearLayout(ctx)
            listContainer.orientation = LinearLayout.VERTICAL
            card.addView(listContainer)

            fun refreshList() {
                listContainer.removeAllViews()
                val items = if (ttsMode) RedPacketHook.getTtsReplyItems() else RedPacketHook.getReplyItems()
                if (items.isEmpty()) {
                    val empty = PageKit.bodyText(ctx, "暂无回复语，请在下方添加")
                    empty.gravity = Gravity.CENTER
                    listContainer.addView(empty)
                }
                for (i in items.indices) {
                    val item = items[i]
                    val text = item[0]
                    val enabled = "1" == item[1]
                    listContainer.addView(makeItemRow(ctx, d, text, enabled, i, ttsMode) {
                        refreshList()
                    })
                }
            }

            // 添加新回复语
            card.addView(PageKit.bodyText(ctx, "新回复语"))
            val etNew = M3Page.input(ctx, "输入新回复语")
            card.addView(etNew)
            card.addView(PageKit.actionButton(ctx, "添加回复语") {
                val t = etNew.text?.toString()?.trim()
                if (t.isNullOrEmpty()) {
                    Toast.makeText(ctx, "请输入回复语", Toast.LENGTH_SHORT).show()
                    return@actionButton
                }
                val items = if (ttsMode) RedPacketHook.getTtsReplyItems() else RedPacketHook.getReplyItems()
                items.add(arrayOf(t, "1"))
                if (ttsMode) RedPacketHook.setTtsReplyItems(items) else RedPacketHook.setReplyItems(items)
                etNew.text = null
                refreshList()
            })

            root.addView(card)
            refreshList()
            return root
        }

        /** 表格行：CheckBox（勾选） + 回复语文本 + 删除按钮 */
        private fun makeItemRow(ctx: Context, d: Float, text: String, enabled: Boolean,
                             index: Int, ttsMode: Boolean, onChanged: () -> Unit): LinearLayout {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.minimumHeight = (44 * d).toInt()
            row.setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())

            val cb = CheckBox(ctx)
            cb.isChecked = enabled
            cb.setOnCheckedChangeListener { _, isChecked ->
                val items = if (ttsMode) RedPacketHook.getTtsReplyItems() else RedPacketHook.getReplyItems()
                if (index < items.size) {
                    items[index][1] = if (isChecked) "1" else "0"
                    if (ttsMode) RedPacketHook.setTtsReplyItems(items) else RedPacketHook.setReplyItems(items)
                }
            }
            row.addView(cb)

            val tv = TextView(ctx)
            tv.text = text
            tv.setTextSize(14f)
            tv.setTextColor(AppColors.text1())
            tv.setPadding((8 * d).toInt(), 0, 0, 0)
            val tvLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(tv, tvLp)

            val del = TextView(ctx)
            del.text = "删除"
            del.setTextSize(12f)
            del.setTextColor(0xFFFF5252.toInt())
            del.setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
            del.setOnClickListener {
                // 用文本内容定位删除（避免 index 在列表重建后失效误删相邻行）
                val items = if (ttsMode) RedPacketHook.getTtsReplyItems() else RedPacketHook.getReplyItems()
                val idx = items.indexOfFirst { it.isNotEmpty() && it[0] == text }
                if (idx >= 0) {
                    items.removeAt(idx)
                    if (ttsMode) RedPacketHook.setTtsReplyItems(items) else RedPacketHook.setReplyItems(items)
                    onChanged()
                }
            }
            row.addView(del)
            return row
        }
    }
}
