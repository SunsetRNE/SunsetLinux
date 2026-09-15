package io.dshroid.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import io.dshroid.core.Channel
import io.dshroid.core.ChannelReport
import io.dshroid.core.DshRuntime
import io.dshroid.core.DshStatus
import io.dshroid.core.EnvMode
import io.dshroid.core.LayerTransport
import io.dshroid.core.LayerUpdate
import io.dshroid.core.DshDistTagStore
import io.dshroid.core.LinuxCtl
import io.dshroid.core.NpmDistTags
import io.dshroid.core.Prefs
import io.dshroid.core.ReportState
import io.dshroid.core.TransportSupport
import io.dshroid.core.UpdateApplier
import io.dshroid.core.UpdateChecker
import io.dshroid.core.formatBytes
import io.dshroid.ui.components.DshCard
import io.dshroid.ui.components.InfoRow
import io.dshroid.ui.components.LogPanel
import io.dshroid.ui.components.Pill
import io.dshroid.ui.components.SectionLabel
import io.dshroid.ui.theme.WarnTone
import io.dshroid.ui.theme.Accent
import io.dshroid.ui.theme.Line
import io.dshroid.ui.theme.Mono2
import io.dshroid.ui.theme.MonoFamily
import io.dshroid.ui.theme.Danger
import io.dshroid.ui.theme.TextSecondary
import io.dshroid.ui.theme.StateRunning
import io.dshroid.ui.theme.TextMuted
import io.dshroid.ui.theme.TextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 更新页的状态机（可复用：首页「更新」tab 与独立的 [io.dshroid.UpdateActivity]）。
 *
 * 与界面解耦，所有网络/进程调用都在 IO 协程里；日志用 [MutableStateFlow] 以便从
 * 下载/解压回调线程安全地追加。
 */
