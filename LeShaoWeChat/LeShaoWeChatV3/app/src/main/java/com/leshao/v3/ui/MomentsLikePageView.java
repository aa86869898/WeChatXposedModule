package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.leshao.v3.hook.MomentsAutoLikeHook;
import com.leshao.v3.ui.widgets.M3Page;

import java.util.Set;

/**
 * 朋友圈自动点赞功能页 —— 开关 + 点赞联系人选择。
 *
 * <p>在朋友圈页面右上角「⋮」菜单中提供「开始/停止自动点赞」，实现见 {@link MomentsAutoLikeHook}。</p>
 */
public final class MomentsLikePageView {

    private MomentsLikePageView() {}

    public static View create(Context ctx, Activity act) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout root = PageKit.pageRoot(ctx);

        root.addView(M3Page.section(ctx, "朋友圈自动点赞",
                "在朋友圈右上角注入「⋮」菜单，对指定联系人的动态自动点赞"));
        root.addView(M3Page.spacer(ctx, 2));

        LinearLayout cardSwitch = PageKit.makeCard(ctx, d);
        cardSwitch.addView(PageKit.switchRow(ctx, d, "开启朋友圈自动点赞",
                "开启后在朋友圈页面右上角显示「⋮ 自动点赞」菜单",
                MomentsAutoLikeHook.isEnabled(),
                (v, on) -> {
                    MomentsAutoLikeHook.setEnabled(on);
                    Toast.makeText(ctx, "朋友圈自动点赞已" + (on ? "开启" : "关闭")
                            + "（重新进入朋友圈生效）", Toast.LENGTH_SHORT).show();
                }, null));
        root.addView(cardSwitch);
        root.addView(PageKit.divider(ctx));

        final int count = MomentsAutoLikeHook.getSelectedCount();
        String sub = count == 0 ? "未选择（默认点赞全部好友）" : ("已选择 " + count + " 位联系人");
        LinearLayout cardContacts = PageKit.makeCard(ctx, d);
        cardContacts.addView(M3Page.clickRow(ctx, "👥", "点赞联系人", sub, () -> {
            StringBuilder cur = new StringBuilder();
            Set<String> sel = MomentsAutoLikeHook.getSelected();
            for (String w : sel) {
                if (cur.length() > 0) cur.append(',');
                cur.append(w);
            }
            ContactPickerDialog.show(act, cur.toString(), ContactPickerDialog.MODE_FRIEND,
                    (Set<String> wxids, String display) -> {
                        MomentsAutoLikeHook.setSelected(wxids);
                        Toast.makeText(ctx, "已选择 " + (wxids == null ? 0 : wxids.size())
                                + " 位联系人", Toast.LENGTH_SHORT).show();
                        SubPageActivity.refreshCurrent(act);
                    },
                    null, "选择点赞联系人");
        }));
        root.addView(cardContacts);
        root.addView(PageKit.divider(ctx));

        LinearLayout cardNote = PageKit.makeCard(ctx, d);
        cardNote.addView(PageKit.bodyText(ctx,
                "使用：进入朋友圈 → 点右上角「⋮ 自动点赞」→ 选择联系人 / 开始自动点赞。"
                        + "未选择联系人时默认点赞全部好友动态（自动跳过广告与已赞）。"
                        + "每条点赞间隔 3.5~6 秒以降低风控风险。"
                        + "原理：Hook ImproveSnsTimelineUI.onCreateOptionsMenu 注入菜单项，"
                        + "通过 lk4.g.W7 枚举时间线动态，调用 h6.p(wxid,5,null,SnsInfo,scene) 完成点赞。"));
        root.addView(cardNote);
        return root;
    }
}
