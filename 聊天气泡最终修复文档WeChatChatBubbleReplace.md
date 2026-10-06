# 微信聊天对话气泡替换 —— 最终版报告（v5）

> 分析对象：`com.tencent.mm`（base.apx）+ 用户运行日志（LeShaoV3 模块，versionCode=3180，Tinker 环境）实锤交叉验证
> 工具：LSPilot（DexKit + jadx + baksmali 逐层核查）
> 版本历程：v1 误判 X2C → v2 找到 bind 期 → v3 找到视图重写 → v4 运行日志实锤 → **v5 误伤修复终版**
> 本文是唯一有效版本；v1~v3 结论已被运行日志证伪，仅存档。

---

## 0. 结论速览（TL;DR）

1. 微信聊天气泡有两条并行的实设路径（Smali 实证）：
   - 文本：`TextView.setBackgroundResource(int)`（`viewitems.to.b` / `hn5.r0.g0` / `s0.k0`）
   - 语音/图片：`AnimImageView.setType(int)` → `setBackgroundDrawable(Drawable)`（drawable 经 `ke5.a.i(ctx,resId)`）
2. 三个必踩的坑（本报告核心）：
   - **坑 1**：`MMNeat7extView` 重写了 `setBackground/setBackgroundResource`，hook `android.view.View` 基类对气泡视图 100% 不触发（v3 根因）。
   - **坑 2**：`AnimImageView.setType` 三分支：`i==3` 走 `setBackgroundDrawable(null)`（复用清空，冲掉你的替换）；`i==2` 走 `setBackgroundResource(2131100638/639)`（发送态）—— 两条都会盖回原图。
   - **坑 3**：用几何猜气泡（TextView + 9patch/StateListDrawable + 宽≥40%）会误伤主页会话列表 + ChatFooter 输入框 + 表情面板（用户实测）。
3. 终版方案 = 三层门控 + BUBBLE 捕获表：
   - 门 1（止血）：`getTag(2131365948) instanceof viewitems.b0` 祖先链检查 —— 全 App 独有 tag，主页/输入框绝无；
   - 门 2（根治）：只重盖微信亲自贴过气泡的 View —— `to.b` 的 `holder.b`、`AnimImageView.setType` 的 this，收进 `WeakHashMap<View,Boolean>`；
   - 门 3（纵深）：仅在 ChattingUI 前台时启用。
4. resId/类名全禁硬编码：本 APX 资源名已混淆（`getIdentifier("chatfrom_bg")=0`，真名 `mh`/`o_`），必须按第 8 节自校准。

---

## 1. 实证链（运行日志 + Smali 双向核对）

| # | 事实 | 证据 |
|---|---|---|
| 1 | 语音气泡走 `mq.b` → `AnimImageView.setType` → `ke5.a.i(resId)` | 运行日志堆栈 + 第 3 节 Smali |
| 2 | 文本气泡在用户构建里不是 MMNeat7extView（`to.b` 打到的 view = `android.widget.TextView neat=false`） | 运行日志 |
| 3 | `MMNeat7extView.setBackgroundResource` 全程 0 触发，v3 方案在该运行环境空转 | 运行日志 |
| 4 | resId=2131232060 真名 `o_`；`getIdentifier from=0 to=0`（资源名混淆） | 运行日志 |
| 5 | 微信同时用 `setBackgroundResource(int)` 与 `setBackgroundDrawable(Drawable)` 两条路贴气泡 | `AnimImageView.setType` Smali 全文 |

---

## 2. 聊天 UI 封装链路（Activity 到 气泡视图）

```
com.tencent.mm.ui.chatting.ChattingUI（MMSecDataFragmentActivity -> BaseMvvmFragmentActivity -> VASLauncher）
└─ ChattingUIFragment                    （字段 B = MMMChattingListView）
   └─ com.tencent.mm.ui.chatting.view.MMMChattingListView（extends MMPullDownView -> FrameLayout）
      └─ 适配器 ChattingDataAdapter -> com.tencent.mm.view.recyclerview.WxRecyclerAdapter（MvvmList）
         └─ ItemConvert：xl5.g（每 viewType 一个）
            ├─ c(rv)    创建 item 视图
            ├─ d(rv,v)  组装 ViewHolder
            └─ h(...)   bind 填充
               └─ Item 工厂 viewitems.lt.a(ctx) -> viewitems.kt（日志 MicroMsg.ItemFactoryNew）
                  ├─ kt.c(msg)  求 viewType（type+subtype+!isSend+子类index）
                  └─ kt.b(key)  Class.newInstance()（默认 viewitems.d2）
                     └─ item.H(inflater,null)               <- viewitems.b0 抽象方法
                        └─ new viewitems.jh(inflater, 内容布局resId)   <- jh = ChattingItemContainer
```