class UpdatePaneState internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var checking by mutableStateOf(false)
        private set
    var applying by mutableStateOf(false)
        private set
    var reports by mutableStateOf<List<ChannelReport>>(emptyList())
        private set
    var updates by mutableStateOf<List<LayerUpdate>>(emptyList())
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf<DshStatus?>(null)
        private set
    var mode by mutableStateOf(EnvMode.ROOT)
        private set
    var checked by mutableStateOf(false)
        private set
    var enabledChannels by mutableStateOf(0)
        private set

    /** 本机 zstd 可用性（null = 可用）；选择产物与界面提示都用它。 */
    val zstdReason: String? = TransportSupport.zstdUnavailableReason()

    // ── 功能 D：DSH 通道（dist-tag）──────────────────────────────
    val prefs = Prefs(context)

    /** 用户选择的 tag（空白表示跟随频道清单声明的 dsh_npm.dist_tag）。 */
    var distTag by mutableStateOf(prefs.dshDistTag ?: "")
        private set

    /** 该 tag 在 npm 上解析出的版本号（null = 未解析/解析失败）。 */
    var resolvedVersion by mutableStateOf<String?>(null)
        private set

    var distTagError by mutableStateOf<String?>(null)
        private set

    var resolving by mutableStateOf(false)
        private set

    var persistedTag by mutableStateOf<String?>(null)
        private set

    fun onDistTagChange(value: String) {
        distTag = value
    }

    /** 解析选中通道 → 版本号，并把选择落到环境配置（etc/config.json 的 dsh_dist_tag）。 */
    fun applyDistTag() {
        val tag = distTag.trim()
        val err = if (tag.isEmpty()) null else NpmDistTags.validateTag(tag)
        if (err != null) {
            distTagError = err
            return
        }
        resolving = true
        scope.launch {
            val (tags, fetchError) = NpmDistTags.fetch()
            val version = if (tag.isEmpty()) null else NpmDistTags.resolve(tag, tags)
            resolvedVersion = version
            distTagError = when {
                fetchError != null -> "查询 npm registry 失败：$fetchError"
                tag.isNotEmpty() && version == null ->
                    "无法解析「$tag」：npm 上没有这个 dist-tag 也不是版本号（现有：" +
                        tags.keys.sorted().joinToString(", ") + "）"
                else -> null
            }
            prefs.dshDistTag = tag.ifEmpty { null }
            persistedTag = withContext(Dispatchers.IO) {
                runCatching {
                    val mode = DshRuntime.resolveMode(context, prefs).mode
                    val r = DshDistTagStore.persist(context, mode, tag.ifEmpty { null })
                    if (r.ok) tag.ifEmpty { null } else null
                }.getOrNull()
            }
            resolving = false
        }
    }

    private val logLock = Any()
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: MutableStateFlow<List<String>> = _logs

    fun append(line: String) {
        synchronized(logLock) {
            val cur = _logs.value
            _logs.value = if (cur.size > 1500) cur.takeLast(1000) + line else cur + line
        }
    }

    fun dismissNotice() {
        notice = null
    }

    fun check() {
        if (checking) return
        checking = true
        synchronized(logLock) { _logs.value = emptyList() }
        scope.launch {
            val prefs = Prefs(context)
            enabledChannels = prefs.channels.count { it.enabled }
            val channels: List<Channel> = prefs.channels.filter { it.enabled }
            val (m, st) = withContext(Dispatchers.IO) {
                val choice = DshRuntime.resolveMode(context, prefs)
                choice.mode to LinuxCtl(context, choice.mode).status()
            }
            mode = m
            status = st
            reports = emptyList()
            updates = emptyList()

            if (channels.isEmpty()) {
                checking = false
                checked = true
                notice = "没有启用中的频道。请在侧边栏「频道管理」里添加「URL + 公钥」后再检查更新。"
                return@launch
            }

            val result = withContext(Dispatchers.IO) { UpdateChecker.check(channels, st) }
            val merged = UpdateChecker.merge(result)
            reports = result
            updates = merged.filter { it.key != prefs.ignoredUpdate }
            checking = false
            checked = true
            notice = when {
                merged.isEmpty() -> "已是最新：所有层与频道清单一致。"
                updates.isEmpty() -> "有可用更新，但你已选择忽略。"
                else -> "发现 ${updates.size} 个层可更新。"
            }
        }
    }

    fun apply(targets: List<LayerUpdate>) {
        if (applying || targets.isEmpty()) return
        applying = true
        synchronized(logLock) { _logs.value = emptyList() }
        scope.launch {
            var ok = true
            for (update in targets) {
                append("=== ${update.id}: ${update.fromVersion ?: "未安装"} → ${update.toVersion ?: "?"} ===")
                var lastStage = ""
                var lastPercent = -1
                val outcome = withContext(Dispatchers.IO) {
                    UpdateApplier.apply(context, mode, update) { stage, done, total ->
                        // 进度回调很密集（每 128 KB 一次），按阶段/百分比节流后再落日志
                        val percent = if (total > 0) ((done * 100) / total).toInt() else -1
                        if (stage != lastStage || percent != lastPercent) {
                            lastStage = stage
                            lastPercent = percent
                            append(
                                if (total > 0) "$stage  ${formatBytes(done)} / ${formatBytes(total)}"
                                else stage
                            )
                        }
                    }
                }
                append(outcome.log)
                if (!outcome.ok) {
                    ok = false
                    notice = "「${update.id}」层更新失败（卡在：${outcome.failedStage?.label ?: "未知阶段"}），已停止后续更新。"
                    break
                }
                updates = updates.filterNot { it.id == update.id }
            }
            applying = false
            if (ok) notice = "更新完成，环境已由 linuxctl update 重启。"
            check()
        }
    }

    fun ignoreCurrent() {
        val first = updates.firstOrNull() ?: return
        Prefs(context).ignoredUpdate = first.key
        updates = emptyList()
        notice = "已忽略当前这批更新。"
    }
}

@Composable
fun rememberUpdatePaneState(): UpdatePaneState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember { UpdatePaneState(context, scope) }
}

/**
 * 更新页内容（首页 tab 与独立 Activity 共用）。
 *
 * 契约要点（§5.2/§5.4）：验签失败的频道明确拒绝并展示原因；只下载变化的层；
 * 下载 → 校验压缩产物 → **解压** → 校验裸镜像 → `linuxctl update … --version …`。
 */
