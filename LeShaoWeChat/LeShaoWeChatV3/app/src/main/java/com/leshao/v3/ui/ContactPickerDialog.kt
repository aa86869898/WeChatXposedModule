package com.leshao.v3.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
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
import com.leshao.v3.ui.widgets.ModernButton
import com.leshao.v3.ui.widgets.ModernTopBar
import com.leshao.v3.ui.widgets.SegmentedControl

import java.util.ArrayList
import java.util.LinkedHashSet

class ContactPickerDialog {

    fun interface OnContactsSelected {
        fun onSelected(wxids: Set<String>, display: String)
    }

    fun interface OnCanceled {
        fun onCanceled()
    }

    companion object {
        const val MODE_FRIEND = 0
        const val MODE_GROUP = 1

        @JvmStatic
        fun show(parentAct: Activity?, currentIds: String?, initialMode: Int, callback: OnContactsSelected?) {
            show(parentAct, currentIds, initialMode, callback, null)
        }

        @JvmStatic
        fun show(parentAct: Activity?, currentIds: String?, initialMode: Int,
                 callback: OnContactsSelected?, cancelCallback: OnCanceled?) {
            show(parentAct, currentIds, initialMode, callback, cancelCallback, null)
        }

        /**
         * 一键拉群等场景的扩展入口(向后兼容): 允许自定义顶部标题。
         *
         * @param title 非空时替换默认「选择联系人」标题, null 保持原行为。
         */
        @JvmStatic
        fun show(parentAct: Activity?, currentIds: String?, initialMode: Int,
                 callback: OnContactsSelected?, cancelCallback: OnCanceled?, title: String?) {
            if (parentAct == null || parentAct.isFinishing()) return

            val loadingRef = arrayOfNulls<AlertDialog>(1)
            try {
                val loading = AlertDialog.Builder(parentAct)
                        .setTitle("LeShao")
                        .setMessage("\u901a\u8baf\u5f55\u52a0\u8f7d\u4e2d\uff0c\u8bf7\u7a0d\u5019\u2026")
                        .setCancelable(true)
                        .create()
                loading.show()
                loadingRef[0] = loading
            } catch (ignored: Throwable) {
            }

            ContactRepository.loadAsync {
                // v3.0.270: 新增「全部」标签，初始数据集统一加载好友+群聊，供三个标签切换
                val all: List<ContactCard>? = ContactRepository.getAll()
                if (all == null || all.isEmpty()) {
                    parentAct.runOnUiThread {
                        val ld = loadingRef[0]
                        if (ld != null) {
                            ld.setMessage("\u901a\u8baf\u5f55\u672a\u52a0\u8f7d\u5b8c\u6210\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd8")
                        } else {
                            Toast.makeText(parentAct, "\u901a\u8baf\u5f55\u672a\u52a0\u8f7d",
                                    Toast.LENGTH_LONG).show()
                        }
                    }
                    return@loadAsync
                }

                val contacts = ArrayList(all)
                contacts.sortBy { it.sortKey() }

                val selected: MutableSet<String> = LinkedHashSet()
                if (currentIds != null && currentIds.isNotEmpty()) {
                    for (id in currentIds.split(",")) {
                        val trimmed = id.trim()
                        if (trimmed.isNotEmpty()) selected.add(trimmed)
                    }
                }

                parentAct.runOnUiThread {
                    val ld = loadingRef[0]
                    if (ld != null) {
                        try {
                            ld.dismiss()
                        } catch (ignored: Throwable) {
                        }
                    }
                    showDialog(parentAct, contacts, selected, initialMode, callback, cancelCallback, title)
                }
            }
        }

        private fun showDialog(act: Activity, items: List<ContactCard>,
                               selected: MutableSet<String>, initialMode: Int,
                               callback: OnContactsSelected?, cancelCallback: OnCanceled?) {
            showDialog(act, items, selected, initialMode, callback, cancelCallback, null)
        }

        private fun showDialog(act: Activity, items: List<ContactCard>,
                               selected: MutableSet<String>, initialMode: Int,
                               callback: OnContactsSelected?, cancelCallback: OnCanceled?,
                               title: String?) {
            val p16 = dp(act, 16)
            val p12 = dp(act, 12)
            val p8 = dp(act, 8)
            val p4 = dp(act, 4)

            val root = LinearLayout(act)
            root.orientation = LinearLayout.VERTICAL
            root.background = CandyUi.dialogBg(act, WindowLayer.depth())
            InsetsUtil.clipRounded(root)
            CandyUi.elevate(root)

            val topBar = ModernTopBar(act,
                    if (title != null && title.isNotEmpty()) title else "\u9009\u62e9\u8054\u7cfb\u4eba", false, null)
            root.addView(topBar, LinearLayout.LayoutParams(-1, -2))

            var friendCount = 0
            var groupCount = 0
            for (c in ContactRepository.getFriends()) {
                if (c.category == Category.FRIEND) friendCount++
            }
            for (c in ContactRepository.getGroups()) {
                if (c.category == Category.GROUP) groupCount++
            }
            val allCount = friendCount + groupCount

            // v3.0.270: 标签 = 全部 / 好友 / 群聊（索引 0=全部, 1=好友, 2=群聊）
            val currentTab = intArrayOf(when (initialMode) {
                MODE_FRIEND -> 1
                MODE_GROUP -> 2
                else -> 0
            })
            val refreshHolder = arrayOfNulls<Runnable>(1)
            val tabs = SegmentedControl(act,
                    arrayOf("\u5168\u90e8(" + allCount + ")", "\u597d\u53cb(" + friendCount + ")", "\u7fa4\u804a(" + groupCount + ")"),
                    currentTab[0])
            tabs.setOnSegmentChangedListener(object : SegmentedControl.OnSegmentChangedListener {
                override fun onChanged(index: Int, label: String) {
                    currentTab[0] = index
                    refreshHolder[0]?.run()
                }
            })
            val tabsContainer = LinearLayout(act)
            tabsContainer.orientation = LinearLayout.VERTICAL
            tabsContainer.setPadding(p12, 0, p12, p8)
            tabsContainer.addView(tabs, LinearLayout.LayoutParams(-1, -2))
            root.addView(tabsContainer)

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
            slp.setMargins(p12, 0, p12, p8)
            root.addView(search, slp)

            val listRoot = LinearLayout(act)
            listRoot.orientation = LinearLayout.VERTICAL
            listRoot.setPadding(p12, 0, p12, 0)

            val sv = ScrollView(act)
            sv.addView(listRoot)
            root.addView(sv, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

            val filteredHolder = arrayOfNulls<List<ContactCard>>(1)
            filteredHolder[0] = items

            refreshHolder[0] = Runnable {
                listRoot.removeAllViews()
                val f = search.text.toString().lowercase().trim()

                filteredHolder[0] = when (currentTab[0]) {
                    1 -> ContactRepository.getFriends()
                    2 -> ContactRepository.getGroups()
                    else -> {
                        // 「全部」= 好友 + 群聊
                        val merged = ArrayList<ContactCard>()
                        merged.addAll(ContactRepository.getFriends())
                        merged.addAll(ContactRepository.getGroups())
                        merged
                    }
                }
                if (filteredHolder[0] == null) filteredHolder[0] = emptyList()

                var count = 0
                for (c in filteredHolder[0].orEmpty()) {
                    if (f.isNotEmpty() && !matchesFilter(c, f)) continue
                    val wxid = c.username
                    listRoot.addView(buildRow(act, c, wxid != null && wxid in selected) {
                        if (wxid != null) {
                            if (wxid in selected) selected.remove(wxid) else selected.add(wxid)
                        }
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
                topBar.setTitle("\u5df2\u9009 " + selected.size + " \u4eba")
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

            val cancel = ModernButton(act, "\u53d6\u6d88", ModernButton.STYLE_GHOST)
            val toggleAll = ModernButton(act, "\u5168\u9009", ModernButton.STYLE_TEXT)
            val confirm = ModernButton(act, "\u786e\u5b9a", ModernButton.STYLE_PRIMARY)

            btns.addView(cancel, btnLp)
            btns.addView(space(act, p16))
            btns.addView(toggleAll, btnLp)
            btns.addView(space(act, p16))
            btns.addView(confirm, btnLp)
            bottomBar.addView(btns)
            root.addView(bottomBar)

            val dialog = AlertDialog.Builder(act)
                    .setView(root)
                    .setCancelable(true)
                    .create()

            cancel.onClick {
                dialog.dismiss()
            }

            confirm.onClick {
                if (callback != null) {
                    val sb = StringBuilder()
                    var i = 0
                    for (wid in selected) {
                        if (i++ > 0) sb.append(", ")
                        var found = findCard(items, wid)
                        if (found == null) found = findCard(ContactRepository.getAll(), wid)
                        sb.append(if (found != null) found.displayName() else wid)
                        if (i >= 4 && i < selected.size) {
                            sb.append("...\u7b49" + selected.size + "")
                            break
                        }
                    }
                    callback.onSelected(LinkedHashSet(selected), sb.toString())
                }
                dialog.dismiss()
            }

            toggleAll.onClick {
                val source = filteredHolder[0] ?: return@onClick
                val f = search.text.toString().lowercase().trim()
                val visibleIds = ArrayList<String>()
                for (c in source) {
                    if (f.isNotEmpty() && !matchesFilter(c, f)) continue
                    val un = c.username ?: continue
                    visibleIds.add(un)
                }
                val allSelected = visibleIds.isNotEmpty() && selected.containsAll(visibleIds)
                if (allSelected) {
                    selected.removeAll(visibleIds)
                } else {
                    selected.addAll(visibleIds)
                }
                refreshHolder[0]?.run()
            }

            dialog.setOnCancelListener {
                if (cancelCallback != null) {
                    cancelCallback.onCanceled()
                }
            }

            val origRefresh = refreshHolder[0]
            refreshHolder[0] = Runnable {
                origRefresh?.run()
                val source = filteredHolder[0] ?: return@Runnable
                val f = search.text.toString().lowercase().trim()
                val visibleIds = ArrayList<String>()
                for (c in source) {
                    if (f.isNotEmpty() && !matchesFilter(c, f)) continue
                    val un = c.username ?: continue
                    visibleIds.add(un)
                }
                val allSelected = visibleIds.isNotEmpty() && selected.containsAll(visibleIds)
                toggleAll.setText(if (allSelected) "\u53d6\u6d88\u5168\u9009" else "\u5168\u9009")
                confirm.setText("\u786e\u5b9a (" + selected.size + ")")
            }

            InsetsUtil.transparentWindow(dialog)
            dialog.show()
            WindowLayer.track(dialog.window)
        }

        private fun findCard(items: List<ContactCard>, wxid: String): ContactCard? {
            for (c in items) {
                if (wxid == c.username) return c
            }
            return null
        }

        private fun buildRow(act: Activity, c: ContactCard, checked: Boolean, onToggle: Runnable): LinearLayout {
            val p8 = dp(act, 8)
            val p12 = dp(act, 12)

            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(p12, p6(act), p12, p6(act))
            row.background = CandyUi.rowPressBg(act)

            val cb = ImageView(act)
            cb.setImageDrawable(makeCheckbox(act, checked))
            cb.scaleType = ImageView.ScaleType.CENTER
            val cblp = LinearLayout.LayoutParams(dp(act, 28), dp(act, 28))
            cblp.setMargins(0, 0, p8, 0)
            row.addView(cb, cblp)

            val avatarSize = dp(act, 40)
            val avatar = ImageView(act)
            val fallback = letterAvatar(act, c.sortKey().substring(0, 1), avatarSize)
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
                val fill = Paint(Paint.ANTI_ALIAS_FLAG)
                fill.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(),
                        AppColors.gradientColors(), null, Shader.TileMode.CLAMP)
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
            return false
        }

        private fun letterAvatar(act: Activity, letter: String, size: Int): Bitmap {
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

        private fun space(act: Activity, w: Int): View {
            val v = View(act)
            v.layoutParams = ViewGroup.LayoutParams(w, 1)
            return v
        }

        private fun roundBg(act: Activity, color: Int, radius: Int): GradientDrawable {
            val gd = GradientDrawable()
            gd.setColor(color)
            gd.setCornerRadius(radius.toFloat())
            return gd
        }

        private fun dp(act: Activity, px: Float): Int {
            return (px * act.resources.displayMetrics.density + 0.5f).toInt()
        }

        private fun dp(act: Activity, px: Int): Int {
            return dp(act, px.toFloat())
        }

        private fun p6(act: Activity): Int {
            return dp(act, 6)
        }

        private fun p10(act: Activity): Int {
            return dp(act, 10)
        }
    }
}
