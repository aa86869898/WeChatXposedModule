package com.leshao.v3.wm

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import com.leshao.v3.LogWriter
import com.leshao.v3.hook.MomentsLazyInstall
import com.leshao.v3.wm.hook.WmChatHook
import com.leshao.v3.wm.utils.WmPrefs
import com.leshao.v3.wm.utils.WmReflect

import java.util.List

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 微信大师完整注入入口 — 复刻自微信大师 MainHook
 * 注入: 聊天⚡🛡 | 群详情入口 | 主页+菜单 | 消息列表
 * 注意: 本环境 R8 改写 XposedHelpers，varargs findAndHookMethod 不可用，
 *       统一使用 findClass + getDeclaredMethod + XposedBridge.hookMethod 模式。
 */
class WmEntry {

    companion object {

        private const val TAG = "WmEntry"
        private val sHandler = Handler(Looper.getMainLooper())
        private var sPendingShow: Runnable? = null
        @Volatile
        private var sResumedActivity: Activity? = null
        private var sReconcileStarted = false
        // v1088: reconciler 边沿触发缓存, 状态未变化时跳过重复的反射/查询开销
        @Volatile
        private var sLastChatVisible = false
        @Volatile
        private var sLastChatUser = ""

        @JvmStatic
        fun injectAll(cl: ClassLoader) {
            WmPrefs.init()
            WmChatHook.initOnAppStart(cl)
            try {
                injectChatWindow(cl)
                LogWriter.log(TAG, "inject all OK (⚡💬)")
            } catch (t: Throwable) {
                LogWriter.log(TAG, "inject err: " + t.message)
            }
        }

        // ===== 聊天窗口 ⚡🛡 =====
        @JvmStatic
        fun injectChatWindow(cl: ClassLoader) {
            try {
                var chatClass: Class<*>? = null
                try {
                    chatClass = XposedHelpers.findClass("com.tencent.mm.ui.chatting.ChattingUI", cl)
                } catch (ignored: Throwable) {}
                if (chatClass == null) {
                    LogWriter.log(TAG, "\u2717 chat: ChattingUI not found")
                    return
                }
                LogWriter.log(TAG, "chat class: " + chatClass.name)
                // 诊断：打印类层次
                var cur: Class<*>? = chatClass
                val hier = StringBuilder("hierarchy: ")
                while (cur != null) { hier.append(cur.simpleName).append(" < "); cur = cur.superclass }
                LogWriter.log(TAG, hier.toString())

XposedBridge.hookAllMethods(Activity::class.java, "onResume", object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        try {
                            sResumedActivity = p.thisObject as? Activity
                            val clsName = p.thisObject.javaClass.name
                            LogWriter.log(TAG, "onResume: " + clsName)
                            if (clsName == "com.tencent.mm.ui.LauncherUI") {
                                // 返回主页，关闭聊天窗口功能入口（MMEditText detach 不触发，微信只隐藏视图）
                                WmChatHook.dismissTitleBtn()
                                // v3.0.151: 主界面就绪后再安装 Moments 系列 hook，避免冷启动
                                // 类加载竞争导致的类锁死锁(首次启动卡死)。安装本身在后台线程。
                                MomentsLazyInstall.maybeInstall(cl)
                            } else if (clsName == "com.tencent.mm.ui.chatting.ChattingUI") {
                                handleChatResume(p.thisObject, cl)
                            }
                        } catch (e: Exception) {
                            LogWriter.log(TAG, "chat window err: " + e.message)
                        }
                    }
                })

                // v1089: 用一定会触发的 onWindowFocusChanged 维护前台 Activity。
                // (部分微信版本 Activity.onResume 子类未回调 super, onResume 钩子不触发, 会导致
                //  reconciler 因 sResumedActivity 为空而不工作)
                try {
                    XposedBridge.hookAllMethods(Activity::class.java, "onWindowFocusChanged",
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(p: MethodHookParam) {
                                try {
                                    if (p.thisObject is Activity && p.args[0] as Boolean) {
                                        sResumedActivity = p.thisObject as Activity
                                    }
                                } catch (ignored: Throwable) {}
                            }
                        })
                    LogWriter.log(TAG, "\u2713 foreground activity (onWindowFocusChanged)")
                } catch (e: Throwable) {
                    LogWriter.log(TAG, "onWindowFocusChanged hook err: " + e.message)
                }

