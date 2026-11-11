package com.xayah.core.service.medium.backup

import android.annotation.SuppressLint
import com.xayah.core.common.util.toLineString
import com.xayah.core.datastore.readBackupConfigs
import com.xayah.core.datastore.readBackupItself
import com.xayah.core.datastore.readMaxPreserveMediaCount
import com.xayah.core.datastore.readPreserveMediaBackups
import com.xayah.core.datastore.readResetBackupList
import com.xayah.core.datastore.saveLastBackupTime
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.pickExpiredPreserves
import com.xayah.core.model.sanitizeMaxPreserveMediaCount
import com.xayah.core.model.ProcessingInfoType
import com.xayah.core.model.ProcessingType
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.Info
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailMediaEntity
import com.xayah.core.model.util.set
import com.xayah.core.service.R
import com.xayah.core.service.medium.AbstractMediumService
import com.xayah.core.service.util.MediumBackupUtil
import com.xayah.core.util.DateUtil
import com.xayah.core.util.NotificationUtil
import com.xayah.core.util.PathUtil
import kotlinx.coroutines.flow.first

internal abstract class AbstractBackupService : AbstractMediumService() {
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
        val medium = mMediaRepo.queryActivated(OpType.BACKUP)

        medium.forEach { media ->
            mMediaEntities.add(
                TaskDetailMediaEntity(
                    taskId = mTaskEntity.id,
                    mediaEntity = media,
                    mediaInfo = Info(title = mContext.getString(R.string.args_backup, DataType.PACKAGE_MEDIA.type.uppercase())),
                ).apply {
                    id = mTaskDao.upsert(this)
                })
        }
    }

    override suspend fun beforePreprocessing() {
        NotificationUtil.notify(mContext, mNotificationBuilder, mContext.getString(R.string.backing_up), mContext.getString(R.string.preprocessing))
    }

    protected open suspend fun onTargetDirsCreated() {}
    protected open suspend fun onFileDirCreated(archivesRelativeDir: String): Boolean = true
    abstract suspend fun backup(m: MediaEntity, r: MediaEntity?, t: TaskDetailMediaEntity, dstDir: String, beforeCompress: suspend () -> Unit)

    /**
     * 「文件保留历史备份」：把现有主备份归档成 `name@<14位戳>` 的历史版本。
     *
     * **id 必须置 0**：[onProcessing] 在调 [backup] 之前就已查出主版本记录，若这里复用
     * existingMain 的 id 去 upsert，会把主版本记录改成历史版本；随后主版本 upsert 又用旧
     * 对象把它改回 preserveId=0，历史版本凭空消失。应用侧是「先归档再查」所以没这问题。
     *
     * 失败时先把 config 写回旧值再返回 null，避免主目录里留着历史版本的 preserveId
     * 被下次扫描当成幽灵记录。云端子类 override 用远端 rename。
     */
    protected open suspend fun archiveMainMediaBackup(existingMain: MediaEntity): String? = runCatching {
        val src = "${mFilesDir}/${existingMain.archivesRelativeDir}"
        if (mRootService.exists(src).not()) return@runCatching null
        var preserveId = DateUtil.getPreserveTimestamp()
        var archived = existingMain.copy(
            id = 0,
            indexInfo = existingMain.indexInfo.copy(preserveId = preserveId),
        )
        var dst = "${mFilesDir}/${archived.archivesRelativeDir}"
        while (mRootService.exists(dst)) {
            preserveId++
            archived = archived.copy(indexInfo = archived.indexInfo.copy(preserveId = preserveId))
            dst = "${mFilesDir}/${archived.archivesRelativeDir}"
        }
        val configSrc = PathUtil.getMediaRestoreConfigDst(src)
        mRootService.writeJson(data = archived, dst = configSrc)
        if (mRootService.renameTo(src, dst).not()) {
            mRootService.writeJson(data = existingMain, dst = configSrc)
            log { "Failed to archive media backup: $src -> $dst" }
            return@runCatching null
        }
        mMediaDao.upsert(archived)
        log { "Archived previous media backup: ${archived.archivesRelativeDir}" }
        dst
    }.onFailure {
        log { "Failed to archive media backup: ${it.message}" }
    }.getOrNull()

    /** 删除超出上限的历史版本目录；云端子类 override 用远端删除。 */
    protected open suspend fun deleteMediaArchiveDir(old: MediaEntity) {
        mRootService.deleteRecursively("${mFilesDir}/${old.archivesRelativeDir}")
        mMediaDao.delete(old.id)
    }

    protected open suspend fun onConfigSaved(path: String, archivesRelativeDir: String) {}
    protected open suspend fun onItselfSaved(path: String, entity: ProcessingInfoEntity) {}
    protected open suspend fun onConfigsSaved(path: String, entity: ProcessingInfoEntity) {}
    protected open suspend fun clear() {}

    protected abstract val mMediumBackupUtil: MediumBackupUtil

    override suspend fun onPreprocessing(entity: ProcessingInfoEntity) {
        when (entity.infoType) {
            ProcessingInfoType.NECESSARY_PREPARATIONS -> {
                log { "Trying to create: $mFilesDir." }
                mRootService.mkdirs(mFilesDir)
                val isSuccess = runCatchingOnService { onTargetDirsCreated() }
                entity.update(progress = 1f, state = if (isSuccess) OperationState.DONE else OperationState.ERROR)
            }

            else -> {}
        }
    }

    override suspend fun onProcessing() {
        // createTargetDirs() before readStatFs().
        mTaskEntity.update(rawBytes = mTaskRepo.getRawBytes(TaskType.MEDIA), availableBytes = mTaskRepo.getAvailableBytes(OpType.BACKUP), totalBytes = mTaskRepo.getTotalBytes(OpType.BACKUP), totalCount = mMediaEntities.size)
        log { "Task count: ${mMediaEntities.size}." }
        val preserveMediaBackups = mContext.readPreserveMediaBackups().first()
        log { "Preserve media backups: $preserveMediaBackups" }

        mMediaEntities.forEachIndexed { index, media ->
            executeAtLeast {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.backing_up),
                    media.mediaEntity.name,
                    mMediaEntities.size,
                    index
                )
                log { "Current media: ${media.mediaEntity}" }

                media.update(state = OperationState.PROCESSING)
                val m = media.mediaEntity
                val dstDir = "${mFilesDir}/${m.archivesRelativeDir}"
                var restoreEntity = mMediaDao.query(OpType.RESTORE, m.preserveId, m.name, m.indexInfo.compressionType, mTaskEntity.cloud, mTaskEntity.backupDir)
                mRootService.mkdirs(dstDir)
                if (onFileDirCreated(archivesRelativeDir = m.archivesRelativeDir)) {
                    // 只在开关打开且数据库里确实有主版本记录时归档。没有记录（首次备份 /
                    // 手动拷入的目录）就照常覆盖，避免凭空造出历史版本。
                    val existingMain = if (preserveMediaBackups) {
                        mMediaDao.query(OpType.RESTORE, 0L, m.name, m.indexInfo.compressionType, mTaskEntity.cloud, mTaskEntity.backupDir)
                    } else {
                        null
                    }
                    val beforeCompress: suspend () -> Unit = {
                        if (existingMain != null) archiveMainMediaBackup(existingMain)
                        Unit
                    }
                    backup(m = m, r = restoreEntity, t = media, dstDir = dstDir, beforeCompress = beforeCompress)

                    if (media.isSuccess) {
                        // Save config
                        m.extraInfo.lastBackupTime = DateUtil.getTimestamp()
                        val id = restoreEntity?.id ?: 0
                        restoreEntity = m.copy(
                            id = id,
                            indexInfo = m.indexInfo.copy(opType = OpType.RESTORE, cloud = mTaskEntity.cloud, backupDir = mTaskEntity.backupDir),
                            extraInfo = m.extraInfo.copy(existed = true, activated = false)
                        )
                        val configDst = PathUtil.getMediaRestoreConfigDst(dstDir = dstDir)
                        mRootService.writeJson(data = restoreEntity, dst = configDst)
                        onConfigSaved(path = configDst, archivesRelativeDir = m.archivesRelativeDir)
                        mMediaDao.upsert(restoreEntity)
                        mMediaDao.upsert(m)
                        media.update(mediaEntity = m)
                        mTaskEntity.update(successCount = mTaskEntity.successCount + 1)
                    } else {
                        mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                    }
                } else {
                    media.update(state = OperationState.ERROR)
                }
                media.update(state = if (media.isSuccess) OperationState.DONE else OperationState.ERROR)
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

                // D(备份侧对齐)：与 packages 侧同因 —— 开关文案即「备份完成后取消勾选所选项」，
                // 原实现额外要求 failureCount == 0，只要有失败就永远清不掉勾选。
                if (mContext.readResetBackupList().first()) {
                    mMediaDao.clearActivated(OpType.BACKUP)
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
        // 文件「保留历史备份」：按上限清理最旧的历史版本（本地 / 云端由子类分流）。
        // 单个目录清理失败只记日志，不影响其它目录与本次备份结果。
        if (mContext.readPreserveMediaBackups().first()) {
            val maxCount = sanitizeMaxPreserveMediaCount(mContext.readMaxPreserveMediaCount().first())
            mMediaEntities.forEach { media ->
                val m = media.mediaEntity
                runCatching {
                    val versions = mMediaDao.query(OpType.RESTORE, m.name, mTaskEntity.cloud, mTaskEntity.backupDir)
                    val expired = pickExpiredPreserves(versions.map { it.indexInfo.preserveId }, maxCount).toSet()
                    versions.filter { it.indexInfo.preserveId in expired }.forEach { old ->
                        log { "Cleaning old preserved media backup: ${old.archivesRelativeDir}" }
                        deleteMediaArchiveDir(old)
                    }
                }.onFailure {
                    log { "Failed to clean preserved media backups for ${m.name}: ${it.message}" }
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
