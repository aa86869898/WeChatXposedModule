### 用户指令条目
[用户指令摘要]
- Date: [YYYY-MM-DD]
- Context: [提及的场景或时间]
- Instructions: [具体内容]

## 条目

### Git 推送方式
- Date: 2026-09-22
- Context: 用户提供 GitHub token 用于推送；2026-09-22 补充提交范围约束
- Category: 工作流协作
- Instructions:
  - 推送时使用原生 git push 命令，不使用 credential helper
  - 推送命令格式: `git push https://<token>@github.com/aa86869898/WeChatXposedModule.git master`
  - 修改代码后编译通过检查没问题后，自动提交并推送，无需用户确认
  - 不要在回复中展示 token 值
  - 编译成功后必须给出 APK 下载链接（URL，不是文件路径），通过 deploy-website 部署 download.html 页面获取预览地址
  - 禁止 `git add -A`（工作区存在大量历史遗留删除项，易误提交）；每次提交前先 `git status --porcelain` 确认范围，提交范围需用户确认
  - 工作区 git 仓库根为 /workspace（上层），V3 路径前缀为 LeShaoWeChat/LeShaoWeChatV3/

### 语音自动播放调试经验
- Date: 2026-08-01
- Context: 用户报告自动播放语音大量失败 + TTS 播报未结束就播放语音消息
- Category: 排错调试
- Instructions:
  - 新版微信语音 XML 无 `voiceid` 属性（只有 `aeskey`/`voiceurl`/`clientmsgid`），`e9.j()` 返回 content 首字段是 talker，`y21.u0.a()` 会把 talker 当 voiceId → 方案A(CDN流) 必然失败（`stream err: p06.b`）
  - 方案B(SilkDecoder) 失败 `bg: no path` 是因为语音文件刚收到尚未下载到 voice2 目录，需轮询等待 5 秒重试
  - 方案C(聊天内 v0.I(msg)) 是实际成功路径，但依赖 `so.y()` 设置 sCurrentSo
  - TTS 重叠判断：不能用瞬时 `TtsEngine.isSpeaking()`（onStart 触发前为 false），改用 `hasPendingSpeak()`（mSpeaking || speakSeq>doneSeq || queue 非空）
  - 路径构建优先用 `VersionCompat.findPlayThreadClass().d()` 权威方法，再回退 md5 目录

### 联系人加载 APK 版本识别
- Date: 2026-08-05
- Context: Agent 分析 leshao_v3_log.txt 时发现日志格式可区分设备上运行的 APK 版本
- Category: 排错调试
- Instructions:
  - 旧版日志（v171 及更早）格式：`openDatabase FIRED`、`rawQuery FIRED`、`Strategy A: DB opened, rcontact table confirmed`、`ensureDirDb OK`，且 loadContacts 里 Strategy A 仍会执行
  - 新版日志（v172+）格式：带 `[DIAG]` 前缀（`[DIAG] openDatabase hook`、`[DIAG] loadContacts START`），Strategy A 已禁用
  - `ensureDirDb` 会在首次调用线程（可能是 main）同步打开 WCDB EnMicroMsg.db 第二连接，早于微信正式初始化 DB 约 5 秒，导致 queryNick 全部 not found；v173 起 queryNickFromDB/queryGroupInfoFromDB 优先用 DatabaseProvider 连接，主线程禁止 ensureDirDb

### 微信语音 SILK 采样率约束
- Date: 2026-08-14
- Context: 用户反馈音乐转语音音质差 + 语速语调异常，定位为采样率错误
- Category: 排错调试
- Instructions:
  - 微信语音消息（voice msg）SILK 采样率必须是 16000Hz，24000Hz 不兼容（会导致语速语调异常/变速变调）
  - 音频转语音/音乐转语音统一走 `TtsVoiceSender.sendMp3Voice`，采样率 16000、码率 30000、复杂度 2（SILK 复杂度标准是 0/1/2，5 是无效值，会音质差）
  - 音质差异主要来自输入源（本地高质量 MP3 vs 下载的音乐试听片段），SILK 编码参数非主因

### 听一听搜索响应 protobuf 字段映射（musiclivesearch 实测，勿用 a65.tg2）
- Date: 2026-08-14
- Context: 从搜索响应 hex 实测定位字段号，纠正了 a65.tg2 误判（a65.tg2 是音乐卡片 FinderMVSongInfo，字段号与搜索响应不同）
- Category: 排错调试
- Instructions:
  - 搜索响应 song detail 字段号（实测）：1=songName, 2=singer, 3=dataUrl, 4=appid, 5=webUrl,
    6=coverUrl(封面), 7=duration(毫秒 varint), 9=mid(getlinkmid_前缀), 10=高亮歌名, 11=高亮歌手, 12=小整数, 13=lyric(歌词)
  - 字段 15/17/19 在搜索响应里不存在，不要用 a65.tg2 的 9/15/19 映射
  - duration 单位是毫秒，显示需 ÷1000
  - a65.tg2 是音乐卡片 <FinderMVSongInfo> 的 protobuf（9=albumUrl,15=duration,19=mid,7=lyric），仅用于音乐卡片 XML，不用于搜索响应

