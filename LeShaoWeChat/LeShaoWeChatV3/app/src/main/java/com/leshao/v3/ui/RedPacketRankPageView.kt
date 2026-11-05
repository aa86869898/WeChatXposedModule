package com.leshao.v3.ui

import android.app.Activity
import android.content.Context
import android.view.View

import com.leshao.v3.hook.RedPacketHook
import com.leshao.v3.ui.widgets.M3Page

/** 群聊红包排行榜单：按群聚合红包个数与金额（金额降序）。 */
class RedPacketRankPageView private constructor() {

    companion object {

        @JvmStatic
        fun create(ctx: Context, act: Activity): View {
            val d = ctx.resources.displayMetrics.density
            val root = PageKit.pageRoot(ctx)

            root.addView(M3Page.section(ctx, "红包排行榜单", "各会话红包个数和金额统计（金额降序）"))
            root.addView(M3Page.spacer(ctx, 2f))

            val card = PageKit.makeCard(ctx, d)
            card.addView(PageKit.sectionLabel(ctx, "会话排行"))
            val list = RedPacketHook.getRankList()
            if (list.isEmpty()) {
                card.addView(PageKit.bodyText(ctx, "暂无红包数据"))
            } else {
                var rank = 1
                for (row in list) {
                    card.addView(PageKit.bodyText(ctx,
                            "No." + rank + "  " + row[1] + "\n" +
                                    "        红包个数:" + row[2] + "  金额:" + row[3] + "元"))
                    rank++
                }
            }
            root.addView(card)
            return root
        }
    }
}