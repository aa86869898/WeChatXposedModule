package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import com.leshao.v3.ui.widgets.M3Page;

/**
 * 红包功能页（v955 M3 重排）：入口导航。
 * 业务逻辑（SubPageActivity 跳转/RedPacketHook 开关）与原版一致。
 */
public class RedPacketPageView {

    public static View create(Context ctx, Activity parentAct) {
        LinearLayout root = M3Page.root(ctx);

        root.addView(M3Page.section(ctx, "红包增强"));

        LinearLayout cardEntry = M3Page.card(ctx);
        cardEntry.addView(M3Page.clickRow(ctx, "🧧", "自动秒抢红包",
                "配置红包自动领取功能，支持私聊/群聊、时间段过滤、关键词过滤、秒抢名单",
                () -> SubPageActivity.open(parentAct, "自动秒抢红包", 91)));
        root.addView(cardEntry);

        return M3Page.scroll(ctx, root);
    }
}