                try {
                    XposedBridge.hookAllMethods(chatClass, "onCreate", object : XC_MethodHook() {
                        override fun afterHookedMethod(p: MethodHookParam) {
                            try {
                                LogWriter.log(TAG, "ChattingUI.onCreate()")
                                handleChatResume(p.thisObject, cl)
                            } catch (e: Exception) {
                                LogWriter.log(TAG, "ChattingUI.onCreate err: " + e.message)
                            }
                        }
                    })
                    LogWriter.log(TAG, "\u2713 chat window (ChattingUI.onCreate)")
                } catch (e: Throwable) {
                    LogWriter.log(TAG, "ChattingUI.onCreate hook err: " + e.message)
                }
                try {
                    XposedBridge.hookAllMethods(chatClass, "onResume", object : XC_MethodHook() {
                        override fun afterHookedMethod(p: MethodHookParam) {
                            try {
                                LogWriter.log(TAG, "ChattingUI.onResume()")
                                handleChatResume(p.thisObject, cl)
                            } catch (e: Exception) {
                                LogWriter.log(TAG, "ChattingUI.onResume err: " + e.message)
                            }
                        }
                    })
                    LogWriter.log(TAG, "\u2713 chat window (ChattingUI.onResume)")
                } catch (e: Throwable) {
                    LogWriter.log(TAG, "ChattingUI.onResume hook err: " + e.message)
                }

                // ChattingUIFragment lifecycle: M0=open, O0=close
                // v957: M0/O0 在 3180 已改名, 逐个 try-catch 隔离, 单点失败不再跳过后续兜底
                try {
                    val fragClass = XposedHelpers.findClass("com.tencent.mm.ui.chatting.ChattingUIFragment", cl)
                    try {
                        val m0 = fragClass.getDeclaredMethod("M0")
                        XposedBridge.hookMethod(m0, object : XC_MethodHook() {
                            override fun afterHookedMethod(p: MethodHookParam) {
                                try {
                                    val frag = p.thisObject
                                    LogWriter.log(TAG, "ChattingUIFragment.M0() open")
                                    handleChatResume(frag, cl)
                                } catch (e: Exception) {
                                    LogWriter.log(TAG, "M0 err: " + e.message)
                                }
                            }
                        })
                        LogWriter.log(TAG, "\u2713 chat window (ChattingUIFragment M0)")
                    } catch (t: Throwable) {
                        LogWriter.log(TAG, "M0 hook 跳过(3180 改名): " + t.message)
                    }
                    try {
                        val o0 = fragClass.getDeclaredMethod("O0")
                        XposedBridge.hookMethod(o0, object : XC_MethodHook() {
                            override fun beforeHookedMethod(p: MethodHookParam) {
                                try {
                                    LogWriter.log(TAG, "ChattingUIFragment.O0() close")
                                    WmChatHook.dismissTitleBtn()
                                } catch (e: Throwable) {
                                    LogWriter.log("WmEntry", "O0 err: " + e)
                                }
                            }
                        })
                        LogWriter.log(TAG, "\u2713 chat window (ChattingUIFragment O0)")
                    } catch (t: Throwable) {
                        LogWriter.log(TAG, "O0 hook 跳过(3180 改名): " + t.message)
                    }
                } catch (e: Throwable) {
                    LogWriter.log(TAG, "ChattingUIFragment hook err: " + e.message)
                }

