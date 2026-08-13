package com.leshao.v3.ai;

public final class AiConst {
    public static final String WX_PACKAGE = "com.tencent.mm";

    // 消息类（MsgInfo）
    public static final String CLS_MSG = "com.tencent.mm.storage.e9";
    public static final String F_TALKER    = "field_talker";
    public static final String F_CONTENT   = "field_content";
    public static final String F_TYPE      = "field_type";
    public static final String F_IS_SEND   = "field_isSend";
    public static final String F_CREATE    = "field_createTime";
    public static final String M_GET_TALKER = "N0";
    public static final String M_GET_CONTENT = "S1";
    public static final String M_GET_CONTENT_ALT1 = "I0";
    public static final String M_GET_CONTENT_ALT2 = "j";
    public static final String M_GET_TYPE   = "getType";
    public static final String M_GET_ISSEND = "z0";
    public static final String M_GET_MSGID  = "H0";

    // 存储类（MsgInfoStorage）
    public static final String CLS_STORAGE = "com.tencent.mm.storage.f9";
    public static final String M_QUERY = "Q1";
    public static final String M_INSERT = "H9";
    public static final String M_INSERT2 = "I9";

    // 内核服务定位（md 原坐标，作兜底）
    public static final String CLS_KERNEL = "pa5.n0";
    public static final String M_GET_SERVICE = "c";
    public static final String CLS_MSG_SERVICE = "com.tencent.mm.plugin.messenger.foundation.h2";
    public static final String M_GET_STORAGE = "wj";

    // 聊天 UI
    public static final String CLS_CHATTING_UI = "com.tencent.mm.ui.chatting.ChattingUI";
    public static final String CLS_BASE_FRAGMENT = "com.tencent.mm.ui.chatting.BaseChattingUIFragment";
    public static final String F_CHAT_CONTEXT = "f";
    public static final String M_GET_TALKER_CTX = "t";
    public static final String CLS_CHAT_FOOTER = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter";

    // 消息类型
    public static final int TYPE_TEXT = 1;
    public static final int TYPE_IMAGE = 3;
    public static final int TYPE_VOICE = 34;
    public static final int TYPE_VIDEO = 43;
    public static final int TYPE_EMOJI = 47;
    public static final int TYPE_LOCATION = 48;
    public static final int TYPE_APPMSG = 49;
    public static final int TYPE_SYSTEM = 10000;

    private AiConst() {}
}
