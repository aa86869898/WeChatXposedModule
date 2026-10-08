package com.leshao.ai.util

import android.util.Log

class DexKitBridgeHolder private constructor() {

    companion object {
        private const val TAG = "LeshaoAI.DexKit"

        @Volatile private var initialized = false

        @JvmStatic
        @Synchronized
        fun init(classLoader: ClassLoader?) {
            if (initialized) {
                return
            }
            initialized = true
            Log.i(TAG, "DexKit 统一由 v3 DexKitHelper 提供, 跳过独立桥创建")
        }

        @JvmStatic
        fun isReady(): Boolean {
            return false
        }
    }
}