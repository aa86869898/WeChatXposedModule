# WeChat 3180 专项反编译：更新拦截 / 红包AppMessage / 消息伪装冗余路径（v3 完整版）
> 独立 Xposed 模块使用。v3 = v2 勘误 + 本轮深挖补充（全部经 smali 方法体核对）。

> 命名提示：`te5 / dx0 / pc5 / im / v51 / x51` 等为 dex **根级扁平包**（无 `com/tencent` 前缀），用短名；`com.tencent.mm.ui.* / com.tencent.mm.sandbox.*` 用全路径。

## 0. 勘误/补充总表
- ① `NetSceneGetUpdateInfo`(te5.a) 回包 proto = **`pc5.zu3`**（字段 `m`=patchXml → `modelsimple.n1` 发起 patch 下载），非 pc5.su6；`lv4/o.rj(Lpc5/su6;)Z`=另一条 dialogInfo 判定。
- ③ `updateRequired` = `com.tencent.mm.ui.rc.b(...)`，上游调用链已挖出：`conversation.x5.onSceneEnd → x5.b(III)Z → rc.b → Updater.f(I)`。
- ⑥ `im.ec` 实锤 = **`WalletLuckyMoney` 表，`mNativeUrl TEXT PRIMARY KEY`**；XML tag 为小写 **`nativeurl`**，解析在 dx0 家族（`dx0.r.c/d`、`dx0.b.d`、`dx0.c.d`）。
- ⑦ `pc5.r66.b()` 自建 RR（newsendmsg/0x20a/modelbase/i）。
- 新补充：`pc5.zu3` 全字段、`Updater.onSceneEnd` 完整分支（含 Tinker 0xf3b 路径）、红包请求 XML 里的 `nativeUrl` 参数（`n6/h6.<init>`）。

---

