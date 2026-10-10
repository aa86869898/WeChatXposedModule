package com.leshao.v3.wm.utils

import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.widget.ImageView
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayList
import java.util.List
import de.robv.android.xposed.XposedHelpers
import com.leshao.v3.LogWriter

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
class WmReflect {

    companion object {

        private const val TAG = "WmReflect"

        // ===== 核心服务 =====
        @JvmStatic
        fun getSendMsgMgr(cl: ClassLoader): Any? {
            try {
                // 8.0.78(3180): 发送管理器 = qs5.v5 (kl5.s5 已失效)。服务定位优先 ph5.n0.c(qs5.v5)
                // v955: 定位器首位改用 DexKit 动态检索结果(特征字符串 "MicroMsg.ServiceManager"),
                // 严禁硬编码类名作主查找; 下列候选仅作历史版本兜底。
                val locatorList = java.util.ArrayList<String>()
                val dkLoc = com.leshao.v3.hook.DexKitHelper.getServiceLocatorClass()
                if (dkLoc != null && !dkLoc.isEmpty()) locatorList.add(dkLoc)
                for (l in arrayOf("ph5.n0", "pa5.n0", "hm0.j1", "gp0.j1.j", "gp0.j1")) {
                    if (!locatorList.contains(l)) locatorList.add(l)
                }
                val locators = locatorList.toTypedArray()
                val managers = arrayOf("qs5.v5", "kl5.s5")
                for (mgrName in managers) {
                    for (loc in locators) {
                        try {
                            val locCls = XposedHelpers.findClass(loc, cl)
                            val mgrCls = XposedHelpers.findClass(mgrName, cl)
                            val inst = XposedHelpers.callStaticMethod(locCls, "c", mgrCls)
                            if (inst != null) {
                                LogWriter.log(TAG, "getSendMsgMgr OK: " + loc + ".c(" + mgrName + ") -> " + inst.javaClass.name)
                                return inst
                            }
                        } catch (ignored: Throwable) {}
                    }
                }
                LogWriter.log(TAG, "getSendMsgMgr FAILED: no qs5.v5/kl5.s5 via any locator")
                return null
            } catch (t: Throwable) {
                LogWriter.log(TAG, "getSendMsgMgr err: " + t.message)
                return null
            }
        }

        @JvmStatic
        fun getChatroomLogic(cl: ClassLoader): Class<*>? {
            // v3.0.272: 反编译确认 3180 群成员逻辑权威类 = b41.u1（ChatroomMembersLogic）
            try {
                return XposedHelpers.findClass("b41.u1", cl)
            } catch (t: Throwable) {
                // Try DexKit candidates（旧版 e01 系兜底）
                val candidates = arrayOf("e01.v1", "e02.v1", "e00.v1", "e01.u1", "e01.w1")
                for (name in candidates) {
                    try { return XposedHelpers.findClass(name, cl) } catch (ignored: Throwable) {}
                }
                return null
            }
        }

        @JvmStatic
        fun getContactStorage(cl: ClassLoader): Any? {
            try {
                var contactCls: Class<*>? = null
                // Try DexKit-discovered contact storage
                val dexKitContact = com.leshao.v3.hook.DexKitHelper.getContactStorageClass()
                if (dexKitContact != null && !dexKitContact.isEmpty()) {
                    try { contactCls = XposedHelpers.findClass(dexKitContact, cl) } catch (ignored: Throwable) {}
                }
                if (contactCls == null) {
                    val candidates = arrayOf("e01.d9", "sh3.c4", "e32.a")
                    for (name in candidates) {
                        try { contactCls = XposedHelpers.findClass(name, cl); break } catch (ignored: Throwable) {}
                    }
                }
                if (contactCls == null) return null
                return XposedHelpers.callMethod(
                    XposedHelpers.callStaticMethod(contactCls, "b"), "q")
            } catch (e: Throwable) { return null }
        }

        @Volatile
        private var sChatroomSvcIface: Class<*>? = null

        @JvmStatic
        fun getChatroomInfo(cl: ClassLoader?, room: String?): Any? {
            if (cl == null || room == null) return null
            if (sChatroomSvcIface == null) {
                sChatroomSvcIface = findChatroomSvcIface(cl)
            }
            if (sChatroomSvcIface == null) return null
            for (retry in 0 until 3) {
                try {
                    val svc = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("hm0.j1", cl), "s", sChatroomSvcIface)
                    if (svc == null) {
                        if (retry < 2) {
                            try { Thread.sleep(500) } catch (ignored: InterruptedException) {}
                            continue
                        }
                        return null
                    }
                    val inst = XposedHelpers.callMethod(svc, "a")
                    if (inst == null) {
                        if (retry < 2) {
                            try { Thread.sleep(500) } catch (ignored: InterruptedException) {}
                            continue
                        }
                        return null
                    }
                    return XposedHelpers.callMethod(inst, "H0", room)
                } catch (e: Throwable) {
                    val msg = e.message
                    if (msg != null && msg.contains("Kernel not initialized")) {
                        if (retry < 2) {
                            try { Thread.sleep(500) } catch (ignored: InterruptedException) {}
                            continue
                        }
                        return null
                    }
                    if (retry >= 2) {
                        LogWriter.log(TAG, "getChatroomInfo err: " + e.message)
                    }
                    return null
                }
            }
            return null
        }

