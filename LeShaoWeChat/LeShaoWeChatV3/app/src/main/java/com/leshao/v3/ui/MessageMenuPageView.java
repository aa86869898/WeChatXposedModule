package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.leshao.v3.hook.MessageMenuHook;
import com.leshao.v3.ui.widgets.M3Page;
import com.leshao.v3.wm.utils.WmPrefs;

import java.lang.ref.WeakReference;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 「去你妈的消息长按菜单」配置页。
 * 列出微信聊天长按消息时出现过的原生按钮（预置 + 自动收录），打开开关即从菜单移除。
 * 数据存于 WmPrefs：msg_menu_enabled / msg_menu_hidden / msg_menu_observed。
 */
public class MessageMenuPageView {

    /** 弱引用当前列表容器，供 hook 收录新按钮时就地刷新，避免持有 Activity */
    private static volatile WeakReference<LinearLayout> sListRef = new WeakReference<>(null);

    public static View create(Context ctx, Activity parentAct) {
        WmPrefs.ensureInit();

        LinearLayout root = M3Page.root(ctx);

        root.addView(M3Page.section(ctx, "消息长按菜单净化",
                "去掉微信原生按钮，仅作用于聊天窗口长按消息"));

        LinearLayout headCard = M3Page.card(ctx);
        M3Page.appendSwitchRow(headCard, ctx, "\uD83E\uDDF9", "启用净化",
                "关闭后不做任何移除，但会继续收录按钮", WmPrefs.isMsgMenuEnabled(),
                (btn, on) -> WmPrefs.setMsgMenuEnabled(on));
        root.addView(headCard);

        root.addView(M3Page.section(ctx, "原生按钮",
                "开启 = 从长按菜单移除；未列出的去聊天里长按一次即自动收录"));

        final LinearLayout listCard = M3Page.card(ctx);
        rebuildList(listCard);
        sListRef = new WeakReference<>(listCard);

        MessageMenuHook.setObserver(new Runnable() {
            @Override public void run() {
                LinearLayout c = sListRef.get();
                if (c != null) rebuildList(c);
            }
        });

        root.addView(listCard);

        LinearLayout actionCard = M3Page.card(ctx);
        actionCard.addView(M3Page.ghostButton(ctx, "清空全部隐藏项", new Runnable() {
            @Override public void run() {
                WmPrefs.setMsgMenuHidden("");
                LinearLayout c = sListRef.get();
                if (c != null) rebuildList(c);
                M3Page.toast(ctx, "已清空隐藏项");
            }
        }));
        root.addView(actionCard);

        return M3Page.scroll(ctx, root);
    }

    private static void rebuildList(LinearLayout card) {
        card.removeAllViews();
        Context ctx = card.getContext();
        WmPrefs.ensureInit();

        Set<String> observed = MessageMenuHook.parseSet(WmPrefs.getMsgMenuObserved());
        final Set<String> hidden = MessageMenuHook.parseSet(WmPrefs.getMsgMenuHidden());

        LinkedHashSet<String> all = new LinkedHashSet<>();
        for (String p : MessageMenuHook.PRESET) all.add(p);
        all.addAll(observed);

        boolean first = true;
        for (final String title : all) {
            if (!first) card.addView(M3Page.divider(ctx));
            first = false;
            M3Page.appendSwitchRow(card, ctx, null, title, null, hidden.contains(title),
                    (btn, on) -> {
                        Set<String> h = MessageMenuHook.parseSet(WmPrefs.getMsgMenuHidden());
                        if (on) h.add(title);
                        else h.remove(title);
                        WmPrefs.setMsgMenuHidden(MessageMenuHook.join(h));
                    });
        }

        if (all.isEmpty()) {
            TextView tv = new TextView(ctx);
            tv.setText("暂无按钮，去聊天里长按一条消息试试");
            tv.setTextSize(14);
            tv.setTextColor(AppColors.onSurfaceVariant());
            int p = (int) (16 * ctx.getResources().getDisplayMetrics().density + 0.5f);
            tv.setPadding(p, p, p, p);
            card.addView(tv);
        }
    }
}
