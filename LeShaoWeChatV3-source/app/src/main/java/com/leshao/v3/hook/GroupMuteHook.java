package com.leshao.v3.hook;

import com.leshao.v3.ContextManager;
import com.leshao.v3.ContactRepository;
import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ContactCard;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedHelpers;

/**
 * 群聊批量免打扰 / 批量解除免打扰。
 *
 * <p>依据《微信_群聊批量免打扰_逆向分析.md》：
 * 免打扰本质 = 联系人 rcontact.type 打/清 bit 0x200(512)。
 * 走官方静态方法 b41.d2.s0(contact, needSync) 开免打扰、b41.d2.C0(contact, needSync) 解除，
 * 自动落库 + OpLog 同步 + 界面刷新。批量务必子线程执行，单项失败吞掉不中断整批。</p>
 */
public final class GroupMuteHook {

    private static final String TAG = "GroupMute";

    // 当前版本符号（换版本见文档"DexKit 字符串锚点"重新定位）
    private static final String C_MUTELOG  = "b41.d2";                              // ContactStorageLogic
    private static final String C_CTENTITY = "com.tencent.mm.storage.y3";          // MMContact 实体
    private static final String C_FACADE   = "tn3.c4";                              // IM 存储门面接口
    private static final String C_H2       = "com.tencent.mm.plugin.messenger.foundation.h2";

    private static volatile boolean sReady = false;
    private static Class<?> kMuteLog;
    private static Method mSetMute;
    private static Method mUnMute;

    private GroupMuteHook() {}

    /** 批量操作结果。 */
    public static final class MuteResult {
        public final int ok;
        public final int total;
        public final boolean ready;
        public MuteResult(int ok, int total, boolean ready) {
            this.ok = ok;
            this.total = total;
            this.ready = ready;
        }
    }

