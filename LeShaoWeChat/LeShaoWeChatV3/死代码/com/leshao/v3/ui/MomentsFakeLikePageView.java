package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.leshao.v3.hook.MomentsFakeLikeHook;
import com.leshao.v3.ui.widgets.M3Page;

/**
 * 朋友圈秒集赞功能页 —— 开关 + 默认伪造点赞数。
 *
 * <p>使用：开启后长按朋友圈任意动态 → 菜单中出现「秒集赞」→ 随机好友 / 自选联系人，
 * 立即在本地伪造指定数量的点赞（仅本机 UI 生效，服务器不认，别人看不到）。</p>
 */
public final class MomentsFakeLikePageView {

    private MomentsFakeLikePageView() {}

    public static View create(Context ctx, Activity act) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout root = PageKit.pageRoot(ctx);

        root.addView(M3Page.section(ctx, "朋友圈秒集赞",
                "长按朋友圈任意动态，立即伪造指定数量的点赞"));
        root.addView(M3Page.spacer(ctx, 2));

        LinearLayout cardSwitch = PageKit.makeCard(ctx, d);
        cardSwitch.addView(PageKit.switchRow(ctx, d, "开启朋友圈秒集赞",
                "开启后长按朋友圈动态出现「秒集赞」菜单",
                MomentsFakeLikeHook.isEnabled(),
                (v, on) -> {
                    MomentsFakeLikeHook.setEnabled(on);
                    Toast.makeText(ctx, "朋友圈秒集赞已" + (on ? "开启" : "关闭")
                            + "（重新进入朋友圈生效）", Toast.LENGTH_SHORT).show();
                }, null));
        root.addView(cardSwitch);
        root.addView(PageKit.divider(ctx));

        LinearLayout cardCount = PageKit.makeCard(ctx, d);
        cardCount.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));
        cardCount.addView(M3Page.fieldLabel(ctx, "随机集赞人数（1~50）"));
        final EditText countBox = M3Page.input(ctx, "例如 10");
        countBox.setText(String.valueOf(MomentsFakeLikeHook.getCount()));
        cardCount.addView(countBox);
        cardCount.addView(M3Page.spacer(ctx, 1));
        cardCount.addView(M3Page.button(ctx, "保存人数", () -> {
            String s = countBox.getText().toString().trim();
            int n;
            try {
                n = Integer.parseInt(s);
            } catch (Throwable t) {
                Toast.makeText(ctx, "请输入 1~50 的整数", Toast.LENGTH_SHORT).show();
                return;
            }
            MomentsFakeLikeHook.setCount(n);
            Toast.makeText(ctx, "已保存，随机集赞人数 = " + MomentsFakeLikeHook.getCount(),
                    Toast.LENGTH_SHORT).show();
        }));
        root.addView(cardCount);
        root.addView(PageKit.divider(ctx));

        LinearLayout cardNote = PageKit.makeCard(ctx, d);
        cardNote.addView(PageKit.bodyText(ctx,
                "使用：开启后进入朋友圈 → 长按任意动态 → 「秒集赞」→ 随机好友或自选联系人。"
                        + "点赞立即显示在该动态的点赞列表（昵称 + 计数），仅本机 UI 生效，"
                        + "服务器不认、别人看不到。数据写入本地 SnsInfo 的 attrBuf 并落库，"
                        + "重进朋友圈依然可见。"));
        root.addView(cardNote);
        return root;
    }
}