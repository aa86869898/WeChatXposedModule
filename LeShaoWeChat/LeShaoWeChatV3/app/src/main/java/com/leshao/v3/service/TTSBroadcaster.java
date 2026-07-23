package com.leshao.v3.service;

import android.content.Context;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.model.WeChatMessage;

public class TTSBroadcaster {

    private static final String TAG = "TTSBroadcaster";

    private static volatile TtsEngine sEngine;
    private static volatile FilterManager sFilter;
    private static volatile MessageHandler sHandler;

    public static synchronized void init(Context ctx) {
        if (sEngine != null) return;
        sEngine = new TtsEngine(ctx);
        sFilter = new FilterManager();
        sHandler = new MessageHandler(sEngine, sFilter, new NicknameResolver());
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
        if (sHandler == null) return;
        try {
            ModuleConfig cfg = ModuleConfig.load(ContextManager.getPrefs());
            sHandler.handle(null, msgType, talker, content, cfg);
        } catch (Throwable t) {
            LogWriter.log(TAG, "handleMessageRaw err: " + t.getMessage());
        }
    }

    public static void announceRedPacket(String sender, String wishing, String amount) {
        if (sHandler != null) sHandler.announceRedPacket(sender, wishing, amount);
    }

    public static void announceTransfer(String sender, String amount, String desc) {
        if (sHandler != null) sHandler.announceTransfer(sender, amount, desc);
    }

    public static void stopAll() {
        if (sEngine != null) sEngine.stop();
    }

    public static void shutdown() {
        if (sEngine != null) sEngine.shutdown();
        sEngine = null;
        sHandler = null;
    }
}
