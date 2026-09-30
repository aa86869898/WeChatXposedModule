package com.leshao.v3.hook;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 文档《WeChat_ChatFooter_ButtonBar.md》——聊天窗口输入框上方注入一排快捷按钮。
 *
 * <p>锚点：{@code ChatFooter.onAttachedToWindow()}（已重写，thisObject 即 ChatFooter，
 * getParent() 就绪）。after → 在 ChatFooter 父容器里 insert 一个水平 LinearLayout，
 * 位置为 {@code parent.indexOfChild(footer)}，即输入栏正上方。</p>
 *
 * <p>LayoutParams 按父容器类型动态生成，避免 ClassCastException；按钮点击把文字 append 到输入框。</p>
 */
public final class ChatFooterBarHook {

    private static final String TAG = "ChatFooterBar";

    private static final String CHAT_FOOTER = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter";
    private static final String BASE_FRAGMENT = "com.tencent.mm.ui.chatting.BaseChattingUIFragment";
    private static final int ROW_TAG = 0x7f31abcd;
    private static final int MAX_RETRY = 5;

    private static volatile Class<?> sFooterClass;
    private static volatile java.lang.ref.WeakReference<View> sLastFooter;

    private static final String K_ENABLED = "ls_footer_bar_enabled";

    private ChatFooterBarHook() {}

