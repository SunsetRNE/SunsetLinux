package io.dshroid.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 环境路径常量。契约见 docs/architecture.md §2。
 *
 * App **不关心**两种模式的差异，只按 [EnvMode] 拼出 `linuxctl` 的路径；
 * 真正的差异（chroot / proot）都在 Linux 侧脚本里。
 */
object DshPaths {
    /** root 模式的环境根：真 root + chroot，非 App 私有目录（卸载 App 不丢数据）。 */
    const val ROOT_LINUX_HOME = "/data/linux"

    /** proot 模式的降级环境根：App 私有目录。 */
    fun prootLinuxHome(context: Context): String = File(context.filesDir, "linux").absolutePath

    fun linuxHome(context: Context, mode: EnvMode): String = when (mode) {
        EnvMode.ROOT -> ROOT_LINUX_HOME
        EnvMode.PROOT -> prootLinuxHome(context)
    }

    /** 契约路径（docs/architecture.md §2/§3）：`$LINUX_HOME/bin/linuxctl`。 */
    fun linuxctl(context: Context, mode: EnvMode): String = "${linuxHome(context, mode)}/bin/linuxctl"

    /**
     * 允许的实现侧变体。KernelSU 模块在 `post-fs-data.sh` 里会把契约路径做成
     * `linuxctl.sh` 的软链；proot 运行时包目前只铺了 `linuxctl.sh`。
     * App 以**契约路径为首选**，仅当它不存在时才退到变体 —— 既守住契约，
     * 又不会因为部署侧只装了 `.sh` 就让用户看到"环境尚未部署"。
     */
    fun linuxctlCandidates(context: Context, mode: EnvMode): List<String> {
        val home = linuxHome(context, mode)
        return listOf("$home/bin/linuxctl", "$home/bin/linuxctl.sh")
    }
}

/** POSIX 单引号转义：`it's` → `'it'\''s'`。所有进入 shell 的参数都必须过这一层。 */
internal fun shQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

/**
 * 与 Linux 侧交互的**唯一**入口（docs/architecture.md §3）。
 *
 * - root 模式：`su -c '/data/linux/bin/linuxctl status'`（优先 libsu，后备 ProcessBuilder）
 * - proot 模式：直接执行 `$APP_FILES/linux/bin/linuxctl status`
 *
 * 两种模式下 `linuxctl` 的相对路径、子命令与 JSON 输出完全一致，所以这里只有一处差异点。
 */
class LinuxCtl(private val context: Context, val mode: EnvMode) {

    val home: String = DshPaths.linuxHome(context, mode)
    /** 契约路径（对外展示与错误信息用）。实际执行路径见 [activeCtlPath]。 */
    val ctlPath: String = DshPaths.linuxctl(context, mode)

    private val candidates: List<String> = DshPaths.linuxctlCandidates(context, mode)

    /** 探测到的实际可用路径；默认即契约路径。 */
    @Volatile
    var activeCtlPath: String = ctlPath
        private set

    private val linuxPathEnv = "PATH=/system/bin:/system/xbin:/data/linux/bin:\$PATH"

    // ---------------------------------------------------------------- 探测

    /** linuxctl 是否存在/可执行。用于区分「未部署」与「出错」。 */
    suspend fun exists(): Boolean = withContext(Dispatchers.IO) {
        when (mode) {
            EnvMode.ROOT -> {
                // 一次 su 调用里把两个候选都试掉，避免两次 25 秒超时叠加
                val script = candidates.joinToString("; ") {
                    "if [ -x ${shQuote(it)} ]; then printf '%s\\n' ${shQuote(it)}; exit 0; fi"
                } + "; exit 1"
                val r = SuShell.exec(script, TIMEOUT_STATUS)
                r.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.let { activeCtlPath = it }
                r.exitCode == 0
            }

            EnvMode.PROOT -> candidates.firstOrNull { File(it).isFile }?.also { activeCtlPath = it } != null
        }
    }

    // ---------------------------------------------------------------- 命令

