# 质检修复报告 · v3.0.580

**版本**：3.0.580（versionCode 30580）
**构建**：`assembleRelease` 通过
**APK**：`LeShaoWeChat-v30580.apk`，10,489,025 字节，MD5 `53155c333df61718485176b224ea877f`
**基准微信**：8.0.79 / versionCode 3200（94 个真实 dex）
**本次主题**：**彻底关闭「模块消息被乱转发 / 全量群发」这条链路**

---

## 一、事故复盘（用真实日志定量）

数据来源：`分身leshao_v3_log.txt`（11-05 06:55:12 → 07:15:43，9969 行）

### 1.1 事故时间线

```
07:13:30.853  MessageHook #5185  isSend=1  talker=wxid_qf3ok46p08v922  content=我想听无情
07:13:32.629  LeshaoAI.Recv     收到文本 talker=wxid_qf3ok46p08v922
                                svrId=1914108797648529663
                                content='🔔叮咚 正在点歌 请稍等片刻…'
07:13:32.630  AutoForward      fwd trigger: type=1 talker=wxid_qf3ok46p08v922
07:13:32.631  WmReflect        getSendMsgMgr OK: ni5.n0.c(no3.c1) -> rt5.i5
07:13:32.633  AutoForward      fwd -> 24704928418@chatroom ok
07:13:32.634  AutoForward      fwd -> 4774584670@chatroom ok
              ……（连续 545 个群）……
07:13:32.998  AutoForward      fwd trigger（同一条内容，第二次触发）
              ……（再来 545 个群）……
07:13:33.204  AutoForward      fwd -> 最后一个目标
```

### 1.2 量化结果

| 指标 | 数值 |
|---|---|
| `正在点歌` 相关日志行 | 2956 |
| 被转发到的**不同会话**数 | **546** |
| 唯一转发目标数 | **545** |
| 转发触发次数 | **2**（同一条内容被转发两轮） |
| 实际外发消息数 | **≈1090 条** |
| 全部发生在 | **0.571 秒**内（32.633 → 33.204） |
| 失败条数 | 0（全部 `ok`） |
| 其它日志是否出现同类事故 | 否（其余 7 份日志 `fwd trigger` 均为 0） |

### 1.3 结论：这是「模块自己发的提示语被自己当成别人的消息全量群发」

1. 用户在**私聊** `wxid_qf3ok46p08v922` 里发「我想听无情」→ 点歌功能接管。
2. 模块发出受理提示 `🔔叮咚 正在点歌 请稍等片刻…`。
3. 这条提示**以「接收形态」回到消息落库回调**（`LeshaoAI.Recv: 收到文本`，带真实 `msgSvrId`，
   不是 `isSend=1` 的自己发送记录）。
4. 「自动转发」的来源名单里恰好包含该会话、类型允许文本 → 判定为「收到了一条新消息」。
5. 目标名单有 545 个群 → 一次把提示语群发到 545 个群；由于去重失效，**转发了两轮**。

**所以用户看到的「群发哪都是了」，就是模块把自己的一句提示语刷到了全部群。**

---

## 二、根因（4 个缺陷叠加，缺一不可）

| # | 缺陷 | 位置 | 后果 |
|---|---|---|---|
| R1 | **没有「模块自发消息」概念**：任何接收侧 hook 都不区分「这条是我自己发的」 | 全项目 | 提示语被当作新消息 |
| R2 | **`isSend` 读取失败即放行**：`int isSend=-1`，getter 抛异常就继续往下走 | `AutoForwardHook.onMsgInsert`<br>`MsgReceiveHook.onMessageInserted` | 读不到 → 误判为对方消息 |
| R3 | **去重对 `svrId==0` 直接放行**：`if (svrId != 0 && !add(svrId)) return;` | `AutoForwardHook` | 同一条消息转发两轮 |
| R4 | **批量发送无任何节流/熔断**：for 循环裸发 545 条 | `AutoForwardHook.forwardAll` | 0.57 秒 1090 条，刷屏 + 极易触发微信风控封号 |

补充（本次一并修掉的关联缺陷）：

