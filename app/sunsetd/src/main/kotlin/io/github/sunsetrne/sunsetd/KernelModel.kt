package io.github.sunsetrne.sunsetd

/**
 * 内核 v2 · 状态模型（**纯逻辑，可 JVM 单测，不碰任何 Android/系统 API**）。
 *
 * 设计依据：`docs/core-v2-design.md`。这里只放"内核必须唯一持有"的那部分事实：
 * **相位、世代、后端、会话身份**。所有客户端（App / `linuxctl` / WebUI / 模块）
 * 都只读由它生成的状态，不允许自己再推导一遍 —— 2026-09-18 一晚的六个事故里，
 * 第 2、3 条（端口没落盘、两棵挂载树）根子就是"同一件事被四处各自推导"。
 *
 * ## 为什么相位要这么细（每个都对应一个真机事故）
 *
 * | 相位 | 加它的理由 |
 * |---|---|
 * | [Phase.MOUNTING] / [Phase.STARTING] | 建树要 30~50 秒。以前这段时间状态被报成 `stopped` ⇒ App 的启动卡照旧可点 ⇒ 用户连点两下就**各建一棵挂载树** |
 * | [Phase.STOPPING] | 停止要等 supervisor + 逐项卸载（20~30 秒），期间不能让新的 start 进来 |
 * | [Phase.DEGRADED] | 允许"带着已知降级继续跑"（例如拿不到 rprivate 时的显式 `--unsafe-propagate`），但必须**说出来**，不能悄悄降级 |
 * | [Phase.FAILED] | 失败要带 `last_error` 与世代，供 doctor 断言不变量 |
 */
enum class Phase(val wire: String) {
    IDLE("idle"),
    PREPARING("preparing"),
    MOUNTING("mounting"),
    STARTING("starting"),
    RUNNING("running"),
    DEGRADED("degraded"),
    STOPPING("stopping"),
    FAILED("failed"),
    ;

    /** 正在"做一件事"：客户端此时不应该再提交同向作业（互斥内建在状态机里）。 */
    val isBusy: Boolean get() = this == PREPARING || this == MOUNTING || this == STARTING || this == STOPPING

    /** 环境可用（含带降级继续跑）。 */
    val isUp: Boolean get() = this == RUNNING || this == DEGRADED

    /**
     * 合法迁移表。**所有** 相位变化都要过这里；写错一条就在这里被单测挡住，
     * 而不是在真机上表现为"按钮亮着但点下去报错"。
     */
    fun canGoTo(next: Phase): Boolean = when (this) {
        IDLE -> next == PREPARING || next == FAILED

        // 准备阶段可以失败、可以被取消（回到 IDLE），也可以进入挂载
        PREPARING -> next == MOUNTING || next == IDLE || next == FAILED

        // 挂载中：成功 → 起进程；失败 → FAILED；被取消 → STOPPING（要把已经挂上的拆掉）
        MOUNTING -> next == STARTING || next == DEGRADED || next == STOPPING || next == FAILED

        STARTING -> next == RUNNING || next == DEGRADED || next == STOPPING || next == FAILED

        RUNNING -> next == STOPPING || next == DEGRADED || next == FAILED

        DEGRADED -> next == RUNNING || next == STOPPING || next == FAILED

        // 停止必须能收敛：正常回到 IDLE；拆不干净则是 FAILED（不许"假装停了"）
        STOPPING -> next == IDLE || next == FAILED

        // 失败后只能重新开始或原地被认领（stop 在 FAILED 上是幂等清理）
        FAILED -> next == PREPARING || next == STOPPING || next == IDLE
    }

    companion object {
        fun from(raw: String?): Phase? = entries.firstOrNull { it.wire == raw?.trim() }
    }
}

/**
 * 进入环境的方式。内核只认这个接口，两种模式是它的两个实现 ——
 * 这就是"root 不该长在 proot 那套脚本上"的落点（`docs/core-v2-design.md` §4）。
 */
enum class Backend(val wire: String) {
    CHROOT("chroot"),
    PROROOT("proroot"),
    PROOT("proot"),
    ;

    companion object {
        fun from(raw: String?): Backend? = entries.firstOrNull { it.wire == raw?.trim() }
    }
}

