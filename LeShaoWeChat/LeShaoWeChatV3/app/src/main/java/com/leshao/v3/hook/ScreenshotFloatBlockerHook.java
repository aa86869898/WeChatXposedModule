package com.leshao.v3.hook;

import android.content.SharedPreferences;
import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 屏蔽微信截图后右下角「转发截图」浮标（v3.0.516 重写）。
 *
 * <p>逆向结论（微信 8.0.79，jadx 验证）：</p>
 * <pre>
 *   com.tencent.mm.ui.feature.api.screenshot.y0  = ScreenShotShareService
 *     Pj(Activity, ScreenShotShareConfig, View.OnClickListener) = showShareTongue
 *       ├─ 已有浮标：重置 alpha/visibility 并重新挂 5s 自动隐藏倒计时
 *       └─ 首次截图：创建并添加两个 WindowManager 悬浮窗
 *           ├─ "com.tencent.mm/.ScreenShotShareTongue"  ← 右下角转发浮标 (type=2038, gravity=RIGHT|BOTTOM)
 *           └─ "com.tencent.mm/.ScreenShotShareMask"    ← 底部遮罩
 * </pre>
 *
 * <p>v3.0.515 失效原因：R8 将方法名改为 Pj，签名串不再包含 showShareTongue，
 * 旧的 {@code sig.contains("showShareTongue")} 过滤把所有 DexKit 命中全部丢弃；
 * 类名锚点又误命中 {@code com.tencent.mm.plugin.appbrand.screenshot.o}。
 * 本版：方法体字符串锚点命中后【不再过滤方法名】，直接按参数个数安装；
 * 权威类名 {@code com.tencent.mm.ui.feature.api.screenshot.y0} 作为首选项兜底。</p>
 */
public final class ScreenshotFloatBlockerHook {

    private static final String TAG = "ShotFloatBlock";
    public static final String PREF_ENABLED = "screenshot_float_block";

    /** showShareTongue 方法体专用字符串（“浮标已存在”分支），唯一性最高。 */
    private static final String ANCHOR_ALREADY =
            "showShareTongue, shareTongue already showing, reset onClick & countDown";
    /** showShareTongue 方法体专用字符串（“首次创建”分支的异常日志）。 */
    private static final String ANCHOR_NO_WM =
            "showShareTongue, unable to get windowManager";
    /** 类级 xlog TAG 锚点，用于诊断（不再作为首选项，误命中 appbrand）。 */
    private static final String ANCHOR_TAG = "MicroMsg.ScreenShotShareService";
    /** 权威类名兜底（jadx 当前版本确认）。 */
    private static final String FALLBACK_CLASS = "com.tencent.mm.ui.feature.api.screenshot.y0";
    /** 预期参数个数：showShareTongue = (Activity, ScreenShotShareConfig, OnClickListener) */
    private static final int EXPECT_PARAM_COUNT = 3;

    private ScreenshotFloatBlockerHook() {
    }

    public static boolean isEnabled() {
        return HookConfig.isEnabled(PREF_ENABLED);
    }

    public static void setEnabled(boolean v) {
        SharedPreferences sp = ContextManager.getPrefs();
        if (sp != null) {
            sp.edit().putBoolean(PREF_ENABLED, v).apply();
        }
    }

    public static void hook(final ClassLoader cl) {
        if (!isEnabled()) {
            LogWriter.log(TAG, "hook: disabled (key=" + PREF_ENABLED + ")");
            return;
        }
        Set<String> hooked = new HashSet<>();
        boolean any = false;

        // 1) 方法体字符串锚点直接定位 showShareTongue（最优路径，方法名 R8 后不匹配过滤）
        for (String anchor : new String[]{ANCHOR_ALREADY, ANCHOR_NO_WM}) {
            try {
                List<String> sigs = DexKitHelper.findMethodsByString(cl, null, anchor);
                if (sigs == null || sigs.isEmpty()) {
                    LogWriter.log(TAG, "anchor no hit: " + anchor);
                    continue;
                }
                for (String sig : sigs) {
                    if (sig == null) continue;
                    MethodInfo mi = parseSig(sig);
                    if (mi == null) {
                        LogWriter.log(TAG, "anchor unparseable: " + sig);
                        continue;
                    }
                    // 目标方法固定 3 参：Activity, ScreenShotShareConfig, OnClickListener
                    if (mi.paramCount != EXPECT_PARAM_COUNT) {
                        LogWriter.log(TAG, "anchor sig param=" + mi.paramCount
                                + " skip: " + sig);
                        continue;
                    }
                    any |= installByInfo(cl, mi, hooked);
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "anchor err (" + anchor + "): " + t);
            }
        }

        // 2) 权威类名兜底：直接加载 y0，遍历 3 参方法，用 DexKit 签名核对方法体字符串
        if (!any) {
            any |= installByClassFallback(cl, FALLBACK_CLASS, hooked);
        }

        LogWriter.log(TAG, "hook done, installed=" + any
                + " hooked=" + hooked.size());
    }

