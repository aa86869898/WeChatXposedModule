# Requirements Document

## Introduction

为 MP3 转语音消息链路新增四项功能：音频分段切割发送、自定义误报时长、转换进度条、历史记录管理。同步新增联系人和群聊功能开关、TTS 语音播报界面优化。

## Glossary

- **音频转语音**: 将本地 MP3 文件解码、重采样、SILK 编码后作为微信语音消息发送
- **切割段**: 将长音频按设定时长切分为多段，每段独立发送为一条语音消息
- **误报时长**: 语音消息气泡上显示的时间长度，与音频实际可播放内容长度无关
- **历史记录**: 已转换发送过的音频文件索引，支持复用
- **模块UI风格**: AppColors/CandyUi 定义的亮暗双套色值体系，包括卡片背景、文字色、开关色等

## Requirements

### Requirement 1: 音频分段切割发送

**User Story:** AS 用户, I want 将长音频按设定时长切割为多段语音消息发送, so that 超长音频可以完整发送

#### Acceptance Criteria

1. WHEN 用户在转换配置弹窗中设置切割时长(单位为秒)且值大于0, THE 系统 SHALL 将解码后的 PCM 数据按切割时长对应的 PCM 字节数切分为多段
2. WHEN 音频被切割为多段, THE 系统 SHALL 依次发送每段语音消息, 并在每条消息中显示序号标签(格式: "第N段/共M段")
3. WHEN 用户未设置切割时长或设置为0, THE 系统 SHALL 将整段音频作为一条语音消息发送
4. IF 音频末尾段不足一个完整切割时长, THE 系统 SHALL 以实际剩余长度发送该段
5. WHILE 分段依次发送, THE 系统 SHALL 在每段发送完成后延迟500毫秒再发送下一段

### Requirement 2: 自定义误报时长

**User Story:** AS 用户, I want 自定义语音消息气泡上显示的时长, so that 显示的时长符合预期

#### Acceptance Criteria

1. WHEN 用户在转换配置弹窗中设置误报时长, THE 系统 SHALL 允许设置范围为1至60秒
2. WHEN 用户未设置误报时长, THE 系统 SHALL 默认使用1秒作为误报时长
3. WHEN 音频被切割为多段, THE 系统 SHALL 每段语音消息使用相同的误报时长
4. THE 系统 SHALL 仅在语音消息 XML 的 `voicelength` 字段填充误报时长, 不截断实际音频内容

### Requirement 3: 转换进度条

**User Story:** AS 用户, I want 在音频转换过程中看到进度, so that 了解转换状态

#### Acceptance Criteria

1. WHEN MP3 解码完成且 SILK 编码开始, THE 系统 SHALL 在聊天界面显示一个进度条对话框
2. WHILE SILK 编码进行中, THE 系统 SHALL 更新进度条百分比, 公式为: 已编码帧数 / 总帧数 * 100%
3. WHEN SILK 编码完成, THE 系统 SHALL 关闭进度条并显示发送结果提示
4. IF 转换过程中发生错误, THE 系统 SHALL 关闭进度条并显示错误提示

### Requirement 4: 历史记录管理

**User Story:** AS 用户, I want 查看历史已转换的音频并复用, so that 无需重复选择文件

#### Acceptance Criteria

1. WHEN 用户在长按菜单中点击"历史记录", THE 系统 SHALL 显示历史记录列表弹窗
2. THE 系统 SHALL 在每次音频转语音成功后, 将音频文件路径、文件名、转换时间戳记录到本地数据库
3. THE 历史记录列表 SHALL 显示每条记录的音频文件名和转换时间
4. WHEN 用户点击历史记录中的某条记录, THE 系统 SHALL 直接复用该音频文件进行转换和发送
5. WHILE 系统处于运行状态, THE 系统 SHALL 在每天首次启动时检查并删除超过30天的历史记录及其关联的音频文件
6. WHEN 用户点击"清空历史", THE 系统 SHALL 清除所有历史记录并删除所有关联的音频副本文件
7. IF 历史记录的音频文件已被删除, THE 系统 SHALL 在列表中标记该记录为"文件已不存在"并禁止复用
8. THE 历史记录弹窗 SHALL 使用 AppColors 亮暗双套色值和 CandyUi 圆角卡片风格, 适配暗色模式

### Requirement 5: 联系人和群聊功能开关

**User Story:** AS 用户, I want 独立控制各功能入口的显示与隐藏, so that 按需使用特定功能

#### Acceptance Criteria

1. THE 系统 SHALL 在联系人和群聊设置页新增"微信左上角菜单"开关, 控制微信三横菜单按钮的注入
2. THE 系统 SHALL 在联系人和群聊设置页新增"聊天窗口长按菜单"开关, 控制左下角长按菜单(MP3转语音)的注入
3. THE 系统 SHALL 在联系人和群聊设置页新增"输入框功能按钮"开关, 控制聊天输入框上方按钮行的注入
4. WHEN 开关关闭, THE 系统 SHALL 隐藏对应的 UI 注入元素, 不触发对应的事件监听
5. WHEN 开关打开, THE 系统 SHALL 恢复对应的 UI 注入元素和交互行为
6. THE 开关 SHALL 使用 CandyUi.newSwitch() 组件, 自动适配暗色模式

### Requirement 6: TTS语音播报界面优化

**User Story:** AS 用户, I want 播报设置界面更简洁, so that 功能项一目了然

#### Acceptance Criteria

1. THE 系统 SHALL 移除"自动播报类型"内每个功能项(文字/语音/图片/视频/位置/红包/转账/名片/文件/表情/引用/聊天记录/被拍/@播报/小程序/视频号消息播报)下方的小字格式说明
2. THE 系统 SHALL 移除"自动播报规则"内"是否播报全群消息"和"是否播报发送人昵称/备注"功能下方的小字说明提示
3. THE 系统 SHALL 保留各功能项的标题文字和开关组件, 不改变功能逻辑和保存行为
