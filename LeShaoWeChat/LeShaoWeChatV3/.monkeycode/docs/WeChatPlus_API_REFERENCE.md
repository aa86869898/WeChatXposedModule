# 微信内部类/方法完整参考手册
## WeChat Internal API Reference — WeChat 8.0.x

---

## 一、核心数据层

### 1.1 MsgInfo — 消息数据对象
```
类: com.tencent.mm.storage.e9

关键方法:
  getType()           → int      消息类型码
  getMsgId()          → long     本地消息ID
  getContent()        → String   消息内容 (混淆名: I0())
  getCreateTime()     → long     创建时间(毫秒)
  setType(int)        → void     设置消息类型 (混淆名: A1(I)V)
  d1(String)          → void     设置撤回提示文本
  P1(e9)              → e9       克隆消息对象
  convertFrom(Cursor) → void     从数据库Cursor恢复
  convertTo()         → ContentValues  序列化为数据库记录
  
 字段(混淆):
  field_type          → int      消息类型
  field_content       → String   消息内容
  field_talker        → String   会话ID(对方username)
  field_isSend        → int      1=已发送 0=接收
  field_msgSvrId      → long     服务器消息ID
  field_createTime    → long     创建时间
  field_status        → int      消息状态

消息类型码 (getType()返回值):
  1         = 文本消息
  3         = 图片消息
  34        = 语音消息
  43        = 视频消息
  47        = 表情消息
  49        = AppMsg(链接/文件/小程序)
  10002     = 已撤回 (0x2712)
  285222674 = 另一种撤回标记 (Proto撤回使用)
  436207665 = 红包通知 (0x1A000031)
  922746929 = 某种特殊消息
```

### 1.2 MessageStorage — 消息存储服务
```
类: com.tencent.mm.storage.f9 (混淆名，实际名字可能是其他)

关键方法:
  Ra(long msgId, e9 msgInfo)  → void      插入/更新消息 (核心写入)
  qU(String talker)           → Cursor    查询会话消息
  t6(String talker)           → e9        获取最后一条消息
  T2(String talker, long svrId) → e9      按服务器ID查找消息

全局访问: d9.b().u() 返回 MessageStorage 实例
```

### 1.3 ConversationStorage — 会话存储
```
类: d9.b().r() 返回

关键方法:
  p(String talker) → j2 (Conversation对象)
  Y(j2, String, boolean, boolean) → int  更新会话
```

---

## 二、撤回机制 (3条路径)

### 2.1 路径1: XML撤回
```
af5.b → 识别XML中的 revokemsg
  - 构造函数: b(Map xmlData, e9 msgInfo)
  - b() → boolean  是否识别为撤回消息 (检查是否包含"revokemsg")
  - c() → void     检查超时

af5.a → 执行撤回逻辑 (Runnable)
  - 构造函数: a(af5.b bVar)
  - run() → void   执行撤回:
    ① bVar.b.setType(10002)           // 修改类型为"已撤回"
    ② d1.J(getString(2131758563), "", bVar.b, "")  // 设置撤回提示
    ③ d9.b().u().Ra(bVar.b.getMsgId(), bVar.b)     // 更新数据库
```

### 2.2 路径2: Protobuf撤回
```
e01.u → Protobuf格式撤回处理
  - q7(String talker, Map data, p0 param) → q0    解析protobuf消息
  - f(String talker, long svrId, p0 p0Var, String str2, String str3, String str4) → void
    核心撤回方法，6个参数:
      talker:  会话ID
      svrId:   服务器消息ID
      p0Var:   协议参数(包含b字段=是否已收到)
      str2:    撤回者
      str3:    附加信息
      str4:    日志标签
    内部调用:
      - d9.b().u().T2(str, j) → 按svrId查找消息
      - T2.getType() → 检查消息类型
      - T2.setType(285222674) → 修改类型
      - T2.d1(str2) → 设置撤回提示
      - d9.b().u().Ra(msgId, msg) → 更新数据库

e01.w → 枚举类 (撤回相关标记)
```

### 2.3 路径3: UI撤回监听
```
com.tencent.mm.ui.chatting.RevokeMsgListener
  - 构造函数: RevokeMsgListener(mc chattingContext, Activity activity)
  - callback(IEvent event) → boolean   撤回事件回调 (返回false阻止)
  - c(e9 msgInfo) → void               处理撤回消息显示

com.tencent.mm.autogen.events.RevokeMsgEvent
  - 撤回事件定义
```

