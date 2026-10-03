package com.leshao.v3.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.leshao.v3.CornerMenu;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.CandyUi;
import com.leshao.v3.wm.utils.WmPrefs;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 微信主页左上角「模块入口」——DecorView 叠加层方案（唯一入口）。
 *
 * <p>随 LauncherUI 生命周期（onResume/onWindowFocusChanged/onPause/onDestroy）、
 * 聊天输入框 MMEditText attach/detach、以及 LauncherUI.closeChatting 信号即时刷新，
 * 外加 300ms 兜底轮询，因而从聊天窗口返回时入口立即显示。</p>
 *
 * <p>旧的 ActionBar 注入与 WindowManager 悬浮球入口已移除；图标沿用旧版「四宫格」位图。</p>
 */
public final class LeftTopEntryHook {

    private static final String TAG = "LeftTopEntry";
    private static final String LAUNCHER_UI = "com.tencent.mm.ui.LauncherUI";
    private static final String CHAT_EDIT_TEXT = "com.tencent.mm.ui.widget.MMEditText";
    private static final String DECOR_TAG = "leshao_lefttop_decor_v1";
    private static final long POLL_MS = 300L;

    private static final Handler sH = new Handler(Looper.getMainLooper());
    private static volatile WeakReference<Activity> sHost = new WeakReference<>(null);
    private static volatile View sDecorBtn;
    private static volatile boolean sPolling;
    private static volatile boolean sInjected;
    private static volatile boolean sDecorLogged;
    /** 显式聊天态：MMEditText 附着 / startChatting 置 true，脱离 / closeChatting 置 false。 */
    private static volatile boolean sInChat;

    private LeftTopEntryHook() {}

