package com.xayah.core.service.util

import android.content.Context
import android.content.pm.PackageManager
import com.xayah.core.common.util.toLineString
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.datastore.readCompressionLevel
import com.xayah.core.datastore.readCompressionThreads
import com.xayah.core.datastore.readFollowSymlinks
import com.xayah.core.datastore.readSelectionType
import com.xayah.core.model.CompressionType
import com.xayah.core.model.DataType
import com.xayah.core.model.OperationState
import com.xayah.core.model.SelectionType
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.util.getCompressPara
import com.xayah.core.network.client.CloudClient
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.IconRelativeDir
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.SymbolUtil
import com.xayah.core.util.TrashedFilePatterns
import com.xayah.core.util.command.Tar
import com.xayah.core.util.filesDir
import com.xayah.core.util.model.ShellResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import com.xayah.core.datastore.readCloudSplitSize
import com.xayah.core.model.util.cloudSplitSuggestion
import com.xayah.core.model.util.formatSize
import com.xayah.core.model.util.shouldSuggestCloudSplit
import com.xayah.core.model.util.CLOUD_SPLIT_HINT_MARK

/**
 * 一次应用备份的增量上下文：把「源侧判定结果 / 借用来源 / 是否参与增量」打包传给各类型备份。
 *
 * @param enabled false = 本次完全不参与增量（云端）：云端备份是「先写本地暂存 → 再上传」，
 *   若暂存目录因上次任务中断而残留，跳过判定会命中 → **不上传 → 远端主备份缺该类型**。
 *   云端一律全量，从根上杜绝远端缺件。
 * @param refDir preserve 归档后的目录（借用来源）；null = 无可借用
 * @param decisions 源侧一次性判定产物（[PackagesBackupUtil.evaluateIncremental]），每类型只 walk 一次
 */
data class IncrementalContext(
    val enabled: Boolean,
    val refDir: String?,
    val decisions: Map<DataType, IncrementalBackupUtil.SourceState>,
) {
    companion object {
        /** 未启用增量时的空上下文：逐类型一律全量重打。 */
        val DISABLED = IncrementalContext(enabled = false, refDir = null, decisions = emptyMap())
    }
}

