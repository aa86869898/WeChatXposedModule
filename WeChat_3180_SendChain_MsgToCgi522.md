# 发消息链深度解剖（3180）：MsgSendTask → SendMsgCgiFactory → SendMsgService/NetSceneSendMsg → cgi522
> 证据：DexKit + smali 取证；`[log]` 为用户模块运行日志实测。扁平包用短名（无 com/tencent 前缀）。
> 图例：✅已证实 ｜ ⚠️纠正 ｜ ❓推断

## 0. 关键结论
- 现代发送主链：`XxxMsgSendTask` → 构造 `SendMsgReq(pc5.r66)` → `SendMsgService x51.b0` 建 RR(newsendmsg/cgi 522) → 派发。
- ⚠️ `v51.q0` 不 `new v51.r0`（见 §3）——它是 v51.r0 的合成内部 Runnable，run() 实际发的是 `com.tencent.mm.modelsimple.l1`，仅通过字段 e 持有 r0。
- ⚠️ "没发送"根因 = 转发目标为空（MvvmContactListUI 回传 data=null），非 doScene 未命中（见 §4/§5）。
- ⚠️ v3.0.192 日志复现：FwdFix 装了但不回调 → 根因是 hook 装机原语被阉割（见 §5.1），**不是锚点/字段错误**。
- ❗字段确认：MsgRetransmitUI 存选中联系人的是 **`h:Ljava/util/List;`**（60 字段里第 38 个，onActivityResult 55 行 `ipub ->h`）；结果 key 仍是 **`Select_Conv_User`**（逗号分隔）。

## 1. 各环节（类名 / 签名 / 参数构造）
| # | 环节 | 类全名(dex真名) | 关键方法/签名 | 证据 |
|---|---|---|---|---|
| 1 | 发文本任务 | `com.tencent.mm.plugin.messenger.foundation.oh0.c` | `a(Ljava/lang/String;Ljava/lang/String;II;Ljava/util/Map;Ljava/lang/String;Ljava/lang/String;)` (TAG: MicroMsg.ChattingUI.SendTextComponent / SendTextLogic) | ✅[log] hook cls=oh0.c |
| 2 | 名片任务 | `com.tencent.mm.plugin.messenger.foundation.e10.h` | `u()->pc5.r66`(createCgiRequest)、`l(Continuation)`sendCgi、`k(Continuation)`sendBypCgi；TAG `MicroMsg.ContactCardMsg.ContactCardMsgSendTask` | ✅smali |
| 3 | 位置任务 | `com.tencent.mm.plugin.messenger.foundation.kb0.w0` | 同 #2；TAG `MicroMsg.LocationMsg.LocationMsgSendTask` | ✅smali |
| 4 | 发送管理器 | `qs5.v5`(SendMsgMgr) | `mj(String,String,int,int)`、`nj(...6...)`、`oj(...7...)`、`pj(...7...)` | ✅[log] hook cls=qs5.v5 |
| 5 | 发送工厂 | `v51.r1`(SendMsgCgiFactory) | `c(Lr96/l;)V`=executeByPPC(log 'executeByPPC() called with: content size = ..')；`a()->v51.n1`(news v51/n1) | ✅smali |
| 6 | 发送服务(现代) | `x51.b0`(SendMsgService) | `kj(Ljava/util/List;Lkotlin/coroutines/Continuation;)Object`、`lj(storage.e9)V`、`mj(List)V`、`nj(List)V` | ✅[log] hook cls=x51.b0 |
| 7 | SendMsgReq proto | `pc5.r66` | `b()->Lcom/tencent/mm/modelbase/i;` 自建 RR：l.a=pc5.r66 / l.b=pc5.s66 / l.c='/cgi-bin/micromsg-bin/newsendmsg' / l.d=0x20a / l.e=0xed / l.f=0x3b9acaed | ✅smali |
| 8 | 经典 NetScene | `v51.r0`(NetSceneSendMsg) | `getType()I=0x20a`、`doScene(network.s,modelbase.u0)I`、`onGYNetEnd(IIILjava/lang/String;network.y0;[B)V`；ctor 含 `(String,String,I,I,Ljava/lang/Object;String)V` | ✅smali/[log] |
| 9 | 变体 NetScene | `com.tencent.mm.plugin.voip.model.y` | 同 TAG `MicroMsg.NetSceneSendMsg`、doScene 内 `new modelbase.l`+const newsendmsg，ctor `(String,String,I,I)V` | ✅smali/[log] |
| 10 | Fake(本地插入) | `f51.b` | TAG `MicroMsg.NetSceneSendMsgFake`，ctor `(String,String,String)V` | ✅smali/[log] |
| 11 | 网络队列(入队) | `com.tencent.mm.modelbase.r1`（与 m1 同层） | `r1`=NetSceneQueue（StorageHub 实测绑定）；`m1.dispatch(network.s,network.y0,network.l0)I`=提交 | ⚠️推断 + ✅[log] readNetSceneQueue OK modelbase.r1 |

