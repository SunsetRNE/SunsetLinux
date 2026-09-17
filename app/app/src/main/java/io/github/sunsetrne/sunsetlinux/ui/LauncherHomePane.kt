package io.github.sunsetrne.sunsetlinux.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sunsetrne.sunsetlinux.ui.components.CapsuleReserve
import io.github.sunsetrne.sunsetlinux.core.DshPaths
import io.github.sunsetrne.sunsetlinux.core.Edition
import io.github.sunsetrne.sunsetlinux.core.EnvMode
import io.github.sunsetrne.sunsetlinux.core.EnvState
import io.github.sunsetrne.sunsetlinux.core.Hint
import io.github.sunsetrne.sunsetlinux.core.HintTarget
import io.github.sunsetrne.sunsetlinux.core.StartMode
import io.github.sunsetrne.sunsetlinux.core.formatBytes
import io.github.sunsetrne.sunsetlinux.core.isStartModeLocked
import io.github.sunsetrne.sunsetlinux.core.resolveStartMode
import io.github.sunsetrne.sunsetlinux.core.startModeLockNote
import io.github.sunsetrne.sunsetlinux.ui.components.ActionTile
import io.github.sunsetrne.sunsetlinux.ui.components.DshCard
import io.github.sunsetrne.sunsetlinux.ui.components.InfoRow
import io.github.sunsetrne.sunsetlinux.ui.components.LogPanel
import io.github.sunsetrne.sunsetlinux.ui.components.Pill
import io.github.sunsetrne.sunsetlinux.ui.components.PrimaryActionButton
import io.github.sunsetrne.sunsetlinux.ui.components.SectionLabel
import io.github.sunsetrne.sunsetlinux.ui.components.StatusDot
import io.github.sunsetrne.sunsetlinux.ui.theme.WarnTone
import io.github.sunsetrne.sunsetlinux.ui.theme.BrushStart
import io.github.sunsetrne.sunsetlinux.ui.theme.BrushStop
import io.github.sunsetrne.sunsetlinux.ui.theme.Mono2
import io.github.sunsetrne.sunsetlinux.ui.theme.OnAccent
import io.github.sunsetrne.sunsetlinux.ui.theme.MonoFamily
import io.github.sunsetrne.sunsetlinux.ui.theme.Danger
import io.github.sunsetrne.sunsetlinux.ui.theme.Line
import io.github.sunsetrne.sunsetlinux.ui.theme.TextSecondary
import io.github.sunsetrne.sunsetlinux.ui.theme.StateRunning
import io.github.sunsetrne.sunsetlinux.ui.theme.TextMuted
import io.github.sunsetrne.sunsetlinux.ui.theme.TextPrimary
import io.github.sunsetrne.sunsetlinux.ui.theme.stateLabelBold
import io.github.sunsetrne.sunsetlinux.ui.theme.stateNeedsStrongOutline
import io.github.sunsetrne.sunsetlinux.ui.theme.TextSecondary
import io.github.sunsetrne.sunsetlinux.ui.theme.stateColor

/**
 * 启动页：**四段式卡片**（操作 → 状态 → 地址 → 日志）。
 *
 * 参考实现把功能塞进抽屉、把日志藏起来，结果"启动失败时用户无从下手"。
 * 这里反过来：状态与日志永远在首屏，失败时直接给出可点的下一步。
 *
 * ★ 2026-09 真机批注：**「操作」卡整块移到最顶部**。原先它在最下面（日志之后），
 *   首屏要滚到底才能点「一键启动」，而"启动"是这个 App 最常做的一件事。
 *   只换顺序，四段各自的内容不动；卡内新增"一键启动 / 分步启动"切换见下方注释。
 */
