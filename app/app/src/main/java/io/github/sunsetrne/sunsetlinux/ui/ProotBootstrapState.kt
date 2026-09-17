package io.github.sunsetrne.sunsetlinux.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.sunsetrne.sunsetlinux.core.CtlResult
import io.github.sunsetrne.sunsetlinux.core.Edition
import io.github.sunsetrne.sunsetlinux.core.EnvMode
import io.github.sunsetrne.sunsetlinux.core.LinuxCtl
import io.github.sunsetrne.sunsetlinux.core.OfflineBundle
import io.github.sunsetrne.sunsetlinux.core.Prefs
import io.github.sunsetrne.sunsetlinux.core.ProotBootstrap
import io.github.sunsetrne.sunsetlinux.core.ProotProvisioner
import io.github.sunsetrne.sunsetlinux.core.ProotSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 免 root 版「打开即直接启用内置 Ubuntu 环境」的状态机。
 *
 * ## 它解决什么
 *
 * 用户要求：**打开免 root 版就自动把内置环境启用起来，最后落到「终端」页**，而不是让用户
 * 在首启引导里自己点"铺运行时 → 铺环境 → 启动"三步（点错顺序还会走进死胡同）。
 * 见 `docs/module-variants.md` §1.3 与 §六 的验收判据。
 *
 * ## 判定与副作用分开
 *
 * ——"该不该跑"是纯函数 [ProotBootstrap.decide]（有单测），这里只负责执行：
 * ```
 * 判 → 铺宿主脚本 → 只补缺的离线包部件 → linuxctl provision → linuxctl start → 交回终端页
 * ```
 * 铺环境那一段复用 [ProotProvisioner]（与首启引导里那个按钮**同一份**实现），
 * 所以"手动能成、自动不成"这类漂移不会发生。
 *
 * ## 几处刻意的实现选择
 *
 * - **判定与文件读写全在 IO 协程**：`OfflineBundle.readHeaderOnly` / `ProotSetup.inspect`
 *   都要碰磁盘，而本对象是在**组合期**（主线程）创建的 —— 同步做就会把冷启动首帧拖长，
 *   那正是 `BootPlaceholder` 当初要解决的"很大的黑屏"。
 * - **日志用 `MutableStateFlow`**：`OfflineApplier` / `LinuxCtl.stream` 的进度回调是从
 *   IO 线程打过来的，直接写 `mutableStateOf` 不是线程安全的（Compose 的 snapshot state
 *   不保证跨线程可见性）；`WelcomeState` 用同一套写法。
 * - **成功后写 `Prefs.onboarded`**：这是"首启引导已完成"的既有闸门。自动启用等价于走完引导，
 *   不写它的话下次冷启动又会进自动页；而失败时**不写**，让用户下次还有机会自动重试
 *   （只有他明确点过「手动部署」才会用 [Prefs.prootBootstrapDeclined] 永久关掉自动路）。
 */
class ProotBootstrapState internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    enum class Phase {
        /** 正在判定（读内嵌包头 + 就绪度）。 */
        CHECKING,

        /** 正在跑流水线（铺脚本 / 装离线包 / provision / start）。 */
        WORKING,

        /** 全部完成：环境已启动。 */
        DONE,

        /** 失败：界面显示 [error]（原始错误）+ 重试 + 手动路径。 */
        FAILED,

        /** 不适用（root 版 / 已取消 / 没内嵌离线包）→ 交回既有引导页。 */
        NOT_APPLICABLE,

