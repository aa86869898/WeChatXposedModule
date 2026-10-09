package com.leshao.v3.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import com.leshao.v3.LogWriter
import com.leshao.v3.ui.AppColors
import java.util.Random

/**
 * v1081 C3 柱状频谱（v1084 提高灵敏度 + 流光渐变）。
 *
 * 22 根柱以「流光渐变」着色：三色渐变在整条频谱上横向循环流动。高度由播放器的
 * 实时音频频谱驱动（Visualizer 挂 MediaPlayer 的 audio session 取 FFT），每根柱按
 * 各自频段的长期峰值独立归一，响应更灵敏、大声时明显冲高，且各柱高低错落。
 *
 * 设备/宿主不支持 Visualizer（如未授予录音权限）时，自动回退为各柱独立随机律动。
 *
 * 纯皮肤绘制，不承载业务语义。
 */
class EqBarsView(ctx: Context) : View(ctx) {

    private val mPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mRect = RectF()
    private val mTarget = FloatArray(BAR_COUNT)
    private val mCurrent = FloatArray(BAR_COUNT)
    private val mPerm = IntArray(BAR_COUNT)
    private val mBandPeak = FloatArray(BAR_COUNT)
    private val mJitter = FloatArray(BAR_COUNT)
    private val mAttack = FloatArray(BAR_COUNT)
    private val mDecay = FloatArray(BAR_COUNT)
    private val mRandom = Random()

    private var mAnim: ValueAnimator? = null
    private var mVisualizer: Visualizer? = null
    private var mUsingVisualizer = false
    private var mFallback = true
    private var mRef = MAG_FLOOR
    private var mLastFallbackMs = 0L
    private var mLastFftMs = 0L
    private var mLastJitterMs = 0L

    // v1139: 精确模式 —— 由播放线程直接推送 PCM, 本地 FFT 分析, 不依赖 Visualizer
    private val mPcmLock = Any()
    private val mPcmRing = FloatArray(PCM_FFT)
    private var mPcmFill = 0
    private var mPcmRate = 24000

    /** 当前柱组占宽比例，可按页面覆盖（如播放器页 90%）。 */
    private var mSpanRatio = SPAN_RATIO

    init {
        mPaint.style = Paint.Style.FILL
        // 固定伪随机：打散「频段→柱」映射并给每根柱不同的起落速度/抖动，
        // 避免所有柱同步升降，也避免相邻柱一起动。
        val seed = Random(1081L)
        val perm = IntArray(BAR_COUNT)
        for (i in 0 until BAR_COUNT) perm[i] = i
        for (i in BAR_COUNT - 1 downTo 1) {
            val j = seed.nextInt(i + 1)
            val t = perm[i]
            perm[i] = perm[j]
            perm[j] = t
        }
        System.arraycopy(perm, 0, mPerm, 0, BAR_COUNT)
        for (i in 0 until BAR_COUNT) {
            mJitter[i] = 0.85f + seed.nextFloat() * 0.15f
            mAttack[i] = 0.50f + seed.nextFloat() * 0.28f
            mDecay[i] = 0.24f + seed.nextFloat() * 0.20f
        }
    }

    /** 开始播放态动画（无音频源时使用回退律动）。 */
    fun start() {
        startInternal(null, true)
    }

    /** 开始播放态动画，并尝试绑定播放器音频频谱。 */
    fun start(player: MediaPlayer?) {
        startInternal(player, true)
    }

    /**
     * v1139: 开始「精确跟随」动画 —— 不做随机回退，柱高完全由
     * pushPcm(byte[], int, int) 推送的 PCM 频谱驱动；无数据时保持静止。
     */
    fun startExact() {
        startInternal(null, false)
    }

