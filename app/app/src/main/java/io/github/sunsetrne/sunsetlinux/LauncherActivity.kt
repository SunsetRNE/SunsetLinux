package io.github.sunsetrne.sunsetlinux

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.github.sunsetrne.sunsetlinux.core.EnvState
import io.github.sunsetrne.sunsetlinux.core.Prefs
import io.github.sunsetrne.sunsetlinux.service.LinuxService
import io.github.sunsetrne.sunsetlinux.ui.AppShell
import io.github.sunsetrne.sunsetlinux.ui.BootPlaceholder
import io.github.sunsetrne.sunsetlinux.ui.LauncherViewModel
import io.github.sunsetrne.sunsetlinux.ui.ShellTab
import io.github.sunsetrne.sunsetlinux.ui.theme.SunsetLinuxTheme
import io.github.sunsetrne.sunsetlinux.ui.theme.applyDshSystemBars

/**
 * 启动器外壳：顶栏汉堡 + 侧边栏 + 悬浮胶囊底栏（启动 / 更新 / 插件 / DSH）。
 *
 * ## 冷启动流程（用户实测过"大黑屏"，这里是改过的版本）
 *
 * ```
 * 启动窗口（纯黑，系统 Splash）
 *   └─ LauncherActivity.onCreate
 *        ├─ 没走完引导 → 立即拉起 WelcomeActivity
 *        │    └─ 本页只画 [BootPlaceholder]（**不组合外壳**，首帧很便宜）
 *        └─ 走完引导   → 直接画 AppShell
 * ```
 *
 * 两个刻意的设计：
 * 1. **门禁在 `setContent` 里判**（而不是 `onCreate` 里判一次），并且
 *    `onResume` 时重新读一次 `Prefs.onboarded` —— 从引导页回来（或用户中途退出再进）
 *    都能立刻切到正确界面，不会卡在占位屏；
 * 2. **通知权限不在冷启动那一瞬要**：那时用户还没看到任何解释，弹系统授权框
 *    既突兀又拖慢首帧。改成引导完成、真正拉起状态服务时再要。
 */
class LauncherActivity : ComponentActivity() {

    private val vm: LauncherViewModel by viewModels()

    /** 引导是否已完成。读一次磁盘 + 每次 onResume 刷新（引导页会改写它）。 */
    private var onboarded by mutableStateOf(false)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝不影响功能，只是看不到常驻通知 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDshSystemBars()

        onboarded = Prefs(this).onboarded

        // 没走完引导：先把引导拉起来（本页作为它的"垫底"，只画占位屏）
        if (!onboarded) {
            startActivity(Intent(this, WelcomeActivity::class.java))
        }

        val initialTab = intent?.getStringExtra(EXTRA_TAB)
            ?.let { name -> ShellTab.entries.firstOrNull { it.name == name } }
            ?: ShellTab.START

        setContent {
            SunsetLinuxTheme {
                if (!onboarded) {
                    // 首帧：轻量占位屏（不组合外壳）
                    BootPlaceholder(
                        onOpenWelcome = { startActivity(Intent(this, WelcomeActivity::class.java)) },
                        onExit = { finish() },
                    )
                } else {
                    val ui by vm.ui.collectAsState()

                    // 环境跑起来后拉起状态观察服务（通知栏提供 启动/停止/重启/打开）
                    LaunchedEffect(ui.state) {
                        if (ui.state == EnvState.RUNNING) {
                            LinuxService.ensureRunning(this@LauncherActivity)
                            // 服务要发常驻通知，这时候要权限才讲得通
                            askNotificationPermission()
                        }
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
        }
    }

    override fun onResume() {
        super.onResume()
        // 引导页可能刚刚完成（或用户放弃后手动改了状态）→ 每次回来重新取一次
        val now = Prefs(this).onboarded
        if (now != onboarded) onboarded = now
        if (!onboarded) return
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
        const val EXTRA_TAB = "io.github.sunsetrne.sunsetlinux.extra.TAB"
    }
}
