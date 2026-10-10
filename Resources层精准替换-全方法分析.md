# 微信气泡 Resources 层精准替换 · 全方法分析（含 DexKit 动态适配与越界红线）

> 版本：LeShaoWeChat V3 / 微信 8.0.78 (versionCode=3180)
> 目标：用 `Resources.getDrawableForDensity` / `getDrawable` 源头替换气泡，零贴皮、零错位。
> 说明：本报告基于 Android 框架源码 + 微信 APK 反编译 + 模块源码三方核对。

---

## 一、★ 核心结论：Resources.getDrawable 这条路径到底走不走

**结论：单参 `Resources.getDrawable(int)` 在气泡设置链路上【不走】；`getDrawableForDensity`【必走】。**

`View.setBackgroundResource(int)` 的 Android 框架真实链路：

```
View.setBackgroundResource(int resid)
   │  （mContext 是 ContextImpl）
   ▼
ContextImpl.getDrawable(int id)                    ★ 真正入口（Context 层）
   │  （ContextImpl.getDrawable 实现）
   ▼
Resources.getDrawable(int id, Theme theme)        ★ 2 参，必走
   │  （getDrawable(id,theme) 内部实现）
   ▼
Resources.getDrawableForDensity(int id, int density, Theme theme)   ★ 3 参，必走
   │  （getDrawableForDensity 内部实现）
   ▼
ResourcesImpl.loadDrawable(...)                    ★ 最终加载点
```

**因此：**
- 你之前 hook 单参 `Resources.getDrawable(int)` 没触发，是正常的——这条链路上根本没有调用它。
- **`Resources.getDrawableForDensity(int, int, Theme)` 是唯一必然经过的 Resources 方法**，因为它就是 `getDrawable(id,theme)` 的内部实现。
- 除 `setBackgroundResource` 外，`LayoutInflater` 解析 XML 背景、`ImageView.setImageResource` 等也会走 `getDrawableForDensity`，所以这个点是最高命中率入口。

---

## 二、必须 hook 的 Resources / Context 方法（按命中优先级排序）

| 优先级 | 方法 | 说明 |
|---|---|---|
| ★★★ | `android.app.ContextImpl.getDrawable(int)` | `setBackgroundResource` 真正入口 |
| ★★★ | `android.content.res.Resources.getDrawableForDensity(int,int,Theme)` | 3 参，内部实现，**必走** |
| ★★★ | `android.content.res.Resources.getDrawableForDensity(int,int,Theme,boolean)` | 4 参（隐藏重载） |
| ★★ | `android.content.res.Resources.getDrawable(int,Theme)` | 2 参，Context 层转发 |
| ★★ | `android.content.ContextWrapper.getDrawable(int)` | 兜底（Activity/ContextThemeWrapper 委托） |
| ★ | `android.content.res.Resources.getDrawable(int)` | 单参，覆盖其它直接调用方 |

> 强烈建议：用 `XposedBridge.hookAllMethods(Resources::class.java, "getDrawable", ...)` 和
> `hookAllMethods(Resources::class.java, "getDrawableForDensity", ...)` 一次 hook 全部重载。

---

## 三、各消息类型用到的方法与资源 ID 清单（已核验）

> 统一说明：气泡背景最终都走 `setBackgroundResource(resId)`，因此只需把对应 `resId` 在资源层替换即可。

### 1. 文本消息（text）

| 项 | 值 |
|---|---|
| 文本绑定器基类 | `hn5.v`（日志 `MicroMsg.ChattingItemTextFrom`）→ 父类 `com.tencent.mm.ui.chatting.viewitems.zn` → `b0` |
| 视图创建 | `zn.H()` 膨胀 layout `2131624834`，holder=`to` |
| 气泡内容视图 | `to.b` = `MMNeat7extView`（viewId `2131365751`=0x7f0a0f77） |
| 气泡背景设置 | 各方向子类调用 `setBackgroundResource(?)` |
| 文本气泡资源 ID（多子类型） | 收到：`2131231925`(普通) / `2131231841`(发送中) / `2131231944`(链接)；发出：`2131232060`(普通) / `2131232062` / `2131232070`(链接) 等 |
| 关键方法 | `hn5.r0.g0`、`hn5.s0.k0`（子类覆盖点） |

