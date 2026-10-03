# 微信「收藏语音」长按 → 选择联系人转发 逆向分析报告

- 目标 APK：`com.tencent.mm`（路径 `/data/app/~~v9c9eyj1z0BZnLudFlFQeQ==/com.tencent.mm-Uok5UcD2qA9CMIVq9DZPwg==/base.apk`）
- 分析环境：LSPilot + DexKit + jadx/baksmali（宿主 `me.yun.lspilot`）
- 分析目标：在**独立 Xposed 模块**（不依赖 LSPilot BSH 脚本）中，实现「微信收藏（Favorites）列表中长按一条收藏语音 → 弹出微信原生"选择联系人"界面 → 选中后把该语音以微信语音消息（MsgInfo type=34）发出」
- 结论先行：**微信官方在三处硬编码拦截了「收藏语音」的转发**，必须逐一绕过；绕过之后可以直接复用微信原生的 `SelectConversationUI` 选人链路，并在选人回调里调用 `VoiceLogic`（`v61.d1`）自己组装并上传语音。

---

## 一、关键类清单（已全部反编译验证）

| 作用 | 类名（混淆后） | 原始业务名 |
|---|---|---|
| 收藏列表 Activity | `com.tencent.mm.plugin.fav.ui.FavoriteIndexUI` | FavoriteIndexUI |
| 收藏列表基类 | `com.tencent.mm.plugin.fav.ui.FavBaseUI` | FavBaseUI |
| 收藏列表适配器 | `com.tencent.mm.plugin.fav.ui.adapter.c` | FavoriteAdapter |
| 收藏过滤页 | `com.tencent.mm.plugin.fav.ui.FavFilterUI` | FavFilterUI |
| 长按菜单创建 | `com.tencent.mm.plugin.fav.ui.gc` | FavoriteIndexUI$OnCreateContextMMMenu |
| 长按菜单点击转发 | `com.tencent.mm.plugin.fav.ui.hc` | FavoriteIndexUI$OnMMMenuItemSelected |
| 长按业务分发 | `com.tencent.mm.plugin.fav.ui.FavoriteIndexUI.K7(IILandroid/view/View;Ltc2/m3;)V` | onLongClickMenuItemSelected |
| 合法性预检（**第 1 道拦截**） | `com.tencent.mm.plugin.fav.ui.FavoriteIndexUI.H7(Ljava/util/List;Landroid/content/Context;Landroid/content/DialogInterface$OnClickListener;ZZ)Z` | checkFavItemsCanForward |
| 转发菜单助手（**第 2 道拦截**） | `com.tencent.mm.plugin.fav.ui.mc` | FavoriteMenuHelper |
| 收藏条目 Model | `tc2.m3`（父 `im.o3`，接口 `n60.v`） | FavItemInfo |
| 收藏 Proto | `pc5.mr0` | FavProto |
| 收藏数据项 | `pc5.rq0` | FavDataItem |
| 收藏 API 门面 | `tc2.s2` | FavApiLogic |
| 语音收藏过滤器（**第 3 道拦截**） | `tc2.x3` | FavSendFilter |
| 收藏语音播放器 | `tc2.m4` | FavVoiceLogic |
| 收藏语音详情页 | `com.tencent.mm.plugin.fav.ui.detail.FavoriteVoiceDetailUI` | FavoriteVoiceDetailUI |
| 收藏点击总入口 | `ge2.i.bj(Landroid/content/Context;Ltc2/m3;Lpc5/or0;)Z` | FavItemLogic.handleFavItem |
| 收藏点击细分 | `ge2.k0`（`d/e/f/g/h/i/j/k/l/m/n/o`） | FavItemLogic |
| 选人 UI | `com.tencent.mm.ui.transmit.SelectConversationUI` | SelectConversationUI |
| 选人后的实际转发 | `com.tencent.mm.ui.transmit.MsgRetransmitUI` | MsgRetransmitUI |
| 转发能力注册表 | `rs5.i` / `rs5.b` 及 37 个子类 | ForwardFeatureService |
| 微信消息 Model | `com.tencent.mm.storage.e9`（父 `im.c8`） | MsgInfo |
| 消息 DB 门面 | `lex0.k0` | MsgInfoStorage |
| 语音 DB 行 | `v61.c1` | VoiceInfo |
| 语音发送/上传逻辑 | `v61.d1` | **VoiceLogic** |
| 语音 MsgContent 组装 | `v61.a1` | VoiceContent |
| 语音上传回调 | `v61.f1` / `com.tencent.mm.modelbase.p0` | — |
| 转发二次确认对话框 | `pb5.j1` | MMConfirmDialogBuilder |
| 确认对话框内容回填 | `he2.e`（监听 `FavInitConfirmDialogContentEvent`） | InitConfirmDialogContentListener |

> 说明：`voicelength` 字段在 `v61/c1;->a(I)`（`0x400d60`），语音消息 `type=34 (0x22)`，语音 DB 表字段见 `v61/c1;->b()` 的 23 个列名（FileName/MsgLocalId/MsgId/TotalLen/NetOffset/Status/VoiceLength/ClientId/CreateTime/User/Human/MsgTalker 等）。

---

## 二、微信官方收藏列表长按菜单是怎么来的（调用链全貌）

### 2.1 入口：长按ListItem

`com.tencent.mm.plugin.fav.ui.fc.onItemLongClick(AdapterView, View, int, long)`：

