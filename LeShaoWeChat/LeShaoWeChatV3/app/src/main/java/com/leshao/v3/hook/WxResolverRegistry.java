package com.leshao.v3.hook;

import de.robv.android.xposed.XposedHelpers;

/**
 * WxResolver 注册表（v3.0.460 自动生成 + 手工补充）。
 *
 * 每个功能键绑定一个或多个稳定锚点（字符串/方法签名/接口），
 * 在微信进程内用 DexKit 动态定位类；兜底类名仅允许出现在本注册表，
 * hooks 中不得再出现任何 com.tencent.mm.* 字面量。
 */
public final class WxResolverRegistry {

    private WxResolverRegistry() {}

    public static void registerAll() {
        registerAnchored();
        registerManual();
    }

    private static void registerAnchored() {
        WxResolver.register("app_i3", WxResolver.byString("Lcom/tencent/mm/app/i3;->a [Lcom/tencent/mm/network/s;", "com.tencent.mm.app.i3"));
        WxResolver.register("app_k0", WxResolver.byString("Lcom/tencent/mm/app/k0;->d Lpv5/p1;", "com.tencent.mm.app.k0"));
        WxResolver.register("autogen_events_DealQBarStrEvent", WxResolver.byString("Lfm/v3;", "com.tencent.mm.autogen.events.DealQBarStrEvent"));
        WxResolver.register("autogen_events_SendMsgSuccessEvent", WxResolver.byString("Lfm/zt;", "com.tencent.mm.autogen.events.SendMsgSuccessEvent"));
        WxResolver.register("autogen_events_SnsCommentUpdateEvent", WxResolver.byString("Lfm/xv;", "com.tencent.mm.autogen.events.SnsCommentUpdateEvent"));
        WxResolver.register("autogen_events_SnsTimelineRefreshEvent", WxResolver.byString("Lfm/kx;", "com.tencent.mm.autogen.events.SnsTimelineRefreshEvent"));
        WxResolver.register("booter_notification_m0", WxResolver.byString("[NO NOTIFICATION](ContactStorageLogic.isMuteContact(talker) || (Contact.isWxOrOpenImChatRoom(talker) && !ContactStorageLogic.isChatRoomNotify(talker) && !msgInfo.isAtSomeone(ConfigStorageLogic.getUsernameFromUserInfo() && msgInfo.getType() != ConstantsProtocal.MM_DATA_MULTITALK)) && (msgInfo != null && !msgInfo.isChatRoomNotice(ConfigStorageLogic.getUsernameFromUserInfo())) )preNotificationCheck, talker: %s, tipsFlag: %s ", "com.tencent.mm.booter.notification.m0"));
        WxResolver.register("chatroom_plugin_listener_n0", WxResolver.byString("MicroMsg.RevokeReceiveMessageListener", "com.tencent.mm.chatroom.plugin.listener.n0"));
        WxResolver.register("chatroom_ui_ChatroomInfoUI", WxResolver.byString("ChatroomMuteRefine OpModChatRoomNotify roomId = %s, notifyMsg = %d, defaultNeedPushFlag=%d", "com.tencent.mm.chatroom.ui.ChatroomInfoUI"));
        WxResolver.register("feature_avatar_s", WxResolver.byString("attachAvatar, AvatarBitmapDrawable already set", "com.tencent.mm.feature.avatar.s"));
        WxResolver.register("modelavatar_d1", WxResolver.byName("com.tencent.mm.modelavatar.d1"));
        WxResolver.register("modelsimple_k0", WxResolver.byName("com.tencent.mm.modelsimple.k0", "com.tencent.mm.modelsimple.i0"));
        WxResolver.register("modelvoice_MediaRecorder", WxResolver.byName("com.tencent.mm.modelvoice.MediaRecorder"));
        WxResolver.register("network_y0", WxResolver.byMethod("bindCellularStrategy", "com.tencent.mm.network.y0"));
        WxResolver.register("plugin_appbrand_ad_jsapi_u", WxResolver.byString("showSplashAd, adOperateCallCostTime:%s, callbackTime:%s, startTime:%s", "com.tencent.mm.plugin.appbrand.ad.jsapi.u"));
        WxResolver.register("plugin_appbrand_ad_ui_AppBrandAdUI", WxResolver.byString("Lcom/tencent/mm/plugin/appbrand/ad/ui/AppBrandAdUI$$a;", "com.tencent.mm.plugin.appbrand.ad.ui.AppBrandAdUI"));
        WxResolver.register("plugin_appbrand_ad_ui_AppBrandMenuFooter", WxResolver.byMethod("setPageView", "com.tencent.mm.plugin.appbrand.ad.ui.AppBrandMenuFooter"));
        WxResolver.register("plugin_appbrand_d7", WxResolver.byString("Lcom/tencent/mm/plugin/appbrand/d7;->v Lcom/tencent/mm/plugin/appbrand/d7;", "com.tencent.mm.plugin.appbrand.d7"));
        WxResolver.register("plugin_appbrand_jsapi_advertise_o", WxResolver.byString("Lcom/tencent/mm/plugin/appbrand/jsapi/advertise/i;->d Lcom/tencent/mm/plugin/appbrand/jsapi/advertise/i;", "com.tencent.mm.plugin.appbrand.jsapi.advertise.o"));
        WxResolver.register("plugin_appbrand_jsapi_advertise_p", WxResolver.byString("[MBAd] notifyAdEvent show->mark adType=%s, appId=%s, isMbAd=%s", "com.tencent.mm.plugin.appbrand.jsapi.advertise.p"));
        WxResolver.register("plugin_appbrand_jsapi_advertise_s", WxResolver.byString("doOpenCanvas canvasid %s, preLoad %d, noStore %d, adInfoXml %s, canvasDynamicInfo %s, canvasDynamicExtInfo %s", "com.tencent.mm.plugin.appbrand.jsapi.advertise.s"));
        WxResolver.register("plugin_appbrand_jsapi_channels_a0", WxResolver.byString("Lcom/tencent/mm/plugin/appbrand/jsapi/channels/OpenChannelsRewardedVideoAdResult;", "com.tencent.mm.plugin.appbrand.jsapi.channels.a0"));
        // 8.0.79 起激励视频结果宿主迁移到 type.jsapi.channels.a0，旧版微信仍在 appbrand.jsapi.channels.a0。
        WxResolver.register("plugin_type_jsapi_channels_a0", WxResolver.byName("com.tencent.mm.plugin.type.jsapi.channels.a0", "com.tencent.mm.plugin.appbrand.jsapi.channels.a0"));
        WxResolver.register("plugin_type_ipc_AppBrandProxyUIProcessTask_ProcessResult", WxResolver.byName("com.tencent.mm.plugin.type.ipc.AppBrandProxyUIProcessTask$ProcessResult", "com.tencent.mm.plugin.appbrand.ipc.AppBrandProxyUIProcessTask$ProcessResult"));
        WxResolver.register("plugin_appbrand_jsapi_channels_f0", WxResolver.byString("MicroMsg.AppBrand.JsApiOpenChannelsUserProfile", "com.tencent.mm.plugin.appbrand.jsapi.channels.f0"));
        WxResolver.register("plugin_appbrand_jsapi_channels_w", WxResolver.byString("MicroMsg.AppBrand.JsApiOpenChannelsLivePrivate", "com.tencent.mm.plugin.appbrand.jsapi.channels.w"));
        WxResolver.register("plugin_appbrand_jsapi_profile_h", WxResolver.byString("Lcom/tencent/mm/plugin/appbrand/jsapi/profile/g;", "com.tencent.mm.plugin.appbrand.jsapi.profile.h"));
        WxResolver.register("plugin_appbrand_service_b0", WxResolver.byString("Lcom/tencent/mm/plugin/appbrand/step/KSProcessWeAppLaunch;->stepInjectWxConfig_Game Lol1/a;", "com.tencent.mm.plugin.appbrand.service.b0"));
        WxResolver.register("plugin_fav_ui_FavSelectUI", WxResolver.byString("Lcom/tencent/mm/plugin/fav/ui/adapter/c;", "com.tencent.mm.plugin.fav.ui.FavSelectUI"));
        WxResolver.register("plugin_fav_ui_FavoriteIndexUI", WxResolver.byString("(IILandroid/view/View;Lcom/tencent/mm/plugin/fav/api/FavItemInfo;)V", "com.tencent.mm.plugin.fav.ui.FavoriteIndexUI"));
        WxResolver.register("plugin_finder_feed_model_BaseFinderFeedLoader", WxResolver.byName("com.tencent.mm.plugin.finder.feed.model.BaseFinderFeedLoader"));
        WxResolver.register("plugin_finder_feed_model_z", WxResolver.byName("com.tencent.mm.plugin.finder.feed.model.z", "com.tencent.mm.plugin.finder.feed.model.y"));
        WxResolver.register("plugin_finder_live_plugin_n0", WxResolver.byString("startPolling onPollingSuccess live_ad_id:", "com.tencent.mm.plugin.finder.live.plugin.n0"));
        WxResolver.register("plugin_finder_live_widget_h3", WxResolver.byName("com.tencent.mm.plugin.finder.live.widget.h3", "com.tencent.mm.plugin.finder.live.widget.d3"));
        WxResolver.register("plugin_finder_model_BaseFinderFeed", WxResolver.byString("Lov2/c;->d Lov2/c;", "com.tencent.mm.plugin.finder.model.BaseFinderFeed"));
        WxResolver.register("plugin_hp_mmdiff_MergeHdiffApkService", WxResolver.byString("Lcom/tencent/mm/plugin/hp/mmdiff/MergeHdiffApkService;->d Ljava/util/concurrent/atomic/AtomicBoolean;", "com.tencent.mm.plugin.hp.mmdiff.MergeHdiffApkService"));
        WxResolver.register("plugin_hp_model_TinkerBootsActivateListener", WxResolver.byString("Lcom/tencent/mm/plugin/hp/model/TinkerBootsActivateListener;->d J", "com.tencent.mm.plugin.hp.model.TinkerBootsActivateListener"));
        WxResolver.register("plugin_location_soso_SoSoProxyUI", WxResolver.byString("summerasyncinit not init activity foreground[%b] savedInstanceState[%b], activity[%s, %d]", "com.tencent.mm.plugin.location_soso.SoSoProxyUI"));
        WxResolver.register("plugin_luckymoney_model_h6", WxResolver.byString("Lcom/tencent/mm/plugin/luckymoney/model/LuckyMoneyEmojiSwitch;", "com.tencent.mm.plugin.luckymoney.model.h6"));
        WxResolver.register("plugin_luckymoney_model_n6", WxResolver.byString("[onGYNetEnd] expressionmd5:%s、expressiontype:%s、enableSetStatus:%s、canVisitNormalDetail: %s", "com.tencent.mm.plugin.luckymoney.model.n6"));
        WxResolver.register("plugin_luckymoney_model_o5", WxResolver.byString("ljd:sendLocalMsg() this is new year msg! don't send predownload image event, because image preload data is illegal!", "com.tencent.mm.plugin.luckymoney.model.o5"));
        WxResolver.register("plugin_messenger_foundation_h2", WxResolver.byString("Lcom/tencent/mm/plugin/messenger/foundation/h2$$a;", "com.tencent.mm.plugin.messenger.foundation.h2"));
        WxResolver.register("plugin_msgquote_model_MsgQuoteItem", WxResolver.byString("Lcom/tencent/mm/plugin/msgquote/model/MsgQuoteItem;->CREATOR Landroid/os/Parcelable$Creator;", "com.tencent.mm.plugin.msgquote.model.MsgQuoteItem"));
        WxResolver.register("plugin_profile_ui_ContactInfoUI", WxResolver.byString("Lcom/tencent/mm/repairer/config/chatting/RepairerConfigChattingAvatarLongPressMenu;", "com.tencent.mm.plugin.profile.ui.ContactInfoUI"));
        WxResolver.register("plugin_remittance_ui_RemittanceDetailUI", WxResolver.byString("(Lcom/tencent/mm/plugin/remittance/model/NetSceneTenpayRemittanceQuery;Lcom/tencent/mm/plugin/remittance/model/NetSceneUnionTransferQuery;)V", "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI"));
        WxResolver.register("plugin_scanner_ImageQBarDataBean", WxResolver.byName("com.tencent.mm.plugin.scanner.ImageQBarDataBean"));
        WxResolver.register("plugin_scanner_ui_BaseScanUI", WxResolver.byString("ScanUI.onCreate#%s enterScene:%d, enableMultiCode:%b enableScanCodeProductMerge:%b  currentMode:%d  setResultAfterScan:%s", "com.tencent.mm.plugin.scanner.ui.BaseScanUI"));
        WxResolver.register("plugin_scanner_z0", WxResolver.byString("Lcom/tencent/mm/plugin/scanner/z0;->j [Ljava/lang/String;", "com.tencent.mm.plugin.scanner.z0"));
        WxResolver.register("plugin_sns_ad_animproxy_SnsAdAnimProxyUI", WxResolver.byString("com.tencent.mm.plugin.sns.ad.timeline.clicker.actionbutton.AdClickActionHandler", "com.tencent.mm.plugin.sns.ad.animproxy.SnsAdAnimProxyUI"));
        WxResolver.register("plugin_sns_ad_landingpage_SnsAdNativeLandingPagesMMUI", WxResolver.byString("com.tencent.mm.plugin.sns.ad.landingpage.SnsAdNativeLandingPagesMMUI", "com.tencent.mm.plugin.sns.ad.landingpage.SnsAdNativeLandingPagesMMUI"));
        WxResolver.register("plugin_sns_model_h6", WxResolver.byString("com.tencent.mm.plugin.sns.ui.comment.emoticon.data.SnsCmtPostContentData", "com.tencent.mm.plugin.sns.model.h6"));
        WxResolver.register("plugin_sns_model_l3", WxResolver.byString("Lcom/tencent/mm/plugin/sns/ui/improve/component/unread/ImproveUnreadUIC;->t Lcom/tencent/mm/plugin/sns/ui/improve/component/unread/ImproveUnreadUIC;", "com.tencent.mm.plugin.sns.model.l3"));
        WxResolver.register("plugin_sns_model_x", WxResolver.byName("com.tencent.mm.plugin.sns.model.x", "com.tencent.mm.sdk.platformtools.h9"));
        WxResolver.register("plugin_sns_storage_SnsInfo", WxResolver.byName("com.tencent.mm.plugin.sns.storage.SnsInfo"));
        WxResolver.register("plugin_sns_storage_l1", WxResolver.byString("com.tencent.mm.plugin.sns.storage.MergeInfoStorage", "com.tencent.mm.plugin.sns.storage.l1"));
        WxResolver.register("plugin_sns_storage_n", WxResolver.byString("com.tencent.mm.plugin.sns.storage.ADXml$ClickableAnimLabelInfo", "com.tencent.mm.plugin.sns.storage.n"));
        WxResolver.register("plugin_sns_ui_SnsAdNativeLandingPagesPreviewUI", WxResolver.byString("com.tencent.mm.plugin.sns.storage.AdLandingPagesStorage.AdLandingPageComponent.component.LandingPageUtil", "com.tencent.mm.plugin.sns.ui.SnsAdNativeLandingPagesPreviewUI"));
        WxResolver.register("plugin_sns_ui_SnsAdNativeLandingPagesUI", WxResolver.byString("<PageList>\n            <Page>\n                <componentItemList>\n                    <componentItem>\n                        <type>2147483647</type>\n                    </componentItem>\n                </componentItemList>\n            </Page>\n        </PageList>", "com.tencent.mm.plugin.sns.ui.SnsAdNativeLandingPagesUI"));
        WxResolver.register("plugin_sns_ui_SnsAdProxyUI", WxResolver.byString("com.tencent.mm.plugin.sns.ui.SnsAdProxyUI", "com.tencent.mm.plugin.sns.ui.SnsAdProxyUI"));
        WxResolver.register("plugin_sns_ui_SnsAdStreamVideoPlayUI", WxResolver.byString("reportVideo minus staytime found! totaltime[%d], offscreenTime[%ld]", "com.tencent.mm.plugin.sns.ui.SnsAdStreamVideoPlayUI"));
        WxResolver.register("plugin_sns_ui_improve_ImproveSnsTimelineUI", WxResolver.byName("com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI", "com.tencent.mm.plugin.sns.model.q6"));
        WxResolver.register("plugin_sns_ui_improve_view_ImproveInteractionLayout", WxResolver.byString("null cannot be cast to non-null type com.tencent.mm.plugin.sns.ui.improve.view.ImproveCommentView", "com.tencent.mm.plugin.sns.ui.improve.view.ImproveInteractionLayout"));
        WxResolver.register("plugin_sns_ui_k1", WxResolver.byName("com.tencent.mm.plugin.sns.ui.k1"));
        WxResolver.register("plugin_sns_ui_listener_c", WxResolver.byName("com.tencent.mm.plugin.sns.ui.listener.c", "com.tencent.mm.plugin.sns.ui.flexible.SnsFlexibleVideoActivity"));
        WxResolver.register("plugin_voip_model_c0", WxResolver.byString("Lcom/tencent/mm/plugin/voip/model/NewVoipIncomingCallManager$renderListener$1;", "com.tencent.mm.plugin.voip.model.c0"));
        WxResolver.register("plugin_voip_model_d0", WxResolver.byString("Lcom/tencent/mm/plugin/voip/model/s1;->a Lcom/tencent/mm/plugin/voip/model/s1;", "com.tencent.mm.plugin.voip.model.d0"));
        WxResolver.register("plugin_voip_model_h2", WxResolver.byString("steve:[simucall]doTaskCallin simulcall already processed, return! VoipGetRoomInfoResp roomid:%d, roomType:%d, mProtocol.roomId:%d, iSimulCallStatus:%d ", "com.tencent.mm.plugin.voip.model.h2"));
        WxResolver.register("plugin_voip_model_n0", WxResolver.byString("Lcom/tencent/mm/plugin/voip/model/a1;", "com.tencent.mm.plugin.voip.model.n0"));
        WxResolver.register("plugin_voip_model_y", WxResolver.byString("Lcom/tencent/mm/modelbase/p1;->f Lcom/tencent/mm/modelbase/p1;", "com.tencent.mm.plugin.voip.model.y"));
        WxResolver.register("plugin_voip_widget_k", WxResolver.byString("(Lcom/tencent/mm/plugin/voip/ui/IVoipUIListener;ILcom/tencent/mm/storage/Contact;ZZZ)V", "com.tencent.mm.plugin.voip.widget.k"));
        WxResolver.register("plugin_webview_ui_tools_WebViewUI", WxResolver.byString("javascript:(function() {if (window._WXJS && _WXJS.mediaTracker && typeof _WXJS.mediaTracker.isPlaying === 'function') {    return _WXJS.mediaTracker.isPlaying();} else {    return false;}})()", "com.tencent.mm.plugin.webview.ui.tools.WebViewUI"));
        WxResolver.register("pluginsdk_model_app_k0", WxResolver.byString("summerbig tryInitAttachInfo info exist systemRowid [%d], totalLen[%d], field_fileFullPath[%s], type[%d], mediaId[%s], msgid[%d], upload[%b], file.exists[%b], status[%d], signature len[%d]", "com.tencent.mm.pluginsdk.model.app.k0"));
        WxResolver.register("pluginsdk_model_p3", WxResolver.byString("null cannot be cast to non-null type com.tencent.mm.protocal.protobuf.QQBrowserReportResp", "com.tencent.mm.pluginsdk.model.p3"));
        WxResolver.register("pluginsdk_ui_chat_ChatFooter", WxResolver.byString("generateReferMsgInfo auto Generate referInfo,  referInfoKey=%s  pageUrl=%s, title=%s, desc=%s, cover=%s, msgSource=%s", "com.tencent.mm.pluginsdk.ui.chat.ChatFooter"));
        WxResolver.register("pluginsdk_ui_chat_ChattingUILayout", WxResolver.byMethod("fitSystemWindows", "com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout"));
        WxResolver.register("pluginsdk_ui_tools_s6", WxResolver.byString("Lcom/tencent/mm/pluginsdk/ui/tools/QBFileShareProxyUI;->m I", "com.tencent.mm.pluginsdk.ui.tools.s6"));
        WxResolver.register("protobuf_f", WxResolver.byString("Cannot use this method", "com.tencent.mm.protobuf.f"));
        WxResolver.register("protocal_protobuf_SnsObject", WxResolver.byString("unknownTagHandler", "com.tencent.mm.protocal.protobuf.SnsObject"));
        WxResolver.register("roomsdk_model_factory_c", WxResolver.byString("Lcom/tencent/mm/roomsdk/model/factory/b;", "com.tencent.mm.roomsdk.model.factory.c"));
        WxResolver.register("roomsdk_model_factory_g", WxResolver.byString("MicroMsg.RoomCallbackFactory", "com.tencent.mm.roomsdk.model.factory.g"));
        WxResolver.register("roomsdk_model_factory_h", WxResolver.byString("Lcom/tencent/mm/roomsdk/model/factory/g;", "com.tencent.mm.roomsdk.model.factory.h"));
        WxResolver.register("sandbox_updater_Updater", WxResolver.byString("Lcom/tencent/mm/sdk/platformtools/a0;->g Ljava/lang/String;", "com.tencent.mm.sandbox.updater.Updater"));
        WxResolver.register("sdk_event_IEvent", WxResolver.byString("EventPoolImpl.asyncPublish executor", "com.tencent.mm.sdk.event.IEvent"));
        WxResolver.register("sdk_platformtools_r4", WxResolver.byString("Lcom/tencent/mm/sdk/platformtools/MultiProcessSharedPreferences;->p Landroid/net/Uri;", "com.tencent.mm.sdk.platformtools.r4"));
        WxResolver.register("sdk_platformtools_x", WxResolver.byString("(Landroid/graphics/Bitmap;Landroid/graphics/Bitmap;Landroid/graphics/RectF;)Landroid/graphics/Bitmap;", "com.tencent.mm.sdk.platformtools.x"));
        WxResolver.register("storage_a3", WxResolver.byString("CREATE INDEX IF NOT EXISTS serverChatRoomUserIndex ON chatroom ( chatroomname )", "com.tencent.mm.storage.a3", "com.tencent.mm.storage.ChatRoom"));
        WxResolver.register("storage_c4", WxResolver.byString("Lcom/tencent/mm/storage/c4;->E Log5/e0;", "com.tencent.mm.storage.c4"));
        WxResolver.register("storage_d9", WxResolver.byString(".msg.Antispam.safetyWarningDetail", "com.tencent.mm.storage.d9"));
        WxResolver.register("storage_e9", WxResolver.byString(".sysmsg.sysmsgtemplate.content_template.link_list.link2.msgcount", "com.tencent.mm.storage.e9"));
        WxResolver.register("storage_f9", WxResolver.byString("CREATE TABLE IF NOT EXISTS message ( msgId INTEGER PRIMARY KEY, msgSvrId INTEGER , type INT, status INT, isSend INT, isShowTimer INTEGER, createTime INTEGER, talker TEXT, content TEXT, imgPath TEXT, reserved TEXT, lvbuffer BLOB, transContent TEXT,transBrandWording TEXT ,talkerId INTEGER, bizClientMsgId TEXT, bizChatId INTEGER DEFAULT -1, bizChatUserId TEXT, msgSeq INTEGER, flag INT, solitaireFoldInfo BLOB, historyId TEXT) ", "com.tencent.mm.storage.f9"));
        WxResolver.register("storage_g4", WxResolver.byString("CREATE INDEX IF NOT EXISTS  contact_label_createtime_index ON ContactLabel ( createTime )", "com.tencent.mm.storage.g4"));
        WxResolver.register("storage_g9", WxResolver.byString("updateConversation talker[%s] msgseq[%d],newConv[%b],newMsgInsertCount[%d], deleteCount[%d], ParentRef[%s], func@count[%s@%d]", "com.tencent.mm.storage.g9"));
        WxResolver.register("storage_j4", WxResolver.byString("CREATE TABLE IF NOT EXISTS contact_ext ( username VARCHAR(40), Uin INTEGER DEFAULT 0, Email VARCHAR(128), Mobile VARCHAR(40), ShowFlag INTEGER DEFAULT 0 , ConType INTEGER DEFAULT 0 , ConRemark TEXT, ConRemark_PYShort TEXT, ConRemark_PYFull TEXT, ConQQMBlog TEXT, ConSMBlog TEXT, DomainList TEXT, reserved1 INT DEFAULT 0 , reserved2 INT DEFAULT 0 , reserved3 INT DEFAULT 0 , reserved4 INT DEFAULT 0 , reserved5 INT DEFAULT 0 , reserved6 TEXT, reserved7 TEXT, reserved8 TEXT, reserved9 TEXT, reserved10 TEXT, weiboflag  INT DEFAULT 0 ,weibonickname TEXT  );", "com.tencent.mm.storage.j4"));
        WxResolver.register("storage_q3", WxResolver.byString("Lcom/tencent/mm/storage/m3;->q Ljava/lang/Object;", "com.tencent.mm.storage.q3"));
        WxResolver.register("storage_y3", WxResolver.byString("wx_;wxid_;gh_;a0;a1;a2;a3;a4;a5;a6;a7;a8;a9;q0;q1;q2;q3;q4;q5;q6;q7;q8;q9;qq0;qq1;qq2;qq3;qq4;qq5;qq6;qq7;qq8;qq9;f0;f1;f2;f3;f4;f5;f6;f7;f8;f9;F0;F1;F2;F3;F4;F5;F6;F7;F8;F9;", "com.tencent.mm.storage.y3"));
        WxResolver.register("storage_z2", WxResolver.byString("Lcom/tencent/mm/storage/z2;->o2 Log5/e0;", "com.tencent.mm.storage.z2", "com.tencent.mm.storage.MMChatRoomMemberInfo"));
        WxResolver.register("storage_z3", WxResolver.byString("Lcom/tencent/mm/storage/z3;->r Log5/e0;", "com.tencent.mm.storage.z3"));
        WxResolver.register("ui_HomeUI", WxResolver.byString("[AmoebaReport][EntrySetup] done, viewId=negative_screen_entry_btn policy=8 cgiBizId=20", "com.tencent.mm.ui.HomeUI"));
        WxResolver.register("ui_LauncherUI", WxResolver.byString("will logout account and kill app, bSwitchAccount = %s, hasDoneCreateImp = %s, isKickOffline = %s, hasLogin = %s, hasInitialAccount = %s, isVirtualLogin=%s", "com.tencent.mm.ui.LauncherUI"));
        WxResolver.register("ui_MMActivity", WxResolver.byString("java.lang.IllegalStateException: Can not perform this action after onSaveInstanceState", "com.tencent.mm.ui.MMActivity"));
        WxResolver.register("ui_base_preference_KeyValuePreference", WxResolver.byString("Lcom/tencent/mm/ui/base/preference/v;", "com.tencent.mm.ui.base.preference.KeyValuePreference"));
        WxResolver.register("ui_base_preference_g0", WxResolver.byString("com/tencent/mm/ui/base/preference/MMPreferenceAdapter", "com.tencent.mm.ui.base.preference.g0"));
        WxResolver.register("ui_bi", WxResolver.byString("com/tencent/mm/ui/ServiceNotifySettingsUI$3", "com.tencent.mm.ui.bi"));
        WxResolver.register("ui_chatting_BaseChattingUIFragment", WxResolver.byString("[onPause] is never doResume before doPause! Because while entering ChattingUI, the app back to background.", "com.tencent.mm.ui.chatting.BaseChattingUIFragment"));
        WxResolver.register("ui_chatting_ChattingUI", WxResolver.byString("injectEdge2EdgeCtrlLayout isTargetSDKVersionOver35=%s enableEdgeToEdgeWrapperLayout=%s curHalfScreenMode=%s", "com.tencent.mm.ui.chatting.ChattingUI"));
        WxResolver.register("ui_chatting_ChattingUIFragment", WxResolver.byString("onResume fragment not foreground, return,  chatting contentview is show %b, viewShow %b listviewShow:%b", "com.tencent.mm.ui.chatting.ChattingUIFragment"));
        WxResolver.register("ui_chatting_RevokeMsgListener", WxResolver.byString("[oneliang][revokeMsgImage] image gallery ui,msg id is:%s,downloadingImageMsgId: %s", "com.tencent.mm.ui.chatting.RevokeMsgListener"));
        WxResolver.register("ui_chatting_adapter_k", WxResolver.byString("null cannot be cast to non-null type com.tencent.mm.ui.chatting.viewitems.ChattingItem.BaseViewHolder", "com.tencent.mm.ui.chatting.adapter.k"));
        WxResolver.register("ui_chatting_component_om", WxResolver.byString("com/tencent/mm/ui/chatting/component/SearchComponent$7", "com.tencent.mm.ui.chatting.component.om"));
        WxResolver.register("ui_chatting_manager_t", WxResolver.byString("null cannot be cast to non-null type android.widget.Button", "com.tencent.mm.ui.chatting.manager.t"));
        WxResolver.register("ui_chatting_view_MMChattingListView", WxResolver.byString("Lcom/tencent/mm/ui/chatting/viewitems/mvvmview/mediagroup/ChattingMediaGroupMvvmView;", "com.tencent.mm.ui.chatting.view.MMChattingListView"));
        WxResolver.register("ui_chatting_viewitems_b0", WxResolver.byString("photoAcctResp is valid, photo: %s, photoAcctResp.interactive_identity: %s, photoAcctResp.headimgurl: %s", "com.tencent.mm.ui.chatting.viewitems.b0"));
        WxResolver.register("ui_chatting_viewitems_bq", WxResolver.byString("com/tencent/mm/ui/chatting/viewitems/ChattingItemVoice$ChattingItemVoiceFrom$5", "com.tencent.mm.ui.chatting.viewitems.bq"));
        WxResolver.register("ui_chatting_viewitems_m0", WxResolver.byName("com.tencent.mm.ui.chatting.viewitems.m0"));
        WxResolver.register("ui_chatting_viewitems_o0", WxResolver.byString("Lcom/tencent/mm/repairer/config/chatting/RepairerConfigChatRecordsTtsEntrance;", "com.tencent.mm.ui.chatting.viewitems.o0"));
        WxResolver.register("ui_chatting_viewitems_p", WxResolver.byString("null cannot be cast to non-null type kotlin.collections.MutableMap<kotlin.String, kotlin.Any?>", "com.tencent.mm.ui.chatting.viewitems.p"));
        WxResolver.register("ui_chatting_viewitems_r0", WxResolver.byString("context item select failed, null dataTag", "com.tencent.mm.ui.chatting.viewitems.r0"));
        WxResolver.register("ui_chatting_viewitems_to", WxResolver.byString("Lkd5/bg;->h Z", "com.tencent.mm.ui.chatting.viewitems.to"));
        WxResolver.register("ui_chatting_x0", WxResolver.byString("Lcom/tencent/mm/repairer/config/chatting/RepairerConfigVoicePlaySpeedUpSpeed;", "com.tencent.mm.ui.chatting.x0"));
        WxResolver.register("ui_conversation_MainUI", WxResolver.byString("[oneliang]current clientVersion=%s,has rating clientVersion=%s,dynamic config showRatting min version=%s,max version:%s,waitDaysMillis:%s", "com.tencent.mm.ui.conversation.MainUI"));
        WxResolver.register("ui_conversation_j6", WxResolver.byString("Llw/e0;", "com.tencent.mm.ui.conversation.j6"));
        WxResolver.register("ui_gk", WxResolver.byString("create_group_sessionid", "com.tencent.mm.ui.gk"));
        WxResolver.register("ui_mvvm_MvvmContactListUI", WxResolver.byString("key_select_contact_report_page_id", "com.tencent.mm.ui.mvvm.MvvmContactListUI"));
        WxResolver.register("ui_transmit_MsgRetransmitUI", WxResolver.byString("done isWaitedForRecreateCompressVideo:%s showSuccessTips:%s isFixSnackBar:%s goToChattingUI:%s, StayWhereYouAre:%s sessionId:%s", "com.tencent.mm.ui.transmit.MsgRetransmitUI"));
        WxResolver.register("ui_transmit_SelectConversationUI", WxResolver.byString("doForwardToWeWork: contentTypeForwardToWeWork:%s , mBaseDataToWeWork:%s ,msgInfo:%s msgId:%s msgInfoList:%s ,wwAppType:%s", "com.tencent.mm.ui.transmit.SelectConversationUI"));
        WxResolver.register("ui_widget_MMEditText", WxResolver.byString("Lcom/tencent/mm/accessibility/feature/IAccessibilityService;", "com.tencent.mm.ui.widget.MMEditText"));
        WxResolver.register("ui_widget_MMNeat7extView", WxResolver.byString("^[\\u0001-\\u00b7\\u4E00-\\u9FA5\\ue001-\\ue537\\u2005-\\u2027\\u3001-\\u3011\\uff01-\\uffe5\\u2100-\\u2900[\\ud83c\\udc00-\\ud83c\\udfff]|[\\ud83d\\udc00-\\ud83d\\udfff]]+$", "com.tencent.mm.ui.widget.MMNeat7extView"));
    }

