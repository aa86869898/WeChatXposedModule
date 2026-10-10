package com.example.wxbubble

import android.app.Activity
import android.graphics.PorterDuff
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.leshao.v3.LogWriter
import com.leshao.v3.hook.ChatBubbleHook
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicReference

/**
 * ★ 独立模块：只给「语音气泡内的波形/播放图标」（AnimImageView 的 compound drawable）上色。
 *   绝不染色气泡背景、头像、秒数、其它文字 —— 避免整气泡变色。
 */
object WxVoiceContentColor {

    private val currentActivity = AtomicReference<Activity?>()

    fun install(cl: ClassLoader) {
        trackForegroundActivity()

        // ★ 核心拦截点：微信设置波形/播放图标 compound drawable 的那一刻，只染这些 drawable。
        runCatching {
            XposedBridge.hookAllMethods(TextView::class.java, "setCompoundDrawablesWithIntrinsicBounds",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        try {
                            val tv = p.thisObject as? TextView ?: return
                            // 只处理语音波形 AnimImageView（mq.e=6091 / mq.u=6096）
                            val id = tv.id
                            if (id != 2131366091 && id != 2131366096) return
                            val color = voiceColorOf(tv)
                            if (color == 0) return
                            for (i in 0 until 4) {
                                val d = p.args[i] as? Drawable ?: continue
                                tintRecursive(d, color)
                            }
                        } catch (_: Throwable) {}
                    }
                })
        }

        // 兜底：AnimImageView.b() 设置波形后，若上面没拦到，只染 compound drawable（不碰背景）
        runCatching {
            val anim = Reflect.load("Lcom/tencent/mm/ui/base/AnimImageView;")
            if (anim != null) {
                val m = anim.declaredMethods.firstOrNull { it.name == "b" && it.parameterTypes.isEmpty() }
                if (m != null) {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(p: MethodHookParam) {
                            try {
                                val v = p.thisObject as? View ?: return
                                tintOnlyCompound(v)
                            } catch (_: Throwable) {}
                        }
                    })
                }
            }
        }

        // 实时刷新：改色后刷新已渲染语音气泡的波形颜色
        runCatching {
            val cc = ChatBubbleHook::class.java
            XposedBridge.hookAllMethods(cc, "refreshRenderedColors", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    try { refreshRenderedWaveColor() } catch (_: Throwable) {}
                }
            })
        }

        LogWriter.log("Bubble", "WxVoiceContentColor install done")
    }

    /** 只染 AnimImageView 的 compound drawable（波形/图标），绝不碰 background 和子视图 */
    private fun tintOnlyCompound(v: View) {
        if (v !is TextView) return
        val color = voiceColorOf(v)
        if (color == 0) return
        for (d in v.compoundDrawables) tintRecursive(d, color)
    }

    /** 取当前方向（对方/自己）的配置颜色；0=不改 */
    private fun voiceColorOf(v: View): Int {
        val from = directionOf(v)
        val kind = if (from) ChatBubbleHook.KIND_FROM else ChatBubbleHook.KIND_TO
        return ChatBubbleHook.getTextColor(kind, ChatBubbleHook.currentTheme())
    }

    /** 递归给 drawable 着色（AnimationDrawable 逐帧） */
    private fun tintRecursive(d: Drawable?, color: Int) {
        if (d == null) return
        if (d is AnimationDrawable) {
            for (i in 0 until d.numberOfFrames) tintRecursive(d.getFrame(i), color)
        }
        d.setColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    /** 改色后遍历当前窗口，找到语音波形 AnimImageView 并重新上色 */
    fun refreshRenderedWaveColor() {
        val a = currentActivity.get() ?: return
        val decor = runCatching { a.window?.decorView }.getOrNull() ?: return
        walkFindWave(decor, 0)
    }

    private fun walkFindWave(v: View, depth: Int) {
        if (v == null || depth > 20) return
        val id = v.id
        if (id == 2131366091 || id == 2131366096) {
            tintOnlyCompound(v)
            return
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walkFindWave(v.getChildAt(i), depth + 1)
    }

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

    private fun directionOf(v: View): Boolean {
        if (v.javaClass.name.contains("AnimImageView")) {
            try { return XposedHelpers.getBooleanField(v, "e") } catch (t: Throwable) {}
        }
        val tag = itemRootOf(v)?.tag ?: return true
        val msg = runCatching { MsgAccess.msgOfTag(tag) }.getOrNull() ?: return true
        return MsgAccess.fromSide(msg)
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
}
