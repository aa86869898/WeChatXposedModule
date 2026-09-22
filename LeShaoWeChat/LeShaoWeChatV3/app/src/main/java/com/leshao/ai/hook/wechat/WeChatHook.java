package com.leshao.ai.hook.wechat;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

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
 *   <li><b>菜单入口</b>：hook ChattingUIFragment.onCreateOptionsMenu，
 *       在聊天页标题栏菜单注入 "AI 助手"（文档 §11.1 方案 A，最稳）。</li>
 *   <li><b>会话转储</b>：LauncherUI 就绪时把会话列表落盘，供白名单页导入。</li>
 * </ol>
 * 所有 hook 均带 try/catch 与日志，单点失败不影响微信运行。
 */
public final class WeChatHook implements IXposedHookLoadPackage {

    private static final String TAG = "LeshaoAI.Hook";

    /** 菜单项 id（固定值，防重复添加）。 */
    private static final int MENU_AI_ITEM = 0x1E5A1;

    /** 模块设置页组件（applicationId 为 com.leshao.v3）。 */
    private static final String MODULE_PACKAGE = "com.leshao.v3";
    private static final String SETTINGS_ACTIVITY =
            "com.leshao.ai.ui.activity.SettingsActivity";

    /** 微信主界面类名（未混淆，文档 §14.5 实证）。 */
    private static final String LAUNCHER_UI = "com.tencent.mm.ui.LauncherUI";

    private final AtomicBoolean coreInstalled = new AtomicBoolean(false);

    /** 菜单注入状态(仅 hook 一次; 重试链与 installCore 共用) */
    private static final AtomicBoolean menuInstalled = new AtomicBoolean(false);
    private static final int MENU_RETRY_MAX = 10;
    private static final long MENU_RETRY_DELAY_MS = 1000L;

    /** 当前前台 LauncherUI 实例(AI 助手弹窗挂载点; Xposed 常驻, 单例可接受) */
    private static volatile Activity sCurrentActivity;

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
        // v965: 系统克隆分身(App-Clone)拦截 —— 与 MainHook.handleLoadPackage 一致的防护,
        // 防止其它调用路径绕开主入口; 判定走系统 API 动态识别 Profile Group, 不写死 userId 数字,
        // LSPosed MultiApp 等独立虚拟用户不受影响, 由 LSPosed 作用域控制。
        if (com.leshao.v3.InstanceManager.isCloneApp()) {
            Log.w(TAG, "系统克隆分身进程, WeChatHook 拦截, 不执行任何模块代码");
            return;
        }
        final ClassLoader cl = lpparam.classLoader;

        // TTS 等需要的微信 Application Context
        final Context appContext = getAppContext(cl);
        try {
            TriggerEngine.setAppContext(appContext);
        } catch (Throwable ignored) {
        }

        final WeChatHook self = new WeChatHook();

