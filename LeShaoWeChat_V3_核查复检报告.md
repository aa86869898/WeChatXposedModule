# LeShaoWeChat V3 — 硬编码类/方法核查复检报告（微信 8.0.78/3180）

> 生成日期：2026-10-09 ｜ 项目：LeShaoWeChat V3
> 说明：所有结论均基于当前 APK 实际反编译（jadx/dexlib2）与字符串锚点核对得出。✅ = 已确认真实类/方法；❌ = 硬编码在本版本不存在（失效）；⚠️ = 有兜底但建议加强。

---

## 一、消息 / 存储层（复用率最高，建议优先接入）

### ✅ 消息实体 MsgInfo → `com.tencent.mm.storage.e9`
- 日志锚点：**`"MicroMsg.MsgInfo"`**
- 继承链：`e9` → `dx0.p3` → **`im.c8`** → `qf5.f0`
- **核心 getter/setter 定义在基类 `im.c8`**（`e9` 只覆盖少量）：
  - `N0()Ljava/lang/String;` = **getTalker**（清单里的 `N0` 存在，但在 `im.c8`）
  - `getMsgId()J` = **getMsgId**（**未混淆**；硬编码的 `H0` 已失效）
  - `getCreateTime()J`、`getType()I`、`getDBInfo()`（均未混淆）
  - `setMsgId(J)V`、`setType(I)V`、`W0(J)V` = setCreateTime
  - **群消息修改 setter（GroupFeatures 关键）**：
    - `b1(String)` = **setContent**（`field_content`）
    - `u1(String)` = **setTalker**（`field_talker`）
    - `W0(J)` = setCreateTime
    - `X0(String)` = setBizChatUserId
- ⚠️ **`H0`、`A1`、`yb` 在本版本不存在**（已在 `e9`+`im.c8` 全方法核对）。`M0()I` 存在于 `im.c8`，但用途非 getMsgId。

### ✅ 消息存储 DAO MsgInfoStorage → `com.tencent.mm.storage.f9`
- 日志锚点：**`"MicroMsg.MsgInfoStorage"`**
- 关键方法：`Bb(e9,Z)J`（= **insert**）、`Db(e9)J`、`sa(e9)J`、`pa(e9)I`、`G7(String)e9` 等
- 覆盖：GroupFeatures / WeChatMessenger / TtsVoiceSender / WmChatHook / MsgForgeHook / StorageHub 中所有 `f9` 硬编码。

---

## 二、`b41` 账户 / 联系人 / 群成员包

| 硬编码 | 真实身份 | 日志锚点 | 关键方法 |
|---|---|---|---|
| `b41.e` | **AccountStorage** | `"MicroMsg.AccountStorage"` | 返回大量存储单例（`lb/mb/vn3.v0/storage.m/o/q/v/w...`） |
| `b41.y1` | **ConfigStorageLogic**（self wxid） | `"MicroMsg.ConfigStorageLogic"` | `c()String` / `b()String` = **selfUsername**（配置 key 42） |
| `b41.h9` | ContactStorage 定位器（`b()`→`b41.e`、`c()`→`b41.c8`、`g()`→`storage.i3`） | 用 `getContactStorageClass()` 覆盖 | — |
| `b41.u1` | **ChatroomMembersLogic** | `"ChatroomMembersLogic"` | `A-z` 群成员增删查 |
| `b41.aa` | 另一 MsgInfoStorage 实现（同样用 `"MicroMsg.MsgInfoStorage"`） | — | — |

→ `b41.u1` 可直接替代 TtsVoiceSender / WmReflect 中的 `b31.w` 群成员枚举硬编码。

---

## 三、VoiceAutoPlay（语音自动播放）— 全部有解

| 硬编码 | 真实身份 | 锚点 |
|---|---|---|
| `com.tencent.mm.ui.chatting.x0` | ✅ 即 `"MicroMsg.AutoPlay"` 逻辑类 | `"MicroMsg.AutoPlay"` |
| 语音播放器（`modelvoice` 下） | **`v61.j1`**（`getStatus/isPlaying/pause/resume/seek/stop/setMute`，`j(String,Z,I)Z`=play） | `"MicroMsg.VoicePlayer"` |
| 语音内容解析 | **`v61.a1`** | `"VoiceContent"` |
| 聊天语音组件 | `com.tencent.mm.ui.chatting.component.vo`（`F0(e9,I)V` 等） | 组件类 |
| `com.tencent.mm.ui.chatting.component.so` | ❌ 只是 `Runnable`（`vo` 的内部任务） | — |

注意：语音模块在 8.0.78 中包名为 `v61`（`modelvoice` 混淆后）。

---

## 四、WeChatMessenger（AI 发消息链路）— 全部有解

| 硬编码 | 真实身份 | 锚点 |
|---|---|---|
| `com.tencent.mm.plugin.msgquote.model.MsgQuoteItem` | ✅ 存在（未混淆） | 类名本身 |
| `com.tencent.mm.pluginsdk.model.app.k0` | App 消息发送工具 | `"MicroMsg.AppMessage"` |
| `dx0.r` | **AppMessage**（XML 构建/解析） | `"MicroMsg.AppMessage"` |
| `xp3.i` | **MsgQuoteHelp**（引用消息构造） | `"MicroMsg.msgquote.MsgQuoteHelp"` |
| `yp3.b` / `vp3.e` | msgquote 存储实体 / DAO | 引用 `MsgQuoteItem` |
| `v51.r0` | **NetSceneSendMsg** | `"MicroMsg.NetSceneSendMsg"` / cgi `"/cgi-bin/micromsg-bin/newsendmsg"` |
| `oh0.a` / `oh0.c` | **SendTextLogic**（协程流） | `"SendTextLogic"` |
| `ou5.c1` | 发消息辅助工具（生成 `ou5.u` / `pc5.bc5`） | — |

---

## 五、会话列表 / 过滤

- ✅ `po5.u` = **ConversationRecyclerAdapter** → 锚点 **`"MicroMsg.ConversationRecyclerAdapter"`**
- ✅ `jo5.e` = **ConversationWithCacheAdapter**（含 `getConvList`）→ 锚点 **`"MicroMsg.ConversationWithCacheAdapter"`**
- `jo5.f` = 小型会话项持有类（`"conv"`/`"getUsername"`/`"list"`）
- `io5.c` = **ConvBoxConversationService**（`"MicroMsg.ConvBoxConversationService"`，折叠会话盒子，非主列表）

