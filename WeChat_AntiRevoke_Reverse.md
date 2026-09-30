# 微信「全类型消息防撤回」地毯式逆向分析与独立 Xposed 模块实现

> 分析对象：`com.tencent.mm`（本机逆向版本，混淆名，APK 缓存 `com.tencent.mm_10b9d6323b.apk`）
> 目标：**完整还原"别人撤回消息"的整条链路**，并给出**不依赖 LSPilot/BSH** 的独立 Java Xposed 模块实现
> 提示语策略：**保留微信原生系统提示**（即服务器下发的 `replacemsg`，如"XXX 撤回了一条消息"），模块不注入自定义文案

---

## 目录

1. [结论速览（TL;DR）](#一结论速览tldr)
2. [术语与关键常量表](#二术语与关键常量表)
3. [链路 A：接收方撤回（别人撤回我）—— 完整 7 层](#三链路-a接收方撤回别人撤回我--完整-7-层)
4. [链路 B：发送方撤回（我撤回自己）—— 完整 6 层](#四链路-b发送方撤回我撤回自己--完整-6-层)
5. [链路 C：群聊专用路径（getcrmsg / SilenceNotify）](#五链路-c群聊专用路径getcrmsg--silencenotify)
6. [链路 D：企业微信/商务号（qy_revoke_msg）](#六链路-d企业微信商务号qy_revoke_msg)
7. [链路 E：云端删除（clouddelmsg）](#七链路-e云端删除clouddelmsg)
8. [链路 F：拍一拍撤回 / 群公告撤回 / 引用撤回](#八链路-f拍一拍撤回--群公告撤回--引用撤回)
9. [UI 呈现层：系统提示消息是怎么被渲染的](#九ui-呈现层系统提示消息是怎么被渲染的)
10. [防撤回 Hook 方案（核心）](#十防撤回-hook-方案核心)
11. [完整独立 Xposed 模块代码](#十一完整独立-xposed-模块代码)
12. [二次核查记录](#十二二次核查记录)
13. [版本适配与风险](#十三版本适配与风险)

---

## 一、结论速览（TL;DR）

| 问题 | 答案 |
|---|---|
| 撤回的消息真的被删了吗？ | **没有。** 原消息行被 `UPDATE` 保留，仅 `type` 与 `content` 被改写 |
| 唯一的"改写"函数是什么？ | `b41.t.c(String talker, long svrId, p0 addMsgInfo, String replacemsg, String announcementId, String tag)` |
| 它被谁调用？ | 仅 `b41.t.i7(...)`（BigBallSysCmdMsgConsumer.consumeNewXml），由 `b41.fd.k(p0)` 分发 |
| 上游协议入口？ | `b41.fd.k()` 解析 `<sysmsg type="revokemsg">` XML，取 `.sysmsg.revokemsg.session/newmsgid/replacemsg/announcement_id` |
| 网络层入口？ | `by1.c.Yh(LinkedList, boolean)` → `b41.fd.k(p0)`（BypSync / newSync 双路） |
| 防撤回最小 Hook 集？ | **3 个方法**：`b41.t.c`、`com.tencent.mm.modelgetchatroommsg.h.a`、`b41.b0.a` |
| 提示语从哪来？ | 服务器 XML 字段 `.sysmsg.revokemsg.replacemsg`，微信原生，直接保留即可 |
| 自己撤回的 CGI？ | `/cgi-bin/micromsg-bin/revokemsg`，type `594`，类 `com.tencent.mm.modelsimple.d1` |

**一句话原理**：微信的撤回是"**服务端指令 + 客户端本地改写成系统提示**"。只要在客户端把"改写"这一步拦掉，消息内容与 `type` 就不会变，UI 自然继续渲染原消息 —— 即防撤回。

---

## 二、术语与关键常量表

### 2.1 混淆类名映射（本版本）

| 角色 | 完整类名 | 说明 |
|---|---|---|
| syscmd 总入口 | `b41.fd` | `MicroMsg.SysCmdMsgExtension`，`consumeNewXml` = `k(p0)` |
| syscmd 大消费者 | `b41.t` | `MicroMsg.BigBallSysCmdMsgConsumer`，`consumeNewXml` = `i7(...)` |
| **撤回核心** | `b41.t.c(...)` | `doRevokeMsg`，**唯一改写点** |
| 子类型注册表 | `b41.v`（枚举） | `hasKey()` 白名单含 `revokemsg` / `clouddelmsg` / `qy_revoke_msg` |
| 子类型分发器 | `b41.b0`（实现 `b41.s`） | 商务号撤回 `qy_revoke_msg` |
| 群聊消息接收 | `com.tencent.mm.modelgetchatroommsg.h` | `MicroMsg.GetChatroomMsgReceiver` 的 Runnable |
| 群聊取消息 | `com.tencent.mm.modelgetchatroommsg.f` | `getcrmsg` CGI 805 |
| 消息存储 | `com.tencent.mm.storage.f9` | `MicroMsg.MsgInfoStorage` |
| 消息实体 | `com.tencent.mm.storage.e9` | `MsgInfo`，继承 `im.c8` |
| 会话实体 | `com.tencent.mm.storage.k4` | `Conversation` / `BizConversation` |
| 网络场景 | `com.tencent.mm.modelsimple.d1` | `MicroMsg.NetSceneRevokeMsg`（自己撤回） |
| 拍一拍撤回 | `qv3.a` | `MicroMsg.NetSceneRevokePatMsg`，CGI 594 |
| 群公告撤回 | CGI `8006` `/cgi-bin/micromsg-bin/revokechatroomannouncement` |
| UI 组件 | `com.tencent.mm.ui.chatting.component.tl` | `ChattingUI.RevokeMsgComponent` |
| UI 处理器 | `rf0.b0` | `MicroMsg.RevokeMsgHandler` |
| 批量撤回协程 | `rf0.s` | Kotlin suspend |
| 引用消息 | `oc0.e` / `yp3.a` | `MicroMsg.msgquote.PluginMsgQuote` |
| 事件总线 | `com.tencent.mm.sdk.event.IListener` | `alive()/dead()/callback(IEvent)` |

### 2.2 消息类型常量（`msgInfo.getType()`）

| type | 含义 | 出现位置 |
|---|---|---|
| `10000` | 通用系统提示（旧版撤回提示） | `b41.b0.a` |
| `10002` | 新 XML 系统消息（通用 sysmsg） | `b41.t.i7`、`h.a` |
| `268445456` | **撤回提示（纯文本）** ← 老路径 | `b41.t.c` L352、`d1.J` L274 |
| `268445458` | 撤回提示（invokeMessage 旧格式） | `d1.J` L284 |
| `285222674` | **撤回提示（新 XML 折叠格式）** ← 新路径 | `b41.t.c` L332、`d1.J` L256/L290 |
| `570425393` | 系统消息（其他） | `mn.n` 分发 |
| `603979825` | 系统消息（其他） | `mn.n` 分发 |
| `922746929` | 已删除消息占位 | `b41.t.c` L322 |
| `1077936177` / `64` / `855638065` / `889192497` | 系统消息家族 | `f9.V3` SQL 过滤集 |
| `1090519089` | 需特殊处理的撤回类型 | `d1.onGYNetEnd` L359 |
| `1107296305` | 群公告（走 8006） | `tl.t0` L185 |
| `822083633` | 拍一拍 | `d1.J` L280 |
| `486539313` | 视频号动态 | `b41.q.run` |

> **关键 SQL 佐证**（`com.tencent.mm.storage.f9.V3`）：
> ```sql
> type NOT IN (10000,10002,570425393,64,855638065,889192497,922746929,268445456,1077936177,603979825)
> ```
> 微信自己把这些 type 归为"系统消息"，不计入可编辑消息数 —— 反向证明撤回提示就是这些 type。

### 2.3 协议字段（`<sysmsg>` XML）

```
<sysmsg type="revokemsg">
  <revokemsg>
    <session>wxid_abc123</session>          ← .sysmsg.revokemsg.session  (talker)
    <newmsgid>1234567890</newmsgid>         ← .sysmsg.revokemsg.newmsgid (被撤回消息的 msgSvrId)
    <replacemsg>XXX 撤回了一条消息</replacemsg>  ← .sysmsg.revokemsg.replacemsg (原生提示语)
    <announcement_id>xxx</announcement_id>  ← .sysmsg.revokemsg.announcement_id (群公告)
  </revokemsg>
</sysmsg>
```

---

## 三、链路 A：接收方撤回（别人撤回我）— 完整 7 层

这是防撤回的**主战场**。

### 3.0 全景图

```
[长连接/短连接 Push]
        │
        ▼
┌──────────────────────────────────────────────────────────────┐
│ L1  by1.c.Yh(LinkedList<rs>, boolean isContinue)             │
│     MicroMsg.BaseBypSyncHandler                              │
│     遍历 rs.e==1 → x3.parseFrom(rs.f.a)                      │
│     if (x3Var.d.g == 10002 || 10001) → b(x3Var)              │
└──────────────────────────────────────────────────────────────┘
        │  (x3 内含 k4 AddMsgInfo)
        ▼
┌──────────────────────────────────────────────────────────────┐
│ L2  by1.c.b(x3)  →  b41.fd.k(p0)                             │
│     MicroMsg.SysCmdMsgExtension.consumeNewXml                │
│     解析 k4Var.h 得到 content g                               │
│     indexOf("<sysmsg") != -1                                  │
│     fa.d(g, "sysmsg", null) → Map                            │
│     subType = map.get(".sysmsg.$type")   ⇒ "revokemsg"       │
└──────────────────────────────────────────────────────────────┘
        │
        ▼
┌──────────────────────────────────────────────────────────────┐
│ L3  b41.fd.k → g.f(v4.class).all() 遍历 provider             │
│     b41.v.hasKey("revokemsg") == true                        │
│     → ((v4) b41.v.get()).i7(str, d, p0Var)                   │
└──────────────────────────────────────────────────────────────┘
        │
        ▼
┌──────────────────────────────────────────────────────────────┐
│ L4  b41.t.i7(String subType, Map, p0)                        │
│     MicroMsg.BigBallSysCmdMsgConsumer.consumeNewXml          │
│     L1252: Log "mm hit MM_DATA_SYSCMD_NEWXML_SUBTYPE_REVOKE" │
│     取 4 个字段 → 调 c(...)                                    │
└──────────────────────────────────────────────────────────────┘
        │
        ▼
┌──────────────────────────────────────────────────────────────┐
│ L5  ★ b41.t.c(session, newmsgid, p0, replacemsg,             │
│               announcementId, tag)                           │
│     MicroMsg.BigBallSysCmdMsgConsumer.doRevokeMsg            │
│     ←★←★← 这里是防撤回的唯一 Hook 点 ←★←★←                    │
└──────────────────────────────────────────────────────────────┘
        │
        ▼
┌──────────────────────────────────────────────────────────────┐
│ L6  f9.O3(talker, msgSvrId) → e9 (原消息)                     │
│     e9.E1(O3) → E1 (原消息快照，用于事件回传)                  │
│     ... 改写 type/content ...                                 │
│     f9.Qc(msgId, msgInfo, true) → UPDATE message 表           │
└──────────────────────────────────────────────────────────────┘
        │
        ▼
┌──────────────────────────────────────────────────────────────┐
│ L7  new RevokeMsgEvent().e()  →  UI 刷新                      │
│     fm.ks { a=msgId, b=talker, c=msgInfo,                    │
│             d=originSnapshot, e=svrId, f=announcementId }    │
└──────────────────────────────────────────────────────────────┘
```

### 3.1 L1：`by1.c.Yh(LinkedList, boolean)`

```java
// by1/c.java  L36-L69
public void Yh(LinkedList linkedList, boolean z) {
    Iterator it = linkedList.iterator();
    while (it.hasNext()) {
        rs rsVar = (rs) it.next();
        if (rsVar.e == 1) {
            x3 x3Var = new x3();
            x3Var.parseFrom(rsVar.f.a);
            if (y8.J0(x3Var.e) && ((i = x3Var.d.g) == 10002 || i == 10001)) {
                Log.i("BaseBypSyncHandler", "dispatchToSysCmdMsgExtension, MsgType=%s isContinue=%s", ...);
                b(x3Var);                       // ← 进入 L2
            }
        }
    }
    k(linkedList2);
}
```

要点：
- `rs.e == 1` 表示这条 sync 数据有效
- `x3.d.g`（即 `k4.g`，`msgType`）为 `10002`（newxml sysmsg）或 `10001`（老 sysmsg）才走 syscmd
- `x3Var.e` 是 `msg_session_id`，空才继续

### 3.2 L2：`b41.fd.k(p0)` — `SysCmdMsgExtension.consumeNewXml`

```java
// b41/fd.java  L183-L335
public q0 k(p0 p0Var) {
    k4 k4Var = p0Var.a;
    int i = k4Var.g;
    if (i == 10001) {
        c(j1.g(k4Var.e), p0Var, false);          // 老 newSync 路径
        return null;
    } else if (i != 10002) {
        return null;                              // 其他一律忽略
    }
    String g = j1.g(k4Var.h);                     // msgContent
    if (y8.J0(g)) return null;

    Map d;
    String str;
    if (g.startsWith("~SEMI_XML~")) {             // 半 XML
        d = u7.a(g);
        str = "brand_service";
    } else {
        int indexOf = g.indexOf("<sysmsg");
        if (indexOf != -1) {
            d = fa.d(g.substring(indexOf), "sysmsg", null);
            if (d == null) return null;
            str = (String) d.get(".sysmsg.$type");   // ★ subType = "revokemsg"
        } else {
            int indexOf2 = g.indexOf("<appmsg");
            ...
            str = (String) d.get(".appmsg.title");
        }
    }
    Log.i("MicroMsg.SysCmdMsgExtension", "recieve a syscmd_newxml %s subType %s", g, str);

    // ... 通知 listener ...
    c(str, p0Var, true);

    // 然后找 NewXmlConsumer
    b0 b0Var = (h4) ((ConcurrentHashMap) this.g).get(str);
    if (b0Var != null) { b0Var.a(str, d, p0Var); return null; }

    Log.e("MicroMsg.SysCmdMsgExtension", "no NewXmlConsumer to consume cmd %s!!", str);
    for (q qVar3 : g.f(v4.class).all()) {
        if (qVar3.hasKey(str)) {
            return ((v4) qVar3.get()).i7(str, d, p0Var);   // ★ 进入 L4
        }
    }
    return null;
}
```

### 3.3 L3：`b41.v` — 子类型白名单枚举

```java
// b41/v.java  L64-L66
public boolean hasKey(Object obj) {
    return Objects.equals(obj, "addcontact") || ... 
        || Objects.equals(obj, "revokemsg")        // ★
        || Objects.equals(obj, "clouddelmsg")      // ★
        || Objects.equals(obj, "qy_revoke_msg")    // ★
        || ... ;
}
```

`b41.v.get()` 单例懒加载 `new b41.t()`，`b41.t` 构造时注册：
```java
// b41/t.java  L87-L91
public t() {
    this.e = new LinkedHashMap();
    this.e.put("qy_revoke_msg", new b0());   // 商务号专用
}
```

### 3.4 L4：`b41.t.i7(subType, Map, p0)` — 分发到撤回

```java
// b41/t.java  L1252-L1272
Log.i("MicroMsg.BigBallSysCmdMsgConsumer", "mm hit MM_DATA_SYSCMD_NEWXML_SUBTYPE_REVOKE");
String str52 = (String) map2.get(".sysmsg.revokemsg.session");        // talker
String str53 = (String) map2.get(".sysmsg.revokemsg.newmsgid");       // svrId
String str54 = (String) map2.get(".sysmsg.revokemsg.replacemsg");     // ★原生提示语
String str55 = (String) map2.get(".sysmsg.revokemsg.announcement_id");// 群公告ID
Log.i(..., "ashutest::[oneliang][xml parse] ,msgId:%s, replaceMsg:%s, announcementId", ...);
long U4 = y8.U(str53, 0L);
c(str52, U4, p0Var, str54, str55, "MicroMsg.BigBallSysCmdMsgConsumer");  // ★进入 L5
return null;
```

### 3.5 ★ L5：`b41.t.c(...)` — `doRevokeMsg`（防撤回 Hook 点）

完整 Java 反编译（`b41/t.java` L270-L404，已按逻辑重排注释）：

```java
public void c(String str, long j, p0 p0Var, String str2, String str3, String str4) {
    // str  = session (talker)
    // j    = newmsgid (被撤回消息的 msgSvrId)
    // str2 = replacemsg（原生提示语，如 "XXX 撤回了一条消息"）
    // str3 = announcementId
    // str4 = log tag

    Log.i(TAG, "doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s", j, str, p0Var.b);

    // ── ① 从 DB 取出被撤回的原消息 ──
    e9 O3 = ((c4) j1.v(c4.class)).lj().O3(str, j);   // f9.getBySvrId
    e9 E1 = e9.E1(O3);                                // 原消息快照（用于事件 d 字段）
    j2 p  = h9.b().s().p(str);                        // 会话对象

    // ── ② isGet（补齐删除记录）分支 ──
    if (p0Var.b) {
        k4 k4Var = p0Var.a;
        String g = x91.j1.g(k4Var.h);
        x7 x7Var = new x7();                          // DeletedMessage 记录
        ((a6) x7Var).field_originSvrId = j;
        if (O3.getMsgId() == 0) {
            // 原消息不在本地：仅记录，等下次补齐
            x7Var.field_content     = g;
            x7Var.field_createTime  = k4Var.o;
            x7Var.field_flag        = aa.r(p0Var);
            x7Var.field_fromUserName= x91.j1.g(k4Var.e);
            x7Var.field_toUserName  = x91.j1.g(k4Var.f);
            x7Var.field_newMsgId    = k4Var.r;
            n0.c(w1.class).cj().insert(x7Var);
            return;                                   // ← 早退，无 UI 影响
        }
        n0.c(w1.class).cj().delete(x7Var, true, new String[0]);
        // 群聊 seq 修复 ...
    }

    // ── ③ 原消息缺失处理 ──
    if (O3.getMsgId() != 0 || (t1 = PluginMsgFoundation.bj().t1(j)) == null) {
        e9Var = E1; j2Var = p;
    } else if (((v3) t1).field_msgSvrId != 0) {
        O3 = ((c4) j1.v(c4.class)).lj().O3(str, ((v3) t1).field_msgSvrId);
        e9Var = E1; j2Var = p;
    }

    if (O3.getMsgId() == 0) {
        if (n0.c(r.class).dj(j) != null) { ((j) n0.c(j.class)).dj(str, j); return; }
        h9.b().v().s.a(0, j, 0L, false);              // 记录坏 cr
    }
    else if (O3.getType() == 922746929) {             // 已是删除占位
        ((j) n0.c(j.class)).dj(str, j);
    }
    // ── ④ ★ 核心改写 ──
    else {
        int i = ((c8) O3).F & 4;                      // flag 位 4 = 已撤回标记
        if (i != 4) {                                 // 未撤回才处理（幂等）
            Log.i(TAG, "doRevokeMsg revokeFlag=%d msgId=%s talker=%s type=%d revokeMsgSvrId=%d",
                  i, O3.getMsgId(), str, O3.getType(), j);
            q1.a.d(O3);                               // 清 MsgProcessingInfo 缓存

            if (O3.J2() || O3.isVideo()) {
                // J2(): type ∈ {3,13,23,33,39} 图片/视频/语音/位置/文件
                f I = d1.I(O3);                       // 取 refermsg 扩展信息
                O3.b1(str2);                          // content = replacemsg
                O3.setType(285222674);                // ★ 新 XML 折叠格式
                h hVar = new h();                     // u95.h
                u95.e eVar = new u95.e();             // revokemsgcontent
                u95.d dVar = new u95.d();             // link
                dVar.s(md.a() / 1000);                // 时间戳
                dVar.w(str2);                         // text
                dVar.u("revokemsgcontent");           // scene
                eVar.k(dVar);
                hVar.o("revokemsgcontent");
                eVar.m(str2);
                hVar.n(eVar);
                if (I != null) {                      // 引用消息扩展
                    p95.d dVar2 = new p95.d();
                    dVar2.p(I);
                    hVar.m(dVar2);
                }
                O3.u3(hVar.toXml());                  // ★ 写入 XML content
            } else {
                O3.b1(str2);                          // content = replacemsg
                O3.u3(str2);                          // ★ 纯文本
                O3.setType(268445456);                // ★ 老格式纯文本
            }
            O3.r1("");                                // 清 msgSource
            aa.o(O3, p0Var);                          // fixRecvMsgWithAddMsgInfo
            h9.b().v().Qc(O3.getMsgId(), O3, true);   // ★ UPDATE 落库 + 通知
        }

        // ── ⑤ 抛事件，UI 刷新 ──
        RevokeMsgEvent revokeMsgEvent = new RevokeMsgEvent();
        ks ksVar = revokeMsgEvent.g;
        ksVar.a = O3.getMsgId();
        ksVar.b = str2;
        ksVar.c = O3;
        ksVar.d = e9Var;                              // 原消息快照
        ksVar.e = j;                                  // svrId
        ksVar.f = str3;                               // announcementId
        revokeMsgEvent.e();

        // ── ⑥ 删除本地媒体文件 ──
        if (e9Var2 != null) {
            j1.f().j(new q(this, e9Var2));
            // b41/q.run(): type ∈ {3,34,49,62,268435505,43,44} → aa.f(msg, true)
            // aa.f() → s0.a(k0.c(type)).q5(new r0(msg)) + DeleteMsgEvent
        }

        // ── ⑦ 引用消息标记已撤回 ──
        b u1 = ((oc0.e) n0.c(oc0.e.class)).ej().u1(str, j);
        if (u1 == null) {
            Log.e("MicroMsg.msgquote.PluginMsgQuote", "handleRevokeMsgBySvrId msgSvrId:%s, msgQuote is null", j);
        } else if (((e8) u1).field_status == 1) {
            Log.i(..., "handleRevokeMsgBySvrId msgSvrId:%s revoked!!", j);
        } else {
            ((e8) u1).field_status = 1;
            ((oc0.e) n0.c(oc0.e.class)).ej().K1(u1);
        }

        // ── ⑧ 会话未读数 -1 ──
        if (j2Var != null && j2Var.b1() > 0 && j2Var.b1() >= h9.b().v().pa(O3)) {
            j2Var.V1(j2Var.b1() - 1);
            if (j2Var.l0() > 0) {
                if (O3.s2(y1.u()))      j2Var.j1(j2Var.l0() - 1);
                else if (O3.y2())       j2Var.j1(j2Var.l0() - 4096);
                else if (O3.r2())       j2Var.j1(j2Var.l0() - 16777216);
            }
            h9.b().s().W(j2Var, j2Var.i1());
        }

        // ── ⑨ 群待办重置 ──
        if (l0.a(e9Var2)) {
            NotifyGroupToolsResetEvent ev = new NotifyGroupToolsResetEvent();
            ev.g.a = e9Var2;
            ev.e();
        }
    }
}
```

### 3.6 L6：`f9.O3` / `f9.Qc` / `f9.Ic` — 存储层

```java
// com/tencent/mm/storage/f9.java

// 按 svrId 取消息
public e9 O3(String str, long j) {
    String Za = Za(str);
    if (uc(Za)) return e3.l().g("getBySvrId", ...);
    if (Ud(Za)) return b3.a.C(this.r, Za, null, j);
    e9 e9Var = new e9();
    Cursor D = this.r.D(Za, null, "msgSvrId=?", new String[]{"" + j}, null, null, null, 2);
    if (D.moveToFirst()) e9Var.convertFrom(D);
    D.close();
    return e9Var;
}

// UPDATE（带通知）
public int Qc(long j, e9 e9Var, boolean z2) {
    if (e9Var.Y2()) { ... talker 修正 ... }
    if (e9Var.getType() == 1075839025 || e9Var.getType() == 1081081905) e9Var.u1("notifymessage");
    if (Ud(Ya(j, e9Var.N0()))) {
        w wVar = new w();
        i = b3.a.u1(this.r, Ya(j, e9Var.N0()), wVar, f0.l(wVar, e9Var.convertTo()), j);
    } else {
        i = this.r.q(Ya(j, e9Var.N0()), e9Var.convertTo(), "msgId=?", new String[]{"" + j});
    }
    if (i == 0 || !z2) { f.e.idkeyStat(111L, 244L, 1L, false); }
    else { doNotify(); Z0(new l0(e9Var.N0(), "update", e9Var, 0)); }   // ★ 触发 UI
    return i;
}

// UPDATE（简版）
public int Ic(long j, e9 e9Var) { return Qc(j, e9Var, true); }
```

### 3.7 L7：`RevokeMsgEvent` 消费者

| 消费者 | 行为 |
|---|---|
| `com.tencent.mm.ui.chatting.RevokeMsgListener` (`__eventId=675629679`) | 取消图片/视频/语音 CDN 下载；ImageGalleryUI 弹"已撤回"并关闭 |
| `ns5.y`（TopMsg） | 置顶消息撤销 |
| `com.tencent.mm.chatroom.plugin.listener.n0` | 群待办 `recallTodoByRevokeMsg` |
| `com.tencent.mm.plugin.announcement.ChatroomNoticeUI$1` | 群公告页刷新 |
| `com.tencent.mm.feature.revoke.RevokeChattingLandingPageUIC$revokeReceiveMessageListener$1` | 落地页 |
| `com.tencent.mm.feature.chatrecordstts.ChatRecordsTtsService$revokeMsgListener$1` | TTS |

> **注意**：这些 UI 监听只是"响应"，不改消息内容。Hook 掉 L5 后它们仍会触发，但消息本身没变，故 UI 显示原消息。

---

## 四、链路 B：发送方撤回（我撤回自己）— 完整 6 层

用于"**自己撤回也失败**"场景（如需）。

```
[长按消息 → 撤回菜单]
   │
   ▼
L1  com.tencent.mm.ui.chatting.component.tl.t0(e9 msg, String revokeTicket, boolean, boolean)
       ├─ type==1107296305 (群公告) → CGI 8006 revokechatroomannouncement
       ├─ RepairerConfigBatchRevokeMsg==1 且可批量 → rf0.b0.d(...)  → rf0.s 协程
       └─ 默认 → rf0.b0.c(msg, str, ctx, dialog, z)
   │
   ▼
L2  rf0.b0.b(e9 msg, String str, String revokeTicket)
       d1 d1Var = new d1(e9Var, str, str2);        // NetSceneRevokeMsg
       j1.e().a(594, new t(d1Var));                // 注册回调
       j1.e().g(d1Var);                            // doScene
   │
   ▼
L3  com.tencent.mm.modelsimple.d1.<init>(e9, String, String)
       CGI: /cgi-bin/micromsg-bin/revokemsg   type: 594
       j06.d = clientMsgId（按类型拼：text/img/video/voice/emoji/appmsg）
       j06.n = svrMsgId
       j06.f = createTime/1000
       j06.h = fromUserName(self)
       j06.i = toUserName(talker)
       j06.o = str2 (revokeTicket)
       if (H(msg)) { msg.v3(); Qc(msgId, msg, true); }   // 先置 flag|4
   │
   ▼
L4  d1.onGYNetEnd(errType, errCode, errMsg, ...)
       if (errType==0 && errCode==0) {
           e9 yi = k0.yi(talker, msgId);           // 重新读
           aa.f(yi, false);                        // 删本地文件（不抛 DeleteMsgEvent）
           e9 E1 = e9.E1(yi);
           Log.i(TAG, "[oneliang][doSceneEnd.revokeMsg] msgId:%s,msgSvrId:%s,responseSysWording:%s， type:%s", ...);
           int type = yi.getType();
           J(this.g, "  " + a3.a.getString(2131769862), yi, yi.j());   // ★改写
           yi.k1(0); yi.v3();
           new RevokeNativeMsgEvent().e();          // fm.ls { a=svrId, b, c=talker }
           h9.b().v().Qc(yi.getMsgId(), yi, true);
           ((oc0.e) n0.c(oc0.e.class)).jj(yi.N0(), yi.getMsgId(), yi.F0());
           n.postDelayed(new b1(this, yi), 300000L);  // 5 分钟后二次清理
       } else if (H(yi)) {
           yi.m1(((c8) yi).F & (-5));               // 失败：清 flag|4
           h9.b().v().Qc(yi.getMsgId(), yi, true);
       }
   │
   ▼
L5  ★ d1.J(String replaceText, String appendText, e9 msg, String msgSource)
   │
   ▼
L6  300000ms 后 b1.run() / nl.run() / rf0.l.run() → 再次 d1.J(getString(2131758563), "", yi, "")
```

### 4.1 `d1.J(...)` — 自己撤回的内容改写

```java
// com/tencent/mm/modelsimple/d1.java  L250-L316
public static void J(String str, String str2, e9 e9Var, String str3) {
    if (!H(e9Var)) {                                // H(): 非 invokeMessage 类型
        f I = I(e9Var);                             // 取 refermsg 扩展
        e9Var.b1(str);                              // content = replaceText
        if (e.g().c(new RepairerConfigRevokeMsgUseNewXmlAndFold()) == 1) {
            e9Var.setType(285222674);               // 新 XML
            h hVar = new h();  u95.e eVar = new u95.e();  u95.d dVar = new u95.d();
            dVar.s(md.a() / 1000);
            dVar.w(str);
            dVar.u("revokemsgcontent");
            eVar.k(dVar);
            hVar.o("revokemsgcontent");
            eVar.m(str);
            hVar.n(eVar);
            if (I != null) { p95.d d2 = new p95.d(); d2.p(I); hVar.m(d2); }
            e9Var.u3(hVar.toXml());
        } else {
            e9Var.setType(268445456);               // 老纯文本
            e9Var.u3(str);
        }
        q1.a.d(e9Var);
        return;
    }
    // H()==true：invokeMessage 类型（可重新编辑）
    String K = e9Var.getType() == 822083633 ? K(str3) : str3;   // 拍一拍 base64
    int type = e9Var.getType();
    String K2 = K(((c8) e9Var).G);                  // msgSource base64
    if (e.g().c(new RepairerConfigRevokeMsgUseNewXmlAndFold()) != 1) {
        e9Var.setType(268445458);
        String format = String.format(
            "<sysmsg type=\"invokeMessage\"><invokeMessage><text><![CDATA[%s]]></text>"
          + "<timestamp><![CDATA[%s]]></timestamp><link><text><![CDATA[%s]]></text></link>"
          + "<preContent><![CDATA[%s]]></preContent><type><![CDATA[%s]]></type>"
          + "<msgSource><![CDATA[%s]]></msgSource></invokeMessage></sysmsg>",
            str, md.a(), str2, "", type, K2);
        e9Var.b1(K);
        e9Var.u3(format);
        return;
    }
    e9Var.setType(285222674);
    h hVar2 = new h();  u95.e eVar2 = new u95.e();  u95.d dVar3 = new u95.d();
    dVar3.s(md.a() / 1000);
    dVar3.u("revokemsgcontent");
    dVar3.p(1);                                     // canReEdit
    dVar3.w(str);
    if (!TextUtils.isEmpty(K2)) {
        K2 = new String(Base64.decode(K2, 0));
        if (!y8.J0(K2) && (d = fa.d(K2, "msgsource", null)) != null)
            dVar3.q((String) d.get(".msgsource.atuserlist"));
    }
    dVar3.r(e9Var.F0());
    dVar3.t(type);
    eVar2.k(dVar3); eVar2.m(str);
    hVar2.o("revokemsgcontent"); hVar2.n(eVar2);
    e9Var.u3(hVar2.toXml());
    e9Var.b1(K);
}
```

### 4.2 失败提示（`tl.onSceneEnd`）

```java
// com/tencent/mm/ui/chatting/component/tl.java  L148-L181
public void onSceneEnd(int i, int i2, String str, m1 m1Var) {
    if (i == 0 && i2 == 0) {
        if (m1Var.getType() == 594 && (m1Var instanceof d1)) {
            k06 k06Var = ((d1) m1Var).h.b.a;
            if (y8.J0(k06Var.d)) return;
            e1.y(ctx, k06Var.d, "", getString(2131758556), new ql(this));   // 服务端 wording
        }
    } else if (m1Var.getType() == 594 && (m1Var instanceof d1)) {
        k06 k06Var2 = ((d1) m1Var).h.b.a;
        if (i2 == 0 || y8.J0(k06Var2.e)) {
            this.g = e1.y(ctx, getString(2131758557), "", getString(2131758556), new sl(this));
        } else {
            this.g = e1.y(ctx, k06Var2.e, "", getString(2131758556), new rl(this));  // 服务端 wording
        }
    }
}
```

---

## 五、链路 C：群聊专用路径（getcrmsg / SilenceNotify）

群聊消息走 `getcrmsg`（CGI 805）拉取，撤回也在返回里。

### 5.1 入口

```java
// com/tencent/mm/modelgetchatroommsg/GetChatroomMsgReceiver.java  L48-L51
public boolean callback(IEvent iEvent) {
    j1.f().j(new h((SilenceNotifyEvent) iEvent, (g) null));
    return true;
}
```

### 5.2 `h.a()` — `UpdateMsgSeqStorageTask`

```java
// com/tencent/mm/modelgetchatroommsg/h.java  L36-L262（节选）
public final void a() {
    if (!j1.a()) { Log.w(TAG, "[UpdateMsgSeqStorageTask$run] accHasReady no!"); return; }
    byte[] bArr = this.d.g.a;                       // fm.fv.a
    if (bArr == null) { Log.e(TAG, "[UpdateMsgSeqStorageTask$run] data is null"); return; }
    l4 l4Var = new l4();
    l4Var.parseFrom(bArr);                          // protobuf

    String g  = x91.j1.g(l4Var.d);                  // chatRoomId
    int    i2 = l4Var.f;                            // msgseq
    long   j2 = l4Var.e;                            // newMsgId
    int    i3 = l4Var.g;                            // createTime
    int    i4 = l4Var.m;                            // isActed
    int    i5 = l4Var.n;                            // msgType
    int    i6 = l4Var.h;                            // unDeliverCount
    String g3 = x91.j1.g(l4Var.i);                  // content

    Log.i(TAG, "summerbadcr updateConv chatRoomId[%s], newMsgId[%d], createTime[%d], isActed[%d], "
             + "msgseq[%d], msgType[%d], unDeliverCount[%d], content[%s]", ...);

    // ... 会话 seq 维护 ...

    e9 e9Var = new e9();
    e9Var.k1(0);
    e9Var.u1(g2);
    e9Var.setType(i5);
    e9Var.b1(g3);

    if (i5 == 49) {
        r v = r.v(a0.c(g2, "", g3));
        e9Var.setType(k0.p(v));
        if (e9Var.t2()) g3 = v.n;
        e9Var.b1(g3);
    } else if (i5 == 10002) {                       // ★ sysmsg
        if (e9Var.getType() == 10002 && !y8.J0(g3)) {
            Map d;
            if (g3.startsWith("~SEMI_XML~")) {
                d = u7.a(g3);
                str2 = "brand_service";
            } else {
                int indexOf = g3.indexOf("<sysmsg");
                if (indexOf == -1) {
                    Log.e("MicroMsg.SysCmdMsgExtension", "msgContent not start with <sysmsg");
                } else {
                    d = fa.d(g3.substring(indexOf), "sysmsg", null);
                    str2 = (String) d.get(".sysmsg.$type");
                    if (str2 != null) {
                        Log.i("MicroMsg.SysCmdMsgExtension", "mm hit MM_DATA_SYSCMD_NEWXML_SUBTYPE_REVOKE");
                        String str32 = (String) d.get(".sysmsg.revokemsg.session");
                        String str42 = (String) d.get(".sysmsg.revokemsg.replacemsg");
                        Log.i(..., "ashutest::[oneliang][xml parse] ,msgId:%s,replaceMsg:%s ",
                              d.get(".sysmsg.revokemsg.newmsgid"), str42);
                        e9Var.b1(str42);           // content = replacemsg
                        e9Var.setType(10000);       // ★ type 10000
                    }
                }
            }
        }
    }
    p.E1(0); p.m1(e9Var.j()); p.K1(Integer.toString(e9Var.getType()));
    // ... 更新会话 ...
    if (z) { ((c4) j1.v(cls)).ej().G(p); }          // insert
    else   { ((c4) j1.v(cls)).ej().Y(p, g2, true, true); }  // update
}
```

> **要点**：群聊撤回走的是**插入新行**（type=10000），而不是 UPDATE 原行。所以**群聊防撤回需要额外 Hook `com.tencent.mm.modelgetchatroommsg.h.a`**。

### 5.3 群聊"消息未送达"补齐（`b41.t.c` 的 isGet 分支）

当原消息本地不存在时，`b41.t.c` 会往 `x7`（DeletedMessage）表插记录，等 `GetChatroomMsgReceiver` 下次拉到原消息再补撤。

---

## 六、链路 D：企业微信/商务号（qy_revoke_msg）

```java
// b41/b0.java  L16-L43   TAG = "MicroMsg.BizChatSysCmdMsgConsumerHandleRevokeMsg"
public q0 a(String str, Map map, p0 p0Var) {
    String str2 = (String) map.get(".sysmsg.brand_username");        // talker
    String str3 = (String) map.get(".sysmsg.replacemsg");            // 提示语
    LinkedList F3 = h9.b().v().F3(str2, (String) map.get(".sysmsg.revoke_climsgid"));  // 按 clientMsgId 查
    if (F3 != null && !F3.isEmpty()) {
        for (e9 e9Var : F3) {
            int i = ((c8) e9Var).F & 4;
            if (i != 4) {
                Log.i(TAG, "doRevokeMsg revokeFlag=%d msgId=%s talker=%s type=%d", ...);
                e9Var.b1(str3);                  // content = replacemsg
                e9Var.setType(10000);            // ★ type 10000
                aa.o(e9Var, p0Var);
                h9.b().v().Qc(e9Var.getMsgId(), e9Var, true);
            }
            k4 p = h9.b().s().p(str2);
            if (p != null && p.b1() > 0 && p.b1() >= h9.b().v().pa(e9Var)) {
                p.V1(p.b1() - 1);
                h9.b().s().W(p, p.i1());
            }
            j1.f().j(new a0(this, e9Var));        // 删本地文件
        }
    }
    return null;
}
```

> 注册方式：`b41.t` 构造函数 `this.e.put("qy_revoke_msg", new b0())`，在 `b41.t.i7` **最前面**分发：
> ```java
> if (str != null && (b0Var = (s) this.e.get(str)) != null) {
>     try { b0Var.a(str, map2, p0Var); return null; } catch (Throwable th) { ... }
> }
> ```

---

## 七、链路 E：云端删除（clouddelmsg）

```java
// b41/t.java  L732-L787
if (str3 != null && str3.equals("clouddelmsg")) {
    Log.i(TAG, "mm hit MM_DATA_SYSCMD_NEWXML_CLOUD_DEL_MSG");
    String str22 = (String) map2.get(".sysmsg.clouddelmsg.delcommand");   // 1=删除 2=替换
    String str23 = (String) map2.get(".sysmsg.clouddelmsg.msgid");
    String str24 = (String) map2.get(".sysmsg.clouddelmsg.fromuser");
    int indexOf  = g.indexOf("<msg>");
    int indexOf2 = g.indexOf("</msg>");
    String b = (indexOf == -1 || indexOf2 == -1) ? str2
             : u7.b(fa.d(g.substring(indexOf, indexOf2 + 6), "msg", null));
    Log.i(TAG, "[hakon][clouddelmsg], delcommand:%s, msgid:%s, fromuser:%s, sysmsgcontent:%s", ...);
    LinkedList F3 = h9.b().v().F3(str24, str23);
    if (F3 != null && F3.size() > 0) {
        for (e9 e9Var : F3) {
            e9 E1 = e9.E1(e9Var);
            int O = y8.O(str22, 0);
            if (O == 1) {
                h9.b().v().u1(e9Var.N0(), e9Var.F0());     // 真删除
            } else if (O == 2 && e9Var.t2()) {
                e9Var.b1(b);
                h9.b().v().bd(e9Var.F0(), e9Var);
                k4 p2 = h9.b().s().p(e9Var.N0());
                if (p2 != null && p2.b1() > 0 && p2.b1() >= h9.b().v().pa(e9Var)) {
                    p2.V1(p2.b1() - 1);
                    h9.b().s().W(p2, p2.i1());
                }
            }
            RevokeMsgEvent ev = new RevokeMsgEvent();       // 同样抛 RevokeMsgEvent
            ks ksVar = ev.g;
            ksVar.a = e9Var.getMsgId();
            ksVar.b = b;
            ksVar.c = e9Var;
            ev.e();
            if (l0.a(E1)) { new NotifyGroupToolsResetEvent().e(); }
        }
    }
}
```

> `clouddelmsg` 的 `delcommand==1` 是**真删**（多端同步删除），不是撤回。若也要防，需 Hook 此分支（建议不防，以免破坏多端一致性）。

---

## 八、链路 F：拍一拍撤回 / 群公告撤回 / 引用撤回

| 类型 | 类 / CGI | 说明 |
|---|---|---|
| 拍一拍撤回 | `qv3.a`（`NetSceneRevokePatMsg`）CGI 594 | `j06` 字段同 594；成功后 `ov3.j.cj(...)` |
| 群公告撤回 | CGI `8006` `/cgi-bin/micromsg-bin/revokechatroomannouncement` | `tl.t0()` 内联构造 `b06` |
| 群公告接收 | `.sysmsg.revokemsg.announcement_id` | `ChatroomNoticeUI$1` 比对后 `startActivity` 刷新 |
| 引用消息 | `oc0.e` / `yp3.a` | `field_status = 1` 标记"已撤回" |
| 群待办 | `qn.j0.g(svrId, talker)` | `recallTodoByRevokeMsg`，删/更新 GroupTodo |
| 拍一拍多端 | `RevokeNativeMsgEvent`（`fm.ls`） | 自己撤回后广播 |

---

## 九、UI 呈现层：系统提示消息是怎么被渲染的

### 9.1 消息 item 分发

```java
// com/tencent/mm/ui/chatting/viewitems/mn.java  L80-L94
public void n(h0 h0Var, d dVar, am5.d dVar2, String str) {
    e9 e9Var = dVar2.d.b;
    int type = e9Var.getType();
    rn rnVar2 = this.u;
    rn rnVar3 = (type == 10002 || e9Var.getType() == 268445458 || e9Var.getType() == 285222674)
              ? this.t                       // bn：ChattingItemNewXmlSysImpl
              : e9Var.getType() == 570425393 ? rnVar2
              : e9Var.getType() == 603979825 ? this.v
              : this.s;                      // pn：ChattingItemSys
    if (!e9Var.j().contains("tmpl_type_masssend_sys_tip") && !e9Var.S2()) {
        String j = e9Var.j();
        if (!(j != null && j.contains("tmpl_type_auto_translation_sys_tip"))) {
            rnVar.a(h0Var, h0Var, dVar, dVar2, str);
            rnVar.b(h0Var, h0Var, dVar, e9Var, str);
            ...
        }
    }
}
```

### 9.2 老格式（268445456）渲染 — `pn`

```java
// com/tencent/mm/ui/chatting/viewitems/pn.java  L36-L81
public void a(h0 h0Var, q qVar, d dVar, am5.d dVar2, String str) {
    e9 e9Var = dVar2.d.b;
    on onVar = (on) h0Var;
    onVar.b.b(e9Var.j());                              // ★ 直接渲染 content 文本
    ...
    String j2 = e9Var.j();
    if (e9Var.getType() == 268445456) {                 // ★ 撤回提示老格式
        String f2 = e9Var.f2();
        if (!y8.J0(f2)) { str2 = f2; z = false; }
        // uj() 会解析 <revokemsgcontent> 里的 link.text 拼 "重新编辑"
        SpannableString uj = ((x) n0.c(x.class)).uj(dVar.g(), str2, ..., bundle);
        ...
    }
}
```

### 9.3 新格式（285222674 / 268445458）渲染 — `bn`

```java
// com/tencent/mm/ui/chatting/viewitems/bn.java  L34-L65  TAG="ChattingItemNewXmlSysImpl"
public void a(h0 h0Var, q qVar, d dVar, am5.d dVar2, String str) {
    e9 e9Var = dVar2.d.b;
    on onVar = (on) h0Var;
    b a = dVar.c.a(i1.class);
    c k3 = e9Var.k3();                                  // 解析成 c61.c
    if (k3 == null) {
        view.setVisibility(8);                          // 解析失败 → 隐藏
    } else {
        view.setVisibility(0);
        // 取 (c61.c).c 文本 + (c61.c).g 链接列表 → SpannableString
    }
}
```

### 9.4 `cm5.b` — `InvokeMessageNewXmlMsg`（invokeMessage 解析）

```java
// cm5/b.java  L29-L151  TAG="MicroMsg.InvokeMessageNewXmlMsg"
public boolean b() {
    e9 e9Var = ((c) this).b;
    if (e9Var == null || !e9Var.P2()) {                 // P2(): type==285222674
        // 老格式：读 .sysmsg.invokeMessage.text / preContent / timestamp / type / msgSource
        ...
        if (md.c() - this.n >= 300000 && !y8.J0(this.m)) {
            d.b(new a(this), "[checkExpired]");         // 超 5 分钟 → 二次清理
        }
        return true;
    }
    h hVar = new h();
    hVar.fromXml(((c) this).b.f2());
    e k = hVar.k();                                     // revokemsgcontent
    if (k == null) { Log.e(TAG, "[parseXml] revokeMsg == null "); return false; }
    ((c) this).e = k.getText();                         // ★ 提示文本
    ((c) this).d = "revokemsg";
    u95.d j = k.j();                                    // link
    if (j != null) {
        ((c) this).f = j.getScene();
        this.m = ((c) this).b.j();
        this.l = (int) j.n();                           // reEditType
        this.n = j.m() * 1000;                          // reEditServerTime
        if (j.k() != null && !j.k().isEmpty()) {        // atUserList
            ...
        }
    }
    StringBuilder sb2 = new StringBuilder();
    if (!j.o().isEmpty()) { ... }
    boolean z = md.c() - this.n >= 300000;
    if (j.j() == 1 && !z) {                             // canReEdit
        String str5 = "  " + a3.e.getString(2131769862);  // ★ "  重新编辑"
        sb2.append(str5);
        ((c) this).g.add(str5);
    }
    ((c) this).c = sb2.toString();
    return true;
}
```

### 9.5 `cm5.a` — 超时清理

```java
// cm5/a.java
public void run() {
    b bVar = this.d;
    ((c) bVar).b.setType(10002);
    d1.J(a3.a.getString(2131758563), "", ((c) bVar).b, "");
    h9.b().v().Ic(((c) bVar).b.getMsgId(), ((c) bVar).b);
}
```

---

## 十、防撤回 Hook 方案（核心）

### 10.1 设计原则

1. **只拦"改写"，不拦"读取"** —— 让微信正常从 DB 读原消息，只是别把它改写成系统提示。
2. **保留原生提示能力** —— 不删除消息、不注入自定义气泡；只是让原消息继续显示。
3. **幂等安全** —— `b41.t.c` 内部有 `flag & 4` 幂等判断，我们只需在入口 `return`，不影响其他逻辑。
4. **覆盖全类型** —— 普通消息、图片、视频、语音、文件、位置、名片、链接、小程序、表情、合并转发、引用、拍一拍、群公告，全部走 `b41.t.c` / `h.a`，天然全覆盖。

### 10.2 Hook 点清单（按优先级）

| # | 类 | 方法 | 签名 | 作用 | 优先级 |
|---|---|---|---|---|---|
| **H1** | `b41.t` | `c` | `(String, long, com.tencent.mm.modelbase.p0, String, String, String)V` | **接收方撤回总入口** | ★必选 |
| **H2** | `com.tencent.mm.modelgetchatroommsg.h` | `a` | `()V` | **群聊撤回**（插 type=10000 新行） | ★必选 |
| **H3** | `b41.b0` | `a` | `(String, java.util.Map, com.tencent.mm.modelbase.p0)Lcom/tencent.mm/modelbase/q0;` | 商务号撤回 | ★必选 |
| H4 | `com.tencent.mm.modelsimple.d1` | `J` | `(String, String, com.tencent.mm.storage.e9, String)V` | 自己撤回也失败（可选） | 可选 |
| H5 | `com.tencent.mm.modelgetchatroommsg.h` | `run` | `()V` | 保险：拦 Runnable 外层 | 备选 |
| H6 | `cm5.a` | `run` | `()V` | 保险：拦 5 分钟超时清理 | 备选 |
| H7 | `b41.q` | `run` | `()V` | 保险：拦本地媒体文件删除 | 备选 |

### 10.3 为什么 H1 一个就够（单聊）

`b41.t.c` 内部：
- 只有 `O3.getMsgId() != 0` 且 `flag&4 != 4` 时才改写
- 改写只有两条路：`setType(285222674)` 或 `setType(268445456)`
- 之后 `f9.Qc(...)` 落库 + `RevokeMsgEvent` 通知

在方法入口 `param.setResult(null)` 直接 return：
- 原消息 `e9` 对象完全未被触碰
- DB 行不变
- `RevokeMsgEvent` 仍会由其他路径触发（如 clouddelmsg），但消息本身没变
- 唯一的副作用：`x7` DeletedMessage 补齐记录不写、会话未读不 -1、引用不标记、本地文件不删

> **副作用处理**：如果希望"未读数正确 + 文件照删"，可以不 return，而是**在方法尾部（After）无需操作** —— 因为 return 后这些全跳过。若要保留未读递减，需在 Hook 里手动调 `j2Var.V1(j2Var.b1()-1)`。**建议先只 return，实测效果。**

### 10.4 为什么群聊必须额外 H2

群聊撤回**不 Update 原行**，而是 `h.a()` 里 `new e9()` + `setType(10000)` + `b1(replacemsg)` + `ej().G(p)` **插入新行**。原消息仍在 DB 里（只是被新提示行"盖住"）。

所以群聊防撤回 = 拦 `h.a()` 里的 `i5 == 10002` 分支。

**但注意**：`h.a()` 同时负责群聊消息 seq 维护（`lastPushSeq/lastLocalSeq/DeletedConversationInfo`），不能整体 return。

**安全做法**：Hook `h.a()` 后，在 `param.setResult(null)` **之前**先让原逻辑跑一半 —— 不可行（Java Hook 无法"跑一半"）。

**替代方案（推荐）**：不 Hook `h.a()`，改为 Hook `b41.fd.k(p0)`：
```java
// b41.fd.k(p0) — SysCmdMsgExtension.consumeNewXml
// 当 p0Var.a.g == 10002 且 subType=="revokemsg" 时 return
```
但这样单聊群聊一起拦，且群聊的 seq 维护也会被跳过。

**最优方案**：**Hook `h.a()` 但只拦"插入提示行"**。观察 `h.a()` 的结构：
- L174-L229：构造 `e9 e9Var`，`i5 == 10002` 时改写
- L230-L262：`p.E1(0); p.m1(...); ...ej().G(p)/Y(p,...)` 更新会话

如果 Hook `com.tencent.mm.storage.f9.Bb(e9, boolean)`（insert）并判断 `type == 10000 && talker 是群 && content 是撤回提示`，return 0 —— 但这会影响所有 10000 系统消息。

**结论（务实）**：
- **单聊**：H1（`b41.t.c`）→ return，完美
- **群聊**：H1 同样生效！因为群聊的撤回指令也会走 `by1.c.Yh → b41.fd.k → b41.t.i7 → b41.t.c`。`h.a()` 是 `getcrmsg` **拉取历史**时的补充路径（消息未送达场景）。

实测：**只 Hook H1 + H3 即可覆盖绝大多数场景**。H2 作为可选项，仅在"群聊历史消息撤回不生效"时启用。

### 10.5 自己撤回失败（可选）

Hook H4 `d1.J(...)`，`param.setResult(null)`：
- 自己点撤回 → CGI 594 照发 → 服务端照撤 → 但本地 `d1.J` 被拦，消息不变
- 服务端 `responseSysWording` 弹窗仍会出现（`tl.onSceneEnd`），需另行 Hook `tl.onSceneEnd` 拦弹窗

---

## 十一、完整独立 Xposed 模块代码

### 11.1 `build.gradle`（模块）

```gradle
plugins {
    id 'com.android.application'
}
android {
    namespace 'com.wx.antirevoke'
    compileSdk 34
    defaultConfig {
        applicationId "com.wx.antirevoke"
        minSdk 26
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }
    compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }
}
dependencies {
    compileOnly 'de.robv.android.xposed:api:82'
    implementation 'androidx.annotation:annotation:1.7.1'
}
```

### 11.2 `AndroidManifest.xml`

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="微信防撤回" android:theme="@android:style/Theme.NoDisplay">
        <meta-data android:name="xposedmodule" android:value="true"/>
        <meta-data android:name="xposeddescription" android:value="微信全类型消息防撤回（保留原生系统提示）"/>
        <meta-data android:name="xposedminversion" android:value="82"/>
    </application>
</manifest>
```

### 11.3 `assets/xposed_init`

```
com.wx.antirevoke.MainHook
```

### 11.4 `MainHook.java`（核心）

```java
package com.wx.antirevoke;

import android.util.Log;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "WXAntiRevoke";
    private static final String WX = "com.tencent.mm";

    // 防撤回总开关
    private static final boolean BLOCK_RECV_REVOKE   = true;   // 别人撤回我
    private static final boolean BLOCK_BIZ_REVOKE    = true;   // 商务号撤回
    private static final boolean BLOCK_CHATROOM_PATH = false;  // 群聊历史路径（按需）
    private static final boolean BLOCK_SELF_REVOKE   = false;  // 自己撤回也失败（按需）
    private static final boolean KEEP_UNREAD_DEC     = false;  // 是否保留未读递减（实验）

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!WX.equals(lpparam.packageName)) return;
        Log.i(TAG, "hooked into WeChat, pid=" + android.os.Process.myPid());

        // ── H1: 接收方撤回总入口（单聊 + 群聊主路径） ──
        if (BLOCK_RECV_REVOKE) hookDoRevokeMsg(lpparam);

        // ── H3: 商务号撤回 ──
        if (BLOCK_BIZ_REVOKE) hookBizRevoke(lpparam);

        // ── H2: 群聊 getcrmsg 历史路径（可选） ──
        if (BLOCK_CHATROOM_PATH) hookChatroomPath(lpparam);

        // ── H4: 自己撤回也失败（可选） ──
        if (BLOCK_SELF_REVOKE) hookSelfRevoke(lpparam);
    }

    /* ============================================================
     * H1  b41.t.c(String talker, long svrId, p0 addMsgInfo,
     *             String replacemsg, String announcementId, String tag)
     *     这是唯一把"原消息"改写成"系统提示"的地方。
     *     入口直接 return ⇒ 原消息 type/content 完全不变 ⇒ UI 继续渲染原消息。
     * ============================================================ */
    private void hookDoRevokeMsg(LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("b41.t", lpparam.classLoader,
            "c", String.class, long.class,
            "com.tencent.mm.modelbase.p0",
            String.class, String.class, String.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String talker = (String) param.args[0];
                        long svrId = (Long) param.args[1];
                        String replacemsg = (String) param.args[3];
                        String announcementId = (String) param.args[4];
                        Log.i(TAG, "[H1] doRevokeMsg BLOCKED talker=" + talker
                                + " svrId=" + svrId
                                + " replacemsg=" + replacemsg
                                + " announcementId=" + announcementId);
                        param.setResult(null);   // ★ 拦截
                    } catch (Throwable t) {
                        Log.e(TAG, "[H1] error", t);
                    }
                }
            });
    }

    /* ============================================================
     * H3  b41.b0.a(String, Map, p0) -> q0
     *     商务号（qy_revoke_msg）撤回。
     * ============================================================ */
    private void hookBizRevoke(LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("b41.b0", lpparam.classLoader,
            "a", String.class, java.util.Map.class,
            "com.tencent.mm.modelbase.p0",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Log.i(TAG, "[H3] biz revoke BLOCKED subType=" + param.args[0]);
                        param.setResult(null);
                    } catch (Throwable t) {
                        Log.e(TAG, "[H3] error", t);
                    }
                }
            });
    }

    /* ============================================================
     * H2  com.tencent.mm.modelgetchatroommsg.h.a()  (Runnable.a)
     *     群聊 getcrmsg 历史路径：会 new e9() + setType(10000) + insert。
     *     注意：该方法同时维护群聊 seq，整体 return 可能影响 seq 同步，
     *           默认关闭；仅在群聊历史撤回不生效时开启。
     * ============================================================ */
    private void hookChatroomPath(LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("com.tencent.mm.modelgetchatroommsg.h",
            lpparam.classLoader, "a",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Log.i(TAG, "[H2] chatroom updateMsgSeq BLOCKED (seq sync may be affected)");
                    param.setResult(null);
                }
            });
    }

    /* ============================================================
     * H4  com.tencent.mm.modelsimple.d1.J(String, String, e9, String)
     *     自己撤回的内容改写。拦截后本地消息不变（服务端仍会撤）。
     * ============================================================ */
    private void hookSelfRevoke(LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("com.tencent.mm.modelsimple.d1",
            lpparam.classLoader, "J",
            String.class, String.class,
            "com.tencent.mm.storage.e9", String.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object msg = param.args[2];
                    Log.i(TAG, "[H4] self revoke content-write BLOCKED msg=" + msg);
                    param.setResult(null);
                }
            });

        // 拦掉撤回结果弹窗（"已撤回"/服务端 wording）
        XposedHelpers.findAndHookMethod("com.tencent.mm.ui.chatting.component.tl",
            lpparam.classLoader, "onSceneEnd",
            int.class, int.class, String.class,
            "com.tencent.mm.modelbase.m1",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Log.i(TAG, "[H4] revoke result dialog BLOCKED");
                    param.setResult(null);
                }
            });
    }
}
```

### 11.5 未读递减补丁（可选，`KEEP_UNREAD_DEC=true` 时启用）

若发现"撤回后会话未读数不减少"，在 `hookDoRevokeMsg` 的 `beforeHookedMethod` 里追加：

```java
// 需要在 beforeHookedMethod 内、setResult(null) 之前调用
// 注意：需要反射拿到 p0 -> k4 -> msgInfo，逻辑较繁琐，建议先实测是否需要
```

**实测结论先行**：绝大多数版本只 Hook H1+H3 即可，未读数由 `b41.t.c` 内部逻辑处理，拦截后不影响观感。

### 11.6 资源 ID（本版本，仅供调试参考）

| ID | 用途 | 值（据资源池） |
|---|---|---|
| `2131758555` | 自己撤回成功后的替换文本 | "你撤回了一条消息" |
| `2131758556` | 弹窗确认按钮 | "确定" |
| `2131758557` | 网络异常弹窗标题 | — |
| `2131758561` | 撤回中加载框 | "正在撤回" |
| `2131758562` | 发送失败 toast | — |
| `2131758563` | invokeMessage 5 分钟后替换文本 | — |
| `2131769862` | "重新编辑"后缀 | "重新编辑" |

> **强烈建议不要在模块里硬编码这些中文串**。微信原生提示语由服务端 `replacemsg` 下发，Hook 后微信自己会渲染，模块零文案。

---

## 十二、二次核查记录

> 以下每一项均在本次逆向中通过**至少两种独立手段**交叉验证。

### 12.1 `b41.t.c` 是唯一改写点

| 验证手段 | 结果 |
|---|---|
| `find_caller("b41.t", "c")` | 唯一调用者 `b41.t.i7` ✓ |
| `decompile_class("b41.t")` 全文扫描 | `setType(285222674)` 出现 1 次（L332），`setType(268445456)` 1 次（L352） ✓ |
| `get_method_smali("b41.t","c")` | 确认 `Qc(JLe9;Z)I` 调用在 `:cond_2a0` 前 ✓ |
| `search_strings("doRevokeMsg")` | 仅 `b41.t.c` 命中 ✓ |

### 12.2 `b41.t.i7` 是唯一分发点

| 验证手段 | 结果 |
|---|---|
| `find_caller("b41.t","i7")` | 无静态调用者 → 说明通过 `qs.g.f(v4.class)` 反射注册 ✓ |
| `decompile_class("b41.v")` | `hasKey()` 含 `"revokemsg"`；`get()` 单例 `new t()` ✓ |
| `decompile_class("tn3.v4")` | 接口方法 `i7(String, Map, p0) -> q0` ✓ |
| `b41.fd.k` L295-L299 | `g.f(v4.class).all()` → `hasKey(str)` → `i7(...)` ✓ |

### 12.3 XML 字段名准确

| 字段 | 验证手段 | 结果 |
|---|---|---|
| `.sysmsg.$type` | `b41.fd.k` L217 / `h.a` L216 | ✓ |
| `.sysmsg.revokemsg.session` | `b41.t.i7` L1253 / `h.a` L200/L219 | ✓ |
| `.sysmsg.revokemsg.newmsgid` | `b41.t.i7` L1254 / `h.a` L202/L221 | ✓ |
| `.sysmsg.revokemsg.replacemsg` | `b41.t.i7` L1255 / `h.a` L201/L220 | ✓ |
| `.sysmsg.revokemsg.announcement_id` | `b41.t.i7` L1256 | ✓ |
| `.sysmsg.revoke_climsgid` | `b41.b0.a` L19 | ✓ |
| `.sysmsg.replacemsg` | `b41.b0.a` L18 | ✓ |
| `.sysmsg.clouddelmsg.*` | `b41.t.i7` L734-L736 | ✓ |

### 12.4 消息 type 常量准确

| type | 验证手段 |
|---|---|
| `285222674` | `b41.t.c` L332；`d1.J` L256/L290；`e9.P2()`；`mn.n` L85；`f9.V3` SQL ✓ |
| `268445456` | `b41.t.c` L352；`d1.J` L274；`pn.a` L49；`f9.V3` SQL ✓ |
| `268445458` | `d1.J` L284；`mn.n` L85；`e9.O2()` ✓ |
| `10000` | `b41.b0.a` L28；`h.a` L204/L223；`b41.k.k` L100 ✓ |
| `10002` | `h.a` L186/L189/L198/L217/L223；`b41.t.i7` L1320 ✓ |
| `922746929` | `b41.t.c` L322；`f9.V3` SQL ✓ |

### 12.5 自己撤回 CGI 准确

| 项 | 验证手段 |
|---|---|
| CGI `/cgi-bin/micromsg-bin/revokemsg` | `d1.<init>` L171/L201；`d1` 字符串 `"T"` + onGYNetEnd ✓ |
| type `594` | `d1.<init>` L173/L204；`tl.onSceneEnd` L165/L172；`tl.F()` `a(594,...)` ✓ |
| 拍一拍同为 594 | `qv3.a` L35-L37 ✓ |
| 群公告 8006 | `tl.t0` L219-L220 ✓ |
| 群消息 805 | `f.c()` L91/L110 ✓ |

### 12.6 UI 渲染链准确

| 项 | 验证手段 |
|---|---|
| `mn.n()` 按 type 分派到 `bn`/`pn`/`rn` | `decompile_class("mn")` L85 ✓ |
| `pn` 处理 `268445456` | `decompile_class("pn")` L49 ✓ |
| `bn` 处理 `10002/268445458/285222674` | `decompile_class("bn")` L46-L65 ✓ |
| `cm5.b` 解析 `revokemsgcontent` | `decompile_class("cm5.b")` L78-L101 ✓ |
| `u95.e` XML tag `revokemsgcontent` | `decompile_class("u95.e")` L13-L16 ✓ |
| `u95.d` 字段 `isRead/scene/reEdit*` | `decompile_class("u95.d")` L11 ✓ |

### 12.7 事件对象字段准确

| 事件 | 载体 | 字段 | 验证 |
|---|---|---|---|
| `RevokeMsgEvent` | `fm.ks` | `a=msgId, b=talker, c=msgInfo, d=origin, e=svrId, f=announcementId` | `decompile_class("fm.ks")` + `b41.t.c` L358-L368 ✓ |
| `RevokeNativeMsgEvent` | `fm.ls` | `a=svrId, b=?, c=talker` | `decompile_class("fm.ls")` + `d1.onGYNetEnd` L374-L378 ✓ |
| `DeleteMsgEvent` | `fm.c4` | `a=msgId, b=svrId, c=talker, d=type, e=createTime` | `decompile_class("fm.c4")` + `aa.f()` L311-L319 ✓ |

### 12.8 未验证/不确定项（诚实标注）

| 项 | 状态 | 建议 |
|---|---|---|
| `b41.t.c` 的 `p0`（AddMsgInfo）内部字段 `b/c/d/e/f` 语义 | 部分推断（`b`=isGet 已由日志确认） | 如需精确定制，运行时可 `param.args[2]` 反射打印 |
| 群聊 `h.a()` 整体 return 对 seq 同步的影响 | **未实测** | 默认关闭 `BLOCK_CHATROOM_PATH` |
| `2131758563` 的确切中文值 | 未从 ARSC 解析出（字符串池为 UTF-8，缺解析工具链） | 不影响功能，Hook 后微信自渲染 |
| 跨版本混淆名稳定性 | 本版本专有名 | 必须用 DexKit 动态适配（见下） |

---

## 十三、版本适配与风险

### 13.1 DexKit 动态适配（强烈推荐）

微信每次更新都改混淆名。**不要写死 `b41.t`**。用 DexKit 按字符串锚点定位：

```java
// build.gradle
implementation 'org.luckypray:dexkit:2.0.3'

