package com.leshao.v3.ui.widgets;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.MediaPlayer;
import android.media.audiofx.Visualizer;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.LinearInterpolator;

import com.leshao.v3.LogWriter;
import com.leshao.v3.ui.AppColors;

import java.util.Random;

/**
 * v1081 C3 柱状频谱（v1084 提高灵敏度 + 流光渐变）。
 *
 * <p>22 根柱以「流光渐变」着色：三色渐变在整条频谱上横向循环流动。高度由播放器的
 * 实时音频频谱驱动（{@link Visualizer} 挂 {@link MediaPlayer} 的 audio session 取
 * FFT），每根柱按各自频段的长期峰值独立归一，响应更灵敏、大声时明显冲高，且各柱
 * 高低错落，不做左右流动。</p>
 *
 * <p>设备/宿主不支持 {@link Visualizer}（如未授予录音权限）时，自动回退为各柱独立
 * 随机律动，仍保持快节奏升降观感。</p>
 *
 * <p>纯皮肤绘制，不承载业务语义。</p>
 */
public class EqBarsView extends View {

    private static final String TAG = "LeShaoV3.EqBars";
    private static final int BAR_COUNT = 22;
    private static final long FALLBACK_INTERVAL_MS = 80L;
    private static final float MAG_FLOOR = 6f;
    private static final float SILENCE_FLOOR = 2f;
    private static final long FLOW_DURATION_MS = 3200L;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final float[] mTarget = new float[BAR_COUNT];
    private final float[] mCurrent = new float[BAR_COUNT];
    private final int[] mPerm = new int[BAR_COUNT];
    private final float[] mBandPeak = new float[BAR_COUNT];
    private final float[] mJitter = new float[BAR_COUNT];
    private final float[] mAttack = new float[BAR_COUNT];
    private final float[] mDecay = new float[BAR_COUNT];
    private final Random mRandom = new Random();

    private ValueAnimator mAnim;
    private Visualizer mVisualizer;
    private boolean mUsingVisualizer;
    private float mRef = MAG_FLOOR;
    private long mLastFallbackMs;
    private long mLastFftMs;
    private long mLastJitterMs;

    // 流光渐变：单例 shader + 平移矩阵，逐帧改变相位形成横向流动
    private LinearGradient mGrad;
    private final Matrix mGradMatrix = new Matrix();
    private int mGradWidth;

    public EqBarsView(Context ctx) {
        super(ctx);
        mPaint.setStyle(Paint.Style.FILL);
        // 固定伪随机：打散「频段→柱」映射并给每根柱不同的起落速度/抖动，
        // 避免所有柱同步升降，也避免相邻柱一起动。
        Random seed = new Random(1081L);
        int[] perm = new int[BAR_COUNT];
        for (int i = 0; i < BAR_COUNT; i++) perm[i] = i;
        for (int i = BAR_COUNT - 1; i > 0; i--) {
            int j = seed.nextInt(i + 1);
            int t = perm[i];
            perm[i] = perm[j];
            perm[j] = t;
        }
        System.arraycopy(perm, 0, mPerm, 0, BAR_COUNT);
        for (int i = 0; i < BAR_COUNT; i++) {
            mJitter[i] = 0.85f + seed.nextFloat() * 0.15f;
            mAttack[i] = 0.50f + seed.nextFloat() * 0.28f;
            mDecay[i] = 0.24f + seed.nextFloat() * 0.20f;
        }
    }

    /** 开始播放态动画（无音频源时使用回退律动）。 */
    public void start() {
        start(null);
    }

    /** 开始播放态动画，并尝试绑定播放器音频频谱。 */
    public void start(MediaPlayer player) {
        if (mAnim != null && mAnim.isStarted()) return;
        attachVisualizer(player);
        mLastFallbackMs = 0L;
        mLastFftMs = 0L;
        mLastJitterMs = 0L;
        mRef = MAG_FLOOR;
        for (int i = 0; i < BAR_COUNT; i++) {
            mBandPeak[i] = MAG_FLOOR;
        }
        mAnim = ValueAnimator.ofFloat(0f, 1f);
        mAnim.setDuration(1000L);
        mAnim.setRepeatCount(ValueAnimator.INFINITE);
        mAnim.setInterpolator(new LinearInterpolator());
        mAnim.addUpdateListener(a -> onFrame());
        mAnim.start();
    }

