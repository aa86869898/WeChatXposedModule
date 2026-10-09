package com.leshao.v3.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

import com.leshao.v3.ContextManager
import com.leshao.v3.service.ActivationManager
import com.leshao.v3.ui.widgets.M3Page

import java.lang.ref.WeakReference
import java.util.Stack

class SubPageActivity {

    /**
     * 子页面内部返回处理器。
     * 页面若实现此接口并挂在返回的根 View 上，返回键/顶栏返回会先交给页面自身
     * 逐级向上返回；仅当页面已在最顶层时，才由宿主关闭弹窗或回到模块主页。
     */
    fun interface BackHandler {
        /** 页面内部已消化本次返回(向上退一级)返回 true；否则返回 false 交回宿主。 */
        fun onBack(): Boolean
    }

    companion object {

        // v1143: 静态引用改用 WeakReference，避免反复进出子页面导致 Activity/Dialog 无法回收
        private var sSubDialogRef: WeakReference<AlertDialog> = WeakReference(null)
        private var sParentActRef: WeakReference<Activity> = WeakReference(null)
        private var sTitle: String? = null
        private var sPageId = 0
        private var sStandalone = false
        private var sBackHandler: BackHandler? = null
        private val sNavStack = Stack<Int>()
        // v1017: 就地重建（refreshCurrent）时保留滚动位置，避免点击/选中后页面跳回顶部
        private var sContentScroll: android.widget.ScrollView? = null
        private var sPendingScrollY = -1

        private fun subDialog(): AlertDialog? {
            return sSubDialogRef.get()
        }

        private fun parentAct(): Activity? {
            return sParentActRef.get()
        }

        private fun setSubDialog(d: AlertDialog?) {
            sSubDialogRef = WeakReference(d)
        }

        private fun setParentAct(a: Activity?) {
            sParentActRef = WeakReference(a)
        }

        // v1140: 深色模式实时跟随 —— 主题变化时就地重建当前子页面(保留滚动位置)
        init {
            AppColors.addThemeListener(Runnable {
                val d = subDialog()
                val act = parentAct()
                if (d == null || !d.isShowing() || act == null) return@Runnable
                act.runOnUiThread {
                    try {
                        val cur = subDialog()
                        if (cur != null && cur.isShowing()) refreshCurrent(act)
                    } catch (ignored: Throwable) {
                    }
                }
            })
        }

        @JvmStatic
        fun open(parentAct: Activity, title: String, pageId: Int) {
            sStandalone = false
            openInternal(parentAct, title, pageId, true)
        }

        @JvmStatic
        fun openFromMain(parentAct: Activity, title: String, pageId: Int) {
            sNavStack.clear()
            sStandalone = false
            openInternal(parentAct, title, pageId, false)
        }

        /**
         * 从微信原生页面（如群聊详情页）独立打开子页面。
         * 返回时清空导航栈并直接关闭弹窗回到原页面，不跳转模块主页。
         */
        @JvmStatic
        fun openStandalone(parentAct: Activity, title: String, pageId: Int) {
            sNavStack.clear()
            sStandalone = true
            openInternal(parentAct, title, pageId, false)
        }

        /** 页面底部「取消/确定」等场景：关闭当前子页面弹窗并回到上一级。 */
        @JvmStatic
        fun closePage(parentAct: Activity?) {
            goBack(parentAct ?: parentAct(), true)
        }

        private fun openInternal(parentAct: Activity, title: String, pageId: Int, pushCurrent: Boolean) {
            if (ActivationManager.isCurrentUserBlocked()) {
                showBlacklistBlock(parentAct)
                return
            }
            if (pushCurrent && sPageId != 0) sNavStack.push(sPageId)
            setParentAct(parentAct)
            sTitle = title
            sPageId = pageId
            sPendingScrollY = -1
            show(parentAct, title, pageId)
        }

        // ===== 黑名单拦截 =====

        private fun showBlacklistBlock(parentAct: Activity) {
            dismissSub()
            MainActivity.dismissDialog()
            if (parentAct() == null) setParentAct(parentAct)
            val act = parentAct
            act.runOnUiThread {
                try {
                    AlertDialog.Builder(act)
                            .setTitle("\uD83D\uDD12 模块已被禁用")
                            .setMessage("您已被管理员列入模块黑名单，当前微信无法使用乐少助手的任何功能，也无法进入任何功能页面。\n\n如有疑问请联系管理员解除限制。")
                            .setPositiveButton("知道了", null)
                            .setCancelable(false)
                            .show()
                } catch (ignored: Throwable) {
                }
            }
        }

        private fun show(parentAct: Activity, title: String, pageId: Int) {
            MainActivity.dismissDialog()
            dismissSub()

            val d = parentAct.resources.displayMetrics.density
            val ctx: Context = parentAct

            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            root.background = CandyUi.pageGradient()
            InsetsUtil.clipRounded(root)

            root.addView(MainActivity.makeTitleBar(ctx, title, true, Runnable { goBack(parentAct) }))

            val body = createPageBody(ctx, parentAct, pageId)
            sBackHandler = body as? BackHandler
            // v1033: 页面自身已是 ScrollView 时不再外套一层，消除同向双层滚动(掉帧/不跟手)
            // v1142: 页面自带底部栏(标记 no_wrap)时也不外套, 由页面内部管理滚动与固定底栏
            val noWrap = "no_wrap" == body.tag
            val scroller: View
            if (body is android.widget.ScrollView || noWrap) {
                scroller = body
                // v1148: 页面自身已是 ScrollView 时必须补 fillViewport，
                // 否则内容高度小于弹窗可用高度时，底部会露出 SubPageActivity 根布局的
                // pageGradient 渐变背景，形成"内容外面还套着一层背景"的视觉缺陷。
                if (body is android.widget.ScrollView) {
                    try { body.isFillViewport = true } catch (ignored: Throwable) {}
                }
            } else {
                val sv = android.widget.ScrollView(ctx)
                sv.isFillViewport = true
                sv.isVerticalScrollBarEnabled = true
                sv.addView(body)
                scroller = sv
            }
            val svLp: LinearLayout.LayoutParams
            if (noWrap) {
                // v1145: 自带底部栏/内部滚动页面保持撑满固定高度弹窗
                svLp = LinearLayout.LayoutParams(-1, 0, 1.0f)
            } else {
                // v1145: 普通内容页随内容自适应高度，不再强制撑满 90% 屏高
                // （否则短内容页面会在底部留下大面积无效空白）
                svLp = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            scroller.layoutParams = svLp
            root.addView(scroller)
            sContentScroll = scroller as? android.widget.ScrollView

            // v1017: 就地重建时恢复滚动位置（refreshCurrent 预设 sPendingScrollY）
            val restoreY = sPendingScrollY
            val targetScroll = sContentScroll
            sPendingScrollY = -1
            if (restoreY > 0 && targetScroll != null) {
                targetScroll.post(Runnable {
                    try { targetScroll.scrollTo(0, restoreY) } catch (ignored: Throwable) {}
                })
            }

            val b = AlertDialog.Builder(ctx, if (AppColors.isDarkMode())
                    android.R.style.Theme_DeviceDefault_Dialog
                else
                    android.R.style.Theme_DeviceDefault_Light_Dialog)
            // v998: 居中浮层窗口
            // v1145: 短内容页用自适应高度窗口（内容 WRAP、上限约 90% 屏）；长/固定布局页保持固定 90% 屏高
            if (noWrap) {
                b.setView(InsetsUtil.window(null, root, 0.9f, 0.9f))
            } else {
                b.setView(InsetsUtil.windowAutoHeight(null, root, 0.9f))
            }
            b.setCancelable(true)
            val dlg = b.create()
            setSubDialog(dlg)

            dlg.setOnCancelListener { goBack(parentAct, false) }
            dlg.setOnDismissListener {
                if (subDialog() == dlg) setSubDialog(null)
            }
            // 返回键：优先让页面内部逐级返回；未消化时再交给宿主关闭。
            dlg.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                        && event.action == android.view.KeyEvent.ACTION_UP) {
                    return@setOnKeyListener sBackHandler?.onBack() ?: false
                }
                false
            }

            InsetsUtil.centerAutoHeight(dlg, 0.9f)
            val w = dlg.window
            if (w != null) {
                InsetsUtil.transparentWindow(w)
                // v1105: 子页面弹窗支持软键盘缩放(否则含输入框的页面键盘不弹出/被遮挡)
                try {
                    w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                            or WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED)
                } catch (ignored: Throwable) {
                }
            }
            InsetsUtil.clearDialogShell(dlg)
            dlg.show()
            InsetsUtil.clearDialogShell(dlg)
            if (w != null) WindowLayer.track(w)
        }

        /** v1105: 让当前子页面弹窗支持软键盘缩放(点歌设置等含输入框的页面)。 */
        @JvmStatic
        fun ensureImeResize() {
            val d = subDialog()
            if (d == null) return
            try {
                val w = d.window
                if (w != null) {
                    // 清除可能阻止软键盘的焦点标志
                    w.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                            or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                    w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                            or WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED)
                }
            } catch (ignored: Throwable) {
            }
        }

        /** v1105: 点击输入框时强制让软键盘弹出(状态置为 ALWAYS_VISIBLE)。 */
        @JvmStatic
        fun ensureImeVisible() {
            val d = subDialog()
            if (d == null) return
            try {
                val w = d.window
                if (w != null) {
                    w.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                            or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                    w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                            or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                }
            } catch (ignored: Throwable) {
            }
        }

        private fun goBack(parentAct: Activity?) {
            goBack(parentAct, true)
        }

        private fun goBack(parentAct: Activity?, allowInternal: Boolean) {
            // 页面内部返回优先：仅顶栏返回/返回键会逐级向上，弹出的空白处点击仍整体关闭。
            if (allowInternal && (sBackHandler?.onBack() ?: false)) {
                return
            }
            dismissSub()
            if (!sNavStack.isEmpty()) {
                val prevPageId = sNavStack.pop()
                openInternal(parentAct!!, "返回", prevPageId, false)
            } else if (!sStandalone) {
                MainActivity.open(parentAct!!)
            }
            sStandalone = false
        }

        /** v1013: 用于配色等设置变更后，就地重建当前页（不改变导航栈） */
        @JvmStatic
        fun refreshCurrent(parentAct: Activity?) {
            if (sPageId == 0) return
            val act = parentAct ?: parentAct()
            if (act == null) return
            val pid = sPageId
            val title = sTitle ?: ""
            // v1017: 记录当前滚动位置，重建后恢复（点击/选中不再跳回顶部）
            sPendingScrollY = sContentScroll?.let { Math.max(0, it.scrollY) } ?: 0
            dismissSub()
            setParentAct(act)
            sTitle = title
            sPageId = pid
            show(act, title, pid)
        }

        private fun dismissSub() {
            val d = subDialog()
            if (d != null && d.isShowing()) {
                try { d.dismiss() } catch (ignored: Throwable) {}
            }
            setSubDialog(null)
            setParentAct(null)
            sBackHandler = null
        }

        private fun createPageBody(ctx: Context, parentAct: Activity, pageId: Int): View {
            return when (pageId) {
                3 ->  // 联系人和群聊
                    ContactGroupPageView.create(ctx, parentAct)
                4 ->  // 群管理助手
                    WxMasterPageView.create(ctx, parentAct)
                8 ->  // TTS语音播报
                    TTSPageView.create(ctx, parentAct)
                22 -> // 在线音乐
                    OnlineMusicPageView.create(ctx, parentAct)
                20 -> // 关于模块
                    AboutPageView.create(ctx, parentAct)
                99 -> // 乐少群发
                    GroupSendPageView.create(ctx, parentAct)
                14 -> // 聊天分组
                    ChatGroupPageView.create(ctx, parentAct)
                21 -> // 消息长按菜单净化
                    MessageMenuPageView.create(ctx, parentAct)
                23 -> // 消息伪装
                    MsgForgePageView.create(ctx, parentAct)
                24 -> // 自动抢红包
                    RedPacketPageView.create(ctx, parentAct)
                25 -> // 数据库直读
                    WeChatDbPageView.create(ctx, parentAct)
                26 -> // 输入框快捷按钮
                    ChatFooterBarPageView.create(ctx, parentAct)
                27 -> // 自定义气泡
                    BubblePageView.create(ctx, parentAct)
                15 -> // 批量加好友记录
                    BatchAddRecordPageView.create(ctx, parentAct)
                28 -> // 更多功能（去广告等）
                    MoreFeaturesPageView.create(ctx, parentAct)
                29 -> // 定位伪装
                    FakeLocationPageView.create(ctx, parentAct)
                30 -> // 朋友圈自动点赞
                    MomentsLikePageView.create(ctx, parentAct)
                31 -> // 朋友圈秒集赞
                    MomentsFakeLikePageView.create(ctx, parentAct)
                32 -> // 查看微信wxid
                    WxIdViewPageView.create(ctx, parentAct)
                33 -> // 微信美化
                    WeChatBeautyPageView.create(ctx, parentAct)
                34 -> // 快捷菜单
                    QuickMenuPageView.create(ctx, parentAct)
                35 -> // 聊天时间修改（微信美化 → 聊天时间修改）
                    TimeModifyPageView.create(ctx, parentAct)
                else ->
                    makePlaceholder(ctx, parentAct)
            }
        }

        private fun makePlaceholder(ctx: Context, parentAct: Activity): View {
            val d = parentAct.resources.displayMetrics.density

            val body = LinearLayout(ctx)
            body.orientation = LinearLayout.VERTICAL
            body.gravity = Gravity.CENTER
            body.setPadding((AppColors.SPACE_LG_DP * d).toInt(), (AppColors.SPACE_MD_DP * d).toInt(),
                    (AppColors.SPACE_LG_DP * d).toInt(), (AppColors.SPACE_MD_DP * d).toInt())

            val placeholder = TextView(ctx)
            placeholder.text = "功能开发中..."
            placeholder.setTextSize(15f)
            placeholder.setTextColor(AppColors.text2())
            placeholder.gravity = Gravity.CENTER
            body.addView(placeholder)

            return body
        }
    }
}