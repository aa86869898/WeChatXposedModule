package com.leshao.v3.ai;

import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.VersionCompat;

import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.util.List;

public class WxReflect {
    private static final String TAG = "WxReflect";
    private final ClassLoader cl;
    public WxReflect(ClassLoader cl) { this.cl = cl; }

    public Object getMsgStorage() {
        // 用户验证: pa5.n0.c(h2.class).wj() 返回 com.tencent.mm.storage.f9
        try {
            Class<?> kernel = XposedHelpers.findClass(AiConst.CLS_KERNEL, cl);
            Class<?> svcCls = XposedHelpers.findClass(AiConst.CLS_MSG_SERVICE, cl);
            LogWriter.log(TAG, "getMsgStorage: kernel=" + kernel.getName() + " svc=" + svcCls.getName());
            Object service = XposedHelpers.callStaticMethod(kernel, AiConst.M_GET_SERVICE, svcCls);
            LogWriter.log(TAG, "getMsgStorage: service=" + (service == null ? "null" : service.getClass().getName()));
            if (service != null) {
                Object storage = XposedHelpers.callMethod(service, AiConst.M_GET_STORAGE);
                LogWriter.log(TAG, "getMsgStorage: storage=" + (storage == null ? "null" : storage.getClass().getName()));
                if (storage != null) return storage;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "getMsgStorage: kernel 方式失败: " + t.getMessage());
        }
        // 备用: shortClass.b().u()
        try {
            Class<?> shortCls = VersionCompat.findMsgStorageShortClass(cl);
            if (shortCls != null) {
                Object service = XposedHelpers.callStaticMethod(shortCls, "b");
                if (service != null) {
                    Object storage = XposedHelpers.callMethod(service, "u");
                    if (storage != null) return storage;
                }
            }
        } catch (Throwable ignored) {}
        LogWriter.log(TAG, "getMsgStorage: 全部方式失败，返回 null");
        return null;
    }

    @SuppressWarnings("unchecked")
    public List<Object> queryMessages(String talker, long createTime, int limit) {
        Object storage = getMsgStorage();
        if (storage == null) {
            LogWriter.log(TAG, "queryMessages: storage 为 null，无法查询");
            return null;
        }
        dumpStorageMethodsOnce(storage);

        long nowMs = System.currentTimeMillis();
        long nowSec = nowMs / 1000;
        List<Object> best = null;

        Class<?> cur = storage.getClass();
        while (cur != null && cur != Object.class) {
            for (Method m : cur.getDeclaredMethods()) {
                if (!List.class.isAssignableFrom(m.getReturnType())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length < 1 || pts.length > 3) continue;
                if (pts[0] != String.class) continue;

                for (Object[] args : buildArgSets(talker, pts, nowMs, nowSec, limit)) {
                    try {
                        m.setAccessible(true);
                        Object r = m.invoke(storage, args);
                        if (r instanceof List) {
                            int n = ((List) r).size();
                            LogWriter.log(TAG, "queryMessages: " + m.getName() + "(" + params(pts)
                                    + ") -> " + n + " 条");
                            if (n > 0) return (List<Object>) r;
                            if (best == null) best = (List<Object>) r;
                        }
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "queryMessages: " + m.getName() + "(" + params(pts)
                                + ") 异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }
            }
            cur = cur.getSuperclass();
        }
        LogWriter.log(TAG, "queryMessages: 无任何方法返回非空结果 talker=" + talker
                + (best == null ? "" : " (空列表 size=" + best.size() + ")"));
        return best;
    }

    private static List<Object[]> buildArgSets(String talker, Class<?>[] pts,
                                               long nowMs, long nowSec, int limit) {
        List<Object[]> sets = new java.util.ArrayList<>();
        if (pts.length == 1) {
            sets.add(new Object[]{talker});
        } else if (pts.length == 2) {
            Class<?> p1 = pts[1];
            if (p1 == long.class || p1 == Long.class) {
                sets.add(new Object[]{talker, 0L});
                sets.add(new Object[]{talker, nowMs});
                sets.add(new Object[]{talker, Long.MAX_VALUE});
            } else if (p1 == int.class || p1 == Integer.class) {
                sets.add(new Object[]{talker, limit});
                sets.add(new Object[]{talker, 1});
                sets.add(new Object[]{talker, 0});
            } else if (p1 == String.class) {
                sets.add(new Object[]{talker, ""});
            }
        } else if (pts.length == 3) {
            Class<?> p1 = pts[1], p2 = pts[2];
            boolean p1long = p1 == long.class || p1 == Long.class;
            boolean p2long = p2 == long.class || p2 == Long.class;
            boolean p1int = p1 == int.class || p1 == Integer.class;
            boolean p2int = p2 == int.class || p2 == Integer.class;
            boolean p1bool = p1 == boolean.class || p1 == Boolean.class;
            if (p1long && p2long) {
                sets.add(new Object[]{talker, 0L, nowMs});
                sets.add(new Object[]{talker, 0L, Long.MAX_VALUE});
                sets.add(new Object[]{talker, nowMs, 0L});
            } else if (p1long && p2int) {
                sets.add(new Object[]{talker, nowMs, limit});
                sets.add(new Object[]{talker, nowSec, limit});
                sets.add(new Object[]{talker, 0L, limit});
                sets.add(new Object[]{talker, Long.MAX_VALUE, limit});
            } else if (p1bool && p2int) {
                sets.add(new Object[]{talker, Boolean.FALSE, limit});
                sets.add(new Object[]{talker, Boolean.TRUE, limit});
            }
        }
        return sets;
    }

    private static String params(Class<?>[] pts) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> p : pts) sb.append(p.getSimpleName()).append(",");
        return sb.toString();
    }

