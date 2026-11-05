package com.leshao.v3.hook

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicReference

/**
 * ★ 权威文档第九/十一节：统一 hook userTV.setText + 网络同步后重刷，根治不稳定。
 *  - 任何情况下微信给昵称 setText 都触发 → 初始/复用/二次进入/大图返回自动恢复标签。
 *  - 本地读角色兜底「成员」，保证每个成员都显示。
 *  - 群成员详情网络响应（qn.w/qn.c0/l81.g0.onGYNetEnd）后延迟重刷已渲染昵称标签。
 */
object GroupTitleTagFix {

    private const val NICK_VIEW_ID = 2131366078   // h0.userTV

    /** 防递归：我们 setText 触发时跳过，避免死循环 */
    private val sApplying = ThreadLocal<Boolean>()

    private val currentActivity = AtomicReference<Activity?>()
    private val mainHandler = Handler(Looper.getMainLooper())
    /** 自动校准去重：同一 sender 只打印一次（避免刷屏） */
    private val sCalibPrinted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** ★ 群主/管理员显示"成员"修复：bind hook（可靠解析）把角色写入缓存，
     *   setText 统一插入点优先读缓存；取不到发送者时不兜底"成员"，避免误插。 */
    private val sRoleCache = java.util.WeakHashMap<TextView, String>()

    /** bind hook 解析到角色后写入缓存（唯一插入点 setText 优先读取）。 */
    fun cacheRole(tv: TextView, role: String) {
        synchronized(sRoleCache) { sRoleCache[tv] = role }
    }

    /** 统一插入入口：清洗旧标签后插入指定角色（bind 完成后立即纠正误插的"成员"）。 */
    fun refreshLabel(tv: TextView, role: String) {
        try {
            if (!GroupTitleTagHook.isEnabled()) return
            val cur = tv.text?.toString() ?: ""
            val prefix = " $role "
            if (cur.startsWith(prefix)) return
            applyLabel(tv, role, cur)
        } catch (_: Throwable) {}
    }

    fun hook(cl: ClassLoader) {
        trackForegroundActivity()

        // 核心：任何情况下微信给昵称 setText 都会触发
        XposedBridge.hookAllMethods(TextView::class.java, "setText", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                try {
                    if (!GroupTitleTagHook.isEnabled()) return
                    if (sApplying.get() == true) return
                    val tv = p.thisObject as? TextView ?: return
                    if (tv.id != NICK_VIEW_ID) return        // 只处理群成员昵称
                    // ★ 自动校准（无需填 wxid）：群成员消息打印 sender + 值为 true 的 boolean 字段（每 sender 一次）
                    val rs0 = GroupTitleTagHook.extractRoomSender(tv)
                    if (rs0 != null) {
                        val calibKey = rs0[0] + "|" + rs0[1]
                        if (GroupTitleTagHook.isChatroom(rs0[0])
                            && !GroupTitleTagHook.isSelfMsg(rs0[1], null)
                            && sCalibPrinted.add(calibKey)) {
                            GroupTitleTagHook.dumpMemberFields(rs0[0], rs0[1])
                        }
                    }
                    val role = synchronized(sRoleCache) { sRoleCache[tv] } ?: resolveRole(tv) ?: return
                    val prefix = " $role "
                    val cur = tv.text?.toString() ?: ""
                    if (cur.startsWith(prefix)) return          // 防重复
                    applyLabel(tv, role, cur)
                } catch (_: Throwable) {}
            }
        })

        // 网络同步：群成员详情响应后，延迟重刷当前窗口已渲染昵称标签
        listOf("qn.w", "qn.c0", "l81.g0").forEach { cn ->
            runCatching {
                val c = XposedHelpers.findClass(cn, cl)
                XposedBridge.hookAllMethods(c, "onGYNetEnd", object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        try {
                            mainHandler.postDelayed({ refreshAllRenderedNickLabels() }, 500)
                        } catch (_: Throwable) {}
                    }
                })
            }
        }
    }

    /** 立即刷新当前窗口里所有已渲染的群成员昵称标签（网络同步/改角色后调用）。 */
    fun refreshAllRenderedNickLabels() {
        try {
            val a = currentActivity.get() ?: return
            val decor = a.window?.decorView ?: return
            walkFindNick(decor, 0)
        } catch (_: Throwable) {}
    }

    private fun walkFindNick(v: View, depth: Int) {
        if (v == null || depth > 24) return
        if (v.id == NICK_VIEW_ID && v is TextView) {
            try {
                val role = synchronized(sRoleCache) { sRoleCache[v] } ?: resolveRole(v) ?: return
                val prefix = " $role "
                val cur = v.text?.toString() ?: ""
                if (!cur.startsWith(prefix)) applyLabel(v, role, cur)
            } catch (_: Throwable) {}
            return
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walkFindNick(v.getChildAt(i), depth + 1)
    }

    /** 用现有 GroupTitleTagHook 的角色查询逻辑（room/sender → 群主/管理员/成员）。 */
    private fun resolveRole(tv: TextView): String? {
        val rs = GroupTitleTagHook.extractRoomSender(tv) ?: return null
        val room = rs[0]
        val sender = rs[1]
        if (!GroupTitleTagHook.isChatroom(room)) return null
        if (GroupTitleTagHook.isSelfMsg(sender, null)) return null
        return GroupTitleTagHook.resolveRole(room, sender)
    }

    private fun applyLabel(tv: TextView, role: String, cur: String) {
        val roleColor = when (role) {
            "群主" -> 0xFFE64340.toInt()
            "管理员" -> 0xFFF5B301.toInt()
            else -> 0xFF888888.toInt()
        }
        // 清洗掉可能残留的旧标签
        val clean = cur.replace(Regex("^\\s*(群主|管理员|成员)\\s*"), "").trim()
        val sp = SpannableStringBuilder()
        val label = SpannableString(" $role ")
        label.setSpan(ForegroundColorSpan(Color.WHITE), 0, label.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        label.setSpan(BackgroundColorSpan(roleColor), 0, label.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.append(label).append(" ").append(clean)
        sApplying.set(true)
        try {
            tv.text = sp
        } finally {
            sApplying.set(false)
        }
        tv.setSingleLine(true)                                  // 防换行
        tv.ellipsize = TextUtils.TruncateAt.END                   // 超长省略
    }

    private fun trackForegroundActivity() {
        XposedBridge.hookAllMethods(Activity::class.java, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                currentActivity.set(p.thisObject as? Activity)
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onPause", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                if (currentActivity.get() === p.thisObject) currentActivity.set(null)
            }
        })
    }
}
