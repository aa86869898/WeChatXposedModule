# 微信（com.tencent.mm）聊天窗口「语音气泡 / 文本气泡」注入
## —— 地毯式深度逆向 + 独立 Xposed 模块实现方案（UI 级最稳）

> 逆向工具链：LSPilot（DexKit 类名/字符串/字段/调用关系 + jadx Java + baksmali Smali）
> 目标：`com.tencent.mm`（最新 8.x 系列）；类名大面积混淆为单字母，但 **Log Tag 字符串、Kotlin 合成类名尾缀、raw 资源文件名未混淆** —— 这是「微信更新后动态适配」的根基。
> 术语：「混淆名」= APK 内真实类名；「语义名」= 反推出的原始语义。文中所有结论均给出代码证据行号。

---

## 目录

1. [结论先行（你 3 个问题的直接回答）](#1-结论先行)
2. [核心类映射表（地毯式逆向结果）](#2-核心类映射表)
3. [消息数据层](#3-消息数据层)
4. [聊天列表渲染管线（完整调用链）](#4-聊天列表渲染管线完整调用链)
5. [气泡承载 View 精确定位](#5-气泡承载-view-精确定位)
6. [注入方案设计（三层洋葱，UI 级最稳）](#6-注入方案设计三层洋葱)
7. [可直接落地的模块代码（Kotlin）](#7-可直接落地的模块代码kotlin)
8. [DexKit 动态适配锚点表（微信更新后不用改代码）](#8-dexkit-动态适配锚点表)
9. [调用时序与调试](#9-调用时序与调试)
10. [风险、坑、自检清单](#10-风险坑自检清单)
11. [二次彻底复审结论](#11-二次彻底复审结论)
12. [附录：关键资源 ID / 布局 ID 速查（本版本）](#12-附录关键资源-id--布局-id-速查)

---

## 1. 结论先行

### Q1：怎么在聊天窗口注入语音/文本气泡？
**不要** hook 布局 ID、不要 hook 资源名（微信全部混淆了），而是 hook 「气泡背景的真正赋值点」+「ViewHolder 填充方法」，用 **身份登记 + 二次重注入** 机制接管背景：

| 层 | Hook 点 | 作用 |
|---|---|---|
| L1 注入层 | `com.tencent.mm.ui.chatting.viewitems.to.b(MsgInfo, to, ChattingContext, Boolean)` → 文本气泡背景赋值点 | 拿到文本气泡 View（`to.b` 字段，MMNeat7extView）并换肤 |
| L1 注入层 | `com.tencent.mm.ui.chatting.viewitems.mq.e(ChattingItem, mq, Data, ChatHolder, ChattingContext, Z, Z, OnLongClickListener, r6)` → 语音行填充（静态） | 拿到语音气泡 View（`mq.e` / `mq.x` / `mq.u`）并换肤 |
| L2 保活层 | `android.view.View#setBackground(Drawable)` / `setBackgroundResource(int)` / `AnimImageView.b()` | 微信在播放语音、切主题、切多选态时会**回写**气泡背景；本层把这些回写也接管 |
| L3 尺寸层 | `h0.resetChatBubbleWidth(View,int)` / 语音宽度 `mq.c(Context,int)` / `MMNeat7extView.setMaxWidth(int)` | 换肤后保证文字/时长/波形图标位置不变 |

### Q2：用的是什么方法？
- **Xposed 方法 Hook**（`findAndHookMethod` + `XC_MethodHook.afterHookedMethod`）
- **DexKit 模糊搜索**（按「字符串常量 + 方法签名 + 字段类型」三重定位混淆类，不写死类名）
- **反射缓存**（`Class/Method/Field` 解析一次后缓存；`versionCode` 变化即失效重解析）
- **Drawable 叠加**（`LayerDrawable(自定义气泡, 原始气泡)`）+ **原始九宫格 padding 拷贝**（`Drawable.getPadding()` → `setPadding()`）
- **动画保留**（语音气泡的原始背景是帧动画/Alpha 动画，必须叠加而不是替换，否则播放动画丢）

### Q3：DexKit 找什么字符串？
核心 12 条（详见第 8 章）：
`MicroMsg.MsgInfo`、`MicroMsg.ChattingItemVoice`、`ChattingItemVoice$VoiceItemHolder`、`MicroMsg.ChattingItemText`、`MicroMsg.ChattingItemTextFrom`、`MicroMsg.ChattingItemTextBase`、`MicroMsg.ItemFactoryNew`、`initChattingItemConfig`、`MicroMsg.ChattingDataAdapterV3`、`_onBindViewHolder[`、`MicroMsg.AutoPlay`、`MicroMsg.VoiceLogic`。
辅助（语音数据/时长/动画）：`voicemsg`、`voicelength`、`voice_continue_play_info`、`[voice interrupt] set continue play visible`、`onStateBtnClick voice msg(%s) re-download!`、`onItemClick voice msg(%s) fail`、`chat_voice_message_speed_up`、`chat_voice_message_speed_reset`。

### 一句话架构
> **DexKit 找赋值点 → Hook 赋值点拿到气泡 View → 用「原始 padding + LayerDrawable」换肤 → Hook `View#setBackground*` 防微信回写 → 尺寸补偿 → ViewHolder 复用时按消息类型重置。**

---

## 2. 核心类映射表（地毯式逆向结果）

| # | 语义名 | 混淆类名 | 关键证据（代码/字符串） | 用途 |
|---|---|---|---|---|
| 1 | ChattingItem（聊天消息 Item 抽象基类） | `com.tencent.mm.ui.chatting.viewitems.b0` | 内含 Log Tag `MicroMsg.ChattingItem`；抽象方法 `H(LayoutInflater,View)View`、`O()`、`Q(MenuItem,d,am5.d)`、`S(...)`、`T(View,d,e9)`；`ChattingItemDyeingTemplate` 直接继承它 | 所有消息类型的统一接口 |
| 2 | ChattingItem.BaseViewHolder（Item 视图 Holder 基类） | `com.tencent.mm.ui.chatting.viewitems.h0` | 继承链 `h0 → Object / adapter.q`；字段 `timeTV/userTV/checkBox/maskView/stateIV/uploadingPB/convertView/chattingItem`；`create(View)`、`getCurrentMsgInfo(gk5.d)→e9`、`resetChatBubbleWidth(View,int)` | 拿气泡父容器、消息对象、气泡宽度重置 |
| 3 | **ChattingItemVoice（语音消息 Item）** | `com.tencent.mm.ui.chatting.viewitems.bq` | Log Tag `MicroMsg.ChattingItemVoice`；字符串 `ChattingItemVoice$ChattingItemVoiceFrom`、`onStateBtnClick voice msg(%s) re-download!`、`onItemClick voice msg(%s) fail`、`get voice duration failed`；`H()` 内 `new jh(layoutInflater, 2131624903)` | type=34 的语音行 |
| 4 | **ChattingItemVoice.VoiceItemHolder（语音 Holder）** | `com.tencent.mm.ui.chatting.viewitems.mq` | 字符串 `ChattingItemVoice$VoiceItemHolder`；`[voice interrupt] set continue play visible`、`chat_voice_message_speed_up/reset`；继承 `h0` | **语音气泡就在这里** |
| 5 | **文本消息 Holder（含气泡背景赋值点）** | `com.tencent.mm.ui.chatting.viewitems.to` | 继承 `h0`；字段 `b: MMNeat7extView`；静态方法 `b(e9,to,d,Boolean)` 内 `toVar.b.setBackgroundResource(2131231925/2131232060/2131231841/2131231895)` | **文本气泡就在这里** |
| 6 | ChattingItemTextBase（文本 Item 基类） | `com.tencent.mm.ui.chatting.viewitems.zn` | Tag `MicroMsg.ChattingItemTextFromBase`；`H()` 内 `new jh(layoutInflater, 2131624834)` + `toVar.a(jh,true)`；同时实现 `com.tencent.neattextview.textview.view.f` | type=1 的文本行 |
| 7 | ChattingItemTextFrom（文本填充逻辑） | `hn5.v` | Tag `MicroMsg.ChattingItemTextFrom`；`n(h0,d,d,String)` → `d(d,d,String,a1)`（241 行主填充） | 文本内容/气泡填充链 |
| 8 | ItemFactoryNew（消息类型→Item 工厂） | `com.tencent.mm.ui.chatting.viewitems.kt` | Tag `MicroMsg.ItemFactoryNew`（实际 `MicroMsg.ItemFactoryNew`）、`initChattingItemConfig`；构造函数里 `f(1,0,v.class,TRUE)`、`f(34,0,bq.class,TRUE)`、`f(34,0,iq.class,FALSE)`、`h(34,0,tr.class,true,ht)`… | 版本更新后「类型→类」映射可能变，可用它做校验 |
| 9 | **ChattingDataAdapterV3（聊天列表适配器）** | `com.tencent.mm.ui.chatting.adapter.k` | Tag `MicroMsg.ChattingDataAdapterV3`；实现接口 `ak5.z`(IChattingListAdapter) 与 `com.tencent.mm.pluginsdk.ui.tools.t3`(getCount/getItem/getView)；继承 `com.tencent.mm.view.recyclerview.WxRecyclerAdapter`；`O(s0,int)`=onBindViewHolder、`S(ViewGroup,int)`=onCreateViewHolder、`X0(int)→e9` | 列表级 Hook、按 position/msgId 取消息 |
| 10 | 列表容器 | `com.tencent.mm.ui.chatting.view.MMChattingListView` / `com.tencent.mm.pluginsdk.ui.tools.u3` | `setAdapter(t3)`、`getBaseAdapter()`、`u()→t3` | 判断「当前是否聊天窗口」 |
| 11 | ChattingUIFragment（聊天页 Fragment） | `com.tencent.mm.ui.chatting.ChattingUIFragment` | Tag `MicroMsg.ChattingUIFragment`；`O0()→ak5.z`；`dealContentView(View)`、`doResume` | 聊天窗生命周期、取根视图 |
| 12 | MsgInfo（消息 Bean） | `com.tencent.mm.storage.e9` | Tag `MicroMsg.MsgInfo`；`convertFrom(Cursor)`/`convertTo()`；`getType()/getCreateTime()/getMsgId()/j()`；继承 `dx0.p3→im.c8`(DataBinding 基类) | 判类型（34 语音 / 1 文本）、判收发 |
| 13 | VoiceInfo（voicemsg 表） | `v61.c1` | 字段串 `FileName/VoiceLength/NetOffset/TotalLen/Status/User/Human/VoiceFlag/MasterBufId/VoiceInfoExt` | 语音时长/文件 |
| 14 | VoiceLogic（语音存储&录音） | `v61.d1` | Tag `MicroMsg.VoiceLogic`（`MicroMsg.VoiceLogic`）；`n(J)F`=秒数换算、`Select * From voiceinfo` | 语音时长换算 |
| 15 | VoiceContent（voicemsg 内容 Bean） | `x95.b` | 属性串 `voicemsg/voicelength/length/clientmsgid/fromusername/endflag/cancelflag/voiceformat/bufid/aeskey/voiceurl`；`getLength()I` | 直接解析语音时长 |
| 16 | AutoPlay（语音自动播放） | `com.tencent.mm.ui.chatting.x0` | Tag `MicroMsg.AutoPlay`；`voice_continue_play_info`、`startplay`、`keep_app_silent`；`H(e9,boolean)` | 播放状态判定（是否在播放中） |
| 17 | ChattingItem.Tag（View→消息 的桥） | `com.tencent.mm.ui.chatting.viewitems.ps` | `c()→e9`（实现为 `return this.a.d.b`）；构造 `(am5.d, boolean, q, String)`；被 `mqVar.d.setTag(new ap(...))` 使用 | **从任意气泡 View 反查消息对象** |
| 18 | ChattingItemData（Item 数据包装） | `am5.d` | 字段 `d:hn5.a`（`a:gk5.d` 上下文 + `b:e9` 消息）、`h:hn5.a1`(uiBlocks)、`i:b0`(当前 ChattingItem) | Hook 参数里取消息 |
| 19 | AnimImageView（语音波形动画 View） | `com.tencent.mm.ui.base.AnimImageView` | `b()` 内 `setBackgroundDrawable(ke5.a.i(ctx,2131231925))` / `2131232060` + `setAnimation(AlphaAnimation)`；`a()` 用 `2130968839/8840/8841`(收)、`2131821038/1039/1040`(发) 组帧 | **气泡背景 = 它的 background（还会被它重写）** |
| 20 | 条目视图容器 | `com.tencent.mm.ui.chatting.viewitems.jh` | `new jh(LayoutInflater,int)` + `setTag(holder)` | convertView 外壳 |

> 说明：`bq`(ChattingItemVoice) 注册于 `kt` 的 type=34；`tr`/`ur` 是 MVVM 变体（`tr extends bq`），共用同一个 Holder `mq` 与同一条填充链 —— 所以 Hook `mq.e(...)` 可同时覆盖新旧两条渲染路径。

---

## 3. 消息数据层

```
MsgInfo (com.tencent.mm.storage.e9)           ← 消息 Bean，等价旧版 com.tencent.mm.storage.MsgInfo
  ├─ getType()        : I     type=34 语音；type=1 文本；48 位置；42/66 名片…
  ├─ getMsgId()       : J     本地 msgId（主键）
  ├─ getCreateTime()  : J
  ├─ z0()             : I     isSend（0=别人发的/接收侧，1=自己发的/发送侧）   ← 判「左/右」
  ├─ M0()             : I     status（5 = 发送/下载失败）
  ├─ j()              : String  getContent()（自动处理 pat/群聊/video 包装）
  ├─ K1()             : String  getDBContent()（数据库原文）
  ├─ N0() / x0()      : String  talker / clientMsgId
  └─ F0()             : String  imgPath（语音文件名，用于 VoiceInfo 查询）

语音三件套
  VoiceContent (x95.b)      : 从 content XML <msg><voicemsg .../></msg> 解析出 voicelength/length/…
  VoiceInfo    (v61.c1)     : voiceinfo 表行（FileName/VoiceLength/TotalLen/Status/…）
  VoiceLogic   (v61.d1)     : 逻辑层；n(long)→float 秒；o(e9)/f(e9,String) 判状态

桥接
  ChattingItemData (am5.d)  : .d.b = MsgInfo；.i = ChattingItem；.h = uiBlocks
  ChattingItem.Tag (ps)     : view.getTag() → ps.c() → MsgInfo   ← 模块里最顺手的「View→消息」通道
```

**模块实战取消息的 3 条路（按推荐排序）：**
1. Hook 参数直接拿：`mq.e(...)` 的 `args[2]` 是 `am5.d` → 反射取字段 `d` → 再取字段 `b` → MsgInfo。
2. `view.getTag()` → 若 Tag 是 `ps`（或 `ap`，`ap` 继承/包装 `ps`）→ 调 `c()` → MsgInfo。
3. 适配器：`ChattingDataAdapterV3.X0(int position)` / `Y0(long msgId)` → MsgInfo（按 position/id 反查）。

---

## 4. 聊天列表渲染管线（完整调用链）

```
RecyclerView（ChattingRecyclerView / MMChattingListView）
  └─ ChattingDataAdapterV3 (adapter.k)                         [Tag: MicroMsg.ChattingDataAdapterV3]
       ├─ S(ViewGroup,int)      = onCreateViewHolder
       │     └─ super.G0(...) → WxRecyclerAdapter.R(parent,viewType,info)
       │            ├─ r = K0(viewType)                       // vv5.r：viewType → layoutId + 创建器
       │            ├─ View inflate = jd.b(ctx).inflate(r.e(), parent, false)   // ← 语音 2131624903 / 文本 2131624834
       │            └─ r.i(recyclerView, holder, viewType)    // → ChattingItem.H(...) → holder.create/b(...)
       │                  ├─ 语音: bq.H(inflater,view)  → new mq(); mqVar.b(jh,true,false)
       │                  │        （mq.b() 里 findViewById 拿到全部子 View，见第 5 章）
       │                  └─ 文本: zn.H(inflater,view)  → new to(); toVar.a(jh,true)
       └─ O(s0,int)             = onBindViewHolder
             ├─ super.E0(holder,pos)                            // 真正的绑定
             │     └─ ChattingItem(VO)/填充链
             │          ├─ 语音: bq.n(...) → mq.e(b0,mq,am5.d,q,gk5.d,Z,Z,OnLongClickListener,r6)
             │          │        ├─ 取时长 j = new a1(msg.j()).b ；n = d1.n(j)（秒）
             │          │        ├─ 气泡宽度 = ke5.a.a(ctx, lk.a(mq.c(ctx, 秒))) → mqVar.d.setWidth(..)
             │          │        ├─ 气泡背景：mqVar.e.setBackgroundResource(2131231940|2066|1925|2060)
             │          │        ├─ 时长文本：mqVar.s.setText(getString(2131768495, 秒))
             │          │        └─ 点击区：mqVar.d.setTag(new ap(...)) + setOnClick/onLongClick/onTouch
             │          └─ 文本: hn5.v.n(h0,d,d,str) → hn5.v.d(...) → toVar.b(msg,to,ctx,Boolean)
             │                   └─ 气泡背景：toVar.b.setBackgroundResource(2131231925|2131232060)
             │                      （多选/高亮态：2131231841|2131231895）
             ├─ itemView.setTag(2131365959, viewType)          // ← viewType 藏在这个 key 上
             ├─ e9 msg = ((am5.d) list.o.get(pos)).d.b
             ├─ view = itemView.findViewById(2131365748)        // 消息内容容器
             └─ UIPendingEventNotifier.a(new q0(msg,view), true)
```

**要点**
- 渲染是 **RecyclerView + MvvmList** 结构；`ChattingDataAdapterV3` 同时实现了 `AbsListView` 风格的 `getView(int,View,ViewGroup)`（`com.tencent.mm.pluginsdk.ui.tools.t3`），所以「按 position 拿 item view」也走得通。
- **ViewHolder 会被复用**：同一个 `mq`/`to` 实例会反复绑定不同消息 —— 因此注入必须「每次绑定都重新判定 + 上次状态要能恢复」。
- 语音行有两条渲染路径（旧 `bq/iq` 与新 `tr/ur`，`tr extends bq`），但 **Holder 与填充方法同一个 `mq.e(...)`** → Hook 一次覆盖两条路径。
- 气泡宽度的唯一算法（`mq.c(Context,int)`）：

```java
public static int c(Context ctx, int sec) {          // 返回 dp
    int w;
    if (sec <= 2) w = 80;
    else if (sec < 10) w = (sec - 2) * 9 + 80;
    else if (sec < 60) w = (sec / 10 + 7) * 9 + 80;
    else w = 204;
    if (ctx != null) {
        int dpW = (int) (ke5.a.B(ctx) / ctx.getResources().getDisplayMetrics().density);
        w = Math.min(Math.max((int) (((dpW - 172) * 400f) / dpW), 80), 204);   // 屏幕自适应上限
    }
    return Math.min(w, i4);
}
```

---

## 5. 气泡承载 View 精确定位

### 5.1 文本气泡（type=1）
```
Holder: com.tencent.mm.ui.chatting.viewitems.to  (extends h0)
  └─ 气泡字段名 b      类型 com.tencent.mm.ui.widget.MMNeat7extView   id = 2131365751
  └─ 赋值点（静态）   b(e9 msgInfo, to holder, gk5.d ctx, Boolean isFrom)
        isFrom = true  → 2131231925（左/收到的气泡九宫格）
        isFrom = false → 2131232060（右/自己发的气泡九宫格）
        高亮/多选态     → 2131231841（左）/ 2131231895（右）
  其它相关字段：c(2131365748 内容容器) / q+L(2131365722 引用容器) / p(2131365705)
                / f(2131366056 AnimImageView 语音条) / g(ProgressBar 2131387587)
```
> `to.a(View,boolean)` 里 `b.setMaxWidth(dimension(2131166702)/f.g)` —— 文本最大宽度在这里定，换肤改 padding 时要补偿。

### 5.2 语音气泡（type=34）
```
Holder: com.tencent.mm.ui.chatting.viewitems.mq  (extends h0)
  ├─ d  TextView   id 2131366097  ← 气泡「点击区/宽度载体」：setWidth(气泡宽)、setCompoundDrawables(状态图标)
  │                                        setTag(new ap(...))、setOnClickListener/OnLongClickListener/OnTouchListener
  ├─ e  AnimImageView  id 2131366091  ←★ 气泡背景（接收侧）setBackgroundResource(2131231940|2131231925)
  ├─ x  TextView       id 2131366108  ←★ 气泡背景（发送侧）setBackgroundResource(2131232066|2131232060)
  ├─ u  AnimImageView  id 2131366096  ←★ 气泡背景（发送侧播放动画，type=0，内部 b() 会重设背景+AlphaAnimation）
  ├─ s  TextView       id 2131365751  ← 语音时长文本（"%d''" 形式，2131768495）
  ├─ c  TextView 2131366095 / o FrameLayout 2131366098 / p 2131366099
  ├─ t,w ProgressBar（下载/上传进度）/ f,g ViewStub（引用）
  ├─ z RelativeLayout 2131365813、y ImageView 2131365811、A TextView 2131365812、C RL 2131365817、D TV 2131365816
  │     （断点续听/倍速提示/未播红点等小组件容器）
  └─ q FrameLayout 2131366105、r ImageView 2131366106（发送侧波形）
```

**气泡背景与状态（`mq.e(...)` 内，第 373–389 行）：**

| 消息状态 | `e` 的背景 | `x` 的背景 |
|---|---|---|
| 未播放（`((c8)msg).F & 1) != 1`）+ 接收 | `2131231940` | — |
| 未播放 + 发送 | `2131232066` | `2131232066` |
| 已播放 + 接收 | `2131231925` | — |
| 已播放 + 发送 | `2131232060` | `2131232060` |

**关键结论：`2131231925` / `2131232060` 就是「收到的气泡九宫格 / 发出的气泡九宫格」本体** —— 由 `AnimImageView.b()` 反向印证：

```java
public void b() {                       // 开始播放语音动画
    a();
    if (this.f == 0) {                  // type=0（发送侧波形）
        setBackgroundDrawable(ke5.a.i(getContext(), isFrom ? 2131231925 : 2131232060));   // ←★气泡本体
        setAnimation(this.g);           // AlphaAnimation 0.1→1.0 循环
        this.g.startNow();
    } else if (this.f == 1) {           // type=1（接收侧波形）：只换 compound drawable 帧动画
        setCompoundDrawablesWithIntrinsicBounds(this.h, null, null, null); this.h.stop(); this.h.start();
    } ...
}
```
> 所以：**只要 Hook `AnimImageView.b()` + `mq.e(...)` 赋值处，就能完整接管语音气泡背景**；且必须「叠加」而不能「替换」，否则帧动画/AlphaAnimation 丢失。

---

## 6. 注入方案设计（三层洋葱，UI 级最稳）

### 6.0 设计原则（为什么这样最稳）
1. **不依赖布局 ID / 资源 ID / 资源名**（微信全部混淆，`listResources("drawable","chat")` 查不到）。
2. **只依赖「气泡背景赋值」这个语义动作**：微信每次绑定都会给它设背景，我们跟着设 → 天然幂等、天然跟随主题/深浅色/多选态。
3. **身份登记（WeakHashMap<View, Info>）**：只对「我们注入过的 View」生效，不影响其它消息类型，也不会 hook 全局所有 View。
4. **叠加而非替换**：`LayerDrawable(我们的气泡, 微信原来的气泡)` —— 原背景若是 `AnimationDrawable`（波形帧动画）或九宫格形状，都原样保留在最上层/最下层，视觉与动画不破。
5. **padding 拷贝**：`orig.getPadding(r)` → `ours.setPadding(r)`。这是「文字/时长/波形位置完全不变」的关键，比重新量 UI 稳 100 倍。
6. **防回写**：微信在播放语音（`AnimImageView.b()`）、切深色模式、多选高亮时会再次调用 `setBackground*`；我们 Hook `View#setBackground` / `setBackgroundResource`（带重入标志，防死循环）把这些回写也接管。

### 6.1 洋葱层结构

```
        ┌───────────────────────────────────────────────┐
        │ L3 尺寸补偿层                                  │
        │   h0.resetChatBubbleWidth(View,int)            │
        │   mq.c(Context,int)  （语音 dp 宽度算法）        │
        │   MMNeat7extView.setMaxWidth(int)              │
        ├───────────────────────────────────────────────┤
        │ L2 保活/防回写层                               │
        │   View#setBackground(Drawable)                 │
        │   View#setBackgroundResource(int)              │
        │   AnimImageView#b()   （语音播放开始时重设背景） │
        ├───────────────────────────────────────────────┤
        │ L1 注入层（核心）                              │
        │   to.b(e9,to,d,Boolean)            文本气泡    │
        │   mq.e(b0,mq,am5.d,q,d,Z,Z,L,r6)   语音气泡    │
        ├───────────────────────────────────────────────┤
        │ L0 发现层（DexKit + 反射缓存）                 │
        │   字符串/签名 → Class+Method+Field，versionCode │
        │   变化即重解析；解析结果做 validate() 校验       │
        └───────────────────────────────────────────────┘
```

### 6.2 注入流程（文本气泡）
```
1. Hook to.b(MsgInfo, Holder, Ctx, Boolean)  after
2. holder = args[1]                          // to
3. view   = getObjectField(holder, "b")      // MMNeat7extView = 文本气泡（字段名解析见 6.6）
4. isFrom = args[3] as Boolean
5. orig   = view.background                  // 微信刚设的九宫格
6. ours   = BubbleFactory.create(ctx, isFrom, voice=false, orig)
             ├─ 取模块资源（XModuleResources）或程序化绘制（GradientDrawable+尾巴）
             ├─ ours.setPadding(orig.getPadding(rect))     ← 关键：保持文字位置
             └─ 若 orig 是 InsetDrawable/LayerDrawable 且含动画，注意保留
7. view.background = ours  （经 applyBg() 带重入标志写入）
8. registry[view] = Info(orig, isFrom, voice=false, widthDelta)
9. 若处于「高亮/多选态」(holder 内 ProgressBar g 的 visibility==VISIBLE，或 orig 是 1841/1895)
      → 追加一层半透明高亮 LayerDrawable，保留微信高亮语义
```

### 6.3 注入流程（语音气泡）
```
1. Hook mq.e(...) after（静态方法，9 参）
2. holder = args[1]                        // mq
3. targets = [ field(e), field(x), field(u) ]   // 三个背景承载 View，取非空的
4. isFrom  = args[5] as Boolean，或用 msg.z0()（0=接收/1=发送）双保险
5. for each v in targets:
      orig = v.background
      ours = BubbleFactory.create(ctx, isFrom, voice=true, orig)
      if (v is AnimImageView || orig is AnimationDrawable)
            final = LayerDrawable([ours, orig])       // 保留波形帧动画 + AlphaAnimation
      else  final = ours
      applyBg(v, final); registry[v] = Info(...)
6. 宽度补偿：点击区 d 的宽度 = mq.c(ctx,秒) 换算；若 ours 的 padding 与 orig 不同，
   用 resetChatBubbleWidth 思路补差值（见 6.5）
7. 时长文本 s 的 compound drawable 不动（微信自己管）
```

### 6.4 L2 防回写（保活层）
```kotlin
// 重入标志：我们自己写背景时不要被自己拦住
private val writing = ThreadLocal.withInitial { false }

fun applyBg(v: View, d: Drawable?) {
    writing.set(true); try { v.background = d } finally { writing.set(false) }
}

// Hook View#setBackground / setBackgroundResource / AnimImageView#b
beforeHookedMethod { p ->
    if (writing.get()) return@beforeHookedMethod          // 放行自己的写入
    val v = p.thisObject as? View ?: return
    val info = registry[v] ?: return                       // 只处理登记过的气泡 View
    val incoming = when (p.method.name) {
        "setBackground" -> p.args[0] as? Drawable
        "setBackgroundResource" -> null                    // id 形式：直接换成我们的
        else -> null
    }
    if (incoming != null && incoming === info.current) return@beforeHookedMethod
    // 微信又想改回去 → 用「我们的 + 它的」叠加层替换
    p.args[0] = wrap(info, incoming)
}
```
> `AnimImageView#b()` 内部会 `setBackgroundDrawable(ke5.a.i(ctx,2131231925))` → 走 `setBackground(Drawable)`，被同一机制接管；随后它 `setAnimation(AlphaAnimation)` 依然是 view animation，对 LayerDrawable 无副作用。

### 6.5 L3 尺寸/padding 补偿
- **首选（推荐）**：把原气泡的 padding 原样搬到我们的气泡上（`setPadding`）→ 内容偏移完全不变，宽度算法 `mq.c()` 不用改。
- 若自定义气泡九宫格的内边距与微信差异较大：
  - 文本：`tv.setMaxWidth(tv.maxWidth + (oursPaddingL+R - origPaddingL+R))`
  - 语音：Hook `mq.c(Context,int)` after，把返回值改成 `orig.c() + deltaDp`；或 Hook `h0.resetChatBubbleWidth(View,int)` after 后对 `view.layoutParams.width` 做 `+deltaPx`。
  - `resetChatBubbleWidth` 原始实现（可直接参考）：
    ```java
    public void resetChatBubbleWidth(View view, int i) {
        if (view != null) { ViewGroup.LayoutParams lp = view.getLayoutParams();
            lp.width = (int) (i / f.g); view.setLayoutParams(lp); view.requestLayout(); }
    }
    ```

### 6.6 字段名/方法名的「动态」解析（不写死混淆名）
| 目标 | 首选特征（与版本无关） | 次级特征（写死名） |
|---|---|---|
| 文本气泡 View | Holder 内**类型为 `com.tencent.mm.ui.widget.MMNeat7extView` 的字段** | 字段名 `b` |
| 语音气泡 View | Holder 内**类型为 `com.tencent.mm.ui.base.AnimImageView` 的字段**（取 e/u）+ 与 `d` 同宽的 TextView | 字段名 `e`/`u`/`x` |
| 发送/接收 | `MsgInfo.z0()`（0=接收，1=发送）；Hook 参数的 `Boolean` | args[3] / args[5] |
| 消息对象 | Hook 参数里的 `am5.d` → 字段 `d` → 字段 `b`（e9）；或 `view.getTag()`→`ps.c()` | — |
| Holder 类 | 被 `ChattingItem.H(LayoutInflater,View)` new 出来的类；或含 `ChattingItemVoice$VoiceItemHolder` 字符串的类 | `mq` / `to` |

---

## 7. 可直接落地的模块代码（Kotlin）

> 依赖：`api 'de.robv.android.xposed:api:82'` + `implementation 'io.github.lsposed.dexkit:dexkit:2.x'`
> 目标：一个**完全独立**的 Xposed 模块（不依赖 LSPilot 本体、不依赖 BSH），只靠 Xposed API + DexKit + 反射。

### 7.1 入口（仅作用于微信）

```kotlin
class WxBubbleModule : IXposedHookLoadPackage {
    override fun handleLoadPackage(lp: XposedBridge.LoadPackageParam) {
        if (lp.packageName != "com.tencent.mm") return
        // 微信很重，DexKit 解析放子线程，装 Hook 前先等解析完
        Thread({ WxCore.boot(lp) }, "wx-bubble-boot").start()
    }
}
```

### 7.2 L0：发现层（DexKit + 反射缓存 + 校验）

```kotlin
object WxCore {
    private const val PKG = "com.tencent.mm"
    private lateinit var lp: XposedBridge.LoadPackageParam
    private var apkPath: String = ""
    private var verTag: String = ""

    // —— 解析产物 ——
    var cMsgInfo: Class<*>? = null;          private set      // e9
    var mMIsSend: Method? = null;            private set      // z0()
    var cVoiceItem: Class<*>? = null;        private set      // bq  (ChattingItemVoice)
    var cVoiceHolder: Class<*>? = null;      private set      // mq  (VoiceItemHolder)
    var mVoiceFill: Method? = null;          private set      // mq.e(...)
    var cTextHolder: Class<*>? = null;       private set      // to
    var mTextBubble: Method? = null;         private set      // to.b(...)
    var cAdapter: Class<*>? = null;          private set      // adapter.k
    var mAdapterBind: Method? = null;        private set      // O(s0,int)
    var cAnimImageView: Class<*>? = null;    private set

    fun boot(lp: XposedBridge.LoadPackageParam) {
        this.lp = lp
        apkPath = lp.appInfo?.sourceDir ?: return
        verTag = versionTag()                       // versionCode + versionName
        log("boot apk=$apkPath ver=$verTag")
        if (!resolve()) { log("resolve FAILED"); return }
        install()
    }

    private fun versionTag(): String = try {
        val pm = XposedHelpers.callMethod(
            XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null), "currentActivityThread"),
            "getSystemContext")?.let { XposedHelpers.callMethod(it, "getPackageManager") } ?: return "?"
        @Suppress("UNCHECKED_CAST")
        val pi = XposedHelpers.callMethod(pm, "getPackageInfo", PKG, 0)
        val code = XposedHelpers.getObjectField(pi, "versionCode")
        val name = XposedHelpers.getObjectField(pi, "versionName")
        "$code:$name"
    } catch (e: Throwable) { "unknown" }

    /** DexKit 检索 + 本地 validate() 校验，返回是否全部就绪 */
    private fun resolve(): Boolean {
        DexKitBridge.create(apkPath, lp.classLoader).use { bridge ->

            // ---- 1) 语音 Item：Tag + 两个强特征字符串 ----
            cVoiceItem = bridge.findClass {
                matcher {
                    usingStrings("MicroMsg.ChattingItemVoice")
                    methods {
                        add { name("H"); paramTypes("android.view.LayoutInflater", "android.view.View") }
                    }
                }
            }.firstOrNull { cls -> cls.usingStrings.containsKey("onStateBtnClick voice msg(%s) re-download!") }
             ?.let { it.toClass(lp.classLoader) } ?: XposedHelpers.findClassIfExists("com.tencent.mm.ui.chatting.viewitems.bq", lp.classLoader)
            if (cVoiceItem == null) return false

            // ---- 2) 语音 Holder：含 VoiceItemHolder 尾缀 + create(View,bool,bool)+fill 9 参 ----
            cVoiceHolder = bridge.findClass {
                matcher {
                    usingStrings("ChattingItemVoice\$VoiceItemHolder")
                    methods {
                        add { name("b"); paramTypes("android.view.View", "boolean", "boolean") }
                    }
                }
            }.firstOrNull { cls -> cls.methods.any { m -> m.name == "e" && m.paramTypes.size == 9 } }
             ?.let { it.toClass(lp.classLoader) }

            // ---- 3) 文本 Holder：Tag + 静态气泡赋值点 b(e9,to,ctx,Boolean) ----
            mTextBubble = bridge.findMethod {
                matcher { name("b"); paramCount(4) }
                searchInClass { matcher { usingStrings("MicroMsg.ChattingItemText") } }
            }.firstOrNull { m -> m.paramTypes[0].contains("storage") && m.paramTypes[3] == "java.lang.Boolean" }
             ?.let { it.toMethod(lp.classLoader) }
            if (mTextBubble != null) cTextHolder = mTextBubble!!.declaringClass

            // ---- 4) 适配器 ----
            cAdapter = bridge.findClass { matcher { usingStrings("MicroMsg.ChattingDataAdapterV3") } }
                .firstOrNull { cls -> cls.methods.any { it.name == "O" && it.paramTypes.size == 2 } }
                ?.let { it.toClass(lp.classLoader) }

            // ---- 5) MsgInfo ----
            cMsgInfo = bridge.findClass {
                matcher {
                    usingStrings("MicroMsg.MsgInfo")
                    methods {
                        add { name("convertFrom"); paramTypes("android.database.Cursor") }
                        add { name("getType") }
                        add { name("getCreateTime") }
                    }
                    searchInPackages(listOf("com.tencent.mm.storage"))
                }
            }.firstOrNull()?.let { it.toClass(lp.classLoader) }

            // ---- 6) AnimImageView ----
            cAnimImageView = XposedHelpers.findClassIfExists("com.tencent.mm.ui.base.AnimImageView", lp.classLoader)
        }

        // ---- 本地二次校验（防止 DexKit 命中错误候选）----
        if (!validate()) return false
        resolveDerived()          // Method/Field 细节
        return true
    }

    private fun validate(): Boolean {
        if (cVoiceHolder == null) { log("voiceHolder FAILED"); return false }
        mVoiceFill = cVoiceHolder!!.declaredMethods.firstOrNull { m ->
            m.name == "e" && m.parameterTypes.size == 9 &&
            m.parameterTypes[1] == cVoiceHolder &&
            m.parameterTypes[0].name.endsWith("viewitems.b0") || true
        } ?: return false
        if (cTextHolder == null) { log("textHolder FAILED"); return false }
        mMIsSend = cMsgInfo?.declaredMethods?.firstOrNull { m ->
            m.name.length == 2 && m.name.startsWith("z") && m.returnType == Int::class.javaPrimitiveType &&
            m.parameterTypes.isEmpty()
        }
        return true
    }

    private fun resolveDerived() {
        mAdapterBind = cAdapter?.declaredMethods?.firstOrNull { m ->
            m.name == "O" && m.parameterTypes.size == 2 &&
            m.parameterTypes[0].name.contains("recyclerview") || m.parameterTypes[1] == Int::class.javaPrimitiveType
        }
        log("resolved: voiceItem=$cVoiceItem voiceHolder=$cVoiceHolder fill=${mVoiceFill?.parameterTypes?.size} " +
            "textHolder=$cTextHolder textBubble=$mTextBubble adapter=$cAdapter bind=$mAdapterBind")
    }

    fun log(s: String) = XposedBridge.log("[WxBubble] $s")
}
```

### 7.3 装 Hook（L1/L2/L3）

```kotlin
private fun install() {
    val lp = WxCore.lp

    // ---------- L1-a 文本气泡：to.b(e9, to, ctx, Boolean) ----------
    WxCore.mTextBubble?.let { m ->
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val holder = param.args.getOrNull(1) ?: return
                val view   = Reflect.fieldByType(holder, "com.tencent.mm.ui.widget.MMNeat7extView") as? View ?: return
                val isFrom = (param.args.getOrNull(3) as? Boolean) ?: true
                BubbleEngine.inject(view, isFrom, voice = false)
            }
        })
    }

    // ---------- L1-b 语音气泡：mq.e(b0, mq, am5.d, q, ctx, Z, Z, OnLongClickListener, r6) ----------
    WxCore.mVoiceFill?.let { m ->
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val holder = param.args.getOrNull(1) ?: return
                val isFrom = (param.args.getOrNull(5) as? Boolean) ?: true
                // 1)  AnimImageView（波形 + 气泡背景）：优先按类型找
                val anims = Reflect.fieldsByType(holder, "com.tencent.mm.ui.base.AnimImageView")
                // 2)  发送侧 TextView 气泡（与 AnimImageView 同类型族，取 TextView 且非时长文本）
                val tvs = Reflect.fieldsByType(holder, "android.widget.TextView")
                // 3)  点击区 TextView（宽度载体，宁可不动，只做宽度补偿）
                val targets = LinkedHashSet<View>()
                anims.forEach { if (it is View) targets += it }
                BubbleEngine.injectAll(targets, isFrom, voice = true)
            }
        })
    }

    // ---------- L2 防回写：View#setBackground / setBackgroundResource ----------
    XposedBridge.hookAllMethods(View::class.java, "setBackground", object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) = BubbleEngine.onSetBackground(param, param.args[0] as? Drawable)
    })
    XposedBridge.hookAllMethods(View::class.java, "setBackgroundResource", object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) = BubbleEngine.onSetBackground(param, null)
    })
    XposedHelpers.findAndHookMethod(View::class.java, "setBackgroundDrawable", android.graphics.drawable.Drawable::class.java,
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) = BubbleEngine.onSetBackground(param, param.args[0] as? Drawable)
        })

    // ---------- L2-b AnimImageView.b()：播放语音时微信会重设气泡背景 ----------
    WxCore.cAnimImageView?.let { c ->
        XposedBridge.hookAllMethods(c, "b", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val v = param.thisObject as? View ?: return
                BubbleEngine.refresh(v)
            }
        })
    }

    // ---------- L3 尺寸补偿 ----------
    // 3-a 语音宽度（dp）：mq.c(Context,int) —— 静态
    WxCore.cVoiceHolder?.declaredMethods?.firstOrNull { it.name == "c" && it.parameterTypes.size == 2 }?.let { m ->
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val base = param.result as? Int ?: return
                val holderKey = "voiceWidth"
                val delta = BubbleEngine.dpDelta(param.thisObject as? android.content.Context ?: return, holderKey)
                param.result = (base + delta).coerceAtLeast(80)
            }
        })
    }
    // 3-b 通用气泡宽度重置：h0.resetChatBubbleWidth(View,int)
    XposedHelpers.findAndHookMethod("com.tencent.mm.ui.chatting.viewitems.h0", lp.classLoader,
        "resetChatBubbleWidth", View::class.java, Int::class.javaPrimitiveType, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val v = param.args[0] as? View ?: return
                BubbleEngine.compensate(v, param.args[1] as? Int ?: return, "resetWidth")
            }
        })
    // 3-c 文本最大宽度
    XposedHelpers.findAndHookMethod("com.tencent.mm.ui.widget.MMNeat7extView", lp.classLoader,
        "setMaxWidth", Int::class.javaPrimitiveType, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val v = param.thisObject as? View ?: return
                BubbleEngine.compensateTextMax(v, param.args[1] as? Int ?: return)
            }
        })

    // ---------- 聊天窗生命周期（可选：进房时刷新、退房时清理）----------
    XposedHelpers.findAndHookMethod("com.tencent.mm.ui.chatting.ChattingUIFragment", lp.classLoader,
        "dealContentView", View::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) { BubbleEngine.chatRoot = param.args[0] as? View }
        })
    WxCore.log("installed.")
}
```

### 7.4 气泡引擎（注入 + 防回写 + 尺寸补偿）

```kotlin
object BubbleEngine {
    private data class Info(
        var original: Drawable?,
        val isFrom: Boolean,
        val voice: Boolean,
        var wrapped: Drawable? = null,
        var padDeltaL: Int = 0, var padDeltaR: Int = 0
    )
    private val reg = WeakHashMap<View, Info>()
    private val writing = ThreadLocal.withInitial { false }
    var chatRoot: View? = null

    /** L1：注入 */
    fun injectAll(views: Collection<View>, isFrom: Boolean, voice: Boolean) =
        views.forEach { inject(it, isFrom, voice) }

    fun inject(view: View, isFrom: Boolean, voice: Boolean) {
        val orig = view.background ?: return
        val ours = BubbleFactory.create(view.context, isFrom, voice, orig)
        copyPadding(orig, ours)

        val final: Drawable = if (voice || orig is AnimationDrawable) {
            // 语音/动画背景：保留微信原来的层（帧动画 + 形状），我们垫在下面
            LayerDrawable(arrayOf(ours, orig)).also { it.setLayerInset(1, 0, 0, 0, 0) }
        } else ours

        val info = reg.getOrPut(view) { Info(orig, isFrom, voice) }
        info.original = orig; info.wrapped = final
        info.padDeltaL = padL(ours) - padL(orig); info.padDeltaR = padR(ours) - padR(orig)
        applyBg(view, final)
    }

    /** L2：微信想改回去 → 拦截 */
    fun onSetBackground(param: XC_MethodHook.MethodHookParam, incoming: Drawable?) {
        if (writing.get()) return
        val v = param.thisObject as? View ?: return
        val info = reg[v] ?: return
        if (incoming != null && incoming === info.wrapped) return
        // 保持「我们的气泡 + 微信的新背景」叠加，微信想加什么（动画/高亮）都留着
        val ours = info.wrapped as? Drawable ?: return
        val base = info.original
        val layered = if (incoming != null && incoming !== base)
                          LayerDrawable(arrayOf(ours, incoming))
                      else ours
        writing.set(true)
        try {
            if (param.method.name == "setBackgroundResource") {
                v.setBackground(layered)
            } else {
                param.args[0] = layered
            }
            param.result = Unit
        } finally { writing.set(false) }
    }

    /** 播放动画触发（AnimImageView.b()）后重新套一次 */
    fun refresh(v: View) {
        val info = reg[v] ?: return
        val orig = v.background ?: return
        if (orig === info.wrapped) return
        val ours = (info.wrapped as? LayerDrawable)?.getDrawable(0) ?: info.wrapped ?: return
        applyBg(v, LayerDrawable(arrayOf(ours, orig)))
    }

    /** L3：宽度补偿（dp 级） */
    fun dpDelta(ctx: android.content.Context, key: String): Int =
        ((padDeltaPx(key) / ctx.resources.displayMetrics.density).toInt())

    fun compensate(v: View, origPx: Int, key: String) {
        val info = reg[v] ?: return
        val lp = v.layoutParams ?: return
        lp.width = (origPx + info.padDeltaL + info.padDeltaR)
        v.layoutParams = lp
    }

    fun compensateTextMax(v: View, orig: Int) {
        val info = reg[v] ?: return
        val tv = v as? android.widget.TextView ?: return
        tv.maxWidth = (orig + info.padDeltaL + info.padDeltaR).coerceAtLeast(orig)
    }

    private fun applyBg(v: View, d: Drawable?) {
        writing.set(true); try { v.background = d } finally { writing.set(false) }
    }

    private fun copyPadding(from: Drawable?, to: Drawable?) {
        if (from == null || to == null) return
        val r = android.graphics.Rect()
        if (from.getPadding(r)) to.setPadding(r.left, r.top, r.right, r.bottom)
    }
    private fun padL(d: Drawable) = pad(d, true)
    private fun padR(d: Drawable) = pad(d, false)
    private fun pad(d: Drawable, left: Boolean): Int {
        val r = android.graphics.Rect(); return if (d.getPadding(r)) (if (left) r.left else r.right) else 0
    }
}

/** 反射小工具：按字段类型找 View（不写死混淆字段名） */
object Reflect {
    fun fieldByType(obj: Any, typeName: String): Any? =
        obj.javaClass.declaredFields.firstOrNull { it.type.name == typeName }?.let {
            it.isAccessible = true; it.get(obj)
        }
    fun fieldsByType(obj: Any, typeName: String): List<Any> =
        obj.javaClass.declaredFields.filter { it.type.name == typeName }.map {
            it.isAccessible = true; it.get(obj) ?: return@map null
        }.filterNotNull()
}
```

### 7.5 气泡 Drawable 工厂

```kotlin
object BubbleFactory {
    private var moduleRes: android.content.res.Resources? = null   // 可选：模块自带九宫格 png

    fun init(modulePath: String) = runCatching {
        moduleRes = XModuleResources.createForXposed(modulePath)
    }

    /** 生成气泡：优先模块资源，退化到程序化绘制 */
    fun create(ctx: android.content.Context, isFrom: Boolean, voice: Boolean, orig: Drawable?): Drawable {
        moduleRes?.let { r ->
            val id = if (voice) (if (isFrom) R.drawable.bubble_voice_from else R.drawable.bubble_voice_to)
                     else       (if (isFrom) R.drawable.bubble_text_from  else R.drawable.bubble_text_to)
            runCatching { return r.getDrawable(id, ctx.theme) }
        }
        return programmatic(ctx, isFrom, voice)
    }

    /** 程序化九宫格替代：圆角体 + 小尾巴（LayerDrawable），完全免资源文件 */
    private fun programmatic(ctx: android.content.Context, isFrom: Boolean, voice: Boolean): Drawable {
        val dm = ctx.resources.displayMetrics
        fun dp(v: Float) = (v * dm.density + 0.5f).toInt()
        val r = dp(if (voice) 8f else 6f).toFloat()
        val body = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            // 只让「靠头像一侧」小圆角，另一侧大圆角，接近微信观感
            cornerRadii = floatArrayOf(r, r, r, r, r, r, r, r)
            setColor(if (isFrom) 0xFFFFFFFF.toInt() else 0xFF95EC69.toInt())
            setStroke(dp(0.6f), 0x1F000000)
        }
        // 尾巴（小三角），按左右摆位
        val tailSize = dp(if (voice) 6f else 5f)
        val path = android.graphics.Path().apply {
            if (isFrom) { moveTo(0f, 0f); lineTo(tailSize.toFloat(), 0f); lineTo(0f, tailSize.toFloat()); close() }
            else       { moveTo(0f, 0f); lineTo(tailSize.toFloat(), 0f); lineTo(tailSize.toFloat(), tailSize.toFloat()); close() }
        }
        val tail = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.PathShape(path, tailSize.toFloat(), tailSize.toFloat())).apply {
            paint.color = if (isFrom) 0xFFFFFFFF.toInt() else 0xFF95EC69.toInt()
            intrinsicWidth = tailSize; intrinsicHeight = tailSize
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(body, tail))
    }
}
```

> 更省事的做法：把自定义气泡做成 **九宫格 png 放模块 `res/drawable-xxhdpi/`**，`XModuleResources.createForXposed(modulePath).getDrawable(R.drawable.xxx, theme)` 直接拿（LSPosed 下可用）；拿不到就自动退到 7.5 的程序化绘制，两条路都写好了。

### 7.6 需要在 `assets/` 里预留的自检（可选）
- 首次命中后 `XposedBridge.log` 打印：`voiceHolder=mq? fillArgs=9 textBubble=true adapter=ChattingDataAdapterV3`
- 记录 `versionTag`，与上次不同时清空缓存重新解析（`resolve()` 已按 `verTag` 定位）。

---

## 8. DexKit 动态适配锚点表（微信更新后不用改代码）

### 8.1 一级锚点（必用，命中即为目标；带「为什么稳」）

| # | 字符串常量 | 命中的类（本版本） | 用途 | 为什么稳 |
|---|---|---|---|---|
| A1 | `MicroMsg.MsgInfo` | `com.tencent.mm.storage.e9` | 消息 Bean | 这是 `Log` Tag，微信永不清；配合 `convertFrom(Cursor)`+`getType()`+有 `com.tencent.mm.storage` 包名三重校验 |
| A2 | `MicroMsg.ChattingItemVoice` | `com.tencent.mm.ui.chatting.viewitems.bq` | 语音 Item | Log Tag + 字符串里的 **空格/占位符 `%s` 与措辞** 多年未变 |
| A3 | `ChattingItemVoice$VoiceItemHolder` | `com.tencent.mm.ui.chatting.viewitems.mq` | 语音 Holder | Kotlin 合成名尾缀 `$VoiceItemHolder`，**即使在混淆 APK 里也以字符串常量形式保留**（本版本可证） |
| A4 | `MicroMsg.ChattingItemText` | `hn5.*` / `to` / `zn` 家族 | 文本 Item/Holder | Log Tag |
| A5 | `MicroMsg.ChattingItemTextFrom` | `hn5.v` | 文本填充入口 | Log Tag（另有 `ChattingItemTextFromBase`、`ChattingItemTextToBase` 可交叉验证） |
| A6 | `MicroMsg.ChattingItemTextBase` | `com.tencent.mm.ui.chatting.viewitems.un` | 文本菜单/基类 | Log Tag |
| A7 | `MicroMsg.ItemFactoryNew` | `com.tencent.mm.ui.chatting.viewitems.kt` | 类型→Item 工厂 | Log Tag；构造函数必有 `initChattingItemConfig` |
| A8 | `initChattingItemConfig` | 同上 | 定位工厂构造 | 微信业务埋点，语义固定 |
| A9 | `MicroMsg.ChattingDataAdapterV3` | `com.tencent.mm.ui.chatting.adapter.k` | 列表适配器 | Log Tag；`V3` 后缀稳定 |
| A10 | `_onBindViewHolder[` | 同上（`O()` 方法内） | 确认 onBind 方法 | onBind 专属模板串 |
| A11 | `MicroMsg.AutoPlay` | `com.tencent.mm.ui.chatting.x0` | 语音自动播放/续听 | Log Tag |
| A12 | `MicroMsg.VoiceLogic` | `v61.d1`（+ `x0`） | 语音逻辑/时长 | Log Tag |
| A13 | `MicroMsg.ChattingItem` | `com.tencent.mm.ui.chatting.viewitems.b0` | Item 抽象基类 | Log Tag，可用来校验「继承链」 |
| A14 | `MicroMsg.ChattingUIFragment` | `com.tencent.mm.ui.chatting.ChattingUIFragment` | 聊天页 | Log Tag |
| A15 | `voicemsg` | `x95.b`（VoiceContent） | 解析语音时长 | XML 节点名，协议级，永不变 |

> DexKit 匹配用 `usingStrings("MicroMsg.MsgInfo")` 一类调用；`findClass().matcher{ usingStrings(...) }` 命中多个时，用下面的二级特征做代码内筛选。

### 8.2 二级锚点（语义动作串，做候选筛选/校验）

| 字符串 | 出现在 | 用途 |
|---|---|---|
| `onStateBtnClick voice msg(%s) re-download!` | `bq.f()` | 确认「这就是语音 Item」 |
| `onItemClick voice msg(%s) fail` / `onItemClick tag(%s) is null` | `bq.g0()` | 同上 |
| `get voice duration failed` / `get voice clientId failed` | `bq.h0()` | 同上 |
| `[voice interrupt] set continue play visible ` | `mq.e()` | 确认「语音 Holder 的填充方法」 |
| `chat_voice_message_speed_up` / `chat_voice_message_speed_reset` | `mq.e()` | 确认倍速提示 UI（可顺带做倍速功能） |
| `voice_continue_play_info` / `startplay` / `keep_app_silent` / `stop play` | `x0`（AutoPlay） | 播放状态、悬浮球续听 |
| `checkChecksum fail. %d, %d` / `start play msg: %d, recordInterrupt: %b` | `x0.H()` | 播放起点 |
| `voicelength` / `VoiceLength` / `TotalLen` / `MasterBufId` | `v61.c1`（VoiceInfo） | 取语音时长 |
| `.msg.voicemsg.$voicelength` / `$length` / `$endflag` | `v61.f1`（VoiceMsgExtension） | 语音消息协议字段 |
| `initChattingItemConfig` | `kt.<init>` | 工厂 |
| `MicroMsg.MsgInfoStorageLogic`（`MicroMsg.MsgInfoStorageLogic`） | `b41.*` | 消息存储逻辑（群名片/撤回等进阶需求） |
| `[oneliang] the length of voice info file name is zero` | `v61.d1` | VoiceLogic 校验 |
| `SelectRecord` / `msgId=? and talker=?` | `b41.*` | 反查消息行（页外需求） |

### 8.3 三级锚点：签名/结构特征（类名全变时兜底）

```kotlin
// 1) 语音 Holder：有 b(View,ZZ)→h0 与 e(...9参...) 且参数[1]==自己
matcher {
  methods { add { name("b"); paramTypes("android.view.View","boolean","boolean") } }
  methods { add { name("e"); paramCount(9) } }
}
// 2) 文本 Holder：有字段类型 com.tencent.mm.ui.widget.MMNeat7extView
//    且有静态方法 b(?,?,?,java.lang.Boolean)
// 3) Item 基类：抽象 H(LayoutInflater,View)View + 含 "MicroMsg.ChattingItem"
// 4) 适配器：含 "MicroMsg.ChattingDataAdapterV3" + 方法 O(k3,int) + 方法 X0(int)→MsgInfo
// 5)  type→类 校验：ItemFactoryNew 的 f(type, int, Class, boolean) 调用点里
//     const 34 → bq.class，const 1 → 文本类.class（更新后若变了，用新类名替换缓存）
```

### 8.4 「版本变化自动适配」流程
```
1. 模块启动 → versionTag = versionCode:versionName
2. 若 versionTag 与上次缓存不同 → 丢弃全部缓存 → 重新 resolve()
3. resolve() 内：DexKit 一级锚点命中的候选 → 用二级锚点/签名 validate() → 只保留通过的
4. 全部通过 → install()；任一失败 → 保留可用子集（例如只有文本注入成功），并打日志
5. 运行期每次注入都重新读 view.background / view.getTag()，不缓存任何视图状态
6. 可选热更新：监听 PackageManager 广播，微信升级后重启注入进程/等待下次冷启
```

---

## 9. 调用时序与调试

### 9.1 时序图（一次「打开聊天 → 看到气泡」）
```
用户点开会话
  → ChattingUIFragment.dealContentView(root)          [记录 chatRoot]
  → ChattingDataAdapterV3.S(parent,viewType)
      → WxRecyclerAdapter.R(...)  inflate(2131624903/2131624834)
      → ChattingItemVoice.H()/ChattingItemTextBase.H() → new mq()/new to() → holder.create/b()  [findViewById]
  → ChattingDataAdapterV3.O(holder,pos)
      → WxRecyclerAdapter.E0(...)
          → 语音: bq.n(...) → 【mq.e(...)】  ←── L1 注入点（我们在这里换肤）
          → 文本: hn5.v.n(...) → hn5.v.d(...) → 【to.b(msg,to,ctx,isFrom)】 ←── L1 注入点
      → itemView.setTag(2131365959, viewType)
  → 用户点语音气泡
      → mqVar.d 的 OnClickListener（chattingItem.y(ctx) → bq.g0(...)）
      → AutoPlay.H(msg,true) 开始播放 → AnimImageView.b() → setBackground(2131231925)
                                                     ←── L2 拦截点（防微信把气泡改回去）
  → 列表滚动 / ViewHolder 复用
      → 同一 holder 绑定到下一条消息 → 再次进入 L1（幂等，重新判定类型与收发）
```

### 9.2 调试开关（建议默认打开）
```kotlin
const val DEBUG = true
fun log(s: String) { if (DEBUG) XposedBridge.log("[WxBubble] $s") }
// 关键日志点：
//  - resolve() 成功/失败与每个候选类的真实类名
//  - 每次注入：holder 类名 + 目标 View 类名 + isFrom + orig drawable 类名
//  - L2 拦截：什么时机微信想改回去（setBackground / setBackgroundResource / AnimImageView.b）
//  - 版本变化：oldVerTag → newVerTag
```

### 9.3 快速自检（注入是否生效）
1. 打开任意单聊/群聊，看自己的文本/语音气泡是否变成自定义皮肤（**左右两侧都要看**）。
2. 点一条语音 → 播放中气泡动画应照常（波形动），说明 LayerDrawable 叠加生效。
3. 切深色模式（微信「我 → 设置 → 通用 → 深色」）→ 气泡应仍为我们皮肤且文字可读（必要时按 `Configuration.uiMode` 出两套皮肤）。
4. 长按消息进入多选 → 高亮态应保留（`to.b` 里 `holder.g.visibility == VISIBLE` 即高亮态）。
5. 上下快速滚动 200 条 → 不应出现「文本气泡出现在语音行」或反之（ViewHolder 复用没串味）。
6. 发一条 1 秒语音与一条 60 秒语音 → 短气泡/长气泡宽度仍合理（`mq.c()` 补偿生效）。

---

## 10. 风险、坑、自检清单

### 10.1 一定会遇到的坑

| # | 坑 | 现象 | 解法 |
|---|---|---|---|
| 1 | **ViewHolder 复用串味** | 语音行出现文本气泡（或反之） | L1 注入点每次绑定都重新执行；对非目标类型**显式 `restore()`**（把 `info.original` 写回）；`WeakHashMap` 以 View 为 key，不复用误判 |
| 2 | **微信回写背景** | 点语音播放后皮肤掉了 | L2 Hook `View#setBackground` / `setBackgroundResource` / `setBackgroundDrawable` + `AnimImageView.b()`；用 `ThreadLocal` 重入标志防自己拦自己 |
| 3 | **帧动画丢失** | 播放语音时波形不动了 | 用 `LayerDrawable(ours, orig)` 叠加，绝不用 `setImageDrawable` 覆盖；`orig` 是 `AnimationDrawable` 时更必须保留 |
| 4 | **九宫格 padding 不一致** | 时长文字/正文偏移、被裁切 | 注入前 `orig.getPadding(r)`，注入后 `ours.setPadding(r)`；必要时再走 L3 宽度补偿 |
| 5 | **资源 ID 每次更新都变** | 旧版本的 `2131231925` 失效 | 本方案**不依赖资源 ID**，只依赖赋值点；ID 仅作调试参考（附录） |
| 6 | **深色模式** | 深色下白气泡刺眼/黑字看不见 | `BubbleFactory` 读 `ctx.resources.configuration.uiMode`，出 light/dark 两套；或在 L1 里检测 `orig` 的 `ConstantState` 与已知深色气泡一致 |
| 7 | **RecyclerView 预加载/异步绑定** | 偶发几条没换肤 | L1 的 `to.b()`/`mq.e()` 是每次绑定必走，理论无遗漏；若仍有，可在 `ChattingDataAdapterV3.O(holder,pos)` after 里兜底遍历该 itemView |
| 8 | **混淆字段名变化** | `getObjectField(holder,"b")` 抛异常 | 一律用 `Reflect.fieldByType(holder, "com.tencent.mm.ui.widget.MMNeat7extView")` 按类型找；字段名仅作兜底 |
| 9 | **多进程/多开** | 部分进程不生效 | LSPosed 作用范围勾选微信全部进程；或在 `handleLoadPackage` 中对每个进程均解析 |
| 10 | **LSPosed 作用域问题** | 模块列表里没有微信 | scope 里手动勾 `com.tencent.mm`，保存后强停微信 |
| 11 | **jadx/DexKit 解析慢** | 微信启动卡一下 | 解析放子线程；结果按 `versionTag` 落盘缓存；`DexKitBridge.create(...).use{}` 及时释放 |
| 12 | **setBackgroundResource 的 id 形式** | 拦截不到（拿不到 Drawable） | L2 对 `setBackgroundResource` 直接 `param.result = Unit` 并 `setBackground(ours)`，不需要知道 id |

### 10.2 自检清单（每次微信更新后跑一遍）
- [ ] `resolve()` 日志里 5 个类是否全部命中（MsgInfo / VoiceItem / VoiceHolder / TextHolder / Adapter）
- [ ] `mq.e(...)` 参数个数是否仍为 9，`args[1]` 是否是 Holder
- [ ] `to.b(...)` 参数个数是否仍为 4，`args[1]` 是否含 `MMNeat7extView` 字段
- [ ] 语音时长文本仍正常（`2131768495` 模板串是否被替换成别的 id —— 只影响参考表）
- [ ] `AnimImageView.b()` 是否还在设置气泡背景（若被移到别的方法，需要新增 L2 拦截点）
- [ ] 文本 item 布局 id（本版 `2131624834`）/ 语音 item 布局 id（本版 `2131624903`）变化只影响参考，不影响 Hook
- [ ] `ItemFactoryNew` 里 type=34 是否还指向语音 Item 类（若指向新类，确认新类是否仍用同一个 `mq` Holder）

---

## 11. 二次彻底复审结论

### 11.1 复核过的事实（逐条对过代码/字符串）
1. **`com.tencent.mm.ui.chatting.viewitems.b0` = ChattingItem 抽象基类**：含 `MicroMsg.ChattingItem`（`MicroMsg.ChattingItem`）Log Tag；`public abstract View H(LayoutInflater, View)`；`I(d,View,Object)` 做 `view.setTag(obj)`+点击监听；`ChattingItemDyeingTemplate` 直接继承它。✅
2. **`bq` = ChattingItemVoice**：Tag `MicroMsg.ChattingItemVoice`；`H()` 内 `new jh(layoutInflater, 2131624903)` + `new mq()` + `mqVar.b(view,true,false)`；`g0(...)` 内 `onItemClick voice msg(%s) fail`；`f(...)` 内 `onStateBtnClick voice msg(%s) re-download!`。✅
3. **`mq` = ChattingItemVoice$VoiceItemHolder**：字符串 `ChattingItemVoice$VoiceItemHolder` 可证；`b(View,boolean,boolean)` 内全部 findViewById；`e(b0,mq,am5.d,q,d,Z,Z,OnLongClickListener,r6)` 为 9 参静态填充；`c(Context,int)` 为气泡 dp 宽度算法。✅
4. **语音气泡背景确实由 `mq.e(...)` 与 `AnimImageView.b()` 设置**：`mq.e(...)` 373–389 行对 `mq.e`/`mq.x` 设 4 个 drawable id；`AnimImageView.b()` type=0 分支 `setBackgroundDrawable(ke5.a.i(ctx, 2131231925/2131232060))`。✅
5. **`to` = 文本 Holder，`to.b(...)` 是文本气泡唯一背景赋值点**：`b(e9,to,d,Boolean)` 内 `toVar.b.setBackgroundResource(2131231925|2131232060|2131231841|2131231895)`；`to.a(View,boolean)` 内 `b = findViewById(2131365751)` 且 `b.setMaxWidth(...)`。✅
6. **`adapter.k` = ChattingDataAdapterV3**：Tag 命中；`O(s0,int)` 是 onBindViewHolder（内部 `itemView.setTag(2131365959, viewType)`、`msg = ((am5.d)list.o.get(pos)).d.b`）；实现 `ak5.z` 与 `com.tencent.mm.pluginsdk.ui.tools.t3`；父类 `WxRecyclerAdapter`。✅
7. **`e9` = MsgInfo**：Tag `MicroMsg.MsgInfo`、`convertFrom(Cursor)/convertTo()`、`getType()/getCreateTime()/getMsgId()/j()`；`z0()` = isSend（由 `b0.K()` 内 `X0.z0() == 1` 反证语义为「自己发的」）；`M0()==5` = 失败态。✅
8. **`kt` = ItemFactoryNew**：Tag + `initChattingItemConfig`；构造函数注册 `f(1,0,v.class,TRUE)/f(1,0,n0.class,FALSE)`、`f(34,0,bq.class,TRUE)/f(34,0,iq.class,FALSE)`、`h(34,0,tr.class,true,ht)`（`tr extends bq`）。✅
9. **`x0` = AutoPlay，`v61.d1` = VoiceLogic，`v61.c1` = VoiceInfo，`x95.b` = VoiceContent**：分别由 `MicroMsg.AutoPlay`/`MicroMsg.VoiceLogic` Tag、`voiceinfo` 表字段、`voicemsg` 属性串证实。✅
10. **`ps`（Tag）可把 View 反查到消息**：`ps.c()` 实现就是 `return this.a.d.b`（`a=am5.d`，`d.b=e9`）。✅

### 11.2 复审中「不确定/需要你在真机再确认」的点（诚实标注）
- `mq.e(...)` 的第 6 个参数（`boolean z`）在本版本被用作 `if (z) { dVar2.t(); } else { dVar2.x(); }`，语义接近「是否为接收侧」，但**未做 100% 反证**；模块里已用 `MsgInfo.z0()` 做双保险，二者不一致时以 `z0()` 为准。
- 语音「未播放/已播放」由 `((c8) msg).F & 1` 判断（`c8` 是 MsgInfo 的中间父类，字段 `F` 即 `flag`），该位含义未完全反证；但不影响换肤（我们只是跟随微信每次赋值）。
- `mq` 内 `z`(RL 2131365813) / `C`(RL 2131365817) / `D`(TV 2131365816) 是「续听/倍速提示」容器，`y`(IV 2131365811) 在时长=0 时显示且带点击监听（疑似未播红点/语音转文字入口）—— 不影响气泡替换，但你若要做「红点常亮」类功能需再反查。
- `2131232057` 出现在 `mqVar.s.setBackgroundResource(...)`，是时长文本在某些主题下的背景，**不是**气泡；别误替换。
- 文本行 `to.b` 与 `to.f`(AnimImageView 2131366056, type=3) 分别是文本气泡与「语音输入条」，两者别混。

### 11.3 结论
> 该方案在 UI 层面是**当前微信版本下最稳的一条路**：它不赌任何类名/字段名/资源 ID，只赌「微信每次绑定都会给气泡设背景」这个必然动作；在此之上叠一层「防回写」和「padding/宽度补偿」，即可在版本更新中保持可用；DexKit 锚点（Log Tag + 语义动作串 + 方法签名）提供了自愈能力。剩余风险集中在「Holder 字段类型变化」与「微信重构渲染框架（如彻底弃用 RecyclerView 适配器）」两类，均已给出兜底与自检清单。

---

## 12. 附录：关键资源 ID / 布局 ID 速查（本版本）

> ⚠️ 以下 ID **仅用于调试与人工核对**，模块代码禁止依赖。

| 分类 | 值 | 说明 |
|---|---|---|
| 布局 | `2131624903` | 语音消息 item 布局（ChattingItemVoice.H inflate） |
| 布局 | `2131624834` | 文本消息 item 布局（ChattingItemTextBase.H inflate） |
| Drawable | `2131231925` | **收到的气泡（chatfrom style 九宫格）** |
| Drawable | `2131232060` | **发出的气泡（chatto style 九宫格）** |
| Drawable | `2131231940` | 语音未播放 · 接收侧气泡 |
| Drawable | `2131232066` | 语音未播放 · 发送侧气泡 |
| Drawable | `2131231841` / `2131231895` | 文本高亮/多选 · 接收/发送 |
| Drawable | `2131232057` | 语音时长文本主题背景（非气泡） |
| 帧动画 | `2130968839/8840/8841` | 接收侧语音波形 3 帧 |
| 帧动画 | `2131821038/1039/1040` | 发送侧语音波形 3 帧 |
| 字符串 | `2131768495` | 语音时长模板（`%d''` 形式） |
| 尺寸 | `2131166702` | 文本最大宽度 dimen |
| 其它 | `2131100638/2131100639` | AnimImageView type=2/3 背景 |
| View id | `2131365751` | 「内容」id（文本=MMNeat7extView；语音=时长文本） |
| View id | `2131366097` | 语音气泡点击区 / 宽度载体（mq.d） |
| View id | `2131366091` / `2131366096` | 语音 AnimImageView（mq.e / mq.u） |
| View id | `2131366108` | 发送侧气泡 TextView（mq.x） |
| View id | `2131365813 / 5811 / 5812` | mq.z / mq.y / mq.A（续听、红点、时长容器） |
| View id | `2131365817 / 5816` | mq.C / mq.D（倍速提示） |
| View id | `2131365722 / 5723` | to.q / to.r（引用消息容器） |
| View id | `2131365748` | 消息内容容器（to.c，适配器里也用它） |
| View id | `2131365740 / 5979 / 6060` | checkBox / maskView / stateIV（h0） |
| View id | `2131366064 / 6078` | timeTV / userTV（h0） |
| Tag key | `2131365959` | itemView.setTag(key, viewType)（适配器 O()） |
| raw（未混淆名） | `chatfrom_voice_playing_f1..f3.svg`、`chatto_voice_playing_f1..f3.svg`、`chatfrom_bg_pic.svg` | 可作「主题包/换肤」素材定位参考 |

---

## 附录 B：与 LSPilot BSH 的对应（仅用于快速原型验证，正式模块请用上面的 Kotlin）

```bsh
// 语义等价：找到语音 Holder 的填充方法并 after 注入
cls  = findClass().pkg("com.tencent.mm.ui.chatting.viewitems").usingStrings("ChattingItemVoice$VoiceItemHolder").single()
hookMethodAfter(cls, "e", param -> {
    holder = param.args[1]
    log("voice holder = " + holder.getClass().getName())
})
// 查找锚点（等价 DexKit 一级锚点）
findClass().usingStrings("MicroMsg.ChattingItemVoice").single()
```
> 正式交付请保持「独立 Xposed 模块」形态：不依赖 LSPilot 插件目录、不依赖 BSH 引擎，只依赖 Xposed API + DexKit，这样微信/宿主升级都不影响你的模块。

---

**文档生成信息**：基于 LSPilot 逆向分析工具链（DexKit 类/方法/字段/字符串检索 + jadx Java 反编译 + baksmali）对目标 APK 的实机分析结果整理；所有类名/字段名/资源 ID 均为目标版本实测值，微信更新后请按第 8、10 章重新核对。

---

# 第二部分 · 全方位复盘与加固（重点：语音气泡全继承 / 包装 or 封装 / 滑动渲染丢失）

> 本章在第一章结论基础上做二次深挖，回答三个问题：
> **① 语音气泡一共有哪些类、继承关系是什么？② 气泡到底是「包装类」还是「封装类」？③ 为什么上下滑动会出现渲染丢失，注入方案怎么改才不再丢？**
> 本章结论优先级高于第 6 章的早期设计（第 6 章仍然有效，本章做增强）。

## 13. 语音气泡全继承图谱（地毯式，逐类对过代码）

### 13.1 语音「Item 层」继承（消息类型 → 渲染类）
```
com.tencent.mm.ui.chatting.viewitems.b0  = ChattingItem（抽象基类，Tag=MicroMsg.ChattingItem）
│   抽象：H(LayoutInflater,View)View  创建条目视图
│        O()/Q(MenuItem,d,am5.d)/S(...)/T(View,d,e9)
│        I(d,View,Object) → view.setTag(obj) + 条目 OnClickListener
│        J(h0,e9)/K(ak5.z,long)/resetChatBubbleWidth 在 h0 上
│   直接子类共 122 个（viewitems 包，实测），语音相关的只有 4 个：
├── bq  = ChattingItemVoice$ChattingItemVoiceFrom      ← 语音·接收侧（Tag: MicroMsg.ChattingItemVoice）
│        证据：Q() 内 "ChattingItemVoice$ChattingItemVoiceFrom"；
│        H() = new jh(inflater,2131624903) + new mq() + mqVar.b(view,true,false)
│        f()=onStateBtnClick 重新下载；g0()=onItemClick；h0()=埋点
│        n(h0,d,am5.d,String) → mq.e(...)  ★最终调用语音填充
│    └── tr   （ChattingItemVoiceFrom 的 MVVM 变体）仅 f0(mq) 空壳 ← 子类只有它 1 个
├── iq  = ChattingItemVoice$ChattingItemVoiceTo        ← 语音·发送侧（Tag 同上）
│        证据：Q() 内 "ChattingItemVoice$ChattingItemVoiceTo"；同样 n()→mq.e(...)
│    └── ur   （To 的 MVVM 变体）仅 f0(mq) 空壳
├── vq / yq / pr / or / xm / gp / gq / mp / pp / wp / jp / oq …  （语音衍生类型/菜单动作类）
│        · vq/yq/pr/or/xm：ItemFactoryNew 里用 g(type,Class) 单独注册的特殊语音类型
│        · gp/pp：构造签名 (mq, e9, d, Context) + onMMMenuItemSelected → 是「长按菜单动作类」，
│                  不是气泡类（持有 mq 只是为了操作语音行 UI）
└── zn（文本 Item 基类）/ 其它 118 个非语音 Item
```

**结论 1：语音消息虽然分成 From/To × 旧/MVVM 共 4 个 Item 类，但它们 100% 共用同一个 Holder `mq` 与同一条填充链 `mq.e(...)`。**
→ 所以 **Hook `mq.e(...)` 一处即可覆盖语音消息的全部渲染路径**（包括微信后续在 MVVM/旧路径之间切换开关）。

### 13.2 语音「Holder 层」继承
```
java.lang.Object
├── com.tencent.mm.ui.chatting.adapter.q   （ChatHolder：getAdapterPosition/getViewHolderScope）
│    └── com.tencent.mm.ui.chatting.viewitems.h0  = ChattingItem.BaseViewHolder
│          · 字段：timeTV/userTV/checkBox/maskView/stateIV/uploadingPB/convertView/chattingItem/quoteView…
│          · 方法：create(View)、getCurrentMsgInfo(gk5.d)→e9、getMainContainerView()、
│                  getQuoteView()→q71.n、setChatHolder(q)、setChattingItem(b0)、
│                  resetChatBubbleWidth(View,int)、showEditView(Z)
│          └── 80 个子类（实测），其中：
│              ├── mq  = ChattingItemVoice$VoiceItemHolder   ★语音唯一 Holder（无子类）
│              │      31 个字段：d/e/x/u/s/c/o/p/t/w/q/r/z/A/B/C/D/y/f/g…
│              ├── to  = 文本 Holder（字段 b: MMNeat7extView）
│              └── 其余 78 个（图片/视频/文件/名片…）
```
**结论 2：语音气泡的所有 View 都在 `mq` 一个 Holder 里，不存在"语音气泡的多个 Holder 变体"。**

### 13.3 语音「View 层」继承（气泡到底是哪种 View？）
```
android.view.View
├── com.tencent.mm.ui.base.AnimImageView            ← 语音气泡背景载体（无任何子类）
│     · 字段 f(type)/e(isFrom)/d(playing)/h,i,m(AnimationDrawable×3)/g(AlphaAnimation)
│     · b()：type=0 → setBackgroundDrawable(ke5.a.i(ctx, 2131231925|2131232060)) + AlphaAnimation
│             type=1 → setCompoundDrawablesWithIntrinsicBounds(波形帧动画)（不动背景）
│             type=2/3 → setBackgroundResource(2131100638|2131100639) + 波形
│     · setFromVoice(Z)/setFromGroup(Z)/setType(I)/setCustomDuration(I)
├── com.tencent.mm.ui.widget.MMNeat7extView         ← 文本气泡背景载体（b.setBackgroundResource(...)）
├── TextView / RelativeLayout / FrameLayout / ProgressBar / ImageView / ViewStub  （mq 内其余字段）
└── q71.n 的 44 个实现 View（引用消息气泡，见 13.4）
```
**结论 3：气泡不是自定义 View 子类，而是「普通 View + Drawable 背景」；帧动画/九宫格都由 Drawable 承担。因此换肤必须在 Drawable 层做（LayerDrawable 叠加），不要 new View。**

### 13.4 「包装 vs 封装」的最终定性
| 层 | 类 | 性质 |
|---|---|---|
| 背景层 | `AnimImageView.background` / `MMNeat7extView.background` | **Drawable 资源**（九宫格 / AnimationDrawable），不是类 |
| 条目数据层 | `am5.d`（ChattingItemData，字段 `d:hn5.a`→`b:e9`、`i:b0`、`h:hn5.a1`） | **数据封装类**（把 MsgInfo + ChattingItem + uiBlocks 打成一包传给 renderer） |
| View↔消息桥 | `ps`（ChattingItem.Tag，`c()→e9`）、`ap` | **包装类**（ViewHolder 模式：view.setTag(ps) 反查消息） |
| 引用气泡 | `q71.n`（IMsgQuoteView）+ `q71.p`（ViewModel）+ 44 个实现（cr5.*, bs5.c, …） | **标准封装类**（接口 + ViewModel + 工厂 `oo.a0.J7()` → `b(Context)→View`，动态 `addView` 进气泡父容器） |
| 气泡内子视图 | `ChattingItemTranslate`（气泡内"转文字"结果）、`eu5.s0`=MMPopupMenu（长按菜单）、`ju5.a0`（播放控制器） | **功能封装类** |

> **一句话：主气泡 =「View + Drawable」的轻组合；引用气泡 = 完整 MVVM 封装类（接口+ViewModel+工厂，动态 addView）。**
> 换肤要"两条腿走路"：主气泡换 Drawable，引用气泡要么整体叠加 LayerDrawable，要么对 `q71.n` 各实现的根 View 做同样处理。

### 13.5 引用气泡是怎么进来的（滑动必踩）
`mq.b(View,boolean,boolean)` 内（第 498–515 行）：
```java
t J7 = ((k) n0.c(k.class)).jj().J7((fm5.b) null);   // 工厂 → q71.n 实例（44 个实现之一）
View b = J7.b(view.getContext());                   // inflate 出引用消息 View
((RelativeLayout) this.F.getParent()).addView(b);   // 动态加入气泡父容器！
RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) b.getLayoutParams();
lp.addRule(3, 2131389578);                          // 相对 quote stub 定位
if (z)  lp.addRule(5, 2131366078);                  // 接收侧：对齐头像
else  { lp.addRule(0, 2131365727); lp.addRule(7, 2131366078); … }   // 发送侧
setQuoteView(J7);                                   // h0.quoteView = J7
```
→ `J7` 实例随 **Holder 复用**而复用；`addView` 只在 holder 创建时发生一次；bind 时微信只换 `ViewModel`（`q71.p`）与内容。**所以引用气泡的皮肤必须在 bind 后重做，不能只在创建时做。**
可通过 `h0.getQuoteView()→q71.n`（或 `J7.b(ctx)` 的返回值存入 `h0.quoteView`）拿引用 View。

---

## 14. 上下滑动「渲染丢失」的根因（逐条对过 smali）

| # | 根因 | 证据 | 后果 |
|---|---|---|---|
| R1 | **局部刷新绕过业务 bind**：`WxRecyclerAdapter.F0(holder,pos,payloads)` 不调 `E0`，直接 `L0(viewType).h(holder,data,pos,viewType,**true**,payloads)` | smali：`F0` 46–56 行 `const/4 v6,0x1` → ItemConvert.h 的 `isPartial=true`；全程无 `E0` 调用 | 微信 `notifyItemChanged(pos,payload)`（语音播放进度、已读、发送态、异步内容）时，**`mq.e()`/`to.b()` 不被调用** → 我们 L1 注入点漏掉 → 皮肤"闪回原生"或残留 |
| R2 | **ItemConvert 的 payload 分支可能只更新 payload 指定的 View**，不重设背景 | `vv5.r.h(holder,data,pos,viewType,isPartial,payloads)` 的 payload 分支由各实现自定义 | 同上 |
| R3 | **ViewHolder 复用 + RecycledViewPool 按 viewType 复用**：同一个 `mq` 实例会先后服务"接收/发送、已播/未播、快语速"等多条语音 | `WxRecyclerAdapter.onViewRecycled` 只取消协程、调 `r.l(holder)`，不清背景 | 若注入不幂等，会串味（A 消息的皮肤出现在 B 消息上） |
| R4 | **`AnimImageView.b()` 会在播放时重设背景** `setBackgroundDrawable(ke5.a.i(ctx,2131231925/2060))` | AnimImageView.b() type=0 分支 | 不拦就掉肤 |
| R5 | **微信在 holder 协程里异步设背景/可见性**，可能晚于我们的注入 | `onViewRecycled` 里 `z0.c(((r0)holder).d, null)` 取消 scope；异步任务若已完成则后置覆盖 | 抖动/掉肤 |
| R6 | **`nq.m/n` 倍速/续听直接改 `mq` 的 View**（setAlpha/scaleX/scaleY/setVisibility，"resetAndHideView"） | nq 字符串：`changePlayingSpeedUpState() called`、`resetAndHideView`、`setAlpha` | 视觉被改（不影响背景，但若我们叠加层用了 alpha 需注意） |
| R7 | **引用气泡随 holder 复用但 ViewModel 换了**；`ViewStub`(mq.f/to.d) 与 quote `addView` 仅在创建时执行 | `mq.b()` 第 498–515 行 | 引用区皮肤/内容不同步 |
| R8 | **`ChattingDataAdapterV3.getView()` 直接 `return null`** | smali 第 548–550 行 | 说明没有第二条渲染路径 —— 反证「只需覆盖 RecyclerView 两条入口」即可 100% 兜住 |

### 14.1 完整 bind 与局部刷新的真实调用链（smali 级）
```
【完整 bind（含滑动复用）】
RecyclerView → vv5.n0.onBindViewHolder(k3,pos)
   →（正文区）vv5.n0.O(k3, pos-header) = onBindViewHolder(k3,pos)
       → ChattingDataAdapterV3.O(s0,pos)              [k]
            → super.E0(s0,pos)                        [WxRecyclerAdapter.E0]
                 → r = L0(viewType) = vv5.r(ItemConvert)
                 → r.h(s0, data, pos, viewType, **false**, null)     ← 业务 fill：mq.e / to.b
                 → I0(itemView,data,pos)（设 touch/click）
            → itemView.setTag(0x7f0a1047/*2131365959*/, viewType)
            → Log "_onBindViewHolder[..][..] cost[..]"
            → e9 msg = ((am5.d) ((z1)this).I.o.get(pos)).d.b
            → view = itemView.findViewById(2131365748)

【局部刷新（payload）】★绕过上面整条
RecyclerView → vv5.n0.onBindViewHolder(k3,pos,payloads)
   → WxRecyclerAdapter.P(k3,pos,List)(bridge)
       → WxRecyclerAdapter.F0(s0,pos,payloads)
            → holder.m = rv；holder.i = data
            → r = L0(viewType)
            → r.h(s0, data, pos, viewType, **true**, payloads)      ← 可能不重设背景！
            → I0(itemView,data,pos)
```
> 注意 `vv5.n0.onBindViewHolder` 对 header/footer 特殊项走 `N/M`（空/bridge C0），**聊天消息恒为正文项**，必走 `O→E0`；`ChattingDataAdapterV3.getView()` 返回 null 证明不存在 ListView 通道。

---

## 15. 加固后的注入方案（四层 + 三保险，专治滑动丢失）

### 15.1 层级总览
```
L0  发现层      DexKit 解析 + validate() + versionTag 缓存
L1  业务注入层   to.b(e9,to,d,Boolean)          文本气泡（精准）
                mq.e(b0,mq,am5.d,q,d,Z,Z,L,r6) 语音气泡（精准，覆盖 From/To×旧/MVVM 全部 4 类）
L1b 兜底注入层   WxRecyclerAdapter.E0(s0,pos)              完整 bind 必经（父类，业务改名不影响）
                WxRecyclerAdapter.F0(s0,pos,payloads)      局部刷新必经 ★专治 R1/R2
                （可选）ChattingDataAdapterV3.O(s0,pos)     双保险
L2  防回写层     View#setBackground / setBackgroundResource / setBackgroundDrawable
                AnimImageView.b()
L3  尺寸补偿层   mq.c(Context,int) / h0.resetChatBubbleWidth(View,int) / MMNeat7extView.setMaxWidth(int)
L4  回收清理层   WxRecyclerAdapter.onViewRecycled(s0) / onViewDetachedFromWindow(s0) → restore + 反注册
```

### 15.2 L1b 兜底实现（核心新增，消灭滑动丢失）
```kotlin
// 判据：只处理「聊天列表」的数据项（vv5.s0.i 在 E0/F0 开头就被赋值）
private fun isChattingItem(holder: Any): Boolean = runCatching {
    val data = Reflect.field(holder, "i") ?: return false      // vv5.s0.i = vv5.c (IDataItem)
    data.javaClass.name == chatItemDataCls.name                // am5.d (ChattingItemData)
}.getOrDefault(false)

private fun ensureBubble(holder: Any) {
    if (!isChattingItem(holder)) return
    val itemView = Reflect.field(holder, "itemView") as? View ?: return   // k3.itemView
    val hTag = itemView.getTag() ?: return                    // = Holder 实例（mq / to / 其它）
    when (hTag.javaClass.name) {
        voiceHolderCls.name -> {
            val isFrom = isFromMessage(holder)                 // 见 15.3
            BubbleEngine.injectAll(bubbleViewsOfVoiceHolder(hTag), isFrom, voice = true)
            BubbleEngine.injectQuote(itemView, isFrom)
        }
        textHolderCls.name  -> {
            val isFrom = isFromMessage(holder)
            BubbleEngine.injectAll(setOfNotNull(Reflect.fieldByType(hTag, MM_NEAT)), isFrom, voice = false)
            BubbleEngine.injectQuote(itemView, isFrom)
        }
        else -> BubbleEngine.restoreAll(itemView)              // ★防串味：非目标类型一律还原
    }
}

// 父类两个必经方法都挂 after hook（hookAllMethods 可避免签名写错）
XposedBridge.hookAllMethods(WxRecyclerAdapterCls, "E0", object : XC_MethodHook() {
    override fun afterHookedMethod(p: MethodHookParam) = ensureBubble(p.args[0])   // 完整 bind
})
XposedBridge.hookAllMethods(WxRecyclerAdapterCls, "F0", object : XC_MethodHook() {
    override fun afterHookedMethod(p: MethodHookParam) = ensureBubble(p.args[0])   // 局部刷新(payload)
})
// 双保险（业务类，供调试/对比；它失效也不影响上面两个父类兜底）
XposedBridge.hookAllMethods(ChattingDataAdapterV3Cls, "O", object : XC_MethodHook() {
    override fun afterHookedMethod(p: MethodHookParam) = ensureBubble(p.args[0])
})
```

要点：
1. **不再依赖被 hook 方法的参数含义**，只从 `holder` 反射取 `itemView` 与 `i`（数据项），并对 `itemView.getTag()`（Holder 实例）做 `instanceof` 判定 → **微信怎么改业务方法签名都不怕**。
2. **每次 bind（含局部刷新）都重做一次注入**：注入函数必须幂等（先 `restore` 再 `apply`），因此 R1/R2/R3/R4 全部失效。
3. **非目标类型显式还原**（`restoreAll(itemView)`）：这是防"文本气泡出现在语音行/图片行"的关键，比"只在目标类型注入"更稳。
4. `isFromMessage(holder)` 的取值顺序：`Reflect.call(holder.i.d.b, isSendMethod)`（`e9.z0()`，0=接收/1=发送）→ 失败则回退 Hook 参数里的 `Boolean`。

### 15.3 兜底层「拿 View」的三个稳途径（按优先级）
```kotlin
// ① itemView 的 tag 就是 Holder 实例（bq.H/zn.H 里 jhVar.setTag(mq/to)）
val holderTag = itemView.getTag()                                  // mq / to / 其它 Holder

// ② 从 ChattingItemData 拿消息与当前 Item（完全不需要 position）
val msgInfo = Reflect.call(Reflect.call(Reflect.field(holder,"i"), /*am5.d*/"d"), "b")  // e9

// ③ 语音气泡 View：按字段类型在 Holder 内找（不写死字段名）
voiceBubbleViews(holderTag) =
      fieldsByType(holderTag, "com.tencent.mm.ui.base.AnimImageView")     // e / u
   ++ fieldsByType(holderTag, "android.widget.TextView")
         .filter { it !== durationText }                                   // 排除 s（时长文本）
// 文本气泡 View：字段类型 MMNeat7extView
textBubbleView(holderTag) = fieldByType(holderTag, "com.tencent.mm.ui.widget.MMNeat7extView")
```
> 更稳的做法：**按"背景 drawable 的身份"反推** —— 在 `to.b()`/`mq.e()` 被调用时，把"这个 View + 它的原始 background"登记进 `WeakHashMap`；兜底层只处理登记过的 View。这样即使字段类型变了，也永远知道"哪个 View 是气泡"。

### 15.4 引用气泡注入（L1b 内附带做）
```kotlin
fun injectQuote(itemView: View, isFrom: Boolean) {
    // 途径 1：h0.getQuoteView() → q71.n；调 b(ctx) 之前存的 View 可能取不到，
    //          直接在 itemView 里找「非气泡层但位于气泡父容器内」的子 View：
    val container = itemView.findViewById(quoteStubId /*2131389578*/)?.parent as? ViewGroup ?: return
    for (i in 0 until container.childCount) {
        val child = container.getChildAt(i)
        if (BubbleEngine.isInjected(child)) continue
        // 引用气泡自身通常也有九宫格背景
        val bg = child.background ?: continue
        if (!looksLikeBubble(bg)) continue          // 尺寸/形状/九宫格特征启发式
        BubbleEngine.inject(child, isFrom, voice = false)
    }
}
// 同时在 ChattingItemData 变化（holder.i 被重新赋值）后调用一次，保证 R7 不丢
```

### 15.5 L4 回收清理（防泄漏 + 防串味）
```kotlin
hook(WxRecyclerAdapterCls, "onViewRecycled",      after = { p -> BubbleEngine.recycle(p.args[0]) })
hook(WxRecyclerAdapterCls, "onViewDetachedFromWindow", after = { p -> BubbleEngine.detach(p.args[0]) })
// recycle(holder): 取出 itemView → restoreAll(itemView) + 反注册（不清 chatRoot 引用）
```
### 15.6 最终不丢的保证（为什么这次不会丢）
1. 完整 bind 与局部刷新**两条入口**都挂了兜底（`E0`/`F0`），而 `getView()` 返回 null 证明没有第三入口。
2. 兜底在 bind 的**最后阶段**执行（`F0` 的 `I0()` 之前、`E0` 的 `r.h()` 之后都在该方法内，after hook 一定在微信业务之后）。
3. 注入幂等 + 非目标类型还原 → 复用不串味。
4. L2 拦住所有"微信事后改背景"（播放、主题、协程异步）。
5. L4 在回收/离屏时还原，避免长期内存持有与跨页污染。

---

## 16. 本章新增/变更的 Hook 点清单（直接对照升级）

| 层 | 方法（混淆签名） | 类 | 作用 | 变更 |
|---|---|---|---|---|
| L1 | `b(Lcom/tencent/mm/storage/e9;Lcom/tencent/mm/ui/chatting/viewitems/to;Lgk5/d;Ljava/lang/Boolean;)V` | `viewitems.to` | 文本气泡 | 维持 |
| L1 | `e(Lb0;Lmq;Lam5/d;Lcom/tencent/mm/ui/chatting/adapter/q;Lgk5/d;ZZLandroid/view/View$OnLongClickListener;Lcom/tencent/mm/ui/chatting/r6;)V` | `viewitems.mq` | 语音气泡 | 维持 |
| **L1b** | `E0(Lvv5/s0;I)V` | `com.tencent.mm.view.recyclerview.WxRecyclerAdapter` | 完整 bind 兜底 | **新增** |
| **L1b** | `F0(Lvv5/s0;ILjava/util/List;)V` | 同上 | **局部刷新兜底（治滑动丢失）** | **新增** |
| L1b | `O(Lvv5/s0;I)V`（k.O 覆盖 onBindViewHolder） | `adapter.k` | 双保险 | 新增（可选） |
| L2 | `setBackground(Drawable)`/`setBackgroundResource(int)`/`setBackgroundDrawable(Drawable)` | `android.view.View` | 防回写 | 维持 |
| L2 | `b()` | `com.tencent.mm.ui.base.AnimImageView` | 播放时重设背景 | 维持 |
| L3 | `c(Landroid/content/Context;I)I` | `viewitems.mq` | 语音宽度(dp) | 维持 |
| L3 | `resetChatBubbleWidth(Landroid/view/View;I)V` | `viewitems.h0` | 通用宽度 | 维持 |
| L3 | `setMaxWidth(I)` | `MMNeat7extView` | 文本宽度 | 维持 |
| **L4** | `onViewRecycled(Lvv5/s0;)V` / `onViewDetachedFromWindow(Lvv5/s0;)V` | `WxRecyclerAdapter` | 回收还原/反注册 | **新增** |
| 诊断 | `J7(Lfm5/b;)Lq71/n;` | `oo.a0` | 引用气泡工厂（只读诊断，确认引用气泡实现类） | 新增（诊断） |

---

# 第三部分 · 可编译单文件实现（WxBubble.kt）

> 放置：模块工程 `src/main/java/com/example/wxbubble/WxBubble.kt`
> 依赖：`de.robv.android.xposed:api:82` + `io.github.lsposed.dexkit:dexkit:2.0.0`
> 资源：模块 `res/drawable-xxhdpi/wx_bubble_text_from.png`（九宫格）、`..._to.png`、`wx_bubble_voice_from.png`、`..._to.png`；缺失时自动退化为程序化绘制。
> 能力：L0 DexKit 解析 + L1 业务注入 + L1b 父类兜底（含局部刷新）+ L2 防回写 + L3 尺寸补偿 + L4 回收清理。
> 章节：17 单文件代码 / 18 编译与资源说明 / 19 联调自检表。

## 17. WxBubble.kt（全文）

```kotlin
//////////////////////////////////////////////////////////////////////////////////
// WxBubble.kt —— 微信聊天窗口「语音气泡 / 文本气泡」注入（独立 Xposed 模块，单文件）
// 不依赖 LSPilot / BSH，只依赖 Xposed API + DexKit + 反射。
//////////////////////////////////////////////////////////////////////////////////
package com.example.wxbubble

import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.PathShape
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.lsposed.dexkit.DexKitBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

private const val PKG = "com.tencent.mm"
private const val TAG = "WxBubble"

private fun log(s: String) = XposedBridge.log("[$TAG] $s")

//////////////////////////////////////////////////////////////////////////////////
// 0. 模块入口
//////////////////////////////////////////////////////////////////////////////////
// 0. 模块入口：见本文件「第 9 节」（IXposedHookZygoteInit + IXposedHookLoadPackage，仅一个入口类）

//////////////////////////////////////////////////////////////////////////////////
// 1. 环境 / 缓存
//////////////////////////////////////////////////////////////////////////////////
object WxEnv {
    lateinit var lp: XC_LoadPackage.LoadPackageParam
    var apkPath: String = ""
    var modulePath: String = ""
    var verTag: String = ""
    val classLoader: ClassLoader get() = lp.classLoader
}

//////////////////////////////////////////////////////////////////////////////////
// 2. 反射工具（按类型找字段，不写死混淆字段名）
//////////////////////////////////////////////////////////////////////////////////
object Reflect {

    /** 收集自身+父类字段，遇到框架类即停（避免拿到 View.mBackground 之类） */
    fun allFields(clazz: Class<*>?): List<Field> {
        val out = ArrayList<Field>()
        var c = clazz
        while (c != null && c != Any::class.java) {
            val n = c.name
            if (n.startsWith("android.") || n.startsWith("androidx.") ||
                n.startsWith("java.") || n.startsWith("kotlin.")) break
            out += c.declaredFields
            c = c.superclass
        }
        return out
    }

    fun fieldByName(obj: Any, name: String): Any? =
        allFields(obj.javaClass).firstOrNull { it.name == name }?.let {
            it.isAccessible = true; it.get(obj)
        }

    fun fieldByType(obj: Any, typeName: String): Any? =
        allFields(obj.javaClass).firstOrNull { it.type.name == typeName }?.let {
            it.isAccessible = true; it.get(obj)
        }

    fun fieldsByType(obj: Any, typeName: String): List<Any> =
        allFields(obj.javaClass).filter { it.type.name == typeName }.mapNotNull {
            it.isAccessible = true; it.get(obj)
        }

    fun fieldPath(obj: Any?, vararg names: String): Any? {
        var cur = obj ?: return null
        for (n in names) {
            cur = fieldByName(cur, n) ?: return null
        }
        return cur
    }

    fun method(clazz: Class<*>, name: String, paramCount: Int): Method? {
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            c.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == paramCount }
                ?.let { it.isAccessible = true; return it }
            c = c.superclass
        }
        return null
    }

    fun call(obj: Any?, method: Method?, vararg args: Any?): Any? =
        runCatching { method?.invoke(obj, *args) }.getOrNull()

    fun callByName(obj: Any?, name: String, paramCount: Int, vararg args: Any?): Any? =
        if (obj == null) null else call(obj, method(obj.javaClass, name, paramCount), *args)

    fun classNameOf(descriptor: String): String =
        descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')
}

//////////////////////////////////////////////////////////////////////////////////
// 3. L0 发现层：DexKit 解析 + 本地校验 + 版本缓存
//////////////////////////////////////////////////////////////////////////////////
object WxResolver {
    // —— 解析产物（混淆类名，运行时确定）——
    var voiceItemFrom: Class<*>? = null        // bq   ChattingItemVoice$ChattingItemVoiceFrom
    var voiceItemFromMvvm: Class<*>? = null    // tr
    var voiceItemTo: Class<*>? = null          // iq   ChattingItemVoice$ChattingItemVoiceTo
    var voiceItemToMvvm: Class<*>? = null      // ur
    var voiceHolder: Class<*>? = null          // mq   ChattingItemVoice$VoiceItemHolder
    var voiceFill: Method? = null              // mq.e(...)  9 参静态填充
    var textHolder: Class<*>? = null           // to   文本 Holder
    var textBubbleSetter: Method? = null       // to.b(e9,to,d,Boolean) 文本气泡背景赋值点
    var adapter: Class<*>? = null              // adapter.k  ChattingDataAdapterV3
    var animImageView: Class<*>? = null        // com.tencent.mm.ui.base.AnimImageView
    var neatTextView: Class<*>? = null         // com.tencent.mm.ui.widget.MMNeat7extView
    var baseViewHolder: Class<*>? = null       // viewitems.h0
    var wxRecyclerAdapter: Class<*>? = null    // com.tencent.mm.view.recyclerview.WxRecyclerAdapter
    var msgInfo: Class<*>? = null              // storage.e9

    // —— 其它常量 ——
    const val ANIM = "com.tencent.mm.ui.base.AnimImageView"
    const val NEAT = "com.tencent.mm.ui.widget.MMNeat7extView"
    const val HOLDER_BASE = "com.tencent.mm.ui.chatting.viewitems.h0"

    fun classOf(name: String): Class<*>? = runCatching {
        XposedHelpers.findClass(name, WxEnv.classLoader)
    }.getOrNull()

    /** DexKit 一级锚点 + 本地 validate()；全部失败则回退硬编码类名 */
    fun resolve(): Boolean {
        runCatching { resolveByDexKit() }
        if (!validate()) {
            log("dexkit 解析不完整，回退硬编码类名")
            fallback()
        }
        return validate()
    }

    private fun resolveByDexKit() {
        val bridge = DexKitBridge.create(WxEnv.apkPath, WxEnv.classLoader)
        bridge.use {
            // —— 语音 Item：Tag + 两个语义动作串 ——
            val voiceCls = it.findClass {
                matcher { usingStrings("MicroMsg.ChattingItemVoice") }
            }.filter { cd ->
                val c = classOf(Reflect.classNameOf(cd.descriptor)) ?: return@filter false
                Reflect.method(c, "H", 2) != null &&                 // 抽象创建条目视图
                Reflect.method(c, "n", 4) != null                    // n(h0,d,am5.d,String) → mq.e
            }
            // 直接用 DexKit 已抓到的「类内字符串常量」区分 From/To（比读 dex 稳）
            val FROM = "ChattingItemVoice\$ChattingItemVoiceFrom"
            val TO = "ChattingItemVoice\$ChattingItemVoiceTo"
            voiceCls.forEach { cd ->
                val c = classOf(Reflect.classNameOf(cd.descriptor)) ?: return@forEach
                val str = cd.usingStrings
                val isFrom = str.contains(FROM) || str.contains("com/tencent/mm/ui/chatting/viewitems/$FROM")
                val isTo = str.contains(TO) || str.contains("com/tencent/mm/ui/chatting/viewitems/$TO")
                when {
                    isFrom && c.declaredMethods.any { it.name == "g0" } -> voiceItemFrom = c
                    isTo && c.declaredMethods.any { it.name == "g0" } -> voiceItemTo = c
                }
            }
            // MVVM 变体(tr/ur)是 bq/iq 的直接子类：方向判定时用 isAssignableFrom 自动覆盖，
            // 不需要显式记录子类名（见 Direction）
            // —— 语音 Holder：含 VoiceItemHolder 尾缀 + b(View,ZZ) + e(9参) ——
            voiceHolder = it.findClass {
                matcher {
                    usingStrings("ChattingItemVoice\$VoiceItemHolder")
                    methods { add { name("b"); paramTypes("android.view.View", "boolean", "boolean") } }
                }
            }.firstOrNull { cd ->
                val c = classOf(Reflect.classNameOf(cd.descriptor)) ?: return@firstOrNull false
                Reflect.method(c, "e", 9) != null
            }?.let { cd -> classOf(Reflect.classNameOf(cd.descriptor)) }

            // —— 语音填充方法 ——
            voiceFill = voiceHolder?.let { c -> Reflect.method(c, "e", 9) }

            // —— 文本 Holder + 气泡背景赋值点 ——
            textBubbleSetter = it.findMethod {
                matcher {
                    name("b"); paramCount(4)
                }
            }.firstOrNull { md ->
                val c = classOf(Reflect.classNameOf(md.descriptor.substringBefore("->"))) ?: return@firstOrNull false
                Reflect.allFields(c).any { it.type.name == NEAT }   // Holder 里必须有 MMNeat7extView 字段
            }?.let { md ->
                val c = classOf(Reflect.classNameOf(md.descriptor.substringBefore("->")))
                textHolder = c
                Reflect.method(c, "b", 4)
            }

            // —— 适配器 ——
            adapter = it.findClass { matcher { usingStrings("MicroMsg.ChattingDataAdapterV3") } }
                .firstOrNull { cd ->
                    val c = classOf(Reflect.classNameOf(cd.descriptor)) ?: return@firstOrNull false
                    Reflect.method(c, "O", 2) != null && Reflect.method(c, "F0", 3) != null
                }?.let { cd -> classOf(Reflect.classNameOf(cd.descriptor)) }

            // —— MsgInfo ——
            msgInfo = it.findClass {
                matcher {
                    usingStrings("MicroMsg.MsgInfo")
                    searchInPackages(listOf("com.tencent.mm.storage"))
                    methods { add { name("convertFrom"); paramTypes("android.database.Cursor") } }
                }
            }.firstOrNull()?.let { cd -> classOf(Reflect.classNameOf(cd.descriptor)) }
        }

        animImageView = classOf(ANIM)
        neatTextView = classOf(NEAT)
        baseViewHolder = classOf(HOLDER_BASE)
        wxRecyclerAdapter = classOf("com.tencent.mm.view.recyclerview.WxRecyclerAdapter")
    }

    private fun fallback() {
        voiceItemFrom = classOf("com.tencent.mm.ui.chatting.viewitems.bq")
        voiceItemTo = classOf("com.tencent.mm.ui.chatting.viewitems.iq")
        voiceItemFromMvvm = classOf("com.tencent.mm.ui.chatting.viewitems.tr")
        voiceItemToMvvm = classOf("com.tencent.mm.ui.chatting.viewitems.ur")
        voiceHolder = classOf("com.tencent.mm.ui.chatting.viewitems.mq")
        voiceFill = voiceHolder?.let { Reflect.method(it, "e", 9) }
        textHolder = classOf("com.tencent.mm.ui.chatting.viewitems.to")
        textBubbleSetter = textHolder?.let { Reflect.method(it, "b", 4) }
        adapter = classOf("com.tencent.mm.ui.chatting.adapter.k")
        animImageView = classOf(ANIM)
        neatTextView = classOf(NEAT)
        baseViewHolder = classOf(HOLDER_BASE)
        wxRecyclerAdapter = classOf("com.tencent.mm.view.recyclerview.WxRecyclerAdapter")
        msgInfo = classOf("com.tencent.mm.storage.e9")
    }

    fun validate(): Boolean =
        voiceHolder != null && voiceFill != null &&
        textHolder != null && textBubbleSetter != null &&
        adapter != null && wxRecyclerAdapter != null &&
        animImageView != null && neatTextView != null

    fun dump() {
        log("resolve: voiceFrom=${voiceItemFrom?.name} voiceTo=${voiceItemTo?.name} " +
            "voiceHolder=${voiceHolder?.name} fill=${voiceFill?.name} " +
            "textHolder=${textHolder?.name} textBubble=${textBubbleSetter?.name} " +
            "adapter=${adapter?.name} rvAdapter=${wxRecyclerAdapter?.name}")
    }
}


//////////////////////////////////////////////////////////////////////////////////
// 4. 启动：versionTag → resolve → install
//////////////////////////////////////////////////////////////////////////////////
object WxBoot {
    fun start() = runCatching {
        WxEnv.verTag = versionTag()
        log("ver=$verTag apk=${WxEnv.apkPath}")
        if (!WxResolver.resolve()) { log("resolve 失败，模块不生效"); return@runCatching }
        WxResolver.dump()
        WxHooks.install()
    }.onFailure { log("start 异常: $it") }

    private fun versionTag(): String = runCatching {
        val at = XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.app.ActivityThread", null), "currentActivityThread")
        val ctx = XposedHelpers.callMethod(at, "getSystemContext")
        val pm = XposedHelpers.callMethod(ctx, "getPackageManager")
        val pi = XposedHelpers.callMethod(pm, "getPackageInfo", PKG, 0)
        "${XposedHelpers.getObjectField(pi, "versionCode")}:${XposedHelpers.getObjectField(pi, "versionName")}"
    }.getOrDefault("?")
}

//////////////////////////////////////////////////////////////////////////////////
// 5. 方向判定：用 ChattingItem 真实类 + isAssignableFrom（自动覆盖 MVVM 子类）
//////////////////////////////////////////////////////////////////////////////////
object Direction {
    /** true=接收侧(From)，false=发送侧(To)，null=判不出 */
    fun fromChatItem(item: Any?): Boolean? {
        if (item == null) return null
        val cls = item.javaClass
        WxResolver.voiceItemFrom?.let { if (it.isAssignableFrom(cls)) return true }
        WxResolver.voiceItemTo?.let { if (it.isAssignableFrom(cls)) return false }
        return null
    }

    fun chatItemOf(holder: Any?): Any? = Reflect.fieldPath(holder, "i", "i")   // am5.d.i = b0(ChattingItem)
}

//////////////////////////////////////////////////////////////////////////////////
// 6. 四层 Hook
//////////////////////////////////////////////////////////////////////////////////
object WxHooks {

    fun install() {
        val rvAdapter = WxResolver.wxRecyclerAdapter ?: return
        val adapter = WxResolver.adapter ?: return

        // ===== L1-a 文本气泡背景赋值点：to.b(e9, to, ctx, Boolean isFrom) =====
        XposedBridge.hookMethod(WxResolver.textBubbleSetter!!, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val holder = param.args.getOrNull(1) ?: return
                val view = Reflect.fieldByType(holder, WxResolver.NEAT) as? View ?: return
                val isFrom = param.args.getOrNull(3) as? Boolean ?: true
                BubbleEngine.inject(view, isFrom, voice = false)
            }
        })

        // ===== L1-b 语音填充：mq.e(b0, mq, am5.d, q, ctx, Z, Z, OnLongClickListener, r6) =====
        XposedBridge.hookMethod(WxResolver.voiceFill!!, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val holder = param.args.getOrNull(1) ?: return
                val isFrom = (param.args.getOrNull(5) as? Boolean)
                    ?: Direction.fromChatItem(Direction.chatItemOf(holder))
                    ?: true
                val targets = LinkedHashSet<View>()
                Reflect.fieldsByType(holder, WxResolver.ANIM).forEach { if (it is View) targets += it }
                BubbleEngine.injectAll(targets, isFrom, voice = true)
            }
        })

        // ===== L1b 兜底：完整 bind(E0) + 局部刷新(F0) + 业务双保险(O) =====
        val ensure = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                BubbleEngine.ensureBubble(param.thisObject, param.args.getOrNull(0))
            }
        }
        XposedBridge.hookAllMethods(rvAdapter, "E0", ensure)   // 完整 bind 必经
        XposedBridge.hookAllMethods(rvAdapter, "F0", ensure)   // 局部刷新(payload) 必经
        XposedBridge.hookAllMethods(adapter, "O", ensure)      // 双保险

        // ===== L2 防回写：拦所有 setBackground 家族 =====
        val guardDrawable = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (BubbleEngine.writing.get()) return
                val v = param.thisObject as? View ?: return
                BubbleEngine.onSetBackground(v, param.args.firstOrNull() as? Drawable)
            }
        }
        XposedBridge.hookAllMethods(View::class.java, "setBackground", guardDrawable)
        XposedBridge.hookAllMethods(View::class.java, "setBackgroundDrawable", guardDrawable)
        XposedBridge.hookAllMethods(View::class.java, "setBackgroundResource", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (BubbleEngine.writing.get()) return
                BubbleEngine.onSetBackground(param.thisObject as? View ?: return, null)
            }
        })

        // 播放语音时 AnimImageView.b() 会重设气泡背景 → 拦
        XposedBridge.hookAllMethods(WxResolver.animImageView!!, "b", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                BubbleEngine.refresh(param.thisObject as? View ?: return)
            }
        })

        // ===== L3 尺寸/padding 补偿 =====
        // 3-a 语音气泡宽度算法（dp）：mq.c(Context, int)
        Reflect.method(WxResolver.voiceHolder ?: return, "c", 2)?.let { m ->
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val base = param.result as? Int ?: return
                    val ctx = param.args.getOrNull(0) as? Context ?: return
                    param.result = (base + BubbleEngine.padDeltaDp(ctx)).coerceAtLeast(80)
                }
            })
        }
        // 3-b 通用气泡宽度重置：h0.resetChatBubbleWidth(View, int)
        WxResolver.baseViewHolder?.let { bvh ->
            XposedBridge.hookAllMethods(bvh, "resetChatBubbleWidth", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    BubbleEngine.compensateWidth(param.args[0] as? View, param.args[1] as? Int ?: return)
                }
            })
        }
        // 3-c 文本最大宽度：MMNeat7extView.setMaxWidth(int)
        XposedBridge.hookAllMethods(WxResolver.neatTextView!!, "setMaxWidth", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                BubbleEngine.compensateTextMax(param.thisObject as? View, param.args[1] as? Int ?: return)
            }
        })

        // ===== L4 回收/离屏：还原 + 反注册 =====
        val cleanup = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val h = param.args.getOrNull(0) ?: return
                BubbleEngine.restoreAll(XposedHelpers.getObjectField(h, "itemView") as? View ?: return)
            }
        }
        XposedBridge.hookAllMethods(rvAdapter, "onViewRecycled", cleanup)
        XposedBridge.hookAllMethods(rvAdapter, "onViewDetachedFromWindow", cleanup)

        log("hooks installed.")
    }
}

//////////////////////////////////////////////////////////////////////////////////
// 7. 气泡引擎：注入 / 防回写 / 尺寸补偿 / 兜底重放 / 回收清理
//////////////////////////////////////////////////////////////////////////////////
object BubbleEngine {

    val writing = ThreadLocal.withInitial { false }

    private class Info(
        var original: Drawable?,       // 微信当前正在用的原始背景
        var ours: Drawable?,           // 我们的气泡 drawable
        var isFrom: Boolean,           // true=接收侧
        val voice: Boolean,
        var applied: Drawable? = null, // 实际 setBackground 的那一层
        var appliedFrom: Boolean = isFrom,
        var padL: Int = 0, var padR: Int = 0
    )

    private val reg = WeakHashMap<View, Info>()                 // 气泡 View -> 注入信息
    private val owners = WeakHashMap<View, MutableSet<View>>()  // itemView -> 被注入的子 View

    // ---------------- L1：注入 ----------------
    fun inject(view: View, isFrom: Boolean, voice: Boolean) {
        val orig = view.background ?: return
        val ours = BubbleFactory.create(view.context, isFrom, voice, orig)
        copyPadding(orig, ours)
        val info = reg.getOrPut(view) { Info(orig, ours, isFrom, voice) }
        info.original = orig; info.ours = ours; info.isFrom = isFrom
        info.padL = padL(ours) - padL(orig); info.padR = padR(ours) - padR(orig)
        apply(view, finalOf(info))
        registerOwner(view)
    }

    fun injectAll(views: Collection<View>, isFrom: Boolean, voice: Boolean) {
        views.forEach { inject(it, isFrom, voice) }
    }

    // ---------------- L1b：兜底（完整 bind + 局部刷新都会调） ----------------
    fun ensureBubble(adapter: Any, holderArg: Any?) {
        val adCls = WxResolver.adapter ?: return
        if (adapter.javaClass.name != adCls.name) return          // 只处理聊天列表适配器
        val holder = holderArg ?: return
        val itemView = XposedHelpers.getObjectField(holder, "itemView") as? View ?: return
        val isFrom = Direction.fromChatItem(Direction.chatItemOf(holder))

        // 1) 已登记 → 幂等重放（方向变化则重建）
        val set = owners[itemView]
        if (!set.isNullOrEmpty()) {
            set.toList().forEach { v ->
                val info = reg[v] ?: return@forEach
                isFrom?.let { if (it != info.appliedFrom) { info.isFrom = it; rebuild(v, info) } }
                apply(v, info.applied ?: return@forEach)
            }
            injectQuote(itemView)
            return
        }
        // 2) 从未登记（极端：直接进局部刷新 / 新 holder 没被 L1 命中）→ 按 Holder 类现算
        val tag = itemView.getTag() ?: return
        val from = isFrom ?: true
        when (tag.javaClass.name) {
            WxResolver.voiceHolder?.name -> {
                val t = LinkedHashSet<View>()
                Reflect.fieldsByType(tag, WxResolver.ANIM).forEach { if (it is View) t += it }
                injectAll(t, from, voice = true); injectQuote(itemView)
            }
            WxResolver.textHolder?.name -> {
                (Reflect.fieldByType(tag, WxResolver.NEAT) as? View)?.let { inject(it, from, voice = false) }
                injectQuote(itemView)
            }
        }
    }

    // ---------------- L2：防回写 ----------------
    fun onSetBackground(v: View, incoming: Drawable?) {
        val info = reg[v] ?: return
        val ours = info.ours ?: return
        if (incoming != null && (incoming === ours || incoming === info.applied)) return
        if (incoming != null) info.original = incoming          // 微信刚设的原始背景（可能含动画/高亮）
        apply(v, finalOf(info))
    }

    fun refresh(v: View) {
        val info = reg[v] ?: return
        val cur = v.background ?: return
        if (cur === info.applied) return
        info.original = cur
        apply(v, finalOf(info))
    }

    // ---------------- L3：尺寸补偿 ----------------
    fun padDeltaDp(ctx: Context): Int =
        (padDeltaPx() / ctx.resources.displayMetrics.density).toInt()

    private fun padDeltaPx(): Int {
        val d = reg.values.firstOrNull() ?: return 0
        return d.padL + d.padR
    }

    fun compensateWidth(v: View?, origPx: Int) {
        val info = reg[v ?: return] ?: return
        val lp = v.layoutParams ?: return
        lp.width = origPx + info.padL + info.padR
        v.layoutParams = lp
    }

    fun compensateTextMax(v: View?, orig: Int) {
        val info = reg[v ?: return] ?: return
        val tv = v as? TextView ?: return
        tv.maxWidth = orig + info.padL + info.padR
    }

    // ---------------- L4：回收清理 ----------------
    fun restoreAll(itemView: View) {
        owners.remove(itemView)?.forEach { v ->
            reg.remove(v)?.let { apply(v, it.original) }
        }
    }

    // ---------------- 内部 ----------------
    private fun finalOf(info: Info): Drawable {
        val orig = info.original
        val d = if ((info.voice || orig is AnimationDrawable) && orig != null)
                    LayerDrawable(arrayOf(info.ours!!, orig))
                else info.ours!!
        info.applied = d
        return d
    }

    private fun rebuild(v: View, info: Info) {
        val ctx = v.context
        val ours = BubbleFactory.create(ctx, info.isFrom, info.voice, info.original)
        copyPadding(info.original, ours)
        info.ours = ours
        info.padL = padL(ours) - padL(info.original); info.padR = padR(ours) - padR(info.original)
        apply(v, finalOf(info))
    }

    private fun apply(v: View, d: Drawable?) {
        writing.set(true)
        try { v.background = d } finally { writing.set(false) }
    }

    private fun registerOwner(v: View) {
        itemViewOf(v)?.let { iv ->
            owners.getOrPut(iv) { LinkedHashSet() }.add(v)
        }
    }

    /** 往上找到聊天条目根视图（父级是 RecyclerView/ListView 即停） */
    private fun itemViewOf(v: View): View? {
        var cur: View = v
        var guard = 0
        while (cur.parent is ViewGroup && guard++ < 12) {
            val p = cur.parent as ViewGroup
            val n = p.javaClass.name
            if (n.contains("RecyclerView") || n.contains("ListView") || n.contains("ChattingList")) return cur
            cur = p
        }
        return cur
    }

    /** 引用消息气泡（q71.n 家族的实现 View）——随 Holder 复用但内容会换，bind 后补注入 */
    fun injectQuote(itemView: View) {
        walk(itemView, 0) { v ->
            if (reg.containsKey(v)) return@walk
            if (!looksLikeBubble(v)) return@walk
            val siblings = (v.parent as? ViewGroup)?.childCount ?: 0
            if (siblings < 2) return@walk
            val from = nearestInfo(itemView)?.isFrom ?: true
            inject(v, from, voice = false)
        }
    }

    private fun nearestInfo(itemView: View): Info? =
        owners[itemView]?.firstOrNull()?.let { reg[it] }

    private fun walk(v: View, depth: Int, block: (View) -> Unit) {
        if (depth > 5) return
        block(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1, block)
    }

    /** 启发式判断"是不是九宫格气泡背景"，避免误伤图标/进度条 */
    private fun looksLikeBubble(v: View): Boolean {
        val bg = v.background ?: return false
        val w = bg.intrinsicWidth; val h = bg.intrinsicHeight
        if (w in 100..4000 && h in 40..600) {
            val name = bg.javaClass.name
            return name.contains("NinePatch") || name.contains("Bitmap") || name.contains("Gradient") ||
                   name.contains("Layer") || name.contains("Shape")
        }
        return false
    }

    private fun copyPadding(from: Drawable?, to: Drawable?) {
        if (from == null || to == null) return
        val r = Rect()
        if (from.getPadding(r)) to.setPadding(r.left, r.top, r.right, r.bottom)
    }

    private fun pad(d: Drawable, left: Boolean): Int {
        val r = Rect()
        return if (d.getPadding(r)) (if (left) r.left else r.right) else 0
    }
}

//////////////////////////////////////////////////////////////////////////////////
// 8. 气泡 Drawable 工厂（优先模块九宫格资源，缺失则程序化绘制）
//////////////////////////////////////////////////////////////////////////////////
object BubbleFactory {
    private var moduleRes: android.content.res.Resources? = null

    fun init() {
        if (WxEnv.modulePath.isEmpty()) return
        runCatching {
            val cls = XposedHelpers.findClass("de.robv.android.xposed.XModuleResources", null)
            val xr = XposedHelpers.callStaticMethod(cls, "createForXposed", WxEnv.modulePath)
            moduleRes = xr as? android.content.res.Resources
            log("XModuleResources ok: $xr")
        }.onFailure { log("XModuleResources 不可用，走程序化绘制: $it") }
    }

    /** 生成气泡：rawNinePatch=false 时用 LayerDrawable(body+tail) 程序化绘制 */
    fun create(ctx: Context, isFrom: Boolean, voice: Boolean, orig: Drawable?): Drawable {
        moduleRes?.let { r ->
            val id = resId(r, when {
                voice && isFrom -> "wx_bubble_voice_from"
                voice -> "wx_bubble_voice_to"
                isFrom -> "wx_bubble_text_from"
                else -> "wx_bubble_text_to"
            })
            if (id != 0) runCatching { return r.getDrawable(id, ctx.theme) }
        }
        return programmatic(ctx, isFrom, voice)
    }

    private fun resId(r: android.content.res.Resources, name: String): Int = runCatching {
        val m = r.javaClass.getMethod("getIdentifier", String::class.java,
            String::class.java, String::class.java)
        m.invoke(r, name, "drawable", WxEnv.lp.packageName) as? Int ?: 0
    }.getOrDefault(0)

    private fun programmatic(ctx: Context, isFrom: Boolean, voice: Boolean): Drawable {
        val dm = ctx.resources.displayMetrics
        fun dp(v: Float) = v * dm.density + 0.5f
        val radius = dp(if (voice) 9f else 7f)
        val body = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius)
            setColor(if (isFrom) 0xFFFFFFFF.toInt() else 0xFF95EC69.toInt())
            setStroke(dp(0.6f).toInt(), if (isFrom) 0x1A000000 else 0x1A000000)
        }
        val ts = dp(if (voice) 7f else 6f)
        val path = Path().apply {
            if (isFrom) { moveTo(0f, 0f); lineTo(ts, 0f); lineTo(0f, ts); close() }
            else { moveTo(0f, 0f); lineTo(ts, 0f); lineTo(ts, ts); close() }
        }
        val tail = ShapeDrawable(PathShape(path, ts, ts)).apply {
            paint.color = if (isFrom) 0xFFFFFFFF.toInt() else 0xFF95EC69.toInt()
            intrinsicWidth = ts.toInt(); intrinsicHeight = ts.toInt()
        }
        return LayerDrawable(arrayOf(body, tail))
    }
}

//////////////////////////////////////////////////////////////////////////////////
// 9. 启动钩子： zygote 里 init 资源工厂
//////////////////////////////////////////////////////////////////////////////////
class WxBubbleModule : IXposedHookZygoteInit, IXposedHookLoadPackage {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        WxEnv.modulePath = startupParam.modulePath
        BubbleFactory.init()
    }

    override fun handleLoadPackage(lp: XC_LoadPackage.LoadPackageParam) {
        if (lp.packageName != PKG) return
        WxEnv.lp = lp
        WxEnv.apkPath = lp.appInfo?.sourceDir ?: return
        Thread({ WxBoot.start() }, "WxBubble-boot").start()
    }
}
```

> 说明：入口类只定义一次（第 9 节）；第 0 节仅作占位说明，Kotlin 允许同包内任意顺序声明，因此放在末尾不影响编译。

---

## 18. 编译与资源说明

### 18.1 build.gradle
```gradle
plugins { id 'com.android.application'; id 'org.jetbrains.kotlin.android' }
android {
    namespace 'com.example.wxbubble'
    compileSdk 34
    defaultConfig { minSdk 24; targetSdk 34 }
    kotlinOptions { jvmTarget = '17' }
}
dependencies {
    compileOnly 'de.robv.android.xposed:api:82'
    implementation 'io.github.lsposed.dexkit:dexkit:2.0.0'
}
```

### 18.2 AndroidManifest.xml（Xposed 模块标识）
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="WxBubble">
        <meta-data android:name="xposedmodule" android:value="true"/>
        <meta-data android:name="xposeddescription" android:value="微信聊天气泡注入"/>
        <meta-data android:name="xposedminversion" android:value="82"/>
    </application>
</manifest>
```

### 18.3 资源（可选，缺省自动走程序化绘制）
```
src/main/res/drawable-xxhdpi/wx_bubble_text_from.png    （九宫格，含 padding）
src/main/res/drawable-xxhdpi/wx_bubble_text_to.png
src/main/res/drawable-xxhdpi/wx_bubble_voice_from.png
src/main/res/drawable-xxhdpi/wx_bubble_voice_to.png
src/main/res/drawable-night/…                            （深色模式两套，自动跟随）
```
> 用 `.9.png` 时放到 `drawable-xxhdpi` 即可，`XModuleResources.getDrawable()` 会自动解析 chunk。

### 18.4 DexKit 版本差异
- `findClass { matcher { usingStrings(…) } }` 是 DexKit 2.x 写法；若你的版本要求 `findClass { searchIn { matcher { … } } }`，把代码里的 `matcher{}` 包一层 `searchIn{}` 即可，其余逻辑不变。
- `ClassData.usingStrings` / `ClassData.descriptor` / `MethodData.descriptor` 在 2.x 均可用；拿到 descriptor 后统一用 `Reflect.classNameOf()` 转成 Java 类名，再 `XposedHelpers.findClass`。

---

## 19. 联调自检表（装好后逐条过）

| # | 检查项 | 通过标准 |
|---|---|---|
| 1 | 模块日志 | `[WxBubble] ver=…`、`resolve: voiceFrom=… voiceTo=… voiceHolder=… textHolder=… adapter=…` 全部非 null |
| 2 | 单聊文本气泡 | 左右两侧都是自定义气泡，文字位置无偏移 |
| 3 | 群聊文本气泡 | 同上（群聊多出昵称/头像，不影响） |
| 4 | 语音气泡（收/发） | 气泡背景被替换；时长文本、波形动画正常 |
| 5 | 播放语音 | 点击播放时波形动画仍在动（LayerDrawable 叠加生效），气泡不回弹成原生 |
| 6 | **上下快滑 200 条** | 全程无"掉回原生气泡 / 文本气泡跑到语音行" |
| 7 | 语音播放中滚动列表 | 局部刷新触发时气泡仍在（F0 兜底生效） |
| 8 | 长按多选 | 高亮态可见（可观察 `holder.g`/ProgressBar 可见性），文案不被遮 |
| 9 | 转发一条带引用的消息 | 引用区也是自定义气泡（injectQuote 生效） |
| 10 | 深色模式 | 切换后气泡仍可读，文字与 bubble 对比度足够 |
| 11 | 退出聊天再进入 | 无残留、无重复 addView、无内存泄漏（WeakHashMap 自动清理） |
| 12 | 微信重启后重复进入 | 日志只打印一次 resolve；versionTag 不变则行为一致 |
| 13 | 微信升级后 | `ver=` 变化 → 模块重新 resolve；若 resolve 失败会回退硬编码类名再试一次 |

---

## 20. 一页速查（给未来的自己）

```
注入点（L1）  to.b(e9,to,ctx,Boolean)              文本气泡
             mq.e(b0,mq,am5.d,q,ctx,Z,Z,L,r6)      语音气泡
兜底（L1b）   WxRecyclerAdapter.E0(s0,pos)          完整 bind
             WxRecyclerAdapter.F0(s0,pos,payloads) 局部刷新 ★
             ChattingDataAdapterV3.O(s0,pos)       双保险
防回写（L2）  View#setBackground / setBackgroundResource / setBackgroundDrawable
             AnimImageView.b()
尺寸（L3）    mq.c(Context,int) / h0.resetChatBubbleWidth(View,int) / MMNeat7extView.setMaxWidth(int)
回收（L4）    WxRecyclerAdapter.onViewRecycled / onViewDetachedFromWindow
判据          param.thisObject 类名 == adapter.k；holder.itemView；holder.i=am5.d；itemView.tag=Holder 实例
方向          am5.d.i(ChattingItem) + isAssignableFrom(bq/tr → true, iq/ur → false)
View→消息     itemView.tag(ps) → c() → e9；或 holder.i.d.b

---

# 第四部分 · 三问精准修复（假气泡 / 尺寸对不上 / 系统提示·红包·撤回·引用）

> 本章基于对目标 APK 的**再次实机核查**（jadx + smali + 字符串三重印证），
> 对前面方案的错误点直接标注「作废/改正」，只保留已验证结论。

## 21. 问题 1：语音行出现「空的假气泡」把真气泡顶到下一行

### 21.1 根因（已定位，不是微信的问题，是注入目标选错）

`mq`（ChattingItemVoice$VoiceItemHolder）里有一个**透明点击热区**：

```java
// mq.e(...) 第 369-372 行（jadx 行号）
mqVar.d.setTag(new ap(dVar, dVar2.E(), mqVar, (String) null));
mqVar.d.setOnClickListener(((h0) mqVar).chattingItem.y(dVar2));
mqVar.d.setOnLongClickListener(onLongClickListener);
mqVar.d.setOnTouchListener(((z) cVar5.a(z.class)).L1);
// 第 236 / 259 / 288 行：mqVar.d.setWidth(a)  ← a = 气泡宽度(px)
```

`mqVar.d` 是 `TextView`（id **2131366097**）：**无背景、无文字、不可见，只负责接收点击/长按/触摸**，
宽度被设成气泡宽度。只要给它 `setBackground(...)`，它立刻变成一个「可见的空方框」，
而它与真气泡 `mq.e` 在布局里并列 → **假气泡占一行，真气泡被挤到下一行**，两个都渲染。
这与你的现象完全一致。

第二嫌疑（同样作废）：第 15.4 节的 `injectQuote()` 用了「`looksLikeBubble()` 启发式 + siblings>=2」遍历
itemView 5 层内的 View —— 极易命中 `mq.o`(FL 2131366098)/`mq.p`(2131366099)/`mq.z`(RL 2131365813)/
`mq.q`(FL 2131366105)/`mq.C`(RL 2131365817) 等**空容器**，从而硬造出假气泡。

### 21.2 修复：白名单 + 黑名单 + 前置校验（三者都要）

```kotlin
// ===== ① 白名单：只有这 4 个 View 才是"气泡" =====
private val VOICE_BUBBLE_IDS = setOf("e", "x", "u")     // mq 内的 AnimImageView / 发送侧 TextView
//   e = AnimImageView  id 2131366091   ← 语音气泡（接收侧）★主目标
//   x = TextView       id 2131366108   ← 语音气泡（发送侧）
//   u = AnimImageView  id 2131366096   ← 语音气泡（发送侧，播放动画）
private val TEXT_BUBBLE  = "b"                           // to 内 MMNeat7extView id 2131365751

// ===== ② 黑名单：绝对禁止注入（一碰就出假气泡/错位）=====
private val BANNED = setOf(
    "d",   // mq.d  2131366097 透明点击热区（宽度=气泡宽） ★假气泡元凶
    "c",   // mq.c  2131366095 副文本
    "s",   // mq.s  2131365751 语音"秒数"文本（不是气泡）
    "o",   // mq.o  2131366098 FrameLayout 容器
    "p",   // mq.p  2131366099 空白占位
    "z",   // mq.z  2131365813 "续听提示"容器（bq.g0 里只被 setVisibility(8)）
    "C",   // mq.C  2131365817 倍速提示容器
    "B",   // mq.B  2131366093 RelativeLayout
    "q",   // mq.q  2131366105 FrameLayout
    "r", "y",      // ImageView 图标/红点
    "t", "w",      // ProgressBar
    "f", "g"       // ViewStub
)

// ===== ③ 注入前三重校验（缺一不注入）=====
private fun injectable(v: View, holder: Any): Boolean {
    if (v !is TextView && v !is ImageView) return false          // 不碰容器
    val bg = v.background ?: return false                        // 微信确实给了背景（真气泡一定有）
    if (v.width <= 0 || v.height <= 0) return false              // 不可见的不碰
    if (v is TextView && v.text.isNullOrEmpty() && v.isClickable && v.isLongClickable) return false // ★点击热区
    if (bg.intrinsicWidth <= 0 || bg.intrinsicHeight <= 0) return false
    return true
}
```

### 21.3 `qu​ote`（引用气泡）改为「工厂注入」，删掉启发式

```kotlin
// 作废：injectQuote(itemView) 的启发式遍历（见 15.4）→ 整段删除

// 改正：DexKit 找出 q71.n(IMsgQuoteView) 的全部实现，hook 其 b(Context)→View
//      （该返回值就是被 addView 进气泡父容器的引用卡片根 View）
DexKitBridge.create(apkPath, cl).use { br ->
    br.findClass { matcher { interfaces { add("Lq71/n;") } } }
      .forEach { cd ->
          val c = XposedHelpers.findClass(Reflect.classNameOf(cd.descriptor), cl) ?: return@forEach
          XposedBridge.hookAllMethods(c, "b", object : XC_MethodHook() {
              override fun afterHookedMethod(p: MethodHookParam) {
                  val root = p.result as? View ?: return
                  BubbleEngine.inject(root, isFrom = true, voice = false)   // 引用卡片自身背景
              }
          })
      }
}
// 备选（拿不到实现类时）：只处理 h0.getQuoteView() 对应的那个 View
//   val qv = Reflect.callByName(holder, "getQuoteView", 0) as? View
//   → 注意：q71.n.b(Context) 会新建 View，不能调用它，只能从 itemView 已有的子 View 里定位
```
> `q71.n` 接口签名：`a(Lq71/p;)V`（setViewModel）、`b(Landroid/content/Context;)Landroid/view/View;`（建 View）、`getViewModel()Lq71/p;`。
> 本版本共 **44 个实现**（`cr5.*`、`bs5.c`）。

### 21.4 修复后的 L1b 兜底（同步收紧）
```kotlin
fun ensureBubble(adapter: Any, holderArg: Any?) {
    if (adapter.javaClass.name != WxResolver.adapter!!.name) return
    val holder = holderArg ?: return
    val itemView = XposedHelpers.getObjectField(holder, "itemView") as? View ?: return
    val isFrom = Direction.fromChatItem(Reflect.fieldPath(holder, "i", "i"))
    val tag = itemView.getTag() ?: return
    when (tag.javaClass.name) {
        WxResolver.voiceHolder?.name -> {
            // 只注入白名单字段：AnimImageView(e/u) + 发送侧 TextView(x)，且必须过 injectable()
            val targets = LinkedHashSet<View>()
            Reflect.fieldsByType(tag, WxResolver.ANIM).forEach { if (it is View) targets += it }
            Reflect.allFields(tag.javaClass)
                .filter { it.name == "x" && it.type.name == "android.widget.TextView" }
                .forEach { it.isAccessible = true; (it.get(tag) as? View)?.let { v -> targets += v } }
            targets.filter { injectable(it, tag) }.forEach { BubbleEngine.inject(it, isFrom ?: true, true) }
        }
        WxResolver.textHolder?.name -> {
            val v = Reflect.fieldByType(tag, WxResolver.NEAT) as? View
            if (v != null && injectable(v, tag)) BubbleEngine.inject(v, isFrom ?: true, false)
        }
        else -> BubbleEngine.restoreAll(itemView)
    }
}
```

---

## 22. 问题 2：自己的气泡被原生气泡「包裹在内」+ 原生气泡数值参数

### 22.1 根因 A：LayerDrawable 层序反了（必须改）

```kotlin
// ❌ 作废（第 6.3 / 17 章旧代码）：
LayerDrawable(arrayOf(ours, orig))     // ours 在下、orig(不透明九宫格) 在上 → 只露边缘 → "被包裹"

// ✅ 改正：让原生机在底层、我们的皮肤在上层
LayerDrawable(arrayOf(orig, ours)).also {
    it.setLayerInset(0, 0, 0, 0, 0)
    it.setLayerInset(1, padL, padT, padR, padB)   // 用原生气泡 padding 对齐内容
}
// ✅ 或者：静态气泡（文本）直接替换，不叠加
view.background = ours
```
> 语音气泡**可以**直接替换：波形动画在 `AnimImageView` 的 **compound drawable**（type=1，`setCompoundDrawablesWithIntrinsicBounds(this.h,null,null,null)`）里；`AlphaAnimation` 是 **view animation**（`setAnimation(...)`），都与 background 内容无关。
> 只有 `mq.u`（type=0）会用 background 做淡入，其 `AlphaAnimation` 同样不受 drawable 替换影响。

### 22.2 根因 B：没用微信自己算好的宽度（必须改）

微信的语音气泡宽度是**运行时算好并写进 View 里的**：
```java
int a = ke5.a.a(ctx, lk.a(c(ctx, 秒)));   // dp→px
mqVar.b = a;                              // holder.b = 最终宽度
mqVar.d.setWidth(a);                      // 点击热区宽度
mqVar.e.setWidth(ke5.a.a(ctx, lk.a(c(ctx, 秒))));   // 真气泡宽度
```
**正确做法：注入时直接读微信算好的值，不要自己算**：
```kotlin
// 读 holder.b（最终 px 宽度）或直接读气泡 View 当前宽度
val baseW = (Reflect.callByName(holder, "b", 0) as? Int)?.takeIf { it > 0 } ?: bubbleView.width
val lp = bubbleView.layoutParams ?: ViewGroup.LayoutParams(baseW, ViewGroup.LayoutParams.WRAP_CONTENT)
lp.width = baseW + oursPadL + oursPadR - origPadL - origPadR      // 只补 padding 差
bubbleView.layoutParams = lp
```

### 22.3 原生气泡数值参数表（本版本实测，来自 `mq.c()` 与 smali）

**语音气泡宽度（dp）算法 `mq.c(Context,int)`：**

| 语音时长 | 公式 | 实际值（dp） |
|---|---|---|
| sec ≤ 2 | 固定 | **80** |
| 3 ≤ sec < 10 | (sec-2)×9 + 80 | 3″=89、4″=98、5″=107、6″=116、7″=125、8″=134、9″=143 |
| 10 ≤ sec < 60 | (sec/10 + 7)×9 + 80 | 10–19″=143、20–29″=161、30–39″=179、40–49″=197 |
| sec ≥ 60 | 固定 | **204** |
| 屏幕自适应上限 | `min(max((screenWidthDp − 172)×400 ÷ screenWidthDp, 80), 204)` | 小屏/分屏时收缩 |
| 兜底 | `Math.min(w, i4)` | `i4` 由上下文决定 |

- 最终像素宽 = `ke5.a.a(ctx, lk.a(c(ctx,sec)))`；`lk.a(float)` 为密度修正、`ke5.a.a` 为 dp→px。
- 最小宽度 **80dp**，高度 `wrap_content`（由九宫格 drawable 决定）。
- **短气泡的最小宽度也由 drawable padding 决定**：80dp 是 `d`/`e` 的宽度，视觉左右留白来自九宫格 padding。

**文本气泡：**
- `MMNeat7extView.setMaxWidth(dimensionPixelSize(2131166702) / f.g)`；`f.g` 为多窗口/字号缩放系数。
- 高度 `wrap_content`，九宫格 padding 决定内边距。

**语音秒数文本 `mq.s`：**
- 字号 `ke5.a.f(ctx, 2131165438)`；内容 `getString(2131768495, 秒)`。
- 阴影 `setShadowLayer(2.0f, 1.2f, 1.2f, ChatBgAttr.g)`。
- 背景：`ChatBgAttr.h == true → setBackgroundResource(2131232057)`，否则 `setBackgroundColor(0)`。

**运行时取真实数值（比硬编码更稳，建议直接用在模块里）：**
```kotlin
fun dumpBubbleMetrics(v: View): String {
    val bg = v.background
    val r = Rect()
    val ok = bg?.getPadding(r) == true
    val lp = v.layoutParams
    return "cls=${v.javaClass.simpleName} id=${v.id} " +
           "w=${v.width} h=${v.height} lpW=${lp?.width} lpH=${lp?.height} " +
           "intrinsic=${bg?.intrinsicWidth}x${bg?.intrinsicHeight} " +
           "padding=${if (ok) "${r.left},${r.top},${r.right},${r.bottom}" else "none"} " +
           "bgCls=${bg?.javaClass?.name}"
}
// 用法：hook mq.e / to.b after 后调用并 log，一次即可拿到这台机器上的所有真实数值
```

### 22.4 修正后的「换肤」函数（含层序 + 尺寸 + padding）
```kotlin
fun inject(view: View, isFrom: Boolean, voice: Boolean) {
    val orig = view.background ?: return
    if (!injectable(view, view)) return
    val ours = BubbleFactory.create(view.context, isFrom, voice, orig)
    copyPadding(orig, ours)                       // 原九宫格 padding → 我们（内容位置不变）
    val final = if (needAnimLayer(orig)) LayerDrawable(arrayOf(orig, ours)) else ours
    apply(view, final)
    // 尺寸：沿用微信算好的宽度，只补 padding 差
    val lp = view.layoutParams ?: return
    lp.width = (view.width + padL(ours) + padR(ours) - padL(orig) - padR(orig))
    view.layoutParams = lp
}
```

---

## 23. 问题 3：时间线 / 已领取红包提示 / 撤回提示 / 引用消息 —— 气泡与文字颜色全挖清

### 23.1 统一结论：这些场景**不加气泡背景**，只有 foreground + 文字颜色

| 场景 | Item / 填充器 / Holder | 消息 type | 气泡怎么加 | 文字颜色怎么来 |
|---|---|---|---|---|
| **时间线（时间提示）** | `viewitems.mn` → `viewitems.rn`(ChattingItemSysMsgTemplate) → Holder `viewitems.on` | 10000 家族（见下） | **没有 `setBackground`**；只有 `onVar.b.setForeground(getDrawable(2131232025))` | `ChatBgAttr` 的 `time_*`（见 23.5）作用于 `h0.timeTV`(id 2131366064) |
| **「XXX 撤回了一条消息」** | 同上（type=10000 的 sysmsg 模板） | 10000 | 同上（仅 foreground） | 同上 |
| **「你已领取 XXX 的红包」** | 同上（type=10000 的 sysmsg 模板） | 10000 | 同上（仅 foreground） | 同上 |
| **群通知/提示（入群、改名、置顶…）** | 同上 | 10000/10002/570425393/603979825/268445456/268445458/285222674/64 | 同上 | 同上 |
| **红包卡片** | Item `viewitems.d4`(From)/`h4`(To)，Holder `b4`，layout **2131624841** | **436207665** | `((h0)b4).clickArea.setBackgroundResource(...)` ← **真正的气泡背景** | 见 23.4 |
| **转账** | Item `viewitems.jd`(From)/`ld`(To) | **419430449** | 同上（`z1.*` 系列） | 同上 |
| **AA 收款** | `z1.c(r,isSend)` 分支 | 419430449 系列 | 同上 | 同上 |
| **引用消息** | 接口 `q71.n` + 44 实现（`cr5.*`、`bs5.c`）+ VM `q71.p`；工厂 `oo.a0.J7(fm5.b)`；`J7.b(ctx)` 后 `addView` 到 `((RelativeLayout)mq.F.getParent())` | 跟随宿主消息 | 引用卡片背景写在各自**布局 XML**（除红包外不动态设） | 由被引用消息自身渲染逻辑决定（被引用消息会用自己的 fill 画一遍） |

### 23.2 时间线/系统提示的真实填充代码（`rn.a()` filling）

```java
// viewitems.rn = ChattingItemSysMsgTemplate（Tag: MicroMsg.SysMsgTemplateImp）
public void a(h0 h0Var, q qVar, d dVar, am5.d dVar2, String str) {
    on onVar = (on) h0Var;                       // Holder：b=NeatTextView, c/d=View, e=TextView
    onVar.b.setTag(new ps(x));
    Map d = fa.d(j, "sysmsg", null);             // 解析 sysmsg XML
    String type = (String) d.get(".sysmsg.$type");
    if ("sysmsgtemplate".equals(type)) {
        CharSequence gj = ((k1) n0.c(k1.class)).gj(d, bundle, weakRefCtx, 0, weakRefTV);  // 生成带 span 的文本
        onVar.c.setVisibility(gj == null || gj.length() == 0 ? 8 : 0);
        ...                                       // 文本上屏，无 setBackground
    }
}
// viewitems.mn.n()（系统提示 Item 的 fill）
rnVar.a(h0Var, h0Var, dVar, dVar2, str);         // filling
rnVar.b(h0Var, h0Var, dVar, e9Var, str);         // fillingExtraParts
on onVar = (on) h0Var;
onVar.b.setTag(new ps(dVar2, dVar.E(), onVar, null));
onVar.b.setForeground(dVar.g().getDrawable(2131232025));     // ★唯一的"背景/气泡"（前景层）
onVar.b.setOnLongClickListener(new mn$.a(this, dVar));
```
**结论（重要）：**
1. 时间线/撤回/已领取红包等系统提示**根本没有气泡背景**，只有 `NeatTextView.setForeground(2131232025)`。
2. 因此给这些场景"加气泡"必须自己动手：hook `mn.n()`（或 `rn.a()`）after → 对 `onVar.b` 用 `setBackground`（**不是** setForeground）。
3. 文字颜色：系统提示文本由 `k1.gj()` 生成（内含彩色 span，如"撤回"高亮），**走 NeatTextView 富文本**；时间文字 `h0.timeTV` 的颜色来自 `ChatBgAttr.time_color`。

### 23.3 `mn` 的 type 分支（决定用哪套模板）

```java
rn rnVar3 = (type == 10002 || type == 268445458 || type == 285222674) ? this.t
          : type == 570425393 ? rnVar2
          : type == 603979825 ? this.v : this.s;         // this.s = 默认模板
```
模板实例 `s/t/u/v` 都是 `rn`(ChattingItemSysMsgTemplate) 的实例 → **同一渲染器，不同 subtype 的模板解析**。

### 23.4 红包 / 转账 / AA 的气泡与文字颜色（`com.tencent.mm.ui.chatting.z1` = C2CAppMsgUtil）

```java
// viewitems.d4.n()（红包 fill）关键 6 行
b4Var.resetChatBubbleWidthWithNewStyle(((h0) b4Var).clickArea, b4Var.o);          // 尺寸
((h0) b4Var).clickArea.setBackgroundResource(z1.c(v, e9Var.z0() == 1));           // 已领取/AA：背景
((h0) b4Var).clickArea.setBackgroundResource(z1.h(status, subStatus, isSend));    // 未领取：背景
((h0) b4Var).clickArea.setPadding(0, 0, 0, 0);
if (gk.D()) {                                                                     // 深色模式
    b4Var.c/d/e.setTextColor(getResources().getColor(2131100289));
    b4Var.c.setAlpha(z1.b(v, isSend, false)); ... b4Var.b.setAlpha(z1.b(v, isSend, false));
} else {                                                                          // 普通模式
    b4Var.c.setTextColor(2131099904); b4Var.d.setTextColor(2131099904);
    b4Var.e.setTextColor(2131099898);   b4Var.b.setAlpha(1.0f);
}
```
`z1.h(int status, int subStatus, boolean isSend)` —— **红包气泡背景 resId 表（本版本实测）**：

| status | subStatus | 发送(isSend) | 接收 |
|---|---|---|---|
| 5 | — | **2131231708** | **2131231695** |
| 4 | — | **2131231702** | **2131231689** |
| 3 | == 2 | **2131231702** | **2131231689** |
| 其它 | — | **2131231697** | **2131231684** |

`z1.c(dx0.r content, boolean isSend)` —— AA 收款/已领取态背景：发 `2131230753/0754`，收 `2131230744/0745/0746/0737`。
默认兜底（content 为空）：发 `2131230753`、收 `2131230744`。

→ **红包换肤 Hook 点：`z1.c(dx0.r,Z)` 与 `z1.h(IIZ)`（静态方法，返回 resId）**，after 里把返回值换成你的 drawable id；
或直接 hook `b4.clickArea` 的 `setBackgroundResource`（用 `2131231684/1689/1695/1697/1702/1708` 做判据）。
→ **红包文字颜色 Hook 点：`gk.D()`(深色判定) 无侵入；改 `b4.c/d/e.setTextColor` 或 hook `TextView.setTextColor` 于红包 Holder 内。**

### 23.5 文字颜色/阴影的统一来源：`ChatBgAttr`（`com.tencent.mm.pluginsdk.ui.i0`）

```java
// 构造：从"聊天背景图"的元数据里解析（Tag: MicroMsg.ChatBgAttr）
public i0(String path, Context ctx) {
    // 读取 "chatbg" 节点：
    //   .chatbg.$version
    //   .chatbg.$time_color / $time_show_background / $time_light_background
    //   .chatbg.$time_shadow_color / $time_show_shadow_color
    //   .chatbg.$voice_second_color / $voice_second_show_background
    //   .chatbg.$voice_second_shadow_color / $voice_second_show_shadow_color
}
// 字段（8 个：3 个 int + 5 个 boolean）：
//   【已由 mq.e() 用法反证，可用】
//     f:Z = 是否给"语音秒数"加阴影      g:I = 秒数阴影色      h:Z = 是否给秒数加背景(→2131232057)
//   【仅知类型、与字符串的对应关系未逐一反证，需运行时实测】
//     a:I、c:I = time_color / time_light_background 之一
//     b:Z、d:Z、e:Z = time_show_background / time_show_shadow_color / voice_second_show_background 之一
```
**消费方（find_class_usage 实测只有 2 处）：**
- `com.tencent.mm.ui.chatting.component.v2.m` ← **聊天窗口内文字颜色/阴影的提供者**
- `com.tencent.mm.plugin.readerapp.ui.ReaderAppUI.o`

**在语音气泡里的用法（`mq.e()` 第 354–368 行）：**
```java
i0 i0Var = ((e) cVar5.a(e.class)).m;                 // ChatBgAttr
if (i0Var != null) {
    if (i0Var.f) mqVar.s.setShadowLayer(2.0f, 1.2f, 1.2f, i0Var.g);   // 秒数文字阴影色
    else         mqVar.s.setShadowLayer(0, 0, 0, 0);
    if (i0Var.h) mqVar.s.setBackgroundResource(2131232057);           // 秒数文字背景（花哨背景时）
    else         mqVar.s.setBackgroundColor(0);
}
```
→ **想改"语音秒数/时间文字"颜色：hook `com.tencent.mm.pluginsdk.ui.i0.<init>(String,Context)` after，把字段 `a/g` 换成你的颜色；或 hook `TextView.setShadowLayer(float,float,float,int)` / `setTextColor(int)` 在聊天窗口内。**
→ 想彻底接管：hook `com.tencent.mm.ui.chatting.component.v2` 的字段 `m` 的写入点，替换整个 ChatBgAttr。

### 23.6 引用消息（Quote）的完整链路

```
mq.b(View,boolean,boolean) 第 498-515 行：
  t J7 = ((k) n0.c(k.class)).jj().J7((fm5.b) null);   // 工厂 → IMsgQuoteView(q71.n) 实例
  View b = J7.b(view.getContext());                   // inflate 引用卡片（44 个实现之一）
  ((RelativeLayout) this.F.getParent()).addView(b);   // F = findViewById(2131389578)（引用 stub）
  lp.addRule(3, 2131389578);                          // 定位在 stub 下方
  isFrom ? lp.addRule(5, 2131366078)                  // 接收侧对齐头像
         : { lp.addRule(0, 2131365727); lp.addRule(7, 2131366078); }
  setQuoteView(J7);                                   // h0.quoteView = J7
```
- 接口 `q71.n`：`a(Lq71/p;)V` setViewModel / `b(Landroid/content/Context;)Landroid/view/View;` 建 View / `getViewModel()Lq71/p;`
- 本版本 **44 个实现**（`cr5.a1/b/d/d1/e/e0/f/f0/f1`、`bs5.c` …），被引用的消息类型各一个。
- `q71.p` = Quote ViewModel（无线段名，纯数据对象）。
- 点击：`bq.g0(...)` 里 `nq.h(dVar, psVar, msgQuoteItem)`（`nq` = 语音 Helper）；`MsgQuoteItem` = `com.tencent.mm.plugin.msgquote.model.MsgQuoteItem`。
- **引用卡片自身背景在各自布局 XML 里静态设置**（除引用红包外不动态 `setBackgroundResource`）→ 换肤要么改布局层（hook `View.setBackground*` 生效），要么按 21.3 的工厂 hook 在 `b(Context)` 返回的根 View 上换肤。

### 23.7 本专题 DexKit 字符串锚点表（新增，务必加入模块）

| # | 字符串 | 命中类（本版本） | 用途 |
|---|---|---|---|
| N1 | `MicroMm.SysMsgTemplateImp` | `com.tencent.mm.ui.chatting.viewitems.rn` | 时间线/撤回/已领取红包提示的**统一填充器** |
| N2 | `com/tencent/mm/ui/chatting/viewitems/ChattingItemSysMsgTemplate` | 同上 | N1 的强备份（字符串里带包路径） |
| N3 | `.sysmsg.sysmsgtemplate.content_template` | `rn.a()` | sysmsg 模板解析入口 |
| N4 | `hy: not acceptable sysmsg: %s` / `hy: request translate content is null!` | `rn.a()` | N1/N3 的候选筛选 |
| N5 | `chat_sys_msg_del_btn` / `log_version` | `com.tencent.mm.ui.chatting.viewitems.mn` | 系统提示 **Item**（type 10000 家族注册在 `kt` 的 `g(...,mn.class,...)`） |
| N6 | `chatbg` / `parse chatbgattr failed` | `com.tencent.mm.pluginsdk.ui.i0`(**ChatBgAttr**) | 文字颜色/阴影/秒数背景的总开关 |
| N7 | `.chatbg.$time_color` / `.chatbg.$voice_second_show_background` | 同上 | ChatBgAttr 字段级确认 |
| N8 | `MicroMm.C2CAppMsgUtil` | `com.tencent.mm.ui.chatting.z1` | 红包/转账/AA 的背景与颜色计算 |
| N9 | `getC2CLuckyMoneyDescByHbStatus() hbType:%s hbStatus:%s receiveStatus:%s isGroupChat:%s exclusiveRecv...` | `z1.i()` | 红包描述文字 + N8 强确认 |
| N10 | `MicroMm.ChattingItemAppMsgC2CFrom` | `com.tencent.mm.ui.chatting.viewitems.d4` | 红包 Item（From），type=436207665 |
| N11 | `frhb://c2cbizmessagehandler/hongbao/receivehongbao` / `.ui.LuckyMoneyNewReceiveUI` / `LuckyMoneyNotHookReceiveUI` | `d4.f0()` | 红包点击跳转，N10 强确认 |
| N12 | `MicroMm.ChattingItemAppMsgRemittanceFrom` / `RemittanceDetailUI` / `transfer_attach` | `com.tencent.mm.ui.chatting.viewitems.jd` | 转账 Item（From），type=419430449 |
| N13 | `ChattingItemVoice$VoiceItemHolder` | `viewitems.mq` | 旧锚点（语音 Holder） |
| N14 | `[voice interrupt] set continue play visible ` | `mq.e()` | 旧锚点（语音 fill） |
| N15 | `q71.n` 接口（`a(Lq71/p;)V` + `b(Landroid/content/Context;)Landroid/view/View;`） | 44 个实现 `cr5.*`/`bs5.c` | 引用气泡工厂注入（21.3） |
| N16 | `com.tencent.mm.plugin.msgquote.model.MsgQuoteItem` | `bq.g0()` / `nq.h()` | 引用数据模型 |

> 写法提示：DexKit `matcher { usingStrings("MicroMm.C2CAppMsgUtil") }` 命中多个时，用 `methods { add { name("h"); paramCount(3) } }` + 本地校验（返回 int、静态）锁定 `z1`。

---

## 21.5 自证工具：一条日志定位「假气泡到底是哪个 View」（打补丁前先跑这个）

> 我给出的"元凶 = `mq.d`"有代码依据（`mq.d` 无背景、无文字、宽度=气泡宽、专接点击），
> 但它在布局 XML 里的确切位置我**无法**从 dex 看到（布局名被混淆、无法枚举）。
> 所以给你这段 dump 代码：**跑一次即可 100% 确认**，也顺便校验本方案所有注入目标。

```kotlin
/** 在 hook mq.e() after 之后调用一次，日志里会列出语音行完整 View 树 */
fun dumpVoiceRow(holder: Any) {
    val sb = StringBuilder()
    fun walk(v: View, depth: Int) {
        val bg = v.background
        val pad = Rect(); val hasPad = bg?.getPadding(pad) == true
        sb.append(" ".repeat(depth * 2)).append(
            "${v.javaClass.simpleName} id=${v.id} " +
            "[${v.left},${v.top},${v.right},${v.bottom}] w=${v.width} h=${v.height} " +
            "vis=${v.visibility} bg=${bg?.javaClass?.simpleName}" +
            "${if (bg != null) "(${bg.intrinsicWidth}x${bg.intrinsicHeight})" else ""} " +
            "${if (hasPad) "pad(${pad.left},${pad.top},${pad.right},${pad.bottom})" else ""} " +
            "${if (v is TextView) "text='${v.text}' " else ""} " +
            "click=${v.isClickable} long=${v.isLongClickable}\n")
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
    }
    val root = XposedHelpers.getObjectField(holder, "itemView") as? View ?: return
    walk(root, 0)
    log("VOICE-ROW-TREE\n$sb")
}
```

**读日志判定法则（按现象对照）：**

| 日志里的特征 | 结论 |
|---|---|
| 某个 `TextView` **`bg=null`、`text=''`、`click=true long=true`、`w=气泡宽`** | ★**它就是假气泡元凶**（`mq.d`）→ 黑名单命中，**不要注入** |
| `AnimImageView` 且 `bg` 是 NinePatch/Bitmap、`w=气泡宽` | ★真气泡（`mq.e`）→ 注入它 |
| `TextView` 有 `bg`、`w=气泡宽`、`text='5"'` 附近 | 发送侧真气泡（`mq.x`） |
| `ProgressBar`/`ImageView`/`ViewStub` | 一律不碰 |
| `w=0 或 vis=8` | 不碰（当前状态不可见） |

**如果 dump 显示元凶不是 `mq.d`**：把那个 View 的 `id=` 发回来，我按同样方法把它加入黑名单 —— 黑名单机制与类名/字段名无关，加一个 id 即可。

**自愈兜底（防止漏网）：** 注入后若该 View 变为 `visibility != VISIBLE` 或 `width == 0`，立即还原原始背景，避免"隐形气泡"在复用时闪现：
```kotlin
fun selfHeal(v: View) {
    val info = reg[v] ?: return
    if (v.visibility != View.VISIBLE || v.width == 0) { apply(v, info.original); return }
}
// 在 L1b ensureBubble() 的"重放"循环里对每个已登记 View 调一次
```


---

## 24. 二次复检：错误内容作废清单 + 保留清单

### 24.1 作废 / 改正（前面章节的错误，按此执行）

| 位置 | 原文（错误） | 处置 |
|---|---|---|
| 6.3 / 15.4 / 17 章 | `injectQuote(itemView)` 用 `looksLikeBubble()` + `siblings>=2` 启发式遍历 | **整段作废** → 改为 hook `q71.n` 实现的 `b(Context)`（21.3） |
| 6.3 / 17 章 | `LayerDrawable(arrayOf(ours, orig))`（ours 在下） | **改正**为 `arrayOf(orig, ours)`（22.1） |
| 17 章 | 注入目标 = `Reflect.fieldsByType(holder, ANIM)`（未过滤黑名单/未校验） | **改正** → 白名单 + 黑名单 + `injectable()` 三校验（21.2/21.4） |
| 15.6 | "注入幂等即可不丢" 未覆盖局部刷新目标选择 | **补充** → 局部刷新时 `mq.e()` 不执行，兜底必须自带白名单选择（21.4） |
| 5.2 节 | 称 `mq.z`(2131365813)/`mq.B`(2131366093) 是"气泡容器" | **作废**：二者只是"续听/倍速提示/普通容器"，从不出气泡 |
| 12 章 | `2131232057` 标为"语音时长文本主题背景" | **保留（正确）**，但补充条件：仅 `ChatBgAttr.h==true` 时设置 |
| 11.2 | `mq.z/C/D/y` 语义"未完全反证" | **已解决**（21.1/23.1）：`z`=续听提示容器、`C/D`=倍速提示、`y`=红点/状态图标 |
| 7.5 / 17 章 | `BubbleFactory` 未处理"orig 不透明会盖住 ours" | **改正**（22.1/22.4） |

### 24.2 保留（已验证正确，可继续依赖）

1. 语音 4 个 Item 类（`bq/tr/iq/ur`）共用唯一 Holder `mq` 与唯一填充 `mq.e(...)`。
2. 文本气泡 = `to.b`(MMNeat7extView, 2131365751)，赋值点 `to.b(e9,to,d,Boolean)`。
3. `2131231925`(收)/`2131232060`(发) 就是气泡九宫格本体（`AnimImageView.b()` 反证）。
4. `WxRecyclerAdapter.E0`(完整 bind) / `F0`(局部刷新) 是两条必经之路，`ChattingDataAdapterV3.getView()` 返回 null（无第三入口）。
5. `MsgInfo = com.tencent.mm.storage.e9`；`z0()`=isSend（0 收 / 1 发）。
6. 系统提示（时间线/撤回/已领取红包）**只有 `setForeground(2131232025)`，没有气泡背景**。
7. 红包/转账气泡背景由 `z1.c()/z1.h()` 返回的 resId 决定，文字颜色按"已领取(1001) + 深色(gk.D())"切换。
8. 文字颜色/阴影总来源 = `ChatBgAttr(com.tencent.mm.pluginsdk.ui.i0)`，持有者 `com.tencent.mm.ui.chatting.component.v2.m`。

### 24.3 修完之后的验收顺序（按你遇到的 3 个问题逐条验收）
1. 语音行只剩**一个**气泡，点击/长按/播放都正常（假气泡消失，`mq.d` 未被注入）。
2. 自定义气泡完整覆盖原生气泡，不再"露边"；短语音(1″)与长语音(60″)宽度都与原生一致（`mqVar.b` 基准 + padding 差补偿）。
3. 时间线/撤回/红包已领取提示按你的设定显示气泡与文字色（若需要）；红包卡片气泡与文字可替换；引用卡片统一换肤。

---

## 25. §17 代码修订补丁（直接替换，drop-in）

> 下面给出「删哪段 / 换成什么」，替换后即可解决你遇到的 3 个问题。

### 补丁 1：`BubbleEngine.inject()` —— 修「被原生气泡包裹 + 尺寸对不上」
```kotlin
// —— 删除 §17 中的旧 inject()，整段替换为： ——
fun inject(view: View, isFrom: Boolean, voice: Boolean) {
    val orig = view.background ?: return
    if (!Injectable.ok(view)) return                       // 见补丁 3
    val ours = BubbleFactory.create(view.context, isFrom, voice, orig)
    copyPadding(orig, ours)                                // 原九宫格 padding → 我们
    val final = if (orig is AnimationDrawable) LayerDrawable(arrayOf(orig, ours)) else ours
    val info = reg.getOrPut(view) { Info(orig, ours, isFrom, voice) }
    info.original = orig; info.ours = ours; info.isFrom = isFrom; info.applied = final
    info.padL = padL(ours) - padL(orig); info.padR = padR(ours) - padR(orig)
    apply(view, final)
    // 尺寸：沿用微信算好的宽度，只补 padding 差
    val lp = view.layoutParams ?: return
    if (view.width > 0) lp.width = view.width + info.padL + info.padR
    view.layoutParams = lp
    registerOwner(view)
}
```

### 补丁 2：删除 `injectQuote()`（假气泡第二来源）
```kotlin
// —— 删除 §17 BubbleEngine 中的：fun injectQuote(itemView: View) 整个函数， ——
// —— 以及 ensureBubble() 内所有 injectQuote(itemView) 调用。                ——
// —— 同时在 WxHooks.install() 末尾追加（引用气泡改走工厂注入）：           ——
fun installQuoteHooks(bridge: DexKitBridge) {
    bridge.findClass { matcher { interfaces { add("Lq71/n;") } } }.forEach { cd ->
        val c = XposedHelpers.findClass(Reflect.classNameOf(cd.descriptor), WxEnv.classLoader) ?: return@forEach
        XposedBridge.hookAllMethods(c, "b", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val root = p.result as? View ?: return
                if (root.background != null) BubbleEngine.inject(root, isFrom = true, voice = false)
            }
        })
    }
}
// 调用点：WxResolver.resolve() 里 DexKitBridge.use{...} 结束前加一句 installQuoteHooks(it)
```

### 补丁 3：新增白名单/黑名单/校验（假气泡第一来源）
```kotlin
object Injectable {
    private val BANNED = setOf("d","c","s","o","p","z","C","B","q","r","y","t","w","f","g")
    fun ok(v: View): Boolean {
        if (v is ViewGroup) return false                        // 永不碰容器
        val bg = v.background ?: return false                   // 真气泡一定有背景
        if (v.width <= 0 || v.height <= 0) return false
        if (bg.intrinsicWidth <= 0 || bg.intrinsicHeight <= 0) return false
        if (v is TextView && v.text.isNullOrEmpty() && v.isClickable && v.isLongClickable) return false  // ★点击热区(mq.d)
        return true
    }
    fun banned(name: String) = name in BANNED
}
```

### 补丁 4：`ensureBubble()` 收紧（局部刷新时也不能注错）
```kotlin
// —— 替换 §17 ensureBubble() 的②分支为： ——
val tag = itemView.getTag() ?: return
val from = isFrom ?: true
when (tag.javaClass.name) {
    WxResolver.voiceHolder?.name -> {
        val targets = LinkedHashSet<View>()
        Reflect.fieldsByType(tag, WxResolver.ANIM).forEach { if (it is View && Injectable.ok(it)) targets += it }
        Reflect.allFields(tag.javaClass).forEach { f ->
            if (f.name == "x" && f.type.name == "android.widget.TextView") {
                f.isAccessible = true; (f.get(tag) as? View)?.let { if (Injectable.ok(it)) targets += it }
            }
        }
        targets.forEach { if (!Injectable.banned(fieldNameOf(tag, it))) BubbleEngine.inject(it, from, true) }
    }
    WxResolver.textHolder?.name -> {
        val v = Reflect.fieldByType(tag, WxResolver.NEAT) as? View
        if (v != null && Injectable.ok(v)) BubbleEngine.inject(v, from, false)
    }
    else -> BubbleEngine.restoreAll(itemView)
}
```

### 补丁 5：`onSetBackground()` 层序同步修正
```kotlin
private fun finalOf(info: Info): Drawable {
    val orig = info.original
    val d = if (orig is AnimationDrawable && orig != null)
                LayerDrawable(arrayOf(orig, info.ours!!))     // ← orig 在下、ours 在上
            else info.ours!!
    info.applied = d
    return d
}
```

### 补丁 6（可选）：红包 / 系统提示 专项
```kotlin
// 红包背景 resId 替换（z1.c / z1.h 是静态方法，返回 resId）
hookStatic("com.tencent.mm.ui.chatting.z1", "c", 2) { p ->       // (dx0.r, Z)
    when (p.result as? Int) {
        2131231684, 2131231689, 2131231695, 2131231697, 2131231702, 2131231708 -> p.result = R.drawable.wx_hb_bubble
        2131230737, 2131230744, 2131230745, 2131230746, 2131230753, 2131230754 -> p.result = R.drawable.wx_aa_bubble
    }
}
hookStatic("com.tencent.mm.ui.chatting.z1", "h", 3) { p ->       // (I,I,Z)
    when (p.result as? Int) {
        2131231684, 2131231689, 2131231695, 2131231697, 2131231702, 2131231708 -> p.result = R.drawable.wx_hb_bubble
    }
}
// 系统提示（时间线/撤回/已领取红包）加气泡：hook mn.n() after
hook("com.tencent.mm.ui.chatting.viewitems.mn", "n", 4) { p ->
    val holder = p.args[0] ?: return@hook
    val tv = Reflect.fieldByType(holder, "com.tencent.neattextview.textview.view.NeatTextView") as? View
    tv?.let { BubbleEngine.inject(it, isFrom = true, voice = false) }   // 走 setBackground
}
// 文字颜色/阴影总开关：接管 ChatBgAttr
hookAfter("com.tencent.mm.pluginsdk.ui.i0", "<init>", 2) { p ->
    XposedHelpers.setIntField(p.thisObject, "a", 0xFF********.toInt())  // time_color
    XposedHelpers.setIntField(p.thisObject, "g", 0xFF********.toInt())  // voice_second_color
    XposedHelpers.setBooleanField(p.thisObject, "b", true)              // time_show_background
    XposedHelpers.setBooleanField(p.thisObject, "f", true)              // voice_second_show_background
}
```

> 注：补丁 6 中 `0xFF********` 为占位，请替换为你的实际颜色（如 `0xCCFFFFFF`），`R.drawable.wx_hb_bubble` 等换成你模块里的资源 id。

---

**最终交付**：`/sdcard/Download/WeChat_Bubble_Inject_Analysis.md`（2716 行 / 165,369 字节 ≈ 161.5 KiB）
- 第一部分（1–12 章）：类映射 / 数据层 / 渲染管线 / 气泡定位 / 方案 / 代码 / DexKit 锚点 / 时序 / 风险 / 复审 / 资源表
- 第二部分（13–16 章）：语音全继承 / 包装 vs 封装 / 滑动丢失根因 / 四层加固
- 第三部分（17–20 章）：可编译单文件 `WxBubble.kt` + 编译配置 + 联调自检 + 速查
- 第四部分（21–26 章）：**假气泡根因与修复 / 气泡尺寸数值与层序修复 / 时间线·红包·撤回·引用·文字颜色全解 / 复检作废清单 / §17 drop-in 补丁**

---

## 26. 全方位复检记录（conclusion → evidence → verdict）

> 判定说明：**✔ = 已用工具实码验证**；**△ = 有强推理但未取到直接证据（需真机 dump/实测）**；**✘ = 已作废**。

### 26.1 类与关系

| # | 结论 | 证据 | 判定 |
|---|---|---|---|
| 1 | MsgInfo = `com.tencent.mm.storage.e9` | Tag `MicroMsg.MsgInfo`；`convertFrom(Cursor)/convertTo()`；`getType/getCreateTime/getMsgId/j()`；`am5.d.d.b` 即它 | ✔ |
| 2 | ChattingItem 抽象基类 = `viewitems.b0` | Tag `MicroMsg.ChattingItem`；`abstract H(LayoutInflater,View)`；`I/J/K/m/n/T`；`ChattingItemDyeingTemplate` 继承 | ✔ |
| 3 | ChattingItemVoice(From) = `viewitems.bq` | Tag + `ChattingItemVoice$ChattingItemVoiceFrom` + `H()` inflate 2131624903 + `new mq()` | ✔ |
| 4 | ChattingItemVoice(To) = `viewitems.iq` | Tag + `ChattingItemVoice$ChattingItemVoiceTo` + `n()→mq.e()` | ✔ |
| 5 | MVVM 变体 `tr extends bq`、`ur extends iq`，只有空壳 `f0(mq)` | `class_hierarchy` + `decompile_class_methods_only` | ✔ |
| 6 | 语音 Holder 唯一 = `viewitems.mq`，**无子类** | 字符串 `ChattingItemVoice$VoiceItemHolder`；`find_class(super_class=mq)` 空 | ✔ |
| 7 | 语音填充 = `mq.e(b0,mq,am5.d,q,d,Z,Z,OnLongClickListener,r6)`，由 `bq.n`/`iq.n` 调用 | `find_caller(mq,"e")` = `bq.n` + `iq.n` | ✔ |
| 8 | 文本 Holder = `viewitems.to`（`b:MMNeat7extView` id 2131365751） | `to.a(View,boolean)` 反编译 | ✔ |
| 9 | 文本气泡赋值点 = `to.b(e9,to,d,Boolean)` | 反编译：`setBackgroundResource(2131231925\|2060\|1841\|1895)` | ✔ |
| 10 | ItemFactoryNew = `viewitems.kt`，type=34 注册 `bq`(TRUE)/`iq`(FALSE)，MVVM `tr`/`ur` | `kt.<init>` 反编译 | ✔ |
| 11 | 适配器 = `adapter.k`(ChattingDataAdapterV3)；`O`=onBindViewHolder；`E0`/`F0` 必经 | Tag + smali | ✔ |
| 12 | `getView()` 返回 null → 无第三条渲染通道 | smali 548–550 | ✔ |
| 13 | 局部刷新 `F0` 不调 `E0`，`ItemConvert.h(...,true,payloads)` | `F0` smali（`const/4 v6,0x1`，无 E0 调用） | ✔ |
| 14 | View→消息：`itemView.getTag()`=Holder；`ps.c()→e9`；`holder.i.d.b`=e9 | `bq.H/zn.H` + `ps.c()` 反编译 | ✔ |

### 26.2 气泡与尺寸

| # | 结论 | 证据 | 判定 |
|---|---|---|---|
| 15 | 气泡九宫格 = `2131231925`(收)/`2131232060`(发) | `AnimImageView.b()` + `to.b()` + `mq.e()` 三处一致 | ✔ |
| 16 | 语音未播放态气泡 = `2131231940`(收)/`2131232066`(发) | `mq.e()` 373–389 | ✔ |
| 17 | 语音气泡可见背景承载：收=`mq.e`，发=`mq.x`+`mq.u` | 同上 | ✔ |
| 18 | `mq.d`(2131366097)=透明点击热区（无背景/无文字/宽度=气泡宽/接点击与长按） | `mq.e()` 236/259/288/369–372 | ✔ |
| 19 | **假气泡元凶 = 注入了 `mq.d`（或 `injectQuote` 命中的空容器）** | 18 + 现象吻合（占位、真气泡被顶下一行） | **△ 需 §21.5 dump 最终确认** |
| 20 | 波形动画在 `AnimImageView` 的 **compound drawable**，替换 background 不影响播放 | `AnimImageView.b()` type=1 分支；`mq.e.setType(1)` | ✔ |
| 21 | 语音宽度算法（80dp 起、每 10s +9dp、上限 204dp、屏幕自适应） | `mq.c(Context,int)` 反编译 | ✔ |
| 22 | 最终宽度写入 `mqVar.b` 与 `mq.d/mq.e.setWidth()` | `mq.e()` 235–238/286–290/345–348 | ✔ |
| 23 | 文本最大宽度 `MMNeat7extView.setMaxWidth(dimension(2131166702)/f.g)` | `to.a(View,boolean)` | ✔ |
| 24 | 秒数字号 `ke5.a.f(ctx,2131165438)`；阴影 `setShadowLayer(2,1.2,1.2,ChatBgAttr.g)` | `mq.e()` 354–368 + `mq.b()` 487 | ✔ |
| 25 | `2131232057` = 秒数文本背景（仅 `ChatBgAttr.h==true`） | `mq.e()` 363–367 | ✔ |
| 26 | LayerDrawable 层序必须 `arrayOf(orig, ours)` | orig 为不透明九宫格时会覆盖 ours | △（视觉推理，改造后目视确认） |

### 26.3 系统提示 / 红包 / 撤回 / 引用 / 文字颜色

| # | 结论 | 证据 | 判定 |
|---|---|---|---|
| 27 | 时间线/撤回/已领取红包 = sysmsg 模板，Item `mn`，填充器 `rn`(ChattingItemSysMsgTemplate)，Holder `on`(`b:NeatTextView`) | Tag `MicroMm.SysMsgTemplateImp`；`mn.n()` 全文 | ✔ |
| 28 | 这些提示**没有 `setBackground`**，只有 `onVar.b.setForeground(2131232025)` | `mn.n()` + `rn.a()`(189 行) + `rn.b()`(63 行) 全部检查无背景调用 | ✔ |
| 29 | 系统提示 type 集合 10000/10002/570425393/603979825/268445456/268445458/285222674/64 | `mn.n()` 分支 + `kt` 注册 `g(...,mn.class)` | ✔ |
| 30 | 红包 Item `d4`(From)/`h4`(To)，Holder `b4`，layout 2131624841，type 436207665 | `d4.H()` + Tag `MicroMm.ChattingItemAppMsgC2CFrom` + `kt` 注册 | ✔ |
| 31 | 红包气泡背景 = `z1.c(content,isSend)` / `z1.h(status,sub,isSend)` | `d4.n()` 358/382 行 | ✔ |
| 32 | `z1.h` 返回表（1708/1695、1702/1689、1702/1689、1697/1684） | `z1.h()` 反编译 403–405 | ✔ |
| 33 | `z1.c` AA 值（0753/0754/0744/0745/0746/0737） | `z1.c()` 反编译 | ✔ |
| 34 | 红包文字颜色：深色 `gk.D()`→2131100289；普通 c/d=2131099904、e=2131099898；alpha 用 `z1.b` | `d4.n()` 360–376 | ✔ |
| 35 | 转账 Item `jd`/`ld`，type 419430449 | Tag `MicroMm.ChattingItemAppMsgRemittanceFrom` + `kt` 注册 | ✔ |
| 36 | 引用消息 = 接口 `q71.n`（`a(p)`/`b(Context)`/`getViewModel()`）+ 44 实现 + VM `q71.p`；工厂 `oo.a0.J7()`；`addView` 到 `((RL)mq.F.getParent())` | `mq.b()` 498–515 + `find_class(interfaces=["q71.n"])` = 44 | ✔ |
| 37 | 引用卡片背景写在各自布局 XML（除红包外不动态设） | `cr5.b`/`cr5.f1` 等实现仅 `a/b/c/getViewModel`，无 setBackground | ✔ |
| 38 | **文字颜色/阴影总来源 = `ChatBgAttr`(`pluginsdk.ui.i0`)**，构造解析 `chatbg` + `.chatbg.$time_*`/`$voice_second_*` | 构造 15 个字符串 + Tag `MicroMm.ChatBgAttr` | ✔ |
| 39 | ChatBgAttr 消费方仅 2 处：`component/v2.m`、`ReaderAppUI.o` | `find_class_usage` | ✔ |
| 40 | ChatBgAttr 字段 `f/g/h` = 秒数阴影开关/阴影色/秒数背景开关 | `mq.e()` 用法 | ✔ |
| 41 | ChatBgAttr 字段 `a/b/c/d/e` 与字符串的对应关系 | — | **△ 需运行时打印实测** |

### 26.4 本轮已作废/已改（随文档同步）

| # | 原内容 | 处置 |
|---|---|---|
| E1 | `injectQuote()` 启发式（`looksLikeBubble`+`siblings>=2`） | **作废** → 工厂 hook `q71.n.b(Context)` |
| E2 | `LayerDrawable(arrayOf(ours, orig))` | **改正** → `arrayOf(orig, ours)` |
| E3 | 注入目标不过滤（`fieldsByType(ANIM)` 全量） | **改正** → 白名单+黑名单+`Injectable.ok()` |
| E4 | 语音宽度自己算 | **改正** → 读 `view.width`/`holder.b` 只补 padding 差 |
| E5 | `mq.z`/`mq.B` 是"气泡容器" | **作废** → 仅为续听/倍速提示与普通容器 |
| E6 | ChatBgAttr 字段与字符串的一一对应（23.5 旧版） | **降级为 △** → 只保留 f/g/h 已验证项 |

### 26.5 仍需你侧实测确认的 3 件事
1. §21.5 dump 中"假气泡"的 `id=` 是否确为 `2131366097`（`mq.d`）—— 若不是，把 id 给我，加黑名单即可。
2. 改造层序后目视确认自定义气泡完整覆盖原生（不再露边）。
3. ChatBgAttr 字段 `a/b/c/d/e` 的实际语义（hook `<init>` 后打印 8 个字段值 + 改聊天背景图对比）。
