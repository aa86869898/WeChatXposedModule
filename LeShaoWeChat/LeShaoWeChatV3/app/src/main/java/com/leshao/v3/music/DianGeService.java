package com.leshao.v3.music;

import android.content.Context;
import android.os.Environment;

import com.leshao.ai.hook.wechat.WeChatMessenger;
import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.GroupFeatures;
import com.leshao.v3.hook.TtsVoiceSender;

import java.io.File;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「点歌」自动化服务。
 *
 * <p>在群里/好友发「点歌 歌名」(支持别名前缀,仅白名单会话生效),系统自动:搜索酷我 →
 * 择优取流(默认 128K 优先,320K 兜底) → 下载 → 解码为 24kHz 单声道 PCM →
 * 转 SILK 语音消息发出。已编码语音会按曲目缓存,同曲再次点歌直接复用,省去下载与编码。
 * 语音时长按「误报时长」上报(默认 60 秒,可自定义 1-60 秒),以规避微信 60 秒上限。</p>
 */
public final class DianGeService {

    private static final String TAG = "LsDianGe";

    /** 指令后歌名最大长度,超过视为普通聊天不触发。 */
    private static final int MAX_KEYWORD_LEN = 40;
    /** 同一会话同一歌名的触发冷却(毫秒)。 */
    private static final long DEDUP_WINDOW_MS = 15000L;
    /** 点歌缓存目录最多保留的音频文件数, 超出按最近使用时间清理最旧。 */
    private static final int MAX_CACHE_FILES = 24;
    /** 语音缓存目录最多保留的已编码语音数。 */
    private static final int MAX_VOICE_CACHE_FILES = 24;

    /**
     * 每会话允许排队等待的点歌任务上限(不含正在执行的那个); 超出即拒绝新请求。
     * <p>v1102: 点歌从「全局并发」改为「每会话串行」——同一会话内一次只处理一个任务,
     * 不同会话之间互不阻塞。</p>
     */
    private static final int MAX_QUEUE_PER_SESSION = 10;
    /** 点歌执行线程池: 每会话串行 worker、手动发送与受理提示共用, 可弹性扩容。 */
    private static final ExecutorService POOL = Executors.newCachedThreadPool();
    /** 会话 → 串行任务队列。 */
    private static final Map<String, SessionQueue> sQueues = new ConcurrentHashMap<>();
    private static final Map<String, Long> sRecent = new ConcurrentHashMap<>();

    private DianGeService() {}

    /** 单个点歌任务。 */
    private static final class SongTask {
        final ClassLoader cl;
        final String talker;
        final String keyword;
        final Object quoted;
        SongTask(ClassLoader cl, String talker, String keyword, Object quoted) {
            this.cl = cl;
            this.talker = talker;
            this.keyword = keyword;
            this.quoted = quoted;
        }
    }

    /** 会话级串行队列: 保证同一会话的点歌任务严格按入队顺序逐个执行。 */
    private static final class SessionQueue {
        final String talker;
        final ArrayDeque<SongTask> queue = new ArrayDeque<>();
        boolean running = false;
        SessionQueue(String talker) { this.talker = talker; }
    }

    /** 兼容旧调用: 不带被引用原消息, 受理提示回退普通文本。 */
    public static boolean maybeHandle(ClassLoader cl, String talker, String content) {
        return maybeHandle(cl, talker, content, null);
    }

