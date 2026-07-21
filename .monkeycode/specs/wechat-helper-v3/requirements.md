# Requirements Document - 微信助手 v3

## Introduction

从零开发一个全新的 LSPosed/Xposed 微信模块，覆盖原乐少助手 v2.1 的全部功能。目标微信版本为 3140，不参考原代码也不依赖 WeKit API，采用全新架构实现。

## Glossary

- **WeChat 8.0.72~8.0.76**: 目标支持微信版本区间
- **LSPosed**: Xposed 框架兼容层，模块运行环境
- **WCDB**: 微信使用的 SQLite 封装 (com.tencent.wcdb)
- **rcontact**: 微信联系人数据表
- **TTS**: Text-to-Speech 文字转语音
- **DingDong**: 叮咚点歌/媒体助手

## Constraints

1. 不引用 LeShaoWeChat v2.1 任何源代码
2. 不引用 WeKit 任何 API (DexKit/Reflekt 等)
3. 纯 LSPosed Xposed API 102 + 标准 Android SDK 实现
4. 目标微信版本区间: 8.0.72 ~ 8.0.76
5. UI 入口为轻量 Hook 微信设置页（搜索框下方添加跳转按钮），不嵌入原生页面布局
6. 数据库 Hook/WCDB 密钥捕获等待微信 attachBaseContext 执行完毕再初始化
7. 编译: Java 17, Gradle 8.5, Android SDK 35
8. 所有关键步骤须输出日志 (密钥捕获、Hook激活、数据库连接、联系人查询)
9. 须编写 R8 混淆规则保护反射相关类与方法

---

## Requirements

### REQ-1: 核心框架与入口

**User Story:** AS 微信用户, I want 模块自动注入微信进程, so that 所有功能正常运行

#### Acceptance Criteria

1. WHEN 微信进程启动, THE 模块 SHALL 通过 IXposedHookLoadPackage 注入微信 com.tencent.mm 进程
2. THE 模块 SHALL 在微信主界面提供至少一个入口进入功能面板
3. THE 模块 SHALL 提供 SharedPreferences 持久化存储所有配置
4. THE 模块 SHALL 支持实时读写配置，无需重启微信
5. THE 模块 SHALL 将运行日志写入文件 (路径: 微信 data 目录下)

### REQ-2: 消息拦截处理

**User Story:** AS 用户, I want 模块能拦截所有消息, so that 实现自动回复/播报/过滤等功能

#### Acceptance Criteria

1. WHEN 微信收到/发送任意类型消息, THE 模块 SHALL 拦截并解析消息类型 (文字/图片/视频/语音/红包/转账/名片/文件/位置)
2. WHEN 消息被拦截, THE 模块 SHALL 提取 talker(会话ID) 和 sender(发送者)
3. THE 模块 SHALL 支持解析消息中的 @提及 对象
4. IF 解析失败, THE 模块 SHALL 记录错误日志并跳过该消息

### REQ-3: 联系人/群聊读取

**User Story:** AS 用户, I want 查看所有好友和群聊列表, so that 配置播报范围和群发目标

#### Acceptance Criteria

1. WHEN 用户打开联系人选择器, THE 模块 SHALL 显示好友列表 (wxid + 昵称)
2. THE 模块 SHALL 支持在好友和群聊两个标签页之间切换
3. THE 模块 SHALL 支持搜索/过滤联系人
4. THE 模块 SHALL 支持多选和全选/反选
5. THE 模块 SHALL 通过 WCDB 数据库直读获取联系人数据 (绕过 ClassLoader 隔离)

### REQ-4: TTS 语音播报

**User Story:** AS 用户, I want 新消息自动朗读, so that 不用看屏幕也能知道消息内容

#### Acceptance Criteria

1. WHEN 新消息到达且播报开关开启, THE 模块 SHALL 将消息文本送入 TTS 引擎朗读
2. THE 模块 SHALL 支持按消息类型选择性播报 (文字/图片/视频/语音/红包/转账/名片/文件/位置/来电)
3. THE 模块 SHALL 支持配置播报模板格式 (自定义播报文案)
4. THE 模块 SHALL 支持播报白名单 (仅播报指定联系人/群)
5. THE 模块 SHALL 支持播报间隔设置 (两次播报之间的最小间隔)
6. THE 模块 SHALL 支持文本熔断 (超过 N 字仅播报前 N 字)
7. THE 模块 SHALL 支持自定义 TTS 引擎配置 (API Key, 语音ID)
8. IF TTS 引擎不可用, THE 模块 SHALL 回退到 Android 系统 TTS

### REQ-5: 叮咚点歌/媒体助手

**User Story:** AS 用户, I want 在群里发指令搜歌/点歌/查天气, so that 群成员可以自助获取服务

#### Acceptance Criteria

1. WHEN 用户发送"点歌 歌名", THE 模块 SHALL 搜索音乐并回复歌曲信息
2. THE 模块 SHALL 支持发送语音歌曲
3. THE 模块 SHALL 支持查询天气
4. THE 模块 SHALL 支持随机笑话/金句
5. THE 模块 SHALL 支持多平台音乐搜索 (酷我/酷狗/网易云)
6. THE 模块 SHALL 支持视频解析 (多平台视频链接提取)

### REQ-6: 群管理

