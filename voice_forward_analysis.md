# WeKit 语音转发完整流程分析

> 分析目标：WeKit 从长按语音消息 → 弹出菜单 → 点击转发 → 选择目标 → 转发成功的完整实现链路

---

## 一、总架构

语音转发涉及 **5 个阶段**：

```
长按语音消息 → 弹出上下文菜单 → 点击"转发" → 选择目标 → 逐条转发
```

核心类链：
`WeChatMessageContextMenuApi` → `d43` → `q90` → `ry1` → `c43` → `WeMessageApi.K0()`

---

## 二、阶段一：注册上下文菜单项

### 类：`d43`

`d43` 是"转发"功能的 Feature 入口，实现了 `jc8`（Feature 基类）和 `sq9` 接口。

```java
// d43.C() — 注册：把"转发"菜单项注入微信上下文菜单
public final void C() {
    WeChatMessageContextMenuApi.g.getClass();
    WeChatMessageContextMenuApi.N(this);
}

// d43.D() — 注销
public final void D() {
    WeChatMessageContextMenuApi.g.getClass();
    WeChatMessageContextMenuApi.K(this);
}
```

### 菜单项结构 `d43.a()`

```java
public final List a() {
    return hu.O(new tq9(
        777010,                    // 菜单项 ID
        "转发",                    // 显示文字
        a43Var,                    // 图标 Drawable
        aq3Var,                    // SVG 矢量图标 (forward)
        new sw2(25),               // 单条消息条件 → 始终 return Boolean.TRUE
        new vq9(new sw2(26), q90.i0),  // 多选条件 + 多选回调
        q90.j0                     // 单击回调（单条消息）
    ));
}
```

**关键：条件过滤器无条件放行**
- `sw2(25)`：单条消息过滤器 → 直接 `return Boolean.TRUE`（所有类型消息都可用）
- `sw2(26)`：多条消息过滤器 → 直接 `return Boolean.TRUE`

---

## 三、阶段二：Hook 微信上下文菜单

### 类：`WeChatMessageContextMenuApi`

```java
// D() — 在模块加载时 hook 微信 4 个关键方法
public final void D() {
    x50.x(this, (x52) j.j(ec4VarArr[0], this), new in9(21));  // methodCreateMenu
    x50.x(this, (x52) k.j(ec4VarArr[1], this), new in9(22));  // methodSelectMenuItem
    x50.x(this, (x52) l.j(ec4VarArr[2], this), new in9(23));  // methodMultiCreateMenu
    x50.x(this, (x52) m.j(ec4VarArr[3], this), new in9(24));  // methodMultiSelectMenuItem
}
```

### 菜单构建 `P()` 方法

当用户长按消息弹出菜单时调用：

```java
public static void P(View view, Object obj, ArrayList arrayList) {
    ArrayList t0 = v41.t0(i.values());  // 已注册的所有菜单项

    // 第一轮：遍历所有 vq9 类型（有多选条件检查的）
    for (tq9 item : t0) {
        vq9 vq9Var = item.f;
        if (vq9Var != null && vq9Var.a.B(arrayList)) {
            // 条件满足 → 加入可选菜单列表
            uq9Var = new uq9(item.b, item.d, clickHandler);
            arrayList2.add(uq9Var);
        }
    }

    // 第二轮：处理没有多选条件 (wq9) 的项
    for (tq9 item : t0) {
        if (!(item.f instanceof wq9) || arrayList.isEmpty()) { continue; }
        // 遍历每条消息检查条件
        for (jb5 msg : arrayList) {
            if (!item.e.B(msg)) { break; }  // 不满足则跳过
        }
        uq9Var = new uq9(item.b, item.d, clickHandler);
        arrayList3.add(uq9Var);
    }

    // 展示菜单对话框
    dg1.W(context, true, new ta1(new j8(20, arrayList, arrayList2, arrayList3), true, 1140326279));
}
```

---

## 四、阶段三：用户点击"转发"

### 类：`q90`

#### 单条消息点击 (`q90.j0` = `q90(7)`)

