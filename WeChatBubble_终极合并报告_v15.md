# 微信聊天气泡 / 文字 / 时间线 / 稳定渲染 —— 终极合并报告（v15）

> **本文档是本系列分析的唯一最终版**，合并以下全部内容并做最终审查：
> - v12 气泡锚点与替换体系 / v13 颜色双模式体系 / v14 稳定渲染与时间线体系 / LeShaoV3 v3.0.160 运行日志实证
> - 分析对象：com.tencent.mm（versionCode=3180，含 Tinker 热修）
> - 实证方式：LSPilot（DexKit + jadx + baksmali）静态 + 运行日志逐条比对
> - 适用：自研 Xposed 模块（不依赖 LSPilot BSH）

---

## 0. 使用说明（必读）

1. §1-§4 是“地图”（环境/链路/锚点）；§5-§8 是“施工图”（气泡/颜色/稳定渲染/时间线）；§9 是**一页式总装代码**；§10-§12 是锚点表/坑/自校准；§13-§14 是审查记录与不确定性。
2. 所有 resId / 类名仅对 versionCode=3180 本构建有效；换构建必须按 §10 锚点 + §12 dump 重新标定。
3. **所有 hook 必须先过 §7 的隔离门**（bind 级隔离或 inChatItem 门），否则必外溢（v11 视频号评论区泄漏实锤）。
4. 报告内标注 ⚠️ 的为未捕获/推测项，均附了运行时 dump 补全法，不阻塞主方案。

---

## 1. 环境与关键事实

| 项 | 值 |
|---|---|
| 包名 / versionCode | com.tencent.mm / 3180 |
| 资源 | 名称已混淆（getIdentifier("chatfrom_bg")=0，真名 mh/o_）→ 一切按 resId |
| 类名 | 重度混淆（viewitems/kw5/gm/ke5/en5/xl5/am5/hn5 等） |
| 布局方案 | 混合：运行时 item 走二进制 XML；gm.* 为 X2C 遗留（仅默认 item 用）；BootX2CFactory 注册 24 个 X2C 布局 |
| Tinker | 有热修；静态与运行可能不同源 → 以 dump 为准 |
| 日志锚点 | "[onBindView] finish position:" / "MicroMsg.MvvmChattingItem" |

---

## 2. 勘误总表（历史错误，最终定论）

| # | 历史结论 | 判定 | 正解 |
|---|---|---|---|
| 1 | 气泡走 X2C `kw5/g.r` | ❌ | gm.g/gm.i 是遗留布局；文本 item 走二进制 XML |
| 2 | hook View 基类 setBackground* 可改气泡 | ❌ | MMNeat7extView 重写了这两个方法，虚分派绕过基类 hook |
| 3 | hook `viewitems.to.b` 可改气泡 | ❌ | to.b 仅关怀模式注册（le5.e.b()&&le5.e.d()） |
| 4 | 时间条 id=2131366078 | ❌ | **timeTV=2131366064（0x7f0a10b0）；2131366078=userTV 群昵称** |
| 5 | AnimImageView 字段 e 可读 isRecv | ❌ | 方向唯一来源 = `mq.b(View,boolean z,boolean z2)` 的 **z=isRecv**（z2=isGroup） |
| 6 | onAttachedToWindow/onLayout 补盖可稳定 | ❌ | 事后补丁永远晚于微信清空；正解 = **bind 级注入 xl5.g.h** |
| 7 | setType(3) 后 REAPPLY 保气泡 | ❌ | i==3 是微信主动清背景态；REAPPLY 与录音状态机冲突 → 发送后永久消失（v10 实锤） |
| 8 | 全局 setTextColor 改文字色 | ⚠️ | 必泄漏（视频号评论区 v11 实锤）；必须 bind 级或 inChatItem 门 |
| 9 | 文本气泡唯一来源=XML background | ✅ | MMNeat7extView.setBackground(Drawable)（inflate 期）+ bind-after 双保险 |

---

## 3. 完整 UI 封装链路

