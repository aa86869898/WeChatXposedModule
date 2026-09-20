package com.leshao.v3.hook;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 微信消息自动转发（自动群发）
 *
 * 链路（见 自动转发.md）：
 *   消息入库 hook: f9.Bb/Db/Hb(e9, ...) after 阶段取 e9（消息实体）
 *   → 过滤: e9.N0()∈指定来源 && type∈{文本族/图片/AppMsg} && !isSend(e9.z0()!=1)
 *   → 分派: 文本→WmReflect.sendTextMsg；图片/AppMsg→AppMsg 发送辅助
 *   → 目标列表循环 = 自动群发
 *
 * 配置存储于 prefs:
 *   ls_autofw_enabled   Boolean
 *   ls_autofw_sources   String (逗号分隔, 留空=所有来源)
 *   ls_autofw_targets   String (逗号分隔)
 *   ls_autofw_types     String (逗号分隔, 留空=文本+AppMsg)
 */
public class AutoForwardHook {

    private static final String TAG = "AutoForward";
    private static final String PKG_WECHAT = "com.tencent.mm";
    private static final String PREF_ENABLED = "ls_autofw_enabled";
    private static final String PREF_SOURCES = "ls_autofw_sources";
    private static final String PREF_TARGETS = "ls_autofw_targets";
    private static final String PREF_TYPES = "ls_autofw_types";

    private static volatile boolean sEnabled = false;
    private static volatile Set<String> sSources = ConcurrentHashMap.newKeySet();
    private static volatile List<String> sTargets = new ArrayList<>();
    private static volatile Set<String> sTypes = ConcurrentHashMap.newKeySet(); // "1","3","49"...
    private static final Set<Long> sForwarded = ConcurrentHashMap.newKeySet();
    private static volatile boolean sHooked = false;

    private AutoForwardHook() {}

    public static boolean isEnabled() { return sEnabled; }
    public static void setEnabled(boolean v) { sEnabled = v; }

    public static void updateConfig(SharedPreferences prefs) {
        try {
            if (prefs == null) return;
            sEnabled = prefs.getBoolean(PREF_ENABLED, false);
            sSources.clear();
            sSources.addAll(split(prefs.getString(PREF_SOURCES, "")));
            sTargets = split(prefs.getString(PREF_TARGETS, ""));
            sTypes.clear();
            sTypes.addAll(split(prefs.getString(PREF_TYPES, "")));
            LogWriter.log(TAG, "config: enabled=" + sEnabled + " sources=" + sSources
                + " targets=" + sTargets + " types=" + sTypes);
        } catch (Throwable t) {
            LogWriter.log(TAG, "updateConfig err: " + t.getMessage());
        }
    }

    public static void setSources(Set<String> src) { sSources = src != null ? src : new HashSet<>(); }
    public static void setTargets(List<String> t) { sTargets = t != null ? t : new ArrayList<>(); }
    public static void setTypes(Set<String> t) { sTypes = t != null ? t : new HashSet<>(); }

