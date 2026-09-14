package com.xayah.feature.main.system

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.xayah.core.util.SymbolUtil.shellQuote
import com.xayah.core.util.command.BaseUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 系统数据备份 / 恢复（短信 · 通话记录 · WiFi）。
 *
 * 两条通路：
 * - **本地**：备份由用户通过系统文件管理器选保存位置，恢复由用户选备份文件（SAF）。
 * - **云端**：备份上传到已配置的云账号，恢复从云端下载。
 *
 * 两条通路都必须借道应用私有缓存 —— 系统数据库在 `/data/data/...`、WiFi 配置在 `/data/misc/...`，
 * 普通文件 API 够不着，所以一律「root 复制到私有缓存 → 再读写目标」。
 * 恢复时属主不能硬编码（各机型 uid 不同），取「恢复前当前设备上该文件的属主」。
 */
object SystemDataUtil {
    /** 短信（含 MMS，附件以 BLOB 存在同一个库里） */
    const val SmsPackage = "com.android.providers.telephony"
    const val SmsDbPath = "/data/data/com.android.providers.telephony/databases/mmssms.db"

    /** 通话记录 */
    const val CallLogPackage = "com.android.providers.contacts"
    const val CallLogDbPath = "/data/data/com.android.providers.contacts/databases/calllog.db"

    /** WiFi 配置：Android 11+ 在 apexdata 下，旧版本在 /data/misc/wifi */
    const val WifiStorePath = "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml"
    const val WifiStoreLegacyPath = "/data/misc/wifi/WifiConfigStore.xml"

    /** 云端存放目录名（挂在云账号配置的远端根目录下） */
    const val CloudRelativeDir = "system_data"

    private const val TempRelativeDir = "system_data_tmp"
    private const val TempFileName = "data.bin"

    /** 兼容性比对时，目标设备现有数据库在缓存里的副本名 */
    private const val TargetProbeFileName = "target_probe.db"

    /** 探测 WiFi 配置文件的真实位置（新路径优先）。 */
    suspend fun resolveWifiStorePath(): String =
        if (fileExists(WifiStorePath)) WifiStorePath else WifiStoreLegacyPath

    suspend fun fileExists(path: String): Boolean =
        BaseUtil.execute("ls", shellQuote(path), log = false).isSuccess

    /** 读取文件的属主（数字 `uid:gid`），失败返回 null。 */
    suspend fun readOwner(path: String): String? {
        val result = BaseUtil.execute("stat", "-c", shellQuote("%u:%g"), shellQuote(path), log = false)
        if (result.isSuccess.not()) return null
        return result.outString.trim().ifEmpty { null }
    }

    /**
     * 私有缓存里的中转文件路径。
     * [name] 必须用数据项的真实文件名（mmssms.db / calllog.db / WifiConfigStore.xml）：
     * 云端上传直接拿这个文件名当远端文件名，三项共用一个临时名会互相覆盖，
     * 恢复时按名字也找不到对应文件。
     */
    fun tempPath(context: Context, name: String = TempFileName) = "${context.cacheDir}/$TempRelativeDir/$name"

    /**
     * 把系统数据复制到应用私有缓存并改成应用属主，返回缓存文件路径。
     * 本地导出与云端上传都以它为起点（两者都需要一个应用能直接读的文件）。
     */
    suspend fun stageToCache(context: Context, src: String, name: String = TempFileName): String? {
        val temp = tempPath(context, name)
        ensureTempDir(context)
        val copied = BaseUtil.execute("cp", "-f", shellQuote(src), shellQuote(temp), log = false).isSuccess
        if (copied.not()) return null
        val uid = context.applicationInfo.uid
        BaseUtil.execute("chown", shellQuote("$uid:$uid"), shellQuote(temp), log = false)
        BaseUtil.execute("chmod", "600", shellQuote(temp), log = false)
        return temp
    }

    /** 临时目录的绝对路径（本地中转与云端下载共用）。 */
    fun tempDir(context: Context) = "${context.cacheDir}/$TempRelativeDir"

