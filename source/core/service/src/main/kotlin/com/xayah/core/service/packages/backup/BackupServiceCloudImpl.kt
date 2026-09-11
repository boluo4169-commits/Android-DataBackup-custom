package com.xayah.core.service.packages.backup

import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.datastore.readCloudPkgOnlyDir
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.preserveArchiveRelativeDir
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.model.util.get
import com.xayah.core.network.client.CloudClient
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.PackagesBackupUtil
import com.xayah.core.util.DateUtil
import com.xayah.core.util.PathUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
            taskType = TaskType.PACKAGE,
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
        mRemoteAppsDir = mPathUtil.getCloudRemoteAppsDir(mRemotePath)
        mRemoteConfigsDir = mPathUtil.getCloudRemoteConfigsDir(mRemotePath)
        mTaskEntity.update(cloud = mCloudEntity.name, backupDir = mRemotePath)
        mPkgOnlyDir = mContext.readCloudPkgOnlyDir().first()
        if (mPkgOnlyDir) log { "Cloud pkg-only dir name is enabled, uploading with legacy (package name only) directory names." }

        log { "Trying to create: $mRemoteAppsDir." }
        log { "Trying to create: $mRemoteConfigsDir." }
        mClient.mkdirRecursively(mRemoteAppsDir)
        mClient.mkdirRecursively(mRemoteConfigsDir)
    }

    private fun getRemoteAppDir(archivesRelativeDir: String) = "${mRemoteAppsDir}/${archivesRelativeDir}"

    // 「云端目录名不含中文」开关：开启后云端目录走 legacyArchivesRelativeDir（纯包名），
    // 避免不支持 UTF-8 文件名的 FTP/网盘服务器出现乱码目录甚至上传失败。本地目录名不受影响。
    override suspend fun resolveArchiveRelativeDir(p: PackageEntity): String =
        if (mPkgOnlyDir) p.legacyArchivesRelativeDir else p.archivesRelativeDir

    override suspend fun onAppDirCreated(archivesRelativeDir: String): Boolean = runCatchingOnService {
        mClient.mkdirRecursively(getRemoteAppDir(archivesRelativeDir))
    }

    override suspend fun backup(type: DataType, p: PackageEntity, r: PackageEntity?, t: TaskDetailPackageEntity, dstDir: String) {
        val remoteAppDir = getRemoteAppDir(resolveArchiveRelativeDir(p))
        val result = if (type == DataType.PACKAGE_APK) {
            mPackagesBackupUtil.backupApk(p = p, t = t, r = r, dstDir = dstDir)
        } else {
            mPackagesBackupUtil.backupData(p = p, t = t, r = r, dataType = type, dstDir = dstDir)
        }
        if (result.isSuccess && t.get(type).state != OperationState.SKIP) {
            mPackagesBackupUtil.upload(client = mClient, p = p, t = t, dataType = type, srcDir = dstDir, dstDir = remoteAppDir)
        }
        t.update(dataType = type, progress = 1f)
        t.update(processingIndex = t.processingIndex + 1)
    }

    override suspend fun onConfigSaved(path: String, archivesRelativeDir: String) {
        mCloudRepo.upload(client = mClient, src = path, dstDir = getRemoteAppDir(archivesRelativeDir))
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

    override suspend fun onIconsSaved(path: String, entity: ProcessingInfoEntity) {
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

    // 修复：保留历史备份在云场景失效。归档旧主备份需在远程执行 rename，否则旧备份被新备份上传覆盖。
    override suspend fun archiveMainBackup(existingMain: PackageEntity) {
        // 定位现存主备份的真实路径：先按新（应用名_包名）找，再回退旧（纯包名），
        // 这样无论「云端目录仅用包名」开关处于哪种状态都能命中现有目录。
        val srcNew = "${mRemoteAppsDir}/${existingMain.archivesRelativeDir}"
        val srcLegacy = "${mRemoteAppsDir}/${existingMain.legacyArchivesRelativeDir}"
        val isLegacy = !mClient.exists(srcNew) && mClient.exists(srcLegacy)
        val src = if (isLegacy) srcLegacy else srcNew
        if (!mClient.exists(src)) return

        // 归档必须落在**源目录自身的父目录**下（即 src 后追加 @preserveId）。
        // 若按 archivesRelativeDir 算目标，开关在两次备份间翻转时会变成跨父目录 rename，
        // FTP 等不支持自动建父目录的服务会抛 IOException 并崩溃（用户实测复现）。
        val srcRel = if (isLegacy) existingMain.legacyArchivesRelativeDir else existingMain.archivesRelativeDir
        var preserveId = DateUtil.getPreserveTimestamp()
        var archived = existingMain.copy(indexInfo = existingMain.indexInfo.copy(preserveId = preserveId))
        var dst = "${mRemoteAppsDir}/${preserveArchiveRelativeDir(srcRel, preserveId)}"
        while (mClient.exists(dst)) {
            preserveId++
            archived = existingMain.copy(indexInfo = existingMain.indexInfo.copy(preserveId = preserveId))
            dst = "${mRemoteAppsDir}/${preserveArchiveRelativeDir(srcRel, preserveId)}"
        }

        // 写新 config（preserveId 已更新）到本地临时目录，上传到远程 src，再远程改名
        val tmpDir = mPathUtil.getCloudTmpDir()
        val tmpJsonPath = PathUtil.getPackageRestoreConfigDst(tmpDir)
        mRootService.writeJson(data = archived, dst = tmpJsonPath)
        mCloudRepo.upload(client = mClient, src = tmpJsonPath, dstDir = src)
        mRootService.deleteRecursively(tmpDir)
        mClient.renameTo(src, dst)
        mPackageDao.upsert(archived)
    }

    // 修复：清理超量旧归档需在远程执行删除，否则远程旧版本不会被真正删除
    override suspend fun deleteArchiveDir(old: PackageEntity) {
        val dirNew = "${mRemoteAppsDir}/${old.archivesRelativeDir}"
        val dir = if (mClient.exists(dirNew)) dirNew else "${mRemoteAppsDir}/${old.legacyArchivesRelativeDir}"
        if (mClient.exists(dir)) {
            mClient.deleteRecursively(dir)
        }
        mPackageDao.delete(old.id)
    }

    @Inject
    override lateinit var mPackageDao: PackageDao

    @Inject
    override lateinit var mPackageRepo: PackageRepository

    @Inject
    override lateinit var mPackagesBackupUtil: PackagesBackupUtil

    override val mRootDir by lazy { mPathUtil.getCloudTmpDir() }
    override val mAppsDir by lazy { mPathUtil.getCloudTmpAppsDir() }
    override val mConfigsDir by lazy { mPathUtil.getCloudTmpConfigsDir() }

    @Inject
    lateinit var mCloudRepo: CloudRepository

    private lateinit var mCloudEntity: CloudEntity
    private lateinit var mClient: CloudClient
    private lateinit var mRemotePath: String
    private lateinit var mRemoteAppsDir: String
    private lateinit var mRemoteConfigsDir: String
    private var mPkgOnlyDir = false
}
