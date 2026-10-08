package com.leshao.v3.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast

import com.leshao.v3.hook.MomentsAutoLikeHook
import com.leshao.v3.ui.widgets.M3Page

/**
 * 朋友圈自动点赞功能页 —— 开关 + 点赞联系人选择。
 *
 * 在朋友圈页面右上角「⋮」菜单中提供「开始/停止自动点赞」，实现见 [MomentsAutoLikeHook]。
 */
class MomentsLikePageView private constructor() {

    companion object {

        @JvmStatic
        fun create(ctx: Context, act: Activity): View {
            val d = ctx.resources.displayMetrics.density
            val root = PageKit.pageRoot(ctx)

            root.addView(M3Page.section(ctx, "朋友圈自动点赞",
                    "在朋友圈右上角注入「⋮」菜单，对指定联系人的动态自动点赞"))
            root.addView(M3Page.spacer(ctx, 2f))

            val cardSwitch = PageKit.makeCard(ctx, d)
            cardSwitch.addView(PageKit.switchRow(ctx, d, "开启朋友圈自动点赞",
                    "开启后在朋友圈页面右上角显示「⋮ 自动点赞」菜单",
                    MomentsAutoLikeHook.isEnabled(),
                    { _, on ->
                        MomentsAutoLikeHook.setEnabled(on)
                        Toast.makeText(ctx, "朋友圈自动点赞已" + (if (on) "开启" else "关闭")
                                + "（重新进入朋友圈生效）", Toast.LENGTH_SHORT).show()
                    }, null))
            root.addView(cardSwitch)
            root.addView(PageKit.divider(ctx))

            val count = MomentsAutoLikeHook.getSelectedCount()
            val sub = if (count == 0) "未选择（默认点赞全部好友）" else "已选择 $count 位联系人"
            val cardContacts = PageKit.makeCard(ctx, d)
            cardContacts.addView(M3Page.clickRow(ctx, "👥", "点赞联系人", sub) {
                val cur = StringBuilder()
                val sel = MomentsAutoLikeHook.getSelected()
                for (w in sel) {
                    if (cur.length > 0) cur.append(',')
                    cur.append(w)
                }
                ContactPickerDialog.show(act, cur.toString(), ContactPickerDialog.MODE_FRIEND,
                        { wxids, _ ->
                            MomentsAutoLikeHook.setSelected(wxids)
                            Toast.makeText(ctx, "已选择 " + wxids.size
                                    + " 位联系人", Toast.LENGTH_SHORT).show()
                            SubPageActivity.refreshCurrent(act)
                        },
                        null, "选择点赞联系人")
            })
            root.addView(cardContacts)
            root.addView(PageKit.divider(ctx))

            // 定时原生刷新（文档 §7）：通过 ImproveOverScrollView.a(int) 复用微信原生下拉刷新，
            // 自动发现新帖并入点赞扫描队列；间隔默认 5 分钟，建议 ≥2~5 分钟。
            val cardRefresh = PageKit.makeCard(ctx, d)
            cardRefresh.addView(PageKit.switchRow(ctx, d, "开启定时刷新",
                    "自动按间隔刷新朋友圈，发现新动态并入点赞队列（建议 5 分钟以上）",
                    MomentsAutoLikeHook.isRefreshEnabled(),
                    { _, on ->
                        MomentsAutoLikeHook.setRefreshEnabled(on)
                        Toast.makeText(ctx, "定时刷新已" + (if (on) "开启" else "关闭")
                                + "（开启后下次进入朋友圈生效）", Toast.LENGTH_SHORT).show()
                    }, null))
            cardRefresh.addView(M3Page.clickRow(ctx, "⏱", "刷新间隔",
                    "每 " + MomentsAutoLikeHook.getRefreshMinutes() + " 分钟刷新一次") {
                val mins = arrayOf("2 分钟", "5 分钟", "10 分钟", "15 分钟", "30 分钟")
                val vals = intArrayOf(2, 5, 10, 15, 30)
                AlertDialog.Builder(act)
                        .setTitle("定时刷新间隔")
                        .setItems(mins) { _, which ->
                            MomentsAutoLikeHook.setRefreshMinutes(vals[which])
                            Toast.makeText(ctx, "刷新间隔已设为 " + vals[which]
                                    + " 分钟", Toast.LENGTH_SHORT).show()
                            SubPageActivity.refreshCurrent(act)
                        }
                        .show()
            })
            root.addView(cardRefresh)
            root.addView(PageKit.divider(ctx))

            val cardNote = PageKit.makeCard(ctx, d)
            cardNote.addView(PageKit.bodyText(ctx,
                    "使用：进入朋友圈 → 点右上角「⋮ 自动点赞」→ 选择联系人 / 开始自动点赞。" +
                            "未选择联系人时默认点赞全部好友动态（自动跳过广告与已赞）。" +
                            "每条点赞间隔 3.5~6 秒以降低风控风险。" +
                            "原理：Hook ImproveSnsTimelineUI.onCreateOptionsMenu 注入菜单项，" +
                            "通过 lk4.g.W7 枚举时间线动态，调用 h6.n(SnsInfo,1,null,0) 标准路由" +
                            "立即发送点赞（修复旧 h6.p 路由只入队不发送的问题）。" +
                            "定时刷新复用微信原生下拉刷新（ImproveOverScrollView.a(1)），" +
                            "自动发现新帖并入自动点赞队列。"))
            root.addView(cardNote)
            return root
        }
    }
}
