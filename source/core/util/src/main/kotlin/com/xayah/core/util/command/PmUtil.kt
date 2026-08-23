package com.xayah.core.util.command

import android.os.Build
import com.xayah.core.util.SymbolUtil.shellQuote
import com.xayah.core.util.model.ShellResult

object Pm {
    private suspend fun execute(vararg args: String): ShellResult = BaseUtil.execute("pm", *args)

    suspend fun install(userId: Int, src: String): ShellResult = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        // pm install --user "$userId" -r -t -d "$src"
        execute(
            "install",
            "--user",
            "$userId",
            "-r",
            "-t",
            "-d",
            shellQuote(src),
        )
    } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU) {
        // pm install -i com.android.vending --user "$userId" -r -t -d "$src"
        execute(
            "install",
            "-i",
            "com.android.vending",
            "--user",
            "$userId",
            "-r",
            "-t",
            "-d",
            shellQuote(src),
        )
    } else {
        // pm install --bypass-low-target-sdk-block -i com.android.vending --user "$userId" -r -t -d "$src"
        execute(
            "install",
            "--bypass-low-target-sdk-block",
            "-i",
            "com.android.vending",
            "--user",
            "$userId",
            "-r",
            "-t",
            "-d",
            shellQuote(src),
        )
    }

    object Install {
        suspend fun create(userId: Int): ShellResult = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // pm install-create --user "$userId" -t -d | grep -E -o '[0-9]+'
            execute(
                "install-create",
                "--user",
                "$userId",
                "-t",
                "-d",
                "|",
                "grep -E -o '[0-9]+'",
            )
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU) {
            // pm install-create -i com.android.vending --user "$userId" -t -d | grep -E -o '[0-9]+'
            execute(
                "install-create",
                "-i",
                "com.android.vending",
                "--user",
                "$userId",
                "-t",
                "-d",
                "|",
                "grep -E -o '[0-9]+'",
            )
        } else {
            // pm install-create --bypass-low-target-sdk-block -i com.android.vending --user "$userId" -t -d | grep -E -o '[0-9]+'
            execute(
                "install-create",
                "--bypass-low-target-sdk-block",
                "-i",
                "com.android.vending",
                "--user",
                "$userId",
                "-t",
                "-d",
                "|",
                "grep -E -o '[0-9]+'",
            )
        }

        suspend fun write(session: String, srcName: String, src: String): ShellResult = run {
            // pm install-write "$session" "$srcDir" "$src"
            execute(
                "install-write",
                shellQuote(session),
                shellQuote(srcName),
                shellQuote(src),
            )
        }

        suspend fun commit(session: String): ShellResult = run {
            // pm install-commit "$session"
            execute(
                "install-commit",
                shellQuote(session),
            )
        }
    }
}
