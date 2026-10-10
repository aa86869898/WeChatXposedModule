# 全项目功能逐一审查报告（v3.0.268）

> 生成日期：2026-10-09 | 项目：LeShaoWeChat V3（微信 8.0.78/3180）
> 状态图例：✅ 正常 / ⚠️ 有隐患（锚点缺失但已兜底）/ ❌ 有问题（需反编译修复）

---

## 一、通讯录与消息（联系人和群聊 · page 3）

| 功能 | 依赖 | 状态 | 说明 |
|---|---|---|---|
| 通讯录导出/联系人读取 | `ContactRepository` + `b41.h9`/`gp0.j1` 锚点 | ⚠️ | `b41.h9` 有 `getContactStorageClass` 锚点，但 `ContactRepository.kt:407` 仍硬编码兜底 |
| 消息防撤回 | `AntiRecallHook` | ✅ | 日志正常 |
| 语音转发 | `VoiceForwardHook` | ✅ | 日志 post-scan init 正常 |
| 收藏语音转发 | `FavVoiceForwardHook` + `plugin.fav.ui.fc` | ❌ | `fc` 类无锚点（清单 §二.A） |
| 自动转发 | `AutoForwardHook` | ✅ | 群发依赖，日志正常 |
| 自定义气泡（旧入口） | `ChatBubbleHook` | ⚠️ | 已被 WxBubble 新方案替代（见 §八） |
| 微信ID注入 | `WeChatIdInjectHook` | ✅ | 日志正常 |
| 批量加好友 | `BatchInviteManager` + `qe5.b`/`b41.y1`/`vf0.e` | ⚠️ | `qe5.b` 有 `TAG_ADD_MEMBER` 兜底，`b41.y1` 无锚点 |

## 二、聊天分组（page 14）

| 功能 | 依赖 | 状态 | 说明 |
|---|---|---|---|
| 群聊分组/标签 | `ChatGroupHook` + `x93.r`/`storage.c4` | ⚠️ | `x93.r`（群标签兜底）、`storage.c4`（联系人缓存）无锚点 |
| 群成员头衔标签 | `GroupTitleTagHook` | ✅ | 日志正常，微信美化入口 |

## 三、TTS 语音播报（page 8）

| 功能 | 依赖 | 状态 | 说明 |
|---|---|---|---|
| TTS 播报引擎 | `TtsVoiceSender` + `dm.c8`/`b31.w`/`storage.f9` | ❌ | `dm.c8`（消息XML）、`b31.w`（群成员枚举）无锚点（清单 §二.A） |
| 配音 API | `CubeTtsPlayer`/`TtsVoiceSender`（网络） | ✅ | 纯网络功能，不依赖微信类 |
| 在线音乐卡片 | `TtsVoiceSender`（QQ音乐接口） | ✅ | 纯网络 |
| 语音消息处理 | `VoiceAutoPlay` + `k0`/`so`/`x0` | ❌ | `com.tencent.mm.app.k0` 等三级兜底无锚点（清单 §二.A），但 `storage.e9` 有锚点 |

## 四、在线音乐（page 22）

| 功能 | 依赖 | 状态 | 说明 |
|---|---|---|---|
| 酷我音乐搜索/播放/下载 | `KuwoMusicApi` 等（纯网络） | ✅ | 不依赖微信混淆类，日志无异常 |

## 五、乐少群发（page 99）

| 功能 | 依赖 | 状态 | 说明 |
|---|---|---|---|
| 万群定时群发 | `GroupSendPageView` + `AutoForwardHook`/`ChatFooterBarHook`/`WmChatHook` | ✅ | 日志调度 43 个任务成功 |
| 群发记录 | `BatchAddRecordPageView` | ✅ | 正常 |

## 六、群管理助手（page 4）

| 功能 | 依赖 | 状态 | 说明 |
|---|---|---|---|
| 群成员管理/统计 | `WxMasterFeatures` + `WmReflect`（`e01.v1`/`hm0.j1`/`d24.h`/`kn.x`） | ❌ | 4 个硬编码类无锚点（清单 §二.A） |
| 一键免打扰/解除 | `GroupMuteHook` | ✅ | 日志正常 |
| 批量拉群 | `WanQunGroupHook` | ✅ | 日志正常 |

## 七、消息增强

| 功能 | 依赖 | 状态 | 说明 |
|---|---|---|---|
| 消息长按菜单净化 | `MessageMenuHook` + **`viewitems.o0`** | ❌ | **日志实证 `o0` 类不存在（ClassNotFoundException）**，语音菜单注入失效 |
| 消息伪装 | `MsgForgeHook` + `storage.f9` | ⚠️ | `f9` 无独立锚点，多处硬编码（清单 §二.C） |
| 输入框快捷按钮 | `ChatFooterBarHook` | ✅ | 日志正常 |
| 聊天时间修改 | `ChatBubbleHook`（时间线） | ✅ | 微信美化 → 聊天时间修改 |
| 转发多选上限突破 | `ForwardLimitHook` | ✅ | 日志正常 |
| 原生转发按钮替换 | `WxForwardReplaceHook` + `viewitems.o0` | ❌ | 同 `o0` 类不存在问题 |

## 八、自定义气泡 / 微信美化（page 33/27）

| 功能 | 依赖 | 状态 | 说明 |
|---|---|---|---|
| 自定义气泡（WxBubble 新方案） | `WxBubbleFinal.kt` | ⚠️ | **v3.0.268 已修复自己消息不替换**（多 holder 覆盖），待实机验证 |
| 气泡文字颜色/时间线/昵称色 | `ChatBubbleHook` | ✅ | 配置持久化正常 |
| 群头衔标签 | `GroupTitleTagHook` | ✅ | 正常 |

