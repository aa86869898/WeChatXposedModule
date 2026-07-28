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
    private static Object sMsgStorage;

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

        initMsgStorage(cl);
        hookF9Insert(cl);
    }

    private static void initMsgStorage(ClassLoader cl) {
        try {
            Class<?> e01d9 = XposedHelpers.findClass("e01.d9", cl);
            Object service = XposedHelpers.callStaticMethod(e01d9, "b");
            if (service != null) {
                sMsgStorage = XposedHelpers.callMethod(service, "u");
                LogWriter.log(TAG, "f9 storage: OK");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "f9 storage fail: " + t.getMessage());
        }
    }

    private static void hookF9Insert(ClassLoader cl) {
        try {
            Class<?> f9Cls = null;
            for (String name : new String[]{"com.tencent.mm.storage.f9",
                    "com.tencent.mm.storage.g9"}) {
                try { f9Cls = XposedHelpers.findClass(name, cl); break; }
                catch (Throwable ignored) {}
            }
            if (f9Cls == null) {
                LogWriter.log(TAG, "f9 class not found");
                return;
            }

            XposedBridge.hookAllMethods(f9Cls, "I9", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    interceptInsert(param);
                }
            });
            XposedBridge.hookAllMethods(f9Cls, "H9", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    interceptInsert(param);
                }
            });
            XposedBridge.hookAllMethods(f9Cls, "O8", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    interceptInsert(param);
                }
            });
            XposedBridge.hookAllMethods(f9Cls, "X9", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    interceptInsert(param);
                }
            });
            LogWriter.log(TAG, "f9 I9/H9/O8/X9 hooked OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "f9 hook fail: " + t.getMessage());
        }
    }

    private static void interceptInsert(XC_MethodHook.MethodHookParam param) {
        try {
            if (param.args.length == 0) return;
            Object msgInfo = param.args[0];
            if (msgInfo == null) return;

            int type;
            try { type = (Integer) XposedHelpers.callMethod(msgInfo, "getType"); }
            catch (Throwable e) { return; }
            if (type != 1) return;

            try {
                boolean isSend = (Boolean) XposedHelpers.callMethod(msgInfo, "G1");
                if (!isSend) return;
            } catch (Throwable e) { return; }

            String content = null;
            try { content = (String) XposedHelpers.getObjectField(msgInfo, "field_content"); }
            catch (Throwable ignored) {}
            if (content == null) try { content = (String) XposedHelpers.callMethod(msgInfo, "I0"); }
            catch (Throwable ignored) {}
            if (content == null) return;

            if (!content.startsWith(TTS_PREFIX)) return;

            String text = content.substring(TTS_PREFIX.length()).trim();
            if (text.isEmpty()) return;

            String talker = null;
            try { talker = (String) XposedHelpers.getObjectField(msgInfo, "field_talker"); }
            catch (Throwable ignored) {}
            if (talker == null) try { talker = (String) XposedHelpers.callMethod(msgInfo, "N0"); }
            catch (Throwable ignored) {}
            if (talker == null || talker.isEmpty()) return;

            LogWriter.log(TAG, "f9 insert #tts: text=" + text.substring(0, Math.min(text.length(), 40)) + " talker=" + talker);

            param.setResult(null);

            final String fText = text;
            final String fTalker = talker;
            new Thread(() -> synthesizeAndSend(fText, fTalker)).start();

        } catch (Throwable e) {
            LogWriter.log(TAG, "interceptInsert err: " + e.getMessage());
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

            boolean sent = sendVoice(amrFile.getAbsolutePath(), duration, talker);
            LogWriter.log(TAG, "send result: " + sent);

            wavFile.delete();
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
