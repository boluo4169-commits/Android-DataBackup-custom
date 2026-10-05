package com.xayah.core.database

import androidx.room.DeleteColumn
import androidx.room.DeleteTable
import androidx.room.RenameColumn
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    @RenameColumn(
        tableName = "DirectoryEntity",
        fromColumnName = "directoryType",
        toColumnName = "opType",
    )
    @RenameColumn(
        tableName = "PackageBackupOperation",
        fromColumnName = "apkLog",
        toColumnName = "apk_log",
    )
    @RenameColumn(
        tableName = "PackageBackupOperation",
        fromColumnName = "userLog",
        toColumnName = "user_log",
    )
    @RenameColumn(
        tableName = "PackageBackupOperation",
        fromColumnName = "userDeLog",
        toColumnName = "userDe_log",
    )
    @RenameColumn(
        tableName = "PackageBackupOperation",
        fromColumnName = "dataLog",
        toColumnName = "data_log",
    )
    @RenameColumn(
        tableName = "PackageBackupOperation",
        fromColumnName = "obbLog",
        toColumnName = "obb_log",
    )
    @RenameColumn(
        tableName = "PackageBackupOperation",
        fromColumnName = "mediaLog",
        toColumnName = "media_log",
    )
    @RenameColumn(
        tableName = "PackageBackupOperation",
        fromColumnName = "apkState",
        toColumnName = "apk_state",
    )
    @RenameColumn(
        tableName = "PackageBackupOperation",
        fromColumnName = "userState",
        toColumnName = "user_state",
    )
    @RenameColumn(
        tableName = "PackageBackupOperation",
        fromColumnName = "userDeState",
        toColumnName = "userDe_state",
    )
    @RenameColumn(
        tableName = "PackageBackupOperation",
        fromColumnName = "dataState",
        toColumnName = "data_state",
    )
    @RenameColumn(
        tableName = "PackageBackupOperation",
        fromColumnName = "obbState",
        toColumnName = "obb_state",
    )
    @RenameColumn(
        tableName = "PackageBackupOperation",
        fromColumnName = "mediaState",
        toColumnName = "media_state",
    )
    @RenameColumn(
        tableName = "PackageRestoreOperation",
        fromColumnName = "apkLog",
        toColumnName = "apk_log",
    )
    @RenameColumn(
        tableName = "PackageRestoreOperation",
        fromColumnName = "userLog",
        toColumnName = "user_log",
    )
    @RenameColumn(
        tableName = "PackageRestoreOperation",
        fromColumnName = "userDeLog",
        toColumnName = "userDe_log",
    )
    @RenameColumn(
        tableName = "PackageRestoreOperation",
        fromColumnName = "dataLog",
        toColumnName = "data_log",
    )
    @RenameColumn(
        tableName = "PackageRestoreOperation",
        fromColumnName = "obbLog",
        toColumnName = "obb_log",
    )
    @RenameColumn(
        tableName = "PackageRestoreOperation",
        fromColumnName = "mediaLog",
        toColumnName = "media_log",
    )
    @RenameColumn(
        tableName = "PackageRestoreOperation",
        fromColumnName = "apkState",
        toColumnName = "apk_state",
    )
    @RenameColumn(
        tableName = "PackageRestoreOperation",
        fromColumnName = "userState",
        toColumnName = "user_state",
    )
    @RenameColumn(
        tableName = "PackageRestoreOperation",
        fromColumnName = "userDeState",
        toColumnName = "userDe_state",
    )
    @RenameColumn(
        tableName = "PackageRestoreOperation",
        fromColumnName = "dataState",
        toColumnName = "data_state",
    )
    @RenameColumn(
        tableName = "PackageRestoreOperation",
        fromColumnName = "obbState",
        toColumnName = "obb_state",
    )
    @RenameColumn(
        tableName = "PackageRestoreOperation",
        fromColumnName = "mediaState",
        toColumnName = "media_state",
    )
    @RenameColumn(
        tableName = "MediaBackupOperationEntity",
        fromColumnName = "opLog",
        toColumnName = "data_log",
    )
    @RenameColumn(
        tableName = "MediaBackupOperationEntity",
        fromColumnName = "opState",
        toColumnName = "data_state",
    )
    @RenameColumn(
        tableName = "MediaBackupOperationEntity",
        fromColumnName = "state",
        toColumnName = "mediaState",
    )
    @RenameColumn(
        tableName = "MediaRestoreOperationEntity",
        fromColumnName = "opLog",
        toColumnName = "data_log",
    )
    @RenameColumn(
        tableName = "MediaRestoreOperationEntity",
        fromColumnName = "opState",
        toColumnName = "data_state",
    )
    @RenameColumn(
        tableName = "MediaRestoreOperationEntity",
        fromColumnName = "state",
        toColumnName = "mediaState",
    )
    class Schema2to3 : AutoMigrationSpec

    @DeleteTable(
        tableName = "LogEntity"
    )
    @DeleteColumn(
        tableName = "DirectoryEntity",
        columnName = "opType"
    )
    @DeleteTable(
        tableName = "TaskEntity"
    )
    @DeleteTable(
        tableName = "CmdEntity"
    )
    @DeleteTable(
        tableName = "PackageBackupEntire"
    )
    @DeleteTable(
        tableName = "PackageBackupOperation"
    )
    @DeleteTable(
        tableName = "PackageRestoreEntire"
    )
    @DeleteTable(
        tableName = "PackageRestoreOperation"
    )
    @DeleteTable(
        tableName = "MediaBackupEntity"
    )
    @DeleteTable(
        tableName = "MediaBackupOperationEntity"
    )
    @DeleteTable(
        tableName = "MediaRestoreEntity"
    )
    @DeleteTable(
        tableName = "MediaRestoreOperationEntity"
    )
    @DeleteTable(
        tableName = "CloudEntity"
    )
    class Schema3to4 : AutoMigrationSpec

    @DeleteColumn(
        tableName = "TaskDetailPackageEntity",
        columnName = "packageEntity_extraInfo_existed"
    )
    @DeleteColumn(
        tableName = "PackageEntity",
        columnName = "extraInfo_existed"
    )
    @DeleteColumn(
        tableName = "PackageEntity",
        columnName = "extraInfo_labels"
    )
    @DeleteColumn(
        tableName = "MediaEntity",
        columnName = "extraInfo_labels"
    )
    @DeleteColumn(
        tableName = "TaskDetailPackageEntity",
        columnName = "packageEntity_extraInfo_labels"
    )
    @DeleteColumn(
        tableName = "TaskDetailMediaEntity",
        columnName = "mediaEntity_extraInfo_labels"
    )
    class Schema5to6 : AutoMigrationSpec
}

