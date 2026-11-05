# 深度检查清单 v3.0.556

## TTS 输入框误触发
- [x] 只 hook 返回 boolean 的发送方法（p/g/Q0）
- [x] 不再 hook 返回 void 的内容同步回调（O/q）
- [x] 输入框打字不再触发 #tts toggle
- [x] 输入框打字不再触发 TTS 合成乱发
- [x] 按发送时才处理 #tts / TTS 模式转语音
- [x] f9.Nb/Bb 双链路兜底保留
- [x] 接收消息 TTS 模式不受影响

## 编译
- [x] assembleRelease 通过
- [x] 版本号五处同步 v3.0.556 / 30556
