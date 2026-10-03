# 乐少微信 v3.0.1.3 全局只读安全/质量审查报告

- 审查范围：`/workspace/LeShaoWeChat/LeShaoWeChatV3` 全部 Java 源码（约 120 个文件，重点为 `MainHook`、`HookManager`、`DexKitHelper`、`AntiRecallHook`、`WxForwardReplaceHook`、`TtsVoiceSender`、`VoiceAutoPlay`、`ContextManager`、`ui/` 目录）
- 审查方式：纯静态只读，未修改任何代码，未运行 gradle，未改动 versionCode/versionName
- 依据日志：`/workspace/leshao_v3_log.txt`（1233 行，重点 300-900 行与 900-1233 行）
- 关联资料：上次报告 `CodeReviewReport_LeShaoWeChatV3.md`（132 行）
- 结论概览：本次审查发现 **3 个 P0 级新增问题**（均与日志中三个"为什么"直接相关），沿用上次报告的 3 个 P0 遗留问题，另发现 4 个 P1、4 个 P2。

---

## 一、概览

| 严重级别 | 数量 | 摘要 |
|---|---|---|
| P0 严重 | 3 新增 + 3 遗留 | activateAll 串行无超时导致 9 任务全部瘫痪；DexKit 桥每次调用重建；WxForwardReplaceHook 未安装；遗留：ContextManager 轮询阻塞、SubPageActivity 静态状态、WCDB 弱口令 |
| P1 中等 | 4 | AntiRecallHook 失败后不可重试；TTS 单线程池+3s 阻塞；日志含 wxid/uin 敏感信息；DexKit 搜索失败静默无日志 |
| P2 建议 | 4 | no_wrap 页面固定 90% 屏高留白；模块版本常量与 APK 不一致；桥复用建议；HookManager 统计口径混杂 |

**核心结论先行**：日志中三个现象是**同一链路**上的三个表现——

1. 弹窗未弹出：主进程 MMKV 缓存命中，未触发全量扫描，`sShouldShowScanDialog` 从未置位；
2. 无 `activateAll DONE`：`leshao-hook-activate` 线程执行第 1/9 个任务 `AntiRecallHook` 时，卡在全包 DexKit 字符串搜索中，**串行无超时**的调度器导致后续 8 个任务全部排队、`DONE` 永不打印；
3. `WxForwardReplaceHook` 无日志：它是第 9 个任务，**从未被执行**，故既无安装日志也无任何 `sig`/`HIT` 日志。

---

## 二、P0 严重问题

### P0-1（新增，根因 ②③）：HookManager.activateAll 串行执行且无超时，单任务卡死拖垮全部 9 个任务

- **位置**：`app/src/main/java/com/leshao/v3/hook/HookManager.java:78-92`（activateAll）、`HookManager.java:94-108`（runTask）
- **问题描述**：
  - `activateAll()` 新建唯一线程 `leshao-hook-activate`，在 `for` 循环中**串行**执行 9 个 pending 任务；
  - `runTask` 只 catch `Throwable` 记录 FAIL，**没有超时机制**。若某任务 `run()` 永不返回（阻塞在 DexKit 原生搜索、死锁、无限循环），后续任务永远不会开始，`activateAll DONE` 永远不会打印；
  - 日志第 887-888 行证实：`[1/9] START AntiRecallHook` → `install... enabled=true ...` 后，该线程再无任何输出，而 heartbeat 持续到 17:13（约 9 分钟）说明进程存活。
- **风险等级**：P0 —— 一个 hook 的重型 DexKit 查询就能让"转发替换"“聊天气泡”等 8 个功能集体静默失效，且无任何失败日志。
- **修复建议**：
  1. `activateAll` 改为对每个任务提交到线程池并 `Future.get(timeout)`（建议 15-30s），超时后打印 `[i/9] STUCK xxx` 并**继续执行后续任务**；
  2. 将强依赖 DexKit 全包搜索的任务（如 `AntiRecallHook`）与轻量任务（如 `WxForwardReplaceHook`）**并行执行**，或把 `AntiRecallHook` 的锚点解析放到扫描阶段批量完成并缓存，运行时只做反射安装。

