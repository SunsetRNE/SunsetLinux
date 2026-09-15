package io.github.sunsetrne.sunsetlinux

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.sunsetrne.sunsetlinux.ui.theme.applyDshSystemBars
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.sunsetrne.sunsetlinux.core.Channel
import io.github.sunsetrne.sunsetlinux.core.DshRuntime
import io.github.sunsetrne.sunsetlinux.core.EnvMode
import io.github.sunsetrne.sunsetlinux.core.LinuxCtl
import io.github.sunsetrne.sunsetlinux.core.Prefs
import io.github.sunsetrne.sunsetlinux.ui.components.DshCard
import io.github.sunsetrne.sunsetlinux.ui.components.Pill
import io.github.sunsetrne.sunsetlinux.ui.components.SectionLabel
import io.github.sunsetrne.sunsetlinux.ui.theme.WarnTone
import io.github.sunsetrne.sunsetlinux.ui.theme.Accent
import io.github.sunsetrne.sunsetlinux.ui.theme.SunsetLinuxTheme
import io.github.sunsetrne.sunsetlinux.ui.theme.Mono2
import io.github.sunsetrne.sunsetlinux.ui.theme.MonoFamily
import io.github.sunsetrne.sunsetlinux.ui.theme.Danger
import io.github.sunsetrne.sunsetlinux.ui.theme.TextSecondary
import io.github.sunsetrne.sunsetlinux.ui.theme.StateRunning
import io.github.sunsetrne.sunsetlinux.ui.theme.TextMuted
import io.github.sunsetrne.sunsetlinux.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 首次部署向导：选模式 → 选频道（可选离线种子）→ 执行并看实时进度。
 *
 * 一个必须说清楚的前提：`linuxctl` 本身**不在 APK 里**，它属于运行时（`runtime/`）或
 * KernelSU 模块（`module/`）的产物。因此本页在执行前会先自检 `linuxctl` 是否就位，
 * 缺失时给出明确指引，而不是让用户对着一条 "No such file" 发呆。
 */
class ProvisionActivity : ComponentActivity() {

    private val mode = mutableStateOf(EnvMode.ROOT)
    private val suAvailable = mutableStateOf<Boolean?>(null)
    private val ctlReady = mutableStateOf<Boolean?>(null)
    private val channels = mutableStateListOf<Channel>()
    private val selectedChannelId = mutableStateOf<String?>(null)
    private val seedDir = mutableStateOf("")
    private val logLines = mutableStateListOf<String>()
    private val running = mutableStateOf(false)
    private val result = mutableStateOf<Boolean?>(null)

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDshSystemBars()
        prefs = Prefs(this)

        mode.value = prefs.modeOverride ?: EnvMode.ROOT
        seedDir.value = prefs.seedDir ?: ""
        channels.addAll(prefs.channels)
        selectedChannelId.value = channels.firstOrNull { it.enabled }?.id

        setContent {
            SunsetLinuxTheme {
                ProvisionScreen(
                    mode = mode.value,
                    suAvailable = suAvailable.value,
                    ctlReady = ctlReady.value,
                    channels = channels,
                    selectedChannelId = selectedChannelId.value,
                    seedDir = seedDir.value,
                    running = running.value,
                    result = result.value,
                    logLines = logLines,
                    onBack = { finish() },
                    onPickMode = { picked ->
                        mode.value = picked
                        prefs.modeOverride = picked
                        probe()
                    },
                    onPickChannel = { selectedChannelId.value = it },
                    onSeedChange = {
                        seedDir.value = it
                        prefs.seedDir = it.ifBlank { null }
                    },
                    onOpenSettings = { startActivity(android.content.Intent(this, SettingsActivity::class.java)) },
                    onProvision = { runProvision() },
                    onStartEnv = { startEnvironment() },
                )
            }
        }

