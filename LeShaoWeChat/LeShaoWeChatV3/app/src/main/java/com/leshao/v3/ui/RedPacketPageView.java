package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;

import com.leshao.v3.ContextManager;
import com.leshao.v3.hook.RedPacketAlert;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.ui.widgets.M3Page;

/**
 * 红包功能页（v955 M3 重排）：入口导航 + 提醒开关。
 * 业务逻辑（SubPageActivity 跳转/ConfigPanels/RedPacketHook 开关）与原版一致。
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

        root.addView(M3Page.section(ctx, "红包提醒"));

        final SharedPreferences prefs = ContextManager.getPrefs();
        final ModuleConfig cfg = ModuleConfig.load(prefs);

        LinearLayout cardAlert = M3Page.card(ctx);
        cardAlert.addView(M3Page.switchRow(ctx, "📳", "红包震动+响铃提醒", "收到红包时强制震动和响铃",
                cfg.redPacketAlertEnabled, (v, on) -> {
                    cfg.redPacketAlertEnabled = on; cfg.save(prefs); RedPacketAlert.setEnabled(on);
                }));
        cardAlert.addView(M3Page.divider(ctx));
        cardAlert.addView(M3Page.clickRow(ctx, "🔔", "提醒设置", "配置提醒的震动/响铃方式",
                () -> ConfigPanels.showRedAlert(parentAct, prefs)));
        root.addView(cardAlert);

        return M3Page.scroll(ctx, root);
    }
}
