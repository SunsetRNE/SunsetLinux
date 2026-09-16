package io.github.sunsetrne.sunsetlinux.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 免 root 引导步骤的**纯函数**测试（[ProotSetup.plan] / [ProotSetup.Readiness]）。
 *
 * 为什么值得单测：真机上用户卡在「没有找到 linuxctl」，而当时的引导里没有任何能铺它的
 * 动作 —— 这类"步骤与现状不匹配"的缺陷不该靠真机试出来。这里把三种现状钉住：
 * 什么都缺 / 缺 rootfs 但有 base 层 / 三件齐全。
 */
class ProotSetupTest {

    private fun ready(
        scripts: Boolean = false,
        proot: String? = null,
        rootfs: Boolean = false,
        layers: List<String> = emptyList(),
        seeds: List<String> = emptyList(),
        bundle: OfflineBundle.Bundle? = null,
    ) = ProotSetup.Readiness(
        scriptsReady = scripts,
        prootBinary = proot,
        rootfsReady = rootfs,
        layers = layers,
        seeds = seeds,
        embeddedBundle = bundle,
    )

    private fun bundle(variant: String) = OfflineBundle.Bundle(
        variant = variant,
        layout = "layers-split-v1",
        baseVersion = "24.04.3-l1",
        runtimeVersion = null,
        dshVersion = null,
        parts = emptyList(),
        size = 0,
        payloadStart = 0,
        builtAt = null,
    )

    @Test
    fun `什么都缺时三步都未完成，且第一步就是铺脚本`() {
        val r = ready()
        assertFalse(r.complete)
        assertEquals("宿主脚本（bin/linuxctl）、proot 运行时、rootfs", r.missingLabel)
        val plan = ProotSetup.plan(r)
        assertEquals(3, plan.size)
        assertTrue(plan.none { it.done })
        assertEquals(ProotSetup.Step.SCRIPTS, plan[0].step)
        assertEquals("铺 proot 运行时（内置脚本）", plan[0].actionLabel)
        assertEquals("缺宿主脚本", ProotSetup.summary(r))
    }

    @Test
    fun `脚本齐了但没运行时，第二步要装内嵌离线包`() {
        val r = ready(scripts = true, proot = "/x/proot/proot-launch.sh")
        val plan = ProotSetup.plan(r)
        assertTrue(plan[0].done)
        assertNull(plan[0].actionLabel)
        assertTrue(plan[1].done)
        assertFalse(plan[2].done)
        // 没有 rootfs 也没有来源 → 文案必须说清"还得先弄到 base 层"
        assertFalse(r.hasRootSource)
        assertTrue(plan[2].detail.contains("base 层"))
        assertEquals("缺 rootfs", ProotSetup.summary(r))
    }

    @Test
    fun `有 base 层时下一步是执行 provision（而不是让用户去找 tar 种子）`() {
        val r = ready(
            scripts = true,
            proot = "/x/proot/proot-launch.sh",
            layers = listOf("base-24.04.3-l1.erofs"),
            bundle = bundle("ubuntu-proot"),
        )
        assertTrue(r.hasBaseLayer)
        assertTrue(r.hasRootSource)
        val plan = ProotSetup.plan(r)
        assertEquals("铺环境（内嵌离线包 + provision）", plan[2].actionLabel)
        assertTrue(plan[2].detail.contains("base 层"))
        assertEquals("可解包出 rootfs（执行 provision）", ProotSetup.summary(r))
    }

    @Test
    fun `种子 tar 也算来源（老用户手工放种子的路不能断）`() {
        val r = ready(scripts = true, proot = "/x/proot/proot", seeds = listOf("ubuntu-base-24.04.3.tar.gz"))
        assertTrue(r.hasRootSource)
        assertTrue(ProotSetup.plan(r)[2].detail.contains("ubuntu-base"))
    }

    @Test
    fun `三件齐全时不再有任何待办动作`() {
        val r = ready(
            scripts = true,
            proot = "/x/proot/proot-launch.sh",
            rootfs = true,
            layers = listOf("base-24.04.3-l1.erofs"),
        )
        assertTrue(r.complete)
        assertEquals("免 root 环境已就绪", ProotSetup.summary(r))
        val plan = ProotSetup.plan(r)
        assertTrue(plan.all { it.done })
        assertTrue(plan.all { it.actionLabel == null })
        assertEquals("", r.missingLabel)
    }
}
