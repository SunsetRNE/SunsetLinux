package io.github.sunsetrne.sunsetlinux.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内嵌离线包的**安装计划**（纯逻辑部分）与"proot 宿主脚本是否真的在仓库里"。
 *
 * 为什么这些要单测：`plan` 决定界面上"本机缺哪些部件、点一下装几个"，
 * 判断错一次用户就会看到"什么都不用装"（环境却起不来）或者"每次都说要装"
 * （点完还是同一句话）。真机上这两类误判都出现过。
 */
class OfflineInstallTest {

    private fun layer(id: String, version: String) = OfflineBundle.Part(
        kind = "layer",
        id = id,
        version = version,
        transport = "zstd",
        file = "$id-$version.erofs.zst",
        size = 10L,
        sha256 = "a".repeat(64),
        sha256Raw = "b".repeat(64),
        sizeRaw = 100L,
        off = 0L,
        len = 10L,
    )

    private fun prootPart(version: String? = "bundle-2026.09") = OfflineBundle.Part(
        kind = "proot",
        id = null,
        version = version,
        transport = null,
        file = "proot-bundle-arm64.tar.gz",
        size = 5L,
        sha256 = "c".repeat(64),
        sha256Raw = null,
        sizeRaw = null,
        off = 0L,
        len = 5L,
    )

    private fun bundle(parts: List<OfflineBundle.Part>) = OfflineBundle.Bundle(
        variant = "ubuntu-proot-dsh",
        layout = "layers-split-v1",
        baseVersion = "24.04.3-l1",
        runtimeVersion = "1.0.0",
        dshVersion = "0.1.5-rc.2",
        parts = parts,
        size = parts.sumOf { it.len },
        payloadStart = 8L,
        builtAt = "2026-09-16T00:00:00Z",
    )

    @Test
    fun `部件顺序必须是 proot 先、层自底向上（与包头顺序无关）`() {
        val parts = listOf(
            layer("dsh", "0.1.5-rc.2"),
            layer("base", "24.04.3-l1"),
            prootPart(),
            layer("runtime", "1.0.0"),
        )
        assertEquals(
            listOf("proot", "base", "runtime", "dsh"),
            OfflineApplier.order(parts).map { OfflineApplier.keyOf(it) },
        )
    }

    @Test
    fun `计划：本机版本一致就不装，缺的与不同的都要装`() {
        val b = bundle(listOf(layer("base", "24.04.3-l1"), layer("runtime", "1.0.0"), layer("dsh", "0.1.5-rc.2")))
        val plan = OfflineApplier.plan(
            local = mapOf("base" to "24.04.3-l1", "runtime" to "0.9.0"),
            bundle = b,
            prootReady = true,
        )
        assertEquals(listOf("base", "runtime", "dsh"), plan.map { it.key })
        assertFalse("base 版本一致 → 不该装", plan.first { it.key == "base" }.needed)
        assertTrue("runtime 本机 0.9.0 ≠ 包内 1.0.0 → 要装", plan.first { it.key == "runtime" }.needed)
        assertTrue("dsh 本机没记录 → 要装", plan.first { it.key == "dsh" }.needed)
        assertTrue("本机版本要显示出来", plan.first { it.key == "runtime" }.localVersion == "0.9.0")
    }

    @Test
    fun `计划：本机层版本读不出来（老 provision 的空元数据）也要装`() {
        // 真机踩过：state.json 里三个层都是 version=null 时，以前判"无需更新"，
        // 结果是"文件在、元数据空"的环境永远等不到修复。
        val b = bundle(listOf(layer("base", "24.04.3-l1")))
        val plan = OfflineApplier.plan(mapOf("base" to null), b)
        assertTrue(plan.single().needed)
        assertEquals(null, plan.single().localVersion)
    }

    @Test
    fun `计划：proot 部件看铺没铺，而不是看版本`() {
        val b = bundle(listOf(prootPart(), layer("base", "24.04.3-l1")))
        val notReady = OfflineApplier.plan(emptyMap(), b, prootReady = false)
        assertTrue("没铺就要铺", notReady.first { it.key == "proot" }.needed)
        val ready = OfflineApplier.plan(emptyMap(), b, prootReady = true)
        assertFalse("已铺就别再折腾（1 MB 也是流量）", ready.first { it.key == "proot" }.needed)
    }

    @Test
    fun `空清单与未知 id 不会崩，也不会说"要装"`() {
        assertTrue(OfflineApplier.plan(emptyMap(), bundle(emptyList())).isEmpty())
        // id 缺失（损坏的头）→ 不参与判断，宁可不装也不乱写 layers/
        val broken = bundle(listOf(layer("base", "1").copy(id = null)))
        assertFalse(OfflineApplier.plan(emptyMap(), broken).single().needed)
    }

    @Test
    fun `仓库里的 proot 宿主脚本必须齐（构建会拷进 assets，缺了非 root 模式起不来）`() {
        // 单测的工作目录是模块目录（app/app），运行时脚本在仓库根的 runtime/proot/
        val dir = File("../../runtime/proot")
        ProotRuntime.REQUIRED.forEach {
            assertTrue(
                "缺 runtime/proot/$it —— ProotRuntime.ensure 铺不出可用的 proot 模式（找的是 ${dir.absolutePath}）",
                File(dir, it).isFile,
            )
        }
        assertTrue("契约脚本 linuxctl.sh 必须在", File(dir, "linuxctl.sh").isFile)
    }
}
