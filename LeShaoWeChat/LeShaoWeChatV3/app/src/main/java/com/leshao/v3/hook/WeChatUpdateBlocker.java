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
            blockVersionUpdate(cl);
            blockTinkerHotPatch(cl);
            LogWriter.log(TAG, "hook: done");
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
    private static void hookByName(ClassLoader cl, String clsName, String methodName,
                                   int paramCount, Object blockResult, String tag) {
        if (clsName == null) return;
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
        } catch (Throwable t) {
            LogWriter.log(TAG, "[WARN] " + tag + ": class/method err " + t);
        }
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
        // V2: NetSceneGetUpdateInfo.onGYNetEnd
        String ns = firstClassByStrings(cl, "MicroMsg.NetSceneGetUpdateInfo");
        if (ns != null) hookByName(cl, ns, "onGYNetEnd", 3, null, "V2");
        // V3: MMErrorProcessor.updateRequired(boolean 返回)
        hookByClassStrings(cl, "MicroMsg.MMErrorProcessor", "com.tencent.mm.ui.rc",
                "b", -1, Boolean.FALSE, "V3");
        // V4: UpdaterManager download/handleCommand
        String um = firstClassByStrings(cl, "MicroMsg.UpdaterManager");
        if (um != null) {
            hookByStrings(cl, um, "MicroMsg.UpdaterManager", -1, null, "V4");
        }
    }

    // ==================== T. Tinker 热补丁阻断 ====================

    private static void blockTinkerHotPatch(ClassLoader cl) {
        // T1: SubCoreHotpatch.onAccountInitialized
        hookByClassStrings(cl, "MicroMsg.Tinker.SubCoreHotpatch", "ge3.a0",
                "onAccountInitialized", -1, null, "T1");
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
        String ct = firstClassByStrings(cl, "MicroMsg.Tinker.CTinkerInstaller");
        if (ct != null) hookByName(cl, ct, "f", -1, Integer.valueOf(-1), "T6b");
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
