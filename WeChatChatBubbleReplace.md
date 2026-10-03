# 微信聊天对话气泡替换 —— 地毯式深挖报告（修正版 v2）

> 分析对象：`com.tencent.mm`（base.apk）
> 工具：LSPilot（DexKit 定位 + jadx 反编译 + baksmali 逐层核查）
> **v2 修正原因**：v1 结论（hook X2C 的 `kw5.g.r` 按 `chatfrom_bg` 名字替换）经深挖被证伪——**文本气泡的真实设置根本不在 X2C 链路上**。本文给出经 Smali 核查的完整 UI 封装链路与真正有效的替换点。

---

## 0. 先说结论：为什么之前的方法无效

本版本微信的聊天气泡有**两套互不相干的设置路径**：

| 路径 | 谁在用 |  bubble drawable | 设置方式 |
|---|---|---|---|
| **X2C 创建期路径**（v1 报告聚焦点） | `gm.g/gm.i`（chatting_item_from/to）等 X2C 生成类 | chatfrom_bg(2131231925)/chatto_bg(2131232060) | `kw5.g.r` → `kw5.i0.f` → `setBackground` |
| **Bind 填充期路径（真实生效）** | 文本 item `hn5.v/hn5.n0`（普通）、`hn5.r0/hn5.s0`（链接子类型） | **2131231925 / 2131232060 / 2131231944 / 2131232070 / 2131231841 / 2131231895** | `setBackgroundResource(int)` 直接调用 |

深挖证实：
1. **文本消息 item 的实际布局是 `0x7f0e0382`（二进制 XML），不在 X2C 注册表里**；X2C 的 `chatting_item_from/to`（gm.g/gm.i）不在文本 item 的加载路径上（只有"默认 item" `viewitems.d2` 用 X2C 的 from_appmsg）。
2. 气泡在**每次 bind 填充时**由代码重新设置：`viewitems.to.b(msg, holder, ctx, isRecv)` → `setBackgroundResource(2131231925/2131232060)`；链接文本子类型再由 `hn5.r0.g0`/`hn5.s0.k0` → `setBackgroundResource(2131231944/2131232070)`。
3. 所以 hook `kw5.g.r`（匹配 "@drawable/chatfrom_bg"）对文本气泡**完全不触发**——这就是"无任何效果"的根因。

**有效的替换点（详见 §5）**：hook `View.setBackgroundResource(int)` 与 `View.setBackground(Drawable)`，按 resId 替换（自校准方向）；或精准 hook `viewitems.to.b(...)`（静态方法，自带 `isRecv` 方向参数与 holder）。

---

## 1. 聊天界面 UI 封装全景（从 Activity 到气泡视图）

```
com.tencent.mm.ui.chatting.ChattingUI                       （MMSecDataFragmentActivity → BaseMvvmFragmentActivity → VASLauncher）
└─ ChattingUIFragment                                        （f 字段 B = MMChattingListView）
   └─ com.tencent.mm.ui.chatting.view.MMChattingListView      （MMPullDownView → FrameLayout，消息列表）
      └─ 适配器 ChattingDataAdapter → com.tencent.mm.view.recyclerview.WxRecyclerAdapter（MvvmList 架构）
         └─ ItemConvert：xl5.g（每个 viewType 一个实例）
            ├─ c(recyclerView)        创建 item 视图
            ├─ d(recyclerView, view) 组装 ViewHolder（tag 机制）
            └─ h(holder, item, ...)   bind 填充
               └─ Item 工厂：viewitems.lt.a(ctx) → viewitems.kt（"MicroMsg.ItemFactoryNew"）
                  ├─ kt.c(msg)  计算 viewType（jt.invoke：type + subtype + isRecv + 子类型 index）
                  └─ kt.b(key)  Class.newInstance() 造 item（默认 viewitems.d2）
                     └─ item.H(inflater, null)   ← ChattingItem 基类 viewitems.b0 的抽象方法
                        └─ new viewitems.jh(inflater, 内容布局resId)   ← jh = ChattingItemContainer
                           └─ jh 构造函数：建"展开"TextView → inflate(0x7f0e0349 历史消息提示)
                              → CheckBox(多选) → inflate(内容布局, null) 挂到自己身上
```

