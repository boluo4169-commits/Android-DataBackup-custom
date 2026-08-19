package com.xayah.core.service.util

import android.app.AppOpsManager
import android.app.AppOpsManagerHidden
import android.content.Context
import android.content.pm.PermissionInfo
import com.xayah.core.common.util.toLineString
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.util.srcDir
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.datastore.readCleanRestoring
import com.xayah.core.datastore.readClearDeviceFingerprint
import com.xayah.core.datastore.readRandomizeSsaid
import com.xayah.core.datastore.readSelectionType
import com.xayah.core.model.DataType
import com.xayah.core.model.OperationState
import com.xayah.core.model.SelectionType
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.PackagePermission
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.util.formatSize
import com.xayah.core.network.client.CloudClient
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.SymbolUtil
import com.xayah.core.util.command.Appops
import com.xayah.core.util.command.Pm
import com.xayah.core.util.command.SELinux
import com.xayah.core.util.command.Tar
import com.xayah.core.util.model.ShellResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

class PackagesRestoreUtil @Inject constructor(
    @ApplicationContext val context: Context,
    private val rootService: RemoteRootService,
    private val taskDao: TaskDao,
    private val packageRepository: PackageRepository,
    private val cloudRepository: CloudRepository,
    private val pathUtil: PathUtil,
) {
    companion object {
        private const val TAG = "PackagesRestoreUtil"

        // 清除设备指纹目前只针对暗区突围（腾讯魔方工作室）
        private const val CLEAR_FINGERPRINT_TARGET_PACKAGE = "com.tencent.mf.uam"

        /**
         * 生成一个全新的随机 SSAID（Android ID，64 位 = 16 个十六进制字符）。
         * 用于「随机化 Android id」功能，给恢复的应用一个全新身份。
         */
        private fun generateRandomSsaid(): String {
            val hex = "0123456789abcdef"
            val random = java.security.SecureRandom()
            return (0 until 16).joinToString(separator = "") { hex[random.nextInt(16)].toString() }
        }
    }

    private fun log(onMsg: () -> String): String = run {
        val msg = onMsg()
        LogUtil.log { TAG to msg }
        msg
    }

    private suspend fun PackageEntity.getDataSelected(dataType: DataType) = when (context.readSelectionType().first()) {
        SelectionType.DEFAULT -> {
            when (dataType) {
                DataType.PACKAGE_APK -> apkSelected
                DataType.PACKAGE_USER -> userSelected
                DataType.PACKAGE_USER_DE -> userDeSelected
                DataType.PACKAGE_DATA -> dataSelected
                DataType.PACKAGE_OBB -> obbSelected
                DataType.PACKAGE_MEDIA -> mediaSelected
                else -> false
            }
        }

        SelectionType.APK -> {
            dataType == DataType.PACKAGE_APK
        }

        SelectionType.DATA -> {
            dataType != DataType.PACKAGE_APK
        }

        SelectionType.BOTH -> {
            true
        }
    }

    private suspend fun TaskDetailPackageEntity.updateInfo(
        dataType: DataType,
        state: OperationState? = null,
        bytes: Long? = null,
        log: String? = null,
        content: String? = null,
    ) = run {
        when (dataType) {
            DataType.PACKAGE_APK -> {
                apkInfo.also {
                    if (state != null) it.state = state
                    if (bytes != null) it.bytes = bytes
                    if (log != null) it.log = log
                    if (content != null) it.content = content
                }
            }

            DataType.PACKAGE_USER -> {
                userInfo.also {
                    if (state != null) it.state = state
                    if (bytes != null) it.bytes = bytes
                    if (log != null) it.log = log
                    if (content != null) it.content = content
                }
            }

            DataType.PACKAGE_USER_DE -> {
                userDeInfo.also {
                    if (state != null) it.state = state
                    if (bytes != null) it.bytes = bytes
                    if (log != null) it.log = log
                    if (content != null) it.content = content
                }
            }

            DataType.PACKAGE_DATA -> {
                dataInfo.also {
                    if (state != null) it.state = state
                    if (bytes != null) it.bytes = bytes
                    if (log != null) it.log = log
                    if (content != null) it.content = content
                }
            }

            DataType.PACKAGE_OBB -> {
                obbInfo.also {
                    if (state != null) it.state = state
                    if (bytes != null) it.bytes = bytes
                    if (log != null) it.log = log
                    if (content != null) it.content = content
                }
            }

            DataType.PACKAGE_MEDIA -> {
                mediaInfo.also {
                    if (state != null) it.state = state
                    if (bytes != null) it.bytes = bytes
                    if (log != null) it.log = log
                    if (content != null) it.content = content
                }
            }

            else -> {}
        }
        taskDao.upsert(this)
    }

    private fun TaskDetailPackageEntity.getLog(
        dataType: DataType,
    ) = when (dataType) {
        DataType.PACKAGE_APK -> apkInfo.log
        DataType.PACKAGE_USER -> userInfo.log
        DataType.PACKAGE_USER_DE -> userDeInfo.log
        DataType.PACKAGE_DATA -> dataInfo.log
        DataType.PACKAGE_OBB -> obbInfo.log
        DataType.PACKAGE_MEDIA -> mediaInfo.log
        else -> ""
    }

    suspend fun restoreApk(userId: Int, p: PackageEntity, t: TaskDetailPackageEntity, srcDir: String): ShellResult = run {
        log { "Restoring apk..." }

        val dataType = DataType.PACKAGE_APK
        val packageName = p.packageName
        val ct = p.indexInfo.compressionType
        val src = packageRepository.getArchiveDst(dstDir = srcDir, dataType = dataType, ct = ct)
        var isSuccess = true
        val out = mutableListOf<String>()

        if (p.getDataSelected(dataType).not()) {
            t.updateInfo(dataType = dataType, state = OperationState.SKIP)
        } else {
            // Return if the archive doesn't exist.
            if (rootService.exists(src)) {
                val sizeBytes = rootService.calculateSize(src)
                t.updateInfo(dataType = dataType, state = OperationState.PROCESSING, bytes = sizeBytes)
                // Verify the archive integrity before restoring.
                val checksumMismatch = ChecksumUtil.verify(rootService = rootService, src = src)
                if (checksumMismatch != null) {
                    out.add(log { "Checksum mismatch: ${checksumMismatch.archivePath}" })
                    if (ChecksumConfirmation.awaitDecision(checksumMismatch)) {
                        out.add(log { "User chose to force restore." })
                    } else {
                        isSuccess = false
                        out.add(log { "Archive corrupted: $src" })
                        t.updateInfo(dataType = dataType, state = OperationState.ERROR, log = out.toLineString())
                        return@run ShellResult(code = -1, input = listOf(), out = out)
                    }
                }
                // Decompress apk archive
                val tmpApkPath = pathUtil.getTmpApkPath(packageName = packageName)
                rootService.deleteRecursively(tmpApkPath)
                rootService.mkdirs(tmpApkPath)
                Tar.decompress(src = src, dst = tmpApkPath, extra = ct.decompressPara).also { result ->
                    isSuccess = result.isSuccess
                    out.addAll(result.out)
                }

                // Install apks
                rootService.listFilePaths(tmpApkPath).also { apksPath ->
                    when (apksPath.size) {
                        0 -> {
                            isSuccess = false
                            out.add(log { "$tmpApkPath is empty." })
                        }

                        1 -> {
                            Pm.install(userId = userId, src = apksPath.first()).also { result ->
                                isSuccess = isSuccess && result.isSuccess
                                out.addAll(result.out)
                            }
                        }

                        else -> {
                            var pmSession = ""
                            Pm.Install.create(userId = userId).also { result ->
                                if (result.isSuccess) pmSession = result.outString
                            }
                            if (pmSession.isNotEmpty()) {
                                out.add(log { "Install session: $pmSession." })

                            } else {
                                isSuccess = false
                                out.add(log { "Failed to get install session." })
                            }

                            apksPath.forEach { apkPath ->
                                Pm.Install.write(session = pmSession, srcName = PathUtil.getFileName(apkPath), src = apkPath).also { result ->
                                    isSuccess = isSuccess && result.isSuccess
                                    out.addAll(result.out)
                                }
                            }

                            Pm.Install.commit(pmSession).also { result ->
                                isSuccess = isSuccess && result.isSuccess
                                out.addAll(result.out)
                            }
                        }
                    }
                }
                rootService.deleteRecursively(tmpApkPath)

                // Check the installation again.
                // pm install 同步返回 Success 后，PackageManager 内部状态可能还没刷新（尤其跨系统大版本恢复时）。
                // 重试等待最多 10 秒，避免恢复 USER/USER_DE 时 PackageManager 查不到包而失败。
                var isInstalled = false
                repeat(20) {
                    isInstalled = rootService.queryInstalled(packageName = packageName, userId = userId)
                    if (isInstalled) return@repeat
                    delay(500)
                }
                if (isInstalled.not()) {
                    isSuccess = false
                    log { "Not installed: $packageName." }
                }
            } else {
                isSuccess = false
                out.add(log { "Not exist: $src" })
            }
            t.updateInfo(dataType = dataType, state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = out.toLineString())
        }

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }

    /**
     * Package data: USER, USER_DE, DATA, OBB, MEDIA
     */
    suspend fun restoreData(userId: Int, p: PackageEntity, t: TaskDetailPackageEntity, dataType: DataType, srcDir: String): ShellResult = run {
        log { "Restoring ${dataType.type}..." }

        val packageName = p.packageName
        val ct = p.indexInfo.compressionType
        val src = packageRepository.getArchiveDst(dstDir = srcDir, dataType = dataType, ct = ct)
        val dstDir = packageRepository.getDataSrcDir(dataType, userId)
        val dst = packageRepository.getDataSrc(dstDir, packageName)
        // 跨系统大版本恢复时，APK 装上后 PackageManager 缓存可能还没刷新，
        // 重试等待最多 10 秒拿到真实 uid（-1 表示还没刷新好）。
        var uid = rootService.getPackageUid(packageName = packageName, userId = userId)
        if (uid == -1) {
            repeat(20) {
                delay(500)
                uid = rootService.getPackageUid(packageName = packageName, userId = userId)
                if (uid != -1) return@repeat
            }
        }
        var isSuccess = true
        val out = mutableListOf<String>()

        if (p.getDataSelected(dataType).not()) {
            t.updateInfo(dataType = dataType, state = OperationState.SKIP)
        } else {
            if (uid == -1) {
                isSuccess = false
                out.add(log { "Failed to get uid of $packageName." })
            } else {
                // Return if the archive doesn't exist.
                if (rootService.exists(src)) {
                    val sizeBytes = rootService.calculateSize(src)
                    t.updateInfo(dataType = dataType, state = OperationState.PROCESSING, bytes = sizeBytes)
                    // Verify the archive integrity before restoring.
                    val checksumMismatch = ChecksumUtil.verify(rootService = rootService, src = src)
                    if (checksumMismatch != null) {
                        out.add(log { "Checksum mismatch: ${checksumMismatch.archivePath}" })
                        if (ChecksumConfirmation.awaitDecision(checksumMismatch)) {
                            out.add(log { "User chose to force restore." })
                        } else {
                            isSuccess = false
                            out.add(log { "Archive corrupted: $src" })
                            t.updateInfo(dataType = dataType, state = OperationState.ERROR, log = out.toLineString())
                            return@run ShellResult(code = -1, input = listOf(), out = out)
                        }
                    }
                    // Generate exclusion items.
                    val exclusionList = mutableListOf<String>()
                    when (dataType) {
                        DataType.PACKAGE_USER, DataType.PACKAGE_USER_DE, DataType.PACKAGE_DATA, DataType.PACKAGE_OBB, DataType.PACKAGE_MEDIA -> {
                            // Exclude cache
                            val folders = listOf(".ota", "cache", "lib", "code_cache", "no_backup")
                            exclusionList.addAll(folders.map { "${SymbolUtil.QUOTE}$packageName/$it${SymbolUtil.QUOTE}" })
                            if (dataType == DataType.PACKAGE_DATA || dataType == DataType.PACKAGE_OBB || dataType == DataType.PACKAGE_MEDIA) {
                                // Exclude Backup_*
                                exclusionList.add("${SymbolUtil.QUOTE}Backup_${SymbolUtil.QUOTE}*")
                                // 清除设备指纹：仅对暗区突围（com.tencent.mf.uam）生效，
                                // 剔除其设备指纹文件，让游戏重新生成全新标识（用于换号防偏框）。
                                if (dataType == DataType.PACKAGE_DATA && packageName == CLEAR_FINGERPRINT_TARGET_PACKAGE && context.readClearDeviceFingerprint().first()) {
                                    val fingerprintExclusions = listOf(
                                        "TGPA",                // 腾讯游戏性能助手（.tgpacloud 设备数据）
                                        "g6_player_prefs.ini", // G6 引擎玩家偏好（加密配置）
                                        "pixui",               // PixUI 界面引擎缓存
                                        "AppVersionCache.txt", // 版本缓存
                                        "program_version.txt", // 版本号
                                    )
                                    exclusionList.addAll(fingerprintExclusions.map { "${SymbolUtil.QUOTE}$packageName/files/$it${SymbolUtil.QUOTE}" })
                                    // UE4 崩溃上报目录（目录名嵌 DeviceId），basename 匹配任意层级
                                    exclusionList.add("${SymbolUtil.QUOTE}CrashReportClient${SymbolUtil.QUOTE}")
                                    log { "Clear device fingerprint: excluded fingerprint files for $packageName." }
                                }
                            }

                        }

                        else -> {}
                    }
                    log { "ExclusionList: $exclusionList." }

                    // Get the SELinux context of the path.
                    val pathContext: String
                    SELinux.getContext(path = dst).also { result ->
                        pathContext = if (result.isSuccess) result.outString else ""
                    }

                    log { "Original SELinux context: $pathContext." }

                    // 干净恢复：解压前清空目标目录（排除 lib/cache 等系统目录，避免误删 native 库）。
                    // 不再用 tar --recursive-unlink（它会递归删除整个目录，包括 lib）。
                    if (context.readCleanRestoring().first()) {
                        SELinux.cleanRestore(dst = dst).also { result ->
                            isSuccess = isSuccess && result.isSuccess
                            out.addAll(result.out)
                        }
                    }

                    // Decompress the archive.
                    // m = false → 恢复原始 mtime，减少恢复后文件时间戳残留（避免触发游戏反作弊的 mtime 异常检测）。
                    Tar.decompress(
                        exclusionList = exclusionList,
                        clear = "",
                        m = false,
                        src = src,
                        dst = dstDir,
                        extra = ct.decompressPara
                    ).also { result ->
                        isSuccess = result.isSuccess
                        out.addAll(result.out)
                    }

                    // Restore SELinux context.
                    var gid: UInt = uid.toUInt()
                    if (dataType == DataType.PACKAGE_DATA || dataType == DataType.PACKAGE_OBB || dataType == DataType.PACKAGE_MEDIA) {
                        val (_, pathGid) = rootService.getUidGid(dataType.srcDir(userId))
                        gid = pathGid
                    }
                    SELinux.chown(uid = uid.toUInt(), gid = gid, path = dst).also { result ->
                        isSuccess = isSuccess && result.isSuccess
                        out.addAll(result.out)
                    }
                    if (pathContext.isNotEmpty()) {
                        SELinux.chcon(context = pathContext, path = dst).also { result ->
                            isSuccess = isSuccess && result.isSuccess
                            out.addAll(result.out)
                        }
                    } else if (dataType == DataType.PACKAGE_DATA || dataType == DataType.PACKAGE_OBB || dataType == DataType.PACKAGE_MEDIA) {
                        // 外部存储（/storage/emulated/0/Android/{data,obb,media}/）：恢复前目录通常不存在，
                        // 手动 getContext 拿不到正确 context（父目录 context 是 fuse/media_rw_data_file，
                        // 旧的 system_data_file→app_data_file 替换不生效）。改用 restorecon 让系统按
                        // file_contexts 规则自动恢复正确 context（含多用户 category）。修复游戏重装后
                        // 更新资源报"保存路径不可写/磁盘只读"（错误码 556793857）的问题。
                        SELinux.restorecon(dst).also { result ->
                            isSuccess = isSuccess && result.isSuccess
                            out.addAll(result.out)
                        }
                    } else {
                        val parentContext: String
                        SELinux.getContext(dstDir).also { result ->
                            parentContext = if (result.isSuccess) result.outString.replace("system_data_file", "app_data_file") else ""
                        }
                        if (parentContext.isNotEmpty()) {
                            SELinux.chcon(context = parentContext, path = dst).also { result ->
                                isSuccess = isSuccess && result.isSuccess
                                out.addAll(result.out)
                            }
                        } else {
                            isSuccess = false
                            out.add(log { "Failed to restore context: $dst" })
                        }
                    }

                } else {
                    if (dataType == DataType.PACKAGE_USER) {
                        isSuccess = false
                        out.add(log { "Not exist: $src" })
                        t.updateInfo(dataType = dataType, state = OperationState.ERROR, log = out.toLineString())
                        return@run ShellResult(code = -1, input = listOf(), out = out)
                    } else {
                        out.add(log { "Not exist and skip: $src" })
                        t.updateInfo(dataType = dataType, state = OperationState.SKIP, log = out.toLineString())
                        return@run ShellResult(code = -2, input = listOf(), out = out)
                    }
                }
            }
            t.updateInfo(dataType = dataType, state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = out.toLineString())
        }

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }

    suspend fun restorePermissions(userId: Int, p: PackageEntity) = run {
        log { "Restoring permissions..." }

        val packageName = p.packageName
        val uid = rootService.getPackageUid(packageName = packageName, userId = userId)
        val user = rootService.getUserHandle(userId)
        val permissions = p.extraInfo.permissions

        if (p.permissionSelected) {
            if (uid != -1) {
                Appops.reset(userId = userId, packageName = packageName)
                log { "Permissions size: ${permissions.size}..." }
                permissions.forEach {
                    log { "Permission name: ${it.name}, isGranted: ${it.isGranted}, op: ${it.op}, mode: ${it.mode}" }
                    runCatching {
                        // 只有 runtime 权限（dangerous/development）才能 grant/revoke。
                        // 非 runtime 权限（如 REQUEST_INSTALL_PACKAGES、FOREGROUND_SERVICE）只恢复 appop，
                        // 跳过 grant/revoke，避免产生 "not a changeable permission type" 的日志噪音。
                        if (it.isRuntimePermission()) {
                            if (it.isGranted) {
                                rootService.grantRuntimePermission(packageName, it.name, user!!)
                            } else {
                                rootService.revokeRuntimePermission(packageName, it.name, user!!)
                                // revoke 只清 grant 标志，对应 appop 可能仍为 MODE_DEFAULT，
                                // 部分检测工具（如 QQ 安全中心）按 appop mode 判定，MODE_DEFAULT 会被当成「允许」。
                                // 额外把 appop 设为 MODE_IGNORED，确保「拒绝」彻底生效。
                                if (it.op != AppOpsManagerHidden.OP_NONE) {
                                    rootService.setOpsMode(it.op, uid, packageName, AppOpsManager.MODE_IGNORED)
                                }
                            }
                        } else if (it.op != AppOpsManagerHidden.OP_NONE) {
                            // 非 runtime 的纯 appop（无对应 runtime 权限），恢复备份的 mode
                            rootService.setOpsMode(it.op, uid, packageName, it.mode)
                        }
                    }
                }
            } else {
                log { "Failed to get uid of $packageName." }
            }
        } else {
            log { "Skip." }
        }
    }

    // runtime 权限 = dangerous(0x1) 或 development(flag 0x20)；protectionLevel 低 0xF 位是基值，其余是 flags。
    // 查不到（权限不存在）时按 runtime 处理，保持旧行为。
    private fun PackagePermission.isRuntimePermission(): Boolean = runCatching {
        val level = context.packageManager.getPermissionInfo(name, 0).protectionLevel
        (level and 0xF) == PermissionInfo.PROTECTION_DANGEROUS || (level and 0x20) != 0
    }.getOrDefault(true)

    suspend fun restoreSsaid(userId: Int, p: PackageEntity) = run {
        log { "Restoring ssaid..." }

        val packageName = p.packageName
        val uid = rootService.getPackageUid(packageName = packageName, userId = userId)
        val ssaid = p.extraInfo.ssaid

        if (context.readRandomizeSsaid().first()) {
            // 随机化：生成一个全新的随机 SSAID 写入，不沿用旧值（给应用一个全新身份）
            if (uid != -1) {
                val randomSsaid = generateRandomSsaid()
                log { "Randomize Ssaid: $randomSsaid" }
                rootService.setPackageSsaidAsUser(packageName, uid, userId, randomSsaid)
            } else {
                log { "Failed to get uid of $packageName." }
            }
        } else if (p.ssaidSelected) {
            if (uid != -1) {
                if (ssaid.isNotEmpty()) {
                    log { "Ssaid: $ssaid" }
                    rootService.setPackageSsaidAsUser(packageName, uid, userId, ssaid)
                } else {
                    log { "Ssaid is empty, skip." }
                }
            } else {
                log { "Failed to get uid of $packageName." }
            }
        } else {
            log { "Skip." }
        }
    }

    suspend fun download(
        client: CloudClient,
        p: PackageEntity,
        t: TaskDetailPackageEntity,
        dataType: DataType,
        srcDir: String,
        dstDir: String,
        onDownloaded: suspend (p: PackageEntity, t: TaskDetailPackageEntity, dataType: DataType, path: String) -> Unit
    ) = run {
        val ct = p.indexInfo.compressionType
        val src = packageRepository.getArchiveDst(dstDir = srcDir, dataType = dataType, ct = ct)

        if (p.getDataSelected(dataType).not()) {
            t.updateInfo(dataType = dataType, state = OperationState.SKIP)
        } else {
            t.updateInfo(dataType = dataType, state = OperationState.DOWNLOADING)

            if (client.exists(src)) {
                var flag = true
                var progress = 0.0
                with(CoroutineScope(coroutineContext)) {
                    launch {
                        while (flag) {
                            t.updateInfo(dataType = dataType, content = progress.formatSize())
                            delay(500)
                        }
                    }
                }

                cloudRepository.download(client = client,
                    src = src,
                    dstDir = dstDir,
                    onDownloading = { written, _ -> progress = written.toDouble() },
                    onDownloaded = {
                        onDownloaded(p, t, dataType, dstDir)
                    }
                ).apply {
                    flag = false
                    t.updateInfo(
                        dataType = dataType,
                        log = (t.getLog(dataType) + "\n${outString}").trim(),
                        content = progress.formatSize()
                    )
                    if (isSuccess.not()) {
                        t.updateInfo(dataType = dataType, state = OperationState.ERROR)
                    }
                }
            } else {
                if (dataType == DataType.PACKAGE_USER || dataType == DataType.PACKAGE_APK) {
                    t.updateInfo(dataType = dataType, state = OperationState.ERROR, log = log { "Failed to connect to cloud or file not exist: $src" })
                } else {
                    t.updateInfo(dataType = dataType, state = OperationState.SKIP, log = log { "Failed to connect to cloud or file not exist, skip: $src" })
                }
            }
        }
    }
}
