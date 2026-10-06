# 微信群角色头衔（群主/管理员/成员 + 自定义颜色背景）逆向分析报告

> 目标 App：微信（WeChat）8.0.x
> 分析工具：LSPilot（DexKit / jadx / baksmali）
> 分析结论：**微信原生并没有"群头衔"标签控件**，"群主/管理员/成员"头衔展示是由三方 Xposed 模块在聊天列表 Item 上动态叠加的。本报告给出**数据层（角色判断）+ UI 层（注入点）+ 自定义颜色/背景** 的完整逆向证据与实现方法。

---

## 1. 总览（三层架构）

```
┌─ 数据层：群成员角色来源 ─────────────────────────────┐
│  com.tencent.mm.storage.z2  (ChatRoomMember 群成员)     │
│    ├─ L0(username)  群主判断                            │
│    ├─ E0(username)  管理员判断（roomFlag bit map）        │
│    ├─ M0()         当前登录用户是否群主                   │
│    └─ q0(username) 返回 ChatRoomMemberData (so.b)       │
└──────────────────────────────────────────────────────┘
        │ 获取入口：p02.a.a() -> storage.a3.t1(chatroomName)
        ▼
┌─ UI 层：消息列表 Item（viewitems）────────────────────┐
│  com.tencent.mm.ui.chatting.viewitems.b0 (ChattingItemBase) │
│    ├─ p()   填充昵称 userTV                             │
│    ├─ Z()   设置 userTV 文本                             │
│    └─ x()   获取显示名（群聊使用 aa.t(e9.j())）            │
│  Holder: h0.userTV / avatarIV                          │
└──────────────────────────────────────────────────────┘
        │ 注入点：Hook b0.p / b0.Z，在 userTV 旁叠加头衔 TextView
        ▼
┌─ 配置层：颜色/背景（模块自身 Settings）─────────────────┐
│  SP / MMKV 存 "群主"、"管理员"、"成员" 的文字色+背景色     │
│  创建 TextView 后 setTextColor / setBackgroundColor     │
└──────────────────────────────────────────────────────┘
```

---

## 2. 数据层：群成员角色数据结构（核心岗位）

### 2.1 ChatRoomMember —— `com.tencent.mm.storage.z2`

反编译方法签名（decompile_class_methods_only）：

| 方法 | 返回 | 作用 |
|---|---|---|
| `L0(String)` | boolean | **群主判断**：`!Util.isNullOrNil(field_roomowner) && field_roomowner.equals(str)` |
| `E0(String)` | boolean | **管理员判断**：`q0(str) != null && (q0(str).f & 2048) != 0`（roomFlag 第 11 位） |
| `M0()` | boolean | 当前登录用户是否群主（综合 roomowner / 管理员逻辑） |
| `q0(String)` | so.b(ChatRoomMemberData) | 取单个成员数据 |
| `x0(String)` | String | 取群内显示名 displayName |
| `z0()` | List | 返回全部成员 username 列表 |
| `A0()` | int | 群成员版本号 |
| `D0()` | so.a(ChatRoomData) | 取整体群数据 |
| `V0(int)` | void | 设置版本号 |
| `T0(List)` / `Y0(String)` | List/String | memberlist 字段 <-> 字符串 |
| `W0(String, String)` | z2 | 从 XML RoomData 解析 |
| `X0(String, so.a, boolean)` | z2 | 保存 RoomData 并刷新缓存 |
| `P0(String, List, List, List, boolean)` | void | 更新成员列表（管理员列表置 2048 位） |

关键字段（decompile_class_fields）：
```
o2: Lqf5/e0;   k2: Lso/a;   l2: Lso/a;   m2: Ljava/util/List;   n2: Ljava/util/Map;
```

父类：`com.tencent.mm.storage.y1`（日志打点 "MicroMsg.ChatRoomMember"），含大量 `field_xxx`：
`field_roomowner`（群主）、`field_chatroomname`、`field_memberlist`、`field_displayname`、
`field_roomdata`、`field_isShowname`、`field_chatroomdataflag`、`field_chatroomnotice` 等。

### 2.2 ChatRoomMemberData —— `so.b`

toJSON 字段（反编译证据）：
```json
{ "userName": d, "displayName": e, "roomFlag": f, "inviterUserName": g,
  "addChatRoomScene": h, "joinTime": i }
```
- `d` = username
- `e` = 群内显示名
- `f` = roomFlag（**2048 = 管理员**，由 P0() 中 `bVar3.f |= 2048` 写入）
- `g` = 邀请人用户名
- `h` / `i` = 加入场景 / 加入时间

### 2.3 ChatRoomData —— `so.a`

字段：`d: LinkedList(成员列表)`, `e/f/g/h/i: int 群属性`, `m/n: int`（m=版本号，由 V0 设置）

### 2.4 存储访问入口

- `com.tencent.mm.storage.a3` = **ChatroomStorage**（日志 "MicroMsg.ChatroomStorage"）
  - `t1(chatroomName)` → z2（按 chatroomname 查 chatroom 表）
  - `x1(chatroomName)` → memberlist 字符串
  - `u1(chatroomName)` → displayname
