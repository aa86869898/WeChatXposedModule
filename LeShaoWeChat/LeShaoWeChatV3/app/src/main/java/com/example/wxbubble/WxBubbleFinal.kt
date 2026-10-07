package com.example.wxbubble

import android.content.Context
import android.content.res.Resources
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.PathShape
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindField
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.FieldMatcher
import org.luckypray.dexkit.query.matchers.InterfacesMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.query.matchers.MethodsMatcher
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

private const val PKG = "com.tencent.mm"
private const val TAG = "WxBubble"
private fun log(s: String) = XposedBridge.log("[$TAG] $s")

private object C {
    const val ANIM   = "com.tencent.mm.ui.base.AnimImageView"
    const val NEAT   = "com.tencent.mm.ui.widget.MMNeat7extView"
    const val RVADPT = "com.tencent.mm.view.recyclerview.WxRecyclerAdapter"
    const val MSG    = "com.tencent.mm.storage.e9"
    const val RES    = "android.content.res.Resources"
}

// ============ 1. 入口 / 环境 / 启动 ============
class WxBubbleModule : IXposedHookLoadPackage {
    override fun handleLoadPackage(lp: XC_LoadPackage.LoadPackageParam) {
        if (lp.packageName != PKG) return
        WxEnv.lp = lp
        WxEnv.apkPath = lp.appInfo?.sourceDir ?: return
        Thread({ WxBoot.start() }, "WxBubble-boot").start()
    }
}

object WxEnv {
    lateinit var lp: XC_LoadPackage.LoadPackageParam
    var apkPath = ""; var modulePath = ""; var verTag = ""
    val cl: ClassLoader get() = lp.classLoader
}

object WxBoot {
    fun start() = runCatching {
        WxEnv.verTag = verTag()
        if (!WxResolver.resolve()) { log("resolve 失败，模块不生效"); return@runCatching }
        WxResolver.dump()
        BubbleFactory.init()
        // 第 8.4 节模块配置（严格按文档）
        Replacer.factory = { ctx, from, voice, orig -> BubbleFactory.create(ctx, from, voice, orig) }
        TimelineEngine.enabled = true
        TimelineEngine.everyMessage = true
        TimelineEngine.format = { ms ->
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
                .format(java.util.Date(ms))
        }
        SysTipEngine.formatter = { msg, raw -> raw?.let { "🕐 " + it } }   // null = 不改
        HkRedPacket.replaceResId = 0                                        // 无红包皮肤资源，0 = 不替换
        Hk.install()
    }.onFailure { log("boot error: $it") }

    private fun verTag(): String = runCatching {
        val at = XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.app.ActivityThread", null), "currentActivityThread")
        val ctx = XposedHelpers.callMethod(at, "getSystemContext")
        val pm = XposedHelpers.callMethod(ctx, "getPackageManager")
        val pi = XposedHelpers.callMethod(pm, "getPackageInfo", PKG, 0)
        "${XposedHelpers.getObjectField(pi, "versionCode")}:${XposedHelpers.getObjectField(pi, "versionName")}"
    }.getOrDefault("?")
}

// ============ 2. 反射工具 ============
object Reflect {
    fun allFields(c: Class<*>?): List<Field> {
        val out = ArrayList<Field>(); var k = c
        while (k != null && k != Any::class.java) {
            val n = k.name
            if (n.startsWith("android.") || n.startsWith("androidx.") ||
                n.startsWith("java.") || n.startsWith("kotlin.")) break
            out += k.declaredFields; k = k.superclass
        }
        return out
    }
    fun fieldByName(o: Any, name: String): Any? =
        allFields(o.javaClass).firstOrNull { it.name == name }?.let { it.isAccessible = true; it.get(o) }
    fun fieldByType(o: Any, type: String): Any? =
        allFields(o.javaClass).firstOrNull { it.type.name == type }?.let { it.isAccessible = true; it.get(o) }
    fun fieldsByType(o: Any, type: String): List<Any> =
        allFields(o.javaClass).filter { it.type.name == type }.mapNotNull { it.isAccessible = true; it.get(o) }
    fun fieldPath(o: Any?, vararg names: String): Any? {
        var cur = o ?: return null
        for (n in names) cur = fieldByName(cur, n) ?: return null
        return cur
    }
    fun method(c: Class<*>, name: String, pc: Int): Method? {
        var k: Class<*>? = c
        while (k != null && k != Any::class.java) {
            k.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == pc }
                ?.let { it.isAccessible = true; return it }
            k = k.superclass
        }
        return null
    }
    fun call(o: Any?, m: Method?, vararg a: Any?): Any? = runCatching { m?.invoke(o, *a) }.getOrNull()
    fun callByName(o: Any?, name: String, pc: Int, vararg a: Any?): Any? =
        if (o == null) null else call(o, method(o.javaClass, name, pc), *a)
    fun classOf(d: String): String = d.removePrefix("L").removeSuffix(";").replace('/', '.')
    fun load(n: String?): Class<*>? = n?.let {
        val cn = if (it.startsWith("L") && it.endsWith(";")) it.removePrefix("L").removeSuffix(";").replace('/', '.') else it
        runCatching { XposedHelpers.findClass(cn, WxEnv.cl) }.getOrNull()
    }
}

