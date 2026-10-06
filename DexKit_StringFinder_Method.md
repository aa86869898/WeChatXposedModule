# DexKit「找字符串 → 类/方法」方法集（v2 核查修正版）
> 已对官方源码 + 文档逐条核对（LuckyPray/DexKit master）。**v1 有 4 处错，§6 是核查记录。**
> Maven：`implementation 'org.luckypray:dexkit:2.x'`（2.0 起 artifactId 由 `DexKit` 改为 `dexkit`）。

---

## 0. 一句话结论（先看这里）
- DexKit 字符串维度只有一个匹配器 **`usingStrings`**（挂在 `ClassMatcher`/`MethodMatcher` 上，作用于“该类/该方法的代码引用的字符串常量”）。
- **默认就是"包含(子串)"匹配，不是精确**（官方示例 `usingStrings("VipCheckUtil","userInfo:")` 命中 `Log.d("VipCheckUtil","userInfo: xxxx")` 可证；`StringMatcher` 默认值亦为 `Contains`）。
- 需要 StartsWith/EndsWith/正则时用 **`StringMatcher` + `StringMatchType`**（枚举：`Contains/StartsWith/EndsWith/SimilarRegex/Equals`）。
- 拿到 `MethodData` 后直接 `getMethodInstance(hostClassLoader)` → `java.lang.reflect.Method` 丢给 Xposed，**不需要自己 Class.forName**。

---

## 1. 初始化（官方写法）

```java
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.LibLoader;   // 官方的 so 加载器（demo 用法）
...
LibLoader.loadLibrary("dexkit");        // Android AAR 通常自动加载，必要时显式 load
String apkPath = loadPackageParam.appInfo.sourceDir;

try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {   // 单个实例，用完自动 close
    doSearch(bridge);
}
// 也可 DexKitBridge.create(new byte[][]{ dexBytes })   // 直接吃 dex 字节（多 dex 自己逐个建桥）
```
> 官方 lint 规则明确：**不要反复 create**（建桥有开销），全局只建一个并自行管生命周期（`close()`/`use{}`）。

---

## 2. Java 链式 API（官方形态，直接用）

```java
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.query.matchers.base.StringMatcher;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.ClassData;
import java.lang.reflect.Method;

// A. 方法粒度：哪些方法体用了该串（★ 定位 Hook 点用这个）
List<MethodData> methods = bridge.findMethod(FindMethod.create()
    .matcher(MethodMatcher.create()
        .usingStrings("newsendmsg")            // 默认 Contains（子串）
    )
    .searchPackages("com.tencent.mm")          // 可选：缩小范围，提速
    .findFirst(false)
);
for (MethodData md : methods) {
    Method m = md.getMethodInstance(hostClassLoader);   // 直接得到反射 Method
    log(md.getClassName() + "->" + md.getMethodName() + " " + md.getMethodSign());
    // XposedBridge.hookMethod(m, ...);
}

// 只要唯一结果
MethodData one = bridge.findMethod(FindMethod.create()
    .matcher(MethodMatcher.create().usingStrings("Kernel not initialized by MMApplication!"))
).single();

// B. 类粒度：哪些类用了该串（任一方法引用即命中，噪声大）
List<ClassData> classes = bridge.findClass(FindClass.create()
    .matcher(ClassMatcher.create().usingStrings("Select_Conv_User"))
);

// C. 多关键字（OR，一次给全）
List<MethodData> multi = bridge.findMethod(FindMethod.create()
    .matcher(MethodMatcher.create().usingStrings("newsendmsg", "receivewxhb", "openwxhb"))
);
```

## 3. 显式匹配类型（StartsWith / EndsWith / 正则）

```java
// Contains（默认，也可显式）
MethodMatcher.create().usingStrings(StringMatcher.create().value("newsendmsg").matchType(StringMatchType.Contains))

// 前缀 / 后缀
MethodMatcher.create().usingStrings(StringMatcher.create().value("MicroMsg.").matchType(StringMatchType.StartsWith))
MethodMatcher.create().usingStrings(StringMatcher.create().value(".ui.LuckyMoneyBusiReceiveUI").matchType(StringMatchType.EndsWith))

// 正则
MethodMatcher.create().usingStrings(StringMatcher.create().value("updateRequired \\[%d,%d\\]").matchType(StringMatchType.SimilarRegex))

// 忽略大小写
StringMatcher.create().value("newsendmsg").matchType(StringMatchType.Contains).ignoreCase(true)
```
> Kotlin DSL 等价：`bridge.findMethod { matcher { usingStrings("onCreate") } }`；
> 注解里可见 DSL 支持带 matchType 的写法：`type("Router", StringMatchType.EndsWith)`。
> 若你的 dexkit 版本 Java 侧没有 `usingStrings(StringMatcher...)` 重载，退回 `usingStrings(vararg String)`（默认 Contains 已够用）或升级版本。

## 4. 批量字符串搜索（官方特色功能，多组关键词首选）

