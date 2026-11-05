# 深度检查清单 v3.0.551

## 防撤回提示
- [x] 带参构造 e9（talker 作为构造参数，GroupFeatures 实证路径）
- [x] read-back 验证：type==10000 / talker 匹配 / isSend==0
- [x] setter 失败记录日志（callSafe 不再静默）
- [x] 去掉 Mb 兜底（避免重复失败）
- [x] ClassLoader 统一（Tinker 真实 CL 优先）
- [x] 补充 status=1 字段
- [x] 失败时放弃入库，不产生脏消息

## 群管理
- [x] 黑白名单 summary 显示成员昵称列表（最多 4 个 + 共 N 人）
- [x] 配置弹窗宽度 90% 屏、高度上限 90% 屏
- [x] 配置弹窗底部按钮固定（内容区滚动）
- [x] 群成员选择器窗口 ≤90% 宽高，扣减导航栏高度
- [x] 全局设置时选择成员有明确提示
- [x] 名单顺序稳定（LinkedHashSet）
- [x] 清理无用 import

## 编译
- [x] ./gradlew :app:assembleRelease 通过
- [x] 版本号五处同步 v3.0.551 / 30551
