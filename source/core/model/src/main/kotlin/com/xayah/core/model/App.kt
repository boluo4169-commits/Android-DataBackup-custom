package com.xayah.core.model

data class App(
    val id: Long,
    val packageName: String,
    val label: String,
    val preserveId: Long,
    val preserveIndex: Int,
    val lastBackupTime: Long,
    val isSystemApp: Boolean,
    val selectionFlag: Int,
    val selected: Boolean,
    // 应用级备注（列表里用于分辨应用，详见 AppNoteEntity）
    val note: String = "",
)
