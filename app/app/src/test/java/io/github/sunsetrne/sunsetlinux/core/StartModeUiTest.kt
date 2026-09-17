package io.github.sunsetrne.sunsetlinux.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「一键启动 / 分步启动」切换与**运行中锁定**的纯函数测试
 * （[resolveStartMode] / [isStartModeLocked] / [startModeLockNote]）。
 *
 * ## 为什么值得逐格钉死
 *
 * 这里错一格的界面表现只是"显示了另一组按钮"：轻则用户看到一组点不动的按钮（以为坏了），
 * 重则界面写着"本次已按「一键启动」方式运行"而事实是 env-only —— 那是一句**假话**，
 * 用户会照着它去点「启动 DSH」然后被模块拒绝。真机极难复现，所以穷举在 JVM 里做。
 *
 * 覆盖需求里的五条：
 *   ① 环境未运行 + 默认 → 一键启动；
 *   ② 环境未运行 + 用户选分步 → 分步启动（没跑就完全听用户的）；
 *   ③ 运行中 `env_mode=full` → 强制一键（哪怕用户选的是分步）；
 *   ④ 运行中 `env_mode=env-only` → 强制分步（哪怕用户选的是一键）；
 *   ⑤ 运行中 `env_mode=null`（旧模块）→ **停在用户选择**，并如实提示"判不了"
 *      —— 与 [StartControls] 的"判不了只留停止环境"是同一口径：不编造本次起法。
 */
class StartModeUiTest {

    // ─────────────────────────── ① 环境未运行：完全听用户的

    @Test
    fun `环境未运行 + 默认选择 = 一键启动`() {
        val shown = resolveStartMode(
            envRunning = false,
            envMode = null,
            userChoice = StartMode.ONE_SHOT,
        )
        assertEquals("没跑的时候默认就该是一键启动", StartMode.ONE_SHOT, shown)
        assertFalse("环境没跑就不存在锁定 —— 用户应能自由切换", isStartModeLocked(envRunning = false))
        assertNull("没锁定时不该有锁定提示（提示只解释「为什么点不动」）", startModeLockNote(envRunning = false, envMode = null))
    }

    @Test
    fun `环境未运行 + 用户选分步 = 分步启动`() {
        val shown = resolveStartMode(
            envRunning = false,
            envMode = null,
            userChoice = StartMode.STEPWISE,
        )
        assertEquals("环境没跑时，用户选什么就用什么", StartMode.STEPWISE, shown)
        assertFalse(isStartModeLocked(envRunning = false))
    }

    @Test
    fun `环境未运行时旧模块的 env_mode 残留值也不影响用户选择`() {
        // 老模块可能在停止后仍留着 env_mode（"上次是这么起的"），但环境没跑就还没有
        // "本次起法"可言 —— 必须听用户的，否则用户会莫名被锁在上一轮的档位上。
        for (mode in listOf<EnvRunMode?>(null, EnvRunMode.FULL, EnvRunMode.ENV_ONLY)) {
            assertEquals(
                "envRunning=false 时必须听用户的（envMode=$mode）",
                StartMode.STEPWISE,
                resolveStartMode(envRunning = false, envMode = mode, userChoice = StartMode.STEPWISE),
            )
        }
    }

    // ─────────────────────────── ② 运行中 full：强制一键

    @Test
    fun `运行中 full 模式：强制锁在一键启动`() {
        val shown = resolveStartMode(
            envRunning = true,
            envMode = EnvRunMode.FULL,
            userChoice = StartMode.STEPWISE,
        )
        assertEquals(
            "本次是一键启动起来的，显示分步那组只会给出点不动的「启动 DSH」",
            StartMode.ONE_SHOT,
            shown,
        )
        assertTrue("运行中切换控件必须不可点", isStartModeLocked(envRunning = true))
        assertEquals(NOTE_LOCKED_ONE_SHOT, startModeLockNote(envRunning = true, envMode = EnvRunMode.FULL))
    }

    // ─────────────────────────── ③ 运行中 env-only：强制分步

    @Test
    fun `运行中 env-only 模式：强制锁在分步启动`() {
        val shown = resolveStartMode(
            envRunning = true,
            envMode = EnvRunMode.ENV_ONLY,
            userChoice = StartMode.ONE_SHOT,
        )
        assertEquals(
            "本次是「仅启动环境」起来的，显示一键那组只有灰按钮（脚本会拒绝带 DSH 的 start）",
            StartMode.STEPWISE,
            shown,
        )
        assertTrue(isStartModeLocked(envRunning = true))
        assertEquals(NOTE_LOCKED_STEPWISE, startModeLockNote(envRunning = true, envMode = EnvRunMode.ENV_ONLY))
    }