@Composable
fun LauncherHomePane(
    vm: LauncherViewModel,
    modifier: Modifier = Modifier,
    onOpenWeb: () -> Unit,
    onOpenProvision: () -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
    onExportReport: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val logs by vm.logLines.collectAsState()
    val context = LocalContext.current
    // 登录令牌默认隐藏：它是凭据，不该一进界面就印在屏幕上
    var revealToken by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // ── ① 操作卡（★ 真机批注：整块移到最顶部）──────────────────
        //
        // "启动"是这个 App 最高频的动作，没有理由让人先滚过三段只读信息才够到它。
        // 卡内仍然只有一份启用判定（core 的 StartControls 纯函数），这里只负责画。
        OperationCard(
            ui = ui,
            vm = vm,
            onOpenWeb = onOpenWeb,
            onOpenProvision = onOpenProvision,
            onOpenUpdates = onOpenUpdates,
            onOpenDiagnostics = onOpenDiagnostics,
        )

        Spacer(Modifier.height(12.dp))

        // ── ② 状态卡 ──────────────────────────────────────────────
        DshCard(
            modifier = Modifier.fillMaxWidth(),
            // 单色下没有红色抢注意力：错误/未知态改用**加亮的描边**把卡片顶出来
            highlighted = stateNeedsStrongOutline(ui.state),
            onClick = vm::showRawJson,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(ui.state)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (ui.loading && ui.status == null) "正在读取…" else ui.state.label,
                            style = MaterialTheme.typography.headlineSmall,
                            color = stateColor(ui.state),
                            // 单色下"加粗"是错误态的主要区分手段
                            fontWeight = if (stateLabelBold(ui.state)) FontWeight.Bold else FontWeight.SemiBold,
                        )
                        Text(
                            text = statusSubtitle(ui),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ModePill(ui)
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))

                // 阶段：失败时这是最有价值的一行
                InfoRow(
                    label = "当前阶段",
                    value = ui.stage,
                    valueColor = when {
                        ui.state == EnvState.ERROR -> Danger
                        ui.provisioned == false -> WarnTone
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                ui.status?.uptimeText?.let { InfoRow("运行时长", it) }
                // 启动方式直接显示：它是"下一步能点哪些按钮"的依据，藏起来用户就得猜
                ui.status?.envMode?.let { InfoRow("启动方式", it.label) }
                ui.status?.pid?.let { InfoRow("进程 PID", it.toString(), mono = true) }
                ui.status?.dshVersion?.let { InfoRow("DSH 版本", it, mono = true) }
                ui.status?.dshPort?.let { InfoRow("监听端口", it.toString(), mono = true) }
                // 「仅启动环境」下 DSH 本来就没起：这时写"无响应"（红字）等于报了一个假故障，
                // 用户会去查端口/日志。如实说"DSH 未启动"才是他要的信息。
                val dshIntentionallyDown = ui.status?.isEnvOnly == true && ui.status?.dshRunning == false
                InfoRow(
                    label = "Web 健康",
                    value = when {
                        dshIntentionallyDown -> "DSH 未启动（仅环境方式）"
                        ui.status?.dshHealthy == true -> "正常（已响应）"
                        ui.status?.dshHealthy == false -> "无响应"
                        else -> "—"
                    },
                    valueColor = when {
                        dshIntentionallyDown -> TextSecondary
                        ui.status?.dshHealthy == true -> StateRunning
                        ui.status?.dshHealthy == false -> Danger
                        else -> TextSecondary
                    },
                )

                ui.status?.layers?.takeIf { it.isNotEmpty() }?.let { layers ->
                    Spacer(Modifier.height(12.dp))
                    SectionLabel("系统层（L0 → L2）")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        layers.forEach { layer ->
                            Pill(
                                text = "${layer.id} ${layer.version ?: "?"}",
                                color = if (layer.mounted == false) Danger else MaterialTheme.colorScheme.primary,
                                filled = true,
                            )
                        }
                    }
                }

                val used = ui.status?.upperUsed
                val total = ui.status?.upperTotal
                if (used != null && total != null && total > 0) {
                    Spacer(Modifier.height(12.dp))
                    SectionLabel("可写层占用  ${formatBytes(used)} / ${formatBytes(total)}")
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (used.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = if (used.toFloat() / total > 0.9f) Danger else MaterialTheme.colorScheme.primary,
                        trackColor = Mono2,
                    )
                }

                ui.status?.lastError?.takeIf { it.isNotBlank() }?.let { err ->
                    Spacer(Modifier.height(12.dp))
                    NoticeBar(text = err, tone = Danger)
                }

                ui.modeNote?.let { note ->
                    Spacer(Modifier.height(10.dp))
                    NoticeBar(text = note, tone = WarnTone)
                }

                // 下一步建议（可点）
                if (ui.hints.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    SectionLabel("下一步")
                    Spacer(Modifier.height(8.dp))
                    ui.hints.take(3).forEach { hint ->
                        HintCard(
                            hint = hint,
                            onAction = { target ->
                                when (target) {
                                    HintTarget.PROVISION, HintTarget.ROOT_GRANT -> onOpenProvision()
                                    HintTarget.DOCTOR -> onOpenDiagnostics()
                                    HintTarget.SETTINGS_PORT, HintTarget.SETTINGS_POWER -> onOpenSettings()
                                    HintTarget.UPDATES -> onOpenUpdates()
                                    HintTarget.START -> vm.start()
                                    HintTarget.LOGS -> onExportReport()
                                }
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── ③ 地址卡 ──────────────────────────────────────────────
        DshCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                SectionLabel("访问地址")
                Spacer(Modifier.height(10.dp))

                // 「仅启动环境」下 DSH 没起，永远拿不到地址：不能一直显示"获取中…"
                // （那会让用户以为再等等就有了）。如实说明并指向「启动 DSH」。
                val dshOff = ui.status?.isEnvOnly == true && ui.status?.dshRunning == false

                AddressRow(
                    label = "本机",
                    value = ui.status?.displayUrl,
                    hint = when {
                        ui.status?.displayUrl != null -> null
                        dshOff -> "DSH 未启动（仅环境方式）：点「启动 DSH」后才有地址"
                        ui.state == EnvState.RUNNING -> "获取中…"
                        else -> "环境未运行"
                    },
                    copyable = ui.status?.displayUrl != null,
                )

                // 登录链接（带一次性令牌）：默认**不显示**凭据，可显式展开/复制。
                // 令牌不写日志、不进任何缓存；展开只是为了让用户能复制到桌面浏览器。
                ui.status?.dshUrl?.let { tokenUrl ->
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "登录链接",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.width(56.dp),
                        )
                        Text(
                            text = if (revealToken) tokenUrl else "••••••••（含一次性令牌，默认隐藏）",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                            color = TextMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { revealToken = !revealToken }) {
                            Text(if (revealToken) "隐藏" else "显示")
                        }
                        TextButton(onClick = { copyToClipboard(context, "DSH 登录链接", tokenUrl) }) {
                            Text("复制")
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))

                // 局域网：明确标为"需开启（后续）"，不伪造能力 —— dsh web 只绑 127.0.0.1
                AddressRow(
                    label = "局域网",
                    value = null,
                    hint = "需开启（后续）：dsh web 只监听 127.0.0.1，局域网访问要另做代理（LanProxy）",
                    copyable = false,
                    muted = true,
                )

                Spacer(Modifier.height(10.dp))
                Text(
                    text = when {
                        ui.status?.dshUrl != null -> "登录地址已就绪（带一次性令牌，界面不显示也不缓存）"
                        // 同上：env-only 下"再等等"是错的，该说的是"去启动 DSH"
                        dshOff -> "登录地址未就绪：现在只有环境在跑，DSH 没启动（点「启动 DSH」后就有）"
                        else -> "登录地址未就绪：环境 running 后由 run/dsh.url 提供"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── ④ 日志卡（常显 220dp）──────────────────────────────────
        DshCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                LogPanel(
                    lines = logs,
                    autoFollow = ui.logAutoFollow,
                    onToggleFollow = { vm.setLogAutoFollow(!ui.logAutoFollow) },
                    onRefresh = { vm.refreshLogsNow() },
                    onExport = onExportReport,
                    emptyText = if (ui.provisioned == false) {
                        "环境尚未部署，没有日志"
                    } else {
                        "暂无日志输出（linuxctl logs -n 200）"
                    },
                    errorText = ui.logError,
                )
            }
        }


        Spacer(Modifier.height(14.dp))

        // 页脚：环境根 + 生命周期说明
        Column {
            Text(
                text = "环境根：${DshPaths.linuxHome(context, ui.mode)}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                color = TextMuted,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (ui.mode == EnvMode.ROOT) {
                    "Root 模式：环境由 KernelSU 模块在开机时启动，与 App 生命周期解耦 —— 杀掉本应用不影响 DSH。"
                } else {
                    "非 root 模式（proot）：环境随 App 进程存活；建议在侧边栏里申请电池白名单与冻结豁免。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }

        Spacer(Modifier.height(CapsuleReserve)) // 给悬浮胶囊底栏留位
        Spacer(Modifier.height(24.dp))
    }
}

// ────────────────────────────────────────────────────────────── 组件

/**
 * 「操作」卡（★ 位于页面最顶部）。
 *
 * ## 一键启动 / 分步启动：只显示一组
 *
 * 五个按钮同时摊开时，用户看到的是一堆灰按钮 + 一句解释，得先读懂矩阵才知道先点哪个。
 * 这里按 [resolveStartMode] 的结果**只画一组**：
 *   · [StartMode.ONE_SHOT]：主按钮「一键启动（环境 + DSH）」+「停止环境」；
 *   · [StartMode.STEPWISE]：仅启动环境 / 启动 DSH / 停止 DSH / 停止环境（2×2）。
 *
 * ⚠️ 切换**只决定显示哪一组**：每个按钮的 enabled 仍然全部取自 [ui.controls]
 * （`StartControls` 纯函数）。这里没有、也不许有第二份"什么时候能点"的判定。
 *
 * 免 root 版（[Edition.showsSplitStartUi] == false）**整块不出现**：那台机器上没有
 * "只起环境"这条路，摆一个切过去只有灰按钮的开关就是在骗人。
 */
@Composable
private fun OperationCard(
    ui: LauncherViewModel.UiState,
    vm: LauncherViewModel,
    onOpenWeb: () -> Unit,
    onOpenProvision: () -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    DshCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            SectionLabel("操作")

            val running = ui.state == EnvState.RUNNING
            val transitional = ui.state == EnvState.STARTING || ui.state == EnvState.STOPPING
            val notProvisioned = ui.provisioned == false
            // 启用矩阵来自 core 的纯函数（StartControls）：面板只画，判定不许有第二份。
            // busy / 过渡态是"临时不可点"，属于界面这一层，所以单独叠加。
            val controls = ui.controls
            val busy = ui.busy || transitional

            if (Edition.showsSplitStartUi) {
                // 实际显示哪一组：环境没跑听用户的；环境在跑按 status 的 env_mode 强制锁定
                // （纯函数 + 单测，见 core/StartModeUi.kt —— 那里写了"运行中为什么必须锁"）。
                val shown = resolveStartMode(
                    envRunning = running,
                    envMode = ui.status?.envMode,
                    userChoice = ui.startMode,
                )
                // 运行中一律锁住切换：起法在 start 那一刻就定死了，换过去只会看到一组点不动的
                // 按钮。解锁的唯一办法是「停止环境」——那句话就印在控件下面。
                val switchLocked = isStartModeLocked(running)

                Spacer(Modifier.height(10.dp))
                StartModeToggle(
                    selected = shown,
                    enabled = !switchLocked,
                    onSelect = vm::setStartMode,
                )
                // 只说"不可点"用户会当成 bug；说到"先停止环境"才是一条走得通的路。
                startModeLockNote(running, ui.status?.envMode)?.let { note ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelSmall,
                        color = WarnTone,
                    )
                }

                Spacer(Modifier.height(12.dp))
                when (shown) {
                    // ── 一键启动：主按钮 + 停止环境 ──
                    //
                    // 为什么"一键启动"这个标签在运行中也不变形（不像免 root 版那样变成
                    // 「停止环境」）：现在有两条起法，主按钮一变形用户就分不清当前生效的是
                    // 哪条路。停止环境单独一张卡片，两个动作的语义才不会打架。
                    StartMode.ONE_SHOT -> {
                        PrimaryActionButton(
                            text = if (notProvisioned) "尚未部署环境" else "一键启动（环境 + DSH）",
                            stopping = false,
                            brush = BrushStart,
                            contentColor = OnAccent,
                            enabled = controls.oneShotEnabled,
                            busy = busy,
                            onClick = { vm.start() },
                        )

                        Spacer(Modifier.height(12.dp))
                        ActionTile(
                            icon = Icons.Filled.Close,
                            label = "停止环境",
                            supporting = if (running) "连同 DSH 一起停" else "环境未运行",
                            enabled = controls.envStopEnabled && !busy,
                            onClick = { vm.stop() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // ── 分步启动：沿用原来那套 2×2 卡片外观 ──
                    StartMode.STEPWISE -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ActionTile(
                                icon = Icons.Filled.PlayArrow,
                                label = "仅启动环境",
                                supporting = when {
                                    running -> "环境已在运行"
                                    else -> "不起 DSH，维护用"
                                },
                                enabled = controls.startEnvOnlyEnabled && !busy,
                                onClick = { vm.startEnvOnly() },
                                modifier = Modifier.weight(1f),
                            )
                            ActionTile(
                                icon = Icons.Filled.Close,
                                label = "停止环境",
                                supporting = if (running) "连同 DSH 一起停" else "环境未运行",
                                enabled = controls.envStopEnabled && !busy,
                                onClick = { vm.stop() },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ActionTile(
                                icon = Icons.Filled.PlayArrow,
                                label = "启动 DSH",
                                supporting = when {
                                    ui.status?.dshRunning == true -> "DSH 已在运行"
                                    !running -> "先起环境"
                                    else -> "接上 DSH Web"
                                },
                                enabled = controls.dshStartEnabled && !busy,
                                onClick = { vm.dshStart() },
                                modifier = Modifier.weight(1f),
                            )
                            ActionTile(
                                icon = Icons.Filled.Close,
                                label = "停止 DSH",
                                supporting = when {
                                    ui.status?.isEnvOnly != true -> "需「仅环境」方式"
                                    ui.status?.dshRunning == true -> "环境继续运行"
                                    else -> "DSH 未在运行"
                                },
                                enabled = controls.dshStopEnabled && !busy,
                                onClick = { vm.dshStop() },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // 互斥判定的理由（为什么有的按钮是灰的、下一步该点哪个）直接印在按钮下面。
                // 只靠置灰，用户会以为是"还没加载好"而不是"这条路不能走"。
                controls.note?.let { note ->
                    Spacer(Modifier.height(12.dp))
                    NoticeBar(
                        text = note,
                        tone = if (controls.noteIsWarning) WarnTone else MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                // ── 免 root（proot）版保持原样：环境与 DSH 一体，只有一键启动/停止 ──
                Spacer(Modifier.height(12.dp))
                val stopping = running || ui.state == EnvState.STOPPING
                PrimaryActionButton(
                    text = when {
                        notProvisioned -> "尚未部署环境"
                        stopping -> "停止环境"
                        else -> "启动环境"
                    },
                    stopping = stopping,
                    brush = if (stopping) BrushStop else BrushStart,
                    // 启动＝白底黑字；停止＝深底白字
                    contentColor = if (stopping) TextPrimary else OnAccent,
                    enabled = !notProvisioned,
                    busy = busy,
                    onClick = { if (stopping) vm.stop() else vm.start() },
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile(
                    icon = Icons.Filled.Home,
                    label = "打开 DSH",
                    supporting = when {
                        ui.canOpenWeb -> ui.status?.displayUrl ?: "WebView"
                        ui.state == EnvState.RUNNING -> "正在获取登录地址…"
                        else -> "环境未运行"
                    },
                    enabled = ui.canOpenWeb,
                    onClick = onOpenWeb,
                    modifier = Modifier.weight(1f),
                )
                ActionTile(
                    icon = Icons.Filled.Refresh,
                    label = "更新",
                    supporting = when {
                        ui.updateCount > 0 -> "${ui.updateCount} 个层可更新"
                        ui.updateChecked -> "已是最新"
                        else -> "未检查"
                    },
                    badge = ui.updateCount,
                    onClick = onOpenUpdates,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile(
                    icon = Icons.Filled.Build,
                    label = "诊断",
                    supporting = "doctor 一键自检",
                    onClick = onOpenDiagnostics,
                    modifier = Modifier.weight(1f),
                )
                ActionTile(
                    icon = Icons.Filled.Info,
                    label = "部署",
                    supporting = if (notProvisioned) "需要执行" else "已部署",
                    onClick = onOpenProvision,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 两段式切换控件：一键启动 / 分步启动。
 *
 * 为什么不用 Material3 的 `SingleChoiceSegmentedButtonRow`：它是 `@ExperimentalMaterial3Api`
 * （跟着 material3 升级就可能改签名），而这里只有两个选项、样式还得跟现有的 Pill / ActionTile
 * 一致 —— 一个 Surface + Row 就够，且不引任何新依赖。
 *
 * [enabled] = false（运行中锁定）时整块降透明度且不可点。**只降透明度是不够的**，
 * 所以调用方必须在它下面同时印一句"为什么锁 + 怎么解锁"（见 [startModeLockNote]）。
 */
@Composable
private fun StartModeToggle(
    selected: StartMode,
    enabled: Boolean,
    onSelect: (StartMode) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            // 视觉上明确"现在点不动"，而不是让用户点了没反应
            .alpha(if (enabled) 1f else 0.55f),
        shape = RoundedCornerShape(999.dp),
        color = Mono2,
        border = BorderStroke(1.dp, Line),
    ) {
        Row(
            Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            StartMode.entries.forEach { entry ->
                val active = entry == selected
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(enabled = enabled) { onSelect(entry) },
                    shape = RoundedCornerShape(999.dp),
                    color = if (active) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    } else {
                        Color.Transparent
                    },
                    border = if (active) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                    } else {
                        null
                    },
                ) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.primary else TextMuted,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModePill(ui: LauncherViewModel.UiState) {
    val (label, color) = when {
        ui.provisioned == false -> "未部署" to TextMuted
        ui.mode == EnvMode.ROOT && !ui.suAvailable -> "ROOT 模式·无 su" to WarnTone
        ui.mode == EnvMode.ROOT -> "ROOT 模式" to MaterialTheme.colorScheme.primary
        else -> "PROOT 模式" to TextSecondary
    }
    Pill(text = label, color = color, filled = true, leadingDot = true)
}

@Composable
private fun statusSubtitle(ui: LauncherViewModel.UiState): String {
    val st = ui.status ?: return "尚未读取状态"
    return when (st.state) {
        EnvState.RUNNING -> buildList {
            add("运行时长 ${st.uptimeText ?: "—"}")
            // DSH 没在跑是 env-only 的正常形态，不能写成"Web 正常"（那是假的），
            // 也不该留空 —— 一句话说清"环境在跑、DSH 没起"。
            if (st.isEnvOnly && !st.dshRunning) add("仅环境（DSH 未启动）")
            else if (st.dshHealthy == true) add("Web 正常")
        }.joinToString(" · ")

        EnvState.STARTING -> "正在拉起 Node 与 DSH Web，请稍候…"
        EnvState.STOPPING -> "正在停止环境…"
        EnvState.STOPPED -> "环境已停止，点下方按钮启动"
        EnvState.ERROR -> st.lastError ?: "环境出错，请看下方日志"
        EnvState.UNKNOWN -> st.lastError ?: "无法确定环境状态"
    }
}

@Composable
private fun AddressRow(
    label: String,
    value: String?,
    hint: String?,
    copyable: Boolean,
    muted: Boolean = false,
) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.width(56.dp),
        )
        Column(Modifier.weight(1f)) {
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily),
                    color = if (muted) TextMuted else MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            hint?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (muted) TextMuted else TextMuted,
                )
            }
        }
        if (copyable && value != null) {
            TextButton(onClick = { copyToClipboard(context, "DSH 地址", value) }) { Text("复制") }
        }
    }
}

/** 一条"下一步"建议：一句话说清问题 + 一个可点的动作。 */
@Composable
private fun HintCard(hint: Hint, onAction: (HintTarget) -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Mono2,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = WarnTone,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = hint.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = hint.detail,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { onAction(hint.target) }) { Text(hint.action) }
        }
    }
}

@Composable
private fun NoticeBar(text: String, tone: Color) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = tone.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = tone,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
