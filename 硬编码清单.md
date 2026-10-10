# 项目硬编码清单（供反编译分析）

> 生成日期：2026-10-09
> 项目：LeShaoWeChat V3（微信 8.0.78/3180）
> 范围：`app/src/main/java` 全部 179 个 Java/Kotlin 源文件，只读核对。
> 分类：A 网络URL / B 文件路径 / C 微信类名硬编码 / D 方法字段名硬编码 / E 数字常量 / F 配置键 / G 无明文密钥说明

---

## A. 网络 URL / API 端点硬编码

| 文件 | URL | 用途 |
|---|---|---|
| `service/CubeTtsPlayer.kt:302` | `https://peiyinmofang.com/api/open/v1/tts/simple-generate` | 配音 TTS 生成 |
| `ui/TTSPageView.kt:107` | `https://peiyinmofang.com` | 配音魔方页面/接口基址 |
| `ui/TTSPageView.kt:1389` | `https://peiyinmofang.com/api/open/v1/me` | 账号信息 |
| `ui/TTSPageView.kt:1431` | `https://peiyinmofang.com/api/open/v1/voices` | 音色列表 |
| `hook/TtsVoiceSender.java:4430` | `https://peiyinmofang.com/api/open/v1/tts/simple-generate` | 同 CubeTtsPlayer |
| `hook/TtsVoiceSender.java:3025` | `https://u.y.qq.com/cgi-bin/musicu.fcg` | QQ音乐接口 |
| `hook/TtsVoiceSender.java:3033-3034` | `https://y.qq.com/`（Referer/Origin） | QQ音乐来源 |
| `music/KuwoMusicApi.kt:120` | `https://music.nxinxz.com/kw.php` | 酷我代理 |
| `music/KuwoMusicApi.kt:131/163/190/224/253/266/294` | `https://search.kuwo.cn/r.s?...` | 酷我搜索/推荐/专辑 |
| `music/KuwoMusicApi.kt:311` | `https://wapi.kuwo.cn/api/pc/bang/list` | 榜单 |
| `music/KuwoMusicApi.kt:342` | `https://kbangserver.kuwo.cn/ksong.s?...` | 电台歌曲 |
| `music/KuwoMusicApi.kt:353` | `https://nplserver.kuwo.cn/pl.svc?...` | 歌单详情 |
| `music/KuwoMusicApi.kt:381/406/410` | `https://wapi.kuwo.cn/api/pc/classify/playlist/...` | 分类歌单 |
| `music/KuwoMusicApi.kt:438/467` | `https://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=` | 歌曲信息/歌词 |
| `music/KuwoMusicApi.kt:492/665` | `https://img4.kuwo.cn/star/albumcover/1080` | 封面图 |
| `ai/hook/wechat/AIBotCore.java:279` | `https://api.anthropic.com` | Anthropic 默认基址 |
| `ai/api/anthropic/AnthropicClient.kt:161` | `https://api.anthropic.com` | Anthropic 默认基址 |
| `ai/ui/activity/SettingsActivity.kt:91` | 提示文案 `如 https://api.deepseek.com` | DeepSeek 地址示例 |
| `ui/MainActivity.kt:1070/1076` | `https://work.weixin.qq.com/ca/cawcde22ff06beab20` | 企业微信客服 |
| `hook/AutoGroupQrHook.java:73-78` | `https://weixin.qq.com/g/`、`http://weixin.qq.com/g/`、`https://c.weixin.com/g/`、`http://c.weixin.com/g/`、`https://work.weixin.qq.com/gm/`、`https://work.weixin.qq.com/m/` | 群二维码前缀判定 |
| `hook/FaceScanHook.java:360` | `https://weixin.qq.com/g/`、`weixin://qr/`、`wxp://` | 群码保护判定 |
| `hook/RedPacketHook.java` | `/cgi-bin/mmpay-bin/openwxhb`、`/cgi-bin/mmpay-bin/receivewxhb` | 红包 cgi 路径 |

---

## B. 文件路径硬编码

