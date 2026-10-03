# 微信「收藏语音转发」完整逆向分析与长按菜单注入方案

> 目标：在**微信收藏列表**中，长按一条**语音收藏**时，在长按菜单里注入「语音转发」按钮，点击后选择好友/群并发送该语音（原生微信已禁用该能力，需自行实现）。
> 适用对象：**独立 Xposed 模块**（不依赖 LSPilot 的 BSH 脚本）。
> 分析对象：微信 Android 客户端（DexKit/LSPilot 逆向，类名为当前版本混淆名，升级后需重新核对）。

---

## 0. TL;DR（结论速览）

| 问题 | 结论 |
|---|---|
| 收藏语音为什么没有「转发」？ | 菜单构建阶段就被 `tc2.x3`（`MicroMsg.FavSendFilter`）按 `field_type==3` 过滤掉；即使绕过，`FavoriteIndexUI.H7()` 也会 Toast"收藏的语音消息不能转发"并中止；多选分支 `mc.g()/mc.h()` 直接 Toast 返回 |
| 菜单在哪里构建？ | 新链路 `de2.m`（实现 `kj5.p4`）；兜底链路 `com.tencent.mm.plugin.fav.ui.gc` |
| 菜单在哪里响应点击？ | 新链路 `de2.n`（实现 `kj5.v4`）→ `de2.u` → `FavoriteIndexUI.K7(itemId, ...)`；兜底 `com.tencent.mm.plugin.fav.ui.hc` → 同样进 `K7` |
| 最稳注入点 | Hook `de2.m#a(kj5.i4, View, ContextMenuInfo)` 加菜单项 + Hook `de2.n#onMMMenuItemSelected` 拦截自定义 itemId |
| 语音文件怎么拿 | `tc2.s2.y(tc2.s2.K(favItem))`；时长(毫秒) `rq0.y`、秒 `(int) tc2.s2.Z(rq0.y)`；格式 `tc2.s2.d0(rq0.K)` |
| 语音怎么发 | 复刻聊天语音转发：`v61.d1.h(toUser, md5)` 生成新语音文件名并插记录 → 复制 silk 文件到 voice 目录 → `b41.h9.e().g(new v61.o(newFileName, 1))` 入队上传，上传完成后微信自己建 `type=34` 消息并入DB发送 |

---

## 1. 关键类名对照表（混淆名 → 语义）

| 类 | 语义 | 备注 |
|---|---|---|
| `com.tencent.mm.plugin.fav.ui.FavoriteIndexUI` | 收藏列表首页 UI | 继承 `FavBaseUI`；`h` 字段 = ListView(`favoriteLV`) |
| `com.tencent.mm.plugin.fav.ui.FavBaseUI` | 收藏 UI 基类 | 内含 `enterMoreMode/exitMoreMode` 等 |
| `com.tencent.mm.plugin.fav.ui.adapter.c` | `FavoriteNormalAdapter` | UI 中字段 `W`；`i(pos)` 取 `FavItemInfo` |
| `com.tencent.mm.plugin.fav.ui.fc` | 收藏列表 `OnItemLongClickListener` | **长按入口** |
| `de2.o` | 菜单显示封装（`MMMenuHelper` 等价物） | 静态 `a(ctx, view, item, delegate, z, aVar, onItemSelected, flags, obj)` |
| `de2.m` | **菜单项构建器**（`FavContextMenuBuilder`） | 实现 `kj5.p4` |
| `de2.n` | **菜单项点击分发** | 实现 `kj5.v4`（`onMMMenuItemSelected(MenuItem,int)`） |
| `de2.k` | 菜单能力委托接口 | `a()b()c()d()e()f()g()h()`（`a`=删除/`b`=多选/`c`=看原消息/`f`=编辑标签/`g`=是否可转发） |
| `de2.v` | `FavoriteIndexUI` 的 `de2.k` 实现 | |
| `com.tencent.mm.plugin.fav.ui.gc` / `hc` | 兜底菜单构建器 / 点击监听 | 当 `de2.p.a()` 为 false 时使用 |
| `eu5.s0` | `com.tencent.mm.ui.widget.menu.MMPopupMenu` | 字段 `w`=构建器, `v`=点击回调, `n(x,y)` 定位 |
| `kj5.i4 / p4 / v4` | `MMMenuBuilder` / `OnCreateContextMenu` / `OnMMMenuItemSelected` | |
| `com.tencent.mm.plugin.fav.ui.mc` | `FavoriteMenuHelper` | `g()` 单条转发、`h()` 多条、`c()` 类型映射、`f()` 批量删 |
| `tc2.x3` | `FavSendFilter` | **语音过滤发生地** |
| `tc2.s2` | `Fav.FavApiLogic`（收藏条目工具） | `K()` 取首个数据项、`y()` 本地路径、`Z()` 秒数、`d0()` 格式、`F0()` 触发下载 |
| `tc2.m3` / `im.o3` | `FavItemInfo` / `FavItemInfoDB` | `field_type/field_localId/field_id/field_favProto/field_itemStatus` |
| `pc5.mr0` / `pc5.rq0` | `FavProto` / `FavProtoItem` | `rq0.T`=文件名、`rq0.K`=扩展名、`rq0.y`=时长(ms) |
| `tc2.m4` | `FavVoiceLogic`（收藏语音播放） | `d(path, voiceType)` |
| `tc2.h6` / `im.k3` | 收藏插件服务 / CDN 记录实体 | `h6.sj()`=条目DB、`h6.nj()`=CDN DB、`h6.mj()`=下载引擎 |
| `v61.o` | `NetSceneUploadVoice` | 构造 `(String fileName, int)` |
| `v61.d1` | `VoiceLogic`（语音 DB/文件工具） | `h()/s()/u()/k()` |
| `v61.c1` | `VoiceInfo` 记录 | `l`=时长、`c`=talker、`b`=fileName |
| `com.tencent.mm.storage.e9` | **MsgInfo** | 继承 `dx0.p3` → `im.c8`；`setType(34)` = 语音 |
| `b41.h9` | 核心静态入口 | `b()`=MsgStorage、`e()`=NetSceneQueue、`g()`=MsgStorage2 |
| `ph5.n0` | 服务定位器 | `n0.c(Xxx.class)` |
| `ou5.x` | voice 路径枚举 | `ou5.x.j` 为默认 voice 目录 |
| `com.tencent.mm.ui.transmit.SelectConversationUI` | 转发选择会话界面 | |