```java
if (i < ((FavBaseUI) favoriteIndexUI).h.getHeaderViewsCount()) { ... return true; }   // header 忽略
m3 i3 = favoriteIndexUI.W.i(i - headerViewsCount);           // 拿到 FavItemInfo
hashMap.put("card_fav_type", field_type);  hashMap.put("card_clk_type", 1);
a.a.a("fav_page_card_operation", "view_clk", hashMap);
m3 i4 = favoriteIndexUI.W.i(i - headerViewsCount);
if (!(p.a() && de2.o.a(ui, view, i4, new v(...), false, null, new u(...), 48, null))) {
    eu5.s0 s0Var = new s0(getContext(), view);      // MMListPopupWindow
    s0Var.A = true;
    s0Var.w = new gc(favoriteIndexUI, i, j);        // ← 菜单内容构建
    s0Var.v = new hc(favoriteIndexUI, i, view, i3); // ← 菜单项点击
    s0Var.n(...anchor...);
}
```

### 2.2 菜单内容构建：`com.tencent.mm.plugin.fav.ui.gc.a()`

```java
x3 x3Var = new x3();                                  // FavSendFilter
m3 i2 = cVar.i(pos - listView.getHeaderViewsCount());
boolean b = x3Var.b(i2, false, false);                // ★ canForward 判断
if (!b) {                      add(0, 3, 0, R.string 2131761210, icon 0x7f110650); }  // 转发
if (b && s2.n0(i2)) {          add(0, 3, 0, R.string 2131761210, icon 0x7f110650); }  // 转发
add(0, 2, 0, 2131761089, 2131823463);   // 标签
add(0, 0, 0, 2131761062, 2131821955);   // 删除
add(0, 1, 0, 2131761147, 2131822077);   // 编辑
// debug 项 5 / 7 / 6 ...
```

即 **itemId = 3 就是「转发」**。`x3.b(favItem, false, false)` 是"能不能转发"的判据。

### 2.3 菜单点击分发：`FavoriteIndexUI.K7(int itemId, int pos, View v, m3 info)`

```java
if (i3 == 0)      → 删除（二次确认对话框）
else if (i3 == 1) → 进入编辑模式
else if (i3 == 2) → FavTagEditUI
else if (i3 == 3) {                                    // ★ 转发
    m3 i7 = this.W.i(pos - headerViewsCount);
    this.J1 = i7.q0();                                 // q0() = 克隆（去掉 detail 引用）
    LinkedList<FavItemInfo> linkedList = new LinkedList<>();
    linkedList.add(this.J1);
    if (H7(linkedList, this, new cb(this), true, true)) {     // ★ 第 1 道拦截
        O7(getContext(), 4106, this.W, this.J1);              // 走 FavoriteMenuHelper
    }
}
```

### 2.4 `FavoriteIndexUI.O7(Context, int, adapter, info)`

```java
if (4106 == i) {
    r3 = info.field_datatotalsize;
    str = "fav_trans_send,";
}
Log.i("MicroMsg.FavoriteMenuHelper", "%s totalSize:%s, maxLimitSize:%s", ...);
if (totalSize > maxLimit) {  toast(fav_cap_limit);  return; }
if (!z && mc.g(context, i, cVar, m3Var)) {            // ★ 第 2 道拦截
    v3.a(".ui.transmit.SelectConversationUI");
}
```

---

## 三、三道拦截的精确位置与绕过方法

### 拦截 A —— `tc2.x3`（FavSendFilter）`b(v, z, z2)`

`com.tencent.mm.plugin.fav.ui.gc` 用 `new x3()` 构造（无参构造 `this.a = true`）。

`tc2/x3;->b(Ln60/v;ZZ)Z` 中对 `field_type == 3`（`FAV_ITEM_TYPE_VOICE`）：

```smali
:pswitch_...  (packed-switch on field_type)
    ...
    const/4 v3, 0x3
    if-ne v3, ... :cond_xx
    iget-boolean v0, p0, Ltc2/x3;->a:Z          # canFilterVoice
    if-eqz v0, :cond_false
    const-string "MicroMsg.FavSendFilter"
    const-string "[FAV_ITEM_TYPE_VOICE] canFilterVoice = true, back"
    return v0                                     # 返回 true = 视为"不可转发"
```

**影响**：`gc.a()` 里 `if (!b)` 成立 → 通常会加菜单；但 `x3.b()` 对语音恒返回 `true`，所以真正决定菜单是否显示的是第二条 `if (b && s2.n0(i2))`，即只有 `s2.n0(info)`（`field_itemStatus ∈ {7,8,10}`，即"已在本地/已完成"）为真时才显示「转发」。**绝大多数收藏语音 `itemStatus` 不是这三个值 → 长按菜单里根本没有「转发」项。**

**绕过 A（模块侧）**：Xposed hook `tc2.x3.b`，当 `field_type == 3` 时强制 `return false`。这样 `gc.a()` 的 `if (!b)` 分支就会把「转发」菜单项加进去。

> 备选：直接 hook `com.tencent.mm.plugin.fav.ui.gc.a()` 在 `super` 后手动 `add(0, 3, 0, ...)`。但 hook `x3.b` 更简洁、兼容 FavFilterUI / FavSelectUI 等其他收藏页。

---

### 拦截 B —— `FavoriteIndexUI.H7(...)`（合法性预检，菜单点击后第一道）

`K7` 中 `H7(linkedList, ...)` 返回 `false` 时直接不再调用 `O7`。

`FavoriteIndexUI;->H7` 的 smali 关键分支（v10 = type==3 的计数 `i4`）：

```smali
:cond_173
if-lez v10, :cond_180          # v10 <= 0 才继续往下
const v2, 0x7f10000a           # R.string.xxx
invoke-virtual {v1, v2}, getString
invoke-static {v1, v2}, Lkj5/e1;->T   # Toast
return v0                      # v0 = 0 → false

:cond_180
if-lez v11, :cond_18d ...      # 其它类型分支
...
:cond_1a7
const/4 v0, 0x1                # 全部合法 → return true
return v0
```

