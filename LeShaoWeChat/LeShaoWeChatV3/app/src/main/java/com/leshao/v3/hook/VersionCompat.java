package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;
import java.lang.reflect.Method;
import de.robv.android.xposed.XposedHelpers;

public class VersionCompat {

    private static final String TAG = "VersionCompat";

    /**
     * 尝试多个候选类名, 返回第一个找到的
     */
    public static Class<?> findClassMulti(ClassLoader cl, String... names) {
        for (String name : names) {
            try { return XposedHelpers.findClass(name, cl); }
            catch (Throwable ignored) {}
        }
        return null;
    }

    // ==================== Storage ====================
    // y3 → y3/y4 联系人对象

    public static Class<?> findContactClass(ClassLoader cl) {
        return findClassMulti(cl, "com.tencent.mm.storage.y3", "com.tencent.mm.storage.y4",
            "com.tencent.mm.storage.a3", "com.tencent.mm.storage.z2");
    }

    public static String getContactUsername(Object contact) {
        for (String m : new String[]{"d1", "d0", "getUsername", "c1", "getWxid"}) {
            try { return (String) XposedHelpers.callMethod(contact, m); } catch (Throwable ignored) {}
        }
        return "";
    }

    public static String getContactNickname(Object contact) {
        for (String m : new String[]{"M0", "M1", "L0", "getNickname", "N0"}) {
            try { return (String) XposedHelpers.callMethod(contact, m); } catch (Throwable ignored) {}
        }
        return "";
    }

    public static String getContactRemark(Object contact) {
        for (String m : new String[]{"w0", "w1", "v0", "getRemark", "u0"}) {
            try { return (String) XposedHelpers.callMethod(contact, m); } catch (Throwable ignored) {}
        }
        return "";
    }

