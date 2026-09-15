package io.github.sunsetrne.sunsetlinux.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sunsetrne.sunsetlinux.ui.components.CapsuleReserve
import io.github.sunsetrne.sunsetlinux.BuildConfig
import io.github.sunsetrne.sunsetlinux.core.DshPaths
import io.github.sunsetrne.sunsetlinux.core.EnvMode
import io.github.sunsetrne.sunsetlinux.ui.components.Pill
import io.github.sunsetrne.sunsetlinux.ui.theme.WarnTone
import io.github.sunsetrne.sunsetlinux.ui.theme.BrushStart
import io.github.sunsetrne.sunsetlinux.ui.theme.Mono1
import io.github.sunsetrne.sunsetlinux.ui.theme.MonoFamily
import io.github.sunsetrne.sunsetlinux.ui.theme.Danger
import io.github.sunsetrne.sunsetlinux.ui.theme.TextSecondary
import io.github.sunsetrne.sunsetlinux.ui.theme.TextMuted
import io.github.sunsetrne.sunsetlinux.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/**
 * 底部胶囊导航的高频 tab。
 *
 * ⚠️ 图标只能用 **Material core 的那几十个**：本项目**故意不引入 `material-icons-extended`**
 * （那会让 APK/dex 明显膨胀）。此前这里用了 `Icons.Filled.Extension`（拼图，语义最贴切），
 * 但它属于 extended 图标集 → **编译不过**。改用 core 里的 `Add`（"装/管理插件"）。
 * 以后加图标前请先确认它在 core 集里。
 */
enum class ShellTab(val label: String, val icon: ImageVector) {
    START("启动", Icons.Filled.PlayArrow),
    UPDATE("更新", Icons.Filled.Refresh),
    PLUGINS("插件", Icons.Filled.Add),
    DSH("DSH", Icons.Filled.Home),
}