## 九、更多功能（page 28）

| 功能 | 依赖 | 状态 | 说明 |
|---|---|---|---|
| 去广告 | `AdBlockerHook` | ✅ | 日志 hooked `pu0.m` 正常 |
| 定位伪装 | `FakeLocationHook` | ✅ | 日志正常 |
| 朋友圈自动点赞 | `MomentsAutoLikeHook` | ❌ | **`ImproveDataUIC`/`ImproveInteractionUtil` 未找到**（日志实证） |
| 朋友圈秒集赞 | `MomentsFakeLikeHook` + `SnsObject`/`l1` | ❌ | `SnsObject`、`plugin.sns.storage.l1` 无锚点（清单 §二.A） |
| 查看微信 wxid | `WxIdViewPageView` + `WeChatIdInjectHook` | ✅ | 正常 |
| 右上角加号注入 | `PlusMenuInjector` | ✅ | 日志正常 |
| 左上角三横菜单 | `LeftTopEntryHook` | ⚠️ | `m()` 方法不存在已 fallback（日志实证） |
| 消息防撤回/转发/收藏 | 见 §一 | ⚠️ | 同上 |

## 十、自动功能

| 功能 | 依赖 | 状态 | 说明 |
|---|---|---|---|
| 自动抢红包 | `RedPacketHook` + `ph5.n0`/`lj0.a3` | ❌ | 红包插件定位类无锚点（清单 §二.A） |
| 自动扫码进群 | `AutoGroupQrHook` + 加群链路 | ❌ | **`pe5.f.j` 抽象方法无法 hook**（日志实证）；`s6`/`ImageQBarDataBean`/`un.k`/`ln.a`/`qn.m`/`vn.m`/`factory.c` 等无锚点（清单 §二.B） |
| 相册二维码模拟 | `FaceScanHook` | ✅ | 日志正常，群码 URL 保护已实现 |
| 数据库直读 | `WeChatDbPageView` + `ContactRepository` | ⚠️ | 依赖 `b41.h9` 锚点，有兜底 |
| 微信热更新拦截 | `WeChatUpdateBlocker` | ✅ | 正常 |

## 十一、AI 助手模块（`com.leshao.ai`）

| 功能 | 依赖 | 状态 | 说明 |
|---|---|---|---|
| AI 自动回复 | `AIBotCore` + `WeChatMessenger`/`StorageHub` | ❌ | **锚点缺失最严重**：`MsgQuoteItem`、`ou5.c1`、`xp3.i`、`dx0.r`、`yp3.b`、`vp3.e`、`v51.r0`、`b41.h9/e/y1`、`storage.f9` 全部无锚点（清单 §二.A） |
| 消息监听/触发 | `MsgReceiveHook`/`TriggerEngine` | ⚠️ | 依赖 `storage.e9` 锚点（有） |
| AI 面板 | `AiAssistantPanel` | ✅ | 纯 UI + 网络 |

---

## 十二、按优先级汇总（需要你反编译）

### P0（日志实证错误，功能当前不可用/部分失效）
1. **`com.tencent.mm.ui.chatting.viewitems.o0`** → ClassNotFoundException（长按菜单/转发替换）
2. **`pe5.f.j`** → 抽象方法无法 hook（自动进群自动点击失效）
3. **`MomentsAutoLike` 的 `ImproveDataUIC`/`ImproveInteractionUtil`** → 未找到（朋友圈点赞诊断）
4. **`LeftTopEntry` 的 `m()`** → 不存在（已 fallback，功能可用但脆弱）

### P1（硬编码类无锚点，升级必挂）
- `s6`、`ImageQBarDataBean`、`un.k`、`ln.a`、`qn.m`、`vn.m`、`roomsdk.model.factory.c`、`modelsimple.k0`、`b41.y1`、`ph5.n0`、`rn3.u0`、`pe3.a`、`ex0.k0`（自动进群）
- `ou5.c1`、`xp3.i`、`dx0.r`、`yp3.b`、`vp3.e`、`v51.r0`、`MsgQuoteItem`、`pluginsdk.model.app.k0`（AI 模块）
- `dm.c8`、`b31.w`、`storage.f9`（TTS/群发）
- `ui.bk`、`jo5.f`、`po5.u`、`aa3.d`、`x93.r`、`storage.c4`、`ui.conversation.s5`、`plugin.fav.ui.fc`、`SnsObject`、`l1`、`ph5.n0`、`lj0.a3`（各功能）

### P2（方法名无锚点，升级易变）
- `N0`/`H0`/`F0`/`M0`/`I0`（消息 getter）
- `Bb`/`N3`（消息监听/发送）
- `X0`/`L1`/`A1`/`yb`（群消息修改）
- `n0`/`getPlayer`/`p0`/`o0`/`k0`（语音播放器）

---

## 十三、本次已修复（v3.0.268）
- ✅ 自定义气泡：自己的消息不替换（文本 holder 只覆盖对方类）→ 扩展为多 holder 集合 + 字段探测兜底 + 方向判断统一 sideOf
- ✅ 自定义气泡：解析慢 → batchFindClassUsingStrings 一次批量查询
- ✅ 缓存信任：微信版本不变时模块升级不再全量扫描（启动快）
- ✅ 扫描线程：提升为前台优先级，首次扫描更快

