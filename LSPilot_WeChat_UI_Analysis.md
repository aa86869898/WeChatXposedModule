# 微信全局界面完整分析报告 - LSPilot 主题模块

> 生成时间: $(date +"%Y-%m-%d %H:%M:%S")
> APK: com.tencent.mm
> 适用: Xposed 模块 / BSH 插件主题开发

---

# 目录

1. [核心继承层级](#一核心继承层级)
2. [主框架层](#二主框架层)
3. [聊天模块](#三聊天模块)
4. [会话列表模块](#四会话列表模块)
5. [联系人模块](#五联系人模块)
6. [发现页模块](#六发现页模块)
7. [朋友圈模块](#七朋友圈模块)
8. [视频号模块](#八视频号模块)
9. [WebView 模块](#九webview-模块)
10. [设置模块](#十设置模块)
11. [钱包/支付模块](#十一钱包支付模块)
12. [公众号/服务号模块](#十二公众号服务号模块)
13. [群聊管理模块](#十三群聊管理模块)
14. [收藏/相册模块](#十四收藏相册模块)
15. [登录/注册模块](#十五登录注册模块)
16. [状态模块](#十六状态模块)
17. [VoIP/通话模块](#十七voip通话模块)
18. [卡券模块](#十八卡券模块)
19. [小程序模块](#十九小程序模块)
20. [底层 UI 组件详解](#二十底层-ui-组件详解)
21. [主题 Hook 架构方案](#二十全局主题-hook-架构方案)

---

# 一、核心继承层级

## 1.1 Activity 完整层级链

```
android.app.Activity
├── HellActivity                              ← 朋友圈时间线(SnsTimeLineUI)老路径
│
└── FragmentActivity
    └── AppCompatActivity
        └── UIComponentActivity               ← UI 组件基类
            └── GloUIComponentActivity
                └── MMFragmentActivity         ★ Fragment Activity 总基类
                    ├── MMActivity             ★ 所有微信 Activity 必经之路
                    │   ├── VASActivityJava    ★★★ VAS 主题引擎 Java 层
                    │   │   └── VASActivity    ★★★ VAS 主题引擎 Kotlin 层
                    │   │       └── BaseMvvmActivity
                    │   │           ├── MMPreference          → 联系人详情/设置
                    │   │           │   ├── DrawStatusBarPreference → ContactProfileUI
                    │   │           │   ├── ContactInfoUI
                    │   │           │   ├── SingleChatInfoUI
                    │   │           │   ├── BaseSettingUI     → 新版设置基类
                    │   │           │   │   ├── MainSettingsUI
                    │   │           │   │   └── CommonSettingsUI
                    │   │           │   └── FindMoreFriendsUI (Fragment)
                    │   │           │
                    │   │           ├── MMSecDataActivity     → WebViewUI, SnsUploadUI
                    │   │           ├── MMSecDataFragmentActivity → LauncherUI, ChattingUI
                    │   │           └── MMFinderUI            → 所有 Finder 界面
                    │   │               └── FinderProfileUI
                    │   │
                    │   └── SnsUserUI / SnsCommentUI (MMActivity)
                    │
                    ├── BaseConversationUI    → 会话列表基类
                    │   ├── BizConversationUI
                    │   ├── EnterpriseConversationUI
                    │   ├── ServiceNotifyConversationUI
                    │   └── AppBrandServiceConversationUI
                    │
                    └── VASLauncher           → LauncherUI 的 VAS 层
                        └── LauncherUI        ★ 微信主界面
```

## 1.2 Fragment 层级链

```
androidx.fragment.app.Fragment
└── HellAndroidXFragment
    └── FragmentActivitySupport
        └── MMFragment                       ★ 所有 Fragment 基类
            ├── BaseChattingUIFragment       → 聊天 Fragment 基类
            │   └── ChattingUIFragment
            ├── MMPreferenceFragment
            │   └── AbstractTabChildPreference
            │       └── FindMoreFriendsUI    → 发现页
            └── BaseConversationUI$BaseConversationFmUI
```

# 二、主框架层

## 2.1 主界面

| 类名 | 完整路径 | 说明 |
|------|----------|------|
| **LauncherUI** | `com.tencent.mm.ui.LauncherUI` | ★ 微信主界面，4 Tab 容器 |
| **LauncherUIBottomTabView** | `com.tencent.mm.ui.LauncherUIBottomTabView` | ★★ 底部导航栏 View |
| **VASLauncher** | `com.tencent.mm.ui.vas.launcher.VASLauncher` | VAS 主题启动层 |
| **AbstractTabChildActivity** | `com.tencent.mm.ui.AbstractTabChildActivity` | Tab 子页基类 |
| **AbstractTabChildPreference** | `com.tencent.mm.ui.AbstractTabChildPreference` | Tab Preference 基类 |
| **WeChatSplashActivity** | `com.tencent.mm.app.WeChatSplashActivity` | 启动闪屏 |
| **WeChatSplashFallbackActivity** | `com.tencent.mm.app.WeChatSplashFallbackActivity` | 备用闪屏 |
| **EmptyActivity** | `com.tencent.mm.ui.EmptyActivity` | 空占位 |

## 2.2 核心基类

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.ui.MMActivity` | ★ 所有 Activity 基类 |
| `com.tencent.mm.ui.MMFragmentActivity` | ★ Fragment Activity 基类 |
| `com.tencent.mm.ui.MMFragment` | ★ 所有 Fragment 基类 |
| `com.tencent.mm.ui.vas.VASActivity` | ★★★ VAS 主题引擎 (Kotlin) |
| `com.tencent.mm.ui.vas.VASActivityJava` | ★★★ VAS 主题引擎 (Java) |
| `com.tencent.mm.plugin.mvvmbase.BaseMvvmActivity` | MVVM 基类 |
| `com.tencent.mm.plugin.mvvmbase.BaseMvvmFragmentActivity` | MVVM Fragment 基类 |
| `com.tencent.mm.plugin.secdata.ui.MMSecDataActivity` | 安全数据 Activity |
| `com.tencent.mm.plugin.secdata.ui.MMSecDataFragmentActivity` | 安全数据 FragmentActivity |

# 三、聊天模块

## 3.1 聊天主界面

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.ui.chatting.ChattingUI` | ★★ 聊天主 Activity |
| `com.tencent.mm.ui.chatting.ChattingUIFragment` | ★ 聊天 UI Fragment |
| `com.tencent.mm.ui.chatting.BaseChattingUIFragment` | ★ 聊天 Fragment 基类（主题Hook重点） |
| `com.tencent.mm.ui.chatting.ChattingUIProxy` | 聊天 UI 代理 |
| `com.tencent.mm.ui.IChattingUIProxy` | 聊天 UI 接口 |

## 3.2 聊天输入区（★ 重点 ★）

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.pluginsdk.ui.chat.ChatFooter` | ★★★ 聊天底部输入栏主类 |
| `com.tencent.mm.pluginsdk.ui.chat.ChatFooterBottom` | ★★ +号按钮和语音按钮容器 |
| `com.tencent.mm.pluginsdk.ui.ChatFooterPanel` | ★★ +号展开面板（照片/文件/位置等） |
| `com.tencent.mm.ui.chatting.ChatFooterCustom` | ★ 自定义聊天底部 |
| `com.tencent.mm.ui.chatting.ChattingFooterMoreBtnBar` | ★ +号面板按钮栏 |
| `com.tencent.mm.pluginsdk.ui.VoiceInputFooter` | ★ 语音输入底部栏 |
| `com.tencent.mm.pluginsdk.ui.VoiceInputLayout` | ★ 语音输入布局 |
| `com.tencent.mm.pluginsdk.ui.chat.VoiceInputPanel` | 语音输入面板 |
| `com.tencent.mm.pluginsdk.ui.VoiceInputScrollView` | 语音输入滚动 |
| `com.tencent.mm.pluginsdk.ui.ChatFooterPanel$RecommendView` | 推荐 View |
| `com.tencent.mm.ui.chatting.ChattingImageBGView` | 聊天背景 View ★ |

## 3.3 聊天变体 (16个子类)

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.ui.chatting.variants.ChattingMainUI` | 主聊天变体 |
| `com.tencent.mm.ui.chatting.variants.FinderChattingUI` | 视频号聊天 |
| `com.tencent.mm.ui.chatting.variants.AppBrandChattingUI` | 小程序聊天 |
| `com.tencent.mm.ui.chatting.variants.AppBrandChattingUI00` ~ `04` | 小程序聊天分片(0-4) |
| `com.tencent.mm.ui.chatting.variants.AppBrandChattingUI1` ~ `4` | 小程序聊天分片(1-4) |
| `com.tencent.mm.ui.chatting.variants.WXCustomEntryChattingUI` | 自定义入口 |
| `com.tencent.mm.ui.chatting.variants.TopStoryChattingUI` | 看一看聊天 |
| `com.tencent.mm.ui.chatting.variants.CastChattingUI` | 投屏聊天 |
| `com.tencent.mm.ui.chatting.variants.LiteAppTaskChattingUI` | LiteApp 聊天 |
| `com.tencent.mm.ui.chatting.variants.VoipChattingUI` | 语音通话聊天 |
| `com.tencent.mm.ui.chatting.variants.FinderLiveChattingUI` | 直播聊天 |
| `com.tencent.mm.ui.chatting.variants.GameChatroomChattingUI` | 游戏群聊天 |
| `com.tencent.mm.ui.chatting.variants.TaskRedirectChattingUI` | 任务跳转 |
| `com.tencent.mm.ui.chatting.variants.WidgetEntryChattingUI` | 小组件入口 |
| `com.tencent.mm.ui.chatting.variants.MusicEntryChattingUI` | 音乐入口 |
| `com.tencent.mm.ui.chatting.variants.SdkEntryChattingUI` | SDK 入口 |
| `com.tencent.mm.ui.chatting.BizHalfScreenChattingUI` | 半屏公众号聊天 |

## 3.4 聊天辅助

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.ui.chatting.SendImgProxyUI` | 图片发送代理 |
| `com.tencent.mm.ui.chatting.ImageDownloadUI` | 图片下载 |
| `com.tencent.mm.ui.chatting.AppAttachNewDownloadUI` | 附件下载 |
| `com.tencent.mm.ui.chatting.ChatMoreSelectUI` | 更多选择 |
| `com.tencent.mm.ui.chatting.TextPreviewUI` | 文字预览 |
| `com.tencent.mm.ui.chatting.gallery.ImageGalleryUI` | ★★ 聊天图片浏览器 |
| `com.tencent.mm.ui.chatting.gallery.MediaHistoryGalleryUI` | 媒体历史 |
| `com.tencent.mm.ui.chatting.history.MsgHistoryGalleryUI` | 消息历史 |
| `com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout` | 聊天布局 |

## 3.5 转发模块

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.ui.transmit.SelectConversationUI` | 选择会话转发 |
| `com.tencent.mm.ui.transmit.MsgRetransmitUI` | 消息重发 |
| `com.tencent.mm.ui.transmit.RetransmitPreviewUI` | 转发预览 |
| `com.tencent.mm.ui.transmit.ShareImageSelectorUI` | 分享图片选择 |
| `com.tencent.mm.ui.transmit.TaskRedirectUI` | 任务重定向 |

# 四、会话列表模块

## 4.1 核心类

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.ui.conversation.BaseConversationUI` | ★★ 会话列表基类 |
| `com.tencent.mm.ui.conversation.BaseConversationUI$BaseConversationFmUI` | 会话列表 Fragment |
| `com.tencent.mm.ui.conversation.MainUI` | 会话主 UI |
| `com.tencent.mm.ui.conversation.MainUIView` | ★ 会话主 View |
| `com.tencent.mm.ui.conversation.ConversationListView` | ★ 会话列表 View |
| `com.tencent.mm.ui.conversation.ConversationAdapter` | ★ 会话列表 Adapter |
| `com.tencent.mm.ui.conversation.adapter.MvvmConversationAdapter` | MVVM Adapter |
| `com.tencent.mm.ui.conversation.ConversationFolderItemView` | ★ 折叠会话项 View |

## 4.2 会话类型子类

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.ui.conversation.BizConversationUI` | 公众号会话列表 |
| `com.tencent.mm.ui.conversation.ServiceNotifyConversationUI` | 服务通知列表 |
| `com.tencent.mm.ui.conversation.EnterpriseConversationUI` | 企业会话列表 |
| `com.tencent.mm.ui.conversation.AppBrandServiceConversationUI` | 小程序客服 |
| `com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI` | 盒子客服 |
| `com.tencent.mm.ui.conversation.OpenImKefuServiceConversationUI` | 开放客服 |
| `com.tencent.mm.ui.bizchat.BizChatConversationUI` | 企业号会话 |
| `com.tencent.mm.plugin.finder.ui.FinderConversationUI` | 视频号会话 |
| `com.tencent.mm.plugin.gamelife.ui.GameLifeConversationUI` | 游戏人生会话 |

# 五、联系人模块

## 5.1 通讯录

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.ui.contact.AddressUI` | ★ 通讯录主界面 |
| `com.tencent.mm.ui.contact.SelectContactUI` | 选择联系人 |
| `com.tencent.mm.ui.contact.OpenIMSelectContactUI` | 开放IM选人 |
| `com.tencent.mm.ui.contact.SelectSpecialContactUI` | 特殊选人 |
| `com.tencent.mm.ui.contact.SelectLabelContactUI` | 标签选人 |
| `com.tencent.mm.ui.contact.GroupCardSelectUI` | 群名片选择 |
| `com.tencent.mm.ui.contact.SendContactCardUI` | 发送名片 |
| `com.tencent.mm.ui.contact.OpenIMAddressUI` | 开放IM通讯录 |
| `com.tencent.mm.ui.contact.SnsSelectConversationUI` | SNS选择会话 |
| `com.tencent.mm.ui.contact.ModRemarkNameUI` | 修改备注名 |
| `com.tencent.mm.ui.contact.ContactRemarkInfoModUI` | 备注信息修改 |
| `com.tencent.mm.ui.contact.ContactRemarkImagePreviewUI` | 备注图片预览 |

## 5.2 联系人详情 (Profile)

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.plugin.profile.ui.ContactInfoUI` | ★★★ 联系人详情页 |
| `com.tencent.mm.plugin.profile.ui.DialogContactInfoUI` | 弹窗式联系人详情 |
| `com.tencent.mm.plugin.profile.ui.ContactProfileUI` | ★★ 联系人Profile (朋友圈入口) |
| `com.tencent.mm.ui.SingleChatInfoUI` | ★ 单聊信息 |
| `com.tencent.mm.plugin.profile.NewContactWidgetNormal` | 新联系人 Widget |
| `com.tencent.mm.plugin.profile.ui.tab.ContactWidgetTabBizInfo` | ★ Tab 页公众号信息 |
| `com.tencent.mm.plugin.profile.ui.tab.ContactWidgetTabBizHeaderView` | ★ Tab 页头部 |
| `com.tencent.mm.plugin.profile.ui.tab.ContactWidgetActionLiveBar` | Tab 页直播条 |
| `com.tencent.mm.plugin.profile.ui.tab.ContactWidgetTabBizHeaderController` | Tab 页头部控制器 |

# 六、发现页模块

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.ui.FindMoreFriendsUI` | ★★ 发现页主体 (Fragment) |
| `com.tencent.mm.ui.AbstractTabChildPreference` | 发现页基类 |

# 七、朋友圈模块 ★★★

## 7.1 核心界面

| 类名 | 继承 | 说明 |
|------|------|------|
| `com.tencent.mm.plugin.sns.ui.SnsTimeLineUI` | `HellActivity` | ★★★ 朋友圈时间线主界面 |
| `com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI` | - | ★★ 新版朋友圈时间线 |
| `com.tencent.mm.plugin.sns.ui.SnsUserUI` | `MMActivity` | ★★ 个人朋友圈主页 |
| `com.tencent.mm.plugin.sns.ui.SnsUploadUI` | `MMSecDataActivity` | ★★ 发布朋友圈 |
| `com.tencent.mm.plugin.sns.ui.SnsCommentUI` | `MMActivity` | ★ 评论详情 |
| `com.tencent.mm.plugin.sns.ui.SnsCommentDetailUI` | - | ★ 评论详情页 |
| `com.tencent.mm.plugin.sns.ui.SnsGalleryUI` | - | ★ 朋友圈图片画廊 |
| `com.tencent.mm.plugin.sns.ui.SnsBaseGalleryUI` | - | 画廊基类 |
| `com.tencent.mm.plugin.sns.ui.SnsMsgUI` | - | 朋友圈消息通知 |
| `com.tencent.mm.plugin.sns.ui.SnsMsgUIWithAll` | - | 全部朋友圈消息 |
| `com.tencent.mm.plugin.sns.ui.SnsMsgUIWithRelevance` | - | 相关朋友圈消息 |
| `com.tencent.mm.plugin.sns.ui.SnsBrowseUI` | - | 朋友圈浏览 |
| `com.tencent.mm.plugin.sns.ui.SnsBlackDetailUI` | - | 不让TA看详情 |
| `com.tencent.mm.plugin.sns.ui.SnsAdNativeLandingPagesUI` | - | 广告落地页 |
| `com.tencent.mm.plugin.sns.ui.SnsAdNativeLandingPagesPreviewUI` | - | 广告预览 |
| `com.tencent.mm.plugin.sns.ui.SettingSnsBackgroundUI` | - | 朋友圈背景设置 |
| `com.tencent.mm.plugin.sns.ui.SnsSettingUI` | - | 朋友圈设置 |
| `com.tencent.mm.plugin.sns.ui.message.SnsMsgSettingUI` | - | 朋友圈消息设置 |
| `com.tencent.mm.plugin.sns.ui.SnsChatRoomMemberUI` | - | 朋友圈群成员 |

## 7.2 SNS Timeline Adapter & 卡片层级

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.plugin.sns.ui.SnsTimeLineBaseAdapter` | ★★ 朋友圈列表 Adapter 基类 |
| `com.tencent.mm.plugin.sns.ui.PassThroughRecyclerView` | 朋友圈 RecyclerView |

## 7.3 SNS 卡片内部 View 组件 ★★★

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.plugin.sns.ui.SnsHeader` | ★★ 朋友圈卡片头部（头像+昵称） |
| `com.tencent.mm.plugin.sns.ui.SnsCollapsibleTextView` | ★★ 可折叠文字内容 |
| `com.tencent.mm.plugin.sns.ui.PhotosContent` | ★★ 朋友圈图片九宫格 |
| `com.tencent.mm.plugin.sns.ui.SnsTimelineImgBottomBar` | ★★ 朋友圈底部栏（赞/评论） |
| `com.tencent.mm.plugin.sns.ui.SnsAlbumImgBottomBar` | 相册底部栏 |
| `com.tencent.mm.plugin.sns.ui.SnsBaseImgBottomBar` | 底部栏基类 |
| `com.tencent.mm.plugin.sns.ui.RichTextImageView` | 富文本图片 |
| `com.tencent.mm.plugin.sns.ui.QTextView` | Q 文字 View |
| `com.tencent.mm.plugin.sns.ui.OnlineVideoView` | ★ 在线视频播放 View |
| `com.tencent.mm.plugin.sns.ui.LocationWidget` | 位置标签 |
| `com.tencent.mm.plugin.sns.ui.RangeWidget` | 可见范围 |
| `com.tencent.mm.plugin.sns.ui.SightWidget` | 小视频 Widget |
| `com.tencent.mm.plugin.sns.ui.AtContactWidget` | @联系人 |
| `com.tencent.mm.plugin.sns.ui.SightAtContactWidget` | 视频@联系人 |
| `com.tencent.mm.plugin.sns.ui.SightLocationWidget` | 视频位置 |
| `com.tencent.mm.plugin.sns.ui.SightRangeWidget` | 视频可见范围 |
| `com.tencent.mm.plugin.sns.ui.SnsUploadConfigView` | 发布配置 View |
| `com.tencent.mm.plugin.sns.ui.SnsWeappView` | 小程序卡片 |
| `com.tencent.mm.plugin.sns.ui.SnsEcsShareTailView` | 分享尾部 |
| `com.tencent.mm.plugin.sns.ui.TranslateCommentTextView` | 翻译评论 |
| `com.tencent.mm.plugin.sns.ui.ShowCommentImageView` | 评论图片 |
| `com.tencent.mm.plugin.sns.ui.ArtistHeader` | 艺术家头部 |
| `com.tencent.mm.plugin.sns.ui.ClassifyHeader` | 分类头部 |
| `com.tencent.mm.plugin.sns.ui.FlipView` | 翻转 View |
| `com.tencent.mm.plugin.sns.ui.LoadingMoreView` | 加载更多 |
| `com.tencent.mm.plugin.sns.ui.MaskLinearLayout` | 遮罩布局 |
| `com.tencent.mm.plugin.sns.ui.PreviewContactView` | 预览联系人 |

## 7.4 新版 SNS (Improve) 组件

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI` | ★ 新版时间线 |
| `com.tencent.mm.plugin.sns.ui.improve.item.header.ImproveItemHeader` | ★ 新版卡片头部 |
| `com.tencent.mm.plugin.sns.ui.improve.item.header.ImproveItemFooter` | ★ 新版卡片底部 |
| `com.tencent.mm.plugin.sns.ui.improve.view.ImproveFinderTierView` | ★ 新版视频号层 |
| `com.tencent.mm.plugin.sns.ui.improve.view.ImproveLoadingMoreView` | 新版加载更多 |
| `com.tencent.mm.plugin.sns.ui.improve.component.unread.ImproveUnreadTierView` | 新版未读层 |
| `com.tencent.mm.plugin.sns.ui.item.improve.recycle.TimelineCommentView` | ★ 新版评论 View |
| `com.tencent.mm.plugin.sns.ui.item.improve.view.ImproveRoundLinearLayout` | 新版圆角布局 |
| `com.tencent.mm.plugin.sns.ui.widget.SnsCardAdTagListView` | 广告标签列表 |
| `com.tencent.mm.plugin.sns.ui.widget.SnsPopover` | 弹窗 |

## 7.5 视频号嵌入朋友圈

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.plugin.finder.view.FinderSnsHeaderView` | ★ Finder 嵌入 SNS 头部 |
| `com.tencent.mm.plugin.finder.view.FinderSnsHeaderPresenter` | Finder SNS 头部控制器 |
| `com.tencent.mm.plugin.sns.ui.video.SnsTimelineVideoView` | SNS 视频 View |

# 八、视频号模块

## 8.1 核心基类

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.plugin.finder.ui.MMFinderUI` | ★★★ Finder 基类 |
| `com.tencent.mm.plugin.finder.ui.MMLiveFinderUI` | 直播 Finder 基类 |
| `com.tencent.mm.plugin.finder.feed.ui.FinderProfileUI` | ★★ 视频号主页 |
| `com.tencent.mm.plugin.finder.profile.FinderProfileUIFragment` | 视频号主页 Fragment |

## 8.2 Finder Feed 流 (约80+个)

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.plugin.finder.feed.ui.FinderFollowTimelineUI` | 关注流 |
| `com.tencent.mm.plugin.finder.feed.ui.NearbyUI` | 附近 |
| `com.tencent.mm.plugin.finder.feed.ui.NearbyAffinityUI` | 附近(Affinity) |
| `com.tencent.mm.plugin.finder.feed.ui.BizProfileTimelineUI` | 公众号 Timeline |
| `com.tencent.mm.plugin.finder.feed.ui.FinderSelfAggregationUI` | 个人聚合 |
| `com.tencent.mm.plugin.finder.feed.ui.FinderFollowAggregationUI` | 关注聚合 |
| `com.tencent.mm.plugin.finder.feed.ui.FinderMixCellUI` | 混合 Cell |
| `com.tencent.mm.plugin.finder.feed.ui.FinderPreviewAtTimelineUI` | @预览 |
| `com.tencent.mm.plugin.finder.feed.ui.FinderMusicTopicAboutVideoUI` | 音乐话题 |
| `com.tencent.mm.plugin.finder.feed.ui.FinderMusicTopicVideoFlowUI` | 音乐视频流 |
| `com.tencent.mm.plugin.finder.feed.ui.FinderTingProfileMusicListUI` | 听一听音乐列表 |
| `com.tencent.mm.plugin.finder.feed.ui.FinderTingProfileAlbumListUI` | 听一听专辑列表 |
| `com.tencent.mm.plugin.finder.feed.ui.FinderTingAudioCollectionUI` | 听一听合集 |
| `com.tencent.mm.plugin.finder.feed.ui.FinderTingProfileSongListUI` | 听一听歌曲列表 |

## 8.3 Finder 直播

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.plugin.finder.feed.ui.FinderLivePostUI` | 发起直播 |
| `com.tencent.mm.plugin.finder.feed.ui.FinderLiveAnchorSettingUI` | 主播设置 |
| `com.tencent.mm.plugin.finder.feed.ui.FinderLiveMoreOptionSettingUI` | 更多设置 |
| `com.tencent.mm.plugin.finder.feed.ui.FinderLiveMsgUI` | 直播消息 |
| `com.tencent.mm.plugin.finder.feed.ui.FinderLivePersonalCenterUI` | 直播个人中心 |
| `com.tencent.mm.plugin.finder.feed.ui.FinderLiveLotteryCreateUI` | 创建抽奖 |
| `com.tencent.mm.plugin.finder.feed.ui.FinderLiveGiftPkgUI` | 礼物包 |
| `com.tencent.mm.plugin.finder.feed.ui.FinderLiveWeCoinHotIncomeUI` | 热度收入 |
| `com.tencent.mm.plugin.finder.nearby.newlivesquare.FinderLiveSquareNewEntranceUI` | 直播广场 |

## 8.4 Finder 搜索

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.plugin.finder.search.FinderFeedSearchUI` | Feed 搜索 |
| `com.tencent.mm.plugin.finder.search.FinderContactSearchUI` | 联系人搜索 |
| `com.tencent.mm.plugin.finder.search.FinderMixSearchUI` | 混合搜索 |
| `com.tencent.mm.plugin.finder.search.FinderTopicSearchUI` | 话题搜索 |
| `com.tencent.mm.plugin.finder.search.FinderFansSearchUI` | 粉丝搜索 |
| `com.tencent.mm.plugin.finder.feed.ui.FinderHotSearchUI` | 热搜 |
| `com.tencent.mm.plugin.finder.feed.ui.FinderGameSearchUI` | 游戏搜索 |

## 8.5 Finder Fragment 子页面

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.plugin.finder.ui.fragment.FinderHomeTabFragment` | ★ 首页 Tab |
| `com.tencent.mm.plugin.finder.ui.fragment.FinderFollowTabFragment` | 关注 Tab |
| `com.tencent.mm.plugin.finder.ui.fragment.FinderFriendTabFragment` | 朋友 Tab |
| `com.tencent.mm.plugin.finder.ui.fragment.FinderLbsTabFragment` | 附近 Tab |
| `com.tencent.mm.plugin.finder.ui.fragment.FinderMachineTabFragment` | 机器推荐 Tab |
| `com.tencent.mm.plugin.finder.ui.fragment.FinderNativeDramaTabFragment` | 短剧 Tab |
| `com.tencent.mm.plugin.finder.activity.topic.fragment.FinderTopicTabFragment` | 话题 Tab |

# 九、WebView 模块

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.plugin.webview.ui.tools.WebViewUI` | ★★★ WebView 主类 |
| `com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI` | MM WebView |
| `com.tencent.mm.plugin.webview.ui.tools.CustomSchemeEntryWebViewUI` | 自定义 Scheme |
| `com.tencent.mm.plugin.webview.ui.tools.TransparentWebViewUI` | 透明 WebView |
| `com.tencent.mm.plugin.webview.ui.tools.SDKOAuthUI` | ★ SDK 授权 |
| `com.tencent.mm.plugin.webview.ui.tools.SDKOAuthFriendUI` | 好友授权 |
| `com.tencent.mm.plugin.webview.ui.tools.SDKOAuthWechatUI` | 微信授权 |
| `com.tencent.mm.plugin.webview.ui.tools.fts.FTSWebViewUI` | FTS 搜索 |
| `com.tencent.mm.plugin.webview.ui.tools.fts.MMFTSWebViewUI` | MM FTS |
| `com.tencent.mm.plugin.webview.ui.tools.fts.CircleToSearchWebViewUI` | 圈选搜索 |
| `com.tencent.mm.plugin.webview.ui.tools.fts.BaseSearchWebViewUI` | 搜索基类 |
| `com.tencent.mm.plugin.webview.ui.tools.fts.FTSSOSHomeWebViewUI` | SOS 主页 |
| `com.tencent.mm.plugin.webview.ui.tools.game.H5GameWebViewUI` | H5 游戏 |
| `com.tencent.mm.plugin.webview.stub.WebViewStubProxyUI` | Stub 代理 |

# 十、设置模块 ★★★

## 10.1 新版设置 (setting_new) - 当前生效

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI` | ★★★ 设置主页 |
| `com.tencent.mm.plugin.setting.ui.setting_new.CommonSettingsUI` | ★★ 通用设置 |
| `com.tencent.mm.plugin.setting.ui.setting_new.SearchSettingsUI` | 设置搜索 |
| `com.tencent.mm.plugin.setting.ui.setting_new.base.BaseSettingUI` | ★ 设置基类 |
| `com.tencent.mm.plugin.setting.ui.setting_new.base.BaseSettingPrefUI` | 设置Pref基类 |

## 10.2 设置分组项目 (setting_new/settings/)

| 分组类 | 说明 |
|------|------|
| `...settings.SettingGroupMain` | ★ 主设置组 |
| `...settings.SettingGroupAccountInfo` | ★ 账号信息组 |
| `...settings.SettingGroupPersonalInfo` | ★ 个人信息组 |
| `...settings.SettingGroupChatting` | ★ 聊天设置组 |
| `...settings.SettingGroupDisplay` | ★ 显示设置组 |
| `...settings.SettingGroupNotify` | ★ 通知设置组 |
| `...settings.SettingGroupPrivacyPermission` | ★ 隐私权限组 |
| `...settings.SettingGroupFriendPrivacy` | ★ 朋友权限组 |
| `...settings.SettingGroupVideo` | ★ 视频设置组 |
| `...settings.SettingGroupMore` | ★ 更多设置组 |
| `...settings.SettingGroupOther` | ★ 其他设置组 |
| `...settings.SettingGroupHelpItem` | 帮助项 |
| `...settings.SettingGroupAboutItem` | 关于项 |
| `...settings.SettingGroupCleanItem` | 清理项 |
| `...settings.SettingGroupChatRecordManageItem` | 聊天记录管理 |
| `...settings.SettingAdditionHeaderSearch` | 搜索头 |
| `...settings.SettingAdditionBottom` | 底部 |
| `...settings.SettingButtonExitAccount` | 退出账号按钮 |
| `...settings.SettingButtonSwitchAccount` | 切换账号按钮 |
| `...settings.SettingButtonLogoutAccount` | 注销账号按钮 |
| `...settings.display.SettingGroupDarkModeItem` | ★ 深色模式项 |

## 10.3 旧版设置 (setting) - 仍在用的子页面

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.plugin.setting.ui.setting.SettingsAboutMicroMsgUI` | ★ 关于微信 |
| `com.tencent.mm.plugin.setting.ui.setting.SettingsFontUI` | ★ 字体大小 |
| `com.tencent.mm.plugin.setting.ui.setting.SettingsLanguageUI` | ★ 语言 |
| `com.tencent.mm.plugin.setting.ui.setting.SettingsChattingBackgroundUI` | ★★★ 聊天背景 |
| `com.tencent.mm.plugin.setting.ui.setting.SettingsSelectBgUI` | ★ 选择背景 |
| `com.tencent.mm.plugin.setting.ui.setting.SettingDarkMode` | ★★★ 深色模式 |
| `com.tencent.mm.plugin.setting.ui.setting.SettingsPersonalInfoPreviewUI` | 个人信息 |
| `com.tencent.mm.plugin.setting.ui.setting.EditSignatureUI` | 编辑签名 |
| `com.tencent.mm.plugin.setting.ui.setting.SelectSexUI` | 性别选择 |
| `com.tencent.mm.plugin.setting.ui.setting.SelfQRCodeUI` | 我的二维码 |
| `com.tencent.mm.plugin.setting.ui.setting.SettingsManageFindMoreUI` | 管理发现页 |
| `com.tencent.mm.plugin.setting.ui.setting.SettingsManageFindMoreV2UI` | V2版 |
| `com.tencent.mm.plugin.setting.ui.setting.SettingsSwitchAccountUI` | 切换账号 |
| `com.tencent.mm.plugin.setting.ui.setting.SettingDeleteAccountUI` | 注销账号 |
| `com.tencent.mm.plugin.setting.ui.setting.SettingsTrustFriendUI` | 信任朋友 |
| `com.tencent.mm.plugin.setting.ui.setting.SettingsMusicUI` | 音乐设置 |
| `com.tencent.mm.plugin.setting.ui.setting.SettingMessageRingtoneUI` | 消息铃声 |
| `com.tencent.mm.plugin.setting.ui.setting.SettingsCareModeIntro` | 关怀模式 |
| `com.tencent.mm.plugin.setting.ui.setting.SettingsHearingAidInitUI` | 助听器 |
| `com.tencent.mm.plugin.setting.ui.setting.SettingsTranslateLanguageUI` | 翻译语言 |
| `com.tencent.mm.plugin.setting.ui.setting.SettingsAliasUI` | 微信号 |
| `com.tencent.mm.plugin.setting.ui.setting.SettingsAuthUI` | 授权管理 |
| `com.tencent.mm.plugin.setting.ui.setting.SettingsModifyNameUI` | 修改昵称 |
| `com.tencent.mm.plugin.setting.ui.setting.SettingsSystemPermissionUI` | 系统权限 |
| `com.tencent.mm.plugin.setting.ui.setting.UnfamiliarContactUI` | 不熟悉联系人 |
| `com.tencent.mm.plugin.setting.ui.setting.PreviewHdHeadImg` | 头像预览 |

# 十一、钱包/支付模块

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.plugin.wallet_core.ui.WalletCheckPwdUI` | ★★ 支付密码验证 |
| `com.tencent.mm.plugin.wallet_core.ui.WalletCheckPwdNewUI` | 新版密码验证 |
| `com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoUI` | 订单详情 |
| `com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoNewUI` | 新版订单 |
| `com.tencent.mm.plugin.wallet_core.ui.WalletBindCardResultUI` | 绑卡结果 |
| `com.tencent.mm.plugin.wallet_core.ui.WalletCardSelectUI` | 选银行卡 |
| `com.tencent.mm.plugin.wallet_core.ui.WalletSetPasswordUI` | 设支付密码 |
| `com.tencent.mm.plugin.wallet_core.ui.WalletPwdConfirmUI` | 确认密码 |
| `com.tencent.mm.plugin.wallet_core.ui.WalletBankcardIdUI` | 银行卡号 |
| `com.tencent.mm.plugin.wallet_core.ui.WalletCardElementUI` | 银行卡要素 |
| `com.tencent.mm.plugin.wallet_core.id_verify.WalletRealNameVerifyUI` | ★ 实名认证 |
| `com.tencent.mm.plugin.wallet_core.id_verify.WcPayRealnameVerifyMainUI` | 实名主界面 |
| `com.tencent.mm.plugin.wallet_core.id_verify.RealnameDialogActivity` | 实名弹窗 |
| `com.tencent.mm.plugin.wallet_core.ui.ShowWxPayAgreementsUI` | 支付协议 |
| `com.tencent.mm.plugin.mall.ui.MallWalletUI` | 钱包Mall |
| `com.tencent.mm.plugin.collect.ui.CollectMainUI` | ★ 收付款 |
| `com.tencent.mm.plugin.aa.ui.LaunchAAUI` | 发起群收款 |
| `com.tencent.mm.plugin.aa.ui.PaylistAAUI` | AA 支付列表 |
| `com.tencent.mm.plugin.aa.ui.AAQueryListUI` | AA 查询 |
| `com.tencent.mm.plugin.gwallet.GWalletUI` | 国际钱包 |

# 十二、公众号/服务号模块

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.ui.conversation.BizConversationUI` | ★ 公众号会话列表 |
| `com.tencent.mm.ui.conversation.presenter.BaseBizConversationUI` | ★ 公众号基类 |
| `com.tencent.mm.plugin.brandservice.ui.timeline.BizTimeLineSettingUI` | 公众号时间线设置 |
| `com.tencent.mm.plugin.brandservice.conversation.ui.BizFansSettingUI` | 粉丝设置 |
| `com.tencent.mm.plugin.brandservice.ui.BizPhotoAccountGalleryUI` | 公众号画廊 |
| `com.tencent.mm.feature.brandecs.ui.BrandEcsNotifySettingUI` | 品牌通知 |
| `com.tencent.mm.ui.brandservice.BrandServiceNotifyUI` | 品牌服务通知 |
| `com.tencent.mm.ui.brandservice.BrandServiceNotifySettingUI` | 品牌服务设置 |
| `com.tencent.mm.ui.bizchat.BizChatConversationUI` | 企业号会话 |
| `com.tencent.mm.ui.bizchat.BizChatFavUI` | 企业号收藏 |
| `com.tencent.mm.ui.bizchat.BizChatSelectConversationUI` | 企业号选会话 |
| `com.tencent.mm.ui.bizchat.BizChatroomInfoUI` | 企业号群信息 |
| `com.tencent.mm.plugin.subapp.ui.friend.FMessageConversationUI` | 好友消息会话 |
| `com.tencent.mm.plugin.subapp.ui.openapi.AppProfileUI` | 应用 Profile |
| `com.tencent.mm.feature.appmsg.ui.RecordDetailUI` | 记录详情 |
| `com.tencent.mm.plugin.fts.ui.FTSServiceNotifyUI` | FTS 服务通知 |

# 十三、群聊管理模块

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.chatroom.ui.ChatroomInfoUI` | ★★★ 群信息页 |
| `com.tencent.mm.plugin.profile.ui.CommonChatroomInfoUI` | 通用群信息 |
| `com.tencent.mm.chatroom.ui.RoomUpgradeUI` | 群升级 |
| `com.tencent.mm.chatroom.ui.RoomAnnouncementUI` | 群公告 |
| `com.tencent.mm.chatroom.ui.ChatroomMemberSearchUI` | 群成员搜索 |
| `com.tencent.mm.chatroom.ui.DelChatroomMemberUI` | 删除成员 |
| `com.tencent.mm.chatroom.ui.SeeRoomMemberUI` | 查看成员 |
| `com.tencent.mm.chatroom.ui.SelectMemberUI` | 选择成员 |
| `com.tencent.mm.chatroom.ui.TransferRoomOwnerUI` | 转让群主 |
| `com.tencent.mm.chatroom.ui.SeeRoomManagerUI` | 管理员 |
| `com.tencent.mm.chatroom.ui.GroupAdminManagerUI` | 管理员管理 |
| `com.tencent.mm.chatroom.ui.ModRemarkRoomNameUI` | 修改群备注 |
| `com.tencent.mm.chatroom.ui.RoomToolsHomeUI` | 群工具首页 |
| `com.tencent.mm.chatroom.ui.RoomToolsManageUI` | 群工具管理 |
| `com.tencent.mm.chatroom.ui.GroupToolsManagereUI` | 群工具管理(旧) |
| `com.tencent.mm.chatroom.ui.ChatRoomBindAppUI` | 群绑定应用 |
| `com.tencent.mm.chatroom.ui.RoomAccessVerifyApplicationUI` | 进群申请 |
| `com.tencent.mm.chatroom.ui.ManagerRoomByWeworkUI` | 企业微信管理 |

# 十四、收藏/相册模块

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.plugin.fav.ui.FavImgGalleryUI` | ★ 收藏图片画廊 |
| `com.tencent.mm.plugin.fav.ui.gallery.FavMediaGalleryUI` | ★ 收藏媒体画廊 |
| `com.tencent.mm.plugin.fav.ui.FavSearchUI` | 收藏搜索 |
| `com.tencent.mm.plugin.favorite.ui.FavOpenApiEntry` | 收藏 API |
| `com.tencent.mm.ui.tools.AddFavoriteUI` | 添加收藏 |
| `com.tencent.mm.plugin.gallery.ui.SmartGalleryUI` | 智能相册 |
| `com.tencent.mm.plugin.subapp.ui.gallery.GestureGalleryUI` | 手势画廊 |
| `com.tencent.mm.plugin.product.ui.MallGalleryUI` | 商城画廊 |
| `com.tencent.mm.plugin.game.media.GamePublishGalleryUI` | 游戏画廊 |

# 十五、登录/注册模块

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.plugin.account.ui.LoginUI` | ★ 登录主页 |
| `com.tencent.mm.plugin.account.ui.LoginHistoryUI` | 登录历史 |
| `com.tencent.mm.plugin.account.ui.LoginPasswordUI` | ★ 密码登录 |
| `com.tencent.mm.plugin.account.ui.LoginSmsUI` | 短信登录 |
| `com.tencent.mm.plugin.account.ui.LoginVoiceUI` | 语音登录 |
| `com.tencent.mm.plugin.account.ui.LoginFaceUI` | 人脸登录 |
| `com.tencent.mm.plugin.account.ui.LoginFastSwitchUI` | 快速切换 |
| `com.tencent.mm.plugin.account.ui.MobileVerifyUI` | 手机验证 |
| `com.tencent.mm.plugin.account.ui.RegByMobileRegAIOUI` | 注册AIO |
| `com.tencent.mm.plugin.account.ui.RegByMobileSetPwdUI` | 注册设密码 |
| `com.tencent.mm.plugin.account.ui.RegByMobileSetNickUI` | 注册设昵称 |
| `com.tencent.mm.ui.tools.AccountDeletedAlphaAlertUI` | 账号已删除提示 |

# 十六、状态模块 (TextStatus)

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.plugin.textstatus.ui.TextStatusEditActivity` | ★★ 编辑状态 |
| `com.tencent.mm.plugin.textstatus.ui.TextStatusShowActivity` | 显示状态 |
| `com.tencent.mm.plugin.textstatus.ui.TextStatusSquareActivity` | 状态广场 |
| `com.tencent.mm.plugin.textstatus.ui.TextStatusDetailActivity` | 状态详情 |
| `com.tencent.mm.plugin.textstatus.ui.TextStatusDoWhatActivity` | 设状态页 |
| `com.tencent.mm.plugin.textstatus.ui.TextStatusDoWhatActivityV2` | V2版设状态 |
| `com.tencent.mm.plugin.textstatus.ui.TextStatusNewActivity` | 新版状态 |
| `com.tencent.mm.plugin.textstatus.ui.TextStatusLikeListActivity` | 点赞列表 |
| `com.tencent.mm.plugin.textstatus.ui.TextStatusHistoryActivity` | 状态历史 |
| `com.tencent.mm.plugin.textstatus.ui.TextStatusCardFeedsActivity` | 状态卡片流 |
| `com.tencent.mm.plugin.textstatus.conversation.ui.TextStatusGreetingActivity` | 打招呼 |

# 十七、VoIP/通话模块

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.plugin.voip.ui.VideoActivity` | ★★ 视频通话 |
| `com.tencent.mm.plugin.voip.ui.MMSuperAlert` | 超级弹窗 |
| `com.tencent.mm.plugin.ipcall.ui.IPCallDialUI` | IP拨号 |
| `com.tencent.mm.plugin.ipcall.ui.IPCallTalkUI` | IP通话 |
| `com.tencent.mm.plugin.ipcall.ui.IPCallAddressUI` | IP通讯录 |
| `com.tencent.mm.plugin.ipcall.ui.IPCallAllRecordUI` | IP通话记录 |
| `com.tencent.mm.plugin.ipcall.ui.IPCallContactUI` | IP联系人 |
| `com.tencent.mm.plugin.ipcall.ui.IPCallRechargeUI` | IP充值 |

# 十八、卡券模块

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.plugin.card.ui.CardIndexUI` | 卡券首页 |
| `com.tencent.mm.plugin.card.ui.CardDetailUI` | ★ 卡券详情 |
| `com.tencent.mm.plugin.card.ui.CardHomePageUI` | 卡券主页 |
| `com.tencent.mm.plugin.card.ui.CardViewUI` | 卡券查看 |
| `com.tencent.mm.plugin.card.ui.CardShopUI` | 卡券商店 |
| `com.tencent.mm.plugin.card.ui.CardNewMsgUI` | 卡券消息 |
| `com.tencent.mm.plugin.card.ui.CardGiftAcceptUI` | 礼品接受 |
| `com.tencent.mm.plugin.card.ui.CardGiftReceiveUI` | 礼品领取 |
| `com.tencent.mm.plugin.card.sharecard.ui.ShareCardListUI` | 共享卡 |
| `com.tencent.mm.plugin.card.ui.v2.CardHomePageNewUI` | V2主页 |
| `com.tencent.mm.plugin.card.ui.v3.CardHomePageV3UI` | V3主页 |
| `com.tencent.mm.plugin.card.ui.v3.VipCardListUI` | VIP卡列表 |
| `com.tencent.mm.plugin.card.ui.v3.CouponCardListUI` | 优惠券列表 |
| `com.tencent.mm.plugin.card.ui.v4.CouponAndGiftCardListV4UI` | V4版 |
| `com.tencent.mm.plugin.card.ui.v4.HistoryCardListUI` | 历史卡券 |

# 十九、小程序模块

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.plugin.appbrand.ui.AppBrandLauncherUI` | ★ 小程序桌面 |
| `com.tencent.mm.plugin.appbrand.ui.AppBrandLauncherFolderUI` | 小程序文件夹 |
| `com.tencent.mm.plugin.appbrand.ui.AppBrandProfileUI` | 小程序信息 |
| `com.tencent.mm.plugin.appbrand.ui.AppBrandUI` | ★ 小程序容器 |
| `com.tencent.mm.plugin.appbrand.ui.AppBrandUI00` ~ `04` | 分片(0-4) |
| `com.tencent.mm.plugin.appbrand.ui.AppBrandAuthorizeUI` | 授权界面 |
| `com.tencent.mm.plugin.appbrand.ui.AppBrandGuideUI` | 引导界面 |
| `com.tencent.mm.plugin.appbrand.ui.AppBrandPluginUI` | 插件页 |
| `com.tencent.mm.plugin.appbrand.ui.AppBrandPluginUI1` ~ `4` | 插件分片 |
| `com.tencent.mm.plugin.appbrand.ui.AppBrand404PageUI` | 404页面 |
| `com.tencent.mm.plugin.appbrand.ui.AppBrandDebugUI` | 调试页面 |
| `com.tencent.mm.plugin.appbrand.ui.AppBrandStorageUsageUI` | 存储使用 |

# 二十、底层 UI 组件详解

## 20.1 顶部栏 (ActionBar / TitleBar)

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.ui.tools.ActionBarSearchView` | ★★ 顶部搜索栏 View |
| `com.tencent.mm.plugin.fts.ui.widget.FTSActionBarSearchView` | FTS 搜索栏 |
| `com.tencent.mm.ui.tools.p2` | TitleBar 辅助类 (混淆) |
| `androidx.appcompat.widget.ActionBarContainer` | AndroidX 原生 |
| `androidx.appcompat.widget.ActionBarContextView` | AndroidX Context |
| `androidx.appcompat.widget.ActionBarOverlayLayout` | AndroidX Overlay |

## 20.2 状态栏

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.ui.statusbar.DrawStatusBarActivity` | ★ 沉浸式状态栏 Activity |
| `com.tencent.mm.ui.statusbar.DrawStatusBarFrameLayout` | ★ 状态栏 FrameLayout |
| `com.tencent.mm.ui.statusbar.DrawStatusBarPreference` | ★ 状态栏 Preference 基类 |
| `com.tencent.mm.plugin.appbrand.widget.AppBrandDrawStatusBarFrameLayout` | 小程序状态栏 |

## 20.3 表情面板 (Emoji)

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.emoji.panel.EmojiPanelRecyclerView` | ★ 表情面板 RecyclerView |
| `com.tencent.mm.emoji.panel.EmojiPanelGroupView` | ★ 表情分组 View |
| `com.tencent.mm.emoji.panel.layout.EmojiPanelLayoutManager` | 表情布局管理器 |
| `com.tencent.mm.emoji.panel.layout.EmojiPanelItemLayoutManager` | 表情项布局 |
| `com.tencent.mm.emoji.view.EmojiPanelInputComponent` | 表情输入组件 |
| `com.tencent.mm.view.EmojiPanelSlideIndicatorView` | 滑动手势指示器 |
| `com.tencent.mm.plugin.emoji.ui.picker.FinderTabFragment` | Finder Tab |
| `com.tencent.mm.plugin.emoji.ui.picker.GalleryTabFragment` | 图片 Tab |
| `com.tencent.mm.plugin.emoji.ui.picker.MultiSelectFinderTabFragment` | 多选 Tab |

## 20.4 声音/语音

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.pluginsdk.ui.VoiceInputLayout` | ★ 语音按钮布局 |
| `com.tencent.mm.pluginsdk.ui.VoiceInputFooter` | ★ 语音输入底部栏 |
| `com.tencent.mm.pluginsdk.ui.chat.VoiceInputPanel` | 语音输入面板 |
| `com.tencent.mm.pluginsdk.ui.VoiceInputScrollView` | 语音输入滚动 |
| `com.tencent.mm.plugin.finder.voice.FinderLiveVoiceInputLayout` | Finder 直播语音 |

## 20.5 其他关键 UI 组件

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.ui.chatting.ChattingAnimFrame` | 聊天动画 Frame |
| `com.tencent.mm.ui.chatting.InitCallBackLayout` | 回调布局 |
| `com.tencent.mm.ui.chatting.TapToDismissFrameLayout` | 点击消失布局 |
| `com.tencent.mm.ui.chatting.HardDeviceChattingItemView` | 硬件设备聊天 |
| `com.tencent.mm.ui.chatting.SendDataToDeviceProgressBar` | 发送进度条 |
| `com.tencent.mm.ui.conversation.ChatBotConversationTextLine` | 聊天机器人文字行 |
| `com.tencent.mm.plugin.finder.view.FinderLiveDarkModePicker` | 直播深色选择器 |
| `com.tencent.mm.plugin.sns.ui.widget.SnsCardAdTagListView` | SNS 广告标签 |
| `com.tencent.mm.plugin.sns.ui.widget.SnsCommentCollapseLayout` | 评论折叠布局 |
| `com.tencent.mm.plugin.sns.ui.widget.SnsCommentEmoticonDetailUI` | 评论表情详情 |

# 二十一、全局主题 Hook 架构方案

## 21.1 VAS 主题引擎（最高优先级）

```
VASActivityJava  ← Java 层
    └── VASActivity  ← Kotlin 层，所有主题属性最终入口
        ├── onCreate()  ← 活动创建时设置主题
        ├── onResume()  ← 恢复时重新应用
        └── setCustomDensity() / setTheme() 相关方法
```

**Hook VASActivity 可以实现全局主题覆盖。**

## 21.2 按模块分层 Hook 方案

### Layer 1: 全局基类 (影响所有界面)
| 目标类 | Hook 点 | 效果 |
|------|------|------|
| `VASActivity` | `onCreate()` | 全局背景色/主题色 |
| `VASActivityJava` | `onCreate()` | Java 层全局颜色 |
| `MMActivity` | `onCreate()` | 所有 Activity 背景 |
| `MMFragmentActivity` | `onCreate()` | Fragment Activity |
| `MMFragment` | `onCreateView()` | 所有 Fragment |

### Layer 2: 主界面框架
| 目标类 | Hook 点 | 效果 |
|------|------|------|
| `LauncherUI` | `onCreate()` | 主界面背景色 |
| `LauncherUIBottomTabView` | 构造函数/`onDraw()` | ★ 底部导航栏颜色 |
| `AbstractTabChildActivity` | `onCreate()` | Tab 子页背景 |

### Layer 3: 会话列表
| 目标类 | Hook 点 | 效果 |
|------|------|------|
| `BaseConversationUI` | `onCreate()` | 会话列表背景 |
| `ConversationListView` | 构造函数 | 列表背景 |
| `ConversationAdapter` | `getView()` | 每项背景 |
| `ConversationFolderItemView` | 构造函数 | 折叠项背景 |
| `MainUIView` | 构造函数 | 主 UI View |

### Layer 4: 聊天界面
| 目标类 | Hook 点 | 效果 |
|------|------|------|
| `BaseChattingUIFragment` | `onCreateView()` | ★ 聊天背景 |
| `ChattingImageBGView` | `setBackground()` | ★ 聊天壁纸 |
| `ChatFooter` | 构造函数 | ★★ 底部输入栏背景 |
| `ChatFooterBottom` | 构造函数 | ★ +号和语音按钮区域 |
| `ChatFooterPanel` | 构造函数 | ★ +号展开面板 |
| `ChattingFooterMoreBtnBar` | 构造函数 | +号按钮栏 |

### Layer 5: 朋友圈
| 目标类 | Hook 点 | 效果 |
|------|------|------|
| `SnsTimeLineUI` | `onCreate()` | ★ 朋友圈页背景 |
| `ImproveSnsTimelineUI` | `onCreate()` | ★ 新版朋友圈背景 |
| `SnsHeader` | 构造函数 | ★ 卡片头部 |
| `SnsCollapsibleTextView` | 构造函数 | ★ 文字内容颜色 |
| `SnsTimelineImgBottomBar` | 构造函数 | ★ 赞/评论栏 |
| `TimelineCommentView` | 构造函数 | ★ 评论 View |
| `ImproveItemHeader` | 构造函数 | 新版卡片头 |
| `ImproveItemFooter` | 构造函数 | 新版卡片底 |
| `PhotosContent` | 构造函数 | 图片九宫格 |

### Layer 6: 联系人
| 目标类 | Hook 点 | 效果 |
|------|------|------|
| `ContactInfoUI` | `onCreate()` | ★ 联系人详情 |
| `ContactProfileUI` | `onCreate()` | 联系人 Profile |
| `AddressUI` | `onCreate()` | ★ 通讯录 |

### Layer 7: 设置
| 目标类 | Hook 点 | 效果 |
|------|------|------|
| `BaseSettingUI` | `onCreate()` | ★ 新版设置基类 |
| `MainSettingsUI` | `onCreate()` | 设置主页 |
| `CommonSettingsUI` | `onCreate()` | 通用设置 |
| `SettingDarkMode` | 相关方法 | ★ 深色模式 |

### Layer 8: 发现页
| 目标类 | Hook 点 | 效果 |
|------|------|------|
| `FindMoreFriendsUI` | `onCreateView()` | ★ 发现页 |
| `AbstractTabChildPreference` | `onCreateView()` | 发现页基类 |

### Layer 9: 公众号/服务号
| 目标类 | Hook 点 | 效果 |
|------|------|------|
| `BizConversationUI` | `onCreate()` | ★ 公众号列表 |
| `BaseBizConversationUI` | `onCreate()` | ★ 公众号基类 |

### Layer 10: WebView
| 目标类 | Hook 点 | 效果 |
|------|------|------|
| `WebViewUI` | `onCreate()` | ★ WebView 页 |

## 21.3 颜色/主题相关关键图标资源

```
actionbar_icon_dark_add        → 深色+号图标
actionbar_icon_dark_back       → 深色返回图标
actionbar_icon_dark_more       → 深色更多图标
actionbar_icon_dark_search     → 深色搜索图标
actionbar_icon_light_back      → 亮色返回图标
actionbar_icon_light_more      → 亮色更多图标
send_normal_darkmode           → 发送按钮(暗色)
send_pressed_darkmode          → 发送按钮按下(暗色)
biz_heart_medium_darkmode      → 点赞图标(暗色)
biz_profile_ai_darkmode        → AI标志(暗色)
```

## 21.4 统计数字

| 维度 | 数量 |
|------|------|
| 总 Activity 组件 | 2001 |
| 主要界面 Activity | ~300 |
| 聊天变体 | 17 |
| 会话列表变体 | 9 |
| Finder 界面 | ~80 |
| 设置子页面 | ~70 |
| 朋友圈组件 | ~50 |
| 钱包/支付 | ~35 |
| 卡券 | ~20 |
| 小程序 | ~18 |
| Fragment Tab 页 | ~15 |
| WebView 子类 | ~25 |
| 登录/注册 | ~12 |

---

> 此文件由 LSPilot AI 助手自动生成，基于微信 APK 逆向分析。
> 所有类名均通过 DexKit / jadx 验证。
> 版本：微信通用版 (com.tencent.mm)

---

# 二十二、聊天窗口完整深度分析 ★★★

> 重点：消息层面层级结构、所有子类、所有组件

## 22.1 聊天窗口总体架构（7 层模型）

```
┌─────────────────────────────────────────────────────┐
│ Layer 7: Activity 容器                               │
│   ChattingUI (Activity)                              │
│   ├── 17 个 ChattingUI variants                     │
│   └── ChattingUIFragment (Fragment 容器)             │
├─────────────────────────────────────────────────────┤
│ Layer 6: Fragment 消息容器                           │
│   BaseChattingUIFragment (基类)                      │
│   ├── ChattingUIFragment                             │
│   ├── AppBrandServiceChattingUI$AppBrandServiceChattingFmUI │
│   └── OpenImKefuServiceConversationUI$OpenImKefuChattingUIFragment │
├─────────────────────────────────────────────────────┤
│ Layer 5: Component 功能组件层 (840 个类)              │
│   每个 Component 负责一个独立功能 → 见 22.3           │
├─────────────────────────────────────────────────────┤
│ Layer 4: Adapter / ListView 层                       │
│   nw1.t2 (混淆后 ChattingAdapter)                    │
│   ChattingListView (RecyclerView/ListView)            │
├─────────────────────────────────────────────────────┤
│ Layer 3: ViewItem 消息卡片渲染层 (1240 个类)           │
│   每种消息类型对应一个 ViewItem → 见 22.4             │
├─────────────────────────────────────────────────────┤
│ Layer 2: 消息气泡 / 内容 View                         │
│   TextView / ImageView / VideoView / VoiceView ...   │
├─────────────────────────────────────────────────────┤
│ Layer 1: 输入区 (ChatFooter)                          │
│   ChatFooter → ChatFooterBottom → ChatFooterPanel     │
└─────────────────────────────────────────────────────┘
```

## 22.2 Layer 6: Fragment 层 — 消息容器

| 类名 | 完整路径 | 说明 |
|------|----------|------|
| **BaseChattingUIFragment** | `com.tencent.mm.ui.chatting.BaseChattingUIFragment` | ★★★ 聊天 Fragment 总基类 |
| **ChattingUIFragment** | `com.tencent.mm.ui.chatting.ChattingUIFragment` | ★★★ 聊天 Fragment 主类 |
| `AppBrandServiceChattingUI$AppBrandServiceChattingFmUI` | 小程序客服 Fragment |
| `OpenImKefuServiceConversationUI$OpenImKefuChattingUIFragment` | 开放客服 Fragment |

**继承链：**
```
BaseChattingUIFragment
  └── MMFragment
      └── FragmentActivitySupport
          └── HellAndroidXFragment
              └── Fragment
```

## 22.3 Layer 5: Component 组件层 — 功能模块化 (840 个类)

聊天界面被拆分为 840 个独立 Component，采用组件化架构。以下是所有**已识别命名组件**：

### 22.3.1 输入/发送相关组件
| 组件名 | 说明 |
|------|------|
| `FootComponent` | ★★★ 底部输入框组件（最核心） |
| `VoiceComponent` | ★★ 语音消息组件 |
| `EmojiComponent` | ★★ 表情组件 |
| `GetImageComponent` | ★ 获取图片组件 |
| `MusicComponent` | 音乐组件 |
| `LbsComponent` | 位置组件 (LBS) |
| `TransformComponent` | 格式转换组件 |
| `TranslateComponent` | 翻译组件 |
| `TranslateWhileWriteComponent` | 边说边译组件 |
| `ChattingVoice2TxtComponent` | 语音转文字组件 |

### 22.3.2 群聊相关组件
| 组件名 | 说明 |
|------|------|
| `ChatroomComponent` | ★★ 群聊组件 |
| `GroupTodoComponent` | 群待办组件 |
| `GroupToolsComponet` | 群工具组件 |
| `SilenceMsgComponent` | 静默消息组件 |
| `TrackRoomComponent` | 房间跟踪组件 |
| `OpenIMArchiveComponent` | 开放IM存档组件 |

### 22.3.3 消息处理组件
| 组件名 | 说明 |
|------|------|
| `ChattingAppBrandNotifyComponent` | 小程序通知组件 |
| `RemittanceSearchComponent` | 汇款搜索组件 |
| `ChatRecordsTtsFloatBallComponent` | 聊天记录TTS悬浮球 |
| `ChatRecordsTtsIndicatorComponent` | 聊天记录TTS指示器 |

### 22.3.4 业务子包组件
| 子包 | 包含组件 |
|------|----------|
| `component.biz.BizComponent` | ★★ 公众号聊天组件 |
| `component.biz.a~z, a0~m0` | 公众号辅助组件 (36个) |
| `component.appbrand.AppBrandServiceComponent` | ★ 小程序客服组件 |
| `component.appbrand.a~h` | 小程序辅助组件 (8个) |

### 22.3.5 混淆命名组件（按字母表分区）
| 命名范围 | 数量 | 说明 |
|------|------|------|
| `a` ~ `aj` | 36 | a系列 |
| `b` ~ `bp` | ~42 | b系列（含b5大组件） |
| `c` ~ `cp` | ~42 | c系列（含cn大组件） |
| `d` ~ `dp` | ~42 | d系列 |
| `e` ~ `ep` | ~42 | e系列 |
| `f` ~ `fp` | ~42 | f系列 |
| ... | ... | 继续到 z 系列 |
| `a0` ~ `a9` | 10 | |
| `b0` ~ `b9` | 10 | |
| ... | ... | |
| `x0` ~ `xp`, `y0` ~ `yh`, `z0`+ | ~50+ | |

## 22.4 Layer 3: ViewItem 消息卡片渲染层 (1240 个类) ★★★

### 22.4.1 命名 ViewItem（已识别）

| ViewItem 类名 | 对应消息类型 | 说明 |
|------|------|------|
| `ChattingItemDyeingTemplate` | ★★★ 模板消息基类 | 所有染色模板消息的基类，子类极多 |
| `ChattingItemFooter` | ★ 消息底部栏 | 消息底部操作栏 |
| `ChattingItemTranslate` | 翻译消息 | 翻译后的消息展示 |
| `ChattingItemAppMsgFinderFeed` | 视频号动态卡片 | 聊天中的视频号Feed |
| `ChattingItemAppMsgFinderProduct` | 视频号商品卡片 | 视频号带货商品 |
| `ChattingItemAppMsgFinderOrder` | 视频号订单卡片 | 视频号订单 |
| `ChatingItemAppMsgFinderLiveFeed` | 视频号直播卡片 | 视频号直播分享 |
| `ChattingItemFoldSys` | 折叠系统消息 | 系统消息折叠 |

### 22.4.2 ViewItem 混淆命名全量列表

viewitems 包内 1240 个类按字母分区：

| 命名范围 | 数量 | 说明 |
|------|------|------|
| `a` ~ `at` | ~46 | a 系列 |
| `b` ~ `bt` | ~46 | b 系列（含 bh/bh$$a-c） |
| `c` ~ `ct` | ~46 | c 系列 |
| `c2` (含所有$$子类) | ~120+ | ★★★ 核心 ViewItem！大量内部子类 |
| `d` ~ `dn` | ~40 | d 系列 |
| `e~z` (单字母) | ~23*20=460 | 全字母范围 |
| `a0~a9` ~ `w0~w9` | ~23*10=230 | 字母+数字 |
| `xa~xs` 等 | ~30 | x+字母范围 |
| `aa~zz` (双字母) | ~26*26=676(部分) | 双字母混淆 |

### 22.4.3 c2 核心 ViewItem 详解（120+ 子类）

`com.tencent.mm.ui.chatting.viewitems.c2` 是最核心的消息渲染类，包含大量内部匿名类：

| 内部子类 | 说明 |
|------|------|
| `c2$$a` ~ `c2$$a3` | a 系列辅助 (4个) |
| `c2$$b` ~ `c2$$b2` | b 系列 (3个) |
| `c2$$c` ~ `c2$$c2` | c 系列 (3个) |
| `c2$$d` ~ `c2$$d2` | d 系列 (3个) |
| `c2$$e` ~ `c2$$e2` | e 系列 |
| `c2$$f` ~ `c2$$f2` | f 系列 |
| `c2$$g` ~ `c2$$g2` | g 系列 |
| `c2$$h` ~ `c2$$h2` | |
| `c2$$i` ~ `c2$$i2` | |
| `c2$$j` ~ `c2$$j2` | |
| `c2$$k` ~ `c2$$k2` | |
| `c2$$l` ~ `c2$$l2` | |
| `c2$$m` ~ `c2$$m2` | |
| `c2$$n` ~ `c2$$n2` | |
| `c2$$o` ~ `c2$$o2` | |
| `c2$$p` ~ `c2$$p2` | |
| `c2$$q` ~ `c2$$q2` | |
| `c2$$r` ~ `c2$$r2` | |
| `c2$$s` ~ `c2$$s2` | |
| `c2$$t` ~ `c2$$t2` | |
| `c2$$u` ~ `c2$$u2` | |
| `c2$$v` ~ `c2$$v2` | |
| `c2$$w` ~ `c2$$w2` | |
| `c2$$x` ~ `c2$$x2` | |
| `c2$$y` ~ `c2$$y2` | |
| `c2$$z` ~ `c2$$z2` | |

**总计：c2 类及其内部类 ≈ 78+ 个子类**，每种对应一种消息类型的渲染逻辑。

### 22.4.4 消息类型 → ViewItem 映射（推断）

基于 ViewItem 命名模式和微信消息类型：

| 消息类型 | 对应渲染层 | 说明 |
|------|------|------|
| 文本消息 | c2 相关子类 | 普通文字气泡 |
| 图片消息 | c2 相关子类 | 图片气泡 |
| 语音消息 | VoiceComponent + 相关 ViewItem | 语音气泡 |
| 视频消息 | c2 相关子类 | 视频气泡 |
| 表情消息 | EmojiComponent + 相关 ViewItem | 表情包 |
| 位置消息 | LbsComponent + 相关 ViewItem | 位置卡片 |
| 名片消息 | c2 相关子类 | 名片卡片 |
| 文件消息 | c2 相关子类 | 文件卡片 |
| 链接消息 | c2 相关子类 | 链接卡片 |
| 音乐消息 | MusicComponent + 相关 ViewItem | 音乐卡片 |
| 小程序消息 | ChattingItemAppMsg* | 小程序卡片 |
| 视频号消息 | ChattingItemAppMsgFinder* | 视频号卡片 |
| 系统消息 | ChattingItemFoldSys | 系统提示 |
| 红包消息 | c2 相关子类 | 红包卡片 |
| 转账消息 | RemittanceSearchComponent + 相关 ViewItem | 转账卡片 |
| 模板消息 | ChattingItemDyeingTemplate | 公众号模板 |
| 翻译消息 | ChattingItemTranslate | 翻译后消息 |
| 群待办 | GroupTodoComponent | 群待办卡片 |
| 引用消息 | c2 相关子类 | 引用回复 |

## 22.5 聊天窗口全量类统计

| 子模块 | 包路径 | 类数量 |
|------|------|------|
| 主界面 Activity | `com.tencent.mm.ui.chatting` | 3440 |
| ├── Activity/Fragment 层 | 直接类 | ~30 |
| ├── 消息 ViewItem 渲染 | `...chatting.viewitems` | 1240 |
| ├── 功能 Component | `...chatting.component` | 840 |
| ├── 图片画廊 | `...chatting.gallery` | 402 |
| ├── 半屏聊天 | `...chatting.half` | 3 |
| ├── 工具类 | `...chatting.utils` | 2 |
| └── 其余混淆类 | `...chatting.*` | ~960 |

## 22.6 聊天底部输入区 (ChatFooter) 完整结构

```
ChatFooter (com.tencent.mm.pluginsdk.ui.chat.ChatFooter)
│
├── ChatFooterBottom        ← ★ 底部按钮栏（+ 号 / 语音切换按钮）
│   ├── 键盘切换按钮 (InputMethod)
│   ├── 语音切换按钮 (VoiceInput)
│   ├── + 号按钮 (Plus)
│   └── 发送按钮 (Send)
│
├── ChatFooterPanel         ← ★ + 号展开面板（照片/拍摄/视频/文件/位置...）
│   ├── 照片 (GalleryTabFragment)
│   ├── 拍摄 (SightCapture)
│   ├── 视频通话
│   ├── 位置 (LbsComponent)
│   ├── 红包
│   ├── 转账
│   ├── 名片
│   ├── 文件
│   ├── 音乐
│   ├── 小程序
│   └── 更多 (...)
│
├── VoiceInputLayout        ← 语音输入按钮布局
│   └── VoiceInputPanel     ← 语音录制面板
│       └── VoiceInputFooter ← 语音底部栏
│
├── EmojiPanel              ← 表情面板
│   ├── EmojiPanelRecyclerView
│   ├── EmojiPanelGroupView
│   └── EmojiPanelInputComponent
│
└── ActionBarSearchView     ← 搜索栏（部分聊天类型）
```

## 22.7 聊天图片画廊 (Gallery) 结构

```
ImageGalleryUI                   ← ★ 主画廊 Activity (402 个类)
├── ImageGalleryGridUI          ← 网格画廊
├── ImageGalleryVideoHandler    ← 视频处理
├── ActivityPullDownCloseLayout ← 下拉关闭
├── AppBrandHistoryListUI       ← 小程序历史
├── EmojiHistoryListUI          ← 表情历史
├── EmojiHistoryListFragment    ← 表情历史 Fragment
├── FinderFeedHistoryListUI     ← 视频号历史
├── MediaHistoryGalleryUI       ← 媒体历史
└── MsgHistoryGalleryUI         ← 消息历史
```

## 22.8 主题 Hook 聊天窗口 — 完整分层方案

### 第 1 层：聊天背景（全局）
```
Hook: BaseChattingUIFragment.onCreateView()
效果: 设置聊天列表背景色/背景图
```

### 第 2 层：消息气泡颜色（自己 vs 对方）
```
Hook: c2 核心 ViewItem 渲染方法
├── 对方消息气泡 → 灰色/白色
└── 自己消息气泡 → 绿色/蓝色
```

### 第 3 层：消息文字颜色
```
Hook: ChattingItemDyeingTemplate 文字渲染方法
效果: 消息文字颜色、链接颜色
```

### 第 4 层：底部输入栏
```
Hook: ChatFooter 构造函数
├── 输入框背景色
├── 输入框文字颜色
├── + 号图标颜色
└── 语音按钮颜色
```

### 第 5 层：+ 号面板
```
Hook: ChatFooterPanel 构造函数
效果: 面板背景色、图标颜色、分隔线颜色
```

### 第 6 层：表情面板
```
Hook: EmojiPanelRecyclerView / EmojiPanelGroupView
效果: 表情面板背景色
```

### 第 7 层：消息底部组件
```
Hook: ChattingItemFooter
效果: 时间戳颜色、已读状态颜色
```

### 第 8 层：系统消息
```
Hook: ChattingItemFoldSys
效果: "你已添加xxx为好友" 等系统消息颜色
```

### 第 9 层：语音组件
```
Hook: VoiceInputLayout / VoiceInputFooter
效果: 语音按钮背景、录音动画颜色
```

### 第 10 层：聊天图片画廊
```
Hook: ImageGalleryUI
├── 画廊背景色
└── 工具栏颜色
```

---

# 附录 A：所有聊天变体完整列表

```
com.tencent.mm.ui.chatting.ChattingUI                       ★ 主聊天
com.tencent.mm.ui.chatting.variants.ChattingMainUI          主聊天变体
com.tencent.mm.ui.chatting.variants.FinderChattingUI        视频号聊天
com.tencent.mm.ui.chatting.variants.AppBrandChattingUI      小程序聊天
com.tencent.mm.ui.chatting.variants.AppBrandChattingUI00    小程序 0
com.tencent.mm.ui.chatting.variants.AppBrandChattingUI01    小程序 1
com.tencent.mm.ui.chatting.variants.AppBrandChattingUI02    小程序 2
com.tencent.mm.ui.chatting.variants.AppBrandChattingUI03    小程序 3
com.tencent.mm.ui.chatting.variants.AppBrandChattingUI04    小程序 4
com.tencent.mm.ui.chatting.variants.AppBrandChattingUI1     小程序 1'
com.tencent.mm.ui.chatting.variants.AppBrandChattingUI2     小程序 2'
com.tencent.mm.ui.chatting.variants.AppBrandChattingUI3     小程序 3'
com.tencent.mm.ui.chatting.variants.AppBrandChattingUI4     小程序 4'
com.tencent.mm.ui.chatting.variants.WXCustomEntryChattingUI 自定义入口
com.tencent.mm.ui.chatting.variants.TopStoryChattingUI      看一看
com.tencent.mm.ui.chatting.variants.CastChattingUI          投屏
com.tencent.mm.ui.chatting.variants.LiteAppTaskChattingUI   LiteApp
com.tencent.mm.ui.chatting.variants.VoipChattingUI          VoIP
com.tencent.mm.ui.chatting.variants.FinderLiveChattingUI    直播
com.tencent.mm.ui.chatting.variants.GameChatroomChattingUI  游戏群
com.tencent.mm.ui.chatting.variants.TaskRedirectChattingUI  任务跳转
com.tencent.mm.ui.chatting.variants.WidgetEntryChattingUI   小组件
com.tencent.mm.ui.chatting.variants.MusicEntryChattingUI    音乐
com.tencent.mm.ui.chatting.variants.SdkEntryChattingUI      SDK
com.tencent.mm.ui.chatting.BizHalfScreenChattingUI          公众号半屏
com.tencent.mm.ui.chatting.AppBrandServiceChattingUI        小程序客服
```

# 附录 B：全部命名聊天组件

```
com.tencent.mm.ui.chatting.component.FootComponent              ★★★ 底部输入
com.tencent.mm.ui.chatting.component.VoiceComponent             ★★ 语音
com.tencent.mm.ui.chatting.component.EmojiComponent             ★★ 表情
com.tencent.mm.ui.chatting.component.GetImageComponent          ★ 图片
com.tencent.mm.ui.chatting.component.MusicComponent             音乐
com.tencent.mm.ui.chatting.component.LbsComponent               位置
com.tencent.mm.ui.chatting.component.TransformComponent         转换
com.tencent.mm.ui.chatting.component.TranslateComponent         翻译
com.tencent.mm.ui.chatting.component.TranslateWhileWriteComponent  边说边译
com.tencent.mm.ui.chatting.component.ChattingVoice2TxtComponent 语音转文字
com.tencent.mm.ui.chatting.component.ChatroomComponent          ★★ 群聊
com.tencent.mm.ui.chatting.component.GroupTodoComponent         群待办
com.tencent.mm.ui.chatting.component.GroupToolsComponet         群工具
com.tencent.mm.ui.chatting.component.SilenceMsgComponent        静默消息
com.tencent.mm.ui.chatting.component.TrackRoomComponent         房间跟踪
com.tencent.mm.ui.chatting.component.OpenIMArchiveComponent     开放IM
com.tencent.mm.ui.chatting.component.ChattingAppBrandNotifyComponent 小程序通知
com.tencent.mm.ui.chatting.component.RemittanceSearchComponent  汇款搜索
com.tencent.mm.ui.chatting.component.ChatRecordsTtsFloatBallComponent  TTS悬浮球
com.tencent.mm.ui.chatting.component.ChatRecordsTtsIndicatorComponent TTS指示器
com.tencent.mm.ui.chatting.component.biz.BizComponent           ★★ 公众号
com.tencent.mm.ui.chatting.component.appbrand.AppBrandServiceComponent 小程序客服
```

# 附录 C：聊天窗口 7 层架构 — 每层类数量

```
Layer 7 (Activity):     ~25  (ChattingUI + 17 variants + 辅助)
Layer 6 (Fragment):     ~5   (BaseChattingUIFragment 等)
Layer 5 (Component):    ~840 (功能组件，大量混淆)
Layer 4 (Adapter/List): ~5   (adapter + listview)
Layer 3 (ViewItem):     ~1240 (消息卡片渲染，大量混淆)
Layer 2 (Bubble/View):  ~50  (内部 View 子类)
Layer 1 (Input):        ~30  (ChatFooter + 面板)
─────────────────────────────────
聊天窗口总类数:         ~2195+
```

---

> 以上为聊天窗口深度分析补充章节，重点覆盖消息渲染层级和组件架构。

---

# 二十三、聊天窗口深度补全 — 所有缺失层级与子类 ★★★

## 23.1 聊天窗口完整包结构一览

```
com.tencent.mm.ui.chatting (3440 类)
│
├── ★ 根包 (直接类 ~50)
│   ├── ChattingUI                    ★ Activity
│   ├── ChattingUIFragment            ★ Fragment 主类
│   ├── ChattingUIProxy               代理
│   ├── BaseChattingUIFragment        ★ Fragment 基类
│   ├── ChatFooterCustom              自定义底部
│   ├── ChattingFooterMoreBtnBar      +号按钮栏
│   ├── ChattingImageBGView           ★ 聊天背景
│   ├── ChattingAnimFrame             动画 Frame
│   ├── ChattingSendDataToDeviceUI    发送数据到设备
│   ├── ChattingSendDataToDeviceForOpenMsgUI
│   ├── HardDeviceChattingItemView    硬件设备消息 View
│   ├── InitCallBackLayout            初始回调布局
│   ├── ResourcesExceedUI             资源超额
│   ├── RevokeMsgListener             撤回消息监听
│   ├── SendDataToDeviceProgressBar   发送进度条
│   ├── TapToDismissFrameLayout       点击消失
│   ├── QQMailHistoryExporter         QQ邮箱历史导出
│   ├── AutoPlay                      自动播放
│   ├── AppAttachNewDownloadUI        附件下载
│   ├── SendImgProxyUI                图片发送代理
│   ├── ImageDownloadUI               图片下载
│   ├── TextPreviewUI                 文字预览
│   ├── ChatMoreSelectUI              更多选择
│   ├── AppBrandServiceChattingUI     小程序客服聊天
│   └── BizHalfScreenChattingUI       公众号半屏聊天
│
├── ★ variants/ (17 变体)
│   ├── ChattingMainUI
│   ├── FinderChattingUI
│   ├── AppBrandChattingUI / 00~04 / 1~4
│   ├── WXCustomEntryChattingUI
│   ├── TopStoryChattingUI
│   ├── CastChattingUI
│   ├── LiteAppTaskChattingUI
│   ├── VoipChattingUI
│   ├── FinderLiveChattingUI
│   ├── GameChatroomChattingUI
│   ├── TaskRedirectChattingUI
│   ├── WidgetEntryChattingUI
│   ├── MusicEntryChattingUI
│   └── SdkEntryChattingUI
│
├── ★ viewitems/ (1240 类) — 消息卡片渲染层
│   ├── ChattingItemDyeingTemplate         ★★★ 染色模板基类 (7 内部类)
│   ├── ChattingItemFooter                 ★ 消息底部
│   ├── ChattingItemTranslate              翻译消息
│   ├── ChattingItemAppMsgFinderFeed        视频号动态
│   ├── ChattingItemAppMsgFinderProduct     视频号商品
│   ├── ChattingItemAppMsgFinderOrder       视频号订单
│   ├── ChatingItemAppMsgFinderLiveFeed     视频号直播
│   ├── c2                                  核心渲染类 (78+ $$子类)
│   ├── foldItem/ChattingItemFoldSys        折叠系统消息
│   └── a~z, a0~z9, aa~xs (混淆 ~1100)
│
├── ★ component/ (840 类) — 功能组件层
│   ├── ★ 命名组件 22个
│   │   ├── FootComponent                 ★★★ 底部输入
│   │   ├── VoiceComponent                ★★ 语音
│   │   ├── EmojiComponent                ★★ 表情
│   │   ├── GetImageComponent             ★ 图片
│   │   ├── MusicComponent                  音乐
│   │   ├── LbsComponent                    位置
│   │   ├── TransformComponent              转换
│   │   ├── TranslateComponent              翻译
│   │   ├── TranslateWhileWriteComponent    边说边译
│   │   ├── ChattingVoice2TxtComponent      语音转文字
│   │   ├── ChatroomComponent             ★★ 群聊
│   │   ├── GroupTodoComponent              群待办
│   │   ├── GroupToolsComponet              群工具
│   │   ├── SilenceMsgComponent             静默消息
│   │   ├── TrackRoomComponent              房间跟踪
│   │   ├── OpenIMArchiveComponent          开放IM存档
│   │   ├── ChattingAppBrandNotifyComponent 小程序通知
│   │   ├── RemittanceSearchComponent       汇款搜索
│   │   ├── ChatRecordsTtsFloatBallComponent TTS悬浮球
│   │   ├── ChatRecordsTtsIndicatorComponent TTS指示器
│   │   ├── TranslateControllerView         翻译控制器View
│   │   └── LoadableTextView                可加载TextView
│   ├── component.biz/ (46 类)
│   │   ├── BizComponent                  ★★ 公众号组件
│   │   └── a~z, a0~m0 (45 混淆)
│   ├── component.appbrand/ (9 类)
│   │   ├── AppBrandServiceComponent      ★ 小程序客服组件
│   │   └── a~h (8 混淆)
│   └── a~z, a0~z9, aa~yh (混淆 ~760)
│
├── ★ gallery/ (402 类) — 图片/媒体画廊
│   ├── ImageGalleryUI                  ★★★ 主画廊
│   │   └── $$a~$$z, $$a0~$$m0 (50+ 内部类)
│   ├── ImageGalleryGridUI              网格画廊
│   ├── ImageGalleryVideoHandler        视频处理器
│   ├── MediaHistoryGalleryUI           媒体历史
│   ├── MediaHistoryListUI              媒体历史列表
│   ├── ActivityPullDownCloseLayout     下拉关闭
│   ├── AppBrandHistoryListUI           小程序历史
│   ├── EmojiHistoryListUI              表情历史
│   ├── EmojiHistoryListFragment        表情历史 Fragment
│   ├── FinderFeedHistoryListUI         视频号历史
│   └── a~z, a0~z9, aa~d7 (混淆 ~340)
│
├── ★ view/ (119 类) — 聊天 View 组件
│   ├── ★ 命名 View 12个
│   │   ├── BubbleCornorLayout          ★★★ 气泡圆角布局
│   │   ├── FoldableCellLayout           ★ 可折叠 Cell
│   │   ├── FoldableChatTextItemView     ★ 可折叠文字
│   │   ├── MMChattingListView           ★★ 聊天列表 View
│   │   ├── ChattingAvatarImageView      ★ 头像 View
│   │   ├── ServiceNotifyHeaderView      ★ 服务通知头部
│   │   ├── AvatarImageView              头像 ImageView
│   │   └── AlphaGradientTextView        渐变文字 View
│   └── a~z, a0~z2 (混淆 ~107)
│
├── ★ adapter/ (40 类) — 消息数据适配器
│   ├── ChattingDataAdapter             ★★★ 聊天数据适配器
│   │   ├── buildItemConvertFactory      Item 工厂
│   │   ├── brandEscTmplMsgChangeListener 品牌模板消息监听
│   │   └── chatMsgChange                消息变更监听
│   └── a~z, a0~z0 (混淆 ~37)
│
├── ★ presenter/ (202 类) — 业务逻辑层
│   ├── MediaHistoryGalleryPresenter    ★ 媒体历史 Presenter
│   └── a~f, a0~f6 (混淆 ~201)
│
├── ★ manager/ (21 类) — 管理器层
│   ├── ChattingOnResultExecutor        ★ 结果执行器
│   └── a~t (混淆 20)
│
├── ★ history/ (42 类) — 聊天历史
│   ├── MsgHistoryGalleryUI             ★ 消息历史画廊
│   │
│   ├── history.chromes/ (5 类)
│   │   ├── MsgHistoryGalleryActionBarView      工具栏
│   │   ├── MsgHistoryGalleryFilterBarView       过滤栏
│   │   ├── MsgHistoryGallerySearchBarView       搜索栏
│   │   ├── MsgHistoryGalleryTimelineOverlayView 时间线叠加
│   │   └── MsgHistoryGalleryToolBarView         工具条
│   │
│   ├── history.components/ (2 类)
│   │   └── MsgHistoryGalleryPreviewTransitionUIC  预览过渡 UIC
│   │
│   ├── history.groups/ (22 类) a~v
│   ├── history.media/ (1 类)
│   │   └── MsgHistoryGalleryMediaLoadGroup        媒体加载组
│   │
│   ├── history.person/ (3 类)
│   │   ├── MsgHistoryGalleryPersonRecommendUI          推荐 UI
│   │   └── MsgHistoryGalleryPersonRecommendActionBarView  推荐工具栏
│   │
│   └── history.widgets/ (4 类)
│       ├── MsgHistoryGalleryAspectRatioFrameLayout  宽高比 Frame
│       ├── MsgHistoryGalleryCheckBox                复选框
│       ├── MsgHistoryGalleryIconImageView           图标 ImageView
│       └── MsgHistoryGalleryTypeFilterView          类型过滤
│
├── ★ half/ (3 类) — 半屏聊天
│   ├── NotificationHalfScreenChattingUIC     半屏通知 UIC
│   ├── HalfScreenChattingStarter             半屏启动器
│   └── NotificationHalfScreenChattingUIC$msgSuccessListener
│
├── ★ search/ (4 类) — 聊天搜索
│   └── search.multi/
│       ├── FTSChattingConvMultiTabUI           搜索多 Tab UI
│       └── search.multi.fragment/
│           ├── FTSMultiAllResultFragment        全部结果
│           ├── FTSMultiImageResultFragment      图片结果
│           └── FTSMultiNormalResultFragment     普通结果
│
├── ★ uic/ (4 类) — UIC 文件
│   └── uic.file/
│       ├── FilePreviewUIC                      文件预览 UIC
│       └── FileQBUIC                           文件 QB UIC
│
├── ★ mvvm/ (1 类)
│   └── MvvmChatList                            MVVM 聊天列表
│
└── ★ utils/ (2 类)
    ├── ServiceNotifyFoldTest
    └── TopLoadExpReportKt
```

---

## 23.2 pluginsdk.ui.chat — 聊天底层组件 (353 类)

这是微信聊天模块的**底层 SDK 库**，被 `com.tencent.mm.ui.chatting` 引用。

### 命名类 (18个)

| 类名 | 说明 |
|------|------|
| `ChatFooter` | ★★★ 聊天底部输入栏主类 (9个$$内部类+$1/$122) |
| `ChatFooterBottom` | ★★ 底部按钮栏 (+号/语音切换) |
| `ChatFooterPanel` | ★★ +号展开面板 |
| `AppPanel` | ★ +号面板内应用面板 |
| `AppGrid` | 应用网格布局 |
| `ChattingContent` | ★ 聊天内容容器 |
| `ChattingScrollLayout` | ★ 聊天滚动布局 |
| `ChattingUILayout` | ★ 聊天 UI 总布局 |
| `VoiceInputPanel` | 语音输入面板 |
| `b3` (含 $$a) | 面板内部组件 (命名但混淆) |
| `c` (含 $$a) | 面板内部组件 |
| `p2` (含 $$a) | 面板内部组件 |
| `hb` (含 $$a) | 面板内部组件 |
| `x9` (含 $$a) | 面板内部组件 |
| 其余 ~320 混淆类 (a~z, a0~z8, aa~ya) | |

### ChatFooter 内部结构

```
ChatFooter
├── ChatFooter$$a ~ $$i (9 个内部类)
├── ChatFooter$1 ($122 等匿名类)
├── ChatFooterBottom ← ★ 底部按钮行
│   ├── 键盘按钮
│   ├── 语音按钮
│   ├── +号按钮
│   └── 发送按钮
├── ChatFooterPanel ← ★ +号展开面板
│   └── AppPanel
│       └── AppGrid
├── ChattingContent ← 消息列表容器
├── ChattingScrollLayout ← 滚动布局
└── ChattingUILayout ← 总布局
```

---

## 23.3 pluginsdk.ui — 聊天相关 UI 组件 (1005 类)

### 聊天直接相关的命名类

| 类名 | 说明 |
|------|------|
| `VoiceInputLayout` | ★★ 语音输入布局 |
| `VoiceInputLayoutImpl` | ★ 语音输入实现 |
| `VoiceInputFooter` | ★★ 语音底部栏 (2个$$内部类) |
| `VoiceInputPanel` | 语音输入面板(chat子包) |
| `VoiceInputScrollView` | 语音滚动 View |
| `VoiceInputUI` | 语音 UI |
| `SpeechInputLayout` | ★ 语音识别输入布局 |
| `ChatFooterPanel` | ★★ +号展开面板 |
| `ChatFooterPanel$RecommendView` | 推荐 View |
| `CommonVideoView` | 通用视频 View |
| `AbstractVideoView` | 抽象视频 View |
| `FileSelectorPreviewUI` | 文件选择预览 |
| `LoadingTipsView` | 加载提示 View |
| `ProfileItemView` | Profile 项 View |
| `ProfileDescribeView` | Profile 描述 |
| `ProfileLabelView` | Profile 标签 |
| `ProfileHdHeadImg` | 高清头像 |
| `ProfileMobilePhoneView` | 手机号 View |
| `ProfileEditPhoneNumberView` | 编辑手机号 |
| `MultiSelectContactView` | 多选联系人 |
| `MMPhoneNumberEditText` | 手机号输入框 |
| `NotCopyUserNameImageView` | 防复制用户名 |

---

## 23.4 聊天窗口完整 10 层架构模型

```
┌──────────────────────────────────────────────────────────┐
│ Layer 10: Activity 容器 (variants/)           ~25 类     │
│   ChattingUI + 17 variants + AppBrandServiceChattingUI   │
│   + BizHalfScreenChattingUI                              │
├──────────────────────────────────────────────────────────┤
│ Layer 9: Fragment 容器 (根包直接)              ~5 类     │
│   BaseChattingUIFragment → ChattingUIFragment             │
├──────────────────────────────────────────────────────────┤
│ Layer 8: UIC 横切层 (uic/)                     ~4 类     │
│   FilePreviewUIC, FileQBUIC                              │
├──────────────────────────────────────────────────────────┤
│ Layer 7: Manager 管理器 (manager/)             ~21 类    │
│   ChattingOnResultExecutor + 混淆                        │
├──────────────────────────────────────────────────────────┤
│ Layer 6: Presenter 业务逻辑 (presenter/)       ~202 类   │
│   MediaHistoryGalleryPresenter + 混淆                    │
├──────────────────────────────────────────────────────────┤
│ Layer 5: Component 功能组件 (component/)       ~840 类   │
│   FootComponent, VoiceComponent, EmojiComponent,         │
│   ChatroomComponent, BizComponent, AppBrandService...    │
├──────────────────────────────────────────────────────────┤
│ Layer 4: Adapter 适配器 (adapter/)             ~40 类    │
│   ChattingDataAdapter ★★★                               │
├──────────────────────────────────────────────────────────┤
│ Layer 3: ViewItem 消息渲染 (viewitems/)        ~1240 类  │
│   ChattingItemDyeingTemplate, c2 (78+$$),               │
│   ChattingItemFooter, ChattingItemFoldSys...             │
├──────────────────────────────────────────────────────────┤
│ Layer 2: View 组件 (view/)                     ~119 类   │
│   BubbleCornorLayout ★★★, FoldableCellLayout,           │
│   MMChattingListView, ChattingAvatarImageView...         │
├──────────────────────────────────────────────────────────┤
│ Layer 1: 输入区 (pluginsdk.ui.chat)           ~353 类    │
│   ChatFooter → ChatFooterBottom → ChatFooterPanel        │
│   + ChattingContent + ChattingScrollLayout               │
├──────────────────────────────────────────────────────────┤
│ Layer 0: 底层 SDK (pluginsdk.ui)              ~1005 类   │
│   VoiceInputLayout, SpeechInputLayout,                   │
│   VoiceInputFooter, CommonVideoView...                   │
└──────────────────────────────────────────────────────────┘
```

---

## 23.5 所有命名消息 ViewItem 完整映射

| ViewItem | 对应消息类型 | 主题 Hook 建议 |
|------|------|------|
| `ChattingItemDyeingTemplate` | ★★★ 模板消息基类（公众号/服务号推送） | 模板消息背景色、标题色 |
| `ChattingItemFooter` | ★ 所有消息底部（时间戳） | 时间戳颜色、已读状态颜色 |
| `ChattingItemTranslate` | 翻译后消息 | 翻译文字颜色 |
| `ChattingItemAppMsgFinderFeed` | 视频号动态卡片 | 视频号卡片背景 |
| `ChattingItemAppMsgFinderProduct` | 视频号商品卡片 | 商品卡片背景 |
| `ChattingItemAppMsgFinderOrder` | 视频号订单卡片 | 订单卡片背景 |
| `ChatingItemAppMsgFinderLiveFeed` | 视频号直播卡片 | 直播卡片背景 |
| `ChattingItemFoldSys` | ★ 系统消息折叠 | "你已添加..." 文字颜色 |
| `c2` (78+ $$子类) | ★★★ 核心消息（文字/图片/视频/文件/链接/红包/转账等） | **最重要的 Hook 目标** |
| 混淆 a~xs (~1100 类) | 其他消息类型 | 各消息类型独立渲染 |

---

## 23.6 所有命名聊天 View 组件映射

| View 组件 | 说明 | 主题 Hook 建议 |
|------|------|------|
| `BubbleCornorLayout` | ★★★ 消息气泡圆角布局 | **气泡背景色、圆角大小** |
| `FoldableCellLayout` | ★ 可折叠消息 Cell | 折叠消息背景 |
| `FoldableChatTextItemView` | ★ 可折叠文字消息 | 文字颜色 |
| `MMChattingListView` | ★★ 聊天消息列表 RecyclerView | 列表背景/分隔线 |
| `ChattingAvatarImageView` | ★ 聊天头像 View | 头像边框颜色 |
| `ServiceNotifyHeaderView` | ★ 服务通知头部 | 头部背景色 |
| `AvatarImageView` | 通用头像 | 头像占位色 |
| `AlphaGradientTextView` | 渐变文字 | 特殊文字效果 |

---

## 23.7 聊天窗口全量类统计（补全版）

| # | 包/模块 | 类数 | 命名类 | 混淆类 |
|---|------|------|------|------|
| 1 | `chatting` 根包 | ~50 | 25 | 25 |
| 2 | `chatting.variants` | ~20 | 20 | 0 |
| 3 | `chatting.viewitems` | 1240 | 12 | 1228 |
| 4 | `chatting.component` | 840 | 28 | 812 |
| 5 | `chatting.gallery` | 402 | 10 | 392 |
| 6 | `chatting.view` | 119 | 8 | 111 |
| 7 | `chatting.adapter` | 40 | 3 | 37 |
| 8 | `chatting.presenter` | 202 | 1 | 201 |
| 9 | `chatting.manager` | 21 | 1 | 20 |
| 10 | `chatting.history` | 42 | 16 | 26 |
| 11 | `chatting.half` | 3 | 3 | 0 |
| 12 | `chatting.search` | 4 | 4 | 0 |
| 13 | `chatting.uic.file` | 4 | 2 | 2 |
| 14 | `chatting.mvvm` | 1 | 1 | 0 |
| 15 | `chatting.utils` | 2 | 2 | 0 |
| 16 | `pluginsdk.ui.chat` | 353 | 10 | 343 |
| 17 | `pluginsdk.ui` (聊天相关) | ~30 | 23 | 7 |
| **总计** | | **~3373** | **169** | **3204** |

---

## 23.8 主题 Hook — 聊天窗口终极分层方案

### 第 1 层：全局背景
```
Hook: ChattingImageBGView.setBackground()
效果: 聊天窗口整体背景色/壁纸
```

### 第 2 层：消息列表
```
Hook: MMChattingListView / ChattingScrollLayout
效果: 列表项间距颜色、分隔线
```

### 第 3 层：消息气泡（自己 vs 对方）
```
Hook: BubbleCornorLayout 构造函数/onDraw()
├── 对方气泡: 灰色/白色背景 + 圆角
└── 自己气泡: 绿色背景 + 圆角
```

### 第 4 层：消息文字
```
Hook: c2 系列 ViewItem + ChattingItemDyeingTemplate
├── 普通文字消息颜色
├── 链接文字颜色
├── @提醒文字颜色
└── 模板消息文字颜色
```

### 第 5 层：消息底部
```
Hook: ChattingItemFooter
├── 时间戳颜色
├── 已读/未读状态颜色
└── 发送状态图标
```

### 第 6 层：系统消息
```
Hook: ChattingItemFoldSys
效果: 系统提示文字颜色、背景色
```

### 第 7 层：头像
```
Hook: ChattingAvatarImageView / AvatarImageView
效果: 头像边框颜色、默认头像背景色
```

### 第 8 层：底部输入栏
```
Hook: ChatFooter + ChatFooterBottom
├── 输入框背景色
├── 输入框文字颜色
├── 输入框边框颜色
├── +号图标着色
├── 语音图标着色
├── 表情图标着色
└── 发送按钮背景色
```

### 第 9 层：+号面板
```
Hook: ChatFooterPanel + AppPanel + AppGrid
├── 面板背景色
├── 图标颜色
├── 文字颜色
└── 分割线颜色
```

### 第 10 层：语音输入
```
Hook: VoiceInputLayout / VoiceInputFooter / SpeechInputLayout
├── 语音按钮背景色
├── 录音动画颜色
├── "按住说话"文字颜色
└── 录音波纹颜色
```

### 第 11 层：表情面板
```
Hook: EmojiComponent + EmojiPanel*
├── 表情面板背景色
├── 表情分组 Tab 颜色
└── 表情项背景色
```

### 第 12 层：视频号卡片
```
Hook: ChattingItemAppMsgFinder* 系列
├── FinderFeed 卡片背景
├── FinderProduct 卡片背景
├── FinderOrder 卡片背景
└── FinderLiveFeed 卡片背景
```

### 第 13 层：服务通知
```
Hook: ServiceNotifyHeaderView
效果: 服务通知头部背景色、文字色
```

### 第 14 层：图片画廊
```
Hook: ImageGalleryUI
├── 画廊背景色 (全黑 → 自定义)
├── 工具栏背景色
├── 按钮着色
└── 页码颜色
```

### 第 15 层：聊天历史搜索
```
Hook: FTSChattingConvMultiTabUI
效果: 搜索结果页背景色、Tab 颜色
```

---

# 附录 D：聊天窗口所有包完整树

```
com.tencent.mm.ui.chatting
├── *.java/kotlin                          (根包 ~50类)
├── variants/*                             (17 变体)
├── viewitems/*                            (1240 消息渲染)
│   └── foldItem/*                         (折叠消息)
├── component/*                            (840 功能组件)
│   ├── biz/*                              (46 公众号)
│   └── appbrand/*                         (9 小程序)
├── gallery/*                              (402 画廊)
├── view/*                                 (119 View组件)
├── adapter/*                              (40 适配器)
├── presenter/*                            (202 业务逻辑)
├── manager/*                              (21 管理器)
├── history/*                              (根)
│   ├── chromes/*                          (5 工具栏)
│   ├── components/*                       (2 UIC)
│   ├── groups/*                           (22 分组)
│   ├── media/*                            (1 媒体)
│   ├── person/*                           (3 推荐)
│   └── widgets/*                          (4 Widget)
├── half/*                                 (3 半屏)
├── search/*                               (根)
│   └── multi/*                            (4 搜索)
│       └── fragment/*                     (3 结果Fragment)
├── uic/*                                  (根)
│   └── file/*                             (4 文件)
├── mvvm/*                                 (1 MVVM)
└── utils/*                                (2 工具)

com.tencent.mm.pluginsdk.ui.chat
├── ChatFooter                             ★★★
├── ChatFooterBottom                       ★★
├── ChatFooterPanel                        ★★
├── AppPanel + AppGrid                     ★
├── ChattingContent + ChattingScrollLayout ★
├── ChattingUILayout                       ★
├── VoiceInputPanel                        ★
└── 345 混淆类

com.tencent.mm.pluginsdk.ui
├── VoiceInputLayout / VoiceInputFooter    ★★
├── SpeechInputLayout                      ★
├── CommonVideoView / AbstractVideoView
├── ChatFooterPanel (也在此)
├── FileSelectorPreviewUI
├── Profile* Views (~8)
└── ~980 混淆类
```

---

# 附录 E：聊天窗口全部命名类总索引 (169 个)

```
=== Activity / Fragment ===
1.  ChattingUI
2.  ChattingUIFragment
3.  BaseChattingUIFragment
4.  ChattingUIProxy
5.  AppBrandServiceChattingUI
6.  AppBrandServiceChattingUI$AppBrandServiceChattingFmUI
7.  BizHalfScreenChattingUI
8.  OpenImKefuServiceConversationUI$OpenImKefuChattingUIFragment

=== variants (17) ===
9.  ChattingMainUI
10. FinderChattingUI
11. AppBrandChattingUI
12. AppBrandChattingUI00~04 (5)
17. AppBrandChattingUI1~4 (4)
21. WXCustomEntryChattingUI
22. TopStoryChattingUI
23. CastChattingUI
24. LiteAppTaskChattingUI
25. VoipChattingUI
26. FinderLiveChattingUI
27. GameChatroomChattingUI
28. TaskRedirectChattingUI
29. WidgetEntryChattingUI
30. MusicEntryChattingUI
31. SdkEntryChattingUI

=== 根包 View ===
32. ChatFooterCustom
33. ChattingFooterMoreBtnBar
34. ChattingImageBGView
35. ChattingAnimFrame
36. ChattingSendDataToDeviceUI
37. ChattingSendDataToDeviceForOpenMsgUI
38. HardDeviceChattingItemView
39. InitCallBackLayout
40. ResourcesExceedUI
41. RevokeMsgListener
42. SendDataToDeviceProgressBar
43. TapToDismissFrameLayout
44. QQMailHistoryExporter
45. AutoPlay
46. AppAttachNewDownloadUI
47. SendImgProxyUI
48. ImageDownloadUI
49. TextPreviewUI
50. ChatMoreSelectUI

=== viewitems (12) ===
51. ChattingItemDyeingTemplate
52. ChattingItemFooter
53. ChattingItemTranslate
54. ChattingItemAppMsgFinderFeed
55. ChattingItemAppMsgFinderProduct
56. ChattingItemAppMsgFinderOrder
57. ChatingItemAppMsgFinderLiveFeed
58. ChattingItemFoldSys (foldItem/)
59. c2 (核心渲染)

=== component (28) ===
60. FootComponent
61. VoiceComponent
62. EmojiComponent
63. GetImageComponent
64. MusicComponent
65. LbsComponent
66. TransformComponent
67. TranslateComponent
68. TranslateWhileWriteComponent
69. ChattingVoice2TxtComponent
70. ChatroomComponent
71. GroupTodoComponent
72. GroupToolsComponet
73. SilenceMsgComponent
74. TrackRoomComponent
75. OpenIMArchiveComponent
76. ChattingAppBrandNotifyComponent
77. RemittanceSearchComponent
78. ChatRecordsTtsFloatBallComponent
79. ChatRecordsTtsIndicatorComponent
80. TranslateControllerView
81. LoadableTextView
82. BizComponent (biz/)
83. AppBrandServiceComponent (appbrand/)

=== gallery (10) ===
84. ImageGalleryUI
85. ImageGalleryGridUI
86. ImageGalleryVideoHandler
87. MediaHistoryGalleryUI
88. MediaHistoryListUI
89. ActivityPullDownCloseLayout
90. AppBrandHistoryListUI
91. EmojiHistoryListUI
92. EmojiHistoryListFragment
93. FinderFeedHistoryListUI

=== view (8) ===
94. BubbleCornorLayout
95. FoldableCellLayout
96. FoldableChatTextItemView
97. MMChattingListView
98. ChattingAvatarImageView
99. ServiceNotifyHeaderView
100. AvatarImageView
101. AlphaGradientTextView

=== adapter (3) ===
102. ChattingDataAdapter
103. ChattingDataAdapter$Companion
104. ChattingDataAdapter$brandEscTmplMsgChangeListener

=== presenter (1) ===
105. MediaHistoryGalleryPresenter

=== manager (1) ===
106. ChattingOnResultExecutor

=== history (16) ===
107. MsgHistoryGalleryUI
108. MsgHistoryGalleryActionBarView
109. MsgHistoryGalleryFilterBarView
110. MsgHistoryGallerySearchBarView
111. MsgHistoryGalleryTimelineOverlayView
112. MsgHistoryGalleryToolBarView
113. MsgHistoryGalleryPreviewTransitionUIC
114. MsgHistoryGalleryMediaLoadGroup
115. MsgHistoryGalleryPersonRecommendUI
116. MsgHistoryGalleryPersonRecommendActionBarView
117. MsgHistoryGalleryAspectRatioFrameLayout
118. MsgHistoryGalleryCheckBox
119. MsgHistoryGalleryIconImageView
120. MsgHistoryGalleryTypeFilterView

=== half (3) ===
121. NotificationHalfScreenChattingUIC
122. HalfScreenChattingStarter

=== search (4) ===
123. FTSChattingConvMultiTabUI
124. FTSMultiAllResultFragment
125. FTSMultiImageResultFragment
126. FTSMultiNormalResultFragment

=== uic.file (2) ===
127. FilePreviewUIC
128. FileQBUIC

=== mvvm (1) ===
129. MvvmChatList

=== utils (2) ===
130. ServiceNotifyFoldTest
131. TopLoadExpReportKt

=== pluginsdk.ui.chat (10) ===
132. ChatFooter
133. ChatFooterBottom
134. ChatFooterPanel
135. AppPanel
136. AppGrid
137. ChattingContent
138. ChattingScrollLayout
139. ChattingUILayout
140. VoiceInputPanel

=== pluginsdk.ui (23 聊天相关) ===
141. VoiceInputLayout
142. VoiceInputLayoutImpl
143. VoiceInputFooter
144. VoiceInputScrollView
145. VoiceInputUI
146. SpeechInputLayout
147. CommonVideoView
148. AbstractVideoView
149. FileSelectorPreviewUI
150. LoadingTipsView
151. ProfileItemView
152. ProfileDescribeView
153. ProfileLabelView
154. ProfileHdHeadImg
155. ProfileMobilePhoneView
156. ProfileEditPhoneNumberView
157. MultiSelectContactView
158. MMPhoneNumberEditText
159. NotCopyUserNameImageView
160. BioHelperUI
161. AutoLoginActivity
162. ChatFooterPanel$RecommendView
163. ChatFooterPanel (重复)

=== 隐式命名混淆关键类 ===
164. nw1.t2 (ChattingAdapter 混淆后)
165. b3 / c / p2 / hb / x9 (ChatFooter 面板组件)
166. d3 / g4 / h4 / lp / qe / qp / sp (Component 混淆后关键类)
```

---

> 以上基于 DexKit 枚举 3440 + 402 + 353 + 1005 类的完整逆向分析。
> 聊天窗口总计 ~5200 个关联类，其中 169 个命名类可用于精准 Hook。

---

# 二十四、微信首页 (LauncherUI) — 主框架深度分析 ★★★

## 24.1 LauncherUI 架构概述

`com.tencent.mm.ui.LauncherUI` 是微信的唯一真正"主页"，管理 **4 个底部 Tab**：

```
微信(聊天) | 通讯录 | 发现 | 我
```

### LauncherUI 完整继承链

```
LauncherUI
  └── MMSecDataFragmentActivity
      └── BaseMvvmFragmentActivity
          └── VASLauncher  ★★★ 主题引擎专属层
              └── MMFragmentActivity
                  ├── GloUIComponentActivity
                  │   └── UIComponentActivity → AppCompatActivity
                  ├── hm5.z2 (混淆接口)
                  ├── hm5.f3 (混淆接口)
                  └── pn5.b (混淆基类)
```

## 24.2 LauncherUI 内部类

| 内部类 | 说明 |
|------|------|
| `LauncherUI$$a` | 核心匿名类 |
| `LauncherUI$1` | 初始化相关 |

## 24.3 底部 Tab 导航栏

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.ui.LauncherUIBottomTabView` | ★★★ 底部 4 Tab 导航栏 View |
| `com.tencent.mm.ui.AbstractTabChildActivity` | Tab 子页 Activity 基类 |
| `com.tencent.mm.ui.AbstractTabChildActivity$AbStractTabFragment` | Tab Fragment |
| `com.tencent.mm.ui.AbstractTabChildPreference` | Tab Preference 基类 |
| `com.tencent.mm.ui.DoubleTabView` | 双 Tab View |

### 4 个 Tab 对应页面

| Tab | 位置 | 主类 | Fragment |
|-----|------|------|----------|
| **微信(聊天)** | 第1个 | `MainUI` / `MainUIView` (会话列表) | `BaseConversationUI$BaseConversationFmUI` |
| **通讯录** | 第2个 | `AddressUI` | `AddressUI$AddressUIFragment` |
| **发现** | 第3个 | `FindMoreFriendsUI` | (本身就是 Fragment) |
| **我** | 第4个 | `HomeUI` | (Fragment 容器) |

## 24.4 Tab 页面 1 — 微信(会话列表) 完整架构

### Activity → Fragment → View 链

```
LauncherUI
  └── Tab 1: 微信
      └── BaseConversationUI (Activity)
          └── BaseConversationUI$BaseConversationFmUI (Fragment)
              ├── MainUI
              ├── MainUIView                    ★ 主视图
              ├── ConversationListView          ★ 会话列表 RecyclerView
              │   └── ConversationAdapter       ★ Adapter
              │       └── MvvmConversationAdapter (MVVM版)
              │           └── MvvmConvList
              ├── ConversationFolderItemView    ★ 折叠会话项
              ├── ConversationUnreadHelper      ★ 未读红点
              ├── BannerHelper                  ★ 顶部 Banner
              │   └── NetWarnBanner
              │   └── TryNewInitBanner
              ├── FolderHelper                  ★ 折叠助手
              ├── InitHelper                    ★ 初始化助手
              ├── RefreshHelper                 ★ 下拉刷新
              ├── ConvExposeHelper              ★ 曝光统计
              ├── EnterpriseFullHeightListView  ★ 企业会话列表
              └── ChatBotConversationTextLine   ★ 聊天机器人文字行
```

### 会话列表变体子类

| 子 Activity | Fragment | 说明 |
|------------|----------|------|
| `BizConversationUI` | - | ★ 公众号会话列表 |
| `ServiceNotifyConversationUI` | - | ★ 服务通知列表 |
| `EnterpriseConversationUI` | `EnterpriseConversationFmUI` | 企业会话列表 |
| `AppBrandServiceConversationUI` | `AppBrandServiceConversationFmUI` | 小程序客服列表 |
| `ConvBoxServiceConversationUI` | `ConvBoxServiceConversationFmUI` | 盒子客服列表 |
| `OpenImKefuServiceConversationUI` | `OpenImKefuServiceConversationFmUI` + `OpenImKefuChattingUIFragment` | 开放客服 |
| `GameLifeConversationUI` | - | 游戏人生 |
| `FinderConversationUI` | - | 视频号会话 |
| `FMessageConversationUI` | - | 好友消息 |

### 会话列表所有命名类 (28 个)

```
BaseConversationUI                               ★★★ 基类
BaseConversationUI$BaseConversationFmUI          ★★ Fragment 基类
MainUI                                           ★ 主 UI 控制器
MainUIView                                       ★ 主 View
ConversationListView                             ★★ 列表 View
ConversationAdapter                              ★★ Adapter
ConversationFolderItemView                       ★ 折叠项
ConversationUnreadHelper                         未读红点
BannerHelper                                     顶部 Banner
NetWarnBanner                                    网络警告 Banner
TryNewInitBanner                                 新初始化 Banner
FolderHelper                                     折叠助手
InitHelper                                       初始化
RefreshHelper                                    刷新
ConvExposeHelper                                 曝光
EnterpriseFullHeightListView                     企业列表
ChatBotConversationTextLine                      机器人文字行
SettingCheckUnProcessWalletConvUI                钱包检查
BizConversationUI                                ★ 公众号列表
ServiceNotifyConversationUI                      ★ 服务通知列表
EnterpriseConversationUI                         企业列表
EnterpriseConversationUI$EnterpriseConversationFmUI
AppBrandServiceConversationUI                    小程序客服
AppBrandServiceConversationUI$AppBrandServiceConversationFmUI
ConvBoxServiceConversationUI                     盒子客服
OpenImKefuServiceConversationUI                  开放客服
OpenImKefuServiceConversationUI$OpenImKefuServiceConversationFmUI
OpenImKefuServiceConversationUI$OpenImKefuChattingUIFragment
```

---

# 二十五、通讯录 Tab — 完整深度分析 ★★★

## 25.1 架构概览

```
LauncherUI
  └── Tab 2: 通讯录
      └── AddressUI (Activity)
          └── AddressUI$AddressUIFragment (Fragment)
              └── MvvmAddressUIFragment (MVVM版)
                  └── AddressLiveList
```

## 25.2 通讯录主界面类

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.ui.contact.AddressUI` | ★★★ 通讯录主 Activity |
| `com.tencent.mm.ui.contact.AddressUI$AddressUIFragment` | ★★ 通讯录 Fragment |
| `com.tencent.mm.ui.contact.address.BaseAddressUIFragment` | ★ 通讯录 Fragment 基类 |
| `com.tencent.mm.ui.contact.address.MvvmAddressUIFragment` | ★ MVVM 通讯录 Fragment |
| `com.tencent.mm.ui.contact.address.AddressLiveList` | ★ 通讯录列表组件 |
| `com.tencent.mm.ui.contact.ContactCountView` | 联系人数量 View |
| `com.tencent.mm.ui.contact.LabelContainerView` | 标签容器 View |
| `com.tencent.mm.ui.contact.CategoryTipView` | 分类提示 View |
| `com.tencent.mm.ui.contact.BizContactEntranceView` | ★ 公众号入口 View |

## 25.3 所有通讯录相关 Activity (61 个命名类)

### 主通讯录变体
| 类名 | 说明 |
|------|------|
| `AddressUI` | ★★★ 通讯录主页 |
| `OpenIMAddressUI` | 开放 IM 通讯录 |
| `OpenIMAddressUI$OpenIMAddressUIFragment` | 开放 IM Fragment |
| `VoipAddressUI` | VoIP 通讯录 |
| `SnsAddressUI` | 朋友圈通讯录 |
| `SnsSelectConversationAddressUI` | SNS 选择会话通讯录 |

### 联系人选择
| 类名 | 说明 |
|------|------|
| `SelectContactUI` | ★★ 选择联系人 |
| `OpenIMSelectContactUI` | 开放 IM 选人 |
| `SelectSpecialContactUI` | 特殊选人 |
| `SelectLabelContactUI` | 标签选人 |
| `GroupCardSelectUI` | 群名片选择 |
| `MMBaseSelectContactUI` | ★ 选联系人基类 |
| `SelectContactsFromRangeUI` | 范围选人 |
| `SendContactCardUI` | 发送名片 |

### 联系人管理
| 类名 | 说明 |
|------|------|
| `ModRemarkNameUI` | ★ 修改备注名 |
| `ContactRemarkInfoModUI` | ★★ 备注信息修改 (12个内部类) |
| `ContactRemarkImagePreviewUI` | 备注图片预览 |
| `ContactSayHiImagePreviewUI` | 打招呼图片预览 |
| `SayHiEditUI` | 打招呼编辑 |
| `OnlyChatContactMgrUI` | 仅聊天联系人管理 |
| `ChatroomContactUI` | 群聊联系人 |

### SNS 联系人
| 类名 | 说明 |
|------|------|
| `SnsSelectConversationUI` | ★ SNS 选择会话 |
| `SnsSelectConversationMemberUI` | SNS 选择成员 |
| `SnsSelectFromConvBoxUI` | SNS 从盒子选择 |
| `SnsLabelContactListUI` | SNS 标签联系人 |
| `SnsTagContactListUI` | SNS 标签列表 |

### 其他
| 类名 | 说明 |
|------|------|
| `DomainMailListPreference` | 域名邮箱列表 |

## 25.4 通讯录子包结构

```
com.tencent.mm.ui.contact (494 类)
├── *.java (根包 ~55 命名类)
│   ├── AddressUI + AddressUIFragment
│   ├── OpenIMAddressUI
│   ├── VoipAddressUI
│   ├── SnsAddressUI
│   ├── SelectContactUI
│   ├── ModRemarkNameUI
│   ├── ContactRemarkInfoModUI (12 内部类)
│   ├── ChatroomContactUI
│   ├── BizContactEntranceView
│   ├── ContactCountView
│   ├── LabelContainerView
│   ├── CategoryTipView
│   └── ... 
├── address/ (4 类)
│   ├── BaseAddressUIFragment     ★ 基类
│   ├── MvvmAddressUIFragment     ★ MVVM
│   └── AddressLiveList           ★ 列表
└── a~z, a0~c7 (混淆 ~430)
```

---

# 二十六、发现 Tab — 完整深度分析 ★★★

## 26.1 架构

```
LauncherUI
  └── Tab 3: 发现
      └── FindMoreFriendsUI (Fragment)
          ├── AbstractTabChildPreference (基类)
          │   └── MMPreferenceFragment
          │       └── MMFragment
          ├── s85.q0 / s85.z0 / e01.x8 / e01.y8 (混淆)
          └── 内部类: $$a, $$b, $2, $4~$10, $17, $18
```

## 26.2 FindMoreFriendsUI 内部结构

| 内部类 | 说明 |
|------|------|
| `FindMoreFriendsUI$$a` | 核心实现 |
| `FindMoreFriendsUI$$b` | 辅助 |
| `FindMoreFriendsUI$2` ~ `$10` | 各项功能监听器 |
| `FindMoreFriendsUI$17` / `$18` | 其他功能 |

## 26.3 发现页各入口项

发现页是一个 Preference 列表，每一项对应一个功能入口：

| 入口 | 点击后跳转 | 说明 |
|------|-----------|------|
| 朋友圈 | `SnsTimeLineUI` | ★ 朋友圈时间线 |
| 视频号 | `FinderHomeUI` / `FinderHomeTabFragment` | 视频号 |
| 扫一扫 | `BaseScanUI` | 扫码 |
| 摇一摇 | `ShakePersonalInfoUI` | 摇一摇 |
| 看一看 | `TopStoryHomeUI` | 看一看 |
| 搜一搜 | `FTSMainUI` | 搜一搜 |
| 直播 | `NearbyLiveSquareTabFragment` | 附近直播 |
| 小程序 | `AppBrandLauncherUI` | 小程序桌面 |
| 游戏 | `GameSearchUI` / 游戏中心 | 游戏 |
| 购物 | WebView | 京东购物 |

## 26.4 发现页辅助类

| 类名 | 说明 |
|------|------|
| `FinderIconViewTipPreference` | 视频号红点提示 |
| `FinderImplIconViewTipPreference` | 视频号实现 |
| `GameIconViewTipPreference` | 游戏红点提示 |
| `FriendSnsPreference` | 朋友圈入口 |
| `FindMoreGameRedLogic` | 游戏红点逻辑 |
| `EnterFindMoreFriendsUIEvent` | 进入发现页事件 |
| `QuitFindMoreFriendsUIEvent` | 退出发现页事件 |
| `FindMoreFriendsUIReporter` | 发现页上报 |

---

# 二十七、"我" Tab — 完整深度分析 ★★★

## 27.1 架构

```
LauncherUI
  └── Tab 4: 我
      └── HomeUI (Fragment)
          ├── 内部类: $$a~$$h, $1~$35
          ├── HomeUITabChangeEvent
          └── ActivityStatus
```

## 27.2 HomeUI 内部类详解

| 内部类 | 说明 |
|------|------|
| `HomeUI$$a` ~ `$$h` | 8 个核心内部实现类 |
| `HomeUI$1` ~ `$35` | 35 个匿名/监听器类 |
| `HomeUI$ActivityStatus` | Activity 状态管理 |

## 27.3 "我"页面包含的功能区域

| 区域 | 对应组件 | 说明 |
|------|----------|------|
| **个人信息头部** | `SettingsPersonalInfoPreviewUI` | 头像+昵称+微信号 |
| **支付** | `WalletPayMainUI` / 钱包 | 微信支付入口 |
| **收藏** | `FavSearchUI` | 收藏 |
| **相册** | `SmartGalleryUI` | 朋友圈相册 |
| **卡包** | `CardIndexUI` | 卡券 |
| **表情** | `EmojiStoreV3HomeUI` | 表情商店 |
| **设置** | `MainSettingsUI` | 设置入口 |
| **视频号** | `FinderProfileUI` | 我的视频号 |
| **状态** | `TextStatusEditActivity` | 状态 |

## 27.4 "我"页面完整类

```
HomeUI                                          ★★★ 主 Fragment
├── HomeUI$$a ~ $$h (8 个核心实现)
├── HomeUI$1 ~ $35 (35 个内部监听器/回调)
├── HomeUI$ActivityStatus
└── HomeUITabChangeEvent                         Tab 切换事件
```

---

# 二十八、公众号/服务号模块 — 完整深度分析 ★★★

## 28.1 架构总览

```
公众号/服务号体系分 3 层:

Layer 1: 会话列表层 (公众号列表)
  └── BizConversationUI → BaseBizConversationUI

Layer 2: 聊天窗口层 (公众号聊天)
  └── BizHalfScreenChattingUI / AppBrandServiceChattingUI
      └── + component.biz.BizComponent
      └── + component.biz.* (46 混淆组件)

Layer 3: 服务通知/品牌服务层
  └── BrandServiceNotifyUI / BrandServiceTimelineUI
```

## 28.2 Layer 1: 公众号会话列表

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.ui.conversation.BizConversationUI` | ★★ 公众号会话列表 |
| `com.tencent.mm.ui.conversation.presenter.BaseBizConversationUI` | ★ 公众号列表基类 |
| `com.tencent.mm.ui.conversation.ServiceNotifyConversationUI` | ★ 服务通知列表 |

## 28.3 Layer 2: 公众号聊天窗口

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.ui.chatting.BizHalfScreenChattingUI` | ★★ 公众号半屏聊天 |
| `com.tencent.mm.ui.chatting.AppBrandServiceChattingUI` | ★ 小程序客服聊天 |
| `com.tencent.mm.ui.conversation.OpenImKefuServiceConversationUI` | 开放客服(含聊天Fragment) |
| `com.tencent.mm.ui.chatting.component.biz.BizComponent` | ★★★ 公众号聊天组件 |
| `com.tencent.mm.ui.chatting.component.biz.a` ~ `z`, `a0`~`m0` | 公众号聊天混淆组件 (45 个) |
| `com.tencent.mm.plugin.brandservice.ui.chatting.component.ChattingBizFansComponent` | ★ 公众号粉丝组件 |

## 28.4 Layer 3: 服务通知/品牌服务

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.ui.brandservice.BrandServiceNotifyUI` | ★★★ 服务通知主页 (23 个类) |
| `com.tencent.mm.ui.brandservice.BrandServiceNotifySettingUI` | ★ 服务通知设置 |
| `com.tencent.mm.ui.brandservice.BrandServiceTimelineUI` | ★ 品牌服务时间线 |
| `com.tencent.mm.ui.brandservice.a` ~ `r` | 混淆子类 (18 个) |

## 28.5 品牌服务 (Plugin BrandService) — 完整类

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.plugin.brandservice.PluginBrandService` | ★ 品牌服务插件入口 |
| `com.tencent.mm.plugin.brandservice.ui.BrandServiceIndexUI` | ★★ 品牌服务首页 |
| `com.tencent.mm.plugin.brandservice.ui.BrandServiceLocalSearchUI` | 品牌服务本地搜索 |
| `com.tencent.mm.plugin.brandservice.ui.SearchOrRecommendBizUI` | 搜索/推荐公众号 |
| `com.tencent.mm.plugin.brandservice.ui.ReceiveTemplateMsgMgrUI` | 模板消息管理 |
| `com.tencent.mm.plugin.brandservice.ui.BizPhotoAccountGalleryUI` | 公众号相册 |
| `com.tencent.mm.plugin.brandservice.ui.BizSearchDetailPageUI` | 公众号搜索详情 |
| `com.tencent.mm.plugin.brandservice.ui.BizSearchResultItemContainer` | 搜索结果容器 |
| `com.tencent.mm.plugin.brandservice.ui.EnterpriseBizContactListUI` | 企业号联系人列表 |
| `com.tencent.mm.plugin.brandservice.ui.EnterpriseBizContactListView` | 企业号联系人 View |
| `com.tencent.mm.plugin.brandservice.ui.EnterpriseBizContactPlainListUI` | 企业号简约列表 |
| `com.tencent.mm.plugin.brandservice.ui.EnterpriseBizEntranceListUI` | 企业号入口列表 |
| `com.tencent.mm.plugin.brandservice.ui.EnterpriseBizSearchUI` | 企业号搜索 |
| `com.tencent.mm.plugin.brandservice.ui.base.BrandServiceSortView` | ★ 品牌服务排序 View |

## 28.6 品牌服务 Flutter 页面

| 类名 | 说明 |
|------|------|
| `BizFlutterTLFlutterViewActivity` | Flutter Timeline |
| `BizMyWorksFlutterViewActivity` | 我的作品 |
| `BizPortraitFlutterViewActivity` | 竖屏 |
| `BizTextCoverUI` | 文字封面 |
| `BizAwesomeFlutterViewStubActivity` | Awesome Stub |
| `BizAwesomeNormalStubActivity` | Awesome Normal Stub |
| `BizBravoFlutterViewStubActivity` | Bravo Stub |
| `BizBravoNormalStubActivity` | Bravo Normal Stub |
| `BizCoolFlutterViewStubActivity` | Cool Stub |
| `BizCoolNormalStubActivity` | Cool Normal Stub |
| `BizDelightfulFlutterViewStubActivity` | Delightful Stub |

## 28.7 公众号个人中心/Profile

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.plugin.brandservice.ui.profile.BizContactInfoDialogFragment` | ★ 公众号详情弹窗 |
| `com.tencent.mm.plugin.brandservice.ui.profile.BizDragHeaderView` | ★ 拖动头部 View |
| `com.tencent.mm.plugin.brandservice.ui.personalcenter.recentread.BizPCRecentReadUI` | 最近阅读 |
| `com.tencent.mm.plugin.brandservice.ui.personalcenter.recentread.BizPCRecentReadRvUIC` | 最近阅读 UIC |

## 28.8 公众号粉丝管理

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.plugin.brandservice.conversation.ui.BizFansSettingUI` | ★ 粉丝设置 |
| `com.tencent.mm.plugin.brandservice.conversation.ui.BizFansConversationFragment` | 粉丝会话 Fragment |
| `com.tencent.mm.plugin.brandservice.conversation.ui.BizFansConversationListUI` | 粉丝会话列表 |
| `com.tencent.mm.plugin.brandservice.conversation.ui.BizFansGreetBoxFragment` | 粉丝招呼盒 Fragment |
| `com.tencent.mm.plugin.brandservice.conversation.ui.BizFansGreetBoxUI` | 粉丝招呼盒 |
| `com.tencent.mm.plugin.brandservice.conversation.ui.BizFansBlackListUI` | 粉丝黑名单 |
| `com.tencent.mm.plugin.brandservice.conversation.ui.BizFansPrivateMsgScopeSettingUI` | 私信范围设置 |

## 28.9 公众号时间线 (BrandService Timeline)

| 类名 | 说明 |
|------|------|
| `com.tencent.mm.plugin.brandservice.ui.timeline.BizTimeLineSettingUI` | ★ 时间线设置 |
| `com.tencent.mm.plugin.brandservice.ui.timeline.BizTimeLineMusicImp` | 时间线音乐 |
| `com.tencent.mm.plugin.brandservice.ui.timeline.BizTimelineSettingLoadingPreference` | 加载 Preference |
| `com.tencent.mm.plugin.brandservice.ui.timeline.a` ~ `e4` | 混淆 (269 个) |

---

# 二十九、四大 Tab 页面架构对比总结

## 29.1 4 Tab 架构一览

| Tab | 主类 | Fragment | 架构模式 | 子包类数 |
|-----|------|----------|----------|---------|
| 微信 | `BaseConversationUI` | `BaseConversationFmUI` | Activity+Fragment+MVVM | 455 |
| 通讯录 | `AddressUI` | `AddressUIFragment` / `MvvmAddressUIFragment` | Activity+Fragment+MVVM | 494 |
| 发现 | `FindMoreFriendsUI` | (自身就是 Fragment) | PreferenceFragment | ~20 |
| 我 | `HomeUI` | (自身就是 Fragment) | Fragment+35匿名类 | ~71 |

## 29.2 四大页面全命名类索引

### 微信 Tab (28 个命名类)
```
BaseConversationUI
BaseConversationUI$BaseConversationFmUI
MainUI
MainUIView
ConversationListView
ConversationAdapter
MvvmConversationAdapter
MvvmConvList
ConversationFolderItemView
ConversationUnreadHelper
BannerHelper
NetWarnBanner
TryNewInitBanner
FolderHelper
InitHelper
RefreshHelper
ConvExposeHelper
EnterpriseFullHeightListView
ChatBotConversationTextLine
SettingCheckUnProcessWalletConvUI
BizConversationUI
ServiceNotifyConversationUI
EnterpriseConversationUI
EnterpriseConversationUI$EnterpriseConversationFmUI
AppBrandServiceConversationUI
AppBrandServiceConversationUI$AppBrandServiceConversationFmUI
ConvBoxServiceConversationUI
OpenImKefuServiceConversationUI
```

### 通讯录 Tab (61 个命名类)
```
AddressUI
AddressUI$AddressUIFragment
BaseAddressUIFragment
MvvmAddressUIFragment
AddressLiveList
OpenIMAddressUI
VoipAddressUI
SnsAddressUI
SnsSelectConversationAddressUI
SelectContactUI
OpenIMSelectContactUI
SelectSpecialContactUI
SelectLabelContactUI
GroupCardSelectUI
MMBaseSelectContactUI
SelectContactsFromRangeUI
SendContactCardUI
ModRemarkNameUI
ContactRemarkInfoModUI (12 内部类)
ContactRemarkImagePreviewUI
ContactSayHiImagePreviewUI
SayHiEditUI
OnlyChatContactMgrUI
ChatroomContactUI
BizContactEntranceView
ContactCountView
LabelContainerView
CategoryTipView
SnsSelectConversationUI
SnsSelectConversationMemberUI
SnsSelectFromConvBoxUI
SnsLabelContactListUI
SnsTagContactListUI
DomainMailListPreference
```

### 发现 Tab (6 个命名类)
```
FindMoreFriendsUI (18 个内部类)
AbstractTabChildPreference
FinderIconViewTipPreference
FinderImplIconViewTipPreference
GameIconViewTipPreference
FriendSnsPreference
FindMoreGameRedLogic
```

### 我 Tab (1 主类 + 43 内部类)
```
HomeUI ($$a~$$h + $1~$35 + ActivityStatus)
```

---

# 三十、主题 Hook 四大页面完整方案

## 30.1 微信 Tab (会话列表)

```
Layer 1: MainUIView.setBackground()              → 列表背景
Layer 2: ConversationListView                    → 列表项背景
Layer 3: ConversationAdapter.getView()           → 每项颜色
Layer 4: ConversationFolderItemView              → 折叠项
Layer 5: BannerHelper                            → 顶部 Banner
Layer 6: ConversationUnreadHelper                → 未读红点颜色
Layer 7: ChatBotConversationTextLine             → 机器人文字行
```

## 30.2 通讯录 Tab

```
Layer 1: AddressUI.onCreate()                    → 页面背景
Layer 2: AddressUI$AddressUIFragment             → Fragment 背景
Layer 3: MvvmAddressUIFragment                   → MVVM 列表
Layer 4: AddressLiveList                         → 列表项
Layer 5: ContactCountView                        → 数量文字颜色
Layer 6: LabelContainerView                      → 标签容器
Layer 7: BizContactEntranceView                  → 公众号入口
```

## 30.3 发现 Tab

```
Layer 1: FindMoreFriendsUI                       → 页面背景
Layer 2: AbstractTabChildPreference              → 列表项
Layer 3: FriendSnsPreference                     → 朋友圈入口
Layer 4: FinderIconViewTipPreference             → 视频号入口红点
Layer 5: GameIconViewTipPreference               → 游戏入口红点
```

## 30.4 我 Tab

```
Layer 1: HomeUI $$a~$$h                          → 页面背景
Layer 2: HomeUI $1~$35                           → 各区域
Layer 3: SettingsPersonalInfoPreviewUI           → 个人信息头部
```

## 30.5 公众号/服务号

```
Layer 1: BizConversationUI                       → 公众号列表背景
Layer 2: BrandServiceNotifyUI                    → ★ 服务通知主页
Layer 3: BrandServiceTimelineUI                  → ★ 品牌服务时间线
Layer 4: BrandServiceIndexUI                     → ★ 品牌服务首页
Layer 5: BizContactInfoDialogFragment            → 公众号详情弹窗
Layer 6: ChattingBizFansComponent                → 公众号聊天粉丝组件
Layer 7: BizTimeLineSettingUI                    → 时间线设置
```

---

# 附录 F：四大页面统计汇总

| 页面 | 主包 | 关联包 | 命名类数 | 总类数 |
|------|------|------|---------|--------|
| 微信 Tab | `conversation/` | `adapter/`, `banner/` | 28 | 455+ |
| 通讯录 Tab | `contact/` | `address/` | 61 | 494+ |
| 发现 Tab | `ui/` | - | 6 | ~20 |
| 我 Tab | `ui/` | - | 1+43内 | ~71 |
| 公众号列表 | `brandservice/` | `conversation/` | 30+ | 322 |
| 公众号聊天 | - | `component/biz/` | 46 | 840内 |
| 服务通知 | `brandservice/` | - | 23 | 23 |
| 品牌服务 | `brandservice/` | `timeline/` | 18 | 322+ |
| **四大页面合计** | | | **~200+** | **~1700+** |

---

> 以上基于 DexKit 完整枚举 6613(ui) + 494(contact) + 455(conversation) + 336(brandservice) + 269(timeline) + 322(ui子目录) 类的逆向分析。

---

# 三十一、聊天背景 — 全层级深度分析 ★★★

> 本章追踪聊天背景从 View 层 → 数据模型 → 存储/下载 → 设置UI → 应用到聊天窗口的完整链路

## 31.1 聊天背景 6 层架构

```
┌──────────────────────────────────────────────────────────┐
│ Layer 6: 设置/选择 UI                                    │
│   SettingsChattingBackgroundUI                           │
│   SettingsSelectBgUI                                     │
│   SettingGroupChattingBgItem (新版设置项)                 │
├──────────────────────────────────────────────────────────┤
│ Layer 5: 背景存储/管理 (DB 层)                            │
│   h21.j0 (存储管理器)                                    │
│   h21.i0 (背景数据模型/DB Entity)                        │
│   h21.k0 (事件回调)                                      │
│   h21.h0 / h21.d0 / h21.d / h21.e (proto业务处理)        │
├──────────────────────────────────────────────────────────┤
│ Layer 4: 背景下载/服务                                    │
│   h21.g0 (下载服务)                                      │
│   h21.l0 / h21.n0 (枚举/配置)                            │
├──────────────────────────────────────────────────────────┤
│ Layer 3: 背景应用到聊天窗口                               │
│   gg5.g6 (聊天背景渲染器)                                 │
│   gg5.d6 (Lambda适配器)                                  │
│   ve5.g (聊天背景适配器/RecyclerView装饰)                 │
│   BaseChattingUIFragment.onResume() (触发刷新)            │
├──────────────────────────────────────────────────────────┤
│ Layer 2: 聊天窗口 View 层级                               │
│   ChattingImageBGView (背景ImageView) ★★★                │
│   e5 (Matrix缩放Runnable)                                │
│   ChattingAnimFrame (动画Frame)                           │
│   InitCallBackLayout                                     │
├──────────────────────────────────────────────────────────┤
│ Layer 1: 底层渲染                                        │
│   DynamicBackgroundGLSurfaceView (动态背景 OpenGL)        │
│   GradientColorBackgroundView (纯色渐变背景)              │
│   DynamicBackgroundNative (Native渲染)                    │
│   DefaultDynamicBgServiceImpl                             │
│   DynamicBgServiceImpl (任务栏动态背景)                    │
└──────────────────────────────────────────────────────────┘
```

## 31.2 Layer 2: 聊天窗口 View 层级 — 核心渲染层

### 31.2.1 ChattingImageBGView ★★★

这是聊天背景的**最核心 View**，继承自 `ImageView`，直接显示背景图片。

**包路径**: `com.tencent.mm.ui.chatting.ChattingImageBGView`

**方法**:

| 方法 | 说明 |
|------|------|
| `<init>(Context, AttributeSet)` | 构造函数1 |
| `<init>(Context, AttributeSet, int)` | 构造函数2 |
| `onLayout(boolean, int, int, int, int)` | 布局回调 |
| `setImageBitmap(Bitmap)` | ★★ **设置背景图** — 核心Hook点 |

**`setImageBitmap()` 源码逻辑**:
```java
public void setImageBitmap(Bitmap bitmap) {
    this.d = bitmap;                    // 缓存 bitmap 引用
    super.setImageBitmap(bitmap);       // 调用父类 ImageView
    post(new e5(this));                 // 异步计算 Matrix 缩放裁剪
}
```

### 31.2.2 e5 (Matrix 缩放 Runnable)

**包路径**: `com.tencent.mm.ui.chatting.e5`

**作用**: 在 `ChattingImageBGView.setImageBitmap()` 内部 post 执行，计算背景图的缩放矩阵，确保图片**等比例放大覆盖全屏**（类似 CSS `background-size: cover`）。

```java
public void run() {
    Bitmap bitmap = this.d.d;  // ChattingImageBGView.d
    Matrix matrix = new Matrix();
    float w = viewWidth / bitmapWidth;
    float h = viewHeight / bitmapHeight;
    if (w > h) {
        matrix.setScale(w, w);              // 宽度比例更大，按宽度缩放
    } else {
        matrix.setScale(h, h);              // 高度比例更大，按高度缩放
        matrix.postTranslate(centeringX, 0); // 居中偏移
    }
    chattingImageBGView.setImageMatrix(matrix);
}
```

### 31.2.3 聊天窗口完整 View 树

```
BaseChattingUIFragment (Fragment)
└── onCreateView() → Inflate 聊天布局
    ├── ChattingImageBGView           ★ 最底层：聊天背景图片
    │   └── [背景图片 Bitmap]
    │
    ├── ChattingAnimFrame             ★ 动画覆盖层
    │
    ├── MMChattingListView            ★ 消息列表 RecyclerView
    │   ├── [消息Item View...]
    │   │   ├── BubbleCornorLayout    (气泡背景)
    │   │   ├── ChattingAvatarImageView (头像)
    │   │   └── TextView / ImageView  (消息内容)
    │   └── ...
    │
    ├── [中间层 View]
    │
    ├── InitCallBackLayout
    │
    └── ChatFooter                    ★ 最顶层：输入栏
        ├── ChatFooterBottom          (+号/语音切换)
        ├── ChatFooterPanel           (+号面板)
        └── VoiceInputLayout          (语音按钮)
```

### 31.2.4 ChattingAnimFrame

**包路径**: `com.tencent.mm.ui.chatting.ChattingAnimFrame`

聊天窗口的动画容器，位于背景层上方、消息列表下方，用于全屏动画效果。

### 31.2.5 InitCallBackLayout

**包路径**: `com.tencent.mm.ui.chatting.InitCallBackLayout`

初始化回调布局，在聊天窗口初始化完成后触发回调，用于延迟加载某些 UI 元素。

---

## 31.3 Layer 3: 背景应用到聊天窗口 — 渲染器层

### 31.3.1 gg5.g6 (聊天背景渲染器)

**包路径**: `gg5.g6`

**方法**:

| 方法 | 说明 |
|------|------|
| `G(LayoutInflater, View)` | 创建/绑定背景 View |
| `a()` | 状态检查 |
| `b(dg5.a)` | 获取背景数据 |
| `d(fd5.d, ye5.d, String, dg5.y0)` | ★ 核心渲染方法 |
| `j(e9, Context, fd5.q, fd5.a)` | 从存储加载背景 |
| `m(g0, fd5.d, ye5.d, String)` | 应用到某条消息 |

### 31.3.2 gg5.d6 (Lambda 适配器)

**包路径**: `gg5.d6`

`invoke(Object): Object` — 将背景渲染逻辑包装为 Lambda，在消息渲染时调用。

### 31.3.3 ve5.g (聊天背景 RecyclerView 装饰器)

**包路径**: `ve5.g`

**方法**: `c`, `d`, `f`, `g`, `h`, `j`, `k`, `l`, `m`

在 RecyclerView 的消息项渲染过程中注入背景装饰。

### 31.3.4 BaseChattingUIFragment — 背景刷新触发

| 方法 | 说明 |
|------|------|
| `onResume()` | ★ 恢复时重新加载背景 |
| `onCreateView()` | 创建时初始化背景 |
| `onConfigurationChanged()` | 屏幕旋转时更新背景缩放 |

---

## 31.4 Layer 4 & 5: 背景存储/管理/下载 — 数据层

### 31.4.1 h21.j0 (背景存储管理器) ★★★

**包路径**: `h21.j0`

这是聊天背景的**数据库存储管理器**，管理所有背景资源的 CRUD。

| 方法 | 说明 |
|------|------|
| `<init>(s85.k0)` | 构造函数(依赖注入) |
| `F0(int, int)` | 获取背景文件路径 |
| `H0(int, int)` | ★ 查询指定聊天背景数据 |
| `I0(int)` | ★ 获取某聊天所有背景 |
| `N0(h21.i0)` | 插入背景记录 |
| `Q0(h21.i0)` | 更新背景记录 |
| `t0(int)` | 删除指定背景 |
| `x0(int)` | 是否存在 |
| `y0(String, boolean)` | 路径转换 |
| `z0(int, int)` | 获取缩略图路径 |

### 31.4.2 h21.i0 (背景数据模型 / DB Entity) ★★★

**包路径**: `h21.i0`

每条聊天背景记录的数据模型，对应数据库中的一行。

| 方法 | 说明 |
|------|------|
| `a(Cursor)` | 从数据库游标反序列化 |
| `b()` | 序列化为 ContentValues |

**字段 (从 DB Cursor 推断)**:
- 背景 ID
- 聊天会话 ID (哪个聊天)
- 背景图片路径
- 缩略图路径
- 设置时间
- 是否全局背景

### 31.4.3 h21 包完整类列表 (46 个类)

| 类名 | 说明 |
|------|------|
| `h21.j0` | ★★★ 存储管理器 (12方法) |
| `h21.i0` | ★★★ 数据模型 (2方法) |
| `h21.k0` | ★ 事件回调监听 |
| `h21.h0` | Proto 业务处理 (compareContent/op) |
| `h21.d0` | Proto 业务处理 |
| `h21.d` | Proto 业务处理 |
| `h21.e` | Proto 业务处理 |
| `h21.g0` | ★ 背景下载服务 |
| `h21.l0` | 配置/辅助 |
| `h21.n0` | 枚举值 (背景类型枚举) |
| `h21.h` | 配置 |
| `h21.l` | 配置 |
| `h21.f0` | 辅助 |
| `h21.c0` | 辅助 |
| `h21.a0` | 辅助 |
| `h21.b0` | 辅助 |
| `h21.e0` | 辅助 |
| `h21.m0` | 辅助 |
| `h21.o0` | 辅助 |
| `h21.p0` | 辅助 |
| `h21.q0` | 辅助 |
| `h21.r0` | 辅助 |
| `h21.s0` | 辅助 |
| `h21.t0` | 辅助 |
| `h21.f` ~ `h21.z` (20混淆) | 辅助 |
| `h21.a` ~ `h21.c` | 辅助 |

### 31.4.4 h21.n0 (背景类型枚举)

推断可能的枚举值:
- `GLOBAL` — 全局默认背景
- `PRESET` — 预设背景（微信自带）
- `CUSTOM` — 自定义背景（用户相册选择）
- `DOWNLOAD` — 下载的背景（背景商店）

---

## 31.5 Layer 1: 底层渲染 — 动态背景/OpenGL

### 31.5.1 DynamicBackgroundGLSurfaceView

**包路径**: `com.tencent.mm.dynamicbackground.view.DynamicBackgroundGLSurfaceView`

使用 **OpenGL ES** 渲染动态背景效果（粒子、星空等动画背景）。

### 31.5.2 GradientColorBackgroundView ★

**包路径**: `com.tencent.mm.dynamicbackground.view.GradientColorBackgroundView`

**纯色/渐变色背景 View**。

| 方法 | 说明 |
|------|------|
| `<init>(Context)` | 构造 |
| `a(boolean)` | 设置状态 |
| `b()` | 重置 |
| `onDraw(Canvas)` | ★ 绘制渐变色 |
| `setUpdateMode(int)` | 设置更新模式 |

### 31.5.3 GameGLSurfaceView

**包路径**: `com.tencent.mm.dynamicbackground.view.GameGLSurfaceView`

游戏场景 OpenGL 渲染（游戏聊天室背景）。

### 31.5.4 DynamicBackgroundNative

**包路径**: `com.tencent.mm.dynamicbackground.model.DynamicBackgroundNative`

Native 层动态背景渲染（C++ 实现）。

### 31.5.5 DefaultDynamicBgServiceImpl

**包路径**: `com.tencent.mm.dynamicbackground.model.DefaultDynamicBgServiceImpl`

默认动态背景服务实现。

### 31.5.6 DynamicBgServiceImpl

**包路径**: `com.tencent.mm.plugin.taskbar.ui.dynamicbackground.DynamicBgServiceImpl`

任务栏场景的动态背景实现。

### 31.5.7 DynamicBackgroundRenderResult

**包路径**: `com.tencent.mm.dynamicbackground.model.DynamicBackgroundRenderResult`

动态背景渲染结果数据类。

---

## 31.6 Layer 6: 背景设置 UI — 用户操作层

### 31.6.1 SettingsChattingBackgroundUI ★★★

**包路径**: `com.tencent.mm.plugin.setting.ui.setting.SettingsChattingBackgroundUI`

聊天背景设置**主页面**。

| 方法 | 说明 |
|------|------|
| `onCreate()` | 初始化 |
| `initView()` | 构建 UI |
| `onPreferenceTreeClick()` | ★ 点击背景选项 |
| `onActivityResult()` | 选择图片回调 |
| `getResourceId()` | 资源 ID |
| `R6()` | 获取背景列表 |
| `T6(boolean)` | 获取文字描述 |
| `U6()` | 是否可用 |
| `V6()` | 刷新 |

**页面包含的选项**:
- 选择背景图 (从相册)
- 从背景商店选择
- 拍一张
- 应用到所有聊天
- 恢复默认背景

### 31.6.2 SettingsSelectBgUI ★★

**包路径**: `com.tencent.mm.plugin.setting.ui.setting.SettingsSelectBgUI`

背景图片**选择/预览页面**。

| 方法 | 说明 |
|------|------|
| `onCreate()` | 初始化预览 |
| `initView()` | 构建网格/列表 |
| `N6(SettingsSelectBgUI, int)` | 选中背景 |
| `O6(List)` | 加载背景列表 |
| `onSceneEnd()` | 网络请求回调 |

### 31.6.3 SettingGroupChattingBgItem ★

**包路径**: `com.tencent.mm.plugin.setting.ui.setting_new.settings.chatting.SettingGroupChattingBgItem`

新版设置中的聊天背景设置项。

| 方法 | 说明 |
|------|------|
| `V7()` | 获取标题 |
| `W7()` | 获取副标题(当前背景描述) |
| `l7()` | 获取跳转目标 |
| `m7()` | 获取图标 |
| `o7()` | 布局 ID |
| `p7()` | 目标 Activity Class |
| `w7()` | 类型 |

### 31.6.4 SettingSnsBackgroundUI

**包路径**: `com.tencent.mm.plugin.sns.ui.SettingSnsBackgroundUI`

朋友圈背景设置（独立于聊天背景）。

### 31.6.5 其他聊天设置项

| 类名 | 说明 |
|------|------|
| `SettingGroupChatting` | 聊天设置分组 (包含 BgItem) |
| `SettingSwitchChatAutoSync` | 聊天记录自动同步 |
| `SettingSwitchChatSaveViewed` | 保存已查看 |
| `SettingSwitchCustomSendBtn` | 自定义发送按钮 |
| `SettingSwitchGallerySearch` | 图库搜索 |
| `SettingSwitchVoicePlayMode` | 语音播放模式 |

---

## 31.7 聊天背景完整数据流

```
用户操作 (设置页面)
    │
    ▼
SettingsChattingBackgroundUI / SettingsSelectBgUI
    │ 选择背景图
    ▼
h21.j0 (存储管理器)
    │ H0()/N0()/Q0() → 数据库
    ▼
h21.i0 (数据模型 → SQLite)
    │
    ▼
BaseChattingUIFragment.onResume()
    │ 触发背景刷新
    ▼
gg5.g6 (背景渲染器)
    │ G()/j()/d() → 加载 & 绑定
    ▼
ChattingImageBGView.setImageBitmap()
    │ post(new e5(this))
    ▼
e5.run() → Matrix 缩放
    │
    ▼
屏幕渲染 (背景图片显示在聊天窗口最底层)
```

---

## 31.8 主题 Hook — 聊天背景全层方案

### Hook 方案总览

| 层级 | Hook 目标 | Hook 方法 | 效果 |
|------|----------|----------|------|
| View 层 | `ChattingImageBGView` | `setImageBitmap()` | ★★★ 替换背景图 |
| View 层 | `ChattingImageBGView` | `<init>()` | 修改 ScaleType |
| View 层 | `e5` | `run()` | 修改缩放算法 |
| 渲染层 | `gg5.g6` | `G()` / `d()` | 注入自定义背景 |
| 渲染层 | `ve5.g` | `c()` / `g()` | 注入 RecyclerView 装饰 |
| 渲染层 | `BaseChattingUIFragment` | `onResume()` | 全局背景刷新 |
| 数据层 | `h21.j0` | `H0()` | 拦截背景查询 |
| 数据层 | `h21.j0` | `N0()` / `Q0()` | 拦截背景保存 |
| 设置UI | `SettingsChattingBackgroundUI` | `onCreate()` | 修改设置页背景色 |
| 设置UI | `SettingGroupChattingBgItem` | `W7()` | 修改副标题文字 |
| 底层 | `GradientColorBackgroundView` | `onDraw()` | 修改默认渐变色 |
| 底层 | `DynamicBackgroundGLSurfaceView` | 渲染回调 | 注入自定义动态背景 |

### 推荐全局主题背景 Hook

```bsh
// 方案1: 直接替换背景 Bitmap (最简单)
hookMethodAfter("com.tencent.mm.ui.chatting.ChattingImageBGView",
    "setImageBitmap",
    param -> {
        // 用主题图片替换原始 bitmap
        Bitmap themeBg = loadThemeBitmap();
        if (themeBg != null) {
            param.setResult(null); // 阻止原始设置
            // 重新调用 setImageBitmap(themeBg)
        }
    }
);

// 方案2: Hook gg5.g6 渲染器 (更底层)
findMethod().inClass("gg5.g6").name("G").hook(...);

// 方案3: Hook BaseChattingUIFragment.onResume (全局)
findMethod().inClass("com.tencent.mm.ui.chatting.BaseChattingUIFragment")
    .name("onResume").hook(...);
```

---

## 31.9 聊天背景类统计

| 层 | 包/路径 | 类数 | 核心类 |
|---|------|------|------|
| View 渲染 | `chatting/` | 5 | ChattingImageBGView, e5, ChattingAnimFrame, InitCallBackLayout |
| 渲染器 | `gg5/` + `ve5/` | ~15 | gg5.g6, gg5.d6, ve5.g |
| 数据存储 | `h21/` | 46 | j0, i0, k0, h0, d0, d, e, g0 |
| 底层渲染 | `dynamicbackground/` | 7 | GradientColorBackgroundView, DynamicBackgroundGLSurfaceView, GameGLSurfaceView |
| 设置 UI | `setting/` | 3 | SettingsChattingBackgroundUI, SettingsSelectBgUI, SettingGroupChattingBgItem |
| 其他 | `setting_new/`, SNS | 6+ | SettingGroupChatting, SettingSnsBackgroundUI |
| **总计** | | **~82** | **20+ 命名类** |

---

## 31.10 聊天背景完整命名类索引

```
=== View 层 (5) ===
1.  ChattingImageBGView                  com.tencent.mm.ui.chatting.ChattingImageBGView
2.  e5 (Matrix Runnable)                com.tencent.mm.ui.chatting.e5
3.  ChattingAnimFrame                    com.tencent.mm.ui.chatting.ChattingAnimFrame
4.  InitCallBackLayout                   com.tencent.mm.ui.chatting.InitCallBackLayout
5.  BaseChattingUIFragment (触发刷新)    com.tencent.mm.ui.chatting.BaseChattingUIFragment

=== 渲染器层 (3+) ===
6.  gg5.g6 (背景渲染器)                 gg5.g6
7.  gg5.d6 (Lambda适配器)               gg5.d6
8.  ve5.g (RecyclerView装饰器)          ve5.g

=== 数据存储层 (46, 关键6个) ===
9.  h21.j0 (存储管理器 ★)               h21.j0
10. h21.i0 (数据模型 ★)                 h21.i0
11. h21.k0 (事件回调)                    h21.k0
12. h21.h0 (Proto处理)                  h21.h0
13. h21.g0 (下载服务)                   h21.g0
14. h21.n0 (背景类型枚举)               h21.n0

=== 底层渲染 (7) ===
15. GradientColorBackgroundView          com.tencent.mm.dynamicbackground.view.GradientColorBackgroundView
16. DynamicBackgroundGLSurfaceView       com.tencent.mm.dynamicbackground.view.DynamicBackgroundGLSurfaceView
17. GameGLSurfaceView                    com.tencent.mm.dynamicbackground.view.GameGLSurfaceView
18. DynamicBackgroundNative              com.tencent.mm.dynamicbackground.model.DynamicBackgroundNative
19. DefaultDynamicBgServiceImpl          com.tencent.mm.dynamicbackground.model.DefaultDynamicBgServiceImpl
20. DynamicBgServiceImpl                 com.tencent.mm.plugin.taskbar.ui.dynamicbackground.DynamicBgServiceImpl
21. DynamicBackgroundRenderResult        com.tencent.mm.dynamicbackground.model.DynamicBackgroundRenderResult

=== 设置 UI (3) ===
22. SettingsChattingBackgroundUI         com.tencent.mm.plugin.setting.ui.setting.SettingsChattingBackgroundUI
23. SettingsSelectBgUI                   com.tencent.mm.plugin.setting.ui.setting.SettingsSelectBgUI
24. SettingGroupChattingBgItem           com.tencent.mm.plugin.setting.ui.setting_new.settings.chatting.SettingGroupChattingBgItem

=== 相关设置 (2) ===
25. SettingGroupChatting                 com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupChatting
26. SettingSnsBackgroundUI               com.tencent.mm.plugin.sns.ui.SettingSnsBackgroundUI
```

---

> 本章基于 DexKit 完整枚举 h21(46类) + gg5(311类) + dynamicbackground(7类) + chatting(~50类) 的深度逆向分析。
> 聊天背景系统共 ~82 个关联类，其中 26 个命名类可直接 Hook。
