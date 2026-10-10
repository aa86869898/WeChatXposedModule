package com.example.wxbubble

import android.app.Activity
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import com.leshao.v3.LogWriter
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * ★ 全新方案：底层直接替换（before-set 参数替换）—— 不做 View 级叠加、不碰布局、不越界。
 *
 * 原理：
 *  微信设置气泡最终都会调用 View#setBackground(Drawable)（或子类重写的 setBackground）。
 *  我们在 【beforeHookedMethod】 阶段，把微信即将设置的「原生气泡 drawable」直接替换成
 *  用户的自定义气泡 drawable（保留原生 padding/intrinsic）。
 *  → 微信设置的本身就是我们的气泡：单次 set、无二次叠加、无布局改动 → 零错位、零越界。
 *
 * 与旧「贴皮」的区别：
 *  - 旧贴皮：afterHookedMethod 再 setBackground 一次（二次设置 → 错位/闪现）。
 *  - 本方案：beforeHookedMethod 替换参数（微信只 set 一次，且 set 的就是我们的）。
 *
 * 覆盖：
 *  - View.setBackground / setBackgroundDrawable（文本/图片/卡片等）
 *  - MMNeat7extView.setBackground（文本气泡重写）
 *  - AnimImageView.setBackground（语音气泡重写）
 *
 * 方向：用消息实体 e9.z0()（isSend）判定，权威可靠。
 */
object WxBubbleDirectReplace {

    private var appContext: Context? = null
    private val replacedCount = ConcurrentHashMap<String, Int>()   // 用于诊断日志（按视图类计数）
    fun setAppContext(c: Context?) { if (c != null) appContext = c }

    private val currentActivity = AtomicReference<Activity?>()

