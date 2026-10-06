# 发消息链深度解剖（3180）：MsgSendTask / executeByPPC → carrier → queue → cgi 522
> 目标：钉死 Item6 主锚与入口调用链，并定位 `v51.q0` 在链中的真实位置。基于 DexKit+jadx/smali 取证。

## 0. 结论先行
- **纠正A（关键，解释你“日志无输出”）**：现代 3180 普通消息的**真正发送器 = `SendMsgService` = `x51.b0`**。它**自行构造 RR（newsendmsg / cgi 522）并用协程 `modelbase.i` + `rp0/h.b` 收发**，**并不 `new v51.r0`**。你原先 Hook 的 `v51.r0`(NetSceneSendMsg, `getType=0x20a`) 只覆盖“经典 NetScene”路径——正常发消息不走它，所以没输出。
- **纠正B（“queue.h”= 入队/传输动作，两种实现）**：
  - 经典 NetScene：`com.tencent.mm.modelbase.m1.dispatch(network.s, network.y0, network.l0)→int`（提交到网络队列）
  - 协程 SendMsgService：`modelbase.i`（RR 发送体）+ 挂起 `rp0/h.b(modelbase.i, Continuation)`
- 两条路径**殊途同归到 cgi 522**：url `/cgi-bin/micromsg-bin/newsendmsg`，req `pc5.r66`，resp `pc5.s66`。
- 命名提示：`v51/x51/pc5/gp0/...` 为 dex **根级扁平包**，`Class.forName`/Hook 直接用短名（如 `v51.r0`），别加 `com.tencent.mm.` 前缀。

## 1. 链路图（3180 实际）
```
[MsgInfo = com.tencent.mm.storage.e9]
        │
[XxxMsgSendTask（每类消息一个）]
  com.tencent.mm...e10.h   = ContactCardMsgSendTask   (名片)
  com.tencent.mm...kb0.w0  = LocationMsgSendTask      (位置)
  … text/img/video/voice/file 同理
   ├─ u()            createCgiRequest -> pc5.r66 (SendMsgReq)
   ├─ C() prepare ; B() updateMsg ; y() uploadAttach ; F() onMsgSendFail
   ├─ l(Cont)  sendCgi       (普通)
   └─ k(Cont)  sendBypCgi    (byp/并发)
        │
[SendMsgCgiFactory v51.r1  (TAG=MicroMsg.SendMsgCgiFactory)]
   ├─ e/g/h(String)->r1 ; f(b41/k7)->r1 ; i(I)->r1      (参数构造, Builder)
   ├─ c(Lr96/l) = executeByPPC                          (PPC/旁路入口)
   │      └─ new x51.y(r1, long, r96/l, Cont) ; 投递到 SequenceLifecycleScope (xe5/i.c)
   └─ a() -> new v51.n1 { a:modelbase.m1 , b:long }      (产出 NetScene 壳)
        │
[真正发送器 SendMsgService x51.b0  (TAG=MicroMsg.SendMsgService)]
   ├─ kj(List<e9>, Cont) 批量发送 ; mj(List)/nj(List)/lj(e9) ; oj()
   └─ 内部建 RR: modelbase/l{ .a=pc5.r66 ; .b=pc5.s66 ;
        .c="/cgi-bin/micromsg-bin/newsendmsg" ; .d=0x20a(522) ; .e=0xed(237) ; .f=0x3b9acaed }
        逐条 e9 -> pc5.pr4(reqCmd) (+ MsgSource: v51.i1.s(pc5.pr4,e9)) 入 pc5.r66.e
        │
[入队/传输]  ↓↓↓ 二选一
 (现代)  pc5.r66.b() -> modelbase.i ; modelbase.i.r(RR) ; rp0/h.b(modelbase.i, Cont) 挂起收发
          回包 modelbase.f{ .a=errType ; .b=errCode ; .c=errMsg ; .d -> pc5.s66 }
 (经典)  [旁路] v51.r0 = NetSceneSendMsg (getType=0x20a)
          v51.r0.doScene() 建同款 RR 后 ->
          modelbase.m1.dispatch(dispatcher, RR(r0.e), this(callback l0)) -> int
        │
[网络引擎] cgi 522 /cgi-bin/micromsg-bin/newsendmsg
        │
[回包] pc5.s66 ; 逐条结果 pc5.qr4 -> 更新 MsgInfo(e9) 发送状态/落 SvrId
```

