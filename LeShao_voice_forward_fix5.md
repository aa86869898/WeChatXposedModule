# LeShao V3 语音转发根因 #5：b31.w 空实例，未设发送目标

> sent=true 但语音没送达 → b31.w 被创建了但没被配置

---

## 一、日志证据

```
14:14:27.904  SceneVoice: g() → 2714140725267af0290c2c6104
              ↑ 这里调了 g(target) 生成文件名，但文件名没有传给 b31.w

14:14:27.910  t(271414...,5000,0,null) → false
              ↑ setVoice 调了，但这是 VoiceLogic 层的登记

14:14:27.928  SceneVoice: b31.w run() → sent
              ↑ run() 没抛异常，但 b31.w 不知道发给谁、发什么文件
```

## 二、根因

`b31.w` 实例被创建后**直接 `run()`**，中间缺少配置步骤。

对照 scan 结果中 `tl.p0` / `ch4.w` 的方法：

| 方法 | 签名 | 作用 |
|------|------|------|
| `g` | `(String, e9) → boolean` | **设置发送参数**：talker + 消息对象 |
| `h` | `(f1) → void` | 设置回调/observer |
| `a` | `() → String` | 获取文件名 |
| `b` | `(e1) → void` | 设置某个对象 |
| `f` | `() → void` | 初始化/触发 |

`g(String, e9)` 是设置**对方 talker + 消息存储对象**的关键方法。
不调它，`b31.w` 就是空壳，`run()` 不知道往哪发。

---

## 三、修复方案

在 `run()` 之前补上配置：

```java
// Step 1: 创建 b31.w 实例
Object svc = b31_w_instance;  // 或 new b31.w(...)

// Step 2: ★ 设置发送目标 + 消息对象 ★
//         g(String talker, e9 msgObj) → boolean
Method gMethod = b31_w_class.getMethod("g", String.class, e9.class);
boolean ok = (Boolean) gMethod.invoke(svc, targetTalker, msgObj);

// Step 3: 可选 — 设置 observer/callback
//         h(f1 observer) → void
Method hMethod = b31_w_class.getMethod("h", f1.class);
hMethod.invoke(svc, observerOrNull);

// Step 4: run()
Method run = b31_w_class.getMethod("run");
run.invoke(svc);
```

其中：
- `targetTalker` — 目标 wxid/chatroom，例如 `"51719151602@chatroom"`
- `msgObj` — `com.tencent.mm.storage.e9` 类型的 WeChat 消息对象
  - 从 `forward: msgId=77613 via c() → e9` 可知你已有这个消息对象
  - 就是 `vo.c()` 返回的那个 `e9`

---

## 四、完整正确流程（5 个修复全部到位）

```
1. g(target)                           → 生成新文件名
2. w6.E(src) → w6.K(flat) → copy      → VFS 流拷贝
3. setVoice(name, dur, 0, null)        → VoiceLogic 登记（忽略 false）
4. Files.copy(原始源文件 → MD5路径)     → voice2/XX/YY/msg_...amr
5. ★ b31.w.g(target, msgObj)          → 设置发送目标和消息对象
6. b31.w.run()                         → 真正发送
```

---

## 五、五个修复汇总

| # | 根因 | 日志特征 | 修复 |
|---|------|----------|------|
| 1 | 缺少 MD5 子目录 | setVoice false | Files.copy 到 voice2/XX/YY/ |
| 2 | setVoice false 后直接 return | t() false → 中断 | 忽略返回值，继续 |
| 3 | md5 copy 源文件错了 | sent=true 没发出 | 从原始源文件拷 |
| 4 | `new tl.p0()` 无参构造 | NoSuchMethodError | 走 b31.w + run() |
| 5 | b31.w 空实例直接 run() | run() 通过但没送达 | run() 前调 `g(talker, msg)` |
