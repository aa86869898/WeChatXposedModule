package com.leshao.ai.hook.wechat;

import android.util.Log;

import com.leshao.ai.hook.HookEntry;
import com.leshao.ai.hook.dexkit.DexKitAdapter;
import com.leshao.v3.LogWriter;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 接收消息 Hook（文档 §4 线路一 / §16.4）。
 * <p>
 * Hook {@code f9.Bb(e9, boolean)} —— 微信消息 insert 总闸门：
 * 群聊、私聊、系统消息、自己发出的消息全部走这里。
 * <p>
 * 取数一律用<b>未混淆字段名</b>（im.c8：field_talker / field_type / field_isSend /
 * field_msgSvrId / field_createTime / field_content / field_fromUsername），
 * 按文档 §19 字段名优先原则保证跨版本稳定。
 * <p>
 * 过滤链：防循环水印 → 只处理收到的（isSend==0）→ 只处理纯文本（type==1）→ 非空校验。
 * 判定通过后投递 {@link TriggerEngine}（其内部 post 到工作线程，
 * 因 f9.Bb 位于 DB 写锁内，严禁在此同步执行网络请求，文档 §17.2）。
 */
public final class MsgReceiveHook {

    private static final String TAG = "LeshaoAI.Recv";

    /** 纯文本消息类型。 */
    private static final int TYPE_TEXT = 1;

    private static volatile boolean installed;

    private MsgReceiveHook() {
    }

