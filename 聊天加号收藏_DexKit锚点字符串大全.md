# 聊天窗口"+"→ 收藏（语音转发）DexKit 锚点字符串大全

> 全部字符串均来自对本版本微信的**实测反编译**（非推测）。
> 用法：DexKit `usingStrings(...)` 命中方法 → 取其 `declaringClassName`；字段名用 `findField{name(...)}`。
> 每个锚点都给了「当前版本类名」作兜底：DexKit 命中就用命中的，没命中用兜底名，两条路都能跑。

---

## A. 入口组（聊天 + → 收藏）

| # | 精确字符串 | 命中方法 | 得到类 | DexKit 查询 |
|---|---|---|---|---|
| A1 | `chat_attachment_item_favorite` | `k()` | `com.tencent.mm.ui.chatting.v4`（ChattingFooterEventImpl，点收藏的组件） | findMethod{usingStrings}→declaringClass |
| A2 | `.ui.FavSelectUI` | `k()` | 同上 | 同 A1 |
| A3 | `fav.ui.FavSelectUI` | `k()` | 同上 | 同 A1（LaunchParam 用的类名字符串） |
| A4 | `key_to_user` | onCreate/onItemClick | `com.tencent.mm.plugin.fav.ui.FavSelectUI` | findMethod{usingStrings}→declaringClass |
| A5 | `fav total size:%s, limitSize:%s` | onItemClick | FavSelectUI | 同 A4（更独特） |
| A6 | `on item click, holder is null` | onItemClick | FavSelectUI | 同 A4 |
| A7 | `key_fav_item_id` | onCreate | FavSelectUI | 同 A4 |

## B. 列表/数据组（语音为什么被藏 + 放行）

| # | 精确字符串 | 命中方法 | 得到类 | 用途 |
|---|---|---|---|---|
| B1 | `getFirstPageList` | `v4(int,int,List,Set,f5)` | `gd2.d`（FavItemInfoStorage） | Hook 放行：`set.remove(3)` |
| B2 | `and type != ` | v4/m9 | gd2.d | 辅证（SQL 拼接串） |
| B3 | `[getList] sql = ` | `m9(long,int,List,Set,f5)` | gd2.d | 分页/时间线查询也要 Hook |
| B4 | `restart cdndata download` | `B0()` | `tc2.s2`（Fav.FavApiLogic） | 定位收藏工具类 |
| B5 | `restartCdnDataDownload: localId=%d, favId=%d, dataId=%s, force=%b` | B0() | tc2.s2 | 辅证 |
| B6 | `restartCdnThumbDownLoad: localId=%d, favId=%d, dataId=%s, thumbDataId=%s, force=%b` | D0() | tc2.s2 | 辅证 |
| B7 | `getFavRoot, favRootSwitch:` | `D()` | tc2.s2 | 收藏根目录逻辑（可选） |
| B8 | `[FAV_ITEM_TYPE_VOICE] canFilterVoice = true, back` | `b(v,zz)` | `tc2.x3`（FavSendFilter） | 语音过滤核心；字段 `a`(boolean) |
| B9 | `filter isFastSendToChat return false for type: ` | b() | tc2.x3 | 辅证 |
| B10 | `can not retransmit short video` | b()/a() | tc2.x3 | 辅证 |
| B11 | **字段名** `field_favProto` / `field_localId` / `field_type` / `field_itemStatus` | — | `im.o3`（收藏条目实体，字段名未混淆） | `findField{name("field_favProto")}` → declaringClass；判语音 `field_type==3` |

## C. 长按菜单组（若要复用微信菜单而非自挂）

| # | 精确字符串 | 命中方法 | 得到类 |
|---|---|---|---|
| C1 | `on header view long click, ignore` | `onItemLongClick(AdapterView,View,int,long)` | `com.tencent.mm.plugin.fav.ui.aa`（la 挂的长按监听） |
| C2 | `fav_page_card_operation` | 埋点方法（aa/fc/de2.n 里都有） | 菜单链路宿主；用于找 `de2.m`（菜单构建） |
| C3 | `[OnCreateContextMMMenu] pos = ` | `a(i4,View,Info)` | `com.tencent.mm.plugin.fav.ui.gc`（兜底菜单构建器） |
| C4 | `openFavDebugUI` | `onMMMenuItemSelected(MenuItem,int)` | `de2.n`（菜单点击分发，含 5/8/9 特判） |
| C5 | `MicroMsg.Fav.StarMigration.Provider` | 同上 | de2.n（辅证） |
| C6 | `do transmit, long click info is %s` | `K7(int,int,View,m3)` | `FavoriteIndexUI`（转发分发，含 H7 拦截） |
| C7 | `MicroMsg.FavSearchManager` | 多处 | `com.tencent.mm.plugin.fav.ui.la`（搜索/选择页管理器） |
| C8 | `[shareFavToFriRequest] select first is FAV_ITEM_TYPE_VOICE` | `g(ctx,int,adapter,m3)` | `com.tencent.mm.plugin.fav.ui.mc`（FavoriteMenuHelper，转发入口） |
| C9 | `fav_trans_send,` | `O7()` | FavoriteIndexUI / mc（单条转发容量校验） |
| C10 | `fav_multi_send,` | `O7()` | 同上（多选） |

## D. 发送组（收藏→聊天出口，语音五步）