/**
 * 一个会话（= 一次成功 start 的产物）的全部内核事实。
 *
 * 不变量（`docs/core-v2-design.md` §3.6）：
 *  - **I1**：[generation] 单调递增，只有从"非运行态"进入 [Phase.MOUNTING] 时才 +1；
 *  - **I5**：`jobId != null` ⟺ 相位是 [Phase.isBusy]（一个时刻只有一个作业在跑）。
 */
data class SessionState(
    val generation: Long = 0,
    val phase: Phase = Phase.IDLE,
    /** 相位进入时间（epoch ms）。"卡住了"的判断靠它，不靠猜超时。 */
    val since: Long = 0,
    val backend: Backend = Backend.CHROOT,
    /** `full` / `env-only`；环境没起时为 null（**不编造**）。 */
    val envMode: String? = null,
    /** 生效端口（来自内核，不再由各处推导 —— 真机事故第 2 条的落地）。 */
    val port: Int? = null,
    /** 持有会话的进程（宿主侧守护进程/入口进程）pid。 */
    val pid: Long? = null,
    /** 当前作业 id（见 I5）。 */
    val jobId: String? = null,
    val lastError: String? = null,
) {
    /**
     * 走一步相位迁移。非法迁移**抛异常**（内核里不允许"悄悄走一步"）。
     *
     * @param now epoch ms，由调用方给（便于单测与时钟注入）
     */
    fun transition(
        next: Phase,
        now: Long,
        jobId: String? = this.jobId,
        lastError: String? = if (next == Phase.FAILED) this.lastError else null,
        envMode: String? = this.envMode,
        port: Int? = this.port,
        pid: Long? = this.pid,
    ): SessionState {
        require(phase.canGoTo(next)) { "非法相位迁移：${phase.wire} → ${next.wire}" }
        // I1：只有"非运行态 → MOUNTING"才算开新会话
        val nextGeneration = if (next == Phase.MOUNTING) generation + 1 else generation
        return copy(
            generation = nextGeneration,
            phase = next,
            since = now,
            jobId = jobId,
            lastError = lastError,
            envMode = envMode,
            port = port,
            pid = pid,
        )
    }

    init {
        // I5 在构造时就成立：busy ⟺ 有 jobId
        require(phase.isBusy == (jobId != null)) {
            "不变量 I5 被破坏：phase=${phase.wire} 与 jobId=${jobId ?: "null"} 不匹配"
        }
    }

    /** 环境没起来时，一切"会话事实"都必须为空 —— 不许留上次的残值（真机踩过：陈旧 url/port）。 */
    fun cleared(): SessionState = SessionState(
        generation = generation,
        phase = Phase.IDLE,
        since = since,
        backend = backend,
    )

    companion object {
        /** 从落盘 JSON 读回（字段缺失一律退化，绝不抛 —— 与 v1 解析口径一致）。 */
        fun fromJson(raw: String?): SessionState {
            if (raw.isNullOrBlank()) return SessionState()
            val m = Json.parseFlatObject(raw) ?: return SessionState()
            val phase = Phase.from(m["phase"]) ?: Phase.IDLE
            val jobId = m["jobId"]?.takeIf { it != "null" && it.isNotBlank() }
            return SessionState(
                generation = m["generation"]?.toLongOrNull() ?: 0L,
                // 脏数据（busy 却没 jobId）不能让内核起不来：按最保守的方式修正
                phase = if (phase.isBusy && jobId == null) Phase.FAILED else phase,
                since = m["since"]?.toLongOrNull() ?: 0L,
                backend = Backend.from(m["backend"]) ?: Backend.CHROOT,
                envMode = m["envMode"]?.takeIf { it != "null" },
                port = m["port"]?.toIntOrNull(),
                pid = m["pid"]?.toLongOrNull(),
                jobId = jobId,
                lastError = m["lastError"]?.takeIf { it != "null" },
            )
        }
    }

    /** 落盘形式（原子写由调用方负责：先写 .tmp 再 rename）。 */
    fun toJson(): String = Json.obj(
        "schema" to 1,
        "generation" to generation,
        "phase" to phase.wire,
        "since" to since,
        "busy" to phase.isBusy,
        "up" to phase.isUp,
        "backend" to backend.wire,
        "envMode" to envMode,
        "port" to port,
        "pid" to pid,
        "jobId" to jobId,
        "lastError" to lastError,
    )
}
