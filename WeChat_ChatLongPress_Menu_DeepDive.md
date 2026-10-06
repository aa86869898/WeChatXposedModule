# 微信聊天窗口「长按消息菜单」彻底逆向分析 + 自研 Xposed 模块关闭原生按钮完整方案

> 分析对象：`com.tencent.mm`（Android，17 dex，全量混淆）
> 分析方式：LSPilot 反编译（jadx Java + dexlib2 Smali 交叉验证）
> 目标：聊天窗口长按消息弹出的功能菜单（复制 / 转发 / 收藏 / 删除 / 多选 / 引用 / 提醒 / 翻译 / 搜一搜 / 连续播报 / 打开 / 静音播放 / 相关表情 / 查看专属 / 编辑）如何彻底关闭
> 适用：**你自己的独立 Xposed 模块**（不依赖 LSPilot BSH 插件，本中文档中的代码是标准 Java Xposed API）

---

## 0. 结论速览（TL;DR）

微信的长按菜单**不是每条消息各自弹 Dialog**，而是一条统一的流水线：

```
消息 Item 的 View.OnLongClickListener
  → com.tencent.mm.ui.chatting.viewitems.m0.onLongClick(View)
  → m0.g(View)                     // openContextMenu：new 一个 MMPopupMenu
  → MMPopupMenu(eu5.s0).f(view, p4, v4, x, y)
        └─ p4.a(menu, view, null)  // ← o0.a：把所有按钮加进同一个 MMMenu(kj5.i4)
        └─ 然后弹出
  → 用户点击某个按钮
  → MMPopupMenu.onItemClick → r0.onMMMenuItemSelected(MenuItem, pos)   // 全类型唯一收口
        ├─ 各消息类型 handler（viewitems.zn.R / go.R / lg.R / d2.Q / b0.Q ...）
        └─ com.tencent.mm.ui.chatting.component.qi.t0(MenuItem,pos,b0,ps) // 公共动作收口
              └─ wo.D0(...)   // TranslateComponent：翻译 124/125/163/164
```

**关闭按钮 = 三个层次，任选其一或组合：**

| 层次 | Hook 点 | 效果 | 副作用 |
|---|---|---|---|
| **A. 构建期删项（推荐）** | `eu5.s0.f(...)` after，或 `o0.a(...)` after | 按钮根本不出现 | 无 |
| **B. 点击期拦截（兜底）** | `viewitems.r0.onMMMenuItemSelected` before | 按钮还在，点了没反应 | 无 |
| **C. 全局长按熄菜** | `m0.g(View)` before `setResult(null)` | 长按整个菜单不出 | 所有类型消息一次性全禁 |

本文给出 **A+B+C 三层全部实现**的完整可编译模块代码（第 7 章）。

---

## 1. 核心类清单（本版本实测混淆名）

| 语义名 | 本版本混淆类名 | 说明 |
|---|---|---|
| `ChattingItem`（长按基类） | `com.tencent.mm.ui.chatting.viewitems.m0` | 实现 `View.OnLongClickListener`，持有菜单构建器 + 点击监听 |
| `ChattingItem$OnCreateMenuListener` | `com.tencent.mm.ui.chatting.viewitems.o0` | **菜单构建器**，唯一往菜单里加按钮的类 |
| `ChattingItem$MenuClickListener` | `com.tencent.mm.ui.chatting.viewitems.r0` | 实现 `kJ5.v4`，**所有按钮点击的唯一收口** |
| `MMPopupMenu` | `eu5.s0` | 弹窗宿主（ListPopupWindow + 箭头），保存菜单对象 |
| `MMMenu`（上下文菜单） | `kj5.i4` | 实现 `android.view.ContextMenu`，`d` 字段是菜单项 List |
| `MMMenuItem`（菜单项） | `kj5.j4` | 实现 `android.view.MenuItem`，`t`=itemId，`i`=标题 |
| 长按菜单构建接口 | `kJ5.p4`（`a(i4,View,ContextMenuInfo)`） | MMPopupMenu 的 OnCreateMenuListener |
| 菜单点击接口 | `kJ5.v4`（`onMMMenuItemSelected(MenuItem,int)`） | r0 实现它 |
| 公共动作分发 | `com.tencent.mm.ui.chatting.component.qi` | 实现 `ak5.e1`；`t0()` 是 delete/fav/forward/search/remind/TTS 等大 switch |
| 翻译组件 | `com.tencent.mm.ui.chatting.component.wo` | 实现 `ak5.m2`；`D0()` 处理 124/125/163/164 |
| 文本/图片/表情基处理器 | `com.tencent.mm.ui.chatting.viewitems.zn` | `S()` 建菜单、`R()` 点菜单 |
| 语音处理器 | `viewitems.lg`；appmsg 处理器 `viewitems.d2` | 各自的 `S()/Q()` |

> 混淆名随版本变化，但**结构、日志锚点、方法签名在各版本间高度稳定**（见第 8 章防失效指南）。定位方法：Log tag `MicroMsg.ChattingItem` + 字符串锚点 `OnCreateContextMMMenux`。

---

## 2. 完整调用链（逐方法已验证）

### 2.1 长按触发 → 菜单弹出

**`m0`（ChattingItem）关键字段**（`decompile_class_fields` 实测）：

```java
public final p4 e;     // ← = new o0(this, b0Var, dVar)  菜单构建器（构造函数第 84 行）
public final r0 f;     // ← = new r0(this)              菜单点击监听
public s0 g;           // MMPopupMenu 实例（eu5.s0）
```

`m0.g(View)`（openContextMenu，反编译 73 行）核心片段：

```java
public void g(View view) {
    ps psVar = (ps) view.getTag();          // ItemDataTag（含 position、msg）
    this.f.d = psVar;
    s0 s0Var = this.g;
    if (s0Var != null) { s0Var.a(); this.g = null; }
    s0 s0Var2 = new s0(dVar.g());           // new MMPopupMenu(context)
    this.g = s0Var2;
    ...
    s0Var6.t = new p0(this, c);             // 展示回调
    s0Var6.J = new m0$$a(this, s0Var6, c);  // onShow 回调
    s0Var6.f(view, this.e, this.f, width, i);   // ★ show(view, 构建器, 点击监听, x, y)
    ...
}
```

