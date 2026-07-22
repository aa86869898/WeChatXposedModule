package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.hook.RedPacketHook;

public class RedPacketPageView {

    private static final int CLR_CARD    = 0xB8FFFFFF;
    private static final int CLR_WHITE   = 0xFFFFFFFF;
    private static final int CLR_TEXT    = 0xFF281838;
    private static final int CLR_TEXT2   = 0xFF786890;
    private static final int CLR_BG      = 0xFFF4F0FF;

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CLR_BG);

        // 与主页一致的分割线和菜单项
        root.addView(MainActivity.makeDivider(ctx));

        // 自动秒抢红包 > — 类似主页菜单项
        root.addView(makeMenuEntry(ctx, d, 0x1F4B0, "自动秒抢红包",
            "点击进入配置红包自动领取功能",
            v -> SubPageActivity.open(parentAct, "自动秒抢红包", 91)));

        root.addView(spacerV(ctx, d, 24));

        return root;
    }

    private static View makeMenuEntry(Context ctx, float d, int emoji, String title,
                                       String desc, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(18 * d), (int)(14 * d), (int)(18 * d), (int)(14 * d));
        row.setBackgroundColor(CLR_WHITE);
        row.setOnClickListener(onClick);

        TextView icon = new TextView(ctx);
        icon.setText(new String(Character.toChars(emoji)));
        icon.setTextSize(22);
        icon.setPadding(0, 0, (int)(14 * d), 0);
        row.addView(icon);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(15);
        tv.setTextColor(CLR_TEXT);
        tv.setTypeface(null, Typeface.BOLD);
        textCol.addView(tv);

        if (desc != null && !desc.isEmpty()) {
            TextView dv = new TextView(ctx);
            dv.setText(desc);
            dv.setTextSize(12);
            dv.setTextColor(CLR_TEXT2);
            dv.setPadding(0, (int)(3 * d), 0, 0);
            textCol.addView(dv);
        }

        row.addView(textCol);

        TextView arrow = new TextView(ctx);
        arrow.setText(">");
        arrow.setTextSize(16);
        arrow.setTextColor(CLR_TEXT2);
        row.addView(arrow);

        return row;
    }

    private static View spacerV(Context ctx, float d, int dp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(dp * d)));
        return v;
    }
}
