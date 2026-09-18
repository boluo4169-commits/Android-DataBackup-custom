package com.xayah.core.data.repository

import android.content.Context
import androidx.annotation.StringRes
import com.xayah.core.database.dao.CloudDao
import com.xayah.core.datastore.readCloudActivatedAccountName
import com.xayah.core.datastore.readCloudSplitSize
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.network.client.CloudClient
import com.xayah.core.network.client.getCloud
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.SplitUtil
import com.xayah.core.util.model.ShellResult
import com.xayah.core.util.withLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import javax.inject.Inject

class CloudRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootService: RemoteRootService,
    private val cloudDao: CloudDao,
) {
    private fun log(msg: () -> String): String = run {
        LogUtil.log { "CloudRepository" to msg() }
        msg()
    }

    fun getString(@StringRes resId: Int) = context.getString(resId)
    suspend fun upsert(item: CloudEntity) = cloudDao.upsert(item)
    suspend fun upsert(items: List<CloudEntity>) = cloudDao.upsert(items)
    suspend fun queryByName(name: String) = cloudDao.queryByName(name)
    suspend fun query() = cloudDao.query()

    /**
     * 归档在云端是否存在 —— **分卷形态也算存在**。
     *
     * 分卷上传后云端只有 `<归档>.part.aaa/.aab/…`，整档并不存在；恢复侧若只判断
     * `client.exists(src)` 会把分卷归档当成"没有"，直接跳过（2026-09-18 真机实测：
     * 恢复时 user.tar.zst 被跳过，日志 `Failed to connect to cloud or file not exist`）。
     * 所有恢复路径的存在性判断都应走这里。
     */
    fun exists(client: CloudClient, src: String): Boolean =
        client.exists(src) || splitParts(client = client, src = src).isNotEmpty()

    /** 该归档在云端的分卷列表（按名字排序 == 按字节顺序）；无分卷返回空列表 */
    fun splitParts(client: CloudClient, src: String): List<String> = runCatching {
        val name = PathUtil.getFileName(src)
        client.walkFileTree(PathUtil.getParentPath(src))
            .map { it.pathString }
            .filter { SplitUtil.isPartOf(PathUtil.getFileName(it), name) }
            .sorted()
    }.getOrDefault(emptyList())

    val clouds = cloudDao.queryFlow().distinctUntilChanged()

    suspend fun delete(entity: CloudEntity) = cloudDao.delete(entity)

    suspend fun upload(client: CloudClient, src: String, dstDir: String, onUploading: (read: Long, total: Long) -> Unit = { _, _ -> }): ShellResult = run {
        log { "Uploading..." }

        var isSuccess = true
        val out = mutableListOf<String>()
        PathUtil.setFilesDirSELinux(context)

        // 云端分卷：只在「上传」这一层生效 —— 归档本身与本地文件都不动，
        // 所有备份（APK/USER/USER_DE/DATA/OBB/MEDIA）与迁移导出都走这里，一处改动全覆盖。
        val splitBytes = context.readCloudSplitSize().first().bytes
        val srcBytes = File(src).length()
        var parts: List<String> = emptyList()

        runCatching {
            if (splitBytes > 0 && srcBytes > splitBytes) {
                val splitted = SplitUtil.split(src = src, bytes = splitBytes)
                if (splitted.isSuccess.not()) throw IOException("Failed to split $src: ${splitted.outString}")
                parts = SplitUtil.listParts(src)
                if (parts.isEmpty()) throw IOException("Split produced no volumes for $src.")
                log { "Split $srcBytes bytes into ${parts.size} volumes ($splitBytes bytes each)." }

                var uploaded = 0L
                parts.forEach { part ->
                    client.upload(
                        src = part,
                        dst = dstDir,
                        onUploading = { read, _ -> onUploading(uploaded + read, srcBytes) }
                    )
                    uploaded += File(part).length()
                }
                out.add(log { "Upload succeed (${parts.size} volumes)." })
            } else {
                client.upload(src = src, dst = dstDir, onUploading = onUploading)
                out.add("Upload succeed.")
            }
        }.onFailure {
            isSuccess = false
            val stringWriter = StringWriter()
            val printWriter = PrintWriter(stringWriter)
            it.printStackTrace(printWriter)
            if (it.localizedMessage != null)
                out.add(log { stringWriter.toString() })
        }

        // 临时分卷清理同样与上传结果解耦：卷都已上传后清理失败只是残留，不该把业务判为失败
        parts.forEach { part ->
            rootService.deleteRecursively(part).also { result ->
                if (result.not()) out.add(log { "Failed to delete $part." })
            }
        }

        // 临时包清理与上传结果解耦：包已完整上传后，本地清理失败只是残留，
        // 不该把业务判为失败（迁移导出曾因此出现「云端已有完整包、界面却报导出失败」）。
        rootService.deleteRecursively(src).also { result ->
            if (result.not()) out.add(log { "Failed to delete $src." })
        }

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }

    suspend fun download(
        client: CloudClient,
        src: String,
        dstDir: String,
        deleteAfterDownloaded: Boolean = true,
        onDownloading: (written: Long, total: Long) -> Unit = { _, _ -> },
        onDownloaded: suspend (path: String) -> Unit,
    ): ShellResult =
        run {
            log { "Downloading..." }

            var code = 0
            val out = mutableListOf<String>()
            rootService.deleteRecursively(dstDir)
            rootService.mkdirs(dstDir)
            PathUtil.setFilesDirSELinux(context)

            val srcName = PathUtil.getFileName(src)
            runCatching {
                if (client.exists(src)) {
                    client.download(src = src, dst = dstDir, onDownloading = onDownloading)
                } else {
                    // 分卷归档：整档不存在 → 按序下载各卷再合并成完整归档。
                    // 后续 .md5 校验、解压链路完全不变（md5 一直是整档的校验值）。
                    val parts = splitParts(client = client, src = src)
                    if (parts.isEmpty())
                        throw IOException("Neither $srcName nor its volumes were found in ${PathUtil.getParentPath(src)}.")

                    log { "Found split archive $srcName in ${parts.size} volumes." }
                    val total = parts.sumOf { client.size(it) }
                    var downloaded = 0L
                    val localParts = mutableListOf<String>()
                    parts.forEach { part ->
                        client.download(
                            src = part,
                            dst = dstDir,
                            onDownloading = { read, _ -> onDownloading(downloaded + read, total) }
                        )
                        downloaded += client.size(part)
                        localParts.add("$dstDir/${PathUtil.getFileName(part)}")
                    }

                    if (SplitUtil.merge(parts = localParts, dst = "$dstDir/$srcName").not())
                        throw IOException("Failed to merge ${parts.size} volumes into $srcName.")
                    localParts.forEach { rootService.deleteRecursively(it) }
                    log { "Merged ${parts.size} volumes into $srcName." }
                }
            }.onFailure {
                code = -2
                if (it.localizedMessage != null)
                    out.add(log { it.localizedMessage!! })
            }

            if (code == 0) {
                // 云端归档的 md5 sidecar 一并下载（存在才下）：恢复侧 ChecksumUtil.verify 依赖它，
                // 没有它校验会静默跳过，损坏的归档将无告警地被恢复
                runCatching {
                    if (client.exists("$src.md5")) client.download(src = "$src.md5", dst = dstDir, onDownloading = { _, _ -> })
                }.withLog()
                onDownloaded("$dstDir/${PathUtil.getFileName(src)}")
            } else {
                out.add(log { "Failed to download $src." })
            }
            if (deleteAfterDownloaded)
                rootService.deleteRecursively(dstDir).also { result ->
                    code = if (result) code else -1
                    if (result.not()) out.add(log { "Failed to delete $dstDir." })
                }

            ShellResult(code = code, input = listOf(), out = out)
        }

    suspend fun getClient(name: String? = null): Pair<CloudClient, CloudEntity> {
        val entity = queryByName(name ?: context.readCloudActivatedAccountName().first())
        if (entity != null) if (entity.remote.isEmpty()) throw IllegalAccessException("${entity.name}: Remote directory is not set.")
        val client = entity?.getCloud()?.apply { connect() } ?: throw NullPointerException("Client is null.")
        return client to entity
    }

    suspend fun withClient(name: String? = null, block: suspend (client: CloudClient, entity: CloudEntity) -> Unit) = run {
        val (client, entity) = getClient(name)
        try {
            block(client, entity)
        } finally {
            // block 抛异常也必须断开，否则 FTP/SFTP/SMB 的 socket/session 泄漏
            runCatching { client.disconnect() }
        }
    }

    suspend fun withActivatedClients(block: suspend (clients: List<Pair<CloudClient, CloudEntity>>) -> Unit) = run {
        val clients: MutableList<Pair<CloudClient, CloudEntity>> = mutableListOf()
        try {
            cloudDao.queryActivated().forEach {
                if (it.remote.isEmpty()) throw IllegalAccessException("${it.name}: Remote directory is not set.")
                clients.add(it.getCloud().apply { connect() } to it)
            }
            block(clients)
        } finally {
            clients.forEach { runCatching { it.first.disconnect() } }
        }
    }
}
