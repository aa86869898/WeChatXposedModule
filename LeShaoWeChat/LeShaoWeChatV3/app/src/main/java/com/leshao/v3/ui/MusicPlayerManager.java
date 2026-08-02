package com.leshao.v3.ui;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

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
    private SharedPreferences mPrefs;
    private int mLastPosition = 0;

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
        mPrefs = ctx.getSharedPreferences("music_player", Context.MODE_PRIVATE);
        restoreState();
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
        saveState();
    }

    public void playUrl(String url) {
        if (url == null || url.isEmpty()) {
            MusicLog.e("Player", "playUrl: url is null or empty");
            stopProgressRunner();
            notifyStateChanged(false);
            mHandler.post(() -> Toast.makeText(mContext, "获取音源失败", Toast.LENGTH_SHORT).show());
            return;
        }
        MusicLog.i("Player", "playUrl: " + url.substring(0, Math.min(60, url.length())));
        stopPlayer();
        mPlayer = new MediaPlayer();
        try {
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            headers.put("Referer", "https://www.kugou.com/");
            mPlayer.setDataSource(mContext, Uri.parse(url), headers);
            mPlayer.setLooping(false);
            mPlayer.setOnPreparedListener(mp -> {
                mPaused = false;
                if (mLastPosition > 0) {
                    mp.seekTo(mLastPosition);
                    mLastPosition = 0;
                }
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
        MusicLog.i("Player", "loadAndPlay: " + (song.title != null ? song.title : "?") + " - " + (song.artist != null ? song.artist : "?") + " hash=" + (song.hash != null ? song.hash : "?"));
        MusicSearchApi.getKugouPlayUrl(song.hash != null ? song.hash : "", "exhigh", new MusicSearchApi.PlayUrlCallback() {
            @Override
            public void onUrl(String url) {
                if (url == null || url.isEmpty()) {
                    MusicLog.e("Player", "Kugou onUrl: url is null/empty");
                    MusicActivity.toast("获取音源失败");
                    stopProgressRunner();
                    notifyStateChanged(false);
                    return;
                }
                MusicLog.i("Player", "Kugou playUrl OK: " + url.substring(0, Math.min(60, url.length())));
                if (mCurrent == song) {
                    playUrl(url);
                }
            }
            @Override
            public void onError(String msg) {
                MusicLog.e("Player", "Kugou playUrl failed: " + msg);
                MusicActivity.toast("获取音源失败");
                stopProgressRunner();
                notifyStateChanged(false);
            }
        });
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
        mLastPosition = mPlayer.getCurrentPosition();
        mPlayer.pause();
        stopProgressRunner();
        notifyStateChanged(false);
        saveState();
    }

    public void resume() {
        if (mPlayer == null || !mPaused) return;
        mPaused = false;
        mPlayer.start();
        startProgressRunner();
        notifyStateChanged(true);
        saveState();
    }

    public int getPlayMode() { return mPlayMode; }

    public void setPlayMode(int mode) {
        mPlayMode = mode % 3;
        if (mPlayer != null) mPlayer.setLooping(mPlayMode == 1);
        saveState();
    }

    public void next() {
        try {
            if (mPlaylist.isEmpty()) return;
            mCurrentIndex = (mCurrentIndex + 1) % mPlaylist.size();
            MusicSearchApi.Song song = mPlaylist.get(mCurrentIndex);
            if (song == null) return;
            addToHistory(song);
            stopPlayer();
            mCurrent = song;
            mLastPosition = 0;
            loadAndPlay(song);
            saveState();
        } catch (Throwable e) {
            MusicLog.e("Player", "next crash: " + e.getMessage());
        }
    }

    public void prev() {
        try {
            if (mPlaylist.isEmpty()) return;
            mCurrentIndex = mCurrentIndex <= 0 ? mPlaylist.size() - 1 : mCurrentIndex - 1;
            MusicSearchApi.Song song = mPlaylist.get(mCurrentIndex);
            if (song == null) return;
            addToHistory(song);
            stopPlayer();
            mCurrent = song;
            mLastPosition = 0;
            loadAndPlay(song);
            saveState();
        } catch (Throwable e) {
            MusicLog.e("Player", "prev crash: " + e.getMessage());
        }
    }

    public void seekTo(int pos) {
        if (mPlayer != null) {
            mPlayer.seekTo(pos);
            mLastPosition = pos;
            saveState();
        }
    }

    public void refetchWithQuality(MusicSearchApi.Song song, int quality) {
        if (mCurrent == null || song == null || mCurrent.id == null || song.id == null || !mCurrent.id.equals(song.id)) return;
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
        if (song == null) return;
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
        MusicSearchApi.getKugouPlayUrl(song.hash, cb);
    }

    private void enqueueDownload(MusicSearchApi.Song song, String url) {
        if (song == null || url == null || url.isEmpty()) return;
        try {
            DownloadManager dm = (DownloadManager) mContext.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            String ext = url.contains("flac") || url.contains("FLAC") ? ".flac" :
                         url.contains("aac") || url.contains("AAC") ? ".aac" : ".mp3";
            String title = song.title != null && !song.title.isEmpty() ? song.title : "未知歌曲";
            String artist = song.artist != null && !song.artist.isEmpty() ? song.artist : "未知歌手";
            String filename = title + " - " + artist + ext;
            filename = filename.replaceAll("[\\\\/:*?\"<>|]", "_");
            req.setTitle("下载: " + filename);
            req.setDescription(title + " - " + artist);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "乐少助手/" + filename);
            if (dm != null) dm.enqueue(req);
        } catch (Exception e) {}
    }

    private void addToHistory(MusicSearchApi.Song song) {
        if (song == null) return;
        mHistory.removeIf(s -> s != null && s.id != null && song.id != null && s.id.equals(song.id));
        mHistory.add(0, song);
        if (mHistory.size() > 50) {
            mHistory.remove(mHistory.size() - 1);
        }
    }

    private int findInPlaylist(String id) {
        if (id == null) return -1;
        for (int i = 0; i < mPlaylist.size(); i++) {
            MusicSearchApi.Song s = mPlaylist.get(i);
            if (s != null && id.equals(s.id)) return i;
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

    private void saveState() {
        try {
            JSONObject root = new JSONObject();
            root.put("mode", mPlayMode);
            root.put("index", mCurrentIndex);
            if (mPlayer != null && mPlayer.isPlaying()) {
                root.put("position", mPlayer.getCurrentPosition());
            } else {
                root.put("position", mLastPosition);
            }
            root.put("paused", mPaused);

            JSONArray arr = new JSONArray();
            for (MusicSearchApi.Song s : mPlaylist) {
                JSONObject so = new JSONObject();
                so.put("id", s.id != null ? s.id : "");
                so.put("hash", s.hash != null ? s.hash : "");
                so.put("hash320", s.hash320 != null ? s.hash320 : "");
                so.put("sqHash", s.sqHash != null ? s.sqHash : "");
                so.put("originHash", s.originHash != null ? s.originHash : "");
                so.put("albumId", s.albumId != null ? s.albumId : "");
                so.put("albumAudioId", s.albumAudioId != null ? s.albumAudioId : "");
                so.put("title", s.title != null ? s.title : "");
                so.put("artist", s.artist != null ? s.artist : "");
                so.put("cover", s.cover != null ? s.cover : "");
                so.put("duration", s.duration);
                so.put("platform", s.platform);
                arr.put(so);
            }
            root.put("playlist", arr);

            // save simplified history (last 20)
            JSONArray histArr = new JSONArray();
            int histCount = Math.min(mHistory.size(), 20);
            for (int i = 0; i < histCount; i++) {
                MusicSearchApi.Song s = mHistory.get(i);
                JSONObject ho = new JSONObject();
                ho.put("title", s.title != null ? s.title : "");
                ho.put("artist", s.artist != null ? s.artist : "");
                ho.put("cover", s.cover != null ? s.cover : "");
                ho.put("hash", s.hash != null ? s.hash : "");
                histArr.put(ho);
            }
            root.put("history", histArr);

            mPrefs.edit().putString("state", root.toString()).apply();
        } catch (Exception e) {
            MusicLog.e("PlayerManager", "saveState error", e);
        }
    }

    private void restoreState() {
        try {
            String json = mPrefs.getString("state", "");
            if (json.isEmpty()) return;
            JSONObject root = new JSONObject(json);
            mPlayMode = root.optInt("mode", 0);
            mCurrentIndex = root.optInt("index", -1);
            mLastPosition = root.optInt("position", 0);
            mPaused = root.optBoolean("paused", true);

            JSONArray arr = root.optJSONArray("playlist");
            if (arr != null && arr.length() > 0) {
                mPlaylist.clear();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject so = arr.getJSONObject(i);
                    MusicSearchApi.Song s = new MusicSearchApi.Song();
                    s.id = so.optString("id", "");
                    s.hash = so.optString("hash", "");
                    s.hash320 = so.optString("hash320", "");
                    s.sqHash = so.optString("sqHash", "");
                    s.originHash = so.optString("originHash", "");
                    s.albumId = so.optString("albumId", "");
                    s.albumAudioId = so.optString("albumAudioId", "");
                    s.title = so.optString("title", "");
                    s.artist = so.optString("artist", "");
                    s.cover = so.optString("cover", "");
                    s.duration = so.optInt("duration", 0);
                    s.platform = so.optInt("platform", 0);
                    mPlaylist.add(s);
                }
                if (mCurrentIndex >= 0 && mCurrentIndex < mPlaylist.size()) {
                    mCurrent = mPlaylist.get(mCurrentIndex);
                }
            }

            JSONArray histArr = root.optJSONArray("history");
            if (histArr != null) {
                mHistory.clear();
                for (int i = 0; i < histArr.length(); i++) {
                    JSONObject ho = histArr.getJSONObject(i);
                    MusicSearchApi.Song s = new MusicSearchApi.Song();
                    s.title = ho.optString("title", "");
                    s.artist = ho.optString("artist", "");
                    s.cover = ho.optString("cover", "");
                    s.hash = ho.optString("hash", "");
                    mHistory.add(s);
                }
            }
        } catch (Exception e) {
            MusicLog.e("PlayerManager", "restoreState error", e);
        }
    }
}
