package com.xayah.core.model.database

import androidx.room.Entity

/**
 * 备份版本备注：**每一条备份记录独立一条**，用于说明「这个版本是干什么的 / 和别的版本有什么不同」。
 *
 * 主键 = 一条备份记录的完整业务标识：
 * - `packageName` + `userId`：哪个应用的哪个用户空间；
 * - `preserveId`：哪个版本（0 = 主版本，非 0 = 保护版本的时间戳）；
 * - `cloud` + `backupDir`：**存在哪**。同一应用的同名版本可能本地一份、云端一份，
 *   只看 preserveId 会把两者串成同一条备注 —— 所以必须带上存储位置。
 *
 * 为什么不是应用级（只在包名 + 用户上存一条）：
 * 用户区分的是「版本」而不是「应用」—— 同一个应用常有一份「干净初始备份」和一份
 * 「配置完了的备份」，需要各自标注。应用级会让改一个版本时其它版本跟着变，正是要避免的。
 *
 * 与标签的分工：标签（LabelEntity）是可复用的分类、一条能挂多个应用；备注是该版本专属的一段自由文本。
 */
@Entity(primaryKeys = ["packageName", "userId", "preserveId", "cloud", "backupDir"])
data class AppNoteEntity(
    var packageName: String,
    var userId: Int,
    var preserveId: Long,
    var cloud: String,
    var backupDir: String,
    var note: String,
)

/** 备注的唯一键：一条备份记录的完整业务标识（详见 [AppNoteEntity]）。 */
fun noteKeyOf(packageName: String, userId: Int, preserveId: Long, cloud: String, backupDir: String): String =
    "$packageName@$userId@$preserveId@$cloud@$backupDir"

val PackageEntity.noteKey: String
    get() = noteKeyOf(packageName, userId, preserveId, indexInfo.cloud, indexInfo.backupDir)

val AppNoteEntity.noteKey: String
    get() = noteKeyOf(packageName, userId, preserveId, cloud, backupDir)
