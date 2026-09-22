package com.leshao.v3;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;

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
 * <p>v962 决策: 实例总开关默认对所有实例开启(LSPosed 作用域激活即用户显式授权),
 * 各实例可在 AI 助手面板独立关闭; 关闭后该实例重启微信时不再加载任何 Hook。</p>
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
                    + " enabled=" + isEnabled() + " dataDir=" + sDataDir
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
     * 当前实例总开关。默认开启; 分身实例如需默认关闭可改 sPrefs.getBoolean(KEY_ENABLED, isPrimary())。
     */
    public static boolean isEnabled() {
        if (sPrefs == null) return true;
        try {
            return sPrefs.getBoolean(KEY_ENABLED, true);
        } catch (Throwable t) {
            return true;
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
}
