package com.xayah.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.xayah.core.model.database.AppNoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppNoteDao {
    @Upsert(entity = AppNoteEntity::class)
    suspend fun upsert(item: AppNoteEntity)

    /** 列表页与详情页共用：一次性读全部备注，在内存里按记录键匹配（备注是短文本，量级 = 备份记录数）。 */
    @Query("SELECT * FROM AppNoteEntity")
    fun queryAllFlow(): Flow<List<AppNoteEntity>>

    @Query(
        "DELETE FROM AppNoteEntity WHERE packageName = :packageName AND userId = :userId" +
            " AND preserveId = :preserveId AND cloud = :cloud AND backupDir = :backupDir"
    )
    suspend fun delete(packageName: String, userId: Int, preserveId: Long, cloud: String, backupDir: String)
}