| 文件 | 路径 | 用途 |
|---|---|---|
| `PathUtil.kt:30` | `/data/data/com.tencent.mm/files` | 微信数据根 |
| `AvatarHelper.kt:261` | `/data/user/{uid}/com.tencent.mm/MicroMsg/` | 头像目录 |
| `MainActivity.kt:289` | `/data/user/0/{baseDir}/...` | 数据目录 |
| `VoiceAutoPlay.java:787` | `{voice2Dir}/{md5[0:2]}/{md5[2:4]}/msg_{clean}.amr` | 语音文件路径规则 |
| `VoiceAutoPlay.java:1055-1067` | `/data/user/{user}/com.tencent.mm/MicroMsg`、`/data/user/0/com.tencent.mm/MicroMsg` | 多用户语音根 |
| `TtsVoiceSender.java:615/628/652` | `/data/user/{uid}/com.tencent.mm/MicroMsg/`、`/data/data/` 替换 | 微信数据目录 |
| `TtsVoiceSender.java:1141` | `{accPath}/voice2/{talker}/msg_{msgId}.amr` | 语音落地路径 |
| `TtsVoiceSender.java:2957` | `{cacheDir}/music_card` | 音乐卡片缓存 |
| `ContactRepository.kt:236` | `{baseDir}MicroMsg/{dbHash}/EnMicroMsg.db` | 微信数据库路径 |

---

## C. 微信类名硬编码汇总

### C1. 短混淆类名（升级必变，强烈建议锚点化）
详见 `缺失DexKit锚点清单.md`，核心如下：

| 类名 | 使用文件 | 有无锚点 |
|---|---|---|
| `wk5.g0/n/o/l0` | AutoGroupQrHook | ⚠️ 仅 g0 有 |
| `v16.a` | AutoGroupQrHook | ✅ ScanImageUtil |
| `v74.v` | AutoGroupQrHook/FaceScanHook | ✅ QBarStringHandler |
| `s6` | AutoGroupQrHook | ❌ **类名待反编译确认** |
| `un.k` `ln.a` `pe5.f` `qn.m` `vn.m` | AutoGroupQrHook | ❌ |
| `ph5.n0` `rn3.u0` `pe3.a` `ex0.k0` | AutoGroupQrHook | ❌ |
| `b41.y1` | AutoGroupQrHook/StorageHub/BatchInviteManager | ❌ |
| `gp0.j1` | 多处 | ✅ getJ1ServiceClass |
| `hm0.j1` `e01.v1` `d24.h` `kn.x` | WmReflect | ❌ |
| `jo5.f` `po5.u` | ConversationFilter | ❌ |
| `aa3.d` | LabelSyncHook | ❌ |
| `ph5.n0` `lj0.a3` | RedPacketHook | ❌ |
| `dm.c8` `b31.w` | TtsVoiceSender | ❌ |
| `ou5.c1` `xp3.i` `dx0.r` `yp3.b` `vp3.e` `v51.r0` | WeChatMessenger | ❌ |
| `b41.h9` `b41.e` | ContactRepository/StorageHub | ⚠️ 部分有 getContactStorageClass |
| `qe5.b` | BatchInviteManager | ⚠️ 有 TAG_ADD_MEMBER |
| `x93.r` `com.tencent.mm.storage.c4` | ChatGroupHook | ❌ |
| `com.tencent.mm.storage.f9` | 多处 | ❌ **高频** |
| `com.tencent.mm.storage.e9` | 多处 | ✅ getE9ClassName |
| `com.tencent.mm.modelavatar.d1` | AvatarHelper | ✅ getAvatarHelperClass |

### C2. 全名微信 UI 类（未混淆，相对稳定）
`com.tencent.mm.ui.chatting.ChattingUI`、`ChattingUIFragment`、`BaseChattingUIFragment`、`com.tencent.mm.ui.LauncherUI`、`com.tencent.mm.ui.conversation.MainUI`、`com.tencent.mm.ui.conversation.s5`、`com.tencent.mm.ui.chatting.adapter.k`、`com.tencent.mm.ui.chatting.component.so`、`com.tencent.mm.ui.chatting.x0`、`com.tencent.mm.modelvoice.MediaRecorder`、`com.tencent.mm.pluginsdk.ui.chat.ChatFooter`、`com.tencent.mm.plugin.fav.ui.fc`、`com.tencent.mm.ui.base.AnimImageView`、`com.tencent.mm.ui.widget.MMNeat7extView`、`com.tencent.mm.ui.bk`、`com.tencent.mm.plugin.msgquote.model.MsgQuoteItem`、`com.tencent.mm.pluginsdk.model.app.k0`、`com.tencent.mm.plugin.scanner.ImageQBarDataBean`、`com.tencent.mm.roomsdk.model.factory.c`、`com.tencent.mm.modelsimple.k0`、`com.tencent.mm.protocal.protobuf.SnsObject`、`com.tencent.mm.plugin.sns.storage.l1`

### C3. Android/框架类（无需锚点）
`android.app.ActivityThread`、`android.app.Activity`、`android.content.res.Resources`、`androidx.recyclerview.widget.RecyclerView`、`kotlin.Result`、`com.tencent.mars.xlog.Log` 等。