- `p02.a` = **ChatroomService**（日志 "MicroMsg.ChatroomService"），`a()` 返回 ChatroomStorage
- 常用取法：`((f) j1.v(f.class)).a().t1(chatroomName)` 或 `p02.a.INSTANCE.a().t1(...)`
- 便捷静态方法：`b41.u1.C(chatroomName, username)` —— 判断**群主或管理员**（L0 || E0）

---

## 3. 角色判断 API（Xposed 直接用）

```java
// 1. 获取群成员对象
com.tencent.mm.storage.z2 member = chatroomService.storage().t1(roomName);

// 2. 判定角色
boolean isOwner  = member.L0(username);   // 群主：field_roomowner.equals(username)
boolean isAdmin  = member.E0(username);   // 管理员：roomFlag & 2048
boolean isNormal = !isOwner && !isAdmin;  // 普通成员

// 3. 便捷方法（聊天 Item 中也常用）
boolean hasRule = b41.u1.C(roomName, username); // 群主或管理员
```

**证据（b41.u1.C 反编译）**：
```java
public static boolean C(String str, String str2) {
    z2 t1 = ((f) j1.v(f.class)).a().t1(str);
    if (t1 != null) {
        return t1.L0(str2) || t1.E0(str2);
    }
    return false;
}
```

**证据（z2.M0 反编译）**：
```java
public boolean M0() {
    if (y8.J0(field_roomowner)) return false;
    String u = b41.y1.u();                       // 当前登录 username
    if (j1.v(f.class) != null) {
        return (((f) j1.v(f.class)).b(field_chatroomname) && E0(u))
                || field_roomowner.equals(u);    // 群主 == 群管理开关 && 管理员 || 群主
    }
    ...
}
```

**证据（z2.E0 反编译）**：
```java
public boolean E0(String str) {
    b q0 = q0(str);
    return (q0 == null || (q0.f & 2048) == 0) ? false : true;
}
```

**证据（z2.L0 反编译）**：
```java
public boolean L0(String str) {
    return !y8.J0(field_roomowner) && field_roomowner.equals(str);
}
```

---

## 4. UI 层：聊天消息 Item（viewitems）注入点

### 4.1 关键类
- `com.tencent.mm.ui.chatting.viewitems.b0`（super 链 end 于 Object / NeatTextView.view.f / r6）= **ChattingItemBase**
  - `p(h0, d, e9, str)`：填充 userTV（昵称）—— 群聊时先判断 is_group_chat
  - `Z(h0, charSequence)`：`h0Var.userTV.setText(charSequence)` 并 setVisibility(0)
  - `x(e9, boolean, boolean)`：**获取显示名**；`e9.z0()==1` 返回自己 `y1.u()`；
    `z` 时 `aa.t(e9.j())`（群聊显示名）；否则 `e9.p0()`；兜底 `e9.N0()`（talker）
  - `v(d, e9)`：`y3.V4(d.x()) ? aa.t(e9.j()) : d.x()`
- `com.tencent.mm.ui.chatting.viewitems.h0` = **ViewHolder**
  - `userTV: TextView`（昵称）、`avatarIV: ChattingAvatarImageView`、`stateIV`、`timeTV` 等
- `go` = ChattingItemTextBase（消息 Layout 填充逻辑 `H() / g0() / f()`）
- `com.tencent.mm.ui.chatting.viewitems.uo` = ChattingItemText 工具类（`b()/d()/e()` 等）

### 4.2 群聊消息内容解析（发送者 username）
- 消息对象 `com.tencent.mm.storage.e9`（MsgInfo，父类 im.c8）
  - `j()` 为 fromusername/内容（群聊专用分支）
  - `N0()` = field_talker（群 ID，形如 `xxx@chatroom`）
  - `G0()` = 解析后的发送者
- `b41.aa.t(String)`：`u(str)` 定位，`str.substring(0, u)` —— 从群聊消息 XML 中解析发送者用户名

### 4.3 头衔叠加实现（推荐方案）

**方案 A：Hook Holder.userTV（推荐，改动小）**
```java
// Hook ChattingItemText 等填充方法 or 直接遍历 RecyclerView
XposedHelpers.findAndHookMethod("com.tencent.mm.ui.chatting.viewitems.b0",
        lpparam.classLoader, "Z",
        "com.tencent.mm.ui.chatting.viewitems.h0", CharSequence.class,
        new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                h0 holder = (h0) param.args[0];
                TextView userTV = (TextView) XposedHelpers.getObjectField(holder, "userTV");
                // 1) 取 talker(群) 与 sender
                // 2) 判断角色 成员.E0/L0
                // 3) 创建头衔 TextView 追加到 userTV 父布局，或设置 userTV 前景/背景 Span
            }
        });
```

**方案 B：Hook ChattingDataAdapter 的 convert/getView（控制力最强）**
- `com.tencent.mm.ui.chatting.adapter.ChattingDataAdapter`（含内部 `$Companion$buildItemConvertFactory$1` 等）
- 在 item 绑定完成后，按 sender username 判断角色，给布局添加/更新一个标签 View（LinearLayout H 排布：头像-昵称-头衔）

