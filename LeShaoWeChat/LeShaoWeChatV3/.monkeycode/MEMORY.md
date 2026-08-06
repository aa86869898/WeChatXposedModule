# 用户指令记忆

## 格式

### 用户指令条目
[用户指令摘要]
- Date: [YYYY-MM-DD]
- Context: [提及的场景或时间]
- Instructions: [具体内容]

## 条目

### Git 推送方式
- Date: 2026-07-24
- Context: 用户提供 GitHub token 用于推送
- Category: 工作流协作
- Instructions:
  - 推送时使用原生 git push 命令，不使用 credential helper
  - 推送命令格式: `git push https://<token>@github.com/aa86869898/WeChatXposedModule.git master`
  - 修改代码后编译通过检查没问题后，自动提交并推送，无需用户确认
  - 不要在回复中展示 token 值

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
