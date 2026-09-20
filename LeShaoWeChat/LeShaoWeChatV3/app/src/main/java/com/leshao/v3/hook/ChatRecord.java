/*
 * ============================================================================
 *  文件名: ChatRecord.java
 *  功能  : 消息记录数据模型(字段与 message 表一一对应)
 *  参照  : 一键导入导出聊天记录.zip / MsgRecord.java
 * ============================================================================
 */
package com.leshao.v3.hook;

import org.json.JSONObject;

import java.util.Base64;

public class ChatRecord {
    public long msgId, msgSvrId, createTime, msgSeq;
    public int type, status, isSend;
    public String talker, content;
    public byte[] lvbuffer;

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("msgId", msgId);
        o.put("msgSvrId", msgSvrId);
        o.put("type", type);
        o.put("status", status);
        o.put("isSend", isSend);
        o.put("createTime", createTime);
        o.put("talker", talker == null ? "" : talker);
        o.put("content", content == null ? "" : content);
        o.put("msgSeq", msgSeq);
        if (lvbuffer != null) {
            o.put("lvbuffer", Base64.getEncoder().encodeToString(lvbuffer));
        }
        return o;
    }

    public static ChatRecord fromJson(JSONObject o) {
        ChatRecord r = new ChatRecord();
        r.msgId = o.optLong("msgId");
        r.msgSvrId = o.optLong("msgSvrId");
        r.type = o.optInt("type", 1);
        r.status = o.optInt("status", 0);
        r.isSend = o.optInt("isSend", 0);
        r.createTime = o.optLong("createTime");
        r.talker = o.optString("talker");
        r.content = o.optString("content");
        r.msgSeq = o.optLong("msgSeq");
        if (o.has("lvbuffer") && !o.isNull("lvbuffer")) {
            try {
                r.lvbuffer = Base64.getDecoder().decode(o.optString("lvbuffer"));
            } catch (Throwable ignored) {
                r.lvbuffer = null;
            }
        }
        return r;
    }
}