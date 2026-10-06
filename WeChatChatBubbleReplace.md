
---

## 13. v9 增补：语音气泡突然不渲染（setType(3) 清背景态）

### 13.1 日志实锤（本次回归）

| 时间 | 现象 |
|---|---|
| 20:33:42（正常） | `AnimImageView.setType type=1` -> `ke5.a.i(2131232060)` -> `REPLACE kind=1`，AnimImageView 有自定义气泡 |
| 20:34:04 之后（故障） | `AnimImageView.setType type=3`（无 ke5.a.i 调用）-> `REPLACE kind=1`，但 VERIFY 显示 `AnimImageView vis=8 w=0 h=0`（GONE/零尺寸） |
| 同时 | `mq.b CALL isRecv=false msgType=-1` —— 方向源读取失败，所有语音都按 kind=1（自己）处理 |

### 13.2 AnimImageView.setType 源码（v4 已实证，i 的语义）

```java
public void setType(int i) {
    if (this.e) {                                   // e = isRecv
        if (i == 2) setBackgroundResource(2131100638);
        else if (i == 3) setBackgroundDrawable(null);        // <- 清背景态
        else setBackgroundDrawable(ke5.a.i(ctx, 2131231925));
    } else {
        if (i == 2) setBackgroundResource(2131100639);
        else if (i == 3) setBackgroundDrawable(null);        // <- 清背景态
        else setBackgroundDrawable(ke5.a.i(ctx, 2131232060));
    }
}
```
`i==3` = **微信主动清空气泡背景**（播放中/复用清理态），且此路径**不再经过 ke5.a.i / setBackgroundResource**，你原有的两道 resId 白名单完全旁路。

### 13.3 语音 holder 完整字段表（mq.b Smali 实证，v9 新增）

```java
public h0 b(View view, boolean z, boolean z2) {     // z = isRecv(对方)，z2 = isGroup(群聊)
    timeTV = findViewById(2131366064);  userTV = findViewById(2131366078);
    mq.d = findViewById(2131366097);     // 时长 TextView
    stateIV = findViewById(2131366060);
    mq.s = findViewById(2131365751);     // MMNeat7extView（语音转文字）
    mq.o = findViewById(2131366098);     // FrameLayout
    mq.t = findViewById(2131366092);     // ProgressBar
    mq.c = findViewById(2131366095);     // TextView
    mq.e = findViewById(2131366091);     // AnimImageView 主动画（自己的语音）setType(1)
    mq.C = findViewById(2131365817);     // RelativeLayout 容器
    mq.D = findViewById(2131365816);     // TextView（StateListDrawable 背景，备用承载）
    if (z) {                              // 收到的语音
        mq.e.setFromVoice(true); mq.e.setFromGroup(z2);
        mq.u = findViewById(2131366096);  // 第二个 AnimImageView（收到侧）setType(0)
        mq.u.setType(0);
    } else {                              // 发出的语音
        mq.q/r/w/x = ...
        mq.e.setFromVoice(false);
    }
}
```

关键修正：
- **方向唯一可靠来源 = `mq.b` 的 `param.args[1]`（z=isRecv）与 `param.args[2]`（z2=isGroup）**；`AnimImageView` 自身字段读出的 isRecv 恒 false（日志实证），不可用。
- 收到侧语音气泡挂在 **`mq.u`（id 2131366096，setType(0)）**，自己侧挂 **`mq.e`（id 2131366091，setType(1)）**；两条都可能在 type=3 态被清背景。

### 13.4 修复方案

```java
// 1) 方向表：mq.b hook 拿准 z/z2，覆盖两个 AnimImageView
findAndHookMethod("com.tencent.mm.ui.chatting.viewitems.mq", cl, "b",
    View.class, Boolean.class, Boolean.class, new XC_MethodHook(){
    protected void afterHookedMethod(MethodHookParam p){
        boolean isRecv = (Boolean) p.args[1];                 // z
        View root = (View) p.args[0];
        for (int id : new int[]{2131366091, 2131366096}) {   // mq.e / mq.u
            View av = root.findViewById(id);
            if (av != null) BUBBLE.put(av, isRecv);
        }
        View d = root.findViewById(2131365816);               // mq.D 备用承载
        if (d != null) BUBBLE.put(d, isRecv);
    }
});

// 2) setType hook：type==3（清背景态）不要就地贴，延后到布局稳定后补
findAndHookMethod("com.tencent.mm.ui.base.AnimImageView", cl, "setType", int.class,
  new XC_MethodHook(){
    protected void afterHookedMethod(MethodHookParam p){
        View v = (View) p.thisObject;
        if ((int) p.args[0] == 3) {                          // 微信正在清背景
            v.post(() -> { Boolean r = BUBBLE.get(v); if (r != null) applyBubble(v, r); });
        }
    }
});

// 3) 统一补盖入口（bind/attach/list onLayout 均调用，方向以 BUBBLE 表为准）
void applyBubble(View v, boolean isRecv) {
    Drawable d = fresh(isRecv ? myRecv : mySend);
    v.setBackground(d);
    v.setVisibility(View.VISIBLE);                           // 防 GONE 态不渲染
    v.setPadding(pl, pt, pr, pb);
    v.requestLayout(); v.invalidate();
}
```