### 红包/转账自动领取的 hook 入口与闪退陷阱
- Date: 2026-08-15
- Context: Agent 排查红包/转账自动领取无效且 v720 疑似闪退时发现
- Category: 排错调试
- Instructions:
  - 红包(type=436207665/318767153)和转账(type=419430449)消息不走 x9 消息分发（MessageHandler.handle 只见 type=1/3），原 MessageHook 入口收不到通知
  - 检测入口应在消息入库处：storage.f9 的 I9(e9,...) 方法（e9 是消息类 com.tencent.mm.storage.e9），ChatHooks 已通过 findInsertMethod 精确匹配第一个参数为 e9 的 I9 并 hook（afterHookedMethod 里 WxReflect.type 可读到 436207665 等）
  - 陷阱：不要再用 `f9.getDeclaredMethods()` 遍历 hook 所有名为 I9 的重载做独立 hook（RedPacketHook.hookMessageInsert v720 这样写导致微信主进程启动闪退，日志只剩子进程 appbrand0/appbrand1 的 STARTUP+skip，主进程日志全丢）；复用 ChatHooks 已有的精确 I9 hook，在 afterHookedMethod 里检测红包/转账并调用 RedPacketHook.onIncomingMessage 即可
  - field_isSend 对红包/转账消息不可靠，收发判断不要依赖它，只按 type 判断类型
  - 红包/转账检测要独立于 AI 开关（不能放在 `if(!AiConfig.masterEnabled()...) return` 之后），否则 AI 关闭时红包不生效

### UI 改版（v1145）约束与空白问题根因
- Date: 2026-10-02
- Context: 用户要求全量 UI 重设计（Material 3 樱花粉）时给出强约束
- Category: 工作流协作
- Instructions:
  - 只修改 `app/src/main/java/com/leshao/v3/ui/` 下文件；禁止改 hook/ 目录、MainHook.java、ContextManager.java、versionName/versionCode
  - 禁止修改 SharedPreferences key、hook 开关回调、Toast 文案、config 按钮逻辑、activity result 契约
  - `CandyUi.newSwitch` 必须保持返回 `android.widget.Switch`；`DexKitScanDialog` 静态 API（show/initSteps/updateProgress/dismiss/onScanComplete/isShowing）签名不可变（并行修复者依赖）
  - SubPageActivity 导航/返回栈逻辑不可变
  - 禁止运行 gradle（并行修复者负责编译）；本地语法校验可用 javac：`javac -nowarn -proc:none -d /tmp/out -cp "/opt/android-sdk/platforms/android-35/android.jar:<androidx-core-runtime.jar>:app/build/intermediates/javac/debug/classes:app/libs/xposed-api-82.jar" <文件>`
  - 底部大片空白根因：SubPageActivity 对所有子页面用固定 90% 屏高 + weight=1 ScrollView，短内容页底部留白
  - v1145 解法：短内容页（非 no_wrap）改用 WRAP 高度 + `InsetsUtil.windowAutoHeight/centerAutoHeight`（内容自适应，上限 87% 屏）；no_wrap 标记页（WeChatDbPageView）保持固定高度
  - 页面分区模板：ContactGroupPageView 的「乐少转发」= `M3Page.section(title, desc)` + 卡片间 `M3Page.divider`，卡片用 `CandyUi.cardBg` + `InsetsUtil.clipRounded`

