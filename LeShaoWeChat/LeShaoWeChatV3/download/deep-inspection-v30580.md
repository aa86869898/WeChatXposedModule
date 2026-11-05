# 深度检查清单 · v3.0.580

**检查对象**：v3.0.580（本次「乱转发 / 全量群发」修复）
**检查方式**：真实日志定量复盘 → 代码逐行复核 → 失败路径推演 → 离线逻辑验证 → 构建产物校验
**检查人**：模块审查（自动化 + 人工逐项）
**结论**：本轮修复项 **全部通过**；发现 **2 项新增待办**（见文末）

---

## A. 事故定性（先确认「还在不在」）

| # | 检查项 | 方法 | 结果 |
|---|---|---|---|
| A1 | 8 份历史日志里是否有同类群发 | `grep -c "AutoForward: fwd trigger"` 全量扫描 | 仅 `分身leshao_v3_log.txt` 有 **2 次**，其余 7 份均为 0 |
| A2 | 单次事故规模 | 统计 `fwd ->` 唯一目标 | **545 个群 / 1090 条 / 0.571 秒** |
| A3 | 被群发的内容是什么 | 定位 `fwd trigger` 的 content | `🔔叮咚 正在点歌 请稍等片刻…`（**模块自己的点歌受理提示**） |
| A4 | 来源会话 | 同一条 trigger 的 talker | `wxid_qf3ok46p08v922`（私聊，用户在里面发「我想听无情」） |
| A5 | 该消息是「自己发的」还是「收到的」 | 找同 msgId 的 `MessageHook` 行 | **没有** `MessageHook` 行，只有 `LeshaoAI.Recv: 收到文本` → 以**接收形态**进入回调 |
| A6 | 是否带了真实 msgSvrId | 日志字段 | `svrId=1914108797648529663`（真实值，非 0） |
| A7 | 为什么转发了两轮 | 去重逻辑复核 | `svrId==0` 才跳过；此处 svrId≠0 但**两次 insert 的 e9 不同源**，`H0()` 读法在旧版可能取不到 → 去重失效 |
| A8 | 是否还有其它「全量群发」代码路径 | `grep broadcastRooms / sendToAll / allGroups` | `broadcastRooms` 全项目**无调用方**；无其它全量群发路径 |

> **定性**：这是一条**真实存在且可复现**的缺陷链，不是日志噪声。旧版已能触发，当前版本在同等条件下**仍可触发**（见 B 节 B1~B4），必须修复。

---

## B. 缺陷逐项复核（修复前 vs 修复后）

| # | 缺陷点 | 修复前代码 | 风险 | 修复后 | 状态 |
|---|---|---|---|---|---|
| B1 | `isSend` 失败即放行 | `int isSend=-1; try{z0()}catch{}; if(isSend==1)return;` | getter 异常 → 视为对方消息 → 群发 | 三路读取，`-1` 直接 return | ✅ 已修 |
| B2 | 无「模块自发消息」判断 | 完全没有 | 提示语被当新消息 | `SelfMsgGuard` 三重拦截 | ✅ 已修 |
| B3 | `svrId==0` 不去重 | `if(svrId!=0 && !add) return;` | 重复群发 | 内容+时间片兜底键 | ✅ 已修 |
| B4 | 批量发送无节流 | `for(target: sTargets) send()` | 0.57s/1090 条 → 风控 | 单线程 + 180~420ms 间隔 + 连败 5 次中止 + 熔断 | ✅ 已修 |
| B5 | `e9 入库` 不校验 isSend | setter 全部 `catch ignored` | 产生 isSend=0 消息 | 入库前回读，≠1 放弃 | ✅ 已修 |
| B6 | `svrId` 读 `F0()` | `callLong(msg,"F0")` | F0 实为 getContent → 永远取不到 | 改 `H0()` | ✅ 已修 |
| B7 | 内容读 `I0/j/N1` | 三选一 | 8.0.79 实际是 `F0()` | 改 `F0,I0,j,N1` + 沿继承链找 `field_content` | ✅ 已修 |
| B8 | `MessageHook` 接收分支无护栏 | `if(isSend!=1){ 自动回复/播报 }` | 提示语触发自动回复→再发消息→循环 | 分支前 `isSelf` 拦截 | ✅ 已修 |
| B9 | `MsgReceiveHook` isSend 为 null 继续 | `if(isSend!=null && isSend!=0)return;` | null → 当对方消息 | null 直接 return | ✅ 已修 |
| B10 | UI 无批量风险提示 | 只显示昵称列表 | 误选 545 群毫无感知 | 实时计数 + >50 二次确认 + >200 红色告警 | ✅ 已修 |

---

## C. 修复代码逐行复核

### C1. `SelfMsgGuard.java`（新增，约 300 行）

| 检查点 | 结论 |
|---|---|
| 三个 map 是否线程安全 | `ConcurrentHashMap` ✅ |
| 是否可能无限增长 | `trim()` 在 mark 后调用，超 600 条按过期清理，仍超限整体 clear ✅ |
| 过期条目是否会误拦 | 命中时校验 `exp >= now`，过期即移除 ✅ |
| `consume()` 与 `isSelf()` 语义 | `consume` 一次性、`isSelf` 只读；**接收侧统一用 `isSelf`**，保证多个 hook 判定一致（不会因第一个 hook 消费掉而导致第二个 hook 漏判）✅ |
| `norm()` 是否可能吃掉真实内容 | 仅去零宽字符 + 去 `xxx:` 发送者前缀（≤48 字符且不含空格/`<`）✅ |
| `isModuleNoticeText()` 是否误伤普通聊天 | 需与配置模板完全一致（占位符 `{song}` 才放宽为 `.*`）；实测「我想听无情」放行 ✅ |
| 模板读取性能 | 5 秒 TTL 缓存，避免每条消息读 SharedPreferences ✅ |
| `allowBroadcast()` 边界 | `<=0` 拒绝；`>600` 拒绝；`>=20` 参与频率统计；`<20` 直通 ✅ |
| 异常安全 | 所有入口 try/catch，异常时**按「非自发」放行**（不吃用户消息）✅ |

