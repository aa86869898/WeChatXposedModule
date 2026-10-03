/**
 * ============================================================
 * 左上角三横菜单 — 微信 Xposed 模块 (WindowManager 悬浮窗注入)
 * ============================================================
 * 依赖: 仅 de.robv.android.xposed (无第三方)
 * 兼容: Java 8+ / API 24+
 *
 * v1117: 回到 v912 的注入方式(实机验证可用), 放弃 v1109 起的 DecorView 子 View 方案。
 *   - 注入: WindowManager.addView(TYPE_APPLICATION_PANEL), Gravity.TOP|START,
 *           x=6dp, y=状态栏高度+5dp, 恰好落在微信主页标题栏内左侧。
 *           不再读取主题 actionBarSize(该属性在微信下是失真值 12289, 曾把图标顶出屏幕)。
 *   - 显隐: 仅在 LauncherUI(主页) 显示; 进入聊天页隐藏。
 *           聊天判定改为「聊天输入框 MMEditText 是否真正显示在屏幕上」——
 *           进入聊天 MMEditText 可见 → 隐藏; 返回主页 MMEditText 不再可见 → 立即恢复。
 *           旧的 ChattingUIFragment.getView().isShown() 在返回主页后仍为 true, 是
 *           "进入聊天再返回后消失" 的根因, 已弃用。
 *   - 信号: Activity.onResume/onWindowFocusChanged/onPause/onDestroy(仓库已验证触发)
 *           + View.onAttachedToWindow/onDetachedFromWindow(MMEditText, 仓库已验证触发)
 *           + 300ms 兜底轮询(仅在主页 Activity 处于前台时运行)。
 *
 * 保留: 文档符号仅用于 diagSymbols() 诊断打印, 不参与任何判定。
 * 保留: 糖果粉图标样式、wm_prefs.corner_menu 开关、点击弹出的快捷菜单。
 * ============================================================
 */
package com.leshao.v3;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;

