# 全项目功能审查报告 v3.0.559

## 审查范围
- 用真实微信 8.0.79 dex 反编译精准修复 AdBlocker

## 结论
1. 找到用户提供的 94 个真实微信 dex，jadx 反编译成功。
2. 精准修复：恢复 mbad.innerReport、删除 isAdContact 重复 hook、删除 finder.liveGuideAd 误伤 hook。
3. rewardAd 因 R8 压平类名 + 子进程类加载差异，需 DexKit 动态定位（保留 seekTo 兜底）。
4. 防撤回放行方案已适配 8.0.79，待实机验证。

## 验证
- 编译通过（assembleRelease）
- 版本号五处同步 v3.0.559 / 30559
