# R8 混淆规则 - 微信助手 v3

# ========== 强制保留 InnerClasses / EnclosingMethod 属性 ==========
# 禁止 R8 删除内部类信息，所有 $ 标识内部类必须保留
-keepattributes InnerClasses,EnclosingMethod,Signature,Exceptions

# ========== Xposed 入口 ==========
-keep class com.leshao.v3.MainHook {
    *;
}

# ========== Hook 类及其所有内部类（含匿名内部类、静态内部类） ==========
# 通配 $* 覆盖所有内部类：$1(匿名类), $InnerClass(命名内部类), $$ExternalSyntheticLambda*(脱糖lambda)
-keep class com.leshao.v3.hook.** {
    *;
}
-keep class com.leshao.v3.hook.**$* {
    *;
}

# ========== Service 类及其内部类 ==========
-keep class com.leshao.v3.service.** {
    *;
}
-keep class com.leshao.v3.service.**$* {
    *;
}

# ========== Dispatch 类及其内部类 ==========
-keep class com.leshao.v3.dispatch.** {
    *;
}
-keep class com.leshao.v3.dispatch.**$* {
    *;
}

# ========== 数据库相关类 ==========
-keep class com.leshao.v3.db.** {
    *;
}
-keep class com.leshao.v3.db.**$* {
    *;
}

# ========== 数据模型 ==========
-keep class com.leshao.v3.model.** {
    *;
}
-keep class com.leshao.v3.model.**$* {
    *;
}

# ========== UI 类 ==========
-keep class com.leshao.v3.ui.** {
    *;
}
-keep class com.leshao.v3.ui.**$* {
    *;
}

# ========== ContextManager ==========
-keep class com.leshao.v3.ContextManager {
    *;
}

# ========== Xposed 回调方法保持 ==========
-keepclasseswithmembers class * {
    public void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam);
}
-keepclasseswithmembers class * {
    public static void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam);
}

# ========== 禁止混淆 Xposed API 相关引用 ==========
-dontwarn de.robv.android.xposed.**
-keep class de.robv.android.xposed.** { *; }