---

## 2. 链路 A：长按菜单的构建（收藏列表）

### 2.1 入口：`com.tencent.mm.plugin.fav.ui.fc.onItemLongClick`

```java
public boolean onItemLongClick(AdapterView<?> parent, View view, int i, long j) {
    if (i < ((FavBaseUI) ui).h.getHeaderViewsCount()) return true;      // 忽略 header
    HashMap<String,Object> rep = new HashMap<>();
    rep.put("card_fav_type", ((o3) item).field_type);
    rep.put("card_clk_type", 1);
    a.a.a("fav_page_card_operation", "view_clk", rep);                  // 埋点
    m3 item = ui.W.i(i - headerViews);                                  // ★ FavItemInfo

    if (de2.p.a()) {                                                    // ★ 新菜单开关
        de2.o.a(ui, view, item,
                new de2.v(item, ui, i, view),      // de2.k 委托（能力位）
                false, null,
                new de2.u(ui, i, view, item),      // 点击回调 r96.p
                48, null);
    } else {                                                            // ★ 兜底菜单
        eu5.s0 menu = new eu5.s0(ui.getContext(), view);
        menu.A = true;
        menu.w = new com.tencent.mm.plugin.fav.ui.gc(ui, i, j);         // p4 构建器
        menu.v = new com.tencent.mm.plugin.fav.ui.hc(ui, i, view, item);// v4 回调
        int[] loc = new int[2]; view.getLocationInWindow(loc);
        menu.n((loc[0] + view.getWidth()) / 2, loc[1]);
    }
    return true;
}
```
`de2.o.a()` 内部：`new eu5.s0(ctx, view)` → `.A=true` → `.w = new de2.m(ctx, item, delegate, z)` → `.v = new de2.n(delegate, ctx, onItemSelected)` → `n(x, y)` → `eu5.s0.p(x,y)` → `p4.a(this.x, this.f, null)`（调用构建器）。

### 2.2 菜单项（`de2.m#a`，实测 Smali 逐条核对）

统一调用：`kj5.i4.c(0 /*groupId*/, itemId, 0 /*order*/, title, iconRes)`

| itemId | title res | icon res | 显示条件（`de2.m`） | 含义 |
|---|---|---|---|---|
| **3** | 2131761210 | 2131822160 | `de2.j.a(item) && k.g() && (!x3.b(item,false,false) \|\| tc2.s2.n0(item))` | 转发给朋友 |
| 8 | 2131781221 | 2131823443 | `j.a && j.a(item) && !ee2.i.a(item)` | 星标 |
| 9 | 2131781227 | 2131822171 | `j.a && j.a(item) && ee2.i.a(item)` | 取消星标 |
| 2 | 2131761089 | 2131823463 | `j.a && k.f()` | 编辑标签 |
| 0 | 2131761062 | 2131821955 | `k.a()` | 删除 |
| 1 | 2131761147 | 2131822077 | `j.a && k.b()` | 多选/编辑 |
| 5 | 2131761036 | 2131822115 | `RepairerConfigGlobalFavDebug == 1` | 收藏调试 |
| 6 | 2131761125 | 2131821930/2131822994 | `k.c() && z3 && item.R1`（`de2.i.a`） | 跳转原消息（笔记/记录） |

> 注：标题 res 已按 jadx 输出十进制记录；其中 item 3 的 `2131761210` 在收藏语境即"转发给朋友"（按 `gc` 兜底构建器与 `K7` 的 `do transmit` 分支交叉验证）。

### 2.3 语音被过滤的确切位置

`de2.m#a` 第一行即：

```java
boolean a = de2.j.a(item);                       // field_id > 0 && item.F0()：已同步服务端
boolean b = new tc2.x3().b(item, false, false);  // FavSendFilter.filter()
...
if (a && k.g() && (!b || tc2.s2.n0(item))) menu.c(0, 3, 0, "转发给朋友", ...);
```

`tc2.x3`（`MicroMsg.FavSendFilter`，接口 `tc2.f5`）构造：`this.a = true; this.b = true; this.c=false; this.d=false;`

```java
// tc2.x3#b(v, z, z2) —— filter()
case 3:                      // FAV_ITEM_TYPE_VOICE
    if (this.a) { Log.i("MicroMsg.FavSendFilter","[FAV_ITEM_TYPE_VOICE] canFilterVoice = true, back"); return true; }  // ★ 被过滤
    return false;
```

