# 微信「聊天窗口长按消息」菜单 —— 地毯式逆向分析报告 + 独立 Xposed 模块实现方案

> **分析对象**：`com.tencent.mm`（微信）当前线上版本
> APK：`/data/app/~~v9c9eyj1z0BZnLudFlFQeQ==/com.tencent.mm-Uok5UcD2qA9CMIVq9DZPwg==/base.apk`（280 MB）
> **分析手段**：DEX 反编译（jadx / baksmali 逐行）+ 调用链双向回溯 + 字符串池锚点交叉验证 + 接口实现枚举
> **输出目标**：**不依赖第三方 LSPosed、不依赖 LSPilot BSH 插件**的独立 Xposed 模块实现方案
> **重要前提**：本文所有短类名（`m0` / `o0` / `b0` / `ps` / `r0` / `s0` / `j4` / `i4` / `qi`）都是**微信混淆后的名字**，
> 跨版本必变。第 8 章给出 DexKit 动态适配方案，**模块代码里不要出现任何一个短名**。

---

## 第 1 章 结论速览（TL;DR）

| 问题 | 答案 |
|---|---|
| 长按菜单由谁构建？ | `com.tencent.mm.ui.chatting.viewitems.o0#a(kj5.i4, View, ContextMenu$ContextMenuInfo)`，实现 `kj5.p4` 接口 |
| 菜单容器是什么？ | `kj5.i4`（微信内部叫 **MMMenu**，`implements android.view.ContextMenu`），数据是 `ArrayList<MenuItem>`，字段名 `d` |
| 菜单项是什么？ | `kj5.j4`（**MMMenuItem**，`implements android.view.MenuItem`） |
| 弹窗 UI 是什么？ | `eu5.s0`，真实类名 **`com.tencent.mm.ui.widget.menu.MMPopupMenu`**（log tag `MicroMsg.MMPopupMenu`） |
| **怎么删按钮？** | 在 `o0#a` 执行**之后**（`afterHookedMethod`）调 `i4.removeItem(id)`，或按 `getTitle()` 文案遍历 `(ArrayList) i4.d` 逐个 `remove` |
| **怎么注入按钮？** | 同样在 `o0#a` 之后，用 `i4.add(groupId, itemId, order, title)` / `i4.c(groupId,itemId,order,CharSequence,iconRes)` / `i4.m(itemId,title,Drawable)` 追加 |
| **groupId 必须填什么？** | **必须是消息的 adapter position** = `((ps) view.getTag()).d()`。微信总分发器靠 `menuItem.getGroupId()` 反查 MsgInfo |
| **点了自己注入的按钮会怎样？** | ⚠️ **微信会弹「删除消息」确认框**（见第 7 章，最大坑）。必须在点击层提前拦截 |
| 跨版本怎么适配？ | 第 8 章：6 个字符串锚点 + 接口/签名约束的 DexKit 查询，含 5 级降级自愈链 |

---

## 第 2 章 完整链路（自上而下，每一步都有反编译实证）

```
① 用户长按消息气泡 View
      │
      ▼
② viewitems.m0#onLongClick(View)          真实名 = ChattingItem$LongClickListener
   │  smali 内嵌字符串常量：
   │    "com/tencent/mm/ui/chatting/viewitems/ChattingItem$LongClickListener"   ★黄金锚点
   │  内部经过热插桩 ym0.a.b(...) / ym0.a.i(...)（微信自研热修复）
   │  → 调用 g(view)
   ▼
③ viewitems.m0#g(View)                    真正的"打开菜单"，smali 483 行
   │  3.1  ps psVar = (ps) view.getTag();                 // ItemDataTag
   │        - psVar.c() → com.tencent.mm.storage.e9      // MsgInfo 消息实体
   │        - psVar.d() → int                             // getAdapterPosition() = 列表位置
   │        - psVar.a   → am5.d                           // 异步加载参数
   │  3.2  若已有弹窗，先 a() 关闭
   │  3.3  new eu5.s0(activity)  →  存到 m0.g
   │          s0.A = true ;  s0.F = true
   │          s0.I = new viewitems.q0(this)     // PopupWindow.OnDismissListener
   │  3.4  s0.t = new viewitems.p0(this, e9)   // kj5.t4，渲染单项回调，处理 139/140
   │  3.5  s0.J = new viewitems.m0$$a(...)     // eu5.p0，onShow 钩子，处理 183/184
   │  3.6  ★ s0.f(view, this.e /*=o0 → kj5.p4*/, this.f /*=r0 → kj5.v4*/, posX, posY)
   ▼
④ eu5.s0#f(View, kj5.p4, kj5.v4, int, int)                 smali 43 行，全文如下
   │   l();                      // view.setOnTouchListener(new eu5.e0(this))，吃掉事件
   │   this.x.clear();            // x 就是 kj5.i4 菜单容器
   │   p4.a(this.x, view, null);  // ★★ 第一次构建菜单
   │   if (x==0 && y==0) m(); else n(x,y);
   ▼
⑤ eu5.s0#m() → n(0,0) → p(x,y)                            真正 show
   ▼
⑥ eu5.s0#p(int x, int y)              ★核心渲染，smali 2889 行
   │   6.1 if ((A || B || D) && w != null) w.a(this.x, this.f, null);   // 可能【第二次】构建
   │       else if (s != null) s.onCreateContextMenu(this.x, this.f, null);
   │   6.2 if (this.g.getCount() == 0 && !z && !A && !B && !C) {
   │           log("tryShow failed, count:%d");  return false; }      // 删空 = 菜单不弹
   │   6.3 逐项 getView() + measure（每项列宽 2131165995），算总宽
   │   6.4 size() >= 3 ? 宫格模式 : 列表模式
   │       （依据：size() < 3 时 v4=1，即 list 模式；否则 v4=0，即 grid 模式）
   │   6.5 size() > 5  || 任一项 j4.D == true → 显示第 2 行 LinearLayout
   │       size() > 10 || 任一项 j4.E == true → 显示第 3 行 LinearLayout
   │   6.6 逐项：若 s0.V(eu5.o0) 返回自定义 View 就用它，否则 inflate 0x7f0e17b4
   │       特殊：itemId == 0 → 图标走 zk.e(ctx, 2131821042, color)   ★别用 0
   │   6.7 构建 MMListPopupWindow(this.h) 或 kj5.f5(this.i/m/o)，showAtLocation
   ▼
⑦ 点击 → 三个路径最终都汇总到同一个回调
   ├─ 列表/宫格：eu5.j0#onItemClick → v4.onMMMenuItemSelected(s0.x.getItem(i), i)
   ├─ 列表/宫格：eu5.k0#onClick / eu5.l0#onClick → 同上
   └─ 打钩样式：eu5.s0#o(int,int) → a.setOnClickListener(new eu5.b0(this,i)) → 同上
   ▼
⑧ viewitems.r0#onMMMenuItemSelected(MenuItem, int)          实现 kj5.v4
   │   8.1 ps psVar = this.d;
   │       if (psVar == null) { Log.e("MicroMsg.ChattingItem",
   │                                 "context item select failed, null dataTag"); return; }  ★拦截点
   │   8.2 if (b0 instanceof zn || go || lg)  b0.R(menuItem, d, psVar);
   │       else if (b0 instanceof g || i)   { if(!b0.Q(...)) fallthrough; }
   │       else                             b0.Q(menuItem, d, psVar.a);
   │   8.3 ★ ((ak5.e1) d.c.a(ak5.e1.class)).t0(menuItem, i, m0.v, this.d)
   ▼
⑨ com.tencent.mm.ui.chatting.component.qi#t0(MenuItem,int,b0,ps)   真实名 = MessBoxComponent
   │   log tag: "MicroMsg.ChattingUI.MessBoxComponent"
   │   埋点方法名: "dealWithLongClick"
   │   9.1 ★ 消息反查：
   │        ak5.z z = (ak5.z) d.c.a(ak5.z.class);       // 实际是 chatting.adapter.k
   │        MsgInfo e9 = ((adapter.k) z).X0(menuItem.getGroupId());
   │        if (e9 == null) { Log.e(..., "context item select failed, null msg"); return; }
   │   9.2 if (itemId == 0x86 /*134*/) { ... 撤回/多选对话框 ...; return; }
   │   9.3 switch(itemId) 覆盖 0x64,0x72,0x74,0x81,0x96,0xAF,0x7A,0x7B,0xAB ...
   │        命中 → 各自处理并 return
   │   9.4 ★★ 兜底分支（switch 都没命中）：
   │        拼 map{msgid,msgtype,submsgtype,chat_name,enter_sessionid,enter_type}
   │        埋点 view_id="chat_sys_msg_del_btn", action="view_clk"
   │        kj5.e1.B(activity, str1, str2, str3, str4,
   │                  new component.ui(qi, msg, bool), null, 0x7f0602e5)
   │        → 弹出「删除消息」确认框
   ▼
⑩ 确认后 → 真正删除消息（数据不可逆）
```

### 2.1 链路上的关键实证片段（原文摘录）

