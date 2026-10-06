# WeChat 3180 语音转发目标链 · 最终版（二次深挖+修正）
> MsgRetransmitUI / MvvmContactListUI / viewitems.bq(Q) / CombineEntranceService(a10/a)
> 独立 Xposed 模块适用；全部结论经 smali 方法体核对。

## 0. 五项核验状态
| # | 目标 | 状态 | 结论 |
|---|---|---|---|
| 1 | MsgRetransmitUI.onActivityResult | ✅ 600 行全过 | key 仍为 `Select_Conv_User`（逗号串）→ `this.h`；data==null 时整块跳过 |
| 2 | MsgRetransmitUI 字段 | ✅ | 选中目标 = **`h: java.util.List`**（非 String[]） |
| 3 | MvvmContactListUI | ✅ | `BaseMvvmListActivity→BaseMvvmActivity`；**finish/关闭/返回全不 setResult ← data=null 根因** |
| 4 | bq(Q) 142 收口 | ✅ | 142(0x8e)→packed-switch→`:pswitch_105`→拉 MsgRetransmitUI；e9 从 `am5/d.b` 取 |
| 5 | 拉起选择器方式 | ✅ 大半 | `a10/a`(CombineEntranceService).cj → `startActivityForResult`，req **0xfac(4012)** |

---

## 1) MsgRetransmitUI.onActivityResult(IILandroid/content/Intent;)V（600 行）
- **实际读的 extras（全部来自 `data`）**：
  | key | 读取方式 | 去向 |
  |---|---|---|
  | `custom_send_text` | `getStringExtra` | 局部变量 customText |
  | **`Select_Conv_User`** | `getStringExtra` → `split(",")` → `y8.P1(Array)→ArrayList` | **★ `this.h: List`** |
  | `KSendGroupToDo` | `getBooleanExtra` | `this.Y: boolean` |
  | `KShowTodoIntroduceView` | `getIntExtra` | 局部 |
- **控制流（修正版）**：
  1. `resultCode == -1` → 服务回调 `iy/f0→hy/b0.bj()`
  2. **`if (data == null)`**：`custom_send_text`/`Select_Conv_User` 读取整块跳过，**`this.h` 保持原值（不清空、不赋值）**，customText=""
  3. `this.h` 非空且 `this.U`(多选标志) → 上报 `MultiMessageForwardStruct`（`ToUsername = h 以";"拼接`，`f=1/2`）
  4. `resultCode == -1` 分支内做 `dx0/r.v(this.i)` 解析用于**上报统计**（type==5 → URLEncoder+idkey 0x3442；type==0x21 → AppBrandOuterMenuClickReportEvent）
  5. 汇聚点 `cond_190`：日志 **`"onActivityResult doRetransmit msgType[%d], iScene[%d], size[%d]"`（size = `this.h.size()`）→ 读 `KSendGroupToDo/KShowTodoIntroduceView` → 取策略执行
- **执行尾段**：`tn3/g4→k2` 的 `d: ConcurrentHashMap<Integer, tn3/q4>` 按 `this.e`(msgType) 取**转发策略 `tn3/q4`**；无策略则弹 `dialog/u3`（string 0x7f100542）后执行。

> ⚠ 上次口述把 ④ 说反了："解析 this.i" 属 **resultCode==RESULT_OK 的上报分支**，不是"失败分支"；真正执行在 ⑤ 的汇聚点，且读 `data` 的扩展（data==null 时这些读取路径基本不可达 → 与"点了发送但无转发"现象吻合）。

## 2) MsgRetransmitUI 字段与方法（60 字段，关键如下）
- **`h: Ljava/util/List;`** ← **选中联系人（wxid 列表，ArrayList<String>）**
- `i: String`（`Retr_Msg_content`/`_bytes`）、`e: int`（`Retr_Msg_Type`）、`f: long`（`Retr_Msg_Id`）、
  `m: ArrayList`（`Retr_Msg_Id_List`）、`x: int`（`Retr_Scene`）、`U/Z`(多选/标志)、`Y: boolean`（KSendGroupToDo）、`L1: q3`(SP)
