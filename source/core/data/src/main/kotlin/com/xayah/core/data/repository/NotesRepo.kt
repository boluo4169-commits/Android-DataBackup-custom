package com.xayah.core.data.repository

import com.xayah.core.database.dao.AppNoteDao
import com.xayah.core.datastore.di.DbDispatchers.Default
import com.xayah.core.datastore.di.Dispatcher
import com.xayah.core.model.database.AppNoteEntity
import com.xayah.core.model.database.PackageEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

/**
 * 备份版本备注（详见 [AppNoteEntity]）。
 *
 * 备注挂在**一条备份记录**上（包名 + 用户 + preserveId + 存储位置），
 * 因此同一个应用的每个版本各写各的、互不影响 —— 这正是它与「标签」的区别：
 * 标签是可复用的分类，备注是这个版本专属的说明。
 */
class NotesRepo @Inject constructor(
    @Dispatcher(Default) private val defaultDispatcher: CoroutineDispatcher,
    private val appNoteDao: AppNoteDao,
) {
    /** 列表页与详情页共用：一次性读全部，调用方按记录键在内存里匹配。 */
    fun getNotesFlow(): Flow<List<AppNoteEntity>> = appNoteDao.queryAllFlow().flowOn(defaultDispatcher)

    /** 保存该记录的备注；内容清空时直接删记录 —— 不留空行。 */
    suspend fun setNote(app: PackageEntity, note: String) {
        val trimmed = note.trim()
        if (trimmed.isEmpty()) {
            appNoteDao.delete(
                packageName = app.packageName,
                userId = app.userId,
                preserveId = app.preserveId,
                cloud = app.indexInfo.cloud,
                backupDir = app.indexInfo.backupDir,
            )
        } else {
            appNoteDao.upsert(
                AppNoteEntity(
                    packageName = app.packageName,
                    userId = app.userId,
                    preserveId = app.preserveId,
                    cloud = app.indexInfo.cloud,
                    backupDir = app.indexInfo.backupDir,
                    note = trimmed,
                )
            )
        }
    }
}
