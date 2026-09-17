package io.github.sunsetrne.sunsetlinux.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.sunsetrne.sunsetlinux.core.BundledModule
import io.github.sunsetrne.sunsetlinux.core.BundledModuleInfo
import io.github.sunsetrne.sunsetlinux.core.DeviceStatus
import io.github.sunsetrne.sunsetlinux.core.DshRuntime
import io.github.sunsetrne.sunsetlinux.core.DshStatus
import io.github.sunsetrne.sunsetlinux.core.ModuleInstaller
import io.github.sunsetrne.sunsetlinux.core.ModuleStatus
import io.github.sunsetrne.sunsetlinux.core.DshPaths
import io.github.sunsetrne.sunsetlinux.core.Edition
import io.github.sunsetrne.sunsetlinux.core.OfflineBundle
import io.github.sunsetrne.sunsetlinux.core.ProotProvisioner
import io.github.sunsetrne.sunsetlinux.core.ProotRuntime
import io.github.sunsetrne.sunsetlinux.core.ProotSetup
import io.github.sunsetrne.sunsetlinux.core.RootProbe
import io.github.sunsetrne.sunsetlinux.core.RootState
import io.github.sunsetrne.sunsetlinux.core.EnvMode
import io.github.sunsetrne.sunsetlinux.core.LinuxCtl
import io.github.sunsetrne.sunsetlinux.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 引导步骤。 */
enum class WelcomeStep { MODE, BRANCH, DEPLOY, DONE }

/**
 * 首启引导的状态机。
 *
 * 目标（用户原话）："安装壳之后，让用户选择是 root 启动还是非 root 环境启动，
 * Root 启动就走刷写模块流程，非 root 就虚拟环境启动流程"。
 *
 * 关键设计：
 * - 选择写入 `Prefs.modeOverride`（**选择优先**）；su 探测只用于校验与提示，
 *   不会被用来偷偷改写用户的选择 —— 但真到执行时 [DshRuntime] 仍会在
 *   "强制 root 但没有 su" 时降级到 proot 并说明原因（这条既有逻辑保留）。
 * - 两条分支最终都收敛到同一个 `linuxctl` 契约与同一个部署入口。
 */
