# 深度检查清单 v3.0.552

## 防撤回提示
- [x] 按微信原生 type=10000 系统消息格式（new e9 + setType(10000) + b1）
- [x] 带参构造 e9（talker 作为构造参数）
- [x] status=1 / isSend=0 / createTime 字段完整
- [x] 三重 read-back 验证（type / talker / isSend）
- [x] setter 失败记录日志
- [x] 自定义文字模板 {name} 占位符
- [x] 配置弹窗输入框保存/读取闭环

## 群管理窗口（≤90%）
- [x] 配置弹窗：宽度 90% 屏，高度 min(内容+按钮, 90% 屏)
- [x] 配置弹窗：show 后 post + setAttributes 强制窗口尺寸
- [x] 选择器：宽度 90% 屏，高度 90% 屏 - 导航栏
- [x] 选择器：post + setAttributes 防重置
- [x] 内容区 weight=1 滚动正常
- [x] 底部按钮固定可见

## 群成员选择器
- [x] 按钮等宽布局（取消左 / 全选中 / 确定右）
- [x] 修正 space 间距为 0 问题
- [x] 成员加载正常（日志 157/159 人）
- [x] 列表在窗口高度正确设置后可滚动显示
- [x] 搜索按昵称/wxid 过滤
- [x] 头像 + 昵称/备注显示

## 日志问题清单
- [x] 已整理 6 项（详见质检修复报告第五节）

## 编译
- [x] ./gradlew :app:assembleRelease 通过
- [x] 版本号五处同步 v3.0.552 / 30552
