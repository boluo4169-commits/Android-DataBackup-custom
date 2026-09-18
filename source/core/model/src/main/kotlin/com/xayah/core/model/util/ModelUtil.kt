package com.xayah.core.model.util

import com.xayah.core.model.CloudSplitSize
import com.xayah.core.model.CompressionType
import com.xayah.core.model.DataType
import com.xayah.core.model.KillAppOption
import com.xayah.core.model.LZ4_SUFFIX
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.SFTPAuthMode
import com.xayah.core.model.SelectionType
import com.xayah.core.model.SmbAuthMode
import com.xayah.core.model.SortType
import com.xayah.core.model.TAR_SUFFIX
import com.xayah.core.model.ThemeType
import com.xayah.core.model.ZSTD_SUFFIX
import com.xayah.core.model.database.Info
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailMediaEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.database.TaskEntity
import java.text.DecimalFormat
import kotlin.math.pow

fun Double.formatSize(unitValue: Int = 1024): String = run {
    var unit = "Bytes"
    var size = this
    val gb = unitValue.toDouble().pow(3)
    val mb = unitValue.toDouble().pow(2)
    val kb = unitValue.toDouble()
    if (this > gb) {
        size = this / gb
        unit = "GB"
    } else if (this > mb) {
        size = this / mb
        unit = "MB"
    } else if (this > kb) {
        size = this / kb
        unit = "KB"
    }
    if (size == 0.0) "0.00 $unit" else "${DecimalFormat("#.00").format(size)} $unit"
}

fun CompressionType.Companion.of(name: String?): CompressionType =
    runCatching { CompressionType.valueOf(name!!.uppercase()) }.getOrDefault(CompressionType.ZSTD)

fun OpType.Companion.of(name: String?): OpType =
    runCatching { OpType.valueOf(name!!.uppercase()) }.getOrDefault(OpType.BACKUP)

fun SortType.Companion.of(name: String?): SortType =
    runCatching { SortType.valueOf(name!!.uppercase()) }.getOrDefault(SortType.ASCENDING)

fun CompressionType.Companion.suffixOf(suffix: String): CompressionType? = when (suffix) {
    TAR_SUFFIX -> CompressionType.TAR
    ZSTD_SUFFIX -> CompressionType.ZSTD
    LZ4_SUFFIX -> CompressionType.LZ4
    else -> null
}

/**
 * 定位归档文件：优先取 [expected] 类型对应的路径，不存在时依次探测其余压缩类型。
 *
 * 用于兼容归档实际类型与记录不一致的历史备份（user 数据曾强制 TAR 不压缩，产生 user.tar；
 * 其余时期跟随全局设置产生 user.tar.zst）。恢复/下载/大小计算三处共用，保证两种归档都能定位。
 *
 * 全部不存在时回退返回 [expected] 的路径，交由调用方按「归档缺失」处理（SKIP 或报错）。
 */
suspend fun CompressionType.Companion.resolveArchive(
    expected: CompressionType,
    pathOf: (CompressionType) -> String,
    exists: suspend (String) -> Boolean,
): Pair<CompressionType, String> {
    val expectedPath = pathOf(expected)
    if (exists(expectedPath)) return expected to expectedPath
    CompressionType.values().forEach { type ->
        if (type != expected) {
            val path = pathOf(type)
            if (exists(path)) return type to path
        }
    }
    return expected to expectedPath
}

fun SelectionType.Companion.of(name: String?): SelectionType =
    runCatching { SelectionType.valueOf(name!!.uppercase()) }.getOrDefault(SelectionType.DEFAULT)

fun ThemeType.Companion.of(name: String?): ThemeType =
    runCatching { ThemeType.valueOf(name!!.uppercase()) }.getOrDefault(ThemeType.AUTO)

fun SmbAuthMode.Companion.indexOf(index: Int): SmbAuthMode = when (index) {
    1 -> SmbAuthMode.GUEST
    2 -> SmbAuthMode.ANONYMOUS
    else -> SmbAuthMode.PASSWORD
}

