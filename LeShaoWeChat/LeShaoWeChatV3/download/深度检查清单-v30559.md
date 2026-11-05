# 深度检查清单 v3.0.559

## 反编译
- [x] 找到并验证用户提供的 94 个 dex 为微信 8.0.79
- [x] 反编译 classes54/48/28
- [x] 确认 vc1.j.a 为 isAdContact（K2 已覆盖）
- [x] 确认 h3.b(b12) 为直播列表刷新（非广告，删除 hook）
- [x] 确认 mbad 字符串存在（恢复 hook）
- [x] 确认 channels.a0 类存在（运行时类加载差异）

## AdBlocker 修复
- [x] 恢复 mbad.innerReport
- [x] 删除 isAdContact 重复 hook
- [x] 删除 finder.liveGuideAd 误伤 hook
- [ ] rewardAd 待子进程 DexKit 动态定位（保留 seekTo 兜底）

## 防撤回
- [x] 放行方案 + field_content 兜底
- [ ] 需实机撤回事件验证

## 编译
- [x] assembleRelease 通过
- [x] 版本号五处同步 v3.0.559 / 30559