```java
// 目录里存在 BatchFindClassUsingStrings / BatchFindMethodUsingStrings
// 配置项：searchPackages / excludePackages / ignorePackagesCase / searchInClasses / matchers(List<StringMatchersGroup>)
// 语义：把关键词分组批量查，内部做了字符串场景优化，多组关键字不会线性变慢
```
> 单接口确切形态各版本有调整，建议对着你依赖版本的 kdoc/源码用；单组查询用 §2/§3 足够。

## 5. 数字段/其他实用项
- `bridge.getDexNum()`：宿主 dex 数（多 dex 时先看这个）。
- 结果元数据：`ClassData.name`、`MethodData.getMethodName()/getMethodSign()/descriptor/paramNames`、`getMethodInstance(ClassLoader)`。
- 提速：`FindMethod/FindClass` 的 `searchPackages` / `excludePackages` / `findFirst=true`。
- `androidx`/`kotlin` 等公共包用 `excludePackages` 排掉，噪声立降。

---

## 6. 核查记录（v1 的 4 处错误，已修）
| # | v1 错误 | 实际（官方源码/文档证实） |
|---|---|---|
| 1 | **说 `usingStrings` 默认"精确匹配"**，要 `.*kw.* + regex=true` | **默认就是 Contains（子串）**；正则走 `StringMatchType.SimilarRegex`，没有 boolean regex 参数 |
| 2 | Java 代码用了 Kotlin lambda DSL：`bridge.findMethod(c -> c.matcher()...)` | Java 是链式：`bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingStrings(...)))`，返回 `List`，`.single()` 取唯一 |
| 3 | §1 初始化第 3 段是"自比较三元"垃圾代码（`myPid()==myPid()?...:null`） | 官方写法：`DexKitBridge.create(loadPackageParam.appInfo.sourceDir)` + try-with-resources；另有 `create(byte[][] dex)` |
| 4 | 池扫用了未经核实的 `MultiDexBase` | 未见该 API 文档证据；改用 `DexKitBridge.create(new byte[][]{dex})` 逐 dex 建桥（已证实），或 dexlib2 自行 `getStringIds()` |

## 7. 可直接运行的封装（Java）

```java
public final class DexKitStringFinder {
    private DexKitStringFinder() {}
    /** 全局只建一次；用完调用方负责 close() */
    public static DexKitBridge open(String apkPath) { return DexKitBridge.create(apkPath); }

    /** 子串：方法粒度（推荐，直接拿 Hook 点） */
    public static List<MethodData> methods(DexKitBridge b, String kw) {
        return b.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create().usingStrings(kw))
                .findFirst(false));
    }

    /** 子串：类粒度 */
    public static List<ClassData> classes(DexKitBridge b, String kw) {
        return b.findClass(FindClass.create()
                .matcher(ClassMatcher.create().usingStrings(kw)));
    }

    /** 指定匹配类型：方法粒度 */
    public static List<MethodData> methodsM(DexKitBridge b, String kw, StringMatchType t) {
        return b.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                        .usingStrings(StringMatcher.create().value(kw).matchType(t))));
    }

    /** 打印 + 直接给 Xposed 用的 Method */
    public static void dump(DexKitBridge b, ClassLoader cl, String kw) {
        for (MethodData md : methods(b, kw)) {
            try {
                Method m = md.getMethodInstance(cl);
                log("HIT " + md.getClassName() + "->" + md.getMethodName() + " " + md.getMethodSign()
                    + "  -> " + (m != null ? m.toString() : "(未加载)"));
            } catch (Throwable t) { log("err " + t); }
        }
    }
}
// 用法：
// try (DexKitBridge b = DexKitStringFinder.open(lpparam.appInfo.sourceDir)) {
//     DexKitStringFinder.dump(b, lpparam.classLoader, "newsendmsg");
//     DexKitStringFinder.dump(b, lpparam.classLoader, "Select_Conv_User");
//     DexKitStringFinder.dump(b, lpparam.classLoader, "Kernel not initialized");
// }
```

## 8. 坑位清单（本轮修订后的最终版）
1. **`usingStrings` 默认 Contains（子串）**，别再自己包 `.*`；要前缀/后缀/正则才需要 `StringMatcher.matchType`。
2. 定位 Hook 点**必须用 `findMethod`**；`findClass` 会把"类里任意方法引用"都算命中（如 `Select_Conv_User` 命中 210+ 类，绝大部分无关）。
3. `MethodData.getMethodInstance(hostClassLoader)` 是最短 Hook 路径；取不到（类未加载/被 relocate）时退回 `Class.forName(getClassName())` + `getMethodSign` 解析。
4. **类名是 dex 真名**：扁平根包（`v51.r0`/`gp0.j1`/`x51.b0`）**没有 `com.tencent.mm.` 前缀**；`getMethodSign()` 形如 `(IILandroid/content/Intent;)V`。
5. 全局一个 `DexKitBridge`，用完 `close()`；`searchPackages/excludePackages/findFirst` 能显著提速。
6. DexKit 只匹配**代码里的字符串常量**，不含 strings.xml 资源；资源索引需另扫 ARSC。
7. 多 dex：`getDexNum()` 确认；`create(apkPath)` 覆盖主 apk 内全部 dex；split apk / 插件 dex 需 `create(byte[][])` 逐个建桥。
8. `usingStrings` 是 OR 语义（多个串命中任一即算），不是 AND。