> ⚠️ 文本气泡**不是只有 2 个 resId**，普通/发送中/链接、收/发方向各有不同资源。**必须动态收集**，不能硬编码 2 个。

### 2. 语音消息（voice）

| 项 | 值 |
|---|---|
| 语音 holder | `mq`（DexKit 字符串锚点：`[voice interrupt] set continue play visible `） |
| 气泡背景设置点 | `com.tencent.mm.ui.base.AnimImageView.setType(int)`，`i==2` 分支调用 `setBackgroundResource(2131100638 对方 / 2131100639 自己)` |
| 语音气泡资源 ID | 对方 `2131100638`；自己 `2131100639` |
| 真承载视图 | `mq.e`(2131366091) 收/发都贴；发送侧还有 `mq.x`(2131366108) TextView、`mq.u`(2131366096) 动画层 |
| 占位/镜像（严禁贴） | `mq.d`(2131366097) 点击热区、`mq.D`(2131365816) 时长容器、接收侧 `mq.x` 镜像、接收侧 `mq.u` 占位层 |

### 3. 图片消息（image）

- 自定义视图：`com.tencent.mm.ui.chatting.viewitems.mvvmview.ChattingImgMvvmView`（`getMainContentIv()` 返回内容 ImageView）。
- 气泡背景在容器上（XML 背景或 setBackgroundResource），资源级替换覆盖其背景 resId。

### 4. 其它类型（视频/位置/名片/文件/链接/小程序/卡片）

- 各自 `b0` 子类绑定器 + 自定义 View；气泡背景同样最终通过 `setBackgroundResource` 或布局 XML 背景。
- 资源级替换天然覆盖所有类型，无需逐个 hook。

### 5. 昵称（群聊成员昵称）

| 项 | 值 |
|---|---|
| 昵称视图 | `h0.userTV`，viewId `2131366078`（0x7f0a10be） |
| 方法 | 在文本绑定器 `b0`/`to` 绑定点 after 里改 `setTextColor`（模块 `applyTimeNickColor` / `TimelineEngine`） |
| 边界 | **只改 userTV 的文字颜色/大小，绝不套气泡背景** |

### 6. 时间线（时间分隔条）

| 项 | 值 |
|---|---|
| 时间视图 | `h0.timeTV`，viewId `2131366064`（0x7f0a10b0） |
| 方法 | 绑定后设置格式化文本 + 颜色（模块 `TimelineEngine.apply`） |
| 边界 | **只改 timeTV 文本内容/颜色，绝不套气泡背景** |

---

## 四、DexKit 动态适配（解析微信类，版本自适应）

不硬编码混淆类名，启动时用 DexKit 解析并缓存到 `R` 对象：

