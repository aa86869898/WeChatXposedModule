# 深度检查清单（v3.0.268）

> 生成日期：2026-10-09
> 项目：LeShaoWeChat V3（微信 8.0.78/3180）
> 范围：全项目 179+ 源文件 + 最新实机日志（leshao_v3_log.txt）

---

## 一、日志实证问题（本次新发现，最高优先级）

### 1. `com.tencent.mm.ui.chatting.viewitems.o0` 类不存在 ❌
- **日志**：`MessageMenuHook: load class fail com.tencent.mm.ui.chatting.viewitems.o0 -> ClassNotFoundException`
- **使用位置**：
  - `WxForwardReplaceHook.java:1094`（语音菜单注入候选类）
  - `MessageMenuHook.java:191`（语音菜单提供者）
- **影响**：语音菜单注入 fallback 失败（可能影响收藏语音转发菜单）
- **需要你反编译**：`com.tencent.mm.ui.chatting.viewitems` 包，找**语音菜单上下文菜单提供者**类（含 `a(MMMenu, View, ContextMenuInfo)` 方法），给出真实类名

### 2. `pe5.f.j` 是抽象方法，hook 失败 ❌
- **日志**：`AutoGroupQr: hookJoinApi pe5.f err: Cannot hook abstract methods: public abstract ... pe5.f.j(...)`
- **使用位置**：`AutoGroupQrHook.java:1063-1091`
- **影响**：自动进群的"自动点击进入群聊"失效（pe5.f 是接口，j 是接口方法，不能直接 hook）
- **需要你反编译**：找 `pe5.f` 接口的**实现类**（如 `ln.a`、`un.k` 等），给出实现类中 `j` 方法的具体实现，或提供 `"addchatroommember"` 相关锚点字符串

### 3. `LeftTopEntry` 的 `m()` 方法不存在 ⚠️
- **日志**：`LeftTopEntry: chatting gate installed ... via field r x0 (m() not found)`
- **影响**：已自动 fallback（用字段 r x0），功能可用，但方法 `m()` 是硬编码，升级易变
- **需要你反编译**：`com.tencent.mm.ui.m8` 类中 gate 相关方法名

### 4. `MomentsAutoLike` 锚点类未找到 ⚠️
- **日志**：`ImproveDataUIC not found`、`ImproveInteractionUtil not found`
- **影响**：朋友圈自动点赞的部分增强功能（手动诊断）不可用
- **需要你反编译**：朋友圈 UI 辅助类 `ImproveDataUIC` / `ImproveInteractionUtil` 的真实类名

---

## 二、缺失 DexKit 锚点清单（复用上次核对，需你反编译补充）

