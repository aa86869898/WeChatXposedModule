# 微信 8.0.76 文本消息上传链路精准分析

> 生成时间：$(date '+%Y-%m-%d %H:%M:%S')
> 工具：LSPilot AI 分析助手

---

## 一、核心结论

文本、图片、语音、视频、文件——**全部走同一条 CGI**，没有单独的"文本上传通道"。

| 属性 | 值 |
|------|-----|
| **CGI URL** | `/cgi-bin/micromsg-bin/newsendmsg` |
| **CmdID** | 522 |
| **RespID** | 237 |
| **FuncID** | 1000000237 |
| **超时** | 120 秒 |
| **请求体** | `a65.f16`（NewSendMsgRequest），内含 `LinkedList<a65.en4>` |
| **响应体** | `a65.g16`（NewSendMsgResponse） |
| **网络层** | Mars STN 长连接 |

区分消息类型靠 `en4.f`（type 字段）：

| type | 消息类型 |
|------|----------|
| 1 | 文本 |
| 3 | 图片 |
| 34 | 语音 |
| 43 | 视频 |
| 49 | 文件/链接 |

---

## 二、完整调用链

```
y11.r1.c()                              ← SendMsgCgiFactory（上层入口）
  └─ a21.b0 (SendMsgService, ViewModel)
       └─ vj(List<e9>, Continuation)    ← ★ 消息组装 + 网络发送
            │
            │  [遍历每条消息 e9]
            │
            ├─ if (e9Var.z0() == 1)     ← type=1 文本消息分支
            │    │
            │    └─ new en4()           ← 构造单条消息命令
            │         │
            │         ├─ en4.d = ew5    ← 收件人: {username, flag=true}
            │         │    └─ ew5.d = e9Var.N0()    username
            │         │    └─ ew5.e = true          flag
            │         │
            │         ├─ en4.e = e9Var.j()          ← ★ 文本内容
            │         ├─ en4.f = e9Var.getType()     ← type=1
            │         ├─ en4.g = (int)(createTime/1000) ← 时间戳(秒)
            │         ├─ en4.h = hash(user,time)     ← newmsgid
            │         ├─ en4.i = MsgSource           ← 来源标记
            │         └─ en4.m = ticket              ← 票据(条件性)
            │
            ├─ f16.e.add(en4)           ← 塞进请求体链表
            ├─ f16.d = f16.e.size()     ← 更新计数
            │
            ├─ f16.b()                  ← 构建 CGI task
            │    └─ lVar.c = "/cgi-bin/micromsg-bin/newsendmsg"
            │       lVar.d = 522         cmdID
            │       lVar.e = 237         respID
            │       lVar.f = 1000000237  funcID
            │
            └─ sm0.h.b(task, cont)      ← ★★★ 网络上传入口
                 │
                 └─ sm0.h.c(task, g, cont)
                      │
                      └─ a4.c(120000L, g)     ← withTimeout 120秒
                           │
                           └─ sm0.g.invokeSuspend()
                                │
                                ├─ new r(scope, 1)     ← 协程作用域
                                ├─ rVar.k()             ← 准备
                                ├─ task.l().L(d, e)     ← ★ wr5.g 分发 CGI
                                ├─ rVar.m(new f(task))  ← 取消回调
                                └─ rVar.j()             ← await 响应
                                     │
                                     └─ wr5.g.L(sn5.d, nn5.a)
                                          │
                                          └─ Mars STN 网络层
                                               │
                                               └─ TCP 长连接
                                                    │
                                                    └─ POST /cgi-bin/micromsg-bin/newsendmsg
```

---

## 三、关键类职责

| 类 | 职责 | 方法 |
|-----|------|------|
| **`sm0.h`** | ★ CGI 发送包装器，所有 CGI 唯一出口 | `b(i, Continuation)` |
| **`sm0.g`** | 实际执行 CGI 调用的协程 lambda | `invokeSuspend(Object)` |
| **`wr5.g`** | CGI 分发器，对接 Mars 网络层 | `L(sn5.d, nn5.a)` |
| **`a21.b0`** | SendMsgService，消息发送业务逻辑 | `vj(List, Continuation)` |
| **`a65.f16`** | NewSendMsgRequest，Protobuf 请求体 | `b()` → 构建 `i`(CGI task) |
| **`a65.en4`** | 单条消息命令，包含内容/类型/收件人 | — |
| **`y11.r1`** | SendMsgCgiFactory，上层的 Builder API | `c(l)`, `b()` |

---

## 四、f16 / en4 字段精准结构

### a65.f16（NewSendMsgRequest）

```smali
# instance fields
.field public d:I                    # 消息条数
.field public e:Ljava/util/LinkedList;  # List<en4> 消息命令列表

# 继承自 a65.nt5
.field public BaseRequest:La65/je;   # 基础请求头（deviceID/sessionKey等）
```

### a65.en4（单条消息命令）

```smali
.field public d:La65/ew5;            # 收件人 {username, flag}
.field public e:Ljava/lang/String;   # ★ 文本内容
.field public f:I                    # ★ 消息类型（1=文本）
.field public g:I                    # 创建时间（秒）
.field public h:I                    # newmsgid（hash）
.field public i:Ljava/lang/String;   # MsgSource
.field public m:Ljava/lang/String;   # ticket
```

