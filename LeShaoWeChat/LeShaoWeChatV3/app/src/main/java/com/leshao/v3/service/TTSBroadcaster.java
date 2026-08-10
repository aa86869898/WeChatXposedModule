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
        sEngine = new TtsEngine(ctx);
        sCubePlayer = new CubeTtsPlayer(ctx);
        sFilter = new FilterManager();
        NicknameResolver.init();
        sHandler = new MessageHandler(sEngine, sCubePlayer, sFilter, new NicknameResolver());
        LogWriter.log(TAG, "TTS init done");
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

    public static void announceRedPacket(String sender, String chatroom, String wishing, String amount) {
        if (sHandler != null) sHandler.announceRedPacket(sender, chatroom, wishing, amount);
    }

    public static void announceTransfer(String sender, String chatroom, String amount, String desc) {
        if (sHandler != null) sHandler.announceTransfer(sender, chatroom, amount, desc);
    }

    public static boolean isSpeaking() {
        if (sEngine != null && sEngine.isSpeaking()) return true;
        if (sCubePlayer != null && sCubePlayer.isSpeaking()) return true;
        return false;
    }

    public static boolean hasPendingSpeak() {
        if (sEngine != null && sEngine.hasPendingSpeak()) return true;
        if (sCubePlayer != null && sCubePlayer.hasPendingSpeak()) return true;
        return false;
    }

    public static void setSpeechRate(float rate) {
        if (sEngine != null) sEngine.setSpeechRate(rate);
    }

    public static void speakText(String text) {
        if (sHandler != null) sHandler.speak(text);
    }

    public static void pause() {
        if (sCubePlayer != null) sCubePlayer.pause();
        if (sEngine != null) sEngine.pause();
    }

    public static void stopAll() {
        if (sCubePlayer != null) sCubePlayer.stop();
        if (sEngine != null) sEngine.stop();
    }

    public static void shutdown() {
        if (sCubePlayer != null) sCubePlayer.shutdown();
        sCubePlayer = null;
        if (sEngine != null) sEngine.shutdown();
        sEngine = null;
        sHandler = null;
    }
}
