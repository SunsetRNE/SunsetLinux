package io.github.sunsetrne.sunsetlinux.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.sunsetrne.sunsetlinux.core.DeviceStatus
import io.github.sunsetrne.sunsetlinux.core.DshRuntime
import io.github.sunsetrne.sunsetlinux.core.DshStatus
import io.github.sunsetrne.sunsetlinux.core.ModuleStatus
import io.github.sunsetrne.sunsetlinux.core.RootProbe
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

    /** 用户选择的模式（默认沿用已有设置；首次安装时默认 root=推荐）。 */
    var mode by mutableStateOf(prefs.modeOverride ?: EnvMode.ROOT)
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

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: MutableStateFlow<List<String>> = _log

    init {
        probe()
    }

    fun append(line: String) {
        _log.value = (_log.value + line).takeLast(400)
    }

    fun dismissMessage() {
        message = null
    }

    fun selectMode(picked: EnvMode) {
        mode = picked
        prefs.modeOverride = picked
    }

    fun acknowledgeModule(done: Boolean) {
        moduleAcknowledged = done
        prefs.moduleStepAcknowledged = done
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
            // ① root：拿到状态（不是布尔） ② 模块：装没装/版本/待重启
            val probe = withContext(Dispatchers.IO) { DeviceStatus.root(force = true) }
            val mod = withContext(Dispatchers.IO) { DeviceStatus.module(force = true) }
            rootProbe = probe
            module = mod
            val su = probe.granted
            suAvailable = su
            val effective = effectiveMode()
            val (exists, st) = withContext(Dispatchers.IO) {
                val ctl = LinuxCtl(context, effective)
                ctl.exists() to ctl.status()
            }
            provisioned = exists
            status = st
            probing = false
            append("· 模式选择：${mode.modeLabel}；实际生效：${effective.modeLabel}")
            append("· root：${probe.label}（${probe.detail}）")
            append("· 模块：${mod.label}")
            append("· linuxctl：${if (exists) "已就位" else "缺失（需要部署）"}")
            // 状态后面必须跟"下一步做什么"，否则用户只能猜（这次就是要解决这个）
            probe.hint?.let { append("  → $it") }
            mod.hint?.let { append("  → $it") }
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
