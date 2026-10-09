package com.leshao.v3

import android.content.Context
import android.content.SharedPreferences

/**
 * 主分身统一配置入口。
 *
 * 主微信与分身微信均返回各自的原生 SharedPreferences(读写自己的 /data/.../shared_prefs/)。
 * v1038 起系统克隆分身不再在模块入口被拦截(旧 v965 的 InstanceManager.isCloneApp() 拦截已移除,
 * 该判定现仅作探测), 因此模块可能运行于机主用户、系统克隆分身与 LSPosed MultiApp 等身份中,
 * 各身份读写各自的 prefs 目录, 天然互不干扰。
 */
object UnifiedPrefs {

    @JvmStatic
    fun get(ctx: Context?, name: String): SharedPreferences? {
        if (ctx == null) return null
        return ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
    }
}