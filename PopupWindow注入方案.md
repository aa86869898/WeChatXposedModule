# WeKit PopupWindow / 菜单注入方案深度技术分析

> 分析日期：2025-01  
> 分析目标：`dev.ujhhgtg.wekit`（WeKit）  
> 涵盖 5 个菜单注入系统：首页弹窗、聊天消息、会话列表、朋友圈、短视频分享

---

## 目录

1. [总体架构对比](#一总体架构对比)
2. [WeHomeScreenPopupMenuApi —— 首页弹窗菜单](#二wehomescreenpopupmenuapi--首页弹窗菜单)
3. [WeChatMessageContextMenuApi —— 聊天消息长按菜单](#三wechatmessagecontextmenuapi--聊天消息长按菜单)
4. [WeConversationContextMenuApi —— 会话列表长按菜单](#四weconversationcontextmenuapi--会话列表长按菜单)
5. [WeMomentsContextMenuApi —— 朋友圈上下文菜单](#五wemomentscontextmenuapi--朋友圈上下文菜单)
6. [WeShortVideosShareMenuApi —— 短视频分享菜单](#六weshortvideossharemenuapi--短视频分享菜单)
7. [通用设计模式总结](#七通用设计模式总结)

---

## 一、总体架构对比

WeKit 共实现了 5 个菜单注入系统，分别对应微信不同界面的弹出菜单。所有系统共享相同的架构模式：

```
 ┌───────────────────────────────────────────────┐
 │           核心模式（Provider 插件式）            │
 │                                                │
 │  1. Provider 实现接口，注册到 Api 单例          │
 │  2. Api 用 DexKit 动态定位微信内部方法           │
 │  3. Xposed Hook 拦截菜单创建/点击事件            │
 │  4. 在 Hook 回调中调用所有 Provider 注入条目     │
 └───────────────────────────────────────────────┘
```

| 系统 | 菜单类型 | 微信内部机制 | Provider 接口 | 数据载体 |
|------|---------|------------|--------------|---------|
| WeHomeScreenPopupMenuApi | 首页 `+` 按钮弹窗 | `SparseArray` + `BaseAdapter` | `i99` | `j99` |
| WeChatMessageContextMenuApi | 聊天消息长按 | 反射调用 `add()` 方法 | `y59` | `z59` |
| WeConversationContextMenuApi | 会话列表长按 | `ContextMenu.add()` | `ra9` | `z89` |
| WeMomentsContextMenuApi | 朋友圈长按 | 多路径反射 | 内部 `i` | `sa9` |
| WeShortVideosShareMenuApi | 短视频分享 | `ContextMenu.add()` | 内部 `i` | `tb9` |

---

## 二、WeHomeScreenPopupMenuApi —— 首页弹窗菜单

### 2.1 触发位置

微信主界面（`HomeUI`）右上角 `+` 按钮弹出的 PlusSubMenuHelper 弹窗。

### 2.2 架构

```
WeHomeScreenPopupMenuApi (单例)
│
├─ [静态字段]
│   i = CopyOnWriteArrayList<i99>  ← Provider 注册表
│   j = nc5                          ← 点击回调注册表 (自定义哈希表)
│   k = methodAddItem (DexKit DSL)
│   l = methodHandleItemClick (DexKit DSL)
│   m = classMenuItemData (DexKit DSL)
│   n = classMenuItemWrapper (DexKit DSL)
│
├─ D() → 注册 2 个 Hook
│   ├─ Hook methodAddItem      → g99(11)  ← 菜单创建时
│   └─ Hook methodHandleItemClick → g99(12)  ← 菜单点击时
│
└─ I() → 返回 classMenuItemWrapper 的 DexKit 委托
```

### 2.3 Provider 接口

```java
// i99 - Provider 接口
public interface i99 {
    List<j99> k(XC_MethodHook.MethodHookParam param);
}
```

### 2.4 数据载体 j99

```java
public final class j99 {
    public final int a;            // 菜单项 ID (自定义整数)
    public final String b;         // 显示文字
    public final Drawable c;       // 图标
    public final y33 d;            // 点击回调 (Runnable)
}
```

### 2.5 已注册的 Provider 实例

| Provider 类 | 菜单项 ID | 文字 | 用途 |
|------------|----------|------|------|
| `w95` | `0` | **WeKit** | 打开 WeKit 设置入口 |
| `me8` | `777010` | 显示对话 | 隐藏/显示会话切换 |
| `me8` | `777011` | 隐藏对话 | |
| `j05` | `777012` | 清空未读 | 清除未读消息 |
| `p84` | `777015` | 强行停止 | 强制停止微信进程 |

### 2.6 Hook 回调详解

#### Hook 1：methodAddItem → `g99(11)`（菜单创建）

这是核心注入逻辑，非常精巧：

```java
// g99 case 11 - afterHookedMethod
void case_11(MethodHookParam param) {
    Object thisObj = param.thisObject;

    // Step 1: 获取 thisObject，如果 className 是 "HomeUI"，
    // 则反射拿到内部 PlusSubMenuHelper 对象
    if (thisObj.getClass().getSimpleName().equals("HomeUI")) {
        // 通过 DexKit 定位 handleItemClick 方法所在类的字段
        // 拿到内部 PlusSubMenuHelper 实例
        Field field = findFieldByType(methodHandleItemClick.getDeclaringClass());
        thisObj = field.get(thisObj);
    }

    // Step 2: 获取 SparseArray（微信用于存储菜单项的核心容器）
    Field sparseArrayField = findFieldByType(SparseArray.class);
    SparseArray sparseArray = (SparseArray) sparseArrayField.get(thisObj);

    // Step 3: 获取 BaseAdapter（微信的弹窗列表适配器）
    Field adapterField = findFieldMatchingCondition(BaseAdapter.class);
    BaseAdapter adapter = (BaseAdapter) adapterField.get(thisObj);

    // Step 4: Hook BaseAdapter.getView（在图标加载时注入自定义图标）
    Method getViewMethod = findMethod("getView");
    // beforeHooked (sz0(3)): Hook ImageView.setImageResource 替换图标
    // afterHooked (sz0(4)): 解注册图标 Hook
    hookMethod(getViewMethod, sz0(3));  // priority 50

    // Step 5: 遍历所有 Provider，注入菜单项
    for (i99 provider : WeHomeScreenPopupMenuApi.i) {
        for (j99 item : provider.k(param)) {
            // 5a: 注册点击回调（hash = text.hashCode() + id）
            nc5.put(item.b.hashCode() + item.a, item.d);

            // 5b: 创建微信 MenuItemWrapper 实例
            Class wrapperClass = classMenuItemWrapper;
            Integer id = item.a;
            String text = item.b;
            Object wrapper = wrapperClass.newInstance(id, text, "", 
                text.hashCode() + item.a, 0);

            // 5c: 放入 SparseArray
            sparseArray.put(sparseArray.size(), wrapper);

            // 5d: 通知 Adapter 刷新
            adapter.notifyDataSetChanged();
        }
    }
    // 最后再刷新一次
    adapter.notifyDataSetChanged();
}
```

#### Hook 2：methodHandleItemClick → `g99(12)`（菜单点击）

```java
// g99 case 12 - afterHookedMethod
void case_12(MethodHookParam param) {
    Object thisObj = param.thisObject;

    // Step 1: 从 thisObj 拿到 SparseArray
    Field field = findFieldByType(SparseArray.class);
    SparseArray sparseArray = (SparseArray) field.get(thisObj);

    // Step 2: 从 args[2] 拿到被点击的 index
    int index = (Integer) param.args[2];

    // Step 3: 从 SparseArray 取出被点击的 MenuItemWrapper
    Object clickedItem = sparseArray.get(index);

    // Step 4: 从 MenuItemWrapper 反射拿 MenuItemData
    Field dataField = findFieldByType(classMenuItemWrapper.I().e());
    Object menuItemData = dataField.get(clickedItem);

    // Step 5: 拿到 id 字段 (第1个字段)
    int itemId = (Integer) getFieldValue(menuItemData, 1);

    // Step 6: 匹配 Provider 的 click callback
    for (i99 provider : WeHomeScreenPopupMenuApi.i) {
        for (j99 item : provider.k(param)) {
            if (item.a == itemId) {
                item.d.invoke();  // 执行点击回调
                return;
            }
        }
    }
}
```

### 2.7 生命周期

每个 `fz7` 子类（`me8`, `j05`, `p84`, `w95`）继承自 `fz7`，后者实现了标准的 `D()`（注册）和 `C()`（注销）：

```java
// Provider 注册
public void D() {
    WeHomeScreenPopupMenuApi.i.addIfAbsent(this);
}

// Provider 注销
public void C() {
    WeHomeScreenPopupMenuApi.i.remove(this);
}
```

### 2.8 数据流图

```
用户点击首页 + 按钮
        │
        ▼
微信 PlusSubMenuHelper 创建弹窗
        │
        ▼
Hook: methodAddItem (g99 case 11)
        │
        ├─ 获取 SparseArray<MenuItemWrapper>
        ├─ 获取 BaseAdapter
        ├─ Hook getView → 图标替换
        ├─ for (i99 provider : providers)
        │     for (j99 item : provider.k(param))
        │         nc5.put(hash, callback)    ← 注册回调
        │         new MenuItemWrapper(...)     ← 创建条目
        │         sparseArray.put(size, wrapper) ← 注入
        │         adapter.notifyDataSetChanged()
        └─ adapter.notifyDataSetChanged()  ← 最终刷新
                │
                ▼
        弹窗显示，用户看到 WeKit 条目
                │
                ▼
        用户点击条目
                │
                ▼
Hook: methodHandleItemClick (g99 case 12)
        │
        ├─ sparseArray.get(index) → MenuItemWrapper
        ├─ 反射取 MenuItemData.id
        ├─ for (provider) for (item)
        │     if (item.id == clickedId)
        │         item.d.invoke()  ← 执行回调
        └─ return
```

---

## 三、WeChatMessageContextMenuApi —— 聊天消息长按菜单

### 3.1 触发位置

聊天界面中长按一条消息时弹出的上下文菜单。

### 3.2 架构

```
WeChatMessageContextMenuApi (单例)
│
├─ i = LinkedHashMap<String, y59>  ← Provider 注册表 (key = className)
├─ j = methodCreateMenu (DexKit DSL)
├─ k = methodSelectMenuItem (DexKit DSL)
├─ l = methodMultiCreateMenu (DexKit DSL)
├─ m = methodMultiSelectMenuItem (DexKit DSL)
│
├─ D() → s(methodCreateMenu, u59(3))  + s(methodSelectMenuItem, u59(4))
│           s(methodMultiCreateMenu, u59(5)) + s(methodMultiSelectMenuItem, u59(6))
│
├─ K(y59) → i.put(className, y59.a())  注册
└─ N(y59) → i.remove(className)         注销
```

### 3.3 Provider 体系

Provider 实现 `y59` 抽象类，重写 `a()` 返回 `List<z59>`（菜单项配置列表）。

### 3.4 菜单创建 Hook（u59 case 3 / case 5）

创建菜单时 Hook 回调的核心：

```java
// 在微信的"创建菜单"方法返回后
// 对每个注册的 Provider 的每个 z59：
//   1. 判断条件 z59.e.B(messageData) 是否为 true
//   2. 如果为 true，反射调用微信的 add 方法插入菜单项
//      add(id, text, drawable)
//
// 特殊：provider z59.f 如果是 d69（默认类型），创建合并菜单项 777000
//   点击后弹出子菜单让用户选择具体功能
```

### 3.5 菜单点击 Hook（u59 case 4 / case 6）

```java
// 获取点击的 MenuItem.getItemId()
// 如果是 777000（合并菜单项）：
//   → 弹出 AlertDialog 列出所有满足条件的 Provider，用户选择
// 否则遍历所有 z59：
//   if (z59.a == itemId) → z59.g.z(view, x59, messageData)
```

### 3.6 菜单项 ID 空间

| ID | 含义 |
|----|------|
| `777000` | WeKit 合并入口（点击弹出子菜单） |
| 其他自定义 ID | 各个 Provider 的独立菜单项 |

---

## 四、WeConversationContextMenuApi —— 会话列表长按菜单

### 4.1 触发位置

会话列表界面（微信首页）长按某个会话时弹出的 ContextMenu。

### 4.2 架构

```
WeConversationContextMenuApi (单例)
│
├─ i = LinkedHashMap<String, z89>  ← Provider 注册表
├─ j = methodOnCreateMenu (DexKit DSL)
├─ k = methodOnItemSelected (DexKit DSL)
│
├─ D() → s(methodOnCreateMenu, t89(26)) + s(methodOnItemSelected, x89(0))
└─ I(obj) → 反射提取 y89 {Activity, username, conversation} 上下文数据
```

### 4.3 菜单创建 Hook（t89 case 29 / default）

```java
void onCreateMenu_afterHooked(MethodHookParam param) {
    // Step 1: 从 args[0] 获取 ContextMenu
    ContextMenu contextMenu = (ContextMenu) param.args[0];

    // Step 2: 从 args[2] 获取 AdapterContextMenuInfo 拿 position
    AdapterView.AdapterContextMenuInfo info = (AdapterContextMenuInfo) param.args[2];
    int position = info != null ? info.position : 0;

    // Step 3: 反射 thisObject 提取会话上下文 (Activity, username, 数据)
    y89 context = WeConversationContextMenuApi.I(param.thisObject);

    // Step 4: 遍历所有注册的 Provider
    for (z89 provider : WeConversationContextMenuApi.i.values()) {
        // 4a: 判断条件：provider.d.R(context, provider.a)
        if (provider.shouldShow(context, provider.id)) {
            // 4b: ContextMenu.add(position, id, order, title).setIcon(drawable)
            contextMenu.add(position, provider.a, 0, provider.b)
                        .setIcon(provider.c);
        }
    }
}
```

### 4.4 数据载体 z89

```java
class z89 {
    int a;       // 菜单项 ID
    String b;    // 显示文字
    Drawable c;  // 图标
    j43 d;       // 条件判断 lambda (y89, int) → Boolean
    j43 e;       // 点击回调 lambda (y89) → void
}
```

### 4.5 上下文提取 I()

```java
public static y89 I(Object thisObj) {
    // Step 1: 反射找到 Activity 类型的字段 → 获取 Activity
    Field activityField = findFieldByType(Activity.class);
    Activity activity = (Activity) activityField.get(thisObj);

    // Step 2: 反射找到 String 字段 + 条件过滤 → username
    Field usernameField = findField(String.class, filterCondition);
    String username = (String) usernameField.get(thisObj);

    // Step 3: 反射找其他关键数据（如 conversation 对象）
    Field dataField = findField(matchingCondition);
    Object data = dataField != null ? dataField.get(thisObj) : null;

    return new y89(activity, username, data);
}
```

### 4.6 点击处理 Hook（x89 case 0）

```java
void onItemSelected_afterHooked(MethodHookParam param) {
    MenuItem menuItem = (MenuItem) param.args[0];
    int itemId = menuItem.getItemId();

    // 从 thisObject 反射拿到内部对象 → 提取 y89 上下文
    y89 context = WeConversationContextMenuApi.I(innerObj);

    // 遍历 Provider 匹配 itemId → 执行回调
    for (z89 provider : i.values()) {
        if (provider.a == itemId) {
            provider.e.B(context);  // onClick callback
        }
    }
}
```

---

## 五、WeMomentsContextMenuApi —— 朋友圈上下文菜单

### 5.1 触发位置

朋友圈界面长按某条动态时弹出的菜单。

### 5.2 架构特点

最复杂的菜单系统，因为微信朋友圈的菜单在不同页面（时间线、详情页、多图浏览）使用了**不同的方法路径**：

```
WeMomentsContextMenuApi.D()
│
├─ methodOnCreateMenu (j=qa9(11))          ← 主菜单创建
├─ methodOnItemSelected (k=qa9(12))         ← 主菜单点击
├─ methodImproveOnItemSelectedRegister2 (l)  ← 路径2 注册
├─ methodImproveOnItemSelectedRegister3 (m)  ← 路径3 注册
├─ methodImproveMultiPhotoOnItemSelected (n) ← 多图路径
├─ methodSnsInfoStorage (o)                 ← SnsInfo 存储
└─ methodGetSnsInfoStorage (p)             ← 获取 SnsInfo
```

### 5.3 核心注入逻辑（K 方法简化版）

```java
void K(MethodHookParam param) {
    // 获取点击的 MenuItem ID
    MenuItem menuItem = param.args[...];
    int itemId = menuItem.getItemId();

    // 遍历 LinkedHashMap i 中的 Provider
    for (sa9 provider : i.values()) {
        if (provider.a == itemId) {
            // 从 thisObject 反射提取 SnsInfo 数据
            Object snsInfo = extractSnsInfo(param.thisObject);

            // 执行回调
            provider.e.B(snsInfo);
            param.setResult(null);
            return;
        }
    }
}
```

---

## 六、WeShortVideosShareMenuApi —— 短视频分享菜单

### 6.1 触发位置

微信视频号/短视频的分享弹出菜单。

### 6.2 架构

```
WeShortVideosShareMenuApi.D()
│
├─ methodCreateMenu1 (j=sb9(9))   + methodOnSelectMenuItem1 (k=sb9(19))
├─ methodCreateMenu2 (l=sb9(20))  + methodOnSelectMenuItem2 (m=sb9(21))
└─ methodCreateMenu3 (n=sb9(22))  + methodOnSelectMenuItem3 (o=sb9(23))
```

三组方法分别对应三个不同的分享入口场景。

### 6.3 核心注入逻辑 I()

```java
public static void I(ContextMenu contextMenu) {
    // 遍历所有注册的菜单项 (tb9)
    for (tb9 item : i.values()) {
        // DexKit 动态定位 ContextMenu 的 add 方法
        // 参数签名: add(int, CharSequence, Drawable)
        Method addMethod = findMethod(
            contextMenu.getClass(),
            "add",
            params={Integer.TYPE, CharSequence.class, Drawable.class}
        );

        // 反射调用 add
        addMethod.invoke(contextMenu, item.a, item.b, item.c);
    }
}
```

### 6.4 数据载体 tb9

```java
class tb9 {
    int a;       // 菜单项 ID
    String b;    // 显示文字
    Drawable c;  // 图标
}
```

### 6.5 点击处理 J()

```java
public static void J(MethodHookParam param, MenuItem menuItem, Object obj) {
    int itemId = menuItem.getItemId();

    // 从 obj 反射提取 feedObject → getMediaType → getMediaList → toJSON
    // 将媒体信息序列化为 JSONObject 列表
    Field feedField = findField("feedObject");
    Object feed = feedField.get(obj);
    int mediaType = (Integer) invokeMethod(feed, "getMediaType");
    LinkedList mediaList = (LinkedList) invokeMethod(feed, "getMediaList");

    // 遍历 mediaList，调用 toJSON 获取 JSON 数据
    List<JSONObject> jsonList = new ArrayList<>();
    for (Object media : mediaList) {
        jsonList.add((JSONObject) invokeMethod(media, "toJSON"));
    }

    // 匹配 Provider 并执行回调
    for (tb9 item : i.values()) {
        if (item matches itemId) {
            // 执行回调，传入 mediaType + jsonList
            item.callback.invoke(mediaType, jsonList);
        }
    }
}
```

---

## 七、通用设计模式总结

### 7.1 Provider 插件模式（所有系统共用）

```
┌──────────────────────────────────────────────────────────┐
│                      Provider 接口                        │
│  定义 createMenuItems(param) → List<MenuItemData>         │
│  定义 shouldShow?(context) → Boolean                     │
│  定义 onClick(context) → void                             │
└──────────────────────┬───────────────────────────────────┘
                       │
         ┌─────────────┼─────────────┬─────────────┐
         │             │             │             │
     me8 (显示/隐藏)  j05 (清空未读)  p84 (强行停止)  w95 (WeKit)
```

每个 Feature 通过实现 Provider 接口自行注册，Api 单例负责在 Hook 回调中遍历所有 Provider 并调用。

### 7.2 DexKit 动态定位（所有系统共用）

所有微信内部方法名、类名都通过 DexKit DSL 动态查找，不硬编码：

```java
// 静态初始化时注册 DexKit 查找委托
static i74 methodAddItem = new md6(
    WeHomeScreenPopupMenuApi.class,
    "methodAddItem",   // 内部名称
    "getMethodAddItem()L...DexMethodDelegate;", 0
);
// 运行时通过 DexKitBridge 在微信 DEX 中搜索匹配的方法
```

### 7.3 Hook 点选择策略

| 策略 | 使用场景 | 示例 |
|------|---------|------|
| `afterHookedMethod` | 菜单创建（拦截返回后注入） | g99(11), t89(29), l29(20) |
| `beforeHookedMethod` | 字符串拦截替换 | o89(3), o89(4) |
| `afterHookedMethod` | 菜单点击（拦截后匹配+路由） | g99(12), x89(0), u59(4) |

### 7.4 身份标识机制

不同菜单系统使用不同的 item ID 策略：

| 系统 | ID 策略 | 示例 ID |
|------|--------|---------|
| 首页弹窗 | 自定义 int | 777010, 777011, 777012, 777015 |
| 聊天消息 | 自定义 int（777000 为合并入口） | 777000 |
| 会话列表 | Provider 自定义 int | 各 Provider 自定 |
| 朋友圈 | Provider 自定义 int | 各 Provider 自定 |
| 短视频 | 自定义 int | 各 Provider 自定 |

### 7.5 注册/注销模式

```java
// 所有 Provider 继承 fz7 基类
public class fz7 {
    public void D() {   // onActivate
        XxxApi.g.i.addIfAbsent(this);
    }
    public void C() {   // onDeactivate
        XxxApi.g.i.remove(this);
    }
}
```

当 Xposed 模块加载/卸载时，框架自动调用 `D()`/`C()`，实现热注册/热注销。

### 7.6 安全防护

- **try-catch 包裹每个 Provider**：一个 Provider 异常不影响其他
- **null 检查**：所有反射结果都有 null 判断，未找到则静默跳过
- **版本兼容**：关键类找不到时用 `k99.k()` 记录日志并跳过，不崩溃

### 7.7 整体对比：与 Settings 注入方案的异同

| 维度 | Settings 注入 | 菜单注入 |
|------|-------------|---------|
| Hook 数量 | 1 个入口类，9 个 Hook 点 | 每个菜单系统 2-6 个 Hook 点 |
| 定位方式 | DexKit + 反射方法签名匹配 | DexKit + 反射方法签名匹配 |
| 注入方式 | 动态字节码生成代理类 | 直接操作微信内部数据结构 |
| 目标数据结构 | Map/HashSet/SparseArray | SparseArray/ContextMenu/反射add |
| 字符串处理 | 虚拟资源ID + Hook getString | 直接传 String 参数 |
| 扩展性 | 固定条目 | Provider 插件式可扩展 |
| 复杂度 | 极高（字节码生成） | 中-高（反射操作） |
