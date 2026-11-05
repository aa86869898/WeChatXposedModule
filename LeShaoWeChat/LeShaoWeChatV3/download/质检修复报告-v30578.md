# 质检修复报告 v3.0.578

## 一、问题现象

用户在**群聊里转发一个压缩包**时，模块把**私聊里别人发的语音**一并转发进了群（误转发 / 隐私外泄）。

依据 11-04 主微信日志（18:11:24–19:25:10，13,104 行）定位：

- `19:24:40.641` `WxFwdReplace: [FwdFix][A] finish picked=[43454772732@chatroom]`
  → 紧接着 `VF: doForward: msgId=10241588` + `voice: cid=amr_4214141104268441381b00c103`
  → `TtsVoiceSender: SceneVoice: start ... talker=43454772732@chatroom`
- 而同窗口用户真实要转发的是**压缩包**（msgId=10229123 / 10238775）。
- 事故前日志中无任何 `INJECT` / `VOICE_MARK`（菜单注入日志全库仅出现在 19:24:47，即事故之后），
  说明被误发的语音来自更早缓存的旧语音。

## 二、根因

`WxForwardReplaceHook` 的两条发送路径**无条件发送缓存语音**，且语音缓存**没有时效限制**：

1. `[FwdFix][A]`（`MvvmContactListUI.finish` 选人器确认）与
   `[FwdFix][B]`（`MsgRetransmitUI.onActivityResult`）在**任意一次普通转发**（压缩包/文件/文字）
   的选人器 finish / onActivityResult 里，都会调用
   `pendingVoiceE9()` 取“缓存里最新的语音”并 `forwardVoiceToTarget(...)` 发送。
2. `pendingVoiceE9()` 对 `sVoiceMsgs` 的兜底取“最新一条”，**没有任何 TTL**，
   导致旧语音被无限期复用 —— 只要此前长按过任意语音，之后任何一次普通转发的选人器确认都会把它发出去。

即：**普通转发 ≠ 语音转发**，但代码没有区分，把“最近缓存的语音”当成了本次转发的内容。

## 三、修复内容（`WxForwardReplaceHook.java`）

核心：引入**语音转发授权闸门（armed gate）**，只有用户真正点了「语音转发」菜单项才允许发送语音。

1. 新增状态：`sVoiceFwdArmed` / `sVoiceFwdArmedAt` / `VOICE_ARM_TTL=90s`；
   新增 `voiceForwardArmed()`（授权有效且窗口内且存在 pending 语音）与 `disarmVoiceForward()`（解除并清 pending）。
2. **置位授权（仅真实语音转发点击）**：
   - `launchNativeVoiceForward(ctx, e9)`（模块自注入的「语音转发」项点击 → 原生选人）：置位授权。
   - `BQ_Q_142`（原生「转发」142 项点击处理器 `viewitems.yb#R(MenuItem,...)`）：
     **仅当被点消息 `type==34`（语音）**才记录 pending 并置位授权；非语音（压缩包/文件/文字/图片）一律忽略。
3. **发送路径严格守卫**：
   - `[FwdFix][A]`：`pendMsgF = armed ? sVoicePendingMsg : null`；为空则**放行不发送**（普通转发不再触发语音）。
   - `[FwdFix][B]`：未授权**第一行直接放行**，既不改写 `Select_Conv_User`，也不发送任何语音。
   - 两条路径在**发送完成 / 取消 / 目标被过滤**后立即 `disarmVoiceForward()`，杜绝跨流程残留。
4. **移除旧语音无限复用根因**：删除 `pendingVoiceE9()` 对 `sVoiceMsgs` 的“最新一条”无 TTL 兜底；
   `markVoiceForwardPending()`（菜单构建时缓存）仅做缓存，不再构成“可发送 pending”。

## 四、反编译佐证（微信 8.0.79 真实 dex）

- `com.tencent.mm.ui.chatting.viewitems.yb#R(MenuItem, d, ym5.d)` 存在（dex classes87），
  即「转发」菜单项点击的权威处理入口；`BQ_Q_142` 锚定该 `R(MenuItem,...)` 方法。
- 语音消息类型常量 `MSG_TYPE_VOICE = 34`；`msgTypeOf(e9)` 反射 `getType()`/`field_type` 取类型。

## 五、编译与版本

- `./gradlew :app:compileDebugJavaWithJavac` 通过；`./gradlew :app:assembleRelease` 通过（BUILD SUCCESSFUL）。
- 版本号五处同步 **v3.0.578 / 30578**：`build.gradle.kts`(versionCode/versionName)、
  `MainHook.java`(MODULE_BUILD/MODULE_VERSION_CODE)、`DexKitHelper.java`(CURRENT_MODULE_VERSION)。
- APK：`LeShaoWeChat-v30578.apk`（10,480,822 字节），`aapt dump badging` 确认 versionCode=30578 / versionName=3.0.578。
- 公网下载校验：普通 GET=200、Range=206。

## 六、分身日志目录说明（附带核查）

用户反馈“分身微信日志目录只有单个日志文件，没有其他配置文件”。核查结论：**属诊断文件、非功能配置，不影响分身功能**：

- `dexkit_baseline.json`：仅在**本轮完成一次全新健康扫描**后导出（`DexKitHelper` `sLastScanHealthy` 分支）；
  分身此前命中“模块版本未变→复用缓存”，未走全新扫描，故未导出。
- `signature_dump.txt`：`ENABLED = BuildConfig.DEBUG`，**Release 包不生成**。
- 分身功能配置存于 `ContextManager.getPrefs()`（`xposed_wechat_iso`，按进程隔离），非文件落盘。
- 本次将 `CURRENT_MODULE_VERSION` 提升到 30578，会令分身 DexKit 缓存失效 → 下次启动触发全新扫描，baseline 亦会随之导出。
