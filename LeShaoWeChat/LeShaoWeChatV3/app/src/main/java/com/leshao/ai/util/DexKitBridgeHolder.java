package com.leshao.ai.util;

import android.util.Log;

import org.luckypray.dexkit.DexKitBridge;

/**
 * dexkit 桥的进程级持有者。
 * <p>
 * DexKit 创建是耗时的原生操作，且应跨 hook 点复用。由
 * {@link com.leshao.ai.hook.HookEntry} 在微信进程加载时用宿主 ClassLoader 初始化一次。
 * 进程结束时自然销毁，无需显式 close。
 */
public final class DexKitBridgeHolder {

    private static final String TAG = "LeshaoAI.DexKit";

    private static volatile DexKitBridge bridge;

    private DexKitBridgeHolder() {
    }

    /**
     * 在微信进程内初始化 dexkit 桥（幂等）。
     *
     * @param classLoader 微信宿主应用类加载器
     */
    public static synchronized void init(ClassLoader classLoader) {
        if (bridge != null) {
            return;
        }
        try {
            System.loadLibrary("dexkit");
        } catch (Throwable t) {
            Log.w(TAG, "加载 dexkit 原生库失败: " + t);
        }
        try {
            bridge = DexKitBridge.create(classLoader, false);
            Log.i(TAG, "DexKitBridge 初始化完成, dex数量=" + bridge.getDexNum());
        } catch (Throwable t) {
            Log.w(TAG, "创建 DexKitBridge 失败: " + t);
            bridge = null;
        }
    }

    /** 获取桥（未初始化时为 null）。 */
    public static DexKitBridge get() {
        return bridge;
    }

    public static boolean isReady() {
        return bridge != null;
    }
}