### 1.1 各层关键类速查

| 层 | 类（本构建混淆名） | 职责 | 关键方法/证据 |
|---|---|---|---|
| 聊天页 Fragment | `com.tencent.mm.ui.chatting.ChattingUIFragment` | 持列表 | 字段 `B: MMChattingListView` |
| 消息列表 | `com.tencent.mm.ui.chatting.view.MMChattingListView` | 列表容器 | extends `MMPullDownView` |
| 适配器 | `ChattingDataAdapter` + `WxRecyclerAdapter` | MvvmList RecyclerView | `adapter.k.getView/S/O/G0/E0` |
| ItemConvert | `xl5.g` | 每 viewType 的转换器 | `c()/d()/h()`（见 §1.2） |
| Item 工厂 | `viewitems.lt` → `viewitems.kt`（implements `viewitems.os`） | viewType→Item 类 | 日志 `MicroMsg.ItemFactoryNew`；`kt.b(I)newInstance`；默认 `viewitems.d2` |
| 注册逻辑 | `kt` 构造器 + `kt.f(from,to,Class,isRecv)` / `kt.h(...,lambda)` | 填充 map `b: key=rs.a(type,sub,isRecv,idx)` | smali 实测 |
| Item 基类 | `viewitems.b0`（= ChattingItem） | 所有消息 item 基类 | `H(LayoutInflater,View)` 抽象；`i(View,isRecv,needMargin)` 设 6dp 边距；`r(...)` 全文截断 |
| **Item 容器** | `viewitems.jh`（smali 内字符串 `ChattingItemContainer`） | **真正的 item 视图（RelativeLayout）** | ctor `(LayoutInflater, 内容布局resId)` |
| 文本 item | `hn5.v`（收）/`hn5.n0`（发）普通；`hn5.r0`（收）/`hn5.s0`（发）链接子类型 | 文本消息 | `hn5.v.d` 日志 `MicroMsg.ChattingItemTextFrom`；继承 `viewitems.zn → b0` |
| 文本 ViewHolder | `viewitems.to` | 持有气泡视图 | `b: MMNeat7extView`（正文/气泡）；`g: ProgressBar`（发送中）；`f: AnimImageView`；`e: ChattingItemTranslate` |
| 语音 item | `viewitems.tr`（发）/`ur`（收），type 34 | 语音 | `kt.h(0x22,0,tr/ur,...)` |
| 默认 item | `viewitems.d2` | 未知类型兜底 | `H → new jh(inflater, 0x7f0e03b9)`（X2C from_appmsg） |
| 媒体类 item | `kn5.h7`（发）/`kn5.j7`（收），types {3,23,13,39,33} | 图片/卡片等 | `H → new jh(inflater, 0x7f0e03c3)` + DataBinding(`m7`) |

### 1.2 ItemConvert（xl5.g）三个关键方法

```java
// 创建 item 视图（xl5.g.c）
public View c(RecyclerView rv) {
    b0 item = lt.a(ctx).b(viewType);                 // viewitems.kt.b → newInstance
    View H = item.H(inflater, null);                 // b0.H → new jh(inflater, 内容布局)
    H.setTag(0x7f0a103c /*2131365948*/, item);       // tag[ChattingItem] = item
    return H;
}
// 组装 ViewHolder（xl5.g.d）
public s0 d(RecyclerView rv, View convertView) {
    ChattingItem item = (ChattingItem) convertView.getTag(2131365948);
    BaseViewHolder holder = (BaseViewHolder) convertView.getTag();
    ((h0) holder).setChattingItem((b0) item);
    return new e(convertView);
}
```

### 1.3 ChattingItemContainer（viewitems.jh）构造函数（smali 实测）

```java
public jh(LayoutInflater inflater, int contentLayoutResId) {   // extends RelativeLayout
    // 1) "展开/全文" TextView（X2C 视图池 kw5.q1.c(ctx,"X2CTextView") 取实例，id 0x7f0a1075）
    // 2) inflater.inflate(0x7f0e0349 历史消息提示布局, null)  id=0x7f0a0fc3
    // 3) CheckBox 多选框（setBackgroundResource(0x7f0811ae), id 0x7f0a0f6c）
    // 4) inflater.inflate(contentLayoutResId, null)            ← 真正的消息内容布局！
    //    内容根 id 兜底 0x7f0a0f74；容器自身 id 兜底 0x7f0a0ff4
    // 全程用 ym0/a.d(...) 做 "android/view/View_EXEC_" 埋点（Matrix 监控）
}
```

