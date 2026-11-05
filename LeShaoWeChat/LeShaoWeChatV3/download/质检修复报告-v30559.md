# 质检修复报告 v3.0.559

## 一、找到真实微信 8.0.79 dex 并反编译

找到用户上传的压缩包 `/workspace/uploads/20261029_041922__微信dex.zip`，解压目录 `/tmp/opencode/wechat_dex/com.tencent.mm` 含 **94 个 classes*.dex**。验证确认为真实微信 8.0.79 dex（含 `com.tencent.mm.storage.e9`、`modelavatar.d1`、`MicroMsg.JsApiEventShouldShowSplashAd` 等类/字符串）。

用 jadx 反编译 classes54/classes48/classes28 等，精准分析并修复 AdBlocker。

## 二、反编译精准修复（AdBlocker）

### 1. 恢复 `mbad.innerReport` 上报阻断
- 反编译确认 `MicroMsg.JsApiNotifyAdEvent[MBAd]` 字符串**存在于** classes29/54/69/75（之前是 DexKit 缓存 MISS，不是字符串不存在）。
- 已恢复该 hook。

### 2. 删除 `isAdContact` 失效 hook（重复）
- 反编译确认 8.0.79 中 isAdContact 宿主为 `vc1.j.a(AppBrandInitConfigWC)`（TAG `MicroMsg.AppBrandAdUtils[AppBrandSplashAd]`，日志 `isAdContact, appId:%s, canShowAd:%s`）。
- 该方法的拦截已由现有 `appbrand.K2`（`MicroMsg.AppBrandAdUtils[AppBrandSplashAd]` 1 参 boolean）覆盖，删除重复 hook 消除日志 WARN。

### 3. 删除 `finder.liveGuideAd` hook（反编译发现是误伤）
- 反编译确认 8.0.79 中 `com.tencent.mm.plugin.finder.live.widget.h3.b(b12)` 是**直播间列表/榜单刷新方法**（`getList -> notifyDataSetChanged -> 列表更新`），**不是广告引导**（原 `FinderLiveGuideFollowAdInfo` 方法已不存在）。
- 强行拦截会损坏直播间列表显示，**已删除该 hook**。

### 4. `rewardAd` 分析结论
- 反编译确认 `com.tencent.mm.plugin.type.jsapi.channels.a0` 类在 classes54.dex 中真实存在（`OpenChannelsRewardedVideoAdResult` 相关）。
- 运行时 `ClassNotFoundException` 是 R8 压平类名 + 子进程/Tinker 类加载差异导致，`Class.forName` 无法直接命中。
- 保留 `seekTo`（激励视频短播）兜底；后续可用 DexKit 字符串（`OpenChannelsRewardedVideoAdResult`）在子进程内动态定位。

## 三、防撤回状态说明
- v3.0.555 起采用「放行微信生成原生提示 + 深拷贝恢复原消息 + 修改提示文字」。
- read-back 验证用 `getType/N0/z0`（8.0.79 实证存在）+ `field_content` 兜底（S1 已不存在，已适配）。
- 需一次实机撤回事件验证（日志窗口内无撤回样本）。

## 四、编译
- ./gradlew :app:assembleRelease 通过
- 版本号五处同步 v3.0.559 / 30559
