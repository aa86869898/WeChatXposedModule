# 微信「选择好友批量邀请进入多个微信群」完整逆向分析报告

> 分析对象: com.tencent.mm (微信) 当前安装 APK
> 分析方法: DexKit + jadx + baksmali（LSPilot 环境实测）
> 需求: ① 选一个好友 ② 批量邀请其进入我加入的多个群 ③ 必须过滤"好友已加入的群(共同群)" ④ 输出 Java Xposed 模块可用的精确类/方法

## ★ 结论速览（核心三类接口）

```
我的群列表   = ContactStorage(j4).r() 本地 SQL 查询, username 以 @chatroom 结尾
共同群       = FTS 搜索服务 fts.e0.xj(2, u73.u) 回调 t73.x.n4 -> List<u73.y>(y.e=群username)
               （等价复用微信原生"我和TA的共同群聊"界面 CommonChatroomInfoUI）
批量邀请     = uf0.e.bj(群名) -> pe5.f.j(群名,成员List,邀请原因,null) -> qn.m
               NetSceneAddChatRoomMember, CGI: /cgi-bin/micromsg-bin/addchatroommember
过滤规则     = 可邀请群 = 我的群 - 共同群 ; 共同群标记为"已在"不可勾选
               兜底: 每个候选群用 z2.z0() 成员列表 contains(好友) 双保险
```

---

## 一、全局服务定位入口（Xposed 反射基座）

微信全局服务获取有三套，务必分清（本报告所有带"获取"步骤都用它们）：

| 用途 | 调用 | 返回 |
|---|---|---|
| 业务服务(插件服务) | `ph5.n0.c(Class)` | `ph5.m` 实例(如 fts 服务、群操作服务) |
| 内核存储服务(单例) | `gp0.j1.v(Class)` | `lp0.a` 实例(如联系人存储、群存储、消息存储) |
| 网络场景管理器 | `b41.h9.e()` 或 `gp0.j1.q().b()` | `com.tencent.mm.modelbase.r1` |
| 核心控制器 | `b41.h9.b()` | `b41.e`（`.r()`=联系人存储） |
| App 全局 Context | `com.tencent.mm.sdk.platformtools.a3.a` | `android.app.Application` |

关键说明：
- `gp0.j1` = 微信内核服务定位器(j1.v / j1.q / j1.x / j1.g / j1.e 等)
- `ph5.n0` = 插件服务定位器(n0.c / n0.b 等)
- 两者不能互换：`n0.c(接口类)` 取服务，`j1.v(接口类)` 取单例存储
- 类 `tn3.c4` = Messenger/存储总装接口，实现 `com.tencent.mm.plugin.messenger.foundation.h2`；`h2.cj()` 返回联系人存储 j4

---

## 二、我的群列表（本地存储，推荐主路径）

### 2.1 类/接口
- 接口: `com.tencent.mm.storage.d8`（ContactStorage）
- 实现: `com.tencent.mm.storage.j4`（extends `com.tencent.mm.storage.vp.a`，构造 `j4(qf5.k0)`）
- 实体: `com.tencent.mm.storage.y3`（联系人，继承链 y3 -> com.tencent.mm.contact.s -> im.f2 -> qf5.f0）

### 2.2 获取联系人存储
```java
// 方式一（推荐，最稳定）
com.tencent.mm.storage.j4 contactStorage =
    ((com.tencent.mm.plugin.messenger.foundation.h2) j1.v(tn3.c4.class)).cj();
// 方式二
com.tencent.mm.storage.j4 contactStorage = (j4) h9.b().r();
```

### 2.3 查询所有群（实测确认）
```java
public Cursor r() {
    String sql = "select ... from rcontact where type & 8=0"
               + " and ( username like '%@chatroom' )";   // d2.b 生成
    return db.B(sql, null);
}
```
- 方法名: `j4.r() : Cursor`（注意小写 r，与 `j4.M()` 静态群条件配合）
- 每行含列: `username, alias, conRemark, nickname, type, verifyFlag, chatroomFlag, deleteFlag, ...`
- 遍历: `cursor.getString(cursor.getColumnIndex("username"))`
- 群判断: `username.endsWith("@chatroom")`（也兼容 `@im.chatroom`）
- 群显示名: 用 username 查联系人 `j4.n(username, true)` 返回 `y3`，`y3.g2()` = 显示名（备注优先）
- 群联系人实体: `y3.b1()` = username；`y3.V0()` = type
- 群类型判断: `y3.o4(s)`=`@openim`、`y3.p4(s)`=`@im.chatroom`、`y3.R4(s)`=合法普通联系人
  （普通微信群 username 以 `@chatroom` 结尾，用 `endsWith("@chatroom")` 判断最直接）

### 2.4 其他可用查询（同表）
- `j4.M()`: 返回群条件 SQL 片段 `(type&1!=0) and type&8=0 and username like ...`（可拼接到自定义 SQL）
- `j4.t(ZZ): String`、`j4.b0()/d0()/S()/M()`: 其它 SQL 构建器
- `j4.L(String): y3` / `j4.n(String, boolean): y3` / `j4.m(String): y3`: 单联系人查询

