package com.leshao.v3.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

import com.leshao.v3.ContactRepository
import com.leshao.v3.LogWriter
import com.leshao.v3.hook.BatchInviteManager
import com.leshao.v3.model.ContactCard
import com.leshao.v3.ui.widgets.ModernButton
import com.leshao.v3.ui.widgets.ModernTopBar

import java.util.ArrayList
import java.util.LinkedHashSet

/**
 * 群成员多选器：与模块联系人选择器同款样式（头像 + 昵称/备注 + 搜索 + 多选）。
 * 数据源为指定群聊的成员 wxid 列表，昵称优先取内存联系人，未命中再查 rcontact 数据库。
 */
class GroupMemberPickerDialog {

    fun interface OnMembersSelected {
        fun onSelected(wxids: Set<String>)
    }

    companion object {

        private const val TAG = "GroupMemberPicker"

        @JvmStatic
        fun show(ctx: Context, room: String, title: String,
                 initial: Set<String>?, callback: OnMembersSelected?) {
            if (ctx == null) return
            // 先弹加载框
            val loadingRef = arrayOfNulls<android.app.AlertDialog>(1)
            try {
                val loading = android.app.AlertDialog.Builder(ctx)
                        .setTitle("LeShao")
                        .setMessage("群成员加载中，请稍候…")
                        .setCancelable(true)
                        .create()
                loading.show()
                loadingRef[0] = loading
            } catch (ignored: Throwable) {
            }

            Thread {
                val members = ArrayList<ContactCard>()
                try {
                    val wxids = BatchInviteManager.membersOf(room)
                    // 先建内存索引（好友/群聊），未命中再查 rcontact
                    val byName = HashMap<String, String>()
                    for (c in ContactRepository.getAll()) {
                        val u = c.username ?: continue
                        c.displayName()?.let { byName[u] = it }
                    }
                    for (wid in wxids) {
                        val card = ContactCard()
                        card.username = wid
                        var name = byName[wid]
                        if (name == null || name.isEmpty()) {
                            name = ContactRepository.queryAnyContactName(wid)
                        }
                        if (name == null || name.isEmpty()) name = wid
                        card.nickname = name
                        members.add(card)
                    }
                    LogWriter.log(TAG, "loaded members=" + members.size + " room=" + room)
                } catch (t: Throwable) {
                    LogWriter.log(TAG, "members load err: " + t)
                }

                Handler(Looper.getMainLooper()).post {
                    try {
                        loadingRef[0]?.dismiss()
                    } catch (ignored: Throwable) {
                    }
                    if (members.isEmpty()) {
                        android.widget.Toast.makeText(ctx, "未获取到群成员", android.widget.Toast.LENGTH_LONG).show()
                        return@post
                    }
                    showDialog(ctx, title, members, initial ?: emptySet(), callback)
                }
            }.start()
        }

        private fun showDialog(ctx: Context, title: String, items: List<ContactCard>,
                               initial: Set<String>, callback: OnMembersSelected?) {
            val selected: MutableSet<String> = LinkedHashSet()
            selected.addAll(initial)

            val p16 = dp(ctx, 16f)
            val p12 = dp(ctx, 12f)
            val p8 = dp(ctx, 8f)
            val p4 = dp(ctx, 4f)

            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            root.background = CandyUi.dialogBg(ctx, WindowLayer.depth())
            InsetsUtil.clipRounded(root)
            CandyUi.elevate(root)

            val topBar = ModernTopBar(ctx, title, false, null)
            root.addView(topBar, LinearLayout.LayoutParams(-1, -2))

            val search = EditText(ctx)
            search.hint = "搜索成员（昵称 / wxid）"
            search.setHintTextColor(AppColors.onSurfaceVariant())
            search.setTextSize(14f)
            search.setTextColor(AppColors.onSurface())
            search.setPadding(p16, dp(ctx, 10f), p16, dp(ctx, 10f))
            search.background = CandyUi.inputBg(ctx)
            search.setSingleLine(true)
            val slp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            slp.setMargins(p12, 0, p12, p8)
            root.addView(search, slp)

            val listRoot = LinearLayout(ctx)
            listRoot.orientation = LinearLayout.VERTICAL
            listRoot.setPadding(p12, 0, p12, 0)

            val sv = ScrollView(ctx)
            sv.addView(listRoot)
            root.addView(sv, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

            val display: MutableList<ContactCard> = ArrayList(items)

            val cancel = ModernButton(ctx, "取消", ModernButton.STYLE_GHOST)
            val toggleAll = ModernButton(ctx, "全选", ModernButton.STYLE_TEXT)
            val confirm = ModernButton(ctx, "确定", ModernButton.STYLE_PRIMARY)

            var refresh: Runnable = Runnable {}
            refresh = Runnable {
                listRoot.removeAllViews()
                val q = search.text.toString().lowercase().trim()
                display.clear()
                for (c in items) {
                    val dn = c.displayName() ?: ""
                    val un = c.username ?: ""
                    if (q.isEmpty() || dn.lowercase().contains(q) || un.lowercase().contains(q)) {
                        display.add(c)
                    }
                }
                var count = 0
                for (c in display) {
                    val wid = c.username ?: continue
                    listRoot.addView(buildRow(ctx, c, wid in selected) {
                        if (wid in selected) selected.remove(wid) else selected.add(wid)
                        refresh.run()
                    })
                    count++
                }
                if (count == 0) {
                    val empty = TextView(ctx)
                    empty.text = "无匹配成员"
                    empty.setTextSize(14f)
                    empty.setTextColor(AppColors.onSurfaceVariant())
                    empty.gravity = Gravity.CENTER
                    empty.setPadding(0, dp(ctx, 40f), 0, 0)
                    listRoot.addView(empty)
                }
                topBar.setTitle("已选 " + selected.size + " 人")
                confirm.setText("确定 (" + selected.size + ")")
            }

            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { refresh.run() }
            })

            refresh.run()

            val bottomBar = LinearLayout(ctx)
            bottomBar.orientation = LinearLayout.VERTICAL
            bottomBar.setPadding(p16, p8, p16, p12)

            val btns = LinearLayout(ctx)
            btns.orientation = LinearLayout.HORIZONTAL
            btns.gravity = Gravity.CENTER

            // 底部按钮：取消靠左、全选居中、确定靠右，等宽均分（8dp 间距）
            val btnLp = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            btns.gravity = Gravity.CENTER
            btns.addView(cancel, btnLp)
            val sp1 = View(ctx)
            btns.addView(sp1, LinearLayout.LayoutParams(dp(ctx, 8f), 1))
            btns.addView(toggleAll, btnLp)
            val sp2 = View(ctx)
            btns.addView(sp2, LinearLayout.LayoutParams(dp(ctx, 8f), 1))
            btns.addView(confirm, btnLp)
            bottomBar.addView(btns)
            root.addView(bottomBar)

            val dialog = android.app.AlertDialog.Builder(ctx)
                    .setView(root)
                    .setCancelable(true)
                    .create()

            cancel.onClick { dialog.dismiss() }

            toggleAll.onClick {
                val q = search.text.toString().lowercase().trim()
                val visibleIds = ArrayList<String>()
                for (c in items) {
                    val dn = c.displayName() ?: ""
                    val un = c.username ?: ""
                    if (q.isEmpty() || dn.lowercase().contains(q) || un.lowercase().contains(q)) {
                        visibleIds.add(un)
                    }
                }
                val allSelected = visibleIds.isNotEmpty() && selected.containsAll(visibleIds)
                if (allSelected) {
                    selected.removeAll(visibleIds)
                } else {
                    selected.addAll(visibleIds)
                }
                refresh.run()
            }

            confirm.onClick {
                callback?.onSelected(LinkedHashSet(selected))
                dialog.dismiss()
            }

            InsetsUtil.transparentWindow(dialog)
            dialog.show()
            // 窗口不超过屏幕 90% 宽高（用户要求群管理所有窗口/页面不能大于 90%）；
            // 三键导航设备上透明窗口可能延伸到导航栏，扣减导航栏高度避免底部按钮被遮挡。
            try {
                val dm = ctx.resources.displayMetrics
                var navH = 0
                try {
                    val id = ctx.resources.getIdentifier("navigation_bar_height", "dimen", "android")
                    if (id > 0) navH = ctx.resources.getDimensionPixelSize(id)
                } catch (ignored: Throwable) {
                }
                val win = dialog.window
                if (win != null) {
                    val w = (dm.widthPixels * 0.9f).toInt()
                    val h = (dm.heightPixels * 0.9f).toInt() - navH
                    bottomBar.setPadding(p16, p8, p16, p12 + navH)
                    // post 后通过 setAttributes 强制窗口尺寸（AlertDialog.show 可能重置 setLayout）
                    win.decorView.post {
                        try {
                            val lp = win.attributes
                            lp.width = w
                            lp.height = Math.max(h, 1)
                            lp.gravity = Gravity.CENTER
                            win.setAttributes(lp)
                        } catch (ignored: Throwable) {
                        }
                    }
                }
            } catch (ignored: Throwable) {
            }
            WindowLayer.track(dialog.window)
        }

