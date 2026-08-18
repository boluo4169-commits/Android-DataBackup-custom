package com.xayah.core.service.packages.backup

import android.annotation.SuppressLint
import com.xayah.core.common.util.toLineString
import com.xayah.core.datastore.readBackupConfigs
import com.xayah.core.datastore.readBackupItself
import com.xayah.core.datastore.readKillAppOption
import com.xayah.core.datastore.readMaxPreserveCount
import com.xayah.core.datastore.readPreserveBackups
import com.xayah.core.datastore.readResetBackupList
import com.xayah.core.datastore.saveLastBackupTime
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.ProcessingInfoType
import com.xayah.core.model.ProcessingType
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.Info
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.util.set
import com.xayah.core.service.R
import com.xayah.core.service.model.NecessaryInfo
import com.xayah.core.service.packages.AbstractPackagesService
import com.xayah.core.service.util.PackagesBackupUtil
import com.xayah.core.util.DateUtil
import com.xayah.core.util.NotificationUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.PreparationUtil
import kotlinx.coroutines.flow.first

internal abstract class AbstractBackupService : AbstractPackagesService() {
    override suspend fun onInitializingPreprocessingEntities(entities: MutableList<ProcessingInfoEntity>) {
        entities.apply {
            add(ProcessingInfoEntity(
                taskId = mTaskEntity.id,
                title = mContext.getString(R.string.necessary_preparations),
                type = ProcessingType.PREPROCESSING,
                infoType = ProcessingInfoType.NECESSARY_PREPARATIONS
            ).apply {
                id = mTaskDao.upsert(this)
            })
        }
    }

    override suspend fun onInitializingPostProcessingEntities(entities: MutableList<ProcessingInfoEntity>) {
        entities.apply {
            add(ProcessingInfoEntity(
                taskId = mTaskEntity.id,
                title = mContext.getString(R.string.backup_itself),
                type = ProcessingType.POST_PROCESSING,
                infoType = ProcessingInfoType.BACKUP_ITSELF
            ).apply {
                id = mTaskDao.upsert(this)
            })
            add(ProcessingInfoEntity(
                taskId = mTaskEntity.id,
                title = mContext.getString(R.string.save_icons),
                type = ProcessingType.POST_PROCESSING,
                infoType = ProcessingInfoType.SAVE_ICONS
            ).apply {
                id = mTaskDao.upsert(this)
            })
            add(ProcessingInfoEntity(
                taskId = mTaskEntity.id,
                title = mContext.getString(R.string.necessary_remaining_data_processing),
                type = ProcessingType.POST_PROCESSING,
                infoType = ProcessingInfoType.NECESSARY_REMAINING_DATA_PROCESSING
            ).apply {
                id = mTaskDao.upsert(this)
            })
        }
    }

