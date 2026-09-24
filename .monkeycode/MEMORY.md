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
- Date: 2026-09-19
- Context: 用户明确要求每次编译 APK 都要递增版本号并清理构建缓存，确保每次都是全新编译。自 v930 起实际使用 release 签名构建并推送
- Category: 构建编译
- Instructions:
  - 每次编译前，同步递增 `app/build.gradle.kts` 中 `versionCode`/`versionName`、`MainHook.MODULE_BUILD`(如 "v936")与 `MainHook.MODULE_VERSION_CODE`(整数，与 DexKit 扫描缓存失效键相同)
  - release 构建命令：`cd /workspace/LeShaoWeChat/LeShaoWeChatV3 && ./gradlew :app:assembleRelease --offline -x lint`（R8 会改写 XposedHelpers，varargs findAndHookMethod 不可用，须用 findClass+getDeclaredMethod+hookMethod 模式）
  - release 产物：`app/build/outputs/apk/release/LeShaoWeChat-v{versionCode}.apk`；签名已配置在 build.gradle.kts signingConfigs(release.keystore)
  - 分发：复制 APK 到 `/tmp/opencode/download/` 并更新 `download/index.html`（置顶新版本入口）后方可提供下载；历史下载页亦同步维护
  - 当前下载服务：`python3 -m http.server 8085 --bind 0.0.0.0`，根目录 `/tmp/opencode/download/`；外网直链 `https://8085-796f33fc01a6a82b.monkeycode-ai.online/LeShaoV3-v{versionCode}-release.apk`

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
