package com.xayah.core.service.util

import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.LogUtil
import com.xayah.core.util.SymbolUtil.shellQuote
import com.xayah.core.util.command.BaseUtil

/**
 * 归档级增量备份：源目录清单（manifest）比对。
 *
 * 思路：tar+zstd 管道与归档格式完全不动；归档旁挂 `<归档>.manifest`（源清单哈希 + 打包参数指纹），
 * 备份前先 walk 源目录算清单哈希，与上一份一致 → 跳过 tar（源没变，重打出来的归档字节必然相同）。
 *
 * 正确性底线（宁可白打、不可漏打）：
 * - manifest 缺失 / 解析失败 / walk 非 0 退出 / 读源失败 → 一律视为「已变化」走全量；
 * - walk 只对**常量目录名**做 find -prune 精确剪枝（与 tar --exclude 的目录项一一对应），
 *   glob 类排除项（Backup_*、.trashed-* 等）不在 walk 层过滤 —— walk 集合是 tar 打包集合的
 *   超集，多算只会让本次跳过失效，绝不会漏掉真实变化；
 * - 打包参数（压缩类型/等级/线程/跟随软链/排除清单）进指纹，任一变动强制重打
 *   （参数不同则归档字节不同，旧 manifest 失去可比性）。
 *
 * 演进注记：本机制取代旧的「总大小相等即跳过」判定 —— 大小相等掩盖「同尺寸内容变化」，
 * 且旧判定对 USER 数据（calculateSize 被跳过、恒 -1）永不生效。
 */
object IncrementalBackupUtil {
    private const val TAG = "IncrementalBackupUtil"
    private const val MANIFEST_SUFFIX = ".manifest"
    const val MANIFEST_VERSION = 1

    /** 打包参数指纹：任一不同 → 归档内容本会不同 → 必须重打。 */
    data class ArchiveParams(
        val compressionType: String,
        val level: Int,
        val threads: Int,
        val followSymlinks: Boolean,
        val exclusionList: List<String>,
    )

    /**
     * 单个数据类型「源侧」的判定产物：一次 walk 得到的清单哈希 + 打包参数指纹。
     *
     * 由 `PackagesBackupUtil.evaluateIncremental` 统一算出，供三处共用：
     * ① 是否归档旧主备份 ② 是否跳过重打 ③ 是否从保护版本借用。
     * 一次源只 walk 一次 —— 微信级目录的 walk 是秒级开销，重复跑会直接拖慢每次备份。
     *
     * @param hash null = 无法判定（walk 失败），调用方一律按「已变化」走全量
     * @param missing 源目录不存在（DATA/OBB/MEDIA 常见：应用本就没产生该类数据）
     */
    data class SourceState(val hash: String?, val params: ArchiveParams, val missing: Boolean = false) {
        /** 能否用于「未变化」判定：必须拿到 hash 且源确实存在。 */
        val usable: Boolean get() = hash != null && !missing
    }

    fun manifestPath(dst: String) = "$dst$MANIFEST_SUFFIX"

    internal fun serialize(hash: String, params: ArchiveParams): String = buildString {
        appendLine("v=$MANIFEST_VERSION")
        appendLine("hash=$hash")
        appendLine("ct=${params.compressionType}")
        appendLine("level=${params.level}")
        appendLine("threads=${params.threads}")
        appendLine("follow=${params.followSymlinks}")
        appendLine("excl=${params.exclusionList.joinToString("|")}")
    }

    /** 解析失败（含版本不符）返回 null，调用方按「已变化」处理。 */
    internal fun parse(text: String): Pair<String, ArchiveParams>? = runCatching {
        val map = text.lines()
            .filter { it.contains("=") }
            .associate { it.substringBefore("=") to it.substringAfter("=") }
        if (map["v"]?.toIntOrNull() != MANIFEST_VERSION) return null
        val hash = map["hash"].orEmpty()
        if (hash.isEmpty()) return null
        // ct 缺失拒收：宁可全量重打，不让异常 manifest 蒙混
        if (map.containsKey("ct").not()) return null
        hash to ArchiveParams(
            compressionType = map["ct"].orEmpty(),
            level = map["level"]?.toIntOrNull() ?: return null,
            threads = map["threads"]?.toIntOrNull() ?: return null,
            followSymlinks = map["follow"] == "true",
            exclusionList = map["excl"].orEmpty().split("|").filter { it.isNotEmpty() },
        )
    }.getOrNull()