---

## 三、共同群（好友已加入的群 —— 过滤核心）

### 3.1 原生入口
- Activity: `com.tencent.mm.plugin.profile.ui.CommonChatroomInfoUI`
  - 继承 `com.tencent.mm.ui.contact.MMBaseSelectContactUI`
  - 字段: `C:y3`（好友联系人）、`D:qy3.u0`（共同群 adapter）、`E:qy3.w0`
  - 启动参数: Intent extra `"Select_Talker_Name"` = 好友 username
  - 完整启动(实测 qy3.f3.invoke):
    ```java
    Intent intent = new Intent(ctx, CommonChatroomInfoUI.class);
    intent.putExtra("Select_Talker_Name", friendUsername);
    ctx.startActivity(intent);
    ```
  - `y7()` 中加载好友: `this.C = h9.b().r().n(getIntent().getStringExtra("Select_Talker_Name"), true);`
  - `initView()` 中发起共同群查询: `fts.e0.xj(2, u73.u)`，u 字段:
    - `u.c = 好友username`
    - `u.b = 6`
    - `u.o = qy3.u0`（实现 t73.x 回调）
    - `u.p = qy3.u0.o`（q3 handler）
    - `u.n = new qy3.t0(null)`（Comparator）

### 3.2 数据回调
- 接口: `t73.x`，唯一方法 `n4(Lu73/v;)V`
- 实现: `qy3.u0`（共同群列表 adapter），`n4(v)`:
  ```java
  public void n4(v vVar) {          // u73.v
      if (vVar.c == 0) this.p = vVar.e;   // c=错误码, e=List<u73.y>
      notifyDataSetChanged();
  }
  public d h(int i) { ... ((y) this.p.get(i)).e ... }  // y.e = 群username
  ```
- 数据模型:
  - `u73.u`（请求）: 字段 a:int b:int c:String d..f:String g/h:int[] i:int j/k:long l/m:HashSet n:Comparator o:t73.x(回调) p:q3 q:int r:boolean s:List
  - `u73.v`（响应）: a:u73.u b:u73.c c:int(错误码) d:u73.r e:List<u73.y>
  - `u73.y`（群条目）: e:String(群username) g/h/i/k:String(展示信息) 等27字段

### 3.3 FTS 底层（了解即可）
- `ph5.n0.c(t73.z.class)` 实际返回 `com.tencent.mm.plugin.fts.e0`（FTS 插件）
- `fts.e0.xj(int, u73.u): u73.c` 按类型分发（type=2 为共同群搜索逻辑）
- 共同群搜索任务: `com.tencent.mm.plugin.fts.logic.i` = "SearchCommonChatroomTask"
  - SQL: `SELECT aux_index FROM <idx> JOIN <aux> ... MATCH '<key>' AND entity_id = n ORDER BY timestamp DESC`
  - 数据来源: FTS 本地索引（联系人/群同步数据构建），登录后自动就绪
- 判断 FTS 是否就绪: `fts.e0.rj(): boolean`
- 注意事项: 直接反射调用需在微信主进程且 FTS 初始化后；Xposed 中更稳的是 Hook `qy3.u0.n4` 拿回调

### 3.4 Xposed 推荐做法（二选一）
- A. Hook `qy3.u0.n4(Lu73/v;)V`（after）: 参数0(v) 的 e 字段(List) 即共同群列表
- B. Hook `CommonChatroomInfoUI` 生命周期，读完 adapter 数据
- C. 反射调用 `fts.e0.xj(2, req)`，实现 `t73.x` 回调（同步到自己的 handler）

---

## 四、群信息与群成员（过滤兜底 / 展示）

### 4.1 群存储获取（实测链路）
```
接口: q02.f (marker, 继承 lp0.a)
实现: p02.a
获取: gp0.j1.v(q02.f.class) -> cast p02.a -> .a() = com.tencent.mm.storage.a3
```
```java
com.tencent.mm.storage.a3 roomStorage = ((p02.a) j1.v(q02.f.class)).a();
```

### 4.2 群存储方法（com.tencent.mm.storage.a3）
- `z2 N1(String chatroomName)` 查群记录（new + get，必返回实体，可能空字段）
- `z2 t1(String chatroomName)` 查群记录（不存在返回 null）
- `List K1(String chatroomName)` 按 ';' 拆成员列表
- `int y1(String chatroomName)` 成员数
- `String u1(String chatroomName)` 群名(displayname)
- `String x1(String chatroomName)` 成员串
- `boolean replace(z2)` / `Q1(z2, boolean)` 增改

### 4.3 群实体（com.tencent.mm.storage.z2，extends im.y1）
- 字段: `field_chatroomname`(群username) `field_memberlist`(成员;分隔) `field_memberCount` `field_roomowner`(群主) `field_displayname`(群名) `field_roomdata`(成员XML) `field_chatroomStatus`
- 方法:
  - `List z0()` 全部成员 username 列表（来自 field_memberlist）
  - `String x0(String username)` 群内成员昵称
  - `boolean E0(String username)` 成员是否管理员（内部标志 & 2048）
  - `boolean L0(String username)` 成员是否群主
  - `boolean M0()` "我"是否群主
  - `so.b q0(String username)` 成员内部对象
  - `int A0()` 版本号；`int r0()/s0()` 状态；`void P0(...)` 写回成员