即：**只要列表里有 type==3 的收藏，H7 直接弹 Toast 并返回 false**，转发不会发生。

**绕过 B**：hook `FavoriteIndexUI.H7`，当 `param0`（List）中存在 `field_type == 3` 的元素时强制 `return true`。或者更彻底：hook `K7`，在 `i3 == 3` 分支直接接管。

---

### 拦截 C —— `com.tencent.mm.plugin.fav.ui.mc`（FavoriteMenuHelper）

即使 A/B 都绕过，`mc.g(Context,int,adapter,m3)` 与 `mc.h(Context,int,List)` 还有显式判断：

`mc.g`（`4105` 多选分支，单条选中时）：
```java
if (((LinkedList) cVar.j(false)).get(0) != null
    && ((o3) ((m3) ((LinkedList) cVar.j(false)).get(0))).field_type == 3) {
    Log.i("MicroMsg.FavoriteMenuHelper", "[shareFavToFriRequest] select first is FAV_ITEM_TYPE_VOICE");
    e1.T(context, context.getString(2131761212));   // "收藏的语音消息不能转发"
    return false;
}
```
`mc.h`（`4106` 单选长按分支）：
```java
if (list.get(0) != null && ((o3) ((m3) list.get(0))).field_type == 3) {
    e1.T(context, context.getString(2131761212));
    return false;
}
```

**绕过 C**：hook `mc.g` / `mc.h`，命中 `field_type == 3` 时强制 `return true`（让微信继续弹 `SelectConversationUI`）。同时把 `mc.c(field_type)` 的 `Retr_Msg_Type` 结果改掉（见下节）。

---

## 四、微信原生「选人 → 转发」链路（绕过拦截后会被走到）

### 4.1 `mc.g/h` 组装的 Intent

```java
Intent i = new Intent();
i.putExtra("Select_Conv_Type", 3);            // 3 = 仅聊天会话（含群聊）
i.putExtra("scene_from", 1);                  // 1 = 收藏来源
i.putExtra("mutil_select_is_ret", true);
if (info != null) {
    i.putExtra("select_fav_local_id", info.field_localId);
    if (info.R1) i.putExtra("select_fav_fake_local_id", info.T1);
    if (info.field_type == 19) i.putExtra("appbrand_params", b0.c(info));
}
if (mc.c(info.field_type) != -1) i.putExtra("Retr_Msg_Type", mc.c(info.field_type));
i.putExtra("scene_from", 1);
hc5.l.v(context, ".ui.transmit.SelectConversationUI", intent, 4105/4106);
```

`mc.c(int favType)` 映射（收藏类型 → `Retr_Msg_Type`）：
```
favType 1  → 4      favType 2  → 0
favType 4  → 1      favType 6  → 9
favType 8  → 3      favType 14 → 13
favType 16 → 11     其他(含3)  → 2
```
注意 **`favType 3`（语音）落在 `其他 → 2`**，而 `Retr_Msg_Type = 2` 在 `SelectConversationUI.h8(j1)` 里走的不是"从收藏发送"而是"重发已有 MsgInfo"分支（`dx0/r;->v(h2)`）。所以即使绕过 C，也**不能**直接把语音交给这条原生转发链路，必须在 `X7` 之前拦下来自己做。

### 4.2 选人结果回传

`SelectConversationUI.W7(String username)`（`doClickUser`）：
```java
Intent data = new Intent();
data.putExtra("Select_Conv_User", username);        // 单个 username
// 若 Intent 里有 Select_Conv_NextStep → f8(data, nextIntent)
// 否则分支：
if (I || l1 || J || K || L || ...) → X7(intent, username);   // J = (scene_from == 1)
```
`scene_from == 1` 时 `y7()` 中 `this.J = true`，于是进入 `X7(data, username)`。

`X7(Intent, String)` 中收藏分支：
```java
} else if (this.J) {
    if (getIntent().hasExtra("appbrand_params") && S7(intent, j1Var, null)) return;
    if (this.p0 != -1) {                       // p0 = select_fav_local_id
        h8(j1Var);                             // 按 Retr_Msg_Type 装"内容点击"回调
        FavInitConfirmDialogContentEvent ev = new FavInitConfirmDialogContentEvent();
        y9 v = ev.g;
        v.a = this.W1;   v.b = this.Z;   v.c = this.p0;   v.d = this.x0;
        v.e = this.V1;   v.f = j1Var;      v.g = getContext();
        v.h = this.o2;   v.i = this.c2.N0();
        ev.e();                               // 异步事件 → he2.e
    }
    ...
    j1Var.g(Boolean.TRUE);                    // 显示"发送"确认框
}
```

`he2.e.callback()` → `ge2.k0.l(j1, ctx, info)` + `ge2.k0.m(j1, ctx, favScene, info, msgId, sessionId)`。

**`ge2.k0.l` / `ge2.k0.m` 都没有 `field_type == 3` 的分支**（`l` 里 `if (3 != i)` 显式跳过；`m` 只处理 1/2/4/5/6/8/14/16/18/20/24）。

**结论**：`Retr_Msg_Type` 路径对收藏语音是完全不通的。**必须在 `SelectConversationUI.W7` 或 `X7` 处拦下来，自己拿到 `Select_Conv_User`，然后自己发语音。**


---

## 五、收藏语音的数据怎么拿（本地文件 + 时长）

`FavoriteVoiceDetailUI.onCreate` 给出了标准取法：

