package io.dshroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 功能 B/C/D 里**纯逻辑**部分的测试（不需要设备、不需要环境）。
 *
 * 刻意把可测逻辑从 UI 里抽出来放在 core：源地址校验、.npmrc 生成、
 * dist-tag 解析、插件目标校验、profile package.json 解析。
 */
class FeaturesTest {

    // ─────────────────────────────── B：npm 源

    @Test
    fun `自定义 registry 地址校验`() {
        assertNull(NpmRegistry.validateUrl("https://registry.npmmirror.com"))
        assertNull(NpmRegistry.validateUrl("http://192.168.1.10:4873"))
        assertNotNull("空地址应被拒", NpmRegistry.validateUrl("   "))
        assertNotNull("非 http(s) 应被拒", NpmRegistry.validateUrl("registry.npmjs.org"))
        assertNotNull("带空格应被拒", NpmRegistry.validateUrl("https://a b.com"))
    }

    @Test
    fun `npmrc 内容只写 registry 且规整尾部斜杠`() {
        val content = NpmRegistry.npmrcContent("https://registry.npmmirror.com/")
        val lines = content.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals("只应有一行有效配置", 1, lines.size)
        assertEquals("registry=https://registry.npmmirror.com", lines.first())
    }

    @Test
    fun `预设源可以被反查出来`() {
        assertEquals("npmmirror", NpmRegistry.presetOf("https://registry.npmmirror.com/")?.id)
        assertEquals("npmjs", NpmRegistry.presetOf("https://registry.npmjs.org")?.id)
        assertNull(NpmRegistry.presetOf("https://my.own.registry"))
        assertNull(NpmRegistry.presetOf(null))
    }

    // ─────────────────────────────── D：dist-tag

    @Test
    fun `dist-tag 能解析成版本号`() {
        val tags = mapOf("latest" to "0.1.5-rc.1", "next" to "0.1.5-rc.2", "alpha" to "0.1.6-alpha.1")
        assertEquals("0.1.5-rc.2", NpmDistTags.resolve("next", tags))
        assertEquals("0.1.6-alpha.1", NpmDistTags.resolve("alpha", tags))
        // 明确版本号直接用
        assertEquals("1.2.3", NpmDistTags.resolve("1.2.3", tags))
        assertEquals("0.1.5-rc.2", NpmDistTags.resolve(" 0.1.5-rc.2 ", tags))
        // 未知 tag 且不像版本号 -> 解析不出来（UI 会提示）
        assertNull(NpmDistTags.resolve("nightly", tags))
        assertNull(NpmDistTags.resolve("", tags))
    }

    @Test
    fun `dist-tag 输入校验`() {
        assertNull(NpmDistTags.validateTag("next"))
        assertNull(NpmDistTags.validateTag("0.1.6-alpha.1"))
        assertNotNull(NpmDistTags.validateTag(""))
        assertNotNull(NpmDistTags.validateTag("a b"))
    }

    // ─────────────────────────────── C：插件

    @Test
    fun `插件目标校验放行包名路径URL但拦截注入`() {
        assertNull(DshPlugins.validateTarget("dsh-plugin-foo"))
        assertNull(DshPlugins.validateTarget("@scope/dsh-plugin"))
        assertNull(DshPlugins.validateTarget("./local-plugin"))
        assertNull(DshPlugins.validateTarget("https://example.com/plugin.tgz"))
        assertNull(DshPlugins.validateTarget("/sdcard/plugins/x"))

        assertNotNull("空输入", DshPlugins.validateTarget("  "))
        assertNotNull("空格", DshPlugins.validateTarget("a b"))
        listOf(";", "|", "&", "`", "$", ">", "<", "'", "\"", "\\").forEach { ch ->
            assertNotNull("不应放行 $ch", DshPlugins.validateTarget("pkg$ch rm -rf /"))
        }
    }

    @Test
    fun `解析 profile 的 package_json 得到已装插件`() {
        val json = """
            {
              "name": "dsh-profile-web",
              "dependencies": {
                "@deepseek-ai/dsh": "0.1.5-rc.2",
                "dsh-plugin-alpha": "^1.2.0"
              },
              "bundles": ["dsh-plugin-alpha"]
            }
        """.trimIndent()
        val plugins = DshPlugins.parsePlugins(json)
        assertEquals(2, plugins.size)
        // 按名字排序
        assertEquals("@deepseek-ai/dsh", plugins[0].name)
        assertEquals("0.1.5-rc.2", plugins[0].version)
        assertTrue("不在 bundles 里就不算启用", !plugins[0].bundled)
        assertEquals("dsh-plugin-alpha", plugins[1].name)
        assertTrue("bundles 里声明了就该标记为已启用", plugins[1].bundled)
    }

    @Test
    fun `bundles 里出现但 dependencies 没有的也要列出来`() {
        val plugins = DshPlugins.parsePlugins("""{"bundles":["only-in-bundles"]}""")
        assertEquals(1, plugins.size)
        assertEquals("only-in-bundles", plugins[0].name)
        assertTrue(plugins[0].bundled)
        assertNull("没有版本号就留空，不要编一个", plugins[0].version)
    }

    @Test
    fun `坏 JSON 不崩只是解析为空`() {
        assertTrue(DshPlugins.parsePlugins("not json").isEmpty())
        assertTrue(DshPlugins.parsePlugins("").isEmpty())
    }

    @Test
    fun `展示给用户的命令行与实际语义一致`() {
        assertEquals("dsh plugin --profile web add dsh-plugin-foo", DshPlugins.installCommand("dsh-plugin-foo"))
        assertEquals("dsh plugin --profile web remove dsh-plugin-foo", DshPlugins.removeCommand("dsh-plugin-foo"))
    }
}