        private fun findChatroomSvcIface(cl: ClassLoader): Class<*>? {
            val candidates = arrayOf("cw1.f", "cw1.g", "cw2.f", "cw2.g")
            for (name in candidates) {
                try { return XposedHelpers.findClass(name, cl) } catch (ignored: Throwable) {}
            }
            LogWriter.log(TAG, "findChatroomSvcIface: no chatroom iface found")
            return null
        }

        // ===== 消息 =====
        @JvmStatic
        fun sendTextMsg(cl: ClassLoader, content: String, toUser: String): Boolean {
            return sendTextMsg(cl, content, toUser, 1, 0)
        }

        /**
         * 带消息类型/flag 的文本发送，供消息伪装等需要伪造 type 的功能复用。
         * 8.0.78(3180): 文本走 qs5.v5 新框架 mj/nj/oj/pj(toUser,content,type,flag)。
         */
        @JvmStatic
        fun sendTextMsg(cl: ClassLoader, content: String, toUser: String, type: Int, flag: Int): Boolean {
            val m = getSendMsgMgr(cl)
            if (m == null) {
                LogWriter.log(TAG, "sendTextMsg FAILED: sendMsgMgr null")
                return false
            }
            // 旧 qj(content,toUser) 为相册名片, 不再用于文本。
            val textMethods = arrayOf("oj", "nj", "mj", "pj")
            var lastErr: Throwable? = null
            for (mn in textMethods) {
                try {
                    // 尝试 (String,String,int,int) 签名
                    XposedHelpers.callMethod(m, mn, toUser, content, type, flag)
                    LogWriter.log(TAG, "sendTextMsg ok via " + mn +
                            "(toUser,content," + type + "," + flag + ")")
                    return true
                } catch (t1: Throwable) {
                    lastErr = t1
                }
                try {
                    // 尝试 (String,String,int,int,int) 等变体
                    XposedHelpers.callMethod(m, mn, toUser, content, type, flag, 0)
                    LogWriter.log(TAG, "sendTextMsg ok via " + mn +
                            "(toUser,content," + type + "," + flag + ",0)")
                    return true
                } catch (ignored: Throwable) {}
            }
            if (type == 1) {
                try {
                    // 多目标文本 hj(atStr, usersCsv, extra) 单目标亦可
                    XposedHelpers.callMethod(m, "hj", null as Any?, toUser, null as Any?)
                    LogWriter.log(TAG, "sendTextMsg ok via hj(null,toUser,null)")
                    return true
                } catch (t2: Throwable) {
                    lastErr = t2
                }
                try {
                    // 多目标 gj(str1,str2,str3,Z)
                    XposedHelpers.callMethod(m, "gj", null as Any?, toUser, null as Any?, true)
                    LogWriter.log(TAG, "sendTextMsg ok via gj(null,toUser,null,true)")
                    return true
                } catch (t3: Throwable) {
                    lastErr = t3
                }
            }
            LogWriter.log("WmReflect", "sendTextMsg FAILED: " + (lastErr?.message ?: "no method"))
            return false
        }

