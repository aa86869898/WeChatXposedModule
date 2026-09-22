/*
 * ============================================================================
 *  文件名: WxChatBridge.java
 *  功能  : 微信 8.0.x 存储层反射桥(参照 一键导入导出聊天记录.zip / WxBridge.java)
 *  链路  : j1.s(sh3.c4) [zip 为 gp0.j1.v(tn3.c4.class)] -> h2 ->
 *          h2.lj()=f9(消息存储) / h2.ej()=l4(会话存储)
 *  实体  : com.tencent.mm.storage.e9 (消息, 继承 im.c8 含全部字段 getter/setter)
 *  方法  : f9.M7(talker,500) 最近消息 / f9.H2(talker,time,500) 分页往前 /
 *          f9.J3(talker,time) 单条 / f9.Bb(e9,false) 插入 / l4.q(List) 枚举会话
 *  适配  : J1 服务定位类名优先取 DexKitHelper.getJ1ServiceClass(), 再候选 gp0.j1
 *          (与 ChatGroupHook/GroupMemberTools 一致), 同时兼容 zip 的 v(Class) 与
 *          项目已确认的 s(Class) 调用方式, 字段 getter/setter 带多候选兜底。
 * ============================================================================
 */
package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XposedHelpers;

public class WxChatBridge {

    private static final String TAG = "WxChatBridge";

    public static final String C_J1 = "gp0.j1";
    public static final String C_J1_ALT = "gp0.j1.j";
    public static final String C_C4 = "tn3.c4";          // zip
    public static final String C_C4_ALT = "sh3.c4";      // 项目已确认
    public static final String C_MSG_STORE = "com.tencent.mm.storage.f9";
    public static final String C_CONV_STORE = "com.tencent.mm.storage.l4";
    public static final String C_MSG = "com.tencent.mm.storage.e9";

    private static ClassLoader sCl;
    private static volatile boolean sInited;
    private static Object sH2;
    private static Object sMsgStore;    // f9
    private static Object sConvStore;   // l4
    private static Class<?> sMsgCls;    // e9

    /* e9 字段 getter/setter (zip 主候选 + 常见兜底) */
    private static Method mGetMsgId, mSetMsgId;
    private static Method mGetSvrId, mSetSvrId;
    private static Method mGetType, mSetType;
    private static Method mGetStatus, mSetStatus;
    private static Method mGetIsSend, mSetIsSend;
    private static Method mGetTime, mSetTime;
    private static Method mGetTalker, mSetTalker;
    private static Method mGetContent, mSetContent;
    private static Method mGetSeq, mSetSeq;
    private static Method mGetLvbuf, mSetLvbuf;

    /** 内核是否就绪: j1 类存在即可(cl.isReady 语义等价) */
    public static boolean isReady(ClassLoader cl) {
        Class<?> j1 = findJ1Class(cl);
        return j1 != null;
    }

