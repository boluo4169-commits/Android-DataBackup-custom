package com.xayah.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.xayah.core.model.database.ScheduleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Upsert
    suspend fun upsert(schedule: ScheduleEntity): Long

    @Query("SELECT * FROM ScheduleEntity WHERE id = :id LIMIT 1")
    suspend fun query(id: Long): ScheduleEntity?

    @Query("SELECT * FROM ScheduleEntity ORDER BY createdAt ASC")
    fun queryAllFlow(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM ScheduleEntity WHERE enabled = 1")
    suspend fun queryEnabled(): List<ScheduleEntity>

    @Query("DELETE FROM ScheduleEntity WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE ScheduleEntity SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE ScheduleEntity SET lastTriggeredAt = :lastTriggeredAt, nextTriggerAt = :nextTriggerAt WHERE id = :id")
    suspend fun updateTriggerTime(id: Long, lastTriggeredAt: Long, nextTriggerAt: Long)
}
