package io.github.sunsetrne.sunsetd

import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * **宿主侧控制面客户端**（P2 的地基）：`app_process … io.github.sunsetrne.sunsetd.Ctl <子命令>`
 *
 * ## 为什么必须有它
 *
 * 控制面 socket 是 `0600 root`（决策 D3：不许任何 App 连上来指挥 root 内核）——
 * 因此 **App 进程自己连不上**：App 是 `untrusted_app`，连 `open()` 那个文件都不行。
 * 而 P2 要 App 改成"提交作业 + 订阅"，所以必须有一个**跑在 root 侧**的客户端，
 * 由 App 用 `su -c` 起（App 今天执行 `linuxctl` 走的也是这条 su 通道）。
 *
 * 真机上 `java.nio` 的 unix 客户端**不可用**（§3.10.50），所以这个客户端走
 * android.net.LocalSocket（[AndroidLocalTransport.connect]），由 [Clients] 选路。
 *
 * ## 子命令（刻意不做 JSON 参数解析：省掉 shell 里的引号地狱）
 *
 * ```
 *   status | ping | version
 *   submit <start|stop|dsh-start|dsh-stop>   提交作业；stdout 打印内核回的那行（含 jobId）
 *   job <jobId>                              查作业
 *   wait <jobId> [超时秒]                     轮询到作业结束（默认 300s）；stdout 只打印最后一行
 *   raw <一行 JSON>                           调试用
 * ```
 *
 * 退出码：`0` 成功 / `2` 参数错 / `3` 连不上内核 / `4` 内核回了错误 / `5` wait 超时。
 */
object Ctl {
    const val EXIT_OK = 0
    const val EXIT_USAGE = 2
    const val EXIT_NO_KERNEL = 3
    const val EXIT_KERNEL_ERROR = 4
    const val EXIT_TIMEOUT = 5

    /** 开机那一刻内核可能还没 bind：默认等 5 秒（每 200ms 重试一次）。 */
    const val DEFAULT_CONNECT_WAIT_SEC = 5
    const val DEFAULT_WAIT_JOB_SEC = 300
    const val WAIT_POLL_MS = 500L

    private val USAGE = """
        Ctl —— 宿主侧控制面客户端（App 用 su 起，或脚本里直接调）
        用法：app_process -Djava.class.path=<dex> /system/bin io.github.sunsetrne.sunsetd.Ctl
                [--connect-wait <秒>] <子命令> [参数]
          status | ping | version
          submit <start|stop|dsh-start|dsh-stop>
          job <jobId>
          wait <jobId> [超时秒]
          raw <一行 JSON>
        退出码：0 成功 / 2 参数错 / 3 连不上内核 / 4 内核回了错误 / 5 wait 超时
    """.trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        val code = run(args.toList(), System.out, System.err)
        System.out.flush()
        System.err.flush()
        exitProcess(code)
    }

    /** 可单测的入口（注入输出流与 [RuntimeEnv]/[Capabilities]）。 */
    fun run(
        args: List<String>,
        out: PrintStream,
        err: PrintStream,
        env: RuntimeEnv = RuntimeEnv(),
        caps: Capabilities = Capabilities.detect(),
    ): Int {
        var rest = args
        var connectWait = DEFAULT_CONNECT_WAIT_SEC
        while (rest.isNotEmpty() && rest[0].startsWith("--")) {
            when (rest[0]) {
                "--connect-wait" -> {
                    connectWait = rest.getOrNull(1)?.toIntOrNull() ?: return usage(err)
                    rest = rest.drop(2)
                }
                else -> return usage(err)
            }
        }
        if (rest.isEmpty()) return usage(err)

        val sub = rest[0]
        // 参数错在连内核**之前**就判掉（免得"参数写错"被报成"连不上"）
        val frame = when (sub) {
            "status", "ping", "version" -> Json.obj("op" to sub)
            "submit" -> rest.getOrNull(1)?.takeIf { it.isNotBlank() }?.let {
                Json.obj("op" to "submit", "action" to it)
            } ?: return usage(err)
            "job" -> rest.getOrNull(1)?.takeIf { it.isNotBlank() }?.let {
                Json.obj("op" to "job", "id" to it)
            } ?: return usage(err)
            "raw" -> rest.drop(1).joinToString(" ").trim().takeIf { it.isNotEmpty() } ?: return usage(err)
            "wait" -> rest.getOrNull(1)?.takeIf { it.isNotBlank() }?.let {
                Json.obj("op" to "job", "id" to it)
            } ?: return usage(err)
            else -> return usage(err)
        }

        val client = connect(env, connectWait, err, caps) ?: return EXIT_NO_KERNEL
        return try {
            client.use {
                if (sub == "wait") {
                    val timeoutSec = rest.getOrNull(2)?.toIntOrNull() ?: DEFAULT_WAIT_JOB_SEC
                    waitJob(it, rest[1], timeoutSec, out, err)
                } else {
                    emit(it.rpc(frame), out, err)
                }
            }
        } catch (t: Throwable) {
            err.println("[ctl] 与内核通信失败：${describe(t)}")
            EXIT_NO_KERNEL
        }
    }

    private fun emit(line: String, out: PrintStream, err: PrintStream): Int {
        out.println(line)
        if (line.contains("\"ok\":false")) {
            err.println("[ctl] 内核回了错误：$line")
            return EXIT_KERNEL_ERROR
        }
        return EXIT_OK
    }

    /** 轮询到作业结束。**stdout 只出最后那行**（脚本要能直接 `| jq`）。 */
    private fun waitJob(
        client: ControlClient,
        jobId: String,
        timeoutSec: Int,
        out: PrintStream,
        err: PrintStream,
    ): Int {
        val frame = Json.obj("op" to "job", "id" to jobId)
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        var last = ""
        while (true) {
            last = client.rpc(frame)
            val done = last.contains("\"finished\":true")
            val failed = last.contains("\"ok\":false")
            if (done || failed || System.currentTimeMillis() >= deadline) {
                out.println(last)
                return when {
                    failed -> EXIT_KERNEL_ERROR
                    done -> EXIT_OK
                    else -> {
                        err.println("[ctl] 等作业 $jobId 超时（${timeoutSec}s）")
                        EXIT_TIMEOUT
                    }
                }
            }
            Thread.sleep(WAIT_POLL_MS)
        }
    }

    private fun connect(
        env: RuntimeEnv,
        waitSec: Int,
        err: PrintStream,
        caps: Capabilities,
    ): ControlClient? {
        val deadline = System.currentTimeMillis() + waitSec * 1000L
        var last: Throwable? = null
        while (true) {
            try {
                return ControlClient.connect(env.socketFile, caps)
            } catch (t: Throwable) {
                last = t
                if (System.currentTimeMillis() >= deadline) break
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        err.println(
            "[ctl] 连不上内核控制面（${env.socketFile}）：" +
                describe(last ?: IllegalStateException("未知原因")),
        )
        return null
    }

    private fun usage(err: PrintStream): Int {
        err.println(USAGE)
        return EXIT_USAGE
    }
}
