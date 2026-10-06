# 微信聊天窗口「输入框上方注入快捷按钮」导致消息遮挡/长文本不拉伸 —— 地毯式逆向分析与修复

> 结论先行：微信的消息列表并非简单被垂直 LinearLayout 挤压，而是由 `ChattingScrollLayout` 通过对
> `MMChattingListView` 施加 `translationY` 来「把消息顶到输入区上方」。你的快捷按钮游离在
> Footer 容器(n) / 消息列表(p) 的这套高度协商协议之外，所以既不抬升消息、也不参与输入框增高，
> 最终表现为：遮挡消息 + 消息不被顶上去 + 输入长文本不再向上拉伸。三者同源。

---

## 一、本次逆向确认的微信聊天窗口容器层次

根容器（垂直 LinearLayout，兼键盘状态机 + 底部面板高度补偿）：
- `com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout`
  - 继承链：`ChattingUILayout` → `BasePanelKeybordLayout` → `KeyboardLinearLayout` → `OnLayoutChangedLinearLayout` → `DrawnCallBackLinearLayout`（= LinearLayout，垂直）
  - `KeyboardLinearLayout.c(int curHeight)`：记录初始高度 `h`；`初始高度 - 当前高度 > 100` → 判为 show keyboard，调 `f(-3)`；`≤ 100` → hide keyboard，调 `f(-2)`；同时回调监听器 `d8.a(-1)`。
  - `BasePanelKeybordLayout.onMeasure(II)`：算高度差 `Δ = 旧总高 - 新总高`；**只遍历 `getPanelView()` 返回列出的面板 View**，把每个面板 `LayoutParams.height -= Δ`（键盘弹起时压缩面板），clamp 后 `setLayoutParams`。⚠️ 不在 `getPanelView()` 列表里的 View 不享受此补偿。
  - 本版 `ChattingUILayout.getPanelView()` 实际 `return new ArrayList<>()`（空），说明该实例本身未注册被托管面板。

内容滚动/位移协调器（核心中的核心）：
- `com.tencent.mm.pluginsdk.ui.chat.ChattingScrollLayout`（`extends LinearLayout`(垂直) + `kw5.a0`）
  - `onFinishInflate()` 用 `findViewById` 绑定三兄弟：
    - `n = findViewById(0x7f0a4ce0)` → **输入栏 / Footer 容器**（`c()` 日志里的 `footerTranslationY` 即取 `n.getTranslationY()`）
    - `o = findViewById(0x7f0a0f73)`；为 null 再试 `findViewById(0x7f0a47ca)` → 头部/通知/多窗口区
    - `p = (MMChattingListView) findViewById(0x7f0a0fc2)` → **消息列表**
  - 字段：`d:OverScroller` `e:Runnable` `f/g/h/i/m:int` `q:Z` `r/s/v:List`(监听器 `l6`/`wl`) `t:float`(上次 translationY) `u:ViewPropertyAnimator`
  - `getInterTranslationY()`：`h!=0` 时返回 `(int)((double)f / h * m)`，否则 0。
  - `c(boolean isFromOnLayout, boolean isFromScroller)`（即 updateListContent）：
    ```
    bottomSpace = p.getBottomSpace()
    footerTy    = n.getTranslationY()
    target      = footerTy
    if (bottomSpace > 0) target = bottomSpace + footerTy
    target     += getInterTranslationY()
    if (target > 0) target = 0                      // 位移只允许 ≤0（向上）
    if (t != target):
        if (>=AndroidN && d.isFinished() && g>0 && target < p.getTranslationY())
            p.animate().translationY(target).setDuration(100ms).start()
        else
            取消 u 动画; p.setTranslationY(target)
    ```
  - `onLayout()`：`super LinearLayout.onLayout()` 后 `post(e)` 跑 `c()`；`OverScroller` 结束也跑 `c(true,true)`。
  - `b(int, boolean)`：遍历 `v` 列表监听器(`wl`→`xl`)，把计数字段 `c` 置为 `max(p1,0)` 并回调——键盘/输入高度变化的广播。
  - 回环引用：`ChatFooter.C2 : ChattingScrollLayout`；另 `com.tencent.mm.pluginsdk.ui.chat.i6.f` 亦引用它。

消息列表：
- `com.tencent.mm.ui.chatting.view.MMChattingListView`
  - `extends MMPullDownView` → `android.widget.FrameLayout`（+ 手势，接口 `kj5.l5/m5/n5/q5/o5`）
  - 资源 id `0x7f0a0fc2`；提供 `getBottomSpace()/setBottomSpace()`（底部留白，配 `ValueAnimator` `J1`）
  - ⚠️ 本质是 **FrameLayout**：往里 `addView` 的任何东西都是 overlay，天然会盖在消息之上。

