package com.leshao.v3.hook;

import android.content.SharedPreferences;
import android.view.MenuItem;
import android.view.View;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ui.VoiceDetailDialog;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.Method;

/**
 * 语音消息详情（v3.0.516 新增）。
 *
 * <p>在语音消息长按菜单中注入「语音详情」按钮，点击后弹出模块自绘的
 * {@link VoiceDetailDialog} 面板：显示时长、快进/快退、拖动进度条播放。</p>
 *
 * <p>实现：</p>
 * <ul>
 *   <li>注入：复用 {@link MessageMenuHook} 的 A 层锚点
 *       {@code com.tencent.mm.ui.chatting.viewitems.o0.a(MMMenu, View, ContextMenuInfo)}，
 *       通过 {@link MessageMenuHook#setBuildListener} 回调拿到已构建菜单和长按的消息 View；
 *       判定消息类型 34（语音）后向菜单 {@code add()} 「语音详情」项。</li>
 *   <li>拦截：hook {@code viewitems.r0.onMMMenuItemSelected(MenuItem,int)}，
 *       命中自定义 itemId 时打开详情面板并吞掉点击。</li>
 * </ul>
 */
public final class VoiceDetailHook {

    private static final String TAG = "VoiceDetail";
    public static final String PREF_ENABLED = "voice_detail_menu";

    /** 自定义菜单 itemId（避开微信全部已知 id，高位 0x2F 段）。 */
    public static final int MENU_ID = 0x2F3A1;

    private static volatile boolean sInstalled;
    private static volatile boolean sResolving;

    private VoiceDetailHook() {
    }

    public static boolean isEnabled() {
        return HookConfig.isEnabled(PREF_ENABLED);
    }

    public static void setEnabled(boolean v) {
        SharedPreferences sp = ContextManager.getPrefs();
        if (sp != null) {
            sp.edit().putBoolean(PREF_ENABLED, v).apply();
        }
    }

    public static boolean isInstalled() {
        return sInstalled;
    }

