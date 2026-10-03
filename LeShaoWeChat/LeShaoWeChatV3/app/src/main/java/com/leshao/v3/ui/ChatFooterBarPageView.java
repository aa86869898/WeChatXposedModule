package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.leshao.v3.hook.ChatFooterBarHook;
import com.leshao.v3.ui.widgets.M3Page;

/** 聊天窗口输入框上方快捷按钮功能页（文档《WeChat_ChatFooter_ButtonBar.md》）。 */
public final class ChatFooterBarPageView {

    private ChatFooterBarPageView() {}

    public static View create(Context ctx, Activity act) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout root = PageKit.pageRoot(ctx);

        // v1145: 页面顶部统一分区标题
        root.addView(M3Page.section(ctx, "输入框快捷按钮",
                "聊天输入框上方常驻一排快捷按钮"));
        root.addView(M3Page.spacer(ctx, 2));

        LinearLayout cardSwitch = PageKit.makeCard(ctx, d);
        cardSwitch.addView(PageKit.switchRow(ctx, d, "输入框快捷按钮",
                "在聊天输入框上方常驻一排快捷按钮（音色/群发/语音/AI助手/转发），无背景",
                ChatFooterBarHook.isEnabled(),
                (v, on) -> {
                    ChatFooterBarHook.setEnabled(on);
                    Toast.makeText(ctx, "输入框快捷按钮已" + (on ? "开启" : "关闭")
                            + "（重启微信后完全生效）", Toast.LENGTH_SHORT).show();
                }, null));
        root.addView(cardSwitch);
        root.addView(PageKit.divider(ctx));

        LinearLayout cardNote = PageKit.makeCard(ctx, d);
        cardNote.addView(PageKit.bodyText(ctx,
                "原理：Hook ChatFooter 构造器/onResume，在输入栏父容器中、ChatFooter 正上方插入一排快捷按钮。"
                        + "不依赖混淆布局资源，单聊/群聊/公众号等所有聊天窗口通用。"));
        root.addView(cardNote);
        return root;
    }
}
