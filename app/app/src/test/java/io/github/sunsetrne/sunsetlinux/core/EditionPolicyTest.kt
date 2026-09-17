package io.github.sunsetrne.sunsetlinux.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「界面切割」判定的纯函数测试（[EditionPolicy]）。
 *
 * ## 为什么要单独钉住
 *
 * `docs/module-variants.md` §一 的两条边界都是**方向性**的：
 *   · 免 root 版不许出现模块痕迹（探到了就会弹授权框 / 给出走不通的指引）；
 *   · root 版不许出现 proot/proroot 设置项（留着会让人以为"这里还能切运行时"）。
 *
 * 判反一个方向，界面不会崩 —— 只会安静地给用户一条走不通的路，真机上很难发现。
 * 所以这里**两个方向都测**：入参是显式的 `isRoot`，而不是去读 `BuildConfig`。
 *
 * ⚠️ 单测是按变体跑的（`testProotFullDebugUnitTest` / `testRootFullDebugUnitTest`），
 * 一旦这里改成读 `Edition.isRoot`，就只有一个变体能测到，另一个方向的断言会永久失真。
 */
class EditionPolicyTest {

    @Test
    fun `模块 UI 只属于 Root 版`() {
        assertTrue("Root 版要有模块版本行 / 模块更新卡片", EditionPolicy.showsKernelSuModuleUi(isRoot = true))
        assertFalse(
            "免 root 版不许出现任何模块内容（§六：不出现任何「KernelSU 模块」字样）",
            EditionPolicy.showsKernelSuModuleUi(isRoot = false),
        )
    }

    @Test
    fun `免 root 运行时设置只属于免 root 版`() {
        assertFalse("Root 版永远不用 proroot/proot，那一组选项不该出现", EditionPolicy.showsRootlessRuntimeUi(isRoot = true))
        assertTrue("免 root 版要能选 proroot / proot", EditionPolicy.showsRootlessRuntimeUi(isRoot = false))
    }

    @Test
    fun `层模式只属于 Root 版`() {
        assertTrue("loop/dir 是 root 模式的启动方式", EditionPolicy.showsLayerModeUi(isRoot = true))
        assertFalse(
            "proot 是把 base 层解成 rootfs，没有\"层怎么挂\"这回事",
            EditionPolicy.showsLayerModeUi(isRoot = false),
        )
    }

    @Test
    fun `拆开的启动按钮只属于 Root 版`() {
        assertTrue(
            "start --no-dsh 与 dsh start|stop 是 root 运行时脚本的子命令",
            EditionPolicy.showsSplitStartUi(isRoot = true),
        )
        assertFalse(
            "proot 版环境与 DSH 一体（start.sh 一次起完），给这几个按钮只会得到命令不存在",
            EditionPolicy.showsSplitStartUi(isRoot = false),
        )
    }

    /**
     * [Edition] 上的短名字必须与 [EditionPolicy] 一致 —— 它就是调用点用的那个入口。
     * 这条**与变体无关**（两边都成立），所以在 proot / root 两个变体里都跑得过。
     */
    @Test
    fun `Edition 上的切割属性与 Policy 一致`() {
        assertEquals(EditionPolicy.showsKernelSuModuleUi(Edition.isRoot), Edition.showsModuleUi)
        assertEquals(EditionPolicy.showsRootlessRuntimeUi(Edition.isRoot), Edition.showsRootlessRuntimeUi)
        assertEquals(EditionPolicy.showsLayerModeUi(Edition.isRoot), Edition.showsLayerModeUi)
        assertEquals(EditionPolicy.showsSplitStartUi(Edition.isRoot), Edition.showsSplitStartUi)
        // 历史属性与新名字不能分叉：以前的代码读 needsKernelSuModule，新的读 showsModuleUi
        assertEquals(
            "needsKernelSuModule 与 showsModuleUi 必须表达同一件事（否则老代码会绕过新的切割）",
            Edition.needsKernelSuModule,
            Edition.showsModuleUi,
        )
    }
}
