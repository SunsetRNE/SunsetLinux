package io.github.sunsetrne.sunsetd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内核状态机 / 作业 / 看护的穷举单测（**全部用假世界与假执行者**，不需要真机）。
 *
 * 这些断言对应的都是真机上出过的具体事故：
 *  · "建树期间状态不可见" → 用户连点两下 ⇒ 两棵挂载树（第 3 条）；
 *  · "命令没返回就一直灰" → 启动区五张卡片全灰（第 1 条）；
 *  · "不是内核起的会话被当成没在跑" → 模块开机自启的那条会被重复启动。
 */
class KernelTest {

    // ── 假件：世界是一张可改的表；执行者只记命令、不真起进程

    private class FakeWorld(var w: World = World()) : WorldView {
        override fun snapshot(): World = w
    }

    private class FakeRunner : JobRunner {
        val spawned = mutableListOf<List<String>>()
        private val exits = mutableMapOf<Long, (Int) -> Unit>()
        private var nextPid = 5000L
        override fun spawn(cmd: List<String>, onExit: (Int) -> Unit): Long {
            spawned += cmd
            val pid = nextPid++
            exits[pid] = onExit
            return pid
        }

        fun exit(pid: Long, code: Int) {
            exits.remove(pid)?.invoke(code)
        }

        override fun alive(pid: Long): Boolean = exits.containsKey(pid)
        override fun kill(pid: Long) {}
    }

    private class FakeSink : StateSink {
        val states = mutableListOf<String>()
        val heartbeats = mutableListOf<Long>()
        val logs = mutableListOf<String>()
        override fun writeState(json: String) {
            states += json
        }

        override fun writeHeartbeat(nowMs: Long) {
            heartbeats += nowMs
        }

        override fun log(line: String) {
            logs += line
        }
    }

    private var now = 1_700_000_000_000L

    /** 每个内核用**独立临时目录**：否则上一个测试留下的 state.json 会被下一个读回来（踩过）。 */
    private fun freshHome(): String {
        val d = java.io.File("/tmp/sunsetd-test-${System.nanoTime()}")
        d.mkdirs()
        return d.path
    }

    private fun kernel(
        world: FakeWorld = FakeWorld(),
        runner: FakeRunner = FakeRunner(),
        sink: FakeSink = FakeSink(),
    ): Triple<Kernel, FakeWorld, FakeRunner> {
        val env = RuntimeEnv(freshHome())
        return Triple(Kernel(env, sink, world, runner) { now }, world, runner)
    }

    private fun runningWorld(mode: String = "full", port: Int = 3081, pid: Long = 4190) =
        World(ready = true, supervisorPid = pid, supervisorAlive = true, envMode = mode, dshPort = port)

    // ─────────────────────────────── 启动：以世界为准，不谎报

    @Test
    fun `环境没跑时内核报 idle，不编造任何会话事实`() {
        val (k, _, _) = kernel()
        k.start()
        assertEquals(Phase.IDLE, k.state().phase)
        assertNull(k.state().port)
        assertNull(k.state().envMode)
    }

    @Test
    fun `认领模块开机自启的会话：running + owner=foreign`() {
        val (k, _, _) = kernel(FakeWorld(runningWorld("env-only", port = 3081, pid = 3455)))
        k.start()
        val s = k.state()
        assertEquals(Phase.RUNNING, s.phase)
        assertEquals(Owner.FOREIGN, s.owner)
        assertEquals("env-only", s.envMode)
        assertEquals(3081, s.port)
        assertEquals(3455L, s.pid)
        assertTrue("认领的会话也要有世代（不能是 0）", s.generation >= 1L)
    }

    @Test
    fun `内核重启后：上次的忙相位不许继续冒充在跑`() {
        // 落盘的上次状态是 mounting，而世界里什么都没有（设备重启过）
        // 写一份"上次的忙状态"到文件（内核要从文件读回它）
        val env = RuntimeEnv(freshHome())
        java.io.File(env.runDir).mkdirs()
        java.io.File(env.stateFile).writeText(
            SessionState(generation = 9, phase = Phase.MOUNTING, since = 1L, jobId = "j9").toJson(),
        )
        val sink2 = FakeSink()
        val k2 = Kernel(env, sink2, FakeWorld(World()), FakeRunner()) { now }
        k2.start()
        assertEquals(Phase.FAILED, k2.state().phase)
        assertEquals(9L, k2.state().generation)
        assertTrue("要说清为什么失败", k2.state().lastError!!.contains("没有结束"))
    }