    private static volatile boolean storageDumped = false;

    private void dumpStorageMethodsOnce(Object storage) {
        if (storageDumped) return;
        storageDumped = true;
        try {
            Class<?> cur = storage.getClass();
            StringBuilder sb = new StringBuilder("storage(" + cur.getName() + ") 方法: ");
            while (cur != null && cur != Object.class) {
                for (Method m : cur.getDeclaredMethods()) {
                    if (!List.class.isAssignableFrom(m.getReturnType())) continue;
                    sb.append(m.getName()).append("(");
                    for (Class<?> p : m.getParameterTypes()) sb.append(p.getSimpleName()).append(",");
                    sb.append("):List ");
                }
                cur = cur.getSuperclass();
            }
            LogWriter.log(TAG, sb.toString());
        } catch (Throwable t) {
            LogWriter.log(TAG, "dumpStorageMethods 失败: " + t.getMessage());
        }
    }

    public static String talker(Object msg) {
        try { Object o = XposedHelpers.getObjectField(msg, AiConst.F_TALKER); if (o != null) return o.toString(); } catch (Throwable ignored) {}
        try { return (String) XposedHelpers.callMethod(msg, AiConst.M_GET_TALKER); } catch (Throwable ignored) {}
        return "";
    }

    public static String content(Object msg) {
        try { Object o = XposedHelpers.getObjectField(msg, AiConst.F_CONTENT); if (o != null) return o.toString(); } catch (Throwable ignored) {}
        for (String m : new String[]{AiConst.M_GET_CONTENT, AiConst.M_GET_CONTENT_ALT1, AiConst.M_GET_CONTENT_ALT2}) {
            try { return (String) XposedHelpers.callMethod(msg, m); } catch (Throwable ignored) {}
        }
        return "";
    }

    public static int type(Object msg) {
        try { return XposedHelpers.getIntField(msg, AiConst.F_TYPE); } catch (Throwable ignored) {}
        try { return (Integer) XposedHelpers.callMethod(msg, AiConst.M_GET_TYPE); } catch (Throwable ignored) { return -1; }
    }

    public static int isSend(Object msg) {
        try { return XposedHelpers.getIntField(msg, AiConst.F_IS_SEND); } catch (Throwable ignored) {}
        try { return (Integer) XposedHelpers.callMethod(msg, AiConst.M_GET_ISSEND); } catch (Throwable ignored) { return -1; }
    }

    public static long createTime(Object msg) {
        try { return XposedHelpers.getLongField(msg, AiConst.F_CREATE); } catch (Throwable ignored) {}
        try { return (Long) XposedHelpers.callMethod(msg, "getCreateTime"); } catch (Throwable ignored) {}
        return 0L;
    }

    public static long msgId(Object msg) {
        try { return (Long) XposedHelpers.callMethod(msg, AiConst.M_GET_MSGID); } catch (Throwable ignored) { return 0L; }
    }

    public static boolean isIncomingText(Object msg) {
        return type(msg) == AiConst.TYPE_TEXT && isSend(msg) == 0;
    }

    private static volatile boolean fieldsDumped = false;

    public static void dumpMsgInfoFields(Object sampleMsg) {
        if (sampleMsg == null || fieldsDumped) return;
        fieldsDumped = true;
        try {
            Class<?> c = sampleMsg.getClass();
            StringBuilder sb = new StringBuilder("MsgInfo(" + c.getName() + ") 字段: ");
            Class<?> cur = c;
            while (cur != null && cur != Object.class) {
                for (java.lang.reflect.Field f : cur.getDeclaredFields()) {
                    sb.append(f.getName()).append(":").append(f.getType().getSimpleName()).append(" ");
                }
                cur = cur.getSuperclass();
            }
            LogWriter.log(TAG, sb.toString());
        } catch (Throwable t) {
            LogWriter.log(TAG, "dumpMsgInfoFields 失败: " + t.getMessage());
        }
    }
}
