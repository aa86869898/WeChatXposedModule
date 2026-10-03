package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.hook.FakeLocationHook;
import com.leshao.v3.ui.widgets.M3Page;

/**
 * 定位伪装功能页 —— 开启开关 + 经纬度/坐标系配置。
 *
 * <p>Hook 点见 {@link FakeLocationHook}（依据《伪装定位WeChat_FakeLocation_Analysis.md》）。</p>
 */
public final class FakeLocationPageView {

    private FakeLocationPageView() {}

    public static View create(Context ctx, Activity act) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout root = PageKit.pageRoot(ctx);

        root.addView(M3Page.section(ctx, "定位伪装",
                "把微信获取到的位置统一替换为指定坐标（附近的人 / 地图 / 小程序等）"));
        root.addView(M3Page.spacer(ctx, 2));

        // 开关
        LinearLayout cardSwitch = PageKit.makeCard(ctx, d);
        cardSwitch.addView(PageKit.switchRow(ctx, d, "开启定位伪装",
                "开启后微信定位结果统一替换为你配置的坐标",
                FakeLocationHook.isEnabled(),
                (v, on) -> {
                    FakeLocationHook.setEnabled(on);
                    Toast.makeText(ctx, "定位伪装已" + (on ? "开启" : "关闭")
                            + "（重启微信后完全生效）", Toast.LENGTH_SHORT).show();
                }, null));
        root.addView(cardSwitch);
        root.addView(PageKit.divider(ctx));

        // 坐标配置
        LinearLayout cardCfg = PageKit.makeCard(ctx, d);
        cardCfg.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));

        cardCfg.addView(M3Page.fieldLabel(ctx, "纬度 (latitude)"));
        final EditText latBox = M3Page.input(ctx, "例如 39.909604");
        latBox.setText(String.valueOf(FakeLocationHook.getLat()));
        cardCfg.addView(latBox);

        cardCfg.addView(M3Page.fieldLabel(ctx, "经度 (longitude)"));
        final EditText lngBox = M3Page.input(ctx, "例如 116.397228");
        lngBox.setText(String.valueOf(FakeLocationHook.getLng()));
        cardCfg.addView(lngBox);

        cardCfg.addView(M3Page.fieldLabel(ctx, "坐标系"));
        final String[] coordHolder = { FakeLocationHook.getCoord() };
        final TextView[] chips = new TextView[2];
        LinearLayout coordRow = new LinearLayout(ctx);
        coordRow.setOrientation(LinearLayout.HORIZONTAL);
        coordRow.setPadding(0, (int) (2 * d), 0, (int) (2 * d));
        chips[0] = buildChip(ctx, d, "GCJ-02（火星坐标）");
        chips[1] = buildChip(ctx, d, "WGS-84（原始 GPS）");
        coordRow.addView(chips[0], new LinearLayout.LayoutParams(0, -2, 1f));
        View gap = new View(ctx);
        gap.setLayoutParams(new LinearLayout.LayoutParams((int) (8 * d), 0));
        coordRow.addView(gap);
        coordRow.addView(chips[1], new LinearLayout.LayoutParams(0, -2, 1f));
        cardCfg.addView(coordRow);
        applyChipState(chips, coordHolder[0]);
        chips[0].setOnClickListener(v -> {
            coordHolder[0] = "gcj02";
            applyChipState(chips, "gcj02");
        });
        chips[1].setOnClickListener(v -> {
            coordHolder[0] = "wgs84";
            applyChipState(chips, "wgs84");
        });

        root.addView(cardCfg);
        root.addView(PageKit.divider(ctx));

        // 保存
        LinearLayout cardSave = PageKit.makeCard(ctx, d);
        cardSave.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));
        cardSave.addView(M3Page.button(ctx, "保存坐标", () -> {
            String lat = latBox.getText().toString().trim();
            String lng = lngBox.getText().toString().trim();
            if (!isValid(lat) || !isValid(lng)) {
                Toast.makeText(ctx, "请输入合法的经纬度数字", Toast.LENGTH_SHORT).show();
                return;
            }
            FakeLocationHook.setConfig(lat, lng, coordHolder[0]);
            Toast.makeText(ctx, "已保存，重启微信后完全生效", Toast.LENGTH_SHORT).show();
        }));
        root.addView(cardSave);
        root.addView(PageKit.divider(ctx));

        LinearLayout cardNote = PageKit.makeCard(ctx, d);
        cardNote.addView(PageKit.bodyText(ctx,
                "原理：Hook 微信位置总分发层 h51.h.c、附近的人请求层 ct3.f、新版位置模型 mf.e.d "
                        + "以及地图蓝点 getMyLocation，将经纬度替换为指定坐标。"
                        + "GCJ-02（高德/腾讯地图）与 WGS-84（原始 GPS）会自动互转，"
                        + "“附近的人”默认使用 WGS-84。修改后需重启微信生效。"));
        root.addView(cardNote);
        return root;
    }

    private static boolean isValid(String s) {
        if (s == null || s.isEmpty()) return false;
        try {
            Double.parseDouble(s);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static TextView buildChip(Context ctx, float d, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding((int) (8 * d), (int) (10 * d), (int) (8 * d), (int) (10 * d));
        tv.setTypeface(null, Typeface.BOLD);
        CandyUi.ripple(tv, AppColors.SHAPE_FULL_DP);
        return tv;
    }

    private static void applyChipState(TextView[] chips, String coord) {
        boolean gcj = !"wgs84".equalsIgnoreCase(coord);
        styleChip(chips[0], gcj);
        styleChip(chips[1], !gcj);
    }

    private static void styleChip(TextView tv, boolean selected) {
        if (selected) {
            tv.setBackground(CandyUi.gradientBgStatic(tv.getContext(), AppColors.SHAPE_FULL_DP));
            tv.setTextColor(AppColors.whiteTextOnAccent());
        } else {
            tv.setBackground(CandyUi.rowBg(tv.getContext()));
            tv.setTextColor(AppColors.text2());
        }
    }
}
