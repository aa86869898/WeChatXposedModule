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
-keep class com.leshao.v3.ContextManager { *; }

# ========== LeshaoAI 模块 (com.leshao.ai) ==========
-keep class com.leshao.ai.** { *; }
-keep class com.leshao.ai.**$* { *; }


# ========== 6. 关闭 R8 危险优化（关键！避免匿名回调被销毁）==========
-dontshrink
-dontoptimize
-dontnote
-dontwarn

# ========== 7. 屏蔽 Xposed 无关警告 ==========
-dontwarn de.robv.android.xposed.**
# 注意: 不能 -keep de.robv.android.xposed.** —— 会把 libs/xposed-api-82.jar 的裁剪版
# 类打进 APK DEX, 与 LSPosed 运行时真实 API 冲突(如 findAndHookMethod 编译期为 void、
# 运行时返回 Unhook, 造成 NoSuchMethodError)。xposed api 由框架在运行时提供, 必须排除打包。

# ========== Xposed 回调方法保持 ==========
-keepclasseswithmembers class * {
    public void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam);
}
-keepclasseswithmembers class * {
    public static void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam);
}
