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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sunsetrne.sunsetlinux.core.Channel
import io.github.sunsetrne.sunsetlinux.core.DshRuntime
import io.github.sunsetrne.sunsetlinux.core.EnvFiles
import io.github.sunsetrne.sunsetlinux.core.Prefs
import io.github.sunsetrne.sunsetlinux.ui.components.CapsuleReserve
import io.github.sunsetrne.sunsetlinux.ui.components.DshCard
import io.github.sunsetrne.sunsetlinux.ui.components.Pill
import io.github.sunsetrne.sunsetlinux.ui.components.SectionLabel
import io.github.sunsetrne.sunsetlinux.ui.theme.Danger
import io.github.sunsetrne.sunsetlinux.ui.theme.MonoFamily
import io.github.sunsetrne.sunsetlinux.ui.theme.StateRunning
import io.github.sunsetrne.sunsetlinux.ui.theme.TextMuted
import io.github.sunsetrne.sunsetlinux.ui.theme.TextSecondary
import io.github.sunsetrne.sunsetlinux.ui.theme.WarnTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 更新频道管理（§5.1）。
 *
 * ## 为什么从「设置」页里拿出来
 *
 * 侧边栏本来就有一条「频道管理」入口，但原先是"打开设置 Activity 再滚到中段"——
 * 入口与页面不是一对一，而且频道那一大块（列表 + 增删改 + 同步）把设置页撑长了。
 * 现在它是侧边栏的独立页，设置页只留通用项。
 *
 * 判定仍然是**同一份**：内置官方频道（[Channel.OFFICIAL]）删不掉，只能停用 ——
 * 它的 URL/公钥是代码里的信任根，这里再兜一层，防止以后有人在别处直接调回调。
 */
@Composable
fun ChannelsPane(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(context) }

    val channels = remember { mutableStateListOf<Channel>().also { it.addAll(prefs.channels) } }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Channel?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }

    fun persist() {
        prefs.channels = channels.toList()
    }

    fun replace(ch: Channel) {
        val idx = channels.indexOfFirst { it.id == ch.id }
        if (idx >= 0) channels[idx] = ch else channels.add(ch)
        persist()
    }

    fun syncToEnv() {
        if (syncing) return
        syncing = true
        scope.launch {
            val mode = withContext(Dispatchers.IO) { DshRuntime.resolveMode(context, prefs).mode }
            val json = Channel.toChannelsFile(channels.toList())
            val result = EnvFiles.writeText(context, mode, "etc/channels.json", json)
            syncing = false
            notice = if (result.ok) {
                "已同步 ${channels.size} 个频道到 ${EnvFiles.home(context, mode)}/etc/channels.json"
            } else {
                "同步失败：${result.message}"
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        notice?.let { text ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { notice = null }) { Text("知道了") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        DshCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("更新频道")
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { creating = true }) {
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
                            onToggle = { enabled -> replace(ch.copy(enabled = enabled)) },
                            onEdit = { editing = ch },
                            onDelete = {
                                if (Channel.isBuiltin(ch.id)) {
                                    notice = "「${ch.name}」是内置频道，不能删除（可停用）。"
                                } else {
                                    channels.removeAll { it.id == ch.id }
                                    persist()
                                    notice = "已删除频道「${ch.name}」"
                                }
                            },
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { syncToEnv() },
                    enabled = channels.isNotEmpty() && !syncing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("同步到环境 (etc/channels.json)")
                }
            }
        }

        Spacer(Modifier.height(CapsuleReserve))
    }

    if (creating || editing != null) {
        ChannelDialog(
            initial = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { ch ->
                if (editing == null) {
                    channels.add(ch)
                    notice = "已添加频道「${ch.name}」"
                } else {
                    replace(ch)
                    notice = "已保存频道「${ch.name}」"
                }
                creating = false
                editing = null
                persist()
            },
        )
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                    Text("启用该频道", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    text = "留空公钥 = 不验签，等于放弃来源校验（契约要求验签失败必须拒绝，留空属于用户显式豁免）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = WarnTone,
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                }
            }
        },
    )
}
