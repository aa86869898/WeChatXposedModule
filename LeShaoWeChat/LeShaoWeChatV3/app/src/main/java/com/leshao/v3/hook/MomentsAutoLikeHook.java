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

import java.lang.ref.WeakReference;
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
 * 《朋友圈右上角注入和自动点赞WeChat_Moments_TopRight_Menu_AutoLike_Reverse.md》
 * 与《朋友圈自动点赞修复WeChat_Moments_AutoLike_Reverse.md》。
 *
 * <p>要点：</p>
 * <ul>
 *   <li>注入点：{@code ImproveSnsTimelineUI.onCreateOptionsMenu(Menu)} 的 <b>before</b>，
 *       菜单项经 {@code mController.g0(menu)} 渲染到微信自定义 ActionBar 右上角。</li>
 *   <li>枚举（v3.0.150 修复）：Hook {@code jk4.p.<init>()（无参构造，必挂）} 收集时间线已加载的
 *       行 bean，再经 {@code Z0()} 取 {@code SnsInfo}。弃用 {@code lk4.g.W7(SnsInfo, tf5.b)}——
 *       其第二参数类型串易解析失败导致 hook 挂空、集合为空，是此前"没效果"的头号根因
 *       （《WeChat_Moments_AutoLike_Reverse.md》§8）。W7 仅保留作兜底枚举。</li>
 *   <li>点赞：反射调用 {@code com.tencent.mm.plugin.sns.model.h6.n(SnsInfo, 1, null, 0)}
 *       —— 标准路由立即发送 {@code mmsnscomment} CGI（Cmd=0xD5）。
 *       旧实现 {@code h6.p(wxid, 5, null, SnsInfo, scene)} 走 strangers 路由，只入队不发送，已废弃。</li>
 * </ul>
 */
public final class MomentsAutoLikeHook {

    private static final String TAG = "MomentsAutoLike";
    private static final int MENU_ID = 0x990011;

    public static final String K_ENABLED = "ls_moments_like_enabled";
    public static final String K_SELECTED = "ls_moments_like_selected";
    public static final String K_REFRESH_ENABLED = "ls_moments_like_refresh_enabled";
    public static final String K_REFRESH_MINUTES = "ls_moments_like_refresh_minutes";

    private static final String UI_CLASS = "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI";
    private static final String BIND_CLASS = "lk4.g";
    private static final String BEAN_CLASS = "jk4.p";
    private static final String SERVER_CLASS = "com.tencent.mm.plugin.sns.model.h6";
    private static final String SNS_INFO = "com.tencent.mm.plugin.sns.storage.SnsInfo";
    private static final String DATA_UIC = "com.tencent.mm.plugin.sns.ui.improve.ImproveDataUIC";
    private static final int DEFAULT_REFRESH_MIN = 5;

    private static volatile ClassLoader sCl;
    private static volatile Activity sTimeline;
    private static final LinkedHashMap<String, Object> sLive = new LinkedHashMap<>();
    /** ImproveDataUIC 实例（弱引用，用于定时原生刷新 §7）；低版本可能无此类，置 null 即定时刷新关闭。 */
    private static volatile WeakReference<Object> sDataUIC;
    private static volatile boolean sRefreshTimerRunning;
    private static final Runnable sRefreshTimer = new Runnable() {
        @Override public void run() {
            if (!isRefreshEnabled()) { sRefreshTimerRunning = false; return; }
            try {
                triggerNativeRefresh();
            } catch (Throwable ignored) {}
            if (isRefreshEnabled()) {
                sH.postDelayed(this, refreshIntervalMs());
            }
        }
    };
    private static volatile boolean sRunning;
    private static volatile boolean sJ7Hooked;
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

    // ---------------- 定时原生刷新（文档 §7） ----------------

    public static boolean isRefreshEnabled() {
        SharedPreferences sp = prefs();
        return sp != null && sp.getBoolean(K_REFRESH_ENABLED, false);
    }