// ============ 3. L0 发现层：四级锚点 ============
object R {
    var msgInfo: Class<*>? = null
    var voiceItemFrom: Class<*>? = null; var voiceItemTo: Class<*>? = null
    var voiceItemFromMvvm: Class<*>? = null; var voiceItemToMvvm: Class<*>? = null
    var voiceHolder: Class<*>? = null; var voiceFill: Method? = null
    var textHolder: Class<*>? = null; var textBubbleSetter: Method? = null; var textItemFrom: Class<*>? = null
    var sysMsgItem: Class<*>? = null; var sysMsgFill: Method? = null
    var sysMsgTemplate: Class<*>? = null; var sysMsgGen: Method? = null
    var adapter: Class<*>? = null; var rvAdapter: Class<*>? = null; var baseHolder: Class<*>? = null
    var autoPlay: Class<*>? = null; var voiceLogic: Class<*>? = null
    var voiceInfo: Class<*>? = null; var voiceContent: Class<*>? = null
    var hbItem: Class<*>? = null; var msgQuote: Class<*>? = null
    var anim: Class<*>? = null; var neat: Class<*>? = null
    var chatBgAttr: Class<*>? = null; var c2cUtil: Class<*>? = null
    var quoteImpls: List<Class<*>> = emptyList()
    var allClasses: List<Class<*>> = emptyList()
}

object WxResolver {
    fun resolve(): Boolean {
        runCatching { resolveByDexKit() }
        if (!validate()) { log("DexKit 不完整 → 硬编码兜底"); fallback() }
        resolveDerived()
        return validate()
    }

private fun resolveByDexKit() {
        val bridge = DexKitBridge.create(WxEnv.apkPath)
        try {
            fun uniq(s: String, extra: (Class<*>) -> Boolean = { true }): Class<*>? =
                bridge.findClass(FindClass.create().matcher(ClassMatcher.create().usingStrings(s)))
                    .mapNotNull { cd -> Reflect.load(cd.descriptor) }
                    .firstOrNull { extra(it) }
            fun andStr(s: String, name: String, pc: Int): Class<*>? =
                bridge.findClass(FindClass.create().matcher(ClassMatcher.create().usingStrings(s)))
                    .mapNotNull { cd -> Reflect.load(cd.descriptor) }
                    .firstOrNull { c -> c.declaredMethods.any { m -> m.name == name && m.parameterTypes.size == pc } }

            // ---- A 区 适配器 ----
            R.adapter   = uniq("_onBindViewHolder[")                                   // A-1 唯一
            R.rvAdapter = Reflect.load("Lcom/tencent/mm/view/recyclerview/WxRecyclerAdapter;") // 四级兜底

            // ---- B 区 语音 ----
            R.voiceItemFrom = uniq("onStateBtnClick voice msg(%s) re-download!")         // B-1 唯一
            R.voiceHolder   = uniq("[voice interrupt] set continue play visible ")       // B-2 唯一
            R.voiceItemTo   = andStr("ChattingItemVoice\$ChattingItemVoiceTo", "H", 2) // B-3
            R.autoPlay      = andStr("voice_continue_play_info", "H", 2)                   // B-6
            R.voiceLogic    = andStr("MicroMsg.VoiceLogic", "n", 1)                      // B-7 n(J)F
            R.voiceInfo     = andStr("MasterBufId", "b", 0)                              // B-8 b()→ContentValues
            R.voiceContent  = andStr("voicemd5", "getLength", 0)                         // B-9
            R.voiceFill     = R.voiceHolder?.let { c ->
                c.declaredMethods.firstOrNull { it.name == "e" && it.parameterTypes.size == 9 } }

            // ---- C 区 文本 ----
            R.textHolder = uniq("[isOpenNeatTextView]")                                  // C-1 唯一
            R.textBubbleSetter = R.textHolder?.let { c ->
                c.declaredMethods.firstOrNull { it.name == "b" && it.parameterTypes.size == 4 } }
            R.textItemFrom = andStr("MicroMsg.ChattingItemTextFrom", "d", 4)             // C-2

            // ---- D 区 系统提示/时间线 ----
            R.sysMsgItem = andStr("chat_sys_msg_del_btn", "H", 2)                        // D-1
            R.sysMsgTemplate = andStr("com/tencent/mm/ui/chatting/viewitems/ChattingItemSysMsgTemplate", "a", 5) // D-3
            R.baseHolder = bridge.findField(FindField.create().matcher(FieldMatcher.create().name("timeTV")))
                .firstOrNull()?.let { fd -> Reflect.load(fd.className) }                  // D-5 ★结构
            R.sysMsgFill = R.sysMsgItem?.let { c ->
                c.declaredMethods.firstOrNull { it.name == "n" && it.parameterTypes.size == 4 } }

            // sysmsg 文本生成：gj 5 参 + AND Tag（D-2）
            val tmplClasses = bridge.findClass(FindClass.create().matcher(
                    ClassMatcher.create().usingStrings("MicroMsg.SysMsgTemplateImp")))
                .mapNotNull { cd -> Reflect.load(cd.descriptor) }
            R.sysMsgGen = tmplClasses.firstOrNull { c ->
                c.declaredMethods.any { it.name == "gj" && it.parameterTypes.size == 5 &&
                                        it.returnType == CharSequence::class.java } }
                ?.let { c -> c.declaredMethods.first { it.name == "gj" } }

            // ---- E 区 文字颜色 ----
            R.chatBgAttr = uniq("chatbg")                                                // E-1 唯一

            // ---- F 区 红包 ----
            R.c2cUtil = uniq("getC2CLuckyMoneyDescByHbStatus")                           // F-1 唯一
            R.hbItem  = andStr("MicroMsg.ChattingItemAppMsgC2CFrom", "H", 2)             // F-3

            // ---- G 区 引用 ----
            R.quoteImpls = bridge.findClass(FindClass.create().matcher(
                    ClassMatcher.create().interfaces(InterfacesMatcher.create().add("Lq71/n;")))) // G-2 ×44
                .mapNotNull { cd -> Reflect.load(cd.descriptor) }
            bridge.findMethod(FindMethod.create().matcher(
                MethodMatcher.create().name("J7").returnType("q71.n")))                  // G-1 仅诊断

            // ---- H 区 消息 ----
            R.msgInfo = bridge.findClass(
                    FindClass.create().searchPackages(listOf("com.tencent.mm.storage"))
                        .matcher(ClassMatcher.create().usingStrings("MicroMsg.MsgInfo")
                            .methods(MethodsMatcher.create().add(
                                MethodMatcher.create().name("convertFrom").paramTypes("android.database.Cursor"))))
                ).firstOrNull()?.let { cd -> Reflect.load(cd.descriptor) }               // H-1

            // ---- 四级：未混淆类名兜底 ----
            R.anim = Reflect.load("Lcom/tencent/mm/ui/base/AnimImageView;")
            R.neat = Reflect.load("Lcom/tencent/mm/ui/widget/MMNeat7extView;")
            R.msgQuote = Reflect.load("Lcom/tencent/mm/plugin/msgquote/model/MsgQuoteItem;")

            // ---- 三级：MVVM 子类 ----
            R.voiceItemFromMvvm = bridge.findClass(FindClass.create().matcher(
                    ClassMatcher.create().superClass(R.voiceItemFrom?.name?.let { n ->
                        "L" + n.replace('.', '/') + ";" } ?: "")))
                .firstOrNull()?.let { Reflect.load(it.descriptor) }
            R.voiceItemToMvvm   = bridge.findClass(FindClass.create().matcher(
                    ClassMatcher.create().superClass(R.voiceItemTo?.name?.let { n ->
                        "L" + n.replace('.', '/') + ";" } ?: "")))
                .firstOrNull()?.let { Reflect.load(it.descriptor) }
        } finally {
            bridge.close()
        }
    }