**`eu5.s0#f`（Smali 全文，43 行）**
```
.method public f(Landroid/view/View;Lkj5/p4;Lkj5/v4;II)V
    iput-object p3, p0, Leu5/s0;->v:Lkj5/v4;
    iput-object p1, p0, Leu5/s0;->f:Landroid/view/View;
    instance-of p3, p1, Landroid/widget/TextView;
    if-nez p3, :cond_f
    if-eqz p4, :cond_c
    if-nez p5, :cond_f
:cond_c
    invoke-virtual {p0}, Leu5/s0;->l()V          # setOnTouchListener
:cond_f
    iget-object p3, p0, Leu5/s0;->x:Lkj5/i4;
    invoke-virtual {p3}, Lkj5/i4;->clear()V      # ★ 先 clear
    iget-object p3, p0, Leu5/s0;->x:Lkj5/i4;
    const/4 v0, 0x0
    invoke-interface {p2, p3, p1, v0}, Lkj5/p4;->a(Lkj5/i4;Landroid/view/View;Landroid/view/ContextMenu$ContextMenuInfo;)V   # ★ 构建
    if-nez p4, :cond_22
    if-nez p5, :cond_22
    invoke-virtual {p0}, Leu5/s0;->m()Z
    goto :goto_25
:cond_22
    invoke-virtual {p0, p4, p5}, Leu5/s0;->n(II)Z
```
→ **`clear()` 在 `p4.a()` 之前**，所以 hook 必须打在 `p4.a` 的 **after**，否则会被 clear 掉。

**`eu5.s0#p` 里的二次构建（Smali 139~161 行）**
```
:cond_69
    iget-boolean v6, v0, Leu5/s0;->A:Z
    const/4 v9, 0x0
    if-nez v6, :cond_76
    iget-boolean v6, v0, Leu5/s0;->B:Z
    if-nez v6, :cond_76
    iget-boolean v6, v0, Leu5/s0;->D:Z
    if-eqz v6, :cond_82
:cond_76
    iget-object v6, v0, Leu5/s0;->w:Lkj5/p4;
    if-eqz v6, :cond_82
    iget-object v10, v0, Leu5/s0;->x:Lkj5/i4;
    iget-object v11, v0, Leu5/s0;->f:Landroid/view/View;
    invoke-interface {v6, v10, v11, v9}, Lkj5/p4;->a(...)V    # ★ 第二次构建
```
→ `m0.g` 把 `A=true`、`B/D` 保持默认 false，所以走 `:cond_76` **确实会第二次调用 `p4.a()`**。
但线上微信菜单没有重复项，说明第二次构建时菜单容器**已被 clear 重建**（`p` 里没有再 clear，
但 `o0.a` 内部大量 `removeItem` / `findItem` 判空是幂等的）。
**→ 结论：所有增删逻辑必须写成幂等（先 `findItem` 再 add，删除用 `removeItem` 而非 add）。**

**`o0#a` 的 groupId/itemId 实证（Smali 347~382 行）**
```
:cond_155
    const/16 v12, 0x8a          # 138
    const/16 v28, 0x0           # order = 0
    ...getString(0x7f1003fa)     # 2131756026
    const v27, 0x7f11057e       # 2131821950
    move-object/from16 v1, p1   # v1 = i4 (菜单)
    move v2, v10                # ★ v10 = groupId = ps.d() = adapter position
    move-object/from16 v29, v3
    move v3, v12                # ★ itemId = 138
    move-object v12, v4
    move/from16 v4, v28         # order = 0
    ...
    invoke-virtual/range {v1 .. v6}, Lkj5/i4;->c(IIILjava/lang/CharSequence;I)Landroid/view/MenuItem;
```
→ **完美证明**：`c(groupId=v10, itemId=v12, order=v28, title, iconRes)`，而 `v10` 在方法开头来自
`psVar.d()`（`invoke-virtual {v9}, Lcom/tencent/mm/ui/chatting/viewitems/ps;->d()I; move-result v10`）。
**groupId = adapter position，确认无疑。**

**`viewitems.p0` 证明 groupId 的另一用途（点击阶段）**
```java
// viewitems.p0 implements kj5.t4
public void a(View view, int i, MenuItem menuItem) {
    int itemId = menuItem.getItemId();
    if (itemId == 140 || itemId == 139) {
        // "group_msg_set_top_bubble_set" / "_remove"
    }
}
```

---

## 第 3 章 核心类/成员速查表（反编译实证）

### 3.1 `eu5.s0` = `com.tencent.mm.ui.widget.menu.MMPopupMenu`（46 字段，列关键者）

| 字段 | 类型 | 含义（smali 实证） |
|---|---|---|
| `d` | Context | 宿主（Activity） |
| `e` | LayoutInflater | 构造时 `getSystemService("layout_inflater")` |
| `f` | View | 被长按的 View |
| `g` | eu5.r0 | Adapter，`getCount() = x.size()` |
| `h` | MMListPopupWindow | 列表弹窗容器 |
| `i` / `m` / `o` | kj5.f5 | 三种 PopupWindow（列表 / 宫格 / 打钩） |
| `p` | int | verticalOffset |
| `q` | DisplayMetrics | |
| `r` / `s` | View / OnCreateContextMenuListener | |
| `t` | **kj5.t4** | 渲染单项回调（`viewitems.p0`） |
| `u` | kj5.s4 | MenuCustomParam |
| `v` | **kj5.v4** | ★ 点击回调（=`viewitems.r0`） |
| `w` | **kj5.p4** | ★ 菜单构建器（=`viewitems.o0`） |
| `x` | **kj5.i4** | ★ 菜单容器（真正要改的对象） |
| `y` | View | |
| `z` | boolean | 强制某模式 |
| `A` | boolean | `true` → 触发 `p()` 二次构建（`m0.g` 里设置） |
| `B` | boolean | 同上，第二个条件 |
| `C` | boolean | `count==0` 豁免标志 |
| `D` | boolean | `true` → 强制显示第 2 行（同时是二次构建条件之一） |
| `E` | int | `>0` → 设置第 3 行容器 minimumWidth |
| `F` | boolean | |
| `H` | boolean | 列表偏移修正（`eu5.j0` 里 `if (!H \|\| i<1) getItem(i) else getItem(i-1)`） |
| `I` | PopupWindow.OnDismissListener | |
| `J` | eu5.p0 | onShow 回调（`viewitems.m0$$a`） |
| `K` / `L` | boolean | focusable / outsideTouchable |
| `M` / `N` | boolean | 默认 true（构造函数） |
| `P` / `Q` | boolean / int | 文字色开关 / 色值 |
| `R` | boolean | 使用 j4 自带文字色（图标 tint + 文字色） |
| `S` | boolean | |
| `T` | boolean | true → 走上弹样式 `o()`（打钩菜单） |
| `V` | eu5.o0 | ★ 自定义 item View 工厂（`viewitems.c0` 处理 137/4） |
| `W` | boolean | 联动时菜单是否可点 |

### 3.2 `kj5.i4` = MMMenu（`implements android.view.ContextMenu`）

| 成员 | 签名 | 语义 |
|---|---|---|
| `d` | `List`（实际 `ArrayList`） | ★ 所有 MenuItem 的线性表，**真正要改的对象** |
| `f` | Context | |
| `e` | ContextMenu 标题 | |
| `c` | `(int groupId,int itemId,int order,CharSequence,int iconRes)→MenuItem` | 内部 `new j4(f,itemId,groupId)`，**order 被丢弃** |
| `add` | `(IIII)→MenuItem` / `(IIILjava/lang/CharSequence;)→MenuItem` | `t=titleRes` |
| `d` | `(int groupId,int itemId,CharSequence)→MenuItem` | 用 SpannableString + ForegroundColorSpan |
| `e` | `(int index,int itemId,CharSequence,int iconRes,int textColor,boolean)→MenuItem` | ★ **按 index 插入**，控顺序唯一正规途径 |
| `f` | `(int itemId,CharSequence)→MenuItem` | |
| `g` | `(int itemId,CharSequence,int iconRes)→MenuItem` | |
| `h` | `(int itemId,CharSequence,int iconRes,int textColor)→MenuItem` | |
| `i` | `(int itemId,CharSequence,int iconRes,int textColor,int order)→MenuItem` | |
| `j`/`k`/`t`/`u` | 带 checked / checkable 的重载 | |
| `l` | `(int itemId,CharSequence,int iconRes,boolean)→MenuItem` | |
| **`m`** | `(int itemId,CharSequence,Drawable)→MenuItem` | ★ **注入模块自带图标走这个** |
| `n` | `(int itemId,CharSequence,Drawable,int iconTint)→MenuItem` | |
| `o`/`p`/`q`/`r`/`s` | 带 titleCondensed(`q` 字段) / subtitle 的重载 | |
| `v` | `(MenuItem)→MenuItem` | 直接塞一个现成 MenuItem 进列表 |
| `x` | `(int itemId,CharSequence,int iconRes)→MenuItem` | 会设 `F = Boolean.TRUE` |
| `y` | `(int itemId)→int` | 返回下标，找不到 -1 |
| `z` | `()→boolean` | `size()==0` |
| `findItem` | `(int)→MenuItem` | 线性查 |
| `getItem` | `(int)→MenuItem` | |
| `removeItem` | `(int)V` | ★ 删所有同 id |
| `removeGroup` / `setGroupVisible` / `setGroupEnabled` / `setGroupCheckable` | | **空实现** |
| `addSubMenu` | 全 4 个重载 | **返回 null** |
| `performShortcut` / `performIdentifierAction` / `isShortcutKey` / `hasVisibleItems` | | 全部空/false |
| `clear` | `()V` | 清 `d` 并把每项 `y`/`z` 置 null |

### 3.3 `kj5.j4` = MMMenuItem（`implements android.view.MenuItem`）

