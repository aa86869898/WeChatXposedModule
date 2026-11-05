package com.leshao.v3.service;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * 音频转码工具：OGG/Opus → WAV（MediaCodec 硬解）。
 * 傻包免登录 TTS 返回 OGG/Opus 24kHz mono，转成 WAV 后供 MediaPlayer / AMR 编码链路使用。
 */
public final class AudioCodecUtil {

    private AudioCodecUtil() {
    }

    /** 将 OGG/Opus 音频字节解码为 WAV 文件，成功返回 true。 */
    public static boolean oggToWav(byte[] oggData, File wavOut) {
        if (oggData == null || oggData.length == 0) {
            return false;
        }
        File tmp = null;
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        try {
            tmp = File.createTempFile("ogg_", ".ogg", wavOut.getParentFile());
            FileOutputStream fo = new FileOutputStream(tmp);
            try {
                fo.write(oggData);
                fo.flush();
            } finally {
                fo.close();
            }

            extractor = new MediaExtractor();
            extractor.setDataSource(tmp.getAbsolutePath());
            int trackIndex = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    break;
                }
            }
            if (trackIndex < 0) {
                return false;
            }
            MediaFormat format = extractor.getTrackFormat(trackIndex);
            extractor.selectTrack(trackIndex);
            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 24000;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;

            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            decoder.configure(format, null, null, 0);
            decoder.start();

            ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int timeoutUs = 10000;
            while (!outputDone) {
                if (!inputDone) {
                    int inIdx = decoder.dequeueInputBuffer(timeoutUs);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
                        int sampleSize = extractor.readSampleData(inBuf, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sampleSize,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIdx = decoder.dequeueOutputBuffer(info, timeoutUs);
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        ByteBuffer outBuf = decoder.getOutputBuffer(outIdx);
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        byte[] chunk = new byte[info.size];
                        outBuf.get(chunk);
                        pcmOut.write(chunk);
                    }
                    decoder.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
            decoder.stop();
            decoder.release();
            decoder = null;
            extractor.release();
            extractor = null;

            byte[] pcm = pcmOut.toByteArray();
            if (pcm.length == 0) {
                return false;
            }
            writeWav(wavOut, pcm, sampleRate, channels);
            return true;
        } catch (Throwable t) {
            com.leshao.v3.LogWriter.log("AudioCodecUtil", "oggToWav err: " + t.getMessage());
            return false;
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (Throwable ignored) {
                }
                try {
                    decoder.release();
                } catch (Throwable ignored) {
                }
            }
            if (extractor != null) {
                extractor.release();
            }
            if (tmp != null) {
                try {
                    tmp.delete();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void writeWav(File f, byte[] pcm, int sampleRate, int channels) throws Exception {
        int bitsPerSample = 16;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        java.io.DataOutputStream dos = new java.io.DataOutputStream(new FileOutputStream(f));
        try {
            dos.writeBytes("RIFF");
            dos.writeInt(Integer.reverseBytes(36 + pcm.length));
            dos.writeBytes("WAVE");
            dos.writeBytes("fmt ");
            dos.writeInt(Integer.reverseBytes(16));
            dos.writeShort(Short.reverseBytes((short) 1));
            dos.writeShort(Short.reverseBytes((short) channels));
            dos.writeInt(Integer.reverseBytes(sampleRate));
            dos.writeInt(Integer.reverseBytes(byteRate));
            dos.writeShort(Short.reverseBytes((short) blockAlign));
            dos.writeShort(Short.reverseBytes((short) bitsPerSample));
            dos.writeBytes("data");
            dos.writeInt(Integer.reverseBytes(pcm.length));
            dos.write(pcm);
            dos.flush();
        } finally {
            dos.close();
        }
    }
}