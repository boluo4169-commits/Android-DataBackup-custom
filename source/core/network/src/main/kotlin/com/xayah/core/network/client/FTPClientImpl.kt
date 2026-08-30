package com.xayah.core.network.client

import android.content.Context
import com.xayah.core.common.util.toPathString
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.FTPExtra
import com.xayah.core.network.R
import com.xayah.core.network.io.CountingInputStreamImpl
import com.xayah.core.network.io.CountingOutputStreamImpl
import com.xayah.core.network.util.getExtraEntity
import com.xayah.core.rootservice.parcelables.PathParcelable
import com.xayah.core.util.GsonUtil
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.toPathList
import com.xayah.core.util.withMainContext
import com.xayah.libpickyou.PickYouLauncher
import com.xayah.libpickyou.parcelables.DirChildrenParcelable
import com.xayah.libpickyou.parcelables.FileParcelable
import com.xayah.libpickyou.ui.model.PickerType
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.security.auth.login.LoginException

class FTPClientImpl(private val entity: CloudEntity, private val extra: FTPExtra) : CloudClient {
    private var client: FTPClient? = null

    private fun log(msg: () -> String): String = run {
        LogUtil.log { "FTPClientImpl" to msg() }
        msg()
    }

    private fun withClient(block: (client: FTPClient) -> Unit) = run {
        if (client == null) throw NullPointerException("Client is null.")
        block(client!!)
    }

    override fun connect() {
        client = FTPClient().apply {
            // 超时设置的三条约束（都踩过坑，改前先看注释）：
            // 1. 顺序：commons-net 的 setSoTimeout() 直接操作内部 _socket_，建连前调用会因 socket
            //    未创建而抛 NPE（"setSoTimeout(int) on a null object reference"，v3.7.2 回归）。
            //    defaultTimeout / connectTimeout 只设字段不碰 socket，可以安全地放在 connect() 之前。
            // 2. 时长：defaultTimeout 会在建连后被应用到控制连接 socket，等于控制连接的读超时。
            //    取 120s：既保证大归档（上百 MiB）上传后服务器落盘回 226 有足够时间，
            //    又不会在真正异常时让用户无限等（原先 60s 偏紧，实测大文件落盘会超）。
            // 3. 建连超时保持 15s，避免服务器不可达时长时间卡住。
            // 4. 不开 controlKeepAliveTimeout：它会接管数据连接的关闭时机（CSL），
            //    与 storeFile 的高级语义叠加后行为更难预测；本场景由超时兜底更可控。
            defaultTimeout = 120_000
            connectTimeout = 15_000
            autodetectUTF8 = true
            connect(entity.host, extra.port)
            soTimeout = 120_000
            if (login(entity.user, entity.pass).not()) throw LoginException("Failed to login, user: ${entity.user}, pass: ${entity.pass}.")
            enterLocalPassiveMode()
            val fileType = FTP.BINARY_FILE_TYPE
            if (setFileType(fileType).not()) throw LoginException("Failed to set file type: $fileType.")
        }
    }

    override fun disconnect() {
        withClient { client ->
            if (client.isConnected) {
                if (client.logout().not()) throw LoginException("Failed to logout.")
                client.disconnect()
            }
        }
        client = null
    }

    override fun mkdir(dst: String) = withClient { client ->
        log { "mkdir: $dst" }
        if (client.makeDirectory(dst).not()) throw IOException("Failed to mkdir: $dst.")
    }

    override fun mkdirRecursively(dst: String) {
        val dirs = dst.split("/")
        withClient { client ->
            for (i in dirs) {
                if (client.changeWorkingDirectory(i).not()) {
                    mkdir(i)
                    client.changeWorkingDirectory(i)
                }
            }
            client.changeWorkingDirectory("/")
        }
    }

    override fun renameTo(src: String, dst: String) = withClient { client ->
        log { "renameTo: from $src to $dst" }
        if (client.rename(src, dst).not()) throw IOException("Failed to rename file from $src to $dst.")
    }

