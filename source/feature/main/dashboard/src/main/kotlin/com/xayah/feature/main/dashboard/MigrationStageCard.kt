package com.xayah.feature.main.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xayah.core.ui.component.BodyLargeText
import com.xayah.core.ui.component.HeadlineSmallText
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens

/**
 * 数据迁移进度状态。
 *  - Idle：未开始（不渲染任何内容，页面保持列表视图）
 *  - Processing：导出/导入进行中（顶部进度条 + 阶段文字）
 *  - Success：完成（对勾 + 阶段文字）
 *  - Failure：失败（仅提示，不渲染）
 */
enum class MigrationStage { Idle, Processing, Success, Failure }

/**
 * 数据迁移处理进度区：轻量方案（方案 B）——
 * 处理中显示顶部总进度条（indeterminate 动画）+ 当前阶段文字；完成显示对勾 + 文字。
 * 仅在 stage != Idle 时调用（Idle 时不渲染，保持页面清爽）。
 */
@Composable
fun MigrationStageCard(
    stage: MigrationStage,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    if (stage == MigrationStage.Idle || stage == MigrationStage.Failure) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = SizeTokens.Level24),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SizeTokens.Level12),
    ) {
        when (stage) {
            MigrationStage.Processing -> {
                // 顶部总进度条（indeterminate 动画）
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                )
            }

            MigrationStage.Success -> {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF3B6D11), // green 600
                    modifier = Modifier.size(SizeTokens.Level56),
                )
            }

            else -> {}
        }
        HeadlineSmallText(text = title)
        BodyLargeText(
            modifier = Modifier.padding(top = SizeTokens.Level4),
            text = description,
            color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
            textAlign = TextAlign.Center,
        )
    }
}