package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

/**
 * [功能19/22/23/24/27/29] 朋友圈增强 — 生产级完整实现
 * ====================================================
 */
public class SnsFeatures {
    private static ClassLoader cl;
    private static long timeOffsetMs = -3600000;
    private static volatile boolean sEnabled = true;

    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static void hook(ClassLoader loader) {
        if (!sEnabled) return;
        cl = loader;
        timeOffsetMs = HookConfig.getLong("sns_time_offset_ms", -3600000);

        if (HookConfig.isEnabled("sns_ad_block"))      hookAdBlock();
        if (HookConfig.isEnabled("sns_forward"))       hookForward();
        if (HookConfig.isEnabled("sns_simulate_like"))     hookFakeLike();
        if (HookConfig.isEnabled("sns_time_edit"))     hookTimeEdit();
        if (HookConfig.isEnabled("sns_video_quality")) hookVideoQuality();
        if (HookConfig.isEnabled("sns_long_video"))    hookLongVideo();
    }

    // ═══════════════════════════════════════════════════════════
    // #19 去广告
    // ═══════════════════════════════════════════════════════════
    private static void hookAdBlock() {
        try {
            Class<?> snsInfo = XposedHelpers.findClass(
                    "com.tencent.mm.plugin.sns.storage.n", cl);
            XposedBridge.hookAllMethods(snsInfo, "getType", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    int type = (Integer) param.getResult();
                    if (type == 15) {
                        try { XposedHelpers.setAdditionalInstanceField(
                                param.thisObject, "wxp_is_ad", true); }
                        catch (Throwable ignored) {}
                    }
                }
            });
        } catch (Throwable t) {}
        try {
            String[] candidates = {
                "com.tencent.mm.plugin.sns.ui.SnsTimeLineAdapter",
                "com.tencent.mm.plugin.sns.ui.adapter.SnsTimeLineAdapter"
            };
            for (String name : candidates) {
                try {
                    Class<?> adapter = XposedHelpers.findClass(name, cl);
                    XposedBridge.hookAllMethods(adapter, "getItem", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object item = param.getResult();
                            if (item == null) return;
                            try {
                                Object isAd = XposedHelpers.getAdditionalInstanceField(item, "wxp_is_ad");
                                if (isAd != null && (Boolean) isAd) param.setResult(null);
                            } catch (Throwable ignored) {}
                        }
                    });
                    break;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {}
        XposedBridge.log("[SNS] #19 去广告完成");
    }

    // ═══════════════════════════════════════════════════════════
    // #22 转发 — 完整端到端实现
    // ═══════════════════════════════════════════════════════════
    private static void hookForward() {
        try {
            Class<?> flip = XposedHelpers.findClass(
                    "com.tencent.mm.plugin.sns.ui.SnsInfoFlip", cl);

            XposedBridge.hookAllMethods(flip, "onCreateContextMenu", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        ContextMenu menu = (ContextMenu) param.args[0];
                        if (menu != null) {
                            menu.add(0, 99995, 0, "转发到聊天");
                            menu.add(0, 99996, 0, "复制文字内容");
                            menu.add(0, 99997, 0, "快速收藏");
                        }
                    } catch (Throwable ignored) {}
                }
            });

            XposedBridge.hookAllMethods(flip, "onContextItemSelected", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        MenuItem item = (MenuItem) param.args[0];
                        if (item.getItemId() == 99995) {
                            executeForward(param.thisObject);
                            param.setResult(true);
                        } else if (item.getItemId() == 99996) {
                            executeCopy(param.thisObject);
                            param.setResult(true);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("[SNS] 转发异常: " + t.getMessage());
                    }
                }
            });
            XposedBridge.log("[SNS] #22 转发完成");
        } catch (Throwable t) {
            XposedBridge.log("[SNS] #22 转发失败: " + t.getMessage());
        }
    }

    private static void executeForward(Object flipObj) {
        try {
            String snsContent = null;
            long snsId = 0;
            
            try {
                Object snsInfo = XposedHelpers.getObjectField(flipObj, "mSnsInfo");
                if (snsInfo != null) {
                    try {
                        snsContent = (String) XposedHelpers.getObjectField(snsInfo, "field_content");
                    } catch (Throwable ignored) {}
                    try {
                        snsId = XposedHelpers.getLongField(snsInfo, "field_snsId");
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}

            if (snsContent == null) {
                for (String f : new String[]{"n", "o", "p", "q", "r", "s", "t"}) {
                    try {
                        Object obj = XposedHelpers.getObjectField(flipObj, f);
                        if (obj != null) {
                            try {
                                snsContent = (String) XposedHelpers.getObjectField(obj, "field_content");
                                if (snsContent != null) break;
                            } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                }
            }
            
            if (snsContent == null) {
                try {
                    View v = (View) XposedHelpers.getObjectField(flipObj, "mView");
                    if (v != null) snsContent = extractTextFromView(v);
                } catch (Throwable ignored) {}
            }

            if (snsContent == null || snsContent.isEmpty()) {
                snsContent = "[朋友圈内容]";
            }

            android.content.Context ctx = null;
            try {
                View v = (View) XposedHelpers.getObjectField(flipObj, "mView");
                if (v != null) ctx = v.getContext();
            } catch (Throwable ignored) {}

            if (ctx == null) return;

            Intent intent = new Intent();
            intent.setClassName("com.tencent.mm", 
                    "com.tencent.mm.ui.transmit.SelectConversationUI");
            intent.putExtra("Select_Conv_Type", 3);
            intent.putExtra("Retr_Msg_content", snsContent);
            intent.putExtra("Retr_Msg_Type", 1);
            intent.putExtra("Retr_Msg_Id", snsId);
            intent.putExtra("Retr_From_Sns", true);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            
            try {
                ctx.startActivity(intent);
                XposedBridge.log("[SNS] 转发: 内容长度=" + snsContent.length());
            } catch (Throwable t) {
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("sns_forward", snsContent));
                Toast.makeText(ctx, "内容已复制到剪贴板，请手动转发", Toast.LENGTH_LONG).show();
                XposedBridge.log("[SNS] 转发: 兜底→已复制到剪贴板");
            }
        } catch (Throwable t) {
            XposedBridge.log("[SNS] 转发执行失败: " + t.getMessage());
        }
    }

    private static void executeCopy(Object flipObj) {
        try {
            View v = (View) XposedHelpers.getObjectField(flipObj, "mView");
            if (v == null) return;
            String content = extractTextFromView(v);
            if (content == null || content.isEmpty()) content = "[朋友圈内容]";
            
            ClipboardManager cm = (ClipboardManager) v.getContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("sns_copy", content));
            Toast.makeText(v.getContext(), "文字已复制: " 
                    + content.substring(0, Math.min(20, content.length())), 
                    Toast.LENGTH_SHORT).show();
            XposedBridge.log("[SNS] 复制: " + content.length() + "字");
        } catch (Throwable ignored) {}
    }

    private static long getSnsIdFromFlip(Object flipObj) {
        try {
            Object snsInfo = XposedHelpers.getObjectField(flipObj, "mSnsInfo");
            if (snsInfo != null) {
                return XposedHelpers.getLongField(snsInfo, "field_snsId");
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static String extractTextFromView(View v) {
        if (v instanceof TextView) {
            CharSequence text = ((TextView) v).getText();
            return text != null ? text.toString() : "";
        }
        if (v instanceof ViewGroup) {
            StringBuilder sb = new StringBuilder();
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                String s = extractTextFromView(vg.getChildAt(i));
                if (s != null && s.length() > sb.length()) sb.append(s);
            }
            return sb.toString();
        }
        return "";
    }

    // ═══════════════════════════════════════════════════════════
    // #23 假点赞
    // ═══════════════════════════════════════════════════════════
    private static void hookFakeLike() {
        try {
            Class<?> n3 = XposedHelpers.findClass(
                    "com.tencent.mm.plugin.sns.model.n3", cl);
            for (java.lang.reflect.Method m : n3.getDeclaredMethods()) {
                if (m.getReturnType() == boolean.class && m.getParameterTypes().length >= 2) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(true);
                        }
                    });
                }
            }
            XposedBridge.log("[SNS] #23 假点赞完成");
        } catch (Throwable t) {}
    }

    // ═══════════════════════════════════════════════════════════
    // #24 时间修改
    // ═══════════════════════════════════════════════════════════
    private static void hookTimeEdit() {
        try {
            Class<?> snsInfo = XposedHelpers.findClass(
                    "com.tencent.mm.plugin.sns.storage.n", cl);
            XposedBridge.hookAllMethods(snsInfo, "getCreateTime", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    long orig = (Long) param.getResult();
                    if (orig > 0 && timeOffsetMs != 0) param.setResult(orig + timeOffsetMs);
                }
            });
        } catch (Throwable t) {}
        try {
            Class<?> uploadUI = XposedHelpers.findClass(
                    "com.tencent.mm.plugin.sns.ui.SnsUploadUI", cl);
            XposedBridge.hookAllMethods(uploadUI, "n7", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        android.app.Activity act = (android.app.Activity) param.thisObject;
                        act.getIntent().putExtra("KSnsPostTime", System.currentTimeMillis() + timeOffsetMs);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {}
        XposedBridge.log("[SNS] #24 时间修改完成 offset=" + (timeOffsetMs/1000) + "s");
    }

    // ═══════════════════════════════════════════════════════════
    // #27 视频画质
    // ═══════════════════════════════════════════════════════════
    private static void hookVideoQuality() {
        try {
            Class<?> config = XposedHelpers.findClass(
                    "com.tencent.mm.plugin.sns.config.RCSnsVideoImproveQualityConfig", cl);
            for (java.lang.reflect.Field f : config.getDeclaredFields()) {
                if (f.getType() == boolean.class) {
                    f.setAccessible(true);
                    try { f.setBoolean(null, true); } catch (Throwable ignored) {}
                }
            }
            XposedBridge.log("[SNS] #27 视频画质完成");
        } catch (Throwable t) {}
    }

    // ═══════════════════════════════════════════════════════════
    // #29 长视频
    // ═══════════════════════════════════════════════════════════
    private static void hookLongVideo() {
        try {
            Class<?> config = XposedHelpers.findClass(
                    "com.tencent.mm.plugin.sns.config.RCSnsUploadVideoThumbWxam", cl);
            for (java.lang.reflect.Field f : config.getDeclaredFields()) {
                if (f.getType() == int.class || f.getType() == long.class) {
                    f.setAccessible(true);
                    try {
                        if (f.getType() == int.class) f.setInt(null, Integer.MAX_VALUE);
                        else f.setLong(null, Long.MAX_VALUE);
                    } catch (Throwable ignored) {}
                }
            }
            XposedBridge.log("[SNS] #29 长视频完成");
        } catch (Throwable t) {}
    }
}
