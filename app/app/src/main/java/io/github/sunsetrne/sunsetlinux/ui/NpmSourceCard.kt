package io.github.sunsetrne.sunsetlinux.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import io.github.sunsetrne.sunsetlinux.core.DshRuntime
import io.github.sunsetrne.sunsetlinux.core.EnvMode
import io.github.sunsetrne.sunsetlinux.core.EnvState
import io.github.sunsetrne.sunsetlinux.core.LinuxCtl
import io.github.sunsetrne.sunsetlinux.core.NpmRegistry
import io.github.sunsetrne.sunsetlinux.core.Prefs
import io.github.sunsetrne.sunsetlinux.ui.components.DshCard
import io.github.sunsetrne.sunsetlinux.ui.components.Pill
import io.github.sunsetrne.sunsetlinux.ui.components.SectionLabel
import io.github.sunsetrne.sunsetlinux.ui.theme.Accent
import io.github.sunsetrne.sunsetlinux.ui.theme.LineStrong
import io.github.sunsetrne.sunsetlinux.ui.theme.Mono2
import io.github.sunsetrne.sunsetlinux.ui.theme.MonoFamily
import io.github.sunsetrne.sunsetlinux.ui.theme.TextMuted
import io.github.sunsetrne.sunsetlinux.ui.theme.TextPrimary
import io.github.sunsetrne.sunsetlinux.ui.theme.TextSecondary
import io.github.sunsetrne.sunsetlinux.ui.theme.WarnTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * npm 源切换（功能 B）。
 *
 * 落点（见 [NpmRegistry] 的说明）：
 * - 真正生效的那份写在**可写层**的 `/root/.npmrc`（经 `linuxctl exec` 进入合并后的 rootfs）；
 * - 同时在环境根写一份 `etc/npmrc`，供运行时将来用 `NPM_CONFIG_USERCONFIG` 注入。
 *
 * 生效方式：**重启环境**。切换后可以点「验证」看工具自己报告的 registry。
 */
@Composable
fun NpmSourceCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(context) }

    var selectedId by remember { mutableStateOf(NpmRegistry.presetOf(prefs.npmRegistry)?.id ?: NpmRegistry.CUSTOM_ID) }
    var customUrl by remember { mutableStateOf(prefs.npmRegistry ?: "") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var verified by remember { mutableStateOf<NpmRegistry.NpmStatus?>(null) }
    var mode by remember { mutableStateOf(EnvMode.ROOT) }

    val chosenUrl = when (selectedId) {
        NpmRegistry.CUSTOM_ID -> customUrl.trim()
        else -> NpmRegistry.presets.firstOrNull { it.id == selectedId }?.url ?: ""
    }

    fun withEnv(block: suspend (LinuxCtl, EnvMode) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            val choice = withContext(Dispatchers.IO) { DshRuntime.resolveMode(context, prefs) }
            mode = choice.mode
            val ctl = LinuxCtl(context, choice.mode)
            val ready = withContext(Dispatchers.IO) {
                if (!ctl.exists()) "环境尚未部署" else {
                    val st = ctl.status()
                    if (st.state != EnvState.RUNNING) "环境未运行（${st.state.label}）" else null
                }
            }
            if (ready != null) {
                busy = false
                notice = "$ready：npm 源需要环境运行中才能写入。"
                return@launch
            }
            block(ctl, choice.mode)
            busy = false
        }
    }

    DshCard(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            SectionLabel("npm 源（registry）")
            Spacer(Modifier.height(4.dp))
            Text(
                text = "插件与依赖都从这里下载。国内直连官方源常常很慢，换个镜像通常立竿见影。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(10.dp))

            NpmRegistry.presets.forEach { preset ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selectedId == preset.id, onClick = { selectedId = preset.id })
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = preset.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                        )
                        Text(
                            text = "${preset.url} · ${preset.note}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                            color = TextMuted,
                        )
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selectedId == NpmRegistry.CUSTOM_ID, onClick = { selectedId = NpmRegistry.CUSTOM_ID })
                Text("自定义", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            }

            if (selectedId == NpmRegistry.CUSTOM_ID) {
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = { customUrl = it },
                    label = { Text("自定义 registry 地址") },
                    placeholder = { Text("https://registry.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                NpmRegistry.validateUrl(customUrl)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = WarnTone)
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "将写入：${NpmRegistry.USER_NPMRC}（可写层）+ $LINUX_HOME_PLACEHOLDER/${NpmRegistry.ENV_NPMRC_RELATIVE}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                color = TextMuted,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "生效方式：**重启环境**后新起的进程才会读到（正在跑的 dsh web 不受影响）。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        val err = if (selectedId == NpmRegistry.CUSTOM_ID) NpmRegistry.validateUrl(customUrl) else null
                        if (err != null) {
                            notice = err
                            return@Button
                        }
                        withEnv { ctl, m ->
                            val result = NpmRegistry.apply(context, m, ctl, chosenUrl)
                            prefs.npmRegistry = chosenUrl
                            notice = if (result.ok) {
                                "已写入 npm 源：$chosenUrl（重启环境后生效）"
                            } else {
                                "写入失败：${result.message}"
                            }
                        }
                    },
                    enabled = !busy,
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("应用")
                }
                TextButton(
                    onClick = {
                        withEnv { ctl, _ ->
                            val status = NpmRegistry.verify(ctl)
                            verified = status
                            notice = status.summary
                        }
                    },
                    enabled = !busy,
                ) { Text("验证当前生效值") }
            }

            verified?.let { st ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Mono2,
                    border = androidx.compose.foundation.BorderStroke(1.dp, LineStrong),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Pill(
                                text = when {
                                    st.error != null -> "读取失败"
                                    st.effective != null && st.fromFile != null && st.effective != st.fromFile -> "不一致"
                                    st.effective != null -> "已生效"
                                    else -> "未设置"
                                },
                                color = if (st.error == null) Accent else WarnTone,
                                filled = true,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(st.summary, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        }
                        st.raw?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = it.trim(),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                                color = TextMuted,
                            )
                        }
                    }
                }
            }

            notice?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (it.contains("失败") || it.contains("尚未") || it.contains("未运行")) WarnTone else TextSecondary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

private const val LINUX_HOME_PLACEHOLDER = "<环境根>"
