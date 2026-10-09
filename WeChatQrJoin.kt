package com.yourpkg.wxqrjoin

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Proxy

/**
 * ============================================================================
 *  微信 8.0.78 (3180) — 聊天图片长按「识别图中二维码」→ 加群
 *  独立 Kotlin Xposed 模块（不依赖 LSPilot）
 *
 *  依据：WeChatQrJoinReport.md（已 Smali 级复检 17 项）
 *
 *  链路：
 *    wk5.g0(ImageScanCodeManager).h(...) → c(wk5.t, wk5.n)
 *      → RecogQBarOfImageFileEvent → 解码引擎
 *      → RecogQBarOfImageFileResultEvent → wk5.z → w6.a()（UI 回调）
 *      → l7.b() → wk5.g0.b(l0, ImageQBarDataBean, o)
 *      → DealQBarStrEvent → scanner.model.s → v74.v.g() → geta8key
 *      → pe5.f.j(room, members, ticket, null) → qn.m (addchatroommember)
 * ============================================================================
 */

object WxQrJoin {

    const val TAG = "WxQrJoin"
    const val PKG = "com.tencent.mm"

    // ------------------------------------------------ 混淆类名（8.0.78 / 3180）
    private const val C_MGR        = "wk5.g0"        // ImageScanCodeManager
    private const val C_L0         = "wk5.l0"        // 识别结果聚合
    private const val C_T          = "wk5.t"         // 识别参数对象
    private const val C_N          = "wk5.n"         // 识别回调接口
    private const val C_O          = "wk5.o"         // 事件回调接口
    private const val C_BEAN       = "com.tencent.mm.plugin.scanner.ImageQBarDataBean"
    private const val C_SIX        = "s6"            // com.tencent.mm.pluginsdk.ui.tools.s6
    private const val C_YZ         = "com.tencent.mm.vfs.z6"

    private const val C_ROOM_API   = "pe5.f"         // ChatRoom API 接口
    private const val C_ROOM_IMPL  = "ln.a"          // 实现
    private const val C_ROOM_TASK  = "com.tencent.mm.roomsdk.model.factory.a"
    private const val C_ADD_MEMBER = "qn.m"          // NetSceneAddChatRoomMember
    private const val C_INVITE     = "qn.x"          // NetSceneInviteChatRoomMember
    private const val C_Y3         = "com.tencent.mm.storage.y3"
    private const val C_D2         = "b41.d2"
    private const val C_C4         = "qf5.k0"

    // ================================================================
    // 入口
    // ================================================================
    fun install(lpparam: LoadPackageParam) {
        if (lpparam.packageName != PKG) return
        val cl = lpparam.classLoader
        hookScanChain(cl)
        hookDealQBarStr(cl)
        hookJoinRequest(cl)
        XposedBridge.log("[$TAG] installed on 8.0.78/3180")
    }