### 4.4 微信自带"成员列表"角色标识（对照组）
- `com.tencent.mm.chatroom.ui.SeeRoomMemberUI`（群成员列表）
- 其 Adapter `cc` 的 `d(List)` 反编译证据：
```java
if (z2Var.L0(n.b1()))      { this.h.add(new yb(1, n, 3)); }  // 群主
else if (z2Var.E0(n.b1())) { this.h.add(new yb(1, n, 2)); }  // 管理员
else                       { this.h.add(new yb(1, n, 1)); }  // 成员
```
→ 证实：**微信内部就是用 `L0()` 群主 / `E0()` 管理员 二分法 + 1/2/3 级别**，可直接复用。

---

## 5. 自定义颜色 / 背景的实现方法

微信无原生"头衔颜色"配置（strings.xml 里只有 "群主/管理员" 文案："%s · 管理员" 等）。
**颜色/背景完全由 Xposed 模块自己管理**，推荐做法：

1. 模块用 SharedPreferences / MMKV 保存三个角色配置：
   ```json
   { "owner":  {"text":"群主",  "fg":"#FFFFFF", "bg":"#FF3B30"},
     "admin":  {"text":"管理员","fg":"#FFFFFF", "bg":"#FF9500"},
     "member": {"text":"成员",  "fg":"#FFFFFF", "bg":"#8E8E93"} }
   ```
2. 注入时创建/复用一个小 TextView：
   ```java
   TextView badge = new TextView(ctx);
   badge.setText(roleName);
   badge.setTextColor(Color.parseColor(roleFg));
   GradientDrawable gd = new GradientDrawable();
   gd.setColor(Color.parseColor(roleBg));
   gd.setCornerRadius(dp(2));
   badge.setBackground(gd);
   badge.setPadding(dp(4), dp(1), dp(4), dp(1));
   badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
   ```
3. 放入昵称右侧：`LinearLayout nameLayout = (LinearLayout) userTV.getParent(); nameLayout.addView(badge, idx+1);`
4. 列表滚动复用注意：每次绑定先 removeView 缓存 badge 再按角色 add，避免串位。

---

## 6. 完整调用链速查表

| 目标 | 类/方法 |
|---|---|
| 群成员对象 | `com.tencent.mm.storage.z2`（ChatRoomMember） |
| 取群成员 | `p02.a.INSTANCE.a().t1(roomName)` 或 `((f) j1.v(f.class)).a().t1(...)` |
| 群主判断 | `z2.L0(username)` |
| 管理员判断 | `z2.E0(username)`（roomFlag & 2048） |
| 是否群主/管理员（复合） | `b41.u1.C(roomName, username)` |
| 当前用户是否群主 | `z2.M0()` |
| 群内显示名 | `z2.x0(username)` / `z2.q0(username).e` |
| 全部成员 | `z2.z0()` |
| 消息 Item 昵称 TextView | `viewitems.h0.userTV` |
| 填充昵称 | `viewitems.b0.p()` / `b0.Z()` |
| 显示名来源 | `b0.x(e9, z, z2)` / `b41.aa.t(e9.j())` |
| 微信自带角色分档 | `chatroom.ui.cc.d()` → yb(1, n, 3/2/1) |

---

## 7. 二次核查记录（本次分析）

- [x] ChatRoomMember(z2) 的 L0/E0/M0/q0/x0/z0 方法体已反编译确认（见第 3 节代码）
- [x] roomFlag 第 2048 位=管理员：P0() 中 `bVar3.f |= 2048` / E0() 中 `q0.f & 2048` 双向印证
- [x] 群主字段 = `field_roomowner`：L0() 中 `field_roomowner.equals(str)` 印证
- [x] 存储层 ChatroomStorage(a3) / ChatroomService(p02.a) 已定位，取法已验证
- [x] 聊天 Item：b0/h0/go/uo 定位，p/Z/x 方法与 holder.userTV 字段确认
- [x] 微信自带成员列表 cc.d() 用同样 L0/E0 分档（3群主/2管理员/1成员）——逻辑可复用
- [x] 自定义颜色/背景：无原生字段，均为模块侧注入配置（合理）
- [x] 结论：**无原生"群头衔"空间，需 Hook viewitems 叠加自定义 TextView**

---

## 8. 风险与注意事项

1. **版本适配**：混淆类名（z2/a3/p02.a/b0/h0 等）会随版本变化，建议用 DexKit 锚点
   （`"MicroMsg.ChatRoomMember"`、`"MicroMsg.ChatroomStorage"`、`"MicroMsg.ChattingItem"`）动态解析。
2. **群成员未拉取时 q0 为 null**：先判断 null 再读 roomFlag。
3. **列表复用错位**：RecyclerView Item 复用必须成对 remove/add badge。
4. **性能**：不要为每条消息实时查库；可在 userTV 绑定处带缓存（member 对象缓存到 WeakHashMap）。
5. **自身消息**：`e9.z0()==1` 时显示名为自己 `y1.u()`，模块应跳过或显示"我"。

---

*报告生成于 LSPilot 逆向分析，已完成二次核查。*