```java
case 7:
    View view4 = (View) obj;
    jb5 jb5Var5 = (jb5) obj3;
    d43 d43Var2 = d43.i;
    nd2 nd2Var = new nd2(6, jb5Var5);   // 包装单条消息为列表
    d43Var2.getClass();
    sx1 sx1Var4 = y82.a;
    dh1.B(is0.c(bx1.Z), null, null, new ry1(view4, nd2Var, null, 7), 3);
    return v79Var;
```

#### 多条消息点击 (`q90.i0` = `q90(6)`)

```java
case 6:
    View view3 = (View) obj;
    List list2 = (List) obj3;
    d43 d43Var = d43.i;
    w6 w6Var = new w6(4, list2);       // 包装消息列表
    d43Var.getClass();
    sx1 sx1Var3 = y82.a;
    dh1.B(is0.c(bx1.Z), null, null, new ry1(view3, w6Var, null, 7), 3);
    return v79Var;
```

#### 这是什么回调模型？

`q90` 实现了 **三参数回调接口** `y83`（view, contextData, messageData）：

| 参数 | 单条 | 多条 |
|------|------|------|
| obj (view) | 当前 View | 当前 View |
| obj2 (context) | 聊天上下文 | 聊天上下文 |
| obj3 (data) | 单条 `jb5` 消息 | `List<jb5>` 消息列表 |

---

## 五、阶段四：选择目标并执行转发

### 类：`ry1` — case 7

`ry1` 是协程 Lambda（实现 `x83`），case 7 → **弹出联系人选择 Compose 对话框**。

从 Smali 看关键流程（smali line 450+）：
```smali
# 从聊天上下文获取 Activity
check-cast v2, Landroid/app/Activity;

# 获取当前对话 talker
iget-object v0, v0, Lzu9;->c:Ljava/lang/String;

# 启动联系人选择协程
new-instance v3, Lg90;
invoke-direct {v3, v2, v0, v11, v5}, Lg90;-><init>(...)
invoke-static {v1, v3, v4}, Ldh1;->O(...)   # 挂起等待用户选择
```

用户选择完成后，返回 `Set<String>` → 目标列表。

---

### 类：`c43.r()` — ★核心转发逻辑★

```java
public final Object r(Object obj) {
    List<jb5> list = (List) this.i0;        // 消息列表
    Set<String> set = (Set) this.j0;         // 用户选中的目标集合

    // 1. 进度提示
    int total = list.size() * set.size();
    String progress = "正在转发 " + list.size() + " 条消息到 " + set.size() + " 个对象...";
    // 显示进度对话框 (se3 + g25)

    // 2. 遍历转发
    int success = 0;
    for (String target : set) {             // 遍历每个目标
        for (jb5 msg : list) {              // 遍历每条消息
            ub5 type = msg.k();             // 获取消息类型枚举

            switch (type) {
                // ============ 文本消息 ============
                case 1:
                    WeMessageApi.I0(target, msg.a());
                    break;

                // ============ App 消息 (链接/音乐/文件等) ============
                case 2:
                    WeMessageApi.A0(target, WeServiceApi.O(msg));
                    break;

                // ============ ★ 语音消息 ★ ============
                case 3:
                    String voicePath = msg.e();
                    if (voicePath == null) {
                        success = false;
                    } else {
                        String processedPath = WeMessageApi.k0(voicePath);
                        int durationMs = (int) AudioUtils.a.getDurationMs(processedPath);
                        WeMessageApi.K0(durationMs, target, processedPath);
                    }
                    break;

                // ============ 视频/图片 ============
                case 4:
                case 5:
                    WeMessageApi.J0(target, WeServiceApi.S(msg));
                    break;

                // ============ 贴纸/表情 ============
                case 6:
                case 7:
                    String md5 = msg.e();
                    if (md5 == null) {
                        md5 = zs6.n(msg.b(), "md5");  // 从内容提取 MD5
                        if (md5 == null) md5 = zs6.o(msg.b(), "md5");
                    }
                    WeMessageApi.x0(target, md5);
                    break;

                // ============ 名片 ============
                case 8:
                    WeMessageApi.L0(target, msg.a());
                    break;

                // ============ 位置消息 ============
                case 9:
                    WeMessageApi.I0(target, msg.g());
                    break;

                // ============ 未知类型 → 降级为卡片消息 ============
                default:
                    Toast: "警告: 该消息类型未经过测试, 回退为作为卡片消息发送, 可能失败!"
                    WeMessageApi.L0(target, msg.a());
            }

            if (result) success++;
        }
    }

    // 3. 结果提示
    if (success == total) {
        Toast: "已转发到 X 个对象"
    } else {
        Toast: "已转发 X/Y 条 (部分失败)"
    }
}
```

