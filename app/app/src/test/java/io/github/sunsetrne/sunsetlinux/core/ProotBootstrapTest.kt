package io.github.sunsetrne.sunsetlinux.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 免 root 版「打开即启用」判定的**真值表**（[ProotBootstrap.decide]）。
 *
 * 四个条件判错的后果都是用户可见的，而且方向性很强：
 *   · 在 root 版误判成 RUN → 会去铺 proot 的东西（操作另一个环境，"看起来能用"里最坏的一种）；
 *   · 用户取消过还跑 → 用户的选择被无视；
 *   · 没内嵌离线包还跑 → 必然以"缺层"失败，把用户从本来正确的引导上拽走；
 *   · 环境就绪还重跑 → 重解几百 MB 离线包，用户以为卡死。
 *
 * 之前真机上就有过"自动流程把用户从手动引导上拽走"的体验问题，所以这里把 16 种组合里
 * 有意义的那几种全部枚举（表驱动，不靠"改天有人记得补"）。
 */
class ProotBootstrapTest {

    private fun decide(
        isRoot: Boolean = false,
        declined: Boolean = false,
        hasEmbeddedBundle: Boolean = true,
        ready: Boolean = false,
    ) = ProotBootstrap.decide(isRoot, declined, hasEmbeddedBundle, ready)

    @Test
    fun `免 root 版没铺好且内嵌包在 → 自动跑`() {
        assertEquals(ProotBootstrap.Decision.RUN, decide())
    }

    @Test
    fun `已经就绪 → 不做重活，直接进外壳`() {
        // 特别是这个方向：ready 时必须 ALREADY_READY 而不是 RUN，
        // 否则每次冷启动都要重解一遍内嵌离线包（真机上要好几分钟）
        assertEquals(ProotBootstrap.Decision.ALREADY_READY, decide(ready = true))
    }

    @Test
    fun `用户明确取消过 → 永不自动跑（哪怕环境没铺好）`() {
        assertEquals(
            "用户点过「手动部署」之后，自动路必须彻底沉默",
            ProotBootstrap.Decision.NOT_APPLICABLE,
            decide(declined = true),
        )
    }

    @Test
    fun `没有内嵌离线包 → 保持既有引导，不硬跑`() {
        // 干净检出 / CI 未附产物时就是这一格：自动跑必然"缺层"失败，不如直接给引导
        assertEquals(
            ProotBootstrap.Decision.NOT_APPLICABLE,
            decide(hasEmbeddedBundle = false),
        )
    }

    @Test
    fun `Root 版无论其它条件如何都不走这条流水线`() {
        for (declined in listOf(true, false)) {
            for (bundle in listOf(true, false)) {
                for (ready in listOf(true, false)) {
                    assertEquals(
                        "Root 版（isRoot=true, declined=$declined, bundle=$bundle, ready=$ready）不该走 proot 自动路",
                        ProotBootstrap.Decision.NOT_APPLICABLE,
                        decide(isRoot = true, declined = declined, hasEmbeddedBundle = bundle, ready = ready),
                    )
                }
            }
        }
    }

    @Test
    fun `判定优先级：root 与取消都压过就绪与内嵌包`() {
        // 这两格即使 ready 也必须是"不适用"（不能变成 ALREADY_READY 而把 Root 版送进终端页默认流程）
        assertEquals(ProotBootstrap.Decision.NOT_APPLICABLE, decide(isRoot = true, ready = true))
        assertEquals(ProotBootstrap.Decision.NOT_APPLICABLE, decide(declined = true, ready = true))
    }
}