而 `de2.k#g()`（`de2.v`/`ka` 中的实现）= `!x3.b(item,false,false) || s2.n0(item)` —— 对语音同样为 false。
→ **结论：语音收藏在菜单构建阶段就永远拿不到 itemId=3 的「转发」项。这就是必须注入的原因。**

---

## 3. 链路 B：菜单点击分发

### 3.1 `de2.n#onMMMenuItemSelected(MenuItem, int)`

```java
int itemId = menuItem.getItemId();
if (itemId == 5) { /* RepairerFavDebugUI */ }
else if (itemId == 8) { /* ee2.i2.f(...)  星标 */ }
else if (itemId == 9) { /* ee2.i2.a.e(...) 取消星标 */ }
// 0/1/2/3/6/7 → 交给外部回调
this.f.invoke(menuItem, i);   // r96.p → de2.u#invoke
```
`de2.u#invoke` → `FavoriteIndexUI.K7(menuItem.getItemId(), pos, view, item)`。
兜底链路：`com.tencent.mm.plugin.fav.ui.hc#onMMMenuItemSelected` → 同样 `K7(menuItem.getItemId(), ...)`。

### 3.2 `FavoriteIndexUI.K7(int itemId, int pos, View v, m3 item)`

| itemId | 行为 | 代码证据 |
|---|---|---|
| 0 | 删除确认弹窗 → `e1.B(...)` | `getString(2131761064)` 标题 + `bb` 回调 |
| 1 | 进入多选编辑态（`W.g(true,item)`、`setOnItemLongClickListener(null)`、`showOptionMenu(11,false)`） | log `enterEditMode: hide post` |
| 2 | `FavTagEditUI`（`key_fav_scene=4`, `key_fav_item_id=field_localId`） | |
| **3** | **转发**：`LinkedList l; l.add(item.q0()); if (H7(l, this, cb, true, true)) O7(ctx, 4106, W, item);` | log `do transmit, long click info is %s` |
| 5 | `RepairerFavDebugUI` | |
| 6/7 | 未在反编译中命中分支（当前版本未走） | |

### 3.3 `O7` → `mc.g`（`MicroMsg.FavoriteMenuHelper`）

`FavoriteIndexUI.O7(Context, reqCode, adapter, m3)`：先做收藏空间容量校验（`fav_cap_limit/fav_cap_usage`...），再 `mc.g(ctx, reqCode, cVar, m3)`。

`mc.g()`：
- **reqCode 4106（单条转发）**
  ```java
  intent.putExtra("Select_Conv_Type", 3);
  intent.putExtra("scene_from", 1);
  intent.putExtra("mutil_select_is_ret", true);
  intent.putExtra("select_fav_local_id", ((o3) item).field_localId);         // select_fav_local_id
  if (item.R1) intent.putExtra("select_fav_fake_local_id", item.T1);          // 转发"原消息"场景
  if (field_type == 19) intent.putExtra("appbrand_params", b0.c(item));
  ((tc2.p5) n0.c(tc2.p5.class)).ej(field_localId);
  if (mc.c(field_type) != -1) intent.putExtra("Retr_Msg_Type", mc.c(field_type));
  tc2.n4.c(intent, item);                                                      // 企业微信数据
  hc5.l.v(ctx, ".ui.transmit.SelectConversationUI", intent, 4106);
  ```
  另有 `i3Var.c(item) && i3Var.a(item)` 命中时走 `s73.b0.ej(...)` 直接分享。
- **reqCode 4105（多选）**：`size==1` 时同样构造 intent，但：
  ```java
  if (field_type == 3) { e1.T(ctx, ctx.getString(2131761212)); return false; }  // ★ "收藏的语音消息不能转发"
  ```

`mc.c(int favType)` → `Retr_Msg_Type` 映射：`1→4, 2→0, 16→11, 4→1, 14→13, 6→9, 8→3, 其他(含 3=语音)→2`。

### 3.4 最后一道拦截：`FavoriteIndexUI.H7(List, ctx, listener, z, z2)`

遍历收藏项统计：`field_type==3 → i4++`（语音）、`19→i5`、`5/24→i6`、数据项 `h2==2/1 → i/i2`、可转发 `i3`。
单条收藏时：

```java
} else {
    if (i3 > 0) { e1.T(ctx, getString(2131755017)); return false; }   // 收藏中的文件未下载完成
    if (i4 > 0) { e1.T(ctx, getString(2131755018)); return false; }   // ★ 收藏的语音消息不能转发
    ...
}
```
→ `K7` 中 `if (H7(...))` 为 false，转发根本不执行。

**所以微信收藏语音有 3 道闸：①`x3.b()` 菜单过滤 ②`H7()` Toast ③`mc.g(4105)` Toast。**

---

## 4. 收藏语音的数据取法（发送时必需）

```java
// 1) 取收藏条目
tc2.m3 item = ((tc2.h6) ph5.n0.c(tc2.h6.class)).sj().H(field_localId);
pc5.rq0 data = tc2.s2.K(item);                 // field_favProto.f.get(0)

// 2) 本地语音文件（silk / speex / amr）
String path = tc2.s2.y(data);                  // s2.y(): s2.s(rq0.T) 目录 + rq0.K 扩展名，含 md5 回退
if (!new File(path).exists()) tc2.s2.F0(item, true);   // 触发下载（收藏语音未下载时）

// 3) 时长（毫秒 → 秒）与格式
int seconds  = (int) tc2.s2.Z(data.y);         // s2.Z(ms) = round(ms/1000)，最小 1
int voiceFmt = tc2.s2.d0(data.K);              // "speex"→1, "silk"→2, else 0

// 4) 类型常量
//    ((im.o3) item).field_type == 3  即语音收藏；field_localId/field_id/field_favProto(pc5.mr0)
```
证据：`FavoriteVoiceDetailUI#onCreate` 中 `s2.K(m3)/s2.y(rq0)/(int)s2.Z(rq0.y)/s2.d0(rq0.K)` 四连用法。

