package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.leshao.v3.hook.RedPacketHook;
import com.leshao.v3.ui.widgets.M3Page;

/** 自动抢红包功能页（文档《WeChat_RedPacket_Background_Grab_Reverse.md》）。 */
public final class RedPacketPageView {

    private RedPacketPageView() {}

    public static View create(Context ctx, Activity act) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout root = PageKit.pageRoot(ctx);
        RedPacketHook.updateConfig();

        LinearLayout cardSwitch = PageKit.makeCard(ctx, d);
        cardSwitch.addView(PageKit.switchRow(ctx, d, "自动抢红包",
                "纯后台解析红包消息并自动领取", RedPacketHook.isEnabled(),
                (v, on) -> {
                    RedPacketHook.setEnabled(on);
                    Toast.makeText(ctx, "自动抢红包已" + (on ? "开启" : "关闭")
                            + "（重启微信后完全生效）", Toast.LENGTH_SHORT).show();
                }, null));
        cardSwitch.addView(PageKit.switchRow(ctx, d, "仅抢群聊",
                "单聊红包不领取，降低风控风险", RedPacketHook.isGroupOnly(),
                (v, on) -> RedPacketHook.setGroupOnly(on), null));
        root.addView(cardSwitch);
        root.addView(PageKit.divider(ctx));

        LinearLayout cardParam = PageKit.makeCard(ctx, d);
        cardParam.addView(PageKit.sectionLabel(ctx, "参数"));

        cardParam.addView(PageKit.bodyText(ctx, "每分钟最多领取次数"));
        final EditText etMax = M3Page.input(ctx, "10");
        etMax.setInputType(InputType.TYPE_CLASS_NUMBER);
        etMax.setText(String.valueOf(RedPacketHook.getMaxPerMin()));
        cardParam.addView(etMax);

        cardParam.addView(PageKit.bodyText(ctx, "随机延时上限（毫秒，0=不延迟）"));
        final EditText etDelay = M3Page.input(ctx, "0");
        etDelay.setInputType(InputType.TYPE_CLASS_NUMBER);
        etDelay.setText(String.valueOf(RedPacketHook.getDelayMax()));
        cardParam.addView(etDelay);

        cardParam.addView(PageKit.bodyText(ctx, "白名单（群/联系人 wxid，逗号分隔，留空=不限制）"));
        final EditText etWhite = M3Page.input(ctx, "可选");
        etWhite.setText(RedPacketHook.getWhitelist());
        cardParam.addView(etWhite);

        cardParam.addView(PageKit.actionButton(ctx, "保存参数", v -> {
            try {
                RedPacketHook.setMaxPerMin(Integer.parseInt(etMax.getText().toString().trim()));
            } catch (Throwable ignored) {}
            try {
                RedPacketHook.setDelayMax(Integer.parseInt(etDelay.getText().toString().trim()));
            } catch (Throwable ignored) {}
            RedPacketHook.setWhitelist(etWhite.getText().toString().trim());
            RedPacketHook.updateConfig();
            Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show();
        }));
        root.addView(cardParam);
        root.addView(PageKit.divider(ctx));

        LinearLayout cardStat = PageKit.makeCard(ctx, d);
        cardStat.addView(PageKit.sectionLabel(ctx, "运行状态"));
        cardStat.addView(PageKit.bodyText(ctx, "本次运行已处理红包数：" + RedPacketHook.handledCount()));
        root.addView(cardStat);
        root.addView(PageKit.divider(ctx));

        LinearLayout cardNote = PageKit.makeCard(ctx, d);
        cardNote.addView(PageKit.bodyText(ctx,
                "原理：Hook XML 解析层识别红包（wcpayinfo / type=2001），解析 nativeUrl 取 sendId 后，"
                        + "反射构造 NetSceneReceiveLuckyMoney 并经微信自身 doScene 后台领取（天然带签）。"
                        + "已内置随机延时、防重、频控、仅群聊等风控策略。"));
        root.addView(cardNote);
        return root;
    }
}