### 1.4 文本消息的 item 与布局（重点）

- `viewitems.zn.H(inflater, null)` → `new jh(inflater, 0x7f0e0382)` + `new viewitems.to().a(view, true)` 作 tag。
- **`0x7f0e0382` 不在 X2C 注册表（BootX2CFactory 的 24 个映射里没有它）→ 二进制 XML 布局**；气泡背景不来自 XML，完全由代码在 bind 时画上去。
- `viewitems.to` ViewHolder：`b` = `MMNeat7extView`（**气泡 = 这个 TextView 的 background**）。

---

## 2. 气泡资源 resId 全景表（本构建实测，全部经 Smali 核查）

| 场景 | 对方（收到） | 自己（发出） | 设置点（类.方法） |
|---|---|---|---|
| **普通文本（最终态）** | **2131231925**（chatfrom_bg，0x7F0804B5） | **2131232060**（chatto_bg，0x7F08053C） | `viewitems.to.b(e9,to,d,Boolean isRecv)` 静态方法 |
| 发送中/高亮分支（`msgId == j2.f`） | 2131231841 | 2131231895 | 同上 `to.b`（同时显示 ProgressBar `to.g`） |
| **链接/自动识别文本子类型**（`y3.O4(msg) && !ctx.F()` 命中时用 hn5.r0/s0） | **2131231944** | **2131232070** | `hn5.r0.g0(MMNeat7extView)` / `hn5.s0.k0(MMNeat7extView)` |
| AppMsg（仅"默认 item" d2 走 X2C） | chat_from_mask_bg = 2131231853（背景+前景） | chatto_bg_app = 2131232062（背景）+ chat_to_mask_bg = 2131231907（前景） | `kw5.g.r → kw5.i0.f/n`（gm.f/gm.h，X2C 创建期） |
| X2C 旧链路（chatting_item_from/to，gm.g/gm.i，文本 item 不走） | chatfrom_bg = 2131231925 | chatto_bg = 2131232060 | `kw5.g.r → kw5.i0.f` |

> 方向判定依据（三重证据）：① `kt` 注册表 key 的 bool = `!isSend`（`jt.invoke` 第 100 行 `rs.a(type, sub, !f0.i(msg), idx)`）；② 发送 item 注册 `FALSE`、接收 item 注册 `TRUE`；③ `hn5.v.d` 日志 tag 为 `MicroMsg.ChattingItemTextFrom`（From = 收到方），且其填充块 `hn5.q.invoke` 调用 `to.b(msg, holder, ctx, Boolean.TRUE)`。
> 注意：`to.b`（普通态）与 `g0/k0`（链接子类型）的**最终赢家**取决于 `hn5.v.d` 中 `e.b() && e.d()` 两个配置开关（未静态解析）——所以 **6 个 resId 全部要纳入替换表**（见 §5 方案 1）。

---

## 3. 文本气泡 bind 填充链路（逐层，含调用顺序）

```
xl5.g.h(holder, item, ...)                                  // onBindViewHolder
└─ hn5.v.n(h0, d, msgData, str)                             // 文本 item 填充入口
   └─ hn5.v.d(d, msgData, str, a1 uiBlocks)                 // 异步准备 + 注册 UI 块（241 行）
      ├─ a1Var.d(new hn5.p(...))        → hn5.p.invoke(to)：
      │     to.b 视图 setTag(2131365951 msgId / 2131365950 / 2131365949)
      │     item.g0(contentITV)          ← 多态：普通 item(v/n0) 为空实现；链接子类型 r0/s0 生效
      │         r0.g0: setMaxWidth + setBackgroundResource(2131231944) + b0.i(view,true,true)
      │         s0.k0: setMaxWidth + setBackgroundResource(2131232070) + b0.i(view,false,true)
      ├─ a1Var.d(new hn5.q(...))        → hn5.q.invoke(to)：   （条件 e.b() && e.d()）
      │     to.b(msg, holder, ctx, Boolean.TRUE)：             ← 普通态气泡最终设置点
      │         msgId == j2.f(发送中) → progressbar 显示 + setBackgroundResource(isRecv?2131231841:2131231895)
      │         否则                   → setBackgroundResource(isRecv?2131231925:2131232060)
      │         msgId == j2.e → AnimImageView(to.f) 动画
      ├─ a1Var.d(new hn5.r/s/t/o/...))                       // 点击、长按、翻译、全文等
```

