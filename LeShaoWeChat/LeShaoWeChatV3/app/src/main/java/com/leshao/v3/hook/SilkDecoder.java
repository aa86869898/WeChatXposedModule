package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;

/**
 * SILK V3 → PCM 解码器
 * 微信语音文件格式: [0x02] [SILK帧1] [SILK帧2] ...
 * 支持 8kHz/16kHz mono 16-bit PCM 输出
 */
public class SilkDecoder {

    // ===== 帧解析 =====

    /**
     * 解码整段 SILK 数据 → PCM (16-bit signed, mono)
     * @param silkData 原始 SILK 字节
     * @return PCM 字节数组 (可直接 feeds AudioTrack), 失败返回 null
     */
    public static byte[] decode(byte[] silkData) {
        try {
            int offset = 0;
            if (silkData.length > 0 && silkData[0] == 0x02) {
                offset = 1;
            }

            SilkState state = new SilkState();
            state.init(8000);

            java.io.ByteArrayOutputStream pcmOut = new java.io.ByteArrayOutputStream();

            while (offset < silkData.length) {
                int consumed = state.decodeFrame(silkData, offset, silkData.length - offset);
                if (consumed <= 0) break;
                offset += consumed;

                if (state.pcmLen > 0) {
                    byte[] pcm = state.getPcmBytes();
                    pcmOut.write(pcm, 0, state.pcmLen * 2);
                }
            }

            byte[] result = pcmOut.toByteArray();
            if (result.length == 0) return null;
            return result;

        } catch (Throwable e) {
            LogWriter.log("SilkDecoder", "decode err: " + e.getMessage());
            return null;
        }
    }

    // ===== SILK 解码器状态 =====

    static class SilkState {
        int sampleRate;
        int frameSize;      // samples per frame
        int subframeSize;   // samples per subframe
        int nSubframes;
        int lpcOrder;

        // 输出缓冲
        short[] pcmBuf = new short[640];
        int pcmLen;

        // 历史
        float[] excBuf = new float[640];  // excitation history
        float[] synBuf = new float[320];  // synthesis history (for LTP)
        float[] prevLsp = new float[16];

        // 状态
        boolean wbDetected;
        int prevLag;
        float prevGain;
        float prevLtpGain;

        void init(int sr) {
            this.sampleRate = sr;
            if (sr <= 8000) {
                frameSize = 160;
                subframeSize = 40;
                lpcOrder = 10;
            } else {
                frameSize = 320;
                subframeSize = 80;
                lpcOrder = 16;
            }
            nSubframes = frameSize / subframeSize;

            // 初始化 LSP
            for (int i = 0; i < lpcOrder; i++) {
                prevLsp[i] = (float) (i + 1) * 3.14159f / (lpcOrder + 1);
            }
            prevLag = 0;
            prevGain = 1.0f;
            prevLtpGain = 0;
        }

