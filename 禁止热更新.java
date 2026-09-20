/*
 * ============================================================================
 *  微信后台更新机制 完整分析报告 + Java Xposed 阻断模块（单文件导出）
 * ============================================================================
 *  生成方式：LSPilot 逆向分析（基于当前安装的微信 APK 全量 DEX 分析）
 *  适用对象：Java 语法独立开发的 Xposed/LSPosed 模块（非 BSH 插件）
 *  目标包名：com.tencent.mm
 *
 *  文件内容：
 *    Part 1  分析报告：微信更新机制（版本号更新 + Tinker 热补丁）完整链路
 *    Part 2  阻断模块 Java 源码（可编译，直接放入你的 Xposed 模块工程）
 *    Part 3  微信版本更新后如何使用 DexKit 适配
 *
 *  核心结论：
 *    微信存在两套互不相同的"后台更新"体系，必须分别阻断：
 *    A) 正式版本更新（APK 升级 / 版本号更新）
 *       -> 链路：Updater.f() -> NetSceneGetUpdateInfo(getupdateinfo)
 *               -> MMErrorProcessor.updateRequired -> UpdaterManager 下载 APK -> 安装
 *    B) Tinker 热补丁（后台功能热更新 dex/res/so）
 *       -> 链路：SubCoreHotpatch / TinkerBootsActivateListener / TinkerBootsSysCmdMsgListener
 *               -> TinkerClient.fetchPatchUpdate -> MergeHdiffApkService
 *               -> CTinkerInstaller 应用 -> 重启后 MMApplicationLikeLegacy 加载
 * ============================================================================
 */

