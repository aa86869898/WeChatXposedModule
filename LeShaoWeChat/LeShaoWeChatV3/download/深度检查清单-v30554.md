# 深度检查清单 v3.0.554

## 防撤回
- [x] read-back content 改用 field_content（S1 在 8.0.79 不存在）
- [x] 四重验证（type/talker/isSend/content）
- [x] 提示行入库（不再因 S1 异常放弃）

## 红包播报
- [x] speakAmount 改用 isVoiceEnabled()（读 prefs 默认开）
- [x] 手动/自动抢都播报（v3.0.553 已去 sHandled 依赖）
- [x] sSpoken 去重

## 头像
- [x] loadAvatarAsync 优先 bindAvatar（微信原生异步绑定）
- [x] loadFromLocalFile 扩展文件名候选
- [x] 模块磁盘缓存保留

## 日志问题
- [x] MessageMenuHook 动态搜索 MMMenu 候选类（8.0.79 自适配）
- [x] HookManager 失败任务名列表输出
- [ ] AdBlocker 锚点需反编译 8.0.79 APK
- [ ] WxResolver fallback 类名需反编译更新
- [ ] VAP y0 偶发

## 编译
- [x] assembleRelease 通过
- [x] 版本号五处同步 v3.0.554 / 30554
