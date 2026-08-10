package com.leshao.v3.hook;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.leshao.v3.IconLoader;
import com.leshao.v3.LogWriter;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 微信主页右上角"+"菜单注入自定义入口
 *
 * 严格参考 /workspace/右上角加号注入菜单.java 实现，并增强多版本兼容：
 *  - 触发点1: com.tencent.mm.ui.tools.hd.d(int) after（菜单显示后注入）
 *  - 触发点2: HomeUI.o() 兜底
 */
public final class PlusMenuInject {

    private static final MenuConfig[] MENU_ITEMS = {
            new MenuConfig("LS助手", "com.leshao.v3", "com.leshao.v3.SettingsActivity")
    };

    // 参考文件(微信 8.0.76)的类名
    private static final String CLASS_HOME_UI = "com.tencent.mm.ui.HomeUI";
    private static final String CLASS_HD      = "com.tencent.mm.ui.tools.hd";
    private static final String CLASS_RG      = "com.tencent.mm.ui.rg";

    private PlusMenuInject() {
    }

    private static Class<?> findClassIfExists(String className, ClassLoader loader) {
        try {
            return XposedHelpers.findClass(className, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 模块入口：在 handleLoadPackage 里调用 */
    public static void init(final ClassLoader loader) {
        hookHdD(loader);     // 主触发：菜单显示时同步注入
        hookHomeUIo(loader); // 兜底触发
    }

    // ==================== 触发点1（主）：hd.d(int) 显示菜单 ====================

    private static void hookHdD(final ClassLoader loader) {
        try {
            Class<?> hdClazz = findClassIfExists(CLASS_HD, loader);
            if (hdClazz == null) {
                LogWriter.log("PlusMenuInject", "[PlusMenuInject] hd 类未找到(" + CLASS_HD + ")，微信版本可能不同");
                return;
            }
            // 注意: 本项目环境 R8 改写 XposedHelpers，varargs findAndHookMethod 不可用，
            //       统一使用 findClass + getDeclaredMethod + XposedBridge.hookMethod 模式
            java.lang.reflect.Method m = null;
            try {
                m = hdClazz.getDeclaredMethod("d", int.class);
            } catch (NoSuchMethodException nsme) {
                LogWriter.log("PlusMenuInject", "[PlusMenuInject] hd.d(int) 方法未找到: " + nsme);
                return;
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object obj = param.thisObject;
                    if (obj == null) return;
                    // 参考文件：严格检查"+菜单"类名；宽松化：类名含 rg 后缀或拥有 ListPopupWindow 字段
                    if (!isPlusMenu(obj)) return;
                    LogWriter.log("PlusMenuInject", "[PlusMenuInject] hd.d(int) 触发 obj=" + obj.getClass().getName());
                    inject(obj);
                }
            });
            LogWriter.log("PlusMenuInject", "[PlusMenuInject] hd.d(int) Hook 已挂载");
        } catch (Throwable t) {
            LogWriter.log("PlusMenuInject", "[PlusMenuInject] hookHdD 异常: " + t);
        }
    }

    // ==================== 触发点2（兜底）：HomeUI.o() ====================

    private static void hookHomeUIo(final ClassLoader loader) {
        try {
            Class<?> homeUIClass = findClassIfExists(CLASS_HOME_UI, loader);
            if (homeUIClass == null) {
                LogWriter.log("PlusMenuInject", "[PlusMenuInject] HomeUI 类未找到(" + CLASS_HOME_UI + ")");
                return;
            }
            java.lang.reflect.Method m = null;
            try {
                m = homeUIClass.getDeclaredMethod("o");
            } catch (NoSuchMethodException nsme) {
                LogWriter.log("PlusMenuInject", "[PlusMenuInject] HomeUI.o() 方法未找到: " + nsme);
                return;
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                // 参考文件：HomeUI.k 字段即"+菜单控制器" rg
                                Object rg = null;
                                try {
                                    rg = XposedHelpers.getObjectField(param.thisObject, "k");
                                } catch (Throwable ignored) {}
                                if (rg == null) rg = findPlusHelper(param.thisObject);
                                if (rg == null) {
                                    LogWriter.log("PlusMenuInject", "[PlusMenuInject] 兜底注入: 未找到 rg，HomeUI=" + param.thisObject.getClass().getName());
                                    return;
                                }
                                LogWriter.log("PlusMenuInject", "[PlusMenuInject] 兜底注入 rg=" + rg.getClass().getName());
                                inject(rg);
                            } catch (Throwable t) {
                                LogWriter.log("PlusMenuInject", "[PlusMenuInject] 兜底注入异常: " + t);
                            }
                        }
                    }, 100);
                }
            });
            LogWriter.log("PlusMenuInject", "[PlusMenuInject] HomeUI.o() 兜底 Hook 已挂载");
        } catch (Throwable t) {
            LogWriter.log("PlusMenuInject", "[PlusMenuInject] hookHomeUIo 异常: " + t);
        }
    }

    /**
     * 在 HomeUI 实例的字段中寻找 "+菜单控制器"（PlusSubMenuHelper）。
     * 参考文件用字段 "k"，但不同微信版本字段名可能不同，这里遍历所有字段，
     * 找到第一个拥有 popup(ListView) 的可注入对象。
     */
    private static Object findPlusHelper(Object homeUI) {
        if (homeUI == null) return null;
        try {
            java.lang.reflect.Field[] fields = homeUI.getClass().getDeclaredFields();
            for (java.lang.reflect.Field f : fields) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(homeUI);
                    if (val == null) continue;
                    // 跳过基本类型/常见 View/Activity
                    if (val instanceof View || val instanceof android.app.Activity) continue;
                    String clsName = val.getClass().getName();
                    if (clsName == null) continue;
                    // 候选：字段类型包含 ui 且具有 popup 字段
                    if (hasPopupField(val)) {
                        return val;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean hasPopupField(Object obj) {
        try {
            for (java.lang.reflect.Field f : obj.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v != null && v.getClass().getName().contains("ListPopupWindow")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isPlusMenu(Object obj) {
        String clsName = obj.getClass().getName();
        if (clsName.equals(CLASS_RG)) return true;
        if (clsName.startsWith("com.tencent.mm.ui.") && clsName.endsWith(".rg")) return true;
        return false;
    }

    // ==================== 核心注入 ====================

    private static void inject(Object rg) {
        if (rg == null) return;
        try {
            // 参考文件：rg.d = MMListPopupWindow，popup.i = ListView
            Object popup = null;
            try {
                popup = XposedHelpers.getObjectField(rg, "d");
            } catch (Throwable ignored) {}
            if (popup == null) popup = findPopup(rg); // 字段名兜底扫描
            if (popup == null) {
                LogWriter.log("PlusMenuInject", "[PlusMenuInject] inject: 未找到 popup (rg=" + rg.getClass().getName() + ")");
                return;
            }

            ListView listView = null;
            try {
                listView = (ListView) XposedHelpers.getObjectField(popup, "i");
            } catch (Throwable ignored) {}
            if (listView == null) listView = findListView(popup); // 字段名兜底扫描
            if (listView == null) {
                LogWriter.log("PlusMenuInject", "[PlusMenuInject] inject: 未找到 ListView (popup=" + popup.getClass().getName() + ")");
                return;
            }

            if ("leshao_plus_menu".equals(listView.getTag())) return;

            Context ctx = listView.getContext();
            if (ctx == null) return;

            for (MenuConfig cfg : MENU_ITEMS) {
                View itemView = createMenuItem(ctx, listView, cfg);
                try {
                    listView.addHeaderView(itemView);
                } catch (Throwable th) {
                    LogWriter.log("PlusMenuInject", "[PlusMenuInject] addHeaderView 异常: " + th);
                }
            }
            listView.setTag("leshao_plus_menu");
            listView.requestLayout();
            listView.postInvalidate();

            LogWriter.log("PlusMenuInject", "[PlusMenuInject] 注入成功! 菜单项数=" + MENU_ITEMS.length);
        } catch (Throwable t) {
            LogWriter.log("PlusMenuInject", "[PlusMenuInject] inject 异常: " + t);
        }
    }

    /** 扫描对象字段，找第一个类型含 ListPopupWindow 的对象 */
    private static Object findPopup(Object owner) {
        try {
            for (java.lang.reflect.Field f : owner.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(owner);
                if (v != null && v.getClass().getName().contains("ListPopupWindow")) {
                    return v;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 扫描 popup 对象字段，找第一个 ListView */
    private static ListView findListView(Object popup) {
        try {
            for (java.lang.reflect.Field f : popup.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(popup);
                if (v instanceof ListView) return (ListView) v;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ==================== UI（自动复制原生菜单项样式） ====================

    private static View createMenuItem(Context ctx, ListView listView, final MenuConfig cfg) {
        float density = ctx.getResources().getDisplayMetrics().density;

        LinearLayout item = new LinearLayout(ctx);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);

        int itemH = (int) (52 * density);
        int leftPad = (int) (16 * density);
        int iconSize = (int) (16 * density);
        int iconTextGap = (int) (14 * density);
        android.graphics.drawable.Drawable itemBg = null;
        int textColor = 0xFF191919;
        float textSize = 16f;

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
                    ImageView sIcon = findImageView(sample);
                    if (sIcon != null) {
                        ViewGroup.LayoutParams silp = sIcon.getLayoutParams();
                        if (silp != null) {
                            iconSize = Math.max(silp.width, silp.height);
                        }
                        ViewGroup.MarginLayoutParams smlp = (silp instanceof ViewGroup.MarginLayoutParams)
                                ? (ViewGroup.MarginLayoutParams) silp : null;
                        if (smlp != null && smlp.leftMargin > 0) {
                            leftPad = smlp.leftMargin;
                        }
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
            bg.setColor(0xFFFFFFFF);
            item.setBackground(bg);
        }

        ImageView icon = new ImageView(ctx);
        Drawable d = IconLoader.load(ctx, IconLoader.IC_LESHAO_ICON, 16);
        if (d != null) {
            icon.setImageDrawable(d);
        }
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(iconSize, iconSize);
        ilp.leftMargin = leftPad;
        ilp.rightMargin = iconTextGap;
        item.addView(icon, ilp);

        TextView tv = new TextView(ctx);
        tv.setText(cfg.text);
        tv.setTextSize(textSize);
        tv.setTextColor(textColor);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(0, 0, (int) (16 * density), 0);
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

    private static ImageView findImageView(View view) {
        if (view == null) return null;
        if (view instanceof ImageView) return (ImageView) view;
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                ImageView found = findImageView(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void openTargetPage(Context ctx, MenuConfig cfg) {
        try {
            if (ctx instanceof android.app.Activity) {
                com.leshao.v3.ui.MainActivity.open((android.app.Activity) ctx);
            } else {
                Intent intent = new Intent();
                intent.setClassName(cfg.pkg, cfg.cls);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
            }
            LogWriter.log("PlusMenuInject", "[PlusMenuInject] 跳转: " + cfg.pkg + "/" + cfg.cls);
        } catch (Throwable t) {
            LogWriter.log("PlusMenuInject", "[PlusMenuInject] 跳转失败: " + t);
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
