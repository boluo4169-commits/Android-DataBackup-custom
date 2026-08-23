package com.xayah.core.util.command

import com.xayah.core.util.SymbolUtil
import com.xayah.core.util.SymbolUtil.shellQuote
import com.xayah.core.util.model.ShellResult

object SELinux {
    private suspend fun execute(vararg args: String): ShellResult = BaseUtil.execute(*args)

    suspend fun getContext(path: String): ShellResult = run {
        // ls -Zd "$path" | awk 'NF>1{print $1}'
        execute(
            "ls",
            "-Zd",
            shellQuote(path),
            "|",
            "awk 'NF>1{print ${SymbolUtil.USD}1}'"
        )
    }

    suspend fun chown(uid: UInt, gid: UInt, path: String): ShellResult = run {
        // chown -hR "$uid:$uid" "$path/"
        execute(
            "chown",
            "-hR",
            shellQuote("$uid:$gid"),
            shellQuote("$path/"),
        )
    }

    suspend fun chcon(context: String, path: String): ShellResult = run {
        // chcon -hR "$context" "$path/"
        execute(
            "chcon",
            "-hR",
            shellQuote(context),
            shellQuote("$path/"),
        )
    }

    /**
     * 用系统自带的 restorecon 按 file_contexts 规则自动恢复 SELinux context（含多用户 category、
     * 外部存储映射）。比手动 chcon 更可靠，尤其对 /storage/emulated/0/Android/{data,obb,media}/ 这类
     * 恢复前目录不存在的场景（手动获取不到正确 context）。
     */
    suspend fun restorecon(path: String): ShellResult = run {
        // restorecon -R -F "$path/"
        execute(
            "restorecon",
            "-R",
            "-F",
            shellQuote("$path/"),
        )
    }

    /**
     * 干净恢复：清空目标目录的第一层内容，但保留 lib/cache/code_cache/no_backup/.ota 等
     * 系统/临时目录。替代原来的 tar `--recursive-unlink`（它会递归删除整个目录，包括安装时
     * 提取到 lib 的 native 库，导致依赖 .so 的应用恢复后崩溃）。
     */
    suspend fun cleanRestore(dst: String): ShellResult = run {
        // find "$dst" -mindepth 1 -maxdepth 1 -not -name lib -not -name cache ... -exec rm -rf {} +
        execute(
            "find", shellQuote(dst),
            "-mindepth", "1", "-maxdepth", "1",
            "-not", "-name", "lib",
            "-not", "-name", "cache",
            "-not", "-name", "code_cache",
            "-not", "-name", "no_backup",
            "-not", "-name", ".ota",
            "-exec", "rm", "-rf", "{}", "+",
        )
    }
}