    private fun fallback() {
        R.msgInfo = Reflect.load(C.MSG)
        R.voiceItemFrom = Reflect.load("com.tencent.mm.ui.chatting.viewitems.bq")
        R.voiceItemTo = Reflect.load("com.tencent.mm.ui.chatting.viewitems.iq")
        R.voiceItemFromMvvm = Reflect.load("com.tencent.mm.ui.chatting.viewitems.tr")
        R.voiceItemToMvvm = Reflect.load("com.tencent.mm.ui.chatting.viewitems.ur")
        R.voiceHolder = Reflect.load("com.tencent.mm.ui.chatting.viewitems.mq")
        R.textHolder = Reflect.load("com.tencent.mm.ui.chatting.viewitems.to")
        R.textItemFrom = Reflect.load("hn5.v")
        R.sysMsgItem = Reflect.load("com.tencent.mm.ui.chatting.viewitems.mn")
        R.sysMsgTemplate = Reflect.load("com.tencent.mm.ui.chatting.viewitems.rn")
        R.sysMsgGen = Reflect.load("go3.e")?.let { Reflect.method(it, "gj", 5) }
        R.adapter = Reflect.load("com.tencent.mm.ui.chatting.adapter.k")
        R.rvAdapter = Reflect.load(C.RVADPT)
        R.baseHolder = Reflect.load("com.tencent.mm.ui.chatting.viewitems.h0")
        R.anim = Reflect.load(C.ANIM); R.neat = Reflect.load(C.NEAT)
        R.chatBgAttr = Reflect.load("com.tencent.mm.pluginsdk.ui.i0")
        R.c2cUtil = Reflect.load("com.tencent.mm.ui.chatting.z1")
        R.hbItem = Reflect.load("com.tencent.mm.ui.chatting.viewitems.d4")
        R.msgQuote = Reflect.load("com.tencent.mm.plugin.msgquote.model.MsgQuoteItem")
        R.quoteImpls = emptyList()
    }

    private fun resolveDerived() {
        R.voiceFill = R.voiceFill ?: R.voiceHolder?.let { Reflect.method(it, "e", 9) }
        R.textBubbleSetter = R.textBubbleSetter ?: R.textHolder?.let { Reflect.method(it, "b", 4) }
        R.sysMsgFill = R.sysMsgFill ?: R.sysMsgItem?.let { Reflect.method(it, "n", 4) }
    }

    fun validate(): Boolean =
        R.voiceHolder != null && R.voiceFill != null && R.textHolder != null &&
        R.textBubbleSetter != null && R.adapter != null && R.rvAdapter != null &&
        R.anim != null && R.neat != null && R.baseHolder != null

