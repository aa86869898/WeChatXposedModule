package com.leshao.v3.service;

import com.leshao.v3.LogWriter;

/**
 * 语音消息中继 — 已由 VoiceAutoPlay Hook 接管
 * VoiceAutoPlay 通过 Xposed hook VoiceComponent/h5/v0 实现原生自动播放
 * 此处的 process() 保留为占位, 避免破坏调用链
 */
public class VoiceRelay {

    private static final String TAG = "VoiceRelay";

    public static void process(String talker, int msgType) {
        if (msgType != com.leshao.v3.model.WeChatMessage.TYPE_VOICE) return;
        LogWriter.log(TAG, "voice msg from: " + talker + " (handled by VoiceAutoPlay hooks)");
    }
}
