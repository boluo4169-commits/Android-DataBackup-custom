package com.xayah.core.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens

/**
 * Staged progress: a row of segments, only one is active.
 *  - segments[i] before current: fully filled (completed)
 *  - segments[current]: filled by [currentProgress] (0f..1f) with smooth animation
 *  - segments[i] after current: empty track
 *  - 当 [activeIndefinite] = true（数据无法实时计算进度的阶段，如上传），当前段改为 Shimmer
 *    亮带扫过效果，忽略 [currentProgress]。
 * 段间用空白 gap 分隔。
 */
@Composable
fun SegmentedLinearProgressIndicator(
    segmentCount: Int,
    currentIndex: Int,
    currentProgress: Float,
    modifier: Modifier = Modifier,
    color: Color = ThemedColorSchemeKeyTokens.Primary.value,
    trackColor: Color = ThemedColorSchemeKeyTokens.SurfaceVariant.value,
    height: Dp = 6.dp,
    gap: Dp = SizeTokens.Level2,
    activeIndefinite: Boolean = false,
) {
    val safeCount = segmentCount.coerceAtLeast(1)
    val safeIndex = currentIndex.coerceIn(0, safeCount - 1)
    val animatedProgress by animateFloatAsState(
        targetValue = currentProgress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 200),
        label = "segmented-progress",
    )
    // 不定态段的扫描亮带：左→右循环。亮块宽为段的 35%，按自身宽度换算可移动距离。
    val sweepFraction by rememberInfiniteTransition(label = "segmented-sweep").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 1200, easing = LinearEasing)),
        label = "segmented-sweep-fraction",
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        repeat(safeCount) { i ->
            val isCompleted = i < safeIndex
            val isActive = i == safeIndex
            // 不定态段**不填充**：填满会让人误以为这步已经做完，只用扫描亮带表示"正在跑"。
            val fillFraction = when {
                isCompleted -> 1f
                isActive && activeIndefinite.not() -> animatedProgress
                else -> 0f
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(height)
                    .clip(RoundedCornerShape(height / 2))
                    .background(trackColor),
            ) {
                if (fillFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fillFraction)
                            .height(height)
                            .clip(RoundedCornerShape(height / 2))
                            .background(color),
                    )
                }
                // 当前段且 indeterminate 时叠加左→右扫描亮带（不用 shimmer：那会盖成灰块）
                if (isActive && activeIndefinite) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.35f)
                            .height(height)
                            .align(Alignment.CenterStart)
                            .graphicsLayer {
                                translationX = sweepFraction * size.width * (1f / 0.35f - 1f)
                            }
                            .clip(RoundedCornerShape(height / 2))
                            .background(color),
                    )
                }
            }
        }
    }
}
