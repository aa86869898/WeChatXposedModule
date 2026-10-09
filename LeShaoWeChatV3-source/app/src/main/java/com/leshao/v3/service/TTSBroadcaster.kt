package com.leshao.v3.service

import android.content.Context
import com.leshao.v3.ContextManager
import com.leshao.v3.LogWriter
import com.leshao.v3.model.ModuleConfig
import com.leshao.v3.model.WeChatMessage

object TTSBroadcaster {

    private const val TAG = "TTSBroadcaster"

    @Volatile
    private var sEngine: TtsEngine? = null

    @Volatile
    private var sCubePlayer: CubeTtsPlayer? = null

    @Volatile
    private var sFilter: FilterManager? = null

    @Volatile
    private var sHandler: MessageHandler? = null

    @JvmStatic
    @Synchronized
    fun init(ctx: Context) {
        if (sEngine != null) return
        try {
            sEngine = TtsEngine(ctx)
            sCubePlayer = CubeTtsPlayer(ctx)
            sFilter = FilterManager()
            NicknameResolver.init()
            sHandler = MessageHandler(sEngine, sCubePlayer, sFilter, NicknameResolver())
            LogWriter.log(TAG, "TTS init done")
        } catch (t: Throwable) {
            // v986: 初始化失败不得向上抛出导致微信启动流程崩溃; 已创建的部分保留, 后续调用有 null 守卫。
            LogWriter.log(TAG, "init err: " + t.javaClass.simpleName + " " + t.message)
        }
    }

    @JvmStatic
    fun process(msg: WeChatMessage?, cfg: ModuleConfig?) {
        if (msg == null || cfg == null) return
        if (sHandler == null) return

        try {
            sHandler!!.handle(null, msg.type, msg.talker, msg.content, cfg)
        } catch (t: Throwable) {
            LogWriter.log(TAG, "process err: " + t.message)
        }
    }

    @JvmStatic
    fun handleMessageRaw(msgType: Int, talker: String?, content: String?) {
        if (sHandler == null) {
            LogWriter.log(TAG, "handleMessageRaw: sHandler==null, dropping msg type=$msgType from=$talker")
            return
        }
        try {
            val cfg = ModuleConfig.load(ContextManager.getPrefs())
            sHandler!!.handle(null, msgType, talker, content, cfg)
        } catch (t: Throwable) {
            LogWriter.log(TAG, "handleMessageRaw err: " + t.message)
        }
    }

    @JvmStatic
    fun isSpeaking(): Boolean {
        try {
            if (sEngine != null && sEngine!!.isSpeaking()) return true
            if (sCubePlayer != null && sCubePlayer!!.isSpeaking()) return true
        } catch (ignored: Throwable) {}
        return false
    }

    @JvmStatic
    fun hasPendingSpeak(): Boolean {
        try {
            if (sEngine != null && sEngine!!.hasPendingSpeak()) return true
            if (sCubePlayer != null && sCubePlayer!!.hasPendingSpeak()) return true
        } catch (ignored: Throwable) {}
        return false
    }

    @JvmStatic
    fun setSpeechRate(rate: Float) {
        try {
            sEngine?.setSpeechRate(rate)
        } catch (ignored: Throwable) {}
    }

    @JvmStatic
    fun speakText(text: String?) {
        try {
            sHandler?.speak(text)
        } catch (ignored: Throwable) {}
    }

    @JvmStatic
    fun pause() {
        try {
            sCubePlayer?.pause()
        } catch (ignored: Throwable) {}
        try {
            sEngine?.pause()
        } catch (ignored: Throwable) {}
    }

    @JvmStatic
    fun stopAll() {
        try {
            sCubePlayer?.stop()
        } catch (ignored: Throwable) {}
        try {
            sEngine?.stop()
        } catch (ignored: Throwable) {}
    }

    @JvmStatic
    fun shutdown() {
        try {
            sCubePlayer?.shutdown()
        } catch (ignored: Throwable) {}
        sCubePlayer = null
        try {
            sEngine?.shutdown()
        } catch (ignored: Throwable) {}
        sEngine = null
        sHandler = null
    }
}