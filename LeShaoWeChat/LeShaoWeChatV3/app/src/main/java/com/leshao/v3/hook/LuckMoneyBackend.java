package com.leshao.v3.hook;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯后台拆红包/收转账：不打开聊天页、不扫描视图点击，
 * 直接从消息 XML 解析 sendId/channelId/nativeUrl，反射定位微信红包插件后台模型并调用。
 *
 * 第一版策略：完整诊断日志 + 多候选反射调用。
 * 命中则纯后台拆包，未命中则日志给出精确类名/方法签名供下一版校准。
 */
public class LuckMoneyBackend {

    private static final String TAG = "LuckMoneyBackend";
    private static final AtomicBoolean sScanning = new AtomicBoolean(false);

    private LuckMoneyBackend() {}

    // ==================== 红包入口 ====================

    public static void grabRedPacket(String talker, String content, long msgId) {
        try {
            LogWriter.log(TAG, "=== REDPACKET XML BEGIN (msgId=" + msgId + " talker=" + talker + ") ===");
            LogWriter.log(TAG, content == null ? "null" : content);
            LogWriter.log(TAG, "=== REDPACKET XML END ===");

            HbInfo info = parseHb(content);
            if (info == null) {
                LogWriter.log(TAG, "parse FAIL: no sendid/channelid found");
                return;
            }
            LogWriter.log(TAG, "parsed sendId=" + info.sendId
                + " channelId=" + info.channelId + " msgType=" + info.msgType);
            scanLuckymoney(info);
        } catch (Throwable t) {
            LogWriter.log(TAG, "grabRedPacket err: " + t);
        }
    }

    // ==================== 转账入口 ====================

    public static void grabTransfer(String talker, String content, long msgId) {
        try {
            LogWriter.log(TAG, "=== TRANSFER XML BEGIN (msgId=" + msgId + " talker=" + talker + ") ===");
            LogWriter.log(TAG, content == null ? "null" : content);
            LogWriter.log(TAG, "=== TRANSFER XML END ===");
            scanRemittance(content);
        } catch (Throwable t) {
            LogWriter.log(TAG, "grabTransfer err: " + t);
        }
    }

    // ==================== XML 解析 ====================

    static class HbInfo {
        String sendId;
        String channelId;
        String msgType;
        String nativeUrl;
    }

    static HbInfo parseHb(String content) {
        if (content == null || content.isEmpty()) return null;
        HbInfo info = new HbInfo();
        info.sendId = extractParam(content, "sendid");
        info.channelId = extractParam(content, "channelid");
        info.msgType = extractParam(content, "msgtype");
        info.nativeUrl = extractNativeUrl(content);
        if (isBlank(info.sendId) && isBlank(info.channelId)) return null;
        return info;
    }

    static String extractNativeUrl(String content) {
        String url = extractTag(content, "nativeurl");
        if (isBlank(url)) url = extractTag(content, "url");
        return url;
    }

    static String extractTag(String xml, String tag) {
        if (xml == null || tag == null) return null;
        int s = xml.indexOf("<" + tag + ">");
        if (s < 0) return null;
        s += tag.length() + 2;
        int e = xml.indexOf("</" + tag + ">", s);
        if (e < 0) return null;
        String v = xml.substring(s, e);
        return v.trim();
    }

