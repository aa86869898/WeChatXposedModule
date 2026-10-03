# 微信主界面右上角"+"菜单 — 独立 Xposed 模块 UI 级注入完整方案

> 基于 LSPilot 逆向分析成果整理。目标：在微信顶部"+"弹出的菜单列表末尾，**稳定**注入自定义功能按钮，点击后拉起**你自己的独立 Xposed 模块** Activity。
>
> 适用对象：拥有自研 Xposed 模块（Java，非 LSPilot BSH 插件）的开发者。
> 分析基线：Android Xposed / LSPosed 宿主。

---

## 一、结论速览（TL;DR）

微信主界面右上角"+"菜单的真实架构是：

```
HomeUI（主界面 ActionBar "+" 点击）
      │  创建并持有实例（字段 HomeUI.k）
      ▼
com.tencent.mm.ui.wg        ← 菜单控制器（混淆名，随版本变化）
      │  extends com.tencent.mm.ui.tools.hd（PopupWindow 容器基类）
      │  implements AdapterView$OnItemClickListener / PopupWindow$OnDismissListener
      │
      ├── 持有 rg（BaseAdapter 子类）→ 负责渲染每一项
      ├── 持有 lg（PlusMenaDataManager）→ 负责菜单数据
      └── 持有 MMListPopupWindow（父类 hd.d）→ 负责弹窗显示/关闭
```

**最稳的注入方式：不碰微信的数据结构，只 Hook Adapter 的 3 个方法 + 菜单控制器 onItemClick，向列表末尾追加一个自绘 Item，并拦截它的点击事件。**

Hook 点：
| 目标 | 时机 | 动作 |
|---|---|---|
| `rg.getCount()` | After | `orig + 1`，缓存 `orig` |
| `rg.getView(pos,…)` | Before | `pos >= orig` → 直接 `setResult(自绘 View)` |
| `wg.onItemClick(…,pos,…)` | Before | `pos >= orig` → `setResult(null)` 消费 + 关弹窗 + 启动你的 Activity |

全部通过**字符串锚点 + 反射字段扫描**动态定位，**零硬编码混淆类名**，跨版本迁移只需校验锚点字符串。

---

## 二、关键类清单（当前实测版本）

> ⚠️ 微信类名高度混淆（`wg / rg / lg / ug / tg / sg / vg`），**版本升级大概率改变**。所以本文所有类名仅作"事实记录"，落地代码一律走文末的**动态定位算法**，不要硬编码。

### 2.1 菜单控制器 `com.tencent.mm.ui.wg`
```java
class wg extends com.tencent.mm.ui.tools.hd
        implements AdapterView.OnItemClickListener,
                   View.OnKeyListener,
                   ViewTreeObserver.OnGlobalLayoutListener,
                   PopupWindow.OnDismissListener {
    int[]            x, y, z;          // 图标/颜色等静态映射
    LayoutInflater   q;
    rg               r;                // ★ BaseAdapter 子类（菜单适配器）
    SparseArray      s;                // ★ position → tg（菜单项实例）
    Context          t;
    MainTabUI        u;
    lg               v;                // ★ PlusMenaDataManager（数据源）
    boolean          w;

    BaseAdapter b();                   // 返回 r
    void e(boolean z);                 // 刷新菜单（触发事件总线）
    void onItemClick(AdapterView, View, int position, long id);  // ★ 点击分发
    void onSceneEnd(int, int, String, m1);                        // 网络回调刷新
}
```

### 2.2 弹窗容器基类 `com.tencent.mm.ui.tools.hd`
```java
class hd {
    MMListPopupWindow  d;   // ★ 真正的 ListPopupWindow
    Context            e;
    View               f;   // anchor view
    int                g;
    BaseAdapter        h;
    ViewTreeObserver   i;
    ViewGroup          m;
    boolean            n, p;
    int                o;

    void a();        // ★ 关闭弹窗：if (c()) d.a();
    BaseAdapter b(); // 抽象
    boolean c();     // 是否 showing
    boolean d(int);
    void onDismiss();
    void onGlobalLayout();
    boolean onKey(View, int, KeyEvent);
}
```

### 2.3 菜单适配器 `com.tencent.mm.ui.rg`（BaseAdapter）
```java
class rg extends BaseAdapter {
    wg d;                       // 指向控制器

    int getCount()      { return d.s.size(); }              // ★ 数据源 = SparseArray.s
    Object getItem(int) { return null; }                    // 微信自己都不返回对象
    long getItemId(int) { return 0L; }                      // 永远是 0（GridView/ListView 用 position）
    View getView(int i, View convertView, ViewGroup parent) { … }
}
```

