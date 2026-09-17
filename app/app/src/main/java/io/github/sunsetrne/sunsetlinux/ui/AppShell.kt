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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sunsetrne.sunsetlinux.ui.components.CapsuleReserve
import io.github.sunsetrne.sunsetlinux.BuildConfig
import io.github.sunsetrne.sunsetlinux.core.DshPaths
import io.github.sunsetrne.sunsetlinux.core.DshPin
import io.github.sunsetrne.sunsetlinux.core.Edition
import io.github.sunsetrne.sunsetlinux.core.EnvMode
import io.github.sunsetrne.sunsetlinux.core.EnvState
import io.github.sunsetrne.sunsetlinux.core.TerminalStatusUi
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
 * 外壳里的页面与入口分工（2026-09-16 按真机使用反馈调整）。
 *
 * - **底栏（悬浮胶囊）只放 3 个高频页**：启动 / 插件 / 终端；
 * - **DSH 移到顶栏图标**：点开就是 DSH Web，返回手势/返回键回主界面 ——
 *   它原先占底栏第 4 格，标签被挤到换行，而且"打开 DSH"与"启动环境"本来就不是同一层级；
 * - **更新移到侧边栏**：低频，但要从任何页都够得着。
 *
 * ⚠️ 图标只能用 **Material core 的那几十个**：本项目**故意不引入 `material-icons-extended`**
 * （那会让 APK/dex 明显膨胀）。此前这里用了 `Icons.Filled.Extension`（拼图，语义最贴切），
 * 但它属于 extended 图标集 → **编译不过**。改用 core 里的 `Add`（"装/管理插件"）。
 * 以后加图标前请先确认它在 core 集里。
 *
 * 因此 `icon` 允许为 **null**：终端在 core 集里没有对应图标（`Terminal` 属于 extended），
 * 就用等宽字形的 `>_` 当图标 —— 见 [CapsuleBar]。
 */
enum class ShellTab(val label: String, val icon: ImageVector?, val inCapsule: Boolean) {
    START("启动", Icons.Filled.PlayArrow, true),
    PLUGINS("插件", Icons.Filled.Add, true),
    TERMINAL("终端", null, true),
    UPDATE("更新", Icons.Filled.Refresh, false),
    DSH("DSH", Icons.Filled.Home, false),
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

