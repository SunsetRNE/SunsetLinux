package io.github.sunsetrne.sunsetlinux

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.sunsetrne.sunsetlinux.ui.theme.applyDshSystemBars
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.sunsetrne.sunsetlinux.core.Channel
import io.github.sunsetrne.sunsetlinux.core.DshRuntime
import io.github.sunsetrne.sunsetlinux.core.DshStatus
import io.github.sunsetrne.sunsetlinux.core.EnvFiles
import io.github.sunsetrne.sunsetlinux.core.Edition
import io.github.sunsetrne.sunsetlinux.core.EnvMode
import io.github.sunsetrne.sunsetlinux.core.Prefs
import io.github.sunsetrne.sunsetlinux.ui.components.DshCard
import io.github.sunsetrne.sunsetlinux.ui.components.Pill
import io.github.sunsetrne.sunsetlinux.ui.components.SectionLabel
import io.github.sunsetrne.sunsetlinux.ui.copyToClipboard
import io.github.sunsetrne.sunsetlinux.ui.theme.WarnTone
import io.github.sunsetrne.sunsetlinux.ui.theme.SunsetLinuxTheme
import io.github.sunsetrne.sunsetlinux.ui.theme.MonoFamily
import io.github.sunsetrne.sunsetlinux.ui.theme.Danger
import io.github.sunsetrne.sunsetlinux.ui.theme.StateRunning
import io.github.sunsetrne.sunsetlinux.ui.NpmSourceCard
import io.github.sunsetrne.sunsetlinux.ui.theme.Accent
import io.github.sunsetrne.sunsetlinux.ui.theme.TextMuted
import io.github.sunsetrne.sunsetlinux.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 设置页：运行模式、端口、开机自启、电池优化、冻结模块豁免提示、频道管理、关于。
 *
 * 频道（URL + ed25519 公钥）是"可插拔分发"的核心（§5.1），因此这里的增删改都落在
 * App 侧存储，并可一键**同步到环境**的 `etc/channels.json`。
 */
class SettingsActivity : ComponentActivity() {

    /** 从侧边栏直接跳到某个分区：null=顶部，channels=频道管理，power=省电与冻结豁免 */
    private var section: String? = null

    private lateinit var prefs: Prefs

    private val modeOverride = mutableStateOf<EnvMode?>(null)

    /** 免 root 运行时偏好：null = auto（优先 proroot），也可固定 proroot / proot。 */
    private val rootlessRuntime = mutableStateOf<String?>(null)