### DexKit 全量扫描 native 崩溃的根因与 v30112 解法
- Date: 2026-10-03
- Context: 用户反馈升级 v30111 后微信主进程打开主页仍闪退，日志显示升级后首次启动全量扫描（模块版本 30110→30111 触发 cache miss）期间进程无 Java 堆栈直接消亡（典型 native SIGSEGV/SIGABRT）
- Category: 排错调试
- Instructions:
  - 崩溃特征：升级后首次启动日志中主进程与 push 进程两个 `DexKitScan-1` 线程同时 `loadLibrary libdexkit`、同时 `scanWechatTargets`、共享同一 MMKV 缓存 → 多进程并发扫描同一份 DEX + 写同一 MMKV 是 native 崩溃主因；启动早期扫描与各 hook 安装线程的 find* 搜索叠加进一步恶化
  - 结论：普通重启（微信版本与模块版本一致且缓存完整）不加载 so 不扫描；只有微信版本/模块版本变化或缓存不完整才触发全量扫描
  - v30112 解法（DexKitHelper.java）：① 子进程（push/分身等非主进程）不再调用 startFullScan，缓存不完整时用 `deferCloneCacheRead` 延迟 30s 重读主进程写入的缓存；② 主进程全量扫描由立即执行改为 `sMainHandler.postDelayed` 延迟 10s 执行，避开冷启动早期；③ 新增 `waitForFullScanIfScheduled()`，全量扫描进行时后台线程的 find* 搜索先等待（主线程不等待防 ANR），由 sBridgeLock 保证 native 串行
  - 多进程并发扫描是首要嫌疑，子进程禁止扫描后单进程扫描基本不会崩

### DexKit native 崩溃：v30112 遗漏的第二座桥（v30113 修复）
- Date: 2026-10-03
- Context: v30112 后主进程仍在 DexKit 操作期间无 Java 堆栈闪退（日志按不同线程截断：boot1 在 `[DexKitScan-1] findConvLongPressEntry`，boot2 在 `[pool-4-thread-1] AntiRecallHook class hit`），排除多进程并发后仍复现
- Category: 排错调试
- Instructions:
  - 根因：AI 模块 `com.leshao.ai.util.DexKitBridgeHolder` 用 `DexKitBridge.create(cl,false)` 另建了**第二个 native dexkit 桥**，且 `DexKitAdapter.findClass/findMethods` 的查询不经过 v3 的 `sBridgeLock` → 与全量扫描/补扫并发访问 libdexkit 崩溃；v30112 只串行化了 v3 自身的桥，漏掉了这座 AI 桥
  - v30113 解法：`DexKitHelper` 新增 `public interface BridgeAction<T>` 与 `public static <T> T withWechatBridge(ClassLoader, BridgeAction<T>)`，复用 v3 进程级缓存桥并在 `sBridgeLock` 内串行执行；`DexKitAdapter` 改走该入口；`DexKitBridgeHolder` 不再是桥持有者（init 空操作，get 已移除）
  - 排查要点：全项目 `grep -rn "DexKitBridge\|DexKitCacheBridge\|withBridge\|libdexkit"` 找出所有 native 使用点，除 DexKitHelper 外仅 AI 模块
  - 版本号三处同步：v30113 = build.gradle.kts(30113/3.0.113) + MainHook.java(MODULE_BUILD/MODULE_VERSION_CODE)

### LeShaoWeChat V3 构建与发布流程
- Date: 2026-10-03
- Context: Agent 完成 v30112 修复后整理构建/发布命令
- Category: 构建编译
- Instructions:
  - 构建：`cd /workspace/LeShaoWeChat/LeShaoWeChatV3 && ./gradlew :app:assembleDebug :app:assembleRelease`（Java 17 已配置）；产物在 `app/build/outputs/apk/{debug,release}/LeShaoWeChat-v<versionCode>.apk`，需复制到项目根目录并命名为 `LeShaoWeChat-v<versionCode>.apk` 与 `LeShaoWeChat-v<versionCode>-debug.apk`
  - 版本号同步更新：`app/src/main/java/com/leshao/v3/MainHook.java` 的 `MODULE_VERSION_CODE` 与 `app/build.gradle.kts` 的 `versionCode`/`versionName` 必须一致（MainHook 值用于 MMKV 缓存失效判断）
  - 发布：更新根目录 `index.html` 的下载链接与版本描述；启动下载服务器 `cd /workspace/LeShaoWeChat/LeShaoWeChatV3 && python3 upload_server.py`（8899 端口，同时提供 APK 下载与日志上传）
  - 用户侧日志回传位置：`/workspace/leshao_v3_log.txt`（微信日志通过 8899 上传服务器写入）

  - 无 local.properties 时需 ANDROID_HOME=/opt/android-sdk（gradle.properties 的 aapt2 override 已指 /opt/android-sdk/build-tools/35.0.1/aapt2，勿改回）
  - 大改动后先 `./gradlew clean`；发布后校验 dexdump 确认新类已进 DEX