```java
m3 H  = ((h6) n0.c(h6.class)).sj().H(localId);       // FavItemInfoStorage.get(localId)
rq0 K = tc2.s2.K(H);                                 // FavApiLogic.getFavDataItem(info) = favProto.f.get(0)
String path = tc2.s2.y(K);                           // FavApiLogic.getFavDataLocalPath(dataItem)  ← silk 文件绝对路径
int    voiceType = tc2.s2.d0(K.K);                   // K.K 字段名见 pc5.rq0
String durText = (String) c7.b(activity, (int) tc2.s2.Z(K.y));   // K.y = 时长(ms?)
if (com.tencent.mm.vfs.z6.j(path)) {                 // 本地有文件
    // 直接播放
} else {
    tc2.s2.F0(H, true);                              // 触发 CDN 下载
}
```

要点：
- `pc5/rq0`（FavDataItem）里语音用到的字段：
  - `.T` = dataId（`key_detail_data_id`）
  - `.K` = 文件后缀/类型串（`s2.d0(K.K)` 得到 voiceType）
  - `.y` = 时长
  - `.d` = 标题/文件名
  - `.M` = cdn url；`.I` = dataType
- `tc2.s2.y(rq0)` 的实现在本地有 `dataItem.T`（dataId）且文件存在时返回 `<favoriteRoot>/<dataId>`；否则按 `M(CDN base) + T + "." + K(后缀)` 拼出目标路径并返回（**下载后**才会存在）。
- `tc2.m4.d(String path, int voiceType)` 是播放器入口：`c1.u(path, isSpeaker, true, voiceType)`，其中 `c1 = com.tencent.mm.modelbase.c1`（`((d1) n0.c(d1.class))` 拿到）。

> 独立模块里**不需要**反射 `tc2.s2` 的私有实现 —— 直接用 DexKit 找到 `tc2.s2.K/y/d0` 后反射调用即可；或者更稳妥的做法是直接 hook `FavoriteVoiceDetailUI` / `FavVoiceBaseView` 把 `FavChatVoiceView.h`（path）与 `.i`（voiceType）读出来。

---

## 六、把收藏语音作为微信语音消息发出去（核心实现）

### 6.1 语音消息的落地事实

微信语音消息 = `MsgInfo`（`com.tencent.mm.storage.e9`，父类 `im.c8`）`type = 34 (0x22)`，`content` 形如 `<msg><voicelength>xxxx</voicelength><voicemsg .../></msg>`，同时在 `VoiceInfo` 表（`v61.c1`）落一行。

微信原生录音发送链路最终都收敛到 **`v61.d1`（VoiceLogic）**：
- `v61/d1;->e(Lv61/c1;ZILjava/lang/String;Ljava/lang/String;Lcom/tencent/mm/modelbase/p0;)J`
  —— 插入/更新 VoiceInfo 并启动上传，返回 `msgId`。
- `v61/d1;->u(Ljava/lang/String;IILcom/tencent/mm/storage/e9;Ljava/lang/String;)Z`
  —— 用本地文件重新上传（重发场景）。
- `v61/d1;->a(Lcom/tencent/mm/storage/e9;Ljava/lang/String;)Z`、`d1.x(c1)`（写库）等。
- `v61/a1;->c(Ljava/lang/String;JZ)Ljava/lang/String` 拼 `<fileName>:<length>:<flag>\n`。

由 `v61.d1.u` 的 smali 可以精确还原插入 MsgInfo 的字段顺序（这是模块要模仿/触发的）：

```smali
invoke-static {p0}, Lv61/d1;->k(Ljava/lang/String;)Lv61/c1;   # 按 fileName 取 VoiceInfo
iget v2, v1, Lv61/c1;->i:I        # Status
const/16 v4, 0x61 / const/16 v3, 0x62
...
new-instance p1, Lcom/tencent/mm/storage/e9;
invoke-direct {p1}, Lcom/tencent/mm/storage/e9;-><init>()V
iget-object v5, v1, Lv61/c1;->c:Ljava/lang/String;   # MsgTalker / toUser
invoke-virtual {p1, v5}, Lim/c8;->u1(Ljava/lang/String;)V     # setTalker(toUser)
const/16 v5, 0x22
invoke-virtual {p1, v5}, Lim/c8;->setType(I)V                 # setType(34)
const/4 v5, 0x1
invoke-virtual {p1, v5}, Lim/c8;->k1(I)V                      # setStatus(1)
invoke-virtual {p1, p0}, Lcom/tencent/mm/storage/e9;->j1(Ljava/lang/String;)V   # setImgPath(fileName)
# 按 voiceFlag 设置 content：v61/a1;->c(fileName, voiceLength, false)
invoke-virtual {p1, v3}, Lcom/tencent/mm/storage/e9;->b1(Ljava/lang/String;)V   # setContent(...)
iget-object v3, v1, Lv61/c1;->c:Ljava/lang/String;
invoke-static {v3}, Lb41/aa;->p(Ljava/lang/String;)J
invoke-virtual {p1, v3, v4}, Lim/c8;->e1(J)V                  # setCreateTime(...)
invoke-virtual {p1, p2}, Lcom/tencent/mm/storage/e9;->n3(I)V
...
invoke-static {p1}, Lb41/aa;->y(Lcom/tencent/mm/storage/e9;)J
iget-wide v2, v1, Lv61/c1;->m:J                              # MsgLocalId
invoke-static {v1}, Lv61/d1;->x(Lv61/c1;)Z                   # 写 VoiceInfo 库
```

### 6.2 三种实现路线（推荐 A）

#### 路线 A（推荐）：复用微信「选人 UI」+ 反射调用 `VoiceLogic` 上传

