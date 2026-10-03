package com.leshao.v3.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextMenu;
import android.view.View;

import com.leshao.v3.ContextManager;
import com.leshao.v3.ContactRepository;
import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ContactCard;
import com.leshao.v3.ui.ContactPickerDialog;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
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
        LogWriter.log(TAG, "install fake-like (v3.0.139)");
        // ① 注入长按菜单（MMPopupMenu 所有 SNS 长按菜单的唯一汇聚点）
        hookMenuRegister("h", 5, 1);
        hookMenuRegister("g", 6, 2);
        hookMenuRegister("j", 3, 1);
        // ② 拦截菜单点击（朋友圈主分发器 + 气泡菜单分发器）
        hookMenuSelected("com.tencent.mm.plugin.sns.ui.listener.c");
        hookMenuSelected("com.tencent.mm.plugin.sns.ui.k1");
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
    // 秒集赞主流程
    // ================================================================

    private static void handleFakeLike(XC_MethodHook.MethodHookParam p) {
        Object listener = p.thisObject;
        View anchor = null;
        String localId = null;
        try { anchor = (View) XposedHelpers.getObjectField(listener, "e"); } catch (Throwable ignored) {}
        try { localId = (String) XposedHelpers.getObjectField(listener, "f"); } catch (Throwable ignored) {}
        final View fAnchor = anchor;
        final Object snsInfo = localId != null ? getSnsInfo(localId) : null;
        if (snsInfo == null) {
            LogWriter.log(TAG, "秒集赞：未定位到动态 localId=" + localId);
            toast(fAnchor, "秒集赞：未定位到动态");
            return;
        }
        final Activity act = getActivity(fAnchor);
        if (act == null) {
            toast(fAnchor, "秒集赞：未找到页面");
            return;
        }
        final String[] items = {
                "秒集赞（随机 " + getCount() + " 位好友）",
                "选择联系人秒集赞",
                "取消"
        };
        new AlertDialog.Builder(act)
                .setTitle("朋友圈秒集赞")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        fakeLikeRandom(act, snsInfo);
                    } else if (which == 1) {
                        ContactPickerDialog.show(act, "", ContactPickerDialog.MODE_FRIEND,
                                (Set<String> wxids, String display) -> {
                                    if (wxids == null || wxids.isEmpty()) {
                                        toast(fAnchor, "未选择联系人");
                                        return;
                                    }
                                    fakeLikeWithWxids(act, snsInfo, wxids);
                                },
                                null, "选择集赞联系人");
                    }
                })
                .show();
    }

    /** 从好友列表随机取 count 位，伪造点赞并刷新。 */
    private static void fakeLikeRandom(Activity act, Object snsInfo) {
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
        doFakeLike(act, snsInfo, pairs);
    }

    /** 对选中的联系人伪造点赞并刷新。 */
    private static void fakeLikeWithWxids(Activity act, Object snsInfo, Set<String> wxids) {
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
        doFakeLike(act, snsInfo, pairs);
    }

    private static void doFakeLike(Activity act, Object snsInfo, List<String[]> pairs) {
        int added = fakeLike(snsInfo, pairs);
        if (added <= 0) {
            toast(act, "秒集赞未生效（可能已全部在点赞列表）");
            return;
        }
        refresh();
        toast(act, "已为 " + added + " 位联系人伪造点赞");
        LogWriter.log(TAG, "fakeLike added=" + added + " total=" + snsInfo);
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

    /**
     * 本地伪造 LikeUserList。
     *
     * @return 实际新增的点赞数（已在列表中 / 参数异常返回 0）
     */
    public static int fakeLike(Object snsInfo, List<String[]> pairs) {
        try {
            if (snsInfo == null || pairs == null || pairs.isEmpty()) return 0;
            byte[] attr = (byte[]) XposedHelpers.getObjectField(snsInfo, "field_attrBuf");
            if (attr == null || attr.length == 0) return 0;

            Class<?> soCls = XposedHelpers.findClass("com.tencent.mm.protocal.protobuf.SnsObject", sCl);
            // ★★ parseFrom 是实例方法、返回新对象（不能丢返回值）
            Object snsObj = XposedHelpers.callMethod(
                    XposedHelpers.newInstance(soCls), "parseFrom", (Object) attr);
            if (snsObj == null) return 0;

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
                XposedHelpers.setObjectField(it, "g", 5);          // 5=陌生人赞；1=好友赞
                XposedHelpers.setObjectField(it, "f", 0);
                XposedHelpers.setObjectField(it, "t", 0);
                int now = (int) (System.currentTimeMillis() / 1000);
                XposedHelpers.setObjectField(it, "i", now);
                XposedHelpers.setObjectField(it, "m", now);
                list.addFirst(it);
                added++;
            }
            if (added == 0) return 0;

            int cnt = list.size();
            XposedHelpers.setObjectField(snsObj, "LikeCount", cnt);
            XposedHelpers.setObjectField(snsObj, "LikeUserListCount", cnt);

            byte[] out = (byte[]) XposedHelpers.callMethod(snsObj, "toByteArray");
            if (out == null || out.length == 0) return 0;

            // ★★★ 必须调 setAttrBuf()：它同时更新 contentByteMd5（缓存 KEY）
            XposedHelpers.callMethod(snsInfo, "setAttrBuf", (Object) out);
            XposedHelpers.setObjectField(snsInfo, "field_attrBuf", out);   // 双保险

            Class<?> p4 = XposedHelpers.findClass("com.tencent.mm.plugin.sns.model.p4", sCl);
            Object store = XposedHelpers.callMethod(
                    XposedHelpers.callStaticMethod(p4, "jj"), "Jj");
            XposedHelpers.callMethod(store, "w4", snsInfo);        // 落库
            LogWriter.log(TAG, "fakeLike OK added=" + added + " likeCount=" + cnt);
            return added;
        } catch (Throwable t) {
            LogWriter.log(TAG, "fakeLike err: " + t);
            return 0;
        }
    }

    /** 刷新时间线（SnsTimelineRefreshEvent + SnsCommentUpdateEvent 三重兜底）。 */
    public static void refresh() {
        try {
            Class<?> e = XposedHelpers.findClass(
                    "com.tencent.mm.autogen.events.SnsTimelineRefreshEvent", sCl);
            Object ev = XposedHelpers.newInstance(e);
            XposedHelpers.setIntField(XposedHelpers.getObjectField(ev, "g"), "a", 1);
            XposedHelpers.callMethod(ev, "e");
        } catch (Throwable ignore) {}
        try {
            Class<?> e = XposedHelpers.findClass(
                    "com.tencent.mm.autogen.events.SnsCommentUpdateEvent", sCl);
            XposedHelpers.callMethod(XposedHelpers.newInstance(e), "e");
        } catch (Throwable ignore) {}
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