| 用途 | DexKit 锚点字符串 | 得到 |
|---|---|---|
| 聊天适配器 | `_onBindViewHolder[` | `com.tencent.mm.ui.chatting.adapter.k`（ChattingDataAdapterV3） |
| RecyclerView 基类 | 类名 `com.tencent.mm.view.recyclerview.WxRecyclerAdapter` | `WxRecyclerAdapter` |
| 语音 holder | `[voice interrupt] set continue play visible ` | `mq` |
| 语音收到项 | `onStateBtnClick voice msg(%s) re-download!` | `voiceItemFrom` |
| 语音发出项 | `ChattingItemVoice$ChattingItemVoiceTo` | `voiceItemTo` |
| 文本 holder | `[isOpenNeatTextView]` | `to` |
| 文本发送基类 | `MicroMsg.ChattingItemTextFrom` | `hn5.v` |
| 系统提示项 | `chat_sys_msg_del_btn` | `sysMsgItem` |
| 系统提示模板 | `com/tencent/mm/ui/chatting/viewitems/ChattingItemSysMsgTemplate` | `sysMsgTemplate` |
| 系统提示文本生成 | `MicroMsg.SysMsgTemplateImp` | `sysMsgGen` |
| 聊天背景属性 | `chatbg` | `chatBgAttr` |
| 红包/转账工具 | `getC2CLuckyMoneyDescByHbStatus` | `c2cUtil` |
| 红包卡片项 | `MicroMsg.ChattingItemAppMsgC2CFrom` | `hbItem` |
| 引用气泡实现 | 接口 `Lq71/n;` | `quoteImpls` |
| 消息实体 | `MicroMsg.MsgInfo` + 方法 `convertFrom(Cursor)` | `com.tencent.mm.storage.e9` |
| 语音动画视图 | 类名 `com.tencent.mm.ui.base.AnimImageView`（未混淆） | `anim` |
| 文本内容视图 | 类名 `com.tencent.mm.ui.widget.MMNeat7extView`（未混淆） | `neat` |

---

## 五、越界红线（绝不能替换 / 触碰）

1. **系统提示消息（type=10000 家族）**：撤回/红包领取/群通知/时间线等，只有 `foreground` 无气泡背景，一律不替换、不着色。
2. **红包卡片 / 转账**：资源 id `436207665` / `419430449` 及相关红包 drawable，绝不替换。
3. **时间条 / 昵称**：`timeTV`、`userTV` 只做文字/颜色，不套气泡。
4. **点击热区 / 时长容器 / 占位层**：`mq.d`(2131366097)、`mq.D`(2131365816)、接收侧 `mq.x`/`mq.u` 不贴。
5. **资源级替换的全局性风险**：一旦替换某 resId，APK 内所有用到该 resId 的地方都会变。
   → 只收集/替换**聊天 item 内被设置过**的气泡 resId（用观察 hook 收集），且这些气泡 9-patch 通常不被他处复用。
6. **观察 hook 只登记、不修改**：`View.setBackgroundResource` 的 before 只收集 id，绝不改背景，避免越界。
7. **非气泡 drawable 不替换**：`getDrawableForDensity` 的 after 只在 `id ∈ 已收集气泡集合` 时替换，其余一律原样返回。

---

## 六、落地代码骨架（Kotlin / Xposed）

```kotlin
object WxBubbleResourceReplace {
    private val bubbleIds: MutableSet<Int> = ConcurrentHashMap.newKeySet() // 动态收集的气泡 resId
    private val idMeta = ConcurrentHashMap<Int, Pair<Boolean, Boolean>>()   // id -> (from, voice)

    fun install(cl: ClassLoader) {
        // 1) 观察：只登记，不改背景
        XposedBridge.hookAllMethods(View::class.java, "setBackgroundResource", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val v = p.thisObject as? View ?: return
                if (!inChatItem(v)) return
                val id = p.args[0] as? Int ?: return
                if (bubbleIds.add(id)) idMeta[id] = metaOf(v)
            }
        })

        // 2) 源头替换：hook 全部 getDrawable / getDrawableForDensity 重载
        val res = Class.forName("android.content.res.Resources")
        listOf("getDrawable", "getDrawableForDensity").forEach { m ->
            XposedBridge.hookAllMethods(res, m, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val id = (p.args[0] as? Int) ?: return
                    if (id !in bubbleIds) return
                    val orig = p.result as? Drawable ?: return
                    if (isOurs(orig)) return
                    val (from, voice) = idMeta[id] ?: (true to false)
                    val ctx = appContext ?: return
                    val ours = BubbleFactory.create(ctx, from, voice, orig) ?: return
                    ours.bounds = orig.bounds
                    p.result = ours
                }
            })
        }

        // 3) Context 层兜底：setBackgroundResource 走 Context.getDrawable
        hookAll("android.app.ContextImpl", "getDrawable", int.class)
        hookAll("android.content.ContextWrapper", "getDrawable", int.class)
    }
}
```

