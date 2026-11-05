package com.leshao.ai.hook.wechat;

import android.util.Log;

import com.leshao.ai.hook.HookEntry;
import com.leshao.ai.hook.dexkit.DexKitAdapter;
import com.leshao.v3.LogWriter;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 接收消息 Hook（文档 §4 线路一 / §16.4）。
 * <p>
 * Hook {@code f9.Bb(e9, boolean)} —— 微信消息 insert 总闸门：
 * 群聊、私聊、系统消息、自己发出的消息全部走这里。
 * <p>
 * 取数一律用<b>未混淆字段名</b>（im.c8：field_talker / field_type / field_isSend /
 * field_msgSvrId / field_createTime / field_content / field_fromUsername），
 * 按文档 §19 字段名优先原则保证跨版本稳定。
 * <p>
 * 过滤链：防循环水印 → 只处理收到的（isSend==0）→ 只处理纯文本（type==1）→ 非空校验。
 * 判定通过后投递 {@link TriggerEngine}（其内部 post 到工作线程，
 * 因 f9.Bb 位于 DB 写锁内，严禁在此同步执行网络请求，文档 §17.2）。
 */
public final class MsgReceiveHook {

    private static final String TAG = "LeshaoAI.Recv";

    /** 纯文本消息类型。 */
    private static final int TYPE_TEXT = 1;
    private static final int TYPE_IMAGE = 3;

    private static volatile boolean installed;

    private MsgReceiveHook() {
    }