### 消息类型枚举对应（`b43.a` 序列表）

| 序号 | 消息类型 | 转发方法 |
|------|----------|----------|
| 1 | 文本 | `WeMessageApi.I0(to, content)` |
| 2 | App消息 | `WeMessageApi.A0(to, content)` |
| 3 | **语音** | `WeMessageApi.K0(duration, to, path)` |
| 4/5 | 视频/图片 | `WeMessageApi.J0(to, path)` |
| 6/7 | 贴纸/表情 | `WeMessageApi.x0(to, md5)` |
| 8 | 名片 | `WeMessageApi.L0(to, id)` |
| 9 | 位置 | `WeMessageApi.I0(to, location)` |
| default | 未知 | 降级 `WeMessageApi.L0(to, id)` |

---

## 六、阶段五：语音消息实际发送 — `WeMessageApi.K0()`

这是语音转发的核心实现，通过**反射调用微信内部 API**完成：

```java
public final boolean K0(int durationMs, String target, String voiceFilePath) {
    try {
        // ====== Step 1: 获取 AMR 文件名 ======
        // 反射调用: VoiceHelper.getInstance().getVoiceFileName(target, "amr_")
        Method getVoiceFileName = f0;  // DexKit 定位到的方法
        Object instance = i0(getVoiceFileName);  // 获取 VoiceHelper 单例
        String amrFileName = (String) getVoiceFileName.invoke(instance, target, "amr_");

        if (amrFileName == null) return false;

        // ====== Step 2: 构造目标路径 ======
        // voice2/msg_<amrFileName>.amr
        String voiceDir = W() + "/voice2/";
        String destPath = (String) tempFileCreator.invoke(null,
            voiceDir, "msg_", amrFileName, ".amr", 2);

        // ====== Step 3: 复制语音文件 ======
        Q(voiceFilePath, destPath);  // 文件复制

        // ====== Step 4: 设置语音时长 ======
        int duration = Math.min(Math.max(durationMs, 1), 60000);
        Method setVoice = g0;  // VoiceLogic.setVoice()
        Object voiceInstance = i0(setVoice);
        setVoice.invoke(voiceInstance, amrFileName, duration, 0, null);

        // ====== Step 5: 启动语音服务 ======
        T0();  // 触发 VoiceService 初始化

        // ====== Step 6: 通过 SceneVoiceService 发送 ======
        Method getServiceMethod = Q;  // DexKit 定位
        Class serviceClass = getServiceMethod.e();
        // 反射获取 VoiceService 单例
        Object voiceService = getVoiceServiceInstance(serviceClass);
        // 调用 run() 发送
        Method runMethod = e0();
        if (runMethod != null) {
            runMethod.invoke(voiceService, null);
        } else {
            // Fallback: 直接调用 startRecvAndSend
            e0().e().invoke(i0(e0().e()), voiceService);
        }

        return true;
    } catch (Throwable e) {
        log("failed to send voice", e);
        return false;
    }
}
```

### 关键反射调用一览

| 步骤 | 反射目标类/方法 | 作用 |
|------|----------------|------|
| `k0()` | `VoiceHelper.getVoiceFileName(talker, prefix)` | 为指定目标生成 AMR 文件名 |
| `VoiceLogic.setVoice()` | `VoiceLogic.setVoice(fileName, duration, 0, null)` | 登记语音消息元数据（时长等） |
| `T0()` / `e0()` | `VoiceService.startRecvAndSend()` | 启动语音收发服务 |
| `SceneVoiceService.run()` | `SceneVoiceService.run(null)` | 触发实际网络发送 |

---

## 七、辅助工具

### `AudioUtils`（native 方法）

```java
public class AudioUtils {
    public static final AudioUtils a = new Object();

    public native long getDurationMs(String path);     // 获取音频时长
    public native boolean silkToPcm(String in, String out);
    public native boolean pcmToMp3(String in, String out);
    public native boolean anyToSilk(String in, String out);
}
```

