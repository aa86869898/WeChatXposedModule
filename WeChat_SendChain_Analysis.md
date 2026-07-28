# 微信 8.0.76 消息发送链路深度分析

> 生成时间：$(date '+%Y-%m-%d %H:%M:%S')
> 工具：LSPilot AI 分析助手

---

## ⚠️ 重要说明：类名差异

你提到的四个类名在此版本 **8.0.76 中不存在**：
- `com.tencent.mm.plugin.chatting.send.SenderPPC` — 包 `plugin.chatting` 不存在
- `x02.d.b()` — `x02.d` 是 TMAssistant 下载器，没有 `b()` 方法
- `g06.h00.f()` — `g06` 只有 `a`/`b` 两个类
- `t06.i.d()` — `t06` 只有 `a`/`b`/`c`/`d`，无 `i`

**以下是你描述链路的 8.0.76 实际对应实现，功能完全等价：**

```
SenderPPC.j2()    →  a21.b0.vj()
x02.d.b()        →  sm0.h.b()
g06.h00.f()      →  sm0.g.invokeSuspend()
t06.i.d()        →  wr5.g.L()
```

---

## 一、完整发送链路（Java → 网络）

```
┌──────────────────────────────────────────────────────────────────┐
│ a21.b0 (SendMsgService, ViewModel)                               │
│   ├── tj()  →  "startSendLooper" 协程循环                         │
│   │   ├── 从 f9.z6(limit) 拉取待发送消息                           │
│   │   └── 循环调用 vj(list, continuation)                        │
│   │                                                              │
│   └── vj(List<e9>, Continuation)                                 │
│       ├── for each e9Var: if (z0() == 1) → 文本消息              │
│       │   ├── new en4() 填充: username, createtime, type, content│
│       │   ├── y1.a(user, time).hashCode() → newmsgid             │
│       │   ├── k4.kj(user) → ticket                              │
│       │   └── r4.i1Var.r(en4, e9) → MsgSource                    │
│       │                                                          │
│       ├── 组装 f16 (NewSendMsgRequest)                            │
│       │   ├── CGI: /cgi-bin/micromsg-bin/newsendmsg              │
│       │   ├── CmdID: 522, RespID: 237, FuncID: 1000000237       │
│       │   └── f16Var.e = LinkedList<en4>                         │
│       │                                                          │
│       └── sm0.h.b(i, continuation)  ← ★ 网络发送                 │
│                                                                  │
├──────────────────────────────────────────────────────────────────┤
│ sm0.h (CGI Send Wrapper)                                         │
│   ├── b(i, continuation)                                         │
│   │   └── c(i, new g("Cgi_" + cmdID), continuation)              │
│   │                                                              │
│   └── c(i, d, continuation)                                      │
│       └── a4.c(120000L, gVar)  // withTimeout 120秒              │
│                                                                  │
├──────────────────────────────────────────────────────────────────┤
│ sm0.g (Suspend Lambda — 实际 CGI 调用)                            │
│   └── invokeSuspend(obj)                                         │
│       ├── new r(CoroutineScope, 1)                               │
│       ├── rVar.k()                  // 准备                       │
│       ├── iVar.l().L(dVar, e)  ← ★ 通过 wr5.g 分发 CGI           │
│       ├── rVar.m(new f(iVar))      // 取消回调                    │
│       └── rVar.j()                 // await 结果                  │
│                                                                  │
├──────────────────────────────────────────────────────────────────┤
│ wr5.g (CGI Dispatcher)                                           │
│   └── L(sn5.d, nn5.a)  →  底层网络发送 CGI 请求                   │
│       └── 通过 Mars 网络层发送到微信服务器                          │
└──────────────────────────────────────────────────────────────────┘
```

---

## 二、a21.b0.vj() — 核心发送方法详解

### 类路径：`a21.b0`
### Log Tag：`"MicroMsg.SendMsgService"`

### 方法签名（从 Smali 还原）：

```java
// a21.b0 extends km0.o (ViewModel) implements tx.j0
public final Object vj(List<e9> list, Continuation continuation)
```

### 完整流程（Java 伪代码）：