    fun dump() = log("resolved ver=${WxEnv.verTag}\n" +
        "  voice from=${R.voiceItemFrom?.name} to=${R.voiceItemTo?.name} " +
        "mvvm=${R.voiceItemFromMvvm?.name}/${R.voiceItemToMvvm?.name}\n" +
        "  holder voice=${R.voiceHolder?.name} text=${R.textHolder?.name} base=${R.baseHolder?.name}\n" +
        "  fill voice=${R.voiceFill?.name} textBubble=${R.textBubbleSetter?.name}\n" +
        "  sys item=${R.sysMsgItem?.name} fill=${R.sysMsgFill?.name} tmpl=${R.sysMsgTemplate?.name} gen=${R.sysMsgGen?.name}\n" +
        "  adapter=${R.adapter?.name} rv=${R.rvAdapter?.name} msg=${R.msgInfo?.name}\n" +
        "  chatBg=${R.chatBgAttr?.name} c2c=${R.c2cUtil?.name} quote=${R.quoteImpls.size}")
}

// ============ 4. 消息访问 / 方向判定 ============
object MsgAccess {
    fun msgOf(holder: Any?): Any? = Reflect.fieldPath(holder, "i", "d", "b")   // am5.d→d→b=e9
    fun itemViewOf(holder: Any?): View? =
        holder?.let { XposedHelpers.getObjectField(it, "itemView") as? View }
    fun isSend(msg: Any?): Boolean = (Reflect.callByName(msg, "z0", 0) as? Int ?: 0) != 0
    fun fromSide(msg: Any?): Boolean = !isSend(msg)
    fun createTime(msg: Any?): Long = Reflect.callByName(msg, "getCreateTime", 0) as? Long ?: 0L
    fun msgType(msg: Any?): Int = Reflect.callByName(msg, "getType", 0) as? Int ?: 0
    fun talker(msg: Any?): String? = Reflect.callByName(msg, "N0", 0) as? String
    /** 从 ps/ap Tag 取消息（ps.c() smali：a→d→b） */
    fun msgOfTag(tag: Any?): Any? = Reflect.callByName(tag, "c", 0)
}

object Direction {
    fun fromChatItem(item: Any?): Boolean? {
        if (item == null) return null
        val c = item.javaClass
        R.voiceItemFrom?.let { if (it.isAssignableFrom(c)) return true }
        R.voiceItemTo?.let { if (it.isAssignableFrom(c)) return false }
        return null
    }
    fun chatItemOf(holder: Any?): Any? = Reflect.fieldPath(holder, "i", "i")
    fun fromHolder(holder: Any?): Boolean =
        fromChatItem(chatItemOf(holder)) ?: MsgAccess.fromSide(MsgAccess.msgOf(holder))
}

// ============ 5. 注入目标识别 ============
object Injectable {
    private val BANNED = setOf(
        "d",        // mq.d 2131366097 ★透明点击热区，严禁替换
        "c", "s",   // 副文本 / 秒数文本
        "o", "p", "z", "C", "B", "q", "r", "y", "t", "w", "f", "g"
    )
    fun banned(n: String) = n in BANNED
    fun ok(v: View): Boolean {
        if (v is ViewGroup) return false
        val bg = v.background ?: return false
        if (v.width <= 0 || v.height <= 0) return false
        if (bg.intrinsicWidth <= 0 || bg.intrinsicHeight <= 0) return false
        if (v is TextView && v.text.isNullOrEmpty() && v.isClickable && v.isLongClickable) return false
        return true
    }
    fun nameOf(holder: Any, v: View): String? =
        Reflect.allFields(holder.javaClass).firstOrNull { f ->
            f.isAccessible = true; runCatching { f.get(holder) }.getOrNull() === v }?.name
}

// ============ 6. 气泡 Drawable 工厂 ============
object BubbleFactory {
    private var res: Resources? = null
    fun init() {
        if (WxEnv.modulePath.isEmpty()) return
        runCatching {
            val xr = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("de.robv.android.xposed.XModuleResources", null),
                "createForXposed", WxEnv.modulePath)
            res = xr as? Resources
        }
    }
    fun create(ctx: Context, from: Boolean, voice: Boolean, orig: Drawable?): Drawable {
        res?.let { r ->
            val id = rid(r, when {
                voice && from -> "wx_bubble_voice_from"
                voice -> "wx_bubble_voice_to"
                from -> "wx_bubble_text_from"
                else -> "wx_bubble_text_to"
            })
            if (id != 0) runCatching { return r.getDrawable(id, ctx.theme) }
        }
        return programmatic(ctx, from, voice)
    }
    private fun rid(r: Resources, n: String): Int = runCatching {
        (r.javaClass.getMethod("getIdentifier", String::class.java, String::class.java, String::class.java)
            .invoke(r, n, "drawable", PKG) as? Int) ?: 0 }.getOrDefault(0)
    private fun programmatic(ctx: Context, from: Boolean, voice: Boolean): Drawable {
        val dm = ctx.resources.displayMetrics
        fun dp(x: Float) = x * dm.density + 0.5f
        val r = dp(if (voice) 9f else 7f)
        val body = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(r, r, r, r, r, r, r, r)
            setColor(if (from) 0xFFFFFFFF.toInt() else 0xFF95EC69.toInt())
            setStroke(dp(0.6f).toInt(), 0x1A000000)
        }
        val ts = dp(if (voice) 7f else 6f)
        val p = Path().apply {
            if (from) { moveTo(0f, 0f); lineTo(ts, 0f); lineTo(0f, ts); close() }
            else { moveTo(0f, 0f); lineTo(ts, 0f); lineTo(ts, ts); close() }
        }
        val tail = ShapeDrawable(PathShape(p, ts, ts)).apply {
            paint.color = if (from) 0xFFFFFFFF.toInt() else 0xFF95EC69.toInt()
            intrinsicWidth = ts.toInt(); intrinsicHeight = ts.toInt()
        }
        return LayerDrawable(arrayOf(body, tail))
    }
}