    private static List<String> split(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null) return list;
        for (String part : raw.split("[,，;；\n]")) {
            String s = part.trim();
            if (!s.isEmpty()) list.add(s);
        }
        return list;
    }

    /** 消息类型过滤: 匹配指定类型集合或默认识别文本族/图片/AppMsg */
    static boolean typeAllowed(int type) {
        if (!sTypes.isEmpty()) {
            return sTypes.contains(String.valueOf(type));
        }
        // 默认: 文本族 {1,11,21,31,36,0x42000031} | 图片 3 | AppMsg 卡片族 49/0x42000031/0x11000031
        if (type == 1 || type == 11 || type == 21 || type == 31 || type == 36
            || type == 0x42000031) return true;
        if (type == 3) return true;
        if ((type & 0xffff) == 49 || type == 0x42000031 || type == 0x11000031) return true;
        return false;
    }

    public static void hook(ClassLoader cl) {
        try {
            SharedPreferences prefs = ContextManager.getPrefs();
            updateConfig(prefs);
            if (!sEnabled || sTargets.isEmpty()) {
                LogWriter.log(TAG, "hook: disabled or no targets");
                return;
            }
            if (sHooked) return;
            sHooked = true;

            Class<?> f9 = VersionCompat.findMsgStorageClass(cl);
            if (f9 == null) {
                LogWriter.log(TAG, "hook: f9 null");
                sHooked = false;
                return;
            }
            int hooked = 0;
            for (java.lang.reflect.Method m : f9.getDeclaredMethods()) {
                if (!m.getName().equals("Bb") && !m.getName().equals("Db")
                    && !m.getName().equals("Hb") && !m.getName().equals("yb")) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length < 1 || pts[0] == null) continue;
                String p0 = pts[0].getName();
                if (!p0.endsWith(".e9") && !p0.contains("MsgInfo")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args.length < 1 || param.args[0] == null) return;
                            onMsgInsert(param.args[0]);
                        } catch (Throwable e) {
                            LogWriter.log("AutoForward", "cb err: " + e);
                        }
                    }
                });
                LogWriter.log(TAG, "hooked f9." + m.getName() + "(" + pts.length + ") p0=" + p0);
                hooked++;
            }
            LogWriter.log(TAG, "fw hooks installed: " + hooked);
            if (hooked == 0) sHooked = false;
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook FAIL: " + t.getMessage());
        }
    }

    static void onMsgInsert(Object e9) {
        try {
            if (e9 == null) return;
            if (!sEnabled) return;

            String talker = callStr(e9, "N0");
            int type = 0;
            try { type = (Integer) XposedHelpers.callMethod(e9, "getType"); } catch (Throwable ignored) {}
            int isSend = -1;
            try { isSend = (Integer) XposedHelpers.callMethod(e9, "z0"); } catch (Throwable ignored) {}
            if (isSend == 1) return; // 自己发送的不转发

            if (talker == null || talker.isEmpty()) return;
            // 来源过滤: sSources 非空时 talker 必须在列表内
            if (!sSources.isEmpty() && !sSources.contains(talker)) return;

            if (!typeAllowed(type)) return;

            // 去重: msgSvrId
            long svrId = 0;
            try { svrId = (Long) XposedHelpers.callMethod(e9, "F0"); } catch (Throwable ignored) {}
            if (svrId != 0 && !sForwarded.add(svrId)) return;
            if (sForwarded.size() > 500) sForwarded.clear();

            String content = readContent(e9);

            LogWriter.log(TAG, "fwd trigger: type=" + type + " talker=" + talker
                + " content=" + trunc(content, 30));
            forwardAll(type, talker, content, e9);
        } catch (Throwable t) {
            LogWriter.log(TAG, "onMsgInsert err: " + t.getMessage());
        }
    }

    private static String readContent(Object e9) {
        for (String mn : new String[]{"I0", "j", "N1"}) {
            try {
                Object v = XposedHelpers.callMethod(e9, mn);
                if (v != null) return v.toString();
            } catch (Throwable ignored) {}
        }
        try {
            java.lang.reflect.Field f = e9.getClass().getDeclaredField("field_content");
            f.setAccessible(true);
            Object v = f.get(e9);
            if (v != null) return v.toString();
        } catch (Throwable ignored) {}
        return null;
    }

    private static String callStr(Object obj, String name) {
        try {
            Object v = XposedHelpers.callMethod(obj, name);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void forwardAll(int type, String fromTalker, String content, Object originalE9) {
        ClassLoader cl = ContextManager.getClassLoader();
        if (cl == null) return;
        for (String target : sTargets) {
            try {
                forwardOne(cl, type, content, target, originalE9);
                LogWriter.log(TAG, "fwd -> " + target + " ok");
            } catch (Throwable t) {
                LogWriter.log(TAG, "fwd -> " + target + " err: " + t.getMessage());
            }
        }
    }

    private static void forwardOne(ClassLoader cl, int type, String content, String target, Object originalE9) {
        boolean isTextFamily = (type == 1 || type == 11 || type == 21 || type == 31 || type == 36
            || type == 0x42000031) && type != 0x11000031;
        boolean isImage = type == 3;
        boolean isAppMsg = (type & 0xffff) == 49 || type == 0x42000031 || type == 0x11000031;

        if (isTextFamily && content != null && !content.isEmpty()) {
            // 纯文本直发
            com.leshao.v3.wm.utils.WmReflect.sendTextMsg(cl, content, target);
            return;
        }
        if (isImage) {
            // 图片: 取 imgPath 发送原图
            String path = callStr(originalE9, "x0");
            if (path == null || path.isEmpty() || !new java.io.File(path).exists()) {
                LogWriter.log(TAG, "fwd image: no local path, fallback xml send");
                sendXmlAsAppMsg(cl, content, target, 3);
                return;
            }
            sendImage(cl, target, path);
            return;
        }
        if (isAppMsg) {
            sendXmlAsAppMsg(cl, content, target, 49);
            return;
        }
        LogWriter.log(TAG, "fwd: unsupported type " + type + " (skip)");
    }

    private static void sendImage(ClassLoader cl, String target, String path) {
        // 复用 WmReflect SendMsgMgr 图片发送（qs5.v5.b CDN 图片）
        try {
            Object sendMgr = com.leshao.v3.wm.utils.WmReflect.getSendMsgMgr(cl);
            if (sendMgr == null) { LogWriter.log(TAG, "sendImage: mgr null"); return; }
            Context ctx = ContextManager.getAppContext();
            for (java.lang.reflect.Method m : sendMgr.getClass().getDeclaredMethods()) {
                if (!m.getName().equals("b")) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length < 4 || pts.length > 18) continue;
                Object[] args = new Object[pts.length];
                for (int i = 0; i < pts.length; i++) {
                    Class<?> p = pts[i];
                    if (i == 0 && (p == Context.class || p.getName().endsWith("Context"))) {
                        args[i] = ctx;
                    } else if (i == 1 && p == String.class) {
                        args[i] = target;
                    } else if (i == 2 && p == String.class) {
                        args[i] = path;
                    } else if (i == 3 && p == int.class) {
                        args[i] = 4; // 原图
                    } else if (p == int.class) {
                        args[i] = 0;
                    } else if (p == long.class) {
                        args[i] = 0L;
                    } else if (p == boolean.class) {
                        args[i] = false;
                    } else if (p == String.class) {
                        args[i] = "";
                    } else {
                        args[i] = null;
                    }
                }
                m.setAccessible(true);
                m.invoke(sendMgr, args);
                return;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "sendImage err: " + t.getMessage());
        }
    }

    /** AppMsg/图片 xml 直发: q3.G(Context, talker, appmsgXml, type, flag) */
    private static void sendXmlAsAppMsg(ClassLoader cl, String xml, String target, int type) {
        Context ctx = ContextManager.getAppContext();
        if (ctx == null) return;
        String[] q3Cands = {"q3", "q2", "q4"};
        for (String qn : q3Cands) {
            try {
                Class<?> c = cl.loadClass(qn);
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals("G")) continue;
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length < 4) continue;
                    Class<?> p0 = pts[0];
                    if (p0 != Context.class && !p0.getName().endsWith("Context")) continue;
                    if (pts[1] != String.class || pts[2] != String.class) continue;
                    m.setAccessible(true);
                    Object[] args = new Object[pts.length];
                    args[0] = ctx;
                    args[1] = target;
                    args[2] = xml;
                    for (int i = 3; i < pts.length; i++) {
                        Class<?> p = pts[i];
                        if (p == int.class) args[i] = type;
                        else if (p == boolean.class) args[i] = i == 3 ? false : true;
                        else if (p == String.class) args[i] = "";
                        else args[i] = null;
                    }
                    m.invoke(null, args);
                    LogWriter.log(TAG, "q3.G invoked");
                    return;
                }
            } catch (Throwable ignored) {}
        }
        LogWriter.log(TAG, "sendXmlAsAppMsg: q3.G not found");
    }

    // ==================== 配置 UI 弹窗 ====================

    public static void showConfigDialog(Context ctx) {
        SharedPreferences prefs = ContextManager.getPrefs();
        if (prefs == null) { Toast.makeText(ctx, "prefs 不可用", Toast.LENGTH_SHORT).show(); return; }

        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (14 * d);
        root.setPadding(pad, pad, pad, pad);

        TextViewTitle lblEnabled = new TextViewTitle(ctx, "自动转发");
        root.addView(lblEnabled);
        final android.widget.Switch swEnabled = new android.widget.Switch(ctx);
        swEnabled.setChecked(prefs.getBoolean(PREF_ENABLED, false));
        root.addView(swEnabled);

        root.addView(new TextViewTitle(ctx, "来源 (逗号分隔, 留空=所有)"));
        final EditText etSrc = new EditText(ctx);
        etSrc.setText(prefs.getString(PREF_SOURCES, ""));
        etSrc.setSingleLine(false);
        root.addView(etSrc);

        root.addView(new TextViewTitle(ctx, "目标 (逗号分隔, 必填)"));
        final EditText etTgt = new EditText(ctx);
        etTgt.setText(prefs.getString(PREF_TARGETS, ""));
        etTgt.setSingleLine(false);
        root.addView(etTgt);

        root.addView(new TextViewTitle(ctx, "类型 (留空=文本+图片+AppMsg; 如 1,3,49)"));
        final EditText etType = new EditText(ctx);
        etType.setText(prefs.getString(PREF_TYPES, ""));
        root.addView(etType);

        int theme = com.leshao.v3.ui.AppColors.isDarkMode()
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(ctx, theme)
                .setTitle("自动转发设置")
                .setView(root)
                .setPositiveButton("保存", (dlgB, w) -> {
                    prefs.edit()
                        .putBoolean(PREF_ENABLED, swEnabled.isChecked())
                        .putString(PREF_SOURCES, etSrc.getText().toString().trim())
                        .putString(PREF_TARGETS, etTgt.getText().toString().trim())
                        .putString(PREF_TYPES, etType.getText().toString().trim())
                        .apply();
                    updateConfig(prefs);
                    Toast.makeText(ctx, "已保存" + (sEnabled ? "，自动转发已开启" : ""),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
    }

    private static class TextViewTitle extends android.widget.TextView {
        TextViewTitle(Context ctx, String text) {
            super(ctx);
            setText(text);
            setTextSize(14);
            setTextColor(com.leshao.v3.ui.AppColors.text1());
            setPadding(0, (int)(8 * ctx.getResources().getDisplayMetrics().density), 0, 4);
        }
    }

    static String trunc(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}