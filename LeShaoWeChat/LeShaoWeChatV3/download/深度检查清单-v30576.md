# 深度检查清单 v3.0.576

## TTS 语音自动播放
- [x] 反编译 x0 确认 g 是 e9 引用（非 boolean）
- [x] 开关关闭时 param.setResult(null) 阻止微信原生播放
- [x] 移除错误 setBooleanField("g")
- [x] onVoiceMsg 加 shouldAutoPlay 防御
- [x] 消息播报 FilterManager 全类型受控（沿用）

## 群成员标签
- [x] 反编译确认新服务接口 e12.a
- [x] 反编译确认 a3.Q1/n2 返回 z2
- [x] 反编译确认管理员判定 z2.F0
- [x] GroupMemberNames 多方法兼容（Q1/n2/t1、F0/E0、e12.a/q02.f）
- [x] GroupTitleTagHook 使用 roleOf 链路恢复

## 其他
- [x] 编译通过
- [x] 版本号五处同步 v3.0.576 / 30576
