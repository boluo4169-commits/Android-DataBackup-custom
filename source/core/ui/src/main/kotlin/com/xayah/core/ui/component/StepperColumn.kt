package com.xayah.core.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens

/**
 * 单个步骤的视觉状态。
 *  - Pending：未到达（空心圆 + 灰线）
 *  - Active：到达但进行中（实心圆 + 主色 + 外圈旋转弧）
 *  - Done：已完成（实心圆 + 主色 + 内嵌勾）
 *
 * 进行中的强调**不使用** [shimmer]：那是 accompanist 的 placeholder，会把文字替换成灰块，
 * 导致"进度中反而读不到字"。改用旋转弧 + 呼吸透明度（见 [StepperRow]）。
 */
sealed interface StepperState {
    data object Pending : StepperState
    data object Active : StepperState
    data object Done : StepperState
}

/** 单个步骤的内容与状态。[detail] 为该行的辅助信息（如"8.2 GB"），为 null 时不显示。 */
data class StepperItem(
    val title: String,
    val state: StepperState,
    val detail: String? = null,
)

/**
 * 纵向 Stepper：节点 + 连线 + 文字。已点亮的段（Active/Done）会带 Shimmer 强调"内容仍在加载"。
 * 所有步骤在父容器中**等高**分布（用 weight(1f) 强制），连线贯穿各段。
 */
@Composable
fun StepperColumn(
    items: List<StepperItem>,
    modifier: Modifier = Modifier,
    activeColor: Color = ThemedColorSchemeKeyTokens.Primary.value,
    trackColor: Color = ThemedColorSchemeKeyTokens.SurfaceVariant.value,
    nodeSize: Dp = SizeTokens.Level24,
    lineWidth: Dp = SizeTokens.Level2,
    horizontalGap: Dp = SizeTokens.Level16,
) {
    if (items.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        items.forEachIndexed { index, item ->
            val isLast = index == items.lastIndex
            val nextState = items.getOrNull(index + 1)?.state
            val lineColor = when {
                // 已完成段之后是 Done/Active：连线为 active
                item.state == StepperState.Done -> activeColor
                item.state == StepperState.Active &&
                    (nextState == StepperState.Active || nextState == StepperState.Done) -> activeColor
                else -> trackColor
            }
            // 用 weight(1f) 让所有行等高,fillMaxHeight(1f) 让连线贯穿整行
            StepperRow(
                item = item,
                isLast = isLast,
                lineColor = lineColor,
                activeColor = activeColor,
                trackColor = trackColor,
                nodeSize = nodeSize,
                lineWidth = lineWidth,
                horizontalGap = horizontalGap,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true),
            )
        }
    }
}

@Composable
private fun StepperRow(
    item: StepperItem,
    isLast: Boolean,
    lineColor: Color,
    activeColor: Color,
    trackColor: Color,
    nodeSize: Dp,
    lineWidth: Dp,
    horizontalGap: Dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top,
    ) {
        // 节点 + 连线（整列等宽）
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(nodeSize)
                .fillMaxHeight(),
        ) {
            StepperNode(
                state = item.state,
                activeColor = activeColor,
                trackColor = trackColor,
                size = nodeSize,
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(lineWidth)
                        .fillMaxHeight()
                        .background(lineColor),
                )
            }
        }
        // 段名 + 辅助信息（顶部对齐节点,垂直居中于行）
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .padding(start = horizontalGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 进行中用呼吸透明度强调（文字始终可读），不用 placeholder 遮挡。
            val textModifier = if (item.state == StepperState.Active) {
                val transition = rememberInfiniteTransition(label = "stepper-breathe")
                val alpha by transition.animateFloat(
                    initialValue = 0.6f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 1200),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "stepper-breathe-alpha",
                )
                Modifier.alpha(alpha)
            } else {
                Modifier
            }
            val textColor = when (item.state) {
                StepperState.Pending -> ThemedColorSchemeKeyTokens.Outline.value
                else -> activeColor
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = textColor,
                modifier = textModifier,
            )
            item.detail?.let { detail ->
                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ThemedColorSchemeKeyTokens.Outline.value,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // 占满剩余宽度并右对齐，长文案不会把阶段名挤出屏幕
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = horizontalGap),
                    )
                }
            }
        }
    }
}

@Composable
private fun StepperNode(state: StepperState, activeColor: Color, trackColor: Color, size: Dp) {
    when (state) {
        StepperState.Pending -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(size - SizeTokens.Level6)
                        .clip(CircleShape)
                        .background(trackColor),
                )
            }
        }
        StepperState.Active -> {
            // 实心圆 + 外圈旋转弧：既看得出"在进行"，又不遮挡任何文字。
            val transition = rememberInfiniteTransition(label = "stepper-spin")
            val angle by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1000, easing = LinearEasing),
                ),
                label = "stepper-spin-angle",
            )
            Box(
                modifier = Modifier.size(size),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize().rotate(angle)) {
                    drawArc(
                        color = activeColor,
                        startAngle = -90f,
                        sweepAngle = 300f,
                        useCenter = false,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(size * 0.45f)
                        .clip(CircleShape)
                        .background(activeColor),
                )
            }
        }
        StepperState.Done -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(activeColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.6f),
                )
            }
        }
    }
}
