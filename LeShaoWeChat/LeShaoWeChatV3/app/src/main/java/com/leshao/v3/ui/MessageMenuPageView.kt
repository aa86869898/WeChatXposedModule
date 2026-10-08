package com.leshao.v3.ui

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

import com.leshao.v3.hook.MessageMenuHook
import com.leshao.v3.ui.widgets.M3Page
import com.leshao.v3.wm.utils.WmPrefs

import java.lang.ref.WeakReference
import java.util.LinkedHashSet

/**
 * 「去你妈的消息长按菜单」配置页。
 *
 * v3.0.170：按《WeChat_ChatLongPress_Menu_DeepDive.md》第 3 章全表按钮做独立开关
 * （存 msg_menu_btn_hidden，判定 = itemId + 标题双保险）；同时保留旧标题黑名单
 * （msg_menu_hidden，自动收录 msg_menu_observed）与「长按菜单整个熄掉」
 * （msg_menu_all_off，C 层）。
 */
class MessageMenuPageView {

    companion object {

        /** 弱引用当前列表容器，供 hook 收录新按钮时就地刷新，避免持有 Activity */
        @Volatile
        private var sListRef: WeakReference<LinearLayout> = WeakReference<LinearLayout>(null)

        @JvmStatic
        fun create(ctx: Context, parentAct: Activity): View {
            WmPrefs.ensureInit()

            val root = M3Page.root(ctx)

            root.addView(M3Page.section(ctx, "消息长按菜单净化",
                    "去掉微信原生按钮，仅作用于聊天窗口长按消息"))

            val headCard = M3Page.card(ctx)
            M3Page.appendSwitchRow(headCard, ctx, null, "启用净化",
                    "关闭后不做任何移除，但会继续收录按钮", WmPrefs.isMsgMenuEnabled(),
                    { _, on -> WmPrefs.setMsgMenuEnabled(on) })
            M3Page.appendSwitchRow(headCard, ctx, null, "长按菜单整个熄掉",
                    "勾选后长按任何消息都不弹出菜单（C 层全局熄菜）", WmPrefs.isMsgMenuAllOff(),
                    { _, on -> WmPrefs.setMsgMenuAllOff(on) })
            root.addView(headCard)

            root.addView(M3Page.section(ctx, "文档按钮（ID+标题双保险）",
                    "按《ChatLongPress_Menu_DeepDive》全表；开启 = 从长按菜单移除"))

            val btnCard = M3Page.card(ctx)
            rebuildBtnList(btnCard)
            root.addView(btnCard)

            root.addView(M3Page.section(ctx, "其他已收录",
                    "自动收集到但不在文档表中的标题，按标题移除"))

            val listCard = M3Page.card(ctx)
            rebuildList(listCard)
            sListRef = WeakReference<LinearLayout>(listCard)

            MessageMenuHook.setObserver {
                val c = sListRef.get()
                if (c != null) rebuildList(c)
            }

            root.addView(listCard)

            val actionCard = M3Page.card(ctx)
            actionCard.addView(M3Page.ghostButton(ctx, "清空全部隐藏项") {
                WmPrefs.setMsgMenuHidden("")
                WmPrefs.setMsgMenuBtnHidden("")
                val c = sListRef.get()
                if (c != null) rebuildList(c)
                M3Page.toast(ctx, "已清空隐藏项")
            })
            root.addView(actionCard)

            return M3Page.scroll(ctx, root)
        }

        private fun rebuildBtnList(card: LinearLayout) {
            card.removeAllViews()
            val ctx = card.context
            WmPrefs.ensureInit()

            val btnHidden = MessageMenuHook.parseSet(WmPrefs.getMsgMenuBtnHidden())

            var first = true
            for (b in MessageMenuHook.BUTTONS) {
                if (!first) card.addView(M3Page.divider(ctx))
                first = false
                M3Page.appendSwitchRow(card, ctx, null, b.name, null, btnHidden.contains(b.name),
                        { _, on ->
                            val h = MessageMenuHook.parseSet(WmPrefs.getMsgMenuBtnHidden())
                            if (on) h.add(b.name)
                            else h.remove(b.name)
                            WmPrefs.setMsgMenuBtnHidden(MessageMenuHook.join(h))
                        })
            }
        }

        private fun rebuildList(card: LinearLayout) {
            card.removeAllViews()
            val ctx = card.context
            WmPrefs.ensureInit()

            val observed = MessageMenuHook.parseSet(WmPrefs.getMsgMenuObserved())
            val hidden = MessageMenuHook.parseSet(WmPrefs.getMsgMenuHidden())

            val all = LinkedHashSet<String>()
            for (title in observed) {
                var known = false
                for (b in MessageMenuHook.BUTTONS) {
                    if (b.matchTitle(title)) {
                        known = true
                        break
                    }
                }
                if (!known) all.add(title)
            }

            var first = true
            for (title in all) {
                if (!first) card.addView(M3Page.divider(ctx))
                first = false
                M3Page.appendSwitchRow(card, ctx, null, title, null, hidden.contains(title),
                        { _, on ->
                            val h = MessageMenuHook.parseSet(WmPrefs.getMsgMenuHidden())
                            if (on) h.add(title)
                            else h.remove(title)
                            WmPrefs.setMsgMenuHidden(MessageMenuHook.join(h))
                        })
            }

            if (all.isEmpty()) {
                val tv = TextView(ctx)
                tv.text = "暂无其他按钮，去聊天里长按一条消息试试"
                tv.setTextSize(14f)
                tv.setTextColor(AppColors.onSurfaceVariant())
                val p = (16 * ctx.resources.displayMetrics.density + 0.5f).toInt()
                tv.setPadding(p, p, p, p)
                card.addView(tv)
            }
        }
    }
}
