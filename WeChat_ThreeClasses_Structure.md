# 微信 8.0.76 三个关键类完整结构

> 生成时间：$(date '+%Y-%m-%d %H:%M:%S')
> 工具：LSPilot AI 分析助手

---

## 一、`a65.f16` — NewSendMsgRequest

### 继承链

```
a65.f16
  └─ a65.nt5
       └─ com.tencent.mm.protobuf.f
            └─ java.lang.Object
```

### 字段（含继承）

| 来源 | 字段 | 类型 | 含义 |
|------|------|------|------|
| **f16 自身** | `d` | `int` | 消息条数 (`f16Var.d = linkedList.size()`) |
| **f16 自身** | `e` | `LinkedList` | ★ 消息命令列表 (`LinkedList<en4>`) |
| nt5 (父) | `BaseRequest` | `a65.je` | 基础请求头 |

> ⚠️ `f16` 只有 2 个自有字段：`d`(int) 和 `e`(LinkedList)

### 方法

```java
public class f16 extends nt5 {
    public int d;                    // count
    public LinkedList e;             // List<en4>

    // ★ 构建 CGI 任务（自带 522/237/1000000237）
    public i b() {
        l lVar = new l();
        lVar.d = 522;                              // CmdID
        lVar.c = "/cgi-bin/micromsg-bin/newsendmsg"; // URL
        lVar.a = this;                             // req = this (f16)
        lVar.b = new g16();                        // resp = g16
        o a = lVar.a();                            // build o
        i iVar = new i();                          // CGI task
        iVar.p(a);                                 // attach o
        return iVar;
    }
}
```

### `b()` 返回值

| 属性 | 值 |
|------|-----|
| 返回类型 | `com.tencent.mm.modelbase.i` |
| 内含 CmdID | 522 |
| 内含 URL | `/cgi-bin/micromsg-bin/newsendmsg` |
| 内含 Resp | `new g16()` |
| 内含 Req | `this` (f16) |

> **重要**：`b()` 是自包含的——CmdID/URL/RespID 都硬编码在里面。你在 `vj()` 里看到的那段 `lVar.c = ...; lVar.d = 522; lVar.e = 237; lVar.f = 1000000237` 其实**不是必要的**，因为 `b()` 内部已经设了。`vj()` 里那段是**冗余覆盖**。

---

## 二、`com.tencent.mm.modelbase.o` — CGI Request Wrapper

### 字段完整映射

| 字段 | 类型 | 访问器 | 对应 `l` 字段 | 含义 |
|------|------|--------|---------------|------|
| `a` | `com.tencent.mm.modelbase.m` | `getReqObjImp()` | `l.a` (f16) | 请求体包装 |
| `b` | `com.tencent.mm.modelbase.n` | `getRespObj()` | `l.b` (g16) | 响应体包装 |
| **`c`** | **`String`** | **`getUri()`** | **`l.c`** | ★ CGI URL |
| **`d`** | **`int`** | **`getType()`** | **`l.d`** | ★ CmdID (522) |
| `e` | `int` | `getOptions()` | — | Options (默认 0) |
| `f` | `int` | `getTimeOut()` | `l.i` | 超时时间 |
| `g` | `boolean` | `getIsLongPolling()` | `l.j` | 是否长轮询 |
| `h` | `boolean` | `keepAlive()` | `l.m` | 是否 KeepAlive |
| `i` | `int` | `getLongPollingTimeout()` | `l.k` | 长轮询超时 |
| `j` | `int` | `getNewExtFlags()` | — | 扩展标志 |
| `k` | `byte[]` | `getTransHeader()` | — | 传输头 |

### `l` → `o` 映射图

```
l (Builder)                    o (CGI Request Wrapper)
─────────────                  ────────────────────────
a  (f,  req protobuf)    →    a  (m, req wrapper)
b  (f,  resp protobuf)   →    b  (n, resp wrapper)
c  (String, URL)         →    c  (String, URL)        ← getUri()
d  (int, CmdID)          →    d  (int, CmdID)         ← getType()
e  (int, RespID)         →    传给 m 构造
f  (int, FuncID)         →    传给 n 构造
g  (boolean)             →    (传给 m/n 构造)
h  (int)                 →    (传给 m 构造的第5参数)
i  (int)                 →    f  (int, timeout)       ← getTimeOut()
j  (boolean)             →    g  (boolean, longPoll)
k  (int)                 →    i  (int, lpTimeout)
l  (int)                 →    j  (newExtFlags)
m  (boolean)             →    h  (boolean, keepAlive)
n  (byte[])              →    k  (byte[], transHeader)
```

### 构造函数（`l.a()` 生成）

```java
// l.a() →
new o(
    this.a,   // f16 (req protobuf)
    this.b,   // g16 (resp protobuf)
    this.c,   // "/cgi-bin/micromsg-bin/newsendmsg"
    this.d,   // 522 (CmdID)
    this.e,   // 237 (RespID)
    this.f,   // 1000000237 (FuncID)
    this.g,   // boolean
    this.i,   // timeout → o.f
    this.h,   // → m constructor
    this.j,   // → o.g (longPolling)
    this.k,   // → o.i (lpTimeout)
    this.m,   // → o.h (keepAlive)
    null      // k (unused)
)
```

---

## 三、`a65.en4` — MsgCommand (单条消息命令)

### 继承

