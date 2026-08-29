package com.xayah.feature.main.dashboard

import android.content.Context
import android.net.Uri
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.model.CompressionType
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.util.DateUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.SymbolUtil
import com.xayah.core.util.command.BaseUtil
import com.xayah.core.util.command.SELinux
import com.xayah.core.util.command.Tar
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.util.withIOContext
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

@HiltViewModel
class DataMigrationImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appsRepo: AppsRepo,
    private val cloudRepo: CloudRepository,
) : androidx.lifecycle.ViewModel() {

    companion object {
        /**
         * 迁移包内条目名的危险字符黑名单。
         * 目录名形如「应用名_包名」，应用名可为任意 Unicode，
         * 因此不能按包名字符集白名单校验；但以下字符一旦出现，
         * 恢复阶段拼接 root shell 命令时存在注入风险，必须整包拒绝：
         * 单双引号、反引号、美元符、反斜杠、分号、竖线、与号、换行及控制字符。
         */
        private val SHELL_META_REGEX = Regex("['\"`$\\\\;|&\r\n\u0000-\u001f]")

        fun isEntryNameSafe(name: String): Boolean = SHELL_META_REGEX.containsMatchIn(name).not()
    }

    init {
        // 兜底清理历史中断残留的临时迁移包：
        // 进程被杀/强退时 onCleared 不触发，文件指针随内存丢失，下次进入导入页时在此统一清理。
        runCatching {
            context.filesDir.listFiles()?.filter { it.isFile && it.name.startsWith("import_") && it.name.endsWith(".tar.zst") }?.forEach { it.delete() }
        }
    }

    private val _parsedApps = MutableStateFlow<List<String>>(emptyList())
    val parsedApps: StateFlow<List<String>> = _parsedApps.asStateFlow()

    private val _isParsing = MutableStateFlow(false)
    val isParsing: StateFlow<Boolean> = _isParsing.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    /**
     * 分段进度：解析阶段 3 段（下载/校验/解析），导入阶段 1 段（解压与恢复）。
     * Processing 阶段外保持为空列表。
     */
    private val _importStages = MutableStateFlow<List<String>>(emptyList())
    val importStages: StateFlow<List<String>> = _importStages.asStateFlow()

    private val _importCurrentStage = MutableStateFlow(0)
    val importCurrentStage: StateFlow<Int> = _importCurrentStage.asStateFlow()

    private val _importStageProgress = MutableStateFlow(0f)
    val importStageProgress: StateFlow<Float> = _importStageProgress.asStateFlow()

    /** 解析阶段 3 段索引。 */
    private val importStageDownloading = 0
    private val importStageVerifying = 1
    private val importStageParsingList = 2

    /** 切换分段 + 段内进度。 */
    private fun setImportStage(index: Int, progress: Float = 0f) {
        _importCurrentStage.value = index
        _importStageProgress.value = progress
    }

    private fun endImportStages() {
        _importStages.value = emptyList()
        _importCurrentStage.value = 0
        _importStageProgress.value = 0f
    }

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * 安全拒绝原因（条目名含危险字符 / 校验码不匹配）。
     * 与通用 error 分开，用于向用户展示具体原因而非笼统的"导入失败"。
     */
    private val _detailMessage = MutableStateFlow<String?>(null)
    val detailMessage: StateFlow<String?> = _detailMessage.asStateFlow()

    private val _success = MutableStateFlow(false)
    val success: StateFlow<Boolean> = _success.asStateFlow()

    /** 本机历史导出的校验码记录（时间 + 应用数 + sha），供输入框旁快速选取 */
    private val _shaHistory = MutableStateFlow<List<MigrationShaRecord>>(emptyList())
    val shaHistory: StateFlow<List<MigrationShaRecord>> = _shaHistory.asStateFlow()

    /** 已配置的云端账号列表，供「从云端导入」选择 */
    private val _clouds = MutableStateFlow<List<CloudEntity>>(emptyList())
    val clouds: StateFlow<List<CloudEntity>> = _clouds.asStateFlow()

    /** 当前选中云端根目录 migration/ 下的迁移包列表 */
    private val _remotePackages = MutableStateFlow<List<CloudMigrationPackage>>(emptyList())
    val remotePackages: StateFlow<List<CloudMigrationPackage>> = _remotePackages.asStateFlow()

    /**
     * 当前导入阶段（Idle/Parsing/Importing/Success），UI 用于显示顶部进度卡。
     */
    private val _stage = MutableStateFlow(MigrationStage.Idle)
    val stage: StateFlow<MigrationStage> = _stage.asStateFlow()

    private var tmpFilePath: String? = null

    /**
     * 流式计算文件 SHA-256（迁移包可能数 GB，禁止一次性读入内存）。
     */
    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 把迁移包拷贝到临时文件，（可选）校验 SHA-256，解析出其中包含的应用（apps/ 下的目录名，去重）。
     *
     * @param expectedSha256 发包方提供的校验码；非空则强校验，不匹配直接拒绝导入。
     */
    suspend fun parse(uri: Uri, expectedSha256: String? = null, stageLabels: List<String>): List<String> = withIOContext {
        _isParsing.value = true
        _stage.value = MigrationStage.Processing
        _error.value = null
        _detailMessage.value = null
        // 解析阶段用 3 段：下载 / 校验 / 解析
        _importStages.value = stageLabels.take(3)
        setImportStage(importStageDownloading, progress = 0f)
        val result = runCatching {
            // 清理上一次可能残留的临时文件
            cleanupTmpFile()
            val tmpFile = "${context.filesDir}/import_${DateUtil.getTimestamp()}.tar.zst"
            // 段 0：迁移包可能数 GB，用大缓冲 + IO 线程复制，避免主线程阻塞
            context.contentResolver.openInputStream(uri)?.use { input ->
                File(tmpFile).outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("open input stream failed")
            setImportStage(importStageDownloading, progress = 1f)
            tmpFilePath = tmpFile
            // 段 1 + 2 的校验 / 解析在 parseTmpFile 内推进
            parseTmpFile(tmpFile, expectedSha256,
                onVerifying = { setImportStage(importStageVerifying, progress = 0f) },
                onParsing = { setImportStage(importStageParsingList, progress = 0f) },
                onDone = { setImportStage(importStageParsingList, progress = 1f) },
            )
        }
        finishParsing(result)
    }

    /**
     * 从云端下载迁移包到本地临时文件并解析。
     * 与本地 parse 共用同一套解析逻辑：SHA-256 校验、应用列表提取、安全门闩全部一致，
     * 导入后的受保护版本处理也与本地导入完全相同。
     */
    suspend fun parseFromCloud(cloudName: String, remotePath: String, expectedSha256: String? = null, stageLabels: List<String>): List<String> = withIOContext {
        _isParsing.value = true
        _stage.value = MigrationStage.Processing
        _error.value = null
        _detailMessage.value = null
        // 解析阶段用 3 段：下载 / 校验 / 解析
        _importStages.value = stageLabels.take(3)
        setImportStage(importStageDownloading, progress = 0f)
        val result = runCatching {
            cleanupTmpFile()
            val tmpFile = "${context.filesDir}/import_${DateUtil.getTimestamp()}.tar.zst"
            cloudRepo.withClient(cloudName) { client, _ ->
                // 下载到独立子目录（CloudClient.download 会把文件写到 dst/<文件名>），再移动到统一的 import_ 临时文件
                val tmpDir = "${context.filesDir}/cloud_import_tmp"
                File(tmpDir).mkdirs()
                client.download(src = remotePath, dst = tmpDir) { _, _ -> }
                val downloaded = "$tmpDir/${PathUtil.getFileName(remotePath)}"
                check(File(downloaded).renameTo(File(tmpFile))) { "failed to move downloaded migration file" }
                File(tmpDir).delete()
            }
            setImportStage(importStageDownloading, progress = 1f)
            tmpFilePath = tmpFile
            parseTmpFile(tmpFile, expectedSha256,
                onVerifying = { setImportStage(importStageVerifying, progress = 0f) },
                onParsing = { setImportStage(importStageParsingList, progress = 0f) },
                onDone = { setImportStage(importStageParsingList, progress = 1f) },
            )
        }
        finishParsing(result)
    }

    /**
     * 解析本地临时迁移包：SHA-256 校验 + 应用列表提取 + 安全门闩。
     * 本地导入与云端导入共用（parse / parseFromCloud 都调用这里）。
     */
    private suspend fun parseTmpFile(
        tmpFile: String,
        expectedSha256: String?,
        onVerifying: () -> Unit = {},
        onParsing: () -> Unit = {},
        onDone: () -> Unit = {},
    ): List<String> {
        // 可选完整性校验：与导出端展示的 SHA-256 对比
        if (!expectedSha256.isNullOrBlank()) {
            onVerifying()
            val actual = sha256Of(File(tmpFile))
            val expected = expectedSha256.trim().lowercase().removePrefix("sha256:")
            if (actual != expected) {
                throw SecurityException("SHA-256 校验失败：期望 $expected，实际 $actual")
            }
        }

        onParsing()
        // zstd -d -c "<tmp>" | tar -tf -
        val shellResult = BaseUtil.execute(
            "zstd", "-d", "-c", SymbolUtil.shellQuote(tmpFile),
            "|", "tar", "-tf", "-",
        )
        check(shellResult.code == 0) { shellResult.out.joinToString("\n") }

        val apps = shellResult.out.mapNotNull { line ->
            // libsu Shell 默认会把 stdout 不可打印字节（如 UTF-8 中文）转义成 \NNN 八进制，
            // 这里反向解码还原成真实 Unicode 字符，避免弹窗里出现 \346\232\227 这类乱码。
            val decoded = line.decodeShellEscape()
            if (decoded.startsWith("apps/")) {
                decoded.substringAfter("apps/").substringBefore('/').takeIf { it.isNotEmpty() }
            } else {
                null
            }
        }.distinct()

        // 安全门闩：任何条目名携带 shell 元字符即整包拒绝，
        // 防止恶意迁移包在恢复阶段以 root 权限执行注入命令。
        val unsafeEntries = apps.filter { !isEntryNameSafe(it) }
        if (unsafeEntries.isNotEmpty()) {
            throw SecurityException(
                "已拒绝导入：迁移包含危险字符的条目名（${unsafeEntries.joinToString(", ") { it.take(24) }}）"
            )
        }

        onDone()
        _parsedApps.value = apps
        return apps
    }

    /** 解析结束统一收尾：成功回 Idle；失败按异常类型分流展示并清理临时文件 */
    private fun finishParsing(result: Result<List<String>>): List<String> {
        _isParsing.value = false
        // 解析完成回到 Idle，Card 消失，弹窗确认后再走 import 重新设置 stages
        endImportStages()
        if (result.isSuccess) {
            _stage.value = MigrationStage.Idle
            return result.getOrDefault(emptyList())
        }
        val exception = result.exceptionOrNull()
        if (exception is SecurityException) {
            _detailMessage.value = exception.message
        } else {
            _error.value = exception?.message
        }
        _parsedApps.value = emptyList()
        cleanupTmpFile()
        _stage.value = MigrationStage.Idle
        return emptyList()
    }

    /** 加载已配置的云端账号列表（从云端导入时选择来源） */
    suspend fun loadClouds() {
        _clouds.value = cloudRepo.query()
    }

    /** 列出指定云端根目录 migration/ 子目录下的迁移包（.tar.zst），按名称排序 */
    suspend fun loadRemotePackages(cloudName: String) = withIOContext {
        val result = runCatching {
            // withClient 的 block 返回 Unit，列表必须经外部变量带出
            var packages: List<CloudMigrationPackage> = emptyList()
            cloudRepo.withClient(cloudName) { client, entity ->
                val remoteDir = "${entity.remote}/migration"
                if (client.exists(remoteDir)) {
                    packages = client.walkFileTree(remoteDir).map { it.pathString }
                        .filter { it.endsWith(".tar.zst") }
                        .sorted()
                        .map { CloudMigrationPackage(cloudName = cloudName, remotePath = it) }
                }
            }
            packages
        }
        _remotePackages.value = result.getOrDefault(emptyList())
    }

    /**
     * 把临时迁移包解压到备份目录，修正 SELinux，刷新备份列表。
     */
    suspend fun import(stageLabels: List<String>): Boolean = withIOContext {
        _isImporting.value = true
        _stage.value = MigrationStage.Processing
        _error.value = null
        // 导入阶段只 1 段：解压与恢复（解析阶段已在 confirmImport 前完成）
        _importStages.value = listOf(stageLabels.getOrNull(3) ?: "")
        setImportStage(0, progress = 0f)
        val result = runCatching {
            val tmpFile = tmpFilePath ?: error("no tmp file")
            val backupDir = context.localBackupSaveDir()
            // OS 升级后首次导入（如澎湃 OS3→OS4）目标目录可能尚未创建，先 mkdir -p
            val mkdirShell = com.xayah.core.util.command.BaseUtil.execute(
                "mkdir", "-p", SymbolUtil.shellQuote(backupDir),
            )
            check(mkdirShell.code == 0) { "mkdir failed: ${mkdirShell.out.joinToString("\n")}" }
            val decompressResult = Tar.decompress(tmpFile, backupDir, CompressionType.ZSTD.decompressPara)
            check(decompressResult.code == 0) { decompressResult.out.joinToString("\n") }

            // 修正 SELinux 标签
            SELinux.restorecon("$backupDir/apps")

            // 刷新，让恢复列表识别新导入的备份
            appsRepo.load(null) { _, _, _ -> }
            setImportStage(0, progress = 1f)
        }
        // 无论成功失败都清理临时文件，避免残留
        cleanupTmpFile()
        _isImporting.value = false
        if (result.isSuccess) {
            _success.value = true
            _stage.value = MigrationStage.Success
            true
        } else {
            _error.value = result.exceptionOrNull()?.message
            _stage.value = MigrationStage.Idle
            false
        }
    }

    /**
     * 删除当前临时迁移包（解析后取消导入、或导入结束）时调用。
     */
    fun cleanupTmpFile() {
        tmpFilePath?.let { runCatching { File(it).delete() } }
        tmpFilePath = null
    }

    fun consumeSuccess() {
        _success.value = false
    }

    fun consumeDetailMessage() {
        _detailMessage.value = null
    }

    /** 重新加载本机导出校验码历史（进入页面或导出完成后调用） */
    suspend fun loadShaHistory() {
        _shaHistory.value = MigrationShaHistoryStore.load(context)
    }

    /**
     * 重置阶段（snackbar 展示完返回 Idle 让用户继续操作）。
     */
    fun consumeStage() {
        _stage.value = MigrationStage.Idle
    }

    override fun onCleared() {
        super.onCleared()
        cleanupTmpFile()
    }
}

/**
 * 反解码 libsu Shell 输出：
 * 1) 先反转义常见的转义（\NNN 三位八进制、\\、\n、\r、\t）——处理 OnePlus 等设备的 \NNN 形式
 *    例 "\346\232\227" → 字节 0xE6 0xE8 → 真实 char → "暗"。
 * 2) 若步骤 1 后仍含 char code > 0x7F（说明 libsu 在该设备直接把 UTF-8 字节按 Latin-1 读成了 String，
 *    例 0xE6 0x9A 0x97 变成 "æ°—"）——尝试按 Latin-1 取字节再 UTF-8 解码还原。
 *    若二次解码出现替换符 U+FFFD（说明已是正确 UTF-8）→ 保留步骤 1 结果。
 */
private fun String.decodeShellEscape(): String {
    val sb = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c == '\\' && i + 3 < length && this[i + 1] in '0'..'7' && this[i + 2] in '0'..'7' && this[i + 3] in '0'..'7') {
            val oct = (this[i + 1] - '0') * 64 + (this[i + 2] - '0') * 8 + (this[i + 3] - '0')
            sb.append(oct.toChar())
            i += 4
        } else if (c == '\\' && i + 1 < length) {
            when (val n = this[i + 1]) {
                '\\' -> { sb.append('\\'); i += 2 }
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                else -> { sb.append(n); i += 2 }
            }
        } else {
            sb.append(c); i++
        }
    }
    val step1 = sb.toString()
    if (step1.any { it.code > 0x7F }) {
        return runCatching {
            val decoded = step1.toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8)
            if (decoded.contains('\uFFFD')) step1 else decoded
        }.getOrDefault(step1)
    }
    return step1
}

/** 云端迁移包：所属云端账号 + 远程完整路径 */
data class CloudMigrationPackage(
    val cloudName: String,
    val remotePath: String,
) {
    val fileName: String get() = remotePath.substringAfterLast('/')
}