---

## 5. 长按菜单注入方案（Hook 设计）

> 三种方案，按推荐度排序。方案 A 完全自主可控，推荐。

### 方案 A（推荐）：构建器注入 + 分发器拦截

**Hook 1 — 注入菜单项（新链路）**
```java
Class<?> cBuilder = XposedHelpers.findClass("de2.m", cl);
XposedHelpers.findAndHookMethod(cBuilder, "a", "kj5.i4", View.class,
        ContextMenu.ContextMenuInfo.class, new XC_MethodHook() {
    @Override protected void afterHookedMethod(MethodHookParam p) {
        Object item = XposedHelpers.getObjectField(p.thisObject, "b");   // tc2.m3
        if (item == null) return;
        int type = (int) XposedHelpers.getObjectField(
                XposedHelpers.getObjectField(item, ... ) /* o3 */, "field_type"); // 见下方取法
        if (type != 3) return;                                            // 仅语音
        Object menu = p.args[0];
        XposedHelpers.callMethod(menu, "c", 0, 10086, 0, "语音转发", 2131822160);
    }
});
```
> `field_type` 取法：`item` 是 `tc2.m3`，`FavItemInfoDB` 为 `im.o3`；用 `XposedHelpers.findField(item.getClass(), "field_type")` 或直接反射 `im.o3`。
> 若不想反射，可改为判断"该 item 的语音文件存在 + `m3` 实例"。

**Hook 1b — 兜底链路同样注入**
```java
XposedHelpers.findAndHookMethod("com.tencent.mm.plugin.fav.ui.gc", cl, "a",
        "kj5.i4", View.class, ContextMenu.ContextMenuInfo.class, afterHook);   // 逻辑同上
```
（`gc` 中可从 `p.thisObject.c` 取 `FavoriteIndexUI`，再 `ui.W.i(pos-headers)` 取 item。）

**Hook 2 — 拦截自定义 itemId**
```java
XposedHelpers.findAndHookMethod("de2.n", cl, "onMMMenuItemSelected",
        MenuItem.class, int.class, new XC_MethodHook() {
    @Override protected void beforeHookedMethod(MethodHookParam p) {
        MenuItem mi = (MenuItem) p.args[0];
        if (mi.getItemId() != 10086) return;          // 其他交给微信
        p.setResult(null);                            // 阻止原逻辑
        Object delegate = XposedHelpers.getObjectField(p.thisObject, "d");  // de2.k
        Object item = XposedHelpers.callMethod(delegate, "e");              // → tc2.m3
        Object ctx   = XposedHelpers.getObjectField(p.thisObject, "e");     // Context(Activity)
        VoiceForwarder.start((Activity) ctx, item);
    }
});
```
**Hook 2b — 兜底链路拦截**
```java
XposedHelpers.findAndHookMethod("com.tencent.mm.plugin.fav.ui.hc", cl,
        "onMMMenuItemSelected", MenuItem.class, int.class, beforeHook);   // 同样 10086 → 自定义
```

> ⚠️ 兜底链路的 `hc` 会把 `getItemId()` 透传给 `K7`，`K7` 对未知 id 无分支 → 不做处理。所以两条链路都必须 Hook。
> 若只想 Hook 一处：可 Hook `com.tencent.mm.plugin.fav.ui.FavoriteIndexUI#K7(int,int,View,m3)` 并处理 `itemId==10086`（同时仍需注入菜单项的 Hook）。

### 方案 B（放开原生过滤，让微信自己弹"转发"）

```java
// tc2.x3#b(v, z, z2)  —— 对语音返回 false（=不过滤）
beforeHooked: if (type==3) p.setResult(false);
// FavoriteIndexUI#H7  —— 直接返回 true 放行
beforeHooked: p.setResult(true);
```
- 优点：纯 Hook，`mc.g(4106)` → `SelectConversationUI` 全链路复用。
- 风险：`SelectConversationUI#X7(Intent,String)` → `h8(j1)` + `FavInitConfirmDialogContentEvent` 这条确认链在语音上很可能仍被拒（`Retr_Msg_Type=2` 走的是普通消息转发而非收藏转发，`h8` 的 `case 2` 分支会去 `r.v(str)` 读聊天消息，对收藏 localId 无效）。**需真机验证，不建议作为首选。**

### 方案 C（直接 Hook `K7` 扩展分支）
注入菜单项后，把自定义 itemId 走 `K7`，再 Hook `FavoriteIndexUI#K7` 增加分支。适合"只想 Hook 一个点击点"的场景，但注入侧仍需 Hook `de2.m`/`gc`。

---

## 6. "选择好友/群"的会话选择

复用微信自己的 `SelectConversationUI`，**不要自己写联系人 UI**：

