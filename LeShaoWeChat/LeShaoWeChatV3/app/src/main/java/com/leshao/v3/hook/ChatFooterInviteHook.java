package com.leshao.v3.hook;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.ContactPickerDialog;

import java.lang.reflect.Constructor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 「拉群」快捷按钮注入 —— 在聊天窗口输入框上方按钮栏追加一个「拉群」入口。
 *
 * <p>完全复用项目既有已验证的注入链路（见 {@link ChatFooterBarHook} 与
 * {@link ChatVoiceSwitchHook#buildActionButtonRow}）：</p>
 * <ol>
 *   <li>hook {@code com.tencent.mm.pluginsdk.ui.chat.ChatFooter} 的所有构造器，
 *       after 阶段给 footer 注册 attach 监听，附加时把按钮行插入 footer 内「包含输入框的垂直
 *       LinearLayout」的输入行之前（与 ChatVoiceSwitchHook 同策略，避免遮挡消息）。</li>
 *   <li>追加 Fragment.onResume / Activity.onResume 兜底扫描，处理模块加载前输入框已存在的情况。</li>
 * </ol>
 *
 * <p>点击流程：{@code ContactPickerDialog.show(好友多选)} → {@code BatchInviteManager.showGroupPicker}
 * → 确认后 {@code BatchInviteManager.startInvite} 立即执行。</p>
 */
public final class ChatFooterInviteHook {

    private static final String TAG = "ChatFooterInvite";

    private static final String CHAT_FOOTER = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter";
    private static final String BASE_FRAGMENT = "com.tencent.mm.ui.chatting.BaseChattingUIFragment";
    private static final int BTN_TAG = 0x7f31abce;
    private static final int MAX_RETRY = 5;

    private static volatile Class<?> sFooterClass;
    private static volatile java.lang.ref.WeakReference<View> sLastFooter;

    private ChatFooterInviteHook() {}

    public static void hook(final ClassLoader cl) {
        // v3.0.271: 「拉群」按钮已合并进输入框上方快捷菜单（ChatVoiceSwitchHook
        // .buildActionButtonRow 内按开关显示），不再独立注入第二行按钮栏。
        try {
            LogWriter.log(TAG, "拉群按钮已合并到快捷菜单，跳过独立注入");
        } catch (Throwable ignored) {}
        return;
    }

    /** 供 WmEntry 等 reconcile 调用：v3.0.271 起拉群按钮已合并进快捷菜单，无需独立注入。 */
    public static void ensureInjected(Activity act) {
        // 拉群按钮已合并进快捷菜单，独立按钮行不再注入
        return;
    }

    // ==================== 注入实现 ====================

    private static void injectButton(final View footer) {
        if (footer == null || !BatchInviteConfig.isEnabled()) return;
        ViewParentHolder holder = locateHost(footer);
        if (holder == null) { retryInject(footer, 0); return; }

        ViewGroup host = holder.host;
        int hostIdx = holder.hostIdx;
        if (host.findViewWithTag(BTN_TAG) != null) return;

        Context ctx = footer.getContext();
        if (ctx == null) return;

        LinearLayout bar = buildInviteRow(ctx);
        bar.setTag(BTN_TAG);
        try {
            if (host != null && hostIdx >= 0) {
                bar.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                host.addView(bar, hostIdx);
            } else if (footer instanceof ViewGroup) {
                bar.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                ((ViewGroup) footer).addView(bar, 0);
            } else {
                ViewGroup parent = (ViewGroup) footer.getParent();
                bar.setLayoutParams(buildParamsFor(parent));
                int idx = parent.indexOfChild(footer);
                if (idx < 0) idx = parent.getChildCount();
                parent.addView(bar, idx);
            }
            footer.requestLayout();
            LogWriter.log(TAG, "injected 拉群按钮 host=" + host.getClass().getName() + " idx=" + hostIdx);
        } catch (Throwable t) {
            LogWriter.log(TAG, "注入异常: " + t);
        }
    }

    private static final class ViewParentHolder {
        final ViewGroup host;
        final int hostIdx;
        ViewParentHolder(ViewGroup h, int i) { this.host = h; this.hostIdx = i; }
    }

    private static ViewParentHolder locateHost(View footer) {
        try {
            View edit = findFirstEditText(footer);
            if (edit != null) {
                View child = edit;
                ViewGroup p = (ViewGroup) edit.getParent();
                while (p != null && p != footer) {
                    if (p instanceof LinearLayout
                            && ((LinearLayout) p).getOrientation() == LinearLayout.VERTICAL) {
                        return new ViewParentHolder(p, p.indexOfChild(child));
                    }
                    if (!(p.getParent() instanceof ViewGroup)) break;
                    child = p;
                    p = (ViewGroup) p.getParent();
                }
            }
            if (footer instanceof ViewGroup) return new ViewParentHolder((ViewGroup) footer, 0);
        } catch (Throwable ignored) {}
        return null;
    }

    private static LinearLayout buildInviteRow(final Context ctx) {
        float density = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, (int) (5 * density), 0, (int) (5 * density));

        TextView btn = new TextView(ctx);
        btn.setText("拉群");
        btn.setTextSize(13);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        btn.setMinWidth((int) (40 * density));
        btn.setLetterSpacing(0.06f);
        btn.setTextColor(AppColors.tertiary());
        btn.setBackground(null);
        btn.setPadding((int) (12 * density), (int) (5 * density),
                (int) (12 * density), (int) (5 * density));
        btn.setOnClickListener(v -> openInvitePicker(ctx));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(btn, lp);
        return row;
    }

    /** 点击「拉群」：选好友（多选）→ 选群 → 立即邀请。供快捷菜单按钮调用。 */
    public static void openInvitePicker(Context ctx) {
        try {
            final Activity act = getActivity(ctx);
            if (act == null) {
                LogWriter.log(TAG, "拉群: 无法获取 Activity");
                return;
            }
            if (!BatchInviteConfig.isEnabled()) {
                android.widget.Toast.makeText(act, "一键拉群未开启，请到「联系人和群聊」中打开",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            ContactPickerDialog.show(act, "", ContactPickerDialog.MODE_FRIEND,
                    (wxids, display) -> {
                        if (wxids == null || wxids.isEmpty()) return;
                        final java.util.List<String> targets = new java.util.ArrayList<>(wxids);
                        BatchInviteManager.showGroupPicker(act, targets, display);
                    },
                    () -> {}, "选择要拉群的好友");
        } catch (Throwable t) {
            LogWriter.log(TAG, "onInviteClick err: " + t);
        }
    }

    // ==================== 兜底扫描 ====================

    private static void hookFragmentResume(ClassLoader cl) {
        try {
            Class<?> baseFrag = null;
            for (ClassLoader l : HookUtil.candidateLoaders(cl)) {
                try { baseFrag = l.loadClass(BASE_FRAGMENT); break; } catch (Throwable ignored) {}
            }
            if (baseFrag != null) {
                for (final java.lang.reflect.Method m : baseFrag.getDeclaredMethods()) {
                    if ("onResume".equals(m.getName()) && m.getParameterTypes().length == 0) {
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (!BatchInviteConfig.isEnabled()) return;
                                    Object frag = param.thisObject;
                                    Object v = XposedHelpers.callMethod(frag, "getView");
                                    if (v instanceof View) {
                                        View footer = findFooter((View) v);
                                        if (footer != null) injectButton(footer);
                                    }
                                } catch (Throwable ignored) {}
                            }
                        });
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "方案B 挂载失败: " + t);
        }
        try {
            for (final java.lang.reflect.Method m : Activity.class.getDeclaredMethods()) {
                if ("onResume".equals(m.getName()) && m.getParameterTypes().length == 0) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!BatchInviteConfig.isEnabled()) return;
                                if (!(param.thisObject instanceof Activity)) return;
                                final Activity act = (Activity) param.thisObject;
                                if (act.getClass().getName().contains("LauncherUI")) return;
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    try {
                                        View decor = act.getWindow() == null ? null
                                                : act.getWindow().peekDecorView();
                                        if (decor == null) return;
                                        View footer = findFooter(decor);
                                        if (footer != null) injectButton(footer);
                                    } catch (Throwable ignored) {}
                                }, 800L);
                            } catch (Throwable ignored) {}
                        }
                    });
                    break;
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "方案B Activity 挂载失败: " + t);
        }
    }

    private static void retryInject(final View footer, final int attempt) {
        if (attempt > MAX_RETRY) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (footer.getParent() instanceof ViewGroup) injectButton(footer);
            else retryInject(footer, attempt + 1);
        }, 500L);
    }

    // ==================== 反射/查找工具 ====================

    private static Class<?> findClass(String name, ClassLoader cl) {
        for (ClassLoader l : HookUtil.candidateLoaders(cl)) {
            try { return l.loadClass(name); } catch (Throwable ignored) {}
        }
        return null;
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

    private static ViewGroup.LayoutParams buildParamsFor(ViewGroup parent) {
        if (parent instanceof LinearLayout) {
            return new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        } else if (parent instanceof FrameLayout) {
            return new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM);
        }
        return new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static Activity getActivity(Context ctx) {
        Context c = ctx;
        while (c instanceof android.content.ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        return null;
    }
}
