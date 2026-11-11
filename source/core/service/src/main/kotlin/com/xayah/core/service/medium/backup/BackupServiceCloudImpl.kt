package com.xayah.core.service.medium.backup

import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.dao.MediaDao
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailMediaEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.network.client.CloudClient
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.MediumBackupUtil
import com.xayah.core.util.DateUtil
import com.xayah.core.util.PathUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

@AndroidEntryPoint
internal class BackupServiceCloudImpl @Inject constructor() : AbstractBackupService() {
    override val mTAG: String = "BackupServiceCloudImpl"

    @Inject
    override lateinit var mRootService: RemoteRootService

    @Inject
    override lateinit var mPathUtil: PathUtil

    @Inject
    override lateinit var mCommonBackupUtil: CommonBackupUtil

    @Inject
    override lateinit var mTaskDao: TaskDao

    @Inject
    override lateinit var mTaskRepo: TaskRepository

    override val mTaskEntity by lazy {
        TaskEntity(
            id = 0,
            opType = OpType.BACKUP,
            taskType = TaskType.MEDIA,
            startTimestamp = mStartTimestamp,
            endTimestamp = mEndTimestamp,
            backupDir = mRootDir,
            isProcessing = true,
        )
    }

    override suspend fun onTargetDirsCreated() {
        mCloudRepo.getClient().also { (c, e) ->
            mCloudEntity = e
            mClient = c
        }

        mRemotePath = mCloudEntity.remote
        mRemoteFilesDir = mPathUtil.getCloudRemoteFilesDir(mRemotePath)
        mRemoteConfigsDir = mPathUtil.getCloudRemoteConfigsDir(mRemotePath)
        mTaskEntity.update(cloud = mCloudEntity.name, backupDir = mRemotePath)

        log { "Trying to create: $mRemoteFilesDir." }
        log { "Trying to create: $mRemoteConfigsDir." }
        mClient.mkdirRecursively(mRemoteFilesDir)
        mClient.mkdirRecursively(mRemoteConfigsDir)
    }

    private fun getRemoteFileDir(archivesRelativeDir: String) = "${mRemoteFilesDir}/${archivesRelativeDir}"

    override suspend fun onFileDirCreated(archivesRelativeDir: String): Boolean = runCatchingOnService {
        mClient.mkdirRecursively(getRemoteFileDir(archivesRelativeDir))
    }

    override suspend fun backup(m: MediaEntity, r: MediaEntity?, t: TaskDetailMediaEntity, dstDir: String, beforeCompress: suspend () -> Unit) {
        val remoteFileDir = getRemoteFileDir(m.archivesRelativeDir)

        // allowIncremental=false：云端不参与增量。云端若命中「未变化跳过」，紧随其后的上传会被跳过
        // （见下方 state != SKIP 判定），而远端此时没有该归档 → 远端缺件。
        // 因此每备一次都会走「要重打」分支 → 开「保留历史备份」时每备一次必多一个版本，
        // 上限清理（afterPostProcessing）必须生效，设置页也已加容量提示。
        val result = mMediumBackupUtil.backupMedia(
            m = m, t = t, r = r, dstDir = dstDir, allowIncremental = false, beforeCompress = beforeCompress,
        )
        if (result.isSuccess && t.mediaInfo.state != OperationState.SKIP) {
            mMediumBackupUtil.upload(client = mClient, m = m, t = t, srcDir = dstDir, dstDir = remoteFileDir)
        }
        t.update(progress = 1f)
        t.update(processingIndex = t.processingIndex + 1)
    }

