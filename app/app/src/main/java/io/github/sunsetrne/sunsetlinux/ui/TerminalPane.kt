package io.github.sunsetrne.sunsetlinux.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sunsetrne.sunsetlinux.core.Edition
import io.github.sunsetrne.sunsetlinux.core.EnvMode
import io.github.sunsetrne.sunsetlinux.core.EnvState
import io.github.sunsetrne.sunsetlinux.core.LinuxCtl
import io.github.sunsetrne.sunsetlinux.core.PtyNative
import io.github.sunsetrne.sunsetlinux.core.PtySession
import io.github.sunsetrne.sunsetlinux.core.TerminalEmulator
import io.github.sunsetrne.sunsetlinux.core.TerminalSession
import io.github.sunsetrne.sunsetlinux.core.TerminalStatusUi
import io.github.sunsetrne.sunsetlinux.ui.components.CapsuleReserve
import io.github.sunsetrne.sunsetlinux.ui.components.Pill
import io.github.sunsetrne.sunsetlinux.ui.theme.Danger
import io.github.sunsetrne.sunsetlinux.ui.theme.Line
import io.github.sunsetrne.sunsetlinux.ui.theme.Mono0
import io.github.sunsetrne.sunsetlinux.ui.theme.MonoFamily
import io.github.sunsetrne.sunsetlinux.ui.theme.TextMuted
import io.github.sunsetrne.sunsetlinux.ui.theme.TextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App 内置终端（「终端」tab）。
 *
 * ## 它是什么
 * 一个连进**正在运行的环境**的常驻会话：`linuxctl attach` → root 模式 `nsenter … chroot … bash`，
 * proot 模式 `start.sh --inner -- bash -l`。命令在环境里跑，和你在 root 终端里手敲是同一条路。
 *
 * ## 两套引擎（按可用性自动选）
 *   · **PTY**（首选，`PtySession` + `TerminalEmulator`）：真伪终端 —— Ctrl-C/Ctrl-D、
 *     Tab、方向键都真的生效，`vim`/`htop` 这类全屏程序能跑，窗口大小按控件尺寸发 TIOCSWINSZ。
 *     回显**由环境里的 shell 自己产生**（PTY 的行规程会回显），所以面板**不再**本地回显。
 *   · **行缓冲**（降级，`TerminalSession`）：原生库不可用（自编译 APK 没带 .so、ABI 不匹配）
 *     时用老的管道实现 —— 终端不能因为缺一个 .so 就完全不可用。这时仍需要本地回显。
 *
 * 会话**不随 tab 切换销毁**（状态挂在 AppShell 上），退出 App 才断开。
 */
