# 微信「长按消息 → 转发」替换为自定义联系人选择器 — 逆向分析与 Xposed Hook 方案 (v2 已核验)

> 目标包名：`com.tencent.mm`（微信）
> 目标：聊天窗口长按消息菜单中点击「转发」后，**不再**走微信原生转发界面，**直接跳转**到你模块的联系人选择器。
> 基线：本机 `base.apk`。类名均为**混淆后真实名**，括号内为反混淆语义。本文已做二次核验并修正/补全（见 §8）。

---

## 0. 结论（TL;DR）

覆盖「聊天窗口**单条**消息长按 → 转发」的最精准、最通用 Hook 点是：

```
com.tencent.mm.ui.chatting.manager.t.a(com.tencent.mm.storage.e9 msg, android.content.Context ctx, java.lang.Runnable cb)
```
- `public static final void`（微信内部日志名 `ShareDialogHelper`），全聊天区 **28 个调用点**横跨**各类消息**（文本收/发、图片、视频、语音、文件、链接、位置、名片、表情、小程序、AI 链接 yuanbao…）。
- 它做「安全检查 → 执行回调 cb」，cb 内才 `startActivity(Intent(MsgRetransmitUI))`。

**做法**：`XC_MethodReplacement` 替换 `manager.t.a`，**不调原方法**，取 `args[0]`(消息 `e9`)、`args[1]`(`Context`)，拉起你的选择器；原生 `MsgRetransmitUI`/`SelectConversationUI` 及安全弹窗都不会出现。

---

## 1. 完整调用链（v2 核验后修正版）

```
长按聊天消息（每种消息类型一个 View 类，各自的分发方法 fo/onMMMenuItemSelected、zn/go.R、*.Q 等）
   · 文本(我发出)  ChattingItemTextFromBase = viewitems.fo   → onMMMenuItemSelected  itemId==2(转发)
   · 文本(收到)    ChattingItemTextToBase   = viewitems.go   → R                     (转发)
   · 文本基类委托  ChattingItemTextBase     = viewitems.zn   → R                    itemId==108(转发)
   · 图片/视频/语音/文件/链接/位置/名片/表情/小程序/AI链接(yuanbao)… 各自 viewitems.*/.Q/.onMMMenuItemSelected
        └── 以上“转发”分支全部汇合到同一静态入口 ───►
   com.tencent.mm.ui.chatting.manager.t.a(e9 msg, Context ctx, Runnable cb)   ★ Hook 点 ★
        │ "ShareDialogHelper"：ma.r(msg) 安全校验
        │  · 无风险 => cb.run()
        │  · 有风险(离站/敏感转发) => 先弹 u1 确认框 → 用户确认后 cb.run()
        └─ cb 实现体（fo$$a / zn$$a / 各类型对应 $$x）
               Intent(ctx, com.tencent.mm.ui.transmit.MsgRetransmitUI.class)
               putExtra: Retr_Msg_Id / Retr_MsgTalker / Retr_Msg_content / Retr_Msg_Type / scene_from=17 …
               ctx.startActivity(intent)   （有时经 ChattingContext.b0(intent) 包装）
                  └─ MsgRetransmitUI.onCreate 读 Retr_* → 它就是你在微信点“转发”后看到的界面
                        内部 startActivityForResult(Intent(SelectConversationUI)) ← 原生“选择聊天/联系人”完整列表
                        onActivityResult 读回 Select_Conv_User / ToUsername / SendMsgUsernames → 真正发送
```

> 关键澄清：你点「转发」后**第一屏**看到的是 `MsgRetransmitUI`（消息预览 + 最近会话 + “发送”），它在需要完整联系人列表时再拉起 `SelectConversationUI`。Hook `manager.t.a` 是在这一屏**之前**，替代效果最彻底。

---

## 2. 分级证据（均已反编译核验）

**(a) 文本(发出) fo 的转发分支**
```java
// com.tencent.mm.ui.chatting.viewitems.fo.onMMMenuItemSelected(MenuItem, int)
int itemId2 = menuItem.getItemId();
...
if (itemId2 == 2) {                       // 转发（ChattingItemTextFromBase）
    ps psVar4 = (ps) toVar.b.getTag();
    if (psVar4 != null) {
        uo.a(psVar4.c(), 4, 0);           // 转发上报标记=4
        t.a(psVar4.c(), context, new fo$$a(this, context, psVar4, e9Var)); // → com...manager.t.a
    }
}
```

**(b) 通用委托 zn.R 同样是转发 → 同入口**
```java
// com.tencent.mm.ui.chatting.viewitems.zn.R(MenuItem, ChattingContext d, ItemDataTag ps)
...
} else if (itemId == 108) {               // 转发（ChattingItemTextBase 委托）
    uo.a(c, 4, 0);
    t.a(c, dVar.g(), new zn$$a(dVar, c)); // → 同 com...manager.t.a
}
```

**(c) manager.t.a 真实签名**
```java
// com.tencent.mm.ui.chatting.manager.t （该类仅此一个方法）
public static final void a(e9 msg, Context context, Runnable runnable) {
    o.h(msg, "msg"); o.h(context, "context");
    la r = ma.r(msg);
    Log.i("ShareDialogHelper", "checkSecAndExecute secData:%s", r);
    if (!(!TextUtils.isEmpty(r.a) || r.c >= 1)) {
        if (runnable != null) runnable.run();    // 无风险 → 直接转发
        return;
    }
    // 有风险 → 弹 u1 对话框 → 确认回调 q/r/s → 执行 runnable
    ...
}
```

**(d) 回调 cb 拉起原生转发界面（zn$$a，fo$$a 同理）**
```java
// com.tencent.mm.ui.chatting.viewitems.zn$$a.run()
Intent intent = new Intent(dVar.g(), MsgRetransmitUI.class); // com.tencent.mm.ui.transmit.MsgRetransmitUI
intent.putExtra("Retr_Msg_Id",    e9Var.getMsgId());
intent.putExtra("Retr_MsgTalker", e9Var.N0());
intent.putExtra("Retr_Msg_content",((m2)dVar.c.a(m2.class)).y0(e9Var,false));
intent.putExtra("Retr_Msg_Type",  e9Var.V2() ? 6 : 4);
intent.putExtra("scene_from", 17);
dVar.b0(intent);                          // ≡ startActivity(MsgRetransmitUI)，走微信的 a.d() 埋点包装
```

**(e) 特殊类型也走 t.a**：`viewitems.be`(=yuanbao/AI 文本链接) 的 `be.Q` 亦是 `t.a` 调用点，证明不止文本，特殊消息同样统一。

---

## 3. 覆盖度（为何一处 Hook 覆盖几乎所有聊天转发）

- `manager.t.a` 的 **28 个调用点**：
  ```
  viewitems: be.Q d2.Q ef.Q fa.onMMMenuItemSelected fo.onMMMenuItemSelected go.R
             k3.Q lg.R o3.Q p2.Q tb.onMMMenuItemSelected wa.Q zn.R
  kn5:       c0.Q l5.b o.c p4.d t5.e w.c y7.Q z8.Q
  un5:       c.h e0.h
  gallery:   ImageGalleryUI.onClick  gallery.m7.onMMMenuItemSelected
  component: component.hm.t0  component.hq.u0   chatting.de.onClick
  ```
  覆盖文本(收/发)、图片、视频、语音、文件、链接、位置、名片、表情、小程序、AI 链接等**所有消息类型**，以及图库点“转发”、聊天组件转发。
- 反向验证：全 APK 共 **74 个类**构造 `MsgRetransmitUI` 的转发意图（靠 `Retr_MsgTalker` 锚定）。其中“聊天单条长按”来源全部落在上面的 `t.a` 调用点；其余是**非本需求**的转发源（Finder 分享、位置、wenote、webview、openmsg、多选、图库、发送到设备等）——它们不经过 `t.a`。

---

## 4. 推荐 Hook（独立 Xposed 模块，非 BSH）

### 4.1 核心 Hook —— 拦截 `manager.t.a`

```java
findAndHookMethod(
    "com.tencent.mm.ui.chatting.manager.t",
    wxClassLoader,
    "a",
    wxClassLoader.loadClass("com.tencent.mm.storage.e9"),
    android.content.Context.class,
    java.lang.Runnable.class,
    new XC_MethodReplacement() {
        @Override protected Object replaceHookedMethod(MethodHookParam param) {
            final Object  msg = param.args[0];           // com.tencent.mm.storage.e9（被转发消息）
            final Context ctx = (Context) param.args[1]; // = 当前 ChattingUI 的 Activity 上下文

            long   msgId   = (Long)    XposedHelpers.callMethod(msg, "getMsgId");
            String talker  = (String)  XposedHelpers.callMethod(msg, "N0");      // 会话 wxid
            int    msgType = (Integer) XposedHelpers.callMethod(msg, "getType"); // 消息类型

            Intent it = new Intent();
            it.setClassName("你的包名", "你的包名.ContactPickerActivity");
            it.putExtra("wx_msgId", msgId);
            it.putExtra("wx_talker", talker);
            it.putExtra("wx_type", msgType);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);

            return null;   // 不调原方法 ⇒ 原生 MsgRetransmitUI/SelectConversationUI/安全弹窗 均不启动
        }
    }
);
```

