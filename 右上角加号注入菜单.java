/*
 * PlusMenuInject.java - 微信主页右上角"+"菜单注入（独立 Xposed 模块）
 *
 * ⚠️ 本文件不是 BSH 插件脚本，请勿在 LSPilot 中启用本插件！
 * 仅供从手机拷贝源码到你的 Android Studio 工程使用。
 *
 * 使用方法（在你自己的模块中）：
 *   if (lpparam.packageName.equals("com.tencent.mm")) {
 *       PlusMenuInject.init(lpparam.classLoader);
 *   }
 */

package com.example.wechatenhance;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ListView;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 微信主页右上角"+"菜单注入自定义入口（独立 Xposed 模块）
 *
 * 逆向结论（微信 8.0.76）：
 *   HomeUI.k   = rg（PlusSubMenuHelper "+菜单控制器"）
 *   rg 继承    = com.tencent.mm.ui.tools.hd
 *   hd.d(int)  = 菜单 PopupWindow 的显示方法（setAdapter + show）
 *   hd.d       = MMListPopupWindow（弹窗），字段 i = ListView
 *
 * 注入：Hook hd.d(int) after（菜单显示完成瞬间）
 *       → 拿 ListView → addHeaderView 自定义菜单项 → 与原生菜单同步出现
 */
public final class PlusMenuInject {

    // ===================== 配置区：改成你的模块页面 =====================
    private static final MenuConfig[] MENU_ITEMS = {
            new MenuConfig("我的模块", "com.example.myapp", "com.example.myapp.MainActivity")
    };
    // ===================================================================

    private static final String CLASS_HOME_UI = "com.tencent.mm.ui.HomeUI";
    private static final String CLASS_HD      = "com.tencent.mm.ui.tools.hd";
    private static final String CLASS_RG      = "com.tencent.mm.ui.rg";

    private static volatile boolean injected = false;

    private PlusMenuInject() {
    }

    /** 模块入口：在你自己的 handleLoadPackage 里调用 */
    public static void init(final ClassLoader loader) {
        hookHdD(loader);     // 主触发：菜单显示时同步注入
        hookHomeUIo(loader); // 兜底触发
    }

    // ==================== 触发点1（主）：hd.d(int) 显示菜单 ====================

    private static void hookHdD(final ClassLoader loader) {
        try {
            Class<?> hdClazz = XposedHelpers.findClassIfExists(CLASS_HD, loader);
            if (hdClazz == null) {
                XposedBridge.log("[PlusMenuInject] hd 类未找到（微信版本可能不同）");
                return;
            }
            XposedHelpers.findAndHookMethod(hdClazz, "d", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object obj = param.thisObject;
                    if (obj == null) return;
                    if (!obj.getClass().getName().equals(CLASS_RG)) return; // 只处理"+菜单"
                    inject(obj);
                }
            });
            XposedBridge.log("[PlusMenuInject] hd.d(int) Hook 已挂载");
        } catch (Throwable t) {
            XposedBridge.log("[PlusMenuInject] hookHdD 异常: " + t);
        }
    }

    // ==================== 触发点2（兜底）：HomeUI.o() ====================

    private static void hookHomeUIo(final ClassLoader loader) {
        try {
            Class<?> homeUIClass = XposedHelpers.findClassIfExists(CLASS_HOME_UI, loader);
            if (homeUIClass == null) return;
            XposedHelpers.findAndHookMethod(homeUIClass, "o", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    if (injected) return;
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Object rg = XposedHelpers.getObjectField(param.thisObject, "k");
                                inject(rg);
                            } catch (Throwable t) {
                                XposedBridge.log("[PlusMenuInject] 兜底注入异常: " + t);
                            }
                        }
                    }, 100);
                }
            });
            XposedBridge.log("[PlusMenuInject] HomeUI.o() 兜底 Hook 已挂载");
        } catch (Throwable t) {
            XposedBridge.log("[PlusMenuInject] hookHomeUIo 异常: " + t);
        }
    }

    // ==================== 核心注入 ====================

    private static void inject(Object rg) {
        if (rg == null || injected) return;
        try {
            Object popup = XposedHelpers.getObjectField(rg, "d");          // MMListPopupWindow
            if (popup == null) return;
            ListView listView = (ListView) XposedHelpers.getObjectField(popup, "i"); // ListView
            if (listView == null) return;

            Context ctx = listView.getContext();
            if (ctx == null) return;

            for (MenuConfig cfg : MENU_ITEMS) {
                View itemView = createMenuItem(ctx, listView, cfg);
                listView.addHeaderView(itemView);
            }
            listView.requestLayout();
            listView.postInvalidate();

            injected = true;
            XposedBridge.log("[PlusMenuInject] 注入成功! 菜单项数=" + MENU_ITEMS.length);
        } catch (Throwable t) {
            XposedBridge.log("[PlusMenuInject] inject 异常: " + t);
        }
    }

    // ==================== UI（自动复制原生菜单项样式） ====================

    private static View createMenuItem(Context ctx, ListView listView, final MenuConfig cfg) {
        float density = ctx.getResources().getDisplayMetrics().density;

        final LinearLayout item = new LinearLayout(ctx);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);

        int itemH = (int) (52 * density);
        android.graphics.drawable.Drawable itemBg = null;
        int textColor = Color.parseColor("#191919");
        float textSize = 16f;

        // 从原生菜单项复制样式
        try {
            android.widget.ListAdapter adapter = listView.getAdapter();
            if (adapter != null && adapter.getCount() > 0) {
                View sample = adapter.getView(0, null, listView);
                if (sample != null) {
                    ViewGroup.LayoutParams slp = sample.getLayoutParams();
                    if (slp != null && slp.height > 0) itemH = slp.height;
                    itemBg = sample.getBackground();
                    TextView stv = findTextView(sample);
                    if (stv != null) {
                        textColor = stv.getCurrentTextColor();
                        textSize = stv.getTextSize() / density;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        item.setLayoutParams(new AbsListView.LayoutParams(
                AbsListView.LayoutParams.MATCH_PARENT, itemH));
        if (itemBg != null) {
            item.setBackground(itemBg);
        } else {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            item.setBackground(bg);
        }

        TextView tv = new TextView(ctx);
        tv.setText(cfg.text);
        tv.setTextSize(textSize);
        tv.setTextColor(textColor);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding((int) (16 * density), 0, (int) (16 * density), 0);
        item.addView(tv);

        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openTargetPage(ctx, cfg);
            }
        });
        return item;
    }

    private static TextView findTextView(View view) {
        if (view == null) return null;
        if (view instanceof TextView) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                TextView found = findTextView(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void openTargetPage(Context ctx, MenuConfig cfg) {
        try {
            Intent intent = new Intent();
            intent.setClassName(cfg.pkg, cfg.cls);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            XposedBridge.log("[PlusMenuInject] 跳转: " + cfg.pkg + "/" + cfg.cls);
        } catch (Throwable t) {
            XposedBridge.log("[PlusMenuInject] 跳转失败: " + t);
        }
    }

    // ==================== 配置 ====================

    public static final class MenuConfig {
        public final String text;
        public final String pkg;
        public final String cls;

        public MenuConfig(String text, String pkg, String cls) {
            this.text = text;
            this.pkg = pkg;
            this.cls = cls;
        }
    }
}