class TerminalPaneState internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val session = TerminalSession()          // 降级引擎（行缓冲）
    private val pty = PtySession()                    // 首选引擎（原生 PTY）
    private val emu = TerminalEmulator()              // PTY 输出 → 屏幕
    private val lock = Any()

    /** 是否跑在原生 PTY 上（false = 降级到行缓冲）。 */
    var ptyActive by mutableStateOf(false)
        private set

    /** 原生库不可用的原因（会显示给用户，说明为什么能力受限）。 */
    val ptyUnavailable: String? get() = PtyNative.loadError

    /** 当前窗口行列（由界面按控件尺寸算出来，用于 TIOCSWINSZ 与 emulator.resize）。 */
    private var rows = 24
    private var cols = 80
    private var lastRender = 0L
    private var renderScheduled = false

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    /** 会话是否活着。 */
    var running by mutableStateOf(false)
        private set

    /** 正在（连环境 + 起进程）。 */
    var starting by mutableStateOf(false)
        private set

    /** 需要用户看一眼的原因（环境没起来 / 找不到 linuxctl / 写入失败）。 */
    var notice by mutableStateOf<String?>(null)
        private set

    /** 最近一次会话的退出码（仅展示用）。 */
    var exitCode by mutableStateOf<Int?>(null)
        private set

    private var askedStop = false
    private var firstLine = true

    init {
        session.onLine = { line -> append(line) }
        pty.onOutput = { buf, len ->
            emu.feed(buf, 0, len)
            // 高频输出节流：最多 ~60ms 刷一次屏（`cat 大文件` 时否则会疯狂重组）
            val now = System.currentTimeMillis()
            if (now - lastRender >= 60) {
                lastRender = now
                publishScreen()
            } else if (!renderScheduled) {
                renderScheduled = true
                scope.launch {
                    kotlinx.coroutines.delay(60)
                    renderScheduled = false
                    lastRender = System.currentTimeMillis()
                    publishScreen()
                }
            }
        }
        pty.onExit = { code ->
            running = false
            val byUs = askedStop
            askedStop = false
            publishScreen()
            if (!byUs) {
                exitCode = code
                append("— 会话已结束（退出码 $code）—")
            }
        }
        session.onExit = { code ->
            running = false
            val byUs = askedStop
            askedStop = false
            if (!byUs) {
                exitCode = code
                append("— 会话已结束（退出码 $code）—")
            }
        }
    }

    /** 把 emulator 的屏幕搬进 [lines]（PTY 模式专用）。 */
    private fun publishScreen() {
        val snap = emu.snapshot()
        val rendered = snap.lines.dropLastWhile { it.isEmpty() }
        synchronized(lock) { _lines.value = rendered }
    }

    /**
     * 控件尺寸变化：改行列 → 既告诉内核（TIOCSWINSZ，vim/htop 会跟着重排），
     * 也让模拟器把画布换尺寸。降级模式只更新 emulator（不生效，仅保持尺寸一致）。
     */
    fun resize(newRows: Int, newCols: Int) {
        val r = newRows.coerceIn(4, 300)
        val c = newCols.coerceIn(20, 500)
        if (r == rows && c == cols) return
        rows = r
        cols = c
        emu.resize(r, c)
        if (ptyActive) pty.resize(r, c)
        if (ptyActive) publishScreen()
    }

    /**
     * 发送**任意字节**（快捷键用）：Ctrl-C = 0x03、Ctrl-D = 0x04、方向键 = ESC[A…。
     * 只有 PTY 模式有意义 —— 行缓冲模式下这些字节不会变成信号（这正是 PTY 的价值）。
     */
    fun sendBytes(bytes: ByteArray) {
        if (!running) { notice = "会话未运行：先点「连接」。"; return }
        if (!ptyActive) {
            notice = "当前是行缓冲降级模式：Ctrl-C / 方向键不可用（原生 PTY 未加载）。"
            return
        }
        pty.write(bytes)
    }

    fun append(line: String) {
        synchronized(lock) {
            val cur = _lines.value
            _lines.value = if (cur.size >= 2000) cur.takeLast(1800) + line else cur + line
        }
    }

    fun clear() {
        emu.reset()
        synchronized(lock) { _lines.value = emptyList() }
    }

    fun dismissNotice() {
        notice = null
    }

    /**
     * 连入环境。**要求环境已经在运行** —— `attach` 进的是一个已存在的 mount namespace，
     * 环境没起来时 linuxctl 只会报错退出，与其让用户看一行报错，不如这里先判一次。
     *
     * ⚠️ 这里**只看环境、不看 DSH**：`attach` 走的是 nsenter + chroot，与 DSH Web 无关。
     * 所以「仅启动环境」之后（`env_mode=env-only`、DSH 没跑）终端必须照常可用 ——
     * 这正是不再要求"先一键启动"的原因。
     */
    fun connect(mode: EnvMode) {
        if (running || starting) return
        starting = true
        notice = null
        exitCode = null
        askedStop = false
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                val ctl = LinuxCtl(context, mode)
                if (!ctl.exists()) {
                    return@withContext "找不到 linuxctl —— 环境还没部署。先到「部署」跑一次部署向导。"
                }
                val st = try {
                    ctl.status()
                } catch (e: Exception) {
                    null
                }
                if (st == null || st.state != EnvState.RUNNING) {
                    // 点名「仅启动环境」而不是笼统的"启动环境"：只想用终端的人不该被迫
                    // 把 DSH 一起拉起来（那正是这次拆开启动要解决的问题）。
                    val next = if (Edition.showsSplitStartUi) {
                        "先到「启动」页点「仅启动环境」（不必启动 DSH）。"
                    } else {
                        "先到「启动」页点「启动环境」。"
                    }
                    return@withContext "环境未运行（当前：${st?.state?.label ?: "未知"}）。" +
                        "终端是连进正在运行的环境的 —— $next"
                }
                if (PtyNative.available) {
                    ptyActive = true
                    pty.start(ctl.ptySpec(rows, cols), rows, cols)
                } else {
                    ptyActive = false
                    session.start(ctl.terminalCommand())
                }
            }
            starting = false
            if (err == null) {
                running = true
                if (firstLine || _lines.value.isEmpty()) {
                    if (ptyActive) {
                        // PTY 模式下 shell 自己会打提示符/回显，这里只留一行状态说明
                        append("— 已连入环境（${mode.modeLabel}，原生 PTY）—")
                    } else {
                        append("— 已连入环境（${mode.modeLabel}，行缓冲降级）—")
                        append("· 原生 PTY 不可用：${ptyUnavailable ?: "未知原因"}")
                        append("· 输入命令后回车发送；命令在环境内执行（root 模式 = chroot 进 rootfs）")
                        append("· 全屏程序（vim / htop / top）与 Ctrl-C 不可用：这里是行缓冲，没有 PTY")
                    }
                    firstLine = false
                } else {
                    append("— 已重新连入环境（${mode.modeLabel}）—")
                }
            } else {
                notice = err
                append("× $err")
            }
        }
    }

    /** 发送一整行：本地补回显（管道里的 shell 不回显），再写进会话。 */
    fun send(text: String) {
        val cmd = text.trimEnd('\n')
        notice = null
        if (!running) {
            notice = "会话未运行：先点「连接」。"
            return
        }
        if (ptyActive) {
            // PTY：把整行 + CR 交给行规程。回显由环境里的 shell 产生，本地**不能**再补一次。
            if (!pty.write(("$cmd\r").toByteArray(Charsets.UTF_8))) {
                running = false
                notice = "写入失败：会话可能已退出。点「重开」再试。"
            }
            return
        }
        append("❯ $cmd")
        if (!session.sendLine(cmd)) {
            running = false
            notice = "写入失败：会话可能已退出。点「重开」再试。"
        }
    }

    fun stop() {
        if (!running && !starting) return
        askedStop = true
        session.stop()
        pty.stop()
        running = false
        append("— 已断开（手动停止）—")
    }

    /** AppShell 销毁时调用：不留孤儿 shell。 */
    fun dispose() {
        session.stop()
        pty.stop()
    }
}