要点：
- `XC_MethodReplacement` 且**不调用**原方法 = 彻底中断原生转发，正好满足“点转发 → 直达你的选择器”。
- `ContactPickerActivity` 需 `android:exported="true"`；用显式 `setClassName` 跨进程拉起最稳。
- 一条 Hook 命中**所有消息类型**的单条长按转发、图库转发、组件转发。

### 4.2 只想替换“选择器”、仍让微信发送（进阶，可选）
不要替换 `t.a`，改 Hook `MsgRetransmitUI` 对 `SelectConversationUI` 的 `startActivityForResult`：拦截目标为 `...transmit.SelectConversationUI` 时改跳你的选择器；你的选择器返回时把结果按微信协议塞回（`Select_Conv_User`=目标 wxid、`ToUsername`）交给 `MsgRetransmitUI.onActivityResult`，微信用原生逻辑发送。耦合紧、版本敏感，**非首选**。

---

## 5. 消息对象 `com.tencent.mm.storage.e9` 关键取值（已核验）

| 数据 | 调用 | 说明 |
|---|---|---|
| 消息 ID | `msg.getMsgId()` | long |
| 会话 wxid | `msg.N0()` | String（talker） |
| 消息类型 | `msg.getType()` | int |
| 创建时间 | `msg.getCreateTime()` | long |
| 文本内容 | 经模型层 `((ak5.m2) gg.c.a(ak5.m2.class)).y0(msg,false)` | 直接透传 `msgId+talker+type` 到你的选择器再回查最稳 |

---

## 6. 兜底 Hook（版本差异 / 覆盖多选，强烈建议一并加上）

若个别版本把 `manager.t.a` 改名/合并，或想**连同多选转发一起拦**，直接在**框架层**拦 Activity 启动，按目标组件名分流：

```java
// 同时覆盖 startActivity（MsgRetransmitUI）与 startActivityForResult（SelectConversationUI）
// 注意：微信有时经 ChattingContext.b0()/埋点 a.d() 包装，最终仍落到框架层，故 hook 框架层最全
String[] HOOKS = {"android.app.Activity", "android.content.ContextWrapper", "android.content.ContextImpl"};
String[] NAMES = {"startActivity", "startActivityForResult"};

for (String cls : HOOKS) for (String m : NAMES) {
    XposedBridge.hookAllMethods(wxClassLoader.loadClass(cls), m, new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            for (Object a : param.args) {
                if (!(a instanceof Intent)) continue;
                Intent in = (Intent) a;
                ComponentName c = in.getComponent();
                String cn = (c == null) ? null : c.getClassName();
                if (cn == null) continue;
                if (cn.equals("com.tencent.mm.ui.transmit.MsgRetransmitUI")
                 || cn.equals("com.tencent.mm.ui.transmit.SelectConversationUI")) {
                    // 来源过滤：仅“来自聊天”时替换，避免误伤 收藏/浏览器/小程序 等转发
                    if (!fromChatting(in)) return;
                    Context ctx = (Context) param.thisObject;
                    Intent mine = new Intent();
                    mine.setClassName("你的包名", "你的包名.ContactPickerActivity");
                    mine.putExtra("wx_msgId", in.getLongExtra("Retr_Msg_Id", -1L));
                    mine.putExtra("wx_talker", in.getStringExtra("Retr_MsgTalker"));
                    mine.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(mine);
                    param.setResult(null);       // 阻止原生界面
                    return;
                }
            }
        }
    });
}
// fromChatting(): 判当前前台为 com.tencent.mm.ui.chatting.ChattingUI(BaseChattingUIFragment)，
// 或 intent 带 scene_from / Retr_Msg_Id 等聊天转发标记。可按需收紧。
```

---

## 7. 其它转发流（不在 `manager.t.a`，需知悉）

- **多选 / 合并转发 / 逐条转发**：`com.tencent.mm.ui.chatting.a4`（日志名 `MicroMsg.ChattingEditModeLogic` / `ChattingEditModeRetransmitMsg`），经 `a4.b(Context, List, …)` 直接构造 `MsgRetransmitUI`（带 `Edit_Mode_Sigle_Msg`、`Retr_MsgFromMoreSelectRetransmit`、`forwardMultiMsgInfo` 等），**不走** `t.a`。若也要接管多选转发：额外 hook `a4.b`；好在 §6 的兜底（拦 `MsgRetransmitUI` startActivity）同样能覆盖它。
- 非聊天转发源（Finder 分享、位置、wenote、webview、收藏、发送到设备等）也构造 `MsgRetransmitUI`，但**不经** `t.a`；用 §6 时务必加 `fromChatting()` 来源过滤，避免误伤。

---

## 8. 二次核验记录（修正 / 补全）

1. **修正**：初版把 `zn.R(itemId==108)` 说成“通用基类，覆盖所有类型”。核验日志名确认为 `zn` 属 `ChattingItemTextBase`（文本族），并非全类型基类。**真正“通用”的依据是 `manager.t.a` 的 28 个跨类型调用点**，故以 `t.a` 为锚点，而非以 `zn` 为基类。
2. **补全**：明确文本两种——`fo`=ChattingItemTextFromBase(发出, id=2)、`go`=ChattingItemTextToBase(收到, R)；二者与 `zn.R(id=108)` 殊途同归到 `t.a`。
3. **补全**：增加 `be`=yuanbao/AI 文本链接也走 `t.a`，证明覆盖到特殊消息类型。
4. **修正（兜底）**：初版兜底只 hook `startActivity`。核验：`MsgRetransmitUI` 由 item 以 `startActivity`/`ChattingContext.b0()` 启动，而其内部拉 `SelectConversationUI` 用 **`startActivityForResult`**。故兜底需**同时** hook `startActivity` 与 `startActivityForResult`（Activity/ContextWrapper/ContextImpl 三层），否则漏掉原生完整联系人列表那一屏。
5. **补全**：补充多选/合并/逐条转发入口 `a4`（ChattingEditModeLogic）为独立流，并说明兜底可覆盖。
6. **补全**：反向覆盖度佐证——全 APK 74 类构造 `MsgRetransmitUI` 意图，聊天单条长按来源已全部落在 `t.a`。

---

## 9. 一页速用 + 自检清单

- **唯一必要 Hook**：`com.tencent.mm.ui.chatting.manager.t` 静态方法 `a(com.tencent.mm.storage.e9, android.content.Context, java.lang.Runnable)`，`XC_MethodReplacement` 直接返回 `null` 并启动你的选择器。
- **建议兜底**：框架层 hook `startActivity` + `startActivityForResult`，拦 `...transmit.MsgRetransmitUI` 与 `...transmit.SelectConversationUI`，加 `fromChatting()` 来源过滤（顺带覆盖多选转发）。
- **数据**：`e9.getMsgId()` / `N0()` / `getType()`。
- 自检：
  - [ ] 你的选择器 Activity 已 `exported="true"`，显式 `setClassName` 拉起；
  - [ ] 用不调用原方法的方式（return null）以跳过安全弹窗与原生两屏；
  - [ ] 兜底加来源过滤，避免误伤 Finder/收藏/webview/wenote/位置等转发；
  - [ ] 如需接管“合并/逐条转发”，另 hook `a4.b` 或依赖兜底；
  - [ ] 不同微信版本若 `t` 类名漂移，用 `manager` 包内唯一 `static a(e9,Context,Runnable)` + “ShareDialogHelper”日志名兜底定位。

---

# §10 v3 补全：各消息类型「发送内核」+ 微信更新适配（DexKit）

## 10.1 关键新结论：你不需要逐类型自研发送内核（架构级修正）

复核 `MsgRetransmitUI` 时挖到决定性代码——微信**转发所有类型**消息到所选联系人的“总发送入口”就是它自己的回调 `onActivityResult`：

```java
// com.tencent.mm.ui.transmit.MsgRetransmitUI.onActivityResult(int i, int i2, Intent intent)
public void onActivityResult(int i, int i2, Intent intent) {
    ...
    if (intent != null) {
        String sel = intent.getStringExtra("Select_Conv_User");
        if (!y8.J0(sel)) this.h = y8.P1(sel.split(","));   // ← 目标用户列表（逗号分隔）
    }
    ...
    if (i2 == -1) {                        // RESULT_OK
        if (i != 0) { Log.e(...); return; }// ← 只认 requestCode == 0
        int msgType = this.e;              // ← 消息类型 Retr_Msg_Type
        ...
        g7(str);                           // ← 逐类型主发送（视频/文件/AppMsg/图片…）
        t7(str, this.h);                   // ← 文本→每个目标（SendMsgEvent）
    }
}
```

- **requestCode 必须为 0**（`i != 0` 直接 return）；
- **resultCode 必须为 -1**（RESULT_OK）；
- **目标 extra**：`"Select_Conv_User"` = 逗号分隔的 wxid（支持多选）；
- 触发后微信走 `g7()`（视频/文件/AppMsg/图片等 per-type 分支）+ `t7()`（文本→多目标 `SendMsgEvent`），**全部原生发送**——你无需实现视频/文件/位置/名片/表情/小程序任一内核。

### 10.1.1 推荐落地：替换原生选择器 + 回灌结果（Design A）