/**
 * 应用外壳：**顶栏汉堡 + 侧边栏 + 悬浮胶囊底栏（3 tab）**。
 *
 * 设计取向（采用「侧边栏 + 胶囊底栏」的信息架构，但把可观测性补上）：
 * - 设置类入口全部收进侧边栏，首屏只留高频操作；
 * - 底栏只放 3 个高频 tab，选中项胶囊化 + 悬浮阴影；
 * - 状态、阶段、失败原因、日志**永远在首屏**，不需要点进任何二级页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    vm: LauncherViewModel,
    initialTab: ShellTab = ShellTab.START,
    onOpenSettings: (String?) -> Unit,
    onOpenProvision: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenWelcome: () -> Unit,
    onOpenLogScreen: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(initialTab) }
    var showAbout by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    // ── 系统返回：不在首页时回首页（**跟手**的预测性返回）──────────────────
    //
    // 用 `PredictiveBackHandler` 而不是 `BackHandler`：后者只在抬手时回调一次，
    // 拖动过程中界面没有任何反馈 —— 用户实测的"返回不跟手、对系统返回无任何消费"
    // 就是这个观感。这里把进度流式映射成整块内容的水平位移。
    //
    // 注册位置在 `ModalNavigationDrawer` **之前**：抽屉展开时它自己会注册一个
    // 预测性返回回调（`enabled = drawerState.targetValue == Open`，见 material3 源码），
    // 后注册的优先，所以抽屉打开时仍然由抽屉来关。
    val backOffset = remember { Animatable(0f) }
    val backSlideMax = with(LocalDensity.current) { 96.dp.toPx() }
    PredictiveBackHandler(enabled = tab != ShellTab.START) { progress ->
        try {
            progress.collect { event ->
                backOffset.snapTo(event.progress.coerceIn(0f, 1f) * backSlideMax)
            }
            backOffset.snapTo(0f)
            tab = ShellTab.START
        } catch (cancelled: CancellationException) {
            backOffset.animateTo(0f, tween(durationMillis = 180))
            throw cancelled
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Mono1,
                modifier = Modifier.width(300.dp),
            ) {
                DrawerBody(
                    ui = ui,
                    onNavigate = { action ->
                        scope.launch { drawerState.close() }
                        action()
                    },
                    onOpenSettings = onOpenSettings,
                    onOpenProvision = onOpenProvision,
                    onOpenDiagnostics = onOpenDiagnostics,
                    onOpenWelcome = onOpenWelcome,
                    onOpenLogScreen = onOpenLogScreen,
                    onExport = vm::exportReport,
                    onReset = { confirmReset = true },
                    onAbout = { showAbout = true },
                )
            }
        },
    ) {
        // 边到边下窗口不会为键盘让位：由这里统一消费 IME inset，
        // 面板里的输入框（插件包名 / 频道 URL / 本地源）才不会被键盘盖住。
        // 子级（如 DSH WebView）再调 imePadding() 不会叠加 —— windowInsetsPadding
        // 会把它应用过的 inset 从子级可见的 inset 里扣掉。
        Box(
            Modifier
                .fillMaxSize()
                .imePadding()
                // 返回手势的进度 → 整块内容右移（跟手）
                .offset { IntOffset(backOffset.value.roundToInt(), 0) },
        ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            // 顶栏：与状态栏同色（沉浸式）
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Filled.Menu, contentDescription = "打开侧边栏")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = when (tab) {
                                ShellTab.START -> "SunsetLinux"
                                ShellTab.UPDATE -> "更新"
                                ShellTab.PLUGINS -> "插件"
                                ShellTab.DSH -> "DSH Web"
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = ui.stage,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ui.state == io.github.sunsetrne.sunsetlinux.core.EnvState.ERROR) Danger else TextMuted,
                            maxLines = 1,
                        )
                    }
                    ModePillSmall(ui)
                    IconButton(onClick = vm::forceRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                }
            }

            if (tab == ShellTab.DSH) {
                // DSH tab：底栏放内容下方（不遮挡网页），其余 tab 用悬浮样式
                Column(Modifier.weight(1f)) {
                    Box(Modifier.weight(1f)) {
                        DshWebPane(
                            modifier = Modifier.fillMaxSize(),
                            showHeader = false,
                            onBack = { tab = ShellTab.START },
                            onGoHome = { tab = ShellTab.START },
                        )
                    }
                    CapsuleBar(
                        selected = tab,
                        onSelect = { tab = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            } else {
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        ShellTab.START -> LauncherHomePane(
                            vm = vm,
                            modifier = Modifier.fillMaxSize(),
                            onOpenWeb = { tab = ShellTab.DSH },
                            onOpenProvision = onOpenProvision,
                            onOpenUpdates = { tab = ShellTab.UPDATE },
                            onOpenDiagnostics = onOpenDiagnostics,
                            onOpenSettings = { onOpenSettings(null) },
                            onExportReport = vm::exportReport,
                        )

                        ShellTab.UPDATE -> {
                            val updateState = rememberUpdatePaneState()
                            androidx.compose.runtime.LaunchedEffect(Unit) { updateState.check() }
                            UpdatePane(
                                state = updateState,
                                modifier = Modifier.fillMaxSize(),
                                onOpenSettings = { onOpenSettings("channels") },
                                showHeader = false,
                            )
                        }

                        ShellTab.PLUGINS -> {
                            val pluginsState = rememberPluginsPaneState()
                            androidx.compose.runtime.LaunchedEffect(Unit) { pluginsState.refresh() }
                            PluginsPane(state = pluginsState, modifier = Modifier.fillMaxSize())
                        }

                        ShellTab.DSH -> Unit // 上面已单独处理
                    }

                    CapsuleBar(
                        selected = tab,
                        onSelect = { tab = it },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 12.dp),
                    )
                }
            }
        }

        // 一次性提示（含"操作失败：…"）：浮层横幅，不打断操作
        ui.message?.let { text ->
            MessageBanner(
                text = text,
                isError = text.startsWith("操作失败") || text.contains("失败"),
                onDismiss = vm::dismissMessage,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = CapsuleReserve),
            )
        }
        }
    }

    if (ui.showDoctor) {
        DoctorDialog(
            running = ui.doctorRunning,
            output = ui.doctorOutput ?: "",
            onClose = vm::closeDoctor,
            onRerun = vm::doctor,
            onExport = vm::exportReport,
        )
    }

    ui.rawJson?.let { raw ->
        TextDialog(title = "原始 status JSON（§3.1）", body = raw, onClose = vm::closeRawJson)
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("恢复出厂设置？") },
            text = {
                Text(
                    "这会**清空环境的可写层**（/data/sunsetlinux/upper.img 或 proot 的 rootfs 可写内容），" +
                        "已安装的 DSH 与你在环境内的所有数据都会丢失，只读的层与频道配置保留。\n\n" +
                        "此操作不可撤销。设备上如果有重要数据，请先在环境内备份。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    vm.resetEnvironment()
                }) { Text("确认清空", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
        )
    }

    if (showAbout) {
        AboutDialog(ui = ui, onClose = { showAbout = false }, onExport = vm::exportReport)
    }
}

@Composable
private fun MessageBanner(
    text: String,
    isError: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 10.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

// ────────────────────────────────────────────────────────────── 侧边栏

@Composable
private fun DrawerBody(
    ui: LauncherViewModel.UiState,
    onNavigate: (() -> Unit) -> Unit,
    onOpenSettings: (String?) -> Unit,
    onOpenProvision: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenWelcome: () -> Unit,
    onOpenLogScreen: () -> Unit,
    onExport: () -> Unit,
    onReset: () -> Unit,
    onAbout: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = "SunsetLinux",
                style = MaterialTheme.typography.headlineSmall.copy(brush = BrushStart),
            )
            Text(
                text = "v${BuildConfig.VERSION_NAME} · ${ui.mode.modeLabel}" +
                    if (ui.suAvailable) " · su 可用" else " · 无 su",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(ui.state.label, color = io.github.sunsetrne.sunsetlinux.ui.theme.stateColor(ui.state), filled = true, leadingDot = true)
                if (ui.provisioned == false) Pill("未部署", color = WarnTone)
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(8.dp))

        DrawerItem(Icons.Filled.Refresh, "重新部署 / 首启引导", "选模式、装模块、铺层") {
            onNavigate(onOpenWelcome)
        }
        DrawerItem(Icons.Filled.Build, "一键诊断 (doctor)", if (ui.doctorRunning) "正在运行…" else "内核能力 / 层完整性 / SELinux") {
            onNavigate(onOpenDiagnostics)
        }
        DrawerItem(Icons.Filled.Share, "导出排障包", "status + 日志 + 崩溃栈，发给开发者") {
            onNavigate(onExport)
        }
        DrawerItem(Icons.AutoMirrored.Filled.List, "完整日志", "全屏查看 linuxctl logs") {
            onNavigate(onOpenLogScreen)
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(8.dp))

        DrawerItem(Icons.Filled.Settings, "设置", "端口 / 自启 / 通知") {
            onNavigate { onOpenSettings(null) }
        }
        DrawerItem(Icons.AutoMirrored.Filled.List, "频道管理", "URL + ed25519 公钥，可增删改") {
            onNavigate { onOpenSettings("channels") }
        }
        DrawerItem(Icons.Filled.Lock, "冻结与省电豁免", "墓碑模块豁免 + 电池白名单") {
            onNavigate { onOpenSettings("power") }
        }
        DrawerItem(Icons.Filled.Refresh, "npm 源（registry）", "官方 / 国内镜像 / 自定义") {
            onNavigate { onOpenSettings("npm") }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(8.dp))

        DrawerItem(Icons.Filled.Delete, "恢复出厂（清空可写层）", "不可撤销，数据会丢", danger = true) {
            onNavigate(onReset)
        }
        DrawerItem(Icons.Filled.Info, "关于", "版本 / 环境根 / 契约") {
            onNavigate(onAbout)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DrawerItem(
    icon: ImageVector,
    title: String,
    supporting: String? = null,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (danger) Danger else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (danger) Danger else MaterialTheme.colorScheme.onSurface,
            )
            supporting?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
        }
    }
}

// ────────────────────────────────────────────────────────────── 胶囊底栏

@Composable
private fun CapsuleBar(
    selected: ShellTab,
    onSelect: (ShellTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = Mono1,
        shadowElevation = 12.dp,
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShellTab.entries.forEach { entry ->
                val active = entry == selected
                val shape = RoundedCornerShape(999.dp)
                Surface(
                    modifier = Modifier
                        .clip(shape)
                        .clickable { onSelect(entry) },
                    shape = shape,
                    color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = entry.icon,
                            contentDescription = null,
                            tint = if (active) MaterialTheme.colorScheme.primary else TextMuted,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = entry.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (active) MaterialTheme.colorScheme.primary else TextMuted,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModePillSmall(ui: LauncherViewModel.UiState) {
    val (label, color) = when {
        ui.provisioned == false -> "未部署" to TextMuted
        ui.mode == EnvMode.ROOT && !ui.suAvailable -> "ROOT·无 su" to WarnTone
        ui.mode == EnvMode.ROOT -> "ROOT" to MaterialTheme.colorScheme.primary
        else -> "PROOT" to TextSecondary
    }
    Pill(text = label, color = color, filled = true, leadingDot = true)
}

// ────────────────────────────────────────────────────────────── 对话框

@Composable
fun DoctorDialog(
    running: Boolean,
    output: String,
    onClose: () -> Unit,
    onRerun: () -> Unit,
    onExport: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
        dismissButton = {
            Row {
                TextButton(onClick = onRerun, enabled = !running) { Text("重新自检") }
                TextButton(onClick = onExport) { Text("导出") }
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("环境自检 (doctor)")
                if (running) {
                    Spacer(Modifier.width(10.dp))
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
        },
        text = {
            Column {
                Box(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = output.ifBlank { "等待输出…" },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
                        color = TextSecondary,
                        textAlign = TextAlign.Start,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "自检覆盖内核能力、层完整性、端口占用与 SELinux denials。" +
                        "把上面这段（或「导出排障包」）发给开发者即可定位问题。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
        },
    )
}

@Composable
fun TextDialog(title: String, body: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
        title = { Text(title) },
        text = {
            Box(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
                    color = TextSecondary,
                )
            }
        },
    )
}

@Composable
private fun AboutDialog(ui: LauncherViewModel.UiState, onClose: () -> Unit, onExport: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
        dismissButton = { TextButton(onClick = onExport) { Text("导出排障包") } },
        title = { Text("关于 SunsetLinux") },
        text = {
            Column {
                AboutLine("应用版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                AboutLine("包名", "io.github.sunsetrne.sunsetlinux")
                AboutLine("运行模式", ui.mode.modeLabel + if (ui.suAvailable) "（su 可用）" else "（无 su）")
                AboutLine("环境根", DshPaths.linuxHome(context, ui.mode))
                AboutLine("Linux 侧接口", "linuxctl / status JSON（§3.1 冻结）")
                AboutLine("当前阶段", ui.stage)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "本应用不解析 rootfs、不自行挂载：所有环境操作都只经 linuxctl。" +
                        "Root 模式下环境由 KernelSU 模块开机启动，与 App 生命周期解耦。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "WebView 加载的登录地址带一次性令牌，不写入任何缓存，每次打开重新获取。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
        },
    )
}

@Composable
private fun AboutLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            modifier = Modifier.width(92.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