    @SuppressLint("StringFormatInvalid")
    override suspend fun onInitializing() {
        val packages = mPackageRepo.queryActivated(OpType.BACKUP)
        packages.forEach { pkg ->
            mPkgEntities.add(
                TaskDetailPackageEntity(
                    taskId = mTaskEntity.id,
                    packageEntity = pkg,
                    apkInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_APK.type.uppercase())),
                    userInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_USER.type.uppercase())),
                    userDeInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_USER_DE.type.uppercase())),
                    dataInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_DATA.type.uppercase())),
                    obbInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_OBB.type.uppercase())),
                    mediaInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_MEDIA.type.uppercase())),
                ).apply {
                    id = mTaskDao.upsert(this)
                }
            )
        }
    }

    override suspend fun beforePreprocessing() {
        NotificationUtil.notify(mContext, mNotificationBuilder, mContext.getString(R.string.backing_up), mContext.getString(R.string.preprocessing))
    }

    protected open suspend fun onTargetDirsCreated() {}
    protected open suspend fun onAppDirCreated(archivesRelativeDir: String): Boolean = true
    abstract suspend fun backup(type: DataType, p: PackageEntity, r: PackageEntity?, t: TaskDetailPackageEntity, dstDir: String)
    protected open suspend fun onConfigSaved(path: String, archivesRelativeDir: String) {}
    protected open suspend fun onItselfSaved(path: String, entity: ProcessingInfoEntity) {}
    protected open suspend fun onConfigsSaved(path: String, entity: ProcessingInfoEntity) {}
    protected open suspend fun onIconsSaved(path: String, entity: ProcessingInfoEntity) {}
    protected open suspend fun clear() {}

    /**
     * 归档旧主备份为保护版本（preserveId 从 0 改为时间戳，目录加 @时间戳 后缀）。
     * 本地默认实现操作本地目录；云子类 override 用远程客户端操作，否则远程旧备份会被新备份覆盖。
     */
    protected open suspend fun archiveMainBackup(existingMain: PackageEntity) {
        var preserveId = DateUtil.getPreserveTimestamp()
        var archived = existingMain.copy(indexInfo = existingMain.indexInfo.copy(preserveId = preserveId))
        var dst = "${mAppsDir}/${archived.archivesRelativeDir}"
        while (mRootService.exists(dst)) {
            preserveId++
            archived = existingMain.copy(indexInfo = existingMain.indexInfo.copy(preserveId = preserveId))
            dst = "${mAppsDir}/${archived.archivesRelativeDir}"
        }
        val srcNew = "${mAppsDir}/${existingMain.archivesRelativeDir}"
        val src = if (mRootService.exists(srcNew)) srcNew else "${mAppsDir}/${existingMain.legacyArchivesRelativeDir}"
        if (mRootService.exists(src)) {
            mRootService.writeJson(data = archived, dst = PathUtil.getPackageRestoreConfigDst(src))
            mRootService.renameTo(src, dst)
            mPackageDao.upsert(archived)
        }
    }

    /**
     * 删除旧归档目录（保留历史备份超出上限时清理）。本地默认实现操作本地目录；
     * 云子类 override 用远程客户端操作，否则远程旧版本不会被真正删除。
     */
    protected open suspend fun deleteArchiveDir(old: PackageEntity) {
        val dirNew = "${mAppsDir}/${old.archivesRelativeDir}"
        val dir = if (mRootService.exists(dirNew)) dirNew else "${mAppsDir}/${old.legacyArchivesRelativeDir}"
        mRootService.deleteRecursively(dir)
        mPackageDao.delete(old.id)
    }

    protected abstract val mPackagesBackupUtil: PackagesBackupUtil

    private lateinit var necessaryInfo: NecessaryInfo

    override suspend fun onPreprocessing(entity: ProcessingInfoEntity) {
        when (entity.infoType) {
            ProcessingInfoType.NECESSARY_PREPARATIONS -> {
                /**
                 * Somehow the input methods and accessibility services
                 * will be changed after backing up on some devices,
                 * so we restore them manually.
                 */
                necessaryInfo = NecessaryInfo(inputMethods = PreparationUtil.getInputMethods().outString.trim(), accessibilityServices = PreparationUtil.getAccessibilityServices().outString.trim())
                log { "InputMethods: ${necessaryInfo.inputMethods}." }
                log { "AccessibilityServices: ${necessaryInfo.accessibilityServices}." }

                log { "Trying to create: $mAppsDir." }
                log { "Trying to create: $mConfigsDir." }
                mRootService.mkdirs(mAppsDir)
                mRootService.mkdirs(mConfigsDir)
                val isSuccess = runCatchingOnService { onTargetDirsCreated() }
                entity.update(progress = 1f, state = if (isSuccess) OperationState.DONE else OperationState.ERROR)
            }

            else -> {}
        }
    }

    override suspend fun onProcessing() {
        // createTargetDirs() before readStatFs().
        mTaskEntity.update(rawBytes = mTaskRepo.getRawBytes(TaskType.PACKAGE), availableBytes = mTaskRepo.getAvailableBytes(OpType.BACKUP), totalBytes = mTaskRepo.getTotalBytes(OpType.BACKUP), totalCount = mPkgEntities.size)
        log { "Task count: ${mPkgEntities.size}." }

        val killAppOption = mContext.readKillAppOption().first()
        log { "Kill app option: $killAppOption" }

        val preserveBackups = mContext.readPreserveBackups().first()
        log { "Preserve backups: $preserveBackups" }

        mPkgEntities.forEachIndexed { index, pkg ->
            executeAtLeast {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.backing_up),
                    pkg.packageEntity.packageInfo.label,
                    mPkgEntities.size,
                    index
                )
                log { "Current package: ${pkg.packageEntity}" }

                killApp(killAppOption, pkg)

                pkg.update(state = OperationState.PROCESSING)
                // BACKUP 实体 preserveId 恒为 0；新备份始终作为「正常版本」（preserveId=0，无盾牌）
                val cleanP = pkg.packageEntity.copy(indexInfo = pkg.packageEntity.indexInfo.copy(preserveId = 0L))
                // 重新查询设备上当前安装的版本，避免数据库缓存过期导致备份记录版本错误
                val p = runCatching {
                    val info = mContext.packageManager.getPackageInfo(cleanP.packageName, 0)
                    cleanP.copy(
                        packageInfo = cleanP.packageInfo.copy(
                            versionName = info.versionName ?: cleanP.packageInfo.versionName,
                            versionCode = info.longVersionCode,
                        )
                    )
                }.getOrDefault(cleanP)
                val dstDir = "${mAppsDir}/${p.archivesRelativeDir}"

                // 保留历史备份：备份前，把已有的主备份（RESTORE preserveId=0）归档成保护版本（带盾牌序号）
                if (preserveBackups) {
                    val existingMain = mPackageDao.query(p.packageName, OpType.RESTORE, p.userId, 0L, p.indexInfo.compressionType, mTaskEntity.cloud, mTaskEntity.backupDir)
                    if (existingMain != null) {
                        archiveMainBackup(existingMain)
                    }
                }

                var restoreEntity = mPackageDao.query(p.packageName, OpType.RESTORE, p.userId, p.preserveId, p.indexInfo.compressionType, mTaskEntity.cloud, mTaskEntity.backupDir)
                mRootService.mkdirs(dstDir)
                if (onAppDirCreated(archivesRelativeDir = p.archivesRelativeDir)) {
                    backup(type = DataType.PACKAGE_APK, p = p, r = restoreEntity, t = pkg, dstDir = dstDir)
                    backup(type = DataType.PACKAGE_USER, p = p, r = restoreEntity, t = pkg, dstDir = dstDir)
                    backup(type = DataType.PACKAGE_USER_DE, p = p, r = restoreEntity, t = pkg, dstDir = dstDir)
                    backup(type = DataType.PACKAGE_DATA, p = p, r = restoreEntity, t = pkg, dstDir = dstDir)
                    backup(type = DataType.PACKAGE_OBB, p = p, r = restoreEntity, t = pkg, dstDir = dstDir)
                    backup(type = DataType.PACKAGE_MEDIA, p = p, r = restoreEntity, t = pkg, dstDir = dstDir)
                    mPackagesBackupUtil.backupPermissions(p = p)
                    mPackagesBackupUtil.backupSsaid(p = p)

                    if (pkg.isSuccess) {
                        // Save config
                        p.extraInfo.lastBackupTime = DateUtil.getTimestamp()
                        val id = restoreEntity?.id ?: 0
                        restoreEntity = p.copy(
                            id = id,
                            indexInfo = p.indexInfo.copy(opType = OpType.RESTORE, cloud = mTaskEntity.cloud, backupDir = mTaskEntity.backupDir),
                            extraInfo = p.extraInfo.copy(activated = false)
                        )
                        val configDst = PathUtil.getPackageRestoreConfigDst(dstDir = dstDir)
                        mRootService.writeJson(data = restoreEntity, dst = configDst)
                        onConfigSaved(path = configDst, archivesRelativeDir = p.archivesRelativeDir)
                        mPackageDao.upsert(restoreEntity)
                        mPackageDao.upsert(cleanP)
                        pkg.update(packageEntity = cleanP)
                        mTaskEntity.update(successCount = mTaskEntity.successCount + 1)
                    } else {
                        mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                    }
                } else {
                    pkg.update(dataType = DataType.PACKAGE_APK, state = OperationState.ERROR)
                    pkg.update(dataType = DataType.PACKAGE_USER, state = OperationState.ERROR)
                    pkg.update(dataType = DataType.PACKAGE_USER_DE, state = OperationState.ERROR)
                    pkg.update(dataType = DataType.PACKAGE_DATA, state = OperationState.ERROR)
                    pkg.update(dataType = DataType.PACKAGE_OBB, state = OperationState.ERROR)
                    pkg.update(dataType = DataType.PACKAGE_MEDIA, state = OperationState.ERROR)
                }
                pkg.update(state = if (pkg.isSuccess) OperationState.DONE else OperationState.ERROR)
            }
            mTaskEntity.update(processingIndex = mTaskEntity.processingIndex + 1)
        }
    }

    override suspend fun onPostProcessing(entity: ProcessingInfoEntity) {
        when (entity.infoType) {
            ProcessingInfoType.BACKUP_ITSELF -> {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.backing_up),
                    mContext.getString(R.string.backup_itself)
                )
                if (mContext.readBackupItself().first()) {
                    log { "Backup itself enabled." }
                    mCommonBackupUtil.backupItself(dstDir = mRootDir).apply {
                        entity.set(state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = outString)
                        if (isSuccess) {
                            onItselfSaved(path = mCommonBackupUtil.getItselfDst(mRootDir), entity = entity)
                        }
                    }
                    entity.update(progress = 1f)
                } else {
                    entity.update(progress = 1f, state = OperationState.SKIP)
                }
            }

            ProcessingInfoType.SAVE_ICONS -> {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.backing_up),
                    mContext.getString(R.string.save_icons)
                )
                mPackagesBackupUtil.backupIcons(dstDir = mConfigsDir).apply {
                    entity.set(state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = outString)
                    if (isSuccess) {
                        onIconsSaved(path = mPackagesBackupUtil.getIconsDst(mConfigsDir), entity = entity)
                    }
                }
                entity.update(progress = 1f)
            }

            ProcessingInfoType.NECESSARY_REMAINING_DATA_PROCESSING -> {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.backing_up),
                    mContext.getString(R.string.wait_for_remaining_data_processing)
                )

                var isSuccess = true
                val out = mutableListOf<String>()
                if (mContext.readBackupConfigs().first()) {
                    log { "Backup configs enabled." }
                    mCommonBackupUtil.backupConfigs(dstDir = mConfigsDir).also { result ->
                        if (result.isSuccess.not()) {
                            isSuccess = false
                        }
                        out.add(result.outString)
                        if (result.isSuccess) {
                            onConfigsSaved(path = mCommonBackupUtil.getConfigsDst(mConfigsDir), entity = entity)
                        }
                    }
                }
                entity.update(progress = 0.5f)

                // Restore keyboard and services.
                if (necessaryInfo.inputMethods.isNotEmpty()) {
                    PreparationUtil.setInputMethods(inputMethods = necessaryInfo.inputMethods)
                    log { "InputMethods restored: ${necessaryInfo.inputMethods}." }
                } else {
                    log { "InputMethods is empty, skip restoring." }
                }
                if (necessaryInfo.accessibilityServices.isNotEmpty()) {
                    PreparationUtil.setAccessibilityServices(accessibilityServices = necessaryInfo.accessibilityServices)
                    log { "AccessibilityServices restored: ${necessaryInfo.accessibilityServices}." }
                } else {
                    log { "AccessibilityServices is empty, skip restoring." }
                }
                if (mContext.readResetBackupList().first() && mTaskEntity.failureCount == 0) {
                    mPackageDao.clearActivated(OpType.BACKUP)
                }
                if (runCatchingOnService { clear() }.not()) {
                    isSuccess = false
                }
                entity.set(progress = 1f, state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = out.toLineString())
            }

            else -> {}
        }
    }

    override suspend fun afterPostProcessing() {
        // 保留历史备份：清理超出最大版本数的旧版本
        if (mContext.readPreserveBackups().first()) {
            val maxPreserveCount = mContext.readMaxPreserveCount().first().coerceAtLeast(1)
            mPkgEntities.forEach { pkg ->
                val p = pkg.packageEntity
                runCatching {
                    val allVersions = mPackageDao.query(p.packageName, OpType.RESTORE, p.userId, mTaskEntity.cloud, mTaskEntity.backupDir)
                    val preserved = allVersions.filter { it.preserveId != 0L }.sortedByDescending { it.preserveId }
                    if (preserved.size > maxPreserveCount) {
                        preserved.drop(maxPreserveCount).forEach { old ->
                            log { "Cleaning old preserved backup: ${old.archivesRelativeDir}" }
                            deleteArchiveDir(old)
                        }
                    }
                }
            }
        }
        mContext.saveLastBackupTime(mEndTimestamp)
        val time = DateUtil.getShortRelativeTimeSpanString(context = mContext, time1 = mStartTimestamp, time2 = mEndTimestamp)
        NotificationUtil.notify(
            mContext,
            mNotificationBuilder,
            mContext.getString(R.string.backup_completed),
            "${time}, ${mTaskEntity.successCount} ${mContext.getString(R.string.succeed)}, ${mTaskEntity.failureCount} ${mContext.getString(R.string.failed)}",
            ongoing = false
        )
    }
}
