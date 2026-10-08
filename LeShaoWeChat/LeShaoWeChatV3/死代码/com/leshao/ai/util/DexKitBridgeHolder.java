package com.leshao.ai.util;

import android.util.Log;

/**
 * dexkit 桥的进程级持有者。
 * <p>
 * v30113 起 AI 模块不再自建 native {@code DexKitBridge}：改为统一调用
 * {@link com.leshao.v3.hook.DexKitHelper#withWechatBridge}，复用 v3 的进程级缓存桥
 * （与全量扫描同一 native 实例，并在同一全局桥锁内串行），避免同进程存在两个
 * libdexkit 桥实例与扫描并发访问导致的 native 崩溃。
 * <p>
 * 本类保留仅为兼容既有初始化调用点，不再持有任何 native 桥。
 */
public final class DexKitBridgeHolder {

    private static final String TAG = "LeshaoAI.DexKit";

    private static volatile boolean initialized = false;

    private DexKitBridgeHolder() {
    }

    /** 兼容入口：Deckit 现由 v3 {@code DexKitHelper} 统一提供，此处不创建独立桥（幂等）。 */
    public static synchronized void init(ClassLoader classLoader) {
        if (initialized) {
            return;
        }
        initialized = true;
        Log.i(TAG, "DexKit 统一由 v3 DexKitHelper 提供, 跳过独立桥创建");
    }

    /** 不再对外提供独立桥，历史调用点应改用 {@code DexKitHelper.withWechatBridge}。 */
    public static boolean isReady() {
        return false;
    }
}
