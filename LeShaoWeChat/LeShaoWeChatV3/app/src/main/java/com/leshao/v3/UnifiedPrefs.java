package com.leshao.v3;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 主分身统一配置入口。
 *
 * 主微信与分身微信均返回各自的原生 SharedPreferences(读写自己的 /data/.../shared_prefs/)。
 * v965: 系统克隆分身进程已在模块入口被 InstanceManager.isCloneApp() 拦截,
 * 模块实际只会运行在机主用户与 LSPosed MultiApp 等放行身份中,
 * 各身份读写各自的 prefs 目录, 天然互不干扰。
 */
public class UnifiedPrefs {

    public static SharedPreferences get(Context ctx, String name) {
        if (ctx == null) return null;
        return ctx.getSharedPreferences(name, Context.MODE_PRIVATE);
    }
}