输入栏 Footer：
- `com.tencent.mm.pluginsdk.ui.chat.ChatFooter`（`extends android.widget.FrameLayout` + `k9`/`e5`/`b35.a`，334 个字段）
  - 关键字段：`C2:ChattingScrollLayout`、`D:MaxHeightScrollView`、`m:st5.i`（输入代理，`m.g()` 返回 `EditText/TextView`）、`I/J/L:LinearLayout/FrameLayout/LinearLayout`、`G/H:RelativeLayout`
  - `getUnscaleInputBarSingleLineHeight()`：取 `EditText` 的 `TextPaint.getFontMetricsInt()` 与两个 layout 常量(`0x7f070081`/`0x7f070163`，后者×2) 计算「未缩放输入栏单行像素高」——这是 Footer 高度的度量基准。

输入框限高滚动（长文本拉伸的执行者）：
- `com.tencent.mm.view.MaxHeightScrollView`（`extends ScrollView`）
  - `onMeasure()`：`super` 后若 `d(maxHeight)>0` 且 `measuredHeight>maxHeight` → `setMeasuredDimension(makeMeasureSpec(maxHeight, AT_MOST))`。
  - `setMaxHeight()` 调用方之一：`com.tencent.mm.ui.chatting.component.xc.x()`（输入栏组件初始化处）。

输入栏组件装配：
- `com.tencent.mm.ui.chatting.component.xc`：持 `e:ChatFooter`、`h:ChatFooterCustom`、`g:k4`、`m:l7`、`n:h3`、`o:z6`、`p:p7`、`B:t5(FooterSwitchListener)`；`x()`（onResume）里 `ChatFooter.setOnFooterSwitchListener(B)` 完成输入栏装配。
- `com.tencent.mm.ui.chatting.ChatFooterCustom`（`extends LinearLayout` + `OnClickListener`）：自定义/开放输入栏场景，含 `d/g:LinearLayout` 两个容器与 `g(ViewGroup,String,int)` 动态加项入口。

真实数据流（务必记住这条链）：
  EditText 多行 → MaxHeightScrollView 增高(封顶 maxHeight) → ChatFooter 有效高度 wrap_content 增高
   → ChatFooter 经 `C2` 回调 `ChattingScrollLayout.c()` → 仅对 `p(MMChattingListView)` 施加 `translationY`
   → 视觉上「消息被顶到输入框上方 / 输入框变高把消息顶走」。

---

## 二、为什么会遮挡 / 为什么消息不被顶上去 / 为什么长文本不再拉伸

三个症状同源：你的快捷按钮**游离在 `n(Footer容器)` 与 `p(消息列表)` 的 translationY 高度协商协议之外**。

1) 消息被按钮遮挡、消息不在按钮上方
   - 最可能的注入落点是 `MMChattingListView`(`p`, **FrameLayout**) 内 overlay，例如
     `addView(btn, FrameLayout.LayoutParams(MATCH_PARENT, H, Gravity.BOTTOM))`。
   - FrameLayout 的 overlay 会直接盖住列表底部几条消息；而 `p.getBottomSpace()/translationY` 这套协议根本不含你的按钮，消息永远不会被顶让给它。

2) “把消息顶上去”为什么没发生
   - 顶消息的唯一动作在 `ChattingScrollLayout.c()`：只对 `p` 施加 `translationY`，且
     `target = n.getTranslationY() + getInterTranslationY() + bottomSpace`。
   - 你的按钮若不在 `n`(Footer 容器) 里，就不会产生 `footerTranslationY` 增量；`c()` 无从感知按钮高度，自然不会抬消息。

3) 输入框输入长文本不再向上拉伸
   - 长文本拉伸依赖：MaxHeightScrollView 增高 → Footer 高度增高 → `ChatFooter.C2` → `c()` 重算(`f/h/m`)。
   - 一旦按钮是根 `ChattingUILayout` 上的 overlay、或被插进了 Footer 却写成固定/覆盖高度、或把 Footer/根高度写死，
     Footer 有效高度不增长、`c()` 不触发或输入失真 → `p.translationY` 不刷新 → 视觉上“输入框不再撑高/消息不被顶”。

4) 键盘弹起遮挡加重的额外因素
   - `BasePanelKeybordLayout.onMeasure` 的高度补偿**只作用于 `getPanelView()` 列出的面板**；你的按钮不在其列，
     键盘弹起时它不会被一同顶上/让位，容易与抬起后的 Footer/面板错位重叠。

---

## 三、修复方案（按推荐度从高到低）

总原则：让按钮成为 **Footer 容器(n / ChatFooter 输入栏) 内部的、按垂直度量合法 wrap_content 增高** 的一员，
从而触发 `ChatFooter.C2 → ChattingScrollLayout.c()` 的 translationY 重算。绝不要 overlay 到 `MMChattingListView`。

