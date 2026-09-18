package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;
import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;

import android.content.Context;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 批量邀请进群: 单聊窗口右上角三点菜单新增「批量邀请进群」,
 * 将当前聊天好友邀请进"我加入且 TA 不在"的多个微信群。
 *
 * 严格参照 《微信好友批量邀请进群_完整分析及Java实现.md》:
 *   我的群   = ContactStorage(j4).r() 本地查询, username 以 @chatroom 结尾
 *   共同群   = fts.e0.xj(2, u73.u) 回调 t73.x.n4 -> List<u73.y>(y.e=群username)
 *   批量邀请 = uf0.e.bj(群名) -> pe5.f.j(群名,成员List,邀请原因,null) -> qn.m
 *             NetSceneAddChatRoomMember, CGI: /cgi-bin/micromsg-bin/addchatroommember
 *   过滤规则 = 可邀请群 = 我的群 - 共同群 - 本地成员缓存兜底
 */
public class BatchInviteGroupsHook {

    private static final String TAG = "BatchInviteGroups";
    private static final int MENU_ID_INVITE = 99982;
    private static final long INVITE_SLEEP_BASE_MS = 1500;
    private static final long INVITE_SLEEP_RAND_MAX = 2000;

    private static ClassLoader sCL;
    private static volatile boolean sEnabled = true;

    private static final class WxCls {
        static final String N0 = "ph5.n0";
        static final String J1 = "gp0.j1";
        static final String H9 = "b41.h9";
        static final String TN3_C4 = "tn3.c4";
        static final String H2_IMPL = "com.tencent.mm.plugin.messenger.foundation.h2";
        static final String Q02_F = "q02.f";
        static final String P02_A = "p02.a";
        static final String T73_Z = "t73.z";
        static final String T73_X = "t73.x";
        static final String U73_U = "u73.u";
        static final String U73_V = "u73.v";
        static final String U73_Y = "u73.y";
        static final String UF0_E = "uf0.e";
        static final String PE5_F = "pe5.f";
        static final String QN_M = "qn.m";
        static final String U0 = "com.tencent.mm.modelbase.u0";
    }

    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static void hook(ClassLoader cl) {
        if (!sEnabled) return;
        sCL = cl;
        ModuleConfig config = ModuleConfig.load(ContextManager.getPrefs());
        if (config == null || !config.batchInviteGroupsEnabled) return;
        hookChatMenu(cl);
    }

