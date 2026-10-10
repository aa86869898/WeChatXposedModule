package com.example.wxbubble

import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 纯「资源级替换」气泡注入 —— 只替换 Resources 返回的 drawable，**不做任何 View 级修改**。
 *
 * v3.0.294（严格按三文件包 + 实测修复）：
 *  - 不预置文本普通/发送中/链接 id（全局替换会误伤聊天主页/会话列表图标）。
 *  - 只预置语音播放态专用 id（2131100638/2131100639）。
 *  - 观察收集覆盖三条路径：
 *      1) setBackgroundResource（文本走资源设置时）
 *      2) setBackgroundDrawable / setBackground（ke5.a.i / X2C 预构建直接设 Drawable 时，
 *         通过 drawable→id 反查登记）
 *  - 替换：Resources.getDrawable/getDrawableForDensity 全部重载 + ContextImpl.getDrawable 兜底。
 */
object WxBubbleResourceReplace {

    /** 已识别的气泡资源 id（动态收集 + 语音播放态预置） */
    private val bubbleIds: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    /** id -> (是否接收侧/对方, 是否语音) */
    private val idMeta = ConcurrentHashMap<Int, Pair<Boolean, Boolean>>()

    /** 语音播放/高亮专用 id（主页不会复用，可安全预置） */
    private val KNOWN_VOICE_PLAY = mapOf(
        2131100638 to (true to true),   // 语音-播放-对方
        2131100639 to (false to true)  // 语音-播放-自己
    )

    /** drawable → 资源 id（getDrawable 命中时记录，供 setBackgroundDrawable 反查登记） */
    private val drawableIdMap = Collections.synchronizedMap(WeakHashMap<Drawable, Int>())

    private var appContext: Context? = null
    fun setAppContext(c: Context?) { if (c != null) appContext = c }

    fun install(cl: ClassLoader) {
        seedKnownBubbleIds()
        installCollectionHook()
        installResourceReplacer()
        XposedBridge.log("[WxBubbleRes] install done, knownIds=" + bubbleIds.size)
    }

    // ---------- 预置：只预置语音播放态专用 id（首帧即替换，不误伤主页） ----------
    private fun seedKnownBubbleIds() {
        KNOWN_VOICE_PLAY.forEach { (id, meta) ->
            if (bubbleIds.add(id)) idMeta[id] = meta
        }
    }

    // ---------- 观察：只登记，绝不修改背景 ----------
    private fun installCollectionHook() {
        // 1) setBackgroundResource：文本/语音播放态走资源设置时直接登记 id
        XposedBridge.hookAllMethods(View::class.java, "setBackgroundResource", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                try {
                    val v = p.thisObject as? View ?: return
                    if (!inChatItem(v)) return
                    val id = p.args[0] as? Int ?: return
                    if (id == 0) return
                    if (bubbleIds.add(id)) idMeta[id] = metaOf(id, v)
                } catch (_: Throwable) {}
            }
        })

        // 2) setBackgroundDrawable / setBackground：ke5.a.i / X2C 预构建直接设 Drawable 时，
        //    通过 drawable→id 反查登记（语音普通态、预构建路径都能收集到）
        val byDrawable = object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                try {
                    val v = p.thisObject as? View ?: return
                    if (!inChatItem(v)) return
                    val d = p.args[0] as? Drawable ?: return
                    val id = drawableIdMap[d] ?: return
                    if (id == 0) return
                    if (bubbleIds.add(id)) idMeta[id] = metaOf(id, v)
                } catch (_: Throwable) {}
            }
        }
        XposedBridge.hookAllMethods(View::class.java, "setBackgroundDrawable", byDrawable)
        XposedBridge.hookAllMethods(View::class.java, "setBackground", byDrawable)
    }

    // ---------- 源头替换：Resources.getDrawable / getDrawableForDensity 全部重载 + ContextImpl 兜底 ----------
    private fun installResourceReplacer() {
        val res = try { Class.forName("android.content.res.Resources") } catch (t: Throwable) { return }
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                try {
                    val id = (p.args[0] as? Int) ?: return
                    val orig = p.result as? Drawable ?: return
                    drawableIdMap[orig] = id
                    if (id !in bubbleIds) return
                    if (isOurs(orig)) return
                    val (from, voice) = idMeta[id] ?: (true to false)
                    val ctx = appContext ?: return
                    val ours = BubbleFactory.create(ctx, from, voice, orig) ?: return
                    drawableIdMap[ours] = id
                    ours.bounds = orig.bounds
                    p.result = ours
                } catch (_: Throwable) {}
            }
        }
        listOf("getDrawable", "getDrawableForDensity").forEach { mName ->
            XposedBridge.hookAllMethods(res, mName, hook)
        }
        // ContextImpl.getDrawable 兜底（setBackgroundResource 的真正入口）
        runCatching {
            XposedBridge.hookAllMethods(Class.forName("android.app.ContextImpl"), "getDrawable", hook)
        }
    }

    // ---------- 工具 ----------

    private fun inChatItem(v: View): Boolean {
        var cur: View = v
        var depth = 0
        while (cur.parent is ViewGroup && depth++ < 12) {
            val p = cur.parent as ViewGroup
            val n = p.javaClass.name
            if (n.contains("RecyclerView") || n.contains("ListView") || n.contains("ChattingListView")) return true
            cur = p
        }
        return false
    }

    /** 方向：已知 id 直接按 id 定向；未知 id 用 view.tag 链反推，兜底 true(接收侧) */
    private fun metaOf(id: Int, v: View): Pair<Boolean, Boolean> {
        KNOWN_VOICE_PLAY[id]?.let { return it }
        val voice = try {
            val tag = itemRootOf(v)?.tag
            R.voiceHolder != null && tag != null && tag.javaClass.name == R.voiceHolder!!.name
        } catch (t: Throwable) { false }
        return (try { Replacer.sideOf(v) } catch (t: Throwable) { true }) to voice
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

    private fun isOurs(d: Drawable): Boolean = d.javaClass.name.startsWith("com.example.wxbubble")
}
