package com.leshao.v3;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 主分身统一配置入口。
 *
 * 主微信与分身微信均返回各自的原生 SharedPreferences(读写自己的 /data/.../shared_prefs/)。
 * 分身微信进程内 su 不可见，无法读主微信 prefs，因此配置一致性改由 ConfigSync 负责：
 * 主微信(user 0)在启动/需要时用 su 把关键 prefs 推送到所有分身的 prefs 目录。
 */
public class UnifiedPrefs {

    public static SharedPreferences get(Context ctx, String name) {
        if (ctx == null) return null;
        return ctx.getSharedPreferences(name, Context.MODE_PRIVATE);
    }
}