        /** 环境本来就绪 → 不做任何重活，直接进外壳。 */
        ALREADY_READY,
    }

    var phase by mutableStateOf(Phase.CHECKING)
        private set

    /** 失败的**原始错误**（脚本/命令打的原文，界面直接展示，不做二次翻译）。 */
    var error by mutableStateOf<String?>(null)
        private set

    /**
     * 用户是否**刚刚**点了「手动部署」（= 主动放弃自动路）。
     *
     * 为什么要有这个标记：`decline()` 会把 [phase] 置成 [Phase.NOT_APPLICABLE]，而调用方
     * （`LauncherActivity`）在 NOT_APPLICABLE 时也会去拉首启引导页 —— 两边同时拉就会叠出
     * 两个引导页。调用方据此跳过自己那一次。
     */
    var declinedByUser by mutableStateOf(false)
        private set

    private val _log = MutableStateFlow<List<String>>(emptyList())

    /**
     * 逐行日志（"当前在做什么" = 最后一行）。
     *
     * ⚠️ 用 `MutableStateFlow` 而不是 `mutableStateOf`：`OfflineApplier` / `LinuxCtl.stream`
     * 的进度回调是从 **IO 线程**打过来的，不能直接写 Compose 的 snapshot state
     * （`WelcomeState` 用的是同一套写法）。
     */
    val log: StateFlow<List<String>> = _log

    private var started = false
    private var job: Job? = null

    private fun append(line: String) {
        if (line.isBlank()) return
        _log.value = (_log.value + line).takeLast(400)
    }

    /** 幂等：组合期调一次即可。 */
    fun start() {
        if (started) return
        started = true
        job = scope.launch { decideAndRun() }
    }

    /** 失败后的「重试」：整个流水线可重入（缺什么补什么，不重复解包）。 */
    fun retry() {
        if (phase == Phase.WORKING) return
        job = scope.launch { run() }
    }

    /**
     * 用户点了「手动部署」：**明确取消**自动路。
     *
     * 取消正在跑的协程 + 落 [Prefs.prootBootstrapDeclined]，之后冷启动只走首启引导。
     * 没有这一步，"取消过"这个条件就永远不成立，用户想自己一步步铺都没有入口。
     */
    fun decline() {
        job?.cancel()
        Prefs(context).prootBootstrapDeclined = true
        declinedByUser = true
        phase = Phase.NOT_APPLICABLE
    }

    private suspend fun decideAndRun() {
        // 判定需要的两件事都要碰磁盘：内嵌包头（几 KB，资产里）与免 root 就绪度（几个 stat）
        val (bundle, ready) = withContext(Dispatchers.IO) {
            runCatching { OfflineBundle.readHeaderOnly(context) }.getOrNull() to
                runCatching { ProotSetup.inspect(context).complete }.getOrDefault(false)
        }
        when (
            ProotBootstrap.decide(
                isRoot = Edition.isRoot,
                declined = Prefs(context).prootBootstrapDeclined,
                hasEmbeddedBundle = bundle != null,
                ready = ready,
            )
        ) {
            ProotBootstrap.Decision.NOT_APPLICABLE -> phase = Phase.NOT_APPLICABLE
            ProotBootstrap.Decision.ALREADY_READY -> {
                append("· 内置环境已就绪（宿主脚本 / proot 运行时 / rootfs 都在），跳过铺设")
                phase = Phase.ALREADY_READY
            }
            ProotBootstrap.Decision.RUN -> run()
        }
    }

    private suspend fun run() {
        phase = Phase.WORKING
        error = null
        _log.value = emptyList()
        append("$ 启用内置 Ubuntu 环境（免 root：不需要 root、不需要刷机）")

        // 只在这里读一次包头：读完传进流水线，避免流水线内部再去读（那会让"读没读到"
        // 这件事有两个事实源）
        val bundle = withContext(Dispatchers.IO) {
            runCatching { OfflineBundle.readHeaderOnly(context) }.getOrNull()
        }
        if (bundle == null) {
            // 判定的那一刻读到了、现在读不到（资产被换/包不完整）→ 老老实实退回引导页
            phase = Phase.NOT_APPLICABLE
            return
        }

        val outcome = ProotProvisioner.run(context, EnvMode.PROOT, bundle, seedDir = null) { append(it) }
        if (!outcome.ok) {
            error = outcome.error ?: "铺环境失败（${outcome.failedStep?.label ?: "未知步骤"}）"
            append("✗ $error")
            phase = Phase.FAILED
            return
        }

        append("$ linuxctl start")
        val start = try {
            LinuxCtl(context, EnvMode.PROOT).start()
        } catch (t: Throwable) {
            CtlResult.fail(t.message ?: "启动失败")
        }
        if (!start.ok) {
            error = start.message
            append("✗ 启动失败：${start.message}")
            phase = Phase.FAILED
            return
        }
        append("✓ 环境已启动，正在进入终端")
        // 自动启用 = 走完了首启引导：写这个闸门，下次冷启动直接进外壳
        Prefs(context).onboarded = true
        phase = Phase.DONE
    }
}
