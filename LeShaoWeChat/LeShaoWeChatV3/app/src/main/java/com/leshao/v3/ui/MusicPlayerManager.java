package com.leshao.v3.ui;

import android.app.DownloadManager;
import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MusicPlayerManager {

    private static MusicPlayerManager sInstance;
    private Context mContext;
    private MediaPlayer mPlayer;
    private MusicSearchApi.Song mCurrent;
    private final List<MusicSearchApi.Song> mPlaylist = new ArrayList<>();
    private final List<MusicSearchApi.Song> mHistory = new ArrayList<>();
    private int mCurrentIndex = -1;
    private boolean mPaused = true;
    private int mPlayMode = 0; // 0=列表循环, 1=单曲循环, 2=随机
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mProgressRunner;

    public interface PlayerCallback {
        void onPlayStateChanged(boolean playing);
        void onProgressChanged(int position, int duration);
        void onSongChanged(MusicSearchApi.Song song, int index);
    }

    private List<PlayerCallback> mCallbacks = new ArrayList<>();

    public static synchronized MusicPlayerManager get(Context ctx) {
        if (sInstance == null) {
            sInstance = new MusicPlayerManager(ctx.getApplicationContext());
        }
        return sInstance;
    }

    private MusicPlayerManager(Context ctx) {
        mContext = ctx;
    }

    public void addCallback(PlayerCallback cb) { mCallbacks.add(cb); }
    public void removeCallback(PlayerCallback cb) { mCallbacks.remove(cb); }

    private void notifyStateChanged(boolean playing) {
        for (PlayerCallback cb : mCallbacks) cb.onPlayStateChanged(playing);
    }

    private void notifyProgress(int pos, int dur) {
        for (PlayerCallback cb : mCallbacks) cb.onProgressChanged(pos, dur);
    }

    private void notifySongChanged() {
        for (PlayerCallback cb : mCallbacks) cb.onSongChanged(mCurrent, mCurrentIndex);
    }

    public MediaPlayer getPlayer() { return mPlayer; }
    public MusicSearchApi.Song getCurrent() { return mCurrent; }
    public boolean isPlaying() { return mPlayer != null && mPlayer.isPlaying(); }
    public boolean isPaused() { return mPaused; }
    public int getPosition() { return mPlayer != null ? mPlayer.getCurrentPosition() : 0; }
    public int getDuration() { return mPlayer != null ? mPlayer.getDuration() : 0; }
    public List<MusicSearchApi.Song> getPlaylist() { return mPlaylist; }
    public List<MusicSearchApi.Song> getHistory() { return mHistory; }

    public void play(MusicSearchApi.Song song) {
        stopPlayer();
        mCurrent = song;

        int idx = findInPlaylist(song.id);
        if (idx >= 0) {
            mCurrentIndex = idx;
        } else {
            mPlaylist.add(song);
            mCurrentIndex = mPlaylist.size() - 1;
        }

        addToHistory(song);
        loadAndPlay(song);
    }

    public void playUrl(String url) {
        MusicLog.i("Player", "playUrl: " + url.substring(0, Math.min(60, url.length())));
        stopPlayer();
        mPlayer = new MediaPlayer();
        try {
            mPlayer.setDataSource(url);
            mPlayer.setLooping(false);
            mPlayer.setOnPreparedListener(mp -> {
                mPaused = false;
                mp.start();
                MusicLog.i("Player", "prepared OK, started playback");
                notifyStateChanged(true);
                notifySongChanged();
                startProgressRunner();
            });
            mPlayer.setOnCompletionListener(mp -> {
                MusicLog.i("Player", "playback completed");
                stopProgressRunner();
                notifyStateChanged(false);
                next();
            });
            mPlayer.setOnErrorListener((mp, what, extra) -> {
                MusicLog.e("Player", "MediaPlayer error what=" + what + " extra=" + extra);
                stopProgressRunner();
                notifyStateChanged(false);
                return false;
            });
            mPlayer.prepareAsync();
        } catch (Exception e) {
            MusicLog.e("Player", "playUrl exception", e);
            mPlayer = null;
        }
    }

    private void loadAndPlay(final MusicSearchApi.Song song) {
        MusicLog.i("Player", "loadAndPlay: " + song.title + " - " + song.artist + " hash=" + song.hash + " platform=" + song.platform);
        MusicSearchApi.PlayUrlCallback cb = new MusicSearchApi.PlayUrlCallback() {
            @Override
            public void onUrl(String url) {
                MusicLog.i("Player", "got playUrl for " + song.title + ": " + (url != null ? url.substring(0, Math.min(60, url.length())) + "..." : "null"));
                if (mCurrent == song && url != null && !url.isEmpty()) {
                    playUrl(url);
                }
            }
            @Override
            public void onError(String msg) {
                MusicLog.e("Player", "playUrl failed for " + song.title + ": " + msg);
                stopProgressRunner();
                notifyStateChanged(false);
            }
        };
        if (song.platform == 0) {
            MusicSearchApi.getKugouPlayUrl(song.hash, cb);
        } else {
            MusicSearchApi.getKuwoPlayUrl(song.hash, cb);
        }
    }

    public void togglePause() {
        if (mPlayer == null || mCurrent == null) return;
        if (mPlayer.isPlaying()) {
            pause();
        } else {
            resume();
        }
    }

    public void pause() {
        if (mPlayer == null || !mPlayer.isPlaying()) return;
        mPaused = true;
        mPlayer.pause();
        stopProgressRunner();
        notifyStateChanged(false);
    }

    public void resume() {
        if (mPlayer == null || !mPaused) return;
        mPaused = false;
        mPlayer.start();
        startProgressRunner();
        notifyStateChanged(true);
    }

    public int getPlayMode() { return mPlayMode; }

    public void setPlayMode(int mode) {
        mPlayMode = mode % 3;
        if (mPlayer != null) mPlayer.setLooping(mPlayMode == 1);
    }

    public void next() {
        if (mPlaylist.isEmpty()) return;
        mCurrentIndex = (mCurrentIndex + 1) % mPlaylist.size();
        MusicSearchApi.Song song = mPlaylist.get(mCurrentIndex);
        addToHistory(song);
        stopPlayer();
        mCurrent = song;
        loadAndPlay(song);
    }

    public void prev() {
        if (mPlaylist.isEmpty()) return;
        mCurrentIndex = mCurrentIndex <= 0 ? mPlaylist.size() - 1 : mCurrentIndex - 1;
        MusicSearchApi.Song song = mPlaylist.get(mCurrentIndex);
        addToHistory(song);
        stopPlayer();
        mCurrent = song;
        loadAndPlay(song);
    }

    public void seekTo(int pos) {
        if (mPlayer != null) {
            mPlayer.seekTo(pos);
        }
    }

    public void refetchWithQuality(MusicSearchApi.Song song, int quality) {
        if (mCurrent == null || !mCurrent.id.equals(song.id)) return;
        boolean wasPlaying = isPlaying();
        int pos = getPosition();
        stopPlayer();
        mCurrent = song;
        loadAndPlay(song);
        if (wasPlaying && pos > 0) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (mPlayer != null && mPlayer.isPlaying()) mPlayer.seekTo(pos);
            }, 500);
        }
    }

    public void stopPlayer() {
        stopProgressRunner();
        mPaused = true;
        if (mPlayer != null) {
            try {
                if (mPlayer.isPlaying()) mPlayer.stop();
                mPlayer.release();
            } catch (Throwable ignored) {}
            mPlayer = null;
        }
    }

    public void playFromPlaylist(int index) {
        if (index < 0 || index >= mPlaylist.size()) return;
        mCurrentIndex = index;
        MusicSearchApi.Song song = mPlaylist.get(index);
        addToHistory(song);
        stopPlayer();
        mCurrent = song;
        loadAndPlay(song);
    }

    public void download(MusicSearchApi.Song song) {
        download(song, null);
    }

    public void download(MusicSearchApi.Song song, String directUrl) {
        if (directUrl != null && !directUrl.isEmpty()) {
            enqueueDownload(song, directUrl);
            return;
        }
        MusicSearchApi.PlayUrlCallback cb = new MusicSearchApi.PlayUrlCallback() {
            @Override
            public void onUrl(String url) {
                enqueueDownload(song, url);
            }
            @Override
            public void onError(String msg) {}
        };
        if (song.platform == 0) {
            MusicSearchApi.getKugouPlayUrl(song.hash, cb);
        } else {
            MusicSearchApi.getKuwoPlayUrl(song.hash, cb);
        }
    }

    private void enqueueDownload(MusicSearchApi.Song song, String url) {
        if (url == null || url.isEmpty()) return;
        try {
            DownloadManager dm = (DownloadManager) mContext.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            String ext = url.contains("flac") || url.contains("FLAC") ? ".flac" :
                         url.contains("aac") || url.contains("AAC") ? ".aac" : ".mp3";
            String filename = song.title + " - " + song.artist + ext;
            filename = filename.replaceAll("[\\\\/:*?\"<>|]", "_");
            req.setTitle("下载: " + filename);
            req.setDescription(song.title + " - " + song.artist);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, filename);
            if (dm != null) dm.enqueue(req);
        } catch (Exception e) {}
    }

    private void addToHistory(MusicSearchApi.Song song) {
        mHistory.removeIf(s -> s.id.equals(song.id));
        mHistory.add(0, song);
        if (mHistory.size() > 50) {
            mHistory.remove(mHistory.size() - 1);
        }
    }

    private int findInPlaylist(String id) {
        for (int i = 0; i < mPlaylist.size(); i++) {
            if (mPlaylist.get(i).id.equals(id)) return i;
        }
        return -1;
    }

    private void startProgressRunner() {
        stopProgressRunner();
        mProgressRunner = new Runnable() {
            @Override
            public void run() {
                if (mPlayer != null && mPlayer.isPlaying()) {
                    notifyProgress(mPlayer.getCurrentPosition(), mPlayer.getDuration());
                    mHandler.postDelayed(this, 500);
                }
            }
        };
        mHandler.post(mProgressRunner);
    }

    private void stopProgressRunner() {
        if (mProgressRunner != null) {
            mHandler.removeCallbacks(mProgressRunner);
        }
    }
}
