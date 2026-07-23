package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.hook.AutoCollectHook;

import java.util.HashSet;
import java.util.Set;

public class AutoCollectPageView {

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.bg());

        root.addView(MainActivity.makeDivider(ctx));

        root.addView(makeMenuEntry(ctx, d, 0x1F4B0, "自动收款",
            "配置自动收款功能，所有功能与红包一致",
            v -> SubPageActivity.open(parentAct, "自动收款", 92)));

        root.addView(spacerV(ctx, d, 24));

        return root;
    }

    private static View makeMenuEntry(Context ctx, float d, int emoji, String title,
                                       String desc, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(18 * d), (int)(14 * d), (int)(18 * d), (int)(14 * d));
        row.setBackgroundColor(AppColors.whiteCard());
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
        tv.setTextColor(AppColors.text1());
        tv.setTypeface(null, Typeface.BOLD);
        textCol.addView(tv);

        if (desc != null && !desc.isEmpty()) {
            TextView dv = new TextView(ctx);
            dv.setText(desc);
            dv.setTextSize(12);
            dv.setTextColor(AppColors.text2());
            dv.setPadding(0, (int)(3 * d), 0, 0);
            textCol.addView(dv);
        }

        row.addView(textCol);

        TextView arrow = new TextView(ctx);
        arrow.setText(">");
        arrow.setTextSize(16);
        arrow.setTextColor(AppColors.text2());
        row.addView(arrow);

        return row;
    }

    private static View spacerV(Context ctx, float d, int dp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(dp * d)));
        return v;
    }
}