        probe()
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚从设置页添加了频道
        val latest = prefs.channels
        channels.clear()
        channels.addAll(latest)
        if (selectedChannelId.value == null) {
            selectedChannelId.value = latest.firstOrNull { it.enabled }?.id
        }
    }

    /** 自检：su 是否可用、当前模式下 linuxctl 是否就位。 */
    private fun probe() {
        lifecycleScope.launch(Dispatchers.IO) {
            val su = DshRuntime.suAvailable(force = true)
            
            withMain { suAvailable.value = su }
            val effective = when {
                mode.value == EnvMode.ROOT && !su -> EnvMode.PROOT
                else -> mode.value
            }
            val ready = LinuxCtl(this@ProvisionActivity, effective).exists()
            withMain {
                ctlReady.value = ready
                if (mode.value == EnvMode.ROOT && !su) {
                    appendLog("提示：本机没有可用的 su，root 模式不可选。")
                }
            }
        }
    }

    private fun runProvision() {
        if (running.value) return
        val picked = mode.value
        prefs.modeOverride = picked
        running.value = true
        result.value = null
        logLines.clear()

        lifecycleScope.launch(Dispatchers.IO) {
            val ctl = LinuxCtl(this@ProvisionActivity, picked)
            appendLog("# 部署向导开始")
            appendLog("# 模式：${picked.modeLabel}   环境根：${ctl.home}")

            if (!ctl.exists()) {
                appendLog("✗ 未找到 linuxctl：${ctl.ctlPath}")
                appendLog(if (picked == EnvMode.ROOT) {
                    "root 模式的 linuxctl 由 KernelSU 模块铺到 /data/sunsetlinux/bin/。" +
                        "请先在 KernelSU 管理器里安装 module/ 下的模块并重启，然后回到这里重试。"
                } else {
                    "proot 模式需要先把 runtime/proot/linuxctl.sh 放到 ${ctl.ctlPath}（并 chmod +x）。" +
                        "也可以用频道分发的方式安装后再回来。"
                })
                withMain { running.value = false; result.value = false }
                return@launch
            }

            val args = buildList {
                add("provision")
                seedDir.value.trim().takeIf { it.isNotEmpty() }?.let {
                    add("--seed"); add(it)
                }
            }
            appendLog("$ linuxctl ${args.joinToString(" ")}")
            val provision = ctl.stream(args) { appendLog(it) }

            when {
                provision.ok -> appendLog("✓ provision 完成")
                provision.exitCode == 2 -> appendLog("✓ 环境已经部署过（退出码 2），跳过。")
                else -> {
                    appendLog("✗ provision 失败：${provision.message}")
                    withMain { running.value = false; result.value = false }
                    return@launch
                }
            }

            appendLog("$ linuxctl start")
            val start = ctl.stream(listOf("start")) { appendLog(it) }
            if (start.ok) {
                appendLog("✓ 环境已启动。首次启动会初始化 rootfs，可能需要几十秒。")
            } else {
                appendLog("✗ 启动失败：${start.message}（部署本身已完成，可回首页重试启动）")
            }
            withMain { running.value = false; result.value = start.ok }
        }
    }

    private fun startEnvironment() {
        lifecycleScope.launch(Dispatchers.IO) {
            withMain { running.value = true }
            val ctl = LinuxCtl(this@ProvisionActivity, mode.value)
            val start = ctl.stream(listOf("start")) { appendLog(it) }
            withMain { running.value = false; result.value = start.ok }
        }
    }

    private fun appendLog(line: String) {
        withMain { if (logLines.size < 2000) logLines.add(line) }
    }

    private fun withMain(block: () -> Unit) = runOnUiThread(block)
}

// ─────────────────────────────────────────────────────────────── Compose 层

