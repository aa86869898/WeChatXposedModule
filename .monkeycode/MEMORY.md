# 用户指令记忆

本文件记录了用户的指令、偏好和教导，用于在未来的交互中提供参考。

## 格式

### 用户指令条目
用户指令条目应遵循以下格式：

[用户指令摘要]
- Date: [YYYY-MM-DD]
- Context: [提及的场景或时间]
- Instructions:
  - [用户教导或指示的内容，逐行描述]

### 项目知识条目
Agent 在任务执行过程中发现的条目应遵循以下格式：

[项目知识摘要]
- Date: [YYYY-MM-DD]
- Context: Agent 在执行 [具体任务描述] 时发现
- Category: [运维部署|构建方法|测试方法|排错调试|工作流协作|环境配置]
- Instructions:
  - [具体的知识点，逐行描述]

## 去重策略
- 添加新条目前，检查是否存在相似或相同的指令
- 若发现重复，跳过新条目或与已有条目合并
- 合并时，更新上下文或日期信息
- 这有助于避免冗余条目，保持记忆文件整洁

## 条目

### 微信助手 v3 开发硬性规范
- Date: 2026-07-21
- Context: 用户要求从零开发全新 Xposed 模块 com.leshao.v3，功能参照原乐少助手但不参考任何代码和 API
- Category: 工作流协作
- Instructions:
  - 所有 WCDB 数据库 Hook 逻辑必须等待微信 attachBaseContext 执行完成后初始化，禁止在 attachBaseContext 之前注册任何微信相关 Hook。ContextManager.isReady() 返回 false 时跳过所有 Hook 注册
  - SettingsEntryHook 仅允许在微信设置页搜索框下方新增跳转按钮，禁止往微信原生布局内嵌入任何功能面板或 UI 组件
  - 适配微信版本区间 8.0.72 ~ 8.0.76，基于 LSPosed Xposed API 102 纯净开发，禁止引入 WeKit/DexKit 或任何第三方依赖
  - 所有 Hook 动作、密钥捕获、数据库查询必须输出完整日志，包含时间戳 + 线程名 + 具体参数信息
  - proguard-rules.pro 必须保护所有反射相关类: `-keep class com.leshao.v3.hook.**`, `-keep class com.leshao.v3.db.**`, `-keep class com.leshao.v3.model.**`
  - 开发优先级严格按: 数据库密钥捕获 → 联系人/群聊列表查询 → 设置页跳转入口 → 独立 Activity 基础界面
  - 包名 com.leshao.v3，编译 Java 17 + Gradle 8.5 + Android SDK 35
   - 编译命令: `cd /workspace/LeShaoWeChat/LeShaoWeChatV3 && ./gradlew assembleDebug`
   - APK 输出路径: app/build/outputs/apk/debug/app-debug.apk
   - 签名: apksigner sign --ks debug.keystore --ks-key-alias androiddebugkey
   - 日志文件路径: /data/data/com.tencent.mm/files/leshao_v3/leshao_v3_log.txt
   - 日志过滤 TAG: LeShaoV3

