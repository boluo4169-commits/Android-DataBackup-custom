package com.xayah.core.data.repository

import android.content.Context
import androidx.annotation.StringRes
import com.xayah.core.database.dao.CloudDao
import com.xayah.core.datastore.readCloudActivatedAccountName
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.network.client.CloudClient
import com.xayah.core.network.client.getCloud
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.model.ShellResult
import com.xayah.core.util.withLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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

    val clouds = cloudDao.queryFlow().distinctUntilChanged()

    suspend fun delete(entity: CloudEntity) = cloudDao.delete(entity)

    suspend fun upload(client: CloudClient, src: String, dstDir: String, onUploading: (read: Long, total: Long) -> Unit = { _, _ -> }): ShellResult = run {
        log { "Uploading..." }

        var isSuccess = true
        val out = mutableListOf<String>()
        PathUtil.setFilesDirSELinux(context)

        runCatching {
            client.upload(src = src, dst = dstDir, onUploading = onUploading)
            out.add("Upload succeed.")
        }.onFailure {
            isSuccess = false
            val stringWriter = StringWriter()
            val printWriter = PrintWriter(stringWriter)
            it.printStackTrace(printWriter)
            if (it.localizedMessage != null)
                out.add(log { stringWriter.toString() })
        }

        rootService.deleteRecursively(src).also { result ->
            isSuccess = isSuccess and result
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

            runCatching {
                client.download(src = src, dst = dstDir, onDownloading = onDownloading)
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