---

## 三、聊天输入与发送

### 3.1 ChatFooter — 聊天输入框
```
类: com.tencent.mm.pluginsdk.ui.chat.ChatFooter

关键方法:
  F(e9 msgInfo, a35.g callback) → boolean  核心发送方法
    - 参数1: 消息对象(storage.e9)
    - 参数2: 发送回调接口(a35.g)
    - 返回: true=发送成功
  
  getEditingLength() → String   获取当前输入长度(用于字数限制检查)
  G() → void                    发送相关(可能发送语音/文件)
  H() → void                    发送相关
  setDelaySendAnim(boolean)     设置延迟发送动画
  A(boolean)  → void            切换输入模式(文字/语音)
  B(boolean)  → void            切换输入模式
```

### 3.2 ChatFooterCustom — 自定义聊天底部
```
类: com.tencent.mm.ui.chatting.ChatFooterCustom

关键方法:
  onClick(View) → void           发送按钮点击处理
  getTalkerUserName() → String   获取当前会话ID
  getSender() → String           获取发送者username
  setTalker(y3 talker)           设置当前会话对象
  r(String, String, String, String, int, int, int, String, String, String) → void
    发送消息的完整方法(10个参数):
      参数1-4: 内容相关字符串
      参数5-7: int类型标志
      参数8-10: 附加信息
  g1(r0) → void                  发送消息(另一个入口)
  L0(p0) → void                  发送完成回调
```

### 3.3 y3 — 会话对象(Talker)
```
类: com.tencent.mm.storage.y3
  i1() → String   获取username
  O0() → long     获取lastSeq
  F0() → long     获取firstUnDeliverSeq
```

---

## 四、聊天界面

### 4.1 ChattingUIFragment
```
类: com.tencent.mm.ui.chatting.ChattingUIFragment

关键方法:
  setMMTitle(String)           设置标题栏
  setMMSubTitle(String)        设置副标题
  getLayoutView() → View      获取布局
  dealContentView(View)        处理内容区域
  finish()                     关闭聊天
  onCreate(Bundle)
  onResume()
```

### 4.2 ChattingUI (Activity)
```
类: com.tencent.mm.ui.chatting.ChattingUI
  - 聊天界面Activity，实际逻辑在ChattingUIFragment中
```

---

## 五、会话列表

### 5.1 MvvmConvList — 会话列表Adapter
```
类: com.tencent.mm.ui.conversation.adapter.MvvmConvList

构造函数:
  MvvmConvList(ao3.f, zn3.n0, Lifecycle, List) → 初始化会话列表
```

### 5.2 LauncherUI — 主界面
```
类: com.tencent.mm.ui.LauncherUI

关键方法:
  getInstance() → LauncherUI        获取单例
  getCurrentTabIndex() → int        当前Tab (0=微信,1=通讯录,2=发现,3=我)
  b7() → MainTabUI                  获取底部Tab对象
  getHomeUI() → HomeUI              获取HomeUI
  startChatting(String, Bundle, boolean)  打开聊天
  closeChatting(boolean)            关闭聊天
```

### 5.3 MainTabUI — 底部Tab
```
类: com.tencent.mm.ui.MainTabUI

关键方法:
  a(int index)               切换Tab
  b(int index, int count)    设置角标数字
  c(int index)               设置Tab属性
  d()                        初始化
  i() → int                  获取当前Tab
  l(int index)               清除角标
```

---

## 六、红包系统

### 6.1 LuckyMoneyBeforeDetailUI — 开红包界面
```
类: com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyBeforeDetailUI

关键方法:
  onCreate(Bundle)                        创建界面(显示"开"按钮)
  R6() → void                             加载红包数据
  S6(boolean) → void                      设置红包状态
  onSceneEnd(int errType, int errCode, String errMsg, m1 scene) → boolean
    网络请求回调(抢红包结果)
```

### 6.2 LuckyMoneyDetailUI — 红包详情
```
类: com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI

关键方法:
  onResume()                               显示红包详情
  Z6(e1 info) → boolean                    检查红包状态(是否已领取)
  onSceneEnd(int, int, String, m1) → boolean  网络回调
  a7() → int                               获取金额?
```

### 6.3 LuckyMoneyBusiReceiveUI — 企业红包接收
```
类: com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyBusiReceiveUI

关键方法:
  R6() → void                              加载数据
  S6(int errType, String errMsg)           显示状态
  onSceneEnd(int, int, String, m1) → boolean
```