    override fun upload(src: String, dst: String, onUploading: (read: Long, total: Long) -> Unit) = withClient { client ->
        val name = PathUtil.getFileName(src)
        val dstPath = "$dst/$name"
        log { "upload: $src to $dstPath" }
        val srcFile = File(src)
        val srcFileSize = srcFile.length()
        val srcInputStream = FileInputStream(srcFile)
        val countingStream = CountingInputStreamImpl(srcInputStream, srcFileSize) { read, total -> onUploading(read, total) }
        // storeFile 返回 false = 服务器拒绝（盘满/权限），只看字节数会把半截上传误判成功
        // 重要：storeFile() 是高级 API，内部已经读完服务器的 226 响应并关闭数据连接。
        // 不能再调 completePendingCommand() —— 那只会额外等一个永远不会来的响应，
        // 表现为「进度 100% 后一直转圈直到超时」（v3.7.2 回归）。
        // 只有低级 API（storeFileStream / retrieveFileStream）才需要手动 completePendingCommand()。
        val stored = client.storeFile(dstPath, countingStream)
        srcInputStream.close()
        countingStream.close()
        if (stored.not()) throw IOException("Failed to store remote file: $dstPath, reply: ${client.replyString}.")
        if (countingStream.byteCount != srcFileSize) throw IOException("Incomplete upload: ${countingStream.byteCount}/$srcFileSize bytes.")
        onUploading(countingStream.byteCount, countingStream.byteCount)
    }

    override fun download(src: String, dst: String, onDownloading: (written: Long, total: Long) -> Unit) = withClient { client ->
        val name = PathUtil.getFileName(src)
        val dstPath = "$dst/$name"
        log { "download: $src to $dstPath" }
        val dstFile = File(dstPath)
        // retrieveFileStream 失败时返回 null，直接用会 NPE
        val srcInputStream: InputStream = client.retrieveFileStream(src)
            ?: throw IOException("Failed to open remote stream: $src, reply: ${client.replyString}.")
        val dstOutPutStream: OutputStream = dstFile.outputStream()
        val countingStream = CountingOutputStreamImpl(dstOutPutStream, -1) { written, total -> onDownloading(written, total) }
        srcInputStream.copyTo(countingStream)
        val pendingCompleted = client.completePendingCommand()
        srcInputStream.close()
        dstOutPutStream.close()
        countingStream.close()
        if (pendingCompleted.not()) throw IOException("Failed to complete download: $src.")
        onDownloading(countingStream.byteCount, countingStream.byteCount)
    }

    override fun deleteFile(src: String) = withClient { client ->
        log { "deleteFile: $src" }
        if (client.deleteFile(src).not()) throw IOException("Failed to delete file: $src.")
    }

    override fun removeDirectory(src: String) = withClient { client ->
        log { "removeDirectory: $src" }
        if (client.removeDirectory(src).not()) throw IOException("Failed to remove dir: $src.")
    }

    override fun clearEmptyDirectoriesRecursively(src: String) = withClient { client ->
        val srcFile = listFile(src)
        if (srcFile.isDirectory) {
            val emptyDirs = mutableListOf<String>()
            val paths = mutableListOf(src)

            while (paths.isNotEmpty()) {
                val dir = paths.removeAt(0)
                val files = client.listFiles(dir)
                if (files.isEmpty()) {
                    emptyDirs.add(dir)
                } else {
                    for (file in files) {
                        val path = "${dir}/${file.name}"
                        if (file.isDirectory) {
                            paths.add(path)
                        }
                    }
                }
            }

            // Remove reversed empty dirs.
            for (path in emptyDirs.reversed()) removeDirectory(path)
        }
    }

    private fun listFile(src: String): FTPFile {
        var srcFile: FTPFile? = null
        withClient { client ->
            srcFile = client.mlistFile(src)
            if (srcFile == null) {
                srcFile = client.listFiles(runCatching { PathUtil.getParentPath(src) }.getOrElse { "." })
                    .firstOrNull { it.name == PathUtil.getFileName(src) }
            }
        }
        if (srcFile != null) {
            return srcFile!!
        } else {
            throw IOException("$src not found.")
        }
    }