### P0-2（新增，根因 ②）：DexKitHelper 每次 find* 调用都创建并关闭原生桥，MMKV 缓存命中并未消除"建桥+索引"成本

- **位置**：
  - `DexKitHelper.java:284-330`（createBridge/每次调用新建桥）
  - `DexKitHelper.java:330-460`（findClassesByString/findMethodsByString/findMethod/findClassByName 均 `createBridge` + `withBridge` + `close`）
  - `DexKitHelper.java:655-689`（loadResultsFromMMKV：建桥→跑完回调→**立即 close**）
  - 反编译确认：`DexKitCacheBridge.obtainBridge` 从 strongPool 返回同一实例，`acquireBridge` 在 `lifecycleLock` 内**懒创建**原生 `DexKitBridge`（加载全部 dex 并建字符串索引）；`close()` 只是把桥归还池并安排 5s 后回收，下一次调用若桥已被回收则**重新建桥+重新索引**。
- **问题描述**：
  - "MMKV 缓存命中"（日志 342-343 行）只缓存了**扫描结果字符串**，不缓存原生桥；每次 `findClassesByString`/`findMethodsByString` 仍要 `DexKitCacheBridge.create(...)` 加载类加载器 dex 并建立索引；
  - 实测日志佐证：RedPacketHook 在 `leshao-onReady` 线程连续 4 次 `findClassesByString` 共耗时 5505ms（日志 14.971→15.869，平均每次约 450ms+）；onReady 总耗时 13411ms 主要由这些同步搜索累积；
  - `AntiRecallHook.findMethodsByString(cl, null, "doRevokeMsg ...")`（className=null 的全包搜索）是其中最重的查询——在微信数十个 dex、数十万方法上做 `usingStrings` 匹配，且因为每次建桥/拆桥，该搜索无法复用任何索引，在本日志观察窗口（9 分钟）内始终未返回。
- **风险等级**：P0 —— 这是 13411ms 耗时与 activate 线程卡死的性能根因。
- **修复建议**：
  1. **持有常驻桥**：进程内全局只维护一个 `RecyclableBridge`，初始化后不再 close，所有 find* 复用同一实例（用读锁/串行队列保护，避免并发原生搜索）；
  2. 锚点解析**一次性批量完成**：把 `AntiRecallHook` 的 5 个字符串锚点放进全量扫描的同一批 `findMethodsByString` 查询中，结果落 MMKV/内存，运行时直接按缓存类名安装 hook，不再在 activate 阶段做全包搜索；
  3. 给所有 `findMethodsByString(cl, null, ...)` 调用加日志（开始+结束+耗时），当前仅在结束后打印结果数，卡死时无任何中间日志。

### P0-3（新增，根因 ③）：WxForwardReplaceHook 因 activateAll 卡死从未安装（含基类 hook 的潜在失效风险）

- **位置**：`MainHook.java:372-374`（注册为第 9 个任务）；`WxForwardReplaceHook.java:75-91`（hook 安装）、`:99-110`（isForwardSelector 指纹）
- **问题描述**：
  - 日志 876 行 `REGISTERED WxForwardReplaceHook activated=false` 后，**没有任何** `hooks installed`（安装日志）或 `sig cls=...`（每个 Activity onCreate 都会打的指纹日志）或 `HIT`（命中日志）。这证明它从未被执行，而不是"执行了但没命中"；
  - 直接原因即 P0-1：activateAll 卡在第 1 个任务。
  - **潜在设计风险**：`XposedBridge.hookAllMethods(Activity.class, "onCreate", ...)` 是**基类方法级 hook**。微信转发选择器（`MvvmContactListUI`/`SelectContactUI`/`MMBaseSelectContactUI`）都重写 `onCreate` 并调用 `super.onCreate()` 时钩子能触发；但一旦某版本不调用 super 或改用其他创建路径，钩子将静默失效。`isForwardSelector` 依赖 intent extras（`list_type`/`titile`/`Select_Conv_User`/`Select_Contact`）与类名血缘锚点，微信改动 key 后指纹也会失效（好在它会打印 `sig` 日志，安装后容易排查）。
