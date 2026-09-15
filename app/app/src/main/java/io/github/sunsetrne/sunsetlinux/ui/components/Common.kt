package io.github.sunsetrne.sunsetlinux.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sunsetrne.sunsetlinux.core.EnvState
import io.github.sunsetrne.sunsetlinux.ui.theme.Line
import io.github.sunsetrne.sunsetlinux.ui.theme.LineStrong
import io.github.sunsetrne.sunsetlinux.ui.theme.Mono1
import io.github.sunsetrne.sunsetlinux.ui.theme.Mono2
import io.github.sunsetrne.sunsetlinux.ui.theme.OnAccent
import io.github.sunsetrne.sunsetlinux.ui.theme.Mono3
import io.github.sunsetrne.sunsetlinux.ui.theme.MonoFamily
import io.github.sunsetrne.sunsetlinux.ui.theme.TextMuted
import io.github.sunsetrne.sunsetlinux.ui.theme.TextSecondary
import io.github.sunsetrne.sunsetlinux.ui.theme.stateColor

/**
 * 悬浮胶囊底栏会盖住滚动内容的底部 —— 各面板末尾留这么多空白。
 *
 * ⚠️ 必须是**一个常量**：这个数字原先在 4 个地方各写了一遍 `96.dp`，改一处就漏三处
 * （漏掉的那个面板最后一张卡片会被胶囊压住，用户滚到底也看不见）。
 */
val CapsuleReserve: Dp = 96.dp

/** 统一卡片：圆角 + 细描边，视觉上比默认 Material Card 更"仪表盘"。 */
@Composable
fun DshCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    highlighted: Boolean = false,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Surface(
        modifier = modifier
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = shape,
        color = Mono1,
        border = BorderStroke(1.dp, if (highlighted) LineStrong else Line),
    ) {
        Box(Modifier.padding(18.dp)) { content() }
    }
}

/** 小胶囊标签：模式徽章、层版本、dist-tag 等。 */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    filled: Boolean = false,
    leadingDot: Boolean = false,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = if (filled) color.copy(alpha = 0.18f) else Color.Transparent,
        border = BorderStroke(1.dp, color.copy(alpha = if (filled) 0.45f else 0.6f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (leadingDot) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(color),
                )
            }
            Text(text = text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
        }
    }
}

/**
 * 状态圆点：运行中/启动中/停止中带一圈呼吸光晕。
 * 纯 Canvas 绘制，不依赖任何图标资源。
 */
@Composable
fun StatusDot(state: EnvState, dotSize: Dp = 13.dp) {
    val color = stateColor(state)
    val animated = state == EnvState.RUNNING || state == EnvState.STARTING || state == EnvState.STOPPING
    val transition = rememberInfiniteTransition(label = "statusDot")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse",
    )

    // 单色方案里颜色不再区分状态 —— 靠**形状 + 明度**：
    // 运行＝实心纯白（+呼吸光晕）、过渡＝空心环（中灰）、已停止＝实心暗灰、
    // 错误＝叉号（纯白）、未知＝空心环（弱灰）。
    val hollow = state == EnvState.STARTING || state == EnvState.STOPPING || state == EnvState.UNKNOWN
    val isCross = state == EnvState.ERROR

    Canvas(Modifier.size(dotSize * 2)) {
        val radius = dotSize.toPx() / 2f
        val stroke = dotSize.toPx() * 0.18f
        if (animated) {
            drawCircle(color = color.copy(alpha = 0.28f * (1f - pulse)), radius = radius * (1f + pulse))
        }
        when {
            isCross -> {
                // 叉号：错误态在单色下最怕被忽略，用形状强制区分
                val r = radius * 0.92f
                drawLine(
                    color = color,
                    start = Offset(radius - r, radius - r),
                    end = Offset(radius + r, radius + r),
                    strokeWidth = stroke * 1.5f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(radius + r, radius - r),
                    end = Offset(radius - r, radius + r),
                    strokeWidth = stroke * 1.5f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }

            hollow -> drawCircle(
                color = color,
                radius = radius - stroke / 2f,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
            )

            else -> {
                drawCircle(color = color, radius = radius)
                if (state == EnvState.RUNNING) {
                    drawCircle(color = Color.Black.copy(alpha = 0.35f), radius = radius * 0.34f)
                }
            }
        }
    }
}

/**
 * 播放三角 / 停止方块：自己画。
 * 为这两个图形引入 material-icons-extended（数十 MB）不划算。
 */
@Composable
fun ActionGlyph(stopping: Boolean, color: Color, glyphSize: Dp = 22.dp) {
    Canvas(Modifier.size(glyphSize)) {
        val w = size.width
        val h = size.height
        if (stopping) {
            val side = size.minDimension * 0.62f
            drawRoundRect(
                color = color,
                topLeft = Offset((w - side) / 2f, (h - side) / 2f),
                size = Size(side, side),
                cornerRadius = CornerRadius(side * 0.18f),
            )
        } else {
            val path = Path().apply {
                moveTo(w * 0.24f, h * 0.16f)
                lineTo(w * 0.84f, h * 0.50f)
                lineTo(w * 0.24f, h * 0.84f)
                close()
            }
            drawPath(path, color)
        }
    }
}

/** 主操作按钮：整宽、渐变、带图形；禁用/忙碌时自动降透明度并显示进度圈。 */
@Composable
fun PrimaryActionButton(
    text: String,
    stopping: Boolean,
    brush: Brush,
    /** 按钮内容色：单色下启动＝黑（白底），停止＝白（深底） */
    contentColor: Color = OnAccent,
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(62.dp)
            .clip(shape)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled && !busy) { onClick() },
        shape = shape,
        color = Color.Transparent,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(brush),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = contentColor,
                        strokeWidth = 2.5.dp,
                    )
                } else {
                    ActionGlyph(stopping = stopping, color = contentColor)
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                )
            }
        }
    }
}

/** 次级功能入口；[badge] > 0 时右上角显示角标（用于"有可用更新"）。 */
@Composable
fun ActionTile(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    badge: Int = 0,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = modifier
            .clip(shape)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled) { onClick() },
        shape = shape,
        color = Mono2,
        border = BorderStroke(1.dp, Line),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(7.dp)
                            .size(18.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (badge > 0) BadgeCount(badge)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BadgeCount(count: Int) {
    Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.tertiary) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            // 用主题的 onTertiary，而不是硬编码色 ——
            // 色板改成单色后，硬编码的深绿会与主题脱节（这正是"混色"那类问题的来源）。
            color = MaterialTheme.colorScheme.onTertiary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** 键值行：左标签右值；[mono] 用于 URL / 路径 / 版本号。 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    maxLines: Int = 2,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.width(76.dp),
        )
        Text(
            text = value,
            style = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily)
            else MaterialTheme.typography.bodyMedium,
            color = valueColor,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 分区小标题。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = TextMuted,
        modifier = modifier,
    )
}

/** 顶部氛围：一层自上而下淡出的高光。 */
@Composable
fun HeroBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(listOf(Mono3.copy(alpha = 0.55f), Color.Transparent)),
        ),
    ) { content() }
}
