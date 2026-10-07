# 微信「聊天气泡 + 时间线」DexKit 锚点汇总
## 按功能分区 · 每条标注「字符串/签名 · 实测命中数 · 命中类 · 用途」

> 全部命中数均为对目标 APK 真实调用 `find_class(using_strings=[…])` / `find_method` / `find_field` / `find_class(interfaces=…)` 的结果。
> **拼写警告**：Tag 一律 `MicroMsg.`；写成 `MicroMm.` 会命中 9999+ 类（DexKit 宽松匹配），比空结果更危险。
> **优先级**：一级（唯一）→ 二级（AND 过滤）→ 三级（字段/签名/继承）→ 四级（未混淆类名兜底）。

---

## 锚点总览

| 区 | 功能 | 一级(唯一) | 二级(需过滤) | 三级(结构) |
|---|---|---|---|---|
| A | 列表适配器 / 渲染入口 | 2 | 1 | 1 |
| B | 语音气泡 / 语音链路 | 1 | 8 | 2 |
| C | 文本气泡 | 1 | 2 | 1 |
| D | 系统提示 / 时间线 | 1 | 5 | 1 |
| E | 文字颜色 / 阴影 | 3 | 0 | 1 |
| F | 红包 / 转账 | 1 | 5 | 0 |
| G | 引用消息 | 0 | 1 | 2 |
| H | 消息数据 | 0 | 1 | 1 |
| **合计** | | **9** | **25** | **9** |

---
---

# ━━━ A 区 · 列表适配器 / 渲染入口 ━━━

## 【A-1】★一级·唯一
```text
字符串   : "_onBindViewHolder["                     实测命中：1
命中     : com.tencent.mm.ui.chatting.adapter.k      （ChattingDataAdapterV3）
用途     : 聊天列表适配器 → 挂 E0/F0 两个 bind 兜底入口
DexKit   : bridge.findClass { matcher { usingStrings("_onBindViewHolder[") } }
备注     : 比 Tag「MicroMsg.ChattingDataAdapterV3」更准（Tag 有 9 命中）
```

## 【A-2】★一级·唯一
```text
字符串   : "fixShowTime: "                          实测命中：1
命中     : com.tencent.mm.ui.chatting.adapter.k
用途     : A-1 备份；同时是"修复指定消息时间显示"入口（adapter.k#W0(int)）
DexKit   : bridge.findClass { matcher { usingStrings("fixShowTime: ") } }
```

## 【A-3】二级·需过滤
```text
字符串   : "MicroMsg.ChattingDataAdapterV3"          实测命中：9
命中     : adapter.k / adapter.f / adapter.h / adapter.j / ChattingUIFragment /
           component.hh / component.vd / adapter.ChattingDataAdapter$chatMsgChange$1 / tm5.a
过滤     : 同时含 E0(s0,int) + F0(s0,int,List) + O(k3,int) 三者 → adapter.k
DexKit   : findClass{usingStrings(...)}.firstOrNull { method(it,"E0",2)!=null && method(it,"F0",3)!=null }
```

## 【A-4】三级·接口
```text
接口     : com.tencent.mm.pluginsdk.ui.tools.t3      实测命中：4
命中     : ak5.z(IChattingListAdapter) / c04.a(Kinda) / pluginsdk.ui.tools.r0 / t0
用途     : 校验"列表适配器接口"；IMChattingListAdapter 的实现 = adapter.k
DexKit   : findClass { matcher { interfaces { add("Lcom/tencent/mm/pluginsdk/ui/tools/t3;") } } }
```

---
---

# ━━━ B 区 · 语音气泡 / 语音链路 ━━━

## 【B-1】★一级·唯一
```text
字符串   : "onStateBtnClick voice msg(%s) re-download!"    实测命中：1
命中     : com.tencent.mm.ui.chatting.viewitems.bq          （ChattingItemVoiceFrom）
用途     : 语音 Item·接收侧 → 判 type=34 的 From 路径
DexKit   : findClass { matcher { usingStrings("onStateBtnClick voice msg(%s) re-download!") } }
```

## 【B-2】★一级·唯一
```text
字符串   : "[voice interrupt] set continue play visible " 实测命中：1
命中     : com.tencent.mm.ui.chatting.viewitems.mq          （VoiceItemHolder）
用途     : 语音 Holder → 拿气泡 View（e/x/u）+ 填充方法 e(...)
DexKit   : findClass { matcher { usingStrings("[voice interrupt] set continue play visible ") } }
备注     : 尾随空格必须保留
```

## 【B-3】二级·需过滤
```text
字符串   : "ChattingItemVoice$ChattingItemVoiceTo"         实测命中：2
命中     : viewitems.iq / viewitems.gq
过滤     : 含 H(LayoutInflater,View)View → iq（To 侧 Item）
```

