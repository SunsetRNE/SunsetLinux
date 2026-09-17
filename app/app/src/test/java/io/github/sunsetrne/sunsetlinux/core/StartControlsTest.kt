package io.github.sunsetrne.sunsetlinux.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「启动区」按钮启用矩阵的纯函数测试（[StartControls.forStatus]）。
 *
 * ## 为什么必须穷举
 *
 * 这次把启动拆成两条互斥的路（一键启动 / 先「仅启动环境」再「启动 DSH」）之后，
 * 判定错一格的**界面表现只是某个按钮亮着**，点下去却会被模块拒绝（退出码 1）。
 * 真机上很难复现、也很难归因，所以在 core 里把它做成纯函数，这里把状态矩阵钉死。
 *
 * 覆盖的四个主状态就是需求里的互斥表：
 *   ① 环境未运行；② `env_mode=full` 且在跑；
 *   ③ `env_mode=env-only` 且 DSH 没跑；④ `env_mode=env-only` 且 DSH 在跑。
 * 另外补三条边界：未部署、在跑但 `env_mode` 判不了（旧模块）、以及"一定有出口"。
 */
class StartControlsTest {

    private fun at(
        envRunning: Boolean,
        envMode: EnvRunMode?,
        dshRunning: Boolean,
        notProvisioned: Boolean = false,
    ) = StartControls.forStatus(
        notProvisioned = notProvisioned,
        envRunning = envRunning,
        envMode = envMode,
        dshRunning = dshRunning,
    )

    // ─────────────────────────── ① 环境未运行

    @Test
    fun `环境未运行：两条启动路都开着，DSH 按钮全灰`() {
        val c = at(envRunning = false, envMode = null, dshRunning = false)
        assertTrue("没环境时一键启动必须可点", c.oneShotEnabled)
        assertTrue("没环境时「仅启动环境」必须可点（终端就靠它）", c.startEnvOnlyEnabled)
        assertFalse("环境都不在，DSH 起不来 —— 必须置灰而不是让脚本报错", c.dshStartEnabled)
        assertFalse(c.dshStopEnabled)
        assertFalse("没在跑就没有可停的环境", c.envStopEnabled)
        assertEquals(StartControls.NOTE_NEED_ENV_FIRST, c.note)
        assertTrue("这是「你还没做前置」的提示，要警示色", c.noteIsWarning)
    }

    // ─────────────────────────── ② 一键启动起的环境（full）

    @Test
    fun `full 模式在跑：只有停止环境可点`() {
        val c = at(envRunning = true, envMode = EnvRunMode.FULL, dshRunning = true)
        assertFalse("环境已在跑，再来一次 start 会被模块拒绝", c.oneShotEnabled)
        assertFalse(c.startEnvOnlyEnabled)
        assertFalse("full 模式下 DSH 归环境管，模块会拒绝单独启动/停止", c.dshStartEnabled)
        assertFalse(c.dshStopEnabled)
        assertTrue("唯一的出路是停止环境（然后重选起法）", c.envStopEnabled)
        assertEquals(StartControls.NOTE_FULL_MODE, c.note)
        assertTrue(c.noteIsWarning)
    }

    @Test
    fun `full 模式下 DSH 崩了也不放开 DSH 按钮（按需求：其余全灰）`() {
        // 模块侧确实允许 full + DSH 恰好不在跑时补起，但 App 的需求明确要求
        // full 模式只留「停止环境」—— 少一个按钮换来"不会两条路混用"，值得。
        val c = at(envRunning = true, envMode = EnvRunMode.FULL, dshRunning = false)
        assertFalse(c.dshStartEnabled)
        assertFalse(c.dshStopEnabled)
        assertTrue(c.envStopEnabled)
    }

    // ─────────────────────────── ③ env-only 且 DSH 没跑

