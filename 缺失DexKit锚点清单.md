# 缺失 DexKit 字符串/锚点清单（供反编译分析）

> 生成日期：2026-10-09
> 项目：LeShaoWeChat V3（微信 8.0.78/3180）
> 说明：以下列出**所有硬编码微信混淆类/方法但缺少 DexKit 动态锚点兜底**的位置。
> 每个条目给出：所在文件、硬编码的类/方法、用途、**需要你反编译提供的特征字符串/锚点**。
> 标注 ✅ = 已有锚点覆盖；❌ = 缺失锚点（需要你补充）；⚠️ = 有兜底锚点但 fallback 仍硬编码。

---

## 一、完全没有 DexKit 锚点的文件（最高优先级）

### 1. `com/leshao/v3/ui/AppColors.kt` ❌
| 项 | 值 |
|---|---|
| 硬编码类 | `com.tencent.mm.ui.bk`（行 580） |
| 硬编码方法 | `C()` |
| 用途 | 读取微信主题色 |
| **需要反编译** | `com.tencent.mm.ui.bk` 的完整方法签名；建议 DexKit 锚点字符串：**含 `getThemeColor`/`C(` 特征的方法所在类**，或直接反编译该类给出方法名 |

### 2. `com/leshao/v3/hook/VoiceAutoPlay.java` ❌
| 项 | 值 |
|---|---|
| 硬编码类 | `com.tencent.mm.app.k0` / `com.tencent.mm.model.k0` / `com.tencent.mm.k0`（三级兜底，行 86-90） |
| 硬编码类 | `com.tencent.mm.ui.chatting.component.so`（行 97） |
| 硬编码类 | `com.tencent.mm.ui.chatting.x0`（行 117） |
| 硬编码类 | `com.tencent.mm.storage.e9`（行 123） |
| 硬编码方法 | `N0`（getTalker）、`H0`（getMsgId）、`M0`、`n0/getPlayer/N0/getVoicePlayer/p0/o0/k0/I0`（播放器反射枚举，行 828） |
| 用途 | 语音消息自动播放（定位语音播放器） |
| **需要反编译** | ① 语音播放器类（`com.tencent.mm.modelvoice` 下）的真实类名，建议锚点字符串：**`getVoicePlayer` 方法所在类** 或 **`voice2`/`voicemsg`/`amr_` 相关字段所在类**；② `x0`（聊天 UI 组件）的真实类名 |

### 3. `com/leshao/v3/hook/ConversationFilter.java` ❌
| 项 | 值 |
|---|---|
| 硬编码类 | `jo5.f`（行 279） |
| 硬编码类 | `po5.u`（行 307） |
| 用途 | 会话列表过滤（读取会话数据） |
| **需要反编译** | ① `jo5.f`（会话列表数据源）真实类名，建议锚点：**含 `ConversationListView` 字段或 `getConversation` 方法的类**；② `po5.u` 真实类名 |

### 4. `com/leshao/v3/hook/LabelSyncHook.java` ❌
| 项 | 值 |
|---|---|
| 硬编码类 | `aa3.d`（行 18） |
| 用途 | 标签同步网络请求（NetScene） |
| **需要反编译** | `aa3.d` 真实类名，建议锚点字符串：**`NetSceneLabelSync` / `LabelSync` 相关日志前缀**（如 `"MicroMsg.NetSceneLabelSync"`） |

### 5. `com/leshao/v3/hook/GroupFeatures.java` ❌
| 项 | 值 |
|---|---|
| 硬编码类 | `com.tencent.mm.storage.f9`（行 58） |
| 硬编码方法 | `X0`、`L1`、`A1`、`yb`（群消息修改） |
| 用途 | 群功能增强（改群消息） |
| **需要反编译** | `f9` 消息存储类的这些方法名，建议锚点：**`setTalker`/`setContent`/`setCreateTime` 等中文注释不可用，用日志字符串**；`f9` 类可用 `MsgInfoStorage` 锚点（`"MicroMsg.MsgInfoStorage"`） |

