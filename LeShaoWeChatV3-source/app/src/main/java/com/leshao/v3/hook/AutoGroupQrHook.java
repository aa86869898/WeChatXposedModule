package com.leshao.v3.hook;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.MainHook;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 「自动扫码进群」—— 参照《微信群聊二维码_全自动检测加群_完整手册.md》及
 * 《自动进群失败_诊断报告.md》方案 A 实现。
 *
 * <p>链路（诊断报告方案 A，纯后台可靠）：后台监听群聊图片消息入库（f9.Bb）→
 * 提取本地图片路径（未落地则轮询等待微信自动下载，ex0.k0.k2 反查 + 缓存对象双通道）→
 * BitmapFactory 采样解码 → {@code com.tencent.mm.pluginsdk.ui.tools.v16.a.a(Bitmap,int[],boolean)}
 * 微信原生同步解码（不依赖 wk5.g0 实例、不依赖 RecogQBarOfImageFileEvent 事件订阅者）→
 * 结果元素 t16.i0.e 拿码值 → {@link #isGroupQr} 判定 → 发 DealQBarStrEvent 让微信走
 * A8Key(Cgi 106) 入群流程 → 自动点「加入群聊」确认 → 进群回执。</p>
 *
 * <p>保留手册原事件链作为辅助通道：hookScanResult（wk5.g0.b）/ 事件层 IEvent.e /
 * 日志层 Mars.xlog.Log / 码值总闸 v74.v.g，当微信自己产生群码时也能捕获。</p>
 *
 * <p>类名兼容策略：消息存储类走 {@link VersionCompat#findMsgStorageClass}（8.0.78 已验证），
 * v16.a / ex0.k0 为 3180 版本速查类名（诊断报告 §五），QBarStringHandler / ImageScanCodeManager
 * 走 DexKit 字符串锚点动态定位。autogen 事件类名称稳定。</p>
 *
 * <p>已内置风控：每日入群上限、随机延迟、同一群码只处理一次、白名单群过滤、
 * 自动点确认带 60s 触发保护窗（仅最近发过入群请求时才点，避免误点其它弹窗）。</p>
 */
public final class AutoGroupQrHook {

    public static final String TAG = "AutoGroupQr";

    private static final String K_TODAY = "gqr_today";
    private static final String K_TODAY_COUNT = "gqr_today_count";

    // 群二维码前缀（手册 §4.1，来自 com.tencent.mm.plugin.scanner.z0 b[]/e[]/f[]，硬编码）
    private static final String[] GROUP_PREFIX = {
            "https://weixin.qq.com/g/",      // 普通群（最常见）
            "http://weixin.qq.com/g/",
            "https://c.weixin.com/g/",       // 短链
            "http://c.weixin.com/g/",
            "https://work.weixin.qq.com/gm/",// 企业微信群
            "https://work.weixin.qq.com/m/",
    };

    // 自动点确认文案白名单 / 保护词（手册 §6.4(3)）
    private static final String[] JOIN_WORDS = {"加入群聊", "进入群聊", "加入该群", "确认加入", "加入群"};
    private static final String[] DENY_WORDS = {"支付", "转账", "收款", "输入密码", "好友验证"};
    // 需验证群申请页自动提交文案
    private static final String[] APPLY_WORDS = {"提交申请", "申请加入", "发送申请"};

    private static final Pattern P_PATH = Pattern.compile("path=\"([^\"]+)\"");
    /** 微信缩略图虚拟路径前缀：ImgInfo2.thumbImgPath 占位符（非真实路径）。 */
    private static final String THUMBNAIL_PREFIX = "THUMBNAIL_DIRPATH://";
    /** 微信 image2 根目录缓存（如 .../MicroMsg/<account>/image2/），来自 pe3.a.b()。 */
    private static volatile String sImage2Root = null;
    /** 微信 ScanImageUtil（v16.a 或混淆替代类）及其静态解码方法，DexKit 定位后缓存。 */
    private static volatile Class<?> sScanImgUtilClass = null;
    private static volatile Method sScanImgUtilMethod = null;

    // 去重：同一群码只处理一次 / 同一消息只识别一次 / 同一群只记一次回执
    private static final Set<String> DONE_CODE = ConcurrentHashMap.newKeySet();
    private static final Set<Long> DONE_MSG = ConcurrentHashMap.newKeySet();
    private static final Set<String> DONE_ROOM = ConcurrentHashMap.newKeySet();
    /** 已安排过延迟重试的消息（ScanImageUtil 类未就绪时只补试一次）。 */
    private static final Set<Long> RETRY_MSG = ConcurrentHashMap.newKeySet();

    private static volatile boolean sEnabled = false;
    private static volatile int sMaxPerDay = 5;
    private static volatile int sDelayMax = 15;
    private static volatile boolean sAutoApply = true;
    private static volatile String sWhitelist = "";
    private static volatile boolean sHooked = false;
    private static volatile ClassLoader sCl;
    /** 最近一次发 DealQBarStrEvent 的时间戳：自动点确认的保护窗。 */
    private static volatile long sLastDealQBarTime = 0L;

    private static final Random sRandom = new Random();
    private static final ExecutorService sBus = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "leshao-gqr-bus");
        t.setDaemon(true);
        return t;
    });
    /** msgId → e9 对象缓存（图片下载完成后，缓存对象可能被微信更新 content，作为反查兜底）。 */
    private static final Map<Long, Object> sMsgCache = new ConcurrentHashMap<>();

    private AutoGroupQrHook() {}

    // ==================== 配置 ====================

    /** 从 prefs 刷新运行时配置（开关/上限/延迟/自动申请/白名单）。 */
    public static void updateConfig() {
        try {
            sEnabled = AutoGroupQrConfig.isEnabled();
            sMaxPerDay = AutoGroupQrConfig.getMaxPerDay();
            sDelayMax = AutoGroupQrConfig.getDelayMax();
            sAutoApply = AutoGroupQrConfig.isAutoApply();
            sWhitelist = AutoGroupQrConfig.getWhitelist();
            LogWriter.log(TAG, "updateConfig enabled=" + sEnabled + " maxPerDay=" + sMaxPerDay
                    + " delayMax=" + sDelayMax + " autoApply=" + sAutoApply);
        } catch (Throwable t) {
            LogWriter.log(TAG, "updateConfig err: " + t.getMessage());
        }
    }

    // ==================== Hook 安装 ====================

    public static void hook(final ClassLoader cl) {
        sCl = cl;
        updateConfig();
        if (sHooked) return;
        // ① 消息监听（f9.Bb）不依赖 DexKit：立即安装，避免错过启动早期/扫描期间的群图片消息。
        //    （实机教训：DexKit 全量扫描约 26s，若等扫描完再装，期间群图片全部漏掉）
        Thread msgThread = new Thread(() -> {
            for (int attempt = 0; attempt < 10; attempt++) {
                try {
                    hookMsgInsert(cl);
                    return;
                } catch (Throwable e) {
                    LogWriter.log(TAG, "hookMsgInsert attempt " + attempt + " failed: " + e.getMessage());
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }
        }, "leshao-gqr-msg");
        msgThread.setDaemon(true);
        msgThread.start();
        // ② 其余依赖 DexKit 字符串锚点的 Hook（识别结果截胡/事件层/日志层/码值总闸/UI 回执）
        //    等扫描完成后再装。
        try {
            DexKitHelper.addPostScanCallback(() -> {
                Thread t = new Thread(() -> {
                    for (int attempt = 0; attempt < 10 && !sHooked; attempt++) {
                        try {
                            installScanHooks(cl);
                            sHooked = true;
                            LogWriter.log(TAG, "scan hooks installed attempt=" + attempt);
                            return;
                        } catch (Throwable e) {
                            LogWriter.log(TAG, "install attempt " + attempt + " failed: " + e.getMessage());
                            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                        }
                    }
                }, "leshao-gqr-hook");
                t.setDaemon(true);
                t.start();
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook schedule err: " + t.getMessage());
        }
    }

    private static void installScanHooks(ClassLoader cl) throws Throwable {
        // DexKit 扫描完成后 Tinker 真实 CL 已就绪，补装一次消息监听（双保险，
        // 避免首次安装只挂到 base.apk 平行副本导致零捕获）
        hookMsgInsert(cl);
        hookScanResult(cl);
        hookRecogResultEvent(cl);
        hookQrLog(cl);
        hookQBarStringHandler(cl);
        hookJoinDialog(cl);
        hookLauncherUI(cl);
        hookVerifyUI(cl);
        hookJoinApi(cl);
        hookA8KeyResult(cl);
        try { ensureScanImgUtil(cl); } catch (Throwable ignored) {}
        LogWriter.log(TAG, "installScanHooks done");
    }

    /** ① 后台消息监听：消息入库 f9.Bb(after)，只关心群图片。
     *  遍历所有候选 ClassLoader（Tinker 真实 CL / base CL / AppContext CL），
     *  避免只挂在一个平行副本类上导致零捕获（本次实机已踩坑）。 */
    private static void hookMsgInsert(ClassLoader cl) {
        int totalHooked = 0;
        for (ClassLoader loader : HookUtil.candidateLoaders(cl)) {
            try {
                Class<?> f9 = VersionCompat.findMsgStorageClass(loader);
                if (f9 == null) {
                    LogWriter.log(TAG, "消息存储类未找到 loader=" + HookUtil.loaderName(loader));
                    continue;
                }
                int hooked = 0;
                for (Method m : f9.getDeclaredMethods()) {
                    if (!"Bb".equals(m.getName())) continue;
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length < 1 || pts[0] == null) continue;
                    String p0 = pts[0].getName();
                    if (p0.endsWith(".e9")) { /* ok */ } else if (p0.contains("MsgInfo")) { /* ok */ } else {
                        continue;
                    }
                    final String fP0 = p0;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                if (p.args.length < 1 || p.args[0] == null) return;
                                onNewMsg(p.args[0]);
                            } catch (Throwable t) {
                                LogWriter.log(TAG, "onNewMsg cb err: " + t);
                            }
                        }
                    });
                    hooked++;
                    LogWriter.log(TAG, "已 hook f9.Bb(" + pts.length + " args) p0=" + fP0
                            + " loader=" + HookUtil.loaderName(loader));
                }
                totalHooked += hooked;
            } catch (Throwable t) {
                LogWriter.log(TAG, "hookMsgInsert loader err: " + t.getMessage());
            }
        }
        LogWriter.log(TAG, "消息监听安装合计: " + totalHooked + " 个 f9.Bb");
    }

    private static void onNewMsg(Object msg) {
        // 诊断报告要求：回调最前面打日志，确认 hook 真的进来
        LogWriter.log(TAG, "onNewMsg enter msg=" + (msg == null ? "null" : msg.getClass().getName()));
        if (!sEnabled) return;
        try {
            String talker = getTalker(msg);
            if (talker == null) { LogWriter.log(TAG, "onNewMsg talker=null, return"); return; }
            if (!(talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom"))) {
                LogWriter.log(TAG, "onNewMsg talker=" + talker + " 非群聊, return");
                return;
            }
            int type = ((Number) XposedHelpers.callMethod(msg, "getType")).intValue();
            if (type != 3) { LogWriter.log(TAG, "onNewMsg type=" + type + " 非图片, return"); return; }
            long msgId = getMsgId(msg);
            if (!inWhitelist(talker)) { LogWriter.log(TAG, "onNewMsg 白名单拦截 talker=" + talker); return; }
            if (!DONE_MSG.add(msgId)) return;                       // 去重
            // 缓存消息对象（≤200 条，图片落地轮询兜底用）
            if (sMsgCache.size() > 200) sMsgCache.clear();
            sMsgCache.put(msgId, msg);

            String path = getImgPath(msg);
            String realPath = resolveImgPath(sCl, path);
            LogWriter.log(TAG, "★ 群图片消息 " + talker + " id=" + msgId + " path=" + path
                    + " real=" + realPath);
            if (realPath == null || !new File(realPath).exists()) {
                // 图片未落地（或虚拟路径尚未解析到真实缩略图）：后台轮询等待
                LogWriter.log(TAG, "图片未落地，启动轮询等待(最多60s): msgId=" + msgId);
                final long fMsgId = msgId;
                final String fTalker = talker;
                final String fUri = path;
                sBus.execute(() -> waitAndScan(sCl, fTalker, fMsgId, fUri));
                return;
            }
            final String fPath = realPath;
            final long fMsgId2 = msgId;
            final String fTalker2 = talker;
            sBus.execute(() -> scanImage(sCl, fTalker2, fMsgId2, fPath));
        } catch (Throwable t) {
            LogWriter.log(TAG, "onNewMsg err: " + t);
        }
    }

    /** 8.0.78 已验证：N0() 返回 talker（MessageHook/WanQunGroupHook 同款）；p0() 为手册样本版本兜底。 */
    private static String getTalker(Object msg) {
        try { return (String) XposedHelpers.callMethod(msg, "N0"); } catch (Throwable ignored) {}
        try { return (String) XposedHelpers.callMethod(msg, "p0"); } catch (Throwable ignored) {}
        return null;
    }

    /** 8.0.78 已验证：getMsgId() 返回 msgId（MessageHook/WanQunGroupHook 同款）；l0() 为手册样本版本兜底。 */
    private static long getMsgId(Object msg) {
        try { return ((Number) XposedHelpers.callMethod(msg, "getMsgId")).longValue(); } catch (Throwable ignored) {}
        try { return ((Number) XposedHelpers.callMethod(msg, "l0")).longValue(); } catch (Throwable ignored) {}
        return 0L;
    }

    /** 读取消息内容 XML（getContent → j() → 字段兜底）。 */
    private static String readContent(Object msg) {
        try { return (String) XposedHelpers.callMethod(msg, "getContent"); } catch (Throwable ignored) {}
        try { return (String) XposedHelpers.callMethod(msg, "j"); } catch (Throwable ignored) {}
        try {
            for (Class<?> c = msg.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != String.class) continue;
                    f.setAccessible(true);
                    Object v = f.get(msg);
                    if (v != null && String.valueOf(v).startsWith("<")) return (String) v;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 从内容 XML 提取本地图片路径；只有 cdn 未落地时返回 null。 */
    static String extractPath(String xml) {
        if (xml == null) return null;
        Matcher m = P_PATH.matcher(xml);
        if (m.find()) {
            String p = m.group(1);
            if (p != null && !p.isEmpty()) return p;
        }
        return null;
    }

    /** ★ 3180 文档：x0() = field_imgPath + 内部兜底解析（最可靠，比正则解析 XML 更稳）。
     *  注意 x0() 第④兜底会拼一个路径返回（即使文件不存在），调用方必须 new File(path).exists() 校验。 */
    static String getImgPath(Object msg) {
        if (msg == null) return null;
        try {
            String p = (String) XposedHelpers.callMethod(msg, "x0");
            if (p != null && !p.isEmpty()) return p;
        } catch (Throwable ignored) {}
        // 兜底：解析 XML
        String xml = readContent(msg);
        return extractPath(xml);
    }

    /** 把微信虚拟路径（THUMBNAIL_DIRPATH://th_<md5>）解析为真实文件路径；普通路径原样返回。
     *  逆向结论（WeChatThumbnailPathReport）：THUMBNAIL_DIRPATH 不是常量而是 DB 占位符，
     *  真实路径 = <image2根>/<md5[0:2]>/<md5[2:4]>/th_<md5>。
     *  ① 反射微信 ImgInfoService（wb0.b.tj）最权威；② 本地计算（pe3.a.b() 根）兜底。 */
    static String resolveImgPath(ClassLoader cl, String path) {
        if (path == null) return null;
        if (!path.startsWith(THUMBNAIL_PREFIX)) return path;
        String real = resolveByWeChatSvc(cl, path);
        if (real != null && !real.isEmpty()) return real;
        return resolveByLocal(cl, path);
    }

    /** 反射微信 ImgInfoService（rn3.u0 → wb0.b 实现）的 tj(uri,false) 拿真实路径。 */
    private static String resolveByWeChatSvc(ClassLoader cl, String uri) {
        try {
            Object svc = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("ph5.n0", cl), "c",
                    XposedHelpers.findClass("rn3.u0", cl));
            if (svc != null) {
                Object real = XposedHelpers.callMethod(svc, "tj", uri, false);
                if (real instanceof String && !((String) real).isEmpty()) {
                    LogWriter.log(TAG, "wb0.b.tj → " + real);
                    return (String) real;
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "resolveByWeChatSvc err: " + t);
        }
        return null;
    }

    /** 本地计算：pe3.a.b() = <data>/MicroMsg/<account>/image2/，再拼 md5 两级散列目录。 */
    private static String resolveByLocal(ClassLoader cl, String uri) {
        try {
            int idx = uri.indexOf("th_");
            if (idx < 0) return null;
            String hash = uri.substring(idx + 3);
            if (hash == null || hash.length() != 32) return null;
            String root = sImage2Root;
            if (root == null) {
                Object r = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("pe3.a", cl), "b");
                if (r instanceof String && !((String) r).isEmpty()) {
                    root = (String) r;
                    sImage2Root = root.endsWith("/") ? root : root + "/";
                    LogWriter.log(TAG, "image2根(pe3.a.b)=" + sImage2Root);
                }
            }
            if (root == null) return null;
            if (!root.endsWith("/")) root = root + "/";
            return root + hash.substring(0, 2) + "/" + hash.substring(2, 4) + "/th_" + hash;
        } catch (Throwable t) {
            LogWriter.log(TAG, "resolveByLocal err: " + t);
        }
        return null;
    }

    /** 轮询等待图片下载落地。真实路径 = image2/<md5[0:2]>/<md5[2:4]>/th_<md5>，
     *  微信收消息后会自动下载缩略图（l51.m0.k 扩展），最多等 60s。
     *  消息反查 ex0.k0.k2 + 缓存对象双通道。 */
    private static void waitAndScan(ClassLoader cl, String talker, long msgId, String initialUri) {
        String realPath = null;
        for (int i = 0; i < 60; i++) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            String p = queryImgPath(cl, talker, msgId);
            if (p == null || p.isEmpty()) p = initialUri;
            String real = resolveImgPath(cl, p);
            if (real != null && new File(real).exists()) {
                realPath = real;
                break;
            }
            if (i == 10 || i == 30 || i == 50) {
                LogWriter.log(TAG, "图片仍未落地 msgId=" + msgId + " 已等 " + (i + 1) + "s uri=" + p);
            }
        }
        if (realPath == null) {
            LogWriter.log(TAG, "图片 60 秒内未落地，放弃 msgId=" + msgId);
            return;
        }
        LogWriter.log(TAG, "图片已落地 msgId=" + msgId + " path=" + realPath);
        scanImage(cl, talker, msgId, realPath);
    }

    /** 反查最新消息 XML 中的图片路径：优先 ex0.k0.k2（重新查 DB），失败用缓存对象。 */
    private static String queryImgPath(ClassLoader cl, String talker, long msgId) {
        try {
            Object msg = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("ex0.k0", cl), "k2", talker, msgId);
            if (msg != null) {
                String p = getImgPath(msg);
                if (p != null) return p;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "ex0.k0.k2 反查失败: " + t);
        }
        Object cached = sMsgCache.get(msgId);
        if (cached != null) {
            return getImgPath(cached);
        }
        return null;
    }

    /** ② 同步解码：BitmapFactory 读本地图 → v16.a.a(Bitmap,int[],boolean) 直接解（诊断报告方案 A）。
     *  不依赖 wk5.g0 实例、不依赖 RecogQBarOfImageFileEvent 事件订阅者，纯后台可用。 */
    private static void scanImage(ClassLoader cl, String talker, long msgId, String path) {
        if (cl == null || path == null) return;
        String imgPath = resolveImgPath(cl, path);
        if (imgPath == null || !new File(imgPath).exists()) {
            LogWriter.log(TAG, "scanImage 文件不存在: " + imgPath);
            return;
        }
        try {
            Bitmap bmp = decodeSampledBitmap(imgPath, 2048);
            if (bmp == null) {
                LogWriter.log(TAG, "Bitmap 解码失败: " + path);
                return;
            }
            List<String> codes = decodeBitmap(cl, bmp);
            try { bmp.recycle(); } catch (Throwable ignored) {}
            if (codes.isEmpty() && !ensureScanImgUtil(cl) && RETRY_MSG.add(msgId)) {
                LogWriter.log(TAG, "ScanImageUtil 未就绪，5s 后重试 msgId=" + msgId);
                sBus.execute(() -> {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                    scanImage(cl, talker, msgId, imgPath);
                });
                return;
            }
            for (String code : codes) {
                if (isGroupQr(code)) {
                    onGroupQr(code, talker, msgId, imgPath);
                    return;
                }
            }
            LogWriter.log(TAG, "未识别到群二维码 msgId=" + msgId + " codes=" + codes);
        } catch (Throwable t) {
            LogWriter.log(TAG, "scanImage err: " + t);
        }
    }

    /** 定位微信同步解码工具类并缓存。
     *  ① 硬编码 v16.a（主 dex 直接命中）；② DexKit 锚点 MicroMsg.ScanImageUtil（Tinker/补丁场景兜底）。
     *  返回 true 表示解码方法已就绪。 */
    private static boolean ensureScanImgUtil(ClassLoader cl) {
        if (sScanImgUtilMethod != null) return true;
        if (cl == null) return false;
        if (sScanImgUtilClass == null) {
            try {
                sScanImgUtilClass = XposedHelpers.findClass("com.tencent.mm.pluginsdk.ui.tools.v16.a", cl);
                LogWriter.log(TAG, "ScanImageUtil 硬编码命中: v16.a");
            } catch (Throwable ignored) {}
        }
        if (sScanImgUtilClass == null) {
            try {
                List<String> classes = DexKitHelper.findClassesByString(cl, "MicroMsg.ScanImageUtil");
                if (classes != null) {
                    for (String cn : classes) {
                        for (Class<?> c : HookUtil.loadClasses(cl, cn)) {
                            try {
                                Method m = c.getMethod("a", Bitmap.class, int[].class, boolean.class);
                                if (Modifier.isStatic(m.getModifiers())) {
                                    sScanImgUtilClass = c;
                                    sScanImgUtilMethod = m;
                                    LogWriter.log(TAG, "ScanImageUtil 锚点命中: " + cn);
                                    return true;
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "ensureScanImgUtil(anchor) err: " + t);
            }
        }
        if (sScanImgUtilClass != null && sScanImgUtilMethod == null) {
            try {
                sScanImgUtilMethod = sScanImgUtilClass.getMethod("a", Bitmap.class, int[].class, boolean.class);
            } catch (Throwable ignored) {}
        }
        return sScanImgUtilMethod != null;
    }

    /** 同步解码 Bitmap，返回码值列表（ScanImageUtil.a 静态方法）。
     *  实测 3180：结果对象（t16.i0）字段映射已变化，e 字段不再是码值而是码类型名（如 QR_CODE），
     *  因此遍历对象所有 String 字段收集码值，兼容新旧版本。 */
    private static List<String> decodeBitmap(ClassLoader cl, Bitmap bmp) {
        List<String> out = new ArrayList<>();
        if (bmp == null) return out;
        try {
            if (!ensureScanImgUtil(cl)) return out;
            Method m = sScanImgUtilMethod;
            Object ret = m.invoke(null, bmp, new int[]{2}, false);
            if (ret instanceof List) {
                for (Object item : (List<?>) ret) {
                    if (item == null) continue;
                    out.addAll(extractCodes(item));
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "decodeBitmap err: " + t);
        }
        return out;
    }

    /** 从单个解码结果对象提取码值字符串。
     *  3180 实测（WxQBarResult extends t16.i0）：f=二维码内容(URL/文本)、e=码类型名、
     *  g=原始字节、h=charset。优先取 f；f 为空时用 g+h 自行解码；最后遍历所有 String 字段兜底。 */
    private static List<String> extractCodes(Object item) {
        List<String> res = new ArrayList<>();
        try {
            Object v = XposedHelpers.getObjectField(item, "f");
            if (v instanceof String) addCode(res, (String) v);
        } catch (Throwable ignored) {}
        if (res.isEmpty()) {
            try {
                byte[] raw = (byte[]) XposedHelpers.getObjectField(item, "g");
                String charset = (String) XposedHelpers.getObjectField(item, "h");
                if (raw != null && raw.length > 0) {
                    String dec;
                    if (charset == null || charset.isEmpty() || "ANY".equals(charset)) {
                        dec = new String(raw, "UTF-8");
                    } else {
                        try {
                            dec = new String(raw, java.nio.charset.Charset.forName(charset));
                        } catch (Throwable t) {
                            dec = new String(raw, "UTF-8");
                        }
                    }
                    addCode(res, dec);
                }
            } catch (Throwable ignored) {}
        }
        if (res.isEmpty()) {
            for (Class<?> c = item.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != String.class) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(item);
                        if (v instanceof String) addCode(res, (String) v);
                    } catch (Throwable ignored) {}
                }
            }
        }
        return res;
    }

    /** 收集疑似码值的字符串：排除空串、码类型名、纯单字符。 */
    private static void addCode(List<String> res, String s) {
        if (s == null || s.isEmpty()) return;
        if (s.equals("QR_CODE") || s.equals("WX_CODE")) return;
        if (s.length() < 4) return;
        if (!res.contains(s)) res.add(s);
    }

    /** 采样解码，避免大图 OOM（>2048px 缩放）。 */
    private static Bitmap decodeSampledBitmap(String path, int maxSize) {
        try {
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opt);
            int sample = 1;
            while (opt.outWidth / sample > maxSize || opt.outHeight / sample > maxSize) {
                sample *= 2;
            }
            opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, opt);
        } catch (Throwable t) {
            LogWriter.log(TAG, "decodeSampledBitmap err: " + t);
            return null;
        }
    }

    /** ③ 识别结果截胡（手册 §1.1 / §7 hookScanResult）：
     *  hook ImageScanCodeManager.b(...)（= 手册 wk5.g0.b），
     *  在 before 阶段读取 ImageQBarDataBean.d（码值）—— 这是「模块发 RecogQBarOfImageFileEvent
     *  后，微信原生解码结果回传」的必经出口。漏掉这一环，码值永远到不了模块。
     *  定位：DexKit 字符串锚点 MicroMsg.ImageScanCodeManager（+ doScanCode from decoder msgId 兜底）。 */
    private static void hookScanResult(ClassLoader cl) {
        try {
            List<String> classes = DexKitHelper.findClassesByString(cl, "MicroMsg.ImageScanCodeManager");
            if (classes == null || classes.isEmpty()) {
                classes = DexKitHelper.findClassesByString(cl, "doScanCode from decoder msgId");
            }
            if (classes == null || classes.isEmpty()) {
                LogWriter.log(TAG, "ImageScanCodeManager 锚点未命中");
                return;
            }
            boolean hooked = false;
            for (String cn : classes) {
                for (Class<?> c : HookUtil.loadClasses(cl, cn)) {
                    Class<?> beanCls = null;
                    try {
                        beanCls = XposedHelpers.findClass("com.tencent.mm.plugin.scanner.ImageQBarDataBean", cl);
                    } catch (Throwable ignored) {}
                    for (Method m : c.getDeclaredMethods()) {
                        if (!"b".equals(m.getName())) continue;
                        Class<?>[] pts = m.getParameterTypes();
                        if (pts.length < 2) continue;
                        // 参数中包含 ImageQBarDataBean（完整类名或含关键字）
                        boolean hasBean = false;
                        for (Class<?> pt : pts) {
                            if (pt == beanCls) { hasBean = true; break; }
                            if (pt.getName().contains("ImageQBarDataBean")) { hasBean = true; break; }
                        }
                        if (!hasBean) continue;
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) {
                                if (!sEnabled) return;
                                try {
                                    // 遍历参数找 ImageQBarDataBean 实例和 wk5.l0
                                    for (Object a : p.args) {
                                        if (a == null) continue;
                                        String an = a.getClass().getName();
                                        if (an.contains("ImageQBarDataBean")) {
                                            String code = (String) XposedHelpers.getObjectField(a, "d");
                                            int e = XposedHelpers.getIntField(a, "e");
                                            int f = XposedHelpers.getIntField(a, "f");
                                            LogWriter.log(TAG, "hookScanResult bean d=" + code
                                                    + " e=" + e + " f=" + f);
                                            if (isGroupQr(code)) onGroupQr(code);
                                        } else if (an.contains("wk5.l0")) {
                                            try {
                                                LogWriter.log(TAG, "hookScanResult l0 f=" + XposedHelpers.getIntField(a, "f")
                                                        + " g=" + XposedHelpers.getIntField(a, "g")
                                                        + " i=" + XposedHelpers.getIntField(a, "i")
                                                        + " n=" + XposedHelpers.getObjectField(a, "n"));
                                            } catch (Throwable ignored) {}
                                        }
                                    }
                                } catch (Throwable t) {
                                    LogWriter.log(TAG, "scanResult err: " + t);
                                }
                            }
                        });
                        hooked = true;
                        LogWriter.log(TAG, "已 hook 识别结果 " + c.getName() + "." + m.getName()
                                + " args=" + pts.length);
                    }
                }
            }
            if (!hooked) LogWriter.log(TAG, "ImageScanCodeManager.b 未命中");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookScanResult err: " + t.getMessage());
        }
    }

    /** ④ 事件层兜底（手册 §0.1 铁律「日志层+事件层双通道」）：
     *  hook IEvent.e()（同步 publish）before，当发布的是 RecogQBarOfImageFileResultEvent
     *  时直接读 g.b（ArrayList<String> 码值）。即使 wk5.g0.b 类名/锚点失效也能捕获识别结果。 */
    private static void hookRecogResultEvent(ClassLoader cl) {
        try {
            Class<?> iEvent = XposedHelpers.findClass("com.tencent.mm.sdk.event.IEvent", cl);
            Method e = iEvent.getDeclaredMethod("e");
            XposedBridge.hookMethod(e, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!sEnabled) return;
                    try {
                        Object ev = p.thisObject;
                        if (ev == null) return;
                        if (!ev.getClass().getName().contains("RecogQBarOfImageFileResultEvent")) return;
                        Object g = XposedHelpers.getObjectField(ev, "g");
                        if (g == null) return;
                        Object codes = XposedHelpers.getObjectField(g, "b");
                        if (!(codes instanceof java.util.List)) return;
                        for (Object o : (java.util.List<?>) codes) {
                            if (o instanceof String && isGroupQr((String) o)) {
                                onGroupQr((String) o);
                                break;
                            }
                        }
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "recogResultEvent err: " + t);
                    }
                }
            });
            LogWriter.log(TAG, "已 hook 识别结果事件 IEvent.e");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookRecogResultEvent err: " + t.getMessage());
        }
    }

    /** ⑤ 日志层兜底（手册 §7 hookQrLog）：零类名依赖，TAG=MicroMsg.QBarStringHandler
     *  且 fmt 含 dealQBarString 时，从格式化参数拿码值。 */
    private static void hookQrLog(ClassLoader cl) {
        try {
            Method logI = XposedHelpers.findClass("com.tencent.mars.xlog.Log", cl)
                    .getDeclaredMethod("i", String.class, String.class, Object[].class);
            XposedBridge.hookMethod(logI, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!sEnabled) return;
                    try {
                        if (!"MicroMsg.QBarStringHandler".equals(p.args[0])) return;
                        String fmt = (String) p.args[1];
                        if (fmt == null || !fmt.contains("dealQBarString")) return;
                        Object[] a = (Object[]) p.args[2];
                        if (a == null || a.length == 0) return;
                        String code = String.valueOf(a[0]);
                        if (isGroupQr(code)) onGroupQr(code);
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "qrLog err: " + t);
                    }
                }
            });
            LogWriter.log(TAG, "已 hook 日志层兜底");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookQrLog err: " + t.getMessage());
        }
    }
    /** ⑥ 码值总闸（手册 §2.1 / §3.1）：QBarStringHandler.g(...) after 拿二维码原文。
     *  这是微信内部 DealQBarStrEvent 分发的落点，作为第二通道：
     *  当微信自己（扫码/长按/相册）产生群码时也能捕获并自动完成入群。 */
    private static void hookQBarStringHandler(ClassLoader cl) {
        try {
            List<String> classes = DexKitHelper.findClassesByString(cl, "MicroMsg.QBarStringHandler");
            if (classes == null || classes.isEmpty()) {
                classes = DexKitHelper.findClassesByString(cl, "[handleCode-dealQBarString]");
            }
            if (classes == null || classes.isEmpty()) {
                LogWriter.log(TAG, "QBarStringHandler 锚点未命中");
                return;
            }
            boolean hooked = false;
            for (String cn : classes) {
                for (Class<?> c : HookUtil.loadClasses(cl, cn)) {
                    for (Method m : c.getDeclaredMethods()) {
                        if (!"g".equals(m.getName())) continue;
                        Class<?>[] pts = m.getParameterTypes();
                        if (pts.length < 5) continue;
                        if (pts[0] != Activity.class || pts[1] != String.class
                                || pts[2] != int.class || pts[3] != int.class || pts[4] != int.class) {
                            continue;
                        }
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) {
                                if (!sEnabled) return;
                                try {
                                    String code = (String) p.args[1];
                                    if (isGroupQr(code)) onGroupQr(code);
                                } catch (Throwable t) {
                                    LogWriter.log(TAG, "qbar g after err: " + t);
                                }
                            }
                        });
                        hooked = true;
                        LogWriter.log(TAG, "已 hook 码值总闸 " + c.getName() + "." + m.getName());
                    }
                }
            }
            if (!hooked) LogWriter.log(TAG, "QBarStringHandler.g 未命中");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookQBarStringHandler err: " + t.getMessage());
        }
    }

    /** 微信自身识别链路命中群码（投喂后微信自己识别产生的回调，无需再投喂，
     *  只需刷新保护窗，供 hookJoinDialog 自动点「加入群聊」）。 */
    private static void onGroupQr(String code) {
        if (!isGroupQr(code)) return;
        if (!DONE_CODE.add(code)) return;
        sLastDealQBarTime = System.currentTimeMillis();
        LogWriter.log(TAG, "微信链路命中群码: " + code);
    }

    /** 模块自行解码命中群码：投喂图片给微信原生识别引擎（复刻长按识别链路，
     *  wk5.g0.h → doScanCode → RecogQBarOfImageFileEvent → 微信解码 → DealQBarStrEvent → 加群）。 */
    private static void onGroupQr(String code, String talker, long msgId, String imgPath) {
        if (!isGroupQr(code)) return;
        if (!DONE_CODE.add(code)) return;
        LogWriter.log(TAG, "★★★ 群二维码命中: " + code + " msgId=" + msgId);
        final String fTalker = talker;
        final long fMsgId = msgId;
        final String fImgPath = imgPath;
        sBus.execute(() -> triggerWxScan(sCl, fTalker, fMsgId, fImgPath));
    }

    /** ④ 发 DealQBarStrEvent 让微信走 A8Key 入群（手册 §5 路径 A）。 */
    private static void dealQBarStr(String code) {
        if (!sEnabled) return;
        if (!allowToday()) {
            LogWriter.log(TAG, "已达每日入群上限(" + sMaxPerDay + ")，跳过 " + code);
            return;
        }
        ClassLoader cl = sCl;
        if (cl == null) return;
        try {
            Object ev = XposedHelpers.newInstance(XposedHelpers.findClass(
                    "com.tencent.mm.autogen.events.DealQBarStrEvent", cl));
            Object g = XposedHelpers.getObjectField(ev, "g");
            Activity act = MainHook.currentActivity();
            if (act == null) act = anyActivity();
            if (act != null) XposedHelpers.setObjectField(g, "b", act);
            else LogWriter.log(TAG, "dealQBarStr: 无可用 Activity，b 保持 null（可能导致微信 NPE）");
            XposedHelpers.setObjectField(g, "a", code);     // 码值
            XposedHelpers.setObjectField(g, "i", 37);       // source
            XposedHelpers.setObjectField(g, "g", 4);        // scene
            XposedHelpers.callMethod(ev, "e");              // publish
            sLastDealQBarTime = System.currentTimeMillis();
            LogWriter.log(TAG, "已触发入群流程: " + code);
        } catch (Throwable t) {
            LogWriter.log(TAG, "dealQBarStr err: " + t);
        }
    }

    /** 获取任意存活 Activity：先取前台，失败则反射 ActivityThread.mActivities 兜底。
     *  后台收到群图片消息时前台可能无 Activity，但微信主界面实例通常仍在任务栈存活。 */
    private static Activity anyActivity() {
        Activity a = MainHook.currentActivity();
        if (a != null) return a;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            Object activities = XposedHelpers.getObjectField(thread, "mActivities");
            if (activities instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) activities;
                for (Object value : map.values()) {
                    try {
                        Object act = XposedHelpers.getObjectField(value, "activity");
                        if (act instanceof Activity) return (Activity) act;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "anyActivity err: " + t);
        }
        return null;
    }

    /** ⑦ 自动点「加入群聊」：仅最近 60s 内发起过入群请求时才介入，避免误点。 */
    private static void hookJoinDialog(ClassLoader cl) {
        try {
            Method show = Dialog.class.getDeclaredMethod("show");
            XposedBridge.hookMethod(show, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!sEnabled) return;
                    if (System.currentTimeMillis() - sLastDealQBarTime > 60000L) return;
                    try {
                        Dialog d = (Dialog) p.thisObject;
                        if (d == null || d.getWindow() == null) return;
                        View decor = d.getWindow().getDecorView();
                        if (!matchDialog(decor, JOIN_WORDS)) return;
                        decor.postDelayed(() -> clickJoin(d), 180);
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "dialog show err: " + t);
                    }
                }
            });
            LogWriter.log(TAG, "已 hook Dialog.show 自动点确认");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookJoinDialog err: " + t.getMessage());
        }
    }

    private static boolean matchDialog(View v, String[] words) {
        List<TextView> tvs = new ArrayList<>();
        collectText(v, tvs);
        for (TextView t : tvs) {
            CharSequence c = t.getText();
            if (c == null) continue;
            String s = c.toString();
            for (String bad : DENY_WORDS) {
                if (s.contains(bad)) return false;   // 安全阀：见到支付/转账等直接放弃
            }
        }
        for (TextView t : tvs) {
            CharSequence c = t.getText();
            if (c == null) continue;
            for (String w : words) {
                if (c.toString().contains(w)) return true;
            }
        }
        return false;
    }

    private static void clickJoin(Dialog d) {
        if (d == null || !d.isShowing()) return;
        try {
            View decor = d.getWindow().getDecorView();
            if (clickText(decor, JOIN_WORDS)) return;
            View btn = d.findViewById(android.R.id.button1);
            if (btn != null && btn.isClickable()) {
                btn.performClick();
                LogWriter.log(TAG, "自动点击 button1");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "clickJoin err: " + t);
        }
    }

    /** 遍历视图树找文案命中且可点击的 TextView（或向上找可点击父级）执行点击。 */
    private static boolean clickText(View root, String[] words) {
        if (root == null) return false;
        List<TextView> tvs = new ArrayList<>();
        collectText(root, tvs);
        for (TextView t : tvs) {
            CharSequence c = t.getText();
            if (c == null) continue;
            String s = c.toString();
            for (String w : words) {
                if (!s.contains(w)) continue;
                View target = t;
                if (!target.isClickable()) {
                    View cur = t;
                    while (cur != null) {
                        if (cur.isClickable()) {
                            target = cur;
                            break;
                        }
                        cur = (View) cur.getParent();
                    }
                }
                if (target.isClickable() || target.hasOnClickListeners()) {
                    target.performClick();
                    LogWriter.log(TAG, "自动点击: " + s);
                    return true;
                }
            }
        }
        return false;
    }

    private static void collectText(View v, List<TextView> out) {
        if (v instanceof TextView) out.add((TextView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectText(g.getChildAt(i), out);
            }
        }
    }

    /** ⑧ 进群回执：LauncherUI handleJump(Intent) after。 */
    private static void hookLauncherUI(ClassLoader cl) {
        try {
            Class<?> launcher = XposedHelpers.findClass("com.tencent.mm.ui.LauncherUI", cl);
            boolean hooked = false;
            for (Method m : launcher.getDeclaredMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length != 1 || pts[0] != Intent.class) continue;
                if (m.getReturnType() != void.class) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Intent i = (Intent) p.args[0];
                            if (i == null) return;
                            String room = i.getStringExtra("enter_conversation_from_liteapp");
                            if (room == null) room = i.getStringExtra("Chat_User");
                            if (room != null && room.endsWith("@chatroom")) {
                                if (DONE_ROOM.add(room)) {
                                    LogWriter.log(TAG, "★★★ 已进群: " + room);
                                }
                            }
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "launcher jump err: " + t);
                        }
                    }
                });
                hooked = true;
                LogWriter.log(TAG, "已 hook LauncherUI.handleJump " + m.getName());
                break;
            }
            if (!hooked) LogWriter.log(TAG, "LauncherUI handleJump 未命中");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookLauncherUI err: " + t.getMessage());
        }
    }

    /** ⑨ 需验证群申请页：出现即记录群 ID，并自动点「提交申请」。 */
    private static void hookVerifyUI(ClassLoader cl) {
        try {
            Method onCreate = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
            XposedBridge.hookMethod(onCreate, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!sEnabled || !sAutoApply) return;
                    try {
                        Activity a = (Activity) p.thisObject;
                        if (a == null) return;
                        String cn = a.getClass().getName();
                        if (cn == null || !cn.contains("RoomAccessVerifyApplicationByQrOrInvitationUI")) {
                            return;
                        }
                        String room = a.getIntent() != null
                                ? a.getIntent().getStringExtra("intent_chatroom_username") : null;
                        LogWriter.log(TAG, "⚠ 需验证群申请页 room=" + room);
                        final View decor = a.getWindow() != null ? a.getWindow().getDecorView() : null;
                        if (decor != null) {
                            decor.postDelayed(() -> {
                                if (clickText(decor, APPLY_WORDS)) {
                                    LogWriter.log(TAG, "已自动提交加群申请 room=" + room);
                                }
                            }, 500);
                        }
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "verify ui err: " + t);
                    }
                }
            });
            LogWriter.log(TAG, "已 hook 需验证群申请页");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookVerifyUI err: " + t.getMessage());
        }
    }

    /** 诊断+自动点击：hook pe5.f.j 接口（微信加群 API 真实调用点：
     *  vf0.e.bj().j(room, members, ticket, localHistoryInfo)），
     *  捕获真实参数；最近 60s 发起过入群请求时直接调 task.a() 发送（等效点击「进入群聊」）。
     *  同时保留 ln.a.j / qn.m.<init> / vn.m.b / factory.c.a() 兜底诊断。 */
    private static void hookJoinApi(ClassLoader cl) {
        try {
            Class<?> pe5f = XposedHelpers.findClass("pe5.f", cl);
            for (Method m : pe5f.getDeclaredMethods()) {
                if (!"j".equals(m.getName())) continue;
                if (m.getParameterTypes().length < 3) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "[JOIN-API] room=" + p.args[0]
                                    + " members=" + p.args[1]
                                    + " ticket=" + p.args[2]);
                            // ★ 最近 60s 发起过入群请求 → 自动点「进入群聊」：直接发送
                            Object task = p.getResult();
                            if (task != null
                                    && System.currentTimeMillis() - sLastDealQBarTime <= 60000L) {
                                try {
                                    XposedHelpers.callMethod(task, "a");
                                    LogWriter.log(TAG, "已自动发送 addchatroommember（模拟点击进入群聊）");
                                } catch (Throwable t) {
                                    LogWriter.log(TAG, "自动发送 err: " + t);
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                LogWriter.log(TAG, "已 hook pe5.f.j args=" + m.getParameterTypes().length);
                break;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookJoinApi pe5.f err: " + t);
        }
        try {
            Class<?> cls = XposedHelpers.findClass("ln.a", cl);
            boolean hooked = false;
            for (Method m : cls.getDeclaredMethods()) {
                if (!"j".equals(m.getName())) continue;
                if (m.getParameterTypes().length < 3) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "[JOIN] room=" + p.args[0]
                                    + " members=" + p.args[1]
                                    + " ticket=" + p.args[2]);
                        } catch (Throwable ignored) {}
                    }
                });
                hooked = true;
                LogWriter.log(TAG, "已 hook ln.a.j args=" + m.getParameterTypes().length);
                break;
            }
            if (!hooked) LogWriter.log(TAG, "ln.a.j 未命中");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookJoinApi err: " + t);
        }
        // 更精确：直接 hook NetSceneAddChatRoomMember <init>(room, members, ticket, extra)
        try {
            Class<?> qnm = XposedHelpers.findClass("qn.m", cl);
            for (java.lang.reflect.Constructor<?> m : qnm.getDeclaredConstructors()) {
                if (m.getParameterTypes().length < 3) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "[★REAL-JOIN] room=" + p.args[0]
                                    + " members=" + p.args[1]
                                    + " ticket=" + p.args[2]);
                        } catch (Throwable ignored) {}
                    }
                });
                LogWriter.log(TAG, "已 hook qn.m.<init> args=" + m.getParameterTypes().length);
                break;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookJoinApi qn.m err: " + t);
        }
        // 更可靠：hook vn.m.b（ChatRoomAddContactProcess，加群处理入口，签名 b(String,int)）
        try {
            Class<?> vnm = XposedHelpers.findClass("vn.m", cl);
            for (Method m : vnm.getDeclaredMethods()) {
                if (!"b".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length < 1) continue;
                if (pts[0] != String.class) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "[★JOIN-PROC] ticket=" + p.args[0]);
                        } catch (Throwable ignored) {}
                    }
                });
                LogWriter.log(TAG, "已 hook vn.m.b");
                break;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookJoinApi vn.m err: " + t);
        }
        // 最兜底：hook roomsdk 任务执行器 factory.c.a()（发送方法，任意线程可调）
        try {
            Class<?> facC = XposedHelpers.findClass("com.tencent.mm.roomsdk.model.factory.c", cl);
            for (Method m : facC.getDeclaredMethods()) {
                if (!"a".equals(m.getName())) continue;
                if (m.getParameterTypes().length != 0) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "[SEND] factory.c.a() 发送 addchatroommember");
                        } catch (Throwable ignored) {}
                    }
                });
                LogWriter.log(TAG, "已 hook factory.c.a()");
                break;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookJoinApi factory.c err: " + t);
        }
    }

    /** 诊断：hook NetSceneGetA8Key 的 N()（返回二维码 URL 解析出的群 username）。 */
    private static void hookA8KeyResult(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass("com.tencent.mm.modelsimple.k0", cl);
            boolean hooked = false;
            for (Method m : cls.getDeclaredMethods()) {
                if (!"N".equals(m.getName())) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "[A8Key] username=" + p.getResult());
                        } catch (Throwable ignored) {}
                    }
                });
                hooked = true;
                LogWriter.log(TAG, "已 hook k0.N()");
                break;
            }
            if (!hooked) LogWriter.log(TAG, "k0.N() 未命中");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookA8KeyResult err: " + t);
        }
    }

    /** 纯后台加群：调 addchatroommember（pe5.f.j → qn.m → Cgi 120）。
     *  room=群 username（xxx@chatroom）、ticket=群二维码票据。 */
    private static boolean joinGroupViaApi(ClassLoader cl, String roomUsername, String ticket) {
        try {
            Object api = XposedHelpers.callMethod(
                    XposedHelpers.newInstance(XposedHelpers.findClass("un.k", cl)), "get");
            if (api == null) {
                LogWriter.log(TAG, "ln.a 实例为 null");
                return false;
            }
            Object self = null;
            try {
                self = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("b41.y1", cl), "u");
            } catch (Throwable ignored) {}
            if (self == null) {
                try {
                    self = XposedHelpers.callMethod(
                            XposedHelpers.callStaticMethod(XposedHelpers.findClass("gp0.j1", cl), "x"), "d");
                } catch (Throwable ignored) {}
            }
            if (self == null) {
                LogWriter.log(TAG, "selfWxid 为 null");
                return false;
            }
            List<String> members = new ArrayList<>();
            members.add(String.valueOf(self));
            Object task = XposedHelpers.callMethod(api, "j", roomUsername, members, ticket, null);
            if (task == null) {
                LogWriter.log(TAG, "addchatroommember 任务为 null");
                return false;
            }
            XposedHelpers.callMethod(task, "a");
            LogWriter.log(TAG, "已调用 addchatroommember room=" + roomUsername + " ticket=" + ticket);
            return true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "joinGroupViaApi err: " + t);
            return false;
        }
    }

    /** 投喂图片给微信原生识别引擎（复刻长按识别：wk5.g0.h）。
     *  创建 wk5.g0(Activity, true, talker) → h(null, msgId, "", imgPath, bitmap,
     *  true, 2, true, callback) → 微信走 RecogQBarOfImageFileEvent → 解码 → DealQBarStrEvent → 加群。 */
    private static void triggerWxScan(ClassLoader cl, String talker, long msgId, String imgPath) {
        if (cl == null || imgPath == null || imgPath.isEmpty()) return;
        try {
            Activity act = anyActivity();
            if (act == null) {
                LogWriter.log(TAG, "triggerWxScan: 无可用 Activity，跳过投喂");
                return;
            }
            Object mgr = XposedHelpers.newInstance(
                    XposedHelpers.findClass("wk5.g0", cl),
                    act, true, talker == null ? "" : talker);
            Bitmap bmp = decodeSampledBitmap(imgPath, 4096);
            if (bmp == null) {
                LogWriter.log(TAG, "triggerWxScan: Bitmap 解码失败 " + imgPath);
                return;
            }
            Object callback = makeWk5nCallback(cl, mgr, talker, msgId, imgPath);
            XposedHelpers.callMethod(mgr, "h",
                    null,           // View（后台无锚点 View）
                    msgId,          // long msgId（必须真实值，走 ScanCodeInfo 加群分支）
                    "",             // 备用 path
                    imgPath,        // ★ 图片路径
                    bmp,            // Bitmap（非 null 避免截屏）
                    true,           // getCodePosition
                    2,              // ★ recognizeType=2（聊天图加群）
                    true,           // retry
                    callback);      // wk5.n 回调
            sLastDealQBarTime = System.currentTimeMillis();
            LogWriter.log(TAG, "已投喂微信识别图片 msgId=" + msgId + " path=" + imgPath);
        } catch (Throwable t) {
            LogWriter.log(TAG, "triggerWxScan err: " + t);
        }
    }

    /** 用动态代理实现 wk5.n 回调接口，微信识别结果回传后：
     *  ① 刷新保护窗；② 用 s6.a.a(event) 取微信解码引擎构造的 ImageQBarDataBean 列表
     *  （与微信 UI 层 w6.a 的 D2.a = s6.a.a(event) 完全一致），命中群码即调用
     *  wk5.g0.b(l0, bean, o) 让微信自己构造 DealQBarStrEvent 触发加群。 */
    private static Object makeWk5nCallback(ClassLoader cl, Object mgr, String talker, long msgId, String imgPath) {
        try {
            Class<?> iface = XposedHelpers.findClass("wk5.n", cl);
            return java.lang.reflect.Proxy.newProxyInstance(cl, new Class[]{iface},
                    (proxy, method, args) -> {
                        if ("a".equals(method.getName()) && args != null && args.length > 0) {
                            try {
                                Object ev = args[0];
                                String hit = null;   // 命中通道描述
                                Object bean = null;
                                // ① 主通道：s6.a.a(event) → ArrayList<ImageQBarDataBean>（微信构造，字段正确）
                                try {
                                    Object beanList = XposedHelpers.callStaticMethod(
                                            XposedHelpers.findClass("com.tencent.mm.pluginsdk.ui.tools.s6", cl), "a", ev);
                                    if (beanList instanceof java.util.List) {
                                        for (Object b : (java.util.List<?>) beanList) {
                                            if (b == null) continue;
                                            Object c = XposedHelpers.getObjectField(b, "d");
                                            if (c instanceof String && isGroupQr((String) c)) {
                                                bean = b;
                                                hit = "s6.a.a";
                                                break;
                                            }
                                        }
                                    }
                                } catch (Throwable ignored) {}
                                // ② 对象图提取：遍历 event 及 g 字段对象图找 ImageQBarDataBean
                                if (bean == null) {
                                    bean = findGroupQrBeanInEvent(cl, ev);
                                    if (bean != null) hit = "对象图";
                                }
                                // ③ 兜底：event.g.b String 码列表 → 手动构造 bean
                                if (bean == null) {
                                    Object g = XposedHelpers.getObjectField(ev, "g");
                                    Object codes = g == null ? null : XposedHelpers.getObjectField(g, "b");
                                    if (codes instanceof java.util.List) {
                                        for (Object o : (java.util.List<?>) codes) {
                                            if (o instanceof String && isGroupQr((String) o)) {
                                                bean = newImageQBarDataBean(cl);
                                                if (bean != null) XposedHelpers.setObjectField(bean, "d", (String) o);
                                                hit = "event.g.b";
                                                break;
                                            }
                                        }
                                    }
                                }
                                if (bean != null && hit != null) {
                                    String code = String.valueOf(XposedHelpers.getObjectField(bean, "d"));
                                    LogWriter.log(TAG, "wk5.n 结果回调命中群码 (" + hit + ")");
                                    onGroupQr(code);
                                    triggerDealQBar(mgr, code, bean, imgPath);
                                } else {
                                    LogWriter.log(TAG, "wk5.n 回调无可用 bean，跳过加群");
                                }
                            } catch (Throwable ignored) {}
                        }
                        return null;
                    });
        } catch (Throwable t) {
            LogWriter.log(TAG, "makeWk5nCallback err: " + t);
            return null;
        }
    }

    /** 在 event 对象图中广度优先查找 ImageQBarDataBean（d 为群码 URL）。
     *  不依赖 s6 类名/方法签名，直接遍历 event 及其字段对象。 */
    private static Object findGroupQrBeanInEvent(ClassLoader cl, Object event) {
        if (cl == null || event == null) return null;
        try {
            Class<?> beanCls = XposedHelpers.findClass("com.tencent.mm.plugin.scanner.ImageQBarDataBean", cl);
            java.util.ArrayDeque<Object> queue = new java.util.ArrayDeque<>();
            java.util.Set<Object> visited = new java.util.HashSet<>();
            queue.add(event);
            while (!queue.isEmpty()) {
                Object obj = queue.poll();
                if (obj == null || visited.contains(obj)) continue;
                visited.add(obj);
                if (beanCls.isInstance(obj)) {
                    Object code = XposedHelpers.getObjectField(obj, "d");
                    if (code instanceof String && isGroupQr((String) code)) return obj;
                    continue;
                }
                if (obj instanceof java.util.List) {
                    for (Object item : (java.util.List<?>) obj) {
                        if (item != null && !visited.contains(item)) queue.add(item);
                    }
                }
                for (java.lang.reflect.Field f : obj.getClass().getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        if (v != null && !visited.contains(v)) queue.add(v);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "findGroupQrBeanInEvent err: " + t);
        }
        return null;
    }

    /** 复刻 ImageGalleryUI 上层回调：调用 wk5.g0.b(l0, ImageQBarDataBean, o)，
     *  让微信自己构造 DealQBarStrEvent（字段完整）→ 全局监听器 → v74.v.g → geta8key → 加群。
     *  bean 优先用微信解码引擎构造的实例（e/f 字段正确），否则手动构造。
     *  imgPath 写入 l0.m（→ DealQBarStrEvent.m → model.s bundle.path，缺失会打开扫一扫）。 */
    private static void triggerDealQBar(Object mgr, String code, Object bean, String imgPath) {
        ClassLoader cl = sCl;
        if (cl == null || mgr == null || code == null || bean == null) return;
        try {
            // ① wk5.l0 识别结果聚合体
            Object l0 = XposedHelpers.newInstance(XposedHelpers.findClass("wk5.l0", cl));
            List<String> codeList = new ArrayList<>();
            codeList.add(code);
            XposedHelpers.setObjectField(l0, "a", codeList);   // 码列表
            XposedHelpers.setIntField(l0, "i", 2);             // recognizeType=2
            XposedHelpers.setIntField(l0, "f", -1);             // scene 默认 37（加群）
            XposedHelpers.setIntField(l0, "g", -1);             // source 默认 4
            XposedHelpers.setBooleanField(l0, "d", false);     // 布尔透传
            if (imgPath != null) XposedHelpers.setObjectField(l0, "m", imgPath);  // ★ path → bundle
            // ② 确保 bean.d = 码值（若来自微信现成 bean，d 已经是码值，重复设置无副作用）
            XposedHelpers.setObjectField(bean, "d", code);
            // ★ 若 bean 是手动构造的（e/f 为 0），补上微信真实码场景/图片来源。
            //   hookScanResult 实测：微信长按识别群码时 e=19（码场景）、f=6（图片来源）。
            //   缺失时微信把 DealQBarStrEvent 当作未知来源 → 打开扫一扫而不是加群。
            try {
                if (XposedHelpers.getIntField(bean, "e") == 0) XposedHelpers.setIntField(bean, "e", 19);
                if (XposedHelpers.getIntField(bean, "f") == 0) XposedHelpers.setIntField(bean, "f", 6);
            } catch (Throwable ignored) {}
            // ③ wk5.o 回调（单方法接口，空实现）
            Object cb = makeWk5oCallback(cl);
            // ④ 调 b()
            XposedHelpers.callMethod(mgr, "b", l0, bean, cb);
            sLastDealQBarTime = System.currentTimeMillis();
            LogWriter.log(TAG, "已调用 wk5.g0.b 触发加群流程: " + code);
        } catch (Throwable t) {
            LogWriter.log(TAG, "triggerDealQBar err: " + t);
        }
    }

    /** wk5.o 回调接口动态代理（b() 参数，空实现即可）。 */
    private static Object makeWk5oCallback(ClassLoader cl) {
        try {
            Class<?> iface = XposedHelpers.findClass("wk5.o", cl);
            return java.lang.reflect.Proxy.newProxyInstance(cl, new Class[]{iface},
                    (proxy, method, args) -> null);
        } catch (Throwable t) {
            LogWriter.log(TAG, "makeWk5oCallback err: " + t);
            return null;
        }
    }

    /** 创建 ImageQBarDataBean 实例：优先无参构造，失败用 Unsafe.allocateInstance 绕过构造器。 */
    private static Object newImageQBarDataBean(ClassLoader cl) {
        Class<?> cls = XposedHelpers.findClass("com.tencent.mm.plugin.scanner.ImageQBarDataBean", cl);
        try {
            return XposedHelpers.newInstance(cls);
        } catch (Throwable ignored) {}
        try {
            Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field f = unsafeCls.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);
            java.lang.reflect.Method allocate = unsafeCls.getMethod("allocateInstance", Class.class);
            return allocate.invoke(unsafe, cls);
        } catch (Throwable t) {
            LogWriter.log(TAG, "newImageQBarDataBean err: " + t);
            return null;
        }
    }

    // ==================== 工具 ====================

    /** 群二维码判定（手册 §4.1 唯一权威规则）。 */
    static boolean isGroupQr(String s) {
        if (s == null || s.isEmpty()) return false;
        String t = s.trim();
        for (String p : GROUP_PREFIX) {
            if (t.startsWith(p)) return true;
        }
        return false;
    }

    /** 白名单过滤：空 = 全部群放行。 */
    private static boolean inWhitelist(String talker) {
        String wl = sWhitelist;
        if (wl == null || wl.isEmpty()) return true;
        for (String s : wl.split("[,，]")) {
            if (s.trim().equals(talker)) return true;
        }
        return false;
    }

    /** 每日入群上限（跨天自动重置）。 */
    private static synchronized boolean allowToday() {
        try {
            SharedPreferences sp = ContextManager.getPrefs();
            if (sp == null) return true;
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            String last = sp.getString(K_TODAY, "");
            int count = sp.getInt(K_TODAY_COUNT, 0);
            if (!today.equals(last)) {
                sp.edit().putString(K_TODAY, today).putInt(K_TODAY_COUNT, 0).apply();
                count = 0;
            }
            if (count >= sMaxPerDay) return false;
            sp.edit().putInt(K_TODAY_COUNT, count + 1).apply();
            return true;
        } catch (Throwable t) {
            return true;
        }
    }
}
