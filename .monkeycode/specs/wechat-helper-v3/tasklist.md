# 微信助手 v3 - Phase 1 实施计划

**硬性约束（每条必须满足）**
1. UI: 仅 Hook 微信设置页搜索框下方添加按钮，仅做跳转，**禁止嵌入原生布局**；使用 `post/postDelayed` + 重试机制解决点击不稳定
2. Hook 时序: 所有 WCDB/核心 Hook **必须等待 attachBaseContext 完成后再注册**，ContextManager.isReady() 为 false 时跳过所有 Hook 注册
3. 版本: 微信 8.0.72~8.0.76，LSPosed API 102，**禁止引入任何 WeKit 依赖**
4. 实现顺序: 数据库密钥捕获 → 联系人查询 → 设置页入口 → 基础 Activity

- [ ] 1. 设置项目结构和构建配置
  - 创建 Gradle 项目，包名 `com.leshao.v3`
  - 配置 compileSdk=35, minSdk=24, Xposed API 102 compileOnly，禁止添加 WeKit/DexKit 等任何第三方依赖
  - 编写 R8 混淆规则保护反射类: `-keep class com.leshao.v3.hook.** { *; }`, `-keep class com.leshao.v3.db.** { *; }`, `-keep class com.leshao.v3.model.** { *; }`
  - 创建 `app/src/main/assets/xposed_init` 声明入口类 `com.leshao.v3.MainHook`
  - 创建 AndroidManifest.xml 含 SettingsActivity 声明
  - 编写 `build.gradle.kts` 含 debug.keystore 签名配置
  - **参照约束**: 版本适配 (#3), 混淆保护 (#5)

- [ ] 2. 实现日志和上下文管理基础设施
  - [ ] 2.1 实现 `LogWriter` 文件日志工具
    - 日志写入 `/data/data/com.tencent.mm/files/leshao_v3/leshao_v3_log.txt`
    - 512KB 自动轮转，同时输出 XposedBridge.log
    - 每条日志带时间戳 + 线程名 + TAG
    - 日志输出: "LogWriter init OK, path=xxx"
    - **参照约束**: 日志完备性 (#5)

  - [ ] 2.2 实现 `ContextManager` 全局上下文管理器
    - 持有 ClassLoader / ApplicationContext / APK 路径 (sourceDir)
    - 提供 `isReady()` 状态标记，初始为 false，attachBaseContext 完成置 true
    - 提供 `waitForReady(long timeoutMs)` 阻塞等待方法供后续模块调用
    - 提供 SharedPreferences 读取封装 `getPrefs()`
    - 日志输出: "ContextManager: attachBaseContext done, apk=xxx"
    - **参照约束**: Hook 时序 (#2)

- [ ] 3. 检查点 - 确保项目结构可通过 gradle assembleRelease 编译，无任何 WeKit 依赖引用

- [ ] 4. 实现 WCDB 密钥捕获与数据库连接（P1最高优先级）
  - [ ] 4.1 实现 `MainHook` IXposedHookLoadPackage 入口
    - 过滤包名 `com.tencent.mm`，非微信进程直接 return
    - 读取版本号: `lpparam.appInfo.versionCode` + `versionName`，日志输出版本信息
    - 保存 `classLoader = lpparam.classLoader` 和 `apkPath = lpparam.appInfo.sourceDir` 到 ContextManager
    - Hook `Application.attachBaseContext`: afterHookedMethod 中调用 `ContextManager.markReady()`
    - 禁止在 attachBaseContext 之前注册任何微信相关 Hook
    - **参照约束**: Hook 时序 (#2)

  - [ ] 4.2 实现 `DatabaseProvider` WCDB Hook 与密钥捕获
    - 在 ContextManager.isReady() 返回 true 后才注册 Hook
    - 使用 DexFile(apkPath) 枚举 `com.tencent.wcdb.database.*` 包下类，定位 SQLiteDatabase
    - Hook `SQLiteDatabase.openDatabase(String path, byte[] password, ...)` 或等价的静态方法
    - 拦截参数: 记录 DB 文件路径 + 密钥字节数组
    - 通过 path 判断: 包含 `EnMicroMsg` 则确认为目标库
    - 保存密钥到 `DatabaseProvider.sDbPassword` 静态字段
    - 使用 `SQLiteDatabase.openDatabase(path, password, ...)` 二次打开独立连接
    - 保存连接实例到 `DatabaseProvider.sDatabase` 静态字段
    - 日志输出: "DB Hook activated, EnMicroMsg found: path=xxx keyLen=xxx" 或 "DB open FAILED: reason"
    - **参照约束**: 优先级顺序 (#4), 日志完备性 (#5), 版本适配 (#3)

  - [ ] 4.3 实现 `ContactRepository` 联系人数据查询
    - 依赖 DatabaseProvider.sDatabase 非空才执行查询
    - 实现 `queryAll(): List<Contact>`: `SELECT username, alias AS customWxId, conRemark AS remarkName, nickname, type FROM rcontact`
    - 实现 `queryFriends(): List<Contact>`: 过滤 `(type & 1) != 0 AND username NOT LIKE '%@chatroom'`
    - 实现 `queryGroups(): List<Contact>`: 过滤 `username LIKE '%@chatroom'`
    - 过滤 `filehelper` / `weixin` / `gh_*` / `@lbsroom` / `@openim` 系统账号
    - 结果映射到 Contact 模型: wxid=username, nickname, remarkName=conRemark, alias, type
    - 日志输出: "ContactRepository: friends=XX groups=XX total=XX"
    - **参照约束**: 优先级顺序 (#4), 日志完备性 (#5)

- [ ] 5. 检查点 - 在微信 3140 上验证: DB Hook activated + EnMicroMsg 密钥捕获 + 联系人/群聊数量查询成功

- [ ] 6. 实现微信设置页跳转入口
  - [ ] 6.1 实现 `SettingsEntryHook` 设置页按钮
    - 等待 ContextManager.isReady() 为 true 后注册 Hook
    - Hook 微信设置 Activity: `com.tencent.mm.ui.setting.SettingsUI`（或等效 setting 页面类），在 `onCreate` afterHookedMethod 中注入
    - 在设置页布局中找到搜索框容器（通过 id `actionbar_title` 或内容描述 `搜索` 定位）
    - 在搜索框下方使用 `addView` 动态添加 Button，文本"乐少助手"，不修改微信原生布局文件
    - 使用 `post/postDelayed` 延迟注入确保布局渲染完成，添加重试机制（最多3次，间隔500ms）
    - Button OnClickListener: `context.startActivity(new Intent(context, SettingsActivity.class))`
    - 日志输出: "SettingsEntryHook: button injected" 或 "SettingsEntryHook: FAILED after 3 retries"
    - **参照约束**: UI 规则 (#1), 日志完备性 (#5)

- [ ] 7. 实现独立 Activity 基础界面
  - [ ] 7.1 实现 `SettingsActivity` 基础壳
    - `Theme.AppCompat.Light.NoActionBar` 独立样式，完全独立于微信 UI
    - 使用 ViewPager2 + FragmentStateAdapter 架构
    - 底部 `TabLayout` 标签: 联系人 / 设置
    - 日志输出: "SettingsActivity: onCreate"
    - **参照约束**: UI 规则 (#1), 优先级顺序 (#4)

  - [ ] 7.2 实现 `ContactPickerFragment` 联系人选择器
    - `RecyclerView` + `LinearLayoutManager` 展示联系人列表
    - 顶部搜索框 `EditText` + `TextWatcher` 实时过滤
    - `TabLayout` 切换群聊/好友
    - 列表项布局: 头像圆形占位 + 昵称(大) + 备注(小)
    - 支持多选: `CheckBox` + `SparseBooleanArray` 记录选中状态
    - 底部操作栏: 全选 / 反选 / 确认(返回选中 wxid 列表)
    - 点击列表项切换选中状态
    - 日志输出: "ContactPickerFragment: loaded XX items"
    - **参照约束**: UI 规则 (#1)

- [ ] 8. 实现数据模型
  - [ ] 8.1 创建 `Contact` 数据模型
    - 字段: wxid(String), nickname(String), remarkName(String), alias(String), type(int)
    - 计算属性: `isGroup()` = wxid.endsWith("@chatroom")
    - 计算属性: `displayName()` = remarkName 非空取 remarkName, 否则取 nickname
    - 实现 `equals/hashCode` 基于 wxid

  - [ ] 8.2 创建 `WeChatMessage` 消息数据模型
    - 字段: talker, senderWxid, content, type(int), createTime(long)
    - 消息类型常量: TEXT=1, IMAGE=2, VOICE=4, VIDEO=5, REDPACKET=6, TRANSFER=7, CARD=8, FILE=9, LOCATION=10
    - 计算属性: `isGroup()` = talker.endsWith("@chatroom")
    - 工厂方法: `fromReflectedObject(Object msgObj)` 通过反射提取字段

  - [ ] 8.3 创建 `ModuleConfig` 配置模型
    - 字段: masterSwitch(bool), announceWhitelist(Set\<String\>), announceTypeMask(int)
    - 实现 `load(SharedPreferences)` 和 `save(SharedPreferences)` 方法
    - 提供默认值常量 `Defaults.MASTER_SWITCH = true`

- [ ] 9. 最终检查点 - Phase 1 全部功能
  - `gradle assembleRelease` 编译成功，R8 混淆规则生效
  - APK 安装到 LSPosed 框架，勾选微信
  - 微信冷启动，adb logcat 过滤 `LeShaoV3` 验证:
    - `LogWriter init OK`
    - `ContextManager: attachBaseContext done`
    - `DB Hook activated`
    - `ContactRepository: friends=XX groups=XX`
    - `SettingsEntryHook: button injected`
    - 点击"乐少助手"按钮跳转到独立 SettingsActivity
    - 联系人列表正确显示名称和数量