### 现代链（正常发送，命中 x51.b0）
```
MsgRetransmitUI.onActivityResult(data!=null, 有目标)
  -> XxxMsgSendTask ( u() 建 pc5.r66 ; l()/k() sendCgi )
  -> SendMsgService x51.b0.kj(List<e9>,Cont)   ( 或 executeByPPC -> v51.r1.c -> x51.y -> x51.b0 )
  -> 建 RR{newsendmsg,0x20a} -> modelbase 派发 -> cgi 522 -> pc5.s66 回包
```
### 经典链（冗余/兜底，命中 v51.r0 / plugin.voip.model.y）
```
(旧路径/重发) -> v51.r0 / plugin.voip.model.y doScene -> modelbase.l{newsendmsg,0x20a} -> m1.dispatch -> cgi 522
```

## 2. executeByPPC 与 new v51.r1 的关系
- `v51.r1.c(Lr96/l;)V` = executeByPPC：Log.i('executeByPPC() called with: content size=..') -> 读 isByp=storage.y3.I3(field b) -> 命中 branch 时 new `x51.y(v51/r1, long, r96/l, Continuation)` -> `xe5.i.c(Scope, null, x51.y, 1, null)` 投递协程。
- `v51.r1.a()->v51.n1`：new `v51/n1`，从插件 `vu1/l -> v51/m1` 取 `modelbase.m1`(NetScene)+long 装入 v51/n1.a/b。
- 结论：executeByPPC 本身不 new NetScene；NetScene 由插件产出，PPC 只投放 x51.y 协程。

## 3. v51.q0 在链中的真实位置（⚠️纠正：不在 new-v51.r0 -> queue.h 上）
```
.class Lv51/q0; .super Ljava/lang.Object; .implements Ljava/lang/Runnable;
.field d:Ljava/lang/String;
.field e:Lv51/r0;                       // 只持有 r0，不构造 r0
<init>(v51/r0; String)                  // 合成，从 v51.r0 内部生成
run():
    new com.tencent.mm.modelsimple.l1(int,String,String,String,String,boolean,int,boolean)
    e(v51/r0).dispatcher()              // 复用 r0 的 dispatcher
    new v51/p0
    modelsimple.l1.doScene(dispatcher, v51/p0)
```
它只是一个轻量旁路 Runnable（发 modelsimple.l1 —— “新消息通知/刷新”类场景），不是 NetSceneSendMsg 的构造点；Hook v51.q0 只能覆盖 modelsimple.l1 触发，覆盖不到正文转发。

## 4. 为什么“没发送成功”
[log] 15:48:31 -> 32 -> 33 实测：
```
31.428 onResume MsgRetransmitUI
31.518 onResume MvvmContactListUI            (第1级选择器)
32.920 onResume HalfScreenTransparentActivity(第2级)
33.817 onActivityResult req=128479597 result=-1 data=null this=MvvmContactListUI
33.848 onActivityResult req=0          result=-1 data=null this=MsgRetransmitUI   <-- data=null => 停留 i 兜底 => 无目标 => 不发送
```
根因：3180 新选择器 `MvvmContactListUI` 不回传 Intent（仅 setResult(RESULT_OK) 或空），`MsgRetransmitUI.onActivityResult` 里 `data==null` -> 跳过 `Select_Conv_User` 解析 -> `this.h` 空 -> 无接收人 -> 不发送。必须注入。

## 5. 转发修复（v3.0.192 日志定位，正确装机）

