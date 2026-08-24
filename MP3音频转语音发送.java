/*
 * ================================================================
 * Mp3ToSilkVoice — 标准 Xposed 模块 (不依赖 LSPilot)
 * ================================================================
 * 
 * 工程文件列表:
 *   app/build.gradle          → 见下方 "=== FILE: build.gradle ==="
 *   app/src/main/AndroidManifest.xml → 见下方
 *   app/src/main/assets/xposed_init  → com.example.mp3tosilk.MainHook
 *   app/src/main/java/com/example/mp3tosilk/MainHook.java → 本文件
 *
 * 编译: Android Studio 新建 Empty Activity 项目，
 *       替换 build.gradle / Manifest / 本文件即可。
 * 依赖: compileOnly 'de.robv.android.xposed:api:82'
 * 兼容: LSPosed / EdXposed / 原版 Xposed
 *
 * 使用方法:
 *   1. 打开微信聊天窗口
 *   2. 输入框输入 "#voice /sdcard/music.mp3"
 *   3. 发送，自动转码为 SILK 语音消息
 *
 * 逆向链路:
 *   MP3 → MediaCodec → PCM(16kHz/mono) 
 *     → MediaRecorder.SilkEncInit/DoEnc (wechatvoicesilk.so)
 *     → SILK字节流 → w6.K()写VFS → y21.x0.t()注册发送
 * ================================================================
 */