### C2. `AutoForwardHook.onMsgInsert`

| 检查点 | 结论 |
|---|---|
| 判定顺序 | isSend → talker → 来源 → 类型 → msgId 护栏 → 内容护栏 → 去重 → 熔断 → 发送 ✅ |
| `sForwarded` 类型变更 | `Set<Long>` → `Set<String>`，全部引用点已同步 ✅ |
| 去重键唯一性 | `s:<svrId>` / `c:<talker>|<type>|<hash>|<createTime/10>` ✅ |
| 日志可观测性 | `skip: isSend 读取失败` / `skip: 模块自发消息(...)` / `skip: 重复消息` / `skip: 群发熔断生效` / `fwd trigger ... targets=N` ✅ |

### C3. `AutoForwardHook.forwardAll`

| 检查点 | 结论 |
|---|---|
| `sBusy` 是否一定复位 | 在 `POOL.execute` 的 lambda `finally` 内复位；`execute` 抛异常时在 catch 内复位 ✅ |
| 线程是否会阻塞微信回调线程 | 否，全部在 `ls-autofw` 守护线程 ✅ |
| 连续失败中止 | `MAX_CONSEC_FAIL=5`，中止时打印剩余目标数 ✅ |
| 中断处理 | `InterruptedException` → 复位中断标志 + break ✅ |
| `forwardOne` 返回值 | 文本链返回 `WmReflect.sendTextMsg` 真实结果，供失败计数 ✅ |

### C4. 发送入口打标覆盖度

| 入口 | 是否覆盖 | 说明 |
|---|---|---|
| `WmReflect.sendTextMsg` | ✅ | 全项目 10 处 `sendTextMsg(` 调用**全部**经过此函数（已 grep 确认） |
| `GroupFeatures.sendTextMessage` | ✅ | 四级链总入口 |
| `WeChatMessenger.sendText` | ✅ | 备链 |
| `TtsVoiceSender.sendVoiceData` | ✅ | type 34 通配 |
| `RedPacketHook` 回执 | ✅ | 走 `WmReflect.sendTextMsg` |
| `WanQunGroupHook` | ✅ | 走 `WmReflect.sendTextMsg` |
| `WmChatHook` | ✅ | 走 `WmReflect.sendTextMsg` |
| `DianGeService.reply` | ✅ | 走 `GroupFeatures` |

---

## D. 构建与产物校验

| # | 项 | 结果 |
|---|---|---|
| D1 | `./gradlew compileDebugJavaWithJavac` | ✅ 通过（仅 deprecation/unchecked 提示） |
| D2 | `./gradlew assembleRelease` | ✅ 通过 |
| D3 | `aapt2 dump badging` | `versionCode='30580' versionName='3.0.580'` ✅ |
| D4 | 版本号五处同步 | `build.gradle.kts` ×2 / `MainHook` ×2 / `DexKitHelper` ×1 ✅ |
| D5 | APK 大小 | 10,489,025 字节 |
| D6 | APK MD5 | `53155c333df61718485176b224ea877f` |
| D7 | latest 副本 | 与主包字节一致 |

---

## E. 回归风险清单

| # | 风险 | 评估 | 缓解 |
|---|---|---|---|
| E1 | `isSend` 三路都读不到 → 自动转发整体不工作 | 低（`z0()` 在 8.0.79 实证有效） | 日志会打 `skip: isSend 读取失败`，可立即定位 |
| E2 | `e9 入库` 因校验失败而兜底失效 | 低（`i1` 实证有效，且 ① ② 链正常时不会走到 ③） | 日志 `e9 入库放弃: isSend=...` |
| E3 | 提示语模板匹配误伤用户相同文案 | 低（需完全相同） | 仅影响「这条不被转发/不触发 AI」，不影响收发 |
| E4 | 545 目标限速后耗时变长（约 3~4 分钟） | 预期行为 | 日志有进度 `(n/total)` |
| E5 | 熔断误拒正常大批量转发 | 低（20 条以下不参与熔断；60 秒 6 次） | 日志 `熔断: ... 已拒绝` |

---

## F. 新增待办（本轮未做，已记录）

| # | 待办 | 优先级 |
|---|---|---|
| F1 | 红包播报 / AI 回复等**动态文案**跨分身同步时仍靠本地账本，建议统一追加零宽水印（需先解决 @ 消息前缀高亮兼容） | 中 |
| F2 | 原生引用消息链（`ou5.c1`/`xp3.i`/`dx0.r` 在 8.0.79 已只剩 lambda）需重新逆向 | 中 |

---

## G. 用户侧验证指引

装 30580 后请做一次「点歌」并在群里/私聊各发一条普通消息，然后看 `leshao_v3_log.txt`：

**应当出现**
```
SelfMsgGuard: 拦截模块自发消息 ...        ← 若有提示语回流
AutoForward: skip: 模块自发消息(内容命中) ...
AutoForward: fwd trigger: type=1 talker=... targets=N
AutoForward: fwd -> xxx@chatroom ok (1/N)
AutoForward: 本批完成: 成功 x 失败 y 目标 N
GroupSend: ① SendMsgMgr.Jj OK -> ...
LsDianGe: 回复(OK): 🔔叮咚 正在点歌 请稍等片刻…
```

**不应当出现**
```
AutoForward: fwd -> ... ok   ← 连续几百行、同一句提示语
```
