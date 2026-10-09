package com.leshao.v3.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import com.leshao.v3.hook.BatchAddRecordStore
import com.leshao.v3.ui.widgets.M3Page

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 批量加好友「添加记录」页：顶部 全部/成功/失败 三个切换按钮 + 清空按钮 + 记录列表。
 * 记录由 BatchAddRecordStore 持久化，重启微信不丢失。
 */
class BatchAddRecordPageView {

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

            root.addView(M3Page.section(ctx, "批量加好友记录",
                    "全部/成功/失败记录，重启微信不丢失"))

            val listArea = LinearLayout(ctx)
            listArea.orientation = LinearLayout.VERTICAL

            val currentTab = intArrayOf(0)

            val tabBar = LinearLayout(ctx)
            tabBar.orientation = LinearLayout.HORIZONTAL
            tabBar.gravity = Gravity.CENTER
            tabBar.background = CandyUi.cardBg(ctx)
            InsetsUtil.clipRounded(tabBar)
            tabBar.setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())

            val tabs = arrayOf("全部", "成功", "失败")

            for (i in tabs.indices) {
                val idx = i
                val tab = TextView(ctx)
                tab.text = tabs[i]
                tab.setTextSize(14f)
                tab.typeface = Typeface.DEFAULT_BOLD
                tab.gravity = Gravity.CENTER
                tab.setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
                tab.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                CandyUi.ripple(tab, AppColors.SHAPE_FULL_DP.toFloat())
                tab.setOnClickListener {
                    currentTab[0] = idx
                    for (j in 0 until tabBar.childCount) {
                        val child = tabBar.getChildAt(j) as TextView
                        child.setTextColor(if (j == idx) AppColors.accent() else AppColors.text2())
                        child.setTypeface(null, if (j == idx) Typeface.BOLD else Typeface.NORMAL)
                    }
                    rebuildList(ctx, parentAct, d, listArea, currentTab[0])
                }
                tab.setTextColor(if (i == 0) AppColors.accent() else AppColors.text2())
                tab.setTypeface(null, if (i == 0) Typeface.BOLD else Typeface.NORMAL)
                tabBar.addView(tab)
            }
            root.addView(tabBar)

            root.addView(M3Page.divider(ctx))

            val actionRow = LinearLayout(ctx)
            actionRow.orientation = LinearLayout.HORIZONTAL
            actionRow.gravity = Gravity.CENTER_VERTICAL
            actionRow.setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())

            val countTv = TextView(ctx)
            countTv.setTextSize(12f)
            countTv.setTextColor(AppColors.text2())
            countTv.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            actionRow.addView(countTv)

            val clearBtn = TextView(ctx)
            clearBtn.text = "清空记录"
            clearBtn.setTextSize(12f)
            clearBtn.setTextColor(AppColors.error())
            clearBtn.setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
            CandyUi.ripple(clearBtn, AppColors.SHAPE_FULL_DP.toFloat())
            clearBtn.setOnClickListener {
                val dlgClear = AlertDialog.Builder(ctx)
                        .setTitle("清空记录")
                        .setMessage("确定要清空全部添加记录吗？此操作不可恢复。")
                        .setPositiveButton("清空") { _, _ ->
                            BatchAddRecordStore.clear()
                            rebuildList(ctx, parentAct, d, listArea, currentTab[0])
                            Toast.makeText(ctx, "记录已清空", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("取消", null)
                        .create()
                dlgClear.show()
            }
            actionRow.addView(clearBtn)
            root.addView(actionRow)

            root.addView(listArea)

            rebuildList(ctx, parentAct, d, listArea, currentTab[0])

            val all = BatchAddRecordStore.getAll()
            countTv.text = "共 " + all.size + " 条记录"

            return root
        }

        private fun rebuildList(ctx: Context, parentAct: Activity, d: Float,
                               listArea: LinearLayout, tab: Int) {
            listArea.removeAllViews()
            val all = BatchAddRecordStore.getAll()
            if (all.isEmpty()) {
                val empty = TextView(ctx)
                empty.text = "暂无添加记录"
                empty.setTextSize(13f)
                empty.setTextColor(AppColors.text2())
                empty.gravity = Gravity.CENTER
                empty.setPadding(0, (40 * d).toInt(), 0, (40 * d).toInt())
                listArea.addView(empty)
                return
            }

            val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
            var shown = 0
            for (r in all) {
                if (tab == 1 && !r.success) continue
                if (tab == 2 && r.success) continue
                listArea.addView(buildRow(ctx, d, r, fmt.format(Date(r.time))))
                shown++
                if (shown >= 200) break
            }
            if (shown == 0) {
                val empty = TextView(ctx)
                empty.text = "无匹配记录"
                empty.setTextSize(13f)
                empty.setTextColor(AppColors.text2())
                empty.gravity = Gravity.CENTER
                empty.setPadding(0, (40 * d).toInt(), 0, (40 * d).toInt())
                listArea.addView(empty)
            }
        }

        private fun buildRow(ctx: Context, d: Float, r: BatchAddRecordStore.Record, timeStr: String): View {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            row.background = CandyUi.rowBg(ctx)
            InsetsUtil.clipRounded(row)

            val textCol = LinearLayout(ctx)
            textCol.orientation = LinearLayout.VERTICAL
            textCol.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)

            val name = TextView(ctx)
            val dn = r.displayName
            name.text = if (!dn.isNullOrEmpty()) dn else r.username
            name.setTextSize(14f)
            name.setTextColor(AppColors.text1())
            name.setTypeface(null, Typeface.BOLD)
            textCol.addView(name)

            val sub = TextView(ctx)
            val un = r.username
            sub.text = timeStr + if (!un.isNullOrEmpty() && un != r.displayName) "  $un" else ""
            sub.setTextSize(12f)
            sub.setTextColor(AppColors.text3())
            sub.setPadding(0, (2 * d).toInt(), 0, 0)
            textCol.addView(sub)

            val reason = TextView(ctx)
            reason.text = r.reason ?: ""
            reason.setTextSize(12f)
            reason.setTextColor(AppColors.text3())
            reason.setPadding(0, (2 * d).toInt(), 0, 0)
            if (!r.reason.isNullOrEmpty()) textCol.addView(reason)

            row.addView(textCol)

            val badge = TextView(ctx)
            badge.text = if (r.success) "成功" else "失败"
            badge.setTextSize(12f)
            badge.setTextColor(AppColors.whiteTextOnAccent())
            badge.gravity = Gravity.CENTER
            badge.setPadding((12 * d).toInt(), (5 * d).toInt(), (12 * d).toInt(), (5 * d).toInt())
            val bg = GradientDrawable()
            bg.setColor(if (r.success) AppColors.primary() else AppColors.error())
            bg.setCornerRadius(AppColors.SHAPE_FULL_DP * d)
            badge.background = bg
            row.addView(badge)

            return row
        }
    }
}