    // ─────────────────────────────── 作业：幂等与互斥

    @Test
    fun `提交 start 立刻返回 jobId，相位进入 preparing（不再等命令返回）`() {
        val (k, _, runner) = kernel()
        k.start()
        val (id, deduped) = k.submit(Kernel.ACTION_START_ENV_ONLY)
        assertEquals("j1", id)
        assertFalse(deduped)
        assertEquals(Phase.PREPARING, k.state().phase)
        assertEquals(id, k.state().jobId)
        assertEquals(Owner.KERNEL_JOB, k.state().owner)
        val cmd = runner.spawned.single()
        assertEquals("/system/bin/sh", cmd[0])
        assertTrue("内核调的必须还是那个契约路径：${cmd[1]}", cmd[1].endsWith("/bin/linuxctl"))
        assertEquals(listOf("start", "--no-dsh"), cmd.drop(2))
    }

    @Test
    fun `同一个动作重复提交是幂等的（用户连点不会起第二个作业 = 不会建第二棵树）`() {
        val (k, _, runner) = kernel()
        k.start()
        val (id1, _) = k.submit(Kernel.ACTION_START_ENV_ONLY)
        val (id2, deduped) = k.submit(Kernel.ACTION_START_ENV_ONLY)
        assertEquals(id1, id2)
        assertTrue("第二次必须被识别为重复", deduped)
        assertEquals("只允许起一条命令", 1, runner.spawned.size)
    }

    @Test
    fun `不同动作在跑时被拒绝（互斥），错误里带上在跑的作业`() {
        val (k, _, _) = kernel()
        k.start()
        k.submit(Kernel.ACTION_START)
        val e = runCatching { k.submit(Kernel.ACTION_STOP) }.exceptionOrNull()
        assertTrue(e is BusyException)
        assertEquals("j1", (e as BusyException).jobId)
        val json = k.handle("""{"op":"submit","action":"stop"}""")
        assertTrue("走协议也要给出可读原因：$json", json.contains("busy") && json.contains("j1"))
    }

    @Test
    fun `不认识的 action 被拒绝，并说清是什么`() {
        val (k, _, _) = kernel()
        k.start()
        val json = k.handle("""{"op":"submit","action":"teleport"}""")
        assertTrue(json.contains("bad-action"))
    }

    // ─────────────────────────────── 看护：相位跟着世界走

    @Test
    fun `建树期间相位是 mounting（App 的启动卡靠它置灰）`() {
        val world = FakeWorld(World(startLockHeld = true, startLockHolder = 9001))
        val (k, _, _) = kernel(world)
        k.start()
        k.submit(Kernel.ACTION_START)
        k.tick()
        assertEquals(Phase.MOUNTING, k.state().phase)
        assertTrue(k.state().phase.isBusy)
    }

    @Test
    fun `就绪之后转 running，端口从世界读（不再各处推导）`() {
        val world = FakeWorld(World(startLockHeld = true, startLockHolder = 9001))
        val (k, _, runner) = kernel(world)
        k.start()
        val (id, _) = k.submit(Kernel.ACTION_START)
        // 脚本把树建好了：锁没了、ready 在、supervisor 活着
        world.w = runningWorld("full", port = 3081, pid = 4672)
        k.tick()
        assertEquals(Phase.RUNNING, k.state().phase)
        assertEquals(3081, k.state().port)
        assertEquals(4672L, k.state().pid)
        // 作业还没退出时也算 running（这正是 v1 卡死的地方：命令不返回 ⇒ 按钮全灰）
        assertEquals(id, k.state().jobId)
        assertEquals(Owner.KERNEL_JOB, k.state().owner)
    }

