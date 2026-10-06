# WeChat 3180 语音转发「点了发送但发不出」修复实现（v2 · 二次复检版）
> 场景：自家模块注入「语音转发」菜单(id=142) → 唤起微信原生选择器 → `MsgRetransmitUI.onActivityResult` 收到 **result=-1 但 data=null** → `this.h` 无接收人 → 静默不发送。
> 签名/字段均经 smali 实读；给出 4 个 hook 点最小可用实现（Java/Xposed，直接粘进你的模块）。

---

## 0. 根因（日志+静态双证）
```
14:07:50.731 onActivityResult recv: req=0 result=-1 data=null this=MsgRetransmitUI
```
1. `MsgRetransmitUI.onActivityResult(IIIntent)` 只在 `if (data != null)` 里 `getStringExtra("Select_Conv_User")` → 逗号 split → 写字段 **`h`**；
2. `result=-1` 说明**选择器确实调了 `setResult(RESULT_OK)`**（否则默认是 0），但**没带 `Select_Conv_User`**（data 为 null / 空 intent）；
3. `h` 为空 → 执行处日志 `doRetransmitOnSceneNormal: mUsernames isNullOrNil` → 什么都不发；
4. 静态佐证：`MvvmContactListUI.finish()`（只 `super.finish()`+动画）、`dr5.c.onClick($onCreate$2$1$1)`（只 `finish()`）、`onBackPressed()` —— 出口本身不放 key。**放 key 的动作不存在，所以只能"补结果"或"注目标"。**

---

## 1. 四个 Hook 点（签名都实读过）
| # | 类（dex 真名） | 方法 | 签名 | 已验证事实 | 用途 |
|---|---|---|---|---|---|
| H1 | `com.tencent.mm.ui.mvvm.MvvmContactListUI` | `setResult` | `(I)V` **和** `(ILandroid/content/Intent;)V` 两个重载都要拦 | `result=-1 data=null` ⟹ 被调，但无 extra | **A1 首选：补带 `Select_Conv_User` 的 Intent** |
| H2 | 同上 | `finish` | `()V` | 无 setResult | A2 等价兜底 + 缓存 lastPicker |
| H3 | **`a10/a`**（不是 `com.tencent.mm.feature.combine.CombineEntranceService`，后者只是其内的字符串 tag） | `cj` | `(Landroid/app/Activity;ILjava/util/ArrayList;Ljava/util/ArrayList;)V` | `args[2]`=选中 wxid 列表（intent key `selectContactUserList`），`args[3]`=exclude | 捕获选中结果（零反射，辅助） |
| H4 | `com.tencent.mm.ui.transmit.MsgRetransmitUI` | `onActivityResult` | `(IILandroid/content/Intent;)V` | 只在 `data!=null` 时读 `Select_Conv_User`→`h` | B 兜底：注入 `h` 后自行执行 |

---

## 2. 取"选中列表"的来源（smali 核实）
**来源① H3 参数（零反射，最先到手）**：`cj(activity, req, args[2], args[3])`，`args[2]`（ArrayList）＝选中 wxid。
**来源② MVI StateCenter（兜底，最可信）**：
```java
Object center = XposedHelpers.callMethod(act, "getStateCenter");              // of5/f
Object state  = center != null ? XposedHelpers.callMethod(center, "getState") : null;  // 实际类型 mr5/n0
Object picked = state != null ? XposedHelpers.getObjectField(state, "p") : null;       // java.util.LinkedList ← 选中列表
```
`mr5/n0` 33 字段：**`p:LinkedList`＝点选结果**；`E/z:List`；`o/n/q/L/N:HashSet`（exclude/其它，**别用错**）；`k(String,boolean)String`、`e/f/g/h/j(String)boolean` 为状态判定。
继承：`MvvmContactListUI → BaseMvvmListActivity → BaseMvvmActivity`（`getStateCenter()` 在最底层，公开）。