### 6. `com/leshao/ai/hook/wechat/WeChatMessenger.java` ❌（AI 模块，锚点缺失最严重）
| 项 | 值 |
|---|---|
| 硬编码类 | `com.tencent.mm.plugin.msgquote.model.MsgQuoteItem`（行 140） |
| 硬编码类 | `com.tencent.mm.pluginsdk.model.app.k0`（行 141） |
| 硬编码类 | `ou5.c1`（行 142） |
| 硬编码类 | `xp3.i`（行 143） |
| 硬编码类 | `dx0.r`（行 144） |
| 硬编码类 | `yp3.b`（行 214） |
| 硬编码类 | `vp3.e`（行 224） |
| 硬编码类 | `v51.r0`（行 340） |
| 硬编码类 | `com.tencent.mm.storage.e9`（行 339）、`com.tencent.mm.storage.f9`（行 363） |
| 硬编码方法 | `Bb`、`N3`、`F0`、`N0`、`E0`、`N1`、`A2`、`c`、`f`、`ej` |
| 用途 | AI 自动回复（构造引用消息、发消息） |
| **需要反编译** | ① 消息引用类 `MsgQuoteItem`（建议锚点：**`MsgQuoteItem` 类名本身可作为 `findClassByName`**，或反编译它的包名）；② `ou5.c1`/`xp3.i`/`dx0.r`（消息发送链路）真实类名，建议锚点：**`SendMsgService`/`SendTextLogic`/`MicroMsg.SendMsgMgr`**；③ `yp3.b`/`vp3.e`（引用相关）真实类名；④ `v51.r0` 真实类名 |

---

## 二、有锚点但关键类仍硬编码的文件（次高优先级）

### 7. `com/leshao/v3/hook/AutoGroupQrHook.java`（自动扫码进群）⚠️
已有锚点：✅ `"MicroMsg.ScanImageUtil"`（v16.a）、✅ `"MicroMsg.ImageScanCodeManager"`/`"doScanCode from decoder msgId"`（wk5.g0）、✅ `"MicroMsg.QBarStringHandler"`/`"[handleCode-dealQBarString]"`（v74.v）

| 硬编码类 | 行号 | 用途 | 锚点状态 |
|---|---|---|---|
| `com.tencent.mm.pluginsdk.ui.tools.s6` | 1311 | 解码结果→ImageQBarDataBean 转换（**实测 s6.a.a 调用失败，类名可能不对**） | ❌ **最高优先** |
| `com.tencent.mm.plugin.scanner.ImageQBarDataBean` | 639/1367/1451 | 码值数据Bean | ❌ |
| `wk5.n` / `wk5.o` / `wk5.l0` | 1300/1440/1408 | 识别回调接口/事件回调/结果聚合 | ⚠️ 依赖 wk5.g0 锚点命中后可推，建议补独立锚点 |
| `un.k` | 1221 | ChatRoom API 工厂 | ❌ |
| `ln.a` | 1098 | pe5.f 接口实现 | ❌ |
| `pe5.f` | 1065 | ChatRoom API 接口 | ❌ |
| `qn.m` | 1124 | NetSceneAddChatRoomMember | ❌ |
| `vn.m` | 1146 | ChatRoomAddContactProcess | ❌ |
| `com.tencent.mm.roomsdk.model.factory.c` | 1169 | 任务执行器（发送） | ❌ |
| `com.tencent.mm.modelsimple.k0` | 1193 | NetSceneGetA8Key | ❌ |
| `b41.y1` | 1229 | selfWxid | ❌ |
| `ph5.n0` | 362 | 路径解析服务定位器 | ❌ |
| `rn3.u0` | 363 | 路径解析服务类 | ❌ |
| `pe3.a` | 387 | image2 根路径 | ❌ |
| `ex0.k0` | 433 | 消息反查缓存 | ❌ |
| `com.tencent.mm.storage.f9` | （消息监听） | 消息存储 | ❌ |

**需要反编译**（按优先）：
1. **`s6` 完整类名** —— 反编译 `com.tencent.mm.pluginsdk.ui.tools` 包，找含 `RecogQBarOfImageFileResultEvent` 参数且返回 `ImageQBarDataBean` 的类，给出完整类名+方法名+静态性。
2. **`un.k` / `ln.a` / `pe5.f` / `qn.m` / `vn.m` / `factory.c` 的真实类名** —— 建议锚点字符串：**`"MicroMsg.NetSceneAddChatRoomMember"`**（qn.m）、**`"ChatRoomAddContactProcess"`**（vn.m）、**`"addchatroommember"`**（cgi 路径）。
3. **`ph5.n0`/`rn3.u0`/`pe3.a`** —— 建议锚点：`"THUMBNAIL_DIRPATH"` 相关字符串、**`"image2"`** 路径处理类。
4. **`b41.y1`** —— 建议锚点：**`"getSelfUsername"` / 自己的 wxid 逻辑**。

