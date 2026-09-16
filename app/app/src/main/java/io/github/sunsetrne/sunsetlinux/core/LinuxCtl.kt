package io.github.sunsetrne.sunsetlinux.core

import android.content.Context
import io.github.sunsetrne.sunsetlinux.BuildConfig
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
    const val ROOT_LINUX_HOME = "/data/sunsetlinux"

    /**
     * proot 模式的降级环境根：App 私有目录。
     *
     * 目录名与 root 模式**同名**（`sunsetlinux`），见 docs/naming.md 第 7 条映射：
     * `runtime/proot/` 下脚本的默认值就是 `$SUNSETLINUX_APP_FILES/sunsetlinux`，
     * 两边必须一致 —— 否则 App 找 `files/linux/bin/linuxctl`，而脚本按
     * `files/sunsetlinux` 解析环境根，用户会看到"环境尚未部署"却查不出原因。
     *
     * ⚠️ 写注释时别让斜杠紧跟星号（Kotlin 块注释**可嵌套**）：那会开一个永不闭合的
     * 内层注释，整个文件报 "Unclosed comment" —— 这条注释本身就是踩过之后补的。
     */
    fun prootLinuxHome(context: Context): String = File(context.filesDir, "sunsetlinux").absolutePath

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

    /**
     * **设备侧原生构建脚本**（`device-provision.sh`）的候选位置。
     *
     * 为什么是它、而不是 `linuxctl provision`：后者只建目录 / `upper.img` / 配置，
     * **从不构建层**。真正"在设备上把三层 erofs 造出来"的只有这个脚本
     * （真 chroot 里跑 apt + npm）—— 所以 App 的部署向导必须会调它，否则在
     * 没有预置层的机器上向导必然以"provision 失败"收场（真机实测，见 docs/HANDOFF.md §三·0）。
     *
     * 模块铺的那份优先：模块升级会同步它；`$LINUX_HOME/bin/` 下那份是部署时被
     * 脚本自己拷过去的，作为模块目录不可读时的后备。
     *
     * ⚠️ App 进程**无权 stat `/data/adb/...`**，所以这里只给候选，真正的存在性判断
     * 必须在 `su` 里做（见 [LinuxCtl.streamDeviceProvision]）。
     */
    fun provisionScriptCandidates(context: Context, mode: EnvMode): List<String> = when (mode) {
        EnvMode.ROOT -> listOf(
            "/data/adb/modules/sunsetlinux/bin/device-provision.sh",
            "${linuxHome(context, mode)}/bin/device-provision.sh",
        )
        // proot 模式没有真 chroot / mount，构建不了层：层只能从频道或种子目录来。
        EnvMode.PROOT -> emptyList()
    }
}

/** POSIX 单引号转义：`it's` → `'it'\''s'`。所有进入 shell 的参数都必须过这一层。 */
internal fun shQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

