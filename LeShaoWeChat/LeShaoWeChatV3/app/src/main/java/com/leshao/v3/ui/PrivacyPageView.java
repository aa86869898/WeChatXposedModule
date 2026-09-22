package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.ScrollView;
import android.widget.LinearLayout;

import com.leshao.v3.ContextManager;
import com.leshao.v3.hook.PrivacyFeatures;
import com.leshao.v3.hook.LoginMonitor;
import com.leshao.v3.hook.HideContactFields;
import com.leshao.v3.hook.ConvPrivacy;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.ui.widgets.M3Page;

public class PrivacyPageView {

    public static View create(Context ctx, Activity parentAct) {
        final SharedPreferences prefs = ContextManager.getPrefs();
        final ModuleConfig cfg = ModuleConfig.load(prefs);
        final Activity act = parentAct;

        LinearLayout root = M3Page.root(ctx);
        ScrollView sv = M3Page.scroll(ctx, root);

        root.addView(M3Page.section(ctx, "隐私安全"));

        LinearLayout card = M3Page.card(ctx);
        card.addView(M3Page.switchRow(ctx, "🛡", "隐私保护 (截图检测/剪贴板/WebView/指纹锁定)",
                null, cfg.privacyFeaturesEnabled, (v, on) -> {
            cfg.privacyFeaturesEnabled = on;
            cfg.save(prefs);
            PrivacyFeatures.setEnabled(on);
        }));
        card.addView(M3Page.divider(ctx));
        card.addView(M3Page.clickRow(ctx, "⚙", "指纹与安全设置", null,
                () -> ConfigPanels.showFingerprintLock(act, prefs)));
        card.addView(M3Page.divider(ctx));
        card.addView(M3Page.switchRow(ctx, "📱", "登录设备监控",
                null, cfg.loginMonitorEnabled, (v, on) -> {
            cfg.loginMonitorEnabled = on;
            cfg.save(prefs);
            LoginMonitor.setEnabled(on);
        }));
        card.addView(M3Page.divider(ctx));
        card.addView(M3Page.switchRow(ctx, "👤", "隐藏联系人敏感字段",
                null, cfg.hideContactFieldsEnabled, (v, on) -> {
            cfg.hideContactFieldsEnabled = on;
            cfg.save(prefs);
            HideContactFields.setEnabled(on);
        }));
        card.addView(M3Page.divider(ctx));
        card.addView(M3Page.clickRow(ctx, "⚙", "隐藏字段设置", null,
                () -> ConfigPanels.showHideContactFields(act, prefs)));
        card.addView(M3Page.divider(ctx));
        card.addView(M3Page.switchRow(ctx, "💬", "会话隐私保护",
                null, cfg.convPrivacyEnabled, (v, on) -> {
            cfg.convPrivacyEnabled = on;
            cfg.save(prefs);
            ConvPrivacy.setEnabled(on);
        }));
        card.addView(M3Page.divider(ctx));
        card.addView(M3Page.clickRow(ctx, "⚙", "会话隐私设置", null,
                () -> ConfigPanels.showConvPrivacy(act, prefs)));
        root.addView(card);

        return sv;
    }
}
