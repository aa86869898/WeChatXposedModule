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
                    onMessageInserted(param.args[0]);
                } catch (Throwable t) {
                    Log.w(TAG, "afterHookedMethod 异常: " + t);
                }
            }
        };

        if (e9 != null) {
            XposedBridge.hookAllMethods(f9, "Bb", callback);
            LogWriter.log(TAG, "已 hook " + f9.getName() + ".Bb (e9=" + e9.getName() + ")");
        } else {
            // e9 未定位时退化为按方法名 hook（Bb 单重载，args[0] 即 MsgInfo）
            XposedBridge.hookAllMethods(f9, "Bb", callback);
            LogWriter.log(TAG, "已 hook " + f9.getName() + ".Bb (e9 未定位，按名 hook)");
        }
    }

    /** 处理一条落库消息。 */
    private static void onMessageInserted(Object msg) {
        if (msg == null) {
            return;
        }
        // 字段名取数（文档 §19：实体字段名与 SQLite 列名绑定，永不被混淆）
        String talker = readString(msg, "field_talker");
        String content = readString(msg, "field_content");
        Integer type = readInt(msg, "field_type");
        Integer isSend = readInt(msg, "field_isSend");
        Long svrId = readLong(msg, "field_msgSvrId");
        Long createTime = readLong(msg, "field_createTime");
        String fromUser = readString(msg, "field_fromUsername");

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

        LogWriter.log(TAG, "收到文本 talker=" + talker + " svrId=" + svrId
                + " content='" + trunc(content) + "'");
        TriggerEngine.dispatch(talker, content, msg, svrId == null ? 0L : svrId,
                createTime == null ? 0L : createTime);
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
