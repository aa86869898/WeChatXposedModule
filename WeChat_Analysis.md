# 微信 8.0.76 逆向分析报告

生成时间：$(date '+%Y-%m-%d %H:%M:%S')
工具：LSPilot AI 分析助手

---

## 一、消息发送机制

### 1.1 核心架构

```
消息同步扩展接口: sh3.b5
  └── a(Object, m50, int type, byte[] data, boolean, zy4 source, e5 callback)

PluginMessengerFoundation.onCreate() 注册关系:
  类型 5/8/9 → a2 (MessageSyncExtension)   ★ 消息收发核心
  类型 2/17/4 → v  (ContactSyncExtension)
  类型 7      → g0
  类型 1      → a3
  类型 78     → d  (bi3.d)

注册方式: sh3.a5.a(int type, b5 impl)
存储位置: sh3.a5.a (static ConcurrentHashMap<Integer, b5>)
```

### 1.2 关键类: com.tencent.mm.plugin.messenger.foundation.a2

| 方法 | 签名 | 作用 |
|------|------|------|
| a | a(Object, m50, int, byte[], boolean, zy4, e5) | 消息分发处理 |
| b | b(p0, zy4, e5) → q0 | 消息写入DB (static) |
| c | c(j4, Object, int, zy4, e5) | addMsg: 组装p0 → 调b() → 写DB → 回调 |

### 1.3 联网发送: a21.r (SendMsgMainFlowPPC)

```
handleSendMsgInitAction()        → 初始化发送
handleMsgInsertInitStgActionDone() → DB插入回调
handleMsgCommonFillMsgActionDone() → 填充字段
handleResendMsgInfoDealActionDone() → 重发处理
success()                        → 发送成功
```

参数对象: y11.r1 (从State取: "PPCKey_Params")

### 1.4 Hook 方案

方案A: Hook a2.b() 拦截消息入库
```bsh
hookMethodBefore("com.tencent.mm.plugin.messenger.foundation.a2", "b", param -> {
    log("消息入库: " + param[0]);
});
```

方案B: Hook sh3.b5.a() 拦截所有消息同步
```bsh
// 覆盖所有 b5 实现类 (a2, v, g0, a3, d)
```

方案C: Hook a21.r 拦截发送流程
```bsh
// handleSendMsgInitAction, success
```

---

## 二、定时/调度框架

### 2.1 n3 (MMHandler) — 最常用

类路径: com.tencent.mm.sdk.platformtools.n3

```bsh
handler = newInstance("com.tencent.mm.sdk.platformtools.n3", "MyTag");
handler.postDelayed(runnable, 5000);       // 5秒后
handler.postUIDelayed(runnable, 3000);     // 3秒后UI线程
handler.sendEmptyMessageDelayed(1, 1000);  // 1秒后发消息
```

### 2.2 u3 (MMHandlerThread) — 独立线程定时

类路径: com.tencent.mm.sdk.platformtools.u3

| 方法 | 功能 |
|------|------|
| i(Runnable, long) | postDelayed |
| k(Runnable, long) → int | postDelayed+返回ID |
| h(Runnable) | 立即post |
| l(Runnable) | removeCallbacks |
| b() → n3 | 获取内部Handler |

### 2.3 rv5.t0 (全局线程池+定时调度) — 最强大

类路径: rv5.t0
全局单例: rv5.t0.d

| 方法 | 签名 | 功能 |
|------|------|------|
| E | E(Runnable, long) → c | 延时执行一次 |
| d | d(Runnable, long, long) → c | 周期性(固定延迟) |
| e | e(Runnable, long, long) → c | 周期性(固定周期) |
| k | k(Runnable, long) → c | 延时(返回Future) |
| B | B(Runnable) → c | 立即执行 |
| p | p(String) → rv5.f | 创建线程池 |

```bsh
// 周期任务: 初始1秒, 之后每5秒
rv5.t0.d.d(runnable, 1000L, 5000L);

// 延时任务
rv5.t0.d.E(runnable, 3000L);

// 自定义线程池
pool = rv5.t0.d.p("MyPool");
pool.execute(runnable);
```

### 2.4 com.tencent.mars.alarm.AlarmManager — Mars层定时器

网络层alarm，通过JNI handle创建，设置CallBack回调。

### 2.5 场景对照

| 场景 | 推荐 |
|------|------|
| UI线程延时 | n3.postUIDelayed(r, delay) |
| 后台延时执行 | rv5.t0.d.E(r, delay) |
| 周期性轮询 | rv5.t0.d.d(r, init, period) |
| 自定义线程池 | rv5.t0.d.p("name") |
| 独立HandlerThread | u3 + i(r, delay) |
| 插件简单延时 | n3 最方便 |

---

## 三、messenger.foundation 包其他关键类

| 类 | 功能 |
|-----|------|
| PluginMessengerFoundation | 插件入口, hj()→ha5.b, ij()→ia5.j, onCreate()注册扩展 |
| v | ContactSyncExtension (联系人同步) |
| a3 | 消息类型1处理 |
| g0 | 消息类型7处理 |
| bi3.d | 消息类型78处理 |
| q | Qualifier枚举 |
| a1 | Qualifier枚举 |

### PluginMessengerFoundation

```java
hj() → ha5.b   // 消息存储访问
ij() → ia5.j   // 会话管理
kj(String) → boolean  // 判断是否加ticket
lj(int)         // 设置发送消息action flag
```

---

## 四、消息存储相关

| 类 | 功能 |
|-----|------|
| com.tencent.mm.storage.e9 | MsgInfo (消息记录) |
| com.tencent.mm.storage.f9 | MsgInfoStorage (消息存储) |
| com.tencent.mm.modelbase.p0 | 消息包装类 (含 j4 + flags) |
| a65.j4 | 原始消息数据 |
| a65.m50 | 消息元数据 (type等) |
| sh3.e5 | 消息回调接口 |
| sh3.zy4 | 消息来源 |

---

## 五、工具使用速查

### BSH Hook模板

```bsh
// Hook静态方法
hookMethodBefore(className, "methodName", param -> {
    log("called with: " + param[0]);
});

// 获取静态字段
pool = getStaticField("rv5.t0", "d");

// 调用静态方法
callStaticMethod("com.tencent.mm.plugin.messenger.foundation.a2", "b", p0, zy4, e5);
```

### 搜索技巧

- search_classes("keyword") → 模糊搜类名
- search_strings("keyword") → 搜DEX字符串
- find_method(method_name_pattern="sendMsg") → 搜方法
- view_strings(class_name) → 看类中字符串分布
- find_caller(class_name, method_name) → 追踪调用链
