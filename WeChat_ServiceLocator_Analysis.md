# 微信 8.0.76 服务定位器 (ServiceLocator) 深度分析

> 生成时间：$(date '+%Y-%m-%d %H:%M:%S')
> 工具：LSPilot AI 分析助手

---

## 一、核心结论

微信的服务定位器是 **`pa5.n0`**（Log Tag: `"MicroMsg.ServiceManager"`）。

它不是 `a21.b0` 获取 ViewModel 的定位器，而是反过来的——ViewModel 通过它获取**其他已注册的服务**。

```java
// a21.b0.vj() 中的实际用法：
((k4) n0.c(k4.class)).kj(e9Var.N0())   // 获取 k4 服务，调 kj()
((r4) n0.c(r4.class)).d                 // 获取 r4 服务，取字段 d
```

---

## 二、`pa5.n0` — ServiceManager 完整 API

### 类路径：`pa5.n0`
### 类型：`abstract class`（全是静态方法和字段）

### 2.1 静态字段

| 字段 | 类型 | 用途 |
|------|------|------|
| `a` | `Application` | 全局 Application 实例 |
| `b` | `y` | **服务注册表**（`y.a(Class)` → `m`） |
| `c` | `ForkJoinPool` | 主线程池（6线程） |
| `d` | `ForkJoinPool` | 辅助线程池（6线程） |
| `e` | `a[]` | 账号初始化状态持有者 |
| `f` | `boolean[]` | 初始化完成标志 |
| `g` | `Set<w>` | 已激活服务集合（LinkedHashSet, 64容量） |
| `h` | `boolean` | 账号初始化是否完成 |
| `i` | `boolean[]` | 预加载完成标志 |
| `j` | `boolean[]` | 账号释放标志 |
| `k` | `boolean` | 全局标志 |
| `l` | `boolean[]` | 首屏延迟标志 |
| `m` | `Handler` | 主线程 Handler |
| `n` | `ThreadLocal` | 当前激活的服务 |
| `s` | `ConcurrentHashMap<Class, Boolean>` | `h()` 方法缓存（64容量） |

### 2.2 静态方法

| 方法 | 签名 | 功能 |
|------|------|------|
| **`c`** ★ | `c(Class) → m` | **服务定位核心**：根据接口 Class 获取服务实例 |
| `d` | `d(Application, y, a)` | 初始化 ServiceManager（设置 App、Registry、线程池） |
| `e` | `e(boolean, boolean)` | 触发所有已激活服务的 `onAccountInitialized()` |
| `f` | `f(Callable) → Object` | 在线程安全上下文中执行 Callable |
| `g` | `g() → boolean` | 检查账号是否初始化 |
| `h` | `h(Class) → boolean` | 检查某服务是否已激活（带缓存） |
| `a` | `a(Iterable, r, boolean, boolean)` | 预加载一组服务 |
| `b` | `b(String)` | 断言已初始化（否则抛异常） |
| `j` | `j(w, w, r, boolean, boolean, boolean)` | 服务状态切换 |
| `k` | `k(boolean)` | 设置全局标志 |
| `l` | `l(Collection, boolean)` | 等待 Future 列表完成 |

---

## 三、`c(Class)` — 核心服务获取流程

### 3.1 调用链

```
n0.c(Class)
  │
  ├─ b("calling getService(...)")     // 断言已初始化
  │
  └─ f(new f0(cls, false))           // 线程安全执行
       │
       └─ f0.call()
            │
            ├─ n0.b.a(cls)            // y.a(Class) → 从注册表取 w（服务）
            │
            ├─ n0.j(old, svc, r.g, ...)// 状态切换到 INITIALIZED
            │
            └─ return svc;            // 返回 m（w implements m）
```

### 3.2 完整源码（反编译）

```java
// pa5.n0.c()
public static m c(Class cls) {
    b("calling getService(...)");
    return (m) f(new f0(cls, false));
}

// pa5.n0.f()
public static Object f(Callable callable) {
    if (Looper.myLooper() != null) {
        return callable.call();      // 主线程直接执行
    }
    // 非主线程：注入 Looper 后执行
    // ... Looper TLS 注入逻辑 ...
    return callable.call();
}

// pa5.f0.call()
public Object call() {
    Class cls = this.d;
    // 1. 从注册表获取服务
    w a = n0.b.a(cls);               // y.a(Class) → w

    // 2. 保存旧服务 → 设置新服务到 ThreadLocal
    ThreadLocal threadLocal = n0.n;
    w old = (w) threadLocal.get();
    threadLocal.set(a);

    // 3. 状态切换：当前状态 → INITIALIZED
    n0.j(old, a, r.g, this.e, false, false);

    // 4. 恢复旧服务
    threadLocal.set(old);

    return a;                         // 返回服务实例
}
```

---

## 四、类型体系

```
pa5.m (接口)  ← c(Class) 的返回类型
  └─ extends is.n (空标记接口)
       └─ implements by pa5.w (服务基类)

pa5.w (服务基类)
  ├── onCreate(Context)          → 服务创建
  ├── onAccountInitialized(Context) → 账号初始化
  ├── onAccountReleased(Context)    → 账号释放
  ├── transitLifecycleStatusOnDemand(...) → 状态切换
  └── isTransitingToOrArrivedAt(r) → 状态查询

pa5.r (生命周期枚举)
  ├── ERROR
  ├── INACTIVE
  ├── ACTIVATED
  └── INITIALIZED

pa5.y (服务注册表)
  └── a(Class) → m               → 根据 Class 查找服务

pa5.q (服务描述符，用于注册)
  └── 实现 pa5.n 接口
```

