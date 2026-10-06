# WeChat 3180 反编译锚点 · 地毯式深度审查报告

- 目标包：`com.tencent.mm`（微信 3180）
- 方法：DexKit 定位 + jadx/smali 取证（类头、字段、构造器、方法体逐条核对）
- 配套独立 Xposed 模块使用；本文不含 BSH 代码，仅交付**精确类名 / 签名 / Hook 落点 / 风险**

---

## 0. 命名与包结构关键点（先读，否则 Hook 会全空）

3180 把大量类**压平到 dex 根级短包**，其字节码描述符**不带 `com/tencent` 前缀**。
对比证据：
- `e9` 方法描述符 = `Lcom/tencent/mm/storage/e9;->D1(J)V`（正常子包，带全路径）
- `a21.q` 类描述符 = `La21/q;`、`v51.r0` = `Lv51/r0;`、`kw5.y0` = `Lkw5/y0;`（**根级扁平包**）

> 结论：凡扁平包类，`Class.forName` / Xposed Hook **直接用短名**：
> `v51.r0` ✅ ｜ `com.tencent.mm.v51.r0` ❌
> 正常子包类仍用全路径：`com.tencent.mm.storage.e9` ✅

扁平包样例：`a21 / kw5 / sw5 / v51 / pc5 / gp0 / ah2 / e10 / kb0 / x51 / ...`

---

## 1. TTS 锚点

### 1.1 `com.tencent.mm.storage.e9` = `MicroMsg.MsgInfo`（存在）

- **`d1` 的真实方法是 `D1(long) → void`（唯一，无小写 `d1`；该类小写 d 方法仅 `d2()/d3()`）**

```java
// Lstorage/e9;->D1(J)V
public static void D1(long j) {
    a.f("msgId: " + j + " not in the reasonable scope",
        1500000001L > j && -10L < j);
}
```
语义：msgId 合理性断言，**不是 TTS 逻辑**。

- **`e9` 不含字符串 `"voicemsg"`**（`find_usage("voicemsg")` 命中集无 `e9`；`e9` 的语音判段在 `b1()` 用 `MicroMsg.VoiceContent` / `VoiceContent parse failed.` 等，无该字面量）。

  真正含 `"voicemsg"` 的类/方法：
  `dl.f0.a`、`ks1.h.a`、`ks1.h.c`、`sp3.i.<init>`、`t44.x5.onCreate`、`v61.f1.k`、`x95.a.<init>`、`x95.b.<init>`

  → 若 TTS 逻辑在 `e9` 内找 `voicemsg`，**锚点错**，需改判到上述 VoiceContent 相关类。

### 1.2 `a21` = 影视/时间线剪辑扁平包（**与 MsgInfo/TTS 无关，疑似选错分支**）

- 类头：`.class public final La21/q; .super Landroidx/recyclerview/widget/p2;`（RecyclerView.holder）
- 字段引用 `com.tencent.mm.mj_publisher.finder.widgets.timelineview.BaseTimelineView`、`MJID`、`PointF×3`、`RectF×2` → 电影/时间线编辑器。

`a21` 同包候选（扁平包 `a21.*`）：

| 类 | 关键字段 / 角色 |
|---|---|
| **`a21.q`** | **真数据模型（38 字段）**：`Context`、`BaseTimelineView`、`PointF×3`、`RectF×2`、`MJID×2`、多个 boolean/float；且是 RecyclerView holder |
| `a21.l` | 仅 `a: a21/q` |
| `a21.m` | 仅 `d: a21/q` |
| `a21.n` | 仅 `d: a21/q` |
| **`a21.o`** | 仅 `d: a21/q`；**构造 `o(<init>(a21/q))` + `invoke()` → kotlin FunctionReference（回调），非数据持有者** |
| `a21.p` | 仅 `d: a21/q` |
| `a21.b`/`a21.g`/`a21.k` | 链表节点（自引用 d/e + `自身[]` 数组 f） |
| `a21.c` | 4 字段节点（d/e/f 自引用 + 数组 g） |
| `a21.d` | `{ a21/a, t01/h, boolean, a21/b }` |
| `a21.a`/`e`/`f`/`h`/`i`/`j` | 空（标记/接口） |

