# 全项目功能审查报告 v3.0.577

## 审查范围
- TTS 语音播报（消息入库 / x9 分发 / 语音自动播放）
- 联系人列表获取（RContactStorage）
- 红包（接收 / 领取）
- 长按菜单（消息菜单项注入）

## 结论
1. 统一根因为 Tinker 双 ClassLoader，本轮改为「按类名在真实 CL 上重新解析所有 Class 对象」，覆盖存储绑定、消息 hook、红包 scene、长按菜单四类链路。
2. 存储链：服务键由实现类 f9 修正为接口 po3.c4（h2 实现），h2.Jj()/Aj() 取 f9/j4，解决 `服务实例仍 null` 与 `MsgInfoStorage/RContactStorage 获取失败`。
3. 消息播报：f9 入库 + x9 分发均在真实 CL 上补挂，修复「收到消息无播报日志」（hook 零捕获）。
4. 红包：scene 类按真实 CL 解析并支持后台覆盖，修复 n6 构造 `InvocationTargetException`。
5. 长按菜单：修复 `loader=null` 导致 15 候选全 `ClassNotFound`。

## 回归风险
- base/real 双 CL 去重集合（synchronizedSet）保证回调不翻倍；非 Tinker 环境真实 CL 恒等于 base，后台补挂自动跳过。
- 长按菜单在分身/非 Tinker 环境退回普通 CL 安装；菜单类通常不在 Tinker 补丁 dex 中，base CL Class 与运行时一致，可正常 hook（已权衡重复菜单风险，不做二次重装）。
- 存储 getter 仍保留「返回类型兜底扫描」，Jj/Aj 命中优先，跨版本多方法兼容。
- 红线保持：未反射写入微信内部字段、未激活原生语音发送链路。

## 版本
- v3.0.577 / 30577
