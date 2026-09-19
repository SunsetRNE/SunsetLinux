package io.github.sunsetrne.sunsetlinux

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.sunsetrne.sunsetlinux.core.DshRuntime
import io.github.sunsetrne.sunsetlinux.core.DshStatus
import io.github.sunsetrne.sunsetlinux.core.Edition
import io.github.sunsetrne.sunsetlinux.core.EnvMode
import io.github.sunsetrne.sunsetlinux.core.Prefs
import io.github.sunsetrne.sunsetlinux.ui.components.DshCard
import io.github.sunsetrne.sunsetlinux.ui.components.Pill
import io.github.sunsetrne.sunsetlinux.ui.components.SectionLabel
import io.github.sunsetrne.sunsetlinux.ui.theme.Accent
import io.github.sunsetrne.sunsetlinux.ui.theme.MonoFamily
import io.github.sunsetrne.sunsetlinux.ui.theme.StateRunning
import io.github.sunsetrne.sunsetlinux.ui.theme.SunsetLinuxTheme
import io.github.sunsetrne.sunsetlinux.ui.theme.TextMuted
import io.github.sunsetrne.sunsetlinux.ui.theme.TextSecondary
import io.github.sunsetrne.sunsetlinux.ui.theme.WarnTone
import io.github.sunsetrne.sunsetlinux.ui.theme.applyDshSystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 设置页 —— **只装"没有独立入口"的通用项**。
 *
 * ## 2026-09 拆分（真机反馈："设置页实在是太长了"）
 *
 * 判据是用户给的那条：**侧边栏里已经有对应标签的内容，一律抽成独立页**。
 * 于是下面三块搬去了外壳里各自的页（侧边栏入口 → `ShellTab`）：
 *
 * | 原位置 | 现页面 | 为什么 |
 * |---|---|---|
 * | 更新频道（列表/增删改/同步） | `ui/ChannelsPane.kt`（ShellTab.CHANNELS） | 侧边栏本来就有「频道管理」入口 |
 * | 省电与通知 + 墓碑冻结豁免 | `ui/PowerPane.kt`（ShellTab.POWER） | 侧边栏本来就有「冻结与省电豁免」入口 |
 * | npm 源 | `ui/SourcesPane.kt`（ShellTab.SOURCES） | 侧边栏本来就有「npm 源」入口；顺手加了 Python 源 |
 *
 * 留在本页的是真的没有独立入口、且彼此构成一条"运行与启动"阅读流的项：
 * 运行模式（本版固定）、免 root 运行时、层模式、服务端口、服务与自启，
 * 外加「快捷入口」（从设置这一页直接跳到别的页）与「关于」。
 * 它们**不拆**的理由写在 [SettingsScreen] 上方。
 *
 * 原先的 `section` 深链（channels/power/npm + animateScrollTo）随三块内容一起删掉了：
 * 现在那三页是一对一的入口，不再需要"进设置页再滚到中段"。
 */
class SettingsActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    /** 免 root 运行时偏好：null = auto（优先 proroot），也可固定 proroot / proot。 */
    private val rootlessRuntime = mutableStateOf<String?>(null)

    /** 层模式偏好：null/loop = 默认；dir = 解包成目录（不碰 loop/erofs）。 */
    private val layerMode = mutableStateOf<String?>(null)
    private val suAvailable = mutableStateOf<Boolean?>(null)
    private val effectiveMode = mutableStateOf(EnvMode.ROOT)
    private val port = mutableStateOf("3080")
    private val bootStart = mutableStateOf(false)
    private val autoStart = mutableStateOf(false)
    private val notice = mutableStateOf<String?>(null)
    private val status = mutableStateOf<DshStatus?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDshSystemBars()
        prefs = Prefs(this)

        rootlessRuntime.value = prefs.rootlessRuntime
        layerMode.value = prefs.layerMode
        port.value = prefs.port.toString()
        bootStart.value = prefs.bootStartService
        autoStart.value = prefs.autoStartEnv

        setContent {
            SunsetLinuxTheme {
                SettingsScreen(
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
                    notice = notice.value,
                    status = status.value,
                    onBack = { finish() },
                    onPortChange = {
                        port.value = it.filter { c -> c.isDigit() }.take(5)
                        port.value.toIntOrNull()?.let { p -> prefs.port = p }
                    },
                    onBootStart = { bootStart.value = it; prefs.bootStartService = it },
                    onAutoStart = { autoStart.value = it; prefs.autoStartEnv = it },
                    onDismissNotice = { notice.value = null },
                    onOpenLogs = { startActivity(Intent(this, LogActivity::class.java)) },
                    onOpenUpdate = { startActivity(Intent(this, UpdateActivity::class.java)) },
                    onOpenProvision = { startActivity(Intent(this, ProvisionActivity::class.java)) },
                )
            }
        }

        refreshProbe()
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

    private fun withMain(block: () -> Unit) = runOnUiThread(block)
}