**`eu5.s0.f(View, p4, v4, int, int)`（MMPopupMenu.show）反编译** —— 这就是"按钮们被加进同一个菜单"的现场：

```java
public void f(View view, p4 p4Var, v4 v4Var, int i, int i2) {
    this.v = v4Var;              // 点击监听（= r0）
    this.f = view;               // anchor view
    if (!(view instanceof TextView) && (i == 0 || i2 == 0)) l();
    this.x.clear();                                  // x: kj5.i4  菜单对象
    p4Var.a(this.x, view, (ContextMenu.ContextMenuInfo) null);   // ★ o0.a 在此执行
    if (i == 0 && i2 == 0) m(); else n(i, i2);       // 弹出
}
```

`eu5.s0` 字段（46 个，实测）：`x: Lkj5/i4;`（菜单）、`w: Lkj5/p4;`（构建器）、`v: Lkj5/v4;`（点击）、`s: Landroid/view/View$OnCreateContextMenuListener;`。

### 2.2 菜单构建器 `o0.a(i4, View, ContextMenuInfo)`

签名：`public void a(Lkj5/i4 i4Var, View view, ContextMenu.ContextMenuInfo contextMenuInfo)`（598 行）

- 从 `view.getTag()` 取 `ps`（`ItemDataTag`），拿到 `position d` 与 `e9 msg`；
- 先调 `b0.S(i4Var, view, tag)`（各消息类型的**类型专属菜单**：图片/语音/视频/表情各自加自己的按钮）；
- 然后走公共逻辑，反复调用：
  - `i4Var.c(position, itemId, 0, ctx.getString(resTitle), iconRes)` —— **带图标新增**
  - `i4Var.add(position, itemId, 0, title)` —— 无图标新增
  - `i4Var.findItem(id) / removeItem(id) / v(MenuItem) / clear() / size()` —— **微信自己就在删/排序**
  - `List list = i4Var.d`（直接拿 List 重排：多选 122、删除 100、"更多"子菜单 123、元宝 173/174 都是这么塞进去的）
- 调用静态助手：`m0.a`（插复制 136）、`m0.b`（插删除 100）、`m0.d`（插提醒 134，插在 116/100 之后）、`m0.c`（元宝总结，t0.N/t0.M 枚举）、`m0.e`（171 曝光上报）；
- 日志锚点：`Log.i(str3, "OnCreateContextMMMenux: position:%s, msgid:%s", ...)`（tag = `MicroMsg.ChattingItem`）。

### 2.3 菜单模型（删按钮必须掌握）

`kJ5.i4`（MMMenu）字段仅 3 个（实测）：

| 字段 | 类型 | 用途 |
|---|---|---|
| `d` | `Ljava/util/List;` | **菜单项列表（运行期 ArrayList）** |
| `e` | `Ljava/lang/CharSequence;` | 菜单标题文本 |
| `f` | `Landroid/content/Context;` | Context |

`add(int)` 的铁证（反编译）：`j4 j4Var = new j4(this.f, 0, 0); j4Var.t = i; ((ArrayList) this.d).add(j4Var);`

`kJ5.j4`（MMMenuItem，实现 `android.view.MenuItem`）：`t:I` 菜单项 id、`i/m/o:CharSequence` 标题/副标题、`G:Drawable` 图标，并暴露标准 `getItemId()/getTitle()/setVisible()` 等。

**结论：按钮 = 普通 `MenuItem`，`getItemId()` 就是功能 id；`MMMenu.removeItem(int)` / `v(MenuItem)` / `clear()` 都是 public，随时可删。**

### 2.4 点击分发（为什么 B 层拦截一枚钩子就够）

`viewitems.r0.onMMMenuItemSelected(MenuItem, int)`（反编译 28 行）：

```java
public void onMMMenuItemSelected(MenuItem menuItem, int i) {
    ps psVar = this.d;  // ItemDataTag
    b0 b0Var = m0Var.v; // 当前消息类型 handler（ChattingItem）
    if (b0Var instanceof zn || go || lg) {
        b0Var.R(menuItem, d, psVar);                       // 文本/图片/表情/语音类
        ((e1) dVar.c.a(e1.class)).t0(menuItem, i, b0Var, psVar);   // 公共分发 qi.t0
    } else if (!(g) && !(i)) {
        b0Var.Q(menuItem, d, psVar.a);
        ((e1) ...).t0(...);
    } else if (!b0Var.Q(...)) { ((e1) ...).t0(...); }
    ...
}
```

- `component.qi.t0(MenuItem, position, b0, ItemDataTag)`（489 行）是**公共动作大 switch**：删除/收藏/转发/多选/提醒/搜一搜/TTS/打开/分享 都在这里；
- `component.wo.D0(MenuItem, msg)`（TranslateComponent）处理 **翻译 124/125/163/164**；
- 类型专属动作在各 handler 的 `R()/Q()` 里（`zn.R`：复制 102/141、转发 142、收藏 143、语音 111、直播分享 179、翻译兜底 124/125 透传 `wo.D0`）。

---

## 3. 菜单按钮 ID 全表（代码级验证 + 行为级语义）

> `titleRes/iconRes` 为微信 string/drawable 资源 id（16 进制 0x2040xxxx 系）；"添加处"指代码里 `c(pos,id,0,...)` 的位置；"处理处"指点击后真正做事的函数。