// ============ 7. ★ Replacer：完整替换核心 ============
object Replacer {
    /** 动态识别出的"微信气泡资源 id"（版本自适应，不写死） */
    val bubbleIds: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    /** id → (from, voice)，首次见到该 id 时从气泡 View 反推并记住 */
    private val idMeta = ConcurrentHashMap<Int, Pair<Boolean, Boolean>>()

    /** 自定义气泡工厂：返回 null = 不替换 */
    var factory: ((Context, from: Boolean, voice: Boolean, orig: Drawable?) -> Drawable?)? = null

    private var ctxRef: WeakReference<Context>? = null
    fun ctx(): Context? = ctxRef?.get()
    fun setCtx(c: Context?) { if (c != null) ctxRef = WeakReference(c) }

    fun install() {
        // ---------- R1：Resources#getDrawable / getDrawableForDensity（共 4 个重载）----------
        // 依据：ke5.a.i() 走 getDrawable(int)；el.d()/View#setBackgroundResource/LayoutInflater 走 (int,Theme)；
        //       le5.j 可能走 getDrawableForDensity。四个重载全部拦截，确保无漏网路径。
        val res = Reflect.load(C.RES) ?: run { log("Resources 类找不到"); return }
        listOf("getDrawable", "getDrawableForDensity").forEach { mName ->
            XposedBridge.hookAllMethods(res, mName, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val id = p.args[0] as? Int ?: return
                    if (id !in bubbleIds) return                    // O(1)，不影响其它资源
                    val orig = p.result as? Drawable ?: return
                    val c = ctx() ?: return
                    val (from, voice) = idMeta[id] ?: (true to false)
                    val ours = factory?.invoke(c, from, voice, orig) ?: return
                    cp(orig, ours); ours.bounds = orig.bounds
                    p.result = ours                                 // ★完整替换
                }
            })
        }

        // ---------- R2：View#setBackgroundDrawable（缓存绕过 R1 时）----------
        XposedBridge.hookAllMethods(View::class.java, "setBackgroundDrawable", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                if (BubbleEngine.writing.get()) return
                val v = p.thisObject as? View ?: return
                val d = p.args[0] as? Drawable ?: return
                if (!Injectable.ok(v) || !looksWx(d)) return
                val c = ctx() ?: v.context
                val ours = factory?.invoke(c, sideOf(v), false, d) ?: return
                cp(d, ours); ours.bounds = d.bounds
                p.args[0] = ours
            }
        })

        // ---------- 收集气泡 id + 首次即时替换（避免第一帧闪原生）----------
        XposedBridge.hookAllMethods(View::class.java, "setBackgroundResource", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val v = p.thisObject as? View ?: return
                val id = p.args[0] as? Int ?: return
                if (id == 0 || !Injectable.ok(v)) return
                val isNew = bubbleIds.add(id)
                idMeta.putIfAbsent(id, metaOf(v))
                if (!isNew) return                               // 已收集 → 交给 R1
                val c = ctx() ?: v.context
                val (from, voice) = idMeta[id] ?: (true to false)
                val orig = v.background
                val ours = factory?.invoke(c, from, voice, orig) ?: return
                orig?.let { cp(it, ours); ours.bounds = it.bounds }
                BubbleEngine.applyDrawable(v, ours)
                p.result = Unit                                  // 跳过微信原本的设置
                log("R1 first replace id=0x${Integer.toHexString(id)} " +
                    "view=${v.javaClass.simpleName} from=$from voice=$voice")
            }
        })
    }

    /** 从气泡 View 反推 (from, voice) */
    private fun metaOf(v: View): Pair<Boolean, Boolean> {
        val tag = itemRootOf(v)?.tag
        val voice = tag != null && R.voiceHolder != null && tag.javaClass.name == R.voiceHolder!!.name
        return sideOf(v) to voice
    }

    /** from 判定：itemView.tag → 字段 d(热区)/b(正文) → tag(ps/ap) → c() → MsgInfo → z0()==0 */
    fun sideOf(v: View): Boolean {
        val tag = itemRootOf(v)?.tag ?: return true
        for (f in listOf("d", "b")) {
            val inner = Reflect.fieldByName(tag, f) ?: continue
            val ps = (inner as? View)?.tag ?: continue
            val msg = MsgAccess.msgOfTag(ps) ?: continue
            return MsgAccess.fromSide(msg)
        }
        return true
    }

    private fun itemRootOf(v: View): View? {
        var cur: View = v; var g = 0
        while (cur.parent is ViewGroup && g++ < 12) {
            val p = cur.parent as ViewGroup
            if (p.javaClass.name.contains("RecyclerView") ||
                p.javaClass.name.contains("ListView")) return cur
            cur = p
        }
        return cur
    }

    private fun looksWx(d: Drawable): Boolean {
        val n = d.javaClass.name
        return (n.contains("NinePatch") || n.contains("Bitmap") || n.contains("Gradient") ||
                n.contains("Layer") || n.contains("Shape")) &&
               d.intrinsicWidth in 100..4000 && d.intrinsicHeight in 40..600
    }

    private fun cp(from: Drawable, to: Drawable) {
        val r = Rect(); if (from.getPadding(r)) {
            (to as? android.graphics.drawable.GradientDrawable)?.setPadding(r.left, r.top, r.right, r.bottom)
        }
    }
}

