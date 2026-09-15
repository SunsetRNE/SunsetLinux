package io.github.sunsetrne.sunsetlinux.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「首次部署向导」下一步决策的回归（纯函数，不需要设备）。
 *
 * 每条断言都对应真机上的一次实际表现 —— 这不是形式化的枚举测试：
 *
 * | 断言 | 对应现象 |
 * |---|---|
 * | 退出码 1 + `missing_layers` ⇒ BUILD_LAYERS（root） | 真机上向导只调 `linuxctl provision`，于是永远以"provision 失败"收场；缺的其实是"构建那一步" |
 * | proot 模式 ⇒ NEED_CHANNEL（**不**去构建） | proot 没有真 chroot/mount，硬调 `device-provision.sh` 只会浪费十几分钟 |
 * | 没有 su ⇒ FAILED（不假装能构建） | `su` 不可用时构建必然失败，不该让用户等 |
 * | 解析不出 `missing_layers` ⇒ FAILED | 没有证据就不要跑一次半小时的构建（宁可让用户把日志发过来） |
 * | `missing_layers` 里出现不认识的层名 ⇒ 丢掉 | 层 id 是契约（base/runtime/dsh），脏数据不驱动行为 |
 */
class ProvisionPlanTest {

    @Test
    fun `层缺失且 root 有 su 时去构建`() {
        val json = """{"ok":false,"already":false,"linux_home":"/data/sunsetlinux","missing_layers":"base,runtime,dsh"}"""
        val missing = ProvisionPlan.missingLayers(json)
        assertEquals(listOf("base", "runtime", "dsh"), missing)
        assertEquals(
            ProvisionPlan.Step.BUILD_LAYERS,
            ProvisionPlan.nextStep(EnvMode.ROOT, exitCode = 1, missing = missing, suAvailable = true),
        )
    }

    @Test
    fun `只缺一层也要去构建`() {
        val json = """{"ok":false,"missing_layers":"dsh"}"""
        val missing = ProvisionPlan.missingLayers(json)
        assertEquals(listOf("dsh"), missing)
        assertEquals(
            ProvisionPlan.Step.BUILD_LAYERS,
            ProvisionPlan.nextStep(EnvMode.ROOT, 1, missing, suAvailable = true),
        )
    }

    @Test
    fun `成功或已部署都是 DONE`() {
        assertEquals(
            ProvisionPlan.Step.DONE,
            ProvisionPlan.nextStep(EnvMode.ROOT, exitCode = 0, missing = emptyList(), suAvailable = true),
        )
        // 契约 §3：已完整部署过 → 退出码 2
        assertEquals(
            ProvisionPlan.Step.DONE,
            ProvisionPlan.nextStep(EnvMode.ROOT, exitCode = 2, missing = emptyList(), suAvailable = true),
        )
    }

    @Test
    fun `proot 模式缺层时不要去构建而是去装频道`() {
        val missing = listOf("base", "runtime", "dsh")
        assertEquals(
            ProvisionPlan.Step.NEED_CHANNEL,
            ProvisionPlan.nextStep(EnvMode.PROOT, exitCode = 1, missing = missing, suAvailable = false),
        )
        // 即便这台机器有 su，proot 模式也不该去跑构建（环境根不一样，建了也用不上）
        assertEquals(
            ProvisionPlan.Step.NEED_CHANNEL,
            ProvisionPlan.nextStep(EnvMode.PROOT, exitCode = 1, missing = missing, suAvailable = true),
        )
    }

    @Test
    fun `没有 su 的 root 模式是 FAILED 而不是假装能构建`() {
        assertEquals(
            ProvisionPlan.Step.FAILED,
            ProvisionPlan.nextStep(EnvMode.ROOT, 1, listOf("base"), suAvailable = false),
        )
    }

    @Test
    fun `解析不出缺哪些层时必须 FAILED 而不是猜着构建`() {
        assertEquals(ProvisionPlan.Step.FAILED, ProvisionPlan.nextStep(EnvMode.ROOT, 1, emptyList(), true))
        assertEquals(emptyList<String>(), ProvisionPlan.missingLayers(""))
        assertEquals(emptyList<String>(), ProvisionPlan.missingLayers("not json at all"))
        assertEquals(emptyList<String>(), ProvisionPlan.missingLayers("""{"ok":false}"""))
    }

    @Test
    fun `脏层名要被丢掉且去重`() {
        val missing = ProvisionPlan.missingLayers("""{"missing_layers":"base, bogus, base, dsh"}""")
        assertEquals(listOf("base", "dsh"), missing)
    }

    @Test
    fun `JSON 前后有日志噪音也能解析`() {
        // linuxctl 会把进度写到 stderr（这里不会混进来），但日志前缀混进 stdout 时也要能用
        val stdout = "[2026-09-16 05:10:01] [linuxctl] 以下层仍缺失： base\n{\"ok\":false,\"missing_layers\":\"base\"}"
        assertEquals(listOf("base"), ProvisionPlan.missingLayers(stdout))
    }

    @Test
    fun `解释文案必须说出 provision 不建层这件事`() {
        val text = ProvisionPlan.explain(ProvisionPlan.Step.BUILD_LAYERS, listOf("base", "dsh"))
        assertTrue("要提到缺哪几层：$text", text.contains("base") && text.contains("dsh"))
        assertTrue("要说清 provision 不构建层：$text", text.contains("不构建层"))
        assertTrue("要提到 device-provision.sh：$text", text.contains("device-provision.sh"))
    }
}