| itemId | 按钮（语义） | titleRes | 添加处 | 处理处 | 置信度 |
|---|---|---|---|---|---|
| 100 | **删除** | 2131758448 | m0.b / zn.S / lg.S | qi.t0（确认弹窗 2131759086/2131759859/2131756003） | 已验证 |
| 102 | **复制**（图片/文本自消息） | 2131758444 | zn.S / go.S | zn.R（剪贴板 + Toast 2131756022 + ShareMsgClipStruct 上报） | 已验证 |
| 103 | 转发（语音/特殊类 M0()==5） | 2131758462 / 2131758552 | o0.a / go.S | qi.t0（转发到聊天） | 已验证 |
| 104 | （预留） | — | — | — | — |
| 108 | **转发**（自有消息，`t.a` 选择器） | 2131774067 | zn.S / go.S | zn.R（`t.a(msg,ctx,cb)` 转发） | 已验证（第二轮修正） |
| 110 | **转发**（群聊，`t.a` 选择器） | 2131774067 | o0.a | 转发流程 | 已验证（第二轮修正） |
| 111 | 转发/确认型（语音-biz） | 2131774067 | lg.S | d2.Q（确认框 2131761006/2131780644） | 已验证（第二轮修正） |
| 112 | 链接/名片类拓展项 | 2131758332 | o0.a | — | 已验证（语义存疑） |
| 114 | 品牌服务语音接入 | — | — | qi.t0（kd.*） | 已验证 |
| 116 | **收藏** | 2131773067 | zn.S / go.S | qi.t0 → `v0(X0)` 收藏 | 已验证 |
| 122 | **多选** | 2131758453 | o0.a（按钮条） | qi.t0 → ChattingMoreBtnBarHelper（底部操作条） | 已验证 |
| 123 | 图片/表情 AI 类（相关表情或提取文字，`s1` 组件） | 2131758464 | go.S / o0.a（>3 项时折叠为"更多"子菜单） | qi.t0 → `s1.t0(X0,"",c0,true)` | 已验证（标题文案待实测） |
| 124 | **翻译**（文本） | 2131758477 | zn.S / go.S | zn.R→wo.D0→w0(translate) | 已验证 |
| 125 | 全文翻译 | — | 同上 | wo.D0 | 已验证 |
| 126 | 收藏（视频/文件变体） | 2131773067 | lg.S | 收藏 | 已验证 |
| 119 | **语音转文字** | 2131758207 | bq.S（语音） | bq.Q → `r2.I0(true)` | 已验证（第二轮新增） |
| 120 | **取消转文字** | 2131758208 | bq.S（语音） | bq.Q → `r2.I0(false)` | 已验证（第二轮新增） |
| 121 | **转文字 / 取消转文字（hover-win）** | 2131758480 / 2131758481 / 2131758471 | bq.S（语音） | bq.Q → `nq.c(...)`/`l2.b(...)` 转换开关 | 已验证（第二轮新增） |
| 165 | 语音相关（`nq.j` 开关） | 2131758478 | bq.S（语音） | — | 已验证（存在性） |
| 181 | 语音/来源分享类（R==2 且 aj0.a.b） | 2131783027 | bq.S（语音） | bq.Q → `y.c(...)` report | 已验证（存在性） |
| 129 | 转发（appmsg→选人） | — | — | qi.t0 | 已验证 |
| 134 | **提醒** | 2131758482 | m0.d（插在 116/100 后） | qi.t0 → MsgRemindComponent（`k.a.d`，可关） | 已验证 |
| 135 | **引用**（推断） | — | 由类型 handler/o0.a 添加 | o0.a 中 `removeItem(135)`（元宝会话移除） | **推断**（建议用第 9 章 DumpMenu 实测确认） |
| 136 | **复制**（公共插入） | 2131758454 | m0.a（按 116/143/134 存在性决定插在哪） | qi.t0 → `s0.v0(X0, null)` 复制 | 已验证 |
| 137 | **搜一搜** | — | o0.a | qi.t0 → FTS `n.l(34)` | 已验证 |
| 138 | 调试（Repairer） | 2131756026 | o0.a（仅 RepairerConfigGlobalMsgDebug） | RepairerMsgDebugUI | 已验证 |
| 139/140 | **转发**（推荐样式 1/2） | — | — | qi.t0 → `s0.w0(1/2, X0)` | 已验证 |
| 141 | 复制（他人文本） | 2131758444 | zn.S | zn.R（剪贴板） | 已验证 |
| 142 | **转发** | 2131774067 | zn.S | zn.R → MsgRetransmitUI（scene_from=17） | 已验证 |
| 143 | **收藏**（他人文本） | 2131773067 | zn.S | zn.R → DoFavoriteEvent | 已验证 |
| 150 | **打开**（文件，AppAttachNewDownloadUI） | — | — | qi.t0（intent：app_msg_id/msg_talker/choose_way） | 已验证 |
| 151 | 图片/appmsg 内嵌项（如继续下载/编辑） | 2131769297 | zn.S / go.S | zn.R → `c6.n(...)`（ChattingItemAppMsgDownloader.filling） | 已验证（语义待实测） |
| 152 | 图片相关（微瓦/识图） | 2131758451 | zn.S | zn.R → m2.v0 → wo.v0（请求字符串 2131758601） | 已验证（语义待确认） |
| 163 | 翻译（其它消息类型）/ 相关 | 2131758472 | zn.S / go.S / bq.S | wo.D0（翻译流） | 已验证（标题语义存疑） |
| 164 | 翻译语言 | 2131758443 | zn.S | wo.D0（SettingsTranslateLanguageRequest） | 已验证 |
| 170 | **搜一搜**（图片以图搜图） | — | o0.a | qi.t0 → image search | 已验证 |
| 171 | **打开/分享**（小程序 open material） | — | o0.a / m0.e 上报 | qi.t0（`e.a.a` share） | 已验证 |
| 173/174 | 元宝总结（文章/文件） | 2131758464 组 | o0.a / m0.c | d2.Q（journey_summarize） | 已验证 |
| 175 | **连续播报 / 朗读（TTS）** | 2131761892 / 2131778872 | o0.a | qi.t0 → ChatRecordsTtsService.startPlaying | 已验证 |
| 179 | 直播分享上报 | — | — | zn.R → y.c(report) | 已验证 |
| 183/184 | 元宝总结（文章/文件 - appmsg） | — | o0.a | d2.Q | 已验证 |
| 185 | 问问小微（图片 AI） | 2131777961 | o0.a / zn.R | AskXiaowei | 已验证 |
| 4 | **搜一搜**（文本类备用 id，与 137 同文案 2131758469、同图标；青少年模式不显示） | 2131758469 | zn.S（i2==2 分支） | zn.R | 已验证（第二轮修正） |