1. **Hook 拦截三处**（见第三节），让长按语音出现「转发」菜单项。
2. **Hook `com.tencent.mm.ui.transmit.SelectConversationUI` 的 `W7(String)`**：
   - 读 `param1`（被选中的 username，群聊以 `@chatroom` 结尾）；
   - `setResult(RESULT_OK)` / 直接 `finish()` 掉选人页；
   - 把 `username` 与先前保存的 `FavItemInfo.localId` 交给自己的发送逻辑；
   - `return`（阻止微信继续走 `X7`）。
   - 多选场景（`style_multi_select_conversation` / `KSelectUserList`）改 hook `X7(Intent, String)` 并解析 `intent.getStringArrayListExtra("Select_Conv_User")`。
3. **发送逻辑**（在模块内）：
   ```text
   a. 通过 hook 或反射 tc2.s2.K(info) 取 rq0；
   b. tc2.s2.y(rq0) 得到本地 silk 路径；s2.d0(rq0.K) 得 voiceType；rq0.y 得时长；
      若文件不存在，先触发 tc2.s2.F0(info, true) 下载，等 VoiceMsgDownloadFinishEvent；
   c. 反射 new v61.c1()，按 v61.d1.u / v61.d1.e 的字段序列填：
         c  = toUser（群/个人 wxid）
         b  = clientId（UUID）
         d  = fileName（建议沿用本地文件 baseName，或 newFileName）
         e  = createTime (System.currentTimeMillis())
         g/h= NetOffset/TotalLen = 文件字节数
         i  = Status（3）
         l  = voiceLength（秒） → v61/a1;->c(fileName, voiceLength, false) 生成 content
         a  = 0x400d60（voiceLength 常量位）
   d. 反射调用 v61.d1.e(voiceInfo, false, 0, null, null, callback) 触发上传；
      或先 new MsgInfo() 按 6.1 的字段顺序 setType(34)/u1(talker)/j1(imgPath)/b1(content)/k1(1)，
      再调 v61.d1.u(fileName, status, msgStatus, msgInfo, null)。
   ```
4. 若反射版本差异导致 `v61.d1` 签名对不上，**兜底方案**：hook `v61.d1.e`，在自己录音/发送时抓一次真实的 `(c1, boolean, int, String, String, p0)` 参数，然后在模块里原样回放（只改 `c1.c = toUser`、`c1.d = fileName`、`c1.e = createTime`）。

#### 路线 B：把语音伪装成"文件"走原生转发
`mc.h/g` 里 `field_type = 7/8`（文件）走的是 `Retr_Msg_Type = 2` + `image_path`，最终进 `MsgRetransmitUI` 的 `processVideoTransfer`/文件分支。改 `field_type` 成 7 可以骗过菜单，但**发出的不是语音消息**，对方看到的是文件。不推荐。

#### 路线 C：把语音伪装成"聊天记录"（Record）
`field_type = 14` 走 `FavRecordDetailUI`（`RecordMsgDetailUI`），内部用 `kfavorite` 发送 `<recordinfo>` XML。可以把单条语音塞进 record XML，但对方收到的是"聊天记录"气泡，体验不符。不推荐。

---

## 七、推荐的 Xposed Hook 点汇总（独立模块用）

| # | 目标 | 方法签名 | 目的 | 参数/返回值处理 |
|---|---|---|---|---|
| 1 | `tc2.x3` | `b(Ln60/v;ZZ)Z` | 让语音长按出现「转发」 | `param1.field_type==3 → return false` |
| 2 | `com.tencent.mm.plugin.fav.ui.FavoriteIndexUI` | `H7(Ljava/util/List;Landroid/content/Context;Landroid/content/DialogInterface$OnClickListener;ZZ)Z` | 通过合法性预检 | List 含 type==3 → `return true` |
| 3 | `com.tencent.mm.plugin.fav.ui.mc` | `g(Landroid/content/Context;ILcom/tencent/mm/plugin/fav/ui/adapter/c;Ltc2/m3;)Z` | 通过单条拦截 | type==3 → `return true`（随后由 #4 接管） |
| 4 | `com.tencent.mm.plugin.fav.ui.mc` | `h(Landroid/content/Context;ILjava/util/List;)Z` | 通过批量拦截 | List.get(0).field_type==3 → `return true` |
| 5 | `com.tencent.mm.ui.transmit.SelectConversationUI` | `W7(Ljava/lang/String;)V` | **接管选人结果** | 读 param1；`setResult+finish`；`return`(不调原方法) |
| 6 | （可选）`com.tencent.mm.ui.transmit.SelectConversationUI` | `X7(Landroid/content/Intent;Ljava/lang/String;)V` | 多选场景接管 | 解析 `Select_Conv_User` / 英文逗号分隔 |
| 7 | `tc2.s2` | `y(Lpc5/rq0;)Ljava/lang/String;` | 取本地 silk 路径 | afterHooked 记录 result |
| 8 | `tc2.s2` | `K(Ln60/v;)Lpc5/rq0;` | 取 FavDataItem | afterHooked 记录 result |
| 9 | `v61.d1` | `e(Lv61/c1;ZILjava/lang/String;Ljava/lang/String;Lcom/tencent/mm/modelbase/p0;)J` | 兜底回放上传 | 记录/回放参数 |
| 10 | `com.tencent.mm.autogen.events.DoFavoriteData` 或 `im.o3` `field_localId` | — | 传递 localId | 模块内全局变量 |