```
ChattingUI（MMSecDataFragmentActivity → BaseMvvmFragmentActivity → VASLauncher）
└─ ChattingUIFragment（字段 B = MMMChattingListView，extends MMPullDownView → FrameLayout）
   └─ ChattingDataAdapter → WxRecyclerAdapter（MvvmList 架构）
      └─ ItemConvert：xl5.g（每 viewType 一个实例）
         ├─ c(rv)  : kt.b(viewType).H(inflater,null) → new viewitems.jh(inflater,布局)；setTag(2131365948,item)
         ├─ d(rv,v): holder = v.getTag()（BaseViewHolder）
         └─ h(...) : ★bind 主入口（MvvmChattingItem.dealItemView）★ ← 稳定渲染的唯一正确注入点
            └─ item.H 建的容器 jh（ChattingItemContainer）内 inflate 二进制布局（文本=0x7f0e0382）
               └─ 各消息类型 fill：hn5.v.n→v.d(UI块链)→hn5/o.invoke 等；语音=mq；复杂消息=mvvmview
```

---

## 4. 承载视图 / 锚点 id 总表（全部 Smali 实证）

### 4.1 通用 holder（viewitems.h0 = ChattingItem$BaseViewHolder）
| 字段 | id | 说明 |
|---|---|---|
| **timeTV** | **2131366064 / 0x7f0a10b0** | 时间线 TextView（L241 setText(info.e)） |
| **userTV** | **2131366078 / 0x7f0a10be** | 群昵称 TextView（单聊 GONE） |
| checkBox / maskView | 2131365740 / 2131365979 | 多选 / 遮罩 |
| historyMsgTip / noMoreMsgTip | dump | 历史消息/没有更多提示 |
| quoteView | dump | 引用消息（to.r=0x7f0a0f5b 发送者昵称） |

### 4.2 文本 item（holder=viewitems.to）
| 字段 | id | 说明 |
|---|---|---|
| **to.b** | **2131365751 / 0x7f0a0f77** | MMNeat7extView 内容/气泡（带 viewitems.ap tag） |
| 布局 | 0x7f0e0382 | chatting_item_from/to 共用（二进制 XML，非 X2C） |
| item 根 | — | viewitems.jh（ChattingItemContainer），tag(2131365948)=ChattingItem |

### 4.3 语音 item（holder=viewitems.mq，b(View,z=isRecv,z2=isGroup)）
| 字段 | id | 说明 |
|---|---|---|
| mq.d | 2131366097 / 0x7f0a10d1 | 时长 TextView |
| **mq.e** | **2131366091** | AnimImageView 主动画（双向通用；setType(1)；内部 e 区分收/发） |
| **mq.u** | **2131366096** | 收到侧附加动画视图（setType(0)） |
| mq.C / mq.D | 2131365817 / 2131365816 | RelativeLayout 容器 / TextView（StateListDrawable 备用承载） |
| mq.s | 2131365751 | MMNeat7extView（语音转文字） |
| mq.o / mq.t | 2131366098 / 2131366092 | FrameLayout / ProgressBar |

### 4.4 颜色/图标/drawable resId 总表
| 资源 | resId | 用途 |
|---|---|---|
| **FG_0** | **2131099867** | 对方内容文字；语音图标基准染色色 |
| **chatting_to_text_color** | **2131100740** | 自己内容文字 |
| hint_text_color | 2131101500 | 时间/昵称/hint |
| normal_text_color | 2131101978 | 名片/文章标题 |
| half_alpha_black | 2131101488 | 名片/文章描述 |
| white_text_color | 2131102773 | 深色底文字 |
| BW_100_Alpha_0_8 | 2131099718 | 深色底 80% 白字 |
| 链接常规/按下 | 2131102210 / 2131100799 | 内容链接 span |
| BW_0_Alpha_0_9 / chat_card_seperator_color | ⚠️dump | 深色底 90% 黑 / 名片分隔线 |
| **chatfrom_bg / chatto_bg** | **2131231925 / 2131232060** | 文本+语音气泡（ke5.a.i） |
| 文本链接子类型 | 2131231944 / 2131232070 | hn5.r0.g0 / hn5.s0.k0 |
| 关怀/发送中 | 2131231925/2060、2131231841/1895 | to.b / setType(2) |
| AppMsg（默认 item） | chat_from_mask_bg 2131231853 / chatto_bg_app 2131232062 / chat_to_mask_bg 2131231907 | kw5/g.r→kw5/i0.f/n |
| **语音图标帧（收到 el.d）** | **2130968839 / 2130968840 / 2130968841** | 0x7f040107-9 |
| **语音图标帧（发送 getDrawable/zk.e）** | **2131821038 / 2131821039 / 2131821040** | 0x7f1101ee-f0 |
| MvvmMsgInfo 时间文本 | am5.d.e（String，空=不显示） | 见 §8 |