        private fun buildRow(ctx: Context, c: ContactCard, checked: Boolean, onToggle: Runnable): LinearLayout {
            val p8 = dp(ctx, 8f)
            val p12 = dp(ctx, 12f)

            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(p12, dp(ctx, 6f), p12, dp(ctx, 6f))
            row.background = CandyUi.rowPressBg(ctx)

            val cb = ImageView(ctx)
            cb.setImageDrawable(makeCheckbox(ctx, checked))
            cb.scaleType = ImageView.ScaleType.CENTER
            val cblp = LinearLayout.LayoutParams(dp(ctx, 28f), dp(ctx, 28f))
            cblp.setMargins(0, 0, p8, 0)
            row.addView(cb, cblp)

            val avatarSize = dp(ctx, 40f)
            val avatar = ImageView(ctx)
            val fallback = letterAvatar(ctx, c.sortKey().substring(0, 1), avatarSize)
            AvatarHelper.loadAvatarAsync(avatar, c.username, avatarSize, fallback)
            val alp = LinearLayout.LayoutParams(avatarSize, avatarSize)
            alp.setMargins(0, 0, p12, 0)
            row.addView(avatar, alp)

            val textCol = LinearLayout(ctx)
            textCol.orientation = LinearLayout.VERTICAL
            textCol.gravity = Gravity.CENTER_VERTICAL

            val name = TextView(ctx)
            name.text = c.displayName()
            name.setTextSize(14f)
            name.setTextColor(AppColors.onSurface())
            textCol.addView(name)

            row.addView(textCol, LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            CandyUi.ripple(row, AppColors.SHAPE_MD_DP.toFloat())
            row.setOnClickListener { onToggle.run() }
            return row
        }

        private fun makeCheckbox(ctx: Context, checked: Boolean): Drawable {
            val size = dp(ctx, 28f)
            val bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bm)

            if (checked) {
                val fill = Paint(Paint.ANTI_ALIAS_FLAG)
                fill.color = AppColors.primary()
                canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1f, fill)

                val check = Paint(Paint.ANTI_ALIAS_FLAG)
                check.color = AppColors.onPrimary()
                check.strokeWidth = dp(ctx, 2.2f).toFloat()
                check.style = Paint.Style.STROKE
                check.strokeCap = Paint.Cap.ROUND
                check.strokeJoin = Paint.Join.ROUND
                val cx = size * 0.32f
                val cy = size * 0.52f
                val mx = size * 0.46f
                val my = size * 0.66f
                val ex = size * 0.72f
                val ey = size * 0.35f
                canvas.drawLine(cx, cy, mx, my, check)
                canvas.drawLine(mx, my, ex, ey, check)
            } else {
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
                stroke.style = Paint.Style.STROKE
                stroke.color = AppColors.outline()
                stroke.strokeWidth = dp(ctx, 2f).toFloat()
                canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1f, stroke)
            }

            return BitmapDrawable(ctx.resources, bm)
        }

        private fun letterAvatar(ctx: Context, letter: String, size: Int): Bitmap {
            val paint = Paint()
            paint.color = AppColors.onSecondaryContainer()
            paint.textSize = size * 0.45f
            paint.isAntiAlias = true
            paint.textAlign = Paint.Align.CENTER
            paint.isFakeBoldText = true

            val bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bm)
            val bgPaint = Paint()
            bgPaint.color = AppColors.secondaryContainer()
            canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), size / 2f, size / 2f, bgPaint)
            val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(letter, size / 2f, y, paint)
            return bm
        }


        private fun dp(ctx: Context, px: Float): Int {
            return (px * ctx.resources.displayMetrics.density + 0.5f).toInt()
        }

        private fun dp(ctx: Context, px: Int): Int {
            return dp(ctx, px.toFloat())
        }
    }
}