        @JvmStatic
        fun broadcastRooms(cl: ClassLoader, rooms: List<String>?, content: String) {
            if (rooms == null || rooms.isEmpty()) return
            for (room in rooms) {
                if (room == null || room.isEmpty()) continue
                sendTextMsg(cl, content, room)
            }
        }

        // ===== 成员 =====
        @Suppress("UNCHECKED_CAST")
        @JvmStatic
        fun getMemberList(cl: ClassLoader, room: String): List<String> {
            try {
                return XposedHelpers.callStaticMethod(getChatroomLogic(cl), "m", room) as List<String>
            } catch (e: Throwable) { return ArrayList<String>() as List<String> }
        }

        @JvmStatic
        fun isChatRoom(cl: ClassLoader, name: String?): Boolean {
            if (name == null) return false
            if (name.endsWith("@chatroom") || name.endsWith("@im.chatroom")) return true
            try {
                return XposedHelpers.callStaticMethod(getChatroomLogic(cl), "B", name) as Boolean
            } catch (e: Throwable) { return false }
        }

        @JvmStatic
        fun getMemberCount(cl: ClassLoader, room: String): Int {
            val i = getChatroomInfo(cl, room)
            if (i != null) {
                try { return XposedHelpers.getIntField(i, "field_memberCount") } catch (ignored: Exception) {}
            }
            // 兜底：成员列表数量
            val ms = getMemberList(cl, room)
            return ms.size
        }

        @JvmStatic
        fun getRoomOwner(cl: ClassLoader, room: String): String {
            val i = getChatroomInfo(cl, room)
            if (i != null) {
                try {
                    val v = XposedHelpers.getObjectField(i, "field_roomowner") as? String
                    if (v != null && !v.isEmpty()) return v
                } catch (ignored: Exception) {}
            }
            // 兜底数据源已清空（原 ContactRepository 群信息），待重写
            return ""
        }

        // ===== 联系人 =====
        @JvmStatic
        fun getContact(cl: ClassLoader, username: String): Any? {
            val s = getContactStorage(cl)
            if (s == null) return null
            try { return XposedHelpers.callMethod(s, "n", username, true) } catch (e: Throwable) { return null }
        }

        @JvmStatic
        fun getWxid(c: Any?): String {
            if (c == null) return ""
            try { return XposedHelpers.getObjectField(c, "field_username") as? String ?: "" } catch (e: Exception) { return "" }
        }

        @JvmStatic
        fun getAlias(c: Any?): String {
            if (c == null) return ""
            try { return XposedHelpers.getObjectField(c, "field_alias") as? String ?: "" } catch (e: Exception) { return "" }
        }

        @JvmStatic
        fun getNickname(c: Any?): String {
            if (c == null) return ""
            try { return XposedHelpers.getObjectField(c, "field_nickname") as? String ?: "" } catch (e: Exception) { return "" }
        }

        @JvmStatic
        fun getRemark(c: Any?): String {
            if (c == null) return ""
            try { return XposedHelpers.getObjectField(c, "field_conRemark") as? String ?: "" } catch (e: Exception) { return "" }
        }

        // ===== 头像 =====
        @JvmStatic
        fun getAvatarUrl(cl: ClassLoader, wxid: String): String? {
            val c = getContact(cl, wxid)
            if (c == null) return null
            val fields = arrayOf("field_headImgUrl", "field_avatarUrl", "field_avatarBUrl",
                "field_headimgurl", "field_avatarurl", "field_avatar_full_url",
                "field_smallHeadImgUrl", "field_encryptUsername")
            for (f in fields) {
                try {
                    val v = XposedHelpers.getObjectField(c, f) as? String
                    if (v != null && !v.isEmpty()) return v
                } catch (ignored: Exception) {}
            }
            return null
        }

        @JvmStatic
        fun loadAvatarInto(cl: ClassLoader, iv: ImageView, wxid: String) {
            try {
                val a = XposedHelpers.findClass("com.tencent.mm.pluginsdk.ui.a", cl)
                XposedHelpers.callStaticMethod(a, "b", iv, wxid)
                return
            } catch (ignored: Exception) {}
            // Fallback: get avatar URL from contact and download
            val url = getAvatarUrl(cl, wxid)
            if (url == null || url.isEmpty()) return
            val target = iv
            Thread {
                val bm = downloadAvatarUrl(url)
                if (bm != null && target != null) {
                    target.post { target.setImageBitmap(bm) }
                }
            }.start()
        }

