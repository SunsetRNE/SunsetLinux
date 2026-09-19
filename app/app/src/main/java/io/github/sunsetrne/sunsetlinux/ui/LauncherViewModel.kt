package io.github.sunsetrne.sunsetlinux.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sunsetrne.sunsetlinux.core.ActionTarget
import io.github.sunsetrne.sunsetlinux.core.CtlResult
import io.github.sunsetrne.sunsetlinux.core.Diagnoser
import io.github.sunsetrne.sunsetlinux.core.DshRuntime
import io.github.sunsetrne.sunsetlinux.core.DshStatus
import io.github.sunsetrne.sunsetlinux.core.Edition
import io.github.sunsetrne.sunsetlinux.core.EnvMode
import io.github.sunsetrne.sunsetlinux.core.EnvState
import io.github.sunsetrne.sunsetlinux.core.Hint
import io.github.sunsetrne.sunsetlinux.core.LinuxCtl
import io.github.sunsetrne.sunsetlinux.core.LogExport
import io.github.sunsetrne.sunsetlinux.core.Prefs
import io.github.sunsetrne.sunsetlinux.core.StartControls
import io.github.sunsetrne.sunsetlinux.core.StartMode
import io.github.sunsetrne.sunsetlinux.core.TransportSupport
import io.github.sunsetrne.sunsetlinux.core.UpdateChecker
import io.github.sunsetrne.sunsetlinux.core.isReadFailure
import io.github.sunsetrne.sunsetlinux.core.keepLastGoodStatus
import io.github.sunsetrne.sunsetlinux.core.channelsAllFailed
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
        /**
         * 当前动作**要设备达成的状态**（[ActionTarget]）。`busy == true` 时有意义：
         * 状态轮询一旦观测到它已达成，就立刻解锁启动区 —— 这条兜底路径存在的理由
         * （一条永不返回的 `linuxctl start` 曾把整个启动区锁死）见 `core/ActionTarget.kt`。
         */
        val actionTarget: ActionTarget = ActionTarget.NONE,
        /** 一次性提示 */
        val message: String? = null,
        val updateCount: Int = 0,
        val updateChecked: Boolean = false,
        /**
         * 上一次频道检查**没拿到任何可用清单**（全部频道都失败）。
         *
         * 为什么单列：`merge()` 只收 OK 的频道，"都失败"与"都没更新"的合并结果都是空表
         * ⇒ 首页磁贴会写成「已是最新」。真机（2026-09-19）就是这样：频道被
         * `REJECTED：签名校验失败`，磁贴却写着"已是最新"。
         */
        val updateFailed: Boolean = false,
        /**
         * 连续多少轮"没读到状态、于是沿用了上一次的好值"。
         *
         * 为什么要有：读失败立刻清空状态会让界面闪（真机实测）；但一直不清又会显示过期值。
         * 折中：沿用最多 [MAX_STALE_TICKS] 轮，超了就如实显示失败。
         */
        val staleTicks: Int = 0,
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
        /**
         * 用户持久化的启动方式偏好（一键启动 / 分步启动）。
         *
         * 这里只是"用户选的那一档"；**界面实际显示哪一组**由纯函数
         * [io.github.sunsetrne.sunsetlinux.core.resolveStartMode] 结合 status 的
         * `env_mode` 算出来（运行中会被强制锁定，理由见 `core/StartModeUi.kt`）。
         */
        val startMode: StartMode = StartMode.ONE_SHOT,
    ) {
        val state: EnvState get() = status?.state ?: EnvState.UNKNOWN
        val canOpenWeb: Boolean get() = status?.canOpenWeb == true

        /**
         * 启动区五个按钮的启用矩阵（纯函数 [StartControls.forStatus]，单测穷举在
         * `StartControlsTest`）。放在这里而不是面板里：面板只画，判定不许有第二份。
         *
         * `provisioned == false` 才是"明确没部署"；null（还没探测出来）不算 ——
         * 沿用原界面的口径，免得冷启动首帧把按钮全灰掉。
         */
        val controls: StartControls
            get() = StartControls.forStatus(
                notProvisioned = provisioned == false,
                envRunning = status?.envRunning == true,
                envMode = status?.envMode,
                dshRunning = status?.dshRunning == true,
            )
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
        _ui.update { it.copy(logAutoFollow = prefs.logAutoFollow, startMode = prefs.startMode) }
        // zstd 能力探测（磁盘 IO）放到 IO 线程，别占冷启动首帧
        viewModelScope.launch {
            val reason = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                TransportSupport.zstdUnavailableReason()
            }
            _ui.update { it.copy(zstdReason = reason) }
        }
        // root / 模块状态：一次探测、结果进 UiState（"关于"页与首页都用它）
        //
        // ★ 免 root 版**什么都不探**（docs/module-variants.md §一："不探测、不提示、不内嵌"）：
        //   · `DeviceStatus.root` 会真的起一个 `su` 进程 —— 免 root 版上这既没意义，
        //     又可能在部分 ROM 上弹出一个授权框（用户会以为装错了 App）；
        //   · `DeviceStatus.module` 走 su 读 /data/adb —— 免 root 版上必然失败，
        //     却要白等一次超时，读到的"模块读不到"还会写进关于页。
        //   所以这两条探测在免 root 版**根本不发起**，而不是"发起了再隐藏结果"。
        viewModelScope.launch {
            val io2 = kotlinx.coroutines.Dispatchers.IO
            val root = if (Edition.needsSu) {
                kotlinx.coroutines.withContext(io2) {
                    io.github.sunsetrne.sunsetlinux.core.DeviceStatus.root(force = true)
                }
            } else {
                // 免 root 版：su 是"不需要"，不是"探测失败"——如实写一句话，别留空白
                io.github.sunsetrne.sunsetlinux.core.RootProbe(
                    io.github.sunsetrne.sunsetlinux.core.RootState.UNKNOWN,
                    "免 root 版不探测 su（也不需要）",
                )
            }
            val module = if (Edition.showsModuleUi) {
                kotlinx.coroutines.withContext(io2) {
                    io.github.sunsetrne.sunsetlinux.core.DeviceStatus.module(force = true)
                }
            } else {
                null
            }
            _ui.update {
                it.copy(
                    rootLabel = root.label,
                    rootHint = root.hint,
                    moduleLabel = module?.label,
                    moduleHint = module?.hint,
                    moduleReadable = module?.readable == true,
                    moduleVersion = module?.version,
                )
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
            // ★ 轮询里的 su 探测也必须按 edition 短路：`DshRuntime.suAvailable()` 会起一个
            //   `su` 进程（缓存），在免 root 版上每轮白跑一次、还可能弹出授权框 ——
            //   "免 root 版不探测 su"是 docs/module-variants.md §一 的明确要求。
            //   免 root 版固定 false（它本来就没有 su，也不需要）。
            val su = if (Edition.needsSu) DshRuntime.suAvailable() else false

            // ★ 2026-09-19：**一次 su** 同时回答"有没有部署"与"什么状态"。
            //   原来 exists() + status() 是两次 su，加上日志轮询与 su 探测，5 秒内 4~5 次
            //   su 往返 —— 真机上就是"壳读得慢、还闪"的一半原因。
            val probed = ctl.statusOrNull()
            val exists = probed != null
            val fresh = probed ?: DshStatus.unavailable(NOT_PROVISIONED_HINT)

            _ui.update { prev ->
                // ★ 读失败**不许丢掉上一次的好状态**（另一半"闪"的原因：任何一次异常都会把
                //   status 覆盖成 unavailable，界面立刻从"运行中"翻成"读取状态失败"，下一轮
                //   又变回来）。连续 MAX_STALE_TICKS 轮都没读成才认账，避免永远显示过期值。
                val keepLast = keepLastGoodStatus(prev.status, fresh, prev.staleTicks, MAX_STALE_TICKS)
                val status = if (keepLast) prev.status!! else fresh
                val staleTicks = if (keepLast) prev.staleTicks + 1 else 0
                val mode = status.modeEnum ?: choice.mode
                val stage = Diagnoser.stage(status, exists, su, mode)
                // ★ 兜底解锁：命令还在跑（甚至卡住），但**设备状态已经到位** ⇒ 立刻解锁启动区。
                //   理由与设备实证见 core/ActionTarget.kt 的注释（一条永不返回的
                //   `linuxctl start` 曾让五张卡片永久置灰、「启动 DSH」点不动）。
                //   解锁后按钮矩阵会按"设备现在真实的状态"重算，所以不会放出不该点的按钮。
                val reachedTarget = prev.busy && prev.actionTarget.reached(status)
                prev.copy(
                    loading = false,
                    staleTicks = staleTicks,
                    mode = mode,
                    modeNote = choice.note,
                    suAvailable = su,
                    provisioned = exists,
                    status = status,
                    busy = prev.busy && !reachedTarget,
                    actionTarget = if (reachedTarget) ActionTarget.NONE else prev.actionTarget,
                    stage = stage,
                    hints = Diagnoser.hints(
                        status = status,
                        provisioned = exists,
                        suAvailable = su,
                        mode = mode,
                        zstdUnavailableReason = prev.zstdReason,
                        updateCount = prev.updateCount,
                        updateFailed = prev.updateFailed,
                    ),
                    lastSyncedAt = System.currentTimeMillis(),
                )
            }
            maybeCheckUpdates(_ui.value.status ?: fresh)
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

    /**
     * 记住用户选的启动方式（一键 / 分步）。
     *
     * **不做锁定判断**：运行中"切换不可点"由界面按 [io.github.sunsetrne.sunsetlinux.core.isStartModeLocked]
     * 置灰（那是纯函数、有单测）。这里再判一次就等于第二份判定 —— 只在调用点判，口径唯一。
     */
    fun setStartMode(mode: StartMode) {
        prefs.startMode = mode
        _ui.update { it.copy(startMode = mode) }
    }

    /** 手动刷新（下拉/按钮），会重新探测 su（免 root 版不探，见 [refresh]）。 */
    fun forceRefresh() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            if (Edition.needsSu) {
                DshRuntime.invalidateSuProbe()
                DshRuntime.suAvailable(force = true)
            }
            refresh()
        }
    }

    // ------------------------------------------------------------ 生命周期动作

    fun start() = runAction("已提交启动请求", ActionTarget.ENV_RUNNING) { it.start() }

    fun stop() = runAction("已提交停止请求", ActionTarget.ENV_STOPPED) { it.stop() }

    fun restart() = runAction("已提交重启请求", ActionTarget.ENV_RUNNING) { it.restart() }

    // ── 拆开的启动路径（仅 Root 版）
    //
    // 三个动作各自先过 edition 判断：按钮只在 Root 版渲染，但动作是 public 的，
    // 漏一处判断（例如以后有人从终端页直接调）就会在免 root 版上发出一条根本
    // 不存在的命令。判断放在动作入口，比依赖"调用点记得看 Edition"可靠。

    /** 只起环境、不起 DSH；起来之后终端就能用，DSH 由「启动 DSH」单独接。 */
    fun startEnvOnly() {
        if (!Edition.showsSplitStartUi) return
        runAction("已提交「仅启动环境」请求（未启动 DSH）", ActionTarget.ENV_RUNNING) { it.startEnvOnly() }
    }

    /** 单独启动 DSH（环境必须已在运行）。 */
    fun dshStart() {
        if (!Edition.showsSplitStartUi) return
        runAction("已提交「启动 DSH」请求", ActionTarget.DSH_RUNNING) { it.dshStart() }
    }

    /**
     * 单独停止 DSH（环境继续运行）。
     *
     * full 模式下模块会拒绝这条命令，原因来自 status 的 `last_error`；这里不做
     * 二次判断 —— 界面已经按 [StartControls] 置灰，能走到这里说明是 env-only。
     */
    fun dshStop() {
        if (!Edition.showsSplitStartUi) return
        runAction("已提交「停止 DSH」请求（环境继续运行）", ActionTarget.DSH_STOPPED) { it.dshStop() }
    }

    /** 恢复出厂：清空可写层。破坏性操作，调用方必须先确认。 */
    fun resetEnvironment() =
        runAction("已清空可写层（恢复出厂），请重新启动环境", ActionTarget.NONE) { it.reset() }

    /**
     * 跑一条生命周期命令。
     *
     * ## `busy` 的两条退出路径（**都必须有**，真机事故见 [ActionTarget] 的注释）
     *
     * 1. **命令返回**（正常路径）：`finally` 里无条件复位 —— 以前只在成功/失败分支复位，
     *    一旦命令抛异常或协程被取消，`busy` 就永久为真，整个启动区跟着死掉；
     * 2. **状态到位**（兜底路径）：状态轮询（4 秒一次）在 [refresh] 里看到设备已经达到
     *    [target] 就立刻解锁。这样"一条慢命令/卡命令"不再能把界面锁死 —— 真机上那条
     *    `linuxctl start` 永不返回时，用户看到的是"五张卡片全灰、点「启动 DSH」没反应"。
     *
     * 提前解锁是安全的：解锁后按矩阵重新算出来的按钮，恰好是"设备现在真实状态"下该亮的
     * 那几个（[StartControls] 是纯函数，判定唯一）；而**设备侧**对重复/混用的 start
     * 同样会拒绝（退出码 1 + 中文 last_error），不会因为提前解锁就把环境搞坏。
     */
    private fun runAction(pending: String, target: ActionTarget, block: suspend (LinuxCtl) -> CtlResult) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, actionTarget = target) }
            val app = getApplication<Application>()
            val ctl = LinuxCtl(app, _ui.value.mode)
            val result = try {
                withTimeoutOrNull(ACTION_TIMEOUT_MS) { block(ctl) }
                    ?: CtlResult.fail("操作超时（${ACTION_TIMEOUT_MS / 1000} 秒未返回），请查看日志区确认环境是否已启动")
            } catch (t: Throwable) {
                CtlResult.fail(t.message ?: "操作失败")
            } finally {
                // ★ 无条件复位：成功、失败、超时、抛异常、协程取消，一个都不能漏
                _ui.update { it.copy(busy = false, actionTarget = ActionTarget.NONE) }
            }
            _ui.update {
                it.copy(message = if (result.ok) pending else "操作失败：${result.message}")
            }
            delay(600)
            refresh()
            refreshLogs()
        }
    }

    /**
     * 进入 App 时保证"该跑的在跑"（仅在「已部署」时动作）。
     *
     * 决策变更（2026-09-19）后这里有两级语义：
     *   · 环境没跑 → `start()`（把环境拉起来）；
     *   · 环境在跑但 **DSH 没起** → `dshStart()` —— "进一步的启动只是启动 DSH"。
     * 环境与 DSH 的生命周期从此各管各的：环境是默认常驻，DSH 按需补齐。
     */
    fun autoStartIfNeeded() {
        if (autoStartTried || !prefs.autoStartEnv) return
        val snapshot = _ui.value
        if (snapshot.loading || snapshot.provisioned != true) return
        autoStartTried = true
        when {
            snapshot.state == EnvState.STOPPED -> start()
            // 环境已在跑、DSH 没起：补 DSH（免 root 版尤其常见：环境随上次 App 存活）
            snapshot.state == EnvState.RUNNING && snapshot.status?.dshRunning != true -> dshStart()
        }
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
            // 没有启用中的频道 = 没得查（不是"已是最新"，也不是"检查失败"）
            _ui.update { it.copy(updateChecked = true, updateCount = 0, updateFailed = false) }
            return
        }
        if (updateJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheckAt < UPDATE_CHECK_INTERVAL_MS) return
        lastUpdateCheckAt = now
        updateJob = viewModelScope.launch {
            var failed = false
            val count = try {
                val reports = UpdateChecker.check(channels, status)
                failed = channelsAllFailed(reports)
                UpdateChecker.merge(reports).count { it.key != prefs.ignoredUpdate }
            } catch (_: Throwable) {
                // 连检查本身都没跑成（网络/解析异常）—— 同样不许说"已是最新"
                failed = true
                0
            }
            _ui.update { prev ->
                prev.copy(
                    updateChecked = true,
                    updateCount = count,
                    updateFailed = failed,
                    hints = Diagnoser.hints(
                        status = prev.status,
                        provisioned = prev.provisioned,
                        suAvailable = prev.suAvailable,
                        mode = prev.mode,
                        zstdUnavailableReason = prev.zstdReason,
                        updateCount = count,
                        updateFailed = failed,
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
        // ★ 2026-09-19：4s/5s → 8s/12s。每一轮都是**一次 su 往返**（真机上很贵），
        //   而状态变化只发生在用户操作或环境自己重启时——操作后都会立刻 refresh()，
        //   所以拉长间隔只省开销，不牺牲"操作后马上看到结果"。
        private const val POLL_MS = 8_000L
        private const val LOG_POLL_MS = 12_000L
        /** 读失败时最多沿用几轮上一次的好状态（见 UiState.staleTicks）。 */
        private const val MAX_STALE_TICKS = 3
        private const val ACTION_TIMEOUT_MS = 200_000L
        private const val UPDATE_CHECK_INTERVAL_MS = 10 * 60 * 1000L

        /**
         * "没找到 linuxctl"时首页那句指引。
         *
         * ★ 必须按 edition 分岔：这句话直接出现在首屏，而"Root 模式需先装好 KernelSU 模块"
         *   对免 root 用户是一条**走不通的路**（他的机器上没有 KernelSU），
         *   免 root 版该给的下一步是"打开时会自动铺 / 去侧边栏手动铺"。
         */
        val NOT_PROVISIONED_HINT: String =
            if (Edition.needsKernelSuModule) {
                "环境尚未部署：没有找到 linuxctl。请先执行部署向导（Root 模式需先装好 KernelSU 模块）。"
            } else {
                "环境尚未部署：没有找到 linuxctl。免 root 版正常会在打开时自动铺好；" +
                    "也可以从侧边栏「重新部署 / 首启引导」手动铺。"
            }
    }
}