---

## 5. 气泡替换体系

### 5.1 三条实设路径（文本气泡）
| 场景 | 路径 | 处理 |
|---|---|---|
| 普通态（99%） | 二进制 XML android:background → inflate → **MMNeat7extView.setBackground(Drawable)**（override 会把 padding 同步给内层 wrappedTextView） | hook MMNeat7extView.setBackground（before 换 Drawable）+ bind-after 双保险 |
| 链接子类型（电话/网址） | hn5.r0.g0 / hn5.s0.k0 → setBackgroundResource(2131231944/2070) | setBackgroundResource 白名单 |
| 关怀模式 | hn5.q.invoke → viewitems.to.b → setBackgroundResource(2131231925/2060) | 同上 |

### 5.2 语音气泡 = AnimImageView.setType 状态机（Smali 全文）
```java
public void setType(int i) {
    if (this.e) {                                  // e=isRecv（内部字段，外部不可靠）
        if (i == 2) setBackgroundResource(2131100638);      // 发送态高亮
        else if (i == 3) setBackgroundDrawable(null);       // ★清背景态：录音中/复用清理★
        else setBackgroundDrawable(ke5.a.i(ctx, 2131231925));
    } else { ... 2131100639 / null / 2131232060 ... }
}
// b() 播放态：setCompoundDrawablesWithIntrinsicBounds(h 收到 / i,m 发送) 挂图标
// c() 停止：清 compound drawables
```
三条军规（v10 实锤）：
1. **i==3 直接 return**，禁止 REAPPLY（否则发送语音后气泡永久消失）；
2. hook 必须 `inChatItem(v)`——录音面板麦克风也是 AnimImageView（76x76 / parent=LinearLayout，v10 误贴实锤）；
3. 方向来源 = `mq.b` 的 **z（isRecv）/z2（isGroup）**，不是 AnimImageView 字段。

### 5.3 语音图标颜色（AnimImageView.a() 322 行 Smali 全链路）
```java
// a() 构建三个 AnimationDrawable（custom uo5/a，只拼一次 → 必须 hook 创建期）：
//   h 收到方静态 3 帧 ← com.tencent.mm.ui.el.d(Context,int)   [obtainStyledAttributes(new int[]{resId})]
//   i 发送方静态 3 帧 ← Resources.getDrawable(int)
//   m 发送方播放中染色 3 帧 ← com.tencent.mm.ui.zk.e(Context,resId,color)
//        color = Resources.getColor(2131099867); zk.e: mutate().setColorFilter(PorterDuffColorFilter(SRC_ATOP)) + setAlpha
static final Set<Integer> ICONS = new HashSet<>(Arrays.asList(
    2130968839, 2130968840, 2130968841, 2131821038, 2131821039, 2131821040));

// ① 播放中染色：换 zk.e 第三参（必做）
findAndHookMethod("com.tencent.mm.ui.zk", cl, "e", Context.class, int.class, int.class,
    before(p) -> { if (ICONS.contains((int) p.args[1])) p.args[2] = color(iconColor); });
// ② 发送方静态帧：Resources.getDrawable(int) 白名单
findAndHookMethod(Resources.class, "getDrawable", int.class, before(p) -> {
    if (ICONS.contains((int) p.args[0])) p.setResult(tint(iconColor));
});
// ③ 收到方静态帧：el.d（走 obtainStyledAttributes，单独 hook）
findAndHookMethod("com.tencent.mm.ui.el", cl, "d", Context.class, int.class, after(p) -> {
    Drawable d = (Drawable) p.getResult();
    if (d != null && ICONS.contains((int) p.args[1]))
        d.setColorFilter(color(iconColor), PorterDuff.Mode.SRC_ATOP);
});
```
以上三个 resId 集合全 App 独占 → **无需页面门也不会外溢**。

