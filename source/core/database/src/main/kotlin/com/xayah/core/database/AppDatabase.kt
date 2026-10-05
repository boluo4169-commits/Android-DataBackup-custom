package com.xayah.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.xayah.core.database.dao.AppNoteDao
import com.xayah.core.database.dao.CloudDao
import com.xayah.core.database.dao.DirectoryDao
import com.xayah.core.database.dao.LabelDao
import com.xayah.core.database.dao.MediaDao
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.database.dao.ScheduleDao
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.database.util.StringListConverters
import com.xayah.core.model.database.AppNoteEntity
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.DirectoryEntity
import com.xayah.core.model.database.LabelAppCrossRefEntity
import com.xayah.core.model.database.LabelEntity
import com.xayah.core.model.database.LabelFileCrossRefEntity
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.ScheduleEntity
import com.xayah.core.model.database.TaskDetailMediaEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.database.TaskEntity

@Database(
    version = 10,
    exportSchema = true,
    entities = [
        PackageEntity::class,
        AppNoteEntity::class,
        MediaEntity::class,
        DirectoryEntity::class,
        CloudEntity::class,
        TaskEntity::class,
        TaskDetailPackageEntity::class,
        TaskDetailMediaEntity::class,
        ProcessingInfoEntity::class,
        LabelEntity::class,
        LabelAppCrossRefEntity::class,
        LabelFileCrossRefEntity::class,
        ScheduleEntity::class,
    ],
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3, spec = DatabaseMigrations.Schema2to3::class),
        AutoMigration(from = 3, to = 4, spec = DatabaseMigrations.Schema3to4::class),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6, spec = DatabaseMigrations.Schema5to6::class),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        // v9：新增备注表 AppNoteEntity（纯新增表，Room 可直接自动迁移）
        AutoMigration(from = 8, to = 9),
        // v9 → v10 是备注表主键变更，Room 的自动迁移不支持（会当成「新增列」而失败），
        // 改由 ManualMigrations.MIGRATION_9_10 手写重建该表（见 DatabaseMigration.kt）。
    ]
)
@TypeConverters(StringListConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun packageDao(): PackageDao
    abstract fun mediaDao(): MediaDao
    abstract fun taskDao(): TaskDao
    abstract fun directoryDao(): DirectoryDao
    abstract fun cloudDao(): CloudDao
    abstract fun labelDao(): LabelDao
    abstract fun appNoteDao(): AppNoteDao
    abstract fun scheduleDao(): ScheduleDao
}
