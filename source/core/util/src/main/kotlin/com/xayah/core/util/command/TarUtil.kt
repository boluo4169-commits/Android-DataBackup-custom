package com.xayah.core.util.command

import com.xayah.core.common.util.toSpaceString
import com.xayah.core.common.util.trim
import com.xayah.core.util.SymbolUtil
import com.xayah.core.util.model.ShellResult

object Tar {
    private suspend fun execute(vararg args: String): ShellResult = BaseUtil.execute("tar", *args)

    /** 列出归档内的路径清单（只读，不解压）。用于恢复前报告哪些文件会被跳过。 */
    suspend fun list(src: String): ShellResult = execute("-tf", SymbolUtil.shellQuote(src))

    /**
     * 同上，但支持压缩归档。[extra] 为 `CompressionType.decompressPara`（"zstd" 等），
     * 非空时走 `zstd -d -c "$src" | tar -tf -`，与 [decompress] / [test] 的压缩分支同口径。
     */
    suspend fun list(src: String, extra: String): ShellResult = if (extra.isEmpty()) {
        list(src)
    } else {
        BaseUtil.execute(
            "zstd",
            "-d",
            "-c",
            SymbolUtil.shellQuote(src),
            "|",
            "tar",
            "-tf",
            "-",
        )
    }

    suspend fun compressInCur(cur: String, src: String, dst: String, extra: String): ShellResult {
        // Move to $cur path.
        BaseUtil.execute("cd", SymbolUtil.shellQuote(cur))

        // Compress
        // Note: src 保持裸传（不 shellQuote）——它可能是通配符（如 ./*.apk），需要 shell 展开；
        // 通配符本身不构成注入面（由调用方硬编码），且引号会阻止展开导致 tar 找不到文件。
        val result = if (extra.isEmpty()) {
            // tar --totals -cpf - $src > "$dst"
            execute(
                "--totals",
                "-cpf",
                "-",
                src,
                ">",
                SymbolUtil.shellQuote(dst),
            )
        } else {
            // tar --totals -cpf - $src | $extra > "$dst"
            execute(
                "--totals",
                "-cpf",
                "-",
                src,
                "|",
                extra,
                ">",
                SymbolUtil.shellQuote(dst),
            )
        }

        // Move back
        BaseUtil.execute("cd", "/")

        return result
    }

    /**
     * 方案② 本机快照：只打 [listFile] 里列出的条目，不整目录打包。
     *
     * [listFile] 每行一条相对 [srcDir] 的路径（如 `DCIM/a.jpg`），由调用方用
     * `rootService.writeText` 写好；命令行先 `cd` 到 [srcDir]，所以归档里的路径
     * 与 `MediumBackupUtil` 打包出来的完全同构，现有恢复链路不用改。
     * 只打冲突文件而不是整个相册目录，是为了避免几十 GB 的无谓 IO。
     */
    suspend fun compressFromList(srcDir: String, listFile: String, dst: String, extra: String): ShellResult {
        BaseUtil.execute("cd", SymbolUtil.shellQuote(srcDir))
        return try {
            if (extra.isEmpty()) {
                // tar --totals -cpf - -T "$listFile" > "$dst"
                execute(
                    "--totals",
                    "-cpf",
                    "-",
                    "-T",
                    SymbolUtil.shellQuote(listFile),
                    ">",
                    SymbolUtil.shellQuote(dst),
                )
            } else {
                // tar --totals -cpf - -T "$listFile" | $extra > "$dst"
                execute(
                    "--totals",
                    "-cpf",
                    "-",
                    "-T",
                    SymbolUtil.shellQuote(listFile),
                    "|",
                    extra,
                    ">",
                    SymbolUtil.shellQuote(dst),
                )
            }
        } finally {
            // `cd` 改的是进程级 root shell 的 cwd；异常时也必须复位，
            // 否则后续所有命令都会在错误目录执行。
            BaseUtil.execute("cd", "/")
        }
    }

