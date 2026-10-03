package com.leshao.v3.hook;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ui.TTSPageView;
import com.leshao.v3.ui.AppColors;

import java.lang.reflect.Constructor;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 微信聊天输入框上方注入 "音色切换" 与 "万群转发" 按钮（渐变流体糖果霓虹风格）
 * 参照《聊天窗口输入框上方注入按钮_新.md》：
 *   方案A（主推）：Hook ChatFooter 构造器，attach 后向父容器 ChattingUILayout（垂直 LinearLayout）
 *                 在 footer 前一索引插入按钮栏，即输入框上方，随键盘自动上移。
 *   方案B（兜底）：Hook BaseChattingUIFragment.onResume，扫描 View 树找 ChatFooter 注入。
 * 注意：本环境 R8 改写 XposedHelpers，varargs findAndHookMethod/findAndHookConstructor 不可用，
 *       统一使用 findClass + getDeclaredMethod/getDeclaredConstructor + XposedBridge.hookMethod。
 */
public final class ChatVoiceSwitchHook {

    private static final String TAG = "ChatVoiceSwitchHook";
    private static final String CHAT_FOOTER = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter";
    private static final String CHAT_UI_LAYOUT = "com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout";
    private static final String BASE_FRAGMENT = "com.tencent.mm.ui.chatting.BaseChattingUIFragment";
    // v1002: 按钮行的 view tag, 用于幂等判定(微信复用 ChatFooter/重建视图树后据此补注入)
    private static final String ROW_TAG = "LESHAO_INPUT_ROW_V1";

    // 糖果霓虹渐变（粉 → 霓虹粉 → 紫 → 青）
    private static final int[] NEON_GRADIENT = new int[]{
            AppColors.primary(), AppColors.primaryDark(), AppColors.tertiary(), AppColors.secondary()
    };

    private static final int MAX_RETRY = 10;

    // 防重复注入：以 footer View 实例为 key（文档做法），Activity 重建后新 footer 是新对象
    private static final WeakHashMap<View, Boolean> sInjected = new WeakHashMap<>();
    // v1017: 诊断去重（同一 footer/Activity 只打印一次，避免 reconcile 轮询刷屏）
    private static final WeakHashMap<View, Boolean> sLogged = new WeakHashMap<>();
    private static final java.util.HashSet<String> sNoFooterLogged = new java.util.HashSet<>();
    // v1017: 运行时解析到的 ChatFooter 类，用于子类匹配
    private static volatile Class<?> sFooterClass;
    // v1088: 缓存最近一次找到的 ChatFooter, 让 WmEntry 每 tick 的 ensureInjected 免于全树扫描
    private static volatile java.lang.ref.WeakReference<View> sCachedFooter;
    // v1105: 记录已挂高度变化监听的 footer, 避免重复添加
    private static final WeakHashMap<View, Boolean> sLayoutWatch = new WeakHashMap<>();
    // v1106: 消息列表原始底部留白(用于幂等补足, 避免重复叠加)
    private static final WeakHashMap<View, Integer> sOrigPadBottom = new WeakHashMap<>();

    private ChatVoiceSwitchHook() {
    }

    public static void init(final ClassLoader loader) {
        ClassLoader tkCL = VersionCompat.findTinkerClassLoader(loader);
        if (tkCL != null) {
            LogWriter.log(TAG, "init: using Tinker ClassLoader");
        }
        final ClassLoader effectiveCL = tkCL != null ? tkCL : loader;
        Class<?> fc = findClassIfExists(CHAT_FOOTER, effectiveCL);
        if (fc == null) fc = findClassIfExists(CHAT_FOOTER, loader);
        if (fc != null) sFooterClass = fc;
        hookChatFooter(effectiveCL, 0);
        hookFragmentResume(effectiveCL);
    }

    // ==================== 方案A：Hook ChatFooter 构造器（主推） ====================