- 可注入执行的方法：
  - **`t7(Ljava/lang/String; Ljava/util/List;)V`**：显式"内容串 + 用户列表"转发（内部 `g1.a(String)→HashMap`、`y3.V4(wxid)` 判群、单群且 map 非空时 `v51/t1.a(wxid)`）
  - `g7(Ljava/lang/String;)V`：`doRetransmitOnSceneNormal`（内部读 `this.h`，空则日志 `mUsernames isNullOrNil`）
  - `v7(Intent, String)V`、`j7(List)→String`、`i7(String)V`、`a7(v51/n1)V`、`s7(String, dx0/r, byte[], e9)V`
- 继承：`com.tencent.mm.ui.MMBaseActivity → androidx.appcompat.app.AppCompatActivity`

## 3) MvvmContactListUI（选择器）
```
com.tencent.mm.ui.mvvm.MvvmContactListUI
  └─ com.tencent.mm.plugin.mvvmlist.BaseMvvmListActivity
       └─ com.tencent.mm.plugin.mvvmbase.BaseMvvmActivity
```
**不是 SelectConversationUI 子类/改名**（旧版 `com.tencent.mm.ui.contact.SelectContactUI` 仍存在且仍用 Select_Conv_User）。

**data=null 根因（实锤）**——选择器侧出口均无 setResult：
- `MvvmContactListUI.finish()`：仅 `super.finish()`+`overridePendingTransition`（动画），**无 setResult**
- `dr5.c.onClick`(`$onCreate$2$1$1`，关闭/取消)：仅 `finish()`
- `onBackPressed()`：`hideVKB()`+派 `mr5/a` 意图+`super.onBackPressed()`，**无 setResult**

**选中态与组件**：
- MVI State：**`mr5/n0`** —— `p: LinkedList`（选定用户）+ `o: HashSet`，经 `BaseMvvmActivity.getStateCenter().getState()`
- 界面/VM：`jm/y2`（`d: Button`＝确定按钮、`m/s: MultiSelectContactView`＝已选面板、`f/w: WxRecyclerView`、`k: CheckBox`、`o: LabelContainerView`…）
- UIC/点击器（均持 `MvvmContactListUI` 字段 d）：
  | 类 | 来源名 | 行为 |
  |---|---|---|
  | `dr5.a` | — | invoke()：读 `INTENT_KEY_KEEP_ACTIVITY_WHEN_BACK_PRESSED` |
  | `dr5.b` | — | `g(View)` 空；`n(View)` 设背景色(0x7f06000c) |
  | **`dr5.c`** | `$onCreate$2$1$1` | **`finish()`（关闭）** |
  | **`dr5.d`** | `$onCreate$2$1$6` | **取 `state.p/state.o` → `a10/a.cj(activity, 0xfac, list, list)`（= startActivityForResult）** |
  | `dr5.e/f` | — | `onClick(DialogInterface,int)` 空 |
  | `dr5.h/i/j` | — | invoke()：`KOrientation` 等配置读取 |

**启动/组装服务 `a10/a` = `com.tencent.mm.feature.combine.CombineEntranceService`**：
- `bj(Activity, ArrayList, ArrayList) → Intent`：构建选择器 Intent，keys：`list_type/menu_mode/already_select_contact/always_exclude_select_contact/block_contact/default_multi_search/need_show_multiSelect_bottom/need_show_expand_btn/recommend_chatroom/key_confirm_menu_name/filehelper/weixin`
- `cj(Activity, int req, ArrayList, ArrayList) → void`：**`activity.startActivityForResult(intent, req)`**，keys：`selectContactUserList/excludeContactUserNameList/min_limit_num/titile/visibility_init_title_name/key_check_box_color/key_confirm_menu_color/enter_search_mode/goToContactSearchFromCombine`

