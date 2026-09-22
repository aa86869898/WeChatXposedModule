package com.leshao.ai.hook.wechat;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import com.leshao.ai.hook.HookEntry;
import com.leshao.ai.hook.dexkit.DexKitAdapter;

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
            // 0) 先从模块进程同步配置/白名单（数据源在模块 files 目录）
            try {
                ConfigBridge.syncFromProvider(appContext);
            } catch (Throwable t) {
                Log.w(TAG, "配置同步失败: " + t);
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
     * 点击 "AI 助手" 打开模块设置页（exported=true，经组件名跨进程启动）。
     */
    private static void installMenu(XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;
        final Class<?> fragment = DexKitAdapter.findChattingUIFragment();
        if (fragment == null) {
            Log.w(TAG, "ChattingUIFragment 未定位，菜单注入跳过");
            return;
        }
        Log.i(TAG, "ChattingUIFragment 定位成功: " + fragment.getName());
        final Context appContext = getAppContext(cl);
        XposedBridge.hookAllMethods(fragment, "onCreateOptionsMenu", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.args[0] instanceof Menu) {
                        injectAiMenu((Menu) param.args[0], appContext);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "菜单注入回调异常: " + t);
                }
            }
        });
    }

    private static void injectAiMenu(Menu menu, Context appContext) {
        if (menu.findItem(MENU_AI_ITEM) != null) {
            return;
        }
        MenuItem item = menu.add(0, MENU_AI_ITEM, 0, "AI 助手");
        item.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem mi) {
                try {
                    Context ctx = appContext;
                    if (ctx == null) {
                        return true;
                    }
                    Intent i = new Intent();
                    i.setClassName(MODULE_PACKAGE, SETTINGS_ACTIVITY);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                } catch (Throwable t) {
                    Log.w(TAG, "打开设置页失败: " + t);
                }
                return true;
            }
        });
        Log.i(TAG, "已注入 AI 助手菜单项");
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