## 【B-4】二级·需过滤
```text
字符串   : "ChattingItemVoice$VoiceItemHolder"             实测命中：2
命中     : viewitems.mq / viewitems.nq
过滤     : 含 b(View,ZZ)→h0 且 e(...) 为 9 参 → mq
```

## 【B-5】二级·分流（11 命中）
```text
字符串   : "MicroMsg.ChattingItemVoice"                    实测命中：11
命中     : bq / iq / mq / nq / gp / jp / mp / pp / vq / wp / yq
分流     : From→B-1；To→B-3；Holder→B-2；Helper→B-6；gp/jp/mp/pp/wp/vq/yq 为菜单动作类或衍生类型
```

## 【B-6】二级·需过滤（AutoPlay）
```text
字符串   : "voice_continue_play_info"                      实测命中：2
命中     : viewitems.nq / com.tencent.mm.ui.chatting.x0
过滤     : 含 H(e9,Z)V + onCompletion() → x0（AutoPlay）
```

## 【B-7】二级·需过滤（VoiceLogic）
```text
字符串   : "MicroMsg.VoiceLogic"                           实测命中：6
命中     : x0 / pv.p0 / tv.e / v61.d1 / v61.f1 / v61.k$$a
过滤     : 含 n(J)F（秒数换算）+ "voiceinfo" → v61.d1
备选     : "Select * From " + "voiceinfo" → v61.d1#m()
```

## 【B-8】二级·需过滤（VoiceInfo）
```text
字符串   : "MasterBufId"                                   实测命中：5
命中     : pc5.jh0 / v61.c1 / v61.k / v61.m1 / yl.y0
过滤     : 含 b()→ContentValues 且 a(Cursor) → v61.c1（voicemsg 表）
```

## 【B-9】二级·需过滤（VoiceContent）
```text
字符串   : "voicemd5"                                      实测命中：2
命中     : pc5.ks / x95.b
过滤     : 含 getLength()I + setLength → x95.b（voicemsg 属性 Bean）
备选     : "voicemsg"（7 命中）+ getLength()I
```

## 【B-10】三级·继承
```text
super_class : viewitems.bq     实测命中：1  → tr（MVVM From 变体）
super_class : viewitems.iq     实测命中：1  → ur（MVVM To 变体）
super_class : viewitems.mq     实测命中：0  → 证明语音 Holder 无子类（一个 Holder 通吃）
DexKit   : findClass { matcher { superClass("Lcom/tencent/mm/ui/chatting/viewitems/bq;") } }
```

---
---

# ━━━ C 区 · 文本气泡 ━━━

## 【C-1】★一级·唯一
```text
字符串   : "[isOpenNeatTextView]"                         实测命中：1
命中     : com.tencent.mm.ui.chatting.viewitems.to         （文本 Holder）
用途     : 拿文本气泡 View（字段 b : MMNeat7extView, id 2131365751）
DexKit   : findClass { matcher { usingStrings("[isOpenNeatTextView]") } }
```

## 【C-2】二级·需过滤
```text
字符串   : "MicroMsg.ChattingItemTextFrom"                 实测命中：8
命中     : viewitems.fo / mb$$m / o5 / zn / hn5.e / hn5.h / hn5.o / hn5.v
过滤     : 含 d(gk5/d, am5/d, String, a1) 4 参 → hn5.v（文本 Item From 填充）
```

## 【C-3】二级·需过滤
```text
字符串   : "MicroMsg.ChattingItemTextBase"                 实测命中：1（本版）
命中     : com.tencent.mm.ui.chatting.viewitems.un          （ChattingItemTextBase 菜单）
过滤     : 如多命中 → 取含 onMMMenuItemSelected(MenuItem,int) 的
备选     : "Retr_Msg_content" / "transform text fav failed"
```

## 【C-4】三级·方法签名（气泡赋值点）
```text
签名     : b(Lcom/tencent/mm/storage/e9;L…to;Lgk5/d;Ljava/lang/Boolean;)V   实测命中：1（C-1 之后）
用途     : 文本气泡背景赋值点（4 分支：1925/2060/1841/1895）
DexKit   : findMethod { matcher { name("b"); paramCount(4);
             paramTypes(null,null,null,"java.lang.Boolean") } }  → 取所在类 == C-1
```

---
---

# ━━━ D 区 · 系统提示 / 时间线 ━━━

## 【D-1】二级·需过滤（Item）
```text
字符串   : "chat_sys_msg_del_btn"                         实测命中：3
命中     : component.qi / viewitems.mn / viewitems.zc
过滤     : 含 H(LayoutInflater,View)View 且 n(h0,d,am5.d,String) 4 参 → mn
备选     : "log_version" + "chat_username" → mn#S()
```

## 【D-2】二级·需过滤（模板文本生成）
```text
字符串   : "MicroMsg.SysMsgTemplateImp"                    实测命中：7
命中     : viewitems.rn / go3.e / go3.f / go3.g / go3.h / go3.n / yb0.e2
过滤     : 含 gj(Map,Bundle,WeakReference,int,WeakReference)→CharSequence → go3.e
```

