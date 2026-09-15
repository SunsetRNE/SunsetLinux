package io.github.sunsetrne.sunsetlinux.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import io.github.sunsetrne.sunsetlinux.ui.components.CapsuleReserve
import io.github.sunsetrne.sunsetlinux.core.DshRuntime
import io.github.sunsetrne.sunsetlinux.core.DshPlugins
import io.github.sunsetrne.sunsetlinux.core.LinuxCtl
import io.github.sunsetrne.sunsetlinux.core.Prefs
import io.github.sunsetrne.sunsetlinux.ui.components.DshCard
import io.github.sunsetrne.sunsetlinux.ui.components.LogPanel
import io.github.sunsetrne.sunsetlinux.ui.components.Pill
import io.github.sunsetrne.sunsetlinux.ui.components.SectionLabel
import io.github.sunsetrne.sunsetlinux.ui.theme.Accent
import io.github.sunsetrne.sunsetlinux.ui.theme.Danger
import io.github.sunsetrne.sunsetlinux.ui.theme.LineStrong
import io.github.sunsetrne.sunsetlinux.ui.theme.Mono2
import io.github.sunsetrne.sunsetlinux.ui.theme.MonoFamily
import io.github.sunsetrne.sunsetlinux.ui.theme.TextMuted
import io.github.sunsetrne.sunsetlinux.ui.theme.TextPrimary
import io.github.sunsetrne.sunsetlinux.ui.theme.TextSecondary
import io.github.sunsetrne.sunsetlinux.ui.theme.WarnTone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 插件页（功能 C）：一键安装/卸载第三方 DSH 插件。
 *
 * 命令行语义（实测）：`dsh plugin --profile web <add|remove> <pkg>` 是把参数透传给
 * profile 目录里的 pnpm；所以这里只需要把输入框的内容交给它，并把**原始输出**显示出来。
 */
class PluginsPaneState internal constructor(
    private val context: android.content.Context,
    private val scope: CoroutineScope,
) {
    var target by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var plugins by mutableStateOf<List<DshPlugins.Plugin>>(emptyList())
        private set
    var profilePath by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var errorText by mutableStateOf<String?>(null)
        private set

    private val logLock = Any()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: MutableStateFlow<List<String>> = _log

    fun append(line: String) {
        synchronized(logLock) {
            val cur = _log.value
            _log.value = if (cur.size > 800) cur.takeLast(500) + line else cur + line
        }
    }

    fun onTargetChange(v: String) {
        target = v
    }

    fun dismissNotice() {
        notice = null
    }

    private suspend fun envReady(): Pair<LinuxCtl, String?> {
        val prefs = Prefs(context)
        val choice = DshRuntime.resolveMode(context, prefs)
        val ctl = LinuxCtl(context, choice.mode)
        if (!ctl.exists()) return ctl to "环境尚未部署：先走「重新部署 / 首启引导」。"
        val status = ctl.status()
        if (status.state != io.github.sunsetrne.sunsetlinux.core.EnvState.RUNNING) {
            return ctl to "插件操作需要环境处于运行中（当前：${status.state.label}）。"
        }
        return ctl to null
    }

    fun refresh() {
        if (loading) return
        loading = true
        scope.launch {
            val (ctl, problem) = envReady()
            if (problem != null) {
                loading = false
                errorText = problem
                plugins = emptyList()
                return@launch
            }
            val result = DshPlugins.list(ctl)
            loading = false
            errorText = result.error
            plugins = result.plugins
            profilePath = result.path
        }
    }

    fun install() {
        val err = DshPlugins.validateTarget(target)
        if (err != null) {
            notice = err
            return
        }
        run("安装 ${target.trim()}") { ctl -> DshPlugins.install(ctl, target) { line -> append(line) } }
    }

    fun remove(name: String) {
        run("卸载 $name") { ctl -> DshPlugins.remove(ctl, name) { line -> append(line) } }
    }

    private fun run(label: String, block: suspend (LinuxCtl) -> io.github.sunsetrne.sunsetlinux.core.CtlResult) {
        if (busy) return
        busy = true
        synchronized(logLock) { _log.value = emptyList() }
        scope.launch {
            val (ctl, problem) = envReady()
            if (problem != null) {
                busy = false
                append("✗ $problem")
                notice = problem
                return@launch
            }
            append("# $label")
            val result = try {
                withContext(Dispatchers.IO) { block(ctl) }
            } catch (t: Throwable) {
                io.github.sunsetrne.sunsetlinux.core.CtlResult.fail(t.message ?: "执行失败")
            }
            busy = false
            if (result.ok) {
                notice = "「$label」完成。插件是第三方代码，重启环境后生效。"
                if (result.stderr.isNotBlank()) append(result.stderr)
            } else {
                notice = "「$label」失败：${result.message}（原始输出见下方日志）"
            }
            refresh()
        }
    }
}

