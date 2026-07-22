package com.leshao.wechat;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    public static Context wechatContext;
    public static Activity currentActivity;
    public static Handler mainHandler;
    public static ClassLoader classLoader;
    public static String apkPath;
    public static android.content.SharedPreferences prefs;
    public static int wxVersion = 0;
    public static String wxVersionName = "";
    private static boolean inited = false;
    private static boolean tabHooked = false;
    private static boolean cardInjected = false;
    private static android.app.Activity cardActivity = null;
    private static boolean settingTabHooked = false;

    private static final String TAG = "LeShaoWeChat";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.tencent.mm".equals(lpparam.packageName)) return;
        Utils.initLogEarly(lpparam.packageName);
        log(">>> handleLoadPackage: " + lpparam.packageName + " (pid=" + android.os.Process.myPid() + ")");
        log(">>> processName=" + lpparam.processName);

        mainHandler = new Handler(Looper.getMainLooper());
        classLoader = lpparam.classLoader;
        apkPath = lpparam.appInfo.sourceDir;

        try { wxVersion = XposedHelpers.getIntField(lpparam.appInfo, "versionCode"); }
        catch (Throwable t) { wxVersion = 0; XposedBridge.log(TAG + " wxVersion err: " + t.getMessage()); }
        try { wxVersionName = (String) XposedHelpers.getObjectField(lpparam.appInfo, "versionName"); }
        catch (Throwable t) { wxVersionName = ""; XposedBridge.log(TAG + " wxVersionName err: " + t.getMessage()); }

        log("━━━ START v2.1 wx=" + wxVersionName + " (" + wxVersion + ") ━━━");

        // ===== 获取Context =====
        try {
            XposedHelpers.findAndHookMethod("com.tencent.mm.app.MMApplicationLike",
                lpparam.classLoader, "onCreate",
                new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            wechatContext = (Context) XposedHelpers.callMethod(p.thisObject, "getApplication");
                            log("Context from MMAppLike OK");
                        } catch (Exception e) { log("MMAppLike err: " + e.getMessage()); }
                        if (wechatContext != null) doInit();
                    }
                });
        } catch (Throwable t) { log("Hook MMAppLike err: " + t.getMessage()); }

        // ===== 入口1: LauncherUI Tab长按 =====
        hookLauncherUI(lpparam);

        // ===== 入口2: 设置页卡片 =====
        hookSettingsPage(lpparam);

        // ===== 入口3: Activity兜底(generic Activity onCreate) =====
        hookAnyActivity(lpparam);

        // ===== 入口4: 音量键长按 =====
        hookVolumeKey(lpparam);

        // ===== 延迟兜底获取Context =====
        mainHandler.postDelayed(new Runnable() {
            int r = 0;
            public void run() {
                if (wechatContext != null || r >= 30) { log("Context delay: done r=" + r); return; }
                try {
                    Class<?> mc = classLoader.loadClass("com.tencent.mm.sdk.platformtools.MMApplicationContext");
                    wechatContext = (Context) XposedHelpers.callStaticMethod(mc, "getContext");
                    if (wechatContext != null) { log("Context from MMAppCtx OK"); doInit(); }
                } catch (Exception e) {}
                r++;
                mainHandler.postDelayed(this, 1000);
            }
        }, 2000);

        log("━━━ hooks registered ━━━");
    }

    // ============= 初始化 =============
    private void doInit() {
        if (wechatContext == null || inited) return;
        inited = true;
        XposedBridge.log(TAG + " INIT start, wechatContext=" + wechatContext);

        try {
            Utils.initLog(wechatContext);
            XposedBridge.log(TAG + " Utils.initLog OK, path=" + Utils.getLogPath());
        } catch (Exception e) { XposedBridge.log(TAG + " Utils.initLog err: " + e.getMessage()); }

        try {
            prefs = wechatContext.getSharedPreferences("leshao_v21_prefs", Context.MODE_PRIVATE);
            ModuleSettings.init(wechatContext, prefs);
            XposedBridge.log(TAG + " ModuleSettings init OK");
        } catch (Exception e) { XposedBridge.log(TAG + " ModuleSettings err: " + e.getMessage()); }

        try { ThemeEngine.init(); XposedBridge.log(TAG + " ThemeEngine init OK"); }
        catch (Exception e) { XposedBridge.log(TAG + " ThemeEngine err: " + e.getMessage()); }

        try { Toast.makeText(wechatContext, "乐少助手v2.1 已激活\n长按底部Tab打开面板", Toast.LENGTH_LONG).show(); }
        catch (Exception e) {}

        mainHandler.postDelayed(new Runnable() { public void run() {
            try { WeChatHooks.init(classLoader, apkPath); XposedBridge.log(TAG + " WeChatHooks init OK"); } catch (Exception e) { XposedBridge.log(TAG + " WeChatHooks err: " + e.getMessage()); }
            try { TTSManager.init(wechatContext); } catch (Exception e) {}
            try { MusicManager.init(wechatContext); } catch (Exception e) {}
            try { MediaAssistant.init(wechatContext); } catch (Exception e) {}
            try { MassMessenger.init(wechatContext); } catch (Exception e) {}
            XposedBridge.log(TAG + " INIT all done");
        }}, 2000);
    }

    // ============= 入口4: 音量键长按触发 =============
    private static long volDownTime = 0;
    private static boolean volTriggered = false;
    private void hookVolumeKey(XC_LoadPackage.LoadPackageParam lpparam) {
        XC_MethodHook keyHook = new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                android.view.KeyEvent ev;
                if (p.args[0] instanceof android.view.KeyEvent) {
                    ev = (android.view.KeyEvent) p.args[0];
                } else if (p.args.length >= 2 && p.args[1] instanceof android.view.KeyEvent) {
                    ev = (android.view.KeyEvent) p.args[1];
                } else return;

                Activity act = (Activity) p.thisObject;
                if (!"com.tencent.mm".equals(act.getPackageName())) return;
                currentActivity = act;

                if (ev.getKeyCode() == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
                    if (ev.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                        volDownTime = System.currentTimeMillis();
                        volTriggered = false;
                        log("VOL- DOWN");
                    } else if (ev.getAction() == android.view.KeyEvent.ACTION_UP) {
                        log("VOL- UP held=" + (System.currentTimeMillis() - volDownTime) + "ms");
                        volDownTime = 0;
                    }
                    return;
                }
                if (ev.getKeyCode() == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
                    if (ev.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                        log("VOL+ DOWN");
                        if (volDownTime > 0 && !volTriggered) {
                            long held = System.currentTimeMillis() - volDownTime;
                            if (held >= 300) {
                                volTriggered = true;
                                p.setResult(true);
                                log("音量键触发 held=" + held + "ms");
                                final Activity a = currentActivity;
                                mainHandler.post(new Runnable() { public void run() {
                                    log("showMainPanel call: act=" + (a!=null?a.getClass().getSimpleName():"null") + " finishing=" + (a!=null?a.isFinishing():"?"));
                                    ModuleUI.showMainPanel(a);
                                }});
                            }
                        }
                    }
                    volDownTime = 0;
                }
            }
        };
        // Hook Activity.class (for dispatchKeyEvent which always passes through)
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "dispatchKeyEvent", android.view.KeyEvent.class, keyHook);
            log("Hook dispatchKeyEvent OK");
        } catch (Throwable t) { log("Hook dispatchKeyEvent err: " + t.getMessage()); }
        // Hook LauncherUI directly (bypass override issue)
        try {
            Class<?> lui = lpparam.classLoader.loadClass("com.tencent.mm.ui.LauncherUI");
            XposedHelpers.findAndHookMethod(lui, "onKeyDown", int.class, android.view.KeyEvent.class, keyHook);
            log("Hook LauncherUI.onKeyDown OK");
        } catch (Throwable t) { log("Hook LauncherUI.onKeyDown err: " + t.getMessage()); }
        try {
            Class<?> lui = lpparam.classLoader.loadClass("com.tencent.mm.ui.LauncherUI");
            XposedHelpers.findAndHookMethod(lui, "onKeyUp", int.class, android.view.KeyEvent.class, keyHook);
            log("Hook LauncherUI.onKeyUp OK");
        } catch (Throwable t) { log("Hook LauncherUI.onKeyUp err: " + t.getMessage()); }
        // Generic Activity onKeyDown/onKeyUp as fallback
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onKeyDown", int.class, android.view.KeyEvent.class, keyHook);
            log("Hook Activity.onKeyDown OK");
        } catch (Throwable t) { log("Hook Activity.onKeyDown err: " + t.getMessage()); }
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onKeyUp", int.class, android.view.KeyEvent.class, keyHook);
            log("Hook Activity.onKeyUp OK");
        } catch (Throwable t) { log("Hook Activity.onKeyUp err: " + t.getMessage()); }
    }

    // ============= 入口1: LauncherUI Tab长按 =============
    private void hookLauncherUI(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("com.tencent.mm.ui.LauncherUI",
                lpparam.classLoader, "onCreate", Bundle.class,
                new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        currentActivity = (Activity) p.thisObject;
                        log("LauncherUI.onCreate — start injection");
                        startTabInjection(currentActivity);
                    }
                });
            log("Hook LauncherUI.onCreate OK");
        } catch (Throwable t) { log("Hook LauncherUI.onCreate err: " + t.getMessage()); }

        try {
            XposedHelpers.findAndHookMethod("com.tencent.mm.ui.LauncherUI",
                lpparam.classLoader, "onResume",
                new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        currentActivity = (Activity) p.thisObject;
                        log("LauncherUI.onResume — start injection");
                        startTabInjection(currentActivity);
                    }
                });
            log("Hook LauncherUI.onResume OK");
        } catch (Throwable t) { log("Hook LauncherUI.onResume err: " + t.getMessage()); }

        // hook onPostResume — 此时View已完全准备好
        try {
            XposedHelpers.findAndHookMethod("com.tencent.mm.ui.LauncherUI",
                lpparam.classLoader, "onPostResume",
                new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        currentActivity = (Activity) p.thisObject;
                        log("LauncherUI.onPostResume — start injection");
                        startTabInjection(currentActivity);
                    }
                });
            log("Hook LauncherUI.onPostResume OK");
        } catch (Throwable t) { log("Hook LauncherUI.onPostResume skip (may be expected): " + t.getClass().getSimpleName()); }
    }

    private void startTabInjection(final Activity act) {
        if (act == null) { log("startTabInjection: act=null"); return; }
        mainHandler.postDelayed(new Runnable() { public void run() {
            if (tabHooked) { log("tabInjection: already hooked"); return; }
            try {
                View root = act.getWindow().getDecorView();
                log("tabInjection: root=" + (root != null ? root.getClass().getSimpleName() : "null"));
                if (root != null) {
                    dumpViewTree(root, 0);
                    hookAllTextViews(root, act);
                }
            } catch (Exception e) { log("tabInjection err: " + e.getMessage()); }
        }}, 1000);

        // 多次重试
        mainHandler.postDelayed(new Runnable() {
            int retry = 0;
            public void run() {
                if (tabHooked || retry >= 20) { log("tabInjection retry stop: hooked=" + tabHooked + " r=" + retry); return; }
                try {
                    View root = act.getWindow().getDecorView();
                    if (root != null) {
                        if (retry % 5 == 0) dumpViewTree(root, 0);
                        hookAllTextViews(root, act);
                    }
                } catch (Exception e) {}
                retry++;
                mainHandler.postDelayed(this, 600);
            }
        }, 2500);
    }

    private void hookAllTextViews(View v, final Activity act) {
        if (tabHooked) return;
        // Collect ALL tab TextViews and group by parent
        java.util.List<TextView> tabViews = new java.util.ArrayList<>();
        collectTabTextViews(v, tabViews);
        if (tabViews.isEmpty()) return;

        // Group by parent ViewGroup
        java.util.Map<ViewGroup, java.util.List<TextView>> parentMap = new java.util.LinkedHashMap<>();
        for (TextView tv : tabViews) {
            ViewGroup p = (ViewGroup) tv.getParent();
            if (p == null) continue;
            if (!parentMap.containsKey(p)) parentMap.put(p, new java.util.ArrayList<TextView>());
            parentMap.get(p).add(tv);
        }

        // Find the parent that contains the most tab texts (need >=3 of the 4)
        ViewGroup bestParent = null;
        int bestCount = 0;
        for (java.util.Map.Entry<ViewGroup, java.util.List<TextView>> e : parentMap.entrySet()) {
            int cnt = e.getValue().size();
            if (cnt > bestCount) { bestCount = cnt; bestParent = e.getKey(); }
        }

        if (bestParent != null && bestCount >= 2) {
            log("  >>> BottomTabBar found: " + bestParent.getClass().getSimpleName() + " tabs=" + bestCount);
            for (TextView tv : parentMap.get(bestParent)) {
                log("    tab: " + tv.getText());
            }
        }

        if (bestParent == null || bestCount < 3) {
            log("  BottomTabBar NOT confirmed (best=" + bestCount + "), will retry");
            return;
        }

        // Hook ALL tab items in the confirmed bottom bar
        tabHooked = true;
        for (final TextView tv : parentMap.get(bestParent)) {
            final String tabText = tv.getText().toString();
            tv.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View vv) {
                    log("Tab长按触发: " + tabText);
                    try {
                        Activity a = currentActivity != null ? currentActivity : act;
                        ModuleUI.showMainPanel(a);
                    } catch (Exception e) {
                        Utils.xlog("Tab长按弹窗异常: " + e.getClass().getName() + ": " + e.getMessage());
                        try { android.widget.Toast.makeText(vv.getContext(), "弹窗失败: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
                        catch (Exception e2) {}
                    }
                    return true;
                }
            });
            tv.setLongClickable(true);
        }
        log("✅ BottomTabBar已注入: " + bestCount + "个Tab");
    }

    private void collectTabTextViews(View v, java.util.List<TextView> out) {
        if (v instanceof TextView) {
            String text = ((TextView) v).getText().toString();
            if (isTabText(text)) out.add((TextView) v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                collectTabTextViews(vg.getChildAt(i), out);
            }
        }
    }

    private boolean isTabText(String text) {
        if (text == null || text.isEmpty()) return false;
        // 微信底部Tab文字: 微信/通讯录/发现/我
        // 英文环境: WeChat/Contacts/Discover/Me
        // 微信新版可能用"消息"代替"微信"
        return "微信".equals(text) || "通讯录".equals(text) || "发现".equals(text) || "我".equals(text)
            || "消息".equals(text)
            || "WeChat".equals(text) || "Contacts".equals(text) || "Discover".equals(text) || "Me".equals(text)
            || "Chats".equals(text);
    }

    // ============= 入口2: 设置页卡片 =============
    private void hookSettingsPage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class,
                new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        Activity act = (Activity) p.thisObject;
                        String cn = act.getClass().getName().toLowerCase();
                        if ((cn.contains("setting") || cn.contains("etting"))
                            && !cn.contains("notification") && !cn.contains("ringtone")
                            && !cn.contains("alarm")) {
                            currentActivity = act;
                            log("Settings page: " + act.getClass().getSimpleName());
                            injectSettingsCard(act);
                        }
                    }
                });
            log("Hook Activity.onCreate for settings OK");
        } catch (Throwable t) { log("Hook Activity.onCreate err: " + t.getMessage()); }
        // 监听Activity销毁，重置卡片标记
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onDestroy",
                new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        Activity act = (Activity) p.thisObject;
                        if (act == cardActivity) {
                            cardInjected = false;
                            cardActivity = null;
                        }
                    }
                });
            log("Hook Activity.onDestroy for card reset OK");
        } catch (Throwable t) { log("Hook Activity.onDestroy err: " + t.getMessage()); }
    }

    private void injectSettingsCard(final Activity act) {
        if (cardInjected && cardActivity == act) { log("injectSettingsCard: already injected for this activity"); return; }
        if (!ModuleSettings.entryCardVisible) return;
        cardActivity = act;
        cardInjected = false;
        mainHandler.postDelayed(new Runnable() {
            int retry = 0;
            public void run() {
                if (cardInjected || retry >= 15) return;
                try {
                    View root = act.getWindow().getDecorView();
                    if (retry == 0) log("injectSettingsCard: root=" + (root != null ? root.getClass().getSimpleName() : "null"));
                    if (findScrollContainerAndInject(root, act)) {
                        cardInjected = true;
                        log("Settings card injected OK");
                    }
                } catch (Exception e) { log("injectSettingsCard err: " + e.getMessage()); }
                retry++;
                mainHandler.postDelayed(this, 800);
            }
        }, 1500);
    }

    private boolean findScrollContainerAndInject(View v, Activity act) {
        // 优先找ScrollView/RecyclerView里的第一个LinearLayout子容器 (设置页结构)
        String cls = v.getClass().getName();
        if (cls.contains("RecyclerView") || cls.contains("ScrollView")
            || cls.contains("ListView") || cls.contains("NestedScrollView")) {
            log("  Settings scroll container found: " + v.getClass().getSimpleName());
            ViewGroup sc = (ViewGroup) v;
            if (sc.getChildCount() > 0) {
                // 找到第一个子容器LinearLayout
                View first = sc.getChildAt(0);
                if (first instanceof ViewGroup) {
                    ViewGroup container = (ViewGroup) first;
                    // 在第一个位置插入卡片
                    container.addView(buildCard(act), 0);
                    log("  Settings card inserted at index 0");
                    return true;
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (findScrollContainerAndInject(vg.getChildAt(i), act)) return true;
            }
        }
        return false;
    }

    // ============= 入口3.5: WindowManager全局悬浮按钮 =============
    private static boolean floatBtnAdded = false;
    private static android.view.WindowManager floatWm;
    private static android.view.View floatView;
    private void addFloatingButton(final Activity act) {
        if (floatBtnAdded) return;
        try {
            floatWm = (android.view.WindowManager) act.getSystemService(android.content.Context.WINDOW_SERVICE);
            android.widget.TextView btn = new android.widget.TextView(act);
            btn.setText("乐");
            btn.setTextColor(0xFFFFFFFF);
            btn.setTextSize(14);
            btn.setGravity(android.view.Gravity.CENTER);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            bg.setColor(0xE6009688);
            btn.setBackground(bg);
            int size = Utils.dp(act, 44);
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                size, size,
                android.os.Build.VERSION.SDK_INT >= 26
                    ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : android.view.WindowManager.LayoutParams.TYPE_PHONE,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.RIGHT;
            lp.x = Utils.dp(act, 16);
            lp.y = Utils.dp(act, 120);
            btn.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) {
                    log("浮动按钮点击");
                    ModuleUI.showMainPanel(act);
                }
            });
            floatView = btn;
            floatWm.addView(floatView, lp);
            floatBtnAdded = true;
            log("浮动按钮已添加(WM) act=" + act.getClass().getSimpleName());
        } catch (Throwable t) { log("浮动按钮失败: " + t.getMessage()); }
    }

    // ============= 入口3: 兜底 - hook所有Activity onResume找View =============
    private void hookAnyActivity(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume",
                new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        if (tabHooked && cardInjected && settingTabHooked) return;
                        final Activity act = (Activity) p.thisObject;
                        if (!"com.tencent.mm".equals(act.getPackageName())) return;
                        currentActivity = act;
                        String cn = act.getClass().getSimpleName();

                        // 如果LauncherUI Tab还没注入, 再多试
                        if (!tabHooked && cn.contains("LauncherUI")) {
                            log("Fallback LauncherUI.onResume");
                            startTabInjection(act);
                        }

                        // Hook 设置页Tab中的"插件"等条目
                        if (!settingTabHooked && cn.toLowerCase().contains("setting")) {
                            log("Fallback Settings activity: " + cn);
                            injectSettingsCard(act);
                            // Also try to find "插件" related items
                            tryInjectSettingsItems(act);
                        }
                    }
                });
            log("Hook Activity.onResume OK");
        } catch (Throwable t) { log("Hook Activity.onResume err: " + t.getMessage()); }
    }

    private void tryInjectSettingsItems(final Activity act) {
        mainHandler.postDelayed(new Runnable() { public void run() {
            try {
                View root = act.getWindow().getDecorView();
                if (root != null) {
                    injectSettingsEntry(root, act);
                }
            } catch (Exception e) {}
        }}, 2000);
    }

    private void injectSettingsEntry(View v, final Activity act) {
        if (settingTabHooked) return;
        if (v instanceof TextView) {
            String text = ((TextView) v).getText().toString();
            if ("插件".equals(text)) {
                log("  Found '插件' in settings, injecting");
                ViewGroup parent = (ViewGroup) v.getParent();
                if (parent != null) {
                    parent.addView(buildCard(act), parent.indexOfChild(v));
                    settingTabHooked = true;
                    log("✅ Settings entry injected near 插件");
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                injectSettingsEntry(vg.getChildAt(i), act);
                if (settingTabHooked) return;
            }
        }
    }

    // ============= 入口卡片View =============
    private View buildCard(Activity act) {
        android.widget.LinearLayout c = new android.widget.LinearLayout(act);
        c.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        c.setGravity(android.view.Gravity.CENTER_VERTICAL);
        c.setPadding(dp(act, 16), dp(act, 12), dp(act, 16), dp(act, 12));
        c.setBackground(ThemeEngine.createPrimaryBtnBg(act, 12));
        c.setClickable(true);

        TextView tv = new TextView(act);
        tv.setText("乐少多功能工具箱 v2.1");
        tv.setTextSize(15); tv.setTextColor(ThemeEngine.thWhite());
        tv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        c.addView(tv);

        android.widget.Button btn = new android.widget.Button(act);
        btn.setText("进入"); btn.setTextSize(12); btn.setTextColor(ThemeEngine.thWhite());
        btn.setAllCaps(false); btn.setBackground(ThemeEngine.createOutlineBtnBg(act, 8));
        btn.setPadding(dp(act, 16), dp(act, 4), dp(act, 16), dp(act, 4));
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { ModuleUI.showMainPanel(act); }
        });
        c.addView(btn);

        c.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { ModuleUI.showMainPanel(act); }
        });

        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(act, 12), dp(act, 8), dp(act, 12), dp(act, 4));
        c.setLayoutParams(lp);
        return c;
    }

    // ============= View树dump (调试用) =============
    private void dumpViewTree(View v, int depth) {
        if (depth > 3) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName());
        if (v instanceof TextView) {
            sb.append(" text='").append(((TextView) v).getText()).append("'");
        }
        if (v.getId() != View.NO_ID) {
            try {
                String resName = v.getResources().getResourceEntryName(v.getId());
                sb.append(" id=").append(resName);
            } catch (Exception e) {}
        }
        String info = sb.toString();
        if (info.length() > 120) info = info.substring(0, 117) + "...";
        log(info);
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                dumpViewTree(vg.getChildAt(i), depth + 1);
            }
        }
    }

    // ============= 日志 =============
    private static void log(String msg) {
        XposedBridge.log(TAG + " " + msg);
        try { Utils.xlog(msg); } catch (Throwable ignore) {}
    }

    private int dp(Context ctx, int d) { return Utils.dp(ctx, d); }
}