```
长按→转发→(manager.t.a 不动)→MsgRetransmitUI→hook 到 startActivityForResult(SelectConversationUI, 0)
       → 取消原生选择器 param.setResult(null)
       → 弹你自己的选择器，同时记下 from=MsgRetransmitUI 实例 与 req=0
       → 你选择器返回联系人后：直接
            XposedHelpers.callMethod(from, "onActivityResult", 0, Activity.RESULT_OK, ret)
            ret.putExtra("Select_Conv_User", "wxid1,wxid2…")
       → 微信原生 g7()/t7() 全类型发送，完成。
```

独立模块 Java 代码骨架：

```java
// ① 不再宽指纹 hook SelectConversationUI.onCreate；
//    只拦“来自 MsgRetransmitUI 的选择器启动”（配合 §6 兜底 startActivityForResult）
XposedHelpers.findAndHookMethod(Activity.class, "startActivityForResult",
    Intent.class, int.class, Bundle.class, new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            Intent in = (Intent) param.args[0];
            if (in == null || in.getComponent() == null) return;
            if (!"com.tencent.mm.ui.transmit.SelectConversationUI"
                    .equals(in.getComponent().getClassName())) return;
            Activity from = (Activity) param.thisObject;
            if (!from.getClass().getName().startsWith("com.tencent.mm.ui.transmit.MsgRetransmitUI")) return;
            int req = (Integer) param.args[1];        // 恒为 0
            launchYourPicker(from, req);              // 记下 from(WeakRef) + req
            param.setResult(null);                    // 取消微信原生选择器
        }
    });

// ② 你的选择器返回后（在你自己的 Activity 中）：
void onPicked(String[] pickedWxids) {
    Activity ms = holder.get();                       // 记下的 MsgRetransmitUI 实例
    if (ms == null) return;
    Intent ret = new Intent();
    ret.putExtra("Select_Conv_User", TextUtils.join(",", pickedWxids)); // 可多选
    XposedHelpers.callMethod(ms, "onActivityResult", 0, Activity.RESULT_OK, ret);
    // 微信立即全类型发送，无需任何 per-type 内核；随后其自身 finish()
}
```

优点：**零新增发送内核**；文本/图片/视频/文件/位置/名片/表情/小程序全兼容；版本波动小（只依赖稳定字符串锚点）。
代价：发送时微信 `MsgRetransmitUI` 短暂驻留后台并可能弹“发送成功”，属原生一致行为，通常可接受。

> 另一条更简变体：直接把 `SelectConversationUI` 当成“你的选择器壳”——hook 其 `onCreate`，用 `Activity.getCallingActivity()`/任务栈定位到 MsgRetransmitUI 后 `finish()` 并回灌 `onActivityResult`。但“宽指纹命中所有选择器”的问题依旧，因此**优先 Design A 的来源过滤**（caller 必须是 MsgRetransmitUI）。

## 10.2 若坚持自研逐类型发送内核（可选参考，均已在本 APK 核验）

> 以下发送内核全部来自 `MsgRetransmitUI` 转发路径（即“把已有消息发给新联系人”的原生代码），
> 比聊天输入框路径更贴合“转发”语义。位置均经反编译确认；个别末端调用链略长，标★处建议直接走 §10.1。

| 消息类型 | 微信原生发送内核（已验证位置） |
|---|---|
| 文本 | `com.tencent.mm.autogen.events.SendMsgEvent`（**非混淆**）：`ev.g.a=目标wxid; ev.g.b=内容; ev.g.c=<flag>; ev.g.d=0; ev.e();`（源自 `MsgRetransmitUI.t7` 逐目标循环） |
| 表情 | `com.tencent.mm.ui.chatting.q3.h(EmojiInfo, toUser, dx0.r)`（源自 `n7`）；先取 EmojiInfo：`((t) n0.c(t.class)).bj().N(fileName)` |
| 小程序/AppMsg | `com.tencent.mm.ui.chatting.q3.G(Context, toUser, content, type, isBiz)`（源自 `m7`）；SNS 特型(53/57/139)走 `r1/s1` 构造 + `d4.c(...)`/`t0.d.h(qs5.x1)` |
| 文件/大文件 | `MsgRetransmitUI.s7`：拷贝文件 → 大文件(>25MB)走 `h9.e().g(eb5.e)`，普通走 `bu.h0.yj(...)`/MediaService ★ |
| 视频 | `MsgRetransmitUI.c7/e7/x7`：校验/转码(`f3.b`,`z6.d`,`i3.b`)后进 `g7` 发送 ★ |
| 图片 | `MsgRetransmitUI.g7` 内按场景分派；常用 `q3.b(s61.v2, toUser, …, MsgIdTalker)` ★ |
| 语音/位置/名片/其它 | 均在 `MsgRetransmitUI.g7` 场景分派内（`Retr_Msg_Type` 分支）★ |

说明：
- `g7(String)` 是 `MsgRetransmitUI` 的**场景级主发送器**（约 3600 行 smali，按 `Retr_Scene`/`Retr_Msg_Type` 分派），
  每个类型最终汇到各自的 `processXxxTransfer`/`q3.xxx`/`SendMsgEvent`。逐条复刻成本高、易随版本失效。
- 因此**强烈建议**：使用 §10.1 Design A（回灌 `onActivityResult`），让微信原生完成以上所有分支，模块只负责“选人”。
- 文本内核 `SendMsgEvent` 如果模块已有 `forwardOne` 可保留；新增类型一律走 Design A，不要再补 6 个内核。

## 10.3 微信更新适配：DexKit 字符串锚点（独立模块 Java 版）

> 微信每版本混淆名都可能变（`manager.t`、`q3`、`fo$$a`…），但**日志 tag、Intent extra、autogen 事件名**极稳定。
> 用 DexKit 按“字符串 + 结构”定位，代替硬编码类名。

```java
import org.lsposed.lsplant.dexkit.DexKitBridge;
import org.lsposed.lsplant.dexkit.StringCondition;
import org.lsposed.lsplant.dexkit.StringMatchType;
import org.lsposed.lsplant.dexkit.StringStringPair;
import org.lsposed.lsplant.dexkit.MethodResult;

String apkPath = wxApp.getApplicationInfo().sourceDir;
DexKitBridge bridge = DexKitBridge.create(apkPath);

// ① 定位转发汇合入口：manager.t（静态 a(e9,Context,Runnable)）
List<String> tCls = bridge.findClassUsingStrings("t_cls", false,
    new StringStringPair(null, "ShareDialogHelper",      StringMatchType.CONTAINS, StringCondition.ALWAYS),
    new StringStringPair(null, "checkSecAndExecute",     StringMatchType.CONTAINS, StringCondition.ALWAYS));
// 再在 tCls 内找唯一“参数3、返回void、static”的方法 → 即 hook 目标
List<MethodResult> tM = bridge.findMethodUsingStrings("t_method", false, null, null,
    null, null, 3, -1, -1, -1, -1, null,
    new StringStringPair(null, "ShareDialogHelper", StringMatchType.CONTAINS, StringCondition.ALWAYS));
// 若命中多类，取 className 含 "ui.chatting.manager" 的那个

// ② 定位 MsgRetransmitUI（用于 Design A 的 caller 判断 + onActivityResult）
List<String> mrui = bridge.findClassUsingStrings("mrui", false,
    new StringStringPair(null, "MicroMsg.MsgRetransmitUI", StringMatchType.CONTAINS, StringCondition.ALWAYS),
    new StringStringPair(null, "Retr_Msg_Id",              StringMatchType.CONTAINS, StringCondition.ALWAYS));

// ③ 定位原生选择器 SelectConversationUI（兜底/来源过滤用）
List<String> scu = bridge.findClassUsingStrings("scu", false,
    new StringStringPair(null, "MicroMsg.SelectConversationUI", StringMatchType.CONTAINS, StringCondition.ALWAYS),
    new StringStringPair(null, "Select_Conv_User",              StringMatchType.CONTAINS, StringCondition.ALWAYS));

// ④ 文本发送事件（非混淆，通常无需 DexKit）
//    Class.forName("com.tencent.mm.autogen.events.SendMsgEvent", true, wxClassLoader)

## 10.4 你的现状诊断确认 + 修改清单

你判断的“根因”**完全正确**：`SelectConversationUI` 被建群选人、发消息选人、标签选人、私信选人等**所有联系人选择场景共用**，
`Activity#onCreate` 宽指纹（`Select_Conv_User`/`Select_Contact` extras、`BaseMvvmListActivity`/`MMBaseSelectContactUI` 血缘）必然把无关界面也接管 → “很多位置乱跳”。

修改清单（按优先级）：
1. **删除** `SelectConversationUI.onCreate` 宽指纹接管（或保留但加三重来源过滤：caller 类名以 `MsgRetransmitUI` 开头 + requestCode==0 + intent 带 `Retr_Msg_Type`/`Retr_Msg_Id`）。
2. **入口统一**：用 `manager.t.a` 作为“聊天长按转发”的统一锚点（v2 §4），需要“不弹原生界面”就用 `XC_MethodReplacement` 直接替换；需要“选完仍由微信发”就用 §10.1 Design A 让它先把 `MsgRetransmitUI` 拉起来。
3. **发送侧**：把缺失内核（视频/文件/位置/名片/表情/小程序）全部用 Design A 的 `onActivityResult(0, RESULT_OK, {"Select_Conv_User": wxid})` 回灌解决——**一行都不需要新增**。
4. 保留你现有的 `AutoForwardHook.forwardOne`（文本/图片/AppMsg）与 `TtsVoiceSender`（语音）作为**纯自动化/无人值守**路径；凡是“人点了转发再选人”的场景一律走 Design A。