## 2. 逐环节明细

| # | 环节 | 类（dex 真名） | 关键方法签名 | 参数/构造说明 |
|---|---|---|---|---|
| 1 | 消息实体 | `com.tencent.mm.storage.e9` | `getMsgId()J`、`N0()String`(talker)、`j()String`(content)、`getType()I`、`getCreateTime()J`、`z0()I`、`M0()I`、`t1(I)V` | MsgForge 载体 |
| 2 | 名片发送任务 | `...e10.h` | `u()->pc5.r66`、`l(Continuation)Object`(sendCgi)、`k(Continuation)Object`(sendBypCgi)、`C()prepare`、`B()updateMsg`、`y()uploadAttach`、`F()onMsgSendFail`、`<init>(params)` | TAG=`MicroMsg.ContactCardMsg.ContactCardMsgSendTask` |
| 3 | 位置发送任务 | `...kb0.w0` | 同 #2 | TAG=`MicroMsg.LocationMsg.LocationMsgSendTask`；`y()`=`uploadAttach: no attachment needed for location msg` |
| 4 | 发送工厂 | `v51.r1` | `c(Lr96/l)V`=**executeByPPC**、`a()->v51.n1`、`e/g/h(String)->r1`、`f(b41/k7)->r1`、`i(I)->r1`、`d(r1,r96/l,int,Object)V`(合成桥,抛"…function: executeByPPC") | TAG=`MicroMsg.SendMsgCgiFactory` |
| 5 | PPC 协程体 | `x51.y` | `<init>(v51/r1, J, r96/l, Continuation)` | executeByPPC 里 `new` 并投递 |
| 6 | 工厂产出壳 | `v51.n1` | 字段 `a:modelbase.m1`、`b:J` | `v51.r1.a()` 里 `new v51/n1` |
| 7 | 真正发送器 | `x51.b0` | `kj(List, Continuation)Object`、`mj(List)V`、`nj(List)V`、`lj(e9)V`、`oj()V`、`jj(b0,Continuation)Object` | TAG=`MicroMsg.SendMsgService`；**现代主发送器** |
| 8 | SendMsgReq | `pc5.r66` | 字段 `e:LinkedList`(reqCmd)、`d:I`(count)；`b()->modelbase.i` | proto |
| 9 | 单条 reqCmd | `pc5.pr4` | `d:pc5.q16`(ToUser)、`e:String`(content)、`f:I`(type)、`g:I`(createTime/1000)、`h:I`(msgsrcHash)、`i:String`(MsgSource)、`m:String`(nickname) | |
| 10 | SendMsgResp | `pc5.s66` | (`e:LinkedList`/逐条 `pc5.qr4`) | proto |
| 11 | RR 构造器 | `com.tencent.mm.modelbase.l` | `a()->modelbase.o`；字段 `.a`req `.b`resp `.c`url `.d`cgi `.e`func `.f`timeout | 与 cgi 522 |
| 12 | 协程 RR 发送 | `com.tencent.mm.modelbase.i` | `r(modelbase.o)V` | 现代传输体 |
| 13 | 协程收发挂起 | `~rp0/h` | `b(modelbase.i, Continuation)Object` | await 返回 `modelbase.f` |
| 14 | 回包体 | `com.tencent.mm.modelbase.f` | `.a:I`(errType) `.b:I`(errCode) `.c:String` `.d:pb.f`(→`pc5.s66`) | |
| 15 | 经典 NetScene | `v51.r0` | `doScene(network.s, modelbase.u0)I`、`onGYNetEnd(IIILjava/lang/String;network.y0;[B)V`、`H(String)V`、`K(e9)V`、`getType()I=0x20a`、`J(I)V`、`L(I)V` | `.super modelbase.m1` `.implements network.l0` |
| 16 | 入队(经典) | `com.tencent.mm.modelbase.m1` | `dispatch(network.s, network.y0, network.l0)I`、`doScene(network.s, modelbase.u0)I`、`dispatcher()->network.s` | = "queue.h" |
| 17 | MsgSource 组装 | `v51.j1`→`v51.i1` | `v51.i1.s(pc5.pr4, e9)V` | 伪造消息常改点 |
| 18 | 场景结束监听 | `v51.p0` | `onSceneEnd(...)` | 发送回调侧 |
| 19 | 内核/插件注册 | `gp0.j1` | `v(Class)lp0/a`（取服务 `tn3/c4`=PluginMessengerFoundation.h2、`tn3/k4`、`tn3/r4`）；`j()`(Kernel not initialized 守卫) | 全链依赖 |