    /**
     * Actually this is not a recursive function,
     * just keep this name to make it easier to understand.
     */
    override fun deleteRecursively(src: String) = withClient { client ->
        val srcFile = listFile(src)
        if (srcFile.isDirectory.not()) {
            deleteFile(src)
        } else {
            val dirs = mutableListOf(src)
            val paths = mutableListOf(src)

            // Delete files and append all empty dirs.
            while (paths.isNotEmpty()) {
                val dir = paths.first()
                val files = client.listFiles(dir)
                for (file in files) {
                    val path = "${dir}/${file.name}"
                    if (file.isDirectory.not()) {
                        deleteFile(path)
                    } else {
                        paths.add(path)
                        dirs.add(path)
                    }
                }
                paths.removeFirstOrNull()
            }

            // Remove reversed empty dirs.
            for (path in dirs.reversed()) removeDirectory(path)
        }
    }

    override fun listFiles(src: String): DirChildrenParcelable {
        val files = mutableListOf<FileParcelable>()
        val directories = mutableListOf<FileParcelable>()
        withClient { client ->
            val clientFiles = client.listFiles(src)
            for (file in clientFiles) {
                val creationTime = file.timestamp.timeInMillis
                val fileParcelable = FileParcelable(file.name, creationTime)
                if (file.isSymbolicLink) {
                    fileParcelable.link = file.link
                }
                if (file.isDirectory) directories.add(fileParcelable)
                else files.add(fileParcelable)
            }
        }
        files.sortBy { it.name }
        directories.sortBy { it.name }
        return DirChildrenParcelable(files = files, directories = directories)
    }

    override fun walkFileTree(src: String): List<PathParcelable> {
        val pathParcelableList = mutableListOf<PathParcelable>()
        val srcFile = listFile(src)
        if (srcFile.isDirectory.not()) {
            pathParcelableList.add(PathParcelable(src))
        } else {
            val files = listFiles(src)
            for (i in files.files) {
                pathParcelableList.add(PathParcelable("${src}/${i.name}"))
            }
            for (i in files.directories) {
                pathParcelableList.addAll(walkFileTree("${src}/${i.name}"))
            }
        }
        return pathParcelableList
    }

    override fun exists(src: String): Boolean = runCatching { listFile(src) }.isSuccess

    override fun size(src: String): Long {
        var size = 0L
        withClient { client ->
            val srcFile = listFile(src)
            if (srcFile.isDirectory.not()) {
                size += client.getSize(src)?.toLongOrNull() ?: 0
            } else {
                val files = listFiles(src)
                for (i in files.files) {
                    size += client.getSize("${src}/${i.name}")?.toLongOrNull() ?: 0
                }
                for (i in files.directories) {
                    size("${src}/${i.name}")
                }
            }
        }
        log { "size: $size, $src" }
        return size
    }

    override suspend fun testConnection() {
        connect()
        disconnect()
    }

    private fun handleOriginalPath(path: String): String = run {
        val pathSplit = path.toPathList().toMutableList()
        // Remove “$Cloud:”
        pathSplit.removeFirstOrNull()
        pathSplit.toPathString()
    }

    override suspend fun setRemote(context: Context, onSet: suspend (remote: String, extra: String) -> Unit) {
        val extra = entity.getExtraEntity<FTPExtra>()!!
        connect()
        val prefix = "${context.getString(R.string.cloud)}:"
        val pickYou = PickYouLauncher(
            checkPermission = false,
            traverseBackend = { listFiles(it.replaceFirst(prefix, "")) },
            mkdirsBackend = { parent, child ->
                runCatching { mkdirRecursively(handleOriginalPath("$parent/$child")) }.isSuccess
            },
            title = context.getString(R.string.select_target_directory),
            pickerType = PickerType.DIRECTORY,
            rootPathList = listOf(prefix),
            defaultPathList = listOf(prefix),
        )
        withMainContext {
            val pathString = pickYou.awaitLaunch(context)
            onSet(handleOriginalPath(pathString), GsonUtil().toJson(extra))
        }
        disconnect()
    }
}