    public static synchronized void init(ClassLoader cl) throws Exception {
        if (sInited) return;
        sCl = cl;
        Class<?> j1 = findJ1Class(cl);
        if (j1 == null) throw new IllegalStateException("j1 service locator not found");

        Class<?> c4 = findC4Class(cl);
        Object h2 = null;
        // zip: j1.v(Class); 项目: j1.s(Class)
        for (String mn : new String[]{"v", "s"}) {
            try {
                h2 = callStaticOpt(j1, mn, c4);
                if (h2 != null) break;
            } catch (Throwable ignored) {}
        }
        if (h2 == null) throw new IllegalStateException("h2 == null (内核未就绪?)");
        sH2 = h2;

        sMsgStore = invokeOpt(sH2, "lj");
        sConvStore = invokeOpt(sH2, "ej");
        if (sMsgStore == null || sConvStore == null) {
            throw new IllegalStateException("f9/l4 store null: msg=" + (sMsgStore != null) + " conv=" + (sConvStore != null));
        }

        sMsgCls = findMsgEntity(cl);

        java.lang.reflect.Constructor<?> ctor = null;
        for (java.lang.reflect.Constructor<?> cc : sMsgCls.getDeclaredConstructors()) {
            Class<?>[] pts = cc.getParameterTypes();
            if (pts.length == 1 && pts[0] == String.class) { ctor = cc; break; }
        }
        if (ctor == null) throw new IllegalStateException("e9(String) ctor not found");
        ctor.setAccessible(true);
        sCtorMsgByTalker = ctor;

        mGetMsgId = findMethod(sMsgCls, true, false, "getMsgId", "W0", "a");
        mSetMsgId = findMethod(sMsgCls, false, true, "setMsgId");
        mGetSvrId = findMethod(sMsgCls, true, false, "F0", "getMsgSvrId", "f0");
        mSetSvrId = findMethod(sMsgCls, false, true, "q1", "setMsgSvrId", "B");
        mGetType = findMethod(sMsgCls, true, false, "getType", "j0");
        mSetType = findMethod(sMsgCls, false, true, "setType", "k0");
        mGetStatus = findMethod(sMsgCls, true, false, "M0", "getStatus", "G0");
        mSetStatus = findMethod(sMsgCls, false, true, "t1", "setStatus", "v1");
        mGetIsSend = findMethod(sMsgCls, true, false, "z0", "getIsSend", "p0");
        mSetIsSend = findMethod(sMsgCls, false, true, "k1", "setIsSend", "h1");
        mGetTime = findMethod(sMsgCls, true, false, "getCreateTime", "gK", "t0");
        mSetTime = findMethod(sMsgCls, false, true, "e1", "setCreateTime", "d1");
        mGetTalker = findMethod(sMsgCls, true, false, "N0", "getTalker", "C0");
        mSetTalker = findMethod(sMsgCls, false, true, "u1", "setTalker", "J0");
        mGetContent = findMethod(sMsgCls, true, false, "j", "getContent", "l0");
        mSetContent = findMethod(sMsgCls, false, true, "b1", "setContent", "a1");
        mGetSeq = findMethod(sMsgCls, true, false, "X1", "getMsgSeq", "H0");
        mSetSeq = findMethod(sMsgCls, false, true, "n1", "setMsgSeq", "o1");
        mGetLvbuf = findMethod(sMsgCls, true, false, "A0", "getLvbuffer", "y0");
        mSetLvbuf = findMethod(sMsgCls, false, true, "l1", "setLvbuffer", "m1");

        sInited = true;
        LogWriter.log(TAG, "init OK j1=" + j1.getName()
                + " f9=" + (sMsgStore != null) + " l4=" + (sConvStore != null) + " e9=" + sMsgCls.getName());
    }

    public static boolean isInited() { return sInited; }
    public static Object msgStore() { return sMsgStore; }
    public static Object convStore() { return sConvStore; }
    public static Class<?> msgClazz() { return sMsgCls; }

    private static java.lang.reflect.Constructor<?> sCtorMsgByTalker;

    public static Object newMsg(String talker) throws Exception {
        if (!sInited || sCtorMsgByTalker == null) throw new IllegalStateException("bridge not inited");
        return sCtorMsgByTalker.newInstance(talker);
    }

    /* ================== 会话/消息操作 ================== */

    /** l4.q(null) -> List<String> 全部会话 username */
    @SuppressWarnings("unchecked")
    public static List<String> getAllTalkers() throws Exception {
        if (!sInited || sConvStore == null) return Collections.emptyList();
        Object r = invokeOpt(sConvStore, "q");
        if (r == null) {
            // 兜底: 尝试 q(List.class) 重载
            r = invokeMethod(sConvStore, "q", new Class<?>[]{List.class}, new Object[]{null});
        }
        if (r instanceof List) return (List<String>) r;
        return Collections.emptyList();
    }

    /** f9.Bb(e9, false) 插入, 返回新 msgId */
    public static long insertMsg(Object e9) throws Exception {
        if (!sInited || sMsgStore == null) return -1;
        Object r = invokeMethod(sMsgStore, "Bb", new Class<?>[]{sMsgCls, boolean.class}, new Object[]{e9, false});
        if (r == null) r = invokeMethod(sMsgStore, "b", new Class<?>[]{sMsgCls, boolean.class}, new Object[]{e9, false});
        if (r == null) throw new IllegalStateException("f9.Bb insert returned null");
        return ((Number) r).longValue();
    }

    /* f9.M7(talker, count) 最近 N 条 */
    public static List<?> getLastMsgs(String talker, int count) throws Exception {
        return (List<?>) invokeMethod(sMsgStore, "M7", new Class<?>[]{String.class, int.class}, new Object[]{talker, count});
    }

    /* f9.H2(talker, createTime, count) createTime 之前 N 条(分页) */
    public static List<?> getMsgsBefore(String talker, long createTime, int count) throws Exception {
        Object r = invokeMethod(sMsgStore, "H2", new Class<?>[]{String.class, long.class, int.class}, new Object[]{talker, createTime, count});
        if (r == null) r = invokeMethod(sMsgStore, "g", new Class<?>[]{String.class, long.class, int.class}, new Object[]{talker, createTime, count});
        return (List<?>) r;
    }