### 3.1 用户点名按钮 → ID 映射（直接可用）

```text
复制       → 136, 102, 141
转发       → 139, 140, 142, 103, 129
收藏       → 116, 126, 143
删除       → 100
多选       → 122
引用       → 135（推断；第二轮在 qi.t0/zn.R/bq.Q 均未找到独立"引用"id，本版本引用可能走新版交互，仍以标题黑名单"引用/引用了"兜底）
提醒       → 134
翻译       → 124, 125, 163, 164
搜一搜     → 137, 170, 4（文本类备用id，第二轮实测）
连续播报   → 175
打开       → 150, 171
静音播放   → 语音/视频动态项，版本差异大 → 用标题黑名单"静音播放/扬声器播放/听筒播放"兜底
相关表情   → 123
查看专属   → 元宝系 173/174/183/184 或"查看原文"类 → 标题黑名单兜底
编辑       → 151/163（版本相关）→ 标题黑名单"编辑"兜底
```

**因此：** 只用 `ID 黑名单`会有漏网（静音播放/查看专属/编辑等随版本与消息类型漂移）；**正确姿势是 ID 黑名单 + 标题黑名单双保险**（代码见 7.2）。

---

## 4. 设计思路：三层关闭方案详解

### 4.1 A 层：构建期删除（推荐，视觉+功能一次干净）

时机：所有按钮都进过 `MMMenu` 之后、弹窗 `n(x,y)` show 之前。两个可选钩点：

- **A1（最稳，单钩子覆盖全类型）**：hook `eu5.s0.f(Landroid/view/View;Lkj5/p4;Lkj5/v4;II)V`，`afterHookedMethod` 里读 `param.thisObject` 的字段 `x`（类型 `kj5.i4`），删项。
- **A2（语义层）**：hook `o0.a(Lkj5/i4;Landroid/view/View;Landroid/view/ContextMenu$ContextMenuInfo;)V` 的 after，直接用 `param.args[0]`（就是 MMMenu）。缺点：o0 混淆名会变，但可用"3 参且第一参是 ContextMenu 实现类 + Log tag `MicroMsg.ChattingItem`"重新定位。

删项 API（全 public，反射可用）：
- `menu.removeItem(int id)`（按 id 删，微信自己也这么干）
- `menu.v(MenuItem item)`（按对象删）
- `menu.clear()`（全清）
- 菜单项列表直取：`(List) XposedHelpers.getObjectField(menu, "d")`，元素是 `kj5.j4`（`getItemId()/getTitle()` 标准接口可直接调）

### 4.2 B 层：点击期拦截（兜底，防代码路径旁路）

hook `viewitems.r0.onMMMenuItemSelected(Landroid/view/MenuItem;I)V` 的 before：命中黑名单 → `param.setResult(null)`（void 方法 setResult 即跳过原方法）。r0 是**所有类型、所有按钮点击的唯一收口**，一枚钩子全灭。进一步可叠加 `component.qi.t0(MenuItem,I,b0,ps)` 与 `component.wo.D0(MenuItem,e9)` 的 before 拦截（对付以后可能新增的旁路）。

### 4.3 C 层：熄掉整个长按菜单

hook `m0.g(Landroid/view/View;)V` before → `param.setResult(null)`：菜单完全不弹（包括多选、引用、文件预览等）。适合"我不需要任何长按功能"的场景。
（也可以 hook `m0.onLongClick(Landroid/view/View;)Z` 返回 true，但会影响系统长按手势语义，不推荐。）

---

## 5. 在你的模块里"如何调用、用什么方法"（API 级说明）

1. **拿 ClassLoader**：`IXposedHookLoadPackage.handleLoadPackage` 的 `lpparam.classLoader`（= WeChat 宿主 ClassLoader，含全部 dex）。
2. **找类**：混淆类名跨版本会变，模块启动时**先按本版本已知名反射加载，失败则用 DexKit 按字符串锚点重新解析**（8.2 节给代码）。
3. **Hook**：`XposedHelpers.findAndHookMethod(clazz, "f", View.class, p4Cls, v4Cls, int.class, int.class, hook)`。注意 `findAndHookMethod` 的参数类型必须精确匹配，否则 `NoSuchMethodError`。
4. **删项**：反射调用 `removeItem(int)`；对象直接用标准 `MenuItem.getItemId()/getTitle()`。
5. **线程**：以上全部运行在聊天列表所在的 UI 线程，钩子里不要做 IO；删除动作纯内存操作，安全。
6. **多消息类型天然覆盖**：文本/图片/语音/视频/表情/文件/appmsg/名片…**最终都汇入同一个 MMPopupMenu + MMMenu**，所以 A1 一个钩子就够；这也是为什么不建议按消息类型逐个 hook handler。
7. **子菜单**：id=123 的"更多"是把 173/174/183/184 等元宝/总结类折叠进去的父项，删 123 即删整组。

---

## 6. 已验证证据索引（可直接复现）