    /**
     * 对 [srcAbs] 下的文件清单（`相对路径|大小|mtime`）排序后取 SHA-256。
     * 只 stat 不读内容；管道在 root shell 内完成，清单不出进程（微信级几十万文件也只是一条哈希）。
     *
     * **前提（勿破坏）**：shell 侧必须开 `set -o pipefail`（BaseUtil 的 EnvInitializer 已设）。
     * `find` 部分失败（子目录权限不足等）时退出码未必非 0，靠 pipefail 把管道整体判失败 →
     * 返回 null → 调用方走全量。若去掉 pipefail，这里会退化成「清单不完整但哈希照算」的静默漏检。
     *
     * @param pruneDirs 精确剪枝的绝对路径（与 tar --exclude 的常量目录项一一对应，如 `<src>/cache`）
     * @param maxDepth 1 = 只 walk 顶层文件（APK 场景，tar 只收顶层 apk）；0 = 不限深度
     * @return 清单哈希；任何一步失败返回 null（调用方视为「已变化」，走全量）
     */
    suspend fun computeSourceHash(
        srcAbs: String,
        pruneDirs: List<String>,
        followSymlinks: Boolean,
        maxDepth: Int = 0,
    ): String? = runCatching {
        val root = srcAbs.trimEnd('/')
        val args = mutableListOf("find")
        if (followSymlinks) args += "-L"
        args += shellQuote(root)
        if (maxDepth > 0) {
            args += "-maxdepth"
            args += maxDepth.toString()
        }
        if (pruneDirs.isNotEmpty()) {
            // 括号必须 shellQuote：BaseUtil 空格拼接参数，裸 '(' 被 shell 当语法符号
            // （v3.13.4 回收站扫描踩过，报 syntax error: unexpected '('，且会被 runCatching 吞成静默失效）
            args += shellQuote("(")
            pruneDirs.forEachIndexed { index, dir ->
                if (index > 0) args += "-o"
                args += "-path"
                args += shellQuote(dir.trimEnd('/'))
            }
            args += shellQuote(")")
            args += "-prune"
            args += "-o"
        }
        args += "-type"
        args += "f"
        // 格式串必须 shellQuote：反斜杠裸传会被 shell 吃掉，find 收到的就不是 \n 了
        args += "-printf"
        args += shellQuote("%P|%s|%T@\\n")
        args += "|"
        args += "LC_ALL=C"
        args += "sort"
        args += "|"
        args += "sha256sum"

        val result = BaseUtil.execute(*args.toTypedArray())
        if (result.code == 0) extractHash(result.out) else null
    }.onFailure {
        LogUtil.log { TAG to "computeSourceHash failed for $srcAbs: ${it.message}" }
    }.getOrNull()

    /**
     * 从 shell 输出里摘 sha256sum 的哈希行。
     * stderr 被 FLAG_REDIRECT_STDERR 合流进 out，哈希行用定长 64 位小写十六进制锚定，不受干扰行影响。
     */
    internal fun extractHash(out: List<String>): String? =
        out.firstOrNull { it.contains(Regex("^[0-9a-f]{64} ")) }?.take(64)

    /**
     * 比对 [dst] 旁的上一份 manifest 与当前清单。
     * @return true = 源未变化且打包参数未变化，可跳过 tar；false = 已变化或无法判定（保守）
     */
    suspend fun isUnchanged(
        rootService: RemoteRootService,
        dst: String,
        params: ArchiveParams,
        currentHash: String,
    ): Boolean {
        if (rootService.exists(manifestPath(dst)).not()) {
            LogUtil.log { TAG to "isUnchanged: manifest missing for $dst" }
            return false
        }
        val text = runCatching { rootService.readText(manifestPath(dst)) }.getOrNull() ?: return false
        val (oldHash, oldParams) = parse(text) ?: run {
            LogUtil.log { TAG to "isUnchanged: manifest parse failed for $dst, text=$text" }
            return false
        }
        val same = oldHash == currentHash && oldParams == params
        if (same.not()) LogUtil.log { TAG to "isUnchanged: changed dst=$dst old=${oldHash.take(8)} cur=${currentHash.take(8)}" }
        return same
    }