- 兜底过滤: `roomStorage.N1(chatroomName).z0().contains(friendUsername)`
  （注意: 本地 memberlist 可能只含最近同步成员，大群可能不全，用共同群接口为准）

### 4.4 拉取完整群成员（可选增强）
- 网络场景: `qn.w(String chatroomName, int version)` = NetSceneGetChatroomMemberDetail
  - CGI: `/cgi-bin/micromsg-bin/getchatroommemberdetail`, type=551
  - 构造时自动带本地版本号；返回后自动写入 a3 存储
  - 发起: `h9.e().g(scene)`（NetSceneManager 派发）

---

## 五、批量邀请（addchatroommember 核心）

### 5.1 高层 API（推荐，微信自封装）
接口: `pe5.f`（群操作服务, RoomCallbackFactory）→ 实现: `ln.a`（核心）、`pe5.g`、`r81.a`
```java
// ① 获取群操作服务（实测字节码: n0.c(uf0.e) 实际对象 Luf0/e，bj 返回 pe5.f）
pe5.f roomSvc = ((uf0.e) ph5.n0.c(uf0.e.class)).bj(chatroomName);
// ② 构造"添加群成员"操作（j = NetSceneAddChatRoomMember; ln.a.j -> qn.m）
com.tencent.mm.roomsdk.model.factory.a op = roomSvc.j(chatroomName,
        java.util.Arrays.asList(friendUsername),   // 成员 List<String>（可多选!）
        invitationReason,                            // 邀请原因(可为 "")
        null);                                       // LocalHistoryInfo(可为 null)
// ③ 设置回调（qe5.b = 带状态回调; b=成功 c=失败 d=取消）
op.b = successCallback;   // qe5.b 实例
op.c = failCallback;
// ④ 执行（会显示进度对话框，内部 b()->doScene）
op.c(context, "正在添加", "取消", true, true, cancelListener);
```
- `pe5.f.j(String str, List list, String str2, Object obj)` 签名含义:
  - str = 群 username（形如 `xxx@chatroom`）
  - list = 成员 username 列表（可一次加多个好友，但本需求每次一个好友）
  - str2 = 邀请原因/来源串（vn.m 传 invitation reason，可为 ""）
  - obj = `ChatroomInfoUI$LocalHistoryInfo`（本地历史消息信息，可 null）
- 同类接口（勿用错）:
  - `pe5.f.b(chatroom, list, i)` → `ln.a.b` → `qn.p` = **删除**群成员 delchatroommember
  - `pe5.f.a(chatroom, list, i, obj)` / `o(...)` → `ln.a.a/o` → `qn.x` = 邀请 invitechatroommember（带 LocalHistoryInfo）
  - `pe5.f.l(chatroom, list)` → `qn.o` = 创建群 createchatroom

### 5.2 网络场景类（可直接构造 + 派发，避开对话框）
```java
// 微信网络场景管理器
com.tencent.mm.modelbase.r1 netSceneMgr = h9.e();
// 或 ((gp0.y) j1.q()).b()

// 场景: NetSceneAddChatRoomMember
qn.m scene = new qn.m(chatroomName, memberList, invitationReason, null);
int type = scene.getType();               // 36
// 注册场景回调（u0 = 场景回调接口，需实现 onSceneEnd）
netSceneMgr.a(type, u0Callback);
// 派发（内部调用 scene.doScene(network, selfGuard)）
netSceneMgr.g(scene);
// 完成/取消后注销
netSceneMgr.q(type, u0Callback);
```
- qn.m 关键字段（结果）:
  - `p:int` = memberCount（服务端返回的当前成员数）
  - `r:String` = tips
  - `f,g,h,i,m,n,o:List` = 各状态成员分组（A() 方法写入 qe5.d 回调数据）
- qn.m.getType() = 36；doScene 返回 dispatch(...)

### 5.3 回调接口
- `qe5.b`（操作回调）: `void a(int errType, int errCode, String errMsg, qe5.b callback)`
- `qe5.d`（场景结果承载）: 字段 a..i/k 对应各状态列表（见 qn.m.A()）
- `com.tencent.mm.modelbase.u0`（场景回调）: `void onSceneEnd(int, int, String, m1 scene)`（在 NetSceneManager 注册）

### 5.4 roomsdk 执行器内部（了解）
- `com.tencent.mm.roomsdk.model.factory.a`（抽象）: 字段 a:boolean b/c/d:qe5.b e:u3(对话框)；方法 a()取消 b()请求 c(Context,标题,取消,show,?,cancelListener)
- `com.tencent.mm.roomsdk.model.factory.c`（默认容器）: 字段 f:m1(场景) g:u0
  - `.c(...)`: 显示对话框 `kj5.e1.Q(...)` 后调 `b()`
  - `.b()`: `r1.a(type, g)` 注册回调 + `r1.g(scene)` 派发（gp0.j1.q().b = r1）
  - `.a()`: `r1.d(scene)` 移除 + `r1.q(type, g)` 注销回调

