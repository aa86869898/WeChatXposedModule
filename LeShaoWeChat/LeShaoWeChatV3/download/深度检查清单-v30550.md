# 深度检查清单 v3.0.550

## 防撤回提示
- [x] 移除 getDeclaredMethods setter 检查（父类 setter 误判根因）
- [x] 入库前 read-back 验证 getType()==10000，失败放弃入库
- [x] 自定义提示文字配置（{name} 占位符）保存/读取闭环
- [x] sHint 静态默认值与 prefs 默认一致（true）
- [x] 配置弹窗新增输入框暗色可读、支持多行
- [x] 无空指针风险（prefs null 兜底、模板 trim 校验）
- [x] 线程安全（后台单线程插入，SharedPreferences 线程安全）

## 群管理页面
- [x] 窗口背景不透明（surface 实底），滚动区背景同步
- [x] 宽度 90% 屏、高度上限 90% 屏自适配
- [x] show 后二次固定窗口尺寸/背景（防 AlertDialog 重置）
- [x] 上下间距压缩（品牌头/分组标题/卡片/开关行）
- [x] 群成员选择器：头像 + 昵称/备注 + 搜索 + 多选 + 全选
- [x] 群成员数据：membersOf 加载 + rcontact 昵称查询
- [x] 成员列表 ScrollView 可滚动，加载中提示可取消
- [x] 清理未使用 import

## 编译
- [x] ./gradlew :app:assembleRelease 通过
- [x] 版本号五处同步 v3.0.550 / 30550