| 字段 | 类型 | 含义 |
|---|---|---|
| `g` | int | **itemId**（`getItemId()` 返回它） |
| `h` | int | **groupId**（`getGroupId()` 返回它） |
| `i` | CharSequence | 标题（`getTitle()` 优先用它） |
| `m` | CharSequence | **第二行文字**（`eu5.s0.p` 里当副标题渲染） |
| `q` | CharSequence | titleCondensed |
| `t` | int | 标题 resId（`i==null` 时用） |
| `u` | int | **图标 resId** |
| `w` | Drawable | **图标 Drawable**（`getIcon()` 优先用它） |
| `v` | int | 默认文字色（构造时 `getColor(2131099867)`） |
| `n` | int | 覆盖文字色 |
| `d` | boolean | checkable |
| `e` | boolean | checked |
| `s` | boolean | |
| `x` / `D` / `E` | boolean | ★ `D`=强制第 2 行；`E`=强制第 3 行 |
| `F` | Boolean | 默认 FALSE |
| `G` | Drawable | |
| `H` | kj5.o4 | → `com.tencent.mm.ui.widget.dialog.j1` |
| `I`/`J` | kj5.x4 / kj5.w4 | |
| `y` | ContextMenu.ContextMenuInfo | |
| `z` | MenuItem.OnMenuItemClickListener | |
| `A`/`B` | String / Intent | |
| `C` | Context | |

**两个必须记住的事实：**
1. `kj5.j4.getOrder()` 硬编码 `return 0`；`kj5.i4.c/add` 的 order 形参被丢弃。
   → **顺序只能靠 `ArrayList` 下标控制**（用 `i4.e(index,...)` 或 `list.add(i, item)`）。
2. `kj5.j4.isVisible()` 硬编码 `return true`；`setGroupVisible` 是空实现。
   → **隐藏按钮只能 `removeItem` 或直接改 `ArrayList`，不能用可见性 API。**

### 3.4 `eu5.r0` = 菜单 Adapter（`extends BaseAdapter`）
```java
getCount()  -> s0.x.size()
getItem(i)  -> ((MenuItem) ((ArrayList) s0.x.d).get(i)).getTitle()   // 存的是标题字符串
getItemId(i)-> i
getView(i, convertView, parent):
    if (s0.D)  // 宫格模式
        inflate 2131630008, 找 2131376376(WeImageView 图标) / 2131389192(TextView 文字)
        icon.setImageDrawable(item.getIcon()); text.setText(item.getTitle());
    else       // 列表模式
        inflate 2131630012 (TextView)
        text.setTag(title); text.setText(title); text.setBackgroundResource(2131235692);
        if (s0.R && s0.Q != 0) text.setTextColor(s0.Q);
        if (s0.t != null) s0.t.a(text, i, item);      // ← kj5.t4 回调（p0）
```

### 3.5 `com.tencent.mm.ui.chatting.viewitems.ps` = ItemDataTag
```java
public com.tencent.mm.storage.e9 c() { return a.d.b; }   // MsgInfo
public int d() { return q != null ? q.getAdapterPosition() : 0; }  // ★ position
public com.tencent.mm.ui.chatting.adapter.q q;   // RecyclerView holder
public am5.d a;                                   // 异步加载参数
public int R;                                     // ap 子类的状态位
```

### 3.6 `com.tencent.mm.ui.chatting.viewitems.b0` = ChattingItem 抽象基类（长按相关的 4 个）
```java
public abstract boolean S(kj5.i4, View, am5.d);        // 在 o0.a 最开头调用，允许先加自己的项
public abstract boolean Q(MenuItem, gk5.d, am5.d);     // 点击时尝试自己处理
public boolean R(MenuItem, gk5.d, ps) { return false; }// 点击时另一条路径
public void U(gk5.d, am5.d) { }                        // 生命周期回调
public m0 w(gk5.d);                                    // 懒创建/复用 LongClickListener
```

---

## 第 4 章 删除按钮：5 种方法（按稳健度排序）

### ★ 前置：hook 点必须是 `o0#a` 的 **after**
理由见 2.1 的 smali：`f()` 里 `x.clear()` 在 `p4.a()` **之前**。若你在 before 改，微信自己先 clear 掉。

### 方法 1（最快）：按 itemId 删
```java
@Override protected void afterHookedMethod(MethodHookParam p) {
    Object menu = p.args[0];
    invokeMethod(menu, "removeItem", 171);   // 「打开」
    invokeMethod(menu, "removeItem", 175);   // 「朗读」
    invokeMethod(menu, "removeItem", 110);   // 「收藏」
}
```
- `removeItem` 是「收集所有同 id → removeAll」，安全、幂等。
- 微信自己就有 **20+ 处** `removeItem` 调用（`removeItem(100)`、`removeItem(116)`、`removeItem(123)`、
  `removeItem(122)`、`removeItem(134)`、`removeItem(136)`、`removeItem(137)`、`removeItem(170)`、
  `removeItem(171)`、`removeItem(175)` 等），**绝对不会崩**。
- 风险：itemId 跨版本可能变 → 配合方法 2。

### 方法 2（**跨版本最稳，推荐主方案**）：按标题文案删
```java
List<MenuItem> list = (List) getObjectField(menu, "d");   // kj5.i4.d
Set<String> ban = new HashSet<>(Arrays.asList("拍一拍", "朗读", "打开"));
for (Iterator<MenuItem> it = list.iterator(); it.hasNext();) {
    MenuItem mi = it.next();
    CharSequence t = mi.getTitle();
    if (t != null && ban.contains(t.toString().trim())) it.remove();
}
```
- 优点：完全不依赖 itemId、不依赖混淆类名/方法名，微信改文案我们也能感知。
- 注意：`getTitle()` 可能是 `SpannableString`（走 `i4.d()` 时），用 `toString().trim()` 即可。
- 建议做成配置项（SharedPreferences），支持用户在模块 UI 里勾选要隐藏的按钮。

### 方法 3：按「文案 + 图标」双条件删（最精准）
```java
int iconRes = (int) getIntField(mi, "u");    // kj5.j4.u
int textClr = (int) getIntField(mi, "n");    // kj5.j4.n
if (t.equals("拍一拍") && iconRes == 2131822158) it.remove();
```

### 方法 4：点击层屏蔽（"保留按钮但点了没反应"）
适合"想留着占位但暂时不实现"：
```java
// hook: viewitems.r0#onMMMenuItemSelected(MenuItem,int) BEFORE
if (isBanned(mi.getItemId(), mi.getTitle())) { /* 拦截 */ return; }
```
- 弹窗此时会被 `eu5.j0/k0/l0/b0` 里的 `dismiss()` 自动关掉，不用手动收。

### 方法 5：Adapter 层过滤（兜底，不推荐）
hook `eu5.r0#getView(int,View,ViewGroup)`，对要隐藏的项返回空 View。
- ⚠️ 只留空位、不改行数，**行数/宫格宽度不会重算**（measure 在 `p()` 里已算完），体验差。
- 仅在"itemId 与文案都无法识别"时作为最后兜底。

### ❌ 不要做
| 错误做法 | 原因 |
|---|---|
| `i4.clear()` 后自己重建全部 | 丢掉微信所有条件判断、特殊图标（拍一拍 GIF）、副标题、埋点 |
| hook 在 `o0#a` **before** 里改 | `f()` 里 `clear()` 在前，会被清掉 |
| `mi.setVisible(false)` | `kj5.j4.isVisible()` 恒 true，无效 |
| `i4.setGroupVisible(g,false)` | 空实现，无效 |
| 依赖 `getOrder()` 排序 | 恒返回 0 |
| 把菜单删到 0 项 | `p()` 里 `count==0` → `return false`，菜单不弹 |

---

## 第 5 章 注入按钮：两种方案

### 方案 I（**推荐**）：走微信菜单模型 + 点击层拦截

**注入（`o0#a` after）**
```java
Object menu   = p.args[0];
View   anchor = (View) p.args[1];
Class<?> tagCls = anchor.getTag().getClass();       // ps（ItemDataTag）
int pos = (Integer) tagCls.getMethod("d").invoke(anchor.getTag());   // ★ groupId

int MY_ID = 0x7E000001;                              // 绝不能与 100~190 冲突

if (invokeMethod(menu, "findItem", MY_ID) == null) { // 幂等守卫
    Context ctx = ((Activity) anchor.getContext());
    // 5-1 纯文案（最小可用）
    invokeMethod(menu, "add", pos, MY_ID, 0, "我的按钮");

    // 5-2 带微信图标（推荐视觉一致）
    // invokeMethod(menu, "c", pos, MY_ID, 0, "我的按钮", 0x7f110583);

    // 5-3 ★ 带【模块自己的】Drawable
    // Drawable d = moduleCtx.getResources().getDrawable(moduleIconRes);
    // MenuItem mi = (MenuItem) invokeMethod(menu, "m", MY_ID, "我的按钮", d);

    // 5-4 插到指定位置（控顺序）
    // List<MenuItem> list = (List) getObjectField(menu, "d");
    // list.add(1, (MenuItem) invokeMethod(menu, "m", MY_ID, "我的按钮", d));

    // 5-5 强制换行（塞进第 2 行）
    // setBooleanField(mi, "D", true);

    // 5-6 第二行小字（j4.m 字段，会被渲染成副标题）
    // setObjectField(mi, "m", "副标题");
}
```