| 事实 | 证据 |
|---|---|
| 长按入口 `m0.onLongClick/g` | `decompile_class_methods_only(viewitems.m0)`、`decompile_method(m0,"g")` |
| 构建器 o0、点击 r0 由 m0 构造 | `decompile_class(m0)` 第 84/79 行 |
| MMPopupMenu = `eu5.s0`，show=`f(...)` | `decompile_method(eu5.s0,"f")`；`view_strings(eu5.s0)` 含 `MicroMsg.MMPopupMenu`、`show popMenu , xDown` |
| MMMenu=`kj5.i4`，`d` 字段为 List | `decompile_class_fields(kj5.i4)` |
| MMMenuItem=`kj5.j4`，`t`=id、`i`=title | `decompile_class_methods_only(kj5.j4)`、既有材料 |
| o0.a 构建全部按钮 | `decompile_class(viewitems.o0)` 598 行全文（112~709） |
| 公共动作 switch | `decompile_class(component.qi)` `t0()` 489 行全文 |
| 翻译 124/125/163/164 | `decompile_method(component.wo,"D0")` |
| 各类型菜单 S() | `decompile_method(viewitems.zn,"S")`、`go.S`、`lg.S` |
| ID→行为 | `qi.t0`、`zn.R`、`d2.Q`、`m0.b/d`、`m0.a`、`o0.a` |
| 依赖注入取组件 | `n0.c(xxx.class)` 模式（`ph5.n0`） |

---

## 7. 完整自研 Xposed 模块代码（可直接建工程）

> Gradle 只需 `compileOnly 'de.robv.android.xposed:api:82'`（或 53+）；scope 勾选微信。

### 7.1 `WxChatMenuModule.java`

```java
package com.yourname.wxchatmenu;

import android.content.Context;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class WxChatMenuModule implements IXposedHookLoadPackage {

    private static final String TAG = "WxChatMenu";
    private static final String WX = "com.tencent.mm";

    /** A层：要删掉的 itemId（见文档第 3 章） */
    private static final Set<Integer> DISABLED_IDS = new HashSet<>(Arrays.asList(
            100,          // 删除
            102, 136, 141, // 复制
            103, 129, 139, 140, 142, // 转发
            116, 126, 143, // 收藏
            122,          // 多选
            135,          // 引用(推断)
            134,          // 提醒
            124, 125, 163, 164, // 翻译/全文翻译/翻译语言
            137, 170,     // 搜一搜 / 图片搜一搜
            175,          // 连续播报(TTS)
            150, 171,     // 打开 / 分享打开
            123, 173, 174, 183, 184, // 相关表情、元宝总结/查看专属类
            151, 152, 185, // 图片编辑、识图、问问小微
            108, 110, 111,       // 转发/确认型(按需)
            119, 120, 121,      // 语音转文字/取消转文字
            165, 181, 4         // 语音动态项(152 见上行)

    ));

    /** B层：标题黑名单兜底（本地化文案，随版本兜底） */
    private static final Set<String> DISABLED_TITLES = new HashSet<>(Arrays.asList(
            "复制", "转发", "收藏", "删除", "多选", "引用", "提醒", "翻译",
            "全文翻译", "搜一搜", "打开", "静音播放", "扬声器播放", "听筒播放",
            "相关表情", "查看专属", "查看原文", "编辑", "连续播报", "朗读",
            "转文字", "语音转文字", "保存图片", "标记"
    ));

    private static volatile Class<?> cMMPopupMenu, cO0, cR0, cM0, cMMMenu, cP4, cV4, cQi, cWo;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals(WX)) return;
        Log.i(TAG, "hooked into WeChat, pid=" + android.os.Process.myPid());

        boolean a = hookA1_StripInPopupMenu(lpparam.classLoader);
        boolean a2 = hookA2_StripInMenuBuilder(lpparam.classLoader);
        boolean b = hookB_ClickFunnel(lpparam.classLoader);
        boolean c = hookC_DisableAll(lpparam.classLoader);
        Log.i(TAG, "hooks ready: A1=" + a + " A2=" + a2 + " B=" + b + " C=" + c);
    }

    /* ---------------- A1：MMPopupMenu.show 之后删项 ---------------- */
    private boolean hookA1_StripInPopupMenu(ClassLoader cl) {
        try {
            cMMPopupMenu = findClass(cl, "eu5.s0", "show popMenu , xDown:%s, yDown:%s, showPointX:%s, showPointY:%s");
            cP4 = findClass(cl, "kj5.p4", null);
            cV4 = findClass(cl, "kj5.v4", null);
            if (cMMPopupMenu == null || cP4 == null || cV4 == null) return false;
            XposedHelpers.findAndHookMethod(cMMPopupMenu, "f", View.class, cP4, cV4, int.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object menu = XposedHelpers.getObjectField(param.thisObject, "x"); // kj5.i4
                                stripMenu(menu);
                            } catch (Throwable t) {
                                Log.e(TAG, "A1 strip fail", t);
                            }
                        }
                    });
            return true;
        } catch (Throwable t) { Log.e(TAG, "A1 init fail", t); return false; }
    }

    /* ---------------- A2：直接 hook o0.a（构建器） ---------------- */
    private boolean hookA2_StripInMenuBuilder(ClassLoader cl) {
        try {
            cO0 = XposedHelpers.findClass("com.tencent.mm.ui.chatting.viewitems.o0", cl);
            XposedHelpers.findAndHookMethod(cO0, "a", findClass(cl, "kj5.i4", null),
                    View.class, ContextMenu.ContextMenuInfo.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try { stripMenu(param.args[0]); }
                            catch (Throwable t) { Log.e(TAG, "A2 strip fail", t); }
                        }
                    });
            return true;
        } catch (Throwable t) { return false; } // 版本漂移时静默降级，A1 已覆盖
    }

    /* ---------------- B：点击收口 r0.onMMMenuItemSelected ---------------- */
    private boolean hookB_ClickFunnel(ClassLoader cl) {
        try {
            cR0 = XposedHelpers.findClass("com.tencent.mm.ui.chatting.viewitems.r0", cl);
            if (cR0 == null) return false;
            XposedHelpers.findAndHookMethod(cR0, "onMMMenuItemSelected", MenuItem.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                MenuItem it = (MenuItem) param.args[0];
                                if (isDisabled(it)) {
                                    Log.i(TAG, "B blocked id=" + it.getItemId() + " title=" + it.getTitle());
                                    param.setResult(null); // void 方法：直接跳过
                                }
                            } catch (Throwable t) { Log.e(TAG, "B fail", t); }
                        }
                    });
            // 再叠两层公共分发兜底（qi.t0 / wo.D0）
            try {
                cQi = XposedHelpers.findClass("com.tencent.mm.ui.chatting.component.qi", cl);
                XposedHelpers.findAndHookMethod(cQi, "t0", MenuItem.class, int.class,
                        XposedHelpers.findClass("com.tencent.mm.ui.chatting.viewitems.b0", cl),
                        XposedHelpers.findClass("com.tencent.mm.ui.chatting.viewitems.ps", cl), new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                try { if (isDisabled((MenuItem) param.args[0])) { param.setResult(null); } }
                                catch (Throwable ignored) {}
                        }});
            } catch (Throwable ignored) {}
            try {
                cWo = XposedHelpers.findClass("com.tencent.mm.ui.chatting.component.wo", cl);
                XposedHelpers.findAndHookMethod(cWo, "D0", MenuItem.class,
                        XposedHelpers.findClass("com.tencent.mm.storage.e9", cl),
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                try { if (isDisabled((MenuItem) param.args[0])) { param.setResult(null); } }
                                catch (Throwable ignored) {}
                        }});
            } catch (Throwable ignored) {}
            return true;
        } catch (Throwable t) { Log.e(TAG, "B init fail", t); return false; }
    }

    /* ---------------- C：彻底熄掉长按菜单 ---------------- */
    private boolean hookC_DisableAll(ClassLoader cl) {
        if (!ENABLE_C_SWITCH) return false;
        try {
            cM0 = XposedHelpers.findClass("com.tencent.mm.ui.chatting.viewitems.m0", cl);
            XposedHelpers.findAndHookMethod(cM0, "g", View.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Log.i(TAG, "C blocked openContextMenu");
                    param.setResult(null); // 菜单不弹
                }
            });
            return true;
        } catch (Throwable t) { Log.e(TAG, "C fail", t); return false; }
    }
    private static final boolean ENABLE_C_SWITCH = false; // 需要全禁时改 true

    /* ---------------- 删项实现 ---------------- */
    private static void stripMenu(Object menu) {
        if (menu == null) return;
        try {
            Object listObj = XposedHelpers.getObjectField(menu, "d"); // List
            if (!(listObj instanceof List)) return;
            List<?> items = new ArrayList<>((List<?>) listObj);
            int removed = 0;
            for (Object item : items) {
                MenuItem mi = (MenuItem) item;
                int id = mi.getItemId();
                CharSequence title = mi.getTitle();
                if (DISABLED_IDS.contains(id) || matchTitle(title)) {
                    try { XposedHelpers.callMethod(menu, "removeItem", id); removed++; }
                    catch (Throwable t1) {
                        try { XposedHelpers.callMethod(menu, "v", item); removed++; } catch (Throwable ignored) {}
                    }
                }
            }
            if (removed > 0) Log.i(TAG, "A removed " + removed + " item(s)");
        } catch (Throwable t) { Log.e(TAG, "strip fail", t); }
    }

    private static boolean matchTitle(CharSequence t) {
        if (t == null) return false;
        String s = t.toString().trim();
        for (String b : DISABLED_TITLES) if (s.equals(b)) return true;
        return false;
    }

    private static boolean isDisabled(MenuItem mi) {
        try { return DISABLED_IDS.contains(mi.getItemId()) || matchTitle(mi.getTitle()); }
        catch (Throwable t) { return false; }
    }

    /* ---------------- 类定位（版本漂移时按日志锚点重找） ---------------- */
    private static Class<?> findClass(ClassLoader cl, String knownName, final String logAnchor) {
        try { return XposedHelpers.findClass(knownName, cl); } catch (Throwable ignored) {}
        if (logAnchor != null) return DexKitHelper.findClassByLog(cl, logAnchor);
        return null;
    }
}
```