### 每次编译必须升版本号 + 清理缓存（当前以 release 构建为准）
- Date: 2026-09-19（2026-10-04 强化）
- Context: 用户明确要求每次编译 APK 都要递增版本号并清理构建缓存，确保每次都是全新编译。自 v930 起实际使用 release 签名构建并推送。2026-10-04 用户再次强调：每次编译打包必须加版本号，并给出多线程分发高速服务器公网直链
- Category: 构建编译
- Instructions:
  - **硬性规矩：每次编译打包前必须递增版本号**，四同步：`app/build.gradle.kts` 中 `versionCode`(整数+1) 与 `versionName`(如 "3.0.143")、`MainHook.MODULE_BUILD`(如 "v3.0.143") 与 `MainHook.MODULE_VERSION_CODE`(与 versionCode 相同)。禁止用旧版本号发新包
  - release 构建命令：`cd /workspace/LeShaoWeChat/LeShaoWeChatV3 && ./gradlew :app:assembleRelease --offline -x lint`（R8 会改写 XposedHelpers，varargs findAndHookMethod 不可用，须用 findClass+getDeclaredMethod+hookMethod 模式）
  - 仅语法校验可用快速任务：`cd /workspace/LeShaoWeChat/LeShaoWeChatV3 && ./gradlew :app:compileReleaseJavaWithJavac --offline -x lint`（秒级失败反馈，检查通过后再跑完整 assembleRelease；正式发版仍须 `clean` 后重新构建）
  - release 产物：`app/build/outputs/apk/release/LeShaoWeChat-v{versionCode}.apk`；签名已配置在 build.gradle.kts signingConfigs(release.keystore)
  - 分发：复制 APK 到 `/workspace/LeShaoWeChat/LeShaoWeChatV3/download/` 并更新 `index.html`（置顶新版本入口）后方可提供下载；历史下载页亦同步维护
  - 下载服务：8899 端口运行多线程高速服务器 `/tmp/opencode/fast_dl -port 8899 -root /workspace/LeShaoWeChat/LeShaoWeChatV3`（FastDL 多线程 + Range 断点续传，支持 IDM/迅雷/aria2c 满速下载）；备选 `HTTP_POOL=64 python3 /tmp/opencode/range_http_server.py 8899 /workspace/LeShaoWeChat/LeShaoWeChatV3`
  - 公网下载前缀：`https://8899-796f33fc01a6a82b.monkeycode-ai.online`；下载页 `https://8899-796f33fc01a6a82b.monkeycode-ai.online/download/`
  - **每次编译打包完成后，回复中必须直接附上公网直链**（`https://8899-796f33fc01a6a82b.monkeycode-ai.online/download/LeShaoWeChat-v{versionCode}.apk`），禁止只给本地路径/localhost/预览页间接入口
  - 发版后验证 `curl -s -o /dev/null -w "%{http_code}" https://8899-796f33fc01a6a82b.monkeycode-ai.online/download/LeShaoWeChat-v{versionCode}.apk` 返回 200 即就绪

### AI 反编译审计结论（f9.Bb 接收链路实锤）
- Date: 2026-09-24
- Context: 用户反编译微信 APK 产出 WeChat_f9_Bb_ReceivePath_Audit.md，二次审查确认接收链路
- Category: 排错调试
- Instructions:
  - 接收实锤链路: a2.b(MessageSyncExtension.dkAddMsg, 纯接收) → b41.k.k(BaseMsgExtension.k) → b41.aa.y(e9) → f9.yb(e9) → f9.Bb(e9,false)；消息已存在改走 f9.bd(svrId,e9) 去重更新不调 Bb
  - f9.Bb 布尔参数语义实锤: false=普通 INSERT(接收/本地业务创建)，true=INSERT OR REPLACE(发送/重发)；仅凭 boolean 无法完全区分接收与业务创建，须同时过滤 field_isSend==0 和 field_type==1
  - f9 类仅有 6 个直接 Bb 调用点(4 类+内部 yb)，方法: Bb(e9,Z)J / yb(e9)J(接收业务统一入口) / Db/Hb(备份恢复) / Qc(J,e9,Z)I(带svrId) / Ic(J,e9)I(改状态) / bd(J,e9)V(去重更新) / O3(String,J)e9(查已存在)
  - 3180 存储链实锤: b41.e.v() 返回接口 vn3.m0(MsgInfoStorage 接口，实现=com.tencent.mm.storage.f9)、.q()→q3(ConfigStorage)、.r()→com.tencent.mm.storage.d8(RContactStorage 接口)；firstGetter 必须兼容"接口返回类型"(isReturnCompatible 双向匹配)
  - 服务定位: gp0.j1=MMKernel, j1.v(tn3.c4.class)→c4(存储门面; lj()=f9 消息存储, cj()=会话, ej()=联系人)
  - 修正: al5.g=MicroMsg.FilePreviewHelper(文件预览, 非消息总线); v3 MessageHook 的 x9 b/j 两路 hook 是白 hook; 09-23 的 #1~#8 全部来自 f9.Bb
  - 全文对照见 /workspace/WeChat_f9_Bb_ReceivePath_Audit_CORRECTIONS.md