### a65.ew5（收件人子结构）

```smali
.field public d:Ljava/lang/String;   # username（群 "xxx@chatroom"）
.field public e:Z                    # flag（固定 true）
```

### a65.g16（NewSendMsgResponse）

```smali
.field public d:I                    # ret？
.field public e:Ljava/util/LinkedList;  # 响应列表
.field public f:I                    # 附加标志
```

---

## 五、Hook 方案

### 方案 A：最底层 — Hook sm0.h.b()（所有 CGI 必经之路）

```bsh
// sm0.h.b(i, Continuation) — 任何 CGI 请求都会经过这里
hookMethodBefore("sm0.h", "b", param -> {
    i task = (i) param[0];
    o oVar = task.f;
    if (oVar == null) return;

    String uri = oVar.c;
    if (!"/cgi-bin/micromsg-bin/newsendmsg".equals(uri)) return;

    log("===== 消息发送 CGI 捕获 =====");
    log("URI: " + uri + "  CmdID: " + oVar.d);
});
```

### 方案 B：中间层 — Hook a21.b0.vj()（可读性最好）

```bsh
// a21.b0.vj(List<e9>, Continuation)
hookMethodBefore("a21.b0", "vj", param -> {
    List<e9> list = (List) param[0];
    log("===== SendMsgService.vj() 被调用 =====");
    log("待发送消息数: " + list.size());

    for (int i = 0; i < list.size(); i++) {
        e9 msg = (e9) list.get(i);
        log("  [" + i + "] type=" + msg.getType()
            + " talker=" + msg.N0()
            + " content=" + (msg.getType() == 1 ? msg.j() : "[非文本]"));
    }
});
```

### 方案 C：上层 — Hook y11.r1.c()（Builder API 入口）

```bsh
// y11.r1.c(l) — 通过 Builder API 发送
hookMethodBefore("y11.r1", "c", param -> {
    r1 self = (r1) param[0];  // BSH 中 param 是 this
    log("===== r1.c() 发送 =====");
    log("  talker: " + self.b);
    log("  content: " + self.d);
    log("  type: " + self.e);
});
```

### 方案 D：修改文本 — Hook a21.b0.vj() 替换内容

```bsh
hookMethodBefore("a21.b0", "vj", param -> {
    List<e9> list = (List) param[0];
    for (e9 msg : list) {
        if (msg.getType() == 1) {
            String orig = msg.j();
            // 替换内容
            callMethod(msg, "d1", "[已篡改] " + orig);
            log("文本已修改: " + orig + " → [已篡改]");
        }
    }
});
```

---

## 六、BSH 完整示例：拦截并记录所有文本发送

```bsh
import com.tencent.mm.storage.e9;
import java.util.List;

hookMethodBefore("a21.b0", "vj", new JavaHookCallback() {
    public void onBefore(Object thisObject, Object[] args) {
        List msgList = (List) args[0];
        int textCount = 0;

        for (int i = 0; i < msgList.size(); i++) {
            e9 msg = (e9) msgList.get(i);
            if (msg.getType() == 1) {
                textCount++;
                log("[TEXT#" + textCount + "] "
                    + "talker=" + msg.N0()
                    + " time=" + msg.getCreateTime()
                    + " content=" + msg.j());
            }
        }

        if (textCount > 0) {
            log("共拦截 " + textCount + " 条文本消息发送");
        }
    }
});
```

---

## 七、CGI 请求/响应对照

```
Request (POST newsendmsg):
┌─────────────────────────────────┐
│ f16 (NewSendMsgRequest)         │
│   d = 1           (count)        │
│   e = [                         │
│     en4 {                       │
│       d = {username, true}  ← 收件人 │
│       e = "hello"           ← ★ 文本 │
│       f = 1                 ← type │
│       g = 1719000000        ← 时间 │
│       h = -123456789        ← msgid │
│       i = "<msgsource>..."  ← 来源 │
│       m = "ticket..."       ← 票据 │
│     }                           │
│   ]                             │
│   BaseRequest = {deviceID, ...} │
└─────────────────────────────────┘

Response (g16):
┌─────────────────────────────────┐
│ g16 (NewSendMsgResponse)        │
│   d = 0           (ret code)     │
│   e = [...]       (per-msg resp) │
│   f = 0           (flags)        │
└─────────────────────────────────┘
```

---

## 八、总结

| 问题 | 答案 |
|------|------|
| 文本走哪个 CGI | `/cgi-bin/micromsg-bin/newsendmsg` (cmd=522) |
| 哪个类是上传出口 | **`sm0.h.b()`** → `sm0.g` → `wr5.g.L()` → Mars STN |
| 文本在请求体哪里 | `f16.e` (LinkedList) → `en4.e` (String) |
| 怎么区分消息类型 | `en4.f` (int): 1=文本, 3=图片, 34=语音, 43=视频 |
| 最佳 Hook 点 | `a21.b0.vj()` — 参数是 `List<e9>`，可读性最好 |
| 最底层 Hook 点 | `sm0.h.b()` — 所有 CGI 唯一出口，包括非消息 CGI |
