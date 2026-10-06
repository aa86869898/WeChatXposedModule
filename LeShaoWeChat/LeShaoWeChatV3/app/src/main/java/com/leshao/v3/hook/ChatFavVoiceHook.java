package com.leshao.v3.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 聊天窗口「+」→ 收藏选择页（FavSelectUI）语音转发。
 *
 * <p>严格按《微信收藏语音转发_终极完整版.md》附录 C 与第四部分完整实现：
 * <ul>
 *   <li>① 放行语音（DB 查询层）：hook {@code gd2.d#v4/m9}，选择页的屏蔽类型集合移除 3，
 *       并把 {@code tc2.x3} 过滤器的布尔字段 a 置 false → 语音条目出现在收藏选择页；</li>
 *   <li>② 单击直接转发：hook {@code FavSelectUI#onItemClick}，type==3 吞掉微信原生
 *       Toast，直接转发给当前聊天（{@code FavSelectUI.W = key_to_user}）；</li>
 *   <li>③ 长按菜单：hook {@code FavSelectUI#onStart} 自挂 ListView 长按监听，
 *       语音条目弹 AlertDialog「转发语音给当前聊天」；</li>
 *   <li>④ 发送复用 {@link FavVoiceForwardHook#sendVoice}（已验证的
 *       {@code TtsVoiceSender.sendViaSceneVoice} 链路）。</li>
 * </ul>
 *
 * <p>字符串锚点（DexKit 优先，兜底当前版本类名）见《聊天加号收藏_DexKit锚点字符串大全.md》。</p>
 */
public final class ChatFavVoiceHook {

    public static final String TAG = "ChatFavVoice";
    public static final String K_ENABLED = "ls_chat_fav_voice_forward";

    // ===== 字符串锚点 + 当前版本兜底类名（锚点文档 A/B/C 组）=====
    private static final String S_FAV_SELECT = "fav total size:%s, limitSize:%s";
    private static final String S_DB_V4 = "getFirstPageList";
    private static final String S_DB_M9 = "[getList] sql = ";
    private static final String S_FILTER = "[FAV_ITEM_TYPE_VOICE] canFilterVoice = true, back";

    private static final String C_FAV_SELECT = "com.tencent.mm.plugin.fav.ui.FavSelectUI";
    private static final String C_DB = "gd2.d";
    private static final String C_FILTER = "tc2.x3";

    private static final int TYPE_VOICE = 3;

    private static volatile boolean sEnabled = false;
    private static volatile boolean sHooked = false;
    private static volatile ClassLoader sCl;

    private ChatFavVoiceHook() {}

    // ---------------- 配置 ----------------

    public static boolean isEnabled() {
        try {
            SharedPreferences sp = ContextManager.getPrefs();
            return sp != null && sp.getBoolean(K_ENABLED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setEnabled(boolean on) {
        try {
            ContextManager.getPrefs().edit().putBoolean(K_ENABLED, on).apply();
        } catch (Throwable ignored) {}
        sEnabled = on;
        LogWriter.log(TAG, "setEnabled=" + on);
    }

    // ---------------- Hook 安装 ----------------

    public static void hook(final ClassLoader cl) {
        sCl = cl;
        try {
            sEnabled = isEnabled();
        } catch (Throwable ignored) {}
        if (sHooked) return;
        Thread t = new Thread(() -> {
            for (int attempt = 0; attempt < 10 && !sHooked; attempt++) {
                try {
                    installHooks(cl);
                    sHooked = true;
                    LogWriter.log(TAG, "hooks installed attempt=" + attempt);
                    return;
                } catch (Throwable e) {
                    LogWriter.log(TAG, "install attempt " + attempt + " failed: " + e.getMessage());
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }
        }, "leshao-chat-fav-voice-hook");
        t.setDaemon(true);
        t.start();
    }

    private static void installHooks(ClassLoader cl) throws Throwable {
        // ① 放行语音（DB 查询层 v4/m9；选择页屏蔽集合中含 3 → 移除，首页无副作用）
        String dbClass = resolveClass(cl, S_DB_V4, C_DB);
        String filterClass = resolveClass(cl, S_FILTER, C_FILTER);
        installDbUnblock(cl, dbClass, filterClass);
        // ② 单击直接转发（FavSelectUI.onItemClick）
        String uiClass = resolveClass(cl, S_FAV_SELECT, C_FAV_SELECT);
        if (uiClass.equals(C_FAV_SELECT) || uiClass.contains("FavSelectUI")) {
            installTap(cl, uiClass);
        } else {
            // DexKit 命中非默认名：对命中类与默认类都尝试
            installTap(cl, uiClass);
            installTap(cl, C_FAV_SELECT);
        }
        // ③ 长按菜单（FavSelectUI.onStart 自挂 ListView 长按监听）
        installLongPress(cl, uiClass);
        if (!uiClass.equals(C_FAV_SELECT)) installLongPress(cl, C_FAV_SELECT);
        LogWriter.log(TAG, "installHooks done");
    }

    /** 字符串锚点解析类名（后台线程调用，DexKit 缓存命中则直接读缓存）。 */
    private static String resolveClass(ClassLoader cl, String anchor, String fallback) {
        try {
            List<String> cs = DexKitHelper.findMethodDeclClassByString(cl, anchor);
            if (cs != null && !cs.isEmpty()) {
                String hit = cs.get(0);
                LogWriter.log(TAG, "resolve '" + anchor + "' -> " + hit);
                if (hit.startsWith("com.tencent.mm.plugin.fav.ui")
                        || hit.startsWith("com.tencent.mm")
                        || (hit.length() > 1 && Character.isLetter(hit.charAt(0)))) {
                    return hit;
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "resolve '" + anchor + "' err: " + t.getMessage());
        }
        return fallback;
    }

    // ---------------- ① 放行语音 ----------------

    private static void installDbUnblock(ClassLoader cl, String dbClass, String filterClass) {
        // v4(int type,int count,List list,Set set,tc2.f5 filter)
        hookQuery(cl, dbClass, "v4", new int[]{4, 5}, 3, 4, filterClass);
        // m9(long updateTime,int count,List list,Set set,tc2.f5 filter)
        hookQuery(cl, dbClass, "m9", new int[]{5, 6}, 3, 4, filterClass);
        LogWriter.log(TAG, "db unblock installed: " + dbClass + " filter=" + filterClass);
    }

    /** 按参数个数匹配查询方法：args[setIdx]=Set（移除 3），args[filterIdx]=x3（a 置 false）。 */
    private static void hookQuery(ClassLoader cl, String dbClass, String methodName,
                                  int[] argCounts, final int setIdx, final int filterIdx,
                                  String filterClass) {
        try {
            for (Class<?> c : HookUtil.loadClasses(cl, dbClass)) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(methodName)) continue;
                    int pc = m.getParameterTypes().length;
                    boolean match = false;
                    for (int n : argCounts) if (n == pc) { match = true; break; }
                    if (!match) continue;
                    m.setAccessible(true);
                    final String cn = c.getName();
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                            if (!sEnabled) return;
                            try {
                            if (setIdx < p.args.length && p.args[setIdx] instanceof Set) {
                                Set<?> set = (Set<?>) p.args[setIdx];
                                if (set.contains(TYPE_VOICE)) {
                                    // 仅选择页屏蔽集合含 3；收藏首页无 3，不受影响
                                    set.remove(TYPE_VOICE);
                                    LogWriter.log(TAG, methodName + " removed type 3 -> "
                                            + set + " in " + cn);
                                    // 行级过滤 x3.a 也只在选择页（set 含 3）时放行，首页不动
                                    if (filterIdx < p.args.length && p.args[filterIdx] != null) {
                                        Object f = p.args[filterIdx];
                                        XposedHelpers.setBooleanField(f, "a", false);
                                    }
                                }
                            }
                            } catch (Throwable ignored) {}
                        }
                    });
                    LogWriter.log(TAG, "hooked " + cn + "." + methodName + " pc=" + pc);
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookQuery " + dbClass + "." + methodName + " err: " + t.getMessage());
        }
    }

    // ---------------- ② 单击直接转发 ----------------

    private static void installTap(ClassLoader cl, String uiClass) {
        XC_MethodHook tapHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                try {
                    if (!sEnabled) return;
                    if (!(p.args[0] instanceof AdapterView)) return;
                    View v = (View) p.args[1];
                    Object holder = v == null ? null : v.getTag();
                    Object item = holder == null ? null : getObjField(holder, "a");
                    if (item == null || FavVoiceForwardHook.getType(item) != TYPE_VOICE) return;
                    // 吞掉微信原生"收藏的语音消息不能转发" Toast
                    p.setResult(null);
                    LogWriter.log(TAG, "onItemClick voice intercepted localId="
                            + FavVoiceForwardHook.getLocalId(item));
                    Activity act = (Activity) p.thisObject;
                    String toUser = getToUser(act);   // key_to_user（不依赖混淆字段 W）
                    if (toUser == null || toUser.isEmpty()) {
                        toast("未取到当前聊天，请从聊天页进入");
                        return;
                    }
                    forwardVoice(item, toUser, act);
                } catch (Throwable t) {
                    LogWriter.log(TAG, "tap err: " + t.getMessage());
                }
            }
        };
        hookMethodByName(cl, uiClass, "onItemClick", tapHook, 4);
        LogWriter.log(TAG, "tap hooked: " + (uiClass.equals(C_FAV_SELECT) ? "default" : uiClass));
    }

    // ---------------- ③ 长按菜单 ----------------

    private static void installLongPress(ClassLoader cl, String uiClass) {
        XC_MethodHook onStartHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                try {
                    if (!sEnabled) return;
                    final Activity act = (Activity) p.thisObject;
                    final ListView lv = findListView(act);
                    if (lv == null) return;
                    final AdapterView.OnItemLongClickListener prev = lv.getOnItemLongClickListener();
                    lv.setOnItemLongClickListener((parent, view, pos, id) -> {
                        try {
                            if (!sEnabled) return prev != null
                                    && prev.onItemLongClick(parent, view, pos, id);
                            Object item = itemAt(lv, pos);
                            if (item == null || FavVoiceForwardHook.getType(item) != TYPE_VOICE) {
                                return prev != null && prev.onItemLongClick(parent, view, pos, id);
                            }
                            // 未下载的语音不弹
                            String toUser = getToUser(act);
                            if (toUser == null || toUser.isEmpty()) {
                                toast("未取到当前聊天，请从聊天页进入");
                                return true;
                            }
                            final Object fav = item;
                            new AlertDialog.Builder(act)
                                    .setTitle("语音转发")
                                    .setMessage("转发该语音到当前聊天？")
                                    .setPositiveButton("转发", (d, w) -> forwardVoice(fav, toUser, act))
                                    .setNegativeButton("取消", null)
                                    .show();
                            return true;
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "long press err: " + t.getMessage());
                            return prev != null && prev.onItemLongClick(parent, view, pos, id);
                        }
                    });
                    LogWriter.log(TAG, "long press listener installed on " + act.getClass().getName());
                } catch (Throwable t) {
                    LogWriter.log(TAG, "onStart err: " + t.getMessage());
                }
            }
        };
        hookMethodByName(cl, uiClass, "onStart", onStartHook, 0);
        LogWriter.log(TAG, "longPress hooked");
    }

    // ---------------- 转发发送 ----------------

    /** 复用 FavVoiceForwardHook 已验证的发送链路（v61.d1.h + copy + v61.d1.u + 上传队列 + 上屏）。 */
    private static void forwardVoice(Object favItem, String toUser, Activity ctx) {
        final long localId = FavVoiceForwardHook.getLocalId(favItem);
        LogWriter.log(TAG, "forwardVoice localId=" + localId + " to=" + toUser);
        new Thread(() -> FavVoiceForwardHook.sendVoice(localId, favItem, toUser),
                "leshao-chat-fav-voice-send").start();
    }

    // ---------------- 工具 ----------------

    /** 自挂长按用：取选择页列表第 pos 组条目（adapter.i(pos - headerViewsCount)）。 */
    private static Object itemAt(ListView lv, int pos) {
        try {
            Object adapter = lv.getAdapter();
            int header = lv.getHeaderViewsCount();
            return XposedHelpers.callMethod(adapter, "i", pos - header);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 从 FavSelectUI（继承 FavBaseUI）查找收藏 ListView（字段 h=favoriteLV）。 */
    private static ListView findListView(Activity act) {
        Object lv = getObjField(act, "h");
        if (lv instanceof ListView) return (ListView) lv;
        // 兜底：全字段扫描 ListView
        for (java.lang.reflect.Field f : act.getClass().getDeclaredFields()) {
            try {
                f.setAccessible(true);
                Object v = f.get(act);
                if (v instanceof ListView) return (ListView) v;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object getObjField(Object o, String name) {
        if (o == null) return null;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 取当前聊天目标：优先 intent 的 key_to_user（文档 A4，不依赖混淆字段），
     *  其次字段 W（文档 C.2），保证换包/重命名后仍能取到。 */
    private static String getToUser(Activity act) {
        try {
            android.content.Intent it = act.getIntent();
            if (it != null) {
                String u = it.getStringExtra("key_to_user");
                if (u != null && !u.isEmpty()) return u;
            }
        } catch (Throwable ignored) {}
        Object w = getObjField(act, "W");
        return w instanceof String ? (String) w : null;
    }

    /** 在类上按名称（及指定参数个数）hook 方法。 */
    private static void hookMethodByName(ClassLoader cl, String clsName, String methodName,
                                         XC_MethodHook hook, int paramCount) {
        try {
            for (Class<?> c : HookUtil.loadClasses(cl, clsName)) {
                boolean any = false;
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(methodName)) continue;
                    if (paramCount >= 0 && m.getParameterTypes().length != paramCount) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, hook);
                    any = true;
                    LogWriter.log(TAG, "hooked " + c.getName() + "." + methodName
                            + " pc=" + m.getParameterTypes().length);
                }
                if (!any) {
                    // 方法可能在继承链（FavBaseUI/FavSelectUI onStart）
                    for (Method m : c.getMethods()) {
                        if (!m.getName().equals(methodName)) continue;
                        if (paramCount >= 0 && m.getParameterTypes().length != paramCount) continue;
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, hook);
                        LogWriter.log(TAG, "hooked(inherited) " + c.getName() + "." + methodName);
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookMethodByName " + clsName + "." + methodName
                    + " err: " + t.getMessage());
        }
    }

    private static void toast(final String msg) {
        try {
            final Context ctx = ContextManager.getAppContext();
            if (ctx == null) return;
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }
}