// ============ 8. BubbleEngine：识别 / 登记 / 尺寸 / 回收 / 自愈 ============
private fun padL(d: Drawable): Int { val r = Rect(); d.getPadding(r); return r.left }
private fun padR(d: Drawable): Int { val r = Rect(); d.getPadding(r); return r.right }
object BubbleEngine {
    val writing = ThreadLocal.withInitial { false }

    private class Info(var original: Drawable?, var appliedFrom: Boolean, val voice: Boolean,
                       var padL: Int = 0, var padR: Int = 0)
    private val reg = WeakHashMap<View, Info>()
    private val owners = WeakHashMap<View, MutableSet<View>>()

    /** L1：识别为气泡 → 登记 → 直接换成我们的 → 尺寸补偿 */
    fun applySkin(v: View, from: Boolean, voice: Boolean) {
        if (!Injectable.ok(v)) return
        val orig = v.background ?: return
        val ours = BubbleFactory.create(v.context, from, voice, orig)
        val info = reg.getOrPut(v) { Info(orig, from, voice) }
        info.original = orig; info.appliedFrom = from
        info.padL = padL(ours) - padL(orig); info.padR = padR(ours) - padR(orig)
        applyDrawable(v, ours)
        val lp = v.layoutParams ?: return
        if (v.width > 0) { lp.width = v.width + info.padL + info.padR; v.layoutParams = lp }
        registerOwner(v)
    }

    /** L1b：bind 兜底（完整 bind + 局部刷新都会调） */
    fun ensureBubble(adapterThis: Any, holderArg: Any?) {
        if (adapterThis.javaClass.name != R.adapter?.name) return
        val holder = holderArg ?: return
        val itemView = MsgAccess.itemViewOf(holder) ?: return
        val from = Direction.fromHolder(holder)
        val set = owners[itemView]
        if (!set.isNullOrEmpty()) {
            set.toList().forEach { v ->
                val info = reg[v] ?: return@forEach
                if (from != info.appliedFrom) applySkin(v, from, info.voice) else selfHeal(v)
            }
            QuoteEngine.injectInto(itemView, from)
            TimelineEngine.apply(holder, itemView, from)
            return
        }
        val tag = itemView.tag ?: return
        when (tag.javaClass.name) {
            R.voiceHolder?.name -> {
                val t = LinkedHashSet<View>()
                Reflect.fieldsByType(tag, C.ANIM).forEach { if (it is View) t += it }
                Reflect.allFields(tag.javaClass).forEach { fd ->
                    if (fd.name == "x" && fd.type.name == "android.widget.TextView") {
                        fd.isAccessible = true; (fd.get(tag) as? View)?.let { t += it }
                    }
                }
                t.filter { Injectable.nameOf(tag, it)?.let { n -> !Injectable.banned(n) } ?: true }
                 .filter { Injectable.ok(it) }
                 .forEach { applySkin(it, from, true) }
            }
            R.textHolder?.name -> {
                (Reflect.fieldByType(tag, C.NEAT) as? View)?.let { applySkin(it, from, false) }
            }
            else -> restoreAll(itemView)
        }
        QuoteEngine.injectInto(itemView, from)
        TimelineEngine.apply(holder, itemView, from)
    }

    fun applyDrawable(v: View, d: Drawable?) {
        writing.set(true); try { v.background = d } finally { writing.set(false) }
    }
    fun isRegistered(v: View) = reg.containsKey(v)

    /** L3：尺寸补偿 */
    fun padDeltaDp(c: Context): Int = (padDeltaPx() / c.resources.displayMetrics.density).toInt()
    private fun padDeltaPx(): Int = reg.values.firstOrNull()?.let { it.padL + it.padR } ?: 0
    fun compensateWidth(v: View?, origPx: Int) {
        val info = reg[v ?: return] ?: return
        val lp = v.layoutParams ?: return
        lp.width = origPx + info.padL + info.padR; v.layoutParams = lp
    }
    fun compensateTextMax(v: View?, orig: Int) {
        val info = reg[v ?: return] ?: return
        (v as? TextView)?.maxWidth = orig + info.padL + info.padR
    }

    /** L4：回收/离屏还原 + 反注册 */
    fun restoreAll(itemView: View) {
        owners.remove(itemView)?.forEach { v -> reg.remove(v)?.let { applyDrawable(v, it.original) } }
    }
    fun recycle(holder: Any?) { MsgAccess.itemViewOf(holder)?.let { restoreAll(it) } }

    /** 自愈：登记过的 View 不可见/零宽 → 还原，避免复用闪现 */
    fun selfHeal(v: View) {
        val info = reg[v] ?: return
        if (v.visibility != View.VISIBLE || v.width == 0) applyDrawable(v, info.original)
    }