    /* f9.J3(talker, createTime) 单条 */
    public static Object getMsgByTime(String talker, long createTime) throws Exception {
        Object r = invokeMethod(sMsgStore, "J3", new Class<?>[]{String.class, long.class}, new Object[]{talker, createTime});
        if (r == null) r = invokeMethod(sMsgStore, "h", new Class<?>[]{String.class, long.class}, new Object[]{talker, createTime});
        return r;
    }

    /* ================== e9 字段读取/写入 ================== */

    private static Object getter(Method m, Object e9) throws Exception {
        if (m == null) return null;
        return m.invoke(e9);
    }
    private static Object setter(Method m, Object e9, Object v) throws Exception {
        if (m == null) return v;
        m.invoke(e9, v);
        return v;
    }

    public static long getMsgId(Object e9) throws Exception { Object v = getter(mGetMsgId, e9); return v instanceof Number ? ((Number) v).longValue() : 0L; }
    public static long getSvrId(Object e9) throws Exception { Object v = getter(mGetSvrId, e9); return v instanceof Number ? ((Number) v).longValue() : 0L; }
    public static int getType(Object e9) throws Exception { Object v = getter(mGetType, e9); return v instanceof Number ? ((Number) v).intValue() : 0; }
    public static int getStatus(Object e9) throws Exception { Object v = getter(mGetStatus, e9); return v instanceof Number ? ((Number) v).intValue() : 0; }
    public static int getIsSend(Object e9) throws Exception { Object v = getter(mGetIsSend, e9); return v instanceof Number ? ((Number) v).intValue() : 0; }
    public static long getCreateTime(Object e9) throws Exception { Object v = getter(mGetTime, e9); return v instanceof Number ? ((Number) v).longValue() : 0L; }
    public static String getTalker(Object e9) throws Exception { Object v = getter(mGetTalker, e9); return v == null ? "" : v.toString(); }
    public static String getContent(Object e9) throws Exception { Object v = getter(mGetContent, e9); return v == null ? "" : v.toString(); }
    public static long getMsgSeq(Object e9) throws Exception { Object v = getter(mGetSeq, e9); return v instanceof Number ? ((Number) v).longValue() : 0L; }
    public static byte[] getLvbuf(Object e9) throws Exception { Object v = getter(mGetLvbuf, e9); return v instanceof byte[] ? (byte[]) v : null; }

    public static void setMsgId(Object e9, long v) throws Exception { setter(mSetMsgId, e9, v); }
    public static void setSvrId(Object e9, long v) throws Exception { setter(mSetSvrId, e9, v); }
    public static void setType(Object e9, int v) throws Exception { setter(mSetType, e9, v); }
    public static void setStatus(Object e9, int v) throws Exception { setter(mSetStatus, e9, v); }
    public static void setIsSend(Object e9, int v) throws Exception { setter(mSetIsSend, e9, v); }
    public static void setCreateTime(Object e9, long v) throws Exception { setter(mSetTime, e9, v); }
    public static void setTalker(Object e9, String v) throws Exception { setter(mSetTalker, e9, v); }
    public static void setContent(Object e9, String v) throws Exception { setter(mSetContent, e9, v); }
    public static void setMsgSeq(Object e9, long v) throws Exception { setter(mSetSeq, e9, v); }
    public static void setLvbuf(Object e9, byte[] v) throws Exception { setter(mSetLvbuf, e9, v); }

    /* ================== 反射工具 ================== */