### 6.4 LuckyMoneyPrepareUI — 发红包界面
```
类: com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyPrepareUI
  - 用于发红包，非抢红包
```

---

## 七、朋友圈/SNS

### 7.1 SnsTimeLineUI — 朋友圈首页
```
类: com.tencent.mm.plugin.sns.ui.SnsTimeLineUI
  - 继承MMActivity，实际逻辑复杂
  - 数据由SnsTimeLineAdapter管理
  - 获取实例: SnsTimeLineUI.getInstance()
```

### 7.2 SnsUploadUI — 朋友圈发布
```
类: com.tencent.mm.plugin.sns.ui.SnsUploadUI

关键方法:
  onCreate(Bundle)                            创建发布界面
  n7(String content, boolean, int, ArrayList mediaList1, ArrayList mediaList2, 
     int privacyType, ArrayList tagList) → void
    发布朋友圈主方法(7个参数):
      content:     文字内容
      privacyType: 隐私设置(0=公开, 1=私密, 等)
      mediaList:   媒体列表
  o7(String, String, String, byte[], boolean, boolean) → void
    上传图片
  e7(int, String, String, long, String) → void
    发布完成回调
```

### 7.3 SNS存储
```
类: com.tencent.mm.plugin.sns.storage.n  — SNS信息对象
  field_snsId      → long     帖子ID
  field_createTime → long     发布时间
  field_type       → int      类型
  field_content    → String   内容

类: com.tencent.mm.plugin.sns.storage.AdSnsInfo  — 广告SNS信息

类: com.tencent.mm.plugin.sns.config.RCSnsVideoImproveQualityConfig  — 视频画质配置
类: com.tencent.mm.plugin.sns.config.RCSnsUploadVideoThumbWxam      — 视频缩略图配置
```

---

## 八、通讯录

### 8.1 ContactInfoUI — 联系人详情
```
类: com.tencent.mm.plugin.profile.ui.ContactInfoUI

关键方法:
  onCreate(Bundle)
  onResume()                                    刷新联系人数据
  initView()                                    初始化界面
  D2() → void                                   加载联系人详情数据
  E5(boolean) → void                            更新状态
  onSceneEnd(int, int, String, m1) → void       网络请求回调
  W6(String) → void                             修改备注
  X6(String) → void
  c7(String) → void
```

### 8.2 AddressUI — 通讯录列表
```
类: com.tencent.mm.ui.contact.AddressUI
  AddressUIFragment → 通讯录列表Fragment

类: com.tencent.mm.ui.contact.address.MvvmAddressUIFragment
类: com.tencent.mm.ui.contact.address.BaseAddressUIFragment
```

### 8.3 ModRemarkNameUI — 修改备注名
```
类: com.tencent.mm.ui.contact.ModRemarkNameUI
  - 包含EditText和保存按钮
```

### 8.4 ContactRemarkInfoModUI — 备注信息修改
```
类: com.tencent.mm.plugin.profile.ui.ContactRemarkInfoModUI
```

### 8.5 SelectContactUI — 选择联系人
```
类: com.tencent.mm.ui.contact.SelectContactUI
```

---

## 九、群聊管理

### 9.1 ChatroomInfoUI — 群信息页
```
类: com.tencent.mm.chatroom.ui.ChatroomInfoUI

关键方法:
  onCreate(Bundle)
  onResume()
  R6(String) → void                             加载群信息
  a7(String) → void                             修改群名称
  S6(ChatroomInfoUI, boolean) → void            更新群设置
  onSceneEnd(int, int, String, m1) → void       网络回调
```

### 9.2 DelChatroomMemberUI — 删除群成员
```
类: com.tencent.mm.chatroom.ui.DelChatroomMemberUI

关键方法:
  onCreate(Bundle)
  R6(String chatroom, e9 msgInfo, int, String userName, w75.f callback) → f0
    删除群成员核心方法
  O6(DelChatroomMemberUI, e9, int, List, int, int, int, String) → void
    批量删除成员
  onSceneEnd(int, int, String, m1) → void       网络回调
```

### 9.3 NetSceneDelChatRoomMemberEvent
```
类: com.tencent.mm.autogen.events.NetSceneDelChatRoomMemberEvent
  data.chatroom → String  群聊ID
  data.username → String  被移除的用户
```