class WelcomeState internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val prefs = Prefs(context)

    var step by mutableStateOf(WelcomeStep.MODE)
        private set

    /**
     * 本 App 的模式 —— 0.3.0 起由 **edition 锁定**（Root 版 / 免 root 版是两个可共存的 App），
     * 不再是"用户选项"。界面上因此没有模式选择（第 34 条那套"先选模式再分叉"的引导
     * 正是误导用户的根源）。
     */
    var mode by mutableStateOf(Edition.lockedMode)
        private set

    /** su 探测结果：null = 还在探测。（保留布尔视图，界面另有可解释的 [rootProbe]） */
    var suAvailable by mutableStateOf<Boolean?>(null)
        private set

    /** root 的**可解释**状态：没有 su / 被拒 / 超时 / 已授权 —— 下一步各不相同。 */
    var rootProbe by mutableStateOf<RootProbe?>(null)
        private set

    /** 模块状态：装没装、版本、是否停用、是否"装了没重启"。 */
    var module by mutableStateOf<ModuleStatus?>(null)
        private set

    /** linuxctl 是否就位（部署结果）。 */
    var provisioned by mutableStateOf<Boolean?>(null)
        private set

    var probing by mutableStateOf(false)
        private set

    var starting by mutableStateOf(false)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    var status by mutableStateOf<DshStatus?>(null)
        private set

    var moduleAcknowledged by mutableStateOf(prefs.moduleStepAcknowledged)
        private set

    /**
     * 这次构建内嵌的模块包（null = 没内嵌 / 本版与模块无关）。
     *
     * ★ 免 root 版**连读都不读**（`assets/module/module.json` 在 proot 包里根本不存在，
     * 那是 root 版才内嵌的产物）。判定收在 [Edition.showsModuleUi]，见 EditionPolicy。
     */
    var bundled by mutableStateOf<BundledModuleInfo?>(null)
        private set

    /** 「一键刷入内置模块」是否在跑。 */
    var flashing by mutableStateOf(false)
        private set

    /** 免 root 模式的就绪度（宿主脚本 / proot 运行时 / rootfs）。 */
    var proot by mutableStateOf<ProotSetup.Readiness?>(null)
        private set

    /** 免 root 分支里"铺环境"这类长任务是否在跑。 */
    var prootBusy by mutableStateOf(false)
        private set

    /** 免 root 引导步骤（有序 + 每步"点哪里"）。 */
    val prootSteps: List<ProotSetup.StepState>
        get() = proot?.let { ProotSetup.plan(it) }.orEmpty()

    /**
     * 模块**真的可用**了（已装 + 已启用 + 已重启生效）。
     *
     * 为什么不用勾选框代替它：勾选框是用户的**断言**，这个是设备的**事实**。
     * 两者任一成立就允许进下一步 —— 事实成立时不该逼用户再勾一次
     * （真机上模块装好又被要求"确认装好"，是纯粹的摩擦）。
     */
    val moduleReady: Boolean
        get() = module?.let { it.readable && it.installed && !it.disabled && !it.pendingReboot } == true

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: MutableStateFlow<List<String>> = _log

    init {
        // 只有 Root 版才有"内嵌模块包"这回事；免 root 版去读它等于白读一次资产
        // （而且一旦 assets 里真出现了同名文件，免 root 版就会冒出模块 UI —— 所以判定必须在读之前）
        bundled = if (Edition.showsModuleUi) BundledModule.info(context) else null
        probe()
    }

    fun append(line: String) {
        _log.value = (_log.value + line).takeLast(400)
    }

    fun dismissMessage() {
        message = null
    }

    /** 保留给旧调用点：单模式版里**不做任何事**（模式由 edition 决定，运行期改不了）。 */
    fun selectMode(picked: EnvMode) {
        // 故意留空：改这个值没有意义 —— 包名/资产/引导流程都按 edition 走
    }

    fun acknowledgeModule(done: Boolean) {
        moduleAcknowledged = done
        prefs.moduleStepAcknowledged = done
    }

    /**
     * 一键刷入**内嵌**的模块包：落盘（校验 sha256）→ `ksud module install`。
     *
     * 与「关于 → 更新模块」共用 [ModuleInstaller]，区别只是包从 assets 来而不是从网上来。
     * 刷完**不替用户重启**（KernelSU 落的是 `modules_update/`，重启才生效），
     * 但会立刻重新探测一次模块状态，让界面能显示"已刷入，待重启"。
     */
    fun flashBundledModule() {
        // 免 root 版不存在这个动作（界面上也没有入口）：再兜一层，避免以后有人从别处调用时
        // 去读 root 版才有的 assets/module 或起 su —— 那是 docs/module-variants.md §一 要切干净的东西
        if (!Edition.showsModuleUi) return
        val info = bundled ?: run {
            message = "这个 APK 没有内嵌模块包。请用「关于 → 更新模块」从官方站下载，" +
                "或把仓库里的 dist/sunsetlinux-module-*.zip 传到手机后用 KernelSU 管理器安装。"
            return
        }
        if (flashing) return
        flashing = true
        append("$ 刷入内置模块 ${info.shortVersion}")
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val zip = BundledModule.extract(context, info)
                    ModuleInstaller.installLocal(
                        zip = zip,
                        version = info.shortVersion,
                        label = "内置模块 ${info.shortVersion}（内嵌包 ${info.fileName}）",
                        onLine = { line -> append("  $line") },
                    )
                } catch (t: Throwable) {
                    ModuleInstaller.Outcome(false, "", t.message ?: t.javaClass.simpleName)
                }
            }
            outcome.log.lineSequence().filter { it.isNotBlank() }.forEach { append(it.trimEnd()) }
            flashing = false
            if (outcome.ok) {
                // 重新探测：ksud 装完落在 modules_update → 状态应是"装了，待重启"
                module = withContext(Dispatchers.IO) { DeviceStatus.module(force = true) }
                message = "模块已刷入：**重启手机**后生效（KernelSU 落的是 modules_update/）。" +
                    "重启后回到这里点「重新检测」，模块状态应显示为已启用。"
            } else {
                message = "刷入失败：${outcome.error ?: "见下方日志"}。" +
                    "可以改用「导出模块包到 Download」，在 KernelSU 管理器里「模块 → 从本地安装」。"
            }
        }
    }

    /** 导出内嵌模块包到 `/sdcard/Download/`（给 KernelSU 管理器的本地安装用）。 */
    fun exportBundledModule() {
        // 同 flashBundledModule：免 root 版没有"模块包"这个概念
        if (!Edition.showsModuleUi) return
        val info = bundled ?: run {
            message = "这个 APK 没有内嵌模块包，没法导出。"
            return
        }
        if (flashing) return
        flashing = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                val export = BundledModule.exportToDownload(context, info)
                if (export.ok) export
                else {
                    // su cp 失败（没有 su / 路径受限）→ 退到 FileProvider 分享，让用户自己存
                    val err = BundledModule.share(context, info)
                    if (err == null) export.copy(error = null, stdout = "已弹出分享面板：保存到 Download 即可")
                    else export.copy(error = "导出失败：$err")
                }
            }
            flashing = false
            if (r.ok) {
                append("✓ 模块包已导出：${r.stdout}")
                message = "已导出到 ${r.stdout}。接下来：KernelSU 管理器 → 「模块」→「从本地安装」→ 选这个 zip → 装完重启。"
            } else {
                append("✗ ${r.message}")
                message = r.message
            }
        }
    }

    /** 打开 root 管理器（KernelSU / KernelSU-Next / MMRL / Magisk）。 */
    fun openModuleManager() {
        // 同 flashBundledModule：免 root 版不该去探测/拉起任何模块管理器
        if (!Edition.showsModuleUi) return
        if (!BundledModule.openManager(context)) {
            message = "没找到已安装的模块管理器（KernelSU / KernelSU-Next / MMRL / Magisk）。请手动打开它。"
        }
    }

    // ───────────────────────────────── 非 root（proot）分支：真的能一键铺好 ─────

    /**
     * 铺免 root 的**宿主脚本**（`bin/linuxctl` 等，来自 APK 内置资产）。
     *
     * 这一步以前在界面上**根本不存在** —— 引导只写"到「更新 → 本机包 → 离线安装」点一下"，
     * 而那个页面要装的是层/运行时，不铺 `bin/`。用户照做之后 doctor 依然报
     * 「没有找到 linuxctl」，于是以为"部署坏了"。
     */
    fun layProotScripts() {
        if (prootBusy) return
        prootBusy = true
        scope.launch {
            append("$ 铺 proot 宿主脚本（内置 assets，约 50 KB）")
            val r = withContext(Dispatchers.IO) {
                runCatching { ProotRuntime.ensure(context, DshPaths.prootLinuxHome(context)) }
                    .getOrElse { ProotRuntime.Result(false, emptyList(), it.message ?: "未知错误", false) }
            }
            append(if (r.ok) "✓ 写入 ${r.written.size} 个文件" else "✗ ${r.error ?: "写入失败"}")
            append("  契约路径 bin/linuxctl：${if (r.contractReady) "已就位" else "未就位"}")
            r.error?.let { append("  注意：$it") }
            proot = withContext(Dispatchers.IO) { ProotSetup.inspect(context) }
            prootBusy = false
            if (r.contractReady) {
                append("下一步：${proot?.let { ProotSetup.plan(it) }?.firstOrNull { !it.done }?.actionLabel ?: "启动环境"}")
            } else {
                message = "脚本没铺成功：${r.error ?: "未知原因"}。这个 APK 可能没内嵌 proot 脚本文档（干净检出/CI 未附产物）。"
            }
        }
    }

    /**
     * 免 root 的「铺环境」：宿主脚本 → 内嵌离线包 → `linuxctl provision`。
     *
     * 为什么这三件事绑在一起：proot 模式的 rootfs 需要**解包**出来（`provision`），
     * 而"料"要么来自内嵌离线包，要么来自已经装好的 base 层（频道）或本机种子 tar。
     * 用户只需要说"铺好它"，顺序由 [ProotProvisioner] 保证。
     *
     * ⚠️ 这条流水线**只有一份**：免 root 版"打开即启用"（`ui/ProotBootstrapState`）走的是
     * 同一个 [ProotProvisioner.run]。以前在这里手写的那份如果留在原地，将来改了顺序
     * （比如"先装包再 provision"的修正）就会只改到手动入口、自动入口继续失败。
     */
    fun provisionProot() {
        if (prootBusy) return
        prootBusy = true
        scope.launch {
            val ctl = LinuxCtl(context, EnvMode.PROOT)
            val bundle = proot?.embeddedBundle
                ?: withContext(Dispatchers.IO) { runCatching { OfflineBundle.readHeaderOnly(context) }.getOrNull() }

            val outcome = withContext(Dispatchers.IO) {
                ProotProvisioner.run(context, EnvMode.PROOT, bundle, seedDir = null) { append(it) }
            }

            proot = withContext(Dispatchers.IO) { ProotSetup.inspect(context) }
            provisioned = withContext(Dispatchers.IO) { ctl.exists() }
            prootBusy = false
            message = when {
                proot?.rootfsReady == true ->
                    "免 root 环境已铺好（rootfs 就位）。回首页点「启动环境」即可。"
                proot?.hasRootSource == false ->
                    "还没有可用的 rootfs 来源：去「更新」页从频道安装 base 层（erofs）后再点一次这个按钮。" +
                        if (outcome.ok) "" else "（这次失败在：${outcome.failedStep?.label ?: "未知步骤"}）"
                else ->
                    "provision 没有成功：${outcome.error ?: "原因见日志"}"
            }
        }
    }

    fun go(step: WelcomeStep) {
        this.step = step
    }

    fun next() {
        step = when (step) {
            WelcomeStep.MODE -> WelcomeStep.BRANCH
            WelcomeStep.BRANCH -> WelcomeStep.DEPLOY
            WelcomeStep.DEPLOY -> WelcomeStep.DONE
            WelcomeStep.DONE -> WelcomeStep.DONE
        }
    }

    fun back(): Boolean = when (step) {
        WelcomeStep.MODE -> false
        WelcomeStep.BRANCH -> { step = WelcomeStep.MODE; true }
        WelcomeStep.DEPLOY -> { step = WelcomeStep.BRANCH; true }
        WelcomeStep.DONE -> { step = WelcomeStep.DEPLOY; true }
    }

    /** 探测 su 与 linuxctl。选择不动，只更新校验结果。 */
    fun probe() {
        if (probing) return
        probing = true
        scope.launch {
            // ① root/模块：**只有 Root 版才探**（免 root 版探 su 毫无意义，还可能弹授权框）
            val probe = if (Edition.needsSu) {
                withContext(Dispatchers.IO) { DeviceStatus.root(force = true) }
            } else {
                RootProbe(RootState.UNKNOWN, "免 root 版不探测 su（也不需要）")
            }
            // 统一走 Edition.showsModuleUi（与 needsKernelSuModule 等价，见 EditionPolicyTest）：
            // 切割判定只有一个入口，以后加面板时不会再有人漏掉另一半
            val mod = if (Edition.showsModuleUi) {
                withContext(Dispatchers.IO) { DeviceStatus.module(force = true) }
            } else {
                null
            }
            // ② 免 root：宿主脚本 / proot 运行时 / rootfs（纯文件检查，不需要 su）
            val pr = withContext(Dispatchers.IO) { ProotSetup.inspect(context) }
            rootProbe = probe
            module = mod
            proot = pr
            val su = if (Edition.needsSu) probe.granted else false
            suAvailable = su
            val effective = effectiveMode()
            val (exists, st) = withContext(Dispatchers.IO) {
                val ctl = LinuxCtl(context, effective)
                ctl.exists() to ctl.status()
            }
            provisioned = exists
            status = st
            probing = false
            append("· 本版：${Edition.label}（${Edition.applicationId}）· 模式固定为 ${effective.modeLabel}")
            if (Edition.needsSu) {
                append("· root：${probe.label}（${probe.detail}）")
            } else {
                append("· root：免 root 版不需要 root（不探测 su）")
            }
            // ★ 模块那一行只在 Root 版打印：免 root 版的日志里出现"KernelSU 模块"字样，
            //   正是 docs/module-variants.md §一 要消灭的痕迹（原来的写法还会打印一句
            //   "免 root 版与 KernelSU 模块无关"—— 提模块本身就是痕迹）。
            if (Edition.showsModuleUi) {
                append("· 模块：${mod?.label ?: "检测中…"}")
            }
            append("· 免 root 就绪度：${ProotSetup.summary(pr)}（缺：${pr.missingLabel.ifEmpty { "无" }}）")
            append("· linuxctl：${if (exists) "已就位" else "缺失（需要部署）"}")
            // 状态后面必须跟"下一步做什么"，否则用户只能猜（这次就是要解决这个）
            probe.hint?.let { append("  → $it") }
            if (Edition.showsModuleUi) mod?.hint?.let { append("  → $it") }
            if (!exists) append("  ${st.lastError ?: ""}")
        }
    }

    /** 用户选择优先；只有"选了 root 但确实没有 su"才在执行侧降级。 */
    fun effectiveMode(): EnvMode = if (mode == EnvMode.ROOT && suAvailable == false) EnvMode.PROOT else mode

    /** 引导最后一步：直接尝试启动环境（幂等）。 */
    fun startEnvironment(onFinished: (Boolean, String) -> Unit) {
        if (starting) return
        starting = true
        scope.launch {
            val effective = effectiveMode()
            append("$ ${effective.modeLabel} · linuxctl start")
            val result = try {
                withContext(Dispatchers.IO) { LinuxCtl(context, effective).start() }
            } catch (t: Throwable) {
                io.github.sunsetrne.sunsetlinux.core.CtlResult.fail(t.message ?: "启动失败")
            }
            append(if (result.ok) "✓ 已提交启动" else "✗ ${result.message}")
            starting = false
            if (!result.ok) message = "启动失败：${result.message}"
            onFinished(result.ok, result.message)
        }
    }

    /** 完成引导。 */
    fun finish() {
        prefs.onboarded = true
    }
}