    /** 层模式偏好：null/loop = 默认；dir = 解包成目录（不碰 loop/erofs）。 */
    private val layerMode = mutableStateOf<String?>(null)
    private val suAvailable = mutableStateOf<Boolean?>(null)
    private val effectiveMode = mutableStateOf(EnvMode.ROOT)
    private val port = mutableStateOf("3080")
    private val bootStart = mutableStateOf(false)
    private val autoStart = mutableStateOf(false)
    private val channels = mutableStateListOf<Channel>()
    private val notice = mutableStateOf<String?>(null)
    private val batteryExempt = mutableStateOf(false)
    private val notifGranted = mutableStateOf(true)
    private val status = mutableStateOf<DshStatus?>(null)

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notifGranted.value = granted
        }

    // 频道编辑对话框状态
    private val editing = mutableStateOf<Channel?>(null)
    private val creating = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDshSystemBars()
        prefs = Prefs(this)
        section = intent?.getStringExtra(EXTRA_SETTINGS_SECTION)

        modeOverride.value = prefs.modeOverride
        rootlessRuntime.value = prefs.rootlessRuntime
        layerMode.value = prefs.layerMode
        port.value = prefs.port.toString()
        bootStart.value = prefs.bootStartService
        autoStart.value = prefs.autoStartEnv
        channels.addAll(prefs.channels)

        setContent {
            SunsetLinuxTheme {
                SettingsScreen(
                    section = section,
                    modeOverride = modeOverride.value,
                    rootlessRuntime = rootlessRuntime.value,
                    layerMode = layerMode.value,
                    onPickLayerMode = { picked ->
                        layerMode.value = picked
                        prefs.layerMode = picked
                        notice.value = when (picked) {
                            "dir" -> "层模式已切到 dir：下次启动会把三层解包成目录（首次几分钟、约 +1.6 GB），全程不碰 loop/erofs"
                            else -> "层模式：默认 loop（losetup + erofs + upper.img，省磁盘）"
                        }
                    },
                    onPickRootless = { picked ->
                        rootlessRuntime.value = picked
                        prefs.rootlessRuntime = picked
                        notice.value = when (picked) {
                            "proroot" -> "免 root 运行时已固定为 proroot（缺件会明确报错，不静默降级）"
                            "proot" -> "免 root 运行时已固定为 proot（随包 bundle，兼容性最好）"
                            else -> "免 root 运行时：自动（优先 proroot，缺件降级 proot）"
                        }
                    },
                    suAvailable = suAvailable.value,
                    effectiveMode = effectiveMode.value,
                    port = port.value,
                    bootStart = bootStart.value,
                    autoStart = autoStart.value,
                    channels = channels,
                    notice = notice.value,
                    batteryExempt = batteryExempt.value,
                    notifGranted = notifGranted.value,
                    status = status.value,
                    onBack = { finish() },
                    onPickMode = { picked ->
                        modeOverride.value = picked
                        prefs.modeOverride = picked
                        refreshProbe()
                    },
                    onPortChange = {
                        port.value = it.filter { c -> c.isDigit() }.take(5)
                        port.value.toIntOrNull()?.let { p -> prefs.port = p }
                    },
                    onBootStart = { bootStart.value = it; prefs.bootStartService = it },
                    onAutoStart = { autoStart.value = it; prefs.autoStartEnv = it },
                    onRequestBattery = ::requestBatteryExemption,
                    onRequestNotif = ::requestNotificationPermission,
                    onDismissNotice = { notice.value = null },
                    onCopyPackage = { copyToClipboard(this, "包名", packageName) },
                    onAddChannel = { creating.value = true },
                    onEditChannel = { editing.value = it },
                    onToggleChannel = { ch, enabled ->
                        replaceChannel(ch.copy(enabled = enabled))
                    },
                    onDeleteChannel = { ch ->
                        // 内置频道删不掉：它的 URL/公钥是代码里的信任根（UI 上也不显示删除键）。
                        // 这里再兜一层，防止以后有人在别处直接调这个回调。
                        if (Channel.isBuiltin(ch.id)) {
                            notice.value = "「${ch.name}」是内置频道，不能删除（可停用）。"
                        } else {
                            channels.removeAll { it.id == ch.id }
                            persistChannels()
                            notice.value = "已删除频道「${ch.name}」"
                        }
                    },
                    onSyncChannels = ::syncChannelsToEnv,
                    onOpenLogs = { startActivity(Intent(this, LogActivity::class.java)) },
                    onOpenUpdate = { startActivity(Intent(this, UpdateActivity::class.java)) },
                    onOpenProvision = { startActivity(Intent(this, ProvisionActivity::class.java)) },
                    onOpenBatterySettings = ::openBatterySettings,
                )

                if (creating.value || editing.value != null) {
                    ChannelDialog(
                        initial = editing.value,
                        onDismiss = { creating.value = false; editing.value = null },
                        onSave = { ch ->
                            if (editing.value == null) {
                                channels.add(ch)
                                notice.value = "已添加频道「${ch.name}」"
                            } else {
                                replaceChannel(ch)
                                notice.value = "已保存频道「${ch.name}」"
                            }
                            creating.value = false
                            editing.value = null
                            persistChannels()
                        },
                    )
                }
            }
        }

        refreshProbe()
        refreshSystemState()
    }

    override fun onResume() {
        super.onResume()
        refreshSystemState()
    }

    private fun replaceChannel(ch: Channel) {
        val idx = channels.indexOfFirst { it.id == ch.id }
        if (idx >= 0) channels[idx] = ch else channels.add(ch)
        persistChannels()
    }

    private fun persistChannels() {
        prefs.channels = channels.toList()
    }

    private fun refreshProbe() {
        lifecycleScope.launch(Dispatchers.IO) {
            val su = DshRuntime.suAvailable(force = true)
            val choice = DshRuntime.resolveMode(this@SettingsActivity, prefs)
            val st = io.github.sunsetrne.sunsetlinux.core.LinuxCtl(this@SettingsActivity, choice.mode).status()
            withMain {
                suAvailable.value = su
                effectiveMode.value = choice.mode
                status.value = st
            }
        }
    }

    private fun refreshSystemState() {
        val pm = getSystemService(PowerManager::class.java)
        batteryExempt.value = pm?.isIgnoringBatteryOptimizations(packageName) == true
        notifGranted.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestBatteryExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }
            .onFailure { openBatterySettings() }
    }

    private fun openBatterySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.onFailure {
            notice.value = "无法打开电池优化设置，请手动到「系统设置 → 电池 → 应用」中放行 SunsetLinux。"
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** 把频道列表写进 `$LINUX_HOME/etc/channels.json`（§5.1 的文件格式）。 */
    private fun syncChannelsToEnv() {
        lifecycleScope.launch(Dispatchers.IO) {
            val mode = DshRuntime.resolveMode(this@SettingsActivity, prefs).mode
            val json = Channel.toChannelsFile(channels.toList())
            val result = EnvFiles.writeText(this@SettingsActivity, mode, "etc/channels.json", json)
            withMain {
                notice.value = if (result.ok) {
                    "已同步 ${channels.size} 个频道到 ${EnvFiles.home(this@SettingsActivity, mode)}/etc/channels.json"
                } else {
                    "同步失败：${result.message}"
                }
            }
        }
    }

    private fun withMain(block: () -> Unit) = runOnUiThread(block)
}