    private fun registerOwner(v: View) {
        itemRootOf(v)?.let { owners.getOrPut(it) { LinkedHashSet() }.add(v) }
    }
    private fun itemRootOf(v: View): View? {
        var cur: View = v; var g = 0
        while (cur.parent is ViewGroup && g++ < 12) {
            val p = cur.parent as ViewGroup
            val n = p.javaClass.name
            if (n.contains("RecyclerView") || n.contains("ListView")) return cur
            cur = p
        }
        return cur
    }
    private fun pad(d: Drawable, left: Boolean): Int {
        val r = Rect(); return if (d.getPadding(r)) (if (left) r.left else r.right) else 0
    }
}

// ============ 9. 引用气泡引擎 ============
object QuoteEngine {
    fun install() {
        val impls = R.quoteImpls
        if (impls.isEmpty()) { log("quoteImpls 为空，引用气泡注入跳过"); return }
        impls.forEach { c ->
            XposedBridge.hookAllMethods(c, "b", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val root = p.result as? View ?: return
                    if (root.background == null || !Injectable.ok(root)) return
                    BubbleEngine.applySkin(root, Replacer.sideOf(root), false)
                }
            })
        }
        log("quote hooks=${impls.size}")
    }
    fun injectInto(itemView: View, from: Boolean) {
        walk(itemView, 0) { v ->
            if (BubbleEngine.isRegistered(v)) return@walk
            if (!Injectable.ok(v)) return@walk
            if (((v.parent as? ViewGroup)?.childCount ?: 0) < 2) return@walk
            BubbleEngine.applySkin(v, from, false)
        }
    }
    private fun walk(v: View, d: Int, b: (View) -> Unit) {
        if (d > 5) return
        b(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), d + 1, b)
    }
}

// ============ 10. 系统提示引擎 ============
object SysTipEngine {
    var formatter: ((Any?, CharSequence?) -> CharSequence?)? = null
    fun install() {
        R.sysMsgFill?.let { m ->
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val holder = p.args.getOrNull(0) ?: return
                    val tv = Reflect.fieldByType(holder, C.NEAT) as? TextView ?: return
                    val msg = Reflect.fieldPath(p.args.getOrNull(2), "d", "b")
                    val custom = formatter?.invoke(msg, tv.text) ?: return
                    tv.setText(custom, TextView.BufferType.SPANNABLE)
                }
            })
        }
        R.sysMsgGen?.let { m ->
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val raw = p.result as? CharSequence ?: return
                    p.result = formatter?.invoke(null, raw) ?: return
                }
            })
        }
        R.chatBgAttr?.let { c ->
            XposedBridge.hookAllMethods(c, "<init>", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val o = p.thisObject
                    XposedHelpers.setBooleanField(o, "f", true)      // 秒数加阴影
                    XposedHelpers.setIntField(o, "g", 0xCCFFFFFF.toInt())    // 阴影色
                    XposedHelpers.setBooleanField(o, "h", true)      // 秒数加背景
                }
            })
        }
    }
}

// ============ 11. 红包 / 转账 ============
object HkRedPacket {
    val HB_SET = setOf(2131231684, 2131231689, 2131231695, 2131231697, 2131231702, 2131231708,
                       2131230737, 2131230744, 2131230745, 2131230746, 2131230753, 2131230754)
    var replaceResId = 0
    fun install() {
        if (replaceResId == 0) return
        val z = R.c2cUtil ?: return
        listOf("c" to 2, "h" to 3).forEach { (n, pc) ->
            Reflect.method(z, n, pc)?.let { m ->
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        if ((p.result as? Int) in HB_SET) p.result = replaceResId
                    }
                })
            }
        }
    }
}

// ============ 12. 时间线引擎 ============
object TimelineEngine {
    var enabled = true
    var everyMessage = true
    var intervalMs = 5 * 60_000L
    var format: (Long) -> String = { ms ->
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))
    }
    private val shown = WeakHashMap<View, Long>()
    private val added = WeakHashMap<View, TextView>()
    private val lastShown = HashMap<String, Long>()

    fun apply(holder: Any?, itemView: View, from: Boolean) {
        if (!enabled) return
        val tag = itemView.tag ?: return
        val timeTV = Reflect.fieldByName(tag, "timeTV") as? TextView
        val msg = MsgAccess.msgOf(holder)
        val ct = MsgAccess.createTime(msg)
        if (ct <= 0) return
        if (!shouldShow(MsgAccess.talker(msg), ct)) { timeTV?.visibility = View.GONE; return }
        if (timeTV == null) { addFallback(itemView, ct); return }
        if (shown[timeTV] == ct) return
        shown[timeTV] = ct
        timeTV.visibility = View.VISIBLE
        timeTV.text = format(ct)
    }

    private fun shouldShow(talker: String?, ct: Long): Boolean {
        if (everyMessage) return true
        val key = talker ?: ""
        val last = lastShown[key] ?: run { lastShown[key] = ct; return true }
        if (ct - last >= intervalMs) { lastShown[key] = ct; return true }
        return false
    }

    private fun addFallback(itemView: View, ct: Long) {
        added[itemView]?.let { it.text = format(ct); return }
        val host = itemView as? ViewGroup ?: return
        val tv = TextView(itemView.context).apply {
            textSize = 11f; setTextColor(0xFF9B9B9B.toInt()); text = format(ct)
        }
        added[itemView] = tv
        host.addView(tv, 0)
    }

    fun disable(holder: Any?) {
        val itemView = MsgAccess.itemViewOf(holder) ?: return
        (itemView.tag?.let { Reflect.fieldByName(it, "timeTV") } as? TextView)?.visibility = View.GONE
        added.remove(itemView)?.let { (itemView as? ViewGroup)?.removeView(it) }
    }
}

