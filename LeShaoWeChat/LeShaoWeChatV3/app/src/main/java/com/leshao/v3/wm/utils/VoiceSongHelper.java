package com.leshao.v3.wm.utils;

import android.app.Activity;
import android.os.Environment;

import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.TtsVoiceSender;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class VoiceSongHelper {

    private static final String TAG = "VoiceSong";

    public static void sendMp3AsVoiceFromUrl(Activity act, String mp3Url, String fileNameHint, String talker) {
        try {
            File cacheDir = getSongCacheDir();
            cacheDir.mkdirs();
            String safeName = fileNameHint.replaceAll("[\\\\/:*?\"<>|]", "_").replace(" ", "");
            File mp3File = new File(cacheDir, safeName + "_" + System.currentTimeMillis() + ".mp3");

            LogWriter.log(TAG, "downloading: " + mp3Url + " -> " + mp3File.getAbsolutePath());
            byte[] pcm = downloadAndDecode(mp3Url, mp3File);
            if (pcm == null) {
                LogWriter.log(TAG, "download/decode failed: " + mp3Url);
                return;
            }
            LogWriter.log(TAG, "pcm decoded: " + pcm.length + " bytes, sending...");
            String clientMsgId = "vs_" + System.currentTimeMillis();
            TtsVoiceSender.sendPcm16kMonoAsVoice(talker, pcm, clientMsgId);
        } catch (Exception e) {
            LogWriter.log(TAG, "sendMp3AsVoiceFromUrl err: " + e.getMessage());
        }
    }

    private static byte[] downloadAndDecode(String mp3Url, File mp3File) {
        HttpURLConnection conn = null;
        InputStream is = null;
        FileOutputStream fos = null;
        try {
            URL url = new URL(mp3Url);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code != 200) {
                LogWriter.log(TAG, "download HTTP " + code);
                return null;
            }

            is = conn.getInputStream();
            fos = new FileOutputStream(mp3File);
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            fos.flush();
            fos.close();
            is.close();
            conn.disconnect();

            LogWriter.log(TAG, "downloaded: " + mp3File.length() + " bytes, decoding...");
            byte[] pcm = TtsVoiceSender.decodeAudioToPcm16kMono(mp3File.getAbsolutePath());
            if (!mp3File.delete()) mp3File.deleteOnExit();
            return pcm;
        } catch (Exception e) {
            LogWriter.log(TAG, "downloadAndDecode err: " + e.getMessage());
            return null;
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
            try { if (is != null) is.close(); } catch (Exception ignored) {}
            try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    private static File getSongCacheDir() {
        try {
            File wmDir = new File(Environment.getExternalStorageDirectory(), "WeChatMaster");
            return new File(wmDir, "song_cache");
        } catch (Exception e) {
            return new File("/data/local/tmp/leshao_song_cache");
        }
    }
}
