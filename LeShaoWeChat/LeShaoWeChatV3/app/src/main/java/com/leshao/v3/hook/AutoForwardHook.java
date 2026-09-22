package com.leshao.v3.hook;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
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

    // ==================== 配置 UI 弹窗（v955 M3 重排 + 联系人选择器 + 类型多选按钮） ====================

    /** 可转发的消息类型（WeChat msgType）: 值 → 显示名 */
    private static final int[] FW_TYPE_CODES = {1, 3, 43, 34, 47, 49, 42, 48, 6};
    private static final String[] FW_TYPE_NAMES = {"文本", "图片", "视频", "语音", "表情", "链接", "名片", "位置", "文件"};

    public static void showConfigDialog(Context ctx) {
        SharedPreferences prefs = ContextManager.getPrefs();
        if (prefs == null) { Toast.makeText(ctx, "prefs 不可用", Toast.LENGTH_SHORT).show(); return; }
        final Activity act = ctx instanceof Activity ? (Activity) ctx : null;

        float d = ctx.getResources().getDisplayMetrics().density;

        // ===== M3 弹窗根 =====
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(com.leshao.v3.ui.CandyUi.dialogBg(ctx));
        root.setPadding((int) (12 * d), (int) (12 * d), (int) (12 * d), (int) (12 * d));

        ScrollView sv = new ScrollView(ctx);
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding((int) (4 * d), 0, (int) (4 * d), 0);

        // ===== 总开关 =====
        content.addView(new com.leshao.v3.ui.widgets.SectionHeader(ctx, "自动转发", "命中来源的消息自动转发到目标"));
        LinearLayout cardMain = com.leshao.v3.ui.widgets.M3Page.card(ctx);
        final android.widget.Switch swEnabled = com.leshao.v3.ui.CandyUi.newSwitch(ctx);
        swEnabled.setChecked(prefs.getBoolean(PREF_ENABLED, false));
        cardMain.addView(com.leshao.v3.ui.widgets.M3Page.tailRow(ctx, "🔁", "启用自动转发",
                "关闭后不转发任何消息", swEnabled));
        content.addView(cardMain);

        // ===== 来源（联系人选择器） =====
        content.addView(new com.leshao.v3.ui.widgets.SectionHeader(ctx, "转发来源", "留空 = 所有会话"));
        LinearLayout cardSrc = com.leshao.v3.ui.widgets.M3Page.card(ctx);
        final String[] srcHolder = {prefs.getString(PREF_SOURCES, "")};
        final TextView srcVal = new TextView(ctx);
        srcVal.setText(srcHolder[0].isEmpty() ? "全部会话" : srcHolder[0]);
        srcVal.setTextSize(13);
        srcVal.setTextColor(com.leshao.v3.ui.AppColors.onSurfaceVariant());
        srcVal.setSingleLine(true);
        srcVal.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cardSrc.addView(com.leshao.v3.ui.widgets.M3Page.tailRow(ctx, "📥", "选择来源会话",
                "仅转发这些会话的消息", srcVal));
        cardSrc.addView(com.leshao.v3.ui.widgets.M3Page.divider(ctx));
        cardSrc.addView(com.leshao.v3.ui.widgets.M3Page.clickRow(ctx, "📥", "从通讯录选择来源",
                "打开联系人选择器多选", () -> {
            if (act == null) { Toast.makeText(ctx, "当前上下文不支持选择器", Toast.LENGTH_SHORT).show(); return; }
            com.leshao.v3.ui.ContactPickerDialog.show(act, srcHolder[0],
                    com.leshao.v3.ui.ContactPickerDialog.MODE_FRIEND,
                    (wxids, display) -> {
                        srcHolder[0] = wxids == null || wxids.isEmpty() ? ""
                                : android.text.TextUtils.join(",", wxids);
                        srcVal.setText(srcHolder[0].isEmpty() ? "全部会话" : display);
                    });
        }));
        cardSrc.addView(com.leshao.v3.ui.widgets.M3Page.divider(ctx));
        cardSrc.addView(com.leshao.v3.ui.widgets.M3Page.clickRow(ctx, "🧹", "清空来源", "恢复为全部会话",
                () -> { srcHolder[0] = ""; srcVal.setText("全部会话"); }));
        content.addView(cardSrc);

        // ===== 目标（联系人选择器, 群聊优先） =====
        content.addView(new com.leshao.v3.ui.widgets.SectionHeader(ctx, "转发目标", "必填, 消息将转发到这些会话"));
        LinearLayout cardTgt = com.leshao.v3.ui.widgets.M3Page.card(ctx);
        final String[] tgtHolder = {prefs.getString(PREF_TARGETS, "")};
        final TextView tgtVal = new TextView(ctx);
        tgtVal.setText(tgtHolder[0].isEmpty() ? "未设置" : tgtHolder[0]);
        tgtVal.setTextSize(13);
        tgtVal.setTextColor(com.leshao.v3.ui.AppColors.onSurfaceVariant());
        tgtVal.setSingleLine(true);
        tgtVal.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cardTgt.addView(com.leshao.v3.ui.widgets.M3Page.tailRow(ctx, "📤", "已选目标",
                "消息转发目的地", tgtVal));
        cardTgt.addView(com.leshao.v3.ui.widgets.M3Page.divider(ctx));
        cardTgt.addView(com.leshao.v3.ui.widgets.M3Page.clickRow(ctx, "📤", "从通讯录选择目标",
                "打开联系人选择器多选(可切好友/群聊)", () -> {
            if (act == null) { Toast.makeText(ctx, "当前上下文不支持选择器", Toast.LENGTH_SHORT).show(); return; }
            com.leshao.v3.ui.ContactPickerDialog.show(act, tgtHolder[0],
                    com.leshao.v3.ui.ContactPickerDialog.MODE_GROUP,
                    (wxids, display) -> {
                        tgtHolder[0] = wxids == null || wxids.isEmpty() ? ""
                                : android.text.TextUtils.join(",", wxids);
                        tgtVal.setText(tgtHolder[0].isEmpty() ? "未设置" : display);
                    });
        }));
        content.addView(cardTgt);

        // ===== 类型（多选按钮组） =====
        content.addView(new com.leshao.v3.ui.widgets.SectionHeader(ctx, "转发类型", "默认 文本+图片+链接; 全不选=默认"));
        LinearLayout cardType = com.leshao.v3.ui.widgets.M3Page.card(ctx);
        final java.util.Set<Integer> typeSel = new java.util.LinkedHashSet<>();
        String savedTypes = prefs.getString(PREF_TYPES, "");
        if (savedTypes != null && !savedTypes.trim().isEmpty()) {
            for (String t : savedTypes.split("[,，]")) {
                try { typeSel.add(Integer.parseInt(t.trim())); } catch (Throwable ignored) {}
            }
        } else {
            typeSel.add(1); typeSel.add(3); typeSel.add(49);
        }
        final TextView typeSummary = new TextView(ctx);
        typeSummary.setTextSize(13);
        typeSummary.setTextColor(com.leshao.v3.ui.AppColors.onSurfaceVariant());
        typeSummary.setPadding((int) (16 * d), (int) (10 * d), (int) (16 * d), (int) (4 * d));
        cardType.addView(typeSummary);

        // 多选按钮流式网格(每行3个)
        LinearLayout typeGrid = new LinearLayout(ctx);
        typeGrid.setOrientation(LinearLayout.VERTICAL);
        typeGrid.setPadding((int) (12 * d), (int) (4 * d), (int) (12 * d), (int) (10 * d));
        final java.util.List<TextView> typeBtns = new java.util.ArrayList<>();
        for (int i = 0; i < FW_TYPE_CODES.length; i++) {
            if (i % 3 == 0) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, (int) (4 * d), 0, 0);
                typeGrid.addView(row);
            }
            final int code = FW_TYPE_CODES[i];
            TextView tb = new TextView(ctx);
            tb.setText(FW_TYPE_NAMES[i]);
            tb.setTextSize(13);
            tb.setGravity(Gravity.CENTER);
            tb.setSingleLine(true);
            tb.setClickable(true);
            tb.setFocusable(true);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, (int) (36 * d), 1f);
            tlp.setMargins((int) (3 * d), 0, (int) (3 * d), 0);
            tb.setLayoutParams(tlp);
            tb.setOnClickListener(v -> {
                if (typeSel.contains(code)) typeSel.remove(code); else typeSel.add(code);
                refreshTypeButtons(typeBtns, FW_TYPE_CODES, typeSel, typeSummary);
            });
            typeBtns.add(tb);
            ((LinearLayout) typeGrid.getChildAt(typeGrid.getChildCount() - 1)).addView(tb);
        }
        refreshTypeButtons(typeBtns, FW_TYPE_CODES, typeSel, typeSummary);
        cardType.addView(typeGrid);
        content.addView(cardType);

        sv.addView(content);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        sv.setLayoutParams(svLp);
        root.addView(sv);

        // ===== 底部按钮 =====
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        btnRow.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (4 * d));
        com.leshao.v3.ui.widgets.ModernButton btnCancel =
                new com.leshao.v3.ui.widgets.ModernButton(ctx, "取消", com.leshao.v3.ui.widgets.ModernButton.STYLE_GHOST);
        com.leshao.v3.ui.widgets.ModernButton btnSave =
                new com.leshao.v3.ui.widgets.ModernButton(ctx, "保存", com.leshao.v3.ui.widgets.ModernButton.STYLE_PRIMARY);
        btnRow.addView(btnCancel);
        View spacer = new View(ctx);
        spacer.setLayoutParams(new LinearLayout.LayoutParams((int) (12 * d), 1));
        btnRow.addView(spacer);
        btnRow.addView(btnSave);
        root.addView(btnRow);

        int theme = com.leshao.v3.ui.AppColors.isDarkMode()
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(ctx, theme)
                .setView(root)
                .setCancelable(true)
                .create();
        btnCancel.onClick(() -> dlg.dismiss());
        btnSave.onClick(() -> {
            StringBuilder types = new StringBuilder();
            for (Integer t : typeSel) {
                if (types.length() > 0) types.append(",");
                types.append(t);
            }
            prefs.edit()
                .putBoolean(PREF_ENABLED, swEnabled.isChecked())
                .putString(PREF_SOURCES, srcHolder[0].trim())
                .putString(PREF_TARGETS, tgtHolder[0].trim())
                .putString(PREF_TYPES, types.toString())
                .apply();
            updateConfig(prefs);
            Toast.makeText(ctx, "已保存" + (sEnabled ? "，自动转发已开启" : ""),
                    Toast.LENGTH_SHORT).show();
            dlg.dismiss();
        });
        dlg.show();
    }

    /** 刷新类型多选按钮态 + 摘要文字 */
    private static void refreshTypeButtons(java.util.List<TextView> btns, int[] codes,
                                           java.util.Set<Integer> sel, TextView summary) {
        for (int i = 0; i < btns.size() && i < codes.length; i++) {
            TextView tb = btns.get(i);
            boolean on = sel.contains(codes[i]);
            tb.setBackground(com.leshao.v3.ui.CandyUi.pillBg(on, tb.getContext()));
            tb.setTextColor(on ? com.leshao.v3.ui.AppColors.textOnPrimary()
                    : com.leshao.v3.ui.AppColors.onSurfaceVariant());
        }
        if (summary != null) {
            if (sel.isEmpty()) {
                summary.setText("当前: 默认(文本+图片+链接)");
            } else {
                StringBuilder sb = new StringBuilder("当前: ");
                for (int i = 0; i < codes.length; i++) {
                    if (sel.contains(codes[i])) {
                        if (sb.length() > 4) sb.append("、");
                        sb.append(FW_TYPE_NAMES[i]);
                    }
                }
                summary.setText(sb.toString());
            }
        }
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