关键源码（smali/jadx 实测）：

```java
// viewitems.to.b —— 普通态气泡（静态包装方法，注意第 4 参 isRecv）
public static void b(e9 msg, to holder, d ctx, Boolean isRecv) {
    if (msg.getMsgId() == ((j2) ctx.c.a(j2.class)).f) {      // 发送中的消息
        holder.g.setVisibility(0);                            // ProgressBar
        holder.b.setBackgroundResource(isRecv ? 2131231841 : 2131231895);
    } else {
        holder.g.setVisibility(8);
        holder.b.setBackgroundResource(isRecv ? 2131231925 /*chatfrom_bg*/ : 2131232060 /*chatto_bg*/);
    }
    if (msg.getMsgId() == ((j2) ctx.c.a(j2.class)).e) { holder.f.setVisibility(0); holder.f.b(); }
    else { holder.f.setVisibility(8); holder.f.c(); }
}

// hn5.r0.g0 —— 链接文本子类型（收到方）
public void g0(MMNeat7extView contentITV) {
    contentITV.setMaxWidth((int) (gk.p(0.88f) / f.g));
    contentITV.setBackgroundResource(2131231944);
    i(contentITV, true, true);
}
// hn5.s0.k0 —— 链接文本子类型（发出方）
public void k0(MMNeat7extView contentITV) {
    contentITV.setMaxWidth((int) (gk.p(0.88f) / f.g));
    contentITV.setBackgroundResource(2131232070);
    i(contentITV, false, true);
}

// viewitems.b0.i —— 气泡边距微调（左/右 6dp，跟随方向）
public void i(View view, boolean isRecv, boolean needMargin) { /* margin 6dp */ }
```

---

## 4. X2C 链路现状（为何只对 AppMsg 默认 item 有效）

- X2C 注册表：`com.tencent.mm.autogen.layout.BootX2CFactory extends kw5.i`，构造器注册 **24 个** 布局 → 生成类：
  `gm.g=chatting_item_from(0x7F0E03CB, chatfrom_bg 2131231925)`、`gm.i=chatting_item_to(0x7F0E041C, chatto_bg 2131232060)`、`gm.f=from_appmsg(0x7F0E03B9, chat_from_mask_bg 2131231853)`、`gm.h=to_appmsg(0x7F0E040C, chatto_bg_app 2131232062 + chat_to_mask_bg 2131231907)`、`gm.d/gm.e`=头像 from/to、`gm.a`=history_msg_tip 等。
- 文本实际布局 `0x7f0e0382`、媒体 item 布局 `0x7f0e03c3` **均不在注册表** → 二进制 XML。
- X2C 开关 `RepairerConfigX2COpenFlag`（默认=开，`kw5/q0.f`），但当布局无生成类时 `com.tencent.mm.ui.hd`(MMLayoutInflater) 自动兜底 `super.inflate()` 走二进制 XML。
- 结论：**hook `kw5.g.r`/`kw5.i0.f` 只能影响"默认 item"的 AppMsg 气泡，对文本气泡无效。**

---

## 5. 气泡替换方法（按推荐度排序，附 Xposed 代码）

### 方案 1（主推·全类型通用·抗混淆）：hook `View.setBackgroundResource(int)` + `View.setBackground(Drawable)`

原理：无论气泡来自 bind 代码、二进制 XML（`android:background` 最终也走 `setBackground`）、DataBinding，**最后都落到 View 的这两个方法**。按 resId 白名单替换即可，与类名混淆、X2C 开关无关。

