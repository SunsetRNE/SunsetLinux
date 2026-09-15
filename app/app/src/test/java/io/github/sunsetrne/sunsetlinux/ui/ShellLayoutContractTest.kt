package io.github.sunsetrne.sunsetlinux.ui

import io.github.sunsetrne.sunsetlinux.TestPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 外壳导航与内置终端的**源码级契约**（与 UiInsetsContractTest 同一手法：跑不了 Compose 渲染，
 * 能守的就是这些结构事实）。
 *
 * 每一条都对应一次"真机上被用户看见"的调整或缺陷，不是凭空立的规矩：
 *
 * | 断言 | 对应现象 / 需求 |
 * |---|---|
 * | 底栏只渲染 `inCapsule` 的页 | 底栏塞了 4 项时 DSH 标签被挤到换行；用户要求"DSH 挪到上面" |
 * | DSH 在顶栏有入口 | 点开是 DSH Web，返回回主界面 |
 * | 更新在侧边栏有入口 | 底栏腾出来给终端，更新仍要够得着 |
 * | 终端走 `linuxctl attach` 且要求环境已运行 | 否则只会得到一行 "环境未运行" 的报错 |
 * | 会话挂在外壳上并在销毁时断开 | 切 tab 不该断开会话；退出 App 不该留下孤儿 shell |
 */
class ShellLayoutContractTest {

    private val srcDir = File(
        TestPaths.repoRoot,
        "app/app/src/main/java/io/github/sunsetrne/sunsetlinux",
    )
    private val shell = File(srcDir, "ui/AppShell.kt")
    private val terminalPane = File(srcDir, "ui/TerminalPane.kt")
    private val terminalSession = File(srcDir, "core/TerminalSession.kt")
    private val linuxCtl = File(srcDir, "core/LinuxCtl.kt")

    private fun read(f: File): String {
        assertTrue("找不到 ${f.path}（文件被改名或移动了？）", f.isFile)
        return f.readText()
    }

    private fun enumFlags(): Map<String, Boolean> {
        val text = read(shell)
        val body = Regex("""enum class ShellTab[^{]*\{([^}]*)\}""").find(text)
            ?.groupValues?.get(1)
        assertTrue("解析不出 ShellTab 枚举体：AppShell.kt 的结构变了？", body != null)
        return Regex("""(\w+)\("[^"]*",\s*[^,]+,\s*(true|false)\s*\)""")
            .findAll(body!!)
            .associate { it.groupValues[1] to (it.groupValues[2] == "true") }
    }

    @Test
    fun `底栏只放 启动-插件-终端（DSH 与更新不在底栏）`() {
        val flags = enumFlags()
        assertEquals(
            "底栏（inCapsule = true）应当正好是 启动/插件/终端 —— " +
                "DSH 已按用户要求移到顶栏、更新移到侧边栏，别再加回底栏（会再次把标签挤到换行）",
            mapOf("START" to true, "PLUGINS" to true, "TERMINAL" to true),
            flags.filterValues { it },
        )
        assertEquals("UPDATE 不应出现在底栏", false, flags["UPDATE"])
        assertEquals("DSH 不应出现在底栏", false, flags["DSH"])
    }

    @Test
    fun `胶囊底栏按 inCapsule 过滤而不是遍历全部 tab`() {
        val text = read(shell)
        assertTrue(
            "CapsuleBar 必须 `ShellTab.entries.filter { it.inCapsule }`，" +
                "否则新增的页（更新/DSH/以后更多）会全部挤进底栏",
            text.contains("ShellTab.entries.filter { it.inCapsule }"),
        )
    }

    @Test
    fun `DSH 在顶栏有入口`() {
        assertTrue(
            "顶栏应当有一个打开 DSH 的按钮（contentDescription = \"打开 DSH\"）",
            read(shell).contains("contentDescription = \"打开 DSH\""),
        )
    }

    @Test
    fun `更新在侧边栏有入口`() {
        val text = read(shell)
        assertTrue("侧边栏要有「更新」项", text.contains("DrawerItem(Icons.Filled.Refresh, \"更新\""))
        assertTrue("「更新」项要能切到 UPDATE 页", text.contains("onOpenUpdates"))
    }

    @Test
    fun `终端页接在外壳上且有内核会话实现`() {
        val text = read(shell)
        assertTrue("AppShell 的 when(tab) 里要有 TERMINAL 分支", text.contains("ShellTab.TERMINAL -> TerminalPane("))
        // 会话必须挂在外壳上（切 tab 不销毁），并在外壳销毁时断开
        assertTrue(
            "终端状态必须在 AppShell 顶层 remember（而不是 tab 分支里），否则切走再回来会话就没了",
            Regex("""val terminalState = rememberTerminalPaneState\(\)""").containsMatchIn(text),
        )
        assertTrue("外壳销毁时要 dispose 终端会话，避免留下孤儿 shell", text.contains("terminalState.dispose()"))
        assertTrue("TerminalPane.kt 不存在？", terminalPane.isFile)
        assertTrue("TerminalSession.kt 不存在？", terminalSession.isFile)
    }

    @Test
    fun `终端走 linuxctl attach，且先确认环境在运行`() {
        val ctl = read(linuxCtl)
        assertTrue(
            "终端必须用 `linuxctl attach`（root 模式会 nsenter+chroot 进 rootfs、proot 模式进 proot 的 bash）",
            ctl.contains("shellCommand(listOf(\"attach\"))") && ctl.contains("prootCommand(listOf(\"attach\"))"),
        )
        val pane = read(terminalPane)
        assertTrue(
            "起会话前必须先判环境是否 RUNNING —— attach 进的是已存在的 mount namespace，没起来时只会报错退出",
            pane.contains("EnvState.RUNNING"),
        )
    }

    @Test
    fun `终端会话明确声明没有 PTY 的限制`() {
        // 这条不是形式主义：用户看到"终端"会理所当然地以为能跑 vim/htop，
        // 代码里必须留下"这是刻意的取舍"，避免后人把它当成 bug 去修一半。
        val session = read(terminalSession)
        assertTrue("TerminalSession.kt 要写明没有 PTY（行缓冲、无作业控制）", session.contains("PTY"))
        assertTrue(
            "界面上也要有这条提示（终端页的空状态里）",
            read(terminalPane).contains("vim"),
        )
    }
}