---

## 七、上机核查：确定本机到底走哪条路径

写一个临时诊断 hook，打开聊天页收发文本/语音/图片，看日志里哪些方法被命中：

```kotlin
// 临时诊断：打印气泡 resId 经过的加载方法
val res = Class.forName("android.content.res.Resources")
listOf("getDrawable", "getDrawableForDensity").forEach { m ->
    XposedBridge.hookAllMethods(res, m, object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            val id = (p.args[0] as? Int) ?: return
            XposedBridge.log("[ResPath] $m id=$id")
        }
    })
}
XposedBridge.hookAllMethods(Class.forName("android.app.ContextImpl"), "getDrawable",
    object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            XposedBridge.log("[ResPath] ContextImpl.getDrawable id=${p.args[0]}")
        }
    })
```

预期：能看到 `getDrawableForDensity` 与 `ContextImpl.getDrawable` 被调用（id 为 213123xxxx / 2131100xxx 等气泡 id）。
如果某条消息类型连 `setBackgroundResource` 都没走（直接 `setBackground(drawable)`），则需对该类型补 `View.setBackground` 的参数替换兜底。

---

*文档生成：针对 Resources 路径专项核查后导出。*

---

## 八、★ 语音气泡全套状态机与资源（smali 实证）

### 8.1 背景设置方法 `AnimImageView.setType(int)`（反编译原文）

```java
public void setType(int i) {
    this.f = i;
    if (this.e /*=isRecv 收到*/) {
        if (i == 2) setBackgroundResource(2131100638);         // 收到-播放/高亮
        else if (i == 3) setBackgroundDrawable(null);          // 播放/复用清背景
        else setBackgroundDrawable(ke5.a.i(ctx, 2131231925));  // 收到-普通
    } else { /* 发出 */ 
        if (i == 2) setBackgroundResource(2131100639);          // 发出-播放/高亮
        else if (i == 3) setBackgroundDrawable(null);         // 清背景
        else setBackgroundDrawable(ke5.a.i(ctx, 2131232060));  // 发出-普通
    }
}
```

### 8.2 `ke5.a.i` 实现（决定走哪条资源路径）

```java
// ke5.a.i(Context, int)  =  com.tencent.mm 的 ResourceHelper
public static Drawable i(Context context, int i) {
    if (context == null) { Log.e("MicroMsg.ResourceHelper", ...); return null; }
    return context.getResources().getDrawable(i);   // ★ 单参 Resources.getDrawable(int)
}
```

### 8.3 语音气泡资源完整集合（收到/发送中/发出/播放时）

| 状态 | 方向 | 资源ID(hex) | 加载路径 | 单参 getDrawable 是否命中 |
|---|---|---|---|---|
| 普通（未播放） | 收到 | `2131231925` (0x7f0804b5) | `ke5.a.i` → 单参 getDrawable | ✅ 命中 |
| 普通（未播放） | 发出 | `2131232060` (0x7f08053c) | `ke5.a.i` → 单参 getDrawable | ✅ 命中 |
| 播放/高亮 | 收到 | `2131100638` (0x7f0603de) | setBackgroundResource → 2参/getDrawableForDensity | ❌ 走2参 |
| 播放/高亮 | 发出 | `2131100639` (0x7f0603df) | setBackgroundResource → 2参/getDrawableForDensity | ❌ 走2参 |

> 说明：
> - 语音**没有独立「发送中」气泡资源**：发送中复用「发出-普通」2131232060 + 上传进度指示。
> - 文本的发送中资源是 `2131231841`（收到）/ `2131231895`（发出），与语音无关。
> - `i==3` 清背景（setBackgroundDrawable(null)）：播放中/复用清理，**不替换**，保持微信状态机。

### 8.4 结论：必须 hook 全部 getDrawable 重载

