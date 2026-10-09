package com.leshao.v3

/**
 * 统一日志工具 (底层委托 LogWriter)
 */
object Logger {
    @JvmField
    var debug = true

    @JvmStatic
    fun i(msg: String) {
        if (debug) LogWriter.log("LeShaoV3", msg)
    }

    @JvmStatic
    fun e(msg: String) {
        LogWriter.log("LeShaoV3", "[E] $msg")
    }

    @JvmStatic
    fun e(msg: String, t: Throwable?) {
        LogWriter.log("LeShaoV3", "[E] $msg -> ${t?.message}")
    }

    @JvmStatic
    fun w(msg: String) {
        LogWriter.log("LeShaoV3", "[W] $msg")
    }
}