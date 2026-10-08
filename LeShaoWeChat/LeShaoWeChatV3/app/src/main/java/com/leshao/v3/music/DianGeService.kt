package com.leshao.v3.music

import android.content.Context
import android.os.Environment

import com.leshao.ai.hook.wechat.WeChatMessenger
import com.leshao.v3.ContextManager
import com.leshao.v3.LogWriter
import com.leshao.v3.hook.GroupFeatures
import com.leshao.v3.hook.TtsVoiceSender

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 「点歌」自动化服务。
 *
 * 在群里/好友发「点歌 歌名」(支持别名前缀,仅白名单会话生效),系统自动:搜索酷我 →
 * 择优取流(默认 128K 优先,320K 兜底) → 下载 → 解码为 24kHz 单声道 PCM →
 * 转 SILK 语音消息发出。已编码语音会按曲目缓存,同曲再次点歌直接复用,省去下载与编码。
 * 语音时长按「误报时长」上报(默认 60 秒,可自定义 1-60 秒),以规避微信 60 秒上限。
 */
final class DianGeService private constructor() {

    /** 单个点歌任务。 */
    private class SongTask(
        val cl: ClassLoader?,
        val talker: String,
        val keyword: String,
        val quoted: Any?
    )

    /** 会话级串行队列: 保证同一会话的点歌任务严格按入队顺序逐个执行。 */
    private class SessionQueue(val talker: String) {
        val queue: ArrayDeque<SongTask> = ArrayDeque()
        var running = false
    }