    /** 停止动画并回到静止态。 */
    public void stop() {
        detachVisualizer();
        if (mAnim != null) {
            mAnim.cancel();
            mAnim = null;
        }
        for (int i = 0; i < BAR_COUNT; i++) {
            mTarget[i] = 0f;
            mCurrent[i] = 0f;
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    // ==================== 帧驱动：快起快落 ====================

    private void onFrame() {
        long now = SystemClock.uptimeMillis();
        if (now - mLastJitterMs >= 130L) {
            mLastJitterMs = now;
            for (int i = 0; i < BAR_COUNT; i++) {
                mJitter[i] = 0.86f + mRandom.nextFloat() * 0.14f;
            }
        }
        if (!mUsingVisualizer) {
            if (now - mLastFallbackMs >= FALLBACK_INTERVAL_MS) {
                mLastFallbackMs = now;
                for (int i = 0; i < BAR_COUNT; i++) {
                    mTarget[i] = 0.12f + mRandom.nextFloat() * 0.78f;
                }
            }
        } else if (now - mLastFftMs > 400L) {
            // 频谱回调长时间中断(暂停/异常)时缓慢回落
            mLastFallbackMs = now;
            for (int i = 0; i < BAR_COUNT; i++) {
                mTarget[i] *= 0.5f;
            }
        }
        for (int i = 0; i < BAR_COUNT; i++) {
            float t = mTarget[i] * mJitter[i];
            if (t > 1f) t = 1f;
            float cur = mCurrent[i];
            float k = t > cur ? mAttack[i] : mDecay[i];
            float next = cur + (t - cur) * k;
            if (Math.abs(next - t) < 0.004f) next = t;
            mCurrent[i] = next;
        }
        invalidate();
    }

    // ==================== 频谱采集 ====================

    private void attachVisualizer(MediaPlayer player) {
        detachVisualizer();
        if (player == null) return;
        try {
            int session = player.getAudioSessionId();
            if (session == 0) return;
            int size = Visualizer.getCaptureSizeRange()[1];
            Visualizer v = new Visualizer(session);
            v.setCaptureSize(size);
            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int rate) {
                }

                @Override
                public void onFftDataCapture(Visualizer visualizer, byte[] fft, int rate) {
                    applyFft(fft, rate);
                }
            }, Visualizer.getMaxCaptureRate(), false, true);
            v.setEnabled(true);
            mVisualizer = v;
            mUsingVisualizer = true;
            LogWriter.log(TAG, "Visualizer 已绑定 session=" + session + " size=" + size);
        } catch (Throwable t) {
            mUsingVisualizer = false;
            mVisualizer = null;
            LogWriter.log(TAG, "Visualizer 不可用, 回退模拟律动: " + t);
        }
    }

    private void detachVisualizer() {
        if (mVisualizer != null) {
            try {
                mVisualizer.setEnabled(false);
            } catch (Throwable ignored) {
            }
            try {
                mVisualizer.release();
            } catch (Throwable ignored) {
            }
            mVisualizer = null;
        }
        mUsingVisualizer = false;
    }

    /**
     * 将 Visualizer FFT 帧映射到 22 根柱的目标高度。
     *
     * <p>每个频段维护各自的长期峰值（快起慢落）并独立归一，因此大声时对应柱会明显
     * 冲高，各柱互不牵连；再用整体响度做动态门限，安静时整体压低，避免动辄顶到最上端。</p>
     */
    private void applyFft(byte[] fft, int samplingRate) {
        if (fft == null || fft.length < 4) return;
        mLastFftMs = SystemClock.uptimeMillis();
        int capture = fft.length;
        int bins = capture / 2;
        int sr = samplingRate > 0 ? samplingRate : 44100;

        float[] mags = new float[BAR_COUNT];
        float frameSum = 0f;
        for (int b = 0; b < BAR_COUNT; b++) {
            int ks = bandFreq(b, capture, sr, bins);
            int ke = Math.max(ks + 1, bandFreq(b + 1, capture, sr, bins));
            float sum = 0f;
            int n = 0;
            for (int k = ks; k < ke && k < bins; k++) {
                float re = fft[2 * k];
                float im = fft[2 * k + 1];
                sum += (float) Math.sqrt(re * re + im * im);
                n++;
            }
            float mag = n > 0 ? sum / n : 0f;
            mags[b] = mag;
            frameSum += mag;
        }
        float frameMean = frameSum / BAR_COUNT;

        for (int i = 0; i < BAR_COUNT; i++) {
            int band = mPerm[i];
            float mag = mags[band];
            // 每根柱独立峰值：瞬时上去，慢慢回落，保证大声时明显冲高
            float peak = mBandPeak[band] = Math.max(mag, mBandPeak[band] * 0.9975f);
            float denom = Math.max(peak, MAG_FLOOR);
            float v;
            if (mag < SILENCE_FLOOR) {
                v = 0f;
            } else {
                v = mag / denom;
                // gamma<1 扩张中低幅度，小声音也有明显起伏
                v = (float) Math.pow(v, 0.78) * 1.05f;
            }
            if (v < 0f) v = 0f;
            else if (v > 1f) v = 1f;
            mTarget[i] = v;
        }

        // 整体响度门限：长期均值慢释放，安静段落整体压低
        mRef = Math.max(frameMean, mRef * 0.9993f);
        if (mRef < MAG_FLOOR) mRef = MAG_FLOOR;
        float loud = frameMean / mRef;
        if (loud < 0f) loud = 0f;
        else if (loud > 1f) loud = 1f;
        float gate = 0.55f + 0.45f * loud;
        for (int i = 0; i < BAR_COUNT; i++) {
            mTarget[i] *= gate;
        }
    }

    private int bandFreq(int band, int capture, int samplingRate, int bins) {
        double fMin = 60.0;
        double fMax = 16000.0;
        double f = fMin * Math.pow(fMax / fMin, band / (double) BAR_COUNT);
        int k = (int) (f * capture / (double) samplingRate);
        return Math.max(1, Math.min(bins - 1, k));
    }

    // ==================== 绘制 ====================

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mGrad = null;
        mGradWidth = 0;
    }

    private void ensureGradient(int w) {
        if (mGrad != null && mGradWidth == w) return;
        mGradWidth = w;
        float span = Math.max(1f, w * 2f);
        // 三色循环，首尾同色以便无缝平移；span 内完成一个周期
        mGrad = new LinearGradient(-span / 2f, 0f, span / 2f, 0f,
                new int[]{AppColors.gradientStart(), AppColors.gradientMid(),
                        AppColors.gradientEnd(), AppColors.gradientStart()},
                new float[]{0f, 0.33f, 0.66f, 1f}, Shader.TileMode.CLAMP);
        mPaint.setShader(mGrad);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float d = getResources().getDisplayMetrics().density;
        float gap = 3f * d;
        float barW = (w - gap * (BAR_COUNT - 1)) / BAR_COUNT;
        if (barW <= 0f) return;
        float radius = Math.min(barW, 4f * d);

        // 流光渐变：横向循环平移相位
        ensureGradient(w);
        if (mGrad != null) {
            float span = Math.max(1f, w * 2f);
            float phase = (SystemClock.uptimeMillis() % FLOW_DURATION_MS) / (float) FLOW_DURATION_MS;
            mGradMatrix.setTranslate(phase * span - span / 2f, 0f);
            mGrad.setLocalMatrix(mGradMatrix);
        }

        for (int i = 0; i < BAR_COUNT; i++) {
            float v = mCurrent[i];
            if (v < 0f) v = 0f;
            else if (v > 1f) v = 1f;
            float bh = h * (0.06f + 0.94f * v);
            float left = i * (barW + gap);
            mRect.set(left, h - bh, left + barW, h);
            canvas.drawRoundRect(mRect, radius, radius, mPaint);
        }
    }
}
