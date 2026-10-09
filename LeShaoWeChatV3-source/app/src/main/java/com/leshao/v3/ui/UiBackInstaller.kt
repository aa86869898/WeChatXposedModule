package com.leshao.v3.ui

import android.app.Dialog
import android.view.KeyEvent
import android.widget.PopupWindow
import com.leshao.v3.LogWriter
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * v1015: 全局窗口返回栈安装器。
 *
 * 一次性 hook [Dialog] 与 [PopupWindow] 的 show/dismiss，自动登记到
 * [UiBackStack]；并 hook `Dialog.onBackPressed` 与 PopupWindow 内部
 * `PopupDecorView.dispatchKeyEvent`，实现「三级返回上一层」的统一返回语义。
 * 由于是全局 hook，模块内所有弹窗（含各 Hook 内临时创建的 Dialog）自动覆盖，无需逐处改造。
 */
object UiBackInstaller {

    private const val TAG = "UiBackInstaller"

    @JvmStatic
    fun install(cl: ClassLoader?) {
        hookDialog()
        hookPopupWindow(cl)
    }

    private fun hookDialog() {
        try {
            XposedBridge.hookAllMethods(Dialog::class.java, "show", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val d = param.thisObject as Dialog
                        UiBackStack.push(d, Runnable { d.dismiss() })
                    } catch (ignored: Throwable) {}
                }
            })
            XposedBridge.hookAllMethods(Dialog::class.java, "dismiss", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        UiBackStack.remove(param.thisObject)
                    } catch (ignored: Throwable) {}
                }
            })
            XposedBridge.hookAllMethods(Dialog::class.java, "onBackPressed", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        // 仅当该 Dialog 之上还有子窗口时拦截：关闭子窗口并消费返回键
                        if (UiBackStack.interceptDialogBack(param.thisObject)) {
                            param.result = null
                        }
                    } catch (ignored: Throwable) {}
                }
            })
        } catch (t: Throwable) {
            LogWriter.log(TAG, "hookDialog 失败: " + t)
        }
    }

    private fun hookPopupWindow(cl: ClassLoader?) {
        try {
            XposedBridge.hookAllMethods(PopupWindow::class.java, "showAtLocation", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    register(param.thisObject)
                }
            })
            XposedBridge.hookAllMethods(PopupWindow::class.java, "showAsDropDown", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    register(param.thisObject)
                }
            })
            XposedBridge.hookAllMethods(PopupWindow::class.java, "dismiss", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        UiBackStack.remove(param.thisObject)
                    } catch (ignored: Throwable) {}
                }
            })
        } catch (t: Throwable) {
            LogWriter.log(TAG, "hookPopupWindow 失败: " + t)
        }
        // PopupDecorView 的返回键分发：允许注册的自定义返回动作拦截
        try {
            val decor = XposedHelpers.findClass("android.widget.PopupWindow\$PopupDecorView", cl)
            XposedBridge.hookAllMethods(decor, "dispatchKeyEvent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val ev = param.args[0] as? KeyEvent
                        if (ev != null && ev.keyCode == KeyEvent.KEYCODE_BACK
                            && ev.action == KeyEvent.ACTION_UP
                            && UiBackStack.interceptPopupBack()
                        ) {
                            param.result = true
                        }
                    } catch (ignored: Throwable) {}
                }
            })
        } catch (t: Throwable) {
            LogWriter.log(TAG, "hookPopupDecorView 失败: " + t)
        }
    }

    private fun register(pw: Any?) {
        try {
            if (pw is PopupWindow) {
                val p = pw as PopupWindow
                UiBackStack.push(p, Runnable { p.dismiss() })
            }
        } catch (ignored: Throwable) {}
    }
}