    /**
     * 判断并处理点歌指令。
     *
     * @param quotedMsg 接收链拿到的原消息对象(e9), 用于发送微信原生引用受理提示; 为空则回退文本
     * @return true 表示已识别为点歌指令(已接管, 调用方无需再走关键词回复)
     */
    public static boolean maybeHandle(ClassLoader cl, String talker, String content, Object quotedMsg) {
        if (talker == null || content == null || content.isEmpty()) return false;
        if (!OnlineMusicPrefs.enabled()) {
            if (looksLikeCommand(talker, content)) {
                LogWriter.log(TAG, "点歌功能未启用, 忽略指令 talker=" + talker);
            }
            return false;
        }

        String keyword = matchKeyword(stripSenderPrefix(talker, content));
        if (keyword == null) return false;
        if (keyword.isEmpty()) {
            LogWriter.log(TAG, "命中别名但歌名为空,忽略 talker=" + talker);
            return false;
        }
        if (keyword.length() > MAX_KEYWORD_LEN) {
            LogWriter.log(TAG, "歌名过长(" + keyword.length() + "),忽略: " + keyword);
            return false;
        }
        if (!OnlineMusicPrefs.inWhitelist(talker)) {
            LogWriter.log(TAG, "非白名单会话,忽略点歌 talker=" + talker + " kw=" + keyword);
            return false;
        }

        String key = talker + "|" + keyword;
        long now = System.currentTimeMillis();
        // v1103: 原子去重(put 返回旧值)。MessageHook 与 Trigger 两条接收链可能并发命中同一条
        // 点歌消息, 原子操作保证仅第一条真正入队, 避免重复受理提示与重复任务。
        Long last = sRecent.put(key, now);
        if (last != null && now - last < DEDUP_WINDOW_MS) {
            LogWriter.log(TAG, "冷却中,忽略重复点歌: " + keyword);
            return true;
        }
        if (sRecent.size() > 200) {
            int removed = 0;
            java.util.Iterator<Map.Entry<String, Long>> it = sRecent.entrySet().iterator();
            while (it.hasNext() && removed < 100) {
                Map.Entry<String, Long> e = it.next();
                if (now - e.getValue() > DEDUP_WINDOW_MS) { it.remove(); removed++; }
            }
        }

        // v1102: 每会话串行入队。同一会话内一次只处理一个任务, 处理完再取下一个;
        // 不同会话各自维护队列, 互不阻塞。ahead = 排在本任务前面的任务数(含正在执行的)。
        SessionQueue q = sQueues.computeIfAbsent(talker, SessionQueue::new);
        int ahead;
        boolean start;
        synchronized (q) {
            if (q.queue.size() >= MAX_QUEUE_PER_SESSION) {
                LogWriter.log(TAG, "点歌队列已满,拒绝: talker=" + talker + " kw=" + keyword);
                // 异步回复: 本方法可能在微信 DB 写锁内被调用(f9.Bb insert 钩子), 严禁锁内同步发送
                final ClassLoader fclFull = cl;
                POOL.execute(() -> reply(fclFull, talker, "点歌排队已满，请稍后再试"));
                return true;
            }
            ahead = (q.running ? 1 : 0) + q.queue.size();
            q.queue.addLast(new SongTask(cl, talker, keyword, quotedMsg));
            start = !q.running;
            if (start) q.running = true;
        }
        LogWriter.log(TAG, "接管点歌: talker=" + talker + " kw=" + keyword
                + " 前面=" + ahead + " 队列=" + q.queue.size());

        // 受理提示: 优先原生引用(引用点歌原消息), 失败回退文本; 入队即发, 用户即时可见
        if (OnlineMusicPrefs.notice()) {
            final ClassLoader fcl = cl;
            POOL.execute(() -> sendAcceptNotice(fcl, talker, keyword, quotedMsg, ahead));
        }
        if (start) {
            final SessionQueue fq = q;
            POOL.execute(() -> drain(fq));
        }
        return true;
    }

    /** 会话级串行 worker: 循环取出队首任务执行, 队列空则退出并标记空闲。 */
    private static void drain(SessionQueue q) {
        while (true) {
            SongTask task;
            synchronized (q) {
                task = q.queue.pollFirst();
                if (task == null) {
                    q.running = false;
                    return;
                }
            }
            try {
                run(task.cl, task.talker, task.keyword);
            } catch (Throwable t) {
                LogWriter.log(TAG, "点歌异常: " + t);
                reply(task.cl, task.talker, "点歌失败:" + shortErr(t));
            }
        }
    }

    /** 发送点歌受理提示: 优先微信原生引用(引用点歌原消息), 失败回退普通文本。 */
    private static void sendAcceptNotice(ClassLoader cl, String talker, String song,
                                         Object quoted, int ahead) {
        String text = buildNoticeText(song, ahead);
        boolean quotedOk = false;
        if (quoted != null) {
            try {
                quotedOk = WeChatMessenger.sendQuoteAndAt(quoted, talker, "", text, cl);
            } catch (Throwable t) {
                LogWriter.log(TAG, "原生引用受理提示失败: " + t.getMessage());
            }
        }
        if (quotedOk) {
            LogWriter.log(TAG, "受理提示(引用): " + text);
        } else {
            reply(cl, talker, text);
        }
    }