### 5.4 语音气泡 direction 采集 + 补盖
```java
findAndHookMethod("com.tencent.mm.ui.chatting.viewitems.mq", cl, "b",
    View.class, Boolean.class, Boolean.class, after(p) -> {
    boolean isRecv = (Boolean) p.args[1];
    View root = (View) p.args[0];
    for (int id : new int[]{2131366091, 2131366096}) {
        View av = root.findViewById(id);
        if (av != null) BUBBLE.put(av, isRecv);
    }
    View d = root.findViewById(2131365816);                 // mq.D 备用承载
    if (d != null) BUBBLE.put(d, isRecv);
});
// 主灌水口在 §7 bind 级：xl5.g.h after → applyBubble(view, isSend) 统一渲染（不再依赖 attach）
static void applyBubble(View v, boolean isRecv) {
    v.setBackground(fresh(isRecv ? myRecv : mySend));     // getConstantState().newDrawable().mutate()
    v.setVisibility(View.VISIBLE);                        // 防清背景态恢复后 GONE
    v.setPadding(pl, pt, pr, pb); v.requestLayout(); v.invalidate();
}
```

---

## 6. 颜色定制体系（双模式）

### 6.1 元素 × 颜色映射（浅/暗两套值由 resId 自动分派）
| 元素 | resId | 设置路径 |
|---|---|---|
| 对方文本气泡文字 | FG_0 2131099867 | 二进制 XML textColor → MMNeat7extView（外层 View 背景/文字分离：外层画背景+padding，内层 wrappedTextView 的 Layout 画文字） |
| 自己文本气泡文字 | chatting_to_text_color 2131100740 | 同上 |
| 时间条 / 群昵称 | hint_text_color 2131101500 | timeTV(2131366064)/userTV(2131366078) XML；userTV 仅群聊可见 |
| 名片标题 | normal_text_color 2131101978 | ChattingContactCardMvvmView（DataBinding） |
| 名片描述/号码 | half_alpha_black 2131101488 | 同上 |
| 名片白卡/深底文字 | white_text_color 2131102773 / BW_100_Alpha_0_8 2131099718 | 同上 |
| 文章标题/摘要 | normal_text_color / half_alpha_black | ChattingUrlMvvmView（DataBinding） |
| 内容内链接/电话 | 2131102210 常规 / 2131100799 按下 | neattext span 构造期（hn5/o.invoke：tVar.g / tVar.f） |
| 语音图标 | 见 §5.3 | — |

### 6.2 双模式机制
所有文字色均为 **resId 引用**，浅/暗两套值存于 values / values-night，由 `Resources` 按 `Configuration` 自动返回 → 模块按 resId 拦截即可双模式（自定义色用 XModuleResources.getColor(myRes)，它会按当前 Configuration 返回你提供的 day/night 两值）。

### 6.3 三套 hook 方案（按稳定/隔离排序）
```java
// 方案 0（最终主方案，见 §7）：bind-after 整树上色 —— 天然稳定+天然聊天隔离

// 方案 1（resId 级，慎用）：仅当目标 resId 是聊天专属色才用；全局色（FG_0 等）禁用
// findAndHookMethod(Resources.class, "getColorStateList", int.class, before(p)->{ replace(p,(int)p.args[0]); });
// static boolean isNight(Context c){ return (c.getResources().getConfiguration().uiMode
//     & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES; }

// 方案 2（视图级兜底）：setTextColor 必须过门，防漏（v11 评论区泄漏根因=无门）
findAndHookMethod(TextView.class, "setTextColor", ColorStateList.class, before(p) -> {
    View v = (View) p.thisObject;
    if (!inChat || !inChatItem(v)) return;                 // inChat：聊天页前台（WmEntry）
    p.args[0] = ColorStateList.valueOf(color(contentColor));
});

// 方案 3（链接 span）
findAndHookMethod("hn5.o", cl, "invoke", Object.class, after(p) -> {
    Object t = p.getResult();
    XposedHelpers.setObjectField(t, "g", color(linkColor));      // 2131102210
    XposedHelpers.setObjectField(t, "f", color(linkPressed));    // 2131100799
});
```