> Hook 类名建议用 DexKit 按"类名字符串 / 方法名 / superclass"动态解析，避免微信换包后失效：
> - `rs5.i` 的 `<clinit>` 里有 37 个 `rs5.b` 子类实例数组（长度 `0x25`），可反向确认转发能力表版本。
> - `pc5.mr0` / `pc5.rq0` / `im.o3` 在 DexKit 中按字段名（`field_localId` / `field_favProto` / `field_type`）精确定位。

---

## 八、端到端流程图（文字版）

```
[收藏列表] FavoriteIndexUI
   └─ fc.onItemLongClick(pos)
        └─ s0(MMListPopupWindow)
             ├─ w = gc  →  a()  ──► tc2.x3.b(item,false,false)   ← 【拦截A】语音恒 true
             └─ v = hc  →  onMMMenuItemSelected(itemId==3)
                          └─ FavoriteIndexUI.K7(3,...)
                               ├─ H7(list,...)                    ← 【拦截B】含语音即 false+Toast
                               └─ O7(ctx,4106,adapter,info)
                                    └─ mc.g(ctx,4106,adapter,info)
                                         ├─ field_type==3 → Toast+false  ← 【拦截C】
                                         └─ SelectConversationUI
                                              (Select_Conv_Type=3, scene_from=1,
                                               mutil_select_is_ret=true,
                                               select_fav_local_id=…, Retr_Msg_Type=2)
                                              └─ 用户选人
                                                   └─ W7(username) → X7(intent,username)
                                                        ├─ j1(V1==2) → dx0/r;->v(h2)  ✗ 不通
                                                        └─ FavInitConfirmDialogContentEvent
                                                             └─ he2.e → ge2.k0.l/m  ✗ 无 type==3 分支
```

模块改造后：

```
[收藏列表] 长按语音 → 出现「转发」
   ↓ (Hook 1/2/3 放行)
SelectConversationUI 弹出（微信原生选人 UI）
   ↓ 用户选中联系人
[Hook 5] SelectConversationUI.W7(username)
   ↓ 拦截，finish()
模块发送逻辑：
   tc2.s2.K(info) → rq0
   tc2.s2.y(rq0)  → 本地 silk 路径（不存在则 s2.F0 下载）
   tc2.s2.d0(rq0.K) → voiceType ；rq0.y → 时长
   组装 v61.c1（VoiceInfo）+ e9（MsgInfo, type=34)
   反射 v61.d1.e(...) 上传
   ↓
对方收到标准微信语音气泡（可播放、可转文字）
```


---

## 九、二次核查记录（逐条验证 + 修正）

对报告中的每个关键结论重新取证，发现并修正了 **2 处字段归属错误** 与 **1 处描述不严谨**：

### 修正 1：`pc5/rq0`（FavDataItem）字段归属

原报告写「`.T` = dataId，`.K` = 文件后缀/类型串，`.y` = 时长」。重新 dump `pc5/rq0` 全部字段后确认为：

| 字段 | 类型 | 实际业务含义 |
|---|---|---|
| `T` (0x54) | `Ljava/lang/String;` | **dataId**（`key_detail_data_id` 用它比对） |
| `d` | `Ljava/lang/String;` | 标题/文件名 |
| `f` | `Ljava/lang/String;` | 来源用户名（`s2.a0` 里 `se0Var.C(rq0.f)`） |
| `A` | `Ljava/lang/String;` | CDN/web url |
| `C` | `Ljava/lang/String;` | cdn aeskey |
| `M` | `Ljava/lang/String;` | CDN 文件 url |
| `K` | `Ljava/lang/String;` | **文件后缀/扩展名**（`s2.y()` 里 `if (K != null && trim().length() > 0) 拼 "T.K"`） |
| `y` | `I`（不是 String） | —— |
| `I` | `I` | dataType |
| `R` | `J` | 文件大小 |
| `G` | `Ljava/lang/String;` | md5 |
| `w2` | `Ljava/lang/String;` | cdn thumb key |
| `T1` | `Lpc5/sq0;` | shareObject/扩展信息（`s2.a0` 用 `T1.C` → `hz4`） |
| `I2` | `Lpc5/mr0;` | 自引用 FavProto |
| `Y1` | `Lpc5/uq0;` | 短视频信息 |
| `h2` | `I` | illegal 标记（`1`=已过期，`2`=超限） |

**修正**：`FavoriteVoiceDetailUI` 中 `s2.Z(K.y)` 的 `K.y` 与 `s2.d0(K.K)` 的 `K.K` 是**两个不同字段**；`y` 用于取"语音时长(秒)"，`K` 是"文件后缀"。原先"`K.K` 字段名见 pc5.rq0"的模糊表述已改为上表。
**另需注意**：`pc5/rq0` 有 110 个字段，以上仅列与语音相关的部分，完整字段见 `decompile_class_fields(pc5.rq0)`。

### 修正 2：`v61/c1`（VoiceInfo）字段 ↔ 列名映射

原报告把 `c` 说成 `MsgTalker`、`e` 说成 `createTime`。重新读 `v61/c1;->b()`（`convertTo()`）的 `ContentValues.put(col, field)` 序列后确认（`a` 是列存在位掩码，非数据字段）：

