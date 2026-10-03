# 微信聊天对话气泡替换 —— 完整逆向分析报告

> 分析对象：`com.tencent.mm`（base.apk，`/data/app/.../com.tencent.mm-*/base.apk`）
> 分析工具：LSPilot（DexKit 定位 + jadx 反编译 + baksmali 二级核查）
> 结论可信度：所有关键结论均经 **Java 反编译 + Smali 双层核查**，见文末「二次核查记录」

---

## 0. 结论速览（TL;DR）

1. 本版本微信的聊天界面全面启用了 **X2C**（XML 布局 → Java 代码化构建），**文本气泡的背景不是在 XML 里设置的，而是在 X2C 生成的 Java 类里通过包装方法设置的**。
2. 气泡背景的**唯一设置点**是包装方法 `kw5.i0.f(Context, View, String, Drawable)`，其内部就是一行 `view.setBackground(drawable)`。
3. 气泡 Drawable 的获取经过包装方法 `kw5.g.r(Context, View, String, String value, int resId)`，其中 `value` 就是形如 `"@drawable/chatfrom_bg"` 的**原始资源引用字符串**（X2C 编译器嵌入，跨版本稳定），最终落到 `ke5.a.i(Context, int)` → `context.getResources().getDrawable(resId)`。
4. **最佳 Hook 点：`kw5.g.r`，判断第 4 个参数 `value` 是否为气泡资源名，是则返回自定义 Drawable。** 按资源名匹配，不依赖会随版本变化的资源 ID，且天然覆盖所有使用该资源的布局。

整条替换链路：

```
ChattingUI（聊天页 Activity）
└─ 消息列表适配器（混淆类）→ ChattingItem（混淆类，com.tencent.mm.ui.chatting.viewitems.*）
   └─ LayoutInflater.from(ctx).inflate(R.layout.chatting_item_from /*0x7F0E03CB*/, parent, false)
      └─ com.tencent.mm.ui.hd  a(int resId, ViewGroup, boolean)   ← "MicroMsg.MMLayoutInflater"，X2C 感知的 LayoutInflater
         ├─ [1] kw5.e1  X2CLayoutCachePool.a(ctx,resId,parent,attach)   ← 视图缓存池，命中直接返回已构建视图
         ├─ [2] Finder 预加载提供者（仅 Finder 场景，可忽略）
         ├─ [3] kw5/f0.f(resId) → kw5/e0（X2C 注册表）→ 生成类实例（本版本为 gm.g / gm.i / gm.f / gm.h）
         │      └─ kw5/g.b(ctx, parent, attach) → gm.g.c(Context)   ← 纯 Java 构建整棵视图树
         │           └─ kw5/g.r(ctx, view, "com.tencent.mm.ui.widget.MMNeat7extView",
         │                        "@drawable/chatfrom_bg", 2131231925)   ← 气泡 Drawable 解析（包装）
         │                └─ ke5.a.i(ctx, 2131231925) → ctx.getResources().getDrawable(2131231925)
         │           └─ kw5/i0.f(ctx, mMNeat7extView, "...", drawable)   ← 气泡背景应用（包装）
         │                    └─ view.setBackground(drawable)
         └─ [4] 兜底：X2C 关闭或无生成类 → super.inflate() → 解析二进制 XML（背景来自 XML 的 android:background）
```

---

## 1. 目标环境判定

| 项目 | 值 |
|---|---|
| 包名 | `com.tencent.mm` |
| 聊天页 Activity | `com.tencent.mm.ui.chatting.ChattingUI`（及 `variants.ChattingMainUI` 等变体） |
| 布局方案 | **X2C**（`com.tencent.mm.view.x2c.*`，含 `RepairerConfigX2COpenFlag` 开关，默认开） |
| 类名混淆 | 重度混淆（X2C 运行时类在 `kw5.*`，聊天布局生成类在 `gm.*`，资源工具类在 `ke5.*`） |
| 资源名混淆 | arsc 资源名不可枚举（`list_resources` 无法列出任何 drawable），**按资源名做 XResources 替换不可靠，必须按 resId** |
| X2C 生成类总量 | 仅 24 个（`gm.a` ~ `gm.x`），其中聊天相关 8 个 |

---

## 2. 气泡资源清单（本构建实测值）