- 文本普通/链接/发送中：走 `Context.getDrawable → Resources.getDrawable(int,Theme) → getDrawableForDensity`。
- 语音普通：走 `ke5.a.i → Resources.getDrawable(int)`（**单参**）。
- 语音播放：走 `setBackgroundResource → Resources.getDrawable(int,Theme) → getDrawableForDensity`。

```kotlin
XposedBridge.hookAllMethods(Resources::class.java, "getDrawable", hook)          // 单参+2参
XposedBridge.hookAllMethods(Resources::class.java, "getDrawableForDensity", hook) // 内部实现
XposedBridge.hookAllMethods(Class.forName("android.app.ContextImpl"), "getDrawable", hook) // setBackgroundResource 入口
```

这样「收到 / 发送中 / 发出 / 播放时」全套语音气泡资源替换全覆盖，无死角。

---

## 九、各消息类型「位置（视图）定位精度」清单

> 说明：以下为本次深挖补充的 binder/holder/根视图定位结果。
> 资源级替换下，只要背景资源 ID 被收集到，微信自己绘制，**不依赖逐个 View 定位**；
> 但若需「贴皮」回退路径，则以本表为准。

### 9.1 文本消息 —— ✅ 已精确到 View
- 内容/气泡视图：`to.b` = `MMNeat7extView`（viewId 2131365751）
- holder：`com.tencent.mm.ui.chatting.viewitems.to`；布局 2131624834

### 9.2 语音消息 —— ✅ 已精确到 View（含方向）
- 真承载：`mq.e`(2131366091) 收/发都贴
- 发送侧：`mq.x`(2131366108)、`mq.u`(2131366096)
- 占位/镜像（不贴）：`mq.d`(2131366097)、`mq.D`(2131365816)、接收侧 `mq.x`/`mq.u`

### 9.3 图片消息 —— 🟡 已定位到 holder/主容器
- 绑定器：`kn5.h7`(发) / `kn5.j7`(收)
- 布局：发送 2131624899 / 接收 2131624980；holder=`m7`
- 内容视图：`ChattingImgMvvmView`（布局 2131624778，`getMainContentIv()` 返回 ImageView）
- 主容器：`h0.clickArea`(2131365741)
- 说明：图片气泡背景通常落在图片容器/遮罩上，非标准 9-patch 气泡；是否要套自定义气泡属设计选择。

### 9.4 引用消息 —— 🟡 已定位到根视图
- 接口：`q71.n`（`b(Context)` 创建根视图，`a(Lq71/p)` 设数据）
- 实现：44 个（`cr5.*`、`bs5.*` 等），如 `cr5.a1` 膨胀布局 2131628856（holder `f1`，含 `MvvmView`）
- 说明：引用气泡是主气泡内的内嵌预览块；背景资源若与主气泡共享则资源级替换自动覆盖。

### 9.5 视频消息 —— 🟡 已定位到自定义视图
- 内容视图：`ChattingVideoMvvmView`（布局 2131625034，holder `jm.y`）
- 字段：`f:I`、`g:Ljm/y`（holder）、`h:Lln5/n0`
- 说明：视频气泡背景在容器/遮罩上，未单独深挖到具体子 View。

### 9.6 名片消息 —— 🟡 已定位到自定义视图
- 内容视图：`ChattingContactCardMvvmView`（内部创建 `com.tencent.mm.mvvm.MvvmView`，字段 `f`）
- 数据：`getUsername()`
- 说明：名片是卡片式气泡，背景在卡片容器上，未单独深挖到具体子 View。

### 9.7 结论
- **资源级替换**：不依赖精确 View 位置，只依赖「气泡背景资源 ID 是否被收集」。只要观察钩子覆盖
  `setBackgroundResource` + `setBackgroundDrawable` + `setBackground`，图片/视频/名片/引用使用到的
  背景资源 ID 都会被动态收集并替换。
- **贴皮回退路径**：如需对图片/视频/名片/引用也做精确贴皮，需进一步定位到每个类型的「背景 View」；
  当前文本/语音已精确，其余类型可用「通用扫描 + 空容器/角标过滤」兜底，避免越界。