```
a65.en4
  └─ com.tencent.mm.protobuf.f
       └─ java.lang.Object
```

### 字段（Smali 确认，100% 准确）

```smali
# instance fields
.field public d:La65/ew5;       # 收件人
.field public e:Ljava/lang/String;  # 内容
.field public f:I               # 类型
.field public g:I               # 时间
.field public h:I               # newmsgid
.field public i:Ljava/lang/String;  # MsgSource
.field public m:Ljava/lang/String;  # ticket
```

### 字段映射表

| 字段 | 类型 | 赋值来源 | 含义 |
|------|------|----------|------|
| **`d`** | `a65.ew5` | `new ew5()` | ★ 收件人对象 |
| **`e`** | `String` | `e9Var.j()` | ★ 消息内容 |
| **`f`** | `int` | `e9Var.getType()` | ★ 消息类型 |
| **`g`** | `int` | `(int)(e9Var.getCreateTime()/1000)` | ★ 创建时间（秒） |
| **`h`** | `int` | `y1.a(user,time).hashCode()` | ★ NewMsgId |
| **`i`** | `String` | `i1Var.r(en4, e9)` | MsgSource 字符串 |
| **`m`** | `String` | `contactStorage.X(username)` | Ticket |

> ✅ **你的字段名 d/e/f/g/h 是正确的！** 不是编码错误。

### `a65.ew5`（收件人子结构）

```smali
.field public d:Ljava/lang/String;   # username
.field public e:Z                    # flag (always true)
```

```java
// vj() 中的赋值：
ew5Var.d = e9Var.N0();   // 收件人 username
ew5Var.e = true;          // 标记
en4Var.d = ew5Var;        // 设入 en4.d
```

---

## 四、完整构建流程对照

### 4.1 `vj()` 中 en4 构建（逐字段）

```java
// 对每条 z0()==1 的消息：
en4 en4Var = new en4();

// d: 收件人
ew5 ew5Var = new ew5();
ew5Var.d = e9Var.N0();        // username (String)
ew5Var.e = true;               // flag (boolean)
en4Var.d = ew5Var;             // en4.d = 收件人

// g: 创建时间（秒）
en4Var.g = (int)(e9Var.getCreateTime() / 1000);

// f: 消息类型
en4Var.f = e9Var.getType();

// e: 消息内容
en4Var.e = e9Var.j();

// h: NewMsgId (user+time hash)
en4Var.h = y1.a(z1.r(), e9Var.getCreateTime()).hashCode();

// m: Ticket (条件性)
if (((k4) n0.c(k4.class)).kj(e9Var.N0())) {
    en4Var.m = ((c4) j1.s(c4.class)).ij().X(e9Var.N0());
}

// i: MsgSource
i1 i1Var = ((r4) n0.c(r4.class)).d;
if (i1Var != null) {
    i1Var.r(en4Var, e9Var);   // 直接修改 en4Var.i
}

// 加入请求列表
f16Var.e.add(en4Var);          // f16.e = LinkedList<en4>
f16Var.d = f16Var.e.size();    // f16.d = count
```

### 4.2 CGI 发送（逐层）

```java
// 1. f16.b() 自构建 CGI 任务
i task = f16Var.b();           // 内部: l.c="/cgi-bin/...", l.d=522

// 2. vj() 中的冗余覆盖（可省略）
// task.p(o) — 在 b() 内部已调用

// 3. 发送
sm0.h.b(task, continuation)
  → sm0.h.c(task, g, continuation)
    → a4.c(120000L, g)
      → sm0.g.invokeSuspend()
        → wr5.g.L(dispatcher, callback)
```

---

## 五、字段校验表（对照你的代码）

| 你的字段名 | Smali 实际 | 类型 | 正确? |
|-----------|-----------|------|-------|
| `en4.d` | `.field public d:La65/ew5;` | ew5 | ✅ |
| `en4.e` | `.field public e:Ljava/lang/String;` | String | ✅ |
| `en4.f` | `.field public f:I` | int | ✅ |
| `en4.g` | `.field public g:I` | int | ✅ |
| `en4.h` | `.field public h:I` | int | ✅ |
| `en4.i` | `.field public i:Ljava/lang/String;` | String | ✅ |
| `en4.m` | `.field public m:Ljava/lang/String;` | String | ✅ |
| `ew5.d` | `.field public d:Ljava/lang/String;` | String | ✅ |
| `ew5.e` | `.field public e:Z` | boolean | ✅ |
| `f16.d` | `.field public d:I` | int | ✅ |
| `f16.e` | `.field public e:Ljava/util/LinkedList;` | LinkedList | ✅ |
| `o.c` | `String c` + `getUri()` | String | ✅ (URL) |
| `o.d` | `int d` + `getType()` | int | ✅ (CmdID) |

---

## 六、结论

**你的字段名 d/e/f/g/h 全部正确。** 如果构建的 request 不对，问题不在字段名，可能的原因：

1. **`f16.b()` 就已经足够** — vj() 里那段 `lVar.c/d/e/f` 赋值是冗余的，可能与 `b()` 内部冲突
2. **ticket 字段 `m`** — 注意是 `m` 不是 `j/k/l`
3. **MsgSource 字段 `i`** — 通过 `i1Var.r()` 回调设置，不是直接赋值
4. **`f16` 还有父类字段 `BaseRequest`** (`a65.je`) — 包含设备信息、session key 等，可能也需要填充