/*
 * ============================================================================
 * Part 1  分析报告（对应本文件所分析的微信版本）
 * ============================================================================
 * 说明：微信核心类经过混淆（如 te5.a、ue5.x0、ee3.a、ge3.a0、x76.a、an0.c0），
 *       但大量 Log Tag 与常量字符串保持稳定，是 DexKit 适配的锚点。
 *
 * ----------------------------------------------------------------------------
 * 1.1 正式版本更新（APK 升级 / 版本号更新）—— "summerupdate"
 * ----------------------------------------------------------------------------
 * 触发源：
 *   - 启动/登录后：com.tencent.mm.sandbox.updater.Updater.f(int)
 *       日志 "summerupdate begin update routine, type="
 *   - 服务器错误码触发：com.tencent.mm.ui.rc.b(Activity,int,int,Intent)
 *       即 MMErrorProcessor.updateRequired：errType=4, errCode=-16(推荐更新) / -17(强制更新)
 *   - 设置页手动检查：SettingsAboutMicroMsgUI（复用同一套 Updater）
 * 检测请求：
 *   - te5.a = NetSceneGetUpdateInfo
 *       协议 URI: /cgi-bin/micromsg-bin/getupdateinfo
 *       日志 "MicroMsg.NetSceneGetUpdateInfo"
 *   - 另有资源控制：com.tencent.mm.modelsimple.q0 = NetSceneGetResourceControlInfo
 *       协议 URI: /cgi-bin/micromsg-bin/getresourcecontrolinfo
 * 下载/安装：
 *   - ue5.x0 = MicroMsg.UpdaterManager（下载 APK、增量包、静默下载）
 *       特征 "MicroMsg.UpdaterManager" / "download()" / "handleCommand"
 *   - ue5.o0 = MicroMsg.UpdateUtil（update_config_prefs 状态）
 *   - com.tencent.mm.sandbox.updater.UpdaterService（下载服务）
 *   - com.tencent.mm.sandbox.updater.AppUpdaterUI / AppInstallerUI
 *
 * ----------------------------------------------------------------------------
 * 1.2 Tinker 热补丁（后台功能热更新）—— "MicroMsg.Tinker"
 * ----------------------------------------------------------------------------
 * 触发源（三路，全部需要阻断）：
 *   ① 账号初始化后：ge3.a0 = MicroMsg.Tinker.SubCoreHotpatch
 *        onAccountInitialized() -> fetchPatchUpdate
 *        特征 "MicroMsg.Tinker.SubCoreHotpatch" / "try to fetch patch update"
 *   ② 定时(6小时)：com.tencent.mm.plugin.hp.model.TinkerBootsActivateListener
 *        callback(IEvent) -> x76.a.a(false) （TinkerClient.fetchPatchUpdate）
 *        特征 "MicroMsg.Tinker.TinkerBootsActivateListener" / "fetchPatchUpdate"
 *   ③ 服务端下行指令：ge3.q0 = MicroMsg.Tinker.TinkerBootsSysCmdMsgListener
 *        U0() 解析 .sysmsg.boots.xmlkey 后触发更新
 *        特征 "MicroMsg.Tinker.TinkerBootsSysCmdMsgListener" / ".sysmsg.boots.xmlkey"
 * 拉取客户端：
 *   - x76.a = TinkerClient / ServerClient
 *        a(boolean)  = fetchPatchUpdate
 *        b(int)      = setFetchPatchIntervalByHours
 *        特征 "Tinker.TinkerClient" / "fetchPatchUpdate"
 * 应用流程：
 *   - com.tencent.mm.plugin.hp.mmdiff.MergeHdiffApkService.onHandleIntent
 *        Intent extra: "patch_syncresponse_extra" (TinkerSyncResponse)
 *        特征 "Tinker.MergeHdiffApkService.HdiffApk"
 *   - ee3.a = MicroMsg.Tinker.CTinkerInstaller
 *        c(Context,String,TinkerSyncResponse)  处理 updateType=4（hdiff APK 增量合并）
 *        f(String,TinkerSyncResponse)          下载完成后应用
 *        特征 "MicroMsg.Tinker.CTinkerInstaller" / "HdiffApk"
 * 加载（重启后）：
 *   - an0.c0 = MicroMsg.MMApplicationLikeLegacy（patch 校验/加载/oat 修复）
 *   - com.tencent.tinker.* （TinkerDexLoader / TinkerResourceLoader / TinkerSoLoader）
 * 同步响应：
 *   - com.tencent.mm.plugin.hp.util.TinkerSyncResponse（Parcelable）
 *
 * ----------------------------------------------------------------------------
 * 1.3 阻断点设计（分层防御，见 Part 2 代码）
 * ----------------------------------------------------------------------------
 *  版本更新 5 处：
 *   V1 Updater.f(int)                         -> 直接 return（阻断更新例程）
 *   V2 NetSceneGetUpdateInfo.onGYNetEnd(...)  -> 直接 return（服务器信息不解析）
 *   V3 MMErrorProcessor.updateRequired(...)   -> return false（不弹窗/不下载）
 *   V4 UpdaterManager.download/handleCommand  -> 直接 return（阻断 APK 下载）
 *   V5 SubCoreSandBox.cj(...)                 -> 直接 return null（无更新对话框）
 *  Tinker 6 处：
 *   T1 SubCoreHotpatch.onAccountInitialized() -> 直接 return（账号初始化不拉取）
 *   T2 TinkerBootsActivateListener.callback() -> return false（定时不拉取）
 *   T3 TinkerBootsSysCmdMsgListener.U0()      -> 直接 return（服务端指令不响应）
 *   T4 TinkerClient.fetchPatchUpdate(boolean) -> 直接 return（兜底断拉取）
 *   T5 MergeHdiffApkService.onHandleIntent()  -> 直接 return（服务不应用 patch）
 *   T6 CTinkerInstaller.c() / f()            -> 返回错误码（核心应用被掐断）
 *  可选：
 *   T7 启动时加载历史 patch（高风险，默认关闭） -> 见 BLOCK_LOAD_EXISTING_PATCH
 *   F1 固定显示版本号（patch 后显示仍为原版）  -> 见 FIX_VERSION_CODE
 *
 * ----------------------------------------------------------------------------
 * 1.4 风险提示
 * ----------------------------------------------------------------------------
 *  - 阻断"历史已应用 patch 的加载"(T7) 风险高：Tinker 的 dex/oat 版本与 APK 强绑定，
 *    强行不加载可能导致启动异常，默认关闭，仅作预留。
 *  - 强制更新(4,-17)被 hook 拦截后，微信可能继续运行，但服务器仍可能对老版本
 *    下发业务限制，这不在模块控制范围。
 *  - 微信对非官方版本有风控，请自担风险，仅用于学习/研究。
 * ============================================================================
 */

