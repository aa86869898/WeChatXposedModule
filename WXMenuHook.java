package com.example.wxmenu;

import android.content.Context;
import android.content.ContextMenu;
import android.graphics.drawable.Drawable;
import android.view.MenuItem;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.result.MethodData;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 微信聊天长按菜单：删按钮 / 注入按钮
 * 独立 Xposed 模块，不依赖 LSPosed / BSH。
 * 唯一第三方依赖：org.luckypray:dexkit:2.2.0
 */
public final class WXMenuHook implements IXposedHookLoadPackage {

    // ===== 配置 =====
    private static final String PKG = "com.tencent.mm";
    private static final int    MY_ID = 0x7E000001;              // 避开微信 100~190
    private static final String[] BAN_TEXT = { "拍一拍", "朗读" };
    // 需要一并屏蔽的微信原生 id（可按实测补）
    private static final int[] BAN_ID = { 171 /*打开*/, 175 /*朗读*/, 110 /*收藏*/ };

    // ===== DexKit 解析结果（只存字符串，防 ClassLoader 泄漏）=====
    private static String sBuilderCls, sBuilderName, sMenuCls;
    private static String sClickCls, sClickName;
    private static Method sTagD;          // ItemDataTag.d() : int
    private static volatile boolean sInstalled;

    @Override public void handleLoadPackage(LoadPackageParam lp) {
        if (!PKG.equals(lp.packageName) || !PKG.equals(lp.processName)) return;
        new Thread(() -> retry(lp.appInfo.sourceDir), "wxmenu-resolve").start();
    }

    private void retry(String apk) {
        for (int i = 0; i < 4; i++) {
            try { if (resolve(apk)) { install(); XposedBridge.log("[WXMenu] installed"); return; } }
            catch (Throwable t) { XposedBridge.log("[WXMenu] resolve#" + i + " " + t); }
            try { Thread.sleep(3000L * (i + 1)); } catch (InterruptedException e) { }
        }
        XposedBridge.log("[WXMenu] unsupported version, feature off");
    }

    // ===== 第 8 章 DexKit 锚点 =====
    private boolean resolve(String apk) throws Exception {
        System.loadLibrary("dexkit");
        DexKitBridge dk = DexKitBridge.create(apk);

        // A5 唯一锚点：菜单构建方法
        var a5 = dk.findMethod().usingStrings("OnCreateContextMMMenux")
                 .getResultAsMethodMatcher();
        if (a5.isEmpty()) return false;
        MethodData m5 = a5.get(0);
        sBuilderCls = m5.getClassName(); sBuilderName = m5.getName();
        sMenuCls = m5.getParamTypes()[0];

        // sanity：MMMenu 必备方法
        Class<?> menuC = Class.forName(sMenuCls);
        menuC.getMethod("size"); menuC.getMethod("findItem", int.class);
        menuC.getMethod("removeItem", int.class);

        // A8 点击回调
        var a8 = dk.findMethod()
                 .usingStrings("context item select failed, null dataTag")
                 .paramTypes("android.view.MenuItem", "int")
                 .getResultAsMethodMatcher();
        if (a8.isEmpty()) return false;
        MethodData m8 = a8.get(0);
        sClickCls = m8.getClassName(); sClickName = m8.getName();

        // A10 ItemDataTag.d()
        for (Field f : Class.forName(sClickCls).getDeclaredFields()) {
            if (f.getType().getSimpleName().equals("ps")) { sTagD = f.getType().getMethod("d"); break; }
        }
        if (sTagD == null) return false;
        return true;
    }

    // ===== 安装 Hook =====
    private void install() throws Exception {
        if (sInstalled) return; sInstalled = true;
        final Class<?> builder = Class.forName(sBuilderCls);
        final Class<?> menuC   = Class.forName(sMenuCls);
        final Class<?> clickC  = Class.forName(sClickCls);
        final Field     listF  = menuC.getField("d"); listF.setAccessible(true);

        // (1) 菜单构建 AFTER：删 + 注入（幂等）
        XposedBridge.hookMethod(
            builder.getDeclaredMethod(sBuilderName, menuC, View.class, ContextMenu.ContextMenuInfo.class),
            new XC_MethodHook() {
                @SuppressWarnings("unchecked")
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object menu = p.args[0];
                        View anchor = (View) p.args[1];
                        Object tag = anchor.getTag();
                        if (tag == null || sTagD == null) return;
                        int pos = (Integer) sTagD.invoke(tag);       // groupId = adapter position

                        // 1.1 按 id 删
                        Method rm = menuC.getMethod("removeItem", int.class);
                        for (int id : BAN_ID) rm.invoke(menu, id);

                        // 1.2 按文案删
                        List<MenuItem> list = (List<MenuItem>) listF.get(menu);
                        for (Iterator<MenuItem> it = list.iterator(); it.hasNext();) {
                            CharSequence t = it.next().getTitle();
                            if (t == null) continue;
                            String s = t.toString().trim();
                            for (String b : BAN_TEXT) if (s.equals(b)) { it.remove(); break; }
                        }

                        // 1.3 注入（幂等守卫）
                        if (menuC.getMethod("findItem", int.class).invoke(menu, MY_ID) != null) return;
                        Drawable icon = moduleIcon(anchor.getContext());
                        Object item = (icon != null)
                            ? menuC.getMethod("m", int.class, CharSequence.class, Drawable.class)
                              .invoke(menu, MY_ID, "我的按钮", icon)
                            : menuC.getMethod("add", int.class, int.class, int.class, CharSequence.class)
                              .invoke(menu, pos, MY_ID, 0, "我的按钮");
                        // 控顺序： list.add(1, (MenuItem) item);
                        // 强制第2行： setBool(item, "D", true);
                    } catch (Throwable t) { XposedBridge.log("[WXMenu] post: " + t); }
                }
            });

        // (2) 点击 BEFORE：拦截自建 id，防止落到 MessBoxComponent 弹「删除消息」
        XposedBridge.hookMethod(
            clickC.getDeclaredMethod(sClickName, MenuItem.class, int.class),
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        MenuItem mi = (MenuItem) p.args[0];
                        if (mi == null || mi.getItemId() != MY_ID) return;
                        XposedBridge.log("[WXMenu] MY BUTTON");
                        onMyAction();
                        for (Field f : p.thisObject.getClass().getDeclaredFields()) {
                            if (f.getType().getSimpleName().equals("ps")) { f.set(p.thisObject, null); break; }
                        }
                    } catch (Throwable t) { XposedBridge.log("[WXMenu] click: " + t); }
                }
            });
    }

    private Drawable moduleIcon(Context ctx) {
        try {
            Context m = ctx.createPackageContext("com.example.wxmenu",
                    Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
            return androidx.core.content.res.ResourcesCompat.getDrawable(m.getResources(), R.drawable.my_icon, null);
        } catch (Throwable t) { return null; }
    }

    private void onMyAction() {
        // 你的业务：Toast / 拉 Activity / 复制消息 / 发 HTTP
    }
}
