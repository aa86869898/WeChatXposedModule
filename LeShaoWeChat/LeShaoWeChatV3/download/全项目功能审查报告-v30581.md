# 全项目功能审查报告 · v3.0.581

**版本**：3.0.581（versionCode 30581）
**微信基准**：8.0.79 / versionCode 3200
**本轮重点**：模块自发消息识别从本地账本升级为跨分身零宽水印（v3.0.580 待办 F1）
**审查范围**：消息外发域全量复核 + 水印接入点覆盖确认 + 其余域回归确认

---

## 一、结论摘要

| 结论 | 内容 |
|---|---|
| v3.0.580 残留风险 | 动态文案跨分身同步漏拦 —— 本轮已封堵 |
| 方案 | 所有模块外发文本统一零宽水印；@ 消息插在提及前缀之后 |
| 接入 | 收敛到 3 个发送函数 + 1 个引用函数，全部调用点自动覆盖 |
| 编译 / 构建 | 均通过 |
| 产物 | 10,489,016 字节，MD5 `684d3324c8e6bdde4ba7355240a10f54` |
| 遗留 | 原生引用链逆向（F2） |

---

## 二、消息外发域复核

### 2.1 水印接入矩阵

| 组件 | 发送函数 | 水印 |
|---|---|---|
| `WmReflect` | `sendTextMsg` | 是 |
| `GroupFeatures` | `sendTextMessage` | 是 |
| `WeChatMessenger` | `sendText` / `sendQuoteAndAt` / `sendTextWithAt` | 是 |
| `DianGeService` | 经 `WmReflect` / `GroupFeatures` | 是 |
| `RedPacketHook` | 经 `WmReflect` | 是 |
| `WanQunGroupHook` | 经 `WmReflect` | 是 |
| `WmChatHook` | 经 `WmReflect` | 是 |
| `AutoForwardHook` | 经 `WmReflect` | 是 |
| `TtsVoiceSender` 文本伴发 | 经 `WmReflect` | 是 |
| `broadcastRooms` | 经 `WmReflect` | 是 |

### 2.2 识别链路（三层，逐层加强）

```
① 零宽水印（本轮新增，跨分身/多设备均有效，不依赖文案）
        ↓ 未命中
② 固定提示语模板（点歌受理/排队后缀/点歌失败）
        ↓ 未命中
③ 本地账本（精确键 120s / 内容兜底 20s / 会话+类型 45s）
        ↓ 未命中
     判定为普通消息
```

任一命中即跳过，接收侧统一调用 `isSelf` / `isBotSent`，只读不消费，多个 hook 判定一致。

### 2.3 为什么水印能拦住跨分身

A 分身发出 → 微信同步 → B 分身收到 `isSend=0`。B 分身虽然账本为空，但内容**自带水印**，
`hasWatermark` 直接命中。转发到第三个分身时水印随内容一起被转发，仍能命中，不会形成转发链。

---

## 三、其余功能域（回归确认）

| 域 | 功能 | 状态 |
|---|---|---|
| 消息 | 防撤回 | 正常 |
| 消息 | 消息伪装 | 正常（走 UI 链，本轮无改动） |
| 消息 | 转发限制解除 / 转发替换 | 正常 |
| 消息 | 引用 / @ | 部分（原生引用链待逆向；文本回退正常且带水印） |
| 语音 | 语音转发 / 播报 / 点歌 | 正常 |
| 群管理 | 自动进群 / 群标题标签 / 万群同步 | 正常 |
| 红包 | 到账播报 / 抢包 | 正常 |
| 朋友圈 | 自动点赞 | 正常 |
| 界面 | 气泡 / 主题 / 菜单注入 | 正常 |
| 工具 | DexKit 锚点 / 版本适配 | 正常 |
| 风控 | 反检测 / 更新拦截 / 广告拦截 | 正常 |

---

## 四、改动清单

| 文件 | 改动 |
|---|---|
| `v3/hook/SelfMsgGuard.java` | 新增 `MARK` / `watermark` / `hasWatermark`；`isSelf` 改用 `hasWatermark` |
| `v3/wm/utils/WmReflect.kt` | `sendTextMsg` 打水印并用水印内容发送 |
| `v3/hook/GroupFeatures.java` | 入口打水印，四级链统一使用 |
| `ai/hook/wechat/WeChatMessenger.java` | `sendText` / `sendQuoteAndAt` 打水印 |
| `ai/hook/wechat/SendGuard.java` | 复用统一水印实现 |
| `app/build.gradle.kts` / `MainHook.java` / `DexKitHelper.java` | 版本号 → 30581 |

---

## 五、验证矩阵

| 类别 | 项 | 结果 |
|---|---|---|
| 构建 | javac / assembleRelease | 通过 / 通过 |
| 产物 | 版本 / 大小 / MD5 | 30581、3.0.581、10,489,016 B、`684d3324…0f54` |
| 逻辑 | 水印 10 用例（含幂等与 @ 首位） | 10/10 |
| 覆盖 | 全部文本发送调用点 | 全覆盖 |
| 网络 | 公网 turbo / Range | 200 / 206 |

---

## 六、遗留

| # | 项 | 建议 |
|---|---|---|
| 1 | 原生引用消息链在 8.0.79 已失效 | 重新逆向 `k0.I` 与 `MsgQuoteItem` 新字段 |
| 2 | 消息伪装不走统一发送函数 | 如需跨分身识别，可在 UI 改写点补同款水印 |
| 3 | 点歌提示回流是否彻底消失 | 需用户实测并回收 `skip: 模块自发消息` 日志 |

*配套文档：`质检修复报告-v30581.md`、`深度检查清单-v30581.md`。*