    private static Class<?> findJ1Class(ClassLoader cl) {
        // v955: DexKit 动态检索优先(特征字符串 "Kernel not initialized" + v/s(Class) 签名),
        // 硬编码候选仅历史版本兜底; 3180 实证为 gp0.j1(仅 v(Class) 方法)
        String dk = DexKitHelper.getJ1ServiceClass();
        if (dk != null && !dk.isEmpty()) {
            try { return XposedHelpers.findClass(dk, cl); } catch (Throwable ignored) {}
        }
        for (String n : new String[]{C_J1, C_J1_ALT, "gp0.j1", "hm0.j1", "fp0.j1", "fp0.j1.j"}) {
            try { return XposedHelpers.findClass(n, cl); } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Class<?> findC4Class(ClassLoader cl) {
        for (String n : new String[]{C_C4_ALT, C_C4}) {
            try { return XposedHelpers.findClass(n, cl); } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object callStaticOpt(Class<?> cls, String name, Object arg) {
        try {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == Class.class) {
                    m.setAccessible(true);
                    return m.invoke(null, arg);
                }
            }
            for (Method m : cls.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == Class.class) {
                    return m.invoke(null, arg);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object invokeOpt(Object o, String name) {
        try {
            for (Method m : o.getClass().getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    return m.invoke(o);
                }
            }
            for (Method m : o.getClass().getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    return m.invoke(o);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object invokeMethod(Object o, String name, Class<?>[] pts, Object[] args) {
        try {
            Method m = null;
            for (Method mm : o.getClass().getDeclaredMethods()) {
                if (mm.getName().equals(name) && matchParams(mm, pts)) { m = mm; break; }
            }
            if (m == null) {
                for (Method mm : o.getClass().getMethods()) {
                    if (mm.getName().equals(name) && matchParams(mm, pts)) { m = mm; break; }
                }
            }
            if (m == null) return null;
            m.setAccessible(true);
            return m.invoke(o, args);
        } catch (Throwable t) {
            LogWriter.log(TAG, "invoke " + name + " err: " + t.getMessage());
            return null;
        }
    }

    private static boolean matchParams(Method m, Class<?>[] pts) {
        Class<?>[] ap = m.getParameterTypes();
        if (ap.length != pts.length) return false;
        for (int i = 0; i < ap.length; i++) {
            if (pts[i] == long.class && (ap[i] == long.class || ap[i] == Long.class)) continue;
            if (pts[i] == int.class && (ap[i] == int.class || ap[i] == Integer.class)) continue;
            if (pts[i] == boolean.class && (ap[i] == boolean.class || ap[i] == Boolean.class)) continue;
            if (!ap[i].isAssignableFrom(pts[i])) return false;
        }
        return true;
    }

    /** 找 e9 的 getter(0参) 或 setter(1参 + 参数类型匹配 v) */
    private static Method findMethod(Class<?> cls, boolean isGetter, boolean isSetter, String... names) {
        for (String n : names) {
            try {
                for (Method m : cls.getDeclaredMethods()) {
                    if (!m.getName().equals(n)) continue;
                    int pc = m.getParameterCount();
                    if (isGetter && pc == 0) {
                        Class<?> rt = m.getReturnType();
                        if (rt == long.class || rt == int.class || rt == String.class || rt == byte[].class) {
                            m.setAccessible(true);
                            return m;
                        }
                        continue;
                    }
                    if (isSetter && pc == 1) {
                        Class<?> pt = m.getParameterTypes()[0];
                        if (pt == long.class || pt == int.class || pt == String.class || pt == byte[].class) {
                            m.setAccessible(true);
                            return m;
                        }
                        continue;
                    }
                }
            } catch (Throwable ignored) {}
        }
        // 兜底: 遍历全部方法按签名(0参/1参标量)匹配
        try {
            for (Method m : cls.getDeclaredMethods()) {
                int pc = m.getParameterCount();
                if (isGetter && pc == 0) {
                    Class<?> rt = m.getReturnType();
                    if (rt == long.class || rt == int.class || rt == String.class || rt == byte[].class) {
                        m.setAccessible(true);
                        return m;
                    }
                }
                if (isSetter && pc == 1) {
                    Class<?> pt = m.getParameterTypes()[0];
                    if (pt == long.class || pt == int.class || pt == String.class || pt == byte[].class) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 获取所有群聊 username(供导出分组/统计) */
    public static List<String> getGroupTalkers() {
        List<String> all = new ArrayList<>();
        try {
            for (String t : getAllTalkers()) {
                if (t != null && (t.endsWith("@chatroom") || t.endsWith("@im.chatroom"))) all.add(t);
            }
        } catch (Throwable ignored) {}
        return all;
    }

    /** 消息实体类定位(对应 zip DexKitAdapter: field_msgId 指纹 + com.tencent.mm.storage.e9 兜底) */
    private static Class<?> findMsgEntity(ClassLoader cl) {
        // 1) 项目已确认的 DexKit 记忆类名
        String remembered = DexKitHelper.getE9ClassName();
        if (remembered != null && !remembered.isEmpty()) {
            try { return XposedHelpers.findClass(remembered, cl); } catch (Throwable ignored) {}
        }
        // 2) zip 语义: 按 "field_msgId" 字符串做类名指纹定位(带 com.tencent.mm.storage 过滤)
        List<String> hits = DexKitHelper.findClassesByString(cl, "field_msgId");
        if (hits != null) {
            for (String n : hits) {
                if (n.startsWith("com.tencent.mm.storage.")) {
                    try { return XposedHelpers.findClass(n, cl); } catch (Throwable ignored) {}
                }
            }
            if (!hits.isEmpty()) {
                try { return XposedHelpers.findClass(hits.get(0), cl); } catch (Throwable ignored) {}
            }
        }
        // 3) 硬编码兜底
        return XposedHelpers.findClass(C_MSG, cl);
    }
}