    /**
     * 纯判定：当前消息是否为该会话可执行的点歌指令（无任何副作用）。
     *
     * <p>供 AI 助手等其它接收链在送大模型前过滤点歌指令使用，避免点歌消息被当作普通
     * 聊天再次送去大模型。判定口径与 {@link #maybeHandle} 一致：功能已启用、命中别名
     * 前缀、歌名非空且不过长、且会话在白名单内。</p>
     */
    public static boolean isSongRequest(String talker, String content) {
        if (talker == null || content == null || content.isEmpty()) return false;
        if (!OnlineMusicPrefs.enabled()) return false;
        String keyword = matchKeyword(stripSenderPrefix(talker, content));
        if (keyword == null || keyword.isEmpty()) return false;
        if (keyword.length() > MAX_KEYWORD_LEN) return false;
        return OnlineMusicPrefs.inWhitelist(talker);
    }

    /**
     * 点歌功能已启用且内容形如点歌指令(不校验白名单)。
     *
     * <p>供 AI 接收链在「送大模型之前」过滤点歌指令使用: 命中即由点歌服务接管或直接丢弃,
     * 避免点歌消息被 AI 当作普通聊天回复。与 {@link #isSongRequest} 的区别是不校验白名单,
     * 因为非白名单会话的点歌指令同样不应送入 AI。</p>
     */
    public static boolean isEnabledCommand(String talker, String content) {
        if (talker == null || content == null || content.isEmpty()) return false;
        if (!OnlineMusicPrefs.enabled()) return false;
        return looksLikeCommand(talker, content);
    }

    // ==================== 手动发送（在线音乐页） ====================

    /**
     * 把指定歌曲以语音消息发送到多个会话（在线音乐页「发送」入口调用）。
     *
     * <p>手动触发，不受「启用点歌 / 白名单」限制；音质与误报时长沿用点歌偏好。</p>
     */
    public static void sendSongToTargets(final ClassLoader cl, final KuwoMusicApi.Song song,
                                         final List<String> talkers) {
        if (song == null || talkers == null || talkers.isEmpty()) return;
        POOL.execute(() -> {
            LogWriter.log(TAG, "手动发送 " + song.display() + " → " + talkers.size() + " 个会话");
            Fetched fetched = downloadWithFallback(song);
            if (fetched == null || fetched.file == null) {
                for (String t : talkers) reply(cl, t, "发送失败:未能获取「" + song.title + "」的音频");
                return;
            }
            File file = fetched.file;
            // 优先流水线转码(解码与编码重叠), 失败回退旧路径
            byte[] voiceData = null;
            TtsVoiceSender.VoiceEncodeResult vr = TtsVoiceSender.decodeAudioToVoiceData(file.getAbsolutePath());
            if (vr != null && vr.data != null && vr.data.length > 0) {
                voiceData = vr.data;
            } else {
                byte[] pcm;
                try {
                    pcm = TtsVoiceSender.decodeAudioToPcm(file.getAbsolutePath());
                } catch (Throwable t) {
                    pcm = null;
                    LogWriter.log(TAG, "手动解码异常: " + t.getMessage());
                }
                if (pcm == null || pcm.length == 0) {
                    for (String t : talkers) reply(cl, t, "发送失败:音频解码失败");
                    safeDelete(file);
                    return;
                }
                voiceData = TtsVoiceSender.encodePcmToVoiceData(pcm);
            }
            if (voiceData == null || voiceData.length == 0) {
                for (String t : talkers) reply(cl, t, "发送失败:音频编码失败");
                safeDelete(file);
                return;
            }
            int fakeMs = OnlineMusicPrefs.falseDurSec() * 1000;
            writeCache(voiceCacheFile(song, fetched.level), voiceData);
            int ok = 0;
            for (String t : talkers) {
                try {
                    if (TtsVoiceSender.sendVoiceData(t, voiceData, fakeMs, null)) ok++;
                } catch (Throwable e) {
                    LogWriter.log(TAG, "发送失败 talker=" + t + " " + e.getMessage());
                }
            }
            LogWriter.log(TAG, "手动发送完成 " + ok + "/" + talkers.size() + " 首=" + song.title);
            pruneCache(file.getParentFile());
            pruneCache(voiceDir(), MAX_VOICE_CACHE_FILES);
        });
    }