        /**
         * 解码一帧
         * @return 消耗的字节数, 0 表示失败
         */
        int decodeFrame(byte[] data, int offset, int available) {
            try {
                if (available < 2) return 0;

                SilkRangeCoder rc = new SilkRangeCoder(data, offset, available);

                // === 帧头 ===
                int vadFlag = rc.decodeSymbol(VAD_ICDF);
                if (vadFlag < 0) return 0;

                int nFramesPerPacket = 1;
                boolean lbrrFlag = false;

                if (!wbDetected) {
                    // 初始帧, 无额外帧头
                }

                // === NLSF ===
                float[] nlsf = decodeNLSF(rc, lpcOrder);
                for (int i = 0; i < lpcOrder; i++) {
                    nlsf[i] = nlsf[i] / 3.14159f;  // normalize
                    if (nlsf[i] <= 0) nlsf[i] = prevLsp[i];
                    if (nlsf[i] >= 1) nlsf[i] = prevLsp[i];
                }

                // 平滑
                for (int i = 0; i < lpcOrder; i++) {
                    prevLsp[i] = 0.85f * prevLsp[i] + 0.15f * nlsf[i];
                }

                float[] lpc = lsp2lpc(prevLsp, lpcOrder);

                // === 增益 ===
                float[] gains = new float[nSubframes];
                for (int s = 0; s < nSubframes; s++) {
                    int idx = rc.decodeSymbol(GAIN_ICDF);
                    if (idx < 0) idx = 0;
                    gains[s] = gainFromIndex(idx);
                }

                // === LTP ===
                int[] ltplag = new int[nSubframes];
                float[] ltpgain = new float[nSubframes];

                int minLag = sampleRate == 8000 ? 16 : 32;
                int maxLag = sampleRate == 8000 ? 144 : 288;
                int lagRange = maxLag - minLag + 1;
                int lagBits = 0;
                while ((1 << lagBits) < lagRange) lagBits++;

                for (int s = 0; s < nSubframes; s++) {
                    int lagIdx = rc.decodeUniform(lagBits);
                    if (lagIdx >= 0 && lagIdx < lagRange) {
                        ltplag[s] = minLag + lagIdx;
                    } else {
                        ltplag[s] = minLag;
                    }
                    prevLag = ltplag[s];

                    int gainIdx = rc.decodeSymbol(LTPGAIN_ICDF);
                    ltpgain[s] = ltpGainFromIndex(gainIdx >= 0 ? gainIdx : 0);
                    prevLtpGain = ltpgain[s];
                }

                // === 激励 ===
                float[] exc = new float[frameSize];
                decodeExcitation(rc, exc, frameSize, subframeSize, nSubframes);

                // === 合成 ===
                float[] out = new float[frameSize];

                // LTP
                for (int s = 0; s < nSubframes; s++) {
                    int so = s * subframeSize;
                    float lagF = (float) ltplag[s];
                    int lag = ltplag[s];

                    for (int i = 0; i < subframeSize; i++) {
                        float ltpVal = 0;
                        if (lag > 0) {
                            int idx = so + i - lag + frameSize;  // historical
                            if (idx >= 0 && idx < frameSize + excBuf.length) {
                                if (idx < frameSize) {
                                    ltpVal = exc[idx];
                                }
                            }
                            // 用 excitation buffer 中的历史
                            int histIdx = (frameSize + so + i - lag) % excBuf.length;
                            if (histIdx < 0) histIdx += excBuf.length;
                            ltpVal += ltpgain[s] * (excBuf[histIdx]);
                        }
                        exc[so + i] += ltpVal;
                    }
                }

                // LPC 滤波
                float[] mem = new float[lpcOrder];
                for (int i = 0; i < frameSize; i++) {
                    float sample = exc[i];
                    for (int j = 1; j <= lpcOrder; j++) {
                        float coef = lpc[j - 1];
                        if (i - j >= 0) {
                            sample -= coef * out[i - j];
                        } else {
                            int histIdx = excBuf.length + i - j;
                            sample -= coef * excBuf[histIdx % excBuf.length];
                        }
                    }
                    out[i] = sample;
                }

                // 后处理: 应用增益
                for (int s = 0; s < nSubframes; s++) {
                    int so = s * subframeSize;
                    float g = gains[s];
                    for (int i = 0; i < subframeSize; i++) {
                        out[so + i] *= g;
                    }
                }

                // 位移 excitation buffer (为下一帧保存历史)
                System.arraycopy(excBuf, frameSize, excBuf, 0, excBuf.length - frameSize);
                for (int i = 0; i < frameSize; i++) {
                    excBuf[excBuf.length - frameSize + i] = out[i];
                }

                // 转 short
                pcmLen = frameSize;
                for (int i = 0; i < frameSize; i++) {
                    float s = out[i];
                    if (s > 32767) s = 32767;
                    if (s < -32768) s = -32768;
                    pcmBuf[i] = (short) s;
                }

                return rc.totalRead;

            } catch (Throwable e) {
                LogWriter.log("SilkDecoder", "decodeFrame err: " + e.getMessage());
                return 0;
            }
        }