    /**
     * 确保临时目录存在，且**属主是应用自己**。
     *
     * 必须用应用身份创建 + 兜底 chown：若交给 root 建（`mkdir` 或 RemoteRootService.mkdirs），
     * 目录属主变成 root，之后云端下载要往这个目录里落文件时应用进程会拿到 EACCES（实测踩过）。
     * 而目录一旦被 root 建过就会一直存在，光 mkdirs 不会纠正属主，所以每次都显式 chown 一次。
     */
    suspend fun ensureTempDir(context: Context) {
        val dir = tempDir(context)
        withContext(Dispatchers.IO) { File(dir).mkdirs() }
        val uid = context.applicationInfo.uid
        BaseUtil.execute("chown", shellQuote("$uid:$uid"), shellQuote(dir), log = false)
        BaseUtil.execute("chmod", "700", shellQuote(dir), log = false)
    }

    /** 清理私有缓存临时目录（导出、上传、恢复结束后调用）。 */
    suspend fun cleanupCache(context: Context) {
        BaseUtil.execute("rm", "-rf", shellQuote("${context.cacheDir}/$TempRelativeDir"), log = false)
    }

    // ---------------- 本地（SAF） ----------------

    /** 备份到用户通过文件管理器选定的位置。 */
    suspend fun backupToUri(context: Context, src: String, uri: Uri): Boolean {
        val temp = stageToCache(context, src) ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                File(temp).inputStream().use { input ->
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        input.copyTo(output)
                    } ?: return@runCatching false
                }
                true
            }.getOrDefault(false).also { cleanupCache(context) }
        }
    }

    /** 把用户选定的备份文件读进私有缓存，成功返回缓存路径。 */
    suspend fun stageFromUri(context: Context, uri: Uri): String? {
        val temp = tempPath(context)
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                File(temp).parentFile?.mkdirs()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    File(temp).outputStream().use { output -> input.copyTo(output) }
                } != null
            }.getOrDefault(false)
        }
        return if (ok) temp else null
    }

    // ---------------- 恢复（两种通路共用） ----------------

    /**
     * 读取 SQLite 文件的表结构（表名 → 列名集合）。
     * 只用于跨设备恢复前的兼容性比对；[dbFile] 必须是应用能直接打开的（缓存里的副本）。
     */
    suspend fun readSchema(dbFile: String): Map<String, Set<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val schema = mutableMapOf<String, Set<String>>()
            SQLiteDatabase.openDatabase(dbFile, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val table = cursor.getString(0) ?: continue
                        // 跳过 SQLite 内部表与 Android 元数据表
                        if (table.startsWith("sqlite_") || table.startsWith("android_")) continue
                        val columns = mutableSetOf<String>()
                        db.rawQuery("PRAGMA table_info(`$table`)", null).use { columnCursor ->
                            while (columnCursor.moveToNext()) columns.add(columnCursor.getString(1))
                        }
                        schema[table] = columns
                    }
                }
            }
            schema
        }.getOrDefault(emptyMap())
    }

    /**
     * 找出「目标设备已有、但备份文件里没有」的列。
     *
     * 恢复是拿备份库**整个覆盖**目标库，之后目标机的 App 会按**它自己的 schema** 去查。
     * 跨品牌时对方的库往往有厂商私有列（如小米的 `miui_*`、一加的 `oplus_*`），备份文件里没有这些列，
     * 覆盖后这些查询就会落空 —— 所以覆盖前必须把差异摆给用户看。
     * 同设备/同品牌恢复时该列表为空，不会打扰用户。
     */
    suspend fun findMissingColumns(context: Context, stagedSource: String, dst: String): List<String> {
        // 目标库同样要先复制到缓存，应用才有权限用 SQLite 打开
        val targetStaged = stageToCache(context, dst, TargetProbeFileName) ?: return emptyList()
        val target = readSchema(targetStaged)
        val source = readSchema(stagedSource)
        val missing = mutableListOf<String>()
        target.forEach { (table, columns) ->
            val sourceColumns = source[table]
            if (sourceColumns == null) {
                // 整张表都不在备份里 —— 覆盖后本机这张表直接消失，比缺列更严重。
                // 实测踩过：拿通话记录库去恢复短信库时，sms/threads 表整体不存在，
                // 早期实现会跳过而误判为「无差异」，结果把短信库整个盖掉。
                missing.add(table)
                return@forEach
            }
            columns.filter { it !in sourceColumns }.forEach { missing.add("$table.$it") }
        }
        return missing
    }

    /**
     * 用已落在私有缓存里的 [staged] 文件恢复数据库类数据：
     * 停 provider → 覆盖 [dst] → 还原属主 / 权限 / SELinux 标签。
     * provider 会在下次访问时自动重启，无需重启设备或 system_server。
     */
    suspend fun restoreFromStaged(context: Context, staged: String, dst: String, pkg: String): Boolean {
        val owner = readOwner(dst)
        BaseUtil.execute("am", "force-stop", pkg, log = false)
        val copied = BaseUtil.execute("cp", "-f", shellQuote(staged), shellQuote(dst), log = false).isSuccess
        if (copied) {
            owner?.let { BaseUtil.execute("chown", shellQuote(it), shellQuote(dst), log = false) }
            BaseUtil.execute("chmod", "660", shellQuote(dst), log = false)
            // 单文件用 -F（不带 -R）；标签由路径策略决定，自动还原 radio_data_file / privapp_data_file 等
            BaseUtil.execute("restorecon", "-F", shellQuote(dst), log = false)
        }
        cleanupCache(context)
        return copied
    }

    /** 用私有缓存里的 WiFi 配置文件恢复：解析出 SSID / 密码，逐条写回并连接。 */
    suspend fun restoreWifiFromStaged(context: Context, staged: String): Boolean {
        val xml = withContext(Dispatchers.IO) {
            runCatching { File(staged).bufferedReader().use { it.readText() } }.getOrNull()
        }
        val networks = xml?.let { parseWifiNetworks(it) }.orEmpty()
        cleanupCache(context)
        if (networks.isEmpty()) return false
        return networks.all { connectWifi(it) }
    }

    /**
     * 解析 WifiConfigStore.xml，取出 `SSID / 安全类型 / 密码`。
     * 结构与来源见 docs/system-data-backup-recon.md 与 wifi 相关调研。
     */
    fun parseWifiNetworks(xml: String): List<WifiNetwork> {
        val networks = mutableListOf<WifiNetwork>()
        // 每个网络一个 <Network>…</Network> 块，块内 SSID 与 PreSharedKey 都是带引号的字符串
        val blocks = Regex("<Network>(.*?)</Network>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
        for (block in blocks) {
            val content = block.groupValues[1]
            val ssid = Regex("<string name=\"SSID\">&quot;(.*?)&quot;</string>", RegexOption.DOT_MATCHES_ALL)
                .find(content)?.groupValues?.get(1) ?: continue
            val key = Regex("<string name=\"PreSharedKey\">&quot;(.*?)&quot;</string>", RegexOption.DOT_MATCHES_ALL)
                .find(content)?.groupValues?.get(1)
            val configKey = Regex("<string name=\"ConfigKey\">&quot;.*?&quot;(\\w*)</string>", RegexOption.DOT_MATCHES_ALL)
                .find(content)?.groupValues?.get(1).orEmpty()
            val security = when {
                configKey.contains("SAE", ignoreCase = true) -> "wpa3"
                configKey.contains("WPA", ignoreCase = true) -> "wpa2"
                configKey.contains("WEP", ignoreCase = true) -> "wep"
                else -> "open"
            }
            networks.add(WifiNetwork(ssid = ssid, security = security, passphrase = key))
        }
        return networks
    }

    /** 恢复一条 WiFi：`cmd wifi connect-network <ssid> <security> [passphrase]`，会同时加入已保存网络列表。 */
    suspend fun connectWifi(network: WifiNetwork): Boolean {
        val args = mutableListOf("cmd", "wifi", "connect-network", shellQuote(network.ssid), network.security)
        if (network.security != "open") {
            args.add(shellQuote(network.passphrase.orEmpty()))
        }
        // log = false：命令行里含明文密码
        return BaseUtil.execute(*args.toTypedArray(), log = false).isSuccess
    }

    data class WifiNetwork(
        val ssid: String,
        val security: String,
        val passphrase: String?,
    )
}