用于读取 AMR/SILK 格式语音文件的实际时长。

---

## 八、完整调用链总结

```
1. 用户长按语音消息
   ↓
2. WeChatMessageContextMenuApi.D() 中 Hook 的 methodCreateMenu 被触发
   ↓
3. WeChatMessageContextMenuApi.P() 构建上下文菜单
   ├─ 遍历 i (LinkedHashMap<String, List<tq9>>)
   ├─ 找到 d43 注册的 "转发" 菜单项 (ID=777010)
   └─ 条件过滤器 sw2(25)/sw2(26) 全部返回 TRUE
   ↓
4. 用户点击 "转发"
   ↓
5. q90.j0(case 7) 或 q90.i0(case 6) 触发
   ↓
6. dh1.B() 启动协程 → ry1.r() case 7
   ├─ 从聊天上下文获取 Activity 和 talker
   ├─ 弹出联系人选择 Compose 对话框
   └─ 挂起等待用户选择目标
   ↓
7. 用户勾选目标并确认 → 返回 Set<String> 目标列表
   ↓
8. c43.r() 协程执行转发：
   ├─ 显示 "正在转发 X 条消息到 Y 个对象..." 进度
   ├─ 双重循环遍历 target × message
   │   └─ 语音消息分支 (case 3):
   │       ① msg.e() 获取语音文件路径
   │       ② AudioUtils.getDurationMs() 获取时长
   │       ③ WeMessageApi.k0() → VoiceHelper.getVoiceFileName()
   │       ④ WeMessageApi.K0():
   │          ├─ getVoiceFileName(target, "amr_") → AMR 文件名
   │          ├─ 复制语音文件到 voice2/ 目录
   │          ├─ VoiceLogic.setVoice(fileName, duration, 0, null)
   │          ├─ T0() 启动 VoiceService
   │          └─ SceneVoiceService.run(null) 发送
   │
   └─ 显示 "已转发到 X 个对象" 或 "已转发 X/Y 条 (部分失败)"
```

---

## 九、核心要点

1. **不是自己拼协议**：WeKit 通过**反射调用微信内部 API**（`VoiceHelper`、`VoiceLogic`、`SceneVoiceService`）来完成语音发送，而非自己构造 protobuf 网络请求。

2. **语音文件三步骤**：
   - 用 `VoiceHelper.getVoiceFileName()` 获取 AMR 文件名
   - 复制文件到 `voice2/` 目录
   - 用 `VoiceLogic.setVoice()` 登记元数据 + `SceneVoiceService.run()` 发送

3. **消息类型分发**：`c43.r()` 中的 `switch-case` 根据 `jb5Var.k()` 返回的消息类型（文本/语音/图片/视频/贴纸/名片/位置等）调用不同的 `WeMessageApi` 方法。

4. **无类型限制**：条件过滤器 `sw2(25)` / `sw2(26)` 直接返回 `TRUE`，没有任何消息类型限制。未测试类型降级为"卡片消息"方式发送。

5. **文件级别操作**：语音转发是文件级别的复制+发送，而非简单的消息 ID 引用转发。这确保了语音内容能正确送达任意目标。

---

## 十、相关文件清单

| 类 | 路径 | 角色 |
|----|------|------|
| `d43` | `defpackage` | 转发 Feature 入口，注册菜单项 |
| `WeChatMessageContextMenuApi` | `dev.ujhhgtg.wekit.features.api.ui` | 上下文菜单 Hook + 管理 |
| `WeMessageApi` | `dev.ujhhgtg.wekit.features.api.core` | 消息发送 API（反射微信内部） |
| `q90` | `defpackage` | 菜单点击回调分发（三参数模型） |
| `c43` | `defpackage` | 转发协程执行器（消息类型分发） |
| `ry1` | `defpackage` | 协程 Lambda，选择目标 |
| `nd2` | `defpackage` | Lambda 包装器（单条/多条消息） |
| `sw2` | `defpackage` | 条件过滤器（全部返回 TRUE） |
| `AudioUtils` | `dev.ujhhgtg.wekit.utils` | Native 音频工具 |
| `tq9` | `defpackage` | 菜单项数据类（ID + 文字 + 图标 + 回调） |
| `hi9` | `defpackage` | 多用途回调（联系人选择对话框等） |