    // ─────────────────────────── ④ 运行中但判不了（旧模块）

    @Test
    fun `运行中 env_mode 判不了：停在用户选择，并如实提示判不了`() {
        // 旧模块（或键缺失）会走到这里。判不了就**不能编造**是哪一种起法：
        // 编 full 会写出"本次已按一键启动方式运行"这句假话，用户照着点「启动 DSH」
        // 还会被模块拒绝（full 下 DSH 不独立）。
        for (choice in StartMode.entries) {
            assertEquals(
                "判不了时停在用户当时的选择（choice=$choice），而不是硬猜一个",
                choice,
                resolveStartMode(envRunning = true, envMode = null, userChoice = choice),
            )
        }
        assertTrue("判不了也必须锁住切换：换了只会显示一组点不动的按钮", isStartModeLocked(envRunning = true))
        val note = startModeLockNote(envRunning = true, envMode = null)
        assertEquals(NOTE_LOCKED_UNKNOWN, note)
        assertTrue("必须如实说出「判不了」这个事实", note!!.contains("判不了"))
        assertTrue("还要指向唯一的出路", note.contains("停止环境"))
        assertTrue("说清原因（模块没报 env_mode），用户才查得下去", note.contains("env_mode"))
        // 口径一致性：不能借用 full/env-only 那两句（它们都在断言本次是怎么起的）
        assertFalse(note == NOTE_LOCKED_ONE_SHOT || note == NOTE_LOCKED_STEPWISE)
    }

    // ─────────────────────────── 边界：锁定提示本身

    @Test
    fun `锁定提示都必须给出 停止环境 这条出路`() {
        // 锁住切换而不告诉用户怎么办 = 把人卡住。矩阵里 `envStopEnabled` 在"环境在跑"时
        // 恒为真（见 StartControlsTest），所以「先停止环境」永远是一条走得通的路。
        for (mode in listOf<EnvRunMode?>(null, EnvRunMode.FULL, EnvRunMode.ENV_ONLY)) {
            val note = startModeLockNote(envRunning = true, envMode = mode)
            assertTrue("envMode=$mode 的锁定提示必须指向「停止环境」", note!!.contains("停止环境"))
        }
    }

    @Test
    fun `运行中两种已知起法锁定后各自显示的按钮组是有意义的`() {
        // 锁定结果与 StartControls 的矩阵必须"配得上"：锁定到 ONE_SHOT 时至少
        // 「停止环境」可点（有出口）；锁定到 STEPWISE 时至少有一个可点按钮。
        // 这两条把"锁定 → 组 → 可点"串起来，防止以后改矩阵时忘了改锁定方向。
        val full = resolveStartMode(true, EnvRunMode.FULL, StartMode.STEPWISE)
        val fullControls = StartControls.forStatus(
            notProvisioned = false,
            envRunning = true,
            envMode = EnvRunMode.FULL,
            dshRunning = true,
        )
        assertEquals(StartMode.ONE_SHOT, full)
        assertTrue("full 锁到一键那组，必须留着「停止环境」这条出口", fullControls.envStopEnabled)

        val envOnly = resolveStartMode(true, EnvRunMode.ENV_ONLY, StartMode.ONE_SHOT)
        val envOnlyControls = StartControls.forStatus(
            notProvisioned = false,
            envRunning = true,
            envMode = EnvRunMode.ENV_ONLY,
            dshRunning = false,
        )
        assertEquals(StartMode.STEPWISE, envOnly)
        assertTrue("env-only 锁到分步那组，至少要有可点的按钮", envOnlyControls.enabledCount >= 1)
    }

    // ─────────────────────────── 落盘值（Prefs.startMode）

    @Test
    fun `StartMode 的落盘值稳定且容错`() {
        // 落盘串改了会让老用户的选择被重置回默认，所以这里钉住取值；
        // 而任何脏数据都必须退化为默认档（绝不抛异常）。
        assertEquals("one-shot", StartMode.ONE_SHOT.wire)
        assertEquals("stepwise", StartMode.STEPWISE.wire)
        assertEquals("一键启动", StartMode.ONE_SHOT.label)
        assertEquals("分步启动", StartMode.STEPWISE.label)
        assertEquals(StartMode.ONE_SHOT, StartMode.from(null))
        assertEquals(StartMode.ONE_SHOT, StartMode.from(""))
        assertEquals(StartMode.ONE_SHOT, StartMode.from("   "))
        assertEquals(StartMode.ONE_SHOT, StartMode.from("nonsense"))
        assertEquals(StartMode.STEPWISE, StartMode.from("stepwise"))
        assertEquals("大小写/空白要容忍（手改过 SharedPreferences 的用户）", StartMode.STEPWISE, StartMode.from(" STEPWISE "))
    }
}
