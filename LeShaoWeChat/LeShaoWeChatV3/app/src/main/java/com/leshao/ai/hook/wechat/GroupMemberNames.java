package com.leshao.ai.hook.wechat;

import com.leshao.ai.hook.HookEntry;
import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.VersionCompat;

import de.robv.android.xposed.XposedHelpers;

/**
 * 群成员群昵称解析（文档《艾特和引用方法》§四）。
 * <p>
 * 实证链路：
 * <pre>
 *   j1 = gp0.j1;  svc = j1.v(q02.f)
 *   a3 = svc.a()                 // ChatroomMembersStorage(com.tencent.mm.storage.a3)
 *   roomInfo = a3.t1(chatroom)   // 群成员存储(z2)
 *   roomNick = roomInfo.x0(wxid) // 群昵称
 * </pre>
 * 失败回退 {@code b41.d2.t(roomInfo, wxid, false)}，再回退
 * {@link ContactQuery#displayName(String)}，最终回退 wxid。
 * <p>
 * @ 高亮必须与群昵称完全一致（文档 §五 提醒 7），故这里优先取 z2.x0。
 */
public final class GroupMemberNames {

    private static final String TAG = "LeshaoAI.GroupNames";

    // v3.0.565: 8.0.79 服务定位器由 gp0.j1 迁移到 pp0.i1（反编译实证 pp0.i1.v(Class)->up0.a）
    // v3.0.576: 8.0.79 群成员存储服务接口由 q02.f 迁移到 e12.a（e12.a.a() -> com.tencent.mm.storage.a3）；
    //           群成员存储 a3 按群名取 z2 的方法由 t1 改为 Q1/n2；管理员判定由 z2.E0 改为 z2.F0。
    private static final String[] J1_CLASSES = {"pp0.i1", "gp0.j1", "gp0.j2", "gp0.i1"};
    private static final String[] Q02F_CLASSES = {"e12.a", "q02.f", "q02.e", "q02.g"};
    private static final String[] ROOM_LOADERS = {"Q1", "n2", "t1"};
    private static final String[] D2_CLASSES = {"b41.d2", "b41.d3"};

    /** 第14章：缓存 L0/E0 Method，避免每次反射查找（提升性能）。 */
    private static volatile java.lang.reflect.Method sL0Method;
    private static volatile java.lang.reflect.Method sE0Method;

    private GroupMemberNames() {
    }

    /** 角色判定（《微信群角色头衔逆向分析报告》§3）：群主/管理员/成员。
     *  群主 = z2.L0(wxid)（field_roomowner 精确比对）；
     *  管理员 = z2.E0(wxid)（roomFlag & 2048，ChatRoomMemberData.f 第 11 位）。
     *  返回 null 表示链路不可用（调用方自行兜底）。 */
    public static String roleOf(String sender, String room) {
        if (room == null || sender == null || sender.isEmpty()) return null;
        Object storage = roomMemberStorage(room);
        if (storage == null) return null;
        try {
            java.lang.reflect.Method m = sL0Method;
            if (m == null) {
                for (java.lang.reflect.Method mm : storage.getClass().getDeclaredMethods()) {
                    if ("L0".equals(mm.getName()) && mm.getParameterTypes().length == 1) {
                        m = mm; sL0Method = mm; break;
                    }
                }
            }
            if (m != null) {
                m.setAccessible(true);
                if (Boolean.TRUE.equals(m.invoke(storage, sender))) return "群主";
            }
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Method m = sE0Method;
            if (m == null) {
                // v3.0.576: 8.0.79 管理员判定由 E0 改名为 F0（z2.F0：roomFlag & 2048）
                for (String mn : new String[]{"F0", "E0"}) {
                    for (java.lang.reflect.Method mm : storage.getClass().getDeclaredMethods()) {
                        if (mn.equals(mm.getName()) && mm.getParameterTypes().length == 1) {
                            m = mm;
                            sE0Method = mm;
                            break;
                        }
                    }
                    if (m != null) break;
                }
            }
            if (m != null) {
                m.setAccessible(true);
                if (Boolean.TRUE.equals(m.invoke(storage, sender))) return "管理员";
            }
        } catch (Throwable ignored) {}
        return "成员";
    }

    /** 取群昵称（群昵称 → 联系人显示名 → wxid）。 */
    public static String displayName(String chatroom, String wxid) {
        if (wxid == null || wxid.isEmpty()) {
            return wxid;
        }
        Object room = roomMemberStorage(chatroom);
        if (room != null) {
            try {
                Object n = XposedHelpers.callMethod(room, "x0", wxid);
                if (n instanceof String && !((String) n).isEmpty()) {
                    return (String) n;
                }
            } catch (Throwable ignored) {
            }
            // 统一显示名入口(优先备注/群昵称)
            for (String cn : D2_CLASSES) {
                try {
                    Class<?> d2 = XposedHelpers.findClass(cn, wechatClassLoader());
                    Object n = XposedHelpers.callStaticMethod(d2, "t", room, wxid, Boolean.FALSE);
                    if (n instanceof String && !((String) n).isEmpty()) {
                        return (String) n;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return ContactQuery.displayName(wxid);
    }

    /** 取群成员存储 z2 实例。 */
    private static Object loadRoom(Object storage, String chatroom) {
        if (storage == null || chatroom == null) return null;
        // v3.0.576: 8.0.79 a3 按群名取 z2 的方法为 Q1/n2（旧 t1 已删除）
        for (String mn : ROOM_LOADERS) {
            try {
                Object room = XposedHelpers.callMethod(storage, mn, chatroom);
                if (room != null) return room;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 取群成员存储 z2 实例。 */
    private static Object roomMemberStorage(String chatroom) {
        if (chatroom == null || chatroom.isEmpty()) {
            return null;
        }
        ClassLoader cl = wechatClassLoader();
        for (String j1n : J1_CLASSES) {
            Class<?> j1;
            try {
                j1 = XposedHelpers.findClass(j1n, cl);
            } catch (Throwable t) {
                continue;
            }
            for (String qn : Q02F_CLASSES) {
                Class<?> q02f;
                try {
                    q02f = XposedHelpers.findClass(qn, cl);
                } catch (Throwable t) {
                    continue;
                }
                try {
                    Object svc = XposedHelpers.callStaticMethod(j1, "v", q02f);
                    if (svc == null) {
                        continue;
                    }
                    Object a3 = XposedHelpers.callMethod(svc, "a");
                    Object room = loadRoom(a3, chatroom);
                    if (room != null) {
                        return room;
                    }
                } catch (Throwable ignored) {
                }
                // 文档 §四 的 .ce().a().t1() 变体
                try {
                    Object svc = XposedHelpers.callStaticMethod(j1, "v", q02f);
                    Object e8 = XposedHelpers.callMethod(svc, "ce");
                    Object core = XposedHelpers.callMethod(e8, "a");
                    Object room = loadRoom(core, chatroom);
                    if (room != null) {
                        return room;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** 微信真实 ClassLoader（优先 Tinker 真实 CL）。 */
    private static ClassLoader wechatClassLoader() {
        ClassLoader cl = HookEntry.appClassLoader;
        try {
            ClassLoader tk = VersionCompat.findTinkerClassLoader(cl);
            if (tk != null && !tk.getClass().getName().contains("Leshao")) {
                return tk;
            }
        } catch (Throwable ignored) {
        }
        return cl;
    }
}