class PackagesBackupUtil @Inject constructor(
    @ApplicationContext val context: Context,
    private val rootService: RemoteRootService,
    private val taskDao: TaskDao,
    private val packageRepository: PackageRepository,
    private val commonBackupUtil: CommonBackupUtil,
    private val cloudRepository: CloudRepository,
) {
    companion object {
        private const val TAG = "PackagesBackupUtil"

        /** 备份涉及的数据类型（顺序即执行顺序），各处遍历统一用它，避免列表漂移。 */
        private val ALL_DATA_TYPES = listOf(
            DataType.PACKAGE_APK, DataType.PACKAGE_USER, DataType.PACKAGE_USER_DE,
            DataType.PACKAGE_DATA, DataType.PACKAGE_OBB, DataType.PACKAGE_MEDIA,
        )
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

    private fun PackageEntity.getDataBytes(dataType: DataType) = when (dataType) {
        DataType.PACKAGE_APK -> dataStats.apkBytes
        DataType.PACKAGE_USER -> dataStats.userBytes
        DataType.PACKAGE_USER_DE -> dataStats.userDeBytes
        DataType.PACKAGE_DATA -> dataStats.dataBytes
        DataType.PACKAGE_OBB -> dataStats.obbBytes
        DataType.PACKAGE_MEDIA -> dataStats.mediaBytes
        else -> 0
    }

    private fun PackageEntity.setDataBytes(dataType: DataType, sizeBytes: Long) = when (dataType) {
        DataType.PACKAGE_APK -> dataStats.apkBytes = sizeBytes
        DataType.PACKAGE_USER -> dataStats.userBytes = sizeBytes
        DataType.PACKAGE_USER_DE -> dataStats.userDeBytes = sizeBytes
        DataType.PACKAGE_DATA -> dataStats.dataBytes = sizeBytes
        DataType.PACKAGE_OBB -> dataStats.obbBytes = sizeBytes
        DataType.PACKAGE_MEDIA -> dataStats.mediaBytes = sizeBytes
        else -> Unit
    }

    private fun PackageEntity.setDisplayBytes(dataType: DataType, sizeBytes: Long) = when (dataType) {
        DataType.PACKAGE_APK -> displayStats.apkBytes = sizeBytes
        DataType.PACKAGE_USER -> displayStats.userBytes = sizeBytes
        DataType.PACKAGE_USER_DE -> displayStats.userDeBytes = sizeBytes
        DataType.PACKAGE_DATA -> displayStats.dataBytes = sizeBytes
        DataType.PACKAGE_OBB -> displayStats.obbBytes = sizeBytes
        DataType.PACKAGE_MEDIA -> displayStats.mediaBytes = sizeBytes
        else -> Unit
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

    private val tarCt = CompressionType.TAR
    fun getIconsDst(dstDir: String) = "${dstDir}/$IconRelativeDir.${tarCt.suffix}"
    suspend fun backupIcons(dstDir: String): ShellResult = run {
        log { "Backing up icons..." }

        val dst = getIconsDst(dstDir = dstDir)
        var isSuccess: Boolean
        val out = mutableListOf<String>()

        Tar.compress(
            exclusionList = listOf(),
            h = "",
            srcDir = context.filesDir(),
            src = IconRelativeDir,
            dst = dst,
            extra = tarCt.getCompressPara(context.readCompressionLevel().first(), context.readCompressionThreads().first())
        ).also { result ->
            isSuccess = result.isSuccess
            out.addAll(result.out)
        }
        commonBackupUtil.testArchive(src = dst, ct = tarCt).also { result ->
            isSuccess = isSuccess && result.isSuccess
            out.addAll(result.out)
        }

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }

    private suspend fun getPackageSourceDir(packageName: String, userId: Int) = rootService.getPackageSourceDir(packageName, userId).let { list ->
        if (list.isNotEmpty()) PathUtil.getParentPath(list[0]) else ""
    }

    /**
     * 备份前的「源侧」一次性判定：对每个**已选中**类型算清单哈希与打包参数指纹。
     *
     * 一次 walk 搞定，产物供三处共用：① 是否归档旧主备份 ② 是否跳过重打 ③ 是否从保护版本借用。
     * 重复 walk 在微信级目录上是秒级开销，会直接加到每次备份时长里，因此必须只算一次。
     *
     * 无法判定（walk 失败、APK 路径取不到）→ 该项 hash = null，调用方按「已变化」走全量。
     */
    suspend fun evaluateIncremental(p: PackageEntity): Map<DataType, IncrementalBackupUtil.SourceState> {
        val packageName = p.packageName
        val userId = p.userId
        val ct = p.indexInfo.compressionType
        val level = context.readCompressionLevel().first()
        val threads = context.readCompressionThreads().first()
        val followSymlinks = context.readFollowSymlinks().first()
        val result = mutableMapOf<DataType, IncrementalBackupUtil.SourceState>()
        for (dataType in ALL_DATA_TYPES) {
            if (p.getDataSelected(dataType).not()) continue
            result[dataType] = when (dataType) {
                DataType.PACKAGE_APK -> {
                    val srcDir = getPackageSourceDir(packageName = packageName, userId = userId)
                    // APK 只打包顶层 *.apk：walk 也只走顶层（超集，安全方向；不受「跟随软链」设置影响）
                    val params = IncrementalBackupUtil.ArchiveParams(ct.name, level, threads, false, listOf())
                    IncrementalBackupUtil.SourceState(
                        hash = srcDir.takeIf { it.isNotEmpty() }?.let { IncrementalBackupUtil.computeSourceHash(it, listOf(), false, maxDepth = 1) },
                        params = params,
                    )
                }

                else -> {
                    val srcDir = packageRepository.getDataSrcDir(dataType, userId)
                    val src = packageRepository.getDataSrc(srcDir, packageName)
                    val (exclusionList, pruneDirs) = buildExclusionAndPrune(dataType, src, packageName)
                    val params = IncrementalBackupUtil.ArchiveParams(ct.name, level, threads, followSymlinks, exclusionList)
                    if (rootService.exists(src).not()) {
                        IncrementalBackupUtil.SourceState(hash = null, params = params, missing = true)
                    } else {
                        IncrementalBackupUtil.SourceState(
                            hash = IncrementalBackupUtil.computeSourceHash(srcAbs = src, pruneDirs = pruneDirs, followSymlinks = followSymlinks),
                            params = params,
                        )
                    }
                }
            }
        }
        return result
    }

    /**
     * 「全部选中类型均未变化」判定（preserve 归档守卫用）。
     *
     * 返回 true 时调用方**不得**归档旧主备份 —— 归档 rename 会把 manifest 随目录搬走，
     * 逐类型跳过判定会全部失效，每次备份都退化成全量重打（真机实测踩过）。
     */
    suspend fun allTypesUnchanged(
        p: PackageEntity,
        decisions: Map<DataType, IncrementalBackupUtil.SourceState>,
        mainDir: String,
    ): Boolean {
        for (dataType in ALL_DATA_TYPES) {
            if (p.getDataSelected(dataType).not()) continue
            val state = decisions[dataType] ?: return false
            val unchanged = when {
                // 源不存在：真实备份流程按「不存在」处理（不产出新归档），不算变化；
                // USER 源不存在属异常，交给原流程报错。
                state.missing -> dataType != DataType.PACKAGE_USER
                state.usable.not() -> false
                else -> {
                    val hash = state.hash ?: return false
                    IncrementalBackupUtil.isUnchanged(rootService, archiveDstIn(mainDir, dataType, p), state.params, hash)
                }
            }
            if (unchanged.not()) {
                log { "allTypesUnchanged: type=$dataType changed, archive main backup." }
                return false
            }
        }
        return true
    }

    /** 某数据类型的归档路径（单个来源，避免各处用不同 ct/目录算路径而错位）。 */
    fun archiveDstIn(dir: String, dataType: DataType, p: PackageEntity) =
        packageRepository.getArchiveDst(dstDir = dir, dataType = dataType, ct = p.indexInfo.compressionType)

    /**
     * 排除项（tar `--exclude`）与 walk 剪枝目录的**唯一来源**。
     *
     * 口径必须一致：剪枝只放常量目录名（与 `--exclude` 的目录项一一对应）；glob 类排除项
     * （`Backup_*`、`.trashed-*`）不进剪枝 —— walk 集合是 tar 打包集合的超集，
     * 多算只会让本次跳过失效，绝不会漏掉真实变化。
     */
    private fun buildExclusionAndPrune(dataType: DataType, src: String, packageName: String): Pair<List<String>, List<String>> {
        val exclusionList = mutableListOf<String>()
        val pruneDirs = mutableListOf<String>()
        when (dataType) {
            DataType.PACKAGE_USER, DataType.PACKAGE_USER_DE -> {
                val folders = listOf(".ota", "cache", "lib", "code_cache", "no_backup")
                exclusionList.addAll(folders.map { "$packageName/$it" })
                pruneDirs.addAll(folders.map { "$src/$it" })
            }

            DataType.PACKAGE_DATA, DataType.PACKAGE_OBB, DataType.PACKAGE_MEDIA -> {
                val folders = listOf("cache")
                exclusionList.addAll(folders.map { "$packageName/$it" })
                pruneDirs.addAll(folders.map { "$src/$it" })
                exclusionList.add("Backup_*")
                exclusionList.addAll(TrashedFilePatterns)
            }

            else -> {}
        }
        return exclusionList to pruneDirs
    }

    /**
     * 归档成功后写增量清单（**唯一入口**）——新增数据类型只要走 [finishArchive] 就自动获得清单，
     * 不会出现「加了类型忘了写 manifest → 该类型永远无法跳过且不报错」的静默退化。
     */
    private suspend fun writeManifest(dst: String, state: IncrementalBackupUtil.SourceState?, ctx: IncrementalContext) {
        if (ctx.enabled.not()) return
        val hash = state?.hash ?: return
        IncrementalBackupUtil.write(rootService, dst, hash, state.params)
    }

    suspend fun backupApk(p: PackageEntity, r: PackageEntity?, t: TaskDetailPackageEntity, dstDir: String, ctx: IncrementalContext = IncrementalContext.DISABLED): ShellResult = run {
        log { "Backing up apk..." }

        val dataType = DataType.PACKAGE_APK
        val packageName = p.packageName
        val userId = p.userId
        val ct = p.indexInfo.compressionType
        val dst = packageRepository.getArchiveDst(dstDir = dstDir, dataType = dataType, ct = ct)
        var isSuccess: Boolean
        val out = mutableListOf<String>()
        val srcDir = getPackageSourceDir(packageName = packageName, userId = userId)

        if (p.getDataSelected(dataType).not()) {
            isSuccess = true
            t.updateInfo(dataType = dataType, state = OperationState.SKIP)
        } else {
            if (srcDir.isNotEmpty()) {
                val level = context.readCompressionLevel().first()
                val threads = context.readCompressionThreads().first()
                // 源侧判定已在 evaluateIncremental 一次算好（APK 走 maxDepth=1 顶层 walk）。
                // ctx 未启用增量（云端）时 decisions 为空 → srcHash 恒 null → 一律全量，不写清单。
                val state = ctx.decisions[dataType]
                val params = state?.params ?: IncrementalBackupUtil.ArchiveParams(ct.name, level, threads, false, listOf())
                val srcHash = state?.hash?.takeIf { ctx.enabled }

                val sizeBytes = rootService.calculateSize(srcDir)
                t.updateInfo(dataType = dataType, state = OperationState.PROCESSING, bytes = sizeBytes)
                // 路径 1：主目录自身的归档 + manifest 未变化 → 跳过（preserve 关闭时的常规路径）
                val unchangedInPlace = srcHash != null && rootService.exists(dst) && IncrementalBackupUtil.isUnchanged(rootService, dst, params, srcHash)
                // 路径 2：preserve 已把旧主备份归档走 → 旧版未变化则从保护版本继承归档，不重打
                val refDst = ctx.refDir?.let { archiveDstIn(it, dataType, p) }
                val reused = unchangedInPlace.not() && srcHash != null && refDst != null && rootService.exists(refDst) &&
                    IncrementalBackupUtil.isUnchanged(rootService, refDst, params, srcHash) &&
                    IncrementalBackupUtil.tryReuse(rootService, refDst, dst)
                if (unchangedInPlace || reused) {
                    isSuccess = true
                    t.updateInfo(dataType = dataType, state = OperationState.SKIP)
                    out.add(log { if (reused) "Data has not changed. Reused archive from preserved version." else "Data has not changed." })
                } else {
                    Tar.compressInCur(cur = srcDir, src = "./*.apk", dst = dst, extra = ct.getCompressPara(level, threads))
                        .also { result ->
                            isSuccess = result.isSuccess
                            out.addAll(result.out)
                        }
                    commonBackupUtil.testArchive(src = dst, ct = ct).also { result ->
                        isSuccess = isSuccess && result.isSuccess
                        out.addAll(result.out)
                        if (result.isSuccess) {
                            p.setDataBytes(dataType, sizeBytes)
                            p.setDisplayBytes(dataType, rootService.calculateSize(dst))
                            ChecksumUtil.write(rootService = rootService, src = dst)?.let { md5 ->
                                out.add(log { "Checksum: $md5" })
                            }
                            // 增量清单随归档落盘（唯一入口）：下次备份据此判定「未变化」跳过 tar。
                            writeManifest(dst = dst, state = state, ctx = ctx)
                        }
                    }
                }
            } else {
                isSuccess = false
                out.add(log { "Failed to get apk path of $packageName." })
            }
            t.updateInfo(dataType = dataType, state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = out.toLineString())
        }

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }

    /**
     * Package data: USER, USER_DE, DATA, OBB, MEDIA
     */
    suspend fun backupData(p: PackageEntity, t: TaskDetailPackageEntity, r: PackageEntity?, dataType: DataType, dstDir: String, ctx: IncrementalContext = IncrementalContext.DISABLED): ShellResult = run {
        log { "Backing up ${dataType.type}..." }

        val packageName = p.packageName
        val userId = p.userId
        // 所有数据类型统一跟随全局压缩设置（user 曾强制 TAR 不压缩，已撤回）。
        val ct = p.indexInfo.compressionType
        val dst = packageRepository.getArchiveDst(dstDir = dstDir, dataType = dataType, ct = ct)
        var isSuccess = true
        val out = mutableListOf<String>()
        val srcDir = packageRepository.getDataSrcDir(dataType, userId)

        if (p.getDataSelected(dataType).not()) {
            isSuccess = true
            t.updateInfo(dataType = dataType, state = OperationState.SKIP)
        } else {
            // Check the existence of origin path.
            val src = packageRepository.getDataSrc(srcDir, packageName)
            if (rootService.exists(src).not()) {
                if (dataType == DataType.PACKAGE_USER) {
                    isSuccess = false
                    out.add(log { "Not exist: $src" })
                    t.updateInfo(dataType = dataType, state = OperationState.ERROR, log = out.toLineString())
                    return@run ShellResult(code = -1, input = listOf(), out = out)
                }
                // 源不存在（应用本就没这类数据，或用户已清掉）：**尽量保留上一份归档**。
                // 不保留的话，preserve 归档掉旧主备份后，新主备份会直接少一项（旧归档只剩在保护版本里），
                // 用户从「主版本」恢复就会缺东西。备份的职责是留住数据，所以这里保留。
                val kept = keepPreviousArchive(dst = dst, dataType = dataType, p = p, ctx = ctx)
                out.add(log { if (kept) "Source not exist, kept previous archive: $src" else "Not exist and skip: $src" })
                t.updateInfo(dataType = dataType, state = OperationState.SKIP, log = out.toLineString())
                return@run ShellResult(code = -2, input = listOf(), out = out)
            }

            // 排除项与剪枝目录（唯一来源，与 evaluateIncremental/allTypesUnchanged 口径一致）
            val (exclusionList, pruneDirs) = buildExclusionAndPrune(dataType, src, packageName)
            log { "ExclusionList: $exclusionList." }

            val level = context.readCompressionLevel().first()
            val threads = context.readCompressionThreads().first()
            val followSymlinks = context.readFollowSymlinks().first()
            // 源侧判定已在 evaluateIncremental 一次算好；ctx 未启用增量（云端）时为空 → 一律全量、不写清单
            val state = ctx.decisions[dataType]
            val params = state?.params ?: IncrementalBackupUtil.ArchiveParams(ct.name, level, threads, followSymlinks, exclusionList)
            val srcHash = state?.hash?.takeIf { ctx.enabled }

            // user 数据是海量小文件，calculateSize 遍历要几十秒且发热（实测微信约 40 秒），
            // 反把设备烤热导致后续 tar 打包被温控杀。跳过，大小由归档实际字节回填。
            // 「是否变化」不用总大小比对（掩盖同尺寸内容变化、且对 USER 恒失效），改由上方清单哈希判定。
            val sizeBytes = if (dataType == DataType.PACKAGE_USER) -1L else rootService.calculateSize(src)
            t.updateInfo(dataType = dataType, state = OperationState.PROCESSING, bytes = if (sizeBytes < 0) 0 else sizeBytes)
            // 路径 1：主目录自身的归档 + manifest 未变化 → 跳过（preserve 关闭时的常规路径）
            val unchangedInPlace = srcHash != null && rootService.exists(dst) && IncrementalBackupUtil.isUnchanged(rootService, dst, params, srcHash)
            // 路径 2：preserve 已把旧主备份归档走 → 旧版未变化则从保护版本继承归档，不重打
            val refDst = ctx.refDir?.let { archiveDstIn(it, dataType, p) }
            val reused = unchangedInPlace.not() && srcHash != null && refDst != null && rootService.exists(refDst) &&
                IncrementalBackupUtil.isUnchanged(rootService, refDst, params, srcHash) &&
                IncrementalBackupUtil.tryReuse(rootService, refDst, dst)
            if (unchangedInPlace || reused) {
                isSuccess = true
                t.updateInfo(dataType = dataType, state = OperationState.SKIP)
                out.add(log { if (reused) "Data has not changed. Reused archive from preserved version." else "Data has not changed." })
            } else {
                // Compress and test.
                Tar.compress(
                    exclusionList = exclusionList,
                    h = if (followSymlinks) "-h" else "",
                    srcDir = srcDir,
                    src = packageName,
                    dst = dst,
                    extra = ct.getCompressPara(level, threads)
                ).also { result ->
                    // 被系统主动杀（温控/OOM/厂商内存守护等，SIGKILL → 退出码 137）时自动抓取证信息，
                    // 区分凶手用（dmesg 的 OOM 记录 / thermal 温度 / Athena 等日志）
                    if (result.code == 137) {
                        LogUtil.logKillEvidence(packageName = packageName, dataType = dataType.type)
                    }
                    // GNU tar 非 0 退出码有两类，归档本身都可能已完整写出，故不在此处判失败，
                    // 统一交给紧随其后的 testArchive 裁定（能被 tar -tf 完整列出即算成功）：
                    //  1 = 读取期间源有变动（告警措辞有多种："file changed as we read it"、
                    //      "File removed before we read it"、"file shrank" 等，全是打包窗口内源被 App
                    //      自身改动的竞态），不逐条枚举文案（枚举必漏）；
                    //  2 = 个别条目无法读取（实测媒体备份 DCIM 时 .tmfs 这类目录连 root 都 Permission denied）。
                    // 归档真损坏（如磁盘写满）时 testArchive 会失败、最终仍判失败，不存在放行坏归档的风险。
                    if (result.isSuccess.not()) {
                        log { "tar exited with code ${result.code}; archive kept, verdict by testArchive." }
                    }
                    out.addAll(result.out)
                }
                commonBackupUtil.testArchive(src = dst, ct = ct).also { result ->
                    isSuccess = isSuccess && result.isSuccess
                    out.addAll(result.out)
                    if (result.isSuccess) {
                        // user 数据跳过了 calculateSize（sizeBytes=-1），用归档实际大小回填 dataBytes（user.tar 是单文件，算大小快）。
                        p.setDataBytes(dataType, if (sizeBytes < 0) rootService.calculateSize(dst) else sizeBytes)
                        p.setDisplayBytes(dataType, rootService.calculateSize(dst))
                        ChecksumUtil.write(rootService = rootService, src = dst)?.let { md5 ->
                            out.add(log { "Checksum: $md5" })
                        }
                        // 增量清单随归档落盘（唯一入口）：下次备份据此判定「未变化」跳过 tar。
                        writeManifest(dst = dst, state = state, ctx = ctx)
                    }
                }
            }

            t.updateInfo(dataType = dataType, state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = out.toLineString())
        }

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }

    /**
     * 源已不存在时，把上一份归档保留到 [dst]，让「主备份」保持完整（不会比保护版本还少一项）。
     *
     * 优先从保护版本借（preserve 场景：旧主备份已被 rename 走），其次原地已有即算保留成功。
     *
     * @return true = dst 侧已有可用归档；false = 无处可留（调用方按原行为提示「不存在，跳过」）
     */
    private suspend fun keepPreviousArchive(dst: String, dataType: DataType, p: PackageEntity, ctx: IncrementalContext): Boolean {
        if (rootService.exists(dst)) return true
        if (ctx.enabled.not()) return false
        val from = ctx.refDir?.let { archiveDstIn(it, dataType, p) }?.takeIf { rootService.exists(it) } ?: return false
        return IncrementalBackupUtil.tryReuse(rootService, from, dst)
    }

    suspend fun backupPermissions(p: PackageEntity) = run {
        log { "Backing up permissions..." }

        val packageName = p.packageName
        val userId = p.userId

        val packageInfo = rootService.getPackageInfoAsUser(packageName, PackageManager.GET_PERMISSIONS, userId)
        packageInfo?.apply {
            p.extraInfo.permissions = rootService.getPermissions(packageInfo = this)
        }
        val permissions = p.extraInfo.permissions
        log { "Permissions size: ${permissions.size}..." }
        permissions.forEach {
            log { "Permission name: ${it.name}, isGranted: ${it.isGranted}, op: ${it.op}, mode: ${it.mode}" }
        }
    }

    suspend fun backupSsaid(p: PackageEntity) = run {
        log { "Backing up ssaid..." }

        val packageName = p.packageName
        val uid = p.extraInfo.uid
        val userId = p.userId

        val ssaid = rootService.getPackageSsaidAsUser(packageName = packageName, uid = uid, userId = userId)
        log { "Ssaid: $ssaid" }
        p.extraInfo.ssaid = ssaid
    }

    suspend fun upload(client: CloudClient, p: PackageEntity, t: TaskDetailPackageEntity, dataType: DataType, srcDir: String, dstDir: String) = run {
        // 与 backupData 保持一致：所有数据类型统一跟随全局压缩设置。
        val ct = p.indexInfo.compressionType
        val src = packageRepository.getArchiveDst(dstDir = srcDir, dataType = dataType, ct = ct)
        t.updateInfo(dataType = dataType, state = OperationState.UPLOADING)

        // 归档较大且未开分卷：提示用户开分卷（阈值见 CLOUD_SPLIT_SUGGEST_BYTES）。
        // 同一次任务只提示一次 —— 日志里已含标记就不再追加，避免每个大文件刷一遍。
        runCatching {
            val archiveBytes = java.io.File(src).length()
            if (shouldSuggestCloudSplit(cloudSplitSize = context.readCloudSplitSize().first(), archiveBytes = archiveBytes)) {
                val hint = cloudSplitSuggestion(archiveBytes.toDouble().formatSize())
                if (t.getLog(dataType).contains(CLOUD_SPLIT_HINT_MARK).not()) {
                    t.updateInfo(dataType = dataType, log = (t.getLog(dataType) + "\n" + hint).trim())
                }
            }
        }

        var flag = true
        var progress = 0f
        with(CoroutineScope(coroutineContext)) {
            launch {
                while (flag) {
                    t.updateInfo(dataType = dataType, content = "${(progress * 100).toInt()}%")
                    delay(500)
                }
            }
        }

        var uploadSuccess = false
        val uploadResult = cloudRepository.upload(client = client, src = src, dstDir = dstDir, onUploading = { read, total -> progress = read.toFloat() / total }).apply {
            uploadSuccess = isSuccess
            flag = false
            t.updateInfo(dataType = dataType, state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = t.getLog(dataType) + "\n${outString}", content = "100%")
        }
        // CloudRepository.upload 内部 runCatching 吞掉异常、仅返回 isSuccess=false（含完整性校验失败）。
        // 注意：这里【不能抛异常】—— backup() 调用链无 runCatching 包裹，抛出会穿过协程边界变成
        // 未捕获异常 → 进程 FATAL 崩溃（2026-09-02 实测：补抛 IOException 导致 OplusExceptionHelper FATAL）。
        // 失败呈现：上方 apply 已把该项标 ERROR 并写入完整原因（outString），任务失败由 pkg.isSuccess/
        // failureCount 统计；这里仅补记一行日志。
        if (uploadResult.isSuccess.not()) {
            log { "Cloud upload failed: ${uploadResult.outString.take(500)}" }
        }

        // md5 sidecar 跟随归档上传：云端恢复的完整性校验依赖它（本地打包时已写在归档旁）。
        // 仅在归档上传成功后补传；sidecar 自身失败只记日志不阻断（恢复侧校验会退化为跳过并留下提示）
        if (uploadSuccess) runCatching {
            if (java.io.File("$src.md5").exists()) cloudRepository.upload(client = client, src = "$src.md5", dstDir = dstDir)
        }.onFailure {
            log { "Failed to upload md5 sidecar for $src: ${it.message}." }
        }
    }
}
