# 微信 8.0.76 Xposed 模块发送消息完整指南

> 生成时间：$(date '+%Y-%m-%d %H:%M:%S')
> 工具：LSPilot AI 分析助手

---

## 一、整条链路总览

```
┌──────────────────────────────────────────────────────────────────┐
│ ① 创建 e9 (MsgInfo)                                              │
│    new e9("123456@chatroom")  →  setContent("hello")             │
│    → setType(1) → setIsSend(1) → setStatus(1) → setCreateTime()  │
│                                                                  │
│ ② 插入 DB                                                        │
│    f9.I9(e9, true)  或  f9.H9(e9)  或  f9.O8(e9)                │
│                                                                  │
│ ③ SendMsgService 自动拉取                                        │
│    a21.b0.tj() 协程循环 → f9.z6(limit) → f9.O6()                │
│    SQL: status=1 AND isSend=1 AND type IN (1,11,21,31,36,...)   │
│                                                                  │
│ ④ 组装 CGI → 发送                                                │
│    a21.b0.vj(List) → f16 → en4 → sm0.h.b() → wr5.g.L()         │
│    POST /cgi-bin/micromsg-bin/newsendmsg  (cmd=522)              │
│                                                                  │
│ ⑤ 回调                                                           │
│    成功 → a21.b0.xj() → e9.t1(5)                                 │
│    失败 → a21.b0.yj() → SendMsgFailEvent                         │
└──────────────────────────────────────────────────────────────────┘
```

---

## 二、e9 (MsgInfo) 字段速查

### dm.c8 核心字段

| 字段名 | 类型 | getter | setter | 含义 |
|--------|------|--------|--------|------|
| `field_talker` | String | `N0()` | `y1(String)` | 会话ID（群: "xxx@chatroom"） |
| `field_content` | String | `j()` | `d1(String)` ★ | 消息内容 |
| `field_type` | int | `getType()` | `setType(int)` | 消息类型 |
| `field_isSend` | int | `z0()` | 无直接setter | 1=发送, 0=接收 |
| `field_status` | int | — | `t1(int)` | 状态: 1=待发送, 5=已发送 |
| `field_createTime` | long | `getCreateTime()` | `U0(long)` | 创建时间(ms) |
| `field_msgId` | long | `getMsgId()` | `L1(long)` | 消息ID(自增) |
| `field_imgPath` | String | `y0()` | `j1(String)` | 图片路径 |
| `field_fromUsername` | String | `x0()` | — | 发送者 |
| `field_toUsername` | String | — | — | 接收者 |

> ★ **警告**: `e9.d1(String)` 重写了 `dm.c8.d1()`，有 610 行复杂逻辑（处理 XML/appmsg/表情等）。纯文本可以直接调 `dm.c8.d1()` 或反射设 `field_content`。

### 消息类型常量

| type | 含义 |
|------|------|
| 1 | 文本 |
| 3 | 图片 |
| 34 | 语音 |
| 43 | 视频 |
| 47 | 表情 |
| 48 | 位置 |
| 49 | 文件/链接 |
| 436207665 | 小程序 |

### 可发送类型（来自 f9.O6 SQL）

```
type IN (1, 11, 21, 31, 36, 42, 66, 67, 48, 55, 57)
```

---

## 三、BSH 插件完整实现

### 3.1 发送纯文本

```bsh
import com.tencent.mm.storage.e9;
import com.tencent.mm.storage.f9;
import hm0.j1;
import sh3.c4;

// ===== 1. 获取 MsgInfoStorage =====
c4 c4Service = (c4) j1.s(c4.class);
f9 msgStorage = (f9) c4Service.wj();

// ===== 2. 创建消息 =====
String talker = "123456789@chatroom";  // 群ID
e9 msg = new e9(talker);               // 构造函数调用 y1() 设 talker

// 设置内容 — 直接调父类方法避免 e9.d1 复杂逻辑
callMethod(msg, "d1", "Hello from Xposed!");  // 或反射 field_content

// 设置类型
msg.setType(1);                         // 1=文本

// 设置 isSend
setIntField(msg, "field_isSend", 1);    // ★ 反射设置（无公开setter）

// 设置状态
msg.t1(1);                              // 1=待发送

// 设置时间
msg.setCreateTime(System.currentTimeMillis());

// ===== 3. 插入 DB =====
long newMsgId = msgStorage.I9(msg, true);
log("消息已插入: msgId=" + newMsgId);
// SendMsgService 会自动拉取并发送！
```

### 3.2 发送图文（图片消息）

```bsh
// 图片消息 type=3
e9 imgMsg = new e9("123456789@chatroom");
imgMsg.setType(3);                       // 图片类型
callMethod(imgMsg, "j1", "/sdcard/test.jpg");  // 设置图片路径
setIntField(imgMsg, "field_isSend", 1);
imgMsg.t1(1);
imgMsg.setCreateTime(System.currentTimeMillis());
long msgId = msgStorage.I9(imgMsg, true);
```

### 3.3 定时发送（使用 rv5.t0）

