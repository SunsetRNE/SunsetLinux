package io.github.sunsetrne.sunsetlinux.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「壳」刷新的**新鲜度策略** —— 决定读失败时要不要沿用上一次的好状态。
 *
 * ## 为什么要有（真机 2026-09-19）
 *
 * Root 模式下每轮刷新要起 su 进程（一次往返几十到几百毫秒），任何一次抖动都会让
 * `refresh()` 走到 catch 或返回 `unavailable(...)`，而旧代码**无条件**把它写进 UI
 * —— 界面就从"运行中"翻成"读取环境状态失败"，下一轮又翻回来。用户的原话是
 * "壳对 Root 环境的读取延迟比较重……会有闪烁"。
 *
 * 修法有两半：① 一次 su 同时回答"有没有部署 + 什么状态"（少一次往返）；
 * ② 读失败时**沿用上一次的好值**最多 N 轮（本文钉的就是 ② 的判定）。
 *
 * ⚠️ 边界：环境**真的**报 ERROR（读到了、不是读失败）时必须照实显示 —— 不许拿
 * "保留上次好状态"把真实故障盖掉。
 */
class StatusFreshnessTest {

    private fun good() = DshStatus.unavailable("占位").copy(state = EnvState.RUNNING)
    private fun readFail() = DshStatus.unavailable("读取环境状态失败：su 超时")
    private fun realError() = DshStatus.unavailable("挂载失败").copy(state = EnvState.ERROR)

    @Test
    fun `读到了新状态就用新值`() {
        assertFalse(
            "这次读到了（哪怕是 ERROR），就不该沿用旧值",
            keepLastGoodStatus(good(), realError(), staleTicks = 0, maxStaleTicks = 3),
        )
        assertFalse(keepLastGoodStatus(good(), good(), 0, 3))
    }

    @Test
    fun `读失败但有上次的好值 —— 先沿用，超过上限就认账`() {
        assertTrue("第一次失败：沿用（界面不该闪）", keepLastGoodStatus(good(), readFail(), 0, 3))
        assertTrue("第二次失败：仍沿用", keepLastGoodStatus(good(), readFail(), 2, 3))
        assertFalse("已连续沿用 3 轮（= 上限）：如实显示失败", keepLastGoodStatus(good(), readFail(), 3, 3))
        assertFalse(keepLastGoodStatus(good(), readFail(), 9, 3))
    }

    @Test
    fun `上次也是读失败（或压根没有上次）就没有好值可留`() {
        assertFalse("上次也是读失败 —— 没有好值", keepLastGoodStatus(readFail(), readFail(), 0, 3))
        assertFalse("初次刷新就失败 —— 没有好值", keepLastGoodStatus(null, readFail(), 0, 3))
    }

    @Test
    fun `读到新的真实故障要立刻显示，但读不到时保留上一次读到的值`() {
        // ① 这次**读到了**、而且是真实故障 ⇒ 必须立刻显示，不许拿"上次在跑"糊过去
        assertFalse("新读到的真实 ERROR 必须显示", keepLastGoodStatus(good(), realError(), 0, 3))

        // ② 上次**读成功过**（哪怕是 ERROR）、这次读不到 ⇒ 保留上一次读到的值。
        //    判据是"读到了 vs 没读到"，不是"好 vs 坏"：把已经确认的事实换成
        //    "读取失败"同样会闪，而且信息更少。
        assertTrue(
            "上次读到的 ERROR 是已知事实，读不到时应当保留",
            keepLastGoodStatus(realError(), readFail(), 0, 3),
        )
        // 保留窗口一到就如实显示"读不到"
        assertFalse(keepLastGoodStatus(realError(), readFail(), 3, 3))
    }
}
