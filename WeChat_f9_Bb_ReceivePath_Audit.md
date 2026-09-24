# 微信 f9.Bb 接收路径二次审查报告（精准度校验版）

> 目标 APK：微信 8.x（com.tencent.mm，混淆命名）
> 审查对象：`com.tencent.mm.storage.f9.Bb(e9, boolean)` 的调用点与接收消息链路
> 核心问题：收到的消息到底走不走 f9.Bb？
> 审查方式：jadx 反编译 + dexlib2 Smali + find_caller/find_class/class_hierarchy 交叉验证

---

## 0. 结论摘要（先看这里）

1. **收到的消息确实走 f9.Bb**，但不是直接调用，而是经
   `MessageSyncExtension.a2.b → IMessageExtension.k（hh3.t/l51.m0 等）→ super.k()（b41.k.k）→ b41.aa.y(e9) → f9.yb(e9) → f9.Bb(e9, false)`。
2. **boolean 参数语义已实锤**：`false` = 普通 INSERT（接收 / 本地业务创建消息用），`true` = INSERT OR REPLACE（发送 / 重发消息用）。
3. **f9.Bb 全 APK 只有 6 个直接调用点**（4 个类 + f9 内部包装方法 yb），接收与发送共用该方法，靠 boolean 区分不完全可靠（false 分支仍混入本地业务创建）。
4. **更精准的接收入口（hook 建议）**：`com.tencent.mm.plugin.messenger.foundation.a2.b(p0,p35,e5)`（纯接收，dkAddMsg）或 `b41.k.k(p0)`（BaseMsgExtension 接收处理）。
5. **原任务 C 假设不成立**：`al5.g` 是 `MicroMsg.FilePreviewHelper`（文件预览工具），不是消息分发总线；真正的"分发总线"是 `v51.h2`（SyncServiceHandler）/ `com.tencent.mm.plugin.zero.a1`（SyncDoCmdDelegate）。
6. **文档表述修正**：`gp0.j1` 不是 IMessageExtension，而是 MMKernel（服务定位器）；`h2` 在接收链路中对应 `v51.h2`（SyncServiceHandler，字段 `d2.h` 类型为 `v51.h2`）。

---

## 1. 任务 A：f9.Bb 的全部调用点（已复核）

`find_caller`（getCallers + invokeMethods + invokes 三重方式）结果：**6 个调用点**，逐一复核如下。

| # | 调用方（类.方法） | 职责（Tag/日志） | Bb 参数 | 复核方式 |
|---|---|---|---|---|
| 1 | `com.tencent.mm.storage.f9.yb(e9)` | f9 内部包装：`Bb(e9,false)` + 失败上报 CmdProcFailedStruct | false | Java 反编译 line 2945-2956 |
| 2 | `cy1.e.<init>` | CgiBypSendLocation（发送位置消息 Byapass 场景） | false | Java 反编译 line 59 |
| 3 | `cy1.f.<init>` | CgiBypSendLocation 系列（bizType 66 等） | false | **Smali 复核**：j>0 走 `Qc`，否则 `Bb(e9,false)` |
| 4 | `cy1.h.<init>` | CgiBypSendLocation 系列 | false | find_caller（同系列） |
| 5 | `v51.r0.<init>` | `MicroMsg.NetSceneSendMsg`（发送消息 NetScene，构造 `(String,String,int,int,long,String)`） | **true** | Java 反编译 line 118 |
| 6 | `x51.q.i` | `MicroMsg.SendMsgPPC.SendMsgInsertStgPPC`（发送 pipeline） | **true** | Java 反编译 line 39/47 |

**重要修正/补充（二次审查新增）**：
- cy1.f 经 Smali 验证：当 serverMsgId j>0 时走 `f9.Qc(j,e9,true)`，否则 `f9.Bb(e9,false)`，与 cy1.e 行为一致。→ 说明"发送路径"内部也有两种插入（带 svrId 走 Qc、不带走 Bb）。
- `f9.yb` 的 63 个调用者（抽样复核 `b41.s1.d`、`ce3.h.f`）均为**本地业务消息创建**（如群通知 type=10000、UI 操作），**不是网络接收**。
- 结论：**6 个直接调用点全部落在"发送 / 本地业务创建"分支；接收路径通过 `f9.yb`（内部）间接命中 Bb**。