    // 终端会话挂在**外壳**上（不在 tab 分支里）：切到别的 tab 再切回来，会话与滚动都还在；
    // 只有外壳销毁（退出 App / Activity 重建）才断开 —— 否则会留下没人管的 shell 进程。
    val terminalState = rememberTerminalPaneState()
    DisposableEffect(Unit) {
        onDispose { terminalState.dispose() }
    }

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
                    onOpenUpdates = { tab = ShellTab.UPDATE },
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
                                ShellTab.TERMINAL -> "终端"
                                ShellTab.DSH -> "DSH Web"
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        // 副标题：终端页给一句**自成一句、不依赖上下文**的说明。
                        //
                        // 为什么终端页不直接显示 `ui.stage`：那是全页通用的状态短语（例如
                        // 「失败（未知，建议跑一键诊断）」），挤在顶栏这条窄光里会被裁成
                        // 「失败（未知，建议跑一键诊…」—— 用户看到一句半截话，比没有还糟。
                        // 终端页真正的前置条件是"环境在跑"，说明白这件事 + 下一步动作就够了。
                        //
                        // maxLines / overflow **只对终端页放宽**（2 行 + 省略号）：其它页
                        // 保持原来的一行裁剪，不动既有观感。终端页那句本身很短
                        // （见 TerminalStatusUi.subtitle 的长度约束），两行足够说完。
                        val terminalTab = tab == ShellTab.TERMINAL
                        Text(
                            text = if (tab == ShellTab.TERMINAL) {
                                TerminalStatusUi.subtitle(ui.state == EnvState.RUNNING, ui.mode)
                            } else {
                                ui.stage
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ui.state == io.github.sunsetrne.sunsetlinux.core.EnvState.ERROR) Danger else TextMuted,
                            maxLines = if (terminalTab) 2 else 1,
                            overflow = if (terminalTab) TextOverflow.Ellipsis else TextOverflow.Clip,
                        )
                    }
                    // DSH 入口：从底栏挪到顶栏 —— 随时可点，也不再挤占底栏那一格。
                    // 点开是 DSH Web 页面；返回手势/返回键回主界面（见 PredictiveBackHandler 与 DshWebPane.onBack）。
                    IconButton(onClick = { tab = ShellTab.DSH }) {
                        Icon(
                            imageVector = Icons.Filled.Home,
                            contentDescription = "打开 DSH",
                            tint = if (tab == ShellTab.DSH) MaterialTheme.colorScheme.primary else TextSecondary,
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

                        ShellTab.TERMINAL -> TerminalPane(
                            state = terminalState,
                            mode = ui.mode,
                            envRunning = ui.state == EnvState.RUNNING,
                            onGoStart = { tab = ShellTab.START },
                            // 只有 Root 版能"只起环境"：免 root 版的环境与 DSH 一体，
                            // 传 null 让终端退回"去启动页"这一条路（见 EditionPolicy）
                            onStartEnvOnly = if (Edition.showsSplitStartUi) {
                                { vm.startEnvOnly() }
                            } else {
                                null
                            },
                            modifier = Modifier.fillMaxSize(),
                        )

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
    onOpenUpdates: () -> Unit,
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
                // su 那一截只在 Root 版显示：免 root 版写"无 su"会让用户以为"少了什么、
                // 该去授权"，而它本来就不需要 su（Edition.needsSu 的语义就是这个）
                text = "v${BuildConfig.VERSION_NAME} · ${ui.mode.modeLabel}" +
                    if (!Edition.needsSu) "" else if (ui.suAvailable) " · su 可用" else " · 无 su",
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

        // 更新从底栏挪到侧边栏：低频，但要从任何页面都够得着（底栏只留启动/插件/终端）
        DrawerItem(Icons.Filled.Refresh, "更新", "频道清单 / 层更新，只下变化的那一层") {
            onNavigate(onOpenUpdates)
        }
        // 说明文字必须按 edition 分岔：免 root 版里"装模块"是一条走不通的路
        //（那台机器上没有 KernelSU），点了只会一头雾水 —— 真机反馈过
        DrawerItem(
            Icons.Filled.Refresh,
            "重新部署 / 首启引导",
            if (Edition.showsModuleUi) "装模块、铺层、部署环境" else "铺运行时、铺环境、启动",
        ) {
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
            ShellTab.entries.filter { it.inCapsule }.forEach { entry ->
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
                        val tint = if (active) MaterialTheme.colorScheme.primary else TextMuted
                        if (entry.icon != null) {
                            Icon(
                                imageVector = entry.icon,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(17.dp),
                            )
                        } else {
                            // 终端：core 图标集里没有 Terminal（extended 才有，本项目不引），
                            // 用等宽字形的 ">_" 当图标 —— 零依赖且一眼就是终端。
                            Text(
                                text = ">_",
                                fontFamily = MonoFamily,
                                fontSize = 13.sp,
                                lineHeight = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = tint,
                            )
                        }
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = entry.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = tint,
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
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // ★ "关于"要能真的回答"我装的是哪个包、什么版本、环境缺不缺东西" ——
                //   以前只有 VERSION_NAME，内置组合的名字都看不出来（真机反馈）。
                AboutLine("应用版本", BuildConfig.STANDARD_VERSION)
                AboutLine("工程版本", "${BuildConfig.ENGINEERING_VERSION} (${BuildConfig.VERSION_CODE})")
                AboutLine("构建时间", BuildConfig.BUILD_TIME)
                AboutLine("git", BuildConfig.GIT_HASH)
                AboutLine("本机包", "${BuildConfig.EMBED_LABEL} · ${BuildConfig.EMBED_VARIANT}")
                AboutLine("内嵌内容", BuildConfig.EMBED_PARTS.ifBlank { "无（装环境要联网）" })
                AboutLine("本版", "${Edition.label}（${Edition.applicationId}）")
                AboutLine("包名", context.packageName)
                AboutLine("运行模式", ui.mode.modeLabel + if (!Edition.needsSu) "" else if (ui.suAvailable) "（su 可用）" else "（无 su）")
                // ★ 模块那一行**只在 Root 版**显示：免 root 版连"模块"两个字都不该出现
                //   （docs/module-variants.md §一：不探测、不提示、不内嵌）。
                if (Edition.showsModuleUi) AboutLine("模块", ui.moduleLabel ?: "检测中…")
                // ★ 免 root 运行时的归属（proroot 许可第 5 条要求 attribution）与实况
                //   —— 只有免 root 版带 proroot/proot；Root 版里印这段等于把另一个 edition 的东西搬过来
                if (Edition.showsRootlessRuntimeUi) AboutLine(
                    "免 root 运行时",
                    buildString {
                        append("proroot ${BuildConfig.PROROOT_VERSION}（首选）+ proot（降级）")
                        val kind = ui.status?.rootlessKind
                        if (kind != null) {
                            append("　本次：")
                            append(kind)
                            ui.status?.rootlessVersion?.let { append(" ").append(it) }
                        }
                    },
                )
                AboutLine("环境根", DshPaths.linuxHome(context, ui.mode))
                // 层模式（loop / dir）是 root 模式独有的启动方式（proot 是把 base 层解成 rootfs）
                if (Edition.showsLayerModeUi) AboutLine("层模式", ui.status?.layerMode ?: "—（未启动）")
                // 内置 DSH（随 APK 冻结）与运行时 DSH（真正在跑的）—— 两者不同不是错误，
                // 但要让人一眼看到"现在跑的是哪一个"（详情在「更新」页，含一键回滚）。
                runCatching { DshPin.of(context, ui.status?.layer("dsh")?.version) }.getOrNull()?.let { pin ->
                    AboutLine("DSH（内置/运行时）", "${pin.embedded ?: "无"} / ${pin.runtime ?: "无"} · ${pin.label}")
                }
                AboutLine("Linux 侧接口", "linuxctl / status JSON（§3.1 冻结）")
                AboutLine("当前阶段", ui.stage)
                Spacer(Modifier.height(10.dp))
                // ★ 模块更新要"实际可用"：以前只能看到版本号，下一步得自己去 GitHub 找 zip、
                //   再打开 KernelSU 管理器手装。这里直接下载 + ksud 刷入（重启由用户决定）。
                // ⚠️ 只有 **Root 版**渲染（`Edition.showsModuleUi`）。免 root 版**整块不出现**
                //   —— 连"本版不使用 KernelSU 模块"这种说明都不要写：提模块本身就是痕迹，
                //   而 docs/module-variants.md §六 的验收判据是"不出现任何「KernelSU 模块」字样"。
                if (Edition.showsModuleUi) {
                    ModuleUpdateCard(
                        installedVersion = ui.moduleVersion,
                        installedReadable = ui.moduleReadable,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (Edition.showsModuleUi) {
                        "本应用不解析 rootfs、不自行挂载：所有环境操作都只经 linuxctl。" +
                            "Root 模式下环境由 KernelSU 模块开机启动，与 App 生命周期解耦。"
                    } else {
                        "本应用不解析 rootfs、不自行挂载：所有环境操作都只经 linuxctl。" +
                            "免 root 模式下环境铺在 App 私有目录、随 App 进程存活（卸载 App 即清除）。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
                // proroot 的 attribution 只在免 root 版有意义：Root 版 APK 里根本没有那几个 .so
                if (Edition.showsRootlessRuntimeUi) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "免 root 模式的运行时是 proroot（第三方，https://github.com/coderredlab/proroot，" +
                            "专有许可，未做修改，仅随本 APK 分发；全文见 assets/licenses/proroot-LICENSE.txt）；" +
                            "它不可用时自动降级到随包的 proot。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "本版是「${BuildConfig.EDITION_LABEL}」的内置${BuildConfig.EMBED_LABEL}：" +
                        "同一个 App 内换内置档位（最小 / Ubuntu / 完整离线）＝覆盖安装，数据不丢；" +
                        "Root 版与免 root 版是两个不同的 App（包名不同），可以同时装、互不影响。",
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
