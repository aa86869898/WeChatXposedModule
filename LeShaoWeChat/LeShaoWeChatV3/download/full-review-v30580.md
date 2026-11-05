# 全项目功能审查报告 · v3.0.580

**版本**：3.0.580（versionCode 30580）
**微信基准**：8.0.79 / versionCode 3200（94 个真实 dex，`/tmp/opencode/wechat_dex/com.tencent.mm`）
**本轮重点**：消息外发域 —— 彻底关闭「模块自发消息被乱转发 / 全量群发」
**审查范围**：消息外发与接收域（全量复核）+ 其余域（沿用上轮结论并做回归确认）

---

## 一、本轮结论摘要

| 结论 | 内容 |
|---|---|
| **乱转发问题「还在不在」** | **旧版能触发、当前版本同等条件下仍能触发** —— 已定位为 4 个缺陷叠加，本轮全部封堵 |
| 事故规模（真实日志） | 一条点歌提示 → **545 个群 / 1090 条 / 0.571 秒** |
| 修复层级 | 新增 1 个护栏类 + 改造 6 个类 + 1 处 UI |
| 构建 | `assembleRelease` 通过，APK 10,489,025 字节 |
| 遗留 | 2 项（动态文案跨分身水印、原生引用链逆向） |

---

## 二、消息外发与接收域（本轮全量复核）

### 2.1 域内组件清单

| 组件 | 职责 | 本轮状态 |
|---|---|---|
| `WmReflect.sendTextMsg` | SendMsgMgr 文本主链（`ni5.n0.c(no3.c1)` → `rt5.i5.Jj`） | ✅ 有效 + 已打标 |
| `GroupFeatures.sendTextMessage` | 四级发送链（SendMsgMgr → Builder → e9 入库 → Footer） | ✅ 已加固（入库前校验 isSend） |
| `WeChatMessenger` | 备链（`f61.s1` Builder / `f9.Mb/Nb`） | ✅ 已打标 |
| `TtsVoiceSender` | 语音发送（SceneVoice 链） | ✅ 已打标（type 34） |
| `DianGeService` | 点歌：受理提示 + 语音发送 | ✅ 提示语走已加固链 |
| `AutoForwardHook` | 自动转发 / 自动群发 | ✅ **本次核心修复** |
| `MsgReceiveHook`（`LeshaoAI.Recv`） | AI 接收总闸门 | ✅ 已加固 |
| `MessageHook` | 关键词自动回复 / 语音播报 / 点歌入口 | ✅ 已加固 |
| `VoiceForwardHook` | 语音转发 | ✅ 沿用上轮（`TtsVoiceSender` 通道） |
| `RedPacketHook` | 红包到账播报 | ✅ 走 `WmReflect.sendTextMsg`，已被打标覆盖 |
| `WanQunGroupHook` | 群管理自动回复 | ✅ 同上 |

### 2.2 「乱转发」缺陷链（4 层叠加）

```
① 点歌提示语由模块发出
        ↓
② 以「接收形态」进入 f9 消息落库回调（isSend 非 1 / 跨分身同步）
        ↓
③ AutoForward 认为「收到了一条新消息」，来源名单命中 → 判定转发
        ↓
④ 对 545 个目标裸循环发送，无去重、无限速、无熔断
        ↓
    0.571 秒内 1090 条消息打进 545 个群
```

| 层 | 缺陷 | 修复 |
|---|---|---|
| ② | 模块没有「这条是我发的」概念 | `SelfMsgGuard` 账本 + 内容模板 + 零宽水印三重识别 |
| ③ | `isSend` 读不到就放行 | 三路读取，失败即不转发 |
| ④-去重 | `svrId==0` 不去重 | 内容 + 时间片兜底键 |
| ④-发送 | 无节流/熔断 | 单线程 + 180~420ms 间隔 + 连败 5 次中止 + 熔断 |
| ②（源头） | `e9 入库` 不校验 isSend | 入库前回读，≠1 放弃 |

### 2.3 为什么必须是「内容识别」而不只是「本地账本」

真实事故发生在**分身**环境。分身之间共享同一个微信号，但 **SharedPreferences 目录各自独立**
（`UnifiedPrefs` 注释已确认：各身份读写各自 prefs）。因此：

- A 分身发出提示语 → 微信服务端同步 → B 分身收到的是 `isSend=0` + 真实 `msgSvrId` 的「别人发来的消息」；
- B 分身的本地账本里**根本没有**这条记录 → 本地账本拦不住。

所以 `SelfMsgGuard` 额外提供 `isModuleNoticeText()`：用**配置的点歌提示模板**（`{song}` 通配）
直接识别文案，不依赖任何本地状态。这是本次最关键的一处设计。

