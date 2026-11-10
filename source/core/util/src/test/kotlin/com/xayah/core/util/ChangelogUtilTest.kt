package com.xayah.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析仓库根 CHANGELOG.md 的结构。
 *
 * 格式由 release-please 维护、也是应用内「更新日志」页的数据源，所以这里把它锁住：
 * 版本段 / 小节 / 条目、半角与全角括号、无日期的版本段、条目续行、以及首个版本段之前的说明被忽略。
 */
class ChangelogUtilTest {
    private val sample = """
        # 更新日志

        本文件记录定制版相对原版的改动。

        ## v3.12.1（2026-09-15）

        ### 修复

        - **扫描应用列表时主线程被通知刷新占满**：说明文字。
        - 一条不带加粗的条目
          被硬换行的续行
        - 第三条

        ### 优化

        - 优化项

        ## v3.12.0 (2026-09-14)

        - 没有小节标题的条目

        ## v3.11.0

        ### 新增

        - 老版本条目
    """.trimIndent()

    @Test
    fun `parses versions sections and items in file order`() {
        val versions = parseChangelog(sample)

        assertEquals(3, versions.size)
        // 文件是新版本在上，解析顺序应与之一致
        assertEquals(listOf("3.12.1", "3.12.0", "3.11.0"), versions.map { it.version })
        assertEquals("2026-09-15", versions[0].date)
        // 半角括号的日期也要认
        assertEquals("2026-09-14", versions[1].date)
        // 无日期
        assertEquals("", versions[2].date)
    }

    @Test
    fun `keeps section grouping and joins wrapped item lines`() {
        val v = parseChangelog(sample).first()

        assertEquals(listOf("修复", "优化"), v.sections.map { it.title })
        val fixItems = v.sections.first().items
        assertEquals(3, fixItems.size)
        assertEquals("**扫描应用列表时主线程被通知刷新占满**：说明文字。", fixItems[0])
        // 续行应并回上一条目，而不是变成新条目
        assertTrue(fixItems[1].startsWith("一条不带加粗的条目"))
        assertTrue(fixItems[1].contains("被硬换行的续行"))
    }

    @Test
    fun `ignores preamble before the first version`() {
        val versions = parseChangelog(sample)
        val all = versions.flatMap { v -> v.sections.flatMap { it.items } }
        assertTrue(all.none { it.contains("本文件记录定制版") })
    }

    @Test
    fun `empty input yields no versions`() {
        assertTrue(parseChangelog("").isEmpty())
        assertTrue(parseChangelog("# 只有标题\n\n没有版本段").isEmpty())
    }

    @Test
    fun `callouts comments code blocks and tables are never joined into items`() {
        val md = """
            ## v3.15.3（2026-11-10）
            ### 修复
            - **修了一个缺陷**：说明。
            > [!warning] 这是给维护者看的提示
            > 第二行引用
            %%维护约定：别改历史%%
            <!-- HTML 注释 -->
            | 能力 | 状态 |
            |---|---|
            ---
            ```
            ## v9.9.9 代码块里的假版本
            - 假条目
            ```
              被硬换行的续行
            - 第二条
        """.trimIndent()
        val items = parseChangelog(md).single().sections.single().items

        assertEquals(2, items.size)
        assertEquals("**修了一个缺陷**：说明。被硬换行的续行", items[0])
        assertEquals("第二条", items[1])
        assertTrue(items.none { it.contains("[!warning]") || it.contains("v9.9.9") || it.contains("维护约定") })
    }

    @Test
    fun `wrapped lines add a space only between ascii words`() {
        val md = """
            ## v1.0
            ### 修复
            - 中文开头
              中文续行
            - word
              next
        """.trimIndent()
        val items = parseChangelog(md).single().sections.single().items

        assertEquals("中文开头中文续行", items[0])
        assertEquals("word next", items[1])
    }
}
