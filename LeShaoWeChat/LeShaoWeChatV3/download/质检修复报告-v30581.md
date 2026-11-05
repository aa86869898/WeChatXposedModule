# 质检修复报告 · v3.0.581

**版本**：3.0.581（versionCode 30581）
**构建**：`assembleRelease` 通过
**APK**：`LeShaoWeChat-v30581.apk`，10,489,016 字节，MD5 `684d3324c8e6bdde4ba7355240a10f54`
**基准微信**：8.0.79 / versionCode 3200
**本次主题**：**给所有模块外发文本统一打零宽水印，封堵跨分身同步漏拦（v3.0.580 待办 F1）**

---

## 一、为什么还要做这一轮

v3.0.580 已经封堵「模块自发消息被乱转发」的 4 层缺陷链，但当时明确留了一项残留风险：

> 红色播报、AI 回复等**动态文案**跨分身同步时仍靠本地账本，另一个分身收到的是
> `isSend=0` + 真实 `svrId` 的「别人发来的消息」，本地账本里没有记录 → 仍可能被转发。

v3.0.580 只有点歌受理提示这一条固定文案走「内容模板识别」，动态文案没有覆盖。
本轮把识别手段升级为**内容自带水印**：不依赖本地状态、不依赖文案内容，凡模块发出的文本都带标记。

---

## 二、方案

### 2.1 统一水印

`SelfMsgGuard` 新增：

| 成员 | 作用 |
|---|---|
| `MARK = "\u200B\u200C"` | 零宽空格 + 零宽非连接符，肉眼不可见 |
| `watermark(content)` | 幂等打水印；内容已带则原样返回 |
| `hasWatermark(content)` | 判定正文起点或 @ 提及前缀之后是否带水印 |

**@ 消息兼容**（本轮解决了上一轮标记的阻塞点）：群聊 @ 高亮依赖 content 以
`@昵称\u2005` 开头，直接在 `@` 前插零宽字符会让高亮失效。因此遇到 `@` 开头的内容时，
水印插在**提及前缀之后、正文之前**：

```
输入：@张三\u2005你好
输出：@张三\u2005\u200B\u200C你好        ← @ 仍在第一位，高亮不受影响
```

多人 @（`@张三\u2005@李四\u2005正文`）同样只在第一个分隔符后插入一次。
`hasWatermark` 允许水印出现在开头 96 个字符内，兼容多人长昵称。

### 2.2 接入点（全部收敛到 3 个发送函数）

| 函数 | 处理 |
|---|---|
| `WmReflect.sendTextMsg(...,type,flag)` | 先 `watermark` 再 `mark` 再发送，`Jj/Kj/oj/nj/mj/pj` 全部使用水印后内容 |
| `GroupFeatures.sendTextMessage` | 入口 `watermark`，①~④ 四级链全部使用水印后内容 |
| `WeChatMessenger.sendText` | 入口 `watermark` 再 `mark` |
| `WeChatMessenger.sendQuoteAndAt` | 原生引用链同样 `watermark` + `mark` |

这样 `DianGeService`、`RedPacketHook`、`WanQunGroupHook`、`WmChatHook`、`AutoForwardHook`、
`TtsVoiceSender` 文本伴发、`broadcastRooms` 等所有调用点自动覆盖，无需逐处修改。

### 2.3 接收侧判定统一

- `SelfMsgGuard.isSelf()` 由 `startsWith(MARK)` 改为 `hasWatermark(content)`。
- `SendGuard.mark()` / `SendGuard.isBotSent()` 改为直接复用
  `SelfMsgGuard.watermark()` / `hasWatermark()`，AI 回复与其它模块消息使用同一套规则。
- `norm()` 原本就会剥离零宽字符，账本精确键与内容兜底键在打水印后仍能对上。

---

## 三、离线验证（10 个用例，与线上算法完全一致）

`/tmp/opencode/wmtest/W.java`：

| # | 用例 | 期望 | 结果 |
|---|---|---|---|
| 1 | 普通文本 | 加在开头 | PASS |
| 2 | 已带水印（幂等） | 不重复加 | PASS |
| 3 | 单人 @ `@张三\u2005你好` | 插在前缀后、@ 仍在首位 | PASS |
| 4 | `@\u200B 你好` | 插在空格后、@ 仍在首位 | PASS |
| 5 | 只有 @ 无正文 | 追加在末尾 | PASS |
| 6 | 多人 @ | 只插一次、@ 仍在首位 | PASS |
| 7 | 已带水印的 @ 消息 | 不重复加 | PASS |
| 8 | 群昵称前缀 `张三:\n` | 加在开头，前缀可正常剥离 | PASS |
| 9 | 引用块 + @ | @ 仍在首位 | PASS |
| 10 | 空串 | 原样返回 | PASS |

（10/10 通过；每个用例同时校验「幂等」与「@ 首位不变」）

---

## 四、验证

| 项 | 结果 |
|---|---|
| `compileDebugJavaWithJavac` | 通过 |
| `assembleRelease` | 通过 |
| `aapt2 dump badging` | `versionCode='30581' versionName='3.0.581'` |
| 五处版本号 | 30581 / 3.0.581 全部同步 |
| APK 大小 / MD5 | 10,489,016 字节 / `684d3324c8e6bdde4ba7355240a10f54` |
| 公网 turbo | 200 |
| 公网 Range 0-1023 | 206 |

---

## 五、残留风险

| # | 风险 | 评估 |
|---|---|---|
| 1 | 用户复制模块消息再粘贴发送，也会被识别为模块消息 | 低。仅在 20 秒内容兜底窗口内，且后果只是「不被自动转发」，丢消息风险为零 |
| 2 | 消息伪装（`MsgForgeHook`）走 UI 发送链，不经过上述函数，暂无固定水印 | 低。伪造消息本身 `isSend=1`，本机不会被当接收消息；跨分身同步仍靠本地账本 |
| 3 | 原生引用链在 8.0.79 本身仍失效 | 沿用 v3.0.580 待办 F2，文本回退可正常发送并带水印 |

---

*配套文档：`深度检查清单-v30581.md`、`全项目功能审查报告-v30581.md`。*
