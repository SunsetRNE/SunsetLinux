package io.dshroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.dshroid.ui.theme.applyDshSystemBars
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import io.dshroid.core.EnvState
import io.dshroid.core.Prefs
import io.dshroid.service.LinuxService
import io.dshroid.ui.AppShell
import io.dshroid.ui.LauncherViewModel
import io.dshroid.ui.ShellTab
import io.dshroid.ui.theme.DshroidTheme

/**
 * 启动器外壳：顶栏汉堡 + 侧边栏 + 悬浮胶囊底栏（启动 / 更新 / DSH）。
 *
 * 这里只做四件事：沉浸式外观、权限、首启引导门禁、把状态观察服务拉起。
 * 所有状态与副作用都在 [LauncherViewModel] 与 `core/` 里。
 */
class LauncherActivity : ComponentActivity() {

    private val vm: LauncherViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝不影响功能，只是看不到常驻通知 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDshSystemBars()

        // 首启门禁：没走过引导就先弹引导（引导页盖在上面，完成后回到本页）
        if (!Prefs(this).onboarded) {
            startActivity(Intent(this, WelcomeActivity::class.java))
        }

        val initialTab = intent?.getStringExtra(EXTRA_TAB)
            ?.let { name -> ShellTab.entries.firstOrNull { it.name == name } }
            ?: ShellTab.START

        setContent {
            DshroidTheme {
                val ui by vm.ui.collectAsState()

                // 环境跑起来后拉起状态观察服务（通知栏提供 启动/停止/重启/打开）
                LaunchedEffect(ui.state) {
                    if (ui.state == EnvState.RUNNING) LinuxService.ensureRunning(this@LauncherActivity)
                }
                LaunchedEffect(ui.provisioned) { vm.autoStartIfNeeded() }

                AppShell(
                    vm = vm,
                    initialTab = initialTab,
                    onOpenSettings = { section -> startActivity(settingsIntent(this, section)) },
                    onOpenProvision = { startActivity(Intent(this, ProvisionActivity::class.java)) },
                    onOpenDiagnostics = { startActivity(Intent(this, DiagnosticsActivity::class.java)) },
                    onOpenWelcome = { startActivity(Intent(this, WelcomeActivity::class.java)) },
                    onOpenLogScreen = { startActivity(Intent(this, LogActivity::class.java)) },
                )
            }
        }

        askNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        // 回到前台立即刷新并恢复轮询；顺便重新探测 su（引导里可能刚授权）
        vm.startPolling()
        vm.onOnboardingChanged()
    }

    /** Android 13+ 需要运行时授权才能显示常驻通知。 */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_TAB = "io.dshroid.extra.TAB"
    }
}