## 3. `MsgRetransmitUI` 侧成员（smali 核实）
| 成员 | 签名/类型 | 说明 |
|---|---|---|
| 字段 **`h`** | `Ljava/util/List;` | 选中联系人（**不是 String[]**） |
| 字段 `i` | `Ljava/lang/String;` | 转发内容（`Retr_Msg_content`/`_bytes`） |
| 字段 `e` | `I` | 消息类型（`Retr_Msg_Type`） |
| 方法 `g7` | `(Ljava/lang/String;)V` | 主执行（读 `this.i`/`this.h`；无接收人时只打日志） |
| 方法 `t7` | `(Ljava/lang/String;Ljava/util/List;)V` | 显式"内容+用户列表"（`public final`） |
| 类 | `.super com.tencent.mm.ui.MMBaseActivity` | |

---

## 4. 方案 A1（首选）：拦 `setResult` 把结果补全
```java
final Class<?> picker = XposedHelpers.findClass("com.tencent.mm.ui.mvvm.MvvmContactListUI", cl);

// (a) setResult(int) —— 空结果版
XposedHelpers.findAndHookMethod(picker, "setResult", int.class, new XC_MethodHook() {
    @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
        if ((int) p.args[0] != Activity.RESULT_OK) return;
        if (FwdFix.busy) return;  FwdFix.busy = true;
        try { FwdFix.upgrade((android.app.Activity) p.thisObject); p.setResult(null); }
        finally { FwdFix.busy = false; }
    }
});
// (b) setResult(int, Intent) —— 可能是 (RESULT_OK, null) 或空 extra（v2 新增，必须拦）
XposedHelpers.findAndHookMethod(picker, "setResult", int.class, android.content.Intent.class, new XC_MethodHook() {
    @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
        if ((int) p.args[0] != Activity.RESULT_OK) return;
        android.content.Intent d = (android.content.Intent) p.args[1];
        if (d != null && d.hasExtra("Select_Conv_User")) return;   // 已经带了，别动
        if (FwdFix.busy) return;  FwdFix.busy = true;
        try {
            if (FwdFix.upgrade((android.app.Activity) p.thisObject)) {
                p.setResult(null);                                // 阻断原来的 setResult(2参)
            }
        } finally { FwdFix.busy = false; }
    }
});

// 工具：把 state.p / 缓存 拼成 Select_Conv_User 并 setResult(-1, intent)；返回 true=已补
public static boolean upgrade(Activity act) {
    try {
        List<String> picked = pull();
        if (picked == null || picked.isEmpty()) picked = fromState(act);
        if (picked == null || picked.isEmpty()) { Log("[FwdFix][A1] no target, ignore"); return false; }
        Intent it = new Intent();
        it.putExtra("Select_Conv_User", TextUtils.join(",", picked));
        act.setResult(Activity.RESULT_OK, it);
        Log("[FwdFix][A1] upgrade setResult -> " + picked);
        return true;
    } catch (Throwable t) { Log("[FwdFix][A1] err " + t); return false; }
}
```
> 之后 `MsgRetransmitUI.onActivityResult` 自己能读到 `Select_Conv_User` → 填 `h` → **原生发送全跑通（含成功提示）**。最不怕改版。

## 5. 方案 A2（等价兜底）：`finish()` 后再补一次 + 缓存 lastPicker
```java
XposedHelpers.findAndHookMethod(picker, "finish", new XC_MethodHook() {
    @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
        FwdFix.lastPicker = (android.app.Activity) p.thisObject;   // 供方案 B 读 state
    }
    @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
        FwdFix.upgrade((android.app.Activity) p.thisObject);        // 覆盖"setResult 在 finish 之后才调"的顺序
    }
});
```

## 6. H3：捕获选中列表（零反射，辅助）
```java
XposedHelpers.findAndHookMethod(a10_a_CLASS, "cj",
    android.app.Activity.class, int.class, java.util.List.class, java.util.List.class,
    new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
            Log("[FwdFix][H3] cj req=" + p.args[1] + " sel=" + p.args[2] + " ex=" + p.args[3]);
            FwdFix.put((java.util.List<String>) p.args[2]);   // 静态缓存（消费即清）
        }
    });
```
> **类名用扁平包 `a10/a`**（DexKit 用 `usingStrings(".*CombineEntranceService.*")` 定），**不要** `Class.forName("com.tencent.mm.feature.combine.CombineEntranceService")`——那只是它内部的原始名 tag 字符串。
> H3 可能在最终确认前（拉二级选择器）先触发一次，所以**最终以 `fromState()` 为准，H3 仅补充**。

