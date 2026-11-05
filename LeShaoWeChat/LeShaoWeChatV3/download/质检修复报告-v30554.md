# 质检修复报告 v3.0.554

## 一、防撤回系统提示（read-back 异常修复）

### 日志证据
```
[H1] hint: read-back 异常，放弃插入提示行: java.lang.NoSuchMethodError: com.tencent.mm.storage.e9#S1[]
```
v3.0.553 在 read-back 中用 `S1()` 读取 content，但 **8.0.79 中 S1() getter 已不存在**，抛 NoSuchMethodError → 整个 read-back 异常 → 放弃插入。

### 修复
read-back 读取 content 改为：先 try `S1()`（兼容旧版），失败则读 `field_content` 字段（8.0.79 实证存在）。四重验证继续生效（type/talker/isSend/content），提示行可正常入库显示。

## 二、红包自动播报无效（修复）

### 日志证据
```
RedPacket: openwxhb sent sendId=...
RedPacket: 红包到账 amount=1分 nick=乐少 user=...
```
红包到账已触发 `onOpenEnd`，但 `speakAmount` 检查的是**静态字段 `sVoiceEnabled`**（启动时未从 prefs 加载，恒为 false）→ 不播报。

### 修复
`speakAmount` 改用 `isVoiceEnabled()`（读 prefs，默认开）——抢到红包即播报金额。

## 三、联系人/群聊头像（改进）

### 修复
1. `loadAvatarAsync` **优先调用微信原生头像绑定 `bindAvatar`**（微信跨进程异步加载，最可靠），失败才走模块加载链路。
2. `loadFromLocalFile` **扩展文件名候选**（user_/avatar_/无前缀 + png/jpg/hd 等），适配 8.0.79 可能的文件名变化。
3. 模块磁盘缓存保留（首次成功加载后写入，二次打开直接读）。

## 四、日志其他问题修复

1. **MessageMenuHook o0 类找不到**：8.0.79 该类已改名。新增 DexKit 按 `MMMenu` 字符串动态搜索 `viewitems/chatting` 包候选类，再经 `findBuilder` 验证 `a` 方法签名，跨版本自适配。
2. **HookManager `1 FAIL` 无法定位**：新增失败任务名列表输出（`activateAll DONE: xx OK, x FAIL failed=[...]`），下次可直接定位失败模块。
3. **AdBlocker 锚点失效**（appbrand.isAdContact / MenuFooter / mbad.innerReport）：需要反编译 8.0.79 微信 APK 获取新字符串锚点，本次无法完成（已列入待办）。
4. **WxResolver fallback（rn3.s1/r41.e0/qy3.z7）**：类名变化导致 fallback 失败，功能有兜底，低优先级。
5. **VAP y0 err**：偶发，可忽略。

## 五、编译
- ./gradlew :app:assembleRelease 通过
- 版本号五处同步 v3.0.554 / 30554