## 10.5 模块落地总览（含既有内核与新增方案）

| 场景 | 方案 |
|---|---|
| 你选择器选中后，直接让微信原生发送 | §10.1 Design A：回灌 `MsgRetransmitUI.onActivityResult(0,RESULT_OK,{Select_Conv_User})` —— **覆盖一切类型，零内核** |
| 纯自动化发文本 | `com.tencent.mm.autogen.events.SendMsgEvent`：`g.a=wxid,g.b=text,g.d=0; e()` |
| 纯自动化发表情 | `com.tencent.mm.ui.chatting.q3.h(EmojiInfo, wxid, r)` |
| 纯自动化发小程序/AppMsg | `com.tencent.mm.ui.chatting.q3.G(Context, wxid, content, type, isBiz)` |
| 纯自动化发图片 | 保留 `AutoForwardHook.forwardOne` |
| 纯自动化发语音 | 保留 `TtsVoiceSender` |
| 视频/文件/位置/名片/小程序（自动化硬核） | 不建议手写；若必须，入口在 `MsgRetransmitUI.g7/s7/c7-e7-x7`（见 §10.2），★优先 Design A |

自检清单（v3）：
- [ ] 已删除 `SelectConversationUI.onCreate` 宽指纹，或缩窄为“caller==MsgRetransmitUI + req==0 + Retr_* 存在”；
- [ ] 接入 `manager.t.a`（入口）与 `Activity.startActivityForResult` 兜底（§6）二选一或并用；
- [ ] Design A 已在你的选择器返回回调里调用 `onActivityResult(0, -1, ret)`（requestCode 必须 0，result 必须 -1）；
- [ ] DexKit 锚点（§10.3）已加入启动期 self-check：找不到 `manager.t`/`MsgRetransmitUI` 时降级为来源过滤式拦截，而不是整模块失效；
- [ ] 微信升级后：重跑 §10.3 DexKit，确认 `t_cls`/`mrui`/`scu` 三者仍唯一命中；`SendMsgEvent` 类名稳定无需查。

---

# §11 v4 深度审查 + 推荐方案标注

## 11.1 全链路审查清单（每条带状态与证据）

| # | 关键声明 | 状态 | 证据 / 说明 |
|---|---|---|---|
| 1 | `manager.t.a(e9,Context,Runnable)` 是聊天「单条长按转发」唯一汇合入口 | ✅ 已核验 | 28 个调用点横跨所有消息类型；反编译源码即“ShareDialogHelper 安全检查→执行 cb” |
| 2 | 菜单 itemId 分歧（文本 fo=2、基类 zn=108）殊途同归 | ✅ 已核验 | 两处都 `uo.a(msg,4,0)` + 都调 `t.a(...)`；上报标记一致 |
| 3 | 点「转发」第一屏=`MsgRetransmitUI`，完整联系人列表=`SelectConversationUI` | ✅ 已核验 | `MsgRetransmitUI` 读取 Retr_*，内部 `startActivityForResult(SelectConversationUI)` |
| 4 | `onActivityResult` 契约：requestCode==0、resultCode==-1、extra=`Select_Conv_User` | ✅ 已核验 | `if (i != 0) return;` + `this.h = y8.P1(sel.split(","))` |
| 5 | 回灌后微信确实发送 | ✅ 已核验 | `onActivityResult` 调 `g7(str)+t7(str,this.h)`；`g7` scene-0 **遍历 this.h 逐目标发送**（smali 实证）；`t7` 是“自定义文本/多目标文本”补充路径 |
| 6 | 你现在模块“乱跳”根因 | ✅ 确认 | `SelectConversationUI` 被建群/发消息/标签/私信等所有选人场景共用，`onCreate` 宽指纹必然接管无关界面 |
| 7 | 兜底：框架层 `startActivityForResult` 拦截按组件名分流 | ✅ 可行 | 3 参重载是最终落点（2 参会委托到 3 参）；`param.setResult(null)` 可取消原生选择器 |
| 8 | DexKit 用日志 tag / Intent extra / autogen 事件名定位 | ✅ 稳定 | 这些字符串微信极少改；`SendMsgEvent` 类名非混淆 |

## 11.2 本轮回灌的两处澄清（防止误用）

1. **`t7` 空内容直接 return**：`t7(String str,…){ if(TextUtils.isEmpty(str)) return; }`
   → 回灌时**只需** `Select_Conv_User`，文本/媒体发送全由 `g7` 完成；`t7` 只在“转发时附带自定义文本(custom_send_text)”场景补发文本。
2. **回灌调用规范**：务必在主线程调用 `ms.onActivityResult(0, -1, ret)`；`ms` 用 `WeakReference<Activity>` 持有，调用前判 `!ms.isDestroyed()`；若已销毁则改走“重新构造 MsgRetransmitUI 再回灌”的降级路径（见 §11.3 🛡）。

## 11.3 推荐方案标注（最终定稿）

- ★★★ **首选（零新增内核，覆盖全部消息类型）**：
  `manager.t.a` 作入口 → `Activity.startActivityForResult` 做**来源过滤**拦截 `SelectConversationUI`（caller 开头 `MsgRetransmitUI` + req==0 + 带 `Retr_Msg_Type`）→ 弹你的选择器 → 回灌
  `onActivityResult(0, RESULT_OK, {Select_Conv_User=wxid1,wxid2})` → 微信原生 `g7()` 全类型发送。
- ★★ **纯自动化文本**：`com.tencent.mm.autogen.events.SendMsgEvent`：`g.a=wxid; g.b=文本; g.d=0; e()`（非混淆）。
- ★★ **纯自动化表情**：`com.tencent.mm.ui.chatting.q3.h(EmojiInfo, wxid, r)`。
- ★★ **纯自动化小程序/AppMsg**：`com.tencent.mm.ui.chatting.q3.G(Context, wxid, content, type, isBiz)`。
- 🛡 **兜底**：框架层 `startActivity`+`startActivityForResult` 按组件名（`MsgRetransmitUI`/`SelectConversationUI`）+ 来源过滤；若 `MsgRetransmitUI` 实例已销毁，重新用 fo$$a/zn$$a 同款 Intent（Retr_Msg_Id/Talker/content/Type/scene_from=17）拉起再回灌。
- ⚠️ **不建议**：自研视频/文件/位置/名片内核（成本高、随版本失效）；确需时入口在 `MsgRetransmitUI.g7/s7/c7-e7-x7`（§10.2）。

## 11.4 落地顺序（按优先级执行）

1. 移除 `SelectConversationUI.onCreate` 宽指纹接管（或加三重来源过滤）；
2. 接入 `manager.t.a`（`XC_MethodReplacement` 替换 = 完全接管；不动 = Design A 配合）；
3. 接入 `Activity.startActivityForResult` 来源过滤（记住 `from` 实例 + req=0）；
4. 你的选择器返回 → 主线程回灌 `onActivityResult(0, -1, ret)`；
5. 启动期用 §10.3 DexKit 自检（`t_cls`/`mrui`/`scu` 唯一命中），失配时降级为来源过滤式拦截；
6. 真机验证矩阵：文本/图片/语音/视频/文件/位置/名片/表情/小程序 各发一次；再验建群/标签/私信选人是否已不再被接管。

---

# §12 v5 语音转发专项核验（重要例外：语音不走 manager.t.a）

## 12.1 结论先行：语音转发**能成功**（微信原生支持）

反编译证据（`com.tencent.mm.ui.chatting.viewitems.bq` = `ChattingItemVoice$ChattingItemVoiceFrom`）：

```java
// bq.Q(MenuItem, ChattingContext d, ...)   —— 语音消息的长按菜单分发
int itemId = menuItem.getItemId();
...
else switch (itemId) {
    ...
    case 142: {                                  // ← 语音“转发”菜单项
        Intent intent = new Intent(dVar.g(), MsgRetransmitUI.class);
        if (e9Var.V2()) { intent.putExtra("Retr_Msg_Type", 6); }
        else            { intent.putExtra("Retr_Msg_Type", 4); }
        intent.putExtra("Retr_Msg_content", B0);   // B0 = 语音内容/描述
        intent.putExtra("scene_from", 17);
        ... startActivity(intent)
    }
}
```

且 `MsgRetransmitUI.g7` 的 scene-0 switch 中 **case 6 属于可发送组**（case 2,6,10,12,13,14,16 → 直接进入发送），语音走 `Retr_Msg_Type=6`（`V2()` 真时）→ **原生发送成功**。

## 12.2 关键影响：语音的“转发”**绕过 `manager.t.a`**