### 2.1 文本消息气泡（核心目标）

| 方向 | 布局 | X2C 生成类 | Drawable 资源名 | resId（十进制 / 十六进制） | 应用到的视图 | 视图 id | 应用方式 |
|---|---|---|---|---|---|---|---|
| 收到 | `chatting_item_from` | `gm.g` | `@drawable/chatfrom_bg` | **2131231925 / 0x7F0804B5** | `com.tencent.mm.ui.widget.MMNeat7extView`（消息正文） | **2131365751 / 0x7F0A0F77** | `kw5/i0.f` → `setBackground` |
| 发出 | `chatting_item_to` | `gm.i` | `@drawable/chatto_bg` | **2131232060 / 0x7F08053C** | 同上（同一 id） | 同上 | 同上 |

关键代码（`gm.g.c()`，jadx 行 109）：

```java
i0Var.f(context, mMNeat7extView, "com.tencent.mm.ui.widget.MMNeat7extView",
    r(context, mMNeat7extView, "com.tencent.mm.ui.widget.MMNeat7extView",
      "@drawable/chatfrom_bg", 2131231925));
```

`gm.i.c()`（jadx 行 213）为对称结构，仅资源名/resId 换成 `chatto_bg` / 2131232060。

### 2.2 分享/AppMsg 消息气泡（同一套链路，一并处理）

| 方向 | 布局 | X2C 生成类 | Drawable | resId | 应用视图 | 视图 id | 方式 |
|---|---|---|---|---|---|---|---|
| 收到 | `chatting_item_from_appmsg` | `gm.f` | `@drawable/chat_from_mask_bg` | **2131231853 / 0x7F08046D** | `X2CFrameLayout`（appmsg 内容容器） | **2131365741 / 0x7F0A0F6D** | `i0.f` 背景 **+ `i0.n` 前景**（同一 drawable 用两次） |
| 发出 | `chatting_item_to_appmsg` | `gm.h` | `@drawable/chatto_bg_app` | **2131232062 / 0x7F08053E** | `X2CLinearLayout`（同上 id 2131365741） | 2131365741 | `i0.f` 背景 |
| 发出 | 同上 | `gm.h` | `@drawable/chat_to_mask_bg` | **2131231907 / 0x7F0804A3** | 同上 | 同上 | `i0.n` **前景**（气泡尾巴/遮罩） |

> 注意：AppMsg 气泡有**前景 mask**（`i0.n` → `View.setForeground`）。只换背景不换 mask 会出现尾巴/边框与自定义气泡不匹配，需一并替换或置空。

### 2.3 布局 resId 速查

| 布局 | resId |
|---|---|
| chatting_item_from | 2131624907 / 0x7F0E03CB |
| chatting_item_to | 2131624988 / 0x7F0E041C |
| chatting_item_from_appmsg | 2131624889 / 0x7F0E03B9 |
| chatting_item_to_appmsg | 2131624972 / 0x7F0E040C |
| chatting_item_avatar_from（include） | `gm.d` |
| chatting_item_avatar_to（include） | `gm.e` |

---

## 3. 逐层链路详解（含类/方法签名）

### L0 — 业务层：聊天页与消息 Item（功能层，类名全混淆）

- `ChattingUI` 及 `variants.*` 承载消息列表；适配器与各消息类型的 `ChattingItem`（`com.tencent.mm.ui.chatting.viewitems.*`，本构建已混淆为 `a/a0/aa/...`）负责按消息类型创建/复用 Item 视图。
- Item 视图统一通过 `LayoutInflater.from(ctx).inflate(layoutResId, parent, false)` 创建（文本消息即 `chatting_item_from`/`chatting_item_to`）。
- 本层**不重复设置气泡背景**（已核实：整个 DEX 中只有 `gm.g/gm.i` 引用 `chatfrom_bg/chatto_bg` 字符串），气泡完全在视图构建时定型。多选、引用（pat/quote）、合并转发均不改变气泡背景。

### L1 — 拦截层：`com.tencent.mm.ui.hd`（MicroMsg.MMLayoutInflater）

微信自定义 `LayoutInflater`（`cloneInContext()` 返回自身），重写 `inflate(int, ViewGroup, boolean)` → `a(int, ViewGroup, boolean)`（smali：`a(ILandroid/view/ViewGroup;Z)Landroid/view/View;`）。`a()` 的分发逻辑（smali 实测）：