```java
static final int REQ_VOICE_FWD = 0x5210;

Intent it = new Intent();
it.setClassName(ctx, "com.tencent.mm.ui.transmit.SelectConversationUI");
it.putExtra("Select_Conv_Type", 3);        // 会话（单聊+群聊）
it.putExtra("scene_from", 1);
it.putExtra("mutil_select_is_ret", true);
it.putExtra("select_count", 1);
((Activity) ctx).startActivityForResult(it, REQ_VOICE_FWD);
```

结果仍回到 `FavoriteIndexUI.onActivityResult`（由当前 Activity 承载），因此：

```java
XposedHelpers.findAndHookMethod("com.tencent.mm.plugin.fav.ui.FavoriteIndexUI", cl,
        "onActivityResult", int.class, int.class, Intent.class, new XC_MethodHook() {
    @Override protected void afterHookedMethod(MethodHookParam p) {
        if ((int) p.args[0] != REQ_VOICE_FWD) return;
        if ((int) p.args[1] != Activity.RESULT_OK) return;
        Intent data = (Intent) p.args[2];
        String talker = data.getStringExtra("Select_Conv_User");   // 目标 talker
        VoiceForwarder.send(FavVoiceHolder.get(), talker);
    }
});
```

> 备选：Hook `SelectConversationUI#W7(String)`（日志 `doClickUser=%s`）直接拿 talker。
> 注意：不带 `select_fav_local_id` 直接启动 `SelectConversationUI`，确认弹窗内容可能为空/异常，真机需确认；若异常，改用 Hook `W7`。

---

## 7. 语音发送实现（核心）

复刻微信"转发聊天语音"的最短路径（逆向自 `com.tencent.mm.ui.chatting.cd#onMMMenuItemSelected`，日志 `MicroMsg.LongClickBrandServiceHelper`，`connector click[voice]: to[%s] filePath[%s]`）：

```java
// 原文：
// String cj = ((h1) n0.c(h1.class)).cj(ctx);   // 源语音 fileName
// c1 k = v61.d1.k(cj);
// String s = v61.d1.s(toUser, cj, k == null ? 0 : k.l);
// b41.h9.e().g(new v61.o(s, 1));
```

### 7.1 `v61.d1.s(String toUser, String srcFileName, int len)`

```java
String h = v61.d1.h(toUser, <md5 of srcFile>);          // 插一条 voice 记录，返回新 fileName
z6.d(((u0) n0.c(u0.class)).Ej(ou5.x.j, srcFileName, false),
      ((u0) n0.c(u0.class)).Fj(ou5.x.j, h, false, true), false);   // 复制语音文件
v61.d1.u(h, len, 1, null, null);                        // 兜底建记录
return h;
```

### 7.2 `v61.d1.h(String talker, String md5)`

```java
String name = com.tencent.mm.modelbase.m1.b1(b41.y1.u(), md5);   // 生成 fileName
v61.c1 rec = new v61.c1();
rec.b = name;  rec.c = talker;  rec.d = name;
rec.i = 1;     rec.n = b41.y1.u();
rec.j = rec.k = System.currentTimeMillis()/1000;
rec.a = -1;
if (VoiceStorage.insert(rec)) return name;   // 日志 "MicroMsg.VoiceLogic: startRecord insert voicestg success"
return null;
```

### 7.3 上传即发送：`new v61.o(fileName, 1)` 入队

`NetSceneUploadVoice`（`v61.o`）`doScene()`：
1. `v61.d1.k(this.f)` 读 voice 记录（`fileName`）；
2. `v61.d1.i((e9)null, this.f)` 拿 fileOp → 读语音数据分片上传；
3. 上传完成后回调 `v61.d1.u(fileName, len, 1, null, null)`：
   - `e9 msg = new e9(); msg.u1(rec.c); msg.setType(34); msg.k1(1);`
   - `msg.t1(1); msg.b1(v61.a1.c(rec.n, rec.l, false));`（`<msg><voicemsg .../></msg>`，与 `ks1.h`"FastBackupItemVoice"的 `packetVoice xml error`/`voicemsg` 组装一致）
   - `msg.r3(ma.f(null))`（clientMsgId）、`msg.e1(...)`、`msg.n3(1)`
   - `((u0) n0.c(u0.class)).Dj(msg, fileName, false)` → 入库 + 发送
4. 日志 tag：`MicroMsg.NetSceneUploadVoice` / `MicroMsg.VoiceLogic`。

### 7.4 本模块的最小实现（收藏语音 → 转发）

```java
void sendFavVoice(Activity aty, tc2.m3 favItem, String toUser) {
    // ① 收藏语音本地文件
    pc5.rq0 data = tc2.s2.K(favItem);
    String srcPath  = tc2.s2.y(data);
    if (!new File(srcPath).exists()) { tc2.s2.F0(favItem, true); return; }  // 先下载，下次再转发
    int durMs = (int) (data.y);                          // 毫秒

    // ② 生成语音记录 + 复制文件到 voice 目录
    String md5 = md5OfFile(srcPath);                      // 与 v61.d1.h 内部一致
    String newName = v61.d1.h(toUser, md5);               // 插记录
    String dst = ((u0) ph5.n0.c(u0.class)).Fj(ou5.x.j, newName, false, true);
    copy(srcPath, dst);

    // ③ 入队上传（上传完微信自动建 type=34 消息并发送）
    ((com.tencent.mm.modelbase.r1) b41.h9.e()).g(new v61.o(newName, durMs));
}
```

