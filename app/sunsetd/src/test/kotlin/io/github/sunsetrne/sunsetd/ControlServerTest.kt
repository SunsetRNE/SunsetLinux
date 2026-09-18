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
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel

/**
 * 控制面（unix domain socket + 行分隔 JSON）的行为测试。
 *
 * 为什么值得单测：真机上"内核起没起来"的第一证据就是这条 socket ——
 * 它是"客户端不再依赖命令返回"的前提（决策 D3）。这里跑的是**真 socket**（桌面 JVM 走
 * `java.nio` 的 UNIX 协议族，即 `nio-unix` 通道），所以协议帧、连接复用、陈旧 socket 清理、
 * 重复启动都被真实验证过一遍。
 *
 * ★ 注意"真机上也一样"这句话在 2026-09-18 被证伪过：真机 ART **没有** `java.nio` 的
 * UNIX 服务端入口，通道选择与真机情形见 [TransportTest] 与 `docs/STATUS.md` §3.10.48。
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
        }), log = {})
        val e = runCatching { second.start() }.exceptionOrNull()
        assertTrue("第二个内核必须起不来：${e?.message}", e is IllegalStateException)
        second.stop()
    }

    @Test
    fun `协议帧：未知 op 走内核的兜底回复，不会把连接搞断`() {
        startServer()
        val (a, b) = rpc("""{"op":"不存在的操作"}""", """{"op":"ping"}""")
        assertTrue("未知 op 要有可读回复：$a", a.isNotBlank())
        assertTrue("后面的帧还得能问：$b", b.contains("pong"))
    }
}