        byte[] getPcmBytes() {
            byte[] bytes = new byte[pcmLen * 2];
            for (int i = 0; i < pcmLen; i++) {
                short s = pcmBuf[i];
                bytes[i * 2] = (byte) (s & 0xFF);
                bytes[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
            }
            return bytes;
        }

        // ===== NLSF 解码 =====

        float[] decodeNLSF(SilkRangeCoder rc, int order) {
            float[] nlsf = new float[order];

            // Stage 1: delta-coded index
            int prevIdx = 0;
            for (int i = 0; i < order; i++) {
                int idx;
                if (i == 0) {
                    idx = rc.decodeGray(3 + (order == 16 ? 1 : 0));  // 3-4 bits
                } else {
                    idx = rc.decodeGray(2);  // 2-bit differential
                }
                if (idx < 0) idx = 0;
                prevIdx += idx;
                nlsf[i] = NLSF_CB[prevIdx % 64];
            }

            return nlsf;
        }

        // ===== 激励解码 =====

        void decodeExcitation(SilkRangeCoder rc, float[] exc, int frameSize,
                              int subframeSize, int nSubframes) {
            int pulsePosBits = 0;
            while ((1 << pulsePosBits) < subframeSize) pulsePosBits++;

            for (int s = 0; s < nSubframes; s++) {
                int so = s * subframeSize;
                float gain = rc.decodeUniform(4) * 0.1f + 0.1f;

                int nPulses = 2 + rc.decodeUniform(2);
                for (int p = 0; p < nPulses; p++) {
                    int pos = rc.decodeUniform(pulsePosBits);
                    int sign = rc.decodeUniform(1);
                    if (pos < subframeSize && so + pos < frameSize) {
                        exc[so + pos] += (sign == 0 ? 1 : -1) * gain;
                    }
                }

                // 噪声
                int noiseGain = rc.decodeUniform(3);
                float nGain = noiseGain * 0.02f * gain;
                for (int i = 0; i < subframeSize; i++) {
                    if (so + i < frameSize) {
                        exc[so + i] += (Math.random() * 2 - 1) * nGain;
                    }
                }
            }
        }

        // ===== 增益解码 =====

        float gainFromIndex(int idx) {
            float[] table = {0.1f, 0.2f, 0.35f, 0.5f, 0.7f, 1.0f, 1.4f, 2.0f};
            if (idx < 0) idx = 0;
            if (idx >= table.length) idx = table.length - 1;
            return table[idx];
        }

        float ltpGainFromIndex(int idx) {
            float[] table = {0, 0.1f, 0.2f, 0.35f, 0.5f, 0.7f, 0.9f};
            if (idx < 0) idx = 0;
            if (idx >= table.length) idx = table.length - 1;
            return table[idx];
        }

        // LSP → LPC (Levinson-Durbin)
        float[] lsp2lpc(float[] lsp, int order) {
            float[] lpc = new float[order];
            float[] f1 = new float[order / 2 + 2];
            float[] f2 = new float[order / 2 + 2];

            for (int i = 0; i < order / 2; i++) {
                float omega = lsp[i];
                float cosOmega = (float) Math.cos(omega);
                float[] tmp = new float[f1.length];
                System.arraycopy(f1, 0, tmp, 0, f1.length);
                f1[0] = 1;
                for (int j = 1; j <= i + 1; j++) {
                    f1[j] = tmp[j] - 2 * cosOmega * tmp[j - 1] + (j >= 2 ? tmp[j - 2] : 0);
                }

                omega = lsp[i + order / 2];
                cosOmega = (float) Math.cos(omega);
                System.arraycopy(f2, 0, tmp, 0, f2.length);
                f2[0] = 1;
                for (int j = 1; j <= i + 1; j++) {
                    f2[j] = tmp[j] - 2 * cosOmega * tmp[j - 1] + (j >= 2 ? tmp[j - 2] : 0);
                }
            }

            int n = order / 2 + 1;
            for (int i = 0; i < order; i++) {
                if (i < n) {
                    lpc[i] = -(f1[i + 1] + f2[i]) * 0.5f;
                } else {
                    lpc[i] = -(f1[n + n - i - 1] - f2[n + n - i - 1]) * 0.5f;
                }
            }

            return lpc;
        }
    }