| `v61/c1` 字段 | 列名 | 说明 |
|---|---|---|
| `b` | `FileName` | 语音文件名（含 .aud / .silk） |
| `c` | `User` | **发送者/接收者 wxid**（`v61.d1.u` 里 `u1(c)` 就是 `setTalker`） |
| `e` | `MsgId` | 服务端 msgId |
| `f` | `NetOffset` | 已上传字节 |
| `g` | `FileNowSize` | 当前文件大小 |
| `h` | `TotalLen` | 总长度 |
| `i` | `Status` | 0/1=未传，3=待传，0x61(97)/0x62(98)=已传 |
| `j` | `CreateTime` | 创建时间 |
| `k` | `LastModifyTime` | 最后修改时间 |
| `l` | `VoiceLength` | **语音时长（秒）** |
| `m` | `MsgLocalId` | 本地 msgId |
| `n` | `Human` | 发送人昵称 |
| `o` | `MsgFlag` | —— |
| `p` | `MasterBufId` | —— |
| `q` | `MsgSource` | —— |
| `r` | `MsgSeq` | —— |
| `s` | `MsgTalker` | 会话 talker（群/个人） |
| `t` | `ClientId` | 客户端唯一 id |
| `u` | `VoiceFlag` | —— |
| `v` | `VoiceInfoExt` | —— |
| `w` | `Lpc5/j97;` | PB 扩展 |
| `x` | `Ljava/lang/String;` | —— |
| `y` | `I` | —— |

**修正**：`v61.d1.u` 中 `iget-object v3, v1, Lv61/c1;->c:Ljava/lang/String; invoke-virtual {p1, v3}, Lim/c8;->u1(...)` 说明 **`c`（列名 `User`）同时被当作 setTalker 的目标**；真正的"会话 talker"是 `s`（列名 `MsgTalker`）。组装 VoiceInfo 时**必须同时写 `c` 和 `s`**。

**修正**：`v61.d1.u` 中 `const p1, 0x400d60; iput p1, v1, Lv61/c1;->a:I` —— 这里的 `a` 是**列存在位掩码**，`0x400d60` = `0b0100_0000_0000_1101_0110_0000`（bit 5,6,8,10,11,16,18），即声明 FileNowSize/TotalLen/Status/CreateTime/LastModifyTime/VoiceLength/… 这些列有值。**不是**"voiceLength 常量位"。

### 修正 3：`im/c8`（MsgInfo 父类）字段方法名

重新 dump `im/c8` 方法集后，把原先模糊的"setTalker/setType"落实为混淆名（模块反射要用这些真名）：

| 混淆方法 | 作用 |
|---|---|
| `u1(Ljava/lang/String;)V` | **setTalker**（会话对端 wxid） |
| `setType(I)V` | setMsgType（语音 = `34` / `0x22`） |
| `k1(I)V` | setStatus（发送中 = 1） |
| `j1(Ljava/lang/String;)V` | **setImgPath**（语音文件名） |
| `b1(Ljava/lang/String;)V` | **setContent**（XML） |
| `e1(J)V` | setCreateTime |
| `n1(J)V` / `W0(J)V` / `q1(J)V` / `setMsgId(J)V` | 各 id 字段 |
| `t1(I)V` | setIsSend（1=自己发的） |
| `f1(I)V` / `m1(I)V` / `v1(I)V` / `Z0(I)V` | 其它 int 标志 |
| `r1(Ljava/lang/String;)V` / `r3(Ljava/lang/String;)V` / `i1(...)` / `w1(...)` / `x1(...)` / `X0(...)` / `Y0(...)` | 各 String 字段 |
| `l1([B)V` / `A0()[B` | 二进制字段 |
| `N0()Ljava/lang/String;` | 取某个 String（`v61.d1.u` 写回 `c1.x`） |
| `getMsgId()J` / `getCreateTime()J` / `getType()I` / `j()Ljava/lang/String;` / `p0()` / `q0()` / `s0()` / `x0()` / `E0()` / `G0()` / `Q0()` / `T0()` / `V0()` | 各 getter |

### 修正 4：`SelectConversationUI` 多选取值方式

原报告只说解析 `intent.getStringArrayListExtra("Select_Conv_User")`。复核 `X7(Intent, String)` 与 `W7(String)` 后修正为：
- **单选**：`W7(String username)` 的 `param1` 就是 wxid；
- **多选**（`style_multi_select_conversation=true` 或 `mutil_select_is_ret=true` 且用户走"多选"模式）：微信在 `W7` 里 `new Intent()` 并 `putExtra("Select_Conv_User", username)`，然后在 `X7(intent, str)` 内部对**每个** username 调 `X7`；模块 hook `X7` 时 `param2` 就是单个 wxid，`param1` 里的 `Select_Conv_User` 也是同一个 —— **不需要解析 ArrayList**。若开了 `KSelectUserList`，则从 `intent.getStringArrayListExtra("Select_Conv_User")` 取，此时 `X7` 会被多次调用。

### 其他复核实结论（无变化）

- `mc.c(int)` 映射表正确；`favType 3 → Retr_Msg_Type 2` 已再次确认。
- `ge2.k0.l` / `ge2.k0.m` 确实没有 `field_type == 3` 分支（`l` 中 `if (3 != i)` 为显式排除）。
- `tc2.s2.n0(m3)` 的判据是 `field_itemStatus ∈ {8, 10, 7}`（smali `0x8 / 0xa / 0x7` 三选一即 true）。
- `tc2.x3.b` 的语音分支判据是 `this.a`（无参构造 `a=true`），确认为恒 true。
- `FavoriteVoiceDetailUI` 的语音路径走 `FavChatVoiceView`（收藏列表/详情通用），`setVoiceHelper(tc2.m4)`，播放 `m4.d(path, voiceType)`。
- `pc5/mr0->G` 是 `Lpc5/dr0;`，`dr0` 只有 `d`/`e` 两个 String（笔记作者/编辑者），**与语音无关**，不要误用。

---

## 十、风险与兼容性提示