## 【D-3】二级·需过滤（填充器）
```text
字符串   : "com/tencent/mm/ui/chatting/viewitems/ChattingItemSysMsgTemplate"   实测命中：2
命中     : viewitems.rn / viewitems.qn
过滤     : 含 a(h0,q,d,am5.d,String) 5 参 → rn
```

## 【D-4】二级·协议字段（模板子类型）
```text
"tmpl_type_masssend_sys_tip"          → mn.n() 判定 + e9
"tmpl_type_auto_translation_sys_tip"  → mn.n() 判定
"tmpl_type_recommend_remark_sys_tip"  → e9#S2()
".sysmsg.$type" / "sysmsgtemplate" / "sysmsg" → rn.a() / go3.e 解析入口
".sysmsg.sysmsgtemplate.content_template"    → rn.a() / go3.e.gj()
"filling" / "fillingExtraParts"             → rn.a() / rn.b()
"link_revoke" / "link_massend_url" / "link_url" / "link_history" → go3.e 链接处理
```

## 【D-5】★一级·唯一（时间线载体字段）
```text
字段名   : timeTV                                        实测命中：1
命中     : com.tencent.mm.ui.chatting.viewitems.h0.timeTV
用途     : 给每句话加时间线（itemView.tag → Holder → 字段 timeTV，id 2131366064）
DexKit   : findField { matcher { name("timeTV") } }
备注     : 比任何字符串都稳；h0.create() 对每条消息都 findViewById(2131366064)
```

## 【D-6】二级·type 常量（不用 DexKit，运行时读）
```text
0x2710=10000  0x2712=10002  0x10002712=268445458  0x11002712=285222674
0x22000031=570425393  0x24000031=603979825   → 均走 mn.s/t/u/v 四套模板
```

---
---

# ━━━ E 区 · 文字颜色 / 阴影（ChatBgAttr）━━━

## 【E-1】★一级·唯一
```text
字符串   : "chatbg"                                      实测命中：1
命中     : com.tencent.mm.pluginsdk.ui.i0                 （ChatBgAttr）
用途     : 文字颜色/阴影/秒数背景总开关
DexKit   : findClass { matcher { usingStrings("chatbg") } }
```

## 【E-2】★一级·唯一
```text
字符串   : "MicroMsg.ChatBgAttr"                         实测命中：1
命中     : com.tencent.mm.pluginsdk.ui.i0
```

## 【E-3】★一级·唯一
```text
字符串   : ".chatbg.$voice_second_show_background"       实测命中：1
命中     : com.tencent.mm.pluginsdk.ui.i0
```

## 【E-4】同属 i0 的其它字段串（均唯一命中同类）
```text
".chatbg.$version"  ".chatbg.$time_color"  ".chatbg.$time_show_background"
".chatbg.$time_light_background"  ".chatbg.$time_shadow_color"  ".chatbg.$time_show_shadow_color"
".chatbg.$voice_second_color"  ".chatbg.$voice_second_shadow_color"  ".chatbg.$voice_second_show_shadow_color"
"parse chatbgattr failed" / "parse chatbgattr failed, values is null"
```

## 【E-5】三级·消费方（反向定位）
```text
find_class_usage(com.tencent.mm.pluginsdk.ui.i0) → 实测仅 2 处：
  FIELD com.tencent.mm.ui.chatting.component.v2.m   ← 聊天窗口内提供者（hook 这里可直接替换整个 ChatBgAttr）
  FIELD com.tencent.mm.plugin.readerapp.ui.ReaderAppUI.o
```

---
---

# ━━━ F 区 · 红包 / 转账 ━━━

## 【F-1】★一级·唯一
```text
字符串   : "getC2CLuckyMoneyDescByHbStatus() hbType:%s hbStatus:%s receiveStatus:%s isGroupChat:%s exclusiveRecv..."   实测命中：1
命中     : com.tencent.mm.ui.chatting.z1                  （C2CAppMsgUtil）
用途     : 红包/AA 背景与颜色计算（c()/h()/b()/g()/i()）
DexKit   : findClass { matcher { usingStrings("getC2CLuckyMoneyDescByHbStatus") } }
备注     : 比 Tag「MicroMsg.C2CAppMsgUtil」（3 命中）更准
```

## 【F-2】二级·需过滤
```text
字符串   : "MicroMsg.C2CAppMsgUtil"                       实测命中：3
命中     : com.tencent.mm.ui.chatting.a2 / c2 / z1
过滤     : 含静态 c(dx0/r,Z)I 且 h(IIZ)I → z1
```

## 【F-3】二级（红包 Item）
```text
字符串   : "MicroMsg.ChattingItemAppMsgC2CFrom"           命中：viewitems.d4（红包 From）
备选     : "frhb://c2cbizmessagehandler/hongbao/receivehongbao"
          ".ui.LuckyMoneyNewReceiveUI" / ".ui.LuckyMoneyNotHookReceiveUI" / ".hk.ui.LuckyMoneyHKReceiveUI"
          "adjustRemittanceIconGravity: holder or downloadScope is null"
layout   : d4.H() inflate 2131624841，Holder b4
```

