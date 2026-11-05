# 深度检查清单 v3.0.557

## 用户需求
- [x] 去掉消息伪装（注册/UI/页面全移除）
- [x] 快捷菜单居中显示
- [x] 群管理只在群界面显示（动态显隐）
- [x] 主页微信昵称显示（ContactRepository 兜底）
- [x] 顶部搜索栏支持搜索模块内功能（补全可搜索项 + 卡片级显隐）

## 地毯式检查修复
- [x] MsgReceiveHook 适配 Nb/Mb（图片识别/豆包对话恢复）
- [x] 11 个 WxResolver 失效锚点改 byName
- [x] MessageMenuHook C 层适配 8.0.79
- [x] TTS/扫码进群/红包/朋友圈/语音转发确认有效

## 待反编译清单
- [ ] AdBlocker 3 个锚点 + rewardAd 类名
- [ ] ChatGroupHook 标签存储服务链
- [ ] 红包 1.5s 延迟优化
- [ ] FakeLocationHook 类名确认

## 编译
- [x] assembleRelease 通过
- [x] 版本号五处同步 v3.0.557 / 30557