        // 延迟到微信主界面就绪后再挂核心 hook（文档 §16.2 deferUntilReady）
        Class<?> launcher = null;
        try {
            launcher = XposedHelpers.findClass(LAUNCHER_UI, cl);
        } catch (Throwable t) {
            Log.w(TAG, "LauncherUI 未找到，退化为立即装配: " + t);
        }
        try {
            if (launcher != null) {
                XposedBridge.hookAllMethods(launcher, "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // v960: 记录前台 Activity, AI 助手弹窗需要 Activity 级 Context
                        if (param.thisObject instanceof Activity) {
                            sCurrentActivity = (Activity) param.thisObject;
                        }
                        self.installCore(lpparam, appContext);
                    }
                });
                Log.i(TAG, "已挂 LauncherUI.onResume 等待微信就绪");
            } else {
                Log.w(TAG, "LauncherUI 未找到，退化为立即装配");
                self.installCore(lpparam, appContext);
            }
        } catch (Throwable t) {
            Log.w(TAG, "deferUntilReady 失败，退化为立即装配: " + t);
            self.installCore(lpparam, appContext);
        }

        // 菜单注入不依赖存储链，可立即挂（定位失败仅损失菜单入口）
        try {
            installMenu(lpparam);
        } catch (Throwable t) {
            Log.w(TAG, "菜单注入失败: " + t);
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
            } catch (Throwable t) {
                Log.w(TAG, "AIBotCore 初始化失败: " + t);
            }

            // 2) 存储访问链（b41.h9 → b41.e → f9/j4/q3）
            StorageHub.get().ensureBound();
            Log.i(TAG, "DexKit 解析: " + DexKitAdapter.dump());

            // v960: DexKit 存储链就绪后再触发菜单注入(此时 findChattingUIFragment 才可能命中)
            try {
                tryInstallMenu(lpparam.classLoader, 0);
            } catch (Throwable t) {
                Log.w(TAG, "菜单注入(installCore) 异常: " + t);
            }

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

    /**
     * 菜单注入：ChattingUIFragment.onCreateOptionsMenu（文档 §11.1 方案 A）。
     * v960: findChattingUIFragment 依赖 DexKit, install() 阶段可能未就绪而返回 null,
     * 旧实现直接跳过导致菜单永久缺失; 现改为重试链 + installCore(DexKit ready 后)再触发一次。
     */
    private static void installMenu(XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;
        tryInstallMenu(cl, 0);
    }

    private static void tryInstallMenu(final ClassLoader cl, final int attempt) {
        if (menuInstalled.get()) {
            return;
        }
        final Class<?> fragment;
        try {
            fragment = DexKitAdapter.findChattingUIFragment();
        } catch (Throwable t) {
            Log.w(TAG, "findChattingUIFragment err: " + t);
            scheduleMenuRetry(cl, attempt);
            return;
        }
        if (fragment == null) {
            scheduleMenuRetry(cl, attempt);
            return;
        }
        try {
            final Context appContext = getAppContext(cl);
            XposedBridge.hookAllMethods(fragment, "onCreateOptionsMenu", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        // v985: hookAllMethods 会沿继承链 hook 到基类声明的
                        // onCreateOptionsMenu, 从而波及主页(LauncherUI)等其它 Fragment,
                        // 导致「AI 助手」菜单项也出现在主页右上角。这里强制校验
                        // 回调方确为聊天 Fragment 才注入。
                        if (!fragment.isInstance(param.thisObject)) return;
                        if (param.args[0] instanceof Menu) {
                            injectAiMenu((Menu) param.args[0], param.thisObject);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "菜单注入回调异常: " + t);
                    }
                }
            });
            menuInstalled.set(true);
            Log.i(TAG, "菜单注入成功: " + fragment.getName() + " (attempt " + attempt + ")");
            LogWriter.log(TAG, "菜单注入成功: " + fragment.getName() + " (attempt " + attempt + ")");
        } catch (Throwable t) {
            Log.w(TAG, "菜单注入失败(" + attempt + "): " + t);
            scheduleMenuRetry(cl, attempt);
        }
    }

    private static void scheduleMenuRetry(final ClassLoader cl, final int attempt) {
        if (attempt >= MENU_RETRY_MAX || menuInstalled.get()) {
            Log.w(TAG, "ChattingUIFragment 未定位，菜单注入放弃 (attempt " + attempt + ")");
            LogWriter.log(TAG, "菜单注入放弃: ChattingUIFragment 未定位 (attempt " + attempt + ")");
            return;
        }
        Log.w(TAG, "ChattingUIFragment 未定位，" + MENU_RETRY_DELAY_MS + "ms 后重试 (" + attempt + ")");
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> tryInstallMenu(cl, attempt + 1), MENU_RETRY_DELAY_MS);
    }

    private static void injectAiMenu(Menu menu, Object fragment) {
        if (menu.findItem(MENU_AI_ITEM) != null) {
            return;
        }
        MenuItem item = menu.add(0, MENU_AI_ITEM, 0, "AI 助手");
        LogWriter.log(TAG, "injectAiMenu: 已注入「AI 助手」菜单项");
        item.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem mi) {
                // v960: 微信进程内 Material 3 弹窗(替代跨进程 startActivity)
                try {
                    LogWriter.log(TAG, "click: AI 助手菜单项");
                    Activity act = resolveActivity(fragment);
                    LogWriter.log(TAG, "click: resolveActivity="
                            + (act == null ? "null" : act.getClass().getName()));
                    AiAssistantPanel.show(act);
                } catch (Throwable t) {
                    Log.w(TAG, "AI 助手弹窗失败: " + t);
                    LogWriter.log(TAG, "AI 助手弹窗失败: " + t);
                }
                return true;
            }
        });
    }

    /** 从 Fragment/Context 解析宿主 Activity, 弹窗必须挂在 Activity 上。 */
    private static Activity resolveActivity(Object fragment) {
        if (fragment instanceof Activity) {
            return (Activity) fragment;
        }
        // 反射取 getActivity(): 避免编译/运行期对 androidx/framework Fragment 类的直接依赖
        try {
            if (fragment != null) {
                Object a = XposedHelpers.callMethod(fragment, "getActivity");
                if (a instanceof Activity) {
                    return (Activity) a;
                }
            }
        } catch (Throwable ignored) {
        }
        Activity cur = sCurrentActivity;
        if (cur != null && !cur.isFinishing()) {
            return cur;
        }
        return null;
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
