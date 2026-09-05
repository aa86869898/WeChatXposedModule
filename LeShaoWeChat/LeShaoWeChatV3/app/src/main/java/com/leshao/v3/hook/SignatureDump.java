package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;
import com.leshao.v3.PathUtil;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 真机签名诊断工具。
 *
 * 枚举并 dump 目标微信类的全部方法/字段签名，用于确认当前微信版本的
 * 混淆类名、方法名、字段名、参数/返回值类型，供反编译比对。
 *
 * 输出文件: <leshao_v3>/signature_dump.txt
 * 关键节点同步写入 XposedBridge.log（LSPosed 日志可见）。
 *
 * 关闭方式: 将 ENABLED 改为 false。
 */
public class SignatureDump {

    private static final String TAG = "SigDump";
    public static volatile boolean ENABLED = true;

    private static BufferedWriter sWriter;

    public static void dump(final ClassLoader cl) {
        if (!ENABLED) return;
        Thread t = new Thread(() -> {
            try {
                openWriter();
                w("========== 微信签名诊断 (v745) ==========");

                // A. 消息收发
                dumpByClass(cl, "消息分发类 x9", VersionCompat.findMsgDispatchClass(cl));
                dumpByClass(cl, "消息信息类 e9", VersionCompat.findMsgInfoStorageClass(cl));
                dumpByClass(cl, "消息存储短名 d9", VersionCompat.findMsgStorageShortClass(cl));
                dumpByClass(cl, "消息存储 f9", VersionCompat.findMsgStorageClass(cl));

                // B. 通话
                dumpByName(cl, "com.tencent.mm.plugin.voip.widget.k");
                dumpByName(cl, "com.tencent.mm.plugin.voip.model.c0");
                dumpByName(cl, "com.tencent.mm.autogen.events.CheckVoipCSIsStartedEvent");
                dumpByName(cl, "com.tencent.mm.plugin.voip.model.h2");   // 接听底层方法 a(boolean,boolean)
                dumpByName(cl, "com.tencent.mm.plugin.voip.model.d0");   // 接听入口 h/j/g0/D

                // C. 隐私
                dumpByName(cl, "com.tencent.mm.plugin.webview.ui.tools.WebViewUI");
                dumpByName(cl, "com.tencent.mm.booter.notification.m0");
                dumpByName(cl, "com.tencent.mm.plugin.appbrand.jsapi.JsApiSetClipboardDataWC");

                // D. 数据库 opener
                dumpByClass(cl, "数据库Opener ka5.f", VersionCompat.findDbOpenerClass(cl));

                // E. 防撤回
                dumpByClass(cl, "防撤回类 af5.a", VersionCompat.findAntiRecallClass(cl));
                dumpByClass(cl, "防撤回Proto e01.u", VersionCompat.findAntiRecallProtoClass(cl));

                // F. 转账/收款 + 朋友圈(用户反编译确认)
                dumpByName(cl, "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI"); // 确认收款 X6 / 拒绝 Y6
                dumpByName(cl, "com.tencent.mm.plugin.voip.model.n0");  // 转账 NetScene
                dumpByName(cl, "com.tencent.mm.plugin.sns.storage.n");  // ContentObj 字段 n..t / contentStyle e
                dumpByName(cl, "q84.w1.b");      // reportAdType 广告类型
                dumpByName(cl, "rs.i");          // getVideoAdViewType viewType 15 球形卡片
                dumpByName(cl, "e01.v1");        // 群成员同步 syncAddChatroomMember t(room,members,owner)
                dumpByName(cl, "a65.aj4");       // ContentObj 标量 protobuf 字段 d..r

                w("========== END ==========");
                closeWriter();
                LogWriter.log(TAG, "签名诊断完成 -> " + filePath());
            } catch (Throwable e) {
                LogWriter.log(TAG, "dump 异常: " + e.getClass().getSimpleName() + " " + e.getMessage());
                closeWriter();
            }
        }, "sig-dump-thread");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    // ==================== 类查找 ====================

    private static void dumpByName(ClassLoader cl, String className) {
        try {
            Class<?> c = cl.loadClass(className);
            dumpClass(c, 0);
        } catch (Throwable t) {
            w("<<< 类不存在: " + className + " (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ") >>>");
        }
    }

    private static void dumpByClass(ClassLoader cl, String label, Class<?> c) {
        if (c == null) {
            LogWriter.log(TAG, "未找到候选类(需反编译确认): " + label);
            w("<<< 未找到候选类: " + label + " >>>");
            return;
        }
        w(">>> 命中候选类: " + label + " -> " + c.getName());
        dumpClass(c, 0);
    }

    // ==================== 签名输出 ====================

    private static void dumpClass(Class<?> c, int depth) {
        if (c == null || depth > 4) return;
        w("");
        w("--- CLASS: " + c.getName()
                + "  super=" + (c.getSuperclass() != null ? c.getSuperclass().getName() : "null") + " ---");

        Method[] methods;
        try {
            methods = c.getDeclaredMethods();
        } catch (Throwable t) {
            methods = new Method[0];
        }
        for (Method m : methods) {
            StringBuilder sb = new StringBuilder();
            sb.append("  ").append(Modifier.isStatic(m.getModifiers()) ? "MS" : "M ")
              .append(" ").append(m.getName()).append("(");
            Class<?>[] pts = m.getParameterTypes();
            for (int i = 0; i < pts.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(fmt(pts[i]));
            }
            sb.append(") : ").append(fmt(m.getReturnType()));
            w(sb.toString());
        }

        Field[] fields;
        try {
            fields = c.getDeclaredFields();
        } catch (Throwable t) {
            fields = new Field[0];
        }
        for (Field f : fields) {
            w("  " + (Modifier.isStatic(f.getModifiers()) ? "FS" : "F ")
                    + " " + f.getName() + " : " + fmt(f.getType()));
        }

        // 内部类(如 autogen event 的 Data 类)
        Class<?>[] inners;
        try {
            inners = c.getDeclaredClasses();
        } catch (Throwable t) {
            inners = new Class<?>[0];
        }
        for (Class<?> ic : inners) {
            if (ic.getName().startsWith("java.") || ic.getName().startsWith("android.")) continue;
            dumpInnerClass(ic);
        }

        Class<?> sup = c.getSuperclass();
        if (sup != null
                && !sup.getName().startsWith("java.")
                && !sup.getName().startsWith("android.")) {
            dumpClass(sup, depth + 1);
        }
    }

    private static void dumpInnerClass(Class<?> ic) {
        w("  ~ INNER CLASS: " + ic.getName());
        Method[] methods;
        try { methods = ic.getDeclaredMethods(); } catch (Throwable t) { methods = new Method[0]; }
        for (Method m : methods) {
            StringBuilder sb = new StringBuilder();
            sb.append("    ").append(Modifier.isStatic(m.getModifiers()) ? "MS" : "M ")
              .append(" ").append(m.getName()).append("(");
            Class<?>[] pts = m.getParameterTypes();
            for (int i = 0; i < pts.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(fmt(pts[i]));
            }
            sb.append(") : ").append(fmt(m.getReturnType()));
            w(sb.toString());
        }
        Field[] fields;
        try { fields = ic.getDeclaredFields(); } catch (Throwable t) { fields = new Field[0]; }
        for (Field f : fields) {
            w("    " + (Modifier.isStatic(f.getModifiers()) ? "FS" : "F ")
                    + " " + f.getName() + " : " + fmt(f.getType()));
        }
    }

    private static String fmt(Class<?> c) {
        if (c == null) return "null";
        String n = c.getName();
        if (n.startsWith("java.") || n.startsWith("android.")) return c.getSimpleName();
        if (c.isArray()) {
            Class<?> comp = c.getComponentType();
            return fmt(comp) + "[]";
        }
        return n;
    }

    // ==================== 文件写入 ====================

    private static String filePath() {
        return new File(PathUtil.getLeshaoRootDir(), "signature_dump.txt").getAbsolutePath();
    }

    private static void openWriter() {
        try {
            File f = new File(PathUtil.getLeshaoRootDir(), "signature_dump.txt");
            if (f.getParentFile() != null && !f.getParentFile().exists()) f.getParentFile().mkdirs();
            sWriter = new BufferedWriter(new FileWriter(f, false), 8192);
        } catch (Throwable t) {
            LogWriter.log(TAG, "打开诊断文件失败: " + t.getMessage());
        }
    }

    private static void w(String line) {
        try {
            if (sWriter != null) {
                sWriter.write(line);
                sWriter.newLine();
            }
        } catch (Throwable ignored) {}
    }

    private static void closeWriter() {
        try {
            if (sWriter != null) { sWriter.flush(); sWriter.close(); }
        } catch (Throwable ignored) {}
        sWriter = null;
    }
}
