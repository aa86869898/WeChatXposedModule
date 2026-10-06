package com.leshao.v3.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextMenu;
import android.view.View;
import android.widget.LinearLayout;

import com.leshao.v3.ContextManager;
import com.leshao.v3.ContactRepository;
import com.leshao.v3.LogWriter;
import com.leshao.v3.MainHook;
import com.leshao.v3.model.ContactCard;
import com.leshao.v3.ui.CandyUi;
import com.leshao.v3.ui.ContactPickerDialog;
import com.leshao.v3.ui.InsetsUtil;
import com.leshao.v3.ui.WindowLayer;
import com.leshao.v3.ui.widgets.M3Page;
import com.leshao.v3.ui.widgets.ModernButton;
import com.leshao.v3.ui.widgets.ModernTopBar;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.luckypray.dexkit.result.ClassData;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 朋友圈长按菜单「秒集赞」—— 本地伪造 LikeUserList，立即显示指定数量的点赞。
 *
 * <p>依据《01_朋友圈长按菜单伪集赞-完整逆向分析.md》《02_二次审核-修订与补全.md》
 * 《03_第三轮深挖-数据链路与缓存机制.md》：</p>
 *
 * <ul>
 *   <li>注入点：{@code eu5.s0.h/g/j(...)}（MMPopupMenu 注册）before 包装第 2/3 参
 *       {@code View.OnCreateContextMenuListener}，在菜单末尾追加「秒集赞」。</li>
 *   <li>点击拦截：{@code com.tencent.mm.plugin.sns.ui.listener.c}（TimeLineMMMenuItemSelectedListener）
 *       与 {@code com.tencent.mm.plugin.sns.ui.k1} 的 {@code onMMMenuItemSelected}。</li>
 *   <li>数据链：{@code l1.b(localId)} 取 SnsInfo → {@code SnsObject.parseFrom(field_attrBuf)}
 *       （实例方法，必须接收返回值）→ LikeUserList.addFirst(伪造 uf6) → LikeCount/LikeUserListCount
 *       同步 → {@code toByteArray()} → <b>必须</b>调 {@code setAttrBuf()}（同时失效 contentByteMd5 缓存）
 *       → {@code p4.Jj().w4()} 落库 → SnsTimelineRefreshEvent 刷新。</li>
 *   <li>伪造项字段：d=wxid、e=昵称、g=5（陌生人赞）、i/m=当前时间、h/o=空、f/t=0。</li>
 * </ul>
 */
public final class MomentsFakeLikeHook {

    private static final String TAG = "MomentsFakeLike";

    public static final String K_ENABLED = "ls_moments_fake_like_enabled";
    public static final String K_COUNT = "ls_moments_fake_like_count";

    /** 自定义 itemId，避开微信原生 0/1/3/4/5/6/7/11/29/37/41（文档推荐值）。 */
    private static final int MENU_ID_FAKE_LIKE = 0x6C6B;
    private static final int MENU_GROUP = 1000;
    private static final int MENU_ORDER_FAKE_LIKE = 99;
    private static final int DEFAULT_COUNT = 10;
    private static final int MAX_COUNT = 50;

    /**
     * v3.0.148：新版时间线(ImproveSnsTimelineUI)渲染点赞时读 {@code jk4.p.Q0()}
     * (ImproveSnsInfo.getLikeUserList) 返回的 {@code uu5.c}，且该容器是 by-lazy
     * 首算后永不再算——改 {@code SnsInfo.field_attrBuf} 对时间线不再上屏。
     * 这里用「菜单点击时的 localId」→ 伪造名单缓存，在 setupLikeLayout 渲染前注入
     * 缓存的 {@code uu5.c.a} List（每行 av5.h，a[0]=wxid、a[1]=昵称）。
     */
    private static final Map<String, List<String[]>> PENDING_LIKES =
            Collections.synchronizedMap(new java.util.LinkedHashMap<String, List<String[]>>());
    private static final Map<String, Long> PENDING_LIKES_TS =
            Collections.synchronizedMap(new java.util.HashMap<String, Long>());
    /** v3.0.158：按 snsId({@code SnsObject.Id}, long) 索引的名单。
     *  渲染侧 ImproveSnsInfo 的 localId 常解析为 -1，按 Id 匹配才能精确命中被渲染的 SnsObject。 */
    private static final Map<Long, List<String[]>> PENDING_BY_SNSID =
            Collections.synchronizedMap(new java.util.LinkedHashMap<Long, List<String[]>>());
    private static final Map<Long, Long> PENDING_BY_SNSID_TS =
            Collections.synchronizedMap(new java.util.HashMap<Long, Long>());
    /** 内存注入名单存活时长：渲染路径每次 bind 都会重新取，加长以跨滚动/多屏复用。 */
    private static final long PENDING_TTL_MS = 5 * 60 * 1000;
    private static final java.util.concurrent.locks.ReentrantLock PENDING_LOCK =
            new java.util.concurrent.locks.ReentrantLock();

    /** v3.0.156：诊断节流表（避免高频 bind 刷屏）。 */
    private static final Map<String, Long> DIAG_TS =
            Collections.synchronizedMap(new java.util.HashMap<String, Long>());

    /**
     * v3.0.163：已注入过伪造赞的<b>渲染层缓存对象</b>登记表（WeakHashMap，对象回收自动消失）。
     * 取值约定：{@code "snsObject"} / {@code "likeCenter"} —— 供取消秒集赞时物理移除伪造行。
     * 注入点在 {@link #injectIntoSnsObject} / {@link #injectIntoLikeCenter}。
     */
    private static final java.util.Map<Object, String> INJECTED_RENDER_OBJECTS =
            Collections.synchronizedMap(new WeakHashMap<Object, String>());

    /** 同一 key 在 intervalMs 内只放行一次日志。 */
    private static boolean diag(String key, long intervalMs) {
        long now = System.currentTimeMillis();
        Long last = DIAG_TS.get(key);
        if (last != null && now - last < intervalMs) return false;
        DIAG_TS.put(key, now);
        return true;
    }

    private static volatile ClassLoader sCl;
    private static final Map<View, View> ANCHOR_MAP =
            Collections.synchronizedMap(new WeakHashMap<View, View>());
    private static final Handler sH = new Handler(Looper.getMainLooper());

    private MomentsFakeLikeHook() {}

    // ================================================================
    // 配置
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

    public static int getCount() {
        SharedPreferences sp = prefs();
        int v = sp != null ? sp.getInt(K_COUNT, DEFAULT_COUNT) : DEFAULT_COUNT;
        return Math.max(1, Math.min(v, MAX_COUNT));
    }

    public static void setCount(int n) {
        SharedPreferences sp = prefs();
        if (sp != null) sp.edit().putInt(K_COUNT, Math.max(1, Math.min(n, MAX_COUNT))).apply();
        LogWriter.log(TAG, "setCount " + n);
    }

    // ================================================================
    // Hook 安装
    // ================================================================

    public static void hook(final ClassLoader cl) {
        sCl = cl;
        LogWriter.log(TAG, "install fake-like (v3.0.157)");
        // ① 注入长按菜单（MMPopupMenu 所有 SNS 长按菜单的唯一汇聚点）
        hookMenuRegister("h", 5, 1);
        hookMenuRegister("g", 6, 2);
        hookMenuRegister("j", 3, 1);
        // ② 拦截菜单点击（朋友圈主分发器 + 气泡菜单分发器）
        hookMenuSelected("com.tencent.mm.plugin.sns.ui.listener.c");
        hookMenuSelected("com.tencent.mm.plugin.sns.ui.k1");
        // ③ 新版时间线 ImproveInteractionLayout.setupLikeLayout(jk4.p) —— 数据层注入
        hookSetupLikeLayout(cl);
        // ④ v3.0.149：jk4.p.Q0() after 注入（兼容旧渲染路径）
        hookImproveInfoQ0(cl);
        // ⑤ v3.0.154：渲染真实读取路径注入 —— jk4.p.b1()(getMergeSnsObject) 与
        //    mh4.z0.D0()(SnsUtil.snsInfoToSnsStruct)。v3.0.153 实测 Q0()/setupLikeLayout
        //    运行期零触发，真正上屏的 SnsObject 来自这两处；只做纯内存注入，不写库、不崩。
        hookRenderSnsObject(cl);
    }

