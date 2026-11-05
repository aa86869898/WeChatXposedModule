package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.VoiceDetailHook;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;

import de.robv.android.xposed.XposedHelpers;

/**
 * 语音消息详情面板（v3.0.516 新增，风格与模块设置弹窗统一）。
 *
 * <p>由长按菜单「语音详情」触发，在微信聊天页之上弹出悬浮面板，显示：</p>
 * <ul>
 *   <li>发送者 / 消息时间 / 总时长</li>
 *   <li>播放进度条（可拖动 seek 到任意位置）</li>
 *   <li>播放/暂停、快退 15s、快进 15s</li>
 * </ul>
 *
 * <p>播放链路：解析 e9 消息的 clientmsgid → 在 voice2 目录按 MD5 定位 amr 文件
 * → 检测 AMR/SILK 格式（兼容微信前导 flag 字节）→ SILK 解码为 WAV → MediaPlayer 播放。</p>
 */
public final class VoiceDetailDialog {

    private static final String TAG = "VoiceDetailUI";

    private static final Object sPendingLock = new Object();
    private static volatile Object sPendingE9;
    private static volatile Object sPendingTag;
    private static volatile View sPendingView;

    private static volatile AlertDialog sDialog;
    private static volatile MediaPlayer sPlayer;
    private static volatile Handler sUiHandler;
    private static volatile String sPlayingPath;
    private static volatile boolean sUserSeeking;
    /** 会话序号：每次 show/dismiss 递增，使过期准备线程失效。 */
    private static volatile int sSession;
    /** 是否正在后台准备播放器（防止重复点击重复加载）。 */
    private static volatile boolean sPreparing;

    /** v3.0.517：前进/后退步长 2 秒。 */
    private static final int SKIP_MS = 2000;

    private VoiceDetailDialog() {
    }

    // ==================== 待显示消息 ====================

    public static void setPendingMessage(Object e9, Object tag) {
        synchronized (sPendingLock) {
            sPendingE9 = e9;
            sPendingTag = tag;
        }
    }

