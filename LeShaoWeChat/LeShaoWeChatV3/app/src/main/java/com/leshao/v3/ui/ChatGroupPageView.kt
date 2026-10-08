package com.leshao.v3.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.leshao.v3.hook.ChatGroupHook
import com.leshao.v3.hook.model.LabelInfo
import com.leshao.v3.ui.widgets.M3Page
import java.util.function.BooleanSupplier

/**
 * 聊天分组页：仅保留「分组列表」与聊天列表顶部分组标签栏配套的分组管理。
 */
class ChatGroupPageView {

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

            val content = LinearLayout(ctx)
            content.orientation = LinearLayout.VERTICAL
            content.setPadding(0, (8 * d).toInt(), 0, 0)
            root.addView(content)

            // v1145: 页面顶部统一分区标题
            content.addView(M3Page.section(ctx, "聊天分组",
                    "标签/分组管理，聊天列表顶部同步显示分组栏"))

            buildLabelList(ctx, parentAct, d, content)
            return root
        }

        private fun refreshLabelList(ctx: Context, parentAct: Activity, d: Float, content: LinearLayout) {
            content.removeAllViews()
            buildLabelList(ctx, parentAct, d, content)
        }

        private fun buildLabelList(ctx: Context, parentAct: Activity, d: Float, content: LinearLayout) {
            val card = makeCard(ctx, d)
            val header = TextView(ctx)
            header.text = "分组管理"
            header.setTextSize(16f)
            header.setTextColor(AppColors.text1())
            header.setTypeface(null, Typeface.BOLD)
            header.setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
            card.addView(header)

            val actionRow = LinearLayout(ctx)
            actionRow.orientation = LinearLayout.HORIZONTAL
            actionRow.gravity = Gravity.CENTER_VERTICAL
            actionRow.setPadding((12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())

            val searchEt = EditText(ctx)
            searchEt.setHint("搜索分组...")
            searchEt.setTextSize(12f)
            searchEt.isSingleLine = true
            searchEt.setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
            searchEt.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            searchEt.background = CandyUi.inputBg(ctx)
            val sp = (10 * d).toInt()
            searchEt.setPadding(sp, sp, sp, sp)
            searchEt.setHintTextColor(AppColors.text3())
            actionRow.addView(searchEt)

            val createBtn = TextView(ctx)
            createBtn.text = "+新建"
            createBtn.setTextSize(12f)
            createBtn.setTextColor(AppColors.accent())
            createBtn.setTypeface(null, Typeface.BOLD)
            createBtn.setPadding((10 * d).toInt(), 0, 0, 0)
            CandyUi.ripple(createBtn, AppColors.SHAPE_FULL_DP.toFloat())
            createBtn.setOnClickListener { showCreateLabelDialog(ctx, parentAct, d) { refreshLabelList(ctx, parentAct, d, content) } }
            actionRow.addView(createBtn)

            val refreshBtn = TextView(ctx)
            refreshBtn.text = "刷新"
            refreshBtn.setTextSize(11f)
            refreshBtn.setTextColor(AppColors.text2())
            refreshBtn.setPadding((8 * d).toInt(), 0, 0, 0)
            CandyUi.ripple(refreshBtn, AppColors.SHAPE_FULL_DP.toFloat())
            refreshBtn.setOnClickListener {
                ChatGroupHook.refreshCache()
                Toast.makeText(parentAct, "已刷新", Toast.LENGTH_SHORT).show()
            }
            actionRow.addView(refreshBtn)

            card.addView(actionRow)

            // Search results
            val listRoot = LinearLayout(ctx)
            listRoot.orientation = LinearLayout.VERTICAL
            card.addView(listRoot)
            content.addView(card)

            searchEt.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, st: Int, cnt: Int, aft: Int) {}
                override fun onTextChanged(s: CharSequence, st: Int, bef: Int, cnt: Int) {}
                override fun afterTextChanged(s: Editable) {
                    listRoot.removeAllViews()
                    val q = s.toString().trim().toLowerCase()
                    val labels: List<LabelInfo> = if (q.isEmpty()) ChatGroupHook.getAllLabels() else ChatGroupHook.searchLabels(q)
                    if (labels.isEmpty()) {
                        val empty = TextView(ctx)
                        empty.text = "暂无分组"
                        empty.setTextSize(12f)
                        empty.setTextColor(AppColors.text2())
                        empty.setPadding((12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt())
                        empty.gravity = Gravity.CENTER
                        listRoot.addView(empty)
                    } else {
                        for (i in labels.indices) {
                            val ref = Runnable { refreshLabelList(ctx, parentAct, d, content) }
                            listRoot.addView(buildLabelRow(ctx, parentAct, d, labels[i], ref))
                        }
                    }
                }
            })
            searchEt.setText("")
        }

        private fun buildLabelRow(ctx: Context, parentAct: Activity, d: Float, label: LabelInfo, refresh: Runnable): View {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            row.background = CandyUi.rowPressBg(ctx)

            val textCol = LinearLayout(ctx)
            textCol.orientation = LinearLayout.VERTICAL
            textCol.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)

            val tvName = TextView(ctx)
            tvName.text = label.labelName
            tvName.setTextSize(14f)
            tvName.setTextColor(AppColors.text1())
            tvName.setTypeface(null, Typeface.BOLD)
            textCol.addView(tvName)

            val tvCount = TextView(ctx)
            val builtIn = label.labelId == ChatGroupHook.LABEL_ID_GROUP || label.labelId == ChatGroupHook.LABEL_ID_FRIEND || label.labelId == ChatGroupHook.LABEL_ID_SERVICE
            tvCount.text = if (builtIn) "内置分组" else (label.contacts.size.toString() + " 位联系人" + (if (label.isTemporary) " | 临时" else ""))
            tvCount.setTextSize(12f)
            tvCount.setTextColor(AppColors.text2())
            tvCount.setPadding(0, (2 * d).toInt(), 0, 0)
            textCol.addView(tvCount)
            row.addView(textCol)

            val editBtn = TextView(ctx)
            editBtn.text = "编辑"
            editBtn.setTextSize(11f)
            editBtn.setTextColor(AppColors.accent())
            editBtn.setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
            CandyUi.ripple(editBtn, AppColors.SHAPE_FULL_DP.toFloat())
            editBtn.setOnClickListener { showRenameLabelDialog(ctx, parentAct, d, label.labelId.toString(), label.labelName, refresh) }
            row.addView(editBtn)

            val delBtn = TextView(ctx)
            delBtn.text = "删除"
            delBtn.setTextSize(11f)
            delBtn.setTextColor(AppColors.error())
            delBtn.setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
            CandyUi.ripple(delBtn, AppColors.SHAPE_FULL_DP.toFloat())
            delBtn.setOnClickListener { showDeleteLabelDialog(ctx, parentAct, d, label.labelId.toString(), label.labelName, refresh) }
            if (label.labelId != ChatGroupHook.LABEL_ID_GROUP && label.labelId != ChatGroupHook.LABEL_ID_FRIEND && label.labelId != ChatGroupHook.LABEL_ID_SERVICE) {
                row.addView(delBtn)
            }

            row.setOnClickListener { showLabelMembers(ctx, parentAct, d, label) }
            return row
        }

        // ==================== Dialogs ====================
        private fun showCreateLabelDialog(ctx: Context, parentAct: Activity, d: Float, onDone: Runnable) {
            val et = makeEditText(ctx, d, "输入分组名称")
            showInputDialog(ctx, parentAct, d, "新建分组", et, BooleanSupplier {
                val name = et.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(parentAct, "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@BooleanSupplier false
                }
                val l = ChatGroupHook.createLabel(name)
                if (l != null) {
                    Toast.makeText(parentAct, "创建成功", Toast.LENGTH_SHORT).show()
                    return@BooleanSupplier true
                } else {
                    Toast.makeText(parentAct, "创建失败", Toast.LENGTH_SHORT).show()
                    return@BooleanSupplier false
                }
            }, onDone)
        }

        private fun showRenameLabelDialog(ctx: Context, parentAct: Activity, d: Float, labelId: String, oldName: String, onDone: Runnable) {
            val et = makeEditText(ctx, d, "")
            et.setText(oldName)
            showInputDialog(ctx, parentAct, d, "重命名分组", et, BooleanSupplier {
                val name = et.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(parentAct, "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@BooleanSupplier false
                }
                ChatGroupHook.renameLabel(labelId, name)
            }, onDone)
        }

        private fun showDeleteLabelDialog(ctx: Context, parentAct: Activity, d: Float, labelId: String, labelName: String, onDone: Runnable) {
            showConfirmDialog(ctx, parentAct, d, "确认删除", "确定要删除分组 \"" + labelName + "\" 吗？", "删除", AppColors.error()) {
                if (ChatGroupHook.deleteLabel(labelId)) {
                    Toast.makeText(parentAct, "已删除", Toast.LENGTH_SHORT).show()
                    if (onDone != null) onDone.run()
                } else Toast.makeText(parentAct, "删除失败", Toast.LENGTH_SHORT).show()
            }
        }

        private fun showLabelMembers(ctx: Context, parentAct: Activity, d: Float, label: LabelInfo) {
            buildMemberDialog(ctx, parentAct, d, label)
        }

        private fun buildMemberDialog(ctx: Context, parentAct: Activity, d: Float, label: LabelInfo) {
            val dlgTheme = if (AppColors.isDarkMode()) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
            val dialog = AlertDialog.Builder(ctx, dlgTheme).create()
            val dlgRoot = LinearLayout(ctx)
            dlgRoot.orientation = LinearLayout.VERTICAL
            dlgRoot.setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            dlgRoot.background = CandyUi.dialogBg(ctx)
            InsetsUtil.clipRounded(dlgRoot)

            val title = TextView(ctx)
            title.text = "分组: " + label.labelName
            title.setTextSize(16f)
            title.setTextColor(AppColors.text1())
            title.setTypeface(null, Typeface.BOLD)
            title.setPadding(0, 0, 0, (4 * d).toInt())
            dlgRoot.addView(title)

            val countText = TextView(ctx)
            countText.text = "共 " + label.contacts.size + " 位联系人"
            countText.setTextSize(12f)
            countText.setTextColor(AppColors.text2())
            countText.setPadding(0, 0, 0, (10 * d).toInt())
            dlgRoot.addView(countText)

            if (label.contacts.isEmpty()) {
                val empty = TextView(ctx)
                empty.text = "暂无联系人"
                empty.setTextSize(12f)
                empty.setTextColor(AppColors.text2())
                empty.setPadding(0, 0, 0, (8 * d).toInt())
                dlgRoot.addView(empty)
            } else {
                val sv = android.widget.ScrollView(ctx)
                val ml = LinearLayout(ctx)
                ml.orientation = LinearLayout.VERTICAL
                for (m in label.contacts) {
                    val mt = TextView(ctx)
                    mt.text = m
                    mt.setTextSize(11f)
                    mt.setTextColor(AppColors.text1())
                    mt.setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
                    ml.addView(mt)
                }
                sv.addView(ml)
                dlgRoot.addView(sv)
            }

            val close = TextView(ctx)
            close.text = "关闭"
            close.setTextSize(14f)
            close.setTextColor(AppColors.text2())
            close.gravity = Gravity.CENTER
            close.setPadding(0, (8 * d).toInt(), 0, 0)
            CandyUi.ripple(close, AppColors.SHAPE_FULL_DP.toFloat())
            close.setOnClickListener { dialog.dismiss() }
            dlgRoot.addView(close)

            dialog.setView(dlgRoot)
            InsetsUtil.transparentWindow(dialog)
            dialog.show()
        }

        // ==================== UI Helpers ====================
        private fun showInputDialog(ctx: Context, parentAct: Activity, d: Float, title: String, et: EditText, onConfirm: BooleanSupplier, onDone: Runnable) {
            val dlgTheme = if (AppColors.isDarkMode()) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
            val dialog = AlertDialog.Builder(ctx, dlgTheme).create()
            val dlgRoot = LinearLayout(ctx)
            dlgRoot.orientation = LinearLayout.VERTICAL
            dlgRoot.setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            dlgRoot.background = CandyUi.dialogBg(ctx)
            InsetsUtil.clipRounded(dlgRoot)

            val dlgTitle = TextView(ctx)
            dlgTitle.text = title
            dlgTitle.setTextSize(16f)
            dlgTitle.setTextColor(AppColors.text1())
            dlgTitle.setTypeface(null, Typeface.BOLD)
            dlgTitle.setPadding(0, 0, 0, (10 * d).toInt())
            dlgRoot.addView(dlgTitle)
            dlgRoot.addView(et)
            dlgRoot.addView(spacerV(ctx, d, 10))

            val btnRow = LinearLayout(ctx)
            btnRow.orientation = LinearLayout.HORIZONTAL
            btnRow.gravity = Gravity.CENTER
            val cancel = TextView(ctx)
            cancel.text = "取消"
            cancel.setTextSize(14f)
            cancel.setTextColor(AppColors.text2())
            cancel.setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), (8 * d).toInt())
            CandyUi.ripple(cancel, AppColors.SHAPE_FULL_DP.toFloat())
            cancel.setOnClickListener { dialog.dismiss() }
            btnRow.addView(cancel)
            val confirm = TextView(ctx)
            confirm.text = "确认"
            confirm.setTextSize(14f)
            confirm.setTextColor(AppColors.accent())
            confirm.setTypeface(null, Typeface.BOLD)
            confirm.setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), (8 * d).toInt())
            CandyUi.ripple(confirm, AppColors.SHAPE_FULL_DP.toFloat())
            confirm.setOnClickListener {
                if (onConfirm.getAsBoolean()) {
                    dialog.dismiss()
                    if (onDone != null) onDone.run()
                }
            }
            btnRow.addView(confirm)
            dlgRoot.addView(btnRow)

            dialog.setView(dlgRoot)
            InsetsUtil.transparentWindow(dialog)
            dialog.show()
        }

        private fun showConfirmDialog(ctx: Context, parentAct: Activity, d: Float, title: String, msg: String, btnText: String, btnColor: Int, onConfirm: Runnable) {
            val dlgTheme = if (AppColors.isDarkMode()) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
            val dialog = AlertDialog.Builder(ctx, dlgTheme).create()
            val dlgRoot = LinearLayout(ctx)
            dlgRoot.orientation = LinearLayout.VERTICAL
            dlgRoot.setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            dlgRoot.background = CandyUi.dialogBg(ctx)
            InsetsUtil.clipRounded(dlgRoot)

            val dlgTitle = TextView(ctx)
            dlgTitle.text = title
            dlgTitle.setTextSize(16f)
            dlgTitle.setTextColor(AppColors.text1())
            dlgTitle.setTypeface(null, Typeface.BOLD)
            dlgTitle.setPadding(0, 0, 0, (4 * d).toInt())
            dlgRoot.addView(dlgTitle)
            val msgTv = TextView(ctx)
            msgTv.text = msg
            msgTv.setTextSize(13f)
            msgTv.setTextColor(AppColors.text2())
            msgTv.setPadding(0, 0, 0, (10 * d).toInt())
            dlgRoot.addView(msgTv)

            val btnRow = LinearLayout(ctx)
            btnRow.orientation = LinearLayout.HORIZONTAL
            btnRow.gravity = Gravity.CENTER
            val cancel = TextView(ctx)
            cancel.text = "取消"
            cancel.setTextSize(14f)
            cancel.setTextColor(AppColors.text2())
            cancel.setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), (8 * d).toInt())
            CandyUi.ripple(cancel, AppColors.SHAPE_FULL_DP.toFloat())
            cancel.setOnClickListener { dialog.dismiss() }
            btnRow.addView(cancel)
            val confirm = TextView(ctx)
            confirm.text = btnText
            confirm.setTextSize(14f)
            confirm.setTextColor(btnColor)
            confirm.setTypeface(null, Typeface.BOLD)
            confirm.setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), (8 * d).toInt())
            CandyUi.ripple(confirm, AppColors.SHAPE_FULL_DP.toFloat())
            confirm.setOnClickListener {
                onConfirm.run()
                dialog.dismiss()
            }
            btnRow.addView(confirm)
            dlgRoot.addView(btnRow)

            dialog.setView(dlgRoot)
            InsetsUtil.transparentWindow(dialog)
            dialog.show()
        }

        private fun makeEditText(ctx: Context, d: Float, hint: String): EditText {
            val et = EditText(ctx)
            et.setHint(hint)
            et.setTextSize(14f)
            et.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            val bg = GradientDrawable()
            bg.setColor(AppColors.inputBg())
            bg.setCornerRadius(AppColors.SHAPE_INPUT_DP * d)
            bg.setStroke((1.5f * d).toInt(), AppColors.outlineVariant())
            et.background = bg
            return et
        }

        private fun makeCard(ctx: Context, d: Float): LinearLayout {
            val card = LinearLayout(ctx)
            card.orientation = LinearLayout.VERTICAL
            card.background = CandyUi.cardBg(ctx)
            card.setPadding(0, 0, 0, 0)
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.setMargins(0, 0, 0, (13 * d).toInt())
            card.layoutParams = lp
            return card
        }

        private fun spacerV(ctx: Context, d: Float, dp: Int): View {
            val v = View(ctx)
            v.layoutParams = LinearLayout.LayoutParams(-1, (dp * d).toInt())
            return v
        }
    }
}