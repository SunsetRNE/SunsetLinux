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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.github.sunsetrne.sunsetlinux.core.Edition
import io.github.sunsetrne.sunsetlinux.core.EnvState
import io.github.sunsetrne.sunsetlinux.core.Prefs
import io.github.sunsetrne.sunsetlinux.service.LinuxService
import io.github.sunsetrne.sunsetlinux.ui.AppShell
import io.github.sunsetrne.sunsetlinux.ui.BootPlaceholder
import io.github.sunsetrne.sunsetlinux.ui.LauncherViewModel
import io.github.sunsetrne.sunsetlinux.ui.ProotBootstrapScreen
import io.github.sunsetrne.sunsetlinux.ui.ProotBootstrapState
import io.github.sunsetrne.sunsetlinux.ui.ShellTab
import io.github.sunsetrne.sunsetlinux.ui.theme.SunsetLinuxTheme
import io.github.sunsetrne.sunsetlinux.ui.theme.applyDshSystemBars

/**
 * 启动器外壳：顶栏汉堡 + 侧边栏 + 悬浮胶囊底栏（启动 / 更新 / 插件 / 终端）。
 *
 * ## 冷启动流程（用户实测过"大黑屏"，这里是改过的版本）
 *
 * ```
 * 启动窗口（纯黑，系统 Splash）
 *   └─ LauncherActivity.onCreate
 *        ├─ 免 root 版且没取消过 → 「启用内置环境」屏（ProotBootstrapScreen）
 *        │    ├─ 判定不适用（root 版/没内嵌包/已取消）→ 拉起 WelcomeActivity，本页画 BootPlaceholder
 *        │    └─ 成功/本就绪 → 落「终端」页（写 Prefs.onboarded 后再组合 AppShell）
 *        ├─ 其它情况没走完引导 → 立即拉起 WelcomeActivity
 *        │    └─ 本页只画 [BootPlaceholder]（**不组合外壳**，首帧很便宜）
 *        └─ 走完引导   → 直接画 AppShell
 * ```
 *
 * 三个刻意的设计：
 * 1. **门禁在 `setContent` 里判**（而不是 `onCreate` 里判一次），并且
 *    `onResume` 时重新读一次 `Prefs.onboarded` —— 从引导页回来（或用户中途退出再进）
 *    都能立刻切到正确界面，不会卡在占位屏；
 * 2. **通知权限不在冷启动那一瞬要**：那时用户还没看到任何解释，弹系统授权框
 *    既突兀又拖慢首帧。改成引导完成、真正拉起状态服务时再要；
 * 3. **免 root 版"进入即启用"**（用户要求，见 docs/module-variants.md §1.3）：不必先走引导页，
 *    打开就把内置环境铺好并停在终端。这里只判**静态条件**（edition + 用户取消标记），
 *    "内嵌包读得到吗 / 环境就绪吗"要碰磁盘，交给 [ProotBootstrapState] 在 IO 协程里判 ——
 *    冷启动首帧不做磁盘 IO，这是当初"大黑屏"那次的教训。
 */
class LauncherActivity : ComponentActivity() {

    private val vm: LauncherViewModel by viewModels()

    /** 引导是否已完成。读一次磁盘 + 每次 onResume 刷新（引导页会改写它）。 */
    private var onboarded by mutableStateOf(false)

    /** 这次冷启动是否交给「启用内置环境」屏（免 root 版专用；其它情况一律直走既有引导）。 */
    private var deferToProotBootstrap by mutableStateOf(false)

    /** 进入外壳时停在哪一页。免 root 版默认终端（用户要求"最后落到终端页"）。 */
    private var startTab by mutableStateOf(ShellTab.START)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝不影响功能，只是看不到常驻通知 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDshSystemBars()

        val prefs = Prefs(this)
        onboarded = prefs.onboarded

        // 免 root 版：默认交给"进入即启用"。用户点过「手动部署」就直走引导页
        // （`prootBootstrapDeclined` 的语义见 Prefs 里的注释：它是"我不想让 App 自动铺"，
        //  与"引导走完了"是两件事，混用会让"自动失败→点手动"再也回不到自动路）。
        deferToProotBootstrap = !onboarded && !Edition.isRoot && !prefs.prootBootstrapDeclined
        if (!onboarded && !deferToProotBootstrap) {
            // 没走完引导：先把引导拉起来（本页作为它的"垫底"，只画占位屏）
            startActivity(Intent(this, WelcomeActivity::class.java))
        }

        // 显式指定 tab（通知/快捷方式）优先；否则按 edition 给默认页
        startTab = intent?.getStringExtra(EXTRA_TAB)
            ?.let { name -> ShellTab.entries.firstOrNull { it.name == name } }
            ?: if (Edition.isRoot) ShellTab.START else ShellTab.TERMINAL

        setContent {
            SunsetLinuxTheme {
                if (!onboarded && deferToProotBootstrap) {
                    // 免 root 版首启：自动铺环境，成功后落到终端（不在这一屏组合外壳）
                    val scope = rememberCoroutineScope()
                    val boot = remember { ProotBootstrapState(this, scope) }
                    LaunchedEffect(Unit) { boot.start() }
                    LaunchedEffect(boot.phase) {
                        when (boot.phase) {
                            // 不适用（没内嵌离线包等）→ 交回既有引导。
                            // 注意排除"用户刚点了手动部署"那种：那条路已经自己拉起引导页了，
                            // 这里再拉一次会出现两个引导页叠着。
                            ProotBootstrapState.Phase.NOT_APPLICABLE ->
                                if (!boot.declinedByUser) {
                                    startActivity(Intent(this@LauncherActivity, WelcomeActivity::class.java))
                                }

                            // 装好 / 本就绪 = 等价于走完首启引导：写闸门 + 直接进外壳（停在终端）
                            ProotBootstrapState.Phase.DONE,
                            ProotBootstrapState.Phase.ALREADY_READY,
                            -> {
                                Prefs(this@LauncherActivity).onboarded = true
                                startTab = ShellTab.TERMINAL
                                onboarded = true
                            }

                            else -> Unit
                        }
                    }
                    if (boot.phase == ProotBootstrapState.Phase.NOT_APPLICABLE) {
                        // 判定为"不走自动路"时用占位屏兜底：它有「打开首启引导」与「退出」两个
                        // 出口，用户从引导页返回也不会停在一个没有按钮的死页面上
                        BootPlaceholder(
                            onOpenWelcome = { startActivity(Intent(this, WelcomeActivity::class.java)) },
                            onExit = { finish() },
                        )
                    } else {
                        ProotBootstrapScreen(
                            state = boot,
                            onRetry = boot::retry,
                            onManual = {
                                // 记下"用户明确取消自动"并送他去引导页，之后冷启动不再自动跑
                                boot.decline()
                                startActivity(Intent(this, WelcomeActivity::class.java))
                            },
                        )
                    }
                } else if (!onboarded) {
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
                        initialTab = startTab,
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