## 【F-4】二级（转账 Item）
```text
字符串   : "MicroMsg.ChattingItemAppMsgRemittanceFrom"    命中：viewitems.jd（转账 From）
备选     : "RemittanceDetailUI" / "PayURemittanceDetailUI" / "transfer_attach" / "is_sender"
```

## 【F-5】运行时判据（红包气泡 resId 表，来自 z1.h()）
```text
status=5                       → 2131231708(发) / 2131231695(收)
status=4                       → 2131231702 / 2131231689
status=3 且 subStatus==2       → 2131231702 / 2131231689
其它                            → 2131231697 / 2131231684
AA 收款（z1.c()）              → 发 2131230753/0754；收 2131230744/0745/0746/0737；兜底 0753/0744
```

---
---

# ━━━ G 区 · 引用消息 ━━━

## 【G-1】★一级·唯一（工厂方法）
```text
方法签名 : J7(Lfm5/b;)Lq71/n;                           实测命中：1
命中     : oo.a0.J7
用途     : 引用气泡工厂（mq.b() 里 ((k)n0.c(k.class)).jj().J7(null)）
DexKit   : findMethod { matcher { name("J7"); returnType("q71.n") } }
```

## 【G-2】三级·接口（实现全集）
```text
接口     : q71.n                                         实测命中：44
命中     : cr5.a1 / cr5.b / cr5.d / cr5.d1 / cr5.e / cr5.e0 / cr5.f / cr5.f0 / cr5.f1 / bs5.c …（共 44）
接口签名 : a(Lq71/p;)V   b(Landroid/content/Context;)Landroid/view/View;   getViewModel()Lq71/p;
用途     : hook 每个实现的 b(Context) → 引用卡片创建即注入（替代旧启发式遍历）
DexKit   : findClass { matcher { interfaces { add("Lq71/n;") } } }
```

## 【G-3】四级·类名未混淆
```text
类       : com.tencent.mm.plugin.msgquote.model.MsgQuoteItem     （直接用 XposedHelpers.findClass）
```

## 【G-4】容器 id（运行时）
```text
mq.F = 2131389578（引用 stub 容器）；mq.b() 里 ((RelativeLayout)mq.F.getParent()).addView(J7.b(ctx))
```

---
---

# ━━━ H 区 · 消息数据 ━━━

## 【H-1】二级·需过滤（MsgInfo）
```text
字符串   : "MicroMsg.MsgInfo"                            实测命中：66
命中     : b41.* / com.tencent.mm.storage.e9 / storage.f9* / chatroom.ui.ae / …
过滤     : searchInPackages(["com.tencent.mm.storage"])
          + 含 convertFrom(Landroid/database/Cursor;)V + getType()I + getCreateTime()J → storage.e9
DexKit   : findClass { matcher { usingStrings("MicroMsg.MsgInfo")
              searchInPackages(listOf("com.tencent.mm.storage"))
              methods { add { name("convertFrom"); paramTypes("android.database.Cursor") } } } }
```

## 【H-2】三级·字段路径（运行时，不用 DexKit）
```text
holder.i (vv5/s0.i : Object) = am5.d（ChattingItemData）
am5.d.d = hn5.a ； hn5.a.b = com.tencent.mm.storage.e9（MsgInfo）
取法：XposedHelpers.getObjectField(holder,"i") → 字段 d → 字段 b
```

## 【H-3】三级·View→消息 Tag
```text
类       : com.tencent.mm.ui.chatting.viewitems.ps（ap 为其子类）
方法     : c() → return this.a.d.b      （即 MsgInfo）
用法     : itemView.getTag() 为 ps/ap 时可直接取消息
```

---
---

# ━━━ I 区 · 结构类锚点（字段 / 签名 / 继承）汇总 ━━━

```text
I-1  findField(name="timeTV")                        → 1   viewitems.h0.timeTV
I-2  findMethod(name="J7", returnType="q71.n")       → 1   oo.a0.J7
I-3  findMethod(name="gj", paramCount=5,
                returnType=CharSequence)
       AND usingStrings("MicroMsg.SysMsgTemplateImp")→ 7→1 go3.e.gj
       （注：单独按名字 gj+5 参 = 272 命中，必须 AND）
I-4  super_class=viewitems.bq  → 1  tr      ；super_class=viewitems.iq → 1  ur
I-5  super_class=viewitems.mq  → 0  （Holder 无子类）
I-6  super_class=viewitems.b0  → 122（全部 Item，校验用）
I-7  super_class=viewitems.h0  → 80 （全部 Holder，校验用）
I-8  interfaces=[pluginsdk.ui.tools.t3] → 4（列表适配器接口）
I-9  interfaces=[q71.n] → 44（引用气泡实现）
```