/**
 * 与 Linux 侧交互的**唯一**入口（docs/architecture.md §3）。
 *
 * - root 模式：`su -c '/data/sunsetlinux/bin/linuxctl status'`（优先 libsu，后备 ProcessBuilder）
 * - proot 模式：直接执行 `$APP_FILES/sunsetlinux/bin/linuxctl status`
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

    private val linuxPathEnv = "PATH=/system/bin:/system/xbin:/data/sunsetlinux/bin:\$PATH"

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
     * 当前真正的更新路径是 [io.github.sunsetrne.sunsetlinux.core.UpdateApplier]（它从频道清单取版本并传 `--version`），
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

    /**
     * **终端**（原生 PTY）的启动规格。
     *
     * 统一用 `/system/bin/sh -c "exec …"` 启动，原因有两条：
     *   1. `execve` 不查 PATH，而 `su` 的绝对路径各 ROM 不同（`/system/bin/su` /
     *      `/system/xbin/su` / `/data/adb/ksu/bin/su`…）—— 交给 sh 的 PATH 查找最稳；
     *   2. 这样 PTY 在最外层，`su` 之后的整条链路（chroot/proot → bash）都继承同一个
     *      控制终端，Ctrl-C 才能落到真正的前台进程组。
     *
     * proot 模式直接执行 `linuxctl attach`（它自己会 `start.sh --inner`）；丢了执行位就
     * 退化 `/system/bin/sh <path> attach` —— 与 [prootCommand] 同一套判据。
     */
    fun ptySpec(rows: Int, cols: Int): PtySpec {
        val script = when (mode) {
            EnvMode.ROOT -> "exec su -c " + shQuote(shellCommand(listOf("attach")))
            EnvMode.PROOT -> if (File(activeCtlPath).canExecute()) {
                "exec " + shQuote(activeCtlPath) + " attach"
            } else {
                "exec /system/bin/sh " + shQuote(activeCtlPath) + " attach"
            }
        }
        val env = baseEnv() + mapOf(
            // 终端相关：TERM 决定程序输出什么控制序列；LOCALE 决定 UTF-8 与消息语言
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
            "COLUMNS" to cols.toString(),
            "LINES" to rows.toString(),
        )
        return PtySpec(
            cmd = "/system/bin/sh",
            args = listOf("-c", script),
            env = env,
            cwd = if (mode == EnvMode.PROOT) context.filesDir.absolutePath else null,
        )
    }

    /** `linuxctl exec -- cmd...`（非交互，供 App 调环境内的命令）。 */
    suspend fun execInEnv(vararg cmd: String): CtlResult =
        execBlocking(listOf("exec", "--") + cmd.toList(), TIMEOUT_START_STOP)

    /**
     * **终端会话**用的完整进程命令：`linuxctl attach`（不带参数 = 进环境的交互 shell）。
     *
     * 与 status/logs 走同一套构造（root = `su -c "<PATH=…>; 'linuxctl' …"`；
     * proot = 直接执行 linuxctl，丢了执行位则退化为 `sh <path>`），
     * 这样环境根的解析（LINUX_HOME / SUNSETLINUX_APP_FILES）与其它命令必然一致。
     *
     * 返回的是**给 ProcessBuilder 的命令**，调用方负责起进程并接管 stdin/stdout
     * （见 core/TerminalSession.kt；没有 PTY，只能行缓冲）。
     */
    fun terminalCommand(): List<String> = when (mode) {
        EnvMode.ROOT -> listOf("su", "-c", shellCommand(listOf("attach")))
        EnvMode.PROOT -> prootCommand(listOf("attach"))
    }

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

    /**
     * 跑**设备侧原生构建**（`device-provision.sh`）：在真 chroot 里装 Ubuntu base → Node+pnpm
     * → DSH，每层打成 erofs 只读镜像。这是"在手机上建层"的**唯一**途径。
     *
     * 几个刻意的选择：
     * - **只有 root 模式**：它要真 chroot + mount + `unshare -m`，proot 里做不到；
     * - 用 `sh <脚本>` 而不是直接 `exec` 它：脚本住在 `/data/adb/modules/...`（vfat/mount
     *   不一定带执行位），而它本身第一行就是 `#!/system/bin/sh`；设备上**没有 bash**
     *   （1.0.6 起脚本是 mksh 原生 + profiles/ 随包，见那个脚本的文件头）。
     * - 种子目录：默认 `$LINUX_HOME/seeds`（`device-provision.sh` 的默认值也是它，
     *   两边一致）；脚本对已有层是幂等的（存在即跳过），所以向导可以放心一直调它。
     *
     * **阻塞 / 无超时**（可能跑十几分钟到半小时）——调用方负责放到 IO 线程。
     */
    fun streamDeviceProvision(seedsDir: String?, onLine: (String) -> Unit): CtlResult {
        if (mode != EnvMode.ROOT) {
            return CtlResult.fail(
                "只有 root 模式能在设备上原生构建层（需要真 chroot + mount）；" +
                    "proot 模式请从频道安装层。",
            )
        }
        val script = provisionShellScript(seedsDir)
        return Proc.stream(listOf("su", "-c", script), env = baseEnv(), cwd = null, onLine = onLine)
    }

    /**
     * 构建脚本的 shell 包装：
     * `PATH=...; prov=''; for c in <候选>; do [ -f "$c" ] && { prov="$c"; break; }; done; ...`
     *
     * 存在性判断必须在 `su` 里做 —— App 进程读不到 `/data/adb/`。找不到时**明确报错**
     * （退出码 127 + 人能看懂的一句话），不要让用户看到 `sh: not found` 的原文。
     */
    private fun provisionShellScript(seedsDir: String?): String {
        val candidates = DshPaths.provisionScriptCandidates(context, mode)
        val seeds = seedsDir?.trim()?.takeIf { it.isNotEmpty() } ?: "$home/seeds"
        return buildString {
            append(linuxPathEnv)
            append("; prov=''; ")
            append("for c in")
            candidates.forEach { append(' ').append(shQuote(it)) }
            append("; do [ -f \"\$c\" ] && { prov=\"\$c\"; break; }; done; ")
            append("if [ -z \"\$prov\" ]; then ")
            append("echo '找不到 device-provision.sh：请先安装/升级 KernelSU 模块（≥1.0.6），或检查模块是否被禁用' >&2; ")
            append("exit 127; fi; ")
            append("echo \"# device-provision.sh：\$prov（种子目录：").append(seeds).append("）\"; ")
            append("exec /system/bin/sh \"\$prov\" --seeds ").append(shQuote(seeds))
        }
    }

    // ---------------------------------------------------------------- 内部

    private suspend fun execBlocking(args: List<String>, timeoutMs: Long): CtlResult =
        withContext(Dispatchers.IO) {
            when (mode) {
                EnvMode.ROOT -> SuShell.exec(shellCommand(args), timeoutMs)
                EnvMode.PROOT -> Proc.exec(prootCommand(args), env = baseEnv(), cwd = workDir(), timeoutMs = timeoutMs)
            }
        }.normalize()

    /** `PATH=...; '/data/sunsetlinux/bin/linuxctl' 'status'` */
    private fun shellCommand(args: List<String>): String = buildString {
        append(linuxPathEnv)
        append("; ")
        append(shQuote(activeCtlPath))
        args.forEach { append(' ').append(shQuote(it)) }
    }

    /**
     * proot 模式直接执行 linuxctl（契约原文：直接执行 `$APP_FILES/sunsetlinux/bin/linuxctl status`）。
     * 万一脚本丢了执行位，退化为 `sh <path>`，避免用户看到 EACCES 却无从下手。
     */
    private fun prootCommand(args: List<String>): List<String> =
        if (File(activeCtlPath).canExecute()) listOf(activeCtlPath) + args
        else listOf("/system/bin/sh", activeCtlPath) + args

    private fun baseEnv(): Map<String, String> = buildMap {
        put("PATH", "/system/bin:/system/xbin:/data/sunsetlinux/bin")
        // 免 root 运行时的选择与位置：
        //   · proroot 的 5 个 .so 只在 **nativeLibraryDir**（随 APK 打的 jniLibs），
        //     脚本自己是找不到这个路径的 —— 必须由 App 透传，否则只能降级到 proot。
        //   · 版本号来自 BuildConfig（构建时读 tools/proroot/VENDOR.json），
        //     status 与日志里要能报出"用的是哪个版本"。
        runCatching { context.applicationInfo.nativeLibraryDir }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?.let { put("SUNSETLINUX_NATIVE_LIB_DIR", it) }
        put("SUNSETLINUX_PROROOT_VERSION", BuildConfig.PROROOT_VERSION)
        Prefs(context).rootlessRuntime?.let { put("SUNSETLINUX_ROOTLESS", it) }
        // 层模式（loop / dir）：用户可在「设置」里选，脚本读不到 App 的偏好，只能透传
        Prefs(context).layerMode?.let { put("SUNSETLINUX_LAYER_MODE", it) }
        // linuxctl 自己会解析环境根：优先 LINUX_HOME，其次 SUNSETLINUX_APP_FILES。
        // 两个都给上，避免部署侧调整解析顺序时 App 侧失效。
        put("LINUX_HOME", home)
        put("SUNSETLINUX_APP_FILES", context.filesDir.absolutePath)
        put("HOME", home)
    }

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