### 5.1 故障判定：不是锚点错，是"装机原语"在你环境不起作用
[log] 15:48:12 安装期：
```
[FwdFix] layer decl=com.tencent.mm.ui.transmit.MsgRetransmitUI hooked=onActivityResult
[FwdFix] hooked com.tencent.mm.ui.transmit.MsgRetransmitUI onActivityResult layers=4
[FwdFix] hooked framework Activity.onActivityResult
[FwdFix] hooked framework Activity.onResume (pick MvvmContactListUI)
```
但 15:48:31–33 转发全程**无任何 `[FwdFix]` 运行时日志**；同一时刻 CFLPMenu 的 onActivityResult 正常打印 4 次 `this=MsgRetransmitUI`。
另见日志反复出现：
```
XposedHelpers.findAndHookMethod(...) -> NoSuchMethodError  (cw intercept / A1 setResult / A2 finish / act / rtx 均中招)
```
→ 结论：你环境里的 `XposedHelpers`（static 重载）是残缺的，`XposedBridge.hookAllMethods` 装了也不回调。
**必须改用你模块里“已证明能回调”的钩子函数**（CFLPMenu / MessageMenuHook / WeChatIdInject / MsgForge 用的那个）。锚点/字段/注入逻辑都不用改。

### 5.2 已钉死的事实
- `MsgRetransmitUI` 自己声明了 onActivityResult（smali: `.method public onActivityResult(IILandroid/content/Intent;)V`）→ **钩 `MsgRetransmitUI.onActivityResult` 本身**即可（不要只钩框架 `android.app.Activity` 层）。
- 结果 key：`custom_send_text`、 **`Select_Conv_User`**、`KSendGroupToDo`、`KShowTodoIntroduceView`；
- 选中联系人落地字段：**`h:Ljava/util/List;`**（onActivityResult 55 行 `ipub ->h`；另有 `y:Ljava/util/List;` 为多选附加）。
- `data==null` 时，微信逻辑 = 不清空 `h`、继续走；因此**回填 data 或写 `h` 都能让原生继续发送**。

### 5.3 正确注入：回填 args[2]（连私有字段都不用碰，让微信原生自己跑完）
把 data 塞回 `p.args[2]`，微信原有 onActivityResult 会自己 `split(,)`、自己写 `h`、自己走完整发送链（以后微信改字段名也不影响）。

```java
// ======= 装机原语（务必换成你 CFLPMenu 里那行能用的钩子）======
interface WorkingHook {
    void hook(ClassLoader cl, String cls, String method, Object[] paramTypes, XC_MethodHook cb);
}
// 方案A（首选）：用你模块内置、已验证能回调的 hookMethod（CFLPMenu/MessageMenuHook 用的就是它）
WorkingHook HOOK = (cl, cls, m, pt, cb) -> YourHooks.hookMethod(cl, cls, m, pt, cb);
// 方案B（兜底）：XposedBridge.hookAllMethods + XposedHelpers.findClass
// WorkingHook HOOK = (cl, cls, m, pt, cb) -> XposedBridge.hookAllMethods(XposedHelpers.findClass(cls, cl), m, cb);
// 方案C（已知在你环境报 NoSuchMethodError，最后才试）
// WorkingHook HOOK = (cl, cls, m, pt, cb) -> { try { XposedHelpers.findAndHookMethod(cls, cl, m, cb); } catch (Throwable t){ XposedBridge.log("[FwdFix] install err "+t);} };

// ======= 安装 =======
public void installFwdFix(ClassLoader cl) {
    HOOK.hook(cl, "com.tencent.mm.ui.transmit.MsgRetransmitUI", "onActivityResult",
        new Object[]{ int.class, int.class, android.content.Intent.class }, new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam p) {
            if (p.thisObject == null) return;
            if (!"com.tencent.mm.ui.transmit.MsgRetransmitUI".equals(p.thisObject.getClass().getName())) return;
            int rc = (Integer) p.args[1];
            android.content.Intent data = (android.content.Intent) p.args[2];
            XposedBridge.log("[FwdFix][B] fired this=MsgRetransmitUI rc=" + rc + " data=" + data);
            if (rc != android.app.Activity.RESULT_OK) return;   // 只在“确定”时补
            if (data != null) return;                            // 原生已带回目标, 不插手
            java.util.List<String> picked = FwdCache.picked;
            if (picked == null || picked.isEmpty()) { XposedBridge.log("[FwdFix][B] 无目标, 放弃"); return; }
            android.content.Intent faked = new android.content.Intent();
            faked.putExtra("Select_Conv_User", android.text.TextUtils.join(",", picked));
            p.args[2] = faked;                                   // 原生继续：自己写 h、自己发送
            XposedBridge.log("[FwdFix][B] 已回填 Select_Conv_User=" + picked);
        }
    });
}
```
> 老版（v0）直接反射写 `h:` 也可，但“`ipub ->h` 回填 args[2]” 更抗混淆、且保留微信原生成功提示。若你坚持写字段：`p.thisObject.getClass().getDeclaredField("h").set(p.thisObject, picked)`。

