package com.xayah.core.data.repository

import android.content.Context
import com.xayah.core.datastore.readDefaultBackupAll
import com.xayah.core.datastore.saveDefaultBackupAll
import com.xayah.core.model.App
import com.xayah.core.model.File
import com.xayah.core.model.OpType
import com.xayah.core.model.SortType
import com.xayah.core.model.Target
import com.xayah.core.model.UserInfo
import com.xayah.core.model.database.LabelAppCrossRefEntity
import com.xayah.core.model.database.LabelFileCrossRefEntity
import com.xayah.core.util.module.combine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ListDataRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usersRepo: UsersRepo,
    private val appsRepo: AppsRepo,
    private val filesRepo: FilesRepo,
    private val labelsRepo: LabelsRepo,
    private val workRepo: WorkRepo,
) {
    private lateinit var listData: Flow<ListData>

    private lateinit var selected: Flow<Long>
    private lateinit var total: Flow<Long>
    private lateinit var searchQuery: MutableStateFlow<String>
    private lateinit var showFilterSheet: MutableStateFlow<Boolean>
    private lateinit var sortIndex: MutableStateFlow<Int>
    private lateinit var sortType: MutableStateFlow<SortType>

    /** 对外只读视图,供 ViewModel 读取当前 sortType（setSortIndex 触发"smart default"时用） */
    val sortTypeFlow: StateFlow<SortType> get() = sortType
    val sortIndexFlow: StateFlow<Int> get() = sortIndex

    /**
     * "默认备份全部"开关（datastore 持久化，APK 重启后保留）。
     * 打开后：进入备份页自动全选 / 文件备份页自动全选 / 定时备份对话框默认范围 = 全部。
     * 关闭后：不动用户已选（避免误操作清空）。
     * 初始值通过 runBlocking 从 datastore 同步读(构造器一次性调用,App 启动主线程短暂阻塞可接受)。
     */
    private val _defaultBackupAll: MutableStateFlow<Boolean> =
        MutableStateFlow(runBlocking { context.readDefaultBackupAll().first() })
    val defaultBackupAll: StateFlow<Boolean> = _defaultBackupAll.asStateFlow()

    suspend fun setDefaultBackupAll(value: Boolean) {
        _defaultBackupAll.value = value
        context.saveDefaultBackupAll(value)
    }

    /**
     * 如果"默认全量备份"开关打开,自动 selectAll 所有应用(备份)或所有文件(备份)。
     * 只在 opType == BACKUP 时执行;RESTORE 不动(用户主动选应用)。
     * 实现:等 appList / fileList 第一次 emit(等 DB 初始化)拿到全量 id,然后 selectAll。
     * 加 5s 超时兜底防 Flow 永远不 emit 的异常情况;开关关闭时直接 return,不动用户已选。
     */
    suspend fun autoSelectAllIfEnabled(opType: OpType, target: Target) {
        if (!_defaultBackupAll.value) return
        if (opType != OpType.BACKUP) return
        withTimeoutOrNull(5_000) {
            when (target) {
                Target.Apps -> {
                    val first = appList.first()
                    val ids = first.map { it.id }
                    if (ids.isNotEmpty()) appsRepo.selectAll(ids)
                }
                Target.Files -> {
                    val first = fileList.first()
                    val ids = first.map { it.id }
                    if (ids.isNotEmpty()) filesRepo.selectAll(ids)
                }
            }
        }
    }
    private lateinit var isUpdating: Flow<Boolean>
    private lateinit var labels: MutableStateFlow<Set<String>>

    // Apps
    private lateinit var showDataItemsSheet: MutableStateFlow<Boolean>
    private lateinit var filters: MutableStateFlow<Filters>
    private lateinit var userIndex: MutableStateFlow<Int>
    private lateinit var userList: Flow<List<UserInfo>>
    private lateinit var userMap: Flow<Map<Int, Long>>
    private lateinit var appList: Flow<List<App>>
    private lateinit var pkgUserSet: Flow<Set<String>> // "${pkgName}-${userId}"
    private lateinit var labelAppRefs: Flow<List<LabelAppCrossRefEntity>> // Labels filtered app refs

    // Files
    private lateinit var fileList: Flow<List<File>>
    private lateinit var labelFileRefs: Flow<List<LabelFileCrossRefEntity>> // Labels filtered file refs

    /** 当前已初始化的列表配置，用于判断是否需要重建状态流（见 [initialize]） */
    private var currentTarget: Target? = null
    private var currentOpType: OpType? = null
    private var currentCloudName: String? = null
    private var currentBackupDir: String? = null

    /**
     * 只重置瞬时 UI 状态（搜索词、弹层、排序、筛选、用户页签），保留状态流实例本身。
     * 用 tryEmit：这些是 MutableStateFlow 且总有订阅者未订阅时也能写入最新值。
     */
    private fun resetTransientState(target: Target) {
        if (::searchQuery.isInitialized) searchQuery.tryEmit("")
        if (::showFilterSheet.isInitialized) showFilterSheet.tryEmit(false)
        if (::labels.isInitialized) labels.tryEmit(setOf())
        if (::sortIndex.isInitialized) sortIndex.tryEmit(0)
        if (::sortType.isInitialized) sortType.tryEmit(SortType.ASCENDING)
        if (target == Target.Apps) {
            if (::showDataItemsSheet.isInitialized) showDataItemsSheet.tryEmit(false)
            if (::filters.isInitialized) {
                filters.tryEmit(
                    filters.value.copy(showSystemApps = runBlocking { appsRepo.getLoadSystemApps() })
                )
            }
            if (::userIndex.isInitialized) userIndex.tryEmit(0)
        }
    }

    fun initialize(target: Target, opType: OpType, cloudName: String, backupDir: String) {
        // 同一份配置重复初始化时**不要重建状态流**。
        // 返回栈里可能同时存在两个列表页实例（主页 → 备份应用 → 引导页 → 再点「应用」进入列表），
        // 先前的那个实例没有销毁，仍持有这批 lateinit flow 的旧引用。一旦在这里重新赋值，
        // 旧实例就永久失联：界面照常显示最后一次组合的值，但所有依赖共享状态的交互全部失效
        // ——筛选面板打不开、切换用户空间没反应；而纯本地状态的菜单（勾选清单/更多）照常可用，
        // 看起来就像"只有某几个按钮坏了"。这里改为重置瞬时 UI 状态，保持 flow 实例不变。
        if (::listData.isInitialized && currentTarget == target && currentOpType == opType &&
            currentCloudName == cloudName && currentBackupDir == backupDir
        ) {
            resetTransientState(target)
            return
        }
        currentTarget = target
        currentOpType = opType
        currentCloudName = cloudName
        currentBackupDir = backupDir
        when (target) {
            Target.Apps -> {
                selected = appsRepo.countSelectedApps(opType)
                total = appsRepo.countApps(opType)
                searchQuery = MutableStateFlow("")
                showFilterSheet = MutableStateFlow(false)
                sortIndex = MutableStateFlow(0)
                sortType = MutableStateFlow(SortType.ASCENDING)
                isUpdating = when (opType) {
                    OpType.BACKUP -> combine(workRepo.isFullInitRunning(), workRepo.isFullInitAndUpdateAppsRunning(), workRepo.isFastInitAndUpdateAppsRunning()) { fInit, full, fast -> fInit || full || fast }
                    OpType.RESTORE -> combine(workRepo.isFullInitRunning(), workRepo.isLoadAppBackupsRunning()) { fInit, lAppBackups -> fInit || lAppBackups }
                }
                labels = MutableStateFlow(setOf())
                labelAppRefs = labels.map {
                    labelsRepo.getAppRefs(it)
                }

                showDataItemsSheet = MutableStateFlow(false)
                filters = MutableStateFlow(
                    Filters(
                        cloud = cloudName,
                        backupDir = backupDir,
                        showSystemApps = runBlocking { appsRepo.getLoadSystemApps() },
                        hasBackups = true,
                        hasNoBackups = true,
                        installedApps = true,
                        notInstalledApps = true,
                    )
                )
                userIndex = MutableStateFlow(0)
                userList = usersRepo.getUsers(opType)
                userMap = usersRepo.getUsersMap(opType, cloudName, backupDir)

                listData = getAppListData()
                pkgUserSet = when (opType) {
                    OpType.BACKUP -> {
                        appsRepo.getBackups(filters)
                    }

                    OpType.RESTORE -> {
                        appsRepo.getInstalledApps(userList)
                    }
                }
                appList = appsRepo.getApps(opType = opType, listData = listData, pkgUserSet = pkgUserSet, refs = labelAppRefs, labels = labels, cloudName = cloudName, backupDir = backupDir)
            }

            Target.Files -> {
                selected = filesRepo.countSelectedFiles(opType)
                total = filesRepo.countFiles(opType)
                searchQuery = MutableStateFlow("")
                showFilterSheet = MutableStateFlow(false)
                sortIndex = MutableStateFlow(0)
                sortType = MutableStateFlow(SortType.ASCENDING)
                isUpdating = when (opType) {
                    OpType.BACKUP -> combine(workRepo.isFullInitRunning(), workRepo.isFastInitAndUpdateFilesRunning()) { fInit, fast -> fInit || fast }
                    OpType.RESTORE -> combine(workRepo.isFullInitRunning(), workRepo.isLoadFileBackupsRunning()) { fInit, lFileBackups -> fInit || lFileBackups }
                }
                labels = MutableStateFlow(setOf())
                labelFileRefs = labels.map {
                    labelsRepo.getFileRefs(it)
                }

                listData = getFileListData()
                fileList = filesRepo.getFiles(opType = opType, listData = listData, refs = labelFileRefs, labels = labels, cloudName = cloudName, backupDir = backupDir)
            }
        }
    }

    private fun getAppListData(): Flow<ListData.Apps> = combine(
        selected,
        total,
        searchQuery,
        showFilterSheet,
        sortIndex,
        sortType,
        isUpdating,
        labels,
        showDataItemsSheet,
        filters,
        userIndex,
        userList,
        userMap,
    ) { s, t, sQuery, sFSheet, sIndex, sType, iUpdating, lIds, sDISheet, filters, uIndex, uList, uMap ->
        // 治本：删除操作可能使 userList 缩短（如删掉双开空间下唯一的备份应用），而 userIndex
        // 不会自动校正，越界值传到 UI 会导致 TabRow 测量时 IndexOutOfBoundsException。
        // 在数据层出口统一钳制，保证下游永远拿到合法索引。
        val safeUserIndex = if (uList.isEmpty()) 0 else uIndex.coerceIn(0, uList.size - 1)
        ListData.Apps(s, t, sQuery, sFSheet, sIndex, sType, iUpdating, lIds, sDISheet, filters, safeUserIndex, uList, uMap)
    }

    private fun getFileListData(): Flow<ListData.Files> = combine(
        selected,
        total,
        searchQuery,
        showFilterSheet,
        sortIndex,
        sortType,
        isUpdating,
        labels,
    ) { s, t, sQuery, sFSheet, sIndex, sType, iUpdating, lIds ->
        ListData.Files(s, t, sQuery, sFSheet, sIndex, sType, iUpdating, lIds)
    }

    fun getListData(): Flow<ListData> = listData

    fun getAppList(): Flow<List<App>> = appList

    fun getFileList(): Flow<List<File>> = fileList

    suspend fun setFilters(block: (Filters) -> Filters) {
        filters.emit(block(filters.value))
    }

    suspend fun setSortIndex(block: (Int) -> Int) {
        sortIndex.emit(block(sortIndex.value))
    }

    suspend fun setSortType(block: (SortType) -> SortType) {
        sortType.emit(block(sortType.value))
    }

    suspend fun setSearchQuery(value: String) {
        searchQuery.emit(value)
    }

    suspend fun setUserIndex(value: Int) {
        userIndex.emit(value)
    }

    suspend fun setShowFilterSheet(value: Boolean) {
        showFilterSheet.emit(value)
    }

    suspend fun setShowDataItemsSheet(value: Boolean) {
        showDataItemsSheet.emit(value)
    }

    suspend fun addLabel(label: String) {
        val ids = labels.value.toMutableSet()
        ids.add(label)
        labels.emit(ids)
    }

    suspend fun removeLabel(label: String) {
        val ids = labels.value.toMutableSet()
        ids.remove(label)
        labels.emit(ids)
    }
}

