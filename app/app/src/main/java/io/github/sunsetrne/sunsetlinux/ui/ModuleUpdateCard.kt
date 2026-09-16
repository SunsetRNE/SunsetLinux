package io.github.sunsetrne.sunsetlinux.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.sunsetrne.sunsetlinux.core.ModuleArtifact
import io.github.sunsetrne.sunsetlinux.core.ModuleInstaller
import io.github.sunsetrne.sunsetlinux.core.ModuleRelease
import io.github.sunsetrne.sunsetlinux.core.compareModuleVersion
import io.github.sunsetrne.sunsetlinux.core.formatBytes
import io.github.sunsetrne.sunsetlinux.ui.components.Pill
import io.github.sunsetrne.sunsetlinux.ui.theme.Accent
import io.github.sunsetrne.sunsetlinux.ui.theme.Danger
import io.github.sunsetrne.sunsetlinux.ui.theme.StateRunning
import io.github.sunsetrne.sunsetlinux.ui.theme.TextMuted
import io.github.sunsetrne.sunsetlinux.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「关于」页里的**模块更新**卡片：显示已装/最新版本，并支持**在 App 内一键刷入**。
 *
 * 为什么要有它（用户原话："更新模块『关于』页面，使其更新实际可用"）：
 * 关于页以前只说 `模块 1.0.9（已启用）`——用户知道了版本，但**没有任何下一步**：
 * 要更新还得自己去 GitHub 找 zip、再打开 KernelSU 管理器手动安装。而设备上早就有
 * `ksud`，App 又有 root，这件事完全可以在 App 里做完（下载 → sha256 → `ksud module install`），
 * 剩下的只有"重启"这一步必须由用户决定。
 *
 * 版本判断用的是**逐段数字比较**（`1.0.10 > 1.0.9`），不是字符串比较。
 */
@Composable
fun ModuleUpdateCard(
    installedVersion: String?,
    /** 已装模块是否可读（读不到时不给"刷入"按钮，先让用户去看 root 授权，别乱刷）。 */
    installedReadable: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var artifact by remember { mutableStateOf<ModuleArtifact?>(null) }
    var checking by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var stage by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }

    /** 已装 < 最新 → 可更新；读不到已装版本时**不猜**，只提示"未知"并给检查按钮。 */
    val behind = artifact != null && installedVersion != null &&
        compareModuleVersion(installedVersion, artifact!!.version) < 0

    fun append(text: String) {
        logs = (logs + text.split('\n').filter { it.isNotBlank() }).takeLast(8)
    }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("KernelSU 模块", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(6.dp))
            when {
                artifact == null -> Pill("未检查", color = TextMuted)
                behind -> Pill("可更新 → ${artifact!!.version}", color = Accent, filled = true)
                else -> Pill("已是最新", color = StateRunning)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = buildString {
                append("已装：")
                append(
                    when {
                        !installedReadable -> "读不到（先确认 root 授权）"
                        installedVersion == null -> "未装"
                        else -> installedVersion
                    },
                )
                artifact?.let { append("　最新：${it.version}（${it.source}）") }
                artifact?.size?.let { append("　${formatBytes(it)}") }
            },
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
        if (notice != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = notice!!,
                style = MaterialTheme.typography.labelSmall,
                color = if (notice!!.startsWith("✗")) Danger else TextSecondary,
            )
        }
        if (busy && stage.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(stage, style = MaterialTheme.typography.labelSmall, color = Accent)
        }
        if (logs.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                logs.forEach {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    if (checking) return@OutlinedButton
                    checking = true
                    notice = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { ModuleRelease.fetch() }
                        artifact = r.artifact
                        notice = when {
                            r.artifact == null -> "✗ 查不到最新模块版本：${r.error ?: "原因未知"}"
                            r.artifact!!.version == installedVersion -> "已是最新，无需刷入。"
                            else -> null
                        }
                        checking = false
                    }
                },
                enabled = !busy,
            ) { Text(if (checking) "检查中…" else "检查模块更新") }

            if (behind) {
                Button(
                    onClick = {
                        val a = artifact ?: return@Button
                        if (installing) return@Button
                        installing = true
                        busy = true
                        notice = null
                        logs = emptyList()
                        scope.launch {
                            val outcome = withContext(Dispatchers.IO) {
                                ModuleInstaller.install(context, a) { s, done, total ->
                                    stage = if (total > 0) "$s  ${formatBytes(done)} / ${formatBytes(total)}" else s
                                }
                            }
                            append(outcome.log)
                            installing = false
                            busy = false
                            stage = ""
                            notice = if (outcome.ok) {
                                "✓ 已刷入 ${a.version}：**重启后生效**（KernelSU 先落在 modules_update/）"
                            } else {
                                "✗ 刷入失败：${outcome.error ?: "见日志"}"
                            }
                        }
                    },
                    enabled = !busy && installedReadable,
                ) { Text(if (installing) "刷入中…" else "下载并刷入 ${artifact?.version ?: ""}".trim()) }
            }
        }
        if (!installedReadable) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "读不到本机模块状态（root 未授权/超时）——先修好授权再刷入，避免刷到一半失败。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
        if (behind) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "刷入 = 下载官方模块 zip（sha256 校验）→ `ksud module install`；" +
                    "重启由你决定，App 不会替你重启。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
    }
}