    // ==================== 主流程 ====================

    private static void run(ClassLoader cl, String talker, String keyword) {
        long t0 = System.currentTimeMillis();
        List<KuwoMusicApi.Song> list;
        try {
            list = KuwoMusicApi.search(keyword, 1);
        } catch (Throwable t) {
            LogWriter.log(TAG, "搜索失败: " + t.getMessage());
            reply(cl, talker, "点歌失败:搜索无响应");
            return;
        }
        if (list == null || list.isEmpty()) {
            LogWriter.log(TAG, "无搜索结果: " + keyword);
            reply(cl, talker, "没有找到「" + keyword + "」这首歌曲");
            return;
        }

        KuwoMusicApi.Song song = bestMatch(list, keyword);
        LogWriter.log(TAG, "选中: " + song.display() + " (id=" + song.id + ")");

        // v1102: 受理提示已改为入队时以「原生引用」发出(见 maybeHandle), 此处不再重复提示。

        // 语音缓存命中: 同曲同档已编码过, 直接发送, 跳过下载/解码/重采样/编码
        File cachedVoice = findVoiceCache(song);
        if (cachedVoice != null) {
            byte[] cached = readAllBytes(cachedVoice);
            if (cached != null && cached.length > 0) {
                int fakeMs0 = OnlineMusicPrefs.falseDurSec() * 1000;
                boolean ok = TtsVoiceSender.sendVoiceData(talker, cached, fakeMs0, null);
                LogWriter.log(TAG, "语音缓存命中直接发送 sent=" + ok + " 耗时="
                        + (System.currentTimeMillis() - t0) + "ms song=" + song.title);
                if (!ok) reply(cl, talker, "点歌失败:语音发送失败");
                return;
            }
        }

        Fetched fetched = downloadWithFallback(song);
        if (fetched == null || fetched.file == null) {
            LogWriter.log(TAG, "取流/下载失败: " + song.display());
            reply(cl, talker, "点歌失败:未能获取「" + song.title + "」的音频");
            return;
        }
        File file = fetched.file;

        // 「自动下载」: 点歌后把原始音频另存到用户可见目录(与在线音乐页下载目录一致)
        if (OnlineMusicPrefs.autoDownload()) {
            copyToPublic(file, song, fetched.level);
        }

        // 优先「解码→重采样→编码」流水线: 解码与编码重叠, 省掉约一半耗时
        byte[] voiceData = null;
        int realMs = 0;
        TtsVoiceSender.VoiceEncodeResult vr = TtsVoiceSender.decodeAudioToVoiceData(file.getAbsolutePath());
        if (vr != null && vr.data != null && vr.data.length > 0) {
            voiceData = vr.data;
            realMs = vr.durationMs;
        } else {
            // 回退旧路径: 整段解码 -> 重采样 -> 编码
            byte[] pcm = null;
            try {
                pcm = TtsVoiceSender.decodeAudioToPcm(file.getAbsolutePath());
            } catch (Throwable t) {
                LogWriter.log(TAG, "解码异常: " + t.getMessage());
            }
            if (pcm == null || pcm.length == 0) {
                LogWriter.log(TAG, "解码失败: " + file.getAbsolutePath());
                reply(cl, talker, "点歌失败:音频解码失败");
                safeDelete(file);
                return;
            }
            realMs = TtsVoiceSender.pcmDurationMs(pcm);
            voiceData = TtsVoiceSender.encodePcmToVoiceData(pcm);
        }

        int fakeMs = OnlineMusicPrefs.falseDurSec() * 1000;
        LogWriter.log(TAG, "发送语音: " + song.title + " 实际=" + realMs + "ms 误报=" + fakeMs
                + "ms voice=" + (voiceData == null ? 0 : voiceData.length) + "B");

        // 语音缓存: 后续同曲点歌可直接复用
        if (voiceData != null && voiceData.length > 0) {
            writeCache(voiceCacheFile(song, fetched.level), voiceData);
            pruneCache(voiceDir(), MAX_VOICE_CACHE_FILES);
        }
        boolean sent = voiceData != null
                && TtsVoiceSender.sendVoiceData(talker, voiceData, fakeMs, null);
        LogWriter.log(TAG, "点歌完成 sent=" + sent + " 耗时=" + (System.currentTimeMillis() - t0) + "ms");
        if (!sent) {
            reply(cl, talker, "点歌失败:语音发送失败");
        }

        // 保留缓存文件以便同曲复用(点歌提速); 目录按最近使用裁剪, 避免无限增长
        pruneCache(file.getParentFile());
    }

