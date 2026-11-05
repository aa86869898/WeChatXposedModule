# 质检修复报告 v3.0.558

## 一、反编译尝试结论（重要）

### 用户提供的 dex 验证
用户提供的一批 dex（/tmp/dex_check2、/tmp/opencode/dex1000、dexcheck、dexcheck30005/30006）经 jadx 反编译验证：
- 包含 `Lcom/leshao/v3;` 模块类定义
- **不包含**微信类定义（如 `Lcom/tencent/mm/storage/e9;`、`Lcom/tencent/mm/modelavatar/d1;` 的类描述符均为 0）
- 反编译输出顶层是模块源码（com/leshao/v3）

**结论：这批 dex 是模块 APK 的 dex（不同历史版本），不是微信 8.0.79 的 dex。**

### 微信 8.0.79 APK 下载尝试
- 安心APK/25PP：需扫码或反爬验证，无法直接下载
- 微信官网直链（weixin790android3200.apk 等）：404
- 应用宝/小米应用商店：下载接口需 JS/跳转，无法提取 APK
- 未获得真实微信 8.0.79 APK/dex

**影响**：AdBlocker 的 appbrand 广告代码在微信子进程独立 dex 中，主 dex 不含这些类。需要**真实微信 8.0.79 APK 或从手机 `/data/app/com.tencent.mm-*/base.apk` 提取的 classes*.dex** 才能精准反编译修复。

## 二、本轮已修复（动态适配，无需反编译）

1. **红包抢领延迟 1.5s → 0.4s**：红包 XML 解析时 talker 未知会走短延时兜底，缩短到 0.4s 提升抢红包速度（消息入库通常几十毫秒内完成）。
2. **删除 AdBlocker `mbad.innerReport` 失效锚点**：`MicroMsg.JsApiNotifyAdEvent[MBAd]` 字符串在 8.0.79 已不存在，每次启动刷 cache MISS。事件拦截已由 `advertise.p.notifyAdEvent` 覆盖，删除无副作用。

## 三、待真实微信 dex 后精准修复清单

| 问题 | 需要的反编译信息 |
|------|----------------|
| AdBlocker `isAdContact`（jsapi.n 的 boolean 闸门方法） | appbrand 子进程 dex |
| AdBlocker `finder.liveGuideAd`（h3.b 方法签名） | 视频号 dex |
| AdBlocker `rewardAd`（type.jsapi.channels.a0 新类名） | appbrand 子进程 dex |
| ChatGroupHook 标签存储服务链（j1 接口） | 通讯录 dex |
| FakeLocationHook 实时位置类名确认 | 定位相关 dex |

## 四、编译
- ./gradlew :app:assembleRelease 通过
- 版本号五处同步 v3.0.558 / 30558
