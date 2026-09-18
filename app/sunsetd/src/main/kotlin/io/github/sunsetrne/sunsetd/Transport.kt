package io.github.sunsetrne.sunsetd

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.lang.reflect.InvocationTargetException
import java.net.ProtocolFamily
import java.net.SocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * 控制面**通道**：把"什么样的 socket"和"协议长什么样"分开。
 *
 * 为什么必须分开（2026-09-18 真机教训，细节见 `docs/STATUS.md` §3.10.48）：
 * 第一版直接把 `java.nio` 的 UNIX 服务端写在 [ControlServer] 里，桌面单测 34 条全绿，
 * 真机上内核起来了、状态也写了，**唯独控制面抛 NoSuchMethodError**：
 *
 * > 控制面启动失败：No static method open(Ljava/net/ProtocolFamily;)
 * > Ljava/nio/channels/ServerSocketChannel; in class Ljava/nio/channels/ServerSocketChannel;
 *
 * 事后按真机的 `core-oj.jar` 逐字节核对：设备 ART 里 `ServerSocketChannel` **只有 `open()`**，
 * 没有 `open(ProtocolFamily)`；而 SDK 桩（`android-35/android.jar`）里是**有的** ——
 * 也就是说"照 SDK 桩写、桌面 JVM 跑单测"这条路，**天生看不见这个差异**。
 * （同一个 dex 里 `sun.nio.ch.UnixDomainSockets` 的 connect/bind/accept 都是齐的，
 *  `SocketChannel.open(SocketAddress)` 也在 ⇒ 真机上**客户端**半边能用，缺的只是服务端入口。）
 *
 * 所以现在：协议层只认 [Duplex]；通道由 [Transports.pick] 按**能力探测**选，
 * 并且选不中时要说清是哪一条、为什么。单测可以注入 [Capabilities] 与假的通道工厂，
 * 把"真机（缺 nio 服务端）/ 桌面（有 nio 服务端）"两种情形都在 JVM 上断言掉。
 */
interface Duplex : Closeable {
    val reader: BufferedReader
    val writer: BufferedWriter
    override fun close()
}

/** 控制面的监听端（`accept` 一条连接；`close` 之后必须能解开阻塞中的 accept）。 */
interface ControlListener {
    /** 通道名，进日志与 `run/control-transport`：`nio-unix` / `android-local`。 */
    val name: String

    /** 阻塞接受一条连接；返回 null = 监听已关闭。 */
    fun accept(): Duplex?

    fun close()
}

/** 选择器的结论。 */
sealed interface Pick {
    data class Ok(val listener: ControlListener) : Pick

    /** 没有任何可用通道；[reason] 要能让 `doctor` / 交付说明直接读。 */
    data class None(val reason: String) : Pick
}

/**
 * 运行环境的能力（**可注入** —— 这是"真机情形能在 JVM 单测里断言"的关键）。
 *
 * 两个能力是**分开**的：真机上 UNXI 服务端缺、客户端有，所以不能用同一个开关。
 */
data class Capabilities(
    /** 服务端：`ServerSocketChannel.open(ProtocolFamily)`（真机 ART 实测**没有**）。 */
    val nioUnixServer: Boolean,
    /** 客户端：`SocketChannel.open(SocketAddress)`（真机 ART 实测**有**）。 */
    val nioUnixClient: Boolean,
    /** `android.net.LocalSocket` / `LocalServerSocket`（真机有，桌面 JVM 没有）。 */
    val androidLocal: Boolean,
) {
    companion object {
        fun detect(): Capabilities = Capabilities(
            nioUnixServer = hasMethod(ServerSocketChannel::class.java, "open", ProtocolFamily::class.java),
            nioUnixClient = hasMethod(SocketChannel::class.java, "open", SocketAddress::class.java),
            androidLocal = hasClass(AndroidLocalTransport.LOCAL_SOCKET),
        )

        private fun hasMethod(cls: Class<*>, name: String, vararg params: Class<*>): Boolean = try {
            cls.getMethod(name, *params)
            true
        } catch (_: Throwable) {
            false
        }

        private fun hasClass(name: String): Boolean = try {
            Class.forName(name)
            true
        } catch (_: Throwable) {
            false
        }
    }
}

/** 通道选择（**纯逻辑**：工厂注入 ⇒ 不需要真机/真 socket 也能把四条分支都测到）。 */
object Transports {

