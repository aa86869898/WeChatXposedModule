# LeShao V3: tl.p0 实例获取 — 地毯式深度分析

> 基于 WeKit 源码逆向 + LeShao V3 scan 结果

---

## 一、分类澄清：三个层次

WeChat 语音发送体系分三层：

```
┌─────────────────────────────────────┐
│  SceneVoiceService (管理/调度层)     │  ← 单例，通过 getVoiceService() 获取
│  字符串: "MicroMsg.SceneVoiceService"│
│  方法: getVoiceService(), run()      │
└──────────────┬──────────────────────┘
               │ 内部创建/管理
┌──────────────▼──────────────────────┐
│  SceneVoice (协议层 = NetScene)      │  ← ch4.w / tl.p0
│  继承: b31.j 或 b31.l               │
│  方法: g(String,e9), h(f1), run()   │
└──────────────┬──────────────────────┘
               │ 入队
┌──────────────▼──────────────────────┐
│  NetSceneQueue (网络队列)            │
│  方法: doScene(dispatcher, scene)   │
└─────────────────────────────────────┘
```

- **`ch4.w` / `tl.p0`**：是协议层 NetScene 对象，不是单例，每次发送可能需要新建一个实例
- **SceneVoiceService**：是管理/调度层单例，内部持有/创建这些 NetScene 对象
- **b31.w**（你代码里用的）：可能是 SceneVoiceService 或者是一个 wrapper

---

## 二、WeKit 怎么获取 SceneVoiceService 实例

### Step 1: DexKit 定位 SceneVoiceService 类

```java
// nu9 case 24 — DexKit 搜索配置
uz0.i0(new String[]{
    "MicroMsg.SceneVoiceService",   // 类中出现的字符串
    "//voicetrymore",
    "getVoiceService %s"
});
```

对应的 ec4 delegate：`classSceneVoiceService` → `h[34]` 和 `h[37]`（两个 su6 引用同一类）

### Step 2: 找到 getVoiceService() 静态方法

在 `T0()` 中（Smali line 1060-1110）：

```java
// 获取 classSceneVoiceService
Class svcClass = T.j(h[37], this).e();

// 在 svcClass 中搜索方法：
//   - access flags: 8 (static)
//   - 返回类型: startRecvAndSend 的 declaringClass（即 VoiceService 接口/实现类本身）
t43 t = new t43(svcClass);
yc5 cfg = new yc5();
cfg.f0 = e0().e().getDeclaringClass();  // 返回类型 = VoiceService 自身
cfg.l(new int[]{8});                     // static
uz6 result = t.J(cfg);                   // → 找到 getVoiceService()

// 调用
Object service = result.a.invoke(null);  // static call → 获取单例
```

### Step 3: 调用 run()

```java
// Try to find run() via c0() delegate
Method run = c0().e();
if (run != null) {
    run.invoke(service, null);     // service.run(null)
} else {
    // Fallback: startRecvAndSend
    e0().e().invoke(i0(e0().e()), service);
}
```

---

## 三、LeShao V3 已有资源

Scan 结果已收录：

| 发现 | 类型 | 说明 |
|------|------|------|
| `ch4.w` | SceneVoice p0 | 语音协议类（基础版） |
| `tl.p0` | SceneVoice p0 | 语音协议类（增强版，多 l/m/n 方法） |
| `b31.j` | NetScene 接口 | 所有 NetScene 的基类接口 |
| `b31.l` | NetScene 接口变体 | 另一个 NetScene 接口变体 |

ch4.w 和 tl.p0 方法对比：

| 方法 | ch4.w | tl.p0 |
|------|-------|-------|
| `a()→String` | ✅ | ✅ |
| `b(e1)→void` | ✅ | ✅ |
| `c()→long` | ✅ | ✅ |
| `cancel()→boolean` | ✅ | ✅ |
| `d()→int` | ✅ | ✅ |
| `e()→int` | ✅ | ✅ |
| `f()→void` | ✅ | ✅ |
| `g(String,e9)→boolean` | ✅ | ✅ |
| `h(f1)→void` | ✅ | ✅ |
| `i()→boolean` | ✅ | ✅ |
| `j()→e9` | ✅ | ✅ |
| `k()→int` | ✅ | ✅ |
| `reset()→void` | ✅ | ✅ |
| `stop()→boolean` | ✅ | ✅ |
| `l()→void` | ❌ | ✅ |
| `m()→boolean` | ❌ | ✅ |
| `n()→void` | ❌ | ✅ |

---

## 四、三种方案获取/使用 tl.p0

### 方案 A：模仿 WeKit — 走 SceneVoiceService（推荐）

