# LeShao V3 语音转发失败根因分析

> 基于日志 07-25 13:19:06 ~ 13:19:08 的完整回放

---

## 一、日志回放（关键行）

```
13:19:06.042  forward: msgId=76283 talker=wxid_9ohhf82mrlgc22
13:19:08.295  voice: cid=122356072426bea2cb8fe51106
13:19:08.321  voice2: found .../voice2/48/2e/msg_122356072426bea2cb8fe51106.amr size=1366
              ↑ 源文件在 MD5 子目录 48/2e/ 下，位置正确

13:19:08.325  SceneVoice: g() → 0813190725267af02901b7a101
              ↑ getVoiceFileName(target) 成功，拿到新文件名

13:19:08.326  copy: ...voice2/48/2e/msg_122...amr → ...voice2/msg_0813190725267af02901b7a101.amr
              ↑ 拷到了 voice2 根目录！没有 MD5 子目录！ ❌

13:19:08.327  VFS create: w6.K(String,boolean)→OutputStream   ← ★ 确认 VFS 创建方法 = w6.K
13:19:08.327  w6.j(src) → true       ← VFS 存在性检查：源 OK
13:19:08.328  VFS stream copy ok     ← 流拷贝成功
13:19:08.329  w6.j(dst) → true       ← VFS 存在性检查：目标 OK

13:19:08.332  t(0813190725267af02901b7a101, 5000) → false
              ↑ VoiceLogic.setVoice() 返回 false！❌

13:19:08.334  sent=false             ← 最终发送失败 ❌
```

---

## 二、根因

文件放在了**错误的位置**。VoiceLogic 内部按**文件名 MD5 前 4 位做子目录**来查找文件，但代码把文件拷到了 `voice2/` 平铺根目录：

| | 期望路径 | 实际路径 |
|---|---|---|
| 新文件名 | `0813190725267af02901b7a101` | ← 相同 |
| 期望子目录 | `08/13`（文件名前4位） | — |
| 期望完整路径 | `voice2/08/13/msg_0813190725267af02901b7a101.amr` | — |
| **实际路径** | — | `voice2/msg_0813190725267af02901b7a101.amr` |

VoiceLogic 去 `voice2/08/13/` 找文件 → 找不到 → **返回 false**。

---

## 三、WeKit 的正确做法（对照）

WeKit 的 `WeMessageApi.K0()` 是**两步走**：

```java
// Step 1: VFS 拷贝到平铺路径（触发 VFS 登记）
Q(voiceFilePath, destPath);
// destPath = "voice2/msg_0813190725267af02901b7a101.amr"

// Step 2: 拿到 MD5 子目录路径，Files.copy 再拷一份到正确位置
String md5Path = k0(amrFileName);
// md5Path = "voice2/08/13/msg_0813190725267af02901b7a101.amr"

Files.copy(
    Paths.get(destPath),
    Paths.get(md5Path),
    StandardCopyOption.REPLACE_EXISTING
);

// Step 3: 现在 VoiceLogic 能找到了
VoiceLogic.setVoice(amrFileName, duration, 0, null);
```

而 LeShao V3 **少了第二步** — 做完 VFS 拷贝就停住了，没有把文件挪到 MD5 子目录。

---

## 四、修复方案

在 VFS 拷贝完成后，补上 MD5 子目录拷贝：

```java
// 已有代码：
w6.K(flatDestPath, false);   // VFS 创建 OutputStream
// ... stream copy ...
boolean ok = w6.j(flatDestPath);  // VFS 验证

// ★ 补上这段：
// 根据文件名前 4 位构造 MD5 子目录路径
String md5Prefix = newFileName.substring(0, 4);  // "0813"
String md5Dir = md5Prefix.substring(0, 2) + "/" + md5Prefix.substring(2, 4);  // "08/13"
String md5DestPath = voice2Dir + "/" + md5Dir + "/msg_" + newFileName + ".amr";

// 创建子目录（如果不存在）
new File(voice2Dir + "/" + md5Dir).mkdirs();

// 文件拷贝到正确位置
Files.copy(
    Paths.get(flatDestPath),
    Paths.get(md5DestPath),
    StandardCopyOption.REPLACE_EXISTING
);

// 然后再调用 setVoice
VoiceLogic.t(newFileName, duration);  // 现在能找到 voice2/08/13/msg_xxx.amr
```

---

## 五、VFS 四件套完整确认

| 方法 | 签名 | 用途 | 证据 |
|---|---|---|---|
| `w6.E` | `(String) → InputStream` | VFS 读取（打开文件读） | 用户提供 |
| `w6.K` | `(String, boolean) → OutputStream` | VFS 创建（打开文件写） | 日志 `VFS create: w6.K(String,boolean)→OutputStream` |
| `w6.j` | `(String) → boolean` | VFS 存在性检查 | 日志 `w6.j(src) → true` / `w6.j(dst) → true` |
| `w6.d` | `(String, String, boolean) → long` | VFS 其他操作 | 用户提供 |

---

## 六、完整文件操作流程总结

```
源语音文件：voice2/48/2e/msg_122356072426bea2cb8fe51106.amr
                              ↑ MD5 前 4 位 = 48/2e

1. g(target)         → 生成新文件名: 0813190725267af02901b7a101
2. w6.E(src)         → InputStream（VFS 读源文件）
3. w6.K(dst, false)  → OutputStream（VFS 创建目标）
4. stream copy       → 流拷贝
5. w6.j(dst)         → true（VFS 验证目标存在）
6. ★ 缺失：Files.copy 到 voice2/08/13/msg_081...amr
7. t(fileName, dur)  → FALSE（因为 voice2/08/13/ 下没有文件）
8. run(null)         → 不会执行，因为 t() 返回 false，提前终止
```

**总结：VFS 拷贝本身没问题，是目标路径缺少 MD5 子目录导致 VoiceLogic 找不到文件。**