                // BaseChattingUIFragment lifecycle fallback: hook onCreate/onResume
                try {
                    val baseFrag = XposedHelpers.findClass("com.tencent.mm.ui.chatting.BaseChattingUIFragment", cl)
                    XposedBridge.hookAllMethods(baseFrag, "onCreate", object : XC_MethodHook() {
                        override fun afterHookedMethod(p: MethodHookParam) {
                            LogWriter.log(TAG, "BaseChattingUIFragment.onCreate()")
                        }
                    })
                    XposedBridge.hookAllMethods(baseFrag, "onResume", object : XC_MethodHook() {
                        override fun afterHookedMethod(p: MethodHookParam) {
                            try {
                                LogWriter.log(TAG, "BaseChattingUIFragment.onResume() -> show balls")
                                handleChatResume(p.thisObject, cl)
                            } catch (e: Exception) {
                                LogWriter.log(TAG, "baseFrag onResume err: " + e.message)
                            }
                        }
                    })
                    XposedBridge.hookAllMethods(baseFrag, "onPause", object : XC_MethodHook() {
                        override fun beforeHookedMethod(p: MethodHookParam) {
                            LogWriter.log(TAG, "BaseChattingUIFragment.onPause()")
                            WmChatHook.dismissTitleBtn()
                        }
                    })
                    LogWriter.log(TAG, "\u2713 chat window (BaseChattingUIFragment lifecycle)")
                } catch (e: Throwable) {
                    LogWriter.log(TAG, "BaseChattingUIFragment hook err: " + e.message)
                }

                // ChattingUIFragment 自身生命周期：二次进入(fragment 复用)时 MMEditText 不会重新 attach、
                // M0 不再调用，必须由 onHiddenChanged/setUserVisibleHint/onResume 兜底重显聊天入口
                try {
                    val fragClass = XposedHelpers.findClass("com.tencent.mm.ui.chatting.ChattingUIFragment", cl)
                    hookChatFragMethod(fragClass, "onHiddenChanged", arrayOf<Class<*>>(Boolean::class.javaPrimitiveType!!), object : XC_MethodHook() {
                        override fun afterHookedMethod(p: MethodHookParam) {
                            try {
                                val hidden = p.args[0] as Boolean
                                LogWriter.log(TAG, "ChattingUIFragment.onHiddenChanged hidden=" + hidden)
                                if (hidden) {
                                    WmChatHook.dismissTitleBtn()
                                } else {
                                    handleChatResume(p.thisObject, cl)
                                }
                            } catch (e: Throwable) { LogWriter.log(TAG, "onHiddenChanged err: " + e.message) }
                        }
                    })
                    hookChatFragMethod(fragClass, "setUserVisibleHint", arrayOf<Class<*>>(Boolean::class.javaPrimitiveType!!), object : XC_MethodHook() {
                        override fun afterHookedMethod(p: MethodHookParam) {
                            try {
                                val visible = p.args[0] as Boolean
                                LogWriter.log(TAG, "ChattingUIFragment.setUserVisibleHint visible=" + visible)
                                if (visible) handleChatResume(p.thisObject, cl)
                            } catch (e: Throwable) { LogWriter.log(TAG, "setUserVisibleHint err: " + e.message) }
                        }
                    })
                    hookChatFragMethod(fragClass, "onResume", emptyArray<Class<*>>(), object : XC_MethodHook() {
                        override fun afterHookedMethod(p: MethodHookParam) {
                            try {
                                LogWriter.log(TAG, "ChattingUIFragment.onResume()")
                                handleChatResume(p.thisObject, cl)
                            } catch (e: Throwable) { LogWriter.log(TAG, "ChattingUIFragment.onResume err: " + e.message) }
                        }
                    })
                    LogWriter.log(TAG, "\u2713 chat window (ChattingUIFragment lifecycle)")
                } catch (e: Throwable) {
                    LogWriter.log(TAG, "ChattingUIFragment lifecycle hook err: " + e.message)
                }