fun SFTPAuthMode.Companion.indexOf(index: Int): SFTPAuthMode = when (index) {
    1 -> SFTPAuthMode.PUBLIC_KEY
    else -> SFTPAuthMode.PASSWORD
}

fun KillAppOption.Companion.indexOf(index: Int): KillAppOption = when (index) {
    1 -> KillAppOption.OPTION_I
    2 -> KillAppOption.OPTION_II
    else -> KillAppOption.DISABLED
}

fun KillAppOption.Companion.of(name: String?): KillAppOption =
    runCatching { KillAppOption.valueOf(name!!.uppercase()) }.getOrDefault(KillAppOption.OPTION_II)

fun CloudSplitSize.Companion.indexOf(index: Int): CloudSplitSize = when (index) {
    1 -> CloudSplitSize.SIZE_1G
    2 -> CloudSplitSize.SIZE_2G
    3 -> CloudSplitSize.SIZE_4G
    else -> CloudSplitSize.DISABLED
}

/**
 * 「恢复权限」的默认值：**澎湃系统默认关闭**。
 *
 * 澎湃/MIUI 上这个开关不是"还原备份时的授权状态"，而是恢复时**强制授予全部权限**；
 * 默认打开会让用户恢复后拿到一堆本不该有的授权，故在澎湃上默认关掉、交给系统自己恢复。
 * 纯函数放在这里是为了可单测（ROM 判定在 RomUtil，读系统属性）。
 */
/** 云端分卷提醒阈值：归档超过该大小且未开分卷时提示用户（10 GB，避免对大归档用户漏提醒） */
const val CLOUD_SPLIT_SUGGEST_BYTES: Long = 10L * 1024 * 1024 * 1024

/** 提醒日志的识别标记：同一次任务只提示一次（靠日志里已否含该串判断），避免每个大文件都刷一遍 */
const val CLOUD_SPLIT_HINT_MARK = "Cloud split size"

/** 是否应提醒开启云端分卷：未开分卷 + 归档超过阈值 */
fun shouldSuggestCloudSplit(cloudSplitSize: CloudSplitSize, archiveBytes: Long): Boolean =
    cloudSplitSize == CloudSplitSize.DISABLED && archiveBytes > CLOUD_SPLIT_SUGGEST_BYTES

/** 云端分卷提醒文案（[sizeText] 由调用方格式化） */
fun cloudSplitSuggestion(sizeText: String): String =
    "This archive is $sizeText and may exceed the drive's single-file limit. " +
        "If the upload fails, turn on \"$CLOUD_SPLIT_HINT_MARK\" in Backup settings to upload it in volumes."

fun defaultRestorePermissions(isHyperOs: Boolean): Boolean = isHyperOs.not()

fun CloudSplitSize.Companion.of(name: String?): CloudSplitSize =
    runCatching { CloudSplitSize.valueOf(name!!.uppercase()) }.getOrDefault(CloudSplitSize.DISABLED)

fun Info.set(
    bytes: Long? = null,
    log: String? = null,
    content: String? = null,
    progress: Float? = null,
    state: OperationState? = null,
) {
    if (state != null) this.state = state
    if (bytes != null) this.bytes = bytes
    if (log != null) this.log = log
    if (content != null) this.content = content
    if (progress != null) this.progress = progress
}

fun TaskDetailPackageEntity.set(
    state: OperationState? = null,
    processingIndex: Int? = null,
    packageEntity: PackageEntity? = null,
) {
    if (state != null) this.state = state
    if (processingIndex != null) this.processingIndex = processingIndex
    if (packageEntity != null) this.packageEntity = packageEntity
}

