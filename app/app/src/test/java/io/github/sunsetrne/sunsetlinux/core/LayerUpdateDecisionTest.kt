package io.github.sunsetrne.sunsetlinux.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这一层要不要装」的回归。
 *
 * 真机依据：老版本的 `linuxctl provision` 会在 `state.json` 里写
 * `"version": null`（三个层都是），而 `layers/` 里可能还留着半成品文件。
 * 旧判断（本地版本 null ⇒ 无需更新）会让这种环境**永远等不到修复** ——
 * 用户在「更新」页看不到任何可装的层，只能自己发现"原来得先删层"。
 *
 * 现在：**本地版本读不出来 = 状态不明 = 一律给装**（装完 state.json 有版本号，
 * 下一次就恢复正常比较，不会一直提示）。
 */
class LayerUpdateDecisionTest {

    @Test
    fun `本地没有这层就要装`() {
        assertTrue("远端有版本、本地没记录 → 装", needsInstallDecision(null, "24.04.3-l1"))
    }

    @Test
    fun `本地版本读不出来也要装（修复旧 state_json）`() {
        assertTrue("state.json 里 version=null 时必须给装，否则环境永远修不好",
            needsInstallDecision(null, "24.04.3-l1"))
    }

    @Test
    fun `版本不同才装`() {
        assertTrue(needsInstallDecision("24.04.3-l0", "24.04.3-l1"))
        assertFalse("版本相同就不该反复提示", needsInstallDecision("24.04.3-l1", "24.04.3-l1"))
    }

    @Test
    fun `远端没有版本号就不装（清单自己都没准备好）`() {
        assertFalse(needsInstallDecision("24.04.3-l1", null))
        assertFalse(needsInstallDecision(null, null))
    }
}
