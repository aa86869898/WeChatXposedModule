package com.leshao.v3.hook;

import android.content.SharedPreferences;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 禁止微信热更新（版本升级 + Tinker 热补丁）
 *
 * 严格参照根目录《禁止热更新.java》的锚点表，改用本项目 DexKitHelper
 * （findClassesByString / findMethodsByString）定位类与方法，再 XposedBridge.hookMethod。
 *
 * 开关配置 key: ls_wp_blockupdate (ModuleConfig.blockWechatUpdate)
 * 阻断点：
 *   V1 Updater.f(int)                     -> setResult(null)
 *   V2 NetSceneGetUpdateInfo.onGYNetEnd   -> setResult(null)
 *   V3 MMErrorProcessor.updateRequired     -> setResult(Boolean.FALSE)
 *   V4 UpdaterManager download/handleCommand -> setResult(null)
 *   T1 SubCoreHotpatch.onAccountInitialized -> setResult(null)
 *   T2 TinkerBootsActivateListener.callback -> setResult(Boolean.FALSE)
 *   T3 TinkerBootsSysCmdMsgListener.U0     -> setResult(null)
 *   T4 TinkerClient.fetchPatchUpdate       -> setResult(null)
 *   T5 MergeHdiffApkService.onHandleIntent  -> setResult(null)
 *   T6 CTinkerInstaller.c/f                -> setResult(Integer.valueOf(-1))
 */
public class WeChatUpdateBlocker {

    private static final String TAG = "WXUpdateBlocker";
    private static final String PREF_ENABLED = "ls_wp_blockupdate";