    public static void setRefreshEnabled(boolean on) {
        SharedPreferences sp = prefs();
        if (sp != null) sp.edit().putBoolean(K_REFRESH_ENABLED, on).apply();
        LogWriter.log(TAG, "setRefreshEnabled " + on);
        if (on) startRefreshTimer(); else sRefreshTimerRunning = false;
    }

    public static int getRefreshMinutes() {
        SharedPreferences sp = prefs();
        return sp != null ? sp.getInt(K_REFRESH_MINUTES, DEFAULT_REFRESH_MIN) : DEFAULT_REFRESH_MIN;
    }

    public static void setRefreshMinutes(int minutes) {
        if (minutes < 1) minutes = 1;
        SharedPreferences sp = prefs();
        if (sp != null) sp.edit().putInt(K_REFRESH_MINUTES, minutes).apply();
        LogWriter.log(TAG, "setRefreshMinutes " + minutes);
    }

    /** 定时刷新循环间隔（毫秒）。§7 要求 ≥2~5 分钟以降低风控。 */
    public static long refreshIntervalMs() {
        return getRefreshMinutes() * 60_000L;
    }

    /* 启动定时刷新循环（须在详情页开启时调用；无 ImproveDataUIC 实例则自动降级跳过）。 */
    static void startRefreshTimer() {
        if (isRefreshEnabled() && !sRefreshTimerRunning) {
            sRefreshTimerRunning = true;
            sH.postDelayed(sRefreshTimer, refreshIntervalMs());
            LogWriter.log(TAG, "refresh timer started interval=" + getRefreshMinutes() + "min");
        }
    }

