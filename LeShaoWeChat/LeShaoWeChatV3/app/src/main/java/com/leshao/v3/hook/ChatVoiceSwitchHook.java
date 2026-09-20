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
import com.leshao.v3.IconLoader;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ui.TTSPageView;

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

    // 糖果霓虹渐变（粉 → 霓虹粉 → 紫 → 青）
    private static final int[] NEON_GRADIENT = new int[]{
            0xFFFF94C2, 0xFFFF10F0, 0xFF7B2FF7, 0xFF36D1E8
    };

    private static final int MAX_RETRY = 10;

    // 防重复注入：以 footer View 实例为 key（文档做法），Activity 重建后新 footer 是新对象
    private static final WeakHashMap<View, Boolean> sInjected = new WeakHashMap<>();

    private ChatVoiceSwitchHook() {
    }

    public static void init(final ClassLoader loader) {
        ClassLoader tkCL = VersionCompat.findTinkerClassLoader(loader);
        if (tkCL != null) {
            LogWriter.log(TAG, "init: using Tinker ClassLoader");
        }
        final ClassLoader effectiveCL = tkCL != null ? tkCL : loader;
        hookChatFooter(effectiveCL, 0);
        hookFragmentResume(effectiveCL);
    }

    // ==================== 方案A：Hook ChatFooter 构造器（主推） ====================

    private static void hookChatFooter(final ClassLoader loader, final int attempt) {
        try {
            Class<?> footerClazz = findClassIfExists(CHAT_FOOTER, loader);
            if (footerClazz == null) {
                retryHook(loader, attempt);
                return;
            }
            Class<?> clazzToHook = footerClazz;
            // 优先 Hook 三参构造器 (Context, AttributeSet, int)，它是唯一真实构造器；
            // 若三参不存在则遍历全部构造器覆盖所有创建路径。
            try {
                Constructor<?> c = clazzToHook.getDeclaredConstructor(
                        Context.class, android.util.AttributeSet.class, int.class);
                XposedBridge.hookMethod(c, footerHook());
                LogWriter.log(TAG, "方案A: ChatFooter 三参构造器 Hook 已挂载");
                return;
            } catch (Throwable ignored) {
            }
            for (Constructor<?> c : clazzToHook.getDeclaredConstructors()) {
                XposedBridge.hookMethod(c, footerHook());
            }
            LogWriter.log(TAG, "方案A: ChatFooter 全部构造器 Hook 已挂载");
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
        if (CHAT_FOOTER.equals(view.getClass().getName())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findFooter(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    // ==================== 注入核心 ====================

    private static void safeInject(View footer) {
        if (footer == null) return;
        synchronized (sInjected) {
            if (sInjected.containsKey(footer)) return;
            sInjected.put(footer, Boolean.TRUE);
        }

        Context ctx = footer.getContext();
        if (ctx == null) return;
        Activity act = getActivityFromContext(ctx);
        if (act == null || !isChatPage(act)) return;

        if (com.leshao.v3.UnifiedPrefs.get(ctx, "wm_prefs")
                .getBoolean("input_buttons", true) == false) return;

        // 确保注入目标不是弹窗中的输入栏
        try {
            View root = footer.getRootView();
            if (root != null) {
                String rootCls = root.getClass().getName();
                if (rootCls.contains("Popup") || rootCls.contains("Dialog")
                    || rootCls.contains("popup") || rootCls.contains("dialog")) {
                    return;
                }
            }
        } catch (Throwable ignored) {}

        ViewGroup parent = (ViewGroup) footer.getParent();
        if (parent == null) {
            scheduleRetryInject(footer);
            return;
        }

        View row = createButtonRow(ctx);
        boolean ok;
        // 父容器是垂直 LinearLayout（ChattingUILayout）：插到 footer 前面 == 输入框上方
        if (parent instanceof LinearLayout
                && ((LinearLayout) parent).getOrientation() == LinearLayout.VERTICAL) {
            int idx = parent.indexOfChild(footer);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            parent.addView(row, idx);
            ok = true;
        } else {
            // 万一父容器不是垂直 LinearLayout：插到 footer 之前，随 footer 布局
            int idx = parent.indexOfChild(footer);
            if (idx < 0) {
                ok = false;
            } else {
                row.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                parent.addView(row, idx);
                ok = true;
            }
        }
        if (ok) {
            LogWriter.log(TAG, "注入成功! 父容器=" + parent.getClass().getName()
                    + " idx=" + parent.indexOfChild(row));
        } else {
            sInjected.remove(footer);
            LogWriter.log(TAG, "注入失败: 找不到合适的父容器");
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

        // 音色：文字蓝色 + 音色列表图标（带边框背景）
        TextView btn = createBlueButton(ctx, "音色", IconLoader.IC_VOICE_LIST, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openTtsPage(ctx);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, (int) (6 * density), 0);
        btn.setLayoutParams(lp);
        row.addView(btn);

        // 群发：文字蓝色 + 定时群发图标（细边框背景），点击进入乐少万群定时群发
        TextView schedBtn = createBlueButton(ctx, "群发", IconLoader.IC_SCHEDULE_SEND, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openMassSend(ctx);
            }
        });
        LinearLayout.LayoutParams lpSched = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpSched.setMargins(0, 0, (int) (6 * density), 0);
        schedBtn.setLayoutParams(lpSched);
        row.addView(schedBtn);

        // 助手：文字蓝色 + 细边框背景，点击打开功能面板
        TextView masterBtn = createBlueButton(ctx, "助手", IconLoader.IC_SCHEDULE_SEND, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openMasterPanel(ctx, v);
            }
        });
        LinearLayout.LayoutParams lpMaster = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpMaster.setMargins(0, 0, (int) (6 * density), 0);
        masterBtn.setLayoutParams(lpMaster);
        row.addView(masterBtn);

        // 语音：文字蓝色 + 细边框背景，点击打开音频选择面板
        TextView mp3Btn = createBlueButton(ctx, "语音", IconLoader.IC_SCHEDULE_SEND, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                com.leshao.v3.ChatFooterLongPressMenu.showPanelStatic(v);
            }
        });
        mp3Btn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(mp3Btn);

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
        return scroll;
    }

    private static int dp(int dpi, Context ctx) {
        return (int) (dpi * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static boolean isDarkMode(Context ctx) {
        return (ctx.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 纯文字按钮：样式/配色与聊天分组标签按钮（未选中态）一致，字符间加空格间距，边框内居中 */
    private static TextView createBlueButton(final Context ctx, String text,
                                             final int iconId, View.OnClickListener listener) {
        TextView btn = new TextView(ctx);
        // 每个文字之间隔一个空格：如 "音色" -> "音 色"
        btn.setText(text.replaceAll("(?<=.)(?=.)", " "));
        btn.setTextSize(15);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        btn.setMinWidth(dp(50, ctx));

        // 与聊天分组标签按钮未选中配色一致（浅色/暗色）
        boolean dark = isDarkMode(ctx);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            dark ? new int[]{0x26FF6B8A, 0x26A855F7, 0x2638BDF8}
                 : new int[]{0x1AFF6B8A, 0x1AA855F7, 0x1A38BDF8});
        bg.setCornerRadius(dp(20, ctx));
        bg.setStroke(dp(1, ctx), dark ? Color.parseColor("#C084FC") : Color.parseColor("#A855F7"));
        btn.setBackground(bg);
        btn.setTextColor(dark ? Color.parseColor("#C8C8CE") : Color.parseColor("#555555"));

        // 左右平均分配：对称内边距 + 水平居中
        btn.setPadding(dp(15, ctx), dp(7, ctx), dp(15, ctx), dp(7, ctx));
        btn.setOnClickListener(listener);
        return btn;
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

    private static void openMasterPanel(Context ctx, View anchor) {
        try {
            Activity act = getActivityFromContext(ctx);
            com.leshao.v3.wm.hook.WmChatHook.showAssistantMenu(act, anchor);
        } catch (Throwable t) {
            LogWriter.log(TAG, "打开助手菜单失败: " + t.getMessage());
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