- **风险等级**：P0（功能整体不可用）
- **修复建议**：
  1. 先解决 P0-1 的调度超时，确保该任务能执行；
  2. 将基类 hook 改为 `Instrumentation.callActivityOnCreate`（与 DexKitHelper 弹窗同款思路，见 `DexKitHelper.java:2557-2570`）或逐个 hook 目标 Activity 的具体类，避免基类方法被重写绕过；
  3. 在 `hook()` 开头打印一条 `WxForwardReplaceHook install begin`，便于区分"未安装"与"安装后未命中"。

### P0-4（遗留，沿用上次报告）：ContextManager 轮询等待阻塞调用线程

- **位置**：`ContextManager.java:112-118`（`waitForReady` 用 `Thread.sleep(100)` 忙等，最长 timeoutMs）
- **现状**：主流程 onReady 已迁移到 `sOnReadyExecutor`（`MainHook.java:40-46`）后台线程，冷启动主线程不再被 onReady 内的 DexKit 搜索阻塞；但任何在 `waitForReady` 上等待的调用方仍会被阻塞到超时。
- **风险等级**：P0（潜在主线程卡顿）
- **建议**：审计 `waitForReady` 调用方，能异步就异步，避免在微信主线程调用。

### P0-5（遗留，上次报告已列，本次确认部分修复）：SubPageActivity 静态状态

- **位置**：`ui/SubPageActivity.java:18-28`。v1143 已将 `AlertDialog`/`Activity` 引用改为 `WeakReference`（改进），但 `sTitle`/`sPageId`/`sNavStack`/`sBackHandler` 仍为 static。
- **风险等级**：P0（跨页面残留状态，深色模式就地重建时可能串页）
- **建议**：把页面状态收敛为实例对象随 dialog 生命周期持有。

### P0-6（遗留）：WCDB 数据库弱口令

- **位置**：`ContactRepository.java:189` 附近，`md5(imei+uin).substring(0,7)` 仅约 28bit 熵，且 imei/uin 均可从应用数据获得。
- **风险等级**：P0（数据库可被本机其他进程暴力打开）
- **建议**：改为随机 16 字节以上密钥并入库存储（仅本模块可读）。

---

## 三、P1 中等问题

### P1-1：AntiRecallHook `sHooked=true` 先置位，失败后无法重试

- **位置**：`AntiRecallHook.java:85-96`（`tryInstall` 开头即 `sHooked=true`）
- **问题**：`install...` 若中途抛异常（如 DexKit 搜索失败/进程被回收），`sHooked` 已置 true，之后用户切换开关触发 `setEnabled(true)` 重试时会直接跳过，功能**静默永久失效**。
- **建议**：`sHooked` 改为在 `install done` 之后置位；或将每个 Hook 点封装为独立可重试子任务。

### P1-2：TtsVoiceSender 单线程池 + ensureTtsReady 最长阻塞 3 秒

- **位置**：`TtsVoiceSender.java:72-77`（`sTtsPool` 单线程）、`:487-537`（`ensureTtsReady` 同步自旋等待 TextToSpeech 初始化，最多 3s）
- **问题**：所有 TTS 转换/发送串行排队；若 `TextToSpeech` 初始化慢或失败（`sReady=false`），调用线程仍会空转 3s 再失败。日志中语音发送延迟（如 355s 音频任务）可能与此相关。
- **建议**：`ensureTtsReady` 改为 CompletableFuture 异步等待；TTS 初始化失败后提供降级（直接转 mp3 发送）。

