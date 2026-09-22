package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;

import com.leshao.v3.ContextManager;
import com.leshao.v3.hook.TypingIndicator;
import com.leshao.v3.hook.ChatFooterEnhance;
import com.leshao.v3.hook.ChatUICustom;
import com.leshao.v3.hook.BatchMessage;
import com.leshao.v3.hook.AutoRemark;
import com.leshao.v3.hook.SearchEnhance;
import com.leshao.v3.hook.NotifyCustom;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.ui.widgets.M3Page;

/**
 * 聊天增强页（v955 M3 重排）：聊天增强 + 通知增强两组开关。
 * 业务逻辑（ModuleConfig/prefs/Hook setEnabled/ConfigPanels 设置入口）与原版一致。
 */
public class ChatEnhancePageView {

    public static View create(Context ctx, Activity parentAct) {
        final SharedPreferences prefs = ContextManager.getPrefs();
        final ModuleConfig cfg = ModuleConfig.load(prefs);
        final Activity act = parentAct;

        LinearLayout root = M3Page.root(ctx);

        // ============ 聊天增强 ============
        root.addView(M3Page.section(ctx, "聊天增强"));
        LinearLayout card = M3Page.card(ctx);
        card.addView(M3Page.switchRow(ctx, "⌨️", "对方正在输入提示", "显示对方输入状态",
                cfg.typingIndicatorEnabled, (v, on) -> {
                    cfg.typingIndicatorEnabled = on; cfg.save(prefs); TypingIndicator.setEnabled(on);
                }));
        card.addView(M3Page.divider(ctx));
        card.addView(M3Page.clickRow(ctx, "⌨️", "输入提示设置", "配置输入提示参数",
                () -> ConfigPanels.showTypingIndicator(act, prefs)));
        card.addView(M3Page.divider(ctx));
        card.addView(M3Page.switchRow(ctx, "📝", "输入框增强", "突破字数限制/输入框自适应",
                cfg.chatFooterEnhanceEnabled, (v, on) -> {
                    cfg.chatFooterEnhanceEnabled = on; cfg.save(prefs); ChatFooterEnhance.setEnabled(on);
                }));
        card.addView(M3Page.divider(ctx));
        card.addView(M3Page.clickRow(ctx, "📝", "输入框增强设置", "配置输入框增强参数",
                () -> ConfigPanels.showChatFooterEnhance(act, prefs)));
        card.addView(M3Page.divider(ctx));
        card.addView(M3Page.switchRow(ctx, "🎨", "聊天界面自定义", "背景/气泡颜色/圆角/昵称",
                cfg.chatUICustomEnabled, (v, on) -> {
                    cfg.chatUICustomEnabled = on; cfg.save(prefs); ChatUICustom.setEnabled(on);
                }));
        card.addView(M3Page.divider(ctx));
        card.addView(M3Page.clickRow(ctx, "🎨", "聊天界面设置", "配置气泡/背景/昵称样式",
                () -> ConfigPanels.showChatUICustom(act, prefs)));
        card.addView(M3Page.divider(ctx));
        card.addView(M3Page.switchRow(ctx, "📦", "批量消息操作", "突破9条限制/全选反选",
                cfg.batchMessageEnabled, (v, on) -> {
                    cfg.batchMessageEnabled = on; cfg.save(prefs); BatchMessage.setEnabled(on);
                }));
        card.addView(M3Page.divider(ctx));
        card.addView(M3Page.clickRow(ctx, "📦", "批量操作设置", "配置批量消息参数",
                () -> ConfigPanels.showBatchMessage(act, prefs)));
        card.addView(M3Page.divider(ctx));
        card.addView(M3Page.switchRow(ctx, "🏷", "自动备注好友", "从群昵称/名片自动填充",
                cfg.autoRemarkEnabled, (v, on) -> {
                    cfg.autoRemarkEnabled = on; cfg.save(prefs); AutoRemark.setEnabled(on);
                }));
        card.addView(M3Page.divider(ctx));
        card.addView(M3Page.clickRow(ctx, "🏷", "自动备注设置", "配置自动备注规则",
                () -> ConfigPanels.showAutoRemark(act, prefs)));
        card.addView(M3Page.divider(ctx));
        card.addView(M3Page.switchRow(ctx, "🔎", "全文搜索增强", "增强聊天记录搜索能力",
                cfg.searchEnhanceEnabled, (v, on) -> {
                    cfg.searchEnhanceEnabled = on; cfg.save(prefs); SearchEnhance.setEnabled(on);
                }));
        card.addView(M3Page.divider(ctx));
        card.addView(M3Page.clickRow(ctx, "🔎", "搜索增强设置", "配置搜索增强参数",
                () -> ConfigPanels.showSearchEnhance(act, prefs)));
        root.addView(card);

        // ============ 通知增强 ============
        root.addView(M3Page.section(ctx, "通知增强"));
        LinearLayout card2 = M3Page.card(ctx);
        card2.addView(M3Page.switchRow(ctx, "🔔", "通知自定义", "快捷回复/头像/优先级",
                cfg.notifyCustomEnabled, (v, on) -> {
                    cfg.notifyCustomEnabled = on; cfg.save(prefs); NotifyCustom.setEnabled(on);
                }));
        card2.addView(M3Page.divider(ctx));
        card2.addView(M3Page.clickRow(ctx, "🔔", "通知设置", "配置通知自定义参数",
                () -> ConfigPanels.showNotifyCustom(act, prefs)));
        root.addView(card2);

        return M3Page.scroll(ctx, root);
    }
}