## 7. 方案 B（兜底）：往 `h` 注值并自行执行
```java
XposedHelpers.findAndHookMethod(
    XposedHelpers.findClass("com.tencent.mm.ui.transmit.MsgRetransmitUI", cl),
    "onActivityResult", int.class, int.class, android.content.Intent.class,
    new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
            if ((int) p.args[1] != Activity.RESULT_OK || p.args[2] != null) return;  // 只补 data==null
            List<String> picked = FwdFix.pull();
            if (picked == null || picked.isEmpty()) picked = FwdFix.fromState(FwdFix.lastPicker); // v2：用缓存的实例
            if (picked == null || picked.isEmpty()) { Log("[FwdFix][B] no target"); return; }

            p.setResult(null);                       // 阻断原逻辑：否则原方法尾部会读 data 的 extra → NPE，且会重复执行
            Object self = p.thisObject;
            XposedHelpers.setObjectField(self, "h", picked);
            try { XposedHelpers.callMethod(self, "g7", ""); }
            catch (Throwable t1) {
                XposedHelpers.callMethod(self, "t7",
                        XposedHelpers.getObjectField(self, "i"), picked);
            }
            Log("[FwdFix][B] injected h=" + picked + " type=" + XposedHelpers.getObjectField(self, "e"));
        }
    });
```
> B 会挡掉微信自己的成功提示，稳定性也不如 A；**仅在 A 全部失效时启用**。

---

## 8. 二次复检记录（v1→v2 改了什么）
| # | v1 问题 | v2 修正 | 依据 |
|---|---|---|---|
| 1 | A1 只拦 `setResult(int)` | **同时拦 `setResult(int,Intent)`** | Android 的 2 参版内部不走 1 参版；微信很可能用的是 `(RESULT_OK,null)`/空 Intent |
| 2 | 方案 B 用 `fromState(null)` | 增加 `lastPicker`（`finish()` 前缓存实例），B 从它读 state | 否则 B 上下文拿不到 StateCenter，必然空 |
| 3 | 把 `CombineEntranceService` 当类名 | 明确运行时类 = `a10/a`，点号名只是字符串 tag | WeChat 扁平包混淆，类名以 dex 真名为准 |
| 4 | 缺重入保护 | 加 `FwdFix.busy` 标志 | 3 参 setResult 会再触发 2 参 hook，防抖 |

**保留未改的已核事实**：`MvvmContactListUI.finish()`/`dr5.c.onClick`/`onBackPressed` 均无 setResult；`mr5/n0.p` 为 `LinkedList`（33 字段中唯一对应 `selectContactUserList` 的点选集合）；`a10/a.cj` 参数序 `(Activity,int,sel,ex)`；`MsgRetransmitUI.h` 是 `List`；`g7(String)V`/`t7(String,List)V`；原 `onActivityResult` 尾部会读 `data` 的 `KSendGroupToDo` → data==null 有 NPE 风险（所以 B 必须 `p.setResult(null)`）。

## 9. 已知坑
1. **别用 `state.o`**（exclude/禁用 HashSet）；选中是 **`state.p`**（LinkedList）。
2. `FwdFix` 缓存**消费即清**，并用 `msgId/talker` 校验，防连续转发串目标。
3. H3 可能在二级选择器阶段先触发一次 → 以 `fromState()` 为准。
4. 若 A1 之后微信又 `setResult(RESULT_OK)` 覆盖你 → A2 的 `afterHookedMethod` 已兜底。
5. `p.setResult(null)` 在 Xposed before 阶段会跳过原方法——B 方案必需（挡 NPE/双发），A 方案仅用于阻断那个"空 setResult"。
6. 若 `g7("")` 执行后仍无反应：查 `self.i`(内容)/`self.e`(type) 是否随 Intent 带入；策略按 msgType 在 `tn3/g4→k2.d` 取 `tn3/q4`，miss 会弹窗(`dialog/u3`)而不是发送。

## 10. 验收 checklist
- [ ] 日志 `[FwdFix][A1] upgrade setResult -> [wxid…]`（确定时触发）
- [ ] `MsgRetransmitUI.onActivityResult` 中 `data != null` 且含 `Select_Conv_User`
- [ ] 聊天窗口出现转发出去的语音；微信"发送成功"提示保留
- [ ] 取消/空选：无 upgrade、无注入、无异常
- [ ] 连点两次转发不串目标（缓存被正确清空）
