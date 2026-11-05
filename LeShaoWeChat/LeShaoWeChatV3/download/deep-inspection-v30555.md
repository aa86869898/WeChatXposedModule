# 深度检查清单 v3.0.555

## 防撤回（微信原生链路）
- [x] 放行 b41.t.c（微信生成原生系统提示）
- [x] before 深拷贝原消息（f9.O3 getBySvrId）
- [x] after 插入副本（Nb）+ Qc 原生通知
- [x] after 修改提示文字（自定义模板）
- [x] 自己撤回完全放行
- [x] ThreadLocal 传递上下文
- [x] 深拷贝失败安全降级

## 自动转发
- [x] 方法名适配 8.0.79（Nb/Mb/insertMsgInfo/processAddMsg）
- [x] 修复 fw hooks installed: 0

## 群发助手
- [x] 入口按钮正常（ChatVoiceSwitchHook）
- [x] 群发向导调用正常（WmChatHook）
- [x] ForwardLimitHook 已安装（转发上限突破）

## 日志
- [x] MessageMenuHook 动态候选
- [x] HookManager 失败名输出
- [ ] AdBlocker 锚点待反编译

## 编译
- [x] assembleRelease 通过
- [x] 版本号五处同步 v3.0.555 / 30555