### 交付规则：不生成源码包 + 16线程池断点续传下载服务器（固定）
- Date: 2026-10-10
- Context: 用户明确要求写死为固定规则，后续每次交付必须遵守
- Category: 工作流协作
- Instructions:
  - **不要每次交付时生成源码包**：任何修复/发布交付不得再生成 `bubble-src-*.zip` 之类的源码压缩包；下载目录只放最新 APK 与文档
  - **每次交付必须使用最高速度下载服务器**：启动 `/tmp/opencode/fast_dl -port <端口> -root /workspace/LeShaoWeChat/LeShaoWeChatV3/download`（Go 高性能静态服务器，原生支持 Range/多段断点续传，goroutine 并发，本地吞吐 700MB/s+）；端口避开 8731(文档)/8899(日志上传)/8900(fast_dl)，推荐 8901
  - 平台对公网单连接限速约 500-550KB/s，但支持 Range 多段并发突破限速（实测 4段=2.27MB/s、8段=3.23MB/s、16段=3.63MB/s，段数无上限，越接近平台总带宽上限收益递减）
  - **交付只给「超高速下载页」一个链接**：`https://<端口>-796f33fc01a6a82b.monkeycode-ai.online/turbo-download.html`（页面内 JS 自动 16 线程分段并发下载并本地合并，实测约 3.6MB/s，是普通下载的 6 倍+）；**不要给发布主页（`/`）链接、不要给原始 APK 直链、不要给 aria2 命令、不要给任何其他下载入口**
  - `download/index.html` 与根 `index.html` 精简为：只保留「超高速下载」按钮 + 质检文档链接；**移除普通 APK 直链与源码 zip 链接**
  - 每次交付后验证 `turbo-download.html` 返回 200、`Range: bytes=0-1023` 返回 206、普通请求返回 200

### Tinker 双 ClassLoader 根因（v3.0.577 关键经验）
- Date: 2026-11-03
- Context: TTS 播报失效/联系人获取失败/红包异常/长按菜单不显示，日志显示 hook 零捕获与存储链 Kernel not initialized
- Category: 排错调试 / Hook 架构
- Instructions:
  - 根因：DexKit 扫描返回的 Class 来自 base.apk 平行副本 CL（`PathClassLoader`），微信运行时真实类由 Tinker `DelegateLastClassLoader` 加载；两套 CL 静态单例不通 → `pp0.i1` 副本未初始化 → `Kernel not initialized by MMApplication!`；hook 挂副本类上运行时零捕获
  - 判据：`MsgReceiveHook` 打印「使用 Tinker 真实 CL DelegateLastClassLoader」=正常；`MessageHook` 未打印该行=挂错 CL
  - 通用解法：**所有 Class 按类名在真实 CL 上重新解析**（`resolveOn`/`resolveInterfaceOr`）；`findTinkerClassLoader` 有 10s 冷却且启动早期常 null → 不能一次性调用，必须 base CL 先挂 + 后台 50×800ms 等真实 CL 补挂（`scheduleRealClRehook`）
  - 存储绑定（8.0.79）：服务键必须用**接口** `po3.c4`（`h2 implements c4`），不能用实现类 f9（`pp0.i1.v(f9)` 恒 null）；`h2.Jj()→f9`（implements `ro3.m0`）、`h2.Aj()→j4`（implements `d8`）；服务定位器 `pp0.i1`（旧 gp0.j1 已删）
  - f9 插入入口 `Mb(e9)`/`Nb(e9,boolean)`；MsgInfo 实体=`com.tencent.mm.storage.e9`，Storage=`f9`，RContact=`j4`
  - 红包 scene `n6` 构造内部依赖 `pp0.i1` 内核 → 必须真实 CL 解析，且需在 grab 前守卫/后台覆盖 base CL 解析到的错误类
  - 去重：base/real 双 CL 补挂用 `synchronizedSet` 记录已挂 Class，防回调翻倍；长按菜单 `pickRealClassLoader` 需 `attempt>=1` 就回退普通 CL（否则 `loader=null` → Class.forName bootstrap → 全 ClassNotFound）

