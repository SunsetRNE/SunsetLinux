package io.github.sunsetrne.sunsetd

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * 宿主侧客户端 [Ctl] 的行为测试。
 *
 * 为什么值钱：P2 里 **App 只能通过 `su -c … Ctl …` 访问控制面**（socket 是 0600 root，
 * App 进程连不上，见 `docs/STATUS.md` §3.10.50）。所以这个客户端的退出码与 stdout 格式
 * 就是 App 与脚本的**接口**：退出码错了，App 会把"参数写错"报成"内核没起来"，
 * 用户就会去修一个本来就对的状态（这个仓在这类假故障上踩过不止一次）。
 *
 * 这里跑的是**真 socket**（ControlServer + 真连接），不是内存桩。
 */
class CtlTest {

    private val home = "/tmp/sunsetd-ctl-${System.nanoTime()}"
    private val env = RuntimeEnv(home)
    private var server: ControlServer? = null

    private fun startKernel(finishJobImmediately: Boolean = false) {
        val sink = object : StateSink {
            override fun writeState(json: String) {}
            override fun writeHeartbeat(nowMs: Long) {}
            override fun log(line: String) {}
        }
        val jobs = object : JobRunner {
            override fun spawn(cmd: List<String>, onExit: (Int) -> Unit): Long {
                if (finishJobImmediately) onExit(0)
                return 4242L
            }

            override fun alive(pid: Long): Boolean = false
            override fun kill(pid: Long) {}
        }
        val kernel = Kernel(env, sink, object : WorldView {
            override fun snapshot() = World()
        }, jobs) { 1_700_000_000_000L }
        kernel.start()
        val s = ControlServer(env, kernel) {}
        s.start()
        server = s
    }

    @After
    fun cleanup() {
        server?.stop()
        File(home).deleteRecursively()
    }

    /** 跑一次 Ctl，返回 (退出码, stdout, stderr)。默认 connect-wait=0（测试里不等）。 */
    private fun ctl(vararg args: String, connectWait: Int = 0): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = Ctl.run(
            listOf("--connect-wait", "$connectWait") + args.toList(),
            PrintStream(out, true, "UTF-8"),
            PrintStream(err, true, "UTF-8"),
            env,
        )
        return Triple(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    @Test
    fun `status：退出码 0，stdout 是内核回的那一行 JSON`() {
        startKernel()
        val (code, out, err) = ctl("status")
        assertEquals("stderr=$err", 0, code)
        assertTrue(out, out.contains("\"ok\":true"))
        assertTrue(out, out.contains("\"phase\":\"idle\""))
    }

    @Test
    fun `submit：stdout 里能拿到 jobId`() {
        startKernel()
        val (code, out, err) = ctl("submit", "start")
        assertEquals("stderr=$err", 0, code)
        assertTrue(out, out.contains("\"jobId\":\"j1\""))
    }

    @Test
    fun `wait：作业结束后退出码 0，且 stdout 只有最后一行（脚本要能直接解析）`() {
        startKernel(finishJobImmediately = true)
        ctl("submit", "start")
        val (code, out, _) = ctl("wait", "j1", "5")
        assertEquals(0, code)
        assertEquals("stdout 应当只有一行：$out", 1, out.trim().lines().size)
        assertTrue(out, out.contains("\"finished\":true"))
    }

    @Test
    fun `wait 超时 ⇒ 退出码 5（作业还在跑时脚本要能区分"没结束"与"失败"）`() {
        startKernel() // 假 runner 不回调 ⇒ 作业永不结束
        ctl("submit", "start")
        val (code, out, err) = ctl("wait", "j1", "1")
        assertEquals(5, code)
        assertTrue(err, err.contains("超时"))
        assertTrue(out, out.contains("\"finished\":false"))
    }

    @Test
    fun `参数错在连内核之前就判掉（否则会被报成"连不上内核"）`() {
        // 故意**不**启内核：参数错的退出码必须是 2，而不是 3
        val (c1, o1, e1) = ctl("submit")
        assertEquals(2, c1)
        assertEquals("", o1.trim())
        assertTrue(e1, e1.contains("用法"))

        val (c2, o2, _) = ctl("不存在的子命令")
        assertEquals(2, c2)
        assertEquals("", o2.trim())
    }

    @Test
    fun `内核不在 ⇒ 退出码 3，错误进 stderr、stdout 保持干净`() {
        val (code, out, err) = ctl("status")
        assertEquals(3, code)
        assertEquals("", out.trim())
        assertTrue(err, err.contains("连不上内核控制面"))
    }

    @Test
    fun `内核回了错误 ⇒ 退出码 4（stdout 保留原话，stderr 说明）`() {
        startKernel()
        val (code, out, err) = ctl("raw", "{\"op\":\"不存在的操作\"}")
        assertEquals(4, code)
        assertTrue(out, out.contains("\"ok\":false"))
        assertTrue(err, err.contains("内核回了错误"))
    }

    @Test
    fun `不认识的 action ⇒ 退出码 4 且带上内核的 bad-action`() {
        startKernel()
        val (code, out, _) = ctl("submit", "乱来")
        assertEquals(4, code)
        assertTrue(out, out.contains("bad-action"))
    }
}
