# 深度检查清单 v3.0.558

## 反编译验证
- [x] jadx 安装并反编译用户提供的 dex
- [x] 确认用户提供的 dex 为模块自身 dex（非微信 8.0.79）
- [x] 尝试 4 个渠道下载微信 8.0.79 APK（均失败，源不可靠）

## 动态适配修复
- [x] 红包兜底延迟 1.5s → 0.4s
- [x] 删除 mbad.innerReport 失效锚点（避免 cache MISS 刷屏）

## 待真实微信 dex
- [ ] AdBlocker isAdContact / finder.liveGuideAd / rewardAd
- [ ] ChatGroupHook 标签存储服务链
- [ ] FakeLocationHook 类名

## 编译
- [x] assembleRelease 通过
- [x] 版本号五处同步 v3.0.558 / 30558