### 傻包TTS V2 引擎升级（v3.0.405→v3.0.409，与 v2.4-r1 文档对齐）
- Date: 2026-10-17
- Context: 按《豆包TTS免登录全套》v2.3/r2/v2.4-r1 逐版升级并修复问题
- Category: 构建编译 / 环境配置 / 排错调试
- Instructions:
  - V2 引擎：`service/DoubaoTtsPool.kt`（与 v2.4-r1 参考 08_icl_multivoice/xposed/DoubaoTtsPool.kt 除 package 外逐行一致）；桥接 `service/DoubaoTtsV2Bridge.kt`（synthesize 流式收集 / ensureInit 幂等 / writeWav 通用解码 OGG+AAC）
  - 格式以 client_stream 文件为准：samantha 主车道 = AAC（client_stream_samantha.py），voicegenie 兜底 = OGG（client_stream_voicegenie.py）；不要 WAV
  - v3.0.409 新增 `service/DoubaoTtsGateway.kt`（HTTP 网关流式客户端，POST {base}/tts，对齐 client_stream 请求格式）；桥接车道顺序 = samantha 网关→Cookie 进程内直连（SamanthaTtsClient.kt）→voicegenie 网关→进程内池；设置页「豆包TTS 网关/Cookie 配置」（tts_samantha_base / tts_voicegenie_base / tts_samantha_cookie）
  - 网关部署：gateway_samantha.py:8398（登录 Cookie）+ gateway_icl_fast.py:8399；手机填电脑局域网 IP；免登录 voicegenie 直连会被静默限流（no audio server silent），必须走 samantha 主车道
  - r2 特性：DeviceProfile 指纹参数化+失败自动迁号，FIRST_FRAME_MS=30s；参考包只给 DEFAULT 一套实测指纹，第二套需用户提供（随机化会 session auth）
  - 依赖：okhttp3:4.12.0 + okio:3.9.0 + kotlinx-coroutines-core:1.8.1（**必须在线构建**下载）；MainHook onReady 后台 ensureInit
  - 调用点：CubeTtsPlayer / TTSPageView / TtsVoiceSender / AiAssistantPanel 全走 DoubaoTtsV2Bridge.writeWav；v1 DoubaoTtsClient.java 已移出源码（备份 /tmp/opencode/cleanup_backup/v1_src/）
  - v3.0.408/409：voicegenie 桥接总预算 timeoutSec+35s（引擎 2 次重试不误判）；AutoGroupQr 过滤 isAbstract；MessageMenuHook 回退 A 层失败不提前装 B/C 层（防启动期类加载风险）
  - 二次复检：引擎 diff + 格式对齐 + 版本号五处同步 + debug/release 编译 + 公网 206/200；工作区压缩包仅保留 v2.4-r1

### 语音转发误转发修复：授权闸门 armed gate（v3.0.578 关键经验）
- Date: 2026-11-04
- Context: 群聊转发压缩包时，模块把私聊里别人发的旧语音一起转发进群（隐私外泄）
- Category: 排错调试 / Hook 架构
- Instructions:
  - 现象日志：`[FwdFix][A] finish picked=[...@chatroom]` → 立即 `VF: doForward msgId=<旧语音>` → `TtsVoiceSender SceneVoice talker=...@chatroom`；真实意图是转发压缩包；事故前无 INJECT/VOICE_MARK
  - 根因：`WxForwardReplaceHook` 的 `[FwdFix][A]`(MvvmContactListUI.finish) 与 `[FwdFix][B]`(MsgRetransmitUI.onActivityResult) 在**任意普通转发**时都会发送缓存语音；且 `pendingVoiceE9()` 对 `sVoiceMsgs` 取"最新一条"**无 TTL** → 旧语音被无限期复用
  - 修复：引入**授权闸门** `sVoiceFwdArmed`/`sVoiceFwdArmedAt`/`VOICE_ARM_TTL=90s`；`voiceForwardArmed()`/`disarmVoiceForward()`
  - 置位授权仅两处真实点击：`launchNativeVoiceForward`（模块「语音转发」项）与 `BQ_Q_142`（原生「转发」142 项点击处理器 `viewitems.yb#R(MenuItem,...)`，**仅 type==34 语音**才授权，非语音忽略）
  - `[A]`/`[B]` 严格以 armed 为准（`pendMsgF = armed ? sVoicePendingMsg : null`），未授权直接放行不发送；发送完成/取消/目标过滤后立即 disarm
  - 删除 `pendingVoiceE9()` 对 `sVoiceMsgs` 的无 TTL 兜底（旧语音无限复用根因）；`markVoiceForwardPending` 仅缓存不再构成可发送 pending
  - 反编译佐证：`com.tencent.mm.ui.chatting.viewitems.yb#R(MenuItem,d,ym5.d)` 存在（classes87）；`MSG_TYPE_VOICE=34`
  - 红线：未反射写微信字段、未激活原生语音发送链；语音仍走「微信原生选人 + 模块 TtsVoiceSender」
  - 分身诊断文件说明：`dexkit_baseline.json` 仅全新健康扫描后导出、`signature_dump.txt` 仅 Debug 生成；配置在 prefs(`xposed_wechat_iso`)，非文件；提升 `CURRENT_MODULE_VERSION` 会令分身缓存失效并重扫
  - 版本 v3.0.578 / 30578；`assembleRelease` 通过；公网 200 / Range 206