---
---

# ━━━ J 区 · ✘ 禁用锚点（命中过多 / 每版本变）━━━

```text
"MicroMsg.ChattingItem"                → 267 命中（所有 Item 基类共用 Tag）
方法名 gj + 5 参（不带 usingStrings）    → 272 命中（全 APP 通用名）
"tmpl_type_"                            → 31+ 命中（协议前缀，非类锚点）
"MicroMm.*"（错拼 Tag）                 → 9999+ 命中（DexKit 宽松匹配，最危险）
"voicemsg"(7) / "MasterBufId"(5) / "voicemd5"(2) 单独使用 → 需叠加方法签名过滤
资源 id 常量（2131231925 等）            → 每个版本重排，禁止写入解析逻辑
type 数字（10000 / 34 / 436207665…）     → 只是常量，不构成类定位
```

---
---
---

# ━━━ K 区 · 最终 resolve() 汇总代码 ━━━

```kotlin
/** 四级锚点解析：先唯一串一把定位 → 多命中 AND 过滤 → 结构兜底 → 未混淆类名兜底 */
fun resolveAll(bridge: DexKitBridge, cl: ClassLoader) {
    fun load(d: String) = runCatching { XposedHelpers.findClass(
        d.removePrefix("L").removeSuffix(";").replace('/', '.'), cl) }.getOrNull()

    fun cls(descriptor: String) = load(descriptor)

    /** 一级：唯一字符串 */
    fun uniq(s: String, extra: (Class<*>) -> Boolean = { true }): Class<*>? =
        bridge.findClass { matcher { usingStrings(s) } }
            .mapNotNull { cd -> load(cd.descriptor) }
            .firstOrNull { extra(it) }

    /** 二级：字符串 + 方法签名 AND */
    fun andStr(s: String, name: String, pc: Int): Class<*>? =
        bridge.findClass { matcher { usingStrings(s) } }
            .mapNotNull { cd -> load(cd.descriptor) }
            .firstOrNull { c ->
                c.declaredMethods.any { it.name == name && it.parameterTypes.size == pc }
            }

    // ---- A 区 适配器 ----
    R.adapter    = uniq("_onBindViewHolder[")                                   // A-1 唯一
    R.rvAdapter  = load("Lcom/tencent/mm/view/recyclerview/WxRecyclerAdapter;") // 四级兜底

    // ---- B 区 语音 ----
    R.voiceItemFrom = uniq("onStateBtnClick voice msg(%s) re-download!")         // B-1 唯一
    R.voiceHolder   = uniq("[voice interrupt] set continue play visible ")       // B-2 唯一
    R.voiceItemTo   = andStr("ChattingItemVoice\$ChattingItemVoiceTo", "H", 2)   // B-3
    R.autoPlay      = andStr("voice_continue_play_info", "H", 2)                 // B-6
    R.voiceLogic    = andStr("MicroMsg.VoiceLogic", "n", 1)                      // B-7 n(J)F
    R.voiceInfo     = andStr("MasterBufId", "b", 0)                              // B-8 b()→ContentValues
    R.voiceContent  = andStr("voicemd5", "getLength", 0)                         // B-9
    R.voiceFill     = R.voiceHolder?.let { c ->
        c.declaredMethods.firstOrNull { it.name == "e" && it.parameterTypes.size == 9 } }

    // ---- C 区 文本 ----
    R.textHolder = uniq("[isOpenNeatTextView]")                                  // C-1 唯一
    R.textBubbleSetter = R.textHolder?.let { c ->
        c.declaredMethods.firstOrNull { it.name == "b" && it.parameterTypes.size == 4 } }
    R.textItemFrom = andStr("MicroMsg.ChattingItemTextFrom", "d", 4)             // C-2

    // ---- D 区 系统提示/时间线 ----
    R.sysMsgItem = andStr("chat_sys_msg_del_btn", "H", 2)                        // D-1
    R.sysMsgTemplate = andStr("com/tencent/mm/ui/chatting/viewitems/ChattingItemSysMsgTemplate", "a", 5) // D-3
    R.baseHolder = bridge.findField { matcher { name("timeTV") } }               // D-5 ★结构
        .firstOrNull()?.let { fd -> load(fd.className) }
    R.sysMsgFill = R.sysMsgItem?.let { c ->
        c.declaredMethods.firstOrNull { it.name == "n" && it.parameterTypes.size == 4 } }

    // sysmsg 文本生成：gj 5 参 + AND Tag（D-2）
    val tmplClasses = bridge.findClass { matcher { usingStrings("MicroMsg.SysMsgTemplateImp") } }
        .mapNotNull { cd -> load(cd.descriptor) }
    R.sysMsgGen = tmplClasses.firstOrNull { c ->
        c.declaredMethods.any { it.name == "gj" && it.parameterTypes.size == 5 &&
                                it.returnType == CharSequence::class.java } }
        ?.let { c -> c.declaredMethods.first { it.name == "gj" } }

    // ---- E 区 文字颜色 ----
    R.chatBgAttr = uniq("chatbg")                                                // E-1 唯一

    // ---- F 区 红包 ----
    R.c2cUtil = uniq("getC2CLuckyMoneyDescByHbStatus")                           // F-1 唯一
    R.hbItem   = andStr("MicroMsg.ChattingItemAppMsgC2CFrom", "H", 2)            // F-3

    // ---- G 区 引用 ----
    R.quoteImpls = bridge.findClass { matcher { interfaces { add("Lq71/n;") } } } // G-2 ×44
        .mapNotNull { cd -> load(cd.descriptor) }
    // G-1 工厂方法（可选，仅诊断用）
    bridge.findMethod { matcher { name("J7"); returnType("q71.n") } }

    // ---- H 区 消息 ----
    R.msgInfo = bridge.findClass { matcher {
            usingStrings("MicroMsg.MsgInfo")
            searchInPackages(listOf("com.tencent.mm.storage"))
            methods { add { name("convertFrom"); paramTypes("android.database.Cursor") } }
        } }.firstOrNull()?.let { cd -> load(cd.descriptor) }                     // H-1

    // ---- 四级：未混淆类名兜底 ----
    R.anim = load("Lcom/tencent/mm/ui/base/AnimImageView;")
    R.neat = load("Lcom/tencent/mm/ui/widget/MMNeat7extView;")
    R.msgQuote = load("Lcom/tencent/mm/plugin/msgquote/model/MsgQuoteItem;")

    // ---- 三级：MVVM 子类 ----
    R.voiceFromMvvm = bridge.findClass { matcher { superClass(R.voiceItemFrom?.name?.let { n ->
        "L" + n.replace('.', '/') + ";" } ?: "") } }.firstOrNull()?.let { load(it.descriptor) }
    R.voiceToMvvm   = bridge.findClass { matcher { superClass(R.voiceItemTo?.name?.let { n ->
        "L" + n.replace('.', '/') + ";" } ?: "") } }.firstOrNull()?.let { load(it.descriptor) }
}
```

