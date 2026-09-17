package io.github.sunsetrne.sunsetlinux.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * status JSON **附加键** `dsh.running` / `env_mode` 的解析与派生属性。
 *
 * ## 为什么单独钉住
 *
 * 拆开启动之后，"环境在跑"不再蕴含"DSH 在跑"，界面就靠这两个键决定五个按钮的亮灭
 * （见 [StartControls]）。两个方向的错法都很安静：
 *   · `dsh.running` 缺失时**不能崩、也不能当成"在跑"**（那会让「启动 DSH」永远灰着）；
 *   · `env_mode` 缺失/取值未知时必须是 null（判不了），**绝不能默认成 full** ——
 *     否则旧模块上界面会编出一句"本次是一键启动"。
 */
class EnvModeStatusTest {

    /** 按需拼一个最小 status JSON；[envMode] / [dshRunning] 传 null 表示**键不存在**。 */
    private fun json(
        envMode: String? = null,
        dshRunning: Boolean? = null,
        tokenUrl: String? = null,
        state: String = "running",
    ): String {
        val dsh = buildList {
            add("\"url\":" + (tokenUrl?.let { "\"$it\"" } ?: "null"))
            add("\"base_url\":null")
            add("\"port\":null")
            add("\"version\":null")
            add("\"healthy\":false")
            if (dshRunning != null) add("\"running\":$dshRunning")
        }.joinToString(",")
        val envModeKey = if (envMode != null) ",\"env_mode\":\"$envMode\"" else ""
        return "{\"schema\":1,\"mode\":\"root\",\"state\":\"$state\",\"pid\":7,\"uptime_sec\":3," +
            "\"dsh\":{$dsh}$envModeKey," +
            "\"layers\":{},\"storage\":{},\"last_error\":null}"
    }

    @Test
    fun `env-only 且 DSH 没跑：如实解析`() {
        val s = DshStatus.parse(json(envMode = "env-only", dshRunning = false))
        assertEquals(EnvRunMode.ENV_ONLY, s.envMode)
        assertTrue(s.envRunning)
        assertTrue("这就是「仅启动环境」起来的状态", s.isEnvOnly)
        assertFalse(s.isFullMode)
        assertFalse("环境在跑但 DSH 没跑 —— 这正是新增键要表达的事", s.dshRunning)
    }

    @Test
    fun `full 且 DSH 在跑：如实解析`() {
        val s = DshStatus.parse(json(envMode = "full", dshRunning = true, tokenUrl = "http://127.0.0.1:3080/?token=x"))
        assertEquals(EnvRunMode.FULL, s.envMode)
        assertTrue(s.isFullMode)
        assertFalse(s.isEnvOnly)
        assertTrue(s.dshRunning)
    }

    @Test
    fun `dsh_running 权威：即使没有 url，键说在跑就是在跑`() {
        val s = DshStatus.parse(json(envMode = "full", dshRunning = true))
        assertTrue("dsh.running 是模块报的事实，优先于 url 推断", s.dshRunning)
    }

    @Test
    fun `缺 dsh_running 时退化为有带令牌 url 就算在跑`() {
        val withUrl = DshStatus.parse(json(envMode = "full", tokenUrl = "http://127.0.0.1:3080/?token=t"))
        assertTrue("老模块没有 dsh.running，但有带令牌 url → 按在跑处理", withUrl.dshRunning)
        assertNull("退化是 dshRunning 的事，原始键仍如实地是 null", withUrl.dshRunningReported)

        val withoutUrl = DshStatus.parse(json(envMode = "full"))
        assertFalse("既没键也没 url → 必须显示成没在跑，不能编造", withoutUrl.dshRunning)
    }

    @Test
    fun `缺 env_mode 是 null（判不了），不当成 full`() {
        val s = DshStatus.parse(json(dshRunning = true, tokenUrl = "http://127.0.0.1:3080/?token=t"))
        assertNull("旧模块没报 env_mode —— 判不了就是 null", s.envMode)
        assertFalse("判不了时绝不能冒充 full（界面会说出\"本次是一键启动\"这种假话）", s.isFullMode)
        assertFalse(s.isEnvOnly)
    }

    @Test
    fun `env_mode 取值未知或大小写混乱时不崩`() {
        assertNull(DshStatus.parse(json(envMode = "unknown-mode")).envMode)
        assertNull(DshStatus.parse(json(envMode = "")).envMode)
        assertEquals("大小写不敏感（走 EnvMode 那套容错口径）", EnvRunMode.ENV_ONLY, DshStatus.parse(json(envMode = "ENV-ONLY")).envMode)
    }

    @Test
    fun `环境没跑时派生属性一律为假`() {
        val s = DshStatus.parse(json(envMode = "full", dshRunning = false, state = "stopped"))
        assertFalse(s.envRunning)
        assertFalse("state 不是 running 时 env_mode 只是残留值，不构成 full 模式", s.isFullMode)
        assertFalse(s.isEnvOnly)
    }

    @Test
    fun `命令没跑起来时的占位状态不会假装在跑`() {
        val s = DshStatus.unavailable("linuxctl status 未输出 JSON")
        assertFalse(s.envRunning)
        assertNull(s.envMode)
        assertFalse(s.dshRunning)
    }
}
