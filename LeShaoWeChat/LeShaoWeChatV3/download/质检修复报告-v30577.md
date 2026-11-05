# 质检修复报告 v3.0.577

## 一、根因定性：Tinker 双 ClassLoader（本轮核心）

依据 11-03 主微信日志（08:06–12:59）定位，问题**不是类名/字符串失效，而是 Class 对象来自错误的 ClassLoader**：

- DexKit 扫描返回的 Class 来自 base.apk 的平行副本 CL（`PathClassLoader`）；
- 微信运行时真实类由 Tinker 的 `DelegateLastClassLoader` 加载；
- 两套 CL 的静态单例互不相通 → `pp0.i1`（服务定位器）在副本 CL 上从未被初始化 →
  `Kernel not initialized by MMApplication!` → 存储链取不到实例、hook 挂上后运行时零捕获。

决定性证据：
- `MsgReceiveHook` 打印了「使用 Tinker 真实 CL DelegateLastClassLoader 定位 f9」（工作正常）；
- `MessageHook` 从未打印该行，`hookMsgStorageInsert`/`hookX9Dispatch` 挂的是 base CL → 12:59:25 收到
  `wxid_9ohhf82mrlgc22` 文本「你好」后无任何 `MessageHook: #N` 播报日志（x9/f9 hook 零捕获）。
- `StorageHub: bindInternal: MsgInfoStorage 获取失败 (acc=l41.e)` / `服务实例仍 null`。

## 二、反编译实证（微信 8.0.79 真实 dex）

- `com.tencent.mm.plugin.messenger.foundation.h2 extends ni5.w implements c4`（`c4` = `po3.c4`）
  - `h2.Jj() → com.tencent.mm.storage.f9`（`f9 implements ro3.m0`）
  - `h2.Aj() → com.tencent.mm.storage.j4`（`j4 implements com.tencent.mm.storage.d8`）
- `f9` 插入入口：`Mb(e9)` / `Nb(e9, boolean)`
- `com.tencent.mm.storage.m3.v(int, String)` 存在（selfWxid 取数源）
- 类描述符均存在于真实 dex：`Lro3/m0;`、`Lcom/tencent/mm/storage/d8;`、`Lpo3/c4;`、`...foundation/h2;`、`Lcom/tencent/mm/storage/e9;`、`Lcom/tencent/mm/storage/f9;`、`Lcom/tencent/mm/storage/j4;`

## 三、修复内容

### 1) StorageHub（存储绑定）
- 新增 `resolveOn(cl, cls, name)` / `resolveInterfaceOr(cl, ifaceName, dexkitCls)`：所有 Class 按类名在**真实 CL** 上重新解析，规避双 CL Class 对象不匹配。
- 主链服务键改为 **`po3.c4`**（旧版 `tn3.c4`/`sh3.c4` 兼容，最后兜底存储接口），修正此前误用实现类 `f9` 作键导致 `pp0.i1.v(f9)` 恒 null 的错误。
- `getterByPrefs(svc, ...)` 优先 `Jj`/`Aj` 取 f9/j4；`serviceLocatorClass` 增加 `pp0.i1` 兜底；`readConfigStorage` 改用 `svc`。

### 2) MessageHook（消息播报入口）
- `addPostScanCallback`：先用当前 CL 立即挂一遍，再 `scheduleRealClRehook()` 后台 50×800ms 等真实 CL 就绪后补挂 f9 入库 + x9 分发。
- `hookX9Dispatch` 增加 `resolveRealCl(cl)`；新增 `sHookedF9Classes`/`sHookedX9Classes`（`synchronizedSet`）去重，防 base/real 双 CL 补挂导致回调翻倍。

### 3) RedPacketHook（红包）
- `install()` 用 `resolveRealCl(cl)` 解析 scene（修 `n6` 构造 `InvocationTargetException`）。
- `scheduleSceneResolveRetry()`：后台等真实 CL 就绪后**重新解析并覆盖**（含此前 base CL 解析到的错误 scene 类）。
- `grab()` 抢红包前增加守卫：若当前 scene 类来自非真实 CL，先用真实 CL 重解析再构造。

### 4) MessageMenuHook（长按菜单）
- `pickRealClassLoader` 从 `attempt>=2` 提前到 `attempt>=1` 回退普通 CL（修 `loader=null` → 15 候选全 `ClassNotFound` 的「anchor not found」）。

## 四、编译与版本
- `./gradlew :app:assembleRelease` 通过（BUILD SUCCESSFUL）。
- 版本号五处同步 **v3.0.577 / 30577**：`build.gradle.kts`(versionCode/versionName)、`MainHook.java`(MODULE_BUILD/MODULE_VERSION_CODE)、`DexKitHelper.java`(CURRENT_MODULE_VERSION)。
- 公网下载校验：`LeShaoWeChat-v30577.apk` 普通 GET=200、Range=206。