**结论**：要读/存字段→对象应指向 **`a21.q`**；`a21.o` 仅是“收一个 `a21.q` 后 invoke”的函数引用。且整个 `a21` 与 MsgInfo/语音转写不同功能，请核对你是否选错包。

---

## 2. 长按菜单 B/C 层（`com.tencent.mm.ui.chatting.viewitems`，三条全部存在且为顶层类）

> `.class public Lcom/tencent/mm/ui/chatting/viewitems/r0;`（无 `$`），可直接以全路径 Hook。

| 层 | 完整类名 | 类声明 | 方法签名 | 角色 |
|---|---|---|---|---|
| A/建菜单 | `...viewitems.o0` | `.super java.lang.Object;` `.implements Lkj5/p4;` | `a(kj5.i4, android.view.View, ContextMenu$ContextMenuInfo)→void` | 构建/弹出长按菜单 |
| **B/点项** | `...viewitems.r0` | `.super java.lang.Object;` | **`onMMMenuItemSelected(android.view.MenuItem, int)→void`** | 菜单项选中 |
| **C/气泡控件** | `...viewitems.m0` | `.super java.lang.Object;` `.implements android.view.View$OnLongClickListener;` | **`g(android.view.View)→void`**；另有 `onLongClick(View)→boolean`、动作 `a/b/c/d/e/f(...)`（带 `storage.e9`） | 每类消息项长按控制核心 |

**`m0.g(View)` 方法体要点（483 行）**：`View.getTag()`→`viewitems.ps`（item 承载），TAG=`MicroMsg.ChattingItem`，空 tag 时 `Log.w("open menu but tag is null")`；随后 `r0.d = ps` 绑定当前项，构造 `eu5/s0`（菜单 PopupWindow），读 tag id `0x7f0a6814`，按 `si5/b` 实验开 flag，挂 `PopupWindow$OnDismissListener(viewitems.q0)`。

**绑定闭环**：`o0(viewitems.m0, viewitems.b0, gk5/d)`、`r0(viewitems.m0)`、`m0(viewitems.b0, gk5.d)`；`m0.g` 里 `r0.d=当前item`，`r0.onMMMenuItemSelected` 即回取该项。
(`b0`=ChattingItem 基类，`gk5/d`=adapter，`kj5/i4`=菜单封装，`ps`=item tag)

**Hook 落点建议**：A=`viewitems.o0.a`；B=`viewitems.r0.onMMMenuItemSelected`；C=`viewitems.m0.g` / `m0.onLongClick`。
（勿与 AppBrand 私聊组 `ui.chatting.o0/r0/m0`、Finder 的 `si2.r0.onMMMenuItemSelected` 混淆，签名不同。）

---

## 3. 评论区广告数据层

**数据类 `com.tencent.mm.protocal.protobuf.FinderCommentInfo`（存在）**。判定字段：

```java
FinderCommentInfo.getAdvertisement_info() -> pc5.i01   // ★ isAd 主锚点：!= null 即广告评论
FinderCommentInfo.getPromotion_info()    -> pc5.o01     // 次
FinderCommentInfo.getHelp_promotion_button() -> pc5.j01
// 辅助：getComment_type()/getExtFlag()/getDisplayFlag()
```

**`pc5.i01`（`advertisement_info`，`.super com/tencent/mm/protobuf/e`，新式 pb 动态表）10 个字段**：
`1 jump_info(FinderJumpInfo)`、`2 aid`、`3 uxinfo`、`4 report_extra_data`、`5 report_byp_data`、`7 account_jump_info(FinderJumpInfo)`、`8 dislike_reason_list(pc5.h01)`、`9 trace_id`、`10 ad_lable_name`、`11 ad_lable_page_jump_info(FinderJumpInfo)`
→ 日志可打 `aid` / `ad_lable_name` / `uxinfo`。

**插入点（`getAdvertisement_info` 共 36 个 caller）**：扁平包 `ah2.*`（`ah2.m0.q`、`ah2.o/p.onClick`、`ah2.v.B/C/E/h`、`ah2.y0.h`、`ah2.y1/z1/z2.onClick`…）与 `com.tencent.mm.plugin.finder.convert.b4.onClick`。→ 把 `isAdComment` 插到评论转换/点击处对 `getAdvertisement_info()` 判空处。

