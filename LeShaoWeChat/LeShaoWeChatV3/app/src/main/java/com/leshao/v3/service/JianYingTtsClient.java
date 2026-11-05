package com.leshao.v3.service;

import android.util.Base64;

import com.leshao.v3.LogWriter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;

/**
 * 剪映「文本朗读」SAMI TTS 客户端（免本机 Python 网关）。
 *
 * <p>全链路复刻 jianying_tts.py：RSA 签名 → frontier WS(aid=1775) → create 帧 →
 * 流式接收 audio_base64 PCM（24000Hz/16bit/mono）。
 * 依赖 {@link WebSocketClient}（RFC6455 极简客户端），无需第三方库。</p>
 */
public final class JianYingTtsClient {

    private static final String TAG = "JianYingTts";

    private static final String DEVICE_ID = "815190091506192";
    private static final String IID = "1395747926360928";
    private static final String APPID = "1775";
    private static final String VERSION_CODE = "217001600";
    private static final String ACCESS_KEY = "b3991059dc0a14c45be8b5b10c973ce9";
    private static final int SAMPLE_RATE = 24000;

    // ===== access_key 动态获取（与 jianying_tts.py get_access_key() 一比一）=====
    // 剪映每次启动会刷新 access_key，硬编码兜底值可能失效导致服务端不下发音频；
    // 优先从剪映 LSPilot NetLogger 日志中读取最新的 aid=1775 access_key，找不到才用兜底。
    private static volatile String sDynamicAccessKey;
    private static volatile long sAccessKeyFetchedAt;
    private static final long ACCESS_KEY_CACHE_MS = 5 * 60 * 1000L;
    private static final String NETLOGGER_LOG_DIR =
            "/sdcard/Android/media/com.lemon.lv/LSPilot/Plugin/NetLogger/log";

