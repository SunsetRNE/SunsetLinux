package io.dshroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.dshroid.ui.theme.applyDshSystemBars
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.dshroid.core.DshRuntime
import io.dshroid.core.LinuxCtl
import io.dshroid.core.LogExport
import io.dshroid.core.Prefs
import io.dshroid.ui.components.LogPanel
import io.dshroid.ui.theme.DshroidTheme
import io.dshroid.ui.theme.MonoFamily
import io.dshroid.ui.theme.TextMuted
import io.dshroid.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 完整日志页。
 *
 * 数据来源是契约里的 **`linuxctl logs -n N`**，不是直接读 `run/linux.log`：
 * 这样两种模式（root/proot）的读取路径一致，也避免 App 去碰环境根里同目录下
 * 0600 的 `run/dsh.url` 登录凭据。
 */
class LogActivity : ComponentActivity() {

    private val lines = mutableStateListOf<String>()
    private val autoRefresh = mutableStateOf(true)
    private val errorText = mutableStateOf<String?>(null)
    private val modeLabel = mutableStateOf("--")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDshSystemBars()
        setContent {
            DshroidTheme {
                LogScreen(
                    lines = lines,
                    autoRefresh = autoRefresh.value,
                    errorText = errorText.value,
                    modeLabel = modeLabel.value,
                    onBack = { finish() },
                    onToggleAuto = { autoRefresh.value = it },
                    onRefresh = { load() },
                    onExport = { exportBundle() },
                )
            }
        }
        load()
        lifecycleScope.launch {
            while (true) {
                delay(4000)
                if (autoRefresh.value) load()
            }
        }
    }

    private fun load() {
        lifecycleScope.launch(Dispatchers.IO) {
            val prefs = Prefs(this@LogActivity)
            val choice = DshRuntime.resolveMode(this@LogActivity, prefs)
            val ctl = LinuxCtl(this@LogActivity, choice.mode)
            val result = ctl.logs(1000)
            withMain {
                modeLabel.value = choice.mode.modeLabel
                if (result.ok) {
                    errorText.value = null
                    val fresh = result.stdout.lines().filter { it.isNotEmpty() }
                    if (fresh != lines.toList()) {
                        lines.clear()
                        lines.addAll(fresh)
                    }
                } else {
                    errorText.value = result.message
                }
            }
        }
    }

    private fun exportBundle() {
        lifecycleScope.launch {
            val prefs = Prefs(this@LogActivity)
            val choice = DshRuntime.resolveMode(this@LogActivity, prefs)
            val file = runCatching {
                LogExport.prune(this@LogActivity)
                LogExport.buildBundle(this@LogActivity, choice.mode, "日志页导出")
            }.getOrNull()
            if (file != null) runCatching { LogExport.share(this@LogActivity, file) }
        }
    }

    private fun withMain(block: () -> Unit) = runOnUiThread(block)
}

@Composable
private fun LogScreen(
    lines: List<String>,
    autoRefresh: Boolean,
    errorText: String?,
    modeLabel: String,
    onBack: () -> Unit,
    onToggleAuto: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text("环境日志", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "linuxctl logs -n 1000 · 模式 $modeLabel",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
                    color = TextMuted,
                )
            }
            Text("自动", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Spacer(Modifier.width(4.dp))
            Switch(checked = autoRefresh, onCheckedChange = onToggleAuto)
        }

        Box(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            LogPanel(
                lines = lines,
                height = null,
                autoFollow = true,
                onRefresh = onRefresh,
                onExport = onExport,
                emptyText = "暂无日志输出",
                errorText = errorText,
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "自检不通过时，先在侧边栏跑一次 doctor 再导出排障包。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onExport) { Text("导出排障包") }
        }

        Spacer(Modifier.height(6.dp))
    }
}