---

## 五、`a21.b0` 中的实际用法

### 5.1 用法 1：获取 `k4` 服务

```java
// a21.b0.vj() Line 244:
((k4) n0.c(k4.class)).kj(e9Var.N0())
```

对应 Smali：
```smali
const-class v13, Lsh3/k4;              // k4 = sh3.k4
invoke-static {v13}, Lpa5/n0;->c(Ljava/lang/Class;)Lpa5/m;
move-result-object v9
check-cast v9, Lsh3/k4;                // 转型为 k4
invoke-virtual {v7}, Ldm/c8;->N0()Ljava/lang/String;  // e9Var.N0()
move-result-object v10
check-cast v9, Lcom/tencent/mm/plugin/messenger/foundation/PluginMessengerFoundation;
invoke-virtual {v9, v10}, ...->kj(Ljava/lang/String;)Z
```

**实际实现类**：`com.tencent.mm.plugin.messenger.foundation.PluginMessengerFoundation`（implements `sh3.k4`）

### 5.2 用法 2：获取 `r4` 服务

```java
// a21.b0.vj() Line 247:
((r4) n0.c(r4.class)).d
```

对应 Smali：
```smali
const-class v9, Lsh3/r4;
invoke-static {v9}, Lpa5/n0;->c(Ljava/lang/Class;)Lpa5/m;
move-result-object v9
check-cast v9, Lsh3/r4;
iget-object v1, v9, Lsh3/r4;->d:Lsh3/i1;  // 取字段 d
```

### 5.3 用法 3：获取 `c4` 服务（通过 `j1.s()` 而非 `n0.c()`）

注意！在 `a21.b0` 中，`c4` 用的是**另一套机制**：

```java
((c4) j1.s(c4.class)).wj()  // hm0.j1.s() 而非 pa5.n0.c()
```

这是 `hm0.j1`，微信还有第二套服务定位器，下面会说明。

---

## 六、微信的双服务定位器架构

微信有两套服务定位器，用途不同：

### 6.1 `pa5.n0` — "Feature Service" 管理器

| 属性 | 值 |
|------|-----|
| 包 | `pa5` |
| Log Tag | `MicroMsg.ServiceManager` |
| 注册表 | `pa5.n0.b` (类型 `pa5.y`) |
| 获取方法 | `n0.c(Class)` → `pa5.m` |
| 生命周期 | `INACTIVE → ACTIVATED → INITIALIZED` |
| 用途 | **业务服务**：k4, r4 等 |

### 6.2 `hm0.j1` — "MM Kernel" 服务管理器

| 属性 | 值 |
|------|-----|
| 包 | `hm0` |
| Log Tag | 各 Kernel 类自行定义 |
| 注册表 | 内置在 j1 中 |
| 获取方法 | `j1.s(Class)` → `mm0.a` |
| 用途 | **内核服务**：c4 (MsgInfoStorage), 等 |

### 6.3 使用区分

```java
// Feature Service (pa5.n0):
k4 k4Service = (k4) n0.c(k4.class);     // PluginMessengerFoundation
r4 r4Service = (r4) n0.c(r4.class);     // MsgSource 组装器

// Kernel Service (hm0.j1):
c4 msgStorage = (c4) j1.s(c4.class);    // MsgInfoStorage (f9/h2)
```

---

## 七、BSH 中调用服务定位器

```bsh
// ===== pa5.n0 服务 =====

// 获取 k4 服务（PluginMessengerFoundation）
k4 = callStaticMethod("pa5.n0", "c", sh3.k4.class);
// 或直接用类名
k4 = callStaticMethod("pa5.n0", "c", 
    findClass().name("sh3.k4").single());

// 获取 r4 服务
r4 = callStaticMethod("pa5.n0", "c", 
    findClass().name("sh3.r4").single());

// ===== hm0.j1 服务 =====

// 获取 c4 服务（MsgInfoStorage）
c4 = callStaticMethod("hm0.j1", "s", 
    findClass().name("sh3.c4").single());

// ===== 检查服务是否激活 =====
isActive = callStaticMethod("pa5.n0", "h", someClass);
```

---

## 八、关键类速查表

| 类 | 全路径 | 角色 |
|-----|--------|------|
| **n0** | `pa5.n0` | ★ ServiceManager 入口 |
| **m** | `pa5.m` | 服务标记接口（extends `is.n`） |
| **w** | `pa5.w` | 服务基类（生命周期） |
| **y** | `pa5.y` | 服务注册表（`a(Class)→m`） |
| **r** | `pa5.r` | 生命周期枚举（ERROR/INACTIVE/ACTIVATED/INITIALIZED） |
| **q** | `pa5.q` | 服务描述符（用于注册遍历） |
| **f0** | `pa5.f0` | Callable：`call()` 执行实际查找 |
| **k4** | `sh3.k4` | 示例服务接口（PluginMessengerFoundation 实现） |
| **r4** | `sh3.r4` | 示例服务接口（MsgSource 组装器） |
| **c4** | `sh3.c4` | 内核服务接口（MsgInfoStorage） |
| **j1** | `hm0.j1` | 第二套服务定位器（`s(Class)`） |