    private fun startInternal(player: MediaPlayer?, fallback: Boolean) {
        if (mAnim != null && mAnim!!.isStarted) return
        detachVisualizer()
        mFallback = fallback
        mUsingVisualizer = false
        if (fallback && player != null) {
            attachVisualizer(player)
        }
        mLastFallbackMs = 0L
        mLastFftMs = 0L
        mLastJitterMs = 0L
        mRef = MAG_FLOOR
        for (i in 0 until BAR_COUNT) {
            mBandPeak[i] = MAG_FLOOR
            mTarget[i] = 0f
            mCurrent[i] = 0f
        }
        synchronized(mPcmLock) {
            mPcmFill = 0
        }
        mAnim = ValueAnimator.ofFloat(0f, 1f)
        mAnim!!.duration = 1000L
        mAnim!!.repeatCount = ValueAnimator.INFINITE
        mAnim!!.interpolator = LinearInterpolator()
        mAnim!!.addUpdateListener { onFrame() }
        mAnim!!.start()
    }

    /** 停止动画并回到静止态。 */
    fun stop() {
        detachVisualizer()
        if (mAnim != null) {
            mAnim!!.cancel()
            mAnim = null
        }
        for (i in 0 until BAR_COUNT) {
            mTarget[i] = 0f
            mCurrent[i] = 0f
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    // ==================== 帧驱动：快起快落 ====================

    private fun onFrame() {
        val now = SystemClock.uptimeMillis()
        val exact = !mFallback && !mUsingVisualizer
        if (!exact && now - mLastJitterMs >= 130L) {
            mLastJitterMs = now
            for (i in 0 until BAR_COUNT) {
                mJitter[i] = 0.86f + mRandom.nextFloat() * 0.14f
            }
        }
        if (!mUsingVisualizer) {
            if (mFallback && now - mLastFallbackMs >= FALLBACK_INTERVAL_MS) {
                mLastFallbackMs = now
                for (i in 0 until BAR_COUNT) {
                    mTarget[i] = 0.12f + mRandom.nextFloat() * 0.78f
                }
            }
        } else if (now - mLastFftMs > 400L) {
            // 频谱回调长时间中断(暂停/异常)时缓慢回落
            mLastFallbackMs = now
            for (i in 0 until BAR_COUNT) {
                mTarget[i] *= 0.5f
            }
        }
        for (i in 0 until BAR_COUNT) {
            var t = mTarget[i] * (if (exact) 1f else mJitter[i])
            if (t > 1f) t = 1f
            val cur = mCurrent[i]
            val k = if (t > cur) mAttack[i] else mDecay[i]
            var next = cur + (t - cur) * k
            if (Math.abs(next - t) < 0.004f) next = t
            mCurrent[i] = next
        }
        invalidate()
    }

    // ==================== 频谱采集 ====================

    private fun attachVisualizer(player: MediaPlayer) {
        detachVisualizer()
        if (player == null) return
        try {
            val session = player.audioSessionId
            if (session == 0) return
            val size = Visualizer.getCaptureSizeRange()[1]
            val v = Visualizer(session)
            v.captureSize = size
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray, rate: Int) {
                }

                override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray, rate: Int) {
                    applyFft(fft, rate)
                }
            }, Visualizer.getMaxCaptureRate(), false, true)
            v.enabled = true
            mVisualizer = v
            mUsingVisualizer = true
            LogWriter.log(TAG, "Visualizer 已绑定 session=$session size=$size")
        } catch (t: Throwable) {
            mUsingVisualizer = false
            mVisualizer = null
            LogWriter.log(TAG, "Visualizer 不可用, 回退模拟律动: $t")
        }
    }

    private fun detachVisualizer() {
        if (mVisualizer != null) {
            try {
                mVisualizer!!.enabled = false
            } catch (ignored: Throwable) {
            }
            try {
                mVisualizer!!.release()
            } catch (ignored: Throwable) {
            }
            mVisualizer = null
        }
        mUsingVisualizer = false
    }

    /**
     * 将 Visualizer FFT 帧映射到 22 根柱的目标高度。
     *
     * 每个频段维护各自的长期峰值（快起慢落）并独立归一，因此大声时对应柱会明显冲高；
     * 再用整体响度做动态门限，安静时整体压低。
     */
    private fun applyFft(fft: ByteArray?, samplingRate: Int) {
        if (fft == null || fft.size < 4) return
        mLastFftMs = SystemClock.uptimeMillis()
        val capture = fft.size
        val bins = capture / 2
        val sr = if (samplingRate > 0) samplingRate else 44100

        val mags = FloatArray(BAR_COUNT)
        for (b in 0 until BAR_COUNT) {
            val ks = bandFreq(b, capture, sr, bins)
            val ke = Math.max(ks + 1, bandFreq(b + 1, capture, sr, bins))
            var sum = 0f
            var n = 0
            for (k in ks until ke) {
                if (k >= bins) break
                val re = fft[2 * k].toFloat()
                val im = fft[2 * k + 1].toFloat()
                sum += Math.sqrt((re * re + im * im).toDouble()).toFloat()
                n++
            }
            mags[b] = if (n > 0) sum / n else 0f
        }
        updateTargets(mags)
    }

    /**
     * 把 22 个频段能量映射为柱目标高度：每柱独立峰值归一（快起慢落）+ 整体响度门限。
     * Visualizer FFT 与本地 PCM FFT 共用此逻辑，保证两条链路观感一致。
     */
    private fun updateTargets(mags: FloatArray) {
        var frameSum = 0f
        for (i in 0 until BAR_COUNT) frameSum += mags[i]
        val frameMean = frameSum / BAR_COUNT

        for (i in 0 until BAR_COUNT) {
            val band = mPerm[i]
            val mag = mags[band]
            // 每根柱独立峰值：瞬时上去，慢慢回落，保证大声时明显冲高
            val peak = mBandPeak[band].let { Math.max(mag, it * 0.9975f) }.also { mBandPeak[band] = it }
            val denom = Math.max(peak, MAG_FLOOR)
            var v: Float
            if (mag < SILENCE_FLOOR) {
                v = 0f
            } else {
                v = mag / denom
                // gamma<1 扩张中低幅度，小声音也有明显起伏
                v = Math.pow(v.toDouble(), 0.78).toFloat() * 1.05f
            }
            if (v < 0f) v = 0f
            else if (v > 1f) v = 1f
            mTarget[i] = v
        }

        // 整体响度门限：长期均值慢释放，安静段落整体压低
        mRef = Math.max(frameMean, mRef * 0.9993f)
        if (mRef < MAG_FLOOR) mRef = MAG_FLOOR
        var loud = frameMean / mRef
        if (loud < 0f) loud = 0f
        else if (loud > 1f) loud = 1f
        val gate = 0.55f + 0.45f * loud
        for (i in 0 until BAR_COUNT) {
            mTarget[i] *= gate
        }
    }

    private fun bandFreq(band: Int, capture: Int, samplingRate: Int, bins: Int): Int {
        return bandFreq(band, capture, samplingRate, bins, 16000.0)
    }

    private fun bandFreq(band: Int, capture: Int, samplingRate: Int, bins: Int, fMax: Double): Int {
        val fMin = 60.0
        // 不超过 Nyquist 的 98%, 避免采样率受限时高频段全部挤到最后一个 bin
        var fMaxV = fMax
        val nyq = samplingRate / 2.0 * 0.98
        if (fMaxV > nyq) fMaxV = nyq
        val f = fMin * Math.pow(fMaxV / fMin, band / BAR_COUNT.toDouble())
        val k = (f * capture / samplingRate).toInt()
        return Math.max(1, Math.min(bins - 1, k))
    }

    /** v1139: 设置精确模式 PCM 采样率（默认 24kHz）。 */
    fun setPcmSampleRate(rate: Int) {
        if (rate > 0) mPcmRate = rate
    }

    /** 设置柱组（旋律柱）占视图宽度的比例，取值 (0, 1]，默认 80%。 */
    fun setSpanRatio(ratio: Float) {
        if (ratio > 0f && ratio <= 1f) {
            mSpanRatio = ratio
            invalidate()
        }
    }

    /**
     * v1139: 精确模式数据入口 —— 播放线程把 16bit LE mono PCM 块推入，
     * 内部对最近 PCM_FFT 个样本做 Hann 窗 FFT 并更新 22 柱目标高度，
     * 从而让柱体精准跟随音乐的低/中/高频与节奏。由播放线程调用，内部加锁。
     */
    fun pushPcm(pcm: ByteArray?, offset: Int, len: Int) {
        if (pcm == null || len < 2) return
        if (mAnim == null || !mAnim!!.isStarted) return
        synchronized(mPcmLock) {
            val end = Math.min(pcm.size, offset + len)
            var i = offset
            while (i + 1 < end) {
                val lo = pcm[i].toInt() and 0xFF
                val hi = pcm[i + 1].toInt()
                mPcmRing[mPcmFill] = ((hi shl 8) or lo).toShort().toFloat()
                mPcmFill = (mPcmFill + 1) % PCM_FFT
                i += 2
            }
            analyzePcmLocked()
        }
    }

    private fun analyzePcmLocked() {
        val re = FloatArray(PCM_FFT)
        val im = FloatArray(PCM_FFT)
        for (i in 0 until PCM_FFT) {
            val s = mPcmRing[(mPcmFill + i) % PCM_FFT] / 32768f
            re[i] = s * (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (PCM_FFT - 1))).toFloat()
        }
        fft(re, im, PCM_FFT)
        val bins = PCM_FFT / 2
        val mags = FloatArray(BAR_COUNT)
        for (b in 0 until BAR_COUNT) {
            val ks = bandFreq(b, PCM_FFT, mPcmRate, bins, 16000.0)
            val ke = Math.max(ks + 1, bandFreq(b + 1, PCM_FFT, mPcmRate, bins, 16000.0))
            var sum = 0f
            var n = 0
            for (k in ks until ke) {
                if (k >= bins) break
                sum += Math.sqrt((re[k] * re[k] + im[k] * im[k]).toDouble()).toFloat()
                n++
            }
            mags[b] = if (n > 0) sum / n else 0f
        }
        updateTargets(mags)
    }

    /** 原地 radix-2 复数 FFT（n 为 2 的幂）。 */
    private fun fft(re: FloatArray, im: FloatArray, n: Int) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val ang = -2.0 * Math.PI / len
            val wr = Math.cos(ang).toFloat()
            val wi = Math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cwr = 1f
                var cwi = 0f
                for (k in 0 until half) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + half] * cwr - im[i + k + half] * cwi
                    val vi = re[i + k + half] * cwi + im[i + k + half] * cwr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + half] = ur - vr
                    im[i + k + half] = ui - vi
                    val nwr = cwr * wr - cwi * wi
                    cwi = cwr * wi + cwi * wr
                    cwr = nwr
                }
                i += len
            }
            len = len shl 1
        }
    }

    // ==================== 绘制 ====================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        mPaint.shader = null
    }

    private fun ensureGradient(w: Int) {
        mPaint.shader = null
        mPaint.color = AppColors.primary()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val d = resources.displayMetrics.density
        val gap = 3f * d
        // 旋律柱占视图宽度指定比例并居中
        val span = w * mSpanRatio
        val spanLeft = (w - span) / 2f
        val barW = (span - gap * (BAR_COUNT - 1)) / BAR_COUNT
        if (barW <= 0f) return
        val radius = Math.min(barW, 4f * d)

        // 纯色填充：跟随模块主题主色
        ensureGradient(w)

        for (i in 0 until BAR_COUNT) {
            var v = mCurrent[i]
            if (v < 0f) v = 0f
            else if (v > 1f) v = 1f
            val bh = h * (0.06f + 0.94f * v)
            val left = spanLeft + i * (barW + gap)
            mRect.set(left, h - bh, left + barW, h.toFloat())
            canvas.drawRoundRect(mRect, radius, radius, mPaint)
        }
    }

    companion object {
        private const val TAG = "LeShaoV3.EqBars"
        private const val BAR_COUNT = 22
        private const val FALLBACK_INTERVAL_MS = 80L
        private const val MAG_FLOOR = 6f
        private const val SILENCE_FLOOR = 2f
        /** v30018: 柱组（旋律柱）默认占视图宽度 80%，居中，左右各留 10%。 */
        private const val SPAN_RATIO = 0.80f
        /** v1139: 精确模式 —— 本地 FFT 窗口大小 */
        private const val PCM_FFT = 1024
    }
}