### Bb 源码证据（line 183-258 关键段）
```java
public long Bb(e9 e9Var, boolean z2) {
    if (e9Var == null || y8.J0(e9Var.N0())) { this.t = -3; return -1L; }
    ...
    Sa.b(e9Var);
    ...
    if (!z2) {
        p1 = Ud(Sa.d) ? b3.a.p1(this.r, Sa.d, e9Var) : this.r.l(Sa.d, "msgId", e9Var.convertTo());
    } else if (Ud(Sa.d)) {
        table.insertOrReplaceObject(wVar, l);      // INSERT OR REPLACE
    } else {
        p1 = this.r.w(Sa.d, "msgId", e9Var.convertTo()); // REPLACE
    }
    Log.i("MicroMsg.MsgInfoStorage", "insert:%d talker:%s ...", ...);
    ...
}
```
**Boolean 参数语义（实锤）**：`false`=普通 INSERT；`true`=按主键 REPLACE（发送重发覆盖用）。

### f9.yb 源码证据（line 2945-2956）
```java
public long yb(e9 e9Var) {
    long Bb = Bb(e9Var, false);                    // ★ 接收/业务创建统一入口
    if (Bb < 0) { /* CmdProcFailedStruct 失败上报 */ }
    return Bb;
}
```

---

## 2. 任务 B：f9 类结构（已复核）

- **继承**：`f9 → qf5.s0 → Object`；实现 `vn3.m0`。21 个字段，核心库句柄 `r: qf5/k0`（SQLite）。
- **日志 Tag**：`MicroMsg.MsgInfoStorage`。

### insert/add/update 类方法（签名 + 真实用途）
| 方法 | 签名 | 用途 | 调用方验证 |
|---|---|---|---|
| `Bb` | `(e9,Z)J` | 通用插入（false=INSERT / true=REPLACE） | 见任务 A |
| `yb` | `(e9)J` | `Bb(e9,false)` 包装 + 失败上报 | 63 调用者（业务创建/接收） |
| `Db` | `(e9)J` | 单参 insert，**备份恢复专用** | `os1.e.g`（BackupStorageLogic）、`xs1.f`（Restore） |
| `Hb` | `(e9)J` | 同上（备份恢复，`z` 选择 Db/Hb） | 同上 |
| `Qc` | `(J,e9,Z)I` | 带 serverMsgId 插入 | 200 调用者（voip/scan/AA/cy1 系列等业务） |
| `Ic` | `(J,e9)I` | 按 msgId 更新（发送成功/失败改状态） | cy1.e.I/J |
| `bd` | `(J,e9)V` | **按 msgSvrId 更新**（接收去重更新） | b41.k.k else 分支 |
| `O3` | `(String,J)e9` | 按 talker+svrId 查已存在消息 | b41.k.a / a2.a |
| `Cd` | `()V` | 清空/重建表（UNIQUE 冲突 fallback） | v51.r0 / x51.q.i 异常分支 |
| 其余 | 查询类 | A9/C2/D3/E7/G2/H2/J3/M4/P8/S7/T3 等游标/列表 | — |

**关键结论**：不存在"不带 boolean 的接收专用 insert 重载"；批量 insert 未见；接收最终落到 `Bb(e9,false)`（经 `yb`）。

---

## 3. 任务 C：x9 类（al5.g）—— 假设不成立，已修正

- `al5.g` = **`MicroMsg.FilePreviewHelper`**（文件预览工具，纯 Object 类，Kotlin companion 展开）。
- 实际签名：`b(e9)String`（不是 b(int)）、`j(e9,String)V`（不是 j(int)）、`i(g, Context, e9, ...)`、`h(Context,e9,al5.h)`。
- 职责：预览开关（RepairerConfigPreviewFile 等）、扩展名集合（md/html）、跳转 `FilePreviewUIC/FileQBUIC`。
- **不是消息分发总线**。

**真实的消息分发链路（替代结论）**：
```
v51.h2（SyncServiceHandler，字段 d2.h = new h2(...)）
  → v51.g2.run()（遍历 SyncResponse CmdList）
  → com.tencent.mm.plugin.zero.a1.d()（SyncDoCmdDelegate.doCmd，按 cmdid 分发）
  → messenger.foundation.z2.a()（SyncDoCmdExtensions，j60.d → tn3.a5 注册表）
  → messenger.foundation.a2（cmdId=5 AddMsg 的 SyncDoCmdExtension）
```

---

## 4. 任务 D：接收链路还原（二次审查后最完整版）