package com.example.mp3tosilk;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    // ===== 微信类/方法反射缓存 =====
    private Class<?> clsMediaRecorder;
    private Class<?> clsX0;
    private Class<?> clsW6;
    private Class<?> clsT8;
    private Class<?> clsW0;
    private Class<?> clsR;
    private ClassLoader wechatCL;

    private String currentTalker = "";
    private Handler mainHandler;

    // ===== SILK 编码参数 =====
    private static final int SAMPLE_RATE = 16000;
    private static final int BIT_RATE   = 16000;
    private static final int FRAME_MS   = 20;
    private static final int FRAME_BYTES = (SAMPLE_RATE * FRAME_MS / 1000) * 2;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!"com.tencent.mm".equals(lpp.packageName)) return;

        wechatCL = lpp.classLoader;
        mainHandler = new Handler(Looper.getMainLooper());

        initWechatClasses();
        hookChattingUI();
        hookSendMessage();

        XposedBridge.log("[Mp3ToSilk] ✅ 模块就绪！用法: 聊天框输入 #voice /path/file.mp3");
    }

    // ============================================================
    // 初始化：反射加载微信内部类
    // ============================================================

    private void initWechatClasses() {
        try {
            clsMediaRecorder = XposedHelpers.findClass(
                "com.tencent.mm.modelvoice.MediaRecorder", wechatCL);
            clsX0 = XposedHelpers.findClass("y21.x0", wechatCL);
            clsW6 = XposedHelpers.findClass("com.tencent.mm.vfs.w6", wechatCL);
            clsT8 = XposedHelpers.findClass(
                "com.tencent.mm.sdk.platformtools.t8", wechatCL);
            clsW0 = XposedHelpers.findClass("tl.w0", wechatCL);
            clsR  = XposedHelpers.findClass("wo.r", wechatCL);
        } catch (Exception e) {
            XposedBridge.log("[Mp3ToSilk] init 失败: " + e.getMessage());
        }
    }

    // ============================================================
    // Hook 1: 聊天界面 → 获取当前 talker
    // ============================================================

    private void hookChattingUI() {
        try {
            Class<?> c = XposedHelpers.findClass(
                "com.tencent.mm.ui.chatting.ChattingUI", wechatCL);
            XposedHelpers.findAndHookMethod(c, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object o = p.thisObject;
                        String t = (String) XposedHelpers.getObjectField(o, "talker");
                        if (t == null)
                            t = (String) XposedHelpers.getObjectField(o, "mTalker");
                        if (t == null)
                            t = (String) XposedHelpers.getObjectField(o, "nPf");
                        if (t != null && !(Boolean) XposedHelpers.callStaticMethod(
                                clsT8, "K0", t)) {
                            currentTalker = t;
                        }
                    } catch (Exception ignored) {}
                }
            });
        } catch (Exception e) {
            XposedBridge.log("[Mp3ToSilk] Hook UI 失败: " + e.getMessage());
        }
    }

    // ============================================================
    // Hook 2: 消息发送 → 拦截 #voice 命令
    // ============================================================

    private void hookSendMessage() {
        try {
            Class<?> sendClz = null;
            for (String cn : new String[]{
                "com.tencent.mm.plugin.messenger.foundation.a2",
                "com.tencent.mm.modelbase.p",
                "jg0.x"
            }) {
                try { sendClz = XposedHelpers.findClass(cn, wechatCL); break; }
                catch (Exception ignored) {}
            }
            if (sendClz == null) {
                XposedBridge.log("[Mp3ToSilk] ⚠ 未找到发送类");
                return;
            }

            for (Method m : sendClz.getDeclaredMethods()) {
                if (!"a".equals(m.getName())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        Object[] args = p.args;
                        for (int i = 0; i < args.length; i++) {
                            if (!(args[i] instanceof String)) continue;
                            String txt = (String) args[i];
                            if (txt == null || !txt.startsWith("#voice ")) continue;

                            String mp3 = txt.substring(7).trim();
                            args[i] = "";

                            if (currentTalker.isEmpty()) {
                                showToast("请先打开聊天窗口！");
                                return;
                            }
                            String talker = currentTalker;
                            new Thread(() -> mp3ToWechatVoice(mp3, talker)).start();
                            showToast("正在转码 MP3 → 语音...");
                            return;
                        }
                    }
                });
            }
            XposedBridge.log("[Mp3ToSilk] Hook 发送: " + sendClz.getName());
        } catch (Exception e) {
            XposedBridge.log("[Mp3ToSilk] Hook 发送失败: " + e.getMessage());
        }
    }

    // ============================================================
    // 主流程: MP3 → PCM → SILK → 发送
    // ============================================================

    private void mp3ToWechatVoice(String mp3Path, String talker) {
        try {
            XposedBridge.log("[Mp3ToSilk] === " + mp3Path + " → " + talker);

            byte[] pcm = decodeMp3(mp3Path);
            if (pcm == null) { showToast("MP3 解码失败！"); return; }

            int durMs = pcm.length / (SAMPLE_RATE * 2) * 1000;
            XposedBridge.log("[Mp3ToSilk] PCM: " + pcm.length + "B ≈" + durMs + "ms");

            byte[] silk = encodeSilk(pcm);
            if (silk == null) { showToast("SILK 编码失败！"); return; }
            XposedBridge.log("[Mp3ToSilk] SILK: " + silk.length + "B");

            boolean ok = injectAndSend(talker, silk, durMs);
            showToast(ok ? "🎤 语音已发送 (" + durMs + "ms)" : "❌ 发送失败");

        } catch (Exception e) {
            XposedBridge.log("[Mp3ToSilk] 异常: " + e.getMessage());
            showToast("异常: " + e.getMessage());
        }
    }

    // ============================================================
    // Step 1: MP3 → 16kHz/mono/16bit PCM
    // ============================================================

    private byte[] decodeMp3(String path) {
        MediaExtractor ex = null;
        MediaCodec codec = null;
        try {
            ex = new MediaExtractor();
            ex.setDataSource(path);

            int track = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) { track = i; break; }
            }
            if (track < 0) return null;

            ex.selectTrack(track);
            MediaFormat fmt = ex.getTrackFormat(track);

            int srcRate = 44100;
            try { srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); }
            catch (Exception ignored) {}
            int ch = 2;
            try { ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT); }
            catch (Exception ignored) {}
            String mime = fmt.getString(MediaFormat.KEY_MIME);

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();

            List<byte[]> chunks = new ArrayList<>();
            int total = 0;
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            boolean done = false;

            while (!done) {
                int inIdx = codec.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    ByteBuffer ib = codec.getInputBuffer(inIdx);
                    if (ib != null) {
                        int sz = ex.readSampleData(ib, 0);
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            done = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz,
                                ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(bi, 10000);
                while (outIdx >= 0) {
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                        done = true;
                    if (bi.size > 0) {
                        ByteBuffer ob = codec.getOutputBuffer(outIdx);
                        if (ob != null) {
                            byte[] d = new byte[bi.size];
                            ob.position(bi.offset);
                            ob.get(d, 0, bi.size);
                            chunks.add(d);
                            total += bi.size;
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    outIdx = codec.dequeueOutputBuffer(bi, 10000);
                }
            }

            byte[] raw = new byte[total];
            int pos = 0;
            for (byte[] ck : chunks) {
                System.arraycopy(ck, 0, raw, pos, ck.length);
                pos += ck.length;
            }

            if (srcRate != SAMPLE_RATE)
                raw = resample(raw, srcRate, ch, SAMPLE_RATE);

            XposedBridge.log("[Mp3ToSilk] 解码: " + raw.length + "B PCM");
            return raw;

        } catch (Exception e) {
            XposedBridge.log("[Mp3ToSilk] 解码异常: " + e.getMessage());
            return null;
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } }
            catch (Exception ignored) {}
            try { if (ex != null) ex.release(); } catch (Exception ignored) {}
        }
    }

    // ============================================================
    // 辅助: 线性重采样
    // ============================================================

    private byte[] resample(byte[] src, int sr, int ch, int dr) {
        int ss = src.length / (2 * ch);
        int ds = (int) ((long) ss * dr / sr);
        byte[] d = new byte[ds * 2];
        double r = (double) sr / dr;
        for (int i = 0; i < ds; i++) {
            int si = (int) (i * r);
            if (si >= ss) si = ss - 1;
            short s = (short) ((src[si * 2 * ch] & 0xFF)
                             | ((src[si * 2 * ch + 1]) << 8));
            int dp = i * 2;
            d[dp]     = (byte) (s & 0xFF);
            d[dp + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return d;
    }

    // ============================================================
    // Step 2: PCM → SILK (微信 wechatvoicesilk.so)
    // ============================================================

    private byte[] encodeSilk(byte[] pcm) {
        if (pcm == null || pcm.length < FRAME_BYTES) return null;

        try {
            if (!XposedHelpers.getStaticBooleanField(clsW0, "b")) {
                XposedBridge.log("[Mp3ToSilk] wechatvoicesilk.so 未加载！");
                return null;
            }
        } catch (Exception e) {
            XposedBridge.log("[Mp3ToSilk] 检查SILK: " + e.getMessage());
            return null;
        }

        int cpuFlag;
        try {
            cpuFlag = (Integer) XposedHelpers.callStaticMethod(clsR, "a");
        } catch (Exception e) {
            cpuFlag = 1024;
        }
        int cpuArch = ((cpuFlag & 1024) != 0) ? 4 : 2;

        long handle;
        try {
            handle = (Long) XposedHelpers.callStaticMethod(
                clsMediaRecorder, "SilkEncInit",
                SAMPLE_RATE, BIT_RATE, cpuArch, 0L);
        } catch (Exception e) {
            XposedBridge.log("[Mp3ToSilk] SilkEncInit 失败: " + e.getMessage());
            return null;
        }
        if (handle == 0) return null;

        try {
            XposedHelpers.callStaticMethod(
                clsMediaRecorder, "SetVoiceSilkControl", 200, 0, handle);
        } catch (Exception ignored) {}

        try {
            int totalFrames = pcm.length / FRAME_BYTES;
            if (pcm.length % FRAME_BYTES > 0) totalFrames++;

            byte[] ob = new byte[FRAME_BYTES * 6];
            short[] ol = new short[1];
            List<byte[]> frames = new ArrayList<>();
            int totalOut = 0;

            for (int fi = 0; fi < totalFrames; fi++) {
                int off = fi * FRAME_BYTES;
                byte[] fb = new byte[FRAME_BYTES];
                if (fi == totalFrames - 1) {
                    int rem = pcm.length - off;
                    if (rem > 0) System.arraycopy(pcm, off, fb, 0, rem);
                } else {
                    System.arraycopy(pcm, off, fb, 0, FRAME_BYTES);
                }

                boolean isLast = (fi == totalFrames - 1);
                if (isLast) {
                    try {
                        XposedHelpers.callStaticMethod(
                            clsMediaRecorder, "SetVoiceSilkControl", 201, 1, handle);
                    } catch (Exception ignored) {}
                }

                java.util.Arrays.fill(ob, (byte) 0);
                ol[0] = 0;

                int ret = (Integer) XposedHelpers.callStaticMethod(
                    clsMediaRecorder, "SilkDoEnc",
                    fb, (short) FRAME_BYTES, ob, ol, isLast, handle);

                int encLen = ol[0];
                if (encLen <= 0) {
                    if (!isLast)
                        XposedBridge.log("[Mp3ToSilk] 帧" + fi + "空 ret=" + ret);
                    continue;
                }

                byte[] frame = new byte[encLen];
                System.arraycopy(ob, 0, frame, 0, encLen);

                if (fi == 0) {
                    if (encLen > 10 && frame[0] == 0x02
                            && frame[1] == 0x23 && frame[2] == 0x21
                            && frame[3] == 0x53) {
                        byte[] n = new byte[encLen - 1];
                        System.arraycopy(frame, 1, n, 0, encLen - 1);
                        frames.add(n);
                        totalOut += (encLen - 1);
                    } else {
                        frames.add(frame); totalOut += encLen;
                    }
                } else {
                    if (encLen > 10 && frame[0] == 0x02 && frame[1] == 0x23) {
                        byte[] n = new byte[encLen - 10];
                        System.arraycopy(frame, 10, n, 0, encLen - 10);
                        frames.add(n);
                        totalOut += (encLen - 10);
                    } else {
                        frames.add(frame); totalOut += encLen;
                    }
                }
            }

            byte[] result = new byte[totalOut];
            int wp = 0;
            for (byte[] fr : frames) {
                System.arraycopy(fr, 0, result, wp, fr.length);
                wp += fr.length;
            }

            XposedBridge.log("[Mp3ToSilk] 编码: " + pcm.length + "B → " + result.length + "B");
            return result;

        } catch (Exception e) {
            XposedBridge.log("[Mp3ToSilk] 编码异常: " + e.getMessage());
            return null;
        } finally {
            try {
                XposedHelpers.callStaticMethod(
                    clsMediaRecorder, "SilkEncUnInit", handle);
            } catch (Exception ignored) {}
        }
    }

    // ============================================================
    // Step 3: 注入微信语音系统 + 触发发送
    // ============================================================

    private boolean injectAndSend(String talker, byte[] silk, int durMs) {
        try {
            String path = (String) XposedHelpers.callStaticMethod(
                clsX0, "g", talker, "silk_");
            if (path == null || path.isEmpty()) {
                XposedBridge.log("[Mp3ToSilk] g() 返回空");
                return false;
            }

            OutputStream os = (OutputStream) XposedHelpers.callStaticMethod(
                clsW6, "K", path, false);
            if (os == null) return false;
            os.write(silk);
            os.flush();
            os.close();

            boolean ok = (Boolean) XposedHelpers.callStaticMethod(
                clsX0, "t", path, durMs, 0, null);
            XposedBridge.log("[Mp3ToSilk] " + (ok ? "✅ 发送" : "❌ 失败"));
            return ok;

        } catch (Exception e) {
            XposedBridge.log("[Mp3ToSilk] 注入异常: " + e.getMessage());
            return false;
        }
    }

    // ============================================================
    // 辅助: 主线程 Toast
    // ============================================================

    private void showToast(String msg) {
        mainHandler.post(() -> {
            try {
                Object ctx = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(
                        "com.tencent.mm.sdk.platformtools.x2", wechatCL), "a");
                if (ctx instanceof android.content.Context) {
                    Toast.makeText((android.content.Context) ctx,
                        msg, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                XposedBridge.log("[Mp3ToSilk] Toast: " + msg);
            }
        });
    }
}