> 也可以用 `v61.d1.s(toUser, srcFileName, durMs)` 一步完成"复制+插记录"，但要求 `srcFileName` 能被 `u0.Ej(ou5.x.j, srcName, false)` 解析（即源文件已在 voice 目录）。收藏语音文件不在该目录，因此建议按 7.4 手动 `h()` + 复制。
> `durMs` 的取值参照 `v61.c1.l`（`cd#onMMMenuItemSelected` 用 `k.l`）；若上传后时长显示异常，改用 `(int) tc2.s2.Z(data.y) * 1000` 试。

---

## 8. 兼容性与自测清单

| 项 | 校验方式 |
|---|---|
| `de2.m` / `de2.n` 类是否存在 | Hook 时 try-catch + `findClassIfExists`；不同版本可能改名 |
| 新/兜底菜单链路 | 看 `de2.p.a()` 的返回值（`tc2.x5#o` 字段）；两条都要 Hook 才稳 |
| itemId 10086 不冲突 | 微信原生 itemId 集合为 `{0,1,2,3,5,6,7,8,9}`；10086 安全 |
| 语音文件是否已下载 | `tc2.s2.y()` 后判 `exists()`，否则 `tc2.s2.F0(item,true)` 触发下载并提示用户重试 |
| 语音时长 | `rq0.y` 为毫秒；`s2.Z()` 返回秒 |
| 群聊发送 | `toUser` 以 `@chatroom` 结尾时 `v61.o` 内部按群处理，无需特殊分支 |
| 上传失败 | 关注 logcat：`MicroMsg.NetSceneUploadVoice`、`MicroMsg.VoiceLogic`、`MicroMsg.FavSendFilter`、`MicroMsg.FavoriteMenuHelper`、`MicroMsg.SelectConversationUI` |

---

## 9. 不确定项 / 需真机二次确认

1. **res 2131761210 的字面值**：按 `gc` 兜底构建器与 `K7` 的 `do transmit` 分支推断为"转发给朋友"；正式版请以 `resources.arsc` 或运行时 `getString()` 为准。
2. **res 2131755018 的字面值**：`H7()` 中语音计数 `i4>0` 时的 Toast，推断同为"收藏的语音消息不能转发"（与 `mc.g` 的 2131761212 同义不同 id）。
3. **`SelectConversationUI` 无 fav id 直接启动**的确认弹窗行为（§6）——若不正常，改用 Hook `SelectConversationUI#W7(String)`。
4. **`v61.o(fileName, len)` 第二参语义**：`cd#onMMMenuItemSelected` 传 `1`；`doScene` 内部主要读 `v61.c1.l`。按 7.4 传入时长毫秒，若异常则传 1。
5. **`v61.d1.h()` 的 md5 入参**：需与 `v61.d1.b1.c(fileName)`（对源文件取 md5）保持一致，否则语音记录与文件对应关系错乱。
6. **收藏语音加密/流式格式**（`RepairerConfigFavWxamSwitch` / `RepairerConfigFavStreamPlaySwitch`）可能在部分版本改变落盘格式，需实测 `s2.y()` 返回的文件能否直接上传。

---
---

# 附录 A：收藏语音数据获取完整链路（调用序）

> 目标：**不依赖 UI**，直接从收藏 DB + CDN 状态拿到语音的本地文件路径 / 元数据 / 下载触发。

## A.1 调用序

```
① 定位收藏服务（ph5.n0 是服务定位器）
   Object favPlugin = XposedHelpers.callStaticMethod(ph5.n0.class, "c", tc2.h6.class);   // ((h6) n0.c(h6.class))

② 取收藏条目 DB
   Object favDb = XposedHelpers.callMethod(favPlugin, "sj");        // tc2.h6#sj() → FavItemInfoDB
   Object item  = XposedHelpers.callMethod(favDb, "H", localId);    // H(long) → tc2.m3（收藏项）
   // 没有 localId 时：先遍历收藏列表（FavoriteIndexUI.W.i(pos)）或 vj(type)/sql 取

③ 取 proto 与数据项
   Object proto = XposedHelpers.getObjectField(item, "field_favProto");       // pc5.mr0（字段名未混淆！）
   List<?> dataItems = (List<?>) XposedHelpers.getObjectField(proto, "f");    // List<pc5.rq0>
   Object d = dataItems.get(0);                                              // 语音只有一项
   // 等价工具：tc2.s2#K(item) = field_favProto.f.get(0)

④ 本地路径（核心，tc2.s2#y(rq0) 逻辑复刻）
   root = tc2.s2#D()                      // <uinPath>/favorite（RepairerConfigFavRootSwitch 可切 t7.b("favorite")）
   dir  = tc2.s2#s(rq0.T)                 // root/(rq0.T.hashCode() & 255)/
   name = rq0.T (+ "." + rq0.K)           // K = "silk"/"speex"/"amr"
   if (!exist) name = md5(rq0.M + rq0.T) + "." + rq0.K      // M = cdn md5，回退
   if (rq0.I == 8 || rq0.I == 10130) name = rq0.d           // 大文件场景
   path = dir + "/" + name

⑤ 文件不存在 → 触发下载
   tc2.s2#F0(item, true)                  // 仅当 field_itemStatus ∈ {8,10}；置 7，遍历 f 调 B0()(CDN数据)/D0()(缩略图)
   下载引擎驱动：favPlugin.mj().M7()
   CDN 记录：favPlugin.nj().Wg(rq0.T)（字段 field_status/field_cdnUrl/field_cdnKey/field_dataId/field_path）

⑥ 元数据
   时长(ms) = rq0.y                       // 秒 = (int) tc2.s2#Z(rq0.y)（round(ms/1000)，最小 1）
   格式     = tc2.s2#d0(rq0.K)            // speex→1, silk→2, 其他→0
   dataId   = rq0.T；cdnUrl = rq0.s；cdnKey = rq0.u；md5 = rq0.M
   类型     = ((im.o3) item).field_type == 3
   下载状态 = ((im.o3) item).field_itemStatus（8=失败, 10=完成...）
```