    companion object {
        private const val TAG = "LsDianGe"

        /** 指令后歌名最大长度,超过视为普通聊天不触发。 */
        private const val MAX_KEYWORD_LEN = 40
        /** 同一会话同一歌名的触发冷却(毫秒)。 */
        private const val DEDUP_WINDOW_MS = 15000L
        /** 点歌缓存目录最多保留的音频文件数, 超出按最近使用时间清理最旧。 */
        private const val MAX_CACHE_FILES = 24
        /** 语音缓存目录最多保留的已编码语音数。 */
        private const val MAX_VOICE_CACHE_FILES = 24

        /**
         * 每会话允许排队等待的点歌任务上限(不含正在执行的那个); 超出即拒绝新请求。
         *
         * v1102: 点歌从「全局并发」改为「每会话串行」——同一会话内一次只处理一个任务,
         * 不同会话之间互不阻塞。
         */
        private const val MAX_QUEUE_PER_SESSION = 10
        /** 点歌执行线程池: 每会话串行 worker、手动发送与受理提示共用, 可弹性扩容。 */
        private val POOL: ExecutorService = Executors.newCachedThreadPool()
        /** 会话 → 串行任务队列。 */
        private val sQueues: MutableMap<String, SessionQueue> = ConcurrentHashMap()
        private val sRecent: MutableMap<String, Long> = ConcurrentHashMap()

        /** 兼容旧调用: 不带被引用原消息, 受理提示回退普通文本。 */
        @JvmStatic
        fun maybeHandle(cl: ClassLoader?, talker: String?, content: String?): Boolean {
            return maybeHandle(cl, talker, content, null)
        }

        /**
         * 判断并处理点歌指令。
         *
         * @param quotedMsg 接收链拿到的原消息对象(e9), 用于发送微信原生引用受理提示; 为空则回退文本
         * @return true 表示已识别为点歌指令(已接管, 调用方无需再走关键词回复)
         */
        @JvmStatic
        fun maybeHandle(cl: ClassLoader?, talker: String?, content: String?, quotedMsg: Any?): Boolean {
            if (talker == null || content == null || content.isEmpty()) return false
            if (!OnlineMusicPrefs.enabled()) {
                if (looksLikeCommand(talker, content)) {
                    LogWriter.log(TAG, "点歌功能未启用, 忽略指令 talker=" + talker)
                }
                return false
            }

            val keyword = matchKeyword(stripSenderPrefix(talker, content))
            if (keyword == null) return false
            if (keyword.isEmpty()) {
                LogWriter.log(TAG, "命中别名但歌名为空,忽略 talker=" + talker)
                return false
            }
            if (keyword.length > MAX_KEYWORD_LEN) {
                LogWriter.log(TAG, "歌名过长(" + keyword.length + "),忽略: " + keyword)
                return false
            }
            if (!OnlineMusicPrefs.inWhitelist(talker)) {
                LogWriter.log(TAG, "非白名单会话,忽略点歌 talker=" + talker + " kw=" + keyword)
                return false
            }

            val key = talker + "|" + keyword
            val now = System.currentTimeMillis()
            // v1103: 原子去重(put 返回旧值)。MessageHook 与 Trigger 两条接收链可能并发命中同一条
            // 点歌消息, 原子操作保证仅第一条真正入队, 避免重复受理提示与重复任务。
            val last = sRecent.put(key, now)
            if (last != null && now - last < DEDUP_WINDOW_MS) {
                LogWriter.log(TAG, "冷却中,忽略重复点歌: " + keyword)
                return true
            }
            if (sRecent.size > 200) {
                var removed = 0
                val it = sRecent.entries.iterator()
                while (it.hasNext() && removed < 100) {
                    val e = it.next()
                    if (now - e.value > DEDUP_WINDOW_MS) {
                        it.remove()
                        removed++
                    }
                }
            }

            // v1102: 每会话串行入队。同一会话内一次只处理一个任务, 处理完再取下一个;
            // 不同会话各自维护队列, 互不阻塞。ahead = 排在本任务前面的任务数(含正在执行的)。
            val q = sQueues.computeIfAbsent(talker) { SessionQueue(it) }
            var ahead = 0
            var start = false
            var rejected = false
            synchronized(q) {
                if (q.queue.size >= MAX_QUEUE_PER_SESSION) {
                    LogWriter.log(TAG, "点歌队列已满,拒绝: talker=" + talker + " kw=" + keyword)
                    // 异步回复: 本方法可能在微信 DB 写锁内被调用(f9.Bb insert 钩子), 严禁锁内同步发送
                    val fclFull = cl
                    POOL.execute { reply(fclFull, talker, "点歌排队已满，请稍后再试") }
                    rejected = true
                } else {
                    ahead = (if (q.running) 1 else 0) + q.queue.size
                    q.queue.addLast(SongTask(cl, talker, keyword, quotedMsg))
                    start = !q.running
                    if (start) q.running = true
                }
            }
            if (rejected) return true
            LogWriter.log(TAG, "接管点歌: talker=" + talker + " kw=" + keyword
                    + " 前面=" + ahead + " 队列=" + q.queue.size)

            // 受理提示: 优先原生引用(引用点歌原消息), 失败回退文本; 入队即发, 用户即时可见
            if (OnlineMusicPrefs.notice()) {
                val fcl = cl
                POOL.execute { sendAcceptNotice(fcl, talker, keyword, quotedMsg, ahead) }
            }
            if (start) {
                val fq = q
                POOL.execute { drain(fq) }
            }
            return true
        }

        /** 会话级串行 worker: 循环取出队首任务执行, 队列空则退出并标记空闲。 */
        private fun drain(q: SessionQueue) {
            while (true) {
                var task: SongTask?
                synchronized(q) {
                    task = q.queue.pollFirst()
                    if (task == null) {
                        q.running = false
                        return
                    }
                }
                val t = task ?: continue
                try {
                    run(t.cl, t.talker, t.keyword)
                } catch (ex: Throwable) {
                    LogWriter.log(TAG, "点歌异常: " + ex)
                    reply(t.cl, t.talker, "点歌失败:" + shortErr(ex))
                }
            }
        }

        /** 发送点歌受理提示: 优先微信原生引用(引用点歌原消息), 失败回退普通文本。 */
        private fun sendAcceptNotice(cl: ClassLoader?, talker: String, song: String?,
                                     quoted: Any?, ahead: Int) {
            val text = buildNoticeText(song, ahead)
            var quotedOk = false
            if (quoted != null) {
                try {
                    quotedOk = WeChatMessenger.sendQuoteAndAt(quoted, talker, "", text, cl)
                } catch (t: Throwable) {
                    LogWriter.log(TAG, "原生引用受理提示失败: " + t.message)
                }
            }
            if (quotedOk) {
                LogWriter.log(TAG, "受理提示(引用): " + text)
            } else {
                reply(cl, talker, text)
            }
        }

        /**
         * 纯判定：当前消息是否为该会话可执行的点歌指令（无任何副作用）。
         *
         * 供 AI 助手等其它接收链在送大模型前过滤点歌指令使用，避免点歌消息被当作普通
         * 聊天再次送去大模型。判定口径与 maybeHandle 一致：功能已启用、命中别名
         * 前缀、歌名非空且不过长、且会话在白名单内。
         */
        @JvmStatic
        fun isSongRequest(talker: String?, content: String?): Boolean {
            if (talker == null || content == null || content.isEmpty()) return false
            if (!OnlineMusicPrefs.enabled()) return false
            val keyword = matchKeyword(stripSenderPrefix(talker, content))
            if (keyword.isNullOrEmpty()) return false
            if (keyword.length > MAX_KEYWORD_LEN) return false
            return OnlineMusicPrefs.inWhitelist(talker)
        }

        /**
         * 点歌功能已启用且内容形如点歌指令(不校验白名单)。
         *
         * 供 AI 接收链在「送大模型之前」过滤点歌指令使用: 命中即由点歌服务接管或直接丢弃,
         * 避免点歌消息被 AI 当作普通聊天回复。与 isSongRequest 的区别是不校验白名单,
         * 因为非白名单会话的点歌指令同样不应送入 AI。
         */
        @JvmStatic
        fun isEnabledCommand(talker: String?, content: String?): Boolean {
            if (talker == null || content == null || content.isEmpty()) return false
            if (!OnlineMusicPrefs.enabled()) return false
            return looksLikeCommand(talker, content)
        }

        // ==================== 手动发送（在线音乐页） ====================

        /**
         * 把指定歌曲以语音消息发送到多个会话（在线音乐页「发送」入口调用）。
         *
         * 手动触发，不受「启用点歌 / 白名单」限制；音质与误报时长沿用点歌偏好。
         */
        @JvmStatic
        fun sendSongToTargets(cl: ClassLoader?, song: KuwoMusicApi.Song?, talkers: List<String>?) {
            if (song == null || talkers == null || talkers.isEmpty()) return
            POOL.execute {
                LogWriter.log(TAG, "手动发送 " + song.display() + " → " + talkers.size + " 个会话")
                val fetched = downloadWithFallback(song)
                if (fetched == null) {
                    for (t in talkers) reply(cl, t, "发送失败:未能获取「" + song.title + "」的音频")
                    return@execute
                }
                val file = fetched.file
                // 优先流水线转码(解码与编码重叠), 失败回退旧路径
                var voiceData: ByteArray?
                val vr = TtsVoiceSender.decodeAudioToVoiceData(file.absolutePath)
                val vData = vr?.data
                if (vData != null && vData.isNotEmpty()) {
                    voiceData = vData
                } else {
                    var pcm: ByteArray?
                    try {
                        pcm = TtsVoiceSender.decodeAudioToPcm(file.absolutePath)
                    } catch (t: Throwable) {
                        pcm = null
                        LogWriter.log(TAG, "手动解码异常: " + t.message)
                    }
                    if (pcm == null || pcm.isEmpty()) {
                        for (t in talkers) reply(cl, t, "发送失败:音频解码失败")
                        safeDelete(file)
                        return@execute
                    }
                    voiceData = TtsVoiceSender.encodePcmToVoiceData(pcm)
                }
                if (voiceData == null || voiceData.isEmpty()) {
                    for (t in talkers) reply(cl, t, "发送失败:音频编码失败")
                    safeDelete(file)
                    return@execute
                }
                val fakeMs = OnlineMusicPrefs.falseDurSec() * 1000
                writeCache(voiceCacheFile(song, fetched.level), voiceData)
                var ok = 0
                for (t in talkers) {
                    try {
                        if (TtsVoiceSender.sendVoiceData(t, voiceData, fakeMs, null)) ok++
                    } catch (e: Throwable) {
                        LogWriter.log(TAG, "发送失败 talker=" + t + " " + e.message)
                    }
                }
                LogWriter.log(TAG, "手动发送完成 " + ok + "/" + talkers.size + " 首=" + song.title)
                pruneCache(file.parentFile)
                pruneCache(voiceDir(), MAX_VOICE_CACHE_FILES)
            }
        }

        // ==================== 主流程 ====================

        private fun run(cl: ClassLoader?, talker: String, keyword: String) {
            val t0 = System.currentTimeMillis()
            val list: MutableList<KuwoMusicApi.Song>
            try {
                list = KuwoMusicApi.search(keyword, 1)
            } catch (t: Throwable) {
                LogWriter.log(TAG, "搜索失败: " + t.message)
                reply(cl, talker, "点歌失败:搜索无响应")
                return
            }
            if (list.isEmpty()) {
                LogWriter.log(TAG, "无搜索结果: " + keyword)
                reply(cl, talker, "没有找到「" + keyword + "」这首歌曲")
                return
            }

            val song = bestMatch(list, keyword)
            LogWriter.log(TAG, "选中: " + song.display() + " (id=" + song.id + ")")

            // v1102: 受理提示已改为入队时以「原生引用」发出(见 maybeHandle), 此处不再重复提示。

            // 语音缓存命中: 同曲同档已编码过, 直接发送, 跳过下载/解码/重采样/编码
            val cachedVoice = findVoiceCache(song)
            if (cachedVoice != null) {
                val cached = readAllBytes(cachedVoice)
                if (cached != null && cached.isNotEmpty()) {
                    val fakeMs0 = OnlineMusicPrefs.falseDurSec() * 1000
                    val ok = TtsVoiceSender.sendVoiceData(talker, cached, fakeMs0, null)
                    LogWriter.log(TAG, "语音缓存命中直接发送 sent=" + ok + " 耗时="
                            + (System.currentTimeMillis() - t0) + "ms song=" + song.title)
                    if (!ok) reply(cl, talker, "点歌失败:语音发送失败")
                    return
                }
            }

            val fetched = downloadWithFallback(song)
            if (fetched == null) {
                LogWriter.log(TAG, "取流/下载失败: " + song.display())
                reply(cl, talker, "点歌失败:未能获取「" + song.title + "」的音频")
                return
            }
            val file = fetched.file

            // 「自动下载」: 点歌后把原始音频另存到用户可见目录(与在线音乐页下载目录一致)
            if (OnlineMusicPrefs.autoDownload()) {
                copyToPublic(file, song, fetched.level)
            }

            // 优先「解码→重采样→编码」流水线: 解码与编码重叠, 省掉约一半耗时
            var voiceData: ByteArray?
            var realMs: Int
            val vr = TtsVoiceSender.decodeAudioToVoiceData(file.absolutePath)
            val vData = vr?.data
            if (vData != null && vData.isNotEmpty()) {
                voiceData = vData
                realMs = vr.durationMs
            } else {
                // 回退旧路径: 整段解码 -> 重采样 -> 编码
                var pcm: ByteArray? = null
                try {
                    pcm = TtsVoiceSender.decodeAudioToPcm(file.absolutePath)
                } catch (t: Throwable) {
                    LogWriter.log(TAG, "解码异常: " + t.message)
                }
                if (pcm == null || pcm.isEmpty()) {
                    LogWriter.log(TAG, "解码失败: " + file.absolutePath)
                    reply(cl, talker, "点歌失败:音频解码失败")
                    safeDelete(file)
                    return
                }
                realMs = TtsVoiceSender.pcmDurationMs(pcm)
                voiceData = TtsVoiceSender.encodePcmToVoiceData(pcm)
            }

            val fakeMs = OnlineMusicPrefs.falseDurSec() * 1000
            LogWriter.log(TAG, "发送语音: " + song.title + " 实际=" + realMs + "ms 误报=" + fakeMs
                    + "ms voice=" + (voiceData?.size ?: 0) + "B")

            // 语音缓存: 后续同曲点歌可直接复用
            if (voiceData != null && voiceData.isNotEmpty()) {
                writeCache(voiceCacheFile(song, fetched.level), voiceData)
                pruneCache(voiceDir(), MAX_VOICE_CACHE_FILES)
            }
            val sent = voiceData != null
                    && TtsVoiceSender.sendVoiceData(talker, voiceData, fakeMs, null)
            LogWriter.log(TAG, "点歌完成 sent=" + sent + " 耗时="
                    + (System.currentTimeMillis() - t0) + "ms")
            if (!sent) {
                reply(cl, talker, "点歌失败:语音发送失败")
            }

            // 保留缓存文件以便同曲复用(点歌提速); 目录按最近使用裁剪, 避免无限增长
            pruneCache(file.parentFile)
        }

        // ==================== 选流 ====================

        /** 下载结果: 音频文件 + 实际命中的档位(用于语音缓存命名)。 */
        private class Fetched(val file: File, val level: String)

        /** 按优先档位顺序尝试,首个成功即返回(含命中档位)。 */
        private fun downloadWithFallback(song: KuwoMusicApi.Song): Fetched? {
            val levels = prioritizedLevels()
            val dir = cacheDir()
            if (dir == null) {
                LogWriter.log(TAG, "缓存目录不可用")
                return null
            }
            for (level in levels) {
                val out = File(dir, safeName(song.title + "-" + song.artist) + "-" + level
                        + (if (KuwoMusicApi.Q_FLAC == level) ".flac" else ".mp3"))
                // 缓存命中: 同曲同档已下载过则直接复用, 避免重复走网络与 302 解析
                if (out.exists() && out.length() > 1024 && looksLikeAudio(out)) {
                    try {
                        out.setLastModified(System.currentTimeMillis())
                    } catch (ignored: Throwable) {
                    }
                    LogWriter.log(TAG, "缓存命中 level=" + level + " size=" + out.length() + "B")
                    return Fetched(out, level)
                }
                // 中转地址自身会 302 到酷我 CDN, download 内部自动跟随, 省去一次解析往返
                try {
                    val n = KuwoMusicApi.download(KuwoMusicApi.streamUrl(song.id, level), out, null)
                    if (n > 1024 && out.exists() && looksLikeAudio(out)) {
                        LogWriter.log(TAG, "下载成功 level=" + level + " size=" + n + "B")
                        return Fetched(out, level)
                    }
                    LogWriter.log(TAG, "下载过小/非音频 level=" + level + " n=" + n)
                } catch (t: Throwable) {
                    LogWriter.log(TAG, "直下失败 level=" + level + " " + t.message)
                }
                // 直下失败时, 显式解析 302 后重试一次
                try {
                    val finalUrl = KuwoMusicApi.resolveFinalUrl(KuwoMusicApi.streamUrl(song.id, level))
                    if (finalUrl.isEmpty()) {
                        safeDelete(out)
                        continue
                    }
                    val n = KuwoMusicApi.download(finalUrl, out, null)
                    if (n > 1024 && out.exists() && looksLikeAudio(out)) {
                        LogWriter.log(TAG, "解析后下载成功 level=" + level + " size=" + n + "B")
                        return Fetched(out, level)
                    }
                    LogWriter.log(TAG, "解析后下载过小/非音频 level=" + level + " n=" + n)
                } catch (t: Throwable) {
                    LogWriter.log(TAG, "解析后下载失败 level=" + level + " " + t.message)
                }
                safeDelete(out)
            }
            return null
        }

        /**
         * 点歌取流档位: 以用户所选音质为首选, 失败时逐级降档, 与设置页「失败时自动降档」一致。
         *
         * 点歌最终以 SILK 语音消息发出(有损), 因此默认档位设为 320K(exhigh) 以兼顾音质与速度;
         * 用户在设置页显式选择 128K/无损时按所选优先。
         */
        private fun prioritizedLevels(): Array<String> {
            val set = java.util.LinkedHashSet<String>()
            val q = OnlineMusicPrefs.quality()
            if (KuwoMusicApi.Q_AUTO == q || KuwoMusicApi.Q_FLAC == q) {
                set.add(KuwoMusicApi.Q_FLAC)
                set.add(KuwoMusicApi.Q_320)
                set.add(KuwoMusicApi.Q_128)
            } else if (KuwoMusicApi.Q_320 == q) {
                set.add(KuwoMusicApi.Q_320)
                set.add(KuwoMusicApi.Q_128)
            } else {
                set.add(KuwoMusicApi.Q_128)
                set.add(KuwoMusicApi.Q_320)
            }
            return set.toTypedArray()
        }

        // ==================== 匹配 ====================

        private fun bestMatch(list: List<KuwoMusicApi.Song>, keyword: String): KuwoMusicApi.Song {
            val kw = norm(keyword)
            var best = list[0]
            var bestScore = -1
            for (s in list) {
                var score = 0
                val title = norm(s.title)
                if (title == kw) score += 100
                else if (title.contains(kw)) score += 60
                else if (kw.contains(title)) score += 40
                if (norm(s.artist).contains(kw)) score += 70
                if (s.durationMs > 30000 && s.durationMs < 900000) score += 5
                if (s.formats?.uppercase(Locale.US)?.contains("FLAC") == true) score += 3
                if (score > bestScore) {
                    bestScore = score
                    best = s
                }
            }
            return best
        }

        // ==================== 指令解析 ====================

        /**
         * 去掉群消息的「发送者:」前缀。
         *
         * 微信群聊收到的 content 形如 wxid_xxx:\n点歌 十年 或 wxid_xxx: 点歌 十年;
         * 私聊无此前缀。仅当会话为群聊、且冒号前是纯 ASCII 发送者标识(无空格/中文)时才剥离,
         * 避免误伤私聊里歌名本身含冒号的正常文本。幂等: 已剥离过的正文不会再次被处理。
         */
        private fun stripSenderPrefix(talker: String?, content: String?): String? {
            if (content == null) return null
            if (talker == null || !isGroupTalker(talker)) return content
            val pos = content.indexOf(':')
            if (pos <= 0 || pos > 64) return content
            for (i in 0 until pos) {
                val c = content[i]
                val ok = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') ||
                        c == '_' || c == '-' || c == '.' || c == '@'
                if (!ok) return content
            }
            return content.substring(pos + 1).replaceFirst("^[\\s\\u3000]+".toRegex(), "")
        }

        /** 群会话判定: 兼容普通群 @chatroom 与企业微信 @im.chatroom。 */
        private fun isGroupTalker(talker: String?): Boolean {
            return talker != null
                    && (talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom"))
        }

        /**
         * 纯解析判定: 内容是否形如点歌指令(命中别名前缀且歌名非空), 与白名单/开关无关。
         * 供接收链在指令被拒时输出可诊断日志用, 避免"消息被吞但无迹可查"。
         */
        @JvmStatic
        fun looksLikeCommand(talker: String?, content: String?): Boolean {
            if (content == null || content.isEmpty()) return false
            val keyword = matchKeyword(stripSenderPrefix(talker, content))
            return !keyword.isNullOrEmpty()
        }

        /** 命中别名前缀则返回其后的歌名,否则返回 null。 */
        private fun matchKeyword(content: String?): String? {
            var t = content?.trim() ?: return null
            // 去掉常见指令包裹符号
            while (t.startsWith("[") || t.startsWith("【") || t.startsWith("@")) {
                if (t.startsWith("@")) {
                    val sp = t.indexOf(' ')
                    if (sp < 0) return null
                    t = t.substring(sp + 1).trim()
                } else {
                    val close = if (t.startsWith("[")) ']' else '】'
                    val idx = t.indexOf(close)
                    if (idx < 0) break
                    t = t.substring(idx + 1).trim()
                }
            }
            for (alias in OnlineMusicPrefs.aliases()) {
                if (alias.isEmpty()) continue
                if (t.startsWith(alias)) {
                    var rest = t.substring(alias.length).trim()
                    rest = rest.replaceFirst("^[:：,，、\\-—\\s]+".toRegex(), "").trim()
                    return rest
                }
            }
            return null
        }

        // ==================== 回复 ====================

        /**
         * 渲染点歌受理提示语: 支持 {song} 占位符, 未配置时用默认文案;
         * 当同一会话前面还有排队任务时, 追加位次提示。
         */
        private fun buildNoticeText(song: String?, ahead: Int): String {
            var tpl = OnlineMusicPrefs.noticeText()
            if (tpl.trim().isEmpty()) tpl = OnlineMusicPrefs.DEFAULT_NOTICE_TEXT
            var text = tpl.replace("{song}", song ?: "")
            if (ahead > 0) text = text + "（前面还有 " + ahead + " 位）"
            return text
        }

        private fun reply(cl: ClassLoader?, talker: String, text: String) {
            try {
                GroupFeatures.sendTextMessage(cl, talker, text)
                LogWriter.log(TAG, "回复: " + text)
            } catch (t: Throwable) {
                LogWriter.log(TAG, "回复失败: " + t.message)
            }
        }

        // ==================== 工具 ====================

        private fun cacheDir(): File? {
            return try {
                val c = ContextManager.getAppContext() ?: return null
                var d = c.getExternalFilesDir("dianGe") ?: File(c.cacheDir, "dianGe")
                if (!d.exists() && !d.mkdirs()) {
                    LogWriter.log(TAG, "创建缓存目录失败: " + d.absolutePath)
                }
                d
            } catch (t: Throwable) {
                LogWriter.log(TAG, "cacheDir err: " + t.message)
                null
            }
        }

        /** 用户可见的下载目录(与在线音乐页「下载目录」一致)。 */
        private fun publicMusicDir(): File? {
            return try {
                val c = ContextManager.getAppContext() ?: return null
                var base: File? = c.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                if (base == null) base = c.filesDir
                if (base == null) return null
                val d = File(base, "LeShaoMusic")
                if (!d.exists() && !d.mkdirs()) {
                    LogWriter.log(TAG, "创建下载目录失败: " + d.absolutePath)
                    return null
                }
                d
            } catch (t: Throwable) {
                LogWriter.log(TAG, "publicMusicDir err: " + t.message)
                null
            }
        }

        /** 「自动下载」开关: 把点歌得到的原始音频复制到用户可见目录。 */
        private fun copyToPublic(src: File?, song: KuwoMusicApi.Song?, level: String?) {
            try {
                if (src == null || !src.exists() || src.length() <= 0 || song == null) return
                val dir = publicMusicDir() ?: return
                val ext = if (KuwoMusicApi.Q_FLAC == level) ".flac" else ".mp3"
                val safe = (song.title + (if (song.artist.isNullOrEmpty()) "" else " - " + song.artist))
                    .replace("[\\\\/:*?\"<>|]".toRegex(), "_")
                val out = File(dir, safe + ext)
                if (out.exists() && out.length() > 1024) return
                FileInputStream(src).use { inStream ->
                    FileOutputStream(out).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inStream.read(buf)
                            if (n <= 0) break
                            fos.write(buf, 0, n)
                        }
                        fos.flush()
                    }
                }
                LogWriter.log(TAG, "自动下载保存: " + out.absolutePath)
            } catch (t: Throwable) {
                LogWriter.log(TAG, "自动下载失败: " + t.message)
            }
        }