## 1) `MicroMsg.Updater`
- **TAG 核心类全名：`com.tencent.mm.sandbox.updater.Updater`**
- 发起检查（type=0xb）：`Updater.c()V`（`modelbase.r1.a(0xb,this)`）、`Updater.d()V`（`modelbase.r1.q(0xb,this)`）
- **`Updater.onSceneEnd(IILjava/lang/String; Lcom/tencent/mm/modelbase/m1;)V` 完整逻辑**：
  - 成功：log `"isShow %s"`（字段 `f:Z`）；若 `f && e:u3(dialog)!=null` → show()；随后排 `b41/va(ue5/s0(updater,te5.a))` 经 `modelbase.r1.g(...)` 并 `d()` 复查
  - 失败：idkey 195/0x3e；隐藏 `dialog.e(ProgressBar)`(#0x7f0a49c3)；**`errType==4 && errCode==-0x12(-18)` → 发 `CheckTinkerUpdateEvent` + 以 type `0xf3b` 排 `ue5/t0`(字段 `n:u0`)**；其它错误 → 文案 `0x7f105b88`
- 有更新包可下：`com.tencent.mm.ui.lc.run()V`（SP `update_has_new_package` + `lv4/o.ij()`HashMap → wifi/GP → doAddDownloadTask）
- 插件更新服务 `lv4.o`（PluginUpdater）：`ij()`HashMap、`qj()Z`、`pj()Z`、**`rj(Lpc5/su6;)Z`（isApkHasUpdateInDialogInfo：`su6.h`=oldApkMd5、`su6.t`）**

## 2) `NetSceneGetUpdateInfo`
- **类全名：`te5.a`**（`.super modelbase.m1`，`.implements network.l0`）
- `<init>(I)V`；`getType()I`=**0xb(11)**；`doScene(network.s, modelbase.u0)I`
- **`onGYNetEnd(IIILjava/lang/String; Lcom/tencent/mm/network/y0; [B)V`**（TAG=`MicroMsg.NetSceneGetUpdateInfo`；idkey 195/7,8,9,0xb,0xc）
- **回包 `pc5.zu3`（11 字段）**：`d:I e:String f:I g:String h:I i:LinkedList m:String(patchXml) n:I o:String p:I q:pc5.p16`
  → `m`(patchXml) → `zk/m.a()` → `patchVersionCode` → `new com.tencent.mm.modelsimple.n1(deviceId, code)` → `modelbase.r1.g(n1)`（patch 下载）

## 3) `MicroMsg.MMErrorProcessor` / `updateRequired`
- 主类 `com.tencent.mm.ui.rc`；**`updateRequired` = `public static b(android.app.Activity, int, int, android.content.Intent) → boolean`**（日志字面 `"updateRequired [%d,%d] current version:%d channel:%d updateMode:%d"`）
- **上游调用链（已挖出）**：
  ```
  network onSceneEnd
    -> com.tencent.mm.ui.conversation.x5.onSceneEnd(IILjava/lang/String; Lcom/tencent/mm/modelbase/m1;)V
    -> com.tencent.mm.ui.conversation.x5.b(III)Z          # (errType,errCode,...)
    -> com.tencent.mm.ui.rc.b(Activity,int,int,Intent)Z   # updateRequired
    -> Updater.f(I)                                       # 进入更新流程
  ```
- rc.b 分发表：`errType==4→false`；`errCode==-0x11(-17)`→推荐更新分支（SP `recomended_update_ignore` 1 天去重 + updateMode 判断→`SubCoreSandBox.cj(0x7f105b99,oc,false)`→`Updater.f(mode)`→true，idkey 195/0x24）；`errCode==-0x10(-16)`→同弹窗路径→true；其它→false；静默 WiFi 分支 `SilentDownloadApkAtWiFi`→`cj(0x7f105b9a,dc,true)`→`Updater.f(p2)`（idkey 195/0x26/0x28）
- 相关：`rc.a(Activity)Z`（alpha 静默下载）；`lc.run()V`（执行下载）

## 4) `receivehongbao`
- 命中：`URISpanHandlerSet$LuckyMoneyUriSpanHandler(c/d)`、`viewitems.d4/h4(f0)`
- 解析类 **`dx0.r`**（`MicroMsg.AppMessage`，`.super Ldx0/i;`，192 字段）；解析 **static `dx0.r.v(Ljava/lang/String;)Ldx0/r;`**
- 消费/路由：`viewitems.d4.f0(Lgk5/d; Lcom/tencent/mm/storage/e9;)Z`（`"frhb://c2cbizmessagehandler/hongbao/receivehongbao"`）；URISpanHandler.c/d
- **结果对象 URL 承载字段**：`dx0.r.s1`（openNativeUrl rawUrl）、`j1`（空回退 `k`）、`A1`(billNo)
- **XML tag 为小写 `nativeurl`**：解析在 `dx0.r.c/d`、`dx0.b.d`、`dx0.c.d`；消费在 `LuckyMoneyBusiReceiveUI/BusiReceiveUIV2/BusiDetailUI/DetailUI/NewDetailUI/HKBeforeDetailUI.onCreate`

## 5) `startreceivebizhbrequest`
- 命中：`console.k0.e`、`ui.chatting.t1.a`、`viewitems.d4/h4(f0)`
- 解析：**`dx0.r.v(String)→dx0/r`**；路由 `t1.a(Ljava/lang/String;Context;MMFragment;Ljava/lang/String;)Z`（startreceivebizhbrequest → `key_native_url`+`key_way=5`+`key_static_from_scene=1` → `.ui.LuckyMoneyBusiReceiveUI`；openDetail → `key_native_url` → `.ui.LuckyMoneyBeforeDetailUI`）
- 承载：`dx0.r.s1` / `j1`|`k` / `A1`

## 6) `originXml` / `nativeUrl`
- 解析类 **`dx0.r`**（`v()` 含 `"originXml"/"parse msg failed"`；`c()/d()/t()/u()` 生成 `.msg.appmsg.*` XML）；包装 `com.tencent.mm.pluginsdk.model.app.w0`（a/b/c/d 静态 XML 拼装）、`k04.y2.g`、`vc2.c.c`
- **nativeUrl 字段名（三级）**：
  1. `dx0.r`：URL 承载 **`s1`**／**`j1`**（空回退 `k`）／`A1`(billNo)；XML tag=`nativeurl` 由 `dx0.r.c/d` 解析
  2. **字面字段 = `im.ec.field_mNativeUrl`**；`im.ec`=`WalletLuckyMoney` AutoDB（`mNativeUrl TEXT PRIMARY KEY`；列：hbType/receiveAmount/receiveTime/receiveStatus/sendId/sender/exclusiveUsername/hbStatus/invalidtime/msgLocalId/msgSvrId/rowid）
  3. intent extra key = **`key_native_url`**
- **收/开红包请求 XML 里也有 `nativeUrl` 参数**：`n6.<init>`（receivewxhb）与 `h6.<init>`（openwxhb）的请求键表均含 `nativeUrl`（另有 sendId/channelId/msgType/ver/sessionUsername/agreeDuty 等）——即 `AppMessage.nativeurl → dx0.r → UI → 收/开请求 nativeUrl` 全链路落点

## 7) `newsendmsg`（冗余路径全集）
| 类全名 | 继承/接口 | 构造器 | 发送/构造方法 |
|---|---|---|---|
| `v51.r0`（NetSceneSendMsg 主） | `.super modelbase.m1` / `.implements network.l0` | `()V`、`(J,I,String)V`、`(String,String,int,int,long,String)V`、`(String,String,int,int,Object,String)V` | `doScene(network.s, modelbase.u0)I`（newsendmsg/0x20a） |
| `com.tencent.mm.plugin.voip.model.y`（单条变体） | `.super modelbase.m1` / `.implements network.l0` | `(String,String,int,int)V` | `doScene(s,u0)I` |
| `x51.b0`（SendMsgService 现代发送器） | `.super jp0.o` / `.implements iy.j0` | `()V` | 无 doScene；`kj(List,Continuation)Object`（+mj/nj/lj/oj） |
| `pc5.r66`（SendMsgReq proto） | `.super pc5.vy5` | `()V` | **`b()Lmodelbase/i;`（自建 RR：newsendmsg/0x20a+`modelbase/i.r`）** |

## 8) `NetSceneSendMsg`（引用方全集）
| 类全名 | 构造器 | doScene | 继承 |
|---|---|---|---|
| `v51.r0`（主） | 4 个 | `doScene(s,u0)I` | m1 → l0 |
| `plugin.voip.model.y`（变体） | `(String,String,int,int)` | `doScene(s,u0)I` | m1 → l0 |
| `f51.b`（NetSceneSendMsgFake 本地重发） | `(String,String,String)` | `doScene(s,u0)I` | m1 → l0 |
| `v51.p0`（场景结束监听） | — | 无 doScene（onSceneEnd） | — |

## 9. 剩余未钉死点（如实标注）
1. **谁实例化 `v51.r0`（经典路径入口）**：降级指令扫描未捕获 new；已证现代主链不走它，经典路径待运行时日志确认。
2. **谁写 `WalletLuckyMoney`(im.ec) 记录**：表结构与字段已实锤；写入方应为 h6/n6 onGYNetEnd 或收红包 UI，未逐条追到（可用字段断点验证）。
3. **`update_has_new_package` 由谁写入**：lc.run 只读；写入方在 PluginUpdater/lv4.o 或 patch 下载完成回调，未追全。
> 这三条均不影响已给出的 Hook 落点（按需并列 Hook 即可）；如需把某条追到根，告诉我哪条，我单开一链挖。