1. **`v61.d1.e(...)` 签名跨版本不稳**。本报告基于 `base.apk`（路径见文首）。微信每次发版 `v61.*` 都可能被重新混淆/改名。**必须**在模块里用 DexKit 按以下特征动态定位 `VoiceLogic`：
   - 方法名/描述符含 `Lv61/c1;` + `Lcom/tencent/mm/modelbase/p0;`
   - 方法体含字符串 `"MicroMsg.VoiceLogic"` 与 `"[oneliang] msg svrid:%s,it is in delete msg list..."`
   - 返回 `J`（long）
2. **`tc2.s2` / `pc5.mr0` / `pc5.rq0` / `im.o3` 同理会变**。定位特征：
   - `im.o3` 有 87 个字段且含 `field_favProto:Lpc5/mr0; field_localId:J field_type:I field_xml:Ljava/lang/String;`
   - `pc5.mr0` 有 38 字段且含 `f:Ljava/util/LinkedList; d:Lpc5/yr0; G:Lpc5/dr0;`
   - `pc5.rq0` 有 110 字段。
3. **本地文件不存在时**：`tc2.s2.y(rq0)` 返回的路径只是"应该存在"的路径。必须先 `tc2.s2.F0(info, true)` 触发下载，并监听收藏同步完成（`field_itemStatus` 变 4/10）后再发。否则会发出 0 长度语音或上传失败。
4. **群聊发送**：`setTalker` 传 `xxx@chatroom`，微信会自动走群消息分支；`v61.d1.u` 内部 `b41.d2.C(str)`（`y3.V4`）判定群聊。
5. **合规提示**：本报告仅用于技术研究与自用模块开发。微信语音转发涉及其消息协议与内容审计机制，请遵守《微信软件许可及服务协议》与当地法律法规，勿用于骚扰、垃圾信息等场景。
6. **不要同时 hook 多 `decompile_class`/`get_class_smali`**（本报告分析阶段均按轻量工具优先完成，未触发 OOM）。

---

## 十一、附录：本报告使用的关键 smali 片段（可直接对照）

### A. `tc2/x3;->b` 语音拦截
```smali
const/4 v3, 0x3
if-ne v3, v4, :cond_xx      # v4 = field_type
iget-boolean v0, p0, Ltc2/x3;->a:Z
if-eqz v0, :cond_false
const-string v0, "MicroMsg.FavSendFilter"
const-string v1, "[FAV_ITEM_TYPE_VOICE] canFilterVoice = true, back"
invoke-static {v0, v1}, Lcom/tencent/mars/xlog/Log;->i(...)
return v0                    # true
```

### B. `FavoriteIndexUI;->H7` 语音拦截
```smali
:cond_173
if-lez v10, :cond_180                 # v10 = type==3 计数
const v2, 0x7f10000a
invoke-virtual {v1, v2}, Landroid/content/Context;->getString(I)Ljava/lang/String;
invoke-static {v1, v2}, Lkj5/e1;->T(Landroid/content/Context;Ljava/lang/String;)Landroid/widget/Toast;
const/4 v0, 0x0
return v0                              # false
:cond_1a7
const/4 v0, 0x1
return v0                              # true
```

### C. `mc;->g/h` 语音拦截
```smali
iget v2, p0, Lim/o3;->field_type:I
const/4 v3, 0x3
if-eq v2, v3, :cond_ok
const v0, 0x7f10183c                    # R.string.fav_voice_cannot_transmit
invoke-virtual {p0, v0}, Landroid/content/Context;->getString(I)Ljava/lang/String;
invoke-static {p0, v0}, Lkj5/e1;->T(...)Landroid/widget/Toast;
const/4 v0, 0x0
return v0
```

### D. `v61/d1;->u` 组装 MsgInfo
```smali
new-instance p1, Lcom/tencent/mm/storage/e9;
iget-object v5, v1, Lv61/c1;->c:Ljava/lang/String;    # "User"
invoke-virtual {p1, v5}, Lim/c8;->u1(Ljava/lang/String;)V
const/16 v5, 0x22
invoke-virtual {p1, v5}, Lim/c8;->setType(I)V
const/4 v5, 0x1
invoke-virtual {p1, v5}, Lim/c8;->k1(I)V
invoke-virtual {p1, p0}, Lcom/tencent/mm/storage/e9;->j1(Ljava/lang/String;)V
# content = v61/a1;->c(fileName, voiceLength, false)
invoke-virtual {p1, v3}, Lcom/tencent/mm/storage/e9;->b1(Ljava/lang/String;)V
invoke-virtual {p1, v3, v4}, Lim/c8;->e1(J)V
invoke-virtual {p1, p2}, Lcom/tencent/mm/storage/e9;->n3(I)V
invoke-static {p1}, Lb41/aa;->y(Lcom/tencent/mm/storage/e9;)J
```

---

## 十二、结论

微信在 **`tc2.x3`（菜单可见性）**、**`FavoriteIndexUI.H7`（合法性预检）**、**`mc.g/mc.h`（FavoriteMenuHelper）** 三处显式封死了"收藏语音转发"，并且即便放行，原生的 `SelectConversationUI → X7 → FavInitConfirmDialogContentEvent → he2.e → ge2.k0.l/m` 链路对 `field_type==3` 完全没有分支，`Retr_Msg_Type` 也会被 `mc.c(3)` 错映射成 `2`。

**可行的实现方式只有一条**：模块自行 hook 上述拦截点放行「转发」菜单 → 复用微信原生 `SelectConversationUI` 选人 → 在 `W7(String)` 处拦截结果 → 自己通过 `v61.d1`（VoiceLogic）组装 `type=34` 的 `MsgInfo` + `VoiceInfo` 并上传。本地语音文件路径由 `tc2.s2.y(tc2.s2.K(info))` 给出，不存在时先 `tc2.s2.F0(info, true)` 下载。

报告完毕。
