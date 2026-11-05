package com.leshao.v3.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/**
 * 傻包网页版登录页（运行在模块自身进程，WebView 不与微信进程冲突）。
 * 登录成功后自动收集完整 Cookie，通过广播回传微信进程保存，然后自动关闭返回模块。
 */
class DoubaoLoginActivity : Activity() {

    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var saved = false

    private val poll = object : Runnable {
        override fun run() {
            try {
                val cookie = CookieManager.getInstance().getCookie(LOGIN_URL) ?: ""
                if (cookie.contains("sessionid=") && cookie.contains("sid_guard=") && cookie.length > 500) {
                    saved = true
                    val intent = Intent(ACTION_SAVE_COOKIE)
                    intent.putExtra(EXTRA_COOKIE, cookie)
                    // 仅投递给微信进程内注册的接收器，防止其他应用接收完整登录 Cookie
                    try { intent.setPackage("com.tencent.mm") } catch (ignored: Throwable) {}
                    sendBroadcast(intent)
                    Toast.makeText(this@DoubaoLoginActivity, "✅ 已获取完整傻包登录 Cookie，自动返回", Toast.LENGTH_SHORT).show()
                    handler.removeCallbacks(this)
                    finish()
                    return
                }
            } catch (ignored: Throwable) {
            }
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            CookieManager.getInstance().setAcceptCookie(true)
            val wv = WebView(this)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.databaseEnabled = true
            wv.settings.loadWithOverviewMode = true
            wv.settings.useWideViewPort = true
            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                ): Boolean {
                    view?.loadUrl(request?.url?.toString() ?: return false)
                    return true
                }
            }
            webView = wv
            setContentView(wv)
            wv.loadUrl(LOGIN_URL)
            handler.postDelayed(poll, 3000)
        } catch (t: Throwable) {
            com.leshao.v3.LogWriter.log("DoubaoLogin", "登录页创建失败: " + t)
            Toast.makeText(this, "网页登录组件不可用：" + t.message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        try { webView?.destroy() } catch (ignored: Throwable) {}
        super.onDestroy()
    }

    companion object {
        const val ACTION_SAVE_COOKIE = "com.leshao.v3.action.SAVE_DOUBAO_COOKIE"
        const val EXTRA_COOKIE = "cookie"
        private const val LOGIN_URL = "https://www.doubao.com/chat/"
    }
}