    /** 由 VoiceDetailHook 点击拦截调用。 */
    public static void showFromHook() {
        final Object e9;
        final Object tag;
        synchronized (sPendingLock) {
            e9 = sPendingE9;
            tag = sPendingTag;
        }
        if (e9 == null) {
            toast("未捕获到语音消息");
            return;
        }
        // 宿主 Activity：优先用 pending View 的 context
        Activity host = null;
        View v = sPendingView;
        if (v != null && v.getContext() instanceof Activity) {
            host = (Activity) v.getContext();
        }
        if (host == null || host.isFinishing()) {
            host = com.leshao.v3.MainHook.currentActivity();
        }
        final Activity act = host;
        if (act == null || act.isFinishing()) {
            toast("未找到可用窗口，请重试");
            return;
        }
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    show(act, e9);
                } catch (Throwable t) {
                    LogWriter.log(TAG, "show err: " + t.getMessage());
                }
            }
        });
    }

    public static void setAnchorView(View v) {
        sPendingView = v;
    }

    // ==================== 面板 ====================

    public static void show(final Activity act, final Object e9) {
        dismiss();
        if (sUiHandler == null) sUiHandler = new Handler(Looper.getMainLooper());
        sSession++; // 使上一会话未完成的准备线程失效
        sPreparing = false;

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 20), dp(act, 16), dp(act, 20), dp(act, 14));
        root.setBackground(CandyUi.dialogBg(act));

        // ===== 标题（居中） =====
        TextView title = new TextView(act);
        title.setText("语音消息详情");
        title.setTextSize(17);
        title.setTextColor(AppColors.text1());
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(act, 12));
        root.addView(title);

        // ===== 时长一行：开始 / 当前 / 结束 平均摊开 =====
        LinearLayout timeRow = new LinearLayout(act);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        timeRow.setPadding(0, dp(act, 4), 0, dp(act, 2));

        final TextView tvStart = new TextView(act);
        tvStart.setText("开始 00:00:00");
        tvStart.setTextSize(12);
        tvStart.setTextColor(AppColors.text2());
        tvStart.setGravity(Gravity.CENTER);
        tvStart.setSingleLine(true);
        timeRow.addView(tvStart, new LinearLayout.LayoutParams(0, -2, 1f));

        final TextView tvCur = new TextView(act);
        tvCur.setText("当前 00:00:00");
        tvCur.setTextSize(12);
        tvCur.setTextColor(AppColors.accent());
        tvCur.setTypeface(null, Typeface.BOLD);
        tvCur.setGravity(Gravity.CENTER);
        tvCur.setSingleLine(true);
        timeRow.addView(tvCur, new LinearLayout.LayoutParams(0, -2, 1f));

        final TextView tvEnd = new TextView(act);
        tvEnd.setText("结束 --:--:--");
        tvEnd.setTextSize(12);
        tvEnd.setTextColor(AppColors.text2());
        tvEnd.setGravity(Gravity.CENTER);
        tvEnd.setSingleLine(true);
        timeRow.addView(tvEnd, new LinearLayout.LayoutParams(0, -2, 1f));

        root.addView(timeRow);

        // ===== 进度条：加粗轨道（已播黄/未播浅黄）+ 小鸟精确跟随进度 =====
        final int birdW = dp(act, 42);
        final int birdH = dp(act, 30);
        final int halfThumb = birdW / 2; // 轨道两侧留出小鸟半宽，进度 0/100 时小鸟中心对齐轨道端点

        final SeekBar seek = new SeekBar(act);
        seek.setMax(10000); // 精度：0.01% 步进，配合 120ms 刷新
        seek.setProgress(0);
        try {
            int barH = dp(act, 8); // 加粗轨道
            android.graphics.drawable.GradientDrawable track = new android.graphics.drawable.GradientDrawable();
            track.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            track.setCornerRadius(barH / 2f);
            track.setColor(0x66F5B301); // 未播放：浅黄色（半透明 accent）
            android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
            fill.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            fill.setCornerRadius(barH / 2f);
            fill.setColor(AppColors.accent()); // 已播放：黄色
            ClipDrawable clip = new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL);
            LayerDrawable layer = new LayerDrawable(new Drawable[]{track, clip});
            layer.setLayerInset(0, halfThumb, 0, halfThumb, 0);
            layer.setLayerInset(1, halfThumb, 0, halfThumb, 0);
            seek.setProgressDrawable(layer);
            // 系统 thumb 透明化：进度指示完全交给上层小鸟 ImageView，避免系统滑块不跟随/错位
            seek.setThumb(emptyThumb());
        } catch (Throwable ignored) {
        }

        // 容器：SeekBar 负责触摸拖动，小鸟 ImageView 叠加在上层精确跟随进度
        FrameLayout progressWrap = new FrameLayout(act);
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(-1, dp(act, 44));
        wrapLp.topMargin = dp(act, 4);
        wrapLp.bottomMargin = dp(act, 2);
        progressWrap.addView(seek, new FrameLayout.LayoutParams(-1, dp(act, 44)));

        final ImageView bird = new ImageView(act);
        bird.setImageDrawable(new BirdThumbDrawable(act));
        bird.setScaleType(ImageView.ScaleType.FIT_CENTER);
        bird.setLayoutParams(new FrameLayout.LayoutParams(birdW, birdH, Gravity.CENTER_VERTICAL));
        progressWrap.addView(bird);
        sBirdView = bird;
        root.addView(progressWrap, wrapLp);
        // 布局完成后立即把小鸟定位到当前进度（0%）
        seek.post(new Runnable() {
            @Override
            public void run() {
                updateBirdPosition(seek, seek.getProgress());
            }
        });

        // ===== -2 / 播放按钮（缩小） / +2 =====
        LinearLayout skipRow = new LinearLayout(act);
        skipRow.setOrientation(LinearLayout.HORIZONTAL);
        skipRow.setGravity(Gravity.CENTER_VERTICAL);
        skipRow.setPadding(dp(act, 16), dp(act, 6), dp(act, 16), dp(act, 2));

        TextView btnBack = skipButton(act, "−2秒");
        btnBack.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        skipRow.addView(btnBack);

        final ImageView playIcon = createPlayButton(act);
        sPlayIconView = playIcon;
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(dp(act, 36), dp(act, 36));
        playLp.leftMargin = dp(act, 20);
        playLp.rightMargin = dp(act, 20);
        skipRow.addView(playIcon, playLp);

        TextView btnFwd = skipButton(act, "＋2秒");
        btnFwd.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        skipRow.addView(btnFwd);

        root.addView(skipRow);

        // ===== 底部：长形圆角黄色背景关闭按钮（居中） =====
        TextView close = new TextView(act);
        close.setText("关闭");
        close.setTextSize(15);
        close.setTypeface(null, Typeface.BOLD);
        close.setTextColor(0xFF000000); // 黑色字（He 指定）
        close.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable closeBg = new android.graphics.drawable.GradientDrawable();
        closeBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        closeBg.setCornerRadius(dp(act, 22));
        closeBg.setColor(AppColors.accent());
        close.setBackground(closeBg);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(-1, dp(act, 44));
        closeLp.topMargin = dp(act, 14);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        root.addView(close, closeLp);

        final AlertDialog dialog = new AlertDialog.Builder(act)
                .setView(root)
                .create();
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(dd -> dismiss());
        sDialog = dialog;
        com.leshao.v3.ui.UiBackStack.push(dialog, null);
        dialog.show();

        // ===== 加载语音文件（后台线程解析路径 + 解码） =====
        final String path = findVoiceFile(e9);
        LogWriter.log(TAG, "voice path=" + path);
        if (path == null || !new File(path).exists()) {
            tvEnd.setText("结束 文件不可用");
            toast("未找到语音文件（可能已过期清理）");
            return;
        }

        final int[] durHolder = new int[1];
        sPlayingPath = path;

        // 播放按钮：播放/暂停切换；播放完成释放后可再次点击重新加载播放
        playIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sPreparing) return;
                MediaPlayer mp = sPlayer;
                if (mp != null && mp.isPlaying()) {
                    pausePlayback(playIcon);
                } else if (mp != null) {
                    startPlayback(playIcon, tvCur, seek, durHolder);
                } else {
                    // 播放完成已释放：重新加载并自动播放
                    prepareAndPlay(act, playIcon, tvStart, tvCur, tvEnd, seek, durHolder);
                }
            }
        });
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                seekBy(playIcon, tvCur, seek, durHolder, -SKIP_MS);
            }
        });
        btnFwd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                seekBy(playIcon, tvCur, seek, durHolder, SKIP_MS);
            }
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                updateBirdPosition(bar, progress); // 拖动时小鸟实时跟随手指
                if (sPlayer == null) return;
                int dur = durHolder[0];
                if (dur <= 0) return;
                int pos = (int) ((long) progress * dur / 10000);
                tvCur.setText("当前 " + fmtHms(pos));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                sUserSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                sUserSeeking = false;
                updateBirdPosition(bar, bar.getProgress());
                MediaPlayer mp = sPlayer;
                if (mp == null) return;
                int dur = durHolder[0];
                if (dur <= 0) return;
                int pos = (int) ((long) bar.getProgress() * dur / 10000);
                try {
                    mp.seekTo(pos);
                    tvCur.setText("当前 " + fmtHms(pos));
                } catch (Throwable ignored) {
                }
            }
        });

        // 首次自动加载并播放
        prepareAndPlay(act, playIcon, tvStart, tvCur, tvEnd, seek, durHolder);
    }

    // ==================== 播放控制 ====================

    /** 黄色圆形播放按钮：accent 圆底 + 白色细描边 + 圆角三角（FIT_CENTER 保证任意密度完美居中）。 */
    private static ImageView createPlayButton(Context ctx) {
        ImageView iv = new ImageView(ctx);
        try {
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            gd.setColor(AppColors.accent());
            gd.setStroke(dp(ctx, 1), 0x80FFFFFF);
            iv.setBackground(gd);
        } catch (Throwable ignored) {
        }
        iv.setImageDrawable(playTriangle(0xFFFFFFFF));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setPadding(dp(ctx, 7), dp(ctx, 7), dp(ctx, 7), dp(ctx, 7)); // 36dp 小按钮适配
        return iv;
    }

    /** 白色播放三角（100x100 坐标系，圆角顶点更精致）。 */
    private static android.graphics.drawable.Drawable playTriangle(int color) {
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(36f, 10f);
        p.quadTo(26f, 16f, 26f, 26f);
        p.lineTo(26f, 74f);
        p.quadTo(26f, 84f, 36f, 90f);
        p.lineTo(84f, 56f);
        p.quadTo(92f, 50f, 84f, 44f);
        p.close();
        return new VoiceIconDrawable(p, color);
    }

    /** 暂停双竖条（圆角矩形，更柔和）。 */
    private static android.graphics.drawable.Drawable pauseBars(int color) {
        android.graphics.Path p = new android.graphics.Path();
        android.graphics.RectF r1 = new android.graphics.RectF(22f, 14f, 44f, 86f);
        android.graphics.RectF r2 = new android.graphics.RectF(56f, 14f, 78f, 86f);
        p.addRoundRect(r1, 8f, 8f, android.graphics.Path.Direction.CW);
        p.addRoundRect(r2, 8f, 8f, android.graphics.Path.Direction.CW);
        return new VoiceIconDrawable(p, color);
    }

    /** 通用矢量图标 Drawable：带 intrinsic 尺寸，保证 ImageView FIT_CENTER 正确缩放居中。 */
    private static final class VoiceIconDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Path mPath;
        private final android.graphics.Paint mPaint;

        VoiceIconDrawable(android.graphics.Path path, int color) {
            mPath = path;
            mPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            mPaint.setColor(color);
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            canvas.drawPath(mPath, mPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            mPaint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return 100;
        }

        @Override
        public int getIntrinsicHeight() {
            return 100;
        }
    }

    /**
     * 进度条小鸟滑块：复用模块 FlyingProgressBar 的小鸟造型（局部坐标 96x72），
     * 水平朝右 + 白色细描边，保证在黄色进度条上清晰可见。
     */
    private static final class BirdThumbDrawable extends Drawable {
        private final Paint mFill;
        private final Paint mFarFill;
        private final Paint mBeak;
        private final Paint mFeet;
        private final Paint mEye;
        private final Paint mEyeHi;
        private final Paint mOutline;
        private final Path mFarWing;
        private final Path mTail;
        private final Path mBody;
        private final Path mNearWing;
        private final Path mHead;
        private final Path mBeakPath;
        private final Path mFeetPath;
        private final Path mEyePath;
        private final Path mEyeHiPath;
        private final Matrix mXform;
        private final Path mTmp;
        private final int mIntrinsicW;
        private final int mIntrinsicH;

        BirdThumbDrawable(Context ctx) {
            float d = ctx.getResources().getDisplayMetrics().density;
            mFill = paint(AppColors.primary());
            mFarFill = paint(AppColors.primary());
            mFarFill.setAlpha(184);
            mBeak = paint(AppColors.warning());
            mFeet = new Paint(Paint.ANTI_ALIAS_FLAG);
            mFeet.setStyle(Paint.Style.STROKE);
            mFeet.setStrokeCap(Paint.Cap.ROUND);
            mFeet.setStrokeJoin(Paint.Join.ROUND);
            mFeet.setStrokeWidth(2.4f * d);
            mFeet.setColor(AppColors.primary());
            mEye = paint(AppColors.onSurface());
            mEyeHi = paint(AppColors.whiteTextOnAccent());
            mOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
            mOutline.setStyle(Paint.Style.STROKE);
            mOutline.setStrokeWidth(1.5f * d);
            mOutline.setStrokeJoin(Paint.Join.ROUND);
            mOutline.setColor(0xFFFFFFFF);

            // 小鸟部件路径（与 FlyingProgressBar.buildBird 完全一致）
            mFarWing = new Path();
            mFarWing.moveTo(44f, 37f);
            mFarWing.cubicTo(38f, 19f, 25f, 8f, 8f, 12f);
            mFarWing.cubicTo(20f, 22f, 33f, 28f, 38f, 43f);
            mFarWing.close();
            mTail = new Path();
            mTail.moveTo(22f, 42f);
            mTail.lineTo(2f, 32f);
            mTail.lineTo(16f, 41f);
            mTail.lineTo(0f, 50f);
            mTail.lineTo(22f, 46f);
            mTail.close();
            mBody = new Path();
            mBody.moveTo(20f, 42f);
            mBody.cubicTo(30f, 31f, 56f, 29f, 68f, 35f);
            mBody.cubicTo(75f, 38f, 75f, 44f, 66f, 47f);
            mBody.cubicTo(52f, 51f, 30f, 51f, 20f, 42f);
            mBody.close();
            mNearWing = new Path();
            mNearWing.moveTo(46f, 39f);
            mNearWing.cubicTo(49f, 16f, 63f, 4f, 82f, 6f);
            mNearWing.cubicTo(70f, 16f, 57f, 27f, 53f, 43f);
            mNearWing.close();
            mHead = new Path();
            mHead.addOval(new RectF(60f, 23f, 80f, 43f), Path.Direction.CW);
            mBeakPath = new Path();
            mBeakPath.moveTo(78f, 30f);
            mBeakPath.lineTo(93f, 34.5f);
            mBeakPath.lineTo(78f, 39f);
            mBeakPath.close();
            mFeetPath = new Path();
            mFeetPath.moveTo(40f, 46f);
            mFeetPath.cubicTo(35f, 50f, 29f, 51f, 24f, 50f);
            mFeetPath.moveTo(24f, 50f); mFeetPath.lineTo(19f, 51f);
            mFeetPath.moveTo(24f, 50f); mFeetPath.lineTo(23f, 53f);
            mFeetPath.moveTo(45f, 48f);
            mFeetPath.cubicTo(40f, 52f, 34f, 54f, 28f, 53f);
            mFeetPath.moveTo(28f, 53f); mFeetPath.lineTo(23f, 54f);
            mFeetPath.moveTo(28f, 53f); mFeetPath.lineTo(27f, 56f);
            mEyePath = new Path();
            mEyePath.addCircle(72f, 31f, 2f, Path.Direction.CW);
            mEyeHiPath = new Path();
            mEyeHiPath.addCircle(72.7f, 30.2f, 0.7f, Path.Direction.CW);

            mXform = new Matrix();
            mTmp = new Path();
            mIntrinsicW = Math.round(42 * d);
            mIntrinsicH = Math.round(30 * d);
        }

        private static Paint paint(int color) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            return p;
        }

        @Override
        public int getIntrinsicWidth() {
            return mIntrinsicW;
        }

        @Override
        public int getIntrinsicHeight() {
            return mIntrinsicH;
        }

        @Override
        public void draw(Canvas canvas) {
            int w = getBounds().width();
            int h = getBounds().height();
            if (w <= 0 || h <= 0) return;
            float scale = w / 96f;
            float cyOffset = (h - 56f * scale) / 2f;
            mXform.reset();
            mXform.postScale(scale, scale);
            mXform.postTranslate(0f, cyOffset);

            // 远翼（半透明）最底层
            canvas.drawPath(xform(mFarWing), mFarFill);
            drawOutlined(canvas, mTail, mFill);
            drawOutlined(canvas, mBody, mFill);
            canvas.drawPath(xform(mFeetPath), mFeet);
            drawOutlined(canvas, mNearWing, mFill);
            drawOutlined(canvas, mHead, mFill);
            canvas.drawPath(xform(mBeakPath), mBeak);
            canvas.drawPath(xform(mEyePath), mEye);
            canvas.drawPath(xform(mEyeHiPath), mEyeHi);
        }

        private Path xform(Path src) {
            mTmp.set(src);
            mTmp.transform(mXform);
            return mTmp;
        }

        /** 先描白色轮廓再填充主色，保证在黄色进度条上清晰。 */
        private void drawOutlined(Canvas canvas, Path src, Paint fill) {
            Path p = xform(src);
            canvas.drawPath(p, mOutline);
            canvas.drawPath(p, fill);
        }

        @Override
        public void setAlpha(int alpha) {
            mFill.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            mFill.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** 异步准备播放器，完成后自动开始播放。播放完成释放后可再次调用（二次点击播放）。 */
    private static void prepareAndPlay(final Context ctx, final ImageView btnPlay,
                                       final TextView tvStart, final TextView tvCur,
                                       final TextView tvEnd, final SeekBar seek,
                                       final int[] durHolder) {
        if (sPreparing) return;
        final String path = sPlayingPath;
        if (path == null || !new File(path).exists()) {
            toast("语音文件不存在");
            return;
        }
        sPreparing = true;
        final int session = sSession;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final MediaPlayer mp = preparePlayer(path);
                sPreparing = false;
                if (mp == null) {
                    if (session == sSession && sUiHandler != null) {
                        sUiHandler.post(() -> {
                            tvCur.setText("当前 --:--:--");
                            toast("语音解码/播放失败");
                        });
                    }
                    return;
                }
                if (session != sSession) {
                    try {
                        mp.release();
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                final int dur = mp.getDuration();
                durHolder[0] = dur;
                if (sUiHandler == null) return;
                sUiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (session != sSession) {
                            try {
                                mp.release();
                            } catch (Throwable ignored) {
                            }
                            return;
                        }
                        sPlayer = mp;
                        tvStart.setText("开始 00:00:00");
                        tvCur.setText("当前 00:00:00");
                        tvEnd.setText("结束 " + fmtHms(dur));
                        seek.setProgress(0);
                        updateBirdPosition(seek, 0);
                        startPlayback(btnPlay, tvCur, seek, durHolder);
                    }
                });
            }
        }, "ls-voicedetail-prepare").start();
    }

    private static void startPlayback(final ImageView btnPlay, final TextView tvCur,
                                      final SeekBar seek, final int[] durHolder) {
        MediaPlayer mp = sPlayer;
        if (mp == null) return;
        try {
            mp.start();
            try {
                btnPlay.setImageDrawable(pauseBars(0xFFFFFFFF));
            } catch (Throwable ignored) {
            }
            updateProgressRunnable(tvCur, seek, durHolder);
        } catch (Throwable t) {
            LogWriter.log(TAG, "start err: " + t.getMessage());
        }
    }

    private static void pausePlayback(ImageView btnPlay) {
        MediaPlayer mp = sPlayer;
        if (mp == null) return;
        try {
            mp.pause();
            try {
                btnPlay.setImageDrawable(playTriangle(0xFFFFFFFF));
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    /** 播放结束后复位按钮图标。 */
    private static void resetPlayIcon(ImageView btnPlay) {
        if (btnPlay == null) return;
        try {
            btnPlay.setImageDrawable(playTriangle(0xFFFFFFFF));
        } catch (Throwable ignored) {
        }
    }

    private static void seekBy(ImageView btnPlay, TextView tvCur, SeekBar seek,
                               int[] durHolder, int deltaMs) {
        MediaPlayer mp = sPlayer;
        if (mp == null) return;
        int dur = durHolder[0];
        if (dur <= 0) return;
        try {
            int cur = mp.getCurrentPosition();
            int target = Math.max(0, Math.min(dur - 50, cur + deltaMs));
            mp.seekTo(target);
            tvCur.setText("当前 " + fmtHms(target));
            seek.setProgress((int) ((long) target * 10000 / dur));
            updateBirdPosition(seek, seek.getProgress());
            if (!mp.isPlaying()) {
                startPlayback(btnPlay, tvCur, seek, durHolder);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void updateProgressRunnable(final TextView tvCur,
                                               final SeekBar seek, final int[] durHolder) {
        if (sUiHandler == null) return;
        sUiHandler.removeCallbacksAndMessages(null);
        sUiHandler.post(new Runnable() {
            @Override
            public void run() {
                MediaPlayer mp = sPlayer;
                if (mp == null) return;
                try {
                    if (mp.isPlaying() && !sUserSeeking) {
                        int dur = durHolder[0];
                        int pos = mp.getCurrentPosition();
                        tvCur.setText("当前 " + fmtHms(pos));
                        if (dur > 0) {
                            seek.setProgress((int) ((long) pos * 10000 / dur));
                            updateBirdPosition(seek, seek.getProgress());
                        }
                    }
                } catch (Throwable ignored) {
                }
                sUiHandler.postDelayed(this, 120L); // 120ms 高频刷新
            }
        });
    }

    // ==================== 播放器准备 ====================

    private static MediaPlayer preparePlayer(final String path) {
        try {
            File f = new File(path);
            if (!f.exists() || f.length() == 0) return null;

            byte[] hdr = new byte[18];
            int hn = 0;
            try {
                InputStream in = new FileInputStream(f);
                hn = in.read(hdr);
                in.close();
            } catch (Throwable ignored) {
            }

            int skip = 0;
            boolean isAmr = false;
            boolean isSilk = false;
            if (startsWith(hdr, hn, 0, "#!AMR")) { isAmr = true; skip = 0; }
            else if (startsWith(hdr, hn, 1, "#!AMR")) { isAmr = true; skip = 1; }
            else if (startsWith(hdr, hn, 0, "#!SILK_V3")) { isSilk = true; skip = 0; }
            else if (startsWith(hdr, hn, 1, "#!SILK_V3")) { isSilk = true; skip = 1; }

            if (!isAmr && !isSilk) {
                LogWriter.log(TAG, "unknown voice format header=" + hex(hdr, hn));
                return null;
            }

            String src = path;
            String norm = null;
            if (skip > 0) {
                norm = path + ".vd.norm";
                File nf = new File(norm);
                if (nf.exists()) nf.delete();
                if (!copySkipBytes(f, nf, skip)) return null;
                src = norm;
            }

            String wav = null;
            if (isSilk) {
                wav = path + ".vd.wav";
                File wf = new File(wav);
                if (wf.exists()) wf.delete();
                boolean ok;
                try {
                    ok = xyz.xxin.silkdecoder.SilkDecoder.decodeToWav(src, wav);
                } catch (Throwable t) {
                    ok = false;
                }
                if (!ok || !new File(wav).exists() || new File(wav).length() == 0) {
                    if (norm != null) new File(norm).delete();
                    return null;
                }
                src = wav;
            }

            final MediaPlayer mp = new MediaPlayer();
            final String cleanupWav = wav;
            final String cleanupNorm = norm;
            try {
                mp.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
            } catch (Throwable ignored) {
            }
            mp.setDataSource(src);
            mp.prepare();
            mp.setOnCompletionListener(m -> {
                try {
                    if (sPlayer == m) sPlayer = null;
                    resetPlayIcon(sPlayIconRef());
                    m.release();
                } catch (Throwable ignored) {
                }
                if (cleanupWav != null) new File(cleanupWav).delete();
                if (cleanupNorm != null) new File(cleanupNorm).delete();
            });
            mp.setOnErrorListener((m, what, extra) -> {
                try {
                    if (sPlayer == m) sPlayer = null;
                    resetPlayIcon(sPlayIconRef());
                    m.release();
                } catch (Throwable ignored) {
                }
                if (cleanupWav != null) new File(cleanupWav).delete();
                if (cleanupNorm != null) new File(cleanupNorm).delete();
                return true;
            });
            LogWriter.log(TAG, "player ready: " + path + " dur=" + mp.getDuration());
            return mp;
        } catch (Throwable t) {
            LogWriter.log(TAG, "prepare err: " + t.getMessage());
            return null;
        }
    }

    // ==================== 文件解析（voice2 定位） ====================

    private static String findVoiceFile(Object e9) {
        String cid = extractClientMsgId(e9);
        if (cid == null || cid.isEmpty()) return null;
        LogWriter.log(TAG, "cid=" + cid);

        String dir = findVoice2Dir();
        if (dir == null) return null;

        String md5 = md5(cid);
        if (md5.length() >= 4) {
            String path = dir + "/" + md5.substring(0, 2) + "/" + md5.substring(2, 4)
                    + "/msg_" + cid + ".amr";
            if (new File(path).exists()) return path;
            // 分组文件 msg_<cid>_<n>.amr
            File group = new File(dir + "/" + md5.substring(0, 2) + "/" + md5.substring(2, 4));
            File[] files = group.listFiles();
            if (files != null) {
                String prefix = "msg_" + cid + "_";
                for (File f : files) {
                    if (f.getName().startsWith(prefix) && f.getName().endsWith(".amr")) {
                        return f.getAbsolutePath();
                    }
                }
            }
        }
        // 递归兜底
        return searchVoice2(dir, cid);
    }

    private static String findVoice2Dir() {
        try {
            int currentUser = android.os.Process.myUid() / 100000;
            String root = "/data/user/" + currentUser + "/com.tencent.mm/MicroMsg";
            File md = new File(root);
            File[] dirs = md.listFiles();
            if (dirs != null) {
                for (File dir : dirs) {
                    if (dir.isDirectory() && dir.getName().length() >= 32) {
                        File v2 = new File(dir, "voice2");
                        if (v2.exists() && v2.isDirectory()) return v2.getAbsolutePath();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String searchVoice2(String dir, String cid) {
        File base = new File(dir);
        File[] subs = base.listFiles();
        if (subs == null) return null;
        for (File sub : subs) {
            if (!sub.isDirectory()) continue;
            File[] files = sub.listFiles();
            if (files == null) continue;
            for (File f : files) {
                String n = f.getName();
                if (n.equals("msg_" + cid + ".amr") || n.startsWith("msg_" + cid + "_")) {
                    return f.getAbsolutePath();
                }
            }
            // 两层：xx/yy
            File[] subs2 = sub.listFiles();
            if (subs2 == null) continue;
            for (File sub2 : subs2) {
                if (!sub2.isDirectory()) continue;
                File[] fs2 = sub2.listFiles();
                if (fs2 == null) continue;
                for (File f : fs2) {
                    String n = f.getName();
                    if (n.equals("msg_" + cid + ".amr") || n.startsWith("msg_" + cid + "_")) {
                        return f.getAbsolutePath();
                    }
                }
            }
        }
        return null;
    }

    static String extractClientMsgId(Object e9) {
        if (e9 == null) return null;
        // 1) u0()/x0() = field_imgPath 语音文件名 msg_<cid>.amr
        try {
            String imgPath = null;
            try { imgPath = (String) XposedHelpers.callMethod(e9, "u0"); } catch (Throwable ignored) {}
            if (imgPath == null || imgPath.isEmpty()) {
                try { imgPath = (String) XposedHelpers.callMethod(e9, "x0"); } catch (Throwable ignored) {}
            }
            if (imgPath != null && !imgPath.isEmpty()) {
                String name = imgPath;
                int slash = name.lastIndexOf('/');
                if (slash >= 0) name = name.substring(slash + 1);
                if (name.startsWith("msg_")) name = name.substring(4);
                if (name.endsWith(".amr")) name = name.substring(0, name.length() - 4);
                if (!name.isEmpty() && !name.contains("/") && !name.contains(":")) return name;
            }
        } catch (Throwable ignored) {
        }
        // 2) I0() XML clientmsgid
        try {
            String xml = (String) XposedHelpers.callMethod(e9, "I0");
            if (xml != null) {
                String cid = extractXmlAttr(xml, "clientmsgid");
                if (cid != null && !cid.isEmpty() && !cid.contains(":")) return cid;
            }
        } catch (Throwable ignored) {
        }
        // 3) j() = field_content 冒号前缀
        try {
            String content = (String) XposedHelpers.callMethod(e9, "j");
            if (content != null && !content.isEmpty()) {
                int colon = content.indexOf(':');
                if (colon > 0) {
                    String cid = content.substring(0, colon).trim();
                    if (!cid.isEmpty() && !cid.contains("/")) return cid;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String extractXmlAttr(String xml, String attr) {
        if (xml == null || attr == null) return null;
        int idx = xml.indexOf(attr + "=\"");
        if (idx < 0) return null;
        int start = idx + attr.length() + 2;
        int end = xml.indexOf('"', start);
        if (end < 0) return null;
        return xml.substring(start, end);
    }

    private static String extractTalker(Object e9) {
        if (e9 == null) return "";
        try {
            String s = (String) XposedHelpers.getObjectField(e9, "field_talker");
            if (s != null && !s.isEmpty()) return s;
        } catch (Throwable ignored) {
        }
        try {
            String s = (String) XposedHelpers.getObjectField(e9, "talker");
            if (s != null && !s.isEmpty()) return s;
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String formatTime(Object e9) {
        long t = 0;
        try {
            t = XposedHelpers.getLongField(e9, "field_createTime");
        } catch (Throwable ignored) {
        }
        if (t <= 0) return "";
        try {
            return new SimpleDateFormat("MM-dd HH:mm:ss").format(new Date(t));
        } catch (Throwable e) {
            return String.valueOf(t);
        }
    }

    // ==================== 工具 ====================

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean startsWith(byte[] buf, int len, int off, String s) {
        if (off + s.length() > len) return false;
        for (int i = 0; i < s.length(); i++) {
            if ((char) (buf[off + i] & 0xFF) != s.charAt(i)) return false;
        }
        return true;
    }

    private static boolean copySkipBytes(File src, File dst, int skip) {
        try {
            InputStream in = new FileInputStream(src);
            byte[] tail = new byte[(int) (src.length() - skip)];
            long skipped = in.skip(skip);
            if (skipped < skip) {
                in.close();
                return false;
            }
            java.io.OutputStream out = new java.io.FileOutputStream(dst);
            int n;
            int total = 0;
            byte[] buf = new byte[8192];
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            in.close();
            out.close();
            return total == tail.length;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String hex(byte[] b, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && i < b.length; i++) {
            sb.append(String.format("%02x", b[i]));
        }
        return sb.toString();
    }

    /** 时长格式化：hh:mm:ss（面板统一三位段，开始/当前/结束都用）。 */
    private static String fmtHms(int ms) {
        int totalSec = Math.max(0, ms / 1000);
        return String.format("%02d:%02d:%02d", totalSec / 3600, (totalSec / 60) % 60, totalSec % 60);
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 前进/后退跳转按钮：圆角浅底、主色文字，宽高固定保证左右对称。 */
    private static TextView skipButton(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(17);
        tv.setTextColor(AppColors.accent());
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        int h = dp(ctx, 38);
        tv.setHeight(h);
        tv.setPadding(0, 0, 0, 0);
        try {
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            gd.setCornerRadius(dp(ctx, 19));
            gd.setColor(0x14FFFFFF);
            gd.setStroke(dp(ctx, 1), AppColors.glassStroke());
            tv.setBackground(gd);
        } catch (Throwable ignored) {
        }
        return tv;
    }

    /** 当前播放按钮引用，供播放结束回调复位图标。 */
    private static volatile ImageView sPlayIconView;

    /** v3.0.520：进度条上的小鸟指示器，随播放进度精确移动。 */
    private static volatile ImageView sBirdView;

    private static ImageView sPlayIconRef() {
        return sPlayIconView;
    }

    /**
     * v3.0.520：小鸟精确跟随进度。
     *
     * 轨道视觉范围 = [halfThumb, width - halfThumb]（与 LayerDrawable 的 inset 一致），
     * 小鸟中心按 progress/max 线性映射到该范围，与 SeekBar 进度一一对应，
     * 播放刷新、±2 秒跳转、手动拖动都会调用本方法同步。
     *
     * @param seek     进度条（提供宽度与进度）
     * @param progress 当前进度（0 ~ seek.getMax()）
     */
    private static void updateBirdPosition(SeekBar seek, int progress) {
        ImageView bird = sBirdView;
        if (seek == null || bird == null) return;
        int w = seek.getWidth();
        if (w <= 0) return; // 尚未布局，post 回调会再触发
        int birdW = bird.getWidth();
        if (birdW <= 0) birdW = dp(seek.getContext(), 42);
        int half = birdW / 2;
        int start = half;
        int end = w - half;
        if (end <= start) return;
        int max = seek.getMax();
        if (max <= 0) max = 10000;
        float fraction = progress / (float) max;
        int centerX = start + Math.round((end - start) * fraction);
        bird.setTranslationX(centerX - birdW / 2f);
    }

    /** 透明零尺寸 thumb：隐藏系统滑块，仅保留触摸拖动能力，进度指示完全由小鸟 ImageView 完成。 */
    private static Drawable emptyThumb() {
        return new Drawable() {
            @Override
            public void draw(Canvas c) {
            }

            @Override
            public void setAlpha(int alpha) {
            }

            @Override
            public void setColorFilter(ColorFilter cf) {
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSPARENT;
            }

            @Override
            public int getIntrinsicWidth() {
                return 0;
            }

            @Override
            public int getIntrinsicHeight() {
                return 0;
            }
        };
    }

    private static void toast(String msg) {
        Context ctx = com.leshao.v3.ContextManager.getAppContext();
        if (ctx != null) {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        }
    }

    public static void dismiss() {
        sUiHandler = null;
        sSession++;
        sPlayIconView = null;
        sBirdView = null;
        if (sDialog != null) {
            try {
                sDialog.dismiss();
            } catch (Throwable ignored) {
            }
            sDialog = null;
        }
        MediaPlayer mp = sPlayer;
        sPlayer = null;
        if (mp != null) {
            try {
                mp.release();
            } catch (Throwable ignored) {
            }
        }
    }
}
