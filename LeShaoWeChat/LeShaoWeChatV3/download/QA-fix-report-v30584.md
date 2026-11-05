# 质检修复报告 · v3.0.584

**版本**：3.0.584（versionCode 30584）
**构建**：`assembleRelease` 通过
**APK**：`LeShaoWeChat-v30584.apk`，10,489,010 字节，MD5 `78d3c0e99b1cbe16684eb3cee3de0587`
**基准微信**：8.0.79 / versionCode 3200
**本次主题**：**落库首次回调的 msgsource XML 不再被当成正文转发**

---

## 一、真实日志

`leshao_v3_log.txt`（11-05 11:08:43）还原了 545 群群发的实际内容：

```
11:08:43.491 LeshaoAI.Recv: talker=wxid_qf3ok46p08v922 content='<msgsource>…'
11:08:43.493 AutoForward: fwd trigger: talker=wxid_qf3ok46p08v922 targets=545 content=<msgsource>
11:08:43.495 MessageHook: #22 msgId=10251867 content=​‌🔔叮咚 正在点歌 请稍等片刻…
11:08:43.495 AutoForward: skip: 模块自发消息(内容命中) content=<msgsource>
```

同一条消息（msgId 10251867）在 4 毫秒内被回调两次：

| 回调 | F0() 返回 | 带水印 | 结果 |
|---|---|---|---|
| 第 1 次 | `<msgsource>…</msgsource>` | 否 | 被当成普通文本 → 转发 545 个群 |
| 第 2 次 | `🔔叮咚 正在点歌…` | 是 | 正确命中自发护栏 → 跳过 |

因此 v3.0.583 的身份识别是有效的，但它管的是「谁发的」；这轮的问题在「正文还没准备好」。第二次回调才带正文，第一次回调永远赶在护栏之前。

## 二、修复

新增 `SelfMsgGuard.isMsgsourceXml(content)`：剥掉零宽字符与空白后，判断是否以 `<msgsource` 开头。

三条接收链在 `isSend == 0` 之后、任何业务判定之前直接跳过，**且不写入各自的去重集合**，让紧随其后的正文回调正常处理：

| 位置 | 效果 |
|---|---|
| `AutoForwardHook` | 不再把 XML 发到目标列表 |
| `MsgReceiveHook` | 不再把 XML 送 AI |
| `MessageHook` | 不再用 XML 匹配点歌/关键词/播报 |

去重键由 `svrId` 决定，占位回调不占用键，第二次回调仍能正常通过去重并命中 `isSongOutput` / 水印。

## 三、离线验证（34 用例）

| 组 | 用例 | 结果 |
|---|---|---|
| XML 判定 | 裸 XML / 水印+XML / 空白+XML / 仅标签 / 普通文本 / 空 / null / @ 文本 / `<msg>` / 超长前缀 | 10/10 PASS |
| 身份判定 | s0 命中 / 他人 / 大小写 / 空 self / 空消息 / 字段兜底命中 / 字段兜底不命中 / 空 s0 回退 | 8/8 PASS |
| 点歌账本 | 私聊提示 / 群提示 / 失败提示 / 语音 / 普通放行 / 类型键 / 精确命中 | 7/7 PASS |
| 水印 | 普通 / 已带水印 / @ 五种 / 群前缀 / 引用块 / 空串 | 9/9 PASS |

## 四、验证

| 项 | 结果 |
|---|---|
| `assembleRelease` | 通过 |
| 五处版本号 | 全部 30584 / 3.0.584 |
| APK 大小 / MD5 | 10,489,010 字节 / `78d3c0e99b1cbe16684eb3cee3de0587` |
| 公网 turbo / Range | 200 / 206 |

## 五、行为边界

| 场景 | 结果 |
|---|---|
| 首次占位回调 | 跳过，不转发、不送 AI、不匹配指令 |
| 紧随其后的正文回调 | 正常处理 |
| 只有一次回调且正文确为 XML | 不转发（宁可漏转，不发 XML） |
| 图片/AppMsg | 不受影响，正文不是 `<msgsource` |

*配套文档：`深度检查清单-v30584.md`、`全项目功能审查报告-v30584.md`。*
