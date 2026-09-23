package com.leshao.ai.hook.wechat;

import android.os.SystemClock;
import android.util.Log;

import com.leshao.ai.hook.HookEntry;
import com.leshao.ai.hook.dexkit.DexKitAdapter;
import com.leshao.v3.LogWriter;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import de.robv.android.xposed.XposedHelpers;

/**
 * 微信核心存储访问链（整座 AI 助手的地基）。
 * <p>
 * 依据逆向交付文档 §2 / §16.3 实证的访问链：
 * <pre>
 *   b41.h9.d()          → 核心 Hub 单例
 *     ├─ .b()           → b41.e  AccountStorage
 *     │     ├─ .v()     → f9  MsgInfoStorage（接收入口 / 历史消息）
 *     │     ├─ .r()     → j4  RContactStorage（联系人查询）
 *     │     └─ .q()     → q3  ConfigStorage（wxid / 昵称，int key）
 * </pre>
 * 类名由 {@link DexKitAdapter} 按字符串锚点动态定位，方法名（d/b/v/r/q）
 * 在已定位类内用反射查找并以返回类型做双重校验，跨版本稳健。
 * <p>
 * 所有方法均容错：未绑定 / 反射失败时返回 null 或空列表，绝不向微信抛异常。
 */
public final class StorageHub {

    private static final String TAG = "LeshaoAI.StorageHub";

    private static volatile StorageHub instance;

    private final Object lock = new Object();

    /** MsgInfoStorage 实例（f9）。 */
    private volatile Object msgInfoStorage;
    /** RContactStorage 实例（j4）。 */
    private volatile Object rcontactStorage;
    /** ConfigStorage 实例（q3）。 */
    private volatile Object configStorage;

    private volatile boolean bound;
    private volatile String cachedSelfWxid;

    /** 绑定失败后的冷却截止时间(uptimeMs)；0 表示无冷却。
     *  内核未就绪时绑定会持续失败，若每次调用都重跑 DexKit 定位会卡死调用线程(尤其主线程)。 */
    private volatile long bindRetryAfterMs = 0L;
    private static final long BIND_FAIL_COOLDOWN_MS = 10000L;

    private StorageHub() {
    }

    /** 进程级单例。 */
    public static StorageHub get() {
        if (instance == null) {
            synchronized (StorageHub.class) {
                if (instance == null) {
                    instance = new StorageHub();
                }
            }
        }
        return instance;
    }

    /**
     * 绑定存储链（幂等）。
     *
     * @return 绑定成功返回 true（MsgInfoStorage / RContactStorage / ConfigStorage 任一可用）
     */
    public boolean ensureBound() {
        if (bound) {
            return true;
        }
        // 失败冷却：内核未就绪时避免高频重复 DexKit 定位阻塞调用线程
        if (bindRetryAfterMs != 0L && SystemClock.uptimeMillis() < bindRetryAfterMs) {
            return false;
        }
        synchronized (lock) {
            if (bound) {
                return true;
            }
            if (bindRetryAfterMs != 0L && SystemClock.uptimeMillis() < bindRetryAfterMs) {
                return false;
            }
            try {
                bindInternal();
            } catch (Throwable t) {
                Log.w(TAG, "bindInternal 异常: " + t);
            }
            // v1019: v()/r()/q() 独立绑定，任一成功即视为可服务（联系人不依赖 MsgInfoStorage）
            bound = msgInfoStorage != null || rcontactStorage != null || configStorage != null;
            if (bound) {
                bindRetryAfterMs = 0L;
            } else {
                bindRetryAfterMs = SystemClock.uptimeMillis() + BIND_FAIL_COOLDOWN_MS;
            }
            LogWriter.log(TAG, "存储链绑定: msgInfoStorage=" + (msgInfoStorage != null)
                    + " rcontactStorage=" + (rcontactStorage != null)
                    + " configStorage=" + (configStorage != null));
            return bound;
        }
    }