### 8.0.79 真实 dex 权威签名表 + 发送/防撤回/红包链路修复（v3.0.579 关键经验）
- Date: 2026-11-04
- Context: 用户要求对 v3.0.579 做系统级全量审查；上一轮域审查提出「发送链全部失效/防撤回锚点不存在/红包 sendId 取错」三条高风险结论需真实 dex 复核
- Category: Hook 架构 / 反编译核查
- Instructions:
  - **dex 定位方法**：`/tmp/opencode/wechat_dex/com.tencent.mm` 有 94 dex 且**存在同名类跨 dex 重复定义**（classes52-55 与 classes74-79 两套），`grep`/jadx 全目录会串味；必须用 class_defs 解析器精确定位类归属，再对单个 dex 做 `jadx --single-class`
  - **文本发送真相**：`ni5.n0.c(no3.c1)` → `rt5.i5`（classes55，implements `no3.c1`）；`rt5.i5.Jj(String,String,II)V` → `Kj` → `Lj` 是官方入口，**有效**。`Lj` 内部走 `f61.t1.a(talker)` → `f61.s1`(Builder) → `o1=s1.a(); o1.a(); MsgRetransmitUI.l7(o1)`
  - **f61.s1 字段语义**：`b`=talker、`d`=content、`e`=msgType、`f`=flag、`i`=scene(4 普通/5 特殊)、`l`=`f61.q1` 枚举（TEXT 常量字段名 `d`，不设则 `c()` 空转）、`j`=`l41.k7`；工厂 `f61.t1.a(String)`/`b(String,q1,int,Object)`
  - **已删除**：`v51.r1`/`x51.b0`/`x51.a0`/`v51.r0`(仅 abstract 壳)/`f9.Bb`/`f9.O3`/`f9.Qc`/`f9.N3`/`e9.b1`/`e9.S1`
  - **e9 实体**：`com.tencent.mm.storage.e9`(119 方法) extends `nx0.q3`，而 `nx0.q3` 就是 `im.c8`(59 方法，字段 getter/setter 全在此)。setter 实证：`setMsgId(J)`/`n1(J)`=svrId/`setType(I)`/`s1(I)`=status/`i1(I)`=isSend/`a1(J)`=createTime/`t1(String)`=talker/`Z0(String)`=content/`u1(I)`=talkerId；getter：`getMsgId()`/`H0()`=svrId/`getType()`/`M0()`=status/`z0()`=isSend/`getCreateTime()`/`N0()`=talker/`F0()`=content/`O0()`=talkerId。**F0 不是 svrId**（多处代码误用）
  - **f9 存储**：`com.tencent.mm.storage.f9`(135 方法)：`k4(String,J)`=getBySvrId、`ud(J,e9,Z)`=update+notify、`td(J,e9)`=`ud(...,true)`、`Nb(e9,Z)J`/`Mb(e9)J`=insert、`Ra(String)`=getTable、`q8(String)`=getByMsgId
  - **红包 h6/n6/e1**：`h6.i`=sendId、`h6.m`=nativeUrl、`h6.n`=sessionUserName、`h6.h`=e1；`n6.m`=sendId、`n6.n`=nativeUrl、`n6.h`=msgType(int)、`n6.i`=channel(int)、`n6.P`=timingIdentifier、`n6.s`=hbStatus、`n6.t`=receiveStatus、`n6.u`=statusMess；e1(`i5.e` 解析)：`q`=amount(分)、`r`=recNum、`t`=totalNum、`u`=totalAmount、`i`=sendNick、`Q`=sendUserName、`f`=statusMess、`A`=receiveStatus、`n`=sendId
  - **双 CL 陷阱**：`RedPacketHook.scheduleSceneResolveRetry` 用真实 Tinker CL 重解析 n6/h6 后**必须重装 onGYNetEnd hook**，否则 hook 留在 base CL 旧类上永不触发（本轮修复，用 `sEndHooked` 幂等）
  - **原生引用链已废**：`ou5.c1` 只剩 `<init>/invoke`（lambda）、`xp3.i` 只剩 `<init>/callback`、`dx0.r` 只剩 `requestRender/t` → `sendQuoteAndAt` 必然失败；且旧实现在读不到 msgId 时返回 true 造成"假成功不回退"，已改 false
  - 点歌受理提示：改「纯文本优先」+ 二次兜底直调 `WmReflect.sendTextMsg` + `LsDianGe: 回复(OK/FAIL)` 结果日志
  - 版本 v3.0.579 / 30579；APK 10,480,814 字节；公网 200 / Range 206

