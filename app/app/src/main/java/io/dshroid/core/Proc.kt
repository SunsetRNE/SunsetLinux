package io.dshroid.core

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 一次外部命令调用的结果。
 *
 * [error] 只用于「命令根本没跑起来」或「有明确可读原因」的场景（su 缺失、linuxctl 缺失、
 * 超时），内容是可以直接显示给用户的中文；[stdout] / [stderr] 保持原样供诊断。
 */
data class CtlResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null && exitCode == 0

    /** 给用户看的一句话（优先友好错误，其次 stderr 首行）。 */
    val message: String
        get() = error
            ?: stderr.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: "命令退出码 $exitCode"

    companion object {
        fun fail(message: String, exitCode: Int = -1): CtlResult = CtlResult(exitCode, "", "", message)
    }
}

/**
 * 极薄的进程封装。**所有调用都必须在 IO 线程上进行**（调用方负责）。
 *
 * 为什么不用 `Process.waitFor()` 默认行为：`su` 若在等授权弹窗会永久阻塞，
 * 因此所有调用都带超时；超时后 destroy 进程并返回可读错误。
 */
internal object Proc {

    fun exec(
        cmd: List<String>,
        env: Map<String, String> = emptyMap(),
        cwd: File? = null,
        timeoutMs: Long = 0L,
    ): CtlResult {
        if (cmd.isEmpty()) return CtlResult.fail("内部错误：空命令")

        val process = try {
            ProcessBuilder(cmd)
                .apply {
                    if (cwd != null) directory(cwd)
                    if (env.isNotEmpty()) environment().putAll(env)
                }
                .start()
        } catch (e: IOException) {
            return CtlResult.fail(friendlyStartFailure(cmd.first(), e))
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        // 必须并发读取两个流，否则管道缓冲写满后子进程会卡死。
        val outReader = pump(process.inputStream, stdout)
        val errReader = pump(process.errorStream, stderr)

        val finished = try {
            if (timeoutMs > 0) process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) else {
                process.waitFor(); true
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroy()
            return CtlResult.fail("命令被取消")
        }

        if (!finished) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            return CtlResult.fail(
                "命令超时（${timeoutMs / 1000} 秒未返回）：${cmd.joinToString(" ")}。" +
                    "如果设备刚弹出 root 授权请求，请先允许本应用使用 root 再重试。"
            )
        }

        outReader.join(1500)
        errReader.join(1500)
        return CtlResult(
            exitCode = process.exitValue(),
            stdout = stdout.toString().trim(),
            stderr = stderr.toString().trim(),
        )
    }

    /**
     * 执行命令并把 [input] 喂给它的 stdin（用于 `cat > file` 这类写入）。
     * 内容不进命令行，因此不受参数长度与转义影响。
     */
    fun execWithInput(cmd: List<String>, input: ByteArray, timeoutMs: Long = 0L): CtlResult {
        if (cmd.isEmpty()) return CtlResult.fail("内部错误：空命令")

        val process = try {
            ProcessBuilder(cmd).start()
        } catch (e: IOException) {
            return CtlResult.fail(friendlyStartFailure(cmd.first(), e))
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outReader = pump(process.inputStream, stdout)
        val errReader = pump(process.errorStream, stderr)

        try {
            process.outputStream.use { it.write(input) }
        } catch (_: IOException) {
            // 子进程提前退出时写 stdin 会失败，交由下面的退出码/输出判断
        }

        val finished = try {
            if (timeoutMs > 0) process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) else {
                process.waitFor(); true
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroy()
            return CtlResult.fail("命令被取消")
        }

        if (!finished) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            return CtlResult.fail("命令超时（${timeoutMs / 1000} 秒未返回，可能卡在 root 授权弹窗）")
        }

        outReader.join(1500)
        errReader.join(1500)
        return CtlResult(process.exitValue(), stdout.toString().trim(), stderr.toString().trim())
    }

