package com.xayah.core.util.command

import com.xayah.core.common.util.toSpaceString
import com.xayah.core.common.util.trim
import com.xayah.core.util.SymbolUtil
import com.xayah.core.util.SymbolUtil.shellQuote
import com.xayah.core.util.model.ShellResult

object Tree {
    private suspend fun execute(vararg args: String): ShellResult = BaseUtil.execute("tree", *args)
    suspend fun tree(src: String, exclusionList: List<String>): ShellResult = run {
        val exclusion = exclusionList.trim().map { "-I ${shellQuote(it)}" }.toSpaceString()
        // tree -N "$src" '$exclusion'
        execute(
            "-N",
            shellQuote(src),
            exclusion,
        )
    }
}
