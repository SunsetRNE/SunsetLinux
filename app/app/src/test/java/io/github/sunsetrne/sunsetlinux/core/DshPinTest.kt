package io.github.sunsetrne.sunsetlinux.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「内置 DSH ↔ 运行时 DSH」对账的纯函数测试。
 *
 * 为什么值得单测：这条判定决定界面要不要提示"你现在跑的不是包里那份、可以回滚"。
 * 判错的方向性代价不对称 —— 明明不一致却说一致（用户更新完崩了找不到退路），
 * 比多提示一次要糟得多。
 */
class DshPinTest {

    @Test
    fun `两边都没有 → NONE`() {
        val s = DshPin.reconcile(null, null)
        assertEquals(DshPin.Verdict.NONE, s.verdict)
        assertFalse(s.canRollbackToEmbedded)
        assertTrue("要说清'本包没内置 DSH'", s.detail.contains("没内置"))
    }

    @Test
    fun `没内置但有运行时 → 仍然是 NONE，且报出运行时版本`() {
        val s = DshPin.reconcile(null, "0.1.5-rc.2")
        assertEquals(DshPin.Verdict.NONE, s.verdict)
        assertTrue(s.detail.contains("0.1.5-rc.2"))
        assertFalse("没内置就没有'回滚到内置版'这回事", s.canRollbackToEmbedded)
    }

    @Test
    fun `有内置但运行时还没装 → RUNTIME_MISSING`() {
        val s = DshPin.reconcile("0.1.5-rc.1", null)
        assertEquals(DshPin.Verdict.RUNTIME_MISSING, s.verdict)
        assertTrue(s.detail.contains("还没有 DSH 层"))
        assertFalse(s.canRollbackToEmbedded)
    }

    @Test
    fun `一致（含前导 v 与空白差异）→ SAME`() {
        val s = DshPin.reconcile(" v0.1.5-rc.1 ", "0.1.5-rc.1")
        assertEquals(DshPin.Verdict.SAME, s.verdict)
        assertFalse(s.canRollbackToEmbedded)
    }

    @Test
    fun `运行时更新过（正常升级）→ RUNTIME_NEWER，且可以回滚到内置版`() {
        val s = DshPin.reconcile("0.1.5-rc.1", "0.1.5-rc.2")
        assertEquals(DshPin.Verdict.RUNTIME_NEWER, s.verdict)
        assertTrue("这是最主要的提示场景", s.canRollbackToEmbedded)
        assertTrue("要说清两个版本号", s.detail.contains("0.1.5-rc.2") && s.detail.contains("0.1.5-rc.1"))
        assertTrue("要给出退路", s.detail.contains("回滚"))
    }

    @Test
    fun `运行时比内置旧 → EMBEDDED_NEWER，也能一键回到内置版`() {
        val s = DshPin.reconcile("0.1.6", "0.1.5-rc.2")
        assertEquals(DshPin.Verdict.EMBEDDED_NEWER, s.verdict)
        assertTrue(s.canRollbackToEmbedded)
        assertTrue(s.detail.contains("建议回滚"))
    }

    @Test
    fun `版本号是 unknown → UNKNOWN，不硬猜`() {
        val s = DshPin.reconcile("unknown", "0.1.5-rc.1")
        assertEquals(DshPin.Verdict.UNKNOWN, s.verdict)
        assertFalse(s.canRollbackToEmbedded)
        assertNotNull(s.detail)
    }

    @Test
    fun `逐段数字比大小（不是字符串比）`() {
        // 字符串比会认为 "0.1.5-rc.9" < "0.1.5-rc.10"，这里必须反过来
        assertEquals(DshPin.Verdict.RUNTIME_NEWER, DshPin.reconcile("0.1.5-rc.9", "0.1.5-rc.10").verdict)
    }

    @Test
    fun `label 能塞进 Pill（短、不空）`() {
        for ((a, b) in listOf<Pair<String?, String?>>(
            null to null, null to "1.0", "1.0" to null, "1.0" to "1.0",
            "1.0" to "1.1", "1.1" to "1.0", "unknown" to "1.0",
        )) {
            val label = DshPin.reconcile(a, b).label
            assertTrue("label 不应为空（$a/$b）", label.isNotBlank())
            assertTrue("label 要短（≤ 16 字）：$label", label.length <= 16)
        }
    }
}