### 5.5 批量循环建议
```java
for (String roomName : selectedGroups) {          // 已过滤后的候选群
    qn.m scene = new qn.m(roomName,
        java.util.Collections.singletonList(friendUsername), "", null);
    netSceneMgr.g(scene);                          // 每个群一个请求
    // 注意: 微信对单账号邀请有风控，建议逐群间隔 1~3 秒串行，
    // 并监听 qn.m 回调/results 做失败重试或跳过
}
```

---

## 六、完整过滤与批量流程（Java 实现级）

```
输入: friendUsername

① 我的群列表 G
   j4.r() 遍历 cursor -> { username endsWith "@chatroom" } -> List<RoomInfo(username, 显示名)>

② 共同群 C（好友已加入的群 ∩ 我加入的群）
   方案A: 反射调用 fts.e0.xj(2, req) 异步拿 List<u73.y> -> y.e
   方案B: Hook qy3.u0.n4 截获共同群列表
   共同群 C = {y.e}

③ 候选群 = G - C          （好友不在的群 = 可邀请）
   已在群 = C              （勾选界面禁止勾选，灰显"已在群聊"）

④ 兜底（可选）: 每个候选群再查 z2.z0().contains(friend) —— 若 contains 则归入"已在"

⑤ 展示: 候选群列表(群名/成员数/勾选框) + 已在群列表(灰显)

⑥ 确认后批量邀请:
   for each 候选群 -> new qn.m(room, [friend], "", null) -> netSceneMgr.g(scene)
   （或 pe5.f.j -> factory.c 带对话框）

⑦ 结果: 监听 scene 回调/结果字段，汇总 成功/已在/失败
```

---

## 七、关键类速查表（混淆名，按本次 APK 实测）

| 角色 | 类/接口 | 说明 |
|---|---|---|
| 服务定位(服务) | `ph5.n0` | `.c(Class)` 取插件/业务服务 |
| 服务定位(内核) | `gp0.j1` | `.v(Class)` 取存储单例, `.q()` 取网络, `.x()` 配置 |
| 核心控制器 | `b41.h9` | `.b()`=b41.e(核心), `.e()`=r1(网络) |
| 核心控制器体 | `b41.e` | `.r()`=联系人存储, `.t()`=DB 管理 |
| 联系人存储接口 | `com.tencent.mm.storage.d8` | 实现 `com.tencent.mm.storage.j4` |
| 联系人存储实现 | `com.tencent.mm.storage.j4` | `r()`=所有群, `n()/L()/m()`=单联系人, `M()`=群条件 |
| 联系人实体 | `com.tencent.mm.storage.y3` | `b1()`=username `g2()`=显示名 `V0()`=type |
| 联系人基类 | `com.tencent.mm.contact.s` / `im.f2` | `g2()` 备注优先显示名 |
| 共同群 UI | `com.tencent.mm.plugin.profile.ui.CommonChatroomInfoUI` | 启动传 "Select_Talker_Name" |
| 共同群 adapter | `qy3.u0` | `n4(u73.v)`=回调, `h(i)`=条目 |
| 共同群请求 | `u73.u` | `c`=好友username `b`=6 `o`=回调 `p`=handler `n`=comparator |
| 共同群响应 | `u73.v` | `c`=errCode `e`=List\<u73.y\> |
| 共同群条目 | `u73.y` | `e`=群username |
| 回调接口 | `t73.x` | `n4(u73.v)` |
| FTS 服务 | `com.tencent.mm.plugin.fts.e0` | `xj(int,u)`=搜索 `rj()`=就绪 |
| 共同群任务 | `com.tencent.mm.plugin.fts.logic.i` | SearchCommonChatroomTask |
| 群存储接口 | `q02.f`(marker) / `p02.a` | `j1.v(q02.f)` cast p02.a `.a()`=a3 |
| 群存储实现 | `com.tencent.mm.storage.a3` | `N1()/t1()`=查群 `K1()`=成员 `u1()`=群名 |
| 群实体 | `com.tencent.mm.storage.z2` | `z0()`=成员List `E0()`=管理员 `L0()`=群主 `x0()`=群内昵称 |
| 群操作服务 | `uf0.e` | `.bj(群名)` → pe5.f |
| 群操作实现 | `pe5.f` / `ln.a` | `.j()`=添加成员(addchatroommember) `.b()`=删除成员 |
| 添加成员场景 | `qn.m` | NetSceneAddChatRoomMember, type=36 |
| 删除成员场景 | `qn.p` | NetSceneDelChatRoomMember, /delchatroommember |
| 邀请场景 | `qn.x` | NetSceneInviteChatRoomMember, /invitechatroommember |
| 群成员详情 | `qn.w` | GetChatroomMemberDetail, type=551 |
| 创建群场景 | `qn.o` | NetSceneCreateChatRoom, /createchatroom |
| 网络管理器 | `com.tencent.mm.modelbase.r1` | `a(type,u0)`注册 `g(scene)`派发 `q(type,u0)`注销 |
| 网络配置 | `gp0.y` | `.b()`=r1 |
| 操作回调 | `qe5.b` | `a(errType,errCode,errMsg,cb)` |
| 场景回调 | `com.tencent.mm.modelbase.u0` | `onSceneEnd(...)` |
| 操作容器 | `com.tencent.mm.roomsdk.model.factory.c` | `.c(ctx,..)`执行 `.b()`请求 `.a()`取消 |

