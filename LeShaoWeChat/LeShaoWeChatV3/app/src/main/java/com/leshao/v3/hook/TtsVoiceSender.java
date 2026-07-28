package com.leshao.v3.hook;

import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * TTS 语音发送 v43
 * - 持久 UtteranceProgressListener，不覆盖
 * - triggerVoiceAutoPlay: tts.speak("语音") → onDone → playPendingVoice
 * - synthesizeAndSend: synthesizeToFile + latch 等待
 */
public class TtsVoiceSender {

    private static final String TAG = "TtsVoiceSender";
    private static final String TTS_PREFIX = "#tts ";
    private static TextToSpeech sTts;
    private static ClassLoader sClassLoader;
    private static boolean sReady;
    private static volatile CountDownLatch sSynthesisLatch;
    private static volatile int sSynthesisResult;

    public static void hook(ClassLoader cl) {
        sClassLoader = cl;

        sTts = new TextToSpeech(ContextManager.getAppContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = sTts.setLanguage(Locale.CHINESE);
                sReady = (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED);
                LogWriter.log(TAG, "TTS init: " + (sReady ? "OK" : "FAIL"));

                if (sReady) {
                    setPersistentListener();
                }
            }
        });

        hookSendMsgSuccessEvent(cl);
    }

    // ========== 持久 UtteranceProgressListener ==========

    private static void setPersistentListener() {
        if (sTts == null) return;
        sTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String uid) {}

            @Override
            public void onDone(String uid) {
                android.util.Log.e(TAG, ">>> onDone uid=" + uid);
                LogWriter.log(TAG, "onDone: " + uid);

                // 语音自动播放
                if (uid != null && uid.startsWith("tts_")) {
                    String talker = uid.substring(4);
                    android.util.Log.e(TAG, ">>> triggering voice play for " + talker);
                    VoiceAutoPlay.playPendingVoice(talker);
                }

                // 合成完成信号
                if (sSynthesisLatch != null) {
                    sSynthesisResult = TextToSpeech.SUCCESS;
                    sSynthesisLatch.countDown();
                }
            }

            @Override
            public void onError(String uid) {
                android.util.Log.e(TAG, ">>> onError uid=" + uid);
                LogWriter.log(TAG, "onError: " + uid);

                if (uid != null && uid.startsWith("tts_")) {
                    String talker = uid.substring(4);
                    VoiceAutoPlay.playPendingVoice(talker);
                }

                if (sSynthesisLatch != null) {
                    sSynthesisResult = TextToSpeech.ERROR;
                    sSynthesisLatch.countDown();
                }
            }
        });
        android.util.Log.e(TAG, ">>> persistent listener set OK");
    }

    // ========== VoiceAutoPlay 触发 ==========

    public static void triggerVoiceAutoPlay(String talker) {
        try {
            if (!sReady || sTts == null) {
                android.util.Log.e(TAG, ">>> trigger: TTS not ready");
                return;
            }
            Bundle params = new Bundle();
            String uid = "tts_" + talker;
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid);
            sTts.speak("语音", TextToSpeech.QUEUE_ADD, params, uid);
            android.util.Log.e(TAG, ">>> trigger ok uid=" + uid);
        } catch (Throwable e) {
            android.util.Log.e(TAG, ">>> trigger err: " + e.getMessage());
        }
    }

    // ========== SendMsgSuccessEvent.callback ==========

    private static void hookSendMsgSuccessEvent(ClassLoader cl) {
        try {
            Class<?> eventClass = XposedHelpers.findClass(
                    "com.tencent.mm.autogen.events.SendMsgSuccessEvent", cl);

            XposedBridge.hookAllMethods(eventClass, "callback", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    android.util.Log.e(TAG, "!!! SendMsgSuccessEvent.callback RAW FIRED");
                    handleEvent(param.args[0]);
                }
            });

            XposedBridge.hookAllMethods(eventClass, "publish", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    android.util.Log.e(TAG, "!!! SendMsgSuccessEvent.publish RAW FIRED");
                    handleEvent(param.args[0]);
                }
            });

            XposedBridge.log("[TTS] SendMsgSuccessEvent hooked OK");
            LogWriter.log(TAG, "SendMsgSuccessEvent hooked OK");
        } catch (Throwable t) {
            XposedBridge.log("[TTS] SendMsgSuccessEvent FAIL: " + t.getMessage());
            LogWriter.log(TAG, "SendMsgSuccessEvent fail: " + t.getMessage());
        }
    }

    private static void handleEvent(Object event) {
        try {
            Object data = XposedHelpers.getObjectField(event, "data");
            if (data == null) return;

            LogWriter.log(TAG, "SendMsgSuccess, data fields:");
            for (Field f : data.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                try {
                    LogWriter.log(TAG, "  data." + f.getName() + " type=" + f.getType().getSimpleName());
                } catch (Throwable ignored) {}
            }

            Object e9 = findE9InData(data);
            if (e9 == null) {
                LogWriter.log(TAG, "e9 not found in SendMsgSuccessEvent.data");
                return;
            }

            String content = null;
            try { content = (String) XposedHelpers.callMethod(e9, "I0"); }
            catch (Throwable ignored) {}
            if (content == null) try { content = (String) XposedHelpers.callMethod(e9, "j"); }
            catch (Throwable ignored) {}
            if (content == null) try { content = (String) XposedHelpers.getObjectField(e9, "field_content"); }
            catch (Throwable ignored) {}

            if (content == null) {
                LogWriter.log(TAG, "SendMsgSuccess: content null");
                return;
            }

            LogWriter.log(TAG, "SendMsgSuccess: content=[" + content.substring(0, Math.min(content.length(), 40)) + "]");

            if (!content.startsWith(TTS_PREFIX)) return;

            String talker = null;
            try { talker = (String) XposedHelpers.callMethod(e9, "N0"); }
            catch (Throwable ignored) {}
            if (talker == null) try { talker = (String) XposedHelpers.getObjectField(e9, "field_talker"); }
            catch (Throwable ignored) {}

            if (talker == null || talker.isEmpty()) {
                LogWriter.log(TAG, "#tts detected but talker null");
                return;
            }

            String text = content.substring(TTS_PREFIX.length()).trim();
            if (text.isEmpty()) return;

            XposedBridge.log("[TTS] SendMsgSuccess #tts: " + text + " -> " + talker);
            LogWriter.log(TAG, "#tts detected via SendMsgSuccess: " + text + " talker=" + talker);

            final String fText = text;
            final String fTalker = talker;
            new Thread(() -> synthesizeAndSend(fText, fTalker)).start();

        } catch (Throwable e) {
            XposedBridge.log("[TTS] handleEvent err: " + e.getMessage());
        }
    }

    private static Object findE9InData(Object data) {
        for (Field f : data.getClass().getDeclaredFields()) {
            try {
                f.setAccessible(true);
                Object val = f.get(data);
                if (val == null) continue;
                try {
                    XposedHelpers.callMethod(val, "getType");
                    LogWriter.log(TAG, "e9 found in data." + f.getName() + ": " + val.getClass().getName());
                    return val;
                } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // ========== TTS 合成与发送 ==========

    public static void synthesizeAndSend(String text, String talker) {
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
            sSynthesisLatch = latch;
            sSynthesisResult = TextToSpeech.ERROR;
            String utteranceId = "synth_" + talker;

            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
            int result = sTts.synthesizeToFile(text, params, wavFile, utteranceId);
            if (result != TextToSpeech.SUCCESS) {
                sSynthesisLatch = null;
                LogWriter.log(TAG, "synthesizeToFile failed: " + result);
                wavFile.delete();
                return;
            }

            boolean finished = latch.await(30, TimeUnit.SECONDS);
            sSynthesisLatch = null;
            if (!finished) {
                LogWriter.log(TAG, "synth timeout");
                wavFile.delete();
                return;
            }
            if (sSynthesisResult != TextToSpeech.SUCCESS) {
                LogWriter.log(TAG, "synth failed");
                wavFile.delete();
                return;
            }

            LogWriter.log(TAG, "WAV: " + wavFile.length() + " bytes");

            int duration = wavToAmr(wavFile, amrFile);
            wavFile.delete();

            if (duration <= 0 || amrFile.length() < 50) {
                LogWriter.log(TAG, "AMR fail, size=" + amrFile.length());
                amrFile.delete();
                return;
            }

            LogWriter.log(TAG, "AMR: " + amrFile.length() + " bytes, " + duration + "ms");

            boolean sent = sendVoice(amrFile.getAbsolutePath(), duration, talker);
            LogWriter.log(TAG, "send result: " + sent);

            // 不删除文件！微信异步上传需要源文件存在
            // amrFile.deleteOnExit() 让系统在进程退出时清理

        } catch (Throwable e) {
            LogWriter.log(TAG, "synthAndSend err: " + e.getMessage());
        }
    }

    private static boolean sendVoice(String filePath, int duration, String talker) {
        try {
            Class<?> y21x0 = XposedHelpers.findClass("y21.x0", sClassLoader);

            // 尝试 r() — 全自动: g()+复制+t()+刷新
            try {
                Object rResult = XposedHelpers.callStaticMethod(y21x0, "r", talker, filePath, duration);
                android.util.Log.e(TAG, ">>> y21.x0.r(" + talker + "," + filePath + "," + duration + ") = " + rResult);
                if (rResult != null) {
                    LogWriter.log(TAG, "sent via r(): " + rResult);
                    return true;
                }
            } catch (Throwable rErr) {
                android.util.Log.e(TAG, ">>> y21.x0.r() not available: " + rErr.getMessage());
            }

            // 手动管线: g() → 复制到voice2 → t() → 刷新
            Class<?> y21p0 = XposedHelpers.findClass("y21.p0", sClassLoader);

            // Step 1: g(talker, "amr_") → 创建 w0 + 新文件名
            String newName = (String) XposedHelpers.callStaticMethod(y21x0, "g", talker, "amr_");
            android.util.Log.e(TAG, ">>> g() → newName=" + newName);
            if (newName == null) {
                LogWriter.log(TAG, "g() returned null");
                return false;
            }

            // Step 2: 找 voice2 目录
            String voice2Dir = findVoice2Dir();
            if (voice2Dir == null) {
                LogWriter.log(TAG, "voice2 dir not found");
                return false;
            }

            // Step 3: 复制到 voice2/msg_{newName}.amr
            String dstPath = voice2Dir + "msg_" + newName + ".amr";
            android.util.Log.e(TAG, ">>> copying to " + dstPath);
            java.io.File dstFile = new java.io.File(dstPath);
            dstFile.getParentFile().mkdirs();
            java.nio.file.Files.copy(
                java.nio.file.Paths.get(filePath),
                java.nio.file.Paths.get(dstPath),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LogWriter.log(TAG, "copy to voice2 ok: " + dstPath);

            // Step 4: t(newName, duration, 0, null) → 写DB
            boolean ok = (Boolean) XposedHelpers.callStaticMethod(y21x0, "t",
                    newName, duration, 0, null);
            android.util.Log.e(TAG, ">>> t(" + newName + "," + duration + ",0,null) = " + ok);
            if (!ok) {
                LogWriter.log(TAG, "t() returned false");
                return false;
            }

            // Step 5: kj().e() 刷新 → 触发上传
            Object q0 = XposedHelpers.callStaticMethod(y21p0, "kj");
            XposedHelpers.callMethod(q0, "e");
            android.util.Log.e(TAG, ">>> kj().e() done");
            LogWriter.log(TAG, "pipeline complete: " + newName);

            return true;
        } catch (Throwable t) {
            android.util.Log.e(TAG, ">>> sendVoice err: " + t.getMessage());
            LogWriter.log(TAG, "sendVoice err: " + t.getMessage());
            // 兜底: 旧方式（不删文件）
            try {
                Class<?> y21x0 = XposedHelpers.findClass("y21.x0", sClassLoader);
                return (Boolean) XposedHelpers.callStaticMethod(y21x0, "t", filePath, duration, 0, null);
            } catch (Throwable t2) {
                return false;
            }
        }
    }

    private static String findVoice2Dir() {
        try {
            java.io.File microMsg = new java.io.File("/data/user/0/com.tencent.mm/MicroMsg");
            if (!microMsg.exists()) return null;
            java.io.File[] dirs = microMsg.listFiles();
            if (dirs == null) return null;
            for (java.io.File dir : dirs) {
                if (dir.isDirectory() && dir.getName().length() >= 32) {
                    java.io.File voice2 = new java.io.File(dir, "voice2");
                    if (voice2.exists() && voice2.isDirectory()) {
                        String path = voice2.getAbsolutePath() + "/";
                        android.util.Log.e(TAG, ">>> voice2 dir: " + path);
                        return path;
                    }
                }
            }
        } catch (Throwable t) {
            android.util.Log.e(TAG, ">>> findVoice2Dir err: " + t.getMessage());
        }
        return null;
    }

    // ========== WAV -> AMR ==========

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

            byte[] mono8000Pcm;
            if (sampleRate == 8000 && channels == 1 && bitsPerSample == 16) {
                mono8000Pcm = pcmBytes;
            } else {
                int srcFrameSize = channels * (bitsPerSample / 8);
                int srcFrames = totalRead / srcFrameSize;
                double ratio = (double) sampleRate / 8000.0;

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

            LogWriter.log(TAG, "PCM: " + sampleRate + "Hz " + channels + "ch " + bitsPerSample + "bit -> mono8kHz " + mono8000Pcm.length + " bytes");

            byte[] amrData = encodeAmr(mono8000Pcm);

            FileOutputStream fos = new FileOutputStream(amrFile);
            fos.write("#!AMR\n".getBytes());
            fos.write(amrData);
            fos.close();

            int totalSamples = mono8000Pcm.length / 2;
            return (int) ((long) totalSamples * 1000 / 8000);

        } catch (Throwable e) {
            LogWriter.log(TAG, "wavToAmr err: " + e.getMessage());
            return -1;
        }
    }

    private static byte[] encodeAmr(byte[] pcm8000) {
        try {
            android.media.MediaCodec codec = android.media.MediaCodec.createEncoderByType("audio/3gpp");
            android.media.MediaFormat fmt = android.media.MediaFormat.createAudioFormat("audio/3gpp", 8000, 1);
            fmt.setInteger(android.media.MediaFormat.KEY_BIT_RATE, 12200);
            fmt.setInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            codec.configure(fmt, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            java.nio.ByteBuffer[] inBufs = codec.getInputBuffers();
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();

            int offset = 0;
            int frameSize = 320;
            boolean eosSent = false;
            ByteArrayOutputStream amrBaos = new ByteArrayOutputStream();

            while (!eosSent) {
                int inIdx = codec.dequeueInputBuffer(10000);
                if (inIdx < 0) continue;

                java.nio.ByteBuffer inBuf = inBufs[inIdx];
                inBuf.clear();

                int remaining = pcm8000.length - offset;
                if (remaining <= 0) {
                    codec.queueInputBuffer(inIdx, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    eosSent = true;
                } else {
                    int chunk = Math.min(remaining, frameSize);
                    inBuf.put(pcm8000, offset, chunk);
                    codec.queueInputBuffer(inIdx, 0, chunk, 0, 0);
                    offset += chunk;
                }

                int outIdx = codec.dequeueOutputBuffer(info, 10000);
                while (outIdx >= 0) {
                    java.nio.ByteBuffer outBuf = codec.getOutputBuffers()[outIdx];
                    byte[] outData = new byte[info.size];
                    outBuf.position(info.offset);
                    outBuf.get(outData, 0, info.size);
                    amrBaos.write(outData);
                    codec.releaseOutputBuffer(outIdx, false);
                    outIdx = codec.dequeueOutputBuffer(info, 0);
                }
            }

            codec.stop();
            codec.release();

            byte[] result = amrBaos.toByteArray();
            LogWriter.log(TAG, "AMR MediaCodec: " + result.length + " bytes");
            return result;

        } catch (Throwable e) {
            LogWriter.log(TAG, "MediaCodec fail: " + e.getMessage() + ", try AmrInputStream");
            try {
                Class<?> amrClass = Class.forName("android.media.AmrInputStream");
                Constructor<?> ctor = amrClass.getDeclaredConstructor(InputStream.class);
                ctor.setAccessible(true);
                InputStream amrStream = (InputStream) ctor.newInstance(new ByteArrayInputStream(pcm8000));

                ByteArrayOutputStream amrBaos = new ByteArrayOutputStream();
                byte[] buf = new byte[512];
                int len;
                while ((len = amrStream.read(buf)) > 0) {
                    amrBaos.write(buf, 0, len);
                }
                amrStream.close();
                byte[] result = amrBaos.toByteArray();
                LogWriter.log(TAG, "AMR AmrInputStream: " + result.length + " bytes");
                return result;
            } catch (Throwable e2) {
                LogWriter.log(TAG, "AmrInputStream fail: " + e2.getMessage() + ", raw PCM fallback");
                return pcm8000;
            }
        }
    }
}
