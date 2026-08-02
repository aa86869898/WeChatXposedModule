package com.leshao.v3.wm.utils;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.widget.ImageView;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.XposedHelpers;
import com.leshao.v3.LogWriter;

/**
 * 微信反射核心 — 复刻自微信大师 WxReflect
 * 全部经 smali/反编译验证：
 * pa5.n0.c(Class) | kl5.s5.qj(String,String)V
 * e01.v1.m(List)/B(bool) | e01.d9.b().q()
 * hm0.j1.s(Class)->a().H0(room)
 * d24.h.a() 踢人 | kn.x 邀请
 */
public class WmReflect {

    private static final String TAG = "WmReflect";

    // ===== 核心服务 =====
    public static Object getSendMsgMgr(ClassLoader cl) {
        try {
            return XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("pa5.n0", cl), "c",
                    XposedHelpers.findClass("kl5.s5", cl));
        } catch (Exception e) { return null; }
    }

    public static Class<?> getChatroomLogic(ClassLoader cl) {
        return XposedHelpers.findClass("e01.v1", cl);
    }

    public static Object getContactStorage(ClassLoader cl) {
        try {
            return XposedHelpers.callMethod(
                    XposedHelpers.callStaticMethod(XposedHelpers.findClass("e01.d9", cl), "b"), "q");
        } catch (Exception e) { return null; }
    }

    private static volatile Class<?> sChatroomSvcIface;

    public static Object getChatroomInfo(ClassLoader cl, String room) {
        if (cl == null || room == null) return null;
        if (sChatroomSvcIface == null) {
            sChatroomSvcIface = findChatroomSvcIface(cl);
        }
        if (sChatroomSvcIface == null) return null;
        try {
            Object svc = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("hm0.j1", cl), "s", sChatroomSvcIface);
            if (svc == null) {
                LogWriter.log(TAG, "getChatroomInfo: svc null after cache");
                return null;
            }
            Object inst = XposedHelpers.callMethod(svc, "a");
            if (inst == null) {
                LogWriter.log(TAG, "getChatroomInfo: inst null");
                return null;
            }
            return XposedHelpers.callMethod(inst, "H0", room);
        } catch (Exception e) {
            LogWriter.log(TAG, "getChatroomInfo err: " + e.getMessage());
            return null;
        }
    }

    private static Class<?> findChatroomSvcIface(ClassLoader cl) {
        String[] candidates = {"f", "e", "g", "h", "d", "c", "i", "j"};
        for (String name : candidates) {
            try {
                Class<?> iface = XposedHelpers.findClass(name, cl);
                Object svc = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("hm0.j1", cl), "s", iface);
                if (svc != null) {
                    Object inst = XposedHelpers.callMethod(svc, "a");
                    if (inst != null) {
                        LogWriter.log(TAG, "getChatroomInfo: found iface=" + name);
                        return iface;
                    }
                }
            } catch (Exception ignored) {}
        }
        LogWriter.log(TAG, "getChatroomInfo: all iface candidates failed");
        return null;
    }

    // ===== 消息 =====
    public static void sendTextMsg(ClassLoader cl, String content, String toUser) {
        Object m = getSendMsgMgr(cl);
        if (m == null) return;
        try { XposedHelpers.callMethod(m, "qj", content, toUser); } catch (Exception ignored) {}
    }

    public static void sendAppMsg(ClassLoader cl, String xmlContent, String toUser) {
        try {
            Class<?> nm = XposedHelpers.findClass("com.tencent.mm.modelmulti.n", cl);
            Object msg = XposedHelpers.newInstance(nm, toUser, xmlContent, 49, (Object) null);
            XposedHelpers.callStaticMethod(nm, "b", msg);
        } catch (Exception e) {
            LogWriter.log(TAG, "sendAppMsg err: " + e.getMessage());
        }
    }

    public static void broadcastRooms(ClassLoader cl, List<String> rooms, String content) {
        if (rooms == null || rooms.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rooms.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(rooms.get(i));
        }
        sendTextMsg(cl, content, sb.toString());
    }

    // ===== 成员 =====
    @SuppressWarnings("unchecked")
    public static List<String> getMemberList(ClassLoader cl, String room) {
        try {
            return (List<String>) XposedHelpers.callStaticMethod(getChatroomLogic(cl), "m", room);
        } catch (Exception e) { return new ArrayList<>(); }
    }

    public static boolean isChatRoom(ClassLoader cl, String name) {
        if (name == null) return false;
        if (name.endsWith("@chatroom") || name.endsWith("@im.chatroom")) return true;
        try {
            return (boolean) XposedHelpers.callStaticMethod(getChatroomLogic(cl), "B", name);
        } catch (Exception e) { return false; }
    }

    public static int getMemberCount(ClassLoader cl, String room) {
        Object i = getChatroomInfo(cl, room);
        if (i != null) {
            try { return XposedHelpers.getIntField(i, "field_memberCount"); } catch (Exception ignored) {}
        }
        // 兜底：成员列表数量
        List<String> ms = getMemberList(cl, room);
        return ms.size();
    }

    public static String getRoomOwner(ClassLoader cl, String room) {
        Object i = getChatroomInfo(cl, room);
        if (i != null) {
            try {
                String v = (String) XposedHelpers.getObjectField(i, "field_roomowner");
                if (v != null && !v.isEmpty()) return v;
            } catch (Exception ignored) {}
        }
        try {
            String[] info = com.leshao.v3.db.ContactRepository.queryGroupInfoFromDB(room);
            if (info != null && info[0] != null && !info[0].isEmpty()) return info[0];
        } catch (Throwable ignored) {}
        return "";
    }

    // ===== 联系人 =====
    public static Object getContact(ClassLoader cl, String username) {
        Object s = getContactStorage(cl);
        if (s == null) return null;
        try { return XposedHelpers.callMethod(s, "n", username, true); } catch (Exception e) { return null; }
    }

    public static String getWxid(Object c) {
        if (c == null) return "";
        try { return (String) XposedHelpers.getObjectField(c, "field_username"); } catch (Exception e) { return ""; }
    }

    public static String getAlias(Object c) {
        if (c == null) return "";
        try { return (String) XposedHelpers.getObjectField(c, "field_alias"); } catch (Exception e) { return ""; }
    }

    public static String getNickname(Object c) {
        if (c == null) return "";
        try { return (String) XposedHelpers.getObjectField(c, "field_nickname"); } catch (Exception e) { return ""; }
    }

    public static String getRemark(Object c) {
        if (c == null) return "";
        try { return (String) XposedHelpers.getObjectField(c, "field_conRemark"); } catch (Exception e) { return ""; }
    }

    // ===== 头像 =====
    public static String getAvatarUrl(ClassLoader cl, String wxid) {
        Object c = getContact(cl, wxid);
        if (c == null) return null;
        String[] fields = {"field_headImgUrl", "field_avatarUrl", "field_avatarBUrl",
                "field_headimgurl", "field_avatarurl", "field_avatar_full_url",
                "field_smallHeadImgUrl", "field_encryptUsername"};
        for (String f : fields) {
            try {
                String v = (String) XposedHelpers.getObjectField(c, f);
                if (v != null && !v.isEmpty()) return v;
            } catch (Exception ignored) {}
        }
        return null;
    }

    public static void loadAvatarInto(ClassLoader cl, ImageView iv, String wxid) {
        try {
            Class<?> a = XposedHelpers.findClass("com.tencent.mm.pluginsdk.ui.a", cl);
            XposedHelpers.callStaticMethod(a, "b", iv, wxid);
            return;
        } catch (Exception ignored) {}
        // Fallback: get avatar URL from contact and download
        String url = getAvatarUrl(cl, wxid);
        if (url == null || url.isEmpty()) return;
        final ImageView target = iv;
        new Thread(() -> {
            Bitmap bm = downloadAvatarUrl(url);
            if (bm != null && target != null) {
                target.post(() -> target.setImageBitmap(bm));
            }
        }).start();
    }

    private static Bitmap downloadAvatarUrl(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            InputStream is = conn.getInputStream();
            Bitmap bm = BitmapFactory.decodeStream(is);
            is.close();
            conn.disconnect();
            if (bm != null) return toRoundBitmap(bm);
        } catch (Exception ignored) {}
        return null;
    }

    private static Bitmap toRoundBitmap(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int s = Math.min(w, h);
        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.drawCircle(s / 2f, s / 2f, s / 2f, p);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        c.drawBitmap(src, (s - w) / 2f, (s - h) / 2f, p);
        src.recycle();
        return out;
    }

    // ===== 踢人(d24.h.a) =====
    public static boolean kickMember(ClassLoader cl, String room, String member) {
        try {
            if (!isChatRoom(cl, room)) return false;
            XposedHelpers.callStaticMethod(XposedHelpers.findClass("d24.h", cl), "a",
                    room, 1, 0, 0, 0, 0, System.currentTimeMillis(), "");
            return true;
        } catch (Exception e) { return false; }
    }

    // ===== 邀请(kn.x) =====
    @SuppressWarnings("unchecked")
    public static boolean inviteMembers(ClassLoader cl, String room, List<String> members) {
        try {
            if (!isChatRoom(cl, room)) return false;
            Class<?> knx = XposedHelpers.findClass("kn.x", cl);
            Object req = null;
            try {
                req = XposedHelpers.newInstance(knx, room, members, 0, (Object) null);
            } catch (Exception ignored) {}
            if (req == null) {
                try {
                    req = XposedHelpers.newInstance(knx, room, members, 0, "", (Object) null);
                } catch (Exception ignored) {}
            }
            if (req == null) return false;
            Object r1 = XposedHelpers.callStaticMethod(XposedHelpers.findClass("hm0.j1", cl), "d");
            if (r1 != null) {
                XposedHelpers.callMethod(r1, "d", req);
                return true;
            }
            return false;
        } catch (Exception e) { return false; }
    }

    // ===== 退群 =====
    // 退群操作需要 ChatroomInfoUI 实例上下文，静态调用不可靠，改为弹窗指引
    public static boolean quitRoom(ClassLoader cl, String room) { return false; }

    // ===== 上下文 =====
    /** 从聊天 intent 提取当前对象，尝试多个 key（兼容不同微信版本） */
    public static String getCurrentChatUser(Intent intent) {
        if (intent == null) return null;
        String[] keys = {
                "Chat_User", "Chatroom_Name", "contact_username", "username",
                "Openim_User", "Contact_User", "Chat_User_To", "talker", "Talker"
        };
        for (String k : keys) {
            String v = intent.getStringExtra(k);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    /** 从 ChattingUIFragment 实例读取当前聊天对象字段（String 类型字段中匹配 wxid/@chatroom） */
    public static String getChatUserFromFragment(Object fragment) {
        if (fragment == null) return null;
        try {
            for (java.lang.reflect.Field f : fragment.getClass().getDeclaredFields()) {
                if (f.getType() != String.class) continue;
                f.setAccessible(true);
                Object v = f.get(fragment);
                if (v == null) continue;
                String s = (String) v;
                if (s.isEmpty()) continue;
                if (s.endsWith("@chatroom") || s.endsWith("@im.chatroom")
                        || s.startsWith("wxid_") || s.endsWith("@openim")
                        || s.endsWith("@qqim") || s.endsWith("@app")) {
                    return s;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static List<String> getAllChatRooms(ClassLoader cl) {
        List<String> rooms = new ArrayList<>();
        Object s = getContactStorage(cl);
        if (s == null) return rooms;
        try {
            Cursor c = (Cursor) XposedHelpers.callMethod(s, "D");
            if (c != null) {
                while (c.moveToNext()) {
                    String u = c.getString(c.getColumnIndex("username"));
                    if (u != null && (u.endsWith("@chatroom") || u.endsWith("@im.chatroom"))) {
                        rooms.add(u);
                    }
                }
                c.close();
            }
        } catch (Exception ignored) {}
        return rooms;
    }

    // ===== 群详情(dm.y1字段) =====
    public static String getRoomNotice(ClassLoader cl, String room) {
        Object i = getChatroomInfo(cl, room);
        if (i == null) return "";
        try { return (String) XposedHelpers.getObjectField(i, "field_chatroomnotice"); } catch (Exception e) { return ""; }
    }

    public static String getRoomNoticeEditor(ClassLoader cl, String room) {
        Object i = getChatroomInfo(cl, room);
        if (i == null) return "";
        try { return (String) XposedHelpers.getObjectField(i, "field_chatroomnoticeEditor"); } catch (Exception e) { return ""; }
    }

    public static long getRoomNoticePubTime(ClassLoader cl, String room) {
        Object i = getChatroomInfo(cl, room);
        if (i == null) return 0;
        try { return XposedHelpers.getLongField(i, "field_chatroomnoticePublishTime"); } catch (Exception e) { return 0; }
    }

    public static long getRoomCreateTime(ClassLoader cl, String room) {
        Object i = getChatroomInfo(cl, room);
        if (i == null) return 0;
        try { return XposedHelpers.getLongField(i, "field_addtime"); } catch (Exception e) { return 0; }
    }

    public static int getRoomStatus(ClassLoader cl, String room) {
        Object i = getChatroomInfo(cl, room);
        if (i == null) return -1;
        try { return XposedHelpers.getIntField(i, "field_chatroomStatus"); } catch (Exception e) { return -1; }
    }

    public static String getRoomDisplayName(ClassLoader cl, String room) {
        Object i = getChatroomInfo(cl, room);
        if (i != null) {
            try {
                String v = (String) XposedHelpers.getObjectField(i, "field_displayname");
                if (v != null && !v.isEmpty()) return v;
            } catch (Exception ignored) {}
        }
        try {
            String[] info = com.leshao.v3.db.ContactRepository.queryGroupInfoFromDB(room);
            if (info != null && info[2] != null && !info[2].isEmpty()) return info[2];
        } catch (Throwable ignored) {}
        try {
            String nick = com.leshao.v3.db.ContactRepository.queryNickFromDB(room);
            if (nick != null && !nick.isEmpty()) return nick;
        } catch (Throwable ignored) {}
        return "";
    }

    public static String getMyDisplayName(ClassLoader cl, String room) {
        Object i = getChatroomInfo(cl, room);
        if (i == null) return "";
        try { return (String) XposedHelpers.getObjectField(i, "field_selfDisplayName"); } catch (Exception e) { return ""; }
    }

    public static boolean isRoomDisbanded(ClassLoader cl, String room) {
        return getRoomStatus(cl, room) != 0;
    }
}
