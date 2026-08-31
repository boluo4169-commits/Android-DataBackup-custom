package com.xayah.feature.main.dashboard

import android.content.Context
import android.net.Uri
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.repository.UsersRepo
import com.xayah.core.datastore.readCompressionThreads
import com.xayah.core.model.OpType
import com.xayah.core.model.SortType
import com.xayah.core.model.UserInfo
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.util.DateUtil
import com.xayah.core.util.SymbolUtil
import com.xayah.core.util.command.BaseUtil
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.util.withIOContext
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

data class DataMigrationVersionItem(
    val key: String,        // "user_0" 或 "user_0@<preserveId>"
    val preserveId: Long,   // 0 = 主备份，非 0 = 受保护版本
    val sizeBytes: Double,
    val lastBackupTime: Long,
    val preserveIndex: Int = 0, // 受保护版本序号（1、2、3...，按备份时间降序），主备份为 0
)

data class DataMigrationExportItem(
    val key: String,        // "label_packageName"
    val label: String,
    val packageName: String,
    val userId: Int,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val versions: List<DataMigrationVersionItem>,
) {
    val hasPreserved: Boolean get() = versions.any { it.preserveId != 0L }
    val totalSizeBytes: Double get() = versions.sumOf { it.sizeBytes }

    fun versionFullKey(version: DataMigrationVersionItem): String = "$key/${version.key}"
}

