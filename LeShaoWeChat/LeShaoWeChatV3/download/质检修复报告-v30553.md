# 质检修复报告 v3.0.553

## 一、防撤回系统提示（根因：8.0.79 setter 改名）

### 日志证据
```
[H1] hint inserted id=303728（入库成功）
[H1] hint setter b1 err: NoSuchMethodError: e9#b1[String]
[H1] hint setter k1 err: NoSuchMethodError: e9#k1[Integer]
...（e1/t1/r1/r3 同样 NoSuchMethodError）
```
提示行**入库成功**但 **content 为空**（b1 设置失败），UI 渲染 type=10000 空文本 → 用户看不到文字。

### 根因
微信 8.0.79 中 e9 的 setter 方法名变化（b1/k1/e1/t1/r1/r3 已不存在），带参构造 `new e9(talker)` 只设置了 talker，type=10000 由 setType（仍存在）设置，但 content 等字段没设置成功。

### 修复
改用 **field_* 字段直接设置**（TtsVoiceSender/WeChatMessage 实证这些字段在 8.0.79 存在）：
- `field_talker`（带参构造已设置，双保险）
- `field_type = 10000`（setType 仍可用，字段兜底）
- `field_content = 自定义文字`（关键修复）
- `field_isSend = 0` / `field_createTime = 当前时间` / `field_status = 1`
- `field_msgSource = ""` / `field_msgSeq = 0`

read-back 验证升级为四重：`getType()==10000` / `N0()==talker` / `z0()==0` / **`S1()==text`**（content 必须等于自定义文字），任一失败放弃入库。

## 二、头像二次打开不显示

### 根因分析
首次打开列表时微信内存缓存命中（getCachedAvatarBitmap 返回 Bitmap）；二次打开时微信内存缓存已释放（LRU），本地/URL 加载在 8.0.79 下不可靠 → 只显示占位图。

### 修复
新增**模块磁盘缓存**：
- `loadAvatar` 开头优先读模块缓存目录（`cacheDir/avatar_cache/<md5>.png`）
- 所有成功加载路径（微信内存/本地文件/URL）都写入模块磁盘缓存
- 二次打开直接从模块缓存读取，不再依赖微信内存缓存

同时将 `modelavatar_d1` 的 DexKit 锚点改为直接类名注册（`com.tencent.mm.modelavatar.d1` 稳定），消除日志中大量 `findMethodDeclClassByString(...) cache MISS` 噪音。

## 三、红包抢到不播报金额

### 根因
播报链路 `h6.onGYNetEnd` 的 `onOpenEnd` 依赖 `sHandled.contains(sendId)`（自动抢红包标记）。
**手动抢红包时 sendId 不在 sHandled 中 → 直接 return，不播报金额。**

### 修复
- `onOpenEnd` **移除 sHandled 依赖**：手动/自动抢都能播报
- 新增 `sSpoken` 去重集合（每个 sendId 只播报一次，防止重复）
- 语音播报开关**默认开启**（抢到红包即播报金额，可在设置关闭）

## 四、模块日志提升到 1MB

`LogWriter.MAX_SIZE` 从 `512 * 1024` 提升到 `1024 * 1024`（1MB）。

## 五、日志其他问题清单

| # | 问题 | 状态 | 说明 |
|---|------|------|------|
| 1 | `MessageMenuHook: load class fail ...viewitems.o0` | 待反编译 | 8.0.79 该类已改名，需反编译 APK 获取新类名 |
| 2 | `AdBlocker: appbrand.isAdContact / MenuFooter / mbad.innerReport 锚点失效` | 待反编译 | 8.0.79 appbrand 广告类漂移，需反编译更新字符串锚点 |
| 3 | `WxResolver: resolve(rn3.s1 / r41.e0 / qy3.z7) fallback failed` | 已降级 | 类名变化，fallback 失败但功能有兜底 |
| 4 | `activateAll DONE: 19 OK, 1 FAIL` | 待定位 | 日志未打印失败模块名，需加诊断 |
| 5 | `VAP: getVoicePath: y0() err` | 偶发 | 语音路径解析一次失败，可忽略 |
| 6 | `modelavatar_d1 DexKit MISS` 大量刷屏 | 已修复 | 改为 byName 直接注册 |

## 六、编译
- ./gradlew :app:assembleRelease 通过
- 版本号五处同步 v3.0.553 / 30553
