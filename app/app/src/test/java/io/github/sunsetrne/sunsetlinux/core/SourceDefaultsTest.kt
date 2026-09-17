package io.github.sunsetrne.sunsetlinux.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * **源设置默认档**的纯逻辑测试（npm / Python 同一套规则）。
 *
 * 对应真机缺陷：`ui/NpmSourceCard.kt` 里原来是
 * `presetOf(prefs.npmRegistry)?.id ?: CUSTOM_ID` —— prefs 为空（用户从没选过）时
 * `presetOf` 必然返回 null，于是"没选过"被画成「自定义」被选中。默认必须落在**官方**。
 *
 * 这几条断言值得留着：默认档一旦漂回"自定义"，用户看到的是"我的源是自定义"
 * 而输入框空空，装插件时却实际走官方源 —— 界面在撒谎，而且没人会去查 .npmrc。
 */
class SourceDefaultsTest {

    // ─────────────────────────────── npm

    @Test
    fun `npm 没选过时默认落在官方而不是自定义`() {
        assertEquals(NpmRegistry.OFFICIAL_ID, NpmRegistry.selectedPresetId(null))
        assertEquals(NpmRegistry.OFFICIAL_ID, NpmRegistry.selectedPresetId(""))
        assertEquals(NpmRegistry.OFFICIAL_ID, NpmRegistry.selectedPresetId("   "))
        assertNotEquals(
            "默认档绝不能是自定义：这正是真机上打开就选中「自定义」的那个缺陷",
            NpmRegistry.CUSTOM_ID,
            NpmRegistry.selectedPresetId(null),
        )
    }

    @Test
    fun `npm 官方预设的地址就是 registry npmjs org`() {
        assertEquals("npmjs", NpmRegistry.OFFICIAL_ID)
        assertEquals("https://registry.npmjs.org", NpmRegistry.official.url)
        assertEquals(NpmRegistry.presets.first(), NpmRegistry.official)
    }

    @Test
    fun `npm 选过的值原样反查，其余算自定义`() {
        assertEquals("npmmirror", NpmRegistry.selectedPresetId("https://registry.npmmirror.com/"))
        assertEquals("tencent", NpmRegistry.selectedPresetId("https://mirrors.cloud.tencent.com/npm/"))
        assertEquals(NpmRegistry.CUSTOM_ID, NpmRegistry.selectedPresetId("https://my.own.registry"))
    }

    @Test
    fun `npm 实际会生效的地址：选过用选过的，没选过用官方`() {
        assertEquals("https://registry.npmjs.org", NpmRegistry.effectiveUrl(null))
        assertEquals("https://registry.npmjs.org", NpmRegistry.effectiveUrl("  "))
        assertEquals("https://registry.npmmirror.com", NpmRegistry.effectiveUrl(" https://registry.npmmirror.com "))
    }

    @Test
    fun `npm 自定义输入框只在存着自定义地址时回填`() {
        assertEquals("", NpmRegistry.customSeed(null))
        assertEquals("", NpmRegistry.customSeed(""))
        assertEquals("", NpmRegistry.customSeed("https://registry.npmjs.org"))
        assertEquals("https://my.own.registry", NpmRegistry.customSeed("https://my.own.registry"))
    }

    // ─────────────────────────────── Python

    @Test
    fun `Python 没选过时默认落在官方而不是自定义`() {
        assertEquals(PypiRegistry.OFFICIAL_ID, PypiRegistry.selectedPresetId(null))
        assertEquals(PypiRegistry.OFFICIAL_ID, PypiRegistry.selectedPresetId(""))
        assertNotEquals(PypiRegistry.CUSTOM_ID, PypiRegistry.selectedPresetId(null))
    }

    @Test
    fun `Python 官方预设的地址就是 pypi org simple`() {
        assertEquals("pypi", PypiRegistry.OFFICIAL_ID)
        assertEquals("https://pypi.org/simple", PypiRegistry.official.url)
        assertEquals(PypiRegistry.presets.first(), PypiRegistry.official)
    }

    @Test
    fun `Python 预设表包含官方与四个国内镜像`() {
        assertEquals(
            listOf("pypi", "tuna", "aliyun", "tencent", "huawei"),
            PypiRegistry.presets.map { it.id },
        )
        PypiRegistry.presets.forEach {
            assertEquals("预设 ${it.id} 必须是 https（http 源会把包内容暴露给链路）", true, it.url.startsWith("https://"))
        }
    }

    @Test
    fun `Python 选过的值原样反查，尾部斜杠差异不算差异，其余算自定义`() {
        assertEquals("aliyun", PypiRegistry.selectedPresetId("https://mirrors.aliyun.com/pypi/simple/"))
        // 少一个尾部斜杠仍然是同一个预设（pip 自己会补，用户也可能手输）
        assertEquals("aliyun", PypiRegistry.selectedPresetId("https://mirrors.aliyun.com/pypi/simple"))
        assertEquals("tuna", PypiRegistry.selectedPresetId("https://pypi.tuna.tsinghua.edu.cn/simple"))
        assertEquals(PypiRegistry.CUSTOM_ID, PypiRegistry.selectedPresetId("https://my.internal/pypi/simple"))
    }

    @Test
    fun `Python 实际会生效的地址与自定义回填`() {
        assertEquals("https://pypi.org/simple", PypiRegistry.effectiveIndexUrl(null))
        assertEquals("https://pypi.org/simple", PypiRegistry.effectiveIndexUrl("  "))
        assertEquals("", PypiRegistry.customSeed(null))
        assertEquals("", PypiRegistry.customSeed("https://pypi.org/simple"))
        assertEquals("https://my.internal/pypi/simple", PypiRegistry.customSeed("https://my.internal/pypi/simple"))
    }
}
