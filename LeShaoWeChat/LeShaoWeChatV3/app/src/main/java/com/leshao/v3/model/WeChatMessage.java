package com.leshao.v3.model;

public class WeChatMessage {
    // V2.1 exact type values from WeChatMsg
    public static final int TYPE_TEXT = 1;
    public static final int TYPE_IMAGE = 3;
    public static final int TYPE_VOICE = 34;
    public static final int TYPE_CARD = 42;
    public static final int TYPE_VIDEO = 43;
    public static final int TYPE_STICKER = 47;
    public static final int TYPE_APPMSG = 49;
    public static final int TYPE_VOIP = 50;
    public static final int TYPE_REDPACKET = 318767153;
    public static final int TYPE_TRANSFER = 419430449;

    public final String talker;
    public final String senderWxid;
    public final String content;
    public final int type;
    public final long createTime;
    public String dbContent;

    public WeChatMessage(String talker, String senderWxid, String content, int type, long createTime) {
        this.talker = talker != null ? talker : "";
        this.senderWxid = senderWxid != null ? senderWxid : "";
        this.content = content != null ? content : "";
        this.type = type;
        this.createTime = createTime;
    }

    public boolean isGroup() { return talker != null && talker.endsWith("@chatroom"); }
    public boolean isText() { return type == TYPE_TEXT; }
    public boolean isImage() { return type == TYPE_IMAGE; }
    public boolean isVideo() { return type == TYPE_VIDEO; }
    public boolean isVoice() { return type == TYPE_VOICE; }
    public boolean isCard() { return type == TYPE_CARD; }
    public boolean isSticker() { return type == TYPE_STICKER; }
    public boolean isRedBag() { return type == TYPE_REDPACKET; }
    public boolean isTransfer() { return type == TYPE_TRANSFER; }
    public boolean isAppMsg() { return type == TYPE_APPMSG; }
    public boolean isVoip() { return type == TYPE_VOIP; }

    public boolean isFileMsg() {
        return isAppMsg() && dbContent != null && dbContent.contains("<appmsg") && dbContent.contains("<title>");
    }

    public boolean isLocationMsg() {
        return isAppMsg() && dbContent != null && dbContent.contains("<location");
    }

    public boolean isFile() {
        return isFileMsg();
    }

    public boolean isLocation() {
        return isLocationMsg();
    }

    public static WeChatMessage fromReflectedObject(Object msgObj) {
        if (msgObj == null) return null;
        try {
            String talker = reflectStr(msgObj, "field_talker");
            String sender = reflectStr(msgObj, "field_senderWxid");
            String content = reflectStr(msgObj, "field_content");
            int type = reflectInt(msgObj, "field_type");
            long createTime = reflectLong(msgObj, "field_createTime");
            WeChatMessage wm = new WeChatMessage(talker, sender, content, type, createTime);
            wm.dbContent = reflectStr(msgObj, "field_dbContent");
            return wm;
        } catch (Throwable t) { return null; }
    }

    private static String reflectStr(Object obj, String field) {
        try { Object v = obj.getClass().getDeclaredField(field).get(obj); return v != null ? v.toString() : ""; }
        catch (Throwable ignored) {}
        return "";
    }

    private static int reflectInt(Object obj, String field) {
        try { return obj.getClass().getDeclaredField(field).getInt(obj); }
        catch (Throwable ignored) { return 0; }
    }

    private static long reflectLong(Object obj, String field) {
        try { return obj.getClass().getDeclaredField(field).getLong(obj); }
        catch (Throwable ignored) { return 0L; }
    }
}