### 「模块自发消息被乱转发 / 全量群发」根因与护栏（v3.0.580 关键经验）
- Date: 2026-11-04
- Context: 用户报告「乱转发、群发哪都是了」，要求用旧日志确认该链路是否仍在；日志 `分身leshao_v3_log.txt` 07:13:32 定量证实：一条点歌受理提示在 0.571 秒内被转发到 545 个群、约 1090 条（同一条内容触发两轮）
- Category: 消息外发域 / 缺陷链分析
- Instructions:
  - **缺陷链 4 层叠加**（缺一不可，必须全堵）：① 模块没有「这条是我自己发的」概念；② `AutoForwardHook.onMsgInsert` 里 `isSend=-1` 且 getter 异常即放行；③ 去重 `if(svrId!=0 && !add(svrId)) return;` → svrId 为 0 时不去重；④ `forwardAll` 对目标列表裸循环，无节流无熔断
  - **关键：事故发生在分身环境**，各分身 prefs 目录独立（`UnifiedPrefs` 各身份读写各自 prefs），A 分身发出的消息同步到 B 分身时是 `isSend=0` + 真实 `msgSvrId` 的「别人发来的消息」，**本地账本拦不住** → 必须有「只看内容」的识别规则（`isModuleNoticeText()` 用配置的点歌提示模板，`{song}` → `.*`）
  - **`Pattern.quote` 陷阱**：不能「先 quote 整串再 replace `{song}`」，`\Q...\E` 块内无法替换；必须按占位符 `split` 后逐段 quote 再拼接 `.*`
  - `SelfMsgGuard`（`v3/hook/SelfMsgGuard.java`）：账本 `talker|type|hash(content)`(120s) + `hash(content)`(20s) + `talker|type` 通配(45s，语音/图片) + `msgId` 账本；`consume()` 一次性、`isSelf()` 只读（**接收侧统一用 `isSelf`**，否则第一个 hook 消费掉会导致其它 hook 漏判）；`allowBroadcast()` 熔断（>600 直接拒；≥20 条 60 秒 ≤6 次、间隔 ≥3s）
  - 发送入口打标点（全项目 `sendTextMsg(` 10 处全部收敛到 `WmReflect.sendTextMsg`）：`WmReflect.sendTextMsg` / `GroupFeatures.sendTextMessage` / `WeChatMessenger.sendText` / `TtsVoiceSender.sendVoiceData`(type 34)
  - `GroupFeatures.insertViaMsgInfo`：`i1(1)` 后**必须回读 isSend**，≠1 放弃入库（否则产生一条「看起来是别人发来」的消息，源头就脏了）
  - `MsgReceiveHook` / `MessageHook` 的 `isSend != 1` 分支前都要加 `SelfMsgGuard.isSelf` 拦截，否则提示语会触发关键词自动回复/语音播报再发消息（自我循环）
  - 内容读取：8.0.79 用 `F0()`；`field_content` 声明在父类 `im.c8`，`getDeclaredField` 在 e9 上取不到，必须沿继承链找
  - 批量发送：独立单线程 + `AtomicBoolean` 单批互斥 + 每条 180~420ms 随机间隔 + 连续失败 5 条中止；绝不能在微信 insert 回调线程里裸循环
  - UI：自动转发目标数实时显示，>50 二次确认，>200 红色告警
  - 版本 v3.0.580 / 30580；APK 10,489,025 字节，MD5 `53155c333df61718485176b224ea877f`；公网 200 / Range 206

### 自发消息跨分身识别升级为零宽水印（v3.0.581 关键经验）
- Date: 2026-11-05
- Context: v3.0.580 遗留「动态文案跨分身同步仍靠本地账本」漏拦风险，本轮以内容水印封堵
- Category: Hook 架构 / 排错调试
- Instructions:
  - 水印常量 `SelfMsgGuard.MARK = "\u200B\u200C"`；`watermark()` 幂等、`hasWatermark()` 扫描正文起点或 @ 前缀后 96 字符内
  - **@ 兼容红线**：@ 高亮依赖 content 以 `@昵称\u2005` 开头，水印必须插在提及前缀之后，绝不能加在 `@` 前；只有 @ 无正文时追加末尾
  - 接入点只需收敛 4 个函数：`WmReflect.sendTextMsg`、`GroupFeatures.sendTextMessage`、`WeChatMessenger.sendText`、`WeChatMessenger.sendQuoteAndAt`，其余调用点自动覆盖
  - 接收侧统一 `isSelf` / `SendGuard.isBotSent`（改为 `hasWatermark`），禁止再写裸 `startsWith(MARK)`
  - `norm()` 负责剥离零宽字符，保证账本键与去重键在打水印后仍一致
  - 消息伪装走 UI 链不经这些函数、无固定水印（isSend=1 本机安全，跨分身仍靠账本）
- 版本 v3.0.581 / 30581；`assembleRelease` 通过；公网 200 / Range 206

