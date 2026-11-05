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
import com.leshao.v3.hook.WxResolver;

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
        LogWriter.log(TAG, "bindInternal: j1 服务链未命中, 回退 l41.h9 旧链");

        // v3.0.577 兜底链: l41.h9.d() → l41.e → v/r/q
        // 【关键修复】DexKitAdapter 返回的 Class 对象可能来自 base.apk 平行 CL(PathClassLoader),
        // 而微信运行时由 Tinker 的 DelegateLastClassLoader 加载真实类。在平行副本 CL 上取得的
        // l41.h9/l41.e 其静态单例(pp0.i1.m 内核、h9.b) 从未被微信初始化 → v()/r() 抛
        // "Kernel not initialized by MMApplication!" → MsgInfoStorage/RContactStorage 获取失败。
        // 因此必须按类名在真实 CL(Tinker) 上重新解析所有 Class 对象(此前只在 null 时才重载)。
        Class<?> hubClass = resolveOn(cl, DexKitAdapter.findCoreHubClass(), "l41.h9");
        Class<?> accClass = resolveOn(cl, DexKitAdapter.findAccountStorageClass(), "l41.e");
        // MsgInfoStorage 接口=ro3.m0(f9 implements), RContactStorage 接口=d8(j4 implements)。
        // 用接口作为 expect 才能跨 CL 用类名兜底命中(实现类 f9/j4 在双 CL 下 isAssignableFrom 会失效)。
        Class<?> msgClass = resolveInterfaceOr(cl, "ro3.m0", DexKitAdapter.findMsgInfoStorageClass());
        Class<?> rcontactClass = resolveInterfaceOr(cl, "com.tencent.mm.storage.d8",
                DexKitAdapter.findRContactStorageClass());
        LogWriter.log(TAG, "bindInternal: hub=" + cn(hubClass) + " acc=" + cn(accClass)
                + " msg=" + cn(msgClass) + " rcontact=" + cn(rcontactClass)
                + " (cl=" + cl.getClass().getSimpleName() + ")");

        if (hubClass == null || accClass == null) {
            LogWriter.log(TAG, "bindInternal: Hub/AccountStorage 定位失败 hub=" + cn(hubClass)
                    + " acc=" + cn(accClass));
            return;
        }

        // 1) l41.h9.d() → Hub 单例（d() 返回自身）
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
            // v3.0.577: 8.0.79 真实服务键 = po3.c4（h2 implements c4，已 jadx 反编译确认）。
            // 旧版兼容 tn3.c4/sh3.c4。此前误用 MsgInfoStorage 实现类 f9 作服务键，
            // 而微信注册的是接口 c4，pp0.i1.v(f9) 必然返回 null → "服务实例仍 null"。
            for (String cn : new String[]{"po3.c4", "tn3.c4", "sh3.c4"}) {
                try {
                    c4 = XposedHelpers.findClass(cn, cl);
                    if (c4 != null) {
                        LogWriter.log(TAG, "bindViaServiceLocator: 服务键=" + cn);
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (c4 == null) {
                // 最后兜底: 用存储接口(旧版 f9 直接注册)作键
                c4 = findMsgInfoStorageClass(cl);
                if (c4 != null) {
                    LogWriter.log(TAG, "bindViaServiceLocator: 回退存储接口作服务键: " + c4.getName());
                } else {
                    LogWriter.log(TAG, "bindViaServiceLocator: 服务接口(c4/f9) 未定位");
                    return false;
                }
            }
            Object svc = null;
            // v3.0.464: 8.0.79 ServiceManager=pp0.i1，取服务方法为 v(Class)（返回 up0.a 中转），
            // 旧 c/s 仅作兼容。
            for (String mn : new String[]{"v", "c", "s"}) {
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

            // v3.0.577: 8.0.79 svc = h2 (implements po3.c4, jadx 反编译确认)。
            // h2 直接暴露存储 getter（不再需要旧的 lj()/cj() IM 中转）：
            //   h2.Jj() → f9  MsgInfoStorage (implements ro3.m0)
            //   h2.Aj() → j4  RContactStorage (implements d8)
            Class<?> msgIface = resolveInterfaceOr(cl, "ro3.m0", findMsgInfoStorageClass(cl));
            Class<?> rcIface = resolveInterfaceOr(cl, "com.tencent.mm.storage.d8", findRContactStorageClass(cl));
            // ① MsgInfoStorage
            msgInfoStorage = getterByPrefs(svc, msgIface, new String[]{"Jj", "v", "lj", "j2", "j3"});
            if (msgInfoStorage == null) {
                msgInfoStorage = firstGetter(svc, msgIface, "Jj");
            }
            if (msgInfoStorage == null) {
                LogWriter.log(TAG, "bindViaServiceLocator: MsgInfoStorage getter 未命中 svc="
                        + svc.getClass().getName());
                dumpAccMethods(svc, msgIface);
            }
            // ② RContactStorage
            rcontactStorage = getterByPrefs(svc, rcIface, new String[]{"Aj", "r", "cj", "ij"});
            if (rcontactStorage == null) {
                rcontactStorage = firstGetter(svc, rcIface, "Aj");
            }
            // ③ NetSceneQueue = j1.q() 实例 .b
            netSceneQueue = readNetSceneQueue(j1);
            // ④ ConfigStorage(q3/m3) = selfWxid/selfNickname 取数源（尽力而为, 失败走 prefs 兜底）。
            if (configStorage == null) {
                configStorage = readConfigStorage(svc, cl);
            }
            LogWriter.log(TAG, "bindViaServiceLocator: msg=" + (msgInfoStorage != null)
                    + " rcontact=" + (rcontactStorage != null)
                    + " queue=" + (netSceneQueue != null)
                    + " config=" + (configStorage != null));
            return msgInfoStorage != null || rcontactStorage != null;
        } catch (Throwable t) {
            LogWriter.log(TAG, "bindViaServiceLocator err: " + t);
            return false;
        }
    }

    /**
     * v1099 补绑 ConfigStorage(q3)：selfWxid(key2)/selfNickname(key4) 的取数源。
     * 优先取服务实例直接暴露的 ConfigStorage, 否则经 AccountStorage(l41.e).q() 兜底。
     * 失败返回 null, 由 selfWxid 的 y1.u / SharedPreferences 兜底接管。
     */
    private static Object readConfigStorage(Object svc, ClassLoader cl) {
        Class<?> q3 = resolveOn(cl, null, "com.tencent.mm.storage.q3");
        // a) 服务实例直接暴露 ConfigStorage
        if (q3 != null && svc != null) {
            Object c = firstGetter(svc, q3, null);
            if (c != null) {
                LogWriter.log(TAG, "readConfigStorage: 命中 " + svc.getClass().getName());
                return c;
            }
        }
        // b) 经 AccountStorage(l41.e).q()
        Class<?> accCls = resolveOn(cl, null, "l41.e");
        if (accCls == null) {
            accCls = resolveOn(cl, DexKitAdapter.findAccountStorageClass(), null);
        }
        Object acc = (accCls != null && svc != null) ? firstGetter(svc, accCls, null) : null;
        if (acc == null) {
            Class<?> hubCls = resolveOn(cl, null, "l41.h9");
            if (hubCls == null) {
                hubCls = resolveOn(cl, DexKitAdapter.findCoreHubClass(), null);
            }
            if (hubCls != null) {
                Object hub = callStaticNoArg(hubCls, "d", hubCls);
                if (hub == null) {
                    hub = callStaticNoArg(hubCls, "d", null);
                }
                if (hub != null) {
                    acc = callNoArgTyped(hub, "b", accCls);
                }
                if (acc == null) {
                    acc = callStaticNoArg(hubCls, "b", accCls);
                }
            }
        }
        if (acc != null) {
            Object c = (q3 != null) ? firstGetter(acc, q3, "q") : null;
            if (c == null) {
                c = callNoArg(acc, "q");
            }
            if (c != null) {
                LogWriter.log(TAG, "readConfigStorage: 经 AccountStorage 命中 "
                        + c.getClass().getName());
            }
            return c;
        }
        return null;
    }

    /** 将类名按目标 CL 重新解析(规避 Tinker 双 CL 下的 Class 对象不匹配), 失败回退原 Class。 */
    private static Class<?> resolveOn(ClassLoader cl, Class<?> cls, String fallbackName) {        if (cls != null && cl != null) {
            try {
                return XposedHelpers.findClass(cls.getName(), cl);
            } catch (Throwable ignored) {
            }
            return cls;
        }
        if (fallbackName != null && cl != null) {
            try {
                return XposedHelpers.findClass(fallbackName, cl);
            } catch (Throwable ignored) {
            }
        }
        return cls;
    }

    /**
     * 优先按接口类名在目标 CL 上解析(接口跨 CL 用类名匹配更稳), 失败回退 DexKit 返回的 Class。
     * v3.0.577: MsgInfoStorage 接口=ro3.m0, RContactStorage 接口=d8。
     */
    private static Class<?> resolveInterfaceOr(ClassLoader cl, String ifaceName, Class<?> dexkitCls) {
        if (cl != null && ifaceName != null) {
            try {
                return XposedHelpers.findClass(ifaceName, cl);
            } catch (Throwable ignored) {
            }
        }
        return dexkitCls;
    }

    /** 定位 j1 服务定位类：优先 DexKit 扫描结果, 兜底类名 pp0.i1(8.0.79)/gp0.j1(旧版)。 */
    private static Class<?> serviceLocatorClass(ClassLoader cl) {
        String dk = DexKitHelper.getJ1ServiceClass();
        if (dk != null && !dk.isEmpty()) {
            try {
                return XposedHelpers.findClass(dk, cl);
            } catch (Throwable ignored) {
            }
        }
        for (String cn : new String[]{"pp0.i1", "gp0.j1", "gp0.j1.j"}) {
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
            return WxResolver.get("storage_f9", cl);
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
        // 兜底: b41.y1 在 8.0.79 已删除，直接读 SharedPreferences 登录 wxid
        String p = readWxidFromPrefs();
        if (p != null && !p.isEmpty()) {
            cachedSelfWxid = p;
            LogWriter.log(TAG, "selfWxid 兜底(prefs)=" + p);
            return p;
        }
        return null;
    }

    /** 兜底已废弃：b41.y1 在 8.0.79 中不存在，selfWxid 统一走 SharedPreferences。 */
    private String readSelfWxidFromAccountUtil() {
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
        if (netSceneQueue == null) {
            upgradeToJ1Queue();
        }
        return netSceneQueue;
    }

    /**
     * v1044: 旧链(l41.h9)绑定成功但缺 NetSceneQueue 时, 内核就绪后尝试 j1 服务链补绑。
     * 发送链路 v51.r0 入队强依赖 queue; 早期 installCore 时 j1 未 ready 会走旧链,
     * 旧链拿不到 NetSceneQueue → 文字发送入队必然失败(「语音能发文字不发」)。
     * 这里在每次取 queue 时兜底一次, 内核就绪即可升级。
     */
    private void upgradeToJ1Queue() {
        synchronized (lock) {
            if (netSceneQueue != null) {
                return;
            }
            ClassLoader cl = HookEntry.appClassLoader;
            if (cl == null) {
                return;
            }
            try {
                ClassLoader tk = com.leshao.v3.hook.VersionCompat.findTinkerClassLoader(cl);
                if (tk != null && !tk.getClass().getName().contains("Leshao") && tk != cl) {
                    cl = tk;
                }
            } catch (Throwable ignored) {
            }
            try {
                Class<?> j1 = serviceLocatorClass(cl);
                if (j1 == null) {
                    return;
                }
                Object q = readNetSceneQueue(j1);
                if (q != null) {
                    netSceneQueue = q;
                    LogWriter.log(TAG, "upgradeToJ1Queue OK: " + q.getClass().getName());
                } else {
                    LogWriter.log(TAG, "upgradeToJ1Queue: j1.q().b 仍不可用(内核未就绪?)");
                }
            } catch (Throwable t) {
                Log.w(TAG, "upgradeToJ1Queue err: " + t);
            }
        }
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
                if (expect == null || isTypeMatch(expect, ret.getClass())) {
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
     * 类型匹配：兼容不同 ClassLoader 的同名类（Tinker/主 dex 双加载导致 isAssignableFrom 误判）。
     * 先做标准 assignable 判断；失败再用类名（含父类/接口）比较兜底。
     */
    private static boolean isTypeMatch(Class<?> expect, Class<?> actual) {
        if (expect == null || actual == null) {
            return false;
        }
        if (expect.isAssignableFrom(actual) || actual.isAssignableFrom(expect)) {
            return true;
        }
        // 跨 ClassLoader：类名一致（自身/父类/实现的接口）
        String expName = expect.getName();
        Class<?> a = actual;
        while (a != null && a != Object.class) {
            if (expName.equals(a.getName())) return true;
            a = a.getSuperclass();
        }
        for (Class<?> iface : actual.getInterfaces()) {
            if (expName.equals(iface.getName())) return true;
        }
        Class<?> e = expect;
        while (e != null && e != Object.class) {
            if (e.getName().equals(actual.getName())) return true;
            e = e.getSuperclass();
        }
        return false;
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
                if (ret != null && isTypeMatch(expect, ret.getClass())) {
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