    /**
     * 参考文档 §7：复用微信原生下拉刷新（ImproveOverScrollView.a(int)=directShowTopLoading）
     * 触发 ImproveDataUIC.refresh() → 仓库拉取 → 每行重建 jk4.p / 触发 lk4.g.W7 → 新 SnsInfo 进集合。
     * 比模拟手势 / 自打网络稳定。
     */
    static void triggerNativeRefresh() {
        Object duic = sDataUIC != null ? sDataUIC.get() : null;
        if (duic == null) {
            LogWriter.log(TAG, "no ImproveDataUIC instance, native refresh skipped");
            return;
        }
        try {
            Object osv = XposedHelpers.callMethod(duic, "getOverScrollView");
            if (osv != null) {
                XposedHelpers.callMethod(osv, "a", 1);
                LogWriter.log(TAG, "native refresh triggered");
            } else {
                LogWriter.log(TAG, "getOverScrollView returned null");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "triggerNativeRefresh err: " + t);
        }
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
                        // v3.0.160：时间线打开后 ImproveDataUIC 类已加载（此前 hook 时未加载）
                        tryHookJ7(cl);
                    }
                });
            }

            // 右上角菜单注入（before：让 super → mController.g0 渲染我们的项）
            // v3.0.132: 菜单始终注入（不再依赖 isEnabled），配置完全在朋友圈右上角完成
            Method ocom = findMethod(ui, "onCreateOptionsMenu", Menu.class);
            if (ocom != null) {
                XposedBridge.hookMethod(ocom, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            // 确保 Activity 实例可靠（onCreate hook 可能因时序未触发）
                            if (param.thisObject instanceof Activity) {
                                sTimeline = (Activity) param.thisObject;
                            }
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

        // 枚举（v3.0.162 重构）：启用从「真实读取路径」收集而非构造期 Z0()。
        // 《WeChat_Moments_AutoLike_Reverse.md》§6 明确：<init> 时绑数据未就绪，
        // 立即调 Z0() 会 materialize 懒加载(l1)并缓存空壳 SnsInfo(field_snsId==0→sns_table_0)，
        // 原生手动点赞 changeLikeStatus→h6.n(pVar.Z0(),1,null,0) 拿到该空壳 → 赞发给 0 → 失败。
        // 故不再 hook jk4.p.<init>→Z0()（v3.0.159 仅收到 sns_table_0 的根因），
        // 改由下方三个「被真实调用才触发」的收集器补位：
        //   ① jk4.p.Z0() after（渲染/交互读真实 SnsInfo 时收集，不过期、poster 非空）
        //   ② lk4.g.W7(args[0]) before+after（数据装载事件，args[0] 为已填充的真实 SnsInfo）
        //   ③ ImproveDataUIC.J7() after → MvvmList.d() 数据副本全量收集
        try {
            Class<?> bean = loadClass(cl, BEAN_CLASS);
            if (bean != null) {
                // ① jk4.p.Z0()(getSnsInfo) after —— 渲染每行必取一次真实绑定后的 SnsInfo
                int z0hooked = 0;
                for (Method z : bean.getDeclaredMethods()) {
                    if (!"Z0".equals(z.getName()) || z.getParameterCount() != 0) continue;
                    Class<?> rt = z.getReturnType();
                    if (rt == null || !rt.getName().endsWith("storage.SnsInfo")) continue;
                    z.setAccessible(true);
                    XposedBridge.hookMethod(z, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object info = param.getResult();
                                if (info != null) collect(info);
                            } catch (Throwable ignored) {}
                        }
                    });
                    z0hooked++;
                }
                LogWriter.log(TAG, "hooked jk4.p.Z0 x" + z0hooked);
            } else {
                LogWriter.log(TAG, "jk4.p not found (enum main off)");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook bean err: " + t);
        }

        // 枚举兜底：lk4.g.W7 收集时间线已加载的 SnsInfo（v3.0.150 起仅作辅助）
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

        // v3.0.151: 枚举增强 —— ImproveDataUIC.J7()(getLiveList) after → MvvmList.d()(数据副本)
        // 全量收集。修复 v3.0.150 仅 jk4.p.<init> 收集到首条(sns_table_0)且 poster=null，
        // 导致自动点赞目标列表为空"没效果"的根因（《WeChat_Moments_AutoLike_Reverse.md》§5/§10）。
        // J7 在时间线数据装载/刷新后返回完整列表副本(d()=new ArrayList(this.o))，遍历安全。
        // v3.0.160: 类在 hook 时可能未加载（日志 "not found"），移到时间线 onCreate 后补装 tryHookJ7。
        tryHookJ7(cl);

        // v3.0.139: 抓真实 comment_scene 改为 Hook h6.n（标准点赞路由，参数 (SnsInfo,int,?,int)）
        try {
            Class<?> server = loadClass(cl, SERVER_CLASS);
            if (server != null) {
                for (Method m : server.getDeclaredMethods()) {
                    if (!"n".equals(m.getName())) continue;
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length != 4 || pts[1] != int.class || pts[3] != int.class) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                sScene = (int) param.args[3];
                                LogWriter.log(TAG, "real h6.n scene=" + sScene
                                        + " likeFlag=" + param.args[1] + " snsId="
                                        + XposedHelpers.callMethod(param.args[0], "getSnsId"));
                            } catch (Throwable ignored) {}
                        }
                    });
                }
                LogWriter.log(TAG, "hooked h6.n(scene)");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook server err: " + t);
        }

        // v3.0.160: 手动正常点赞诊断 —— 真实 UI 入口 ImproveInteractionUtil.changeLikeStatus
        // （mk4.r/mk4.s.onClick 最终都走这里；日志确认原生赞路由与模块互不干扰，只读不拦截）
        try {
            Class<?> iu = loadClass(cl, "com.tencent.mm.plugin.sns.ui.improve.util.ImproveInteractionUtil");
            if (iu != null) {
                int n = 0;
                for (Method m : iu.getDeclaredMethods()) {
                    if (!"changeLikeStatus".equals(m.getName())) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                StringBuilder sb = new StringBuilder("manualLike changeLikeStatus hit args=");
                                for (int i = 0; i < param.args.length; i++) {
                                    Object a = param.args[i];
                                    sb.append(i).append(":")
                                      .append(a == null ? "null" : a.getClass().getSimpleName()).append(" ");
                                }
                                LogWriter.log(TAG, sb.toString());
                            } catch (Throwable ignored) {}
                        }
                    });
                    n++;
                }
                if (n > 0) LogWriter.log(TAG, "hooked changeLikeStatus x" + n);
                else LogWriter.log(TAG, "changeLikeStatus not found on ImproveInteractionUtil");
            } else {
                LogWriter.log(TAG, "ImproveInteractionUtil not found (manual like diag off)");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook changeLikeStatus err: " + t);
        }
    }

    private static void collect(Object info) {
        if (info == null) return;
        String cn = info.getClass().getName();
        if (!cn.equals(SNS_INFO) && !cn.endsWith("storage.SnsInfo")) return;
        try {
            // v3.0.162（《WeChat_Moments_AutoLike_Reverse.md》§5/§11）：★空壳过滤。
            // 空壳 SnsInfo field_snsId==0 → getSnsId()=="sns_table_0"、getUserName()==null，
            // 是"集合只有空壳 → 自动点赞无目标 / h6.n 发给 0 → 服务器不认(手动点赞也失败)"的根因。
            String poster = (String) XposedHelpers.callMethod(info, "getUserName");
            if (poster == null) return;                      // ★过滤空壳(poster=null)
            Object sidO = XposedHelpers.getObjectField(info, "field_snsId");
            long sid = (sidO instanceof Number) ? ((Number) sidO).longValue() : 0L;
            if (sid == 0) return;                            // ★过滤空壳(field_snsId==0)
            if (toBool(XposedHelpers.callMethod(info, "isAd"))) return; // 广告
            String id = "sns_table_" + sid;
            synchronized (sLive) {
                boolean fresh = !sLive.containsKey(id);
                sLive.put(id, info);
                if (fresh) {
                    LogWriter.log(TAG, "collect snsId=" + id
                            + " poster=" + poster
                            + " likeFlag=" + XposedHelpers.callMethod(info, "getLikeFlag"));
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "collect err: " + t);
        }
    }

    /** v3.0.151：从 J7() 返回的 MvvmList 数据副本（d()=getData）全量收集全部行 bean 的 SnsInfo。 */
    private static void collectAllFromLiveList(Object mvvmList) {
        if (mvvmList == null) return;
        try {
            Object data = XposedHelpers.callMethod(mvvmList, "d");
            if (!(data instanceof List)) return;
            for (Object bean : (List<?>) data) {
                if (bean == null) continue;
                try {
                    Object info = XposedHelpers.callMethod(bean, "Z0");
                    collect(info);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "collectAllFromLiveList err: " + t);
        }
    }

    /** v3.0.160：安装 ImproveDataUIC.J7() 枚举 hook。类在 hook() 时可能尚未加载
     *  （日志 "ImproveDataUIC not found"），时间线 onCreate 后会再次调用补装；已装则跳过。 */
    /** v3.0.160：安装 ImproveDataUIC.J7() 枚举 hook。类在 hook() 时可能尚未加载
     *  （日志 "ImproveDataUIC not found"），时间线 onCreate 后会再次调用补装；已装则跳过。
     *  同时捕获 ImproveDataUIC.<init> 实例供文档 §7 定时原生刷新使用。 */
    private static void tryHookJ7(final ClassLoader cl) {
        if (sJ7Hooked) return;
        try {
            final Class<?> duic = loadClass(cl, DATA_UIC);
            if (duic == null) {
                LogWriter.log(TAG, "ImproveDataUIC not found (enum J7 off, will retry on timeline open, refresh disabled)");
                return;
            }
            // 捕获实例（<init>(androidx.appcompat.app.AppCompatActivity)）
            try {
                for (java.lang.reflect.Constructor<?> ctor : duic.getDeclaredConstructors()) {
                    Class<?>[] pts = ctor.getParameterTypes();
                    if (pts.length != 1 || !"androidx.appcompat.app.AppCompatActivity"
                            .equals(pts[0].getName())) continue;
                    ctor.setAccessible(true);
                    XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            sDataUIC = new WeakReference<>(p.thisObject);
                            if (isRefreshEnabled() && !sRefreshTimerRunning) startRefreshTimer();
                            LogWriter.log(TAG, "captured ImproveDataUIC instance for native refresh");
                        }
                    });
                    break;
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "hook ImproveDataUIC.<init> err: " + t);
            }
            int n = 0;
            for (Method m : duic.getDeclaredMethods()) {
                if (!"J7".equals(m.getName()) || m.getParameterCount() != 0) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        collectAllFromLiveList(p.getResult());
                    }
                });
                n++;
            }
            sJ7Hooked = n > 0;
            LogWriter.log(TAG, "hooked ImproveDataUIC.J7 x" + n + " loader=" + HookUtil.loaderName(duic.getClassLoader()));
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook J7 err: " + t);
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
            // v3.0.139: 统一走 h6.n(SnsInfo,1,null,0) 标准路由立即发送。
            // 废弃 h6.p(wxid,5,null,SnsInfo,scene)（strangers 路由只入队不发送）与
            // extFlag 特殊态下的 h6.m 评论式路由（该分支同样基于旧协议假设）。
            // v3.0.160: 顺序对齐《朋友圈自动点赞修复》B.2 与真实入口 mk4.r：
            //   setLikeFlag(1) → l1.d(snsId,info) 写库（LiveDB 观察者触发 rebind）→ h6.n(...) 立即发。
            try { XposedHelpers.callMethod(info, "setLikeFlag", 1); } catch (Throwable ignored) {}
            try {
                Class<?> l1 = loadClass(sCl, "com.tencent.mm.plugin.sns.storage.l1");
                if (l1 != null) {
                    String sidStr = (String) XposedHelpers.callMethod(info, "getSnsId");
                    XposedHelpers.callStaticMethod(l1, "d", sidStr, info);
                    LogWriter.log(TAG, "wrote back l1.d(" + sidStr + ") poster=" + poster);
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "l1.d err: " + t);
            }
            Method n = findStandardLikeMethod(server);
            if (n != null) {
                // §9：h6.n 内部含磁盘写（l1.d→SQLite），对齐 app 放宽 StrictMode 磁盘限制
                try { android.os.StrictMode.allowThreadDiskReads(); } catch (Throwable ignored) {}
                n.invoke(null, info, 1, null, 0);
                LogWriter.log(TAG, "liked " + safeId(info) + " by " + poster
                        + " via h6.n(SnsInfo,1,null,0)");
            } else {
                LogWriter.log(TAG, "like fail: h6.n not found");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "like fail: " + t);
        }
        long delay = 3500 + ThreadLocalRandom.current().nextInt(2500);
        sH.postDelayed(() -> doLikeChain(idx + 1, targets, server), delay);
    }

    /** 反射找 h6.n(SnsInfo,int,?,int)：标准点赞路由（立即发送 mmsnscomment CGI）。
     *  v3.0.139: 由 h6.p(String,int,lj4.a,SnsInfo,int) 改为 h6.n，依据
     *  《朋友圈自动点赞修复WeChat_Moments_AutoLike_Reverse.md》。
     *  优先精确匹配 (SnsInfo,int,*,int) 签名，避免选中错误重载。 */
    private static Method findStandardLikeMethod(Class<?> server) {
        Method fallback = null;
        for (Method m : server.getDeclaredMethods()) {
            if (!"n".equals(m.getName())) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length == 4 && pts[1] == int.class && pts[3] == int.class) {
                boolean exact = pts[0].getName().endsWith("SnsInfo");
                if (exact) {
                    m.setAccessible(true);
                    LogWriter.log(TAG, "h6.n matched: " + m.toGenericString()
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
