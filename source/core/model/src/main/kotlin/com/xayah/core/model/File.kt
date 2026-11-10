package com.xayah.core.model

data class File(
    val id: Long,
    val name: String,
    val path: String,
    val preserveId: Long,
    val selected: Boolean,
    /** 历史版本序号（护盾 1/2/3…）；主版本为 0。 */
    val preserveIndex: Int = 0,
)