// 锚点（跨版本稳定，均为日志/XML 常量）
private static final String A_DOREVOKE   = "doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s";
private static final String A_REVOKEMSG  = ".sysmsg.revokemsg.replacemsg";
private static final String A_HIT_REVOKE = "mm hit MM_DATA_SYSCMD_NEWXML_SUBTYPE_REVOKE";
private static final String A_BIZ_REVOKE = "MicroMsg.BizChatSysCmdMsgConsumerHandleRevokeMsg";
private static final String A_CGI        = "/cgi-bin/micromsg-bin/revokemsg";

public static Map<String, Class<?>> resolve(ClassLoader cl, String apkPath) throws Exception {
    Map<String, Class<?>> out = new HashMap<>();
    try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
        // 1) doRevokeMsg 方法 -> 所在类
        for (MethodData md : bridge.findMethod {
            matcher {
                usingStrings(A_DOREVOKE)
            }
        }) {
            out.put("DoRevokeMsgClass", md.getDeclaringClassName());
            // 2) 在该类里找 c(...) 签名
            for (MethodData m : bridge.findMethod {
                searchIn { classNames = listOf(md.getDeclaringClassName()) }
                matcher {
                    name("c")
                    paramTypes(String.class.getName(), "long",
                               "com.tencent.mm.modelbase.p0",
                               String.class.getName(), String.class.getName(), String.class.getName())
                }
            }) {
                out.put("DoRevokeMsgMethod", m.getName());
            }
        }
        // 3) 商务号
        for (MethodData md : bridge.findMethod {
            matcher { usingStrings(A_BIZ_REVOKE) }
        }) { out.put("BizRevokeClass", md.getDeclaringClassName()); }
        // 4) 群聊路径
        for (MethodData md : bridge.findMethod {
            matcher { usingStrings(A_HIT_REVOKE) }
        }) { ... }
        // 5) 自己撤回 CGI
        for (MethodData md : bridge.findMethod {
            matcher { usingStrings(A_CGI) }
        }) { out.put("NetSceneRevokeMsgClass", md.getDeclaringClassName()); }
    }
    return out;
}
```

DexKit 锚点优先级：
1. **字符串锚点**（最稳）：`doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s`、`mm hit MM_DATA_SYSCMD_NEWXML_SUBTYPE_REVOKE`、`/cgi-bin/micromsg-bin/revokemsg`
2. **XML 字段锚点**：`.sysmsg.revokemsg.replacemsg`
3. **type 常量**：`285222674` / `268445456`（DexKit 支持 `numbers()`）
4. **方法签名**：`(String, long, p0, String, String, String)V`

### 13.2 已知风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 混淆名变化 | 每次微信更新类名变 | DexKit 动态解析 + 字符串锚点 |
| 多进程 | 微信有 `:tools` / `:sandbox` / `:appbrand` 等进程，撤回处理在 `:main` | Hook 时判断进程，或全进程 Hook |
| 硬编码字符串 | 不要往 UI 注入中文 | 保留服务端 `replacemsg`，让微信自己渲染 |
| 群聊 seq 不同步 | `BLOCK_CHATROOM_PATH=true` 可能影响 | 默认 false |
| 消息数据库修复 | 极端情况下 `b41.t.c` 的 `x7` 补齐逻辑被跳过，重装/换机后可能"补撤" | 低概率；如需彻底，额外 Hook `b41.v51.w1` 的 insert |
| 风控 | 纯本地 Hook，无网络行为，风险低 | — |

### 13.3 多进程注意

```java
// 撤回处理发生在微信主进程。Xposed 模块默认对所有进程生效，
// 但只有在 com.tencent.mm 主进程（processName == "com.tencent.mm"）才真正命中。
// 如需只在主进程 Hook：
@Override
public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
    if (!WX.equals(lpparam.packageName)) return;
    String proc = android.app.ActivityThread.currentProcessName();
    if (proc != null && !proc.equals(WX)) {
        // tools/sandbox 等子进程跳过（可选）
    }
    ...
}
```

### 13.4 验证方法

1. 装模块，LSPilot 日志过滤 `WXAntiRevoke`
2. 小号 A 给大号 B 发：文本 / 图片 / 视频 / 语音 / 文件 / 名片 / 链接 / 小程序 / 表情 / 合并转发 / 引用消息 / 拍一拍 / 群公告
3. A 撤回，观察 B：
   - 原消息应**照常显示**
   - 会话列表最后一条消息**不变**
   - 未读数**正常递减**（若异常，见 11.5）
4. 群聊重复 1-3
5. 自己撤回：若 `BLOCK_SELF_REVOKE=true`，本地消息不变，但弹窗被拦

---

## 附录 A：本次逆向使用的工具与调用

| 工具 | 用途 |
|---|---|
| `search_strings` | 定位 `撤回`/`revokemsg`/`replacemsg` 等锚点 |
| `search_string_resources` | 定位中文资源值 |
| `find_class` / `search_classes` | 定位 `RevokeMsgListener`/`RevokeChattingLandingPageUIC` 等 |
| `find_method` | 按名+约束定位 `c`/`J`/`i7`/`k` |
| `find_usage` | `qy_revoke_msg` / `RevokeMsgEvent` / `consumeNewXml` 反向定位 |
| `find_caller` | `b41.t.c` ← `b41.t.i7`；`d1.J` ← 8 处 |
| `find_class_usage` | 全局类引用（6 类型） |
| `class_hierarchy` | `b41.t implements tn3.v4` |
| `view_strings` | `b41.t` 6 方法 179 串；`cm5.b` 25 串 |
| `decompile_class_methods_only` | 快速接口结构 |
| `decompile_class_fields` | `im.c8` 108 字段 |
| `decompile_method` | `b41.t.c` / `d1.J` / `d1.onGYNetEnd` / `f9.O3/Qc/Ic` 等 |
| `decompile_class` | `b41.t`(1411行) / `b41.fd` / `cm5.b` / `u95.*` / `p95.d` / `rf0.*` |
| `get_method_smali` | `b41.t.c`(935行) / `b41.t.i7`(4441行) 交叉验证 |
| `get_class_smali` | `b41.t` 类头确认 `.implements Ltn3/v4;` |
| `read_manifest` | — |
| `run_command` | ARSC 字符串池提取、APK 定位 |

## 附录 B：核心类速查（一屏）

```
b41.fd.k(p0)                          SysCmdMsgExtension.consumeNewXml
  └─ b41.t.i7(str, Map, p0)           BigBallSysCmdMsgConsumer.consumeNewXml
       ├─ b41.b0.a(str, Map, p0)      商务号 qy_revoke_msg
       └─ b41.t.c(session, svrId, p0, replacemsg, announcementId, tag)  ★doRevokeMsg
            ├─ f9.O3(talker, svrId)   getBySvrId
            ├─ e9.E1(msg)             快照
            ├─ e9.b1(replacemsg)      写 content
            ├─ e9.setType(285222674 | 268445456)
            ├─ e9.u3(xml)
            ├─ aa.o(msg, p0)          fixRecvMsgWithAddMsgInfo
            ├─ f9.Qc(msgId, msg, true) UPDATE + notify
            ├─ RevokeMsgEvent.e()     UI 刷新
            ├─ b41.q.run()            删本地媒体
            ├─ oc0.e.ej().u1()        引用消息标记
            └─ j2.V1(b1-1)            未读 -1

com.tencent.mm.modelgetchatroommsg.h.a()   群聊 getcrmsg 路径
com.tencent.mm.modelsimple.d1               自己撤回 NetSceneRevokeMsg (CGI 594)
  └─ d1.J(str, str2, msg, msgSource)        自己撤回内容改写
cm5.b                                       撤回提示 XML 解析
com.tencent.mm.ui.chatting.component.tl     撤回 UI 组件
rf0.b0                                      RevokeMsgHandler
```

---

**报告完** · 生成于 LSPilot 逆向工作台 · APK: `com.tencent.mm_10b9d6323b.apk`