1. `ThreadLocal q0.g` 未处于预创建态 且 `q0.f`（X2C 总开关）为 true：
   `q0.a().b().b()` → `kw5/z` → `z.a(ctx, resId, parent, attach)`（即 **X2C 视图缓存池** `kw5.e1.a`）。
2. 未命中且为 Finder 场景：走 `tx2/j`（FinderLayoutPreloadProvider）预加载队列。
3. 主路径：`q0.a().b().f(resId)` → `kw5/e0`（X2C 布局对象，即 `gm.*` 实例）：
   - `parent != null` → `kw5/g.b(ctx, parent, attach)`（内部 `c(ctx)` 建树后挂到 parent）；
   - `parent == null` → 直接 `kw5/g.c(ctx)`。
   - 异常时打日志 `"X2CCreateLayoutView %s"`。
4. 仍为 null → `super.inflate(resId, parent, attach)`：**兜底走二进制 XML**（此时背景来自 XML 的 `android:background`，经框架 `Resources` 加载）。

> `kw5/q0.f` 为 X2C 总开关（volatile boolean，由 X2C 初始化注入；`q0.c` 为真实 provider，默认为空操作实现 `kw5/n`，其 `f(int)` 直接返回 null → 走 XML 兜底）。

### L2 — 缓存层：`kw5.e1`（MicroMsg.X2C.X2CLayoutCachePool）

- `a(Context, int resId, ViewGroup, boolean)`：按 `context.hashCode + "_" + resId + "_" + (parent!=null?1:0)` 为 key 从 `ConcurrentHashMap` 取已构建视图队列，命中则直接从 `tag(2131391881)` 恢复 MarginLayoutParams 并挂载。
- `b()`：`clearLayoutCache` 清空全部缓存；`d(ro7)`：投递预加载任务（`RepairerConfigLauncherUIX2CPreload` 会在 LauncherUI 预构建聊天布局）。
- **对替换方案的影响**：缓存池会长期持有已构建的 Item 视图。若你的模块支持「运行时动态开关气泡」，仅替换 Drawable 不会刷新已缓存视图——需要调 `kw5.e1.b()` 清缓存，或遍历缓存视图重设背景。

### L3 — 注册表层：`kw5/f0` / `kw5/e0`

- `kw5/f0`（provider 接口，实例在 `q0.c`）：`b()→kw5/z`（视图缓存池）、`c()→kw5/i1`（Drawable 缓存）、`d()→kw5/y`（按 Activity 的 X2C 策略）、`f(int resId)→kw5/e0`（**resId → X2C 生成类对象**）。
- `kw5/e0`：`a()→String`（布局名，如 `"chatting_item_from_x2c"`）、`getLayoutId()→int`（对应 XML 布局 id，兜底用）。
- 生成类（`gm.*`）继承 `kw5/g`，全 APK 无任何直接引用（反射/注册表方式实例化）。

### L4 — 构建层：`gm.g` / `gm.i`（X2C 生成类，纯 Java 建树）

以 `gm.g`（`chatting_item_from_x2c`）为例，`c(Context)` 用代码重建整个 XML 视图树：

- 根：`X2CLinearLayout`（vertical）；
- `<include>`：`LayoutInflater.from(ctx).inflate(2131624800, root, true)`（头部/头像区，另有 `chatting_item_avatar_from` = `gm.d`）；
- 内容区：`X2CLinearLayout`(weight=1) → `X2CTextView`（时间，id 2131366078）→ `X2CRelativeLayout` → **`MMNeat7extView`（消息正文，id 2131365751，背景 chatfrom_bg）** + `AnimImageView` + 多个 `ViewStub`（按消息类型 inflated 具体内容布局，如 0x7F0E041D、0x7F0E0372）。
- 所有 XML 属性都通过 `kw5/i0` 的字母方法应用（如 `p`=setId、`N`=setTextColor、`O`=setTextSize、`E`=setMaxWidth、`d/c`=padding/margin、`P`=setVisibility、`f`=setBackground、`n`=setForeground、`K`=setImageDrawable……）。