    private static void hookChatFooter(final ClassLoader loader, final int attempt) {
        try {
            Class<?> footerClazz = sFooterClass != null ? sFooterClass : findClassIfExists(CHAT_FOOTER, loader);
            if (footerClazz == null) {
                retryHook(loader, attempt);
                return;
            }
            sFooterClass = footerClazz;
            // v1017: Hook 全部构造器（含子类会调用的任意 super 构造器），
            // 不再只挂三参构造器后提前返回——否则子类若走其它 super 构造器则永不触发。
            int hooked = 0;
            for (Constructor<?> c : footerClazz.getDeclaredConstructors()) {
                try {
                    XposedBridge.hookMethod(c, footerHook());
                    hooked++;
                } catch (Throwable ignored) {
                }
            }
            if (hooked == 0) {
                retryHook(loader, attempt);
                return;
            }
            LogWriter.log(TAG, "方案A: ChatFooter 构造器 Hook 已挂载 count=" + hooked
                    + " class=" + footerClazz.getName());
        } catch (Throwable t) {
            LogWriter.log(TAG, "方案A hook 异常: " + t);
            retryHook(loader, attempt);
        }
    }

    private static XC_MethodHook footerHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.thisObject == null) return;
                    final View footer = (View) param.thisObject;
                    // 等它真正 attach 到窗口再插（此时父容器已就绪）
                    footer.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                        @Override
                        public void onViewAttachedToWindow(View v) {
                            // v1101: 先同步注入一次, 让按钮行赶在 footer 首次 measure 之前就位。
                            // 旧实现只用 footer.post(), 而 post 的 Runnable 要等当前 traversal
                            // (含首次 measure/layout) 跑完才执行, 即微信第一次量 footer 时还没有按钮行。
                            // 微信据此缓存了 footer 高度, 之后输入内容变长(多行)时按旧高度做键盘上移计算,
                            // 表现为「打字内容过长时有时候不顶上去」。同步注入消除该时序问题, post 仅兜底。
                            try { safeInject(v); } catch (Throwable ignored) {}
                            v.post(new Runnable() {
                                @Override
                                public void run() {
                                    safeInject(footer);
                                }
                            });
                        }

                        @Override
                        public void onViewDetachedFromWindow(View v) {
                        }
                    });
                } catch (Throwable e) {
                    LogWriter.log(TAG, "方案A cb err: " + e);
                }
            }
        };
    }

    private static Class<?> findClassIfExists(String className, ClassLoader loader) {
        try {
            return XposedHelpers.findClass(className, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void retryHook(final ClassLoader loader, final int attempt) {
        if (attempt > MAX_RETRY) {
            LogWriter.log(TAG, "方案A 重试超限");
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                hookChatFooter(loader, attempt + 1);
            }
        }, 2000);
    }

    // ==================== 方案B：Hook Fragment onResume 兜底 ====================

    private static void hookFragmentResume(final ClassLoader loader) {
        try {
            Class<?> baseFrag = findClassIfExists(BASE_FRAGMENT, loader);
            if (baseFrag == null) {
                // 版本变化时退到 Activity.onResume 扫描
                hookActivityResume(loader);
                return;
            }
            for (java.lang.reflect.Method m : baseFrag.getDeclaredMethods()) {
                if ("onResume".equals(m.getName()) && m.getParameterTypes().length == 0) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object fragment = param.thisObject;
                                View root = (View) XposedHelpers.callMethod(fragment, "getView");
                                if (root == null) return;
                                View footer = findFooter(root);
                                if (footer != null) safeInject(footer);
                            } catch (Throwable e) {
                                LogWriter.log(TAG, "方案B cb err: " + e);
                            }
                        }
                    });
                    LogWriter.log(TAG, "方案B: BaseChattingUIFragment.onResume 兜底已挂载");
                    return;
                }
            }
            LogWriter.log(TAG, "方案B: onResume 方法未找到，退到 Activity 扫描");
            hookActivityResume(loader);
        } catch (Throwable t) {
            LogWriter.log(TAG, "方案B 挂载失败: " + t);
            hookActivityResume(loader);
        }
    }

    private static void hookActivityResume(final ClassLoader loader) {
        try {
            Class<?> activityCls = XposedHelpers.findClass("android.app.Activity", loader);
            for (java.lang.reflect.Method m : activityCls.getDeclaredMethods()) {
                if ("onResume".equals(m.getName()) && m.getParameterTypes().length == 0) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.thisObject == null) return;
                                final Activity act = (Activity) param.thisObject;
                                if (!isChatPage(act)) return;
                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        scanAndInject(act);
                                    }
                                }, 1200);
                            } catch (Throwable e) {
                                LogWriter.log(TAG, "方案B Activity cb err: " + e);
                            }
                        }
                    });
                    LogWriter.log(TAG, "方案B: Activity.onResume 兜底已挂载");
                    return;
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "方案B Activity 挂载失败: " + t);
        }
    }

    private static boolean isChatPage(Activity act) {
        String name = act.getClass().getName().toLowerCase();
        // 默认允许注入，仅排除已知非聊天页面
        if (name.contains("luckymoney")) return false;
        if (name.contains("redpacket") || name.contains("collection")) return false;
        if (name.contains("setting") || name.contains("profile")
            || name.contains("plugin") || name.contains("webview")) return false;
        return true;
    }

    private static void scanAndInject(Activity act) {
        try {
            View decor = act.getWindow().getDecorView();
            if (decor == null) return;
            View footer = findFooter(decor);
            if (footer != null) {
                safeInject(footer);
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "方案B 扫描异常: " + t);
        }
    }

    private static View findFooter(View view) {
        if (view == null) return null;
        if (isFooter(view)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findFooter(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** v1017: 兼容 ChatFooter 子类（isInstance 匹配）与混淆后包名（名称后缀匹配） */
    private static boolean isFooter(View view) {
        Class<?> fc = sFooterClass;
        if (fc != null && fc.isInstance(view)) return true;
        String n = view.getClass().getName();
        return CHAT_FOOTER.equals(n) || n.endsWith(".chat.ChatFooter");
    }

    private static void logOnce(View footer, String msg) {
        synchronized (sLogged) {
            if (sLogged.put(footer, Boolean.TRUE) == null) {
                LogWriter.log(TAG, msg);
            }
        }
    }

    // ==================== 旧按钮行清理 ====================

    /** 移除历史版本已注入的旧按钮行（footer 子树或父容器中的 ROW_TAG 视图）。 */
    private static void removeInputRow(View footer) {
        try {
            View row = footer.findViewWithTag(ROW_TAG);
            if (row == null && footer.getParent() instanceof ViewGroup) {
                row = ((ViewGroup) footer.getParent()).findViewWithTag(ROW_TAG);
            }
            if (row != null && row.getParent() instanceof ViewGroup) {
                ((ViewGroup) row.getParent()).removeView(row);
                LogWriter.log(TAG, "已隐藏输入框上方按钮行");
            }
            synchronized (sInjected) { sInjected.remove(footer); }
        } catch (Throwable ignored) {}
    }

    // ==================== 注入核心 ====================

    private static void safeInject(View footer) {
        if (footer == null) return;
        // 旧版「输入框上方按钮排」已废弃：统一改用「输入框快捷按钮」(ChatFooterBarHook)
        // 的按钮组。这里仅清理历史版本可能已注入的旧行，不再自行注入。
        removeInputRow(footer);
    }

    /** v1087: 在 footer 子树中查找第一个 EditText(用于锚定输入行容器)。 */
    private static View findFirstEditText(View root) {
        if (root == null) return null;
        if (root instanceof EditText) return root;
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findFirstEditText(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void scheduleRetryInject(final View footer) {
        final int[] retry = {0};
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (footer.getParent() != null) {
                        safeInject(footer);
                    } else if (retry[0] < 5) {
                        retry[0]++;
                        new Handler(Looper.getMainLooper()).postDelayed(this, 800);
                    } else {
                        sInjected.remove(footer);
                        LogWriter.log(TAG, "注入失败: 父容器迟迟未就绪");
                    }
                } catch (Throwable t) {
                    sInjected.remove(footer);
                    LogWriter.log(TAG, "注入异常: " + t);
                }
            }
        }, 800);
    }

    /**
     * v1105: 监听 footer 高度变化, 变化时逐级向上请求重排。
     *
     * <p>修复「按钮行注入后, 最底部聊天消息偶发被按钮行遮挡」: 微信在
     * {@code ChattingScrollLayout} 中按 footer 高度计算消息区底部留白, footer 变高后
     * 若该缓存未刷新, 消息仍会延伸到按钮行下方。这里在 footer 测高变化时主动触发重排,
     * 让微信重新计算消息区底部留白。</p>
     */
    private static void ensureLayoutWatch(final View footer) {
        if (footer == null) return;
        synchronized (sLayoutWatch) {
            if (sLayoutWatch.put(footer, Boolean.TRUE) != null) return;
        }
        footer.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((b - t) != (ob - ot)) {
                scheduleRelayout(footer, footer.getParent() instanceof ViewGroup
                        ? (ViewGroup) footer.getParent() : null);
            }
            // v1106: footer 高度变化(如键盘弹出)后, 微信可能重算消息区留白而漏掉按钮行,
            // 这里补一次底部留白, 保证最新消息不被按钮行遮挡。
            ensureMessageSpace(footer, footer.findViewWithTag(ROW_TAG));
        });
    }

    /**
     * v1106: 保证消息列表底部留白包含注入的按钮行高度。
     *
     * <p>实测微信在 footer 因注入行变高后并不会刷新消息列表的底部留白, 导致最新一条
     * 聊天记录被按钮行遮挡。这里直接给消息列表设置 {@code paddingBottom = 原始留白 + 按钮行高度}
     * (以首次记录的原始值为基准, 幂等不叠加), 从根上保证消息顶在按钮行上方。</p>
     */
    private static void ensureMessageSpace(final View footer, final View row) {
        if (footer == null || row == null) return;
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) applyMessageSpace(footer, row);
            else new Handler(Looper.getMainLooper()).post(() -> applyMessageSpace(footer, row));
        } catch (Throwable ignored) {}
    }

    private static void applyMessageSpace(View footer, View row) {
        try {
            int rowH = row.getHeight();
            if (rowH <= 0) return;
            View root = footer.getRootView();
            if (root == null) return;
            java.util.List<View> lists = new java.util.ArrayList<>();
            collectScrollLists(root, footer, lists);
            // 消息列表通常是页面内最高的可滚动列表; 排除 footer 子树(表情/更多面板等)
            View target = null;
            int best = -1;
            for (View v : lists) {
                int h = v.getHeight();
                if (h > best) { best = h; target = v; }
            }
            if (target == null) return;
            Integer orig = sOrigPadBottom.get(target);
            if (orig == null) {
                orig = target.getPaddingBottom();
                sOrigPadBottom.put(target, orig);
            }
            int want = orig + rowH;
            if (target.getPaddingBottom() != want) {
                target.setPadding(target.getPaddingLeft(), target.getPaddingTop(),
                        target.getPaddingRight(), want);
                target.requestLayout();
                logOnce(footer, "消息区底部留白补足 +" + rowH + "px (orig=" + orig + ")");
            }
        } catch (Throwable ignored) {}
    }

    private static void collectScrollLists(View v, View footer, java.util.List<View> out) {
        if (v == null) return;
        if (v == footer) return; // 跳过 footer 子树
        String cn = v.getClass().getName();
        if (cn.contains("RecyclerView") || v instanceof android.widget.AbsListView) {
            out.add(v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectScrollLists(g.getChildAt(i), footer, out);
            }
        }
    }

    /**
     * v1105: 注入后延迟补一次重排。footer 首次测量可能仍带旧高度(按钮行尚未计入),
     * 因此 post 到当前布局/measure 跑完后再触发一次, 确保消息区底部留白同步。
     */
    private static void scheduleRelayout(final View footer, final ViewGroup parent) {
        if (footer == null) return;
        final Runnable r = () -> forceRelayout(footer, parent);
        footer.post(r);
        footer.postDelayed(r, 250L);
    }

    /** 从 footer/父容器逐级向上请求重排(最多 8 层), 触发微信重新计算消息区底部留白。 */
    private static void forceRelayout(View footer, ViewGroup parent) {
        try {
            footer.requestLayout();
            View p = parent != null ? parent : (footer.getParent() instanceof View ? (View) footer.getParent() : null);
            int guard = 0;
            while (p != null && guard++ < 8) {
                p.requestLayout();
                android.view.ViewParent vp = p.getParent();
                p = (vp instanceof View) ? (View) vp : null;
            }
        } catch (Throwable ignored) {}
    }

    /** 注入后延迟补足消息区底部留白(footer 首次测量可能仍带旧高, 多次兜底)。 */
    private static void scheduleMessageSpace(final View footer, final View row) {
        if (footer == null || row == null) return;
        final Runnable r = () -> applyMessageSpace(footer, row);
        footer.post(r);
        footer.postDelayed(r, 200L);
        footer.postDelayed(r, 600L);
    }

    // ==================== UI ====================

    /**
     * 构建模块现有的一排功能按钮（音色/群发/语音/AI助手/转发）。
     *
     * <p>供「输入框快捷按钮」(ChatFooterBarHook) 复用同一按钮组；{@code noBackground=true}
     * 时按钮不设背景（新版样式），{@code false} 时沿用本 Hook 的流光浅底描边样式。</p>
     */
    public static LinearLayout buildActionButtonRow(final Context ctx, boolean noBackground) {
        float density = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, (int) (5 * density), 0, (int) (5 * density));

        int gap = (int) (6 * density);
        addButton(row, makeFooterButton(ctx, "音色", AppColors.primary(), v -> openTtsPage(ctx), noBackground), gap);
        addButton(row, makeFooterButton(ctx, "群发", AppColors.tertiary(), v -> openMassSend(ctx), noBackground), gap);
        addButton(row, makeFooterButton(ctx, "语音", AppColors.primary(), v -> {
            // v960: 面板展示异常(BadTokenException 等)必须兜底, 否则点击即闪退
            try {
                com.leshao.v3.ChatFooterLongPressMenu.showPanelStatic(v);
            } catch (Throwable t) {
                LogWriter.log(TAG, "voice btn click err: " + t);
            }
        }, noBackground), gap);
        // AI助手：原「更多」菜单中的 AI 助手功能直达
        addButton(row, makeFooterButton(ctx, "AI助手", AppColors.secondary(), v -> openAiAssistant(ctx), noBackground), gap);
        // 转发：原「更多」菜单中的自动转发功能直达
        addButton(row, makeFooterButton(ctx, "转发", AppColors.primary(), v -> openAutoForward(ctx), noBackground), 0);
        return row;
    }

    /** v1002: 供 WmEntry reconciler 调用 —— 微信复用 ChatFooter/漏掉生命周期 hook 时补注入。
     *  以 Activity decorView 为根扫描 ChatFooter, 命中即走 safeInject(tag 幂等)。 */
    public static void ensureInjected(Activity act) {
        if (act == null || act.isFinishing()) return;
        // 输入框上方按钮统一由「输入框快捷按钮」(ChatFooterBarHook) 注入；
        // 此处仅复用同一条 reconcile 触发链，并兜底清理旧版遗留的按钮行。
        try { ChatFooterBarHook.ensureInjected(act); } catch (Throwable ignored) {}
        // 微信复用 ChatFooter 时「一键拉群」按钮可能丢失，接入同一 reconcile 链补注入。
        try { ChatFooterInviteHook.ensureInjected(act); } catch (Throwable ignored) {}
        try {
            View footer = null;
            java.lang.ref.WeakReference<View> cf = sCachedFooter;
            if (cf != null) footer = cf.get();
            if (footer == null || !footer.isAttachedToWindow()) {
                View decor = act.getWindow() != null ? act.getWindow().peekDecorView() : null;
                if (decor != null) {
                    footer = findFooter(decor);
                    sCachedFooter = footer != null
                            ? new java.lang.ref.WeakReference<>(footer) : null;
                }
            }
            if (footer != null) removeInputRow(footer);
        } catch (Throwable t) {
            LogWriter.log(TAG, "ensureInjected err: " + t.getMessage());
        }
    }

    private static int dp(int dpi, Context ctx) {
        return (int) (dpi * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static boolean isDarkMode(Context ctx) {
        return (ctx.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** B 套「同色系浅底描边」按钮：浅底 + 同色文字 + 同色细边框，紧凑间距。
     *  {@code noBackground=true} 时不设背景，仅保留同色文字（新版无背景样式）。 */
    private static TextView makeFooterButton(final Context ctx, String text, int color,
                                             View.OnClickListener listener, boolean noBackground) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextSize(13);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        btn.setMinWidth(dp(40, ctx));
        // 收紧字间距(替代原先逐字插空格的写法)
        btn.setLetterSpacing(0.06f);

        boolean dark = isDarkMode(ctx);
        if (noBackground) {
            // 尺寸与旧版按钮保持一致：最小宽度 40dp、内边距 12/5dp、字号 13sp。
            btn.setBackground(null);
            btn.setTextColor(color);
            btn.setPadding(dp(12, ctx), dp(5, ctx), dp(12, ctx), dp(5, ctx));
        } else {
            int bgColor = dark ? mix(color, 0xFF1B1F24, 0.26f) : mix(color, 0xFFFFFFFF, 0.14f);
            int borderColor = dark ? mix(color, 0xFF1B1F24, 0.55f) : mix(color, 0xFFFFFFFF, 0.45f);
            int textColor = dark ? mix(color, 0xFFFFFFFF, 0.78f) : color;

            // v1148 去渐变：纯色底 + 描边（此前为三色流动渐变）
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(bgColor);
            bg.setCornerRadius(dp(18, ctx));
            bg.setStroke(dp(1, ctx), borderColor);
            btn.setBackground(bg);
            btn.setTextColor(textColor);
            btn.setPadding(dp(12, ctx), dp(5, ctx), dp(12, ctx), dp(5, ctx));
        }
        btn.setOnClickListener(listener);
        return btn;
    }

    private static void addButton(LinearLayout row, TextView btn, int marginEndPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (marginEndPx > 0) lp.setMargins(0, 0, marginEndPx, 0);
        btn.setLayoutParams(lp);
        row.addView(btn);
    }

    /** 将 fg 按 fgRatio 混入 bg, 模拟 color-mix(in srgb, fg fgRatio%, bg)。 */
    private static int mix(int fg, int bg, float fgRatio) {
        int fr = (fg >> 16) & 0xFF;
        int fg2 = (fg >> 8) & 0xFF;
        int fb = fg & 0xFF;
        int br = (bg >> 16) & 0xFF;
        int bg2 = (bg >> 8) & 0xFF;
        int bb = bg & 0xFF;
        int r = Math.round(fr * fgRatio + br * (1 - fgRatio));
        int g = Math.round(fg2 * fgRatio + bg2 * (1 - fgRatio));
        int b = Math.round(fb * fgRatio + bb * (1 - fgRatio));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 群发：打开乐少万群定时群发（完整版群发向导） */
    private static void openMassSend(Context ctx) {
        final Activity act = getActivityFromContext(ctx);
        if (act == null) {
            LogWriter.log(TAG, "群发: 无法获取 Activity");
            return;
        }
        try {
            ClassLoader cl = ContextManager.getClassLoader();
            com.leshao.v3.wm.hook.WmChatHook.showMassSendFromCorner(act, cl);
        } catch (Throwable t) {
            LogWriter.log(TAG, "群发异常: " + t.getMessage());
            Toast.makeText(act, "群发暂不可用", Toast.LENGTH_SHORT).show();
        }
    }

    private static void openTtsPage(Context ctx) {
        Activity act = getActivityFromContext(ctx);
        if (act == null) {
            LogWriter.log(TAG, "无法获取 Activity");
            return;
        }
        try {
            float density = ctx.getResources().getDisplayMetrics().density;
            TTSPageView.showTtsCubeDialog(act, act, density);
        } catch (Throwable t) {
            LogWriter.log(TAG, "打开音色选择失败: " + t.getMessage());
        }
    }

    /** AI助手：直达 AI 助手弹窗(原「更多」菜单内入口)。 */
    private static void openAiAssistant(Context ctx) {
        try {
            Activity act = getActivityFromContext(ctx);
            if (act == null) {
                LogWriter.log(TAG, "AI助手: 无法获取 Activity");
                return;
            }
            com.leshao.ai.hook.wechat.AiAssistantPanel.show(act);
        } catch (Throwable t) {
            LogWriter.log(TAG, "打开AI助手失败: " + t.getMessage());
        }
    }

    /** 转发：直达自动转发配置(原「更多」菜单内入口)。 */
    private static void openAutoForward(Context ctx) {
        try {
            Activity act = getActivityFromContext(ctx);
            if (act == null) {
                LogWriter.log(TAG, "转发: 无法获取 Activity");
                return;
            }
            com.leshao.v3.hook.AutoForwardHook.showConfigDialog(act);
        } catch (Throwable t) {
            LogWriter.log(TAG, "打开自动转发失败: " + t.getMessage());
        }
    }

    private static Activity getActivityFromContext(Context ctx) {
        if (ctx instanceof Activity) return (Activity) ctx;
        if (ctx instanceof android.content.ContextWrapper) {
            Context base = ((android.content.ContextWrapper) ctx).getBaseContext();
            while (base != null) {
                if (base instanceof Activity) return (Activity) base;
                if (base instanceof android.content.ContextWrapper) {
                    base = ((android.content.ContextWrapper) base).getBaseContext();
                } else {
                    break;
                }
            }
        }
        return null;
    }
}