// ─────────────────────────────────────────────────────────────── Compose 层

@Composable
private fun SettingsScreen(
    section: String?,
    modeOverride: EnvMode?,
    rootlessRuntime: String?,
    onPickRootless: (String?) -> Unit,
    layerMode: String?,
    onPickLayerMode: (String?) -> Unit,
    suAvailable: Boolean?,
    effectiveMode: EnvMode,
    port: String,
    bootStart: Boolean,
    autoStart: Boolean,
    channels: List<Channel>,
    notice: String?,
    batteryExempt: Boolean,
    notifGranted: Boolean,
    status: DshStatus?,
    onBack: () -> Unit,
    onPickMode: (EnvMode?) -> Unit,
    onPortChange: (String) -> Unit,
    onBootStart: (Boolean) -> Unit,
    onAutoStart: (Boolean) -> Unit,
    onRequestBattery: () -> Unit,
    onRequestNotif: () -> Unit,
    onDismissNotice: () -> Unit,
    onCopyPackage: () -> Unit,
    onAddChannel: () -> Unit,
    onEditChannel: (Channel) -> Unit,
    onToggleChannel: (Channel, Boolean) -> Unit,
    onDeleteChannel: (Channel) -> Unit,
    onSyncChannels: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenUpdate: () -> Unit,
    onOpenProvision: () -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .imePadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                Text("设置", style = MaterialTheme.typography.titleLarge)
            }

            // 侧边栏深链：滚到指定分区
            val scrollState = rememberScrollState()
            var powerY by remember { mutableIntStateOf(0) }
            var channelsY by remember { mutableIntStateOf(0) }
            var npmY by remember { mutableIntStateOf(0) }
            LaunchedEffect(section, powerY, channelsY, npmY) {
                val target = when (section) {
                    "power" -> powerY
                    "channels" -> channelsY
                    "npm" -> npmY
                    else -> -1
                }
                if (target > 0) scrollState.animateScrollTo((target - 24).coerceAtLeast(0))
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp),
            ) {
                // 运行模式：**单模式版**（0.3.0 起两个 App 各自锁死一条路）
                //   —— 不再提供"切换模式"：Root 版只走真 chroot，免 root 版只走 proot/proroot，
                //   想换一条路就装另一个 App（两个包名不同，可以同时装）。
                DshCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        SectionLabel("运行模式（本版固定）")
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Pill(Edition.labelShort, color = Accent, filled = true)
                            Spacer(Modifier.width(8.dp))
                            Pill(effectiveMode.modeLabel, color = StateRunning, filled = true)
                            if (Edition.needsSu) {
                                Spacer(Modifier.width(8.dp))
                                Pill(
                                    text = if (suAvailable == true) "su 可用" else "无 su（去授权）",
                                    color = if (suAvailable == true) StateRunning else WarnTone,
                                    filled = true,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (Edition.isRoot) {
                                "本版是 **Root 版**：" + Edition.applicationId + "，只走真 root + chroot 那条路" +
                                    "（环境由 KernelSU 模块开机启动，与 App 生命周期解耦）。" +
                                    "设备没有 root 就装「免 root 版」，两个 App 可以同时安装。"
                            } else {
                                "本版是 **免 root 版**：" + Edition.applicationId + "，不需要 root、不需要刷机" +
                                    "（环境铺在 App 私有目录，随 App 进程存活）。" +
                                    "设备能 root 的话，Root 版的体验更好，两个 App 可以同时安装。"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 免 root 运行时（proroot 首选 / proot 降级）
                DshCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        SectionLabel("免 root 运行时")
                        Spacer(Modifier.height(10.dp))
                        ModeRow("自动：优先 proroot，缺件降级 proot", rootlessRuntime == null) {
                            onPickRootless(null)
                        }
                        ModeRow("只用 proroot（缺件会明确报错）", rootlessRuntime == "proroot") {
                            onPickRootless("proroot")
                        }
                        ModeRow("只用 proot（随包 bundle）", rootlessRuntime == "proot") {
                            onPickRootless("proot")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "只影响非 root 模式。proroot ${BuildConfig.PROROOT_VERSION} 是 LD_PRELOAD 实现" +
                                "（无 ptrace，系统调用密集的负载更快），随 APK 的 nativeLibraryDir 提供；" +
                                "proot 作为降级实现随包内嵌。",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                        val kind = status?.rootlessKind
                        if (kind != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "本次运行实际使用：$kind" +
                                    (status?.rootlessVersion?.let { " $it" } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = Accent,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 层模式（loop / dir）—— root 模式专用
                DshCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        SectionLabel("层模式（root 模式）")
                        Spacer(Modifier.height(10.dp))
                        ModeRow("默认 loop：losetup + erofs + upper.img（省磁盘）", layerMode == null || layerMode == "loop") {
                            onPickLayerMode(null)
                        }
                        ModeRow("dir：把层解包成目录，不碰 loop/erofs", layerMode == "dir") {
                            onPickLayerMode("dir")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "loop 省磁盘但要占 loop 设备与 erofs 挂载；dir 完全不碰它们（目录 + overlayfs），" +
                                "代价是首次解包几分钟、磁盘约 +1.6 GB。真机上 loop/erofs 出问题时切到 dir 即可。",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                        status?.layerMode?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "本次 start 实际使用：$it",
                                style = MaterialTheme.typography.labelSmall,
                                color = Accent,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 端口
                DshCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        SectionLabel("服务端口")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = port,
                            onValueChange = onPortChange,
                            label = { Text("端口") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "仅作为参考值与部署时的默认值；实际端口以 linuxctl status 为准" +
                                (status?.dshPort?.let { "（当前 $it）" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 服务与自启
                DshCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        SectionLabel("服务与自启")
                        Spacer(Modifier.height(6.dp))
                        SwitchRow(
                            title = "开机自启状态服务",
                            subtitle = "开机后拉起状态观察服务（root 模式的环境仍由 KernelSU 模块启动）",
                            checked = bootStart,
                            onCheckedChange = onBootStart,
                        )
                        SwitchRow(
                            title = "打开 App 时自动启动环境",
                            subtitle = "仅在已部署且当前处于停止状态时生效",
                            checked = autoStart,
                            onCheckedChange = onAutoStart,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 电池与通知
                DshCard(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { powerY = it.positionInParent().y.toInt() },
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        SectionLabel("省电与通知")
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Pill(
                                text = if (batteryExempt) "已加入电池优化白名单" else "未加入白名单",
                                color = if (batteryExempt) StateRunning else WarnTone,
                                filled = true,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onRequestBattery, enabled = !batteryExempt) {
                                Text("申请白名单")
                            }
                            TextButton(onClick = onOpenBatterySettings) { Text("打开系统设置") }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Pill(
                                text = if (notifGranted) "通知权限已授予" else "通知权限被拒绝",
                                color = if (notifGranted) StateRunning else Danger,
                                filled = true,
                            )
                            Spacer(Modifier.width(8.dp))
                            if (!notifGranted) TextButton(onClick = onRequestNotif) { Text("去授权") }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 冻结豁免提示卡
                FreezeExemptionCard(onCopyPackage = onCopyPackage)

                Spacer(Modifier.height(12.dp))

                // npm 源（功能 B）
                NpmSourceCard(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { npmY = it.positionInParent().y.toInt() },
                )

                Spacer(Modifier.height(12.dp))

                // 频道管理
                DshCard(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { channelsY = it.positionInParent().y.toInt() },
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel("更新频道")
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = onAddChannel) {
                                Icon(Icons.Filled.Add, contentDescription = "新增频道", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Text(
                            text = "频道 = 一个清单 URL + 一个 ed25519 公钥。第三方可自签自建，无需审核。",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                        Spacer(Modifier.height(10.dp))

                        if (channels.isEmpty()) {
                            Text("暂无频道。", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        } else {
                            channels.forEach { ch ->
                                ChannelRow(
                                    channel = ch,
                                    builtin = Channel.isBuiltin(ch.id),
                                    onToggle = { onToggleChannel(ch, it) },
                                    onEdit = { onEditChannel(ch) },
                                    onDelete = { onDeleteChannel(ch) },
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = onSyncChannels,
                            enabled = channels.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("同步到环境 (etc/channels.json)")
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 快捷入口
                DshCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        SectionLabel("快捷入口")
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = onOpenLogs) { Text("环境日志") }
                            TextButton(onClick = onOpenUpdate) { Text("更新") }
                            TextButton(onClick = onOpenProvision) { Text("部署向导") }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 关于
                DshCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        SectionLabel("关于")
                        Spacer(Modifier.height(8.dp))
                        AboutRow("包名", "io.github.sunsetrne.sunsetlinux")
                        AboutRow("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        AboutRow("Linux 侧接口", "linuxctl（docs/architecture.md §3）")
                        AboutRow("环境根", if (effectiveMode == EnvMode.ROOT) "/data/sunsetlinux" else "filesDir/linux（App 私有）")
                    }
                }

                Spacer(Modifier.height(28.dp))
            }
        }

        notice?.let {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 8.dp,
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismissNotice) { Text("知道了") }
                }
            }
        }
    }
}

@Composable
private fun ModeRow(
    title: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else TextMuted,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 墓碑 / 冻结类模块的豁免提示。
 *
 * 这不是可选的"最佳实践"：设备上这类模块会按 per-app 策略用 cgroup freezer
 * 冻结后台应用，被冻结后 App 的状态轮询与通知都会停摆。
 *
 * 措辞刻意**不点名任何具体模块/作者**：这类模块很多，点名既无必要，
 * 也容易让人误以为我们在评价某个第三方实现。只描述「行为」与「怎么豁免」。
 */
@Composable
private fun FreezeExemptionCard(onCopyPackage: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = WarnTone.copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚠️", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "请把本应用加入墓碑/冻结模块豁免",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "部分后台管理类模块（墓碑调度 / 按应用分级冻结）会用 cgroup freezer 冻结后台应用。" +
                    "被冻结后，SunsetLinux 无法轮询环境状态，通知栏的启动/停止/重启也会失效。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "操作指引：\n" +
                    "1. 打开 KernelSU / Magisk 管理器 → 模块，找到你安装的后台冻结类模块的设置；\n" +
                    "2. 在「应用策略 / 白名单」里找到 SunsetLinux（包名 io.github.sunsetrne.sunsetlinux）；\n" +
                    "3. 策略设为「不冻结 / 白名单」并保存。\n" +
                    "root 模式下环境本体不依赖 App 进程 —— 即使 App 被冻结，DSH 仍在运行，" +
                    "只是你看不到状态与通知。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onCopyPackage) { Text("复制应用包名") }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    builtin: Boolean = false,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(channel.name, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(6.dp))
                // 内置频道：URL 与公钥来自代码（信任根），只允许启用/停用
                if (builtin) {
                    Pill("内置", color = StateRunning)
                }
                if (channel.pubkey.isBlank()) {
                    Pill("无公钥·不验签", color = WarnTone)
                } else {
                    Pill("已签名", color = StateRunning)
                }
            }
            Text(
                text = channel.shortUrl,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = channel.enabled, onCheckedChange = onToggle)
        if (!builtin) {
            IconButton(onClick = onEdit) { Text("改", style = MaterialTheme.typography.labelMedium) }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = Danger, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.width(96.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 新增/编辑频道。公钥留空表示「不验签」——UI 上会明确标注风险。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelDialog(
    initial: Channel?,
    onDismiss: () -> Unit,
    onSave: (Channel) -> Unit,
) {
    var id by mutableStateOf(initial?.id ?: "")
    var name by mutableStateOf(initial?.name ?: "")
    var url by mutableStateOf(initial?.url ?: "")
    var pubkey by mutableStateOf(initial?.pubkey ?: "")
    var enabled by mutableStateOf(initial?.enabled ?: true)
    var priority by mutableStateOf((initial?.priority ?: 100).toString())
    var error by mutableStateOf<String?>(null)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val cleanId = id.trim().ifBlank { name.trim().lowercase().replace(' ', '-') }
                val cleanUrl = url.trim()
                when {
                    cleanId.isBlank() -> error = "请填写频道 ID"
                    !cleanUrl.startsWith("http") -> error = "清单 URL 必须以 http(s):// 开头"
                    else -> onSave(
                        Channel(
                            id = cleanId,
                            name = name.trim().ifBlank { cleanId },
                            url = cleanUrl,
                            pubkey = pubkey.trim().replace("\n", "").replace(" ", ""),
                            enabled = enabled,
                            priority = priority.toIntOrNull() ?: 100,
                        ),
                    )
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text(if (initial == null) "新增频道" else "编辑频道") },
        text = {
            Column {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("ID（唯一）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("显示名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("channel.json URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pubkey,
                    onValueChange = { pubkey = it },
                    label = { Text("ed25519 公钥（base64，可留空）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = priority,
                    onValueChange = { priority = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("优先级（大者优先）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                    Spacer(Modifier.width(8.dp))
                    Text("启用该频道", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    text = "留空公钥 = 不验签，等于放弃来源校验（契约要求验签失败必须拒绝，留空属于用户显式豁免）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = WarnTone,
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}


/** 侧边栏深链用的 extra key。 */
const val EXTRA_SETTINGS_SECTION = "io.github.sunsetrne.sunsetlinux.extra.SETTINGS_SECTION"

/** 打开设置页；[section] 为 null / "channels" / "power"。 */
fun settingsIntent(context: android.content.Context, section: String? = null): android.content.Intent =
    android.content.Intent(context, SettingsActivity::class.java)
        .putExtra(EXTRA_SETTINGS_SECTION, section)