---
---

# ━━━ L 区 · 实测命中记录（复现用）━━━

```text
命令模板：find_class(using_strings=["<字符串>"], limit=10) / find_method(...) / find_field(...)

唯一命中（9 条）
  "_onBindViewHolder["                                   → 1   adapter.k
  "fixShowTime: "                                        → 1   adapter.k
  "onStateBtnClick voice msg(%s) re-download!"           → 1   viewitems.bq
  "[voice interrupt] set continue play visible "         → 1   viewitems.mq
  "[isOpenNeatTextView]"                                 → 1   viewitems.to
  "chatbg"                                               → 1   pluginsdk.ui.i0
  "MicroMsg.ChatBgAttr"                                  → 1   pluginsdk.ui.i0
  ".chatbg.$voice_second_show_background"                → 1   pluginsdk.ui.i0
  "getC2CLuckyMoneyDescByHbStatus"                        → 1   chatting.z1
  "MicroMsg.ItemFactoryNew" / "initChattingItemConfig"    → 1 / 1  viewitems.kt
  findField(name="timeTV")                               → 1   viewitems.h0.timeTV
  findMethod(name="J7", returnType="q71.n")              → 1   oo.a0.J7

需过滤（实测命中数）
  "MicroMsg.ChattingDataAdapterV3"                        → 9
  "ChattingItemVoice$ChattingItemVoiceTo"                 → 2
  "ChattingItemVoice$VoiceItemHolder"                     → 2
  "chat_sys_msg_del_btn"                                  → 3
  "com/tencent/mm/ui/chatting/viewitems/ChattingItemSysMsgTemplate" → 2
  "MicroMsg.SysMsgTemplateImp"                            → 7
  "voice_continue_play_info"                              → 2
  "MicroMsg.AutoPlay"                                     → 10
  "MicroMsg.VoiceLogic"                                   → 6
  "MicroMsg.C2CAppMsgUtil"                                → 3
  "MicroMsg.ChattingItemTextFrom"                         → 8
  "MicroMsg.ChattingItemText"                             → 15
  "MicroMsg.ChattingItemVoice"                            → 11
  "MicroMsg.MsgInfo"                                      → 66
  "MasterBufId"                                           → 5
  "voicemd5" / "voicemsg"                                 → 2 / 7
  "MsgQuoteItem"                                          → 4（其中 model.MsgQuoteItem 类名未混淆）
  方法名 gj + paramCount 5（无 usingStrings）              → 272
  "MicroMsg.ChattingItem"                                 → 267
  "MicroMm.VoiceLogic"（错拼）                             → 9999+

继承/接口/字段
  super_class=viewitems.bq → 1(tr)     super_class=viewitems.iq → 1(ur)
  super_class=viewitems.mq → 0         super_class=viewitems.b0 → 122
  super_class=viewitems.h0 → 80        interfaces=[pluginsdk.ui.tools.t3] → 4
  interfaces=[q71.n] → 44              find_class_usage(pluginsdk.ui.i0) → 2
```

