package io.github.sunsetrne.sunsetlinux.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「仅启动环境」（`env_mode=env-only`）下 [Diagnoser] 的 Web 健康提示。
 *
 * 为什么单独钉住：status 里的 `dsh.healthy=false` 在两种情况下含义**完全不同** ——
 *   · DSH 在跑但没响应 → 真故障，该去跑诊断；
 *   · DSH 压根没启动（用户就是按「仅启动环境」起来的）→ **预期状态**，
 *     这时还报"端口无响应 / 端口没写对"会把用户引去做一堆无用诊断，
 *     而他真正该做的是点「启动 DSH」。
 * 这条分岔错在"多给一个提示"上，界面上一点红都不会出现，所以用测试钉住。
 */
class EnvOnlyHintTest {

    private fun status(envMode: String?, dshRunning: Boolean?, healthy: Boolean): DshStatus {
        val dsh = buildList {
            add("\"url\":null")
            add("\"base_url\":null")
            add("\"port\":3080")
            add("\"version\":null")
            add("\"healthy\":$healthy")
            if (dshRunning != null) add("\"running\":$dshRunning")
        }.joinToString(",")
        val envModeKey = if (envMode != null) ",\"env_mode\":\"$envMode\"" else ""
        val json = "{\"schema\":1,\"mode\":\"root\",\"state\":\"running\",\"pid\":9,\"uptime_sec\":1," +
            "\"dsh\":{$dsh}$envModeKey,\"layers\":{},\"storage\":{},\"last_error\":null}"
        return DshStatus.parse(json)
    }

    private fun hints(s: DshStatus) = Diagnoser.hints(
        status = s,
        provisioned = true,
        suAvailable = true,
        mode = EnvMode.ROOT,
    )

    @Test
    fun `env-only 且 DSH 没跑：不报 Web 无响应`() {
        val s = status(envMode = "env-only", dshRunning = false, healthy = false)
        assertTrue("前提：这就是「仅启动环境」的状态", s.isEnvOnly && !s.dshRunning)
        assertFalse(
            "DSH 是用户刻意没启动的 —— 报「端口无响应」是误导（该做的是点「启动 DSH」）",
            hints(s).any { it.title.contains("无响应") || it.title.contains("Web") },
        )
    }

    @Test
    fun `env-only 但 DSH 在跑却不健康：照旧报 Web 无响应`() {
        val s = status(envMode = "env-only", dshRunning = true, healthy = false)
        assertTrue("DSH 起来了却没响应 —— 这是真故障，诊断提示不能少", hints(s).any { it.title.contains("无响应") })
    }

    @Test
    fun `full 模式不健康：照旧报 Web 无响应`() {
        val s = status(envMode = "full", dshRunning = true, healthy = false)
        assertTrue(hints(s).any { it.title.contains("无响应") })
    }
}
