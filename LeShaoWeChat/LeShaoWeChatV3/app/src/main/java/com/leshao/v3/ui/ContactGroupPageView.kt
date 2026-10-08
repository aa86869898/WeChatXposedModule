package com.leshao.v3.ui

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.leshao.v3.ContextManager
import com.leshao.v3.LogWriter
import com.leshao.v3.hook.*
import com.leshao.v3.model.ModuleConfig
import com.leshao.v3.ui.widgets.ColorPickerDialog
import com.leshao.v3.ui.widgets.M3Page
import com.leshao.v3.wm.utils.WmPrefs

class ContactGroupPageView {

    companion object {

        @JvmStatic
        fun create(ctx: Context, parentAct: Activity): View {
            val d = ctx.resources.displayMetrics.density
            val prefs = ContextManager.getPrefs()
            val cfg = ModuleConfig.load(prefs)
            val act = parentAct

            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            root.background = CandyUi.pageGradient()
            InsetsUtil.clipRounded(root)
            root.setPadding((AppColors.SPACE_MD_DP * d).toInt(), (6 * d).toInt(),
                    (AppColors.SPACE_MD_DP * d).toInt(), (8 * d).toInt())

            // ==================== 转发类 ====================
            root.addView(M3Page.section(ctx, "乐少转发",
                    "语音/收藏/自动转发、突破9人上限、原生转发按钮替换、万群定时群发"))

            // 语音消息转发
            val cardChat = makeCard(ctx, d)
            val vfOn = HookConfig.isEnabled("voice_forward")

            // v998: 移除"消息防撤回"功能入口
            cardChat.addView(switchRow(ctx, d, "语音消息转发", null, vfOn, { _, on ->
                if (prefs != null) prefs.edit().putBoolean("voice_forward", on).apply()
                VoiceForwardHook.setEnabled(on)
            }, null))
            root.addView(cardChat)

            root.addView(candyDivider(ctx, d))

            // 收藏语音转发：文档《收藏语音转发WeChat_FavVoice_Forward_Analysis.md》路线A
            val favVoiceOn = prefs != null && prefs.getBoolean(FavVoiceForwardHook.K_ENABLED, false)
            val cardFavVoice = makeCard(ctx, d)
            cardFavVoice.addView(switchRow(ctx, d, "收藏语音转发",
                    "收藏的语音长按可转发给联系人/群聊", favVoiceOn,
                    { _, on ->
                        if (prefs != null) prefs.edit().putBoolean(FavVoiceForwardHook.K_ENABLED, on).apply()
                        FavVoiceForwardHook.setEnabled(on)
                        Toast.makeText(ctx, "收藏语音转发已" + (if (on) "开启" else "关闭"),
                                Toast.LENGTH_SHORT).show()
                    }, null))
            root.addView(cardFavVoice)

            root.addView(candyDivider(ctx, d))

            // v3.0.206: 聊天页面收藏语音转发（聊天窗口「+」→ 收藏选择页，长按/单击语音直接转发给当前聊天）
            val chatFavVoiceOn = prefs != null
                    && prefs.getBoolean(ChatFavVoiceHook.K_ENABLED, false)
            val cardChatFavVoice = makeCard(ctx, d)
            cardChatFavVoice.addView(switchRow(ctx, d, "聊天页面收藏语音转发",
                    "聊天「+」→收藏选择页中，单击/长按语音可直接转发给当前聊天", chatFavVoiceOn,
                    { _, on ->
                        if (prefs != null) prefs.edit().putBoolean(ChatFavVoiceHook.K_ENABLED, on).apply()
                        ChatFavVoiceHook.setEnabled(on)
                        Toast.makeText(ctx, "聊天页面收藏语音转发已" + (if (on) "开启" else "关闭"),
                                Toast.LENGTH_SHORT).show()
                    }, null))
            root.addView(cardChatFavVoice)

            root.addView(candyDivider(ctx, d))

            // v3.0.89: 突破转发/群发多选联系人 9 人上限（文档《微信突破转发群发9个联系人上限》方案A）
            val forwardLimitOn = prefs != null
                    && prefs.getBoolean(ForwardLimitHook.K_ENABLED, false)
            val cardForwardLimit = makeCard(ctx, d)
            cardForwardLimit.addView(switchRow(ctx, d, "去你妈只能选9个对象",
                    "转发/群发多选联系人时突破 9 人上限", forwardLimitOn,
                    { _, on ->
                        if (prefs != null) prefs.edit().putBoolean(ForwardLimitHook.K_ENABLED, on).apply()
                        ForwardLimitHook.setEnabled(on)
                        Toast.makeText(ctx, (if (on) "已开启" else "已关闭") +
                                        "突破9人上限（频繁大群发易触发风控，请注意频率）",
                                Toast.LENGTH_LONG).show()
                    }, null))
            root.addView(cardForwardLimit)

            root.addView(candyDivider(ctx, d))

            // 微信原生转发按钮替换：长按消息菜单「转发」/ 多选左下角「转发」使用模块联系人选择器
            val wxFwdReplaceOn = prefs != null
                    && prefs.getBoolean(WxForwardReplaceHook.K_ENABLED, false)
            val cardWxFwdReplace = makeCard(ctx, d)
            cardWxFwdReplace.addView(switchRow(ctx, d, "微信原生转发按钮替换",
                    "长按消息「转发」/多选左下角「转发」改用模块联系人选择器（不限制人数）", wxFwdReplaceOn,
                    { _, on ->
                        if (prefs != null) prefs.edit().putBoolean(WxForwardReplaceHook.K_ENABLED, on).apply()
                        WxForwardReplaceHook.setEnabled(on)
                        Toast.makeText(ctx, "微信原生转发按钮替换已" + (if (on) "开启" else "关闭"), Toast.LENGTH_SHORT).show()
                    }, null))
            root.addView(cardWxFwdReplace)

            root.addView(candyDivider(ctx, d))

            // ==================== 消息增强类 ====================
            root.addView(M3Page.section(ctx, "消息增强",
                    "防撤回、长按菜单净化、消息伪装"))

            // v1146: 消息防撤回（严格实现文档《WeChat_AntiRevoke_Reverse.md》H1/H3 方案）
            val antiRevokeOn = prefs != null && prefs.getBoolean(AntiRecallHook.K_MASTER, true)
            val cardAntiRevoke = makeCard(ctx, d)
            cardAntiRevoke.addView(switchRow(ctx, d, "消息防撤回",
                    "拦截服务端撤回改写，原消息继续显示（保留微信原生提示）", antiRevokeOn,
                    { _, on ->
                        if (prefs != null) prefs.edit().putBoolean(AntiRecallHook.K_MASTER, on).apply()
                        AntiRecallHook.setEnabled(on)
                        Toast.makeText(ctx, "消息防撤回已" + (if (on) "开启" else "关闭") +
                                "（重启微信后完全生效）", Toast.LENGTH_SHORT).show()
                    },
                    { AntiRecallHook.showConfigDialog(act) }))
            root.addView(cardAntiRevoke)

            root.addView(candyDivider(ctx, d))

            // v1110: 消息长按菜单净化入口
            val cardMsgMenu = makeCard(ctx, d)
            cardMsgMenu.addView(M3Page.clickRow(ctx, "\uD83E\uDDF9", "去你妈的消息长按菜单",
                    "勾选要移除的微信原生按钮",
                    { SubPageActivity.open(act, "去你妈的消息长按菜单", 21) }))
            root.addView(cardMsgMenu)

            root.addView(candyDivider(ctx, d))

            // 消息伪装（原"更多功能"卡片拆出，归入消息增强）
            val cardMsgForge = makeCard(ctx, d)
            cardMsgForge.addView(switchRow(ctx, d, "消息伪装",
                    "文本伪装成系统消息 / 名片 / 链接卡片", MsgForgeHook.isEnabled(),
                    { _, on ->
                        MsgForgeHook.setEnabled(on)
                        Toast.makeText(ctx, "消息伪装已" + (if (on) "开启" else "关闭"), Toast.LENGTH_SHORT).show()
                    },
                    { SubPageActivity.open(act, "消息伪装", 23) }))
            root.addView(cardMsgForge)

            // ==================== 红包装备类 ====================
            root.addView(M3Page.section(ctx, "红包装备",
                    "自动抢红包"))

            // 自动抢红包（原"更多功能"卡片拆出）
            val cardRedPacket = makeCard(ctx, d)
            cardRedPacket.addView(switchRow(ctx, d, "自动抢红包",
                    "纯后台自动领取群红包", RedPacketHook.isEnabled(),
                    { _, on ->
                        RedPacketHook.setEnabled(on)
                        Toast.makeText(ctx, "自动抢红包已" + (if (on) "开启" else "关闭"), Toast.LENGTH_SHORT).show()
                    },
                    { SubPageActivity.open(act, "自动抢红包", 24) }))
            root.addView(cardRedPacket)

            // ==================== 聊天类 ====================
            root.addView(M3Page.section(ctx, "聊天增强",
                    "聊天分组、输入框快捷按钮"))

            // 聊天分组卡片
            val chatGroupOn = prefs != null && prefs.getBoolean("ls_chat_group_enabled", true)
            val cardGroup = makeCard(ctx, d)
            cardGroup.addView(switchRow(ctx, d, "聊天分组", null, chatGroupOn, { _, on ->
                if (prefs != null) prefs.edit().putBoolean("ls_chat_group_enabled", on).apply()
                // v998: 开关变化后立即显示/隐藏聊天列表顶部的分组栏
                ChatGroupUiInjector.onEnabledChanged()
            }, { SubPageActivity.open(act, "聊天分组管理", 14) }))
            root.addView(cardGroup)

            root.addView(candyDivider(ctx, d))

            // 输入框快捷按钮（原"更多功能"卡片拆出）
            val cardFooter = makeCard(ctx, d)
            cardFooter.addView(switchRow(ctx, d, "输入框快捷按钮",
                    "聊天输入框上方常驻一排按钮", ChatFooterBarHook.isEnabled(),
                    { _, on ->
                        ChatFooterBarHook.setEnabled(on)
                        Toast.makeText(ctx, "输入框快捷按钮已" + (if (on) "开启" else "关闭"), Toast.LENGTH_SHORT).show()
                    },
                    { SubPageActivity.open(act, "输入框快捷按钮", 26) }))
            root.addView(cardFooter)

            root.addView(candyDivider(ctx, d))

            // 一键拉群（文档《微信_一键邀请联系人进多群_逆向分析.md》）
            val cardBatchInvite = makeCard(ctx, d)
            cardBatchInvite.addView(switchRow(ctx, d, "一键拉群",
                    "选好友→选群聊→按随机延迟逐群邀请",
                    BatchInviteConfig.isEnabled(),
                    { _, on ->
                        BatchInviteConfig.setEnabled(on)
                        Toast.makeText(ctx, "一键拉群已" + (if (on) "开启" else "关闭"), Toast.LENGTH_SHORT).show()
                    },
                    { BatchInviteConfig.showConfigDialog(act) }))
            root.addView(cardBatchInvite)

            // ==================== UI美化类 ====================
            root.addView(M3Page.section(ctx, "UI美化",
                    "微信左上角菜单、聊天窗口长按菜单入口开关"))

            // v3.0.165: 自定义气泡 / 聊天时间线颜色 / 群聊成员昵称颜色 / 显示群成员头衔标签
            // 已迁移至主页「微信美化」分类，此处仅保留微信原生菜单入口开关
            val cardEntry = makeCard(ctx, d)
            val cornerMenuOn = WmPrefs.isCornerMenu()
            val longPressMenuOn = WmPrefs.isLongPressMenu()

            cardEntry.addView(switchRow(ctx, d, "微信左上角菜单", null, cornerMenuOn, { _, on ->
                WmPrefs.set("corner_menu", on)
            }, null))
            cardEntry.addView(switchRow(ctx, d, "聊天窗口长按菜单", null, longPressMenuOn, { _, on ->
                WmPrefs.set("long_press_menu", on)
            }, null))
            root.addView(cardEntry)

            // ==================== 系统工具类 ====================
            root.addView(M3Page.section(ctx, "系统工具",
                    "数据库直读"))

            // 数据库直读（原"更多功能"卡片拆出）
            val cardDb = makeCard(ctx, d)
            cardDb.addView(M3Page.clickRow(ctx, "\uD83D\uDDC4", "数据库直读",
                    "直接只读查询微信主库（联系人/群/消息）",
                    { SubPageActivity.open(act, "数据库直读", 25) }))
            root.addView(cardDb)

            return root
        }

        private fun subSwitch(ctx: Context, prefs: SharedPreferences?, d: Float,
                               key: String, title: String, desc: String?, defVal: Boolean): LinearLayout {
            return subSwitch(ctx, prefs, d, key, title, desc, defVal, null)
        }

        private fun subSwitch(ctx: Context, prefs: SharedPreferences?, d: Float,
                               key: String, title: String, desc: String?, defVal: Boolean,
                               config: View.OnClickListener?): LinearLayout {
            val checked = if (prefs != null) prefs.getBoolean(key, defVal) else defVal
            return switchRow(ctx, d, title, desc, checked, { _, on ->
                if (prefs != null) prefs.edit().putBoolean(key, on).apply()
            }, config)
        }

        private fun makeCard(ctx: Context, d: Float): LinearLayout {
            val card = LinearLayout(ctx)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(0, 0, 0, 0)
            card.background = CandyUi.cardBg(ctx)
            InsetsUtil.clipRounded(card)
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.setMargins(0, 0, 0, (13 * d).toInt())
            card.layoutParams = lp
            return card
        }

        private fun switchRow(ctx: Context, d: Float, title: String, desc: String?,
                              checked: Boolean, listener: CompoundButton.OnCheckedChangeListener?,
                              configListener: View.OnClickListener?): LinearLayout {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            // 全局规范: 行触控区域不低于 48dp
            row.minimumHeight = (48 * d).toInt()
            row.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            row.background = CandyUi.rowBg(ctx)
            InsetsUtil.clipRounded(row)

            val textCol = LinearLayout(ctx)
            textCol.orientation = LinearLayout.VERTICAL
            textCol.layoutParams = LinearLayout.LayoutParams(0, -2, 1.0f)

            val tv = TextView(ctx)
            tv.text = title
            tv.setTextSize(16f)
            tv.setTextColor(AppColors.text1())
            tv.setTypeface(null, Typeface.BOLD)
            textCol.addView(tv)

            if (desc != null && desc.isNotEmpty()) {
                val dv = TextView(ctx)
                dv.text = desc
                dv.setTextSize(12f)
                dv.setTextColor(AppColors.text2())
                dv.setPadding(0, (3 * d).toInt(), 0, 0)
                textCol.addView(dv)
            }
            row.addView(textCol)

            if (configListener != null) {
                LogWriter.log("ContactGroupPageView", "switchRow [" + title + "] 显示[设置]按钮")
                val btn = TextView(ctx)
                btn.text = "[设置]"
                btn.setTextSize(12f)
                btn.setTextColor(AppColors.accent())
                btn.setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
                btn.paintFlags = btn.paintFlags or Paint.UNDERLINE_TEXT_FLAG
                CandyUi.ripple(btn, AppColors.SHAPE_FULL_DP.toFloat())
                btn.setOnClickListener(configListener)
                row.addView(btn)
            }

            val sw = CandyUi.newSwitch(ctx)
            sw.isChecked = checked
            sw.setOnCheckedChangeListener(listener)
            row.addView(sw)
            return row
        }

        /** v3.0.150：颜色修改行（色块预览 + [取色]，0 = 恢复微信原生）。 */
        private fun colorRow(ctx: Context, d: Float, title: String, desc: String?,
                             current: Int, onPick: ColorPickerDialog.OnPick): LinearLayout {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.minimumHeight = (48 * d).toInt()
            row.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            row.background = CandyUi.rowBg(ctx)
            InsetsUtil.clipRounded(row)

            val textCol = LinearLayout(ctx)
            textCol.orientation = LinearLayout.VERTICAL
            textCol.layoutParams = LinearLayout.LayoutParams(0, -2, 1.0f)

            val tv = TextView(ctx)
            tv.text = title
            tv.setTextSize(16f)
            tv.setTextColor(AppColors.text1())
            tv.setTypeface(null, Typeface.BOLD)
            textCol.addView(tv)

            if (desc != null && desc.isNotEmpty()) {
                val dv = TextView(ctx)
                dv.text = desc
                dv.setTextSize(12f)
                dv.setTextColor(AppColors.text2())
                dv.setPadding(0, (3 * d).toInt(), 0, 0)
                textCol.addView(dv)
            }
            row.addView(textCol)

            // 色块预览
            val sz = (22 * d).toInt()
            val slp = LinearLayout.LayoutParams(sz, sz)
            slp.setMargins(0, 0, (10 * d).toInt(), 0)
            val swatch = View(ctx)
            swatch.layoutParams = slp
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.RECTANGLE
            gd.setCornerRadius(6 * d)
            if (current == 0) {
                gd.setColor(AppColors.inputBg())
                gd.setStroke((1 * d).toInt(), AppColors.outlineVariant())
            } else {
                gd.setColor(current)
                gd.setStroke((1 * d).toInt(), 0x33000000)
            }
            swatch.background = gd
            row.addView(swatch)

            val btn = TextView(ctx)
            btn.text = "[取色]"
            btn.setTextSize(12f)
            btn.setTextColor(AppColors.accent())
            btn.setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
            btn.paintFlags = btn.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            CandyUi.ripple(btn, AppColors.SHAPE_FULL_DP.toFloat())
            btn.setOnClickListener {
                ColorPickerDialog.show(ctx, title, current, true, onPick)
            }
            row.addView(btn)

            return row
        }

        private fun sectionLabel(ctx: Context, text: String): TextView {
            val d = ctx.resources.displayMetrics.density
            val tv = TextView(ctx)
            tv.text = text
            tv.setTextSize(13f)
            tv.setTextColor(AppColors.text2())
            tv.setPadding(0, 0, 0, (8 * d).toInt())
            return tv
        }

        private fun candyDivider(ctx: Context, d: Float): View {
            return M3Page.divider(ctx)
        }

        private fun spacerV(ctx: Context, d: Float, dp: Int): View {
            val v = View(ctx)
            v.layoutParams = LinearLayout.LayoutParams(-1, (dp * d).toInt())
            return v
        }
    }
}