| # | 缺陷 | 位置 |
|---|---|---|
| R5 | `e9 入库` 不校验 `isSend`，setter 失败仍入库 → 消息以「别人发来的」形态进入所有回调 | `GroupFeatures.insertViaMsgInfo` |
| R6 | `svrId` 用 `F0()`（实为 `getContent`）读取 | `MsgReceiveHook` |
| R7 | 内容读取只试 `I0/j/N1`，8.0.79 实际是 `F0()`；`field_content` 声明在父类 `im.c8`，`getDeclaredField` 取不到 | `AutoForwardHook.readContent`<br>`MsgReceiveHook.readContent` |

---

## 三、修复内容

### 3.1 新增 `SelfMsgGuard`（模块自发消息护栏）

`app/src/main/java/com/leshao/v3/hook/SelfMsgGuard.java`

- **本地账本**：模块每次发送前登记 `talker|type|hash(content)`（TTL 120s）与 `hash(content)`（TTL 20s），
  无正文消息（语音/图片）登记 `talker|type` 通配（TTL 45s）；另有 `msgId` 账本。
- **内容识别（跨分身/多设备）**：`isModuleNoticeText()` 用**配置的点歌提示模板**（`{song}` → 任意）
  与排队后缀 `（前面还有 N 位）` 做匹配，不依赖本地状态 ——
  因为真实事故发生在**分身**环境：A 分身发出的提示语经微信同步到 B 分身时是「别人发来的」
  （`isSend=0` + 真实 `svrId`），B 分身的本地账本里根本没有这条记录。
- **零宽水印识别**：`\u200B\u200C` 开头即判定为模块消息（AI 回复已带该水印）。
- **群发熔断** `allowBroadcast()`：单批目标 > 600 直接拒绝；> 20 的批量 60 秒内最多 6 次、
  两次之间至少间隔 3 秒。

### 3.2 `AutoForwardHook` 加固

| 项 | 修复前 | 修复后 |
|---|---|---|
| isSend | 读不到 → 放行 | `z0()/getIsSend()/field_isSend` 三路读取，**全失败即不转发** |
| 模块消息 | 无判断 | `isSelfMsgId()` + `isSelf()`（账本/内容模板/水印）三重拦截，命中写日志 `skip: 模块自发消息` |
| 去重 | `svrId==0` 不去重 | `svrId` 优先；为 0 时用 `talker|type|contentHash|createTime/10` 兜底 |
| 批量发送 | 同步裸循环 | 独立单线程 + 单批互斥 + 每条间隔 180~420ms + 连续失败 5 条中止 |
| 熔断 | 无 | `SelfMsgGuard.allowBroadcast()` |
| 日志 | `fwd trigger` 不带目标数 | 带 `targets=N`，结束打印「本批完成: 成功 x 失败 y 目标 n」 |

### 3.3 发送入口统一打标

| 入口 | 处理 |
|---|---|
| `WmReflect.sendTextMsg`（SendMsgMgr 主链） | 发送前 `SelfMsgGuard.mark(toUser, type, content)` |
| `GroupFeatures.sendTextMessage`（四级链总入口） | 入口即 mark，覆盖 ①②③④ 全部链路 |
| `WeChatMessenger.sendText` | 入口即 mark |
| `TtsVoiceSender.sendVoiceData` | 发送前 mark（type 34 通配） |

### 3.4 `GroupFeatures.insertViaMsgInfo` 增加入库前校验

`i1(1)` 设置完成后**回读 `isSend`**，不等于 1 直接放弃本条入库并写日志
`e9 入库放弃: isSend=-1 (期望 1)`。宁可这条兜底不发，也不允许产生一条「看起来是别人发来」的消息。

### 3.5 其它接收侧 hook 同步加固

- `MsgReceiveHook`（`LeshaoAI.Recv`）：新增自发消息拦截；`isSend` 读不到即跳过；`svrId` 由 `F0()` 改 `H0()`。
- `MessageHook`（关键词自动回复 / 语音播报 / 点歌入口）：`isSend != 1` 分支前加自发消息拦截，
  避免提示语触发自动回复再发一条消息（自我循环）。

