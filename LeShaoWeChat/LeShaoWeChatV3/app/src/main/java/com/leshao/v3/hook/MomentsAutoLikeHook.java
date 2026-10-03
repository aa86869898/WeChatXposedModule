package com.leshao.v3.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ui.ContactPickerDialog;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 朋友圈右上角注入「自动点赞」菜单 + 自动点赞 —— 依据
 * 《朋友圈右上角注入和自动点赞WeChat_Moments_TopRight_Menu_AutoLike_Reverse.md》。
 *
 * <p>要点：</p>
 * <ul>
 *   <li>注入点：{@code ImproveSnsTimelineUI.onCreateOptionsMenu(Menu)} 的 <b>before</b>，
 *       菜单项经 {@code mController.g0(menu)} 渲染到微信自定义 ActionBar 右上角。</li>
 *   <li>枚举：Hook {@code lk4.g.W7(SnsInfo, tf5.b)} 收集时间线已加载的 {@code SnsInfo}。</li>
 *   <li>点赞：反射调用 {@code com.tencent.mm.plugin.sns.model.h6.p(发布者wxid, 5, null, SnsInfo, scene)}，
 *       即 {@code opType=5} 的空内容 {@code SnsComment}。</li>
 * </ul>
 */
public final class MomentsAutoLikeHook {

    private static final String TAG = "MomentsAutoLike";
    private static final int MENU_ID = 0x990011;

    public static final String K_ENABLED = "ls_moments_like_enabled";
    public static final String K_SELECTED = "ls_moments_like_selected";

    private static final String UI_CLASS = "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI";
    private static final String BIND_CLASS = "lk4.g";
    private static final String SERVER_CLASS = "com.tencent.mm.plugin.sns.model.h6";
    private static final String SNS_INFO = "com.tencent.mm.plugin.sns.storage.SnsInfo";

    private static volatile ClassLoader sCl;
    private static volatile Activity sTimeline;
    private static final LinkedHashMap<String, Object> sLive = new LinkedHashMap<>();
    private static volatile boolean sRunning;
    private static volatile int sScene = 0;
    private static final Handler sH = new Handler(Looper.getMainLooper());

    private MomentsAutoLikeHook() {}

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

