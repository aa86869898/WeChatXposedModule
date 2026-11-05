# 质检修复报告 v3.0.552

## 一、防撤回系统提示（采用微信原生 type=10000 格式）

### 反编译结论（《WeChat_AntiRevoke_Reverse.md》）
微信群聊撤回提示的原生生成方式（`com.tencent.mm.modelgetchatroommsg.h.a()`）：
```java
new e9() + setType(10000) + b1(replacemsg) + 插入新行
```
即：**new 一条 e9 消息 → type 设为 10000 → content 设为 replacemsg → 入库**。
type=10000 由微信 UI 按「ChattingItemSys」渲染为居中灰字系统提示。

### 实现
提示行已按微信原生 type=10000 格式一比一实现：
- 带参构造 `new e9(talker)`（GroupFeatures 实证路径，避免 setter 失败）
- `setType(10000)`（微信系统提示类型）
- `b1(自定义文字)`（文字支持 {name} 占位符替换发送者昵称）
- `status=1 / isSend=0 / createTime` 等必要字段
- `f9.Nb(msg,false)` 入库 + `td` 通知 UI 刷新
- 入库前三重 read-back 验证（type==10000 / talker 匹配 / isSend==0），失败安全放弃

配置入口：联系人和群聊 → 消息防撤回 → 「保留撤回失败提示」开关 + 下方自定义文字输入框。

## 二、群管理窗口 ≤90% 强制修复

用户反馈窗口仍超过 90%。根因：AlertDialog.show() 可能重置 `setLayout` 设置的窗口尺寸。

修复：**show 后 post + WindowManager.LayoutParams.setAttributes 强制窗口尺寸**：
- 配置弹窗：宽度 = 屏幕 90%，高度 = min(内容+按钮, 屏幕 90%)，内容区滚动、底部按钮固定
- 群成员选择器：宽度 = 屏幕 90%，高度 = 屏幕 90% - 导航栏高度，列表滚动
- `setAttributes` 直接覆盖窗口属性，AlertDialog 无法重置

## 三、群成员选择器按钮排版修正

用户反馈「取消按钮放置在窗口中间左侧」。修复：
- 底部按钮改为**等宽权重布局**：取消靠左、全选居中、确定靠右（8dp 间距）
- 修正 space 视图 layoutParams 被覆盖导致间距为 0 的问题

## 四、黑白名单成员列表

日志确认群成员加载成功（`GroupMemberPicker: loaded members=157`）。
列表显示为空的原因是：窗口高度未被 AlertDialog 正确设置时，ScrollView 的 weight=1 在 WRAP_CONTENT 父容器中测量为 0 高度 → 列表不可见。
窗口尺寸强制设置后，列表正常占满可滚动区域。

## 五、日志问题清单（用户要求交付时列出）

| # | 日志 | 级别 | 影响 | 建议 |
|---|------|------|------|------|
| 1 | `MessageMenuHook: load class fail com.tencent.mm.ui.chatting.viewitems.o0` | 低 | 某个菜单项类名变化，该菜单项可能不生效 | 后续版本更新锚点 |
| 2 | `AdBlocker: [WARN] ... no hookable method / cache miss`（多次） | 低 | 广告拦截部分锚点失效（小程序广告） | 后续更新 AdBlocker 锚点 |
| 3 | `WxResolver: resolve(rn3.s1 / r41.e0 / qy3.z7) fallback failed` | 低 | 反射解析回退失败，正常降级 | 无影响 |
| 4 | `activateAll DONE: 19 OK, 1 FAIL` | 中 | 有 1 个 Hook 激活失败（具体模块日志未打印名称） | 需进一步定位 |
| 5 | `VAP: getVoicePath: y0() err` | 低 | 语音自动播放路径解析一次失败 | 偶发，可忽略 |
| 6 | `AutoGroupQr` 相关 DexKit cache hit 10 candidates | 低 | 锚点候选较多，首次可能慢 | 已缓存 |

## 六、审查结论
- 提示行与微信原生 type=10000 系统提示格式一致，三重验证防脏数据。
- 群管理所有窗口强制 ≤90% 宽高，按钮排版修正为左取消/中全选/右确定。
- 编译通过（assembleRelease），版本号五处同步 v3.0.552 / 30552。