### L5 — 资源解析包装层：`kw5.g.r(...)`

```
public final Drawable r(Context ctx, View view, String name, String value, int resId)
```

- `value` 以 `#`/`@color/`/`@android:color/` 开头 → 返回 `ColorDrawable(p(...))`；
- `resId == 0` → null；
- 否则：provider 的 Drawable 缓存（`kw5/i1`，`a(resId)` 命中则 `constantState.newDrawable()`）→ 未命中调 **`ke5.a.i(ctx, resId)`** 并缓存。
- 也就是说：**`value`（原始资源引用字符串）和 `resId`（编译期兜底 id）在这里同时可得**——这是按名字匹配的锚点。

### L6 — 资源加载包装层：`ke5.a.i(Context, int)`（MicroMsg.ResourceHelper）

```java
public static Drawable i(Context context, int i) {
    if (context == null) { Log.e("MicroMsg.ResourceHelper", "get drawable, resId %d, but context is null", i); return null; }
    return context.getResources().getDrawable(i);
}
```

smali 确认 `kw5/g.r` 内三处调用 `Lke5/a;->i(Landroid/content/Context;I)Landroid/graphics/drawable/Drawable;`。

### L7 — 应用包装层：`kw5.i0.f(...)` / `kw5.i0.n(...)`

```java
public final void f(Context context, View view, String str, Drawable drawable) {
    view.setBackground(drawable);          // ← 气泡背景最终落点
}
public final void n(Context context, View view, String str, Drawable drawable) {
    if (h.c(23)) view.setForeground(drawable);   // ← AppMsg 气泡前景 mask
}
```

`kw5/i0` 是 Kotlin object（单例存于 `kw5/j0.a` 静态字段），**全 App 所有 X2C 视图的背景/前景都走这两个方法**（不仅聊天）。

---

## 4. Hook 方案（按鲁棒性排序，附代码）

> 以下类名（`kw5.*`/`gm.*`/`ke5.*`）为本构建混淆名，**其他构建会变**，定位方法见第 5 节。

### 方案 A（推荐）：Hook X2C 资源解析 `kw5.g.r`，按资源名替换

优点：按 `"@drawable/chatfrom_bg"` 这类**原始资源引用字符串**匹配（X2C 编译器嵌入、抗混淆、抗 resId 变化）；一处 hook 覆盖文本气泡 + AppMsg 气泡 + 未来新增布局；在 Drawable 缓存之前返回，无缓存残留问题。

```java
// resId 仅用于日志/兜底判断，主要匹配 value 字符串
private static final Set<String> BUBBLE_RES = new HashSet<>(Arrays.asList(
    "@drawable/chatfrom_bg", "@drawable/chatto_bg",
    "@drawable/chat_from_mask_bg", "@drawable/chatto_bg_app", "@drawable/chat_to_mask_bg"));

public void hookBubbleResolver(ClassLoader cl) throws Throwable {
    Class<?> resCls = Class.forName("kw5.g");            // ← 每版本需按锚点重新定位
    XposedHelpers.findAndHookMethod(resCls, "r",
        Context.class, View.class, String.class, String.class, int.class,
        new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            String value = (String) param.args[3];       // "@drawable/chatfrom_bg"
            if (value == null || !BUBBLE_RES.contains(value)) return;
            View target = (View) param.args[1];
            Drawable custom = pickBubble(value, target); // 你的气泡图（建议按 view 方向/上下文选择）
            if (custom != null) param.setResult(custom);
        }
    });
}
```

`pickBubble` 建议逻辑：
- `chatfrom_bg` / `chat_from_mask_bg` → 收到侧气泡；
- `chatto_bg` / `chatto_bg_app` → 发出侧气泡；
- mask 类（`chat_to_mask_bg`、`chat_from_mask_bg` 作为前景时）→ 返回 `null`/透明或配套 mask；
- 用 `target` 的上下文（父容器 id、视图 id 2131365741/2131365751）做二级校验，避免误伤同名资源。

### 方案 B：Hook `ke5.a.i(Context, int)`，按 resId 替换

