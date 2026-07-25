# LeShao V3 语音转发根因 #3：b31.w.start() ≠ 真正发送

> 修复 #1（MD5子目录）✅ + 修复 #2（忽略setVoice返回值）✅ → sent=true 但实际没发出去

---

## 一、当前状态

```
13:47:02.385  md5 copy ok                              ✅
13:47:02.388  t(0213470725267af0290a6a5102,5000,0,null) → false
13:47:02.389  t() false, continuing (WeKit ignores too)  ✅
13:47:02.390  b31.w: instance created
13:47:02.391  b31.w: start() called → sent
13:47:02.392  doForward: sent=true
```

代码全通，但语音没发出去。

---

## 二、对照 WeKit 的完整 K0() 流程

WeKit 的 WeMessageApi.K0() 在 VFS copy + setVoice 之后的真实步骤：

```
1. VFS copy: Q(src, dst_flat)
    → voice2/msg_0213...amr（平铺）

2. setVoice #1: VoiceLogic.setVoice(name, dur, 0, null)
    → false → 忽略

3. T0(): startRecvAndSend

4. getVoiceFileName(target, "amr_") → "0213470725267af0290a6a5102"

5. k0(name) → voice2/02/13/msg_0213470725267af0290a6a5102.amr（MD5路径）

6. ★ Files.copy(原始源文件 → MD5路径) ★
   源: voice2/12/7a/msg_382353072426bea2cb8a3db102.amr（原始文件）
   目标: voice2/02/13/msg_0213470725267af0290a6a5102.amr
   ↑ 关键：从原始文件拷，不是从 VFS 平铺文件拷！

7. setVoice #2: 通过 SceneVoiceService 类再调一次 setVoice(name, dur, 0, null)

8. 获取 SceneVoiceService 实例（scan 结果是 ch4.w 或 tl.p0）

9. service.run(null) → 真正网络发送
```

---

## 三、LeShao V3 缺了什么

| 步骤 | WeKit | LeShao V3 |
|------|-------|-----------|
| VFS copy | ✅ | ✅ |
| setVoice #1 | ✅ | ✅ |
| startRecvAndSend | ✅ | ✅ |
| Files.copy(原始源→MD5) | **从原始文件拷** | **从 VFS 平铺文件拷** ❌ |
| setVoice #2 (VoiceService类) | ✅ | ❌ |
| SceneVoice 实例获取 | `ch4.w` / `tl.p0` | `b31.w`（可能不对） |
| run(null) | ✅ | ❌ 只调了 start() |

---

## 四、根因 #3：两个问题

### 问题 A：Files.copy 的源文件不对

```
你的做法:
  voice2/msg_0213...amr（w6.K 创建的 VFS 平铺文件）
    → Files.copy →
  voice2/02/13/msg_0213...amr

WeKit 的做法:
  voice2/12/7a/msg_3823...amr（原始真实文件）
    → Files.copy →
  voice2/02/13/msg_0213...amr
```

`w6.K()` 是 VFS 抽象层的创建操作，输出流写完后文件可能只存在于微信的 VFS 缓存中，
还没有落地到真实文件系统。从 VFS 平铺文件拷出来的内容可能是空的或不完整的。

### 问题 B：b31.w.start() 不是发送入口

WeKit 最终调用的是 `SceneVoiceService.run(null)`，而你用的是 `b31.w.start()`。
从 scan 结果看，正确的 SceneVoice 类是 `ch4.w` 或 `tl.p0`，不是 `b31.w`。

---

## 五、修复方案

```java
// ===== 完整正确流程 =====

// 1. 生成新文件名
String newName = g(target);  // → "0213470725267af0290a6a5102"

// 2. VFS copy 到平铺路径
w6.E(srcPath);              // VFS 读原始文件
w6.K(flatPath, false);      // VFS 创建平铺目标
streamCopy(in, out);        // 流拷贝
w6.j(flatPath);             // VFS 验证

// 3. setVoice #1（忽略返回值）
setVoice(newName, duration, 0, null);

// 4. startRecvAndSend
T0();

// 5. 获取 MD5 子目录全路径
String md5Path = k0(newName);  // voice2/02/13/msg_0213...amr

// 6. ★ 从原始真实文件拷到 MD5 路径（不是从 VFS 平铺文件！）
Files.copy(
    Paths.get(srcPath),      // ← 原始 source voice2/12/7a/msg_3823...amr
    Paths.get(md5Path),
    REPLACE_EXISTING
);

// 7. ★ 第二次 setVoice（通过 SceneVoiceService 的类）
//    找到 VoiceService 类中签名 (String,int,int,null)→boolean 的方法
voiceServiceSetVoice.invoke(null, newName, duration, 0, null);

// 8. ★ 获取正确的 SceneVoice 实例
//    scan 结果: ch4.w 或 tl.p0
Object sceneVoice = getSceneVoiceInstance();  // ch4.w 或 tl.p0

// 9. ★ 调用 run() 真正发送
sceneVoice.run(null);
```

---

## 六、三个修复汇总

| # | 根因 | 修复 |
|---|------|------|
| 1 | 目标路径缺少 MD5 子目录 | 加 Files.copy 到 voice2/XX/YY/ |
| 2 | setVoice false 后直接 return | 忽略返回值，继续执行 |
| 3 | Files.copy 源文件错 + 发送入口错 | 源用原始文件 + 调正确的 run() |