    private static void registerManual() {
        // ============ 8.0.79 已移除/改名类的等价映射 ============
        // kernel 包在 8.0.79 已删除（旧 getAccPath 宿主消失）：返回 null，调用方走 default_uin+DB hash 兜底。
        // 旧版微信（8.0.78 及更早）仍存在 kernel.h/kernel.g，由版本分流直接命中旧类名。
        WxResolver.register("kernel_h", WxResolver.byName("com.tencent.mm.kernel.h"));
        WxResolver.register("kernel_g", WxResolver.byName("com.tencent.mm.kernel.g"));
        WxResolver.register("model_k0", WxResolver.byName("com.tencent.mm.model.k0", "com.tencent.mm.k0"));
        WxResolver.register("k0", WxResolver.byName("com.tencent.mm.k0", "com.tencent.mm.model.k0"));
        WxResolver.register("sdk_platformtools_MMApplicationContext", WxResolver.byName("com.tencent.mm.sdk.platformtools.MMApplicationContext"));
        WxResolver.register("plugin_messenger_foundation_h3", WxResolver.alias("plugin_messenger_foundation_h2"));
        WxResolver.register("storage_ChatRoom", WxResolver.alias("storage_a3"));
        WxResolver.register("storage_MMChatRoomMemberInfo", WxResolver.alias("storage_z2"));
        WxResolver.register("ui_chatting_v2_ChattingUIFragment", WxResolver.alias("ui_chatting_ChattingUIFragment"));
        WxResolver.register("ui_LauncherUIProxy", WxResolver.alias("ui_LauncherUI"));
        WxResolver.register("ui_transmit_SelectConversation", WxResolver.alias("ui_transmit_SelectConversationUI"));
        // voip：v2.model/ui 旧类在 8.0.79 归并为 model 包
        WxResolver.register("plugin_voip_ui_h2", WxResolver.alias("plugin_voip_model_h2"));
        WxResolver.register("plugin_voip_v2_model_h2", WxResolver.alias("plugin_voip_model_h2"));
        WxResolver.register("plugin_voip_v2_model_d0", WxResolver.alias("plugin_voip_model_d0"));
        // 朋友圈：ImproveDataUIC 搬进 component.data 包，ImproveInteractionUtil 改为类名包含定位
        WxResolver.register("plugin_sns_ui_improve_ImproveDataUIC",
                WxResolver.byClassNameContains("ImproveDataUIC", "com.tencent.mm.plugin.sns.ui.improve.component.data.ImproveDataUIC"));
        WxResolver.register("plugin_sns_ui_improve_util_ImproveInteractionUtil",
                WxResolver.byClassNameContains("ImproveInteraction"));
        // 小程序剪贴板：外部类移除，内部类含稳定未混淆名
        WxResolver.register("plugin_appbrand_jsapi_JsApiSetClipboardDataWC",
                WxResolver.byClassNameContains("JsApiSetClipboardDataWC"));
        // 朋友圈时间线场景：NetSceneSnsTimeLine 在 8.0.79 更名，用 handleNormalResp 方法锚点
        WxResolver.register("plugin_sns_model_NetSceneSnsTimeLine",
                WxResolver.byMethod("handleNormalResp", "com.tencent.mm.plugin.sns.model.NetSceneSnsTimeLine"));
        // 群二维码扫码工具：v16.a 更名，用 AutoGroupQrHook 已有 QR 特征串定位
        WxResolver.register("pluginsdk_ui_tools_v16_a",
                WxResolver.byString("scanType", "com.tencent.mm.pluginsdk.ui.tools.v16.a"));
        // 组合选人服务：8.0.79 已移除，返回 null（WxForwardReplaceHook 降级）
        WxResolver.register("feature_combine_CombineEntranceService", WxResolver.byName("com.tencent.mm.feature.combine.CombineEntranceService"));
        // 联系人存储接口 d8（版本内稳定存在）
        WxResolver.register("storage_d8", WxResolver.byName("com.tencent.mm.storage.d8"));
        // 内部类（dex 名用 $ 分隔，注册表内集中兜底）
        WxResolver.register("ui_gk_D", WxResolver.byName("com.tencent.mm.ui.gk$D"));
        WxResolver.register("ui_rc_b", WxResolver.byName("com.tencent.mm.ui.rc$b"));
        // 8.0.79 起 MMErrorProcessor.updateRequired 宿主由 ui.rc 迁移到 ui.uc，旧版微信仍为 ui.rc。
        WxResolver.register("ui_uc", WxResolver.byName("com.tencent.mm.ui.uc", "com.tencent.mm.ui.rc"));
        WxResolver.register("ui_rc", WxResolver.byName("com.tencent.mm.ui.rc", "com.tencent.mm.ui.uc"));
        WxResolver.register("app_h3", WxResolver.byName("com.tencent.mm.app.h3", "com.tencent.mm.app.g3", "com.tencent.mm.app.i3", "com.tencent.mm.app.h4"));
        WxResolver.register("app_h3_a", WxResolver.byName("com.tencent.mm.app.h3.a"));
        WxResolver.register("app_h3_c", WxResolver.byName("com.tencent.mm.app.h3.c"));
        WxResolver.register("app_g3", WxResolver.byName("com.tencent.mm.app.g3", "com.tencent.mm.app.h3", "com.tencent.mm.app.i3"));
        WxResolver.register("app_h4", WxResolver.byName("com.tencent.mm.app.h4", "com.tencent.mm.app.h3", "com.tencent.mm.app.i3"));
        // 撤回监听接口：使用 DexKitHelper 运行时已解析的监听类列表
        WxResolver.register("ui_chatting_RevokeReceiveMessageListener", cl -> {
            java.util.List<String> listeners = DexKitHelper.getRevokeListenerClasses();
            if (listeners != null) {
                for (String n : listeners) {
                    if (n != null && n.contains("RevokeReceive")) {
                        try {
                            return XposedHelpers.findClass(n, cl);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            return null;
        });
        // 8.0.79 仍存在的补充类（锚点见 resolver_plan 或直接 byName）
        WxResolver.register("plugin_fav_ui_fc", WxResolver.byName("com.tencent.mm.plugin.fav.ui.fc"));
        WxResolver.register("pluginsdk_ui_a", WxResolver.byName("com.tencent.mm.pluginsdk.ui.a"));
        WxResolver.register("ui_chatting_component_so", WxResolver.byName("com.tencent.mm.ui.chatting.component.so"));
        WxResolver.register("ui_conversation_s5", WxResolver.byName("com.tencent.mm.ui.conversation.s5"));
    }
}