    /**
     * 云端归档必须作用在**远端** `mRemoteFilesDir`，不是本地暂存 `mFilesDir`：
     * 暂存目录在每次任务结束时会被 clear() 清掉，真正的历史版本只存在云端。
     *
     * 与应用侧 `archiveMainBackup` 同口径：目标目录就在源目录的父目录下（`name@ts`），
     * 同父目录 rename 在 FTP 上已由 Phase 0 真机验证不崩。config 先上传到旧主目录再改名；
     * rename 失败时把旧 config 写回，避免主目录被扫描成幽灵历史版本。
     */
    override suspend fun archiveMainMediaBackup(existingMain: MediaEntity): String? {
        val tmpJsonPath = PathUtil.getMediaRestoreConfigDst(mRootDir)
        var src = ""
        var renamed = false
        val result = runCatching {
            src = getRemoteFileDir(existingMain.archivesRelativeDir)
            if (mClient.exists(src).not()) return@runCatching null
            var preserveId = DateUtil.getPreserveTimestamp()
            var archived = existingMain.copy(
                id = 0,
                indexInfo = existingMain.indexInfo.copy(preserveId = preserveId),
            )
            var dst = getRemoteFileDir(archived.archivesRelativeDir)
            while (mClient.exists(dst)) {
                preserveId++
                archived = archived.copy(indexInfo = archived.indexInfo.copy(preserveId = preserveId))
                dst = getRemoteFileDir(archived.archivesRelativeDir)
            }
            mRootService.writeJson(data = archived, dst = tmpJsonPath)
            mCloudRepo.upload(client = mClient, src = tmpJsonPath, dstDir = src)
            mClient.renameTo(src, dst)
            renamed = true
            // rename 后主目录消失，重建供后续 upload() / onConfigSaved() 使用
            mClient.mkdirRecursively(src)
            mMediaDao.upsert(archived)
            log { "Archived previous cloud media backup: ${archived.archivesRelativeDir}" }
            null
        }.onFailure {
            log { "Failed to archive cloud media backup: ${it.message}" }
            if (renamed.not() && src.isNotEmpty()) {
                runCatching {
                    mRootService.writeJson(data = existingMain, dst = tmpJsonPath)
                    mCloudRepo.upload(client = mClient, src = tmpJsonPath, dstDir = src)
                }
            }
        }.getOrNull()
        // 只删这一个临时 json，绝不能删整个 mRootDir —— 里面还有 mFilesDir 暂存归档等着上传
        mRootService.deleteRecursively(tmpJsonPath)
        return result
    }

    override suspend fun deleteMediaArchiveDir(old: MediaEntity) {
        val dir = getRemoteFileDir(old.archivesRelativeDir)
        // afterPostProcessing() 排在 clear()（mClient.disconnect()）之后，那时 mClient 已断连；
        // 而 FTPClientImpl.exists() 是 runCatching{...}.isSuccess，断连会静默返回 false，
        // 结果是远端旧目录没删、数据库记录却被删掉 → 下次扫描又把该目录当成新版本读回来，
        // 上限永远清不掉。这里按本次任务选中的账号重建一条短连接，与调用时机解耦。
        mCloudRepo.withClient(name = mCloudEntity.name) { client, _ ->
            if (client.exists(dir)) client.deleteRecursively(dir)
        }
        mMediaDao.delete(old.id)
    }

    override suspend fun onConfigSaved(path: String, archivesRelativeDir: String) {
        mCloudRepo.upload(client = mClient, src = path, dstDir = getRemoteFileDir(archivesRelativeDir))
    }

    override suspend fun onItselfSaved(path: String, entity: ProcessingInfoEntity) {
        entity.update(state = OperationState.UPLOADING)
        var flag = true
        var progress = 0f
        with(CoroutineScope(coroutineContext)) {
            launch {
                while (flag) {
                    entity.update(content = "${(progress * 100).toInt()}%")
                    delay(500)
                }
            }
        }
        mCloudRepo.upload(client = mClient, src = path, dstDir = mRemotePath, onUploading = { read, total -> progress = read.toFloat() / total }).apply {
            entity.update(state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = if (isSuccess) null else outString, content = "100%")
        }
        flag = false
    }

    override suspend fun onConfigsSaved(path: String, entity: ProcessingInfoEntity) {
        entity.update(state = OperationState.UPLOADING)
        var flag = true
        var progress = 0f
        with(CoroutineScope(coroutineContext)) {
            launch {
                while (flag) {
                    entity.update(content = "${(progress * 100).toInt()}%")
                    delay(500)
                }
            }
        }
        mCloudRepo.upload(client = mClient, src = path, dstDir = mRemoteConfigsDir, onUploading = { read, total -> progress = read.toFloat() / total }).apply {
            entity.update(state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = if (isSuccess) null else outString, content = "100%")
        }
        flag = false
    }

    override suspend fun clear() {
        mRootService.deleteRecursively(mRootDir)
        mClient.disconnect()
    }

    @Inject
    override lateinit var mMediaDao: MediaDao

    @Inject
    override lateinit var mMediaRepo: MediaRepository

    @Inject
    override lateinit var mMediumBackupUtil: MediumBackupUtil

    override val mRootDir by lazy { mPathUtil.getCloudTmpDir() }
    override val mFilesDir by lazy { mPathUtil.getCloudTmpFilesDir() }
    override val mConfigsDir by lazy { mPathUtil.getCloudTmpConfigsDir() }

    @Inject
    lateinit var mCloudRepo: CloudRepository

    private lateinit var mCloudEntity: CloudEntity
    private lateinit var mClient: CloudClient
    private lateinit var mRemotePath: String
    private lateinit var mRemoteFilesDir: String
    private lateinit var mRemoteConfigsDir: String
}
