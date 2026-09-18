package io.github.sunsetrne.sunsetd

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 控制面：`run/control.sock` 上的 **unix domain socket + 行分隔 JSON**（决策 D3）。
 *
 * 为什么用 unix socket 而不是 TCP：只有本机进程能连、能按文件权限收紧（0600）、
 * 且不会有"任何 App 都能连上来指挥 root 内核"的问题。设备 ART 支持
 * `java.nio` 的 UNIX 协议族（实测 `core-oj.jar` 里有 `UnixDomainSocketAddress`），
 * 所以这份实现**同一套代码**在真机与容器单测里都能跑。
 *
 * P1 只有"请求 → 响应"；连接可以复用（一次读一行、回一行）。
 * 事件流（订阅）在 P2 —— 那时 App 才真正不再依赖"命令返回"。
 */
class ControlServer(
    private val env: RuntimeEnv,
    private val kernel: Kernel,
    private val log: (String) -> Unit,
) {
    private val stopFlag = AtomicBoolean(false)
    private var server: ServerSocketChannel? = null
    private var acceptThread: Thread? = null

    val shouldStop: Boolean get() = stopFlag.get()

    fun start() {
        File(env.runDir).mkdirs()
        val sockFile = File(env.socketFile)
        if (sockFile.exists() && !canConnect(sockFile.path)) {
            // 陈旧 socket（上次内核没清理）：删掉重建
            sockFile.delete()
        }
        if (canConnect(sockFile.path)) {
            throw IllegalStateException("control.sock 已被占用（另一个 sunsetd 在跑？）：${sockFile.path}")
        }
        val ch = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        ch.bind(UnixDomainSocketAddress.of(sockFile.path))
        server = ch
        // 0600：只有 root 能连（App 通过 su 起的客户端也能读；普通 App 连不上）
        try {
            java.nio.file.Files.setPosixFilePermissions(
                sockFile.toPath(),
                PosixFilePermissions.fromString("rw-------"),
            )
        } catch (t: Throwable) {
            log("[sunsetd] WARN: 无法设置 socket 权限（非致命）：${t.message}")
        }
        log("[sunsetd] 控制面已就绪：${sockFile.path}")
        acceptThread = Thread({ acceptLoop(ch) }, "sunsetd-accept").apply {
            isDaemon = true
            start()
        }
    }

    private fun canConnect(path: String): Boolean = try {
        SocketChannel.open(UnixDomainSocketAddress.of(path)).use { true }
    } catch (_: Throwable) {
        false
    }

    private fun acceptLoop(ch: ServerSocketChannel) {
        while (!stopFlag.get()) {
            val client = try {
                ch.accept()
            } catch (_: Throwable) {
                if (stopFlag.get()) return else continue
            }
            try {
                handleClient(client)
            } catch (t: Throwable) {
                log("[sunsetd] 客户端处理异常：${t.message}")
            } finally {
                try {
                    client.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun handleClient(client: SocketChannel) {
        val reader = BufferedReader(InputStreamReader(Channels.newInputStream(client), Charsets.UTF_8))
        val writer = OutputStreamWriter(Channels.newOutputStream(client), Charsets.UTF_8)
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
            server?.close()
        } catch (_: Throwable) {
        }
        try {
            File(env.socketFile).delete()
        } catch (_: Throwable) {
        }
    }
}

/**
 * 内核进程入口：`app_process /system/bin io.github.sunsetrne.sunsetd.MainKt`（见 `module/service.sh`）。
 *
 * 主循环：每秒 `tick()`（推相位 + 写 `state.json` + 心跳）。状态**只**从这里写出去 ——
 * 这是"唯一状态源"的字面意思：App、`linuxctl`、WebUI、doctor 读到的都是同一份。
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
            sink.log("[sunsetd] 控制面启动失败：${t.message}")
            kernel.publish()
            return
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
}
