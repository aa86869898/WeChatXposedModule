package com.leshao.v3.hook;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 收藏语音转发 —— 文档《收藏语音转发WeChat_FavVoice_Forward_Analysis.md》路线 A。
 *
 * <p>微信官方在 {@code tc2.x3.b}（菜单可见性）、{@code FavoriteIndexUI.H7}（合法性预检）、
 * {@code mc.g/mc.h}（FavoriteMenuHelper）三处硬编码拦截收藏语音转发，且原生
 * SelectConversationUI → X7 链路对 type==3 无分支。模块放行三道拦截后复用微信原生选人页，
 * 在 {@code SelectConversationUI.W7(String)} 拦截选人结果，自行通过 {@code v61.d1}（VoiceLogic）
 * 组装 type=34 的语音消息并上传。</p>
 */
public final class FavVoiceForwardHook {

    public static final String TAG = "FavVoice";
    public static final String K_ENABLED = "ls_fav_voice_forward";

    // 本构建实测混淆名（文档附录），微信换包后需按文档第十节重新锚定
    private static final String C_FILTER = "tc2.x3";                          // FavSendFilter
    private static final String C_FAV_UI = "com.tencent.mm.plugin.fav.ui.FavoriteIndexUI";
    private static final String C_MENU_HELPER = "com.tencent.mm.plugin.fav.ui.mc"; // FavoriteMenuHelper
    private static final String C_SELECT_UI = "com.tencent.mm.ui.transmit.SelectConversationUI";
    private static final String C_FAV_API = "tc2.s2";                            // FavApiLogic
    private static final String C_VOICE_LOGIC = "v61.d1";                       // VoiceLogic
    private static final String C_VOICE_INFO = "v61.c1";                         // VoiceInfo
    private static final String C_P0 = "com.tencent.mm.modelbase.p0";             // 上传回调基类

    private static volatile boolean sEnabled = false;
    private static volatile boolean sHooked = false;
    private static volatile ClassLoader sCl;

    // 待转发状态：长按语音时由 mc.g/h 保存，选人后消费
    private static volatile long sPendingLocalId;
    private static volatile Object sPendingFavInfo;