### 5.4 抓目标：钩 MvvmContactListUI.finish()（它声明了 finish()V，确定/关闭一刻 state 即最终勾选）
```java
HOOK.hook(cl, "com.tencent.mm.ui.mvvm.MvvmContactListUI", "finish",
    new Object[]{}, new XC_MethodHook() {
    @Override protected void beforeHookedMethod(MethodHookParam p) {
        try {
            Object center = p.thisObject.getClass().getMethod("getStateCenter").invoke(p.thisObject);
            Object st = center.getClass().getMethod("getState").invoke(center);   // mr5/n0
            if (st != null) {
                java.lang.reflect.Field fp = st.getClass().getDeclaredField("p"); // LinkedList<String> 选中
                fp.setAccessible(true);
                Object v = fp.get(st);
                if (v instanceof java.util.List && !((java.util.List<?>) v).isEmpty()) {
                    java.util.List<String> out = new java.util.ArrayList<>();
                    for (Object o : (java.util.List<?>) v) out.add(String.valueOf(o));
                    FwdCache.picked = out;
                    XposedBridge.log("[FwdFix][A] picked=" + out);
                }
            }
            // 若 p 取不到或为空, dump 全字段定位（只需跑一次）
            for (java.lang.reflect.Field f : st.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                XposedBridge.log("[FwdFix][STATE] " + f.getName() + " : " + f.getType().getSimpleName() + " = " + f.get(st));
            }
        } catch (Throwable t) { XposedBridge.log("[FwdFix][A] err " + t); }
    }
});
```
- 状态类 `mr5/n0`：`p:Ljava/util/LinkedList`(选中列表) / `o:Ljava/util.HashSet`(排除/不可选)。若某版字段名变，看 `[FwdFix][STATE]` 里那个 `LinkedList<String>`/内容为 wxid 的字段。

### 5.5 30 秒自证（先只挂这一条）
```java
HOOK.hook(cl, "com.tencent.mm.ui.transmit.MsgRetransmitUI", "onActivityResult",
    new Object[]{ int.class, int.class, android.content.Intent.class }, new XC_MethodHook() {
    @Override protected void beforeHookedMethod(MethodHookParam p) {
        XposedBridge.log("[FwdFix][TEST] FIRED this=" + p.thisObject.getClass().getName()
            + " req=" + p.args[0] + " rc=" + p.args[1] + " data=" + p.args[2]);
    }
});
```
- 打印 `[FwdFix][TEST]` → 原语 OK，接 5.3/5.4 即通；
- 不打印 → 原语问题，把 CFLPMenu 里 install_activity_result 的那一行贴我，逐字对齐。

## 6. Item6 主锚建议
- 追踪“发送成功/失败”经典路径 → `v51.r0.onGYNetEnd`（你已 hook，旧/重发路径）。
- 现代普通发送真正出口 → `x51.b0.kj`（SendMsgService，建 RR+派发）与 `v51.r1.c`(executeByPPC)。
- 冗余兜底 → `com.tencent.mm.plugin.voip.model.y`、`f51.b`。
- 转发目标注入 → `MsgRetransmitUI.onActivityResult`（data==null 时回填 `Select_Conv_User` / 写 `h`）。

---

## 5A. 用你已验证的原语（getDeclaredMethod + XposedBridge.hookMethod）的正确装机
用户已确认主路径原语 = `XposedBridge.hookMethod(java.lang.reflect.Method, XC_MethodHook)`（Method 来自 getDeclaredMethod / 继承链逐层），findAndHookMethod/hookAllMethods 未用。
→ 既然**原语与 CFLPMenu/MessageMenuHook 完全一致**，FwdFix v3.0.192 仍不回调，问题只在 **Method 取层 / 安装时机 / 回调早退**，不在原语。按下面写，**第一行日志不加任何守卫**，装机时打目标层+classloader：

