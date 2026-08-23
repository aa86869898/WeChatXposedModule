package com.leshao.v3.service;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.PowerManager;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.wm.utils.WmPrefs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentLinkedQueue;

public class CubeTtsPlayer {

    private static final String TAG = "CubeTtsPlayer";
    private static final String API_URL = "https://peiyinmofang.com/api/open/v1/tts/simple-generate";

    private final File mCacheDir;
    private final PowerManager.WakeLock mWakeLock;
    private final ConcurrentLinkedQueue<String> mQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean mPlaying = false;
    private volatile boolean mPaused = false;
    private volatile int mSpeakSeq = 0;
    private volatile int mDoneSeq = 0;

    public CubeTtsPlayer(Context ctx) {
        mCacheDir = new File(ctx.getFilesDir(), "cube_tts_cache");
        mCacheDir.mkdirs();

        PowerManager pm = (PowerManager) ctx.getApplicationContext().getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "leshao:cube_tts");
    }

    public boolean isSpeaking() { return mPlaying; }

    public boolean hasPendingSpeak() {
        return mPlaying || mSpeakSeq > mDoneSeq || !mQueue.isEmpty();
    }

    public void speak(String text) {
        if (text == null || text.isEmpty()) return;
        mQueue.offer(text);
        processQueue();
    }

    public void pause() {
        mPaused = true;
        mPlaying = false;
    }

    public void stop() {
        mPaused = false;
        mPlaying = false;
        mQueue.clear();
    }

    public void shutdown() {
        stop();
    }

    private void processQueue() {
        if (mPlaying) return;
        String text = mQueue.poll();
        if (text == null) return;

        mPlaying = true;
        mSpeakSeq++;
        acquireWakeLock();

        new Thread(() -> {
            try {
                File wav = synthesize(text);
                if (wav != null && !mPaused) {
                    playWavSync(wav);
                    wav.delete();
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "process err: " + t.getMessage());
            } finally {
                mPlaying = false;
                mDoneSeq++;
                releaseWakeLock();
                if (!mPaused) processQueue();
            }
        }, "leshao-cube-tts").start();
    }

    private File synthesize(String text) {
        try {
            String apiKey = WmPrefs.getStr("tts_cube_key", "");
            String voiceId = WmPrefs.getStr("tts_cube_voice", "");
            if (apiKey.isEmpty() || voiceId.isEmpty()) {
                LogWriter.log(TAG, "synthesize: key or voice empty, fallback to system TTS");
                return null;
            }

            URL url = new URL(API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-API-Key", apiKey);

                String jsonBody = "{\"voiceId\":\"" + voiceId + "\",\"text\":\"" + escapeJson(text) + "\"}";
                OutputStream os = null;
                try {
                os = conn.getOutputStream();
                os.write(jsonBody.getBytes("UTF-8"));
                os.flush();
                } finally {
                    if (os != null) { try { os.close(); } catch (Throwable ignored) {} }
                }

                int code = conn.getResponseCode();
                if (code != 200) {
                    LogWriter.log(TAG, "synthesize: API HTTP " + code);
                    return null;
                }

                InputStream is = null;
                StringBuilder sb = new StringBuilder();
                try {
                is = conn.getInputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                } finally {
                    if (is != null) { try { is.close(); } catch (Throwable ignored) {} }
                }

                String resp = sb.toString();
                int audioIdx = resp.indexOf("\"audio\":\"");
                if (audioIdx < 0) {
                    LogWriter.log(TAG, "synthesize: no audio field");
                    return null;
                }
                int audioStart = audioIdx + 9;
                int audioEnd = resp.indexOf("\"", audioStart);
                if (audioEnd < 0) return null;

                String audioUrl = resp.substring(audioStart, audioEnd).replace("\\/", "/");

                URL audioURL = new URL(audioUrl);
                HttpURLConnection aConn = (HttpURLConnection) audioURL.openConnection();
                try {
                    aConn.setConnectTimeout(15000);
                    aConn.setReadTimeout(15000);
                    if (aConn.getResponseCode() != 200) return null;

                    File wav = new File(mCacheDir, "tts_" + System.currentTimeMillis() + ".wav");
                    InputStream ais = null;
                    FileOutputStream fos = null;
                    try {
                    ais = aConn.getInputStream();
                    fos = new FileOutputStream(wav);
                    byte[] wBuf = new byte[8192];
                    int rn;
                    while ((rn = ais.read(wBuf)) > 0) fos.write(wBuf, 0, rn);
                    fos.flush();
                    } finally {
                        if (ais != null) { try { ais.close(); } catch (Throwable ignored) {} }
                        if (fos != null) { try { fos.close(); } catch (Throwable ignored) {} }
                    }

                    LogWriter.log(TAG, "synthesize OK: " + wav.length() + "b");
                    return wav;
                } finally {
                    aConn.disconnect();
                }
            } finally {
                conn.disconnect();
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "synthesize err: " + t.getMessage());
            return null;
        }
    }

    private void playWavSync(File wav) {
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setDataSource(wav.getAbsolutePath());
            mp.prepare();
            mp.setOnCompletionListener(m -> {
                synchronized (mp) { mp.notify(); }
            });
            mp.start();
            synchronized (mp) {
                try { mp.wait(mp.getDuration() + 5000); } catch (InterruptedException ignored) {}
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "play err: " + t.getMessage());
        } finally {
            try { mp.release(); } catch (Throwable ignored) {}
        }
    }

    private void acquireWakeLock() {
        try {
            if (mWakeLock != null && !mWakeLock.isHeld()) {
                mWakeLock.acquire(60000);
            }
        } catch (Throwable ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (mWakeLock != null && mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        } catch (Throwable ignored) {}
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