关键机制（Smali 实测）：

```java
// xl5.g.c —— item 视图创建
View H = item.H(inflater, null);
H.setTag(2131365948, item);                  // <- 全 App 独有 ChattingItem tag（门 1 锚点）

// xl5.g.d —— ViewHolder 组装
ChattingItem item = (ChattingItem) v.getTag(2131365948);
BaseViewHolder holder = (BaseViewHolder) v.getTag();   // <- 普通 tag 即 holder

// viewitems.zn.H —— 文本 item 骨架
new viewitems.jh(inflater, 0x7f0e0382);      // 内容布局（二进制 XML，非 X2C）
new viewitems.to().a(view, true);
view.setTag(holder);                        // 普通 tag 即 holder
```

---

## 3. 气泡资源全景表与全部实设点

```java
// viewitems.to.b —— 文本普通态气泡（静态，第 4 参 isRecv 直供方向）
public static void b(e9 msg, to holder, d ctx, Boolean isRecv) {
    if (msg.getMsgId() == ((j2) ctx.c.a(j2.class)).f) {      // 发送中
        holder.g.setVisibility(0);                            // ProgressBar
        holder.b.setBackgroundResource(isRecv ? 2131231841 : 2131231895);
    } else {
        holder.g.setVisibility(8);
        holder.b.setBackgroundResource(isRecv ? 2131231925 : 2131232060);
    }
}

// hn5.r0.g0（收到，链接文本子类型） / hn5.s0.k0（发出，同）
public void g0(MMNeat7extView ctv) {
    ctv.setMaxWidth((int)(gk.p(0.88f) / f.g));               // 最大宽 88% 屏宽
    ctv.setBackgroundResource(2131231944);
    i(ctv, true, true);                                      // b0.i: 左右 margin 6dp
}
```

```java
// com.tencent.mm.ui.base.AnimImageView.setType —— 语音/图片气泡（三分支）
public void setType(int i) {
    this.f = i;
    if (this.e) {                                            // e = isRecv（对方）
        if (i == 2) setBackgroundResource(2131100638);       // 发送态高亮气泡
        else if (i == 3) setBackgroundDrawable(null);        // <- 复用/空态清空
        else setBackgroundDrawable(ke5.a.i(getContext(), 2131231925));
    } else if (i == 2) setBackgroundResource(2131100639);
    else if (i == 3) setBackgroundDrawable(null);            // <- 同上
    else setBackgroundDrawable(ke5.a.i(getContext(), 2131232060));
}
```

| 场景 | 对方(收到) | 自己(发出) | 实设点（类.方法） | 设置 API |
|---|---|---|---|---|
| 文本普通态 | 2131231925 | 2131232060 | viewitems.to.b | setBackgroundResource(int) |
| 文本发送中 | 2131231841 | 2131231895 | 同上 | 同上 |
| 文本链接子类型 | 2131231944 | 2131232070 | hn5.r0.g0 / hn5.s0.k0 | 同上 |
| 语音/图片气泡 | 2131231925 | 2131232060 | AnimImageView.setType | setBackgroundDrawable(Drawable) |
| 语音/图片发送态 | 2131100638 | 2131100639 | 同上（i==2） | setBackgroundResource(int) |
| AppMsg（仅默认 item d2 走 X2C） | chat_from_mask_bg 2131231853 | chatto_bg_app 2131232062 + chat_to_mask_bg 2131231907(前景) | kw5.g.r -> kw5.i0.f/n | setBackground / setForeground |

资源名在本 APX 已混淆：2131231925 对应 mh，2131232060 对应 o_；getIdentifier("chatfrom_bg") 返回 0。

---

## 4. 终版替换方案（三层门控 + BUBBLE 捕获表）

### 4.1 第一层：ChattingItem tag 祖先门（止血）

```java
final int TAG_ITEM = 2131365948;                 // 换构建需按第 8 节重定位
Class<?> CLS_ITEM = Class.forName("com.tencent.mm.ui.chatting.viewitems.b0", cl);

static boolean inChatItem(View v) {
    for (View p = v; p != null; ) {
        Object t = p.getTag(TAG_ITEM);
        if (t != null && CLS_ITEM.isInstance(t)) return true;
        Object par = p.getParent();
        p = (par instanceof View) ? (View) par : null;
    }
    return false;
}
```

