# LeShaoWeChat V3 代码审查报告

- 审查日期：2026-10-02
- 审查方式：静态只读审查（未修改任何代码）
- 审查范围：`app/src/main/java/com/leshao/v3/`（主模块）与 `app/src/main/java/com/leshao/ai/`（AI 子模块）
- 审查重点：性能/卡顿、内存泄漏、逻辑正确性（NPE/并发/跨进程）、Xposed hook 安全、安全/隐私（硬编码密钥/token）、代码质量

---

## 1. 概述

共审阅约 40 个核心文件（合计约 7000+ 行），并针对关键模式做了全量静态搜索：

| 统计项 | 数值 |
| --- | --- |
| 全模块 Java 文件数 | 158 |
| 吞异常模式 `catch (Throwable ignored/t/e) {}` | 761 处 |
| `new Thread` + `Handler(Looper.getMainLooper())` 模式 | 5+ 处（AiAssistantPanel 内） |
| 硬编码密钥/token | 0 处（密钥均运行时从配置读取） |
| SSL 证书校验绕过（TrustManager/ALLOW_ALL） | 0 处 |
| 明文 HTTP 地址 | 0 处（仅注释提及 http 封面图，实际用 https） |

结论：**未发现硬编码密钥或证书校验绕过等直接高危漏洞**；主要问题集中在吞异常、主线程阻塞、静态引用泄漏、线程无生命周期管理、以及 hook 目标微信版本兼容性带来的大密度 try-catch。

---

## 2. P0 严重问题（建议优先修复）

### P0-1 主线程阻塞轮询等待微信内核初始化
- **位置**：`com/leshao/v3/ContextManager.java:112`、`com/leshao/v3/ContactRepository.java:193-209`
- **问题**：使用 `wait()` / 同步轮询循环（最长约 20 秒、`Thread.sleep(1500)` 重试）等待微信内核与 CsoLoader 就绪。若在消息回调或 `onResume` 路径上触发，会导致微信 UI 卡死 ANR。
- **建议**：改为异步回调/监听模式（如微信内核初始化事件通知后回调），轮询期间不得阻塞调用线程；至少将轮询放到工作线程并对外暴露 future/callback。

### P0-2 静态 Activity/Dialog 引用导致内存泄漏
- **位置**：`com/leshao/v3/ui/SubPageActivity.java:36-37`（`static Activity`、`static Dialog` 引用）
- **问题**：静态持有 Activity 和 Dialog 实例，页面销毁后无法被 GC 回收，反复进出页面会累积泄漏，长时间运行内存持续增长。
- **建议**：改用 `WeakReference<Activity>` / `WeakReference<Dialog>`，并在 `onDestroy` 中置空。

### P0-3 以弱口令直接打开微信数据库（安全性弱）
- **位置**：`com/leshao/v3/ContactRepository.java:189`（`md5(imei + uin).substring(0, 7)`）、`com/leshao/v3/db/DatabaseProvider.java:284-295`（hook WCDB open 捕获密码与 DB 引用到静态字段）
- **问题**：
  1. 数据库密码仅 7 位十六进制字符（约 28 bit 熵），且由 `imei + uin` 推导，具备可预测性；该密码被用于直接打开 `EnMicroMsg.db`。
  2. `DatabaseProvider` 通过反射 hook WCDB 的 `open*` 方法，在 `afterHookedMethod` 中把数据库密码（`pwd`）和 `SQLiteDatabase` 实例存入静态变量 `sPassword`/`sDatabase`，进程存活期间常驻内存。
  3. `LogWriter.log` 会记录数据库绝对路径（`ContactRepository.java:190`）与密钥类型/长度（`DatabaseProvider.java:286-289`，未记录密钥明文，但路径已属敏感信息）。
- **说明**：作为 Xposed 模块读取微信本地数据属于功能需求，但口令强度与日志路径仍建议收敛。
- **建议**：密码推导加入更多不可预测因子（如设备随机盐）并评估必要性；避免将 DB 引用与密码长期静态持有；日志脱敏数据库路径（只记录文件名）。

