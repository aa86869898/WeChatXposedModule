/*
 * ============================================================================
 *  文件名: ChatImportManager.java
 *  功能  : 一键导入聊天记录: 读 JSON -> 构造 e9 -> f9.Bb 插入
 *  参照  : 一键导入导出聊天记录.zip / ImportManager.java
 *  说明  : 不设置 msgId 由微信自增分配(避免主键冲突), 保留 createTime 保证排序。
 * ============================================================================
 */
package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class ChatImportManager {

    private static final String TAG = "ChatImportManager";

    /** 从文件导入, 返回导入成功条数 */
    public static int importFile(ClassLoader cl, String inPath) throws Exception {
        synchronized (WxChatBridge.class) {
            WxChatBridge.init(cl);
            return doImport(inPath);
        }
    }

    private static int doImport(String inPath) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStreamReader r = new InputStreamReader(
                new FileInputStream(inPath), StandardCharsets.UTF_8)) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        }
        JSONObject root = new JSONObject(sb.toString());
        JSONArray sessions = root.optJSONArray("sessions");
        if (sessions == null) return 0;

        int ok = 0;
        for (int s = 0; s < sessions.length(); s++) {
            JSONObject sess = sessions.getJSONObject(s);
            JSONArray msgs = sess.optJSONArray("msgs");
            if (msgs == null) continue;
            for (int i = 0; i < msgs.length(); i++) {
                try {
                    ChatRecord rec = ChatRecord.fromJson(msgs.getJSONObject(i));
                    if (rec.talker == null || rec.talker.isEmpty()) continue;
                    Object e9 = WxChatBridge.newMsg(rec.talker);

                    // 注意: 不设置 msgId, 由微信自增分配; msgSvrId 保留 (0 表示无服务端ID)
                    if (rec.msgSvrId > 0) WxChatBridge.setSvrId(e9, rec.msgSvrId);
                    WxChatBridge.setType(e9, rec.type);
                    WxChatBridge.setStatus(e9, rec.status);
                    WxChatBridge.setIsSend(e9, rec.isSend);
                    WxChatBridge.setCreateTime(e9, rec.createTime);
                    WxChatBridge.setContent(e9, rec.content == null ? "" : rec.content);
                    if (rec.msgSeq > 0) WxChatBridge.setMsgSeq(e9, rec.msgSeq);
                    if (rec.lvbuffer != null) WxChatBridge.setLvbuf(e9, rec.lvbuffer);

                    long id = WxChatBridge.insertMsg(e9);
                    if (id >= 0) ok++;
                } catch (Throwable t) {
                    LogWriter.log(TAG, "import one msg err: " + t.getMessage());
                }
            }
        }
        LogWriter.log(TAG, "import done: " + ok + " msgs <- " + inPath);
        return ok;
    }
}