**勿混**：字面 `"isAdComment"` 实为 `"isAdCommentOrLikedBySelf"`，出自 **`mh4.m0`（=SnsAdUtil 混淆体，TAG=`com.tencent.mm.plugin.sns.data.SnsAdUtil`）**的 `U(SnsObject,String)→boolean`（遍历 `SnsObject.CommentUserList/LikeUserList` 的 `pc5.uf6.d`，判“自己是否赞/评过该 **SNS** 广告”）。这是 SNS 广告链路，与 Finder 评论广告是两码事。

---

## 4. 红包锚点（`com.tencent.mm.plugin.luckymoney.model`，存在，且为继承链）

继承：`q5 → com.tencent.mm.wallet_core.model.d1 → modelbase.m1 → network.l0`；`e6`（空壳）居中；`n6、h6 → e6 → q5`。

| 类 | TAG | CGI (`H()`) | 用途 | 关键签名 |
|---|---|---|---|---|
| **`q5`** | `MicroMsg.NetSceneLuckyMoneyBase` | （基础，`setRequestData` 带 city/province） | 红包网络基类 | `doScene(l,u0)I`、`onGYNetEnd(IIILjava/lang/String;Ly0;[B)V`、`onGYNetEnd(ILjava/lang/String;Lorg/json/JSONObject;)V`、`setRequestData(Map)V`、`getCgicmdForKV()I`、`isJumpRemind()Z`、`H()S`、`I()I`、`J(I,JSONObject)V` |
| **`n6`** | `MicroMsg.NetSceneReceiveLuckyMoney` | `/cgi-bin/mmpay-bin/receivewxhb` | **领取/拆** | `ctor(int,int,String,String,int,String,String)`、`getType()I`、`onGYNetEnd(I,String,JSONObject)V`、`H()S`、`K()V` |
| **`h6`** | `MicroMsg.NetSceneOpenLuckyMoney` | `/cgi-bin/mmpay-bin/openwxhb` | **打开** | `ctor(int,int,8×String)`、`getType()I`、`onGYNetEnd(I,String,JSONObject)V`、`H()S`、`K()V` |

与代码引用的“三类同包同方法”对得上；按 CGI/用途即可为 `fallback grab` 精确打“缺哪个锚点”日志。

---

## 5. P06（`w5 → kw5/sw5`；**纠正：`Kernel not initialized` 实际指向 `gp0.j1`**）

- **`w5$y0` 不存在**。真身在根扁平包：

| 类 | 精确 `Class.forName` 串 | `b` 方法 | 说明 |
|---|---|---|---|
| **`kw5.y0`** | `"kw5.y0"` | **无 `b`**（仅 `<init>(String,WeakReference,String)` + `invoke()`） | kotlin FunctionReference（带 Activity 弱引用异步回调）；兄弟 `kw5.o0/r0/m0/w/x/h/b/s0/a0` 同属一个管理器拆分 |
| **`sw5.y0`** | `"sw5.y0"` | 唯一 `public b()Ljava/util/ArrayList;`（装配 `vy4/d` 媒体条目列表，from `qv/v0`，**无内核串**） | 大控制器 ~30 方法（`A()V`、`B(int,vy4/t,J,int,String,ArrayList,Z)V`、`c()vy4/d`…） |

**`"Kernel not initialized by MMApplication!"` 全 dex 只在 `gp0.j1.j()`**：
```smali
gp0/j1;->j()Lgp0/j1;
  const-string v0, "Kernel not initialized by MMApplication!"
  invoke-static {v0, sget-object gp0/j1->m}, Lb96/a;->c(String,Object)V
```
`gp0.j1` 是**内核/账号核心单例**（`e()modelbase.r1`、`v(Class)lp0/a` 插件注册、`d/h/o()/n()/p()/u()` 启动生命周期），其 `b()`（`public static b()Lgp0/m;`）转调 `j()`、断言 `"mCoreAccount not initialized!"`。