---

## 六、标签同步（LabelSyncHook）

- ❌ `aa3.d` **不是 NetScene**（是单例枚举，`<clinit>` 仅 `"INSTANCE"`）
- ✅ 真实标签同步 NetScene = **`mf3.d`**（`doScene/getType/onGYNetEnd`，使用 `"GetContactLabel"` / `"getcontactlabel"`）
- 建议锚点：**`"getcontactlabel"`**（cgi 路径，最稳定）

---

## 七、扫码进群（AutoGroupQrHook）— 链路已打通

| 硬编码 | 真实身份 | 锚点/说明 |
|---|---|---|
| `com.tencent.mm.pluginsdk.ui.tools.s6` | ✅ 静态工具：`a(RecogQBarOfImageFileResultEvent)` 返回 `ArrayList`、`b(...)`→`CodePointRect`、`e(...)`→String | **调用失败原因是参数应为事件对象**，不是类名错 |
| `com.tencent.mm.plugin.scanner.ImageQBarDataBean` | ✅ 存在（未混淆） | 类名本身 |
| `un.k` | ChatRoom API 工厂：`get() → pe5.f` | — |
| `pe5.f` | **ChatRoom API 接口**（a-q 均返回 `com.tencent.mm.roomsdk.model.factory.a`） | — |
| `ln.a` | **ChatRoom API 实现**（同 pe5.f 签名） | — |
| `qn.m` | **NetSceneAddChatRoomMember** | `"MicroMsg.NetSceneAddChatRoomMember"` / cgi `"addchatroommember"` |
| `vn.m` | **ChatRoomAddContactProcess**（构造含 `ChatroomInfoUI$LocalHistoryInfo`） | — |
| `com.tencent.mm.roomsdk.model.factory.c` | 房间任务执行器（`a/b/c(Context,...)`） | — |
| `com.tencent.mm.modelsimple.k0` | **NetSceneGetA8Key**（`doScene/getType/onGYNetEnd`） | 包名未混淆 |
| `ex0.k0` | 消息反查缓存（`N9/k2/pg/yi` 返回 `storage.e9`） | — |
| `wk5.n` | 扫码识别回调（处理 `RecogQBarOfImageFileResultEvent/FailedEvent`） | — |
| `wk5.o` | 事件回调（`NotifyDealQBarStrResultEvent`） | — |
| `pe3.a` | 路径提供类（`a/b/c/d()` 返回 String，image2 根路径） | — |

已有锚点 `"MicroMsg.ScanImageUtil"`、`"MicroMsg.ImageScanCodeManager"`、`"MicroMsg.QBarStringHandler"` 仍有效。

---

## 八、红包（RedPacketHook）

- 红包插件主体在 **`com.tencent.mm.plugin.luckymoney.*`**（未混淆）
- `ph5.n0` = 插件宿主框架类（方法 a-l 管理 `ph5.m/ph5.w/rh5.a`）
- 建议锚点：红包插件内字符串 **`"LuckyMoney"`**（命中大量 `plugin.luckymoney` 类）再按包名过滤

---

## 九、朋友圈 / 收藏 / TTS

- **Moments**：`com.tencent.mm.protocal.protobuf.SnsObject` ✅ 存在（未混淆）；`com.tencent.mm.plugin.sns.storage.l1` = **SnsInfoStorage**（`a/b/c/d` 返回 `SnsInfo`），锚点建议 `"MicroMsg.SnsInfoStorage"`
- **Fav**：`com.tencent.mm.plugin.fav.ui.fc` ✅ 存在（FavoriteIndexUI 长按监听）
- **TTS**：
  - ❌ `dm.c8` **本版本不存在**；`gv3.b` 是 `"OrderCommonMsgXml"` 存储（非语音）
  - 语音 XML 解析实际在 **`v61.a1`** 与 `e9.b1()`（引用 `"MicroMsg.VoiceContent"`）
  - `b31.w` → 用 **`b41.u1`**（ChatroomMembersLogic）替代

---

## 十、WmReflect.kt / WmChatHook / 其他

| 硬编码 | 状态 | 结论 |
|---|---|---|
| `e01.v1` | ❌ 不存在 | 会话列表服务需重定位；可用 `jo5.e`（ConversationWithCacheAdapter） |
| `hm0.j1` | ❌ 不存在 | 群聊服务 → **`p02.a`**（`"MicroMsg.ChatroomService"`） |
| `d24.h` | ❌ 非群成员逻辑（是 recordvideo 音频缓存 ViewHolder） | 用 `b41.u1` |
| `kn.x` | ❌ 不存在 | 群信息 → 用 `com.tencent.mm.storage.y3` / Chatroom 相关 |
| `gp0.j1` | ⚠️ 有 `getJ1ServiceClass` | 服务定位器，建议保留锚点 |
| `x93.r` | ❌ 不存在 | ChatGroupHook 群标签兜底类需重定位 |
| `com.tencent.mm.storage.c4` | ✅ 联系人缓存实体（`getDBInfo`） | — |
| `qe5.b` | ⚠️ 有 `TAG_ADD_MEMBER` 锚点 | fallback 仍硬编码 |

---

## 十一、失效硬编码汇总（需重新识别）

| 文件 | 失效项 | 建议 |
|---|---|---|
| `AppColors.kt` | `com.tencent.mm.ui.bk` 是 `TopStoryIconViewTipPreference` 的 AnimatorListener，**非主题色工具** | 改用资源读取（微信绿 `#07C160`），或重新定位主题色工具 |
| `WmReflect.kt` | `e01.v1`、`hm0.j1`、`d24.h`、`kn.x` | 见第十章替代 |
| `LabelSyncHook` | `aa3.d` 非 NetScene | 用 `mf3.d`（`"getcontactlabel"`） |
| `TtsVoiceSender` | `dm.c8` | 用 `v61.a1` / `e9.b1()` |
| `GroupFeatures` | `A1`、`yb` 不存在 | 用 `im.c8` 的 `b1`(setContent)/`u1`(setTalker)/`W0`(setCreateTime) |
| `VoiceAutoPlay` | `H0`(getMsgId) 不存在 | 用 `getMsgId()`（未混淆） |
| `ChatGroupHook` | `x93.r` 不存在 | 重定位群标签兜底类 |

