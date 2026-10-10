# 用户指令记忆

## 格式

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

### v959 构建与发布流程
- Date: 2026-09-22
- Context: Agent 在执行 LeshaoAI 模块 v959 修复发布时总结
- Category: 构建编译
- Instructions:
  - 编译命令: `cd /workspace/LeShaoWeChat/LeShaoWeChatV3 && ANDROID_HOME=/opt/android-sdk ./gradlew :app:assembleRelease --console=plain`（无 local.properties，必须 ANDROID_HOME；大改动后先 `./gradlew clean`）
  - 版本号三处同步: app/build.gradle.kts(versionCode/versionName) + MainHook.java(MODULE_BUILD/MODULE_VERSION_CODE)
  - APK 产物名规则: `LeShaoWeChat-v<versionCode>.apk`，需拷贝到 `download/` 与项目根目录两处
  - 两个 index.html（根目录与 download/）必须同步且 diff 一致: 新版置顶为"最新版"，原最新版降级为"上一版"
  - 验证: md5sum 一致 + 公网直链 `https://8899-796f33fc01a6a82b.monkeycode-ai.online/download/<apk>` 返回 200 且下载 md5 相同（8899 为 http.server，cwd=LeShaoWeChatV3）
  - gradle.properties 的 aapt2 override 已指 /opt/android-sdk/build-tools/35.0.1/aapt2，勿改回
  - 发布后校验: dexdump 确认新类已进 DEX（`/opt/android-sdk/build-tools/35.0.1/dexdump <apk> | rg "Lcom/leshao/ai/<类>;"`）

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