    /** 读状态。永远不抛异常：失败时返回 state=UNKNOWN + 可读 last_error。 */
    suspend fun status(): DshStatus {
        val r = execBlocking(listOf("status"), TIMEOUT_STATUS)
        if (r.error != null) return DshStatus.unavailable(r.error, r.stdout)
        if (r.exitCode != 0 && r.stdout.isBlank()) return DshStatus.unavailable(r.message, r.stdout)
        return DshStatus.parse(r.stdout)
    }

    suspend fun start(): CtlResult = execBlocking(listOf("start"), TIMEOUT_START_STOP)

    suspend fun stop(): CtlResult = execBlocking(listOf("stop"), TIMEOUT_START_STOP)

    /** 重启 = 幂等 stop + 幂等 start。 */
    suspend fun restart(): CtlResult {
        val stop = stop()
        if (!stop.ok) return stop
        delay(800)
        return start()
    }

    suspend fun doctor(): CtlResult = execBlocking(listOf("doctor"), TIMEOUT_DOCTOR)

    suspend fun logs(lines: Int = 400): CtlResult =
        execBlocking(listOf("logs", "-n", lines.coerceIn(1, 20000).toString()), TIMEOUT_LOGS)

    suspend fun provision(seedDir: String? = null): CtlResult {
        val args = buildList {
            add("provision")
            if (!seedDir.isNullOrBlank()) {
                add("--seed")
                add(seedDir)
            }
        }
        return execBlocking(args, TIMEOUT_PROVISION)
    }

    /**
     * 更新某一层。
     *
     * ⚠️ [version] **必须尽量传**：`linuxctl update` 靠 `--version` 决定落盘名
     * （`layers/<id>-<ver>.erofs`），而 `find_layer` 是"state.json 里的版本优先"——
     * 不传版本就只能落户无版本名的 `<id>.erofs`，**回滚语义会失效**。
     *
     * 当前真正的更新路径是 [io.dshroid.core.UpdateApplier]（它从频道清单取版本并传 `--version`），
     * 本方法保留为 API 完整性；任何新调用者都必须把清单里的 `version` 透传进来。
     */
    suspend fun update(layer: String, file: String, version: String? = null): CtlResult {
        val args = buildList {
            add("update")
            add(layer)
            add(file)
            version?.trim()?.ifEmpty { null }?.let {
                add("--version")
                add(it)
            }
        }
        return execBlocking(args, TIMEOUT_UPDATE)
    }

    suspend fun reset(): CtlResult = execBlocking(listOf("reset"), TIMEOUT_UPDATE)

    suspend fun snapshot(name: String): CtlResult =
        execBlocking(listOf("snapshot", name), TIMEOUT_UPDATE)

    /**
     * `linuxctl exec -- sh -c <script>` 并**把内容喂给它的 stdin**。
     *
     * 用于往环境内部写文件（例如 `/root/.npmrc`）：必须经 `exec` 进入**合并后的 rootfs**，
     * 直接写 `$LINUX_HOME/rootfs/...` 在 root 模式下会被 overlay 盖住，写到不生效的地方。
     * 走 stdin 而不是拼命令行，避免引号/长度问题。
     */
    suspend fun execInEnvWithStdin(
        cmd: List<String>,
        stdin: ByteArray,
        timeoutMs: Long = TIMEOUT_START_STOP,
    ): CtlResult = withContext(Dispatchers.IO) {
        val args = listOf("exec", "--") + cmd
        val result = when (mode) {
            EnvMode.ROOT -> Proc.execWithInput(listOf("su", "-c", shellCommand(args)), stdin, timeoutMs)
            EnvMode.PROOT -> Proc.execWithInput(prootCommand(args), stdin, timeoutMs)
        }
        result.normalize()
    }

    /** `linuxctl exec -- cmd...`（非交互，供 App 调环境内的命令）。 */
    suspend fun execInEnv(vararg cmd: String): CtlResult =
        execBlocking(listOf("exec", "--") + cmd.toList(), TIMEOUT_START_STOP)

    // ---------------------------------------------------------------- 流式