### 8. `com/leshao/v3/hook/RedPacketHook.java` ❌
| 项 | 值 |
|---|---|
| 硬编码类 | `ph5.n0`（行 538）、`lj0.a3`（行 539） |
| 用途 | 红包插件定位 |
| **需要反编译** | 红包插件宿主类（`ph5.n0` 是插件服务定位器）、插件接口 `lj0.a3`，建议锚点：**`"LuckyMoney"` / `"RedPacket"` 相关字符串** |

### 9. `com/leshao/v3/hook/TtsVoiceSender.java` ❌
| 项 | 值 |
|---|---|
| 硬编码类 | `dm.c8`（行 1527） |
| 硬编码类 | `b31.w`（行 1918） |
| 硬编码类 | `com.tencent.mm.storage.f9`（行 1962/1997/2107/2203） |
| 用途 | TTS 语音发送（读消息 XML、群成员枚举） |
| **需要反编译** | ① `dm.c8`（消息 XML 存储）真实类名，建议锚点：**`"MsgXml"`/`CDN` 相关字段**；② `b31.w`（群成员枚举）真实类名，建议锚点：**`"ChatroomMembersLogic"`**（已用于 BatchInviteManager，可复用） |

### 10. `com/leshao/v3/wm/utils/WmReflect.kt` ⚠️
已有锚点：✅ `getServiceLocatorClass`、✅ `getContactStorageClass`

| 硬编码类 | 行号 | 用途 | 状态 |
|---|---|---|---|
| `e01.v1` | 75 | 会话列表服务 | ❌ |
| `hm0.j1` | 120/402 | 群聊天服务接口 | ❌ |
| `d24.h` | 379 | 群成员逻辑 | ❌ |
| `kn.x` | 391 | 群信息 | ❌ |

**需要反编译**：`e01.v1`/`hm0.j1`/`d24.h`/`kn.x` 真实类名，建议锚点：**`"ChatroomSvc"`**、**`"ChatroomMembersLogic"`**、**`"getChatroomMember"`** 相关字符串。

### 11. `com/leshao/v3/wm/hook/WmChatHook.java` ⚠️
| 硬编码类 | 行号 | 状态 |
|---|---|---|
| `com.tencent.mm.storage.f9` | 2460 | ❌ 建议 `"MicroMsg.MsgInfoStorage"` 锚点 |
| `gp0.j1` | 2727 | ⚠️ 有 `getJ1ServiceClass`，此处硬编码兜底 |
| `p06` | 2802 | ✅ 有 `getP06ClassName` |

### 12. `com/leshao/ai/hook/wechat/StorageHub.java` ❌
| 硬编码类 | 行号 | 状态 |
|---|---|---|
| `b41.h9` | 179 | ❌ 可用 `getContactStorageClass()` 但未接 |
| `b41.e` | 182 | ❌ |
| `b41.y1` | 532 | ❌ |
| `com.tencent.mm.storage.f9` | 403 | ❌ |

**需要反编译**：`b41.e`（账号）真实类名，建议锚点：**`"getSelfUsername"`** 相关。

### 13. `com/leshao/v3/ContactRepository.kt` ⚠️
| 硬编码类 | 行号 | 状态 |
|---|---|---|
| `b41.h9` | 407 | ⚠️ 有 `getContactStorageClass`，此处硬编码兜底 |

### 14. `com/leshao/v3/hook/BatchInviteManager.java` ⚠️
| 硬编码类 | 行号 | 状态 |
|---|---|---|
| `qe5.b` | 634 | ⚠️ 有 `TAG_ADD_MEMBER` 锚点，fallback 硬编码 |
| `b41.y1` / `b41.s1` / `t73.n` / `vf0.e` | 常量 | ❌ 部分被 `TAG_MEMBERS_LOGIC`/`TAG_FTS` 覆盖 |

### 15. `com/leshao/v3/hook/ChatGroupHook.java` ⚠️
| 硬编码类 | 行号 | 状态 |
|---|---|---|
| `x93.r` | 321 | ❌ 群标签兜底类 |
| `com.tencent.mm.storage.c4` | 705 | ❌ 联系人缓存类 |