```java
XposedHelpers.findAndHookMethod("ke5.a", cl, "i", Context.class, int.class, new XC_MethodHook() {
    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        int resId = (int) param.args[1];
        if (resId == 2131231925)      param.setResult(fromBubble);   // chatfrom_bg
        else if (resId == 2131232060) param.setResult(toBubble);     // chatto_bg
        // 2131231853 chat_from_mask_bg / 2131232062 chatto_bg_app / 2131231907 chat_to_mask_bg
    }
});
```

resId 会随构建变化；可在方案 A 的 hook 里**运行时捕获** `value → resId` 映射后动态维护，不必硬编码。

### 方案 C：Hook `View.setBackground(Drawable)`，按视图特征替换

不依赖 X2C 内部类（将来微信关掉 X2C 走 XML 也生效），但需要区分 from/to：

```java
XposedHelpers.findAndHookMethod(View.class, "setBackground", Drawable.class, new XC_MethodHook() {
    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        View v = (View) param.thisObject;
        if (!"com.tencent.mm.ui.widget.MMNeat7extView".equals(v.getClass().getName())) return;
        // 二级判断：v.getId() == 2131365751（聊天正文）；from/to 可用父容器方向或先经方案B捕获的 drawable 比对
        if (v.getId() != 2131365751) return;
        param.args[0] = currentBubbleFor(v);
    }
});
```

AppMsg 气泡同理 hook `setForeground`，判断 `v.getId() == 2131365741`。

### 方案 D：资源级替换（XResources），覆盖所有路径（含二进制 XML）

```java
// resId 需按构建更新（本构建值见第 2 节；资源名已被微信混淆，按名替换基本不可行）
xres.setReplacement(0x7F0804B5 /*chatfrom_bg*/, new XModuleResources(modRes, R.drawable.my_from_bubble));
xres.setReplacement(0x7F08053C /*chatto_bg*/,   new XModuleResources(modRes, R.drawable.my_to_bubble));
```

优缺点：能覆盖 XML 兜底路径与非 X2C 消息类型；但 resId 随构建变，且无法在运行时动态切换（需配合重载 Activity）。

---

## 5. 跨版本锚点定位法（类名/resId 会变时）

1. **找 X2C 生成类**：`search_strings("chatfrom_bg")` → 命中 `gm.g`（唯一）；`search_strings("chatto_bg")` → `gm.i`/`gm.h`。
2. **找解析包装类**：生成类的父类即 `kw5/g`（含 `r(Context,View,String,String,int)→Drawable`）；其 smali 中调用 `Lke5/a;->i(...)`。
3. **找应用包装类**：`kw5/i0` 中签名 `(Landroid/content/Context;Landroid/view/View;Ljava/lang/String;Landroid/graphics/drawable/Drawable;)V` 且方法体仅 `view.setBackground(drawable)` 的方法（本构建为 `f`）；前景版为 `n`。
4. **找资源加载类**：`search_strings("MicroMsg.ResourceHelper")` → `ke5.a`。
5. **找 Inflater**：`search_strings("MicroMsg.MMLayoutInflater")` / `"X2CCreateLayoutView"` → `com.tencent.mm.ui.hd`。
6. **找缓存池**：`search_strings("MicroMsg.X2C.X2CLayoutCachePool")` → `kw5.e1`。
7. **运行时兜底枚举**：hook `kw5.g.r` 打印所有 `value`，在真机聊天页翻一圈，即可拿到**全部**气泡/消息类型资源名（含其它消息类型若走 X2C）；对 XML 路径则 hook `android.content.res.Resources#getDrawable` 打印 resId 辅助定位。

---

## 6. 注意事项与坑