---

## 使用建议（整理用）
1. **先只接 9 条唯一锚点**即可覆盖：适配器、语音 Item+Holder、文本 Holder、ChatBgAttr、红包计算、ItemFactory、timeTV 字段、引用工厂 —— 模块就能跑通主流程。
2. 二级命中全部用「字符串 + 方法签名 AND」，**不要**用「字符串 + 包名」（包名也会变）。
3. 每次微信升级后，重跑 L 区的命令比对命中数：命中数变化=需要重新配过滤规则；命中 0=字符串被改，换同区备选串。
4. 所有 `Class/Method/Field` 解析结果按 `versionCode:versionName` 缓存，版本变化即失效重解析。

---
---

# ━━━ M 区 · 资源替换专用锚点（完整替换方案 R1/R2 用）━━━

> 完整替换不依赖"贴皮"，而是拦 `Resources#getDrawable`。本区锚点用于**定位微信的资源助手类**与**动态识别气泡 resId**。

## 【M-1】★一级·唯一（资源助手类）
```text
字符串   : "MicroMsg.ResourceHelper"                      实测命中：1
命中     : ke5.a
用途     : 微信统一资源助手；i(Context,int) → Resources.getDrawable(int)（单参重载）
          a(Context,float)=dp2px / b(Context,int)=dip / f(Context,int)=dimen / d(Context,int)=color / r(Context,int)=string
权威证据 : ke5.a.i smali 第 29 行
          invoke-virtual {p0,p1}, Landroid/content/res/Resources;->getDrawable(I)Landroid/graphics/drawable/Drawable;
DexKit   : findClass { matcher { usingStrings("MicroMsg.ResourceHelper") } }
```

## 【M-2】二级（compound drawable 助手）
```text
类       : com.tencent.mm.ui.el
方法     : d(Context,int) → context.obtainStyledAttributes(new int[]{id}).getDrawable(0)
用途     : 证明还存在「双参 getDrawable」路径 → R1 必须同时 hook 两个重载
DexKit   : findMethod { matcher { name("d"); paramCount(2); returnType("android.graphics.drawable.Drawable") } }
          （或直接用未混淆类名 XposedHelpers.findClass("com.tencent.mm.ui.el", cl)）
```

## 【M-3】框架 Hook 点（不需要 DexKit，但必须挂）
```text
android.content.res.Resources#getDrawable(int)            单参 → 覆盖 M-1(ke5.a.i)、代码直接调
android.content.res.Resources#getDrawable(int, Theme)     双参 → 覆盖 M-2(el.d)、View#setBackgroundResource、LayoutInflater
android.view.View#setBackgroundResource(int)              用于「动态收集气泡 resId」
android.view.View#setBackgroundDrawable(Drawable)         R2 兜底（ConstantState 缓存绕过 R1 时）
实现     : XposedBridge.hookAllMethods(resCls, "getDrawable", cb)，cb 内统一取 args[0] 作为 id
```

## 【M-4】气泡 resId 的动态识别（不写死，版本自适应）
```text
步骤 1  hook View#setBackgroundResource(int)
         若该 View 通过「三重校验」判定为气泡 View（Injectable.ok）→ 把 args[0] 加入 bubbleIds
步骤 2  同时从该 View 反推 (from, voice)：
         itemView = 向上第一个父级为 RecyclerView/ListView 的 View
         voice   = itemView.tag 的类名 == R.voiceHolder（viewitems.mq）
         from    = itemView.tag → 字段 "d"（mq.d 热区）→ getTag() = ap(ps 子类) → c() → MsgInfo → z0()==0
步骤 3  首次见到该 id → 立即替换并 param.result = Unit（跳过微信原设置，避免首帧闪原生）
步骤 4  之后所有 getDrawable(该 id) → 返回自定义 drawable（移植 orig 的 padding 与 bounds）
```

## 【M-5】本版本已知气泡 resId（仅供首启预置/校验，禁止作为唯一依据）
```text
文本  收 2131231925 / 发 2131232060 / 高亮 收 2131231841 / 发 2131231895
语音  收 2131231925 / 发 2131232060 / 未播放 收 2131231940 / 发 2131232066
秒数  2131232057（非气泡，勿替换）
红包  12 个：2131231684 / 1689 / 1695 / 1697 / 1702 / 1708 + 2131230737 / 0744 / 0745 / 0746 / 0753 / 0754
```

---
---

# ━━━ N 区 · 分区速查（一张表）━━━