    @Test
    fun `作业失败：相位落到 failed 并带上原因（不许悄悄回 idle）`() {
        val world = FakeWorld(World(startLockHeld = true, startLockHolder = 9001))
        val (k, _, runner) = kernel(world)
        k.start()
        k.submit(Kernel.ACTION_START)
        val pid = runner.spawned.size.let { 5000L }
        world.w = World(lastError = "overlay 挂载失败：Invalid argument")
        runner.exit(pid, 1)
        assertEquals(Phase.FAILED, k.state().phase)
        assertTrue(k.state().lastError!!.contains("overlay"))
    }

    @Test
    fun `停止：作业在跑时是 stopping，世界清空后回 idle`() {
        val world = FakeWorld(runningWorld())
        val (k, _, runner) = kernel(world)
        k.start()
        assertEquals(Phase.RUNNING, k.state().phase)
        k.submit(Kernel.ACTION_STOP)
        k.tick()
        assertEquals(Phase.STOPPING, k.state().phase)
        world.w = World()
        runner.exit(5000L, 0)
        assertEquals(Phase.IDLE, k.state().phase)
        assertNull(k.state().jobId)
    }

    @Test
    fun `别人的启动（foreign）也能被看见：相位 mounting + owner=foreign，但不算内核作业`() {
        val (k, _, _) = kernel(FakeWorld(World(startLockHeld = true, startLockHolder = 3455)))
        k.start()
        k.tick()
        assertEquals(Phase.MOUNTING, k.state().phase)
        assertEquals(Owner.FOREIGN, k.state().owner)
        assertNull("不是内核的作业", k.state().jobId)
    }

    // ─────────────────────────────── 落盘与协议

    @Test
    fun `状态只在内核里被写出去：start 与每拍 tick 都落盘 + 心跳`() {
        val sink = FakeSink()
        val (k, _, _) = kernel(sink = sink)
        k.start()
        val afterStart = sink.states.size
        val hbAfterStart = sink.heartbeats.size
        k.tick()
        k.tick()
        assertTrue("start 就要写出状态", afterStart >= 1)
        assertTrue("每拍 tick 都要写出状态", sink.states.size >= afterStart + 2)
        assertTrue("心跳要跟着走", sink.heartbeats.size >= hbAfterStart + 2)
        assertTrue(sink.states.last().contains("\"phase\":\"idle\""))
    }

    @Test
    fun `协议：ping version status job 都能答，垃圾输入不崩`() {
        val (k, _, _) = kernel()
        k.start()
        assertTrue(k.handle("""{"op":"ping"}""").contains("\"pong\":true"))
        assertTrue(k.handle("""{"op":"version"}""").contains("\"protocol\":1"))
        assertTrue(k.handle("""{"op":"status"}""").contains("\"phase\":\"idle\""))
        assertTrue(k.handle("""{"op":"job"}""").contains("\"job\":null"))
        assertTrue(k.handle("not json").contains("bad-request"))
        assertTrue(k.handle("""{"op":"nope"}""").contains("unknown-op"))
        assertTrue(k.handle("""{"action":"start"}""").contains("bad-request"))
    }

    /**
     * 回归：`ok` 响应必须是**合法 JSON 对象**。
     *
     * 真机事故（2026-09-19）：`Protocol.ok()` 把 `Json.obj(...)` 的完整对象原样拼在后面，
     * 得到 `{"ok":true,{"state":…}}` —— 缺键名，`run/ctl-status.json` 无法被任何解析器读。
     * 旧断言只 contains 字段名，所以四个 op 全坏也没有一条测试变红。
     */
    @Test
    fun `协议：ok 响应是合法对象（payload 展开，不嵌套）`() {
        val (k, _, _) = kernel()
        k.start()
        for (req in listOf("""{"op":"ping"}""", """{"op":"version"}""", """{"op":"status"}""", """{"op":"job"}""")) {
            val r = k.handle(req)
            assertTrue("必须以 ok:true 开头：$r", r.startsWith("{\"ok\":true"))
            assertFalse("payload 不能被原样嵌套（缺键名 = 非法 JSON）：$r", r.contains("{\"ok\":true,{"))
            assertEquals("对象不配平：$r", r.count { it == '{' }, r.count { it == '}' })
        }
        // 具体形状：字段与 ok 平级
        assertTrue("ping 的形状应当稳定", k.handle("""{"op":"ping"}""") == """{"ok":true,"pong":true}""")
    }
}
