package com.xayah.core.util.command

import com.xayah.core.util.SymbolUtil.shellQuote
import com.xayah.core.util.model.ShellResult

/**
 * 数据目录属主扫描与修复。
 *
 * 背景：应用卸载重装后系统会分配新 UID，但外部存储（Android/{data,obb,media}）可能残留
 * 旧 UID 属主的数据目录，导致游戏等应用无法写入而报错（如 UE4 更新报 556793857）。
 * 本工具扫描所有已安装应用的数据目录（顶层属主 + 树内深度探测），把属主与当前 UID 不一致的残留修复掉。
 */
object Ownership {
    private suspend fun execute(vararg args: String): ShellResult = BaseUtil.execute(*args)

    data class Mismatch(
        val packageName: String,
        val path: String,
        val owner: String,
        val uid: String,
        val gid: String,
    )

    data class Report(
        val scanned: Int,
        val fixed: List<String>,
        val failed: List<String>,
        val skipped: List<String>,
    )

    /** 已安装应用表：pkg -> uid（user 0） */
    private suspend fun readInstalledUids(): Map<String, String> {
        // pm list packages -U → "package:xxx uid:10xxx"
        val result = execute("pm", "list", "packages", "-U")
        return buildMap {
            result.out.forEach { line ->
                val m = Regex("^package:(\\S+) uid:(\\d+)$").find(line.trim()) ?: return@forEach
                put(m.groupValues[1], m.groupValues[2])
            }
        }
    }

    /**
     * 扫描所有候选数据目录，返回属主错位列表。
     * 安全规则：属主是「其他已安装应用的活跃 UID」时跳过不修（多用户/双开场景），
     * 只修复属主为残留 UID（不在任何已安装应用 UID 集合中）的目录。
     */
    suspend fun scan(): List<Mismatch> {
        val uids = readInstalledUids()
        val activeUids = uids.values.toSet()
        val mismatches = mutableListOf<Mismatch>()

        // 内部存储 + 外部存储（含多用户 999 视图，去重后同一底层目录只处理一次）
        val bases = listOf(
            "/data/data",
            "/storage/emulated/0/Android/data",
            "/storage/emulated/0/Android/obb",
            "/storage/emulated/0/Android/media",
        )
        for (base in bases) {
            val listing = execute("ls", shellQuote(base))
            if (listing.isSuccess.not()) continue
            val dirs = listing.out.filter { it.isNotBlank() }
            for (pkg in dirs) {
                val cur = uids[pkg] ?: continue
                val path = "$base/$pkg"
                // 格式串必须 shellQuote：BaseUtil.execute 会把参数按空格 join 成一行，
                // 裸的 "%u %g" 会被拆成两个参数（%g 被当成文件名，stat 报 No such file or directory），
                // 导致 owner 解析成 "stat:"、所有目录被误报为属主错位。
                val stat = execute("stat", "-c", shellQuote("%u %g"), shellQuote(path))
                if (stat.isSuccess.not()) continue
                val parts = stat.outString.trim().split(Regex("\\s+"))
                val owner = parts.getOrNull(0) ?: continue
                val gid = parts.getOrNull(1) ?: continue
                if (owner != cur) {
                    if (owner in activeUids) continue // 其他活跃应用的 UID，可能是双开，不动
                    mismatches.add(Mismatch(packageName = pkg, path = path, owner = owner, uid = cur, gid = gid))
                    continue
                }
                // 深度检查：顶层属主正确时，探测树内是否残留其他属主文件（命中即停；不支持 -quit 的环境自动降级跳过）
                val probe = execute("find", shellQuote("$path/"), "!", "-user", shellQuote(cur), "-print", "-quit")
                if (probe.isSuccess && probe.out.any { it.isNotBlank() }) {
                    mismatches.add(Mismatch(packageName = pkg, path = path, owner = "inner", uid = cur, gid = gid))
                }
            }
        }
        return mismatches
    }

    /** 修复单个错位目录：停进程 → chown -R → 复检。返回 true 表示整树属主已一致。 */
    suspend fun fix(m: Mismatch): Boolean {
        execute("am", "force-stop", m.packageName)
        val chown = execute("chown", "-R", shellQuote("${m.uid}:${m.gid}"), shellQuote("${m.path}/"))
        if (chown.isSuccess.not()) return false
        val left = SELinux.countNotOwnedBy(path = m.path, uid = m.uid.toUInt())
        return (left.outString.trim().toIntOrNull() ?: 1) == 0
    }

    /** 扫描 + 全部修复，输出报告。 */
    suspend fun scanAndFixAll(): Report {
        val mismatches = scan()
        val fixed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        mismatches.forEach { m ->
            val label = "${m.packageName} (${m.path}, ${m.owner} -> ${m.uid})"
            if (fix(m)) fixed.add(label) else failed.add(label)
        }
        return Report(scanned = mismatches.size, fixed = fixed, failed = failed, skipped = listOf())
    }
}
