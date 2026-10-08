package com.leshao.v3.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
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
import android.widget.Toast

import com.leshao.v3.ContactRepository
import com.leshao.v3.model.ContactCard
import com.leshao.v3.model.ContactCard.Category

import java.util.ArrayList
import java.util.LinkedHashSet

class ContactSelectorView {

    fun interface Callback {
        fun onSelected(selected: List<ContactCard>)
    }

    companion object {
        const val MODE_ALL = 0
        const val MODE_FRIEND = 1
        const val MODE_GROUP = 2

        @JvmStatic
        fun show(activity: Activity?, voiceOnlyMode: Boolean, callback: Callback?) {
            show(activity, voiceOnlyMode, MODE_ALL, callback)
        }

        @JvmStatic
        fun show(activity: Activity?, voiceOnlyMode: Boolean, mode: Int, callback: Callback?) {
            show(activity, voiceOnlyMode, mode, null, callback)
        }

        /**
         * 带「预选」的选择器：{@code preselectUsernames} 中的会话在打开时即处于勾选态。
         */
        @JvmStatic
        fun show(activity: Activity?, voiceOnlyMode: Boolean, mode: Int,
                 preselectUsernames: Collection<String>?, callback: Callback?) {
            if (activity == null || activity.isFinishing()) return

            ContactRepository.loadAsync {
                val all: List<ContactCard>? = when (mode) {
                    MODE_FRIEND -> ContactRepository.getFriends()
                    MODE_GROUP -> ContactRepository.getGroups()
                    else -> ContactRepository.getAll()
                }
                if (all == null || all.isEmpty()) {
                    activity.runOnUiThread {
                        Toast.makeText(activity,
                                "\u901a\u8baf\u5f55\u672a\u52a0\u8f7d\uff0c\u8bf7\u5148\u786e\u4fdd\u5df2\u767b\u5f55\u5fae\u4fe1",
                                Toast.LENGTH_LONG).show()
                    }
                    return@loadAsync
                }

                val items = ArrayList(all)
                items.sortBy { it.sortKey() }
                val selected: MutableSet<ContactCard> = LinkedHashSet()
                if (preselectUsernames != null && preselectUsernames.isNotEmpty()) {
                    for (c in items) {
                        val un = c.username
                        if (un != null && preselectUsernames.contains(un)) {
                            selected.add(c)
                        }
                    }
                }

                activity.runOnUiThread { showDialog(activity, items, selected, callback) }
            }
        }

        private fun showDialog(act: Activity, items: List<ContactCard>,
                               selected: MutableSet<ContactCard>, callback: Callback?) {
            val p20 = dp(act, 20)
            val p16 = dp(act, 16)
            val p12 = dp(act, 12)
            val p8 = dp(act, 8)
            val p4 = dp(act, 4)

            val root = LinearLayout(act)
            root.orientation = LinearLayout.VERTICAL
            root.background = CandyUi.dialogBg(act)

            val title = TextView(act)
            title.text = "\u9009\u62e9\u8054\u7cfb\u4eba"
            title.setTextSize(17f)
            title.typeface = Typeface.DEFAULT_BOLD
            title.setTextColor(AppColors.onSurface())
            title.setPadding(p12, p12, p12, p8)
            title.gravity = Gravity.CENTER
            root.addView(title)

            var allCount = 0
            var friendCount = 0
            var groupCount = 0
            for (c in items) {
                allCount++
                if (c.category == Category.FRIEND) friendCount++
                else if (c.category == Category.GROUP) groupCount++
            }

            val currentTab = intArrayOf(0)
            val tabs = LinearLayout(act)
            tabs.orientation = LinearLayout.HORIZONTAL
            tabs.gravity = Gravity.CENTER
            tabs.setPadding(p12, 0, p12, p8)

            val tabAll = buildTab(act, "\u5168\u90e8(" + allCount + ")", true)
            val tabFriend = buildTab(act, "\u597d\u53cb(" + friendCount + ")", false)
            val tabGroup = buildTab(act, "\u7fa4\u804a(" + groupCount + ")", false)
            tabs.addView(tabAll)
            tabs.addView(space(act, p20))
            tabs.addView(tabFriend)
            tabs.addView(space(act, p20))
            tabs.addView(tabGroup)
            root.addView(tabs)

            val search = EditText(act)
            search.hint = "\u641c\u7d22..."
            search.setHintTextColor(AppColors.onSurfaceVariant())
            search.setTextSize(14f)
            search.setTextColor(AppColors.onSurface())
            search.setPadding(p16, p10(act), p16, p10(act))
            search.background = CandyUi.inputBg(act)
            search.setSingleLine(true)
            val slp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            slp.setMargins(p16, 0, p16, p8)
            root.addView(search, slp)

            val listRoot = LinearLayout(act)
            listRoot.orientation = LinearLayout.VERTICAL
            listRoot.setPadding(p12, 0, p12, 0)

            val sv = ScrollView(act)
            sv.addView(listRoot)
            root.addView(sv, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

            val refreshHolder = arrayOfNulls<Runnable>(1)
            refreshHolder[0] = Runnable {
                listRoot.removeAllViews()
                val f = search.text.toString().lowercase().trim()
                var count = 0
                for (c in items) {
                    if (currentTab[0] == 1 && c.category != Category.FRIEND) continue
                    if (currentTab[0] == 2 && c.category != Category.GROUP) continue
                    if (f.isNotEmpty() && !matchesFilter(c, f)) continue
                    listRoot.addView(buildRow(act, c, c in selected) {
                        if (c in selected) selected.remove(c) else selected.add(c)
                        refreshHolder[0]?.run()
                    })
                    count++
                }
                if (count == 0) {
                    val empty = TextView(act)
                    empty.text = "\u65e0\u5339\u914d\u8054\u7cfb\u4eba"
                    empty.setTextSize(14f)
                    empty.setTextColor(AppColors.onSurfaceVariant())
                    empty.gravity = Gravity.CENTER
                    empty.setPadding(0, dp(act, 40), 0, 0)
                    listRoot.addView(empty)
                }
                title.text = "\u5df2\u9009 " + selected.size + " \u4eba"
            }

            CandyUi.ripple(tabAll, AppColors.SHAPE_FULL_DP.toFloat())
            tabAll.setOnClickListener {
                currentTab[0] = 0
                updateTabs(act, tabAll, tabFriend, tabGroup, 0)
                refreshHolder[0]?.run()
            }
            CandyUi.ripple(tabFriend, AppColors.SHAPE_FULL_DP.toFloat())
            tabFriend.setOnClickListener {
                currentTab[0] = 1
                updateTabs(act, tabAll, tabFriend, tabGroup, 1)
                refreshHolder[0]?.run()
            }
            CandyUi.ripple(tabGroup, AppColors.SHAPE_FULL_DP.toFloat())
            tabGroup.setOnClickListener {
                currentTab[0] = 2
                updateTabs(act, tabAll, tabFriend, tabGroup, 2)
                refreshHolder[0]?.run()
            }

            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                }

                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                }

                override fun afterTextChanged(s: Editable?) {
                    refreshHolder[0]?.run()
                }
            })

            refreshHolder[0]?.run()

            val bottomBar = LinearLayout(act)
            bottomBar.orientation = LinearLayout.VERTICAL
            bottomBar.setPadding(p16, p8, p16, p12)

            val btns = LinearLayout(act)
            btns.orientation = LinearLayout.HORIZONTAL
            btns.gravity = Gravity.CENTER

            val btnLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            btnLp.leftMargin = p4
            btnLp.rightMargin = p4

            val cancel = outlinedBtn(act, "\u53d6\u6d88", AppColors.onSurfaceVariant())
            val toggleAll = outlinedBtn(act, "\u5168\u9009", AppColors.primary())
            val confirm = filledBtn(act, "\u786e\u5b9a")

            btns.addView(cancel, btnLp)
            btns.addView(space(act, p16))
            btns.addView(toggleAll, btnLp)
            btns.addView(space(act, p16))
            btns.addView(confirm, btnLp)
            bottomBar.addView(btns)
            root.addView(bottomBar)

            val dialog = AlertDialog.Builder(act)
                    .setCancelable(true)
                    .create()
            val host = InsetsUtil.windowAutoHeight(dialog, root, 0.9f)
            dialog.setView(host)
            CandyUi.ripple(cancel, AppColors.SHAPE_FULL_DP.toFloat())
            cancel.setOnClickListener {
                dialog.dismiss()
                callback?.onSelected(emptyList())
            }

            CandyUi.ripple(confirm, AppColors.SHAPE_FULL_DP.toFloat())
            confirm.setOnClickListener {
                callback?.onSelected(ArrayList(selected))
                dialog.dismiss()
            }

            CandyUi.ripple(toggleAll, AppColors.SHAPE_FULL_DP.toFloat())
            toggleAll.setOnClickListener {
                val visible = ArrayList<ContactCard>()
                val f = search.text.toString().lowercase().trim()
                for (c in items) {
                    if (currentTab[0] == 1 && c.category != Category.FRIEND) continue
                    if (currentTab[0] == 2 && c.category != Category.GROUP) continue
                    if (f.isNotEmpty() && !matchesFilter(c, f)) continue
                    visible.add(c)
                }
                val allSelected = selected.containsAll(visible)
                if (allSelected) {
                    selected.removeAll(visible)
                } else {
                    selected.addAll(visible)
                }
                refreshHolder[0]?.run()
            }

            dialog.setOnCancelListener {
                callback?.onSelected(emptyList())
            }

            val origRefresh = refreshHolder[0]
            refreshHolder[0] = Runnable {
                origRefresh?.run()
                val visible = ArrayList<ContactCard>()
                val f = search.text.toString().lowercase().trim()
                for (c in items) {
                    if (currentTab[0] == 1 && c.category != Category.FRIEND) continue
                    if (currentTab[0] == 2 && c.category != Category.GROUP) continue
                    if (f.isNotEmpty() && !matchesFilter(c, f)) continue
                    visible.add(c)
                }
                val allSelected = visible.isNotEmpty() && selected.containsAll(visible)
                toggleAll.text = if (allSelected) "\u53d6\u6d88\u5168\u9009" else "\u5168\u9009"
                confirm.text = "\u786e\u5b9a (" + selected.size + ")"
            }

            dialog.show()
            try {
                val w = dialog.window
                if (w != null) {
                    WindowLayer.track(w)
                }
            } catch (ignored: Throwable) {
            }
        }

        private fun updateTabs(act: Activity, t0: TextView, t1: TextView, t2: TextView, idx: Int) {
            applyPill(t0, act, idx == 0)
            applyPill(t1, act, idx == 1)
            applyPill(t2, act, idx == 2)
        }

        private fun applyPill(tv: TextView, act: Activity, selected: Boolean) {
            val radius = dp(act, AppColors.SHAPE_FULL_DP)
            if (selected) {
                val gd = GradientDrawable()
                gd.setShape(GradientDrawable.RECTANGLE)
                gd.setCornerRadius(radius.toFloat())
                gd.setColor(AppColors.primary())
                tv.background = gd
                tv.setTextColor(AppColors.onGradient())
            } else {
                val gd = GradientDrawable()
                gd.setShape(GradientDrawable.RECTANGLE)
                gd.setCornerRadius(radius.toFloat())
                gd.setColor(AppColors.surfaceContainerLow())
                gd.setStroke(dp(act, 1), AppColors.outline())
                tv.background = gd
                tv.setTextColor(AppColors.onSurfaceVariant())
            }
        }

        private fun buildTab(act: Activity, text: String, selected: Boolean): TextView {
            val tv = TextView(act)
            tv.text = text
            tv.setTextSize(13f)
            tv.gravity = Gravity.CENTER
            tv.setPadding(dp(act, 18), dp(act, 8), dp(act, 18), dp(act, 8))
            applyPill(tv, act, selected)
            return tv
        }

        private fun buildRow(act: Activity, c: ContactCard, checked: Boolean, onToggle: Runnable): LinearLayout {
            val p8 = dp(act, 8)
            val p12 = dp(act, 12)

            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(p12, p6(act), p12, p6(act))

            val cb = ImageView(act)
            cb.setImageDrawable(makeCheckbox(act, checked))
            cb.scaleType = ImageView.ScaleType.CENTER
            val cblp = LinearLayout.LayoutParams(dp(act, 28), dp(act, 28))
            cblp.setMargins(0, 0, p8, 0)
            row.addView(cb, cblp)

            val avatarSize = dp(act, 40)
            val avatar = ImageView(act)
            val sortKey = c.sortKey()
            val fallback = AvatarHelper.letterAvatar(
                    if (sortKey.isEmpty()) "?" else sortKey, avatarSize)
            AvatarHelper.loadAvatarAsync(avatar, c.username, avatarSize, fallback)
            val alp = LinearLayout.LayoutParams(avatarSize, avatarSize)
            alp.setMargins(0, 0, p12, 0)
            row.addView(avatar, alp)

            val textCol = LinearLayout(act)
            textCol.orientation = LinearLayout.VERTICAL
            textCol.gravity = Gravity.CENTER_VERTICAL

            val name = TextView(act)
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

        private fun makeCheckbox(act: Activity, checked: Boolean): Drawable {
            val size = dp(act, 22)
            val bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bm)

            if (checked) {
                val lg = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(),
                        intArrayOf(AppColors.gradientStart(), AppColors.gradientMid(), AppColors.gradientEnd()),
                        floatArrayOf(0f, 0.5f, 1f),
                        Shader.TileMode.CLAMP)
                val fill = Paint(Paint.ANTI_ALIAS_FLAG)
                fill.shader = lg
                canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1f, fill)

                val check = Paint(Paint.ANTI_ALIAS_FLAG)
                check.color = AppColors.whiteTextOnAccent()
                check.strokeWidth = dp(act, 2.2f).toFloat()
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
                stroke.strokeWidth = dp(act, 2).toFloat()
                canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1f, stroke)
            }

            return BitmapDrawable(act.resources, bm)
        }

        private fun matchesFilter(c: ContactCard, q: String): Boolean {
            val dn = c.displayName()
            if (dn != null && dn.lowercase().contains(q)) return true
            val un = c.username
            if (un != null && un.lowercase().contains(q)) return true
            val al = c.alias
            if (al != null && al.lowercase().contains(q)) return true
            val py = c.pyInitial
            if (py != null && py.lowercase().contains(q)) return true
            val qp = c.quanPin
            if (qp != null && qp.lowercase().contains(q)) return true
            return false
        }

        private fun filledBtn(act: Activity, text: String): TextView {
            val btn = TextView(act)
            btn.text = text
            btn.setTextSize(14f)
            btn.gravity = Gravity.CENTER
            btn.setPadding(dp(act, 18), dp(act, 8), dp(act, 18), dp(act, 8))
            val gd = GradientDrawable()
            gd.setShape(GradientDrawable.RECTANGLE)
            gd.setCornerRadius(dp(act, AppColors.SHAPE_FULL_DP).toFloat())
            gd.setColor(AppColors.primary())
            btn.background = gd
            btn.setTextColor(AppColors.onGradient())
            return btn
        }

        private fun outlinedBtn(act: Activity, text: String, textColor: Int): TextView {
            val btn = TextView(act)
            btn.text = text
            btn.setTextSize(14f)
            btn.gravity = Gravity.CENTER
            btn.setPadding(dp(act, 18), dp(act, 8), dp(act, 18), dp(act, 8))
            val gd = GradientDrawable()
            gd.setShape(GradientDrawable.RECTANGLE)
            gd.setCornerRadius(dp(act, AppColors.SHAPE_FULL_DP).toFloat())
            gd.setColor(0x00000000)
            gd.setStroke(dp(act, 1), AppColors.outline())
            btn.background = gd
            btn.setTextColor(textColor)
            return btn
        }

        private fun space(act: Activity, w: Int): View {
            val v = View(act)
            v.layoutParams = ViewGroup.LayoutParams(w, 1)
            return v
        }

        private fun dp(act: Activity, px: Float): Int {
            return (px * act.resources.displayMetrics.density + 0.5f).toInt()
        }

        private fun dp(act: Activity, px: Int): Int {
            return dp(act, px.toFloat())
        }

        private fun p2(act: Activity): Int {
            return dp(act, 2)
        }

        private fun p6(act: Activity): Int {
            return dp(act, 6)
        }

        private fun p10(act: Activity): Int {
            return dp(act, 10)
        }
    }
}
