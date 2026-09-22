package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import com.leshao.v3.hook.WxMasterFeatures;
import com.leshao.v3.ui.widgets.M3Page;

/**
 * 万群群发页（v955 M3 重排）：banner + 群发入口。
 * 业务逻辑（WxMasterFeatures.batchSend）与原版一致。
 */
public class WxMasterPageView {

    public static View create(Context ctx, Activity parentAct) {
        ClassLoader cl = parentAct.getClassLoader();

        LinearLayout root = M3Page.root(ctx);

        root.addView(M3Page.section(ctx, "乐少万群定时群发", "勾选多个群 + 输入内容 + 可选定时，一键群发"));

        LinearLayout card1 = M3Page.card(ctx);
        card1.addView(M3Page.clickRow(ctx, "📨", "乐少万群定时群发", "勾选多个群+定时发送",
                () -> {
                    try { WxMasterFeatures.batchSend(parentAct, cl); } catch (Throwable ignored) {}
                }));
        root.addView(card1);

        root.addView(M3Page.section(ctx, "使用提示"));
        LinearLayout cardTip = M3Page.card(ctx);
        cardTip.addView(M3Page.infoRow(ctx, "第一步", "点击后从联系人中选择多个群聊"));
        cardTip.addView(M3Page.divider(ctx));
        cardTip.addView(M3Page.infoRow(ctx, "第二步", "可立即发送，也可设置定时时间"));
        root.addView(cardTip);

        return M3Page.scroll(ctx, root);
    }
}
