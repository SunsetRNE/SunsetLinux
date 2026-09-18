package io.github.sunsetrne.sunsetd

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 通道选择与回环自检。
 *
 * 为什么这几条测试值钱：2026-09-18 真机上"内核起来了、控制面没起来"这个事故，
 * 根因是**桌面 JVM 与真机 ART 的 API 面不一样**（真机 `ServerSocketChannel` 没有
 * `open(ProtocolFamily)`，SDK 桩里却有）。所以判据不能只靠"在桌面上跑一遍" ——
 * 这里用**注入的能力**把真机情形搬进 JVM 单测，选择逻辑一旦被改坏就会红。
 */
class TransportTest {

    private val fakeAndroid = object : ControlListener {
        override val name = "android-local"
        override fun accept(): Duplex? = null
        override fun close() {}
    }

    private val path = "/tmp/sunsetd-transport-${System.nanoTime()}/control.sock"

    @After
    fun cleanup() {
        File(path).parentFile?.deleteRecursively()
    }

    @Test
    fun `真机情形（java_nio 缺 UNIX 服务端）⇒ 选择器改走 android-local，并说清为什么`() {
        val logs = mutableListOf<String>()
        val pick = Transports.pick(
            path = path,
            caps = Capabilities(nioUnixServer = false, nioUnixClient = true, androidLocal = true),
            openNio = { error("nio 服务端不可用时不该调用它的工厂") },
            openAndroid = { fakeAndroid },
            log = { logs += it },
        )
        assertTrue("真机上必须能选到 android-local：$pick", pick is Pick.Ok)
        assertEquals("android-local", (pick as Pick.Ok).listener.name)
        assertTrue(
            "日志里要有一句能对上真机日志的原因：$logs",
            logs.any { it.contains("open(ProtocolFamily)") },
        )
    }

    @Test
    fun `两条通道都没有 ⇒ 失败原因两条都在（doctor 与交付说明要能直接读）`() {
        val pick = Transports.pick(
            path = path,
            caps = Capabilities(nioUnixServer = false, nioUnixClient = false, androidLocal = false),
            openNio = { error("不该调用") },
            openAndroid = { error("不该调用") },
            log = {},
        )
        assertTrue(pick is Pick.None)
        val reason = (pick as Pick.None).reason
        assertTrue("原因里要有 nio-unix：$reason", reason.contains("nio-unix"))
        assertTrue("原因里要有 android-local：$reason", reason.contains("android-local"))
    }

    @Test
    fun `nio 抢不到 socket（已被占用）时，会自动退到 android-local`() {
        val pick = Transports.pick(
            path = path,
            caps = Capabilities(nioUnixServer = true, nioUnixClient = true, androidLocal = true),
            openNio = { throw IllegalStateException("control.sock 已被占用") },
            openAndroid = { fakeAndroid },
            log = {},
        )
        assertTrue("第一条失败必须继续试下一条：$pick", pick is Pick.Ok)
        assertEquals("android-local", (pick as Pick.Ok).listener.name)
    }

    @Test
    fun `本机能力探测：nio 服务端与客户端都在（android_net 只在替身在场时为真）`() {
        val caps = Capabilities.detect()
        assertTrue("桌面 JVM 应当有 ServerSocketChannel.open(ProtocolFamily)", caps.nioUnixServer)
        assertTrue("桌面 JVM 应当有 SocketChannel.open(SocketAddress)", caps.nioUnixClient)
        // ★ androidLocal 在单测里恒为 true（测试替身 android.net.LocalSocket 在 test 源集里）。
        //   所以"真机走哪条通道"不能用这个环境判定 —— 那要靠真机 dex 静态核对 + 开机自检，
        //   见 docs/STATUS.md §3.10.48。
    }

    @Test
    fun `android-local 通道（测试替身）：反射全程走通、能收发、socket 收到 0600`() {
        val home = "/tmp/sunsetd-android-${System.nanoTime()}"
        val path = "$home/control.sock"
        File(home).mkdirs()
        val listener = AndroidLocalTransport.listen(path)
        assertEquals("android-local", listener.name)
        assertTrue("socket 文件应当存在", File(path).exists())
        assertEquals(
            "socket 必须是 0600（只有 root 能连）",
            "rw-------",
            java.nio.file.attribute.PosixFilePermissions.toString(
                java.nio.file.Files.getPosixFilePermissions(File(path).toPath()),
            ),
        )

        val received = LinkedBlockingQueue<String>()
        Thread {
            val d = listener.accept() ?: return@Thread
            d.use {
                received += it.reader.readLine() ?: "<null>"
                it.writer.write("{\"ok\":true}\n")
                it.writer.flush()
            }
        }.apply { isDaemon = true; start() }

        AndroidLocalTransport.connect(path).use { client ->
            client.writer.write("{\"op\":\"ping\"}\n")
            client.writer.flush()
            assertTrue("客户端应当收到回复", client.reader.readLine()?.contains("ok") == true)
        }
        assertEquals("服务端应当收到整帧", "{\"op\":\"ping\"}", received.poll(5, TimeUnit.SECONDS))

        listener.close()
        assertFalse("close 应当删掉 socket 文件", File(path).exists())
        File(home).deleteRecursively()
    }

    @Test
    fun `android-local 的 close 会解开阻塞中的 accept（否则停内核会挂住）`() {
        val home = "/tmp/sunsetd-android-close-${System.nanoTime()}"
        val path = "$home/control.sock"
        File(home).mkdirs()
        val listener = AndroidLocalTransport.listen(path)
        val returned = CountDownLatch(1)
        Thread {
            try {
                listener.accept()
            } catch (_: Throwable) {
            } finally {
                returned.countDown()
            }
        }.apply { isDaemon = true; start() }
        Thread.sleep(300)
        listener.close()
        assertTrue("close 之后阻塞的 accept 必须返回", returned.await(5, TimeUnit.SECONDS))
        File(home).deleteRecursively()
    }

    @Test
    fun `选择器的探测口径：判据就是 ServerSocketChannel_open(ProtocolFamily)`() {
        // 真机 ART 的 core-oj.jar 里**没有**这个方法（逐字节核对见 docs/STATUS.md §3.10.48），
        // 而 SDK 桩里有。Capabilities.detect() 用的判据写死在这里：谁改判据就来改这条。
        val m = java.nio.channels.ServerSocketChannel::class.java
            .getMethod("open", java.net.ProtocolFamily::class.java)
        assertTrue("必须是静态工厂", java.lang.reflect.Modifier.isStatic(m.modifiers))
    }

    @Test
    fun `回环自检：真 socket 上 ping 得到 pong，结论是 ok-nio-unix`() {
        val home = "/tmp/sunsetd-selftest-${System.nanoTime()}"
        val env = RuntimeEnv(home)
        val kernel = Kernel(env, object : StateSink {
            override fun writeState(json: String) {}
            override fun writeHeartbeat(nowMs: Long) {}
            override fun log(line: String) {}
        }, object : WorldView {
            override fun snapshot() = World()
        }, object : JobRunner {
            override fun spawn(cmd: List<String>, onExit: (Int) -> Unit): Long = 1L
            override fun alive(pid: Long): Boolean = false
            override fun kill(pid: Long) {}
        }) { 1_700_000_000_000L }
        kernel.start()
        val server = ControlServer(env, kernel) {}
        try {
            server.start()
            assertEquals("nio-unix", server.transportName)
            val result = ControlSelfTest.run(env.socketFile)
            assertEquals("ok:nio-unix", result)
        } finally {
            server.stop()
            File(home).deleteRecursively()
        }
    }
}
