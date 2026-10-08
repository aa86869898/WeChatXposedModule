package com.leshao.v3.service;

import android.content.Context;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.model.WeChatMessage;

public class TTSBroadcaster {

    private static final String TAG = "TTSBroadcaster";

    private static volatile TtsEngine sEngine;
    private static volatile CubeTtsPlayer sCubePlayer;
    private static volatile FilterManager sFilter;
    private static volatile MessageHandler sHandler;

    public static synchronized void init(Context ctx) {
        if (sEngine != null) return;
        try {
            sEngine = new TtsEngine(ctx);
            sCubePlayer = new CubeTtsPlayer(ctx);
            sFilter = new FilterManager();
            NicknameResolver.init();
            sHandler = new MessageHandler(sEngine, sCubePlayer, sFilter, new NicknameResolver());
            LogWriter.log(TAG, "TTS init done");
        } catch (Throwable t) {
            // v986: 初始化失败不得向上抛出导致微信启动流程崩溃; 已创建的部分保留, 后续调用有 null 守卫。
            LogWriter.log(TAG, "init err: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    public static void process(WeChatMessage msg, ModuleConfig cfg) {
        if (msg == null || cfg == null) return;
        if (sHandler == null) return;

        try {
            sHandler.handle(null, msg.type, msg.talker, msg.content, cfg);
        } catch (Throwable t) {
            LogWriter.log(TAG, "process err: " + t.getMessage());
        }
    }

    public static void handleMessageRaw(int msgType, String talker, String content) {
        if (sHandler == null) {
            LogWriter.log(TAG, "handleMessageRaw: sHandler==null, dropping msg type=" + msgType + " from=" + talker);
            return;
        }
        try {
            ModuleConfig cfg = ModuleConfig.load(ContextManager.getPrefs());
            sHandler.handle(null, msgType, talker, content, cfg);
        } catch (Throwable t) {
            LogWriter.log(TAG, "handleMessageRaw err: " + t.getMessage());
        }
    }

    public static boolean isSpeaking() {
        try {
            if (sEngine != null && sEngine.isSpeaking()) return true;
            if (sCubePlayer != null && sCubePlayer.isSpeaking()) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean hasPendingSpeak() {
        try {
            if (sEngine != null && sEngine.hasPendingSpeak()) return true;
            if (sCubePlayer != null && sCubePlayer.hasPendingSpeak()) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    public static void setSpeechRate(float rate) {
        try { if (sEngine != null) sEngine.setSpeechRate(rate); } catch (Throwable ignored) {}
    }

    public static void speakText(String text) {
        try { if (sHandler != null) sHandler.speak(text); } catch (Throwable ignored) {}
    }

    public static void pause() {
        try { if (sCubePlayer != null) sCubePlayer.pause(); } catch (Throwable ignored) {}
        try { if (sEngine != null) sEngine.pause(); } catch (Throwable ignored) {}
    }

    public static void stopAll() {
        try { if (sCubePlayer != null) sCubePlayer.stop(); } catch (Throwable ignored) {}
        try { if (sEngine != null) sEngine.stop(); } catch (Throwable ignored) {}
    }

    public static void shutdown() {
        try { if (sCubePlayer != null) sCubePlayer.shutdown(); } catch (Throwable ignored) {}
        sCubePlayer = null;
        try { if (sEngine != null) sEngine.shutdown(); } catch (Throwable ignored) {}
        sEngine = null;
        sHandler = null;
    }
}