    private static void hookChatMenu(ClassLoader cl) {
        try {
            Class<?> chattingUI = VersionCompat.findChattingUIClass(cl);
            if (chattingUI == null) {
                for (String cn : new String[]{
                    "com.tencent.mm.ui.chatting.ChattingUI",
                    "com.tencent.mm.ui.chatting.BaseChattingUI",
                    "com.tencent.mm.ui.chatting.v2.ChattingUI",
                    "com.tencent.mm.ui.chatting.ChattingUIFragment",
                    "com.tencent.mm.ui.chatting.v2.ChattingUIFragment"
                }) {
                    try { chattingUI = cl.loadClass(cn); break; } catch (Throwable ignored) {}
                }
            }
            if (chattingUI == null) {
                LogWriter.log(TAG, "chat menu hook err: ChattingUI not found");
                return;
            }

            XposedBridge_hookAllMethods(chattingUI, "onCreateOptionsMenu", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Menu menu = (Menu) param.args[0];
                        if (menu == null) return;
                        final Object self = param.thisObject;
                        if (self == null) return;
                        String talker = getTalker(self);
                        // 仅单聊(非 @chatroom / @im 前缀)展示; 群聊顶部三点是群设置，不注入
                        if (talker == null || talker.isEmpty() || talker.endsWith("@chatroom")) {
                            LogWriter.log(TAG, "skip invite menu for talker=" + talker);
                            return;
                        }
                        final Object frag = self;
                        MenuItem mi = menu.add(0, MENU_ID_INVITE, 0, "批量邀请进群");
                        // 8.0.78: 三点菜单点击不走 onOptionsItemSelected/onMenuItemSelected,
                        // 用 MenuItem.setOnMenuItemClickListener 直接绑定点击(公共API, 不依赖具体实现)
                        try {
                            mi.setOnMenuItemClickListener(new android.view.MenuItem.OnMenuItemClickListener() {
                                @Override public boolean onMenuItemClick(MenuItem item) {
                                    new Thread(() -> onInviteSelected(frag), "InviteGroups").start();
                                    return true;
                                }
                            });
                        } catch (Throwable e) {
                            LogWriter.log(TAG, "setFromOnMenuItemClickListener err: " + e.getMessage());
                        }
                        LogWriter.log(TAG, "invite menu added for talker=" + talker);
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "menu cb err: " + e.getMessage());
                    }
                }
            });

            XposedBridge_hookAllMethods(chattingUI, "onOptionsItemSelected", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length == 0) return;
                        Object last = param.args[param.args.length - 1];
                        if (!(last instanceof MenuItem)) return;
                        MenuItem item = (MenuItem) last;
                        if (item.getItemId() == MENU_ID_INVITE) {
                            String talker = getTalker(param.thisObject);
                            LogWriter.log(TAG, "invite selected talker=" + talker);
                            if (talker == null || talker.isEmpty()) {
                                showToastAsync("无法获取当前聊天对象");
                            } else {
                                final String t = talker;
                                new Thread(() -> doBatchInvite(t), "InviteGroups").start();
                            }
                            param.setResult(true);
                        }
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "onOptionsItemSelected cb err: " + e.getMessage());
                    }
                }
            });

            XposedBridge_hookAllMethods(chattingUI, "onMenuItemSelected", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length == 0) return;
                        Object last = param.args[param.args.length - 1];
                        if (!(last instanceof MenuItem)) return;
                        MenuItem item = (MenuItem) last;
                        if (item.getItemId() == MENU_ID_INVITE) {
                            LogWriter.log(TAG, "invite selected via onMenuItemSelected");
                            String talker = getTalker(param.thisObject);
                            if (talker == null || talker.isEmpty()) {
                                showToastAsync("无法获取当前聊天对象");
                            } else {
                                final String t = talker;
                                new Thread(() -> doBatchInvite(t), "InviteGroups").start();
                            }
                            param.setResult(true);
                        }
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "onMenuItemSelected cb err: " + e.getMessage());
                    }
                }
            });

            LogWriter.log(TAG, "chat menu hook OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "chat menu hook err: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
    }

    /** onCreateOptionsMenu 菜单项点击 / 兜底 onOptionsItemSelected 共用入口 */
    private static void onInviteSelected(final Object fragment) {
        try {
            String talker = getTalker(fragment);
            LogWriter.log(TAG, "invite selected talker=" + talker);
            if (talker == null || talker.isEmpty()) {
                showToastAsync("无法获取当前聊天对象");
            } else {
                final String t = talker;
                doBatchInvite(t);
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "onInviteSelected err: " + e.getMessage());
        }
    }

    /** R8 编译环境不可用 varargs findAndHookMethod, 统一走 hookAllMethods 全量匹配 */
    private static void XposedBridge_hookAllMethods(Class<?> cls, String method,
            XC_MethodHook h) {
        try {
            de.robv.android.xposed.XposedBridge.hookAllMethods(cls, method, h);
        } catch (Throwable e) {
            LogWriter.log(TAG, "hook " + method + " err: " + e.getMessage());
        }
    }

    /** 参考 MsgExport.getTalker: 从 ChattingUIFragment 取当前聊天对象 */
    private static String getTalker(Object fragment) {
        try {
            Object args = XposedHelpers.callMethod(fragment, "getArguments");
            if (args instanceof android.os.Bundle) {
                String s = ((android.os.Bundle) args).getString("Chat_User");
                if (s != null && !s.isEmpty()) return s;
            }
        } catch (Throwable ignored) {}

        for (String fieldName : new String[]{"mFooter", "talker", "mTalker", "username", "mUsername"}) {
            try {
                Object val = XposedHelpers.getObjectField(fragment, fieldName);
                if (val instanceof String && !((String) val).isEmpty()) return (String) val;
            } catch (Throwable ignored) {}
        }

        try {
            Object val = XposedHelpers.callMethod(fragment, "getTalkerUserName");
            if (val instanceof String && !((String) val).isEmpty()) return (String) val;
        } catch (Throwable ignored) {}
        return null;
    }

    /* ================= 批量邀请核心 ================= */

    private static void doBatchInvite(final String friend) {
        try {
            showToastAsync("正在查询群列表...");
            List<Room> mine = getMyChatrooms();
            long t0 = System.currentTimeMillis();
            Set<String> common = getCommonChatrooms(friend);
            LogWriter.log(TAG, "my=" + mine.size() + " common=" + common.size()
                    + " (" + (System.currentTimeMillis() - t0) + "ms) friend=" + friend);
            List<Room> invitable = filterInvitable(mine, common, friend);
            LogWriter.log(TAG, "invitable=" + invitable.size());
            if (invitable.isEmpty()) {
                showToastAsync("没有可邀请的群(好友已在全部群中)");
                return;
            }
            showGroupPickDialog(friend, invitable);
        } catch (Throwable t) {
            LogWriter.log(TAG, "doBatchInvite err: " + t.getMessage());
            showToastAsync("批量邀请出错: " + t.getClass().getSimpleName());
        }
    }

    /** 弹窗展示可邀请群列表(带勾选), 确认后逐群发送邀请 */
    private static void showGroupPickDialog(final String friend, final List<Room> invitable) {
        try {
            final boolean[] checked = new boolean[invitable.size()];
            for (int i = 0; i < checked.length; i++) checked[i] = true;
            final String[] names = new String[invitable.size()];
            for (int i = 0; i < invitable.size(); i++) {
                Room r = invitable.get(i);
                names[i] = r.name == null || r.name.isEmpty() ? r.username : r.name;
            }

            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Context ctx = ContextManager.getAppContext();
                    android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(ctx)
                            .setTitle("选择要邀请的群 (" + invitable.size() + " 个)")
                            .setMultiChoiceItems(names, checked, (d, which, isChecked) ->
                                    checked[which] = isChecked)
                            .setPositiveButton("全选", (d, w) -> {
                                for (int i = 0; i < checked.length; i++) checked[i] = true;
                            })
                            .setNegativeButton("开始邀请", (d, w) -> {
                                List<Room> selected = new ArrayList<>();
                                for (int i = 0; i < checked.length; i++) {
                                    if (checked[i]) selected.add(invitable.get(i));
                                }
                                if (selected.isEmpty()) {
                                    showToastAsync("未选择任何群");
                                    return;
                                }
                                final List<Room> targets = selected;
                                showToastAsync("将邀请 " + friend + " 进入 " + targets.size() + " 个群...");
                                new Thread(() -> runBatchInvite(friend, targets), "InviteGroups").start();
                            })
                            .create();
                    dialog.show();
                } catch (Throwable e) {
                    LogWriter.log(TAG, "showGroupPickDialog err: " + e.getMessage());
                    showToastAsync("弹窗失败, 改为全部邀请");
                    new Thread(() -> runBatchInvite(friend, invitable), "InviteGroups").start();
                }
            });
        } catch (Throwable e) {
            LogWriter.log(TAG, "showGroupPickDialog outer err: " + e.getMessage());
            showToastAsync("弹窗失败, 改为全部邀请");
            new Thread(() -> runBatchInvite(friend, invitable), "InviteGroups").start();
        }
    }

    private static void runBatchInvite(final String friend, final List<Room> invitable) {
        try {
            Object netSceneMgr = null;
            try {
                netSceneMgr = getNetSceneManager();
            } catch (Throwable e) {
                LogWriter.log(TAG, "getNetSceneManager err: " + e.getMessage());
            }

            int success = 0, fail = 0;
            for (Room r : invitable) {
                boolean ok;
                if (netSceneMgr != null) {
                    ok = inviteViaNetScene(netSceneMgr, r.username, friend);
                } else {
                    ok = inviteViaRoomSvc(r.username, friend);
                }
                LogWriter.log(TAG, "invite -> " + r.username + " ok=" + ok);
                if (ok) success++; else fail++;
                try {
                    long sleep = INVITE_SLEEP_BASE_MS
                            + (long) (Math.random() * INVITE_SLEEP_RAND_MAX);
                    Thread.sleep(sleep);
                } catch (InterruptedException ignored) {}
            }
            showToastAsync("批量邀请完成: 成功 " + success + " 失败 " + fail
                    + "; " + friend);
        } catch (Throwable t) {
            LogWriter.log(TAG, "runBatchInvite err: " + t.getMessage());
            showToastAsync("批量邀请出错: " + t.getClass().getSimpleName());
        }
    }

    private static final class Room {
        String username;
        String name;
        @Override public String toString() { return name + "|" + username; }
    }

    /** ① 我的群列表: j1.v(tn3.c4) -> cj() -> j4, r() 游标 */
    private static List<Room> getMyChatrooms() throws Throwable {
        List<Room> out = new ArrayList<>();
        Object c4 = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass(WxCls.J1, sCL), "v",
                XposedHelpers.findClass(WxCls.TN3_C4, sCL));
        Object h2 = XposedHelpers.findClass(WxCls.H2_IMPL, sCL).cast(c4);
        Object contactStorage = XposedHelpers.callMethod(h2, "cj");
        Cursor cur = (Cursor) XposedHelpers.callMethod(contactStorage, "r");
        if (cur == null) {
            LogWriter.log(TAG, "getMyChatrooms: cursor null");
            return out;
        }
        try {
            int idxUser = cur.getColumnIndex("username");
            int idxRemark = cur.getColumnIndex("conRemark");
            int idxNick = cur.getColumnIndex("nickname");
            while (cur.moveToNext()) {
                String u = cur.getString(idxUser);
                if (u == null || !u.endsWith("@chatroom")) continue;
                Room room = new Room();
                room.username = u;
                String remark = idxRemark >= 0 ? cur.getString(idxRemark) : null;
                String nick = idxNick >= 0 ? cur.getString(idxNick) : null;
                room.name = (remark != null && remark.length() > 0)
                        ? remark : (nick != null ? nick : u);
                out.add(room);
            }
        } finally {
            try { cur.close(); } catch (Throwable ignored) {}
        }
        return out;
    }

    /** ② 共同群: fts.e0.xj(2, u73.u) 动态回调 t73.x.n4 -> 解析 u73.v.e */
    private static Set<String> getCommonChatrooms(String friend) throws Throwable {
        final Object lock = new Object();
        final Set<String> out = new HashSet<>();

        Object ftsService;
        try {
            Class<?> iface = XposedHelpers.findClass(WxCls.T73_Z, sCL);
            ftsService = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(WxCls.N0, sCL), "c", iface);
        } catch (Throwable e) {
            LogWriter.log(TAG, "getFtsService err: " + e.getMessage());
            return out;
        }
        LogWriter.log(TAG, "ftsService=" + (ftsService == null ? "null" : ftsService.getClass().getName()));
        if (ftsService == null) return out;

        try {
            Object req = XposedHelpers.newInstance(XposedHelpers.findClass(WxCls.U73_U, sCL));
            XposedHelpers.setObjectField(req, "c", friend);
            XposedHelpers.setIntField(req, "b", 6);
            // u73.u.p 字段类型是 com.tencent.mm.sdk.platformtools.q3 (微信 Handler 封装),
            // 直接塞 android.os.Handler 会抛字段类型不匹配, 需反射创建 q3 实例
            Object q3Handler = createQ3Handler();
            if (q3Handler != null) {
                XposedHelpers.setObjectField(req, "p", q3Handler);
            } else {
                LogWriter.log(TAG, "getCommonChatrooms: q3 handler unavailable, p left default");
            }

            Class<?> cbIface = XposedHelpers.findClass(WxCls.T73_X, sCL);
            Object callback = Proxy.newProxyInstance(sCL, new Class<?>[]{cbIface},
                    (proxy, method, args) -> {
                        if (method.getName().equals("n4") && args != null && args.length == 1) {
                            synchronized (lock) {
                                out.addAll(readCommonGroupsFromV(args[0]));
                                lock.notifyAll();
                                LogWriter.log(TAG, "common groups callback <- " + out.size());
                            }
                        }
                        return null;
                    });
            XposedHelpers.setObjectField(req, "o", callback);

            XposedHelpers.callMethod(ftsService, "xj", 2, req);
            synchronized (lock) {
                lock.wait(5000);
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "getCommonChatrooms err: " + e.getMessage());
        }
        return out;
    }

    /** 反射创建 com.tencent.mm.sdk.platformtools.q3 (微信 Handler 封装) 实例。
     *  多级兜底: 空构造 -> (Looper) -> (Looper, Callback) -> (Handler); 全失败返回 null。 */
    private static Object createQ3Handler() {
        try {
            Class<?> q3 = XposedHelpers.findClass("com.tencent.mm.sdk.platformtools.q3", sCL);
            Throwable last = null;
            try {
                return XposedHelpers.newInstance(q3);
            } catch (Throwable t) { last = t; }
            try {
                return XposedHelpers.newInstance(q3, Looper.getMainLooper());
            } catch (Throwable t) { last = t; }
            try {
                return XposedHelpers.newInstance(q3, Looper.getMainLooper(), null);
            } catch (Throwable t) { last = t; }
            try {
                return XposedHelpers.newInstance(q3, new Handler(Looper.getMainLooper()));
            } catch (Throwable t) { last = t; }
            LogWriter.log(TAG, "createQ3Handler: all constructors failed: "
                    + (last == null ? "?" : last.getMessage()));
            return null;
        } catch (Throwable e) {
            LogWriter.log(TAG, "createQ3Handler err: " + e.getMessage());
            return null;
        }
    }

    /** getType() 安全取值: 部分版本返回 null, 直接 (int) 拆箱抛 NPE */
    private static int safeSceneType(Object scene) {
        try {
            Object t = XposedHelpers.callMethod(scene, "getType");
            if (t instanceof Integer) return (Integer) t;
            if (t instanceof Number) return ((Number) t).intValue();
        } catch (Throwable ignored) {}
        return 36; // qn.m = NetSceneAddChatRoomMember, type=36 (文档实证)
    }

    @SuppressWarnings("unchecked")
    private static Set<String> readCommonGroupsFromV(Object v) {
        Set<String> out = new HashSet<>();
        try {
            int errCode = XposedHelpers.getIntField(v, "c");
            if (errCode != 0) return out;
            Object list = XposedHelpers.getObjectField(v, "e");
            if (list instanceof List) {
                for (Object y : (List<Object>) list) {
                    String user = (String) XposedHelpers.getObjectField(y, "e");
                    if (user != null) out.add(user);
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "readCommonGroupsFromV err: " + e.getMessage());
        }
        return out;
    }

    /** ③ 过滤: 可邀请 = 我的群 - 共同群 - 本地成员缓存(兜底) */
    private static List<Room> filterInvitable(List<Room> mine, Set<String> common,
            String friend) {
        List<Room> out = new ArrayList<>();
        for (Room r : mine) {
            if (common.contains(r.username)) continue;
            if (isFriendInRoomLocal(r.username, friend)) continue;
            out.add(r);
        }
        return out;
    }

    private static boolean isFriendInRoomLocal(String roomName, String friend) {
        try {
            Object srv = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(WxCls.J1, sCL), "v",
                    XposedHelpers.findClass(WxCls.Q02_F, sCL));
            Object p02 = XposedHelpers.findClass(WxCls.P02_A, sCL).cast(srv);
            Object roomStorage = XposedHelpers.callMethod(p02, "a");
            Object room = XposedHelpers.callMethod(roomStorage, "N1", roomName);
            if (room == null) return false;
            List<?> members = (List<?>) XposedHelpers.callMethod(room, "z0");
            return members != null && members.contains(friend);
        } catch (Throwable t) {
            LogWriter.log(TAG, "isFriendInRoomLocal err: " + t.getMessage());
            return false;
        }
    }

    /** ④-a. 直连网络场景: new qn.m(room, [friend], "", null) + r1 派发 */
    private static boolean inviteViaNetScene(final Object netSceneMgr, final String room,
            final String friend) {
        try {
            Object scene = XposedHelpers.newInstance(
                    XposedHelpers.findClass(WxCls.QN_M, sCL),
                    room, Collections.singletonList(friend), "", null);
            // getType() 在部分版本返回 null, 直接 (int) 拆箱抛 NPE
            int type = safeSceneType(scene);
            final boolean[] done = {false};
            final boolean[] ok = {false};
            final Object lock = new Object();

            Class<?> u0 = XposedHelpers.findClass(WxCls.U0, sCL);
            Object cb = Proxy.newProxyInstance(sCL, new Class<?>[]{u0},
                    (proxy, method, args) -> {
                        if (method.getName().equals("onSceneEnd") && args != null
                                && args.length >= 3) {
                            synchronized (lock) {
                                int errType = args[0] instanceof Integer ? (Integer) args[0] : 0;
                                int errCode = args[1] instanceof Integer ? (Integer) args[1] : 0;
                                String errMsg = args[2] == null ? null : String.valueOf(args[2]);
                                LogWriter.log(TAG, "invite onSceneEnd room=" + room
                                        + " errType=" + errType + " errCode=" + errCode
                                        + " msg=" + errMsg);
                                ok[0] = (errType == 0 && errCode == 0);
                                done[0] = true;
                                lock.notifyAll();
                            }
                        }
                        return null;
                    });
            XposedHelpers.callMethod(netSceneMgr, "a", type, cb);
            XposedHelpers.callMethod(netSceneMgr, "g", scene);
            synchronized (lock) {
                if (!done[0]) lock.wait(8000);
            }
            return ok[0];
        } catch (Throwable e) {
            LogWriter.log(TAG, "inviteViaNetScene err: " + e.getMessage());
            return false;
        }
    }

    /** ④-b. 群操作服务路线: uf0.e.bj(群名) -> pe5.f.j(群名,List,原因,null) */
    private static boolean inviteViaRoomSvc(String room, String friend) {
        try {
            Object uf0e = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(WxCls.N0, sCL), "c",
                    XposedHelpers.findClass(WxCls.UF0_E, sCL));
            Object roomSvc = XposedHelpers.callMethod(
                    XposedHelpers.findClass(WxCls.PE5_F, sCL).cast(uf0e), "bj", room);
            Object ret = XposedHelpers.callMethod(
                    XposedHelpers.findClass(WxCls.PE5_F, sCL).cast(roomSvc), "j",
                    room, Collections.singletonList(friend), "", null);
            LogWriter.log(TAG, "inviteViaRoomSvc room=" + room + " ret=" + ret);
            return true;
        } catch (Throwable e) {
            LogWriter.log(TAG, "inviteViaRoomSvc err: " + e.getMessage()
                    + " -> fallback maybe unavailable");
            return false;
        }
    }

    private static Object getNetSceneManager() throws Throwable {
        Class<?> h9 = XposedHelpers.findClass(WxCls.H9, sCL);
        return XposedHelpers.callStaticMethod(h9, "e");
    }

    private static void showToastAsync(final String msg) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                android.widget.Toast.makeText(ContextManager.getAppContext(),
                        msg, android.widget.Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {}
        });
    }
}