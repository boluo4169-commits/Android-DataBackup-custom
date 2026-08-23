package com.xayah.core.util

object SymbolUtil {
    const val USD = '$'
    const val BACKSLASH = '\\'
    const val QUOTE = '"'
    const val LF = '\n'
    const val DOT = '•'

    /**
     * POSIX sh 单引号安全包裹。
     * 双引号包裹无法防御内嵌双引号/$/反引号导致的 root shell 命令注入，
     * 单引号内除自身外所有字符均为字面量，转义自身即可保证安全：
     * 例: a'b -> 'a'\''b'
     */
    fun shellQuote(s: String): String = "'${s.replace("'", "'\\''")}'"
}
