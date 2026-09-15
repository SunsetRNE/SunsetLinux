package io.dshroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.dshroid.ui.theme.applyDshSystemBars
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.dshroid.core.DshRuntime
import io.dshroid.core.LinuxCtl
import io.dshroid.core.LogExport
import io.dshroid.core.Prefs
import io.dshroid.ui.components.DshCard
import io.dshroid.ui.components.LogPanel
import io.dshroid.ui.components.SectionLabel
import io.dshroid.ui.copyToClipboard
import io.dshroid.ui.theme.WarnTone
import io.dshroid.ui.theme.DshroidTheme
import io.dshroid.ui.theme.MonoFamily
import io.dshroid.ui.theme.TextMuted
import io.dshroid.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 一键诊断：**流式**跑 `linuxctl doctor`，输出可复制、可导出。
 *
 * 这是针对参考实现"启动失败时用户无从下手"最直接的对策：绿/黄/红一目了然，
 * 而且附了一张"常见失败 → 怎么办"的对照表 + "把这段发给开发者"。
 */
class DiagnosticsActivity : ComponentActivity() {

    private val lines = mutableStateListOf<String>()
    private val running = mutableStateOf(false)
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDshSystemBars()
        setContent {
            DshroidTheme {
                DiagnosticsScreen(
                    lines = lines,
                    running = running.value,
                    onBack = { finish() },
                    onRerun = { runDoctor() },
                    onCopy = {
                        copyToClipboard(this, "DSHroid doctor", lines.joinToString("\n"))
                    },
                    onExport = { exportBundle() },
                )
            }
        }
        runDoctor()
    }

    /** 流式执行 doctor：逐行落进界面，长任务期间界面不会假死。 */
    private fun runDoctor() {
        if (running.value) return
        running.value = true
        lines.clear()
        job?.cancel()
        job = lifecycleScope.launch(Dispatchers.IO) {
            val prefs = Prefs(this@DiagnosticsActivity)
            val choice = DshRuntime.resolveMode(this@DiagnosticsActivity, prefs)
            val ctl = LinuxCtl(this@DiagnosticsActivity, choice.mode)
            emit("# linuxctl doctor  ·  模式 ${choice.mode.modeLabel}  ·  ${ctl.home}")
            choice.note?.let { emit("# 注意：$it") }
            if (!ctl.exists()) {
                emit("✗ 没有找到 linuxctl：${ctl.ctlPath}")
                emit("  请先在侧边栏「重新部署 / 首启引导」里完成部署。")
                withMain { running.value = false }
                return@launch
            }
            emit("$ ${ctl.activeCtlPath} doctor")
            val result = try {
                ctl.stream(listOf("doctor")) { line -> emit(line) }
            } catch (t: Throwable) {
                io.dshroid.core.CtlResult.fail(t.message ?: "doctor 执行失败")
            }
            emit("")
            emit(if (result.ok) "✓ 自检通过" else "✗ 自检未通过：${result.message}")
            emit("")
            emit("提示：把上面这段（或「导出排障包」）发给开发者即可定位。")
            withMain { running.value = false }
        }
    }

    private fun emit(line: String) = withMain { if (lines.size < 2000) lines.add(line) }

    private fun withMain(block: () -> Unit) = runOnUiThread(block)

    private fun exportBundle() {
        lifecycleScope.launch {
            val prefs = Prefs(this@DiagnosticsActivity)
            val choice = DshRuntime.resolveMode(this@DiagnosticsActivity, prefs)
            val file = runCatching {
                LogExport.prune(this@DiagnosticsActivity)
                LogExport.buildBundle(this@DiagnosticsActivity, choice.mode, "诊断（doctor）")
            }.getOrNull()
            if (file != null) runCatching { LogExport.share(this@DiagnosticsActivity, file) }
        }
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }
}

@Composable
private fun DiagnosticsScreen(
    lines: List<String>,
    running: Boolean,
    onBack: () -> Unit,
    onRerun: () -> Unit,
    onCopy: () -> Unit,
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
                Text("环境自检", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "linuxctl doctor · 内核能力 / 层完整性 / 端口 / SELinux",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
            if (running) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            LogPanel(
                lines = lines,
                height = 380.dp,
                autoFollow = true,
                onRefresh = onRerun,
                emptyText = if (running) "正在运行 doctor…" else "点右上角刷新重新运行",
            )

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRerun, enabled = !running) { Text("重新自检") }
                TextButton(onClick = onCopy) { Text("复制输出") }
                TextButton(onClick = onExport) { Text("导出排障包") }
            }

            Spacer(Modifier.height(14.dp))
            DshCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    SectionLabel("常见失败 → 怎么办")
                    Spacer(Modifier.height(8.dp))
                    CheatRow("挂载失败 / overlay / erofs", "内核能力不足或层不完整；看 doctor 的内核能力段。")
                    CheatRow("端口被占用 / EADDRINUSE", "到设置里换端口，再重启环境。")
                    CheatRow("权限不足 / denied / EPERM", "KernelSU 里的 root 授权可能被回收，重新授权。")
                    CheatRow("找不到 linuxctl", "环境未部署：走「重新部署 / 首启引导」。")
                    CheatRow("Web 无响应 / healthy=false", "首次启动初始化较慢，稍等；仍不行看日志区最后几行。")
                    CheatRow("环境随 App 一起停", "非 root 模式的固有行为；Root 模式由模块开机启动，不受影响。")
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = WarnTone,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "排障包内容：status 原始 JSON、linuxctl logs、崩溃堆栈（含 cause 链与 40 帧）、" +
                        "模式/su/环境根等环境摘要。不含任何密钥。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun CheatRow(symptom: String, fix: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            text = symptom,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFamily),
            color = WarnTone,
            modifier = Modifier.width(150.dp),
        )
        Text(text = fix, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}
