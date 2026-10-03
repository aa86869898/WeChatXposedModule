package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.leshao.v3.hook.AdBlockerHook;
import com.leshao.v3.hook.HookConfig;
import com.leshao.v3.ui.widgets.M3Page;

/**
 * 更多功能页（主页「更多功能」卡片入口）。
 *
 * <p>当前包含：去你妈的广告（{@link AdBlockerHook}，开关 key = {@code sns_ad_block}）。</p>
 */
public final class MoreFeaturesPageView {

    private MoreFeaturesPageView() {}

    public static View create(Context ctx, Activity act) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout root = PageKit.pageRoot(ctx);

        root.addView(M3Page.section(ctx, "去广告",
                "屏蔽微信内各类广告（小程序 / 朋友圈 / 视频号）"));
        root.addView(M3Page.spacer(ctx, 2));

        boolean adOn = HookConfig.isEnabled(AdBlockerHook.PREF_ENABLED);
        LinearLayout cardAd = PageKit.makeCard(ctx, d);
        cardAd.addView(PageKit.switchRow(ctx, d, "去你妈的广告",
                "屏蔽小程序开屏广告、朋友圈广告、视频号广告（重启微信后完全生效）",
                adOn,
                (v, on) -> {
                    AdBlockerHook.setEnabled(on);
                    Toast.makeText(ctx, "去广告已" + (on ? "开启" : "关闭")
                            + "（重启微信后完全生效）", Toast.LENGTH_SHORT).show();
                }, null));
        root.addView(cardAd);
        root.addView(PageKit.divider(ctx));

        LinearLayout cardNote = PageKit.makeCard(ctx, d);
        cardNote.addView(PageKit.bodyText(ctx,
                "原理：按《微信去广告_完整方案_三轮审查合并终版.md》对小程序（AppBrand）、"
                        + "朋友圈（SNS Timeline）、视频号（Finder）的广告位做拦截/过滤/隐藏。"));
        root.addView(cardNote);
        return root;
    }
}