- `bq.Q`(142) 是**直接** `startActivity(MsgRetransmitUI)`，没有经过 `manager.t.a`（t.a 的 28 个调用点里没有语音类 `bq/gp/gq/iq/jp/mp/mq/nq/oq/pp`）。
- 因此：
  - ❌ 你只在 `manager.t.a` 上 Hook → **语音转发不会进你的选择器**，仍走微信原生选择器。
  - ✅ 要语音也走你的选择器，二选一：
    - **首选**：改用框架层 `startActivity`/`startActivityForResult` 按组件名（`MsgRetransmitUI`/`SelectConversationUI`）+ 来源过滤兜底 —— 语音（及任何“直连 MsgRetransmitUI”的类型）一并覆盖；
    - 或额外 hook 语音分发 `bq.Q` 的 `itemId==142` 分支（用 DexKit：类含 `MicroMsg.ChattingItemVoice` + 方法内含 `Retr_Msg_Type` + `MsgRetransmitUI`）。

## 12.3 发送侧对语音同样成立（Design A 可用）

语音消息进入 `MsgRetransmitUI` 后：你的选择器返回 → 主线程回灌
`onActivityResult(0, RESULT_OK, {Select_Conv_User=wxid})` → `g7` 走 type 6 发送分支 → **语音原生发送成功**。
（你的 `TtsVoiceSender` 是“自动发一段语音”用的；转发“已有语音消息”走 Design A 即可，无需再用它。）

## 12.4 对推荐标注的修正

- ★★★ **首选升级为框架层兜底**：`startActivity`+`startActivityForResult` 按组件名拦截 + 来源过滤 —— 这是**唯一能同时覆盖**「t.a 路径（多数类型）」与「语音等直连 MsgRetransmitUI 路径」的 Hook。
- ★★ `manager.t.a`：保留作“聊天单条转发精确入口”，但**不再是全类型唯一入口**（语音例外）。
- 🎯 语音专用（如需更精细）：`bq.Q` itemId==142。
- ✅ 回灌 `onActivityResult`（Design A）对所有类型（含语音）统一有效，发送无需逐类内核。

---

# §13 v6 语音长按无“转发”按钮：根因实证 + 处理方案

## 13.1 根因（已挖到字节级）

聊天长按菜单 = `MMPopupMenu`（`eu5.s0`，日志名 `com/tencent/mm/ui/widget/menu/MMPopupMenu`）展示；
菜单项由**每种消息类型各自的菜单构建方法** `S(kj5.i4 menu, View, am5.d tag)` 添加（`kj5.i4` 即菜单，`c(group,id,?,title,icon)` 添加项）。

**文本类菜单构建 `zn.S`（ChattingItemTextBase）：**
```java
if (apVar.R == 1) i4.c(d, 108, 0, getString(2131774067), 2131822160); // ← 转发(我发)
if (apVar.R == 2) i4.c(d, 142, 0, getString(2131774067), 2131822160); // ← 转发(收)
```
**语音类菜单构建 `bq.S`（ChattingItemVoiceFrom）：** 只加 播放/暂停(119/120)、收藏(116)、转文字(121/165)、提醒(114)、复制(141)… **没有加 108/142（转发）** → 你长按语音看不到转发按钮。

**但语音的转发“处理器”是存在的**：`bq.Q` 里 `case 142` 直接 `Intent(MsgRetransmitUI)`（§12 已证）。
⇒ “有处理器、无菜单项” = 你观察到的现象。整条链路：`zn.S/bq.S` 建菜单 → `MMPopupMenu.f(view,p4,v4,x,y)` 展示 → 点击 → `bq.Q/onMMMenuItemSelected` 分发。

## 13.2 处理方案（推荐）

### 方案①（治本，首选）：Hook 语音菜单构建 `bq.S` 注入“转发”项
```java
// 1) 注入菜单项：与文本同款 title(2131774067=转发)/icon(2131822160)
XposedHelpers.findAndHookMethod(
    "com.tencent.mm.ui.chatting.viewitems.bq", cl,
    "S", cl.loadClass("kj5.i4"), View.class, cl.loadClass("am5.d"),
    new XC_MethodHook() {
        @Override protected void afterHookedMethod(MethodHookParam p) {
            Object menu = p.args[0];                       // kj5.i4
            if (XposedHelpers.callMethod(menu, "findItem", 142) != null) return;
            View v = (View) p.args[1];
            String title = v.getContext().getString(2131774067); // “转发”
            XposedHelpers.callMethod(menu, "c", 0, 142, 0, title, 2131822160);
            // i4.c(group, itemId, ?, title, iconRes)
        }
    });

// 2) 点击无需再 Hook bq.Q —— 142 已有原生处理器：直接 Intent(MsgRetransmitUI)
//    随后用你已有的 Design A（框架层 hook SelectConversationUI→你的选择器→回灌
//    onActivityResult(0,-1,{Select_Conv_User})）→ 语音原生发送成功。
```

### 方案②（覆盖全部语音变体 + 所有消息类型收口）：Hook `eu5.s0.f(View, kj5.p4, kj5.v4, x, y)`
`MMPopupMenu.f` 是**所有聊天消息**长按菜单的展示收口点。在这里：
- 取 `view.getTag()` → `ps` → `e9` 判断消息类型；
- 语音（或任何缺转发项的类型）时，若菜单没有 142 就注入“转发”。
需要从 `s0` 实例拿到内部 `kj5.i4` 菜单对象（Hook `s0` 的菜单构建/字段，或 hook `s0.n/o` 展示前回调）。

### 方案③（若模块想完全自管语音）
Hook `bq.Q` 的 `itemId==142` 分支：不调原生，直接拉起你的选择器；返回后交给你自己的 `TtsVoiceSender` 发送（需取出原语音的音频/信息）。此方案只在你不想看到任何微信 UI 时选用。

## 13.3 DexKit 定位（微信更新后找语音菜单构建/分发）
```java
// 语音菜单构建（注入点）：类含 ChattingItemVoice 且方法参数含 kj5/i4
List<String> voiceMenu = bridge.findClassUsingStrings("voice_menu", false,
    new StringStringPair(null, "MicroMsg.ChattingItemVoice", StringMatchType.CONTAINS, StringCondition.ALWAYS));
// 再在该类内找“参数含 kj5.i4”的方法 → S / p4.a —— 即菜单构建，做 afterHooked 注入
// 语音菜单分发（拦截点）：该类内找含 MsgRetransmitUI + Retr_Msg_Type 的方法 → Q / onMMMenuItemSelected
// 文本对照锚点：类含 MicroMsg.ChattingItemTextFromBase 且方法含 2131774067 → 转发 title 资源
```

## 13.4 验证清单
- [ ] 长按语音，菜单出现“转发”（与文本同款图标/文字）；
- [ ] 点转发 → 不再打开微信原生选择器，进入你的选择器；
- [ ] 选人后 → 语音成功发送（原生 g7 type6 分支）；
- [ ] 文本/图片等其它类型长按菜单不受影响（142 已存在则不重复注入）。

---

# §14 v7 方案A（Design A）最终定稿：补全 + 修正清单

> 本轮深挖核验了 MsgRetransmitUI.onCreate（2483 行 smali）的完整控制流，
> 新增 1 个重大发现（直接发送模式），并修正/补全方案A的 5 处细节。

## 14.1 重大新发现：MsgRetransmitUI 自带「直接发送模式」（无选择器）

`onCreate` 末端存在 **两条互斥路径**（smali 实证）：
- **路径P（选人模式）**：拼好 intent v2 → `startActivityForResult(v2, req)`（`MMBaseActivity` 2参重载，内部落 `Activity.startActivityForResult(Intent,int,Bundle)`）→ 拉起 `SelectConversationUI`，等 onActivityResult 回灌 → g7+t7 发送；
- **路径D（直接发送模式）**：`cond_906` 分支——从**启动 Intent**里读一个**输入性目标字段**，若非空则直接 `this.h = 拆分(逗号)` → 立即 `g7(custom_send_text); t7(custom_send_text, this.h);` → **不发选择器、直接发送**。

推论：模块在用户选完人后，可以直接**用「原转发 Intent 的全部 Retr_* extras + 目标字段」重新构造并拉起 MsgRetransmitUI** 走路径D → 微信原生直发任意类型（含语音）。
目标字段名**极大概率就是 `Select_Conv_User`**（与 onActivityResult 同 key 双向复用；`onCreate` 的 `v19` 寄存器的字面量需真机确认）。若字段名不生效，仍用 14.3 的回灌方案兜底。

## 14.2 修正①：语音菜单注入「转发」项（补全为可直接运行）

```java
// 注入点：com.tencent.mm.ui.chatting.viewitems.bq.S(kj5.i4 menu, View v, am5.d tag)
XposedHelpers.findAndHookMethod("com.tencent.mm.ui.chatting.viewitems.bq", cl,
    "S", cl.loadClass("kj5.i4"), View.class, cl.loadClass("am5.d"),
    new XC_MethodHook() {
        @Override protected void afterHookedMethod(MethodHookParam p) {
            Object menu = p.args[0];
            if (menu == null) return;
            if (XposedHelpers.callMethod(menu, "findItem", 142) != null) return; // 已有则不重复
            View v = (View) p.args[1];
            int group = 0;
            Object tag = v.getTag();
            if (tag != null) { // ps(ItemDataTag).d() 与 zn.S 里一致的分组
                Object d = XposedHelpers.callMethod(tag, "d");
                if (d instanceof Integer) group = (Integer) d;
            }
            String title = v.getContext().getString(2131774067);     // “转发”
            XposedHelpers.callMethod(menu, "c", group, 142, 0, title, 2131822160);
        }
    });
// 点击无需再 Hook bq.Q：142 已有原生处理器（case 142 → Intent(MsgRetransmitUI)）。
// 只做 bq=ChattingItemVoiceFrom；收到的语音(To)用 §13 方案②(s0.f 收口)或 DexKit 批量注入补全。
```

