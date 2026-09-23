package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import com.leshao.v3.Logger;
import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ScrollView;
import android.widget.ListView;
import android.widget.Toast;

/**
 * [功能58] 摇一摇自定义
 * 支持动作:
 *   0 = 截图
 *   1 = 滚动到顶部
 *   2 = 发送广播(自定义)
 *   3 = 仅记录日志
 *   4 = 弹出快捷菜单
 */
public class ShakeCustom {

    private static volatile boolean sEnabled = true;
    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    private static int action = 3;
    private static long lastShakeTime = 0;
    private static final long SHAKE_COOLDOWN = 3000;
    private static int shakeCount = 0;
    private static Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 热更新: 设置面板保存后调用, 无需重启微信即可生效 */
    public static void applyConfig(android.content.SharedPreferences prefs) {
        try {
            if (prefs != null) action = prefs.getInt("shake_action", 3);
        } catch (Throwable ignored) {}
    }

    public static void hook(ClassLoader cl) {
        if (!sEnabled) return;
        ModuleConfig config = ModuleConfig.load(ContextManager.getPrefs());
        if (config == null || !config.shakeCustomEnabled) return;
        action = HookConfig.getInt("shake_action", 3);

        hookShakeDetect(cl);
    }

    private static void hookShakeDetect(ClassLoader cl) {
        try {
            Class<?> shakeUI = XposedHelpers.findClass(
                    "com.tencent.mm.plugin.shake.ui.ShakeReportUI", cl);

            int totalHooked = 0;

            XposedBridge.hookAllMethods(shakeUI, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                                        shakeCount = 0;
                                        Logger.i("[Shake] 摇一摇界面已打开 (动作=" + getActionName() + ")");
                    } catch (Throwable e) {
                        de.robv.android.xposed.XposedBridge.log("LeShaoV3 cb err: " + e);
                    }
                }
            });
            totalHooked++;

            XposedBridge.hookAllMethods(shakeUI, "onPause", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                                        Logger.i("[Shake] 摇一摇界面已关闭 (共摇动" + shakeCount + "次)");
                    } catch (Throwable e) {
                        de.robv.android.xposed.XposedBridge.log("LeShaoV3 cb err: " + e);
                    }
                }
            });
            totalHooked++;

            for (java.lang.reflect.Method m : shakeUI.getDeclaredMethods()) {
                final String name = m.getName();
                if (name.toLowerCase().contains("shake")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                                        onShakeDetected(param.thisObject);
                            } catch (Throwable e) {
                                de.robv.android.xposed.XposedBridge.log("LeShaoV3 cb err: " + e);
                            }
                        }
                    });
                    totalHooked++;
                }
            }

            if (totalHooked <= 2) {
                for (java.lang.reflect.Method m : shakeUI.getDeclaredMethods()) {
                    if (m.getReturnType() == void.class
                            && m.getParameterTypes().length == 0
                            && !m.getName().equals("onResume")
                            && !m.getName().equals("onPause")
                            && !m.getName().equals("finish")) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                                                onShakeDetected(param.thisObject);
                                } catch (Throwable e) {
                                    de.robv.android.xposed.XposedBridge.log("LeShaoV3 cb err: " + e);
                                }
                            }
                        });
                        totalHooked++;
                    }
                }
            }

            Logger.i("[Shake] ShakeReportUI Hook完成 (" + totalHooked + "个方法)");
        } catch (Throwable t) {
            Logger.w("[Shake] Hook失败: " + t.getMessage());
        }
    }

    private static void onShakeDetected(final Object activity) {
        long now = System.currentTimeMillis();
        if (now - lastShakeTime < SHAKE_COOLDOWN) return;
        lastShakeTime = now;
        shakeCount++;

        Logger.i("[Shake] 检测到摇动 #" + shakeCount
                + " (动作=" + getActionName() + ")");

        mainHandler.post(new Runnable() {
            public void run() {
                performAction((android.app.Activity) activity);
            }
        });
    }

    private static void performAction(android.app.Activity activity) {
        try {
            switch (action) {
                case 0:
                    performScreenshot(activity);
                    break;
                case 1:
                    scrollToTop(activity);
                    break;
                case 2:
                    Intent intent = new Intent("com.leshao.v3.SHAKE_ACTION");
                    activity.sendBroadcast(intent);
                    Toast.makeText(activity, "摇一摇动作已触发", Toast.LENGTH_SHORT).show();
                    break;
                case 4:
                    showQuickMenu(activity);
                    break;
                default:
                    Logger.i("[Shake] 摇动已记录 (无操作)");
                    break;
            }
        } catch (Throwable t) {
            Logger.w("[Shake] 动作执行失败: " + t.getMessage());
        }
    }

    private static void performScreenshot(android.app.Activity activity) {
        try {
            Intent intent = new Intent("android.intent.action.SCREENSHOT");
            activity.sendBroadcast(intent);
            Toast.makeText(activity, "截图已触发", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Logger.w("[Shake] 截图失败: " + t.getMessage());
        }
    }

    private static void scrollToTop(android.app.Activity activity) {
        try {
            View root = activity.getWindow().getDecorView();
            ScrollView sv = findScrollableView(root);
            ListView lv = findListView(root);

            if (sv != null) {
                sv.smoothScrollTo(0, 0);
                Toast.makeText(activity, "已回到顶部", Toast.LENGTH_SHORT).show();
            } else if (lv != null) {
                lv.smoothScrollToPosition(0);
                Toast.makeText(activity, "已回到顶部", Toast.LENGTH_SHORT).show();
            } else {
                Logger.i("[Shake] 未找到可滚动的View");
            }
        } catch (Throwable t) {
            Logger.w("[Shake] 回顶失败: " + t.getMessage());
        }
    }

    private static void showQuickMenu(android.app.Activity activity) {
        try {
            String[] items = {"截图", "回顶部", "设置"};
            android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(activity)
                    .setTitle("摇一摇快捷操作")
                    .setItems(items, new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            switch (which) {
                                case 0: performScreenshot(activity); break;
                                case 1: scrollToTop(activity); break;
                                case 2:
                                    Toast.makeText(activity, "请在配置中设置",
                                            Toast.LENGTH_SHORT).show();
                                    break;
                            }
                        }
                    })
                    .setNegativeButton("取消", null)
                    .create();
            dlg.show();
        } catch (Throwable t) {
            Logger.w("[Shake] 菜单失败: " + t.getMessage());
        }
    }

    private static ScrollView findScrollableView(View v) {
        if (v instanceof ScrollView) return (ScrollView) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                ScrollView sv = findScrollableView(vg.getChildAt(i));
                if (sv != null) return sv;
            }
        }
        return null;
    }

    private static ListView findListView(View v) {
        if (v instanceof ListView) return (ListView) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                ListView lv = findListView(vg.getChildAt(i));
                if (lv != null) return lv;
            }
        }
        return null;
    }

    private static String getActionName() {
        switch (action) {
            case 0: return "截图";
            case 1: return "回顶部";
            case 2: return "自定义广播";
            case 4: return "快捷菜单";
            default: return "仅记录";
        }
    }
}