```java
private static final ClassLoader CL = /*你的宿主 ClassLoader*/;

private static void installRetransmit() {
    try {
        Class<?> c = CL.loadClass("com.tencent.mm.ui.transmit.MsgRetransmitUI");
        Method m = c.getDeclaredMethod("onActivityResult",
                int.class, int.class, android.content.Intent.class);
        // 装机即诊断：确认钩的是 MsgRetransmitUI 自身那一层
        XposedBridge.log("[FwdFix] target=" + m
                + " declClass=" + m.getDeclaringClass().getName()
                + " declCL=" + m.getDeclaringClass().getClassLoader());
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                XposedBridge.log("[FwdFix][B] ENTER this=" + p.thisObject.getClass().getName()
                        + " req=" + p.args[0] + " rc=" + p.args[1] + " data=" + p.args[2]);   // ← 第一行，无守卫
                if ((Integer) p.args[1] != android.app.Activity.RESULT_OK) return;
                if (p.args[2] != null) return;
                java.util.List<String> picked = FwdCache.picked;
                if (picked == null || picked.isEmpty()) { XposedBridge.log("[FwdFix][B] 无目标"); return; }
                android.content.Intent faked = new android.content.Intent();
                faked.putExtra("Select_Conv_User", android.text.TextUtils.join(",", picked));
                p.args[2] = faked;                                    // 原生继续：自己写 h、自己发
                XposedBridge.log("[FwdFix][B] injected=" + picked);
            }
        });
    } catch (Throwable t) { XposedBridge.log("[FwdFix] install err " + t); }
}

private static void installPicker() {
    try {
        Class<?> c = CL.loadClass("com.tencent.mm.ui.mvvm.MvvmContactListUI");
        Method m = c.getDeclaredMethod("finish");
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    Object center = c.getMethod("getStateCenter").invoke(p.thisObject);
                    Object st = center.getClass().getMethod("getState").invoke(center);
                    for (java.lang.reflect.Field f : st.getClass().getDeclaredFields()) {   // dump 一次定位字段名
                        f.setAccessible(true);
                        XposedBridge.log("[FwdFix][STATE] " + f.getName() + " : " + f.getType().getSimpleName() + " = " + f.get(st));
                    }
                    java.lang.reflect.Field fp = st.getClass().getDeclaredField("p");       // LinkedList<String> 选中
                    fp.setAccessible(true);
                    Object pv = fp.get(st);
                    if (pv instanceof java.util.List && !((java.util.List<?>) pv).isEmpty()) {
                        java.util.List<String> out = new java.util.ArrayList<>();
                        for (Object o : (java.util.List<?>) pv) out.add(String.valueOf(o));
                        FwdCache.picked = out;
                        XposedBridge.log("[FwdFix][A] picked=" + out);
                    }
                } catch (Throwable t) { XposedBridge.log("[FwdFix][A] err " + t); }
            }
        });
    } catch (Throwable t) { XposedBridge.log("[FwdFix] installPicker err " + t); }
}
```

### 诊断表（照这个走，一轮定位）
| 现象 | 结论 | 处置 |
|---|---|---|
| 装机能打印 `target=...declClass=MsgRetransmitUI`，但**无 `[FwdFix][B] ENTER`** | 该回调没被回调（版本/链特殊） | **Piggyback**：把上面注入逻辑直接塞进 CFLPMenu 已有的 `MsgRetransmitUI.onActivityResult` before 里（日志已证它回调） |
| 有 ENTER，但 `rc`/`data` 与预期不同 | reqCode/resultCode 分支 | 看 rc 再分支；或干脆用 `installPicker` 抓目标后**直接驱动发送**，不走 onActivityResult |
| 有 ENTER 且 `injected=[...]` 但仍不发 | 注入时机/发送链问题 | 改钩 `x51.b0.kj(List,Continuation)` 或 `v51.r1.c(Lr96/l;)V` 直接发 |

### Piggyback（保底，最省事）
CFLPMenu 已成功钩中 `MsgRetransmitUI.onActivityResult`（日志 `onActivityResult recv: req=0 result=-1 data=null this=MsgRetransmitUI` 即证据）。把 5A 的注入段（`Select_Conv_User` 回填 `p.args[2]`）直接加进 CFLPMenu 那个 before 分支，别另起炉灶。
