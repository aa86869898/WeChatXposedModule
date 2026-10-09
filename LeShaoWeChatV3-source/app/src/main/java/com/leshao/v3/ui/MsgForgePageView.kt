package com.leshao.v3.ui

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import com.leshao.v3.hook.MsgForgeHook
import com.leshao.v3.ui.widgets.M3Page

/** 消息伪装功能页：发出的文本替换为自定义伪装文字。 */
class MsgForgePageView private constructor() {

    companion object {

        @JvmStatic
        fun create(ctx: Context, act: Activity): View {
            val d = ctx.resources.displayMetrics.density
            val root = PageKit.pageRoot(ctx)

            MsgForgeHook.updateConfig()

            // v1145: 页面顶部统一分区标题
            root.addView(M3Page.section(ctx, "消息伪装",
                    "发出的文本内容替换为自定义伪装文字"))
            root.addView(M3Page.spacer(ctx, 2f))

            // 总开关
            val cardSwitch = PageKit.makeCard(ctx, d)
            cardSwitch.addView(PageKit.switchRow(ctx, d, "消息伪装",
                    "发出的文本内容替换为自定义伪装文字", MsgForgeHook.isEnabled(),
                    { _, on ->
                        MsgForgeHook.setEnabled(on)
                        Toast.makeText(ctx, "消息伪装已" + (if (on) "开启" else "关闭"), Toast.LENGTH_SHORT).show()
                    }, null))
            root.addView(cardSwitch)
            root.addView(PageKit.divider(ctx))

            // 伪装文案
            val input = M3Page.input(ctx, "")
            input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            input.setText(MsgForgeHook.getText())
            val previewText = PageKit.bodyText(ctx, MsgForgeHook.preview())

            val cardContent = PageKit.makeCard(ctx, d)
            cardContent.addView(PageKit.sectionLabel(ctx, "伪装文案"))
            cardContent.addView(input)
            cardContent.addView(PageKit.actionButton(ctx, "保存文案") {
                MsgForgeHook.setText(input.text.toString())
                previewText.setText(MsgForgeHook.preview())
                Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show()
            })
            root.addView(cardContent)
            root.addView(PageKit.divider(ctx))

            // 预览
            val cardPreview = PageKit.makeCard(ctx, d)
            cardPreview.addView(PageKit.sectionLabel(ctx, "预览（发送给对方的内容）"))
            cardPreview.addView(previewText)
            root.addView(cardPreview)
            root.addView(PageKit.divider(ctx))

            val cardNote = PageKit.makeCard(ctx, d)
            cardNote.addView(PageKit.bodyText(ctx,
                    "说明：开启后，在聊天框发送任何文字，对方收到的都是上面的伪装文案。" +
                            "消息类型保持普通文本（type=1），确保发送成功、不转圈、不显示原始 XML。"))
            root.addView(cardNote)

            return root
        }
    }
}