import com.leshao.v3.hook.VersionCompat;
import com.leshao.v3.ui.AppColors;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class CornerMenu {
    private static final String TAG = "CornerMenu";
    private static final String VIEW_TAG = "LESHAO_HAM_V2";

    private static final String LAUNCHER_UI = "com.tencent.mm.ui.LauncherUI";
    private static final String CHAT_EDIT_TEXT = "com.tencent.mm.ui.widget.MMEditText";

    /** 兜底轮询间隔(仅在主页 Activity 前台时运行) */
    private static final long POLL_MS = 300L;
    private static final int MAX_RETRY = 10;
    private static final long RETRY_DELAY_MS = 200L;

    // ── 文档符号(仅 diagSymbols 诊断用, 不参与判定) ──
    private static final String F_HOME_UI = "i";
    private static final String F_CHATTING_TAB = "chattingTabUI";
    private static final String M_GET_TAB_UI = "getMainTabUI";
    private static final String F_TAB_INDEX = "e";
    private static final String M_GET_CUR_FRAG = "g";
    private static final String M_CHAT_FOREGROUND = "m";

    private static ClassLoader sClassLoader;
    private static Bitmap sBitmapLight;
    private static Bitmap sBitmapDark;
    private static final Handler sH = new Handler(Looper.getMainLooper());

    /** 悬浮窗状态(全局一份, 只属于当前前台 LauncherUI) */
    private static volatile View sIcon;
    private static volatile WindowManager sWM;
    private static volatile WeakReference<Activity> sHost = new WeakReference<>(null);
    private static volatile boolean sPolling;

    private static int dp(Context ctx, float dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 入口方法 — 在 handleLoadPackage 中调用
     */
    public static void hook(ClassLoader cl) {
        try {
            ClassLoader tkCL = VersionCompat.findTinkerClassLoader(cl);
            sClassLoader = tkCL != null ? tkCL : cl;
            LogWriter.log(TAG, "hook: start (WindowManager overlay)");
            createBitmaps();

            Class<?> launcher = findClass(LAUNCHER_UI, sClassLoader);
            if (launcher == null) {
                LogWriter.log(TAG, "hook: 找不到 " + LAUNCHER_UI + ", 请确认微信版本");
                return;
            }

            // ── Activity 生命周期(本仓库 MainHook/WmEntry 已验证 hookAllMethods(Activity) 必定触发) ──
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!(p.thisObject instanceof Activity)) return;
                        Activity a = (Activity) p.thisObject;
                        if (!LAUNCHER_UI.equals(a.getClass().getName())) return;
                        LogWriter.log(TAG, "onResume LauncherUI -> pump");
                        onHomeResumed(a);
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "onWindowFocusChanged", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!(p.thisObject instanceof Activity)) return;
                        Activity a = (Activity) p.thisObject;
                        if (!LAUNCHER_UI.equals(a.getClass().getName())) return;
                        boolean focused = p.args.length > 0 && Boolean.TRUE.equals(p.args[0]);
                        if (focused) onHomeResumed(a);
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "onPause", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!(p.thisObject instanceof Activity)) return;
                        Activity a = (Activity) p.thisObject;
                        if (!LAUNCHER_UI.equals(a.getClass().getName())) return;
                        // 主页暂停(切到设置/朋友圈/离开微信等) → 摘除悬浮窗并停轮询
                        stopPoll();
                        removeOverlay();
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "onDestroy", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!(p.thisObject instanceof Activity)) return;
                        Activity a = (Activity) p.thisObject;
                        if (!LAUNCHER_UI.equals(a.getClass().getName())) return;
                        stopPoll();
                        removeOverlay();
                    } catch (Throwable ignored) {}
                }
            });

            // ── 聊天进出即时信号(本仓库 WmEntry 已验证 MMEditText attach/detach 可靠) ──
            XposedBridge.hookAllMethods(View.class, "onAttachedToWindow", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.thisObject instanceof View
                            && CHAT_EDIT_TEXT.equals(p.thisObject.getClass().getName())) {
                        triggerRefresh("MMEditText attached");
                    }
                }
            });
            XposedBridge.hookAllMethods(View.class, "onDetachedFromWindow", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.thisObject instanceof View
                            && CHAT_EDIT_TEXT.equals(p.thisObject.getClass().getName())) {
                        triggerRefresh("MMEditText detached");
                    }
                }
            });

            // ── 文档的打开/关闭聊天 hook: 仅作即时刷新的额外信号(取不到不阻断) ──
            hook(launcher, "startChatting", new Class<?>[0], new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { triggerRefresh("startChatting"); }
            });
            hook(launcher, "closeChatting", new Class<?>[]{boolean.class}, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { triggerRefresh("closeChatting"); }
            });

            diagSymbols(launcher);
            LogWriter.log(TAG, "hook installed (WindowManager overlay + 聊天输入框判定)");
        } catch (Throwable e) {
            LogWriter.log(TAG, "hook: FAILED - " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    // ================================================================
    // 主页生命周期 / 轮询
    // ================================================================

    private static void onHomeResumed(Activity a) {
        sHost = new WeakReference<>(a);
        startPoll();
        refresh(a);
    }

    private static void startPoll() {
        if (sPolling) return;
        sPolling = true;
        sH.post(tick);
    }

    private static void stopPoll() {
        sPolling = false;
        sH.removeCallbacks(tick);
    }

    private static final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!sPolling) return;
            Activity a = sHost.get();
            if (a == null || a.isFinishing()) { sPolling = false; return; }
            try { refresh(a); } catch (Throwable ignored) {}
            sH.postDelayed(this, POLL_MS);
        }
    };

    private static void triggerRefresh(final String why) {
        sH.post(new Runnable() {
            @Override public void run() {
                Activity a = sHost.get();
                if (a == null) return;
                try { refresh(a); } catch (Throwable ignored) {}
            }
        });
    }

    /** 唯一职责: 决定当前主页是否应该显示左上角按钮 */
    private static void refresh(Activity act) {
        if (act == null || act.isFinishing()
                || (Build.VERSION.SDK_INT >= 17 && act.isDestroyed())) {
            removeOverlay();
            return;
        }
        if (!isEnabled(act)) { removeOverlay(); return; }
        if (isWeChatHome(act)) showOverlay(act);
        else hideOverlay();
    }

    /**
     * 是否「主页且不在聊天页」。
     * 聊天判定: 聊天输入框 MMEditText 是否真正显示在屏幕上 —— 进入聊天它在, 返回主页它不在。
     */
    private static boolean isWeChatHome(Activity act) {
        try {
            if (!LAUNCHER_UI.equals(act.getClass().getName())) return false;
            if (isChatInputVisible(act)) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** DecorView 内是否存在正在显示的 MMEditText(即聊天输入框) */
    private static boolean isChatInputVisible(Activity act) {
        try {
            View decor = act.getWindow() != null ? act.getWindow().getDecorView() : null;
            if (decor == null) return false;
            return hasShownChatInput(decor);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasShownChatInput(View v) {
        if (v == null || !v.isShown()) return false;
        if (CHAT_EDIT_TEXT.equals(v.getClass().getName())) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasShownChatInput(g.getChildAt(i))) return true;
            }
        }
        return false;
    }

    // ================================================================
    // 悬浮窗增删
    // ================================================================

    private static void showOverlay(Activity act) {
        View cur = sIcon;
        if (cur != null && sHost.get() == act) {
            if (cur.getVisibility() != View.VISIBLE) {
                cur.setVisibility(View.VISIBLE);
                LogWriter.log(TAG, "overlay show");
            }
            return;
        }
        addOverlay(act, 0);
    }

    private static void hideOverlay() {
        View cur = sIcon;
        if (cur != null && cur.getVisibility() != View.GONE) {
            cur.setVisibility(View.GONE);
            LogWriter.log(TAG, "overlay hide (聊天页)");
        }
    }

    private static void removeOverlay() {
        View cur = sIcon;
        WindowManager wm = sWM;
        sIcon = null;
        sWM = null;
        if (cur == null) return;
        try { if (wm != null) wm.removeViewImmediate(cur); } catch (Throwable ignored) {}
        LogWriter.log(TAG, "overlay removed");
    }

    private static void addOverlay(final Activity act, final int attempt) {
        try {
            if (act == null || act.isFinishing()) return;
            if (Build.VERSION.SDK_INT >= 17 && act.isDestroyed()) return;
            // 文档《WeChat_LeftTop_Inject_Analysis.md》注入成功后，隐藏旧版悬浮球入口，避免重复入口
            if (com.leshao.v3.hook.LeftTopEntryHook.isInjected()) { removeOverlay(); return; }
            removeOverlay();

            Context ctx = act;
            WindowManager wm = act.getWindowManager();
            if (wm == null) return;

            int w = dp(ctx, 36);
            int h = dp(ctx, 36);
            ImageView icon = new ImageView(ctx);
            icon.setTag(VIEW_TAG);
            icon.setImageBitmap(darkMode(ctx) ? sBitmapDark : sBitmapLight);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setClickable(true);
            icon.setFocusable(true);
            icon.setEnabled(true);
            try {
                icon.setBackground(null);
                int pad = dp(ctx, 3);
                icon.setPadding(pad, pad, pad, pad);
            } catch (Throwable ignored) {}
            // 点击左上角入口弹出快捷菜单（模块主页/一键群聊免打扰/一键解除群聊免打扰）
            icon.setOnClickListener(v -> {
                try { com.leshao.v3.hook.LeftTopEntryHook.showMenu(act); }
                catch (Throwable e) { LogWriter.log(TAG, "打开左上角菜单失败: " + e.getMessage()); }
            });

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    w, h,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = dp(ctx, 6);
            lp.y = statusBarHeight(act) + dp(ctx, 5);

            wm.addView(icon, lp);
            sIcon = icon;
            sWM = wm;
            sHost = new WeakReference<>(act);
            LogWriter.log(TAG, "overlay added x=" + lp.x + " y=" + lp.y
                    + (attempt == 0 ? "" : " (retry " + attempt + ")") + " sb=" + statusBarHeight(act));
        } catch (Throwable e) {
            if (attempt >= MAX_RETRY) {
                LogWriter.log(TAG, "overlay add FAILED 放弃 - "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                return;
            }
            if (attempt == 0) {
                LogWriter.log(TAG, "overlay add retry - "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            sH.postDelayed(() -> addOverlay(act, attempt + 1), RETRY_DELAY_MS);
        }
    }

    /** 状态栏/刘海高度: Insets 优先, status_bar_height 资源兜底 */
    private static int statusBarHeight(Activity act) {
        try {
            if (act != null && act.getWindow() != null) {
                View decor = act.getWindow().peekDecorView();
                if (decor != null && decor.isAttachedToWindow()) {
                    androidx.core.view.WindowInsetsCompat wi =
                            androidx.core.view.ViewCompat.getRootWindowInsets(decor);
                    if (wi != null) {
                        int t = wi.getInsets(
                                androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
                        if (t > 0) return t;
                    }
                }
            }
        } catch (Throwable ignored) {}
        try {
            int id = act.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return act.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {}
        return dp(act, 24);
    }

    // ================================================================
    // 图标
    // ================================================================

    /** 绘制图标(颜色跟随 AppColors 动态主色)。已锁定样式 02「四宫格」 */
    private static void createBitmaps() {
        int size = 128;
        Paint paint = new Paint();
        paint.setStyle(Paint.Style.FILL);
        paint.setAntiAlias(true);

        int primary = AppColors.primary();
        sBitmapLight = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(sBitmapLight);
        paint.setShader(new android.graphics.LinearGradient(0f, 0f, size, size,
                new int[]{primary, primary, primary}, null,
                android.graphics.Shader.TileMode.CLAMP));
        drawGrid(canvas, paint);

        sBitmapDark = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        canvas = new Canvas(sBitmapDark);
        paint.setShader(new android.graphics.LinearGradient(0f, 0f, size, size,
                new int[]{primary, primary, primary}, null,
                android.graphics.Shader.TileMode.CLAMP));
        drawGrid(canvas, paint);
    }

    /** 供新版左上角 DecorView 入口复用旧版「四宫格」图标位图。 */
    public static Bitmap entryIconBitmap(Context ctx) {
        if (sBitmapLight == null || sBitmapDark == null) createBitmaps();
        return darkMode(ctx) ? sBitmapDark : sBitmapLight;
    }

    /** 02 四宫格: 2×2 圆角方块 */
    private static void drawGrid(Canvas canvas, Paint paint) {
        float pad = 22f;
        float gap = 10f;
        float box = (128f - pad * 2f - gap) / 2f;
        float r = 9f;
        android.graphics.RectF rect = new android.graphics.RectF();
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                float x = pad + col * (box + gap);
                float y = pad + row * (box + gap);
                rect.set(x, y, x + box, y + box);
                canvas.drawRoundRect(rect, r, r, paint);
            }
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
            Class<?> bkClass = VersionCompat.findClassMulti(cl,
                    "com.tencent.mm.ui.bk", "com.tencent.mm.ui.bl",
                    "com.tencent.mm.ui.bj", "com.tencent.mm.ui.bi");
            if (bkClass == null) return false;
            for (String m : new String[]{"C", "D", "B", "E"}) {
                try {
                    return (boolean) XposedHelpers.callStaticMethod(bkClass, m);
                } catch (Throwable ignored) {}
            }
            return false;
        } catch (Throwable ignored) { return false; }
    }

    // ================================================================
    // Hook / 反射工具(R8 安全: 不用 varargs findAndHookMethod)
    // ================================================================

    private static void hook(Class<?> c, String name, Class<?>[] params, XC_MethodHook h) {
        try {
            Method m = findMethod(c, name, params);
            if (m == null) {
                LogWriter.log(TAG, "hook miss " + c.getName() + "#" + name);
                return;
            }
            m.setAccessible(true);
            XposedBridge.hookMethod(m, h);
            LogWriter.log(TAG, "hooked " + c.getSimpleName() + "#" + name);
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook err " + c.getName() + "#" + name + ": " + t);
        }
    }

    private static Method findMethod(Class<?> c, String name, Class<?>[] params) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredMethod(name, params);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Class<?> findClass(String name, ClassLoader cl) {
        try { return XposedHelpers.findClass(name, cl); } catch (Throwable t) { return null; }
    }

    /** 诊断: 打印文档符号在当前微信版本是否存在(不参与判定, 仅供定位) */
    private static void diagSymbols(Class<?> launcher) {
        try {
            java.lang.reflect.Field fi = findFieldUp(launcher, F_HOME_UI);
            java.lang.reflect.Field fc = findFieldUp(launcher, F_CHATTING_TAB);
            LogWriter.log(TAG, "diag: LauncherUI.i=" + (fi == null ? "MISSING" : fi.getType().getName())
                    + "  chattingTabUI=" + (fc == null ? "MISSING" : fc.getType().getName()));
            if (fi != null) {
                Class<?> home = fi.getType();
                Method gm = findMethodAny(home, M_GET_TAB_UI, new Class<?>[0]);
                LogWriter.log(TAG, "diag: " + home.getName() + ".getMainTabUI="
                        + (gm == null ? "MISSING" : gm.getReturnType().getName()));
                if (gm != null) {
                    Class<?> tab = gm.getReturnType();
                    LogWriter.log(TAG, "diag: " + tab.getName() + " field '" + F_TAB_INDEX + "'="
                            + (findFieldUp(tab, F_TAB_INDEX) != null)
                            + " method '" + M_GET_CUR_FRAG + "'="
                            + (findMethodAny(tab, M_GET_CUR_FRAG, new Class<?>[0]) != null));
                }
            }
            if (fc != null) {
                LogWriter.log(TAG, "diag: " + fc.getType().getName() + ".m()="
                        + (findMethodAny(fc.getType(), M_CHAT_FOREGROUND, new Class<?>[0]) != null));
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "diag err: " + t);
        }
    }

    private static java.lang.reflect.Field findFieldUp(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try { return k.getDeclaredField(name); } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Method findMethodAny(Class<?> c, String name, Class<?>[] params) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try { return k.getDeclaredMethod(name, params); } catch (Throwable ignored) {}
        }
        return null;
    }
}
