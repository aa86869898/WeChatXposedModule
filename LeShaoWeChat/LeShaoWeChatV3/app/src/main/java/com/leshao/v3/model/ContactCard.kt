package com.leshao.v3.model

open class ContactCard {

    enum class Category {
        FRIEND, GROUP, OFFICIAL, SYSTEM, FILE_HELPER, OTHER
    }

    @JvmField
    var username: String? = null

    @JvmField
    var nickname: String? = null

    @JvmField
    var alias: String? = null

    @JvmField
    var conRemark: String? = null

    @JvmField
    var pyInitial: String? = null

    @JvmField
    var quanPin: String? = null

    @JvmField
    var conRemarkPYFull: String? = null

    @JvmField
    var type: Int = 0

    @JvmField
    var showHead: Int = 0

    @JvmField
    var imgFlag: Int = 0

    @JvmField
    var contactLabelIds: String? = null

    @JvmField
    var createTime: Long = 0

    @JvmField
    var category: Category? = null

    fun displayName(): String? {
        if (!conRemark.isNullOrEmpty()) return conRemark
        if (!nickname.isNullOrEmpty()) return nickname
        if (!alias.isNullOrEmpty()) return alias
        return username
    }

    fun sortKey(): String {
        val src = if (!conRemarkPYFull.isNullOrEmpty()) conRemarkPYFull else quanPin
        if (!src.isNullOrEmpty()) return src.uppercase()
        val name = displayName()
        if (!name.isNullOrEmpty()) return name.substring(0, 1).uppercase()
        return "#"
    }

    fun isFriend(): Boolean {
        return category == Category.FRIEND
    }

    fun isGroup(): Boolean {
        return category == Category.GROUP
    }

    companion object {
        @JvmStatic
        fun classify(username: String?, type: Int): Category {
            if ("filehelper" == username) return Category.FILE_HELPER
            if (username != null && username.endsWith("@chatroom")) return Category.GROUP
            if (username != null && username.startsWith("gh_")) return Category.OFFICIAL
            if (type == 33 || type == 2049) return Category.SYSTEM
            if ((type and 1) != 0 && (type and 32) == 0 && (type and 8) == 0 && (type and 64) == 0) return Category.FRIEND
            if ((type and 32) != 0) return Category.OFFICIAL
            return Category.OTHER
        }
    }
}