## 3. `new v51.r0` 到底在不在链上 / `v51.q0` 的真实位置
- **`new v51.r0` 不在现代主链上**。`x51.b0.kj` 在第 189–223 行直接 `new modelbase/l + new pc5.r66/pc5.s66` 建 RR，第 424–444 行走 `pc5.r66.b()→modelbase.i` + `rp0/h.b` 收发，全程**无 `new v51/r0`**。`v51.r0` 只在“经典/重发”路径出现，且 `find_class_usage(v51.r0)` 仅命中 `v51.q0` 的**字段引用**（未见 new；降级扫描限制，但与 x51.b0 直接建 RR 互相印证）。
- **`v51.q0` = `v51.r0` 的合成 Runnable，不在“发消息→cgi522”主链上**：
  - 声明：`.class public Lv51/q0; .super Ljava/lang/Object; .implements Ljava/lang/Runnable;`
  - 字段：`d:String`、`e:Lv51/r0`（即 find_class_usage 的 `FIELD: v51/q0.e`）；构造 `<init>(v51/r0, String)`
  - `run()V`：`new com.tencent.mm.modelsimple.l1(int,String,String,String,String,boolean,int,boolean)` → `e(r0).dispatcher()` 取 dispatcher → `new v51/p0` 回调 → `modelsimple.l1.doScene(dispatcher, v51/p0)`
  - 即：由 `v51.r0` 派生、**复用 r0 的 dispatcher 去发一个“新消息通知/刷新”轻场景 `modelsimple.l1`**，与真正的发文本/名片/位置（走 x51.b0）不是同一环节。它只是“携带 v51.r0 实例”的旁路任务。

## 4. Item6 主锚与入口 Hook 钉死方案
| 目的 | 建议 Hook 点 | 说明 |
|---|---|---|
| 抓普通发送（文本/名片/位置/图片…） | **`x51.b0.kj(List, Continuation)`** | 现代主入口（SendMsgService） |
| 抓 RR 构造/伪造 | `pc5.pr4` 组装、`v51.i1.s(pc5.pr4,e9)`、`modelbase.l` 字段 | 改 content/toUser/MsgSource |
| 抓入队/传输 | `modelbase.i.r(modelbase.o)` + `rp0/h.b(modelbase.i,Cont)`（现代）；`modelbase.m1.dispatch(...)`（经典） | = "queue" |
| 抓 PPC/旁路 | `v51.r1.c(Lr96/l)`(=executeByPPC)、`x51.y`、`x51.b0` | Bypass/并发发送 |
| 经典/重发 NetScene | `v51.r0.doScene` / `onGYNetEnd` / `H(String)` / `K(e9)` | 覆盖旧路径 |
| 回包/结果 | `pc5.s66`、逐条 `pc5.qr4`；成功更新在 `x51.b0.kj` resp 处理或 `v51.r0.onGYNetEnd` | |
| 内核依赖 | `gp0.j1.v(Class)`（=Kernel 守卫同族） | 取 PluginMessengerFoundation/服务 |

> 你之前“NetSceneSendMsg 无输出”：改 Hook `x51.b0.kj`（或 v51.r1.c）即可命中真实发送；若要同时兜底经典路径，再并列 Hook `v51.r0.onGYNetEnd`。