    // ==================== 选流 ====================

    /** 下载结果: 音频文件 + 实际命中的档位(用于语音缓存命名)。 */
    private static final class Fetched {
        final File file;
        final String level;
        Fetched(File file, String level) { this.file = file; this.level = level; }
    }

    /** 按优先档位顺序尝试,首个成功即返回(含命中档位)。 */
    private static Fetched downloadWithFallback(KuwoMusicApi.Song song) {
        String[] levels = prioritizedLevels();
        File dir = cacheDir();
        if (dir == null) {
            LogWriter.log(TAG, "缓存目录不可用");
            return null;
        }
        for (String level : levels) {
            File out = new File(dir, safeName(song.title + "-" + song.artist) + "-" + level
                    + (KuwoMusicApi.Q_FLAC.equals(level) ? ".flac" : ".mp3"));
            // 缓存命中: 同曲同档已下载过则直接复用, 避免重复走网络与 302 解析
            if (out.exists() && out.length() > 1024 && looksLikeAudio(out)) {
                try { out.setLastModified(System.currentTimeMillis()); } catch (Throwable ignored) {}
                LogWriter.log(TAG, "缓存命中 level=" + level + " size=" + out.length() + "B");
                return new Fetched(out, level);
            }
            // 中转地址自身会 302 到酷我 CDN, download 内部自动跟随, 省去一次解析往返
            try {
                long n = KuwoMusicApi.download(KuwoMusicApi.streamUrl(song.id, level), out, null);
                if (n > 1024 && out.exists() && looksLikeAudio(out)) {
                    LogWriter.log(TAG, "下载成功 level=" + level + " size=" + n + "B");
                    return new Fetched(out, level);
                }
                LogWriter.log(TAG, "下载过小/非音频 level=" + level + " n=" + n);
            } catch (Throwable t) {
                LogWriter.log(TAG, "直下失败 level=" + level + " " + t.getMessage());
            }
            // 直下失败时, 显式解析 302 后重试一次
            try {
                String finalUrl = KuwoMusicApi.resolveFinalUrl(KuwoMusicApi.streamUrl(song.id, level));
                if (finalUrl == null || finalUrl.isEmpty()) { safeDelete(out); continue; }
                long n = KuwoMusicApi.download(finalUrl, out, null);
                if (n > 1024 && out.exists() && looksLikeAudio(out)) {
                    LogWriter.log(TAG, "解析后下载成功 level=" + level + " size=" + n + "B");
                    return new Fetched(out, level);
                }
                LogWriter.log(TAG, "解析后下载过小/非音频 level=" + level + " n=" + n);
            } catch (Throwable t) {
                LogWriter.log(TAG, "解析后下载失败 level=" + level + " " + t.getMessage());
            }
            safeDelete(out);
        }
        return null;
    }