### 3.6 自动转发设置 UI

- 目标行下方新增实时提示：`已选 N 个目标`；> 50 橙色告警，> 200 红色告警。
- 保存时目标数 > 50 弹出二次确认：「每条命中的消息都会被转发到这 N 个会话，量大会触发微信风控甚至封号」。

---

## 四、验证

### 4.1 编译与产物

| 项 | 结果 |
|---|---|
| `compileDebugJavaWithJavac` | 通过 |
| `assembleRelease` | 通过 |
| `aapt2 dump badging` | `versionCode='30580' versionName='3.0.580'` |
| 五处版本号 | 30580 / 3.0.580 全部同步 |
| APK 大小 / MD5 | 10,489,025 字节 / `53155c333df61718485176b224ea877f` |

### 4.2 护栏匹配逻辑离线验证（8 个用例）

用与 `SelfMsgGuard` 完全相同的算法在 JVM 上跑（`/tmp/opencode/guardtest/T.java`）：

| # | 用例 | 期望 | 结果 |
|---|---|---|---|
| 1 | 本机自发，精确键命中 | 拦截 | ✅ true |
| 2 | 分身同步，内容一致但带群发送者前缀 `wxid_xxx:\n` | 拦截 | ✅ true |
| 3 | 模板含 `{song}` 占位符：「正在点歌：无情」 | 拦截 | ✅ true |
| 3b | 占位符不误伤：「我想听无情」 | 放行 | ✅ false |
| 4 | 带排队后缀 `（前面还有 2 位）` | 拦截 | ✅ true |
| 5 | 同会话普通消息「哈喽」 | 放行 | ✅ false |
| 6 | 语音通配（无正文） | 拦截 | ✅ true |
| 7 | 零宽水印 `\u200B\u200C你好` | 拦截 | ✅ true |
| 8 | 点歌提示语出现在其它群 | 拦截 | ✅ true（提示语本就不该被转发） |

> 注：用例 3 首次失败，原因是「先 `Pattern.quote` 整串再替换 `{song}`」在 `\Q...\E` 块内无法替换；
> 已改为按占位符切分后逐段 quote，复测通过。

### 4.3 事故场景回归（推演）

| 场景 | 修复前 | 修复后 |
|---|---|---|
| 点歌提示在本机落库后回到接收侧 | 群发 545 群 ×2 | 拦截（账本命中） |
| 点歌提示从另一分身/设备同步过来（isSend=0，真实 svrId） | 群发 545 群 | 拦截（内容模板命中） |
| `z0()` 反射失败 | 视为对方消息 → 群发 | 不转发（失败即跳过） |
| `svrId==0` | 转发两轮 | 内容兜底键去重，只发一轮 |
| 配置误选 545 个群 | 0.57 秒发完 1090 条 | 二次确认 + 限速（约 3~4 分钟）+ 熔断 |
| `e9 入库` 的 `i1` 失效 | 产生 isSend=0 的消息 | 放弃入库并报错 |

---

## 五、残留风险与后续

1. **跨分身/多设备**：内容模板识别已覆盖点歌提示；**红包播报、AI 回复**等动态文案目前靠本地账本 + 水印，
   跨分身同步时仍可能漏拦（但已不会像点歌提示那样固定文案被放大）。
   后续可给所有模块外发文本统一追加零宽水印（需先解决 @ 消息前缀高亮兼容问题）。
2. **原生引用消息**：8.0.79 的 `ou5.c1` / `xp3.i` / `dx0.r` 已只剩 lambda 与 render 方法，引用链仍待重新逆向。
3. 用户侧需实测一轮，回收 `leshao_v3_log.txt` 中：
   - `SelfMsgGuard: 拦截模块自发消息` / `AutoForward: skip: 模块自发消息`
   - `AutoForward: fwd trigger ... targets=N` 与 `本批完成: 成功 x 失败 y`
   - `GroupSend: ①/②/③/④`、`LsDianGe: 回复(OK/FAIL)`

---

*本报告与 `深度检查清单-v30580.md`、`全项目功能审查报告-v30580.md` 配套。*