    private static volatile boolean sEnabled = false;
    private static volatile boolean sHooked = false;
    /**
     * 版本更新/Tinker 热补丁阻断涉及大量 DexKit 全量搜索(单次可达数秒),
     * 参照 AntiRecallHook 的修复模式: hook() 只做入队, 搜索与安装全部放到后台线程,
     * 避免阻塞 HookManager.activateAll 串行循环导致后续任务饿死。
     */
    private static final java.util.concurrent.ExecutorService sExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "wx-update-blocker");
                t.setDaemon(true);
                return t;
            });

    private WeChatUpdateBlocker() {}

    public static boolean isEnabled() { return sEnabled; }

    public static void setEnabled(boolean v) {
        sEnabled = v;
        SharedPreferences prefs = ContextManager.getPrefs();
        if (prefs != null) {
            prefs.edit().putBoolean(PREF_ENABLED, v).apply();
        }
    }

    public static void updateConfig(SharedPreferences prefs) {
        try {
            sEnabled = prefs != null && prefs.getBoolean(PREF_ENABLED, true);
        } catch (Throwable t) {
            sEnabled = true;
        }
    }

    /** hook 入口：应在 DexKit 扫描完成后、onReady 子线程中调用 */
    public static void hook(final ClassLoader cl) {
        try {
            SharedPreferences prefs = ContextManager.getPrefs();
            updateConfig(prefs);
            if (!sEnabled) {
                LogWriter.log(TAG, "hook: disabled");
                return;
            }
            if (sHooked) return;
            sHooked = true;

            LogWriter.log(TAG, "hook: install...");
            // 阻断点依赖 DexKit 全量搜索, 单次可达数秒, 必须放到后台线程执行,
            // 让 HookManager.activateAll 立即返回, 不阻塞后续任务。
            sExecutor.execute(() -> {
                try {
                    blockVersionUpdate(cl);
                    blockTinkerHotPatch(cl);
                    LogWriter.log(TAG, "hook: done");
                } catch (Throwable t) {
                    LogWriter.log(TAG, "hook err: " + t);
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook err: " + t);
        }
    }

    // ==================== 通用定位 ====================

    private static String firstClassByStrings(ClassLoader cl, String... anchors) {
        for (String kw : anchors) {
            try {
                List<String> cands = DexKitHelper.findClassesByString(cl, kw);
                if (cands != null && !cands.isEmpty()) {
                    LogWriter.log(TAG, "class hit: " + cands.get(0) + " via '" + kw + "'");
                    return cands.get(0);
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 在目标类中查找方法名匹配、且参数个数==paramCount 的方法并 hook（before 阶段 setResult） */
    private static boolean hookByName(ClassLoader cl, String clsName, String methodName,
                                   int paramCount, Object blockResult, String tag) {
        if (clsName == null) return false;
        try {
            Class<?> c = cl.loadClass(clsName);
            boolean hooked = false;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                if (paramCount >= 0 && m.getParameterCount() != paramCount) continue;
                m.setAccessible(true);
                final String t = tag;
                final Object res = blockResult;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            LogWriter.log(TAG, "[" + t + "] block " + m.getName());
                        } catch (Throwable ignored) {}
                        param.setResult(res);
                    }
                });
                hooked = true;
                LogWriter.log(TAG, "hooked " + clsName + "." + m.getName()
                    + "(" + m.getParameterCount() + ") as " + tag);
            }
            if (!hooked) LogWriter.log(TAG, "[WARN] " + tag + ": no method " + methodName
                + " in " + clsName);
            return hooked;
        } catch (Throwable t) {
            LogWriter.log(TAG, "[WARN] " + tag + ": class/method err " + t);
            return false;
        }
    }

    /**
     * v3.0.123 兜底: 当缓存类名/短名定位失败时，用 DexKit 在**全局**按方法字符串锚点搜索，
     * 命中指定方法名的方法并 hook。用于 V2/T1/T6b 等因类名漂移而失效的阻断点。
     */
    private static void hookByGlobalMethodStrings(ClassLoader cl, String anchor,
                                                  String methodName, Object blockResult,
                                                  String tag) {
        try {
            List<String> sigs = DexKitHelper.findMethodsByString(cl, null, anchor);
            if (sigs == null || sigs.isEmpty()) {
                LogWriter.log(TAG, "[WARN] " + tag + ": no method strings '" + anchor + "' anywhere");
                return;
            }
            int hooked = 0;
            for (String sig : sigs) {
                String[] parsed = parseSig(sig);
                if (parsed == null) continue;
                if (methodName != null && !methodName.equals(parsed[1])) continue;
                try {
                    Class<?> c = cl.loadClass(parsed[0]);
                    for (Method m : c.getDeclaredMethods()) {
                        if (!m.getName().equals(methodName)) continue;
                        m.setAccessible(true);
                        final String t = tag;
                        final Object res = blockResult;
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    LogWriter.log(TAG, "[" + t + "] block " + m.getName());
                                } catch (Throwable ignored) {}
                                param.setResult(res);
                            }
                        });
                        hooked++;
                        LogWriter.log(TAG, "hooked " + parsed[0] + "." + methodName
                                + " as " + tag + " (global)");
                    }
                } catch (Throwable ignored) {}
            }
            if (hooked == 0) LogWriter.log(TAG, "[WARN] " + tag
                    + ": global found '" + anchor + "' but no hookable method "
                    + (methodName == null ? "(any)" : methodName));
        } catch (Throwable t) {
            LogWriter.log(TAG, "[WARN] " + tag + " global err: " + t);
        }
    }

    private static String[] parseSig(String sig) {
        if (sig == null) return null;
        int lp = sig.indexOf('(');
        if (lp <= 0) return null;
        String head = sig.substring(0, lp);
        int dot = head.lastIndexOf('.');
        if (dot <= 0 || dot >= head.length() - 1) return null;
        return new String[]{ head.substring(0, dot), head.substring(dot + 1) };
    }

    /** 在目标类中按方法字符串特征定位方法（DexKit），再 hook */
    private static void hookByStrings(ClassLoader cl, String clsName, String methodAnchor,
                                      int paramCount, Object blockResult, String tag) {
        if (clsName == null) return;
        try {
            List<String> sigs = DexKitHelper.findMethodsByString(cl, clsName, methodAnchor);
            if (sigs == null || sigs.isEmpty()) {
                LogWriter.log(TAG, "[WARN] " + tag + ": no method strings '" + methodAnchor + "' in " + clsName);
                return;
            }
            Class<?> c = cl.loadClass(clsName);
            int hooked = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (paramCount >= 0 && m.getParameterCount() != paramCount) continue;
                final Object res = blockResult;
                final String t = tag;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            LogWriter.log(TAG, "[" + t + "] block " + m.getName());
                        } catch (Throwable ignored) {}
                        param.setResult(res);
                    }
                });
                hooked++;
            }
            LogWriter.log(TAG, "hooked " + clsName + " methods(" + hooked + ") as " + tag);
        } catch (Throwable t) {
            LogWriter.log(TAG, "[WARN] " + tag + " err: " + t);
        }
    }

    /** 查找类中全部满足签名的静态方法并 hook（返回类型与 paramCount 匹配才作为备选） */
    private static void hookByClassStrings(ClassLoader cl, String anchor, String fallbackCls,
                                           String methodName, int paramCount,
                                           Object blockResult, String tag) {
        String clsName = firstClassByStrings(cl, anchor);
        if (clsName == null) clsName = fallbackCls;
        if (clsName == null) {
            LogWriter.log(TAG, "[WARN] " + tag + ": class not found");
            return;
        }
        if (anchor != null) {
            hookByStrings(cl, clsName, anchor, paramCount, blockResult, tag);
        } else {
            hookByName(cl, clsName, methodName, paramCount, blockResult, tag);
        }
    }

    // ==================== V. 版本更新阻断 ====================

    private static void blockVersionUpdate(ClassLoader cl) {
        // V1: Updater.f(int)
        hookByClassStrings(cl, "MicroMsg.Updater", "com.tencent.mm.sandbox.updater.Updater",
                "f", 1, null, "V1");
        // V2: NetSceneGetUpdateInfo.onGYNetEnd (缓存优先, 避免普通重启触发 DexKit 搜索)
        String ns = DexKitHelper.ngetNetSceneUpdateInfo();
        if (ns == null || ns.isEmpty()) {
            ns = firstClassByStrings(cl, "MicroMsg.NetSceneGetUpdateInfo");
        }
        if (ns != null) {
            boolean ok = hookByName(cl, ns, "onGYNetEnd", 3, null, "V2");
            // 缓存类名方法签名漂移时，全局按方法字符串锚点兜底
            if (!ok) hookByGlobalMethodStrings(cl, "MicroMsg.NetSceneGetUpdateInfo", "onGYNetEnd", null, "V2g");
        } else {
            hookByGlobalMethodStrings(cl, "MicroMsg.NetSceneGetUpdateInfo", "onGYNetEnd", null, "V2g");
        }
        // V3: MMErrorProcessor.updateRequired(boolean 返回)
        hookByClassStrings(cl, "MicroMsg.MMErrorProcessor", "com.tencent.mm.ui.rc",
                "b", -1, Boolean.FALSE, "V3");
        // V4: UpdaterManager download/handleCommand (类名缓存优先, 方法字符串搜索仍走 hookByStrings)
        String um = DexKitHelper.ngetUpdaterManager();
        if (um == null || um.isEmpty()) {
            um = firstClassByStrings(cl, "MicroMsg.UpdaterManager");
        }
        if (um != null) {
            hookByStrings(cl, um, "MicroMsg.UpdaterManager", -1, null, "V4");
        }
    }

    // ==================== T. Tinker 热补丁阻断 ====================

    private static void blockTinkerHotPatch(ClassLoader cl) {
        // T1: SubCoreHotpatch.onAccountInitialized
        hookByClassStrings(cl, "MicroMsg.Tinker.SubCoreHotpatch", "ge3.a0",
                "onAccountInitialized", -1, null, "T1");
        // T1 兜底: 全局按方法字符串锚点定位（类名/短名漂移时仍可命中）
        hookByGlobalMethodStrings(cl, "MicroMsg.Tinker.SubCoreHotpatch",
                "onAccountInitialized", null, "T1g");
        // T2: TinkerBootsActivateListener.callback
        hookByClassStrings(cl, "MicroMsg.Tinker.TinkerBootsActivateListener",
                "com.tencent.mm.plugin.hp.model.TinkerBootsActivateListener",
                "callback", -1, Boolean.FALSE, "T2");
        // T3: TinkerBootsSysCmdMsgListener.U0
        hookByClassStrings(cl, ".sysmsg.boots.xmlkey", "ge3.q0",
                "U0", -1, null, "T3");
        // T4: TinkerClient.fetchPatchUpdate
        hookByClassStrings(cl, "Tinker.TinkerClient", "x76.a",
                null, -1, null, "T4");
        // T5: MergeHdiffApkService.onHandleIntent
        hookByClassStrings(cl, "Tinker.MergeHdiffApkService.HdiffApk",
                "com.tencent.mm.plugin.hp.mmdiff.MergeHdiffApkService",
                "onHandleIntent", -1, null, "T5");
        // T6: CTinkerInstaller.c / f
        hookByClassStrings(cl, "MicroMsg.Tinker.CTinkerInstaller", "ee3.a",
                "c", -1, Integer.valueOf(-1), "T6");
        // T6b: CTinkerInstaller.f (缓存优先, 避免普通重启触发 DexKit 搜索)
        String ct = DexKitHelper.ngetCtinkerInstaller();
        if (ct == null || ct.isEmpty()) {
            ct = firstClassByStrings(cl, "MicroMsg.Tinker.CTinkerInstaller");
        }
        boolean t6b = false;
        if (ct != null) t6b = hookByName(cl, ct, "f", -1, Integer.valueOf(-1), "T6b");
        // T6b 兜底: 类名/方法名漂移时，按锚点全局搜索该 Tinker 类内任意方法并阻断（方法名已混淆，不再按 "f" 过滤）
        if (!t6b) hookByGlobalMethodStrings(cl, "MicroMsg.Tinker.CTinkerInstaller",
                null, Integer.valueOf(-1), "T6bg");
    }

    // ==================== 便捷: 类名是否已加载（日志辅助） ====================

    static boolean classExists(ClassLoader cl, String cn) {
        try {
            cl.loadClass(cn);
            return true;
        } catch (Throwable ignored) {}
        return false;
    }
}