### P0-4 大规模吞异常（761 处），静默失败难以排查
- **分布**：`TtsVoiceSender.java` 231、`ChatFooterLongPressMenu.java` 99、`VoiceForwardHook.java` 94、`DexKitHelper.java` 93、`FavVoiceForwardHook.java` 70、`ConversationFilter.java` 70、`ChatBubbleHook.java` 67、`VoiceAutoPlay.java` 65、`ChatGroupHook.java` 59、`MsgForgeHook.java` 51、`AiAssistantPanel.java` 47、`MessageHook.java` 39、`RedPacketHook.java` 38 等。
- **问题**：大量 `catch (Throwable ignored) {}` 空捕获。虽然部分用于“微信版本字段不存在时降级”，但完全静默会导致：功能静默失效、错误难以复现、线上问题只能靠用户日志大海捞针。
- **建议**：区分“预期降级”与“异常”：
  - 预期降级（字段不存在）至少记录一次 debug 日志（带频率限制，避免刷日志）；
  - 非预期异常应 `LogWriter.log(TAG, "...", t)` 输出堆栈；
  - 对 `TtsVoiceSender` 这种单文件 231 处的情况，考虑提取统一工具方法（如 `SilentCatch.run(name, Runnable)`）。

---

## 3. P1 重要问题（建议近期修复）

### P1-1 消息处理全部 post 到主线程执行
- **位置**：`com/leshao/v3/hook/MessageHook.java:529`
- **问题**：接收消息后的去重、关键词匹配、回调通知等全部 `post` 到主线程；结合 P0-1 的同步等待，主线程负载高，微信聊天页易卡顿。
- **建议**：将消息处理链中的 DB 查询、反射、正则匹配移到专用工作线程，主线程只做 UI 刷新。

### P1-2 `new Thread` + `Handler` 模式泛滥，线程无统一管理
- **位置**：`com/leshao/ai/hook/wechat/AiAssistantPanel.java:888-889, 1314-1316, 1391`；另在 `VoiceForwardHook`、`FavVoiceForwardHook`、`TtsVoiceSender` 中大量出现。
- **问题**：每处交互都新建裸线程，无线程池复用；多数线程未命名（仅 `"leshao-ai-targets"` 一处命名），无法用 `adb shell ps -T` 定位线程归属；线程结束后无法统一取消，Activity 销毁后 `runOnUiThread`/`Handler.post` 仍可能执行。
- **建议**：封装统一 `AsyncTask`/`ThreadPoolExecutor`（单例），所有耗时操作走同一线程池；`post` 回主线程前检查 `activity.isFinishing()`（部分位置已检查，需保持一致）。

### P1-3 AI 子模块线程池无生命周期管理
- **位置**：`com/leshao/ai/hook/wechat/AIBotCore.java`（`Executors.newFixedThreadPool(2)`）
- **问题**：线程池随进程常驻，未在模块卸载/禁用时 `shutdown`；若 LLM 请求超时或接口挂起，线程池资源长期被占用。
- **建议**：请求统一设置超时（OpenAI/Anthropic 客户端已设 30s/120s 超时，需确保调用链不绕过）；提供 `shutdown()` 出口。

### P1-4 跨进程 ContentProvider exported=true 仅靠 UID 白名单
- **位置**：`com/leshao/ai/data/AiDataProvider.java`、`com/leshao/ai/hook/wechat/BridgeReceiver.java`
- **问题**：`authority=com.leshao.v3.aiconfig` 的 ContentProvider 与显式广播 `exported=true`，依赖调用方 UID 判断是否可信。微信主进程与模块进程 UID 相同（同 APK 注入），但其他同 UID 应用不可控；Android 11+ 包可见性会干扰显式广播。
- **建议**：对 provider 的每个操作再次校验 caller UID == 自身 UID；广播改为 `setPackage()` 定向；敏感数据（API Key 等）仅通过 provider 内私有调用传递，不放入广播 intent。

### P1-5 hook 版本兼容导致的高密度反射与 try-catch
- **位置**：`DexKitHelper.java`、`VoiceForwardHook.java`、`FavVoiceForwardHook.java`、`VersionCompat.java`
- **问题**：为兼容多个微信版本，代码中大量使用 DexKit 动态发现类/方法 + 反射调用，失败时静默降级。这带来两个风险：
  1. 新版微信一旦改动内部实现，功能静默失效（无告警）；
  2. 反射调用热点路径性能开销大。
- **建议**：为关键路径（消息发送、红包检测）增加“功能自检”日志，在每次 hook 安装后主动验证一次目标方法是否命中，未命中时输出告警日志。

