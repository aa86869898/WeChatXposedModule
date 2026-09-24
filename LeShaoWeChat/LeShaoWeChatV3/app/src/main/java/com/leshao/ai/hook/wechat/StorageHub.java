package com.leshao.ai.hook.wechat;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.leshao.ai.hook.HookEntry;
import com.leshao.ai.hook.dexkit.DexKitAdapter;
import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.DexKitHelper;

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
    /** NetSceneQueue（gp0.y.b = modelbase.r1），发送入队用。 */
    private volatile Object netSceneQueue;

    private volatile boolean bound;
    private volatile String cachedSelfWxid;

    /** 微信 Application Context（selfWxid 的 SharedPreferences 兜底用）。 */
    private static volatile Context appContext;

    /** 绑定失败后的冷却截止时间(uptimeMs)；0 表示无冷却。
     *  内核未就绪时绑定会持续失败，若每次调用都重跑 DexKit 定位会卡死调用线程(尤其主线程)。 */
    private volatile long bindRetryAfterMs = 0L;
    private static final long BIND_FAIL_COOLDOWN_MS = 10000L;

    /** 注入微信 Application Context（selfWxid 的 SharedPreferences 兜底用）。 */
    public static void setAppContext(Context ctx) {
        if (ctx != null) {
            appContext = ctx.getApplicationContext();
        }
    }

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
        // v1042: 微信经 Tinker 热修复时, 真实存储类(b41.e/b41.h9/f9/j4/q3)由
        // DelegateLastClassLoader 加载, HookEntry.appClassLoader 只是 base.apk 平行副本,
        // 其上取得的 hub/acc 静态单例与运行时完全隔离 → 绑定必然失败。
        // 改用 VersionCompat.findTinkerClassLoader 解析真实运行时 CL。
        try {
            ClassLoader tk = com.leshao.v3.hook.VersionCompat.findTinkerClassLoader(cl);
            if (tk != null && !tk.getClass().getName().contains("Leshao")
                    && tk != cl) {
                LogWriter.log(TAG, "bindInternal: 使用 Tinker 真实 CL " + tk.getClass().getSimpleName());
                cl = tk;
            }
        } catch (Throwable ignored) {
        }

        // v1043 主链: j1.v(tn3.c4) 服务定位 (文档实证, Tinker 下稳定)
        //   ((c4) j1.v(c4.class)).lj() → f9 MsgInfoStorage
        //   c4.cj()/ij()              → j4 RContactStorage
        //   j1.q() 实例 .b           → gp0.y.b = modelbase.r1 NetSceneQueue
        if (bindViaServiceLocator(cl)) {
            return;
        }
        LogWriter.log(TAG, "bindInternal: j1 服务链未命中, 回退 b41.h9 旧链");

        // v1042 兜底链: b41.h9.d() → b41.e → v/r/q
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

        // 3) acc → MsgInfoStorage(f9)。混淆方法名("v")跨版本会变, 优先已知名, 失败按返回类型兜底扫描。
        msgInfoStorage = firstGetter(acc, msgClass, "v");
        if (msgInfoStorage == null) {
            LogWriter.log(TAG, "bindInternal: MsgInfoStorage 获取失败 (acc=" + cn(acc.getClass()) + ")");
            dumpAccMethods(acc, msgClass);
        }

        // 4) acc → RContactStorage(j4)。同上, 方法名("r")不保证, 按类型扫描。
        rcontactStorage = firstGetter(acc, rcontactClass, "r");
        if (rcontactStorage == null) {
            LogWriter.log(TAG, "bindInternal: RContactStorage 获取失败 (acc=" + cn(acc.getClass()) + ")");
        }

        // 5) acc → ConfigStorage(q3)(尽力而为; 失败时 selfWxid 走 SharedPreferences 兜底)
        configStorage = callNoArg(acc, "q");
        LogWriter.log(TAG, "bindInternal: msg=" + (msgInfoStorage != null)
                + " rcontact=" + (rcontactStorage != null)
                + " config=" + (configStorage != null));
    }

    /**
     * v1043 主链：经 j1 服务定位器绑定存储（文档实证，Tinker 下稳定）。
     * <pre>
     *   j1.v(tn3.c4)  → h2 服务实例（com.tencent.mm.plugin.messenger.foundation.h2）
     *     ├─ .lj()    → f9  MsgInfoStorage
     *     ├─ .cj()/.ij() → j4 RContactStorage
     *     └─ j1.q().b → gp0.y.b = modelbase.r1 NetSceneQueue
     * </pre>
     *
     * @return 任一存储绑定成功
     */
    private boolean bindViaServiceLocator(ClassLoader cl) {
        try {
            Class<?> j1 = serviceLocatorClass(cl);
            if (j1 == null) {
                LogWriter.log(TAG, "bindViaServiceLocator: j1 未定位");
                return false;
            }
            Class<?> c4 = null;
            for (String cn : new String[]{"tn3.c4", "sh3.c4"}) {
                try {
                    c4 = XposedHelpers.findClass(cn, cl);
                    break;
                } catch (Throwable ignored) {
                }
            }
            if (c4 == null) {
                LogWriter.log(TAG, "bindViaServiceLocator: IM 接口(tn3.c4) 未定位");
                return false;
            }
            Object svc = null;
            for (String mn : new String[]{"v", "s"}) {
                try {
                    svc = XposedHelpers.callStaticMethod(j1, mn, c4);
                    if (svc != null) {
                        LogWriter.log(TAG, "bindViaServiceLocator: j1." + mn + "(" + c4.getName()
                                + ") OK class=" + svc.getClass().getName());
                        break;
                    }
                } catch (Throwable t) {
                    String m = String.valueOf(t);
                    if (m.contains("Kernel not initialized")) {
                        LogWriter.log(TAG, "bindViaServiceLocator: 内核未就绪, 等重试");
                    }
                }
            }
            if (svc == null) {
                LogWriter.log(TAG, "bindViaServiceLocator: 服务实例仍 null");
                return false;
            }

            // ① MsgInfoStorage = svc.lj()
            Class<?> f9 = findMsgInfoStorageClass(cl);
            msgInfoStorage = getterByPrefs(svc, f9, new String[]{"lj", "j2", "j3"});
            if (msgInfoStorage == null) {
                msgInfoStorage = firstGetter(svc, f9, "lj");
            }
            // ② RContactStorage = svc.cj()/ij()
            Class<?> j4 = findRContactStorageClass(cl);
            rcontactStorage = getterByPrefs(svc, j4, new String[]{"cj", "ij"});
            if (rcontactStorage == null) {
                rcontactStorage = firstGetter(svc, j4, "cj");
            }
            // ③ NetSceneQueue = j1.q() 实例 .b
            netSceneQueue = readNetSceneQueue(j1);
            LogWriter.log(TAG, "bindViaServiceLocator: msg=" + (msgInfoStorage != null)
                    + " rcontact=" + (rcontactStorage != null)
                    + " queue=" + (netSceneQueue != null));
            return msgInfoStorage != null || rcontactStorage != null;
        } catch (Throwable t) {
            LogWriter.log(TAG, "bindViaServiceLocator err: " + t);
            return false;
        }
    }

    /** 定位 j1 服务定位类：优先 DexKit 扫描结果, 兜底类名 gp0.j1。 */
    private static Class<?> serviceLocatorClass(ClassLoader cl) {
        String dk = DexKitHelper.getJ1ServiceClass();
        if (dk != null && !dk.isEmpty()) {
            try {
                return XposedHelpers.findClass(dk, cl);
            } catch (Throwable ignored) {
            }
        }
        for (String cn : new String[]{"gp0.j1", "gp0.j1.j"}) {
            try {
                return XposedHelpers.findClass(cn, cl);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 定位 f9 MsgInfoStorage 类：DexKit 优先, 兜底类名。 */
    private static Class<?> findMsgInfoStorageClass(ClassLoader cl) {
        Class<?> c = DexKitAdapter.findMsgInfoStorageClass();
        if (c != null) {
            return c;
        }
        try {
            return XposedHelpers.findClass("com.tencent.mm.storage.f9", cl);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 定位 j4 RContactStorage 类：DexKit 优先, 兜底类名。 */
    private static Class<?> findRContactStorageClass(ClassLoader cl) {
        Class<?> c = DexKitAdapter.findRContactStorageClass();
        if (c != null) {
            return c;
        }
        for (String cn : new String[]{"com.tencent.mm.storage.j4", "com.tencent.mm.storage.d8"}) {
            try {
                return XposedHelpers.findClass(cn, cl);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 按候选方法名快取，返回类型需与 expect 兼容。 */
    private static Object getterByPrefs(Object obj, Class<?> expect, String[] prefs) {
        if (obj == null) {
            return null;
        }
        for (String n : prefs) {
            Object v = callNoArgTyped(obj, n, expect);
            if (v != null) {
                LogWriter.log(TAG, "getterByPrefs 命中 " + obj.getClass().getName()
                        + "." + n + "() -> " + v.getClass().getName());
                return v;
            }
        }
        return null;
    }

    /** j1.q() → gp0.y 实例 → 字段 b = NetSceneQueue(modelbase.r1)。 */
    private static Object readNetSceneQueue(Class<?> j1) {
        for (Method m : allMethods(j1)) {
            if (!m.getName().equals("q") || m.getParameterCount() != 0
                    || !Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            try {
                Object y = m.invoke(null);
                if (y != null) {
                    try {
                        Object q = XposedHelpers.getObjectField(y, "b");
                        if (q != null) {
                            LogWriter.log(TAG, "readNetSceneQueue OK: " + q.getClass().getName());
                            return q;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String cn(Class<?> c) {
        return c == null ? "null" : c.getName();
    }

    /** 诊断 dump：列出 acc 上 0/1 参数方法的签名，用于定位真实 getter 方法名或类型。 */
    private static void dumpAccMethods(Object acc, Class<?> expect) {
        try {
            StringBuilder sb = new StringBuilder("acc 方法签名(")
                    .append(acc.getClass().getName()).append(") 目标=").append(cn(expect)).append(":");
            for (Method m : acc.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() > 1) {
                    continue;
                }
                sb.append("\n  ").append(m.getName()).append('(')
                        .append(m.getParameterCount()).append(")->")
                        .append(m.getReturnType() == null ? "void" : m.getReturnType().getName())
                        .append(" final=").append(Modifier.isFinal(m.getModifiers()));
                if (sb.length() > 8000) {
                    break;
                }
            }
            LogWriter.log(TAG, sb.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 自己 wxid（ConfigStorage key 2；存储链不可用时走 SharedPreferences 兜底）。取不到返回 null。 */
    public String selfWxid() {
        if (cachedSelfWxid != null) {
            return cachedSelfWxid;
        }
        if (ensureBound() && configStorage != null) {
            try {
                Object v = XposedHelpers.callMethod(configStorage, "v", 2, "");
                if (v instanceof String && !((String) v).isEmpty()) {
                    cachedSelfWxid = (String) v;
                    return cachedSelfWxid;
                }
            } catch (Throwable t) {
                Log.w(TAG, "selfWxid 失败: " + t);
            }
        }
        // 兜底: 存储链不可用/取不到时, 从微信 SharedPreferences 读登录 wxid
        String p = readWxidFromPrefs();
        if (p != null && !p.isEmpty()) {
            cachedSelfWxid = p;
            LogWriter.log(TAG, "selfWxid 兜底(prefs)=" + p);
            return p;
        }
        return null;
    }

    /** 自己昵称（ConfigStorage key 4）。 */
    public String selfNickname() {
        if (!ensureBound() || configStorage == null) {
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

    /** NetSceneQueue（modelbase.r1），发送入队用。 */
    public Object netSceneQueue() {
        ensureBound();
        return netSceneQueue;
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
        for (Method m : allMethods(cls)) {
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
        for (Method m : allMethods(obj.getClass())) {
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

    /**
     * 枚举类的全部方法（含非 public）。
     * 微信 R8 混淆会把内部 getter(如 b41.e 的 v/r/q、b41.h9 的 d/b) 降为 package-private/private,
     * Class.getMethods() 只返回 public 会全部漏掉, 故需遍历 getDeclaredMethods() 并沿父类链向上合并。
     */
    private static java.util.List<Method> allMethods(Class<?> cls) {
        java.util.LinkedHashSet<Method> set = new java.util.LinkedHashSet<>();
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                try {
                    m.setAccessible(true);
                } catch (Throwable ignored) {
                }
                set.add(m);
            }
            c = c.getSuperclass();
        }
        for (Method m : cls.getMethods()) {
            set.add(m);
        }
        return new java.util.ArrayList<>(set);
    }

    /** 调用对象的无参方法，返回第一个非 null 结果。 */
    private static Object callNoArg(Object obj, String name) {
        return callNoArgTyped(obj, name, null);
    }

    /**
     * 从 AccountStorage 取指定类型存储实例。
     * 微信混淆器会跨版本重命名方法(如 b41.e 的 v/r/q), 故先按已知名快取,
     * 失败后按【返回类型 / 实例类型】扫描全部无参方法兜底, 彻底摆脱对方法名的依赖。
     * <p>
     * 3180 实测(v1035 dump): b41.e.v() 返回类型是<b>接口 vn3.m0</b>(MsgInfoStorage 接口,
     * 实现类=com.tencent.mm.storage.f9), 而非 f9 实体类本身——因此返回类型匹配必须同时接受
     * 「接口」与「实现类」两种形态(审计 WeChat_f9_Bb_ReceivePath_Audit.md §1/§2)。
     *
     * @param obj            AccountStorage 实例
     * @param expect         目标存储类型(MsgInfoStorage f9 / RContactStorage d8 接口)
     * @param preferredName  该版本已知的方法名(可空)
     */
    private static Object firstGetter(Object obj, Class<?> expect, String preferredName) {
        if (obj == null || expect == null) {
            return null;
        }
        // 快路径: 已知方法名
        Object v = callNoArgTyped(obj, preferredName, expect);
        if (v != null) {
            return v;
        }
        // 兜底: 遍历所有无参方法, 返回类型与 expect 相关且实际实例匹配即命中
        for (Method m : allMethods(obj.getClass())) {
            if (m.getParameterCount() != 0) {
                continue;
            }
            Class<?> rt = m.getReturnType();
            // 双向匹配: rt 是接口时 expect 实现它; expect 是接口时 rt 实现它; 或同为超/子类
            if (!isReturnCompatible(rt, expect)) {
                continue;
            }
            try {
                Object ret = m.invoke(obj);
                if (ret != null && expect.isInstance(ret)) {
                    LogWriter.log(TAG, "firstGetter 命中 " + obj.getClass().getName()
                            + "." + m.getName() + "() -> " + m.getReturnType().getName());
                    return ret;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 返回类型与目标存储类型的兼容性: 接口↔实现类 / 超类↔子类 双向匹配。 */
    private static boolean isReturnCompatible(Class<?> rt, Class<?> expect) {
        if (rt == null || expect == null) {
            return false;
        }
        // 直接双向 isAssignableFrom 覆盖: 同类型 / 子类→父类 / 接口→实现
        if (rt.isAssignableFrom(expect) || expect.isAssignableFrom(rt)) {
            return true;
        }
        // rt 为接口: 期望实现类实现该接口则兼容(如 vn3.m0 接口 vs f9 实现)
        if (rt.isInterface()) {
            Class<?> exp = expect;
            while (exp != null) {
                if (rt.isAssignableFrom(exp)) {
                    return true;
                }
                exp = exp.getSuperclass();
            }
            for (Class<?> iface : expect.getInterfaces()) {
                if (rt.isAssignableFrom(iface)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 兜底: 从微信 SharedPreferences 读登录 wxid(不依赖存储链/方法名, 与 TtsVoiceSender 同款)。 */
    private String readWxidFromPrefs() {
        Context ctx = appContext;
        if (ctx == null) {
            return null;
        }
        String[] prefNames = {
                "system_config_prefs", "com.tencent.mm_preferences",
                "notify_sync_pref", "auth_info_key_prefs",
                "app_brand_global_sp", "exdevice_pref",
        };
        String[] keyNames = {
                "login_weixin_username", "login_user_name", "last_login_username",
                "auth_uin", "username", "uin", "_auth_uin",
        };
        for (String pn : prefNames) {
            try {
                java.util.Map<String, ?> all = ctx.getSharedPreferences(pn, 0).getAll();
                for (String key : keyNames) {
                    Object v = all.get(key);
                    if (v != null && v.toString().startsWith("wxid_")) {
                        return v.toString();
                    }
                }
                for (java.util.Map.Entry<String, ?> e : all.entrySet()) {
                    Object v = e.getValue();
                    if (v != null) {
                        String val = v.toString();
                        if (val.startsWith("wxid_") && !val.contains("@")) {
                            return val;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
