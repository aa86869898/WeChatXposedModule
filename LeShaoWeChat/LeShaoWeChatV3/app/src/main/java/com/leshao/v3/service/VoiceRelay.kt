package com.leshao.v3.service

import com.leshao.v3.LogWriter
import com.leshao.v3.model.WeChatMessage

/**
 * 语音消息中继 — 已由 VoiceAutoPlay Hook 接管
 * VoiceAutoPlay 通过 Xposed hook VoiceComponent/h5/v0 实现原生自动播放
 * 此处的 process() 保留为占位, 避免破坏调用链
 */
object VoiceRelay {

    private const val TAG = "VoiceRelay"

    @JvmStatic
    fun process(talker: String?, msgType: Int) {
        if (msgType != WeChatMessage.TYPE_VOICE) return
        LogWriter.log(TAG, "voice msg from: " + talker + " (handled by VoiceAutoPlay hooks)")
    }
}