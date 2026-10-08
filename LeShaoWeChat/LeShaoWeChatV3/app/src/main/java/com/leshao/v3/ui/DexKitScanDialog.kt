package com.leshao.v3.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

import com.leshao.v3.LogWriter
import com.leshao.v3.MainHook

import java.lang.ref.WeakReference

/**
 * DexKit 扫描进度弹窗（v1138 样式定稿 X6）。
 *
 * 静态 API（show/initSteps/updateProgress/dismiss/onScanComplete/isShowing）与原版完全一致，
 * DexKitHelper 调用点零改动。
 *
 * v1138：移除标题/副标题与步骤打勾列表，仅保留「流光进度条 + 百分比 + 加粗渐变文字」；
 * 窗口按内容自适应高度并居中显示，去掉了底部多余空白。
 */
class DexKitScanDialog {

    companion object {

        // v1131: 静态强引用 Dialog/View 会泄漏 Activity, 改为 WeakReference 并在关闭时清空视图引用
        @Volatile
        private var sDialogRef: WeakReference<AlertDialog> = WeakReference(null)
        @Volatile
        private var sPercentText: TextView? = null
        @Volatile
        private var sProgressBar: FlyingProgressBar? = null
        @Volatile
        private var sStatusText: TextView? = null
        @Volatile
        private var sDismissed = false
        /** v3.0.156：记录最近用于显示进度弹窗的 Activity，供"部分功能未适配"弹窗复用以避开已销毁的 Splash。 */
        @Volatile
        private var sHostAct: WeakReference<Activity> = WeakReference(null)
        /** 防重复弹出"部分功能未适配"。 */
        @Volatile
        private var sMissingShown = false
        private val MAIN = Handler(Looper.getMainLooper())

        private fun dialog(): AlertDialog? {
            return sDialogRef.get()
        }

        private fun setDialog(d: AlertDialog?) {
            sDialogRef = WeakReference(d)
        }

        private fun clearViewRefs() {
            sPercentText = null
            sStatusText = null
            sProgressBar = null
        }

        @JvmStatic
        fun show(ctx: Context?) {
            if (sDismissed) return
            if (ctx is Activity) {
                sHostAct = WeakReference(ctx)
            }
            MAIN.post {
                try {
                    val cur = dialog()
                    if (cur != null && cur.isShowing) return@post
                    val c = ctx ?: return@post
                    val d = buildDialog(c)
                    setDialog(d)
                    d.setCancelable(false)
                    d.show()
                    LogWriter.log("DexKitScanDialog", "scan dialog shown on "
                            + c.javaClass.name)
                    val window = d.window
                    if (window != null) {
                        window.setDimAmount(0.6f)
                        val lp = window.attributes
                        lp.width = (c.resources.displayMetrics.widthPixels * 0.9f).toInt()
                        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                        lp.gravity = Gravity.CENTER
                        window.attributes = lp
                    }
                } catch (t: Throwable) {
                    LogWriter.log("DexKitScanDialog", "show err: " + t.message)
                }
            }
        }

        /** 兼容旧 API：步骤数据不再展示（样式定稿为纯进度条）。 */
        @JvmStatic
        fun initSteps(names: Array<String>?, details: Array<String>?) {
        }

        @JvmStatic
        fun updateProgress(percent: Int, status: String?, detail: String?) {
            if (sDismissed) return
            MAIN.post {
                try {
                    sProgressBar?.setProgress(percent)
                } catch (ignored: Throwable) {
                }
            }
        }

        @JvmStatic
        fun dismiss() {
            sDismissed = true
            MAIN.post {
                try {
                    val d = dialog()
                    if (d != null && d.isShowing) {
                        d.dismiss()
                        LogWriter.log("DexKitScanDialog", "scan dialog dismissed")
                    }
                } catch (ignored: Throwable) {
                }
                setDialog(null)
                clearViewRefs()
            }
        }

        /** 扫描完成: 全部命中则直接补到 100% 并自动关闭弹窗(无需手动关闭)。 */
        @JvmStatic
        fun onScanComplete() {
            onScanComplete(null)
        }

        /** v3.0.153: 全部命中则自动关闭; 存在未定位到的目标时弹出提示, 列出缺失项。
         *  v3.0.170: 扫描通常数秒内完成、进度条只跑到个位数，立即关闭会让用户以为没扫完。
         *  这里先把进度条补到 100%（自绘条会缓动追目标），并保持 1.5s 让用户看到“扫完了”再关。
         *  @param missingSummary 缺失项摘要, null/空表示全部命中。 */
        @JvmStatic
        fun onScanComplete(missingSummary: String?) {
            LogWriter.log("DexKitScanDialog", "onScanComplete called, missing="
                    + (if (missingSummary == null) "null" else (missingSummary.length.toString() + " chars")))
            MAIN.post {
                try {
                    sProgressBar?.setProgress(100)
                    sPercentText?.text = "100%"
                    sStatusText?.text = "扫描完成"
                } catch (ignored: Throwable) {
                }
                if (missingSummary != null && missingSummary.isNotEmpty()) {
                    if (sMissingShown) return@post
                    sMissingShown = true
                    showMissingSummary(missingSummary, 0)
                } else {
                    MAIN.postDelayed({ hideDialog() }, 1500L)
                }
            }
        }

        /** 弹出"部分功能未适配"提示, 列出本次扫描未定位到的目标。
         *  v3.0.156：进度弹窗常挂在快速销毁的 WeChatSplashActivity 上，导致完成提示随 Splash 一起消失。
         *  这里在弹窗时选取"当前未销毁的 Activity"（优先 LauncherUI），Splash 已销毁则延时重试。 */
        private fun showMissingSummary(summary: String, attempt: Int) {
            try {
                var host = pickLiveActivity()
                if (host == null) {
                    if (attempt < 25) {
                        MAIN.postDelayed({ showMissingSummary(summary, attempt + 1) }, 800L)
                    } else {
                        val any = MainHook.currentActivity()
                        if (any != null && !any.isFinishing) host = any
                        if (host == null) {
                            LogWriter.logSync("DexKitScanDialog",
                                    "showMissingSummary: no live activity, give up")
                            return
                        }
                    }
                }
                if (host == null) return
                hideDialog()
                val tv = TextView(host)
                tv.text = summary
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                tv.setPadding(dp(host, 8), dp(host, 8), dp(host, 8), dp(host, 8))
                val sv = ScrollView(host)
                sv.addView(tv)
                AlertDialog.Builder(host)
                        .setTitle("部分功能未适配")
                        .setView(sv)
                        .setPositiveButton("我知道了", null)
                        .setCancelable(true)
                        .show()
                LogWriter.logSync("DexKitScanDialog",
                        "missing summary shown on " + host.javaClass.name)
            } catch (t: Throwable) {
                LogWriter.log("DexKitScanDialog", "showMissingSummary err: " + t.message)
                if (attempt < 8) {
                    MAIN.postDelayed({ showMissingSummary(summary, attempt + 1) }, 800L)
                }
            }
        }

        /** 选一个当前存活、可用的 Activity：优先记录的主机(非 Splash)，其次 MainHook 当前 Activity。 */
        private fun pickLiveActivity(): Activity? {
            val act = sHostAct.get()
            val cn = if (act != null) act.javaClass.name else ""
            val splash = cn.contains("WeChatSplashActivity")
            if (act != null && !act.isFinishing && !splash) return act
            try {
                val cur = MainHook.currentActivity()
                if (cur != null && !cur.isFinishing
                        && !cur.javaClass.name.contains("WeChatSplashActivity")) {
                    sHostAct = WeakReference(cur)
                    return cur
                }
            } catch (ignored: Throwable) {
            }
            // v3.0.158：不再回退到 Splash。Splash 上弹窗会随其销毁而消失/错位。
            // 仍为 Splash 时返回 null，让上层继续重试直到真正的宿主页(LauncherUI/朋友圈/聊天)出现。
            return null
        }

        /** 关闭弹窗但不清空可用状态(自动关闭用, 不锁定后续再次展示)。 */
        @JvmStatic
        fun hideDialog() {
            MAIN.post {
                try {
                    val d = dialog()
                    if (d != null && d.isShowing) d.dismiss()
                } catch (ignored: Throwable) {
                }
                setDialog(null)
                clearViewRefs()
            }
        }

        @JvmStatic
        fun isShowing(): Boolean {
            val d = dialog()
            return d != null && d.isShowing
        }

        private fun buildDialog(ctx: Context): AlertDialog {
            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            root.gravity = Gravity.CENTER
            root.setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 14))
            // v1145: 按当前浮层层级取底色，并补上统一阴影
            root.background = CandyUi.dialogBg(ctx, WindowLayer.depth())
            InsetsUtil.clipRounded(root)
            CandyUi.elevate(root)

