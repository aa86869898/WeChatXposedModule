# 质检修复报告 v3.0.269

- 版本：v3.0.269 / 30269
- 微信：8.0.78(3180)
- 性质：三路并行深度质检（A 启动性能 / B 功能页面与持久化 / C UI 窗口层级），本版完成全部确认问题的修复。

---

## 一、质检方式

按用户要求派出三路质检并行检查：

| 路线 | 检查范围 | 主要发现 |
|------|---------|---------|
| A | 微信启动卡顿 / 卡死 / 一切延迟根因 | 主线程初始化、三重 Activity.onResume、DexKit 锁等待 |
| B | 各功能页面按钮失灵 / 点击无效 / 未持久化 | 清空不刷新、开关不即时保存、异常被吞、点击无反馈、Prefs 未初始化 |
| C | 多层窗口堆叠无法区分当前层 | dimAmount 被清零、多个 Dialog 未登记层级 |

以下为逐项审查结果与修复。

---

## 二、A 路：启动卡顿根因审查结论

### A-H1 主线程同步初始化

**报告**：MainHook 在主线程同步执行大量反射/hook。

**审查结论（日志实证）**：本版实机日志显示主线程模块调度仅 **57ms**，其中：

- DexKitHelper.hookApplication = 3ms（v3.0.263 缓存信任生效，微信版本不变时 cache-only，不重扫）
- UiBackInstaller = 10ms
- DatabaseProvider.initEarly = 20ms（WCDB 逃密必须早于微信开库）

剩余大部分 hook 已通过 `deferRun` 异步安装。**无需改代码**。

### A-H2 三处独立 hook Activity.onResume

**报告**：MainHook、ChatGroupUiInjector、WmEntry 各自 hook Activity.onResume。

**审查结论**：三处职责不同，无法合并：

- MainHook：记录前台 Activity、深色模式配色刷新、气泡配置重载、WCDB 反查重 hook（每次 onResume 均执行，轻量）
- ChatGroupUiInjector：仅当 `LauncherUI` 时注入会话列表标签栏（带类名过滤）
- WmEntry：仅当 `LauncherUI` / `ChattingUI` 时处理入口与悬浮球（带类名过滤）

三者均有类名过滤，回调本身微秒级，**不构成启动卡顿**。强行合并会引入类加载时序风险，本版不合并。

### A-H3 withWechatBridge 等待全量扫描

**审查结论**：`waitForFullScanIfScheduled()` 已有主线程保护（主线程直接返回不等待）；配合 v3.0.263 缓存信任，正常启动不会调度全量扫描。**无需改代码**。

---

## 三、B 路：功能页面与持久化修复（5 项全部修复）

### B-H1 消息长按菜单「清空全部隐藏项」后按钮开关不刷新

**问题**：`MessageMenuPageView` 清空隐藏项后只刷新「其他已收录」列表，文档按钮开关仍保留旧状态，视觉与实际不一致。

**修复**：新增 `sBtnCardRef` 弱引用按钮开关容器，清空后同步调用 `rebuildBtnList()` 重建全部开关。

### B-H2 AI 设置开关不即时保存

**问题**：`SettingsActivity` 的「AI 助手」与「语音消息发送」两个总开关仅 `onPause` 时保存，用户切换后立即退出（或进程被杀）会丢失设置。

**修复**：两个开关增加即时回调，切换后立即 `saveFieldsToConfig()` + `config.save()` + 推送刷新。

### B-H3 抢红包参数非法输入被吞、误报「已保存」

**问题**：`RedPacketPageView` 保存参数时 `parseInt` 异常被 catch 忽略，非法内容仍提示「已保存」，实际未生效。

**修复**：保存前校验「每分钟次数」「随机延时上限」必须为非负整数，非法时 Toast 提示原因且不保存、不误报成功。

### B-M1 万群群发入口点击无反馈

**问题**：`WxMasterPageView` 点击「乐少万群定时群发」后异常被吞，用户不知道是否发起成功。

**修复**：成功 Toast「已发起群发」，失败 Toast 具体错误信息。

### B-M2 WmPrefs 部分页面未初始化导致读写失效

**问题**：`WmPrefs` 的 `sp` 依赖 `init()` 调用，部分页面未先调用 `ensureInit()` 导致读写全部走默认值（设置不生效、不持久化）。

**修复**：`get / set / getStr / setStr` 四个基础方法内部自动 `ensureInit()`，所有功能开关读写天然安全，消除遗漏点。

---

## 四、C 路：UI 窗口层级修复（4 项全部修复）

### C-1 InsetsUtil.clearWindowShell 清零 dimAmount 破坏层级压暗

**问题**：`clearWindowShell` 强制 `setDimAmount(0f)`，把 `WindowLayer.applyScrim` 设置的多层遮罩全部清零，导致多层弹窗背景同色、无法区分当前层。

**修复**：移除 `setDimAmount(0f)`，层级压暗统一由 `WindowLayer` 管理。

### C-2 主界面捐赠弹窗未登记层级

**问题**：`MainActivity.showDonateDialog` / `showDonateImage` 未调用 `WindowLayer.track`，叠加在其它弹窗上时无遮罩区分。

**修复**：两个弹窗 show 后均接入 `WindowLayer.track`。

### C-3 取色器 ColorPickerDialog 未登记层级

**问题**：`ColorPickerDialog` show 后未 track，叠加时不压暗下层。

**修复**：接入 `WindowLayer.track`。

### C-4 TTS 页两个对话框未登记层级 / 手动清除遮罩

**问题**：`TTSPageView` 的「配音魔方音色库」与「设置 API Key」两个对话框未 track；Key 弹窗还手动 `clearFlags(FLAG_DIM_BEHIND)` 拒绝遮罩。

**修复**：两个对话框 show 后均接入 `WindowLayer.track`，移除手动清除遮罩的代码。

---

## 五、无法/无需修复项说明

| 项 | 结论 |
|----|------|
| A-H1 主线程重初始化 | 日志实证 57ms，缓存信任已解决首次扫描卡顿，无需改动 |
| A-H2 三重 onResume | 各有独立职责与类名过滤，合并有类加载时序风险，不合并 |
| A-H3 锁等待 | 已有主线程保护，无需改动 |
| 微信侧缺失锚点（viewitems.o0、pe5.f.j、LeftTopEntry.m 等） | 依赖用户反编译提供真实签名，见《缺失DexKit锚点清单.md》 |

---

## 六、本版文件

- `LeShaoWeChat-v30269.apk`（最新版）
- `质检修复报告-v30269.md`（本报告）
- `深度检查清单-v30268.md`（历史）
- `全项目功能审查报告-v30268.md`（历史）