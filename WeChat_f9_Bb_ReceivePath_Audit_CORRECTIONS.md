# 反编译审计修正注记（对照既有文档）

> 依据 `WeChat_f9_Bb_ReceivePath_Audit.md` 的二次审查结论，修正既有实现文档中的三处表述误判。
> 仅修正「事实描述」，不动任何代码逻辑。

## 修正 1：x9（al5.g）的真实身份

- **审计结论**：`al5.g` = `MicroMsg.FilePreviewHelper`（文件预览工具），方法为 `b(e9)String` / `j(e9,String)V`，**不是消息分发总线**。
- **既有文档误判**：把 `al5.g` 当作「消息分发类 x9」，认为 `b(int)` / `j(int)` 是消息分发入口（v3 模块 MessageHook 曾按此 hook `al5.g`，实为白 hook，永远不触发）。
- **影响**：v3 MessageHook 的 x9 两路 hook（b/j）实际从未命中。09-23 日志中捕获的 `#1~#8` 消息**全部来自 f9.Bb hook**——这反向证明 f9.Bb 在 3180 上是真实且有效的接收入口。
- **真实分发链路**（审计 §3）：`v51.h2`(SyncServiceHandler) → `v51.g2.run()` → `plugin.zero.a1.d()`(SyncDoCmdDelegate) → `messenger.foundation.z2.a()` → `messenger.foundation.a2`(AddMsg cmdId=5)。

## 修正 2：gp0.j1 的定位

- **审计结论**：`gp0.j1` = `MMKernel`（服务定位器）。`gp0.j1.v(Class)` 返回对应服务；`j1.v(c4.class)` 返回 `tn3.c4`（存储服务门面）。
- **既有文档误判**：部分推导将 `gp0.j1` 直接等同 IMessageExtension 的持有者。
- **正确关系**：
  - `gp0.j1` = MMKernel（服务定位器）
  - `tn3.c4` = 存储服务门面：`c4.lj()` = f9 消息存储、`c4.cj()` = 会话存储、`c4.ej()` = 联系人存储
  - `c4` 由 `j1.v(tn3.c4.class)` 取得

## 修正 3：接收链路中的 h2

- **审计结论**：接收链路里的 `h2` 是 **`v51.h2` = SyncServiceHandler**（字段 `v51.d2.h` 持有），**不是** `h2.cj()` 形式。既有文档中 `h2.cj()` 表述不成立。
- **实锤接收链路**（审计 §0.1 / §4）：
  ```
  v51.a1.a → v51.d2.g → v51.h2.b → v51.g2.run → plugin.zero.a1.d
  → messenger.foundation.z2.a → messenger.foundation.a2.a
  → a2.c → a2.b(MessageSyncExtension.dkAddMsg) ⭐ 中央入口
  → s0.a(msgType/talker).k(p0)  (IMessageExtension 分发)
  → hh3.t.k / l51.m0.k → super.k() (b41.k.k BaseMsgExtension.k) ⭐ 接收处理
  → b41.aa.o(a,p0) fixRecvMsgWithAddMsgInfo
  → b41.aa.y(a) → f9.yb(e9) → f9.Bb(e9,false) ⭐⭐ 接收插入点
  （消息已存在 → f9.bd(svrId,e9) 去重更新，不调 Bb）
  ```

## 附：反编译报告给的更精准 hook 候选（未启用，仅备查）

| 优先级 | Hook 点 | 命中范围 |
|---|---|---|
| 现状 | `f9.Bb(e9,boolean)` | 接收(false)+业务创建(false)+发送(true)，需 issend 过滤 |
| ⭐ 推荐 | `messenger.foundation.a2.b(p0,p35,e5)` | 纯接收(dkAddMsg)，含去重判断 |
| ⭐ 推荐 | `b41.k.k(p0)` | 纯接收处理(BaseMsgExtension.k) |
| 次选 | `f9.yb(e9)` | 接收+本地业务创建(不含发送 replace 分支) |
| 中间层 | `b41.aa.y(e9)` | 接收+部分业务(55 调用者多 UI/业务) |

## 附：f9 类方法清单（审计 §2）

- 继承：`f9 → qf5.s0 → Object`；实现 `vn3.m0`(MsgInfoStorage 接口)；核心库句柄 `r: qf5/k0`
- insert 类方法：
  - `Bb(e9,Z)J` 通用插入（false=INSERT / true=REPLACE）
  - `yb(e9)J` `Bb(e9,false)` 包装 + 失败上报（63 调用者：接收/业务创建）
  - `Db(e9)J` / `Hb(e9)J` 备份恢复专用
  - `Qc(J,e9,Z)I` 带 serverMsgId 插入（voip/scan/AA 等业务）
  - `Ic(J,e9)I` 按 msgId 更新（发送成功/失败改状态）
  - `bd(J,e9)V` 按 msgSvrId 更新（接收去重更新）
  - `O3(String,J)e9` 按 talker+svrId 查已存在消息
- 布尔参数语义（实锤）：`false`=普通 INSERT（接收/本地业务创建），`true`=INSERT OR REPLACE（发送/重发）