---

## 十、通话系统

### 10.1 Voip Widget (通话界面)
```
类: com.tencent.mm.plugin.voip.widget.k

构造函数: k(g voipUi, int, y3 talker, boolean, boolean, boolean)

关键方法:
  d() → void              开始通话?
  g() → void              结束通话
  k() → void              挂断
  a4(String) → void       设置显示信息
  b(boolean) → void       静音
  M2(boolean) → void      扬声器
  setScreenEnable(boolean)
```

### 10.2 Voip Model (通话模型)
```
类: com.tencent.mm.plugin.voip.model.c0

构造函数: c0(c1, d3)

关键方法:
  V() → void                    初始化
  X(boolean, boolean, String) → void  拨打/接听
  c() → void                    开始
  d() → void                    结束
  n() → boolean                 是否通话中
  h() → boolean                 是否静音
  j() → boolean                 是否扬声器
```

### 10.3 Voip Events
```
com.tencent.mm.autogen.events.CheckVoipCSIsStartedEvent
com.tencent.mm.autogen.events.StartVoipCSResultEvent
```

---

## 十一、通知系统

### 11.1 Notification Handler
```
类: com.tencent.mm.booter.notification.m0

构造函数: m0(k0)

关键方法:
  a(e9 msgInfo, long, boolean, boolean, String, int, String, String, Intent, boolean) → void
    构建通知 (11个参数):
      msgInfo:  消息对象
      long:     时间戳
      boolean:  是否震动
      boolean:  是否响铃
      String:   摘要文本
      int:      未读数
      String:   会话名
      String:   消息内容
      Intent:   点击跳转
      boolean:  是否前台

  b(String talker, e9 msgInfo, int, boolean) → boolean
    检查是否需要通知
  
  c(int, Map) → void
    批量通知处理
```

---

## 十二、WebView与隐私

### 12.1 WebViewUI
```
类: com.tencent.mm.plugin.webview.ui.tools.WebViewUI

关键方法:
  onCreate(Bundle)
  U6(WebViewUI, WebView, String) → void    设置WebView配置
  A7() → String                             获取URL
  C8(String) → void                         加载URL
  E7() → MPVideoPlayFullScreenView          获取视频全屏View
  H7() → s0                                 获取WebView Settings
  O8() → boolean                            JS是否启用
```

### 12.2 Clipboard
```
com.tencent.mm.plugin.appbrand.jsapi.JsApiSetClipboardDataWC  — 设置剪贴板
com.tencent.mm.plugin.webview.modeltools.WebViewClipBoardHelper  — WebView剪贴板
```

---

## 十三、事件系统

```
com.tencent.mm.autogen.events.ReceiveTypingEvent     — 正在输入事件
com.tencent.mm.autogen.events.RevokeMsgEvent          — 撤回事件
com.tencent.mm.autogen.events.SendMsgEvent            — 发送消息事件
com.tencent.mm.autogen.events.SendMsgSuccessEvent     — 发送成功事件
com.tencent.mm.autogen.events.SnsUploadPostDoneEvent  — SNS发布完成
com.tencent.mm.autogen.events.BizDeleteContactEvent   — 删除联系人
com.tencent.mm.autogen.events.FingerprintLoginAuthEvent — 指纹认证
com.tencent.mm.autogen.events.BackupPcManagerEvent    — PC备份
com.tencent.mm.autogen.events.NetSceneDelChatRoomMemberEvent — 群成员删除
```

---

## 十四、全局服务入口

```
d9.b()        → 消息服务总入口
  .u()        → MessageStorage (消息存储)
  .r()        → ConversationStorage (会话存储)

d1.J()        → 设置消息撤回提示
d1.I(e9)      → 获取消息的某种信息

n0.c(X.class) → 获取插件服务 (ServiceManager)

j1.s(X.class) → 获取另一个服务
```

---

## 十五、数据库

```
数据库路径: /data/data/com.tencent.mm/MicroMsg/<MD5(uin)>/EnMicroMsg.db

核心表:
  message    → 消息表
  rcontact   → 联系人表
    - username    TEXT   用户ID
    - nickname    TEXT   昵称
    - alias       TEXT   微信号
    - conRemark   TEXT   备注名
    - type        INT    类型(0=好友,2=被删,4=拉黑)
    - verifyFlag  INT    验证标志
    - deleteFlag  INT    删除标志
  conversation → 会话表