```bsh
import rv5.t0;

// 周期性发送（每 60 秒检查一次）
Runnable sendTask = new Runnable() {
    public void run() {
        // 创建并插入消息...
        e9 msg = new e9("123456789@chatroom");
        // ... 设置字段 ...
        msgStorage.I9(msg, true);
    }
};

// 初始延迟 5 秒，之后每 60 秒
callMethod(
    getStaticField("rv5.t0", "d"),  // rv5.t0.d 全局实例
    "d",                            // periodic 方法
    sendTask,
    5000L,                          // 初始延迟
    60000L                          // 周期
);
```

---

## 四、关键类/方法速查

### 获取服务

| 目标 | 代码 |
|------|------|
| MsgInfoStorage | `((c4) j1.s(c4.class)).wj()` → `f9` |
| SendMsgService | `a21.b0`（自动运行，无需手动调） |
| 联系人存储 | `((c4) j1.s(c4.class)).ij()` → `j4` |

### e9 构造

| 方式 | 代码 |
|------|------|
| 指定 talker | `new e9("user@chatroom")` |
| 空构造 | `new e9()` → 再 `y1(talker)` |

### 字段设置方式

| 字段 | 推荐方式 |
|------|----------|
| talker | `new e9(talker)` 或 `msg.y1(talker)` |
| content | `callMethod(msg, "d1", content)` 或反射 `field_content` |
| type | `msg.setType(type)` |
| isSend | **反射**: `setIntField(msg, "field_isSend", 1)` |
| status | `msg.t1(1)` |
| createTime | `msg.setCreateTime(time)` |
| imgPath | `callMethod(msg, "j1", path)` |

### 插入 DB

| 方法 | 签名 | 说明 |
|------|------|------|
| `I9` | `I9(e9, boolean) → long` | 插入并返回 msgId |
| `H9` | `H9(e9) → long` | 插入 |
| `O8` | `O8(e9) → long` | 插入 |
| `X9` | `X9(e9) → long` | 插入 |
| `Ta` | `Ta(long, e9, boolean) → int` | 更新 |

---

## 五、自动发送机制（无需手动触发）

### 5.1 `a21.b0.tj()` 循环

```
startSendLooper()
  └─ f9.z6(limit)
       └─ f9.O6(limit, talkers)
            └─ 遍历所有 talker:
                 ├─ ra(talker): 从 DB 查 pending 消息
                 ├─ yb(talker): 从缓存查
                 └─ else: SQL直查:
                      WHERE status=1 AND isSend=1
                        AND type IN (1,11,21,31,36,42,66,67,48,55,57)
                      ORDER BY createTime
                      LIMIT 100
            └─ 超时(>24h)消息标记 status=5
            └─ 返回 limit 条
```

### 5.2 必要条件

要消息被自动发送，必须满足：

```
✅ field_isSend  = 1       （标记为"发出"）
✅ field_status  = 1       （待发送状态）
✅ field_type    ∈ 可发送类型列表
✅ field_talker  非空       （有目标会话）
✅ createTime   < 24小时前  （否则被标记超时）
```

---

## 六、定时框架选择

| 场景 | 方案 |
|------|------|
| 简单延时发 | `n3.postDelayed(sendTask, delay)` |
| 周期性发 | `rv5.t0.d.d(sendTask, initDelay, period)` |
| 精确时间发 | `n3.sendEmptyMessageDelayed(...)` |
| 后台长期运行 | `rv5.t0.d.p("SendPool").execute(...)` |

---

## 七、BSH 完整示例（定时发文本到群）

```bsh
// ===== 保存为插件 main.java =====

import com.tencent.mm.storage.e9;
import com.tencent.mm.storage.f9;
import com.tencent.mm.sdk.platformtools.n3;
import hm0.j1;
import sh3.c4;

// 获取存储服务
c4 c4Service = (c4) j1.s(c4.class);
f9 msgStorage = (f9) c4Service.wj();

// 创建 Handler
n3 handler = new n3("SendMsgHandler");

// 发送函数
def sendTextMsg(String talker, String text) {
    e9 msg = new e9(talker);
    msg.setType(1);
    callMethod(msg, "d1", text);
    setIntField(msg, "field_isSend", 1);
    msg.t1(1);
    msg.setCreateTime(System.currentTimeMillis());
    long id = msgStorage.I9(msg, true);
    log("发送文本: talker=" + talker + " text=" + text + " msgId=" + id);
}

// 延时 10 秒发送
handler.postDelayed(new Runnable() {
    public void run() {
        sendTextMsg("123456789@chatroom", "定时消息测试");
    }
}, 10000L);

log("定时任务已设置，10秒后发送");
```

---

## 八、调试检查清单

如果消息没发出，逐项检查：

1. ✅ `field_isSend == 1`？（必须反射设置）
2. ✅ `field_status == 1`？（调了 `t1(1)`）
3. ✅ `field_type` 在可发送列表中？
4. ✅ `field_talker` 是正确的群ID格式（`xxx@chatroom`）？
5. ✅ 插入后 `msgId > 0`？（`I9` 返回正数）
6. ✅ `createTime` 是当前时间（非0、非未来）？
7. ✅ 账号已登录、网络已连接？