```java
// resId → 自定义气泡（方向映射见下文"自校准"）
private int RES_RECV = 0, RES_SEND = 0;                 // 运行时采集后填入
private static final Set<Integer> RECV_IDS = new HashSet<>(Arrays.asList(
    2131231925, 2131231841, 2131231944));               // 对方：chatfrom_bg / 高亮 / 链接子类型
private static final Set<Integer> SEND_IDS = new HashSet<>(Arrays.asList(
    2131232060, 2131231895, 2131232070));               // 自己：chatto_bg / 高亮 / 链接子类型

public void hookBubble(ClassLoader cl) {
    // 1) 整数资源入口（气泡主路径）
    XposedHelpers.findAndHookMethod(View.class, "setBackgroundResource", int.class, new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            Integer rep = mapRes((int) param.args[0]);
            if (rep != null) param.args[0] = rep;
        }
    });
    // 2) Drawable 入口（XML/databinding/前景）
    XposedHelpers.findAndHookMethod(View.class, "setBackground", Drawable.class, new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            Drawable d = (Drawable) param.args[0];
            Integer rep = mapRes(resIdOf(d));             // 用"已知气泡 drawable 的 ConstantState"反查
            if (rep != null) param.args[0] = param.thisObject...
        }
    });
}
```

**方向自校准（不依赖上表的硬编码）**：首次进入聊天页时，hook 以上两方法，对每个命中的 `MMNeat7extView`（`com.tencent.mm.ui.widget.MMNeat7extView`，类名未混淆）打印 `resId + getLocationOnScreen`：
- 视图右缘离屏幕右半边近 → **自己（发出）**；离左半边近 → **对方（收到）**。
- 用采集结果动态建表，之后同一会话/跨会话复用（resId 在同一 APK 构建内稳定）。

### 方案 2（精准·仅文本气泡）：hook `viewitems.to.b` 与 `hn5.r0.g0 / hn5.s0.k0`

```java
// to.b(e9 msg, to holder, d ctx, Boolean isRecv) —— 普通态最终设置点，自带方向
XposedHelpers.findAndHookMethod("com.tencent.mm.ui.chatting.viewitems.to", cl, "b",
    "com.tencent.mm.storage.e9", "com.tencent.mm.ui.chatting.viewitems.to",
    "gk5.d", Boolean.class, new XC_MethodHook() {
    @Override protected void afterHookedMethod(MethodHookParam param) {
        Object holder = param.args[1];
        boolean isRecv = (Boolean) param.args[3];
        View bubble = (View) XposedHelpers.getObjectField(holder, "b");   // MMNeat7extView
        bubble.setBackground(isRecv ? myRecvBubble : mySendBubble);
    }
});
// 链接文本子类型（类名每版会变，需按 §6 锚点重新定位）
XposedHelpers.findAndHookMethod("hn5.r0", cl, "g0", "com.tencent.mm.ui.widget.MMNeat7extView", ...);
XposedHelpers.findAndHookMethod("hn5.s0", cl, "k0", "com.tencent.mm.ui.widget.MMNeat7extView", ...);
// after 里对 param.args[0]（MMNeat7extView）直接 setBackground
```
优点：语义最清晰（方向由参数/类直接给出）、调用频率低、不影响其他 UI。

### 方案 3（资源级·覆盖 XML/DataBinding 路径）

```java
// ① hook WeChat 资源包装（仅覆盖 App 内部代码路径）
XposedHelpers.findAndHookMethod("ke5.a", cl, "i", Context.class, int.class, new XC_MethodHook() {
    @Override protected void beforeHookedMethod(MethodHookParam param) {
        Integer rep = mapRes((int) param.args[1]);
        if (rep != null) param.setResult(rep);             // 返回自定义 Drawable
    }
});
// ② 或 XResources 按 resId 替换（覆盖二进制 XML，资源名已被微信混淆，按名替换不可行）
xres.setReplacement(0x7F0804B5 /*chatfrom_bg*/, modRes);  // 注意每次构建 resId 会变
```

### 方案 4（X2C 层）：仅对"默认 item"的 AppMsg 气泡有效，见 §4。文本气泡勿用。

---

## 6. 跨版本锚点定位法（类名/resId 每个构建都会变）

