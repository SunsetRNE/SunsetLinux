package io.github.sunsetrne.sunsetd

/**
 * **sunsetd 内核**：唯一状态机 + 唯一状态源。
 *
 * 设计依据 `docs/core-v2-design.md`。P1 的范围（刻意收窄）：
 *
 *  - 内核**拥有**相位与世代，并把它写进 `run/state.json`（原子写）+ 心跳；
 *  - 内核**驱动**现有脚本（`linuxctl start/stop/dsh start|stop`）作为"作业"，
 *    从中派生相位 —— 不重写挂载/命名空间逻辑（那是后续阶段）；
 *  - 内核**认领**不是它起的会话（模块 `service.sh` 开机启动的那条）：
 *    观察到 `run/ready + supervisor.pid` 活着就报 `running`，owner=`foreign-observer`；
 *  - 互斥内建：同一时刻只允许一个作业（这正是 v1 里 `run/start.lock` 想解决的事，
 *    但那时它只是"启动锁"，现在它是状态机的一部分）。
 *
 * 明确**不**在 P1 做：把挂载表变成数据（P2/P3）、事件流（P2）、删掉 v1 判据（P1 收尾阶段）。
 */
class Kernel(
    private val env: RuntimeEnv,
    private val sink: StateSink,
    private val world: WorldView,
    private val jobs: JobRunner,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 一个作业：提交即返回，结果通过 [status] / `job` 查询拿到。 */
    data class Job(
        val id: String,
        val action: String,
        val startedAt: Long,
        var pid: Long? = null,
        var exitCode: Int? = null,
    ) {
        val finished: Boolean get() = exitCode != null
    }

    private var state = SessionState()
    private var job: Job? = null
    private var seq = 0L

    /** 最近一次"外部世界说的"失败原因（用于 FAILED 的 lastError）。 */
    private var lastWorldError: String? = null

    fun state(): SessionState = state

    fun currentJob(): Job? = job

    // ─────────────────────────────────────────────────────────── 启动

    /**
     * 内核自己的开机动作：读回上次的状态（如果有），并把世界现状合并进来。
     * **不主动起环境** —— 起环境是模块 `service.sh` 的事（v1 行为不变，D4 兼容）。
     */
    fun start() {
        val persisted = try {
            java.io.File(env.stateFile).takeIf { it.isFile }?.readText()?.let { SessionState.fromJson(it) }
        } catch (_: Throwable) {
            null
        }
        // 落盘的状态可能来自"上一次内核进程"，而设备重启后 run/ 里可能已经没有会话了：
        // 以**世界**为准（环境不在了就不能继续报 running —— 这是 v1 里最常见的谎报）。
        val w = world.snapshot()
        state = when {
            w.envUp -> adopted(w, persisted?.generation ?: 0L)
            persisted != null && persisted.phase.isBusy -> SessionState(
                generation = persisted.generation,
                phase = Phase.FAILED,
                since = clock(),
                backend = persisted.backend,
                lastError = "内核重启时发现上次的会话没有结束（phase=${persisted.phase.wire}）",
            )
            else -> SessionState(generation = persisted?.generation ?: 0L, since = clock())
        }
        sink.log("[sunsetd] 启动：${state.toJson()}")
        publish()
    }

    // ─────────────────────────────────────────────────────────── 作业

    /**
     * 提交一个动作。**幂等**：同一个动作在做就返回同一个作业；不同动作在做则拒绝（互斥）。
     *
     * @return (jobId, deduped) 或抛 [BusyException] 表示与在跑的作业冲突
     */
    fun submit(action: String): Pair<String, Boolean> {
        val running = job?.takeIf { !it.finished }
        if (running != null) {
            if (running.action == action) return running.id to true
            throw BusyException(running.id, running.action)
        }
        val now = clock()
        val cmd = commandFor(action) ?: throw UnknownActionException(action)
        seq += 1
        val j = Job(id = "j$seq", action = action, startedAt = now)
        job = j
        // 只有"改变环境本身"的作业才推环境相位。子作业（dsh start/stop）**不动环境相位** ——
        // 环境仍然是 running，那个作业通过 status.job 单独可见（否则"起 DSH"会把环境说成"正在启动"）。
        if (affectsEnvPhase(action)) {
            // 停止一提交就进 stopping（它本来就是"正在收摊"）；启动类先 preparing。
            val first = if (action == ACTION_STOP) Phase.STOPPING else Phase.PREPARING
            state = state.transition(first, now, jobId = j.id)
        }
        publish()
        val pid = try {
            jobs.spawn(cmd) { code -> onJobExit(j, code) }
        } catch (t: Throwable) {
            onJobExit(j, -1, t.message ?: t.javaClass.simpleName)
            throw UnknownActionException("$action（起进程失败：${t.message}）")
        }
        j.pid = pid
        sink.log("[sunsetd] 作业 ${j.id}（$action）已起：pid=$pid cmd=${cmd.joinToString(" ")}")
        return j.id to false
    }

    private fun commandFor(action: String): List<String>? = when (action) {
        ACTION_START -> listOf("/system/bin/sh", env.linuxctl, "start")
        ACTION_START_ENV_ONLY -> listOf("/system/bin/sh", env.linuxctl, "start", "--no-dsh")
        ACTION_STOP -> listOf("/system/bin/sh", env.linuxctl, "stop")
        ACTION_DSH_START -> listOf("/system/bin/sh", env.linuxctl, "dsh", "start")
        ACTION_DSH_STOP -> listOf("/system/bin/sh", env.linuxctl, "dsh", "stop")
        else -> null
    }

    private fun onJobExit(j: Job, code: Int, error: String? = null) {
        if (job?.id != j.id) return
        j.exitCode = code
        val now = clock()
        sink.log("[sunsetd] 作业 ${j.id}（${j.action}）结束：rc=$code ${error ?: ""}")
        lastWorldError = error ?: world.snapshot().lastError
        // 相位不在这里直接判定：交给 tick() 以**世界**为准（脚本 rc=0 不等于环境真的在跑）
        tick()
    }

    // ─────────────────────────────────────────────────────────── 看护（tick）

    /**
     * 推进一步。由主循环每秒调用一次 —— 状态**只**在这里被写出去。
     *
     * 判据全部来自 [WorldView] 的快照（v1 标记），因此"内核说的"与"设备上真的"永远一致；
     * 靠脚本退出码猜状态的写法（v1 的老毛病）在这里被彻底去掉。
     */
    fun tick() {
        val now = clock()
        val w = world.snapshot()
        val running = job?.takeIf { !it.finished }
        val finished = job?.takeIf { it.finished }

        val next: SessionState = when {
            // ── 有作业在跑：按世界的进度推进相位
            running != null && affectsEnvPhase(running.action) -> {
                val phase = phaseFor(running, w)
                // 相位没变就不是"迁移"（迁移表只描述真正的变化）—— 只把世界带回的字段补齐
                if (phase == state.phase) {
                    state.copy(
                        envMode = w.envMode ?: state.envMode,
                        port = w.dshPort ?: state.port,
                        pid = w.supervisorPid ?: state.pid,
                    )
                } else {
                    state.transition(
                        phase,
                        now,
                        jobId = running.id,
                        envMode = w.envMode ?: state.envMode,
                        port = w.dshPort ?: state.port,
                        pid = w.supervisorPid ?: state.pid,
                    )
                }
            }

            // ── 作业刚结束（或没有作业）：以世界为准
            else -> {
                val justFailed = if (finished != null) clearJobIfSettled(finished, w, now) else false
                // FAILED 是**粘的**：一次失败的启动不许自己悄悄回到 idle（用户会以为"什么都没发生"）。
                // 只有新的作业、或世界明确说"环境在跑"（别人起起来了）才能离开 FAILED。
                if (justFailed || (state.phase == Phase.FAILED && finished == null && !w.envUp)) {
                    state
                } else {
                    val want = phaseFromWorld(w)
                    if (want != null && want.first != state.phase) {
                        state.transition(
                            want.first,
                            now,
                            jobId = null,
                            owner = want.second,
                            envMode = w.envMode,
                            port = w.dshPort,
                            pid = w.supervisorPid,
                            lastError = if (want.first == Phase.FAILED) (lastWorldError ?: w.lastError) else null,
                        )
                    } else {
                        // 世界说不清（既没就绪也没在启动）：把失败原因落成 FAILED，而不是留在忙相位里
                        val err = lastWorldError ?: w.lastError
                        if (want == null && err != null && state.phase != Phase.FAILED) {
                            state = SessionState(
                                generation = state.generation,
                                phase = Phase.FAILED,
                                since = now,
                                backend = state.backend,
                                lastError = err,
                            )
                        }
                        state
                    }
                }
            }
        }
        if (next != state) state = next
        publish()
    }

    /** 作业在跑时的相位：只看世界（`ready` 出现 = 环境真的起来了；启动锁在 = 还在建树）。 */
    private fun phaseFor(j: Job, w: World): Phase = when {
        j.action == ACTION_STOP -> Phase.STOPPING
        w.envUp -> Phase.RUNNING
        w.starting -> if (j.action == ACTION_START || j.action == ACTION_START_ENV_ONLY) Phase.MOUNTING else Phase.STARTING
        else -> Phase.PREPARING
    }

    /** 作业结束后是否已"落定"（世界与期望一致 → 收掉作业，相位交回世界）。@return 是否刚判定失败 */
    private fun clearJobIfSettled(j: Job, w: World, now: Long): Boolean {
        val ok = when (j.action) {
            ACTION_START, ACTION_START_ENV_ONLY -> w.envUp
            ACTION_STOP -> !w.envUp && !w.starting
            ACTION_DSH_START -> w.dshRunning
            ACTION_DSH_STOP -> !w.dshRunning
            else -> true
        }
        var failed = false
        if (ok || (j.exitCode != null && j.exitCode != 0)) {
            job = null
            if (!ok && j.exitCode != null && j.exitCode != 0) {
                failed = true
                // 失败：把原因钉在 FAILED 上，别让它悄悄回到 IDLE（用户会以为"什么都没发生"）
                state = SessionState(
                    generation = state.generation,
                    phase = Phase.FAILED,
                    since = now,
                    backend = state.backend,
                    lastError = lastWorldError ?: w.lastError ?: "作业 ${j.action} 失败（rc=${j.exitCode}）",
                )
            }
        }
        return failed
    }

    /** 没有作业时，世界告诉我们什么相位（null = 说不清，保持不动）。 */
    private fun phaseFromWorld(w: World): Pair<Phase, Owner>? = when {
        w.envUp -> {
            val degraded = state.phase == Phase.DEGRADED && w.envMode == state.envMode
            (if (degraded) Phase.DEGRADED else Phase.RUNNING) to Owner.FOREIGN
        }
        w.starting -> Phase.MOUNTING to Owner.FOREIGN
        !w.envUp && !w.starting -> Phase.IDLE to Owner.NONE
        else -> null
    }

    /** 认领一个不是内核起的会话（模块 `service.sh` 开机那条）。 */
    private fun adopted(w: World, generation: Long) = SessionState(
        generation = maxOf(generation, 1L),
        phase = Phase.RUNNING,
        since = clock(),
        envMode = w.envMode,
        port = w.dshPort,
        pid = w.supervisorPid,
        owner = Owner.FOREIGN,
    )

    // ─────────────────────────────────────────────────────────── 输出

    /** 状态 + 心跳落盘。**唯一**的写出点（客户端读到的永远是同一份）。 */
    fun publish() {
        sink.writeState(state.toJson())
        sink.writeHeartbeat(clock())
    }

    /** `status` 响应：既给结构化字段，也给 v1 客户端要的那几个键。 */
    fun statusJson(): String = Json.obj(
        "state" to Json.Raw(state.toJson()),
        "job" to Json.Raw(
            job?.let {
                Json.obj(
                    "id" to it.id, "action" to it.action, "pid" to it.pid,
                    "exitCode" to it.exitCode, "startedAt" to it.startedAt, "finished" to it.finished,
                )
            } ?: "null",
        ),
        "protocol" to PROTOCOL,
    )

    // ─────────────────────────────────────────────────────────── 协议

    /** 处理一条控制帧，返回一行 JSON 响应。 */
    fun handle(line: String): String {
        val req = Protocol.parse(line)
            ?: return Protocol.error("bad-request", "看不懂这一行（要是 JSON 对象）")
        return try {
            when (req.op) {
                "ping" -> Protocol.ok(Json.obj("pong" to true))
                "version" -> Protocol.ok(
                    Json.obj(
                        "protocol" to PROTOCOL,
                        "caps" to Json.arr(listOf("status", "submit", "job", "heartbeat")),
                        "kernel" to VERSION,
                    ),
                )
                "status" -> Protocol.ok(statusJson())
                "job" -> Protocol.ok(
                    Json.obj(
                        "job" to Json.Raw(
                            job?.let {
                                Json.obj(
                                    "id" to it.id, "action" to it.action, "pid" to it.pid,
                                    "exitCode" to it.exitCode, "finished" to it.finished,
                                )
                            } ?: "null",
                        ),
                    ),
                )
                "submit" -> {
                    val action = req.params["action"] ?: return Protocol.error("bad-request", "submit 缺 action")
                    val (id, deduped) = submit(action)
                    Protocol.ok(Json.obj("jobId" to id, "deduped" to deduped))
                }
                else -> Protocol.error("unknown-op", "不认识的操作：${req.op}")
            }
        } catch (e: BusyException) {
            Protocol.error("busy", "有作业在跑（${e.jobId} ${e.action}）；同一个动作幂等，其它动作要等它结束")
        } catch (e: UnknownActionException) {
            Protocol.error("bad-action", e.message ?: "不认识的 action")
        } catch (t: Throwable) {
            Protocol.error("internal", t.message ?: t.javaClass.simpleName)
        }
    }

    private fun affectsEnvPhase(action: String): Boolean =
        action == ACTION_START || action == ACTION_START_ENV_ONLY || action == ACTION_STOP

    companion object {
        const val PROTOCOL = 1
        const val VERSION = "p1"
        const val ACTION_START = "start"
        const val ACTION_START_ENV_ONLY = "start-env-only"
        const val ACTION_STOP = "stop"
        const val ACTION_DSH_START = "dsh-start"
        const val ACTION_DSH_STOP = "dsh-stop"
    }
}

class BusyException(val jobId: String, val action: String) : RuntimeException("busy: $jobId $action")

class UnknownActionException(message: String) : RuntimeException(message)

/** 控制帧（行分隔 JSON）。P1 只有"请求 → 响应"，事件流在 P2。 */
object Protocol {
    data class Request(val op: String, val params: Map<String, String>)

    fun parse(line: String): Request? {
        val m = Json.parseFlatObject(line.trim()) ?: return null
        val op = m["op"]?.takeIf { it.isNotBlank() } ?: return null
        // `action` 这类参数是平铺字符串；嵌套参数（P2 才有）先按原文本带回
        val params = m.filterKeys { it != "op" }
        return Request(op = op, params = params)
    }

    fun ok(payloadJson: String): String = "{\"ok\":true,$payloadJson}"
    fun error(code: String, message: String): String =
        Json.obj("ok" to false, "code" to code, "message" to message)

    /** 一条帧 = 一行（JSON 里不能出现裸换行 —— Json.escape 会处理）。 */
    fun line(json: String): String = json + "\n"
}
