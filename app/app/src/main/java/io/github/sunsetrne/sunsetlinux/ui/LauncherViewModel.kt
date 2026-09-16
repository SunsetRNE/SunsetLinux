package io.github.sunsetrne.sunsetlinux.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sunsetrne.sunsetlinux.core.CtlResult
import io.github.sunsetrne.sunsetlinux.core.Diagnoser
import io.github.sunsetrne.sunsetlinux.core.DshRuntime
import io.github.sunsetrne.sunsetlinux.core.DshStatus
import io.github.sunsetrne.sunsetlinux.core.EnvMode
import io.github.sunsetrne.sunsetlinux.core.EnvState
import io.github.sunsetrne.sunsetlinux.core.Hint
import io.github.sunsetrne.sunsetlinux.core.LinuxCtl
import io.github.sunsetrne.sunsetlinux.core.LogExport
import io.github.sunsetrne.sunsetlinux.core.Prefs
import io.github.sunsetrne.sunsetlinux.core.TransportSupport
import io.github.sunsetrne.sunsetlinux.core.UpdateChecker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 首页状态机。
 *
 * 三条轮询线（都在 IO 协程，互不阻塞主线程）：
 * 1. `status` —— 4 秒，驱动状态卡；
 * 2. `logs -n 200` —— 5 秒，喂固定的日志面板（**常显**是排障的前提）；
 * 3. 频道更新检查 —— 10 分钟一次，只为一个角标。
 *
 * 失败时不只给"错误"，还给出**当前阶段**与**可点的下一步**（[Diagnoser]）。
 */
class LauncherViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val loading: Boolean = true,
        val mode: EnvMode = EnvMode.ROOT,
        val modeNote: String? = null,
        val suAvailable: Boolean = false,
        /** root 的**可解释**状态（没有 su / 被拒 / 超时 / 已授权）：只留布尔说不出"该去哪一步"。 */
        val rootLabel: String? = null,
        val rootHint: String? = null,
        /** 模块状态：`模块 1.0.9（已启用）` / `模块未装` / `模块状态未知`… */
        val moduleLabel: String? = null,
        val moduleHint: String? = null,
        /** 模块状态**读到了没有**（读不到 ≠ 没装；关于页据此决定给不给"刷入"按钮）。 */
        val moduleReadable: Boolean = false,
        /** 已装模块版本（`1.0.9`），读不到就是 null。 */
        val moduleVersion: String? = null,
        /** null = 还没探测出来 */
        val provisioned: Boolean? = null,
        val status: DshStatus? = null,
        /** 正在执行 start/stop/restart/reset */
        val busy: Boolean = false,
        /** 一次性提示 */
        val message: String? = null,
        val updateCount: Int = 0,
        val updateChecked: Boolean = false,
        val doctorRunning: Boolean = false,
        val doctorOutput: String? = null,
        val showDoctor: Boolean = false,
        val rawJson: String? = null,
        val lastSyncedAt: Long? = null,
        /** 当前阶段（授权/部署/启动/健康检查/挂载/端口…） */
        val stage: String = "读取状态",
        /** 下一步建议 */
        val hints: List<Hint> = emptyList(),
        val logAutoFollow: Boolean = true,
        val logError: String? = null,
        val exporting: Boolean = false,
        val zstdReason: String? = null,
    ) {
        val state: EnvState get() = status?.state ?: EnvState.UNKNOWN
        val canOpenWeb: Boolean get() = status?.canOpenWeb == true
    }

    private val prefs = Prefs(app)
    private val _ui = MutableStateFlow(
        // ⚠️ 这里**故意不**做 zstd 能力探测：探测要"写临时文件 + 解一段真实的 zstd 帧"，
        //    是实打实的磁盘 IO；而 ViewModel 由 `by viewModels()` 在**主线程**构造，
        //    放构造器里就等于往冷启动首帧里塞 IO（用户实测的"首装大黑屏"成因之一）。
        //    改成 init 里丢到 IO 协程补上。
        UiState(),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    private var pollJob: Job? = null
    private var logJob: Job? = null
    private var updateJob: Job? = null
    private var autoStartTried = false
    private var lastUpdateCheckAt = 0L

    init {
        _ui.update { it.copy(logAutoFollow = prefs.logAutoFollow) }
        // zstd 能力探测（磁盘 IO）放到 IO 线程，别占冷启动首帧
        viewModelScope.launch {
            val reason = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                TransportSupport.zstdUnavailableReason()
            }
            _ui.update { it.copy(zstdReason = reason) }
        }
        // root / 模块状态：一次探测、结果进 UiState（"关于"页与首页都用它）
        viewModelScope.launch {
            val io2 = kotlinx.coroutines.Dispatchers.IO
            val root = kotlinx.coroutines.withContext(io2) {
                io.github.sunsetrne.sunsetlinux.core.DeviceStatus.root(force = true)
            }
            val module = kotlinx.coroutines.withContext(io2) {
                io.github.sunsetrne.sunsetlinux.core.DeviceStatus.module(force = true)
            }
            _ui.update {
                it.copy(rootLabel = root.label, rootHint = root.hint,
                        moduleLabel = module.label, moduleHint = module.hint,
                        moduleReadable = module.readable, moduleVersion = module.version)
            }
        }
        startPolling()
    }

    // ------------------------------------------------------------ 轮询

    fun startPolling() {
        if (pollJob?.isActive != true) {
            pollJob = viewModelScope.launch {
                while (isActive) {
                    refresh()
                    delay(POLL_MS)
                }
            }
        }
        if (logJob?.isActive != true) {
            logJob = viewModelScope.launch {
                while (isActive) {
                    refreshLogs()
                    delay(LOG_POLL_MS)
                }
            }
        }
    }

    /** 单次刷新：解析模式 → 判断是否已部署 → 读状态 → 归因。 */
    suspend fun refresh() {
        val app = getApplication<Application>()
        try {
            val choice = DshRuntime.resolveMode(app, prefs)
            val ctl = LinuxCtl(app, choice.mode)
            val su = DshRuntime.suAvailable()
            val exists = ctl.exists()

            val status = if (exists) ctl.status() else DshStatus.unavailable(NOT_PROVISIONED_HINT)
            val mode = status.modeEnum ?: choice.mode

            _ui.update { prev ->
                val stage = Diagnoser.stage(status, exists, su, mode)
                prev.copy(
                    loading = false,
                    mode = mode,
                    modeNote = choice.note,
                    suAvailable = su,
                    provisioned = exists,
                    status = status,
                    stage = stage,
                    hints = Diagnoser.hints(
                        status = status,
                        provisioned = exists,
                        suAvailable = su,
                        mode = mode,
                        zstdUnavailableReason = prev.zstdReason,
                        updateCount = prev.updateCount,
                    ),
                    lastSyncedAt = System.currentTimeMillis(),
                )
            }
            maybeCheckUpdates(status)
        } catch (t: Throwable) {
            _ui.update {
                it.copy(
                    loading = false,
                    status = DshStatus.unavailable("读取环境状态失败：${t.message ?: t.javaClass.simpleName}"),
                    stage = "读取状态失败",
                )
            }
        }
    }

    /** 日志轮询：只在已部署时跑，避免每 5 秒刷一条"找不到 linuxctl"。 */
    private suspend fun refreshLogs() {
        val app = getApplication<Application>()
        val snapshot = _ui.value
        if (snapshot.provisioned != true) {
            _logLines.value = emptyList()
            _ui.update { it.copy(logError = null) }
            return
        }
        try {
            val ctl = LinuxCtl(app, snapshot.mode)
            val result = ctl.logs(200)
            if (result.ok) {
                val fresh = result.stdout.lines().filter { it.isNotEmpty() }
                if (fresh != _logLines.value) _logLines.value = fresh
                _ui.update { it.copy(logError = null) }
            } else {
                _ui.update { it.copy(logError = result.message) }
            }
        } catch (_: Throwable) {
            // 轮询失败不打断界面，下一轮自愈
        }
    }

    fun refreshLogsNow() {
        viewModelScope.launch { refreshLogs() }
    }

    fun setLogAutoFollow(enabled: Boolean) {
        prefs.logAutoFollow = enabled
        _ui.update { it.copy(logAutoFollow = enabled) }
    }

    /** 手动刷新（下拉/按钮），会重新探测 su。 */
    fun forceRefresh() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            DshRuntime.invalidateSuProbe()
            DshRuntime.suAvailable(force = true)
            refresh()
        }
    }

    // ------------------------------------------------------------ 生命周期动作

    fun start() = runAction("已提交启动请求") { it.start() }

    fun stop() = runAction("已提交停止请求") { it.stop() }

    fun restart() = runAction("已提交重启请求") { it.restart() }

    /** 恢复出厂：清空可写层。破坏性操作，调用方必须先确认。 */
    fun resetEnvironment() = runAction("已清空可写层（恢复出厂），请重新启动环境") { it.reset() }

    private fun runAction(pending: String, block: suspend (LinuxCtl) -> CtlResult) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val app = getApplication<Application>()
            val ctl = LinuxCtl(app, _ui.value.mode)
            val result = try {
                withTimeoutOrNull(ACTION_TIMEOUT_MS) { block(ctl) }
                    ?: CtlResult.fail("操作超时（${ACTION_TIMEOUT_MS / 1000} 秒未返回），请查看日志区确认环境是否已启动")
            } catch (t: Throwable) {
                CtlResult.fail(t.message ?: "操作失败")
            }
            _ui.update {
                it.copy(
                    busy = false,
                    message = if (result.ok) pending else "操作失败：${result.message}",
                )
            }
            delay(600)
            refresh()
            refreshLogs()
        }
    }

    /** 首次进入时按设置自动启动（仅在「已部署 + 已停止」时动作）。 */
    fun autoStartIfNeeded() {
        if (autoStartTried || !prefs.autoStartEnv) return
        val snapshot = _ui.value
        if (snapshot.loading || snapshot.provisioned != true) return
        autoStartTried = true
        if (snapshot.state == EnvState.STOPPED) start()
    }

    // ------------------------------------------------------------ 诊断

    fun doctor() {
        if (_ui.value.doctorRunning) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            _ui.update {
                it.copy(doctorRunning = true, showDoctor = true, doctorOutput = "正在运行 linuxctl doctor…\n")
            }
            val ctl = LinuxCtl(app, _ui.value.mode)
            val result = try {
                withTimeoutOrNull(LinuxCtl.TIMEOUT_DOCTOR + 5_000) { ctl.doctor() }
                    ?: CtlResult.fail("doctor 超时")
            } catch (t: Throwable) {
                CtlResult.fail(t.message ?: "doctor 执行失败")
            }
            val text = buildString {
                append("$ ${ctl.activeCtlPath} doctor\n\n")
                if (result.stdout.isNotBlank()) append(result.stdout).append('\n')
                if (result.stderr.isNotBlank()) append("\n[stderr]\n").append(result.stderr).append('\n')
                append("\n结果：").append(if (result.ok) "自检通过" else result.message).append('\n')
            }
            _ui.update { it.copy(doctorRunning = false, doctorOutput = text) }
        }
    }

    fun closeDoctor() = _ui.update { it.copy(showDoctor = false) }

    fun showRawJson() = _ui.update {
        it.copy(rawJson = it.status?.raw?.ifBlank { "（无输出）" } ?: "（尚未读取状态）")
    }

    fun closeRawJson() = _ui.update { it.copy(rawJson = null) }

    // ------------------------------------------------------------ 排障包

    fun exportReport() {
        if (_ui.value.exporting) return
        viewModelScope.launch {
            _ui.update { it.copy(exporting = true) }
            val app = getApplication<Application>()
            val snapshot = _ui.value
            val file = try {
                LogExport.prune(app)
                LogExport.buildBundle(app, snapshot.mode, snapshot.stage)
            } catch (t: Throwable) {
                _ui.update { it.copy(exporting = false, message = "生成排障包失败：${t.message ?: t.javaClass.simpleName}") }
                return@launch
            }
            _ui.update { it.copy(exporting = false, message = "排障包已生成：${file.name}（含 status/日志/崩溃栈）") }
            runCatching { LogExport.share(app, file) }
        }
    }

    // ------------------------------------------------------------ 更新角标

    private fun maybeCheckUpdates(status: DshStatus) {
        val channels = prefs.channels
        if (channels.none { it.enabled }) {
            _ui.update { it.copy(updateChecked = true, updateCount = 0) }
            return
        }
        if (updateJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheckAt < UPDATE_CHECK_INTERVAL_MS) return
        lastUpdateCheckAt = now
        updateJob = viewModelScope.launch {
            val count = try {
                val reports = UpdateChecker.check(channels, status)
                UpdateChecker.merge(reports).count { it.key != prefs.ignoredUpdate }
            } catch (_: Throwable) {
                0
            }
            _ui.update { prev ->
                prev.copy(
                    updateChecked = true,
                    updateCount = count,
                    hints = Diagnoser.hints(
                        status = prev.status,
                        provisioned = prev.provisioned,
                        suAvailable = prev.suAvailable,
                        mode = prev.mode,
                        zstdUnavailableReason = prev.zstdReason,
                        updateCount = count,
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------ 杂项

    fun dismissMessage() = _ui.update { it.copy(message = null) }

    /** 引导页写入模式选择后，回到首页重新探测。 */
    fun onOnboardingChanged() {
        DshRuntime.invalidateSuProbe()
        autoStartTried = false
        forceRefresh()
    }

    override fun onCleared() {
        pollJob?.cancel()
        logJob?.cancel()
        updateJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val POLL_MS = 4_000L
        private const val LOG_POLL_MS = 5_000L
        private const val ACTION_TIMEOUT_MS = 200_000L
        private const val UPDATE_CHECK_INTERVAL_MS = 10 * 60 * 1000L
        const val NOT_PROVISIONED_HINT =
            "环境尚未部署：没有找到 linuxctl。请先执行部署向导（Root 模式需先装好 KernelSU 模块）。"
    }
}
