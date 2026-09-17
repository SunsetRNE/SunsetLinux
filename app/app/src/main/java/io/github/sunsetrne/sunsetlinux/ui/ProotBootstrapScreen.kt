package io.github.sunsetrne.sunsetlinux.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sunsetrne.sunsetlinux.core.Edition
import io.github.sunsetrne.sunsetlinux.ui.components.DshCard
import io.github.sunsetrne.sunsetlinux.ui.components.LogPanel
import io.github.sunsetrne.sunsetlinux.ui.components.Pill
import io.github.sunsetrne.sunsetlinux.ui.components.SectionLabel
import io.github.sunsetrne.sunsetlinux.ui.theme.Accent
import io.github.sunsetrne.sunsetlinux.ui.theme.Danger
import io.github.sunsetrne.sunsetlinux.ui.theme.MonoFamily
import io.github.sunsetrne.sunsetlinux.ui.theme.StateRunning
import io.github.sunsetrne.sunsetlinux.ui.theme.TextMuted
import io.github.sunsetrne.sunsetlinux.ui.theme.TextSecondary
import io.github.sunsetrne.sunsetlinux.ui.theme.WarnTone

/**
 * 免 root 版冷启动的「启用内置环境」界面（[ProotBootstrapState] 的渲染层）。
 *
 * ## 为什么要有这一屏，而不是直接进首启引导
 *
 * 用户要求"打开免 root 版就自动启用、最后落到终端页"（`docs/module-variants.md` §1.3）。
 * 自动跑就必须**看得见在跑什么** —— 真机上这一步要解几百 MB 的离线包，界面若是空白或
 * 只有一个转圈，用户会以为卡死、然后杀掉 App（而杀进程正是最糟的时机：解包解到一半）。
 * 所以这里照 App 里其它长任务的做法：**一步一行日志 + 当前阶段**（[ProotBootstrapState.stage]）。
 *
 * ## 失败时的三件事（一条都不能少）
 *
 * 1. **原始错误**：脚本/命令打出来的原文（[ProotBootstrapState.error]），不做二次翻译
 *    —— 只有原文才能拿去搜、才能发给维护者；
 * 2. **重试**：流水线是幂等的（缺什么补什么），所以重试不会重复解包；
 * 3. **手动路径**：`onManual` 把用户送到既有的首启引导页（并记下"用户明确取消自动"），
 *    保证自动路走不通时永远还有一条走得通的路。
 */
@Composable
fun ProotBootstrapScreen(
    state: ProotBootstrapState,
    onRetry: () -> Unit,
    onManual: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val logs by state.log.collectAsState()
    // "当前在做什么" = 日志最后一行（日志本身是从 IO 线程 append 进 StateFlow 的，
    // 这里用 collectAsState 取，避免跨线程写 Compose 的 snapshot state）
    val current = logs.lastOrNull() ?: "正在检查内置环境…"

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Text("正在启用内置 Ubuntu 环境", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "免 root 版（${Edition.applicationId}）：不需要 root、不需要刷机。" +
                "App 会把内置的 proot 运行时与 Ubuntu 展开到自己的私有目录，然后启动环境并进入终端。",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(12.dp))

        // 当前在做什么：状态胶囊 + 最后一行日志（"在做什么"必须一眼可见）
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (state.phase) {
                ProotBootstrapState.Phase.FAILED -> {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = Danger, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Pill("启用失败", color = Danger, filled = true)
                }
                ProotBootstrapState.Phase.DONE,
                ProotBootstrapState.Phase.ALREADY_READY,
                -> {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = StateRunning, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Pill("环境已就绪", color = StateRunning, filled = true)
                }
                else -> {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Pill("正在启用", color = Accent, filled = true)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = current,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
            color = if (state.phase == ProotBootstrapState.Phase.FAILED) Danger else TextMuted,
            maxLines = 1,
        )
        Spacer(Modifier.height(12.dp))

        // 日志占满剩余高度（LogPanel 的 height = null 就是"填满可用高度"）
        Box(Modifier.weight(1f)) {
            LogPanel(
                lines = logs,
                modifier = Modifier.fillMaxSize(),
                height = null,
                autoFollow = true,
                emptyText = "正在检查内置环境…",
                errorText = null,
            )
        }
        Spacer(Modifier.height(12.dp))

        if (state.phase == ProotBootstrapState.Phase.FAILED) {
            DshCard(Modifier.fillMaxWidth(), highlighted = true) {
                Column(Modifier.fillMaxWidth()) {
                    SectionLabel("原始错误（未翻译，可直接发给维护者）")
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 132.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = state.error ?: "（没有错误信息）",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                            color = Danger,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRetry, modifier = Modifier.weight(1f)) { Text("重试") }
                        OutlinedButton(onClick = onManual, modifier = Modifier.weight(1f)) {
                            Text("手动部署")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "重试是可重入的：已经铺好的部件不会重装。手动部署会打开首启引导" +
                            "（铺运行时 / 铺环境 / 启动），以后也可以从侧边栏「重新部署 / 首启引导」再进来。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        } else {
            TextButton(onClick = onManual) { Text("改成手动部署（首启引导）") }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "手动部署后 App 不再自动铺环境；想恢复自动，可在侧边栏「重新部署 / 首启引导」里重新走一遍。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
        Spacer(Modifier.height(14.dp))
    }
}
