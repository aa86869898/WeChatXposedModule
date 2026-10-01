package com.leshao.v3.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextMenu;
import android.view.View;
import android.widget.ListView;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 收藏语音转发 —— 严格按《WeChat_收藏语音转发_逆向分析与注入方案.md》方案 A 实现。
 *
 * <p>微信在三个点硬编码拦截收藏语音转发：菜单构建（de2.m / gc 中 x3.b 过滤）、预检
 * （FavoriteIndexUI.H7）、多选（mc.g）。本模块不放开微信原生「转发给朋友」，
 * 而是注入自定义 itemId=10086 的「语音转发」菜单项，点击后自行拉起
 * SelectConversationUI，选人后复刻聊天语音转发：{@code v61.d1.h + 复制文件 +
 * v61.o 入队上传}（微信上传完自动建 type=34 消息并发送）。</p>
 */
public final class FavVoiceForwardHook {

    public static final String TAG = "FavVoice";
    public static final String K_ENABLED = "ls_fav_voice_forward";

    // ===== 文档类名（当前版本混淆名，换包后需按文档重新核对）=====
    private static final String C_FAV_UI = "com.tencent.mm.plugin.fav.ui.FavoriteIndexUI";
    private static final String C_BUILDER_NEW = "de2.m";                 // 新链路菜单构建器
    private static final String C_DISPATCH_NEW = "de2.n";                 // 新链路点击分发
    private static final String C_BUILDER_OLD = "com.tencent.mm.plugin.fav.ui.gc"; // 兜底构建器
    private static final String C_DISPATCH_OLD = "com.tencent.mm.plugin.fav.ui.hc";  // 兜底点击回调
    private static final String C_FAV_API = "tc2.s2";                    // FavApiUtil
    private static final String C_VOICE_LOGIC = "v61.d1";                // VoiceLogic
    private static final String C_VOICE_INFO = "v61.c1";                 // VoiceInfo
    private static final String C_SELECT_UI = "com.tencent.mm.ui.transmit.SelectConversationUI";
    private static final String C_UPLOAD_VOICE = "v61.o";               // NetSceneUploadVoice
    private static final String C_CORE_ENTRY = "b41.h9";                // 核心静态入口
    private static final String C_NETSCENE_QUEUE = "com.tencent.mm.modelbase.r1"; // NetSceneQueue
    private static final String C_VOICE_PATH = "ou5.x";                  // voice 路径枚举
    private static final String C_VOICE_SERVICE = "u0";                      // 路径服务

    private static final int TYPE_VOICE = 3;
    private static final int ITEM_VOICE_FWD = 10086;   // 自定义 itemId，微信原生集合 {0..9} 不冲突
    private static final int REQ_VOICE_FWD = 0x5210;     // startActivityForResult 请求码

    private static volatile boolean sEnabled = false;
    private static volatile boolean sHooked = false;
    private static volatile ClassLoader sCl;

    // 待转发状态：点击「语音转发」时保存，选人结果返回后消费
    private static volatile long sPendingLocalId;
    private static volatile Object sPendingFavInfo;
    // 最近长按的收藏对象：菜单构建时 gc 不直接持有 item，用它兜底判断是否注入
    private static volatile Object sLastLongClickInfo;

    private FavVoiceForwardHook() {}

    // ---------------- 配置 ----------------

    public static boolean isEnabled() {
        SharedPreferences sp = safePrefs();
        return sp != null && sp.getBoolean(K_ENABLED, false);
    }

    public static void setEnabled(boolean on) {
        try {
            ContextManager.getPrefs().edit().putBoolean(K_ENABLED, on).apply();
        } catch (Throwable ignored) {}
        sEnabled = on;
        LogWriter.log(TAG, "setEnabled=" + on);
    }

