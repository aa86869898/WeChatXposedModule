/**
 * ============================================================
 * 左上角三横菜单 — 微信 Xposed 模块 (WindowManager 悬浮窗方式)
 * ============================================================
 * 依赖: 仅 de.robv.android.xposed (无第三方)
 * 兼容: Java 7+ / API 19+
 *
 * 采用 WindowManager.TYPE_APPLICATION_PANEL 悬浮窗方式注入，
 * 不依赖微信 ActionBar 内部布局，确保图标始终可见。
 * 仅在主页 (LauncherUI/HomeUI) 注入左上角"三横"按钮，点击弹出快捷菜单。
 * 聊天窗口 (ChattingUI) 不注入任何元素，避免遮挡微信自带的右上角三点菜单
 * (导出聊天记录等功能)。
 * ============================================================
 */
package com.leshao.v3;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.leshao.v3.hook.VersionCompat;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.wm.hook.WmHomeHook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class CornerMenu {
    private static final String TAG = "CornerMenu";
    private static final String HAMBURGER_TAG = "LESHAO_HAM_V2";
    private static final int MAX_RETRY = 10;
    private static final long RETRY_DELAY_MS = 200;
    private static ClassLoader sClassLoader;
    private static Bitmap sBitmapLight;
    private static Bitmap sBitmapDark;

    private static View sMainIcon;
    private static WindowManager sMainWM;
    private static final Handler sH = new Handler(Looper.getMainLooper());

    // 聊天窗口精确标志位: 由 ChattingUIFragment.onHiddenChanged 驱动,
    // 比遍历 fragment 检查 view 状态更可靠 (回主页后 view 状态可能未同步/多实例误判)
    private static volatile boolean sChatWindowActive;

    private static int dp(Context ctx, float dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 入口方法 — 在 handleLoadPackage 中调用
     */
    public static void hook(ClassLoader cl) {
        try {
            ClassLoader tkCL = VersionCompat.findTinkerClassLoader(cl);
            if (tkCL != null) {
                sClassLoader = tkCL;
                LogWriter.log(TAG, "hook: using Tinker ClassLoader");
            } else {
                sClassLoader = cl;
            }
            LogWriter.log(TAG, "hook: start");
            createBitmaps();
            LogWriter.log(TAG, "hook: bitmaps created");

            XposedBridge.hookAllMethods(Activity.class, "onWindowFocusChanged",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object activity = param.thisObject;
                        if (!(activity instanceof Activity)) return;
                        boolean focused = (boolean) param.args[0];
                        String clsName = activity.getClass().getName();
                        try {
                            if (focused && ("com.tencent.mm.ui.LauncherUI".equals(clsName)
                                    || "com.tencent.mm.ui.HomeUI".equals(clsName))) {
                                // 8.0.49+: 聊天窗口是 LauncherUI 内的 ChattingUIFragment，
                                // 必须排除，否则聊天界面也注入三横菜单
                                if (isInChatWindow((Activity) activity)) {
                                    LogWriter.log(TAG, "skip inject: chat window active");
                                    if (sMainIcon != null) removeAll();
                                    return;
                                }
                                LogWriter.log(TAG, "Activity.onWindowFocusChanged -> main page focused");
                                injectMain((Activity) activity, 0);
                            }
                        } catch (Throwable e) {
                            LogWriter.log(TAG, "Activity.onWindowFocusChanged cb err: " + e);
                        }
                    }
                });
            LogWriter.log(TAG, "hook: Activity.onWindowFocusChanged hooked");
            XposedBridge.hookAllMethods(Activity.class, "onPause",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            sChatWindowActive = false;
                            if (sMainIcon != null) {
                                LogWriter.log(TAG, "Activity.onPause -> remove hamburger");
                                removeAll();
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            LogWriter.log(TAG, "hook: Activity.onPause hooked");

            // 聊天窗口精确可见性标志: onHiddenChanged 驱动。
            // 修复回主页后 isInChatWindow 误判导致三横菜单不注入的问题。
            hookChatFragmentVisibility(cl);
            LogWriter.log(TAG, "hook: ChattingUIFragment.onHiddenChanged hooked");
        } catch (Throwable e) {
            LogWriter.log(TAG, "hook: FAILED - " + e.getClass().getSimpleName()
                + ": " + e.getMessage());
        }
    }

    /** 绘制三横 (≡) 菜单图标 */
    private static void createBitmaps() {
        int size = 128;
        Paint paint = new Paint();
        paint.setStyle(Paint.Style.STROKE);
        paint.setAntiAlias(true);
        paint.setStrokeWidth(9.0f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        sBitmapLight = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(sBitmapLight);
        paint.setColor(0xFF333333);
        drawLines(canvas, paint);

        sBitmapDark = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        canvas = new Canvas(sBitmapDark);
        paint.setColor(0xFFE0E0E0);
        drawLines(canvas, paint);
    }

    private static void drawLines(Canvas canvas, Paint paint) {
        canvas.drawLine(20f, 36f, 108f, 36f, paint);
        canvas.drawLine(20f, 64f, 108f, 64f, paint);
        canvas.drawLine(20f, 92f, 108f, 92f, paint);
    }

    /**
     * 判断当前 LauncherUI 是否处于聊天窗口(8.0.49+ 聊天是内部 fragment)。
     * 聊天中则不注入三横菜单。
     */
    private static boolean isInChatWindow(Activity act) {
        // 优先使用 onHiddenChanged 驱动的精确标志位:
        // 回主页后 fragment view 可能仍短暂标记可见或存在多个实例,
        // 遍历检查不可靠, 导致三横菜单偶发不显示。
        if (sChatWindowActive) return true;
        try {
            Object fm = XposedHelpers.callMethod(act, "getSupportFragmentManager");
            if (fm == null) return false;
            java.util.List fragments = (java.util.List) XposedHelpers.callMethod(fm, "getFragments");
            if (fragments == null) return false;
            for (Object f : fragments) {
                if (!"com.tencent.mm.ui.chatting.ChattingUIFragment".equals(f.getClass().getName())) continue;
                // 仅当 fragment 视图真正显示在屏幕上才算聊天中;
                // 回主页后残留的隐藏/未附加 fragment 不算 (修复回主页后三横菜单不再注入的问题)
                try {
                    Object h = XposedHelpers.callMethod(f, "isHidden");
                    if (h instanceof Boolean && (Boolean) h) continue;
                } catch (Throwable ignored) {}
                try {
                    Object v = XposedHelpers.callMethod(f, "isVisible");
                    if (v instanceof Boolean && (Boolean) v) return true;
                } catch (Throwable ignored) {}
                try {
                    Object viewObj = XposedHelpers.getObjectField(f, "mView");
                    if (viewObj instanceof View && ((View) viewObj).isShown()) return true;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 聊天窗口精确可见性: hook ChattingUIFragment.onHiddenChanged 维护标志位。
     *  同时拦截回主页瞬间(chat hidden)立即恢复注入, 修复偶发不显示。 */
    private static void hookChatFragmentVisibility(ClassLoader cl) {
        try {
            Class<?> fragCls = XposedHelpers.findClass(
                "com.tencent.mm.ui.chatting.ChattingUIFragment", cl);
            XposedBridge.hookAllMethods(fragCls, "onHiddenChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        boolean hidden = (Boolean) param.args[0];
                        sChatWindowActive = !hidden;
                        if (hidden) {
                            // 回主页: 立即尝试恢复三横菜单(不依赖下次 onWindowFocusChanged)
                            final Object frag = param.thisObject;
                            sH.postDelayed(() -> {
                                try {
                                    if (frag == null || sChatWindowActive) return;
                                    Activity act = (Activity) XposedHelpers.callMethod(frag, "getActivity");
                                    if (act == null) return;
                                    String clsName = act.getClass().getName();
                                    if (!"com.tencent.mm.ui.LauncherUI".equals(clsName)
                                            && !"com.tencent.mm.ui.HomeUI".equals(clsName)) return;
                                    if (sMainIcon != null) return;
                                    injectMain(act, 0);
                                } catch (Throwable ignored) {}
                            }, 100);
                        }
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "onHiddenChanged cb err: " + e);
                    }
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookChatFragmentVisibility err: " + t.getMessage());
        }
    }

    private static boolean isEnabled(Context ctx) {
        try {
            return UnifiedPrefs.get(ctx, "wm_prefs").getBoolean("corner_menu", true);
        } catch (Throwable ignored) { return true; }
    }

    private static boolean darkMode(Context ctx) {
        try {
            ClassLoader cl = sClassLoader;
            // Dynamic discovery: find class with boolean C() method
            Class<?> bkClass = VersionCompat.findClassMulti(cl,
                "com.tencent.mm.ui.bk", "com.tencent.mm.ui.bl",
                "com.tencent.mm.ui.bj", "com.tencent.mm.ui.bi");
            if (bkClass == null) return false;
            // Try C() first, then fallback to other boolean methods
            for (String m : new String[]{"C", "D", "B", "E"}) {
                try {
                    return (boolean) XposedHelpers.callStaticMethod(bkClass, m);
                } catch (Throwable ignored) {}
            }
            return false;
        } catch (Throwable ignored) { return false; }
    }

    /** 主页左上角三横菜单，注入失败时自动重试（修复启动时 window token 未就绪导致的 BadTokenException） */
    private static void injectMain(final Activity act, final int attempt) {
        try {
            if (act == null || act.isFinishing()) return;
            if (!isEnabled(act)) return;
            removeAll();
            Context ctx = act;
            WindowManager wm = act.getWindowManager();
            if (wm == null) return;
            sMainWM = wm;

            int iconW = dp(ctx, 36);
            int iconH = dp(ctx, 44);
            ImageView icon = new ImageView(ctx);
            icon.setTag(HAMBURGER_TAG);
            icon.setImageBitmap(darkMode(ctx) ? sBitmapDark : sBitmapLight);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setClickable(true);
            icon.setFocusable(true);
            icon.setEnabled(true);

            icon.setOnClickListener(v -> showMenu(v.getContext(), act));

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    iconW, iconH,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.x = dp(ctx, 6);
            lp.y = statusBarHeight(act) + dp(ctx, 6);

            wm.addView(icon, lp);
            sMainIcon = icon;
            if (attempt == 0) {
                LogWriter.log(TAG, "injectMain: added hamburger x=" + lp.x + " y=" + lp.y);
            } else {
                LogWriter.log(TAG, "injectMain: added hamburger (retry " + attempt + ") x=" + lp.x + " y=" + lp.y);
            }
        } catch (Throwable e) {
            if (attempt == 0) {
                LogWriter.log(TAG, "injectMain: FAILED - " + e.getClass().getSimpleName()
                    + ": " + e.getMessage() + ", 开始重试");
            }
            retryInject(act, attempt);
        }
    }

    private static void retryInject(final Activity act, final int attempt) {
        if (attempt >= MAX_RETRY) {
            LogWriter.log(TAG, "injectMain: 重试已达上限，放弃");
            return;
        }
        final int next = attempt + 1;
        try {
            final View decor = act.getWindow() != null ? act.getWindow().getDecorView() : null;
            if (decor != null) {
                decor.postDelayed(() -> injectMain(act, next), RETRY_DELAY_MS);
            } else {
                sH.postDelayed(() -> injectMain(act, next), RETRY_DELAY_MS);
            }
        } catch (Throwable t) {
            sH.postDelayed(() -> injectMain(act, next), RETRY_DELAY_MS);
        }
    }

    private static void removeAll() {
        if (sMainIcon != null) {
            try { if (sMainWM != null) sMainWM.removeView(sMainIcon); } catch (Throwable ignored) {}
            sMainIcon = null;
        }
    }

    private static int statusBarHeight(Activity act) {
        try {
            int id = act.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return act.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {}
        return dp(act, 24);
    }

    /** 弹出快捷菜单 */
    private static void showMenu(Context ctx, Activity act) {
        try {
            java.util.List<String> items = new java.util.ArrayList<>();
            java.util.List<Runnable> actions = new java.util.ArrayList<>();

            SharedPreferences sp = UnifiedPrefs.get(ctx, "wm_prefs");

            if (sp.getBoolean("scheduled_moment", false)) {
                items.add("朋友圈定时");
                actions.add(() -> { if (act != null) WmHomeHook.scheduledMoment(act); });
            }
            items.add("一键免打扰");
            actions.add(() -> ChatRoomMuteHelper.muteAllAsync(sClassLoader, ctx));
            items.add("取消免打扰");
            actions.add(() -> ChatRoomMuteHelper.unmuteAllAsync(sClassLoader, ctx));

            // 乐少助手（菜单第一项）
            items.add(0, "乐少助手");
            actions.add(0, () -> {
                try { com.leshao.v3.ui.MainActivity.open(act); }
                catch (Throwable e2) { LogWriter.log(TAG, "打开设置失败: " + e2.getMessage()); }
            });

            String[] menuArr = items.toArray(new String[0]);
            int userId = Process.myUid() / 100000;
            String title = userId == 0 ? "快捷菜单" : ("快捷菜单【分身user" + userId + "】");

            AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setItems(menuArr, (d, w) -> {
                    try { actions.get(w).run(); } catch (Throwable ignored) {}
                })
                .create();
            dialog.show();

            Window window = dialog.getWindow();
            if (window != null) {
                int cardBg = AppColors.card();
                int textColor = AppColors.text1();
                window.setBackgroundDrawable(new ColorDrawable(cardBg));
                window.setDimAmount(0.3f);

                ListView listView = dialog.getListView();
                if (listView != null) {
                    listView.setBackgroundColor(cardBg);
                    listView.setDivider(new ColorDrawable(AppColors.divider()));
                    listView.setDividerHeight(1);
                    final android.graphics.drawable.Drawable settingsIcon =
                        IconLoader.load(ctx, IconLoader.IC_LESHAO_ICON, 14);
                    listView.setAdapter(new ArrayAdapter<String>(ctx,
                            android.R.layout.simple_list_item_1, menuArr) {
                        @Override
                        public View getView(int pos, View convertView, ViewGroup parent) {
                            TextView tv = (TextView) super.getView(pos, convertView, parent);
                            tv.setTextColor(textColor);
                            if (pos == 0 && settingsIcon != null) {
                                settingsIcon.setBounds(0, 0,
                                    (int) (18 * ctx.getResources().getDisplayMetrics().density),
                                    (int) (18 * ctx.getResources().getDisplayMetrics().density));
                                tv.setCompoundDrawables(settingsIcon, null, null, null);
                                tv.setCompoundDrawablePadding(
                                    (int) (8 * ctx.getResources().getDisplayMetrics().density));
                            }
                            return tv;
                        }
                    });
                }

                try {
                    int titleId = ctx.getResources().getIdentifier("alertTitle", "id", "android");
                    TextView titleView = dialog.findViewById(titleId);
                    if (titleView != null) titleView.setTextColor(textColor);
                } catch (Throwable ignored) {}

                WindowManager.LayoutParams lp = window.getAttributes();
                lp.width = dp(ctx, 200);
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                lp.gravity = Gravity.TOP | Gravity.LEFT;
                lp.x = dp(ctx, 8);
                lp.y = statusBarHeight(act) + dp(ctx, 50);
                window.setAttributes(lp);
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "showMenu: FAILED - " + e.getMessage());
        }
    }
}