### P1-3：日志文件包含 wxid/uin 等敏感信息

- **位置**：`MainActivity` 用户信息读取路径（日志 917-920 行：`uin=295734952`、`wxid_wxid_qf3ok46p08v922`、`DB path=...`）；`LogWriter` 落盘 `/data/data/com.tencent.mm/files/leshao_v3/leshao_v3_log.txt`
- **问题**：日志含微信号、uin、数据库路径；虽位于应用私有目录，但 Xposed 模块间可读，且用户导出日志排查时可能外泄。
- **建议**：对 wxid/uin 打码（如 `wxid_*****`）；DB 路径仅打印文件名。

### P1-4：DexKit 搜索失败静默吞异常，无失败日志

- **位置**：`DexKitHelper.java:350/415/437/470` 等 `catch (Throwable ignored) {}`；`AntiRecallHook.java:213` 同样静默
- **问题**：`findMethodsByString` 只有在成功返回后打印 `...: N`，若桥创建失败/原生异常，仅空返回且无日志，调用方无从判断"没找到"还是"搜索失败"。
- **建议**：在 `catch` 中打印异常摘要；`findMethodsByString` 增加开始与耗时日志。

---

## 四、P2 改进建议

### P2-1：UI "大空白"残余场景

- **位置**：`ui/SubPageActivity.java:195-198`、`:226-229`
- **说明**：v1145 已修复普通内容页（`WRAP_CONTENT` + `windowAutoHeight`）；但 `no_wrap`（自带底部栏）页面仍固定 90% 屏高（`InsetsUtil.window(null, root, 0.92f, 0.90f)`）。若 `no_wrap` 页面内容较短，底部仍会出现无效空白。建议逐个检查 `no_wrap` 页面内容是否撑满。

### P2-2：MODULE_VERSION_CODE 与发布 APK 版本不一致

- **位置**：`MainHook.java:63`（`MODULE_VERSION_CODE = 30097`，`MODULE_BUILD = "v3.0.97"`）
- **说明**：用户环境为 v3.0.1.3（30103）。DexKit 缓存键含 `module_version`（`DexKitHelper.java:549-550`），源码与发布版不一致会导致缓存频繁失效、每次升级都触发全量重扫。若源码确为当前发布版，请同步常量；若为历史分支，需明确。

### P2-3：DexKit 桥复用与并发保护

- 建议在 `DexKitHelper` 内维护**常驻单例桥**，并用串行队列（或读锁）封装所有 `find*` 调用，避免多线程并发使用同一 `RecyclableBridge` 时在 `lifecycleLock` 上排队（反编译已确认 `acquireBridge` 在 monitor 内懒创建桥）。
- 当前"每次调用 create/close + 5 秒回收"的设计在 onReady 集中搜索场景下放大建桥成本。

### P2-4：HookManager 统计口径混杂

- `HookManager.java:120-142` 的 `register(key, clazz, methodName, ...)` 路径与 `register(name, task)` 入队路径共用 `successCount/failCount`，但语义不同（一个是立即安装并计数，一个是入队）。建议拆分统计，避免汇总页误导。

---

## 五、与日志关联的三个"为什么"定位建议

### ① 为什么 `Instrumentation.callActivityOnCreate` hook 从未触发弹窗日志？

**答案：不是 hook 没装上，而是"无需弹窗"。**

1. hook 已成功安装：日志 318 行 `Instrumentation.callActivityOnCreate hook installed`（`DexKitHelper.java:2557-2570`）；
2. 弹窗开关 `sShouldShowScanDialog` 只在 `startFullScan(app, true)` 中置 true（`DexKitHelper.java:2427-2429`）；
3. 主进程走的是 **MMKV 缓存命中**路径（日志 342-343 行 `MMKV cache hit for version 3180` / `loaded cached results`），`loadResultsFromMMKV` 成功后直接 `return`（`DexKitHelper.java:2530-2532`），`startFullScan` 从未被调用；
4. 因此每次 Activity 创建进入回调时，第一行 `if (!sShouldShowScanDialog) return;`（`DexKitHelper.java:2559`）静默返回，该分支无日志，看起来就像"从未触发"。