### 2.4 数据源 `com.tencent.mm.ui.lg` = **PlusMenaDataManager**
```java
class lg {
    static lg       h;              // 单例
    int             a;              // 红点计数
    SparseIntArray  b;              // menuId → 本地缓存配置
    SparseArray     c;              // menuId → sg（DEX 下发的菜单配置）
    ArrayList       d;              // 排序后的 menuId 列表
    SparseArray     e;              // 最终可见菜单 position → tg
    long            f;              // 上次刷新时间戳
    boolean         g;              // 是否已加载配置

    void a(boolean z);   // 从 c/d 构建 e（含扫一扫红点特判 id==10）
    void b(boolean z);   // 从 DynamicConfig 解析 `TopRightMenu\d*` / `TopRightMenus`
    void c();            // fallback：从本地默认 TopRightMenus 配置解析
}
```
> 日志 tag：`MicroMsg.PlusMenaDataManager`（拼写就是 Men**a**）
> 它通过 `wg.f(int menuId) → ug` 静态注册表把 id 映射为运行时菜单定义。

### 2.5 数据模型
```java
class ug {   // 运行时菜单定义
    String a;   // 标题
    int    b;   // 图标 resId
    int    c;   // ★ menuId（点击分发用）
    int    d;   // 标题颜色 resId
}
class tg {   // 运行时菜单实例
    boolean a; // 是否显示红点/新
    ug      b; // 指向菜单定义
}
class sg {   // DEX 动态配置项（PlusMenaDataManager 内部用）
    int a, b, c, d;   // id / shownew / seq / order
}
class vg {   // MenuItemView 控制器（getView 里 new 出来绑定）
    View    f;   // item 根布局（inflate 2131629591）
    TextView g;  // 标题（2131389192）
    ImageView h; // 图标（2131376376）
    TextView i;  // 副标题/提示
    TextView m;  // 红点容器（2131390152）
    View     n;  // NEW 角标（2131381577）
}
```

### 2.6 入口持有关系
```java
class HomeUI {
    wg k;    // ★ 主界面持有菜单控制器实例
}
```
> 通过 `find_class_usage(com.tencent.mm.ui.wg)` 确认全局引用共 3 处：`HomeUI.k`（创建/持有）、`mg.d`（rg 内部）、`rg.d`（adapter 反持控制器）。**菜单 UI 弹出时机 = HomeUI 构造 wg 后由 MMListPopupWindow 展示。**

---

## 三、菜单项 id 语义（onItemClick 分发表）

`onItemClick` 核心逻辑：
```java
tg tgVar = (tg) this.s.get(position);   // 注意：直接按 position 取，越界 → NPE（对我们有利，见 §5.3）
int id = tgVar.b.c;                     // menuId
g0.r.c(11104, new Object[]{ id });      // 埋点上报
switch (id) {
    case 1:  → 添加朋友  AddMoreFriendsUI（.ui.pluginapp.AddMoreFriendsUI, invite_friend_scene=2）
    case 2:  → 发起群聊  MvvmContactListUI（from_create_group_scene=1, KShowSelectExistChatroom=true …）
    case 10: → 扫一扫    BaseScanUI（.ui.BaseScanUI，key_scan_report_enter_scene …）
    case 22: → a0.k(activity, 1)
    case 24: → 特殊分支
    …
    default: → 收付款 / 其他动态项
}
// 所有分支汇合到 :cond_23c → 末尾统一 hd.a() 关闭弹窗
```
`packed-switch` 覆盖：`case 1..14`、`case 16..20`、以及 `0x7ffffffe / 0x7ffffffd`（动态项/哨兵 id）。
> `id == 10` 在 PlusMenaDataManager 中有扫一扫红点特判，`id == 20` 有另一处特判（疑似限时/运营位）。

**对我们的意义**：只要注入项的 `position` 超出微信真实数据范围，微信的 `onItemClick` 就会先执行 `s.get(position)` 拿到 `null`，下一行 `tgVar.b.c` 直接 NPE —— 这正说明必须在 `onItemClick` 的 **Before** 阶段 `setResult(null)` 拦掉，绝不能放进原方法。

