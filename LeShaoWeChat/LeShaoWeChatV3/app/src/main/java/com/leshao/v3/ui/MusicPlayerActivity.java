package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.List;
import java.util.Locale;

public class MusicPlayerActivity extends Activity {

    private ImageView mCover, mDiscBg;
    private FrameLayout mDiscFrame;
    private RotateAnimation mDiscAnim;
    private TextView mTitle, mArtist, mCurrentTime, mTotalTime;
    private SeekBar mSeekBar;
    private TextView mPrevBtn, mPlayBtn, mNextBtn, mModeBtn, mQualityBtn;
    private TextView mLyricText;
    private LinearLayout mRoot;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mProgressRunner;
    private boolean mUserSeeking;
    private boolean mDiscPaused;
    private float mDiscDegrees;
    private int mQuality = 0; // 0=标准 1=HQ 2=无损

    private static final String[] QUALITY_LABELS = {"标准音质", "高品质 HQ", "无损 FLAC"};
    private static final int[] QUALITY_COLORS = {0xFF6B7280, 0xFF3B8EFF, 0xFFF59E0B};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            mRoot = new LinearLayout(this);
            mRoot.setOrientation(LinearLayout.VERTICAL);
            mRoot.setBackgroundColor(MusicActivity.CLR_BG);
            mRoot.setPadding(0, MusicActivity.sStatusBarH, 0, 0);

            buildHeader();
            buildDiscArea();
            buildSongInfo();
            buildProgressArea();
            buildLyricArea();
            buildControls();