    private static SharedPreferences safePrefs() {
        try {
            return ContextManager.getPrefs();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- Hook 安装 ----------------

    public static void hook(final ClassLoader cl) {
        sCl = cl;
        try {
            sEnabled = isEnabled();
        } catch (Throwable ignored) {}
        if (sHooked) return;
        Thread t = new Thread(() -> {
            for (int attempt = 0; attempt < 10 && !sHooked; attempt++) {
                try {
                    installHooks(cl);
                    sHooked = true;
                    LogWriter.log(TAG, "hooks installed attempt=" + attempt);
                    return;
                } catch (Throwable e) {
                    LogWriter.log(TAG, "install attempt " + attempt + " failed: " + e.getMessage());
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }
        }, "leshao-fav-voice-hook");
        t.setDaemon(true);
        t.start();
    }

    private static void installHooks(ClassLoader cl) throws Throwable {
        // ===== 0. 捕获长按的收藏对象：fc.onItemLongClick =====
        installLongClickCapture(cl);
        // ===== 1. 注入菜单项：de2.m（新链路）与 gc（兜底）=====
        installMenuInject(cl);
        // ===== 2. 拦截点击：de2.n（新链路）与 hc（兜底）=====
        installClickInterceptor(cl);
        // ===== 3. 选人结果：FavoriteIndexUI.onActivityResult + SelectConversationUI.W7 兜底 =====
        installPickResult(cl);
        // ===== 3.1 选人兜底：SelectConversationUI.onPause 字段扫描（微信新版不走 onActivityResult 返回）=====
        installSelectUiCapture(cl);
        LogWriter.log(TAG, "installHooks done");
    }

    /** 捕获收藏列表长按的语音项：fc.onItemLongClick → ui.W.i(pos - headerViews)。 */
    private static void installLongClickCapture(ClassLoader cl) {
        try {
            Class<?> fcCls = XposedHelpers.findClass("com.tencent.mm.plugin.fav.ui.fc", cl);
            XposedBridge.hookAllMethods(fcCls, "onItemLongClick", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled) return;
                        Activity ui = findActivity(param.thisObject);
                        if (ui == null) return;
                        Object adapter = getObjectFieldByName(ui, "W");
                        if (adapter == null) return;
                        int pos = param.args.length > 2 && param.args[2] instanceof Number
                                ? ((Number) param.args[2]).intValue() : -1;
                        int header = 0;
                        try {
                            Object lv = XposedHelpers.getObjectField(ui, "h");
                            if (lv != null) {
                                header = ((Number) XposedHelpers.callMethod(lv,
                                        "getHeaderViewsCount")).intValue();
                            }
                        } catch (Throwable ignored) {}
                        if (pos < 0) return;
                        Object item = XposedHelpers.callMethod(adapter, "i", pos - header);
                        if (item == null) return;
                        int t = getType(item);
                        LogWriter.log(TAG, "longClick pos=" + pos + " type=" + t);
                        if (t == TYPE_VOICE) {
                            sLastLongClickInfo = item;
                            LogWriter.log(TAG, "longClick voice saved localId=" + getLocalId(item));
                        }
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "longClick capture hooked");
        } catch (Throwable t) {
            LogWriter.log(TAG, "longClick capture err: " + t.getMessage());
        }
    }

    // ---------------- 菜单注入 ----------------

    private static void installMenuInject(ClassLoader cl) {
        XC_MethodHook injectHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!sEnabled) return;
                    Object builder = param.thisObject;
                    Object item = resolveItemFromMenuContext(param, builder);
                    int t = getType(item);
                    LogWriter.log(TAG, "menu build after: cls=" + builder.getClass().getName()
                            + " item=" + (item == null ? "null" : item.getClass().getName())
                            + " type=" + t);
                    if (item == null || t != TYPE_VOICE) return;
                    Object menu = param.args.length > 0 ? param.args[0] : null;
                    if (menu == null) return;
                    if (menuHasItem(menu, ITEM_VOICE_FWD)) {
                        LogWriter.log(TAG, "menu inject: item already present menu="
                                + menu.getClass().getName());
                        return;
                    }
                    boolean ok = addMenuItem(menu, ITEM_VOICE_FWD, "语音转发");
                    LogWriter.log(TAG, "menu inject itemId=" + ITEM_VOICE_FWD + " ok=" + ok
                            + " menu=" + menu.getClass().getName());
                } catch (Throwable ignored) {}
            }
        };

        Set<String> builderCands = new LinkedHashSet<>();
        builderCands.add(C_BUILDER_NEW);
        builderCands.add(C_BUILDER_OLD);
        for (String cn : builderCands) {
            try {
                for (Class<?> c : HookUtil.loadClasses(cl, cn)) {
                    for (Method m : c.getDeclaredMethods()) {
                        if (!isMenuBuildMethod(m)) continue;
                        m.setAccessible(true);
                        LogWriter.log(TAG, "menu builder hooked " + c.getName() + "."
                                + m.getName() + " params=" + Arrays.toString(m.getParameterTypes()));
                        XposedBridge.hookMethod(m, injectHook);
                    }
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "menu builder " + cn + " err: " + t.getMessage());
            }
        }
    }

    /** 判定是否为菜单构建方法：参数形如 (菜单接口, View, ContextMenuInfo)。 */
    private static boolean isMenuBuildMethod(Method m) {
        Class<?>[] pts = m.getParameterTypes();
        if (pts.length != 3) return false;
        String p0 = pts[0].getName();
        if (!p0.startsWith("kj5")) return false;
        if (pts[1] != View.class) return false;
        return pts[2] == ContextMenu.ContextMenuInfo.class
                || pts[2].isAssignableFrom(ContextMenu.ContextMenuInfo.class);
    }

    // ---------------- 点击拦截 ----------------

    private static void installClickInterceptor(ClassLoader cl) {
        XC_MethodHook clickHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!sEnabled) return;
                    int itemId = findMenuItemId(param.args);
                    LogWriter.log(TAG, "click dispatch: m=" + param.method.getName()
                            + " this=" + param.thisObject.getClass().getName()
                            + " itemId=" + itemId + " pending=" + sPendingLocalId);
                    if (itemId != ITEM_VOICE_FWD) return;
                    // 阻止微信原生逻辑（K7 对 10086 无分支，返回 boolean 时放行）
                    try {
                        Class<?> rt = ((Method) param.method).getReturnType();
                        if (rt == boolean.class) param.setResult(true);
                    } catch (Throwable ignored) {}
                    Object item = resolveItemFromMenuContext(param, param.thisObject);
                    if (item == null || getType(item) != TYPE_VOICE) item = sLastLongClickInfo;
                    if (item == null) {
                        LogWriter.log(TAG, "voice forward: no fav item, skip");
                        return;
                    }
                    Activity act = findActivity(param.thisObject);
                    if (act == null) {
                        LogWriter.log(TAG, "voice forward: no activity, skip");
                        return;
                    }
                    startForward(act, item);
                } catch (Throwable ignored) {}
            }
        };

        Set<String> dispatchCands = new LinkedHashSet<>();
        dispatchCands.add(C_DISPATCH_NEW);
        dispatchCands.add(C_DISPATCH_OLD);
        for (String cn : dispatchCands) {
            try {
                for (Class<?> c : HookUtil.loadClasses(cl, cn)) {
                    for (Method m : c.getDeclaredMethods()) {
                        if (!isMenuClickMethod(m)) continue;
                        m.setAccessible(true);
                        LogWriter.log(TAG, "click dispatch hooked " + c.getName() + "."
                                + m.getName() + " params=" + Arrays.toString(m.getParameterTypes()));
                        XposedBridge.hookMethod(m, clickHook);
                    }
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "click dispatch " + cn + " err: " + t.getMessage());
            }
        }
    }

    /** 判定是否为菜单点击分发方法：形如 onMMMenuItemSelected(MenuItem,int)。 */
    private static boolean isMenuClickMethod(Method m) {
        Class<?>[] pts = m.getParameterTypes();
        if (pts.length == 2 && pts[1] == int.class) {
            String p0 = pts[0].getName();
            if ("android.view.MenuItem".equals(p0) || p0.startsWith("android.view")) return true;
        }
        return "onMMMenuItemSelected".equals(m.getName());
    }

    // ---------------- 选人结果 ----------------

    private static void installPickResult(ClassLoader cl) {
        // 主入口：FavoriteIndexUI.onActivityResult 接收选人结果
        try {
            Class<?> favUi = XposedHelpers.findClass(C_FAV_UI, cl);
            XposedBridge.hookAllMethods(favUi, "onActivityResult", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled) return;
                        if (param.args.length < 3) return;
                        int reqCode = ((Number) param.args[0]).intValue();
                        int resultCode = ((Number) param.args[1]).intValue();
                        LogWriter.log(TAG, "onActivityResult req=" + reqCode
                                + " result=" + resultCode + " pending=" + sPendingLocalId);
                        if (reqCode != REQ_VOICE_FWD) return;
                        if (sPendingLocalId == 0) return; // W7 已消费，防重复
                        if (resultCode != Activity.RESULT_OK) {
                            sPendingLocalId = 0;
                            sPendingFavInfo = null;
                            return;
                        }
                        Intent data = (Intent) param.args[2];
                        String talker = data == null ? null
                                : data.getStringExtra("Select_Conv_User");
                        if (talker == null || talker.isEmpty()) {
                            LogWriter.log(TAG, "onActivityResult no Select_Conv_User");
                            return;
                        }
                        long localId = sPendingLocalId;
                        Object info = sPendingFavInfo;
                        sPendingLocalId = 0;
                        sPendingFavInfo = null;
                        final String toUser = talker;
                        final long lid = localId;
                        final Object fav = info;
                        new Thread(() -> sendVoice(lid, fav, toUser), "leshao-fav-voice-send")
                                .start();
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "onActivityResult hooked");
        } catch (Throwable t) {
            LogWriter.log(TAG, "onActivityResult hook err: " + t.getMessage());
        }

        // 兜底：SelectConversationUI.W7(String) 单选回调 —— 阻止微信原生 X7 链路并回传 talker
        try {
            Class<?> selUi = XposedHelpers.findClass(C_SELECT_UI, cl);
            XposedBridge.hookAllMethods(selUi, "W7", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled) return;
                        if (param.args.length != 1 || !(param.args[0] instanceof String)) return;
                        String username = (String) param.args[0];
                        if (sPendingLocalId == 0) return;
                        LogWriter.log(TAG, "W7 selected=" + username + " localId=" + sPendingLocalId);
                        // 阻止微信继续走 X7（Retr_Msg_Type=2 对语音无效）
                        param.setResult(null);
                        try {
                            Activity act = (Activity) param.thisObject;
                            Intent data = new Intent();
                            data.putExtra("Select_Conv_User", username);
                            act.setResult(Activity.RESULT_OK, data);
                            act.finish();
                        } catch (Throwable ignored) {}
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "W7 hooked");
        } catch (Throwable t) {
            LogWriter.log(TAG, "W7 hook err: " + t.getMessage());
        }
    }

    /** 选人兜底：微信 8.0.78 SelectConversationUI 单选不走 onActivityResult 返回数据
     *  （result 恒为 -1），选中联系人保存在 Activity 实例字段中。
     *  SelectConversationUI 的 onPause 可能在父类（MMFragmentActivity）声明，
     *  因此全局 hook Activity.onPause，再判断实例是否为选人界面。 */
    private static void installSelectUiCapture(ClassLoader cl) {
        try {
            XposedBridge.hookAllMethods(Activity.class, "onPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled || sPendingLocalId == 0) return;
                        Object thiz = param.thisObject;
                        if (thiz == null) return;
                        String cn = thiz.getClass().getName();
                        if (!cn.equals(C_SELECT_UI)
                                && !cn.startsWith("com.tencent.mm.ui.transmit.SelectConversation")) {
                            return;
                        }
                        Activity ui = (Activity) thiz;
                        String talker = findTalkerInFields(ui);
                        LogWriter.log(TAG, "select onPause pending=" + sPendingLocalId
                                + " talker=" + talker + " cls=" + cn);
                        if (talker == null || talker.isEmpty()) {
                            dumpStringFields(ui);
                            return;
                        }
                        long localId = sPendingLocalId;
                        Object info = sPendingFavInfo;
                        sPendingLocalId = 0;
                        sPendingFavInfo = null;
                        final String toUser = talker;
                        final long lid = localId;
                        final Object fav = info;
                        new Thread(() -> sendVoice(lid, fav, toUser), "leshao-fav-voice-send")
                                .start();
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "select onPause capture hooked (global Activity.onPause)");
        } catch (Throwable t) {
            LogWriter.log(TAG, "installSelectUiCapture err: " + t.getMessage());
        }
    }

    /** 找不到用户名时打印全部 String 字段（值裁剪），便于下次定位选中联系人存在哪里。 */
    private static void dumpStringFields(Object o) {
        try {
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(o);
                        if (v instanceof String) {
                            String s = (String) v;
                            if (s.isEmpty()) continue;
                            LogWriter.log(TAG, "  strField " + c.getSimpleName() + "." + f.getName()
                                    + " = " + (s.length() > 40 ? s.substring(0, 40) + "..." : s));
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    /** 扫描 Activity 实例字段，找像微信用户名的 String（wxid_/gh_/@chatroom/手机号）。 */
    private static String findTalkerInFields(Object o) {
        if (o == null) return null;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v instanceof String) {
                        String s = (String) v;
                        if (looksLikeTalker(s)) {
                            LogWriter.log(TAG, "select field " + f.getName() + " -> " + s);
                            return s;
                        }
                    } else if (v instanceof List) {
                        List<?> l = (List<?>) v;
                        if (!l.isEmpty()) {
                            Object first = l.get(0);
                            if (first != null && looksLikeTalker(first.toString())) {
                                LogWriter.log(TAG, "select list field " + f.getName()
                                        + " -> " + first);
                                return first.toString();
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static boolean looksLikeTalker(String s) {
        if (s == null || s.isEmpty()) return false;
        if (s.contains("@chatroom")) return true;
        if (s.startsWith("wxid_") || s.startsWith("gh_")) return true;
        if (s.length() >= 5 && s.length() <= 20) {
            boolean allDigit = true;
            for (int i = 0; i < s.length(); i++) {
                if (!Character.isDigit(s.charAt(i))) { allDigit = false; break; }
            }
            if (allDigit) return true;
        }
        return false;
    }

    // ---------------- 转发启动与发送 ----------------

    private static void startForward(Activity ctx, Object favItem) {
        sPendingLocalId = getLocalId(favItem);
        sPendingFavInfo = favItem;
        try {
            Intent it = new Intent();
            it.setClassName(ctx, C_SELECT_UI);
            // 单选转发模式：不加 mutil_select_is_ret，点联系人立即返回 RESULT_OK
            it.putExtra("Select_Conv_Type", 3);
            it.putExtra("scene_from", 1);
            it.putExtra("select_count", 1);
            ctx.startActivityForResult(it, REQ_VOICE_FWD);
            LogWriter.log(TAG, "startForward SelectConversationUI localId=" + sPendingLocalId);
        } catch (Throwable t) {
            LogWriter.log(TAG, "startForward err: " + t.getMessage());
            toast("无法打开转发选择");
        }
    }

    /** 收藏语音 → 直接复用模块内已验证的语音发送链路
     *  {@link TtsVoiceSender#sendViaSceneVoice}：
     *  v61.d1.h(talker,"amr_") 建记录 → 复制到 voice2 目录 → v61.d1.u 建 type=34 消息
     *  → v61.v0.dj().e() 踢上传队列 → refreshChattingList 上屏。 */
    private static void sendVoice(long localId, Object favInfo, String toUser) {
        try {
            ClassLoader cl = sCl;
            if (cl == null) return;
            if (toUser == null || toUser.isEmpty()) { toast("转发目标为空"); return; }
            LogWriter.log(TAG, "sendVoice start localId=" + localId + " to=" + toUser);

            // ① 收藏语音本地文件
            Object rq0 = callFavApi(cl, "K", favInfo);
            if (rq0 == null) { toast("获取收藏语音数据失败"); return; }
            String srcPath = (String) callFavApi(cl, "y", rq0);
            if (srcPath == null || srcPath.isEmpty() || !new File(srcPath).exists()) {
                LogWriter.log(TAG, "silk file missing: " + srcPath);
                toast("语音文件尚未下载，请先在收藏中播放一次");
                return;
            }
            int durMs = 0;
            try { durMs = XposedHelpers.getIntField(rq0, "y"); } catch (Throwable ignored) {}
            if (durMs <= 0) durMs = 1000;
            LogWriter.log(TAG, "fav voice src=" + srcPath + " durMs=" + durMs);

            // ② 复用模块内已验证链路发送（v61.d1.h/u + kick queue + 上屏）
            boolean ok = TtsVoiceSender.sendViaSceneVoice(toUser, srcPath, durMs);
            LogWriter.log(TAG, "fav voice sendViaSceneVoice ok=" + ok + " to=" + toUser);
            if (ok) {
                toast("语音已转发");
            } else {
                toast("语音转发失败");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "sendVoice err: " + t.getMessage());
            toast("语音发送失败: " + t.getMessage());
        }
    }

    /** 构造 v61.o(String fileName, int durMs) 上传场景。 */
    private static Object newUploadScene(ClassLoader cl, String fileName, int durMs) {
        try {
            Class<?> sceneCls = XposedHelpers.findClass(C_UPLOAD_VOICE, cl);
            return XposedHelpers.newInstance(sceneCls, fileName, durMs);
        } catch (Throwable t) {
            LogWriter.log(TAG, "newUploadScene err: " + t.getMessage());
            return null;
        }
    }

    /** 调用 b41.h9.e() 等静态入口。 */
    private static Object getCoreEntry(ClassLoader cl, String method) {
        try {
            Class<?> entry = XposedHelpers.findClass(C_CORE_ENTRY, cl);
            return XposedHelpers.callStaticMethod(entry, method);
        } catch (Throwable t) {
            LogWriter.log(TAG, "getCoreEntry " + method + " err: " + t.getMessage());
            return null;
        }
    }

    /** 调用 NetSceneQueue.g(NetScene) 入队，返回是否成功。 */
    private static boolean enqueueScene(ClassLoader cl, Object queue, Object scene) {
        try {
            Class<?> qCls = XposedHelpers.findClass(C_NETSCENE_QUEUE, cl);
            Method m = findMethod(qCls, "g", scene.getClass());
            if (m == null) {
                for (Method mm : qCls.getDeclaredMethods()) {
                    if ("g".equals(mm.getName()) && mm.getParameterTypes().length == 1) {
                        m = mm;
                        break;
                    }
                }
            }
            if (m == null) {
                LogWriter.log(TAG, "NetSceneQueue.g not found");
                return false;
            }
            m.setAccessible(true);
            Object r = m.invoke(queue, scene);
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (Throwable t) {
            LogWriter.log(TAG, "enqueueScene err: " + t.getMessage());
            return false;
        }
    }

    /** 解析 voice 目录中目标文件路径：u0.Fj(ou5.x.j, newName, false, true)。 */
    private static String voiceDirPath(ClassLoader cl, String newName) {
        try {
            Class<?> pathEnum = XposedHelpers.findClass(C_VOICE_PATH, cl);
            Object voiceDir = XposedHelpers.getStaticObjectField(pathEnum, "j");
            Class<?> svc = XposedHelpers.findClass(C_VOICE_SERVICE, cl);
            Object u0 = getServiceByClass(cl, svc);
            if (u0 == null) return null;
            Method m = findMethod(u0.getClass(), "Fj", voiceDir.getClass(), String.class,
                    boolean.class, boolean.class);
            if (m == null) {
                for (Method mm : u0.getClass().getMethods()) {
                    Class<?>[] pts = mm.getParameterTypes();
                    if (pts.length == 4 && pts[1] == String.class && pts[2] == boolean.class
                            && pts[3] == boolean.class) {
                        m = mm;
                        break;
                    }
                }
            }
            if (m == null) {
                LogWriter.log(TAG, "u0.Fj not found in " + u0.getClass().getName());
                return null;
            }
            m.setAccessible(true);
            Object dst = m.invoke(u0, voiceDir, newName, false, true);
            return dst == null ? null : dst.toString();
        } catch (Throwable t) {
            LogWriter.log(TAG, "voiceDirPath err: " + t.getMessage());
            return null;
        }
    }

    // ---------------- 反射工具 ----------------

    private static Object callFavApi(ClassLoader cl, String method, Object... args) {
        try {
            Class<?> apiCls = XposedHelpers.findClass(C_FAV_API, cl);
            try {
                return XposedHelpers.callStaticMethod(apiCls, method, args);
            } catch (Throwable ignored) {}
            try {
                Object inst = XposedHelpers.getStaticObjectField(apiCls, "INSTANCE");
                if (inst != null) return XposedHelpers.callMethod(inst, method, args);
            } catch (Throwable ignored) {}
            Object svc = getService(cl, C_FAV_API);
            if (svc != null) return XposedHelpers.callMethod(svc, method, args);
        } catch (Throwable t) {
            LogWriter.log(TAG, "callFavApi " + method + " err: " + t.getMessage());
        }
        return null;
    }

    private static Object callVoiceLogic(ClassLoader cl, Object d1, String method, Object... args)
            throws Throwable {
        try {
            Method m = findMethod(d1.getClass(), method, argTypes(args));
            if (m != null) {
                m.setAccessible(true);
                return m.invoke(d1, args);
            }
            return XposedHelpers.callMethod(d1, method, args);
        } catch (Throwable t) {
            LogWriter.log(TAG, "callVoiceLogic " + method + " err: " + t.getMessage());
            throw t;
        }
    }

    private static Class<?>[] argTypes(Object... args) {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] == null ? Object.class : args[i].getClass();
        }
        return types;
    }

    private static Object getService(ClassLoader cl, String serviceClsName) {
        try {
            return getServiceByClass(cl, XposedHelpers.findClass(serviceClsName, cl));
        } catch (Throwable t) {
            LogWriter.log(TAG, "getService " + serviceClsName + " FAILED: " + t.getMessage());
            return null;
        }
    }

    private static Object getServiceByClass(ClassLoader cl, Class<?> svcCls) {
        List<String> locators = new ArrayList<>();
        String dk = DexKitHelper.getServiceLocatorClass();
        if (dk != null && !dk.isEmpty()) locators.add(dk);
        locators.add("ph5.n0");
        locators.add("pa5.n0");
        locators.add("hm0.j1");
        for (String loc : locators) {
            try {
                Class<?> locCls = XposedHelpers.findClass(loc, cl);
                Object inst = XposedHelpers.callStaticMethod(locCls, "c", svcCls);
                if (inst != null) {
                    LogWriter.log(TAG, "getService " + svcCls.getName() + " via " + loc);
                    return inst;
                }
            } catch (Throwable ignored) {}
        }
        LogWriter.log(TAG, "getService " + svcCls.getName() + " FAILED");
        return null;
    }

    /** 从菜单构建参数解析收藏对象：优先用长按 View 找 AbsListView 定位 position（主路径），
     *  再尝试 ContextMenuInfo，最后回退字段链/长按缓存。 */
    private static Object resolveItemFromMenuContext(XC_MethodHook.MethodHookParam param,
                                                      Object builder) {
        // ① 长按 View 参数：沿父链找 ListView，getPositionForView 定位
        Object viewArg = param.args.length > 1 ? param.args[1] : null;
        if (viewArg instanceof View) {
            View v = (View) viewArg;
            ListView lv = findAncestorListView(v);
            if (lv != null) {
                int pos = lv.getPositionForView(v);
                LogWriter.log(TAG, "menu view: cls=" + v.getClass().getName()
                        + " pos=" + pos);
                if (pos >= 0) {
                    Activity ui = findActivity(builder);
                    if (ui != null) {
                        Object adapter = getObjectFieldByName(ui, "W");
                        int header = lv.getHeaderViewsCount();
                        Object item = callAdapterItem(adapter, pos - header);
                        LogWriter.log(TAG, "menu view resolve pos=" + pos + " header=" + header
                                + " item=" + (item == null ? "null" : item.getClass().getName())
                                + " type=" + getType(item));
                        if (item != null) return item;
                    }
                }
            }
            Object tag = v.getTag();
            if (tag != null && hasFieldType(tag)) return tag;
        }
        // ② ContextMenuInfo 兜底
        try {
            Object info = param.args.length > 2 ? param.args[2] : null;
            if (info != null) {
                int pos = -1;
                try {
                    Object p = XposedHelpers.callMethod(info, "getPosition");
                    if (p instanceof Number) pos = ((Number) p).intValue();
                } catch (Throwable ignored) {}
                if (pos < 0) {
                    try { pos = XposedHelpers.getIntField(info, "position"); } catch (Throwable ignored) {}
                }
                if (pos >= 0) {
                    Activity ui = findActivity(builder);
                    if (ui != null) {
                        Object adapter = getObjectFieldByName(ui, "W");
                        int header = 0;
                        try {
                            Object lv = XposedHelpers.getObjectField(ui, "h");
                            if (lv != null) {
                                header = ((Number) XposedHelpers.callMethod(lv,
                                        "getHeaderViewsCount")).intValue();
                            }
                        } catch (Throwable ignored) {}
                        Object item = callAdapterItem(adapter, pos - header);
                        if (item != null) return item;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return findFavItemFromBuilder(builder);
    }

    /** 沿 View 父链找 AbsListView（ListView/GridView）。 */
    private static ListView findAncestorListView(View v) {
        View cur = v;
        while (cur != null) {
            if (cur instanceof ListView) return (ListView) cur;
            Object parent = cur.getParent();
            if (!(parent instanceof View)) break;
            cur = (View) parent;
        }
        return null;
    }

    /** 调用收藏列表 adapter 的按位置取 item 方法（i / getItem 等）。 */
    private static Object callAdapterItem(Object adapter, int position) {
        if (adapter == null) return null;
        try {
            return XposedHelpers.callMethod(adapter, "i", position);
        } catch (Throwable ignored) {}
        try {
            return XposedHelpers.callMethod(adapter, "getItem", position);
        } catch (Throwable ignored) {}
        for (Class<?> c = adapter.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 1 && pts[0] == int.class
                        && m.getReturnType() != void.class && m.getReturnType() != int.class) {
                    try {
                        m.setAccessible(true);
                        Object v = m.invoke(adapter, position);
                        if (v != null) return v;
                    } catch (Throwable ignored) {}
                }
            }
        }
        return null;
    }

    /** 从菜单构建器/分发器对象中获取收藏对象：先字段链直找，再用最近长按兜底。 */
    private static Object findFavItemFromBuilder(Object builder) {
        if (builder == null) return null;
        Object direct = findFavItemInFields(builder);
        if (direct != null) return direct;
        return sLastLongClickInfo;
    }

    /** 按字段名沿继承链读取对象字段。 */
    private static Object getObjectFieldByName(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 在对象字段链中查找带 field_type 的收藏对象（de2.m 字段 b、gc 的 UI 引用）。 */
    private static Object findFavItemInFields(Object host) {
        if (host == null) return null;
        Class<?> c = host.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(host);
                    if (v == null) continue;
                    if (hasFieldType(v)) return v;
                    if (v instanceof List) {
                        List<?> l = (List<?>) v;
                        if (!l.isEmpty() && hasFieldType(l.get(0))) return l.get(0);
                    }
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static boolean hasFieldType(Object o) {
        if (o == null) return false;
        try {
            XposedHelpers.getIntField(o, "field_type");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int getType(Object favItem) {
        try {
            return XposedHelpers.getIntField(favItem, "field_type");
        } catch (Throwable t) {
            return -1;
        }
    }

    private static long getLocalId(Object favItem) {
        try {
            return XposedHelpers.getLongField(favItem, "field_localId");
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 从菜单点击回调参数中解析 itemId：第一个 Number，或可调用 getItemId() 的对象。 */
    private static int findMenuItemId(Object[] args) {
        if (args == null) return -1;
        for (Object a : args) {
            if (a == null) continue;
            if (a instanceof Number) return ((Number) a).intValue();
            try {
                Object id = XposedHelpers.callMethod(a, "getItemId");
                if (id instanceof Number) return ((Number) id).intValue();
            } catch (Throwable ignored) {}
        }
        return -1;
    }

    /** 查找对象字段链中的 Activity（de2.n 字段 e 是 Context，FavoriteIndexUI 本身是 Activity）。 */
    private static Activity findActivity(Object host) {
        if (host == null) return null;
        if (host instanceof Activity) return (Activity) host;
        Class<?> c = host.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(host);
                    if (v instanceof Activity) return (Activity) v;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** 判断菜单是否已含指定 itemId。 */
    private static boolean menuHasItem(Object menu, int itemId) {
        try {
            Object item = XposedHelpers.callMethod(menu, "findItem", itemId);
            return item != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 向菜单注入 itemId 项：kj5.i4.c(0, itemId, 0, title, iconRes)。 */
    private static boolean addMenuItem(Object menu, int itemId, String title) {
        Object[][] attempts = {
                {0, itemId, 0, title, 2131822160},
                {0, itemId, 0, title},
                {itemId, title}
        };
        for (String mn : new String[]{"c", "add"}) {
            for (Object[] args : attempts) {
                try {
                    XposedHelpers.callMethod(menu, mn, args);
                    return true;
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... paramTypes) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, paramTypes);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String md5OfFile(String path) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (FileInputStream in = new FileInputStream(path)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "0";
        }
    }

    private static void copyFile(String src, String dst) {
        try {
            File out = new File(dst);
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileInputStream in = new FileInputStream(src);
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "copyFile err: " + t.getMessage());
        }
    }

    private static void toast(final String msg) {
        try {
            final Context ctx = ContextManager.getAppContext();
            if (ctx == null) return;
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }
}