---

## 十二、已确认未混淆（相对稳定，可保留硬编码）

- `com.tencent.mm.ui.chatting.ChattingUI` / `ChattingUIFragment` / `BaseChattingUIFragment`
- `com.tencent.mm.ui.LauncherUI`、`com.tencent.mm.ui.conversation.MainUI`
- `com.tencent.mm.modelvoice.MediaRecorder`、`com.tencent.mm.pluginsdk.ui.chat.ChatFooter`
- `com.tencent.mm.protocal.protobuf.SnsObject`
- `com.tencent.mm.plugin.msgquote.model.MsgQuoteItem`
- `com.tencent.mm.plugin.scanner.ImageQBarDataBean`
- `com.tencent.mm.modelsimple.k0`（NetSceneGetA8Key）

---

## 十三、建议锚点汇总表（可直接用于 DexKit 兜底）

| 用途 | 建议锚点字符串 | 命中类 |
|---|---|---|
| 消息实体 | `MicroMsg.MsgInfo` | `com.tencent.mm.storage.e9` |
| 消息存储 | `MicroMsg.MsgInfoStorage` | `com.tencent.mm.storage.f9` |
| 账户存储 | `MicroMsg.AccountStorage` | `b41.e` |
| 自身账号配置 | `MicroMsg.ConfigStorageLogic` | `b41.y1` |
| 群成员逻辑 | `ChatroomMembersLogic` | `b41.u1` |
| 群服务 | `MicroMsg.ChatroomService` | `p02.a` |
| 语音播放器 | `MicroMsg.VoicePlayer` | `v61.j1` |
| 语音内容 | `VoiceContent` | `v61.a1` |
| 语音自动播放 | `MicroMsg.AutoPlay` | `com.tencent.mm.ui.chatting.x0` |
| 发消息 NetScene | `MicroMsg.NetSceneSendMsg` | `v51.r0` |
| 引用消息构造 | `MicroMsg.msgquote.MsgQuoteHelp` | `xp3.i` |
| App 消息 | `MicroMsg.AppMessage` | `dx0.r` / `pluginsdk.model.app.k0` |
| 发文本逻辑 | `SendTextLogic` | `oh0.a` / `oh0.c` |
| 会话列表适配器 | `MicroMsg.ConversationRecyclerAdapter` | `po5.u` |
| 会话数据源 | `MicroMsg.ConversationWithCacheAdapter` | `jo5.e` |
| 标签同步 NetScene | `getcontactlabel` | `mf3.d` |
| 加群成员 NetScene | `MicroMsg.NetSceneAddChatRoomMember` | `qn.m` |
| 朋友圈存储 | `MicroMsg.SnsInfoStorage` | `com.tencent.mm.plugin.sns.storage.l1` |

---

## 十四、后续行动建议

1. **优先接入**：`MicroMsg.MsgInfo`、`MicroMsg.MsgInfoStorage`、`MicroMsg.VoicePlayer`、`MicroMsg.NetSceneSendMsg`、`getcontactlabel`、`MicroMsg.ConversationRecyclerAdapter` 六个锚点，即可覆盖清单约 60% 硬编码。
2. **必改**：`AppColors`（主题色）、`GroupFeatures`（setter 名）、`LabelSyncHook`（`aa3.d`→`mf3.d`）、`TtsVoiceSender`（`dm.c8`→`v61.a1`）、`VoiceAutoPlay`（`H0`→`getMsgId()`）。
3. **可复用**：`b41.u1`（ChatroomMembersLogic）一处接入，可替代 TtsVoiceSender、WmReflect、BatchInvite 等多处群成员硬编码。

---

# 附录 A：第二轮深挖补充结论

> 本附录为针对首轮报告未覆盖/未完全确认项的补充核查结果（微信 8.0.78/3180）。

## A1. 红包插件接口（RedPacketHook）
- ✅ `lj0.a3` = **红包/钱包插件接口**（实现插件生命周期 `onCreate/onAccountInitialized/onAccountReleased`，含红包预览逻辑）
  - 日志锚点：**`"MicroMsg.LuckyMoneyEnvelopePreview"`** / **`"MicroMsg.WalletCoreService"`**
- `ph5.n0` = 插件宿主框架类（管理 `ph5.m/ph5.w/rh5.a`）
- 建议：用 `"MicroMsg.WalletCoreService"` 或 `"LuckyMoneyEnvelopePreview"` 定位红包插件接口。

## A2. 语音播放器方法映射（VoiceAutoPlay）
真实播放器 `v61.j1` 的关键方法（**不是** `n0/getPlayer/p0/o0/k0/I0`）：
| 语义 | 真实方法 |
|---|---|
| startPlay | `j(String, boolean, int)` |
| 播放变体 | `b(String, boolean, int)` / `e(String, boolean)` |
| 状态 | `getStatus()I`、`isPlaying()Z` |
| 控制 | `pause()Z`、`resume()Z`、`stop()Z`、`seek(J)Z`、`setMute(Z)V` |

## A3. 群成员 / 群信息相关（WmReflect、TtsVoiceSender）
- ✅ `com.tencent.mm.storage.y3` = **ChatroomMemberInfo**（群成员实体，大量 `X4(String)Z` 成员标志 + `q0()String`/`P0()String`）
- ✅ `com.tencent.mm.storage.i4` = **ChatroomMemberStorage**（`get(String)→storage.y3`）
- ✅ `b41.u1` = ChatroomMembersLogic（`"ChatroomMembersLogic"`）
- ❌ `kn.x`、`x93.r`、`p06` 本版本均不存在（失效）。

## A4. 联系人存储逻辑（StorageHub / ContactRepository）
- ✅ `b41.d2` = **ContactStorageLogic**（`"MicroMsg.ContactStorageLogic"`，处理 `@chatroom/@app/@openim` 等用户名分类）
- `b41.h9` = 存储管理器（方法返回 `b41.e`=AccountStorage、`b41.c8`、`storage.i3`），疑为联系人存储定位器
- `b41.e` = AccountStorage（`"MicroMsg.AccountStorage"`）