| 区 | 功能 | 首选锚点 | 备选 |
|---|---|---|---|
| A | 列表适配器 | `_onBindViewHolder[`（唯一） | `fixShowTime: `、Tag（9 命中需过滤） |
| B | 语音气泡 | `[voice interrupt] set continue play visible `（唯一） | `ChattingItemVoice$VoiceItemHolder`(2)、`onStateBtnClick voice msg(%s) re-download!`(唯一，From) |
| C | 文本气泡 | `[isOpenNeatTextView]`（唯一） | Tag `MicroMsg.ChattingItemTextFrom`(8，需 AND `d` 4 参) |
| D | 系统提示/时间线 | `chat_sys_msg_del_btn`(3，需 AND `H` 2 参) | `com/.../ChattingItemSysMsgTemplate`(2)、`findField("timeTV")`(唯一) |
| E | 文字颜色 | `chatbg` / `MicroMsg.ChatBgAttr`（均唯一） | `.chatbg.$voice_second_show_background`（唯一） |
| F | 红包/转账 | `getC2CLuckyMoneyDescByHbStatus`（唯一） | Tag `MicroMsg.C2CAppMsgUtil`(3) |
| G | 引用消息 | `interfaces=["q71.n"]`（44） | `findMethod("J7", q71.n)`（唯一）、类名 `…model.MsgQuoteItem` |
| H | 消息数据 | `MicroMsg.MsgInfo`(66，需 AND storage 包 + convertFrom) | 字段路径 `holder.i.d.b` |
| M | 资源替换 | `MicroMsg.ResourceHelper`（唯一）→ ke5.a | `com.tencent.mm.ui.el`（类名未混淆） |

---
---

# ━━━ M 区补充 · 权威复核新增（Resources 子类 / 缓存 / 重载）━━━

## 【M-6】Resources 子类清单（`find_class(super_class="android.content.res.Resources")` 实测 8 个）
```text
androidx.appcompat.widget.f3 / p1                 AppCompat 包装（不 override getDrawable）
com.tencent.mm.compatible.loader.PluginResourceLoader   插件资源：loadDrawable(TypedValue,int)，聊天主路径不走
com.tencent.mm.plugin.appbrand.widget.a          小程序控件资源
com.tencent.xweb.w2                              XWeb 内核资源
ef.f1                                            仅 override getConfiguration
le5.a                                            完整委托包装（override 40+ 方法，【无 getDrawable】）
le5.j                                            多语言/密度 Resources（override getDrawableForDensity，【无 getDrawable】）
```

## 【M-7】hook 有效性逐类核查（本轮实测）
```text
find_method(in_class="le5.a", "getDrawable")            → 0 命中  → 未 override → R1 拦得到
find_method(in_class="ef.f1", "getDrawable")            → 0 命中  → 同上
le5.j 方法表                                            → 只有 getDrawableForDensity(II)/(II,Theme)
                                                          → getDrawable 未 override，但需补挂 getDrawableForDensity
ke5.a 字段表                                            → 仅 a:SparseIntArray / b:F / c:Z / d:Z
                                                          → 【无 Drawable 缓存】→ ke5.a.i() 每次都真实调 getDrawable
```

## 【M-8】R1 最终 hook 清单（4 个重载）
```text
Resources#getDrawable(int)                    ← ke5.a.i()、代码直接调
Resources#getDrawable(int, Theme)             ← el.d()、View#setBackgroundResource、LayoutInflater
Resources#getDrawableForDensity(int,int)      ← le5.j 可能走（加固）
Resources#getDrawableForDensity(int,int,Theme)
实现：listOf("getDrawable","getDrawableForDensity").forEach { hookAllMethods(res, it, cb) }
```

## 【M-9】`AnimImageView.b()` type=0 分支 smali（播放语音时，权威）
```smali
:cond_78
iget-boolean v0, p0, Lcom/tencent/mm/ui/base/AnimImageView->e:Z
if-eqz v0, :cond_8b
    getContext(); const v1, 0x7f0804b5                                  # 2131231925 收
    ke5/a->i(Context,I) → Drawable
    View->setBackgroundDrawable(Drawable)                               # ★不是 setBackgroundResource
    goto :goto_99
:cond_8b
    getContext(); const v1, 0x7f08053c                                  # 2131232060 发
    ke5/a->i(Context,I) → Drawable
    View->setBackgroundDrawable(Drawable)
:goto_99
    setAnimation(this.g); this.g.startNow()                             # AlphaAnimation
```
→ 证明：只拦 `setBackgroundResource` 会漏；R1（getDrawable）+ R2（setBackgroundDrawable）都必需。

## 【M-10】`ps#c()` 与 `ap` 继承（sideOf 判定的权威依据）
```smali
# ps.c()
.method public c()Lcom/tencent/mm/storage/e9;
    iget-object v0, p0, Lcom/tencent/mm/ui/chatting/viewitems/ps;->a:Lam5/d;
    iget-object v0, v0, Lam5/d;->d:Lhn5/a;
    iget-object v0, v0, Lhn5/a;->b:Lcom/tencent/mm/storage/e9;
    return-object v0
.end method
# class_hierarchy(ap)：ap extends ps   → mq.d 热区上的 ap Tag 可直接当 ps 用
```