### 6.4 复杂消息（位置/名片/文章/链接/文件…）= mvvmview DataBinding 体系（132 个类）
```java
// ChattingLocationCardMvvmView.c(Context) = new d0(new q(LayoutInflater.inflate(2131625004, this, false)))
// 布局走二进制 XML + DataBinding，文字色默认黑 → 换深色气泡必看不清
static void recolorItem(View root, boolean isRecv) {   // 整树、不依赖任何 id
    ArrayDeque<View> st = new ArrayDeque<>(); st.push(root);
    while (!st.isEmpty()) {
        View v = st.pop();
        if (v instanceof TextView) {
            int id = v.getId();
            if (id == 2131366064)      ((TextView) v).setTextColor(color(timeColor));
            else if (id == 2131366078) ((TextView) v).setTextColor(color(nickColor));
            else ((TextView) v).setTextColor(color(isRecv ? fromText : toText));
        } else if (v instanceof ImageView) {
            ((ImageView) v).setColorFilter(color(iconColor), PorterDuff.Mode.SRC_ATOP);
        }
        if (v instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++)
                st.push(((ViewGroup) v).getChildAt(i));
    }
}
```
mvvmview 内部 TextView id 未逐一采集（recolorItem 规避；精细控制先 §12 dump）。

---

## 7. 稳定渲染架构（bind 级注入，根治滑动不渲染/外溢）

### 7.1 原理
之前用 onAttachedToWindow/onLayout「事后补盖」，永远晚于微信在 bind/复用/播放态里的清空（setType(3) 等）。**正确模型 = 直接 hook 聊天适配器的 onBindViewHolder**：
```java
xl5.g.h(s0 holder, am5.d MvvmMsgInfo, int pos, int type, boolean, List)  // MvvmChattingItem.dealItemView
```
- **每次 bind 必走**（初次/上下滑/回收复用/notifyItemChanged）→ 渲染天然稳定，等价“直接注入”；
- **只存在于聊天列表适配器** → 视频号/朋友圈/输入框结构上不可能被渲染（天然隔离）；
- after 里即可拿到全部锚点：`((k3)args[0]).itemView` → `getTag()=h0`、`getTag(2131365948)=ChattingItem`；`args[1]` → `.d.b=msg(e9)`、`.e=时间文本`、`.i=ChattingItem`；`msg.z0()=是否自己`（日志 "send:"+e9.z0() 实证）。

### 7.2 隔离双保险
| 层 | 机制 |
|---|---|
| 天然层 | hook 类 xl5.g 是聊天 ItemConvert → 其他页面 bind 不经过它 |
| 兜底层 | 资源级 hook 只用独占 resId 白名单（语音图标 6 帧、气泡 drawable） |
| 禁做 | 全局色（FG_0/hint_text_color 等）**禁止** `Resources.getColor` 级替换 |

### 7.3 装配
```java
findAndHookMethod("xl5.g", cl, "h", <s0>, <am5.d>, int.class, int.class, boolean.class, List.class,
    after(p) -> {
    View itemView = (View) XposedHelpers.getObjectField(p.args[0], "itemView");
    if (itemView == null) return;
    Object info = p.args[1];                                  // MvvmMsgInfo
    Object msg = XposedHelpers.getObjectField(
        XposedHelpers.getObjectField(info, "d"), "b");         // hn5.a.b = e9
    boolean isSend = (Boolean) XposedHelpers.callMethod(msg, "z0");
    recolorItem(itemView, !isSend);                            // 文字/图标（§6.4）
    applyTimeLine((h0) itemView.getTag(), msg, info);          // 时间线（§8）
    // 气泡：内容 ITV（to.b,MMNeat7extView）+ 语音 view（BUBBLE 表，§5.4）
});
```
> `<s0>/<am5.d>` 形参为接口类型，hook 时可用 DexKit 反混淆签名（§10）定位后按实际类名写，或对 inflate 后的 convert 实例做 `XposedHelpers.findAndHookMethod(convertObj.getClass(), "h", ...)`。

