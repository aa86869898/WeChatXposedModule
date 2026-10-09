package com.leshao.v3.hook.model

import java.util.ArrayList

open class LabelInfo {
    @JvmField
    var labelId: Int = 0

    @JvmField
    var labelName: String = ""

    @JvmField
    var labelPYFull: String = ""

    @JvmField
    var labelPYShort: String = ""

    @JvmField
    var createTime: Long = 0

    @JvmField
    var isTemporary: Boolean = false

    @JvmField
    var lastUseTime: Long = 0

    @JvmField
    var contacts: MutableList<String> = ArrayList()

    constructor() {
        contacts = ArrayList()
    }

    constructor(labelId: Int, labelName: String) : this() {
        this.labelId = labelId
        this.labelName = labelName
    }

    override fun toString(): String {
        return "[" + labelId + "] " + labelName +
            (if (isTemporary) " (临时)" else "") +
            " — " + contacts.size + "个联系人"
    }
}