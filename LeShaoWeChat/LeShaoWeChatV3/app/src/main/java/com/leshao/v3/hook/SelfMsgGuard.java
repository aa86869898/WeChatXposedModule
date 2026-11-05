package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模块自发消息护栏（v3.0.580）。
 *
 * <p><b>为什么需要它</b>：模块自己发出的消息（点歌受理提示、AI 回复、红包播报、转发内容等）
 * 只要有一条以「接收形态」（isSend 非 1）落库，微信的 insert 回调就会把它当成
 * 用户刚收到的新消息，交给所有接收侧 hook：
 * 自动转发（AutoForward）、AI 接收（LeshaoAI.Recv）、自动进群（AutoGroupQr）……
 * 一旦「自动转发」同时开着且来源包含该会话，就会把这条提示语<b>群发到全部目标</b>。
 * 真实事故日志（分身leshao_v3_log.txt 07:13:32）就是点歌提示语被转发到 545 个群。</p>
 *
 * <p><b>做法</b>：模块每次发送前在账本里登记一条「自发消息」记录，接收侧 hook 命中即丢弃。
 * 账本有两个键：</p>
 * <ul>
 *   <li>精确键 {@code talker|type|hash(content)}，TTL 120s —— 正常命中路径</li>
 *   <li>兜底键 {@code hash(content)}，TTL 20s —— talker 被改写（群内前缀/别名）时仍能拦住</li>
 * </ul>
 * <p>命中后立即移除（一次性消费），避免长期误伤用户自己重复发送的相同内容。</p>
 *
 * <p>另外提供<b>群发熔断</b>：短时间内多次大批量群发会被拒绝，防止一次误触发把账号打成
 * 营销号（真实事故里 0.57 秒内向 545 个群发出 1090 条）。</p>
 */
public final class SelfMsgGuard {

    private static final String TAG = "SelfMsgGuard";

    /** 精确键 TTL：覆盖「发送 → 服务端回包 → 落库回调」的完整窗口。 */
    private static final long TTL_KEY_MS = 120_000L;
    /** 仅内容哈希的兜底 TTL：短窗口，避免误伤用户真实重复消息。 */
    private static final long TTL_HASH_MS = 20_000L;
    private static final int MAX_ENTRIES = 600;

    private static final ConcurrentHashMap<String, Long> sByKey = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> sByHash = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> sMsgIds = new ConcurrentHashMap<>();
    /** 无正文消息（语音等）按「会话+类型」登记，TTL 更短。 */
    private static final ConcurrentHashMap<String, Long> sByType = new ConcurrentHashMap<>();
    private static final long TTL_TYPE_MS = 45_000L;

    /** 群发熔断：最近若干次大批量广播的时间戳。 */
    private static final ArrayDeque<Long> sBatches = new ArrayDeque<>();
    /** 单批目标数超过该值即视为「大批量群发」，参与熔断统计。 */
    private static final int BATCH_THRESHOLD = 20;
    /** 60 秒内允许的大批量群发次数。 */
    private static final int BATCH_PER_MINUTE = 6;
    /** 两次大批量群发之间的最小间隔。 */
    private static final long BATCH_MIN_GAP_MS = 3_000L;
    /** 单次广播硬上限：超过直接拒绝（防止配置错误把全部群一次性打穿）。 */
    public static final int HARD_MAX_TARGETS = 600;

    private SelfMsgGuard() {
    }

    // ==================== 登记 ====================

    /**
     * 登记一条模块即将发出（或刚刚发出）的消息。
     *
     * @param talker  目标会话（wxid / xxx@chatroom）
     * @param type    微信消息类型（文本 1、语音 34、图片 3……）
     * @param content 消息内容（语音等无正文的可传 null）
     */
    public static void mark(String talker, int type, String content) {
        try {
            long now = System.currentTimeMillis();
            String n = norm(content);
            if (talker != null && !talker.isEmpty()) {
                sByKey.put(key(talker, type, n), now + TTL_KEY_MS);
                // 无正文消息（语音/图片等）额外登记「会话+类型」通配键
                if (n.isEmpty()) sByType.put(talker + "|" + type, now + TTL_TYPE_MS);
            }
            if (!n.isEmpty()) {
                sByHash.put(hash(n), now + TTL_HASH_MS);
            }
            trim();
        } catch (Throwable ignored) {
        }
    }

