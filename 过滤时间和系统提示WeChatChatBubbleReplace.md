# 微信聊天对话气泡替换 —— 最终版报告（v7）

> 分析对象：com.tencent.mm（base.apx）+ 用户运行日志（LeShaoV3 模块，versionCode=3180，Tinker 环境）实锤交叉验证
> 工具：LSPilot（DexKit + jadx + baksmali 逐层核查）
> 版本历程：v1 误判 X2C -> v2 找到 bind 期 -> v3 找到视图重写 -> v4 运行日志实锤 -> v5 误伤修复 -> v6 文字路径定论 -> v7 时间/系统提示过滤
> 本文是唯一有效版本；v1~v3 结论已被运行日志证伪，仅存档。

---

## 0. 结论速览

1. 两条并行实设路径（Smali 实证）：
   - 文本普通态：XML android:background 在 inflate 时进入 MMNeat7extView.setBackground(Drawable)（唯一来源，见第 10 节）
   - 语音/图片：AnimImageView.setType(int) -> setBackgroundDrawable(Drawable)（drawable 经 ke5.a.i(ctx,resId)）
2. 三个必踩的坑：
   - 坑 1：MMNeat7extView 重写了 setBackground/setBackgroundResource，hook android.view.View 基类对气泡视图 100% 不触发
   - 坑 2：AnimImageView.setType 三分支：i==3 走 setBackgroundDrawable(null)（复用清空）；i==2 走 setBackgroundResource(2131100638/639)（发送态）
   - 坑 3：几何猜气泡（TextView + 9patch/StateListDrawable + 宽>=40%）会误伤主页会话列表、ChatFooter 输入框、表情面板、时间条、系统提示
3. 终版方案 = 三层门控 + BUBBLE 捕获表 + 四重过滤：
   - 门 1：getTag(2131365948) instanceof viewitems.b0 祖先链检查（全 App 独有 tag）
   - 门 2：只重盖微信亲自贴过气泡的 View，收进 WeakHashMap<View,Boolean>
   - 门 3：仅在 ChattingUI 前台时启用
   - 过滤：msg type==1 且 isContentTv 且 id 黑名单外且背景为 9-patch/StateList（见第 11 节）
4. resId/类名全禁硬编码：本 APX 资源名已混淆（getIdentifier("chatfrom_bg")=0，真名 mh/o_），必须按第 8 节自校准

---

## 1. 实证链（运行日志 + Smali 双向核对）