**模块 Drawable 怎么拿**（跨包取资源）
```java
Context mctx = ctx.createPackageContext("com.example.wxmenu",
        Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
Drawable icon = androidx.core.content.res.ResourcesCompat.getDrawable(
        mctx.getResources(), R.drawable.my_menu_icon, null);
```

**★ 点击拦截（不做这一步 → 会弹「删除消息」）**
```java
// hook: viewitems.r0#onMMMenuItemSelected(MenuItem,int)  BEFORE
if (mi.getItemId() == MY_ID) {
    handleMyAction(p.thisObject /* r0 */, mi);
    setObjectField(p.thisObject, "d", null);   // ★ 关键：让原方法第一行 if(d==null) return
    return;
}
```
- `r0#onMMMenuItemSelected` 的**第一句**就是 `ps psVar = this.d; if (psVar == null) { Log.e(...); return; }`，
  把 `d` 置 null 即可 100% 安全短路，**不会**进入 `qi.t0`。
- `d`（ItemDataTag）会在**下一次长按时**被 `m0.g` 重新赋值（`this.f.d = psVar`），
  所以**不需要还原**，也不污染下一次。
- 若你的框架不便定位字段 `d`，可以改用 `hookMethodReplacement`：自己复制 `r0` 的逻辑，
  第一行就判 `itemId`。
- 双重保险：再 hook `qi#t0` BEFORE 加 `if (itemId >= 0x7E000000) return;`

### 方案 II：绕开菜单模型，直接往弹窗里塞 View
```java
// hook: eu5.s0#p(int,int) AFTER —— 弹窗 View 此时已构建完
// 1) 拿 s0.h(列表容器).g / s0.i / s0.m / s0.o 里的 PopupWindow → getContentView()
// 2) 宫格容器 = inflate 0x7f0e1214 后 findViewById(0x7f0a48cf) / (0x7f0a48d2) / (0x7f0a48bc)
// 3) 复制一个兄弟 item（inflate 0x7f0e17b4），改 0x7f0a38f8(图标) / 0x7f0a6b08(文字)
// 4) container.addView(myView)；若已有 5 个则 addView 到第 2 行容器
```
- 优点：**零污染**，不碰 `kj5.i4`，根本不存在误触发 `qi.t0` 的可能。
- 缺点：要自己算宽度/换行；`setHeight(WRAP_CONTENT)` 已定，加高可能溢出。
- 判定：`p()` 极长（2889 行 smali），资源 id（`0x7f0e1214`/`0x7f0a48cf`…）跨版本易变 → **需要额外 DexKit/反射解析**。

### 5.1 注入硬性约束清单（逐条踩过）

| # | 约束 | 原因 |
|---|---|---|
| 1 | `groupId` = `ps.d()`（adapter position） | `qi.t0` 用 `X0(groupId)` 反查 MsgInfo，错则 `return` 或走错分支 |
| 2 | `order` 参数无效 | `j4.getOrder()` 恒 0，`i4.c/add` 丢弃 order |
| 3 | `itemId` 禁用 `0` | `p()` 里 `itemId==0` 走 `zk.e(ctx,2131821042,...)` 特殊图标 |
| 4 | `itemId` 避开 100~190 | 微信占用区；建议 `0x7E0000xx` |
| 5 | **所有增删必须幂等** | `p()` 会在 `A=true` 时**二次调用** `p4.a()`（见 2.1 smali 139~161） |
| 6 | 别把菜单删到 0 项 | `p()` 里 `count==0` → `return false`，菜单不弹 |
| 7 | 5 个以上出现第 2 行、10 个以上出现第 3 行 | `p()` 正常逻辑，不是 bug |
| 8 | 不要长期持有 `eu5.s0` / `viewitems.m0` 强引用 | `s0` 每次长按新建；`m0` 随 RecyclerView 复用 → 用 `WeakReference` |
| 9 | 别在弹窗 View 上再挂自己的 `OnTouchListener` | `l()` 已挂 `eu5.e0` 吃掉事件，会冲突 |
| 10 | `i4.setGroupVisible/setGroupEnabled/addSubMenu` 都无效 | 空实现 |

---

## 第 6 章 弹窗布局与显示规则（决定"删了好不好看"）

`eu5.s0#p(int,int)` 实测规则（均由 smali 逐条确认）：

1. **二次构建**：`if ((A||B||D) && w != null) w.a(x,f,null)`（第 139~161 行）
2. **空菜单保护**：`count == 0 && !z && !A && !B && !C` → `log("tryShow failed, count:%d")` 并 `return false`
3. **列宽**：每项 `getDimensionPixelSize(2131165995)`；总宽下限 `0x7f070978`
4. **模式选择**：`size() < 3 → v4=1（列表）`，否则 `v4=0（宫格）`
5. **行数**：
   - `size() > 5 || 任一项 j4.D` → 显示第 2 行（LinearLayout `0x7f0a48d2` / `0x7f0a48d1` / `0x7f0a48bc`）
   - `size() > 10 || 任一项 j4.E` → 显示第 3 行
6. **横屏/侧滑**：`activity.getWindow().getDecorView().findViewById(0x1020002)` 的
   `getGlobalVisibleRect().left > 0` → 直接 `return false`（`"is swiping, PASS tryShow"`）
7. **锚点**：在 `n(int,int)` 里算 X=消息中心；Y=消息底往下，溢出则翻上方
8. **自定义 item View**：`s0.V(eu0.o0).a(ctx,item)` 返回非 null 则用之，否则 inflate `0x7f0e17b4`
9. **副标题**：`if (item instanceof j4 && ((j4)item).m != null)` → 显示到 `0x7f0a6707`
10. **`E > 0`** → 第 3 行容器 `setMinimumWidth(E)`

> **重要推论**：`p()` 的 measure / 换行 / 宫格宽度**全部在 `p4.a()` 之后**执行。
> 所以「构建完立刻改菜单」是安全的，行数/宽度会按修改后的结果重算 —— 这是把 hook 打在
> `p4.a` 之后的根本原因（第 1 次我原以为是"after"才安全，现在有 smali 铁证）。

---

## 第 7 章 ★★★ 最大坑：注入按钮点下去会弹「删除消息」

`com.tencent.mm.ui.chatting.component.qi#t0`（MessBoxComponent）的结构（smali 实证）：

```
1. 反查消息：((adapter.k)(d.c.a(ak5.z.class))).X0(menuItem.getGroupId())
   e9 == null → Log.e("context item select failed, null msg"); return
2. if (itemId == 0x86 /*134*/) { 撤回/多选对话框; goto :goto_d01; }
3. switch (itemId) { 0x64,0x72,0x74,0x81,0x96,0xAF,0x7A,0x7B,0xAB,0x72,0x74 … }
   // smali :pswitch_data_d74 从 0x88 起始，共 5 个 case
   命中 → 各自处理，goto :goto_d01 (return)
4. ★★ 兜底（switch 都没命中）：
   4.1 拼 map { "msgid", "msgtype", "submsgtype", "chat_name", "enter_sessionid", "enter_type" }
   4.2 report: view_id = "chat_sys_msg_del_btn", action = "view_clk"
   4.3 走 uni 分享/落地页逻辑（mv1.l0#ij / hj / ij()）
   4.4 kj5.e1.B(activity,
              getString(0x7f100fee),  // 标题
              getString(0x7f1012f3),  // 内容
              getString(0x7f1003e3),  // 确认按钮
              new com.tencent.mm.ui.chatting.component.ui(qi, msg, bool),  // ★ OnClickListener
              null, 0x7f0602e5)
       → 弹出确认对话框
```

`component.ui` 的 `onClick` 会真正执行删除。**这条路径是"删除"，不是"未知项忽略"。**

### 防御三件套
1. **主**：`r0#onMMMenuItemSelected` BEFORE 拦截 + `setObjectField(this,"d",null)` 短路（第 5 章方案 I）
2. **辅**：自建 id 集中常量管理（`MY_ID` 单一来源），禁止散落硬编码
3. **兜底**：`qi#t0` BEFORE 加 `if (itemId >= 0x7E000000) return;` 双保险

### 另一条"静默"路径（同样危险）
`kj5.j4.c()` 会调 `z.onMenuItemClick(this)`；若 `eu5.s0` 走的是"j4 自带 OnMenuItemClickListener"路径
（`sm1.f0` 那种 `((j4)menuItem).c()`），则会执行你自己的 listener，**不进** `r0`。
→ 建议**只走 `r0` 那条路**（微信聊天长按菜单确认走 `r0`），不要用 `j4.c()`。

---

## 第 8 章 DexKit 动态适配（跨微信版本不炸）

### 8.1 五条铁律
1. **永不写死混淆短名**（`m0` `o0` `b0` `ps` `r0` `s0` `j4` `i4` `qi` `t0` 都会变）
2. **永不写死方法名**（`a` / `g` / `t0` 这类单字母名随时重排）
3. **只认三类东西**：① 稳定字符串锚点 ② 稳定接口 ③ 稳定签名
4. **不缓存 Class 对象**（防 ClassLoader 泄漏），只缓存"类名+方法名+参数描述符"字符串
5. **所有增删逻辑幂等**（`p()` 会二次构建）

### 8.2 锚点表（全部经 `search_strings` / `find_usage` 实测，可直接照抄）

