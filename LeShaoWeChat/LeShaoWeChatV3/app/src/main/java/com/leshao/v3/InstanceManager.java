package com.leshao.v3;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.Process;
import android.os.UserHandle;

import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * 主微信/系统分身实例隔离管理器(v962, 依《微信模块隔离.md》实现)。
 *
 * <p>原理: Android 系统分身(应用分身/第二空间/Work Profile)本质是多用户, 每个 user 有独立
 * userId(0, 10, 11...)、独立进程、独立 /data/user/{userId}/ 目录。本类以 userId 作为实例标识,
 * 实例级配置寄生在微信进程的 SharedPreferences 中, 系统自动按 user 隔离存储路径,
 * 实现主微信与分身微信配置零干扰。</p>
 *
 * <p>使用要求:</p>
 * <ol>
 *   <li>必须在 Application.attachBaseContext 时调用 {@link #init(Context)}
 *       (由 ContextManager.hookAttachBaseContext 完成)</li>
 *   <li>业务侧通过 {@link #userId()} / {@link #isPrimary()} / {@link #isEnabled()} 判断当前实例</li>
 *   <li>严禁把实例配置写到模块自身包名下 —— 分身 user 可能无权访问模块目录</li>
 * </ol>
 *
 * <p>v965 决策: 系统克隆分身(ColorOS 应用分身 / AOSP App-Clone)进程在模块入口即被
 * {@link #isCloneApp()} 拦截, 完全不执行模块代码; 实例总开关默认值对齐《微信模块隔离.md》
 * 规范 —— 主微信默认开启, 分身默认关闭。LSPosed MultiApp 等独立虚拟用户不受入口拦截影响,
 * 其模块启停完全由 LSPosed 作用域(勾选【安装到用户 xxx】)控制。</p>
 */
public final class InstanceManager {

    /** SharedPreferences 文件名, 落在微信进程数据目录, 按 user 自动隔离 */
    private static final String PREF_NAME = "xposed_wechat_iso";
    /** Android 多用户 UID 间隔, 固定 100000 */
    private static final int PER_USER_RANGE = 100000;
    /** 实例总开关 key */
    private static final String KEY_ENABLED = "enabled";

    private static final String TAG = "InstanceManager";

    private static volatile int sUserId = -1;
    private static volatile String sDataDir = "";
    private static volatile boolean sPrimary = false;
    private static volatile SharedPreferences sPrefs;

    private InstanceManager() {
    }

    /**
     * 初始化当前实例。每个进程只需调用一次, 应在 Application.attachBaseContext 中调用。
     *
     * @param ctx Application 的 BaseContext(来自 attachBaseContext), 必须是微信进程上下文
     */
    public static void init(Context ctx) {
        if (ctx == null) {
            LogWriter.log(TAG, "init skipped: ctx null");
            return;
        }
        if (sUserId != -1) return;
        try {
            sUserId = Process.myUid() / PER_USER_RANGE;
            sPrimary = (sUserId == 0);
            sDataDir = ctx.getApplicationInfo().dataDir;
            // 关键: 存到微信进程自己的 SP, 系统按 user 隔离文件路径
            sPrefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            LogWriter.log(TAG, "init DONE: userId=" + sUserId + " primary=" + sPrimary
                    + " cloneApp=" + isCloneApp() + " enabled=" + isEnabled() + " dataDir=" + sDataDir
                    + " prefs=" + PREF_NAME);
        } catch (Throwable t) {
            // 兜底: 至少保证 userId 可用(SP 不可用时 isEnabled 走默认值)
            LogWriter.log(TAG, "init err: " + t);
            ensureUid();
        }
    }

    /** 未经 init 的兜底: 仅计算 userId, SP 相关方法走默认值 */
    private static void ensureUid() {
        if (sUserId == -1) {
            sUserId = Process.myUid() / PER_USER_RANGE;
            sPrimary = (sUserId == 0);
        }
    }

    /** 当前实例 userId: 0=主微信, >0=系统分身 */
    public static int userId() {
        ensureUid();
        return sUserId;
    }

    /** 是否为主微信(user 0) */
    public static boolean isPrimary() {
        ensureUid();
        return sPrimary;
    }

    /** 当前微信实例数据目录, 用于日志排查 */
    public static String dataDir() {
        return sDataDir;
    }

    /**
     * 当前实例总开关。默认值对齐《微信模块隔离.md》规范: 主微信默认开启, 分身默认关闭。
     */
    public static boolean isEnabled() {
        if (sPrefs == null) return isPrimary();
        try {
            return sPrefs.getBoolean(KEY_ENABLED, isPrimary());
        } catch (Throwable t) {
            return isPrimary();
        }
    }

    /** 设置总开关(仅影响当前实例) */
    public static void setEnabled(boolean on) {
        if (sPrefs == null) {
            LogWriter.log(TAG, "setEnabled skipped: prefs null");
            return;
        }
        try {
            sPrefs.edit().putBoolean(KEY_ENABLED, on).apply();
            LogWriter.log(TAG, "setEnabled: userId=" + userId() + " -> " + on);
        } catch (Throwable t) {
            LogWriter.log(TAG, "setEnabled err: " + t);
        }
    }

    /** 功能开关。每个功能一个 key, 各实例独立存储, 互不干扰; 默认关闭 */
    public static boolean feature(String key) {
        if (key == null || sPrefs == null) return false;
        try {
            return sPrefs.getBoolean(key, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 设置功能开关(仅影响当前实例) */
    public static void setFeature(String key, boolean on) {
        if (key == null || sPrefs == null) return;
        try {
            sPrefs.edit().putBoolean(key, on).apply();
            LogWriter.log(TAG, "setFeature: userId=" + userId() + " " + key + " -> " + on);
        } catch (Throwable t) {
            LogWriter.log(TAG, "setFeature err: " + t);
        }
    }

    /** 实例可读名称, 用于日志与 UI 展示 */
    public static String label() {
        return isPrimary() ? "主微信(user0)" : ("系统分身(user" + userId() + ")");
    }

    // ---------------- v965: 系统克隆分身(App-Clone)拦截 ----------------

    /**
     * 判断当前进程是否为系统应用克隆分身(ColorOS 应用分身 / AOSP App-Clone)。
     *
     * <p>判定原则(全部走系统 API 动态识别, 不写死任何 userId 数字):</p>
     * <ol>
     *   <li>机主用户(userId=0)直接放行, 完整运行模块全部功能;</li>
     *   <li>优先 binder 直查 IUserManager.getProfileIds(自身): 系统克隆分身与机主用户
     *       同属一个 Profile Group(组内同时包含机主用户与自身), 命中即拦截;</li>
     *   <li>兜底经 ActivityThread.getSystemContext() 依次尝试 Context.isCloneApp()(API 34+)、
     *       UserManager.isCloneProfile()(API 31+), 以及 getUserProfiles() 组内包含自身判定;</li>
     *   <li>所有系统 API 均不可用时放行 —— LSPosed MultiApp 等独立虚拟用户自成一组
     *       (组内无机主用户), 天然不会被本判定拦截, 模块能否运行完全交给 LSPosed
     *       作用域控制(须在 LSP 界面手动选择【安装到用户 xxx】才会注入)。</li>
     * </ol>
     *
     * <p>本方法设计为在 handleLoadPackage 最早阶段(无 Application Context)即可调用,
     * 日志经 XposedBridge.log 落 LSPosed 日志, 不触碰 LogWriter(分身进程零写入)。</p>
     */
    public static boolean isCloneApp() {
        final int myUserId = Process.myUid() / 100000;
        if (myUserId == 0) {
            return false; // 机主用户, 完整运行
        }
        try {
            // 一级判定: binder 直查自身所属 Profile Group, 无需 Context, 权限要求最低
            int[] group = getProfileGroupIds(myUserId);
            if (group != null) {
                for (int id : group) {
                    if (id == 0) {
                        xlog("isCloneApp: userId=" + myUserId
                                + " 与机主用户同 Profile Group -> 系统克隆分身, 拦截");
                        return true;
                    }
                }
                xlog("isCloneApp: userId=" + myUserId
                        + " 独立 Profile Group(组内无机主用户) -> 非克隆分身, 放行(由 LSPosed 控制)");
                return false;
            }

            // 二级判定: systemContext 上的系统 API 逐级尝试
            Object at = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread").invoke(null);
            if (at != null) {
                Context sysCtx = (Context) Class.forName("android.app.ActivityThread")
                        .getMethod("getSystemContext").invoke(at);
                if (sysCtx != null) {
                    try {
                        Object r = sysCtx.getClass().getMethod("isCloneApp").invoke(sysCtx);
                        if (r instanceof Boolean) {
                            xlog("isCloneApp: Context.isCloneApp()=" + r);
                            return (Boolean) r;
                        }
                    } catch (Throwable ignored) {}
                    try {
                        Object um = sysCtx.getSystemService(Context.USER_SERVICE);
                        Object r = um.getClass().getMethod("isCloneProfile").invoke(um);
                        if (r instanceof Boolean) {
                            xlog("isCloneApp: UserManager.isCloneProfile()=" + r);
                            return (Boolean) r;
                        }
                    } catch (Throwable ignored) {}
                    try {
                        Object um = sysCtx.getSystemService(Context.USER_SERVICE);
                        List<?> profiles =
                                (List<?>) um.getClass().getMethod("getUserProfiles").invoke(um);
                        if (profiles != null && profiles.contains(Process.myUserHandle())) {
                            xlog("isCloneApp: userId=" + myUserId
                                    + " 出现在机主用户 Profile Group 中 -> 系统克隆分身, 拦截");
                            return true;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            xlog("isCloneApp: 系统判定 API 全部不可用, userId=" + myUserId
                    + " 放行(由 LSPosed 作用域控制)");
        } catch (Throwable t) {
            xlog("isCloneApp err(放行): " + t);
        }
        return false;
    }

    /** 反射 IUserManager.getProfileIds(userId, false) 查询自身所属 Profile Group, 失败返回 null */
    private static int[] getProfileGroupIds(int myUserId) {
        try {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "user");
            if (binder == null) return null;
            Object um = Class.forName("android.os.IUserManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            return (int[]) um.getClass()
                    .getMethod("getProfileIds", int.class, boolean.class)
                    .invoke(um, myUserId, false);
        } catch (Throwable t) {
            xlog("getProfileGroupIds err: " + t);
            return null;
        }
    }

    /** 早期入口日志(LSPosed 日志), 此时 LogWriter 尚未 init, 分身进程不产生任何文件写入 */
    private static void xlog(String msg) {
        try {
            XposedBridge.log("[LeShaoV3/InstanceManager] " + msg);
        } catch (Throwable ignored) {}
    }
}