## 14.3 修正②：Design A 拦截点精确化（补全 2参/3参 + 来源过滤）

```java
// MMBaseActivity 用的是 2 参 startActivityForResult(Intent,int) → 会委托到 Activity 3 参重载。
// 因此 Hook 3 参（必须）+ 2 参（保险）+ ContextWrapper 变体（保险）。
String[][] HOOKS = {
    {"android.app.Activity", "startActivityForResult", "(Landroid/content/Intent;ILandroid/os/Bundle;)V"},
    {"android.app.Activity", "startActivityForResult", "(Landroid/content/Intent;I)V"},
    {"android.content.ContextWrapper", "startActivityForResult", "(Landroid/content/Intent;I)V"},
};
for (String[] h : HOOKS) {
    XposedHelpers.findAndHookMethod(wxCl.loadClass(h[0]), h[1], ... /*按签名*/,
        new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!(p.args[0] instanceof Intent)) return;
                Intent in = (Intent) p.args[0];
                ComponentName cn = in.getComponent();
                if (cn == null) return;
                if (!"com.tencent.mm.ui.transmit.SelectConversationUI".equals(cn.getClassName())) return;
                Activity from = (Activity) p.thisObject;
                if (!from.getClass().getName().startsWith("com.tencent.mm.ui.transmit.MsgRetransmitUI")) return;
                int req = (Integer) p.args[1];            // 原生必须为 0（onActivityResult 校验）
                launchYourPicker(from, req, in);          // 保存 from(WeakRef) + req + 原 intent
                p.setResult(null);                        // 取消原生选择器
            }
        });
}
```

## 14.4 修正③：回灌 onActivityResult（补全线程/生命周期容错）

```java
void onPicked(WeakReference<Activity> ref, int req, String[] wxids, Intent orig) {
    Activity ms = ref.get();
    String target = TextUtils.join(",", wxids);
    Intent ret = new Intent();
    ret.putExtra("Select_Conv_User", target);
    if (ms != null && !ms.isDestroyed()) {
        // 首选：直灌 onActivityResult（契约 req==0, RESULT_OK==-1）
        final Activity f = ms;
        f.runOnUiThread(() ->
            XposedHelpers.callMethod(f, "onActivityResult", req, Activity.RESULT_OK, ret));
    } else {
        // 降级：路径D 直发 —— 用备份的 orig intent + 目标字段重建 MsgRetransmitUI
        Intent it = new Intent(orig);
        it.setClass(wxCl, Class.forName("com.tencent.mm.ui.transmit.MsgRetransmitUI", true, wxCl));
        it.putExtra("Select_Conv_User", target);          // 字段名真机验证；失败则退回 14.3
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        wxCl.loadClass("com.tencent.mm.ui.transmit.MsgRetransmitUI"); // ensure class loaded
        ctx.startActivity(it);
    }
}
```

## 14.5 语音 To 变体 / 维护墙

- `bq` 只覆盖 `ChattingItemVoiceFrom`（我发的语音）。**收到的语音(To)** 是另外的 View/菜单构建（`gp.onMMMenuItemSelected` 等在 `ChattingItemVoice*` 族）。
- 保证全量的两条路（任选其一）：
  1. **DexKit 批量**：找所有“类含 `MicroMsg.ChattingItemVoice` 且方法含 `kj5/i4` 参数（菜单构建 S/p4.a）”→ 统一 afterHooked 注入 142；分发拦截按“含 `MsgRetransmitUI`+`Retr_Msg_Type`”补 hook；
  2. **收口兜底**：`eu5.s0.f(View, kj5.p4, kj5.v4, int, int)`（MMPopupMenu 展示入口）里拿 `view.getTag()→ps→e9`，语音则注入 142（菜单对象从 s0 内部字段获取，需小改）。

## 14.6 真机验证矩阵（更新）
- [ ] 长按「我发的语音」→ 出现转发 → 点转发 → 你的选择器 → 选人 → 语音发送成功；
- [ ] 长按「收到的语音」→ 同样成立（To 变体已注入）；
- [ ] 文本/图片/视频/文件/AppMsg 长按转发回归不受影响；
- [ ] 建群/标签/发消息等其它选人界面不再被接管（来源过滤生效）；
- [ ] 直接发送模式（14.1）若字段名非 `Select_Conv_User`：改读 `v19` 字面量后仍用回灌方案，功能不受影响。

---

# §15 v8 依据运行日志的最终核验：为什么"注入了却不显示按钮"

> 依据 LeShaoV3 v3.0.180 / 微信 3180 真机日志反推，全部结论均已在本机 APK 反编译复核。

## 15.1 日志给出的权威链路（与本文档一致）

```
长按语音气泡 → viewitems.m0.onLongClick → m0.g(View)
  → new eu5.s0(MMPopupMenu)；s0.f(view, m0.e(=viewitems.o0, kj5.p4 提供者), m0.f(=viewitems.r0 分发器), x, y)
  → eu5.s0.f:  this.x.clear();   m0.e.a(this.x, view, null);     // ← 菜单唯一构建点
       → viewitems.o0.a(kj5.i4 x, View, ContextMenuInfo)
            → b0Var5.S(x, view, am5.d)   // m0.v(=viewitems.b0 子类,语音=bq) 建类型项(119/120/121/116/141…)
            → o0.a 再补通用项: add(d,123,0,引用) / add(d,100,0,…) 等
  → eu5.s0.p(x,y) 渲染：适配器 eu5.r0.getCount()=x.size()
                    getView(i): ((MenuItem)((ArrayList) x.d).get(i)).getIcon() / .getTitle()
```
**铁证**：适配器逐项读 `x.d.get(i)` 的 `getTitle()+getIcon()` → **菜单项必须有 Title(CharSequence) 和 Icon 才会画出来**。

## 15.2 你"注入了却没按钮"的三个根因（按概率排序）

1. **注入项没有 Title / 没有 Icon（最大嫌疑）**
   - 若用了 `i4.add(group,id,0, intResId)`（`add(IIII)` 重载**不设** title），或 title 传了资源 ID 而不是字符串 → `getTitle()`=null → `textView.setText(null)` → **渲染成空白格**，看起来就是"没有按钮"。
   - 网格模式下还会读 `getIcon()` 填左上图标，没有 icon 则图标位空白。
2. **注入对象不是 `MMPopupMenu.x`**：你 DexKit 批量 hook 了 4 个 `S()`，命中日志的那次拿到了 `kj5.i4`，但若命中的不是 `m0.v.S` 这条真实渲染路径（而是别的探针/旧菜单路径），item 进了另一个菜单对象 → 不可见。
3. **group 乱用**：原生菜单 group = `tag.d()`（列表位置），乱填 group 可能被分组渲染逻辑跳过。

## 15.3 修正后的注入代码（务必按此改）

```java
// 注入点：提供者 o0.a —— 运行时 3180 就是 com.tencent.mm.ui.chatting.viewitems.o0（日志已证实）
// çon 但请用 DexKit 锚点动态定位：类内含字符串 "OnCreateContextMMMenu" / "MicroMsg.ChattingItem"
Class<?> provider = findProvider();               // 见 §15.4
findAndHookMethod(provider, "a",
    cl.loadClass("kj5.i4"), View.class,
    cl.loadClass("android.view.ContextMenu$ContextMenuInfo"),
    new XC_MethodHook() {
        @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
            Object menu = p.args[0];                       // == MMPopupMenu.x，铁定被渲染
            if (XposedHelpers.callMethod(menu, "findItem", 108) != null) return; // 已有转发
            if (XposedHelpers.callMethod(menu, "findItem", 142) != null) return;
            View v = (View) p.args[1];
            Object tag = v.getTag();
            if (tag == null) return;
            int type = (Integer) XposedHelpers.callMethod(
                          XposedHelpers.callMethod(tag, "c"), "getType");
            if (type != 34) return;                        // 只补语音；其它类型原生已有转发
            int group = (Integer) XposedHelpers.callMethod(tag, "d");   // ★用 tag.d()，别写死
            // ★关键：必须用 c(..., CharSequence title, int icon) —— 同时给 Title 和 Icon！
            XposedHelpers.callMethod(menu, "c", group, 142, 0,
                v.getContext().getString(2131774067),      // "转发"（微信原生 title）
                2131822160);                               // 微信原生转发图标
            // —— 自检日志（务必加，跑一次确认）——
            int n = (Integer) XposedHelpers.callMethod(menu, "size");
            Object it = XposedHelpers.callMethod(menu, "getItem", n - 1);
            Log.i("WxFwd", "injected size=" + n + " title=" +
                XposedHelpers.callMethod(it, "getTitle") + " icon=" +
                XposedHelpers.callMethod(it, "getIcon"));
        }
    });
```

