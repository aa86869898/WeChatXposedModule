# LeShaoWeChat V3 —— 微信 Xposed 模块完整工程源码

> 微信 8.0.78(3180) 助手模块（乐少助手）完整可编译工程。
> 包名：`com.leshao.v3`（另含 `com.leshao.ai` 智能助手子模块、`com.example.wxbubble` 气泡组件）。
> 当前版本：v3.0.247（versionCode 30257）。

---

## 1. 编译环境

| 项 | 版本/值 |
|---|---|
| Gradle Wrapper | **8.5**（`gradle/wrapper/gradle-wrapper.properties`） |
| Android Gradle Plugin | **8.2.0** |
| Kotlin | 1.9.22 |
| JDK | **17**（sourceCompatibility / targetCompatibility = 17） |
| compileSdk | 36 |
| buildToolsVersion | 35.0.0 |
| minSdk / targetSdk | 24 / 36 |
| AndroidX | 启用 |
| 本地 SDK 路径 | `local.properties` 的 `sdk.dir`（示例：`/opt/android-sdk`，按本机路径修改） |

### 1.1 gradle.properties 特殊项

```properties
android.aapt2FromMavenOverride=/opt/android-sdk/build-tools/35.0.1/aapt2
```

- 若本机不存在该 aapt2 路径，**删除这一行**即可，AGP 会回退到 SDK 自带 aapt2。
- 其余配置（`android.useAndroidX`、`nonTransitiveRClass`、`suppressUnsupportedCompileSdk`）保持原样。

---

## 2. 构建命令

```bash
# 首次构建（下载依赖）
./gradlew :app:assembleDebug

# release 正式包（R8 混淆 + 资源收缩 + 签名）
./gradlew :app:assembleRelease

# 与发布流水线一致的命令（clean 后打 release）
./gradlew :app:clean :app:assembleRelease -x lint
```

产物输出到 `app/build/outputs/apk/release/LeShaoWeChat-v<versionCode>.apk`。

---

## 3. 签名配置

### 3.1 keystore 文件路径（工程内）

| 文件 | 用途 |
|---|---|
| `app/release.keystore` | **正式签名**（debug / release 构建共用） |
| `app/debug.keystore` | 备用调试签名（当前 build 类型实际均走 release.keystore） |

### 3.2 签名口令来源

`app/build.gradle.kts` 通过 `signingConfigs.release` 读取 `local.properties` 或同名环境变量：

| 键 | 默认/来源 | 说明 |
|---|---|---|
| `LESHAO_STORE_PASSWORD` | `local.properties` 或环境变量（**必填**） | keystore 存储密码 |
| `LESHAO_KEY_PASSWORD` | `local.properties` 或环境变量（**必填**） | key 密码 |
| `LESHAO_KEY_ALIAS` | 缺省 `leshao` | 别名 |

`local.properties` 已随工程导出，内容：

```properties
sdk.dir=/opt/android-sdk
LESHAO_STORE_PASSWORD=leshao2024
LESHAO_KEY_PASSWORD=leshao2024
LESHAO_KEY_ALIAS=leshao
```

> ⚠️ 迁移到新机器后：
> 1. 修改 `sdk.dir` 为你的 Android SDK 路径；
> 2. 若不想在 local.properties 留明文密码，可删除这三行并设置同名**环境变量**；
> 3. keystore 文件本身已包含在 `app/` 下，二次打包可直接沿用原签名（SHA-256 指纹 `28:FD:B4:...` 不变）。

### 3.3 签名校验/读取相关源码

- `app/src/main/java/com/leshao/v3/hook/SignatureDump.java` —— 真机签名/方法/字段签名诊断工具（`ENABLED = BuildConfig.DEBUG`，release 默认关闭）。
- `app/build.gradle.kts` —— signingConfigs 完整配置（enableV1/V2/V3Signing 均开启）。
- `app/src/main/java/com/leshao/v3/MainHook.java` —— 模块入口（Xposed 初始化，含签名相关启动逻辑）。

---

## 4. DexKit 字符串检索模块

依赖：`org.luckypray:dexkit:2.3.0`（`app/build.gradle.kts` dependencies）。

