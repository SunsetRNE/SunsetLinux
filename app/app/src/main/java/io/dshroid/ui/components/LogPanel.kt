package io.dshroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dshroid.ui.theme.WarnTone
import io.dshroid.ui.theme.Accent
import io.dshroid.ui.theme.Mono0
import io.dshroid.ui.theme.Danger
import io.dshroid.ui.theme.StateRunning
import io.dshroid.ui.theme.TextMuted
import io.dshroid.ui.theme.TextSecondary

/**
 * 常显日志面板。
 *
 * 设计要点（照参考实现的优点做）：
 * - **固定高度**，不是"有日志才撑开" —— 排障时最忌讳界面跟着内容跳；
 * - **行号**，方便用户口述"第 137 行报错了"；
 * - **关键行高亮**：error/失败 → 红，warn → 黄，ok/成功 → 绿，命令 → 青；
 * - **自动跟随**尾部，可暂停；暂停后不再抢滚动位置。
 */
@Composable
fun LogPanel(
    lines: List<String>,
    modifier: Modifier = Modifier,
    /** null = 填满可用高度（整屏日志页用）；默认固定 220dp（首页常显） */
    height: Dp? = 220.dp,
    autoFollow: Boolean = true,
    onToggleFollow: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null,
    emptyText: String = "暂无日志输出",
    errorText: String? = null,
) {
    val listState = rememberLazyListState()
    val latest by rememberUpdatedState(lines)

    // 内容变化后跟随到尾部；用户暂停时不抢滚动
    LaunchedEffect(lines.size, autoFollow) {
        if (autoFollow && latest.isNotEmpty()) {
            runCatching { listState.scrollToItem(latest.size - 1) }
        }
    }

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "日志",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${lines.size} 行",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
            Spacer(Modifier.weight(1f))
            if (onRefresh != null) {
                TextButton(onClick = onRefresh) { Text("刷新", style = MaterialTheme.typography.labelSmall) }
            }
            if (onToggleFollow != null) {
                TextButton(onClick = onToggleFollow) {
                    Text(
                        text = if (autoFollow) "跟随中" else "已暂停",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (autoFollow) StateRunning else WarnTone,
                    )
                }
            }
            if (onExport != null) {
                TextButton(onClick = onExport) { Text("导出", style = MaterialTheme.typography.labelSmall) }
            }
        }

        Spacer(Modifier.height(6.dp))

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Mono0,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (height != null) Modifier.height(height) else Modifier.fillMaxHeight()),
        ) {
            Box(Modifier.fillMaxSize()) {
                when {
                    lines.isEmpty() && errorText != null -> Box(
                        Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = errorText,
                            style = MaterialTheme.typography.bodySmall,
                            color = Danger,
                        )
                    }

                    lines.isEmpty() -> Box(
                        Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(emptyText, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }

                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        itemsIndexed(lines) { index, line ->
                            LogLine(index = index + 1, text = line)
                        }
                    }
                }
            }
        }
    }
}

/** 单行：行号 + 内容；横向可滚动，长行不折行（保持"一行一事件"的可读性）。 */
@Composable
private fun LogLine(index: Int, text: String) {
    val color = logLineColor(text)
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = index.toString().padStart(4, ' '),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 15.sp,
            ),
            color = TextMuted.copy(alpha = 0.7f),
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text.ifBlank { " " },
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                // 单色下颜色只有明度差：错误行靠**加粗**抢注意力
                fontWeight = if (logLineIsError(text)) FontWeight.Bold else FontWeight.Normal,
            ),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Visible,
        )
    }
}

/** 该行是否算"错误行"（决定是否加粗）。 */
fun logLineIsError(text: String): Boolean {
    val t = text.lowercase()
    return t.contains("error") || text.contains("失败") || t.contains("fatal") ||
        t.contains("denied") || t.contains("cannot") || text.contains("✗")
}

/** 关键行高亮规则（顺序即优先级）。**单色**：只用明度区分，不引入色相。 */
fun logLineColor(text: String): Color {
    val t = text.lowercase()
    return when {
        t.contains("error") || text.contains("失败") || text.contains("fatal") ||
            t.contains("denied") || t.contains("cannot") || t.contains("✗") -> Danger

        t.contains("warn") || text.contains("警告") || text.contains("⚠") -> WarnTone

        t.contains("ok") && t.contains("true") || text.contains("成功") || text.contains("✓") ||
            t.contains("ready") || t.contains("running") -> StateRunning

        text.startsWith("$") || text.startsWith("#") || text.startsWith("===") -> Accent

        else -> TextSecondary
    }
}