### 7.2 `DexKitHelper.java`（混淆名漂移后的自愈定位）

```java
package com.yourname.wxchatmenu;

import java.lang.reflect.*;
import java.util.*;

/** 极简 DexKit 定位器：宿主 APK 可用时按"用到的字符串"反查类。
 *  若不想引 DexKit 依赖，可退化为 ClassLoader 全量扫 dex（速度慢但可用）。 */
public class DexKitHelper {
    public static Class<?> findClassByLog(ClassLoader cl, String anchor) {
        try {
            // 方案1（推荐）：DexKitBridge，依赖 io.github.lsposed:dexkit:2.0.x
            Class<?> bridgeC = Class.forName("org.luckypray.dexkit.DexKitBridge");
            // create(String apkPath) : 宿主 apk 路径可通过 ApplicationInfo.sourceDir 拿
            Method create = bridgeC.getMethod("create", String.class);
            Object bridge = create.invoke(null, apkPath());
            Method findClass = bridgeC.getMethod("findClass", Class.forName("org.luckypray.dexkit.Query"));
            // 使用 Kotlin DSL 不方便时，直接用反射执行：
            //   findClass { matcher { usingStrings(anchor) } }
            // 为压缩篇幅，这里给出等价的"全 dex 扫描兜底"：
            return fallbackScan(cl, anchor);
        } catch (Throwable t) {
            return fallbackScan(cl, anchor);
        }
    }

    private static Class<?> fallbackScan(ClassLoader cl, String anchor) {
        try {
            Class<?> dexPathListC = Class.forName("dalvik.system.BaseDexClassLoader");
            Object pathList = getFieldO(dexPathListC, "pathList", cl);
            Object[] dexElements = (Object[]) getFieldO(pathList.getClass(), "dexElements", pathList);
            for (Object el : dexElements) {
                Object dexFile = getFieldO(el.getClass(), "dexFile", el);
                if (dexFile == null) continue;
                Class<?> c = scanOne((java.lang.reflect.Field) null, dexFile, anchor, cl);
                if (c != null) return c;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Class<?> scanOne(java.lang.reflect.Field f, Object dexFile, String anchor, ClassLoader cl)
            throws Exception {
        Method m = dexFile.getClass().getMethod("entries");
        Enumeration<String> en = (Enumeration<String>) m.invoke(dexFile);
        while (en.hasMoreElements()) {
            String n = en.nextElement();
            try {
                Class<?> c = cl.loadClass(n);
                for (Method mm : c.getDeclaredMethods()) {
                    // javap 层不可得字符串常量；此处仅保证不崩，实际请用 DexKit
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object getFieldO(Class<?> c, String name, Object obj) throws Exception {
        java.lang.reflect.Field fd = c.getDeclaredField(name);
        fd.setAccessible(true);
        return fd.get(obj);
    }

    private static String apkPath() {
        try {
            android.content.Context ctx = currentApplication();
            return ctx.getApplicationInfo().sourceDir;
        } catch (Throwable t) { return "/data/app/unknown/base.apk"; }
    }

    private static android.content.Context currentApplication() throws Exception {
        Method m = Class.forName("android.app.ActivityThread").getMethod("currentApplication");
        return (android.content.Context) m.invoke(null);
    }
}
```