1. **`MMNeat7extView` 类名未混淆**（`com.tencent.mm.ui.widget.MMNeat7extView`）→ 气泡视图类型锚点。
2. 找文本 item 基类：`search_strings("MicroMsg.ChattingItemTextFrom")` → `hn5.v`；其继承链 `→ viewitems.zn → viewitems.b0`。
3. 找 Item 工厂：`search_strings("MicroMsg.ItemFactoryNew")` → `viewitems.kt`；构造函数里 `kt.f(type, sub, Class, isRecv)` 的注册表给出 type→item 类映射（type 1 = 文本）。
4. 找气泡设置点：在文本 item 填充链中搜 `setBackgroundResource` 调用；或直接 hook `View.setBackgroundResource` 真机打印 resId 列表（最稳）。
5. 找 X2C 注册表：`search_strings("chatfrom_bg")` / `"MicroMsg.X2C"` → `BootX2CFactory` / `kw5.*`。
6. **真机自校准**（推荐每次换版本都做）：临时 hook `View.setBackgroundResource(int)`，打印 `(resId, view.getClass(), view 在屏幕中的 x/width)`，翻一遍聊天记录即可得到完整的"气泡 resId ↔ 方向"表。

---

## 7. 二次核查记录（v2 导出前逐项验证）

| # | 结论 | 核查方式 | 结果 |
|---|---|---|---|
| 1 | 文本 item 布局 = 0x7f0e0382（非 X2C） | `zn.H` smali；对照 BootX2CFactory 24 项注册表逐一比对 | ✔ 不在注册表 |
| 2 | 普通态气泡 = chatfrom_bg(2131231925)/chatto_bg(2131232060)，设置点 `to.b(e9,to,d,Boolean)` | jadx 反编译 `to.b` 全文 + `hn5.q.invoke` 调用点 | ✔ |
| 3 | 链接子类型气泡 = 2131231944(收)/2131232070(发)，设置点 `hn5.r0.g0`/`hn5.s0.k0` | jadx 反编译 + 工厂注册（r0=TRUE=isRecv，s0=FALSE=isSend） | ✔ |
| 4 | 方向映射：hn5.v/n0 = 收/发；r0/s0 = 收/发 | `kt.f/h` 注册参数 + `jt.invoke` key 计算 `!f0.i(msg)` + 日志 tag "ChattingItemTextFrom" + q 块传 `Boolean.TRUE` | ✔ 四证据一致 |
| 5 | item 容器 = `viewitems.jh`（smali 字符串 `ChattingItemContainer`），ctor 里 `inflate(内容布局)` | jh 完整 smali（748 行）逐段核查 | ✔ |
| 6 | Item 工厂 = `viewitems.kt`，`b(I)newInstance`，默认 `viewitems.d2` | kt.b smali + 构造器注册代码 | ✔ |
| 7 | X2C gm.g/gm.i(chatfrom_bg/chatto_bg) 不在文本 item 路径 | 注册表 24 项枚举 + zn.H 用 0x7f0e0382 | ✔ |
| 8 | `to.b` 发送中分支 2131231841/2131231895 + ProgressBar 逻辑 | jadx 反编译 | ✔ |
| 9 | AppMsg 仅默认 item d2 走 X2C（gm.f from_appmsg，mask bg 2131231853/2062/1907） | d2.H smali + gm.f/gm.h 反编译 | ✔ |
| 10 | hook `View.setBackground(int)` 可覆盖二进制 XML 的 `android:background` | Android 框架行为（View ctor 走 setBackground） | ✔（框架语义） |

### 遗留不确定性（如实说明）

- `to.b` 与 `g0/k0` 谁最终生效取决于 `hn5.v.d` 中 `e.b() && e.d()` 两个运行时配置开关（未静态解析）→ 方案 1 已把 6 个 resId 全部纳入替换表规避该不确定性。
- `j2.f`/`j2.e` 语义（发送中消息 id / 动画态消息 id）为基于 ProgressBar/AnimImageView 行为的合理推断。
- 图片/语音/卡片等非文本消息的气泡 drawable resId 未逐一采集（其布局走二进制 XML + DataBinding）→ 用 §6 第 6 条真机自校准采集后纳入方案 1 映射表。
- 所有 resId/混淆类名仅对**本 base.apk 构建**有效，换版本必须按 §6 重新锚定。

---

*报告生成：LSPilot AI 分析助手 · v2 修正版（DexKit + jadx + baksmali 交叉验证）*
