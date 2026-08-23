package com.xayah.core.util.command

import com.xayah.core.common.util.toSpaceString
import com.xayah.core.common.util.trim
import com.xayah.core.util.SymbolUtil
import com.xayah.core.util.model.ShellResult

object Tar {
    private suspend fun execute(vararg args: String): ShellResult = BaseUtil.execute("tar", *args)

    suspend fun compressInCur(cur: String, src: String, dst: String, extra: String): ShellResult {
        // Move to $cur path.
        BaseUtil.execute("cd", SymbolUtil.shellQuote(cur))

        // Compress
        val result = if (extra.isEmpty()) {
            // tar --totals -cpf - "$src" > "$dst"
            execute(
                "--totals",
                "-cpf",
                "-",
                SymbolUtil.shellQuote(src),
                ">",
                SymbolUtil.shellQuote(dst),
            )
        } else {
            // tar --totals -cpf - "$src" | $extra > "$dst"
            execute(
                "--totals",
                "-cpf",
                "-",
                SymbolUtil.shellQuote(src),
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
