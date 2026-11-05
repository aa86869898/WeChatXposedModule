package com.leshao.v3.hook;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import com.leshao.v3.LogWriter;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * M3 玻璃化风格主题适配（微信界面毛玻璃）。
 *
 * <p>原理（Android 12+ 系统级毛玻璃，无需微信内部方法）：</p>
 * <ol>
 *   <li>hook Activity.onCreate/onResume，拿到微信 Activity 的 Window；</li>
 *   <li>{@code WindowManager.LayoutParams.setBackgroundBlurRadius()} + {@code FLAG_BLUR_BEHIND}
 *       —— 系统对窗口后面的壁纸/内容做真实高斯模糊（M3 毛玻璃核心）；</li>
 *   <li>窗口背景与 DecorView 根内容背景替换为半透明白，让壁纸透出，形成磨砂玻璃质感；</li>
 *   <li>首次处理每个界面时整树打印 DecorView 视图树（类名/背景/尺寸），方便后续按树找方法
 *       继续细化（状态栏、导航栏、列表、输入栏等局部玻璃化）。</li>
 * </ol>
 *
 * <p>开关：HookConfig key = {@value #KEY_ENABLED}（默认开启）。</p>
 */
public final class M3GlassThemeHook {

    private static final String TAG = "M3GlassTheme";
    /** 模块设置开关 key（HookConfig 默认值见 HookConfig.getDefault） */
    public static final String KEY_ENABLED = "m3_glass_theme";
    /** 背景模糊半径（px），M3 推荐 24-40 */
    private static final int BLUR_RADIUS = 32;
    /** 窗口/根内容半透明白色 alpha（0xB3=70%，0x8C=55%，0x59=35%） */
    private static final int ROOT_TRANSLUCENT_COLOR = 0xB3FFFFFF;
    /** 视图树打印最大深度 */
    private static final int MAX_DUMP_DEPTH = 14;
    /** 是否打印视图树（调试用，默认开，首次每个 Activity 打一次） */
    private static final boolean DUMP_VIEW_TREE = true;

    private static volatile boolean sHooked = false;
    private static volatile String sTargetProcess = "com.tencent.mm";
    /** 已处理过的 Activity（弱引用，防重复处理 + 防泄漏） */
    private static final Map<Activity, Boolean> sProcessed =
            Collections.synchronizedMap(new IdentityHashMap<Activity, Boolean>());
    /** 已打印过视图树的 Activity 引用（弱引用，进程内存增长极小） */
    private static final Set<WeakReference<Activity>> sDumped =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<WeakReference<Activity>, Boolean>()));

    private M3GlassThemeHook() {}

    /**
     * 安装 M3 玻璃化 hook。
     *
     * @param cl          微信 ClassLoader
     * @param processName 当前进程名（仅主进程 {@code com.tencent.mm} 注入，跳过 appbrand/push 子进程）
     */
    public static void hook(ClassLoader cl, String processName) {
        if (processName != null && !processName.isEmpty()) {
            sTargetProcess = processName;
        }
        if (sHooked) return;
        sHooked = true;
        try {
            // Activity.onCreate：尽早应用，避免首帧闪烁
            XposedBridge.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        applyGlass((Activity) param.thisObject);
                    } catch (Throwable ignored) { }
                }
            });
            // onResume：Activity 从后台回前台时确保属性还在（部分主题/窗口属性会被重置）
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        applyGlass((Activity) param.thisObject);
                    } catch (Throwable ignored) { }
                }
            });
            LogWriter.log(TAG, "hook installed process=" + sTargetProcess
                    + " api=" + Build.VERSION.SDK_INT
                    + " blurSupported=" + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S));
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook err: " + t.getMessage());
        }
    }

    /** 供其它 hook 查询是否启用（例如 ChatGroupUiInjector 可联动列表背景透明化） */
    public static boolean isEnabled() {
        return HookConfig.isEnabled(KEY_ENABLED);
    }

    private static void applyGlass(Activity act) {
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        if (!isEnabled()) return;
        // 只处理微信进程的 Activity
        String pkg = safeGetPackageName(act);
        if (pkg == null || !pkg.contains("tencent.mm")) return;
        // 子进程（appbrand）的 Activity 也属于 tencent.mm 包，但只在主进程注入
        if (!sTargetProcess.equals("com.tencent.mm")) return;

        if (sProcessed.containsKey(act)) return;

        try {
            Window w = act.getWindow();
            if (w == null) return;

            // 1. 窗口背景 -> 半透明白（让壁纸透出）
            w.setBackgroundDrawable(new ColorDrawable(ROOT_TRANSLUCENT_COLOR));

            // 2. 系统级毛玻璃：模糊窗口背后的壁纸（API 31+，反射调用以兼容低 compileSdk）
            // Android 17 起 setBackgroundBlurRadius 可能被移除，改用 backgroundBlurRadius 字段直写，
            // 并保留 setBackgroundBlurRadius / setBlurBehindRadius 两级兜底。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                WindowManager.LayoutParams lp = w.getAttributes();
                boolean blurRadiusSet = false;
                try {
                    java.lang.reflect.Method m = WindowManager.LayoutParams.class
                            .getMethod("setBackgroundBlurRadius", int.class);
                    m.invoke(lp, BLUR_RADIUS);
                    blurRadiusSet = true;
                } catch (NoSuchMethodException nsme) {
                    try {
                        java.lang.reflect.Method m2 = WindowManager.LayoutParams.class
                                .getMethod("setBlurBehindRadius", int.class);
                        m2.invoke(lp, BLUR_RADIUS);
                        blurRadiusSet = true;
                    } catch (Throwable ignored) {}
                } catch (Throwable t) {
                    LogWriter.log(TAG, "setBackgroundBlurRadius err: " + t.getMessage());
                }
                if (!blurRadiusSet) {
                    try {
                        java.lang.reflect.Field f = WindowManager.LayoutParams.class
                                .getDeclaredField("backgroundBlurRadius");
                        f.setAccessible(true);
                        f.setInt(lp, BLUR_RADIUS);
                        blurRadiusSet = true;
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "backgroundBlurRadius field err: " + t.getMessage());
                    }
                }
                lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                w.setAttributes(lp);
            }

            // 3. DecorView 背景半透明
            View decor = w.getDecorView();
            if (decor != null) {
                decor.setBackgroundColor(ROOT_TRANSLUCENT_COLOR);
                // 4. 只把最顶层内容容器（DecorView 的直接子 View）背景半透明化，
                //    不递归子 View，避免破坏列表项/气泡/输入栏等细节。
                //    v3.0.513: 资料/设置/偏好列表页保持内容不透明（半透明列表 + 浅色壁纸
                //    会整页发白像白屏），仅保留窗口级毛玻璃。
                String cn = act.getClass().getName();
                boolean keepOpaque = cn != null && (cn.contains("ContactInfoUI")
                        || cn.contains("Preference") || cn.contains("Profile")
                        || cn.contains("Setting") || cn.contains("RoomInfo"));
                translucentRootChild(decor, keepOpaque);
                // 5. 整树打印视图树（仅一次），便于日志中找方法继续细化
                if (DUMP_VIEW_TREE) dumpViewTreeOnce(act, decor);
            }

            sProcessed.put(act, Boolean.TRUE);
            LogWriter.log(TAG, "glass applied " + act.getClass().getSimpleName()
                    + " blur=" + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S));
        } catch (Throwable t) {
            LogWriter.log(TAG, "applyGlass err " + act.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** 将 DecorView 第一个内容子 View 的不透明纯色背景替换为半透明白 */
    private static void translucentRootChild(View decor, boolean keepOpaque) {
        if (!(decor instanceof ViewGroup)) return;
        ViewGroup vg = (ViewGroup) decor;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            if (child == null) continue;
            if (!keepOpaque) {
                Drawable bg = child.getBackground();
                if (bg == null || (bg instanceof ColorDrawable && ((ColorDrawable) bg).getAlpha() == 255)) {
                    try {
                        child.setBackgroundColor(ROOT_TRANSLUCENT_COLOR);
                    } catch (Throwable ignored) { }
                }
            }
            break; // 只处理第一个内容容器
        }
    }

    /** 每个 Activity 首次处理时整树打印一次视图树 */
    private static void dumpViewTreeOnce(Activity act, View decor) {
        // 用 IdentityHashMap 语义的弱引用集合去重
        synchronized (sDumped) {
            for (WeakReference<Activity> ref : sDumped) {
                if (ref != null && ref.get() == act) return;
            }
            sDumped.add(new WeakReference<Activity>(act));
        }
        LogWriter.log(TAG, "==== ViewTree " + act.getClass().getName() + " ====");
        dumpTree(decor, 0, MAX_DUMP_DEPTH);
        LogWriter.log(TAG, "==== ViewTree end ====");
    }

    private static void dumpTree(View v, int depth, int maxDepth) {
        if (v == null || depth > maxDepth) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName());
        Object id = null;
        try { id = v.getId() != View.NO_ID ? v.getResources().getResourceEntryName(v.getId()) : null; } catch (Throwable ignored) { }
        if (id != null) sb.append(" id=").append(id);
        Drawable bg = v.getBackground();
        String bgDesc = "null";
        if (bg instanceof ColorDrawable) {
            ColorDrawable cd = (ColorDrawable) bg;
            bgDesc = String.format("#%08X", cd.getColor());
        } else if (bg != null) {
            bgDesc = bg.getClass().getSimpleName();
        }
        sb.append(" bg=").append(bgDesc);
        sb.append(" ").append(v.getWidth()).append("x").append(v.getHeight());
        LogWriter.log(TAG, sb.toString());
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                dumpTree(vg.getChildAt(i), depth + 1, maxDepth);
            }
        }
    }

    private static String safeGetPackageName(Activity act) {
        try {
            return act.getPackageName();
        } catch (Throwable t) {
            return null;
        }
    }
}