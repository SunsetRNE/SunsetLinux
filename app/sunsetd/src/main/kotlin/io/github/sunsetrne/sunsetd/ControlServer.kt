package io.github.sunsetrne.sunsetd

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 控制面：`run/control.sock` 上的 **unix domain socket + 行分隔 JSON**（决策 D3）。
 *
 * 为什么用 unix socket 而不是 TCP：只有本机进程能连、能按文件权限收紧（0600）、
 * 且不会有"任何 App 都能连上来指挥 root 内核"的问题。
 *
 * **通道是可换的**（[ControlListener]）：*什么样的 socket* 由 [Transports] 按能力探测决定，
 * 本类只管协议本身。真机上 java.nio 没有 UNIX 服务端入口，走 `android-local`；
 * 桌面单测走 `nio-unix` —— 协议代码两边**是同一份**（2026-09-18 真机事故的修法，
 * 见 `docs/STATUS.md` §3.10.48）。
 *
 * P1 只有"请求 → 响应"；连接可以复用（一次读一行、回一行）。
 * 事件流（订阅）在 P2 —— 那时 App 才真正不再依赖"命令返回"。
 */
class ControlServer(
    private val env: RuntimeEnv,
    private val kernel: Kernel,
    private val caps: Capabilities = Capabilities.detect(),
    private val log: (String) -> Unit,
) {
    private val stopFlag = AtomicBoolean(false)
    private var listener: ControlListener? = null
    private var acceptThread: Thread? = null

    val shouldStop: Boolean get() = stopFlag.get()

    /** 实际选中的通道名（`nio-unix` / `android-local`）；没起来时是 `none`。 */
    var transportName: String = "none"
        private set

    fun start() {
        File(env.runDir).mkdirs()
        val pick = Transports.pick(
            path = env.socketFile,
            caps = caps,
            openNio = { NioUnixListener.open(it) },
            openAndroid = { AndroidLocalTransport.listen(it) },
            log = log,
        )
        val l = when (pick) {
            is Pick.Ok -> pick.listener
            is Pick.None -> throw IllegalStateException("没有可用的控制面通道（${pick.reason}）")
        }
        listener = l
        transportName = l.name
        log("[sunsetd] 控制面已就绪：${env.socketFile}（通道 ${l.name}）")
        acceptThread = Thread({ acceptLoop(l) }, "sunsetd-accept").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop(l: ControlListener) {
        while (!stopFlag.get()) {
            val client = try {
                l.accept() ?: return
            } catch (_: Throwable) {
                if (stopFlag.get()) return else continue
            }
            try {
                handleClient(client)
            } catch (t: Throwable) {
                log("[sunsetd] 客户端处理异常：${describe(t)}")
            } finally {
                client.close()
            }
        }
    }

    private fun handleClient(client: Duplex) {
        val reader: BufferedReader = client.reader
        val writer = client.writer
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isBlank()) continue
            val op = Protocol.parse(line)?.op
            val response = if (op == "shutdown") {
                // 开发/测试用：请求内核退出（真机上由模块的 stop/重启流程调用）
                stopFlag.set(true)
                Protocol.ok(Json.obj("stopping" to true))
            } else {
                kernel.handle(line)
            }
            writer.write(Protocol.line(response))
            writer.flush()
        }
    }

    fun stop() {
        stopFlag.set(true)
        try {
            listener?.close()
        } catch (_: Throwable) {
        }
        try {
            File(env.socketFile).delete()
        } catch (_: Throwable) {
        }
    }
}

/**
 * 内核进程入口：`app_process /system/bin io.github.sunsetrne.sunsetd.Main`（见 `module/service.sh`）。
 *
 * 主循环：每秒 `tick()`（推相位 + 写 `state.json` + 心跳）。状态**只**从这里写出去 ——
 * 这是"唯一状态源"的字面意思：App、`linuxctl`、WebUI、doctor 读到的都是同一份。
 *
 * 2026-09-18 起，启动时还会落两个**诊断文件**（不是状态，是"通道这条命还在不在"）：
 *  - `run/control-transport`：选中的通道（`nio-unix` / `android-local` / `none`）
 *  - `run/control-selftest`：回环自检结论（`running` / `ok:<通道>` / `fail:<原因>`）
 * 上一轮真机验证卡在"控制面起没起来"，这两个文件让下一轮**一次装包就能问到底**。
 */
object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        val env = RuntimeEnv()
        val sink = FileStateSink(env)
        val kernel = Kernel(env, sink, FileWorld(env), ProcessJobRunner())
        sink.log("[sunsetd] 内核启动（LINUX_HOME=${env.linuxHome}，protocol=${Kernel.PROTOCOL}）")
        kernel.start()
        val server = ControlServer(env, kernel) { sink.log(it) }
        try {
            server.start()
        } catch (t: Throwable) {
            // 起不来也要留下状态与原因（否则客户端只会看到"没有 socket"）
            sink.log("[sunsetd] 控制面启动失败：${describe(t)}")
            sink.log("[sunsetd] 控制面启动失败（栈前 12 行）：\n${frames(t)}")
            writeDiag(env.transportFile, "none")
            writeDiag(env.selfTestFile, "not-run：${describe(t)}")
            kernel.publish()
            return
        }
        writeDiag(env.transportFile, server.transportName)
        // 自检异步跑：主循环一秒都不能被它挡住（自检只影响诊断文件，不影响相位）
        ControlSelfTest.runAsyncAndRecord(env.socketFile) { result ->
            writeDiag(env.selfTestFile, result)
            sink.log("[sunsetd] 控制面回环自检：$result")
        }
        Runtime.getRuntime().addShutdownHook(
            Thread {
                server.stop()
                sink.log("[sunsetd] 内核退出")
            },
        )
        while (!server.shouldStop) {
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            kernel.tick()
        }
        server.stop()
        sink.log("[sunsetd] 主循环结束")
    }

    private fun writeDiag(path: String, text: String) {
        try {
            File(path).writeText(text + "\n")
        } catch (_: Throwable) {
        }
    }

    private fun frames(t: Throwable): String =
        t.stackTrace.take(12).joinToString("\n") { "    at $it" }
}