**结论（纠正）**：用“`b` 方法体含 `Kernel not initialized`”验 P06，会**否掉** `kw5.y0`/`sw5.y0`，**命中 `gp0.j1`**（串在 `j()`，`b()` 间接依赖）。若 P06 依赖“初始化守卫”，对象应换 `gp0.j1`。诱饵 `com.tencent.mm.plugin.wallet.balance.ui.lqt.w5` 已排除（钱包 UI，无 `y0`）。

---

## 6. 防撤回 / NetSceneSendMsg

### 6.1 `v51.r0` = **NetSceneSendMsg 主场景**（精确 `Class.forName` = `"v51.r0"`）

```
.class public Lv51/r0;
.super       Lcom/tencent/mm/modelbase/m1;
.implements  Lcom/tencent/mm/network/l0;

# 字段：static r:List; d:modelbase.u0; e:modelbase.o; f:J; g:String; h:I(=15);
#       i:List(LinkedList, MsgInfo 列表); m:I(=3); n:Z(false); o:List;
#       p:Lcom/tencent/mm/storage/e9;  q:Ltn3/f4

# 构造器（4）：
<init>()V
<init>(J I Ljava/lang/String;)V
<init>(Ljava/lang/String;Ljava/lang/String; II J Ljava/lang/String;)V
<init>(Ljava/lang/String;Ljava/lang/String; II Ljava/lang/Object; Ljava/lang/String;)V   // MsgForge 透传

getType()I -> 0x20a (522)
doScene(Lcom/tencent/mm/network/s; Lcom/tencent/mm/modelbase/u0;)I
onGYNetEnd(IIILjava/lang/String; Lcom/tencent/mm/network/y0; [B)V
H(Ljava/lang/String;)V   // 发起点 -> doScene
K(Lcom/tencent/mm/storage/e9;)V   // Forge 塞 MsgInfo
J(I)V  L(I)V
securityLimitCount()I  securityLimitCountReach()Z  securityVerificationChecked(y0)->o1  uniqueInNetsceneQueue()Z
```
`doScene`：`new modelbase/l`，req `pc5/r66`、resp `pc5/s66`、url `/cgi-bin/micromsg-bin/newsendmsg`、cgi `0x20a`(522)、func `0xed`(237)。
`onGYNetEnd`：遍历 `i(List<e9>)`，解 `pc5.s66`/`pc5.qr4`，回填发送结果并调 `PluginMessengerFoundation.ej(...)`。

### 6.2 同族 / 变体 / 完整性

- **`plugin.voip.model.y`**：`.super modelbase.m1; .implements network.l0;`，`ctor(String,String,int,int)`，`doScene` 构造**完全相同**的 `newsendmsg`（req/resp 同为 `pc5/r66`/`pc5.s66`）。**是真实的“单条消息版 NetSceneSendMsg”变体，非空壳/误挂**；作兜底/次路径。
- **`f51.b`** = `MicroMsg.NetSceneSendMsgFake`（本地插入/重发，无网络，`ctor(String,String,String)`）。
- **`v51.p0`** = 发送场景回调监听（`onSceneEnd`，含 `verifypsw` 分支）。
- **`v51.q0`** = `.implements java.lang.Runnable`，**仅以字段引用 `v51.r0`**（`e:v51/r0` = find_class_usage 的 `FIELD: v51/q0.e`），`run()` **new 的是 `com.tencent.mm.modelsimple.l1`**（用 `e(r0).dispatcher()` 发送）——**不 new r0**。

**CGI 完整性枚举**（`find_class(using_strings=["/cgi-bin/micromsg-bin/newsendmsg"])`）= `{ v51.r0, plugin.voip.model.y, x51.b0 }`（`pc5.r66` 为 proto 本身）——即 newsendmsg 发送方仅这三处，无遗漏。

### 6.3 发送工厂 `v51.r1`（SendMsgCgiFactory.Builder，TAG=`MicroMsg.SendMsgCgiFactory`）

