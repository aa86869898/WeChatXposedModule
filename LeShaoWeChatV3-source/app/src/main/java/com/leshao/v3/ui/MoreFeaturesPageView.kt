package com.leshao.v3.ui

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast

import com.leshao.v3.hook.AdBlockerHook
import com.leshao.v3.hook.FaceScanHook
import com.leshao.v3.hook.FakeLocationHook
import com.leshao.v3.hook.HookConfig
import com.leshao.v3.hook.MomentsAutoLikeHook
import com.leshao.v3.hook.MomentsFakeLikeHook
import com.leshao.v3.hook.WeChatIdInjectHook
import com.leshao.v3.ui.widgets.M3Page

/**
 * 更多功能页（主页「更多功能」卡片入口）。
 *
 * 当前包含：去你妈的广告（[AdBlockerHook]，开关 key = sns_ad_block）。
 */
class MoreFeaturesPageView private constructor() {

    companion object {

        @JvmStatic
        fun create(ctx: Context, act: Activity): View {
            val d = ctx.resources.displayMetrics.density
            val root = PageKit.pageRoot(ctx)

            root.addView(M3Page.section(ctx, "去广告",
                    "屏蔽微信内各类广告（小程序 / 朋友圈 / 视频号）"))
            root.addView(M3Page.spacer(ctx, 2f))

            val adOn = HookConfig.isEnabled(AdBlockerHook.PREF_ENABLED)
            val cardAd = PageKit.makeCard(ctx, d)
            cardAd.addView(PageKit.switchRow(ctx, d, "去你妈的广告",
                    "屏蔽小程序开屏广告、朋友圈广告、视频号广告（重启微信后完全生效）",
                    adOn,
                    { _, on ->
                        AdBlockerHook.setEnabled(on)
                        Toast.makeText(ctx, "去广告已" + (if (on) "开启" else "关闭")
                                + "（重启微信后完全生效）", Toast.LENGTH_SHORT).show()
                    }, null))
            root.addView(cardAd)
            root.addView(PageKit.divider(ctx))

            val cardNote = PageKit.makeCard(ctx, d)
            cardNote.addView(PageKit.bodyText(ctx,
                    "原理：按《微信去广告_完整方案_三轮审查合并终版.md》对小程序（AppBrand）、" +
                            "朋友圈（SNS Timeline）、视频号（Finder）的广告位做拦截/过滤/隐藏。"))
            root.addView(cardNote)
            root.addView(PageKit.divider(ctx))

            // ---- 定位伪装 ----
            root.addView(M3Page.section(ctx, "定位伪装",
                    "仅对「实时共享位置」生效"))
            root.addView(M3Page.spacer(ctx, 2f))

            val locOn = FakeLocationHook.isEnabled()
            val cardLoc = PageKit.makeCard(ctx, d)
            cardLoc.addView(PageKit.switchRow(ctx, d, "定位伪装",
                    "共享实时位置时替换为你选择的坐标（其它定位保持真实）",
                    locOn,
                    { _, on ->
                        FakeLocationHook.setEnabled(on)
                        Toast.makeText(ctx, "定位伪装已" + (if (on) "开启" else "关闭")
                                + "（进入实时共享位置生效）", Toast.LENGTH_SHORT).show()
                    },
                    { SubPageActivity.open(act, "定位伪装", 29) }))
            root.addView(cardLoc)
            root.addView(PageKit.divider(ctx))

            // ---- 朋友圈自动点赞（v3.0.139 入口迁移：主页更多功能 + 朋友圈右上角「⋮」双入口） ----
            root.addView(M3Page.section(ctx, "朋友圈",
                    "自动点赞 / 秒集赞工具"))
            root.addView(M3Page.spacer(ctx, 2f))

            val cardLike = PageKit.makeCard(ctx, d)
            cardLike.addView(M3Page.clickRow(ctx, "👍", "朋友圈自动点赞",
                    "对指定联系人的动态自动点赞（朋友圈右上角「⋮」也可进入）",
                    { SubPageActivity.open(act, "朋友圈自动点赞", 30) }))
            root.addView(cardLike)
            root.addView(PageKit.divider(ctx))

            val cardFakeLike = PageKit.makeCard(ctx, d)
            cardFakeLike.addView(PageKit.switchRow(ctx, d, "朋友圈秒集赞",
                    "长按朋友圈 → 秒集赞（立即伪造指定数量的点赞，无需等待好友）",
                    MomentsFakeLikeHook.isEnabled(),
                    { _, on ->
                        MomentsFakeLikeHook.setEnabled(on)
                        Toast.makeText(ctx, "朋友圈秒集赞已" + (if (on) "开启" else "关闭")
                                + "（重新进入朋友圈生效）", Toast.LENGTH_SHORT).show()
                    },
                    { SubPageActivity.open(act, "朋友圈秒集赞", 31) }))
            root.addView(cardFakeLike)
            root.addView(PageKit.divider(ctx))

            // ---- 查看微信wxid（v3.0.163：移植 WeChatIDInject.zip） ----
            root.addView(M3Page.section(ctx, "微信号/ID 查看",
                    "联系人/群成员资料页注入「微信号 / ID」行"))
            root.addView(M3Page.spacer(ctx, 2f))

            val cardWxid = PageKit.makeCard(ctx, d)
            cardWxid.addView(PageKit.switchRow(ctx, d, "查看微信wxid",
                    "打开联系人/群成员资料页时，在「微信号」下方注入一行显示微信号或 ID / wxid" +
                            "（累计已收集 " + WeChatIdInjectHook.size() + " 条）",
                    WeChatIdInjectHook.isEnabled(),
                    { _, on ->
                        WeChatIdInjectHook.setEnabled(on)
                        Toast.makeText(ctx, "查看微信wxid已" + (if (on) "开启" else "关闭")
                                + "（打开资料页生效）", Toast.LENGTH_SHORT).show()
                    },
                    { SubPageActivity.open(act, "查看微信wxid", 32) }))
            root.addView(cardWxid)
            root.addView(PageKit.divider(ctx))

            // v3.0.206: 去你妈的面对面扫码（更多功能 -> 扫码）
            root.addView(M3Page.section(ctx, "扫码",
                    "相册二维码按真实面对面扫码交付"))
            root.addView(M3Page.spacer(ctx, 2f))

            val cardFaceScan = PageKit.makeCard(ctx, d)
            cardFaceScan.addView(PageKit.switchRow(ctx, d, "去你妈的面对面扫码",
                    "扫一扫支持从相册选二维码，并按真实面对面扫码返回结果（result_image_source=1）",
                    FaceScanHook.isEnabled(),
                    { _, on ->
                        FaceScanHook.setEnabled(on)
                        Toast.makeText(ctx, "去你妈的面对面扫码已" + (if (on) "开启" else "关闭")
                                + "（重启微信后完全生效）", Toast.LENGTH_SHORT).show()
                    }, null))
            root.addView(cardFaceScan)
            root.addView(PageKit.divider(ctx))

            // v3.0.132: 朋友圈自动点赞配置已迁移至朋友圈右上角「⋮ 自动点赞」菜单，主界面不再保留入口
            return root
        }
    }
}