## A5. SnsInfo 存储修正（MomentsFakeLikeHook）
- ⚠️ `com.tencent.mm.plugin.sns.storage.l1` 实为 **MergeInfoStorage**（引用 `"com.tencent.mm.plugin.sns.storage.MergeInfoStorage"`）
- 真正的 SnsInfoStorage 是 `com.tencent.mm.plugin.sns.storage.f2/g2/i2`（`"MicroMsg.SnsInfoStorage"`）
- 建议：若需 SnsInfo 增删查，锚点用 **`"MicroMsg.SnsInfoStorage"`**（命中 `f2/g2/i2`）。

## A6. 其他补充
- ✅ `t73.n` = **FTSApiLogic**（`"MicroMsg.FTS.FTSApiLogic"`，处理 `wxid_/gh_/wx_` 前缀、MD5、startFTSActivity）→ BatchInviteManager 可用 `"MicroMsg.FTS.FTSApiLogic"` 覆盖。
- ✅ `com.tencent.mm.storage.f9.N3(String,J)` 存在（= getMsg 类方法），`Bb`=insert。
- ❌ `com.tencent.mm.model.k0`、`com.tencent.mm.k0`、`p06`、`x93.r`、`kn.x`、`dm.c8`、`e01.v1`、`hm0.j1`、`d24.h`、`aa3.d`（作 NetScene 用）在 8.0.78 均不存在。

---

# 附录 B：仍未定位 / 未完全分析（剩余缺口）

| # | 项 | 状态 | 说明 |
|---|---|---|---|
| 1 | AppColors 主题色工具类 | ❌ | `com.tencent.mm.ui.bk`=AnimatorListener（失效）。真实主题色工具未定位；建议改用资源读取（微信绿 `#07C160`）或重新定位。 |
| 2 | `com.tencent.mm.app.k0` 用途 | ⚠️ | 存在但仅 `<init>(app.i0)`，疑似 app 级服务定位器；二级/三级兜底 `model.k0`、`k0` 已失效。 |
| 3 | 语音播放器反射枚举改写 | ⚠️ | 需把 `n0/getPlayer/p0/o0/k0/I0` 改为直接调 `v61.j1` 的 `j/pause/resume/stop/isPlaying/getStatus`。 |
| 4 | `ph5.n0` 取红包插件实例的调用链 | ⚠️ | `lj0.a3` 已定位为插件接口，但 `ph5.n0` 具体调用方式未完全拆解。 |
| 5 | TtsVoiceSender 通用消息 XML 存储 | ❌ | `dm.c8` 失效；语音 XML 用 `v61.a1` 解析，通用 XML 存储未定位。 |
| 6 | ChatGroupHook 群标签兜底类 | ❌ | `x93.r` 失效，替代类未定位。 |
| 7 | WmChatHook `p06` 锚点实际类 | ❌ | 本版本无 `p06`，`getP06ClassName` 返回的类需重确认。 |
| 8 | WmReflect 群信息实体 | ❌ | 群成员实体 `storage.y3` 已定位，但"群信息"实体类未确认。 |
| 9 | `l1` 锚点修正 | ⚠️ | `l1`=MergeInfoStorage，需用 `"MergeInfoStorage"` 或按 SnsInfo 类型定位，勿用 `"MicroMsg.SnsInfoStorage"`。 |
| 10 | `b41.h9` 精确日志身份 | ⚠️ | `view_strings` 对该类反复失败；已确认其返回 `b41.e`/`b41.c8`/`storage.i3`。 |
| 11 | `vf0.e` | ⚠️ | 空类/标记接口（无方法）。 |
| 12 | `com.tencent.mm.ui.conversation.s5` | ✅ | 小型 invoke/lambda 回调，非主逻辑。 |

---

# 附录 C：剩余缺口深挖最终结论（12 项全部定位/给出替代）

> 本轮读取了项目源码（LeShaoWeChatV3-源码-v30257.zip）逐处核对实际调用，再回 APK 反编译定位真实类，完成二次核查。

## C1. AppColors 主题色/深色模式 ⭐（原第1缺口，已解决）
- AppColors.kt 实际调用：`XposedHelpers.callStaticMethod(bk, "C")` 期望返回 **Boolean**，用途是**深色模式检测**（非取色 int）。
- 真实类：**`com.tencent.mm.ui.gk`**（日志 `"MicroMsg.UIUtils"`）
- 真实方法：**`public static boolean D()`** —— 读取 `dark_mode` / `dark_mode_follow_system` 配置判深色模式。
  - `D()` 依赖 `J()`（`dark_mode_follow_system`）与 `F/G/H`；`K()` = `isDarkModeOn`（`clicfg_dark_mode_on`）。
- **改法**：`com.tencent.mm.ui.bk` 的 `C()` → `com.tencent.mm.ui.gk` 的 `D()`。
- **CornerMenu** 的 `bk/bl/bj/bi` 候选列表需加入 `gk`。
- **建议锚点**：查找引用字符串 **`"dark_mode_follow_system"`** 或 **`"dark_mode"`** 的静态 boolean 方法所在类。

## C2. VoiceAutoPlay `com.tencent.mm.app.k0`
- 存在：`com.tencent.mm.app.k0` 继承 `ph5.y`（插件框架基类）。
- 源码核对：`sK0Class` 仅用于初始化日志，**实际已不参与逻辑（死代码）**；`com.tencent.mm.model.k0`、`com.tencent.mm.k0` 确认不存在。可直接删除该三级兜底。

## C3. 语音播放器方法映射（反射枚举改写）
| 语义 | 真实方法（v61.j1） |
|---|---|
| startPlay | `j(String, boolean, int)` |
| 播放变体 | `b(String, boolean, int)` / `e(String, boolean)` |
| 状态 | `getStatus()I`、`isPlaying()Z` |
| 控制 | `pause()Z`、`resume()Z`、`stop()Z`、`seek(J)Z`、`setMute(Z)V` |

代码里的 `n0/getPlayer/N0/getVoicePlayer/p0/o0/k0/I0` 在 `v61.j1` 上不存在，应删除并直接调上述方法。