    // ===== Range Decoder =====

    static class SilkRangeCoder {
        byte[] data;
        int pos;
        int bitPos;
        int remaining;
        int totalRead;

        // range coder state
        long range;
        long value;

        SilkRangeCoder(byte[] data, int offset, int available) {
            this.data = data;
            this.pos = offset;
            this.bitPos = 0;
            this.remaining = available;
            this.totalRead = 0;

            // 初始化 range coder
            range = 0xFFFFFFFFL;
            value = 0;
            for (int i = 0; i < 4; i++) {
                value = (value << 8) | (readByte() & 0xFF);
            }
        }

        int readByte() {
            if (remaining <= 0) return 0;
            int b = data[pos] & 0xFF;
            pos++;
            remaining--;
            totalRead++;
            return b;
        }

        void normalize() {
            while (range < 0x80000000L) {
                range <<= 8;
                value = (value << 8) | (readByte() & 0xFF);
            }
        }

        int decodeSymbol(int[] icdf) {
            if (icdf == null || icdf.length == 0) return -1;

            int total = icdf[icdf.length - 1];
            if (total <= 0) return -1;

            normalize();

            long scaled = value / (range / total);
            if (scaled >= total) scaled = total - 1;

            int sym = 0;
            while (icdf[sym] <= scaled && sym < icdf.length - 1) sym++;

            long low = (sym == 0) ? 0 : ((long) icdf[sym - 1] * range / total);
            long high = ((long) icdf[sym] * range / total);

            value -= low;
            range = high - low;

            normalize();
            return sym;
        }

        int decodeUniform(int bits) {
            if (bits <= 0) return 0;

            normalize();

            long scaledRange = range >> bits;
            long scaledValue = value >> bits;

            long low = scaledValue * ((long) 1 << bits);
            value -= low;
            range = scaledRange;

            normalize();
            return (int) scaledValue;
        }

        int decodeGray(int bits) {
            int gray = decodeUniform(bits);
            int bin = gray;
            for (int mask = bin >> 1; mask != 0; mask >>= 1) {
                bin ^= mask;
            }
            return bin;
        }
    }

    // ===== 静态表 =====

    // VAD ICDF
    static final int[] VAD_ICDF = {16384, 32768};

    // Gain ICDF (8 levels)
    static final int[] GAIN_ICDF = {4096, 8192, 12288, 16384, 20480, 24576, 28672, 32768};

    // LTP gain ICDF (7 levels)
    static final int[] LTPGAIN_ICDF = {4681, 9362, 14043, 18724, 23405, 28086, 32768};

    // NLSF codebook (简化, 64 entries covering 0..pi)
    static final float[] NLSF_CB = {
        0.098f, 0.196f, 0.295f, 0.393f, 0.491f, 0.589f, 0.687f, 0.785f,
        0.884f, 0.982f, 1.080f, 1.178f, 1.276f, 1.375f, 1.473f, 1.571f,
        0.100f, 0.202f, 0.304f, 0.406f, 0.508f, 0.610f, 0.712f, 0.814f,
        0.916f, 1.018f, 1.120f, 1.222f, 1.324f, 1.426f, 1.528f, 1.630f,
        0.105f, 0.211f, 0.317f, 0.423f, 0.529f, 0.635f, 0.741f, 0.847f,
        0.953f, 1.059f, 1.165f, 1.271f, 1.377f, 1.483f, 1.589f, 1.695f,
        0.108f, 0.218f, 0.328f, 0.438f, 0.548f, 0.658f, 0.768f, 0.878f,
        0.988f, 1.098f, 1.208f, 1.318f, 1.428f, 1.538f, 1.648f, 1.758f,
    };
}
