package com.leshao.ai.hook.wechat;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.leshao.ai.hook.HookEntry;
import com.leshao.ai.hook.dexkit.DexKitAdapter;
import com.leshao.v3.LogWriter;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 微信（com.tencent.mm）主 Hook 装配（文档 §11 线路八 / §16.2）。
 * <p>
 * 装配策略：
 * <ol>
 *   <li><b>延迟到微信就绪</b>：hook LauncherUI.onResume，微信主界面首帧后才挂核心 hook
 *       （文档 §15.7：v51.r1 的协程发送要求进程初始化完成）。</li>
 *   <li><b>接收链路</b>：{@link MsgReceiveHook} hook f9.Bb 消息总闸门。</li>
 *   <li><b>会话转储</b>：LauncherUI 就绪时把会话列表落盘，供白名单页导入。</li>
 * </ol>
 * 所有 hook 均带 try/catch 与日志，单点失败不影响微信运行。
 */
public final class WeChatHook implements IXposedHookLoadPackage {

    private static final String TAG = "LeshaoAI.Hook";

    /** 模块设置页组件（applicationId 为 com.leshao.v3）。 */
    private static final String MODULE_PACKAGE = "com.leshao.v3";
    private static final String SETTINGS_ACTIVITY =
            "com.leshao.ai.ui.activity.SettingsActivity";

    /** 微信主界面类名（未混淆，文档 §14.5 实证）。 */
    private static final String LAUNCHER_UI = "com.tencent.mm.ui.LauncherUI";