## C4. RedPacketHook
- `lj0.a3` = **红包/钱包插件接口**（插件生命周期 + 红包预览），锚点 `"MicroMsg.WalletCoreService"` / `"MicroMsg.LuckyMoneyEnvelopePreview"`。
- `ph5.n0` = 插件宿主框架（`c(Class)`→`ph5.m`，`j(...)` 管理插件）。
- 取红包插件实例：用 `ph5.n0` 框架 API 按插件类加载。

## C5. TtsVoiceSender `dm.c8`（消息 XML / setType）
- `dm.c8` 已失效；源码注释证实主路径为 **`e9.setType(int)`**（继承自 `im.c8`）。
- 改法：`dm.c8` 兜底可删除或改为 `im.c8`（`setType(I)V`）。

## C6. ChatGroupHook `x93.r`（群标签 Provider）⭐
- 真实类：**`jf3.z`**，静态方法 **`bj()`** 返回 `com.tencent.mm.storage.g4`（标签存储）。
- 源码 3180 主路径已用 `jf3.z.bj()`；`x93.r.hj()` 兜底应改为 `jf3.z.bj()`。

## C7. WmChatHook `p06`
- 源码注释实证：**"P06 即扁平包内核单例 `gp0.j1`"**。
- `gp0.j1` 方法 `b()` 为 hook 目标；`p06` 硬编码可删除。

## C8. WmReflect `kn.x` / `hm0.j1`（群邀请）
- 邀请请求：真实类 = **`qn.m`**（NetSceneAddChatRoomMember，cgi `addchatroommember`，构造 `(String, List, String, Object)`）。
- `hm0.j1`（群服务单例，静态 `d()`）→ 建议用 **`un.k.get()`→`pe5.f`**（ChatRoom API）或 `p02.a`（ChatroomService）。
- ⚠️ 注意：WmReflect 构造 `(room, members, 0, null)` 的 int 参数与 `qn.m` 的 String 参数不匹配，反射参数需调整。

## C9. `l1` 锚点修正
- `com.tencent.mm.plugin.sns.storage.l1` = **MergeInfoStorage**（引用 `"MergeInfoStorage"`）。
- 真 SnsInfoStorage = `com.tencent.mm.plugin.sns.storage.f2/g2/i2`（`"MicroMsg.SnsInfoStorage"`）。

## C10. `b41.h9` 精确身份
- = **联系人存储定位器**：`d()`→self、`b()`→`b41.e`（AccountStorage）、`c()`→`b41.c8`、`g()`→`storage.i3`。
- ContactRepository 用 `h9.d().b().r()` 直连存储实例。
- ⚠️ `view_strings` 对该类反复失败（DEX 段问题），用方法签名定位即可。

## C11. `vf0.e`
- 继承 `ph5.m`、实现 `qs.n`，是**插件入口类**（自身无方法）。BatchInviteManager 可忽略或按插件处理。

## C12. `com.tencent.mm.ui.conversation.s5`
- = 会话列表「折叠/刷新」回调（由 `com.tencent.mm.ui.conversation.t5` 创建，`invoke()` 处理 `message_fold` 逻辑）。
- 非 UI 注入点，而是列表更新回调；ChatGroupUiInjector 用它做兜底需谨慎。

---

# 附录 D：二次核查确认表（关键锚点复验）

| 锚点/类 | 二次验证结果 |
|---|---|
| `com.tencent.mm.ui.gk.D()` | ✅ 静态 `boolean`，判深色模式（读 `dark_mode`/`dark_mode_follow_system`） |
| `v61.j1.j(String,Z,I)` | ✅ = startPlay（日志 `MicroMsg.VoicePlayer`） |
| `qn.m` | ✅ extends `m1`，cgi `/cgi-bin/micromsg-bin/addchatroommember` = NetSceneAddChatRoomMember |
| `jf3.z.bj()` | ✅ 返回 `com.tencent.mm.storage.g4` = 标签存储 Provider |
| `gp0.j1.b()` | ✅ P06 真实类（源码注释实证） |
| `b41.e` | ✅ = AccountStorage（`"MicroMsg.AccountStorage"`） |
| `p02.a` | ✅ = ChatroomService（`"MicroMsg.ChatroomService"`） |
| `com.tencent.mm.plugin.sns.storage.l1` | ⚠️ = MergeInfoStorage，非 SnsInfoStorage |
| `ph5.n0` | ✅ 插件宿主框架（继承链 `ph5.y`） |
| `vf0.e` | ✅ 插件入口（继承 `ph5.m`，实现 `qs.n`） |

---

# 附录 E：建议代码改动清单（速改）

1. `AppColors.kt` / `CornerMenu.kt`：`com.tencent.mm.ui.bk` → `com.tencent.mm.ui.gk`，方法 `C` → `D`（候选类列表加 `gk`）。
2. `VoiceAutoPlay.java`：删除 `com.tencent.mm.app.k0/model.k0/k0` 三级兜底（死代码）；反射枚举改调 `v61.j1` 的 `j/pause/resume/stop/isPlaying/getStatus`。
3. `ChatGroupHook.java`：`x93.r.hj()` → `jf3.z.bj()`。
4. `TtsVoiceSender.java`：`dm.c8` 兜底删除或改 `im.c8`；`setType` 主路径用 `e9.setType`。
5. `WmChatHook.java`：`p06` 硬编码删除，统一用 `gp0.j1` / `getP06ClassName()`。
6. `WmReflect.kt`：`kn.x` → `qn.m`；`hm0.j1` → `un.k.get()`→`pe5.f`（并调整构造参数类型）。
7. `MomentsFakeLikeHook.java`：`l1` 按 MergeInfoStorage 处理，或改用 `f2/g2/i2`（SnsInfoStorage）。
8. `BatchInviteManager.java`：`vf0.e` 按插件入口处理；`t73.n` 已确认 = FTSApiLogic。

---

# 附录 F：任务1 — 深色模式检测权威方案（独立 Xposed 模块，非 BSH）

