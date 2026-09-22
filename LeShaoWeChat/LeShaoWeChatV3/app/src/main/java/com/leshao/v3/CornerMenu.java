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
    private static Runnable sRecheckRunnable;
    private static ClassLoader sClassLoader;
    private static boolean sSelfHealStarted;
    private static Bitmap sBitmapLight;
    private static Bitmap sBitmapDark;

    private static View sMainIcon;
    private static WindowManager sMainWM;
    private static Activity sHomeAct;
    private static final Handler sH = new Handler(Looper.getMainLooper());

    // 聊天窗口精确标志位: 由 ChattingUIFragment(基类)onHiddenChanged/onResume/onPause 驱动,
    // 比遍历 fragment 检查 view 状态更可靠 (回主页后 view 状态可能未同步/多实例误判)
    private static volatile boolean sChatWindowActive;
    /** 聊天 fragment 最近一次 onResume 时间(elapsedRealtime), 防残留 fragment 误判 */
    private static volatile long sChatResumeAt;

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
                                sHomeAct = (Activity) activity;
                                // 8.0.49+: 聊天窗口是 LauncherUI 内的 ChattingUIFragment，
                                // 必须排除，否则聊天界面也注入三横菜单
                                if (isInChatWindow((Activity) activity)) {
                                    LogWriter.log(TAG, "skip inject: chat window active");
                                    if (sMainIcon != null) removeAll();
                                    // 可能误判: fragment view 状态延迟同步, 延迟复核一次
                                    scheduleRecheckInject((Activity) activity);
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
            // 兜底注入路径: 主页 onResume (onWindowFocusChanged 在焦点不变/悬浮窗被系统移除
            // 等场景不触发, 三横菜单会"偶尔消失")。延迟至窗口就绪后再注入。
            XposedBridge.hookAllMethods(Activity.class, "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!(param.thisObject instanceof Activity)) return;
                            Activity act = (Activity) param.thisObject;
                            String clsName = act.getClass().getName();
                            if (!"com.tencent.mm.ui.LauncherUI".equals(clsName)
                                    && !"com.tencent.mm.ui.HomeUI".equals(clsName)) return;
                            sHomeAct = act;
                            // 延迟至窗口树就绪, 避免 BadToken; 若已有效注入则跳过
                            final Activity fAct = act;
                            sH.postDelayed(() -> {
                                try {
                                    if (fAct == null || fAct.isFinishing()) return;
                                    if (hasActiveMenu()) return;
                                    // v969: 交由 isInChatWindow 复核(可纠正卡死的标志位)
                                    if (isInChatWindow(fAct)) return;
                                    LogWriter.log(TAG, "onResume 兜底注入 hamburger");
                                    injectMain(fAct, 0);
                                } catch (Throwable ignored) {}
                            }, 150);
                        } catch (Throwable ignored) {}
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

            // 自愈轮询: 主页停留期间悬浮窗可能被系统/微信布局刷新移除, 而聚焦事件不再触发。
            // 低频率检查弥补"偶尔消失", 无副作用(主页且无有效菜单时补注入)。
            if (!sSelfHealStarted) {
                sSelfHealStarted = true;
                sH.postDelayed(new Runnable() {
                    @Override public void run() {
                        try {
                            selfHealMainMenu();
                        } catch (Throwable ignored) {}
                        sH.postDelayed(this, 2000);
                    }
                }, 2000);
                LogWriter.log(TAG, "self-heal poll started");
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "hook: FAILED - " + e.getClass().getSimpleName()
                + ": " + e.getMessage());
        }
    }

    /** 自愈: 若当前处于主页且三横菜单缺失, 补注入 */
    private static void selfHealMainMenu() {
        try {
            Activity act = sHomeAct;
            if (act == null || act.isFinishing()) return;
            String clsName = act.getClass().getName();
            if (!"com.tencent.mm.ui.LauncherUI".equals(clsName)
                    && !"com.tencent.mm.ui.HomeUI".equals(clsName)) return;
            if (hasActiveMenu()) return;
            // v969: 不再用 sChatWindowActive 直接拦截, 交给 isInChatWindow 复核
            // (可自动纠正卡死在 true 的标志位, 修复三横菜单偶发不再出现)
            if (isInChatWindow(act)) return;
            LogWriter.log(TAG, "self-heal: hamburger missing on main page, reinject");
            injectMain(act, 0);
        } catch (Throwable ignored) {}
    }

    /** 绘制三横 (≡) 菜单图标（v955 M3：主色绘制，微信浅色底/暗色底均清晰） */
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
        paint.setColor(0xFF006C4C); // M3 primary（浅色）
        drawLines(canvas, paint);

        sBitmapDark = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        canvas = new Canvas(sBitmapDark);
        paint.setColor(0xFF6CDBAC); // M3 primary（暗色）
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
        // 优先使用 onHiddenChanged/onResume 驱动的精确标志位:
        // 回主页后 fragment view 可能仍短暂标记可见或存在多个实例,
        // 遍历检查不可靠, 导致三横菜单偶发不显示。
        if (sChatWindowActive) {
            // v969: 标志位可能因残留 fragment / 生命周期回调缺失而卡在 true,
            // 从而永久挡死 self-heal/restore 全部恢复路径(表现为三横菜单消失后不再出现)。
            // 最近 3s 内确实发生过聊天 onResume 才信任; 否则用 fragment 实际可见性复核,
            // 复核为“不在聊天”时立刻纠正卡死的标志位。
            long since = sChatResumeAt > 0
                    ? android.os.SystemClock.elapsedRealtime() - sChatResumeAt
                    : Long.MAX_VALUE;
            if (since < 3000) return true;
            if (chatFragmentVisible(act)) return true;
            sChatWindowActive = false;
            return false;
        }
        // v961: 最近 3s 内聊天 fragment 有过 onResume 视为聊天中;
        // 残留(已 detach/hide)的 fragment 不满足该时间条件, 不再误判
        if (sChatResumeAt > 0
                && android.os.SystemClock.elapsedRealtime() - sChatResumeAt < 3000) {
            return true;
        }
        return chatFragmentVisible(act);
    }

    /** fragment 实际可见性扫描(仅统计真正显示在屏幕上的聊天 fragment)。 */
    private static boolean chatFragmentVisible(Activity act) {
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

    /** 三横菜单是否"真正有效显示"（view 存在且仍挂载在窗口树）。
     *  悬浮窗 view 可能被系统/微信移除但引用残留, 此时应视为无菜单并可重新注入,
     *  避免 sMainIcon != null 判断永久阻挡注入导致"偶尔消失"。 */
    private static boolean hasActiveMenu() {
        if (sMainIcon == null) return false;
        try { return sMainIcon.getParent() != null; } catch (Throwable t) { return false; }
    }

    /** 聊天窗口精确可见性: hook ChattingUIFragment(含继承链上的基类方法)维护标志位。
     *  同时拦截回主页瞬间(chat hidden)立即恢复注入, 修复偶发不显示。
     *
     *  v961: 3180 的 onHiddenChanged 声明在基类 MMFragment 上, 原
     *  hookAllMethods(ChattingUIFragment, ...) 只 hook 子类自身声明的方法,
     *  hook 从未触发 → 回主页后无恢复路径。改为沿继承链查找声明方法再 hook
     *  (与 WmEntry.hookChatFragMethod 同策略), 并增加 onResume/onPause 驱动
     *  sChatWindowActive + sChatResumeAt 时间戳(防残留 fragment mView.isShown 误判)。 */
    private static void hookChatFragmentVisibility(ClassLoader cl) {
        try {
            Class<?> fragCls = XposedHelpers.findClass(
                "com.tencent.mm.ui.chatting.ChattingUIFragment", cl);
            // v961: 沿继承链向上找声明方法(onHiddenChanged 在 MMFragment 基类)
            hookChatFragMethod(fragCls, "onHiddenChanged", new Class<?>[]{boolean.class},
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            // v966: 该方法声明在 MMFragment 基类, 主页所有 fragment 都会触发;
                            // 必须过滤出聊天 fragment, 否则主页 tab show 会把 sChatWindowActive
                            // 错误置 true, 挡死 self-heal/restore/onResume 兜底全部恢复路径
                            // (表现为按钮消失后须重启微信才恢复)
                            Object thiz = param.thisObject;
                            if (thiz == null || !fragCls.isAssignableFrom(thiz.getClass())) return;
                            boolean hidden = (Boolean) param.args[0];
                            sChatWindowActive = !hidden;
                            if (hidden) {
                                restoreMainMenuFromFragment(thiz);
                            } else {
                                // v966: 进入聊天瞬间移除悬浮按钮
                                // (Activity 级悬浮窗不会随 fragment 切换自动消失)
                                if (sMainIcon != null) removeAll();
                            }
                        } catch (Throwable e) {
                            LogWriter.log(TAG, "onHiddenChanged cb err: " + e);
                        }
                    }
                });
            // v961: onResume 置位 + 记录时间戳(进入聊天)
            hookChatFragMethod(fragCls, "onResume", new Class<?>[0],
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            // v966: 防御性过滤, 仅聊天 fragment 生效
                            Object thiz = param.thisObject;
                            if (thiz == null || !fragCls.isAssignableFrom(thiz.getClass())) return;
                            sChatWindowActive = true;
                            sChatResumeAt = android.os.SystemClock.elapsedRealtime();
                            // v966: 进入聊天立即移除, 双保险(onHiddenChanged(false) 可能未触发)
                            if (sMainIcon != null) removeAll();
                        } catch (Throwable e) {
                            LogWriter.log(TAG, "chat onResume cb err: " + e);
                        }
                    }
                });
            // v961: onPause 置位 + 回主页恢复注入
            hookChatFragMethod(fragCls, "onPause", new Class<?>[0],
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            // v966: 防御性过滤, 仅聊天 fragment 生效
                            Object thiz = param.thisObject;
                            if (thiz == null || !fragCls.isAssignableFrom(thiz.getClass())) return;
                            sChatWindowActive = false;
                            restoreMainMenuFromFragment(thiz);
                        } catch (Throwable e) {
                            LogWriter.log(TAG, "chat onPause cb err: " + e);
                        }
                    }
                });
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookChatFragmentVisibility err: " + t.getMessage());
        }
    }

    /** 回主页后恢复三横菜单(延迟至 fragment 状态稳定)。 */
    private static void restoreMainMenuFromFragment(final Object frag) {
        final Object f = frag;
        sH.postDelayed(() -> {
            try {
                if (f == null || sChatWindowActive) return;
                Activity act = (Activity) XposedHelpers.callMethod(f, "getActivity");
                if (act == null) return;
                String clsName = act.getClass().getName();
                if (!"com.tencent.mm.ui.LauncherUI".equals(clsName)
                        && !"com.tencent.mm.ui.HomeUI".equals(clsName)) return;
                if (hasActiveMenu()) return;
                if (isInChatWindow(act)) return;
                LogWriter.log(TAG, "restore: hamburger missing after leaving chat, reinject");
                injectMain(act, 0);
            } catch (Throwable ignored) {}
        }, 100);
    }

    /** 沿继承链查找 fragment 方法并 hook(onHiddenChanged 等在基类声明)。 */
    private static void hookChatFragMethod(Class<?> fragCls, String name,
                                           Class<?>[] paramTypes, XC_MethodHook hook) {
        try {
            java.lang.reflect.Method m = null;
            Class<?> cur = fragCls;
            while (cur != null && cur != Object.class) {
                try {
                    m = cur.getDeclaredMethod(name, paramTypes);
                    break;
                } catch (NoSuchMethodException e) {
                    cur = cur.getSuperclass();
                }
            }
            if (m == null) return;
            m.setAccessible(true);
            XposedBridge.hookMethod(m, hook);
            LogWriter.log(TAG, "hook: ChattingUIFragment." + name
                + " (" + m.getDeclaringClass().getSimpleName() + ") hooked");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookChatFragMethod " + name + " err: " + t.getMessage());
        }
    }

    /** 延迟复核注入: 修复回主页瞬间 fragment 遍历误判"在聊天中"导致三横菜单不显示。
     *  若复核时已离开聊天窗口且无残留菜单, 立即注入。 */
    private static void scheduleRecheckInject(final Activity act) {
        try {
            if (sRecheckRunnable != null) sH.removeCallbacks(sRecheckRunnable);
            sRecheckRunnable = () -> {
                try {
                    if (act == null || act.isFinishing()) return;
                    if (hasActiveMenu()) return;
                    if (isInChatWindow(act)) return;
                    String clsName = act.getClass().getName();
                    if (!"com.tencent.mm.ui.LauncherUI".equals(clsName)
                            && !"com.tencent.mm.ui.HomeUI".equals(clsName)) return;
                    LogWriter.log(TAG, "scheduleRecheckInject -> main page confirmed, inject");
                    injectMain(act, 0);
                } catch (Throwable ignored) {}
            };
            sH.postDelayed(sRecheckRunnable, 200);
        } catch (Throwable t) {
            LogWriter.log(TAG, "scheduleRecheckInject err: " + t.getMessage());
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

            int iconW = dp(ctx, 40);
            int iconH = dp(ctx, 40);
            ImageView icon = new ImageView(ctx);
            icon.setTag(HAMBURGER_TAG);
            icon.setImageBitmap(darkMode(ctx) ? sBitmapDark : sBitmapLight);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setClickable(true);
            icon.setFocusable(true);
            icon.setEnabled(true);
            // v955 M3: 悬浮入口加圆角容器底（surfaceContainerLowest + 主色描边），提升可见性与质感
            try {
                int r = dp(ctx, 12);
                GradientDrawable iconBg = new GradientDrawable();
                iconBg.setShape(GradientDrawable.RECTANGLE);
                iconBg.setCornerRadius(r);
                iconBg.setColor(AppColors.surfaceContainerLowest());
                iconBg.setStroke(dp(ctx, 1), AppColors.primary());
                icon.setBackground(iconBg);
                int pad = dp(ctx, 7);
                icon.setPadding(pad, pad, pad, pad);
            } catch (Throwable ignored) {}

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
                // v955 M3: 28dp extra-large 圆角弹窗 + surfaceContainerHigh 底
                int r = dp(ctx, 28);
                GradientDrawable winBg = new GradientDrawable();
                winBg.setShape(GradientDrawable.RECTANGLE);
                winBg.setCornerRadius(r);
                winBg.setColor(AppColors.surfaceContainerHigh());
                window.setBackgroundDrawable(winBg);
                window.setDimAmount(0.3f);

                ListView listView = dialog.getListView();
                if (listView != null) {
                    listView.setBackgroundColor(0x00000000);
                    listView.setDivider(new ColorDrawable(AppColors.outlineVariant()));
                    listView.setDividerHeight(1);
                    listView.setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8));
                    listView.setClipToPadding(false);
                    final android.graphics.drawable.Drawable settingsIcon =
                        IconLoader.load(ctx, IconLoader.IC_LESHAO_ICON, 14);
                    listView.setAdapter(new ArrayAdapter<String>(ctx,
                            android.R.layout.simple_list_item_1, menuArr) {
                        @Override
                        public View getView(int pos, View convertView, ViewGroup parent) {
                            TextView tv = (TextView) super.getView(pos, convertView, parent);
                            // v955 M3 列表行：15sp onSurface + 48dp 行高 + 16dp 水平边距
                            tv.setTextColor(AppColors.onSurface());
                            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
                            tv.setMinimumHeight(dp(ctx, 48));
                            int p = dp(ctx, 16);
                            tv.setPadding(p, 0, p, 0);
                            tv.setGravity(Gravity.CENTER_VERTICAL);
                            try {
                                GradientDrawable rowBg = new GradientDrawable();
                                rowBg.setShape(GradientDrawable.RECTANGLE);
                                rowBg.setCornerRadius(dp(ctx, 12));
                                rowBg.setColor(0x00000000);
                                tv.setBackground(rowBg);
                            } catch (Throwable ignored) {}
                            if (pos == 0 && settingsIcon != null) {
                                int iconSize = dp(ctx, 20);
                                settingsIcon.setBounds(0, 0, iconSize, iconSize);
                                tv.setCompoundDrawables(settingsIcon, null, null, null);
                                tv.setCompoundDrawablePadding(dp(ctx, 12));
                            }
                            return tv;
                        }
                    });
                }

                try {
                    int titleId = ctx.getResources().getIdentifier("alertTitle", "id", "android");
                    TextView titleView = dialog.findViewById(titleId);
                    if (titleView != null) titleView.setTextColor(AppColors.onSurface());
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