```java
public final Object vj(List<e9> list, Continuation continuation) {
    // ========== 第一阶段：构建 CGI 请求 ==========
    
    // 1. 创建 CGI 请求包装
    l lVar = new l();
    lVar.a = new f16();              // NewSendMsgRequest
    lVar.b = new g16();              // NewSendMsgResponse
    lVar.c = "/cgi-bin/micromsg-bin/newsendmsg";
    lVar.d = 522;                     // CmdID
    lVar.e = 237;                     // RespID
    lVar.f = 1000000237;              // FuncID
    
    o a = lVar.a();
    f16 f16Var = (f16) a.a.a;        // 取出 Protobuf Request
    
    // 2. 遍历待发送消息列表
    for (e9 e9Var : list) {
        // ★ type=1 文本消息分支（你提到的 switch(106) type=1）
        if (e9Var.z0() == 1) {
            en4 en4Var = new en4();   // 单条消息体
            
            // 2a. 收件人
            ew5 ew5Var = new ew5();
            ew5Var.d = e9Var.N0();    // username
            ew5Var.e = true;
            en4Var.d = ew5Var;
            
            // 2b. 时间戳
            en4Var.g = (int)(e9Var.getCreateTime() / 1000);
            
            // 2c. 消息类型
            en4Var.f = e9Var.getType();
            
            // 2d. 消息内容
            en4Var.e = e9Var.j();     // content
            
            // 2e. NewMsgId (基于 user+time 的 hash)
            en4Var.h = y1.a(z1.r(), e9Var.getCreateTime()).hashCode();
            
            // 2f. Ticket (如果需要)
            if (((k4) n0.c(k4.class)).kj(e9Var.N0())) {
                en4Var.m = ((c4) j1.s(c4.class)).ij().X(e9Var.N0());
            }
            
            // 2g. MsgSource 组装器
            i1 i1Var = ((r4) n0.c(r4.class)).d;
            if (i1Var != null) {
                i1Var.r(en4Var, e9Var);
            }
            
            // 2h. 加入请求列表
            f16Var.e.add(en4Var);
            f16Var.d = f16Var.e.size();
        }
    }
    
    // 3. 构建 i (CGI task) 并发送
    i b2 = f16Var.b();
    b2.p(a);
    
    // ========== 第二阶段：网络发送 ==========
    Object b = sm0.h.b(b2, continuation);  // ★ 这里联网发送
    
    // ========== 第三阶段：处理响应 ==========
    // ... 解析 g16 响应，处理成功/失败 ...
    // 成功 → xj(list)
    // 失败 → yj(list)
}
```

### 关键类型速查

| 变量 | 类型 | 含义 |
|------|------|------|
| `f16` | `a65.f16` | NewSendMsgRequest (Protobuf) |
| `g16` | `a65.g16` | NewSendMsgResponse (Protobuf) |
| `en4` | `a65.en4` | 单条消息命令 (MsgCommand) |
| `ew5` | `a65.ew5` | 收件人信息 (username + flag) |
| `e9` | `com.tencent.mm.storage.e9` | MsgInfo (本地消息记录) |
| `i` | `com.tencent.mm.modelbase.i` | CGI 任务基类 |
| `o` | `com.tencent.mm.modelbase.o` | CGI 请求包装 (含 req/resp) |

---

## 三、sm0.h — CGI 网络发送层

### 类路径：`sm0.h`

```java
public abstract class h {
    
    // ★ 入口：b(i, continuation)
    public static final Object b(i iVar, Continuation continuation) {
        StringBuilder sb = new StringBuilder("Cgi_");
        o oVar = iVar.f;
        sb.append(oVar != null ? oVar.d : 0);
        return c(iVar, new g(sb.toString()), continuation);
    }
    
    // 核心：c(i, d, continuation) — withTimeout(120s) + 实际 dispatch
    public static final Object c(i iVar, d dVar, Continuation continuation) {
        // ...
        g gVar = new g(iVar, dVar, fVar2, null);
        // withTimeout(120000ms)
        Object c = a4.c(120000L, gVar, dVar2);
        // ...
        // 超时返回: f.a(3, -13, "Cgi Timeout", ...)
    }
    
    // 带自定义超时：a(i, timeout, d, continuation)
    public static Object a(i iVar, long j, d dVar, Continuation c, int i, Object obj) {
        if ((i & 1) != 0) j = Long.MAX_VALUE;
        if ((i & 2) != 0) dVar = null;
        return a4.b(j, new c(iVar, dVar, null), c);
    }
}
```

---

## 四、sm0.g — 实际 CGI 调用 Lambda

