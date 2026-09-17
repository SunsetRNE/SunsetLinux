package io.github.sunsetrne.sunsetlinux.ui

import io.github.sunsetrne.sunsetlinux.TestPaths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「设置页太长 → 按侧边栏入口拆成独立页」的**源码级契约**。
 *
 * 与 `ShellLayoutContractTest` / `UiInsetsContractTest` 同一手法：本机跑不了 Compose 渲染，
 * 能守的就是这些结构事实。每条都对应一次真机反馈或一个会被用户看见的缺陷：
 *
 * | 断言 | 对应现象 / 需求 |
 * |---|---|
 * | 源卡片不在设置页里，而在独立的 `SourcesPane` | 用户："源独立页"、"设置页实在是太长了" |
 * | 频道 / 省电也各自成页 | 侧边栏本来就有对应入口，入口与页面要一对一 |
 * | 三个新页面 `inCapsule = false` | 底栏只留启动/插件/终端，多一个就把标签挤到换行 |
 * | 侧边栏不再深链 `onOpenSettings("channels"/"npm"/"power")` | 深链就是"没有独立页"的证据 |
 * | npm 默认档走 core 的纯函数，不许 `?: CUSTOM_ID` | 真机打开就选中「自定义」的那个缺陷 |
 * | 设置页仍保留端口/自启/关于 | 拆的是"已有入口的内容"，不是把设置页掏空 |
 */
class PageSplitContractTest {

    private val srcDir = File(
        TestPaths.repoRoot,
        "app/app/src/main/java/io/github/sunsetrne/sunsetlinux",
    )

    private fun read(rel: String): String {
        val f = File(srcDir, rel)
        assertTrue("找不到 ${f.path}（文件被改名或移动了？）", f.isFile)
        return f.readText()
    }

    /**
     * 去掉注释行后的源码。
     *
     * 断"某个东西不许再出现"时**必须**用它：设置页的文件头会解释"频道/省电/npm 源
     * 搬去了哪些页面"，直接 contains 会把解释性注释也算成违例。
     */
    private fun codeOnly(text: String): String = text.lineSequence()
        .filterNot {
            val t = it.trim()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

    private fun assertHasTab(shell: String, name: String) {
        assertTrue(
            "ShellTab 里应当有 $name（侧边栏独立页）",
            Regex("""$name\("([^"]*)",\s*[^,]+,\s*(true|false)\s*\)""").containsMatchIn(shell),
        )
        assertTrue(
            "$name 必须在底栏之外（inCapsule = false）：底栏只留启动/插件/终端",
            Regex("""$name\("[^"]*",\s*[^,]+,\s*false\s*\)""").containsMatchIn(shell),
        )
    }

    @Test
    fun `源设置是独立页而不是设置页里的一张卡`() {
        val settings = read("SettingsActivity.kt")
        assertFalse(
            "设置页不许再直接渲染 NpmSourceCard —— 源设置已经有自己的页面了",
            codeOnly(settings).contains("NpmSourceCard"),
        )
        val pane = read("ui/SourcesPane.kt")
        assertTrue("SourcesPane 要渲染 npm 卡片", pane.contains("NpmSourceCard("))
        assertTrue("SourcesPane 要渲染 Python 卡片", pane.contains("PypiSourceCard("))
        assertTrue("Python 卡片要写在 PypiSourceCard.kt 里", read("ui/PypiSourceCard.kt").contains("PypiRegistry"))
    }

    @Test
    fun `npm 与 Python 的默认档都由 core 的纯函数决定`() {
        val npm = read("ui/NpmSourceCard.kt")
        val pypi = read("ui/PypiSourceCard.kt")
        assertTrue(npm.contains("NpmRegistry.selectedPresetId("))
        assertTrue(pypi.contains("PypiRegistry.selectedPresetId("))
        // 反面断言：这条正是真机缺陷的写法（prefs 为空 → 反查 null → 落进自定义）
        assertFalse(
            "不许再用 `?: CUSTOM_ID` 兜底：没选过时它会落进「自定义」并显示成被选中",
            npm.contains("?: NpmRegistry.CUSTOM_ID"),
        )
        assertFalse(pypi.contains("?: PypiRegistry.CUSTOM_ID"))
    }

    @Test
    fun `频道与省电各自成页，设置页只留通用项`() {
        val settings = read("SettingsActivity.kt")
        val settingsCode = codeOnly(settings)
        for (gone in listOf("ChannelDialog", "FreezeExemptionCard", "同步到环境", "申请白名单", "npm 源")) {
            assertFalse("「$gone」应当已经搬出设置页", settingsCode.contains(gone))
        }
        for (kept in listOf("服务端口", "服务与自启", "快捷入口", "关于")) {
            assertTrue("通用项「$kept」应当留在设置页", settings.contains(kept))
        }
        assertTrue(read("ui/ChannelsPane.kt").contains("ChannelDialog("))
        assertTrue(read("ui/PowerPane.kt").contains("FreezeExemptionCard("))
        // edition 分岔也要跟着走：免 root 版不许出现模块操作指引
        assertTrue(
            "省电页的冻结豁免指引必须仍按 Edition.showsModuleUi 分岔",
            read("ui/PowerPane.kt").contains("if (Edition.showsModuleUi)"),
        )
    }

    @Test
    fun `三个新页面在侧边栏有入口、不进底栏、且不再深链设置页`() {
        val shell = read("ui/AppShell.kt")
        assertHasTab(shell, "SOURCES")
        assertHasTab(shell, "CHANNELS")
        assertHasTab(shell, "POWER")
        assertTrue("侧边栏要有「源与镜像」入口", shell.contains("\"源与镜像\""))
        assertTrue("侧边栏要有「频道管理」入口", shell.contains("\"频道管理\""))
        assertTrue("侧边栏要有「冻结与省电豁免」入口", shell.contains("\"冻结与省电豁免\""))
        for (cb in listOf("onOpenSources", "onOpenChannels", "onOpenPower")) {
            assertTrue("侧边栏入口要接到 $cb", shell.contains(cb))
        }
        for (section in listOf("channels", "power", "npm")) {
            assertFalse(
                "不该再深链进设置页的分区「$section」—— 独立页是一对一的入口",
                codeOnly(shell).contains("onOpenSettings(\"$section\")"),
            )
        }
        // 顶栏标题与内容分支都要有（when 是穷尽的，漏一个编译就不过；这条守的是"别只留占位"）
        for (tab in listOf("SOURCES", "CHANNELS", "POWER")) {
            assertTrue("内容分支缺 ShellTab.$tab", shell.contains("ShellTab.$tab ->"))
        }
    }

    @Test
    fun `设置页不再需要分区深链`() {
        val settings = read("SettingsActivity.kt")
        assertFalse(settings.contains("EXTRA_SETTINGS_SECTION"))
        assertFalse("section 深链参数随三块内容一起删掉了", codeOnly(settings).contains("animateScrollTo"))
        assertTrue("settingsIntent 仍然只有上下文一个参数", settings.contains("fun settingsIntent(context: android.content.Context): Intent"))
    }
}