点击分发（接你的选择器）：分发器是 `viewitems.r0.onMMMenuItemSelected(MenuItem, int)`（`m0.f`），日志里你已 hook 过它——在里面判断 `getItemId()==142` 且当前 tag 是语音 → 跳你的选择器，然后走 §14 的 `onActivityResult(0,-1,{Select_Conv_User})` 回灌发送。

## 15.4 DexKit 动态定位（微信升级适配）

```java
// 提供者（注入点）：含 "OnCreateContextMMMenu" 日志串、且有方法 a(kj5.i4,View,ContextMenuInfo)
List<String> cls = bridge.findClassUsingStrings("p", false,
  new StringStringPair(null, "OnCreateContextMMMenu", StringMatchType.CONTAINS, StringCondition.ALWAYS));
// 兜底锚点：含 "MicroMsg.ChattingItem" 且方法参数含 kj5.i4
// 分发器（点击拦截）：类内方法 onMMMenuItemSelected 且引用 "Select_Conv_User"/"Retr_Msg_Type"
// 标题/图标资源：2131774067("转发"), 2131822160(转发图标) —— 也可运行时 getIdentifier("转发"标题 need)
```

## 15.5 你现在要做的 3 个改动
1. 把注入从"4 个 S()"改为**提供者 `a(kj5/i4,View,ContextMenuInfo)`**（3180 即 `viewitems.o0.a`，DexKit 锚点 `OnCreateContextMMMenu`）；
2. 注入改用 **`c(group=tag.d(), id=142, 0, title字符串, iconRes)`**，**别再**用 `add(g,id,0,intRes)`；
3. 加 §15.3 的自检日志，确认 `getTitle()/getIcon()` 非 null——若仍不显示，把该日志和长按一次的输出发来，可继续定位。

---

# §16 v9 挖到底：kj5.i4/kj5.j4 菜单 API 契约（smali 实证）

## 16.1 微信自定义菜单对象 `kj5.i4`（implements ContextMenu）

```java
public class kj5.i4 implements ContextMenu {
    public final List d;          // ★ 真正的菜单项列表（适配器 eu5.r0.getView 就是读 x.d.get(i)）
    public CharSequence e;
    public final Context f;       // ★ 构造时传入的 Context = 微信 Activity

    public MenuItem add(int group, int id, int order, int titleRes) {   // add(IIII)
        j4 item = new j4(this.f, id, group);
        item.t = titleRes;                    // ★ int 只存进 t 字段，不解析！
        d.add(item); return item;
    }
    public MenuItem add(int group, int id, int order, CharSequence title) { // add(III;CharSequence)
        j4 item = new j4(this.f, id, group);
        item.i = title;                       // ★ 直接设 Title
        d.add(item); return item;
    }
    public MenuItem c(int group, int id, int order, CharSequence title, int iconRes) {
        j4 item = new j4(this.f, id, group);
        item.i = title;                       // ★ Title
        item.setIcon(iconRes);                // ★ Icon（getIcon() 网格模式要用）
        d.add(item); return item;
    }
    public int size() { return d.size(); }
}
```

## 16.2 MenuItem 实现 `kj5.j4.getTitle()`（渲染成败的关键）

```java
public CharSequence getTitle() {
    CharSequence s = this.i;                  // 由 add(III;CharSequence) / c(...) 设置
    if (s == null) {
        int resId = this.t;                   // add(IIII) 塞进来的 int
        if (resId == 0 || this.C == null) return null;   // ★ resId=0 → 返回 null → 渲染空白！
        return this.C.getString(resId);       // ★ 用【微信的 Context】解析资源 ID
    }
    return s;
}
```

**三条铁律（对照你的日志逐条排雷）：**

1. **`add(g,id,0,资源ID)` 存的 int 会在 `getTitle()` 里用“微信的 Context”解析**：
   - 传 **0** → getTitle()=null → 列表模式下 `setText(null)` → **空白格，看起来就是"没按钮"**；
   - 传**你自己模块 APK 的 R.string**（如 0x7E00xxxx）→ `weChatCtx.getString(你的资源ID)` → **Resources.NotFoundException**（弹窗直接挂）或解析失败 → 同样看不到。
2. **必须用 String**：列表模式 `getView` 里是 `(String) item.getTitle()` 强转——CharSequence 不是 String 会 CCE。
3. **必须进 `x.d`**：只有加进 `MMPopupMenu.x.d` 的项才会被渲染；hook 的 menu 对象若不是这个 x，就是白加。

## 16.3 最终注入代码（按 16.1/16.2 铁律重写，可直接用）

```java
// 注入点 = 提供者（3180 实测 viewitems.o0.a；DexKit 锚点 "OnCreateContextMMMenu"）
findAndHookMethod(providerCls, "a",
    cl.loadClass("kj5.i4"), View.class,
    cl.loadClass("android.view.ContextMenu$ContextMenuInfo"),
    new XC_MethodHook() {
        @Override protected void afterHookedMethod(MethodHookParam p) {
            Object menu = p.args[0];                     // == MMPopupMenu.x（必渲染）
            if (call(menu, "findItem", 108) != null) return;
            if (call(menu, "findItem", 142) != null) return;
            View v = (View) p.args[1];
            Object tag = v.getTag(); if (tag == null) return;
            Object msg = call(tag, "c"); if (msg == null) return;
            if ((Integer) call(msg, "getType") != 34) return;          // 只补语音
            int group = (Integer) call(tag, "d");                      // 原生分组=tag.d()
            // ★ 唯一正确姿势：String 标题 + 资源ID 图标，一次 c() 搞定
            Object added = call(menu, "c", group, 142, 0,
                (CharSequence) "转发", 2131822160);                    // 微信原生转发图标
            // ★ 自检（跑一次，把日志发我即可定案）
            int n = (Integer) call(menu, "size");
            Object last = call(menu, "getItem", n - 1);
            Log.i("WxFwd", "size=" + n
                + " title=" + call(last, "getTitle")
                + " icon=" + call(last, "getIcon"));
        }
    });
```

## 16.4 如果自检 title/icon 非 null 但依然不显示 → 只有一种可能

说明你 hook 的 `a()` 提供的 `menu` 不是被渲染的那个 `x`。用下面两行日志彻底定位：
- 在提供者 `a()` 的 after 里打印 `System.identityHashCode(menu)`；
- hook `eu5.s0.p(int,int)`（渲染入口）在 before 里打印 `System.identityHashCode(((kj5.i4) getObjectField(thiz,"x")))`；
- **两个 hash 不一致 → hook 错类，用 DexKit 换成含 "OnCreateContextMMMenu" 串的那个提供者即可。**

## 16.5 全链路最终版（一次对齐）

```
① 长按语音 → m0.g(view) → s0.f(view, o0, r0, x, y) → o0.a(x) → m0.v.S(x) 建菜单
② 【本方案】hook 提供者 a(kj5.i4,View,ContextMenuInfo)：语音且无 108/142 → c(tag.d(),142,0,"转发",2131822160)
③ 菜单出现"转发" → 点击 → r0.onMMMenuItemSelected(item,pos) 里 itemId==142 → 拦截 → 你的选择器
④ 选人后 → onActivityResult(0,-1,{Select_Conv_User}) 回灌 → 微信 g7(type6) 原生发送
```

---

# §17 v10 依据 v3.0.181 运行日志的终审：注入项不渲染的真正原因

## 17.1 日志新证据（决定性）

1. **提供者链路完全证实**：`m0.<init>` 源码级证据——
   ```java
   public m0(b0 item, gk5.d ctx) {
       this.v = item;                                   // 消息 item（语音时=语音item）
       this.e = new o0(this, item, ctx);                // ★ 提供者恒为 viewitems.o0（所有消息类型共用！）
       this.f = new r0(this);                           // 点击分发器
   }
   ```
   → 语音长按同样走 `m0.g → s0.f(view, o0, r0, x, y) → o0.a(x) → m0.v.S(x)`。
   v3.0.181 已正确 hook `viewitems.o0.a(kj5/i4,View,ContextMenuInfo)`（安装日志证实）。
2. **`eu5.s0` 字段证实**：`x: Lkj5/i4`（菜单对象）、`w: Lkj5/p4`（展示期重建用的提供者，模块hook的o0.a同样会被走到）。
3. **VF 注入确认进过菜单**：`menuBuild: tag=ap type=34` → `menu injected 语音转发 id=2113929217 group=49`（group 已按 tag.d() 取，修正已对）。

## 17.2 真正根因：注入用的是【你自己模块的资源ID】

`2113929217 = 0x7E000001` —— 这是**你模块 APK 自己的 R.\*** 段 id（微信自身的 R.id 在 0x7F 段）。
而 `kj5.j4.getTitle()` / `setIcon(int)` 的资源解析用的是**微信的 Context/Resource**：

```java
public CharSequence getTitle() {
    CharSequence s = this.i;                         // add(III;CharSequence)/c(...) 才会设
    if (s == null) {
        int r = this.t;                              // add(IIII) 的 int 参数落这里
        if (r == 0 || this.C == null) return null;   // ★ 0 → null → 渲染空白
        return this.C.getString(r);                  // ★ 微信ctx.getString(你的资源ID) → 解析失败/NotFound
    }
    return s;
}
```