### P1-6 DexKit 扫描缓存依赖 MMKV 多进程一致性
- **位置**：`com/leshao/v3/hook/DexKitHelper.java`（MMKV 缓存扫描结果）
- **问题**：主进程与 `:push` 进程都可能访问 MMKV 缓存，若缓存写入未同步或版本升级后未失效，可能拿到过期类描述。
- **建议**：缓存 key 加入微信版本号；写入使用 `putString` 后同步 `apply`/`commit`；启动时校验缓存有效性。

---

## 4. P2 改进建议

- **P2-1 超大 UI 类拆分**：`AiAssistantPanel.java`（2218 行）、`MainActivity.java`（1232 行）。建议按页面/功能拆分为多个 View 与 Adapter，提升可维护性。
- **P2-2 重复代码收敛**：模型列表选择、音色多选等弹窗结构高度相似（`AiAssistantPanel.java:932-979` 与 `1297-1375`），可抽取通用“异步加载 + 列表选择弹窗”组件。
- **P2-3 日志分级**：`LogWriter.log` 全量写文件，部分高频路径（如 `MessageHook`、`VoiceForwardHook` 每次调用都写日志）会导致日志文件膨胀与 IO 开销。建议增加级别过滤（debug/info/error）与文件大小轮转。
- **P2-4 版本号三处同步**：`app/build.gradle.kts`（versionCode/versionName）与 `MainHook.java`（`MODULE_BUILD`/`MODULE_VERSION_CODE`）需手工同步，易遗漏。建议改为从 `BuildConfig` 或 manifest meta-data 单一来源读取。
- **P2-5 资源管理**：`TtsVoiceSender`、`OnlineMusicPageView` 中下载/播放回调需统一在页面销毁时取消；现有实现依赖 `activity.isFinishing()` 检查，建议统一封装生命周期感知的异步组件。

---

## 5. 做得好的方面

- **无硬编码密钥**：所有 API Key 从配置/SharedPreferences 读取，且 `ApiUrl.mask()`（`com/leshao/ai/api/ApiUrl.java:59-64`）在日志中只输出前 3 后 4 位，脱敏规范。
- **密钥粘贴自动清洗**：`ApiUrl.normalizeKey()` 会剔除零宽字符/引号/空白，避免用户误粘贴导致请求失败，体验细节到位。
- **AI 防死循环**：`SendGuard` 使用零宽水印 `\u200B\u200C` 标记 AI 消息，`MsgReceiveHook` 过滤链在入库前拦截，设计合理。
- **异步化意识在增强**：`AiAssistantPanel` 中 `collectTargets` 已移入后台线程（v1016 注释明确说明避免阻塞主线程）；`TriggerEngine.dispatch` 投递到工作线程避免阻塞微信 DB 写锁。
- **无证书校验绕过**：未发现 `TrustManager`/`ALLOW_ALL_HOSTNAME_VERIFIER` 等危险实现。
- **AI 客户端超时完备**：OpenAI/Anthropic 客户端均设置 connectTimeout=30s、readTimeout=120s。

---

## 6. 附录：代表性位置速查表

| 问题 | 文件:行号 |
| --- | --- |
| 主线程同步轮询 | `com/leshao/v3/ContextManager.java:112` |
| 数据库固定弱口令 | `com/leshao/v3/ContactRepository.java:189` |
| WCDB open hook 捕获密码 | `com/leshao/v3/db/DatabaseProvider.java:284-295` |
| 消息处理 post 主线程 | `com/leshao/v3/hook/MessageHook.java:529` |
| 静态 Activity/Dialog 引用 | `com/leshao/v3/ui/SubPageActivity.java:36-37` |
| 裸线程 + Handler | `com/leshao/ai/hook/wechat/AiAssistantPanel.java:888-889, 1314-1316, 1391` |
| AI 线程池无 shutdown | `com/leshao/ai/hook/wechat/AIBotCore.java` |
| 跨进程 provider/广播 | `com/leshao/ai/data/AiDataProvider.java`、`com/leshao/ai/hook/wechat/BridgeReceiver.java` |
| 吞异常高发文件 | `TtsVoiceSender.java`(231)、`ChatFooterLongPressMenu.java`(99)、`VoiceForwardHook.java`(94)、`DexKitHelper.java`(93) |
| API 密钥脱敏 | `com/leshao/ai/api/ApiUrl.java:59-64` |
| AI 消息防循环水印 | `com/leshao/ai/hook/wechat/SendGuard.java` |

---

*报告结束。审查为静态只读，未对任何代码做修改。*