### v1042 认证的 Tinker ClassLoader 根因（hook 上零捕获/存储链失败）
- Date: 2026-09-24
- Context: Agent 排查"f9.Bb hook 装上但零捕获 + StorageHub 绑定失败"时，从 leshao_v3_log.txt 三行时序定位到根因
- Category: 排错调试
- Instructions:
  - 微信 8.0.78(3180) 经 Tinker 热修复时, 存储/内核类(f9/e9/b41.h9/b41.e)真实加载在 `DelegateLastClassLoader`(tinker patch dex) 下, base.apk 的 `PathClassLoader` 只是平行副本
  - 判定方法(日志): `probeAndRehook: wechat instance CL=PathClassLoader` 出现早于 `CL=DelegateLastClassLoader[patch-*/...]` → 缓存被平行副本污染; `findTinkerClassLoader` 134 行缓存短路 + SDK 不刷新 = 根因
  - 修复: `VersionCompat.setWechatRealClassLoader` 探测到 Tinker CL 时同步刷新 `sCachedTinkerClassLoader`; `findTinkerClassLoader` 先查 `sWechatRealClassLoader` 是否已更新为 Tinker CL; DexKitAdapter.toClass 及全部 f9 hook(StorageHub/MsgReceiveHook/MessageHook/TtsVoiceSender/WanQunGroup) 统一经真实 CL 再 hook/bind
  - 教训: hook 类对象必须与运行时类对象同一 ClassLoader(用 `DexKit 类名 + 真实 CL 重新 findClass`), 否则 hook 装上零捕获且不报错
  - 该洞察也解释 v1034 注释"isInstance 恒为 false" 与 v1025 引入 findWechatRealClassLoader 的真正原因

### AI 助手不回复排查链路
- Date: 2026-09-24
- Context: Agent 排查"AI 助手私聊/群聊不回复"时发现，v1033/v1034 日志分析确认接收链路从未装配
- Category: 排错调试
- Instructions:
  - 按日志 TAG 顺序排查: `LeshaoAI.DexKit`(桥就绪) → `模块构建版本`(确认新包生效) → `installCore: enter`(LauncherUI 装配触发, 3180 下 must 用类名字符串判定不能用 isInstance, Tinker 多 ClassLoader 导致 isInstance 恒 false) → `已 hook f9.Bb`(收消息 hook 装上) → `LeshaoAI.Recv 收到文本` → `LeshaoAI.Trigger dispatch` → `LeshaoAI.Core ask` → 回复日志
  - 若日志窗口内连 `过滤: 非纯文本/自己发出` 都没有, 说明微信侧本就没收到消息, 无法判定接收链路, 需让用户实际收发消息后再导出日志
  - f9.Bb 是消息 insert 总闸门(群/私聊/系统/自己发的都走), 3180 版本号下的 f9.MsgInfoStorage 由 AI DexKitAdapter 定位, 定位失败会在 v1035 起自动重试
  - 存储链绑定失败(StorageHub bindInternal 全 false)不影响私聊回复; 群聊 @ 判定靠 selfWxid(有 SharedPreferences 兜底), 仅联系人显示名依赖存储链

