package com.leshao.v3.music;

import android.content.SharedPreferences;

import com.leshao.v3.ContextManager;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 「在线音乐 / 点歌」功能偏好。
 *
 * <p>点歌指令仅在白名单会话内生效;白名单为空时不触发。误报时长默认 60 秒,可自定义 1-60 秒
 * (对应微信语音 60 秒上限)。</p>
 */
public final class OnlineMusicPrefs {

    public static final String K_ENABLED = "ls_kge_enabled";
    public static final String K_WHITELIST = "ls_kge_whitelist";
    public static final String K_ALIASES = "ls_kge_aliases";
    public static final String K_FALSE_DUR = "ls_kge_false_dur_sec";
    public static final String K_QUALITY = "ls_kge_quality";
    public static final String K_NOTICE = "ls_kge_notice";
    public static final String K_NOTICE_TEXT = "ls_kge_notice_text";
    public static final String K_AUTO_DOWNLOAD = "ls_om_auto_download";
    public static final String K_FAVORITES = "ls_om_favorites";
    public static final String K_SEARCH_HISTORY = "ls_om_search_history";
    // v1105: 播放状态持久化
    public static final String K_PLAY_QUEUE = "ls_om_play_queue";
    public static final String K_PLAY_INDEX = "ls_om_play_index";
    public static final String K_PLAY_POS = "ls_om_play_pos";
    public static final String K_PLAY_MODE = "ls_om_play_mode";
    public static final String K_PLAY_PAUSED = "ls_om_play_paused";
    public static final String K_AUTO_RANDOM = "ls_om_auto_random";

    /** 搜索历史最多保留条数。 */
    private static final int MAX_SEARCH_HISTORY = 20;
    /** 搜索历史内部存储分隔符（SOH，关键词不会包含）。 */
    private static final String HIST_SEP = "\u0001";

    public static final String DEFAULT_ALIASES = "点歌,点唱,来一首,我想听";
    /** 点歌处理前的提示语默认文案; 支持 {song} 占位符代表歌名。 */
    public static final String DEFAULT_NOTICE_TEXT = "正在点歌…";

    private OnlineMusicPrefs() {}

    private static SharedPreferences sp() {
        return ContextManager.getPrefs();
    }

    // ---------- 点歌 ----------

    public static boolean enabled() {
        try { return sp().getBoolean(K_ENABLED, false); } catch (Throwable t) { return false; }
    }

    public static void setEnabled(boolean v) {
        try { sp().edit().putBoolean(K_ENABLED, v).apply(); } catch (Throwable ignored) {}
    }

    public static boolean notice() {
        try { return sp().getBoolean(K_NOTICE, true); } catch (Throwable t) { return true; }
    }

    public static void setNotice(boolean v) {
        try { sp().edit().putBoolean(K_NOTICE, v).apply(); } catch (Throwable ignored) {}
    }

    /** 点歌提示语内容,支持 {song} 占位符; 默认「正在点歌…」。 */
    public static String noticeText() {
        try {
            String s = sp().getString(K_NOTICE_TEXT, DEFAULT_NOTICE_TEXT);
            return (s == null || s.trim().isEmpty()) ? DEFAULT_NOTICE_TEXT : s;
        } catch (Throwable t) {
            return DEFAULT_NOTICE_TEXT;
        }
    }

    public static void setNoticeText(String v) {
        try {
            String s = (v == null || v.trim().isEmpty()) ? DEFAULT_NOTICE_TEXT : v;
            sp().edit().putString(K_NOTICE_TEXT, s).apply();
        } catch (Throwable ignored) {}
    }

    public static boolean autoDownload() {
        try { return sp().getBoolean(K_AUTO_DOWNLOAD, false); } catch (Throwable t) { return false; }
    }

    public static void setAutoDownload(boolean v) {
        try { sp().edit().putBoolean(K_AUTO_DOWNLOAD, v).apply(); } catch (Throwable ignored) {}
    }

    /** 点歌/试听音质档:auto / lossless / exhigh / standard,默认 auto(优先最高,失败逐级降档)。 */
    public static String quality() {
        try {
            String q = sp().getString(K_QUALITY, KuwoMusicApi.Q_AUTO);
            if (q == null || q.isEmpty()) return KuwoMusicApi.Q_AUTO;
            return q;
        } catch (Throwable t) {
            return KuwoMusicApi.Q_AUTO;
        }
    }