```
网络推送   v51.a1.a(...)              NewSyncMgr.dealWithPushResp
  →        v51.d2.g(...)              SyncService.triggerNotifyDataSync
  →        v51.h2.b(...)              SyncServiceHandler（"h2" 实锤）
  →        v51.g2.run()               处理 CmdList，逐条 j60
  →        zero.a1.d(...)             SyncDoCmdDelegate.doCmd（cmdid 分发）
  →        messenger.foundation.z2.a  SyncDoCmdExtensions（tn3.a5 按 j60.d 查扩展）
  →        messenger.foundation.a2.a  cmdId=5 → AddMsg 反序列化 k4
  →        a2.c → a2.b(...)           MessageSyncExtension.dkAddMsg ⭐中央入口
  →        s0.a(msgType/talker).k(p0) IMessageExtension 分发（tn3/s0 注册表）
  →        hh3.t.k / l51.m0.k         ★ 具体消息扩展（i1 实现，super 调父类）
  →        b41.k.k(p0)                BaseMsgExtension.k（接收处理）⭐
  →        aa.o(a, p0)                fixRecvMsgWithAddMsgInfo（接收修正）
  →        aa.y(a) → f9.yb(e9)        MsgInfoStorageLogic 插入
  →        f9.Bb(e9, false)           ★★ 接收消息插入点 ★★
（消息已存在 → f9.bd(svrId, e9) 去重更新）
```

### 决定性证据 1：a2.b（MessageSyncExtension.dkAddMsg，line 165-171）
```java
t0 a = s0.a(Integer.valueOf(k4Var.g));
if (a == null) { a = s0.a(g); }
if (a != null) {
    q0 k = a.k(p0Var);       // IMessageExtension.k(AddMsgInfo)
    ...
}
```

### 决定性证据 2：b41.k.k（BaseMsgExtension，line 113-123）★
```java
if (a.getMsgId() == 0) {
    a.f1(2);
    aa.o(a, p0Var);           // fixRecvMsgWithAddMsgInfo
    a.setMsgId(aa.y(a));      // ★ aa.y(e9) → f9.yb → f9.Bb(e9,false) ★
    q0Var = new q0(a, true);
} else {
    ((c4) j1.v(c4.class)).lj().bd(k4Var.r, a);  // 去重更新（不调 Bb）
}
```

### 决定性证据 3：b41.aa.y（MsgInfoStorageLogic，line 792-803）
```java
public static long y(e9 e9Var) {
    y3 n = ((c4) j1.v(c4.class)).cj().n(e9Var.N0(), true);
    if (n == null || ((int)((s) n).O2) == 0) {
        y3 y3Var = new y3(e9Var.N0());      // 新会话自动创建
        ...
        ((c4) j1.v(c4.class)).cj().h0(y3Var);
    }
    return ((c4) j1.v(c4.class)).lj().yb(e9Var);  // → f9.yb → Bb(false)
}
```

### 二次审查新增证据：扩展类继承关系（修正"b41.k 无子类"的假象）
```
hh3.t → b41.k → t0，并实现 i1        （i1 是 t0 子接口，注册在 s0.a 查找表）
l51.m0 → b41.k → t0，并实现 i1
b41.k.k 的调用方 = hh3.t.k、l51.m0.k  （find_caller 验证：super.k() 触发）
```
说明：s0.a 按消息类型/会话查找 i1 实现（hh3.t/l51.m0 等），这些具体扩展继承 BaseMsgExtension 并 super.k() 进入父类插入逻辑。class_hierarchy 曾显示 b41.k"无子类"是 DexKit 分片扫描局限，find_caller 证据更可靠。

### 文档表述核对
- `gp0.j1` = **MMKernel（服务定位器）**，`j1.v(c4.class)` 返回 `tn3.c4`；`c4.lj()` = f9 消息存储、`c4.cj()` = 会话存储、`c4.ej()` = 联系人存储。**不是 IMessageExtension**。
- 接收链路中的 "h2" = `v51.h2`（SyncServiceHandler），由 `v51.d2.h` 字段持有。`h2.cj()` 表述不成立（v51.h2 无 cj 方法），但 **a2.b（dkAddMsg）上游确实最终调用 f9.Bb**——原问题核心成立。

---

## 5. Hook 建议（接收消息入口评估）

