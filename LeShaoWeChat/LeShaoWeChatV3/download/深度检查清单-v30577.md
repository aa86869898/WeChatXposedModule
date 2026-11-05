# 深度检查清单 v3.0.577

## Tinker 双 ClassLoader 根因
- [x] 定位：DexKit Class 来自 base.apk 平行 CL，运行时为 DelegateLastClassLoader
- [x] 证据：MsgReceiveHook 打印真实 CL，MessageHook 未打印且 hook 零捕获
- [x] 存储链 Kernel not initialized / 服务实例仍 null 归因确认

## 反编译实证（8.0.79）
- [x] h2 implements po3.c4；h2.Jj()→f9；h2.Aj()→j4
- [x] f9 implements ro3.m0；j4 implements com.tencent.mm.storage.d8
- [x] f9 插入入口 Mb(e9)/Nb(e9,boolean) 确认
- [x] m3.v(int,String) 存在（selfWxid）
- [x] ro3.m0 / d8 / po3.c4 / h2 / e9 / f9 / j4 类描述符均在真实 dex

## StorageHub
- [x] resolveOn / resolveInterfaceOr 按类名在真实 CL 重解析
- [x] 主链服务键 po3.c4（兼容 tn3.c4/sh3.c4，兜底存储接口）
- [x] getterByPrefs 优先 Jj/Aj；serviceLocatorClass 兜底 pp0.i1
- [x] readConfigStorage 改用 svc；null 守卫完备

## MessageHook
- [x] base CL 立即挂 + scheduleRealClRehook 后台补挂真实 CL
- [x] hookX9Dispatch 增加 resolveRealCl
- [x] sHookedF9Classes/sHookedX9Classes 去重（synchronizedSet）

## RedPacketHook
- [x] install 用 resolveRealCl 解析 scene
- [x] scheduleSceneResolveRetry 真实 CL 就绪后重解析并覆盖错误 scene
- [x] grab 前守卫：非真实 CL 则重解析再构造

## MessageMenuHook
- [x] pickRealClassLoader attempt>=1 回退普通 CL（修 loader=null）

## 其他
- [x] 未反射写入微信内部字段、未激活原生语音发送链路（红线保持）
- [x] 编译通过（assembleRelease）
- [x] 版本号五处同步 v3.0.577 / 30577
- [x] 公网下载 200 / Range 206
