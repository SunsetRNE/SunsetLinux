package io.github.sunsetrne.sunsetd

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileDescriptor
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.lang.reflect.Method

/**
 * 真机（Android ART）上的控制面通道：`android.net.LocalSocket` + `LocalServerSocket`。
 *
 * ## 为什么是这一套，而不是继续用 java.nio
 *
 * 真机的 `core-oj.jar` 里 `ServerSocketChannel` 只有 `open()`，**没有** `open(ProtocolFamily)`
 * ⇒ `ServerSocketChannel.open(StandardProtocolFamily.UNIX)` 在真机上必然抛 NoSuchMethodError
 * （2026-09-18 真机日志原文，见 `docs/STATUS.md` §3.10.48）。而 Android 自己的
 * `LocalSocket` 家族是**公开 SDK API**，且能绑到**文件系统路径**（不是只有抽象命名空间）：
 *
 * ```java
 * LocalSocket s = new LocalSocket();                       // AF_UNIX / SOCK_STREAM
 * s.bind(new LocalSocketAddress(path, Namespace.FILESYSTEM));
 * LocalServerSocket server = new LocalServerSocket(s.getFileDescriptor());  // 内部调 listen(50)
 * ```
 *
 * 依据（都按 AOSP 源码核对过，不是猜的）：
 *  - `LocalSocketImpl.bind()` → `bindLocal(fd, name, namespace)`，namespace 由原生按
 *    `FILESYSTEM`/`ABSTRACT` 分别处理 ⇒ 文件路径命名空间可用；
 *  - `LocalServerSocket(FileDescriptor)`：`impl.listen(50)`，注释明说"fd 由调用方管理"；
 *  - `LocalSocketImpl.accept()` → `Os.accept(fd, null)`；
 *  - `LocalSocketImpl.close()` 对"外部传入的 fd"**不关 fd** ⇒ 谁创建谁关：本类自己持有
 *    那个绑定的 `LocalSocket`（它才拥有 fd），关它才能解开阻塞中的 `accept()`。
 *
 * ## 为什么用反射
 *
 * `:sunsetd` 是**纯 JVM 模块**（离线构建、单测都在桌面 JVM 上跑），编译期没有 android.jar；
 * 反射让"本机没有 android.net.*"变成一条**可处理的正常分支**（桌面就走不到这里），
 * 而不是给构建加一个 SDK 路径依赖。
 */
object AndroidLocalTransport {

    const val NAME: String = "android-local"

    const val LOCAL_SOCKET: String = "android.net.LocalSocket"
    private const val LOCAL_SOCKET_ADDRESS = "android.net.LocalSocketAddress"
    private const val LOCAL_SOCKET_NAMESPACE = "android.net.LocalSocketAddress\$Namespace"
    private const val LOCAL_SERVER_SOCKET = "android.net.LocalServerSocket"
    private const val OS = "android.system.Os"

    /** 0600：只有 root 能连（与 nio 通道那条 `setPosixFilePermissions` 对齐）。 */
    private const val MODE_0600 = 0x180

    fun present(): Boolean = try {
        Class.forName(LOCAL_SOCKET)
        true
    } catch (_: Throwable) {
        false
    }

    /** 起监听（服务端半边）。文件路径命名空间 + 0600 + 陈旧 socket 清理。 */
    fun listen(path: String): ControlListener {
        val socketCls = Class.forName(LOCAL_SOCKET)
        val addressCls = Class.forName(LOCAL_SOCKET_ADDRESS)

        val file = File(path)
        if (file.exists() && !canConnect(path)) file.delete() // 陈旧 socket（上次被 kill -9）
        if (canConnect(path)) {
            throw IllegalStateException("control.sock 已被占用（另一个 sunsetd 在跑？）：$path")
        }
        file.parentFile?.mkdirs()

        // LocalSocket()（SOCKET_STREAM）→ bind(FILESYSTEM 路径)
        val binder = socketCls.getConstructor().newInstance()
        socketCls.getMethod("bind", addressCls).invoke(binder, address(path))

        val fd = socketCls.getMethod("getFileDescriptor").invoke(binder) as FileDescriptor
        val serverCls = Class.forName(LOCAL_SERVER_SOCKET)
        val server = serverCls.getConstructor(FileDescriptor::class.java).newInstance(fd)

        // 权限收紧失败不是致命（与 nio 通道同一口径：记一行、继续）
        try {
            Class.forName(OS)
                .getMethod("chmod", String::class.java, Int::class.javaPrimitiveType)
                .invoke(null, path, MODE_0600)
        } catch (_: Throwable) {
        }

        return AndroidLocalListener(
            binder = binder,
            server = server,
            accept = serverCls.getMethod("accept"),
            closeBinder = socketCls.getMethod("close"),
            closeServer = serverCls.getMethod("close"),
            path = path,
        )
    }

    /** 客户端半边（自检用；以后 CLI/App 也可以走这条）。 */
    fun connect(path: String): Duplex {
        val socketCls = Class.forName(LOCAL_SOCKET)
        val socket = socketCls.getConstructor().newInstance()
        try {
            socketCls.getMethod("connect", Class.forName(LOCAL_SOCKET_ADDRESS))
                .invoke(socket, address(path))
        } catch (t: Throwable) {
            runCatching { socketCls.getMethod("close").invoke(socket) }
            throw t
        }
        return AndroidDuplex(socket)
    }

    private fun canConnect(path: String): Boolean = try {
        connect(path).close()
        true
    } catch (_: Throwable) {
        false
    }

    private fun address(path: String) = Class.forName(LOCAL_SOCKET_ADDRESS)
        .getConstructor(String::class.java, Class.forName(LOCAL_SOCKET_NAMESPACE))
        .newInstance(path, Class.forName(LOCAL_SOCKET_NAMESPACE).getField("FILESYSTEM").get(null))
}

private class AndroidLocalListener(
    private val binder: Any,
    private val server: Any,
    private val accept: Method,
    private val closeBinder: Method,
    private val closeServer: Method,
    private val path: String,
) : ControlListener {

    override val name: String = AndroidLocalTransport.NAME

    override fun accept(): Duplex? {
        val client = try {
            accept.invoke(server)
        } catch (t: Throwable) {
            // 关监听时阻塞中的 accept 会以 IOException 返回 —— 交给上层按 stopFlag 判断
            throw IllegalStateException(describe(t), t)
        } ?: return null
        return AndroidDuplex(client)
    }

    override fun close() {
        // ★ 顺序要紧：fd 是 binder（LocalSocket）创建的，它才是 fd 的属主。
        //   先关它才能解开 accept()；LocalServerSocket 对外部 fd 不负责关闭（AOSP 注释明说）。
        try {
            closeBinder.invoke(binder)
        } catch (_: Throwable) {
        }
        try {
            closeServer.invoke(server)
        } catch (_: Throwable) {
        }
        try {
            File(path).delete()
        } catch (_: Throwable) {
        }
    }
}

private class AndroidDuplex(private val socket: Any) : Duplex {

    override val reader: BufferedReader = BufferedReader(
        InputStreamReader(stream("getInputStream") as InputStream, Charsets.UTF_8),
    )

    override val writer: BufferedWriter = BufferedWriter(
        OutputStreamWriter(stream("getOutputStream") as OutputStream, Charsets.UTF_8),
    )

    override fun close() {
        try {
            socket.javaClass.getMethod("close").invoke(socket)
        } catch (_: Throwable) {
        }
    }

    private fun stream(name: String): Any =
        socket.javaClass.getMethod(name).invoke(socket)
            ?: throw IllegalStateException("android LocalSocket.$name() 返回了 null")
}