---

## 8. 时间线（全链路 + 三件套）

### 8.1 数据层/视图层实证
```java
// 数据层：am5.d(MvvmMsgInfo) { hn5/a d; String e; String f; boolean g; hn5/a1 h; b0 i; }
//   e = 时间线文本（★空字符串=不显示分隔★；间隔>5 分钟才由数据层生成）
// 视图层：xl5/g.h（dealItemView）L212-241 Smali 实证：
//   timeTV.setVisibility(isEmpty(info.e) ? 8 : 0);   // L213-240
//   timeTV.setText(info.e);                          // L241 ← 内容唯一来源
//   timeTV.setOnClickListener(new d(this, msg));     // L242 点击（跳转/复制）
// 字段：viewitems.h0.timeTV（find_field 实证，唯一声明，字段名未混淆）；id=2131366064
```

### 8.2 三件套 hook（全部在 bind after，天然稳定）
```java
static void applyTimeLine(h0 holder, Object msg, Object info) {
    TextView timeTV = (TextView) XposedHelpers.getObjectField(holder, "timeTV");
    if (timeTV == null) return;

    // ① 修改内容：buildCustomTime 返回 null 则保留原文
    String custom = buildCustomTime((Long) XposedHelpers.callMethod(msg, "getCreateTime"));
    String origin = (String) XposedHelpers.getObjectField(info, "e");
    timeTV.setText(custom != null ? custom : origin);

    // ② 每一条消息都加时间线：无视 e 是否为空
    timeTV.setVisibility(View.VISIBLE);

    // ③ 左侧显示：按父容器三分支
    moveToSide(timeTV, true);

    // ④（可选）替换点击行为
    if (REPLACE_CLICK) timeTV.setOnClickListener(v -> { /* your action */ });
}

static String buildCustomTime(long ms) {                 // 与微信数据层同款格式（en5.n1.sj 实证）
    Date d = new Date(ms);
    if (isToday(d))     return new SimpleDateFormat("HH:mm", Locale.CHINA).format(d);
    if (isYesterday(d)) return "昨天 " + new SimpleDateFormat("HH:mm", Locale.CHINA).format(d);
    return new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.CHINA).format(d);
}

static void moveToSide(TextView tv, boolean left) {
    ViewGroup.LayoutParams lp = tv.getLayoutParams();
    ViewParent par = tv.getParent();
    int g = left ? Gravity.START : Gravity.END;
    if (par instanceof LinearLayout && lp instanceof LinearLayout.LayoutParams) {
        ((LinearLayout.LayoutParams) lp).gravity = g | Gravity.CENTER_VERTICAL;  // 竖向 LL 水平重力
        tv.setGravity(g);
    } else if (par instanceof RelativeLayout && lp instanceof RelativeLayout.LayoutParams) {
        ((RelativeLayout.LayoutParams) lp).addRule(left ? ALIGN_PARENT_LEFT : ALIGN_PARENT_RIGHT);
        ((RelativeLayout.LayoutParams) lp).removeRule(RelativeLayout.CENTER_HORIZONTAL);
    } else if (lp instanceof FrameLayout.LayoutParams) {
        ((FrameLayout.LayoutParams) lp).gravity = g;
    }
    tv.setLayoutParams(lp);
    tv.requestLayout();
}
// ⚠️ timeTV 父容器类型以运行时 dump 为准；moveToSide 已做三分支兼容。
```

---

## 9. 一页式总装清单（本次所有 hook）