## A.2 关键字段表

| 类 | 字段 | 含义 |
|---|---|---|
| `im.o3`（收藏条目实体，**字段名未混淆**） | `field_localId`(J) | 本地 ID，转发 intent 用它 |
| | `field_id`(I) | 服务端 favId，`>0` 才能转发（`de2.j.a()`） |
| | `field_type`(I) | 3 = 语音；14=记录；18=笔记；19=小程序… |
| | `field_itemStatus`(I) | 下载状态（8 失败 / 10 完成；`F0()` 会置 7） |
| | `field_favProto`(pc5.mr0) | proto 根 |
| | `field_datatotalsize`(J)、`field_xml`(String)、`field_fromUser`、`field_toUser`、`field_tagProto` | 大小/XML/来源 |
| `pc5.mr0`（FavProto） | `f`(List) | 数据项列表 |
| | `s`(String) | 文本内容；`m`=链接；`h`=位置；`I`=小程序；`P`/`d`/`q`/`y` | |
| `pc5.rq0`（数据项，混淆） | `T` | dataId / 本地文件名（不含扩展名） |
| | `K` | 扩展名 silk/speex/amr |
| | `y`(J) | 时长（毫秒） |
| | `M` | cdn md5（本地路径回退用） |
| | `s` / `u` | cdnUrl / cdnKey |
| | `h2`(I) | 数据项状态（1=已过期 2=大小超限） |
| | `d` | 大文件名（I==8/10130 时启用） |
| `im.k3`（CDN 记录实体，**字段名未混淆**） | `field_status`/`field_cdnUrl`/`field_cdnKey`/`field_dataId`/`field_path`/`field_type` | CDN 下载任务状态 |

> **这是本方案最重要的可移植性发现**：微信对 **storage/DB 实体类保留原始字段名**（`field_favProto`、`field_cdnUrl`、`field_itemStatus`…），而普通逻辑类名/方法名全混淆。→ 跨版本定位优先用字段名，见附录 B。

---
---

# 附录 B：微信升级后的 DexKit 适配方案（独立 Xposed 模块）

## B.1 锚点优先级（由稳到易变）

| 级别 | 锚点 | 说明 |
|---|---|---|
| ★★★★★ | **系统接口 / 系统类** | `android.view.View$OnCreateContextMenuListener`、`android.widget.AdapterView$OnItemLongClickListener`、`android.view.MenuItem` —— 永不变，微信混淆不了 |
| ★★★★★ | **DB 实体字段名** | `field_favProto`/`field_cdnUrl`/`field_itemStatus`/`field_localId` —— 微信不混淆 storage 字段 |
| ★★★★ | **字符串常量** | 日志 TAG 与文案：`[FAV_ITEM_TYPE_VOICE] canFilterVoice = true, back`、`startRecord insert voicestg success`、`do transmit, long click info is %s` |
| ★★★★ | **资源 ID** | `2131761210`(转发给朋友) 等，微信资源 ID 跨版本基本稳定（但文案可能换） |
| ★★★ | 方法签名形态 | `(IIILjava/lang/CharSequence;I)Landroid/view/MenuItem;`、`(Ljava/lang/String;I)V`(NetSceneUploadVoice 构造) |
| ★★ | 类名 / 包名 | `de2.m`/`com.tencent.mm.plugin.fav.ui.*` —— 每次大版本都会变，**不要硬编码** |

## B.2 目标 → DexKit 查询对照表

| 目标 | 主查询 | 备用查询 |
|---|---|---|
| 收藏实体类（`im.o3`） | `findField{ name("field_favProto") }` → `field.declaringClass` | `findField{ name("field_itemStatus") }` |
| CDN 实体（`im.k3`） | `findField{ name("field_cdnUrl") }` → declaringClass | `findField{ name("field_dataId") }` |
| FavApiLogic（`tc2.s2`，拿 `y/K/Z/d0/F0/D`） | `findMethod{ usingStrings("restart cdndata download") }` 同类；再 `findMethod{ declaredClass(=该类) }` 挑 `static String (rq0)` | `findMethod{ usingStrings("getFavRoot, favRootSwitch:") }` |
| FavSendFilter（`tc2.x3`，语音过滤） | `findMethod{ usingStrings("[FAV_ITEM_TYPE_VOICE] canFilterVoice = true, back") }` | `findClass{ usingStrings("MicroMsg.FavSendFilter") }` |
| 长按菜单构建器（`de2.m`） | `findMethod{ usingStrings("fav_page_card_operation") }` 所在类中，参数 `(kj5.i4, View, ContextMenu$ContextMenuInfo)` 的方法 | `findClass{ from().clazz().usingStrings("fav_page_card_operation") }` 后取唯一 void(i4,View,Info) |
| 长按菜单分发（`de2.n` / `hc`） | 同上类中含 `onMMMenuItemSelected` 的方法 | `findMethod{ usingStrings("openFavDebugUI") }` |
| 转发分发 `K7` | `findMethod{ usingStrings("do transmit, long click info is %s") }` | `findMethod{ usingStrings("do tag, long click info is %s") }`（同一方法） |
| 单条转发 `mc.g` | `findMethod{ usingStrings("[shareFavToFriRequest] select first is FAV_ITEM_TYPE_VOICE") }` | `findMethod{ usingStrings("fav_trans_send,") }` |
| 收藏列表 UI | `findClass{ usingStrings("after filter, nothing"); usingStrings("do transmit, long click info is %s") }` | `findClass{ superClass(clz) }` 链 |
| NetSceneUploadVoice（`v61.o`） | `findMethod{ usingStrings("doScene:  filename null!") }` → declaredClass | 构造器 `paramTypes("java.lang.String","int")` |
| VoiceLogic（`v61.d1`，建语音消息/生成文件名） | `findMethod{ usingStrings("startRecord insert voicestg success") }` → declaredClass；再 `findMethod{ declaredClass(=它); paramTypes("String","String","int") }` = `d1.s` | `findMethod{ usingStrings("doScene: fileOp is null") }` |
| MsgInfo（`e9`） | `findField{ name("field_type") }` 结合 `returnType("I")` 与 `setType` 形态 | 由 `d1.u` 方法体 `check-cast`/`new-instance` 反查 |