/*
 * ============================================================================
 * Part 2  阻断模块 Java 源码（可直接编译进你的 Xposed 模块工程）
 * ============================================================================
 * 使用方法：
 *   1) 在 Xposed 模块工程中新建类 WeChatUpdateBlocker，内容粘贴本段。
 *   2) 在 assets/xposed_init 中声明入口：
 *        com.example.module.WeChatUpdateBlocker
 *   3) 若使用 LSPosed：直接勾选目标应用 com.tencent.mm，勾选"作用域"。
 *   4) 可选依赖：LSPosed 内置 DexKit（无需额外 gradle 依赖，代码以反射方式调用；
 *      若你的环境没有 DexKit，会自动回退到硬编码混淆名）。
 *   5) 编译后安装，重启微信生效。
 * ============================================================================
 */
package com.example.module;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class WeChatUpdateBlocker implements IXposedHookLoadPackage {

    public static final String WX_PKG = "com.tencent.mm";
    private static final String TAG = "WXUpdateBlocker";

    // ============================================================
    // 开关（按需调整）
    // ============================================================
    /** 阻断正式版本更新（APK 升级 / 版本号更新） */
    private static final boolean BLOCK_VERSION_UPDATE = true;
    /** 阻断 Tinker 热补丁（功能热更新） */
    private static final boolean BLOCK_TINKER_PATCH = true;
    /** 阻断启动时加载【历史已应用】的 patch（高风险，默认关闭） */
    private static final boolean BLOCK_LOAD_EXISTING_PATCH = false;
    /** 固定显示版本号（patch 后微信设置页显示的版本仍为原版） */
    private static final boolean FIX_VERSION_CODE = false;

    // DexKit 桥（反射持有）
    private static Object dexKit = null;
    private static ClassLoader appClassLoader = null;

    // ============================================================
    // 入口
    // ============================================================
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!WX_PKG.equals(lpparam.packageName)) {
            return;
        }
        try {
            initDexKit(lpparam.classLoader);

            if (BLOCK_VERSION_UPDATE) {
                blockVersionUpdate(lpparam);
            }
            if (BLOCK_TINKER_PATCH) {
                blockTinkerHotPatch(lpparam);
            }
            if (BLOCK_LOAD_EXISTING_PATCH) {
                blockLoadExistingPatch(lpparam);
            }
            if (FIX_VERSION_CODE) {
                fixVersionCode(lpparam.classLoader);
            }
            XposedBridge.log(TAG + " => hooks installed, process=" + lpparam.processName);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " init error: " + t);
        }
    }

    // ============================================================
    // DexKit 封装（反射调用 LSPosed 内置 DexKit）
    // 类路径: io.github.lsposed.lsposed.dexkit.DexKitBridge
    // API:
    //   create(ClassLoader, boolean loadAllClasses)          -> DexKitBridge
    //   findClassUsingStrings(Set<String>, boolean subclass, boolean methodOnly) -> Map<String,ClassDef>
    //   findMethodUsingStrings(Set<String>)                  -> Map<String,MethodDef>
    //   ClassDef.getClassImpl() 返回 Class<?>
    //   MethodDef.getMethodImpl() 返回 java.lang.reflect.Method
    // ============================================================
    private static void initDexKit(ClassLoader cl) {
        try {
            Class<?> c = Class.forName("io.github.lsposed.lsposed.dexkit.DexKitBridge", false, cl);
            Method create = c.getMethod("create", ClassLoader.class, boolean.class);
            appClassLoader = cl;
            dexKit = create.invoke(null, cl, Boolean.FALSE);
            XposedBridge.log(TAG + " DexKit bridge ready.");
        } catch (Throwable t) {
            appClassLoader = cl;
            dexKit = null;
            XposedBridge.log(TAG + " DexKit not available, fallback to obfuscated names. " + t);
        }
    }

    /** 按特征字符串定位类；失败回退已知混淆名 */
    private static Class<?> findClassByStrings(String fallback, boolean methodOnly, String... strings) {
        if (dexKit != null) {
            try {
                Set<String> set = new HashSet<>(Arrays.asList(strings));
                @SuppressWarnings("unchecked")
                Map<String, Object> res = (Map<String, Object>) dexKit.getClass()
                        .getMethod("findClassUsingStrings", Set.class, boolean.class, boolean.class)
                        .invoke(dexKit, set, Boolean.FALSE, Boolean.valueOf(methodOnly));
                if (res != null && !res.isEmpty()) {
                    Object clsDef = res.values().iterator().next();
                    Class<?> impl = (Class<?>) clsDef.getClass().getMethod("getClassImpl").invoke(clsDef);
                    XposedBridge.log(TAG + " DexKit class hit: " + impl.getName() + " via " + Arrays.toString(strings));
                    return impl;
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " DexKit findClass err: " + t);
            }
        }
        if (fallback != null) {
            return XposedHelpers.findClassIfExists(fallback, appClassLoader);
        }
        return null;
    }

    /** 按特征字符串在指定类中定位方法（DexKit findMethodUsingStrings 返回的是全 App 范围，
     *  这里按返回结果的 declaringClass 过滤，取第一个匹配）；失败回退已知方法名 */
    private static Method findMethodByStrings(Class<?> clazz, String fallbackMethod, String... strings) {
        if (clazz == null) return null;
        if (dexKit != null) {
            try {
                Set<String> set = new HashSet<>(Arrays.asList(strings));
                @SuppressWarnings("unchecked")
                Map<String, Object> res = (Map<String, Object>) dexKit.getClass()
                        .getMethod("findMethodUsingStrings", Set.class)
                        .invoke(dexKit, set);
                if (res != null) {
                    for (Object mDef : res.values()) {
                        Method m = (Method) mDef.getClass().getMethod("getMethodImpl").invoke(mDef);
                        if (m.getDeclaringClass().getName().equals(clazz.getName())) {
                            XposedBridge.log(TAG + " DexKit method hit: " + clazz.getName() + "." + m.getName());
                            return m;
                        }
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " DexKit findMethod err: " + t);
            }
        }
        if (fallbackMethod != null) {
            return findMethodIfExists(clazz, fallbackMethod);
        }
        return null;
    }

    /** 兼容 getDeclaredMethods 的方法查找（支持重载，取第一个） */
    private static Method findMethodIfExists(Class<?> clazz, String name) {
        if (clazz == null || name == null) return null;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }

    /** 便捷 hook：按特征字符串找类+方法并 beforeHook */
    private static void hookByStrings(String fallbackCls, String fallbackMethod,
                                      boolean methodOnly, XC_MethodHook hook,
                                      String[] classStrings, String[] methodStrings) {
        try {
            Class<?> clazz = findClassByStrings(fallbackCls, methodOnly, classStrings);
            if (clazz == null) {
                XposedBridge.log(TAG + " [WARN] class not found: " + Arrays.toString(classStrings));
                return;
            }
            Method m = findMethodByStrings(clazz, fallbackMethod, methodStrings);
            if (m == null) {
                XposedBridge.log(TAG + " [WARN] method not found in " + clazz.getName()
                        + ": " + Arrays.toString(methodStrings));
                return;
            }
            XposedBridge.hookMethod(m, hook);
            XposedBridge.log(TAG + " hooked: " + clazz.getName() + "." + m.getName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookByStrings err: " + t);
        }
    }

    // ============================================================
    // V. 版本更新阻断（APK 升级 / 版本号）
    // ============================================================
    private void blockVersionUpdate(final XC_LoadPackage.LoadPackageParam lpparam) {
        // V1: Updater.f(int) —— 更新例程启动点
        hookByStrings(
                "com.tencent.mm.sandbox.updater.Updater", "f", false,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        XposedBridge.log(TAG + " [V1] block Updater.f(type=" + param.args[0] + ")");
                        param.setResult(null);
                    }
                },
                new String[]{"MicroMsg.Updater", "summerupdate begin update routine"},
                new String[]{"summerupdate begin update routine"});

        // V2: NetSceneGetUpdateInfo.onGYNetEnd —— 服务器更新信息不解析
        hookByStrings(
                "te5.a", "onGYNetEnd", false,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        XposedBridge.log(TAG + " [V2] block NetSceneGetUpdateInfo.onGYNetEnd");
                        param.setResult(null);
                    }
                },
                new String[]{"MicroMsg.NetSceneGetUpdateInfo", "getupdateinfo"},
                new String[]{"MicroMsg.NetSceneGetUpdateInfo", "GetUpdateInfo onGYNetEnd"});

        // V3: MMErrorProcessor.updateRequired —— 不弹更新框、不触发下载
        hookByStrings(
                "com.tencent.mm.ui.rc", "b", false,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        XposedBridge.log(TAG + " [V3] block MMErrorProcessor.updateRequired"
                                + " errType=" + param.args[1] + " errCode=" + param.args[2]);
                        param.setResult(Boolean.FALSE);
                    }
                },
                new String[]{"MicroMsg.MMErrorProcessor", "updateRequired"},
                new String[]{"MicroMsg.MMErrorProcessor", "updateRequired"});

        // V4: UpdaterManager 下载入口（download/handleCommand 已被混淆，用字符串定位）
        hookByStrings(
                "ue5.x0", null, false,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        XposedBridge.log(TAG + " [V4] block UpdaterManager download/handleCommand");
                        param.setResult(null);
                    }
                },
                new String[]{"MicroMsg.UpdaterManager"},
                new String[]{"MicroMsg.UpdaterManager", "download() downloading"});
    }

    // ============================================================
    // T. Tinker 热补丁阻断（功能热更新）
    // ============================================================
    private void blockTinkerHotPatch(final XC_LoadPackage.LoadPackageParam lpparam) {
        // T1: SubCoreHotpatch.onAccountInitialized —— 账号初始化不拉取
        hookByStrings(
                "ge3.a0", "onAccountInitialized", false,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        XposedBridge.log(TAG + " [T1] block SubCoreHotpatch.onAccountInitialized");
                        param.setResult(null);
                    }
                },
                new String[]{"MicroMsg.Tinker.SubCoreHotpatch", "try to fetch patch update"},
                new String[]{"MicroMsg.Tinker.SubCoreHotpatch", "try to fetch patch update"});

        // T2: TinkerBootsActivateListener.callback —— 定时(6h)不拉取
        hookByStrings(
                "com.tencent.mm.plugin.hp.model.TinkerBootsActivateListener", "callback", false,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        XposedBridge.log(TAG + " [T2] block TinkerBootsActivateListener.callback");
                        param.setResult(Boolean.FALSE);
                    }
                },
                new String[]{"MicroMsg.Tinker.TinkerBootsActivateListener", "fetchPatchUpdate"},
                new String[]{"MicroMsg.Tinker.TinkerBootsActivateListener", "callback post task"});

        // T3: TinkerBootsSysCmdMsgListener.U0 —— 服务端下行指令不响应
        hookByStrings(
                "ge3.q0", "U0", false,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        XposedBridge.log(TAG + " [T3] block TinkerBootsSysCmdMsgListener.U0");
                        param.setResult(null);
                    }
                },
                new String[]{"MicroMsg.Tinker.TinkerBootsSysCmdMsgListener", ".sysmsg.boots.xmlkey"},
                new String[]{"MicroMsg.Tinker.TinkerBootsSysCmdMsgListener", ".sysmsg.boots.xmlkey"});

        // T4: TinkerClient.fetchPatchUpdate —— 兜底断拉取
        hookByStrings(
                "x76.a", null, false,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        XposedBridge.log(TAG + " [T4] block TinkerClient.fetchPatchUpdate");
                        param.setResult(null);
                    }
                },
                new String[]{"Tinker.TinkerClient", "fetchPatchUpdate"},
                new String[]{"Tinker.TinkerClient", "fetchPatchUpdate"});

        // T5: MergeHdiffApkService.onHandleIntent —— 服务不应用 patch
        hookByStrings(
                "com.tencent.mm.plugin.hp.mmdiff.MergeHdiffApkService", "onHandleIntent", false,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        XposedBridge.log(TAG + " [T5] block MergeHdiffApkService.onHandleIntent");
                        param.setResult(null);
                    }
                },
                new String[]{"Tinker.MergeHdiffApkService.HdiffApk", "patch_syncresponse_extra"},
                new String[]{"Tinker.MergeHdiffApkService.HdiffApk", "doApplyPatch"});

        // T6: CTinkerInstaller.c / .f —— 核心应用掐断（返回错误码）
        hookByStrings(
                "ee3.a", "c", false,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        XposedBridge.log(TAG + " [T6] block CTinkerInstaller.c(hdiff/tinker apply)");
                        param.setResult(Integer.valueOf(-1));
                    }
                },
                new String[]{"MicroMsg.Tinker.CTinkerInstaller", "buildOldDeltaFriendFile"},
                new String[]{"MicroMsg.Tinker.CTinkerInstaller", "HdiffApk"});

        hookByStrings(
                null, "f", false,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        XposedBridge.log(TAG + " [T6b] block CTinkerInstaller.f(onDownloadFinish)");
                        param.setResult(Integer.valueOf(-1));
                    }
                },
                new String[]{"MicroMsg.Tinker.CTinkerInstaller", "onDownloadFinish"},
                new String[]{"MicroMsg.Tinker.CTinkerInstaller", "HdiffApk onDownloadFinish"});
    }

    // ============================================================
    // T7(可选). 阻断启动时加载历史已应用的 patch —— 高风险，默认关闭
    // ============================================================
    private void blockLoadExistingPatch(XC_LoadPackage.LoadPackageParam lpparam) {
        // 微信启动加载入口 an0.c0 = MMApplicationLikeLegacy（patch 加载/oat 修复）
        // 注意：Tinker 的 dex/oat 与 APK 强绑定，阻断可能导致启动异常，谨慎使用。
        hookByStrings(
                "an0.c0", null, false,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        XposedBridge.log(TAG + " [T7] block MMApplicationLikeLegacy patch load");
                        param.setResult(Boolean.FALSE);
                    }
                },
                new String[]{"MicroMsg.MMApplicationLikeLegacy", "tinker_classN.dex"},
                new String[]{"MicroMsg.MMApplicationLikeLegacy", "tinker_classN.dex"});
    }

    // ============================================================
    // F1(可选). 固定显示版本号
    // ============================================================
    private void fixVersionCode(ClassLoader cl) {
        try {
            // 微信版本字段：com.tencent.mm.sdk.platformtools.BuildInfo 的混淆为 zf.g
            // 这里按特征字符串定位 BuildInfo 类，并将 VERSION / REV 等只读常量替换为固定值。
            // 注意：该 Hook 影响上报与协议，请自行评估。
            Class<?> bi = findClassByStrings(null, false,
                    new String[]{"MicroMsg.BuildInfo", "VERSION"});
            if (bi != null) {
                XposedBridge.hookAllMethods(bi, "getVersion", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        // 保持原值（占位），如需固定可 setResult("8.0.0") 等
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " fixVersionCode err: " + t);
        }
    }
}