    static String extractParam(String s, String key) {
        if (s == null || key == null) return null;
        Matcher m = Pattern.compile("(?:[?&;])" + key + "=([^&\"'<>\\s]+)").matcher(s);
        if (m.find()) {
            String v = m.group(1);
            if (v.endsWith("&amp;")) v = v.substring(0, v.length() - 5);
            return v;
        }
        m = Pattern.compile("<" + key + ">([^<]+)</" + key + ">").matcher(s);
        if (m.find()) return m.group(1);
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ==================== 枚举定位 + 调用 ====================

    private static void scanLuckymoney(final HbInfo info) {
        new Thread(() -> {
            try {
                doScanLuckymoney(info);
            } catch (Throwable t) {
                LogWriter.log(TAG, "scanLuckymoney err: " + t);
            }
        }, "leshao-hb-backend").start();
    }

    private static void doScanLuckymoney(HbInfo info) {
        if (!sScanning.compareAndSet(false, true)) return;
        try {
            String apkPath = ContextManager.getApkPath();
            ClassLoader cl = ContextManager.getClassLoader();
            if (apkPath == null || cl == null) {
                LogWriter.log(TAG, "scan abort: apkPath or classLoader null");
                return;
            }
            dalvik.system.DexFile dex = new dalvik.system.DexFile(apkPath);
            try {
                Enumeration<String> entries = dex.entries();
                int modelCount = 0;
                while (entries.hasMoreElements()) {
                    String cn = entries.nextElement();
                    if (cn.startsWith("com.tencent.mm.plugin.luckymoney.model.") && !cn.contains("$")) {
                        modelCount++;
                        inspectLuckymoneyClass(cl, cn, info);
                    }
                }
                LogWriter.log(TAG, "luckymoney.model scanned: " + modelCount + " classes");
            } finally {
                dex.close();
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "doScanLuckymoney err: " + t.getMessage());
        } finally {
            sScanning.set(false);
        }
    }

    private static void inspectLuckymoneyClass(ClassLoader cl, String cn, HbInfo info) {
        try {
            Class<?> cls = cl.loadClass(cn);
            if (cls == null) return;
            String simple = cls.getSimpleName();
            boolean interesting = simple.toLowerCase().contains("open")
                || simple.toLowerCase().contains("receive")
                || simple.toLowerCase().contains("netscene");
            StringBuilder sb = new StringBuilder("LM cls: ").append(cn);
            for (Constructor<?> c : cls.getDeclaredConstructors()) {
                Class<?>[] pts = c.getParameterTypes();
                if (pts.length >= 2) {
                    sb.append(" | ctor(");
                    for (Class<?> p : pts) sb.append(p.getSimpleName()).append(",");
                    sb.append(")");
                }
            }
            if (interesting) {
                for (Method m : cls.getDeclaredMethods()) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length >= 2) {
                        sb.append(" | m.").append(m.getName()).append("(");
                        for (Class<?> p : pts) sb.append(p.getSimpleName()).append(",");
                        sb.append(")");
                    }
                }
                LogWriter.log(TAG, sb.toString());
                tryInvokeReceive(cls, info);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 多候选反射调用拆包方法，全部 try-catch，失败只记日志，不影响主进程。
     */
    private static void tryInvokeReceive(Class<?> cls, HbInfo info) {
        ClassLoader cl = cls.getClassLoader();
        try {
            String[] methodNames = {"receive", "open", "a", "doScene", "c", "b"};
            for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                Class<?>[] pts = ctor.getParameterTypes();
                if (pts.length < 2) continue;
                Object[] args = buildArgs(pts, info);
                if (args == null) continue;
                try {
                    Object inst = ctor.newInstance(args);
                    for (String mn : methodNames) {
                        for (Method m : cls.getDeclaredMethods()) {
                            if (!m.getName().equals(mn)) continue;
                            Class<?>[] mpts = m.getParameterTypes();
                            if (mpts.length != 0) continue;
                            if (!Modifier.isStatic(m.getModifiers())) continue;
                            try {
                                m.setAccessible(true);
                                m.invoke(null);
                                LogWriter.log(TAG, "INVOKE static " + cls.getSimpleName() + "." + mn + "() called");
                            } catch (Throwable ignored) {}
                        }
                    }
                    LogWriter.log(TAG, "constructed " + cls.getSimpleName()
                        + " with " + pts.length + " args (no further auto-call)");
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static Object[] buildArgs(Class<?>[] pts, HbInfo info) {
        Object[] args = new Object[pts.length];
        int strIdx = 0;
        String[] strs = {info.sendId, info.channelId, info.msgType, info.nativeUrl};
        for (int i = 0; i < pts.length; i++) {
            Class<?> p = pts[i];
            if (p == String.class) {
                if (strIdx < strs.length && strs[strIdx] != null) {
                    args[i] = strs[strIdx];
                } else {
                    args[i] = "";
                }
                strIdx++;
            } else if (p == int.class || p == Integer.class) {
                args[i] = 1;
            } else if (p == boolean.class || p == Boolean.class) {
                args[i] = false;
            } else if (p == long.class || p == Long.class) {
                args[i] = 0L;
            } else {
                return null;
            }
        }
        return args;
    }

    // ==================== 转账枚举 ====================

    private static void scanRemittance(final String content) {
        new Thread(() -> {
            try {
                String apkPath = ContextManager.getApkPath();
                ClassLoader cl = ContextManager.getClassLoader();
                if (apkPath == null || cl == null) return;
                dalvik.system.DexFile dex = new dalvik.system.DexFile(apkPath);
                try {
                    Enumeration<String> entries = dex.entries();
                    int count = 0;
                    while (entries.hasMoreElements()) {
                        String cn = entries.nextElement();
                        if (!cn.startsWith("com.tencent.mm.plugin.remittance.model.")) continue;
                        if (cn.contains("$")) continue;
                        count++;
                        Class<?> cls = cl.loadClass(cn);
                        StringBuilder sb = new StringBuilder("RM cls: ").append(cn);
                        for (Method m : cls.getDeclaredMethods()) {
                            Class<?>[] pts = m.getParameterTypes();
                            if (pts.length >= 1) {
                                sb.append(" | m.").append(m.getName()).append("(");
                                for (Class<?> p : pts) sb.append(p.getSimpleName()).append(",");
                                sb.append(")");
                            }
                        }
                        LogWriter.log(TAG, sb.toString());
                    }
                    LogWriter.log(TAG, "remittance.model scanned: " + count + " classes");
                } finally {
                    dex.close();
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "scanRemittance err: " + t.getMessage());
            }
        }, "leshao-tr-backend").start();
    }
}