| # | hook | 作用 | 门控 |
|---|---|---|---|
| 1 | **xl5.g.h**（dealItemView）after | 气泡/文字/时间线按 msg 渲染（**主**） | 天然 bind 级 |
| 2 | mq.b after | 语音方向采集（z=isRecv）→ BUBBLE 表 | 天然 bind 级 |
| 3 | MMNeat7extView.setBackground / setBackgroundResource before | 文本气泡直改（双保险） | inChatItem |
| 4 | View.setBackgroundResource before（白名单 2131231944/2070/1925/2060/1841/1895） | 子类型/关怀/发送态气泡 | inChatItem |
| 5 | AnimImageView.setType after | i==3 return；其余 applyBubble | inChatItem |
| 6 | com.tencent.mm.ui.zk.e before（6 帧白名单） | 语音播放中图标染色 | resId 独占 |
| 7 | Resources.getDrawable(int) before（同上白名单） | 发送方静态图标 | resId 独占 |
| 8 | com.tencent.mm.ui.el.d after（同上白名单） | 收到方静态图标 | resId 独占 |
| 9 | ke5.a.i / Resources.getDrawable(int)（气泡 2 id） | 语音/默认 item 气泡（辅） | resId 独占 |
| 10 | kw5.g.r + kw5.i0.f/n（仅默认 item AppMsg） | AppMsg 气泡 | resId |
| 11 | TextView.setTextColor(ColorStateList) before | 兜底防漏 | inChat+inChatItem |
| 12 | hn5.o.invoke after | 链接 span 色 | inChatItem |
| 13 | viewitems.to.b / hn5.r0.g0 / hn5.s0.k0 | 精准改气泡（可选） | inChatItem |
| — | ~~onAttachedToWindow / onLayout~~ | **降级为日志/自检用** | v14 决策 |

**渲染统一入口（每次 bind）**：`recolorItem(itemView, isRecv)` + `applyBubble(view, recv)` + `applyTimeLine(...)`；浅/暗由 `color()` 按 `Configuration.UI_MODE_NIGHT_MASK` 取 XModuleResources 对应值。

---

## 10. DexKit 字符串锚点全表（换构建定位）

| 目标 | 锚点 | 用法 |
|---|---|---|
| bind 主类 xl5.g | `"[onBindView] finish position:"` | findMethodsByString |
| 类名确认 | `"MicroMsg.MvvmChattingItem"` | 日志 tag |
| 反混淆签名 | `"dealItemView"` + `"(Landroid/view/View;Lcom/tencent/mm/ui/chatting/mvvm/item/MvvmMsgInfo;ZLcom/tencent/mm/ui/chatting/mvvm/MvvmChattingItem$MvvmChattingViewHolder;)V"` | 埋在 a.d/a.f 埋点字符串 |
| 时间线可见性 | `"com/tencent/mm/kt/CommonKt"` `"visibleIf"` | 定位 L213-240 |
| timeTV 字段 | `timeTV`（未混淆） | findField |
| 语音 holder | `mq.b`（形参 View,boolean,boolean） | 方向采集 |
| AnimImageView 状态机 | `setType` + `ke5.a.i` 调用栈 | setType(3)=清背景 |
| 时间格式 | `"yyyy/MM/dd HH:mm"` / `"yyyy年MM月dd日 HH:mm"` | en5.n1.sj 同款 |
| 文本 item | `"@color/FG_0"`(2131099867) / `"@color/chatting_to_text_color"`(2131100740) | gm.g.c L105 / gm.i.c L209 |
| 名片/文章色 | `"@color/normal_text_color"`(2131101978) / `"@color/half_alpha_black"`(2131101488) | gm.h.c L262 / gm.f.c L280 |
| 链接色 | hn5/o.invoke tVar.g=2131102210 / tVar.f=2131100799 | span 字段 |
| X2C 注册表 | `com.tencent.mm.autogen.layout.BootX2CFactory` | 24 布局映射 |
| 颜色名→resId | `"chatfrom_bg"/"chatto_bg"`→2131231925/2060 | X2C 生成类 |

---

## 11. 已知坑速查