/**
 * 手写迁移：Room 的 automatic migration 只覆盖「增删列 / 增删表」，
 * **主键变更它处理不了**（会把它当成「新增 NOT NULL 列」然后报错）。
 */
object ManualMigrations {
    /**
     * v9 → v10：备注表 AppNoteEntity 的主键由 (packageName, userId) 扩为
     * (packageName, userId, preserveId, cloud, backupDir) —— 备注粒度从「应用级」改为「版本级」。
     *
     * 直接重建该表：表里只有开发期测试数据（v9 从未对外发布），丢掉不影响任何用户。
     * Room 的迁移校验走 PRAGMA 比对实际表结构，因此这里的建表语句只需结构正确。
     */
    val MIGRATION_9_10: Migration = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `AppNoteEntity`")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `AppNoteEntity` (" +
                    "`packageName` TEXT NOT NULL, " +
                    "`userId` INTEGER NOT NULL, " +
                    "`preserveId` INTEGER NOT NULL, " +
                    "`cloud` TEXT NOT NULL, " +
                    "`backupDir` TEXT NOT NULL, " +
                    "`note` TEXT NOT NULL, " +
                    "PRIMARY KEY(`packageName`, `userId`, `preserveId`, `cloud`, `backupDir`))"
            )
        }
    }
}