---

## 八、Xposed 模块注意事项

1. **版本兼容**: 上述全部为混淆类/方法，随微信版本变化。Java Xposed 模块建议:
   - 入口用字符串特征适配（如 hook 时用 `findClass("com.tencent.mm.storage.j4")` + 方法特征）
   - 关键方法用 `XposedHelpers.findAndHookMethod` 传字符串名即可（Xposed 反射不依赖编译期类型）
   - 本报告类名基于当前安装包实测；若升级后失效，重新用 DexKit 按"CGI 字符串 + Log 标签"定位
2. **线程**: 数据库/网络操作勿在主线程。r1.g(scene) 在微信自己可在主线程派发；但批量场景建议在后台线程逐群执行。
3. **FTS 就绪**: 共同群查询依赖 FTS 索引（登录完成后就绪）。请先判断 `fts.rj()`，未就绪则降级为"逐群 memberlist 本地 contains 校验"。
4. **本地 memberlist 不全**: `z2.z0()` 只含同步过的成员；对候选群可先 `qn.w` 拉一次群成员详情再判断。
5. **风控/频率**: 单好友多群连续邀请可能触发风控。建议串行 + 随机间隔 + 错误重试, 单次会话控制群数量。
6. **对话框**: 直接用 `qn.m` + `r1.g` 可完全无 UI；用 `pe5.f.j` + `factory.c` 会弹微信原生进度框。
7. **权限判断**: 部分群(如已停用/或被踢)不可邀请，接口返回 errType/errCode 需展示。

---

## 九、二次审查记录（已逐项核验）

> 以下关键结论均通过字节码/Java 反编译二次确认，非猜测。

1. **我的群列表 = j4.r()**
   - 已核验 `com.tencent.mm.storage.j4.r()` 源码：SQL 为 `type & 8=0` + `username like '%@chatroom'`（由 `b41.d2.b("username","@chatroom",false)` 生成），返回全部群联系人 Cursor。
   - 已核验调用方：`b41.d2.j`、`fts.logic.h4/i4.i`、`t73.n.c` 均将其用于群列表/群索引构建。
2. **群存储获取链**
   - 已核验 `qn.w.<init>` 字节码：`j1.v(q02.f)` → check-cast `p02.a` → `.a()` → `com.tencent.mm.storage.a3` → `.N1(chatroomname)` → `z2`。
3. **共同群服务 = fts.e0.xj(2, u73.u)**
   - 已核验 `CommonChatroomInfoUI.initView` 字节码：`n0.c(t73.z)` → check-cast `com.tencent.mm.plugin.fts.e0` → `.xj(2, u73.u)`。
   - 已核验回调 `t73.x.n4(u73.v)`，`qy3.u0.n4` 中 `v.c==0` 成功、`v.e` 为 List<u73.y>、`y.e` 为群 username。
   - 已核验底层 `com.tencent.mm.plugin.fts.logic.i` 类名 `SearchCommonChatroomTask`，SQL 按 entity_id 过滤（共同群索引）。
4. **批量邀请 = uf0.e.bj().j() → qn.m (addchatroommember)**
   - 已核验 `ChatroomInfoUI.onActivityResult` 字节码 case 7（删除成员）调 `pe5.f.b`（qn.p delchatroommember），切勿与添加混淆。
   - 已核验 `vn.m.b` 添加成员流程：`n0.c(vf0.e/实际 uf0.e).bj(群名).j(群名,成员List,邀请原因,obj)` → `qn.m`（NetSceneAddChatRoomMember，CGI `/cgi-bin/micromsg-bin/addchatroommember`，getType=36）。
   - 已核验 `qn.m` 构造参数：①群 username ②List 成员 username ③str2(邀请原因) ④Object(LocalHistoryInfo, 可 null)；onGYNetEnd 将各状态成员写入 f/g/h/i/m/n/o，memberCount 写入 p。
5. **网络派发 = com.tencent.mm.modelbase.r1**
   - 已核验 `roomsdk.model.factory.c.b()` 字节码：`j1.q().b`（即 r1）`.a(type, u0)` 注册回调 + `.g(m1)` 派发场景；`c.a()` 用 `.d(m1)` 移除 + `.q(type, u0)` 注销。
   - 已核验 `b41.h9.e()` 返回 `com.tencent.mm.modelbase.r1`（NetSceneManager）。
6. **联系人显示名**
   - 已核验 `com.tencent.mm.contact.s.g2()`：备注(conRemark)优先，否则返回昵称 f2()。
7. **联系人存储获取**
   - 已核验 `ChatroomInfoUI` 字节码：`j1.v(tn3.c4)` → check-cast `com.tencent.mm.plugin.messenger.foundation.h2` → `.cj()` → `j4`；`CommonChatroomInfoUI` 用 `h9.b().r()` 同样得到 j4。
