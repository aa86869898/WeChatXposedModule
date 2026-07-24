# WeChatPlus — 微信增强 Xposed 模块

## 37项功能完整实现

### 聊天增强 (7项)
| # | 功能 | 实现类 |
|---|------|--------|
| 2 | 防撤回 (XML+Proto+UI三道防线) | AntiRevoke.java |
| 4 | 正在输入提示增强 | TypingIndicator.java |
| 5 | 自动回复/关键词回复 | AutoReply.java |
| 6 | 定时发送 | ScheduledSend.java |
| 7 | 输入框增强 | ChatFooterEnhance.java |
| 8 | 聊天界面自定义 | ChatUICustom.java |
| 9 | 批量消息操作 | BatchMessage.java |

### 通讯录增强 (5项)
| # | 功能 | 实现类 |
|---|------|--------|
| 10 | 好友删除检测 | DeleteDetect.java |
| 11 | 通讯录批量导出 | ContactExport.java |
| 12 | 自动备注名 | AutoRemark.java |
| 13 | 联系人变更记录 | ContactChangeLog.java |
| 14 | 隐藏敏感字段 | HideContactFields.java |

### 会话列表增强 (3项)
| # | 功能 | 实现类 |
|---|------|--------|
| 15 | 消息预览隐私 | ConvPrivacy.java |
| 16 | 置顶增强 | StickyEnhance.java |
| 17 | 未读角标自定义 | UnreadBadge.java |

### 朋友圈增强 (8项)
| # | 功能 | 实现类 |
|---|------|--------|
| 19 | 去广告 | SnsFeatures.java |
| 22 | 转发 | SnsFeatures.java |
| 23 | 假点赞 | SnsFeatures.java |
| 24 | 时间修改 | SnsFeatures.java |
| 27 | 视频画质高清 | SnsFeatures.java |
| 29 | 长视频(>30s) | SnsFeatures.java |

### 其他功能 (14项)
| # | 功能 | 实现类 |
|---|------|--------|
| 31 | Tab自定义 | TabCustom.java |
| 32 | 登录设备监控 | LoginMonitor.java |
| 34 | 通话录音 | CallFeatures.java |
| 38 | 自动接听 | CallFeatures.java |
| 39 | 群成员变更日志 | GroupFeatures.java |
| 40 | 群公告回执 | GroupFeatures.java |
| 41 | 群批量操作 | GroupFeatures.java |
| 42 | 匿名发言 | GroupFeatures.java |
| 43 | 消息导出 | MsgExport.java |
| 44 | 聊天备份 | ChatBackup.java |
| 46 | 搜索增强 | SearchEnhance.java |
| 51 | 自动抢红包 | LuckyMoney.java |
| 52 | 红包提醒 | RedPacketAlert.java |
| 54 | 截图检测 | PrivacyFeatures.java |
| 55 | 剪贴板保护 | PrivacyFeatures.java |
| 56 | WebView隐私 | PrivacyFeatures.java |
| 58 | 摇一摇自定义 | ShakeCustom.java |
| 64 | 指纹锁聊天 | PrivacyFeatures.java |
| 65/66/67 | 通知增强 | NotifyCustom.java |

## 编译说明

1. 用 Android Studio 打开此目录
2. Sync Gradle
3. Build → Build APK
4. 安装 APK 后在 Xposed/LSPosed 中激活
5. 勾选微信 (com.tencent.mm)
6. 重启微信

## 微信内部参数说明

详细的微信类名、方法签名、字段说明请参考:
- WeChatClasses.java — 类名映射表
- 各 Feature 类中的注释 — 详细 Hook 点和参数说明

## 日志

所有日志通过 XposedBridge.log 输出，TAG = "WeChatPlus"
可使用 adb logcat -s WeChatPlus 查看
