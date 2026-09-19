package com.leshao.v3.hook;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.leshao.v3.LogWriter;
import com.leshao.v3.wm.utils.WmPrefs;
import com.leshao.v3.wm.utils.WmReflect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 乐少·万群管理 —— 已集成版。
 * 消息入口复用 8.0.78(3180) 已验证的 f9.Bb(MsgInfoStorage.insertMsgInfo) after 阶段；
 * 文本发送复用 WmReflect.sendTextMsg；踢人(oplog 0xDD)、进群时间(z2.q0) 为反射链路（含 8.0.7x 取证类名兜底）。
 * 配置存 SharedPreferences(wm_prefs, wq_*)，入口由 MsgExport 三点菜单 p2w 提供并打开配置面板。
 */
public class WanQunGroupHook {

    private static final String TAG = "WanQunGroup";

    private static ClassLoader sCL;
    private static Handler sHandler;
    private static final Set<Long> sSeen = ConcurrentHashMap.newKeySet();

    private static volatile boolean sEnabled = true;

    public static void setEnabled(boolean en) { sEnabled = en; }
    public static boolean isEnabled() { return sEnabled; }

    /* ================= 反射类名（8.0.78 候选 + 8.0.7x 取证名兜底） ================= */
    private static String[] CT_ROOM_FACTORY = { "com.tencent.mm.roomsdk.model.factory.h",
                                                "com.tencent.mm.roomsdk.model.factory.g" };
    private static String[] CT_OPLOG        = { "vn3.p0", "vn3.o0" };
    private static String[] CT_OPPROTO      = { "pc5.c24", "pc5.c23" };
    private static String[] CT_OPLIST       = { "d61.k", "d61.j" };
    private static String[] CT_STORAGE_Z2   = { "com.tencent.mm.storage.z2", "com.tencent.mm.storage.z3",
                                                "com.tencent.mm.storage.a3" };
    private static String[] CT_SVC_N0       = { "ph5.n0", "pa5.n0" };
    private static String[] CT_MSG_SVC      = { "com.tencent.mm.plugin.messenger.foundation.h2",
                                                "com.tencent.mm.plugin.messenger.foundation.h3" };

    public static void init(ClassLoader cl) {
        sCL = cl;
        sHandler = new Handler(Looper.getMainLooper());
        LogWriter.log(TAG, "万群管理初始化");
    }