---

## D. 方法/字段名硬编码（高频 TOP）

| 方法/字段名 | 频次 | 说明 |
|---|---|---|
| `d` `b` `f` `a` `g` `e` `i` `c` `m` `h` | 极高 | 微信单字母混淆字段/方法 |
| `N0` | 10 | 消息对象 getTalker |
| `H0` | 11 | 消息对象 getMsgId |
| `getType` | 15 | 类型 |
| `getMsgId` | 12 | 消息 ID |
| `z0` | 14 | 群/消息字段 |
| `field_talker` `field_content` `field_type` `field_labelName` `field_labelID` `field_attrBuf` | 中 | 数据库字段 |
| `getView` `getLocalid` `getTalkerUserName` `getSnsId` `getContent` `getUserName` | 中 | 常用 getter |
| `notifyDataSetChanged` | 7 | 列表刷新 |
| `LikeUserList` `LikeUserListCount` `LikeCount` | 中 | 朋友圈点赞 |
| `t1` `i1` `k1` `j1` `hj` `x0` `v` `p0` `e1` `cj` | 中 | 混淆方法 |
| `Bb` `N3` `X0` `L1` `A1` `yb` | 低 | 消息发送/修改 |
| `n0` `getPlayer` `getVoicePlayer` `o0` `k0` `I0` `M0` | 低 | 语音播放器 |

> 单字母混淆方法/字段名只能在锚点类定位成功后反射调用，**无法单独用 DexKit 字符串锚点**；需要的是"类锚点"。

---

## E. 数字/时间常量硬编码

| 值 | 用途 |
|---|---|
| `1000`（27处） | 毫秒换算/延时 |
| `3000` / `5000` | 超时/重试 |
| `60000` | 轮询超时（AutoGroupQrHook） |
| `0xFFFFFFFF` | 全 F 掩码 |
| `0xFF07C160` `0xFF1B1218` `0xFFD0E8D4` `0xFF3DD68C` 等 | 主题色 ARGB |
| `0x42000031` `0x11000031` `0x2BFF` `0xFE0F` | 状态/标志位 |
| `30257` | 当前 versionCode（构建配置+源码 DexKitHelper 同步） |

---

## F. 配置键硬编码（SharedPreferences）

模块自身配置键（不属于微信反编译需求，但列出类型）：
- `ls_peiyin_apikey` / `ls_ark_apikey`（API Key 存储）
- `ls_face_scan_fake`、`ls_wp_blockupdate`、`ls_msgforge_*`、`ls_wx_forward_replace`、`ls_plus_orig_count`、`ls_auto_group_qr_*` 等模块开关
- 微信 `com.tencent.mm_preferences`、`notify_sync_pref`、`auth_info_key_prefs`、`auth_uin`、`username`、`uin`、`_auth_uin` 等（读取微信会话/登录态）

---

## G. 无明文密钥说明

- **没有发现硬编码的私密密钥/Token**。所有 API Key（配音魔方、AI 模型）均从 `SharedPreferences`/`WmPrefs` 读取（`ModuleConfig`、`WmPrefs`），用户自行填写。
- 签名口令在 `local.properties`（`LESHAO_STORE_PASSWORD=leshao2024` 等），属于**构建配置**，不打包进 APK，且 `.gitignore` 已忽略。
- `ContactRepository.kt:237` 的 `md5(imei+uin)` 是微信数据库解密密码的**算法**（非固定密钥），用于本地打开 `EnMicroMsg.db`。

---

## 二次复检记录

- ✅ 复核：`com.tencent.mm.storage.e9` 有锚点（getE9ClassName），未误判。
- ✅ 复核：`gp0.j1` 有 `getJ1ServiceClass` 锚点，但部分文件仍直接硬编码（兜底），已标注 ⚠️。
- ✅ 复核：`p06` 有 `getP06ClassName` 锚点。
- ✅ 复核：`ph5.n0` 在 BatchInviteManager 中被 `getServiceLocatorClass` 覆盖；在 AutoGroupQrHook/RedPacketHook 中**无**锚点，未误判为有。
- ✅ 复核：`s6` 类名在运行时报错（s6.a.a 调用失败），确认为**缺失且可能错误**的类名，已标注最高优先。
- ✅ 复核：未发现任何 `findAndHookMethod("字符串类名", ...)` 形式（全部用 Class 对象），避免重复。
- ✅ 复核：未发现硬编码的本地端口/Socket/服务器 IP。