### 代码提交时机（用户确认后才提交）
- Date: 2026-08-14
- Context: 用户明确表示：未经允许不要自动提交代码，等问题解决后由他指示再提交
- Category: 工作流协作
- Instructions:
  - 默认不自动执行 git commit / git push，除非用户明确说"提交"、"推送"等指令
  - 用户问题解决后会主动让我提交，此时才执行 commit + push
  - 远程仓库: https://github.com/aa86869898/WeChatXposedModule.git
  - 提交时只 add 本次真实改动的文件，禁止使用 `git add -A`（会把历史遗留 APK、临时文件一并提交）
  - APK 已解除 gitignore (!**/build/outputs/apk/debug/*.apk)，可随源码一起推送
   - 编译后必须提供下载链接：将 APK 复制到 `/workspace/LeShaoWeChat/LeShaoWeChatV3/download/` 目录，通过 `request_preview` 端口 8000 获取预览地址，下载链接为 `预览地址/LeShaoWeChat-v814.apk`
  - 推送命令: `git push`（本地 master → 远程 master）
  - 严禁提交 `/workspace/leshao_v3_log.txt`（用户实机日志，包含隐私，永不入 git）；提交前用 `git status --short` 核对暂存文件列表，只 add 源码与 download/index.html

### 气泡替换 X2C/复用路径失效（v3.0.133 文本气泡不生效根因）
- Date: 2026-10-03
- Context: 用户反馈 v3.0.133（三层门控重构）后聊天气泡不生效，分析 /workspace/全leshao_v3_log.txt 定位
- Category: 排错调试
- Instructions:
  - 文本气泡背景可能在 item attach 前由 X2C 预构建/RecyclerView 复用路径直接 `setBackground(StateListDrawable)` 设置（此时 `inChatItem` 找不到 0x7f0a103c tag，chat=false），`viewitems.to.b/mq.b` 绑定方法不一定触发 → 严格 tag 门控会导致文本气泡完全不替换
  - 判定：日志中语音气泡（AnimImageView.setType + attach BUBBLE apply）正常、红包 hook 正常，但无 `viewitems.b REPLACE`/`holderField`/`ke5.a.i stack`，同时有 `CAL setBackground view=MMNeat7extView ... chat=false`
  - 修复：`MMNeat7extView` 是微信聊天文本专用视图（主页/输入框不用），`isBubbleContext` 对 `isChatTextBubble(v)`（类名含 MMNeat）放行；setBackground/neat.setBackground/onDraw 替换后把 view 收进 BUBBLE 表供 attach 补盖
  - 注意：主页/输入框即使放行也不会误伤，因为所有替换路径仍有 matchBaseDrawable/resId 白名单二次校验

### 文字消息 to.b 不触发/气泡资源不匹配（v3.0.134 文字气泡仍不生效根因）
- Date: 2026-10-03
- Context: 用户反馈 v3.0.134 语音消息生效、文字气泡仍不生效；分析 leshao_v3_log.txt（22:17 时段，群聊含文字消息）定位
- Category: 排错调试
- Instructions:
  - 微信 8.0.78 文字消息绑定方法 `viewitems.to.b[e9,to,gk5.d,Boolean]`（静态+首参e9）虽能 hook 到，但运行时可能不调用该签名（走其他重载或 MVVM 路径 `viewitems.mvvmview.Chatting*MvvmView`）→ 只 hook 严格签名会零触发，必须对 to 类放宽为所有名为 b 的方法
  - 文字消息气泡背景在 main 线程绑定数据时以 `setBackground(StateListDrawable)` 设置，其 constantState 与 `res.getDrawable(2131231925/2131232060)`（chatfrom_bg/chatto_bg）不匹配 → matchBaseDrawable 返回 -1，X2C 阶段已替换的自定义图被微信覆盖回原生
  - 修复：扩展候选气泡资源（mi/ob/链接/发送中等相邻资源 ID）加入 constantState 匹配集；to 类 hook 放宽为所有 b 方法；输出 UNMATCHED textBubble 诊断日志（drawable 结构+调用栈）用于继续定位
  - 诊断：日志中语音消息正常（AnimImageView.setType + viewitems.b REPLACE neat=false）但无 to.b 触发 + 有 `CAL setBackground view=MMNeat7extView drawable=StateListDrawable chat=false` = 文字气泡路径未命中

### 语音消息失效根因：View 参数过滤误伤 mq.b（v3.0.137 修复）
- Date: 2026-10-03
- Context: 用户反馈 v3.0.136 语音消息气泡也不起作用；分析 /workspace/全leshao_v3_log.txt 定位
- Category: 排错调试
- Instructions:
  - v3.0.136 为防启动卡死对 `viewitems.to/mq` 所有 b 方法统一排除 View 参数重载，但 `mq.b(View,boolean,boolean)` 是语音消息绑定主路径（View 参数被 `View.class.isAssignableFrom(View)` 误杀）→ 日志中仅见 `to.b[e9,to,gk5.d,Boolean] hooked`、无任何 mq.b 安装行
  - 修复：按类区分过滤——`to`（文字）类继续排除 View 参数重载（防卡死），`mq`（语音）类保留全部 b 方法（mq.b 接收 View 参数且 v3.0.135 前一直正常）
  - 附带修正：`debugDumpUnmatchedTextBubble` 会把已替换的自定义 NineSliceDrawable 误报为 UNMATCHED，打印前需先 `isCustomBackground(d)` 排除
  - v3.0.136 日志中语音其实仍通过 `AnimImageView.setType` + `setBackgroundResource REPLACE` 兜底替换（2131232060/2131231925），说明语音兜底路径有效；但 mq.b 主路径必须恢复以保留 BUBBLE 捕获与方向直供

### v3.0.171 日志排错知识：DexKit 缓存不持久化根因与日志进程归属判断
- Date: 2026-10-05
- Context: Agent 审查用户回传的两份日志（全leshao_v3_log.txt=旧版 v3.0.164/165，leshao_v3_log.txt=v3.0.171 且全部来自 appbrand0/appbrand1 子进程）时发现
- Category: 排错调试
- Instructions:
  - 红包抢不了根因是 DexKit 扫描结果从不持久化：v3.0.169 引入的 `isCoreScanHealthy()` 要求 core 13 项全部非 null，但 3180 上 dbOpener/imei/cso/avatar/label/convAdapter/chatOpen 本就无法定位（旧版日志证实为 null）→ 永远不健康 → `persistScanVersion()` 被跳过 → MMKV 缓存卡死旧版本号 → 子进程 read-only keep cache → 红包 scene 类候选找不到
  - 修复方向：放宽 `isCoreScanHealthy()` 只检查 v3.0.171 核心审计锚点（p06/j1/e9/voiceApi/contactStorage），对已有运行时兜底的项不计数，然后重编译升版本号
  - leshao_v3_log.txt 为多进程共享文件，每行时间戳可叠加但进程只能靠 `当前实例: 主微信(user0), process=com.tencent.mm:appbrandX` 行区分；子进程段不含主进程的 P06/TTS/红包 scene resolved 日志
  - "收到消息自动发送相同内容"唯一代码路径是 MessageHook.processKeywordAndSensitive 的关键词回复（发送固定 r.reply），日志无 [KwReply] 记录时需主进程日志才能证实/排除

### v3.0.173 修复经验：版本号四同步必须含 DexKitHelper.CURRENT_MODULE_VERSION；红包类名直接加载；AutoForward 回环；遮挡留白
- Date: 2026-10-05
- Context: Agent 审查 v3.0.172 日志并修复 4 个问题后沉淀
- Category: 排错调试
- Instructions:
  - 升版本号是"五同步"而非四同步：除 build.gradle.kts(versionCode/versionName)、MainHook.MODULE_BUILD/MODULE_VERSION_CODE 外，还必须改 DexKitHelper.CURRENT_MODULE_VERSION。漏改会导致日志显示新版本号但 DexKit 缓存版本仍是旧的（currentModule=30171），子进程永远读旧缓存
  - 红包类名在 3180 上是确定混淆名：com.tencent.mm.plugin.luckymoney.model.n6(NetSceneReceiveLuckyMoney, receivewxhb, 7参构造) / h6(NetSceneOpenLuckyMoney, openwxhb, 10参构造)。DexKit 字符串搜索 receivewxhb 在 3180 上返回 0 candidates（URL 被混淆/拆分），必须直接 loadClass 权威类名，DexKit 仅作兜底
  - 自动转发回环：用户配置 sources=[A] targets=[A] 时，收到 A 消息会原样转发回 A。AutoForwardHook.forwardAll 已加 target==fromTalker 跳过防护
  - 消息遮挡：快捷按钮行插入 footer 内垂直容器并驱动 c(false,false) 后，微信 bottomSpace 仍不含按钮行高度，最新消息会被按钮行盖住。必须在注入后调用 ChatVoiceSwitchHook.ensureMessageSpace/scheduleMessageSpace 给消息列表补 paddingBottom

### 气泡整改新文档优先级（WeChat_Bubble_Inject_Analysis.md）
- Date: 2026-10-07
- Context: 用户要求自定义气泡严格按 /workspace/WeChat_Bubble_Inject_Analysis.md 全部整改，并二次审查
- Category: 工作流协作
- Instructions:
  - 该文档（2034 行）是气泡注入最高优先级依据，核心四层：L1=to.b/mq.e 业务注入、L1b=WxRecyclerAdapter.E0/F0（局部刷新兜底，治滑动丢失）+ adapter.k.O 双保险、L2=View.setBackground 家族防回写、L3=padding 拷贝（首选）、L4=onViewRecycled/onViewDetachedFromWindow 回收清理；另含 injectQuote 引用气泡注入
  - v3.0.214 起 ChatBubbleHook.java 已落地 L1b/L4/injectQuote/hookVoiceFillE（mq.e 9 参）；后续气泡改动必须先对照该文档 §15/§16 再动代码