    /**
     * 流式执行（provision / update 用），逐行回调。
     *
     * **阻塞**，必须在 IO 线程调用；之所以不用 libsu，是因为 libsu 的流式 API
     * 对「同时拿到 stdout+stderr 的实时行」支持较弱，而这里要的就是实时进度。
     */
    fun stream(args: List<String>, onLine: (String) -> Unit): CtlResult {
        val cmd = when (mode) {
            EnvMode.ROOT -> listOf("su", "-c", shellCommand(args))
            EnvMode.PROOT -> prootCommand(args)
        }
        return Proc.stream(cmd, env = baseEnv(), cwd = workDir(), onLine = onLine)
    }

    // ---------------------------------------------------------------- 内部

    private suspend fun execBlocking(args: List<String>, timeoutMs: Long): CtlResult =
        withContext(Dispatchers.IO) {
            when (mode) {
                EnvMode.ROOT -> SuShell.exec(shellCommand(args), timeoutMs)
                EnvMode.PROOT -> Proc.exec(prootCommand(args), env = baseEnv(), cwd = workDir(), timeoutMs = timeoutMs)
            }
        }.normalize()

    /** `PATH=...; '/data/linux/bin/linuxctl' 'status'` */
    private fun shellCommand(args: List<String>): String = buildString {
        append(linuxPathEnv)
        append("; ")
        append(shQuote(activeCtlPath))
        args.forEach { append(' ').append(shQuote(it)) }
    }

    /**
     * proot 模式直接执行 linuxctl（契约原文：直接执行 `$APP_FILES/linux/bin/linuxctl status`）。
     * 万一脚本丢了执行位，退化为 `sh <path>`，避免用户看到 EACCES 却无从下手。
     */
    private fun prootCommand(args: List<String>): List<String> =
        if (File(activeCtlPath).canExecute()) listOf(activeCtlPath) + args
        else listOf("/system/bin/sh", activeCtlPath) + args

    private fun baseEnv(): Map<String, String> = mapOf(
        "PATH" to "/system/bin:/system/xbin:/data/linux/bin",
        // linuxctl 自己会解析环境根：优先 LINUX_HOME，其次 DSHROID_APP_FILES。
        // 两个都给上，避免部署侧调整解析顺序时 App 侧失效。
        "LINUX_HOME" to home,
        "DSHROID_APP_FILES" to context.filesDir.absolutePath,
        "HOME" to home,
    )

    private fun workDir(): File? = if (mode == EnvMode.PROOT) context.filesDir else null

    /** 把「命令能跑但退出码非 0」的常见原因翻译成中文，并保留原始 stderr。 */
    private fun CtlResult.normalize(): CtlResult {
        if (error != null) return this
        if (exitCode == 0) return this
        val friendly = when {
            // 退出码是主信号；文本匹配同时覆盖英文（toybox/mksh）与中文（部分 ROM 的 shell）
            exitCode == 127 || anyText(stderr, "not found", "No such file", "没有那个文件", "未找到", "无法访问") ->
                "找不到 linuxctl（$ctlPath）：环境可能尚未部署，请先执行部署向导。"
            exitCode == 126 || anyText(stderr, "Permission denied", "权限不够", "拒绝访问") ->
                "linuxctl 不可执行（缺少执行权限）：请重新执行部署向导。"
            exitCode == 2 -> "该操作已经完成过（退出码 2），无需重复执行。"
            else -> null
        } ?: return this
        return copy(error = friendly)
    }

    private fun anyText(haystack: String, vararg needles: String): Boolean =
        needles.any { haystack.contains(it, ignoreCase = true) }

    companion object {
        const val TIMEOUT_STATUS = 25_000L
        const val TIMEOUT_START_STOP = 180_000L
        const val TIMEOUT_DOCTOR = 120_000L
        const val TIMEOUT_LOGS = 20_000L
        const val TIMEOUT_PROVISION = 60 * 60 * 1000L
        const val TIMEOUT_UPDATE = 30 * 60 * 1000L
    }
}
