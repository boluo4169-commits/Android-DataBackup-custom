package com.xayah.core.ui.component

import androidx.compose.animation.core.animateFloatAsState
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
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        repeat(safeCount) { i ->
            val isCompleted = i < safeIndex
            val isActive = i == safeIndex
            val fillFraction = when {
                isCompleted -> 1f
                isActive && activeIndefinite.not() -> animatedProgress
                isActive && activeIndefinite -> 1f
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
                // 当前段且 indeterminate 时叠加 Shimmer 亮带
                if (isActive && activeIndefinite) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(1f)
                            .height(height)
                            .shimmer(),
                    )
                }
            }
        }
    }
}