**修复师排查方向**：确认目标进程首次启动时缓存是否命中；若需验证弹窗链路，可在 `loadResultsFromMMKV` 返回 true 时打一行 `scan skipped, dialog suppressed` 日志；或在 `DexKitHelper.java:2559` 的 return 分支补一条 debug 日志。

### ② 为什么第二次 onReady 13411ms 后无 `activateAll DONE` 日志？

**答案：`leshao-hook-activate` 线程卡死在任务 #1（AntiRecallHook）的全包 DexKit 搜索中，且调度器串行无超时。**

1. onReady 总计 13411ms 属正常表现：日志 `RedPacketHook elapsed=5505ms`（4 次 `findClassesByString`，每次约 450ms+，原因见 P0-2 的建桥成本）；
2. 17:04:15.887 `activateAll: 9 pending tasks`，同刻 `[1/9] START AntiRecallHook`、`install...`（日志 887-888 行）；
3. `AntiRecallHook.hookByMethodString`（`AntiRecallHook.java:208`）调用 `DexKitHelper.findMethodsByString(cl, null, "doRevokeMsg ...")` —— **className=null 的全包 usingStrings 搜索**，是 DexKit 最重的查询类型；
4. 此后 9 分钟该线程无任何输出（无 `findMethodsByString(...): N` 结果日志、无 `install done`）。期间 `leshao-p06-delayed` 于 16.184 用**带 declaredClass 的定向查询**成功（3ms），heartbeat 持续存活 —— 说明进程正常、DexKit 可用，**只是该全包搜索长时间未返回**；
5. `HookManager.activateAll` 单线程串行 + `runTask` 无超时（`HookManager.java:78-108`），任务 #1 不返回则 #2-#9 永不执行，`activateAll DONE` 永不打印。

**修复师排查方向**：先给 `activateAll` 加超时/并行（P0-1 修复）；再给 `findMethodsByString` 加开始+结束日志定位具体耗时；最后把 AntiRecallHook 锚点解析移入扫描阶段缓存（P0-2 修复）。

### ③ 为什么 `WxForwardReplaceHook` 无任何日志输出？

**答案：它从未被执行（第 9 个任务，排在卡死的 AntiRecallHook 之后）。**

1. 日志 876 行 `REGISTERED WxForwardReplaceHook activated=false` —— 仅完成入队；
2. `MainHook.java:372-374` 注册顺序使 WxForwardReplaceHook 排在最后（第 9/9）；
3. 因 P0-1 调度卡死，#2-#9 全部未执行，所以既无 `hooks installed`（`WxForwardReplaceHook.java:90`），也无每个 Activity 都会打的 `sig cls=...`（`:109`）与命中日志 `HIT`；
4. 排除法：若它安装过，微信任意 Activity 创建都会产生大量 `sig` 日志，日志中一条都没有 → 结论只能是"未安装"。

**修复师排查方向**：优先解决 P0-1（超时/并行），并给 `WxForwardReplaceHook.hook()` 开头加 `install begin` 日志；同时按 P0-3 建议把基类 `Activity.onCreate` hook 改为 `Instrumentation.callActivityOnCreate` 路径，规避子类重写绕过风险。

---

## 六、结论

- 三个现象由**同一条故障链**引起：缓存命中（无弹窗）→ 全包 DexKit 搜索卡死任务 #1（无 DONE）→ 后续 8 个任务（含转发替换）全部未执行（无日志）。
- 最高优先修复项：**HookManager 超时/并行调度（P0-1）** 与 **DexKit 桥常驻复用 + 锚点批量预解析（P0-2）**。这两项修复后，日志中的三个问题预计同时消失。
- 遗留安全项（WCDB 弱口令、日志敏感信息）建议一并处理。