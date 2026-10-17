package com.xayah.core.model

data class App(
    val id: Long,
    val packageName: String,
    val label: String,
    // 打标签要按用户维度落库（多开应用同名不同用户），列表里必须带出来
    val userId: Int,
    val preserveId: Long,
    val preserveIndex: Int,
    val lastBackupTime: Long,
    val isSystemApp: Boolean,
    val selectionFlag: Int,
    val selected: Boolean,
    // 应用级备注（列表里用于分辨应用，详见 AppNoteEntity）
    val note: String = "",
)
