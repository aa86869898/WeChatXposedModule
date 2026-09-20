/*
 * ============================================================================
 *  文件名: ChatExportManager.java
 *  功能  : 一键导出聊天记录: 遍历会话 -> 分页拉取全部消息 -> 序列化 JSON
 *  参照  : 一键导入导出聊天记录.zip / ExportManager.java
 *  说明  : 升级自 MsgExport(单会话 SQL rawQuery TXT/HTML 导出),
 *          本管理器走 WxChatBridge 存储反射桥(f9.M7/H2 分页), 全量导出为 JSON。
 * ============================================================================
 */
package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ChatExportManager {

    private static final String TAG = "ChatExportManager";
    public static final int PAGE = 500;

    /** 全部导出: 返回消息总数 */
    public static int exportAll(ClassLoader cl, String outPath) throws Exception {
        synchronized (WxChatBridge.class) {
            WxChatBridge.init(cl);
            return doExport(outPath);
        }
    }

    /** 按 talker 过滤导出(逗号分隔, 可空=全部) */
    public static int exportFiltered(ClassLoader cl, String outPath, List<String> talkers) throws Exception {
        synchronized (WxChatBridge.class) {
            WxChatBridge.init(cl);
            return doExportFiltered(outPath, talkers);
        }
    }

    private static int doExport(String outPath) throws Exception {
        List<String> talkers = WxChatBridge.getAllTalkers();
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("exportTime", System.currentTimeMillis());
        JSONArray sessions = new JSONArray();
        int total = 0;

        for (String talker : talkers) {
            if (talker == null || talker.isEmpty()) continue;
            JSONArray msgs = exportTalker(talker);
            JSONObject sess = new JSONObject();
            sess.put("talker", talker);
            sess.put("isGroup", talker.endsWith("@chatroom"));
            sess.put("count", msgs.length());
            sess.put("msgs", msgs);
            sessions.put(sess);
            total += msgs.length();
        }

        root.put("sessions", sessions);
        writeJson(outPath, root);
        LogWriter.log(TAG, "exportAll done: " + total + " msgs, " + talkers.size() + " sessions -> " + outPath);
        return total;
    }

    private static int doExportFiltered(String outPath, List<String> talkers) throws Exception {
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("exportTime", System.currentTimeMillis());
        JSONArray sessions = new JSONArray();
        int total = 0;

        for (String talker : talkers) {
            if (talker == null || talker.isEmpty()) continue;
            JSONArray msgs = exportTalker(talker);
            JSONObject sess = new JSONObject();
            sess.put("talker", talker);
            sess.put("isGroup", talker.endsWith("@chatroom"));
            sess.put("count", msgs.length());
            sess.put("msgs", msgs);
            sessions.put(sess);
            total += msgs.length();
        }

        root.put("sessions", sessions);
        writeJson(outPath, root);
        LogWriter.log(TAG, "exportFiltered done: " + total + " msgs, " + talkers.size() + " sessions");
        return total;
    }

    /** 分页拉取单会话全部消息(时间戳递减锚点) */
    private static JSONArray exportTalker(String talker) {
        JSONArray msgs = new JSONArray();
        try {
            long anchor = Long.MAX_VALUE;
            int guard = 0;
            while (guard++ < 1_000_000) {
                List<?> batch;
                if (anchor == Long.MAX_VALUE) {
                    batch = WxChatBridge.getLastMsgs(talker, PAGE);
                } else {
                    batch = WxChatBridge.getMsgsBefore(talker, anchor, PAGE);
                }
                if (batch == null || batch.isEmpty()) break;

                boolean any = false;
                long newAnchor = anchor;
                for (Object e9 : batch) {
                    if (e9 == null) continue;
                    long t = WxChatBridge.getCreateTime(e9);
                    if (t >= anchor) continue;
                    ChatRecord rec = toRecord(e9);
                    msgs.put(rec.toJson());
                    any = true;
                    newAnchor = Math.min(newAnchor, t);
                }
                if (!any) break;
                anchor = newAnchor;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "exportTalker(" + talker + ") err: " + t.getMessage());
        }
        return msgs;
    }

    private static ChatRecord toRecord(Object e9) throws Exception {
        ChatRecord r = new ChatRecord();
        r.msgId = WxChatBridge.getMsgId(e9);
        r.msgSvrId = WxChatBridge.getSvrId(e9);
        r.type = WxChatBridge.getType(e9);
        r.status = WxChatBridge.getStatus(e9);
        r.isSend = WxChatBridge.getIsSend(e9);
        r.createTime = WxChatBridge.getCreateTime(e9);
        r.talker = WxChatBridge.getTalker(e9);
        r.content = WxChatBridge.getContent(e9);
        r.msgSeq = WxChatBridge.getMsgSeq(e9);
        r.lvbuffer = WxChatBridge.getLvbuf(e9);
        return r;
    }

    private static void writeJson(String outPath, JSONObject root) throws Exception {
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(outPath), StandardCharsets.UTF_8)) {
            w.write(root.toString(1));
        }
    }
}