## 4) bq.Q（语音长按菜单收口，TAG=`MicroMsg.ChattingItemVoice`）
- **签名：`Q(android.view.MenuItem, gk5.d, am5.d) → boolean`**（第三个参数是 `am5/d`，不是 e9）
- e9 取法：`p3.d.hn5.a.b`（`am5/d → hn5/a → storage/e9`）
- **142(0x8e) 确认真经过 `bq.Q`**：
```
packed-switch 0x8d: { 0x8d(141)→:pswitch_171,  0x8e(142)→:pswitch_105,  0x8f(143)→:pswitch_cc }
（另: 0x77-0x79→pswitch_c0/b4/61；0xa3-0xa5→pswitch_1f2/1f2/180；单判 0x64/0x67/0x7c/0x89/0x98/0xb3/0xb5）
```
- `:pswitch_105`（转发）：`new Intent(activity, MsgRetransmitUI.class)` + `Retr_Msg_content`(经 `b0.C(gk5.d,e9)` 取文本) + `Retr_Msg_Type`(6/4，按 `e9.V2()`) + `scene_from=0x11` → **`gk5/d.b0(intent)`** 启动；随后 `nq.d(9, e9)` 上报
- ⚠ 全程被 **`ym0/a`** 插桩包裹（3180 µ-hook；hook 时栈里有 ym0 帧属正常）
- → 你 hook `bq.Q` 不触发，优先查签名（第三参应为 `am5/d`）；并并列 hook `com.tencent.mm.ui.chatting.viewitems.r0.onMMMenuItemSelected(android.view.MenuItem,int)V` 兜底

## 5) 拉起选择器（req / 内容）
- 语音菜单 → MsgRetransmitUI（`Retr_*` extras）→ 经 `a10/a.bj/cj` 发起选择器：**`startActivityForResult(intent, reqCode)`，实测一处 req=`0xfac`(4012)**
- intent keys 见 §3 的 bj/cj 列表
- 结果回传：选择器侧**未发现 setResult**（§3 实锤）→ **MsgRetransmitUI.onActivityResult 收到 data==null**

## 6) 方案B落地（比读 data 更稳，可直接用）
1. **hook `MsgRetransmitUI.onActivityResult`**：入口读 `this`(Activity) 的字段 **`h`**（`List<String>`）→ **与 data 解耦**，data==null 也能拿到目标；
2. 若 `h` 为空：反射写 `h` 后调 **`t7(String, List)`** 显式执行（或用 `g7(String)`，它会读 `this.h`）；
3. 转发策略若按 msgType 查不到（`tn3/q4` map miss）会走弹窗——注入时保持 `this.e`(msgType)/`this.i`(content) 原值即可命中策略；
4. 142 收口 hook 用 `bq.Q(MenuItem, gk5.d, am5.d)`，e9 从 `p3.d.b` 取。
5. 兜底 dump：hook `MvvmContactListUI.finish()` + `getStateCenter().getState()` 打 `mr5/n0.p`（最终选定列表），hook `a10/a.bj` 打出 Intent 全 extras —— 若发现与当前不同的 key，据实替换。

## 7. 本轮修正/补充（相对上一条口述）
- **修正**：`this.i` 的 `dx0/r` 解析属 resultCode==RESULT_OK 的**上报**分支（上次误述为"失败分支"）；真正执行在 cond_190 汇聚点。
- **补**：extra 清单补齐 `KSendGroupToDo`、`KShowTodoIntroduceView`；执行尾段策略 `tn3/g4→k2.d ConcurrentHashMap<Integer,tn3/q4>`；`t7(String,List)` 内部逻辑（单群/`v51/t1.a` 判定）。
- **补**：`dr5.e/f/h/i/j` 已核（no-op / KOrientation 等），确认 setResult 只在"未找到"的路径上；旧版 `SelectContactUI` 仍存在可作为旁证/备胎。

## 8. 仍待运行时确认（不影响上述 Hook 点）
1. 最终 setResult 的具体位置（若存在）——静态未命中，dump 方案见 §6.5；
2. MsgRetransmitUI 中调 `a10/a` 的确切行与是否总用 req 0xfac；
3. `ym0/a` 是否影响你对 `startActivityForResult` 的 hook（预期只包不拦）。
