package io.github.sunsetrne.sunsetlinux.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sunsetrne.sunsetlinux.ui.theme.MonoFamily
import io.github.sunsetrne.sunsetlinux.ui.theme.TextMuted
import io.github.sunsetrne.sunsetlinux.ui.theme.TextSecondary

/**
 * 冷启动的第一帧（引导之前 / 门禁判定期间）。
 *
 * ## 为什么需要它（用户实测："初次安装会有很大的黑屏页面，过一会才有模式引导"）
 *   1. 主题的 `windowBackground` 是**纯黑**（单色主题），所以"还没画东西"看起来就是一块大黑屏；
 *   2. 原来 `LauncherActivity` 在**没走完引导时也会**把整套外壳（`AppShell`：顶栏 + 三个面板 +
 *      日志轮询）组合出来，引导页再盖在上面 —— 首帧要等这套重活组合完，黑屏时间被拉长；
 *   3. 引导页是另一个 Activity，冷启动要经历"启动窗口 → 外壳首帧 → 再起一个 Activity"，
 *      中间每一次空窗都表现为黑屏。
 *
 * 现在：没走完引导时**不组合外壳**，先画这一屏 —— 有标题、有"在做什么"、有明确出路。
 * 首帧很便宜，黑屏立刻结束。
 *
 * 回调式（而不是在这里 `startActivity`）：方便以后写 UI 测试，也避免依赖
 * `LocalActivity`（各 Compose 版本可用性不一）。
 */
@Composable
fun BootPlaceholder(
    onOpenWelcome: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "SunsetLinux",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "首次启动：先选运行方式，再部署环境。",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "正在打开引导…",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
            color = TextMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))

        // 明确出路：万一引导被返回键关掉，这里能一键回去，
        // 而不是停在一块"什么都没有"的页面上让人不知所措。
        Button(onClick = onOpenWelcome, modifier = Modifier.fillMaxWidth()) {
            Text("打开引导")
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "已经部署过环境？直接退出再打开本应用即可跳过引导。",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onExit) {
            Text("退出", style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
    }
}
