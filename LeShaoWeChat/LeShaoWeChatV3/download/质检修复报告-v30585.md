# 质检修复报告 · v3.0.585

**版本**：3.0.585（versionCode 30585）
**构建**：`assembleRelease` 通过
**APK**：`LeShaoWeChat-v30585.apk`，10,489,006 字节，MD5 `67603bbb212fae390e7bbc2c50d30d85`
**基准微信**：8.0.79 / versionCode 3200
**本次主题**：**自动转发面板标明实例与配置目录，打开/保存均留痕**

---

## 一、问题

主微信与分身的配置各存一份 SharedPreferences：

- 主微信：`/data/user/0/com.tencent.mm/shared_prefs/leshao_v3_prefs.xml`
- 系统分身：`/data/user/999/com.tencent.mm/shared_prefs/leshao_v3_prefs.xml`

面板长得一模一样，也不显示自己在哪个实例。用户在主微信勾了来源、看到目标为空，就以为分身也没目标；而分身那份里仍是来源 1 条 + 目标 545 条，于是继续群发。

真实日志（`leshao_v3_log.txt` 11:08）证明运行实例是 user999，且加载到的配置为：

```
11:08:15.500 InstanceManager: init DONE: userId=999 primary=false dataDir=/data/user/999/com.tencent.mm
11:08:16.272 AutoForward: config: enabled=true sources=1 targets=545 types=[1]
```

`types=[1]` 只有文本，属于被手工改过的配置，不是默认值。

## 二、修复

| 位置 | 变化 |
|---|---|
| 配置面板标题 | 显示「当前实例: 主微信 / 分身 user999」与 dataDir 全路径 |
| 打开面板 | 日志记录实例、dataDir、enabled、来源数、目标数 |
| 点击保存 | 日志记录实例、dataDir、enabled、来源数、目标数 |
| `updateConfig` | 启动时日志追加 userId 与 dataDir |

这样下次再出现「面板显示为空却群发」，日志能直接指出是哪个实例、哪个目录、几个目标。

## 三、判定口径（答复本轮疑问）

- 目标为空时 `forwardAll` 直接返回，不可能群发；因此面板若真显示「⚠ 未设置目标」，它看的就不是 11:08 那个实例。
- 11:08 的实例是 user999，配置里目标 545、类型仅文本。面板在 545 时必定出现红色「已选 545 个目标」提示。
- 来源是否勾选与目标是否为空是两件事：来源决定「拦不拦这条消息」，目标决定「发给谁」。两者都不为空才会真正外发。

## 四、验证

| 项 | 结果 |
|---|---|
| `assembleRelease` | 通过 |
| 五处版本号 | 全部 30585 / 3.0.585 |
| APK 大小 / MD5 | 10,489,006 字节 / `67603bbb212fae390e7bbc2c50d30d85` |
| 公网 turbo / Range | 200 / 206 |
| 回归 | msgsource 占位 10 用例、身份 8 用例、账本 7 用例、水印 9 用例 |

## 五、用户侧确认

在**要操作的那个微信**里打开模块 → 自动转发，标题应显示「当前实例」。目标行：

- 红字「已选 545 个目标」→ 就是群发的那份配置；
- 灰字「⚠ 未设置目标」→ 这份不会群发。

*配套文档：`深度检查清单-v30585.md`、`全项目功能审查报告-v30585.md`。*
