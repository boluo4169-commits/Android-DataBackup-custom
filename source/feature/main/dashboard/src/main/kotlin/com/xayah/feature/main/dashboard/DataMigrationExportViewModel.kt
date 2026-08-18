package com.xayah.feature.main.dashboard

import android.content.Context
import android.net.Uri
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.repository.UsersRepo
import com.xayah.core.model.OpType
import com.xayah.core.model.SortType
import com.xayah.core.model.UserInfo
import com.xayah.core.util.DateUtil
import com.xayah.core.util.SymbolUtil
import com.xayah.core.util.command.BaseUtil
import com.xayah.core.util.localBackupSaveDir
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.io.File
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
) : androidx.lifecycle.ViewModel() {

    private val _allItems = MutableStateFlow<List<DataMigrationExportItem>>(emptyList())
    val allItems: StateFlow<List<DataMigrationExportItem>> = _allItems.asStateFlow()

    private val _userList = MutableStateFlow<List<UserInfo>>(emptyList())
    val userList: StateFlow<List<UserInfo>> = _userList.asStateFlow()

    private val _userMap = MutableStateFlow<Map<Int, Long>>(emptyMap())
    val userMap: StateFlow<Map<Int, Long>> = _userMap.asStateFlow()

    private val _userIndex = MutableStateFlow(0)
    val userIndex: StateFlow<Int> = _userIndex.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortIndex = MutableStateFlow(0)
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

    suspend fun load() {
        if (_isLoading.value) return
        _isLoading.value = true
        _error.value = null
        runCatching {
            // 扫描本地备份目录，确保列表最新（与恢复页刷新逻辑一致）
            appsRepo.load(null) { _, _, _ -> }
            val packages = packageRepository.queryPackages(OpType.RESTORE, false)
            val groups = packages.groupBy { it.archivesRelativeDir.substringBeforeLast('/') }
            val itemList = groups.map { (dir, entities) ->
                DataMigrationExportItem(
                    key = dir,
                    label = entities.first().packageInfo.label,
                    packageName = entities.first().packageName,
                    userId = entities.first().userId,
                    firstInstallTime = entities.first().packageInfo.firstInstallTime,
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
            2 -> compareBy { it.totalSizeBytes }
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
     */
    suspend fun export(uri: Uri): Boolean {
        _isExporting.value = true
        _error.value = null
        val result = runCatching {
            val selectedDirs = _selectedKeys.value
            check(selectedDirs.isNotEmpty()) { "no selected" }
            val backupDir = context.localBackupSaveDir()
            val tmpDir = context.filesDir
            val dstPath = "$tmpDir/DataBackup_migration_${DateUtil.getTimestamp()}.tar.zst"

            // tar --totals -cpf - -C "<backupDir>" -- "apps/dir1" "apps/dir2" | zstd ... > "<dstPath>"
            val srcArgs = selectedDirs.map { "${SymbolUtil.QUOTE}apps/$it${SymbolUtil.QUOTE}" }.toTypedArray()
            val shellResult = BaseUtil.execute(
                "tar", "--totals", "-cpf", "-",
                "-C", "${SymbolUtil.QUOTE}$backupDir${SymbolUtil.QUOTE}",
                "--", *srcArgs,
                "|", "zstd -r -T0 -q --priority=rt",
                ">", "${SymbolUtil.QUOTE}$dstPath${SymbolUtil.QUOTE}",
            )
            check(shellResult.code == 0) { shellResult.out.joinToString("\n") }

            context.contentResolver.openOutputStream(uri)?.use { out ->
                File(dstPath).inputStream().use { it.copyTo(out) }
            } ?: error("open output stream failed")
            File(dstPath).delete()
        }
        _isExporting.value = false
        if (result.isSuccess) {
            _success.value = true
            return true
        } else {
            _error.value = result.exceptionOrNull()?.message
            return false
        }
    }

    fun consumeSuccess() {
        _success.value = false
    }
}