- **`executeByPPC` 已混淆成 `c(Lr96/l;)V`**：合成 `d()` 抛 `"...function: executeByPPC"`，`c()` 首句 `Log.i("executeByPPC() called with: content size = .. isByp = ..")`。
- `c()` **new `x51/y`**（PPC 挂起任务 `(r1,long,r96/l,Continuation)`）并投递到 `SequenceLifecycleScope`；PPC 实际出口 `x51/b0`。
- 产出方法 **`a()→new v51/n1`**：`v51/n1.a = com.tencent.mm.modelbase.m1`（真正 NetScene，来自插件 `vu1/l.a(r1)→v51/m1`）+ `v51/n1.b = long`。

### 6.4 MsgForge / 发送子任务（新时代协程发送链，每条消息类型一个 `XxxMsgSendTask`）

`find_class_usage(pc5/r66)`（SendMsgReq）的 RETURN 命中：`e10/h.u`、`kb0/w0.u`。
- **`e10.h`** = `MicroMsg.ContactCardMsg.ContactCardMsgSendTask`（名片）
- **`kb0.w0`** = `MicroMsg.LocationMsg.LocationMsgSendTask`（位置）

统一方法约定：`u()`=**createCgiRequest→返回 `pc5/r66`(SendMsgReq)**、`l()`=sendCgi、`k()`=sendBypCgi、`y()`=uploadAttach、`C()`=prepare、`B()`=updateMsg、`F()`=onMsgSendFail、`<init>={params}`。
→ 完整链：`XxxMsgSendTask` → `u()` 造 `SendMsgReq` → `l()/k()` 走 `SendMsgCgiFactory(v51/r1)` → `NetSceneSendMsg(v51/r0)` 发 newsendmsg(cgi 522)。

**Hook 落点（Anti-撤回/Forge）**：
- 发送结果 / 撤回判据 → `v51.r0.onGYNetEnd`
- 发起点 → `v51.r0.H(String)→doScene`、或 `v51.r0.doScene`
- Forge 塞原始消息 → `v51.r0.<init>(String,String,int,int,Object,String)` + `v51.r0.K(storage.e9)`
- 伪造 SendMsgReq → `XxxMsgSendTask.u()`（`e10/h`、`kb0/w0`…）
- PPC 通道 → `v51.r1.c`（executeByPPC）/ `x51.b0`

---

## 7. 六大锚点一句话状态

| # | 锚点 | 3180 状态 | 精确类名 | 备注 |
|---|---|---|---|---|
| 1 | TTS `e9`/`a21` | ⚠️ 错位 | `com.tencent.mm.storage.e9`=`MsgInfo`；`d1`→`D1(J)V` | `e9` 无 `voicemsg`；`a21`=影视时间线，非 TTS |
| 2 | 长按菜单 B/C | ✅ 全中 | `...ui.chatting.viewitems.{o0,r0,m0}` | `o0.a`、`r0.onMMMenuItemSelected`、`m0.g(View)` |
| 3 | 评论广告 | ✅ | `protobuf.FinderCommentInfo` | 判 `getAdvertisement_info()!=null`（`pc5.i01`: aid/uxinfo/ad_lable_name） |
| 4 | 红包 `n6/h6/q5` | ✅ | `plugin.luckymoney.model.*` | `q5`基→`e6`→`{n6 拆,h6 开}` |
| 5 | P06 `w5.y0` | ⚠️ 改名 | `kw5.y0` / `sw5.y0`（或内核 `gp0.j1`） | `w5$y0` 无效；`Kernel not initialized`→`gp0.j1.j()` |
| 6 | NetSceneSendMsg | ✅ 混淆 | **`v51.r0`** | `getType=0x20a(522)`；`plugin.voip.model.y` 为变体；类名 `NetSceneSendMsg` 已不存在 |

---

## 8. 需你拍板
1. **P06**：实际取 `kw5.y0`（弱引用回调）/`sw5.y0`（大控制器）/ `gp0.j1`（内核单例）？
2. **Item6 主锚**：默认锁 `v51.r0`；若抓包链确实走单条 CGI 通道再切 `plugin.voip.model.y`。
3. **Item1/5 分支**：`a21`、`w5` 家族是否确实属于你的功能面？证据更像影视/评论/内核，非 MsgInfo/TTS。

（报告生成于 DexKit+jadx 反编译；如需我把第七节汇总表单独导成 CSV/JSON 可直接说。）