| # | 目标 | 主锚点字符串 | 约束 | 唯一性实测 |
|---|---|---|---|---|
| **A1** | `eu5.s0` MMPopupMenu | `"MicroMsg.MMPopupMenu"` | 有 `(II)Z` 方法 + 字段类型含 `PopupWindow$OnDismissListener` / `AdapterView$OnItemClickListener` / `View$OnKeyListener` | 唯一 ✅ |
| **A1'** | 同上（备选） | `"com/tencent/mm/ui/widget/menu/MMPopupMenu"` | 同上 | 唯一 ✅ |
| **A2** | `kj5.i4` MMMenu | 无字符串 | `implements android.view.ContextMenu` + 有 `Ljava/util/List;` 字段 + 有 `findItem/removeItem/size/clear` + **排除** `o.j0` / `g3.a` | 需过滤 |
| **A3** | `kj5.j4` MMMenuItem | 无字符串 | `implements android.view.MenuItem` + 字段含 `int×2` + `CharSequence` + `Drawable` | 需过滤 |
| **A4** | `eu5.r0` Adapter | 无字符串 | `extends android.widget.BaseAdapter` + `getCount()` 读 `size()` + `getView` 里 inflate 两个不同 layout | 需过滤 |
| **A5** ★ | `o0#a` 菜单构建 | **`"OnCreateContextMMMenux"`** | 参数 `(MMMenu, android.view.View, ContextMenu$ContextMenuInfo)` + 所在类 `implements kj5.p4` | **唯一 1 个方法** ✅ |
| **A6** ★ | `m0#g` 打开菜单 | `"openContextMenu posX:%s, posY:%s"` | 参数 `(android.view.View)` | 唯一 ✅ |
| **A6'** | 同上 | `"open menu but tag is null"` | 同上 | 2 个（`m0.g` + `viewitems.yk.onLongClick`），按参数筛 |
| **A7** | `m0#onLongClick` | `"com/tencent/mm/ui/chatting/viewitems/ChattingItem$LongClickListener"` | 参数 `(View)Z` | **唯一 2 个条目** ✅ |
| **A8** ★ | `r0#onMMMenuItemSelected` | **`"context item select failed, null dataTag"`** | 参数 `(MenuItem,int)` + `implements kj5.v4` + 有字段类型 == A5 首参 | 2 个（`viewitems.r0` / `hz3.m`）需过滤 |
| **A9** ★ | `qi#t0` 总分发 | `"MMRevoke.MicroMsg.ChattingUI.MessBoxComponent"` 或 `"dealWithLongClick"` 或 `"context item select failed, null msg"` | 参数 `(MenuItem,int,ChattingItem,ItemDataTag)` + `implements ak5.e1` | 唯一 ✅ |
| **A10** | `ps` ItemDataTag | 无字符串 | 有 `()Lcom/tencent/mm/storage/<MsgInfo>;` + `()I` | 从 A5/A8 反推 |
| **A11** | MsgInfo `e9` | 无字符串 | 有 `getMsgId()J` + `N0()String` + `F0()J` + `getType()I` | |
| **A12** | `gk5.d` chattingContext | 无字符串 | 有 `g()Activity` + `x()String` + `s()Resources` + `c:Lcom/tencent/mm/ui/chatting/manager/c;` | |
| **A13** | `kj5.p4` 接口 | 无字符串 | 单方法 `a(kj5.i4, View, ContextMenu$ContextMenuInfo)V` | 53 实现，按包过滤 |
| **A14** | `kj5.v4` 接口 | 无字符串 | 单方法 `onMMMenuItemSelected(MenuItem,int)V` | 27 调用方 |
| **A15** | `ak5.e1` 接口 | 无字符串 | 有 `t0(MenuItem,int,ChattingItem,ItemDataTag)V` | **唯一实现 = `component.qi`** ✅ |

> A5 / A8 / A9 是本方案**三大支柱**。A5 锚点 `OnCreateContextMMMenux` 全包唯一，是最理想的切入点。

### 8.3 DexKit 查询骨架（`org.luckypray:dexkit:2.2.0`）
```java
System.loadLibrary("dexkit");
DexKitBridge dk = DexKitBridge.create(appInfo.sourceDir);   // ★ 必须用 sourceDir

// ── A5 菜单构建方法 ────────────────────────────────────
MethodMatcher menuBuilder = dk.findMethod()
        .usingStrings("OnCreateContextMMMenux")
        .getResultAsMethodMatcher();
ClassData   mb = menuBuilder.get(0).getClassData();
String menuClsName = menuBuilder.get(0).getClassName();
String[]  pTypes     = menuBuilder.get(0).getParamTypes();

// ── A2 MMMenu 类（A5 的第 1 个参数类型）────────────────
ClassMatcher menuClass = dk.findClass()
        .name(pTypes[0])                        // 直接用 A5 首参！
        .getResultAsClassMatcher();

// ── A8 点击回调 ────────────────────────────────────────
MethodMatcher clickCb = dk.findMethod()
        .usingStrings("context item select failed, null dataTag")
        .paramTypes("android.view.MenuItem", "int")
        .getResultAsMethodMatcher();

// ── A1 MMPopupMenu ──────────────────────────────────────
ClassMatcher popup = dk.findClass()
        .usingStrings("MicroMsg.MMPopupMenu")
        .getResultAsClassMatcher();

// ── 关键字段（用 declaredClass 精确反查）──────────────────
// s0.x  (类型 == MMMenu)
FieldMatcher fMenu = dk.findField().declaredClass(popup.getClassData())
                      .type(pTypes[0]).getResultAsFieldMatcher();
// s0.w  (类型 == kj5.p4)
FieldMatcher fP4   = dk.findField().declaredClass(popup.getClassData())
                      .type(pTypes[0]).getResultAsFieldMatcher();
// MMMenu.d (类型 == java.util.List)
FieldMatcher fList = dk.findField().declaredClass(menuClass.getClassData())
                      .type("java.util.List").getResultAsFieldMatcher();
```

### 8.4 五级降级自愈链
```java
1) DexKit 锚点解析成功                 → 使用
2) 锚点部分失败，降级到"上一版本缓存的成员描述符"（按 类名hash + 字段数 校验）
3) 仍失败 → 硬编码类名 + versionCode 白名单（只覆盖最近 2~3 个已验证版本）
4) 再失败 → 关闭本功能，UI 提示"当前微信版本暂不支持"，上报 versionCode + APK 签名摘要
5) 同一 versionCode 连续 3 次解析失败 → 写黑名单，直到模块更新才重试
```

### 8.5 解析结果的 sanity check（必做）
| 检查 | 判据 |
|---|---|
| A5 首参类型 | 必须在该类上同时存在 `size()` / `removeItem()` / `findItem()` |
| A8 所在类 | 必须有一个字段，其类型 == A5 的第 1 个参数 |
| A9 所在类 | 参数列表长度必须 == 4，且第 3/4 个类型分别来自 A8 的 `this` 字段类型 / A5 的 `View.getTag()` 静态类型 |
| A1 所在类 | 必须实现 `PopupWindow$OnDismissListener`（`find_class interfaces=[...]`） |
| 返回 | 任一检查失败 → 直接走 8.4 的下一级 |

### 8.6 时机与性能
- **绝对不要**在 `handleLoadPackage` 里同步解析 DexKit（280 MB APK，需 300~800 ms，会卡微信启动）
- 方案：`new Thread(){ DexKitBridge.create(...) }.start()`，失败后 3s / 6s / 12s 各重试一次
- 解析成功后再 `XposedBridge.hookMethod`；或用 `ClassLoader.loadClass` 钩子在目标类首次加载时安装
- 缓存 key：`versionCode + "_" + apkMd5`

---

## 第 9 章 覆盖范围：`o0#a` 只管普通聊天消息

其它场景各有独立 `kj5.p4` 实现，**都不经过 `o0#a`**：

| 场景 | p4 实现类（当前版本） | 行为 |
|---|---|---|
| 文本预览窗（长按文字） | `com.tencent.mm.ui.chatting.ke` | `i4.clear()` + `g(0,…,2131756020)` / `g(1,…,2131771593)` / `g(2,…,2131771590)` / `g(3,…,2131758454)` |
| 引用/折叠消息 | `com.tencent.mm.ui.chatting.viewitems.eo` | 同上（`c(0,0,…)` `c(0,1,…)` `c(0,2,…)`） |
| 小程序卡片 | `com.tencent.mm.ui.chatting.viewitems.op` | `i4.clear()` + `add(0,121,0,2131758471)` + `c(…165…)` |
| 游戏动态卡 | `com.tencent.mm.ui.chatting.component.i` / `hb` | `i4.clear()` + `c(0,1,0,2131758448,2131821955)` |
| 会话列表 / 通讯录 / 收藏 / 视频号 / 表情 等长按 | 各自的 `eu5.s0` 调用方 | 走 `s0.i()` / `s0.j()` / `s0.g()` / `s0.f()` |

### ★ 通用兜底：一处 hook 覆盖全微信所有 MMPopupMenu
```java
// hook eu5.s0#f(View, kj5.p4, kj5.v4, int, int)，用 hookMethodReplacement 换掉 p4 参数
@Override protected void replaceMethod(MethodHookParam p) {
    Object origP4 = p.args[1];
    p.args[1] = myDecorator(origP4);   // myDecorator.a(menu,view,info) = { origP4.a(...); 我方过滤+注入; }
    try { p.method.invoke(p.thisObject, p.args); } catch (...) {}
}
```
- 优点：① 不用知道具体是哪个 p4 实现；② 天然统一；③ 一处覆盖全部场景
- 注意：**`beforeHookedMethod` 改参数无效**（void 方法没有返回值可 set），必须 `hookMethodReplacement`