    /** 内核就绪后清除失败冷却，允许立即重试绑定。 */
    public void resetBindingCooldown() {
        bindRetryAfterMs = 0L;
    }

    private void bindInternal() throws Throwable {
        ClassLoader cl = HookEntry.appClassLoader;
        if (cl == null) {
            // 兜底: 从当前 Application 取类加载器
            try {
                Object app = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("android.app.ActivityThread", null),
                        "currentApplication");
                if (app != null) {
                    cl = app.getClass().getClassLoader();
                }
            } catch (Throwable ignored) {
            }
        }
        if (cl == null) {
            LogWriter.log(TAG, "appClassLoader 未就绪，跳过绑定");
            return;
        }
        Class<?> hubClass = DexKitAdapter.findCoreHubClass();
        Class<?> accClass = DexKitAdapter.findAccountStorageClass();
        Class<?> msgClass = DexKitAdapter.findMsgInfoStorageClass();
        Class<?> rcontactClass = DexKitAdapter.findRContactStorageClass();
        LogWriter.log(TAG, "bindInternal: hub=" + cn(hubClass) + " acc=" + cn(accClass)
                + " msg=" + cn(msgClass) + " rcontact=" + cn(rcontactClass));

        // DexKit 结果可能因 bridge 回收而不可用，按 3180 类名兜底
        if (hubClass == null) {
            try { hubClass = XposedHelpers.findClass("b41.h9", cl); } catch (Throwable ignored) {}
        }
        if (accClass == null) {
            try { accClass = XposedHelpers.findClass("b41.e", cl); } catch (Throwable ignored) {}
        }
        if (hubClass == null || accClass == null) {
            LogWriter.log(TAG, "bindInternal: Hub/AccountStorage 定位失败 hub=" + cn(hubClass)
                    + " acc=" + cn(accClass));
            return;
        }

        // 1) b41.h9.d() → Hub 单例（d() 返回自身）
        Object hub = callStaticNoArg(hubClass, "d", hubClass);
        if (hub == null) {
            hub = callStaticNoArg(hubClass, "d", null);
        }

        // 2) hub.b() → AccountStorage；兜底 静态 hub.b()
        Object acc = hub != null ? callNoArgTyped(hub, "b", accClass) : null;
        if (acc == null) {
            acc = callStaticNoArg(hubClass, "b", accClass);
        }
        if (acc == null) {
            LogWriter.log(TAG, "bindInternal: AccountStorage 获取失败 hub=" + (hub != null));
            return;
        }

        // 3) acc.v() → MsgInfoStorage(f9)，独立绑定：失败不再阻断 r()/q()
        try {
            msgInfoStorage = callNoArgTyped(acc, "v", msgClass);
        } catch (Throwable t) {
            Log.w(TAG, "acc.v() 异常: " + t);
        }
        if (msgInfoStorage == null) {
            LogWriter.log(TAG, "bindInternal: acc.v() 失败 (acc=" + cn(acc.getClass()) + ")，继续尝试 r/q");
        }

        // 4) acc.r() → RContactStorage(j4)，独立绑定
        try {
            rcontactStorage = callNoArgTyped(acc, "r", rcontactClass);
        } catch (Throwable t) {
            Log.w(TAG, "acc.r() 异常: " + t);
        }
        if (rcontactStorage == null) {
            try {
                rcontactStorage = callNoArgTyped(acc, "r", null);
            } catch (Throwable t) {
                Log.w(TAG, "acc.r() 二次尝试异常: " + t);
            }
        }
        if (rcontactStorage == null) {
            LogWriter.log(TAG, "bindInternal: acc.r() 失败 (acc=" + cn(acc.getClass()) + ")");
        }

