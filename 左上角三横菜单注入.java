/**
 * ============================================================
 * 左上角菜单 — 微信 Xposed 模块 (标准 Java, 兼容版)
 * ============================================================
 * 验证: 微信 8.0.76 (3141)
 * 依赖: 仅 de.robv.android.xposed (无第三方)
 * 兼容: Java 7+ / API 19+
 *
 * 集成方法 (handleLoadPackage 中):
 *   if (lpparam.packageName.equals("com.tencent.mm")) {
 *       CornerMenu.hook(lpparam.classLoader);
 *   }
 * ============================================================
 */
package com.your.module;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.view.View;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Toast;
import android.app.AlertDialog;
import android.content.DialogInterface;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class CornerMenu {

    private static final String HAMBURGER_TAG = "HAM_V1";
    private static final String[] MENU_ITEMS = {
        "📋 扫一扫",
        "💬 发起群聊",
        "➕ 添加朋友",
        "📁 我的收藏",
        "💳 我的钱包",
        "⚙️ 设置"
    };
    private static Bitmap sBitmapLight;
    private static Bitmap sBitmapDark;

    /**
     * 入口方法 — 在你的 handleLoadPackage 中调用
     * @param cl 微信的 ClassLoader (来自 lpparam.classLoader)
     */
    public static void hook(ClassLoader cl) {
        try {
            createBitmaps();

            Class<?> homeUIClass = XposedHelpers.findClass(
                "com.tencent.mm.ui.HomeUI", cl);
            Class<?> launcherUIClass = XposedHelpers.findClass(
                "com.tencent.mm.ui.LauncherUI", cl);

            // Hook 1: HomeUI.m() = initActionBar
            XposedHelpers.findAndHookMethod(homeUIClass, "m",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param)
                            throws Throwable {
                        inject(param.thisObject, cl);
                    }
                });

            // Hook 2: LauncherUI.onResume (兜底注入)
            XposedHelpers.findAndHookMethod(launcherUIClass, "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param)
                            throws Throwable {
                        Object homeUI = XposedHelpers.getObjectField(
                            param.thisObject, "i");
                        if (homeUI != null) {
                            inject(homeUI, cl);
                        }
                    }
                });

            XposedBridge.log("[CornerMenu] Hook 安装成功");

        } catch (Throwable e) {
            XposedBridge.log("[CornerMenu] 初始化失败: " + e.getMessage());
        }
    }

    /**
     * 用 Canvas 绘制三横线图标
     * 浅色模式: #333333  深色模式: #E0E0E0
     */
    private static void createBitmaps() {
        int size = 128;
        Paint paint = new Paint();
        paint.setStyle(Paint.Style.STROKE);
        paint.setAntiAlias(true);
        paint.setStrokeWidth(7.0f);
        // setStrokeCap 需要 API 29, 低版本用默认 BUTT 也可接受
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        // 浅色图标
        sBitmapLight = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(sBitmapLight);
        paint.setColor(0xFF333333);
        canvas.drawLine(19.2f, 12.8f, 108.8f, 12.8f, paint);
        canvas.drawLine(19.2f, 64.0f, 108.8f, 64.0f, paint);
        canvas.drawLine(19.2f, 115.2f, 108.8f, 115.2f, paint);

        // 深色图标
        sBitmapDark = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        canvas = new Canvas(sBitmapDark);
        paint.setColor(0xFFE0E0E0);
        canvas.drawLine(19.2f, 12.8f, 108.8f, 12.8f, paint);
        canvas.drawLine(19.2f, 64.0f, 108.8f, 64.0f, paint);
        canvas.drawLine(19.2f, 115.2f, 108.8f, 115.2f, paint);
    }

    /**
     * 核心注入逻辑
     * 调用链: HomeUI.c (ActionBar) → .j() (getCustomView)
     *        → RelativeLayout → addView(icon, 0) 注入到最左侧
     */
    private static void inject(final Object homeUI, final ClassLoader cl) {
        try {
            // [1] 获取 ActionBar: HomeUI.c 字段
            Object actionBar = XposedHelpers.getObjectField(homeUI, "c");
            if (actionBar == null) return;

            // [2] 获取 CustomView: ActionBar.j() 方法
            Object customView = XposedHelpers.callMethod(actionBar, "j");
            if (!(customView instanceof RelativeLayout)) return;
            final RelativeLayout layout = (RelativeLayout) customView;

            // [3] 防重复: 移除已有图标
            View existing = layout.findViewWithTag(HAMBURGER_TAG);
            if (existing != null) {
                layout.removeView(existing);
            }

            // [4] 暗黑模式检测: bk.C()
            boolean darkMode = false;
            try {
                Class<?> bkClass = XposedHelpers.findClass(
                    "com.tencent.mm.ui.bk", cl);
                darkMode = (boolean) XposedHelpers.callStaticMethod(
                    bkClass, "C");
            } catch (Throwable ignored) {
                // 检测失败则使用浅色模式
            }

            // [5] 创建图标 ImageView
            ImageView icon = new ImageView(layout.getContext());
            icon.setTag(HAMBURGER_TAG);
            icon.setImageBitmap(darkMode ? sBitmapDark : sBitmapLight);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setPadding(12, 0, 12, 0);
            icon.setClickable(true);
            icon.setFocusable(true);
            icon.setEnabled(true);

            // [6] 计算图标尺寸 (ActionBar 高度的 65%)
            int barHeight = layout.getHeight();
            if (barHeight <= 0) barHeight = 132;  // 默认约 66dp
            int iconSize = (int) (barHeight * 0.65);

            // [7] 设置布局参数: 贴左 + 垂直居中
            RelativeLayout.LayoutParams params =
                new RelativeLayout.LayoutParams(iconSize, iconSize);
            params.addRule(RelativeLayout.ALIGN_PARENT_START);
            params.addRule(RelativeLayout.CENTER_VERTICAL);
            icon.setLayoutParams(params);

            // [8] 点击弹出菜单 (使用匿名类, 兼容 Java 7)
            icon.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new AlertDialog.Builder(v.getContext())
                        .setTitle("快捷菜单")
                        .setItems(MENU_ITEMS,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    Toast.makeText(v.getContext(),
                                        MENU_ITEMS[w],
                                        Toast.LENGTH_SHORT).show();
                                }
                            })
                        .show();
                }
            });

            // [9] 注入到 position=0 (绝对左上角)
            layout.addView(icon, 0);
            icon.bringToFront();  // 确保可点击

        } catch (Throwable e) {
            XposedBridge.log("[CornerMenu] 注入异常: " + e.getMessage());
        }
    }
}