// ─────────────────────────────────────────────────────────────── Compose 层

/**
 * 设置页的剩余内容。
 *
 * ## 为什么这几块**不**再拆（写给下一个想动手的人）
 *
 * 1. 它们没有独立入口：端口、自启、层模式、免 root 运行时都只在这里出现，
 *    按"侧边栏已有入口就抽走"的判据，它们本来就不该动；
 * 2. 拆完三块之后本页只剩 5 张卡 + 关于，一屏到两屏，已经不是"太长"的那个问题
 *    （原来的长度大半来自频道列表、省电/冻结说明和源卡片）；
 * 3. 这几项是一条**同一条阅读流**："这个环境怎么跑起来" —— 模式 → 运行时 → 层 →
 *    端口 → 自启。拆成 5 个页面会让"想确认自己环境怎么配的"变成点 5 次。
 *
 * 反过来说：**不要**为了凑数把「关于」也拆出去 —— 它短、且没有需要独立成页的信息量。
 */
@Composable
private fun SettingsScreen(
    rootlessRuntime: String?,
    onPickRootless: (String?) -> Unit,
    layerMode: String?,
    onPickLayerMode: (String?) -> Unit,
    suAvailable: Boolean?,
    effectiveMode: EnvMode,
    port: String,
    bootStart: Boolean,
    autoStart: Boolean,
    notice: String?,
    status: DshStatus?,
    onBack: () -> Unit,
    onPortChange: (String) -> Unit,
    onBootStart: (Boolean) -> Unit,
    onAutoStart: (Boolean) -> Unit,
    onDismissNotice: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenUpdate: () -> Unit,
    onOpenProvision: () -> Unit,
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

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
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
                //
                // ★ 只有**免 root 版**才渲染这一组：Root 版走真 chroot，`SUNSETLINUX_ROOTLESS`
                //   递过去也没人读；留着它只会让用户以为"这里还能切运行时"（决策 1：
                //   "root 运行时不再保留任何 proot 降级分支"）。判定收在 EditionPolicy。
                if (Edition.showsRootlessRuntimeUi) {
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
                                text = "proroot ${BuildConfig.PROROOT_VERSION} 是 LD_PRELOAD 实现" +
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
                                        (status.rootlessVersion?.let { " $it" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Accent,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                }

                // 层模式（loop / dir）—— root 模式专用
                // ★ 免 root 版不渲染：proot 是把 base 层**解成一棵 rootfs**，没有"层怎么挂"这回事
                if (Edition.showsLayerModeUi) {
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
                }

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
                            // ★ 说明按 edition 分岔：免 root 版这句原来写"root 模式的环境仍由
                            //   KernelSU 模块启动" —— 在他的机器上既没有 KernelSU，也没有
                            //   另一个 root 模式，提它纯属噪音（决策 1：界面不许留模块痕迹）
                            subtitle = if (Edition.showsModuleUi) {
                                "开机后拉起状态观察服务（环境本身由 KernelSU 模块启动）"
                            } else {
                                "开机后拉起状态观察服务（免 root 环境需要 App 进程存活）"
                            },
                            checked = bootStart,
                            onCheckedChange = onBootStart,
                        )
                        // 决策变更（2026-09-19）：自动启动**不再是开关**，它是默认行为
                        // （「打开 App 即启动环境；进一步的启动只是启动 DSH」）。
                        // 留一行说明而不是一个可以关掉的开关：关掉它就等于把环境变成"要手动按"，
                        // 而那正是这次要去掉的东西。
                        InfoRow(
                            title = "打开 App 时自动启动环境",
                            subtitle = "默认行为（已部署时）：进 App 即确保环境在跑，DSH 在打开时补起",
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 快捷入口
                // ★ 保留而不是删掉：设置是一个**独立 Activity**，没有侧边栏抽屉，
                //   要跳去日志/更新/部署向导只能从别处退出再进 —— 这几个按钮是唯一出口。
                //   它不是"内容"（不承载任何设置项），所以不在"抽成独立页"的范围里。
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
 * 只陈述事实的一行（没有开关）。
 *
 * 决策变更（2026-09-19）后「打开 App 自动启动环境」不再是可关的选项 —— 它是默认行为。
 * 用一个"看起来能关但关了会破坏默认语义"的开关比直接说明更糟，所以这里只印说明。
 */
@Composable
private fun InfoRow(title: String, subtitle: String) {
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

/** 打开设置页。设置页现在只有通用项，不再需要分区参数（见文件头的拆分说明）。 */
fun settingsIntent(context: android.content.Context): Intent =
    Intent(context, SettingsActivity::class.java)