    fun pick(
        path: String,
        caps: Capabilities,
        openNio: (String) -> ControlListener,
        openAndroid: (String) -> ControlListener,
        log: (String) -> Unit,
    ): Pick {
        val reasons = mutableListOf<String>()

        if (caps.nioUnixServer) {
            try {
                val l = openNio(path)
                log("[sunsetd] 控制面通道：${l.name}（java.nio 的 UNIX 服务端可用）")
                return Pick.Ok(l)
            } catch (t: Throwable) {
                reasons += "nio-unix：${describe(t)}"
                log("[sunsetd] nio-unix 起不来（继续试下一条）：${describe(t)}")
            }
        } else {
            reasons += "nio-unix：本机 java.nio 没有 ServerSocketChannel.open(ProtocolFamily)"
            log("[sunsetd] 跳过 nio-unix：本机 java.nio 没有 ServerSocketChannel.open(ProtocolFamily)")
        }

        if (caps.androidLocal) {
            try {
                val l = openAndroid(path)
                log("[sunsetd] 控制面通道：${l.name}（Android LocalSocket，文件路径命名空间）")
                return Pick.Ok(l)
            } catch (t: Throwable) {
                reasons += "android-local：${describe(t)}"
                log("[sunsetd] android-local 起不来：${describe(t)}")
            }
        } else {
            reasons += "android-local：本机没有 android.net.LocalSocket"
        }

        return Pick.None(reasons.joinToString("；"))
    }

    /** 真机/容器上都可用的入口（能力探测在内部做）。 */
    fun open(path: String, log: (String) -> Unit): Pick =
        pick(
            path = path,
            caps = Capabilities.detect(),
            openNio = { NioUnixListener.open(it) },
            openAndroid = { AndroidLocalTransport.listen(it) },
            log = log,
        )
}

/** `nio-unix` 通道：桌面 JVM（单测）走这条。 */
class NioUnixListener private constructor(
    private val ch: ServerSocketChannel,
    private val path: String,
) : ControlListener {

    override val name: String = "nio-unix"

    override fun accept(): Duplex? {
        val client = ch.accept() ?: return null
        return NioDuplex(client)
    }

    override fun close() {
        try {
            ch.close()
        } catch (_: Throwable) {
        }
        try {
            File(path).delete()
        } catch (_: Throwable) {
        }
    }

    companion object {
        fun open(path: String): NioUnixListener {
            val f = File(path)
            f.parentFile?.mkdirs()
            // 陈旧 socket（上次内核被 kill -9）：连不上就删掉重建
            if (f.exists() && !canConnect(path)) f.delete()
            if (canConnect(path)) {
                throw IllegalStateException("control.sock 已被占用（另一个 sunsetd 在跑？）：$path")
            }
            val ch = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            ch.bind(UnixDomainSocketAddress.of(path))
            try {
                Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------"))
            } catch (_: Throwable) {
            }
            return NioUnixListener(ch, path)
        }

        fun canConnect(path: String): Boolean = try {
            SocketChannel.open(UnixDomainSocketAddress.of(path)).use { true }
        } catch (_: Throwable) {
            false
        }
    }
}

private class NioDuplex(private val ch: SocketChannel) : Duplex {
    override val reader: BufferedReader =
        BufferedReader(InputStreamReader(Channels.newInputStream(ch), Charsets.UTF_8))
    override val writer: BufferedWriter =
        BufferedWriter(OutputStreamWriter(Channels.newOutputStream(ch), Charsets.UTF_8))

    override fun close() {
        try {
            ch.close()
        } catch (_: Throwable) {
        }
    }
}

/** 客户端半边（自检 + CLI + App）：真机只能用 android-local，见 [Clients.open]。 */
object OpenClient {
    fun nioClient(path: String): Duplex = NioDuplex(SocketChannel.open(UnixDomainSocketAddress.of(path)))
}

/**
 * 客户端通道选择。
 *
 * **为什么默认先试 android-local**：真机上 `java.nio` 的 unix 客户端**不可用**
 * （2026-09-18 真机自检结论 `ok:android-local`：`SocketChannel.open(UnixDomainSocketAddress)`
 * 方法在、运行时不通，见 `docs/STATUS.md` §3.10.50）。桌面 JVM 上两者都在，
 * 选谁都行（测试里走 android 替身 —— 它内部就是真 unix socket，能与服务端互操作）。
 */