    @Test
    fun `env-only 且 DSH 没跑：启动 DSH 与停止环境可点`() {
        val c = at(envRunning = true, envMode = EnvRunMode.ENV_ONLY, dshRunning = false)
        assertFalse("环境在跑，一键启动（带 DSH 的 start）会被拒", c.oneShotEnabled)
        assertFalse("已经在跑了，不能再起一次", c.startEnvOnlyEnabled)
        assertTrue("环境已在，DSH 没跑 —— 这正是「启动 DSH」的用武之地", c.dshStartEnabled)
        assertFalse("没跑的东西不能停", c.dshStopEnabled)
        assertTrue(c.envStopEnabled)
        assertEquals(StartControls.NOTE_ENV_ONLY_DSH_DOWN, c.note)
        assertFalse("这是中性说明（下一步提示），不是错误", c.noteIsWarning)
    }

    // ─────────────────────────── ④ env-only 且 DSH 在跑

    @Test
    fun `env-only 且 DSH 在跑：停止 DSH 与停止环境可点`() {
        val c = at(envRunning = true, envMode = EnvRunMode.ENV_ONLY, dshRunning = true)
        assertFalse(c.oneShotEnabled)
        assertFalse(c.startEnvOnlyEnabled)
        assertFalse("DSH 已在跑，不能重复启", c.dshStartEnabled)
        assertTrue("env-only 下 DSH 是独立的一层，可以单独停（环境继续跑）", c.dshStopEnabled)
        assertTrue(c.envStopEnabled)
        assertEquals(StartControls.NOTE_ENV_ONLY_DSH_UP, c.note)
        assertFalse(c.noteIsWarning)
    }

    // ─────────────────────────── 边界

    @Test
    fun `明确未部署：五个按钮全灰并给出部署指引`() {
        val c = at(envRunning = false, envMode = null, dshRunning = false, notProvisioned = true)
        assertEquals("未部署时一个都不该亮", 0, c.enabledCount)
        assertEquals(StartControls.NOTE_NOT_PROVISIONED, c.note)
    }

    @Test
    fun `在跑但 env_mode 判不了：不冒充 full，只留停止环境`() {
        // 旧模块（或键缺失）会走到这里。判不了就不能编造"本次是一键启动"，
        // 但也不能放开"启动"类按钮 —— 那会被脚本拒绝。
        val c = at(envRunning = true, envMode = null, dshRunning = true)
        assertFalse(c.oneShotEnabled)
        assertFalse(c.startEnvOnlyEnabled)
        assertFalse(c.dshStartEnabled)
        assertFalse(c.dshStopEnabled)
        assertTrue(c.envStopEnabled)
        assertEquals(StartControls.NOTE_MODE_UNKNOWN, c.note)
        assertTrue(c.noteIsWarning)
    }

    @Test
    fun `只要环境在跑就一定有停止环境这条出口`() {
        for (mode in listOf<EnvRunMode?>(null, EnvRunMode.FULL, EnvRunMode.ENV_ONLY)) {
            for (dsh in listOf(false, true)) {
                val c = at(envRunning = true, envMode = mode, dshRunning = dsh)
                assertTrue("环境在跑却一个按钮都不能点 = 用户卡死（mode=$mode, dsh=$dsh）", c.enabledCount >= 1)
                assertTrue(c.envStopEnabled)
            }
        }
    }

    @Test
    fun `两条路永远互斥：环境在跑时启动类按钮全灭，DSH 的启停也不会同时亮`() {
        val modes = listOf<EnvRunMode?>(null, EnvRunMode.FULL, EnvRunMode.ENV_ONLY)
        for (running in listOf(false, true)) {
            for (mode in modes) {
                for (dsh in listOf(false, true)) {
                    val c = at(envRunning = running, envMode = mode, dshRunning = dsh)
                    val where = "running=$running, mode=$mode, dsh=$dsh"
                    if (running) {
                        assertFalse("环境在跑时一键启动必须灭（脚本会拒绝重复/混用的 start）：$where", c.oneShotEnabled)
                        assertFalse("环境在跑时不能再「仅启动环境」：$where", c.startEnvOnlyEnabled)
                    } else {
                        // 没在跑时两条启动路**都**可以走：它们互斥的是"起法"（环境起来之后就定死了），
                        // 不是"能不能起"—— 用户在这一刻才做选择。
                        assertTrue("环境没跑时两条启动路都该开着：$where", c.oneShotEnabled && c.startEnvOnlyEnabled)
                    }
                    assertFalse("同一个 DSH 不能同时可启可停：$where", c.dshStartEnabled && c.dshStopEnabled)
                }
            }
        }
    }
}