所有相关 hook 的入口第一行：`if (!inChatItem((View) param.thisObject)) return;`
（主页会话列表、ChatFooter 输入框、表情面板均无此 tag，直接短路）

### 4.2 第二层：BUBBLE 捕获表（根治，删掉几何猜）

```java
final WeakHashMap<View, Boolean> BUBBLE = new WeakHashMap<>();   // view -> isRecv

// 文本：to.b 第 4 参即方向；holder.b 即气泡 view
findAndHookMethod(holderCls, "b", e9Cls, holderCls, ctxCls, Boolean.class, new XC_MethodHook(){
    protected void afterHookedMethod(MethodHookParam p){
        View bubble = (View) XposedHelpers.getObjectField(p.args[1], "b");
        BUBBLE.put(bubble, (Boolean) p.args[3]);
    }
});

// 语音/图片：setType 捕获 view；字段 e = isRecv
findAndHookMethod("com.tencent.mm.ui.base.AnimImageView", cl, "setType", int.class, new XC_MethodHook(){
    protected void afterHookedMethod(MethodHookParam p){
        View v = (View) p.thisObject;
        BUBBLE.put(v, (Boolean) XposedHelpers.getObjectField(v, "e"));
    }
});
```

重盖只认这张表：

```java
findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook(){
    protected void afterHookedMethod(MethodHookParam p){
        View v = (View) p.thisObject;
        Boolean recv = BUBBLE.get(v);
        if (recv == null) return;                   // <- 只动真气泡
        apply(v, recv);
    }
});

void apply(View v, boolean recv) {
    if (!inChatItem(v)) return;                      // 双门
    Drawable base = origFromResource(recv);          // 原始 9-patch（首帧从微信路径抓）
    Drawable d = base.getConstantState().newDrawable();   // 每 view 新实例，禁共享
    d.mutate();
    v.setBackground(d);
    v.setPadding(pl, pt, pr, pb);                    // NeatTextView 重写会把 padding 同步内层
    v.requestLayout();
    v.invalidate();                                  // 强制重排，吃掉 Layout 缓存
}
```

### 4.3 setter 直改（保留，即时生效）

```java
// 文本/语音背景；resId 白名单：2131231925/2060/1944/2070/1841/1895/2131100638/639
findAndHookMethod(View.class, "setBackgroundResource", int.class, new XC_MethodHook(){
    protected void beforeHookedMethod(MethodHookParam p){
        if (!inChatItem((View) p.thisObject)) return;
        Integer rep = mapRes((int) p.args[0]);
        if (rep != null) p.args[0] = rep;
    }
});

findAndHookMethod(View.class, "setBackgroundDrawable", Drawable.class, new XC_MethodHook(){
    protected void beforeHookedMethod(MethodHookParam p){
        if (p.args[0] == null) return;               // 别拦 setType i==3 的故意清空
        if (!inChatItem((View) p.thisObject)) return;
        Integer rep = mapRes(resIdOf((Drawable) p.args[0]));
        if (rep != null) p.args[0] = rep;
    }
});
```

必删：宽度>=40% 且 StateListDrawable 的几何猜逻辑（主页/输入框误伤元凶）。

### 4.4 第三层：页面门

仅当 ChattingUI / ChattingUIFragment 前台时启用上述 hook。

---

## 5. 滑动偶发不渲染：5 因 5 修

| # | 根因（实证） | 修法 |
|---|---|---|
| 1 | AnimImageView.setType 的 i==3 分支走 setBackgroundDrawable(null)，复用清空 | before 跳过 null；after 补盖 |
| 2 | AnimImageView.setType 的 i==2 分支走 setBackgroundResource(2131100638/639)，高亮气泡盖回 | 白名单补这两个 resId |
| 3 | 文本只在 to.b / g0 / k0 时机设置；动画 item、仅内容刷新、pat/quote 复用不触发 | 用第 4.2 节 BUBBLE 表在 onAttachedToWindow 补盖 |
| 4 | 共享 Drawable 实例导致 bounds 互踩，偶发不画 | 每次 getConstantState().newDrawable().mutate() |
| 5 | X2C 视图缓存池 kw5.e1（key = ctx.hashCode _ resId _ hasParent；kw5.e1.b() 清池重建） | 用 bind/attach 补盖法天然覆盖，不用一次性遍历 |

---

## 6. 文字与气泡适配拉伸（微信量化参数与做法）

