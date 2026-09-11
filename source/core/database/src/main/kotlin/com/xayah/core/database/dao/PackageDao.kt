package com.xayah.core.database.dao

import androidx.room.Dao
import androidx.room.MapColumn
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.xayah.core.model.CompressionType
import com.xayah.core.model.OpType
import com.xayah.core.model.database.PackageDataStatesEntity
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.PackageUpdateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PackageDao {
    @Upsert(entity = PackageEntity::class)
    suspend fun upsert(items: List<PackageEntity>)

    @Upsert(entity = PackageEntity::class)
    suspend fun upsert(item: PackageEntity)

    @Query(
        "SELECT * FROM PackageEntity WHERE" +
                " indexInfo_packageName = :packageName AND indexInfo_opType = :opType AND indexInfo_userId = :userId AND indexInfo_preserveId = :preserveId" +
                " AND indexInfo_cloud = :cloud AND indexInfo_backupDir = :backupDir" +
                " LIMIT 1"
    )
    suspend fun query(packageName: String, opType: OpType, userId: Int, preserveId: Long, cloud: String, backupDir: String): PackageEntity?

    @Query(
        "SELECT * FROM PackageEntity WHERE" +
                " indexInfo_packageName = :packageName AND indexInfo_opType = :opType AND indexInfo_userId = :userId" +
                " LIMIT 1"
    )
    suspend fun query(packageName: String, opType: OpType, userId: Int): PackageEntity?

    @Query(
        "SELECT * FROM PackageEntity WHERE" +
                " indexInfo_packageName = :packageName AND indexInfo_opType = :opType AND indexInfo_userId = :userId" +
                " AND indexInfo_cloud = :cloud AND indexInfo_backupDir = :backupDir"
    )
    suspend fun query(packageName: String, opType: OpType, userId: Int, cloud: String, backupDir: String): List<PackageEntity>

    @Query(
        "SELECT indexInfo_packageName FROM PackageEntity WHERE" +
                " indexInfo_opType = :opType AND indexInfo_userId = :userId" +
                " AND extraInfo_blocked = 0"
    )
    suspend fun queryPackageNamesByUserId(opType: OpType, userId: Int): List<String>

    @Query(
        "SELECT DISTINCT indexInfo_packageName FROM PackageEntity WHERE" +
                " indexInfo_opType = :opType AND indexInfo_userId = :userId"
    )
    suspend fun queryPkgSetByUserId(opType: OpType, userId: Int): List<String>

    @Query(
        "SELECT * FROM PackageEntity WHERE" +
                " indexInfo_opType = :opType AND indexInfo_userId = :userId"
    )
    suspend fun queryPkgEntitiesByUserId(opType: OpType, userId: Int): List<PackageEntity>

    @Query("SELECT * FROM PackageEntity WHERE indexInfo_opType = :opType AND extraInfo_firstUpdated = :firstUpdated")
    suspend fun queryFirstUpdatedApps(opType: OpType, firstUpdated: Boolean): List<PackageEntity>

    @Query(
        "DELETE FROM PackageEntity WHERE indexInfo_opType = :opType AND indexInfo_userId = :userId AND indexInfo_packageName in (:packageNames)"
    )
    suspend fun deleteByPkgNames(opType: OpType, userId: Int, packageNames: Set<String>)

    @Query(
        "SELECT * FROM PackageEntity WHERE" +
                " indexInfo_packageName = :packageName AND indexInfo_opType = :opType AND indexInfo_userId = :userId AND indexInfo_preserveId = :preserveId AND indexInfo_compressionType = :ct" +
                " AND indexInfo_cloud = :cloud AND indexInfo_backupDir = :backupDir" +
                " LIMIT 1"
    )
    suspend fun query(packageName: String, opType: OpType, userId: Int, preserveId: Long, ct: CompressionType, cloud: String, backupDir: String): PackageEntity?

    @Query("SELECT * FROM PackageEntity WHERE extraInfo_activated = 1 AND indexInfo_opType = :opType")
    suspend fun queryActivated(opType: OpType): List<PackageEntity>

    @Query("SELECT * FROM PackageEntity WHERE extraInfo_activated = 1 AND indexInfo_opType = :opType AND indexInfo_cloud = :cloud AND indexInfo_backupDir = :backupDir")
    suspend fun queryActivated(opType: OpType, cloud: String, backupDir: String): List<PackageEntity>

    @Query("UPDATE PackageEntity SET extraInfo_activated = 0 WHERE indexInfo_opType = :opType")
    suspend fun clearActivated(opType: OpType)

    /**
     * 清理「所属用户已不存在」的激活状态（多开空间被摧毁后其 BACKUP 记录不会被 full/fastInitialize 遍历到）。
     * 调用方必须保证 userIds 非空 —— 空集合会被拼成 NOT IN () 且语义上等于"清掉全部勾选"。
     */
    @Query(
        "UPDATE PackageEntity SET extraInfo_activated = 0 WHERE" +
                " indexInfo_opType = :opType AND indexInfo_userId NOT IN (:userIds)"
    )
    suspend fun clearActivatedNotInUsers(opType: OpType, userIds: List<Int>)

    /**
     * 清理系统应用的激活状态（全部用户空间）。
     * 「加载系统应用」关闭后，系统应用不该再处于勾选状态：列表按开关过滤后看不到它们，
     * 但备份取的是全局 queryActivated —— 残留的勾选会变成看不见却照样被备份的幽灵。
     */
    @Query(
        "UPDATE PackageEntity SET extraInfo_activated = 0 WHERE" +
                " indexInfo_opType = :opType AND (packageInfo_flags & :systemFlag) != 0"
    )
    suspend fun clearActivatedSystemApps(opType: OpType, systemFlag: Int)

    @Query("UPDATE PackageEntity SET extraInfo_activated = :activated WHERE id = :id")
    suspend fun activateById(id: Long, activated: Boolean)

    @Query("UPDATE PackageEntity SET extraInfo_activated = :activated WHERE id in (:ids)")
    suspend fun activateByIds(ids: List<Long>, activated: Boolean)

    @Query("UPDATE PackageEntity SET extraInfo_activated = NOT extraInfo_activated WHERE id in (:ids)")
    suspend fun reverseActivatedByIds(ids: List<Long>)

    @Query("UPDATE PackageEntity SET extraInfo_activated = 0, extraInfo_blocked = 1 WHERE id in (:ids)")
    suspend fun blockByIds(ids: List<Long>)

    @Query("DELETE FROM PackageEntity WHERE id in (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM PackageEntity WHERE id = :id")
    suspend fun queryById(id: Long): PackageEntity?

    @Query("SELECT * FROM PackageEntity WHERE id = :id")
    fun queryFlowById(id: Long): Flow<PackageEntity?>

    @Update(PackageEntity::class)
    suspend fun updatePackageDataStates(items: List<PackageDataStatesEntity>)

    @Update(PackageEntity::class)
    suspend fun update(items: List<PackageUpdateEntity>)

    @Update(PackageEntity::class)
    suspend fun update(item: PackageUpdateEntity)

    @Update(PackageEntity::class)
    suspend fun update(item: PackageEntity)

    @Query(
        "UPDATE PackageEntity" +
                " SET dataStates_apkState = :apk," +
                " dataStates_userState = :user," +
                " dataStates_userDeState = :userDe," +
                " dataStates_dataState = :data," +
                " dataStates_obbState = :obb," +
                " dataStates_mediaState = :media" +
                " WHERE id = :id"
    )
    suspend fun selectDataItemsById(id: Long, apk: String, user: String, userDe: String, data: String, obb: String, media: String)

    @Query(
        "SELECT * FROM PackageEntity WHERE" +
                " indexInfo_packageName = :packageName AND indexInfo_opType = :opType AND indexInfo_userId = :userId AND indexInfo_preserveId = :preserveId" +
                " LIMIT 1"
    )
    fun queryFlow(packageName: String, opType: OpType, userId: Int, preserveId: Long): Flow<PackageEntity?>

    @Query(
        "SELECT COUNT(*) FROM PackageEntity WHERE" +
                " indexInfo_opType = :opType AND extraInfo_blocked = :blocked"
    )
    fun countPackagesFlow(opType: OpType, blocked: Boolean): Flow<Long>

    /**
     * 总数（排除系统应用）。「加载系统应用」关闭时使用：数据库里可能残留之前开启时入库的
     * 系统应用记录（initialize 只跳过新增、不删除已有），此时顶部计数应只统计第三方应用。
     */
    @Query(
        "SELECT COUNT(*) FROM PackageEntity WHERE" +
                " indexInfo_opType = :opType AND extraInfo_blocked = :blocked AND" +
                " (packageInfo_flags & :systemFlag) = 0"
    )
    fun countNonSystemPackagesFlow(opType: OpType, blocked: Boolean, systemFlag: Int): Flow<Long>

    @Query(
        "SELECT COUNT(*) FROM PackageEntity WHERE" +
                " indexInfo_opType = :opType AND extraInfo_blocked = :blocked AND extraInfo_activated = 1"
    )
    fun countActivatedPackagesFlow(opType: OpType, blocked: Boolean): Flow<Long>

    /**
     * 已选数（排除系统应用）。与 [countNonSystemPackagesFlow] 配对：关闭「加载系统应用」后，
     * 之前系统应用的勾选（若有）不应计入顶部 (N/M)。
     */
    @Query(
        "SELECT COUNT(*) FROM PackageEntity WHERE" +
                " indexInfo_opType = :opType AND extraInfo_blocked = :blocked AND extraInfo_activated = 1 AND" +
                " (packageInfo_flags & :systemFlag) = 0"
    )
    fun countActivatedNonSystemPackagesFlow(opType: OpType, blocked: Boolean, systemFlag: Int): Flow<Long>

    @Query(
        "SELECT indexInfo_userId, COUNT(*) as iCount FROM PackageEntity WHERE" +
                " indexInfo_opType = :opType AND extraInfo_blocked = :blocked AND" +
                " indexInfo_cloud = :cloud AND indexInfo_backupDir = :backupDir AND" +
                " extraInfo_activated = 1 GROUP BY indexInfo_userId"
    )
    fun countUsersMapFlow(opType: OpType, blocked: Boolean, cloud: String, backupDir: String):
            Flow<Map<@MapColumn(columnName = "indexInfo_userId") Int, @MapColumn(columnName = "iCount") Long>>

    @Query(
        "SELECT * FROM PackageEntity WHERE id = :id"
    )
    fun queryPackageFlow(id: Long): Flow<PackageEntity?>

    @Query(
        "SELECT * FROM PackageEntity WHERE" +
                " indexInfo_opType = :opType AND extraInfo_blocked = :blocked"
    )
    fun queryPackagesFlow(opType: OpType, blocked: Boolean): Flow<List<PackageEntity>>

    @Query(
        "SELECT * FROM PackageEntity WHERE" +
                " indexInfo_opType = :opType AND extraInfo_blocked = :blocked"
    )
    suspend fun queryPackages(opType: OpType, blocked: Boolean): List<PackageEntity>

    @Query(
        "SELECT * FROM PackageEntity WHERE" +
                " indexInfo_opType = :opType"
    )
    fun queryPackagesFlow(opType: OpType): Flow<List<PackageEntity>>

    @Query(
        "SELECT * FROM PackageEntity WHERE" +
                " indexInfo_opType = :opType AND indexInfo_cloud = :cloud AND indexInfo_backupDir = :backupDir"
    )
    fun queryPackagesFlow(opType: OpType, cloud: String, backupDir: String): Flow<List<PackageEntity>>

    @Query(
        "SELECT DISTINCT indexInfo_userId FROM PackageEntity WHERE" +
                " indexInfo_opType = :opType"
    )
    suspend fun queryUserIds(opType: OpType): List<Int>

    @Query(
        "SELECT DISTINCT indexInfo_userId FROM PackageEntity WHERE" +
                " indexInfo_opType = :opType"
    )
    fun queryUserIdsFlow(opType: OpType): Flow<List<Int>>

    @Query(
        "SELECT * FROM PackageEntity WHERE" +
                " indexInfo_opType = :opType AND indexInfo_cloud = :cloud AND indexInfo_backupDir = :backupDir"
    )
    suspend fun queryPackages(opType: OpType, cloud: String, backupDir: String): List<PackageEntity>

    @Query(
        "UPDATE PackageEntity" +
                " SET extraInfo_blocked = :blocked" +
                " WHERE id = :id"
    )
    suspend fun setBlocked(id: Long, blocked: Boolean)

    @Query(
        "UPDATE PackageEntity" +
                " SET extraInfo_enabled = :enabled" +
                " WHERE id = :id"
    )
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query(
        "UPDATE PackageEntity SET extraInfo_blocked = 0"
    )
    suspend fun clearBlocked()

    @Query("DELETE FROM PackageEntity WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM PackageEntity WHERE indexInfo_backupDir = :backupDir")
    suspend fun delete(backupDir: String)
}