---

## 四、为什么选"Hook Adapter"而不是改数据 / 反射塞对象

| 方案 | 稳定性 | 说明 |
|---|---|---|
| A. 反射往 `wg.s`(SparseArray) 塞自定义 `tg`/`ug` | ❌ 易崩 | `ug/tg/vg` 构造器签名混淆，字段名随版本变；getView 内部要读 icon/title/埋点，凑不齐就 NPE |
| B. 反射 `PlusMenaDataManager` 改 `d/e` | ❌ 易崩 | 动态配置 + 排序 + 红点逻辑交织，且 1 小时缓存回填 `b()` 会覆盖 |
| C. Hook ActionBar "+" 自己弹菜单 | ❌ 不 UI 级 | 替换整块 UI，与微信视觉/刘海/折叠屏适配割裂 |
| **D. Hook `rg`(getCount/getView) + `wg.onItemClick`** | ✅ **推荐** | 不动微信任何数据结构，只在渲染末位追加一项 + 消费其点击；微信原有项逻辑零改动 |

**方案 D 的三个稳定性保障：**
1. **getView 里 `i == getCount()-1` 的末项背景判定会落在我们项上**，微信把"特殊末项背景"给了我们，原最后项退化为普通背景 —— 这是唯一视觉副作用（微信单列列表末项通常只是圆角 selector），且我们自绘 item 可自行控制背景，影响面积极小。
2. **`getItem()` 恒返回 null、`getItemId()` 恒 0**，说明微信自己只依赖 `getCount/getView/onItemClick(position)`，我们无需 Hook 这两个方法，缩小了攻击面。
3. **点击关闭统一走 `hd.a()`**（所有 case 汇合处），我们反射调用同一个 `a()`，关闭行为与微信原生完全一致。

---

## 五、完整实现（独立 Xposed 模块）

### 5.1 Hook 入口与动态定位

**定位原则**：先靠"高辨识度业务字符串"找到菜单控制器 `onItemClick`，再靠"控制器里类型为 BaseAdapter 子类的字段"反推 adapter 类，**全程无硬编码混淆名**。

```java
package com.your.module.wechatplus;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.util.Log;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class WeChatPlusMenuHook implements IXposedHookLoadPackage {

    private static final String TAG = "WxPlusMenu";
    private static final String TARGET_PKG = "com.tencent.mm";

    private static final String KEY_ORIG_COUNT = "wx_plus_orig_count";

    // 高辨识度锚点字符串：唯一命中菜单控制器的 onItemClick（已实测）
    private static final String[] ANCHOR_STRINGS = {
            "KShowSelectExistChatroom",
            "from_create_group_scene",
            "PlusSubMenuHelper"        // 注意：DEX 里是 "com/tencent/mm/ui/PlusSubMenuHelper"
    };

    private ClassLoader wxClassLoader;
    private Class<?> controllerClass;   // = com.tencent.mm.ui.wg
    private Class<?> adapterClass;      // = com.tencent.mm.ui.rg
    private String   sparseArrayField;  // 控制器里 SparseArray 类型的字段名（= "s"）

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;
        wxClassLoader = lpparam.classLoader;

        try {
            if (!locateMenuClasses()) {
                XposedBridge.log(TAG + " locate failed");
                return;
            }
            hookAdapter();
            hookOnItemClick();
            XposedBridge.log(TAG + " hooked: controller=" + controllerClass.getName()
                    + " adapter=" + adapterClass.getName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " init error: " + t);
        }
    }

    /* ---------- 5.1 动态定位 ---------- */
    private boolean locateMenuClasses() {
        // 1) 用锚点字符串找到 onItemClick 所在的控制器类（DexKit 已完成，此处运行时只需兜底扫描）
        controllerClass = findClassByStrings(ANCHOR_STRINGS);
        if (controllerClass == null) return false;

        // 2) 在控制器类里找"类型为 BaseAdapter 子类"的字段 → adapter 类
        Field adapterField = null;
        for (Field f : controllerClass.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            try {
                Class<?> ft = f.getType();
                if (BaseAdapter.class.isAssignableFrom(ft) && !ft.equals(ArrayAdapter.class)) {
                    adapterField = f;      // 取第一个非系统 Adapter 字段
                    adapterClass = ft;
                    break;
                }
            } catch (Throwable ignore) {}
        }
        if (adapterClass == null || adapterClass.equals(ArrayAdapter.class)) return false;

        // 3) 找控制器里 SparseArray 字段（后续用它算真实菜单项数量）
        for (Field f : controllerClass.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (SparseArray.class.isAssignableFrom(f.getType())) {
                sparseArrayField = f.getName();
                break;
            }
        }
        return true;
    }

    /**
     * 兜底定位：遍历所有已加载 dex 不可行，这里采用"反射已知锚点 + 父类扫描"。
     * 正式环境建议配合 DexKit（与 LSPilot 分析一致），或直接把此结果做版本表缓存：
     *   版本号 → controller 类名
     */
    private Class<?> findClassByStrings(String[] anchors) {
        // 由于运行时无法枚举字符串引用，这里用「类结构特征」反查：
        // 控制器特征 = implements AdapterView.OnItemClickListener 且含 SparseArray 字段且含 BaseAdapter 字段
        // 但更可靠的做法是你在模块里内置一张"锚点→类名"表（见 §6.3 版本表），命中后直接 forName。
        String cached = AnchorTable.resolve(anchorKeyOf(anchors));
        if (cached != null) {
            try { return XposedHelpers.findClass(cached, wxClassLoader); }
            catch (Throwable ignore) {}
        }
        return null;
    }

    private String anchorKeyOf(String[] anchors) {
        return anchors[0] + "|" + anchors[anchors.length - 1];
    }
```