### 点歌产物只留在发起会话（v3.0.582 关键规则）
- Date: 2026-11-05
- Context: 用户点歌后受理提示被自动转发到 500+ 好友/群，要求「我发给谁就是谁」
- Category: 消息外发域 / 排错调试
- Instructions:
  - 点歌是业务产物，需要独立硬规则，不能只依赖通用自发护栏/水印
  - `DianGeService` 维护点歌产物账本（180s）：指令、受理提示、全部失败提示、点歌语音一律登记
  - 文本统一在 `reply()` 内登记；语音统一走 `sendSongVoice()`（先登记再发）；无正文按 `talker|34`
  - `AutoForwardHook` 在自发护栏之前加两道：`isSongOutput`（产物）与 `isSongRequest`（同步回来的指令），命中即 return
  - `maybeHandle` 命中已登记指令直接返回 true，防分身重复接管
  - `normOutput` 必须剥离零宽水印与群发送者前缀，保证同步副本能对上
  - 产品口径：点歌产物永不被自动转发；普通消息的自动转发逻辑不变
- 版本 v3.0.582 / 30582；`assembleRelease` 通过；公网 200 / Range 206

### 自身发送消息的跨分身身份识别（v3.0.583 关键规则）
- Date: 2026-11-05
- Context: 580-582 只用「isSend==0 才算收到」，但分身/多设备同步副本 isSend 同样是 0，且正文可能无水印（手打、历史、语音无正文），会在对端分身二次触发
- Category: 消息接收域 / 排错调试
- Instructions:
  - 接收归属必须看**发送者身份**，不能只看 isSend：`s0`（回退 `field_fromUsername`）与 `StorageHub.selfWxid()` 相等即归属自己
  - `SelfMsgGuard.isSelfSender(e9)` 拆成公共+包内纯函数，纯函数接收 `self` 参数便于离线用例
  - 三条接收链统一前置拦截：`MessageHook`、`MsgReceiveHook`、`AutoForwardHook`，位置在 isSend==0 之后、业务判定之前
  - 取不到自己 wxid 返回 false，退回水印/账本，宁可多处理也不误杀真实消息
  - 白名单复核口径：`inWhitelist` 在 `maybeHandle` 里先于产物账本，空白名单固定 false；非白名单指令被吞掉，不送 AI
- 版本 v3.0.583 / 30583；`assembleRelease` 通过；公网 200 / Range 206

### 8.0.79 落库首回调 content 是 msgsource 占位（v3.0.584 关键经验）
- Date: 2026-11-05
- Context: 分身点歌仍群发 545 个群，真实日志显示群发内容就是 `<msgsource>…` XML
- Category: 消息接收域 / 排错调试
- Instructions:
  - 微信落库先回调一次，`F0()` 此时返回 `<msgsource>…`，正文与水印都在紧随其后的第二次回调（同 msgId、同 svrId，间隔数毫秒）
  - 任何在首次回调读 content 做判定的逻辑都会拿到 XML：转发、AI、点歌、关键词一律中招
  - `SelfMsgGuard.isMsgsourceXml(content)`：剥零宽/空白后看前缀 `<msgsource`，三条接收链在 isSend==0 之后、业务判定之前直接 return
  - **必须放在去重之前且不写入去重集合**：svrId 是去重键，占位若占了键，正文回调会被当成重复丢掉
  - 图片/AppMsg 正文不是 `<msgsource`，不受影响；null/空快速返回 false
  - 排查此类问题先看「实际发出内容」，不要只看 skip 日志——583 的 skip 日志正常，但被转发的仍是 XML
- 版本 v3.0.584 / 30584；`assembleRelease` 通过；公网 200 / Range 206

### 主微信与分身 prefs 各自独立，面板必须标实例（v3.0.585 关键经验）
- Date: 2026-11-05
- Context: 用户称分身未开自动转发、目标为空，日志却显示 user999 加载 enabled=true targets=545，判断为主微信/分身配置看串
- Category: 环境配置 / 排错调试
- Instructions:
  - prefs 路径随用户隔离：`/data/user/0/com.tencent.mm/...` 与 `/data/user/999/com.tencent.mm/...`，互不同步，UI 与 hook 在各自进程读各自那份
  - 面板标题固定显示 `InstanceManager.isPrimary()/userId()` 与 `applicationInfo.dataDir`
  - 打开配置、点击保存、`updateConfig` 启动加载三处都要记录实例、目录、enabled、来源数、目标数
  - 目标为空时 `forwardAll` 直接返回；目标 545 时面板必有红字「已选 545 个目标」，可据此判断用户看的是哪份配置
  - 回答此类疑问先给日志里的 InstanceManager/userId 证据，再让用户在同一面板看红字数量
- 版本 v3.0.585 / 30585；`assembleRelease` 通过；公网 200 / Range 206
