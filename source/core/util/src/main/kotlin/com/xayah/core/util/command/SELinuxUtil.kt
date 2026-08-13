package com.xayah.core.util.command

import com.xayah.core.util.SymbolUtil.QUOTE
import com.xayah.core.util.SymbolUtil.USD
import com.xayah.core.util.model.ShellResult

object SELinux {
    private suspend fun execute(vararg args: String): ShellResult = BaseUtil.execute(*args)

    suspend fun getContext(path: String): ShellResult = run {
        // ls -Zd "$path" | awk 'NF>1{print $1}'
        execute(
            "ls",
            "-Zd",
            "$QUOTE$path$QUOTE",
            "|",
            "awk 'NF>1{print ${USD}1}'"
        )
    }

    suspend fun chown(uid: UInt, gid: UInt, path: String): ShellResult = run {
        // chown -hR "$uid:$uid" "$path/"
        execute(
            "chown",
            "-hR",
            "$QUOTE$uid:$gid$QUOTE",
            "$QUOTE$path/$QUOTE",
        )
    }

    suspend fun chcon(context: String, path: String): ShellResult = run {
        // chcon -hR "$context" "$path/"
        execute(
            "chcon",
            "-hR",
            "$QUOTE$context$QUOTE",
            "$QUOTE$path/$QUOTE",
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
            "$QUOTE$path/$QUOTE",
        )
    }
}