    // ==================== 安装 ====================

    /** 按签名串（类名.方法名(参数类型)）在所有候选 ClassLoader 上安装。 */
    private static boolean installByInfo(ClassLoader cl, MethodInfo mi, Set<String> hooked) {
        boolean ok = false;
        for (ClassLoader loader : HookUtil.candidateLoaders(cl)) {
            try {
                Class<?> c = loader.loadClass(mi.clsName);
                if (c == null) continue;
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(mi.methodName)) continue;
                    if (m.getParameterCount() != EXPECT_PARAM_COUNT) continue;
                    String key = c.getName() + "#" + m.getName() + "#" + m.getParameterCount();
                    if (!hooked.add(key)) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, blockHook());
                    ok = true;
                    LogWriter.log(TAG, "hooked " + c.getName() + "." + m.getName()
                            + " via anchor (" + mi.src + ")");
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "installByInfo skip " + mi.clsName + ": " + t);
            }
        }
        return ok;
    }

    /** 权威类名兜底：遍历 3 参方法，方法体含 ANCHOR_ALREADY/ANCHOR_NO_WM 者即目标。 */
    private static boolean installByClassFallback(ClassLoader cl, String clsName,
                                                Set<String> hooked) {
        boolean ok = false;
        for (ClassLoader loader : HookUtil.candidateLoaders(cl)) {
            Class<?> c;
            try {
                c = loader.loadClass(clsName);
            } catch (Throwable t) {
                LogWriter.log(TAG, "load " + clsName + " failed: " + t);
                continue;
            }
            if (c == null) continue;
            for (Method m : c.getDeclaredMethods()) {
                try {
                    if (m.getParameterCount() != EXPECT_PARAM_COUNT) continue;
                    String key = c.getName() + "#" + m.getName() + "#" + m.getParameterCount();
                    if (!hooked.contains(key)) {
                        if (!methodUsesAnchor(loader, m, ANCHOR_ALREADY)
                                && !methodUsesAnchor(loader, m, ANCHOR_NO_WM)) {
                            continue;
                        }
                    }
                    if (!hooked.add(key)) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, blockHook());
                    ok = true;
                    LogWriter.log(TAG, "hooked " + c.getName() + "." + m.getName()
                            + " (class fallback)");
                } catch (Throwable t) {
                    LogWriter.log(TAG, "installByClassFallback method err: " + t);
                }
            }
            if (ok) break;
        }
        return ok;
    }

    /** before 直接 return：阻止浮标/遮罩创建。 */
    private static XC_MethodHook blockHook() {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    LogWriter.log(TAG, "BLOCKED screenshot share tongue");
                } catch (Throwable ignored) {
                }
                param.setResult(null);
            }
        };
    }

    // ==================== 工具 ====================

    /** 解析 “类名.方法名(参数列表)” 签名串。 */
    private static MethodInfo parseSig(String sig) {
        int p = sig.indexOf('(');
        if (p <= 0) return null;
        String decl = sig.substring(0, p);
        int dot = decl.lastIndexOf('.');
        if (dot <= 0) return null;
        String cls = decl.substring(0, dot);
        String name = decl.substring(dot + 1);
        String params = sig.substring(p + 1, sig.length() - 1);
        int pc = params.isEmpty() ? 0 : params.split(",").length;
        return new MethodInfo(cls, name, pc, sig);
    }

    /** 用 DexKit 再查一次该字符串，核对目标方法体确实包含锚点字符串。 */
    private static boolean methodUsesAnchor(ClassLoader loader, Method m, String anchor) {
        try {
            List<String> sigs = DexKitHelper.findMethodsByString(loader, null, anchor);
            if (sigs == null || sigs.isEmpty()) return false;
            String full = m.getDeclaringClass().getName() + "." + m.getName() + "(";
            for (String sig : sigs) {
                if (sig != null && sig.startsWith(full)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static final class MethodInfo {
        final String clsName;
        final String methodName;
        final int paramCount;
        final String src;

        MethodInfo(String clsName, String methodName, int paramCount, String src) {
            this.clsName = clsName;
            this.methodName = methodName;
            this.paramCount = paramCount;
            this.src = src;
        }
    }
}