data class Filters(
    val cloud: String,
    val backupDir: String,
    val showSystemApps: Boolean,
    val hasBackups: Boolean,
    val hasNoBackups: Boolean,
    val installedApps: Boolean,
    val notInstalledApps: Boolean,
)

sealed class ListData(
    open val selected: Long,
    open val total: Long,
    open val searchQuery: String,
    open val showFilterSheet: Boolean,
    open val sortIndex: Int,
    open val sortType: SortType,
    open val isUpdating: Boolean,
    open val labels: Set<String>,
) {
    data class Apps(
        override val selected: Long,
        override val total: Long,
        override val searchQuery: String,
        override val showFilterSheet: Boolean,
        override val sortIndex: Int,
        override val sortType: SortType,
        override val isUpdating: Boolean,
        override val labels: Set<String>,
        val showDataItemsSheet: Boolean,
        val filters: Filters,
        val userIndex: Int,
        val userList: List<UserInfo>,
        val userMap: Map<Int, Long>,
    ) : ListData(selected, total, searchQuery, showFilterSheet, sortIndex, sortType, isUpdating, labels)

    data class Files(
        override val selected: Long,
        override val total: Long,
        override val searchQuery: String,
        override val showFilterSheet: Boolean,
        override val sortIndex: Int,
        override val sortType: SortType,
        override val isUpdating: Boolean,
        override val labels: Set<String>,
    ) : ListData(selected, total, searchQuery, showFilterSheet, sortIndex, sortType, isUpdating, labels)
}
