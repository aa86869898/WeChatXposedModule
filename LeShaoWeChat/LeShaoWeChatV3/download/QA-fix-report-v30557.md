# 质检修复报告 v3.0.557

## 一、去掉消息伪装功能
- 移除 MainHook 中 MsgForgeHook 注册
- 移除「联系人和群聊」页面消息伪装开关/入口
- 移除 SubPageActivity 消息伪装页面（case 23 置空）

## 二、输入框快捷菜单
1. **居中显示**：快捷菜单按钮行改为 `fillViewport=true` + 行宽 MATCH_PARENT + gravity CENTER，按钮少时居中、多时横向滑动。
2. **群管理只在群界面显示**：群管理按钮加 `ls_group_mgr_btn` tag，ChatFooterBarHook 在注入与 onResume 时按当前会话动态显隐（仅 `@chatroom` 显示，私聊隐藏）。

## 三、主页微信昵称显示
- `loadUserDetails` 的 DB 查询失败时，新增 `ContactRepository.queryAnyContactName(wxid)` 兜底（已验证的 rcontact 链路），确保昵称不显示为空/微信号。

## 四、顶部搜索栏彻底修复
- 补全「爱心捐赠」「乐少群发」的可搜索项（tag + searchMap）
- 搜索时改为**卡片级显隐**：卡片内无匹配项时整卡隐藏，避免空白卡片残留

## 五、深度地毯式检查（3 路并行）发现并修复的问题

### 已修复
1. **图片识别/豆包对话收不到消息（高）**：`MsgReceiveHook` 方法名数组未适配 8.0.79 的 `Nb/Mb`（仍只有 Bb/Db/Hb/yb），导致消息入库 hook 挂到已删除的 Bb 上。已补充 `Nb/Mb/insertMsgInfo/processAddMsg`。
2. **WxResolverRegistry 11 个失效锚点**（在 8.0.79 中 cache MISS）：`ImageQBarDataBean`、`h9;->h2`、`SnsInfo->cacheMediaPost`、`BaseFinderFeedLoader`、`finder/feed/model/y`、`finder/live/widget/d3`、`sns/model/q6`、`SnsFlexibleVideoActivity`、`SnsFeedOwnerDeleteCommentReportStruct`、`modelsimple/i0`、`RepairerConfigChattingLongClickMenuOrder` 等**批量改为 byName 注册**（fallback 类名 8.0.79 存在），消除大量 DexKit cache MISS 刷屏。
3. **MessageMenuHook C 层熄菜失效**：`m0.g(View)` 在 8.0.79 已改名，改为扫描 m0 类中「1 参 View 返回 void」的方法并全部 hook（仅全关时 setResult(null)）。

### 检查确认有效
- TTS 文字转语音：`#tts` 切换、SILK 编码、SceneVoice 发送（f71.d1.u）全链路正常
- 自动扫码进群：消息监听 + 识别 + `SEND addchatroommember` 正常
- 红包：检测/请求链路正常（本次红包已领完 hbStatus=4，非模块故障）
- 朋友圈秒集赞、语音转发、更新拦截、ForwardLimitHook：有效

### 待后续反编译修复（清单）
| 问题 | 说明 |
|------|------|
| AdBlocker `isAdContact`/`mbad.innerReport`/`finder.liveGuideAd` 锚点失效 | 8.0.79 字符串/方法漂移，需反编译 APK 更新锚点 |
| AdBlocker `rewardAd` 类名（type.jsapi.channels.a0）R8 改名 | 需反编译定位实际类名 |
| `ChatGroupHook` 标签存储 j1 服务链失效（retry 20 放弃） | 需适配 8.0.79 新 IM 服务接口 |
| 红包抢领 1.5s 延迟兜底 | 红包 XML 解析时消息未入库，性能优化项 |
| `FakeLocationHook` 类名 di3.s0 为推测 | 需反编译确认 8.0.79 实时位置类 |

## 六、编译
- ./gradlew :app:assembleRelease 通过
- 版本号五处同步 v3.0.557 / 30557
