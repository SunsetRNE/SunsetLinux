package io.github.sunsetrne.sunsetlinux.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「检查没成」与「没有更新」是两件事 —— 界面**不许**把前者说成后者。
 *
 * ## 为什么单独立一份（真机 2026-09-19，App 0.3.18）
 *
 * 那台机器上频道被 `REJECTED：签名校验失败…`（本机 Ed25519 实现取到平台的
 * AndroidKeyStore，解不了 SPKI），而：
 *   · 更新页顶部提示写着「已是最新：所有层与频道清单一致。」（旧的 `notice` 分支只看
 *     `merged.isEmpty()`，而 `merge()` 只收 `state == OK` ⇒ "都失败"与"都没更新"同形）；
 *   · 首页「更新」磁贴写着「已是最新」。
 * 用户据此以为"没有更新"，实际是"根本没查成" —— 这也正是"用户自己更新不了"的原因。
 *
 * 这两个判定都抽成了纯函数（[channelsAllFailed]、[channelNotice]），这里把四种形状钉住。
 */
class UpdateNoticeTest {

    private fun ch(id: String) = Channel(id = id, name = id, url = "https://example.invalid/$id.json", pubkey = "k")

    private fun report(state: ReportState) = ChannelReport(ch("c-$state"), state, reason = "因为 ${state}")

    @Test
    fun `所有频道都失败时，不许说已是最新`() {
        val reports = listOf(report(ReportState.REJECTED), report(ReportState.ERROR))
        assertTrue(channelsAllFailed(reports))
        val text = channelNotice(
            reportCount = reports.size,
            failedCount = reports.count { it.state != ReportState.OK },
            mergedCount = 0,
            updatesCount = 0,
        )
        assertTrue("必须明说检查失败：$text", text.contains("频道检查失败"))
        assertTrue("并且要挡住「已是最新」这个错觉：$text", text.contains("不代表已是最新"))
        assertFalse("一个字都不许提「所有层与频道清单一致」：$text", text.contains("所有层与频道清单一致"))
    }

    @Test
    fun `有任何一个频道成功时就不算检查失败`() {
        val okOnly = listOf(report(ReportState.OK))
        assertFalse(channelsAllFailed(okOnly))
        assertFalse(channelsAllFailed(listOf(report(ReportState.REJECTED), report(ReportState.OK))))
        assertFalse("没有频道（还没配）不算失败", channelsAllFailed(emptyList()))
    }

    @Test
    fun `都成功且没有更新才是真正的已是最新`() {
        val text = channelNotice(reportCount = 1, failedCount = 0, mergedCount = 0, updatesCount = 0)
        assertTrue("这才是可以说已是最新的情形：$text", text.contains("已是最新：所有层与频道清单一致"))
    }

    @Test
    fun `部分频道失败但结论为空时要说明结论可能不完整`() {
        val text = channelNotice(reportCount = 2, failedCount = 1, mergedCount = 0, updatesCount = 0)
        assertTrue("要提到有频道失败：$text", text.contains("1 个频道检查失败"))
        assertTrue("要说明结论可能不完整：$text", text.contains("可能不完整"))
    }

    @Test
    fun `发现更新时照常报数量，失败频道只做补充`() {
        val text = channelNotice(reportCount = 2, failedCount = 1, mergedCount = 2, updatesCount = 2)
        assertTrue("主结论仍是有更新：$text", text.contains("发现 2 个层可更新"))
        assertTrue("失败频道要顺带提一句：$text", text.contains("另有 1 个频道检查失败"))
    }

    @Test
    fun `被用户忽略的那一支不受影响`() {
        val text = channelNotice(reportCount = 1, failedCount = 0, mergedCount = 1, updatesCount = 0)
        assertEquals("有可用更新，但你已选择忽略。", text)
    }
}
