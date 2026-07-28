package com.leshao.v3.hook;

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
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * TTS 语音发送 v35
 * - ChatFooter.F 无条件裸日志（诊断 F 是否被调用）
 * - e01.x9 全方法扫描（找出真正的发送入口）
 * - 兼容 MessageHook 中已有的 #tts 检测（C(e9) 入库后额外发送语音）
 */
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

        hookChatFooterSend(cl);
        hookMsgLogicScan(cl);
    }

    // ========== ChatFooter.F 诊断 hook ==========

    private static void hookChatFooterSend(ClassLoader cl) {
        try {
            Class<?> chatFooter = XposedHelpers.findClass(
                    "com.tencent.mm.pluginsdk.ui.chat.ChatFooter", cl);

            XposedBridge.hookAllMethods(chatFooter, "F", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    XposedBridge.log("[TTS] ChatFooter.F CALLED");
                    try {
                        if (param.args.length >= 1 && param.args[0] != null) {
                            Object msgInfo = param.args[0];
                            String content = null;
                            try { content = (String) XposedHelpers.getObjectField(msgInfo, "field_content"); }
                            catch (Throwable ignored) {}
                            if (content == null) try { content = (String) XposedHelpers.callMethod(msgInfo, "I0"); }
                            catch (Throwable ignored) {}
                            if (content == null) try { content = (String) XposedHelpers.callMethod(msgInfo, "j"); }
                            catch (Throwable ignored) {}

                            XposedBridge.log("[TTS] ChatFooter.F content=[" + (content != null ? content.substring(0, Math.min(content.length(), 40)) : "null") + "]");

                            if (content != null && content.startsWith(TTS_PREFIX)) {
                                String talker = null;
                                try { talker = (String) XposedHelpers.getObjectField(msgInfo, "field_talker"); }
                                catch (Throwable ignored) {}
                                if (talker == null) try { talker = (String) XposedHelpers.callMethod(msgInfo, "N0"); }
                                catch (Throwable ignored) {}

                                if (talker == null || talker.isEmpty()) {
                                    XposedBridge.log("[TTS] ChatFooter.F #tts but talker is null");
                                    return;
                                }

                                String text = content.substring(TTS_PREFIX.length()).trim();
                                if (text.isEmpty()) return;

                                XposedBridge.log("[TTS] ChatFooter.F #tts DETECTED: text=" + text.substring(0, Math.min(text.length(), 40)) + " talker=" + talker);
                                LogWriter.log(TAG, "F #tts: " + text.substring(0, Math.min(text.length(), 60)) + " talker=" + talker);

                                param.setResult(false);

                                final String fText = text;
                                final String fTalker = talker;
                                new Thread(() -> synthesizeAndSend(fText, fTalker)).start();
                            }
                        }
                    } catch (Throwable e) {
                        XposedBridge.log("[TTS] ChatFooter.F err: " + e.getMessage());
                        LogWriter.log(TAG, "F err: " + e.getMessage());
                    }
                }
            });
            XposedBridge.log("[TTS] ChatFooter.F hooked OK");
            LogWriter.log(TAG, "ChatFooter.F hooked OK");
        } catch (Throwable t) {
            XposedBridge.log("[TTS] ChatFooter.F FAIL: " + t.getMessage());
            LogWriter.log(TAG, "ChatFooter.F fail: " + t.getMessage());
        }
    }

    // ========== e01.x9 全方法扫描 (诊断发送入口) ==========

    private static void hookMsgLogicScan(ClassLoader cl) {
        try {
            Class<?> x9Cls = null;
            for (String name : new String[]{"e01.x9", "e02.x9", "e00.x9", "e01.x8", "e01.y9"}) {
                try { x9Cls = cl.loadClass(name); break; } catch (Throwable ignored) {}
            }
            if (x9Cls == null) {
                XposedBridge.log("[TTS] scan: x9 class not found");
                return;
            }
            Class<?> e9Cls = VersionCompat.findMsgInfoStorageClass(cl);
            if (e9Cls == null) {
                XposedBridge.log("[TTS] scan: e9 class not found");
                return;
            }

            int hooked = 0;
            for (java.lang.reflect.Method m : x9Cls.getDeclaredMethods()) {
                // 只 hook 参数中含 e9 的方法
                boolean hasE9 = false;
                for (Class<?> pt : m.getParameterTypes()) {
                    if (pt == e9Cls) { hasE9 = true; break; }
                }
                if (!hasE9) continue;

                String mName = m.getName();
                if (mName.equals("n") || mName.equals("C")) continue; // MessageHook 已 hook

                final String fName = mName;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        StringBuilder sb = new StringBuilder("[TTS] e01.x9." + fName + "(");
                        for (int i = 0; i < param.args.length; i++) {
                            if (i > 0) sb.append(",");
                            sb.append(param.args[i] == null ? "null" : param.args[i].getClass().getSimpleName());
                        }
                        sb.append(")");
                        XposedBridge.log(sb.toString());

                        // 检测 #tts
                        for (Object arg : param.args) {
                            if (arg == null || arg.getClass() != e9Cls) continue;
                            try {
                                String content = null;
                                try { content = (String) XposedHelpers.callMethod(arg, "I0"); }
                                catch (Throwable ignored) {}
                                if (content == null) try { content = (String) XposedHelpers.callMethod(arg, "j"); }
                                catch (Throwable ignored) {}
                                if (content != null && content.startsWith(TTS_PREFIX)) {
                                    XposedBridge.log("[TTS] e01.x9." + fName + " #tts content=[" + content + "]");
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                });
                hooked++;
            }
            XposedBridge.log("[TTS] e01.x9 scan: hooked " + hooked + " methods with e9 param");
            LogWriter.log(TAG, "e01.x9 scan: " + hooked + " methods hooked");
        } catch (Throwable t) {
            XposedBridge.log("[TTS] scan err: " + t.getMessage());
            LogWriter.log(TAG, "e01.x9 scan fail: " + t.getMessage());
        }
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
                wavFile.delete();
                return;
            }

            boolean finished = latch.await(30, TimeUnit.SECONDS);
            if (!finished) {
                LogWriter.log(TAG, "synth timeout");
                wavFile.delete();
                return;
            }
            if (synthResult[0] != TextToSpeech.SUCCESS) {
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

            amrFile.delete();

        } catch (Throwable e) {
            LogWriter.log(TAG, "synthAndSend err: " + e.getMessage());
        }
    }

    private static boolean sendVoice(String filePath, int duration, String talker) {
        try {
            Class<?> y21x0 = XposedHelpers.findClass("y21.x0", sClassLoader);

            Object e9talker = XposedHelpers.newInstance(
                    XposedHelpers.findClass("com.tencent.mm.storage.e9", sClassLoader),
                    talker);

            return (Boolean) XposedHelpers.callStaticMethod(y21x0, "t",
                    filePath, duration, 0, e9talker);
        } catch (Throwable t) {
            LogWriter.log(TAG, "sendVoice err: " + t.getMessage());
            return false;
        }
    }

    // ========== WAV → AMR ==========

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