### A. 完全无锚点文件
| 文件 | 硬编码类/方法 | 建议锚点 |
|---|---|---|
| `ui/AppColors.kt:580` | `com.tencent.mm.ui.bk.C()` | 含 `getThemeColor`/`C(` 特征的方法所在类 |
| `hook/VoiceAutoPlay.java:86-123` | `com.tencent.mm.app.k0`/`model.k0`/`k0`、`ui.chatting.component.so`、`ui.chatting.x0`、`storage.e9` | `"getVoicePlayer"` 方法所在类、`"voice2"`/`voicemsg` 相关类 |
| `hook/ConversationFilter.java:279/307` | `jo5.f`、`po5.u` | `"ConversationListView"` 字段或 `getConversation` 方法所在类 |
| `hook/LabelSyncHook.java:18` | `aa3.d` | `"MicroMsg.NetSceneLabelSync"` |
| `hook/GroupFeatures.java:58` | `storage.f9.X0/L1/A1/yb` | `"MicroMsg.MsgInfoStorage"` |
| `ai/hook/wechat/WeChatMessenger.java` | `MsgQuoteItem`、`pluginsdk.model.app.k0`、`ou5.c1`、`xp3.i`、`dx0.r`、`yp3.b`、`vp3.e`、`v51.r0`、`storage.e9/f9` | `"MsgQuoteItem"`、`"SendMsgService"`/`"SendTextLogic"`、`"MicroMsg.SendMsgMgr"` |
| `hook/RedPacketHook.java:538-539` | `ph5.n0`、`lj0.a3` | `"LuckyMoney"`/`"RedPacket"` |
| `hook/TtsVoiceSender.java:1527/1918` | `dm.c8`、`b31.w` | `"MsgXml"`、`"ChatroomMembersLogic"` |
| `ai/hook/wechat/StorageHub.java` | `b41.h9`、`b41.e`、`b41.y1`、`storage.f9` | `"getSelfUsername"` |
| `wm/utils/WmReflect.kt` | `e01.v1`、`hm0.j1`、`d24.h`、`kn.x` | `"ChatroomSvc"`、`"ChatroomMembersLogic"` |
| `hook/ChatGroupHook.java:321/705` | `x93.r`、`storage.c4` | 群标签/联系人缓存锚点 |
| `hook/FavVoiceForwardHook.java:150` | `plugin.fav.ui.fc` | 收藏 UI 类 |
| `hook/MomentsFakeLikeHook.java:997/1215` | `SnsObject`、`plugin.sns.storage.l1` | `"MicroMsg.SnsObject"`、`"SnsInfoStorage"` |
| `hook/ChatGroupUiInjector.java:60` | `ui.conversation.s5` | 会话列表 UI 辅助类 |

### B. 自动进群相关（AutoGroupQrHook）缺失锚点
| 硬编码类 | 用途 | 建议锚点 |
|---|---|---|
| `com.tencent.mm.pluginsdk.ui.tools.s6` | 解码结果转 ImageQBarDataBean（实测调用失败） | 含 `RecogQBarOfImageFileResultEvent` 参数且返回 `ImageQBarDataBean` 的类 |
| `com.tencent.mm.plugin.scanner.ImageQBarDataBean` | 码值数据 Bean | 类名本身 |
| `wk5.n`/`wk5.o`/`wk5.l0` | 识别回调/事件回调/结果聚合 | 依赖 wk5.g0 锚点 |
| `un.k`/`ln.a`/`pe5.f`/`qn.m`/`vn.m`/`roomsdk.model.factory.c` | 加群 API 链路 | `"MicroMsg.NetSceneAddChatRoomMember"`、`"ChatRoomAddContactProcess"`、`"addchatroommember"` |
| `modelsimple.k0` | NetSceneGetA8Key | `"geta8key"` |
| `b41.y1` | selfWxid | `"getSelfUsername"` |
| `ph5.n0`/`rn3.u0`/`pe3.a` | 路径解析 | `"THUMBNAIL_DIRPATH"`、`"image2"` |
| `ex0.k0` | 消息反查缓存 | 反查缓存相关 |

### C. 硬编码方法/字段名（无锚点，升级易变）
| 方法/字段 | 出现频次 | 建议锚点 |
|---|---|---|
| `N0`(getTalker)、`H0`(getMsgId)、`F0`、`M0`、`I0` | 高 | `"getTalker"`/`"getMsgId"` |
| `Bb`(消息监听)、`N3` | 中 | `"insertMsg"`/`"onNewMsg"` |
| `X0`、`L1`、`A1`、`yb` | 低 | 群消息修改日志 |
| `n0`/`getPlayer`/`p0`/`o0`/`k0` | 中 | `"getVoicePlayer"` |
| 单字母方法 `a`~`g` 等 | 极高 | 依赖类锚点后反射 |

---

## 三、本次已修复（v3.0.268）
1. **气泡自己的消息不替换**：文本 holder 从单一 `R.textHolder` 扩展为 `R.textHolders` 集合（From/To 两类全部覆盖），`ensureBubble` 类名匹配改为集合+字段探测兜底，adapter 匹配放宽为 `isAssignableFrom` 支持子类，方向判断统一用 `sideOf`
2. **气泡渲染慢**：WxBubble 解析改为 batchFindClassUsingStrings 一次批量查询 + Contains 显式匹配

