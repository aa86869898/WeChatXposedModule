# 质检修复报告 v3.0.555

## 一、防撤回系统提示（重构为微信原生提示链路）

### 现状分析
v3.0.554 日志显示提示行已入库（`id=303817 text=...`），但用户仍看不到。
根因：手动插入 type=10000 后依赖 `f9.td(id,msg)` 通知 UI，该通知在 8.0.79 不可靠（可能匹配到错误重载），UI 不刷新。

### 重构方案（调用微信原生系统提示机制）
不再手动插入提示行 + 拦截改写，改为：
1. **放行微信 `b41.t.c`**：微信正常执行"原消息改写成系统提示"（type=268445456/285222674），并走微信原生 `Qc(msgId, e9, true)` 落库 + UI 刷新 + `RevokeMsgEvent`。
2. **before 深拷贝原消息**：通过 `f9.O3(talker, svrId)`（反编译文档确认 getBySvrId）取出原消息，反射深拷贝全部字段（清 msgId）。
3. **after 插入原消息副本**：副本用 `f9.Nb(copy, false)` 插入新行（原消息恢复显示 = 防撤回），再用微信原生 `Qc(newId, copy, true)` 落库 + 通知。
4. **after 修改系统提示文字**：用 `f9.O3(talker, svrId)` 取微信已生成的提示，把 content 改为自定义文字（{name} 模板），再用 `Qc(msgId, cur, true)` 落库 + 通知。

这样 UI 刷新走微信原生 `Qc` 通知（b41.t.c 同款），提示文字可自定义，原消息保留（防撤回）。

### 关键点
- 自己撤回（replacemsg 以"你撤回"开头）：完全放行，不处理。
- ThreadLocal 在 before/after 同线程传递深拷贝副本。
- 深拷贝失败则只修改提示文字（不恢复原消息），不崩溃。

## 二、自动转发功能修复

### 根因
`AutoForwardHook.hook` 只查找 `Bb/Db/Hb/yb` 方法名，8.0.79 中 `Bb` 已改名 `Nb/Mb` → `fw hooks installed: 0`，自动转发完全失效。

### 修复
方法名数组补充 `Nb/Mb/insertMsgInfo/processAddMsg`（与 WanQunGroupHook/MessageHook 一致），8.0.79 下自动转发可正常安装。

## 三、群发助手功能检查

结论：**功能链路完整，无失效问题**。
- 入口：ChatVoiceSwitchHook 注入「群发」按钮 → `WmChatHook.showMassSendFromCorner`（微信原生群发向导）
- 转发上限突破：ForwardLimitHook 已安装（日志 `hooked Intent.getIntExtra`、`hooks installed`）
- 群发依赖的消息入库方法已由 AutoForwardHook/MessageHook 适配 Nb/Mb

## 四、日志问题

| 问题 | 状态 |
|------|------|
| 防撤回提示 UI 不显示 | ✅ 重构为微信原生 Qc 通知链路 |
| AutoForwardHook 装不上（fw hooks installed: 0） | ✅ 方法名适配 Nb/Mb |
| MessageMenuHook o0 类找不到 | ✅ 已加 MMMenu 动态候选 |
| HookManager 1 FAIL 无法定位 | ✅ 失败任务名列表输出 |
| AdBlocker 锚点失效 | ⏳ 需反编译 8.0.79 APK |
| WxResolver fallback 类名变化 | ⏳ 低优先级 |

## 五、编译
- ./gradlew :app:assembleRelease 通过
- 版本号五处同步 v3.0.555 / 30555
