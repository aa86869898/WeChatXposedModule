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
                            footer.post(new Runnable() {
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

    // ==================== 注入核心 ====================

    private static void safeInject(View footer) {
        if (footer == null) return;
        // v1002: 以"父容器里是否已有本模块按钮行(tag)"为幂等判据, 而非仅凭 footer 实例。
        // 微信会复用 ChatFooter/重建视图树, 旧实现只记 footer 实例, 行一旦被移除就再也不补注入。
        ViewGroup parent = footer.getParent() instanceof ViewGroup
                ? (ViewGroup) footer.getParent() : null;
        if (parent == null) {
            scheduleRetryInject(footer);
            return;
        }
        if (parent.findViewWithTag(ROW_TAG) != null) {
            synchronized (sInjected) { sInjected.put(footer, Boolean.TRUE); }
            return;
        }

        Context ctx = footer.getContext();
        if (ctx == null) {
            logOnce(footer, "跳过注入: footer.getContext() 为 null");
            return;
        }
        Activity act = getActivityFromContext(ctx);
        if (act == null) {
            logOnce(footer, "跳过注入: 无法从 Context 取得 Activity ctx=" + ctx.getClass().getName());
            return;
        }
        if (!isChatPage(act)) {
            logOnce(footer, "跳过注入: 非聊天页面 act=" + act.getClass().getName());
            return;
        }

        if (com.leshao.v3.UnifiedPrefs.get(ctx, "wm_prefs")
                .getBoolean("input_buttons", true) == false) {
            logOnce(footer, "跳过注入: wm_prefs.input_buttons=false");
            return;
        }

        // 确保注入目标不是弹窗中的输入栏
        try {
            View root = footer.getRootView();
            if (root != null) {
                String rootCls = root.getClass().getName();
                if (rootCls.contains("Popup") || rootCls.contains("Dialog")
                    || rootCls.contains("popup") || rootCls.contains("dialog")) {
                    logOnce(footer, "跳过注入: 根视图疑似弹窗 " + rootCls);
                    return;
                }
            }
        } catch (Throwable ignored) {}

        int idx = parent.indexOfChild(footer);
        if (idx < 0) {
            logOnce(footer, "跳过注入: footer 不在父容器索引中 parent=" + parent.getClass().getName());
            return;
        }

        View row = createButtonRow(ctx);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        boolean ok = true;
        try {
            parent.addView(row, idx);
        } catch (Throwable t) {
            ok = false;
            LogWriter.log(TAG, "注入异常: " + t);
        }
        if (ok && parent.findViewWithTag(ROW_TAG) != null) {
            synchronized (sInjected) { sInjected.put(footer, Boolean.TRUE); }
            LogWriter.log(TAG, "注入成功! 父容器=" + parent.getClass().getName()
                    + " idx=" + parent.indexOfChild(row));
        } else {
            sInjected.remove(footer);
            LogWriter.log(TAG, "注入失败: addView 未生效 parent=" + parent.getClass().getName());
        }
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

    // ==================== UI ====================

    private static android.widget.HorizontalScrollView createButtonRow(Context ctx) {
        float density = ctx.getResources().getDisplayMetrics().density;

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (5 * density),
                0, (int) (5 * density));

        // B 套「同色系浅底描边」配色：音色 / 群发 / 语音 / AI助手 / 转发
        int gap = (int) (6 * density);
        addButton(row, makeFooterButton(ctx, "音色", 0xFFC026D3, v -> openTtsPage(ctx)), gap);
        addButton(row, makeFooterButton(ctx, "群发", 0xFF8B5CF6, v -> openMassSend(ctx)), gap);
        addButton(row, makeFooterButton(ctx, "语音", 0xFF9333EA, v -> {
            // v960: 面板展示异常(BadTokenException 等)必须兜底, 否则点击即闪退
            try {
                com.leshao.v3.ChatFooterLongPressMenu.showPanelStatic(v);
            } catch (Throwable t) {
                LogWriter.log(TAG, "voice btn click err: " + t);
            }
        }), gap);
        // AI助手：原「更多」菜单中的 AI 助手功能直达
        addButton(row, makeFooterButton(ctx, "AI助手", 0xFF7C3AED, v -> openAiAssistant(ctx)), gap);
        // 转发：原「更多」菜单中的自动转发功能直达
        addButton(row, makeFooterButton(ctx, "转发", 0xFFDB2777, v -> openAutoForward(ctx)), 0);

        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(ctx);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setPadding(0, 0, 0, 0);
        // 按钮整体水平居中显示：HScrollView 子 view 宽度强制为内容宽(UNSPECIFIED)，
        // 用 FrameLayout.LayoutParams 的 gravity 让内容行在整行内居中
        row.setGravity(Gravity.CENTER);
        scroll.addView(row, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL));
        // v1002: 打 tag 供幂等判定(见 safeInject)
        scroll.setTag(ROW_TAG);
        return scroll;
    }

    /** v1002: 供 WmEntry reconciler 调用 —— 微信复用 ChatFooter/漏掉生命周期 hook 时补注入。
     *  以 Activity decorView 为根扫描 ChatFooter, 命中即走 safeInject(tag 幂等)。 */
    public static void ensureInjected(Activity act) {
        if (act == null || act.isFinishing()) return;
        try {
            View decor = act.getWindow() != null ? act.getWindow().peekDecorView() : null;
            if (decor == null) return;
            View footer = findFooter(decor);
            if (footer != null) {
                safeInject(footer);
            } else {
                String key = act.getClass().getName();
                synchronized (sNoFooterLogged) {
                    if (sNoFooterLogged.add(key)) {
                        LogWriter.log(TAG, "ensureInjected: 未在视图树中找到 ChatFooter act=" + key);
                    }
                }
            }
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

    /** B 套「同色系浅底描边」按钮：浅底 + 同色文字 + 同色细边框，紧凑间距。 */
    private static TextView makeFooterButton(final Context ctx, String text, int color,
                                             View.OnClickListener listener) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextSize(13);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        btn.setMinWidth(dp(40, ctx));
        // 收紧字间距(替代原先逐字插空格的写法)
        btn.setLetterSpacing(0.06f);

        boolean dark = isDarkMode(ctx);
        int bgColor = dark ? mix(color, 0xFF1B1F24, 0.26f) : mix(color, 0xFFFFFFFF, 0.14f);
        int borderColor = dark ? mix(color, 0xFF1B1F24, 0.55f) : mix(color, 0xFFFFFFFF, 0.45f);
        int textColor = dark ? mix(color, 0xFFFFFFFF, 0.78f) : color;

        // v1067 葡萄气泡：同色系浅底也加入流光，整体动起来
        com.leshao.v3.ui.FlowingGradientDrawable bg = new com.leshao.v3.ui.FlowingGradientDrawable(
                bgColor, borderColor, bgColor);
        bg.setCornerRadius(dp(18, ctx));
        bg.setStroke(dp(1, ctx), borderColor);
        btn.setBackground(bg);
        btn.setTextColor(textColor);
        btn.setPadding(dp(12, ctx), dp(5, ctx), dp(12, ctx), dp(5, ctx));
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
