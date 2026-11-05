package com.leshao.v3.hook;

import android.app.Activity;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * v3.0.163：联系人/群成员资料页注入「微信号 / ID」行 —— 严格移植
 * 《微信号id注入WeChatIDInject.zip》（README 目标微信 8.0.78，混淆名已现场核对）。
 *
 * <p>功能（与 zip 完全一致）：</p>
 * <ul>
 *   <li>资料页「微信号」位置下方（或不存在时兜底到 {@code contact_profile_header_normal}）
 *       新增一行：好友有完整身份 → 「微信号：&lt;alias&gt;」，陌生人 → 「ID / wxid：&lt;username&gt;」。</li>
 *   <li>显示值 = {@code IdStore.get(wxid)}（离线库命中）&gt; 当场 {@code field_alias} &gt; {@code field_username}。</li>
 * </ul>
 *
 * <p>三个组成部分（与 zip 的 Entry/Hooks/IdStore 对应）：</p>
 * <ul>
 *   <li><b>方案一（完整化）</b>：hook {@code ContactInfoUI.onResume} 读 {@code n}(im.f2) 的
 *       {@code field_alias/field_username} 落库；hook {@code qn.w}(NetSceneGetChatroomMemberDetail)
 *       {@code onGYNetEnd} → 响应链反射 {@code d.b.a.i(LinkedList<q16>)} → 复用微信自身
 *       {@code scene.I(list)} 全量枚举群成员 wxid → {@code markKnown()}（预热离线库），失败走
 *       {@code fallbackNames} 兜底。</li>
 *   <li><b>方案二（报文 dump）</b>：hook {@code p3}{@code /rn3.s1}{@code /r41.e0} 的
 *       {@code onGYNetEnd} 与 {@code qy3.z7} 构造，就近配对递归挖 {@code field_alias}+{@code field_username}。</li>
 *   <li><b>UI 注入</b>：hook {@code g0.notifyDataSetChanged()} after → {@code g0.i()} 取行 →
 *       克隆 {@code SummaryTextPreference} 字段覆盖 {@code q/h/m} →
 *       {@code g0.f(row, g0.m(锚点)+1)}；锚点优先「微信号/WeChat/ID」行，兜底头像昵称行；
 *       幂等（扫 key）+ ThreadLocal BUSY 防重入。</li>
 * </ul>
 */
public final class WeChatIdInjectHook {

    private static final String TAG = "WeChatIdInject";

    public static final String K_ENABLED = "ls_wxid_inject_enabled";

    /** zip 的 Entry.curProfileUI 对应物：当前资料页 WeakReference。 */
    private static volatile WeakReference<Object> sCurProfileUI = new WeakReference<>(null);

    private static volatile ClassLoader sCl;

    private WeChatIdInjectHook() {}

    // ================================================================
    // 配置（模块主页「更多功能」->「查看微信wxid」开关）
    // ================================================================

    private static SharedPreferences prefs() {
        return ContextManager.getPrefs();
    }

    public static boolean isEnabled() {
        SharedPreferences sp = prefs();
        return sp != null && sp.getBoolean(K_ENABLED, false);
    }

    public static void setEnabled(boolean on) {
        SharedPreferences sp = prefs();
        if (sp != null) sp.edit().putBoolean(K_ENABLED, on).apply();
        LogWriter.log(TAG, "setEnabled " + on);
    }

    public static int size() {
        return sizeInternal();
    }

    // ================================================================
    // 入口（MainHook 注册）
    // ================================================================

    public static void hook(ClassLoader cl) {
        sCl = cl;
        // v3.0.472: 启动时打印开关状态，便于诊断（模块设置 -> 更多功能 -> 查看微信wxid）
        LogWriter.log(TAG, "wxid inject enabled=" + isEnabled()
                + " (开关在: 模块设置->更多功能->查看微信wxid)");
        load();
        installDataHooks(cl);
        installInjectHook(cl);
        LogWriter.log(TAG, "hook installed");
    }