    public static void hook(final ClassLoader cl) {
        if (sInstalled || sResolving) return;
        sResolving = true;
        try {
            MessageMenuHook.setBuildListener(new MessageMenuHook.BuildListener() {
                @Override
                public void onMenuBuilt(Object menu, Object anchorView) {
                    try {
                        onVoiceDetailMenuBuilt(menu, anchorView);
                    } catch (Throwable ignored) {
                    }
                }
            });
            LogWriter.log(TAG, "build listener registered");
        } catch (Throwable t) {
            LogWriter.log(TAG, "register listener err: " + t);
        }

        // 点击拦截：真实类需 Tinker CL，后台重试
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int attempt = 0; attempt < 8 && !sInstalled; attempt++) {
                    ClassLoader useCL = pickRealLoader(cl, attempt);
                    try {
                        if (installClickIntercept(useCL)) {
                            sInstalled = true;
                            LogWriter.log(TAG, "click intercept installed attempt=" + attempt
                                    + " cl=" + (useCL == null ? "null" : useCL.getClass().getSimpleName()));
                            return;
                        }
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "click install err: " + e.getClass().getSimpleName()
                                + ": " + e.getMessage());
                    }
                    try {
                        Thread.sleep(2000L);
                    } catch (InterruptedException ignored) {
                    }
                }
                if (!sInstalled) {
                    LogWriter.log(TAG, "click intercept give up");
                }
            }
        }, "ls-voicedetail-click");
        t.setDaemon(true);
        t.start();
    }

    private static ClassLoader pickRealLoader(ClassLoader base, int attempt) {
        try {
            ClassLoader tk = VersionCompat.findTinkerClassLoader(base);
            if (isRealLoader(tk)) return tk;
        } catch (Throwable ignored) {
        }
        try {
            ClassLoader cm = ContextManager.getTinkerClassLoader();
            if (isRealLoader(cm)) return cm;
        } catch (Throwable ignored) {
        }
        if (attempt >= 2) {
            try {
                ClassLoader cm = ContextManager.getClassLoader();
                if (cm != null) return cm;
            } catch (Throwable ignored) {
            }
            return base;
        }
        return null;
    }

    private static boolean isRealLoader(ClassLoader cl) {
        if (cl == null) return false;
        String n = cl.getClass().getName();
        return n.contains("DelegateLastClassLoader") || n.contains("Tinker")
                || n.contains("IncrementalClassLoader");
    }

    // ==================== 菜单注入 ====================

    private static void onVoiceDetailMenuBuilt(Object menu, Object anchorObj) {
        if (menu == null || !isEnabled()) return;
        if (!(anchorObj instanceof View)) return;
        View anchor = (View) anchorObj;
        Object tag = anchor.getTag();
        Object e9 = getE9(tag);
        if (e9 == null) return;
        if (msgTypeOf(e9) != 34) return;   // 34 = 语音消息

        // 幂等：已注入则不重复加
        try {
            Object existing = XposedHelpers.callMethod(menu, "findItem", MENU_ID);
            if (existing != null) return;
        } catch (Throwable ignored) {
        }

        boolean ok = false;
        try {
            XposedHelpers.callMethod(menu, "add", 0, MENU_ID, 0, "语音详情");
            ok = true;
        } catch (Throwable t1) {
            try {
                XposedHelpers.callMethod(menu, "add", MENU_ID, "语音详情");
                ok = true;
            } catch (Throwable t2) {
                LogWriter.log(TAG, "menu add fail: " + t2.getMessage());
            }
        }
        if (!ok) return;

        VoiceDetailDialog.setPendingMessage(e9, tag);
        LogWriter.log(TAG, "menu injected 语音详情 id=" + Integer.toHexString(MENU_ID)
                + " msgType=" + msgTypeOf(e9));
    }

    // ==================== 点击拦截 ====================

    private static boolean installClickIntercept(final ClassLoader cl) {
        boolean any = false;
        // A8 锚点：r0#onMMMenuItemSelected —— 唯一收口
        try {
            String anchor = "context item select failed, null dataTag";
            java.util.List<String> sigs = DexKitHelper.findMethodsByString(cl, null, anchor);
            java.util.Set<String> seen = new java.util.HashSet<>();
            if (sigs != null) {
                for (String sig : sigs) {
                    if (sig == null) continue;
                    int lp = sig.indexOf('(');
                    if (lp <= 0) continue;
                    String decl = sig.substring(0, lp);
                    int dot = decl.lastIndexOf('.');
                    if (dot <= 0) continue;
                    String cls = decl.substring(0, dot);
                    String method = decl.substring(dot + 1);
                    String key = cls + "#" + method;
                    if (!seen.add(key)) continue;
                    any |= hookClickOnClass(cl, cls, method);
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "anchor scan err: " + t);
        }

        // 权威类名兜底
        if (!any) {
            any |= hookClickOnClass(cl, "com.tencent.mm.ui.chatting.viewitems.r0",
                    "onMMMenuItemSelected");
        }
        return any;
    }

    private static boolean hookClickOnClass(ClassLoader cl, String cls, String methodName) {
        boolean ok = false;
        for (ClassLoader loader : HookUtil.candidateLoaders(cl)) {
            try {
                Class<?> c = loader.loadClass(cls);
                if (c == null) continue;
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(methodName)) continue;
                    if (m.getParameterCount() != 2) continue;
                    if (!MenuItem.class.isAssignableFrom(m.getParameterTypes()[0])) continue;
                    if (m.getParameterTypes()[1] != int.class) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!isEnabled()) return;
                                MenuItem mi = (MenuItem) param.args[0];
                                if (mi == null || mi.getItemId() != MENU_ID) return;
                                LogWriter.log(TAG, "语音详情 clicked");
                                VoiceDetailDialog.showFromHook();
                                param.setResult(null);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    ok = true;
                    LogWriter.log(TAG, "hooked " + c.getName() + "." + m.getName()
                            + " (" + m.getParameterTypes().length + ")");
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "hookClickOnClass skip " + cls + ": " + t);
            }
        }
        return ok;
    }

    // ==================== 消息工具 ====================

    private static Object getE9(Object msgObj) {
        if (msgObj == null) return null;
        if (msgObj.getClass().getName().contains("storage")) return msgObj;
        try {
            return XposedHelpers.callMethod(msgObj, "c");
        } catch (Throwable ignored) {
        }
        for (String mn : new String[]{"getMsgInfo", "getMsg", "a", "b", "d"}) {
            try {
                Object r = XposedHelpers.callMethod(msgObj, mn);
                if (r != null && r.getClass().getName().contains("storage")) return r;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static int msgTypeOf(Object e9) {
        if (e9 == null) return -1;
        try {
            Object t = XposedHelpers.callMethod(e9, "getType");
            if (t instanceof Integer) return (Integer) t;
        } catch (Throwable ignored) {
        }
        try {
            Object t = XposedHelpers.getObjectField(e9, "field_type");
            if (t instanceof Integer) return (Integer) t;
        } catch (Throwable ignored) {
        }
        return -1;
    }
}