    /**
     * 逐行流式执行（用于 provision / update 这类长任务）。
     * [onLine] 在读取线程上被调用，实现方需自行切回合适线程。
     */
    fun stream(
        cmd: List<String>,
        env: Map<String, String> = emptyMap(),
        cwd: File? = null,
        onLine: (String) -> Unit,
    ): CtlResult {
        if (cmd.isEmpty()) return CtlResult.fail("内部错误：空命令")

        val process = try {
            ProcessBuilder(cmd)
                .apply {
                    if (cwd != null) directory(cwd)
                    if (env.isNotEmpty()) environment().putAll(env)
                }
                .start()
        } catch (e: IOException) {
            return CtlResult.fail(friendlyStartFailure(cmd.first(), e))
        }

        val stderr = StringBuilder()
        val errReader = pump(process.errorStream) { line ->
            stderr.append(line).append('\n')
            onLine(line)
        }

        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line -> onLine(line) }
        }

        val code = process.waitFor()
        errReader.join(1500)
        return CtlResult(code, "", stderr.toString().trim())
    }

    private fun pump(src: java.io.InputStream, sink: StringBuilder): Thread {
        val t = Thread {
            try {
                src.bufferedReader().forEachLine { sink.append(it).append('\n') }
            } catch (_: Throwable) {
                // 进程被 destroy 时读取会抛异常，忽略即可
            }
        }
        t.isDaemon = true
        t.start()
        return t
    }

    private fun pump(src: java.io.InputStream, onLine: (String) -> Unit): Thread {
        val t = Thread {
            try {
                src.bufferedReader().forEachLine(onLine)
            } catch (_: Throwable) {
            }
        }
        t.isDaemon = true
        t.start()
        return t
    }

    private fun friendlyStartFailure(program: String, e: IOException): String = when {
        program.endsWith("su") || program == "su" ->
            "未找到可用的 su（${e.message ?: "exec 失败"}）。请确认设备已刷入 KernelSU/Magisk 且已授权本应用，" +
                "或改用 proot 模式。"
        else ->
            "无法执行 ${program}：${e.message ?: e.javaClass.simpleName}"
    }
}

/**
 * root 模式下的 `su` 通道。
 *
 * 优先 libsu（`com.github.topjohnwu.libsu:core`）：它把 root shell 常驻，省掉每次
 * 调用新建进程的开销，并且不需要自己在命令行里拼 su 参数。
 * **但 libsu 并非总是可用**（未授权 / 初始化失败 / 换设备），因此保留
 * `ProcessBuilder("su", "-c", ...)` 作为等价后备实现——两条路的返回值语义完全一致。
 *
 * 备注：本工程构建时 JitPack 可达，libsu 6.0.0 已成功解析进依赖；若在无 JitPack 的
 * 环境构建失败，只需删掉 app/build.gradle.kts 里的 `libsu.core` 依赖和本类的
 * [execViaLibsu] 分支，[execViaProcessBuilder] 会自动接管全部调用。
 */
internal object SuShell {

    /** libsu 报告的是否已授予 root：null 表示 libsu 不可用或无法判定。 */
    fun libsuGranted(): Boolean? = try {
        com.topjohnwu.superuser.Shell.isAppGrantedRoot()
    } catch (_: Throwable) {
        null
    }

    /** 探测设备是否具备可用的 su（proot 模式的判定依据）。 */
    fun probe(timeoutMs: Long = 6000L): Boolean {
        if (libsuGranted() == true) return true
        val r = execViaProcessBuilder("id", timeoutMs)
        return r.exitCode == 0 && r.stdout.contains("uid=0")
    }

    fun exec(command: String, timeoutMs: Long): CtlResult =
        if (libsuGranted() == true) execViaLibsu(command, timeoutMs) else execViaProcessBuilder(command, timeoutMs)

    /**
     * libsu 的 `Job.exec()` 是同步阻塞的，但**没有超时参数**；
     * 因此超时由调用方的协程（`withTimeoutOrNull`）负责，这里只做结果搬运。
     */
    private fun execViaLibsu(command: String, @Suppress("UNUSED_PARAMETER") timeoutMs: Long): CtlResult = try {
        val result = com.topjohnwu.superuser.Shell.cmd(command).exec()
        CtlResult(
            exitCode = result.code,
            stdout = result.out.joinToString("\n").trim(),
            stderr = result.err.joinToString("\n").trim(),
        )
    } catch (t: Throwable) {
        // libsu 抛异常（授权被撤销、shell 崩溃）→ 退回 ProcessBuilder，保证功能不中断
        execViaProcessBuilder(command, timeoutMs)
    }

    private fun execViaProcessBuilder(command: String, timeoutMs: Long): CtlResult =
        Proc.exec(listOf("su", "-c", command), timeoutMs = timeoutMs)
}