- 若你用 `add(g,id,0, R.string.xxx)`（你自己的资源ID）→ 微信 Context 解析不了 → **title=null/异常 → 渲染成空白格，看起来就是"没按钮"**；
- 若你用 `c(..., iconRes=你模块的drawable)` → `setIcon(int)` 同样经微信资源解析 → 图标位空白。

**这就是"注入了日志有、界面看不到"的最终答案。**

## 17.3 终审修正后的注入代码（唯一正确姿势）

```java
// 提供者（v3.0.181 已hook它）：o0.a(kj5.i4 x, View v, ContextMenuInfo)
// afterHookedMethod：
Object menu = p.args[0];
if (call(menu,"findItem",108)!=null || call(menu,"findItem",142)!=null) return;
View v = (View) p.args[1];
Object tag = v.getTag(); if (tag == null) return;
Object msg = call(tag,"c"); if (msg == null) return;
if ((Integer) call(msg,"getType") != 34) return;                  // 只补语音
int group = (Integer) call(tag,"d");                              // 原生分组
// ★★★ 三条铁律：
//  1) title 必须是“字符串”或“微信的资源ID”，绝不能用你模块的 R.string；
//  2) icon 必须是微信的资源ID，绝不能用你模块的 R.drawable；
//  3) 用 c(...) 一次把 title+icon 都带上。
String title = v.getContext().getString(2131774067);               // 微信"转发"(用微信ctx取!)
XposedHelpers.callMethod(menu, "c", group, 142, 0,
        (CharSequence) title, 2131822160);                         // 微信转发图标
// 自检（保留）：
int n = (Integer) call(menu,"size");
Object it = call(menu,"getItem", n-1);
Log.i("WxFwd","size="+n+" title="+call(it,"getTitle")+" icon="+call(it,"getIcon"));
```

## 17.4 一次运行即可定案的核对点
- `title` 必须打出 `转发`、`icon` 必须非 null → 若 null/异常，就是资源ID串了包（把 `2131774067` 当普通int传给 `add(IIII)`，或用了自己模块的资源）；
- 若 title/icon 都正确**仍不显示**，再比对接 `eu5.s0.p(int,int)` 里 `x.size()`（渲染时菜单项数）与注入时的 hash —— 正常必然一致（§16.4）。

---

# §18 v11 依据 v3.0.182 日志的终审：卡在模块自身的“跳过分支”

## 18.1 日志给出的最终答案

```
11:17:52.198 VF: menuBuild#1 enabled=true menu=kj5.i4 anchor=android.widget.TextView
11:17:52.198 VF: menuBuild: voice forward uses native, skip module injection
```

- **链路与钩子全部正确**：`WxFwdReplace: voice menu: hooked provider viewitems.o0.a(kj5/i4,View,ContextMenuInfo)`（安装成功）；长按语音时 provider 回调也进来了（`menuBuild` 打印，`menu=kj5.i4`）。
- **真正的拦路虎是新版自己加的分支**：`voice forward uses native, skip module injection` —— 模块判断“语音走原生转发”，于是**主动跳过了菜单项注入**。
- 但 §13 已用反编译证实：**微信原生语音菜单（`bq.S`）根本不加转发项（108/142）** —— “走原生”= 没有任何按钮。于是现象依旧是“没按钮”。

## 18.2 修正：把“native”判断从【菜单项】挪到【点击之后】

错误逻辑（现在）：
```
if (voice forward uses native) skip injection;   // ← 直接不注入 → 永远没按钮
```

正确逻辑：
```java
// 1) 菜单项：语音菜单若没有转发项 → 必须注入（与文本同款）
if (call(menu,"findItem",108)==null && call(menu,"findItem",142)==null) {
    injectVoiceForward(menu, v);        // §17.3 的 c(tag.d(),142,0,微信title,2131822160)
}
// 2) “走原生”只作用于点击之后：
//    点击 142 → 不拦 bq.Q(142)，让其原生拉起 MsgRetransmitUI
//              → 模块在 Activity.startActivityForResult 拦截 SelectConversationUI（已hook）
//              → 你的选择器 → onActivityResult(0,-1,{Select_Conv_User}) 回灌 → 微信 g7(type6) 发送
```
即：**“native”指的是发送内核用微信原生（Design A 回灌），绝不是“菜单项也不加”。**

## 18.3 v3.0.182 需要改的三行

1. 删掉/收紧 `voice forward uses native -> skip module injection` 这个早退；
2. 恢复 `tag/type/group` 与注入自检日志（本次日志已看不到这些，无法判断 tag.d()/title/icon 是否正确）：
   ```java
   Object tag = v.getTag(); Object msg = call(tag,"c");
   Log: "menuBuild tag="+tag.getClass()+" type="+call(msg,"getType")+" group="+call(tag,"d");
   ```
3. 注入后必须打印（§17.3 的自检，别删）：
   ```java
   Log: "injected size="+call(menu,"size")+" title="+call(call(menu,"getItem",n-1),"getTitle")+" icon="+call(...,"getIcon");
   ```

## 18.4 验收标准（改完后）
- 长按语音 → 菜单出现“转发”（与文本同款图标/文字）；
- 点转发 → **不弹微信的选择器**，直接弹你的选择器；
- 选人 → 语音发送成功（原生 g7 type6）；
- 建群/标签/私信等其它选人界面不受影响。

---

# §19 v12 方案B（确认版）：让微信原生选人，确定后由你的模块发送

## 19.1 就是这个意思 —— 三段分工

```
① 按钮：   hook o0.a 注入“转发”(142)          —— 已完成（§18）
② 选人：   点 142 后让 bq.Q(142) 原生跑        —— 它自动 Intent(MsgRetransmitUI) → 内部自动拉 SelectConversationUI
           （你无需自己拼 intent，微信拼的是对的）
③ 发送：   hook MsgRetransmitUI.onActivityResult —— 读到 Select_Conv_User 时：
             取到目标 wxid → 调你的内核发送（TtsVoiceSender / forwardOne）
             → param.setResult(null) 阻止微信原生 g7/t7（否则会双发）
```

## 19.2 关键代码位（三个 hook）

```java
// (1) 菜单注入（已有）：o0.a 里 无108/142 → c(tag.d(),142,0,微信title,2131822160)
// (2) 点击不拦 bq.Q(142)，让它原生走；只需在这里存一下上下文：
XposedHelpers.findAndHookMethod("com.tencent.mm.ui.chatting.viewitems.bq", cl,
    "Q", MenuItem.class, cl.loadClass("gk5.d"), cl.loadClass("am5.d"),
    new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam p) {
            if ((Integer) XposedHelpers.callMethod(p.args[0], "getItemId") != 142) return;
            CURRENT_MSG.set(p.thisObject);     // 或从 args 取 e9，WeakReference/ThreadLocal
        }
    });

// (3) 拦截结果、改由你的模块发：
XposedHelpers.findAndHookMethod("com.tencent.mm.ui.transmit.MsgRetransmitUI", cl,
    "onActivityResult", int.class, int.class, Intent.class,
    new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
            Intent data = (Intent) p.args[2];
            if (data == null) return;
            String sel = data.getStringExtra("Select_Conv_User");   // 逗号分隔 wxid
            if (y8_isEmpty(sel)) return;                            // 不是选人结果，放行
            Object msg = CURRENT_MSG.get();                          // ①(2) 存的，或从 getIntent() 的 Retr_Msg_Id/Talker 反查
            int type = (Integer) XposedHelpers.callMethod(msg, "getType");
            for (String wxid : sel.split(",")) {
                if (type == 34)      TtsVoiceSender.sendVoice(msg, wxid);        // 语音
                else if (text/img/appmsg) AutoForwardHook.forwardOne(msg, wxid); // 你的既有内核
                else { /* 视频/文件/位置/名片/表情/小程序：内核没有 → 见 19.4 混搭 */ }
            }
            p.setResult(null);        // ★ 阻止微信原生发送（否则双发）
        }
    });
```

## 19.3 与 Design A 的取舍

| | 方案B（本文：微信选人+你发送） | Design A（你的选择器+微信发送） |
|---|---|---|
| 选人界面 | 微信原生 SelectConversationUI（不用自己做UI） | 你自己的选择器 |
| 发送内核 | 你的（语音/文本/图片/AppMsg；其它类型缺） | 微信原生，**全类型** |
| 双发风险 | 有（必须 setResult(null) 拦死） | 无 |
| 实现量 | 小（3 个 hook） | 中（拦截+回灌） |

## 19.4 混合策略（推荐落地）
- **能自发的类型**（文本/图片/AppMsg/语音）→ 方案B（微信选人 + 你的内核发）；
- **不能自发的类型**（视频/文件/位置/名片/表情/小程序）→ 同一按钮点击后走 Design A：拦 `startActivityForResult(SelectConversationUI)` → 换你自己的选择器 → 回灌 `onActivityResult(0,-1,{Select_Conv_User})` → 微信原生发。
- 判据：`e9.getType()`；两条路共用同一个注入按钮，按 type 分流。