    public static boolean isInjected() {
        try {
            return sInjected && WmPrefs.isCornerMenu();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void hook(final ClassLoader cl) {
        // DecorView 方案不依赖 DexKit 扫描结果，立即安装，避免等待扫描(约十几秒)错过首个 onResume。
        install(cl);
    }

    private static void install(ClassLoader cl) {
        try {
            installDecorEntry(cl);
            adoptCurrentHome();
            // 无条件启动轮询：无论 onResume/onWindowFocusChanged/closeChatting 哪个信号
            // 丢失，都能在 ≤POLL_MS 内补齐入口，消除「有时候很久没出来」。
            startPoll();
            LogWriter.log(TAG, "decor entry hooks installed");
        } catch (Throwable e) {
            LogWriter.log(TAG, "hook err: " + e);
        }
    }

    /** install 晚于 LauncherUI.onResume 时（DexKit 扫描耗时），立即接管当前主页，消除冷启动不显示。 */
    private static void adoptCurrentHome() {
        try {
            Activity cur = com.leshao.v3.MainHook.currentActivity();
            if (cur == null || !LAUNCHER_UI.equals(cur.getClass().getName())) return;
            sInChat = isChatInputVisible(cur);
            onHomeResumed(cur);
            LogWriter.log(TAG, "adopt current home, inChat=" + sInChat);
        } catch (Throwable ignored) {}
    }

    // ---------------- DecorView 注入 ----------------

    private static void installDecorEntry(ClassLoader cl) {
        XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (p.thisObject instanceof Activity
                            && LAUNCHER_UI.equals(((Activity) p.thisObject).getClass().getName())) {
                        // 主页 Resume 即已离开聊天页（聊天内嵌时不会触发本 Activity Resume）
                        sInChat = false;
                        onHomeResumed((Activity) p.thisObject);
                    }
                } catch (Throwable ignored) {}
            }
        });
        XposedBridge.hookAllMethods(Activity.class, "onWindowFocusChanged", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (!(p.thisObject instanceof Activity)) return;
                    if (!LAUNCHER_UI.equals(((Activity) p.thisObject).getClass().getName())) return;
                    if (p.args.length > 0 && Boolean.TRUE.equals(p.args[0])) {
                        onHomeResumed((Activity) p.thisObject);
                    }
                } catch (Throwable ignored) {}
            }
        });
        XposedBridge.hookAllMethods(Activity.class, "onPause", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (p.thisObject instanceof Activity
                            && LAUNCHER_UI.equals(((Activity) p.thisObject).getClass().getName())) {
                        hideDecor();
                    }
                } catch (Throwable ignored) {}
            }
        });
        XposedBridge.hookAllMethods(Activity.class, "onDestroy", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (p.thisObject instanceof Activity
                            && LAUNCHER_UI.equals(((Activity) p.thisObject).getClass().getName())) {
                        removeDecor();
                    }
                } catch (Throwable ignored) {}
            }
        });
        XposedBridge.hookAllMethods(View.class, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (p.thisObject instanceof View
                            && CHAT_EDIT_TEXT.equals(p.thisObject.getClass().getName())) {
                        sInChat = true;
                        sH.post(() -> {
                            Activity a = sHost.get();
                            if (a != null) sync(a);
                        });
                    }
                } catch (Throwable ignored) {}
            }
        });
        XposedBridge.hookAllMethods(View.class, "onDetachedFromWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (p.thisObject instanceof View
                            && CHAT_EDIT_TEXT.equals(p.thisObject.getClass().getName())) {
                        sInChat = false;
                        sH.post(() -> {
                            Activity a = sHost.get();
                            if (a != null) sync(a);
                        });
                    }
                } catch (Throwable ignored) {}
            }
        });
        hookCloseChatting(cl);
        hookStartChatting(cl);
    }

    /** 从聊天窗口返回主页的即时信号，消除入口延迟显示。 */
    private static void hookCloseChatting(ClassLoader cl) {
        hookLauncherMethod(cl, "closeChatting", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    sInChat = false;
                    if (p.thisObject instanceof Activity) {
                        onHomeResumed((Activity) p.thisObject);
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    /** 进入聊天窗口的即时信号，立即隐藏入口。 */
    private static void hookStartChatting(ClassLoader cl) {
        hookLauncherMethod(cl, "startChatting", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    sInChat = true;
                    Activity a = sHost.get();
                    if (a != null) sync(a);
                } catch (Throwable ignored) {}
            }
        });
    }

    private static void hookLauncherMethod(ClassLoader cl, String name, XC_MethodHook hook) {
        try {
            Class<?> c = findClass(LAUNCHER_UI, cl);
            if (c == null) return;
            for (Method m : c.getDeclaredMethods()) {
                if (!name.equals(m.getName())) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, hook);
                LogWriter.log(TAG, "hooked " + name + "(params="
                        + m.getParameterTypes().length + ")");
                return;
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "hook " + name + " err: " + e);
        }
    }

    private static void onHomeResumed(Activity a) {
        sHost = new WeakReference<>(a);
        startPoll();
        sync(a);
    }

    private static void startPoll() {
        if (sPolling) return;
        sPolling = true;
        sH.post(sTick);
    }

    private static final Runnable sTick = new Runnable() {
        @Override
        public void run() {
            if (!sPolling) return;
            try { reconcile(); } catch (Throwable ignored) {}
            sH.postDelayed(this, POLL_MS);
        }
    };

    /**
     * 轮询兜底：不依赖任何生命周期信号，直接以「当前前台 Activity」为准刷新入口。
     * 自校正聊天态（信号丢失时以视图树为准），确保入口既不迟到也不误留在聊天页。
     */
    private static void reconcile() {
        if (!WmPrefs.isCornerMenu()) { hideDecor(); return; }
        Activity a = com.leshao.v3.MainHook.currentActivity();
        if (a == null || a.isFinishing()
                || (Build.VERSION.SDK_INT >= 17 && a.isDestroyed())) {
            return;
        }
        if (!LAUNCHER_UI.equals(a.getClass().getName())) { hideDecor(); return; }
        sHost = new WeakReference<>(a);
        boolean inChat = isChatInputVisible(a);
        if (sInChat != inChat) sInChat = inChat;
        sync(a);
    }

    private static void sync(Activity a) {
        if (a == null) return;
        if (!WmPrefs.isCornerMenu()) { hideDecor(); return; }
        if (!LAUNCHER_UI.equals(a.getClass().getName()) || sInChat) {
            hideDecor();
            return;
        }
        showDecor(a);
    }

    private static void showDecor(Activity a) {
        try {
            View decorV = a.getWindow() == null ? null : a.getWindow().getDecorView();
            if (!(decorV instanceof ViewGroup)) return;
            ViewGroup decor = (ViewGroup) decorV;

            View btn = decor.findViewWithTag(DECOR_TAG);
            if (btn == null) {
                View v = buildDecorButton(a);
                v.setTag(DECOR_TAG);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(a, 34), dp(a, 34));
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.leftMargin = dp(a, 6);
                lp.topMargin = statusBarHeight(a) + dp(a, 5);
                decor.addView(v, lp);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    v.setElevation(dp(a, 12));
                }
                btn = v;
                if (!sDecorLogged) {
                    sDecorLogged = true;
                    LogWriter.log(TAG, "decor entry attached topMargin=" + lp.topMargin
                            + " host=" + decor.getClass().getName());
                }
            }
            if (btn.getVisibility() != View.VISIBLE) btn.setVisibility(View.VISIBLE);
            btn.bringToFront();
            sDecorBtn = btn;
            sInjected = true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "showDecor err: " + t);
        }
    }

    private static void hideDecor() {
        View v = sDecorBtn;
        if (v != null && v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
    }

    private static void removeDecor() {
        View v = sDecorBtn;
        sDecorBtn = null;
        if (v != null && v.getParent() instanceof ViewGroup) {
            try { ((ViewGroup) v.getParent()).removeView(v); } catch (Throwable ignored) {}
        }
    }

    private static boolean isChatInputVisible(Activity a) {
        try {
            View decor = a.getWindow() == null ? null : a.getWindow().getDecorView();
            return decor != null && hasShownChatInput(decor);
        } catch (Throwable t) {
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

    private static View buildDecorButton(Context ctx) {
        ImageView iv = new ImageView(ctx);
        iv.setImageBitmap(CornerMenu.entryIconBitmap(ctx));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setContentDescription("模块入口");
        iv.setClickable(true);
        iv.setOnClickListener(v -> showMenu(asActivity(v.getContext())));
        return iv;
    }

    /** 点击左上角入口弹出快捷菜单：仅保留「模块主页」。
     *  免打扰/解除免打扰入口已移除，统一走右上角「+」菜单。
     *  菜单界面适配模块 M3 主题配色（surface 背景 + primary 标题 + 行按压反馈）。 */
    public static void showMenu(final Activity act) {
        if (act == null || act.isFinishing()) return;
        final String[] items = {"模块主页"};
        try {
            final float d = act.getResources().getDisplayMetrics().density;
            Context ctx = act;

            LinearLayout panel = new LinearLayout(ctx);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding((int) (18 * d), (int) (10 * d), (int) (18 * d), (int) (10 * d));
            panel.setBackgroundColor(AppColors.windowBg());

            TextView title = new TextView(ctx);
            title.setText("乐少模块");
            title.setTextSize(18);
            title.setTextColor(AppColors.primary());
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setPadding((int) (8 * d), (int) (8 * d), (int) (8 * d), (int) (6 * d));
            panel.addView(title);

            final AlertDialog[] ref = {null};
            for (int i = 0; i < items.length; i++) {
                final int which = i;
                TextView item = new TextView(ctx);
                item.setText(items[i]);
                item.setTextSize(15);
                item.setTextColor(AppColors.onSurface());
                item.setPadding((int) (8 * d), (int) (12 * d), (int) (8 * d), (int) (12 * d));
                item.setBackground(CandyUi.rowPressBg(ctx));
                item.setOnClickListener(v -> {
                    try {
                        if (ref[0] != null && ref[0].isShowing()) ref[0].dismiss();
                    } catch (Throwable ignored) {}
                    handleMenuClick(act, which);
                });
                panel.addView(item);
            }

            ref[0] = new AlertDialog.Builder(act)
                    .setView(panel)
                    .setCancelable(true)
                    .create();
            if (ref[0].getWindow() != null) {
                ref[0].getWindow().setBackgroundDrawable(new ColorDrawable(AppColors.windowBg()));
            }
            ref[0].show();
        } catch (Throwable e) {
            LogWriter.log(TAG, "showMenu err: " + e);
        }
    }

    /** 菜单项点击处理。 */
    private static void handleMenuClick(Activity act, int which) {
        try {
            switch (which) {
                case 0:
                    com.leshao.v3.ui.MainActivity.open(act);
                    break;
                default:
                    break;
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "menu item err: " + e);
        }
    }

    private static int statusBarHeight(Context ctx) {
        try {
            int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return ctx.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {}
        return dp(ctx, 24);
    }

    private static void openModule(Context ctx) {
        try {
            Activity act = asActivity(ctx);
            if (act != null) {
                com.leshao.v3.ui.MainActivity.open(act);
            } else {
                android.content.Intent i = new android.content.Intent();
                i.setClassName("com.leshao.v3", "com.leshao.v3.ui.MainActivity");
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "openModule err: " + e);
        }
    }

    private static Activity asActivity(Context ctx) {
        Context c = ctx;
        while (c != null) {
            if (c instanceof Activity) return (Activity) c;
            if (!(c instanceof android.content.ContextWrapper)) return null;
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    private static Class<?> findClass(String name, ClassLoader cl) {
        try { return XposedHelpers.findClass(name, cl); } catch (Throwable t) { return null; }
    }

    private static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }
}