        // 5) acc.q() → ConfigStorage(q3)，无类型校验，独立绑定
        try {
            configStorage = callNoArg(acc, "q");
        } catch (Throwable t) {
            Log.w(TAG, "acc.q() 异常: " + t);
        }
        LogWriter.log(TAG, "bindInternal: msg=" + (msgInfoStorage != null)
                + " rcontact=" + (rcontactStorage != null)
                + " config=" + (configStorage != null));
    }

    private static String cn(Class<?> c) {
        return c == null ? "null" : c.getName();
    }

    /** 自己 wxid（ConfigStorage key 2）。取不到返回 null。 */
    public String selfWxid() {
        if (cachedSelfWxid != null) {
            return cachedSelfWxid;
        }
        if (!ensureBound()) {
            return null;
        }
        try {
            Object v = XposedHelpers.callMethod(configStorage, "v", 2, "");
            if (v instanceof String && !((String) v).isEmpty()) {
                cachedSelfWxid = (String) v;
                return cachedSelfWxid;
            }
        } catch (Throwable t) {
            Log.w(TAG, "selfWxid 失败: " + t);
        }
        return null;
    }

    /** 自己昵称（ConfigStorage key 4）。 */
    public String selfNickname() {
        if (!ensureBound()) {
            return null;
        }
        try {
            Object v = XposedHelpers.callMethod(configStorage, "v", 4, "");
            if (v instanceof String && !((String) v).isEmpty()) {
                return (String) v;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 由自己发出消息的 field_fromUsername 反推并缓存 wxid（文档 §8.4 零依赖兜底）。 */
    public void cacheSelfWxidFrom(String fromUsername) {
        if (cachedSelfWxid == null && fromUsername != null && !fromUsername.isEmpty()) {
            cachedSelfWxid = fromUsername;
        }
    }

    /**
     * 历史消息（文档 §10.1）。
     *
     * @param talker   会话 id
     * @param fromTime 起始时间（毫秒）
     * @param limit    条数上限
     * @return 消息对象列表；失败返回 null
     */
    @SuppressWarnings("unchecked")
    public List<Object> history(String talker, long fromTime, int limit) {
        if (!ensureBound() || talker == null) {
            return null;
        }
        try {
            return (List<Object>) XposedHelpers.callMethod(
                    msgInfoStorage, "H2", talker, fromTime, limit);
        } catch (Throwable t) {
            Log.w(TAG, "history 失败: " + t);
            return null;
        }
    }

    /** 某会话最后一条消息（f9.G7）。 */
    public Object lastMsg(String talker) {
        if (!ensureBound() || talker == null) {
            return null;
        }
        try {
            return XposedHelpers.callMethod(msgInfoStorage, "G7", talker);
        } catch (Throwable t) {
            Log.w(TAG, "lastMsg 失败: " + t);
            return null;
        }
    }

    public Object msgInfoStorage() {
        ensureBound();
        return msgInfoStorage;
    }

    public Object rcontactStorage() {
        ensureBound();
        return rcontactStorage;
    }

    public boolean isBound() {
        return bound;
    }

    // ---------- 反射辅助 ----------

    /** 调用类的无参静态方法，返回类型需匹配 expect（可为 null 跳过校验）。 */
    private static Object callStaticNoArg(Class<?> cls, String name, Class<?> expect) {
        if (cls == null) {
            return null;
        }
        for (Method m : cls.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != 0) {
                continue;
            }
            if (!Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (expect != null && !expect.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            try {
                return m.invoke(null);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 调用对象的无参方法，优先返回类型匹配 expect 的重载。 */
    private static Object callNoArgTyped(Object obj, String name, Class<?> expect) {
        Object fallback = null;
        for (Method m : obj.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != 0) {
                continue;
            }
            try {
                Object ret = m.invoke(obj);
                if (ret == null) {
                    continue;
                }
                if (expect == null || expect.isAssignableFrom(ret.getClass())) {
                    return ret;
                }
                if (fallback == null) {
                    fallback = ret;
                }
            } catch (Throwable ignored) {
            }
        }
        return fallback;
    }

    /** 调用对象的无参方法，返回第一个非 null 结果。 */
    private static Object callNoArg(Object obj, String name) {
        return callNoArgTyped(obj, name, null);
    }
}