fun TaskDetailPackageEntity.set(
    dataType: DataType,
    bytes: Long? = null,
    log: String? = null,
    content: String? = null,
    progress: Float? = null,
    state: OperationState? = null,
) = run {
    when (dataType) {
        DataType.PACKAGE_APK -> {
            apkInfo.set(bytes, log, content, progress, state)
        }

        DataType.PACKAGE_USER -> {
            userInfo.set(bytes, log, content, progress, state)
        }

        DataType.PACKAGE_USER_DE -> {
            userDeInfo.set(bytes, log, content, progress, state)
        }

        DataType.PACKAGE_DATA -> {
            dataInfo.set(bytes, log, content, progress, state)
        }

        DataType.PACKAGE_OBB -> {
            obbInfo.set(bytes, log, content, progress, state)
        }

        DataType.PACKAGE_MEDIA -> {
            mediaInfo.set(bytes, log, content, progress, state)
        }

        else -> {}
    }
}

fun TaskDetailPackageEntity.get(
    dataType: DataType,
): Info = run {
    when (dataType) {
        DataType.PACKAGE_APK -> {
            apkInfo
        }

        DataType.PACKAGE_USER -> {
            userInfo
        }

        DataType.PACKAGE_USER_DE -> {
            userDeInfo
        }

        DataType.PACKAGE_DATA -> {
            dataInfo
        }

        DataType.PACKAGE_OBB -> {
            obbInfo
        }

        DataType.PACKAGE_MEDIA -> {
            mediaInfo
        }

        else -> apkInfo
    }
}

fun TaskDetailMediaEntity.set(
    state: OperationState? = null,
    processingIndex: Int? = null,
    mediaEntity: MediaEntity? = null,
) {
    if (state != null) this.state = state
    if (processingIndex != null) this.processingIndex = processingIndex
    if (mediaEntity != null) this.mediaEntity = mediaEntity
}

fun TaskDetailMediaEntity.set(
    bytes: Long? = null,
    log: String? = null,
    content: String? = null,
    progress: Float? = null,
    state: OperationState? = null,
) = run {
    mediaInfo.set(bytes, log, content, progress, state)
}

fun ProcessingInfoEntity.set(
    bytes: Long? = null,
    log: String? = null,
    title: String? = null,
    content: String? = null,
    progress: Float? = null,
    state: OperationState? = null,
) = run {
    if (bytes != null) this.bytes = bytes
    if (log != null) this.log = log
    if (title != null) this.title = title
    if (content != null) this.content = content
    if (progress != null) this.progress = progress
    if (state != null) this.state = state
}

fun TaskEntity.set(
    startTimestamp: Long? = null,
    endTimestamp: Long? = null,
    rawBytes: Double? = null,
    availableBytes: Double? = null,
    totalBytes: Double? = null,
    totalCount: Int? = null,
    successCount: Int? = null,
    failureCount: Int? = null,
    preprocessingIndex: Int? = null,
    processingIndex: Int? = null,
    postProcessingIndex: Int? = null,
    isProcessing: Boolean? = null,
    cloud: String? = null,
    backupDir: String? = null,
) = run {
    if (startTimestamp != null) this.startTimestamp = startTimestamp
    if (endTimestamp != null) this.endTimestamp = endTimestamp
    if (rawBytes != null) this.rawBytes = rawBytes
    if (availableBytes != null) this.availableBytes = availableBytes
    if (totalBytes != null) this.totalBytes = totalBytes
    if (totalCount != null) this.totalCount = totalCount
    if (successCount != null) this.successCount = successCount
    if (failureCount != null) this.failureCount = failureCount
    if (preprocessingIndex != null) this.preprocessingIndex = preprocessingIndex
    if (processingIndex != null) this.processingIndex = processingIndex
    if (postProcessingIndex != null) this.postProcessingIndex = postProcessingIndex
    if (isProcessing != null) this.isProcessing = isProcessing
    if (cloud != null) this.cloud = cloud
    if (backupDir != null) this.backupDir = backupDir
}

fun CompressionType.getCompressPara(level: Int, threads: Int): String = when (this) {
    CompressionType.TAR -> compressPara
    // zstd 的 --ultra 只对 level 20~22 生效，1~19 加了也是冗余，故仅在 >=20 时拼上
    CompressionType.ZSTD, CompressionType.LZ4 -> "$compressPara -T$threads${if (level >= 20) " --ultra" else ""} -$level"
}