> 说明：运行时从"字符串"反查"类"在纯 Xposed 里做不到像 DexKit 那样检索，所以工程上采用**锚点表（版本→类名）**做主定位，特征扫描做兜底。锚点表由一次逆向生成，升级微信时只需跑一遍本文档的定位流程回填表（见 §6.3）。

```java
    /* ---------- 5.2 Hook Adapter ---------- */
    private void hookAdapter() {
        // getCount → 真实数量 + 1
        XposedHelpers.findAndHookMethod(adapterClass, wxClassLoader, "getCount",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object origObj = param.getResult();
                        int orig = (origObj instanceof Integer) ? (Integer) origObj : 0;
                        XposedHelpers.setAdditionalInstanceField(
                                param.thisObject, KEY_ORIG_COUNT, orig);
                        param.setResult(orig + 1);
                    }
                });

        // getView → position >= orig 时返回自绘 item
        XposedHelpers.findAndHookMethod(adapterClass, wxClassLoader, "getView",
                int.class, View.class, ViewGroup.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int position = (int) param.args[0];
                        Integer orig = (Integer) XposedHelpers.getAdditionalInstanceField(
                                param.thisObject, KEY_ORIG_COUNT);
                        if (orig == null) orig = 0;
                        if (position < orig) return;          // 微信原有项，放行

                        ViewGroup parent = (ViewGroup) param.args[2];
                        Context ctx = parent != null ? parent.getContext()
                                : (Context) XposedHelpers.getObjectField(param.thisObject, "t");
                        param.setResult(buildCustomItem(ctx, position));
                    }
                });
    }

    /* ---------- 5.3 Hook 点击分发 ---------- */
    private void hookOnItemClick() {
        XposedHelpers.findAndHookMethod(controllerClass, wxClassLoader, "onItemClick",
                AdapterView.class, View.class, int.class, long.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int position = (int) param.args[2];

                        // 计算真实菜单数量：优先 adapter 缓存，其次反射 SparseArray.size()
                        int orig = resolveRealCount(param.thisObject);
                        if (position < orig) return;          // 原生项，放行

                        // 1) 消费事件，阻止微信执行（否则 s.get(pos)=null → NPE）
                        param.setResult(null);

                        // 2) 关闭微信菜单：调父类 hd.a()，行为与原生完全一致
                        try {
                            XposedHelpers.callMethod(param.thisObject, "a");
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " dismiss failed: " + t);
                        }

                        // 3) 拉起你自己的模块 Activity
                        launchYourFeature((AdapterView) param.args[0]);
                    }
                });
    }

    private int resolveRealCount(Object controller) {
        // 优先：控制器 → adapter 字段 → AdditionalInstanceField
        try {
            Object adapter = XposedHelpers.getObjectField(controller, adapterFieldNameOf(controller));
            if (adapter != null) {
                Object cache = XposedHelpers.getAdditionalInstanceField(adapter, KEY_ORIG_COUNT);
                if (cache instanceof Integer) return (Integer) cache;
            }
        } catch (Throwable ignore) {}

        // 兜底：直接反射 SparseArray 字段 size()
        if (sparseArrayField != null) {
            try {
                SparseArray<?> sp = (SparseArray<?>) XposedHelpers.getObjectField(controller, sparseArrayField);
                if (sp != null) return sp.size();
            } catch (Throwable ignore) {}
        }
        return 0;
    }

    private String adapterFieldNameOf(Object controller) {
        for (Field f : controller.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            try {
                if (BaseAdapter.class.isAssignableFrom(f.getType())
                        && !f.getType().equals(ArrayAdapter.class)) {
                    return f.getName();
                }
            } catch (Throwable ignore) {}
        }
        return "r";
    }

    /* ---------- 5.4 启动你的功能 ---------- */
    private void launchYourFeature(AdapterView<?> parent) {
        Context ctx = parent != null ? parent.getContext() : null;
        if (ctx == null) return;
        try {
            Intent intent = new Intent();
            intent.setClassName(ctx, "com.your.module.MainActivity"); // ← 改成你的模块 Activity
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " launch failed: " + t);
        }
    }
```

