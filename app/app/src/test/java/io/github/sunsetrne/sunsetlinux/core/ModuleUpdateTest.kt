package io.github.sunsetrne.sunsetlinux.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模块更新链路里**纯逻辑**的部分：版本比较、index.json 解析、设备侧刷入脚本。
 *
 * 这三样错一个，"App 内刷模块"就会以不同方式变成坑：版本比错 → 该更新时不提示
 * 或永远提示要更新；解析错 → 下到不存在的资产；脚本错 → `su` 里跑出一个空脚本，
 * 用户看到"刷入失败"却不知道为什么。
 */
class ModuleUpdateTest {

    /** 与线上 `/stable/index.json` 同形（字段名一个不改，值取自 v0.2.5 那次发布）。 */
    private val indexJson = """
        {
          "schema": 1,
          "channel": "stable",
          "built_at": "2026-09-16T03:40:31Z",
          "commit": "4043fc89e7851af69fdfbbd497d9e391c6168443",
          "run_number": 66,
          "app_version": "0.2.5",
          "github_release_tag": "v0.2.5",
          "module_version": "1.0.10",
          "files": [
            { "name": "SunsetLinux-0.2.5-minimal-debug.apk", "size": 13812369, "sha256": "aa" },
            { "name": "sunsetlinux-module-1.0.10.zip", "size": 187710, "sha256": "bbccdd" }
          ]
        }
    """.trimIndent()

    @Test
    fun `版本比较按数字段：1_0_10 比 1_0_9 新`() {
        assertTrue("1.0.10 必须 > 1.0.9（字符串比会得出相反结论）", compareModuleVersion("1.0.10", "1.0.9") > 0)
        assertTrue(compareModuleVersion("1.0.9", "1.0.10") < 0)
        assertEquals(0, compareModuleVersion("1.0.9", "1.0.9"))
        assertEquals("v 前缀不算版本差异", 0, compareModuleVersion("v1.0.9", "1.0.9"))
        assertEquals("1.0 与 1.0.0 视为同一版（少一次假更新提示）", 0, compareModuleVersion("1.0", "1.0.0"))
        assertTrue(compareModuleVersion("1.1", "1.0.9") > 0)
        assertTrue(compareModuleVersion("2.0.0", "1.99.99") > 0)
    }

    @Test
    fun `从官方 index_json 读出模块产物：版本、Release URL、sha256、大小`() {
        val a = ModuleRelease.parse(indexJson, "stable")!!
        assertEquals("1.0.10", a.version)
        assertEquals("sunsetlinux-module-1.0.10.zip", a.name)
        assertEquals(
            "https://github.com/SunsetRNE/SunsetLinux/releases/download/v0.2.5/sunsetlinux-module-1.0.10.zip",
            a.url,
        )
        assertEquals("bbccdd", a.sha256)
        assertEquals(187710L, a.size)
        assertEquals("stable", a.source)
    }

    @Test
    fun `索引里没有模块信息就不猜（宁可不提示更新）`() {
        assertNull(ModuleRelease.parse("""{"schema":1,"app_version":"0.2.5"}""", "stable"))
        assertNull(ModuleRelease.parse("这不是 JSON", "stable"))
        // 有 module_version 但没有 files/tag：仍然给出产物，URL 退到 latest
        val a = ModuleRelease.parse("""{"module_version":"1.0.10"}""", "beta")!!
        assertEquals(
            "https://github.com/SunsetRNE/SunsetLinux/releases/latest/download/sunsetlinux-module-1.0.10.zip",
            a.url,
        )
        assertNull("没有 sha256 也要如实说没有，不能编一个", a.sha256)
    }

    @Test
    fun `刷入脚本要真的能跑：ksud 优先、落 tmp、带 magisk 兜底`() {
        val s = ModuleInstaller.installScript("/data/user/0/io.github.sunsetrne.sunsetlinux/cache/module/sunsetlinux-module-1.0.10.zip", "sunsetlinux-module-1.0.10.zip")
        // 1) 变量没有被 Kotlin 模板吃掉（真踩过：$c 被当成 Kotlin 变量，编译期就红）
        assertTrue("shell 变量必须是字面量：$s", s.contains("\"\$c\""))
        assertTrue(s.contains("KSUD=\"\$c\""))
        assertTrue("退出码要透传：$s", s.contains("exit \$?"))
        // 2) 关键动作
        assertTrue(s.contains("module install"))
        assertTrue(s.contains("/data/adb/ksu/bin/ksud"))
        assertTrue("先复制到 /data/local/tmp 再刷（App 私有目录的 SELinux 上下文不保证可读）", s.contains("/data/local/tmp/"))
        assertTrue(s.contains("cp '/data/user/0/io.github.sunsetrne.sunsetlinux/cache/module/sunsetlinux-module-1.0.10.zip'"))
        assertTrue("没有 ksud 时退到 magisk", s.contains("/data/adb/magisk/magisk --install-module"))
        assertTrue("两者都没有要明确报错退出 127", s.contains("exit 127"))
        // 3) 不许悄悄重启设备
        assertTrue("脚本里不许出现 reboot", !s.contains("reboot"))
    }
}
