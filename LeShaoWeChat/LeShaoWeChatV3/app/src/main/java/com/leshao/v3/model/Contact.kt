package com.leshao.v3.model

open class Contact @JvmOverloads constructor(
    wxid: String?,
    nickname: String?,
    remarkName: String?,
    alias: String?,
    @JvmField val type: Int,
    @JvmField var sex: Int = 0,
    @JvmField val createTime: Long = 0
) {
    @JvmField
    val wxid: String = wxid ?: ""

    @JvmField
    val nickname: String = nickname ?: ""

    @JvmField
    val remarkName: String = remarkName ?: ""

    @JvmField
    val alias: String = alias ?: ""

    @JvmField
    var verifyFlag: Int = 0

    @JvmField
    var chatroomFlag: Int = 0

    fun isGroup(): Boolean {
        return wxid.endsWith("@chatroom")
    }

    fun isMale(): Boolean {
        return sex == 1
    }

    fun isFemale(): Boolean {
        return sex == 2
    }

    fun displayName(): String {
        if (remarkName.isNotEmpty()) return remarkName
        if (nickname.isNotEmpty()) return nickname
        if (alias.isNotEmpty() && !alias.startsWith("wxid_")) return alias
        return wxid
    }

    fun detailInfo(): String {
        if (alias.isNotEmpty() && !alias.startsWith("wxid_")
            && alias != wxid && !alias.equals(remarkName, ignoreCase = true)
        ) {
            return "$wxid | $alias"
        }
        return wxid
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Contact) return false
        return wxid == other.wxid
    }

    override fun hashCode(): Int {
        return wxid.hashCode()
    }

    override fun toString(): String {
        return "Contact{wxid=$wxid, name=${displayName()}}"
    }

    companion object {
        @JvmStatic
        fun empty(): Contact {
            return Contact("", "", "", "", 0)
        }
    }
}