### 入口类（字符串检索 / 类搜索 / 方法搜索）

| 类 | 职责 |
|---|---|
| **`com.leshao.v3.hook.DexKitHelper`** | **主入口**。DexKitBridge 封装：findClassesByString / findMethodsByString / findClassesUsingField / findMethodUsingString 等，带内存缓存与基线文件（`dexkit_baseline.json`）。所有 hook 通过它定位微信混淆类/方法。 |
| `com.leshao.ai.hook.dexkit.DexKitAdapter` | AI 子模块的 DexKit 桥接（复用 DexKitHelper 能力）。 |
| `com.leshao.v3.ui.DexKitScanDialog` | DexKit 全量扫描进度对话框（首次安装时展示扫描进度）。 |
| `com.leshao.ai.util.DexKitBridgeHolder` | AI 子模块持有 DexKitBridge 实例的容器。 |

### 典型调用链

```
DexKitHelper.findClassesByString(cl, "MicroMsg.ImageScanCodeManager")
  → DexKitBridge.createBridge(apkPath, cachePath)
  → bridge.findClass(FindClass...)
  → ClassData / MethodData（反射目标类/方法）
```

> DexKit 相关源码、调用逻辑、依赖配置**完整保留**，未做精简或修改。

---

## 5. 包结构（原样保留）

```
com.leshao.v3
├── MainHook.java                     # Xposed 模块入口
├── hook/**                           # 全部 Xposed Hook（含 DexKitHelper、SignatureDump、AutoGroupQrHook 等）
├── service/**                        # 后台服务（TTS、消息处理、激活校验、转发等）
├── model/**                          # 数据模型
├── ui/**                             # 设置页 UI（含 ui/widgets 组件库）
├── wm/**                             # 微信窗口/聊天界面增强
├── music/**                          # 点歌服务
├── db/**                             # 数据库
├── ContextManager / LogWriter / ...  # 基础工具
com.leshao.ai                          # AI 助手子模块（hook/dexkit/api/data/ui）
com.example.wxbubble                   # 聊天气泡组件
```

> 注：`proguard-rules.pro` 中保留了 `com.leshao.v3.dispatch.**` 的 keep 规则（历史兼容），当前源码中该包已并入 `hook/**`，规则原样保留不影响编译。

---

## 6. R8 / ProGuard 规则

`app/proguard-rules.pro` 已完整导出：

- `-keep class com.leshao.v3.hook.** { *; }`（含内部类 `$*`）
- `-keep class com.leshao.v3.service.**` / `model.**` / `ui.**` / `dispatch.**`
- `-keep class com.leshao.ai.**` / `com.leshao.ai.**$*`
- `-dontshrink` / `-dontoptimize`（关闭危险优化，保护 Xposed 回调）
- `-dontwarn de.robv.android.xposed.**`（xposed-api-82.jar 仅 compileOnly，由框架运行时提供）

---

## 7. 目录树

```
LeShaoWeChatV3-source
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── local.properties
├── gradlew / gradlew.bat
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    ├── debug.keystore
    ├── release.keystore
    ├── proguard-rules.pro
    ├── libs/
    │   └── xposed-api-82.jar
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── assets/xposed_init
        │   ├── res/
        │   │   ├── drawable*/ layout/ mipmap-*/ values/ xml/ raw/
        │   └── java/
        │       ├── com/example/wxbubble/
        │       ├── com/leshao/ai/**
        │       └── com/leshao/v3/**
        │           ├── MainHook.java
        │           ├── hook/**（DexKitHelper.java、SignatureDump.java、AutoGroupQrHook.java 等 40+ 类）
        │           ├── service/** model/** ui/** wm/** music/** db/**
        │           └── ...（共 205 个源文件）
```

---

## 8. 备注

- `download/`、`index.html`、`upload_server.py`、`死代码/` 等**发布辅助文件不属于编译工程**，未随源码导出。
- 首次打开工程 Android Studio 会自动同步 Gradle；若 aapt2 覆盖路径报错，按 §1.1 删除 `android.aapt2FromMavenOverride` 行。
- 模块为 Xposed/LSPosed 模块，需在 LSPosed 中勾选作用域 `com.tencent.mm` 并重启生效。