        /** 通过文件头魔数判断是否为音频(避免把 200 的错误页/HTML 当作下载成功)。 */
        private fun looksLikeAudio(f: File): Boolean {
            return try {
                RandomAccessFile(f, "r").use { raf ->
                    val h = ByteArray(8)
                    val rn = raf.read(h)
                    if (rn < 4) return@use false
                    val b0 = h[0].toInt() and 0xFF
                    val b1 = h[1].toInt() and 0xFF
                    val b2 = h[2].toInt() and 0xFF
                    val b3 = h[3].toInt() and 0xFF
                    if (b0 == 0x49 && b1 == 0x44 && b2 == 0x33) return@use true            // ID3 (mp3)
                    if (b0 == 0x66 && b1 == 0x4C && b2 == 0x61 && b3 == 0x43) return@use true // fLaC
                    if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46) return@use true // RIFF (wav)
                    if (b0 == 0x4F && b1 == 0x67 && b2 == 0x67 && b3 == 0x53) return@use true // OggS
                    if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) return@use true                // MPEG/AAC 帧同步
                    // MP4/M4A: 第 4-8 字节为 'f','t','y','p'(中转的 128K 流可能是此容器,
                    // 旧实现只认前 4 字节, 导致合法 2MB+ 音频被误判为非音频而白耗一次回落下载)
                    if (rn >= 8 && h[4] == 0x66.toByte() && h[5] == 0x74.toByte() &&
                        h[6] == 0x79.toByte() && h[7] == 0x70.toByte()) return@use true
                    return@use false
                }
            } catch (t: Throwable) {
                false
            }
        }

        /** 裁剪点歌缓存: 仅保留最近使用的 MAX_CACHE_FILES 个音频文件。 */
        private fun pruneCache(dir: File?) {
            pruneCache(dir, MAX_CACHE_FILES)
        }

        /** 仅保留最近使用的 max 个文件, 超出按 lastModified 升序删除。 */
        private fun pruneCache(dir: File?, max: Int) {
            try {
                if (dir == null || !dir.isDirectory) return
                val files = dir.listFiles()
                if (files == null || files.size <= max) return
                java.util.Arrays.sort(files) { a, b ->
                    java.lang.Long.compare(b.lastModified(), a.lastModified())
                }
                for (i in max until files.size) {
                    try {
                        files[i].delete()
                    } catch (ignored: Throwable) {
                    }
                }
                LogWriter.log(TAG, "缓存裁剪: 保留 " + max + " 个, 原 " + files.size)
            } catch (t: Throwable) {
                LogWriter.log(TAG, "缓存裁剪异常: " + t.message)
            }
        }

        // ==================== 语音缓存 ====================

        private fun voiceDir(): File? {
            val base = cacheDir() ?: return null
            val d = File(base, "voice")
            if (!d.exists() && !d.mkdirs()) {
                LogWriter.log(TAG, "创建语音缓存目录失败: " + d.absolutePath)
            }
            return d
        }

        private fun voiceCacheFile(song: KuwoMusicApi.Song?, level: String?): File? {
            val d = voiceDir()
            if (d == null || song == null) return null
            val lv = level ?: "128"
            return File(d, song.id + "-" + lv + ".voice")
        }

        /** 按优先档位查找已编码语音缓存, 命中返回文件。 */
        private fun findVoiceCache(song: KuwoMusicApi.Song?): File? {
            if (song == null) return null
            for (level in prioritizedLevels()) {
                val f = voiceCacheFile(song, level)
                if (f != null && f.exists() && f.length() > 1024) {
                    try {
                        f.setLastModified(System.currentTimeMillis())
                    } catch (ignored: Throwable) {
                    }
                    LogWriter.log(TAG, "语音缓存命中 level=" + level + " size=" + f.length() + "B")
                    return f
                }
            }
            return null
        }

        private fun writeCache(f: File?, data: ByteArray?) {
            if (f == null || data == null || data.isEmpty()) return
            try {
                FileOutputStream(f).use { fos ->
                    fos.write(data)
                    fos.flush()
                    LogWriter.log(TAG, "语音缓存写入 size=" + data.size + "B file=" + f.name)
                }
            } catch (t: Throwable) {
                LogWriter.log(TAG, "语音缓存写入失败: " + t.message)
            }
        }

        private fun readAllBytes(f: File?): ByteArray? {
            if (f == null || !f.exists()) return null
            return try {
                FileInputStream(f).use { fis ->
                    val len = f.length()
                    if (len <= 0 || len > Integer.MAX_VALUE.toLong()) return@use null
                    val buf = ByteArray(len.toInt())
                    var off = 0
                    while (off < buf.size) {
                        val r = fis.read(buf, off, buf.size - off)
                        if (r < 0) break
                        off += r
                    }
                    if (off != buf.size) return@use null
                    buf
                }
            } catch (t: Throwable) {
                LogWriter.log(TAG, "读取语音缓存失败: " + t.message)
                null
            }
        }

        private fun safeName(s: String?): String {
            if (s == null) return "song"
            var r = s.replace("[\\\\/:*?\"<>|\\r\\n]".toRegex(), "_").trim()
            if (r.length > 60) r = r.substring(0, 60)
            return if (r.isEmpty()) "song" else r
        }

        private fun norm(s: String?): String {
            if (s == null) return ""
            return s.lowercase(Locale.US).replace("[\\s\\-_()（）\\[\\]【】]".toRegex(), "")
        }

        private fun shortErr(t: Throwable?): String {
            val m = if (t == null) "" else t.message
            if (m == null || m.isEmpty()) return "未知错误"
            return if (m.length > 40) m.substring(0, 40) else m
        }

        private fun safeDelete(f: File?) {
            try {
                if (f != null && f.exists()) f.delete()
            } catch (ignored: Throwable) {
            }
        }
    }
}
