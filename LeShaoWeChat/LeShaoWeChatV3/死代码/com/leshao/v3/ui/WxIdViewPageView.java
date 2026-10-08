package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.leshao.v3.hook.WeChatIdInjectHook;
import com.leshao.v3.ui.widgets.M3Page;

/**
 * v3.0.163：「查看微信wxid」详情页（更多功能 → 查看微信wxid）。
 *
 * <p>展示功能开关、离线库统计与使用说明（严格移植 WeChatIDInject.zip 的数据源：
 * 当场联系人 field_alias + 群成员 wxid 全量枚举 + 报文 dump 离线库）。</p>
 */
public final class WxIdViewPageView {

    private WxIdViewPageView() {}

    public static android.view.View create(Context ctx, Activity act) {
        LinearLayout root = M3Page.root(ctx);

        root.addView(M3Page.section(ctx, "查看微信wxid",
                "联系人/群成员资料页注入「微信号 / ID」行（移植 WeChatIDInject.zip）"));
        root.addView(M3Page.spacer(ctx, 2));

        LinearLayout card = M3Page.card(ctx);
        M3Page.appendSwitchRow(card, ctx, "🔍", "启用在资料页显示",
                "开启后打开联系人/群成员资料页，在「微信号」下方注入一行",
                WeChatIdInjectHook.isEnabled(), (v, on) -> {
                    WeChatIdInjectHook.setEnabled(on);
                    M3Page.toastSuccess(ctx, "已" + (on ? "开启" : "关闭"));
                });
        card.addView(M3Page.divider(ctx));

        // 离线库统计
        M3Page.appendClickRow(card, ctx, "🗂", "离线库统计",
                "wxid_alias.tsv / wxid_known.tsv（/sdcard/Download/）\n"
                        + "已收集 " + WeChatIdInjectHook.size() + " 条微信号映射",
                () -> M3Page.toast(ctx, "已收集映射 " + WeChatIdInjectHook.size() + " 条"));
        root.addView(card);
        root.addView(M3Page.spacer(ctx, 2));

        LinearLayout note = M3Page.card(ctx);
        TextView tv = new TextView(ctx);
        tv.setTextSize(13);
        tv.setLineSpacing(4, 1f);
        tv.setTextColor(com.leshao.v3.ui.AppColors.onSurfaceVariant());
        tv.setText("原理：\n"
                + "1. 当场联系人：资料页读取 field_alias / field_username 并落库；\n"
                + "2. 群成员枚举：群成员详情网络响应里复用微信自身解析，全量收集成员 wxid；\n"
                + "3. 报文 dump：verifyuser / searchcontact 等响应对近配对挖 alias；\n"
                + "4. UI 注入：在资料页「微信号/WeChat」行下方插入摘要行。\n\n"
                + "显示值优先级：离线库命中 > 当场 alias > username。\n"
                + "陌生人微信号受服务端硬限制，稳定产出为 wxid（属预期）。");
        note.addView(tv);
        root.addView(note);

        return root;
    }
}