方案 1（推荐，最稳、联动最好）—— 插进 Footer 输入栏容器内、输入框(MaxHeightScrollView) 的正上方：
- 锚点：从 `ChatFooter` 实例内经 findViewById 拿输入栏容器（`ChatFooter` 的 `d/g` 或 `I/L/J/G/H` 之一），
  或直接定位 `ChattingScrollLayout` 的 `n`（资源 id `0x7f0a4ce0`）。
- 参数：把按钮作为该容器的顶部首个子 View，`width=MATCH_PARENT`、`height=WRAP_CONTENT`。
- 效果：Footer 总高 = 按钮 + 原输入栏；MaxHeightScrollView 增高时，`c()` 链路天然联动，`p.translationY` 自动补偿。
- 时机：hook `ChatFooter` 完成 inflate 之后（或 `component.xc.x()`，此时 ChatFooter 已装配）再插入。

方案 2 —— 插到 `ChattingScrollLayout` 层、消息列表(p) 与 Footer 容器(n) 之间：
- `ChattingScrollLayout` 是垂直 LinearLayout，可 `addView` 在 `p` 与 `n` 之间的索引处。
- ⚠️ 单纯插入通常**不会**自动把消息顶到按钮上方（`c()` 的 target 仍按 n 的 translationY 与 InterTranslationY 算）。
- 需二选一配合：
  a) 把按钮高度计入：hook `getInterTranslationY()` 的输入 `m`，或在 `c()` 里把按钮高加进 target；
  b) 直接给消息列表留白：`p.setBottomSpace(按钮高)` 后触发重算。
- 此方案改动面大，慎用。

方案 3（兜底，保底可用）—— 无法精准定位容器时，把 overlay 改为“占位不遮挡”：
- 给 `MMChattingListView` 设底部空间：`p.setBottomSpace(按钮高)`（或加等量 bottom padding），让消息列表底部空出按钮高度。
- 长文本仍不拉伸时，额外 hook `ChatFooter.getUnscaleInputBarSingleLineHeight()` 令其返回 `原值 + 按钮高`，强制 Footer 认知按钮高度。
- 缺点：联动性/平滑度不如方案 1，仅作兜底。

方案 4 —— 复用微信原生自定义 Footer 通道（开放/自定义输入栏场景）：
- 走 `ChatFooterCustom`(LinearLayout)，把按钮加入其 `d/g` LinearLayout，或调 `g(ViewGroup,String,int)`。

通用注意事项：
- 每次 添加/移除/改显隐 按钮后，必须请求重新布局并驱动一次协议刷新：
  `container.addView(btn); container.post(() -> { container.requestLayout(); scrollLayout.c(false,false); })`
  （`scrollLayout` 即 ChatFooter 的 `C2`）。否则 `translationY` 不会刷新。
- 高度一律 `WRAP_CONTENT`，禁止 `MATCH_PARENT`；避免用绝对 gravity 覆盖。
- 键盘弹起补偿只覆盖 `getPanelView()` 面板；按钮必须作为 Footer 子 View 存在，不要做成根 `ChattingUILayout` 的直接 overlay。
- 移除时要对称：`removeView(btn)` 后同样 `requestLayout()` + 触发 `c()`，避免残留 translationY/底部留白导致列表下方露空。

---

## 四、二次核查（cross-check）

| 核查点 | 证据来源 | 一致性 |
|---|---|---|
| IMList 是 FrameLayout(会被 overlay 遮挡) | `class_hierarchy(MMChattingListView→MMPullDownView→FrameLayout)` | ✅ |
| 顶消息只作用于消息列表 translationY | `ChattingScrollLayout.c()` 中 `p.setTranslationY/animate().translationY` | ✅ |
| Footer 身份 | `c()` 日志 `footerTranslationY` = `n.getTranslationY()`；`onFinishInflate` n=0x7f0a4ce0 | ✅ |
| Footer↔ScrollLayout 回环 | `find_class_usage` → `ChatFooter.C2 : ChattingScrollLayout` | ✅ |
| 长文本封顶拉伸 | `MaxHeightScrollView.onMeasure` 限 maxHeight；`setMaxHeight` 调用方 `xc.x` | ✅ |
| 根布局键盘面板补偿范围 | `BasePanelKeybordLayout.onMeasure` 仅迭代 `getPanelView()` | ✅ |
| ChatFooter 为 FrameLayout、持 Footer 内多容器 | `class_hierarchy(ChatFooter)` + 334 字段抽样 | ✅ |

不确定性说明：
- 字段名 `n/o/p` 及资源 id 来自本版微信(混淆版)静态结果，跨版本资源 id 可能漂移；落地时以运行时
  `ChatFooter` 实例向上找到其宿主 `ChattingScrollLayout`、取其 `MMChattingListView` 子 View 为最稳。
- `ChattingUILayout.getPanelView()` 本版返回空列表，若你的目标版本返回非空，则底部面板弹起时的压缩行为需重新评估按钮的挂载层级。