### 16. `com/leshao/v3/hook/MsgForgeHook.java` ⚠️
| 硬编码类 | 行号 | 状态 |
|---|---|---|
| `com.tencent.mm.storage.f9` | 853 | ❌ |

### 17. `com/leshao/v3/hook/FavVoiceForwardHook.java` ⚠️
| 硬编码类 | 行号 | 状态 |
|---|---|---|
| `com.tencent.mm.plugin.fav.ui.fc` | 150 | ❌ 收藏 UI 类 |

### 18. `com/leshao/v3/hook/MomentsFakeLikeHook.java` ❌
| 硬编码类 | 行号 | 状态 |
|---|---|---|
| `com.tencent.mm.protocal.protobuf.SnsObject` | 997/1273 | ❌ |
| `com.tencent.mm.plugin.sns.storage.l1` | 1215 | ❌ 朋友圈数据存储 |

**需要反编译**：`SnsObject`/`l1` 建议锚点：**`"MicroMsg.SnsObject"`**、**`"SnsInfoStorage"`**。

### 19. `com/leshao/v3/hook/ChatGroupUiInjector.java` ❌
| 硬编码类 | 行号 | 状态 |
|---|---|---|
| `com.tencent.mm.ui.conversation.s5` | 60 | ❌ |
| `com.tencent.mm.ui.conversation.MainUI` | 137/170 | ⚠️ 未混淆 UI 类，较稳定 |

---

## 三、全名微信 UI 类（相对稳定，可选加锚点）

以下类名未被混淆，升级变化概率低，但严格说仍是硬编码：

| 类 | 使用文件 |
|---|---|
| `com.tencent.mm.ui.chatting.ChattingUI` | TtsVoiceSender、WmEntry |
| `com.tencent.mm.ui.chatting.ChattingUIFragment` | TtsVoiceSender、WmEntry |
| `com.tencent.mm.ui.chatting.BaseChattingUIFragment` | WmEntry |
| `com.tencent.mm.ui.LauncherUI` | AutoGroupQrHook |
| `com.tencent.mm.ui.conversation.MainUI` | ChatGroupUiInjector |
| `com.tencent.mm.ui.chatting.adapter.k` | TtsVoiceSender |
| `com.tencent.mm.modelvoice.MediaRecorder` | TtsVoiceSender |
| `com.tencent.mm.plugin.fav.ui.fc` | FavVoiceForwardHook |
| `com.tencent.mm.pluginsdk.ui.chat.ChatFooter` | TtsVoiceSender |
| `com.tencent.mm.ui.base.AnimImageView` | ChatBubbleHook |
| `com.tencent.mm.ui.widget.MMNeat7extView` | ChatBubbleHook |

---

## 四、硬编码方法/字段名（无锚点保护，升级易变）

以下方法名在代码中被硬编码调用，且**没有对应的 `findMethodsByString` 锚点**。请反编译确认后在微信新版本中更新：

| 方法/字段名 | 出现频次 | 建议锚点字符串 |
|---|---|---|
| `N0`（getTalker）、`H0`（getMsgId）、`F0`、`M0`、`I0` | 高 | `"getTalker"`/`"getMsgId"` |
| `Bb`（消息监听）、`N3` | 中 | `"insertMsg"` / `"onNewMsg"` |
| `X0`、`L1`、`A1`、`yb` | 低 | 群消息修改相关日志 |
| `n0`/`getPlayer`/`p0`/`o0`/`k0`（语音播放器） | 中 | `"getVoicePlayer"` |
| `a`/`b`/`c`/`d`/`e`/`f`/`g`（单字母混淆方法） | 极高 | 依赖类锚点定位后反射，需逐类反编译 |

---

## 五、二次复检说明

- ✅ 已核对：`AvatarHelper` 的 `com.tencent.mm.modelavatar.d1` **有**锚点（`getAvatarHelperClass`），不缺失。
- ✅ 已核对：`com.tencent.mm.storage.e9` 有锚点（`getE9ClassName`），但 `f9` **无**独立锚点，多处硬编码。
- ✅ 已核对：`gp0.j1` 有 `getJ1ServiceClass` 锚点，但部分文件直接硬编码 `gp0.j1`（兜底写法）。
- ✅ 已核对：`p06` 有 `getP06ClassName` 锚点。
- ✅ 已核对：`ph5.n0` 在 BatchInviteManager 中被当 service locator（有 `getServiceLocatorClass`），但 AutoGroupQrHook/RedPacketHook 中用途不同且无锚点。