package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.ScrollView;
import android.widget.LinearLayout;

import com.leshao.v3.ContextManager;
import com.leshao.v3.hook.SnsFeatures;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.ui.widgets.M3Page;

public class SnsPageView {

    public static View create(Context ctx, Activity parentAct) {
        final SharedPreferences prefs = ContextManager.getPrefs();
        final ModuleConfig cfg = ModuleConfig.load(prefs);
        final Activity act = parentAct;

        LinearLayout root = M3Page.root(ctx);
        ScrollView sv = M3Page.scroll(ctx, root);

        root.addView(M3Page.section(ctx, "朋友圈增强"));

        LinearLayout cardMain = M3Page.card(ctx);
        cardMain.addView(M3Page.switchRow(ctx, "⚙", "启用朋友圈增强",
                "关闭后所有子功能均不生效", cfg.snsFeaturesEnabled, (v, on) -> {
            cfg.snsFeaturesEnabled = on;
            cfg.save(prefs);
            SnsFeatures.setEnabled(on);
        }));
        root.addView(cardMain);

        root.addView(M3Page.spacer(ctx, 8f));
        root.addView(M3Page.section(ctx, "功能开关"));

        LinearLayout cardSub = M3Page.card(ctx);
        cardSub.addView(M3Page.switchRow(ctx, "🚫", "去广告", "隐藏朋友圈中的广告内容",
                prefs.getBoolean("sns_ad_block", true), (v, on) -> {
            prefs.edit().putBoolean("sns_ad_block", on).apply();
        }));
        cardSub.addView(M3Page.divider(ctx));
        cardSub.addView(M3Page.switchRow(ctx, "📤", "转发与复制", "支持转发到聊天和复制文字内容",
                prefs.getBoolean("sns_forward", true), (v, on) -> {
            prefs.edit().putBoolean("sns_forward", on).apply();
        }));
        cardSub.addView(M3Page.divider(ctx));
        cardSub.addView(M3Page.switchRow(ctx, "👍", "假点赞", "强制点赞结果返回成功",
                prefs.getBoolean("sns_simulate_like", false), (v, on) -> {
            prefs.edit().putBoolean("sns_simulate_like", on).apply();
        }));
        cardSub.addView(M3Page.divider(ctx));
        cardSub.addView(M3Page.switchRow(ctx, "⏰", "时间修改", "修改朋友圈发布时间的偏移量",
                prefs.getBoolean("sns_time_edit", false), (v, on) -> {
            prefs.edit().putBoolean("sns_time_edit", on).apply();
        }));
        cardSub.addView(M3Page.divider(ctx));
        cardSub.addView(M3Page.clickRow(ctx, "⏱", "时间偏移设置", "设置朋友圈发布时间的偏移量",
            () -> ConfigPanels.showSnsTimeOffset(act, prefs)));
        cardSub.addView(M3Page.divider(ctx));
        cardSub.addView(M3Page.switchRow(ctx, "🎬", "视频画质增强", "解锁朋友圈视频的高画质播放",
                prefs.getBoolean("sns_video_quality", true), (v, on) -> {
            prefs.edit().putBoolean("sns_video_quality", on).apply();
        }));
        cardSub.addView(M3Page.divider(ctx));
        cardSub.addView(M3Page.switchRow(ctx, "🎥", "长视频", "解除朋友圈视频时长限制",
                prefs.getBoolean("sns_long_video", true), (v, on) -> {
            prefs.edit().putBoolean("sns_long_video", on).apply();
        }));
        root.addView(cardSub);

        return sv;
    }
}
