package io.github.sunsetrne.sunsetd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内核 v2 状态模型的穷举单测。
 *
 * 为什么这些断言值钱：状态机判错一格的**真机表现**是"按钮亮着但点下去报错"，
 * 或者更糟 —— 今晚第 3 条那种"以为没在跑 ⇒ 又建一棵挂载树"。所以这里把
 * 迁移表、世代规则、两条不变量（I1/I5）与脏数据退化全部钉死。
 */
class KernelModelTest {

    private val t0 = 1_700_000_000_000L

    // ────────────────────────────── 迁移表

    @Test
    fun `合法的关键路径走得通`() {
        var s = SessionState()
        s = s.transition(Phase.PREPARING, t0, jobId = "j1")
        s = s.transition(Phase.MOUNTING, t0 + 1, jobId = "j1")
        s = s.transition(Phase.STARTING, t0 + 2, jobId = "j1")
        s = s.transition(Phase.RUNNING, t0 + 3, jobId = null, envMode = "full", port = 3081, pid = 4190)
        assertEquals(Phase.RUNNING, s.phase)
        assertEquals(1L, s.generation)
        assertEquals("full", s.envMode)
        assertEquals(3081, s.port)
        assertEquals(4190L, s.pid)
        assertTrue(s.phase.isUp)
        assertFalse(s.phase.isBusy)
    }

    @Test
    fun `非法迁移必须抛（不许悄悄走一步）`() {
        // 空转到运行：就是"没挂载就报 running"这一类谎报
        assertThrows(IllegalArgumentException::class.java) {
            SessionState().transition(Phase.RUNNING, t0)
        }
        // 运行中又去挂载：正是"两棵挂载树"的形状
        assertThrows(IllegalArgumentException::class.java) {
            SessionState(phase = Phase.RUNNING, since = t0).transition(Phase.MOUNTING, t0)
        }
        // 停止后直接回运行（没有经过 preparing/mounting）
        assertThrows(IllegalArgumentException::class.java) {
            SessionState(phase = Phase.STOPPING, since = t0).transition(Phase.RUNNING, t0)
        }
    }

    @Test
    fun `挂载中可以被取消：转 STOPPING（已挂上的必须拆掉）`() {
        val s = SessionState(phase = Phase.MOUNTING, since = t0, jobId = "j1")
        assertTrue(Phase.MOUNTING.canGoTo(Phase.STOPPING))
        val stopped = s.transition(Phase.STOPPING, t0 + 1, jobId = "j1")
        assertEquals(Phase.STOPPING, stopped.phase)
    }

    @Test
    fun `停止只有两个出口：回到 idle，或如实报 failed`() {
        assertEquals(listOf(Phase.IDLE, Phase.FAILED), Phase.STOPPING.entries())
        val bad = SessionState(phase = Phase.STOPPING, since = t0, jobId = "j1")
            .transition(Phase.FAILED, t0 + 1, jobId = null, lastError = "还有 3 个挂载卸不下来")
        assertEquals("还有 3 个挂载卸不下来", bad.lastError)
        assertFalse(bad.phase.isUp)
    }

    private fun Phase.entries(): List<Phase> =
        Phase.entries.filter { canGoTo(it) }

    // ────────────────────────────── I1：世代

    @Test
    fun `世代只在开新会话时 +1`() {
        var s = SessionState()
        s = s.transition(Phase.PREPARING, t0, jobId = "j1")
        assertEquals("准备阶段不开新会话", 0L, s.generation)
        s = s.transition(Phase.MOUNTING, t0 + 1, jobId = "j1")
        assertEquals("进入挂载 = 开新会话", 1L, s.generation)
        s = s.transition(Phase.STARTING, t0 + 2, jobId = "j1")
        s = s.transition(Phase.RUNNING, t0 + 3, jobId = null)
        s = s.transition(Phase.STOPPING, t0 + 4, jobId = "j2")
        s = s.transition(Phase.IDLE, t0 + 5, jobId = null)
        assertEquals(1L, s.generation)
        s = s.transition(Phase.PREPARING, t0 + 6, jobId = "j3")
        s = s.transition(Phase.MOUNTING, t0 + 7, jobId = "j3")
        assertEquals(2L, s.generation)
    }