    /** 打包成功后写 manifest；失败仅记日志（最坏结果：下次备份全量重打，无正确性影响）。 */
    suspend fun write(rootService: RemoteRootService, dst: String, hash: String, params: ArchiveParams) {
        runCatching {
            rootService.writeText(serialize(hash, params), manifestPath(dst))
        }.onFailure {
            LogUtil.log { TAG to "Failed to write manifest for $dst: ${it.message}" }
        }
    }

    /**
     * 把归档三件套（归档本体 + `.md5` + `.manifest`）从 [refDst] 继承到 [dst]。
     *
     * 用途：preserve 模式下该类型未变化时，直接从被归档的旧版本「借」回归档，避免整包重打。
     *
     * 安全前提（调用方必须保证）：
     * - 旧主备份已 rename 走、新主目录刚创建 → [dst] 必然不存在，借用来的文件不会被 tar 原地覆盖
     *   （tar 只在判定「已变化」时执行，此时不会走到本函数）；
     * - [refDst] 所在目录已 rename 为保护版本，本次任务内不会再被写入。
     *
     * **平台事实（别按注释想当然做容量规划）**：Android 的 `/sdcard` 是 FUSE 文件系统，
     * **不支持硬链接**，`ln -f` 必然失败，实际全部走 `cp -f` 复制。因此本函数**不是**「秒级零拷贝」：
     * 耗时是顺序复制（省掉的是 tar+zstd 的压缩 CPU 与发热），空间上该类型在保护版本被清理前会短暂翻倍。
     * 想要真正的零拷贝需要把备份根目录放到 ext4 分区，或使用支持 reflink 的文件系统。
     *
     * 任一环节失败 → 清理本次已复制出来的文件并返回 false，调用方照常全量重打（fail-closed）。
     */
    suspend fun tryReuse(rootService: RemoteRootService, refDst: String, dst: String): Boolean {
        // refDst 与 dst 同构：归档本体 + ".md5" + ".manifest"
        val suffixes = listOf("", ".md5", ".manifest")
        val created = mutableListOf<String>()
        return try {
            for (suffix in suffixes) {
                val from = "$refDst$suffix"
                val to = "$dst$suffix"
                if (rootService.exists(from).not()) {
                    // manifest 必然存在（调用方已据此判定「未变化」）；md5 属既有产物，缺失不阻断继承
                    if (suffix == ".manifest") {
                        discard(rootService, created)
                        return false
                    }
                    continue
                }
                if (linkOrCopy(from, to).not()) {
                    discard(rootService, created)
                    return false
                }
                created += to
            }
            // 兜底校验：应落盘的都在、且与来源大小一致
            val ok = suffixes.all { suffix ->
                val from = "$refDst$suffix"
                val to = "$dst$suffix"
                rootService.exists(from).not() ||
                    (rootService.exists(to) && rootService.calculateSize(to) == rootService.calculateSize(from))
            }
            if (ok.not()) discard(rootService, created)
            ok
        } catch (e: Exception) {
            LogUtil.log { TAG to "tryReuse failed for $dst: ${e.message}" }
            discard(rootService, created)
            false
        }
    }

    /** 借用半途失败时清掉已复制出来的文件：不留半套归档在本目录里（下次全量重打会覆盖，但白占空间）。 */
    private suspend fun discard(rootService: RemoteRootService, paths: List<String>) {
        paths.forEach { runCatching { rootService.deleteRecursively(it) } }
    }

    /** `ln -f`（同分区零拷贝）失败时回退 `cp -f`。Android /sdcard 为 FUSE，实际上永远走 cp 分支。 */
    private suspend fun linkOrCopy(src: String, dst: String): Boolean {
        val cmd = "ln -f ${shellQuote(src)} ${shellQuote(dst)} 2>/dev/null || cp -f ${shellQuote(src)} ${shellQuote(dst)}"
        val result = BaseUtil.execute(cmd)
        return result.code == 0
    }
}