                // Fallback: 通过 MMEditText onAttachedToWindow 检测进入聊天窗口
                // ChattingUIFragment.M0/O0 和 BaseChattingUIFragment 生命周期在 8.0.50 不可靠
                try {
                    XposedBridge.hookAllMethods(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
                        override fun afterHookedMethod(p: MethodHookParam) {
                            try {
                                val v = p.thisObject as View
                                if ("com.tencent.mm.ui.widget.MMEditText" != v.javaClass.name) return
                                LogWriter.log(TAG, "chat detected via MMEditText attached")
                                val act = v.context as? Activity
                                if (act == null) return
                                val user = findChatUserFromActivity(act)
                                LogWriter.log(TAG, "chat detected user=" + user)
                                if (user != null && user.isNotEmpty()) {
                                    val fUser = user
                                    val fAct = act
                                    val fCl = cl
                                    val pendingOld = sPendingShow
                                    if (pendingOld != null) {
                                        sHandler.removeCallbacks(pendingOld)
                                    }
                                    sPendingShow = Runnable {
                                        WmChatHook.showTitleBtn(fAct, fCl, fUser)
                                        // v1017: MMEditText attach 是进入聊天窗口的可靠信号，
                                        // 直接补注入输入框上方按钮行（幂等），不再只依赖构造器 hook / 轮询。
                                        try { com.leshao.v3.hook.ChatVoiceSwitchHook.ensureInjected(fAct) }
                                        catch (ignored: Throwable) {}
                                        sPendingShow = null
                                    }
                                    sHandler.postDelayed(sPendingShow!!, 600)
                                }
                            } catch (e: Exception) {
                                LogWriter.log(TAG, "MMEditText detect err: " + e.message)
                            }
                        }
                    })
                    LogWriter.log(TAG, "\u2713 chat window (MMEditText onAttachedToWindow)")
                } catch (e: Throwable) {
                    LogWriter.log(TAG, "MMEditText hook err: " + e.message)
                }

                // 从会话列表进入第二个聊天窗口时：ChattingUI 为复用 Activity，MMEditText 不会重新 attach，
                // 通过 onNewIntent 检测切换会话并重显聊天入口
                try {
                    val chatUiClass = XposedHelpers.findClass("com.tencent.mm.ui.chatting.ChattingUI", cl)
                    XposedBridge.hookAllMethods(chatUiClass, "onNewIntent", object : XC_MethodHook() {
                        override fun afterHookedMethod(p: MethodHookParam) {
                            try {
                                LogWriter.log(TAG, "ChattingUI.onNewIntent")
                                handleChatResume(p.thisObject, cl)
                            } catch (e: Exception) {
                                LogWriter.log(TAG, "onNewIntent err: " + e.message)
                            }
                        }
                    })
                    LogWriter.log(TAG, "\u2713 chat window (ChattingUI.onNewIntent)")
                } catch (e: Throwable) {
                    LogWriter.log(TAG, "onNewIntent hook err: " + e.message)
                }

                // 离开聊天窗口时关闭聊天入口（MMEditText detach）
                try {
                    XposedBridge.hookAllMethods(View::class.java, "onDetachedFromWindow", object : XC_MethodHook() {
                        override fun afterHookedMethod(p: MethodHookParam) {
                            try {
                                val v = p.thisObject as View
                                if ("com.tencent.mm.ui.widget.MMEditText" != v.javaClass.name) return
                                LogWriter.log(TAG, "chat closed via MMEditText detached")
                                WmChatHook.dismissTitleBtn()
                            } catch (e: Exception) {
                                LogWriter.log(TAG, "MMEditText detach err: " + e.message)
                            }
                        }
                    })
                    LogWriter.log(TAG, "\u2713 chat window dismiss (MMEditText onDetachedFromWindow)")
                } catch (e: Throwable) {
                    LogWriter.log(TAG, "MMEditText detach hook err: " + e.message)
                }

                // 轮询 reconciler：不依赖任何 hook 触发。二次进入(fragment 复用)时 MMEditText 不重 attach、
                // M0/onResume 可能不触发，每 400ms 检查 LauncherUI 内聊天 fragment 可见性来对齐 ⚡ 显示/隐藏
                if (!sReconcileStarted) {
                    sReconcileStarted = true
                    val fCl = cl
                    sHandler.postDelayed(object : Runnable {
                        override fun run() {
                            try {
                                if (sResumedActivity != null) reconcileChatEntry(fCl)
                            } catch (ignored: Throwable) {}
                            // v1089: 即使 sResumedActivity 暂未就绪也继续轮询, 避免首 tick 直接退出
                            sHandler.postDelayed(this, 1200)
                        }
                    }, 400)
                    LogWriter.log(TAG, "✓ chat window (reconcile poll started)")
                }
            } catch (e: Exception) {
                LogWriter.log(TAG, "\u2717 chat:" + e.message)
            }
        }

        /** 轮询对齐聊天窗口功能入口(输入框上方「助手」菜单)与聊天窗口状态（幂等，不依赖 fragment/生命周期 hook） */
        private fun reconcileChatEntry(cl: ClassLoader) {
            val act = sResumedActivity
            if (act == null || act.isFinishing) return
            val clsName = act.javaClass.name
            if (clsName != "com.tencent.mm.ui.LauncherUI"
                    && clsName != "com.tencent.mm.ui.chatting.ChattingUI") return
            val user = findChatUserFromActivity(act)
            val visible = isChattingFragmentVisible(act)
            // v1088: 边沿触发 —— 仅在可见性/会话变化时更新标题按钮(showTitleBtn 幂等, 但避免每 tick 重复设置)
            val changed = (visible != sLastChatVisible) || (visible && user != null
                    && user != sLastChatUser)
            sLastChatVisible = visible
            if (visible && user != null && user.isNotEmpty()) {
                if (changed) {
                    sLastChatUser = user
                    WmChatHook.showTitleBtn(act, cl, user)
                }
                // v1002: 聊天页可见时补注入 输入框上方按钮行。
                // 微信冷启动会复用/提前创建 ChatFooter, 一次性生命周期 hook 可能错过,
                // 由该轮询兜底(tag/标记幂等, 不会重复注入; 内部已缓存 footer, 开销极小)。
                try { com.leshao.v3.hook.ChatVoiceSwitchHook.ensureInjected(act) }
                catch (ignored: Throwable) {}
            } else {
                sLastChatUser = ""
                WmChatHook.dismissTitleBtn()
            }
        }

        /** 判断 LauncherUI 内 ChattingUIFragment 是否真正显示（getView().isShown 优先，避免恢复态误判） */
        private fun isChattingFragmentVisible(act: Activity): Boolean {
            try {
                val fm = XposedHelpers.callMethod(act, "getSupportFragmentManager")
                if (fm == null) return false
                val fragments = XposedHelpers.callMethod(fm, "getFragments") as List<*>?
                if (fragments == null) return false
                for (f in fragments) {
                    if (f?.javaClass?.name != "com.tencent.mm.ui.chatting.ChattingUIFragment") continue
                    try {
                        val fv = XposedHelpers.callMethod(f, "getView")
                        if (fv is View) {
                            val v = fv
                            if (v.isShown) return true
                        }
                    } catch (ignored: Throwable) {}
                    try {
                        val v = XposedHelpers.callMethod(f, "isVisible")
                        if (v is Boolean && v) return true
                    } catch (ignored: Throwable) {}
                }
            } catch (ignored: Throwable) {}
            return false
        }

        /** 沿类链查找与参数签名匹配的方法并 hook（不存在则静默跳过，兼容不同微信版本） */
        private fun hookChatFragMethod(fragCls: Class<*>, name: String, paramTypes: Array<Class<*>>, hook: XC_MethodHook) {
            try {
                var m: java.lang.reflect.Method? = null
                var cur: Class<*>? = fragCls
                while (cur != null && cur != Any::class.java) {
                    try {
                        m = cur.getDeclaredMethod(name, *paramTypes)
                        break
                    } catch (e: NoSuchMethodException) {
                        cur = cur.superclass
                    }
                }
                if (m == null) return
                m.isAccessible = true
                XposedBridge.hookMethod(m, hook)
                LogWriter.log(TAG, "\u2713 fragment " + name + " hook (" + m.declaringClass.simpleName + ")")
            } catch (e: Throwable) {
                LogWriter.log(TAG, "fragment " + name + " hook err: " + e.message)
            }
        }

        private fun handleChatResume(obj: Any?, cl: ClassLoader) {
            val act = if (obj is Activity) obj
            else XposedHelpers.callMethod(obj, "getActivity") as? Activity
            if (act == null) { LogWriter.log(TAG, "handleChatResume: act null"); return }
            var user: String? = null
            try {
                user = WmReflect.getCurrentChatUser(act.intent)
            } catch (ignored: Exception) {}
            if (user == null || user.isEmpty()) {
                // 8.0.49 聊天窗口为 LauncherUI 内嵌 fragment：Activity intent 无 Chat_User，
                // 从 fragment arguments 兜底解析
                user = findChatUserFromActivity(act)
            }
            LogWriter.log(TAG, "handleChatResume user=" + user + " act=" + act.javaClass.simpleName)
            if (user == null || user.isEmpty()) {
                try {
                    val b = act.intent.extras
                    if (b != null) {
                        val sb = StringBuilder("chat intent keys:")
                        for (k in b.keySet()) {
                            val v = b.get(k)
                            sb.append(" ").append(k).append("=")
                                .append(v?.javaClass?.simpleName ?: "null")
                        }
                        LogWriter.log(TAG, sb.toString())
                    }
                } catch (ignored2: Exception) {}
                return
            }
            val fUser = user
            val fAct = act
            val fCl = cl
            // 去重：多路径（onCreate/onResume/M0/MMEditText attach/onNewIntent）并发触发时只保留最后一次
            val pendingOld = sPendingShow
            if (pendingOld != null) {
                sHandler.removeCallbacks(pendingOld)
            }
            sPendingShow = Runnable {
                sPendingShow = null
                WmChatHook.showTitleBtn(fAct, fCl, fUser)
                // v1017: 生命周期进入聊天页时补注入输入框上方按钮行（幂等）
                try { com.leshao.v3.hook.ChatVoiceSwitchHook.ensureInjected(fAct) }
                catch (ignored: Throwable) {}
            }
            sHandler.postDelayed(sPendingShow!!, 600)
        }

        /** 从 Activity 的 FragmentManager 中查找 ChattingUIFragment 并获取聊天对象 */
        private fun findChatUserFromActivity(act: Activity): String? {
            try {
                val fm = XposedHelpers.callMethod(act, "getSupportFragmentManager")
                if (fm == null) return null
                val fragments = XposedHelpers.callMethod(fm, "getFragments") as List<*>?
                if (fragments == null) return null
                for (f in fragments) {
                    if (f?.javaClass?.name != "com.tencent.mm.ui.chatting.ChattingUIFragment") continue
                    // 1) getArguments
                    try {
                        val args = XposedHelpers.callMethod(f, "getArguments")
                        if (args is android.os.Bundle) {
                            val u = args.getString("Chat_User")
                            if (u != null && u.isNotEmpty()) return u
                        }
                    } catch (ignored: Throwable) {}
                    // 2) scan fields
                    val u = WmReflect.getChatUserFromFragment(f)
                    if (u != null) return u
                }
            } catch (e: Throwable) {
                LogWriter.log(TAG, "findChatUserFromActivity err: " + e.message)
            }
            return null
        }
    }
}