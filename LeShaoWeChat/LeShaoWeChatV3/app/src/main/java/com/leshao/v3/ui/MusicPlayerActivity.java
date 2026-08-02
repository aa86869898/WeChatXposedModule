package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RectShape;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.AbsoluteSizeSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.io.File;

public class MusicPlayerActivity extends Activity {

    private ImageView mCover, mDiscBg;
    private FrameLayout mDiscFrame;
    private RotateAnimation mDiscAnim;
    private TextView mTitle, mArtist, mCurrentTime, mTotalTime;
    private LinearGradientSeekBar mSeekBar;
    private TextView mPrevBtn, mPlayBtn, mNextBtn, mModeBtn, mQualityBtn, mDownloadBtn, mSendVoiceBtn;
    private TextView mLyricText;
    private LinearLayout mRoot;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mProgressRunner;
    private boolean mUserSeeking;
    private boolean mDiscPaused;
    private int mQuality = 0;
    private MusicPlayerManager.PlayerCallback mPlayerCb;
    private List<LyricLine> mLyricLines = new ArrayList<>();
    private int mCurrentLyricIdx = -1;
    private ScrollView mLyricScroll;

    private static final String[] QUALITY_LABELS = {"标准音质", "高品质 HQ", "无损 FLAC"};
    private static final String[] QUALITY_LABELS_SHORT = {"标准", "HQ", "无损"};
    private static final int[] QUALITY_COLORS = {0xFF6B7280, 0xFF3B8EFF, 0xFFF59E0B};
    private static final int[] NEON_COLORS = {0xFFFF6B9D, 0xFFC44DFF, 0xFF6BC5FF, 0xFF39E6A5, 0xFFFFE259};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MusicLog.init();
        MusicLog.i("PlayerActivity", "onCreate start");
        try {
            mRoot = new LinearLayout(this);
            mRoot.setOrientation(LinearLayout.VERTICAL);
            mRoot.setBackgroundColor(MusicActivity.CLR_BG);
            mRoot.setPadding(0, MusicActivity.sStatusBarH, 0, 0);

            buildHeader();
            buildDiscArea();
            buildSongInfo();
            buildLyricArea();
            buildProgressArea();
            buildControls();
            buildBottomBar();

            setContentView(mRoot);
            updateUI();
            startDiscAnim();
            startProgressRunner();

            mPlayerCb = new MusicPlayerManager.PlayerCallback() {
                @Override public void onPlayStateChanged(boolean playing) {
                    mHandler.post(() -> updatePlayBtn());
                }
                @Override public void onProgressChanged(int position, int duration) {}
                @Override public void onSongChanged(MusicSearchApi.Song song, int index) {
                    mHandler.post(() -> updateUI());
                }
            };
            MusicActivity.sPlayer.addCallback(mPlayerCb);

            MusicLog.i("PlayerActivity", "onCreate OK");
        } catch (Throwable e) {
            MusicLog.e("PlayerActivity", "onCreate CRASH", e);
            Log.e("MusicPlayer", "onCreate CRASH", e);
            Toast.makeText(this, "播放器崩溃: " + e.toString(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopProgressRunner();
        stopDiscAnim();
        if (mPlayerCb != null && MusicActivity.sPlayer != null) {
            MusicActivity.sPlayer.removeCallback(mPlayerCb);
        }
        mHandler.removeCallbacksAndMessages(null);
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

        mRoot.addView(header);
    }

    void buildDiscArea() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setGravity(Gravity.CENTER);
        area.setPadding(0, MusicActivity.dp(8), 0, MusicActivity.dp(8));

        int discSize = (int) (Math.min(getResources().getDisplayMetrics().widthPixels * 0.55f, MusicActivity.dp(200)));

        mDiscFrame = new FrameLayout(this);
        mDiscFrame.setLayoutParams(new LinearLayout.LayoutParams(discSize, discSize));

        mDiscBg = new ImageView(this);
        FrameLayout.LayoutParams discLp = new FrameLayout.LayoutParams(discSize, discSize);
        mDiscBg.setLayoutParams(discLp);
        mDiscBg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mDiscBg.setImageDrawable(MusicActivity.emoji("\uD83D\uDCBF", discSize));
        mDiscBg.setAlpha(0.12f);
        mDiscFrame.addView(mDiscBg);

        int coverSize = (int) (discSize * 0.55f);
        mCover = new ImageView(this);
        FrameLayout.LayoutParams covLp = new FrameLayout.LayoutParams(coverSize, coverSize);
        covLp.gravity = Gravity.CENTER;
        mCover.setLayoutParams(covLp);
        mCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable coverBg = new GradientDrawable();
        coverBg.setCornerRadius(coverSize / 2);
        coverBg.setColor(0xFFE8E8E8);
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
        mTitle.setTextSize(16);
        mTitle.setTextColor(MusicActivity.CLR_TEXT);
        mTitle.setTypeface(null, Typeface.BOLD);
        mTitle.setSingleLine(true);
        mTitle.setGravity(Gravity.CENTER);
        mTitle.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        mTitle.setMarqueeRepeatLimit(-1);
        mTitle.setSelected(true);
        mTitle.setPadding(0, 0, 0, MusicActivity.dp(2));
        area.addView(mTitle);

        mArtist = new TextView(this);
        mArtist.setTextSize(12);
        mArtist.setTextColor(MusicActivity.CLR_TEXT2);
        mArtist.setSingleLine(true);
        mArtist.setGravity(Gravity.CENTER);
        area.addView(mArtist);

        mRoot.addView(area);
    }

    void buildLyricArea() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setGravity(Gravity.CENTER);
        area.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1.0f));
        area.setPadding(MusicActivity.dp(24), MusicActivity.dp(8), MusicActivity.dp(24), MusicActivity.dp(4));

        mLyricScroll = new ScrollView(this);
        mLyricScroll.setFillViewport(true);

        mLyricText = new TextView(this);
        mLyricText.setTextSize(14);
        mLyricText.setTextColor(MusicActivity.CLR_TEXT);
        mLyricText.setGravity(Gravity.CENTER);
        mLyricText.setLineSpacing(MusicActivity.dp(8), 1.0f);
        mLyricText.setText("加载歌词中...");
        mLyricText.setPadding(0, MusicActivity.dp(60), 0, MusicActivity.dp(60));
        mLyricScroll.addView(mLyricText);
        area.addView(mLyricScroll, new LinearLayout.LayoutParams(-1, -1));

        mRoot.addView(area);
    }

    void buildProgressArea() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setPadding(MusicActivity.dp(16), MusicActivity.dp(4), MusicActivity.dp(16), MusicActivity.dp(2));

        mSeekBar = new LinearGradientSeekBar(this);
        mSeekBar.setLayoutParams(new LinearLayout.LayoutParams(-1, MusicActivity.dp(28)));
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
        timeRow.setPadding(0, MusicActivity.dp(2), 0, 0);

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

    void buildControls() {
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(MusicActivity.dp(24), MusicActivity.dp(8), MusicActivity.dp(24), MusicActivity.dp(8));

        mPrevBtn = playCtrlBtn("\u23EE", 22);
        mPrevBtn.setOnClickListener(v -> {
            try {
                if (MusicActivity.sPlayer != null) { MusicActivity.sPlayer.prev(); updateUI(); }
            } catch (Throwable e) { /* ignore */ }
        });
        controls.addView(mPrevBtn);

        View sp2 = new View(this);
        sp2.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(14), 1));
        controls.addView(sp2);

        int playSize = MusicActivity.dp(52);
        GradientDrawable playBg = new GradientDrawable();
        playBg.setShape(GradientDrawable.OVAL);
        int[] playColors = {0xFF6B5CE7, 0xFFFF6B9D};
        playBg.setColors(playColors);
        playBg.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        playBg.setOrientation(GradientDrawable.Orientation.TL_BR);

        mPlayBtn = new TextView(this);
        mPlayBtn.setText("\u25B6");
        mPlayBtn.setTextSize(22);
        mPlayBtn.setTextColor(0xFFFFFFFF);
        mPlayBtn.setGravity(Gravity.CENTER);
        mPlayBtn.setBackground(playBg);
        mPlayBtn.setLayoutParams(new LinearLayout.LayoutParams(playSize, playSize));
        mPlayBtn.setPadding(MusicActivity.dp(3), 0, 0, 0);
        mPlayBtn.setOnClickListener(v -> {
            if (MusicActivity.sPlayer == null || MusicActivity.sPlayer.getCurrent() == null) return;
            if (MusicActivity.sPlayer.isPlaying()) {
                MusicActivity.sPlayer.pause();
                pauseDisc();
            } else {
                MusicActivity.sPlayer.resume();
                resumeDisc();
            }
            updatePlayBtn();
            if (MusicActivity.sInstance != null) MusicActivity.sInstance.refreshPlayerBar();
        });
        controls.addView(mPlayBtn);

        View sp3 = new View(this);
        sp3.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(14), 1));
        controls.addView(sp3);

        mNextBtn = playCtrlBtn("\u23ED", 22);
        mNextBtn.setOnClickListener(v -> {
            try {
                if (MusicActivity.sPlayer != null) { MusicActivity.sPlayer.next(); updateUI(); }
            } catch (Throwable e) { /* ignore */ }
        });
        controls.addView(mNextBtn);

        mRoot.addView(controls);
    }

    void buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(MusicActivity.dp(8), MusicActivity.dp(10), MusicActivity.dp(8), MusicActivity.dp(14));

        mModeBtn = bottomBtn("\uD83D\uDD01", "循环");
        mModeBtn.setOnClickListener(v -> {
            if (MusicActivity.sPlayer != null) {
                int mode = MusicActivity.sPlayer.getPlayMode();
                String[] modes = {"\uD83D\uDD01", "\uD83D\uDD02", "\uD83D\uDD00"};
                String[] modeLabels = {"循环", "单曲", "随机"};
                MusicActivity.sPlayer.setPlayMode((mode + 1) % 3);
                mModeBtn.setText(modes[(mode + 1) % 3] + "\n" + modeLabels[(mode + 1) % 3]);
            }
        });
        bar.addView(mModeBtn);

        View sep1 = new View(this);
        sep1.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(8), 1));
        bar.addView(sep1);

        mQualityBtn = bottomBtn("\uD83C\uDFA7", QUALITY_LABELS_SHORT[mQuality]);
        mQualityBtn.setOnClickListener(v -> showQualityDialog());
        bar.addView(mQualityBtn);

        View sep2 = new View(this);
        sep2.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(8), 1));
        bar.addView(sep2);

        mDownloadBtn = bottomBtn("\u2B07", "下载");
        mDownloadBtn.setOnClickListener(v -> showDownloadDialog());
        bar.addView(mDownloadBtn);

        View sep3 = new View(this);
        sep3.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(8), 1));
        bar.addView(sep3);

        mSendVoiceBtn = bottomBtn("\uD83C\uDFB6", "发语音");
        mSendVoiceBtn.setOnClickListener(v -> showSendVoiceDialog());
        bar.addView(mSendVoiceBtn);

        View sep4 = new View(this);
        sep4.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(8), 1));
        bar.addView(sep4);

        TextView listBtn = bottomBtn("\uD83D\uDCCB", "列表");
        listBtn.setOnClickListener(v -> showPlaylist());
        bar.addView(listBtn);

        mRoot.addView(bar);
    }

    private TextView playCtrlBtn(String icon, int size) {
        TextView btn = new TextView(this);
        btn.setText(icon);
        btn.setTextSize(size);
        btn.setTextColor(MusicActivity.CLR_TEXT);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(MusicActivity.dp(10), MusicActivity.dp(10), MusicActivity.dp(10), MusicActivity.dp(10));
        return btn;
    }

    private TextView bottomBtn(String icon, String label) {
        TextView btn = new TextView(this);
        btn.setText(icon + "\n" + label);
        btn.setTextSize(11);
        btn.setTextColor(MusicActivity.CLR_TEXT2);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(MusicActivity.dp(6), MusicActivity.dp(6), MusicActivity.dp(6), MusicActivity.dp(6));
        btn.setLineSpacing(MusicActivity.dp(3), 1.0f);
        btn.setMinWidth(MusicActivity.dp(56));
        return btn;
    }

    private void showQualityDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择音质");
        builder.setSingleChoiceItems(QUALITY_LABELS, mQuality, (d, which) -> {
            mQuality = which;
            if (mQualityBtn != null) mQualityBtn.setText("\uD83C\uDFA7\n" + QUALITY_LABELS_SHORT[mQuality]);
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

    private void showDownloadDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("下载音质选择");
        builder.setSingleChoiceItems(QUALITY_LABELS, mQuality, (d, which) -> {
            d.dismiss();
            MusicSearchApi.Song song = MusicActivity.sPlayer != null ? MusicActivity.sPlayer.getCurrent() : null;
            if (song == null) {
                MusicActivity.toast("无歌曲可下载");
                return;
            }
            MusicActivity.toast("开始下载 (" + QUALITY_LABELS_SHORT[which] + ")...");
            MusicActivity.sPlayer.download(song);
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    // ===== 发送语音 =====

    private void showSendVoiceDialog() {
        MusicSearchApi.Song song = MusicActivity.sPlayer != null ? MusicActivity.sPlayer.getCurrent() : null;
        if (song == null) {
            MusicActivity.toast("无歌曲可发送");
            return;
        }
        ContactSelectorView.show(this, true, selected -> {
            if (selected == null || selected.isEmpty()) {
                MusicActivity.toast("未选择联系人");
                return;
            }
            sendSongAsVoice(song, selected);
        });
    }

    private void sendSongAsVoice(final MusicSearchApi.Song song, final List<com.leshao.v3.model.Contact> contacts) {
        MusicActivity.toast("获取播放地址...");
        MusicSearchApi.PlayUrlCallback cb = new MusicSearchApi.PlayUrlCallback() {
            @Override
            public void onUrl(String url) {
                if (url == null || url.isEmpty()) {
                    MusicActivity.toast("获取音源失败");
                    return;
                }
                convertAndSend(url, contacts);
            }
            @Override
            public void onError(String msg) {
                MusicActivity.toast("获取音源失败");
            }
        };
        MusicSearchApi.getKugouPlayUrl(song.hash, cb);
    }

    private void convertAndSend(final String audioUrl, final List<com.leshao.v3.model.Contact> contacts) {
        new Thread(() -> {
            try {
                File tmp = new File(getCacheDir(), "voice_send_" + System.currentTimeMillis());
                MusicLog.i("Player", "download audio: " + audioUrl.substring(0, Math.min(60, audioUrl.length())));
                downloadToFile(audioUrl, tmp);
                if (!tmp.exists() || tmp.length() < 1024) {
                    MusicActivity.toast("音频下载失败");
                    return;
                }
                MusicLog.i("Player", "audio downloaded: " + tmp.length() + " bytes");
                byte[] pcm = com.leshao.v3.hook.TtsVoiceSender.decodeAudioToPcm16kMono(tmp.getAbsolutePath());
                tmp.delete();
                if (pcm == null || pcm.length == 0) {
                    MusicActivity.toast("音频解码失败");
                    return;
                }
                for (com.leshao.v3.model.Contact c : contacts) {
                    String cid = "mv" + System.currentTimeMillis() + "_" + Math.abs(c.wxid.hashCode());
                    com.leshao.v3.hook.TtsVoiceSender.sendPcm16kMonoAsVoice(c.wxid, pcm, cid);
                }
                MusicActivity.toast("已发送 " + contacts.size() + " 人");
            } catch (Throwable e) {
                MusicLog.e("Player", "send voice crash: " + e.getMessage());
                MusicActivity.toast("发送失败: " + e.getMessage());
            }
        }, "leshao-music-voice-send").start();
    }

    private void downloadToFile(String urlStr, File out) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        conn.setInstanceFollowRedirects(true);
        try (java.io.InputStream in = conn.getInputStream();
             java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) fos.write(buf, 0, r);
        } finally {
            conn.disconnect();
        }
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
        if (isFinishing() || isDestroyed()) return;
        try {
            MusicSearchApi.Song song = MusicActivity.sPlayer != null ? MusicActivity.sPlayer.getCurrent() : null;
            if (song == null) {
                if (mTitle != null) mTitle.setText("未在播放");
                if (mArtist != null) mArtist.setText("");
                if (mCover != null) mCover.setImageBitmap(null);
                if (mLyricText != null) mLyricText.setText("暂无歌词");
                pauseDisc();
                return;
            }
            if (mTitle != null) mTitle.setText(song.title);
            if (mArtist != null) mArtist.setText(song.artist);
            MusicActivity.loadCircularCover(mCover, song.cover);

            updatePlayBtn();

            if (mSeekBar != null) {
                mSeekBar.setMax(1000);
                mSeekBar.setProgress(0);
            }
            if (mCurrentTime != null) mCurrentTime.setText("0:00");
            if (mTotalTime != null) mTotalTime.setText(formatTime(song.duration * 1000));

            mLyricLines.clear();
            mCurrentLyricIdx = -1;
            loadLyric(song.hash);
        } catch (Throwable e) { /* ignore */ }
    }

    private void updatePlayBtn() {
        boolean playing = MusicActivity.sPlayer != null && MusicActivity.sPlayer.isPlaying();
        mPlayBtn.setText(playing ? "\u23F8" : "\u25B6");
        if (playing) resumeDisc();
        else pauseDisc();
    }

    private void loadLyric(String hash) {
        if (hash == null || hash.isEmpty()) {
            mLyricText.setText("暂无歌词");
            return;
        }
        KgApi.getLyric(hash, new KgApi.StringCallback() {
            @Override
            public void onResult(String data) {
                if (isFinishing()) return;
                mHandler.post(() -> {
                    mLyricLines = parseLrc(data);
                    if (mLyricLines.isEmpty()) {
                        mLyricText.setText("暂无歌词");
                    } else {
                        mLyricText.setText("");
                        mLyricText.setSingleLine(false);
                    }
                });
            }
            @Override
            public void onError(String msg) {
                if (isFinishing()) return;
                mHandler.post(() -> mLyricText.setText("暂无歌词"));
            }
        });
    }

    private List<LyricLine> parseLrc(String raw) {
        List<LyricLine> lines = new ArrayList<>();
        if (raw == null || raw.isEmpty() || raw.startsWith("<?xml") || raw.startsWith("<!DOCTYPE"))
            return lines;
        if (!raw.contains("[") || !raw.contains(":")) return lines;

        String[] rawLines = raw.split("\n");
        for (String line : rawLines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\\[(\\d{2}):(\\d{2})(?:[.:](\\d{2,3}))?\\]").matcher(line);
            String text = line.replaceAll("\\[\\d{2}:\\d{2}[.:]\\d{2,3}\\]", "").trim();
            if (text.isEmpty()) continue;

            while (m.find()) {
                int min = Integer.parseInt(m.group(1));
                int sec = Integer.parseInt(m.group(2));
                int ms = 0;
                String msStr = m.group(3);
                if (msStr != null) {
                    ms = Integer.parseInt(msStr);
                    if (msStr.length() == 2) ms *= 10;
                }
                int timeMs = min * 60000 + sec * 1000 + ms;
                lines.add(new LyricLine(timeMs, text));
            }
        }

        if (lines.size() > 1) {
            for (int i = 0; i < lines.size(); i++) {
                lines.get(i).index = i;
            }
        }
        return lines;
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
                        updateLyric(pos);
                    }
                }
                if (MusicActivity.sInstance != null) MusicActivity.sInstance.refreshPlayerBar();
                mHandler.postDelayed(this, 500);
            }
        };
        mHandler.post(mProgressRunner);
    }

    private void stopProgressRunner() {
        if (mProgressRunner != null) mHandler.removeCallbacks(mProgressRunner);
    }

    private void updateLyric(int positionMs) {
        if (mLyricLines.isEmpty() || mLyricText == null) return;
        int idx = -1;
        for (int i = mLyricLines.size() - 1; i >= 0; i--) {
            if (mLyricLines.get(i).timeMs <= positionMs) {
                idx = i;
                break;
            }
        }
        if (idx < 0) idx = 0;
        if (idx == mCurrentLyricIdx) return;
        mCurrentLyricIdx = idx;

        int start = Math.max(0, idx - 3);
        int end = Math.min(mLyricLines.size(), idx + 4);
        android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder();
        for (int i = start; i < end; i++) {
            LyricLine ll = mLyricLines.get(i);
            String line = ll.text + (i < end - 1 ? "\n" : "");
            int oldLen = ssb.length();
            ssb.append(line);
            if (i == idx) {
                ssb.setSpan(new ForegroundColorSpan(MusicActivity.CLR_ACCENT), oldLen, oldLen + line.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new AbsoluteSizeSpan(MusicActivity.dp(16)), oldLen, oldLen + line.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), oldLen, oldLen + line.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                ssb.setSpan(new ForegroundColorSpan(MusicActivity.CLR_TEXT2), oldLen, oldLen + line.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        mLyricText.setText(ssb);
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

    class LinearGradientSeekBar extends SeekBar {

        private Paint mLinePaint;
        private Paint mThumbPaint;
        private int[] mColors;

        public LinearGradientSeekBar(android.content.Context context) {
            super(context);
            mColors = NEON_COLORS;
            mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mLinePaint.setStyle(Paint.Style.STROKE);
            mLinePaint.setStrokeCap(Paint.Cap.ROUND);

            mThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mThumbPaint.setStyle(Paint.Style.FILL);
            setThumb(null);
            setBackgroundColor(Color.TRANSPARENT);
            setProgressDrawable(null);
        }

        @Override
        protected synchronized void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            int cy = h / 2;
            int pad = MusicActivity.dp(2);
            int left = pad;
            int right = w - pad;
            int trackH = MusicActivity.dp(5);
            float ratio = getProgress() / 1000f;
            int progressX = (int) (left + (right - left) * ratio);

            float startX = left + getPaddingLeft();
            float endX = right - getPaddingRight();

            mLinePaint.setStrokeWidth(trackH);
            mLinePaint.setColor(0xFFE5E7EB);
            canvas.drawLine(startX, cy, endX, cy, mLinePaint);

            mLinePaint.setShader(new LinearGradient(startX, 0, progressX, 0,
                mColors, null, Shader.TileMode.MIRROR));
            mLinePaint.setStrokeWidth(trackH);
            canvas.drawLine(startX, cy, progressX, cy, mLinePaint);

            mLinePaint.setShader(null);
            drawHeart(canvas, progressX, cy, MusicActivity.dp(6));
        }

        private void drawHeart(Canvas canvas, float cx, float cy, float size) {
            int saved = canvas.save();
            canvas.translate(cx, cy);
            Path heart = new Path();
            float s = size;
            heart.moveTo(0, s * 0.4f);
            heart.cubicTo(-s, -s * 0.4f, -s * 0.5f, -s, 0, -s * 0.3f);
            heart.cubicTo(s * 0.5f, -s, s, -s * 0.4f, 0, s * 0.4f);
            heart.close();

            float[] pts = {0, -s * 0.5f, 0, s * 0.5f};
            Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
            sp.setShader(new LinearGradient(-s, 0, s, 0, mColors, null, Shader.TileMode.MIRROR));
            sp.setStyle(Paint.Style.FILL);
            canvas.drawPath(heart, sp);

            canvas.restore();
        }
    }

    static class LyricLine {
        int timeMs;
        int index;
        String text;
        LyricLine(int timeMs, String text) { this.timeMs = timeMs; this.text = text; }
    }
}
