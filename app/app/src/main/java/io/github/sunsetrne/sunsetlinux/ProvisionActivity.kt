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
import io.github.sunsetrne.sunsetlinux.core.Edition
import io.github.sunsetrne.sunsetlinux.core.EnvMode
import io.github.sunsetrne.sunsetlinux.core.LinuxCtl
import io.github.sunsetrne.sunsetlinux.core.DeviceStatus
import io.github.sunsetrne.sunsetlinux.core.ModuleStatus
import io.github.sunsetrne.sunsetlinux.core.Prefs
import io.github.sunsetrne.sunsetlinux.core.RootProbe
import io.github.sunsetrne.sunsetlinux.core.RootState
import io.github.sunsetrne.sunsetlinux.core.ProvisionPlan
import io.github.sunsetrne.sunsetlinux.core.ProotRuntime
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

    // 单模式版：模式由 edition 锁定（Root 版 / 免 root 版是两个可共存的 App）
    private val mode = mutableStateOf(Edition.lockedMode)
    private val suAvailable = mutableStateOf<Boolean?>(null)
    private val ctlReady = mutableStateOf<Boolean?>(null)
    // root / 模块的**可解释**状态（用户要的"检测"）：不再只显示一个"su 不可用"
    private val rootProbe = mutableStateOf<RootProbe?>(null)
    private val moduleStatus = mutableStateOf<ModuleStatus?>(null)
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

        mode.value = Edition.lockedMode
        seedDir.value = prefs.seedDir ?: ""
        channels.addAll(prefs.channels)
        selectedChannelId.value = channels.firstOrNull { it.enabled }?.id

        setContent {
            SunsetLinuxTheme {
                ProvisionScreen(
                    mode = mode.value,
                    suAvailable = suAvailable.value,
                    ctlReady = ctlReady.value,
                    rootProbe = rootProbe.value,
                    moduleStatus = moduleStatus.value,
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
            // ① root/模块：**只有 Root 版才探**（免 root 版探 su 没意义，还可能弹授权框）
            val probe = if (Edition.needsSu) DeviceStatus.root(force = true)
                        else RootProbe(RootState.UNKNOWN, "免 root 版不探测 su（也不需要）")
            val su = Edition.needsSu && probe.granted
            val module = if (Edition.needsKernelSuModule) DeviceStatus.module(force = true) else null

            withMain {
                rootProbe.value = probe
                moduleStatus.value = module
                suAvailable.value = su
            }
            // Root 版没有 su 时**不降级**：降级会去操作另一个环境（最坏的一种"看起来能用"）
            val effective = mode.value
            val ready = LinuxCtl(this@ProvisionActivity, effective).exists()
            withMain {
                ctlReady.value = ready
                if (Edition.needsSu) appendLog("环境检测：${probe.label}（${probe.detail}）")
                appendLog("本版：${Edition.label}（${Edition.applicationId}）· 模式固定为 ${mode.value.modeLabel}")
                appendLog("环境检测：${module?.label ?: "免 root 版与 KernelSU 模块无关"}")
                probe.hint?.let { appendLog("→ $it") }
                module?.hint?.let { appendLog("→ $it") }
                if (mode.value == EnvMode.ROOT && !su) {
                    appendLog("提示：本版是 Root 版但没有 su —— 请先在 KernelSU/Magisk 里授权本应用；" +
                        "不想 root 就用「免 root 版」（两个 App 可同时安装）。")
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

            if (!ctl.exists() && picked == EnvMode.PROOT) {
                // ★ 免 root 模式：`bin/linuxctl` 这套宿主脚本**随 APK 内置**，不是从别处来的。
                //   这里直接补上（幂等，50 KB），而不是把用户打发去找文件 —— 真机上用户照旧引导
                //   走到这里只看到"没有找到 linuxctl"，而当时的界面里根本没有能铺它的动作。
                appendLog("· bin/linuxctl 不在：先从 APK 内置资产铺宿主脚本（不需要 root）")
                val laid = runCatching {
                    ProotRuntime.ensure(this@ProvisionActivity, ctl.home)
                }.getOrElse {
                    ProotRuntime.Result(false, emptyList(), it.message ?: "未知错误", false)
                }
                if (laid.ok) {
                    appendLog(
                        "  写入 ${laid.written.size} 个文件；契约路径 bin/linuxctl " +
                            (if (laid.contractReady) "已就位" else "仍未就位"),
                    )
                } else {
                    appendLog("  铺脚本失败：${laid.error}")
                }
                laid.error?.let { appendLog("  注意：$it") }
            }

            if (!ctl.exists()) {
                appendLog("✗ 未找到 linuxctl：${ctl.ctlPath}")
                appendLog(if (picked == EnvMode.ROOT) {
                    "root 模式的 linuxctl 由 KernelSU 模块铺到 /data/sunsetlinux/bin/。" +
                        "请先在 KernelSU 管理器里安装模块并重启，然后回到这里重试" +
                        "（首启引导第 2 步可以一键刷入内置模块）。"
                } else {
                    "免 root 模式的 bin/linuxctl 由 App 内置资产铺（约 50 KB，不需要 root、不联网），" +
                        "但这次没铺成：多半是这个 APK 没内嵌 proot 脚本（干净检出 / CI 未附产物）。" +
                        "请换官方发布的 APK；也可以在「关于」页确认组合信息。"
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

            // ★ 分诊：`linuxctl provision` 只建目录/可写层/配置，**从不构建层**。
            //   层缺失时它返回 ok:false + missing_layers —— 真机上向导原先就在这里以
            //   "provision 失败"收场，而其实缺的是"构建那一步"（见 core/ProvisionPlan.kt）。
            val missing = ProvisionPlan.missingLayers(provision.stdout)
            val step = ProvisionPlan.nextStep(
                mode = picked,
                exitCode = provision.exitCode,
                missing = missing,
                suAvailable = suAvailable.value == true,
            )
            appendLog(ProvisionPlan.explain(step, missing))

            when (step) {
                ProvisionPlan.Step.DONE -> appendLog("✓ provision 完成（层齐）")

                ProvisionPlan.Step.BUILD_LAYERS -> {
                    appendLog("$ sh /data/adb/modules/sunsetlinux/bin/device-provision.sh --seeds …")
                    val build = ctl.streamDeviceProvision(seedDir.value) { appendLog(it) }
                    if (!build.ok) {
                        appendLog("✗ 设备侧原生构建失败（退出码 ${build.exitCode}）：${build.message}")
                        appendLog("  日志：${ctl.home}/cache/provision.log")
                        appendLog("  也可以自己在 root 终端跑：sh /data/adb/modules/sunsetlinux/bin/device-provision.sh --seeds ${ctl.home}/seeds")
                        withMain { running.value = false; result.value = false }
                        return@launch
                    }
                    appendLog("✓ 设备侧原生构建完成")
                    // 构建完再看一次状态：层是否真的齐了（不靠"命令返回 0"就宣布成功）
                    val st = ctl.status()
                    val stillMissing = ProvisionPlan.KNOWN_LAYERS.filter { st.layer(it)?.version == null }
                    if (stillMissing.isNotEmpty()) {
                        appendLog("✗ 构建跑完了，但状态里仍然缺层：${stillMissing.joinToString("、")}")
                        appendLog("  请把上面的输出 + ${ctl.home}/cache/provision.log 发给维护者。")
                        withMain { running.value = false; result.value = false }
                        return@launch
                    }
                    appendLog("✓ 三层都在（${ProvisionPlan.KNOWN_LAYERS.joinToString("、")}）")
                }

                ProvisionPlan.Step.NEED_CHANNEL -> {
                    // ★ proot 模式的这句话以前是"先在更新页装三层，再回来启动" —— 而当时
                    //   proot 的 linuxctl 只认 tar，装进来的 erofs 层根本解不开（用户 2026-09-16
                    //   正是卡在这条建议上）。模块 1.0.17 起 proot 也能解 erofs 层，所以现在
                    //   这句话是**真的可执行**的，并把"装完还要再点一次 provision"说清楚。
                    appendLog(
                        if (picked == EnvMode.PROOT) {
                            "· 免 root 模式的根文件系统来自 base 层：请到「更新」页从频道安装 base（erofs），" +
                                "然后回到这里再点一次「开始部署」——proot 会用 fsck.erofs 把它解成 rootfs。"
                        } else {
                            "· 缺少层：请到「更新」页从频道安装三层（base/runtime/dsh），或在「关于」页一键更新模块后用设备侧构建。"
                        },
                    )
                    withMain { running.value = false; result.value = false }
                    return@launch
                }

                ProvisionPlan.Step.FAILED -> {
                    appendLog("✗ 部署没有完成：${provision.message}")
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
    rootProbe: RootProbe?,
    moduleStatus: ModuleStatus?,
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
            // ① 本版（单模式版：模式由 edition 锁定，这里只如实说明，不给"选"）
            StepCard(1, "本版（模式固定）") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(Edition.labelShort, color = Accent, filled = true)
                    Spacer(Modifier.width(8.dp))
                    Pill(mode.modeLabel, color = StateRunning, filled = true)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (Edition.isRoot) {
                        "Root 版（${Edition.applicationId}）：环境由 KernelSU 模块铺到 /data/sunsetlinux，" +
                            "开机自启、与 App 生命周期解耦。部署向导会按这条路走。"
                    } else {
                        "免 root 版（${Edition.applicationId}）：宿主脚本随 APK 内置，rootfs 从 base 层/种子解开，" +
                            "全程不需要 su。部署向导会先自动铺脚本。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
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
                    text = if (mode == EnvMode.PROOT) {
                        "免 root 的三件必需件：**宿主脚本**（App 内置，进这个向导时会自动铺）、" +
                            "**proot 运行时**（内嵌离线包）、**rootfs**（从 base 层解开或解一个 tar 种子）。" +
                            "给了种子目录就以 --seed 传给 provision；没给也会自动用已装好的 base 层。"
                    } else {
                        "给了种子目录就会以 --seed 传给 linuxctl provision；root 模式下层通常来自" +
                            "频道或设备侧构建（device-provision.sh）。"
                    },
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
                        text = rootProbe?.label ?: if (suAvailable == true) "su 可用" else "su 不可用",
                        color = when {
                            rootProbe == null -> TextMuted
                            rootProbe.granted -> StateRunning
                            rootProbe.state == io.github.sunsetrne.sunsetlinux.core.RootState.UNKNOWN -> TextMuted
                            else -> Danger
                        },
                    )
                    if (mode == EnvMode.ROOT) {
                        Spacer(Modifier.width(8.dp))
                        Pill(
                            text = moduleStatus?.label ?: "模块检测中…",
                            color = when {
                                moduleStatus == null -> TextMuted
                                // 读不到 ≠ 没装：中性色 + 提示去授权（真机踩过"重启了还说没刷入"）
                                !moduleStatus.readable -> TextMuted
                                !moduleStatus.installed || moduleStatus.disabled -> WarnTone
                                moduleStatus.pendingReboot -> WarnTone
                                else -> StateRunning
                            },
                        )
                    }
                }
                // 说不清就没意义 —— 状态后面必须跟"下一步做什么"（免 root 下模块与它无关，不显示）
                listOfNotNull(
                    rootProbe?.hint,
                    if (mode == EnvMode.ROOT) moduleStatus?.hint else null,
                ).forEach { hint ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "→ $hint",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarnTone,
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