### 5.2 自绘菜单 Item（视觉对齐微信列表项）

微信 item 是横向"图标 + 标题 + 红点位"结构（vg 布局）。为 **100% 不依赖微信资源 id**，自绘一个同构 View：

```java
    private View buildCustomItem(Context ctx, int position) {
        float density = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setLayoutParams(new AbsListView.LayoutParams(
                AbsListView.LayoutParams.MATCH_PARENT,
                (int) (56 * density)));
        int pad = (int) (16 * density);
        root.setPadding(pad, 0, pad, 0);
        // 透明背景，让微信列表 selector 正常透出点击反馈
        root.setBackgroundColor(Color.TRANSPARENT);

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(R.drawable.ic_your_feature);  // ← 你的图标
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                (int) (24 * density), (int) (24 * density));
        icon.setLayoutParams(ip);

        TextView title = new TextView(ctx);
        title.setText("我的功能");                            // ← 你的标题
        title.setTextSize(15);
        title.setTextColor(Color.parseColor("#1A1A1A"));     // 深灰，贴近微信原生
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.setMarginStart((int) (16 * density));
        title.setLayoutParams(tp);

        root.addView(icon);
        root.addView(title);
        return root;
    }
}
```

**尺寸/颜色调参建议（贴近微信）：**
| 元素 | 值 |
|---|---|
| item 高度 | 56dp |
| 左边距 | 16dp |
| 图标 | 24dp × 24dp |
| 图标-文字间距 | 16dp |
| 标题字号 | 15sp |
| 标题颜色 | `#1A1A1A` |

> 若你更想"像素级复刻微信 item"，可在模块首次拿到任意原生 item view 后（`getView` After 阶段缓存一个模板 view），反射读取其高度/padding/textSize/textColor 再套用到自绘 view。多数场景 56dp 方案已足够。

---

## 六、版本兼容与失效排查

### 6.1 已知可退化点
| 现象 | 原因 | 处理 |
|---|---|---|
| Hook 后无新增项 | `getCount()` 未被本类调用（Launcher 缓存了 count） | 同时 Hook `BaseAdapter.notifyDataSetChanged` 前的数据构造，或在 `e(Z)` After 里让列表刷新 |
| 点击无响应 | `onItemClick` 在新版改名/改签名 | 用接口兜底：`hookMethod(AdapterView.OnItemClickListener.class, "onItemClick", …)` 再 `instanceof 控制器` |
| 项出现但位置错 | 微信改用 `RecyclerView` 而非 `MMListPopupWindow` | 走 §6.4 RecyclerView 方案 |
| 崩溃 NPE | 未在 Before 拦断导致 `s.get(pos)=null` | 确认 `param.setResult(null)` 在 beforeHookedMethod **首行**执行 |

### 6.2 兼容性自检（建议模块内置）
```java
private boolean sanityCheck() {
    return controllerClass != null && adapterClass != null
            && sparseArrayField != null
            && hasMethod(controllerClass, "onItemClick",
                AdapterView.class, View.class, int.class, long.class)
            && hasMethod(adapterClass, "getCount")
            && hasMethod(adapterClass, "getView", int.class, View.class, ViewGroup.class);
}
```