    public static int getContactAvatar(Object contact) {
        for (String m : new String[]{"R0", "R1", "Q0", "getShowHead"}) {
            try {
                Object r = XposedHelpers.callMethod(contact, m);
                if (r instanceof Integer) return (Integer) r;
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    // ==================== ModContact (np4) ====================

    public static Class<?> findModContactClass(ClassLoader cl) {
        return findClassMulti(cl, "a65.np4", "a59.np4", "a67.np4",
            "a65.mp4", "a65.nq4", "a65.op4");
    }

    public static Class<?> findEw5Class(ClassLoader cl) {
        return findClassMulti(cl, "a65.ew5", "a59.ew5", "a67.ew5",
            "a65.ew6", "a65.dw5", "a65.fw5");
    }

    public static Class<?> findVClass(ClassLoader cl) {
        return findClassMulti(cl, "com.tencent.mm.plugin.messenger.foundation.v",
            "com.tencent.mm.plugin.messenger.foundation.t",
            "com.tencent.mm.plugin.messenger.foundation.u");
    }

    public static String ew5ToString(Object ew5Obj, ClassLoader cl) {
        try {
            Class<?> j1Class = findClassMulti(cl, "a65.j1", "a59.j1", "a67.j1",
                "a65.i1", "a65.k1", "a65.h1");
            if (j1Class != null) {
                Method m = j1Class.getDeclaredMethod("g", Object.class);
                m.setAccessible(true);
                return (String) m.invoke(null, ew5Obj);
            }
        } catch (Throwable ignored) {}
        try {
            Object result = XposedHelpers.callMethod(ew5Obj, "toString");
            return result != null ? result.toString() : "";
        } catch (Throwable ignored2) {}
        return "";
    }

    // ==================== Database ====================

    public static Class<?> findDbOpenerClass(ClassLoader cl) {
        return findClassMulti(cl, "ka5.f", "ka5.e", "ka4.f", "ka6.f", "ka5.g");
    }

    public static Object openDatabase(Class<?> dbOpenerClass, String path, String password) {
        for (int flags : new int[]{0, 1}) {
            for (boolean b : new boolean[]{true, false}) {
                try {
                    return dbOpenerClass.getDeclaredMethod("s", String.class, String.class, int.class, boolean.class)
                        .invoke(null, path, password, flags, b);
                } catch (Throwable ignored) {}
            }
            try {
                return dbOpenerClass.getDeclaredMethod("r", String.class, String.class, int.class)
                    .invoke(null, path, password, flags);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static Class<?> findBaseDirClass(ClassLoader cl) {
        return findClassMulti(cl, "mp0.b", "mo0.b", "mq0.b",
            "mp0.a", "mp0.c", "np0.b");
    }

    public static String getBaseDir(ClassLoader cl, android.content.Context ctx) {
        Class<?> baseClass = findBaseDirClass(cl);
        if (baseClass != null) {
            for (String m : new String[]{"X", "Y", "W", "getDataDir", "a"}) {
                try {
                    Object r = XposedHelpers.callStaticMethod(baseClass, m);
                    if (r instanceof String && !((String) r).isEmpty()) return (String) r;
                } catch (Throwable ignored) {}
            }
        }
        return ctx.getFilesDir().getParentFile().getAbsolutePath() + "/";
    }

    public static Class<?> findDbHashClass(ClassLoader cl) {
        return findClassMulti(cl, "hm0.b0", "hm0.a0", "hm0.c0",
            "gm0.b0", "im0.b0", "hl0.b0");
    }

    public static String getDbHash(ClassLoader cl, int uin) {
        Class<?> hashClass = findDbHashClass(cl);
        if (hashClass != null) {
            for (String m : new String[]{"e", "f", "d", "a"}) {
                try {
                    Object r = hashClass.getDeclaredMethod(m, int.class).invoke(null, uin);
                    if (r instanceof String && !((String) r).isEmpty()) return (String) r;
                } catch (Throwable ignored) {}
            }
        }
        return md5("mm" + uin);
    }

    public static Class<?> findImeiClass(ClassLoader cl) {
        return findClassMulti(cl, "wo.w0", "wn.w0", "wp.w0",
            "wo.v0", "wo.x0", "vo.w0");
    }

    public static String getImei(ClassLoader cl) {
        Class<?> imeiClass = findImeiClass(cl);
        if (imeiClass != null) {
            for (String m : new String[]{"g", "f", "h", "e"}) {
                try { return (String) imeiClass.getDeclaredMethod(m, boolean.class).invoke(null, true); }
                catch (Throwable ignored) {}
            }
        }
        return "1234567890ABCDEF";
    }

    // ==================== Storage helpers ====================

    public static Class<?> findContactInfoUIClass(ClassLoader cl) {
        return findClassMulti(cl,
            "com.tencent.mm.plugin.profile.ui.ContactInfoUI",
            "com.tencent.mm.plugin.profile.ui.v2.ContactInfoUI",
            "com.tencent.mm.plugin.profile.ui.ContactWidgetUI");
    }

    public static Class<?> findLauncherUIClass(ClassLoader cl) {
        return findClassMulti(cl,
            "com.tencent.mm.ui.LauncherUI",
            "com.tencent.mm.ui.LauncherUIProxy");
    }

    public static Class<?> findChattingUIClass(ClassLoader cl) {
        return findClassMulti(cl,
            "com.tencent.mm.ui.chatting.ChattingUIFragment",
            "com.tencent.mm.ui.chatting.v2.ChattingUIFragment");
    }

    public static Class<?> findContactStorageClass(ClassLoader cl) {
        return findClassMulti(cl, "com.tencent.mm.storage.j4",
            "com.tencent.mm.storage.k4", "com.tencent.mm.storage.i4",
            "com.tencent.mm.storage.h4");
    }

    // ==================== AntiRecall ====================

    public static Class<?> findAntiRecallClass(ClassLoader cl) {
        return findClassMulti(cl, "af5.a", "af6.a", "af4.a", "af7.a",
            "ag5.a", "ae5.a", "af5.b");
    }

    public static Class<?> findAntiRecallProtoClass(ClassLoader cl) {
        return findClassMulti(cl, "e01.u", "e02.u", "e00.u", "e03.u",
            "e01.t", "e02.t", "e01.v", "e00.t");
    }

    // ==================== Message/Notification ====================

    public static Class<?> findMsgStorageClass(ClassLoader cl) {
        return findClassMulti(cl, "com.tencent.mm.storage.f9",
            "com.tencent.mm.storage.g9", "com.tencent.mm.storage.e9",
            "com.tencent.mm.storage.f8");
    }

    public static Class<?> findNotifyClass(ClassLoader cl) {
        return findClassMulti(cl, "com.tencent.mm.plugin.notification.c.c",
            "com.tencent.mm.plugin.notification.c.d",
            "com.tencent.mm.plugin.notification.c.b");
    }

    // ==================== MainSettings ====================

    public static Class<?> findMainSettingsClass(ClassLoader cl) {
        return findClassMulti(cl,
            "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI",
            "com.tencent.mm.plugin.setting.ui.setting.MainSettingsUI",
            "com.tencent.mm.plugin.setting.ui.MainSettingsUI");
    }

    // ==================== Storage/DB (short names) ====================

    public static Class<?> findMsgStorageShortClass(ClassLoader cl) {
        return findClassMulti(cl, "d9", "e9", "c9", "d8", "e8");
    }

    public static Class<?> findMsgInfoStorageClass(ClassLoader cl) {
        return findClassMulti(cl, "com.tencent.mm.storage.e9",
            "com.tencent.mm.storage.d9", "com.tencent.mm.storage.f9",
            "com.tencent.mm.storage.e8");
    }

    public static Class<?> findAdapterClass(ClassLoader cl) {
        return findClassMulti(cl, "nw1.t2", "nw1.s2", "nw1.u2",
            "nv1.t2", "nx1.t2");
    }

    // ==================== Voice/Media ====================

    public static Class<?> findVoiceMsgClass(ClassLoader cl) {
        return findClassMulti(cl, "e01.d9", "e02.d9", "e00.d9", "e03.d9",
            "e01.e9", "e01.d8");
    }

    public static Class<?> findVoiceStreamClass(ClassLoader cl) {
        return findClassMulti(cl, "b31.w", "b32.w", "b30.w", "b33.w",
            "b31.v", "b31.x");
    }

    public static Class<?> findVoicePlayerClass(ClassLoader cl) {
        return findClassMulti(cl, "y21.p0", "y22.p0", "y20.p0", "y23.p0",
            "y21.o0", "y21.q0");
    }

    public static Class<?> findPlayThreadClass(ClassLoader cl) {
        return findClassMulti(cl, "com.tencent.mm.sdk.platformtools.h1",
            "com.tencent.mm.sdk.platformtools.h2",
            "com.tencent.mm.sdk.platformtools.g1");
    }

    // ==================== AntiRecall/Events ====================

    public static Class<?> findBd0SClass(ClassLoader cl) {
        return findClassMulti(cl, "bd0.s", "be0.s", "bc0.s", "bd1.s",
            "bd0.t", "bd0.r");
    }

    // ==================== UI/Theme ====================

    public static Class<?> findGaClass(ClassLoader cl) {
        return findClassMulti(cl, "com.tencent.mm.ui.ga",
            "com.tencent.mm.ui.gb", "com.tencent.mm.ui.fa",
            "com.tencent.mm.ui.ha");
    }

    public static Class<?> findAppClass(ClassLoader cl) {
        return findClassMulti(cl, "com.tencent.mm.app.h3",
            "com.tencent.mm.app.g3", "com.tencent.mm.app.i3",
            "com.tencent.mm.app.h4");
    }

    // ==================== Group/SNS ====================

    public static Class<?> findStickySorterClass(ClassLoader cl) {
        return findClassMulti(cl, "tm2.v8", "tm2.u8", "tm2.w8",
            "tl2.v8", "tn2.v8");
    }

    public static Class<?> findContextMenuClass(ClassLoader cl) {
        return findClassMulti(cl, "ly3.k3", "ly3.j3", "ly3.l3",
            "lx3.k3", "ly3.k4");
    }

    // ==================== Misc ====================

    public static Class<?> findModelBaseClass(ClassLoader cl) {
        return findClassMulti(cl, "com.tencent.mm.modelbase.b",
            "com.tencent.mm.modelbase.c",
            "com.tencent.mm.modelbase.a");
    }

    public static Class<?> findKkKClass(ClassLoader cl) {
        return findClassMulti(cl, "kk.k", "kl.k", "kj.k",
            "kk.l", "kk.j");
    }

    // ==================== Contact get signature ====================

    public static String getContactSignature(Object contact) {
        for (String extraMethod : new String[]{"z0", "y0", "A0", "z1"}) {
            try {
                Object extra = XposedHelpers.callMethod(contact, extraMethod);
                if (extra != null) {
                    for (String sigMethod : new String[]{"getSignature", "signature", "a0", "A0"}) {
                        try {
                            Object result = XposedHelpers.callMethod(extra, sigMethod);
                            if (result instanceof String) return (String) result;
                        } catch (Throwable ignored) {}
                    }
                    break;
                }
            } catch (Throwable ignored) {}
        }
        return "";
    }

    // ==================== Utility ====================

    private static String md5(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }
}