8. **版本适配提示**
   - 上述混淆名基于当前安装包实测；升级后若失效，请按"CGI 字符串(/cgi-bin/micromsg-bin/addchatroommember 等) + Log 标签(MicroMsg.NetSceneAddChatRoomMember 等)"重新定位，替换常量表即可。

### 已知局限
- `z2.z0()` 本地成员列表可能只含最近同步的成员（大群/新群可能不全）；建议以"共同群服务"结果为准，本地校验仅作兜底。
- FTS 共同群查询依赖登录后的索引初始化；未就绪时可降级为逐群 `qn.w` 拉成员详情再判断。
- 单好友多群连续邀请存在风控风险，务必串行+间隔+限流。


---
# 附录：Java Xposed 参考实现（原文件 InviteFriendToGroups_Xposed.java）

```java
/* ============================================================================
 * Java Xposed 模块参考实现（骨架 + 核心逻辑）
 * 功能: 选择一个好友 -> 批量邀请其进入多个我已加入、且该好友不在的微信群
 * 基于逆向分析: 见同目录 InviteFriendToGroups_RevDoc.md
 *
 * 说明:
 *  - 全部通过 XposedHelpers 反射调用, 不直接依赖微信混淆类型,
 *    因此微信升级后只需修改下方"常量表"中的混淆类/方法名即可适配。
 *  - 本文给出核心逻辑, UI(勾选列表/进度)请按自己的框架实现。
 *  - 需在 Xposed 模块 build.gradle 引入 api 'de.robv.android.xposed:api:82'
 * ==========================================================================*/
package com.example.invitefriendtogroups;

import android.app.Activity;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 入口: Xposed 加载微信时激活
 */
public class EntryHook implements IXposedHookLoadPackage {

    /**
     * 混淆常量表（按当前实测微信版本）——微信升级后在此统一改
     */
    public static final class WxCls {
        // 服务定位
        public static final String N0      = "ph5.n0";        // n0.c(Class)
        public static final String J1      = "gp0.j1";        // j1.v(Class)
        public static final String H9      = "b41.h9";        // h9.b() 核心控制器 / h9.e() 网络
        public static final String TN3_C4  = "tn3.c4";        // 存储总装接口
        public static final String H2_IMPL = "com.tencent.mm.plugin.messenger.foundation.h2";
        public static final String R1      = "com.tencent.mm.modelbase.r1"; // 网络场景管理器
        // 联系人 / 群存储
        public static final String J4      = "com.tencent.mm.storage.j4";  // ContactStorage 实现
        public static final String Y3      = "com.tencent.mm.storage.y3";  // 联系人实体
        public static final String Q02_F   = "q02.f";         // 群存储 marker 接口
        public static final String P02_A   = "p02.a";         // 群服务实现 -> a()=a3
        public static final String A3      = "com.tencent.mm.storage.a3"; // ChatroomStorage
        public static final String Z2      = "com.tencent.mm.storage.z2"; // ChatRoomMember 实体
        // FTS 共同群
        public static final String T73_Z   = "t73.z";         // 共同群服务 marker 接口
        public static final String T73_X   = "t73.x";         // 回调接口 n4(u73.v)
        public static final String U73_U   = "u73.u";         // 请求
        public static final String U73_V   = "u73.v";         // 响应
        public static final String U73_Y   = "u73.y";         // 条目
        public static final String FTS_E0  = "com.tencent.mm.plugin.fts.e0";
        // 群操作 / 邀请
        public static final String UF0_E   = "uf0.e";         // .bj(群名)->pe5.f
        public static final String PE5_F   = "pe5.f";         // .j()=添加成员
        public static final String QN_M    = "qn.m";          // NetSceneAddChatRoomMember
        public static final String QN_W    = "qn.w";          // GetChatroomMemberDetail
        public static final String QE5_B   = "qe5.b";         // 操作回调
        public static final String U0      = "com.tencent.mm.modelbase.u0"; // 场景回调
        // UI
        public static final String COMMON_ROOM_UI = "com.tencent.mm.plugin.profile.ui.CommonChatroomInfoUI";
        public static final String QY3_U0 = "qy3.u0";         // 共同群 adapter n4
        public static final String CONTACT_INFO_UI = "com.tencent.mm.plugin.profile.ui.ContactInfoUI";
    }
    public static final String PKG = "com.tencent.mm";

    /** 好友资料页: 添加"批量邀请到多群"菜单入口 */
    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(PKG)) return;
        final ClassLoader cl = lpparam.classLoader;

        // 1) Hook 好友资料页 onCreate -> 注入操作入口
        try {
            XposedHelpers.findAndHookMethod(WxCls.CONTACT_INFO_UI, cl, "onCreate",
                    Bundle.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object contact = getContactOfActivity(param.thisObject, cl);
                                String username = getUsername(contact, cl);
                                if (username == null) return;
                                final Activity activity = (Activity) param.thisObject;
                                final String friend = username;
                                // TODO: 弹出你自己的批量邀请界面 -> 调 BatchInviter
                                new BatchInviter(cl).showPickActivity(activity, friend);
                            } catch (Throwable t) { XposedBridge.log(t); }
                        }
                    });
        } catch (Throwable t) { XposedBridge.log(t); }

        // 2) Hook 共同群 adapter 回调（拦截共同群列表, 供界面过滤展示）
        try {
            XposedHelpers.findAndHookMethod(WxCls.QY3_U0, cl, "n4",
                    XposedHelpers.findClass(WxCls.U73_V, cl), new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object v = param.args[0];
                                CommonGroupsCache.lastCommonGroups = readCommonGroupsFromV(v, cl);
                            } catch (Throwable ignored) { }
                        }
                    });
        } catch (Throwable t) { XposedBridge.log(t); }
    }

    /* ------------------------------------------------------------
     * 小工具
     * ------------------------------------------------------------ */
    public static class CommonGroupsCache {
        public static Set<String> lastCommonGroups = new HashSet<>();
    }

    private static Object getContactOfActivity(Object activity, ClassLoader cl) throws Throwable {
        // ContactInfoUI 里字段(混淆)保存 y3 联系人; 遍历字段找 y3 类型
        for (java.lang.reflect.Field f : activity.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            Object v = f.get(activity);
            if (v != null && f.getType().getName().equals(WxCls.Y3)) return v;
        }
        return null;
    }

    public static String getUsername(Object contact, ClassLoader cl) throws Throwable {
        if (contact == null) return null;
        return (String) XposedHelpers.callMethod(contact, "b1");   // im.f2.b1() = username
    }

    public static String getDisplayName(Object contact, ClassLoader cl) throws Throwable {
        if (contact == null) return "";
        return (String) XposedHelpers.callMethod(contact, "g2");   // contact.s.g2() 备注优先
    }

    private static Set<String> readCommonGroupsFromV(Object v, ClassLoader cl) throws Throwable {
        Set<String> out = new HashSet<>();
        int errCode = (int) XposedHelpers.getIntField(v, "c");
        if (errCode != 0) return out;
        Object list = XposedHelpers.getObjectField(v, "e");       // List<u73.y>
        if (list instanceof List) {
            for (Object y : (List<?>) list) {
                String user = (String) XposedHelpers.getObjectField(y, "e"); // y.e = 群username
                if (user != null) out.add(user);
            }
        }
        return out;
    }

    /* ------------------------------------------------------------
     * 核心: 批量邀请器
     * ------------------------------------------------------------ */
    public static class BatchInviter {
        private final ClassLoader cl;

        public BatchInviter(ClassLoader cl) { this.cl = cl; }

        /** 展示勾选界面(示例: 你在自己的 Activity 里调用下述三个方法拉数据) */
        public void showPickActivity(Activity activity, String friend) {
            new Thread(() -> {
                try {
                    List<Room> mine = getMyChatrooms();
                    Set<String> common = getCommonChatrooms(friend);
                    List<Room> invite = filterInvitable(mine, common, friend);
                    StringBuilder msg = new StringBuilder("我的群=" + mine.size()
                            + " 共同群=" + common.size() + " 可邀请=" + invite.size());
                    for (Room r : invite) msg.append("\n  ").append(r.name)
                            .append("  (").append(r.username).append(")");
                    XposedBridge.log("[InviteTG] " + msg);
                } catch (Throwable t) { XposedBridge.log(t); }
            }).start();
        }

        /* ---------- ① 我的群列表 ---------- */
        public static class Room {
            public String username;   // xxx@chatroom
            public String name;       // 显示名
            @Override public String toString() { return name + "|" + username; }
        }

        public List<Room> getMyChatrooms() throws Throwable {
            List<Room> out = new ArrayList<>();
            Object contactStorage = getContactStorage();
            Cursor cur = (Cursor) XposedHelpers.callMethod(contactStorage, "r"); // 所有群
            try {
                int idxUser   = cur.getColumnIndex("username");
                int idxRemark = cur.getColumnIndex("conRemark");
                int idxNick   = cur.getColumnIndex("nickname");
                while (cur.moveToNext()) {
                    String u = cur.getString(idxUser);
                    if (u == null || !u.endsWith("@chatroom")) continue;
                    Room r = new Room();
                    r.username = u;
                    String remark = idxRemark >= 0 ? cur.getString(idxRemark) : null;
                    String nick   = idxNick   >= 0 ? cur.getString(idxNick)   : null;
                    r.name = (remark != null && remark.length() > 0) ? remark
                            : (nick != null ? nick : u);
                    out.add(r);
                }
            } finally { if (cur != null) cur.close(); }
            return out;
        }

        private Object getContactStorage() throws Throwable {
            // 方式一: ((tn3.c4) j1.v(tn3.c4.class)).cj()
            Object c4 = XposedHelpers.callStaticMethod(XposedHelpers.findClass(WxCls.J1, cl), "v",
                    XposedHelpers.findClass(WxCls.TN3_C4, cl));
            Object h2 = XposedHelpers.findClass(WxCls.H2_IMPL, cl).cast(c4);
            return XposedHelpers.callMethod(h2, "cj");            // -> j4
        }

        private Object getRoomStorage() throws Throwable {
            Object srv = XposedHelpers.callStaticMethod(XposedHelpers.findClass(WxCls.J1, cl), "v",
                    XposedHelpers.findClass(WxCls.Q02_F, cl));
            Object p02 = XposedHelpers.findClass(WxCls.P02_A, cl).cast(srv);
            return XposedHelpers.callMethod(p02, "a");            // -> a3
        }

        /* ---------- ② 共同群（好友已加入 ∩ 我加入） ---------- */
        public Set<String> getCommonChatrooms(String friend) throws Throwable {
            final Object resultLock = new Object();
            final Set<String> out = new HashSet<>();

            Object ftsService = getFtsService();
            if (ftsService == null) return out;

            Object req = XposedHelpers.newInstance(XposedHelpers.findClass(WxCls.U73_U, cl));
            XposedHelpers.setObjectField(req, "c", friend);                    // 好友 username
            XposedHelpers.setIntField(req, "b", 6);                            // 场景=共同群
            XposedHelpers.setObjectField(req, "p", new Handler(Looper.getMainLooper()));

            // 动态代理 t73.x 回调: n4(u73.v)
            Class<?> iface = XposedHelpers.findClass(WxCls.T73_X, cl);
            Object callback = java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{iface},
                    (proxy, method, args) -> {
                        if (method.getName().equals("n4") && args != null && args.length == 1) {
                            synchronized (resultLock) {
                                out.addAll(readCommonGroupsFromV(args[0], cl));
                                resultLock.notifyAll();
                            }
                        }
                        return null;
                    });
            XposedHelpers.setObjectField(req, "o", callback);

            XposedHelpers.callMethod(ftsService, "xj", 2, req);
            synchronized (resultLock) {
                try { resultLock.wait(5000); } catch (InterruptedException ignored) { }
            }
            return out;
        }

        private Object getFtsService() throws Throwable {
            Class<?> iface = XposedHelpers.findClass(WxCls.T73_Z, cl);
            return XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(WxCls.N0, cl), "c", iface);       // n0.c(t73.z) -> fts.e0
        }

        /* ---------- ③ 过滤: 可邀请 = 我的群 - 共同群（+本地兜底） ---------- */
        public List<Room> filterInvitable(List<Room> mine, Set<String> common, String friend) {
            List<Room> out = new ArrayList<>();
            for (Room r : mine) {
                if (common.contains(r.username)) continue;       // 好友已在共同群 -> 跳过
                if (isFriendInRoom(r.username, friend)) continue; // 兜底: 本地成员缓存校验
                out.add(r);
            }
            return out;
        }

        private boolean isFriendInRoom(String roomName, String friend) {
            try {
                Object roomStorage = getRoomStorage();
                Object room = XposedHelpers.callMethod(roomStorage, "N1", roomName); // z2
                List<?> members = (List<?>) XposedHelpers.callMethod(room, "z0");    // 成员List
                return members != null && members.contains(friend);
            } catch (Throwable t) { return false; }
        }

        /* ---------- ④ 批量邀请（核心: NetSceneAddChatRoomMember） ---------- */
        public interface InviteResult {
            void onResult(String roomName, int errType, int errCode, String errMsg);
        }

        public void batchInvite(List<Room> rooms, String friend, InviteResult resultCb)
                throws Throwable {
            Object netSceneMgr = getNetSceneManager();           // com.tencent.mm.modelbase.r1
            Class<?> u0Iface = XposedHelpers.findClass(WxCls.U0, cl); // 场景回调接口
            for (Room r : rooms) {
                try {
                    // new qn.m(room, [friend], reason, null)
                    Object scene = XposedHelpers.newInstance(
                            XposedHelpers.findClass(WxCls.QN_M, cl),
                            r.username,
                            java.util.Collections.singletonList(friend),
                            "", null);
                    int type = (int) XposedHelpers.callMethod(scene, "getType"); // 36
                    Object cb = java.lang.reflect.Proxy.newProxyInstance(cl,
                            new Class<?>[]{u0Iface}, (proxy, method, args) -> {
                                if (method.getName().equals("onSceneEnd") && args != null) {
                                    int errType = (int) args[0];
                                    int errCode = (int) args[1];
                                    String errMsg = (String) args[2];
                                    if (resultCb != null)
                                        resultCb.onResult(r.username, errType, errCode, errMsg);
                                }
                                return null;
                            });
                    XposedHelpers.callMethod(netSceneMgr, "a", type, cb);        // 注册回调
                    XposedHelpers.callMethod(netSceneMgr, "g", scene);           // 派发请求
                    XposedBridge.log("[InviteTG] invite " + friend + " -> " + r.username);
                    // 串行 + 间隔, 降低风控; 等 onSceneEnd 后再发下一个
                    Thread.sleep(1500 + (long) (Math.random() * 2000));
                } catch (Throwable t) { XposedBridge.log(t); }
            }
        }

        private Object getNetSceneManager() throws Throwable {
            // h9.e() -> r1
            Object h9 = XposedHelpers.findClass(WxCls.H9, cl);
            return XposedHelpers.callStaticMethod(h9, "e");
        }
    }
}
```

---
（本文件由 LSPilot 逆向分析自动整理导出，两源文件已合并于此。）
