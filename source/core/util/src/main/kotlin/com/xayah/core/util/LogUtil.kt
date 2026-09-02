package com.xayah.core.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.xayah.core.common.util.BuildConfigUtil
import com.xayah.core.datastore.readCustomSUFile
import com.xayah.core.util.SymbolUtil.LF
import com.xayah.core.util.SymbolUtil.USD
import com.xayah.core.util.command.BaseUtil
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.nio.channels.FileChannel
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LogUtil {
    private lateinit var cacheDir: String
    private lateinit var logFile: RandomAccessFile
    private val timestamp: Long = DateUtil.getTimestamp()
    private const val SEPARATOR = "    "
    private const val LOG_FILE_PREFIX = "log_"
    private const val LOG_ZIP_PREFIX = "logs_"
    private const val MAX_LOG_FILES = 10
    private const val TAG_COMMON = "Common    "
    const val TAG_SHELL_IN = "SHELL_IN  "
    const val TAG_SHELL_OUT = "SHELL_OUT "
    const val TAG_SHELL_CODE = "SHELL_CODE"

    // 文件名内的时间格式（人类可读，替代原先难以辨认的 epoch 毫秒）
    private val fileNameTime: String
        get() = DateUtil.formatTimestamp(timestamp, "yyyyMMdd_HHmmss")

    private fun getLogFileName() = "$LOG_FILE_PREFIX$fileNameTime.txt"

    fun initialize(context: Context, cacheDir: String) = runCatching {
        // Clear empty log files.
        FileUtil.listFilePaths(cacheDir).forEach { path ->
            File(path).apply {
                if (readLines().size <= 4) deleteRecursively()
            }
        }

        File(cacheDir).apply {
            if (exists().not()) mkdirs()
        }
        this.cacheDir = cacheDir
        this.logFile = RandomAccessFile("$cacheDir/${getLogFileName()}", "rw")
        log("Version:    ${BuildConfigUtil.VERSION_NAME}")
        log("Model:      ${Build.MODEL}")
        log("ABIs:       ${Build.SUPPORTED_ABIS.firstOrNull() ?: ""}")
        log("SDK:        ${Build.VERSION.SDK_INT}")
        log("Global Namespace:     ${runBlocking { BaseUtil.readLink("1") }}")
        log("Namespace:            ${runBlocking { BaseUtil.readLink("self") }}")
        log("SU:                   ${runBlocking { BaseUtil.readSuVersion(context.readCustomSUFile().first()) }}")
        log("${USD}PATH:                ${runBlocking { BaseUtil.readVariable("PATH").trim() }}")
        log("${USD}HOME:                ${runBlocking { BaseUtil.readVariable("HOME").trim() }}")
    }

    private fun appendLine(msg: String) = runCatching {
        val bytes = (msg + LF).toByteArray()
        val pos = logFile.channel.size()
        val buffer = logFile.channel.map(FileChannel.MapMode.READ_WRITE, pos, bytes.size.toLong())
        buffer.put(bytes)
    }

    private fun appendWithTimestamp(tag: String, msg: String) = run {
        Log.d(tag, msg)
        appendLine("${DateUtil.formatTimestamp(DateUtil.getTimestamp())}$SEPARATOR$tag$SEPARATOR$msg")
    }

    fun log(content: () -> Pair<String, String>) {
        appendWithTimestamp(tag = content().first, msg = content().second)
    }

    fun log(msg: String) {
        appendWithTimestamp(tag = TAG_COMMON, msg = msg)
    }

    fun logCrash(msg: String) = runCatching {
        appendLine("${DateUtil.formatTimestamp(DateUtil.getTimestamp())}$SEPARATOR$TAG_COMMON$SEPARATOR$msg")
    }

    /**
     * 收集系统级取证信息（需 root，失败静默跳过）：
     * 1. 全系统 logcat 尾部（诊断「恢复后目标应用闪退」类问题的关键证据）；
     * 2. 系统 dropbox 中最近的 app crash 条目（目标应用崩溃的官方记录）。
     */
    private fun collectSystemEvidence(): String = buildString {
        appendLine("======== System logcat (tail 1000) ========")
        runCatching {
            val r = runBlocking { BaseUtil.execute("logcat", "-d", "-t", "1000", log = false) }
            appendLine(r.outString)
        }.onFailure { appendLine("unavailable: ${it.message}") }
        appendLine("")
        appendLine("======== System dropbox recent crashes ========")
        runCatching {
            val ls = runBlocking { BaseUtil.execute("ls", "-t", "/data/system/dropbox", log = false) }
            val candidates = ls.out
                .map { it.trim() }
                .filter { it.contains("crash", ignoreCase = true) || it.contains("anr", ignoreCase = true) }
                .take(3)
            if (candidates.isEmpty()) {
                appendLine("(no crash entries)")
            } else {
                candidates.forEach { name ->
                    appendLine("---- $name ----")
                    // 单条目截断 20000 字符，避免超大文件撑爆日志
                    val cat = runBlocking {
                        BaseUtil.execute("head", "-c", "20000", com.xayah.core.util.SymbolUtil.shellQuote("/data/system/dropbox/$name"), log = false)
                    }
                    appendLine(cat.outString)
                }
            }
        }.onFailure { appendLine("unavailable: ${it.message}") }
    }

    /**
     * 备份/恢复过程中进程被系统主动杀（如 SHELL_CODE 137）时抓取取证信息写入日志（需 root，失败静默跳过）。
     * 覆盖四类证据，用于区分温控 / OOM(lmkd) / 厂商内存守护(Athena 等) 谁是凶手：
     * 1. 内核 dmesg（OOM killer 会记录 "Killed process ... total-vm"；thermal 会有温度相关条目）；
     * 2. thermal 各温度传感器读数；
     * 3. 内存概况（/proc/meminfo）；
     * 4. 系统 logcat 中 athena/lmkd/thermal/kill 相关行（缓冲区内时）。
     */
    fun logKillEvidence(packageName: String, dataType: String) {
        log { "SystemKillEvidence" to "Collecting evidence after $dataType backup of $packageName was killed (137)..." }
        runCatching {
            val dmesg = runBlocking { BaseUtil.execute("dmesg", log = false) }
            val relevant = dmesg.out
                .filter {
                    it.contains("Killed process", ignoreCase = true) ||
                        it.contains("oom", ignoreCase = true) ||
                        it.contains("lowmemorykiller", ignoreCase = true) ||
                        it.contains("lmkd", ignoreCase = true) ||
                        it.contains("thermal", ignoreCase = true) ||
                        it.contains("watchdog", ignoreCase = true)
                }
                .takeLast(40)
            log { "SystemKillEvidence" to "dmesg relevant lines:\n${relevant.joinToString("\n")}" }
        }
        runCatching {
            // 通配符需裸传（勿 shellQuote），让 shell 展开
            val temp = runBlocking { BaseUtil.execute("cat", "/sys/class/thermal/thermal_zone*/temp", log = false) }
            log { "SystemKillEvidence" to "thermal temps:\n${temp.outString.trim()}" }
        }
        runCatching {
            val mem = runBlocking { BaseUtil.execute("cat", "/proc/meminfo", log = false) }
            log { "SystemKillEvidence" to "meminfo:\n${mem.out.take(12).joinToString("\n")}" }
        }
        runCatching {
            val lc = runBlocking { BaseUtil.execute("logcat", "-d", "-t", "3000", log = false) }
            val lines = lc.out
                .filter {
                    it.contains("athena", ignoreCase = true) ||
                        it.contains("lmkd", ignoreCase = true) ||
                        it.contains("thermal", ignoreCase = true) ||
                        it.contains("lowmemory", ignoreCase = true) ||
                        it.contains(" kill", ignoreCase = true)
                }
                .takeLast(40)
            log { "SystemKillEvidence" to "logcat relevant lines:\n${lines.joinToString("\n")}" }
        }
    }

    fun createLogsZip(): File? {
        val zipName = "$LOG_ZIP_PREFIX${DateUtil.formatTimestamp(DateUtil.getTimestamp(), "yyyyMMdd_HHmmss")}.zip"
        val logFiles = File(cacheDir).listFiles { f ->
            f.isFile && f.name.startsWith(LOG_FILE_PREFIX) && f.name.endsWith(".txt")
        }?.sortedByDescending { it.lastModified() }?.take(MAX_LOG_FILES).orEmpty()

        return runCatching {
            val zipFile = File(cacheDir, zipName)
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                logFiles.forEach { f ->
                    zos.putNextEntry(ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                // 系统取证（root 可用时），失败不影响日志导出
                runCatching {
                    val evidence = collectSystemEvidence()
                    zos.putNextEntry(ZipEntry("system_evidence.txt"))
                    evidence.byteInputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            zipFile
        }.getOrNull()
    }
}

fun <T> Result<T>.withLog(): Result<T> {
    exceptionOrNull()?.let {
        val stringWriter = StringWriter()
        it.printStackTrace(PrintWriter(stringWriter))
        LogUtil.log { "Exception" to stringWriter.toString() }
    }
    return this
}