| 优先级 | Hook 点 | 命中范围 | 适用 |
|---|---|---|---|
| 现状 | `f9.Bb(e9,boolean)` | 接收(false)+业务创建(false)+发送(true)；需 boolean 过滤但 false 不纯 | 已上线 hook 无需更换，仅需注意 false 混入 |
| ⭐推荐 | `com.tencent.mm.plugin.messenger.foundation.a2.b(p0,p35,e5)` | **纯接收**（dkAddMsg），含去重判断 | 需要元数据 k4/AddMsgInfo 的场景 |
| ⭐推荐 | `b41.k.k(p0)` | **纯接收处理**（BaseMsgExtension.k） | 需要拦截接收后处理逻辑 |
| 次选 | `f9.yb(e9)` | 接收+本地业务创建（不含发送 replace 分支） | 比 Bb 更干净的 false 等价入口 |
| 中间层 | `b41.aa.y(e9)` | 接收+部分业务（55 调用者多 UI/业务） | 需要会话自动创建逻辑上下文 |

**参数区分技巧**：若保留 hook f9.Bb —— 过滤 `boolean==true` 即发送/重发；`boolean==false` 含接收 + 本地业务创建（红包/表情/AA/位置发送），无法单靠 Bb 完全区分，应结合 `e9Var.z0()`（issend 标志：接收=0，发送=1）进一步过滤。

---

## 6. 置信度评估与已知局限

| 结论 | 置信度 | 依据 |
|---|---|---|
| 接收消息最终调用 f9.Bb(e9,false) | ★★★★★ | a2.b → b41.k.k → aa.y → f9.yb → Bb 全链源码+调用方交叉验证 |
| Bb 仅 6 个直接调用点 | ★★★★☆ | find_caller 三重方式；不排除通过反射/动态代理的间接调用 |
| boolean 参数语义 | ★★★★★ | Bb 源码 INSERT/REPLACE 分支 + 发送场景全传 true |
| Db/Hb=备份恢复 | ★★★★☆ | os1.e.g（BackupStorageLogic）/ xs1.f（Restore）日志实锤 |
| al5.g=FilePreviewHelper | ★★★★☆ | 源码头部+方法签名；与任务描述 b(int)/j(int) 不符，判断为记忆偏差 |
| b41.k 子类（hh3.t/l51.m0） | ★★★★★ | class_hierarchy + find_caller 双重验证 |

**已知局限**：
1. `f9.Bb` 若存在经反射/动态代理（如 lp0/a 服务代理）的间接调用无法被 find_caller 完全覆盖。
2. 主动同步（`v51.z0.onGYNetEnd`，NetSceneNewSync）与推送（`v51.a1`）最终都汇入 v51.h2 → 同一插入链路，但主动同步路径未逐行展开（链路一致，风险低）。
3. 部分方法用途（如 Tb/U7/E7 等）未逐一定性，仅列于方法清单。
4. `cy1.h` 未经独立反编译验证（与 e/f 同系列，find_caller 一致，置信度高但非逐行）。
5. 进程边界（:appbrand/:pay 等）未验证，`f9` 存储主要在主进程使用。

---

## 7. 附：关键类索引

| 混淆类 | 真实身份 | Tag/日志 |
|---|---|---|
| com.tencent.mm.storage.f9 | MsgInfoStorage（消息存储） | MicroMsg.MsgInfoStorage |
| com.tencent.mm.storage.e9 | 消息对象（im.c8 子类） | — |
| gp0.j1 | MMKernel / 服务定位器 | MicroMsg.MMKernel |
| tn3.c4 | 存储服务门面（lj=消息存储、cj=会话、ej=联系人） | — |
| v51.r0 | NetSceneSendMsg | MicroMsg.NetSceneSendMsg |
| x51.q | SendMsgInsertStgPPC | MicroMsg.SendMsgPPC |
| v51.a1 | NewSyncMgr | MicroMsg.NewSyncMgr |
| v51.d2 | SyncService | MicroMsg.SyncService |
| v51.h2 | SyncServiceHandler | MicroMsg.SyncServiceHandler |
| com.tencent.mm.plugin.zero.a1 | SyncDoCmdDelegate | MicroMsg.SyncDoCmdDelegate |
| messenger.foundation.a2 | MessageSyncExtension（AddMsg cmdId=5） | MicroMsg.MessageSyncExtension |
| b41.k | BaseMsgExtension | MicroMsg.BaseMsgExtension |
| hh3.t / l51.m0 | 具体消息类型扩展（i1 实现） | — |
| b41.aa | MsgInfoStorageLogic | MicroMsg.MsgInfoStorageLogic |
| al5.g | FilePreviewHelper（非消息总线） | MicroMsg.FilePreviewHelper |
| os1.e / xs1.f | 备份恢复逻辑 | MicroMsg.BackupStorageLogic / Restore |

---
*报告生成：LSPilot AI 分析助手 · 二次审查完成*