    /** 安装收消息 hook（幂等）。 */
    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (installed) {
            return;
        }
        installed = true;
        try {
            installInternal(lpparam);
        } catch (Throwable t) {
            installed = false;
            Log.w(TAG, "install 失败: " + t);
        }
    }

    private static void installInternal(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        // v1042: 微信经 Tinker 热修复时 f9/e9 由 DelegateLastClassLoader 真实加载,
        // lpparam.classLoader 只是 base.apk 平行副本 → hookAllMethods 挂到平行副本类上
        // 运行时消息入库不经过它 → 零捕获。改用 Tinker 真实 CL 定位。
        try {
            ClassLoader tk = com.leshao.v3.hook.VersionCompat.findTinkerClassLoader(cl);
            if (tk != null && !tk.getClass().getName().contains("Leshao")
                    && tk != cl) {
                LogWriter.log(TAG, "使用 Tinker 真实 CL " + tk.getClass().getSimpleName()
                        + " 定位 f9");
                cl = tk;
            }
        } catch (Throwable ignored) {
        }
        Class<?> f9 = DexKitAdapter.findMsgInfoStorageClass();
        if (f9 == null) {
            Log.w(TAG, "f9(MsgInfoStorage) 未定位，接收链路不可用");
            return;
        }
        // v1042: DexKitAdapter 类对象可能仍来自 hook appClassLoader 平行副本,
        // 若当前已切换到 Tinker CL, 按类名重新在真实 CL 上解析 f9/e9。BreedingClone 类
        // 名不混淆, 直接按全名重新加载即是真实类对象。
        try {
            if (cl != lpparam.classLoader) {
                Class<?> realF9 = XposedHelpers.findClass(f9.getName(), cl);
                if (realF9 != null) {
                    LogWriter.log(TAG, "f9 重新在真实 CL 加载: " + realF9.getName());
                    f9 = realF9;
                }
            }
        } catch (Throwable ignored) {
        }
        Class<?> e9 = DexKitAdapter.findMsgInfoClass();
        try {
            if (e9 != null && cl != lpparam.classLoader) {
                Class<?> realE9 = XposedHelpers.findClass(e9.getName(), cl);
                if (realE9 != null) {
                    e9 = realE9;
                }
            }
        } catch (Throwable ignored) {
        }

        XC_MethodHook callback = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.args != null && param.args.length > 0) {
                        onMessageInserted(param.args[0]);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "afterHookedMethod 异常: " + t);
                }
            }
        };

        // v1075: 微信不同版本收消息入库走 Bb/Db/Hb/yb 不同方法, 只挂 Bb 会漏收
        // (自动转发链早已同时挂这 4 个)。这里全部挂上, 由 svrId/对象去重防重复触发。
        int hooked = 0;
        for (java.lang.reflect.Method m : f9.getDeclaredMethods()) {
            String mn = m.getName();
            if (!"Bb".equals(mn) && !"Db".equals(mn) && !"Hb".equals(mn) && !"yb".equals(mn)) {
                continue;
            }
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length < 1 || pts[0] == null) {
                continue;
            }
            String p0 = pts[0].getName();
            if (!p0.endsWith(".e9") && !p0.contains("MsgInfo")) {
                continue;
            }
            XposedBridge.hookMethod(m, callback);
            hooked++;
        }
        if (hooked == 0) {
            // 兜底: 按名挂 Bb
            XposedBridge.hookAllMethods(f9, "Bb", callback);
            LogWriter.log(TAG, "已 hook " + f9.getName() + ".Bb (兜底 e9="
                    + (e9 == null ? "?" : e9.getName()) + ")");
        } else {
            LogWriter.log(TAG, "已 hook " + f9.getName()
                    + " insert 方法 " + hooked + " 个 (Bb/Db/Hb/yb, e9="
                    + (e9 == null ? "?" : e9.getName()) + ")");
        }
    }

    /** svrId 去重, 防止同一消息经多个入库方法重复触发。 */
    private static final java.util.Set<Long> seenSvrIds = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
    private static final java.util.Set<Integer> seenIdentity = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<Integer, Boolean>());

    /** 处理一条落库消息。 */
    private static void onMessageInserted(Object msg) {
        if (msg == null) {
            return;
        }
        // 字段名取数（文档 §19：实体字段名与 SQLite 列名绑定，永不被混淆）
        String talker = callStr(msg, "N0");
        if (talker == null || talker.isEmpty()) {
            talker = readString(msg, "field_talker");
        }
        String content = readContent(msg);
        Integer type = callInt(msg, "getType");
        if (type == null) {
            type = readInt(msg, "field_type");
        }
        Integer isSend = callInt(msg, "z0");
        if (isSend == null) {
            isSend = readInt(msg, "field_isSend");
        }
        Long svrId = callLong(msg, "F0");
        if (svrId == null || svrId == 0L) {
            svrId = readLong(msg, "field_msgSvrId");
        }
        Long createTime = readLong(msg, "field_createTime");
        String fromUser = callStr(msg, "s0");
        if (fromUser == null || fromUser.isEmpty()) {
            fromUser = readString(msg, "field_fromUsername");
        }

        // ① 防循环：机器人自己所发（零宽水印）
        if (SendGuard.isBotSent(content)) {
            return;
        }
        // ② 自己发出的消息：反推缓存 wxid 后跳过（文档 §8.4）
        if (isSend != null && isSend != 0) {
            StorageHub.get().cacheSelfWxidFrom(fromUser);
            return;
        }
        // ③ 只处理纯文本
        if (type == null || type != TYPE_TEXT || content == null || content.isEmpty()) {
            return;
        }
        // ④ 空 talker
        if (talker == null || talker.isEmpty()) {
            return;
        }
        // ⑤ 去重: svrId 优先, 无 svrId 时用对象身份
        if (svrId != null && svrId != 0L) {
            if (!seenSvrIds.add(svrId)) {
                return;
            }
            if (seenSvrIds.size() > 500) {
                seenSvrIds.clear();
            }
        } else {
            int id = System.identityHashCode(msg);
            if (!seenIdentity.add(id)) {
                return;
            }
            if (seenIdentity.size() > 500) {
                seenIdentity.clear();
            }
        }

        LogWriter.log(TAG, "收到文本 talker=" + talker + " svrId=" + svrId
                + " content='" + trunc(content) + "'");
        TriggerEngine.dispatch(talker, content, msg, svrId == null ? 0L : svrId,
                createTime == null ? 0L : createTime);
    }

    /** 取 content: 访问器优先, 字段兜底。 */
    private static String readContent(Object msg) {
        for (String mn : new String[]{"I0", "j", "N1"}) {
            String v = callStr(msg, mn);
            if (v != null) {
                return v;
            }
        }
        return readString(msg, "field_content");
    }

    private static String callStr(Object obj, String name) {
        try {
            Object v = XposedHelpers.callMethod(obj, name);
            return v == null ? null : v.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Integer callInt(Object obj, String name) {
        try {
            Object v = XposedHelpers.callMethod(obj, name);
            if (v instanceof Number) {
                return ((Number) v).intValue();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Long callLong(Object obj, String name) {
        try {
            Object v = XposedHelpers.callMethod(obj, name);
            if (v instanceof Number) {
                return ((Number) v).longValue();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 日志用截断。 */
    private static String trunc(String s) {
        if (s == null) return "";
        String one = s.replace('\n', ' ').trim();
        return one.length() > 60 ? one.substring(0, 60) + "..." : one;
    }

    // ---------- 字段读取（兼容 int/long 装箱差异） ----------

    private static String readString(Object obj, String field) {
        try {
            Object o = XposedHelpers.getObjectField(obj, field);
            return o instanceof String ? (String) o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Integer readInt(Object obj, String field) {
        try {
            Object o = XposedHelpers.getObjectField(obj, field);
            if (o instanceof Number) {
                return ((Number) o).intValue();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Long readLong(Object obj, String field) {
        try {
            Object o = XposedHelpers.getObjectField(obj, field);
            if (o instanceof Number) {
                return ((Number) o).longValue();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
