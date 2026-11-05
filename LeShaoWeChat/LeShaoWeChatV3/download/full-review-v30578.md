# 全项目功能审查报告 v3.0.578

## 审查范围
- 语音转发（模块「语音转发」菜单项 / 原生「转发」142 项 / 选人器 finish / MsgRetransmitUI 回灌）
- 普通转发（压缩包 / 文件 / 文字 / 图片）与语音转发的区分
- 自动转发规则（AutoForwardHook）语音发送

## 结论
1. 误转发根因：`WxForwardReplaceHook` 的 `[FwdFix][A]`(选人器 finish) 与 `[FwdFix][B]`(MsgRetransmitUI.onActivityResult)
   在任意普通转发时都会把“缓存里最新的语音”发送出去，且 `pendingVoiceE9()` 对 `sVoiceMsgs` 取最新**无 TTL**，
   导致旧语音被无限期复用 → 群转发压缩包时把私聊旧语音一起发进群。
2. 修复采用**授权闸门**：仅当用户真正点击「语音转发」菜单（`launchNativeVoiceForward` 或 `BQ_Q_142` 点击语音）
   才置位 armed，且 90s 窗口内有效；`[A]`/`[B]` 严格以 armed 为准，未授权一律放行不发送。
   `BQ_Q_142` 增加类型校验，只有 `type==34` 的语音才授权，非语音（压缩包/文件/文字/图片）忽略。
3. 发送后/取消/目标过滤均 `disarmVoiceForward()`，无跨流程残留；`pendingVoiceE9()` 去掉无 TTL 兜底。
4. 自动转发规则（AutoForwardHook）发送语音属用户显式配置的预期行为，保持原逻辑不变。

## 回归风险评估
- **语音转发功能**：正常路径（点「语音转发」→ 原生选人 → 模块发送）依赖 armed；`viewitems.yb#R` 已确认存在于 8.0.79，
  `BQ_Q_142` 与模块自注入项点击两条置位入口均在，功能可正常触发。
- **普通转发功能**：未 armed → `[A]`/`[B]` 直接放行，微信原生转发链路不受影响，压缩包/文件/文字照常转发。
- **误发窗口**：已消除——普通转发不再读取任何语音缓存；旧语音不再被无限期复用。
- 红线保持：未反射写入微信内部字段、未激活原生语音发送链路，语音仍走「微信原生选人 + 模块 TtsVoiceSender」。

## 分身环境
- 分身日志目录缺 `dexkit_baseline.json` / `signature_dump.txt` 属诊断文件差异（前者仅全新扫描导出、后者仅 Debug 生成），
  不影响分身功能；配置存于独立 prefs（`xposed_wechat_iso`）。
- 本版 `CURRENT_MODULE_VERSION` 升至 30578，分身缓存失效 → 下次启动重扫，baseline 随之生成。

## 版本
- v3.0.578 / 30578