    /** 安装收消息 hook（幂等）。 */
    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (installed) {
            return;
        }
        installed = true;
        try {
            installInternal(lpparam);
        } catch (Throwable t) {
            installed = false;
            Log.w(TAG, "install 失败: " + t);
        }
    }

    private static void installInternal(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        // v1042: 微信经 Tinker 热修复时 f9/e9 由 DelegateLastClassLoader 真实加载,
        // lpparam.classLoader 只是 base.apk 平行副本 → hookAllMethods 挂到平行副本类上
        // 运行时消息入库不经过它 → 零捕获。改用 Tinker 真实 CL 定位。
        try {
            ClassLoader tk = com.leshao.v3.hook.VersionCompat.findTinkerClassLoader(cl);
            if (tk != null && !tk.getClass().getName().contains("Leshao")
                    && tk != cl) {
                LogWriter.log(TAG, "使用 Tinker 真实 CL " + tk.getClass().getSimpleName()
                        + " 定位 f9");
                cl = tk;
            }
        } catch (Throwable ignored) {
        }
        Class<?> f9 = DexKitAdapter.findMsgInfoStorageClass();
        if (f9 == null) {
            Log.w(TAG, "f9(MsgInfoStorage) 未定位，接收链路不可用");
            return;
        }
        // v1042: DexKitAdapter 类对象可能仍来自 hook appClassLoader 平行副本,
        // 若当前已切换到 Tinker CL, 按类名重新在真实 CL 上解析 f9/e9。BreedingClone 类
        // 名不混淆, 直接按全名重新加载即是真实类对象。
        try {
            if (cl != lpparam.classLoader) {
                Class<?> realF9 = XposedHelpers.findClass(f9.getName(), cl);
                if (realF9 != null) {
                    LogWriter.log(TAG, "f9 重新在真实 CL 加载: " + realF9.getName());
                    f9 = realF9;
                }
            }
        } catch (Throwable ignored) {
        }
        Class<?> e9 = DexKitAdapter.findMsgInfoClass();
        try {
            if (e9 != null && cl != lpparam.classLoader) {
                Class<?> realE9 = XposedHelpers.findClass(e9.getName(), cl);
                if (realE9 != null) {
                    e9 = realE9;
                }
            }
        } catch (Throwable ignored) {
        }

        XC_MethodHook callback = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.args != null && param.args.length > 0) {
                        onMessageInserted(param.args[0]);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "afterHookedMethod 异常: " + t);
                }
            }
        };

        // v1075: 微信不同版本收消息入库走不同方法, 只挂 Bb 会漏收。
        // v3.0.557: 8.0.79 中 Bb 已改名 Nb/Mb，补充方法名数组（对齐 AutoGroupQrHook）。
        int hooked = 0;
        for (java.lang.reflect.Method m : f9.getDeclaredMethods()) {
            String mn = m.getName();
            if (!"Nb".equals(mn) && !"Mb".equals(mn) && !"Bb".equals(mn)
                    && !"Db".equals(mn) && !"Hb".equals(mn) && !"yb".equals(mn)
                    && !"insertMsgInfo".equals(mn) && !"processAddMsg".equals(mn)) {
                continue;
            }
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length < 1 || pts[0] == null) {
                continue;
            }
            String p0 = pts[0].getName();
            if (!p0.endsWith(".e9") && !p0.contains("MsgInfo")) {
                continue;
            }
            XposedBridge.hookMethod(m, callback);
            hooked++;
        }
        if (hooked == 0) {
            // 兜底: 按名挂 Bb（旧版本）
            XposedBridge.hookAllMethods(f9, "Bb", callback);
            LogWriter.log(TAG, "已 hook " + f9.getName() + ".Bb (兜底 e9="
                    + (e9 == null ? "?" : e9.getName()) + ")");
        } else {
            LogWriter.log(TAG, "已 hook " + f9.getName()
                    + " insert 方法 " + hooked + " 个 (Nb/Mb/Bb/Db/Hb/yb, e9="
                    + (e9 == null ? "?" : e9.getName()) + ")");
        }
    }

    /** svrId 去重, 防止同一消息经多个入库方法重复触发（容量上限自动淘汰最旧）。 */
    private static final java.util.Set<Long> seenSvrIds = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(
                    new java.util.LinkedHashMap<Long, Boolean>(500, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(java.util.Map.Entry<Long, Boolean> eldest) {
                            return size() > 500;
                        }
                    }));
    /** 图片消息 svrId 去重（豆包图片识别用，容量上限自动淘汰最旧）。 */
    private static final java.util.Set<Long> seenImageSvrIds = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(
                    new java.util.LinkedHashMap<Long, Boolean>(300, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(java.util.Map.Entry<Long, Boolean> eldest) {
                            return size() > 300;
                        }
                    }));

    /** 待绑定问题的图片（talker → PendingImage），10 秒内到来的文字会作为识别问题。 */
    private static final java.util.Map<String, PendingImage> pendingImages =
            new java.util.HashMap<String, PendingImage>();
    /** 最近文字（供「先文字后图片」场景绑定），8 秒有效。 */
    private static final java.util.Map<String, String[]> recentTexts =
            new java.util.HashMap<String, String[]>();

    private static final class PendingImage {
        final String talker;
        final String path;
        final long svrId;
        final long timestamp;
        final String sender;
        final boolean isGroup;
        final Object msgInfo;
        final com.leshao.ai.config.ConversationConfig.Entry ov;
        final com.leshao.ai.config.AppConfig cfg;
        final java.util.concurrent.atomic.AtomicReference<String> question =
                new java.util.concurrent.atomic.AtomicReference<String>(null);
        PendingImage(String talker, String path, long svrId, String sender,
                     boolean isGroup, Object msgInfo,
                     com.leshao.ai.config.ConversationConfig.Entry ov,
                     com.leshao.ai.config.AppConfig cfg) {
            this.talker = talker;
            this.path = path;
            this.svrId = svrId;
            this.sender = sender;
            this.isGroup = isGroup;
            this.msgInfo = msgInfo;
            this.ov = ov;
            this.cfg = cfg;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private static final java.util.Set<Integer> seenIdentity = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(
                    new java.util.LinkedHashMap<Integer, Boolean>(500, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(java.util.Map.Entry<Integer, Boolean> eldest) {
                            return size() > 500;
                        }
                    }));

    /** 处理一条落库消息。 */
    private static void onMessageInserted(Object msg) {
        if (msg == null) {
            return;
        }
        // 字段名取数（文档 §19：实体字段名与 SQLite 列名绑定，永不被混淆）
        String talker = callStr(msg, "N0");
        if (talker == null || talker.isEmpty()) {
            talker = readString(msg, "field_talker");
        }
        String content = readContent(msg);
        Integer type = callInt(msg, "getType");
        if (type == null) {
            type = readInt(msg, "field_type");
        }
        Integer isSend = callInt(msg, "z0");
        if (isSend == null) {
            isSend = readInt(msg, "field_isSend");
        }
        Long svrId = callLong(msg, "H0");
        if (svrId == null || svrId == 0L) {
            svrId = readLong(msg, "field_msgSvrId");
        }
        Long createTime = readLong(msg, "field_createTime");
        String fromUser = callStr(msg, "s0");
        if (fromUser == null || fromUser.isEmpty()) {
            fromUser = readString(msg, "field_fromUsername");
        }

        // ① 防循环：机器人自己所发（零宽水印）
        if (SendGuard.isBotSent(content)) {
            return;
        }
        // ①.5 v3.0.580: 模块自发消息护栏（点歌提示/AI 回复/红包播报等）
        // 这些消息一旦以接收形态落库，会被当成用户新消息送 AI / 触发自动转发。
        try {
            if (com.leshao.v3.hook.SelfMsgGuard.isSelf(talker, type == null ? 1 : type, content)) {
                LogWriter.log(TAG, "跳过模块自发消息 talker=" + talker);
                return;
            }
        } catch (Throwable ignored) {
        }
        // ② 自己发出的消息：反推缓存 wxid 后跳过（文档 §8.4）
        // v3.0.580: isSend 读不到时按「自己发出」处理（失败即跳过），
        // 旧实现读不到就继续往下走，模块插入的消息会被当作收到的新消息回复。
        if (isSend == null) {
            LogWriter.log(TAG, "跳过: isSend 读取失败 talker=" + talker);
            return;
        }
        if (isSend != 0) {
            StorageHub.get().cacheSelfWxidFrom(fromUser);
            return;
        }
        // ③ 图片消息：走豆包图片识别（带图对话）并回复原会话（须通过会话名单门控）
        if (type != null && type == TYPE_IMAGE) {
            if (com.leshao.ai.hook.wechat.TriggerEngine.isConversationEnabled(talker)) {
                handleImageMessage(talker, msg, svrId == null ? 0L : svrId, fromUser);
            } else {
                LogWriter.log(TAG, "跳过图片识别: 未配置/未启用会话 talker=" + talker);
            }
            return;
        }
        // ③ 只处理纯文本
        if (type == null || type != TYPE_TEXT || content == null || content.isEmpty()) {
            return;
        }
        // ④ 空 talker
        if (talker == null || talker.isEmpty()) {
            return;
        }
        // ⑤ 去重: svrId 优先, 无 svrId 时用对象身份
        if (svrId != null && svrId != 0L) {
            if (!seenSvrIds.add(svrId)) {
                return;
            }
        } else {
            int id = System.identityHashCode(msg);
            if (!seenIdentity.add(id)) {
                return;
            }
        }

        LogWriter.log(TAG, "收到文本 talker=" + talker + " svrId=" + svrId
                + " content='" + trunc(content) + "'");

        // v1104: 点歌指令在 AI 接收边界统一拦截 —— 命中即由点歌服务接管, 绝不送入 AI。
        // 点歌只依赖 OnlineMusicPrefs, 与 AI 个性化配置无关; 私聊/群聊都经此 insert 总闸门。
        // 非白名单的点歌指令同样过滤(丢弃), 避免被 AI 当作普通聊天回复。
        try {
            if (com.leshao.v3.music.DianGeService.isEnabledCommand(talker, content)) {
                boolean handled = com.leshao.v3.music.DianGeService.maybeHandle(
                        HookEntry.appClassLoader, talker, content, msg);
                LogWriter.log(TAG, "点歌指令拦截" + (handled ? "(已接管)" : "(未接管:非白名单/冷却)")
                        + ", 不送 AI talker=" + talker);
                return;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "点歌指令拦截异常, 继续走 AI: " + t);
        }

        // ★ 会话级名单门控：未配置/未启用的会话不触发任何 AI（豆包文本/图片/原 AI）
        if (!com.leshao.ai.hook.wechat.TriggerEngine.isConversationEnabled(talker)) {
            LogWriter.log(TAG, "跳过豆包文本/图片: 未配置/未启用会话 talker=" + talker);
            return;
        }

        // ★ 豆包文本模型：若开启，文本对话走豆包网页模型会话（复用傻包登录态）
        try {
            if (com.leshao.ai.hook.wechat.AIBotCore.config().isDoubaoTextEnabled()) {
                doubaoChatReply(talker, content, fromUser, msg, HookEntry.appClassLoader);
                return;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "豆包文本模型开关读取失败: " + t);
        }

        // ★ 图片+文字配对：若该会话 10 秒内有待识别图片，文字作为图片问题，不单独走文本 AI
        synchronized (pendingImages) {
            PendingImage pi = pendingImages.get(talker);
            if (pi != null && System.currentTimeMillis() - pi.timestamp < 10_000L) {
                pi.question.set(content);
                pendingImages.remove(talker);
                LogWriter.log(TAG, "图片+文字配对 talker=" + talker
                        + " text='" + trunc(content) + "'");
                return;
            }
            // 缓存最近文字（供「先文字后图片」场景）
            recentTexts.put(talker, new String[]{content, String.valueOf(System.currentTimeMillis())});
            // 容量上限 + 过期清理（防止无界增长内存泄漏）
            if (recentTexts.size() > 200) {
                final long now = System.currentTimeMillis();
                java.util.Iterator<java.util.Map.Entry<String, String[]>> it = recentTexts.entrySet().iterator();
                while (it.hasNext()) {
                    java.util.Map.Entry<String, String[]> e = it.next();
                    String[] v = e.getValue();
                    if (v == null || v.length < 2) { it.remove(); continue; }
                    try {
                        if (now - Long.parseLong(v[1]) > 10000) it.remove();
                    } catch (Throwable ignored) {}
                }
                if (recentTexts.size() > 200) {
                    // 仍超限：删除最早的一半
                    java.util.List<String> keys = new java.util.ArrayList<>(recentTexts.keySet());
                    keys.sort(new java.util.Comparator<String>() {
                        @Override public int compare(String a, String b) {
                            long ta = 0L, tb = 0L;
                            try { ta = Long.parseLong(recentTexts.get(a)[1]); } catch (Throwable ignored) {}
                            try { tb = Long.parseLong(recentTexts.get(b)[1]); } catch (Throwable ignored) {}
                            return Long.compare(ta, tb);
                        }
                    });
                    for (int i = 0; i < 100 && i < keys.size(); i++) recentTexts.remove(keys.get(i));
                }
            }
        }

        TriggerEngine.dispatch(talker, content, msg, svrId == null ? 0L : svrId,
                createTime == null ? 0L : createTime);
    }

    /** 豆包网页纯文本模型会话：注入 cookie → 豆包 completion → 统一 AI 回复（@/引用/语音）。 */
    private static void doubaoChatReply(final String talker, final String text,
                                        final String sender, final Object msgInfo,
                                        final ClassLoader cl) {
        try {
            final boolean isGroup = talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom");
            final com.leshao.ai.config.AppConfig cfg = com.leshao.ai.hook.wechat.AIBotCore.config();
            final com.leshao.ai.config.ConversationConfig cc =
                    com.leshao.ai.hook.wechat.AIBotCore.conversationConfig();
            final com.leshao.ai.config.ConversationConfig.Entry ov =
                    cc != null ? cc.get(talker) : null;
            String chatCookie = com.leshao.v3.service.DoubaoTtsV2Bridge.resolveChatCookie();
            if (chatCookie.isEmpty()) {
                LogWriter.log(TAG, "豆包文本返回空（分身未配置豆包 Cookie，请在 AI 助手 → 傻包音色库配置 手动粘贴或登录保存） talker=" + talker);
                return;
            }
            com.leshao.v3.service.DoubaoChatDirect.INSTANCE.setCookie(chatCookie);
            com.leshao.v3.service.DoubaoChatDirect.INSTANCE.setUa(
                    com.leshao.ai.hook.wechat.MsgReceiveHook.resolveChatUA());
            // ★ 上下文记忆：记录用户消息（豆包侧由 conversation_id 原生管理多轮上下文）
            final com.leshao.ai.memory.ChatMemory mem = com.leshao.ai.hook.wechat.AIBotCore.memory();
            if (mem != null) {
                mem.add(talker, "user", text);
                try { mem.persist(); } catch (Throwable ignored) {}
            }
            com.leshao.v3.service.DoubaoChatDirect.INSTANCE.chat(
                    text, talker,
                    new kotlin.jvm.functions.Function1<String, kotlin.Unit>() {
                        @Override
                        public kotlin.Unit invoke(String answer) {
                            try {
                                if (answer != null && !answer.isEmpty()) {
                                    LogWriter.log(TAG, "豆包文本回复 talker=" + talker
                                            + " len=" + answer.length()
                                            + " content='" + trunc(answer) + "'");
                                    // 回复写回模块记忆
                                    final com.leshao.ai.memory.ChatMemory mem2 =
                                            com.leshao.ai.hook.wechat.AIBotCore.memory();
                                    if (mem2 != null) {
                                        mem2.add(talker, "assistant", answer);
                                        try { mem2.persist(); } catch (Throwable ignored) {}
                                    }
                                    com.leshao.ai.hook.wechat.TriggerEngine.sendReply(
                                            talker, answer, text, isGroup, sender,
                                            msgInfo, ov, cfg, cl);
                                } else {
                                    LogWriter.log(TAG, "豆包文本返回空（Cookie 失效/网络/风控？） talker="
                                            + talker);
                                }
                            } catch (Throwable t) {
                                LogWriter.log(TAG, "豆包文本回复失败: " + t);
                            }
                            return kotlin.Unit.INSTANCE;
                        }
                    });
        } catch (Throwable t) {
            LogWriter.log(TAG, "doubaoChatReply err: " + t);
        }
    }

    /** 图片消息 → 豆包图片识别（v2.5 DoubaoImgChatDirect 直连）→ 统一 AI 回复（@/引用/语音）。
     *  图片与文字是两条独立消息：图片先到，等待 1.5s 内到来的文字作为识别问题；
     *  若先有文字后发图片，则用 8 秒内缓存的文字作为问题。
     *  会话级「图片识别回复」开关优先，未配置时跟随全局开关。 */
    private static void handleImageMessage(String talker, Object msg, long svrId, String sender) {
        try {
            if (talker == null || talker.isEmpty()) return;
            if (svrId != 0L) {
                if (!seenImageSvrIds.add(svrId)) return;
            }
            final boolean isGroup = talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom");
            final com.leshao.ai.config.AppConfig cfg = com.leshao.ai.hook.wechat.AIBotCore.config();
            final com.leshao.ai.config.ConversationConfig cc =
                    com.leshao.ai.hook.wechat.AIBotCore.conversationConfig();
            final com.leshao.ai.config.ConversationConfig.Entry ov =
                    cc != null ? cc.get(talker) : null;
            // 会话级「图片识别回复」开关优先，未配置则跟随全局开关
            boolean imgEnabled = (ov != null && ov.imageReplyEnabled != null)
                    ? ov.imageReplyEnabled.booleanValue() : cfg.isImageReplyEnabled();
            if (!imgEnabled) {
                LogWriter.log(TAG, "图片识别回复已关闭，跳过 talker=" + talker);
                return;
            }
            final String path = com.leshao.v3.hook.AutoGroupQrHook.getImgPath(msg);
            LogWriter.log(TAG, "收到图片 talker=" + talker + " svrId=" + svrId + " path=" + path);
            if (path == null || path.isEmpty()) return;

            final PendingImage pi = new PendingImage(talker, path, svrId, sender,
                    isGroup, msg, ov, cfg);
            synchronized (pendingImages) {
                // 先文字后图片：检查 8 秒内该会话是否有最近文字
                String[] rt = recentTexts.get(talker);
                if (rt != null && System.currentTimeMillis() - Long.parseLong(rt[1]) < 8_000L) {
                    pi.question.set(rt[0]);
                    recentTexts.remove(talker);
                } else {
                    pendingImages.put(talker, pi);   // 等待后到的文字绑定
                }
            }
            // 延迟 1.5s 后识别：给「图片后跟文字」留绑定窗口
            Thread t = new Thread(() -> {
                try { Thread.sleep(1500L); } catch (InterruptedException ignored) {}
                synchronized (pendingImages) {
                    PendingImage cur = pendingImages.get(pi.talker);
                    if (cur == pi) pendingImages.remove(pi.talker);
                }
                String question = pi.question.get();
                if (question == null) {
                    question = "请识别这张图片的内容，并用中文简洁回复我。";
                }
                // 豆包网页协议不支持人设提示词，直接使用问题文本
                recognizeAndReply(pi, question, HookEntry.appClassLoader);
            }, "leshao-img-ai");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            LogWriter.log(TAG, "handleImageMessage err: " + t);
        }
    }

    /** 解析图片真实路径（未落地重试 3 次）→ 注入 cookie → 豆包识别 → 统一回复。 */
    /** 读取图片文件字节（兼容 API < 26，不用 java.nio.file.Files） */
    private static byte[] readFileBytes(String path) {
        java.io.File f = new java.io.File(path);
        if (!f.exists()) return null;
        java.io.FileInputStream fis = null;
        try {
            fis = new java.io.FileInputStream(f);
            int len = (int) f.length();
            if (len <= 0) return null;
            byte[] buf = new byte[len];
            int off = 0;
            while (off < len) {
                int r = fis.read(buf, off, len - off);
                if (r < 0) break;
                off += r;
            }
            return off == len ? buf : java.util.Arrays.copyOf(buf, off);
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (fis != null) fis.close(); } catch (Throwable ignored) {}
        }
    }

    private static void recognizeAndReply(final PendingImage pi, final String question,
                                           final ClassLoader cl) {
        Thread t = new Thread(() -> {
            try {
                String real = null;
                for (int i = 0; i < 3; i++) {
                    real = com.leshao.v3.hook.AutoGroupQrHook.resolveImgPath(cl, pi.path);
                    if (real != null && new java.io.File(real).exists()) break;
                    try { Thread.sleep(2000L); } catch (InterruptedException ignored) {}
                    real = null;
                }
                if (real == null || !new java.io.File(real).exists()) {
                    LogWriter.log(TAG, "图片未落地，跳过识别 talker=" + pi.talker);
                    return;
                }
                byte[] bytes = readFileBytes(real);
                if (bytes == null || bytes.length == 0) return;
                doRecognize(pi, bytes, question, cl);
            } catch (Throwable t2) {
                LogWriter.log(TAG, "图片识别准备失败: " + t2);
            }
        }, "leshao-img-ai-solve");
        t.setDaemon(true);
        t.start();
    }

    /** 解析豆包 completion 用 UA：用户手动/网页登录 Cookie 存在时用对应 WebView UA，否则用官方默认浏览器 UA。 */
    private static String resolveChatUA() {
        final String defaultUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
        try {
            String manual = com.leshao.v3.wm.utils.WmPrefs.getStr("doubao_chat_cookie", "");
            if (manual.isEmpty()) return defaultUA;
            String ua = com.leshao.v3.wm.utils.WmPrefs.getStr("doubao_webview_ua", "");
            return ua.isEmpty() ? defaultUA : ua;
        } catch (Throwable t) {
            return defaultUA;
        }
    }

    /** 注入 cookie 并调用豆包图片识别，成功后走 TriggerEngine 统一回复（@/引用/语音）。 */
    private static void doRecognize(final PendingImage pi, byte[] imageBytes,
                                     final String question, final ClassLoader cl) {
        try {
            String usedCookie = com.leshao.v3.service.DoubaoTtsV2Bridge.resolveChatCookie();
            if (usedCookie.isEmpty()) {
                LogWriter.log(TAG, "图片识别返回空（分身未配置豆包 Cookie，请在 AI 助手 → 傻包音色库配置 手动粘贴或登录保存） talker=" + pi.talker);
                return;
            }
            com.leshao.v3.service.DoubaoImgChatDirect.INSTANCE.setCookie(usedCookie);
            com.leshao.v3.service.DoubaoImgChatDirect.INSTANCE.setUa(
                    com.leshao.ai.hook.wechat.MsgReceiveHook.resolveChatUA());
            LogWriter.log(TAG, "图片识别启动 talker=" + pi.talker
                    + " cookieLen=" + usedCookie.length());
            com.leshao.v3.service.DoubaoImgChatDirect.INSTANCE.recognize(
                    imageBytes, question,
                    new kotlin.jvm.functions.Function1<String, kotlin.Unit>() {
                        @Override
                        public kotlin.Unit invoke(String answer) {
                            try {
                                if (answer != null && !answer.isEmpty()) {
                                    LogWriter.log(TAG, "图片识别结果 talker=" + pi.talker
                                            + " len=" + answer.length()
                                            + " content='" + trunc(answer) + "'");
                                    // 统一回复：支持 @回复 / 引用回复 / 语音回复（与文本 AI 一致）
                                    com.leshao.ai.hook.wechat.TriggerEngine.sendReply(
                                            pi.talker, answer, "[图片]",
                                            pi.isGroup, pi.sender, pi.msgInfo,
                                            pi.ov, pi.cfg, cl);
                                } else {
                                    LogWriter.log(TAG, "图片识别返回空（Cookie失效/网络/风控） talker="
                                            + pi.talker);
                                }
                            } catch (Throwable t) {
                                LogWriter.log(TAG, "图片识别回复失败: " + t);
                            }
                            return kotlin.Unit.INSTANCE;
                        }
                    });
        } catch (Throwable t) {
            LogWriter.log(TAG, "doRecognize err: " + t);
        }
    }

    /** 取 content: 访问器优先, 字段兜底。 */
    private static String readContent(Object msg) {
        // v3.0.580: 8.0.79 实证 F0() = getContent
        for (String mn : new String[]{"F0", "I0", "j", "N1"}) {
            String v = callStr(msg, mn);
            if (v != null) {
                return v;
            }
        }
        return readString(msg, "field_content");
    }

    private static String callStr(Object obj, String name) {
        try {
            Object v = XposedHelpers.callMethod(obj, name);
            return v == null ? null : v.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Integer callInt(Object obj, String name) {
        try {
            Object v = XposedHelpers.callMethod(obj, name);
            if (v instanceof Number) {
                return ((Number) v).intValue();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Long callLong(Object obj, String name) {
        try {
            Object v = XposedHelpers.callMethod(obj, name);
            if (v instanceof Number) {
                return ((Number) v).longValue();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 日志用截断。 */
    private static String trunc(String s) {
        if (s == null) return "";
        String one = s.replace('\n', ' ').trim();
        return one.length() > 60 ? one.substring(0, 60) + "..." : one;
    }

    // ---------- 字段读取（兼容 int/long 装箱差异） ----------

    private static String readString(Object obj, String field) {
        try {
            Object o = XposedHelpers.getObjectField(obj, field);
            return o instanceof String ? (String) o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Integer readInt(Object obj, String field) {
        try {
            Object o = XposedHelpers.getObjectField(obj, field);
            if (o instanceof Number) {
                return ((Number) o).intValue();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Long readLong(Object obj, String field) {
        try {
            Object o = XposedHelpers.getObjectField(obj, field);
            if (o instanceof Number) {
                return ((Number) o).longValue();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