```java
// 1. 找 SceneVoiceService 类
//    搜索包含 "MicroMsg.SceneVoiceService" 的类
Class<?> svcClass = findClassByString("MicroMsg.SceneVoiceService");

// 2. 找 getVoiceService() 静态方法
Method getVoiceService = null;
for (Method m : svcClass.getDeclaredMethods()) {
    if (Modifier.isStatic(m.getModifiers()) 
        && m.getReturnType().equals(svcClass)  // 或某个接口
        && m.getParameterCount() == 0) {
        getVoiceService = m;
        break;
    }
}

// 3. 获取单例
Object service = getVoiceService.invoke(null);

// 4. 调用 run()
Method run = service.getClass().getDeclaredMethod("run");
run.invoke(service);
```

### 方案 B：直接构造 tl.p0（绕过 Service）

```java
// 1. 找到 tl.p0 的构造函数
//    ch4.w / tl.p0 继承 b31.j 或 b31.l
//    构造函数通常类似: new tl.p0(String talker, String fileName, int duration, ...)

// 2. 从 scan 结果，tl.p0 有这些关键方法：
//    g(String, e9)→boolean  → 设置语音参数（文件路径 + 消息对象）
//    h(f1)→void             → 设置回调
//    l()→void               → 可能是 run/start 的别名
//    m()→boolean            → 可能检查状态
//    n()→void               → 可能是 doScene

// 3. 创建实例并发送
Object sceneVoice = tl_p0_ctor.newInstance(/* params */);
tl_p0_g.invoke(sceneVoice, filePath, msgObj);  // g(String, e9)
tl_p0_l.invoke(sceneVoice);                     // l() = 触发发送

// 4. 或者入队到 NetSceneQueue
Object queue = getNetSceneQueue();
Method doScene = queue.getClass().getMethod("doScene", dispatcherType, b31_j_Type);
doScene.invoke(queue, dispatcher, sceneVoice);
```

### 方案 C：从 MmKernel 链路获取（最稳）

```java
// 1. 获取 MmKernel 实例
//    pu9 case 0: 搜索含 "MicroMsg.MMKernel" + "Initialize skeleton" 的类

// 2. MmKernel.account().getVoiceService() 或类似链路
Object kernel = getMmKernel();
Object account = kernel.getClass().getMethod("account").invoke(kernel);
Object voiceService = account.getClass().getMethod("getVoiceService").invoke(account);
voiceService.getClass().getMethod("run").invoke(voiceService);
```

---

## 五、你当前的问题定位

你的代码已经走到 `b31.w: start() called → sent`，但语音没发出去。

问题不在前面的文件拷贝（MD5 子目录已有 ✅），而可能在：

### 可能原因 1：b31.w 不是 SceneVoiceService

`b31.w` 可能是 NetScene 协议类而不是 Service。`start()` 可能不等于 `run()`。NetScene 需要先入队（`doScene`）才会真正执行网络请求。

检查 b31.w 的继承链：
```
b31.w 是否继承 b31.j 或 b31.l？
是 → 它是 NetScene 协议对象，需要 doScene 入队
否 → 可能是 Service
```

### 可能原因 2：start() 之前需要先 setVoice / g()

即使走 Service 路线，也需要在 `run()` 之前先告诉它发哪个文件。WeKit 在 T0()/run() 之前做了：

```java
// setVoice #1 (VoiceLogic)
setVoice(name, dur, 0, null);

// setVoice #2 (SceneVoiceService 类上的方法)
voiceService_setVoice(name, dur, 0, null);
```

### 可能原因 3：需要 NetSceneQueue.doScene()

如果 `b31.w` 是 NetScene 协议对象，流程应该是：

```java
Object queue = NetSceneQueue.getInstance();  // 需要定位
Object dispatcher = queue.getDispatcher();   // 或用 null
queue.doScene(dispatcher, b31_w_instance);
```

---

## 六、建议的调试步骤

```
1. 打印 b31.w 的继承链
   b31.w.getClass().getSuperclass().getName()
   b31.w.getClass().getInterfaces()

2. 如果继承 b31.j/b31.l → 走 NetScene 路线（doScene 入队）
   如果继承其他 → 走 Service 路线

3. 尝试用 tl.p0 替代 b31.w：
   tl.p0 有 l()/m()/n() 三个额外方法
   l() 可能是启动发送，n() 可能是 doScene

4. 补充 setVoice 调用（在 run/start 之前）
```

---

## 七、快速验证：补上缺少的步骤

```java
// ===== 在 b31.w.start() 之前补上 =====

// 补充第 2 次 setVoice（这次通过 SceneVoiceService 类）
// WeKit 中这个方法是 SceneVoiceService 类上的，不是 VoiceLogic
// 签名: boolean setVoice(String fileName, int duration, int flag, Object extra)

// 然后再 start/run
b31_w_instance.start();  // 或 run() / l()
```

如果仍然不行，尝试 `tl.p0.l()` 替代 `b31.w.start()`。
