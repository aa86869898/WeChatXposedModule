# 微信长按菜单构建方法反编译材料（MessageMenuHook 修复用）

> 生成：LSPilot AI 反编译分析 · 目标：com.tencent.mm (微信) base.apk，268MB / 17 dex
> 用途：修复 MessageMenuHook `OnCreateContextMMMenux` anchor not found

---

## 1. 结论速览

- 锚点字符串引用**类未变**：`com.tencent.mm.ui.chatting.viewitems.o0`
- **真实方法**：`o0.a(Lkj5/i4; Landroid/view/View; ContextMenu$ContextMenuInfo;)V`
- **MMMenu 类当前混淆名为 `kj5.i4`**（实现 `android.view.ContextMenu`）
- MMMenu 的菜单项 List 字段 **仍是 `d: Ljava/util/List;`（运行期 ArrayList），未变**
- **失效根因**：旧 DexKit 缓存签名条件用“参数类型 = 旧 MMMenu 类”，而当前第一参数已是 `kj5.i4` → 签名比对失败 → 虽命中 2 个候选（类 o0 + 方法 o0.a），最终 anchor not found

---

## 2. 字符串锚点分析

`OnCreateContextMMMenux` 全包**仅 1 个类 + 1 个方法**引用（`find_usage` 与 `search_strings` 交叉一致）：

| 类型 | 位置 |
|---|---|
| CLASS | `com.tencent.mm.ui.chatting.viewitems.o0` |
| METHOD | `com.tencent.mm.ui.chatting.viewitems.o0.a` |

方法内位置（o0.a 第 568 行）：

```java
Log.i(str3, "MicroMsg.ChattingItem",
      "OnCreateContextMMMenux: position:%s, msgid:%s",
      new Object[]{Integer.valueOf(d), Long.valueOf(e9Var2.getMsgId())});
```

⚠️ 注意：该字符串是**方法内的日志文本，不是方法名/锚点本身**，不能单独作为定位条件。

---

## 3. 类 `com.tencent.mm.ui.chatting.viewitems.o0`

类只有 2 个方法：

```
void <init>(m0, b0, gk5.d)                                    // 构造
public void a(Lkj5/i4; Landroid/view/View; ContextMenu$ContextMenuInfo;) V   // 上下文菜单构建
```

`o0.a` 共 598 行（jadx 行 112~709），是本轮分析核心。

---

## 4. MMMenu = `kj5.i4`（关键）

- 继承 Object，实现 `android.view.ContextMenu`，无子类
- **字段（共 3 个，已确认无遗漏）**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `d` | `Ljava/util/List;` | **菜单项列表（运行期 ArrayList）** ← hook 目标 |
| `e` | `Ljava/lang/CharSequence;` | 标题文本 |
| `f` | `Landroid/content/Context;` | 上下文 |

- 方法集（60 个）全部是菜单 API：`add / addSubMenu / findItem / removeItem / size / setHeaderTitle / v(MenuItem) / clear / close / hasVisibleItems / performIdentifierAction ...`

**铁证：`kj5.i4.add(int)` 反编译（93 行 36-41）**

```java
public MenuItem add(int i) {
    j4 j4Var = new j4(this.f, 0, 0);   // f = Context
    j4Var.t = i;                        // 菜单项 id
    ((ArrayList) this.d).add(j4Var);    // 存入 d 字段（ArrayList）
    return j4Var;
}
```

---

## 5. 菜单项类 `kj5.j4`（MenuItem 实现）

共 30 字段，关键项：

| 字段 | 类型 | 说明 |
|---|---|---|
| `t` | `I` | 菜单项 id（add 中赋值） |
| `A` | `String` | |
| `i` / `m` / `o` | `CharSequence` | 标题/副标题文本 |
| `g` / `h` / `n` | `I` | id / 序号等 |
| `d` / `e` / `f` / `D` / `E` | `Z` | 开关类标志 |
| `G` | `Drawable` | 图标 |
| `C` | `Context` | |
| `H` / `I` / `J` | `kj5.o4 / kj5.x4 / kj5.w4` | 子对象 |
| `p` | `TextUtils$TruncateAt` | |

---

## 6. `o0.a` 菜单构建逻辑（已验证摘要）

- 菜单项填充：`i4Var.add(d, <itemId>, 0, <resString>)`、`i4Var.c(d, <itemId>, 0, <resString>, <iconResId>)`
- 菜单项调整：`findItem(id)` / `removeItem(id)` / `size()` / `v(MenuItem)`
- **第 572 行直取 List 字段**：
  ```java
  List list22 = i4Var.d;   // 菜单项列表
  ...
  LinkedList linkedList22 = new LinkedList();
  arrayList = (ArrayList) list;   // 转 ArrayList 后截断前 4 项等操作
  ```
- ⚠️ 注意：众多 `add/c` 调用里的 `d` 是**消息位置 int**（`int d = apVar.d()`），不是 List 字段，勿混淆。

---

## 7. 失效根因（修正版）

你的假设是“MMMenu 的 List 字段变了” → **实测字段没变，仍是 `d: List`**。
真正变的是：**MMMenu 类被混淆成 `kj5.i4`**，导致：
1. 字符串锚点命中 2 个候选（类 o0 + 方法 o0.a）✓
2. 但按缓存中的旧签名（第一参数类型 = 旧 MMMenu 类）过滤 `o0.a` 时匹配失败 → `anchor not found` ✗

---

## 8. 插件修复建议（MessageMenuHook 直接可改）

1. **DexKit 方法匹配**（二选一）：
   - 稳：`findMethod().inClass(o0类名).name("a").paramCount(3)` —— 当前 `o0` 只有 2 个方法，`a` 是唯一 3 参方法
   - 显式：参数类型 `["Lkj5/i4;", "Landroid/view/View;", "Landroid/view/ContextMenu$ContextMenuInfo;"]`
2. **`findListField` 保持找 `d: Ljava/util/List;` 不变**，hook 后 `i4Var.d` 即菜单项列表
3. 字符串 `OnCreateContextMMMenux` 可保留作二次校验，但**勿作为唯一锚点条件**

---

## 9. 版本号

- 当前 dex 内未直接读到明文 `8.0.x`（DEX 字符串池、Manifest string pool 均未命中）
- 历史分析文档记录 **8.0.78**（`WeChat_8.0.78_Silk_DeepDive.md`，可能为旧版本）
- 电脑端确认命令：`aapt dump badging base.apk | grep versionName` 或 `apkanalyzer manifest version-name base.apk`

---

## 10. 关键证据来源（工具记录）

| 项目 | 证据 |
|---|---|
| 锚点字符串唯一性 | `search_strings("OnCreateContextMMMenux")` → 2 条；`find_usage` → CLASS o0 + METHOD o0.a |
| 方法签名 | `decompile_class_methods_only(o0)` → `a(Lkj5/i4;Landroid/view/View;ContextMenu$ContextMenuInfo;)V` |
| MMMenu 类身份 | `class_hierarchy(kj5.i4)` → 实现 android.view.ContextMenu，无子类 |
| 字段 d:List | `decompile_class_fields(kj5.i4)` → d:List / e:CharSequence / f:Context |
| d 字段真实用途 | `decompile_method(kj5.i4, "add")` → `((ArrayList) this.d).add(j4Var)` |
| 菜单项类 | `decompile_class_fields(kj5.j4)` → 30 字段，t:I 为 id |
| 字符串在方法内位置 | `decompile_class(o0)` 多段 → 568 行日志；572 行 `List list22 = i4Var.d` |