    /**
     * 点歌取流档位: 以用户所选音质为首选, 失败时逐级降档, 与设置页「失败时自动降档」一致。
     *
     * <p>点歌最终以 SILK 语音消息发出(有损), 因此默认档位设为 320K(exhigh) 以兼顾音质与速度;
     * 用户在设置页显式选择 128K/无损时按所选优先。</p>
     */
    private static String[] prioritizedLevels() {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        String q = OnlineMusicPrefs.quality();
        if (KuwoMusicApi.Q_AUTO.equals(q) || KuwoMusicApi.Q_FLAC.equals(q)) {
            set.add(KuwoMusicApi.Q_FLAC);
            set.add(KuwoMusicApi.Q_320);
            set.add(KuwoMusicApi.Q_128);
        } else if (KuwoMusicApi.Q_320.equals(q)) {
            set.add(KuwoMusicApi.Q_320);
            set.add(KuwoMusicApi.Q_128);
        } else {
            set.add(KuwoMusicApi.Q_128);
            set.add(KuwoMusicApi.Q_320);
        }
        return set.toArray(new String[0]);
    }

    // ==================== 匹配 ====================

    private static KuwoMusicApi.Song bestMatch(List<KuwoMusicApi.Song> list, String keyword) {
        String kw = norm(keyword);
        KuwoMusicApi.Song best = list.get(0);
        int bestScore = -1;
        for (KuwoMusicApi.Song s : list) {
            int score = 0;
            String title = norm(s.title);
            if (title.equals(kw)) score += 100;
            else if (title.contains(kw)) score += 60;
            else if (kw.contains(title)) score += 40;
            if (norm(s.artist).contains(kw)) score += 70;
            if (s.durationMs > 30000 && s.durationMs < 900000) score += 5;
            if (s.formats != null && s.formats.toUpperCase(Locale.US).contains("FLAC")) score += 3;
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    // ==================== 指令解析 ====================

    /**
     * 去掉群消息的「发送者:」前缀。
     *
     * <p>微信群聊收到的 content 形如 {@code wxid_xxx:\n点歌 十年} 或 {@code wxid_xxx: 点歌 十年};
     * 私聊无此前缀。仅当会话为群聊、且冒号前是纯 ASCII 发送者标识(无空格/中文)时才剥离,
     * 避免误伤私聊里歌名本身含冒号的正常文本。幂等: 已剥离过的正文不会再次被处理。</p>
     */
    private static String stripSenderPrefix(String talker, String content) {
        if (content == null) return null;
        if (talker == null || !isGroupTalker(talker)) return content;
        int pos = content.indexOf(':');
        if (pos <= 0 || pos > 64) return content;
        for (int i = 0; i < pos; i++) {
            char c = content.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.' || c == '@';
            if (!ok) return content;
        }
        return content.substring(pos + 1).replaceFirst("^[\\s\\u3000]+", "");
    }

    /** 群会话判定: 兼容普通群 @chatroom 与企业微信 @im.chatroom。 */
    private static boolean isGroupTalker(String talker) {
        return talker != null
                && (talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom"));
    }

    /**
     * 纯解析判定: 内容是否形如点歌指令(命中别名前缀且歌名非空), 与白名单/开关无关。
     * 供接收链在指令被拒时输出可诊断日志用, 避免"消息被吞但无迹可查"。
     */
    public static boolean looksLikeCommand(String talker, String content) {
        if (content == null || content.isEmpty()) return false;
        String keyword = matchKeyword(stripSenderPrefix(talker, content));
        return keyword != null && !keyword.isEmpty();
    }

    /** 命中别名前缀则返回其后的歌名,否则返回 null。 */
    private static String matchKeyword(String content) {
        String t = content.trim();
        // 去掉常见指令包裹符号
        while (t.startsWith("[") || t.startsWith("【") || t.startsWith("@")) {
            if (t.startsWith("@")) {
                int sp = t.indexOf(' ');
                if (sp < 0) return null;
                t = t.substring(sp + 1).trim();
            } else {
                char open = t.charAt(0);
                char close = t.startsWith("[") ? ']' : '】';
                int idx = t.indexOf(close);
                if (idx < 0) break;
                t = t.substring(idx + 1).trim();
            }
        }
        for (String alias : OnlineMusicPrefs.aliases()) {
            if (alias.isEmpty()) continue;
            if (t.startsWith(alias)) {
                String rest = t.substring(alias.length()).trim();
                rest = rest.replaceFirst("^[:：,，、\\-—\\s]+", "").trim();
                return rest;
            }
        }
        return null;
    }

    // ==================== 回复 ====================

    /**
     * 渲染点歌受理提示语: 支持 {song} 占位符, 未配置时用默认文案;
     * 当同一会话前面还有排队任务时, 追加位次提示。
     */
    private static String buildNoticeText(String song, int ahead) {
        String tpl = OnlineMusicPrefs.noticeText();
        if (tpl == null || tpl.trim().isEmpty()) tpl = OnlineMusicPrefs.DEFAULT_NOTICE_TEXT;
        String text = tpl.replace("{song}", song == null ? "" : song);
        if (ahead > 0) text = text + "（前面还有 " + ahead + " 位）";
        return text;
    }

    private static void reply(ClassLoader cl, String talker, String text) {
        try {
            GroupFeatures.sendTextMessage(cl, talker, text);
            LogWriter.log(TAG, "回复: " + text);
        } catch (Throwable t) {
            LogWriter.log(TAG, "回复失败: " + t.getMessage());
        }
    }

    // ==================== 工具 ====================

    private static File cacheDir() {
        try {
            Context c = ContextManager.getAppContext();
            if (c == null) return null;
            File d = c.getExternalFilesDir("dianGe");
            if (d == null) d = new File(c.getCacheDir(), "dianGe");
            if (d != null && !d.exists() && !d.mkdirs()) {
                LogWriter.log(TAG, "创建缓存目录失败: " + d.getAbsolutePath());
            }
            return d;
        } catch (Throwable t) {
            LogWriter.log(TAG, "cacheDir err: " + t.getMessage());
            return null;
        }
    }

    /** 用户可见的下载目录(与在线音乐页「下载目录」一致)。 */
    private static File publicMusicDir() {
        try {
            Context c = ContextManager.getAppContext();
            if (c == null) return null;
            File base = c.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
            if (base == null) base = c.getFilesDir();
            if (base == null) return null;
            File d = new File(base, "LeShaoMusic");
            if (!d.exists() && !d.mkdirs()) {
                LogWriter.log(TAG, "创建下载目录失败: " + d.getAbsolutePath());
                return null;
            }
            return d;
        } catch (Throwable t) {
            LogWriter.log(TAG, "publicMusicDir err: " + t.getMessage());
            return null;
        }
    }

    /** 「自动下载」开关: 把点歌得到的原始音频复制到用户可见目录。 */
    private static void copyToPublic(File src, KuwoMusicApi.Song song, String level) {
        try {
            if (src == null || !src.exists() || src.length() <= 0 || song == null) return;
            File dir = publicMusicDir();
            if (dir == null) return;
            String ext = KuwoMusicApi.Q_FLAC.equals(level) ? ".flac" : ".mp3";
            String safe = (song.title + (song.artist == null || song.artist.isEmpty()
                    ? "" : " - " + song.artist)).replaceAll("[\\\\/:*?\"<>|]", "_");
            File out = new File(dir, safe + ext);
            if (out.exists() && out.length() > 1024) return;
            try (java.io.FileInputStream in = new java.io.FileInputStream(src);
                 java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                fos.flush();
            }
            LogWriter.log(TAG, "自动下载保存: " + out.getAbsolutePath());
        } catch (Throwable t) {
            LogWriter.log(TAG, "自动下载失败: " + t.getMessage());
        }
    }

    /** 通过文件头魔数判断是否为音频(避免把 200 的错误页/HTML 当作下载成功)。 */
    private static boolean looksLikeAudio(File f) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            byte[] h = new byte[8];
            int rn = raf.read(h);
            if (rn < 4) return false;
            int b0 = h[0] & 0xFF, b1 = h[1] & 0xFF, b2 = h[2] & 0xFF, b3 = h[3] & 0xFF;
            if (b0 == 0x49 && b1 == 0x44 && b2 == 0x33) return true;            // ID3 (mp3)
            if (b0 == 0x66 && b1 == 0x4C && b2 == 0x61 && b3 == 0x43) return true; // fLaC
            if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46) return true; // RIFF (wav)
            if (b0 == 0x4F && b1 == 0x67 && b2 == 0x67 && b3 == 0x53) return true; // OggS
            if (b0 == 0xFF && (b1 & 0xE0) == 0xE0) return true;                // MPEG/AAC 帧同步
            // MP4/M4A: 第 4-8 字节为 'f','t','y','p'(中转的 128K 流可能是此容器,
            // 旧实现只认前 4 字节, 导致合法 2MB+ 音频被误判为非音频而白耗一次回落下载)
            if (rn >= 8 && h[4] == 0x66 && h[5] == 0x74 && h[6] == 0x79 && h[7] == 0x70) return true;
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 裁剪点歌缓存: 仅保留最近使用的 {@link #MAX_CACHE_FILES} 个音频文件。 */
    private static void pruneCache(File dir) {
        pruneCache(dir, MAX_CACHE_FILES);
    }

    /** 仅保留最近使用的 {@code max} 个文件, 超出按 lastModified 升序删除。 */
    private static void pruneCache(File dir, int max) {
        try {
            if (dir == null || !dir.isDirectory()) return;
            File[] files = dir.listFiles();
            if (files == null || files.length <= max) return;
            java.util.Arrays.sort(files, (a, b) ->
                    Long.compare(b.lastModified(), a.lastModified()));
            for (int i = max; i < files.length; i++) {
                try { files[i].delete(); } catch (Throwable ignored) {}
            }
            LogWriter.log(TAG, "缓存裁剪: 保留 " + max + " 个, 原 " + files.length);
        } catch (Throwable t) {
            LogWriter.log(TAG, "缓存裁剪异常: " + t.getMessage());
        }
    }

    // ==================== 语音缓存 ====================

    private static File voiceDir() {
        File base = cacheDir();
        if (base == null) return null;
        File d = new File(base, "voice");
        if (!d.exists() && !d.mkdirs()) {
            LogWriter.log(TAG, "创建语音缓存目录失败: " + d.getAbsolutePath());
        }
        return d;
    }

    private static File voiceCacheFile(KuwoMusicApi.Song song, String level) {
        File d = voiceDir();
        if (d == null || song == null) return null;
        String lv = level == null ? "128" : level;
        return new File(d, song.id + "-" + lv + ".voice");
    }

    /** 按优先档位查找已编码语音缓存, 命中返回文件。 */
    private static File findVoiceCache(KuwoMusicApi.Song song) {
        if (song == null) return null;
        for (String level : prioritizedLevels()) {
            File f = voiceCacheFile(song, level);
            if (f != null && f.exists() && f.length() > 1024) {
                try { f.setLastModified(System.currentTimeMillis()); } catch (Throwable ignored) {}
                LogWriter.log(TAG, "语音缓存命中 level=" + level + " size=" + f.length() + "B");
                return f;
            }
        }
        return null;
    }

    private static void writeCache(File f, byte[] data) {
        if (f == null || data == null || data.length == 0) return;
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
            fos.write(data);
            fos.flush();
            LogWriter.log(TAG, "语音缓存写入 size=" + data.length + "B file=" + f.getName());
        } catch (Throwable t) {
            LogWriter.log(TAG, "语音缓存写入失败: " + t.getMessage());
        }
    }

    private static byte[] readAllBytes(File f) {
        if (f == null || !f.exists()) return null;
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            long len = f.length();
            if (len <= 0 || len > Integer.MAX_VALUE) return null;
            byte[] buf = new byte[(int) len];
            int off = 0;
            while (off < buf.length) {
                int r = fis.read(buf, off, buf.length - off);
                if (r < 0) break;
                off += r;
            }
            if (off != buf.length) return null;
            return buf;
        } catch (Throwable t) {
            LogWriter.log(TAG, "读取语音缓存失败: " + t.getMessage());
            return null;
        }
    }

    private static String safeName(String s) {
        if (s == null) return "song";
        String r = s.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (r.length() > 60) r = r.substring(0, 60);
        return r.isEmpty() ? "song" : r;
    }

    private static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.US).replaceAll("[\\s\\-_()（）\\[\\]【】]", "");
    }

    private static String shortErr(Throwable t) {
        String m = t == null ? "" : t.getMessage();
        if (m == null || m.isEmpty()) return "未知错误";
        return m.length() > 40 ? m.substring(0, 40) : m;
    }

    private static void safeDelete(File f) {
        try { if (f != null && f.exists()) f.delete(); } catch (Throwable ignored) {}
    }
}