## F1. 权威事实（已反编译核实）
- 微信深色模式工具类：**`com.tencent.mm.ui.gk`**（日志 `"MicroMsg.UIUtils"`）
- 关键静态方法（全部 `public static boolean`，无参）：
  - **`D()`** = 完整深色模式检测（读 `dark_mode`，组合 `J()`/`F`/`G`/`H`）← **推荐调用**
  - `J()` = 是否跟随系统（读 `dark_mode_follow_system`）
  - `M()` = 是否使用过深色（读 `dark_mode_used`）
  - `K()` = `isDarkModeOn`（读 `clicfg_dark_mode_on`）

## F2. DexKit 锚点（已核验唯一性）
- 锚点字符串：**`"dark_mode_used"`**
  - 全 DEX 仅 3 个类引用：`com.tencent.mm.ui.gk`、`com.tencent.mm.ui.HomeUI$9`、`wz.q4`
  - 其中**静态 boolean 方法**只有 **`gk.M()`** → 唯一命中，声明类 = `com.tencent.mm.ui.gk`
- 备选锚点：`"dark_mode_follow_system"`（6 个类，静态 boolean 方法唯一 = `gk.J()`）

## F3. 独立模块 Java 代码示例（非 BSH；按你的 DexKit 版本适配）
```java
import java.lang.reflect.Modifier;
import org.a.dexkit.DexKitBridge;          // 依你的 DexKit 版本调整 import
import org.a.dexkit.entity.MethodData;
import org.a.dexkit.entity.MethodPredicate;
import de.robv.android.xposed.XposedHelpers;

/** 定位微信深色模式类，返回 com.tencent.mm.ui.gk */
public static String findDarkModeClass() {
    // 用你的 DexKitHelper 传入 APK path / 复用缓存 bridge
    try (DexKitBridge bridge = DexKitBridge.create(apkPath, null, 0, 0)) {
        for (MethodData md : bridge.findMethod(new MethodPredicate() {
            @Override public boolean invoke(MethodData d) {
                return (d.getModifiers() & Modifier.STATIC) != 0
                        && "Z".equals(d.getMethodReturnType())
                        && d.getClassName().startsWith("com.tencent.mm.ui.")
                        && d.getUsingStrings().contains("dark_mode_used");
            }
        })) {
            return md.getClassName();          // = com.tencent.mm.ui.gk
        }
    } catch (Throwable ignored) {}
    return "com.tencent.mm.ui.gk";            // 兜底硬编码
}

/** 调用：微信当前是否深色模式 */
public static boolean isWeChatDark(ClassLoader cl) {
    Class<?> c = XposedHelpers.findClass(findDarkModeClass(), cl);
    return (Boolean) XposedHelpers.callStaticMethod(c, "D");  // 权威方法 = D()
}
```
要点：
- 用 `"dark_mode_used"` 锚点 + `static` + 返回 `Z` + `com.tencent.mm.ui.` 前缀过滤，唯一命中 `gk`。
- **调用 `D()`**（完整深色检测），不要用 `M()`/`J()`。
- 兼容硬编码兜底 `"com.tencent.mm.ui.gk"`。
- `AppColors.kt` / `CornerMenu.kt` 中的 `bk/bl/bj/bi` 候选列表需替换为 `gk`（或加入 `gk` 优先）。

---

# 附录 G：任务2 — 群邀请权威调用链（3180 实证）

## G1. 权威事实（已从源码 joinGroupViaApi + 反编译双向核实）
3180 加群/添加群成员的真实链路（不再有 `kn.x` / `hm0.j1`）：
```
un.k 实例.get()                    → pe5.f       (ChatRoom API 接口)
b41.y1.u()  (static)              → selfWxid（配置 key 2）
pe5.f.j(String room, List members, String ticket, Object obj)
                                    → com.tencent.mm.roomsdk.model.factory.a  (任务)
factory.a.a()                     → 发送 addchatroommember (Cgi 120)
```
对应类已确认：
| 环节 | 真实类 | 方法 |
|---|---|---|
| ChatRoom API 工厂 | `un.k` | `get()` → `pe5.f` |
| ChatRoom API 接口 | `pe5.f` | `j(String,List,String,Object)` → `factory.a` |
| 接口实现 | `ln.a` | 同 `pe5.f` 签名 |
| 任务执行器 | `com.tencent.mm.roomsdk.model.factory.a` | `a()` 发送 |
| selfWxid | `b41.y1` | `u()` static |
| NetScene（实际请求） | `qn.m` | NetSceneAddChatRoomMember，cgi `addchatroommember` |
| 加群处理入口 | `vn.m` | `b(String,int)`（ChatRoomAddContactProcess） |

## G2. 独立模块 Java 代码（替代 WmReflect.inviteMembers）
```java
public static boolean inviteMembers(ClassLoader cl, String room, String ticket) {
    try {
        // 1) 获取 ChatRoom API（pe5.f）
        Object api = XposedHelpers.callMethod(
                XposedHelpers.newInstance(XposedHelpers.findClass("un.k", cl)), "get");
        if (api == null) return false;
        // 2) 自己的 wxid
        String self = (String) XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("b41.y1", cl), "u");
        if (self == null) return false;
        // 3) 构造加群任务（成员 = [自己]）
        Object task = XposedHelpers.callMethod(
                api, "j", room, java.util.Collections.singletonList(self), ticket, null);
        if (task == null) return false;
        // 4) 发送 addchatroommember
        XposedHelpers.callMethod(task, "a");
        return true;
    } catch (Throwable ignored) { return false; }
}
```
⚠️ 旧代码 `kn.x`（构造 `(room,members,0,null)`）与 `hm0.j1.d()` 均已失效；`qn.m` 构造为 `(String,List,String,Object)`，参数类型与旧反射不一致，勿再直接 newInstance 旧签名。

---

# 附录 H：全量权威数据总表（重审最终版）

