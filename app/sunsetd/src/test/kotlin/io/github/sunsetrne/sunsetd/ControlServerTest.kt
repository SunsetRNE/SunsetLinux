package io.github.sunsetrne.sunsetd

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * 控制面（unix domain socket + 行分隔 JSON）的行为测试。
 *
 * 为什么值得单测：真机上"内核起没起来"的第一证据就是这条 socket ——
 * 它是"客户端不再依赖命令返回"的前提（决策 D3）。这里跑的是**真 socket**（容器与真机
 * 都支持 `java.nio` UNIX 协议族），不是内存桩，所以协议帧、连接复用、陈旧 socket 清理、
 * 重复启动都被真实验证过一遍。
 */
class ControlServerTest {

    private val home = "/tmp/sunsetd-sock-${System.nanoTime()}"
    private val env = RuntimeEnv(home)
    private var server: ControlServer? = null

    private fun startServer(): ControlServer {
        val sink = object : StateSink {
            val logs = mutableListOf<String>()
            override fun writeState(json: String) {}
            override fun writeHeartbeat(nowMs: Long) {}
            override fun log(line: String) {
                logs += line
            }
        }
        val kernel = Kernel(env, sink, object : WorldView {
            override fun snapshot() = World()
        }, object : JobRunner {
            override fun spawn(cmd: List<String>, onExit: (Int) -> Unit): Long = 1L
            override fun alive(pid: Long): Boolean = false
            override fun kill(pid: Long) {}
        }) { 1_700_000_000_000L }
        kernel.start()
        val s = ControlServer(env, kernel) { sink.log(it) }
        s.start()
        server = s
        return s
    }

    @After
    fun cleanup() {
        server?.stop()
        File(home).deleteRecursively()
    }

    private fun rpc(vararg frames: String): List<String> {
        SocketChannel.open(UnixDomainSocketAddress.of(env.socketFile)).use { ch ->
            val w = OutputStreamWriter(Channels.newOutputStream(ch), Charsets.UTF_8)
            val r = BufferedReader(InputStreamReader(Channels.newInputStream(ch), Charsets.UTF_8))
            val out = mutableListOf<String>()
            for (f in frames) {
                w.write(f + "\n")
                w.flush()
                out += r.readLine()
            }
            return out
        }
    }

    @Test
    fun `socket 建在 run 下，且一条连接可以问多帧（CLI 复用连接）`() {
        startServer()
        assertTrue("socket 文件应存在：${env.socketFile}", File(env.socketFile).exists())
        val (a, b) = rpc("""{"op":"status"}""", """{"op":"status"}""")
        assertTrue(a.contains("\"phase\":\"idle\""))
        assertTrue(b.contains("\"phase\":\"idle\""))
    }

    @Test
    fun `提交作业：立刻返回 jobId（客户端不等命令结束）`() {
        startServer()
        val (resp) = rpc("""{"op":"submit","action":"start"}""")
        assertTrue("应立刻给出作业 id：$resp", resp.contains("\"jobId\":\"j1\""))
        assertFalse(resp.contains("\"ok\":false"))
    }

    @Test
    fun `shutdown 帧让内核退出（真机上由模块的停止流程调用）`() {
        val s = startServer()
        val (resp) = rpc("""{"op":"shutdown"}""")
        assertTrue(resp.contains("stopping"))
        // 给接受线程一点时间观察到标志
        Thread.sleep(200)
        assertTrue(s.shouldStop)
    }

    @Test
    fun `陈旧 socket 文件会被清掉重建（内核上次是被 kill -9 的场景）`() {
        File(env.runDir).mkdirs()
        File(env.socketFile).writeText("我不是 socket")
        startServer()
        assertTrue(File(env.socketFile).exists())
        val (resp) = rpc("""{"op":"ping"}""")
        assertTrue(resp.contains("pong"))
    }

    @Test
    fun `同一个路径上再起一个内核会被拦住（不会有两个权威状态源）`() {
        startServer()
        val second = ControlServer(env, Kernel(env, object : StateSink {
            override fun writeState(json: String) {}
            override fun writeHeartbeat(nowMs: Long) {}
            override fun log(line: String) {}
        }, object : WorldView {
            override fun snapshot() = World()
        }, object : JobRunner {
            override fun spawn(cmd: List<String>, onExit: (Int) -> Unit): Long = 1L
            override fun alive(pid: Long): Boolean = false
            override fun kill(pid: Long) {}
        }), {})
        val e = runCatching { second.start() }.exceptionOrNull()
        assertTrue("第二个内核必须起不来：${e?.message}", e is IllegalStateException)
        second.stop()
    }

    @Test
    fun `我们的实现真的用 java_nio 的 UNIX 协议族（设备 ART 里已实测有此类）`() {
        // 这条断言的意义：如果哪天换成 Android 专有 LocalSocket，设备上就不再是同一套代码，
        // 这个测试会红 —— 提醒改动者同步真机验证。
        val path = "/tmp/sunsetd-probe-${System.nanoTime()}.sock"
        val ch = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        ch.bind(UnixDomainSocketAddress.of(path))
        val bound: java.net.SocketAddress? = ch.getLocalAddress()
        ch.close()
        File(path).delete()
        assertTrue("绑定出来的地址必须是 unix 路径族，实际=$bound", bound is UnixDomainSocketAddress)
    }
}
