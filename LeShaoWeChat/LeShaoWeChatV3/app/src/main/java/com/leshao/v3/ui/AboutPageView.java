package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;

import com.leshao.v3.ContextManager;
import com.leshao.v3.MainHook;
import com.leshao.v3.hook.WeChatUpdateBlocker;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.ui.widgets.M3Page;

/**
 * 关于模块页：展示模块版本信息，并提供微信更新管控入口。
 */
public class AboutPageView {

    public static View create(Context ctx, Activity parentAct) {
        final SharedPreferences prefs = ContextManager.getPrefs();
        final ModuleConfig cfg = ModuleConfig.load(prefs);

        LinearLayout root = M3Page.root(ctx);

        root.addView(M3Page.section(ctx, "模块信息"));
        LinearLayout infoCard = M3Page.card(ctx);
        infoCard.addView(M3Page.infoRow(ctx, "模块名称", "乐少微信助手 V3"));
        infoCard.addView(M3Page.divider(ctx));
        infoCard.addView(M3Page.infoRow(ctx, "构建版本", MainHook.MODULE_BUILD));
        infoCard.addView(M3Page.divider(ctx));
        infoCard.addView(M3Page.infoRow(ctx, "版本号", String.valueOf(MainHook.MODULE_VERSION_CODE)));
        root.addView(infoCard);

        root.addView(M3Page.section(ctx, "微信更新管控"));
        LinearLayout updateCard = M3Page.card(ctx);
        updateCard.addView(M3Page.switchRow(ctx, "\u26d4", "禁止微信热更新",
                "阻断版本升级与 Tinker 热补丁，避免公众号/微信用静默更新",
                cfg.blockWechatUpdate, (v, on) -> {
                    cfg.blockWechatUpdate = on;
                    cfg.save(prefs);
                    WeChatUpdateBlocker.setEnabled(on);
                }));
        updateCard.addView(M3Page.divider(ctx));
        updateCard.addView(M3Page.button(ctx, "立即应用管控", () -> {
            WeChatUpdateBlocker.setEnabled(cfg.blockWechatUpdate);
            M3Page.toast(ctx, "管控设置已保存，重启微信后生效");
        }));
        root.addView(updateCard);

        return M3Page.scroll(ctx, root);
    }
}
