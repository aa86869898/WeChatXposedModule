# LeShao V3 语音转发根因 #4：tl.p0 没有无参构造

> 日志：`NoSuchMethodError tl.p0#[]` — 尝试 `new tl.p0()` 失败

---

## 一、日志回放

```
14:05:45.620  SceneVoice: g() → 4514050725267af0290ca93105
14:05:45.622  VFS create: w6.K(String,boolean)→OutputStream
14:05:45.624  VFS stream copy ok
14:05:45.630  t(451405...,5000,0,null) → false, continuing
14:05:45.633  md5 copy original ...voice2/45/f3/msg_0422...amr
              → ...voice2/45/14/msg_4514...amr
14:05:45.648  md5 copy ok
14:05:45.649  ★ SceneVoice error: NoSuchMethodError tl.p0#[]
14:05:45.650  doForward: sent=false
```

所有文件操作都正确，最后一步 `new tl.p0()` 崩溃。

---

## 二、根因

`tl.p0` 不是无参构造的对象，不能 `new tl.p0()`。

从 scan 结果看 tl.p0 的方法签名：
```
g(String, e9)→boolean   — 需要 talker + msg 对象
b(e1)→void              — 需要回调对象
h(f1)→void              — 需要另一个对象
a()→String
c()→long
d()→int / e()→int / k()→int
f()→void / j()→e9 / i()→boolean
cancel()/reset()/stop()→boolean/void
l()/m()/n()             — 额外方法
```

它的构造至少需要 `(String talker, e9 msgObj, ...)` 之类的参数。

---

## 三、WeKit 的做法（不直接构造 tl.p0）

WeKit 完全不走 `new tl.p0()`，而是通过 **SceneVoiceService 单例**：

```
WeChat 语音发送体系：

SceneVoiceService (管理/调度层)   ← 单例，static getVoiceService()
  │
  ├── 内部持有 tl.p0 / ch4.w 实例
  │
  └── run() → 触发内部的 NetScene 去 doScene
```

### WeKit 的 T0() 代码路径

```java
// 1. 定位 SceneVoiceService 类
//    DexKit 搜索条件:
//      字符串: "MicroMsg.SceneVoiceService"
//             + "//voicetrymore"
//             + "getVoiceService %s"
Class<?> svcClass = T.j(h[37], this).e();

// 2. 找 static 无参方法，返回类型 = VoiceService 接口/自身
t43 cfg = new t43(svcClass);
cfg.f0 = e0().e().getDeclaringClass(); // 返回类型
cfg.l(new int[]{8});                    // static
uz6 getSvc = cfg.J();

// 3. 调用 → 获取单例
Object service = getSvc.a.invoke(null);

// 4. 找 run() 方法
Method run = c0().e();
if (run != null) {
    run.invoke(service, null);   // service.run(null)
} else {
    // fallback: startRecvAndSend
    e0().e().invoke(i0(e0().e()), service);
}
```

---

## 四、修复方案

不要 `new tl.p0()`，改用 SceneVoiceService 单例：

```java
// ===== 方案 A: 走 SceneVoiceService =====

// 1. 找包含 "MicroMsg.SceneVoiceService" 的类
Class<?> svcClass = findClassByStrings("MicroMsg.SceneVoiceService");

// 2. 找 static 无参方法 → 获取单例
Method getVoiceService = null;
for (Method m : svcClass.getDeclaredMethods()) {
    if (Modifier.isStatic(m.getModifiers())
        && m.getParameterCount() == 0) {
        getVoiceService = m;
        break;
    }
}
Object service = getVoiceService.invoke(null);

// 3. 调 run()
service.getClass().getMethod("run").invoke(service, null);


// ===== 方案 B: 如果 b31.w 就是 SceneVoiceService =====
// 把 b31_w.start() 换成:
b31_w.getClass().getMethod("run").invoke(b31_w, null);
```

---

## 五、四个修复汇总

| # | 根因 | 日志表现 | 修复 |
|---|------|----------|------|
| 1 | 缺少 MD5 子目录 | setVoice false, 文件找不到 | Files.copy 到 voice2/XX/YY/ |
| 2 | setVoice false 后直接 return | t() false → 中断 | 忽略返回值，继续 |
| 3 | md5 copy 源文件用错 + send 入口错 | sent=true 但没发出去 | 源用原始文件 + 调 run() |
| 4 | `new tl.p0()` 无参构造不存在 | NoSuchMethodError tl.p0#[] | 走 SceneVoiceService 单例 |