object Clients {
    fun open(path: String, caps: Capabilities = Capabilities.detect()): Duplex {
        val reasons = mutableListOf<String>()
        if (caps.androidLocal) {
            try {
                return AndroidLocalTransport.connect(path)
            } catch (t: Throwable) {
                reasons += "${AndroidLocalTransport.NAME}：${describe(t)}"
            }
        } else {
            reasons += "${AndroidLocalTransport.NAME}：本机没有 android.net.LocalSocket"
        }
        if (caps.nioUnixClient) {
            try {
                return OpenClient.nioClient(path)
            } catch (t: Throwable) {
                reasons += "nio-unix：${describe(t)}"
            }
        } else {
            reasons += "nio-unix：本机没有 SocketChannel.open(SocketAddress)"
        }
        throw IllegalStateException("没有可用的控制面客户端（${reasons.joinToString("；")}）")
    }
}

/** 一次请求-响应（协议：一行 JSON 进、一行 JSON 出；连接可复用）。 */
class ControlClient private constructor(private val duplex: Duplex) : Closeable {

    fun rpc(frame: String): String {
        duplex.writer.write(frame)
        duplex.writer.write("\n")
        duplex.writer.flush()
        return duplex.reader.readLine()
            ?: throw IllegalStateException("内核没回话（连接被关掉）")
    }

    override fun close() {
        duplex.close()
    }

    companion object {
        fun connect(path: String, caps: Capabilities = Capabilities.detect()): ControlClient =
            ControlClient(Clients.open(path, caps))
    }
}

/**
 * 控制面**回环自检**：连自己、发一帧 `ping`、读回一帧。
 *
 * 为什么要有它：P1 这一轮真机验证最贵的一步是"装模块 + 重启"，而上一轮交付的六条清单里，
 * ①③ 只能回答"内核起没起来"，回答不了"**通道选中的那条真的能用吗**"。
 * 自检把这句话变成一个可以 `cat` 的结论（`run/control-selftest`）。
 *
 * 结果格式：`ok:<通道名>[；<其它通道> 失败：<原因>]` / `fail:<每条通道的失败原因>`。
 * **所有**通道都会被试一遍并把结果写进结论 —— 2026-09-18 就是靠"每个通道都试"才发现
 * "真机上 java.nio 的 unix 客户端也不可用"（§3.10.50）。
 */
object ControlSelfTest {

    fun run(
        path: String,
        caps: Capabilities = Capabilities.detect(),
        log: (String) -> Unit = {},
    ): String {
        // 顺序 = 生产使用顺序：真机上 android-local 是唯一可用的（§3.10.50）
        val clients = mutableListOf<Pair<String, (String) -> Duplex>>()
        if (caps.androidLocal) {
            clients += AndroidLocalTransport.NAME to { p: String -> AndroidLocalTransport.connect(p) }
        }
        if (caps.nioUnixClient) clients += "nio-unix" to { p: String -> OpenClient.nioClient(p) }

        var winner: String? = null
        val notes = mutableListOf<String>()
        for ((name, open) in clients) {
            try {
                open(path).use { d ->
                    d.writer.write("{\"op\":\"ping\"}\n")
                    d.writer.flush()
                    val line = d.reader.readLine()
                    if (line != null && line.contains("pong")) {
                        if (winner == null) winner = name
                    } else {
                        notes += "$name 失败：回了 ${line ?: "<连接被对方关掉>"}"
                    }
                }
            } catch (t: Throwable) {
                notes += "$name 失败：${describe(t)}"
            }
        }
        val w = winner
        if (w != null) {
            val tail = if (notes.isEmpty()) "" else "；" + notes.joinToString("；")
            return "ok:$w$tail"
        }
        val tail = if (notes.isEmpty()) "没有可用的客户端通道" else notes.joinToString("；")
        log("[sunsetd] 控制面回环自检失败：$tail")
        return "fail：$tail"
    }

    /**
     * 异步跑（内核主循环一秒都不能被自检挡住）：先把 `running` 落盘，
     * 有结论再覆盖 —— 这样"没结论"本身也是一条信息。
     */
    fun runAsyncAndRecord(path: String, record: (String) -> Unit) {
        record("running")
        Thread {
            val result = try {
                run(path)
            } catch (t: Throwable) {
                "fail：${describe(t)}"
            }
            record(result)
        }.apply {
            isDaemon = true
            name = "sunsetd-selftest"
            start()
        }
    }
}

/** 把反射包出来的异常还原成"能读的一句话"（日志里要的是根因，不是 InvocationTargetException）。 */
internal fun describe(t: Throwable): String {
    var e = t
    var guard = 0
    while (e is InvocationTargetException && e.targetException != null && guard++ < 8) {
        e = e.targetException
    }
    val msg = e.message?.takeIf { it.isNotBlank() } ?: ""
    return if (msg.isEmpty()) e.javaClass.simpleName else "${e.javaClass.simpleName}：$msg"
}