    private static final int TYPE_VOICE = 3;
    private static final int STATUS_UPLOAD_WAIT = 3;
    private static final int COLUMN_MASK = 0x400d60; // 列存在位掩码（文档修正2）

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
        // 拦截 A：tc2.x3.b —— 语音恒 true 视为不可转发，强制 false 让「转发」菜单出现
        Class<?> filterCls = XposedHelpers.findClass(C_FILTER, cl);
        XposedBridge.hookAllMethods(filterCls, "b", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object info = param.args.length > 0 ? param.args[0] : null;
                    int t = getType(info);
                    long lid = getLocalId(info);
                    LogWriter.log(TAG, "x3.b hit: args=" + param.args.length
                            + " info=" + (info == null ? "null" : info.getClass().getName())
                            + " type=" + t + " localId=" + lid);
                    if (t == TYPE_VOICE) {
                        LogWriter.log(TAG, "x3.b voice -> force false");
                        param.setResult(false);
                    }
                } catch (Throwable t) {
                    LogWriter.log(TAG, "x3.b err: " + t.getMessage());
                }
            }
        });

        // 拦截 B：FavoriteIndexUI.H7 —— 列表含语音时直接放行
        Class<?> favUi = XposedHelpers.findClass(C_FAV_UI, cl);
        XposedBridge.hookAllMethods(favUi, "H7", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object listObj = param.args.length > 0 ? param.args[0] : null;
                    boolean hasVoice = false;
                    if (listObj instanceof List) {
                        for (Object item : (List<?>) listObj) {
                            if (getType(item) == TYPE_VOICE) {
                                hasVoice = true;
                                break;
                            }
                        }
                    }
                    LogWriter.log(TAG, "H7 hit: args=" + param.args.length + " list="
                            + (listObj == null ? "null" : listObj.getClass().getName())
                            + " hasVoice=" + hasVoice);
                    if (hasVoice) {
                        LogWriter.log(TAG, "H7 voice list -> force true");
                        param.setResult(true);
                    }
                } catch (Throwable ignored) {}
            }
        });

        // 诊断：菜单点击分发 K7 —— 确认「转发」点击是否到达
        XposedBridge.hookAllMethods(favUi, "K7", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object itemId = param.args.length > 0 ? param.args[0] : null;
                    LogWriter.log(TAG, "K7 hit: args=" + param.args.length + " itemId=" + itemId);
                } catch (Throwable ignored) {}
            }
        });

        // 拦截 C：mc.g / mc.h —— 语音单条/多条放行并记录 localId
        Class<?> menuHelper = XposedHelpers.findClass(C_MENU_HELPER, cl);
        XposedBridge.hookAllMethods(menuHelper, "g", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object info = param.args.length > 3 ? param.args[3] : null;
                    int t = getType(info);
                    LogWriter.log(TAG, "mc.g hit: args=" + param.args.length
                            + " info=" + (info == null ? "null" : info.getClass().getName())
                            + " type=" + t);
                    if (t == TYPE_VOICE) {
                        sPendingLocalId = getLocalId(info);
                        sPendingFavInfo = info;
                        LogWriter.log(TAG, "mc.g voice -> allow localId=" + sPendingLocalId);
                        param.setResult(true);
                    }
                } catch (Throwable ignored) {}
            }
        });
        XposedBridge.hookAllMethods(menuHelper, "h", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object listObj = param.args.length > 2 ? param.args[2] : null;
                    LogWriter.log(TAG, "mc.h hit: args=" + param.args.length
                            + " list=" + (listObj == null ? "null" : listObj.getClass().getName()));
                    if (listObj instanceof List && !((List<?>) listObj).isEmpty()) {
                        Object info = ((List<?>) listObj).get(0);
                        int t = getType(info);
                        if (t == TYPE_VOICE) {
                            sPendingLocalId = getLocalId(info);
                            sPendingFavInfo = info;
                            LogWriter.log(TAG, "mc.h voice -> allow localId=" + sPendingLocalId);
                            param.setResult(true);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        });

        // 兜底：长按菜单构建 gc.a() 中若微信未添加「转发」项（x3.b 未生效），手动注入 itemId=3
        try {
            Class<?> gcCls = XposedHelpers.findClass("com.tencent.mm.plugin.fav.ui.gc", cl);
            XposedBridge.hookAllMethods(gcCls, "a", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled) return;
                        Object host = param.thisObject;
                        Object existing = null;
                        try {
                            existing = XposedHelpers.callMethod(host, "findItem", 3);
                        } catch (Throwable ignored) {}
                        if (existing != null) return;
                        boolean added = tryAddForwardItem(host);
                        LogWriter.log(TAG, "gc.a after: findItem3=" + (existing != null)
                                + " addForward=" + added);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "gc hook err: " + t.getMessage());
        }

        // 接管选人结果：W7(String username) 单选回调
        Class<?> selUi = XposedHelpers.findClass(C_SELECT_UI, cl);
        XposedBridge.hookAllMethods(selUi, "W7", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args.length != 1 || !(param.args[0] instanceof String)) return;
                    String username = (String) param.args[0];
                    if (sPendingLocalId == 0) return;
                    final long localId = sPendingLocalId;
                    final Object favInfo = sPendingFavInfo;
                    sPendingLocalId = 0;
                    sPendingFavInfo = null;
                    LogWriter.log(TAG, "W7 selected=" + username + " localId=" + localId);
                    // 阻止微信继续走 X7（Retr_Msg_Type=2 对语音不通）
                    param.setResult(null);
                    // 关闭选人页
                    try {
                        Activity act = (Activity) param.thisObject;
                        act.setResult(Activity.RESULT_OK);
                        act.finish();
                    } catch (Throwable ignored) {}
                    final String toUser = username;
                    new Thread(() -> sendVoice(localId, favInfo, toUser), "leshao-fav-voice-send")
                            .start();
                } catch (Throwable ignored) {}
            }
        });
    }

    /** 手动向收藏长按菜单注入「转发」项（兼容不同 add 签名） */
    private static boolean tryAddForwardItem(Object menuHost) {
        Object[][] attempts = {
                {0, 3, 0, 2131761210, 0},
                {0, 3, 0, "转发", 0},
                {0, 3, 0, 2131761210},
                {0, 3, 0, "转发"},
                {3, "转发"},
                {0, 0, 0, "转发"}
        };
        for (Object[] args : attempts) {
            try {
                XposedHelpers.callMethod(menuHost, "add", args);
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    // ---------------- 发送逻辑 ----------------

    private static void sendVoice(long localId, Object favInfo, String toUser) {
        try {
            ClassLoader cl = sCl;
            if (cl == null) return;
            if (toUser == null || toUser.isEmpty()) { toast("转发目标为空"); return; }

            // 1. tc2.s2.K(info) → FavDataItem(rq0)
            Object rq0 = callFavApi(cl, "K", favInfo);
            if (rq0 == null) { toast("获取收藏语音数据失败"); return; }

            // 2. tc2.s2.y(rq0) → 本地 silk 路径
            String path = (String) callFavApi(cl, "y", rq0);
            if (path == null || path.isEmpty() || !new File(path).exists()) {
                LogWriter.log(TAG, "silk file missing: " + path);
                toast("语音文件尚未下载，请先在收藏中播放一次");
                return;
            }

            // 3. 后缀 → voiceType；rq0.y → 时长(ms)
            String suffix = (String) XposedHelpers.getObjectField(rq0, "K");
            int voiceType = 0;
            try {
                Object vt = callFavApi(cl, "d0", suffix);
                if (vt instanceof Integer) voiceType = (Integer) vt;
            } catch (Throwable ignored) {}
            int voiceLengthMs = 0;
            try {
                voiceLengthMs = XposedHelpers.getIntField(rq0, "y");
            } catch (Throwable ignored) {}
            int voiceLengthSec = Math.max(1, (int) Math.ceil(voiceLengthMs / 1000.0));
            long fileLen = new File(path).length();

            // 4. 组装 v61.c1（VoiceInfo），字段名见文档修正2
            Class<?> c1Cls = XposedHelpers.findClass(C_VOICE_INFO, cl);
            Object voiceInfo = XposedHelpers.newInstance(c1Cls);
            String fileName = new File(path).getName();
            long now = System.currentTimeMillis();
            XposedHelpers.setObjectField(voiceInfo, "b", fileName);      // FileName
            XposedHelpers.setObjectField(voiceInfo, "c", toUser);        // User（同时作 talker）
            XposedHelpers.setObjectField(voiceInfo, "s", toUser);        // MsgTalker
            XposedHelpers.setObjectField(voiceInfo, "g", fileLen); // FileNowSize
            XposedHelpers.setObjectField(voiceInfo, "h", fileLen); // TotalLen
            XposedHelpers.setIntField(voiceInfo, "i", STATUS_UPLOAD_WAIT); // Status 待传
            XposedHelpers.setObjectField(voiceInfo, "j", now); // CreateTime
            XposedHelpers.setObjectField(voiceInfo, "k", now); // LastModifyTime
            XposedHelpers.setIntField(voiceInfo, "l", voiceLengthSec); // VoiceLength(秒)
            XposedHelpers.setObjectField(voiceInfo, "t", UUID.randomUUID().toString()); // ClientId
            XposedHelpers.setIntField(voiceInfo, "a", COLUMN_MASK);     // 列存在位掩码
            LogWriter.log(TAG, "VoiceInfo assembled: file=" + fileName + " len=" + fileLen
                    + " dur=" + voiceLengthSec + "s to=" + toUser + " voiceType=" + voiceType);

            // 5. 获取 v61.d1（VoiceLogic）并调用 e(...) 触发上传
            Object d1 = getService(cl, C_VOICE_LOGIC);
            if (d1 == null) { toast("语音服务不可用"); return; }
            Object msgId = invokeVoiceLogic(cl, d1, "e", voiceInfo);
            if (msgId instanceof Long) {
                LogWriter.log(TAG, "voice uploaded msgId=" + msgId + " to=" + toUser);
                toast("收藏语音已发送");
            } else {
                LogWriter.log(TAG, "voice upload returned null");
                toast("语音发送失败");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "sendVoice err: " + t.getMessage());
            toast("语音发送失败: " + t.getMessage());
        }
    }

    /** 反射调用 v61.d1.e(VoiceInfo, boolean, int, String, String, p0) */
    private static Object invokeVoiceLogic(ClassLoader cl, Object d1, String method, Object voiceInfo)
            throws Throwable {
        try {
            Class<?> c1Cls = XposedHelpers.findClass(C_VOICE_INFO, cl);
            Class<?> p0Cls = null;
            try { p0Cls = XposedHelpers.findClass(C_P0, cl); } catch (Throwable ignored) {}
            if (p0Cls != null) {
                Method m = findMethod(d1.getClass(), method,
                        new Class<?>[]{c1Cls, boolean.class, int.class, String.class, String.class,
                                p0Cls});
                if (m != null) {
                    m.setAccessible(true);
                    return m.invoke(d1, voiceInfo, false, 0, null, null, null);
                }
            }
            // 兜底：让 XposedHelpers 按实参自动匹配方法
            return XposedHelpers.callMethod(d1, method, voiceInfo, false, 0, null, null, null);
        } catch (Throwable t) {
            LogWriter.log(TAG, "invokeVoiceLogic " + method + " err: " + t.getMessage());
            throw t;
        }
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>[] paramTypes) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, paramTypes);
                return m;
            } catch (Throwable ignored) {}
        }
        return null;
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
            // 兜底：通过服务定位器获取 FavApiLogic 实例
            Object svc = getService(cl, C_FAV_API);
            if (svc != null) return XposedHelpers.callMethod(svc, method, args);
        } catch (Throwable t) {
            LogWriter.log(TAG, "callFavApi " + method + " err: " + t.getMessage());
        }
        return null;
    }

    private static Object getService(ClassLoader cl, String serviceClsName) {
        List<String> locators = new ArrayList<>();
        String dk = DexKitHelper.getServiceLocatorClass();
        if (dk != null && !dk.isEmpty()) locators.add(dk);
        locators.add("ph5.n0");
        locators.add("pa5.n0");
        locators.add("hm0.j1");
        for (String loc : locators) {
            try {
                Class<?> locCls = XposedHelpers.findClass(loc, cl);
                Class<?> svcCls = XposedHelpers.findClass(serviceClsName, cl);
                Object inst = XposedHelpers.callStaticMethod(locCls, "c", svcCls);
                if (inst != null) {
                    LogWriter.log(TAG, "getService " + serviceClsName + " via " + loc);
                    return inst;
                }
            } catch (Throwable ignored) {}
        }
        LogWriter.log(TAG, "getService " + serviceClsName + " FAILED");
        return null;
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