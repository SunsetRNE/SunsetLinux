package io.github.sunsetrne.sunsetlinux.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sunsetrne.sunsetlinux.core.EnvMode
import io.github.sunsetrne.sunsetlinux.core.EnvState
import io.github.sunsetrne.sunsetlinux.core.LinuxCtl
import io.github.sunsetrne.sunsetlinux.core.TerminalSession
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
 * ## 三个必须说清的边界（都写进了界面上的提示）
 * 1. **没有 PTY**：只按行刷新、没有作业控制 —— `vim`/`htop`/`top` 这类全屏程序不可用；
 *    连提示符（`>>> ` 这种不换行的）也不会即时出现，要等它换行。
 * 2. **命令回显由本面板补**（`❯ ` 前缀）：管道里的 shell 是非交互的，它自己不回显、
 *    也不打提示符 —— 所以本地回显是**必须的**，不是多此一举。
 * 3. 会话**不随 tab 切换销毁**（状态挂在 AppShell 上），退出 App 才断开。
 */
class TerminalPaneState internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val session = TerminalSession()
    private val lock = Any()

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

    fun append(line: String) {
        synchronized(lock) {
            val cur = _lines.value
            _lines.value = if (cur.size >= 2000) cur.takeLast(1800) + line else cur + line
        }
    }

    fun clear() {
        synchronized(lock) { _lines.value = emptyList() }
    }

    fun dismissNotice() {
        notice = null
    }

    /**
     * 连入环境。**要求环境已经在运行** —— `attach` 进的是一个已存在的 mount namespace，
     * 环境没起来时 linuxctl 只会报错退出，与其让用户看一行报错，不如这里先判一次。
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
                    return@withContext "环境未运行（当前：${st?.state?.label ?: "未知"}）。" +
                        "终端是连进正在运行的环境的 —— 先到「启动」页点「启动环境」。"
                }
                session.start(ctl.terminalCommand())
            }
            starting = false
            if (err == null) {
                running = true
                if (firstLine || _lines.value.isEmpty()) {
                    append("— 已连入环境（${mode.modeLabel}）—")
                    append("· 输入命令后回车发送；命令在环境内执行（root 模式 = chroot 进 rootfs）")
                    append("· 全屏程序（vim / htop / top）与 Ctrl-C 不可用：这里是行缓冲，没有 PTY")
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
        running = false
        append("— 已断开（手动停止）—")
    }

    /** AppShell 销毁时调用：不留孤儿 shell。 */
    fun dispose() {
        session.stop()
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
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

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
            Spacer(Modifier.width(8.dp))
            Pill(text = mode.modeLabel, color = TextMuted)
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

        // ── 输出区
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(16.dp),
            color = Mono0,
            border = BorderStroke(1.dp, Line),
        ) {
            if (lines.isEmpty()) {
                Column(Modifier.padding(16.dp)) {
                    Text("终端还没有输出。", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "命令在环境内执行：root 模式会 chroot 进 rootfs，proot 模式进 proot 的 bash。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "限制：没有 PTY —— vim / htop / top 这类全屏程序不可用；输出按行刷新。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
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

        // ── 提示（环境没起来 / 找不到 linuxctl）
        state.notice?.let { msg ->
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(msg, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!envRunning) {
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
                .padding(vertical = 10.dp),
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
            Spacer(Modifier.width(6.dp))
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

        Spacer(Modifier.height(8.dp))
    }
}
