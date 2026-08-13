# R8 混淆规则 - 微信助手 v3

# ========== 1. 保留内部类基础属性 ==========
-keepattributes InnerClasses,EnclosingMethod,Signature

# ========== 2. 完整保留模块入口 ==========
-keep class com.leshao.v3.MainHook { *; }

# ========== 3. 保留 hook 包下所有类 + 全部内部类（带$的回调类）==========
-keep class com.leshao.v3.hook.** { *; }
-keep class com.leshao.v3.hook.**$* { *; }

# ========== 4. 保留 service 包所有类及内部类（定时任务回调）==========
-keep class com.leshao.v3.service.** { *; }
-keep class com.leshao.v3.service.**$* { *; }

# ========== 5. 实体、API 相关类防止反射异常 ==========
-keep class com.leshao.v3.model.** { *; }
-keep class com.leshao.v3.model.**$* { *; }
-keep class com.leshao.v3.dispatch.** { *; }
-keep class com.leshao.v3.dispatch.**$* { *; }
-keep class com.leshao.v3.ui.** { *; }
-keep class com.leshao.v3.ui.**$* { *; }
-keep class com.leshao.v3.ai.** { *; }
-keep class com.leshao.v3.ai.**$* { *; }
-keep class com.leshao.v3.ContextManager { *; }

# ========== 6. 关闭 R8 危险优化（关键！避免匿名回调被销毁）==========
-dontshrink
-dontoptimize
-dontnote
-dontwarn

# ========== 7. 屏蔽 Xposed 无关警告 ==========
-dontwarn de.robv.android.xposed.**
-keep class de.robv.android.xposed.** { *; }

# ========== Xposed 回调方法保持 ==========
-keepclasseswithmembers class * {
    public void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam);
}
-keepclasseswithmembers class * {
    public static void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam);
}