    public static void setQuality(String q) {
        try { sp().edit().putString(K_QUALITY, q == null ? KuwoMusicApi.Q_AUTO : q).apply(); } catch (Throwable ignored) {}
    }

    // ---------- 收藏 ----------

    /** 收藏的歌曲 id 集合。 */
    public static Set<String> favorites() {
        String s;
        try { s = sp().getString(K_FAVORITES, ""); } catch (Throwable t) { s = ""; }
        return split(s, false);
    }

    public static boolean isFavorite(String songId) {
        return songId != null && !songId.isEmpty() && favorites().contains(songId);
    }

    /** 切换收藏状态, 返回切换后是否为已收藏。 */
    public static boolean toggleFavorite(String songId) {
        if (songId == null || songId.isEmpty()) return false;
        Set<String> f = new LinkedHashSet<>(favorites());
        boolean added;
        if (f.contains(songId)) { f.remove(songId); added = false; }
        else { f.add(songId); added = true; }
        try { sp().edit().putString(K_FAVORITES, String.join(",", f)).apply(); } catch (Throwable ignored) {}
        return added;
    }

    // ---------- 搜索历史 ----------

    /** 搜索历史（最近在前）。 */
    public static java.util.List<String> searchHistory() {
        String s;
        try { s = sp().getString(K_SEARCH_HISTORY, ""); } catch (Throwable t) { s = ""; }
        java.util.List<String> out = new java.util.ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String p : s.split(HIST_SEP)) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** 记录一次搜索（去重后置顶，最多 {@link #MAX_SEARCH_HISTORY} 条）。 */
    public static void addSearchHistory(String keyword) {
        if (keyword == null) return;
        String k = keyword.trim();
        if (k.isEmpty()) return;
        java.util.List<String> list = searchHistory();
        list.remove(k);
        list.add(0, k);
        while (list.size() > MAX_SEARCH_HISTORY) list.remove(list.size() - 1);
        try { sp().edit().putString(K_SEARCH_HISTORY, String.join(HIST_SEP, list)).apply(); } catch (Throwable ignored) {}
    }

    public static void clearSearchHistory() {
        try { sp().edit().remove(K_SEARCH_HISTORY).apply(); } catch (Throwable ignored) {}
    }

    /** 误报时长(秒),范围 1-60,默认 60。 */
    public static int falseDurSec() {
        int v;
        try {
            v = sp().getInt(K_FALSE_DUR, 60);
        } catch (Throwable t) {
            v = 60;
        }
        if (v < 1) v = 1;
        if (v > 60) v = 60;
        return v;
    }

    public static void setFalseDurSec(int sec) {
        if (sec < 1) sec = 1;
        if (sec > 60) sec = 60;
        try { sp().edit().putInt(K_FALSE_DUR, sec).apply(); } catch (Throwable ignored) {}
    }

    /** 点歌指令别名前缀集合。 */
    public static Set<String> aliases() {
        String s;
        try {
            s = sp().getString(K_ALIASES, DEFAULT_ALIASES);
        } catch (Throwable t) {
            s = DEFAULT_ALIASES;
        }
        return split(s, true);
    }

    public static void setAliases(String csv) {
        try {
            String v = (csv == null || csv.trim().isEmpty()) ? DEFAULT_ALIASES : csv;
            sp().edit().putString(K_ALIASES, v).apply();
        } catch (Throwable ignored) {}
    }

    /** 点歌白名单会话(好友/群)。 */
    public static Set<String> whitelist() {
        String s;
        try {
            s = sp().getString(K_WHITELIST, "");
        } catch (Throwable t) {
            s = "";
        }
        return split(s, false);
    }

    public static void setWhitelist(String csv) {
        try { sp().edit().putString(K_WHITELIST, csv == null ? "" : csv).apply(); } catch (Throwable ignored) {}
    }

    public static boolean inWhitelist(String talker) {
        if (talker == null || talker.isEmpty()) return false;
        Set<String> wl = whitelist();
        // 白名单为空时不触发点歌(符合「仅白名单会话生效」预期)
        return !wl.isEmpty() && wl.contains(talker);
    }

    // ---------- 播放状态持久化 ----------

    private static final String SONG_SEP = "\u0002";
    private static final String FIELD_SEP = "\u0001";

    /** 自动随机播放开关,默认开启:进入在线音乐即随机播放一首。 */
    public static boolean autoRandom() {
        try { return sp().getBoolean(K_AUTO_RANDOM, true); } catch (Throwable t) { return true; }
    }

    public static void setAutoRandom(boolean v) {
        try { sp().edit().putBoolean(K_AUTO_RANDOM, v).apply(); } catch (Throwable ignored) {}
    }

    private static String encField(String s) {
        return s == null ? "" : s.replace(FIELD_SEP, " ").replace(SONG_SEP, " ");
    }

    private static String encodeSong(KuwoMusicApi.Song s) {
        if (s == null) return "";
        return encField(s.id) + FIELD_SEP + encField(s.title) + FIELD_SEP + encField(s.artist)
                + FIELD_SEP + encField(s.album) + FIELD_SEP + encField(s.artwork)
                + FIELD_SEP + encField(s.formats) + FIELD_SEP + s.durationMs;
    }

    private static KuwoMusicApi.Song decodeSong(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String[] p = raw.split(FIELD_SEP, -1);
        if (p.length < 4) return null;
        KuwoMusicApi.Song s = new KuwoMusicApi.Song();
        s.id = p[0];
        s.title = p[1];
        s.artist = p[2];
        s.album = p[3];
        if (p.length > 4) s.artwork = p[4];
        if (p.length > 5) s.formats = p[5];
        if (p.length > 6) { try { s.durationMs = Integer.parseInt(p[6]); } catch (Throwable ignored) {} }
        return s;
    }

    /** 保存当前播放状态(队列/下标/进度/模式/暂停态)。 */
    public static void savePlayback(java.util.List<KuwoMusicApi.Song> queue, int index,
                                    int positionMs, int mode, boolean paused) {
        try {
            StringBuilder sb = new StringBuilder();
            if (queue != null) {
                for (KuwoMusicApi.Song s : queue) {
                    if (s == null) continue;
                    if (sb.length() > 0) sb.append(SONG_SEP);
                    sb.append(encodeSong(s));
                }
            }
            sp().edit()
                    .putString(K_PLAY_QUEUE, sb.toString())
                    .putInt(K_PLAY_INDEX, index)
                    .putInt(K_PLAY_POS, Math.max(0, positionMs))
                    .putInt(K_PLAY_MODE, mode <= 0 ? 1 : mode)
                    .putBoolean(K_PLAY_PAUSED, paused)
                    .apply();
        } catch (Throwable ignored) {}
    }

    public static java.util.List<KuwoMusicApi.Song> playbackQueue() {
        java.util.List<KuwoMusicApi.Song> out = new java.util.ArrayList<>();
        try {
            String raw = sp().getString(K_PLAY_QUEUE, "");
            if (raw == null || raw.isEmpty()) return out;
            for (String part : raw.split(SONG_SEP)) {
                KuwoMusicApi.Song s = decodeSong(part);
                if (s != null && s.id != null && !s.id.isEmpty()) out.add(s);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static int playbackIndex() {
        try { return sp().getInt(K_PLAY_INDEX, -1); } catch (Throwable t) { return -1; }
    }

    public static int playbackPosition() {
        try { return sp().getInt(K_PLAY_POS, 0); } catch (Throwable t) { return 0; }
    }

    public static int playbackMode() {
        try { return sp().getInt(K_PLAY_MODE, 1); } catch (Throwable t) { return 1; }
    }

    public static boolean playbackPaused() {
        try { return sp().getBoolean(K_PLAY_PAUSED, true); } catch (Throwable t) { return true; }
    }

    // ---------- 工具 ----------

    private static Set<String> split(String csv, boolean lower) {
        if (csv == null || csv.trim().isEmpty()) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        for (String p : csv.split("[,，、\\s]+")) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            out.add(lower ? t : t);
        }
        return out;
    }
}
