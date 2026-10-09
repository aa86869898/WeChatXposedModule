package com.leshao.v3.ui

import android.app.Activity
import android.content.ClipboardManager
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
import com.leshao.v3.ui.widgets.SegmentedControl

import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * 数据库直读 - 新版联系人选择器。
 * 数据源为 ContactRepository 内存缓存（日志证实 53 好友 / 105 群），
 * 不依赖 rawQuery 结果，保证选择器窗口可独立运行。
 * 页签仅保留 全部 / 好友 / 群聊 / 标签，服务号订阅号一律过滤不展示。
 * 标签页从联系人 contactLabelIds 聚合，性别按资料包结论展示。
 */
class WeChatDbPageView private constructor() {

    companion object {

        private const val TAB_ALL = 0
        private const val TAB_FRIEND = 1
        private const val TAB_GROUP = 2
        private const val TAB_LABEL = 3

        /** 页面状态：当前页签、已选集合、标签浏览位置、标签名映射。 */
        private class State(
            val search: EditText,
            val listRoot: LinearLayout,
            val topInfo: TextView
        ) {
            var tab = TAB_ALL
            val selected = LinkedHashSet<String>()
            var currentLabelId: String? = null
            var recentTimes: Map<String, Long> = HashMap()
            val labelNames = LinkedHashMap<String, String>()
        }

        @JvmStatic
        fun create(ctx: Context, act: Activity): View {
            val d = ctx.resources.displayMetrics.density
            val p12 = (12 * d).toInt()
            val p8 = (8 * d).toInt()
            val p4 = (4 * d).toInt()

            // v1143: 移除最外层 ScrollView，改为 no_wrap 固定结构：
            // 搜索/页签/底部按钮固定，中间列表独立滚动，避免双层 ScrollView 嵌套导致列表无法滑动。
            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            root.setPadding(p12, p8, p12, p8)
            root.tag = "no_wrap"

            // 搜索框（顶部）
            val search = EditText(ctx)
            search.hint = "搜索名称 / 微信号 / 备注"
            search.setHintTextColor(AppColors.onSurfaceVariant())
            search.setTextSize(14f)
            search.setTextColor(AppColors.onSurface())
            search.setSingleLine(true)
            search.setPadding(p12, (10 * d).toInt(), p12, (10 * d).toInt())
            search.background = CandyUi.inputBg(ctx)
            root.addView(search)

            // 页签
            val friendCount = ContactRepository.getFriends().size
            val groupCount = ContactRepository.getGroups().size
            val tabs = SegmentedControl(ctx,
                arrayOf("全部",
                    "联系人(" + friendCount + ")",
                    "群聊(" + groupCount + ")",
                    "标签"), TAB_ALL)
            val tabBox = LinearLayout(ctx)
            tabBox.orientation = LinearLayout.VERTICAL
            tabBox.setPadding(0, p8, 0, p8)
            tabBox.addView(tabs)
            root.addView(tabBox)

            // 列表区域（weight=1 占满剩余高度，内部滚动）
            val listRoot = LinearLayout(ctx)
            listRoot.orientation = LinearLayout.VERTICAL
            listRoot.setPadding(0, p4, 0, p4)
            val listScroll = ScrollView(ctx)
            listScroll.isFillViewport = true
            listScroll.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f)
            listScroll.addView(listRoot)
            root.addView(listScroll)

            val topInfo = PageKit.bodyText(ctx, "共 0 项，已选 0 项")
            topInfo.setPadding(0, p4, 0, p4)
            root.addView(topInfo)

            // 底部操作栏：取消 / 全选(取消勾选) / 确定
            val bottom = LinearLayout(ctx)
            bottom.orientation = LinearLayout.HORIZONTAL
            bottom.gravity = Gravity.CENTER
            bottom.setPadding(0, p8, 0, 0)

            val cancelBtn = ModernButton(ctx, "取消", ModernButton.STYLE_GHOST)
            val toggleBtn = ModernButton(ctx, "全选", ModernButton.STYLE_TEXT)
            val okBtn = ModernButton(ctx, "确定", ModernButton.STYLE_PRIMARY)

            val btnLp = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            btnLp.setMargins(p4, 0, p4, 0)
            bottom.addView(cancelBtn, btnLp)
            bottom.addView(toggleBtn, btnLp)
            bottom.addView(okBtn, btnLp)
            root.addView(bottom)

            // 初始化状态与刷新
            val state = State(search, listRoot, topInfo)
            loadLabelNames(ctx, state.labelNames)

            val refresh = Runnable {
                val vis = visibleCards(state)
                val allSelected = vis.isNotEmpty() && state.selected.containsAll(idsOf(vis))
                toggleBtn.setText(if (allSelected) "取消勾选" else "全选")
                refreshList(ctx, state)
            }

            tabs.setOnSegmentChangedListener(object : SegmentedControl.OnSegmentChangedListener {
                override fun onChanged(index: Int, label: String) {
                    state.tab = index
                    state.currentLabelId = null
                    refresh.run()
                }
            })

            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable) { refresh.run() }
            })

            cancelBtn.onClick {
                try {
                    SubPageActivity.closePage(act)
                } catch (t1: Throwable) {
                    try { act.finish() } catch (t2: Throwable) {}
                }
            }

            toggleBtn.onClick {
                val visible = visibleCards(state)
                if (visible.isEmpty()) return@onClick
                val ids = idsOf(visible)
                val allSelected = visible.isNotEmpty() && state.selected.containsAll(ids)
                if (allSelected) state.selected.removeAll(ids)
                else state.selected.addAll(ids)
                toggleBtn.setText(if (allSelected) "全选" else "取消勾选")
                refresh.run()
            }

            okBtn.onClick {
                if (state.selected.isEmpty()) {
                    Toast.makeText(ctx, "尚未选择任何项", Toast.LENGTH_SHORT).show()
                    return@onClick
                }
                val sb = StringBuilder()
                val all = allContacts()
                var i = 0
                for (wid in state.selected) {
                    val c = findCard(all, wid)
                    if (i++ > 0) sb.append('\n')
                    sb.append(c?.displayName() ?: wid)
                    if (c != null) {
                        sb.append("  ").append(c.username)
                        val alias = c.alias
                        if (alias != null && alias.isNotEmpty()) sb.append("  ").append(alias)
                    }
                }
                try {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.text = sb.toString()
                    Toast.makeText(ctx, "已复制 " + state.selected.size + " 项", Toast.LENGTH_SHORT).show()
                    try {
                        SubPageActivity.closePage(act)
                    } catch (ignored: Throwable) {}
                } catch (t: Throwable) {
                    Toast.makeText(ctx, "复制失败", Toast.LENGTH_SHORT).show()
                }
            }

            // 数据加载完成后刷新；已缓存则立即刷新
            ContactRepository.loadAsync {
                act.runOnUiThread {
                    state.recentTimes = loadRecentTimes()
                    refresh.run()
                }
            }

            // 数据库实时刷新：每 3 秒重新读取会话表时间戳，有变化才重绘列表
            val poller = Handler(Looper.getMainLooper())
            val pollTask = object : Runnable {
                override fun run() {
                    val next = loadRecentTimes()
                    if (next != state.recentTimes) {
                        state.recentTimes = next
                        refresh.run()
                    }
                    if (root.isAttachedToWindow) poller.postDelayed(this, 3000)
                }
            }
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    poller.postDelayed(pollTask, 3000)
                }
                override fun onViewDetachedFromWindow(v: View) {
                    poller.removeCallbacks(pollTask)
                }
            })

            return root
        }

        private fun refreshList(ctx: Context, state: State) {
            state.listRoot.removeAllViews()
            val query = state.search.text.toString().lowercase().trim()

            if (state.tab == TAB_LABEL && state.currentLabelId == null) {
                renderLabelList(ctx, state, query)
                return
            }

            if (state.tab == TAB_LABEL) {
                addBackRow(ctx, state.listRoot, "← 返回标签列表", View.OnClickListener {
                    state.currentLabelId = null
                    refreshList(ctx, state)
                })
            }

            val cards = visibleCards(state)
            if (cards.isEmpty()) {
                addEmpty(ctx, state.listRoot, "无匹配项")
                state.topInfo.text = "共 0 项"
                return
            }

            for (c in cards) {
                val wid = c.username ?: continue
                val checked = state.selected.contains(wid)
                state.listRoot.addView(buildRow(ctx, c, checked, Runnable {
                    if (state.selected.contains(wid)) state.selected.remove(wid)
                    else state.selected.add(wid)
                    refreshList(ctx, state)
                }))
            }
            state.topInfo.text = "当前 " + cards.size + " 项，已选 " + state.selected.size + " 项"
        }

        private fun renderLabelList(ctx: Context, state: State, query: String) {
            val counts = labelCounts()
            if (counts.isEmpty()) {
                addEmpty(ctx, state.listRoot,
                    "联系人中没有标签数据（contactLabelIds 为空）。")
                state.topInfo.text = "标签聚合"
                return
            }
            val ids = ArrayList<String>(counts.keys)
            ids.sortWith { a, b ->
                labelName(a, state.labelNames)
                    .compareTo(labelName(b, state.labelNames))
            }
            var shown = 0
            for (id in ids) {
                val name = labelName(id, state.labelNames)
                if (!query.isEmpty() && !name.lowercase().contains(query)
                    && !id.contains(query)) continue
                val cnt = counts[id]
                val fid = id
                state.listRoot.addView(buildLabelRow(ctx, name + " (" + cnt + " 人)", View.OnClickListener {
                    state.currentLabelId = fid
                    refreshList(ctx, state)
                }))
                shown++
            }
            if (shown == 0) {
                addEmpty(ctx, state.listRoot, "无匹配标签")
            }
            state.topInfo.text = "共 " + counts.size + " 个标签，点击查看标签成员"
        }

        private fun addEmpty(ctx: Context, listRoot: LinearLayout, text: String) {
            val empty = TextView(ctx)
            empty.text = text
            empty.setTextSize(14f)
            empty.setTextColor(AppColors.onSurfaceVariant())
            empty.gravity = Gravity.CENTER
            empty.setPadding(0, (40 * ctx.resources.displayMetrics.density).toInt(), 0, 0)
            listRoot.addView(empty)
        }

        private fun addBackRow(ctx: Context, listRoot: LinearLayout, text: String,
                               l: View.OnClickListener) {
            val back = TextView(ctx)
            back.text = text
            back.setTextSize(14f)
            back.setTextColor(AppColors.primary())
            back.gravity = Gravity.CENTER_VERTICAL
            back.setPadding((12 * ctx.resources.displayMetrics.density).toInt(),
                (10 * ctx.resources.displayMetrics.density).toInt(), 0, 0)
            back.background = CandyUi.rowPressBg(ctx)
            back.setOnClickListener(l)
            listRoot.addView(back)
        }

        private fun buildLabelRow(ctx: Context, text: String, l: View.OnClickListener): LinearLayout {
            val p12 = (12 * ctx.resources.displayMetrics.density).toInt()
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(p12, (10 * ctx.resources.displayMetrics.density).toInt(), p12,
                (10 * ctx.resources.displayMetrics.density).toInt())
            row.background = CandyUi.rowPressBg(ctx)
            val tv = TextView(ctx)
            tv.text = text
            tv.setTextSize(14f)
            tv.setTextColor(AppColors.onSurface())
            row.addView(tv)
            CandyUi.ripple(row, AppColors.SHAPE_MD_DP.toFloat())
            row.setOnClickListener(l)
            return row
        }

        private fun buildRow(ctx: Context, c: ContactCard, checked: Boolean,
                             onToggle: Runnable): LinearLayout {
            val p8 = (8 * ctx.resources.displayMetrics.density).toInt()
            val p12 = (12 * ctx.resources.displayMetrics.density).toInt()

            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(p12, (6 * ctx.resources.displayMetrics.density).toInt(), p12,
                (6 * ctx.resources.displayMetrics.density).toInt())
            row.background = CandyUi.rowPressBg(ctx)

            val cb = ImageView(ctx)
            cb.setImageDrawable(makeCheckbox(ctx, checked))
            cb.scaleType = ImageView.ScaleType.CENTER
            val cblp = LinearLayout.LayoutParams(
                (28 * ctx.resources.displayMetrics.density).toInt(),
                (28 * ctx.resources.displayMetrics.density).toInt())
            cblp.setMargins(0, 0, p8, 0)
            row.addView(cb, cblp)

            val avatarSize = (40 * ctx.resources.displayMetrics.density).toInt()
            val avatar = ImageView(ctx)
            val key = c.sortKey()
            val letter = if (key != null && key.isNotEmpty()) key.substring(0, 1) else "#"
            val letterBmp = letterAvatar(ctx, letter, avatarSize)
            avatar.setImageBitmap(letterBmp)
            AvatarHelper.loadAvatarAsync(avatar, c.username, avatarSize, letterBmp)
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

            val sub = subTitle(c)
            if (sub != null && sub.isNotEmpty()) {
                val subTv = TextView(ctx)
                subTv.text = sub
                subTv.setTextSize(11f)
                subTv.setTextColor(AppColors.onSurfaceVariant())
                textCol.addView(subTv)
            }

            row.addView(textCol, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            CandyUi.ripple(row, AppColors.SHAPE_MD_DP.toFloat())
            row.setOnClickListener { onToggle.run() }
            return row
        }

        private fun subTitle(c: ContactCard): String? {
            if (c.category == Category.GROUP) {
                return null
            }
            val sb = StringBuilder()
            val alias = c.alias
            if (alias != null && alias.isNotEmpty()) sb.append("微信号 ").append(alias)
            val remark = c.conRemark
            if (remark != null && remark.isNotEmpty()) {
                if (sb.length > 0) sb.append("  ")
                sb.append("备注 ").append(remark)
            }
            val labelIds = c.contactLabelIds
            if (labelIds != null && labelIds.isNotEmpty()) {
                if (sb.length > 0) sb.append("  ")
                sb.append("标签 ").append(labelIds)
            }
            return sb.toString()
        }

        /** 全部数据 = 好友 + 群聊，服务号订阅号不展示。 */
        private fun allContacts(): List<ContactCard> {
            val all = ArrayList<ContactCard>()
            all.addAll(ContactRepository.getFriends())
            all.addAll(ContactRepository.getGroups())
            return all
        }

        private fun visibleCards(state: State): List<ContactCard> {
            val query = state.search.text.toString().lowercase().trim()
            val src: List<ContactCard> = when (state.tab) {
                TAB_ALL -> allContacts()
                TAB_GROUP -> ContactRepository.getGroups()
                TAB_LABEL -> if (state.currentLabelId != null) cardsWithLabel(ContactRepository.getFriends(), state.currentLabelId!!) else ContactRepository.getFriends()
                else -> ContactRepository.getFriends()
            }
            if (src.isEmpty()) return Collections.emptyList()
            val out = ArrayList<ContactCard>()
            for (c in src) {
                if (query.isEmpty() || matchesFilter(c, query)) out.add(c)
            }
            // "全部"页签：按最近来消息时间降序（无会话记录的排最后，再按名称排序）
            if (state.tab == TAB_ALL) {
                val times = state.recentTimes
                out.sortWith { a, b ->
                    val ua = a.username ?: ""
                    val ub = b.username ?: ""
                    val ta = if (times.containsKey(ua)) times[ua] ?: -1L else -1L
                    val tb = if (times.containsKey(ub)) times[ub] ?: -1L else -1L
                    if (ta != tb) tb.compareTo(ta) else (a.displayName() ?: "").compareTo(b.displayName() ?: "", ignoreCase = true)
                }
            }
            return out
        }

        private fun cardsWithLabel(src: List<ContactCard>, labelId: String): List<ContactCard> {
            val out = ArrayList<ContactCard>()
            for (c in src) {
                val labelIds = c.contactLabelIds ?: continue
                for (p in labelIds.split(",")) {
                    if (p.trim() == labelId) { out.add(c); break }
                }
            }
            return out
        }

        private fun labelCounts(): Map<String, Int> {
            val map = LinkedHashMap<String, Int>()
            val src = ArrayList<ContactCard>()
            src.addAll(ContactRepository.getFriends())
            for (c in src) {
                val labelIds = c.contactLabelIds ?: continue
                for (p in labelIds.split(",")) {
                    val id = p.trim()
                    if (id.isEmpty()) continue
                    val n = map[id]
                    map[id] = (n ?: 0) + 1
                }
            }
            return map
        }

        private fun loadLabelNames(ctx: Context, out: MutableMap<String, String>) {
            try {
                if (!ContactRepository.isDbReady()) return
                val rows = ContactRepository.rawQuery(
                    "SELECT labelID, labelName FROM ContactLabel WHERE isTemporary = 0", null, 500)
                if (rows == null) return
                for (r in rows) {
                    val id = valueOf(r, "labelID")
                    val name = valueOf(r, "labelName")
                    if (id != null && name != null && id.isNotEmpty()) out[id.trim()] = name
                }
            } catch (ignored: Throwable) {}
        }

        /** 读取会话表（conversation）各对象的最新来消息时间戳，用于"全部"页签排序。
         *  先探测列名（不同微信版本字段可能不同），再按时间倒序取前 500 个会话。 */
        private fun loadRecentTimes(): Map<String, Long> {
            val map = HashMap<String, Long>()
            try {
                if (!ContactRepository.isDbReady()) return map
                val cols = ContactRepository.rawQuery(
                    "PRAGMA table_info(conversation)", null, 200)
                if (cols == null || cols.isEmpty()) return map
                var userCol: String? = null
                var timeCol: String? = null
                for (r in cols) {
                    val name = valueOf(r, "name")
                    if (name == null) continue
                    if (userCol == null && ("username".equals(name, ignoreCase = true)
                            || "strTalker".equals(name, ignoreCase = true)
                            || "talker".equals(name, ignoreCase = true)
                            || "userName".equals(name, ignoreCase = true))) {
                        userCol = name
                    } else if (timeCol == null && ("createTime".equals(name, ignoreCase = true)
                            || "lastMsgTime".equals(name, ignoreCase = true)
                            || "msgCreateTime".equals(name, ignoreCase = true)
                            || "chatTime".equals(name, ignoreCase = true))) {
                        timeCol = name
                    }
                }
                if (userCol == null || timeCol == null) return map
                val rows = ContactRepository.rawQuery(
                    "SELECT " + userCol + ", " + timeCol + " FROM conversation ORDER BY " + timeCol + " DESC",
                    null, 500)
                if (rows == null) return map
                for (r in rows) {
                    val user = valueOf(r, userCol)
                    if (user == null || user.isEmpty()) continue
                    val tv = valueOf(r, timeCol)
                    if (tv == null || tv.isEmpty()) continue
                    try {
                        map[user] = tv.toLong()
                    } catch (ignored: Throwable) {}
                }
            } catch (ignored: Throwable) {}
            return map
        }

        private fun valueOf(r: ContactRepository.DbRow, col: String): String? {
            for (i in r.cols.indices) {
                if (col.equals(r.cols[i], ignoreCase = true)) return r.vals[i]
            }
            return null
        }

        private fun labelName(id: String, names: Map<String, String>): String {
            val n = names[id]
            return if (n != null && n.isNotEmpty()) n else "标签#" + id
        }

        private fun matchesFilter(c: ContactCard, q: String): Boolean {
            if (c.displayName()?.lowercase()?.contains(q) == true) return true
            if (c.username?.lowercase()?.contains(q) == true) return true
            if (c.alias?.lowercase()?.contains(q) == true) return true
            if (c.conRemark?.lowercase()?.contains(q) == true) return true
            return false
        }

        private fun idsOf(cards: List<ContactCard>): Set<String> {
            val ids = LinkedHashSet<String>()
            for (c in cards) ids.add(c.username!!)
            return ids
        }

        private fun findCard(items: List<ContactCard>, wxid: String): ContactCard? {
            for (c in items) {
                if (wxid == c.username) return c
            }
            return null
        }

        private fun makeCheckbox(ctx: Context, checked: Boolean): Drawable {
            val size = (22 * ctx.resources.displayMetrics.density).toInt()
            val bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bm)
            if (checked) {
                val fill = Paint(Paint.ANTI_ALIAS_FLAG)
                fill.color = AppColors.primary()
                canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1, fill)
                val check = Paint(Paint.ANTI_ALIAS_FLAG)
                check.color = AppColors.whiteTextOnAccent()
                check.strokeWidth = (2.2 * ctx.resources.displayMetrics.density).toFloat()
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
                stroke.strokeWidth = (2 * ctx.resources.displayMetrics.density).toFloat()
                canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1, stroke)
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
    }
}