    /** 反射定位 b41.d2.s0 / C0，仅首次调用开销大。 */
    public static synchronized boolean ensureReady() {
        if (sReady) return true;
        try {
            ClassLoader cl = ContextManager.getClassLoader();
            if (cl == null) return false;
            ClassLoader tkCL = VersionCompat.findTinkerClassLoader(cl);
            if (tkCL != null) cl = tkCL;

            kMuteLog = XposedHelpers.findClass(C_MUTELOG, cl);
            Class<?> kEnt = XposedHelpers.findClass(C_CTENTITY, cl);
            mSetMute = findStaticMethod(kMuteLog, "s0", kEnt, boolean.class);
            mUnMute  = findStaticMethod(kMuteLog, "C0", kEnt, boolean.class);
            if (mSetMute == null || mUnMute == null) {
                LogWriter.log(TAG, "ensureReady: s0/C0 未定位");
                return false;
            }
            sReady = true;
            LogWriter.log(TAG, "ensureReady OK: " + kMuteLog.getName()
                    + " s0=" + mSetMute.getName() + " C0=" + mUnMute.getName());
            return true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "ensureReady err: " + t);
            return false;
        }
    }

    /**
     * 批量设置/解除全部群聊免打扰。
     *
     * @param mute true=开启免打扰，false=解除免打扰
     */
    public static MuteResult muteAllGroups(boolean mute) {
        if (!ensureReady()) return new MuteResult(0, 0, false);

        Object storage = contactStorage();
        List<String> groups = collectGroupUsernames(storage);
        if (groups.isEmpty()) {
            LogWriter.log(TAG, (mute ? "mute" : "unmute") + ": 无群聊可操作");
            return new MuteResult(0, 0, true);
        }

        int ok = 0;
        for (String u : groups) {
            if (applyMute(storage, u, mute)) ok++;
        }
        LogWriter.log(TAG, (mute ? "mute" : "unmute") + " done: ok=" + ok + "/" + groups.size());
        return new MuteResult(ok, groups.size(), true);
    }

    private static boolean applyMute(Object storage, String username, boolean mute) {
        try {
            Object contact = XposedHelpers.callMethod(storage, "n", username, true);
            if (contact == null) return false;
            Method m = mute ? mSetMute : mUnMute;
            m.invoke(null, contact, true);
            return true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "applyMute fail user=" + username + " err=" + t);
            return false;
        }
    }

    /** 枚举全部群聊 username（*@chatroom / *@im.chatroom）。 */
    private static List<String> collectGroupUsernames(Object storage) {
        List<String> result = new ArrayList<>();

        // 优先使用已加载的群聊列表（与 UI/通讯录一致）
        try {
            List<ContactCard> groups = ContactRepository.getGroups();
            if (groups != null && !groups.isEmpty()) {
                for (ContactCard c : groups) {
                    if (c.username != null
                            && (c.username.endsWith("@chatroom") || c.username.endsWith("@im.chatroom"))) {
                        result.add(c.username);
                    }
                }
                if (!result.isEmpty()) return result;
            }
        } catch (Throwable ignored) {}

        // 兜底：直接从联系人存储枚举 rcontact 游标
        try {
            if (storage == null) return result;
            Object cursor = XposedHelpers.callMethod(storage, "r");
            if (cursor == null) return result;
            try {
                int idx = (int) XposedHelpers.callMethod(cursor, "getColumnIndex", "username");
                while ((boolean) XposedHelpers.callMethod(cursor, "moveToNext")) {
                    if (idx < 0) break;
                    String u = (String) XposedHelpers.callMethod(cursor, "getString", idx);
                    if (u != null
                            && (u.endsWith("@chatroom") || u.endsWith("@im.chatroom"))) {
                        result.add(u);
                    }
                }
            } finally {
                try { XposedHelpers.callMethod(cursor, "close"); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "collectGroupUsernames cursor err: " + t);
        }
        return result;
    }

    /**
     * 获取联系人存储：j1.v(tn3.c4) -> (h2 实现).cj() -> com.tencent.mm.storage.j4。
     * 参考《微信_群聊批量免打扰_逆向分析.md》与 ContactRepository 链路。
     */
    private static Object contactStorage() {
        try {
            ClassLoader cl = ContextManager.getClassLoader();
            if (cl == null) return null;
            ClassLoader tkCL = VersionCompat.findTinkerClassLoader(cl);
            if (tkCL != null) cl = tkCL;

            Object svc = j1ServiceLookup(cl, C_FACADE, "tn3.d4");
            if (svc == null) return null;
            return XposedHelpers.callMethod(svc, "cj");
        } catch (Throwable t) {
            LogWriter.log(TAG, "contactStorage err: " + t);
            return null;
        }
    }

    /** j1 服务定位器查找：优先 DexKit 结果，回退 gp0.j1/fp0.j1，兼容 v(Class)/s(Class)。 */
    private static Object j1ServiceLookup(ClassLoader cl, String... keyClassNames) {
        String dexJ1 = DexKitHelper.getJ1ServiceClass();
        Class<?> j1 = null;
        for (String cand : new String[]{dexJ1, "gp0.j1", "fp0.j1"}) {
            if (cand == null || cand.isEmpty()) continue;
            try {
                j1 = XposedHelpers.findClass(cand, cl);
                break;
            } catch (Throwable ignored) {}
        }
        if (j1 == null) return null;

        for (String kn : keyClassNames) {
            if (kn == null) continue;
            Class<?> key;
            try {
                key = XposedHelpers.findClass(kn, cl);
            } catch (Throwable e) {
                continue;
            }
            if (key == null) continue;
            for (String mn : new String[]{"v", "s"}) {
                try {
                    Object r = XposedHelpers.callStaticMethod(j1, mn, key);
                    if (r != null) return r;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static Method findStaticMethod(Class<?> clazz, String name, Class<?>... params) {
        try {
            Method m = clazz.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            try {
                Method m = clazz.getMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (Throwable t) {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }
}