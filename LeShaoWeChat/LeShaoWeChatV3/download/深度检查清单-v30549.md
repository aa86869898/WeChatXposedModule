# 深度检查清单 v3.0.549

本次二次严格审查覆盖以下检查点，全部确认通过或已修复。

## 防撤回

- [x] sSelf 强制 false 后 H4 不再安装（tryInstall 仅在 if(sSelf) 调用 hookSelfRevoke，无残留路径）
- [x] sHint 默认 true，「保留撤回失败提示」开关默认开启
- [x] H1 参数校验修正：只要求 replacemsg 存在，不要求 args[0] 是 String
- [x] 自己撤回判断精确匹配「你撤回了一条消息」
- [x] 提示行 type=10000 不参与关键词回复/敏感词过滤
- [x] 提示行插入前检查关键 setter 存在性，缺失则放弃插入
- [x] 「自己撤回也失效」开关禁用（功能已停用）
- [x] H3（商务号 3 参）/ H2（群聊历史 0 参）不受 H1 参数校验影响，恢复有效

## 群管理重设计

- [x] 名单初始 Set 按 JSON 解析（listSet），不再 split(",") 处理 JSON 数组串
- [x] inList 合并检查全局段（"" 键），全局名单生效
- [x] DexKit 动态定位：缓存前验证类确有「静态 1 参返回 List」方法
- [x] findStaticMethod：返回类型 isAssignableFrom + 参数类型可接收 String + setAccessible(true)
- [x] membersOf 三级回退：动态类 → 硬编码类 l41.u1 动态方法 → 旧方法名 o
- [x] 群成员选择器：搜索按 wxid 过滤、多选状态按 wxid 保留、回调在主线程
- [x] ListView 固定高度 360dp，避免 weight 塌陷
- [x] CheckedTextView 用 StateListDrawable 区分选中/未选中
- [x] 成员选择行补卡片背景与间距
- [x] 管理员/黑名单/白名单选择器绑定正确（getChildAt(1) = 选择成员按钮）
- [x] 保存逻辑：live set 为最新值，setCfgStrList 格式与 inList 兼容
- [x] 清理无用 import（java.util.Arrays）与未使用变量

## 编译验证

- [x] ./gradlew :app:assembleRelease 通过
- [x] 版本号五处同步为 v3.0.549 / 30549（build.gradle.kts / MainHook MODULE_BUILD+MODULE_VERSION_CODE / DexKitHelper.CURRENT_MODULE_VERSION）