    private final AtomicBoolean coreInstalled = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || !HookEntry.WECHAT_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        install(lpparam);
    }

    /**
     * 由 {@link HookEntry} 或 MainHook 调用，装配全部 hook。
     */
    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        // v1038: 移除 v965 系统克隆分身(App-Clone)拦截 —— 与 MainHook 一致, 克隆分身隔离功能已废掉,
        // 所有微信进程均允许模块执行, 实例启停由 LSPosed 作用域控制。
        final ClassLoader cl = lpparam.classLoader;

        // TTS 等需要的微信 Application Context
        final Context appContext = getAppContext(cl);
        try {
            TriggerEngine.setAppContext(appContext);
        } catch (Throwable ignored) {
        }
        // v1034: selfWxid 的 SharedPreferences 兜底需要 Context
        try {
            StorageHub.setAppContext(appContext);
        } catch (Throwable ignored) {
        }

        final WeChatHook self = new WeChatHook();

        // 延迟到微信主界面就绪后再挂核心 hook（文档 §16.2 deferUntilReady）
        Class<?> launcher = null;
        try {
            launcher = XposedHelpers.findClass(LAUNCHER_UI, cl);
        } catch (Throwable t) {
            LogWriter.log(TAG, "LauncherUI 未找到，退化为立即装配: " + t);
        }
        try {
            if (launcher != null) {
                // v1007: hookAllMethods(LauncherUI) 只命中 LauncherUI【自身声明】的方法,
                // 而 LauncherUI 并未重写 onResume, 导致 installCore 永不执行、AIBotCore 未初始化。
                // 改为 hook Activity.onResume 并按 LauncherUI 实例过滤(ChatGroupUiInjector 同款可靠方案)。
                // v1034: 判定改用类名字符串。微信经 Tinker 热修复(TinkerPatch/多 ClassLoader),
                // findClass 得到的 LauncherUI Class 对象与运行期实例可能来自不同 ClassLoader,
                // isInstance 恒为 false → installCore 永不执行 → 接收链路缺失(AI 不回复)。
                XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof Activity)) return;
                        if (!LAUNCHER_UI.equals(param.thisObject.getClass().getName())) return;
                        self.installCore(lpparam, appContext);
                    }
                });
                LogWriter.log(TAG, "已挂 Activity.onResume 等待微信就绪(过滤 " + LAUNCHER_UI + ")");
            } else {
                LogWriter.log(TAG, "LauncherUI 未找到，退化为立即装配");
                self.installCore(lpparam, appContext);
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "deferUntilReady 失败，退化为立即装配: " + t);
            self.installCore(lpparam, appContext);
        }

        // v1034: 兜底 —— Activity.onResume 判定若因 ClassLoader/时机未命中,
        // 接收链路(MsgReceiveHook)将永远缺失, 表现为「AI 不回复任何消息」。
        // 这里延迟在后台线程强制执行一次 installCore(由 CAS 保证只执行一次);
        // 此时菜单注入已完成, DexKit 已就绪, 存储类可正常解析。
        try {
            Thread coreFallback = new Thread(() -> {
                try {
                    Thread.sleep(3000L);
                } catch (InterruptedException ignored) {
                }
                self.installCore(lpparam, appContext);
            }, "leshao-ai-core-fallback");
            coreFallback.setDaemon(true);
            coreFallback.start();
        } catch (Throwable ignored) {
        }

        // 配置刷新广播（设置页保存后实时同步）
        try {
            ConfigBridge.registerRefreshReceiver(appContext);
        } catch (Throwable ignored) {
        }

        Log.i(TAG, "WeChatHook 装配完成");
    }

    /** 微信就绪后的核心装配（只执行一次）。 */
    private void installCore(XC_LoadPackage.LoadPackageParam lpparam, Context appContext) {
        if (!coreInstalled.compareAndSet(false, true)) {
            return;
        }
        LogWriter.log(TAG, "installCore: enter");
        try {
            // 0) 先从模块进程同步配置/白名单（广播 payload 为主，Provider 兜底）
            try {
                ConfigBridge.syncFromProvider(appContext);
            } catch (Throwable t) {
                Log.w(TAG, "配置同步失败: " + t);
            }
            // 主动请求模块 app 推送一次（覆盖“设置页保存时微信未运行”的冷启动旧数据）
            try {
                ConfigBridge.requestConfig(appContext);
            } catch (Throwable t) {
                Log.w(TAG, "配置拉取请求失败: " + t);
            }

            // 1) 初始化业务核心（配置/记忆/知识库/白名单，落微信 data 目录）
            String hostDataDir = appContext != null
                    ? appContext.getFilesDir().getParent()
                    : "/data/user/0/com.tencent.mm";
            try {
                AIBotCore.ensureInit(hostDataDir);
                LogWriter.log(TAG, "AIBotCore.ensureInit done, config="
                        + (AIBotCore.config() == null ? "null" : "ok")
                        + " hostDataDir=" + hostDataDir);
            } catch (Throwable t) {
                LogWriter.log(TAG, "AIBotCore 初始化失败: " + t);
            }

            // 2) 存储访问链（b41.h9 → b41.e → f9/j4/q3）
            StorageHub.get().ensureBound();
            Log.i(TAG, "DexKit 解析: " + DexKitAdapter.dump());

            // 3) 接收链路：f9.Bb 消息总闸门
            MsgReceiveHook.install(lpparam);

            // 4) 会话列表转储（白名单页导入数据源）
            try {
                ConversationQuery.dumpSessions(appContext);
            } catch (Throwable t) {
                Log.w(TAG, "会话转储失败: " + t);
            }
        } catch (Throwable t) {
            Log.w(TAG, "installCore 异常: " + t);
        }
    }

    /** 从微信进程获取 Application Context（通过反射读取静态 context）。 */
    private static Context getAppContext(final ClassLoader cl) {
        try {
            Class<?> holder = XposedHelpers.findClass(
                    "com.tencent.mm.sdk.platformtools.MMApplicationContext", cl);
            Object o = XposedHelpers.getStaticObjectField(holder, "context");
            if (o instanceof Context) {
                return (Context) o;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> app = XposedHelpers.findClass("android.app.ActivityThread", cl);
            Object thread = XposedHelpers.callStaticMethod(app, "currentApplication");
            if (thread instanceof Context) {
                return (Context) thread;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