@Composable
fun UpdatePane(
    state: UpdatePaneState,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit,
    showHeader: Boolean = true,
) {
    val logs by state.logs.collectAsState()

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (showHeader) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("更新", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "分层更新：只下载变化的那一层",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
                IconButton(onClick = { state.check() }, enabled = !state.checking && !state.applying) {
                    if (state.checking) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "检查更新")
                    }
                }
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            DshCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    SectionLabel("当前环境")
                    Spacer(Modifier.height(10.dp))
                    InfoRow("模式", state.mode.modeLabel)
                    InfoRow("DSH 版本", state.status?.dshVersion ?: "—", mono = true)
                    state.status?.layers?.forEach { layer ->
                        InfoRow(
                            label = layer.id,
                            value = "${layer.version ?: "?"}  ${formatBytes(layer.size)}" +
                                if (layer.mounted == false) "  (未挂载)" else "",
                            mono = true,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 功能 D：通道（dist-tag）选择
            DshCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    SectionLabel("DSH 通道（npm dist-tag）")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "官方内测走 npm 的 dist-tag。选定后会写进环境配置，" +
                            "并在下面解析出该通道当前对应的版本号。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                    Spacer(Modifier.height(10.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NpmDistTags.presets.forEach { tag ->
                            val active = state.distTag.trim() == tag
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = if (active) Accent.copy(alpha = 0.18f) else Mono2,
                                border = BorderStroke(1.dp, if (active) Accent.copy(alpha = 0.6f) else Line),
                                onClick = { state.onDistTagChange(if (active) "" else tag) },
                            ) {
                                Text(
                                    text = tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (active) Accent else TextSecondary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.distTag,
                        onValueChange = state::onDistTagChange,
                        label = { Text("或自定义 tag / 版本号（留空＝跟随频道清单）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { state.applyDistTag() }, enabled = !state.resolving) {
                            if (state.resolving) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("解析并保存")
                        }
                        state.resolvedVersion?.let { v ->
                            Pill("→ $v", color = Accent, filled = true)
                        }
                    }

                    val declared = state.reports.mapNotNull { it.dshDistTag }.distinct()
                    if (declared.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "频道清单声明的通道：${declared.joinToString(", ")}" +
                                if (state.distTag.isNotBlank() && state.distTag.trim() !in declared) {
                                    "（与你选的「${state.distTag.trim()}」不同 —— 以你的选择为准，但层的实际版本仍由频道发布内容决定）"
                                } else {
                                    ""
                                },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.distTag.isNotBlank() && state.distTag.trim() !in declared) WarnTone else TextMuted,
                        )
                    }
                    state.persistedTag?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "已写入环境配置 etc/config.json 的 dsh_dist_tag = $it",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                            color = TextMuted,
                        )
                    }
                    state.distTagError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = WarnTone)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "限制：运行时目前还没有消费这个键（只有发布工具会写 dsh_npm.dist_tag）。" +
                            "这里的解析结果是直接从 npm registry 查的，可用来确认「选哪个通道会拿到哪个版本」。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            DshCard(Modifier.fillMaxWidth(), highlighted = state.updates.isNotEmpty()) {
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("可用更新")
                        Spacer(Modifier.width(8.dp))
                        if (state.updates.isNotEmpty()) {
                            Pill("${state.updates.size} 层", color = Accent, filled = true)
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    when {
                        state.enabledChannels == 0 -> {
                            Text(
                                text = "没有启用中的频道。频道 = 一个清单 URL + 一个 ed25519 公钥，第三方可自签自建。",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                                Text("去添加频道")
                            }
                        }

                        state.updates.isEmpty() -> Text(
                            text = if (state.checked) "无需更新的层。" else "点右上角刷新开始检查。",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )

                        else -> {
                            if (state.zstdReason != null) {
                                NoticeLine("${state.zstdReason}；频道若提供 gzip 回退产物会自动使用。", WarnTone)
                                Spacer(Modifier.height(8.dp))
                            }
                            state.updates.forEach { update ->
                                UpdateRow(
                                    update = update,
                                    enabled = !state.applying,
                                    zstdUsable = state.zstdReason == null,
                                    onApply = { state.apply(listOf(update)) },
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { state.apply(state.updates) },
                                    enabled = !state.applying &&
                                        state.updates.any { it.choose(state.zstdReason == null) != null },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    if (state.applying) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(if (state.applying) "更新中…" else "全部更新")
                                }
                                TextButton(onClick = { state.ignoreCurrent() }, enabled = !state.applying) { Text("忽略") }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (state.reports.isNotEmpty()) {
                DshCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        SectionLabel("频道检查结果")
                        Spacer(Modifier.height(10.dp))
                        state.reports.forEach { report ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(report.channel.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = report.channel.shortUrl,
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                                        color = TextMuted,
                                    )
                                    report.reason?.let {
                                        Spacer(Modifier.height(3.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Filled.Warning,
                                                contentDescription = null,
                                                tint = if (report.state == ReportState.REJECTED) Danger else WarnTone,
                                                modifier = Modifier.size(13.dp),
                                            )
                                            Spacer(Modifier.width(5.dp))
                                            Text(
                                                text = it,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (report.state == ReportState.REJECTED) Danger else WarnTone,
                                            )
                                        }
                                    }
                                    report.dshDistTag?.let {
                                        Spacer(Modifier.height(3.dp))
                                        Pill("dist-tag: $it", color = Accent)
                                    }
                                }
                                Pill(
                                    text = when (report.state) {
                                        ReportState.OK -> "通过"
                                        ReportState.REJECTED -> "已拒绝"
                                        ReportState.ERROR -> "错误"
                                    },
                                    color = when (report.state) {
                                        ReportState.OK -> StateRunning
                                        ReportState.REJECTED -> Danger
                                        ReportState.ERROR -> WarnTone
                                    },
                                    filled = true,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // 更新过程的实时日志（常显固定高度，便于排障）
            LogPanel(
                lines = logs,
                autoFollow = true,
                emptyText = "更新日志会显示在这里（下载 / 校验 / 解压 / 应用各阶段）",
                errorText = null,
            )

            Spacer(Modifier.height(96.dp)) // 给悬浮胶囊底栏留位
        }

        state.notice?.let { text ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { state.dismissNotice() }) { Text("知道了") }
                }
            }
        }
    }
}