    /** 登记模块自己入库得到的 msgId，接收侧见到同一 msgId 直接跳过。 */
    public static void markMsgId(long msgId) {
        if (msgId == 0L) return;
        try {
            sMsgIds.put(msgId, System.currentTimeMillis() + TTL_KEY_MS);
            if (sMsgIds.size() > MAX_ENTRIES) {
                long now = System.currentTimeMillis();
                Iterator<Map.Entry<Long, Long>> it = sMsgIds.entrySet().iterator();
                while (it.hasNext()) {
                    if (it.next().getValue() < now) it.remove();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================== 判定 ====================

    /** 该 msgId 是否是模块自己入库的消息。 */
    public static boolean isSelfMsgId(long msgId) {
        if (msgId == 0L) return false;
        Long exp = sMsgIds.get(msgId);
        if (exp == null) return false;
        if (exp < System.currentTimeMillis()) {
            sMsgIds.remove(msgId);
            return false;
        }
        return true;
    }

    /**
     * 判定一条落库消息是否由模块自己发出；命中即消费（同一条只拦一次）。
     *
     * @return true = 模块自发消息，接收侧必须跳过
     */
    public static boolean consume(String talker, int type, String content) {
        try {
            long now = System.currentTimeMillis();
            String n = norm(content);

            if (talker != null && !talker.isEmpty()) {
                String k = key(talker, type, n);
                Long exp = sByKey.get(k);
                if (exp != null) {
                    if (exp >= now) {
                        sByKey.remove(k);
                        if (!n.isEmpty()) sByHash.remove(hash(n));
                        LogWriter.log(TAG, "拦截模块自发消息 key=" + type + " talker=" + talker
                                + " content=" + trunc(n));
                        return true;
                    }
                    sByKey.remove(k);
                }
            }

            if (!n.isEmpty()) {
                String h = hash(n);
                Long exp = sByHash.get(h);
                if (exp != null) {
                    if (exp >= now) {
                        sByHash.remove(h);
                        LogWriter.log(TAG, "拦截模块自发消息(内容兜底) type=" + type
                                + " talker=" + talker + " content=" + trunc(n));
                        return true;
                    }
                    sByHash.remove(h);
                }
            }

            // 无正文消息（语音/图片等）：按「会话+类型」通配拦截
            if (talker != null && !talker.isEmpty()) {
                String tk = talker + "|" + type;
                Long exp = sByType.get(tk);
                if (exp != null) {
                    if (exp >= now) {
                        sByType.remove(tk);
                        LogWriter.log(TAG, "拦截模块自发消息(会话+类型) type=" + type + " talker=" + talker);
                        return true;
                    }
                    sByType.remove(tk);
                }
            }
            return false;
        } catch (Throwable t) {
            // 护栏自身异常时按「非自发」放行，避免把用户消息吃掉
            return false;
        }
    }

    /** 只看不消费（供只读判断使用）。 */
    public static boolean isSelf(String talker, int type, String content) {
        // 零宽水印：内容本身即携带模块标记，跨进程/跨分身都能识别
        if (hasWatermark(content)) return true;
        // 模块固定提示语（点歌受理提示等）：不依赖本地账本，分身/多设备同步过来的同样拦住
        if (isModuleNoticeText(content)) return true;
        try {
            long now = System.currentTimeMillis();
            String n = norm(content);
            if (talker != null && !talker.isEmpty()) {
                Long exp = sByKey.get(key(talker, type, n));
                if (exp != null && exp >= now) return true;
                Long texp = sByType.get(talker + "|" + type);
                if (texp != null && texp >= now) return true;
            }
            if (!n.isEmpty()) {
                Long exp = sByHash.get(hash(n));
                return exp != null && exp >= now;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ==================== 零宽水印 ====================

    /** 零宽空格 + 零宽非连接符。肉眼不可见，接收方阅读无感。 */
    public static final String MARK = "\u200B\u200C";
    /** 水印允许出现在正文前多少个字符内（@ 提及前缀可能很长）。 */
    private static final int WATERMARK_SCAN = 96;

    /**
     * 给模块外发文本打水印（幂等）。
     *
     * <p>为什么不一律加在最前面：群聊 @ 消息的高亮依赖 content 以 {@code @昵称\u2005} 开头，
     * 在 {@code @} 前插零宽字符会让高亮失效。因此遇到 {@code @} 开头的内容时，
     * 水印插在<b>提及前缀之后</b>、正文之前。</p>
     */
    public static String watermark(String content) {
        if (content == null || content.isEmpty()) return content;
        if (hasWatermark(content)) return content;
        if (content.charAt(0) != '@') return MARK + content;

        // @ 提及前缀以 \u2005 分隔；兼容「@\u200B 正文」这种单空格写法
        int sep = content.indexOf('\u2005');
        if (sep >= 0) return content.substring(0, sep + 1) + MARK + content.substring(sep + 1);
        int sp = content.indexOf(' ');
        if (sp > 0) return content.substring(0, sp + 1) + MARK + content.substring(sp + 1);
        return content + MARK;
    }

    /** 内容是否已带水印（正文起点或 @ 提及前缀之后）。 */
    public static boolean hasWatermark(String content) {
        if (content == null || content.isEmpty()) return false;
        if (content.startsWith(MARK)) return true;
        if (content.charAt(0) != '@') return false;
        int i = content.indexOf(MARK);
        return i > 0 && i <= WATERMARK_SCAN;
    }

    // ==================== 模块固定提示语识别 ====================

    private static volatile String sNoticeTpl = null;
    private static volatile java.util.regex.Pattern sNoticePat = null;
    private static volatile String sNoticeTplCache = null;

    /**
     * 判断文本是否是模块自己生成的提示语（当前覆盖点歌受理提示）。
     *
     * <p>为什么不能只靠本地账本：真实事故发生在<b>分身</b>环境。A 分身（或另一台设备）
     * 发出点歌提示后，微信把这条消息同步到 B 分身，B 分身收到的是「别人发来的」消息
     * （isSend=0 + 真实 msgSvrId），本地账本里根本没有这条记录 —— 于是被自动转发
     * 原样群发到 545 个群。所以必须有一条<b>只看内容</b>的识别规则。</p>
     */
    public static boolean isModuleNoticeText(String content) {
        if (content == null) return false;
        try {
            String s = norm(content);
            if (s.isEmpty()) return false;
            // 排队后缀「（前面还有 N 位）」是模块追加的，先去掉再比对
            int idx = s.indexOf("（前面还有 ");
            if (idx > 0) s = s.substring(0, idx).trim();

            String tpl = cachedNoticeTemplate();
            if (tpl == null || tpl.trim().isEmpty()) {
                tpl = com.leshao.v3.music.OnlineMusicPrefs.DEFAULT_NOTICE_TEXT;
            }
            tpl = tpl.trim();
            if (!tpl.isEmpty() && s.equals(tpl)) return true;

            java.util.regex.Pattern p = noticePattern(tpl);
            if (p != null && p.matcher(s).matches()) return true;

            // 点歌失败/其它模块固定提示
            if (s.startsWith("点歌失败:") || s.startsWith("点歌失败：")) return true;
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static volatile long sNoticeTplAt = 0L;

    /** 提示语模板 5 秒缓存，避免每条消息都读一次 SharedPreferences。 */
    private static String cachedNoticeTemplate() {
        long now = System.currentTimeMillis();
        if (now - sNoticeTplAt < 5_000L && sNoticeTplCache != null) return sNoticeTplCache;
        try {
            sNoticeTplCache = com.leshao.v3.music.OnlineMusicPrefs.noticeText();
        } catch (Throwable t) {
            sNoticeTplCache = null;
        }
        sNoticeTplAt = now;
        return sNoticeTplCache;
    }

    /** 把提示语模板编译成正则（{song} → 任意歌名）；按模板字符串缓存。 */
    private static java.util.regex.Pattern noticePattern(String tpl) {
        if (tpl == null || tpl.isEmpty()) return null;
        if (tpl.equals(sNoticeTpl)) return sNoticePat;
        try {
            // 不能用「先 quote 整串再替换」——Pattern.quote 会生成 \Q...\E 块，
            // 块内的 {song} 已不是可替换的转义序列。改为按占位符切分后逐段 quote。
            String[] parts = tpl.split(java.util.regex.Pattern.quote("{song}"), -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) sb.append(".*");
                sb.append(java.util.regex.Pattern.quote(parts[i]));
            }
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(sb.toString());
            sNoticeTpl = tpl;
            sNoticePat = p;
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 群发熔断 ====================

    /**
     * 是否允许执行一次批量发送。
     *
     * @param feature     功能名（日志用）
     * @param targetCount 本批目标数量
     * @return false = 拒绝执行（已被熔断）
     */
    public static boolean allowBroadcast(String feature, int targetCount) {
        if (targetCount <= 0) return false;
        if (targetCount > HARD_MAX_TARGETS) {
            LogWriter.log(TAG, "熔断: " + feature + " 目标数 " + targetCount
                    + " 超过硬上限 " + HARD_MAX_TARGETS + "，已拒绝执行");
            return false;
        }
        if (targetCount < BATCH_THRESHOLD) return true;
        try {
            long now = System.currentTimeMillis();
            synchronized (sBatches) {
                while (!sBatches.isEmpty() && now - sBatches.peekFirst() > 60_000L) {
                    sBatches.pollFirst();
                }
                if (!sBatches.isEmpty() && now - sBatches.peekLast() < BATCH_MIN_GAP_MS) {
                    LogWriter.log(TAG, "熔断: " + feature + " 短时间内重复大批量群发("
                            + targetCount + " 目标)，已拒绝");
                    return false;
                }
                if (sBatches.size() >= BATCH_PER_MINUTE) {
                    LogWriter.log(TAG, "熔断: " + feature + " 60 秒内大批量群发已达 "
                            + BATCH_PER_MINUTE + " 次，已拒绝");
                    return false;
                }
                sBatches.addLast(now);
            }
            return true;
        } catch (Throwable ignored) {
            return true;
        }
    }

    // ==================== 内部 ====================

    private static String key(String talker, int type, String normContent) {
        return talker + "|" + type + "|" + hash(normContent);
    }

    /** 稳定内容指纹：长度 + hashCode，足够区分且不占内存。 */
    private static String hash(String s) {
        return s.length() + ":" + Integer.toHexString(s.hashCode());
    }

    /**
     * 内容归一化：去掉零宽水印、去掉群消息「wxid_xxx:\n」发送者前缀、压缩空白。
     * 目的：模块登记的内容与落库读回的内容能对上。
     */
    private static String norm(String content) {
        if (content == null) return "";
        String s = content.replace("\u200B", "").replace("\u200C", "").replace("\uFEFF", "");
        s = s.trim();
        int nl = s.indexOf('\n');
        if (nl > 0 && nl <= 48) {
            String head = s.substring(0, nl);
            if (head.endsWith(":") && !head.contains(" ") && head.indexOf('<') < 0) {
                s = s.substring(nl + 1).trim();
            }
        }
        return s;
    }

    private static void trim() {
        if (sByKey.size() <= MAX_ENTRIES && sByHash.size() <= MAX_ENTRIES) return;
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<String, Long>> it = sByKey.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() < now) it.remove();
        }
        for (Iterator<Map.Entry<String, Long>> it = sByHash.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() < now) it.remove();
        }
        for (Iterator<Map.Entry<String, Long>> it = sByType.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() < now) it.remove();
        }
        // 仍超限则整体清空，宁可漏拦一次也不无限增长
        if (sByKey.size() > MAX_ENTRIES) sByKey.clear();
        if (sByHash.size() > MAX_ENTRIES) sByHash.clear();
    }

    private static String trunc(String s) {
        if (s == null) return "";
        return s.length() <= 24 ? s : s.substring(0, 24) + "…";
    }
}
