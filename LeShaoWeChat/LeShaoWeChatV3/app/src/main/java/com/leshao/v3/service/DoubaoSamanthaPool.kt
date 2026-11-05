// 移植自 v2.4 金矿版 08_icl_multivoice/xposed/DoubaoSamanthaPool.kt（2026-10-20）
// DoubaoSamanthaPool.kt —— 豆包登录态 samantha 直链 TTS（DoubaoTtsPool 兼容接口版，2026-10-20）
//
// 这是 SamanthaTtsClient 的薄封装，目的是让现有使用 DoubaoTtsPool 接口的模块
// 可以【零改动】切换到 samantha 车道：
//
//   DoubaoTtsPool    ->   DoubaoSamanthaPool
//   DoubaoTtsPool.init(ctx)
//                       ->   DoubaoSamanthaPool.init(ctx, cookie)   // 多传登录 Cookie
//   DoubaoTtsPool.speak(text, voice, speed, pitch, cb)
//                       ->   DoubaoSamanthaPool.speak(text, voice, speed, pitch, cb)  // 签名完全一致
//   cb.onAudio(oggChunk)
//                       ->   cb.onAudio(wavChunk)                  // 注意：现在是 WAV 帧，不是 OGG
//
// 为什么切 samantha（实测 2026-10-20，直链协议级）：
//   * voicegenie 免登录：无 TTSEnded 事件，每次请求要白等 ~2.9s 静默兜底；
//     慢窗口首帧 13-14s -> 单次总耗时 17-24s（模块 10-20s 延迟的来源）
//   * samantha 登录态：有 finish 事件零白等，首帧 ~0.9s，单次总耗时 ~1.9s
//   * speaker 音色 ID 直接透传（zh_female_wenroutaozi_uranus_bigtts 实测可用）
//   * format 默认 wav（samantha 只收 wav|aac，ogg/mp3 会被上游拦 400）
//
// 与 voicegenie 的关键差异：
//   * 不要做连接池！samantha 服务端对闲置预握手连接有极短超时（闲置十几秒后发
//     text 会被拒回空音频）——每次请求即时建连、用完即弃（冷开握手实测 0.5-1s）
//   * 需要登录 Cookie（网页登录态全量串）。失效表现为 open_success 收不到或空音频
//
// 依赖：okhttp3 / okio / kotlinx-coroutines-core / org.json（与 DoubaoTtsPool 同款）
// 底层实现见 SamanthaTtsClient.kt（协议/超时/错误处理全在那，本文件只做接口适配）。
//
// 用法：
//   DoubaoSamanthaPool.init(context, cookie)   // cookie 从模块配置读（软件设置页/SharedPreferences）
//   DoubaoSamanthaPool.speak("你好", "zh_female_wenroutaozi_uranus_bigtts", 1.0f, 0,
//       object : DoubaoSamanthaPool.Callback {
//           override fun onAudio(wavChunk: ByteArray) { /* 流式喂播放器，WAV 帧 */ }
//           override fun onComplete() { }
//           override fun onError(msg: String) { }
//       })
//
// Cookie 过期后无需重建：随时调 DoubaoSamanthaPool.updateCookie(新串) 即可热更新。
package com.leshao.v3.service

import android.content.Context

object DoubaoSamanthaPool {

    /** 与 DoubaoTtsPool.DEFAULT_VOICE 同款音色 ID（samantha 透传，实测可用） */
    const val DEFAULT_VOICE = "zh_female_wenroutaozi_uranus_bigtts"

    /** 输出音频格式：samantha 只收 wav | aac。默认 wav（模块拼接/播放最省事） */
    @Volatile
    var format: String = "wav"

    interface Callback {
        fun onAudio(wavChunk: ByteArray)
        fun onComplete()
        fun onError(msg: String) {}
    }

    @Volatile private var inited = false

    /** 初始化（对标 DoubaoTtsPool.init(ctx)）。cookie 为豆包网页登录态全量 Cookie 串 */
    @Synchronized
    fun init(context: Context, cookie: String) {
        SamanthaTtsClient.init(context.applicationContext, cookieOverride = cookie)
        inited = true
    }

    /** 初始化（cookie 从回调实时取，适合模块自己从目标进程/配置读取的场景） */
    @Synchronized
    fun init(context: Context, cookieProvider: () -> String) {
        SamanthaTtsClient.init(context.applicationContext, cookieProvider = cookieProvider)
        inited = true
    }

    /** Cookie 热更新（过期/被踢时换新串，无需重建任何东西） */
    fun updateCookie(cookie: String) {
        SamanthaTtsClient.setCookieOverride(cookie)
    }

    /** 对标 DoubaoTtsPool.speak 的完整签名：text / voice / speed / pitch(Int) / cb */
    fun speak(
        text: String,
        voice: String = DEFAULT_VOICE,
        speed: Float = 1.0f,
        pitch: Int = 0,
        cb: Callback,
    ) {
        if (!inited) { cb.onError("DoubaoSamanthaPool.init(context, cookie) not called"); return }
        if (text.isBlank()) { cb.onError("empty text"); return }
        SamanthaTtsClient.speak(
            text = text,
            voice = voice,
            format = format,
            speed = speed,
            pitch = pitch.toFloat(),
            callback = object : SamanthaTtsClient.Callback {
                override fun onAudio(chunk: ByteArray) { cb.onAudio(chunk) }
                override fun onEvent(name: String, data: org.json.JSONObject?) { /* 封装层不暴露 */ }
                override fun onComplete() { cb.onComplete() }
                override fun onError(msg: String) { cb.onError(msg) }
            },
        )
    }
}