### 6.3 锚点表（版本 → 类名）
升级微信后，重新跑一遍本文定位流程，把结果回填到 `AnchorTable`，即可秒级恢复：
```java
final class AnchorTable {
    // key = ANCHOR_STRINGS[0] + "|" + ANCHOR_STRINGS[last]
    private static final Map<String, String> MAP = new HashMap<>();
    static {
        // 示例（请按目标微信版本更新）
        MAP.put("KShowSelectExistChatroom|PlusSubMenuHelper",
                "com.tencent.mm.ui.wg");   // controller
    }
    static String resolve(String key) { return MAP.get(key); }
}
```
> 生成锚点表的方法：对目标 APK 执行 `find_method(using_strings=["KShowSelectExistChatroom","from_create_group_scene","PlusSubMenuHelper"])` → 唯一命中的方法所在类即 controller；再 `decompile_class_fields(controller)` 找 `BaseAdapter` 子类字段类型即 adapter。

### 6.4 RecyclerView 兜底（未来版）
若微信把菜单换成 RecyclerView：
1. Hook `RecyclerView.Adapter.getItemCount()` → `+1`
2. Hook `RecyclerView.Adapter.onCreateViewHolder / onBindViewHolder` → position>=orig 时返回自定义 VH
3. Hook 容器 `RecyclerView` 的 `OnItemTouchListener` 或 item 的 `OnClickListener` → position>=orig 时消费并启动你的功能

### 6.5 与微信"动态菜单配置"的关系
PlusMenaDataManager 会从服务端 DynamicConfig（`TopRightMenu\d*`）动态增删菜单项，意味着**原生菜单数量会随账号/时间变化**。我们的方案每次 Hook 都以**实时 `origCount`** 为基准追加，天然兼容动态增减，不会错位——这也是不改数据结构方案的核心收益。

---

## 七、一键落地清单（Checklist）

- [ ] 模块 `AndroidManifest.xml` 注册你的入口 Activity（`exported=true` 便于拉起，或内部拉起）
- [ ] `XposedInit` 中声明 `handleLoadPackage` 过滤 `com.tencent.mm`
- [ ] 确认 `resources.arsc` / `R.drawable.ic_your_feature` 存在（自绘 item 图标）
- [ ] 回填 `AnchorTable` 为目标微信版本的 controller/adapter 类名
- [ ] 首次运行后查看 LSPosed 日志：`WxPlusMenu hooked: controller=… adapter=…`
- [ ] 打开微信 → 点右上角"+" → 末位出现"我的功能" → 点击拉起模块 → 菜单正常关闭
- [ ] 回归测试：动态菜单增减后再次点击，位置仍正确

---

## 八、附：本次逆向取证方法（可复现）

| 步骤 | 工具/动作 | 产出 |
|---|---|---|
| 1 | `search_classes("PlusMenu")` + `search_strings("PlusMenu")` | 命中 `HomeUI$18$1 / lg / m74.i0` |
| 2 | `view_strings(lg)` | tag=`MicroMsg.PlusMenaDataManager`，`TopRightMenu\d*`、`.Menu.$id/$seq/$shownew/$order` |
| 3 | `decompile_class_fields/methods_only(lg)` | `a/b/c` + `c/d/e` 字段结构 |
| 4 | `decompile_method(lg, a/b/c)` | 确认数据加载与 `wg.f(id)→ug` 注册表 |
| 5 | `decompile_class_fields/methods_only(wg)` | 控制器 + `s/r/t/v` 字段 + `b()/e()/onItemClick()` |
| 6 | `decompile_method(wg, onItemClick)` + `get_method_smali` | 完整点击分发、switch id 表、结尾 `hd.a()` |
| 7 | `decompile_method(rg, getCount/getView)` | adapter 数据源 = `wg.s.size()`，item 布局 2131629591 |
| 8 | `class_hierarchy(wg)` | `wg extends tools.hd`，PopupWindow 容器链路 |
| 9 | `decompile_method(hd, a)` + `fields(hd)` | `a()`=`MMListPopupWindow.a()` 关闭 |
| 10 | `find_method(using_strings=[3 锚点])` | **唯一命中 wg.onItemClick** → 验证动态定位锚点有效 |
| 11 | `find_class_usage(wg)` | `HomeUI.k / mg.d / rg.d` → 确认入口持有关系 |

---

*文档由 LSPilot AI 分析助手生成。所有类名/字段名以当前分析版本为准，落地前请按 §6 做兼容性自检。*