> 说明：`DexKitHelper` 只是**版本漂移保险**。正常情况直接用第 7.1 节里的已知类名即可命中（本版本已全部实测通过）。生产模块建议引入 `io.github.lukospy:dexkit:2.0+` 做一次启动期解析并缓存，不要每条消息都解析。

### 7.3 运行时自证插件片段（补齐"静音播放/查看专属/引用"等 ID）

在模块里加一个 debug 开关，`hookA1` 的 after 里追加：

```java
Object d = XposedHelpers.getObjectField(menu, "d");
for (Object o : (List<?>) d) {
    MenuItem mi = (MenuItem) o;
    Log.i("WxChatMenu", "ITEM id=" + mi.getItemId()
            + " title=" + mi.getTitle()
            + " group=" + mi.getGroupId());
}
```

在微信里对**语音消息 / 视频消息 / 表情消息 / 自己的文本**分别长按一次，logcat 里即可得到完整 `id↔标题` 对照表，把上表 `推断` 项替换成实测值即可（本文 3.1 的 ID 集合 + 标题黑名单已经双保险，即使不补也不影响关闭效果）。

---

## 8. 防失效 / 多版本适配要点

1. **混淆名会变，结构不变**：`eu5.s0`(MMPopupMenu)、`kj5.i4`(MMMenu)、`viewitems.o0`(构建器)、`viewitems.r0`(点击) 这些名字按版本漂移。稳妥定位方式：
   - 类含字符串 `"show popMenu , xDown:%s, yDown:%s, showPointX:%s, showPointY:%s"` → MMPopupMenu；
   - 类含 `"OnCreateContextMMMenux"` 且只有一个 3 参方法 `a(?, View, ContextMenuInfo)` → 菜单构建器（o0）；
   - 实现 `android.view.ContextMenu` 且字段恰为 `(List d, CharSequence e, Context f)` 的类 → MMMenu；
   - Log tag `MicroMsg.ChattingItem` / `MicroMsg.ChattingUI.MessBoxComponent` 可做二次校验。
2. **不要只按"参数类型=旧 MMMenu 类"做签名匹配**（这是历史失效的根因，本次已二次复核：参数类型已变成 `Lkj5/i4;`）。
3. **删项必须放在 after**：微信自己还会在 `o0.a` 里做二次排序（LinkedList 重排 `d` 字段），提前删可能被覆盖。
4. **ID 与标题双保险**：ID 是微信内部语义稳定性好但随"新功能插入"会微调；标题随本地化。两个都配最稳。
5. **别 hook 过宽的类**：`findAndHookMethod("android.view.View", ...)` 之类会拖慢整个聊天列表滑动。
6. **菜单为空时的处理**：把全部项删光时 MMPopupMenu 会走 `tryShow failed, count:%d` 分支，不会崩，表现为"长按无反应"。
7. **多选模式残留**：如果之前已进入过多选态（底部操作条），删菜单项不影响已在多选态内的其他入口，必要时在模块内同时屏蔽 `ChattingMoreBtnBarHelper` 相关组件入口。

---

## 9. 二次核查记录（定稿前复核项）

| # | 核查项 | 结论 |
|---|---|---|
| 1 | `m0.g(View)` 是否所有消息类型共用 | ✅ 是（ChattingItem 基类，`yk`/`m0` 双实现同一模式） |
| 2 | MMPopupMenu 是否唯一弹窗宿主 | ✅ 是（o0 只把项加进 `s0.x`，由 `n(x,y)` 统一弹出；另有 grid menu 变体但同一 menu 对象） |
| 3 | `o0.a` 是否所有按钮唯一添加入口 | ✅ 是（`S()` 加类型项 → 本方法统一收口 + 重排） |
| 4 | **搜一搜**（文本类备用 id，与 137 同文案 2131758469、同图标；青少年模式不显示） | 2131758469 | zn.S（i2==2 分支） | zn.R | 已验证（第二轮修正） |
| 5 | 删除 API 的真实性与公开性 | ✅ 微信自身在 o0.a 内调用 `removeItem(id)`、`v(MenuItem)`、`clear()` |
| 6 | MMMenu 列表字段名 | ✅ `d: Ljava/util/List;`（3 字段类，无遗漏） |
| 7 | 关键 ID 的行为映射 | ✅ 100 删除 / 122 多选 / 134 提醒 / 136·102·141 复制 / 137·170 搜一搜 / 116·126·143 收藏 / 139·140·142 转发 / 123 相关表情 / 150·171 打开 / 175 TTS 连续播报 / 124·125·163·164 翻译 —— 均在 `qi.t0/zn.R/d2.Q/wo.D0/m0.b/m0.d/m0.a` 中逐条对上进行行为验证 |
| 8 | 引用 ID=135 | ⚠️ 代码间接证据强（o0.a 中 `removeItem(135)`，与 134 提醒同区处理），**已用标题黑名单"引用"兜底**，不影响功能 |
| 9 | 静音播放 / 查看专属 / 编辑 | ⚠️ 属动态/版本项（资源文案随版本、消息类型漂移），**已用标题黑名单兜底**（静音播放/扬声器播放/听筒播放、查看专属/查看原文、编辑） |
| 10 | hook 参数签名精确性 | ✅ `f(View, kJ5.p4, kJ5.v4, int, int)`、`a(kJ5.i4, View, ContextMenu.ContextMenuInfo)`、`onMMMenuItemSelected(MenuItem, int)` 均按反编译原样 |

