package com.xayah.core.service.medium.backup

import android.annotation.SuppressLint
import com.xayah.core.common.util.toLineString
import com.xayah.core.datastore.readBackupConfigs
import com.xayah.core.datastore.readBackupItself
import com.xayah.core.datastore.readMaxPreserveMediaCount
import com.xayah.core.datastore.readMediaBackupStrategy
import com.xayah.core.datastore.readResetBackupList
import com.xayah.core.datastore.saveLastBackupTime
import com.xayah.core.model.DataType
import com.xayah.core.model.MediaBackupStrategy
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.pickExpiredPreserves
import com.xayah.core.model.sanitizeMaxPreserveMediaCount
import com.xayah.core.model.shouldArchiveMainBackup
import com.xayah.core.model.shouldSkipExistingBackup
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
            preserveId = DateUtil.nextPreserveTimestamp(preserveId)
            archived = archived.copy(indexInfo = archived.indexInfo.copy(preserveId = preserveId))
            dst = "${mFilesDir}/${archived.archivesRelativeDir}"
        }
        val configSrc = PathUtil.getMediaRestoreConfigDst(src)
        // writeJson 失败只返回 ShellResult(code=-1)，不抛异常；必须拦在 rename 之前，
        // 否则会把一个没有 config 的目录改名成历史版本。
        if (mRootService.writeJson(data = archived, dst = configSrc).isSuccess.not()) {
            log { "Failed to write archived config before rename: $configSrc" }
            return@runCatching null
        }
        if (mRootService.renameTo(src, dst).not()) {
            mRootService.writeJson(data = existingMain, dst = configSrc)
            log { "Failed to archive media backup: $src -> $dst" }
            return@runCatching null
        }
        mMediaDao.upsert(archived)
        mLastArchive = ArchiveRollback(original = existingMain, archived = archived, src = src, dst = dst)
        log { "Archived previous media backup: ${archived.archivesRelativeDir}" }
        dst
    }.onFailure {
        log { "Failed to archive media backup: ${it.message}" }
    }.getOrNull()

    /** 删除超出上限的历史版本目录；云端子类 override 用远端删除。 */
    protected open suspend fun deleteMediaArchiveDir(old: MediaEntity) {
        // 删除失败时保留 DB 记录，否则目录还在、记录没了，下次扫描会被当成新版本读回来。
        val path = "${mFilesDir}/${old.archivesRelativeDir}"
        if (mRootService.deleteRecursively(path)) {
            mMediaDao.delete(old.id)
        } else {
            log { "Failed to delete preserved media backup dir, keeping DB record: $path" }
        }
    }

    protected open suspend fun onConfigSaved(path: String, archivesRelativeDir: String) {}
    protected open suspend fun onItselfSaved(path: String, entity: ProcessingInfoEntity) {}
    protected open suspend fun onConfigsSaved(path: String, entity: ProcessingInfoEntity) {}
    protected open suspend fun clear() {}

    protected abstract val mMediumBackupUtil: MediumBackupUtil

    /** 本次任务生效的文件备份策略：一进 onProcessing 读一次，afterPostProcessing 复用，避免中途改设置导致不裁剪。 */
    private var mStrategy: MediaBackupStrategy = MediaBackupStrategy.REPLACE

    /** 本次目录把旧主备份归档成历史版本的结果，供压缩/上传失败时回滚。 */
    protected data class ArchiveRollback(
        val original: MediaEntity,
        val archived: MediaEntity,
        val src: String,
        val dst: String,
    )

    /** 每个目录开始前清空；只有「本次备份失败」才用它回滚。 */
    protected var mLastArchive: ArchiveRollback? = null

    /** 本地回滚：清掉失败的新包，把历史版本搬回主目录，恢复 config 与 DB。云端子类 override。 */
    protected open suspend fun rollbackLastArchive(): Boolean = runCatching {
        val a = mLastArchive ?: return@runCatching true
        mRootService.deleteRecursively(a.src)
        if (mRootService.renameTo(a.dst, a.src).not()) return@runCatching false
        mRootService.writeJson(data = a.original, dst = PathUtil.getMediaRestoreConfigDst(a.src))
        mMediaDao.delete(a.archived.id)
        log { "Rolled back archived media backup: ${a.dst} -> ${a.src}" }
        true
    }.onFailure {
        log { "Failed to roll back archived media backup: ${it.message}" }
    }.getOrDefault(false)

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
        mStrategy = mContext.readMediaBackupStrategy().first()
        val strategy = mStrategy
        log { "Media backup strategy: $strategy" }

        mMediaEntities.forEachIndexed { index, media ->
            mLastArchive = null
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

                // 三种策略共用「是否已有主备份」判定。SKIP 必须在创建目录 / 打包 / 上传之前
                // 早退，否则云端会白建一次远端目录、本地也会留下空的暂存目录。
                val mainRestore = mMediaDao.query(
                    OpType.RESTORE, 0L, m.name, m.indexInfo.compressionType, mTaskEntity.cloud, mTaskEntity.backupDir,
                )
                if (shouldSkipExistingBackup(strategy, mainRestore != null)) {
                    media.update(state = OperationState.SKIP)
                    log { "Skipped by SKIP strategy: ${m.name} (existing main backup kept)" }
                    return@executeAtLeast
                }

                val dstDir = "${mFilesDir}/${m.archivesRelativeDir}"
                var restoreEntity = mMediaDao.query(OpType.RESTORE, m.preserveId, m.name, m.indexInfo.compressionType, mTaskEntity.cloud, mTaskEntity.backupDir)
                mRootService.mkdirs(dstDir)
                if (onFileDirCreated(archivesRelativeDir = m.archivesRelativeDir)) {
                    // 只有 COPY 且数据库里确实有主版本记录时归档。首次备份 / 手动拷入的目录
                    // 照常覆盖，避免凭空造出历史版本。
                    val existingMain = if (shouldArchiveMainBackup(strategy, mainRestore != null)) mainRestore else null
                    val beforeCompress: suspend () -> Unit = {
                        if (existingMain != null) archiveMainMediaBackup(existingMain)
                        Unit
                    }
                    backup(m = m, r = restoreEntity, t = media, dstDir = dstDir, beforeCompress = beforeCompress)

                    // 归档发生在压缩/上传之前：只要新包没成功，就把旧主版本搬回来，
                    // 否则主记录（preserveId=0）会指向刚被搬空的目录，默认恢复不可用。
                    if (media.isSuccess.not() && mLastArchive != null) {
                        if (rollbackLastArchive()) log { "Rollback done for ${m.name}" }
                        else log { "Rollback FAILED for ${m.name}; previous main backup is kept as archived version" }
                    }

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
        // 只有「复制」策略会产生历史版本；替换 / 跳过都没必要跑一次清理。
        if (mStrategy == MediaBackupStrategy.COPY) {
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
