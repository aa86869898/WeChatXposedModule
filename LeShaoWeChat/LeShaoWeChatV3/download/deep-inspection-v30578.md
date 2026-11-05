# 深度检查清单 v3.0.578

## 误转发根因（群里转发压缩包却发出私聊语音）
- [x] 日志定位：19:24:40 `[FwdFix][A] finish picked=[...@chatroom]` → `VF: doForward msgId=10241588`（语音）→ `TtsVoiceSender SceneVoice talker=...@chatroom`
- [x] 真实意图为转发压缩包（msgId=10229123/10238775），语音为旧缓存
- [x] 事故前无 INJECT/VOICE_MARK（菜单注入日志均在事故之后）
- [x] 归因：[A]/[B] 无条件发送缓存语音；`pendingVoiceE9()` 对 sVoiceMsgs 取最新且无 TTL

## 修复：授权闸门 armed gate
- [x] 新增 `sVoiceFwdArmed` / `sVoiceFwdArmedAt` / `VOICE_ARM_TTL=90s`
- [x] `voiceForwardArmed()`：armed && 窗口内 && pending 非空
- [x] `disarmVoiceForward()`：清 armed + 清 pending
- [x] `launchNativeVoiceForward` 置位授权（模块语音转发项点击）
- [x] `BQ_Q_142` 仅语音(type==34)记录 pending 并置位授权；非语音忽略
- [x] `[FwdFix][A]`：`pendMsgF = armed ? pending : null`，空则放行不发送
- [x] `[FwdFix][B]`：未授权第一行放行，不改 Select_Conv_User、不发送
- [x] [A]/[B] 发送完成/取消/目标过滤后均 disarm
- [x] 删除 `pendingVoiceE9()` 对 sVoiceMsgs 的无 TTL 兜底（死代码一并移除）
- [x] `markVoiceForwardPending` 仅缓存，不再构成可发送 pending

## 发送调用点全覆盖
- [x] `WxForwardReplaceHook` [A]（finish）已守卫
- [x] `WxForwardReplaceHook` [B]（onActivityResult）已守卫
- [x] `AutoForwardHook`（自动转发规则，用户配置触发）保持原逻辑，属预期行为

## 反编译佐证（8.0.79）
- [x] `viewitems.yb#R(MenuItem,d,ym5.d)` 存在（classes87）
- [x] `MSG_TYPE_VOICE=34`；`msgTypeOf` 反射 getType/field_type

## 红线
- [x] 未反射写入微信内部字段
- [x] 未激活微信原生语音发送链路（仍走模块 TtsVoiceSender）
- [x] 语音转发仍走「微信原生选人 + 模块发送」

## 分身日志目录
- [x] 缺 dexkit_baseline.json：仅全新健康扫描后导出，分身复用了缓存
- [x] 缺 signature_dump.txt：ENABLED=BuildConfig.DEBUG，Release 不生成
- [x] 配置存 prefs（xposed_wechat_iso），非文件；提升 30578 将令分身缓存失效并重扫

## 编译与版本
- [x] compileDebugJavaWithJavac 通过
- [x] assembleRelease 通过（BUILD SUCCESSFUL）
- [x] 版本号五处同步 v3.0.578 / 30578
- [x] aapt 校验 versionCode=30578 / versionName=3.0.578
- [x] 公网下载 200 / Range 206