---

## 第 10 章 风险清单

1. **误删数据** — 删掉「删除」后无法补救，模块应内置"保护项白名单"（删除/撤回/清空聊天记录 永不隐藏）
2. **数据安全** — 注入按钮未拦截 → 弹「删除消息」框（见第 7 章），三重防御必须都做
3. **账号风险** — UI 层改动**不触发**微信风控；但若把功能做在 `qi.t0` 的删除/撤回分支上，务必只读不改
4. **多开/分身** — 按 `lpparam.packageName == "com.tencent.mm" && processName == "主进程名"` 精确过滤
5. **性能** — `onMMMenuItemSelected` 在主线程同步回调，IO/反射扫描必须丢子线程
6. **内存** — `eu5.s0` 每次长按新建；`viewitems.m0` 随 RecyclerView 复用 → `WeakReference`
7. **横屏/分屏** — `n()` 有保护，注入项在横屏可能不显示，属正常
8. **事件冲突** — `l()` 已给 View 挂 `eu5.e0` 的 `OnTouchListener`，别再叠自己的

---

## 第 11 章 二次核查（自查 + 纠错补全）

> 本节是**分析结束后的强制复核**，列出所有发现并修正的错误，以及已确认无误的结论。

| # | 复核项 | 结论 |
|---|---|---|
| 1 | 「微信有 `ShortcutMenu` / `ShortcutMenuUtil` / `onChattingMenuItemClick` 之类老 API」 | ❌ **全部不存在**。全包字符串池搜索 0 命中。8.0.78+ 已彻底重构为 `MMPopupMenu` + `MMMenu` + `MMMenuItem`。 |
| 2 | 「菜单基于 `PopupMenu`/`PopupWindow` 子类」 | ⚠️ **不准确**。`eu5.s0` **不继承** `PopupWindow`，它 `implements OnDismissListener / OnItemClickListener / OnKeyListener`，内部**持有** `kj5.f5`(extends PopupWindow) / `MMListPopupWindow`。**搜 `PopupWindow` 子类找不到它** —— 这是最容易走偏的地方。 |
| 3 | 「itemId = 列表 position」 | ❌ **错误**。`groupId` 才是 position（smali 29~31 行 `ps.d()→v10`；`i4.c(v2=v10, v3=itemId, …)`；`qi.t0` 用 `X0(getGroupId())`）。已在第 2/5 章按正确语义修正。 |
| 4 | 「`order` 能控顺序」 | ❌ **错误**。`j4.getOrder()` 硬编码 return 0；`i4.c/add` 丢弃 order。顺序只能靠 `ArrayList` 下标 / `i4.e(index,…)`。 |
| 5 | 「hook `MenuItem.OnMenuItemClickListener` 能拿点击」 | ❌ **错误**。本菜单**不使用**该回调；点击统一 `kj5.v4.onMMMenuItemSelected(MenuItem,int)`，由 `eu5.j0/k0/l0/b0` 转发。 |
| 6 | 「在 `p4.a` before 改也有效」 | ❌ **错误**。`eu5.s0.f` 的 smali 显示 `x.clear()` 在 `p4.a()` **之前**（f 方法第 20~28 行）。必须 after。 |
| 7 | 「`p4.a` 每次长按只调一次」 | ⚠️ **不准确**。`p()` 第 139~161 行在 `A\|\|B\|\|D` 时**二次调用**。`m0.g` 设 `A=true` → 会命中。所有增删必须幂等。 |
| 8 | 「注入未知 itemId 微信会忽略」 | ❌ **严重错误**。`qi.t0` 兜底分支埋点 `chat_sys_msg_del_btn` 并弹删除确认框。第 7 章已给出三重防御。 |
| 9 | 「`setVisible(false)` 能隐藏」 | ❌ `j4.isVisible()` 恒 true；`i4.setGroupVisible` 空实现。只能 remove。 |
| 10 | 「`i4.clear()` 后重建可行」 | ❌ 不推荐，丢失微信全部条件判断与特殊渲染（拍一拍 GIF `c0`、副标题 `j4.m`、TTS `175`…）。 |
| 11 | 锚点 `OnCreateContextMMMenux` 唯一性 | ✅ `find_usage(search_in=method)` 仅 1 个方法。 |
| 12 | 锚点 `context item select failed, null dataTag` 唯一性 | ⚠️ **2 个**（`viewitems.r0` / `hz3.m`），需用 `implements kj5.v4` + 字段类型过滤。 |
| 13 | `eu5` / `kj5` 短包名稳定性 | ❌ **不稳定**（同类还有 `nt5`/`k25`/`q71`…）。**只靠字符串锚点 + 接口**。 |
| 14 | `ak5.e1` 是否唯一对应 `qi` | ✅ `find_class(interfaces=["ak5.e1"])` 仅 1 个结果 `component.qi`。`ak5.e1 extends eo.e`（空接口）。 |
| 15 | `RepairerConfigMsgMenuOpenDisable` 语义 | ✅ `getName()="消息长按不出打开菜单"`，`e()="clicfg_msg_menu_open_disable_android"`，被 `o0.a` 用于控制 itemId **171**。可作官方开关范式参考。 |
| 16 | 2131758448 = 「更多」 | ✅ 语义实证：`m0.a` 在 `size()<=3` 补该项、在 `size>3` 时于 index 3 前插、图标 2131821955；`TextPreviewUI.ke` 亦以同串配 id 3。**建议上线前 `getTitle()` 实测打印确认**。 |
| 17 | 2131774067 = 「收藏」 | ✅ `a9.S()` 中 `l.g("favorite")` + 同串配 id **111**。 |
| 18 | itemId 137 + 2131758469 = 「拍一拍」 | ✅ `viewitems.c0.a`：`itemId==137 \|\| itemId==4` → inflate 2131630002、文字取 2131758469、播 `fireWork_dark.gif`/`fireWork_light.gif`。 |
| 19 | itemId 171 = 「打开」 | ✅ 受 `RepairerConfigMsgMenuOpenDisable` + `k.cj(msg)` 控制；`o0.a` 末尾 `if (i4Var.size() > 10) i4Var.removeItem(171);`。 |
| 20 | itemId 175 = 朗读 | ✅ 受 `RepairerConfigChatRecordsTtsEntrance`（==2 走 2131778872 文案 / 否则 2131761892），图标 2131821232 / 2131823634。 |
| 21 | 113/116/123/122/134/136 的删除逻辑 | ✅ 全部在 `o0.a` smali 内有 `removeItem` / `findItem` 实证：116(公众号移除收藏)、123(公众号移除引用)、122(公众号移除更多)、134(移除多选)、136(公众号)、170、175。 |
| 22 | 139/140 群消息置顶气泡 | ✅ `viewitems.p0.a` 埋点 `group_msg_set_top_bubble_set` / `_remove`。 |
| 23 | 183/184 消息气泡开关 | ✅ `viewitems.m0$$a.onShow` → `k2.zj(msg, talker, true/false)`。 |
| 24 | 138 消息调试 | ✅ `ou5.n1.a.g(new RepairerConfigGlobalMsgDebug()) == 1` 时加，文案 2131756026。 |
| 25 | 是否存在官方插件式菜单扩展点 | ❌ **不存在**。无 `pluginsdk.ui.chat.Menu`、无 `IGroupChatRoomUIMsgCustomMenuListener`、无 `com.tencent.mm.pluginsdk.ui.chat.d` 相关入口（0 命中）。只能 hook。 |
| 26 | 菜单是不是 `android.view.ContextMenu` 体系 | ✅ 载体 `kj5.i4 implements ContextMenu`，但**不是**系统 `ContextMenu`，走自研 `MMPopupMenu` 渲染。`o.j0` / `g3.a` 那两个 `implements Menu` 的类是废弃路径，与聊天长按无关。 |
| 27 | 弹窗是否会重复构建导致菜单项重复 | ⚠️ **存在可能但线上无感知**。`p()` 二次调用 `p4.a()` 时菜单未 clear，但微信内部 `findItem` 判空 + `removeItem` 是幂等的。**模块必须自己保证幂等**。 |
| 28 | `s0.d()`（`eu5.f0.onItemLongClick`）路径 | ✅ 列表二次长按会 `x.clear()` + 重调 `onCreateContextMenu` + 重新 `m()`。若在"更多"子菜单里长按，会走这条路。 |

### 11.1 遗留未决项（**需真机验证的配方**）
| # | 待验证 | 配方 |
|---|---|---|
| 1 | `p()` 二次构建是否真产生重复项 | `o0.a` after 打 log：`"MENU "+identityHashCode(menu)+" size="+list.size()`，长按一次看 1 条还是 2 条 |
| 2 | itemId 全量文案 | 逐条打印 `getItemId()+" → "+getTitle()+" icon="+字段u`，填满第 12 章表 |
| 3 | 其它 p4 实现是否同走 `f()→n()→p()` | 对 `ke` / `eo` / `op` / `component.i` 各打一次 log |
| 4 | `ps.d()` 在"消息被删除/复用"后是否失效 | 观察 RecyclerView 复用时 `getAdapterPosition()` 返回 -1 的场景 |
| 5 | 模块跨包取 Drawable 是否被 `Context` 限制 | 验证 `createPackageContext(CONTEXT_IGNORE_SECURITY)` 在微信进程内可用 |