@HiltViewModel
class DataMigrationExportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appsRepo: AppsRepo,
    private val packageRepository: PackageRepository,
    private val usersRepo: UsersRepo,
    private val cloudRepo: CloudRepository,
) : androidx.lifecycle.ViewModel() {

    private val _allItems = MutableStateFlow<List<DataMigrationExportItem>>(emptyList())
    val allItems: StateFlow<List<DataMigrationExportItem>> = _allItems.asStateFlow()

    /** 已配置的云端账号列表（WebDAV/FTP/SFTP），供「导出到云端」选择 */
    private val _clouds = MutableStateFlow<List<CloudEntity>>(emptyList())
    val clouds: StateFlow<List<CloudEntity>> = _clouds.asStateFlow()

    private val _userList = MutableStateFlow<List<UserInfo>>(emptyList())
    val userList: StateFlow<List<UserInfo>> = _userList.asStateFlow()

    private val _userMap = MutableStateFlow<Map<Int, Long>>(emptyMap())
    val userMap: StateFlow<Map<Int, Long>> = _userMap.asStateFlow()

    private val _userIndex = MutableStateFlow(0)
    val userIndex: StateFlow<Int> = _userIndex.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortIndex = MutableStateFlow(2)
    val sortIndex: StateFlow<Int> = _sortIndex.asStateFlow()

    private val _sortType = MutableStateFlow(SortType.DESCENDING)
    val sortType: StateFlow<SortType> = _sortType.asStateFlow()

    private val _selectedKeys = MutableStateFlow<Set<String>>(emptySet())
    val selectedKeys: StateFlow<Set<String>> = _selectedKeys.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _success = MutableStateFlow(false)
    val success: StateFlow<Boolean> = _success.asStateFlow()

    /** 最近一次成功导出的迁移包 SHA-256，供接收方校验完整性 */
    private val _lastSha256 = MutableStateFlow<String?>(null)
    val lastSha256: StateFlow<String?> = _lastSha256.asStateFlow()

    /**
     * 当前导出阶段（Idle/Processing/Success），UI 用于显示顶部进度卡。
     */
    private val _stage = MutableStateFlow(MigrationStage.Idle)
    val stage: StateFlow<MigrationStage> = _stage.asStateFlow()

    /**
     * 分段进度：导出内部细分的 4 段（校验 / 打包 / 校验码 / 上传），
     * UI 用 [SegmentedLinearProgressIndicator] 显示。
     * Processing 阶段外保持为空。
     */
    private val _exportStages = MutableStateFlow<List<String>>(emptyList())
    val exportStages: StateFlow<List<String>> = _exportStages.asStateFlow()

    private val _exportCurrentStage = MutableStateFlow(0)
    val exportCurrentStage: StateFlow<Int> = _exportCurrentStage.asStateFlow()

    private val _exportStageProgress = MutableStateFlow(0f)
    val exportStageProgress: StateFlow<Float> = _exportStageProgress.asStateFlow()

    /** 4 个内部阶段的索引常量（顺序：0=校验 1=打包 2=校验码 3=上传）。 */
    private val stageValidating = 0
    private val stagePacking = 1
    private val stageHashing = 2
    private val stageUploading = 3

    /** 初始化分段标签并切到指定段位（用于 export / exportToCloud 入口）。 */
    private fun beginExportStages(stageLabels: List<String>) {
        _exportStages.value = stageLabels
        _exportCurrentStage.value = 0
        _exportStageProgress.value = 0f
    }

    /** 把当前段切到 [index]，并把段内进度归零（由调用方后续推进到 1f 表示段完成）。 */
    private fun setExportStage(index: Int, progress: Float = 0f) {
        _exportCurrentStage.value = index
        _exportStageProgress.value = progress
    }

    private fun endExportStages() {
        _exportStages.value = emptyList()
        _exportCurrentStage.value = 0
        _exportStageProgress.value = 0f
    }

    suspend fun load() {
        if (_isLoading.value) return
        _isLoading.value = true
        _error.value = null
        runCatching {
            // 云端账号列表（导出到云端时选择目标）
            _clouds.value = cloudRepo.query()
            // 扫描本地备份目录，确保列表最新（与恢复页刷新逻辑一致）
            appsRepo.load(null) { _, _, _ -> }
            // 只列本地备份记录（cloud 为空 = 本地目录备份）：
            // 云端恢复时会把云端记录写入数据库（cloud 非空），但本地 /storage/emulated/0/DataBackup/apps
            // 下没有对应目录，勾选导出会报 tar "Cannot stat"。云端备份请走云备份恢复，不参与本地迁移打包。
            val packages = packageRepository.queryPackages(OpType.RESTORE, "", context.localBackupSaveDir())
            val groups = packages.groupBy { it.archivesRelativeDir.substringBeforeLast('/') }
            val itemList = groups.map { (dir, entities) ->
                DataMigrationExportItem(
                    key = dir,
                    label = entities.first().packageInfo.label,
                    packageName = entities.first().packageName,
                    userId = entities.first().userId,
                    firstInstallTime = entities.first().packageInfo.firstInstallTime,
                    lastUpdateTime = entities.first().packageInfo.lastUpdateTime,
                    versions = entities.map { entity ->
                        DataMigrationVersionItem(
                            key = entity.archivesRelativeDir.substringAfterLast('/'),
                            preserveId = entity.preserveId,
                            sizeBytes = entity.displayStatsBytes,
                            lastBackupTime = entity.extraInfo.lastBackupTime,
                        )
                    }.let { list ->
                        // 受保护版本按备份时间降序编号（最新被覆盖的 = 护盾 1），与恢复页机制一致
                        val preserved = list.filter { it.preserveId != 0L }
                            .sortedByDescending { it.lastBackupTime }
                            .mapIndexed { index, version -> version.copy(preserveIndex = index + 1) }
                        list.filter { it.preserveId == 0L } + preserved
                    },
                )
            }
            _allItems.value = itemList
            _userList.value = usersRepo.getUsers(OpType.RESTORE).first()
            _userMap.value = usersRepo.getUsersMap(OpType.RESTORE, "", context.localBackupSaveDir()).first()
            _userIndex.value = 0
            _selectedKeys.value = itemList.flatMap { item ->
                item.versions.map { item.versionFullKey(it) }
            }.toSet()
        }.onFailure {
            _error.value = it.message
        }
        _isLoading.value = false
    }

    /** 当前用户空间 + 搜索 + 排序后的显示列表 */
    fun displayItems(): List<DataMigrationExportItem> {
        val currentUser = _userList.value.getOrNull(_userIndex.value)?.id
        val query = _searchQuery.value
        val filtered = _allItems.value.filter { item ->
            (currentUser == null || item.userId == currentUser) &&
                (query.isEmpty() || item.label.contains(query, true) || item.packageName.contains(query, true))
        }
        val comparator: Comparator<DataMigrationExportItem> = when (_sortIndex.value) {
            1 -> compareBy { it.firstInstallTime }
            2 -> compareBy { it.lastUpdateTime }
            3 -> compareBy { it.totalSizeBytes }
            else -> compareBy { it.label.lowercase() }
        }
        return if (_sortType.value == SortType.DESCENDING) {
            filtered.sortedWith(comparator.reversed())
        } else {
            filtered.sortedWith(comparator)
        }
    }

    fun search(text: String) {
        _searchQuery.value = text
    }

    fun setUser(index: Int) {
        _userIndex.value = index
    }

    fun setSortIndex(index: Int) {
        _sortIndex.value = index
    }

    fun toggleSortType() {
        _sortType.value = if (_sortType.value == SortType.ASCENDING) SortType.DESCENDING else SortType.ASCENDING
    }

    /** 勾选/取消单个版本 */
    fun toggleVersion(fullKey: String, checked: Boolean) {
        _selectedKeys.update { if (checked) it + fullKey else it - fullKey }
    }

    /** 勾选/取消整个应用的所有版本 */
    fun toggleApp(item: DataMigrationExportItem, checked: Boolean) {
        val keys = item.versions.map { item.versionFullKey(it) }
        _selectedKeys.update {
            if (checked) it + keys else it - keys
        }
    }

    /** 应用的所有版本是否都已勾选 */
    fun isAppAllSelected(item: DataMigrationExportItem): Boolean {
        val keys = item.versions.map { item.versionFullKey(it) }
        return keys.isNotEmpty() && _selectedKeys.value.containsAll(keys)
    }

    /** 应用是否有部分版本被勾选（用于展开行显示 indeterminate） */
    fun isAppPartiallySelected(item: DataMigrationExportItem): Boolean {
        val keys = item.versions.map { item.versionFullKey(it) }
        val selected = _selectedKeys.value
        return keys.any { it in selected } && !selected.containsAll(keys)
    }

    fun selectAll() {
        _selectedKeys.value = _allItems.value.flatMap { item ->
            item.versions.map { item.versionFullKey(it) }
        }.toSet()
    }

    fun unselectAll() {
        _selectedKeys.value = emptySet()
    }

    val selectedCount: Int
        get() = _selectedKeys.value.size

    val totalCount: Int
        get() = _allItems.value.size

    /**
     * 把勾选的版本目录（apps/<label_pkg>/<user_0...>）打包成迁移包并写入用户选择的 uri。
     * 本地模式：打包 → 复制到 SAF 目标位置 → 删除临时文件。
     */
    suspend fun export(uri: Uri, stageLabels: List<String>): Boolean = withIOContext {
        _isExporting.value = true
        _stage.value = MigrationStage.Processing
        _error.value = null
        beginExportStages(stageLabels)
        val result = runCatching {
            // 阶段 0：校验（目录预检 + 路径生成）
            setExportStage(stageValidating, progress = 1f)
            val dstPath = buildMigrationPackage { stageIndex, progress ->
                // buildMigrationPackage 在 3 个切分点回调：1 打包起、2 校验码起、2 校验码完
                setExportStage(stageIndex, progress)
            }

            // 阶段 3：复制到 SAF（本地导出最后一步）
            setExportStage(stageUploading, progress = 0f)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                File(dstPath).inputStream().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                    }
                }
            } ?: error("open output stream failed")
            File(dstPath).delete()
            setExportStage(stageUploading, progress = 1f)
        }
        endExportStages()
        _isExporting.value = false
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
     * 导出到云端：打包后直接上传到云端根目录的 migration/ 子目录（与 apps/files/configs 平级）。
     * 上传成功后 CloudRepository.upload 会自动删除本地临时迁移包，无需手动清理。
     * 受保护版本的处理与本地导出完全一致（共用 buildMigrationPackage 的勾选与打包逻辑）。
     */
    suspend fun exportToCloud(cloudName: String, stageLabels: List<String>): Boolean = withIOContext {
        _isExporting.value = true
        _stage.value = MigrationStage.Processing
        _error.value = null
        beginExportStages(stageLabels)
        val result = runCatching {
            // 阶段 0：校验 + 打包 + 校验码都在 buildMigrationPackage 内部推进
            setExportStage(stageValidating, progress = 1f)
            val dstPath = buildMigrationPackage { stageIndex, progress ->
                setExportStage(stageIndex, progress)
            }
            // 阶段 3：上传到云端 migration/ 目录
            setExportStage(stageUploading, progress = 0f)
            cloudRepo.withClient(cloudName) { client, entity ->
                val remoteMigrationDir = "${entity.remote}/migration"
                // 首次上传时目标目录可能不存在（如刚配置的云端），先逐级创建
                client.mkdirRecursively(remoteMigrationDir)
                val uploadResult = cloudRepo.upload(client = client, src = dstPath, dstDir = remoteMigrationDir)
                check(uploadResult.code == 0) { uploadResult.out.joinToString("\n") }
            }
            setExportStage(stageUploading, progress = 1f)
        }
        endExportStages()
        _isExporting.value = false
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
     * 打包勾选的版本目录到本地临时文件（含 SHA-256 计算与历史记录写入），返回临时文件路径。
     * 本地导出与云端导出共用，保证受保护版本的勾选语义与打包内容完全一致。
     */
    private suspend fun buildMigrationPackage(onStage: (stageIndex: Int, progress: Float) -> Unit = { _, _ -> }): String = withIOContext {
        val selectedDirs = _selectedKeys.value
        check(selectedDirs.isNotEmpty()) { "no selected" }
        val backupDir = context.localBackupSaveDir()

        // 预检：勾选的版本目录必须真实存在于本地备份目录。
        // 数据库可能残留云端恢复写入的记录（本地无对应目录）或已被手动删除的目录，
        // 直接打包会得到 tar 的模糊 "Cannot stat" 错误，这里提前给出明确提示。
        val missing = selectedDirs.filter { dir -> File("$backupDir/apps/$dir").exists().not() }
        check(missing.isEmpty()) {
            "以下备份目录不存在，无法打包（可能来自云端备份或已被删除）：\n" +
                missing.joinToString("\n") { "• ${it.substringBeforeLast('/')}" }
        }

        // 迁移包文件名用可读的北京时间（与本地导出 SAF 默认文件名一致），
        // 云端目录里一眼可分辨新旧；不用 epoch 毫秒（一串数字无法辨认时间）。
        val dstPath = "${context.filesDir}/DataBackup_migration_${DateUtil.formatTimestamp(DateUtil.getTimestamp(), "yyyyMMdd_HHmmss")}.tar.zst"

        // 阶段 1：开始打包（tar + zstd 流式压缩，耗时大头）
        onStage(stagePacking, 0f)
        // tar --totals -cpf - -C "<backupDir>" -- "apps/dir1" "apps/dir2" | zstd ... > "<dstPath>"
        val srcArgs = selectedDirs.map { SymbolUtil.shellQuote("apps/$it") }.toTypedArray()
        val shellResult = BaseUtil.execute(
            "tar", "--totals", "-cpf", "-",
            "-C", SymbolUtil.shellQuote(backupDir),
            "--", *srcArgs,
            "|", "zstd -r -T${context.readCompressionThreads().first()} -q",
            ">", SymbolUtil.shellQuote(dstPath),
        )
        check(shellResult.code == 0) { shellResult.out.joinToString("\n") }
        onStage(stagePacking, 1f)

        // 阶段 2：计算迁移包 SHA-256（流式，数 GB 文件不进内存），导出完成后展示给用户
        onStage(stageHashing, 0f)
        val digest = MessageDigest.getInstance("SHA-256")
        File(dstPath).inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        _lastSha256.value = digest.digest().joinToString("") { "%02x".format(it) }
        onStage(stageHashing, 1f)

        // 写入本机历史记录：时间 + 应用数 + 校验码，供导入页快速回查
        val appCount = _selectedKeys.value.map { it.substringBeforeLast('/') }.distinct().size
        MigrationShaHistoryStore.append(
            context,
            MigrationShaRecord(time = DateUtil.getTimestamp(), apps = appCount, sha = _lastSha256.value!!),
        )
        dstPath
    }

    /**
     * 重置阶段（用户继续操作时返回 Idle）。
     */
    fun consumeStage() {
        _stage.value = MigrationStage.Idle
    }

    fun consumeSuccess() {
        _success.value = false
    }
}
