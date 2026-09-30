# Requirements Document

## Introduction

本特性在既有「点歌」自动化（`DianGeService`）基础上新增三项能力：

1. 点歌受理时，通过微信原生引用消息（引用发起人的点歌消息）回复提示文案（默认「正在点歌…」）。
2. 最终发送的音频语音消息保持为普通语音消息，不携带引用关系。
3. 多个点歌请求进入串行队列，按接收顺序逐个处理，一个完成后再处理下一个。

原生引用发送复用 AI 助手现有能力 `WeChatMessenger.sendQuoteAndAt(...)`（`app/src/main/java/com/leshao/ai/hook/wechat/WeChatMessenger.java:116`），其调用范式见 `TriggerEngine`（`.../TriggerEngine.java:210`、`:285`）。

## Glossary

- **点歌指令**：白名单会话中命中「点歌别名前缀」且带非空歌名的文本消息。
- **受理提示**：系统在开始处理点歌任务前发送的提示消息，默认文案「正在点歌…」。
- **原生引用消息**：微信自带的引用气泡消息，展示被引用原文并由 `sendQuoteAndAt` 构造。
- **点歌队列**：保存待处理点歌任务的先进先出（FIFO）队列。
- **点歌任务**：一次点歌请求的完整处理单元（搜索 → 取流下载 → 解码 → 编码 → 发送语音）。
- **DianGeService**：点歌服务实现 `app/src/main/java/com/leshao/v3/music/DianGeService.java`。

## Requirements

### Requirement 1

**User Story:** AS 发起点歌的客户, I want 收到一条引用我点歌消息的「正在点歌…」提示, so that 我知道系统已受理且能对应到我的请求。

#### Acceptance Criteria

1. WHEN 白名单会话收到点歌指令且点歌功能已启用, THE 系统 SHALL 通过微信原生引用消息回复该指令，引用被引用的原始点歌消息，内容为受理提示文案。
2. IF 原生引用消息发送失败, THE 系统 SHALL 回退为普通文本消息发送受理提示。
3. WHILE 受理提示开关为关闭状态, THE 系统 SHALL 不发送受理提示，且直接进入点歌任务处理。
4. WHEN 受理提示文案配置存在 `{song}` 占位符, THE 系统 SHALL 用命中的歌曲标题替换该占位符。
5. WHERE 该会话在受理时队列中已有等待任务, THE 系统 SHALL 在受理提示中追加当前排队位次。
6. WHERE 会话为私聊, THE 系统 SHALL 同样尝试原生引用受理提示，失败时回退文本。

### Requirement 2

**User Story:** AS 发起点歌的客户, I want 最终收到的是普通音频语音消息, so that 收听体验不被引用气泡干扰。

#### Acceptance Criteria

1. WHEN 点歌任务完成音频编码, THE 系统 SHALL 以普通语音消息发送音频数据，且不建立任何引用关系。
2. WHEN 音频语音消息发送失败, THE 系统 SHALL 回复一条文本错误提示，且该文本不引用原消息。

### Requirement 3

**User Story:** AS 群内多位点歌的客户, I want 各自的点歌请求被依次处理, so that 音频不会互相覆盖或错发。

#### Acceptance Criteria

1. WHEN 收到新的点歌请求且**该会话**当前存在正在处理的点歌任务, THE 系统 SHALL 将该请求加入该会话的点歌队列等待处理。
2. WHILE 点歌队列中存在待处理任务, THE 系统 SHALL 对该会话一次仅处理一个点歌任务，并在该任务结束后开始处理下一个任务。
3. WHEN 一个点歌任务处理完成（无论成功或失败）, THE 系统 SHALL 按入队顺序开始处理该会话队首的下一个任务。
4. IF 该会话点歌队列长度达到配置上限, THE 系统 SHALL 丢弃新请求并回复队列已满提示。
5. WHEN 同一会话在冷却窗口内重复提交相同歌名, THE 系统 SHALL 视为重复请求并忽略。
6. WHILE 不同会话各自存在待处理任务, THE 系统 SHALL 允许它们各自串行推进，互不阻塞。

### Requirement 4

**User Story:** AS 系统维护者, I want 队列与提示行为可观测、可配置, so that 能排查异常并调整体验。

#### Acceptance Criteria

1. WHEN 点歌请求入队, THE 系统 SHALL 记录包含会话、歌名、队列长度的日志。
2. WHEN 原生引用发送或点歌任务处理发生异常, THE 系统 SHALL 记录包含异常摘要的日志。
3. WHERE 配置项可用, THE 系统 SHALL 支持配置受理提示文案、受理提示开关与队列长度上限。