    public static Set<String> getSelected() {
        Set<String> out = new LinkedHashSet<>();
        SharedPreferences sp = prefs();
        String s = sp != null ? sp.getString(K_SELECTED, "") : "";
        if (s != null && !s.isEmpty()) {
            for (String p : s.split(",")) {
                String t = p.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out;
    }

    public static void setSelected(Set<String> wxids) {
        StringBuilder sb = new StringBuilder();
        if (wxids != null) {
            for (String w : wxids) {
                if (w == null || w.isEmpty()) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(w);
            }
        }
        SharedPreferences sp = prefs();
        if (sp != null) sp.edit().putString(K_SELECTED, sb.toString()).apply();
        LogWriter.log(TAG, "setSelected " + sb);
    }

    public static int getSelectedCount() {
        return getSelected().size();
    }

    public static boolean isRunning() {
        return sRunning;
    }

    // ================================================================
    // Hook 安装
    // ================================================================

    public static void hook(final ClassLoader cl) {
        sCl = cl;
        try {
            final Class<?> ui = loadClass(cl, UI_CLASS);
            if (ui == null) {
                LogWriter.log(TAG, "ImproveSnsTimelineUI not found, moments hook off");
                return;
            }

            // 记录 Activity 实例
            Method onCreate = findMethod(ui, "onCreate", android.os.Bundle.class);
            if (onCreate != null) {
                XposedBridge.hookMethod(onCreate, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try { sTimeline = (Activity) param.thisObject; } catch (Throwable ignored) {}
                    }
                });
            }

            // 右上角菜单注入（before：让 super → mController.g0 渲染我们的项）
            Method ocom = findMethod(ui, "onCreateOptionsMenu", Menu.class);
            if (ocom != null) {
                XposedBridge.hookMethod(ocom, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!isEnabled()) return;
                            Menu menu = (Menu) param.args[0];
                            MenuItem item = menu.add(0, MENU_ID, 0, "自动点赞");
                            try {
                                item.setIcon(android.R.drawable.ic_menu_more);
                                item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                            } catch (Throwable ignored) {}
                            item.setOnMenuItemClickListener(mi -> {
                                showMenu();
                                return true;
                            });
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "menu inject err: " + t);
                        }
                    }
                });
                LogWriter.log(TAG, "hooked ImproveSnsTimelineUI.onCreateOptionsMenu");
            } else {
                LogWriter.log(TAG, "onCreateOptionsMenu not found");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook ui err: " + t);
        }

        // 枚举：收集时间线已加载的 SnsInfo
        try {
            Class<?> bind = loadClass(cl, BIND_CLASS);
            if (bind != null) {
                int n = 0;
                for (Method m : bind.getDeclaredMethods()) {
                    if (!"W7".equals(m.getName()) || m.getParameterTypes().length != 2) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            collect(param.args[0]);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // v3.0.131: 绑定完成后再次收集（W7 内部可能替换/填充 SnsInfo 字段）
                            collect(param.args[0]);
                        }
                    });
                    n++;
                    LogWriter.log(TAG, "hooked lk4.g.W7 overload " + m.toGenericString());
                }
                LogWriter.log(TAG, "hooked lk4.g.W7 x" + n);
            } else {
                LogWriter.log(TAG, "lk4.g not found (enumeration off)");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook bind err: " + t);
        }

        // 抓真实 comment_scene（手动点赞时上报一次）
        try {
            Class<?> server = loadClass(cl, SERVER_CLASS);
            if (server != null) {
                for (Method m : server.getDeclaredMethods()) {
                    if (!"p".equals(m.getName())) continue;
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length != 5 || pts[1] != int.class) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                sScene = (int) param.args[4];
                                LogWriter.log(TAG, "real h6.p scene=" + sScene
                                        + " op=" + param.args[1] + " to=" + param.args[0]);
                            } catch (Throwable ignored) {}
                        }
                    });
                }
                LogWriter.log(TAG, "hooked h6.p(scene)");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook server err: " + t);
        }
    }

    private static void collect(Object info) {
        if (info == null) return;
        String cn = info.getClass().getName();
        if (!cn.equals(SNS_INFO) && !cn.endsWith("storage.SnsInfo")) return;
        try {
            String id = (String) XposedHelpers.callMethod(info, "getSnsId");
            if (id == null) return;
            synchronized (sLive) {
                boolean fresh = !sLive.containsKey(id);
                sLive.put(id, info);
                if (fresh) {
                    LogWriter.log(TAG, "collect snsId=" + id
                            + " poster=" + XposedHelpers.callMethod(info, "getUserName")
                            + " likeFlag=" + XposedHelpers.callMethod(info, "getLikeFlag"));
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "collect err: " + t);
        }
    }

    // ================================================================
    // 右上角菜单
    // ================================================================

    private static void showMenu() {
        final Activity act = sTimeline;
        if (act == null) {
            LogWriter.log(TAG, "showMenu: no activity");
            return;
        }
        sH.post(() -> {
            try {
                final String[] items = { "选择点赞联系人", "开始自动点赞", "停止自动点赞" };
                new AlertDialog.Builder(act)
                        .setTitle("朋友圈自动点赞")
                        .setItems(items, (DialogInterface d, int which) -> {
                            switch (which) {
                                case 0: pickContacts(act); break;
                                case 1: startAutoLike(act); break;
                                case 2:
                                    sRunning = false;
                                    toast(act, "已停止自动点赞");
                                    break;
                            }
                        })
                        .show();
            } catch (Throwable t) {
                LogWriter.log(TAG, "showMenu err: " + t);
            }
        });
    }

    private static void pickContacts(Activity act) {
        try {
            StringBuilder cur = new StringBuilder();
            for (String w : getSelected()) {
                if (cur.length() > 0) cur.append(',');
                cur.append(w);
            }
            ContactPickerDialog.show(act, cur.toString(), ContactPickerDialog.MODE_FRIEND,
                    (Set<String> wxids, String display) -> {
                        setSelected(wxids);
                        toast(act, "已选择 " + (wxids == null ? 0 : wxids.size()) + " 位联系人");
                    },
                    null, "选择点赞联系人");
        } catch (Throwable t) {
            LogWriter.log(TAG, "pickContacts err: " + t);
        }
    }

    // ================================================================
    // 自动点赞
    // ================================================================

    private static void startAutoLike(final Activity act) {
        if (sRunning) {
            toast(act, "正在自动点赞中…");
            return;
        }
        final Class<?> server = sCl != null ? loadClass(sCl, SERVER_CLASS) : null;
        if (server == null) {
            toast(act, "当前微信版本不支持自动点赞");
            return;
        }
        final List<Object> targets = new ArrayList<>();
        Set<String> sel = getSelected();
        synchronized (sLive) {
            for (Object info : sLive.values()) {
                try {
                    int likeFlag = toInt(XposedHelpers.callMethod(info, "getLikeFlag"));
                    if (likeFlag != 0) continue; // 已赞
                    if (toBool(XposedHelpers.callMethod(info, "isAd"))) continue; // 广告
                    String poster = (String) XposedHelpers.callMethod(info, "getUserName");
                    if (poster == null) continue;
                    if (sel.isEmpty() || sel.contains(poster)) targets.add(info);
                } catch (Throwable ignored) {}
            }
        }
        if (targets.isEmpty()) {
            LogWriter.log(TAG, "no target: sLive=" + sLive.size()
                    + " selected=" + sel.size() + " running=" + sRunning);
            toast(act, "没有可点赞的朋友圈（可能已赞过或未加载）");
            return;
        }
        sRunning = true;
        LogWriter.log(TAG, "start auto like targets=" + targets.size() + " scene=" + sScene);
        toast(act, "开始自动点赞，共 " + targets.size() + " 条");
        doLikeChain(0, targets, server);
    }

    private static void doLikeChain(final int idx, final List<Object> targets, final Class<?> server) {
        if (!sRunning || idx >= targets.size()) {
            sRunning = false;
            return;
        }
        final Object info = targets.get(idx);
        try {
            String poster = (String) XposedHelpers.callMethod(info, "getUserName");
            if (toBool(XposedHelpers.callMethod(info, "isExtFlag"))) {
                // v3.0.131: 特殊态（extFlag）动态走评论式路由 opType=1
                Method m = findCommentLikeMethod(server);
                if (m != null) {
                    m.invoke(null, info, 1, "", 0L, "", Boolean.FALSE, sScene);
                    try { XposedHelpers.callMethod(info, "setLikeFlag", 1); } catch (Throwable ignored) {}
                    LogWriter.log(TAG, "liked(ext) " + safeId(info) + " by " + poster);
                } else {
                    LogWriter.log(TAG, "like(ext) fail: h6.m not found");
                }
            } else {
                Method p = findLikeMethod(server);
                if (p != null) {
                    p.invoke(null, poster, 5, null, info, sScene);
                    try { XposedHelpers.callMethod(info, "setLikeFlag", 1); } catch (Throwable ignored) {}
                    LogWriter.log(TAG, "liked " + safeId(info) + " by " + poster
                            + " scene=" + sScene);
                } else {
                    LogWriter.log(TAG, "like fail: h6.p not found");
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "like fail: " + t);
        }
        long delay = 3500 + ThreadLocalRandom.current().nextInt(2500);
        sH.postDelayed(() -> doLikeChain(idx + 1, targets, server), delay);
    }

    /** 反射找 h6.p(String,int,lj4.a,SnsInfo,int)：参数 5 个且第 2 个为 int。
     *  v3.0.131: 优先精确匹配 String,int,*,SnsInfo,int 签名，避免选中错误重载。 */
    private static Method findLikeMethod(Class<?> server) {
        Method fallback = null;
        for (Method m : server.getDeclaredMethods()) {
            if (!"p".equals(m.getName())) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length == 5 && pts[1] == int.class) {
                boolean exact = pts[0] == String.class && pts[4] == int.class
                        && (pts[3].getName().equals(SNS_INFO) || pts[3].getName().endsWith("SnsInfo"));
                if (exact) {
                    m.setAccessible(true);
                    LogWriter.log(TAG, "h6.p matched: " + m.toGenericString()
                            + " static=" + java.lang.reflect.Modifier.isStatic(m.getModifiers()));
                    return m;
                }
                if (fallback == null) {
                    m.setAccessible(true);
                    fallback = m;
                }
            }
        }
        return fallback;
    }

    /** 反射找 h6.m(SnsInfo,int,String,long,String,boolean,int) 评论式路由。 */
    private static Method findCommentLikeMethod(Class<?> server) {
        for (Method m : server.getDeclaredMethods()) {
            if (!"m".equals(m.getName())) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length == 7 && pts[1] == int.class && pts[4] == String.class
                    && pts[5] == boolean.class && pts[6] == int.class) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static String safeId(Object info) {
        try { return (String) XposedHelpers.callMethod(info, "getSnsId"); } catch (Throwable t) { return "?"; }
    }

    // ================================================================
    // 工具
    // ================================================================

    private static int toInt(Object o) {
        return o instanceof Integer ? (Integer) o : 0;
    }

    private static boolean toBool(Object o) {
        return o instanceof Boolean && (Boolean) o;
    }

    private static void toast(final Activity act, final String msg) {
        if (act == null) return;
        sH.post(() -> {
            try { android.widget.Toast.makeText(act, msg, android.widget.Toast.LENGTH_SHORT).show(); }
            catch (Throwable ignored) {}
        });
    }

    private static Class<?> loadClass(ClassLoader cl, String name) {
        if (name == null) return null;
        for (ClassLoader l : HookUtil.candidateLoaders(cl)) {
            try { return l.loadClass(name); } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Method findMethod(Class<?> c, String name, Class<?>... params) {
        if (c == null) return null;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {}
        }
        return null;
    }
}