| 现象 | 根因 | 修复 |
|---|---|---|
| 滑动不渲染 | 事后补盖晚于微信清空 | §7 bind 级注入 |
| 视频号评论区变色 | setTextColor 全局无门 | §7 天然隔离 / §6.3 方案 2 门 |
| 主页/输入框被贴气泡 | 几何猜气泡 | inChatItem + 四重过滤（id 黑名单：2131366064/2131366078/0x7f0a1075/0x7f0a0fc3/0x7f0a0f6c/0x7f0a0f74） |
| 语音发送后气泡消失 | setType(3) REAPPLY + 录音麦克风误贴 | i==3 return + inChatItem |
| 深色气泡下黑字 | mvvmview DataBinding 默认黑 | recolorItem 整树 |
| 链接色不变 | span 不走 XML | §6.3 方案 3 |
| 语音图标只变一半 | 动画帧只拼一次 | §5.3 创建期三 hook |
| 暗色不变 | 写死色值 | color() 按 uiMode |
| 姓名/时间被贴气泡 | 遍历整树无过滤 | id 黑名单 + msg type 白名单 |

---

## 12. 跨版本自校准（每次升级必做）

```java
// A. 取色侧：hook Resources.getColor(int)/(int,Theme)+getColorStateList(int)，打印 resId+entryName+返回值+uiMode
//    → 得到浅/暗两套真实色值表，并补全 ⚠️ 的 BW_0_Alpha_0_9 / chat_card_seperator_color
// B. 视图侧：hook View.setBackground*/setTextColor/setImageDrawable，打印 class/id/resId/getLocationOnWindow/bg.class/vis
// C. dump 聊天页可见 TextView/ImageView 全树 → 识别内容/timeTV/userTV/名片标题描述/图标
// D. 播放一条语音记录 setType 序列（1/0→3→是否恢复）+ el.d/zk.e 调用栈 → 校准图标白名单
// E. 产出 (resId, entryName, viewClass, id, x, w, h) 表 → 更新 §4
// 注：本 APX 资源名已混淆，getIdentifier()=0 → 一切以运行时 dump 为准
```

---

## 13. 最终审查记录（v15）

| # | 审查项 | 结论 |
|---|---|---|
| 1 | 三类文档（v12/v13/v14）合并无冲突、无残留旧结论 | ✅ 勘误总表已固化 |
| 2 | 全部 id 二次核对（timeTV/userTV/to.b/mq.*） | ✅ findViewById Smali |
| 3 | 全部颜色 resId 二次核对（FG_0/chatting_to_text_color/…） | ✅ gm.* 双处交叉 |
| 4 | 语音图标三加载器+6 resId+zk.e 语义 | ✅ 322 行 Smali 逐行 |
| 5 | 时间线链路（MvvmMsgInfo.e → dealItemView L241） | ✅ am5.d 字段+h() 反编译 |
| 6 | 稳定渲染模型（bind 级 vs attach 补盖） | ✅ 日志实锤 + 语义论证 |
| 7 | 隔离双保险（bind 天然 + 白名单独占） | ✅ v11 教训固化 |
| 8 | 双模式机制（Configuration 分流） | ✅ 框架语义 |
| 9 | 总装清单 13 项无重复无冲突 | ✅ |
| 10 | ⚠️ 未捕获项均有 dump 补全路径 | ✅（BW_0_Alpha_0_9、chat_card_seperator_color、mvvmview 内部 id、>5 分钟常量） |

## 14. 遗留不确定性（如实声明）

1. `xl5.g.h` 形参 s0/am5.d 为接口类型，hook 需按 §10 签名定位后写实际类名（或 convert 实例 getClass()）；
2. timeTV 父容器类型以 dump 为准（moveToSide 三分支已兼容）；
3. mvvmview 各内部 TextView id 未逐一采集（recolorItem 规避；精细控制先 dump）；
4. 「间隔>5 分钟」数据层生成类未定位（不影响 after 覆写）；
5. Tinker 热修可能与静态分析不同源 → 一切 resId/类名以 §12 dump 为准；
6. SDK36 动态取色（日志 seed=0xff216dff）是否覆盖聊天区未静态证实（方案不依赖该结论）。

---
*报告生成：LSPilot AI 分析助手 · v15 终极合并版（DexKit + jadx + baksmali + 运行日志交叉验证）*