| 参数 | 值 | 出处 |
|---|---|---|
| 气泡最大宽 | setMaxWidth((int)(gk.p(0.88f) / f.g))，即 88% 屏宽 | hn5.r0.g0 / hn5.s0.k0 |
| 外左右距 | 6dp（左/右随方向） | b0.i(view,isRecv,true) 调 ke5.a.b(ctx,6) |
| 内 padding | 约左右 7dp、下 1.5dp（以你真机 dump 为准） | gm.g.c；MMNeat7extView 的 setBackground 系列把 View padding 复制给内层 wrappedTextView |

做法：

1. 替换 drawable 必须为 9-patch：stretch 只开中间竖条（避开圆角与尾巴），content padding 左右不小于 7dp、上下 4~6dp；或把 9patch padding 置 0，全部用 setPadding 显式给，防止两层 padding 叠加切字。
2. 换背景后必做 setPadding + requestLayout + invalidate：MMNeat7extView 的 setBackground 重写会把 padding 同步给内层 wrappedTextView；不重排则 NeatTextView 的 Layout 缓存不重算，文字不跟随气泡伸缩。
3. 高度随文字走：9-patch 竖直 stretch 一并打开；不要给 view 设固定宽高，maxWidth 保持微信的 88%。
4. 禁止多 item 共享同一 Drawable 实例，mutate() 后各自 setBounds。

---

## 7. 文字颜色与语音图标颜色

文字颜色：NeatTextView.setTextColor 已实证存在并被 MMNeat7extView 继承（内部同步到 wrappedTextView）。

```java
tv.setTextColor(0xFF1A1A1A);                 // 或 setTextColor(ColorStateList) 保 selector
// 全局白名单版：findAndHookMethod(NeatTextView.class, "setTextColor", int.class 或 ColorStateList.class, ...)
```

语音/图片图标（气泡已随第 4 节改掉，剩下图标本体）：

```java
findAndHookMethod(ImageView.class, "setImageDrawable", Drawable.class, new XC_MethodHook(){
    protected void beforeHookedMethod(MethodHookParam p){
        if (!inChatItem((View) p.thisObject)) return;
        Drawable d = (Drawable) p.args[0];
        if (d != null && isVoiceIcon(d)) d.setTint(iconColor);          // API 21 以上
        // 兜底：d.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
    }
});
```

连时长数字、发送中动画、转文字 TextView 一起改：在 bind 之后对 mq holder 字段（mq.D / mq.c / mq.d 等）逐个 setTextColor。图标 resId 与气泡 resId 不同，从第 8 节自校准表取。

---

## 8. 跨版本与跨构建自校准（本机必做）

1. 停用一切硬编码，只保留方法名与 tag 语义：
   - viewitems.to.b / hn5.r0.g0 / hn5.s0.k0 / AnimImageView.setType，按第 2 节类链与日志 tag MicroMsg.ChattingItemTextFrom 重定位；
   - tag key 2131365948 与字符串 ChattingItemContainer 锚定。
2. 跑一次自校准 dump（翻阅聊天约一分钟）：

```java
// A. 资源侧
hook Resources.getDrawable(int) 与 getDrawable(int,Theme)
    -> 记录 resId、getResourceEntryName(resId)、调用方
// B. 视图侧
hook View.setBackgroundResource(int) / setBackground(Drawable) / setBackgroundDrawable(Drawable)
    -> 记录 view.getClass().getName()、resId、getLocationInWindow()
```

3. 产出 (resId, entryName, 视图类, x 坐标, w, h) 表：x 偏右为自己发出，x 偏左为对方收到，填入白名单与 BUBBLE 初始表。
4. getIdentifier("chatfrom_bg") 在本 APX 返回 0，只能按 resId 加运行时捕获。

---

## 9. 遗留不确定性（如实声明）

- to.b（普通态）与 g0/k0（链接态）谁最终生效取决于运行时开关 e.b() 与 e.d()（未静态解析），第 4.2 节 BUBBLE 捕获法已规避该不确定性。
- 本机运行时的微信与 LSPilot 静态分析的 base.apx 非同一安装或补丁态（install token 不同且存在 Tinker 热修），故运行日志中的类结构（to.b 命中普通 TextView）与静态分析（MMNeat7extView）存在差异，一切以运行时自校准为准。
- 语音与图片的发送态高亮气泡（2131100638/639）方向判定依赖 AnimImageView.setType 的 this.e 字段。
- 图片、文件、卡片等非文本消息的气泡 resId 未逐一采集，按第 8 节采集后并入白名单。

---

*报告生成：LSPilot AI 分析助手 · v5 终版（DexKit + jadx + baksmali + 运行日志实锤交叉验证）*