**User Story:** AS 群主, I want 自动化群管理功能, so that 减少人工管理成本

#### Acceptance Criteria

1. WHEN 新成员加入群, THE 模块 SHALL 发送入群欢迎语 (支持文字/图片/语音/视频)
2. WHEN 成员退群, THE 模块 SHALL 发送退群提醒
3. THE 模块 SHALL 支持关键词自动拉人 (匹配邀请消息自动通过)
4. THE 模块 SHALL 支持防广告检测 (消息包含广告关键词自动标记)
5. THE 模块 SHALL 支持自动踢人 (累计违规达到阈值)
6. THE 模块 SHALL 支持黑名单管理 (黑名单成员自动踢出)
7. THE 模块 SHALL 支持警告功能 (发送警告消息)

### REQ-7: 自动通过好友

**User Story:** AS 用户, I want 自动通过好友申请, so that 不需要手动处理大量好友请求

#### Acceptance Criteria

1. WHEN 收到好友申请, THE 模块 SHALL 自动通过
2. THE 模块 SHALL 支持发送自定义好友通过欢迎语

### REQ-8: 安全功能

**User Story:** AS 用户, I want 防撤回/抢红包/敏感词过滤, so that 保护消息隐私和安全

#### Acceptance Criteria

1. WHEN 对方撤回消息, THE 模块 SHALL 记录撤回内容并可选提醒
2. WHEN 检测到红包消息, THE 模块 SHALL 自动打开红包
3. THE 模块 SHALL 支持敏感词过滤 (阻止包含敏感词的消息处理)
4. IF 防撤回开关关闭, THE 模块 SHALL 不拦截撤回事件

### REQ-9: 定时任务

**User Story:** AS 用户, I want 定时发送消息/公告, so that 自动化运营群组

#### Acceptance Criteria

1. THE 模块 SHALL 支持设置定时任务 (指定时间/重复周期)
2. THE 模块 SHALL 支持定时群发消息 (指定目标和内容)
3. THE 模块 SHALL 支持定时公告发布
4. THE 模块 SHALL 在发送期间保持 WakeLock 防止设备休眠

### REQ-10: AI 功能

**User Story:** AS 用户, I want AI 能力集成, so that 实现智能回复和内容生成

#### Acceptance Criteria

1. THE 模块 SHALL 支持 DeepSeek 对话 (智能回复/翻译/摘要/写作/问答)
2. THE 模块 SHALL 支持 @我自动回复 (群聊中被 @) 时自动调用 AI 回复
3. THE 模块 SHALL 支持 AI 图片生成 (火山方舟 API)
4. THE 模块 SHALL 支持 AI 视频生成 (火山方舟 API)
5. THE 模块 SHALL 支持语音转文字
6. IF API Key 未配置, THE 模块 SHALL 返回提示而非错误

### REQ-11: 免打扰

**User Story:** AS 用户, I want 设置免打扰时段, so that 休息时不被播报打扰

#### Acceptance Criteria

1. WHILE 当前时间在免打扰时段内, THE 模块 SHALL 暂停 TTS 播报
2. THE 模块 SHALL 支持设置免打扰开始/结束时间

### REQ-12: 数据统计

**User Story:** AS 群主, I want 查看群数据统计, so that 了解群活跃情况

#### Acceptance Criteria

1. THE 模块 SHALL 支持未读消息统计
2. THE 模块 SHALL 支持群活跃统计 (消息数/活跃成员)
3. THE 模块 SHALL 支持文件分类记录
4. THE 模块 SHALL 支持群投票 (创建/投票/查看结果)

### REQ-13: 关键词回复

**User Story:** AS 用户, I want 设置关键词自动回复, so that 自动应答常见问题

#### Acceptance Criteria

1. WHEN 消息包含已配置的关键词, THE 模块 SHALL 发送对应回复
2. THE 模块 SHALL 支持模糊匹配

### REQ-14: 语音中继

**User Story:** AS 用户, I want 自动播放语音消息, so that 收到语音自动播放

#### Acceptance Criteria

1. WHEN 收到语音消息, THE 模块 SHALL 自动播放
2. THE 模块 SHALL 支持播报发送者名称
3. THE 模块 SHALL 支持语音中继免打扰时段

### REQ-15: 总开关

**User Story:** AS 用户, I want 一个总开关, so that 需要时可一键关闭所有功能

#### Acceptance Criteria

1. IF 总开关关闭, THE 模块 SHALL 停止所有自动化功能 (Hook/播报/自动回复等)

---

## Non-Functional Requirements

### NFR-1: 性能

1. THE 模块 SHALL 在消息处理路径上的额外耗时不超过 50ms
2. THE 模块 SHALL 在数据库读取时使用缓存避免重复查询

### NFR-2: 稳定性

1. IF Hook 失败, THE 模块 SHALL 不影响微信正常使用
2. THE 模块 SHALL 在所有异常点使用 try-catch 保护

### NFR-3: 兼容性

1. THE 模块 SHALL 在 WeChat 3140 版本正常工作
2. THE 模块 SHALL 对微信类名变更具备基础容错能力

### NFR-4: 代码质量

1. 所有代码 SHALL 不引用原 LeShaoWeChat v2.1 源代码
2. 所有代码 SHALL 不依赖 WeKit API
3. 代码包名使用全新命名空间
