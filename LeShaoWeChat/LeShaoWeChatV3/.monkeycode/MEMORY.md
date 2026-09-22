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