    /**
     * Hook {@code eu5.s0}（MMPopupMenu）的 h/g/j 注册方法，把第 2/3 参
     * {@code View.OnCreateContextMenuListener} 包一层 {@link WrappedMenuCreator}，
     * 在微信构建完菜单后追加「秒集赞」。
     *
     * @param method      方法名（h/g/j）
     * @param paramCount  方法参数个数（h=5、g=6、j=3）
     * @param listenerIdx listener 参数下标（h/j=1，g=2，因 g 第 2 参是 long）
     */
    private static void hookMenuRegister(String method, int paramCount, int listenerIdx) {
        Class<?> c = loadClass("eu5.s0");
        if (c == null) {
            LogWriter.log(TAG, "[WARN] eu5.s0 not found, menu inject off");
            return;
        }
        final int idx = listenerIdx;
        int hooked = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(method) || m.getParameterCount() != paramCount) continue;
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args.length <= idx) return;
                        Object origin = p.args[idx];
                        if (origin instanceof View.OnCreateContextMenuListener) {
                            p.args[idx] = new WrappedMenuCreator(
                                    (View) p.args[0], (View.OnCreateContextMenuListener) origin);
                        }
                    } catch (Throwable ignored) {}
                }
            });
            hooked++;
        }
        LogWriter.log(TAG, "hooked eu5.s0." + method + "/" + paramCount + " (listener@" + idx + ") x" + hooked);
    }

    /** 包装微信原生菜单创建回调：先让微信构建菜单，再追加「秒集赞」，并记录锚点 View。 */
    static final class WrappedMenuCreator implements View.OnCreateContextMenuListener {
        final View anchor;
        final View.OnCreateContextMenuListener origin;

        WrappedMenuCreator(View a, View.OnCreateContextMenuListener o) {
            anchor = a;
            origin = o;
        }

        @Override
        public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo info) {
            try {
                origin.onCreateContextMenu(menu, v, info);
                // v3.0.141：开关关闭时不注入菜单，避免「关闭了却仍出现秒集赞菜单、点击无反应」
                // 造成设置不生效的错觉。isEnabled() 实时读 prefs，长按时一定取到最新状态。
                if (!isEnabled()) return;
                if (menu.findItem(MENU_ID_FAKE_LIKE) != null) return;
                menu.add(MENU_GROUP, MENU_ID_FAKE_LIKE, MENU_ORDER_FAKE_LIKE, "秒集赞");
                if (anchor != null) ANCHOR_MAP.put(anchor, v);
                LogWriter.log(TAG, "injected 秒集赞 menu (anchor=" + anchor + ")");
            } catch (Throwable t) {
                LogWriter.log(TAG, "onCreateContextMenu err: " + t);
            }
        }
    }

    /** 拦截菜单点击：itemId == MENU_ID_FAKE_LIKE 时阻止微信原生分支，走秒集赞流程。 */
    private static void hookMenuSelected(String clsName) {
        Class<?> c = loadClass(clsName);
        if (c == null) {
            LogWriter.log(TAG, "[WARN] " + clsName + " not found");
            return;
        }
        int hooked = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!"onMMMenuItemSelected".equals(m.getName())) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != 2 || pts[0] != android.view.MenuItem.class) continue;
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!isEnabled()) return;
                    try {
                        android.view.MenuItem item = (android.view.MenuItem) p.args[0];
                        if (item == null || item.getItemId() != MENU_ID_FAKE_LIKE) return;
                        p.setResult(null); // 阻止微信原生分支
                        handleFakeLike(p);
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "onMMMenuItemSelected err: " + t);
                    }
                }
            });
            hooked++;
        }
        LogWriter.log(TAG, "hooked " + clsName + ".onMMMenuItemSelected x" + hooked);
    }

    // ================================================================
    // 新版时间线注入（v3.0.148）
    // ================================================================

    /**
     * Hook {@code ImproveInteractionLayout.setupLikeLayout(jk4.p)}：
     * <ul>
     *   <li><b>数据层(治本)</b>：before 里把伪造名单直接写进
     *       {@code jk4.p.Q0()}(ImproveSnsInfo.getLikeUserList) 返回的
     *       {@code uu5.c.a} public List（每行 av5.h，a[0]=wxid、a[1]=昵称）。
     *       由于 Q0() 是 by-lazy 首算后缓存，改 field_attrBuf 永不再上屏，
     *       必须改这个缓存 List。</li>
     *   <li><b>UI 破早退</b>：before 里 setVisibility(0)，防止原动态没人赞时
     *       {@code if (Q0().b() <= 0) { setVisibility(8); return; }} 早退。</li>
     * </ul>
     */
    private static void hookSetupLikeLayout(ClassLoader cl) {
        Class<?> layout = loadClass("com.tencent.mm.plugin.sns.ui.improve.view.ImproveInteractionLayout");
        if (layout == null) {
            LogWriter.log(TAG, "[WARN] ImproveInteractionLayout not found, improve inject off");
            return;
        }
        Class<?> infoCls = loadClass("jk4.p");
        if (infoCls == null) {
            LogWriter.log(TAG, "[WARN] jk4.p(ImproveSnsInfo) not found, improve inject off");
            return;
        }
        int hooked = 0;
        for (Method m : layout.getDeclaredMethods()) {
            if (!m.getName().equals("setupLikeLayout")) continue;
            if (m.getParameterCount() != 1) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (!infoCls.isAssignableFrom(pts[0])) continue;
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!isEnabled()) return;
                        Object info = p.args[0];
                        if (info == null) return;
                        // 破早退：原动态没人赞时 setupLikeLayout 会直接 return
                        try {
                            Object likeLayout = XposedHelpers.callMethod(p.thisObject, "getLikeLayout");
                            if (likeLayout != null) {
                                XposedHelpers.callMethod(likeLayout, "setVisibility", android.view.View.VISIBLE);
                            }
                        } catch (Throwable ignored) {}
                        List<String[]> pairs = null;
                        // v3.0.160：优先按 e1()(snsId) 命中（渲染侧 localId 常为 -1）
                        long sid = 0L;
                        try { sid = ((Number) XposedHelpers.callMethod(info, "e1")).longValue(); } catch (Throwable ignored) {}
                        List<String[]> byId = PendingLikes.peekBySnsId(sid);
                        if (byId != null && !byId.isEmpty()) {
                            pairs = byId;
                        } else {
                            pairs = PendingLikes.take(info);
                        }
                        if (pairs == null || pairs.isEmpty()) return;
                        Object likeC = XposedHelpers.callMethod(info, "Q0");
                        if (likeC == null) return;
                        int added = injectIntoLikeCenter(likeC, pairs);
                        if (added > 0) {
                            LogWriter.log(TAG, "improve inject +" + added
                                    + " total=" + injectedRowCount(likeC) + " layout=" + layout.getName()
                                    + " snsId=" + sid);
                        }
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "setupLikeLayout err: " + t);
                    }
                }
            });
            hooked++;
        }
        LogWriter.log(TAG, "hooked " + layout.getName() + ".setupLikeLayout x" + hooked);
    }

    /**
     * v3.0.149：hook {@code jk4.p.Q0()}（ImproveSnsInfo.getLikeUserList，by-lazy 首算后缓存）。
     * <p>v3.0.148 实测：新版时间线渲染点赞区不调用 ImproveInteractionLayout.setupLikeLayout
     * （钩子已装但运行期零触发，无 improve inject / pending take 日志），PendingLikes 名单永不注入。
     * 改为在渲染读 Q0() 的 after 直接注入 —— 无论渲染路径走哪个方法，只要读 Q0() 缓存必然命中。</p>
     */
    private static void hookImproveInfoQ0(ClassLoader cl) {
        Class<?> infoCls = loadClass("jk4.p");
        if (infoCls == null) {
            LogWriter.log(TAG, "[WARN] jk4.p not found, Q0 inject off");
            return;
        }
        int hooked = 0;
        for (Method m : infoCls.getDeclaredMethods()) {
            if (!m.getName().equals("Q0") || m.getParameterCount() != 0) continue;
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!isEnabled()) return;
                        Object info = p.thisObject;
                        Object likeC = p.getResult();
                        if (likeC == null) return;
                        // v3.0.160：渲染侧 ImproveSnsInfo.localId 常解析为 -1（sns_table_-1），
                        // 先按 e1()(snsId, long) 命中——与 doFakeLike readSnsId 的 field_snsId 对应。
                        long sid = 0L;
                        try { sid = ((Number) XposedHelpers.callMethod(info, "e1")).longValue(); } catch (Throwable ignored) {}
                        List<String[]> byId = PendingLikes.peekBySnsId(sid);
                        if (byId != null && !byId.isEmpty()) {
                            int a2 = injectIntoLikeCenter(likeC, byId);
                            if (a2 > 0) LogWriter.logSync(TAG, "Q0 inject(bySnsId) +" + a2 + " snsId=" + sid);
                            return;
                        }
                        List<String[]> pairs = PendingLikes.peek(info);
                        if (pairs == null || pairs.isEmpty()) {
                            if (!PENDING_LIKES.isEmpty() && diag("Q0", 2000)) {
                                LogWriter.logSync(TAG, "Q0 CALL miss resolved="
                                        + PendingLikes.resolveLocalId(info)
                                        + " e1=" + sid
                                        + " pendingBySn=" + PENDING_BY_SNSID.keySet());
                            }
                            return;
                        }
                        int added = injectIntoLikeCenter(likeC, pairs);
                        String localId = PendingLikes.resolveLocalId(info);
                        if (added > 0) {
                            LogWriter.logSync(TAG, "Q0 inject +" + added + " localId=" + localId);
                        }
                        // v3.0.154：不移除，交给渲染路径(b1/D0)每帧重复注入，直至 TTL 过期。
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "Q0 inject err: " + t);
                    }
                }
            });
            hooked++;
        }
        LogWriter.log(TAG, "hooked " + infoCls.getName() + ".Q0 x" + hooked);
    }

    /**
     * v3.0.154：渲染真实读取路径注入。v3.0.153 实测 {@code jk4.p.Q0()} 与
     * {@code setupLikeLayout} 运行期零触发；真正上屏的 {@code SnsObject} 来自：
     * <ul>
     *   <li>{@code jk4.p.b1()} = getMergeSnsObject（返回 SnsObject，逐帧 parseFrom field_attrBuf）</li>
     *   <li>{@code mh4.z0.D0(...)} = SnsUtil.snsInfoToSnsStruct（返回 rt：a=SnsInfo、c=SnsObject，
     *       improve 时间线 {@code improve.component.f2.y2} 调用）</li>
     * </ul>
     * 只往返回对象的 {@code LikeUserList} 里 addFirst 伪造 {@code pc5.uf6}（纯内存），
     * 不 toByteArray、不 setAttrBuf、不落库 —— 避免畸形 proto 污染微信持久化数据导致原生崩溃。
     */
    private static void hookRenderSnsObject(ClassLoader cl) {
        // ① jk4.p.b1() → SnsObject
        Class<?> infoCls = loadClass("jk4.p");
        if (infoCls != null) {
            int hooked = 0;
            for (Method m : infoCls.getDeclaredMethods()) {
                if (!m.getName().equals("b1") || m.getParameterCount() != 0) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (!isEnabled()) return;
                            Object res = p.getResult();
                            if (res == null) return;
                            // v3.0.162（《WeChat_Moments_AutoLike_Reverse.md》§0）：b1() 的返回值就是
                            // 真正被渲染的 SnsObject —— 直接用 thisObject.V0() 拿 localId，本地命名单，
                            // 直接改返回对象的 LikeUserList，不再做任何 snsId 反查（SnsObject.Id 与
                            // snsId 是两套号，反查必然 miss）。
                            long rid = 0L;
                            try { rid = ((Number) XposedHelpers.getObjectField(res, "Id")).longValue(); } catch (Throwable ignored) {}
                            String lid = PendingLikes.resolveLocalId(p.thisObject);
                            List<String[]> pairs = PendingLikes.peek(lid);
                            if (pairs == null || pairs.isEmpty()) {
                                // 本地名未命中 → 兜底按渲染对象 Id（旧路径）
                                List<String[]> byId = PendingLikes.peekBySnsId(rid);
                                if (byId != null && !byId.isEmpty()) {
                                    int a2 = injectIntoSnsObject(res, byId);
                                    if (a2 > 0) LogWriter.logSync(TAG, "b1 inject(bySnsId,fallback) +"
                                            + a2 + " Id=" + rid);
                                    return;
                                }
                                if (PendingLikes.hasBySnsId() && diag("b1", 2000)) {
                                    LogWriter.logSync(TAG, "b1 CALL miss localId=" + lid
                                            + " renderId=" + rid);
                                }
                                return;
                            }
                            int added = injectIntoSnsObject(res, pairs);
                            if (added > 0) {
                                LogWriter.logSync(TAG, "b1 inject +" + added + " localId=" + lid);
                            }
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "b1 inject err: " + t);
                        }
                    }
                });
                hooked++;
            }
            LogWriter.log(TAG, "hooked jk4.p.b1 x" + hooked);
        } else {
            LogWriter.log(TAG, "[WARN] jk4.p not found, b1 inject off");
        }
        // ② mh4.z0.D0(...) → rt{a=SnsInfo, c=SnsObject}
        Class<?> snsUtil = loadClass("mh4.z0");
        if (snsUtil != null) {
            int hooked = 0;
            for (Method m : snsUtil.getDeclaredMethods()) {
                if (!m.getName().equals("D0")) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (!isEnabled()) return;
                            Object rt = p.getResult();
                            if (rt == null) return;
                            Object snsObj = XposedHelpers.getObjectField(rt, "c");
                            // ① v3.0.158：按 SnsObject.Id 精确匹配
                            long rid = 0L;
                            try { rid = ((Number) XposedHelpers.getObjectField(snsObj, "Id")).longValue(); } catch (Throwable ignored) {}
                            List<String[]> byId = PendingLikes.peekBySnsId(rid);
                            if (byId != null && !byId.isEmpty()) {
                                int a2 = injectIntoSnsObject(snsObj, byId);
                                if (a2 > 0) LogWriter.logSync(TAG, "D0 inject(bySnsId) +" + a2 + " Id=" + rid);
                                return;
                            }
                            // ② 回退 localId（v3.0.162：优先用入参 SnsInfo.getLocalid；比 rt.a 更可靠。
                            //    bySnsId(renderId) 与 localId 是两套号，先 localId 后 bySnsId）
                            String localId = null;
                            if (p.args != null && p.args.length > 0 && p.args[0] != null) {
                                try { localId = (String) XposedHelpers.callMethod(p.args[0], "getLocalid"); }
                                catch (Throwable ignored) {}
                            }
                            if (localId == null) {
                                try {
                                    Object sns = XposedHelpers.getObjectField(rt, "a");
                                    localId = (String) XposedHelpers.callMethod(sns, "getLocalid");
                                } catch (Throwable ignored) {}
                            }
                            List<String[]> pairs = PendingLikes.peek(localId);
                            if (pairs == null || pairs.isEmpty()) {
                                if (PendingLikes.hasBySnsId() && diag("D0", 2000)) {
                                    LogWriter.logSync(TAG, "D0 CALL miss localId=" + localId
                                            + " renderId=" + rid);
                                }
                                return;
                            }
                            int added = injectIntoSnsObject(snsObj, pairs);
                            if (added > 0) {
                                LogWriter.logSync(TAG, "D0 inject +" + added + " localId=" + localId);
                            }
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "D0 inject err: " + t);
                        }
                    }
                });
                hooked++;
            }
            LogWriter.log(TAG, "hooked mh4.z0.D0 x" + hooked);
        } else {
            LogWriter.log(TAG, "[WARN] mh4.z0 not found, D0 inject off");
        }
    }

    /**
     * 向 {@code SnsObject.LikeUserList}({@code LinkedList<pc5.uf6>}) 注入伪造点赞行（纯内存）。
     * 返回实际新增数；命中已存在同名项则跳过。
     */
    private static int injectIntoSnsObject(Object snsObj, List<String[]> pairs) {
        if (snsObj == null || pairs == null || pairs.isEmpty()) return 0;
        try {
            Object luo = XposedHelpers.getObjectField(snsObj, "LikeUserList");
            if (!(luo instanceof LinkedList)) return 0;
            @SuppressWarnings("unchecked")
            LinkedList<Object> list = (LinkedList<Object>) luo;
            Class<?> uf6 = loadClass("pc5.uf6");
            if (uf6 == null) {
                LogWriter.logSync(TAG, "pc5.uf6 not found, SnsObject inject off");
                return 0;
            }
            int added = 0;
            for (String[] pr : pairs) {
                String wxid = pr[0], nick = pr[1];
                if (wxid == null || wxid.isEmpty()) continue;
                boolean dup = false;
                for (Object o : list) {
                    if (wxid.equals(XposedHelpers.getObjectField(o, "d"))) { dup = true; break; }
                }
                if (dup) continue;
                Object it = XposedHelpers.newInstance(uf6);
                XposedHelpers.setObjectField(it, "d", wxid);
                XposedHelpers.setObjectField(it, "e", nick != null ? nick : wxid);
                XposedHelpers.setObjectField(it, "h", "");
                XposedHelpers.setObjectField(it, "o", "");
                XposedHelpers.setObjectField(it, "g", 1);   // 1=好友赞（这些是用户好友）
                XposedHelpers.setObjectField(it, "f", 0);
                XposedHelpers.setObjectField(it, "t", 0);
                int now = (int) (System.currentTimeMillis() / 1000);
                XposedHelpers.setObjectField(it, "i", now);
                XposedHelpers.setObjectField(it, "m", now);
                list.addFirst(it);
                added++;
            }
            if (added > 0) {
                int cnt = list.size();
                XposedHelpers.setObjectField(snsObj, "LikeCount", cnt);
                XposedHelpers.setObjectField(snsObj, "LikeUserListCount", cnt);
                INJECTED_RENDER_OBJECTS.put(snsObj, "snsObject");
            }
            return added;
        } catch (Throwable t) {
            LogWriter.logSync(TAG, "injectIntoSnsObject err: " + t);
            return 0;
        }
    }
    /**
     * 向 likeC(uu5.c.a) 的 a(List<av5.h>) 注入伪造点赞行，返回实际新增数。
     */
    private static int injectIntoLikeCenter(Object likeC, List<String[]> pairs) {
        if (likeC == null || pairs == null || pairs.isEmpty()) return 0;
        try {
            @SuppressWarnings("unchecked")
            List<Object> rows = (List<Object>) XposedHelpers.getObjectField(likeC, "a");
            if (rows == null) return 0;
            Class<?> hCls = loadClass("av5.h");
            if (hCls == null) {
                LogWriter.logSync(TAG, "av5.h not found, improve inject off");
                return 0;
            }
            int added = 0;
            for (String[] pr : pairs) {
                String wxid = pr[0], nick = pr[1];
                if (wxid == null || nick == null) continue;
                boolean dup = false;
                for (Object r : rows) {
                    Object[] a = (Object[]) XposedHelpers.getObjectField(r, "a");
                    if (a != null && a.length > 0 && wxid.equals(a[0])) { dup = true; break; }
                }
                if (dup) continue;
                Object row = XposedHelpers.newInstance(hCls);
                Object[] cols = (Object[]) XposedHelpers.getObjectField(row, "a");
                cols[0] = wxid;
                cols[1] = nick;
                XposedHelpers.setObjectField(row, "a", cols);
                rows.add(0, row);
                added++;
            }
            if (added > 0) INJECTED_RENDER_OBJECTS.put(likeC, "likeCenter");
            return added;
        } catch (Throwable t) {
            LogWriter.logSync(TAG, "injectIntoLikeCenter err: " + t);
            return 0;
        }
    }

    private static int injectedRowCount(Object likeC) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> rows = (List<Object>) XposedHelpers.getObjectField(likeC, "a");
            return rows == null ? 0 : rows.size();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 秒集赞名单暂存：localId → 名单（TTL 10s 后自动清理）。
     * setupLikeLayout 渲染时从 jk4.p(ImproveSnsInfo) 反查 SnsInfo.getLocalid()
     * 匹配 key，命中则把名单注入 uu5.c.a 缓存 List。
     */
    static final class PendingLikes {
        static void put(String localId, List<String[]> pairs) {
            if (localId == null) return;
            PENDING_LOCK.lock();
            try {
                purgeExpiredLocked();
                PENDING_LIKES.put(localId, pairs);
                PENDING_LIKES_TS.put(localId, System.currentTimeMillis());
            } finally {
                PENDING_LOCK.unlock();
            }
            LogWriter.log(TAG, "pending put localId=" + localId + " n=" + (pairs == null ? 0 : pairs.size()));
        }

        static List<String[]> peek(String localId) {
            if (localId == null) return null;
            PENDING_LOCK.lock();
            try {
                purgeExpiredLocked();
                return PENDING_LIKES.get(localId);
            } finally {
                PENDING_LOCK.unlock();
            }
        }

        /** v3.0.158：按 snsId 暂存名单，供渲染路径 b1()/D0() 按 SnsObject.Id 精确注入。 */
        static void putBySnsId(long snsId, List<String[]> pairs) {
            if (snsId == 0L) return;
            PENDING_LOCK.lock();
            try {
                long now = System.currentTimeMillis();
                java.util.Iterator<java.util.Map.Entry<Long, Long>> it = PENDING_BY_SNSID_TS.entrySet().iterator();
                while (it.hasNext()) {
                    java.util.Map.Entry<Long, Long> e = it.next();
                    if (now - e.getValue() > PENDING_TTL_MS) {
                        PENDING_BY_SNSID.remove(e.getKey());
                        it.remove();
                    }
                }
                PENDING_BY_SNSID.put(snsId, pairs);
                PENDING_BY_SNSID_TS.put(snsId, now);
            } finally {
                PENDING_LOCK.unlock();
            }
            LogWriter.logSync(TAG, "pending put snsId=" + snsId + " n=" + (pairs == null ? 0 : pairs.size()));
        }

        static List<String[]> peekBySnsId(long snsId) {
            if (snsId == 0L) return null;
            PENDING_LOCK.lock();
            try {
                purgeExpiredLocked();
                return PENDING_BY_SNSID.get(snsId);
            } finally {
                PENDING_LOCK.unlock();
            }
        }

        static void clearBySnsId(long snsId) {
            PENDING_LOCK.lock();
            try {
                PENDING_BY_SNSID.remove(snsId);
                PENDING_BY_SNSID_TS.remove(snsId);
            } finally {
                PENDING_LOCK.unlock();
            }
        }

        static boolean hasBySnsId() {
            PENDING_LOCK.lock();
            try {
                purgeExpiredLocked();
                return !PENDING_BY_SNSID.isEmpty();
            } finally {
                PENDING_LOCK.unlock();
            }
        }

        /** 从 jk4.p(ImproveSnsInfo) 提取 localId 并取名单（不移除，供 Q0 注入重复检查）。 */
        static List<String[]> peek(Object improveInfo) {
            if (improveInfo == null) return null;
            return peek(resolveLocalId(improveInfo));
        }

        /** 强制移除（供 Q0 注入成功后清理，防重复注入）。 */
        static void remove(String localId) {
            if (localId == null) return;
            PENDING_LOCK.lock();
            try {
                PENDING_LIKES.remove(localId);
                PENDING_LIKES_TS.remove(localId);
            } finally {
                PENDING_LOCK.unlock();
            }
        }

        /** 从 jk4.p(ImproveSnsInfo) 提取 localId 并取出名单（取出后即移除）。 */
        static List<String[]> take(Object improveInfo) {
            if (improveInfo == null) return null;
            String localId = resolveLocalId(improveInfo);
            if (localId == null) return null;
            PENDING_LOCK.lock();
            try {
                purgeExpiredLocked();
                List<String[]> v = PENDING_LIKES.remove(localId);
                PENDING_LIKES_TS.remove(localId);
                if (v != null) LogWriter.log(TAG, "pending take localId=" + localId + " n=" + v.size());
                return v;
            } finally {
                PENDING_LOCK.unlock();
            }
        }

        /** 强制过期（供调试/兜底）。 */
        static void clear(String localId) {
            if (localId == null) return;
            PENDING_LOCK.lock();
            try {
                PENDING_LIKES.remove(localId);
                PENDING_LIKES_TS.remove(localId);
            } finally {
                PENDING_LOCK.unlock();
            }
        }

        private static void purgeExpiredLocked() {
            long now = System.currentTimeMillis();
            java.util.Iterator<java.util.Map.Entry<String, Long>> it = PENDING_LIKES_TS.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<String, Long> e = it.next();
                if (now - e.getValue() > PENDING_TTL_MS) {
                    PENDING_LIKES.remove(e.getKey());
                    it.remove();
                }
            }
            java.util.Iterator<java.util.Map.Entry<Long, Long>> it2 = PENDING_BY_SNSID_TS.entrySet().iterator();
            while (it2.hasNext()) {
                java.util.Map.Entry<Long, Long> e = it2.next();
                if (now - e.getValue() > PENDING_TTL_MS) {
                    PENDING_BY_SNSID.remove(e.getKey());
                    it2.remove();
                }
            }
        }

        /** jk4.p → 优先 V0()（直接取 ImproveSnsInfo.localId，渲染侧稳定）；
         *  失败回退 jk4.p→Z0()→SnsInfo.getLocalid()，再退 getLocalId/getLocalID 直查。
         *  v3.0.162（《WeChat_Moments_AutoLike_Reverse.md》§0）：b1() 的返回值就是渲染源，
         *  不要经由 snsId 反查；渲染侧 localId 用 V0() 拿（Z0() 懒加载常返回 sns_table_-1）。 */
        static String resolveLocalId(Object improveInfo) {
            for (String m : new String[]{ "V0", "getLocalid", "getLocalId", "getLocalID" }) {
                try {
                    Object v = XposedHelpers.callMethod(improveInfo, m);
                    if (v instanceof String) return (String) v;
                } catch (Throwable ignored) {}
            }
            try {
                Object sns = XposedHelpers.callMethod(improveInfo, "Z0");
                if (sns != null) {
                    Object v = XposedHelpers.callMethod(sns, "getLocalid");
                    if (v instanceof String) return (String) v;
                }
            } catch (Throwable ignored) {}
            return null;
        }
    }

    // ================================================================
    // 秒集赞主流程
    // ================================================================

    private static void handleFakeLike(XC_MethodHook.MethodHookParam p) {
        Object listener = p.thisObject;
        View anchor = null;
        String localId = null;
        try { anchor = (View) XposedHelpers.getObjectField(listener, "e"); } catch (Throwable ignored) {}
        try { localId = (String) XposedHelpers.getObjectField(listener, "f"); } catch (Throwable ignored) {}
        final View fAnchor = anchor;
        final String fLocalId = localId;
        final Object snsInfo = localId != null ? getSnsInfo(localId) : null;
        if (snsInfo == null) {
            LogWriter.log(TAG, "秒集赞：未定位到动态 localId=" + fLocalId);
            toast(fAnchor, "秒集赞：未定位到动态");
            return;
        }
        final Object fSnsInfo = snsInfo;
        final Activity act = getActivity(fAnchor);
        if (act == null) {
            toast(fAnchor, "秒集赞：未找到页面");
            return;
        }
        // v3.0.162（用户要求）：长按菜单点击「秒集赞」进入 M3 风格控制面板窗口
        // （不再是原生菜单/列表排列样式），并提供「取消秒集赞」入口。
        showControlPanel(act, fAnchor, fLocalId, fSnsInfo);
    }

    /**
     * M3 风格秒集赞控制面板窗口。
     *
     * <p>包含三个动作：<b>随机集赞</b>、<b>自选联系人集赞</b>、<b>取消秒集赞</b>（新增），
     * 以及底部「关闭」按钮。样式复用 {@link ModernTopBar} / {@link M3Page} / {@link ModernButton}。</p>
     */
    private static void showControlPanel(final Activity act, final View anchor,
                                         final String localId, final Object snsInfo) {
        final float d = act.getResources().getDisplayMetrics().density;
        final int p12 = (int) (12 * d);
        final int p8 = (int) (8 * d);
        final int p4 = (int) (4 * d);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CandyUi.dialogBg(act, WindowLayer.depth()));
        InsetsUtil.clipRounded(root);
        CandyUi.elevate(root);

        final AlertDialog[] dialogRef = new AlertDialog[1];
        final Runnable dismiss = () -> {
            if (dialogRef[0] != null) {
                try { dialogRef[0].dismiss(); } catch (Throwable ignored) {}
            }
        };

        ModernTopBar topBar = new ModernTopBar(act, "朋友圈秒集赞", true, dismiss);
        root.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

        // 动态归属提示
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(p12, 0, p12, p4);
        android.widget.TextView tv = new android.widget.TextView(act);
        tv.setTextSize(13);
        tv.setTextColor(com.leshao.v3.ui.AppColors.onSurfaceVariant());
        tv.setText("对当前动态本地伪造点赞（仅本机可见）\nlocalId=" + localId);
        head.addView(tv);
        root.addView(head);

        LinearLayout card = M3Page.card(act);
        // ① 随机集赞
        M3Page.appendClickRow(card, act, "🎲", "随机集赞 " + getCount() + " 位",
                "随机从好友列表挑选，立即显示点赞", () -> {
                    dismiss.run();
                    fakeLikeRandom(act, localId, snsInfo);
                });
        card.addView(M3Page.divider(act));
        // ② 自选联系人
        M3Page.appendClickRow(card, act, "👤", "自选联系人集赞",
                "从好友列表手动挑选集赞对象", () -> {
                    dismiss.run();
                    ContactPickerDialog.show(act, "", ContactPickerDialog.MODE_FRIEND,
                            (Set<String> wxids, String display) -> {
                                if (wxids == null || wxids.isEmpty()) {
                                    toast(anchor, "未选择联系人");
                                    return;
                                }
                                fakeLikeWithWxids(act, localId, snsInfo, wxids);
                            },
                            null, "选择集赞联系人");
                });
        card.addView(M3Page.divider(act));
        // ③ 取消秒集赞（v3.0.162 新增）
        M3Page.appendClickRow(card, act, "🛑", "取消秒集赞",
                "移除本动态已注入的伪造点赞并还原", () -> {
                    dismiss.run();
                    cancelFakeLike(act, anchor, localId, snsInfo);
                });
        root.addView(card);

        // 底部关闭按钮
        LinearLayout bottom = new LinearLayout(act);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(android.view.Gravity.CENTER);
        bottom.setPadding(p12, p8, p12, p12);
        ModernButton close = new ModernButton(act, "关闭", ModernButton.STYLE_GHOST);
        close.onClick(dismiss);
        bottom.addView(close, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(bottom);

        AlertDialog dialog = new AlertDialog.Builder(act)
                .setView(root)
                .setCancelable(true)
                .create();
        dialogRef[0] = dialog;
        InsetsUtil.transparentWindow(dialog);
        dialog.show();
        WindowLayer.track(dialog.getWindow());
    }

    /**
     * 取消秒集赞：清空该动态的伪造名单（localId + snsId 双索引），并把
     * SnsInfo.field_attrBuf 中已注入的伪造项（按 wxid 匹配）移除后写回。
     *
     * <p>v3.0.163 修复「取消不生效」：
     * <ul>
     *   <li>① 旧版只移除 attrBuf，但伪造赞实际经 b1()/D0() 渲染路径注入到<b>渲染层缓存对象</b>
     *       （SnsObject.LikeUserList / likeC.a），attrBuf 移除对屏幕上已渲染的伪造赞无效；</li>
     *   <li>② 旧版取消后调 {@link #refresh()} 带 800ms 节流（{@code diag("refresh",800)}），
     *       而注入后紧接的 refresh 刚消耗窗口，取消的 refresh 被节流吞掉、列表不重绘；</li>
     *   <li>③ 本版改为：清名单 + attrBuf 物理移除 + 全 SnsObject 渲染缓存对象物理移除
     *       + 强制无节流刷新。</li>
     * </ul>
     */
    private static void cancelFakeLike(Activity act, View anchor, String localId, Object snsInfo) {
        int cleared = 0;
        long snsId = readSnsId(snsInfo);
        // 1. 收集注入名单（去重）
        List<String[]> injected = new ArrayList<>();
        List<String[]> bySns = PendingLikes.peekBySnsId(snsId);
        if (bySns != null && !bySns.isEmpty()) {
            for (String[] p : bySns) {
                if (!containsPair(injected, p)) injected.add(p);
            }
        }
        List<String[]> byLocal = PendingLikes.peek(localId);
        if (byLocal != null && !byLocal.isEmpty()) {
            for (String[] p : byLocal) {
                if (!containsPair(injected, p)) injected.add(p);
            }
        }
        // 2. 清空名单（后续 b1/D0/Q0 渲染不再注入）
        PendingLikes.clearBySnsId(snsId);
        PendingLikes.clear(localId);
        // 3. 从 attrBuf 物理移除注入项（权威 SnsInfo：优先按 localId 从存储重取）
        Object authoritative = getSnsInfo(localId);
        if (authoritative == null) authoritative = snsInfo;
        cleared = removeInjectedLikes(authoritative, injected);
        // 4. 从所有已注入的渲染层缓存对象物理移除（v3.0.163 核心修复）
        int purged = purgeInjectedRenderObjects(injected);
        // 5. 强制刷新（绕过 800ms 节流，避免被注入后的 refresh 吞掉）
        forceRefresh();
        LogWriter.logSync(TAG, "取消秒集赞 localId=" + localId + " snsId=" + snsId
                + " injected=" + injected.size() + " cleared=" + cleared
                + " purged=" + purged + " authoritative="
                + (authoritative != null ? authoritative.getClass().getName() : "null"));
        toast(anchor, "已取消秒集赞");
    }

    private static boolean containsPair(List<String[]> list, String[] p) {
        if (p == null || p.length == 0) return false;
        for (String[] q : list) {
            if (q != null && q.length > 0 && p[0].equals(q[0])) return true;
        }
        return false;
    }

    /** 从 SnsInfo.field_attrBuf 的 SnsObject.LikeUserList 中移除注入的伪造项（按 wxid 匹配）。 */
    private static int removeInjectedLikes(Object snsInfo, List<String[]> injected) {
        if (snsInfo == null || injected == null || injected.isEmpty()) return 0;
        try {
            byte[] attr = (byte[]) XposedHelpers.getObjectField(snsInfo, "field_attrBuf");
            if (attr == null || attr.length == 0) return 0;
            Class<?> soCls = XposedHelpers.findClass("com.tencent.mm.protocal.protobuf.SnsObject", sCl);
            Object snsObj = XposedHelpers.callMethod(
                    XposedHelpers.newInstance(soCls), "parseFrom", (Object) attr);
            if (snsObj == null) return 0;
            @SuppressWarnings("unchecked")
            LinkedList<Object> list = (LinkedList<Object>)
                    XposedHelpers.getObjectField(snsObj, "LikeUserList");
            if (list == null) return 0;
            Set<String> wxids = new java.util.HashSet<>();
            for (String[] p : injected) {
                if (p != null && p[0] != null) wxids.add(p[0]);
            }
            int removed = 0;
            Iterator<Object> it = list.iterator();
            while (it.hasNext()) {
                Object o = it.next();
                try {
                    Object d = XposedHelpers.getObjectField(o, "d");
                    if (d instanceof String && wxids.contains((String) d)) {
                        it.remove();
                        removed++;
                    }
                } catch (Throwable ignored) {}
            }
            if (removed == 0) return 0;
            int cnt = list.size();
            try { XposedHelpers.setObjectField(snsObj, "LikeCount", cnt); } catch (Throwable ignored) {}
            try { XposedHelpers.setObjectField(snsObj, "LikeUserListCount", cnt); } catch (Throwable ignored) {}
            byte[] out = (byte[]) XposedHelpers.callMethod(snsObj, "toByteArray");
            if (out == null || out.length == 0) return 0;
            XposedHelpers.callMethod(snsInfo, "setAttrBuf", (Object) out);
            XposedHelpers.setObjectField(snsInfo, "field_attrBuf", out);
            return removed;
        } catch (Throwable t) {
            LogWriter.logSync(TAG, "removeInjectedLikes err: " + t);
            return 0;
        }
    }

    /**
     * v3.0.163：从所有已注入的<b>渲染层缓存对象</b>中物理移除伪造行（按 wxid 匹配）。
     *
     * <p>背景：b1()/D0() 渲染路径把伪造项注入到返回的 {@code SnsObject.LikeUserList}，
     * Q0()/setupLikeLayout 注入到 {@code likeC.a}（List<av5.h>）。这些对象被时间线缓存
     * 持有，attrBuf 移除后屏幕仍显示旧行 —— 取消秒集赞必须回到这些缓存对象逐行删除。</p>
     *
     * @return 移除的总行数
     */
    private static int purgeInjectedRenderObjects(List<String[]> injected) {
        if (injected == null || injected.isEmpty()) return 0;
        Set<String> wxids = new java.util.HashSet<>();
        for (String[] p : injected) {
            if (p != null && p[0] != null) wxids.add(p[0]);
        }
        if (wxids.isEmpty()) return 0;
        int purged = 0;
        synchronized (INJECTED_RENDER_OBJECTS) {
            java.util.Iterator<java.util.Map.Entry<Object, String>> it =
                    INJECTED_RENDER_OBJECTS.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<Object, String> e = it.next();
                Object obj = e.getKey();
                String kind = e.getValue();
                if (obj == null) {
                    it.remove();
                    continue;
                }
                try {
                    if ("snsObject".equals(kind)) {
                        purged += removeRowsFromSnsObject(obj, wxids);
                    } else if ("likeCenter".equals(kind)) {
                        purged += removeRowsFromLikeCenter(obj, wxids);
                    }
                } catch (Throwable t) {
                    LogWriter.logSync(TAG, "purge obj err: " + t);
                }
            }
        }
        LogWriter.logSync(TAG, "purgeInjectedRenderObjects removed=" + purged);
        return purged;
    }

    /** 从 SnsObject.LikeUserList 移除指定 wxid 的伪造行，并同步 LikeCount。 */
    private static int removeRowsFromSnsObject(Object snsObj, Set<String> wxids) {
        if (snsObj == null) return 0;
        try {
            Object luo = XposedHelpers.getObjectField(snsObj, "LikeUserList");
            if (!(luo instanceof LinkedList)) return 0;
            @SuppressWarnings("unchecked")
            LinkedList<Object> list = (LinkedList<Object>) luo;
            int removed = 0;
            Iterator<Object> it = list.iterator();
            while (it.hasNext()) {
                Object o = it.next();
                try {
                    Object d = XposedHelpers.getObjectField(o, "d");
                    if (d instanceof String && wxids.contains((String) d)) {
                        it.remove();
                        removed++;
                    }
                } catch (Throwable ignored) {}
            }
            if (removed > 0) {
                int cnt = list.size();
                try { XposedHelpers.setObjectField(snsObj, "LikeCount", cnt); } catch (Throwable ignored) {}
                try { XposedHelpers.setObjectField(snsObj, "LikeUserListCount", cnt); } catch (Throwable ignored) {}
            }
            return removed;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 从 likeC(uu5.c.a) 的 a(List<av5.h>) 移除指定 wxid 的伪造行。 */
    private static int removeRowsFromLikeCenter(Object likeC, Set<String> wxids) {
        if (likeC == null) return 0;
        try {
            @SuppressWarnings("unchecked")
            List<Object> rows = (List<Object>) XposedHelpers.getObjectField(likeC, "a");
            if (rows == null) return 0;
            int removed = 0;
            Iterator<Object> it = rows.iterator();
            while (it.hasNext()) {
                Object r = it.next();
                try {
                    Object[] a = (Object[]) XposedHelpers.getObjectField(r, "a");
                    if (a != null && a.length > 0 && a[0] instanceof String
                            && wxids.contains((String) a[0])) {
                        it.remove();
                        removed++;
                    }
                } catch (Throwable ignored) {}
            }
            return removed;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 从好友列表随机取 count 位，伪造点赞并刷新。 */
    private static void fakeLikeRandom(Activity act, String localId, Object snsInfo) {
        List<ContactCard> friends = ContactRepository.getFriends();
        List<ContactCard> pool = new ArrayList<>();
        if (friends != null) {
            for (ContactCard c : friends) {
                if (c != null && c.username != null && !c.username.isEmpty()) pool.add(c);
            }
        }
        if (pool.isEmpty()) {
            toast(act, "联系人列表未加载，请稍后重试");
            return;
        }
        int n = Math.min(getCount(), pool.size());
        List<String[]> pairs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ContactCard c = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
            pairs.add(new String[]{ c.username, displayName(c) });
            pool.remove(c);
            if (pool.isEmpty()) break;
        }
        doFakeLike(act, localId, snsInfo, pairs);
    }

    /** 对选中的联系人伪造点赞并刷新。 */
    private static void fakeLikeWithWxids(Activity act, String localId, Object snsInfo, Set<String> wxids) {
        List<String[]> pairs = new ArrayList<>();
        for (String w : wxids) {
            if (w == null || w.isEmpty()) continue;
            ContactCard c = ContactRepository.findByUsername(w);
            pairs.add(new String[]{ w, c != null ? displayName(c) : w });
        }
        if (pairs.isEmpty()) {
            toast(act, "未选择联系人");
            return;
        }
        doFakeLike(act, localId, snsInfo, pairs);
    }

    private static void doFakeLike(Activity act, String localId, Object snsInfo, List<String[]> pairs) {
        // v3.0.157：改回「数据层纯内存注入」——把伪造项写进 snsInfo.field_attrBuf 并调 setAttrBuf()
        // 让 contentByteMd5 缓存失效，使下一次 bind(经 b1()/D0() 重新 parseFrom) 直接渲染出新名单。
        // 关键：本版**不落库**(不再调 store.w4)，因此不会污染微信持久化数据、不触发旧版原生崩溃。
        if (pairs == null || pairs.isEmpty()) {
            toast(act, "秒集赞未生效（未选择联系人）");
            return;
        }
        int added = 0;
        long snsId = 0L;
        if (snsInfo != null) {
            added = fakeLike(snsInfo, pairs);
            // v3.0.160：getSnsId() 返回 String（sns_table_<rowid> 或数字串），强转 Number 必抛
            // ClassCastException → snsId 恒 0 → bySnsId 渲染路径永不命中（v3.0.158/159 根因）。
            // 改读父类 im.ua.field_snsId(long)，失败再回退 getSnsId() 解析数字串。
            snsId = readSnsId(snsInfo);
        } else {
            LogWriter.logSync(TAG, "doFakeLike: snsInfo null localId=" + localId);
        }
        // v3.0.162（《WeChat_Moments_AutoLike_Reverse.md》§0/§5）：b1() 的返回值就是渲染源，
        // 渲染侧用 thisObject.V0() 的 localId 命名单。因此**始终**按 localId 登记
        // （此前仅 added==0 才登记，导致数据层写入成功时渲染路径无名单、b1() 注入为空）。
        if (localId != null) {
            PendingLikes.put(localId, pairs);
        }
        // v3.0.158：兜底按 snsId 登记，令渲染路径 b1()/D0() 按对象 Id 改写（旧路径保留）。
        if (snsId != 0L) PendingLikes.putBySnsId(snsId, pairs);
        refresh();
        toast(act, added > 0 ? ("已为 " + added + " 位联系人伪造点赞")
                : "秒集赞未生效（详见日志）");
        LogWriter.logSync(TAG, "doFakeLike done added=" + added + " localId=" + localId);
    }

    // ================================================================
    // 伪集赞核心（03_第三轮深挖 v3 最终版，含全部修正）
    // ================================================================

    /** 取 SnsInfo（唯一推荐入口：MergeInfoStorage.getByLocalId）。 */
    static Object getSnsInfo(String localId) {
        try {
            Class<?> l1 = XposedHelpers.findClass("com.tencent.mm.plugin.sns.storage.l1", sCl);
            return XposedHelpers.callStaticMethod(l1, "b", localId);
        } catch (Throwable t) {
            LogWriter.log(TAG, "getSnsInfo err: " + t);
            return null;
        }
    }

    /** 读 SnsInfo 的服务器 snsId（long）。优先级：父类 im.ua.field_snsId → getSnsId() 数字串。
     *  v3.0.160：getSnsId() 返回 String（新动态未分配时返回 "sns_table_<rowid>"），不能强转 Number。 */
    static long readSnsId(Object snsInfo) {
        if (snsInfo == null) return 0L;
        try {
            Object v = XposedHelpers.getObjectField(snsInfo, "field_snsId");
            if (v instanceof Number) return ((Number) v).longValue();
        } catch (Throwable ignored) {}
        try {
            Object v = XposedHelpers.callMethod(snsInfo, "getSnsId");
            if (v instanceof Number) return ((Number) v).longValue();
            if (v instanceof String) {
                String s = (String) v;
                if (s != null && s.length() > 0 && s.matches("\\d+")) {
                    return Long.parseLong(s);
                }
            }
        } catch (Throwable ignored) {}
        LogWriter.logSync(TAG, "readSnsId: unresolved class=" + snsInfo.getClass().getName());
        return 0L;
    }

    static String safeLocalId(Object snsInfo) {
        try {
            Object v = XposedHelpers.callMethod(snsInfo, "getLocalid");
            if (v != null) return v.toString();
        } catch (Throwable ignored) {}
        return "?";
    }

    /**
     * 本地伪造 LikeUserList。
     *
     * @return 实际新增的点赞数（已在列表中 / 参数异常返回 0）
     */
    public static int fakeLike(Object snsInfo, List<String[]> pairs) {
        try {
            if (snsInfo == null || pairs == null || pairs.isEmpty()) {
                LogWriter.logSync(TAG, "fakeLike: bad args snsInfo=" + (snsInfo == null ? "null" : "obj")
                        + " pairs=" + (pairs == null ? 0 : pairs.size()));
                return 0;
            }
            byte[] attr = (byte[]) XposedHelpers.getObjectField(snsInfo, "field_attrBuf");
            if (attr == null || attr.length == 0) {
                LogWriter.logSync(TAG, "fakeLike: attrBuf empty len=" + (attr == null ? -1 : attr.length)
                        + " cls=" + snsInfo.getClass().getName()
                        + " localid=" + safeLocalId(snsInfo));
                return 0;
            }

            Class<?> soCls = XposedHelpers.findClass("com.tencent.mm.protocal.protobuf.SnsObject", sCl);
            // ★★ parseFrom 是实例方法、返回新对象（不能丢返回值）
            Object snsObj = XposedHelpers.callMethod(
                    XposedHelpers.newInstance(soCls), "parseFrom", (Object) attr);
            if (snsObj == null) {
                LogWriter.logSync(TAG, "fakeLike: parseFrom null len=" + attr.length);
                return 0;
            }

            @SuppressWarnings("unchecked")
            LinkedList<Object> list = (LinkedList<Object>)
                    XposedHelpers.getObjectField(snsObj, "LikeUserList");
            Class<?> uf6 = loadClass("pc5.uf6");
            if (uf6 == null) {
                LogWriter.log(TAG, "pc5.uf6 not found");
                return 0;
            }

            int added = 0;
            for (String[] p : pairs) {
                String wxid = p[0], nick = p[1];
                if (wxid == null || wxid.isEmpty()) {
                    LogWriter.logSync(TAG, "fakeLike: skip empty wxid nick=" + nick);
                    continue;
                }
                boolean dup = false;
                for (Object o : list) {
                    if (wxid.equals(XposedHelpers.getObjectField(o, "d"))) { dup = true; break; }
                }
                if (dup) continue;

                Object it = XposedHelpers.newInstance(uf6);
                XposedHelpers.setObjectField(it, "d", wxid);
                XposedHelpers.setObjectField(it, "e", nick != null ? nick : wxid);
                XposedHelpers.setObjectField(it, "h", "");
                XposedHelpers.setObjectField(it, "o", "");
                XposedHelpers.setObjectField(it, "g", 5);          // 5=陌生人赞；1=好友赞
                XposedHelpers.setObjectField(it, "f", 0);
                XposedHelpers.setObjectField(it, "t", 0);
                int now = (int) (System.currentTimeMillis() / 1000);
                XposedHelpers.setObjectField(it, "i", now);
                XposedHelpers.setObjectField(it, "m", now);
                list.addFirst(it);
                added++;
            }
            if (added == 0) {
                LogWriter.logSync(TAG, "fakeLike: all dup/empty existing="
                        + (list == null ? -1 : list.size()) + " cls=" + snsInfo.getClass().getName());
                return 0;
            }

            int cnt = list.size();
            // 计数字段名可能随版本漂移，逐项 best-effort
            try { XposedHelpers.setObjectField(snsObj, "LikeCount", cnt); } catch (Throwable ignored) {}
            try { XposedHelpers.setObjectField(snsObj, "LikeUserListCount", cnt); } catch (Throwable ignored) {}

            byte[] out = (byte[]) XposedHelpers.callMethod(snsObj, "toByteArray");
            if (out == null || out.length == 0) {
                LogWriter.logSync(TAG, "fakeLike: toByteArray empty");
                return 0;
            }

            // ★★★ 必须调 setAttrBuf()：它同时更新 contentByteMd5（缓存 KEY）→ 时间线缓存失效
            XposedHelpers.callMethod(snsInfo, "setAttrBuf", (Object) out);
            XposedHelpers.setObjectField(snsInfo, "field_attrBuf", out);   // 双保险

            // v3.0.158 诊断：立即回读 field_attrBuf 重新 parseFrom，确认写入是否落到
            // 时间线实际读取的同一 SnsInfo。reread 数 == cnt 说明数据层确实生效，
            // 若界面仍不刷新则问题在渲染/绑定层，而非写入层。
            try {
                byte[] check = (byte[]) XposedHelpers.getObjectField(snsInfo, "field_attrBuf");
                Object so2 = XposedHelpers.callMethod(
                        XposedHelpers.newInstance(soCls), "parseFrom", (Object) check);
                Object lul2 = XposedHelpers.getObjectField(so2, "LikeUserList");
                int n2 = (lul2 instanceof java.util.List) ? ((java.util.List<?>) lul2).size() : -1;
                String lid = "";
                try { lid = String.valueOf(XposedHelpers.callMethod(snsInfo, "getLocalid")); } catch (Throwable ignored) {}
                LogWriter.logSync(TAG, "fakeLike VERIFY reread localId=" + lid + " likeCount=" + n2);
            } catch (Throwable t2) {
                LogWriter.logSync(TAG, "fakeLike VERIFY err: " + t2);
            }

            // v3.0.157：不再落库(store.w4)，纯内存改写，避免污染持久化数据/原生崩溃。
            LogWriter.logSync(TAG, "fakeLike(in-memory,no-persist) OK added=" + added + " likeCount=" + cnt);
            return added;
        } catch (Throwable t) {
            LogWriter.logSync(TAG, "fakeLike err: " + t);
            return 0;
        }
    }

    /** 刷新时间线（SnsTimelineRefreshEvent + SnsCommentUpdateEvent + 列表 Adapter 三重兜底）。
     *  v3.0.141：修复「设置后不生效」——旧实现里 setIntField(getObjectField(ev,"g"),"a",1)
     *  若因混淆字段漂移抛异常，同一 try 块内的 ev.e() 不会执行，导致事件根本没发出去、
     *  数据已落库但界面不刷新。现在拆分 try：字段设置失败仅告警，事件发送必然执行。
     *  v3.0.142b：新增第三重——直接对当前朋友圈页面的列表 Adapter notifyDataSetChanged。
     *  新版 ImproveSnsTimelineUI 可能不订阅 SnsTimelineRefreshEvent，事件发了但列表不重绘。 */
    public static void refresh() {
        if (!diag("refresh", 800)) {
            LogWriter.logSync(TAG, "refresh skipped (throttled)");
            return;
        }
        refreshNoThrottle();
    }

    /** v3.0.163：无节流刷新（取消秒集赞使用，避免被注入后的 refresh 吞掉）。 */
    private static void forceRefresh() {
        LogWriter.logSync(TAG, "forceRefresh begin");
        refreshNoThrottle();
    }

    private static void refreshNoThrottle() {
        LogWriter.logSync(TAG, "refresh begin");
        try {
            Class<?> e = XposedHelpers.findClass(
                    "com.tencent.mm.autogen.events.SnsTimelineRefreshEvent", sCl);
            Object ev = XposedHelpers.newInstance(e);
            try {
                Object g = XposedHelpers.getObjectField(ev, "g");
                if (g != null) XposedHelpers.setIntField(g, "a", 1);
            } catch (Throwable t) {
                LogWriter.log(TAG, "refresh set g.a err(ignore): " + t);
            }
            XposedHelpers.callMethod(ev, "e");
            LogWriter.logSync(TAG, "refresh event1 sent");
        } catch (Throwable t) {
            LogWriter.log(TAG, "refresh SnsTimelineRefreshEvent err: " + t);
        }
        try {
            Class<?> e = XposedHelpers.findClass(
                    "com.tencent.mm.autogen.events.SnsCommentUpdateEvent", sCl);
            XposedHelpers.callMethod(XposedHelpers.newInstance(e), "e");
            LogWriter.logSync(TAG, "refresh event2 sent");
        } catch (Throwable t) {
            LogWriter.log(TAG, "refresh SnsCommentUpdateEvent err: " + t);
        }
        refreshSnsListAdapters();
    }

    /** 直接对当前 SNS 页面 View 树中的列表 Adapter 调用 notifyDataSetChanged。
     *  新版 ImproveSnsTimelineUI 不订阅旧事件时，这是唯一能立即重绘点赞列表的路径。 */
    private static void refreshSnsListAdapters() {
        try {
            Activity act = MainHook.currentActivity();
            if (act == null) return;
            String name = act.getClass().getName();
            if (!name.contains("SnsTimelineUI") && !name.contains("SnsCommentDetailUI")
                    && !name.contains("SnsWsFoldDetailUI")) {
                return;
            }
            android.view.View decor = act.getWindow() != null
                    ? act.getWindow().getDecorView() : null;
            if (decor == null) return;
            notifyAdapters(decor, 0);
            LogWriter.logSync(TAG, "refresh SNS list adapters in " + name);
        } catch (Throwable t) {
            LogWriter.log(TAG, "refreshSnsListAdapters err: " + t);
        }
    }

    private static void notifyAdapters(android.view.View v, int depth) {
        if (v == null || depth > 40) return;
        try {
            Object adapter = null;
            if (v instanceof android.widget.AbsListView) {
                adapter = ((android.widget.AbsListView) v).getAdapter();
            } else {
                try {
                    java.lang.reflect.Method m = v.getClass().getMethod("getAdapter");
                    if (m != null) adapter = m.invoke(v);
                } catch (Throwable ignored) {}
            }
            if (adapter != null) {
                try {
                    int n = -1;
                    try {
                        java.lang.reflect.Method gm = adapter.getClass().getMethod("getItemCount");
                        n = ((Number) gm.invoke(adapter)).intValue();
                    } catch (Throwable ignored) {}
                    LogWriter.logSync(TAG, "notifyAdapters -> " + adapter.getClass().getName() + " itemCount=" + n);
                    adapter.getClass().getMethod("notifyDataSetChanged").invoke(adapter);
                } catch (Throwable t) {
                    LogWriter.logSync(TAG, "notifyAdapters err: " + t);
                }
            }
        } catch (Throwable ignored) {}
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                notifyAdapters(g.getChildAt(i), depth + 1);
            }
        }
    }

    // ================================================================
    // 工具
    // ================================================================

    /** 优先备注名，其次昵称，最后 wxid。 */
    private static String displayName(ContactCard c) {
        if (c == null) return null;
        if (c.conRemark != null && !c.conRemark.isEmpty()) return c.conRemark;
        if (c.nickname != null && !c.nickname.isEmpty()) return c.nickname;
        return c.username;
    }

    private static Activity getActivity(View v) {
        for (Context c = v != null ? v.getContext() : null; c != null; c = c.getApplicationContext()) {
            if (c instanceof Activity) return (Activity) c;
            if (c instanceof android.content.ContextWrapper) {
                Context base = ((android.content.ContextWrapper) c).getBaseContext();
                if (base != null && base != c) {
                    if (base instanceof Activity) return (Activity) base;
                }
            }
        }
        return null;
    }

    private static void toast(final View v, final String msg) {
        if (v == null || v.getContext() == null) return;
        final Context ctx = v.getContext().getApplicationContext();
        sH.post(() -> {
            try { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show(); }
            catch (Throwable ignored) {}
        });
    }

    private static void toast(final Activity act, final String msg) {
        if (act == null) return;
        final Context ctx = act.getApplicationContext();
        sH.post(() -> {
            try { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show(); }
            catch (Throwable ignored) {}
        });
    }

    /** 具名类直接 loadClass；混淆短名（如 pc5.uf6 / eu5.s0）loadClass 失败时回退 DexKit 按简名解析。 */
    private static Class<?> loadClass(String name) {
        if (name == null || name.isEmpty() || sCl == null) return null;
        for (ClassLoader l : HookUtil.candidateLoaders(sCl)) {
            try { return l.loadClass(name); } catch (Throwable ignored) {}
        }
        try {
            ClassData cd = DexKitHelper.findClassByName(sCl, name);
            if (cd != null && cd.getName() != null) {
                for (ClassLoader l : HookUtil.candidateLoaders(sCl)) {
                    try { return l.loadClass(cd.getName()); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}