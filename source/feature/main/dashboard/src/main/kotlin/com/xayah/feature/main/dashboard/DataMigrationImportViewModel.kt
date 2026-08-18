package com.xayah.feature.main.dashboard

import android.content.Context
import android.net.Uri
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.model.CompressionType
import com.xayah.core.util.DateUtil
import com.xayah.core.util.SymbolUtil
import com.xayah.core.util.command.BaseUtil
import com.xayah.core.util.command.SELinux
import com.xayah.core.util.command.Tar
import com.xayah.core.util.localBackupSaveDir
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DataMigrationImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appsRepo: AppsRepo,
) : androidx.lifecycle.ViewModel() {

    private val _parsedApps = MutableStateFlow<List<String>>(emptyList())
    val parsedApps: StateFlow<List<String>> = _parsedApps.asStateFlow()

    private val _isParsing = MutableStateFlow(false)
    val isParsing: StateFlow<Boolean> = _isParsing.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _success = MutableStateFlow(false)
    val success: StateFlow<Boolean> = _success.asStateFlow()

    private var tmpFilePath: String? = null

    /**
     * 把迁移包拷贝到临时文件，解析出其中包含的应用（apps/ 下的目录名，去重）。
     */
    suspend fun parse(uri: Uri): List<String> {
        _isParsing.value = true
        _error.value = null
        val result = runCatching {
            // 清理上一次可能残留的临时文件
            cleanupTmpFile()
            val tmpFile = "${context.filesDir}/import_${DateUtil.getTimestamp()}.tar.zst"
            context.contentResolver.openInputStream(uri)?.use { input ->
                File(tmpFile).outputStream().use { input.copyTo(it) }
            } ?: error("open input stream failed")
            tmpFilePath = tmpFile

            // zstd -d -c "<tmp>" | tar -tf -
            val shellResult = BaseUtil.execute(
                "zstd", "-d", "-c", "${SymbolUtil.QUOTE}$tmpFile${SymbolUtil.QUOTE}",
                "|", "tar", "-tf", "-",
            )
            check(shellResult.code == 0) { shellResult.out.joinToString("\n") }

            val apps = shellResult.out.mapNotNull { line ->
                if (line.startsWith("apps/")) {
                    line.substringAfter("apps/").substringBefore('/').takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            }.distinct()
            _parsedApps.value = apps
            apps
        }
        _isParsing.value = false
        if (result.isFailure) {
            _error.value = result.exceptionOrNull()?.message
            // 解析失败时也清理临时文件
            cleanupTmpFile()
        }
        return result.getOrDefault(emptyList())
    }

    /**
     * 把临时迁移包解压到备份目录，修正 SELinux，刷新备份列表。
     */
    suspend fun import(): Boolean {
        _isImporting.value = true
        _error.value = null
        val result = runCatching {
            val tmpFile = tmpFilePath ?: error("no tmp file")
            val backupDir = context.localBackupSaveDir()
            val decompressResult = Tar.decompress(tmpFile, backupDir, CompressionType.ZSTD.decompressPara)
            check(decompressResult.code == 0) { decompressResult.out.joinToString("\n") }

            // 修正 SELinux 标签
            SELinux.restorecon("$backupDir/apps")

            // 刷新，让恢复列表识别新导入的备份
            appsRepo.load(null) { _, _, _ -> }
        }
        // 无论成功失败都清理临时文件，避免残留
        cleanupTmpFile()
        _isImporting.value = false
        return if (result.isSuccess) {
            _success.value = true
            true
        } else {
            _error.value = result.exceptionOrNull()?.message
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

    override fun onCleared() {
        super.onCleared()
        cleanupTmpFile()
    }
}
