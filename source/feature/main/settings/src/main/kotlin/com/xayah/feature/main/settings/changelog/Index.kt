package com.xayah.feature.main.settings.changelog

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.xayah.core.common.util.BuildConfigUtil
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.InnerBottomSpacer
import com.xayah.core.ui.component.LabelMediumText
import com.xayah.core.ui.component.Section
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.util.ChangelogVersion
import com.xayah.core.util.readChangelog
import com.xayah.feature.main.settings.R
import com.xayah.feature.main.settings.SettingsScaffold

/**
 * 更新日志：当前版本 + 历史版本。
 *
 * 数据来自打包进 assets 的 `CHANGELOG.md`（构建期由 Gradle 从仓库根拷入，单一来源），
 * 入口有两个：主页顶栏的版本号徽章、设置 → 关于 里的「更新日志」。
 */
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageChangelog() {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    // 静态文本、解析一次即可（当前 CHANGELOG 约 300 行，开销可忽略）
    val versions = remember { context.readChangelog() }
    val currentVersion = BuildConfigUtil.VERSION_NAME
    val currentLabel = stringResource(id = R.string.changelog_current)

    SettingsScaffold(
        scrollBehavior = scrollBehavior,
        title = stringResource(id = R.string.changelog),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level24)
        ) {
            if (versions.isEmpty()) {
                BodyMediumText(
                    modifier = Modifier.paddingHorizontal(SizeTokens.Level16),
                    text = stringResource(id = R.string.changelog_empty),
                    color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                )
            } else {
                versions.forEach { version ->
                    Section(title = version.title(isCurrent = version.version == currentVersion, currentLabel = currentLabel)) {
                        version.sections.forEach { section ->
                            LabelMediumText(
                                text = section.title,
                                color = ThemedColorSchemeKeyTokens.Primary.value,
                                fontWeight = FontWeight.SemiBold,
                            )
                            section.items.forEach { item -> ChangelogItem(item = item) }
                        }
                    }
                }
            }
            InnerBottomSpacer(innerPadding = it)
        }
    }
}

/** 版本段标题：`v3.12.1　2026-09-15　· 当前版本` */
private fun ChangelogVersion.title(isCurrent: Boolean, currentLabel: String): String = buildString {
    append("v")
    append(version)
    if (date.isNotEmpty()) {
        append("　")
        append(date)
    }
    if (isCurrent) {
        append("　· ")
        append(currentLabel)
    }
}

/**
 * 一条更新内容。
 *
 * CHANGELOG 的条目是 `- **标题**：说明` 的形式，这里把标题和说明拆两行显示（标题加粗），
 * 长说明读起来更清楚；没有冒号分隔的条目整条展示。
 */
@Composable
private fun ChangelogItem(item: String) {
    val clean = item.replace("**", "").replace("`", "")
    val head = clean.substringBefore("：", missingDelimiterValue = "")
    val rest = clean.substringAfter("：", missingDelimiterValue = "")
    val hasHead = head.isNotEmpty() && rest.isNotEmpty() && head.length <= HEAD_MAX_LENGTH

    Column(modifier = Modifier.paddingHorizontal(SizeTokens.Level16), verticalArrangement = Arrangement.spacedBy(SizeTokens.Level4)) {
        if (hasHead) {
            LabelMediumText(text = "· $head", fontWeight = FontWeight.SemiBold)
            BodyMediumText(
                text = rest,
                color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
            )
        } else {
            BodyMediumText(
                text = "· $clean",
                color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
            )
        }
    }
}

/** 超过该长度的"标题"多半其实是正文，不做加粗拆分 */
private const val HEAD_MAX_LENGTH = 40
