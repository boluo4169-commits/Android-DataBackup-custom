package com.xayah.core.service.util

import android.content.Context
import com.xayah.core.common.util.toLineString
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.datastore.readCompressionLevel
import com.xayah.core.datastore.readCompressionThreads
import com.xayah.core.datastore.readFollowSymlinks
import com.xayah.core.model.DataType
import com.xayah.core.model.OperationState
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.TaskDetailMediaEntity
import com.xayah.core.model.util.getCompressPara
import com.xayah.core.network.client.CloudClient
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.Tar
import com.xayah.core.util.model.ShellResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import com.xayah.core.datastore.readCloudSplitSize
import com.xayah.core.model.util.cloudSplitSuggestion
import com.xayah.core.model.util.formatSize
import com.xayah.core.model.util.shouldSuggestCloudSplit
import com.xayah.core.model.util.CLOUD_SPLIT_HINT_MARK

class MediumBackupUtil @Inject constructor(
    @ApplicationContext val context: Context,
    private val rootService: RemoteRootService,
    private val taskDao: TaskDao,
    private val mediaRepository: MediaRepository,
    private val commonBackupUtil: CommonBackupUtil,
    private val cloudRepository: CloudRepository,
) {
    companion object {
        private const val TAG = "MediumBackupUtil"
    }

    private fun log(onMsg: () -> String): String = run {
        val msg = onMsg()
        LogUtil.log { TAG to msg }
        msg
    }

    private fun MediaEntity.getDataBytes() = mediaInfo.dataBytes

    private fun MediaEntity.setDataBytes(sizeBytes: Long) = run { mediaInfo.dataBytes = sizeBytes }
    private fun MediaEntity.setDisplayBytes(sizeBytes: Long) = run { mediaInfo.displayBytes = sizeBytes }

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

    suspend fun backupMedia(m: MediaEntity, t: TaskDetailMediaEntity, r: MediaEntity?, dstDir: String): ShellResult = run {
        log { "Backing up ${DataType.MEDIA_MEDIA.type}..." }

        val name = m.name
        val ct = m.indexInfo.compressionType
        val dst = mediaRepository.getArchiveDst(dstDir = dstDir, ct = ct)
        var isSuccess = true
        val out = mutableListOf<String>()
        val src = m.path
        val srcDir = PathUtil.getParentPath(src)

        // Check the existence of origin path.
        rootService.exists(src).also {
            if (it.not()) {
                isSuccess = false
                out.add(log { "Not exist: $src" })
                t.updateInfo(state = OperationState.ERROR, log = out.toLineString())
                return@run ShellResult(code = -1, input = listOf(), out = out)
            }
        }

        val sizeBytes = rootService.calculateSize(src)
        t.updateInfo(state = OperationState.PROCESSING, bytes = sizeBytes)
        if (rootService.exists(dst) && sizeBytes == r?.getDataBytes()) {
            t.updateInfo(state = OperationState.SKIP)
            out.add(log { "Data has not changed." })
        } else {
            // Compress and test.
            Tar.compress(
                // 跳过系统回收站里的文件：Android/ColorOS 删除照片是「原地改名」——
                // 变成 .trashed-<到期时间戳>-原名，文件仍在目录里躺着。以前会把它们一起
                // 打进归档，恢复出来后 MediaScanner 按名字判定为「已删除」：相册看不见，
                // 30 天后连文件一起被清掉（实测一加 15，external.db 里半数记录是 trashed）。
                exclusionList = listOf(".trashed-*"),
                h = if (context.readFollowSymlinks().first()) "-h" else "",
                srcDir = srcDir,
                src = PathUtil.getFileName(src),// the name is not always the actual file name of the source,but the src does contain
                dst = dst,
                extra = ct.getCompressPara(context.readCompressionLevel().first(), context.readCompressionThreads().first())
            ).also { result ->
                // tar 的非 0 退出码有两类，归档本身都可能已完整写出，故不在此处判失败，
                // 统一交给紧随其后的 testArchive 裁定（能被 tar -tf 完整列出即算成功）：
                //  1 = 读取期间源有变动（措辞有多种：file changed / file removed before we read it /
                //      file shrank 等），不逐条枚举警告文案（枚举必漏，实测抖音 btm_scope_config 翻车）；
                //  2 = 个别条目无法读取（实测 DCIM/.tmfs 这类隐藏目录连 root 都 Permission denied）。
                // 归档真损坏（如磁盘写满）时 testArchive 会失败、最终仍判失败，所以不存在放行坏归档的风险。
                if (result.isSuccess.not()) {
                    log { "tar exited with code ${result.code}; archive kept, verdict by testArchive." }
                }
                out.addAll(result.out)
            }
            commonBackupUtil.testArchive(src = dst, ct = ct).also { result ->
                isSuccess = isSuccess && result.isSuccess
                out.addAll(result.out)
                if (result.isSuccess) {
                    m.setDataBytes(sizeBytes)
                    m.setDisplayBytes(rootService.calculateSize(dst))
                    ChecksumUtil.write(rootService = rootService, src = dst)?.let { md5 ->
                        out.add(log { "Checksum: $md5" })
                    }
                }
            }
        }

        t.updateInfo(state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = out.toLineString())

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }

    suspend fun upload(client: CloudClient, m: MediaEntity, t: TaskDetailMediaEntity, srcDir: String, dstDir: String) = run {
        val ct = m.indexInfo.compressionType
        val src = mediaRepository.getArchiveDst(dstDir = srcDir, ct = ct)
        t.updateInfo(state = OperationState.UPLOADING)

        // 归档较大且未开分卷：提示用户开分卷（阈值见 CLOUD_SPLIT_SUGGEST_BYTES）。
        // 同一次任务只提示一次 —— 日志里已含标记就不再追加，避免每个大文件刷一遍。
        runCatching {
            val archiveBytes = java.io.File(src).length()
            if (shouldSuggestCloudSplit(cloudSplitSize = context.readCloudSplitSize().first(), archiveBytes = archiveBytes)) {
                val hint = cloudSplitSuggestion(archiveBytes.toDouble().formatSize())
                if (t.getLog().contains(CLOUD_SPLIT_HINT_MARK).not()) {
                    t.updateInfo(log = (t.getLog() + "\n" + hint).trim())
                }
            }
        }

        var flag = true
        var progress = 0f
        with(CoroutineScope(coroutineContext)) {
            launch {
                while (flag) {
                    t.updateInfo(content = "${(progress * 100).toInt()}%")
                    delay(500)
                }
            }
        }

        val uploadResult = cloudRepository.upload(client = client, src = src, dstDir = dstDir, onUploading = { read, total -> progress = read.toFloat() / total }).apply {
            flag = false
            t.updateInfo(state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = t.getLog() + "\n${outString}", content = "100%")
        }

        // md5 sidecar 跟随归档上传：云端恢复的完整性校验依赖它。仅在归档上传成功后补传，失败只记日志
        if (uploadResult.isSuccess) runCatching {
            if (java.io.File("$src.md5").exists()) cloudRepository.upload(client = client, src = "$src.md5", dstDir = dstDir)
        }.onFailure {
            log { "Failed to upload md5 sidecar for $src: ${it.message}." }
        }
    }
}
