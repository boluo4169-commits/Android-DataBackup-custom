package com.xayah.core.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.UserHandle
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import com.xayah.core.data.R
import com.xayah.core.data.util.srcDir
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.datastore.di.DbDispatchers.Default
import com.xayah.core.datastore.di.Dispatcher
import com.xayah.core.datastore.readCloudPkgOnlyDir
import com.xayah.core.datastore.readCustomSUFile
import com.xayah.core.datastore.readLoadSystemApps
import com.xayah.core.datastore.readLoadedIconMD5
import com.xayah.core.datastore.saveLoadedIconMD5
import com.xayah.core.hiddenapi.castTo
import com.xayah.core.model.App
import com.xayah.core.model.CompressionType
import com.xayah.core.model.util.resolveArchive
import com.xayah.core.model.DataState
import com.xayah.core.model.DataType
import com.xayah.core.model.DefaultPreserveId
import com.xayah.core.model.OpType
import com.xayah.core.model.SettingsData
import com.xayah.core.model.UserInfo
import com.xayah.core.model.database.LabelAppCrossRefEntity
import com.xayah.core.model.database.preserveArchiveRelativeDir
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStatesEntity
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.PackageExtraInfo
import com.xayah.core.model.database.PackageIndexInfo
import com.xayah.core.model.database.PackageInfo
import com.xayah.core.model.database.PackageStorageStats
import com.xayah.core.model.database.PackageUpdateEntity
import com.xayah.core.model.database.asExternalModel
import com.xayah.core.rootservice.parcelables.PathParcelable
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.ConfigsPackageRestoreName
import com.xayah.core.util.DateUtil
import com.xayah.core.util.IconRelativeDir
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.BaseUtil
import com.xayah.core.util.command.PackageUtil
import com.xayah.core.util.command.Tar
import com.xayah.core.util.filesDir
import com.xayah.core.util.iconDir
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.util.withLog
import com.xayah.core.util.withMainContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

class AppsRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(Default) private val defaultDispatcher: CoroutineDispatcher,
    private val appsDao: PackageDao,
    private val packageRepo: PackageRepository,
    private val rootService: RemoteRootService,
    private val settingsDataRepo: SettingsDataRepo,
    private val pathUtil: PathUtil,
    private val cloudRepo: CloudRepository
) {
    fun getBackups(filters: Flow<Filters>): Flow<Set<String>> = combine(
        filters,
        appsDao.queryPackagesFlow(opType = OpType.RESTORE).flowOn(defaultDispatcher),
    ) { f, p ->
        p.filter { it.indexInfo.cloud == f.cloud && it.indexInfo.backupDir == f.backupDir }.map { it.pkgUserKey }.toSet()
    }

    fun getInstalledApps(users: Flow<List<UserInfo>>): Flow<Set<String>> = users.map { u ->
        val set = mutableSetOf<String>()
        u.forEach {
            set.addAll(rootService.getInstalledPackagesAsUser(0, it.id).map { p -> "${p.packageName}-${it.id}" }.toSet())
        }
        set
    }

    fun getApp(id: Long) = appsDao.queryPackageFlow(id).flowOn(defaultDispatcher)

    fun getApps(
        opType: OpType,
        listData: Flow<ListData>,
        pkgUserSet: Flow<Set<String>>,
        refs: Flow<List<LabelAppCrossRefEntity>>,
        labels: Flow<Set<String>>,
        cloudName: String,
        backupDir: String
    ): Flow<List<App>> = combine(
        listData,
        pkgUserSet,
        refs,
        labels,
        when (opType) {
            OpType.BACKUP -> appsDao.queryPackagesFlow(opType = opType, blocked = false)
            OpType.RESTORE -> appsDao.queryPackagesFlow(opType = opType, cloud = cloudName, backupDir = backupDir)
        }
    ) { lData, pSet, lRefs, lLabels, apps ->
        val data = lData.castTo<ListData.Apps>()
        apps.asSequence()
            .filter(packageRepo.getKeyPredicateNew(key = data.searchQuery))
            .filter(packageRepo.getShowSystemAppsPredicate(value = data.filters.showSystemApps))
            .filter(packageRepo.getHasBackupsPredicate(value = data.filters.hasBackups, pkgUserSet = pSet))
            .filter(packageRepo.getHasNoBackupsPredicate(value = data.filters.hasNoBackups, pkgUserSet = pSet))
            .filter(packageRepo.getInstalledPredicate(value = data.filters.installedApps, pkgUserSet = pSet))
            .filter(packageRepo.getNotInstalledPredicate(value = data.filters.notInstalledApps, pkgUserSet = pSet))
            .filter(packageRepo.getUserIdPredicateNew(userId = data.userList.getOrNull(data.userIndex)?.id))
            .filter { if (lLabels.isNotEmpty()) lRefs.find { ref -> it.packageName == ref.packageName && it.userId == ref.userId && it.preserveId == ref.preserveId } != null else true }
            .sortedBy { it.extraInfo.lastBackupTime }
            .sortedWith(packageRepo.getSortComparatorNew(sortIndex = data.sortIndex, sortType = data.sortType))
            .sortedByDescending { p -> p.extraInfo.activated }.toList()
            .let { list ->
                // 计算每个应用的保护版本序号（按 lastBackupTime 降序，最新被覆盖的 = 护盾 1）
                val indexMap = list.groupBy { it.packageName to it.userId }
                    .flatMap { (_, entities) ->
                        entities.filter { it.preserveId != 0L }
                            .sortedByDescending { it.extraInfo.lastBackupTime }
                            .mapIndexed { index, entity -> entity.id to (index + 1) }
                    }
                    .toMap()
                list.map { it.asExternalModel(preserveIndex = indexMap[it.id] ?: 0) }
            }
    }.flowOn(defaultDispatcher)

    /**
     * 顶部计数与列表同口径，跟随「加载系统应用」开关实时切换：
     * 开 = 系统应用 + 第三方全量；关 = 只计第三方（数据库可能残留开关开启期间入库的系统应用记录）。
     * 必须用 combine 持续订阅 datastore 开关 —— 若用 first() 只读一次，在过滤面板里翻转开关后
     * 计数分支不会重选，表现为「开了加载系统应用总数不变、勾选系统应用计数不动」。
     */
    fun countApps(opType: OpType): Flow<Long> = combine(
        context.readLoadSystemApps(),
        appsDao.countPackagesFlow(opType = opType, blocked = false),
        appsDao.countNonSystemPackagesFlow(opType = opType, blocked = false, systemFlag = ApplicationInfo.FLAG_SYSTEM),
    ) { loadSystemApps, all, nonSystem ->
        if (loadSystemApps) all else nonSystem
    }.flowOn(defaultDispatcher)

    fun countSelectedApps(opType: OpType): Flow<Long> = combine(
        context.readLoadSystemApps(),
        appsDao.countActivatedPackagesFlow(opType = opType, blocked = false),
        appsDao.countActivatedNonSystemPackagesFlow(opType = opType, blocked = false, systemFlag = ApplicationInfo.FLAG_SYSTEM),
    ) { loadSystemApps, all, nonSystem ->
        if (loadSystemApps) all else nonSystem
    }.flowOn(defaultDispatcher)

    suspend fun getLoadSystemApps() = context.readLoadSystemApps().first()

    suspend fun selectApp(id: Long, selected: Boolean) {
        appsDao.activateById(id, selected)
    }

    suspend fun selectDataItems(id: Long, apk: DataState, user: DataState, userDe: DataState, data: DataState, obb: DataState, media: DataState) {
        appsDao.selectDataItemsById(id, apk.name, user.name, userDe.name, data.name, obb.name, media.name)
    }

    suspend fun selectAll(ids: List<Long>) {
        appsDao.activateByIds(ids, true)
    }

    /**
     * 定时备份/一键备份用：全选备份列表（与备份页 UI 全选同集合语义）。
     *
     * 只选第三方应用：系统应用不属于用户数据，批量备份它们没有意义，而且设备上动辄
     * 近 400 个系统应用 —— 一旦被全选，引导页会显示几百个图标和数 GB 体积，
     * 备份规模和处理耗时都暴增（表现为扫描后长时间「加载」、界面响应迟钝）。
     * 「加载系统应用」开关只控制系统应用是否在列表中可见，不代表要备份它们。
     */
    suspend fun activateAllForBackup() {
        val apps = appsDao.queryPackages(OpType.BACKUP, blocked = false).filter {
            (it.packageInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0
        }
        appsDao.activateByIds(apps.map { it.id }, true)
    }

    suspend fun unselectAll(ids: List<Long>) {
        appsDao.activateByIds(ids, false)
    }

    suspend fun reverseAll(ids: List<Long>) {
        appsDao.reverseActivatedByIds(ids)
    }

    suspend fun blockSelected(ids: List<Long>) {
        appsDao.blockByIds(ids)
    }

    suspend fun blockByIds(ids: List<Long>) {
        appsDao.blockByIds(ids)
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        appsDao.setEnabled(id, enabled)
    }

    suspend fun deleteSelected(ids: List<Long>) {
        val appsDir = pathUtil.getLocalBackupAppsDir()
        // 云端路径必须按「云端仅用包名」开关解析（与详情页路径、备份/恢复服务同一规则），
        // 否则开关开启时硬编码 archivesRelativeDir 会指向一个不存在的目录，远端删不到、但本地记录被误删。
        val pkgOnly = context.readCloudPkgOnlyDir().first()
        val deletedIds = mutableListOf<Long>()
        ids.forEach {
            val app = appsDao.queryById(it)
            if (app != null) {
                val isSuccess = if (app.indexInfo.cloud.isEmpty()) {
                    val src = "${appsDir}/${app.archivesRelativeDir}"
                    rootService.deleteRecursively(src)
                } else {
                    // 先探测真实存在的目录再删：开关可能在备份之后翻转过，按当前开关（或硬编码
                    // archivesRelativeDir）都会指向不存在的路径。探测放在开客户端之前，避免嵌套云端连接。
                    val srcRel = resolveExistingArchiveRelativeDir(app) ?: app.resolveArchivesRelativeDir(pkgOnly)
                    runCatching {
                        cloudRepo.withClient(app.indexInfo.cloud) { client, entity ->
                            val remoteArchivesPackagesDir = pathUtil.getCloudRemoteAppsDir(entity.remote)
                            val src = "${remoteArchivesPackagesDir}/$srcRel"
                            // 路径不存在则视为失败并保留本地记录，避免出现「远端还在但本地记没了」的幽灵；
                            // 走 withLog() 之后会留日志，调用方可看到原因。
                            if (client.exists(src)) client.deleteRecursively(src) else error("cloud backup not found at $src")
                        }
                    }.withLog().isSuccess
                }
                if (isSuccess) deletedIds.add(app.id)
            }
        }
        appsDao.deleteByIds(deletedIds)
    }

    /**
     * 探测该实体在服务器/本地上**真实存在**的归档相对目录。
     *
     * 「云端目录仅用包名」开关可能在两次备份之间翻转（旧的走 legacy 纯包名目录、新的走带应用名的目录），
     * 因此只看"当前开关"会指向错误路径——比如备份时开关是开的（纯包名），后来关掉，
     * 按当前开关算出的却是带应用名的路径，与服务器上的实际目录对不上。
     *
     * 与恢复列表扫描（loadLocalApps）的双探测保持一致：先试 archivesRelativeDir（带应用名），
     * 再试 legacyArchivesRelativeDir（纯包名），返回真实存在的那个；都不存在则返回 null。
     */
    suspend fun resolveExistingArchiveRelativeDir(app: PackageEntity): String? {
        val candidates = listOf(app.archivesRelativeDir, app.legacyArchivesRelativeDir).distinct()
        if (app.indexInfo.cloud.isEmpty()) {
            val appsDir = pathUtil.getLocalBackupAppsDir()
            return candidates.firstOrNull { rootService.exists("$appsDir/$it") }
        }
        var found: String? = null
        runCatching {
            cloudRepo.withClient(app.indexInfo.cloud) { client, entity ->
                val remoteAppsDir = pathUtil.getCloudRemoteAppsDir(entity.remote)
                found = candidates.firstOrNull { client.exists("$remoteAppsDir/$it") }
            }
        }.withLog()
        return found
    }

    suspend fun setDataItems(ids: List<Long>, selections: PackageDataStates) {
        appsDao.updatePackageDataStates(ids.map { PackageDataStatesEntity(it, selections) })
    }

    /**
     * Initialize only newly installed apps or remove uninstalled apps.
     *
     * Faster than [fastInitialize] if there are too many newly installed apps.
     */
    suspend fun fullInitialize(onInit: suspend (cur: Int, max: Int, content: String) -> Unit) {
        val loadSystemApps = context.readLoadSystemApps().first()
        val settings = settingsDataRepo.settingsData.first()
        val pm = context.packageManager
        val userInfoList = rootService.getUsers()
        for (userInfo in userInfoList) {
            val userId = userInfo.id
            val installedPackages = getInstalledPackages(userId)
            val storedSet = appsDao.queryPkgSetByUserId(OpType.BACKUP, userId).toSet()

            // Remove uninstalled apps
            val outdatedPackages = storedSet.subtract(installedPackages.map { it.packageName }.toSet())
            appsDao.deleteByPkgNames(opType = OpType.BACKUP, userId = userId, packageNames = outdatedPackages)

            val apps = mutableListOf<PackageEntity>()
            installedPackages.forEachIndexed { index, info ->
                onInit(index, installedPackages.size, info.packageName)
                if (storedSet.contains(info.packageName).not()) {
                    val isSystemApp = ((info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0
                    if (loadSystemApps || isSystemApp.not()) {
                        apps.add(initializeApp(settings, pm, userId, info))
                    }
                }
            }
            appsDao.upsert(apps)
        }
        clearActivatedOfRemovedUsers(userInfoList.map { it.id })
        // 开关关着时，系统应用不应保留任何勾选（含其它用户空间里看不见的幽灵）
        if (loadSystemApps.not()) clearActivatedSystemApps()
    }

    /**
     * 清理「所属用户已被删除」的 BACKUP 记录勾选。
     * fullInitialize/fastInitialize 只遍历**现存**用户，被摧毁的多开空间（如炼妖壶/壶中界）下的记录
     * 永远不会被访问到，其 activated 会永久残留 —— 表现为：顶部计数多出看不见的已选项、
     * 引导页体积 0.00 Bytes、该幽灵条目被纳入备份却必然失败，且因失败而永远清不掉勾选。
     * userIds 为空 = 读取系统用户失败，必须跳过，否则会清掉全部勾选。
     */
    private suspend fun clearActivatedOfRemovedUsers(userIds: List<Int>) {
        if (userIds.isEmpty()) return
        appsDao.clearActivatedNotInUsers(opType = OpType.BACKUP, userIds = userIds)
    }

    /**
     * 清理系统应用的勾选（全部用户空间）。
     * 背景：一键备份的 activateAllForBackup 是全局全选，开关开着时会把双开空间（如 999）的系统应用
     * 一并勾上；而关开关、点「全部不选」都只作用于当前列表可见项，那些看不见的勾选就永久残留 ——
     * 表现为「明明只勾了 1 个应用，引导页却显示 2.16 GB、一堆系统应用图标」，且它们会被真实备份。
     */
    suspend fun clearActivatedSystemApps() = appsDao.clearActivatedSystemApps(
        opType = OpType.BACKUP,
        systemFlag = ApplicationInfo.FLAG_SYSTEM,
    )

    /**
     * Initialize only newly installed apps or remove uninstalled apps.
     */
    suspend fun fastInitialize(onInit: suspend (cur: Int, max: Int, content: String) -> Unit) {
        val loadSystemApps = context.readLoadSystemApps().first()
        val settings = settingsDataRepo.settingsData.first()
        val pm = context.packageManager
        val userInfoList = rootService.getUsers()
        for (userInfo in userInfoList) {
            val userId = userInfo.id
            val installedPackages = getInstalledPackages(userId).map { it.packageName }.toSet()
            val storedSet = appsDao.queryPkgSetByUserId(OpType.BACKUP, userId).toSet()

            // Remove uninstalled apps
            val outdatedPackages = storedSet.subtract(installedPackages)
            appsDao.deleteByPkgNames(opType = OpType.BACKUP, userId = userId, packageNames = outdatedPackages)

            // Add newly install apps
            val apps = mutableListOf<PackageEntity>()
            val missingPackages = installedPackages.subtract(storedSet)
            missingPackages.forEachIndexed { index, pkg ->
                onInit(index, missingPackages.size, pkg)
                val info = rootService.getPackageInfoAsUser(pkg, 0, userId)
                if (info != null) {
                    val isSystemApp = ((info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0
                    if (loadSystemApps || isSystemApp.not()) {
                        apps.add(initializeApp(settings, pm, userId, info))
                    }
                }
            }
            appsDao.upsert(apps)
        }
        clearActivatedOfRemovedUsers(userInfoList.map { it.id })
        // 开关关着时，系统应用不应保留任何勾选（含其它用户空间里看不见的幽灵）
        if (loadSystemApps.not()) clearActivatedSystemApps()
    }

    private fun initializeApp(settings: SettingsData, pm: PackageManager, userId: Int, info: android.content.pm.PackageInfo): PackageEntity {
        return PackageEntity(
            id = 0,
            indexInfo = PackageIndexInfo(
                opType = OpType.BACKUP,
                packageName = info.packageName,
                userId = userId,
                compressionType = settings.compressionType,
                preserveId = DefaultPreserveId,
                cloud = "",
                backupDir = "",
            ),
            packageInfo = PackageInfo(
                info.applicationInfo?.loadLabel(pm).toString(),
                versionName = info.versionName ?: "",
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    info.versionCode.toLong()
                },
                flags = info.applicationInfo?.flags ?: 0,
                firstInstallTime = info.firstInstallTime,
                lastUpdateTime = info.lastUpdateTime,
            ),
            extraInfo = PackageExtraInfo(
                uid = info.applicationInfo?.uid ?: -1,
                hasKeystore = false,
                permissions = listOf(),
                ssaid = "",
                lastBackupTime = 0L,
                blocked = false,
                activated = false,
                firstUpdated = false,
                enabled = true,
            ),
            dataStates = PackageDataStates(),
            storageStats = PackageStorageStats(),
            dataStats = PackageDataStats(),
            displayStats = PackageDataStats(),
        )
    }

    suspend fun fullUpdate(onUpdate: suspend (cur: Int, max: Int, content: String) -> Unit) {
        val pm = context.packageManager
        val userInfoList = rootService.getUsers()
        BaseUtil.mkdirs(context.iconDir())
        for (userInfo in userInfoList) {
            val userId = userInfo.id
            val userHandle = rootService.getUserHandle(userId)
            val apps = appsDao.queryPkgEntitiesByUserId(OpType.BACKUP, userId)
            val updateList = mutableListOf<PackageUpdateEntity>()

            apps.forEachIndexed { index, pkg ->
                onUpdate(index, apps.size, pkg.packageName)
                val updateEntity = updateApp(pm, pkg, userId, userHandle, queryStats = false, queryKeystore = false)
                if (updateEntity != null) {
                    updateList.add(updateEntity)
                }
            }
            appsDao.update(updateList)
        }
    }

    suspend fun fastUpdate(onUpdate: suspend (cur: Int, max: Int, content: String) -> Unit) {
        val pm = context.packageManager
        val apps = appsDao.queryFirstUpdatedApps(opType = OpType.BACKUP, firstUpdated = false)
        val updateList = mutableListOf<PackageUpdateEntity>()
        BaseUtil.mkdirs(context.iconDir())
        apps.forEachIndexed { index, pkg ->
            onUpdate(index, apps.size, pkg.packageName)
            val userId = pkg.userId
            val userHandle = rootService.getUserHandle(userId)
            val updateEntity = updateApp(pm, pkg, userId, userHandle, queryStats = false, queryKeystore = false)
            if (updateEntity != null) {
                updateList.add(updateEntity)
            }
        }
        appsDao.update(updateList)
    }

    suspend fun updateApp(pkg: PackageEntity, userId: Int, queryStats: Boolean = true) {
        val pm = context.packageManager
        val userHandle = rootService.getUserHandle(userId)
        val updateEntity = updateApp(pm, pkg, userId, userHandle, queryStats = queryStats)
        if (updateEntity != null) {
            appsDao.update(updateEntity)
        }
    }

    private suspend fun updateApp(pm: PackageManager, pkg: PackageEntity, userId: Int, userHandle: UserHandle?, queryStats: Boolean = true, queryKeystore: Boolean = true): PackageUpdateEntity? {
        val info = rootService.getPackageInfoAsUser(pkg.packageName, PackageManager.GET_PERMISSIONS, userId)
        val updateEntity = PackageUpdateEntity(pkg.id, pkg.packageInfo, pkg.extraInfo, pkg.storageStats)
        if (info != null) {
            runCatching {
                val iconPath: String
                val icon: Drawable?
                val iconDrawable = runCatching { context.packageManager.getApplicationIcon(pkg.packageName) }.getOrElse { AppCompatResources.getDrawable(context, android.R.drawable.sym_def_app_icon) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && iconDrawable is AdaptiveIconDrawable) {
                    iconPath = pathUtil.getPackageIconPath(info.packageName, true)
                    icon = LayerDrawable(arrayOf(iconDrawable.background, iconDrawable.foreground))
                } else {
                    iconPath = pathUtil.getPackageIconPath(info.packageName, false)
                    icon = iconDrawable
                }
                if (icon != null) {
                    BaseUtil.writeIcon(icon = icon, dst = iconPath)
                }
            }.withLog()

            updateEntity.packageInfo.label = info.applicationInfo?.loadLabel(pm).toString()
            updateEntity.packageInfo.versionName = info.versionName ?: ""
            updateEntity.packageInfo.versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
            updateEntity.packageInfo.flags = info.applicationInfo?.flags ?: 0
            updateEntity.packageInfo.firstInstallTime = info.firstInstallTime
            updateEntity.packageInfo.lastUpdateTime = info.lastUpdateTime

            updateEntity.extraInfo.firstUpdated = true
            val uid = info.applicationInfo?.uid ?: -1
            updateEntity.extraInfo.uid = uid
            updateEntity.extraInfo.permissions = rootService.getPermissions(packageInfo = info)
            if (queryKeystore) {
                updateEntity.extraInfo.hasKeystore = PackageUtil.hasKeystore(context.readCustomSUFile().first(), uid)
            }
            updateEntity.extraInfo.ssaid = rootService.getPackageSsaidAsUser(packageName = info.packageName, uid = uid, userId = userId)
            updateEntity.extraInfo.enabled = info.applicationInfo?.enabled ?: false

            if (queryStats && userHandle != null) {
                val stats = withTimeoutOrNull(5_000) {
                    rootService.queryStatsForPackage(info, userHandle)
                }
                if (stats != null) {
                    updateEntity.storageStats.appBytes = stats.appBytes
                    updateEntity.storageStats.cacheBytes = stats.cacheBytes
                    updateEntity.storageStats.dataBytes = stats.dataBytes
                    updateEntity.storageStats.externalCacheBytes = stats.externalCacheBytes
                }
            }
            return updateEntity
        } else {
            appsDao.delete(updateEntity.id)
            return null
        }
    }

    private suspend fun getInstalledPackages(userId: Int) = rootService.getInstalledPackagesAsUser(0, userId).filter {
        // Filter itself
        it.packageName != context.packageName
    }

    suspend fun load(cloudName: String?, onLoad: suspend (cur: Int, max: Int, content: String) -> Unit) {
        if (cloudName.isNullOrEmpty().not()) {
            cloudName?.apply {
                loadCloudIcons(this)
                loadCloudApps(this, onLoad)
            }
        } else {
            loadLocalIcons()
            loadLocalApps(onLoad)
        }
    }

    private suspend fun loadLocalIcons() {
        val archivePath = "${pathUtil.getLocalBackupConfigsDir()}/$IconRelativeDir.${CompressionType.TAR.suffix}"
        if (rootService.exists(archivePath)) {
            val loadedIconMD5 = context.readLoadedIconMD5().first()
            val iconMD5 = rootService.calculateMD5(archivePath) ?: ""
            if (loadedIconMD5 != iconMD5) {
                Tar.decompress(src = archivePath, dst = context.filesDir(), extra = CompressionType.TAR.decompressPara)
                PathUtil.setFilesDirSELinux(context)
                context.saveLoadedIconMD5(iconMD5)
            }
        }
    }

    private suspend fun loadCloudIcons(cloudName: String) = runCatching {
        cloudRepo.withClient(cloudName) { client, entity ->
            val archivePath = "${pathUtil.getCloudRemoteConfigsDir(entity.remote)}/$IconRelativeDir.${CompressionType.TAR.suffix}"
            if (client.exists(archivePath)) {
                val tmpDir = pathUtil.getCloudTmpDir()
                cloudRepo.download(client = client, src = archivePath, dstDir = tmpDir) { path ->
                    val loadedIconMD5 = context.readLoadedIconMD5().first()
                    val iconMD5 = rootService.calculateMD5(path) ?: ""
                    if (loadedIconMD5 != iconMD5) {
                        Tar.decompress(src = path, dst = context.filesDir(), extra = CompressionType.TAR.decompressPara)
                        PathUtil.setFilesDirSELinux(context)
                        context.saveLoadedIconMD5(iconMD5)
                    }
                }
            }
        }
    }.withLog()

    private fun parsePreserveAndUserId(pathParcelable: PathParcelable): Pair<Long, Int>? {
        runCatching {
            val userPath = pathParcelable.pathList[pathParcelable.pathList.size - 2]
            if (userPath.contains("@")) {
                val userIdWithPreserveId = userPath.split("@")
                val preserveId = userIdWithPreserveId.lastOrNull()?.toLongOrNull() ?: 0L
                val userId = userIdWithPreserveId.first().split("_").lastOrNull()?.toIntOrNull() ?: 0
                return preserveId to userId
            } else {
                // Main backup
                val preserveId = 0L
                val userId = userPath.split("_").lastOrNull()?.toIntOrNull() ?: 0
                return preserveId to userId
            }
        }
        return null
    }

    private suspend fun loadLocalApps(onLoad: suspend (cur: Int, max: Int, content: String) -> Unit) {
        val path = pathUtil.getLocalBackupAppsDir()
        val paths = rootService.walkFileTree(path)
        paths.forEachIndexed { index, pathParcelable ->
            val fileName = PathUtil.getFileName(pathParcelable.pathString)
            onLoad(index, paths.size, fileName)
            if (fileName == ConfigsPackageRestoreName) {
                runCatching {
                    var recovered = false
                    val dirName = pathParcelable.pathList.getOrNull(pathParcelable.pathList.size - 3)
                    rootService.readJson<PackageEntity>(pathParcelable.pathString).also { p ->
                        p?.id = 0
                        p?.extraInfo?.activated = false
                        p?.indexInfo?.cloud = ""
                        p?.indexInfo?.backupDir = context.localBackupSaveDir()
                        // 兼容被旧版扫描污染的历史 json：剥离修复（若命中），并删除数据库旧实体防重复
                        recovered = dirName != null && p?.recoverPackageNameFromDirName(dirName) == true
                        parsePreserveAndUserId(pathParcelable).also { result ->
                            result?.also { (pId, uId) ->
                                p?.indexInfo?.preserveId = pId
                                p?.indexInfo?.userId = uId
                            }
                        }
                        // 无论是否剥离，都按目录名查删旧实体（幂等）
                        if (p != null && dirName != null) {
                            val old = appsDao.query(
                                dirName, OpType.RESTORE,
                                p.indexInfo.userId, p.indexInfo.preserveId,
                                p.indexInfo.compressionType, p.indexInfo.cloud, p.indexInfo.backupDir
                            )
                            if (old != null) appsDao.delete(old.id)
                        }
                        // 剥离成功时写回 json 修复被污染的数据
                        if (recovered && p != null) {
                            rootService.writeJson(data = p, dst = pathParcelable.pathString)
                        }
                    }?.apply {
                        if (appsDao.query(packageName, indexInfo.opType, userId, preserveId, indexInfo.compressionType, indexInfo.cloud, indexInfo.backupDir) == null) {
                            appsDao.upsert(this)
                        }
                    }
                }
            }
        }
        rootService.clearEmptyDirectoriesRecursively(path)
        appsDao.queryPackages(OpType.RESTORE, "", context.localBackupSaveDir()).forEach {
            val src = "${path}/${it.archivesRelativeDir}"
            val legacySrc = "${path}/${it.legacyArchivesRelativeDir}"
            if (rootService.exists(src).not() && rootService.exists(legacySrc).not()) {
                appsDao.delete(it.id)
            }
        }
    }

    private suspend fun loadCloudApps(cloudName: String, onLoad: suspend (cur: Int, max: Int, content: String) -> Unit) = runCatching {
        cloudRepo.withClient(cloudName) { client, entity ->
            val remote = entity.remote
            val path = pathUtil.getCloudRemoteAppsDir(remote)
            if (client.exists(path)) {
                val paths = client.walkFileTree(path)
                val tmpDir = pathUtil.getCloudTmpDir()
                paths.forEachIndexed { index, pathParcelable ->
                    val fileName = PathUtil.getFileName(pathParcelable.pathString)
                    onLoad(index, paths.size, fileName)
                    if (fileName == ConfigsPackageRestoreName) {
                        runCatching {
                            val dirName = pathParcelable.pathList.getOrNull(pathParcelable.pathList.size - 3)
                            cloudRepo.download(client = client, src = pathParcelable.pathString, dstDir = tmpDir) { dlPath ->
                                val p = rootService.readJson<PackageEntity>(dlPath)
                                if (p != null) {
                                    p.id = 0
                                    p.extraInfo.activated = false
                                    p.indexInfo.cloud = entity.name
                                    p.indexInfo.backupDir = remote
                                    // 兼容被旧版扫描污染的历史 json：剥离修复（若命中），并删除数据库旧实体防重复
                                    if (dirName != null) {
                                        p.recoverPackageNameFromDirName(dirName)
                                    }
                                    parsePreserveAndUserId(pathParcelable)?.let { (pId, uId) ->
                                        p.indexInfo.preserveId = pId
                                        p.indexInfo.userId = uId
                                    }
                                    // 无论是否剥离，都按目录名查删旧实体（幂等）
                                    if (dirName != null) {
                                        val old = appsDao.query(
                                            dirName, OpType.RESTORE,
                                            p.indexInfo.userId, p.indexInfo.preserveId,
                                            p.indexInfo.compressionType, p.indexInfo.cloud, p.indexInfo.backupDir
                                        )
                                        if (old != null) appsDao.delete(old.id)
                                    }
                                    if (appsDao.query(p.packageName, p.indexInfo.opType, p.userId, p.preserveId, p.indexInfo.compressionType, p.indexInfo.cloud, p.indexInfo.backupDir) == null) {
                                        appsDao.upsert(p)
                                    }
                                }
                            }
                        }
                    }
                }
                appsDao.queryPackages(OpType.RESTORE, entity.name, entity.remote).forEach {
                    val src = "${path}/${it.archivesRelativeDir}"
                    // 「云端目录仅用包名」开关开启后，远端目录是 legacyArchivesRelativeDir（纯包名）；
                    // 清扫必须双探测，否则刚从 config 入库的实体会被误判不存在而立刻删除。
                    val legacySrc = "${path}/${it.legacyArchivesRelativeDir}"
                    if (client.exists(src).not() && client.exists(legacySrc).not()) {
                        appsDao.delete(it.id)
                    }
                }
            }
        }
    }.withLog()

    suspend fun calculateLocalAppSize(app: PackageEntity) {
        val dataTypes = listOf(
            DataType.PACKAGE_APK,
            DataType.PACKAGE_USER,
            DataType.PACKAGE_USER_DE,
            DataType.PACKAGE_DATA,
            DataType.PACKAGE_OBB,
            DataType.PACKAGE_MEDIA,
        )
        val sizes = coroutineScope {
            dataTypes.map { dt -> async { dt to calculateLocalAppDataSize(app, dt) } }.awaitAll()
        }
        sizes.forEach { (dt, size) ->
            when (dt) {
                DataType.PACKAGE_APK -> app.displayStats.apkBytes = size
                DataType.PACKAGE_USER -> app.displayStats.userBytes = size
                DataType.PACKAGE_USER_DE -> app.displayStats.userDeBytes = size
                DataType.PACKAGE_DATA -> app.displayStats.dataBytes = size
                DataType.PACKAGE_OBB -> app.displayStats.obbBytes = size
                DataType.PACKAGE_MEDIA -> app.displayStats.mediaBytes = size
                else -> {}
            }
        }
        appsDao.upsert(app)
    }

    private suspend fun calculateLocalAppDataSize(p: PackageEntity, dataType: DataType): Long {
        val src = getLocalAppDataSrcDir(p, dataType)
        return if (rootService.exists(src)) {
            // 源目录可能含海量小文件（微信 user/data 实测 ftw 遍历需数十秒），5s 超时必被掐断
            // → 详情页显示的大小错误（0/残缺值）。统一放宽到 60s 兜底；计算完立即返回，超时仅为最坏情况上限。
            withTimeoutOrNull(60_000) { rootService.calculateSize(src) } ?: 0
        } else {
            0
        }
    }

    private fun getDataSrcDir(dataType: DataType, userId: Int) = dataType.srcDir(userId)

    private fun getDataSrc(srcDir: String, packageName: String) = "$srcDir/$packageName"

    private suspend fun getPackageSourceDir(packageName: String, userId: Int) = rootService.getPackageSourceDir(packageName, userId).let { list ->
        if (list.isNotEmpty()) PathUtil.getParentPath(list[0]) else ""
    }

    private suspend fun getLocalAppDataSrcDir(p: PackageEntity, dataType: DataType) =
        if (dataType == DataType.PACKAGE_APK) getPackageSourceDir(packageName = p.packageName, userId = p.userId) else getDataSrc(srcDir = getDataSrcDir(dataType = dataType, userId = p.userId), packageName = p.packageName)

    /**
     * @author <a href="https://github.com/MuntashirAkon">@MuntashirAkon</a>
     */
    suspend fun launchApp(packageName: String, userId: Int) {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val user = rootService.getUserHandle(userId)
        if (launcherApps.isPackageEnabled(packageName, user).not()) {
            // Package not enabled
            withMainContext {
                Toast.makeText(context, context.getString(R.string.app_is_frozen), Toast.LENGTH_SHORT).show()
            }
            return
        }
        val activityInfoList = launcherApps.getActivityList(packageName, user)
        if (activityInfoList.isEmpty()) {
            // No activities
            withMainContext {
                Toast.makeText(context, context.getString(R.string.no_activities_found), Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Return the first openable activity
        val info = activityInfoList[0]
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            .setComponent(info.componentName)

        context.startActivity(intent)
    }

    private fun getArchiveSrc(dstDir: String, dataType: DataType, ct: CompressionType) = "${dstDir}/${dataType.type}.${ct.suffix}"

    private suspend fun calculateArchiveDataSize(p: PackageEntity, dataType: DataType): Long = withTimeoutOrNull(5_000) {
        val dir = "${pathUtil.getLocalBackupAppsDir()}/${p.archivesRelativeDir}"
        // 归档实际类型可能与记录不一致（历史版本 user 数据曾强制 TAR 不压缩），按记录类型优先、其余类型兜底探测。
        val (_, src) = CompressionType.resolveArchive(
            expected = p.indexInfo.compressionType,
            pathOf = { getArchiveSrc(dir, dataType, it) },
            exists = { rootService.exists(it) },
        )
        rootService.calculateSize(src)
    } ?: 0

    suspend fun calculateLocalAppArchiveSize(app: PackageEntity) {
        // 云端备份的归档目录在远程 WebDAV，本地路径不存在，本地计算会得到 0 并覆盖 displayStats。
        // 云端实体跳过计算，保留备份时写入的 displayStats。
        if (app.indexInfo.cloud.isNotEmpty()) return
        val dataTypes = listOf(
            DataType.PACKAGE_APK,
            DataType.PACKAGE_USER,
            DataType.PACKAGE_USER_DE,
            DataType.PACKAGE_DATA,
            DataType.PACKAGE_OBB,
            DataType.PACKAGE_MEDIA,
        )
        val sizes = coroutineScope {
            dataTypes.map { dt -> async { dt to calculateArchiveDataSize(app, dt) } }.awaitAll()
        }
        // 兜底：归档目录/文件路径不存在（备份被移动/压缩类型不匹配等）时全部算出 0，
        // 此时保留备份时写入的 displayStats，避免详情页"闪一下归零"。
        val newTotal = sizes.sumOf { it.second }
        val oldTotal = app.displayStats.apkBytes + app.displayStats.userBytes + app.displayStats.userDeBytes +
            app.displayStats.dataBytes + app.displayStats.obbBytes + app.displayStats.mediaBytes
        if (newTotal > 0 || oldTotal <= 0) {
            sizes.forEach { (dt, size) ->
                when (dt) {
                    DataType.PACKAGE_APK -> app.displayStats.apkBytes = size
                    DataType.PACKAGE_USER -> app.displayStats.userBytes = size
                    DataType.PACKAGE_USER_DE -> app.displayStats.userDeBytes = size
                    DataType.PACKAGE_DATA -> app.displayStats.dataBytes = size
                    DataType.PACKAGE_OBB -> app.displayStats.obbBytes = size
                    DataType.PACKAGE_MEDIA -> app.displayStats.mediaBytes = size
                    else -> {}
                }
            }
            appsDao.upsert(app)
        }
    }

    private suspend fun resolveLocalArchiveDir(app: PackageEntity, appsDir: String): String {
        val newDir = "${appsDir}/${app.archivesRelativeDir}"
        return if (rootService.exists(newDir)) newDir else "${appsDir}/${app.legacyArchivesRelativeDir}"
    }

    suspend fun protectApp(cloudName: String?, app: PackageEntity) {
        if (cloudName.isNullOrEmpty().not()) {
            cloudName?.apply {
                protectCloudApp(this, app)
            }
        } else {
            protectLocalApp(app)
        }
    }

    private suspend fun protectLocalApp(app: PackageEntity) {
        val preserveId = DateUtil.getTimestamp()
        val protectedApp = app.copy(indexInfo = app.indexInfo.copy(preserveId = preserveId))
        val appsDir = pathUtil.getLocalBackupAppsDir()
        val src = resolveLocalArchiveDir(app, appsDir)
        val dst = "${appsDir}/${preserveArchiveRelativeDir(src.removePrefix("$appsDir/"), preserveId)}"
        rootService.writeJson(data = protectedApp, dst = PathUtil.getPackageRestoreConfigDst(src))
        rootService.renameTo(src, dst)
        appsDao.update(protectedApp)
    }

    private suspend fun protectCloudApp(cloudName: String, app: PackageEntity) = runCatching {
        cloudRepo.withClient(cloudName) { client, entity ->
            val preserveId = DateUtil.getTimestamp()
            val protectedApp = app.copy(indexInfo = app.indexInfo.copy(preserveId = preserveId))
            val remote = entity.remote
            val remoteAppsDir = pathUtil.getCloudRemoteAppsDir(remote)
            // 探测真实存在的目录：开关可能在两次备份间翻转，旧备份会落在 legacy（纯包名）目录下，
            // 只看 archivesRelativeDir 会找不到源目录（静默失败）。
            val srcRel = resolveExistingArchiveRelativeDir(app) ?: app.archivesRelativeDir
            val src = "$remoteAppsDir/$srcRel"
            val dst = "$remoteAppsDir/${preserveArchiveRelativeDir(srcRel, preserveId)}"
            val tmpDir = pathUtil.getCloudTmpDir()
            val tmpJsonPath = PathUtil.getPackageRestoreConfigDst(tmpDir)
            rootService.writeJson(data = protectedApp, dst = tmpJsonPath)
            cloudRepo.upload(client = client, src = tmpJsonPath, dstDir = src)
            rootService.deleteRecursively(tmpDir)
            client.renameTo(src, dst)
            // 修复：远程场景缺数据库更新，导致受保护标记（preserveId）不生效
            appsDao.update(protectedApp)
        }
    }.withLog()

    /**
     * 取消保护：把保护版本（`user_X@时间戳`）改回正常版本（`user_X`），并把 preserveId 清零。
     *
     * 两条约束：
     * 1. 只改最后一段（剥掉 @时间戳），**不换父目录**——跨父目录 rename 在 FTP 上会崩。
     * 2. 目标目录已存在（说明已有一个正常版本）时**拒绝并返回 false**，绝不覆盖、不丢数据。
     */
    suspend fun unprotectApp(cloudName: String?, app: PackageEntity): Boolean {
        if (app.indexInfo.preserveId == DefaultPreserveId) return false
        val srcRel = resolveExistingArchiveRelativeDir(app) ?: return false
        if (!srcRel.contains('@')) return false // 本来就是正常版本
        val dstRel = srcRel.substringBefore('@')
        val unprotected = app.copy(indexInfo = app.indexInfo.copy(preserveId = DefaultPreserveId))

        if (cloudName.isNullOrEmpty()) {
            val appsDir = pathUtil.getLocalBackupAppsDir()
            val src = "$appsDir/$srcRel"
            val dst = "$appsDir/$dstRel"
            if (rootService.exists(dst)) {
                LogUtil.log { "AppsRepo" to "unprotect: target already exists, refusing: $dst" }
                return false
            }
            return runCatching {
                rootService.writeJson(data = unprotected, dst = PathUtil.getPackageRestoreConfigDst(src))
                rootService.renameTo(src, dst)
                appsDao.update(unprotected)
                true
            }.withLog().getOrDefault(false)
        }

        var ok = false
        runCatching {
            cloudRepo.withClient(cloudName) { client, entity ->
                val remoteAppsDir = pathUtil.getCloudRemoteAppsDir(entity.remote)
                val src = "$remoteAppsDir/$srcRel"
                val dst = "$remoteAppsDir/$dstRel"
                if (client.exists(dst)) {
                    LogUtil.log { "AppsRepo" to "unprotect: target already exists, refusing: $dst" }
                    return@withClient
                }
                val tmpDir = pathUtil.getCloudTmpDir()
                val tmpJsonPath = PathUtil.getPackageRestoreConfigDst(tmpDir)
                rootService.writeJson(data = unprotected, dst = tmpJsonPath)
                cloudRepo.upload(client = client, src = tmpJsonPath, dstDir = src)
                rootService.deleteRecursively(tmpDir)
                client.renameTo(src, dst)
                appsDao.update(unprotected)
                ok = true
            }
        }.withLog()
        return ok
    }

    suspend fun deleteApp(cloudName: String?, app: PackageEntity) {
        if (cloudName.isNullOrEmpty().not()) {
            cloudName?.apply {
                deleteCloudApp(this, app)
            }
        } else {
            deleteLocalApp(app)
        }
    }

    private suspend fun deleteLocalApp(app: PackageEntity) {
        val appsDir = pathUtil.getLocalBackupAppsDir()
        val src = resolveLocalArchiveDir(app, appsDir)
        if (rootService.deleteRecursively(src)) {
            appsDao.delete(app.id)
        }
    }

    private suspend fun deleteCloudApp(cloudName: String, app: PackageEntity) = runCatching {
        // 探测真实存在的目录：开关可能在备份之后翻转过，只看 archivesRelativeDir 会指向
        // 不存在的路径，导致纯包名目录下的备份删不掉（静默失败，记录还在、又冒出来）。
        val srcRel = resolveExistingArchiveRelativeDir(app)
        if (srcRel == null) {
            LogUtil.log { "AppsRepo" to "deleteCloudApp: archive dir not found for ${app.packageName}" }
            return@runCatching
        }
        cloudRepo.withClient(cloudName) { client, entity ->
            val remoteAppsDir = pathUtil.getCloudRemoteAppsDir(entity.remote)
            val src = "$remoteAppsDir/$srcRel"
            if (client.exists(src)) {
                client.deleteRecursively(src)
                if (client.exists(src).not()) {
                    appsDao.delete(app.id)
                }
            }
        }
    }.withLog()
}