| 用途 | 权威类 | 权威方法 | 锚点/日志 |
|---|---|---|---|
| 深色模式 | `com.tencent.mm.ui.gk` | `D()` static | `"dark_mode_used"` / `"MicroMsg.UIUtils"` |
| 消息实体 | `com.tencent.mm.storage.e9`（基类 `im.c8`） | `N0()`=getTalker、`getMsgId()`、`b1()`=setContent、`u1()`=setTalker、`W0(J)`=setCreateTime、`setType(I)` | `"MicroMsg.MsgInfo"` |
| 消息存储 | `com.tencent.mm.storage.f9` | `Bb(e9,Z)`=insert、`N3(String,J)`=get | `"MicroMsg.MsgInfoStorage"` |
| 账户存储 | `b41.e` | — | `"MicroMsg.AccountStorage"` |
| selfWxid | `b41.y1` | `u()` static、`c()/b()` | `"MicroMsg.ConfigStorageLogic"` |
| 群成员逻辑 | `b41.u1` | — | `"ChatroomMembersLogic"` |
| 群服务 | `p02.a` | — | `"MicroMsg.ChatroomService"` |
| 语音播放器 | `v61.j1` | `j(String,Z,I)`=startPlay、`pause/resume/stop/isPlaying/getStatus` | `"MicroMsg.VoicePlayer"` |
| 语音内容 | `v61.a1` | — | `"VoiceContent"` |
| 语音自动播放 | `com.tencent.mm.ui.chatting.x0` | `q(e9)` | `"MicroMsg.AutoPlay"` |
| 发消息 NetScene | `v51.r0` | `doScene` | `"MicroMsg.NetSceneSendMsg"` |
| 引用消息构造 | `xp3.i` | — | `"MicroMsg.msgquote.MsgQuoteHelp"` |
| App 消息 | `dx0.r` / `pluginsdk.model.app.k0` | — | `"MicroMsg.AppMessage"` |
| 会话适配器 | `po5.u` | — | `"MicroMsg.ConversationRecyclerAdapter"` |
| 会话数据源 | `jo5.e` | `getConvList` | `"MicroMsg.ConversationWithCacheAdapter"` |
| 标签同步 NetScene | `mf3.d` | `doScene/getType` | `"getcontactlabel"` |
| 标签存储 Provider | `jf3.z` | `bj()` → `storage.g4` | 返回类型 `com.tencent.mm.storage.g4` |
| P06 内核单例 | `gp0.j1` | `b()` | `"getP06ClassName"` |
| 红包插件接口 | `lj0.a3` | 插件生命周期 | `"MicroMsg.WalletCoreService"` / `"LuckyMoneyEnvelopePreview"` |
| ChatRoom API | `un.k`→`pe5.f`（实现 `ln.a`） | `get()`、`j(...)` | `"addchatroommember"` |
| 加群任务执行 | `com.tencent.mm.roomsdk.model.factory.a` | `a()` | — |
| 加群 NetScene | `qn.m` | `<init>(String,List,String,Object)` | `"MicroMsg.NetSceneAddChatRoomMember"` |
| 加群处理进程 | `vn.m` | `b(String,int)` | — |
| 扫码识别回调 | `wk5.n`/`wk5.o` | — | `"RecogQBarOfImageFileResultEvent"` |
| 朋友圈存储 | `com.tencent.mm.plugin.sns.storage.f2/g2/i2` | — | `"MicroMsg.SnsInfoStorage"` |
| SnsInfo 合并存储 | `com.tencent.mm.plugin.sns.storage.l1` | — | `"MergeInfoStorage"` |
| 收藏 UI | `com.tencent.mm.plugin.fav.ui.fc` | — | — |
| 群成员实体 | `com.tencent.mm.storage.y3` | — | — |
| 群成员存储 | `com.tencent.mm.storage.i4` | `get(String)→y3` | — |
| FTS API | `t73.n` | — | `"MicroMsg.FTS.FTSApiLogic"` |

---

# 附录 I：最终交付 — 遗漏修正 + 全清单逐项权威结论（一次性）

> 本附录为最终权威交付，含本轮新发现的**关键遗漏修正**（NetSceneQueue 真实类），以及**原始清单逐项→结论**的完整对照，确保无漏项。

## I1. 关键遗漏修正：NetSceneQueue 真实类（LabelSyncHook / WeChatMessenger 入队）

- ❌ 你的 LabelSyncHook 用 `com.tencent.mm.model.bb` + `getInstance()` —— **3180 不存在**。
- ✅ 真实 NetSceneQueue = **`com.tencent.mm.modelbase.r1`**（日志 `"MicroMsg.NetSceneQueue"`）。
- 入队方法：**`h(com.tencent.mm.modelbase.m1, I)Z`**（NetScene 继承 `m1`）。
- **获取单例权威链**（模块内已验证）：`gp0.j1.q()` → `gp0.y` 实例 → **字段 `b`** = NetSceneQueue。
```java
Object queue = XposedHelpers.getObjectField(
        XposedHelpers.callMethod(XposedHelpers.findClass("gp0.j1", cl), "q"), "b");
XposedHelpers.callMethod(queue, "h", req, 0);   // 入队触发 CGI
```
- 已有正确用法：`FavVoiceForwardHook.C_NETSCENE_QUEUE = "com.tencent.mm.modelbase.r1"`；`StorageHub.get().netSceneQueue()`。

## I2. GroupFeatures 权威 setter（3180）

- 主路径（模块注释已实证）：`e9` 上 `b1(content)`、`u1(talker)`、`e1(createTime)`、`setType(int)`。
- 兜底 `X0(text)`（=setBizChatUserId，**非 content**）、`L1(long)`（=getCreateTime 无参，**带参不存在**）、`A1(1)`、`f9.yb(msg,0)`（**不存在**）在 3180 均为失效/错误兜底。
- 正确入库：`f9.Bb(e9, boolean)`（insert）。

## I3. WeChatMessenger MsgQuoteItem / 引用消息 权威方法映射

被引用消息对象 = `com.tencent.mm.storage.e9`（MsgInfo），其方法/字段已核实：
| 模块调用 | 真实语义 | 权威位置 |
|---|---|---|
| `getType()` | 消息类型 | `e9.getType()I` |
| `F0()` | getMsgSvrId（long） | `im.c8.F0()` → `field_msgSvrId` |
| `N0()` | getTalker（String） | `im.c8.N0()` |
| `E0()` | src（String，`this.G`） | `im.c8.E0()` |
| `N1()` | getContent（解析后展示内容） | `e9.N1()` |
| `j()` | getContent / getDBContent（原始 XML） | `e9.j()` |
| `getCreateTime()` | 时间 | `im.c8.getCreateTime()` |
| 字段 `A2` | scene（int） | `e9.A2:I` |
| `ou5.c1.d(e9)` | qFrom | `ou5.c1.d(e9)String` |
| `ou5.c1.f(src,atMap,1)` | 拼接 at 内容 | `ou5.c1.f(String,Map,int)String` |
| `xp3.i.e(e9,room)` | qName | `xp3.i.e(e9,String)String` |
| `k0.c(type)` | type→appId 映射 | `pluginsdk.model.app.k0.c(I)I` |
| `k0.I(...)` | 发送返回 Pair | `k0.I(dx0.r,...)` |
| `vp3.e.ej()` | 引用关系 DAO | `vp3.e.ej() → yp3.a` |
| `yp3.a.x1(rel)` | 插入引用关系 | `yp3.a.x1(Lyp3/b;)Z` |

