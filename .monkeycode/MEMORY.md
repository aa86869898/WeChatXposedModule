# 用户指令记忆

本文件记录了用户的指令、偏好和教导，用于在未来的交互中提供参考。

## 格式

### 用户指令条目
用户指令条目应遵循以下格式：

[用户指令摘要]
- Date: [YYYY-MM-DD]
- Context: [提及的场景或时间]
- Instructions:
  - [用户教导或指示的内容，逐行描述]

### 项目知识条目
Agent 在任务执行过程中发现的条目应遵循以下格式：

[项目知识摘要]
- Date: [YYYY-MM-DD]
- Context: Agent 在执行 [具体任务描述] 时发现
- Category: [运维部署|构建方法|测试方法|排错调试|工作流协作|环境配置]
- Instructions:
  - [具体的知识点，逐行描述]

## 去重策略
- 添加新条目前，检查是否存在相似或相同的指令
- 若发现重复，跳过新条目或与已有条目合并
- 合并时，更新上下文或日期信息
- 这有助于避免冗余条目，保持记忆文件整洁

## 条目

### 微信助手 v3 开发硬性规范
- Date: 2026-07-21
- Context: 用户要求从零开发全新 Xposed 模块 com.leshao.v3，功能参照原乐少助手但不参考任何代码和 API
- Category: 工作流协作
- Instructions:
  - 所有 WCDB 数据库 Hook 逻辑必须等待微信 attachBaseContext 执行完成后初始化，禁止在 attachBaseContext 之前注册任何微信相关 Hook。ContextManager.isReady() 返回 false 时跳过所有 Hook 注册
  - SettingsEntryHook 仅允许在微信设置页搜索框下方新增跳转按钮，禁止往微信原生布局内嵌入任何功能面板或 UI 组件
  - 适配微信版本区间 8.0.72 ~ 8.0.76，基于 LSPosed Xposed API 102 纯净开发，禁止引入 WeKit/DexKit 或任何第三方依赖
  - 所有 Hook 动作、密钥捕获、数据库查询必须输出完整日志，包含时间戳 + 线程名 + 具体参数信息
  - proguard-rules.pro 必须保护所有反射相关类: `-keep class com.leshao.v3.hook.**`, `-keep class com.leshao.v3.db.**`, `-keep class com.leshao.v3.model.**`
  - 开发优先级严格按: 数据库密钥捕获 → 联系人/群聊列表查询 → 设置页跳转入口 → 独立 Activity 基础界面
  - 包名 com.leshao.v3，编译 Java 17 + Gradle 8.5 + Android SDK 35
   - 编译命令: `cd /workspace/LeShaoWeChat/LeShaoWeChatV3 && ./gradlew assembleDebug`
   - APK 输出路径: app/build/outputs/apk/debug/app-debug.apk
   - 签名: apksigner sign --ks debug.keystore --ks-key-alias androiddebugkey
   - 日志文件路径: /data/data/com.tencent.mm/files/leshao_v3/leshao_v3_log.txt
   - 日志过滤 TAG: LeShaoV3

### 自动推送 Git
- Date: 2026-07-23
- Context: 用户要求每次改完代码编译成功后自动提交并推送
- Category: 工作流协作
- Instructions:
  - 远程仓库: https://github.com/aa86869898/WeChatXposedModule.git
  - 推送分支: 本地 master → 远程 main (强制推送)
  - 每次代码修改编译成功后，自动执行 git add + commit + push
  - APK 已解除 gitignore (!**/build/outputs/apk/debug/*.apk)，会随源码一起推送
  - 推送命令: `git push https://<token>@github.com/aa86869898/WeChatXposedModule.git master:main --force`