// ============ 13. 挂载 ============
object Hk {
    fun install() {
        val rv = R.rvAdapter!!
        val ad = R.adapter!!

        // ---- L1-a 文本气泡 ----
        XposedBridge.hookMethod(R.textBubbleSetter!!, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val holder = p.args.getOrNull(1) ?: return
                val v = Reflect.fieldByType(holder, C.NEAT) as? View ?: return
                Replacer.setCtx(v.context)
                val from = (p.args.getOrNull(3) as? Boolean) ?: MsgAccess.fromSide(MsgAccess.msgOf(holder))
                BubbleEngine.applySkin(v, from, voice = false)
            }
        })

        // ---- L1-b 语音填充（覆盖 bq/iq/tr/ur）----
        XposedBridge.hookMethod(R.voiceFill!!, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val holder = p.args.getOrNull(1) ?: return
                val from = Direction.fromHolder(holder)
                MsgAccess.itemViewOf(holder)?.let { Replacer.setCtx(it.context) }
                val t = LinkedHashSet<View>()
                Reflect.fieldsByType(holder, C.ANIM).forEach { if (it is View) t += it }
                Reflect.allFields(holder.javaClass).forEach { fd ->
                    if (fd.name == "x" && fd.type.name == "android.widget.TextView") {
                        fd.isAccessible = true; (fd.get(holder) as? View)?.let { t += it }
                    }
                }
                t.filter { Injectable.nameOf(holder, it)?.let { n -> !Injectable.banned(n) } ?: true }
                 .filter { Injectable.ok(it) }
                 .forEach { BubbleEngine.applySkin(it, from, voice = true) }
            }
        })

        // ---- L1b 兜底 ----
        val ensure = object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                BubbleEngine.ensureBubble(p.thisObject, p.args.getOrNull(0))
            }
        }
        XposedBridge.hookAllMethods(rv, "E0", ensure)
        XposedBridge.hookAllMethods(rv, "F0", ensure)

        // ---- R1/R2 完整替换 ----
        Replacer.install()

        // ---- L3 尺寸补偿 ----
        Reflect.method(R.voiceHolder ?: return, "c", 2)?.let { m ->
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val base = p.result as? Int ?: return
                    val c = p.args.getOrNull(0) as? Context ?: return
                    p.result = (base + BubbleEngine.padDeltaDp(c)).coerceAtLeast(80)
                }
            })
        }
        XposedBridge.hookAllMethods(R.baseHolder!!, "resetChatBubbleWidth", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                BubbleEngine.compensateWidth(p.args[0] as? View, p.args[1] as? Int ?: return)
            }
        })
        XposedBridge.hookAllMethods(R.neat!!, "setMaxWidth", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                BubbleEngine.compensateTextMax(p.thisObject as? View, p.args[1] as? Int ?: return)
            }
        })

        // ---- L4 回收 ----
        val clean = object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) { BubbleEngine.recycle(p.args.getOrNull(0)) }
        }
        XposedBridge.hookAllMethods(rv, "onViewRecycled", clean)
        XposedBridge.hookAllMethods(rv, "onViewDetachedFromWindow", clean)

        // ---- 引擎 ----
        SysTipEngine.install()
        HkRedPacket.install()
        QuoteEngine.install()

        log("hooks installed. ver=${WxEnv.verTag}")
    }
}

// ============ 14. 调试 ============
object Dbg {
    fun dumpVoiceRow(holder: Any) {
        val root = MsgAccess.itemViewOf(holder) ?: return
        val sb = StringBuilder()
        fun walk(v: View, d: Int) {
            val bg = v.background
            val r = Rect(); val hp = bg?.getPadding(r) == true
            sb.append(" ".repeat(d * 2)).append(
                "${v.javaClass.simpleName} id=${v.id} [${v.left},${v.top}] w=${v.width} h=${v.height} " +
                "vis=${v.visibility} bg=${bg?.javaClass?.simpleName}" +
                "${if (bg != null) "(${bg.intrinsicWidth}x${bg.intrinsicHeight})" else ""} " +
                "${if (hp) "pad(${r.left},${r.top},${r.right},${r.bottom})" else ""} " +
                "${if (v is TextView) "text='${v.text}' " else ""} " +
                "click=${v.isClickable} long=${v.isLongClickable}\n")
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), d + 1)
        }
        walk(root, 0)
        log("VOICE-ROW-TREE\n$sb")
    }
    fun dumpMetrics(v: View): String {
        val bg = v.background
        val r = Rect(); val ok = bg?.getPadding(r) == true
        val lp = v.layoutParams
        return "cls=${v.javaClass.simpleName} id=${v.id} w=${v.width} h=${v.height} " +
               "lpW=${lp?.width} lpH=${lp?.height} intrinsic=${bg?.intrinsicWidth}x${bg?.intrinsicHeight} " +
               "padding=${if (ok) "${r.left},${r.top},${r.right},${r.bottom}" else "none"} " +
               "bgCls=${bg?.javaClass?.name}"
    }
}
