package com.leshao.v3.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.leshao.v3.LogWriter
import com.leshao.v3.hook.BatchInviteManager
import com.leshao.v3.hook.JoinRecordStore
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 自动扫码进群记录页（v3.0.516 新增）。
 *
 * <p>展示成功进群的记录列表：群头像 + 群名称 + 进群时间。
 * 底部固定栏提供「清空记录」与「返回」。</p>
 */
class JoinRecordPageView {

    companion object {

        @JvmStatic
        fun create(ctx: Context, parentAct: Activity): View {
            val d = ctx.resources.displayMetrics.density
            val act = parentAct

            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            root.setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            // 自带底部栏，SubPageActivity 不再外套滚动层
            root.tag = "no_wrap"

            // 说明
            val tip = TextView(ctx)
            tip.text = "以下为自动扫码进群成功记录（最多 200 条，保存在本机）。"
            tip.setTextSize(12f)
            tip.setTextColor(AppColors.text2())
            tip.setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (10 * d).toInt())
            root.addView(tip)

            // 列表区（ScrollView）
            val sv = android.widget.ScrollView(ctx)
            sv.isFillViewport = true
            sv.isVerticalScrollBarEnabled = true
            val listContainer = LinearLayout(ctx)
            listContainer.orientation = LinearLayout.VERTICAL
            sv.addView(listContainer)
            root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

            // 空态
            val empty = TextView(ctx)
            empty.text = "暂无进群记录"
            empty.setTextSize(14f)
            empty.setTextColor(AppColors.text2())
            empty.gravity = Gravity.CENTER
            empty.setPadding(0, (40 * d).toInt(), 0, (40 * d).toInt())
            listContainer.addView(empty)

            fun refreshList() {
                listContainer.removeAllViews()
                val records = JoinRecordStore.getAll()
                if (records.isEmpty()) {
                    listContainer.addView(empty)
                    return
                }
                for (r in records) {
                    listContainer.addView(buildRow(ctx, d, r))
                }
            }
            refreshList()

            // 底部按钮栏
            val bottom = LinearLayout(ctx)
            bottom.orientation = LinearLayout.HORIZONTAL
            bottom.gravity = Gravity.CENTER
            bottom.setPadding(0, (8 * d).toInt(), 0, 0)

            val btnClear = TextView(ctx)
            btnClear.text = "清空记录"
            btnClear.setTextSize(15f)
            btnClear.setTextColor(Color.rgb(220, 90, 90))
            btnClear.gravity = Gravity.CENTER
            btnClear.setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt())
            CandyUi.ripple(btnClear, (12 * d))
            btnClear.setOnClickListener {
                android.app.AlertDialog.Builder(act)
                        .setTitle("清空进群记录")
                        .setMessage("确定清空全部进群记录？")
                        .setPositiveButton("清空") { _, _ ->
                            JoinRecordStore.clear()
                            refreshList()
                        }
                        .setNegativeButton("取消", null)
                        .show()
            }
            bottom.addView(btnClear, LinearLayout.LayoutParams(0, -2, 1f))

            val btnBack = TextView(ctx)
            btnBack.text = "返回"
            btnBack.setTextSize(15f)
            btnBack.setTextColor(AppColors.accent())
            btnBack.typeface = Typeface.DEFAULT_BOLD
            btnBack.gravity = Gravity.CENTER
            btnBack.setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt())
            CandyUi.ripple(btnBack, (12 * d))
            btnBack.setOnClickListener {
                try {
                    SubPageActivity.closePage(parentAct)
                } catch (t: Throwable) {
                    parentAct.finish()
                }
            }
            bottom.addView(btnBack, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(bottom)

            return root
        }

        private fun buildRow(ctx: Context, d: Float, r: JoinRecordStore.Record): LinearLayout {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            row.background = CandyUi.rowBg(ctx)
            InsetsUtil.clipRounded(row)

            // 群头像
            val size = (40 * d).toInt()
            val avatar = ImageView(ctx)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.setMargins(0, 0, (10 * d).toInt(), 0)
            avatar.layoutParams = lp
            val fallback = letterAvatar(ctx, r.room, size)
            try {
                AvatarHelper.loadAvatarAsync(avatar, r.room, size, fallback)
            } catch (t: Throwable) {
                avatar.setImageBitmap(fallback)
            }
            row.addView(avatar)

            // 文本列：群名 + 时间
            val textCol = LinearLayout(ctx)
            textCol.orientation = LinearLayout.VERTICAL
            textCol.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)

            val name = TextView(ctx)
            val displayName = try {
                BatchInviteManager.roomDisplayName(r.room)
            } catch (t: Throwable) {
                r.room
            }
            name.text = displayName
            name.setTextSize(14f)
            name.setTextColor(AppColors.text1())
            name.setTypeface(null, Typeface.BOLD)
            name.setSingleLine(true)
            textCol.addView(name)

            val time = TextView(ctx)
            time.text = formatTime(r.time)
            time.setTextSize(12f)
            time.setTextColor(AppColors.text2())
            time.setPadding(0, (2 * d).toInt(), 0, 0)
            textCol.addView(time)

            row.addView(textCol)
            return row
        }

        /** 首字母圆形占位头像（群名首字）。 */
        private fun letterAvatar(ctx: Context, room: String, size: Int): Bitmap {
            var letter = "群"
            try {
                val name = BatchInviteManager.roomDisplayName(room)
                if (!name.isNullOrEmpty() && name != room) {
                    letter = name.substring(0, 1)
                }
            } catch (t: Throwable) {
            }
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val bg = Paint()
            bg.isAntiAlias = true
            bg.color = AppColors.secondaryContainer()
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, bg)
            val tp = Paint()
            tp.isAntiAlias = true
            tp.color = Color.WHITE
            tp.textSize = size * 0.42f
            tp.typeface = Typeface.DEFAULT_BOLD
            tp.textAlign = Paint.Align.CENTER
            val baseline = size / 2f - (tp.ascent() + tp.descent()) / 2f
            canvas.drawText(letter, size / 2f, baseline, tp)
            return bmp
        }

        private fun formatTime(t: Long): String {
            if (t <= 0) return ""
            return try {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(t))
            } catch (e: Throwable) {
                ""
            }
        }
    }
}