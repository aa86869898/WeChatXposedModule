package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import com.leshao.v3.Logger;
import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;
import android.database.Cursor;

/**
 * [功能41/46] 全文搜索增强
 * FTS架构:
 *   com.tencent.mm.plugin.fts — 搜索引擎
 *   FTSBaseVoiceSearchUI — 语音搜索UI
 *   logic.q3/r3/s3/t3 — 搜索逻辑
 * 增强:
 *   1. 强制启用语音消息搜索
 *   2. 搜索时自动扩展时间范围
 *   3. 搜索结果不截断
 */
public class SearchEnhance {

    private static volatile boolean sEnabled = true;
    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    private static boolean voiceSearchEnabled = true;
    public static int maxResults = 500;

    public static void hook(ClassLoader cl) {
        if (!sEnabled) return;
        ModuleConfig config = ModuleConfig.load(ContextManager.getPrefs());
        if (config == null || !config.searchEnhanceEnabled) return;

        enableVoiceSearch(cl);
        hookFtsLogic(cl);
        hookSearchUI(cl);
    }

    private static void enableVoiceSearch(ClassLoader cl) {
        try {
            Class<?> cfg = XposedHelpers.findClass(
                    "com.tencent.mm.repairer.config.fts.RepairerConfigFTSVoiceMsgEnable", cl);

            try {
                Object instance = XposedHelpers.callStaticMethod(cfg, "getInstance");
                if (instance != null) {
                    XposedHelpers.setBooleanField(instance, "isEnabled", true);
                    Logger.i("[Search] 语音消息搜索已强制启用");
                }
            } catch (Throwable e1) {
                try {
                    XposedHelpers.setStaticBooleanField(cfg, "isEnabled", true);
                    Logger.i("[Search] 语音搜索(静态字段)已启用");
                } catch (Throwable e2) {
                    for (java.lang.reflect.Field f : cfg.getDeclaredFields()) {
                        if (f.getType() == boolean.class) {
                            f.setAccessible(true);
                            try { f.setBoolean(null, true); } catch (Throwable ignored) {}
                        }
                    }
                    Logger.i("[Search] 语音搜索(字段扫描)已启用");
                }
            }
        } catch (Throwable t) {
            Logger.w("[Search] 语音搜索配置未找到: " + t.getMessage());
        }
    }

    private static void hookFtsLogic(ClassLoader cl) {
        String[] logicClasses = {
            "com.tencent.mm.plugin.fts.logic.q3",
            "com.tencent.mm.plugin.fts.logic.r3",
            "com.tencent.mm.plugin.fts.logic.s3",
            "com.tencent.mm.plugin.fts.logic.t3"
        };

        for (String clsName : logicClasses) {
            try {
                Class<?> ftsLogic = XposedHelpers.findClass(clsName, cl);
                int methodCount = 0;

                for (java.lang.reflect.Method m : ftsLogic.getDeclaredMethods()) {
                    if (m.getReturnType() == Cursor.class) {
                        final String methodName = m.getName();
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                                                Cursor cursor = (Cursor) param.getResult();
                                                                if (cursor != null) {
                                                                    int count = cursor.getCount();
                                                                    if (count > 0) {
                                                                        Logger.i("[Search] FTS查询: "
                                                                                + methodName + " → " + count + "条");
                                                                    }
                                                                }
                                } catch (Throwable e) {
                                    de.robv.android.xposed.XposedBridge.log("LeShaoV3 cb err: " + e);
                                }
                            }
                        });
                        methodCount++;
                    }

                    if (m.getReturnType() == int.class
                            && m.getParameterTypes().length <= 1
                            && !m.getName().equals("hashCode")) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                                                int val = (Integer) param.getResult();
                                                                if (val == 100 || val == 200 || val == 50) {
                                                                    param.setResult(maxResults);
                                                                    Logger.i("[Search] 结果数限制: "
                                                                            + val + " → " + maxResults);
                                                                }
                                } catch (Throwable e) {
                                    de.robv.android.xposed.XposedBridge.log("LeShaoV3 cb err: " + e);
                                }
                            }
                        });
                        methodCount++;
                    }
                }

                if (methodCount > 0) {
                    Logger.i("[Search] " + clsName + " Hook完成 ("
                            + methodCount + "个方法)");
                }
            } catch (Throwable t) {}
        }
    }

    private static void hookSearchUI(ClassLoader cl) {
        try {
            Class<?> searchUI = XposedHelpers.findClass(
                    "com.tencent.mm.plugin.fts.ui.FTSBaseVoiceSearchUI", cl);

            XposedBridge.hookAllMethods(searchUI, "onCreate",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Logger.i("[Search] FTS搜索界面已打开");

                    try {
                        android.app.Activity act = (android.app.Activity) param.thisObject;
                        if (act.getActionBar() != null) {
                            act.getActionBar().setSubtitle(
                                    "语音搜索: " + (voiceSearchEnabled ? "开" : "关"));
                        }
                    } catch (Throwable ignored) {}
                }
            });
            Logger.i("[Search] FTSBaseVoiceSearchUI Hook完成");
        } catch (Throwable t) {
            Logger.w("[Search] FTS UI未找到");
        }
    }
}
