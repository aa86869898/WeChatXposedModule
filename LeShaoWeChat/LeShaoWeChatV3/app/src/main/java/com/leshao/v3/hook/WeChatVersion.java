package com.leshao.v3.hook;

/**
 * 微信版本号分流（v3.0.528）。
 *
 * <p>3.0.455 源码针对 8.0.78（versionCode≈3180）及更早版本，
 * 3.0.527 起的新锚点针对 8.0.79（versionCode=3200）。
 * 本类在启动时由 MainHook 注入微信 versionCode，
 * 供 WxResolver 做「旧版微信走旧类名 / 新版微信走 DexKit 新锚点」的分流。
 */
public final class WeChatVersion {

    /** 8.0.79 的 versionCode（新版微信锚点起点）。 */
    public static final int NEW_WECHAT_VERSION_CODE = 3200;

    /** 8.0.78 的 versionCode（455 源码目标版本）。 */
    public static final int OLD_WECHAT_VERSION_CODE = 3180;

    private static volatile int sVersionCode = -1;

    private WeChatVersion() {}

    public static void setVersionCode(int versionCode) {
        sVersionCode = versionCode;
    }

    public static int getVersionCode() {
        return sVersionCode;
    }

    /** 是否为 8.0.79 及更新版本（走 527 新锚点）。 */
    public static boolean isNew() {
        return sVersionCode >= NEW_WECHAT_VERSION_CODE;
    }

    /** 是否为 8.0.78 及更早版本（走 455 旧类名/旧字符串）。 */
    public static boolean isOld() {
        return sVersionCode > 0 && sVersionCode < NEW_WECHAT_VERSION_CODE;
    }

    /** 版本号是否已知（MainHook 已注入）。 */
    public static boolean isKnown() {
        return sVersionCode > 0;
    }

    /** 旧版微信对应的类名（455 源码硬编码）。 */
    public static String[] oldFallbacks(String... names) {
        return names;
    }
}