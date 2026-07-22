package com.leshao.v3.service;

import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.model.WeChatMessage;

public class VoiceRelay {

    private static final String TAG = "VoiceRelay";

    /**
     * 自动播放语音消息（后续实现具体播放逻辑）
     */
    public static void process(WeChatMessage msg, ModuleConfig cfg) {
        if (msg == null || cfg == null) return;
        if (msg.type != WeChatMessage.TYPE_VOICE) return;

        LogWriter.log(TAG, "voice relay: talker=" + msg.talker + " time=" + msg.createTime);
        // 后续通过 Hook 微信语音播放 API 实现自动播放
    }
}
