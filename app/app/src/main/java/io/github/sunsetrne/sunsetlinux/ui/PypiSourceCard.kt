package io.github.sunsetrne.sunsetlinux.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sunsetrne.sunsetlinux.core.Prefs
import io.github.sunsetrne.sunsetlinux.core.PypiRegistry
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

/**
 * Python 源切换（pip index-url）。
 *
 * 落点与理由见 [PypiRegistry]：可写层的 `/root/.config/pip/pip.conf`（真正生效的那份）
 * + 环境根的 `etc/pip.conf`（配置面 / 系统级兜底）。
 *
 * 生效方式：**重启环境**。切完可以点「验证当前生效值」看 pip 自己报告的值。
 */
@Composable
fun PypiSourceCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val session = rememberSourceEnvSession()

    // 默认档 = 官方：没选过（prefs 为空）时**不能**落进「自定义」——
    // 那是真机上真实出现过的缺陷（npm 那张卡），抽成纯函数后这里与 core 同一口径。
    var selectedId by remember { mutableStateOf(PypiRegistry.selectedPresetId(prefs.pypiIndex)) }
    var customUrl by remember { mutableStateOf(PypiRegistry.customSeed(prefs.pypiIndex)) }
    // 与 npm 卡同一手法：Prefs 不是 Compose 状态，点完「应用」不会触发重组
    var stored by remember { mutableStateOf(prefs.pypiIndex) }
    var verified by remember { mutableStateOf<PypiRegistry.PypiStatus?>(null) }

    val chosenUrl = when (selectedId) {
        PypiRegistry.CUSTOM_ID -> customUrl.trim()
        else -> PypiRegistry.presets.firstOrNull { it.id == selectedId }?.url ?: PypiRegistry.official.url
    }

    fun apply() {
        val err = if (selectedId == PypiRegistry.CUSTOM_ID) PypiRegistry.validateUrl(customUrl) else null
        if (err != null) {
            session.warn(err)
            return
        }
        session.launch { ctl, mode ->
            val result = PypiRegistry.apply(context, mode, ctl, chosenUrl)
            prefs.pypiIndex = chosenUrl
            stored = chosenUrl
            if (result.ok) {
                "已写入 Python 源：$chosenUrl（重启环境后生效）"
            } else {
                "写入失败：${result.message}"
            }
        }
    }

    DshCard(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            SectionLabel("Python 源（pip index-url）")
            Spacer(Modifier.height(4.dp))
            Text(
                text = "pip 装包与虚拟环境依赖都从这里下载。国内直连官方 PyPI 常常很慢，" +
                    "换个镜像通常立竿见影。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(10.dp))

            PypiRegistry.presets.forEach { preset ->
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
                RadioButton(selected = selectedId == PypiRegistry.CUSTOM_ID, onClick = { selectedId = PypiRegistry.CUSTOM_ID })
                Text("自定义", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            }

            if (selectedId == PypiRegistry.CUSTOM_ID) {
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = { customUrl = it },
                    label = { Text("自定义 index-url") },
                    placeholder = { Text("https://mirror.example.com/pypi/simple") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                PypiRegistry.validateUrl(customUrl)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = WarnTone)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "只接受 https：pip 会把源里下载的包直接安装执行，明文 http 可被链路上任何人替换包内容。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }

            Spacer(Modifier.height(10.dp))
            if (stored.isNullOrBlank()) {
                Text(
                    text = "尚未选择过：按官方源 ${PypiRegistry.official.url} 处理。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = "将写入：${PypiRegistry.USER_PIP_CONF}（可写层）+ " +
                    "$ENV_HOME_PLACEHOLDER/${PypiRegistry.ENV_PIP_CONF_RELATIVE}",
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
                Button(onClick = { apply() }, enabled = !session.busy) {
                    if (session.busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("应用")
                }
                TextButton(
                    onClick = {
                        session.launch { ctl, mode ->
                            val status = PypiRegistry.verify(context, mode, ctl)
                            verified = status
                            status.summary
                        }
                    },
                    enabled = !session.busy,
                ) { Text("验证当前生效值") }
            }

            verified?.let { st ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Mono2,
                    border = BorderStroke(1.dp, LineStrong),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Pill(
                                text = when {
                                    st.error != null -> "读取失败"
                                    st.effective != null && st.fromUserConf != null && st.effective != st.fromUserConf -> "不一致"
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

            session.notice?.let {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (it.contains("失败") || it.contains("尚未") || it.contains("未运行")) WarnTone else TextSecondary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { session.dismissNotice() }) { Text("知道了") }
                }
            }
        }
    }
}
