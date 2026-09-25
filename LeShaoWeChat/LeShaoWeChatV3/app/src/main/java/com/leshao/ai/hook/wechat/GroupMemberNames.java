package com.leshao.ai.hook.wechat;

import com.leshao.ai.hook.HookEntry;
import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.VersionCompat;

import de.robv.android.xposed.XposedHelpers;

/**
 * 群成员群昵称解析（文档《艾特和引用方法》§四）。
 * <p>
 * 实证链路（与已稳定的 {@code GroupMemberTools.getRoomInfo} 一致）：
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

    private static final String[] J1_CLASSES = {"gp0.j1", "gp0.j2", "gp0.i1"};
    private static final String[] Q02F_CLASSES = {"q02.f", "q02.e", "q02.g"};
    private static final String[] D2_CLASSES = {"b41.d2", "b41.d3"};

    private GroupMemberNames() {
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
                    Object room = XposedHelpers.callMethod(a3, "t1", chatroom);
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
                    Object room = XposedHelpers.callMethod(core, "t1", chatroom);
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
