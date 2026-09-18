package android.net

import java.io.FileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.IdentityHashMap

/**
 * **测试替身**：模拟真机上的 `android.net.LocalSocket` / `LocalServerSocket`。
 *
 * 为什么需要它：内核（`:sunsetd`）跑在**纯 JVM** 上，而真机通道走的是 android.net 的公开 API，
 * 代码里用反射调用（见 `AndroidLocalTransport.kt`）。反射那一段——类名、方法名、构造器签名、
 * 参数顺序、close 的归属——**是整条链上最容易写错、又最难在桌面发现的部分**；
 * 而真机验证的代价是"装模块 + 重启"（用户操作）。
 * 所以在这里按 AOSP 源码（`LocalSocket.java` / `LocalSocketImpl.java` / `LocalServerSocket.java`）
 * 的语义做一个替身，让反射管道能在 JVM 单测里整条跑起来。
 *
 * 替身**只覆盖内核用到的那一面**，且刻意保留三个关键语义（正是内核依赖的）：
 *  1. `LocalSocket()` 先 `bind()` → 得到一个**已绑定**的 fd；
 *  2. `LocalServerSocket(fd)` 接管这个 fd 并 `listen`（真正的 listen 由 NIO 的 bind 代劳，
 *     对内核而言"accept 前必须 listen 过"这一点一致）；
 *  3. `LocalServerSocket.close()` **不关** fd（AOSP 注释：fd 由调用方管理），
 *     关 fd 的是创建它的 `LocalSocket`，只有这样阻塞中的 `accept()` 才会被解开。
 *
 * 真机 API 语义本身不靠这个替身保证 —— 那部分是**按真机 `framework.jar` 的 dex 逐条核对**
 * 的（`docs/STATUS.md` §3.10.48 有清单）。
 */
private val FD_TO_CHANNEL = IdentityHashMap<FileDescriptor, ServerSocketChannel>()

class LocalSocketAddress(val name: String, val namespace: Namespace = Namespace.ABSTRACT) {

    enum class Namespace(private val id: Int) {
        ABSTRACT(0),
        FILESYSTEM(1),
        ;

        fun getId(): Int = id
    }
}

class LocalSocket {

    private var client: SocketChannel? = null
    private var listener: ServerSocketChannel? = null
    private var fd: FileDescriptor? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    fun bind(bindpoint: LocalSocketAddress) {
        val ch = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        ch.bind(UnixDomainSocketAddress.of(bindpoint.name))
        listener = ch
        fd = FileDescriptor()
        FD_TO_CHANNEL[fd] = ch
    }

    fun connect(endpoint: LocalSocketAddress) {
        val ch = SocketChannel.open(UnixDomainSocketAddress.of(endpoint.name))
        client = ch
        fd = FileDescriptor()
        input = Channels.newInputStream(ch)
        output = Channels.newOutputStream(ch)
    }

    fun getFileDescriptor(): FileDescriptor = fd ?: throw IllegalStateException("socket not created")

    fun getInputStream(): InputStream = input ?: throw IllegalStateException("not connected")

    fun getOutputStream(): OutputStream = output ?: throw IllegalStateException("not connected")

    fun close() {
        client?.let { runCatching { it.close() } }
        listener?.let { runCatching { it.close() } }
        fd?.let { FD_TO_CHANNEL.remove(it) }
        client = null
        listener = null
        input = null
        output = null
    }

    companion object {
        internal fun channelOf(fd: FileDescriptor): ServerSocketChannel? = FD_TO_CHANNEL[fd]

        /** 等价于 AOSP 的 `LocalSocket.createLocalSocketForAccept(impl)`。 */
        internal fun adopt(ch: SocketChannel): LocalSocket = LocalSocket().also {
            it.client = ch
            it.fd = FileDescriptor()
            it.input = Channels.newInputStream(ch)
            it.output = Channels.newOutputStream(ch)
        }
    }
}

class LocalServerSocket(fd: FileDescriptor) : java.io.Closeable {

    private val ch: ServerSocketChannel =
        LocalSocket.channelOf(fd) ?: throw IllegalStateException("fd 不是已绑定的 socket")

    fun accept(): LocalSocket = LocalSocket.adopt(ch.accept())

    /** 与 AOSP 一致：外部传入的 fd 不归本类管（关它的是创建者）。 */
    override fun close() {}
}
