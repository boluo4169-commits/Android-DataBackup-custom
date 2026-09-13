package com.xayah.feature.main.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xayah.core.ui.component.BodyLargeText
import com.xayah.core.ui.component.HeadlineSmallText
import com.xayah.core.ui.component.SegmentedLinearProgressIndicator
import com.xayah.core.ui.component.StepperColumn
import com.xayah.core.ui.component.StepperItem
import com.xayah.core.ui.component.StepperState
import com.xayah.core.ui.component.shimmer
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens

/**
 * 数据迁移进度状态。
 *  - Idle：未开始（不渲染任何内容，页面保持列表视图）
 *  - Processing：导出/导入进行中（顶部标题 + 纵向 Stepper）
 *  - Success：完成（对勾 + 阶段文字）
 *  - Failure：失败（仅提示，不渲染）
 */
enum class MigrationStage { Idle, Processing, Success, Failure }

/**
 * 数据迁移处理进度区。
 *  - Processing 时若传入了 [stages]，渲染标题 + 纵向 Stepper 竖列填满中间空白；
 *    所有"已点亮"的段（Active/Done）都带 Shimmer 强调"内容仍在加载"。
 *  - Success 时显示对勾 + 文字。
 *  - Idle / Failure 不渲染。
 */
@Composable
fun MigrationStageCard(
    stage: MigrationStage,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    stages: List<String> = emptyList(),
    currentStageIndex: Int = 0,
    currentStageProgress: Float = 0f,
    /** 每段的辅助信息（如"8.2 GB"、"63%"），与 [stages] 等长，缺省为空。 */
    stageDetails: List<String> = emptyList(),
    /** 当前段的量化信息（如"已写入 8.2 GB · 00:42 · 877 MiB/s"），为空时回落到 [description]。 */
    detail: String? = null,
    /** 当前段无法计算真实进度时置 true，进度条该段走不定态扫描。 */
    activeIndefinite: Boolean = false,
) {
    if (stage == MigrationStage.Idle || stage == MigrationStage.Failure) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .padding(vertical = SizeTokens.Level24, horizontal = SizeTokens.Level24),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SizeTokens.Level16),
    ) {
        when (stage) {
            MigrationStage.Processing -> {
                val idx = if (stages.isNotEmpty()) currentStageIndex.coerceIn(0, stages.lastIndex) else 0
                // 主标题直接给出"当前在做什么"，而不是一句孤立的「第 X / N 步」
                Text(
                    text = stages.getOrNull(idx) ?: title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = ThemedColorSchemeKeyTokens.OnSurface.value,
                    textAlign = TextAlign.Center,
                )
                // 副行：量化信息（已写入/百分比/速度）；算不出时回落到描述文案。
                // 这里**不加** shimmer —— 那会把文字替换成灰块，等于不让人读。
                Text(
                    text = detail?.takeIf { it.isNotEmpty() } ?: description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = SizeTokens.Level4),
                )
                if (stages.isNotEmpty()) {
                    // 分段进度条：已完成段全满、当前段按真实进度。
                    // 进度还停在 0（要么本就算不出，要么调用方只推 0/1，如导入页）时走不定态扫描，
                    // 否则当前段会是一根完全静止的空槽，看着像卡住。
                    SegmentedLinearProgressIndicator(
                        segmentCount = stages.size,
                        currentIndex = idx,
                        currentProgress = currentStageProgress,
                        activeIndefinite = activeIndefinite || currentStageProgress <= 0f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = SizeTokens.Level16),
                    )
                    val items = stages.mapIndexed { index, label ->
                        val state = when {
                            index < currentStageIndex -> StepperState.Done
                            index == currentStageIndex -> StepperState.Active
                            else -> StepperState.Pending
                        }
                        StepperItem(
                            title = label,
                            state = state,
                            detail = stageDetails.getOrNull(index),
                        )
                    }
                    StepperColumn(
                        items = items,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = SizeTokens.Level8)
                            .height(240.dp),
                    )
                } else {
                    // 旧路径（导入页等未启用分段的场景）
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                    )
                }
            }

            MigrationStage.Success -> {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF3B6D11), // green 600
                    modifier = Modifier.size(SizeTokens.Level56),
                )
                HeadlineSmallText(text = title)
                BodyLargeText(
                    modifier = Modifier.padding(top = SizeTokens.Level4),
                    text = description,
                    color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                    textAlign = TextAlign.Center,
                )
            }

            else -> {}
        }
    }
}