---

## 三、其余功能域状态（回归确认）

| 域 | 功能 | 状态 | 说明 |
|---|---|---|---|
| 消息 | 防撤回 | ✅ 已适配 8.0.79 | `k4(talker,svrId)` / `ud(msgId,e9,notify)` / `Z0` / `F0` |
| 消息 | 消息伪装 / `MsgForgeHook` | ✅ | 沿用上轮 |
| 消息 | 转发限制解除 `ForwardLimitHook` | ✅ | — |
| 消息 | 转发替换 `WxForwardReplaceHook` | ✅ | 日志确认 4 处 hook 安装成功 |
| 消息 | 引用 / @ | ⚠ 部分 | 原生引用链（`ou5.c1`/`xp3.i`/`dx0.r`）8.0.79 已只剩 lambda，待逆向 |
| 语音 | 语音转发 | ✅ | 走微信原生选人 + `TtsVoiceSender` 发送（严禁激活原生语音链路） |
| 语音 | 语音播报 / `#tts` | ✅ | `hookF9Bb` 已匹配 `Nb`/`Mb`/`Bb` |
| 语音 | 点歌 | ✅ | 受理提示改纯文本优先 + 二次兜底 |
| 群管理 | 自动进群二维码 | ✅ | — |
| 群管理 | 群标题标签 / 群成员 | ✅ | — |
| 群管理 | 万群同步 `WanQunGroupHook` | ✅ | — |
| 红包 | 到账播报 / 抢包 | ✅ | sendId 取 `e1.n`/`h6.i`；双 CL 重装 hook 幂等 |
| 朋友圈 | 自动点赞 | ✅ | — |
| 界面 | 聊天气泡 / 主题 / 菜单注入 | ✅ | — |
| 工具 | DexKit 锚点 / 版本适配 | ✅ | 锚点 `MicroMsg.SendMsgCgiFactory` 有效 |
| 风控 | 反检测 / 更新拦截 / 广告拦截 | ✅ | — |

---

## 四、本轮改动清单（文件级）

| 文件 | 改动 |
|---|---|
| `v3/hook/SelfMsgGuard.java` | **新增**，模块自发消息护栏 + 群发熔断（约 300 行） |
| `v3/hook/AutoForwardHook.java` | isSend 三路 fail-closed、三重自发拦截、去重兜底、异步限速、熔断、UI 计数与二次确认、内容读取补 `F0` |
| `v3/hook/GroupFeatures.java` | 发送入口打标；`insertViaMsgInfo` 入库前校验 isSend |
| `v3/hook/MessageHook.java` | 接收分支加自发消息拦截 |
| `v3/hook/TtsVoiceSender.java` | 语音发送打标 |
| `v3/wm/utils/WmReflect.kt` | 文本发送打标 |
| `ai/hook/wechat/WeChatMessenger.java` | 文本发送打标 |
| `ai/hook/wechat/MsgReceiveHook.java` | 自发消息拦截、isSend fail-closed、`F0→H0`、内容读取补 `F0` |
| `app/build.gradle.kts` / `MainHook.java` / `DexKitHelper.java` | 版本号 → 30580 / 3.0.580 |

---

## 五、验证矩阵

| 类别 | 项 | 结果 |
|---|---|---|
| 构建 | javac / assembleRelease | ✅ / ✅ |
| 产物 | versionCode/Name、大小、MD5 | 30580 / 3.0.580、10,489,025 B、`53155c33…877f` |
| 逻辑 | 护栏匹配 8 用例（含群前缀、占位符、排队后缀、水印、语音通配、不误伤） | ✅ 8/8 |
| 回归 | 事故 6 场景推演 | ✅ 全部封堵 |
| 日志 | 关键判定均有独立日志行 | ✅ |
| 代码 | 全项目 `sendTextMsg(` 调用点覆盖 | ✅ 10/10 经打标入口 |

---

## 六、遗留与建议

| # | 项 | 建议 |
|---|---|---|
| 1 | 红包播报 / AI 回复等**动态文案**跨分身同步时仍依赖本地账本 | 统一追加零宽水印（需先解决 @ 消息前缀高亮兼容） |
| 2 | 原生引用消息链在 8.0.79 已失效 | 重新逆向 `k0.I` 与 `MsgQuoteItem` 新字段 |
| 3 | 用户配置了 545 个转发目标 | 已加二次确认与限速；建议同时确认「来源名单」是否只有需要的会话 |
| 4 | 点歌提示语回流是否完全消失 | 需用户实测一轮，回收 `SelfMsgGuard` / `AutoForward: skip` 日志 |

---

*配套文档：`质检修复报告-v30580.md`、`深度检查清单-v30580.md`。*
