package io.github.sunsetrne.sunsetlinux.core

/**
 * 一个终端会话的**启动规格**（要跑什么、在哪个目录、什么环境）。
 *
 * 单独抽出来是为了可单测：LinuxCtl 负责按模式（root / proot）生成它，
 * PtySession 只负责把它跑起来 —— 两者的边界就是"一份纯数据"。
 */
data class PtySpec(
    /** 可执行文件**绝对路径**（execve 不查 PATH）。我们统一用 `/system/bin/sh`。 */
    val cmd: String,
    /** 参数（不含 argv[0]，JNI 侧会按 cmd 的 basename 补）。 */
    val args: List<String>,
    /** 完整环境变量（execve 是替换式的，所以必须是全量，不是增量）。 */
    val env: Map<String, String>,
    /** 工作目录（null = 不 chdir）。 */
    val cwd: String?,
) {
    /** 给日志/排障看的一行（不含环境变量，免得刷屏）。 */
    val display: String
        get() = (listOf(cmd) + args).joinToString(" ") { if (it.any { c -> c == ' ' }) "'$it'" else it }
}

/**
 * 基于原生 PTY 的终端会话：**字节流**进、**字节流**出。
 *
 * 与旧的 [TerminalSession]（普通管道 + 按行）相比，这里多了三件事 ——
 * 而它们正是"终端"该有的样子：
 *   1. [write] 任意字节：`\u0003`(Ctrl-C) / `\u0004`(Ctrl-D) / `\u001b[A`(↑) 都由
 *      **PTY 的行规程**处理（Ctrl-C 会真的变成 SIGINT，本地实测确认）；
 *   2. [resize]：把控件尺寸通过 `TIOCSWINSZ` 告诉内核，vim/htop 跟着重排；
 *   3. 退出码：`waitFor` 走 waitpid，信号退出是 128+signo（和 shell 一致）。
 *
 * 生命周期：start → (onOutput 若干) → onExit。stop() 主动结束 = 关 master fd，
 * 内核向前台进程组发 SIGHUP；1.5 秒还没退再 SIGKILL 兜底。
 */
internal class PtySession {

    private val lock = Any()
    private var fd: Int = -1
    private var pid: Int = -1
    private var pfd: java.io.FileDescriptor? = null
    private var input: java.io.FileInputStream? = null
    private var output: java.io.FileOutputStream? = null

    /** 原始输出字节（在 IO 线程回调，调用方要**尽快**处理，缓冲区会被复用）。 */
    var onOutput: ((buf: ByteArray, len: Int) -> Unit)? = null

    /** 会话结束（退出码；信号退出为 128+signo）。stop() 主动结束也会走这里。 */
    var onExit: ((Int) -> Unit)? = null

    val isRunning: Boolean
        get() = synchronized(lock) { pid > 0 }

    /**
     * 启动。成功返回 `null`，失败返回**可直接展示给用户**的中文原因。
     *
     * 失败路径会把已经分配的资源收干净（fd / 子进程），不留孤儿。
     */
    fun start(spec: PtySpec, rows: Int, cols: Int): String? {
        if (!PtyNative.available) return PtyNative.loadError ?: "原生 PTY 不可用"
        stop()

        val pidOut = IntArray(1)
        val master = try {
            PtyNative.open(
                cmd = spec.cmd,
                cwd = spec.cwd,
                args = spec.args.toTypedArray(),
                env = spec.env.map { (k, v) -> "$k=$v" }.toTypedArray(),
                pidOut = pidOut,
                rows = rows.coerceAtLeast(1),
                cols = cols.coerceAtLeast(1),
            )
        } catch (t: Throwable) {
            return "无法启动终端：${t.message ?: t.javaClass.simpleName}"
        }
        if (master < 0) return "无法分配 PTY（fd=$master）"

        val descriptor = android.os.ParcelFileDescriptor.adoptFd(master).fileDescriptor
        synchronized(lock) {
            fd = master
            pid = pidOut[0]
            pfd = descriptor
            input = java.io.FileInputStream(descriptor)
            output = java.io.FileOutputStream(descriptor)
        }

        Thread({
            val buf = ByteArray(8192)
            try {
                val i = synchronized(lock) { input }
                while (true) {
                    val n = i?.read(buf) ?: -1
                    if (n < 0) break
                    if (n > 0) onOutput?.invoke(buf, n)
                }
            } catch (_: Throwable) {
                // 关 fd（stop/子进程退出）时读会抛，属正常路径
            }
        }, "sunsetlinux-pty-reader").apply { isDaemon = true }.start()

        Thread({
            val code = try { PtyNative.waitFor(pidOut[0]) } catch (_: Throwable) { -1 }
            closeQuietly()
            synchronized(lock) {
                if (pid == pidOut[0]) {
                    pid = -1
                    fd = -1
                }
            }
            onExit?.invoke(code)
        }, "sunsetlinux-pty-waiter").apply { isDaemon = true }.start()

        return null
    }

    /** 写原始字节（不是"一行"——Ctrl-C、方向键都靠这个）。 */
    fun write(bytes: ByteArray): Boolean {
        val out = synchronized(lock) { output } ?: return false
        return try {
            out.write(bytes)
            out.flush()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 写一段文本（补 `\r` 交给调用方决定：终端里回车是 `\r`，不是 `\n`）。 */
    fun writeText(text: String): Boolean = write(text.toByteArray(Charsets.UTF_8))

    /** 控件尺寸变了：让内核把新行列告诉前台程序。 */
    fun resize(rows: Int, cols: Int) {
        val cur = synchronized(lock) { fd }
        if (cur < 0) return
        runCatching { PtyNative.resize(cur, rows.coerceAtLeast(1), cols.coerceAtLeast(1)) }
    }

    /** 结束会话：关 master（SIGHUP）→ 1.5s 后仍在则 SIGKILL。 */
    fun stop() {
        val (pidNow, fdNow) = synchronized(lock) { pid to fd }
        if (fdNow >= 0) closeQuietly()
        if (pidNow > 0) {
            Thread({
                val deadline = System.currentTimeMillis() + 1500
                while (System.currentTimeMillis() < deadline) {
                    // 子进程还在不在：kill(pid, 0) 在 JNI 侧没有直接暴露，用 /proc 判定
                    if (!java.io.File("/proc/$pidNow").exists()) return@Thread
                    try { Thread.sleep(150) } catch (_: InterruptedException) { return@Thread }
                }
                runCatching { PtyNative.signal(pidNow, 9) }   // SIGKILL
            }, "sunsetlinux-pty-reaper").apply { isDaemon = true }.start()
        }
        synchronized(lock) {
            pid = -1
            fd = -1
        }
    }

    private fun closeQuietly() {
        synchronized(lock) {
            runCatching { input?.close() }
            runCatching { output?.close() }
            input = null
            output = null
            pfd = null
        }
    }
}
