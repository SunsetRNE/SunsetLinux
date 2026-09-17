package io.github.sunsetrne.sunsetlinux.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ActionTarget] 的穷举单测。
 *
 * 这套判定决定「命令还没返回时界面能不能解锁」，判错的后果很具体：
 *   · 太松（该等也解锁）→ 用户看到按钮亮着，可环境其实还没起来，点下去被模块拒绝；
 *   · 太紧（到了都不解锁）→ 真机事故复现：整块启动区永久置灰、「启动 DSH」点不动。
 * 所以每个目标都要**正反两面**都钉住。
 */
class ActionTargetTest {

    private fun status(
        state: EnvState,
        dshRunning: Boolean? = null,
        dshUrl: String? = null,
    ) = DshStatus.unavailable("测试用状态").copy(
        state = state,
        dshRunningReported = dshRunning,
        dshUrl = dshUrl,
    )

    private val runningEnvOnly = status(EnvState.RUNNING, dshRunning = false)
    private val runningWithDsh = status(EnvState.RUNNING, dshRunning = true)
    private val stopped = status(EnvState.STOPPED, dshRunning = false)
    private val starting = status(EnvState.STARTING, dshRunning = false)

    // ── 环境起来（一键启动 / 仅启动环境 / 重启）

    @Test
    fun `环境起来：running 才算达成，starting 不算`() {
        assertTrue(ActionTarget.ENV_RUNNING.reached(runningEnvOnly))
        assertTrue("DSH 在不在不影响「环境已起来」", ActionTarget.ENV_RUNNING.reached(runningWithDsh))
        assertFalse("starting 是过渡态，不能提前解锁", ActionTarget.ENV_RUNNING.reached(starting))
        assertFalse(ActionTarget.ENV_RUNNING.reached(stopped))
        assertFalse("读不到状态就不解锁（宁可多等一轮轮询）", ActionTarget.ENV_RUNNING.reached(null))
    }

    // ── 环境停止（一键启动那条路上的「停止环境」）

    @Test
    fun `环境停止：只有 stopped 才算达成`() {
        assertTrue(ActionTarget.ENV_STOPPED.reached(stopped))
        assertFalse(ActionTarget.ENV_STOPPED.reached(status(EnvState.STOPPING)))
        assertFalse(ActionTarget.ENV_STOPPED.reached(runningEnvOnly))
        assertFalse(ActionTarget.ENV_STOPPED.reached(null))
    }

    // ── 启动 DSH（分步启动的后半段：用户这张截图里点不动的那张卡片）

    @Test
    fun `启动 DSH：dsh_running 为真才达成`() {
        assertTrue(ActionTarget.DSH_RUNNING.reached(runningWithDsh))
        assertFalse("env-only 且 DSH 没跑时还不能解锁", ActionTarget.DSH_RUNNING.reached(runningEnvOnly))
        assertFalse(ActionTarget.DSH_RUNNING.reached(stopped))
        assertFalse(ActionTarget.DSH_RUNNING.reached(null))
    }

    @Test
    fun `启动 DSH：旧模块缺 dsh_running 键时按 url 退化，有令牌 url 就算起来`() {
        // dshRunningReported = null → dshRunning 退化为"有带令牌 url"
        val legacy = status(EnvState.RUNNING, dshRunning = null, dshUrl = "http://127.0.0.1:3081/?token=abc")
        assertTrue(ActionTarget.DSH_RUNNING.reached(legacy))
    }

    // ── 停止 DSH

    @Test
    fun `停止 DSH：必须环境还在跑，且 DSH 已经不在`() {
        assertTrue(ActionTarget.DSH_STOPPED.reached(runningEnvOnly))
        assertFalse("DSH 还在跑就不算停掉", ActionTarget.DSH_STOPPED.reached(runningWithDsh))
        assertFalse(
            "环境一起没了是两个动作的结果，不能当「DSH 已停」的证据",
            ActionTarget.DSH_STOPPED.reached(stopped),
        )
        assertFalse(ActionTarget.DSH_STOPPED.reached(null))
    }

    // ── NONE：从不达成（只能等命令自己返回）

    @Test
    fun `NONE 永不达成：清空可写层这类动作只能等命令返回`() {
        for (s in listOf(runningEnvOnly, runningWithDsh, stopped, starting, null)) {
            assertFalse("NONE 不该因为任何状态而解锁", ActionTarget.NONE.reached(s))
        }
    }

    // ── 动作 → 目标的映射（调用点不许再各写一份 when）

    @Test
    fun `动作到目标的映射穷举`() {
        assertEquals(ActionTarget.ENV_RUNNING, ActionTarget.of(ActionTarget.Action.START))
        assertEquals(ActionTarget.ENV_RUNNING, ActionTarget.of(ActionTarget.Action.RESTART))
        assertEquals(ActionTarget.ENV_RUNNING, ActionTarget.of(ActionTarget.Action.START_ENV_ONLY))
        assertEquals(ActionTarget.ENV_STOPPED, ActionTarget.of(ActionTarget.Action.STOP))
        assertEquals(ActionTarget.DSH_RUNNING, ActionTarget.of(ActionTarget.Action.DSH_START))
        assertEquals(ActionTarget.DSH_STOPPED, ActionTarget.of(ActionTarget.Action.DSH_STOP))
        assertEquals(ActionTarget.NONE, ActionTarget.of(ActionTarget.Action.RESET))
        // 每个动作都要有明确目标，不许漏（漏了就是"永远不解锁"）
        assertEquals(ActionTarget.Action.entries.size, ActionTarget.Action.entries.map { ActionTarget.of(it) }.size)
    }
}