    /* ================= 消息 Hook（f9.Bb after） ================= */
    public static void hookReceive(ClassLoader cl) {
        try {
            Class<?> f9 = VersionCompat.findMsgStorageClass(cl);
            if (f9 == null) { LogWriter.log(TAG, "f9 未找到，无法监听群消息"); return; }
            int hooked = 0;
            for (Method m : f9.getDeclaredMethods()) {
                if (!m.getName().equals("Bb")) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length < 1 || pts[0] == null) continue;
                String p0 = pts[0].getName();
                if (p0.endsWith(".e9")) { } else if (p0.contains("MsgInfo")) { } else continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (p.args.length < 1 || p.args[0] == null) return;
                            onNewMsg(p.args[0]);
                        } catch (Throwable t) { LogWriter.log(TAG, "onNewMsg cb err: " + t); }
                    }
                });
                hooked++;
            }
            LogWriter.log(TAG, "万群管理消息 hook 安装: " + hooked + " 个 f9.Bb");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookReceive FAIL: " + t.getMessage());
        }
    }

    private static long idOf(Object msg) {
        long msgId = 0, svrId = 0;
        try { msgId = (Long) XposedHelpers.callMethod(msg, "getMsgId"); } catch (Throwable ignored) {}
        if (msgId == 0) try { msgId = (Long) XposedHelpers.callMethod(msg, "H0"); } catch (Throwable ignored) {}
        try { svrId = (Long) XposedHelpers.callMethod(msg, "F0"); } catch (Throwable ignored) {}
        if (msgId == 0 && svrId != 0) return -svrId;
        return msgId;
    }

    private static void onNewMsg(Object msg) {
        if (!sEnabled) return;
        long mid = idOf(msg);
        if (mid != 0 && !sSeen.add(mid)) return;
        if (sSeen.size() > 600) { Long oldest = sSeen.iterator().next(); sSeen.remove(oldest); }
        try {
            int type = (Integer) XposedHelpers.callMethod(msg, "getType");
            String talker = (String) XposedHelpers.callMethod(msg, "N0");
            if (talker == null || !isGroupChat(talker)) return;
            int isSend = -1;
            try { isSend = (Integer) XposedHelpers.callMethod(msg, "z0"); } catch (Throwable ignored) {}
            if (isSend == 1) return;
            String content = null;
            try { content = (String) XposedHelpers.callMethod(msg, "j"); } catch (Throwable ignored) {}
            if (content == null) try { content = (String) XposedHelpers.callMethod(msg, "getContent"); } catch (Throwable ignored) {}
            String from = null;
            try { from = (String) XposedHelpers.callMethod(msg, "s0"); } catch (Throwable ignored) {}

            if (type == 10000 || type == 10002) { handleSysMsg(talker, content); return; }
            handleGroupMsg(talker, from, type, content);
        } catch (Throwable t) {
            LogWriter.log(TAG, "onNewMsg ex: " + t.getMessage());
        }
    }

    private static boolean isGroupChat(String t) {
        return t != null && (t.endsWith("@chatroom") || t.endsWith("@im.chatroom"));
    }

    /* ================= 业务 ================= */
    private static void handleSysMsg(String group, String content) {
        int jl = detectJoinLeave(content);
        if (jl == 1) {
            if (Cfg.bool("wq_welcome_enabled", true)) { String t = Cfg.str("wq_welcome_text", "欢迎新成员进群！"); sendText(group, t); }
        } else if (jl == 2) {
            if (Cfg.bool("wq_bye_enabled", true)) { String t = Cfg.str("wq_bye_text", "有人退群了。"); sendText(group, t); }
        }
    }

    private static void handleGroupMsg(String group, String from, int type, String content) {
        if (from == null || group == null) return;
        if (inList("wq_black", group, from)) { kick(group, from); return; }
        if (inList("wq_white", group, from)) return;

        String kt = Cfg.str("wq_kick_msg_types", "");
        if (!kt.isEmpty() && inTypeList(kt, type)) { kick(group, from); return; }

        if (type == 1 && hitForbidden(content)) {
            int cnt = warnInc(group, from);
            postSend(group, "【警告】" + from + " 发言包含违禁词，已记 " + cnt + " 次");
            if (cnt >= Cfg.integer("wq_warn_threshold", 3)) { kick(group, from); warnClear(group, from); }
            return;
        }
        if (type == 1 && content != null && content.startsWith("!群管")) {
            handleCommand(group, from, content);
        }
    }

    private static boolean inTypeList(String cfg, int type) {
        for (String s : cfg.split("[,\u3001;\\s]+")) {
            try { if (Integer.parseInt(s.trim()) == type) return true; } catch (Throwable ignored) {}
        }
        return false;
    }

    private static int detectJoinLeave(String content) {
        if (content == null || content.isEmpty()) return 0;
        if (content.contains("加入了群聊") || content.contains("加入该群聊")
                || content.contains("加入群聊") || (content.contains("邀请") && content.contains("进群"))
                || content.contains("邀请你加入")) return 1;
        if (content.contains("退出了群聊") || content.contains("退出群聊") || content.contains("移出群聊")) return 2;
        return 0;
    }

    /* ================= 黑/白名单 ================= */
    private static boolean inList(String kind, String group, String member) {
        try {
            JSONObject root = new JSONObject(Cfg.str(kind, "{}"));
            JSONArray arr = root.optJSONArray(group);
            if (arr == null) return false;
            for (int i = 0; i < arr.length(); i++) if (member.equals(arr.optString(i))) return true;
        } catch (Throwable ignored) {}
        return false;
    }
    private static void addList(String kind, String group, String member) {
        try {
            JSONObject root = new JSONObject(Cfg.str(kind, "{}"));
            JSONArray arr = root.optJSONArray(group);
            if (arr == null) arr = new JSONArray();
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) if (!member.equals(arr.optString(i))) out.put(arr.optString(i));
            out.put(member);
            root.put(group, out);
            Cfg.setStr(kind, root.toString());
        } catch (Throwable ignored) {}
    }
    private static void removeList(String kind, String group, String member) {
        try {
            JSONObject root = new JSONObject(Cfg.str(kind, "{}"));
            JSONArray arr = root.optJSONArray(group);
            if (arr == null) return;
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) if (!member.equals(arr.optString(i))) out.put(arr.optString(i));
            root.put(group, out);
            Cfg.setStr(kind, root.toString());
        } catch (Throwable ignored) {}
    }
    private static String listStr(String kind, String group) {
        try {
            JSONObject root = new JSONObject(Cfg.str(kind, "{}"));
            JSONArray arr = root.optJSONArray(group);
            return arr == null || arr.length() == 0 ? "无" : arr.toString();
        } catch (Throwable t) { return "无"; }
    }

    /* ================= 警告 ================= */
    private static int warnGet(String group, String member) {
        try {
            JSONObject root = new JSONObject(Cfg.str("wq_warn_count", "{}"));
            JSONObject gg = root.optJSONObject(group);
            return gg == null ? 0 : gg.optInt(member, 0);
        } catch (Throwable t) { return 0; }
    }
    private static int warnInc(String group, String member) {
        int n = warnGet(group, member) + 1;
        try {
            JSONObject root = new JSONObject(Cfg.str("wq_warn_count", "{}"));
            JSONObject gg = root.optJSONObject(group);
            if (gg == null) gg = new JSONObject();
            gg.put(member, n);
            root.put(group, gg);
            Cfg.setStr("wq_warn_count", root.toString());
        } catch (Throwable ignored) {}
        return n;
    }
    private static void warnClear(String group, String member) {
        try {
            JSONObject root = new JSONObject(Cfg.str("wq_warn_count", "{}"));
            JSONObject gg = root.optJSONObject(group);
            if (gg == null) return;
            gg.remove(member);
            root.put(group, gg);
            Cfg.setStr("wq_warn_count", root.toString());
        } catch (Throwable ignored) {}
    }

    /* ================= 违禁词 ================= */
    private static boolean hitForbidden(String content) {
        if (content == null || content.isEmpty()) return false;
        List<String> words = Cfg.strList("wq_forbidden_words", new String[]{"广告", "加微信", "https?://"});
        for (String w : words) {
            if (w.isEmpty()) continue;
            try { if (content.matches(".*" + w + ".*")) return true; }
            catch (Throwable t) { if (content.contains(w)) return true; }
        }
        return false;
    }

    /* ================= 群管指令 ================= */
    private static boolean isAdmin(String w) {
        List<String> admins = Cfg.strList("wq_admin_users", new String[]{});
        return admins == null || admins.isEmpty() || admins.contains(w);
    }
    private static void handleCommand(String group, String from, String content) {
        if (!Cfg.bool("wq_command_enabled", true)) return;
        if (!isAdmin(from)) { postSend(group, "无权限执行群管指令"); return; }
        String cmd = content.substring(3).trim();
        try {
            // 注意顺序：长命令需优先于其前缀短命令（如"白名单列表"先于"白名单"）
            if (cmd.startsWith("进群时间")) {
                String m = cmd.replace("进群时间", "").trim();
                if (m.startsWith("@")) m = m.substring(1);
                String t = queryJoinTime(group, m);
                postSend(group, "成员 " + m + " 进群时间: " + (t == null ? "未知" : t));
            } else if (cmd.startsWith("取消拉黑")) {
                String m = cmd.replace("取消拉黑", "").trim(); if (m.startsWith("@")) m = m.substring(1);
                removeList("wq_black", group, m); postSend(group, "已取消拉黑 " + m);
            } else if (cmd.startsWith("拉黑")) {
                String m = cmd.replace("拉黑", "").trim(); if (m.startsWith("@")) m = m.substring(1);
                addList("wq_black", group, m); postSend(group, "已拉黑 " + m);
            } else if (cmd.startsWith("取消白名单")) {
                String m = cmd.replace("取消白名单", "").trim(); if (m.startsWith("@")) m = m.substring(1);
                removeList("wq_white", group, m); postSend(group, "已移出白名单 " + m);
            } else if (cmd.startsWith("白名单列表")) {
                postSend(group, "本群白名单: " + listStr("wq_white", group));
            } else if (cmd.startsWith("白名单")) {
                String m = cmd.replace("白名单", "").trim(); if (m.startsWith("@")) m = m.substring(1);
                addList("wq_white", group, m); postSend(group, "已加入白名单 " + m);
            } else if (cmd.startsWith("取消警告")) {
                String m = cmd.replace("取消警告", "").trim(); if (m.startsWith("@")) m = m.substring(1);
                warnClear(group, m); postSend(group, "已取消 " + m + " 的警告");
            } else if (cmd.startsWith("黑名单")) {
                postSend(group, "本群黑名单: " + listStr("wq_black", group));
            }
        } catch (Throwable t) { LogWriter.log(TAG, "cmd err: " + t); }
    }

    /* ================= 发送（线程安全，主线程投递） ================= */
    private static void sendText(String group, String text) {
        postSend(group, text);
    }
    private static void postSend(final String group, final String text) {
        if (group == null || text == null || sCL == null) return;
        final ClassLoader cl = sCL;
        sHandler.post(() -> {
            try {
                WmReflect.sendTextMsg(cl, text, group);
                LogWriter.log(TAG, "发送到 " + group + ": " + text);
            } catch (Throwable t) { LogWriter.log(TAG, "postSend err: " + t); }
        });
    }

    /* ================= 踢人（oplog 0xDD 同链路，反射） ================= */
    public static boolean kick(String group, String member) {
        try {
            if (group == null || member == null) return false;
            Object c24 = newInstance(CT_OPPROTO);
            if (c24 == null) { LogWriter.log(TAG, "踢人: 群操作 proto 类未找到"); return false; }
            XposedHelpers.setObjectField(c24, "d", 0L);
            XposedHelpers.setObjectField(c24, "e", 0L);
            XposedHelpers.setObjectField(c24, "f", group);
            XposedHelpers.setObjectField(c24, "g", 2);            // opType=2 移出
            XposedHelpers.setObjectField(c24, "h", member);
            XposedHelpers.setObjectField(c24, "i", String.valueOf(System.currentTimeMillis() / 1000));
            Class<?> oplogCls = first(CT_OPLOG);
            if (oplogCls == null) { LogWriter.log(TAG, "踢人: oplog 类未找到"); return false; }
            Object op = XposedHelpers.newInstance(oplogCls, Integer.valueOf(0xDD), c24);
            LinkedList<Object> list = new LinkedList<>();
            list.add(op);
            Class<?> oplistCls = first(CT_OPLIST);
            if (oplistCls == null) { LogWriter.log(TAG, "踢人: op 列表类未找到"); return false; }
            Object k = XposedHelpers.newInstance(oplistCls, list);
            Class<?> factory = first(CT_ROOM_FACTORY);
            if (factory == null) { LogWriter.log(TAG, "踢人: roomsdk factory 未找到"); return false; }
            Object net = XposedHelpers.newInstance(factory, Boolean.TRUE);
            XposedHelpers.setObjectField(net, "f", k);
            XposedHelpers.callMethod(net, "b");
            LogWriter.log(TAG, "kick " + group + " -> " + member);
            return true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "kick FAIL: " + t.getMessage());
            return false;
        }
    }

    /* ================= 进群时间（storage.z2.q0(member).i，Unix 秒） ================= */
    public static String queryJoinTime(String group, String member) {
        try {
            Class<?> z2 = first(CT_STORAGE_Z2);
            if (z2 == null) return null;
            Object svc = null;
            Class<?> svcN0 = first(CT_SVC_N0);
            Class<?> msgSvc = first(CT_MSG_SVC);
            if (svcN0 != null && msgSvc != null) {
                svc = XposedHelpers.callStaticMethod(svcN0, "c", new Object[]{msgSvc});
            }
            if (svc == null) return null;
            Object info = null;
            for (java.lang.reflect.Method mt : svc.getClass().getMethods()) {
                if (mt.getReturnType() == z2 && mt.getParameterTypes().length == 1) {
                    try { info = mt.invoke(svc, new Object[]{group}); break; } catch (Throwable ignored) {}
                }
            }
            if (info == null) return null;
            Object mb = XposedHelpers.callMethod(info, "q0", member);
            if (mb == null) return null;
            long t = ((Number) XposedHelpers.getObjectField(mb, "i")).longValue() & 0xFFFFFFFFL;
            if (t <= 0) return null;
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(t * 1000);
            return String.format("%04d-%02d-%02d %02d:%02d",
                    cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1,
                    cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE));
        } catch (Throwable t) { return null; }
    }

    /* ================= 配置面板（三点菜单入口调用） ================= */
    public static void showConfigDialog(final Context ctx, final String group) {
        if (ctx == null) return;
        final LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(ctx, 16);
        root.setPadding(pad, pad, pad, pad);

        final String room = group == null ? "" : group;

        final EditText welcomeText = cfgField(ctx, "欢迎语（新成员入群自动发送）",
                Cfg.str("wq_welcome_text", "欢迎新成员进群！"));
        final EditText welcomeOn = cfgField(ctx, "欢迎开关 true/false",
                String.valueOf(Cfg.bool("wq_welcome_enabled", true)));
        final EditText byeText = cfgField(ctx, "退群提示语",
                Cfg.str("wq_bye_text", "有人退群了。"));
        final EditText byeOn = cfgField(ctx, "退群提示开关 true/false",
                String.valueOf(Cfg.bool("wq_bye_enabled", true)));
        final EditText forbidden = cfgField(ctx, "违禁词（逗号分隔，支持正则）",
                join(Cfg.strList("wq_forbidden_words", new String[]{"广告", "加微信", "https?://"}), ","));
        final EditText warnThreshold = cfgField(ctx, "警告触发踢人阈值（数字）",
                String.valueOf(Cfg.integer("wq_warn_threshold", 3)));
        final EditText kickMsgTypes = cfgField(ctx, "自动踢人的消息类型（逗号分隔，空=关）",
                Cfg.str("wq_kick_msg_types", ""));
        final EditText adminUsers = cfgField(ctx, "群管指令管理员（wxid 逗号分隔，空=所有人）",
                join(Cfg.strList("wq_admin_users", new String[]{}), ","));
        final EditText cmdOn = cfgField(ctx, "群管指令开关 true/false",
                String.valueOf(Cfg.bool("wq_command_enabled", true)));
        final EditText blackList = cfgField(ctx, "【本群】黑名单 wxid（逗号分隔）",
                listStr("wq_black", room));
        final EditText whiteList = cfgField(ctx, "【本群】白名单 wxid（逗号分隔）",
                listStr("wq_white", room));

        root.addView(welcomeText);
        root.addView(welcomeOn);
        root.addView(byeText);
        root.addView(byeOn);
        root.addView(forbidden);
        root.addView(warnThreshold);
        root.addView(kickMsgTypes);
        root.addView(adminUsers);
        root.addView(cmdOn);
        root.addView(blackList);
        root.addView(whiteList);

        ScrollView sv = new ScrollView(ctx);
        sv.addView(root);

        new AlertDialog.Builder(ctx)
                .setTitle("乐少·万群管理  (" + (room.isEmpty() ? "全部" : room) + ")")
                .setView(sv)
                .setPositiveButton("保存", (d, w) -> {
                    Cfg.setStr("wq_welcome_text", value(welcomeText));
                    Cfg.setStr("wq_bye_text", value(byeText));
                    Cfg.setStr("wq_forbidden_words", value(forbidden).replace("，", ","));
                    Cfg.setStr("wq_kick_msg_types", value(kickMsgTypes).replace("，", ","));
                    Cfg.setStr("wq_admin_users", value(adminUsers).replace("，", ","));
                    setCfgStrList("wq_black", room, value(blackList));
                    setCfgStrList("wq_white", room, value(whiteList));
                    try { Cfg.setBool("wq_welcome_enabled", Boolean.parseBoolean(value(welcomeOn).trim())); } catch (Throwable ignored) {}
                    try { Cfg.setBool("wq_bye_enabled", Boolean.parseBoolean(value(byeOn).trim())); } catch (Throwable ignored) {}
                    try { Cfg.setBool("wq_command_enabled", Boolean.parseBoolean(value(cmdOn).trim())); } catch (Throwable ignored) {}
                    try { Cfg.setInt("wq_warn_threshold", Integer.parseInt(value(warnThreshold).trim())); } catch (Throwable ignored) {}
                    LogWriter.log(TAG, "万群管理配置已保存");
                    toast(ctx, "万群管理配置已保存");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static EditText cfgField(Context ctx, String label, String text) {
        EditText et = new EditText(ctx);
        et.setHint(label);
        et.setText(text == null ? "" : text);
        et.setSingleLine(false);
        return et;
    }

    private static String value(EditText et) { return et == null ? "" : (et.getText() == null ? "" : et.getText().toString()); }

    private static int dp(Context ctx, int v) {
        try { return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f); } catch (Throwable t) { return v; }
    }

    private static String join(List<String> l, String sep) {
        if (l == null || l.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : l) { if (sb.length() > 0) sb.append(sep); sb.append(s); }
        return sb.toString();
    }

    private static void setCfgStrList(String kind, String group, String csv) {
        JSONObject root = new JSONObject();
        try { root = new JSONObject(Cfg.str(kind, "{}")); } catch (Throwable ignored) {}
        JSONArray arr = new JSONArray();
        if (csv != null) {
            for (String s : csv.split("[,\u3001;]+")) {
                String t = s.trim();
                if (!t.isEmpty()) { boolean dup = false; for (int i = 0; i < arr.length(); i++) if (t.equals(arr.optString(i))) dup = true; if (!dup) arr.put(t); }
            }
        }
        try { root.put(group, arr); Cfg.setStr(kind, root.toString()); } catch (Throwable ignored) {}
    }

    private static void toast(Context ctx, String msg) {
        try { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
    }

    /* ================= 反射工具 ================= */
    private static Class<?> first(String[] names) {
        for (String n : names) {
            try { return sCL.loadClass(n); } catch (Throwable ignored) {}
        }
        return null;
    }
    private static Object newInstance(String[] names) {
        Class<?> c = first(names);
        if (c == null) return null;
        try { return c.newInstance(); } catch (Throwable t) { return null; }
    }

    /* ================= 配置读取（WmPrefs: wq_*） ================= */
    static final class Cfg {
        private Cfg() {}
        static boolean bool(String k, boolean d) { try { return WmPrefs.get(k, d); } catch (Throwable t) { return d; } }
        static void setBool(String k, boolean v) { try { WmPrefs.set(k, v); } catch (Throwable ignored) {} }
        static String str(String k, String d) { try { return WmPrefs.getStr(k, d); } catch (Throwable t) { return d; } }
        static void setStr(String k, String v) { try { WmPrefs.setStr(k, v); } catch (Throwable ignored) {} }
        static int integer(String k, int d) { try { return WmPrefs.getInt(k, d); } catch (Throwable t) { return d; } }
        static void setInt(String k, int v) { try { WmPrefs.setInt(k, v); } catch (Throwable ignored) {} }
        static List<String> strList(String k, String[] def) {
            String s = str(k, "");
            if (s == null || s.isEmpty()) { List<String> l = new ArrayList<>(); if (def != null) for (String x : def) l.add(x); return l; }
            List<String> out = new ArrayList<>();
            for (String x : s.split("[\\n\u3001,;]+")) { String t = x.trim(); if (!t.isEmpty()) out.add(t); }
            return out;
        }
    }
}