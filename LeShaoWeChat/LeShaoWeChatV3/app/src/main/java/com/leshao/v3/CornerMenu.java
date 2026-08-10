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
package com.leshao.v3;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;

import com.leshao.v3.ui.AppColors;
import com.leshao.v3.wm.hook.WmHomeHook;

import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Process;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.Method;

public class CornerMenu {

    private static final String TAG = "CornerMenu";
    private static final String HAMBURGER_TAG = "HAM_V1";
    private static ClassLoader sClassLoader;
    private static Bitmap sBitmapLight;
    private static Bitmap sBitmapDark;

    private static int dp(Context ctx, float dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 入口方法 — 在你的 handleLoadPackage 中调用
     * @param cl 微信的 ClassLoader (来自 lpparam.classLoader)
     */
    public static void hook(ClassLoader cl) {
        try {
            sClassLoader = cl;
            LogWriter.log(TAG, "hook: start");
            createBitmaps();
            LogWriter.log(TAG, "hook: bitmaps created");

            Class<?> homeUIClass = XposedHelpers.findClass(
                "com.tencent.mm.ui.HomeUI", cl);
            LogWriter.log(TAG, "hook: HomeUI found");
            Class<?> launcherUIClass = XposedHelpers.findClass(
                "com.tencent.mm.ui.LauncherUI", cl);
            LogWriter.log(TAG, "hook: LauncherUI found");

            // Hook 1: HomeUI.m() = initActionBar
            Method methodM = homeUIClass.getDeclaredMethod("m");
            XposedBridge.hookMethod(methodM,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param)
                            throws Throwable {
                        inject(param.thisObject, cl);
                    }
                });
            LogWriter.log(TAG, "hook: HomeUI.m() hooked");

            // Hook 2: LauncherUI.onResume (兜底注入)
            Method methodOnResume = launcherUIClass.getDeclaredMethod("onResume");
            XposedBridge.hookMethod(methodOnResume,
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
            LogWriter.log(TAG, "hook: LauncherUI.onResume hooked OK");

        } catch (Throwable e) {
            LogWriter.log(TAG, "hook: FAILED - " + e.getClass().getSimpleName()
                + ": " + e.getMessage());
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

            boolean cornerMenuOn = layout.getContext().getSharedPreferences("wm_prefs", 0)
                    .getBoolean("corner_menu", true);
            if (!cornerMenuOn) return;

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

            // [8] 点击弹出菜单
            icon.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final Context ctx = v.getContext();
                    final Activity act = (ctx instanceof Activity) ? (Activity) ctx : null;

                    java.util.List<String> items = new java.util.ArrayList<>();
                    java.util.List<Runnable> actions = new java.util.ArrayList<>();

                    android.content.SharedPreferences sp = ctx.getSharedPreferences("wm_prefs", 0);

                    if (sp.getBoolean("batch_send", true)) {
                        items.add("乐少万群定时群发");
                        actions.add(() -> { if (act != null) com.leshao.v3.wm.hook.WmChatHook.showMassSendFromCorner(act, sClassLoader); });
                    }
                    if (sp.getBoolean("group_list", true)) {
                        items.add("所有群列表");
                        actions.add(() -> { if (act != null) WmHomeHook.allGroups(act, sClassLoader); });
                    }
                    if (sp.getBoolean("quick_scan", true)) {
                        items.add("快捷扫码");
                        actions.add(() -> { if (act != null) WmHomeHook.quickScan(act); });
                    }
                    if (sp.getBoolean("scheduled_moment", false)) {
                        items.add("朋友圈定时");
                        actions.add(() -> { if (act != null) WmHomeHook.scheduledMoment(act); });
                    }
                    if (sp.getBoolean("file_helper", true)) {
                        items.add("文件助手");
                        actions.add(() -> { if (act != null) WmHomeHook.fileHelper(act); });
                    }
                    items.add("一键免打扰");
                    actions.add(() -> ChatRoomMuteHelper.muteAllAsync(sClassLoader, ctx));
                    items.add("取消免打扰");
                    actions.add(() -> ChatRoomMuteHelper.unmuteAllAsync(sClassLoader, ctx));

                    if (items.isEmpty()) return;

                    int userId = Process.myUid() / 100000;
                    String title = userId == 0 ? "快捷菜单" : ("快捷菜单【分身user" + userId + "】");

                    String[] menuArr = items.toArray(new String[0]);
                    AlertDialog dialog = new AlertDialog.Builder(ctx)
                        .setTitle(title)
                        .setItems(menuArr,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    actions.get(w).run();
                                }
                            })
                        .create();

                    dialog.show();

                    // 暗色模式适配
                    Window window = dialog.getWindow();
                    if (window != null) {
                        int cardBg = AppColors.card();
                        int textColor = AppColors.text1();
                        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(cardBg));
                        window.setDimAmount(0.3f);

                        // 自定义列表项适配器 (暗色模式文字颜色)
                        ListView listView = dialog.getListView();
                        if (listView != null) {
                            listView.setBackgroundColor(cardBg);
                            listView.setDivider(new android.graphics.drawable.ColorDrawable(AppColors.divider()));
                            listView.setDividerHeight(1);
                            listView.setAdapter(new ArrayAdapter<String>(ctx,
                                    android.R.layout.simple_list_item_1, menuArr) {
                                @Override
                                public View getView(int pos, View convertView, ViewGroup parent) {
                                    TextView tv = (TextView) super.getView(pos, convertView, parent);
                                    tv.setTextColor(textColor);
                                    return tv;
                                }
                            });
                        }

                        // 自定义标题颜色
                        try {
                            int titleId = ctx.getResources().getIdentifier("alertTitle", "id", "android");
                            TextView titleView = dialog.findViewById(titleId);
                            if (titleView != null) {
                                titleView.setTextColor(textColor);
                            }
                        } catch (Throwable ignored) {}

                        // 定位到按钮紧下方 (减去按钮底部 padding 减小间距)
                        WindowManager.LayoutParams lp = window.getAttributes();
                        lp.width = dp(ctx, 180);
                        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                        lp.gravity = Gravity.TOP | Gravity.START;

                        int[] loc = new int[2];
                        v.getLocationOnScreen(loc);
                        lp.x = loc[0];
                        lp.y = loc[1] + v.getHeight() - dp(ctx, 8);
                        window.setAttributes(lp);
                    }
                }
            });

            // [9] 注入到 position=0 (绝对左上角)
            layout.addView(icon, 0);
            icon.bringToFront();  // 确保可点击

        } catch (Throwable e) {
            LogWriter.log(TAG, "inject: FAILED - " + e.getClass().getSimpleName()
                + ": " + e.getMessage());
        }
    }
}