### 13.5 自检清单（换构建/回归必做）

1. dump 语音 item 全树：`log(id, class, bg.class, vis, w, h)`，确认当前形态下气泡真实承载（mq.e / mq.u / mq.D 三者哪个有 9-patch 且 VISIBLE）。
2. 播放一条语音，观察 `setType` 的 i 值序列（1/0 -> 3 -> 是否恢复 1/0）；依此决定补盖时机（post 延迟或 list onLayout after）。
3. 校验方向：`mq.b` args[1]=true 的 item，气泡必须用 myRecv；日志里不再出现 `isRecv=false` 一刀切。
4. ` AnimImageView` 若为 GONE，说明本形态气泡不在它身上——改贴 §13.3 表里 VISIBLE 的那个 view。

---

## 14. v10 增补：语音气泡发送后彻底消失（录音面板被误贴 + setType(3) 状态机冲突）

### 14.1 日志实锤（v3.0.160，21:49:58 正常 -> 21:50:02 故障）

| 时间 | 证据 | 解读 |
|---|---|---|
| 21:49:58 | `AnimImageView.setType type=1 ... parent=FrameLayout` + VERIFY `w=254 h=127 vis=0 custom=true` | 聊天气泡正常渲染（mq.e，FrameLayout 内） |
| 21:50:02（长按说话后） | `AnimImageView.setType type=3 ... parent=LinearLayout` -> `REAPPLY type=3 kind=1` | **出现第二类 AnimImageView**：按住说话录音面板的麦克风（76x76，LinearLayout 内） |
| 21:50:02.458 | `AnimImageView VERIFY ... w=76 h=76 vis=0 attached=true parent=LinearLayout custom=true` | **模块把聊天气泡贴到了录音麦克风上**（漏网之鱼） |
| 21:50:02 之后 | 列表 item 再无 `setType type=1` 事件；21:50:02.259 VERIFY 聊天气泡 `vis=8` | 微信清背景后不再自恢复，模块也没补 → 气泡消失 |

### 14.2 根因

1. **AnimImageView.setType 有两类调用方**：聊天 item 内的气泡（parent=FrameLayout，mq.e/mq.u）和 ChatFooter 录音面板麦克风（parent=LinearLayout，76x76）。hook 必须用 `inChatItem(v)` 只认前者。
2. `setType(3)` = 微信**主动清背景态**（录音中/复用清理）。after 里强制 REAPPLY 静态气泡，与微信录音动画状态机冲突；录音完成/发送后微信不再补 setType(1)（不重新 bind），气泡停在被冲掉的状态。
3. 附带方向 bug：`attach BUBBLE apply isRecv=true kind=1 view=MMNeat7extView` —— isRecv=true 却贴 kind=1（自己）气泡，方向源混用。

### 14.3 修复

```java
// 1) setType hook：只认聊天 item 内 + type=3 不 REAPPLY
findAndHookMethod("com.tencent.mm.ui.base.AnimImageView", cl, "setType", int.class, after(p) -> {
    View v = (View) p.thisObject;
    if (!inChatItem(v)) return;                       // 录音麦克风/ChatFooter 全挡掉
    if ((int) p.args[0] == 3) return;                 // 微信要清就让它清，别 REAPPLY
    Boolean recv = BUBBLE.get(v);
    if (recv != null) applyBubble(v, recv);
});

// 2) 补盖时机改到列表布局稳定后（录音结束后一次覆盖）
findAndHookMethod(RecyclerView.class, "onLayout", boolean.class, int.class, int.class, int.class, int.class, after(p) -> {
    if (!inChat) return;
    for (Map.Entry<View, Boolean> e : BUBBLE.entrySet())
        if (e.getKey().isAttachedToWindow()) applyBubble(e.getKey(), e.getValue());
});

// 3) 方向唯一来源：mq.b 的 args[1]（z=isRecv），apply 统一
void applyBubble(View v, boolean isRecv) {
    v.setBackground(fresh(isRecv ? myRecv : mySend));   // 杜绝 isRecv=true kind=1
    v.setVisibility(View.VISIBLE);                      // 清背景态恢复后防 GONE
    v.requestLayout(); v.invalidate();
}
```

### 14.4 自检

1. 长按说话时 dump：是否还有 `parent=LinearLayout w=76` 的 AnimImageView 被贴气泡（应为 0）。
2. 发送语音后 3 秒内，`RecyclerView.onLayout` after 是否对 BUBBLE 集合补盖成功（VERIFY 出现 `w=254 h=127 vis=0 custom=true`）。
3. 文本 item 不再出现 `isRecv=true kind=1` 组合。