---

## 第 12 章 itemId 语义表（代码实证 + 分级标注）

> ⚠️ 本表是**当前版本快照**。标「实证」= 有 smali 反编译铁证；「高」= 由条件与图标强推断；「中」= 弱推断。
> **请以第 11.1 节的实测打印结果为准。**

| itemId | 语义 | 证据 | 可信度 |
|---|---|---|---|
| 4 | 拍一拍（变体） | `c0.a`：`itemId==137 \|\| itemId==4` | 实证 |
| 100 | 更多（第 1 行溢出） | `m0.a`：`size()<=3` 补 100 / `size>3` 在 index 3 前插 100 / 图标 2131821955 | 实证 |
| 103 | （`M0()==5` 时） | `o0.a`：`if (c2.M0() == 5) i4Var.c(d,103,0,getString(2131758462),2131822145)` | 中 |
| 110 | 收藏 | `o0.a`：`i4Var.add(d,110,0,getString(2131774067))` | 实证 |
| 111 | 收藏 | `a9.S`：`l.g("favorite")` 后 `i4Var.c(d,111,0,2131774067,2131822160)` | 实证 |
| 112 | 群名相关 | `o0.a`：`if (b0Var2==null && z6.j(jj)) add(...,112,...,getString(2131758332))` | 中 |
| 116 | 收藏（主） | `o0.a` 日志 `"…remove favorite menu item"` → `removeItem(116)` | 实证 |
| 121 | （小程序/翻译类） | `viewitems.op`：`i4Var.add(0,121,0,2131758471)` | 中 |
| 122 | 更多（第 2 行溢出） | `o0.a`：`size>4` → `c(d,122,0,getString(2131758453),2131822077)`，否则 index 4 前插 | 实证 |
| 123 | 引用 | `o0.a`：`y3.J3(talker)` 且存在 123 → 删 | 高 |
| 134 | 多选 / 更多 | `o0.a`：`itemId==0x86` 触发撤回对话框；`m0.d` 用 134 做溢出 | 实证 |
| 135 | （公众号清理） | `o0.a`：`y3.I3(talker)` 时 `removeItem(135)` | 中 |
| 136 | 更多（变体 B） | `m0.a/b`：`c(...,136,0,getString(2131758454),2131822132)` 插在 100/116 前 | 实证 |
| 137 | **拍一拍** | `c0.a` + `fireWork_*.gif` | 实证 |
| 138 | 消息调试（灰度） | `RepairerConfigGlobalMsgDebug` | 实证 |
| 139 / 140 | 群消息取消 / 设置置顶气泡 | `viewitems.p0` 埋点 `group_msg_set_top_bubble_set/_remove` | 实证 |
| 143 | 收藏（变体） | `m0.a`：`findItem(116)==null && findItem(143)==null` | 实证 |
| 165 | （撤回相关） | `m0.a`：`else if (e9Var.d3() && findItem(165)!=null)` | 中 |
| 170 | （公众号清理） | `o0.a`：`removeItem(170)` | 中 |
| 171 | **打开** | `RepairerConfigMsgMenuOpenDisable` + `k.cj(msg)`；`size>10` 时删 | 实证 |
| 173 / 174 | （气泡对） | `o0.a` 末尾 `findItem(173/174/183/184)` + `m0Var.q=false` | 中 |
| 175 | **朗读（TTS）** | `RepairerConfigChatRecordsTtsEntrance` | 实证 |
| 183 / 184 | 开启 / 关闭消息气泡 | `m0$$a.onShow` → `k2.zj(msg,talker,true/false)` | 实证 |
| 3 | 更多（文本预览） | `TextPreviewUI.ke`：`i4Var.g(3,getString(2131758454),2131822132)` | 实证 |
| 0 | 禁用（特殊图标） | `eu5.s0.o/p`：`if (item.getItemId() == 0) 图标走 zk.e(...)` | 实证 |

### 已确认的字符串 resId
| resId | hex | type | 语义 |
|---|---|---|---|
| 2131758447 | 0x7F100D6F | string(0x10) | 引用/相关（`o0.a` 190 行） |
| **2131758448** | 0x7F100D70 | string | **更多**（`m0.a/b`、`component.i`） |
| 2131758453 | 0x7F100D75 | string | 更多（变体，`o0.a` 442 行） |
| 2131758454 | 0x7F100D76 | string | 更多（变体，`m0.a/b`、`TextPreviewUI.ke`） |
| 2131758458 | 0x7F100D7A | string | 打开（`o0.a` 299 行） |
| 2131758462 | 0x7F100D7E | string | （`M0()==5` 场景） |
| **2131758469** | 0x7F100D85 | string | **拍一拍**（`c0.a`） |
| 2131758471 | 0x7F100D87 | string | （`viewitems.op`） |
| 2131758482 | 0x7F100D92 | string | （`m0.d` 溢出） |
| **2131774067** | 0x7F104A73 | string | **收藏**（`a9.S` 配 id 111） |
| 2131756026 | 0x7F1003FA | string | 消息调试（id 138） |
| 2131761892 / 2131778872 | — | string | 朗读（两种形态） |
| 2131821955 | 0x7F110583 | drawable(0x11) | 更多图标 |
| 2131822145 | 0x7F110641 | drawable | （103 用） |
| 2131822077 | 0x7F1105FD | drawable | 更多（122）图标 |
| 2131822132 | 0x7F110634 | drawable | 更多（136）图标 |
| 2131821902 | 0x7F11054E | drawable | 多选（134）图标 |
| 2131822158 | 0x7F11064E | drawable | 拍一拍（137）图标 |
| 2131821820 | 0x7F1104FC | drawable | 打开（171）图标 |
| 2131822160 | 0x7F110650 | drawable | 收藏（111）图标 |

> 资源 ID 公式：`0x7F` + type(0x10=string / 0x11=drawable) + entry。
> 微信每个版本重排资源表，**不要把 resId 写死在模块里**；需要时用 DexKit/资源反查，或直接用模块自己的 Drawable。

---

## 第 13 章 最小可用模块骨架（独立 Xposed，零第三方框架依赖）

> 依赖只有 `org.luckypray:dexkit:2.2.0`（含 `libdexkit.so`）+ Xposed API。
> **不依赖 LSPosed**，也不依赖任何 BSH/脚本引擎。