    private static String getAccessKey() {
        String cached = sDynamicAccessKey;
        if (cached != null && !cached.isEmpty()
                && System.currentTimeMillis() - sAccessKeyFetchedAt < ACCESS_KEY_CACHE_MS) {
            return cached;
        }
        try {
            File dir = new File(NETLOGGER_LOG_DIR);
            File[] logs = dir.listFiles();
            if (logs != null && logs.length > 0) {
                Arrays.sort(logs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (File log : logs) {
                    String ak = parseAccessKeyFromLog(log);
                    if (ak != null && !ak.isEmpty()) {
                        sDynamicAccessKey = ak;
                        sAccessKeyFetchedAt = System.currentTimeMillis();
                        return ak;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return ACCESS_KEY;
    }

    private static String parseAccessKeyFromLog(File log) {
        try {
            long size = log.length();
            if (size <= 0) return null;
            long start = Math.max(0, size - 4 * 1024 * 1024);
            RandomAccessFile raf = new RandomAccessFile(log, "r");
            try {
                raf.seek(start);
                byte[] buf = new byte[(int) (size - start)];
                raf.readFully(buf);
                String tail = new String(buf, "UTF-8");
                Pattern p = Pattern.compile("wss://frontier\\.snssdk\\.com/ws/v2\\?[^\\s\"')]+");
                Matcher m = p.matcher(tail);
                String lastUrl = null;
                while (m.find()) lastUrl = m.group();
                if (lastUrl == null) return null;
                int q = lastUrl.indexOf('?');
                if (q < 0) return null;
                String query = lastUrl.substring(q + 1);
                boolean aidOk = false;
                String ak = null;
                for (String pair : query.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq <= 0) continue;
                    String k = pair.substring(0, eq);
                    String v = pair.substring(eq + 1);
                    if ("aid".equals(k) && APPID.equals(v)) aidOk = true;
                    if ("access_key".equals(k) && !v.isEmpty()) ak = v;
                }
                return aidOk ? ak : null;
            } finally {
                try { raf.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final String PUBKEY_PEM =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAmTd34Lw4b7IuldSXh/zY"
                    + "CMla+ITdGG5TeWz6ad+OySd4r+IrY45AoqrYUxhQ2dl+7z+i7r/5vEa8rr39BYfB"
                    + "8AGMQLmZA8HmgpWBsqrn/V6daUALkKnkLb70Fn32CJigIuGXAYqxUdGuI340aC+0"
                    + "v5Es3puJsHyzf01/AelE4Cdc6bZhQrASJLBh8R3BQToYClmDVSDUQk28o8sl/guA"
                    + "Z4n303Vj+6Siv1HayPCdV6kpVVnMBAG4+umUbwGmn132N3fgpzLarFF3XyWmS1zh"
                    + "D/J07iM/rP8GDO9IskHNHd2phrO0G6KzrcFAnTBHjVv+hCBEfzN/no3FNA9AuC36"
                    + "mwIDAQAB";

    private static final String BABI_PARAM =
            "{\"edit_type\":\"edit\",\"enter_from\":\"edit\",\"feature_entrance\":\"editor\","
                    + "\"feature_entrance_detail\":\"editor-editpage-text-text_to_audio\","
                    + "\"feature_key\":\"text_to_speech_text_to_audio\",\"scenario\":\"video_editor\","
                    + "\"tab_name\":\"edit\",\"tool_id\":\"text_to_audio\"}";

    private JianYingTtsClient() {
    }

    /** 剪映音色项（从 assets jianying_voices.json 加载）。 */
    public static final class JianYingVoice {
        public final String title;
        public final String effectId;
        public final String voiceType;
        public final boolean vip;
        public final String[] categories;

        public JianYingVoice(String title, String effectId, String voiceType,
                             boolean vip, String[] categories) {
            this.title = title;
            this.effectId = effectId;
            this.voiceType = voiceType;
            this.vip = vip;
            this.categories = categories;
        }
    }

    private static volatile java.util.List<JianYingVoice> sVoices;

    /** 从 assets 加载 797 个剪映音色（懒加载 + 缓存）。 */
    public static java.util.List<JianYingVoice> voices() {
        if (sVoices != null) return sVoices;
        try {
            // Xposed 环境下 ContextManager 拿到的是宿主(微信) Context，读不到模块 APK 的 assets，
            // 必须用模块自己的 ClassLoader 读取 assets 资源。
            java.io.InputStream is = null;
            try {
                is = JianYingTtsClient.class.getClassLoader()
                        .getResourceAsStream("assets/jianying_voices.json");
            } catch (Throwable ignored) {
            }
            if (is == null) {
                android.content.Context ctx = com.leshao.v3.ContextManager.getAppContext();
                if (ctx != null) {
                    try {
                        is = ctx.getAssets().open("jianying_voices.json");
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (is == null) {
                LogWriter.log(TAG, "voices stream null (assets not found)");
                sVoices = new java.util.ArrayList<>();
                return sVoices;
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            org.json.JSONArray arr = new org.json.JSONArray(
                    bos.toString("UTF-8"));
            java.util.List<JianYingVoice> list = new java.util.ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                String title = o.optString("title");
                String effectId = o.optString("effect_id");
                String voiceType = o.optString("voice_type");
                boolean vip = o.optBoolean("is_vip", false);
                org.json.JSONArray cats = o.optJSONArray("categories");
                String[] catArr = new String[cats == null ? 0 : cats.length()];
                if (cats != null) {
                    for (int j = 0; j < cats.length(); j++) catArr[j] = cats.optString(j);
                }
                list.add(new JianYingVoice(title, effectId, voiceType, vip, catArr));
            }
            sVoices = list;
            LogWriter.log(TAG, "voices loaded: " + list.size());
        } catch (Throwable t) {
            LogWriter.log(TAG, "voices load err: " + t);
            sVoices = new java.util.ArrayList<>();
        }
        return sVoices;
    }

    /** 按标题/voice_type 查找音色，返回 {voice_type, effect_id, title}。 */
    public static String[] resolveVoice(String voice) {
        if (voice == null || voice.isEmpty()) return null;
        for (JianYingVoice v : voices()) {
            if (voice.equals(v.voiceType) || voice.equals(v.title)) {
                return new String[]{v.voiceType, v.effectId, v.title};
            }
        }
        String q = voice.trim().toLowerCase();
        for (JianYingVoice v : voices()) {
            if (v.title != null && v.title.toLowerCase().contains(q)) {
                return new String[]{v.voiceType, v.effectId, v.title};
            }
            if (v.voiceType != null && v.voiceType.toLowerCase().contains(q)) {
                return new String[]{v.voiceType, v.effectId, v.title};
            }
        }
        return null;
    }

    // ==================== RSA 签名 ====================

    private static volatile PublicKey sPubKey;

    private static PublicKey pubKey() throws Exception {
        if (sPubKey == null) {
            byte[] keyBytes = Base64.decode(PUBKEY_PEM, Base64.DEFAULT);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            sPubKey = KeyFactory.getInstance("RSA").generatePublic(spec);
        }
        return sPubKey;
    }

    private static String md5Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static String makeSign(String text, String voiceType) throws Exception {
        String textMd5 = md5Hex(text);
        String plain = "appid:1775&did:" + DEVICE_ID + "&platform:sami&speaker_id:"
                + voiceType + "&text:" + textMd5;
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, pubKey());
        byte[] enc = cipher.doFinal(plain.getBytes("UTF-8"));
        return Base64.encodeToString(enc, Base64.NO_WRAP);
    }

    // ==================== protobuf 帧 ====================

    private static byte[] varint(long n) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            int b = (int) (n & 0x7f);
            n >>= 7;
            if (n != 0) {
                out.write(b | 0x80);
            } else {
                out.write(b);
                return out.toByteArray();
            }
        }
    }

    private static byte[] field(int fieldNo, byte[] payload) {
        byte[] tag = varint((fieldNo << 3) | 2);
        byte[] len = varint(payload.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag, 0, tag.length);
        out.write(len, 0, len.length);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    private static byte[] fieldVarint(int fieldNo, long value) {
        byte[] v = varint(value);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(varint((fieldNo << 3) | 0), 0, 1);
        out.write(v, 0, v.length);
        return out.toByteArray();
    }

    private static byte[] buildCreateFrame(byte[] ttsJson, int seq) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] f1 = fieldVarint(1, seq);
        byte[] f2 = fieldVarint(2, 1);
        byte[] f3 = fieldVarint(3, 117440527L);
        byte[] f4 = fieldVarint(4, 101);
        byte[] f5a = field(5, new byte[]{0x0a, 0x06, 's', 'e', 'q', '_', 'i', 'd', 0x12, 0x01, '0'});
        byte[] f5b = field(5, new byte[]{0x0a, 0x03, 'c', 'm', 'd', 0x12, 0x01, '1'});
        byte[] f6 = field(6, new byte[0]);
        byte[] f7 = field(7, "application/json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] f8 = field(8, ttsJson);
        out.write(f1, 0, f1.length);
        out.write(f2, 0, f2.length);
        out.write(f3, 0, f3.length);
        out.write(f4, 0, f4.length);
        out.write(f5a, 0, f5a.length);
        out.write(f5b, 0, f5b.length);
        out.write(f6, 0, f6.length);
        out.write(f7, 0, f7.length);
        out.write(f8, 0, f8.length);
        return out.toByteArray();
    }

    /** 解析下行帧，返回 f8 JSON；非 JSON 帧返回 null。 */
    private static org.json.JSONObject parseDownFrame(byte[] raw) {
        try {
            int pos = 0;
            while (pos < raw.length) {
                long tag = readVarint(raw, pos);
                pos += varintLen(raw, pos);
                int fieldNo = (int) (tag >> 3);
                int wire = (int) (tag & 7);
                if (wire == 2) {
                    long ln = readVarint(raw, pos);
                    pos += varintLen(raw, pos);
                    int len = (int) ln;
                    byte[] payload = new byte[len];
                    System.arraycopy(raw, pos, payload, 0, len);
                    pos += len;
                    if (fieldNo == 8) {
                        return new org.json.JSONObject(new String(payload, "UTF-8"));
                    }
                } else if (wire == 0) {
                    readVarint(raw, pos);
                    pos += varintLen(raw, pos);
                } else if (wire == 1) {
                    pos += 8;
                } else if (wire == 5) {
                    pos += 4;
                } else {
                    break;
                }
            }
        } catch (Throwable t) {
            // 与 Python 网关一致：空/非 JSON 帧（如首帧元数据）静默跳过，不视为错误
            return null;
        }
        return null;
    }

    private static long readVarint(byte[] data, int pos) {
        long r = 0;
        int s = 0;
        while (pos < data.length) {
            int b = data[pos];
            r |= (long) (b & 0x7f) << s;
            if ((b & 0x80) == 0) break;
            s += 7;
            pos++;
        }
        return r;
    }

    private static int varintLen(byte[] data, int pos) {
        int n = 0;
        while (pos < data.length) {
            n++;
            if ((data[pos] & 0x80) == 0) break;
            pos++;
        }
        return n;
    }

    // ==================== 合成 ====================

    /**
     * 合成文本，返回 PCM（24000Hz/16bit/mono）。
     *
     * @param voice 音色标题或 voice_type
     * @param timeoutSec 超时秒数
     */
    public static byte[] synthesize(String text, String voice, int timeoutSec) {
        if (text == null || text.isEmpty()) return null;
        try {
            String[] resolved = resolveVoice(voice);
            if (resolved == null) {
                LogWriter.log(TAG, "voice not found: " + voice);
                return null;
            }
            return synthesize(text, resolved[0], resolved[1], timeoutSec);
        } catch (Throwable t) {
            LogWriter.log(TAG, "synthesize err: " + t);
            return null;
        }
    }

    /** 直接按 voice_type + effect_id 合成。 */
    public static byte[] synthesize(final String text, final String voiceType,
                                     final String resourceId, final int timeoutSec) {
        if (text == null || text.isEmpty() || voiceType == null || voiceType.isEmpty()) {
            return null;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<byte[]> result = new AtomicReference<>();
        final AtomicReference<String> error = new AtomicReference<>();
        final java.util.concurrent.atomic.AtomicLong lastAudioTs = new java.util.concurrent.atomic.AtomicLong(0);
        try {
            final String sign = makeSign(text, voiceType);
            final String url = buildWsUrl();
            final byte[] createFrame = buildCreateFrame(buildTtsJson(text, voiceType, resourceId, sign), 8);

            LogWriter.log(TAG, "connect " + voiceType + " textLen=" + text.length());
            final WebSocketClient[] wsRef = new WebSocketClient[1];
            WebSocketClient client = new WebSocketClient("frontier.snssdk.com", 443, url,
                    null, null, null, new WebSocketClient.Listener() {
                private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
                private boolean gotAudio = false;

                @Override
                public void onMessage(byte[] payload, boolean binary) {
                    try {
                        org.json.JSONObject obj = parseDownFrame(payload);
                        if (obj == null) return;
                        String b64 = obj.optString("audio_base64", "");
                        if (!b64.isEmpty()) {
                            byte[] audio = Base64.decode(b64, Base64.DEFAULT);
                            pcm.write(audio, 0, audio.length);
                            gotAudio = true;
                            lastAudioTs.set(System.currentTimeMillis());
                            // 与 Python 网关一致：每次收到音频都更新结果，
                            // 服务端不发结束帧时超时也能返回已收集的 PCM
                            result.set(pcm.toByteArray());
                        } else if (gotAudio) {
                            // 与 Python 网关完全一致：仅 speech_url 作为末尾结束帧标志
                            if (obj.has("speech_url")) {
                                result.set(pcm.toByteArray());
                                latch.countDown();
                            }
                        }
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "onMessage err: " + t);
                    }
                }

                @Override
                public void onClose(int code, String reason) {
                    if (result.get() == null && gotAudio) {
                        result.set(pcm.toByteArray());
                    }
                    if (result.get() == null) {
                        error.set("ws closed code=" + code + " " + reason);
                    }
                    latch.countDown();
                }

                @Override
                public void onError(Throwable t) {
                    if (result.get() == null && gotAudio) {
                        result.set(pcm.toByteArray());
                    }
                    if (result.get() == null) {
                        error.set(String.valueOf(t));
                    }
                    latch.countDown();
                }
            });
            wsRef[0] = client;
            client.sendBinary(createFrame);

            boolean done = latch.await(timeoutSec, TimeUnit.SECONDS);
            // ★ 帧间静默兜底：服务端不发 speech_url 结束帧时，音频收完后 5 秒无新帧即判定结束，
            //   避免白等 timeoutSec（最长 90s）导致竹节跳动延迟/卡死。
            if (!done && result.get() != null) {
                long deadline = System.currentTimeMillis() + 5_000L;
                while (System.currentTimeMillis() < deadline && latch.getCount() > 0) {
                    if (System.currentTimeMillis() - lastAudioTs.get() > 5_000L) {
                        break;
                    }
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                }
            }
            try {
                wsRef[0].close();
            } catch (Throwable ignored) {
            }
            if (!done && result.get() == null) {
                LogWriter.log(TAG, "synthesize timeout " + voiceType);
                return null;
            }
            if (error.get() != null && result.get() == null) {
                LogWriter.log(TAG, "synthesize fail " + voiceType + " err=" + error.get());
                return null;
            }
            byte[] pcm = result.get();
            LogWriter.log(TAG, "synthesize OK " + voiceType + " pcm=" + (pcm == null ? 0 : pcm.length));
            return pcm;
        } catch (Throwable t) {
            LogWriter.log(TAG, "synthesize crash " + voiceType + ": " + t);
            return null;
        }
    }

    private static String buildWsUrl() {
        final String ak = getAccessKey();
        return "/ws/v2?aid=" + APPID + "&device_id=" + DEVICE_ID
                + "&access_key=" + ak + "&fpid=89&sdk_version=3&iid=" + IID
                + "&pl=0&ne=0&version_code=" + VERSION_CODE + "&is_background=0"
                + "&x_update_version=" + VERSION_CODE + "&x_fsdk_v=1.1.1"
                + "&host_aid=" + APPID
                + "&access_key=" + ak + "&device_platform=android&ne=0"
                + "&qos_level=2&qos_sdk_version=2";
    }
    private static byte[] buildTtsJson(String text, String voiceType, String resourceId,
                                       String sign) {
        try {
          org.json.JSONObject speaker = new org.json.JSONObject();
          speaker.put("emotion", "");
          speaker.put("emotion_scale", 0.0);
          speaker.put("is_clone_tone", false);
          speaker.put("moyin_emotion", "");
          speaker.put("resource_id", resourceId);
          speaker.put("role", "");
          speaker.put("sample_rate", SAMPLE_RATE);
          speaker.put("speaker_id", voiceType);
          speaker.put("speech_rate", 1);
          speaker.put("style", "");

          org.json.JSONObject payload = new org.json.JSONObject();
          payload.put("audio_format", "pcm");
          payload.put("merge_disable", true);
          payload.put("need_subtitle_timestamp", false);
          payload.put("platform", "sami");
          payload.put("sign", sign);
          payload.put("speaker_info", speaker);
          payload.put("text", text);

          org.json.JSONObject roi = new org.json.JSONObject();
          roi.put("generate_id", java.util.UUID.randomUUID().toString());

org.json.JSONObject req = new org.json.JSONObject();
            // ★ 上行样例中 babi_param 是 JSON 字符串（不是嵌套对象），必须原样放入字符串
            req.put("babi_param", BABI_PARAM);
          req.put("matrix_task_id", "");
          req.put("payload", payload);
          req.put("req_key", "sami_stream_tts");
          req.put("roi_meta", roi);
          req.put("status", "create");
          req.put("task_id", java.util.UUID.randomUUID().toString().replace("-", ""));
          return req.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
      } catch (Throwable t) {
          LogWriter.log(TAG, "buildTtsJson err: " + t);
          return "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      }

    }
    /** 将 16bit mono PCM 包装为 WAV 文件字节。 */
    public static byte[] pcmToWav(byte[] pcm, int sampleRate) {
        if (pcm == null || pcm.length == 0 || sampleRate <= 0) return null;
        try {
            int dataLen = pcm.length;
            int byteRate = sampleRate * 2;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(dataLen + 44);
            java.io.DataOutputStream dos = new java.io.DataOutputStream(bos);
            dos.writeBytes("RIFF");
            dos.writeInt(Integer.reverseBytes(36 + dataLen));
            dos.writeBytes("WAVE");
            dos.writeBytes("fmt ");
            dos.writeInt(Integer.reverseBytes(16));
            dos.writeShort(Short.reverseBytes((short) 1));
            dos.writeShort(Short.reverseBytes((short) 1));
            dos.writeInt(Integer.reverseBytes(sampleRate));
            dos.writeInt(Integer.reverseBytes(byteRate));
            dos.writeShort(Short.reverseBytes((short) 2));
            dos.writeShort(Short.reverseBytes((short) 16));
            dos.writeBytes("data");
            dos.writeInt(Integer.reverseBytes(dataLen));
            dos.write(pcm, 0, pcm.length);
            dos.flush();
            return bos.toByteArray();
        } catch (Throwable t) {
            LogWriter.log(TAG, "pcmToWav err: " + t);
            return null;
        }
    }
}