    // ================================================================
    // ① 识别链路 Hook（诊断用）
    // ================================================================
    private fun hookScanChain(cl: ClassLoader) {
        val mgr = XposedHelpers.findClassIfExists(C_MGR, cl) ?: return
        val l0Cls = XposedHelpers.findClass(C_L0, cl)
        val tCls  = XposedHelpers.findClass(C_T, cl)
        val nCls  = XposedHelpers.findClass(C_N, cl)
        val oCls  = XposedHelpers.findClass(C_O, cl)
        val bean  = XposedHelpers.findClass(C_BEAN, cl)

        // h(view, msgId, str, imgPath, bitmap, getCodePosition, recognizeType, retry, callback)
        runCatching {
            XposedHelpers.findAndHookMethod(mgr, "h",
                View::class.java, java.lang.Long.TYPE, String::class.java, String::class.java,
                Bitmap::class.java, java.lang.Boolean.TYPE, java.lang.Integer.TYPE,
                java.lang.Boolean.TYPE, nCls,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        XposedBridge.log("[$TAG][h] msgId=${p.args[1]} talker=${p.args[2]} " +
                            "imgPath=${p.args[3]} bitmap=${p.args[4]} " +
                            "recognizeType=${p.args[6]} retry=${p.args[7]}")
                    }
                })
        }

        // c(wk5.t, wk5.n) —— doScanCode
        runCatching {
            XposedHelpers.findAndHookMethod(mgr, "c", tCls, nCls,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        val t = p.args[0]
                        XposedBridge.log("[$TAG][c] session=${XposedHelpers.getLongField(t,"a")}" +
                            " msgId=${XposedHelpers.getLongField(t,"b")}" +
                            " path=${XposedHelpers.getObjectField(t,"c")}" +
                            " type=${XposedHelpers.getIntField(t,"e")}" +
                            " talker=${XposedHelpers.getObjectField(t,"f")}" +
                            " screenShot=${XposedHelpers.getBooleanField(t,"h")}")
                    }
                })
        }

        // b(wk5.l0, ImageQBarDataBean, wk5.o) —— 触发 DealQBarStrEvent
        runCatching {
            XposedHelpers.findAndHookMethod(mgr, "b", l0Cls, bean, oCls,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        val b = p.args[1]
                        val l0 = p.args[0]
                        XposedBridge.log("[$TAG][b] ★ codeString=${XposedHelpers.getObjectField(b,"d")}" +
                            " scene=${XposedHelpers.getIntField(b,"e")}" +
                            " source=${XposedHelpers.getIntField(b,"f")}" +
                            " | l0.f=${XposedHelpers.getIntField(l0,"f")}" +
                            " l0.g=${XposedHelpers.getIntField(l0,"g")}" +
                            " l0.n=${XposedHelpers.getObjectField(l0,"n")}")
                    }
                })
        }
    }

    // ================================================================
    // ② DealQBarStrEvent → QBarStringHandler（可选 Hook）
    // ================================================================
    private fun hookDealQBarStr(cl: ClassLoader) {
        // v74.v = QBarStringHandler
        val qbar = XposedHelpers.findClassIfExists("v74.v", cl) ?: return
        runCatching {
            XposedHelpers.findAndHookMethod(qbar, "g",
                Activity::class.java, String::class.java,
                Integer.TYPE, Integer.TYPE, Integer.TYPE, String::class.java,
                Integer.TYPE, Integer.TYPE, "f74.d", "com.tencent.mm.plugin.scanner.view.s",
                Bundle::class.java, Boolean.TYPE, Integer.TYPE, Boolean.TYPE,
                "com.tencent.qbar.ScanIdentifyReportInfo", "pc5.kc0",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        XposedBridge.log("[$TAG][dealQBarString] code=${p.args[1]} " +
                            "scanUIScene=${p.args[2]} source=${p.args[3]} " +
                            "codeType=${p.args[5]} getA8KeyScene=${p.args[9]}")
                    }
                })
        }
    }

    // ================================================================
    // ③ 加群请求 Hook（抓真实 room / ticket）
    // ================================================================
    private fun hookJoinRequest(cl: ClassLoader) {
        val add = XposedHelpers.findClassIfExists(C_ADD_MEMBER, cl) ?: return
        runCatching {
            XposedHelpers.findAndHookMethod(add, "<init>",
                String::class.java, java.util.List::class.java,
                String::class.java, Any::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        XposedBridge.log("[$TAG][★REAL-JOIN] room=${p.args[0]}" +
                            "\n            members=${p.args[1]}" +
                            "\n            ticket=${p.args[2]}" +
                            "\n            extra=${p.args[3]}")
                    }
                })
        }
        runCatching {
            val y0 = XposedHelpers.findClass("com.tencent.mm.network.y0", cl)
            XposedHelpers.findAndHookMethod(add, "onGYNetEnd",
                Integer.TYPE, Integer.TYPE, String::class.java, y0, ByteArray::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val errType = p.args[0] as Int
                        val errCode = p.args[1] as Int
                        XposedBridge.log("[$TAG][addchatroommember] errType=$errType " +
                            "errCode=$errCode errMsg=${p.args[2]}")
                    }
                })
        }
        // invitechatroommember
        val inv = XposedHelpers.findClassIfExists(C_INVITE, cl)
        if (inv != null) runCatching {
            XposedHelpers.findAndHookMethod(inv, "<init>",
                String::class.java, java.util.List::class.java, Any::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        XposedBridge.log("[$TAG][invitechatroommember] room=${p.args[0]}" +
                            " members=${p.args[1]} scene=${p.args[2]}")
                    }
                })
        }
    }

    // ================================================================
    // ④ 对外 API
    // ================================================================

    /**
     * 创建独立 ImageScanCodeManager 实例。
     * @param activity 必须是你真实的 MMActivity，否则下游 ExternRequestDealQBarStrHandler$2 会跳过生命周期路径
     * @param talker   当前会话名（群 xxx@chatroom / 好友 wxid），可传 ""
     */
    @JvmStatic
    fun newManager(cl: ClassLoader, activity: Activity, talker: String = "", enable: Boolean = true): Any? = try {
        XposedHelpers.newInstance(
            XposedHelpers.findClass(C_MGR, cl),
            activity, enable, talker
        )
    } catch (t: Throwable) {
        XposedBridge.log("[$TAG] newManager err: $t"); null
    }

    /**
     * 手动触发识别（等价于点「识别图中二维码」）。
     *
     * @param view        锚点 View（可传 null，会走截屏分支）
     * @param msgId       聊天消息 ID；非聊天场景传 -1L
     * @param talker      会话名（用于 ScanCodeInfo 加群上下文）
     * @param imgPath     ★ 图片绝对路径（为空直接 return）
     * @param bitmap      可为 null
     * @param recognizeType ★★ 必须 2，否则不会组 ScanCodeInfo（加群上下文丢失）
     * @param retry       是否重试
     * @param callback    wk5.n 实现（可用 [newScanCallback] 生成）
     */
    @JvmStatic
    @JvmOverloads
    fun startScan(cl: ClassLoader, mgr: Any, activity: Activity,
                  imgPath: String, bitmap: Bitmap? = null, msgId: Long = -1L,
                  talker: String = "", view: View? = null,
                  recognizeType: Int = 2, retry: Boolean = true,
                  callback: Any? = null): Boolean = try {
        require(recognizeType == 2) { "recognizeType 必须为 2（加群上下文依赖它）" }
        require(imgPath.isNotEmpty()) { "imgPath 不能为空" }
        val cb = callback ?: newScanCallback(cl, null)
        XposedHelpers.callMethod(mgr, "h",
            view, msgId, talker, imgPath, bitmap,
            true,          // getCodePosition
            recognizeType, retry, cb)
        true
    } catch (t: Throwable) {
        XposedBridge.log("[$TAG] startScan err: $t"); false
    }

    /**
     * 生成 wk5.n 回调代理。
     * @param onCode 识别成功回调：(codeString, ImageQBarDataBean) -> Unit
     * @param onFail 识别失败回调
     */
    @JvmStatic
    fun newScanCallback(cl: ClassLoader,
                        onCode: ((String, Any) -> Unit)? = null,
                        onFail: ((Any) -> Unit)? = null): Any {
        val nCls = XposedHelpers.findClass(C_N, cl)
        val s6Cls = XposedHelpers.findClass(C_SIX, cl)
        val z6Cls = XposedHelpers.findClass(C_YZ, cl)
        return Proxy.newProxyInstance(cl, arrayOf(nCls)) { _, method, args ->
            when (method.name) {
                // a(RecogQBarOfImageFileResultEvent)
                "a" -> {
                    val ev = args?.get(0)
                    val j = XposedHelpers.getObjectField(ev, "g")
                    val path = XposedHelpers.getObjectField(j, "a")
                    val k = XposedHelpers.getIntField(j, "k")
                    XposedBridge.log("[$TAG][cb.a] path=$path recognizeType=$k")
                    if (k == 2 && onCode != null) {
                        val list = XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass(C_SIX, cl), "a", ev) as? java.util.ArrayList<Any>
                        list?.firstOrNull()?.let { bean ->
                            val code = XposedHelpers.getObjectField(bean, "d") as? String
                            if (code != null) runCatching { onCode(code, bean) }
                        }
                    }
                    null
                }
                // b(RecogQBarOfImageFileFailedEvent)
                "b" -> {
                    val ev = args?.get(0)
                    val iq = XposedHelpers.getObjectField(ev, "g")
                    XposedBridge.log("[$TAG][cb.b] failed " +
                        "type=${XposedHelpers.getIntField(iq,"b")} retryable=${XposedHelpers.getBooleanField(iq,"c")}")
                    onFail?.let { runCatching { it(ev!!) } }
                    null
                }
                // c(String) 发起识别时回调（imgPath）
                "c" -> {
                    XposedBridge.log("[$TAG][cb.c] scanStart ${args?.get(0)}")
                    null
                }
                else -> null
            }
        }
    }

    /**
     * 手动触发 DealQBarStrEvent（等价于用户选中码后的自动进群路径）。
     *
     * @param mgr        ImageScanCodeManager 实例
     * @param codeString 二维码内容 URL（就是 t16.i0.f / ImageQBarDataBean.d）
     * @param scene      l0.f —— -1 表示用微信默认 37（加群场景）
     * @param source     l0.g —— -1 表示用微信默认 4
     */
    @JvmStatic
    @JvmOverloads
    fun fireDealQBarStr(cl: ClassLoader, mgr: Any, codeString: String,
                        scene: Int = -1, source: Int = -1,
                        msgSvrId: String = ""): Boolean = try {
        val l0Cls = XposedHelpers.findClass(C_L0, cl)
        val l0 = XposedHelpers.newInstance(l0Cls)
        XposedHelpers.setObjectField(l0, "a", arrayListOf(codeString))
        XposedHelpers.setIntField(l0, "f", scene)        // -1 → 微信用默认 37（加群）
        XposedHelpers.setIntField(l0, "g", source)       // -1 → 微信用默认 4
        XposedHelpers.setIntField(l0, "i", 2)            // recognizeType
        XposedHelpers.setBooleanField(l0, "d", false)
        XposedHelpers.setObjectField(l0, "n", msgSvrId)  // → kc0.e

        val bean = XposedHelpers.newInstance(XposedHelpers.findClass(C_BEAN, cl))
        XposedHelpers.setObjectField(bean, "d", codeString)   // ★ 码值
        XposedHelpers.setIntField(bean, "e", 0)
        XposedHelpers.setIntField(bean, "f", 0)

        val oCls = XposedHelpers.findClass(C_O, cl)
        val oProxy = Proxy.newProxyInstance(cl, arrayOf(oCls)) { _, _, _ -> null }

        XposedHelpers.callMethod(mgr, "b", l0, bean, oProxy)
        XposedBridge.log("[$TAG] fireDealQBarStr -> $codeString")
        true
    } catch (t: Throwable) {
        XposedBridge.log("[$TAG] fireDealQBarStr err: $t"); false
    }

    /**
     * 静默进群（跳过第⑧步之后所有 UI，直接发 addchatroommember）。
     *
     * @param roomName 形如 "xxxxx@chatroom"
     * @param ticket   二维码 ticket，可为 null（群不需验证时）
     * @param showConfirm true = 弹微信原生确认框；false = 直接发
     *
     * 返回 true = 任务已发出（不代表服务端成功）
     */
    @JvmStatic
    @JvmOverloads
    fun joinGroupSilently(cl: ClassLoader, activity: Activity,
                          roomName: String, ticket: String?,
                          showConfirm: Boolean = false,
                          members: List<String>? = null): Boolean = try {
        require(roomName.endsWith("@chatroom")) { "roomName 必须以 @chatroom 结尾" }

        // 1) ChatRoom API 实例（pe5.f = ln.a）
        val api = XposedHelpers.newInstance(XposedHelpers.findClass(C_ROOM_IMPL, cl))

        // 2) 成员列表（默认只有自己）
        val list = members ?: arrayListOf(selfWxid(cl))

        // 3) 构造任务：j(room, members, ticket, localHistoryInfo)
        val task = XposedHelpers.callMethod(api, "j", roomName, list, ticket, null)

        // 4) 发送
        if (showConfirm) {
            XposedHelpers.callMethod(task, "c", activity, "加入群聊", "确定加入该群聊？",
                true, true, null)
        } else {
            XposedHelpers.callMethod(task, "a")          // ★ 静默
        }
        XposedBridge.log("[$TAG] joinGroup $roomName ticket=$ticket confirm=$showConfirm")
        true
    } catch (t: Throwable) {
        XposedBridge.log("[$TAG] joinGroupSilently err: $t"); false
    }

    /** 邀请进群（走 invitechatroommember，等价于「收到群内成员邀请」） */
    @JvmStatic
    @JvmOverloads
    fun inviteGroup(cl: ClassLoader, roomName: String, scene: Int = 3,
                   members: List<String>? = null): Boolean = try {
        val api = XposedHelpers.newInstance(XposedHelpers.findClass(C_ROOM_IMPL, cl))
        val list = members ?: arrayListOf(selfWxid(cl))
        // a(room, members, scene, callback)
        val task = XposedHelpers.callMethod(api, "a", roomName, list, scene, null)
        XposedHelpers.callMethod(task, "a")
        true
    } catch (t: Throwable) {
        XposedBridge.log("[$TAG] inviteGroup err: $t"); false
    }

    /** 自己的 wxid（b41.y1.u()） */
    @JvmStatic
    fun selfWxid(cl: ClassLoader): String = try {
        XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("b41.y1", cl), "u") as String
    } catch (t: Throwable) {
        // 兜底：MsgInfoStorage.getSelfUsername
        try {
            XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("com.tencent.mm.storage.f9", cl), "cJ") as String
        } catch (t2: Throwable) { "" }
    }

    /** 是否已在该群里 */
    @JvmStatic
    fun isInGroup(cl: ClassLoader, roomName: String): Boolean = try {
        val contact = XposedHelpers.callStaticMethod(
            XposedHelpers.findClass(C_C4, cl), "F0", true)
        contact != null &&
            XposedHelpers.callMethod(contact, "t4", roomName) != null
    } catch (t: Throwable) { false }

    /** 会话类型（1=好友 2=群 3=公众号 4=企业微信）—— 对应 wk5.g0.b 里 o46Var.e */
    @JvmStatic
    fun chatTypeOf(cl: ClassLoader, talker: String): Int = try {
        val y3 = XposedHelpers.findClass(C_Y3, cl)
        val d2 = XposedHelpers.findClass(C_D2, cl)
        when {
            XposedHelpers.callStaticMethod(y3, "s4", talker) as Boolean -> 4
            (XposedHelpers.callStaticMethod(y3, "o4", talker) as Boolean) ||
                (XposedHelpers.callStaticMethod(d2, "U", talker) as Boolean) -> 2
            XposedHelpers.callStaticMethod(d2, "G", talker) as Boolean -> 3
            else -> 1
        }
    } catch (t: Throwable) { 1 }
}

/**
 * ============================================================================
 *  Xposed 入口
 * ============================================================================
 */
class WxQrJoinModule : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        WxQrJoin.install(lpparam)
    }
}