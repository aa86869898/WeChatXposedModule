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
    }

    private static void hookChatFooterSend(ClassLoader cl) {
        try {
            Class<?> chatFooter = XposedHelpers.findClass(
                    "com.tencent.mm.pluginsdk.ui.chat.ChatFooter", cl);

            XposedBridge.hookAllMethods(chatFooter, "F", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        XposedBridge.log("[TTSVoiceSender] ChatFooter.F CALLBACK FIRED");
                        if (param.args.length < 1 || param.args[0] == null) return;
                        Object msgInfo = param.args[0];

                        String content = null;
                        try { content = (String) XposedHelpers.getObjectField(msgInfo, "field_content"); }
                        catch (Throwable ignored) {}
                        if (content == null) {
                            XposedBridge.log("[TTSVoiceSender] field_content is null");
                            return;
                        }

                        if (!content.startsWith(TTS_PREFIX)) return;

                        int type;
                        try { type = (Integer) XposedHelpers.callMethod(msgInfo, "getType");
                        } catch (Throwable e) { type = -1; }

                        String text = content.substring(TTS_PREFIX.length()).trim();
                        if (text.isEmpty()) return;

                        String talker = null;
                        try { talker = (String) XposedHelpers.getObjectField(msgInfo, "field_talker"); }
                        catch (Throwable ignored) {}
                        if (talker == null || talker.isEmpty()) return;

                        LogWriter.log(TAG, "ChatFooter.F #tts: type=" + type + " text=" + text.substring(0, Math.min(text.length(), 60)) + " talker=" + talker);

                        param.setResult(false);

                        final String fText = text;
                        final String fTalker = talker;
                        new Thread(() -> synthesizeAndSend(fText, fTalker)).start();

                    } catch (Throwable e) {
                        LogWriter.log(TAG, "intercept err: " + e.getMessage());
                    }
                }
            });
            XposedBridge.log("[TTSVoiceSender] ChatFooter.F hooked OK");
            LogWriter.log(TAG, "ChatFooter.F hooked OK");
        } catch (Throwable t) {
            XposedBridge.log("[TTSVoiceSender] ChatFooter.F FAIL: " + t.getMessage());
            LogWriter.log(TAG, "ChatFooter.F hook fail: " + t.getMessage());
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

            LogWriter.log(TAG, "WAV generated: " + wavFile.length() + " bytes");

            int duration = wavToAmr(wavFile, amrFile);
            wavFile.delete();

            if (duration <= 0 || amrFile.length() < 50) {
                LogWriter.log(TAG, "AMR encode failed, size=" + amrFile.length());
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

            boolean ok = (Boolean) XposedHelpers.callStaticMethod(y21x0, "t",
                    filePath, duration, 0, e9talker);

            return ok;
        } catch (Throwable t) {
            LogWriter.log(TAG, "sendVoice err: " + t.getMessage());
            return false;
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

            LogWriter.log(TAG, "PCM: " + sampleRate + "Hz " + channels + "ch " + bitsPerSample + "bit → mono8kHz " + mono8000Pcm.length + " bytes");

            byte[] amrData = encodeAmr(mono8000Pcm);

            FileOutputStream fos = new FileOutputStream(amrFile);
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
            LogWriter.log(TAG, "AMR encoded via MediaCodec: " + result.length + " bytes");
            return result;

        } catch (Throwable e) {
            LogWriter.log(TAG, "MediaCodec AMR fail: " + e.getMessage() + ", try reflection");
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
                LogWriter.log(TAG, "AMR encoded via AmrInputStream: " + result.length + " bytes");
                return result;
            } catch (Throwable e2) {
                LogWriter.log(TAG, "AmrInputStream fail: " + e2.getMessage() + ", raw PCM fallback");
                return pcm8000;
            }
        }
    }
}