    public static boolean isEnabled() {
        try {
            // 旧版输入框上方按钮排已移除，本按钮组作为唯一入口默认开启。
            return ContextManager.getPrefs().getBoolean(K_ENABLED, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setEnabled(boolean on) {
        try {
            ContextManager.getPrefs().edit().putBoolean(K_ENABLED, on).apply();
        } catch (Throwable ignored) {}
        LogWriter.log(TAG, "setEnabled " + on);
    }

    public static void hook(ClassLoader cl) {
        try {
            Class<?> footer = findClass(CHAT_FOOTER, cl);
            if (footer == null) {
                LogWriter.log(TAG, "ChatFooter 类未找到，按钮排不可用");
                return;
            }
            sFooterClass = footer;
            int n = 0;
            for (Constructor<?> ctor : footer.getDeclaredConstructors()) {
                try {
                    ctor.setAccessible(true);
                    XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!(param.thisObject instanceof View)) return;
                                final View barHost = (View) param.thisObject;
                                barHost.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                                    @Override
                                    public void onViewAttachedToWindow(View v) {
                                        try {
                                            if (BarState.attachFired++ == 0) {
                                                LogWriter.log(TAG, "attach fired enabled=" + isEnabled());
                                            }
                                            if (!isEnabled()) return;
                                            injectBar(v);
                                        } catch (Throwable t) {
                                            LogWriter.log(TAG, "attach inject err: " + t);
                                        }
                                    }

                                    @Override
                                    public void onViewDetachedFromWindow(View v) {
                                    }
                                });
                            } catch (Throwable t) {
                                LogWriter.log(TAG, "ctor after err: " + t);
                            }
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                }
            }
            LogWriter.log(TAG, "hooked ChatFooter ctor x" + n + " cls=" + footer.getName());
            // 文档方案：仅靠构造器会在「模块加载前输入框已存在」时漏注入，追加
            // Fragment.onResume / Activity.onResume 兜底扫描（与其他输入框功能一致）。
            hookFragmentResume(cl);
        } catch (Throwable e) {
            LogWriter.log(TAG, "hook err: " + e);
        }
    }

    private static final class BarState {
        static int attachFired = 0;
    }

    private static Class<?> findClass(String name, ClassLoader cl) {
        for (ClassLoader l : HookUtil.candidateLoaders(cl)) {
            try {
                return l.loadClass(name);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void injectBar(final View footer) {
        if (footer == null) return;
        ViewParent pp = footer.getParent();
        if (!(pp instanceof ViewGroup)) {
            retryInject(footer, 0);
            return;
        }
        ViewGroup parent = (ViewGroup) pp;
        // 幂等：以「父容器/自身子树里是否已有按钮排 tag」为准，微信复用视图树后仍能补注入。
        if (footer.findViewWithTag(ROW_TAG) != null || parent.findViewWithTag(ROW_TAG) != null) return;

        Context ctx = footer.getContext();
        if (ctx == null) return;

        // 新版：复用模块现有按钮组（音色/群发/语音/AI助手/转发），按钮无背景。
        LinearLayout bar = ChatVoiceSwitchHook.buildActionButtonRow(ctx, true);
        bar.setTag(ROW_TAG);

        // 文档实测：footer 的父容器是自定义 ChattingScrollLayout，直接插兄弟节点不会被其
        // onLayout 计入占位 → 遮挡消息。改为锚定 footer 内「包含输入框的垂直 LinearLayout」，
        // 在其输入行之前插入，使 footer 高度自然增长（与 ChatVoiceSwitchHook 同方案）。
        String target;
        try {
            View edit = findFirstEditText(footer);
            ViewGroup host = null;
            int hostIdx = -1;
            if (edit != null) {
                View child = edit;
                ViewParent p = edit.getParent();
                while (p instanceof ViewGroup && p != footer) {
                    ViewGroup vg = (ViewGroup) p;
                    if (vg instanceof LinearLayout
                            && ((LinearLayout) vg).getOrientation() == LinearLayout.VERTICAL) {
                        host = vg;
                        hostIdx = vg.indexOfChild(child);
                        break;
                    }
                    child = (View) vg;
                    p = vg.getParent();
                }
            }
            if (host != null && hostIdx >= 0) {
                bar.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                host.addView(bar, hostIdx);
                target = "footer内垂直容器 idx=" + hostIdx;
            } else if (footer instanceof ViewGroup) {
                bar.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                ((ViewGroup) footer).addView(bar, 0);
                target = "footer根 idx=0";
            } else {
                bar.setLayoutParams(buildParamsFor(parent));
                int idx = parent.indexOfChild(footer);
                if (idx < 0) idx = parent.getChildCount();
                parent.addView(bar, idx);
                target = "兄弟节点 父容器=" + parent.getClass().getName();
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "注入异常: " + t);
            return;
        }
        footer.requestLayout();
        parent.requestLayout();
        LogWriter.log(TAG, "injected bar " + target + " footer=" + footer.getClass().getName());
    }

    private static void retryInject(final View footer, final int attempt) {
        if (attempt > MAX_RETRY) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (footer.getParent() instanceof ViewGroup) injectBar(footer);
            else retryInject(footer, attempt + 1);
        }, 500L);
    }

    // ---------------- 兜底：Fragment / Activity onResume 扫描 ----------------

    private static void hookFragmentResume(ClassLoader cl) {
        try {
            Class<?> baseFrag = null;
            for (ClassLoader l : HookUtil.candidateLoaders(cl)) {
                try { baseFrag = l.loadClass(BASE_FRAGMENT); break; } catch (Throwable ignored) {}
            }
            if (baseFrag != null) {
                for (final Method m : baseFrag.getDeclaredMethods()) {
                    if ("onResume".equals(m.getName()) && m.getParameterTypes().length == 0) {
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (!isEnabled()) return;
                                    Object frag = param.thisObject;
                                    Object v = XposedHelpers.callMethod(frag, "getView");
                                    if (v instanceof View) {
                                        View footer = findFooter((View) v);
                                        if (footer != null) injectBar(footer);
                                    }
                                } catch (Throwable ignored) {}
                            }
                        });
                        LogWriter.log(TAG, "方案B: BaseChattingUIFragment.onResume 已挂载");
                        break;
                    }
                }
            }
            hookActivityResume(cl);
        } catch (Throwable t) {
            LogWriter.log(TAG, "方案B 挂载失败: " + t);
        }
    }

    private static void hookActivityResume(ClassLoader cl) {
        try {
            for (final Method m : Activity.class.getDeclaredMethods()) {
                if ("onResume".equals(m.getName()) && m.getParameterTypes().length == 0) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!isEnabled()) return;
                                if (!(param.thisObject instanceof Activity)) return;
                                final Activity act = (Activity) param.thisObject;
                                if (act.getClass().getName().contains("LauncherUI")) return;
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    try {
                                        View decor = act.getWindow() == null ? null
                                                : act.getWindow().peekDecorView();
                                        if (decor == null) return;
                                        View footer = findFooter(decor);
                                        if (footer != null) injectBar(footer);
                                    } catch (Throwable ignored) {}
                                }, 800L);
                            } catch (Throwable ignored) {}
                        }
                    });
                    LogWriter.log(TAG, "方案B: Activity.onResume 扫描已挂载");
                    return;
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "方案B Activity 挂载失败: " + t);
        }
    }

    /** 供 WmEntry reconcile 调用：在 Activity 视图树中找 ChatFooter 并幂等补注入。 */
    public static void ensureInjected(Activity act) {
        if (act == null || act.isFinishing() || !isEnabled()) return;
        try {
            // 缓存 footer：已注入时直接返回，避免 reconcile 每 tick 全树扫描造成卡顿。
            View footer = sLastFooter != null ? sLastFooter.get() : null;
            if (footer != null && footer.isAttachedToWindow()
                    && footer.findViewWithTag(ROW_TAG) != null) {
                return;
            }
            View decor = act.getWindow() == null ? null : act.getWindow().peekDecorView();
            if (decor == null) return;
            if (footer == null || !footer.isAttachedToWindow()) {
                footer = findFooter(decor);
                sLastFooter = footer != null
                        ? new java.lang.ref.WeakReference<>(footer) : null;
            }
            if (footer != null) injectBar(footer);
        } catch (Throwable ignored) {}
    }

    private static View findFooter(View view) {
        if (view == null) return null;
        if (isFooter(view)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findFooter(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean isFooter(View view) {
        Class<?> fc = sFooterClass;
        if (fc != null && fc.isInstance(view)) return true;
        String n = view.getClass().getName();
        return CHAT_FOOTER.equals(n) || n.endsWith(".chat.ChatFooter");
    }

    private static ViewGroup.LayoutParams buildParamsFor(ViewGroup parent) {
        if (parent instanceof LinearLayout) {
            return new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        } else if (parent instanceof FrameLayout) {
            return new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM);
        } else {
            return new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static View findFirstEditText(View root) {
        if (root == null) return null;
        if (root instanceof EditText) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findFirstEditText(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }
}
