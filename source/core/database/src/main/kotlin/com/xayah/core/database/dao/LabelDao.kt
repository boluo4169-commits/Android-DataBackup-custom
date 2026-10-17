package com.xayah.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.xayah.core.model.database.LabelAppCrossRefEntity
import com.xayah.core.model.database.LabelEntity
import com.xayah.core.model.database.LabelFileCrossRefEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LabelDao {
    @Upsert(entity = LabelEntity::class)
    suspend fun upsert(item: LabelEntity): Long

    @Upsert(entity = LabelEntity::class)
    suspend fun upsertLabels(items: List<LabelEntity>)

    @Upsert(entity = LabelAppCrossRefEntity::class)
    suspend fun upsert(item: LabelAppCrossRefEntity): Long

    @Upsert(entity = LabelAppCrossRefEntity::class)
    suspend fun upsertAppRefs(items: List<LabelAppCrossRefEntity>)

    @Upsert(entity = LabelFileCrossRefEntity::class)
    suspend fun upsert(item: LabelFileCrossRefEntity): Long

    @Upsert(entity = LabelFileCrossRefEntity::class)
    suspend fun upsertFileRefs(items: List<LabelFileCrossRefEntity>)

    @Query("SELECT * FROM LabelEntity")
    fun queryLabelsFlow(): Flow<List<LabelEntity>>

    @Query("SELECT * FROM LabelEntity")
    suspend fun queryLabels(): List<LabelEntity>

    @Query("SELECT * FROM LabelAppCrossRefEntity WHERE label in (:labels)")
    suspend fun queryAppRefs(labels: Set<String>): List<LabelAppCrossRefEntity>

    @Query("SELECT * FROM LabelFileCrossRefEntity WHERE label in (:labels)")
    suspend fun queryFileRefs(labels: Set<String>): List<LabelFileCrossRefEntity>

    @Query("SELECT * FROM LabelAppCrossRefEntity")
    suspend fun queryAppRefs(): List<LabelAppCrossRefEntity>

    @Query("SELECT * FROM LabelFileCrossRefEntity")
    suspend fun queryFileRefs(): List<LabelFileCrossRefEntity>

    @Query("SELECT * FROM LabelAppCrossRefEntity")
    fun queryAppRefsFlow(): Flow<List<LabelAppCrossRefEntity>>

    @Query("SELECT * FROM LabelFileCrossRefEntity")
    fun queryFileRefsFlow(): Flow<List<LabelFileCrossRefEntity>>

    @Query("DELETE FROM LabelEntity WHERE label = :label")
    suspend fun delete(label: String)

    @Delete(entity = LabelAppCrossRefEntity::class)
    suspend fun deleteAppRef(item: LabelAppCrossRefEntity)

    @Delete(entity = LabelFileCrossRefEntity::class)
    suspend fun deleteFileRef(item: LabelFileCrossRefEntity)

    @Query("DELETE FROM LabelAppCrossRefEntity WHERE label = :label")
    suspend fun deleteAppRefsByLabel(label: String)

    @Query("DELETE FROM LabelFileCrossRefEntity WHERE label = :label")
    suspend fun deleteFileRefsByLabel(label: String)

    /**
     * 删除标签必须连关联一起删。
     *
     * 两张关联表没有外键约束（Room 未声明 ForeignKey），只删标签行会留下一批指向已不存在标签的
     * 孤儿 ref；它们不会被任何界面展示，但会永久占着库，且按标签查询时能命中幽灵记录。
     */
    @Transaction
    suspend fun deleteLabelCascade(label: String) {
        deleteAppRefsByLabel(label)
        deleteFileRefsByLabel(label)
        delete(label)
    }

    /**
     * 批量打标：建标签 + 写关联合成一个事务。
     *
     * 几百个应用逐条写，中途失败会留下「标签建好了但只挂了一半应用」的半批数据，用户看不出问题。
     */
    @Transaction
    suspend fun attachLabels(labels: List<LabelEntity>, refs: List<LabelAppCrossRefEntity>) {
        upsertLabels(items = labels)
        upsertAppRefs(items = refs)
    }

    @Query(
        "DELETE FROM LabelAppCrossRefEntity WHERE label = :label AND packageName = :packageName" +
            " AND userId = :userId AND preserveId = :preserveId"
    )
    suspend fun deleteAppRefByKey(label: String, packageName: String, userId: Int, preserveId: Long)

    /**
     * 批量移除标签：按关联主键逐条删，一个事务收口（与打标对称）。
     */
    @Transaction
    suspend fun detachLabels(refs: List<LabelAppCrossRefEntity>) {
        refs.forEach { deleteAppRefByKey(it.label, it.packageName, it.userId, it.preserveId) }
    }
}
