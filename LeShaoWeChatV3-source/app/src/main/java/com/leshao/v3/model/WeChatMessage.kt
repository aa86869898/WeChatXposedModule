package com.leshao.v3.model

open class WeChatMessage @JvmOverloads constructor(
    talker: String?,
    senderWxid: String?,
    content: String?,
    @JvmField val type: Int,
    @JvmField val createTime: Long
) {
    @JvmField
    val talker: String = talker ?: ""

    @JvmField
    val senderWxid: String = senderWxid ?: ""

    @JvmField
    val content: String = content ?: ""

    @JvmField
    var dbContent: String? = null

    fun isGroup(): Boolean {
        return talker.endsWith("@chatroom")
    }

    fun isText(): Boolean {
        return type == TYPE_TEXT
    }

    fun isImage(): Boolean {
        return type == TYPE_IMAGE
    }

    fun isVideo(): Boolean {
        return type == TYPE_VIDEO
    }

    fun isVoice(): Boolean {
        return type == TYPE_VOICE
    }

    fun isCard(): Boolean {
        return type == TYPE_CARD
    }

    fun isSticker(): Boolean {
        return type == TYPE_STICKER
    }

    fun isAppMsg(): Boolean {
        return type == TYPE_APPMSG
    }

    fun isVoip(): Boolean {
        return type == TYPE_VOIP
    }

    fun isFileMsg(): Boolean {
        return isAppMsg() && dbContent != null && dbContent!!.contains("<appmsg") && dbContent!!.contains("<title>")
    }

    fun isLocationMsg(): Boolean {
        return isAppMsg() && dbContent != null && dbContent!!.contains("<location")
    }

    fun isFile(): Boolean {
        return isFileMsg()
    }

    fun isLocation(): Boolean {
        return isLocationMsg()
    }

    companion object {
        const val TYPE_TEXT = 1

        const val TYPE_IMAGE = 3

        const val TYPE_VOICE = 34

        const val TYPE_CARD = 42

        const val TYPE_FILE = 6

        const val TYPE_VIDEO = 43

        const val TYPE_STICKER = 47

        const val TYPE_APPMSG = 49

        const val TYPE_VOIP = 50

        @JvmStatic
        fun fromReflectedObject(msgObj: Any?): WeChatMessage? {
            if (msgObj == null) return null
            return try {
                val talker = reflectStr(msgObj, "field_talker")
                val sender = reflectStr(msgObj, "field_senderWxid")
                val content = reflectStr(msgObj, "field_content")
                val type = reflectInt(msgObj, "field_type")
                val createTime = reflectLong(msgObj, "field_createTime")
                val wm = WeChatMessage(talker, sender, content, type, createTime)
                wm.dbContent = reflectStr(msgObj, "field_dbContent")
                wm
            } catch (t: Throwable) {
                null
            }
        }

        private fun reflectStr(obj: Any, field: String): String {
            return try {
                val f = obj.javaClass.getDeclaredField(field)
                f.isAccessible = true
                val v = f.get(obj)
                v?.toString() ?: ""
            } catch (ignored: Throwable) {
                ""
            }
        }

        private fun reflectInt(obj: Any, field: String): Int {
            return try {
                val f = obj.javaClass.getDeclaredField(field)
                f.isAccessible = true
                f.getInt(obj)
            } catch (ignored: Throwable) {
                0
            }
        }

        private fun reflectLong(obj: Any, field: String): Long {
            return try {
                val f = obj.javaClass.getDeclaredField(field)
                f.isAccessible = true
                f.getLong(obj)
            } catch (ignored: Throwable) {
                0L
            }
        }
    }
}