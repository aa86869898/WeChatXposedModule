package com.leshao.v3.ui

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.leshao.v3.hook.WeChatIdInjectHook
import com.leshao.v3.ui.widgets.M3Page

/**
 * v3.0.163：「查看微信wxid」详情页（更多功能 → 查看微信wxid）。
 *
 * 展示功能开关、离线库统计与使用说明（严格移植 WeChatIDInject.zip 的数据源：
 * 当场联系人 field_alias + 群成员 wxid 全量枚举 + 报文 dump 离线库）。
 */
class WxIdViewPageView private constructor() {

    companion object {
        @JvmStatic
        fun create(ctx: Context, act: Activity): View {
            val root = M3Page.root(ctx)

            root.addView(M3Page.section(ctx, "查看微信wxid",
                    "联系人/群成员资料页注入「微信号 / ID」行（移植 WeChatIDInject.zip）"))
            root.addView(M3Page.spacer(ctx, 2f))

            val card = M3Page.card(ctx)
            M3Page.appendSwitchRow(card, ctx, "🔍", "启用在资料页显示",
                    "开启后打开联系人/群成员资料页，在「微信号」下方注入一行",
                    WeChatIdInjectHook.isEnabled(), { _, on ->
                        WeChatIdInjectHook.setEnabled(on)
                        M3Page.toastSuccess(ctx, "已" + (if (on) "开启" else "关闭"))
                    })
            card.addView(M3Page.divider(ctx))

            M3Page.appendClickRow(card, ctx, "🗂", "离线库统计",
                    "wxid_alias.tsv / wxid_known.tsv（/sdcard/Download/）\n"
                            + "已收集 " + WeChatIdInjectHook.size() + " 条微信号映射",
                    { M3Page.toast(ctx, "已收集映射 " + WeChatIdInjectHook.size() + " 条") })
            root.addView(card)
            root.addView(M3Page.spacer(ctx, 2f))

            val note = M3Page.card(ctx)
            val tv = TextView(ctx)
            tv.setTextSize(13f)
            tv.setLineSpacing(4f, 1f)
            tv.setTextColor(AppColors.onSurfaceVariant())
            tv.text = "原理：\n" + "1. 当场联系人：资料页读取 field_alias / field_username 并落库；\n" + "2. 群成员枚举：群成员详情网络响应里复用微信自身解析，全量收集成员 wxid；\n" + "3. 报文 dump：verifyuser / searchcontact 等响应对近配对挖 alias；\n" + "4. UI 注入：在资料页「微信号/WeChat」行下方插入摘要行。\n\n" + "显示值优先级：离线库命中 > 当场 alias > username。\n" + "陌生人微信号受服务端硬限制，稳定产出为 wxid（属预期）。"
            note.addView(tv)
            root.addView(note)

            return root
        }
    }
}