@Composable
fun rememberTerminalPaneState(): TerminalPaneState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember { TerminalPaneState(context, scope) }
}

@Composable
fun TerminalPane(
    state: TerminalPaneState,
    mode: EnvMode,
    envRunning: Boolean,
    onGoStart: () -> Unit,
    /** 「一键跳到『仅启动环境』」：非 Root 版没有这条路，传 null 即可。 */
    onStartEnvOnly: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val lines by state.lines.collectAsState()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    fun submit() {
        val text = input
        input = ""
        state.send(text)
    }

    // 新行到达就滚到底（流式输出时这是"跟手"的关键）
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // ★ 底部给悬浮胶囊底栏留位（真机批注："悬浮导航栏遮挡了终端"）。
            //   快捷键行与输入框原本正好落在胶囊下面，会被整块盖住。CapsuleReserve 是一个
            //   常量（见 components/Common.kt）：这个数字散落写过好几遍、漏改一处就漏一个面板。
            //   只加在终端页这一页 —— 其它页各自的滚动容器里已经有自己的留白，不动它们。
            .padding(start = 16.dp, end = 16.dp, bottom = CapsuleReserve),
    ) {
        // 竖直留白整体收掉约 1/4（用户："太散了"）。只动 Spacer/padding，**不设固定高度**：
        // 硬挤压会让大屏上的终端变小气，而这里收的是容器之间的空隙，输出区该多大还是多大。
        Spacer(Modifier.height(4.dp))

        // ── 状态条 + 工具
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(
                text = when {
                    state.running -> "已连接"
                    state.starting -> "连接中…"
                    else -> "未连接"
                },
                color = if (state.running) MaterialTheme.colorScheme.primary else TextMuted,
                filled = state.running,
                leadingDot = true,
            )
            // 真实身份：**只在会话真的连上之后才显示**。
            //
            // 旧版这里是 `Pill(mode.modeLabel)`（一个恒定的 "ROOT"），它既没说明连接态，
            // 也没说清环境里到底是什么身份 —— 用户看到 "ROOT" 就以为里面真有 root 权限。
            // 现在按 mode 给事实：Root 版 `uid 0（root）`；免 root 版是 proot 伪造的 root，
            // 没有真实 capabilities。没连上时我们对环境内一无所知 → 不显示（不编造）。
            TerminalStatusUi.identityLabel(state.running, mode)?.let { identity ->
                Spacer(Modifier.width(8.dp))
                Text(
                    text = identity,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                    color = TextSecondary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { state.clear() }) { Text("清屏") }
            TextButton(
                onClick = { state.connect(mode) },
                enabled = !state.starting,
            ) { Text(if (state.running) "重开" else "连接") }
            TextButton(onClick = { state.stop() }, enabled = state.running) {
                Text("停止", color = Danger)
            }
        }

        // ── 环境没起来：先把话说清楚，并给一条一键到位的路
        //
        // 为什么是常显横幅而不是等用户点了「连接」再报错：终端"看起来能用但连不上"
        // 最容易让人以为是终端坏了。这里在输入框还在的时候就说清前提，
        // 并且直接给出「仅启动环境」—— 起好环境就能用，不必启动 DSH。
        if (!envRunning) {
            Spacer(Modifier.height(4.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        "环境未运行 —— 终端要连进正在运行的环境（nsenter + chroot），现在没有可连的东西。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (onStartEnvOnly != null) {
                            "点「仅启动环境」把环境起来即可用终端；DSH 与终端无关，不必一起启动。"
                        } else {
                            "到「启动」页把环境起来之后回到这里点「连接」。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        onStartEnvOnly?.let { startEnvOnly ->
                            TextButton(onClick = startEnvOnly) { Text("仅启动环境") }
                        }
                        TextButton(onClick = onGoStart) { Text("去启动页") }
                    }
                }
            }
        }

        // ── 输出区
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(16.dp),
            color = Mono0,
            border = BorderStroke(1.dp, Line),
        ) {
            // 窗口行列按**控件真实尺寸**算：这就是 TIOCSWINSZ 的来源，
            // 让 vim/htop 的分辨率跟着屏幕走（旋转/分屏都会重算）。
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val dens = LocalDensity.current
                val charW = with(dens) { (12.sp * 0.6f).toPx() }
                val lineH = with(dens) { 16.sp.toPx() }
                val wPx = with(dens) { maxWidth.toPx() } - with(dens) { 24.dp.toPx() }
                val hPx = with(dens) { maxHeight.toPx() } - with(dens) { 20.dp.toPx() }
                val cols = (wPx / charW).toInt().coerceAtLeast(20)
                val rows = (hPx / lineH).toInt().coerceAtLeast(4)
                LaunchedEffect(cols, rows) { state.resize(rows, cols) }
                if (lines.isEmpty()) {
                // 空态：只留最必要的两三行，且**每一行都跟着真实状态自动变**（纯函数见
                // core/TerminalStatusUi —— 旧版那句"root 模式会 chroot…proot 模式…"同时讲
                // 两个 edition 的事，用户装的是其中一个，读到的另一半是噪音）。
                Column(Modifier.padding(12.dp)) {
                    Text("终端还没有输出。", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Spacer(Modifier.height(3.dp))
                    // 权限行：这一版**以什么身份、经哪条路**执行命令
                    Text(
                        TerminalStatusUi.privilegeLine(mode),
                        style = MaterialTheme.typography.labelSmall.copy(lineHeight = 15.sp),
                        color = TextMuted,
                    )
                    Spacer(Modifier.height(2.dp))
                    // 引擎行：就绪时压成一行；降级时完整说明（全屏程序/Ctrl-C 不可用是
                    // 用户必须知道的能力差异，不能为了紧凑把它压没）
                    Text(
                        TerminalStatusUi.engineLine(PtyNative.available, PtyNative.loadError),
                        style = MaterialTheme.typography.labelSmall.copy(lineHeight = 15.sp),
                        color = TextMuted,
                    )
                    // 未连接时才补这句**可操作**的提示；连上之后它自己消失
                    if (!state.running) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            TerminalStatusUi.NOT_CONNECTED_HINT,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    items(lines) { line ->
                        Text(
                            text = line,
                            fontFamily = MonoFamily,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = when {
                                line.startsWith("❯ ") -> MaterialTheme.colorScheme.primary
                                line.startsWith("—") -> TextMuted
                                line.startsWith("×") -> Danger
                                else -> TextSecondary
                            },
                            fontWeight = if (line.startsWith("×")) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            }
        }

        // ── 快捷键（PTY 才有意义：这些字节靠行规程变成信号/光标移动）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val keys = listOf(
                "Ctrl-C" to byteArrayOf(0x03),
                "Ctrl-D" to byteArrayOf(0x04),
                "Ctrl-Z" to byteArrayOf(0x1a),
                "Tab" to byteArrayOf(0x09),
                "Esc" to byteArrayOf(0x1b),
                "↑" to "\u001b[A".toByteArray(),
                "↓" to "\u001b[B".toByteArray(),
                "←" to "\u001b[D".toByteArray(),
                "→" to "\u001b[C".toByteArray(),
                "Ctrl-L" to byteArrayOf(0x0c),
            )
            // 横向滚动免得小屏放不下（Row + horizontalScroll）
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                keys.forEach { (label, bytes) ->
                    TextButton(
                        onClick = { state.sendBytes(bytes) },
                        enabled = state.running && state.ptyActive,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }

        // ── 提示（环境没起来 / 找不到 linuxctl）
        state.notice?.let { msg ->
            Spacer(Modifier.height(4.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(msg, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!envRunning) {
                            // 终端的环境前提：能一键起环境就别让用户先离开这个 tab
                            onStartEnvOnly?.let { startEnvOnly ->
                                TextButton(onClick = startEnvOnly) { Text("仅启动环境") }
                            }
                            TextButton(onClick = onGoStart) { Text("去启动环境") }
                        }
                        TextButton(onClick = { state.connect(mode) }) { Text("重试") }
                        TextButton(onClick = { state.dismissNotice() }) { Text("知道了") }
                    }
                }
            }
        }

        // ── 输入行（IME inset 由 AppShell 统一消费，这里不再重复申请）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = MonoFamily,
                    fontSize = 13.sp,
                ),
                placeholder = {
                    Text(
                        if (state.running) "输入命令，回车发送" else "先点上面的「连接」",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = { submit() },
                enabled = state.running && input.isNotEmpty(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    tint = if (state.running && input.isNotEmpty()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        TextMuted
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // 底部只剩 2dp：真正的避让由最外层 Column 的 CapsuleReserve 负责
        Spacer(Modifier.height(2.dp))
    }
}