### 类路径：`sm0.g`

```java
// sm0.g.invokeSuspend() 核心逻辑
public final Object invokeSuspend(Object obj) {
    // ...
    i iVar = this.h;        // CGI task
    d dVar = this.i;        // dispatcher
    f fVar = this.m;        // protobuf response
    
    // 创建协程 scope
    r rVar = new r(CoroutineScope, 1);
    rVar.k();
    
    // ★ 通过 wr5.g 分发 CGI 请求
    o oVar = iVar.f;
    iVar.l()                           // → wr5.g (CGI Dispatcher)
        .L(dVar,                       // dispatcher
           new e(oVar.d, fVar, iVar, rVar));  // callback
    
    rVar.m(new f(iVar));              // 设置取消回调
    obj = rVar.j();                   // ★ await 网络响应
    return obj;
}
```

---

## 五、wr5.g — CGI 分发器

### 类路径：`wr5.g`

```java
// 关键方法
public class g {
    // 设置 dispatcher
    D(sn5.d) → g
    
    // ★ 发送 CGI：L(sn5.d, nn5.a)
    L(sn5.d dispatcher, nn5.a callback) → g
    
    // 设置各种回调
    E(nn5.a), F(nn5.a), G(nn5.a), H(nn5.a)
    K(nn5.a), M(rn5.c)
    
    // 设置超时等参数
    l(long timeout) → rn5.d
    h(boolean) → rn5.d
}
```

---

## 六、完整类映射表（你的版本 → 8.0.76）

| 你的类 | 8.0.76 等价类 | 方法 | 功能 |
|--------|---------------|------|------|
| `SenderPPC` | `a21.b0` | `vj(List, Continuation)` | SendMsgService，组装消息+发送 |
| `SenderPPC.j2()` | `a21.b0.vj()` | 同上 | 遍历消息列表，构建 CGI 请求 |
| 其中 `switch(106) type=1` | `if (e9Var.z0() == 1)` | 同上 | 文本消息类型判断 |
| `x02.d.b()` | `sm0.h.b()` | `b(i, Continuation)` | CGI 发送入口 |
| `g06.h00.f()` | `sm0.g.invokeSuspend()` | `invokeSuspend(Object)` | 实际 execute CGI |
| `t06.i.d()` | `wr5.g.L()` | `L(sn5.d, nn5.a)` | CGI 分发器 send |

---

## 七、BSH Hook 方案

### 方案 A：Hook SendMsgService.vj() — 拦截所有发送

```bsh
// 类: a21.b0  方法: vj
hookMethodBefore("a21.b0", "vj", param -> {
    // param[0] = List<e9> 待发送消息
    // param[1] = Continuation
    List msgList = (List) param[0];
    for (int i = 0; i < msgList.size(); i++) {
        e9 msg = (e9) msgList.get(i);
        log("发送消息: type=" + msg.getType() + 
            " content=" + msg.j() + 
            " to=" + msg.N0());
    }
});
```

### 方案 B：Hook sm0.h.b() — 拦截网络发送

```bsh
// 类: sm0.h  方法: b
hookMethodBefore("sm0.h", "b", param -> {
    // param[0] = com.tencent.mm.modelbase.i (CGI task)
    // 从 i.f (o) 中获取 CGI 信息
    i task = (i) param[0];
    o oVar = task.f;
    if (oVar != null) {
        log("CGI发送: cmdID=" + oVar.d + 
            " url=" + oVar.c);
    }
});
```

### 方案 C：Hook wr5.g.L() — 底层网络拦截

```bsh
// 类: wr5.g  方法: L
hookMethodBefore("wr5.g", "L", param -> {
    // param[0] = sn5.d (dispatcher)
    // param[1] = nn5.a (callback)
    log("CGI Dispatch: " + param[0]);
});
```

---

## 八、关键常量

| 常量 | 值 | 含义 |
|------|-----|------|
| CGI URL | `/cgi-bin/micromsg-bin/newsendmsg` | 新消息发送接口 |
| CmdID | 522 | 命令 ID |
| RespID | 237 | 响应 ID |
| FuncID | 1000000237 | 功能 ID |
| Timeout | 120000ms (120秒) | 网络超时 |
| 文本消息类型 | `z0() == 1` | 对应 type=1 |
| 发送中状态 | `t1(5)` | wj() 中设定的状态 |