        private fun downloadAvatarUrl(url: String): Bitmap? {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.instanceFollowRedirects = true
                    val input: InputStream = conn.inputStream
                    try {
                        val bm = BitmapFactory.decodeStream(input)
                        if (bm != null) return toRoundBitmap(bm)
                    } finally { input.close() }
                } finally { conn.disconnect() }
            } catch (ignored: Exception) {}
            return null
        }

        private fun toRoundBitmap(src: Bitmap): Bitmap {
            val w = src.width
            val h = src.height
            val s = Math.min(w, h)
            val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            c.drawCircle(s / 2f, s / 2f, s / 2f, p)
            p.setXfermode(PorterDuffXfermode(PorterDuff.Mode.SRC_IN))
            c.drawBitmap(src, (s - w) / 2f, (s - h) / 2f, p)
            src.recycle()
            return out
        }

        // ===== 踢人（v3.0.272: 候选 b41.u1 / d24.h）=====
        @JvmStatic
        fun kickMember(cl: ClassLoader, room: String, member: String): Boolean {
            try {
                if (!isChatRoom(cl, room)) return false
                // 反编译确认 d24.h 非群成员逻辑；b41.u1(ChatroomMembersLogic) 为权威类，保留 d24.h 作旧版兜底
                for (cn in arrayOf("b41.u1", "d24.h")) {
                    try {
                        XposedHelpers.callStaticMethod(XposedHelpers.findClass(cn, cl), "a",
                                room, 1, 0, 0, 0, 0, System.currentTimeMillis(), "")
                        return true
                    } catch (ignored: Exception) {}
                }
                return false
            } catch (e: Exception) { return false }
        }

        // ===== 邀请（v3.0.272: 权威链路 un.k.get() → pe5.f.j(...) → factory.a.a()）=====
        @Suppress("UNCHECKED_CAST")
        @JvmStatic
        fun inviteMembers(cl: ClassLoader, room: String, members: List<String>): Boolean {
            try {
                if (!isChatRoom(cl, room)) return false
                // 权威链路（附录 G）：un.k.get() → pe5.f.j(room, members, ticket, null) → factory.a.a()
                try {
                    val unk = XposedHelpers.newInstance(XposedHelpers.findClass("un.k", cl))
                    val api = XposedHelpers.callMethod(unk, "get")
                    if (api != null) {
                        val task = XposedHelpers.callMethod(api, "j", room, members, null as Any?, null as Any?)
                        if (task != null) {
                            XposedHelpers.callMethod(task, "a")
                            return true
                        }
                    }
                } catch (ignored: Exception) {}
                // 旧链路兜底（kn.x 已失效，仅兼容旧版）
                try {
                    val knx = XposedHelpers.findClass("kn.x", cl)
                    var req: Any? = null
                    try {
                        req = XposedHelpers.newInstance(knx, room, members, 0, null as Any?)
                    } catch (ignored: Exception) {}
                    if (req == null) {
                        try {
                            req = XposedHelpers.newInstance(knx, room, members, 0, "", null as Any?)
                        } catch (ignored: Exception) {}
                    }
                    if (req == null) return false
                    val r1 = XposedHelpers.callStaticMethod(XposedHelpers.findClass("hm0.j1", cl), "d")
                    if (r1 != null) {
                        XposedHelpers.callMethod(r1, "d", req)
                        return true
                    }
                } catch (ignored: Exception) {}
                return false
            } catch (e: Exception) { return false }
        }

        // ===== 上下文 =====
        /** 从聊天 intent 提取当前对象，尝试多个 key（兼容不同微信版本） */
        @JvmStatic
        fun getCurrentChatUser(intent: Intent?): String? {
            if (intent == null) return null
            val keys = arrayOf(
                "Chat_User", "Chatroom_Name", "contact_username", "username",
                "Openim_User", "Contact_User", "Chat_User_To", "talker", "Talker"
            )
            for (k in keys) {
                val v = intent.getStringExtra(k)
                if (v != null && !v.isEmpty()) return v
            }
            return null
        }

        /** 从 ChattingUIFragment 实例读取当前聊天对象字段（String 类型字段中匹配 wxid/@chatroom） */
        @JvmStatic
        fun getChatUserFromFragment(fragment: Any?): String? {
            if (fragment == null) return null
            try {
                for (f in fragment.javaClass.declaredFields) {
                    if (f.type != String::class.java) continue
                    f.isAccessible = true
                    val v = f.get(fragment)
                    if (v == null) continue
                    val s = v as String
                    if (s.isEmpty()) continue
                    if (s.endsWith("@chatroom") || s.endsWith("@im.chatroom")
                        || s.startsWith("wxid_") || s.endsWith("@openim")
                        || s.endsWith("@qqim") || s.endsWith("@app")) {
                        return s
                    }
                }
            } catch (ignored: Throwable) {}
            return null
        }

        @JvmStatic
        fun getAllChatRooms(cl: ClassLoader): List<String> {
            val rooms = ArrayList<String>()
            val s = getContactStorage(cl)
            if (s == null) return rooms as List<String>
            var c: Cursor? = null
            try {
                c = XposedHelpers.callMethod(s, "D") as Cursor?
                if (c != null) {
                    while (c.moveToNext()) {
                        val u = c.getString(c.getColumnIndex("username"))
                        if (u != null && (u.endsWith("@chatroom") || u.endsWith("@im.chatroom"))) {
                            rooms.add(u)
                        }
                    }
                }
            } catch (e: Exception) {
                LogWriter.log(TAG, "getAllChatRooms err: " + e.message)
            } finally {
                if (c != null) {
                    try { c.close() } catch (ignored: Exception) {}
                }
            }
            return rooms as List<String>
        }

        // ===== 群详情(dm.y1字段) =====
        @JvmStatic
        fun getRoomNotice(cl: ClassLoader, room: String): String {
            val i = getChatroomInfo(cl, room)
            if (i == null) return ""
            try { return XposedHelpers.getObjectField(i, "field_chatroomnotice") as? String ?: "" } catch (e: Exception) { return "" }
        }

        @JvmStatic
        fun getRoomNoticeEditor(cl: ClassLoader, room: String): String {
            val i = getChatroomInfo(cl, room)
            if (i == null) return ""
            try { return XposedHelpers.getObjectField(i, "field_chatroomnoticeEditor") as? String ?: "" } catch (e: Exception) { return "" }
        }

        @JvmStatic
        fun getRoomNoticePubTime(cl: ClassLoader, room: String): Long {
            val i = getChatroomInfo(cl, room)
            if (i == null) return 0
            try { return XposedHelpers.getLongField(i, "field_chatroomnoticePublishTime") } catch (e: Exception) { return 0 }
        }

        @JvmStatic
        fun getRoomCreateTime(cl: ClassLoader, room: String): Long {
            val i = getChatroomInfo(cl, room)
            if (i == null) return 0
            try { return XposedHelpers.getLongField(i, "field_addtime") } catch (e: Exception) { return 0 }
        }

        @JvmStatic
        fun getRoomStatus(cl: ClassLoader, room: String): Int {
            val i = getChatroomInfo(cl, room)
            if (i == null) return -1
            try { return XposedHelpers.getIntField(i, "field_chatroomStatus") } catch (e: Exception) { return -1 }
        }

        @JvmStatic
        fun getRoomDisplayName(cl: ClassLoader, room: String): String {
            val i = getChatroomInfo(cl, room)
            if (i != null) {
                try {
                    val v = XposedHelpers.getObjectField(i, "field_displayname") as? String
                    if (v != null && !v.isEmpty()) return v
                } catch (ignored: Exception) {}
            }
            // 兜底数据源已清空（原 ContactRepository 群信息/昵称），待重写
            return ""
        }

        @JvmStatic
        fun getMyDisplayName(cl: ClassLoader, room: String): String {
            val i = getChatroomInfo(cl, room)
            if (i == null) return ""
            try { return XposedHelpers.getObjectField(i, "field_selfDisplayName") as? String ?: "" } catch (e: Exception) { return "" }
        }

        @JvmStatic
        fun isRoomDisbanded(cl: ClassLoader, room: String): Boolean {
            return getRoomStatus(cl, room) != 0
        }
    }
}