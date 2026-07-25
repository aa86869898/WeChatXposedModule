# LeShao V3 语音转发根因 #6：b31.w 不是 SceneVoice 类

> NoSuchMethodError b31.w#g[String, e9] → 方法签名不匹配，走错类了

---

## 一、日志

```
14:25:38.693  SceneVoice error: NoSuchMethodError b31.w#g[class java.lang.String, class com.tencent.mm.storage.e9]
14:25:38.693  doForward: sent=false
```

## 二、根因

`b31.w` 上没有 `g(String, e9)` 方法，它根本就不是 SceneVoice 类。

| 类 | `g(String, e9)→boolean` | `run()` | 身份 |
|---|---|---|---|
| `ch4.w` | ✅ | ❌（有 doScene） | SceneVoice 协议类（基础版） |
| `tl.p0` | ✅ | ❌（有 l/m/n） | SceneVoice 协议类（增强版） |
| `b31.w` | ❌ | ✅ | 未知类，不是 SceneVoice |

## 三、修复：改用 ch4.w 或 tl.p0

### 方案 A：ch4.w

```java
Class<?> ch4w = findClass("ch4.w");

// 构造函数（需要反编译确认参数）
Constructor<?> ctor = ch4w.getDeclaredConstructors()[0];
Object scene = ctor.newInstance(targetTalker, msgObj /* , ... */);

// 设置语音文件
Method g = ch4w.getMethod("g", String.class, e9.class);
g.invoke(scene, filePath, msgObj);

// 入队发送
Method doScene = ch4w.getMethod("doScene", dispatcherType, sceneType);
doScene.invoke(scene, dispatcher, scene);
```

### 方案 B：tl.p0（多了 l/m/n）

```java
Class<?> tlp0 = findClass("tl.p0");
Object scene = ctor.newInstance(targetTalker, msgObj /* , ... */);

Method g = tlp0.getMethod("g", String.class, e9.class);
g.invoke(scene, filePath, msgObj);

// l() 可能是完整发送入口
Method l = tlp0.getMethod("l");
l.invoke(scene);
```

### tl.p0 方法清单

| 方法 | 签名 | 推测作用 |
|------|------|----------|
| `a` | `()→String` | 获取文件名 |
| `b` | `(e1)→void` | 设置某个对象 |
| `c` | `()→long` | 获取时长/大小 |
| `d` | `()→int` | 获取某状态值 |
| `e` | `()→int` | 获取某状态值 |
| `f` | `()→void` | 初始化 |
| `g` | `(String, e9)→boolean` | ★ 设置目标 talker + 消息对象 |
| `h` | `(f1)→void` | 设置回调/observer |
| `i` | `()→boolean` | 检查状态 |
| `j` | `()→e9` | 获取消息对象 |
| `k` | `()→int` | 获取类型 |
| `l` | `()→void` | ★ 可能是发送入口 |
| `m` | `()→boolean` | 检查发送状态 |
| `n` | `()→void` | 可能是 doScene |
| `cancel` | `()→boolean` | 取消 |
| `reset` | `()→void` | 重置 |
| `stop` | `()→boolean` | 停止 |

---

## 四、六个修复汇总

| # | 根因 | 修复 |
|---|------|------|
| 1 | 缺少 MD5 子目录 | Files.copy → voice2/XX/YY/ |
| 2 | setVoice false 后直接 return | 忽略 false，继续执行 |
| 3 | md5 copy 源文件错了 | 从原始源文件拷 |
| 4 | `new tl.p0()` 无参构造 | 走 b31.w（临时绕过） |
| 5 | b31.w 空实例直接 run() | 补 g(talker, msg) |
| 6 | **b31.w 根本不是 SceneVoice** | **改用 ch4.w 或 tl.p0** |
