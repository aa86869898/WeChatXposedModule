# R8 混淆规则 - 微信助手 v3

# 保持 Xposed 入口 (包含无参构造器，Xposed 反射加载必须)
-keep class com.leshao.v3.MainHook {
    *;
}

# 保持反射调用 Hook 类 (禁止混淆类名和方法名)
-keep class com.leshao.v3.hook.** {
    *;
}

# 保持数据库相关类
-keep class com.leshao.v3.db.** {
    *;
}

# 保持数据模型 (Gson 或其他序列化需要)
-keep class com.leshao.v3.model.** {
    *;
}

# 保持 LogWriter 的日志方法
-keep class com.leshao.v3.LogWriter {
    public void init(...);
    public static void log(...);
}

# 保持 ContextManager
-keep class com.leshao.v3.ContextManager {
    *;
}

# Xposed 回调方法保持
-keepclasseswithmembers class * {
    public void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam);
}

-keepclasseswithmembers class * {
    public static void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam);
}