| # | 事实 | 证据 |
|---|---|---|
| 1 | 语音气泡走 mq.b -> AnimImageView.setType -> ke5.a.i(resId) | 运行日志堆栈 + 第 3 节 Smali |
| 2 | 文本气泡承载视图是 MMNeat7extView，tag 为 viewitems.ap（hn5.p.invoke 所打） | 运行日志 menuBuild 行 + hn5.p.invoke |
| 3 | MMNeat7extView.setBackgroundResource 对普通文本 0 触发，v3 方案空转 | 运行日志 + 第 10 节 to.b 条件 |
| 4 | resId=2131232060 真名 o_；getIdentifier from=0 to=0（资源名混淆） | 运行日志 |
| 5 | 微信同时用 setBackgroundResource(int) 与 setBackgroundDrawable(Drawable) 两条路 | AnimImageView.setType Smali 全文 |

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
// viewitems.to.b —— 仅关怀模式（le5.e.b() && le5.e.d()）才会走到，见第 10 节
public static void b(e9 msg, to holder, d ctx, Boolean isRecv) {
    if (msg.getMsgId() == ((j2) ctx.c.a(j2.class)).f) {      // 发送中
        holder.g.setVisibility(0);
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
| 文本普通态（唯一来源） | XML android:background | 同 | inflate -> MMNeat7extView.setBackground(Drawable) | setBackground(Drawable) |
| 文本链接子类型 | 2131231944 | 2131232070 | hn5.r0.g0 / hn5.s0.k0 | setBackgroundResource(int) |
| 文本关怀模式 | 2131231925 | 2131232060 | hn5.q.invoke -> viewitems.to.b | setBackgroundResource(int) |
| 语音/图片气泡 | 2131231925 | 2131232060 | AnimImageView.setType | setBackgroundDrawable(Drawable) |
| 语音/图片发送态 | 2131100638 | 2131100639 | 同上（i==2） | setBackgroundResource(int) |
| AppMsg（仅默认 item d2 走 X2C） | chat_from_mask_bg 2131231853 | chatto_bg_app 2131232062 + chat_to_mask_bg 2131231907(前景) | kw5.g.r -> kw5.i0.f/n | setBackground / setForeground |

资源名在本 APX 已混淆：2131231925 对应 mh，2131232060 对应 o_；getIdentifier("chatfrom_bg") 返回 0。

---

## 4. 终版替换方案（三层门控 + BUBBLE 捕获表 + 四重过滤）

### 4.1 门 1：ChattingItem tag 祖先门

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

所有相关 hook 的入口第一行：if (!inChatItem((View) param.thisObject)) return;

### 4.2 门 2：BUBBLE 捕获表

```java
final WeakHashMap<View, Boolean> BUBBLE = new WeakHashMap<>();   // view -> isRecv

// 捕获源 1：文本普通态 inflate 入口（核心）
findAndHookMethod("com.tencent.mm.ui.widget.MMNeat7extView", cl, "setBackground", Drawable.class,
  new XC_MethodHook(){
    protected void beforeHookedMethod(MethodHookParam p){
        View v = (View) p.thisObject;
        if (!inChatItem(v)) return;
        if (!shouldReplace(v)) return;                        // 第 11 节四重过滤
        boolean recv = isLeft(v);
        BUBBLE.put(v, recv);
        p.args[0] = fresh(recv ? myRecv : mySend);            // 9-patch 新实例
    }
});

// 捕获源 2：链接子类型 / 关怀模式 / 语音发送态 的 resId 白名单
findAndHookMethod(View.class, "setBackgroundResource", int.class, new XC_MethodHook(){
    protected void beforeHookedMethod(MethodHookParam p){
        View v = (View) p.thisObject;
        if (!inChatItem(v) || !shouldReplace(v)) return;
        BUBBLE.put(v, isLeft(v));
        Integer rep = mapRes((int) p.args[0]);   // 白名单：1944/2070/1841/1895/2131100638/639
        if (rep != null) p.args[0] = rep;
    }
});

// 捕获源 3：语音/图片 anim 气泡 view；字段 e = isRecv
findAndHookMethod("com.tencent.mm.ui.base.AnimImageView", cl, "setType", int.class, new XC_MethodHook(){
    protected void afterHookedMethod(MethodHookParam p){
        View v = (View) p.thisObject;
        if (!inChatItem(v)) return;
        BUBBLE.put(v, (Boolean) XposedHelpers.getObjectField(v, "e"));
    }
});
```

### 4.3 setBackgroundDrawable 直改（语音/图片）

```java
findAndHookMethod(View.class, "setBackgroundDrawable", Drawable.class, new XC_MethodHook(){
    protected void beforeHookedMethod(MethodHookParam p){
        if (p.args[0] == null) return;               // 别拦 setType i==3 的故意清空
        if (!inChatItem((View) p.thisObject)) return;
        Integer rep = mapRes(resIdOf((Drawable) p.args[0]));
        if (rep != null) p.args[0] = rep;
    }
});
```

### 4.4 门 3 + 补盖兜底

```java
// 仅 ChattingUI 前台时全局启用（你模块已有该判定）

// 复用/清空后补盖：只对 BUBBLE 命中的 view 生效
findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook(){
    protected void afterHookedMethod(MethodHookParam p){
        View v = (View) p.thisObject;
        Boolean recv = BUBBLE.get(v);
        if (recv == null || !shouldReplace(v)) return;
        v.setBackground(fresh(recv ? myRecv : mySend));
        v.setPadding(pl, pt, pr, pb);
        v.requestLayout(); v.invalidate();
    }
});
```

必删：宽度>=40% 且 StateListDrawable 的几何猜逻辑。

---

## 5. 滑动偶发不渲染：5 因 5 修

| # | 根因（实证） | 修法 |
|---|---|---|
| 1 | AnimImageView.setType 的 i==3 分支走 setBackgroundDrawable(null)，复用清空 | before 跳过 null；after 补盖 |
| 2 | AnimImageView.setType 的 i==2 分支走 setBackgroundResource(2131100638/639)，高亮气泡盖回 | 白名单补这两个 resId |
| 3 | 文本普通态只在 inflate 时进 setBackground；holder 复用不重新 inflate | BUBBLE 表 + onAttachedToWindow 补盖 |
| 4 | 共享 Drawable 实例导致 bounds 互踩，偶发不画 | 每次 getConstantState().newDrawable().mutate() |
| 5 | X2C 视图缓存池 kw5.e1（key = ctx.hashCode _ resId _ hasParent；kw5.e1.b() 清池重建） | bind/attach 补盖法天然覆盖，不用一次性遍历 |

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
```

语音/图片图标：

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

1. 停用一切硬编码，只保留方法名与 tag 语义：MMNeat7extView.setBackground、AnimImageView.setType、viewitems.to.b、hn5.r0.g0/s0.k0；tag key 2131365948 与字符串 ChattingItemContainer 锚定。
2. 跑一次自校准 dump（翻阅聊天约一分钟）：

```java
// A. 资源侧
hook Resources.getDrawable(int) 与 getDrawable(int,Theme)
    -> 记录 resId、getResourceEntryName(resId)、调用方
// B. 视图侧
hook View.setBackgroundResource(int) / setBackground(Drawable) / setBackgroundDrawable(Drawable)
    -> 记录 view.getClass().getName()、view.getId()、resId、getLocationInWindow()
```

3. 产出 (resId, entryName, 视图类, view id, x 坐标, w, h) 表：x 偏右为自己发出，x 偏左为对方收到，填入白名单与 BUBBLE 初始表。
4. getIdentifier("chatfrom_bg") 在本 APX 返回 0，只能按 resId 加运行时捕获；文本普通态的 XML 背景 resId 无法从 DEX 反查，必须以第 10 节 hook 点 + 运行时捕获为准。

---

## 9. 遗留不确定性（如实声明）

- 链接子类型判定条件 y3.O4(msg) && !ctx.F() 与关怀模式开关 t3.xa / t3.Aa 的具体语义以微信配置为准。
- 本机运行时的微信与 LSPilot 静态分析的 base.apx 非同一安装或补丁态（install token 不同且存在 Tinker 热修），类结构存在差异，一切以运行时自校准为准。
- 语音与图片的发送态高亮气泡（2131100638/639）方向判定依赖 AnimImageView.setType 的 this.e 字段。
- 图片、文件、卡片等非文本消息的气泡 resId 未逐一采集，按第 8 节采集后并入白名单。

---

## 10. v6 增补：文字消息气泡的唯一真实路径（Smali 实锤）

### 10.1 决定性发现：to.b 是关怀模式专属

```java
// hn5.v.d 第 78-82 行（文本填充注册异步 UI 块处）
if (le5.e.b() && le5.e.d()) {          // le5.e = MicroMsg.MMCareModeManager（关怀模式）
    a1Var.d(new hn5.q(e9Var, dVar));    // 只有这里才调用 viewitems.to.b
}
```
le5.e.b() 读配置 t3.xa，le5.e.d() 读配置 t3.Aa，默认均 false。
结论：普通（非关怀模式）用户永远不走 to.b，setBackgroundResource(2131231925/2060) 对普通文本消息根本不执行。

### 10.2 普通态文本气泡的唯一来源：XML 布局的 android:background

```
文本 item = hn5.v(收)/hn5.n0(发)；item.H() -> new viewitems.jh(inflater, 0x7f0e0382)
  -> LayoutInflater.inflate(0x7f0e0382) 二进制 XML
     -> MMNeat7extView（holder 字段 b）由 View 构造器读 android:background
        -> MMNeat7extView.setBackground(Drawable) override
           -> super.setBackground(d) + 把 padding 复制给内层 wrappedTextView
```

该 drawable 来自 XML 资源引用，DEX 字符串中不可见（find_usage 搜不到、getIdentifier 也不可靠），且从 Drawable 对象无法反推 resId —— 这正是此前文本 hook 静默跳过的原因。

### 10.3 文本气泡三条完整路径与 hook 点

| 场景 | 路径 | hook 方式 |
|---|---|---|
| 普通态（绝大多数消息） | XML android:background -> MMNeat7extView.setBackground(Drawable) | hook MMNeat7extView.setBackground(Drawable)，before/after 直接替换 drawable 对象，不要反查 resId；新实例 + mutate |
| 链接子类型（电话/网址/邮箱命中 y3.O4） | hn5.r0.g0 / hn5.s0.k0 -> setBackgroundResource(2131231944/2070) | before 改 args[0]，或 after 直接 setBackground |
| 关怀模式开启 | hn5.q.invoke -> viewitems.to.b -> setBackgroundResource(2131231925/2060) | 已有 hook 保留 |

绑定佐证：hn5.p.invoke 中 contentITV.setTag(new ap(msgData, ...))，运行日志 anchor=MMNeat7extView tag=viewitems.ap 反证 holder 字段 b 即该 MMNeat7extView。

### 10.4 语音/图片与文本的 resId 共用说明

AnimImageView.setType 用 ke5.a.i(ctx, 2131231925/2060) 取同一对 drawable；文本普通态由 XML 引用同一对 drawable（不同构建 resId 可能不同，以自校准表为准）。因此同一 resId 会同时出现在两类消息中，hook 时以 inChatItem + 视图类区分，不会误伤。

---

## 11. v7 增补：时间文字 / 系统提示的过滤（防过度渲染）

### 11.1 泄漏源定位（三个，静态实证）

| 泄漏源 | 视图 | 特征（实证） |
|---|---|---|
| item 内时间分隔条 | X2CTextView，id 2131366078（0x7F0A10BE） | gm.g.c / gm.i.c 第 70/110 行 setId(2131366078)；通常无背景 |
| jh 容器内非内容视图 | 展开 TextView id 0x7f0a1075；历史消息提示(0x7f0e0349) id 0x7f0a0fc3；多选 CheckBox id 0x7f0a0f6c；内容根 0x7f0a0f74 | viewitems.jh 构造函数 Smali 实证 |
| 系统提示消息（撤回/安全提示/以下为新消息等） | type 10000 的独立 item，居中小灰字 | 运行日志 type=10000->1000 content=由于账号安全原因... |

对照：真正的气泡内容视图 = holder.b，MMNeat7extView，id 2131365751（0x7F0A0F77，gm.g.c/gm.i.c 实证 setId），且 hn5.p.invoke 给它打 viewitems.ap tag。

### 11.2 四重过滤（全部通过才替换）

```java
// 1) 消息类型过滤：只放行文本类（type == 1，含其子类型）；10000=系统提示一律不碰
//    WeakHashMap<View,Boolean> ALLOW：bind 后 if (((e9)msg).getType() == 1) ALLOW.put(contentView, isRecv);

// 2) 视图身份过滤：必须是 MMNeat7extView 或带 ap tag（内容 ITV 双重身份）
static boolean isContentTv(View v) {
    if (!(v instanceof com.tencent.mm.ui.widget.MMNeat7extView)) {
        Object tag = v.getTag();
        if (tag == null || !tag.getClass().getName().endsWith("viewitems.ap")) return false;
    }
    return true;
}

// 3) id 黑名单（时间条 / jh 内非内容 / CheckBox / 内容根）
static final Set<Integer> ID_BLACK = new HashSet<>(Arrays.asList(
    2131366078,   // chatting_time_tv 时间分隔条（本构建实证，运行时需自校准）
    0x7f0a1075,   // jh 内“展开”TextView
    0x7f0a0fc3,   // jh 内历史消息提示
    0x7f0a0f6c,   // jh 内多选 CheckBox
    0x7f0a0f74    // jh inflate 的内容根
));

// 4) 背景类型兜底：气泡 = NinePatch/StateListDrawable；时间/提示 = 无背景或 GradientDrawable
static boolean bubbleBg(View v) {
    Drawable bg = v.getBackground();
    return bg instanceof NinePatchDrawable || bg instanceof StateListDrawable;
}

static boolean shouldReplace(View v) {
    return inChatItem(v) && isContentTv(v) && !ID_BLACK.contains(v.getId())
        && bubbleBg(v) && ALLOW.containsKey(v);          // ALLOW 即第 4.2 节 BUBBLE 捕获表
}
```

### 11.3 对接现有 BUBBLE 捕获表

把第 4.2 节三处 BUBBLE.put(...) 全部加上 shouldReplace 前置；onAttachedToWindow 补盖同样先过 shouldReplace。这样时间条、系统提示 item、jh 内展开/历史提示/CheckBox 一律不再被渲染。

### 11.4 自校准（换构建）

dump 一次聊天页所有 TextView：log(view.getClass().getName(), view.getId(), bg.getClass().getSimpleName(), getLocationInWindow())；时间条 = 位于气泡上方、无背景/GradientDrawable、id 固定者；内容 ITV = MMNeat7extView 或带 ap tag、背景 9-patch 者；将新 id 写入 ID_BLACK，内容 id 写入 ALLOW 采集条件；msg type 用你 MessageHook 已有的 e9.getType() 判定，无需硬编码 item 类。

---
*报告生成：LSPilot AI 分析助手 · v7 终版（DexKit + jadx + baksmali + 运行日志实锤交叉验证）*
