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
 * 微信反射核心
 * 多类型群发2_新.md 实证:
 * 核心发送管理器 qs5.v5(MicroMsg.SendMsgMgr), 服务定位 ph5.n0.c(X.class)
 * 文本: mj/nj/oj/pj(toUser,content,type,flag) / hj(atStr,usersCsv,extra) 多群
 * 图片: b(Context,toUser,fileName,i,...,k7,d) sendImg
 * 视频: sj/tj(Context,toUser,file,thumb,i,i2,qn6,..) sendVedio
 * 名片: ej/fj(String,String,Z,yl) sendContactCard
 * AppMsg: dj(String,byte[],String,String,String,MsgIdTalker,String,Z,String) / cj 简版
 */
public class WmReflect {

    private static final String TAG = "WmReflect";

    // ===== 核心服务 =====
    public static Object getSendMsgMgr(ClassLoader cl) {
        try {
            // 8.0.78(3180): 发送管理器 = qs5.v5 (kl5.s5 已失效)。服务定位优先 ph5.n0.c(qs5.v5)
            // v955: 定位器首位改用 DexKit 动态检索结果(特征字符串 "MicroMsg.ServiceManager"),
            // 严禁硬编码类名作主查找; 下列候选仅作历史版本兜底。
            java.util.List<String> locatorList = new java.util.ArrayList<>();
            String dkLoc = com.leshao.v3.hook.DexKitHelper.getServiceLocatorClass();
            if (dkLoc != null && !dkLoc.isEmpty()) locatorList.add(dkLoc);
            for (String l : new String[]{"ph5.n0", "pa5.n0", "hm0.j1", "gp0.j1.j", "gp0.j1"}) {
                if (!locatorList.contains(l)) locatorList.add(l);
            }
            String[] locators = locatorList.toArray(new String[0]);
            String[] managers = {"qs5.v5", "kl5.s5"};
            for (String mgrName : managers) {
                for (String loc : locators) {
                    try {
                        Class<?> locCls = XposedHelpers.findClass(loc, cl);
                        Class<?> mgrCls = XposedHelpers.findClass(mgrName, cl);
                        Object inst = XposedHelpers.callStaticMethod(locCls, "c", mgrCls);
                        if (inst != null) {
                            LogWriter.log(TAG, "getSendMsgMgr OK: " + loc + ".c(" + mgrName + ") -> " + inst.getClass().getName());
                            return inst;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            LogWriter.log(TAG, "getSendMsgMgr FAILED: no qs5.v5/kl5.s5 via any locator");
            return null;
        } catch (Throwable t) {
            LogWriter.log(TAG, "getSendMsgMgr err: " + t.getMessage());
            return null;
        }
    }

    public static Class<?> getChatroomLogic(ClassLoader cl) {
        try {
            return XposedHelpers.findClass("e01.v1", cl);
        } catch (Throwable t) {
            // Try DexKit candidates
            String[] candidates = {"e01.v1", "e02.v1", "e00.v1", "e01.u1", "e01.w1"};
            for (String name : candidates) {
                try { return XposedHelpers.findClass(name, cl); } catch (Throwable ignored) {}
            }
            return null;
        }
    }

    public static Object getContactStorage(ClassLoader cl) {
        try {
            Class<?> contactCls = null;
            // Try DexKit-discovered contact storage
            String dexKitContact = com.leshao.v3.hook.DexKitHelper.getContactStorageClass();
            if (dexKitContact != null && !dexKitContact.isEmpty()) {
                try { contactCls = XposedHelpers.findClass(dexKitContact, cl); } catch (Throwable ignored) {}
            }
            if (contactCls == null) {
                String[] candidates = {"e01.d9", "sh3.c4", "e32.a"};
                for (String name : candidates) {
                    try { contactCls = XposedHelpers.findClass(name, cl); break; } catch (Throwable ignored) {}
                }
            }
            if (contactCls == null) return null;
            return XposedHelpers.callMethod(
                    XposedHelpers.callStaticMethod(contactCls, "b"), "q");
        } catch (Throwable e) { return null; }
    }

    private static volatile Class<?> sChatroomSvcIface;

    public static Object getChatroomInfo(ClassLoader cl, String room) {
        if (cl == null || room == null) return null;
        if (sChatroomSvcIface == null) {
            sChatroomSvcIface = findChatroomSvcIface(cl);
        }
        if (sChatroomSvcIface == null) return null;
        for (int retry = 0; retry < 3; retry++) {
            try {
                Object svc = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("hm0.j1", cl), "s", sChatroomSvcIface);
                if (svc == null) {
                    if (retry < 2) { try { Thread.sleep(500); } catch (InterruptedException ignored) {} continue; }
                    return null;
                }
                Object inst = XposedHelpers.callMethod(svc, "a");
                if (inst == null) {
                    if (retry < 2) { try { Thread.sleep(500); } catch (InterruptedException ignored) {} continue; }
                    return null;
                }
                return XposedHelpers.callMethod(inst, "H0", room);
            } catch (Throwable e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("Kernel not initialized")) {
                    if (retry < 2) { try { Thread.sleep(500); } catch (InterruptedException ignored) {} continue; }
                    return null;
                }
                if (retry >= 2) {
                    LogWriter.log(TAG, "getChatroomInfo err: " + e.getMessage());
                }
                return null;
            }
        }
        return null;
    }

    private static Class<?> findChatroomSvcIface(ClassLoader cl) {
        String[] candidates = {"cw1.f", "cw1.g", "cw2.f", "cw2.g"};
        for (String name : candidates) {
            try { return XposedHelpers.findClass(name, cl); } catch (Throwable ignored) {}
        }
        LogWriter.log(TAG, "findChatroomSvcIface: no chatroom iface found");
        return null;
    }

    // ===== 消息 =====
    public static boolean sendTextMsg(ClassLoader cl, String content, String toUser) {
        Object m = getSendMsgMgr(cl);
        if (m == null) {
            LogWriter.log(TAG, "sendTextMsg FAILED: sendMsgMgr null");
            return false;
        }
        // 8.0.78(3180): 文本走 qs5.v5 新框架 mj/nj/oj/pj(toUser,content,type,flag);
        // 旧 qj(content,toUser) 为相册名片, 不再用于文本。
        String[] textMethods = {"oj", "nj", "mj", "pj"};
        Throwable lastErr = null;
        for (String mn : textMethods) {
            try {
                // 尝试 (String,String,int,int) 签名
                XposedHelpers.callMethod(m, mn, toUser, content, 1, 0);
                LogWriter.log(TAG, "sendTextMsg ok via " + mn + "(toUser,content,1,0)");
                return true;
            } catch (Throwable t1) {
                lastErr = t1;
            }
            try {
                // 尝试 (String,String,int,int,int) 等变体
                XposedHelpers.callMethod(m, mn, toUser, content, 1, 0, 0);
                LogWriter.log(TAG, "sendTextMsg ok via " + mn + "(toUser,content,1,0,0)");
                return true;
            } catch (Throwable ignored) {}
        }
        try {
            // 多目标文本 hj(atStr, usersCsv, extra) 单目标亦可
            XposedHelpers.callMethod(m, "hj", (Object) null, toUser, (Object) null);
            LogWriter.log(TAG, "sendTextMsg ok via hj(null,toUser,null)");
            return true;
        } catch (Throwable t2) {
            lastErr = t2;
        }
        try {
            // 多目标 gj(str1,str2,str3,Z)
            XposedHelpers.callMethod(m, "gj", (Object) null, toUser, (Object) null, true);
            LogWriter.log(TAG, "sendTextMsg ok via gj(null,toUser,null,true)");
            return true;
        } catch (Throwable t3) {
            lastErr = t3;
        }
        LogWriter.log("WmReflect", "sendTextMsg FAILED: " + (lastErr != null ? lastErr.getMessage() : "no method"));
        return false;
    }

    public static void broadcastRooms(ClassLoader cl, List<String> rooms, String content) {
        if (rooms == null || rooms.isEmpty()) return;
        for (String room : rooms) {
            if (room == null || room.isEmpty()) continue;
            sendTextMsg(cl, content, room);
        }
    }

    // ===== 成员 =====
    @SuppressWarnings("unchecked")
    public static List<String> getMemberList(ClassLoader cl, String room) {
        try {
            return (List<String>) XposedHelpers.callStaticMethod(getChatroomLogic(cl), "m", room);
        } catch (Throwable e) { return new ArrayList<>(); }
    }

    public static boolean isChatRoom(ClassLoader cl, String name) {
        if (name == null) return false;
        if (name.endsWith("@chatroom") || name.endsWith("@im.chatroom")) return true;
        try {
            return (boolean) XposedHelpers.callStaticMethod(getChatroomLogic(cl), "B", name);
        } catch (Throwable e) { return false; }
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
        // 兜底数据源已清空（原 ContactRepository 群信息），待重写
        return "";
    }

    // ===== 联系人 =====
    public static Object getContact(ClassLoader cl, String username) {
        Object s = getContactStorage(cl);
        if (s == null) return null;
        try { return XposedHelpers.callMethod(s, "n", username, true); } catch (Throwable e) { return null; }
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
            try {
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            InputStream is = conn.getInputStream();
            try {
            Bitmap bm = BitmapFactory.decodeStream(is);
            if (bm != null) return toRoundBitmap(bm);
            } finally { is.close(); }
            } finally { conn.disconnect(); }
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
        Cursor c = null;
        try {
            c = (Cursor) XposedHelpers.callMethod(s, "D");
            if (c != null) {
                while (c.moveToNext()) {
                    String u = c.getString(c.getColumnIndex("username"));
                    if (u != null && (u.endsWith("@chatroom") || u.endsWith("@im.chatroom"))) {
                        rooms.add(u);
                    }
                }
            }
        } catch (Exception e) {
            LogWriter.log(TAG, "getAllChatRooms err: " + e.getMessage());
        } finally {
            if (c != null) {
                try { c.close(); } catch (Exception ignored) {}
            }
        }
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
        // 兜底数据源已清空（原 ContactRepository 群信息/昵称），待重写
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