    fun install(cl: ClassLoader) {
        trackForegroundActivity()

        // 1) 通用 View.setBackground / setBackgroundDrawable
        hookSetBackground(View::class.java, "setBackground")
        hookSetBackground(View::class.java, "setBackgroundDrawable")

        // 2) 文本气泡视图重写
        runCatching {
            val neat = Reflect.load("Lcom/tencent/mm/ui/widget/MMNeat7extView;")
            if (neat != null) hookSetBackground(neat, "setBackground")
        }
        // 3) 语音气泡视图重写（AnimImageView 当前版本不重写 setBackground，此兜底为未来版本预留）
        runCatching {
            val anim = Reflect.load("Lcom/tencent/mm/ui/base/AnimImageView;")
            if (anim != null) {
                hookSetBackground(anim, "setBackground")
                // ★ 语音状态补盖：微信 setType 切换状态（播放/发送/清空）后，把我们的气泡重新盖回去，
                //   解决「发送中/播放中 setType(3) 清背景 → 气泡一闪而过消失」。
                XposedBridge.hookAllMethods(anim, "setType", object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        try {
                            val v = p.thisObject as? View ?: return
                            if (!isVoiceCarrier(v)) return
                            if (!inChatItem(v)) return
                            // 接收侧 mq.u(6096) 占位层永不补盖
                            if (v.id == 2131366096) return
                            // 发送侧 mq.x 方向强制为 false
                            val from = if (v.id == 2131366108) false else directionOf(v)
                            val cur = v.background
                            if (cur != null && isOurs(cur)) return   // 已经是我们
                            val ctx = appContext ?: v.context
                            val ours = BubbleFactory.create(ctx, from, true, cur) ?: return
                            if (cur != null) ours.bounds = cur.bounds
                            // 只在视图可见/已 attach 时补盖，避免干扰回收复用
                            if (v.isAttachedToWindow && v.visibility == View.VISIBLE) {
                                v.background = ours
                                val cls = v.javaClass.name
                                val cnt = replacedCount.merge("voice-reapply:" + cls, 1, Int::plus) ?: 1
                                if (cnt <= 5) LogWriter.log("Bubble", "voice-reapply view=" + cls + " from=" + from)
                            }
                            // ★ 决定性：setType 一定触发，这里对整条语音 item 全层替换所有气泡背景。
                            //   不依赖 R.voiceFill 是否解析成功，确保表层/内层都被换掉。
                            val itemRoot = itemRootOf(v) ?: return
                            replaceAllBubbleLayers(itemRoot, ctx ?: v.context, from)
                        } catch (_: Throwable) {}
                    }
                })
            }
        }

        // ★ 语音「表层」气泡容器补盖：语音消息表面气泡在 mq.o(FrameLayout 2131366098)，
        //   由布局 XML 设置，运行时 setBackground 不会触发 before 替换 → 必须在语音绑定点 after 强制设上。
        runCatching {
            val fill = R.voiceFill
            if (fill != null) {
                XposedBridge.hookMethod(fill, object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        try {
                            val holder = p.args.getOrNull(1) ?: return       // mq holder
                            val itemView = MsgAccess.itemViewOf(holder) ?: return
                            val from = directionOf(itemView)                      // 整条语音消息方向
                            val ctx = appContext ?: itemView.context
                            // ★ 决定性方案：把整条语音 item 内「所有带气泡背景的视图」全部替换成自定义气泡。
                            //   表层/内层/波形层一次性全换，不再猜哪一层是表面。
                            replaceAllBubbleLayers(itemView, ctx, from)
                        } catch (_: Throwable) {}
                    }
                })
            }
        }

        LogWriter.log("Bubble", "WxBubbleDirectReplace install done")
        XposedBridge.log("[WxBubbleDirect] install done")
    }

    /** ★ 递归替换整棵视图树里所有「气泡类背景」的视图（语音 item 内全层替换） */
    private fun replaceAllBubbleLayers(root: View, ctx: Context?, from: Boolean) {
        if (root.background != null && looksLikeBubble(root.background) && !isOurs(root.background)) {
            val ours = BubbleFactory.create(ctx ?: return, from, true, root.background) ?: return
            ours.bounds = root.background!!.bounds
            root.background = ours
            LogWriter.log("Bubble", "voice-surface view=" + root.javaClass.name + " from=" + from)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                replaceAllBubbleLayers(root.getChildAt(i), ctx, from)
            }
        }
    }

    /** 跟踪当前前台 Activity（判断是否在聊天窗口内） */
    private fun trackForegroundActivity() {
        XposedBridge.hookAllMethods(Activity::class.java, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) { currentActivity.set(p.thisObject as? Activity) }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onPause", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                if (currentActivity.get() === p.thisObject) currentActivity.set(null)
            }
        })
    }

    /** 当前前台是否在聊天窗口 */
    private fun isChatWindowForeground(): Boolean {
        val a = currentActivity.get() ?: return false
        val n = a.javaClass.name
        return n.contains("ChattingUI") || n.contains("chatting", ignoreCase = true)
    }

    private fun hookSetBackground(c: Class<*>, mName: String) {
        LogWriter.log("Bubble", "hook " + c.name + "." + mName + " installed")
        XposedBridge.hookAllMethods(c, mName, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                try {
                    val v = p.thisObject as? View ?: return
                    if (!inChatItem(v)) return                    // 只处理聊天列表内
                    val d = p.args[0] as? Drawable ?: return
                    if (!looksLikeBubble(d)) return                // 只替换气泡类背景
                    if (isOurs(d)) return                          // 已替换过，避免二次包装
                    val vid = v.id
                    // ★ 语音 item 精准白名单（依据 mq.b 反编译实锤）：
                    //   mq.e(6091)=真承载（收/发都替换）；mq.x(6108)=恒为发送侧气泡背景（无条件替换）；
                    //   mq.u(6096)=接收侧占位层（永不替换）；其它时长/秒数/转文字一律不替换。
                    var from = directionOf(v)                                // true=对方/收
                    if (isVoiceItemView(v)) {
                        when (vid) {
                            2131366091 -> { /* mq.e 真承载 */ }
                            2131366108 -> { from = false }                    // mq.x 恒为发送侧
                            2131366096 -> return                              // mq.u 接收侧占位，永不替换
                            else -> return                                     // 其它视图不替换
                        }
                    }
                    val ctx = appContext ?: v.context
                    val ours = BubbleFactory.create(ctx, from, isVoiceCarrier(v), d) ?: return
                    // 保留原生 bounds/padding（withNativeMetrics 已保持 intrinsic 与 padding）
                    ours.bounds = d.bounds
                    p.args[0] = ours                                // ★ 微信即将 set 的就是我们的气泡
                    // ★ 初始渲染「不点不替换」修复：语音 item 在 bind 时背景可能尚未全部就绪（XML 表层容器
                    //   要到布局完成后才有背景），这里 post 到布局完成后，对整条语音 item 全层替换。
                    if (isVoiceItemView(v)) {
                        val f = from
                        val rootRef = itemRootOf(v)
                        v.post {
                            try {
                                val root = rootRef ?: itemRootOf(v) ?: return@post
                                replaceAllBubbleLayers(root, appContext ?: v.context, f)
                            } catch (_: Throwable) {}
                        }
                    }
                    val cls = v.javaClass.name
                    val cnt = replacedCount.merge(cls, 1, Int::plus) ?: 1
                    if (cnt <= 5) {                                  // 只打印前 5 次，防刷屏
                        LogWriter.log("Bubble", "direct-replace view=" + cls
                                + " from=" + from + " voice=" + isVoiceCarrier(v))
                    }
                } catch (_: Throwable) {}
            }
        })
    }

    // ---------- 方向判定（权威：消息实体 z0()） ----------
    /** true=对方/收到；false=自己/发出 */
    private fun directionOf(v: View): Boolean {
        // 语音：AnimImageView.e 字段 = isRecv（微信直供方向，权威；避免绑定期未 attach 导致默认成对方）
        if (v.javaClass.name.contains("AnimImageView")) {
            try { return XposedHelpers.getBooleanField(v, "e") } catch (t: Throwable) {}
        }
        val tag = itemRootOf(v)?.tag ?: return true
        val msg = runCatching { MsgAccess.msgOfTag(tag) }.getOrNull() ?: return true
        return MsgAccess.fromSide(msg)
    }

    // ---------- 判断是否语音气泡视图（用于选语音气泡图） ----------
    private fun isVoiceCarrier(v: View): Boolean {
        val id = v.id
        return id == 2131366091 || id == 2131366108 || id == 2131366096 ||
               v.javaClass.name.contains("AnimImageView")
    }

    /** 是否语音消息 item 内的视图：通过 item tag 是语音 holder，或视图是 AnimImageView/语音承载 id 判定 */
    private fun isVoiceItemView(v: View): Boolean {
        val tag = itemRootOf(v)?.tag
        if (tag != null && R.voiceHolder != null && tag.javaClass.name == R.voiceHolder!!.name) return true
        val n = v.javaClass.name
        return n.contains("AnimImageView") ||
               v.id == 2131366091 || v.id == 2131366108 || v.id == 2131366096
    }

    // ---------- 严格聊天列表判定（防首页越界） ----------
    private fun inChatItem(v: View): Boolean {
        // ① MMNeat7extView 只用于聊天文本气泡 → 无条件视为聊天
        if (v.javaClass.name.contains("MMNeat7extView")) return true
        // ①b 语音气泡承载 id（mq.e/mq.x/mq.u 只存在于聊天语音 item）→ 无条件视为聊天
        if (v.id == 2131366091 || v.id == 2131366108 || v.id == 2131366096) return true
        // ② 已 attach：父链是聊天列表（复用视图）
        if (chatListInAncestor(v)) return true
        // ③ 绑定期新视图：前台在聊天窗口 且 视图是聊天气泡视图 → 判定为聊天
        return isChatBubbleView(v) && isChatWindowForeground()
    }

    private fun chatListInAncestor(v: View): Boolean {
        var cur: View = v
        var depth = 0
        while (cur.parent is ViewGroup && depth++ < 12) {
            val p = cur.parent as ViewGroup
            val n = p.javaClass.name
            if (n.contains("MMChattingListView") || n.contains("ChattingListView") ||
                n.contains("chatting.view") || n.contains("chatting.viewitems")) return true
            if (n.contains("RecyclerView") || n.contains("ListView") || n.contains("ScrollView")) {
                val ad = runCatching {
                    when (p) {
                        is androidx.recyclerview.widget.RecyclerView -> p.adapter
                        is android.widget.AbsListView -> p.adapter
                        else -> null
                    }
                }.getOrNull()
                val an = ad?.javaClass?.name
                if (an != null && (an == R.adapter?.name || an == R.rvAdapter?.name)) return true
            }
            cur = p
        }
        return false
    }

    /** 聊天消息内容视图（文本 MMNeat7extView / 语音 AnimImageView / 其它 chatting 容器） */
    private fun isChatBubbleView(v: View): Boolean {
        val n = v.javaClass.name
        return n.contains("MMNeat7extView") || n.contains("AnimImageView") ||
               n.contains("chatting.viewitems") || n.contains("chatting.view")
    }

    private fun itemRootOf(v: View): View? {
        var cur: View = v; var g = 0
        while (cur.parent is ViewGroup && g++ < 12) {
            val p = cur.parent as ViewGroup
            if (p.javaClass.name.contains("RecyclerView") || p.javaClass.name.contains("ListView")) return cur
            cur = p
        }
        return cur
    }

    private fun looksLikeBubble(d: Drawable): Boolean {
        val n = d.javaClass.name
        return n.contains("NinePatch") || n.contains("StateList") || n.contains("Gradient") ||
               n.contains("Layer") || n.contains("Shape") ||
               (n.contains("Bitmap") && d.intrinsicWidth in 100..4000 && d.intrinsicHeight in 40..600)
    }

    private fun isOurs(d: Drawable): Boolean = d.javaClass.name.startsWith("com.example.wxbubble")
}
