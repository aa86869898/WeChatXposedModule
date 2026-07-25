# LeShao V3 语音转发根因 #2（修正）：setVoice 返回 false 不应中断

> 用户确认：`t()` 传了 4 个参数，和 WeKit 一致。日志只打了前两个。

---

## 一、真相：WeKit 也不管 setVoice 的返回值

`WeMessageApi.K0()` 源码（第 1327-1333 行）：

```java
// 调用 setVoice
Object invoke3 = setVoice.invoke(instance, fileName, duration, 0, null);
Boolean bool = invoke3 instanceof Boolean ? (Boolean) invoke3 : null;

if (!(bool != null ? bool.booleanValue() : false)) {
    // ★ 只打日志，不 return，不 abort！
    hu9.k("WeMessageApi",
        "VoiceLogic.setVoice returned false, still starting voice service: fileName="
        + str3 + ", target=" + str);
}

T0();  // ← 无论如何都继续执行 startRecvAndSend
```

**WeKit 也收到 false，但它无视了。**

---

## 二、WeKit 完整执行顺序

```
1. VFS copy (Q)              → voice2/msg_xxx.amr（平铺）
2. setVoice(name, dur, 0, null) → 可能 false → ★ 忽略
3. T0()                       → startRecvAndSend
4. getAmrFullPath → MD5 路径  → voice2/XX/YY/msg_xxx.amr
5. Files.copy 到 MD5 路径
6. SceneVoiceService.run(null) → 真正发送
```

**关键：setVoice 在第 2 步、MD5 拷贝在第 5 步。setVoice 在 MD5 拷贝之前就被调用了，所以它返回 false 跟 MD5 路径完全无关，就是它本身的正常行为。**

---

## 三、LeShao V3 的问题

```
1. VFS copy                  → voice2/msg_xxx.amr
2. Files.copy 到 MD5 路径    → voice2/14/13/msg_xxx.amr
3. t(fileName, dur, ...)     → false
4. return sent=false         ← ❌ 直接中断，后面的都没执行
```

**修正：把 `t()` 的返回值当作 warning 而不是 fatal error，继续执行后续。**

---

## 四、修复

```java
// ===== 修复前 =====
boolean ok = t(fileName, duration, 0, null);
if (!ok) {
    Log.w("VF", "setVoice returned false, but continuing...");
    //    ↑ 之前这里是 return，直接中断了
}
// ★ 不要 return，继续往下

// startRecvAndSend
startRecvAndSend();

// SceneVoiceService.run(null)
voiceService.run(null);

sent = true;
```

---

## 五、最终结论

| # | 问题 | 修复 |
|---|------|------|
| 1 | 目标路径缺少 MD5 子目录 | 已修复 ✅ |
| 2 | `setVoice` 返回 false 后直接中断 | `setVoice` 返回 false 时只打 warning，继续执行 `startRecvAndSend` + `run()` |