@Composable
private fun UpdateRow(
    update: LayerUpdate,
    enabled: Boolean,
    zstdUsable: Boolean,
    onApply: () -> Unit,
) {
    val choice = update.choose(zstdUsable)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Mono2,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = update.id,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(6.dp))
                    if (choice != null) {
                        Pill(
                            text = choice.artifact.transport.wire,
                            color = when (choice.artifact.transport) {
                                LayerTransport.ZSTD -> Accent
                                LayerTransport.GZIP -> TextSecondary
                                LayerTransport.RAW -> TextMuted
                            },
                        )
                    }
                }
                Text(
                    text = "${update.fromVersion ?: "未安装"}  →  ${update.toVersion ?: "?"}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                    color = TextSecondary,
                )
                Text(
                    text = buildString {
                        append("传输产物 ")
                        append(choice?.artifact?.size?.let { formatBytes(it) } ?: "大小未知")
                        append(" · 镜像 ")
                        append(update.sizeRaw?.let { formatBytes(it) } ?: "大小未知")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
                if (choice == null) {
                    Text(
                        text = "频道既没提供 url 也没提供 url_gz，无法更新",
                        style = MaterialTheme.typography.labelSmall,
                        color = Danger,
                    )
                } else if (choice.note != null) {
                    Text(
                        text = choice.note,
                        style = MaterialTheme.typography.labelSmall,
                        color = WarnTone,
                    )
                }
            }
            TextButton(onClick = onApply, enabled = enabled && choice != null) { Text("下载并应用") }
        }
    }
}

@Composable
internal fun NoticeLine(text: String, tone: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = tone,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = tone)
    }
}