@Composable
private fun ProvisionScreen(
    mode: EnvMode,
    suAvailable: Boolean?,
    ctlReady: Boolean?,
    channels: List<Channel>,
    selectedChannelId: String?,
    seedDir: String,
    running: Boolean,
    result: Boolean?,
    logLines: List<String>,
    onBack: () -> Unit,
    onPickMode: (EnvMode) -> Unit,
    onPickChannel: (String) -> Unit,
    onSeedChange: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onProvision: () -> Unit,
    onStartEnv: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .imePadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            Column {
                Text("部署向导", style = MaterialTheme.typography.titleLarge)
                Text(
                    "首次使用需要把 Linux 环境准备好",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // ① 模式
            StepCard(1, "选择运行模式") {
                ModeOption(
                    title = "root 模式（推荐）",
                    subtitle = "真 chroot + mount 命名空间，真 uid 0；环境由 KernelSU 模块开机启动，与 App 生命周期解耦。",
                    selected = mode == EnvMode.ROOT,
                    enabled = suAvailable == true,
                    disabledReason = if (suAvailable == false) "本机没有可用的 su：请先刷入 KernelSU/Magisk 并授权本应用" else null,
                    onClick = { onPickMode(EnvMode.ROOT) },
                )
                Spacer(Modifier.height(10.dp))
                ModeOption(
                    title = "proot 模式（降级兜底）",
                    subtitle = "不需要 root，环境目录在 App 私有空间；性能与生命周期都受 App 限制。",
                    selected = mode == EnvMode.PROOT,
                    enabled = true,
                    disabledReason = null,
                    onClick = { onPickMode(EnvMode.PROOT) },
                )
            }

            Spacer(Modifier.height(14.dp))

            // ② 频道
            StepCard(2, "选择更新频道（可跳过）") {
                if (channels.isEmpty()) {
                    Text(
                        text = "尚未配置任何频道。provision 仍可离线完成（需要本地种子或已铺好的层）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onOpenSettings) { Text("去设置里添加频道") }
                } else {
                    channels.forEach { ch ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedChannelId == ch.id,
                                onClick = { onPickChannel(ch.id) },
                                enabled = ch.enabled,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(ch.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = ch.shortUrl,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                                    color = TextMuted,
                                )
                            }
                            if (!ch.enabled) Pill("已禁用", color = TextMuted)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = seedDir,
                    onValueChange = onSeedChange,
                    label = { Text("离线种子目录（可选）") },
                    placeholder = { Text("/sdcard/sunsetlinux-seed") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "给了种子目录就会以 --seed 传给 linuxctl provision，可离线铺层。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }

            Spacer(Modifier.height(14.dp))

            // ③ 执行
            StepCard(3, "执行部署") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(
                        text = when (ctlReady) {
                            true -> "linuxctl 已就位"
                            false -> "linuxctl 缺失"
                            null -> "正在自检…"
                        },
                        color = when (ctlReady) {
                            true -> StateRunning
                            false -> Danger
                            null -> TextMuted
                        },
                        filled = true,
                    )
                    Spacer(Modifier.width(8.dp))
                    Pill(
                        text = if (suAvailable == true) "su 可用" else "su 不可用",
                        color = if (suAvailable == true) Accent else WarnTone,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onProvision,
                    enabled = !running && ctlReady == true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) {
                    if (running) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(if (running) "正在部署…" else "开始部署")
                }

                if (result == true) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = StateRunning,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("部署完成，环境已启动", color = StateRunning, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onStartEnv, enabled = !running) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("再次确认启动")
                    }
                }
            }

            if (logLines.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                SectionLabel("实时输出")
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Mono2,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier
                            .heightIn(min = 120.dp, max = 320.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    ) {
                        logLines.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
                                color = TextSecondary,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StepCard(index: Int, title: String, content: @Composable () -> Unit) {
    DshCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(999.dp), color = Accent.copy(alpha = 0.16f)) {
                    Text(
                        text = index.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Accent,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    disabledReason: String?,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) Accent.copy(alpha = 0.10f) else Mono2,
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else TextMuted,
                )
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                if (disabledReason != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = WarnTone,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(disabledReason, style = MaterialTheme.typography.labelSmall, color = WarnTone)
                    }
                }
            }
            if (selected && enabled) Pill("已选", color = TextSecondary, filled = true)
        }
    }
}
