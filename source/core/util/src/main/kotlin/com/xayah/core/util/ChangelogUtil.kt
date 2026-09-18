package com.xayah.core.util

import android.content.Context

/** 更新日志里的一个小节（如「修复」「优化」） */
data class ChangelogSection(
    val title: String,
    val items: List<String>,
)

/** 更新日志里的一个版本段 */
data class ChangelogVersion(
    val version: String,
    val date: String,
    val sections: List<ChangelogSection>,
)

/** 打包进 assets 的更新日志文件名（构建期由 Gradle 从仓库根 CHANGELOG.md 拷入） */
const val CHANGELOG_ASSET = "CHANGELOG.md"

private val VERSION_REGEX = Regex("""^##\s+v?([0-9][0-9A-Za-z.\-_]*)\s*(?:[（(]\s*([^）)]*?)\s*[）)])?\s*$""")
private val SECTION_REGEX = Regex("""^###+\s+(.+?)\s*$""")
private val ITEM_REGEX = Regex("""^[-*]\s+(.+?)\s*$""")

/**
 * 解析 CHANGELOG.md 文本。
 *
 * 只认这一种结构（仓库根的 CHANGELOG.md 就是这个格式，由 release-please 维护）：
 * ```
 * ## v3.12.1（2026-09-15）      ← 版本段（日期可省略）
 * ### 修复                      ← 小节
 * - **标题**：说明                ← 条目
 * ```
 * 首个 `## ` 之前的内容（文件标题、说明段落）忽略；条目下的续行会并回上一条目。
 * 返回顺序与文件一致（文件是**新版本在上**）。
 */
fun parseChangelog(markdown: String): List<ChangelogVersion> {
    val versions = mutableListOf<ChangelogVersion>()
    var version: String? = null
    var date: String = ""
    var sections = mutableListOf<ChangelogSection>()
    var sectionTitle: String? = null
    var items = mutableListOf<String>()

    fun flushSection() {
        val title = sectionTitle ?: return
        if (items.isEmpty()) return
        sections += ChangelogSection(title = title, items = items.toList())
        items = mutableListOf()
    }

    fun flushVersion() {
        flushSection()
        val v = version ?: return
        versions += ChangelogVersion(version = v, date = date, sections = sections.toList())
        sections = mutableListOf()
        sectionTitle = null
        items = mutableListOf()
    }

    markdown.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty()) return@forEach

        val versionMatch = VERSION_REGEX.find(line)
        if (versionMatch != null) {
            flushVersion()
            version = versionMatch.groupValues[1]
            date = versionMatch.groupValues.getOrNull(2).orEmpty().trim()
            return@forEach
        }
        // 版本段之前的内容不支持也不关心
        if (version == null) return@forEach

        val sectionMatch = SECTION_REGEX.find(line)
        if (sectionMatch != null) {
            flushSection()
            sectionTitle = sectionMatch.groupValues[1]
            return@forEach
        }

        val itemMatch = ITEM_REGEX.find(line)
        if (itemMatch != null) {
            items += itemMatch.groupValues[1]
            return@forEach
        }

        // 续行：并回上一条目（避免硬换行的长条目被拆断）
        if (items.isNotEmpty()) {
            items[items.lastIndex] = "${items.last()}$line"
        }
    }
    flushVersion()
    return versions
}

/** 读 assets 里打包的 CHANGELOG.md；读不到返回空串（页面自行做空态展示） */
fun Context.readChangelogText(): String = runCatching {
    assets.open(CHANGELOG_ASSET).bufferedReader().use { it.readText() }
}.getOrDefault("")

/** 读并解析 assets 里的 CHANGELOG.md */
fun Context.readChangelog(): List<ChangelogVersion> = parseChangelog(readChangelogText())