            setContentView(mRoot);
            updateUI();
            startDiscAnim();
            startProgressRunner();
            Log.d("MusicPlayer", "onCreate OK");
        } catch (Throwable e) {
            Log.e("MusicPlayer", "onCreate CRASH: " + Log.getStackTraceString(e));
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopProgressRunner();
        stopDiscAnim();
    }

    void buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(MusicActivity.dp(12), MusicActivity.dp(8), MusicActivity.dp(12), MusicActivity.dp(4));

        TextView back = new TextView(this);
        back.setText("\u2190");
        back.setTextSize(20);
        back.setTextColor(MusicActivity.CLR_TEXT);
        back.setPadding(0, 0, MusicActivity.dp(12), 0);
        back.setOnClickListener(v -> finish());
        header.addView(back);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView topTitle = new TextView(this);
        topTitle.setText("正在播放");
        topTitle.setTextSize(15);
        topTitle.setTextColor(MusicActivity.CLR_TEXT);
        topTitle.setTypeface(null, Typeface.BOLD);
        titleCol.addView(topTitle);
        header.addView(titleCol);

        mQualityBtn = new TextView(this);
        mQualityBtn.setTextSize(10);
        mQualityBtn.setGravity(Gravity.CENTER);
        mQualityBtn.setPadding(MusicActivity.dp(8), MusicActivity.dp(4), MusicActivity.dp(8), MusicActivity.dp(4));
        mQualityBtn.setBackground(MusicActivity.rd(12, QUALITY_COLORS[mQuality]));
        mQualityBtn.setTextColor(0xFFFFFFFF);
        mQualityBtn.setText(QUALITY_LABELS[mQuality]);
        mQualityBtn.setTypeface(null, Typeface.BOLD);
        mQualityBtn.setOnClickListener(v -> showQualityDialog());
        header.addView(mQualityBtn);

        mRoot.addView(header);
    }

    void buildDiscArea() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setGravity(Gravity.CENTER);
        area.setPadding(0, MusicActivity.dp(8), 0, MusicActivity.dp(8));

        int discSize = (int) (Math.min(getResources().getDisplayMetrics().widthPixels * 0.65f, MusicActivity.dp(240)));

        mDiscFrame = new FrameLayout(this);
        mDiscFrame.setLayoutParams(new LinearLayout.LayoutParams(discSize, discSize));

        mDiscBg = new ImageView(this);
        FrameLayout.LayoutParams discLp = new FrameLayout.LayoutParams(discSize, discSize);
        mDiscBg.setLayoutParams(discLp);
        mDiscBg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mDiscBg.setImageDrawable(MusicActivity.emoji("\uD83D\uDCBF", discSize));
        mDiscBg.setAlpha(0.15f);
        mDiscFrame.addView(mDiscBg);

        int coverSize = (int) (discSize * 0.55f);
        mCover = new ImageView(this);
        FrameLayout.LayoutParams covLp = new FrameLayout.LayoutParams(coverSize, coverSize);
        covLp.gravity = Gravity.CENTER;
        mCover.setLayoutParams(covLp);
        mCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable coverBg = new GradientDrawable();
        coverBg.setCornerRadius(coverSize / 2);
        coverBg.setColor(0xFFDDDDDD);
        mCover.setBackground(coverBg);
        mDiscFrame.addView(mCover);

        area.addView(mDiscFrame);
        mRoot.addView(area);
    }

    void buildSongInfo() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setGravity(Gravity.CENTER);
        area.setPadding(MusicActivity.dp(24), 0, MusicActivity.dp(24), 0);

        mTitle = new TextView(this);
        mTitle.setTextSize(17);
        mTitle.setTextColor(MusicActivity.CLR_TEXT);
        mTitle.setTypeface(null, Typeface.BOLD);
        mTitle.setSingleLine(true);
        mTitle.setGravity(Gravity.CENTER);
        mTitle.setPadding(0, MusicActivity.dp(12), 0, MusicActivity.dp(4));
        area.addView(mTitle);

        mArtist = new TextView(this);
        mArtist.setTextSize(13);
        mArtist.setTextColor(MusicActivity.CLR_TEXT2);
        mArtist.setSingleLine(true);
        mArtist.setGravity(Gravity.CENTER);
        area.addView(mArtist);

        mRoot.addView(area);
    }

    void buildProgressArea() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setPadding(MusicActivity.dp(20), MusicActivity.dp(8), MusicActivity.dp(20), MusicActivity.dp(4));

        mSeekBar = new SeekBar(this);
        mSeekBar.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        mSeekBar.setMax(1000);
        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser && MusicActivity.sPlayer != null) {
                    int dur = MusicActivity.sPlayer.getDuration();
                    if (dur > 0) MusicActivity.sPlayer.seekTo(p * dur / 1000);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { mUserSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) { mUserSeeking = false; }
        });
        area.addView(mSeekBar);

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);

        mCurrentTime = new TextView(this);
        mCurrentTime.setTextSize(10);
        mCurrentTime.setTextColor(MusicActivity.CLR_TEXT2);
        mCurrentTime.setText("0:00");
        timeRow.addView(mCurrentTime);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1.0f));
        timeRow.addView(spacer);

        mTotalTime = new TextView(this);
        mTotalTime.setTextSize(10);
        mTotalTime.setTextColor(MusicActivity.CLR_TEXT2);
        mTotalTime.setText("0:00");
        timeRow.addView(mTotalTime);

        area.addView(timeRow);
        mRoot.addView(area);
    }

    void buildLyricArea() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setGravity(Gravity.CENTER);
        area.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1.0f));
        area.setPadding(MusicActivity.dp(20), MusicActivity.dp(4), MusicActivity.dp(20), MusicActivity.dp(4));

        mLyricText = new TextView(this);
        mLyricText.setTextSize(14);
        mLyricText.setTextColor(MusicActivity.CLR_TEXT2);
        mLyricText.setGravity(Gravity.CENTER);
        mLyricText.setLineSpacing(MusicActivity.dp(6), 1.0f);
        mLyricText.setText("暂无歌词");
        area.addView(mLyricText);

        mRoot.addView(area);
    }

    void buildControls() {
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(MusicActivity.dp(6), MusicActivity.dp(4), MusicActivity.dp(6), MusicActivity.dp(12));

        mModeBtn = ctrlBtn("\uD83D\uDD01", 11);
        mModeBtn.setOnClickListener(v -> {
            if (MusicActivity.sPlayer != null) {
                int mode = MusicActivity.sPlayer.getPlayMode();
                String[] modes = {"\uD83D\uDD01", "\uD83D\uDD02", "\uD83D\uDD00"};
                MusicActivity.sPlayer.setPlayMode((mode + 1) % 3);
                mModeBtn.setText(modes[(mode + 1) % 3]);
            }
        });
        controls.addView(mModeBtn);

        View sp1 = new View(this);
        sp1.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1.0f));
        controls.addView(sp1);

        mPrevBtn = ctrlBtn("\u23EE", 20);
        mPrevBtn.setOnClickListener(v -> {
            if (MusicActivity.sPlayer != null) { MusicActivity.sPlayer.prev(); updateUI(); }
        });
        controls.addView(mPrevBtn);

        View sp2 = new View(this);
        sp2.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(6), 1));
        controls.addView(sp2);

        int playSize = MusicActivity.dp(56);
        GradientDrawable playBg = new GradientDrawable();
        playBg.setShape(GradientDrawable.OVAL);
        playBg.setColor(MusicActivity.CLR_ACCENT);

        mPlayBtn = new TextView(this);
        mPlayBtn.setText("\u25B6");
        mPlayBtn.setTextSize(24);
        mPlayBtn.setTextColor(0xFFFFFFFF);
        mPlayBtn.setGravity(Gravity.CENTER);
        mPlayBtn.setBackground(playBg);
        mPlayBtn.setLayoutParams(new LinearLayout.LayoutParams(playSize, playSize));
        mPlayBtn.setOnClickListener(v -> {
            if (MusicActivity.sPlayer == null || MusicActivity.sPlayer.getCurrent() == null) return;
            if (MusicActivity.sPlayer.isPlaying()) {
                MusicActivity.sPlayer.pause();
                pauseDisc();
            } else {
                MusicActivity.sPlayer.resume();
                resumeDisc();
            }
            updateUI();
            if (MusicActivity.sInstance != null) MusicActivity.sInstance.refreshPlayerBar();
        });
        controls.addView(mPlayBtn);

        View sp3 = new View(this);
        sp3.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(6), 1));
        controls.addView(sp3);

        mNextBtn = ctrlBtn("\u23ED", 20);
        mNextBtn.setOnClickListener(v -> {
            if (MusicActivity.sPlayer != null) { MusicActivity.sPlayer.next(); updateUI(); }
        });
        controls.addView(mNextBtn);

        View sp4 = new View(this);
        sp4.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1.0f));
        controls.addView(sp4);

        TextView listBtn = ctrlBtn("\uD83D\uDCCB", 11);
        listBtn.setOnClickListener(v -> showPlaylist());
        controls.addView(listBtn);

        mRoot.addView(controls);
    }

    private TextView ctrlBtn(String emoji, int size) {
        TextView btn = new TextView(this);
        btn.setText(emoji);
        btn.setTextSize(size);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(MusicActivity.dp(8), MusicActivity.dp(8), MusicActivity.dp(8), MusicActivity.dp(8));
        return btn;
    }

    private void showQualityDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择音质");
        builder.setSingleChoiceItems(QUALITY_LABELS, mQuality, (d, which) -> {
            mQuality = which;
            mQualityBtn.setText(QUALITY_LABELS[mQuality]);
            mQualityBtn.setBackground(MusicActivity.rd(12, QUALITY_COLORS[mQuality]));
            d.dismiss();

            MusicSearchApi.Song song = MusicActivity.sPlayer != null ? MusicActivity.sPlayer.getCurrent() : null;
            if (song != null) {
                MusicActivity.toast("已切换到: " + QUALITY_LABELS[mQuality]);
                MusicActivity.sPlayer.refetchWithQuality(song, mQuality);
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void startDiscAnim() {
        mDiscAnim = new RotateAnimation(0, 360,
            Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        mDiscAnim.setDuration(8000);
        mDiscAnim.setRepeatCount(Animation.INFINITE);
        mDiscAnim.setInterpolator(new LinearInterpolator());
        mDiscFrame.startAnimation(mDiscAnim);
    }

    private void pauseDisc() {
        mDiscPaused = true;
        if (mDiscFrame.getAnimation() != null) {
            // save current rotation state
            mDiscAnim.cancel();
        }
    }

    private void resumeDisc() {
        if (!mDiscPaused) return;
        mDiscPaused = false;
        startDiscAnim();
    }

    private void stopDiscAnim() {
        if (mDiscFrame != null) mDiscFrame.clearAnimation();
    }

    private void updateUI() {
        MusicSearchApi.Song song = MusicActivity.sPlayer != null ? MusicActivity.sPlayer.getCurrent() : null;
        if (song == null) {
            mTitle.setText("未在播放");
            mArtist.setText("");
            mCover.setImageBitmap(null);
            pauseDisc();
            return;
        }
        mTitle.setText(song.title);
        mArtist.setText(song.artist);
        MusicActivity.loadCover(mCover, song.cover);

        boolean playing = MusicActivity.sPlayer != null && MusicActivity.sPlayer.isPlaying();
        mPlayBtn.setText(playing ? "\u23F8" : "\u25B6");
        if (playing) resumeDisc();
        else pauseDisc();

        mSeekBar.setMax(1000);
        mCurrentTime.setText("0:00");
        mTotalTime.setText(formatTime(song.duration * 1000));
    }

    private void startProgressRunner() {
        mProgressRunner = new Runnable() {
            @Override
            public void run() {
                if (MusicActivity.sPlayer != null && MusicActivity.sPlayer.isPlaying() && !mUserSeeking) {
                    int pos = MusicActivity.sPlayer.getPosition();
                    int dur = MusicActivity.sPlayer.getDuration();
                    if (dur > 0) {
                        mSeekBar.setProgress(pos * 1000 / dur);
                        mCurrentTime.setText(formatTime(pos));
                        mTotalTime.setText(formatTime(dur));
                    }
                }
                if (MusicActivity.sInstance != null) MusicActivity.sInstance.refreshPlayerBar();
                mHandler.postDelayed(this, 1000);
            }
        };
        mHandler.post(mProgressRunner);
    }

    private void stopProgressRunner() {
        if (mProgressRunner != null) mHandler.removeCallbacks(mProgressRunner);
    }

    private String formatTime(int ms) {
        int s = ms / 1000;
        int m = s / 60;
        s = s % 60;
        return String.format(Locale.getDefault(), "%d:%02d", m, s);
    }

    private void showPlaylist() {
        List<MusicSearchApi.Song> list = MusicActivity.sPlayer != null ? MusicActivity.sPlayer.getPlaylist() : null;
        if (list == null || list.isEmpty()) {
            MusicActivity.toast("播放列表为空");
            return;
        }
        MusicSearchApi.Song current = MusicActivity.sPlayer.getCurrent();

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(MusicActivity.dp(12), MusicActivity.dp(12), MusicActivity.dp(12), MusicActivity.dp(12));

        TextView header = new TextView(this);
        header.setText("播放列表 (" + list.size() + " 首)");
        header.setTextSize(14);
        header.setTextColor(MusicActivity.CLR_TEXT);
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(0, 0, 0, MusicActivity.dp(8));
        container.addView(header);

        for (int i = 0; i < Math.min(list.size(), 50); i++) {
            MusicSearchApi.Song s = list.get(i);
            boolean isCurrent = current != null && s.id.equals(current.id);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, MusicActivity.dp(4), 0, MusicActivity.dp(4));

            TextView tv = new TextView(this);
            tv.setText((i + 1) + ". " + s.title + " - " + s.artist);
            tv.setTextSize(12);
            tv.setTextColor(isCurrent ? MusicActivity.CLR_ACCENT : MusicActivity.CLR_TEXT);
            tv.setTypeface(null, isCurrent ? Typeface.BOLD : Typeface.NORMAL);
            tv.setSingleLine(true);
            row.addView(tv);

            final int idx = i;
            row.setOnClickListener(v -> {
                if (MusicActivity.sPlayer != null) { MusicActivity.sPlayer.playFromPlaylist(idx); updateUI(); }
            });
            container.addView(row);
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(container);

        new AlertDialog.Builder(this)
            .setView(sv)
            .setPositiveButton("关闭", null)
            .show();
    }
}