    // ================================================================
    // IdStore（移植 zip IdStore.java，落地离线库）
    // ================================================================

    static final String FILE_ALIAS = "/sdcard/Download/wxid_alias.tsv";
    static final String FILE_KNOWN = "/sdcard/Download/wxid_known.tsv";

    static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    static final java.util.Set<String> KNOWN = ConcurrentHashMap.newKeySet();

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "leshao-wxid-store");
        t.setDaemon(true);
        return t;
    });
    private static volatile boolean loaded = false;

    static void load() {
        if (loaded) return;
        loaded = true;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(FILE_ALIAS))) {
            String ln;
            while ((ln = r.readLine()) != null) {
                int t = ln.indexOf('\t');
                if (t <= 0) continue;
                String u = ln.substring(0, t), a = ln.substring(t + 1);
                if (!u.isEmpty() && isValidAlias(a)) CACHE.put(u, a);   // v3.0.513: 过滤地区污染
            }
        } catch (Throwable ignored) {}
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(FILE_KNOWN))) {
            String ln;
            while ((ln = r.readLine()) != null) {
                String u = ln.trim();
                if (!u.isEmpty()) KNOWN.add(u);
            }
        } catch (Throwable ignored) {}
        LogWriter.log(TAG, "IdStore loaded cache=" + CACHE.size() + " known=" + KNOWN.size());
    }

    static void put(final String user, final String alias) {
        if (user == null || alias == null || user.isEmpty() || alias.isEmpty()) return;
        if (!isValidAlias(alias)) return;          // v3.0.513: 拒绝地区/昵称等非微信号值
        if (alias.equals(CACHE.get(user))) return;
        CACHE.put(user, alias);
        IO.execute(() -> append(FILE_ALIAS, user + "\t" + alias));
    }

    static void markKnown(final String user) {
        if (user == null || user.isEmpty()) return;
        if (KNOWN.add(user)) IO.execute(() -> append(FILE_KNOWN, user));
    }

    private static void append(String path, String line) {
        try (java.io.BufferedWriter w = new java.io.BufferedWriter(
                new java.io.FileWriter(path, true))) {
            w.write(line);
            w.newLine();
        } catch (Throwable ignored) {}
    }

    static String get(String user) {
        return user == null ? null : CACHE.get(user);
    }

    static int sizeInternal() {
        return CACHE.size();
    }

    // ================================================================
    // 数据 hook（移植 zip Hooks.java 方案一 + 方案二）
    // ================================================================

    private static String[] contactIds(Object contact) {
        if (contact == null) return null;
        String u = str(contact, "field_username");
        String a = str(contact, "field_alias");
        if (u == null) u = "";
        if (a == null) a = "";
        // v3.0.469: 字段名可能漂移，用 im.f2 接口方法兜底（O0=alias, r0=username，按需探测）
        if (u.isEmpty() || a.isEmpty()) {
            try {
                for (java.lang.reflect.Method m : contact.getClass().getMethods()) {
                    if (m.getParameterCount() != 0 || m.getReturnType() != String.class) continue;
                    String mn = m.getName();
                    Object v = null;
                    try { v = m.invoke(contact); } catch (Throwable ignored) {}
                    if (v == null) continue;
                    String sv = String.valueOf(v);
                    if (u.isEmpty() && ("Z0".equals(mn) || "r0".equals(mn) || "getUsername".equals(mn))) u = sv;
                    if (a.isEmpty() && ("O0".equals(mn) || "getAlias".equals(mn))) a = sv;
                }
            } catch (Throwable ignored) {}
        }
        return new String[]{u, a};
    }

    private static String str(Object o, String f) {
        try {
            Object v = XposedHelpers.getObjectField(o, f);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 限频诊断日志：每个 key 最多打印 3 次，避免刷屏。 */
    private static final java.util.Set<String> DIAG_KEYS = new java.util.concurrent.ConcurrentHashMap<String, Boolean>().keySet(true);
    private static void diag(String msg) {
        try {
            String key = msg;
            if (DIAG_KEYS.size() >= 50) return;
            if (DIAG_KEYS.add(key)) {
                LogWriter.log(TAG, msg);
            }
        } catch (Throwable ignored) {}
    }

    private static void harvest(Object root) {
        if (root == null) return;
        Object[] pair = dig(root, "field_alias", "field_username", 0, new IdentityHashMap<>());
        if (pair != null) put((String) pair[1], (String) pair[0]);
    }

    private static Object[] dig(Object o, String nA, String nU, int depth,
                                IdentityHashMap<Object, Boolean> seen) {
        if (o == null || depth > 5) return null;
        if (seen.put(o, Boolean.TRUE) != null) return null;
        Class<?> c = o.getClass();
        while (c != null && !c.equals(Object.class)) {
            String la = null, lu = null;
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    if (f.getName().equals(nA)) {
                        Object v = f.get(o);
                        if (v != null) la = String.valueOf(v);
                    }
                    if (f.getName().equals(nU)) {
                        Object v = f.get(o);
                        if (v != null) lu = String.valueOf(v);
                    }
                } catch (Throwable ignored) {}
            }
            if (la != null && lu != null && !la.isEmpty()) return new Object[]{la, lu};
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v == null) continue;
                    if (v instanceof String || v instanceof Number || v instanceof Boolean
                            || v instanceof Character) continue;
                    if (v instanceof Collection) {
                        for (Object e : (Collection<?>) v) {
                            Object[] r = dig(e, nA, nU, depth + 1, seen);
                            if (r != null) return r;
                        }
                    } else if (v instanceof Map) {
                        for (Object e : ((Map<?, ?>) v).values()) {
                            Object[] r = dig(e, nA, nU, depth + 1, seen);
                            if (r != null) return r;
                        }
                    } else if (v instanceof Context || v.getClass().getName().startsWith("android.")) {
                        // skip
                    } else {
                        Object[] r = dig(v, nA, nU, depth + 1, seen);
                        if (r != null) return r;
                    }
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static void installDataHooks(ClassLoader cl) {
        // ① 当场联系人：ContactInfoUI.onResume
        try {
            Class<?> ui = loadClass(cl, "com.tencent.mm.plugin.profile.ui.ContactInfoUI");
            if (ui == null) return;
            for (Method m : ui.getDeclaredMethods()) {
                if (!m.getName().equals("onResume") || m.getParameterCount() != 0) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            sCurProfileUI = new WeakReference<>(p.thisObject);
                            Object contact = XposedHelpers.getObjectField(p.thisObject, "n");
                            String[] id = contactIds(contact);
                            if (id != null && !id[1].isEmpty()) put(id[0], id[1]);
                        } catch (Throwable ignored) {}
                    }
                });
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "ContactInfoUI hook err: " + t);
        }
        // v3.0.467: 兜底——Tinker/类加载时序导致直接 hook 失败时，全局 Activity.onResume
        // 检测到 ContactInfoUI 即设置 sCurProfileUI（资料页打开必然触发）。
        try {
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object thiz = p.thisObject;
                        if (thiz == null) return;
                        String cn = thiz.getClass().getName();
                        if (!"com.tencent.mm.plugin.profile.ui.ContactInfoUI".equals(cn)
                                && !cn.endsWith(".ContactInfoUI")) return;
                        sCurProfileUI = new WeakReference<>(thiz);
                        Object contact = XposedHelpers.getObjectField(thiz, "n");
                        String[] id = contactIds(contact);
                        if (id != null && !id[1].isEmpty()) put(id[0], id[1]);
                        LogWriter.log(TAG, "ContactInfoUI captured via Activity.onResume: " + cn);
                        // v3.0.470: 页面首次渲染在 onResume 之前，g0.notifyDataSetChanged 已错过
                        // sCurProfileUI。这里延迟触发一次 ListView adapter 刷新，让注入执行。
                        // v3.0.513: 改为主线程 postDelayed（后台线程触碰 ListView 会导致白屏/布局错乱）。
                        final Object act = thiz;
                        android.os.Handler h = new android.os.Handler(Looper.getMainLooper());
                        for (final long delay : new long[]{300L, 800L, 1500L, 3000L, 5000L}) {
                            h.postDelayed(() -> {
                                try {
                                    Object lv = XposedHelpers.callMethod(act, "getListView");
                                    if (lv == null) return;
                                    Object adapter = XposedHelpers.callMethod(lv, "getAdapter");
                                    if (adapter == null) return;
                                    XposedHelpers.callMethod(adapter, "notifyDataSetChanged");
                                    LogWriter.log(TAG, "主动触发 adapter 刷新 (delay=" + delay + ")");
                                } catch (Throwable ignored) {}
                            }, delay);
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "Activity.onResume hook err: " + t);
        }

        // onGYNetEnd 签名：（int,int,int,String,y0,byte[]）
        Object sig = new Object[]{int.class, int.class, int.class, String.class,
                loadClass(cl, "com.tencent.mm.network.y0"), byte[].class};

        // ② 方案一完整化：群成员 wxid 枚举（qn.w = NetSceneGetChatroomMemberDetail）
        try {
            Class<?> qn = loadClass(cl, "qn.w");
            if (qn != null) {
                hookOnGYNetEnd(qn, sig, true);
                LogWriter.log(TAG, "hooked qn.w.onGYNetEnd");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "qn.w hook err: " + t);
        }

        // ③ 方案二：verifyuser / searchcontact 响应 dump
        String[] aux = {"com.tencent.mm.pluginsdk.model.p3", "rn3.s1", "r41.e0"};
        for (String cn : aux) {
            try {
                Class<?> c = loadClass(cl, cn);
                if (c != null) hookOnGYNetEnd(c, sig, false);
            } catch (Throwable t) {
                LogWriter.log(TAG, "aux hook err " + cn + ": " + t);
            }
        }

        // ④ 方案二(c)：资料页 widget 持有的联系人（挖 lvbuff/contactExtra 里的 alias）
        try {
            Class<?> qy = loadClass(cl, "qy3.z7");
            if (qy != null) {
                Class<?> ui = loadClass(cl, "com.tencent.mm.plugin.profile.ui.ContactInfoUI");
                Class<?> cc0 = loadClass(cl, "pc5.cc0");
                if (ui != null && cc0 != null) {
                    for (Constructor<?> ctor : qy.getDeclaredConstructors()) {
                        Class<?>[] pts = ctor.getParameterTypes();
                        if (pts.length >= 2 && ui.isAssignableFrom(pts[0])
                                && cc0.isAssignableFrom(pts[pts.length - 1])) {
                            ctor.setAccessible(true);
                            XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam p) {
                                    try {
                                        Object contact = XposedHelpers.getObjectField(p.thisObject, "f");
                                        String[] id = contactIds(contact);
                                        if (id != null && !id[1].isEmpty()) put(id[0], id[1]);
                                    } catch (Throwable ignored) {}
                                }
                            });
                            LogWriter.log(TAG, "hooked qy3.z7 ctor");
                            break;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "qy3.z7 hook err: " + t);
        }
    }

    private static void hookOnGYNetEnd(Class<?> cls, Object sig, boolean enumerate) {
        Method m = null;
        try {
            for (Method mm : cls.getDeclaredMethods()) {
                if (!mm.getName().equals("onGYNetEnd")) continue;
                Class<?>[] pts = mm.getParameterTypes();
                if (pts.length == 6 && pts[4] != null && pts[4].getName().equals("com.tencent.mm.network.y0")) {
                    m = mm;
                    break;
                }
            }
        } catch (Throwable ignored) {}
        if (m == null) return;
        m.setAccessible(true);
        final Method fm = m;
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    // 数据采集始终进行（开关只控制 UI 注入）：离线库越全，资料页展示越准
                    if (enumerate) enumerateMembers(p.thisObject);
                    harvest(p.thisObject);
                    if (p.args != null && p.args.length > 4) harvest(p.args[4]);
                } catch (Throwable ignored) {}
            }
        });
    }

    // ================================================================
    // 群成员 wxid 全量枚举（zip 方案一完整化）
    //   响应链: scene.d(modelbase.o).b(modelbase.n).a(protobuf.f=di3).i(LinkedList<q16>)
    //   复用微信自身解析 scene.I(list): q16 -> q16.d(String wxid)
    // ================================================================

    private static void enumerateMembers(Object scene) {
        try {
            Object o = XposedHelpers.getObjectField(scene, "d");
            Object n = (o == null) ? null : XposedHelpers.getObjectField(o, "b");
            Object rsp = (n == null) ? null : XposedHelpers.getObjectField(n, "a");
            if (rsp == null) return;
            List<?> names = null;
            Object memberList = null;
            try {
                memberList = XposedHelpers.getObjectField(rsp, "i");
            } catch (Throwable ignored) {}
            if (memberList != null) {
                try {
                    names = (List<?>) XposedHelpers.callMethod(scene, "I", memberList);
                } catch (Throwable ignored) {}
            }
            if (names == null || names.isEmpty()) names = fallbackNames(rsp, 0);
            if (names != null) {
                int cnt = 0;
                for (Object s : names) {
                    if (s instanceof String) {
                        String u = (String) s;
                        if (!u.isEmpty()) {
                            markKnown(u);
                            cnt++;
                        }
                    }
                }
                if (cnt > 0) LogWriter.log(TAG, "enumerateMembers found=" + cnt);
            }
        } catch (Throwable ignored) {}
    }

    private static List<String> fallbackNames(Object node, int depth) {
        if (node == null || depth > 4) return null;
        List<String> out = new ArrayList<>();
        if (node instanceof Iterable) {
            for (Object el : (Iterable<?>) node) {
                if (el == null) continue;
                Object dv = null;
                try {
                    dv = XposedHelpers.getObjectField(el, "d");
                } catch (Throwable ignored) {}
                if (dv instanceof String) {
                    out.add((String) dv);
                    continue;
                }
                List<String> sub = fallbackNames(el, depth + 1);
                if (sub != null) out.addAll(sub);
            }
        }
        return out.isEmpty() ? null : out;
    }

    // ================================================================
    // UI 注入（移植 zip Hooks.java installInjectHook + inject）
    // ================================================================

    static final String INJECT_KEY = "inject_wxid_row";
    static final String[] ANCHOR_CATS = {"contact_info_category_1", "contact_info_category_1_openim"};
    static final String ANCHOR_HEADER = "contact_profile_header_normal";
    static final String KVC = "com.tencent.mm.ui.base.preference.KeyValuePreference";
    static final ThreadLocal<Boolean> BUSY = ThreadLocal.withInitial(() -> Boolean.FALSE);
    /** v3.0.514: 突发去重兜底（主幂等已改为 g0.n(INJECT_KEY) 可见性检查）。
     *  页面数据异步加载会重建适配器列表（g0.g() 清空 e/f/h），永久去重会导致注入行被
     *  擦掉后无法重新注入 → 资料页看不到 wxid 行。n() 正常时不写这里。 */
    private static final java.util.concurrent.ConcurrentHashMap<Object, Long> LAST_INJECT_TIME =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long BURST_GUARD_MS = 1000L;

    /** v3.0.513: 微信号/ID 只能是 ASCII（字母/数字/_-）。含中文（地区/昵称）一律拒绝，
     *  防止把「广东」「冰岛」等地区字段当微信号写入缓存/展示。 */
    private static boolean isValidAlias(String s) {
        if (s == null) return false;
        s = s.trim();
        if (s.length() < 2 || s.length() > 64) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 0x7F) return false;          // 非 ASCII：中文/emoji/特殊字符
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.')) return false;
        }
        return true;
    }

    private static void installInjectHook(ClassLoader cl) {
        try {
            Class<?> g0 = loadClass(cl, "com.tencent.mm.ui.base.preference.g0");
            if (g0 == null) {
                LogWriter.log(TAG, "[WARN] g0 not found, UI inject off");
                return;
            }
            for (Method m : g0.getDeclaredMethods()) {
                if (!m.getName().equals("notifyDataSetChanged") || m.getParameterCount() != 0) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (!isEnabled()) {
                                diag("g0 notify: isEnabled=false（开关未开？）");
                                return;
                            }
                            inject(p.thisObject);
                        } catch (Throwable ignored) {}
                    }
                });
            }
            LogWriter.log(TAG, "hooked g0.notifyDataSetChanged");
        } catch (Throwable t) {
            LogWriter.log(TAG, "g0 hook err: " + t);
        }
    }

    @SuppressWarnings("unchecked")
    private static void inject(Object screen) throws Throwable {
        if (Boolean.TRUE.equals(BUSY.get())) return;      // 防自调递归
        BUSY.set(Boolean.TRUE);
        try {
            ClassLoader cl = screen.getClass().getClassLoader();
            List<Object> list = (List<Object>) XposedHelpers.callMethod(screen, "i");
            if (list == null || list.isEmpty()) return;

            // v3.0.514: 权威幂等——行键已在可见列表 h 中（g0.n(key)=h.indexOf(key)）。
            // 页面重建清空适配器后 n() 返回 -1，允许重新注入；不再永久屏蔽同一屏幕。
            try {
                int vis = (Integer) XposedHelpers.callMethod(screen, "n", INJECT_KEY);
                if (vis >= 0) return;
            } catch (Throwable ignored) {}
            for (Object pf : list) {
                if (INJECT_KEY.equals(XposedHelpers.getObjectField(pf, "q"))) return; // 兜底幂等
            }
            // 兜底突发去重：仅当 n() 不可用时，1s 内同一屏幕不重复注入（防渲染突发双行）
            long now = System.currentTimeMillis();
            Long last = LAST_INJECT_TIME.get(screen);
            if (last != null && now - last < BURST_GUARD_MS) return;
            LAST_INJECT_TIME.put(screen, now);
            if (LAST_INJECT_TIME.size() > 200) {   // 简单清理，防地图无限增长
                long cutoff = now - 10_000L;
                LAST_INJECT_TIME.entrySet().removeIf(e -> e.getValue() < cutoff);
            }

            Object activity = sCurProfileUI.get();
            if (activity == null) {
                diag("inject: sCurProfileUI null (ContactInfoUI 未捕获)");
                return;
            }
            // v3.0.514: 防止旧页面引用串数据——页面已销毁时跳过本次注入，
            // 后续 300/800/1500/3000/5000ms 主线程刷新会带上新页面重试。
            try {
                if (activity instanceof android.app.Activity) {
                    android.app.Activity a = (android.app.Activity) activity;
                    if (a.isFinishing() || a.isDestroyed()) {
                        diag("inject: sCurProfileUI 页面已销毁，跳过本次");
                        return;
                    }
                }
            } catch (Throwable ignored) {}
            String[] id = contactIds(XposedHelpers.getObjectField(activity, "n"));
            if (id == null) {
                diag("inject: contactIds null");
                return;
            }
            String wxid = id[0], alias = id[1];
            if (wxid.isEmpty() && alias.isEmpty()) {
                diag("inject: wxid/alias 均为空");
                return;
            }

            String mem = get(wxid);
            if (mem == null || mem.isEmpty()) mem = alias;
            // v3.0.513: 缓存/字段可能被地区污染（广东/冰岛），非 ASCII 一律当无微信号处理
            boolean hasAlias = mem != null && isValidAlias(mem);
            String value = hasAlias ? mem : wxid;
            String title = hasAlias ? "微信号" : "ID";

            // 锚点（zip 现版）：朋友资料分类行（"添加到通讯录"下方）→ 屏幕既有 KeyValue/FriendProfile
            // → 头像行；索引一律用权威 g0.m(key)
            int anchorIdx = -1;
            boolean before = false;
            for (String cat : ANCHOR_CATS) {
                int idx = (Integer) XposedHelpers.callMethod(screen, "m", cat);
                if (idx >= 0 && prefKey(list, cat) != null) {
                    anchorIdx = idx;
                    break;
                }
            }
            if (anchorIdx < 0) {
                for (int i = 0; i < list.size(); i++) {
                    Object pf = list.get(i);
                    String cn = pf.getClass().getName();
                    if (cn.equals(KVC) || cn.contains("FriendProfilePreference")
                            || cn.contains("KeyValuePreference")) {
                        Object ik = XposedHelpers.getObjectField(pf, "q");
                        int ri = (ik == null) ? i
                                : (Integer) XposedHelpers.callMethod(screen, "m", (String) ik);
                        anchorIdx = (ri < 0) ? i : ri;
                        before = true;
                        break;
                    }
                }
            }
            if (anchorIdx < 0) {
                anchorIdx = (Integer) XposedHelpers.callMethod(screen, "m", ANCHOR_HEADER);
            }
            // v3.0.513: 锚点位于列表头/前部时改为在其后插入，绝不插到 0 位（破坏头部布局 → 白屏）
            if (before && anchorIdx <= 0) before = false;
            int at = (anchorIdx < 0) ? 0 : (before ? anchorIdx : anchorIdx + 1);
            if (at <= 0 && list != null && !list.isEmpty()) at = 1;

            // 行对象（zip 现版）：优先克隆屏幕上真实 KeyValuePreference，否则构造
            Object row = buildRow(list, activity, cl);
            if (row == null) return;
            setLine(row, title, value);

            // v3.0.473: 新版 g0(MMPreferenceAdapter) 插入行方法为 d(Preference,int)，旧 f 已删除
            XposedHelpers.callMethod(screen, "d", row, at);
            XposedHelpers.callMethod(screen, "notifyDataSetChanged");
            LogWriter.log(TAG, "inject at=" + at + " class=" + row.getClass().getName()
                    + " title=" + title + " value=" + value);
        } finally {
            BUSY.remove();
        }
    }

    private static Object prefKey(List<Object> list, String key) {
        for (Object pf : list) {
            Object k = XposedHelpers.getObjectField(pf, "q");
            if (key.equals(k)) return pf;
        }
        return null;
    }

    // 只做"标题 | 值"一行：无头像/无名（zip 现版 setLine，双通道落值，显式抑制图标）
    static void setLine(Object row, String title, String value) {
        try { XposedHelpers.setObjectField(row, "Q", title); } catch (Throwable ignored) {}        // KeyValue 标题
        try { XposedHelpers.setObjectField(row, "h", title); } catch (Throwable ignored) {}        // 基类标题(16908310)
        try { XposedHelpers.callMethod(row, "P", (CharSequence) value); } catch (Throwable ignored) {} // 值=摘要(16908304)
        try { XposedHelpers.setObjectField(row, "m", value); } catch (Throwable ignored) {}        // 摘要字段双保险
        try { XposedHelpers.callMethod(row, "J", INJECT_KEY); } catch (Throwable ignored) {}
        try { XposedHelpers.setObjectField(row, "q", INJECT_KEY); } catch (Throwable ignored) {}
        try { XposedHelpers.setObjectField(row, "n", 0); } catch (Throwable ignored) {}            // 无左侧图标
        try { XposedHelpers.setObjectField(row, "p", null); } catch (Throwable ignored) {}
        try { XposedHelpers.setObjectField(row, "X", null); } catch (Throwable ignored) {}          // KeyValue 图标
        try { XposedHelpers.setObjectField(row, "W", null); } catch (Throwable ignored) {}
        try { XposedHelpers.setObjectField(row, "A", 8); } catch (Throwable ignored) {}            // 右侧小图标隐藏
        try { XposedHelpers.setBooleanField(row, "M", true); } catch (Throwable ignored) {}         // 单行
        try { XposedHelpers.setObjectField(row, "r", new android.os.Bundle()); } catch (Throwable ignored) {}
    }

    // 构建行：1) clone 屏幕既有 KeyValuePreference（微信原生渲染，无头像/名） 2) 兜底 newInstance
    @SuppressWarnings("unchecked")
    static Object buildRow(List<Object> list, Object ctx, ClassLoader cl) {
        for (Object pf : list) {
            if (pf != null && KVC.equals(pf.getClass().getName())) {
                Object r = cloneObj(pf, ctx);
                if (r != null) return r;
            }
        }
        try {
            Class<?> kvc = loadClass(cl, KVC);
            if (kvc == null) return null;
            Constructor<?> ctor = kvc.getDeclaredConstructor(Context.class, android.util.AttributeSet.class);
            ctor.setAccessible(true);
            return ctor.newInstance(ctx, null);
        } catch (Throwable t) {
            return null;
        }
    }

    // 反射克隆：同类的 (Context) 或 (Context, AttributeSet) 构造，复制非 final / 非运行时字段
    static Object cloneObj(Object src, Object ctx) {
        try {
            Class<?> cls = src.getClass();
            Object dst;
            try {
                Constructor<?> c = cls.getDeclaredConstructor(Context.class);
                c.setAccessible(true);
                dst = c.newInstance(ctx);
            } catch (Throwable t) {
                Constructor<?> c = cls.getDeclaredConstructor(Context.class, android.util.AttributeSet.class);
                c.setAccessible(true);
                dst = c.newInstance(ctx, null);
            }
            java.util.Set<String> skip = new java.util.HashSet<>(java.util.Arrays.asList(
                    "J", "K", "L", "W", "Y", "z", "Z", "p", "X", "e", "f", "r", "d", "D"));
            Class<?> c = cls;
            while (c != null && !c.equals(Object.class)) {
                for (Field f : c.getDeclaredFields()) {
                    int m = f.getModifiers();
                    if (Modifier.isStatic(m) || Modifier.isFinal(m)) continue;
                    if (skip.contains(f.getName())) continue;
                    try {
                        f.setAccessible(true);
                        Class<?> tp = f.getType();
                        if (tp.isPrimitive() || CharSequence.class.isAssignableFrom(tp) || tp.isEnum()) {
                            f.set(dst, f.get(src));
                        }
                    } catch (Throwable ignored) {}
                }
                c = c.getSuperclass();
            }
            return dst;
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================
    // ClassLoader 工具
    // ================================================================

    private static Class<?> loadClass(ClassLoader cl, String name) {
        if (name == null || cl == null) return null;
        Class<?> c = WxResolver.resolve(cl, name);
        if (c != null) return c;
        for (ClassLoader l : HookUtil.candidateLoaders(cl)) {
            try {
                return l.loadClass(name);
            } catch (Throwable ignored) {}
        }
        try {
            org.luckypray.dexkit.result.ClassData cd = DexKitHelper.findClassByName(cl, name);
            if (cd != null) {
                for (ClassLoader l : HookUtil.candidateLoaders(cl)) {
                    try {
                        return l.loadClass(cd.getName());
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Class<?> findOrNull(ClassLoader cl, String name) {
        return loadClass(cl, name);
    }
}