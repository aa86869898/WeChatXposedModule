/*
 * ChatQuickBar.java - 微信聊天输入框上方快捷指令按钮
 * 独立 Xposed 模块 Java 源码参考文件
 *
 * ⚠️ 本文件不是 BSH 插件脚本，请勿在 LSPilot 中启用本插件！
 * 仅供从手机拷贝源码到你的 Android Studio 工程使用。
 *
 * 使用方法（在你自己的模块中）：
 *   if (lpparam.packageName.equals("com.tencent.mm")) {
 *       ChatQuickBar.init(lpparam.classLoader);
 *   }
 */

package com.example.wechatenhance;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Constructor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 微信聊天输入框上方快捷指令按钮（独立 Xposed Hook 类）
 *
 * 逆向结论（微信 8.0.76）：
 *   - 输入框：com.tencent.mm.ui.widget.MMEditText
 *   - 输入框行：com.tencent.mm.view.MaxHeightScrollView（输入框的第 3 层祖先）
 *   - 注入：Hook MMEditText 构造 → 延迟 800ms → 向上找 MaxHeightScrollView
 *           → 优先插到其父 RelativeLayout 的父级 LinearLayout 中（输入框正上方）
 *           → 兜底插到 RelativeLayout 中 MaxHeightScrollView 之前
 */
public final class ChatQuickBar {

    // ===================== 配置区：改成你的模块页面 =====================
    private static final ButtonConfig[] BUTTONS = {
            new ButtonConfig("功能一", "com.example.myapp", "com.example.myapp.MainActivity"),
            new ButtonConfig("功能二", "com.example.myapp", "com.example.myapp.SecondActivity"),
            new ButtonConfig("功能三", "com.example.myapp", "com.example.myapp.ThirdActivity")
    };
    // ===================================================================

    private static final String WECHAT_PKG = "com.tencent.mm";
    private static final String MM_EDIT_TEXT = "com.tencent.mm.ui.widget.MMEditText";
    private static final String MAX_HEIGHT_SCROLL = "com.tencent.mm.view.MaxHeightScrollView";

    private static final int BTN_COLOR = Color.parseColor("#07C160");
    private static final int BTN_TEXT_SIZE = 12;
    private static final long INJECT_DELAY_MS = 800;
    private static final int MAX_RETRY = 10;

    private static volatile boolean injected = false;

    private ChatQuickBar() {
    }

    /** 模块入口：在你的 handleLoadPackage 里调用 */
    public static void init(final ClassLoader loader) {
        hookMMEditText(loader, 0);
    }

    // ------------------------------------------------------------
    // Hook 挂载
    // ------------------------------------------------------------

    private static void hookMMEditText(final ClassLoader loader, final int attempt) {
        try {
            Class<?> editClazz = XposedHelpers.findClassIfExists(MM_EDIT_TEXT, loader);
            if (editClazz == null) {
                retryHook(loader, attempt);
                return;
            }
            for (Constructor<?> c : editClazz.getDeclaredConstructors()) {
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        injectFromEdit(param.thisObject);
                    }
                });
            }
            XposedBridge.log("[ChatQuickBar] MMEditText Hook 已挂载");
        } catch (Throwable t) {
            XposedBridge.log("[ChatQuickBar] hook 异常: " + t);
            retryHook(loader, attempt);
        }
    }

    private static void retryHook(final ClassLoader loader, final int attempt) {
        if (attempt > MAX_RETRY) {
            XposedBridge.log("[ChatQuickBar] 重试超限，放弃（微信版本可能不兼容）");
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                hookMMEditText(loader, attempt + 1);
            }
        }, 2000);
    }

    // ------------------------------------------------------------
    // 注入
    // ------------------------------------------------------------

    private static void injectFromEdit(final View edit) {
        if (edit == null || injected) return;

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    View mhs = findAncestor(edit, MAX_HEIGHT_SCROLL, 6);
                    if (mhs == null) {
                        XposedBridge.log("[ChatQuickBar] 未找到输入框行，跳过");
                        return;
                    }

                    Context ctx = edit.getContext();
                    if (ctx == null) return;
                    LinearLayout row = createButtonRow(ctx);

                    ViewParent rel = mhs.getParent();
                    if (rel != null && rel.getParent() instanceof LinearLayout) {
                        LinearLayout grand = (LinearLayout) rel.getParent();
                        row.setLayoutParams(new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT));
                        grand.addView(row, grand.indexOfChild((View) rel));
                        injected = true;
                        XposedBridge.log("[ChatQuickBar] 注入成功(LinearLayout方案)");
                        return;
                    }

                    if (rel instanceof ViewGroup) {
                        ViewGroup relVg = (ViewGroup) rel;
                        row.setLayoutParams(new android.widget.RelativeLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT));
                        relVg.addView(row, relVg.indexOfChild(mhs));
                        injected = true;
                        XposedBridge.log("[ChatQuickBar] 注入成功(RelativeLayout兜底)");
                    }
                } catch (Throwable t) {
                    XposedBridge.log("[ChatQuickBar] 注入异常: " + t);
                }
            }
        }, INJECT_DELAY_MS);
    }

    private static View findAncestor(View view, String className, int maxDepth) {
        ViewParent node = view.getParent();
        int depth = 0;
        while (node != null && depth < maxDepth) {
            if (node.getClass().getName().equals(className)) {
                return (View) node;
            }
            node = node.getParent();
            depth++;
        }
        return null;
    }

    // ------------------------------------------------------------
    // UI
    // ------------------------------------------------------------

    private static LinearLayout createButtonRow(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        float density = ctx.getResources().getDisplayMetrics().density;
        row.setPadding((int) (10 * density), (int) (5 * density),
                (int) (10 * density), (int) (5 * density));

        for (ButtonConfig cfg : BUTTONS) {
            TextView btn = createButton(ctx, cfg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins((int) (4 * density), 0, (int) (4 * density), 0);
            btn.setLayoutParams(lp);
            row.addView(btn);
        }
        return row;
    }

    private static TextView createButton(final Context ctx, final ButtonConfig cfg) {
        TextView btn = new TextView(ctx);
        btn.setText(cfg.text);
        btn.setTextSize(BTN_TEXT_SIZE);
        btn.setTextColor(Color.WHITE);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(18);
        bg.setColor(BTN_COLOR);
        btn.setBackground(bg);

        float density = ctx.getResources().getDisplayMetrics().density;
        btn.setPadding((int) (12 * density), (int) (6 * density),
                (int) (12 * density), (int) (6 * density));

        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openTargetPage(ctx, cfg);
            }
        });
        return btn;
    }

    private static void openTargetPage(Context ctx, ButtonConfig cfg) {
        try {
            Intent intent = new Intent();
            intent.setClassName(cfg.pkg, cfg.cls);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            XposedBridge.log("[ChatQuickBar] 跳转: " + cfg.pkg + "/" + cfg.cls);
        } catch (Throwable t) {
            XposedBridge.log("[ChatQuickBar] 跳转失败: " + t);
        }
    }

    // ------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------

    public static final class ButtonConfig {
        public final String text;
        public final String pkg;
        public final String cls;

        public ButtonConfig(String text, String pkg, String cls) {
            this.text = text;
            this.pkg = pkg;
            this.cls = cls;
        }
    }
}