| # | 精确字符串 | 命中方法 | 得到类 | 用途 |
|---|---|---|---|---|
| D1 | `startRecord insert voicestg success` | `h(String,String)` | `v61.d1`（VoiceLogic） | `h(toUser,前缀)` 插记录 |
| D2 | `doScene:  filename null!`（注意**两个空格**） | `doScene(...)` | `v61.o`（NetSceneUploadVoice） | 构造 `(String,int)` 入队上传 |
| D3 | `Get info Failed file:` | doScene | v61.o | 辅证 |
| D4 | `doScene: fileOp is null, fileName:%s` | doScene | v61.o | 辅证 |
| D5 | `forward_file_from_fav` | `bj(String,String,String,i7)` | `ge2.l0`（tc2.u5 实现） | 原生「以文件发送」捷径 |
| D6 | `getDisplayInfo favItemInfo is null` | `l()/m()` | `ge2.k0`（FavItemLogic） | 核对 type3 无分支（法A 失效根因） |
| D7 | `sendFavFile fastSend: filePath=%s` | `d(toUser,m3,rq0)` | `com.tencent.mm.plugin.fav.ui.x5`（FavSendLogic） | 收藏发送总线（bj 的调用方） |
| D8 | `sendFavMsgToBizChat, toUser: %s, toBizChatId: %d, type: %d` | `k(Activity,String,long,m3)` | x5 | biz 白名单 `{1,2,5,6,8,14}`，无 voice |
| D9 | `type not support, type: %d` | k() | x5 | 同上（最直白证据） |
| D10 | `resendFileMsg localId:` | `b(e9,String)` | `qs5.v`（MicroMsg.AppMsg.FileSendLogic） | 原生发送订阅流（观察用） |

## E. 基础设施组

| # | 精确字符串 | 得到类 | 用途 |
|---|---|---|---|
| E1 | `MicroMsg.ServiceManager` | `ph5.n0` | `c(Class)` 取服务（ge2.l0/ou5.u0 等） |
| E2 | 无字符串 —— **Hook `v61.o#doScene` 的第 2 个参数** | NetSceneQueue 实例 | 捕获队列 `g(NetScene)` 入队（`b41.h9.e()` 拿不到时用） |
| E3 | 无字符串 —— 兜底路径 | — | `<MicroMsg>/voice2/0/<fileName>`（voice 目录失败时） |
| E4 | 无字符串 —— 字段名 `field_cdnUrl`/`field_dataId` | `im.k3`（CDN 记录） | 仅下载态排查用 |

---

## F. 一键解析代码（DexKit 1.x，可直接用）

```kotlin
object WxFavAnchors {
    // key -> 精确字符串
    val S = mapOf(
        "chatEntry"     to "chat_attachment_item_favorite",
        "favSelectUI"   to "fav total size:%s, limitSize:%s",
        "favSearchMgr"  to "MicroMsg.FavSearchManager",
        "longClick"     to "on header view long click, ignore",
        "favMenuBuild"  to "[OnCreateContextMMMenu] pos = ",
        "favMenuClick"  to "openFavDebugUI",
        "transmitHost"  to "do transmit, long click info is %s",
        "mcHelper"      to "[shareFavToFriRequest] select first is FAV_ITEM_TYPE_VOICE",
        "favDb"         to "getFirstPageList",
        "favDbList"     to "[getList] sql = ",
        "favApiLogic"   to "restart cdndata download",
        "favFilter"     to "[FAV_ITEM_TYPE_VOICE] canFilterVoice = true, back",
        "voiceLogic"    to "startRecord insert voicestg success",
        "voiceUpload"   to "doScene:  filename null!",
        "fileFromFav"   to "forward_file_from_fav",
        "favItemLogic"  to "getDisplayInfo favItemInfo is null",
        "favSendLogic"  to "sendFavFile fastSend: filePath=%s",
        "bizNotSupport" to "type not support, type: %d",
        "fileSendLogic" to "resendFileMsg localId:",
        "serviceMgr"    to "MicroMsg.ServiceManager"
    )
    fun resolve(cl: ClassLoader, apkPath: String): Map<String,String> {
        val out = HashMap<String,String>()
        val bridge = DexKitBridge.create(apkPath, cl)      // 旧版：DexKitBridge.create(cl)
        S.forEach { (k, s) ->
            runCatching {
                bridge.findMethod { matcher { usingStrings(s) } }
                    .firstOrNull()?.declaringClassName?.let { out[k] = it }
            }
        }
        runCatching {
            bridge.findField { matcher { name("field_favProto") } }
                .firstOrNull()?.declaringClassName?.let { out["favEntity"] = it }
        }
        runCatching { bridge.close() }
        return out
    }
}
// 用法：
// val A = WxFavAnchors.resolve(cl, apkPath)
// A["favSelectUI"] -> FavSelectUI 类名（Hook onItemClick）
// A["favDb"]       -> gd2.d（Hook v4/m9 放行语音）
// A["voiceLogic"]  -> v61.d1（h/u）
// A["voiceUpload"] -> v61.o（构造 (String,int)）
// A["fileFromFav"] -> ge2.l0（bj 以文件发送）
```

> 老版 DexKit（batchFindMethodsUsingStrings）等价写法：
> `bridge.batchFindMethodsUsingStrings(BatchFindUsingStrings().addSearchUsingStrings(key, str)).result[key][0].declaringClassName`

## G. 兜底顺序（升级失联时）
1. 字符串命中（上表）→ 2. 字段名（`field_favProto`/`field_cdnUrl`）→ 3. 当前版本默认名（本目录各 .java 文件内置）→ 4. 系统接口层（`View$OnCreateContextMenuListener` / `AdapterView$OnItemLongClickListener` 全部实现）。
