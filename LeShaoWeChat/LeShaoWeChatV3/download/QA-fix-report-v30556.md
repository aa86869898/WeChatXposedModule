# 质检修复报告 v3.0.556

## 一、TTS 输入框误触发（#tts 未发送就切换 / 打字乱发）

### 日志证据
```
ChatFooter input: content='#tts' talker=47842538229@chatroom   ← 输入框刚输入
ChatFooter #tts toggle talker=47842538229@chatroom             ← 立即切换开关
ChatFooter TTS-mode text -> voice talker=... text='大家好'
ChatFooter TTS-mode text -> voice talker=... text='大家好，'
ChatFooter TTS-mode text -> voice talker=... text='大家好，我是'
...（每输入一个字符就合成发送一次）
```

### 根因
ChatFooter 拦截器 hook 了 `p/g/Q0/O/q/d/V0` 等方法，其中 **8.0.79 的 `O(String)V` / `q(String)V` 等返回 void 的方法是「输入框内容同步回调」**（用户打字时调用），不是发送方法。
导致：
1. 输入 `#tts` 还没按发送就触发了 toggle。
2. TTS 模式下，输入框每变化一个字符就被当成要发送的文本 → 逐步合成语音乱发到群里（"大家好"只打了 3 个字就被发送，并不断循环）。

### 修复
**只 hook 返回 `boolean` 的方法**（`p/g/Q0` 等真正的发送入口，用户按发送时才调用），不再 hook 返回 `void` 的内容同步回调。
- 输入框打字不再触发任何 TTS 动作
- 按发送时才处理 `#tts` 切换 / TTS 模式自动转语音（整段文本完整合成）
- 发送文本转语音仍有 f9.Nb/Bb 双链路兜底

## 二、附带说明
- 接收消息的 TTS 模式（收到文本自动转语音）不受影响。
- 语音消息（type=34）发送后不会被再次识别为文本，循环终止。

## 三、编译
- ./gradlew :app:assembleRelease 通过
- 版本号五处同步 v3.0.556 / 30556