```java
package com.example.wxmenu;

import android.content.Context;
import android.content.ContextMenu;
import android.graphics.drawable.Drawable;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.ClassData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public final class MenuHook implements IXposedHookLoadPackage {

    // ========== 配置 ==========
    private static final String  PKG      = "com.tencent.mm";
    private static final int     MY_ID    = 0x7E000001;   // 自建 itemId，避开 100~190
    private static final String[] BAN_TEXT = { "拍一拍", "朗读" };

    // ========== 运行时解析结果（只存字符串，不存 Class） ==========
    private static String sBuilderCls, sBuilderName, sMenuCls;
    private static String sClickCls,   sClickName;
    private static Method sItemDataTag_d_getter;  // ps.d() : int
    private static volatile boolean sInstalled = false;

    @Override
    public void handleLoadPackage(LoadPackageParam lp) throws Throwable {
        if (!PKG.equals(lp.packageName)) return;
        if (!"com.tencent.mm".equals(lp.processName)) return;   // 只 hook 主进程
        new Thread(() -> resolveWithRetry(lp.appInfo.sourceDir), "wxmenu-resolve").start();
    }

    // ========== 第 8 章：DexKit 解析 + 重试 ==========
    private void resolveWithRetry(String apkPath) {
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                if (resolve(apkPath)) {
                    install();
                    XposedBridge.log("[WXMenu] hooks installed OK");
                    return;
                }
            } catch (Throwable t) {
                XposedBridge.log("[WXMenu] resolve fail #" + attempt + ": " + t);
            }
            try { Thread.sleep(3000L * (attempt + 1)); } catch (InterruptedException ignored) {}
        }
        XposedBridge.log("[WXMenu] GIVE UP: unsupported WeChat version");
    }

    private boolean resolve(String apkPath) {
        System.loadLibrary("dexkit");
        DexKitBridge dk = DexKitBridge.create(apkPath);

        // ── A5：菜单构建方法（唯一锚点）──
        var a5 = dk.findMethod().usingStrings("OnCreateContextMMMenux")
                 .getResultAsMethodMatcher();
        if (a5.isEmpty()) return false;
        MethodData m5 = a5.get(0);
        sBuilderCls  = m5.getClassName();
        sBuilderName = m5.getName();
        sMenuCls     = m5.getParamTypes()[0];          // ← MMMenu 类名
        String[] p5  = m5.getParamTypes();
        // sanity: p5[0] 必须有 size/removeItem
        Class<?> menuC = Class.forName(sMenuCls);
        menuC.getMethod("size"); menuC.getMethod("removeItem", int.class);
        menuC.getMethod("findItem", int.class);

        // ── A8：点击回调（2 个命中，按接口 + 字段过滤）──
        var a8 = dk.findMethod()
                 .usingStrings("context item select failed, null dataTag")
                 .paramTypes("android.view.MenuItem", "int")
                 .getResultAsMethodMatcher();
        for (MethodData m8 : a8) {
            ClassData c8 = m8.getClassData();
            if (c8.getInterfaces().toString().contains("v4")   // 宽松过滤，可再收紧
                    || c8.getDeclaredFields().size() > 0) {
                sClickCls = m8.getClassName(); sClickName = m8.getName(); break;
            }
        }
        if (sClickCls == null && !a8.isEmpty()) {
            sClickCls = a8.get(0).getClassName(); sClickName = a8.get(0).getName();
        }
        if (sClickCls == null) return false;

        // ── A10：ItemDataTag 的 d() ──
        // 从 A5 第三个参数 ContextMenuInfo 拿不到 ps，改从 A8 所在类字段反推
        for (Field f : Class.forName(sClickCls).getDeclaredFields()) {
            if (f.getType().getName().equals("com.tencent.mm.ui.chatting.viewitems.ps")) {
                sItemDataTag_d_getter = f.getType().getMethod("d");
                break;
            }
        }
        if (sItemDataTag_d_getter == null) {              // 兜底：直接按类名试
            try { sItemDataTag_d_getter = Class.forName("com.tencent.mm.ui.chatting.viewitems.ps").getMethod("d"); }
            catch (Throwable ignored) {}
        }
        return true;
    }

    // ========== 安装 Hook ==========
    private void install() throws Throwable {
        if (sInstalled) return;
        sInstalled = true;
        final Class<?> builder = Class.forName(sBuilderCls);
        final Class<?> menuC   = Class.forName(sMenuCls);
        final Class<?> clickC  = Class.forName(sClickCls);
        final Field     listF  = menuC.getField("d");  listF.setAccessible(true);

        // ── 1) 菜单构建后：删 + 注入（幂等）──
        XposedBridge.hookMethod(
            builder.getDeclaredMethod(sBuilderName, menuC, View.class, ContextMenu.ContextMenuInfo.class),
            new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        final Object menu   = p.args[0];
                        final View   anchor = (View) p.args[1];
                        final Object tag    = anchor.getTag();
                        if (tag == null || sItemDataTag_d_getter == null) return;
                        final int pos = (Integer) sItemDataTag_d_getter.invoke(tag);   // groupId

                        // 1.1 禁用：按标题文案删
                        @SuppressWarnings("unchecked")
                        List<MenuItem> list = (List<MenuItem>) listF.get(menu);
                        for (Iterator<MenuItem> it = list.iterator(); it.hasNext();) {
                            MenuItem mi = it.next();
                            CharSequence t = mi.getTitle();
                            if (t == null) continue;
                            String s = t.toString().trim();
                            for (String ban : BAN_TEXT) if (s.equals(ban)) { it.remove(); break; }
                        }
                        // 1.1b 也可按 id 删（按需）：menuC.getMethod("removeItem", int.class).invoke(menu, 171);

                        // 1.2 注入（幂等守卫）
                        if (menuC.getMethod("findItem", int.class).invoke(menu, MY_ID) != null) return;

                        Drawable icon = loadModuleIcon(anchor.getContext());
                        Method m = icon != null
                                ? menuC.getMethod("m", int.class, CharSequence.class, Drawable.class)
                                : menuC.getMethod("add", int.class, int.class, int.class, CharSequence.class);
                        Object item = (icon != null)
                                ? m.invoke(menu, MY_ID, "我的按钮", icon)
                                : m.invoke(menu, pos, MY_ID, 0, "我的按钮");
                        // 想控顺序：list.add(1, (MenuItem) item);
                        // 想强制第 2 行：setBooleanField(item, "D", true);
                    } catch (Throwable t) {
                        XposedBridge.log("[WXMenu] post-build: " + t);
                    }
                }
            });

        // ── 2) 点击拦截：我的 id 一律短路，防止落到 qi#t0 弹「删除消息」──
        XposedBridge.hookMethod(
            clickC.getDeclaredMethod(sClickName, MenuItem.class, int.class),
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        MenuItem mi = (MenuItem) p.args[0];
                        if (mi == null || mi.getItemId() != MY_ID) return;

                        onMyButton(p.thisObject, mi);                 // 你的业务

                        // ★ 把 ItemDataTag 置空 → 原方法第一行 if(d==null) return
                        Object tag = p.thisObject.getClass().getDeclaredFields()[0].get(p.thisObject);
                        for (Field f : p.thisObject.getClass().getDeclaredFields()) {
                            if (tag == null || f.getType().isInstance(tag)) { f.set(p.thisObject, null); break; }
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("[WXMenu] click intercept: " + t);
                    }
                }
            });
    }

    private Drawable loadModuleIcon(Context ctx) {
        try {
            Context m = ctx.createPackageContext("com.example.wxmenu",
                    Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
            return androidx.core.content.res.ResourcesCompat.getDrawable(m.getResources(), R.drawable.my_icon, null);
        } catch (Throwable t) { return null; }
    }

    private void onMyButton(Object r0, MenuItem mi) {
        XposedBridge.log("[WXMenu] >>> MY BUTTON CLICKED <<<");
        // 你的业务：Toast / 拉 Activity / 复制消息 / 发 HTTP …
    }
}
```

### 部署要点
| 项 | 值 |
|---|---|
| `AndroidManifest.xml` | `<meta-data android:name="xposedmodule" android:value="true"/>`、`xposedminversion`、`xposeddescription` |
| `assets/xposed_init` | 内容 = `com.example.wxmenu.MenuHook` |
| 依赖 | `org.luckypray:dexkit:2.2.0`（含 `libdexkit.so`，需 `System.loadLibrary("dexkit")`） |
| 进程过滤 | `packageName == "com.tencent.mm"` 且主进程 |
| 解析时机 | 子线程 + 3s/6s/12s/18s 重试，**不要**在 `handleLoadPackage` 同步解析 |
| 缓存 | `SharedPreferences` 存「类名+方法名+描述符」，key = `versionCode_apkMd5` |
| 失败处理 | 4 次仍失败 → 关闭功能 + 上报 versionCode（见 8.4） |

---

## 附录 A：方法论沉淀（做微信其它 UI 时直接复用）

1. **入口优先级**：字符串锚点 > 类名模糊搜索 > 资源入口 > 精确匹配。
2. **别搜"组件基类"，要搜"日志 tag"**：`MMPopupMenu` 不继承 `PopupWindow`，搜子类 0 命中；
   搜 `"MicroMsg.MMPopupMenu"` 1 秒命中。
3. **微信把原始类名以字符串常量保留在热插桩里**，这是**最好的 DexKit 锚点**，比 log tag 还稳：
   `ym0.a.b("com/tencent/mm/ui/chatting/viewitems/ChattingItem$LongClickListener", ...)`
4. **Android 组件搜索要覆盖 6 种引用**：FIELD / RETURN / PARAM / EXTENDS / IMPLEMENTS / INVOKE。
   本例关键接口 `kj5.p4` 只有 IMPLEMENTS 一种引用（53 个实现）。
5. **混淆短名 ↔ 真实名的桥**：`view_strings` 里的 `[LOG] MicroMsg.XXX` 与 `[TEXT] com/tencent/mm/...` 两种标签要分开看。
6. **资源 ID 反查**：`0x7F` + type(`0x10`=string / `0x11`=drawable) + entry。
   可在 `resources.arsc` 的 value pool 里筛 `Res_value.dataType == 0x03` 得到全部字符串。
   ⚠️ 每个版本重排，**不可写死**。
7. **找"删菜单项"的官方范式**：搜 `com.tencent.mm.repairer.config.*` 包，
   里面每个 `RepairerConfigXxx.getName()` 都是一个人类可读的功能开关名
   （如「消息长按不出打开菜单」），可直接当语义词典。

## 附录 B：本文所有实证结论的来源
**字符串池检索**
`MicroMsg.MMPopupMenu` / `com/tencent/mm/ui/widget/menu/MMPopupMenu` / `OnCreateContextMMMenux` /
`context item select failed, null dataTag` / `context item select failed, null msg` /
`MMRevoke.MicroMsg.ChattingUI.MessBoxComponent` / `dealWithLongClick` /
`clicfg_msg_menu_open_disable_android` / `com/tencent/mm/ui/chatting/viewitems/ChattingItem$LongClickListener` /
`openContextMenu posX:%s, posY:%s` / `open menu but tag is null` / `chat_sys_msg_del_btn` /
`fireWork_dark.gif` / `group_msg_set_top_bubble_set` / `favorite`

**接口/实现枚举**
`implements kj5.p4` → 53 个实现；`implements kj5.v4` → 27 个调用方；
`implements ak5.e1` → **唯一** `component.qi`；`implements android.view.ContextMenu` → `kj5.i4`；
`implements android.view.MenuItem` → `kj5.j4`；`implements android.view.Menu` → `o.j0` / `g3.a`（废弃路径）

**Smali 逐行**
`eu5.s0.f`(43) / `.n`(273) / `.p`(2889) / `.o`(431) / `.m` / `.c` / `.a` / `<init>`(102) / `.i`(48) / `.j`(48)；
`viewitems.m0.g`(483) / `.onLongClick`；`viewitems.o0.a`(2537)；
`component.qi.t0`(3457)；`kj5.i4`(440 全类)；`kj5.j4`(316 全类)；`eu5.r0`(83 全类)

**Jadx 全文**
`viewitems.p0` / `m0$$a` / `q0`(126) / `r0` / `t0` / `s0` / `o0` / `m0` / `b0`(方法清单) / `ps` / `ap` /
`viewitems.c0` / `viewitems.eo` / `viewitems.op` / `viewitems.a9` / `chatting.ke` /
`component.k`(80 行) / `gk5.d`(27 字段) / `am5.d` / `hn5.*` / `gj5`…
