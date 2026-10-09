package com.leshao.v3.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import com.leshao.v3.ContextManager
import com.leshao.v3.IconLoader
import com.leshao.v3.LogWriter
import com.leshao.v3.hook.VersionCompat
import com.leshao.v3.model.ModuleConfig
import com.leshao.v3.service.ActivationManager

import java.io.File
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.HashMap

class MainActivity {

    companion object {

        private const val TAG = "MainActivity"

        private var sActiveDialog: Dialog? = null
        @Volatile
        private var sLastOpenTime = 0L

        // v1140: 深色模式实时跟随 —— 记录主页弹窗宿主，主题变化时就地重建
        private var sMainPanelAct: java.lang.ref.WeakReference<Activity>? = null
        @Volatile
        private var sMainPanelShowing = false

        init {
            AppColors.addThemeListener(Runnable {
                if (!sMainPanelShowing) return@Runnable
                val ref = sMainPanelAct
                val act = ref?.get()
                if (act == null) return@Runnable
                act.runOnUiThread {
                    try {
                        if (sMainPanelShowing && sActiveDialog != null && sActiveDialog!!.isShowing()) {
                            showMainPanel(act)
                        }
                    } catch (ignored: Throwable) {
                    }
                }
            })
        }

        @Volatile
        private var sUserNickname: String? = null
        @Volatile
        private var sUserAlias: String? = null
        @Volatile
        private var sUserWxid: String? = null
        @Volatile
        private var sAvatarPath: String? = null
        private var sVipLevel = "王者VIP"

        @JvmStatic
        fun getUserNickname(): String? { return sUserNickname }
        @JvmStatic
        fun getUserAlias(): String? { return sUserAlias }
        @JvmStatic
        fun getUserWxid(): String? { return sUserWxid }
        @JvmStatic
        fun getAvatarPath(): String? { return sAvatarPath }
        @JvmStatic
        fun getVipLevel(): String { return sVipLevel }

        private fun dialogTheme(): Int {
            return if (AppColors.isDarkMode())
                    android.R.style.Theme_DeviceDefault_NoActionBar
                else
                    android.R.style.Theme_DeviceDefault_Light_NoActionBar
        }

        // v998: 移除主页"群管理助手"入口, 万群定时群发已移植至"联系人和群聊"菜单内
        // v1018: 移除"M3模块配色"入口, 模块统一使用 M3 动态配色, 全局实时生效
        private val ITEM_NAMES = arrayOf(
            "联系人和群聊", "聊天分组",
            "TTS语音播报", "在线音乐",
            "关于模块"
        )
        private val ITEM_ICONS = intArrayOf(
            0x1F465, 0x1F4CB,
            0x1F50A, 0x1F3B5,
            0x2139
        )

        private val PAGE_IDS = intArrayOf(
            3, 14, 8, 22, 20
        )

        private val PAGE_FEATURES = HashMap<Int, String>()
        init {
            PAGE_FEATURES[14] = "聊天分组|标签分组|分组管理|标签管理|ChatGroup"
            PAGE_FEATURES[3] = "通讯录导出|通讯录|联系人|防撤回|消息防撤回|语音转发|语音消息转发|自定义气泡|气泡|收藏语音转发"
            PAGE_FEATURES[8] = "语音播报|TTS播报|排版引擎|配音|API|Voice|间隔|熔断|消息类型|免打扰|安静时段|播报参数|音量|语速|音调|TTS|文字消息播报|语音消息播报|图片消息播报|播报发送人昵称|播报群聊消息|截断长文字"
            PAGE_FEATURES[22] = "在线音乐|音乐|点歌|歌曲搜索|专辑|歌手|歌单|排行榜|无损|试听|下载|酷我|Music|点歌白名单"
            PAGE_FEATURES[20] = "关于模块|版本|模块版本|热更新|更新管控|禁止微信热更新|WeChatUpdateBlocker"
            PAGE_FEATURES[33] = "微信美化|自定义气泡|气泡|聊天时间线颜色|时间颜色|群聊成员昵称颜色|昵称颜色|群成员头衔|头衔标签|群主|管理员"
            PAGE_FEATURES[34] = "快捷菜单|快捷|入口"
            PAGE_FEATURES[99] = "乐少群发|群发|万群定时群发|自动转发|转发"
        }

        @JvmStatic
        fun open(act: Activity) {
            val now = System.currentTimeMillis()
            if (now - sLastOpenTime < 2000) return
            sLastOpenTime = now
            try {
                LogWriter.logSync(TAG, "open called")
                loadUserInfoAsync()
                LogWriter.logSync(TAG, "open: userinfo async started")

                val currentWxid = ModuleConfig.getCurrentWxid()
                if (currentWxid != null && currentWxid.isNotEmpty()
                        && ActivationManager.isBlacklisted(currentWxid)
                        && !ActivationManager.isAdmin(currentWxid)) {
                    showBlacklistBlock(act)
                    return
                }

                val prefs = ContextManager.getPrefs()
                if (prefs == null || !prefs.getBoolean("ls_disclaimer_accepted", false)) {
                    LogWriter.logSync(TAG, "open: show disclaimer")
                    showDisclaimer(act)
                } else {
                    LogWriter.logSync(TAG, "open: show main panel")
                    showMainPanel(act)
                }
            } catch (t: Throwable) {
                LogWriter.logSync(TAG, "open ERROR: " + android.util.Log.getStackTraceString(t))
                try {
                    Toast.makeText(act, "模块打开异常: " + t.message, Toast.LENGTH_SHORT).show()
                } catch (ignored: Throwable) {}
            }
        }

        @JvmStatic
        fun show(act: Activity) {
            open(act)
        }

        // ===== User Info Loading =====

        private fun loadUserInfoAsync() {
            val ctx = ContextManager.getAppContext()
            if (ctx == null) { LogWriter.log(TAG, "getAppContext null"); return }

            sUserWxid = findWxidFromPrefs(ctx)
            sUserNickname = findNicknameFromPrefs(ctx)
            sUserAlias = sUserWxid
            if (sUserNickname == null || sUserNickname.isNullOrEmpty()) sUserNickname = sUserWxid

            Thread(Runnable { loadUserDetails(ctx) }, "leshao-userinfo").start()
        }

        private fun loadUserDetails(ctx: Context) {
            try {
                val sp = ctx.getSharedPreferences("system_config_prefs", 0)
                val uv = sp.all["default_uin"]
                if (uv == null) { LogWriter.log(TAG, "default_uin null"); return }
                val uin = java.lang.Long.parseLong(uv.toString())
                LogWriter.log(TAG, "uin=" + uin)

                if (sUserWxid == null || sUserWxid.isNullOrEmpty()) {
                    sUserWxid = findWxidFromPrefs(ctx)
                    LogWriter.log(TAG, "wxid=" + sUserWxid)
                }

                val wxid = sUserWxid
                if (wxid != null && wxid.isNotEmpty()) {
                    var found = false
                    try {
                        var cl = ContextManager.getClassLoader()
                        val tkCL = VersionCompat.findTinkerClassLoader(cl)
                        if (tkCL != null) {
                            cl = tkCL
                            LogWriter.log(TAG, "loadUserDetails: using Tinker ClassLoader")
                        }
                        if (cl != null) {
                            LogWriter.logSync(TAG, "userdetails: openDb begin")
                            val db = openDb(cl, uin)
                            LogWriter.logSync(TAG, "userdetails: openDb " + (if (db != null) "done" else "null"))
                            if (db != null) {
                                try {
                                    val queryMethod = findQueryMethod(db.javaClass)
                                    if (queryMethod == null) {
                                        LogWriter.log(TAG, "DB query: no query method found")
                                        return
                                    }
                                    val sql = "SELECT username, nickname, alias FROM rcontact WHERE username=?"
                                    val cursor: Any?
                                    if (queryMethod.parameterTypes.size == 1) {
                                        cursor = queryMethod.invoke(db, sql)
                                    } else {
                                        cursor = queryMethod.invoke(db, sql, arrayOf(wxid))
                                    }
                                    if (cursor != null) {
                                        val moveToFirst = cursor.javaClass.getMethod("moveToFirst")
                                        if (moveToFirst.invoke(cursor) as Boolean) {
                                            val getStr = cursor.javaClass.getMethod("getString", Int::class.javaPrimitiveType)
                                            sUserNickname = getStr.invoke(cursor, 1) as String
                                            sUserAlias = getStr.invoke(cursor, 2) as String
                                            found = true
                                            LogWriter.log(TAG, "nick=" + sUserNickname + " alias=" + sUserAlias)
                                        }
                                        cursor.javaClass.getMethod("close").invoke(cursor)
                                    }
                                } catch (e: Throwable) {
                                    LogWriter.log(TAG, "DB query failed: " + e.message)
                                } finally {
                                    try {
                                        val closeMethod = db.javaClass.getMethod("close")
                                        closeMethod.invoke(db)
                                    } catch (e: Throwable) {
                                        try {
                                            val c = db.javaClass.getDeclaredMethod("c")
                                            c.isAccessible = true
                                            c.invoke(db)
                                        } catch (ignored: Throwable) {}
                                    }
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        LogWriter.log(TAG, "DB open failed: " + e.message)
                    }

                    if (!found) {
                        sUserNickname = findNicknameFromPrefs(ctx)
                        sUserAlias = sUserWxid
                    }
                }

                // v1013: 昵称回退链 nickname -> alias -> wxid，避免主页显示空/微信号
                if (sUserNickname == null || sUserNickname.isNullOrEmpty()) {
                    val alias = sUserAlias
                    if (alias != null && alias.isNotEmpty() && !isNumeric(alias)) {
                        sUserNickname = alias
                    } else {
                        sUserNickname = sUserWxid
                    }
                }
                LogWriter.log(TAG, "resolved nick=" + sUserNickname + " wxid=" + sUserWxid)

                sAvatarPath = findAvatarPath(sUserWxid)
            } catch (e: Throwable) {
                LogWriter.log(TAG, "loadUserDetails error: " + e.message)
            }
        }

        private fun findAvatarPath(wxid: String?): String? {
            if (wxid == null) return null
            try {
                val baseDir = ContextManager.getAppContext()!!.filesDir.parentFile
                val searchDirs = arrayOf(
                    baseDir,
                    File("/data/user/0/" + baseDir!!.name),
                )

                for (dataDir in searchDirs) {
                    val subDirs = dataDir!!.listFiles()
                    if (subDirs == null) continue
                    for (sub in subDirs) {
                        if (!sub.isDirectory) continue
                        val fn = sub.name
                        if (fn.length < 10) continue
                        val avatarDir = File(sub, "avatar")
                        if (!avatarDir.isDirectory) continue
                        for (ext in arrayOf("_hd.png", ".png", ".jpg")) {
                            val avFile = File(avatarDir, wxid + ext)
                            if (avFile.exists() && avFile.length() > 0) return avFile.absolutePath
                        }
                    }
                }
            } catch (t: Throwable) {
                LogWriter.log(TAG, "findAvatarPath err: " + t.message)
            }
            return null
        }

        private fun findWxidFromPrefs(ctx: Context): String? {
            val prefNames = arrayOf(
                "system_config_prefs", "com.tencent.mm_preferences",
                "notify_sync_pref", "tinker_simulate", "auth_info_key_prefs",
                "app_brand_global_sp", "exdevice_pref",
            )
            val keyNames = arrayOf(
                "login_weixin_username", "login_user_name", "last_login_username",
                "auth_uin", "username", "uin", "_auth_uin",
            )

            for (pn in prefNames) {
                try {
                    val p = ctx.getSharedPreferences(pn, 0)
                    val all: Map<String, *> = p.all
                    for (key in keyNames) {
                        val v = all[key]
                        if (v != null) {
                            val vStr = v.toString()
                            if (vStr.startsWith("wxid_")) {
                                return vStr
                            }
                        }
                    }
                    for ((_, value) in all) {
                        val v = value
                        if (v == null) continue
                        val vStr = v.toString()
                        if (vStr.startsWith("wxid_") && !vStr.contains("@")) {
                            return vStr
                        }
                    }
                } catch (e: Throwable) {}
            }
            return null
        }

        private fun findNicknameFromPrefs(ctx: Context): String? {
            val prefNames = arrayOf(
                "system_config_prefs", "com.tencent.mm_preferences",
                "auth_info_key_prefs",
            )
            val keyNames = arrayOf(
                "login_user_name", "login_nick_name", "nick_name", "nickname",
                "last_login_nickname", "user_nickname", "display_name",
            )
            for (pn in prefNames) {
                try {
                    val p = ctx.getSharedPreferences(pn, 0)
                    val all: Map<String, *> = p.all
                    for (key in keyNames) {
                        val v = all[key]
                        if (v != null) {
                            val vStr = v.toString()
                            if (vStr.isNotEmpty() && !vStr.startsWith("wxid_") && !isNumeric(vStr)) {
                                return vStr
                            }
                        }
                    }
                    for ((key, value) in all) {
                        val v = value
                        if (v == null) continue
                        val vStr = v.toString()
                        if (vStr.isNotEmpty() && !vStr.startsWith("wxid_") && !isNumeric(vStr)
                                && vStr.length >= 2 && vStr.length <= 30
                                && !key.lowercase().contains("avatar")) {
                            return vStr
                        }
                    }
                } catch (ignored: Throwable) {}
            }
            return null
        }

        private fun isNumeric(s: String): Boolean {
            return try { java.lang.Long.parseLong(s); true } catch (t: Throwable) { false }
        }

        private fun openDb(cl: ClassLoader, uin: Long): Any? {
            var cl = cl
            val tkCL = VersionCompat.findTinkerClassLoader(cl)
            if (tkCL != null) {
                cl = tkCL
                LogWriter.log(TAG, "openDb: using Tinker ClassLoader")
            }
            val baseDir = VersionCompat.getBaseDir(cl, ContextManager.getAppContext())
            LogWriter.log(TAG, "DB path=" + baseDir + "MicroMsg/<hash>/EnMicroMsg.db")
            // v1016: 目录名候选化 + 按磁盘实际存在选择
            return VersionCompat.openEnMicroDb(cl, baseDir, uin)
        }

        private fun findQueryMethod(dbClass: Class<*>): Method? {
            val knownNames = arrayOf("u", "rawQuery", "v", "w", "x", "y", "z", "rowQuery")
            for (name in knownNames) {
                try {
                    val m = dbClass.getDeclaredMethod(name, String::class.java, Array<String>::class.java)
                    m.isAccessible = true
                    return m
                } catch (ignored: NoSuchMethodException) {}
            }
            for (name in knownNames) {
                try {
                    val m = dbClass.getDeclaredMethod(name, String::class.java)
                    m.isAccessible = true
                    return m
                } catch (ignored: NoSuchMethodException) {}
            }
            var best: Method? = null
            for (m in dbClass.declaredMethods) {
                if (m.returnType == android.database.Cursor::class.java) {
                    val pts = m.parameterTypes
                    if (pts.size == 2 && pts[0] == String::class.java && pts[1] == Array<String>::class.java) {
                        m.isAccessible = true
                        return m
                    }
                    if (best == null && pts.size >= 1 && pts[0] == String::class.java) {
                        best = m
                    }
                }
            }
            if (best != null) best.isAccessible = true
            return best
        }

        private fun md5(input: String): String {
            return try {
                val md = MessageDigest.getInstance("MD5")
                val digest = md.digest(input.toByteArray(Charsets.UTF_8))
                val sb = StringBuilder()
                for (b in digest) sb.append(String.format("%02x", b.toInt() and 0xff))
                sb.toString()
            } catch (e: Throwable) { input }
        }

        // ===== Disclaimer Dialog =====

        // ===== 黑名单拦截 =====

        private fun showBlacklistBlock(act: Activity) {
            dismissDialog()
            val d = act.resources.displayMetrics.density
            val ctx: Context = act

            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            root.gravity = Gravity.CENTER
            root.background = CandyUi.pageGradient()
            root.setPadding(dp(d, 18), dp(d, 16), dp(d, 18), dp(d, 16))

            val iconTv = TextView(ctx)
            iconTv.text = "\uD83D\uDD12"
            iconTv.setTextSize(52f)
            iconTv.gravity = Gravity.CENTER
            root.addView(iconTv)

            val titleTv = TextView(ctx)
            titleTv.text = "模块已被禁用"
            titleTv.setTextSize(18f)
            titleTv.setTextColor(AppColors.accent())
            titleTv.setTypeface(null, Typeface.BOLD)
            titleTv.gravity = Gravity.CENTER
            titleTv.setPadding(0, dp(d, 10), 0, 0)
            root.addView(titleTv)

            val msgTv = TextView(ctx)
            msgTv.text = "您已被管理员列入模块黑名单，当前微信无法使用乐少助手的任何功能，也无法进入任何功能页面。\n\n如有疑问请联系管理员解除限制。"
            msgTv.setTextSize(13f)
            msgTv.setTextColor(AppColors.text2())
            msgTv.gravity = Gravity.CENTER
            msgTv.setLineSpacing(dp(d, 4).toFloat(), 1.2f)
            msgTv.setPadding(0, dp(d, 10), 0, 0)
            root.addView(msgTv)

            val b = AlertDialog.Builder(ctx, dialogTheme())
            b.setView(InsetsUtil.window(null, root, 0.9f, -1f))
            b.setCancelable(false)
            val dlg = b.create()
            InsetsUtil.center(dlg, 0.9f, -1f)
            val w = dlg.window
            sActiveDialog = dlg
            dlg.setOnDismissListener { if (sActiveDialog == dlg) sActiveDialog = null }
            dlg.show()
            InsetsUtil.clearDialogShell(dlg)
            if (w != null) WindowLayer.track(w)
            InsetsUtil.center(dlg, 0.9f, -1f)
        }

        private fun showDisclaimer(act: Activity) {
            dismissDialog()
            val d = act.resources.displayMetrics.density
            val ctx: Context = act

            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            root.background = CandyUi.pageGradient()
            root.setPadding(dp(d, 12), dp(d, 12), dp(d, 12), dp(d, 12))

            val titleTv = TextView(ctx)
            titleTv.text = "免责声明"
            titleTv.setTextSize(18f)
            titleTv.setTextColor(AppColors.accent())
            titleTv.setTypeface(null, Typeface.BOLD)
            titleTv.gravity = Gravity.CENTER
            titleTv.setPadding(0, 0, 0, dp(d, 8))
            root.addView(titleTv)

            val sv = ScrollView(ctx)
            sv.layoutParams = LinearLayout.LayoutParams(-1, 0, 1.0f)
            val bodyCol = LinearLayout(ctx)
            bodyCol.orientation = LinearLayout.VERTICAL
            val bodyBg = GradientDrawable()
            bodyBg.cornerRadius = dp(d, AppColors.SHAPE_SM_DP).toFloat()
            bodyBg.setColor(AppColors.card())
            bodyCol.background = bodyBg
            bodyCol.setPadding(dp(d, 12), dp(d, 10), dp(d, 12), dp(d, 10))

            val ssb = SpannableStringBuilder()
            appendPara(ssb, "用户在使用本工具前，须完整阅读、充分理解并自愿同意本全部免责条款，开启及使用本软件即代表本人已完整阅读、完全知晓并自愿接受所有协议内容。")
            appendPara(ssb, "乐少助手为完全免费的个人技术学习工具，面向所有用户免费使用。平台所有捐赠通道均为用户自愿支持行为，纯属个人心意赞助，不属于软件收费、功能购买、售后担保服务，捐赠与否不影响软件完整功能的正常使用。")
            appendPara(ssb, "本工具依据《计算机软件保护条例》第十七条，仅供个人Android技术学习、开发研究、技术测试使用，仅可在本人持有完全使用权的设备上运行。本工具所有用户配置、任务数据、操作记录均仅在用户设备本地存储，不会私自收集、上传、泄露用户任何隐私数据与账号信息。")
            appendPara(ssb, "本模块纯属个人技术学习作品，与腾讯公司及微信官方无任何合作、授权、关联关系。使用本工具可能存在违反对应平台用户协议的风险，可能导致账号限制、功能受限或封禁，所有风险由使用者自行预判并承担。")
            appendBold(ssb, "严禁私自贩卖、倒卖、二次打包、商用分发本软件及相关衍生资源，严禁用于批量营销、骚扰引流、违规牟利、侵权破坏等违规违法场景。使用者需遵守国家法律法规，一切不当使用造成的账号后果、法律责任均由使用者自行承担，开发者不承担任何连带责任，亦不提供规避风控相关技术支持。")

            val bodyTv = TextView(ctx)
            bodyTv.text = ssb
            bodyTv.setTextSize(13f)
            bodyTv.setTextColor(AppColors.text1())
            bodyTv.setLineSpacing(dp(d, 4).toFloat(), 1.2f)
            bodyCol.addView(bodyTv)
            sv.addView(bodyCol)
            root.addView(sv)

            root.addView(candyDivider(ctx, d))

            val checkBox = com.leshao.v3.ui.widgets.M3Page.checkBox(ctx, "我已完整阅读并同意以上免责条款")
            checkBox.setTextColor(AppColors.text1())
            checkBox.setPadding(0, 0, 0, dp(d, 2))
            root.addView(checkBox)

            val btnRow = LinearLayout(ctx)
            btnRow.orientation = LinearLayout.HORIZONTAL
            btnRow.gravity = Gravity.CENTER

            val declineBtn = com.leshao.v3.ui.widgets.ModernButton(ctx, "不同意",
                    com.leshao.v3.ui.widgets.ModernButton.STYLE_GHOST)
            declineBtn.layoutParams = LinearLayout.LayoutParams(0, -2, 1.0f)
            btnRow.addView(declineBtn)

            btnRow.addView(spacerH(ctx, d, 10))

            val agreeBtn = com.leshao.v3.ui.widgets.ModernButton(ctx, "同意并继续 (30秒)",
                    com.leshao.v3.ui.widgets.ModernButton.STYLE_PRIMARY)
            agreeBtn.isEnabled = false
            agreeBtn.layoutParams = LinearLayout.LayoutParams(0, -2, 1.0f)
            btnRow.addView(agreeBtn)
            root.addView(btnRow)

            val handler = Handler(Looper.getMainLooper())
            val remaining = intArrayOf(30)
            val countdown = object : Runnable {
                override fun run() {
                    if (remaining[0] <= 0) {
                        agreeBtn.setText("同意并继续")
                        agreeBtn.isEnabled = true
                        return
                    }
                        agreeBtn.setText("同意并继续 (" + remaining[0] + "秒)")
                    remaining[0]--
                    handler.postDelayed(this, 1000)
                }
            }
            handler.post(countdown)

            val dlRef = arrayOfNulls<AlertDialog>(1)
            declineBtn.setOnClickListener {
                handler.removeCallbacks(countdown)
                if (dlRef[0] != null) dlRef[0]?.dismiss()
                act.finish()
            }

            agreeBtn.setOnClickListener {
                if (!checkBox.isChecked) {
                    Toast.makeText(ctx, "请先阅读并勾选同意条款", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                handler.removeCallbacks(countdown)
                val prefs = ContextManager.getPrefs()
                if (prefs != null) {
                    prefs.edit().putBoolean("ls_disclaimer_accepted", true).apply()
                }
                if (dlRef[0] != null) dlRef[0]?.dismiss()
                showMainPanel(act)
            }

            val dl = AlertDialog.Builder(ctx, dialogTheme())
                .setView(InsetsUtil.window(null, root, 0.9f, 0.9f))
                .setCancelable(false)
                .create()
            dlRef[0] = dl
            sActiveDialog = dl
            dl.setOnDismissListener { if (sActiveDialog == dl) sActiveDialog = null }
            dl.show()
            InsetsUtil.clearDialogShell(dl)
            InsetsUtil.centerAutoHeight(dl, 0.9f)
            val w = dl.window
            if (w != null) WindowLayer.track(w)
        }

        private fun appendPara(ssb: SpannableStringBuilder, text: String) {
            if (ssb.length > 0) ssb.append("\n\n")
            ssb.append(text)
        }

        private fun appendBold(ssb: SpannableStringBuilder, text: String) {
            if (ssb.length > 0) ssb.append("\n\n")
            val start = ssb.length
            ssb.append(text)
            ssb.setSpan(StyleSpan(Typeface.BOLD), start, ssb.length, 0)
        }

        // ===== Main Panel Dialog =====

        private fun showMainPanel(act: Activity) {
            dismissDialog()
            sMainPanelAct = java.lang.ref.WeakReference(act)
            sMainPanelShowing = true
            LogWriter.logSync(TAG, "panel: begin build")

            val d = act.resources.displayMetrics.density
            val ctx: Context = act

            // v998: 居中浮层窗口 —— 顶部标题栏固定, 正文滚动
            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            root.background = CandyUi.pageGradient()
            InsetsUtil.clipRounded(root)

            root.addView(buildTopBar(ctx, d, act))

            val sv = ScrollView(ctx)
            sv.isFillViewport = true
            sv.isVerticalScrollBarEnabled = true
            val body = LinearLayout(ctx)
            body.orientation = LinearLayout.VERTICAL
            body.addView(buildUserCard(ctx, d, act))
            val searchCard = buildSearchCard(ctx, d)
            body.addView(searchCard)
            body.addView(candyDivider(ctx, d))

            val searchMap = HashMap<View, String>()

            val card1 = buildCard(ctx, d)
            card1.addView(makeListRow(ctx, d, 0x2764, "爱心捐赠", AppColors.accent(), true, View.OnClickListener { showDonateDialog(act) }))
            card1.addView(candyDivider(ctx, d))
            card1.addView(makeListRow(ctx, d, 0x1F464, "乐少群发", 0, false, View.OnClickListener {
                dismissDialog()
                SubPageActivity.openFromMain(act, "乐少群发", 99)
            }))
            // v998: 实例隔离入口已隐藏(默认开启), 不再提供主页切换开关
            body.addView(card1)

            body.addView(candyDivider(ctx, d))

            // 微信美化卡片（v3.0.165：移入 群头衔/自定义气泡/时间线颜色/昵称颜色）
            val cardBeauty = buildCard(ctx, d)
            val beautyItem = makeListRow(ctx, d, 0x2728, "微信美化", 0, false, View.OnClickListener {
                dismissDialog()
                SubPageActivity.openFromMain(act, "微信美化", 33)
            })
            beautyItem.tag = "menu_item"
            searchMap[beautyItem] = "微信美化|自定义气泡|气泡|聊天时间线颜色|时间颜色|群聊成员昵称颜色|昵称颜色|群成员头衔|头衔标签"
            cardBeauty.addView(beautyItem)
            body.addView(cardBeauty)

            body.addView(candyDivider(ctx, d))

            // 快捷菜单卡片（v3.0.165：占位，后续放置高频快捷功能）
            val cardQuick = buildCard(ctx, d)
            val quickItem = makeListRow(ctx, d, 0x2699, "快捷菜单", 0, false, View.OnClickListener {
                dismissDialog()
                SubPageActivity.openFromMain(act, "快捷菜单", 34)
            })
            quickItem.tag = "menu_item"
            searchMap[quickItem] = "快捷菜单|快捷|入口"
            cardQuick.addView(quickItem)
            body.addView(cardQuick)

            body.addView(candyDivider(ctx, d))

            val card2 = buildCard(ctx, d)
            var first = true
            for (i in ITEM_NAMES.indices) {
                if (!first) card2.addView(candyDivider(ctx, d))
                first = false
                val idx = i
                val pageId = PAGE_IDS[i]

                val item = makeListRow(ctx, d, ITEM_ICONS[i], ITEM_NAMES[i], 0, false, View.OnClickListener {
                    dismissDialog()
                    SubPageActivity.openFromMain(act, ITEM_NAMES[idx], pageId)
                })
                item.tag = "menu_item"
                val features = PAGE_FEATURES[pageId]
                val searchText = ITEM_NAMES[i] + (if (features != null) "|" + features else "")
                searchMap[item] = searchText
                card2.addView(item)
            }

            body.addView(card2)

            body.addView(candyDivider(ctx, d))

            // 更多功能卡片（去广告等）
            val cardMore = buildCard(ctx, d)
            val moreItem = makeListRow(ctx, d, 0x2699, "更多功能", 0, false, View.OnClickListener {
                dismissDialog()
                SubPageActivity.openFromMain(act, "更多功能", 28)
            })
            moreItem.tag = "menu_item"
            searchMap[moreItem] = "更多功能|去广告|广告|定位伪装|虚拟定位|伪装定位"
            cardMore.addView(moreItem)
            body.addView(cardMore)

            body.addView(candyDivider(ctx, d))

            sv.addView(body, LinearLayout.LayoutParams(-1, -2))
            root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

            val b = AlertDialog.Builder(ctx, dialogTheme())
            b.setView(InsetsUtil.window(null, root, 0.9f, 0.9f))
            b.setCancelable(true)
            val dlg = b.create()

            InsetsUtil.centerAutoHeight(dlg, 0.9f)
            val w = dlg.window
            if (w != null) {
                InsetsUtil.transparentWindow(w)
            }

            sActiveDialog = dlg
            dlg.setOnDismissListener { if (sActiveDialog == dlg) sActiveDialog = null }

            val searchBox = searchCard.findViewWithTag("search_box") as? EditText
            setupSearch(searchBox, searchMap, card2)

            InsetsUtil.clearDialogShell(dlg)
            LogWriter.logSync(TAG, "panel: dialog.show")
            dlg.show()
            LogWriter.logSync(TAG, "panel: shown")
            InsetsUtil.clearDialogShell(dlg)
            if (w != null) WindowLayer.track(w)
            LogWriter.logSync(TAG, "panel: tracked")
        }

        // ===== User Card (v955 新增) =====

        private fun buildUserCard(ctx: Context, d: Float, act: Activity): View {
            val card = LinearLayout(ctx)
            card.orientation = LinearLayout.HORIZONTAL
            card.gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.setMargins(dp(d, 12), dp(d, 12), dp(d, 12), 0)
            card.layoutParams = lp
            card.background = CandyUi.cardBg(ctx)
            InsetsUtil.clipRounded(card)
            card.setPadding(dp(d, 16), dp(d, 14), dp(d, 16), dp(d, 14))
            card.isClickable = true
            card.isFocusable = true
            applyRipple(card, d, AppColors.SHAPE_CARD_DP.toFloat())

            // v1013 M3: 头像 44dp 圆形容器；真实头像异步加载，加载前显示首字母占位
            val avatar = android.widget.ImageView(ctx)
            val avSize = dp(d, 44)
            val avBg = GradientDrawable()
            avBg.shape = GradientDrawable.RECTANGLE
            avBg.cornerRadius = avSize / 2f
            avBg.setColor(AppColors.primary())
            avatar.background = avBg
            avatar.clipToOutline = true
            avatar.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            val avLp = LinearLayout.LayoutParams(avSize, avSize)
            avLp.setMarginEnd(dp(d, 12))
            avatar.layoutParams = avLp
            card.addView(avatar)

            var nick = getUserNickname()
            if (nick == null || nick.isEmpty()) nick = getUserWxid()
            val displayName = if (nick != null && nick.isNotEmpty()) nick else "微信"
            val wxid = getUserWxid()
            try {
                if (wxid != null && wxid.isNotEmpty()) {
                    val fallback = AvatarHelper.letterAvatar(displayName, avSize)
                    AvatarHelper.loadAvatarAsync(avatar, wxid, avSize, fallback)
                } else {
                    avatar.setImageBitmap(AvatarHelper.letterAvatar(displayName, avSize))
                }
            } catch (ignored: Throwable) {
                try { avatar.setImageBitmap(AvatarHelper.letterAvatar(displayName, avSize)) } catch (ignored2: Throwable) {}
            }

            val textCol = LinearLayout(ctx)
            textCol.orientation = LinearLayout.VERTICAL
            textCol.layoutParams = LinearLayout.LayoutParams(0, -2, 1.0f)

            val nameTv = TextView(ctx)
            nameTv.text = displayName
            nameTv.setTextSize(16f)
            nameTv.setTypeface(null, Typeface.BOLD)
            nameTv.setTextColor(AppColors.text1())
            nameTv.isSingleLine = true
            textCol.addView(nameTv)

            // v1013: 去掉等级逻辑，仅显示 wxid
            val subTv = TextView(ctx)
            subTv.text = if (wxid != null && wxid.isNotEmpty()) wxid else "点击查看乐少群发"
            subTv.setTextSize(12f)
            subTv.setTextColor(AppColors.textTertiary())
            subTv.isSingleLine = true
            subTv.setPadding(0, dp(d, 2), 0, 0)
            textCol.addView(subTv)

            card.addView(textCol)

            val arrow = TextView(ctx)
            arrow.text = "›"
            arrow.setTextSize(20f)
            arrow.setTextColor(AppColors.arrow())
            card.addView(arrow)

            card.setOnClickListener {
                dismissDialog()
                SubPageActivity.openFromMain(act, "乐少群发", 99)
            }

            return card
        }

        // ===== Top Bar =====

        private fun buildTopBar(ctx: Context, d: Float, act: Activity): View {
            val bar = LinearLayout(ctx)
            bar.orientation = LinearLayout.HORIZONTAL
            bar.gravity = Gravity.CENTER_VERTICAL
            // v1143: 收紧主页顶栏上下留白
            bar.setPadding(dp(d, 16), dp(d, 8), dp(d, 16), dp(d, 8))
            // v3.0.101 糖果粉: hero 顶栏糖果粉纯色 + 28dp 底部圆角(M3 extra-large shape)
            bar.background = CandyUi.topBarGradientBg(ctx)
            InsetsUtil.clipRounded(bar)

            val textCol = LinearLayout(ctx)
            textCol.orientation = LinearLayout.VERTICAL
            textCol.layoutParams = LinearLayout.LayoutParams(0, -2, 1.0f)

            val titleTv = TextView(ctx)
            titleTv.text = "乐少助手"
            titleTv.setTextSize(22f)
            titleTv.setTextColor(AppColors.onPrimary())
            titleTv.setTypeface(null, Typeface.BOLD)
            titleTv.gravity = Gravity.CENTER
            textCol.addView(titleTv)

            val verTv = TextView(ctx)
            verTv.text = "v" + ContextManager.getVersionName() + " · 微信功能增强模块"
            verTv.setTextSize(11f)
            verTv.setTextColor(AppColors.onPrimary())
            verTv.gravity = Gravity.CENTER
            verTv.setPadding(0, dp(d, 3), 0, 0)
            textCol.addView(verTv)

            bar.addView(textCol)

            return bar
        }

        // ===== Search Card =====

        private fun buildSearchCard(ctx: Context, d: Float): View {
            val card = LinearLayout(ctx)
            card.orientation = LinearLayout.HORIZONTAL
            card.gravity = Gravity.CENTER_VERTICAL
            val cardLp = LinearLayout.LayoutParams(-1, -2)
            cardLp.setMargins(dp(d, 12), 0, dp(d, 12), 0)
            card.layoutParams = cardLp
            // M3 Expressive search bar: 纯白底 + 28dp 全圆角
            val searchBg = GradientDrawable()
            searchBg.shape = GradientDrawable.RECTANGLE
            searchBg.cornerRadius = dp(d, AppColors.DIALOG_RADIUS_DP).toFloat()
            searchBg.setColor(AppColors.surfaceContainerLowest())
            card.background = searchBg
            InsetsUtil.clipRounded(card)
            card.setPadding(dp(d, 14), dp(d, 10), dp(d, 14), dp(d, 10))

            // v955: 放大镜图标
            val searchIcon = TextView(ctx)
            searchIcon.text = "\uD83D\uDD0D"
            searchIcon.setTextSize(15f)
            searchIcon.setPadding(0, 0, dp(d, 8), 0)
            card.addView(searchIcon)

            val searchBox = EditText(ctx)
            searchBox.hint = "搜索模块功能..."
            searchBox.setTextSize(14f)
            searchBox.setTextColor(AppColors.text1())
            searchBox.setHintTextColor(AppColors.textTertiary())
            searchBox.isSingleLine = true
            searchBox.setBackgroundColor(Color.TRANSPARENT)
            searchBox.setPadding(0, 0, 0, 0)
            val boxLp = LinearLayout.LayoutParams(0, -2, 1.0f)
            searchBox.layoutParams = boxLp
            searchBox.tag = "search_box"
            card.addView(searchBox)
            card.tag = "search_card"

            return card
        }

        private fun setupSearch(searchBox: EditText?,
                                 searchMap: HashMap<View, String>,
                                 itemsContainer: LinearLayout) {
            if (searchBox == null) return

            searchBox.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, cnt: Int, aft: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, bef: Int, cnt: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val query = s.toString().trim().lowercase()
                    for ((menuItem, searchText) in searchMap) {
                        val searchTextLower = searchText.lowercase()
                        menuItem.visibility = if (query.isEmpty() || searchTextLower.contains(query)) View.VISIBLE else View.GONE
                    }
                    for (i in 0 until itemsContainer.childCount) {
                        val child = itemsContainer.getChildAt(i)
                        if ("menu_item" == child.tag) continue
                        child.visibility = View.GONE
                        for (j in i + 1 until itemsContainer.childCount) {
                            val next = itemsContainer.getChildAt(j)
                            if ("menu_item" == next.tag) {
                                if (next.visibility == View.VISIBLE && query.isEmpty())
                                    child.visibility = View.VISIBLE
                                break
                            }
                        }
                    }
                }
            })
        }

        // ===== Card Containers =====

        private fun buildCard(ctx: Context, d: Float): LinearLayout {
            val card = LinearLayout(ctx)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(0, 0, 0, 0)
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.setMargins(dp(d, 12), 0, dp(d, 12), 0)
            card.layoutParams = lp
            card.background = CandyUi.cardBg(ctx)
            InsetsUtil.clipRounded(card)
            return card
        }

        // ===== List Row =====

        private fun makeListRow(ctx: Context, d: Float, emoji: Int, title: String,
                                textColor: Int, bold: Boolean,
                                onClick: View.OnClickListener?): View {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            // 全局规范: 行触控区域不低于 48dp
            row.minimumHeight = (48 * d).toInt()
            row.setPadding(dp(d, 12), dp(d, 8), dp(d, 12), dp(d, 8))
            row.background = CandyUi.rowPressBg(ctx)
            row.setOnClickListener(onClick)
            row.isClickable = true

            // v3.0.99 按需求去图标化：不再渲染左侧 emoji 图标

            val tv = TextView(ctx)
            tv.text = title
            tv.setTextSize(16f)
            tv.setTextColor(if (textColor != 0) textColor else AppColors.text1())
            if (bold) tv.setTypeface(null, Typeface.BOLD)
            tv.layoutParams = LinearLayout.LayoutParams(0, -2, 1.0f)
            row.addView(tv)

            val arrow = TextView(ctx)
            arrow.text = "›"
            arrow.setTextSize(22f)
            arrow.setTextColor(AppColors.arrow())
            row.addView(arrow)

            return row
        }

        private fun makeInnerDivider(ctx: Context, d: Float): View {
            val v = View(ctx)
            val lp = LinearLayout.LayoutParams(-1, 1)
            lp.setMargins(dp(d, 12), 0, 0, 0)
            v.layoutParams = lp
            v.setBackgroundColor(AppColors.divider())
            return v
        }

        // ===== Donate Dialog =====

        private fun showDonateDialog(act: Activity) {
            val d = act.resources.displayMetrics.density
            val ctx: Context = act

            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            root.background = CandyUi.pageGradient()
            root.setPadding(dp(d, 16), dp(d, 14), dp(d, 16), dp(d, 12))

            val titleTv = TextView(ctx)
            titleTv.text = "爱心捐赠"
            titleTv.setTextSize(20f)
            titleTv.setTextColor(AppColors.accent())
            titleTv.setTypeface(null, Typeface.BOLD)
            titleTv.gravity = Gravity.CENTER
            titleTv.setPadding(0, 0, 0, dp(d, 10))
            root.addView(titleTv)

            val card1 = LinearLayout(ctx)
            card1.orientation = LinearLayout.VERTICAL
            val card1Bg = GradientDrawable()
            card1Bg.cornerRadius = dp(d, AppColors.SHAPE_CARD_DP).toFloat()
            card1Bg.setColor(AppColors.card())
            card1.background = card1Bg
            card1.setPadding(dp(d, 12), dp(d, 10), dp(d, 12), dp(d, 10))

            card1.addView(buildDonateButtons(ctx, d, act))
            root.addView(card1)
            root.addView(candyDivider(ctx, d))

            val card2 = LinearLayout(ctx)
            card2.orientation = LinearLayout.VERTICAL
            val card2Bg = GradientDrawable()
            card2Bg.cornerRadius = dp(d, AppColors.SHAPE_CARD_DP).toFloat()
            card2Bg.setColor(AppColors.card())
            card2.background = card2Bg
            card2.setPadding(dp(d, 16), dp(d, 14), dp(d, 16), dp(d, 14))

            val contactBtn = TextView(ctx)
            contactBtn.text = "联系乐少"
            contactBtn.setTextSize(15f)
            contactBtn.setTextColor(AppColors.whiteTextOnAccent())
            contactBtn.setTypeface(null, Typeface.BOLD)
            contactBtn.gravity = Gravity.CENTER
            contactBtn.setPadding(dp(d, 14), dp(d, 12), dp(d, 14), dp(d, 12))
            contactBtn.background = CandyUi.gradientBgStatic(ctx, AppColors.SHAPE_FULL_DP.toFloat())
            applyRipple(contactBtn, d, AppColors.SHAPE_FULL_DP.toFloat())
            contactBtn.setOnClickListener {
                try {
                    val intent = Intent()
                    intent.setClassName("com.tencent.mm", "com.tencent.mm.plugin.webview.ui.tools.WebViewUI")
                    intent.putExtra("rawUrl", "https://work.weixin.qq.com/ca/cawcde22ff06beab20")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    act.startActivity(intent)
                } catch (e: Throwable) {
                    try {
                        val fallback = Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://work.weixin.qq.com/ca/cawcde22ff06beab20"))
                        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        act.startActivity(fallback)
                    } catch (ignored: Throwable) {}
                }
            }
            contactBtn.paintFlags = contactBtn.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            card2.addView(contactBtn)
            root.addView(card2)

            val dlg = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(root)
                .setCancelable(true)
                .create()
            InsetsUtil.clearDialogShell(dlg)
            dlg.show()
            InsetsUtil.center(dlg)
        }

        private fun buildDonateButtons(ctx: Context, d: Float, act: Activity): View {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER

            val aliBtn = buildDonateBtn(ctx, d, act, "donate_alipay", "支付宝打赏")
            val spacer = View(ctx)
            spacer.layoutParams = LinearLayout.LayoutParams(dp(d, 12), 0)
            val wxBtn = buildDonateBtn(ctx, d, act, "donate_wechat", "微信打赏")

            row.addView(aliBtn)
            row.addView(spacer)
            row.addView(wxBtn)
            return row
        }

        private fun buildDonateBtn(ctx: Context, d: Float, act: Activity, resName: String, label: String): View {
            val btn = LinearLayout(ctx)
            btn.orientation = LinearLayout.VERTICAL
            btn.gravity = Gravity.CENTER
            btn.setPadding(dp(d, 8), dp(d, 6), dp(d, 8), dp(d, 6))
            val btnBg = GradientDrawable()
            btnBg.cornerRadius = dp(d, AppColors.SHAPE_LG_DP).toFloat()
            btnBg.setColor(AppColors.bg())
            btn.background = btnBg
            val btnLp = LinearLayout.LayoutParams(0, -2, 1.0f)
            btn.layoutParams = btnLp
            btn.isClickable = true
            btn.setOnClickListener { showDonateImage(ctx, d, act, resName, label) }
            applyRipple(btn, d, AppColors.SHAPE_LG_DP.toFloat())

            val thumb = loadModuleDrawable(ctx, resName)
            if (thumb != null) {
                val img = ImageView(ctx)
                img.setImageDrawable(thumb)
                img.adjustViewBounds = true
                img.maxWidth = dp(d, 130)
                img.layoutParams = LinearLayout.LayoutParams(-2, dp(d, 140))
                img.scaleType = ImageView.ScaleType.FIT_CENTER
                btn.addView(img)
            }

            val labelTv = TextView(ctx)
            labelTv.text = label
            labelTv.setTextSize(13f)
            labelTv.setTextColor(AppColors.accent())
            labelTv.setTypeface(null, Typeface.BOLD)
            labelTv.gravity = Gravity.CENTER
            labelTv.setPadding(0, dp(d, 4), 0, 0)
            btn.addView(labelTv)

            return btn
        }

        private fun showDonateImage(ctx: Context, d: Float, act: Activity, resName: String, title: String) {
            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            root.background = CandyUi.pageGradient()
            root.setPadding(dp(d, 16), dp(d, 14), dp(d, 16), dp(d, 12))
            root.gravity = Gravity.CENTER

            val tv = TextView(ctx)
            tv.text = title
            tv.setTextSize(18f)
            tv.setTextColor(AppColors.accent())
            tv.setTypeface(null, Typeface.BOLD)
            tv.gravity = Gravity.CENTER
            tv.setPadding(0, 0, 0, dp(d, 10))
            root.addView(tv)

            val full = loadModuleDrawable(ctx, resName)
            if (full != null) {
                val img = ImageView(ctx)
                img.setImageDrawable(full)
                img.adjustViewBounds = true
                img.maxWidth = dp(d, 300)
                val lp = LinearLayout.LayoutParams(-2, -2)
                lp.gravity = Gravity.CENTER
                img.layoutParams = lp
                root.addView(img)
            }

            val dlg = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(root)
                .setCancelable(true)
                .create()
            InsetsUtil.clearDialogShell(dlg)
            dlg.show()
            InsetsUtil.center(dlg)
        }

        // ===== Public Static Utilities (used by other classes) =====

        @JvmStatic
        fun dismissDialog() {
            if (sActiveDialog != null && sActiveDialog!!.isShowing()) {
                try { sActiveDialog!!.dismiss() } catch (ignored: Throwable) {}
            }
            sActiveDialog = null
            sMainPanelShowing = false
        }

        @JvmStatic
        fun loadModuleDrawable(ctx: Context, name: String): android.graphics.drawable.Drawable? {
            return try {
                val res = IconLoader.moduleResources(ctx)
                if (res == null) return null
                val resId = res.getIdentifier(name, "drawable", "com.leshao.v3")
                if (resId != 0) {
                    res.getDrawable(resId)
                } else {
                    null
                }
            } catch (t: Throwable) {
                LogWriter.log("MainActivity", "loadModuleDrawable(" + name + ") err: " + t.message)
                null
            }
        }

        @JvmStatic
        fun makeTitleBar(ctx: Context, title: String, showBack: Boolean, onBack: Runnable?): View {
            val d = ctx.resources.displayMetrics.density
            val bar = LinearLayout(ctx)
            bar.orientation = LinearLayout.HORIZONTAL
            bar.gravity = Gravity.CENTER_VERTICAL
            // v1143: 收紧顶栏上下留白，压缩无效空白
            bar.setPadding(dp(d, 12), dp(d, 5), dp(d, 12), dp(d, 5))
            // v3.0.101 糖果粉: 子页顶栏糖果粉纯色 + 28dp 底部圆角
            bar.background = CandyUi.topBarGradientBg(ctx)
            InsetsUtil.clipRounded(bar)
            val barOn = AppColors.onGradient()

            if (showBack) {
                val back = TextView(ctx)
                back.text = "‹"
                back.setTextSize(22f)
                back.setTextColor(barOn)
                back.setPadding(0, 0, dp(d, 6), 0)
                back.isClickable = true
                back.setOnClickListener { if (onBack != null) onBack.run() }
                applyRipple(back, d, AppColors.SHAPE_FULL_DP.toFloat())
                bar.addView(back)
            }

            val tv = TextView(ctx)
            tv.text = title
            tv.setTextSize(19f)
            tv.setTextColor(barOn)
            tv.setTypeface(null, Typeface.BOLD)
            tv.gravity = Gravity.CENTER
            tv.layoutParams = LinearLayout.LayoutParams(0, -2, 1.0f)
            bar.addView(tv)

            if (showBack) {
                val spacer = View(ctx)
                spacer.layoutParams = LinearLayout.LayoutParams(dp(d, 8), 0)
                bar.addView(spacer)
            }

            return bar
        }

        @JvmStatic
        fun makeDivider(ctx: Context): View {
            val d = ctx.resources.displayMetrics.density
            val v = View(ctx)
            v.layoutParams = LinearLayout.LayoutParams(-1, dp(d, 6))
            v.background = CandyUi.pageGradient()
            return v
        }

        // ===== Internal Utilities =====

        private fun dp(density: Float, dp: Int): Int {
            return (dp * density + 0.5f).toInt()
        }

        private fun spacerV(ctx: Context, d: Float, dp: Int): View {
            val v = View(ctx)
            v.layoutParams = LinearLayout.LayoutParams(-1, dp(d, dp))
            return v
        }

        private fun spacerH(ctx: Context, d: Float, dp: Int): View {
            val v = View(ctx)
            v.layoutParams = LinearLayout.LayoutParams(dp(d, dp), 0)
            return v
        }

        /** v1033 M3: 为已有圆角底的可点击容器挂全圆角涟漪边界 */
        private fun applyRipple(v: View, d: Float, radiusDp: Float) {
            try {
                val mask = GradientDrawable()
                mask.shape = GradientDrawable.RECTANGLE
                mask.cornerRadius = radiusDp * d
                mask.setColor(0xFFFFFFFF.toInt())
                v.foreground = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(AppColors.stateLayerPressed()),
                        null, mask)
            } catch (ignored: Throwable) {}
        }

        private fun candyDivider(ctx: Context, d: Float): View {
            val gd = GradientDrawable()
            gd.setColor(AppColors.divider())
            val v = View(ctx)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1f * d).toInt())
            lp.setMargins((12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
            v.layoutParams = lp
            v.background = gd
            return v
        }
    }
}