## B.3 DexKit 代码骨架（Kotlin，独立模块）

```kotlin
object WXResolver {
    private var cache: JSONObject? = null          // 版本号 → 解析结果，落盘缓存

    fun resolve(apkPath: String, verCode: Long) {
        cache?.takeIf { it.optLong("verCode") == verCode }?.let { return }
        val bridge = DexKitBridge.create(apkPath)          // 新版本 DexKit 需 apk 路径；旧版 DexKitBridge.create(classLoader)
        val map = HashMap<String, Any>()

        // ① 用字段名反查实体类（最稳）
        bridge.findField { matcher { name("field_favProto") } }.forEach {
            map["FavItemInfo"] = it.declaringClassName
        }

        // ② 用字符串定位方法
        bridge.findMethod {
            searchIn { from().clazz().usingStrings("[FAV_ITEM_TYPE_VOICE] canFilterVoice = true, back") }
            matcher  { usingStrings("[FAV_ITEM_TYPE_VOICE] canFilterVoice = true, back") }
        }.forEach { map["FavFilter"] = it }        // it.declaringClassName / it.name / it.paramTypes

        bridge.findMethod { matcher { usingStrings("do transmit, long click info is %s") } }
            .forEach { map["K7"] = it }
        bridge.findMethod { matcher { usingStrings("[shareFavToFriRequest] select first is FAV_ITEM_TYPE_VOICE") } }
            .forEach { map["McG"] = it }
        bridge.findMethod { matcher { usingStrings("startRecord insert voicestg success") } }
            .forEach { map["VoiceLogic"] = it.declaringClassName }
        bridge.findMethod { matcher { usingStrings("fav_page_card_operation") } }
            .forEach { map["MenuOwner"] = it.declaringClassName }   // 再用签名过滤出 a(i4,View,Info)
        bridge.close()
        cache = JSONObject(map).put("verCode", verCode)
    }
}
```
> 注：DexKit 1.x 的 DSL（`findClass{ matcher{ usingStrings() } }`）与老版 `batchFindClassUsingStrings` 两种写法都存在，按你依赖的版本选择；字段/方法结果里都带 `declaringClassName/name/paramTypes/returnType`，可直接喂给 `XposedHelpers.findAndHookMethod`。

## B.4 抗更新的三层兜底（不依赖任何微信类名）

1. **系统接口层（最强）**：Hook `android.view.View$OnCreateContextMenuListener#onCreateContextMenu(Menu, View, ContextMenuInfo)` 的**全部实现**。
   - 判断是否收藏场景：`anchor.getTag()` / `anchor.getContext()` 的类名含 `com.tencent.mm.plugin.fav`，或 `anchor` 上能取到带 `field_favProto` 字段的对象。
   - 注入：`menu.add(0, 10086, 0, "语音转发")`。（`eu5.s0#p()` 里就是回调 `onCreateContextMenuListener.onCreateContextMenu(...)`，覆盖所有弹菜单路径。）
2. **长按入口层**：Hook `android.widget.AdapterView$OnItemLongClickListener#onItemLongClick` 的全部实现，仅在宿主含 `favoriteLV`/收藏 Adapter 时放行（用于自己判断是否语音、决定是否接管）。
3. **微信业务层**：按 B.2 的 DexKit 结果 Hook `de2.n`/`hc`/`K7`。三层任一命中即可工作。

## B.5 兼容性工程建议

- **结果缓存**：`SharedPreferences/MMKV` 按 `versionCode + versionName` 存解析结果，二次启动秒加载；解析失败再全量扫（DexKit 全量扫较慢，建议放子线程 + 进度提示）。
- **软失败**：所有 Hook 用 `findClassIfExists` + try/catch；任一环节失败就**不注入**并 `Log.w`，绝不让微信崩溃。
- **多版本分支**：若某版本 `field_favProto` 也被混淆，退到「字符串锚点」→ 再退到「系统接口层」。
- **自检 Hook**：`handleHookedMethod` 里打点，出现连续 miss 即上报（便于你及时更新 DexKit 查询）。
- **菜单标题**：别硬编码中文资源 id，直接 `menu.add(0, 10086, 0, "语音转发")`（塞字符串常量最省事）。