    suspend fun compress(exclusionList: List<String>, h: String, srcDir: String, src: String, dst: String, extra: String): ShellResult =
        run {
            val exclusion = exclusionList.trim().map { "--exclude=${SymbolUtil.shellQuote(it)}" }.toSpaceString()
            if (extra.isEmpty()) {
                // tar --totals "$exclusion" $h -cpf - -C "$srcDir" -- "$src" > "$dst"
                execute(
                    "--totals",
                    exclusion,
                    h,
                    "-cpf",
                    "-",
                    "-C",
                    SymbolUtil.shellQuote(srcDir),
                    "--",
                    SymbolUtil.shellQuote(src),
                    ">",
                    SymbolUtil.shellQuote(dst),
                )
            } else {
                // tar --totals "$exclusion" $h -cpf - -C "$srcDir" -- "$src" | $extra > "$dst"
                execute(
                    "--totals",
                    exclusion,
                    h,
                    "-cpf",
                    "-",
                    "-C",
                    SymbolUtil.shellQuote(srcDir),
                    "--",
                    SymbolUtil.shellQuote(src),
                    "|",
                    extra,
                    ">",
                    SymbolUtil.shellQuote(dst),
                )
            }
        }

    suspend fun test(src: String, extra: String): ShellResult = if (extra.isEmpty()) {
        // tar -tf "$src" > /dev/null 2>&1
        execute(
            "-tf",
            SymbolUtil.shellQuote(src),
            ">",
            "/dev/null",
            "2>&1",
        )
    } else {
        // zstd -d -c "$src" | tar -tf - > /dev/null 2>&1
        BaseUtil.execute(
            "zstd",
            "-d",
            "-c",
            SymbolUtil.shellQuote(src),
            "|",
            "tar",
            "-tf",
            "-",
            ">",
            "/dev/null",
            "2>&1",
        )
    }

    suspend fun decompress(src: String, dst: String, extra: String): ShellResult = run {
        if (extra.isEmpty()) {
            // tar --totals -xmpf "$src" -C "$dst"
            execute(
                "--totals",
                "-xmpf",
                SymbolUtil.shellQuote(src),
                "-C",
                SymbolUtil.shellQuote(dst),
            )
        } else {
            // zstd -d -c "$src" | tar --totals -xmpf - -C "$dst"
            BaseUtil.execute(
                "zstd",
                "-d",
                "-c",
                SymbolUtil.shellQuote(src),
                "|",
                "tar",
                "--totals",
                "-xmpf",
                "-",
                "-C",
                SymbolUtil.shellQuote(dst),
            )
        }
    }

    /**
     * [clear] 现在是「恢复冲突参数」，由 `resolveRestoreConflictArgs()` 从枚举生成：
     * `--overwrite` / `--skip-old-files` / `--backup=numbered` / `--recursive-unlink` 之一。
     * 调用方不得传入外部自由文本（会拼进 root shell 命令）。
     */
    suspend fun decompress(exclusionList: List<String>, clear: String, m: Boolean, src: String, dst: String, extra: String): ShellResult = run {
        val exclusion = exclusionList.trim().map { "--exclude=${SymbolUtil.shellQuote(it)}" }.toSpaceString()
        if (extra.isEmpty()) {
            // tar --totals "$exclusion" $clear -xmpf "$src" -C "$dst"
            execute(
                "--totals",
                exclusion,
                clear,
                if (m) "-xmpf" else "-xpf",
                SymbolUtil.shellQuote(src),
                "-C",
                SymbolUtil.shellQuote(dst),
            )
        } else {
            // zstd -d -c "$src" | tar --totals "$exclusion" $clear -xmpf - -C "$dst"
            BaseUtil.execute(
                "zstd",
                "-d",
                "-c",
                SymbolUtil.shellQuote(src),
                "|",
                "tar",
                "--totals",
                exclusion,
                clear,
                if (m) "-xmpf" else "-xpf",
                "-",
                "-C",
                SymbolUtil.shellQuote(dst),
            )
        }
    }
}
