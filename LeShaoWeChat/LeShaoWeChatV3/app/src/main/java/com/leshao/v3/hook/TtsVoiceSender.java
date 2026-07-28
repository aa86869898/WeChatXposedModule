package com.leshao.v3.hook;

import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class TtsVoiceSender {

    private static final String TAG = "TtsVoiceSender";
    private static final String TTS_PREFIX = "#tts ";
    private static TextToSpeech sTts;
    private static ClassLoader sClassLoader;
    private static boolean sReady;

    public static void hook(ClassLoader cl) {
        sClassLoader = cl;

        sTts = new TextToSpeech(ContextManager.getAppContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = sTts.setLanguage(Locale.CHINESE);
                sReady = (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED);
                LogWriter.log(TAG, "TTS init: " + (sReady ? "OK" : "FAIL"));
            }
        });

        sTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {}
            @Override public void onDone(String utteranceId) {}
            @Override public void onError(String utteranceId) {
                LogWriter.log(TAG, "TTS synth error: " + utteranceId);
            }
        });

        hookChatFooterSend(cl);
    }

    private static void hookChatFooterSend(ClassLoader cl) {
        try {
            Class<?> chatFooter = XposedHelpers.findClass(
                    "com.tencent.mm.pluginsdk.ui.chat.ChatFooter", cl);
            Class<?> e9Cls = cl.loadClass("com.tencent.mm.storage.e9");
            Class<?> a35g = XposedHelpers.findClass("a35.g", cl);

            for (java.lang.reflect.Method m : chatFooter.getDeclaredMethods()) {
                if (!m.getName().equals("F") || m.getParameterCount() != 2) continue;
                if (m.getParameterTypes()[0] != e9Cls) continue;
                if (m.getParameterTypes()[1] == a35g) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            interceptSend(param);
                        }
                    });
                    LogWriter.log(TAG, "ChatFooter.F hooked OK via hookMethod");
                    return;
                }
            }
            LogWriter.log(TAG, "ChatFooter.F: exact match not found, fallback hookAllMethods");
            XposedBridge.hookAllMethods(chatFooter, "F", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    interceptSend(param);
                }
            });
            LogWriter.log(TAG, "ChatFooter.F hooked OK via hookAllMethods");
        } catch (Throwable t) {
            LogWriter.log(TAG, "ChatFooter.F hook fail: " + t.getMessage());
        }
    }

    private static void interceptSend(XC_MethodHook.MethodHookParam param) {
        try {
            Object msgInfo = param.args[0];

            String content = null;
            // 尝试多种方式读取 content
            try { content = (String) XposedHelpers.getObjectField(msgInfo, "field_content"); } catch (Throwable ignored) {}
            if (content == null) try { content = (String) XposedHelpers.callMethod(msgInfo, "I0"); } catch (Throwable ignored) {}
            if (content == null) try { content = (String) XposedHelpers.callMethod(msgInfo, "j"); } catch (Throwable ignored) {}

            LogWriter.log(TAG, "ChatFooter.F fired, content=" + (content != null ? content.substring(0, Math.min(content.length(), 40)) : "null"));
            if (content == null || !content.startsWith(TTS_PREFIX)) return;

            String text = content.substring(TTS_PREFIX.length()).trim();
            if (text.isEmpty()) return;

            LogWriter.log(TAG, "#tts detected: " + text.substring(0, Math.min(text.length(), 50)));

            String talker = null;
            try { talker = (String) XposedHelpers.getObjectField(msgInfo, "field_talker"); } catch (Throwable ignored) {}
            if (talker == null) try { talker = (String) XposedHelpers.callMethod(msgInfo, "N0"); } catch (Throwable ignored) {}
            if (talker == null || talker.isEmpty()) {
                LogWriter.log(TAG, "talker is empty");
                return;
            }

            param.setResult(null);

            final String fText = text;
            final String fTalker = talker;
            new Thread(() -> synthesizeAndSend(fText, fTalker)).start();

        } catch (Throwable e) {
            LogWriter.log(TAG, "hook err: " + e.getMessage());
        }
    }

    static void synthesizeAndSend(String text, String talker) {
        try {
            if (!sReady || sTts == null) {
                LogWriter.log(TAG, "TTS not ready");
                return;
            }

            File tmpDir = new File(ContextManager.getAppContext().getCacheDir(), "tts_voice");
            tmpDir.mkdirs();

            File wavFile = new File(tmpDir, "tts_" + System.currentTimeMillis() + ".wav");
            File amrFile = new File(tmpDir, "tts_" + System.currentTimeMillis() + ".amr");

            CountDownLatch latch = new CountDownLatch(1);
            final int[] synthResult = {TextToSpeech.ERROR};

            sTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}
                @Override
                public void onDone(String utteranceId) {
                    synthResult[0] = TextToSpeech.SUCCESS;
                    latch.countDown();
                }
                @Override
                public void onError(String utteranceId) {
                    LogWriter.log(TAG, "synth error: " + utteranceId);
                    latch.countDown();
                }
            });

            int result = sTts.synthesizeToFile(text, null, wavFile, "tts_voice");
            if (result != TextToSpeech.SUCCESS) {
                LogWriter.log(TAG, "synthesizeToFile failed: " + result);
                return;
            }

            boolean finished = latch.await(30, TimeUnit.SECONDS);
            if (!finished) {
                LogWriter.log(TAG, "synth timeout");
                return;
            }
            if (synthResult[0] != TextToSpeech.SUCCESS) {
                LogWriter.log(TAG, "synth failed");
                return;
            }

            LogWriter.log(TAG, "WAV generated: " + wavFile.length() + " bytes");

            int duration = wavToAmr(wavFile, amrFile);
            if (duration <= 0 || amrFile.length() == 0) {
                LogWriter.log(TAG, "AMR encode failed");
                return;
            }

            LogWriter.log(TAG, "AMR: " + amrFile.length() + " bytes, " + duration + "ms");

            boolean sent = VoiceForwardHook.sendViaSceneVoice(
                    null, sClassLoader, talker, amrFile.getAbsolutePath(), duration, null);

            LogWriter.log(TAG, "send result: " + sent);

            wavFile.delete();
            amrFile.delete();

        } catch (Throwable e) {
            LogWriter.log(TAG, "synthAndSend err: " + e.getMessage());
        }
    }

    private static int wavToAmr(File wavFile, File amrFile) {
        try (FileInputStream fis = new FileInputStream(wavFile)) {
            byte[] header = new byte[44];
            int read = fis.read(header);
            if (read < 44) return -1;

            int sampleRate = ((header[24] & 0xFF) | ((header[25] & 0xFF) << 8)
                    | ((header[26] & 0xFF) << 16) | ((header[27] & 0xFF) << 24));
            int channels = (header[22] & 0xFF) | ((header[23] & 0xFF) << 8);
            int bitsPerSample = (header[34] & 0xFF) | ((header[35] & 0xFF) << 8);
            int dataSize = ((header[40] & 0xFF) | ((header[41] & 0xFF) << 8)
                    | ((header[42] & 0xFF) << 16) | ((header[43] & 0xFF) << 24));

            byte[] pcmBytes = new byte[dataSize];
            int totalRead = 0;
            while (totalRead < dataSize) {
                int r = fis.read(pcmBytes, totalRead, dataSize - totalRead);
                if (r < 0) break;
                totalRead += r;
            }

            int srcSampleRate = sampleRate;
            int srcChannels = channels;
            byte[] mono8000Pcm;

            if (srcSampleRate == 8000 && srcChannels == 1 && bitsPerSample == 16) {
                mono8000Pcm = pcmBytes;
            } else {
                int srcFrameSize = srcChannels * (bitsPerSample / 8);
                int srcFrames = totalRead / srcFrameSize;
                double ratio = (double) srcSampleRate / 8000.0;

                ByteArrayOutputStream resampled = new ByteArrayOutputStream();
                for (int i = 0; i < srcFrames; i++) {
                    int srcIdx = i * srcFrameSize;
                    if (i % Math.round(ratio) == 0) {
                        resampled.write(pcmBytes[srcIdx]);
                        resampled.write(pcmBytes[srcIdx + 1]);
                    }
                }
                mono8000Pcm = resampled.toByteArray();
            }

            LogWriter.log(TAG, "PCM: srcRate=" + srcSampleRate + " ch=" + srcChannels
                    + " bits=" + bitsPerSample + " → mono 8kHz " + mono8000Pcm.length + " bytes");

            ByteArrayOutputStream amrBaos = new ByteArrayOutputStream();
            ByteArrayInputStream pcmBais = new ByteArrayInputStream(mono8000Pcm);

            Class<?> amrClass = Class.forName("android.media.AmrInputStream");
            Constructor<?> ctor = amrClass.getDeclaredConstructor(InputStream.class);
            ctor.setAccessible(true);
            InputStream amrStream = (InputStream) ctor.newInstance(pcmBais);

            byte[] buf = new byte[512];
            int len;
            while ((len = amrStream.read(buf)) > 0) {
                amrBaos.write(buf, 0, len);
            }
            amrStream.close();

            byte[] amrData = amrBaos.toByteArray();

            java.io.FileOutputStream fos = new java.io.FileOutputStream(amrFile);
            fos.write("#!AMR\n".getBytes());
            fos.write(amrData);
            fos.close();

            int totalSamples = mono8000Pcm.length / 2;
            int durationMs = (int) ((long) totalSamples * 1000 / 8000);

            return durationMs;

        } catch (Throwable e) {
            LogWriter.log(TAG, "wavToAmr err: " + e.getMessage());
            return -1;
        }
    }
}