    @Test
    fun `每个相位变化都刷新 since（卡住判断靠它，不靠猜超时）`() {
        val s = SessionState(phase = Phase.MOUNTING, since = t0, jobId = "j1")
            .transition(Phase.STARTING, t0 + 30_000, jobId = "j1")
        assertEquals(t0 + 30_000, s.since)
    }

    // ────────────────────────────── I5：busy ⟺ jobId

    @Test
    fun `不变量 I5：忙相位必须带 jobId`() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionState(phase = Phase.MOUNTING, since = t0, jobId = null)
        }
        // 非法：idle 却带着作业（既不是忙相位，也不是运行/失败态）
        assertThrows(IllegalArgumentException::class.java) {
            SessionState(phase = Phase.IDLE, since = t0, jobId = "j1")
        }
        // 合法组合：忙相位带作业；环境已 up 而命令还在收尾（真机就这么回事）
        SessionState(phase = Phase.STARTING, since = t0, jobId = "j1")
        SessionState(phase = Phase.RUNNING, since = t0, jobId = null)
        SessionState(phase = Phase.RUNNING, since = t0, jobId = "j1")
    }

    // ────────────────────────────── 落盘 / 读回

    @Test
    fun `状态 JSON 往返：字段一个不少`() {
        val s = SessionState(
            generation = 7,
            phase = Phase.DEGRADED,
            since = t0,
            backend = Backend.PROOT,
            envMode = "env-only",
            port = 3081,
            pid = 4672,
            jobId = null,
            lastError = "拿不到 rprivate：带降级继续（unsafe-propagate）",
        )
        val back = SessionState.fromJson(s.toJson())
        assertEquals(s, back)
    }

    @Test
    fun `脏数据不让内核起不来：忙却没 jobId 退化为 FAILED`() {
        val dirty = """{"schema":1,"generation":3,"phase":"mounting","since":123}"""
        val s = SessionState.fromJson(dirty)
        assertEquals(Phase.FAILED, s.phase)
        assertEquals(3L, s.generation)
        assertNull(s.jobId)
    }

    @Test
    fun `空输入或者垃圾输入都退回默认状态，绝不抛`() {
        assertEquals(SessionState(), SessionState.fromJson(null))
        assertEquals(SessionState(), SessionState.fromJson(""))
        assertEquals(SessionState(), SessionState.fromJson("not json at all"))
        assertEquals(SessionState(), SessionState.fromJson("{ broken"))
    }

    @Test
    fun `clear 掉会话事实但保留世代（环境停了不许留上次的 port url）`() {
        val s = SessionState(
            generation = 5, phase = Phase.RUNNING, since = t0,
            envMode = "full", port = 3080, pid = 4190,
        ).cleared()
        assertEquals(Phase.IDLE, s.phase)
        assertEquals(5L, s.generation)
        assertNull(s.envMode)
        assertNull(s.port)
        assertNull(s.pid)
    }

    // ────────────────────────────── 小 JSON 工具（内核算法的地基）

    @Test
    fun `JSON 写出：转义与 null 键都稳定`() {
        assertEquals(
            """{"a":"x\"y\n","b":null,"c":true,"d":3081}""",
            Json.obj("a" to "x\"y\n", "b" to null, "c" to true, "d" to 3081),
        )
    }

    @Test
    fun `JSON 读入：平铺对象，嵌套原样当文本`() {
        val m = Json.parseFlatObject("""{"a":"1","b":2,"c":null,"d":{"x":1},"e":[1,2]}""")!!
        assertEquals("1", m["a"])
        assertEquals("2", m["b"])
        assertEquals("null", m["c"])
        assertEquals("""{"x":1}""", m["d"])
        assertEquals("[1,2]", m["e"])
    }

    @Test
    fun `JSON 读入：字符串里的逗号与引号不被拆坏`() {
        val m = Json.parseFlatObject("""{"s":"a,b\"c","n":3}""")!!
        assertEquals("""a,b"c""", m["s"])
        assertEquals("3", m["n"])
    }
}
