package com.leshao.ai.hook.wechat;

import android.util.Log;

import com.leshao.ai.hook.HookEntry;
import com.leshao.ai.hook.dexkit.DexKitAdapter;

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
     * @return 绑定成功返回 true（至少拿到 MsgInfoStorage 与 ConfigStorage）
     */
    public boolean ensureBound() {
        if (bound) {
            return true;
        }
        synchronized (lock) {
            if (bound) {
                return true;
            }
            try {
                bindInternal();
            } catch (Throwable t) {
                Log.w(TAG, "bindInternal 异常: " + t);
            }
            bound = msgInfoStorage != null && configStorage != null;
            Log.i(TAG, "存储链绑定: msgInfoStorage=" + (msgInfoStorage != null)
                    + " rcontactStorage=" + (rcontactStorage != null)
                    + " configStorage=" + (configStorage != null));
            return bound;
        }
    }

    private void bindInternal() throws Throwable {
        ClassLoader cl = HookEntry.appClassLoader;
        if (cl == null) {
            Log.w(TAG, "appClassLoader 未就绪，跳过绑定");
            return;
        }
        Class<?> hubClass = DexKitAdapter.findCoreHubClass();
        Class<?> accClass = DexKitAdapter.findAccountStorageClass();
        Class<?> msgClass = DexKitAdapter.findMsgInfoStorageClass();
        Class<?> rcontactClass = DexKitAdapter.findRContactStorageClass();
        if (hubClass == null || accClass == null || msgClass == null) {
            Log.w(TAG, "DexKit 定位失败: hub=" + hubClass + " acc=" + accClass
                    + " msg=" + msgClass);
            return;
        }

        // 1) b41.h9.d() → Hub 单例
        Object hub = callStaticNoArg(hubClass, "d", hubClass);
        if (hub == null) {
            Log.w(TAG, "hub.d() 失败");
            return;
        }

        // 2) hub.b() → AccountStorage
        Object acc = callNoArgTyped(hub, "b", accClass);
        if (acc == null) {
            Log.w(TAG, "hub.b() 失败");
            return;
        }

        // 3) acc.v() → MsgInfoStorage(f9)
        msgInfoStorage = callNoArgTyped(acc, "v", msgClass);
        if (msgInfoStorage == null) {
            Log.w(TAG, "acc.v() 失败");
            return;
        }

        // 4) acc.r() → RContactStorage(j4)
        if (rcontactClass != null) {
            rcontactStorage = callNoArgTyped(acc, "r", rcontactClass);
        }

        // 5) acc.q() → ConfigStorage(q3)，无类型校验
        configStorage = callNoArg(acc, "q");
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