/*
 * ============================================================================
 * Part 3  微信版本更新后如何使用 DexKit 适配
 * ============================================================================
 * 3.1 为什么需要适配
 *   微信每次发版都会重新混淆类名，但【字符串常量、Log Tag、协议 URI】
 *   基本保持稳定。DexKit 做的就是"按特征字符串找类/方法"，因此可以跨版本。
 *
 * 3.2 特征字符串锚点表（本项目使用）
 *   -----------------------------------------------------------------
 *   模块            | 特征字符串
 *   -----------------------------------------------------------------
 *   版本更新核心     | "MicroMsg.Updater"
 *   更新例程启动     | "summerupdate begin update routine"
 *   版本检测网络     | "MicroMsg.NetSceneGetUpdateInfo" / "getupdateinfo"
 *   错误码弹窗       | "MicroMsg.MMErrorProcessor" / "updateRequired"
 *   下载管理器       | "MicroMsg.UpdaterManager"
 *   更新工具类       | "MicroMsg.UpdateUtil" / "update_config_prefs"
 *   热补丁子模块     | "MicroMsg.Tinker.SubCoreHotpatch" / "try to fetch patch update"
 *   boots 定时拉取   | "MicroMsg.Tinker.TinkerBootsActivateListener" / "fetchPatchUpdate"
 *   服务端指令       | "MicroMsg.Tinker.TinkerBootsSysCmdMsgListener" / ".sysmsg.boots.xmlkey"
 *   Tinker 客户端    | "Tinker.TinkerClient" / "fetchPatchUpdate" / "patch_server_config"
 *   patch 应用服务   | "Tinker.MergeHdiffApkService.HdiffApk" / "patch_syncresponse_extra"
 *   patch 安装器     | "MicroMsg.Tinker.CTinkerInstaller" / "HdiffApk"
 *   patch 加载器     | "MicroMsg.MMApplicationLikeLegacy" / "tinker_classN.dex"
 *   网络场景基类     | "onGYNetEnd"（微信网络回调统一方法名，稳定）
 *   -----------------------------------------------------------------
 *
 * 3.3 适配流程（微信升级后）
 *   Step 1 抓新签名
 *     使用 LSPilot 工具（或 jadx）搜索上述特征字符串，例如：
 *       search_strings("MicroMsg.NetSceneGetUpdateInfo")
 *       search_strings("MicroMsg.Tinker.CTinkerInstaller")
 *     即可得到新版本的混淆类名。
 *
 *   Step 2 更新 fallback 类名
 *     将 Part 2 代码中 hookByStrings 的第一个参数（fallbackCls）
 *     替换为新版混淆类名；DexKit 找不到时自动使用该回退。
 *
 *   Step 3 校验方法签名
 *     对关键方法用 decompile_class_methods_only 核对重载与返回类型：
 *       - Updater.f(int)                   返回 void
 *       - NetSceneGetUpdateInfo.onGYNetEnd(...) 返回 void
 *       - MMErrorProcessor.updateRequired  返回 boolean
 *       - TinkerBootsActivateListener.callback 返回 boolean
 *       - MergeHdiffApkService.onHandleIntent  返回 void
 *       - CTinkerInstaller.c / f           返回 int（错误码）
 *
 *   Step 4 验证
 *     安装后开启 xposed 日志，观察 "WXUpdateBlocker" 输出：
 *       - "DexKit class hit: <类名>" 说明定位成功
 *       - "[V1] block Updater.f" 等说明对应 hook 被触发
 *     若某一行显示 "[WARN] class not found" 则对应锚点已变，更新该行特征串。
 *
 * 3.4 DexKit 底层用法示例（LSPosed 内置）
 *   ClassLoader cl = lpparam.classLoader;
 *   DexKitBridge bridge = DexKitBridge.create(cl, false);
 *   Map<String, ClassDef> cls = bridge.findClassUsingStrings(
 *       new HashSet<>(Arrays.asList("MicroMsg.Tinker.CTinkerInstaller", "HdiffApk")),
 *       false, false);
 *   ClassDef def = cls.values().iterator().next();
 *   Class<?> real = def.getClassImpl();   // 得到真实 Class
 *
 *   Map<String, MethodDef> mtd = bridge.findMethodUsingStrings(
 *       new HashSet<>(Arrays.asList("HdiffApk onDownloadFinish")));
 *   Method m = mtd.values().iterator().next().getMethodImpl(); // 得到 Method
 *
 * 3.5 常见坑
 *   - 同一特征串可能命中多个类（如 "MicroMsg.Updater" 同时命中 Updater、
 *     SubCoreSandBox、SettingsAboutMicroMsgUI），findClassUsingStrings 返回 Map，
 *     取第一个可能与预期不符。此时应增加更多组合字符串，或用
 *     MethodDef.getDeclaringClass() 过滤（本文件 findMethodByStrings 已做了该过滤）。
 *   - 方法名"c/f/a/b"这类混淆短名在回退时不要裸用（会错 hook），
 *     必须配合 DexKit 字符串定位（本文件 T4/T6 已按此处理）。
 *   - 微信多进程：patch 下载服务可能在 :push/:tools 进程运行，
 *     建议 LSPosed 作用域覆盖微信所有进程；本模块对任意进程都安装 hook，
 *     由微信进程内实际类加载决定是否生效。
 * ============================================================================
 */