@Composable
fun rememberPluginsPaneState(): PluginsPaneState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember { PluginsPaneState(context, scope) }
}

@Composable
fun PluginsPane(
    state: PluginsPaneState,
    modifier: Modifier = Modifier,
) {
    val logs by state.log.collectAsState()

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        state.notice?.let { text ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = Mono2,
                border = androidx.compose.foundation.BorderStroke(1.dp, LineStrong),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { state.dismissNotice() }) { Text("知道了") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 第三方代码警告：必须显眼（单色下用强描边 + 加粗）
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Mono2,
            border = androidx.compose.foundation.BorderStroke(1.dp, LineStrong),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = WarnTone, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = "安装的是第三方代码",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                    )
                    Text(
                        text = "插件由第三方发布，等同于把他们的代码装进你的环境。请只装你信任的来源。" +
                            "安装/卸载后需要**重启环境**才生效。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 安装
        DshCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                SectionLabel("安装插件")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.target,
                    onValueChange = state::onTargetChange,
                    label = { Text("npm 包名 / 本地路径 / URL") },
                    placeholder = { Text("例如 dsh-plugin-foo 或 ./my-plugin") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { state.install() },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.busy) "执行中…" else "安装")
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "将执行：${DshPlugins.installCommand(state.target.ifBlank { "<包名>" })}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                    color = TextMuted,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "包的下载走 npm 源；源慢/装不上时到「设置 → npm 源」换一个镜像。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 已装
        DshCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("已装插件")
                    Spacer(Modifier.width(8.dp))
                    Pill("${state.plugins.size}", color = Accent, filled = true)
                    Spacer(Modifier.weight(1f))
                    if (state.loading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = { state.refresh() }) { Text("刷新") }
                    }
                }
                state.profilePath?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                        color = TextMuted,
                    )
                }
                Spacer(Modifier.height(8.dp))

                when {
                    state.errorText != null -> Text(
                        text = state.errorText!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = WarnTone,
                    )

                    state.plugins.isEmpty() -> Text(
                        text = "还没有装任何插件。（需要环境处于运行中才能读取 profile）",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )

                    else -> state.plugins.forEach { plugin ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = plugin.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily),
                                    color = TextPrimary,
                                )
                                Text(
                                    text = buildString {
                                        append(if (plugin.bundled) "已启用（bundles）" else "已安装（dependencies）")
                                        plugin.version?.let { append(" · ").append(it) }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted,
                                )
                            }
                            TextButton(onClick = { state.remove(plugin.name) }, enabled = !state.busy) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = null,
                                    tint = Danger,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("卸载")
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 原始输出：dsh/pnpm 踩坑时全靠它
        LogPanel(
            lines = logs,
            autoFollow = true,
            onRefresh = { state.refresh() },
            emptyText = "安装/卸载的原始输出（含 pnpm 报错）会显示在这里",
        )

        Spacer(Modifier.height(CapsuleReserve))
    }
}
