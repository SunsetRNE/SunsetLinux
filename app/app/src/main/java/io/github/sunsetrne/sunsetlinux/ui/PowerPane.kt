package io.github.sunsetrne.sunsetlinux.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import io.github.sunsetrne.sunsetlinux.core.Edition
import io.github.sunsetrne.sunsetlinux.ui.components.CapsuleReserve
import io.github.sunsetrne.sunsetlinux.ui.components.DshCard
import io.github.sunsetrne.sunsetlinux.ui.components.Pill
import io.github.sunsetrne.sunsetlinux.ui.components.SectionLabel
import io.github.sunsetrne.sunsetlinux.ui.theme.Danger
import io.github.sunsetrne.sunsetlinux.ui.theme.StateRunning
import io.github.sunsetrne.sunsetlinux.ui.theme.TextMuted
import io.github.sunsetrne.sunsetlinux.ui.theme.TextSecondary
import io.github.sunsetrne.sunsetlinux.ui.theme.WarnTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 省电、通知与墓碑/冻结豁免。
 *
 * ## 为什么从「设置」页里拿出来
 *
 * 侧边栏本来就有「冻结与省电豁免」入口（原先也是滚到设置页中段）。这一块与
 * "端口、自启、外观"不是一类东西：它是**系统级授权状态**（电池白名单 / 通知权限）
 * 加一段操作指引，自己一页更清楚，也把设置页让出来。
 *
 * ## 为什么刷新要用 [refreshKey]（轮询心跳）而不是 lifecycle-compose
 *
 * 电池白名单是用户在**系统设置**里改的：从那边返回时本页不会重组。
 * 原先设置页靠 `onResume` 重读；这里外壳没有 onResume 钩子，
 * 而 `LauncherActivity.onResume` 会重启轮询 → `ui.lastSyncedAt` 变化 → 用它的值当
 * LaunchedEffect 的 key 就能重新读一次。为这一处再加一个 lifecycle-compose 依赖不划算。
 */
@Composable
fun PowerPane(refreshKey: Long? = null, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var batteryExempt by remember { mutableStateOf(false) }
    var notifGranted by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<String?>(null) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifGranted = granted
    }

    // 两项状态都要问系统（PowerManager / 权限），放 IO 线程：轮询心跳大约 4 秒一次，
    // 每次重组都在主线程打两次 binder 调用没必要。
    suspend fun readSystemState(): Pair<Boolean, Boolean> = withContext(Dispatchers.IO) {
        val pm = context.getSystemService(PowerManager::class.java)
        val exempt = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        exempt to notif
    }

    fun refreshSystemState() {
        scope.launch {
            val (exempt, notif) = readSystemState()
            batteryExempt = exempt
            notifGranted = notif
        }
    }

    LaunchedEffect(refreshKey) { refreshSystemState() }

    fun openBatterySettings() {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.onFailure {
            notice = "无法打开电池优化设置，请手动到「系统设置 → 电池 → 应用」中放行 SunsetLinux。"
        }
    }

    fun requestBatteryExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(intent) }
            .onFailure { openBatterySettings() }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        DshCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                SectionLabel("省电与通知")
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(
                        text = if (batteryExempt) "已加入电池优化白名单" else "未加入白名单",
                        color = if (batteryExempt) StateRunning else WarnTone,
                        filled = true,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { requestBatteryExemption() }, enabled = !batteryExempt) {
                        Text("申请白名单")
                    }
                    TextButton(onClick = { openBatterySettings() }) { Text("打开系统设置") }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(
                        text = if (notifGranted) "通知权限已授予" else "通知权限被拒绝",
                        color = if (notifGranted) StateRunning else Danger,
                        filled = true,
                    )
                    if (!notifGranted) {
                        TextButton(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }) { Text("去授权") }
                    }
                    // 从系统设置返回后不能只在组合期读一次，给一个手动出口
                    TextButton(onClick = { refreshSystemState() }) { Text("刷新状态") }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        FreezeExemptionCard(onCopyPackage = { copyToClipboard(context, "包名", context.packageName) })

        notice?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = WarnTone, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(CapsuleReserve))
    }
}

/**
 * 墓碑 / 冻结类模块的豁免提示。
 *
 * 这不是可选的"最佳实践"：设备上这类模块会按 per-app 策略用 cgroup freezer
 * 冻结后台应用，被冻结后 App 的状态轮询与通知都会停摆。
 *
 * 措辞刻意**不点名任何具体模块/作者**：这类模块很多，点名既无必要，
 * 也容易让人误以为我们在评价某个第三方实现。只描述「行为」与「怎么豁免」。
 */
@Composable
private fun FreezeExemptionCard(onCopyPackage: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = WarnTone.copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚠️", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "请把本应用加入墓碑/冻结模块豁免",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "部分后台管理类模块（墓碑调度 / 按应用分级冻结）会用 cgroup freezer 冻结后台应用。" +
                    "被冻结后，SunsetLinux 无法轮询环境状态，通知栏的启动/停止/重启也会失效。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                // ★ 操作指引按 edition 分岔：免 root 版的机器上没有 KernelSU/Magisk，
                //   "打开 KernelSU 管理器 → 模块"是一条走不通的路（而且这句话本身就是模块痕迹）。
                //   免 root 版该说的是"在这类模块自己的 App/配置里加白名单"。
                text = if (Edition.showsModuleUi) {
                    "操作指引：\n" +
                        "1. 打开 KernelSU / Magisk 管理器 → 模块，找到你安装的后台冻结类模块的设置；\n" +
                        "2. 在「应用策略 / 白名单」里找到 SunsetLinux（包名 io.github.sunsetrne.sunsetlinux）；\n" +
                        "3. 策略设为「不冻结 / 白名单」并保存。\n" +
                        "root 模式下环境本体不依赖 App 进程 —— 即使 App 被冻结，DSH 仍在运行，" +
                        "只是你看不到状态与通知。"
                } else {
                    "操作指引：\n" +
                        "1. 打开你安装的后台冻结类模块自己的 App / 配置页（这类模块通常带一个管理器）；\n" +
                        "2. 在「应用策略 / 白名单」里找到 SunsetLinux（包名 io.github.sunsetrne.sunsetlinux）；\n" +
                        "3. 策略设为「不冻结 / 白名单」并保存。\n" +
                        "免 root 模式下环境随 App 进程存活 —— App 被冻结，环境就跟着停，所以这一步更要紧。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onCopyPackage) { Text("复制应用包名") }
        }
    }
}
