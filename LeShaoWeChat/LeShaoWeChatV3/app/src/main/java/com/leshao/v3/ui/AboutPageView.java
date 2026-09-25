package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;

import com.leshao.v3.ContextManager;
import com.leshao.v3.MainHook;
import com.leshao.v3.hook.DexKitHelper;
import com.leshao.v3.hook.WeChatUpdateBlocker;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.ui.widgets.M3Page;

/**
 * 关于模块页：展示模块版本信息、DexKit 加载入口，并提供微信更新管控开关。
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
        infoCard.addView(M3Page.divider(ctx));
        infoCard.addView(M3Page.infoRow(ctx, "编译时间", MainHook.MODULE_BUILD_TIME));
        root.addView(infoCard);

        root.addView(M3Page.section(ctx, "DexKit 加载"));
        LinearLayout dexCard = M3Page.card(ctx);
        dexCard.addView(M3Page.infoRow(ctx, "扫描状态",
                DexKitHelper.isScanComplete() ? "已完成" : "未完成"));
        dexCard.addView(M3Page.divider(ctx));
        dexCard.addView(M3Page.button(ctx, "启动微信自动加载 DexKit", () -> {
            if (DexKitHelper.isReloading()) {
                M3Page.toast(ctx, "正在重新加载，请稍候");
                return;
            }
            DexKitHelper.forceReload();
            M3Page.toast(ctx, "已开始重新加载 DexKit，请稍候");
        }));
        root.addView(dexCard);

        root.addView(M3Page.section(ctx, "微信更新管控"));
        LinearLayout updateCard = M3Page.card(ctx);
        updateCard.addView(M3Page.switchRow(ctx, "\u26d4", "禁止微信热更新",
                "阻断版本升级与 Tinker 热补丁，避免公众号/微信用静默更新（重启微信后生效）",
                cfg.blockWechatUpdate, (v, on) -> {
                    cfg.blockWechatUpdate = on;
                    cfg.save(prefs);
                    WeChatUpdateBlocker.setEnabled(on);
                }));
        root.addView(updateCard);

        return M3Page.scroll(ctx, root);
    }
}