1. **9-patch**：`chatfrom_bg/chatto_bg` 历史上是 `.9.png`。自定义气泡请继续用 9-patch（或自行给 `MMNeat7extView` 设 padding），否则文字会贴边/被裁。X2C 代码已给正文设置了显式 padding（约 7dp 横 / 1.5dp 下），9-patch 的 content padding 会叠加。
2. **夜间模式**：原 drawable 带 `-night` 变体时，替换图应按 `Configuration`/主题返回对应版本。
3. **前景 mask**：AppMsg 气泡（`chat_to_mask_bg`、`chat_from_mask_bg`）有 `setForeground`，只换背景会导致尾巴/边框残留，需一并处理。
4. **X2C 视图缓存池**（`kw5.e1`）会长期复用已构建视图；动态开关气泡需清缓存（`kw5.e1.b()`）或对已缓存视图补设背景。
5. **Drawable 缓存**（`kw5/i1`，provider `c()`）按 resId 缓存；在 `kw5.g.r` 层替换可天然绕过，在 `ke5.a.i` 层替换也无需理会（缓存的是你返回的 drawable）。
6. **`kw5/i0.f` 是全 App 所有 X2C 视图的背景入口**，直接 hook 它会被高频调用，务必用资源名/视图特征过滤，注意性能。
7. **资源名混淆**：本 APK arsc 资源名不可枚举，`getIdentifier("chatfrom_bg", ...)` 未必可用——优先用「value 字符串匹配」或「运行时捕获 resId」。
8. **兜底路径**：X2C 关闭（`RepairerConfigX2COpenFlag`）时走二进制 XML，此时 `kw5.*` hook 不触发，需方案 C/D。
9. **`MMNeat7extView` 类名未混淆**（`com.tencent.mm.ui.widget.MMNeat7extView`），可作为稳定锚点；但它也用于其它模块（如翻译 `improve_translate_layout`），需配合视图 id / 父容器判断。

---

## 7. 二次核查记录（导出前逐项验证）

| # | 结论 | 核查方式 | 结果 |
|---|---|---|---|
| 1 | 文本气泡背景 = `chatfrom_bg`(2131231925) 作用于 `MMNeat7extView`(id 2131365751) | jadx 反编译 `gm.g.c()` 行 109 + `view_strings` + smali const-string | ✔ 双重确认 |
| 2 | 发出侧 = `chatto_bg`(2131232060)，同一视图 id | jadx `gm.i.c()` 行 213 | ✔ |
| 3 | AppMsg：`chat_from_mask_bg`(2131231853) 背景+前景 / `chatto_bg_app`(2131232062) 背景 / `chat_to_mask_bg`(2131231907) 前景，容器 id 2131365741 | jadx `gm.f` 行 124-125、`gm.h` 行 200-201 | ✔ |
| 4 | `kw5/i0.f` 即 `view.setBackground(drawable)`；`n` 即 `setForeground` | 直接反编译两方法 | ✔ |
| 5 | `kw5/g.r` 经 `ke5/a.i` → `context.getResources().getDrawable(resId)` | jadx + smali（`Lke5/a;->i` 三处调用点） | ✔ |
| 6 | Inflater 链路：`hd.inflate → hd.a → (kw5.e1 缓存) → f0.f(resId) → kw5/g.b → kw5/g.c → gm.g.c`；兜底 `super.inflate` | `find_caller(kw5.g,"c"/"b")` 均指向 `com.tencent.mm.ui.hd.a` + hd.a smali 657-795 行 | ✔ |
| 7 | 全 DEX 仅 `gm.g/gm.i` 引用 `chatfrom_bg/chatto_bg`（无其它代码重复设置气泡背景） | `find_usage` 两资源名 | ✔ |
| 8 | X2C 生成类共 24 个（gm.a~gm.x），聊天相关 8 个；其余消息类型内容布局走二进制 XML | `search_strings("_x2c")` 全量枚举 + 各 `a()` 布局名核对 | ✔ |
| 9 | 布局 resId：from=0x7F0E03CB / to=0x7F0E041C / from_appmsg=0x7F0E03B9 / to_appmsg=0x7F0E040C | 各类 `getLayoutId()` smali | ✔ |
| 10 | 资源名不可枚举（arsc 混淆）→ 资源名替换方案不可靠 | `list_resources` 各类型均无结果 | ✔ |

### 遗留不确定性（如实说明）

- 适配器 / 各 `ChattingItem` 具体混淆类名未逐一认定（类名全混淆）；但不影响 hook 点——气泡背景不在该层设置。
- 图片/语音/视频等**非文本消息**的气泡 drawable 未在 DEX 字符串中出现（其内容布局走二进制 XML 或独立布局）。如需全覆盖，按第 5 节第 7 条在真机运行时枚举；对 XML 路径用方案 C/D。
- 所有 resId / 混淆类名仅对**本 base.apk 构建**有效，换版本必须按第 5 节重新锚定。

---

*报告生成：LSPilot AI 分析助手 · 基于 DexKit + jadx + baksmali 交叉验证*