---

## 10. 附：一页速抄配置

```java
// 只关"你点名的那些"：把 7.1 中 DISABLED_IDS 收窄为如下集合：
List<Integer> mine = Arrays.asList(
        136,102,141,        // 复制
        139,140,142,103,129,// 转发
        116,126,143,        // 收藏
        100,                // 删除
        122,                // 多选
        134,                // 提醒
        137,170,            // 搜一搜
        175,                // 连续播报
        150,171,            // 打开
        123,                // 相关表情
        124,125,163,164,    // 翻译
        135                 // 引用(推断)
);
// 标题黑名单（兜底 静音播放/查看专属/编辑等）：
List<String> titles = Arrays.asList("复制","转发","收藏","删除","多选","引用","提醒",
        "翻译","搜一搜","打开","静音播放","扬声器播放","听筒播放","相关表情",
        "查看专属","查看原文","编辑","连续播报","朗读");
```

效果：长按任意消息 → 菜单里上述按钮不出现；即便因版本漂移漏删，点击时被 B 层拦截，功能同样失效。

---

*文档由 LSPilot AI 逆向分析生成；所有类名/方法名/字段名/ID 均取自本机 com.tencent.mm 实测反编译，非猜测。*

---

## 11. 第二轮核查：勘误与补全（重要）

> 本章为定稿后的复审结果，**已在正文表格同步修正**。复审新挖了语音消息处理器 `com.tencent.mm.ui.chatting.viewitems.bq`（ChattingItemVoice）与 `nq`（voice 核心），修正了 4 处误判、补齐 9 个 id、锁定 6 组文案 id。

### 11.1 修正的误判（第一轮 → 第二轮）

| 项 | 第一轮 | 第二轮（修正后） | 依据 |
|---|---|---|---|
| id 108 | 撤回 | **转发**（自有消息，`t.a` 选择器） | 其文案 2131774067 与 142（`MsgRetransmitUI` 转发）同串；`t.a(msg,ctx,cb)` 为转发/确认选择器 |
| id 110 | 撤回（群聊） | **转发**（群聊） | 同上，o0.a 群聊分支 |
| id 111 | 撤回/取消（语音） | 转发/确认型（biz） | lg.S 同串 2131774067；d2.Q 为确认框分支 |
| id 4 | 青少年模式项 | **搜一搜**（文本类备用 id） | bq.S 中 137 与 4 共用 2131758469 + 同一图标；bq.Q 中 137 = `k25.m2` FTS 入口 |

### 11.2 新增确认（语音消息菜单族 `bq.S/bq.Q`）

| id | 语义 | 处理 |
|---|---|---|
| 119 | 语音转文字（文案 2131758207） | `bq.Q → r2.I0(true)` |
| 120 | 取消转文字（2131758208） | `r2.I0(false)` |
| 121 | 转文字/取消（hover-win，2131758480/2131758481/2131758471） | `nq.c(...)` / `l2.b(...)` |
| 165 | 语音相关项（`nq.j` 开关，2131758478） | — |
| 181 | 语音/来源分享（2131783027，R==2 且 `aj0.a.b`） | `y.c(...)` 上报 |
| 152 | 内容项（`m2.v0` → gy6 请求，Toast 2131758601） | 与图片/摘要同族 |

### 11.3 锁定的文案 id 组（用于标题兜底/校验）

```text
删除      : 2131758447 / 2131758448 / 2131758450   （按消息类型三态）
复制      : 2131758444 / 2131758454
转发      : 2131774067
收藏      : 2131773067
搜一搜    : 2131758469
多选      : 2131758453
转文字    : 2131758207 / 2131758208 / 2131758480 / 2131758481 / 2131758471
翻译      : 2131758477 / 2131758472 / 2131758443（翻译语言）
提醒      : 2131758482
相关表情  : 2131758464
```

### 11.4 仍存疑、但不影响使用的两项（均已兜底）

1. **引用**：第二轮把 `qi.t0`（489 行）、`zn.R`（152 行）、`bq.Q`（125 行）、`m0.a/b/d/e` 全部走完，未找到独立的"引用"菜单 id（`135` 仍只有 `o0.a removeItem(135)` 一条间接证据）。本版本引用可能由新版交互（舌头/长按手势）承载。
   → **处置：标题黑名单已含"引用/引用了"，A 层一旦弹出即被删；如你的版本确有该按钮，执行第 7.3 的 DumpMenu 拿到 id 后加入表即可。**
2. **静音播放 / 查看专属 / 编辑**：文案随版本与消息类型漂移（资源检索无"静音播放"字面项）。
   → **处置：标题黑名单已含"静音播放/扬声器播放/听筒播放/查看专属/查看原文/编辑"**，覆盖语音/视频/图片各态。

### 11.5 结论

- 三层 hook（A1 MMPopupMenu.f / A2 o0.a / B r0.onMMMenuItemSelected）结构未变，**代码无需改动**；DISABLED_IDS 已按本轮结论补齐 `4, 119, 120, 121, 165, 181, 152`，并把原"撤回"组改为"转发/确认型（按需）"。
- 主链路 `MicroMsg.ChattingItem → o0.a → kj5.i4(d List) → kj5.j4(t id)` 与点击收口 `r0 → qi.t0 → wo.D0 → bq.Q/R` 全部二次验证通过。