            // 流光进度条（左→右填充 + 流动虚线 + 斜飞鸟）
            val bar = FlyingProgressBar(ctx)
            val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(ctx, 4), 0, dp(ctx, 4), 0)
            bar.layoutParams = lp
            root.addView(bar)
            sProgressBar = bar

            // 百分比（条下方，渐变流光）
            val percentText = TextView(ctx)
            percentText.text = "0%"
            percentText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            percentText.setTypeface(null, android.graphics.Typeface.BOLD)
            percentText.gravity = Gravity.CENTER
            percentText.setPadding(0, dp(ctx, 6), 0, dp(ctx, 2))
            root.addView(percentText)
            sPercentText = percentText
            GradientText.apply(percentText)
            bar.setProgressListener(object : FlyingProgressBar.ProgressListener {
                override fun onDisplay(percent: Int) {
                    sPercentText?.text = percent.toString() + "%"
                }
            })

            // 加粗渐变文字
            val statusText = TextView(ctx)
            statusText.text = "正在适配微信 请勿切换后台"
            statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            statusText.setTypeface(null, android.graphics.Typeface.BOLD)
            statusText.gravity = Gravity.CENTER
            root.addView(statusText)
            sStatusText = statusText
            GradientText.apply(statusText)

            val dialog = AlertDialog.Builder(ctx)
                    .setView(root)
                    .create()

            InsetsUtil.transparentWindow(dialog)
            return dialog
        }

        private fun dp(ctx: Context, v: Int): Int {
            return TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
        }
    }
}
