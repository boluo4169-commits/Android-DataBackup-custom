package com.xayah.core.service.util

import android.content.Context
import android.media.MediaScannerConnection
import com.xayah.core.common.util.toLineString
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.datastore.readCleanRestoring
import com.xayah.core.datastore.readMediaRestoreConflictStrategy
import com.xayah.core.model.CompressionType
import com.xayah.core.model.ConflictPreview
import com.xayah.core.model.DataType
import com.xayah.core.model.MAX_PREVIEW_ENTRIES
import com.xayah.core.model.OperationState
import com.xayah.core.model.RestoreConflictStrategy
import com.xayah.core.model.computeConflictPreview
import com.xayah.core.model.computeSnapshotPaths
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.TaskDetailMediaEntity
import com.xayah.core.model.resolveRestoreConflictArgs
import com.xayah.core.model.shouldCreateLocalSnapshot
import com.xayah.core.model.util.formatSize
import com.xayah.core.network.client.CloudClient
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.DateUtil
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.TrashedFilePatterns
import com.xayah.core.util.command.Tar
import com.xayah.core.util.command.TrashedUtil
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.util.model.ShellResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

class MediumRestoreUtil @Inject constructor(
    @ApplicationContext val context: Context,
    private val rootService: RemoteRootService,
    private val taskDao: TaskDao,
    private val mediaRepository: MediaRepository,
    private val cloudRepository: CloudRepository,
    private val pathUtil: PathUtil,
) {
    companion object {
        private const val TAG = "MediumRestoreUtil"
    }

    private fun log(onMsg: () -> String): String = run {
        val msg = onMsg()
        LogUtil.log { TAG to msg }
        msg
    }

    private fun TaskDetailMediaEntity.getLog() = mediaInfo.log

    private suspend fun TaskDetailMediaEntity.updateInfo(
        state: OperationState? = null,
        bytes: Long? = null,
        log: String? = null,
        content: String? = null,
    ) = run {
        mediaInfo.also {
            if (state != null) it.state = state
            if (bytes != null) it.bytes = bytes
            if (log != null) it.log = log
            if (content != null) it.content = content
        }
        taskDao.upsert(this)
    }

    suspend fun restoreMedia(m: MediaEntity, t: TaskDetailMediaEntity, srcDir: String): ShellResult = run {
        log { "Restoring ${DataType.MEDIA_MEDIA.type}..." }

        val ct = m.indexInfo.compressionType
        val src = mediaRepository.getArchiveDst(dstDir = srcDir, ct = ct)
        val dst = m.path
        val dstDir = PathUtil.getParentPath(dst)
        var isSuccess = true
        val out = mutableListOf<String>()

        // Return if the archive doesn't exist.
        if (rootService.exists(src)) {
            val sizeBytes = rootService.calculateSize(src)
            t.updateInfo(state = OperationState.PROCESSING, bytes = sizeBytes)

            // Verify the archive integrity before restoring.
            val checksumMismatch = ChecksumUtil.verify(rootService = rootService, src = src)
            if (checksumMismatch != null) {
                out.add(log { "Checksum mismatch: ${checksumMismatch.archivePath}" })
                if (ChecksumConfirmation.awaitDecision(checksumMismatch)) {
                    out.add(log { "User chose to force restore." })
                } else {
                    isSuccess = false
                    out.add(log { "Archive corrupted: $src" })
                    t.updateInfo(state = OperationState.ERROR, log = out.toLineString())
                    return@run ShellResult(code = -1, input = listOf(), out = out)
                }
            }

            // 冲突策略：干净恢复优先（开启时策略选择器置灰，参数退化为 --recursive-unlink）。
            // 参数一律由纯函数从枚举生成，绝不拼接外部自由文本进 root shell 命令。
            val cleanRestoring = context.readCleanRestoring().first()
            val strategy = RestoreConflictStrategy.fromId(context.readMediaRestoreConflictStrategy().first())
            var conflictArgs = resolveRestoreConflictArgs(cleanRestoring = cleanRestoring, strategy = strategy)
            log { "Conflict strategy: $strategy (cleanRestoring=$cleanRestoring), tar args: $conflictArgs" }

            // 恢复前预览：列归档条目（只读）与本机目录比对，算出会被覆盖 / 跳过 / 留档的数量。
            // 列不出来时降级为「照常恢复」，绝不因为预览失败阻断恢复。
            val archiveEntries = runCatching {
                Tar.list(src, ct.decompressPara).takeIf { it.isSuccess }?.out.orEmpty()
            }.onFailure { log { "Failed to list archive for preview: ${it.message}" } }.getOrDefault(emptyList())
            val previewData = buildConflictPreview(
                archiveEntries = archiveEntries,
                dst = dst,
                dstDir = dstDir,
                strategy = strategy,
                cleanRestoring = cleanRestoring,
            )
            val preview = previewData.preview
            if (preview != null) {
                RestorePreviewConfirmation.accumulate(preview)
                out.add(log { "Conflict preview: $preview" })
            }

            // 用户在上一个目录点了取消 → 本次任务后续目录一律跳过（不弹第二次窗）。
            // 只有「真的有冲突 / 会删文件 / 有排除项」时才需要弹清单确认。
            val request = preview?.takeIf { it.needsConfirmation }?.let {
                RestorePreviewRequest(name = m.name, preview = it, strategy = strategy, cleanRestoring = cleanRestoring)
            }
            val confirmed = if (RestorePreviewConfirmation.isCancelled()) {
                false
            } else if (request != null) {
                RestorePreviewConfirmation.awaitConfirm(request)
            } else {
                true
            }
            if (confirmed.not()) {
                RestorePreviewConfirmation.markSkipped()
                out.add(log { "Restore skipped by user at preview: ${m.name}" })
                t.updateInfo(state = OperationState.SKIP, log = out.toLineString())
                return@run ShellResult(code = 0, input = listOf(), out = out)
            }

            // 方案②「本机快照版本」：两份都留时，先把会被覆盖的本机旧文件打包成一份归档并
            // 登记成护盾版本，再按普通覆盖解压。快照失败一律降级回 --backup=numbered，
            // 绝不因为新功能失败而丢掉旧文件。取消预览时上面已经 return，不会误生成快照。
            val snapshotPaths = computeSnapshotPaths(archiveEntries, previewData.existingRelativePaths)
            // 超过预览上限时只能看到前 MAX_PREVIEW_ENTRIES 条，部分打包会让后面的旧文件
            // 被覆盖却没进快照（用户以为「两份都留」实际丢了一半）。这种目录整体降级。
            val entriesTruncated = archiveEntries.size > MAX_PREVIEW_ENTRIES
            if (entriesTruncated && shouldCreateLocalSnapshot(strategy, cleanRestoring, snapshotPaths)) {
                out.add(log { "Archive exceeds $MAX_PREVIEW_ENTRIES entries; skip local snapshot to avoid partial capture, fallback to --backup=numbered" })
            }
            if (shouldCreateLocalSnapshot(strategy, cleanRestoring, snapshotPaths) && entriesTruncated.not()) {
                if (createLocalSnapshot(m = m, dstDir = dstDir, snapshotPaths = snapshotPaths, out = out)) {
                    conflictArgs = ""
                    out.add(log { "KEEP_BOTH handled by local snapshot; tar args: (default overwrite)" })
                } else {
                    out.add(log { "KEEP_BOTH fallback; tar args: $conflictArgs" })
                }
            }

            // Decompress the archive.
            log { "ExclusionList: $TrashedFilePatterns." }
            // 被跳过的文件不会落到目标目录，只能从归档清单里找；复用上面那次 tar -tf，不再列一遍。
            TrashedUtil.logExcluded(scene = " from archive", paths = archiveEntries)
            Tar.decompress(
                // 旧归档里可能混着回收站/写入中的文件（备份时它们还在那个状态）。恢复时
                // 一并跳过：放出来照样被 MediaScanner 标成已删除，相册看不到、到期被清掉，
                // 反而让用户以为照片丢了。判据与备份侧同源（TrashedFilePatterns）。
                exclusionList = TrashedFilePatterns,
                clear = conflictArgs,
                m = false,
                src = src,
                dst = dstDir,
                extra = ct.decompressPara
            ).also { result ->
                isSuccess = result.isSuccess
                out.addAll(result.out)
            }
            // 恢复的是 Pictures/DCIM/Music 等媒体目录，文件落盘后 MediaStore 数据库并没有
            // 索引，相册/图库读不到。恢复成功后触发一次媒体扫描，让系统重新入库。
            if (isSuccess) {
                scanMediaStore(m.path)
            }
        } else {
            isSuccess = false
            out.add(log { "Not exist: $src" })
            t.updateInfo(state = OperationState.ERROR, log = out.toLineString())
            return@run ShellResult(code = -1, input = listOf(), out = out)
        }
        t.updateInfo(state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = out.toLineString())

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }

    /** 预览结果 + 复用的本机文件清单（方案② 生成快照时不再重复遍历目录）。 */
    private data class PreviewData(
        val preview: ConflictPreview?,
        val existingRelativePaths: Set<String>,
    )

    /**
     * 用归档条目与本机已有文件算出预览统计。任一步失败都返回空结果，让调用方照常恢复。
     *
     * 归档条目相对解压 `-C` 的 [dstDir]（如 `DCIM/a.jpg`），本机文件是绝对路径，
     * 必须去掉 `dstDir/` 前缀后才是同一口径，否则「同名」永远判定不出来。
     */
    private suspend fun buildConflictPreview(
        archiveEntries: List<String>,
        dst: String,
        dstDir: String,
        strategy: RestoreConflictStrategy,
        cleanRestoring: Boolean,
    ): PreviewData = runCatching {
        if (archiveEntries.isEmpty()) return@runCatching PreviewData(null, emptySet())
        val existing = rootService.walkFileTree(dst)
            .take(MAX_PREVIEW_ENTRIES)
            .map { it.pathString.removePrefix("$dstDir/") }
            .toSet()
        PreviewData(
            preview = computeConflictPreview(
                archiveEntries = archiveEntries.take(MAX_PREVIEW_ENTRIES),
                existingRelativePaths = existing,
                strategy = strategy,
                cleanRestoring = cleanRestoring,
            ),
            existingRelativePaths = existing,
        )
    }.onFailure {
        log { "Failed to build conflict preview: ${it.message}" }
    }.getOrDefault(PreviewData(null, emptySet()))

    /**
     * 触发 MediaStore 重新扫描指定目录，让相册/图库/音乐等应用立即索引到恢复的文件。
     * 传目录路径，MediaScanner 会递归扫描子目录；异步执行，不阻塞恢复流程。
     */
    private fun scanMediaStore(path: String) {
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        }.onFailure {
            log { "Failed to trigger media scan for $path: ${it.message}" }
        }
    }

    /**
     * 方案②「本机快照版本」：把会被覆盖的本机旧文件打包成一份归档，并登记成护盾版本。
     *
     * 归档与 config 写在**本地**备份源的 `files/<name>@<时间戳>/` 下，不上传云端：
     * 这些是本机隐私数据，云端源恢复时再传一份既慢又占容量。因此从云端源恢复后，
     * 护盾版本要在恢复页切到「本地」源才看得到（预览弹窗与日志已提示）。
     *
     * 只打冲突文件、不整目录；全程记日志（含文件数与耗时）当作进度提示。
     * 返回 false 时调用方降级回 `--backup=numbered`，旧文件绝不会丢。
     */
    private suspend fun createLocalSnapshot(
        m: MediaEntity,
        dstDir: String,
        snapshotPaths: List<String>,
        out: MutableList<String>,
    ): Boolean = runCatching {
        val started = System.currentTimeMillis()
        val localFilesDir = pathUtil.getLocalBackupFilesDir()
        var preserveId = DateUtil.getPreserveTimestamp()
        var snapshot = m.copy(
            id = 0,
            indexInfo = m.indexInfo.copy(
                preserveId = preserveId,
                compressionType = CompressionType.TAR,
                cloud = "",
                backupDir = context.localBackupSaveDir(),
            ),
            extraInfo = m.extraInfo.copy(
                activated = false,
                existed = true,
                lastBackupTime = System.currentTimeMillis(),
            ),
        )
        var snapshotDir = "$localFilesDir/${snapshot.archivesRelativeDir}"
        // 同一秒内连续恢复同一目录会撞名，照抄保护逻辑递增
        while (rootService.exists(snapshotDir)) {
            preserveId++
            snapshot = snapshot.copy(indexInfo = snapshot.indexInfo.copy(preserveId = preserveId))
            snapshotDir = "$localFilesDir/${snapshot.archivesRelativeDir}"
        }
        if (rootService.mkdirs(snapshotDir).not()) {
            out.add(log { "Failed to create snapshot dir: $snapshotDir" })
            return@runCatching false
        }

        val listFile = "${pathUtil.getCloudTmpDir()}/local_snapshot_$preserveId.list"
        val archive = mediaRepository.getArchiveDst(dstDir = snapshotDir, ct = CompressionType.TAR)
        var success = false
        try {
            if (rootService.writeText(snapshotPaths.joinToString("\n"), listFile).not()) {
                out.add(log { "Failed to write snapshot list: $listFile" })
                return@runCatching false
            }
            out.add(log { "Creating local snapshot: ${snapshotPaths.size} file(s) into ${snapshot.archivesRelativeDir}; large directories may take a while." })
            val compress = Tar.compressFromList(srcDir = dstDir, listFile = listFile, dst = archive, extra = "")
            if (compress.isSuccess.not() || rootService.exists(archive).not() || Tar.list(archive).isSuccess.not()) {
                out.add(log { "Local snapshot archive invalid: $archive" })
                return@runCatching false
            }
            rootService.writeJson(data = snapshot, dst = PathUtil.getMediaRestoreConfigDst(snapshotDir))
            mediaRepository.upsert(snapshot)
            val seconds = (System.currentTimeMillis() - started) / 1000.0
            out.add(log { "Local snapshot created: ${snapshot.archivesRelativeDir} (${snapshotPaths.size} file(s), ${seconds}s). Switch restore source to Local to see the shield version." })
            success = true
            true
        } finally {
            rootService.deleteRecursively(listFile)
            if (success.not()) rootService.deleteRecursively(snapshotDir)
        }
    }.onFailure {
        out.add(log { "Failed to create local snapshot: ${it.message}; falling back to --backup=numbered" })
    }.getOrDefault(false)

    suspend fun download(
        client: CloudClient,
        m: MediaEntity,
        t: TaskDetailMediaEntity,
        dataType: DataType,
        srcDir: String,
        dstDir: String,
        onDownloaded: suspend (m: MediaEntity, t: TaskDetailMediaEntity, path: String) -> Unit
    ) = run {
        val ct = m.indexInfo.compressionType
        val src = mediaRepository.getArchiveDst(dstDir = srcDir, ct = ct)

        t.updateInfo(state = OperationState.DOWNLOADING)
        if (cloudRepository.exists(client = client, src = src)) {
            var flag = true
            var progress = 0.0
            with(CoroutineScope(coroutineContext)) {
                launch {
                    while (flag) {
                        t.updateInfo(content = progress.formatSize())
                        delay(500)
                    }
                }
            }

            cloudRepository.download(client = client,
                src = src,
                dstDir = dstDir,
                onDownloading = { written, _ -> progress = written.toDouble() },
                onDownloaded = {
                    onDownloaded(m, t, dstDir)
                }
            ).apply {
                flag = false
                t.updateInfo(
                    log = (t.getLog() + "\n${outString}").trim(),
                    content = progress.formatSize()
                )
                if (isSuccess.not()) {
                    t.updateInfo(state = OperationState.ERROR)
                }
            }
        } else {
            if (dataType == DataType.PACKAGE_USER || dataType == DataType.PACKAGE_APK) {
                t.updateInfo(state = OperationState.ERROR, log = log { "Failed to connect to cloud or file not exist: $src" })
            } else {
                t.updateInfo(state = OperationState.SKIP, log = log { "Failed to connect to cloud or file not exist, skip: $src" })
            }
        }
    }
}