## I4. 原始清单 → 权威结论 全对照（逐项）

| # | 清单项 | 权威结论 |
|---|---|---|
| 1 | AppColors `ui.bk.C()` | ❌ 失效 → **`com.tencent.mm.ui.gk.D()`**（静态 boolean 深色模式） |
| 2a | VoiceAutoPlay `app.k0/model.k0/k0` | `app.k0` 存在（死代码）；后两者不存在 → 可删 |
| 2b | `chatting.component.so` | 是 `Runnable`（非播放器） |
| 2c | `chatting.x0` | ✅ AutoPlay（`MicroMsg.AutoPlay`） |
| 2d | `storage.e9` / N0/H0/M0 | e9=MsgInfo；`N0`=getTalker（im.c8）；`H0` 不存在→`getMsgId()`；`M0` 在 im.c8 |
| 2e | 语音播放器 | `v61.j1`（`MicroMsg.VoicePlayer`），`j`=startPlay |
| 3 | ConversationFilter `jo5.f/po5.u` | `jo5.e`=ConvWithCacheAdapter；`po5.u`=ConvRecyclerAdapter |
| 4 | LabelSyncHook `aa3.d` | ❌ 枚举 → **`mf3.d`**（getcontactlabel NetScene） |
| 5 | GroupFeatures `f9` X0/L1/A1/yb | e9 用 `b1/u1/e1/setType`；`f9.yb` 不存在→`f9.Bb` |
| 6 | WeChatMessenger 多类 | 全部定位（见 I3 及正文） |
| 7 | AutoGroupQrHook | s6/ImageQBarDataBean/un.k/pe5.f/ln.a/qn.m/vn.m/factory.c/modelsimple.k0/b41.y1/ph5.n0/rn3.u0/pe3.a/ex0.k0/wk5.n/o 全部定位 |
| 8 | RedPacketHook `ph5.n0/lj0.a3` | `lj0.a3`=红包/钱包插件接口；`ph5.n0`=插件宿主框架 |
| 9 | TtsVoiceSender `dm.c8/b31.w/f9` | `dm.c8` 失效→`v61.a1`/`e9`；`b31.w`→`b41.u1`；`f9`=MsgInfoStorage |
| 10 | WmReflect `e01.v1/hm0.j1/d24.h/kn.x` | 均失效；替代 `jo5.e`/`p02.a`/`b41.u1`/`qn.m`+`pe5.f` |
| 11 | WmChatHook `f9/gp0.j1/p06` | `f9`=MsgInfoStorage；`gp0.j1`=P06 内核单例 |
| 12 | StorageHub `b41.h9/e/y1/f9` | `b41.h9`=存储定位器、`b41.e`=AccountStorage、`b41.y1`=ConfigStorageLogic、`f9`=MsgInfoStorage |
| 13 | ContactRepository `b41.h9` | 存储定位器（`d().b().r()`） |
| 14 | BatchInvite `qe5.b/b41.y1/s1/t73.n/vf0.e` | `qe5.b`=回调；`t73.n`=FTSApiLogic；`vf0.e`=插件入口 |
| 15 | ChatGroupHook `x93.r/c4` | `x93.r` 失效→**`jf3.z.bj()`**；`c4`=标签实体 |
| 16 | MsgForgeHook `f9` | MsgInfoStorage（`Bb/Db/Hb`） |
| 17 | FavVoiceForwardHook `fav.ui.fc` | ✅ 存在（FavoriteIndexUI 长按监听） |
| 18 | MomentsFakeLikeHook `SnsObject/l1` | `SnsObject` 存在；`l1`=MergeInfoStorage（`l1.b(localId)`→SnsInfo） |
| 19 | ChatGroupUiInjector `s5/MainUI` | `s5`=会话折叠回调（`invoke`，`h` 方法不存在）；`MainUI` 未混淆 |
| 三 | 稳定未混淆类 | 全部确认存在 |
| 四 | 方法 N0/H0/F0/M0/I0/Bb/N3/X0/L1/A1/yb/n0/getPlayer... | 见 I3/正文：`N0`=getTalker、`getMsgId()` 未混淆、`F0`=getMsgSvrId、`Bb`=insert、`N3`=getMsg、`X0`=setBizChatUserId、`L1`=getCreateTime；`H0/A1/yb` 失效 |

## I5. 新增权威锚点（本次交付补充）

| 用途 | 锚点/类 |
|---|---|
| NetSceneQueue | `com.tencent.mm.modelbase.r1`（`MicroMsg.NetSceneQueue`），`h(m1,int)` |
| 深色模式 | `com.tencent.mm.ui.gk`，`D()`（锚点 `dark_mode_used`） |
| 标签存储 Provider | `jf3.z.bj()` → `storage.g4` |
| 群邀请任务 | `un.k.get()`→`pe5.f.j(...)`→`factory.a.a()` |
| P06 | `gp0.j1`（`b()`） |
| 消息内容 | `e9.N1()`/`e9.j()`、`e9.F0()`/`N0()`/`E0()`/字段`A2` |

## I6. 结论
原始清单中**所有硬编码项均已给出 3180 权威结论**（定位真实类或标注失效+替代）。关键失效集中在：`ui.bk`→`ui.gk.D()`、`aa3.d`→`mf3.d`、`model.bb`→`modelbase.r1`、`dm.c8`→`v61.a1`、`x93.r`→`jf3.z`、`kn.x/hm0.j1`→`qn.m/pe5.f`、`p06`→`gp0.j1`、`f9.yb`→`f9.Bb`。
