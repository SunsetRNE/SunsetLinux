package io.github.sunsetrne.sunsetlinux.ui

import io.github.sunsetrne.sunsetlinux.TestPaths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DSH 网页"覆盖整窗"策略的回归（纯逻辑 + 源码级契约，与 `UiInsetsContractTest` 同一手法）。
 *
 * 每一条都对应一次"用户看得见"的诉求或缺陷，不是凭空立的规矩：
 *
 * | 断言 | 对应的诉求 / 现象 |
 * |---|---|
 * | DSH 覆盖整窗、且不收系统栏以外的页 | 用户："原来的约束（在应用内渲染）改为**覆盖应用全屏渲染显示**" |
 * | 覆盖整窗 ⇒ 必须收起系统栏 | 不收就把网页又切掉两条（上下各一条） |
 * | 覆盖整窗 ⇒ 必须有常驻返回控件 | 系统栏收起来后"看不见出口"，网页应用还常自己吃掉返回键 |
 * | 壳里必须走 `DshFullscreen` 判据而不是写死 `ShellTab.DSH` | 判据散落 ⇒ 以后加"全屏页"必然漏改一处 |
 * | 通知栏入口同样整窗 | 同一个问题两条入口都要治（用户明确说"两类模式都有这个设计问题"） |
 * | 覆盖时必须先回网页历史 | 全屏网页应用里"返回"应当先退页面，再回壳 |
 */
class DshFullscreenContractTest {

    private val srcDir = File(
        TestPaths.repoRoot,
        "app/app/src/main/java/io/github/sunsetrne/sunsetlinux",
    )
    private val shell = File(srcDir, "ui/AppShell.kt")
    private val pane = File(srcDir, "ui/DshWebPane.kt")
    private val activity = File(srcDir, "DshWebActivity.kt")
    private val policy = File(srcDir, "ui/DshFullscreen.kt")

    private fun read(f: File): String {
        assertTrue("找不到 ${f.path}（文件被改名或移动了？）", f.isFile)
        return f.readText()
    }

    // ─────────────────────────── ① 纯策略

    @Test
    fun `只有 DSH 页覆盖整窗，其它页照旧`() {
        assertTrue("DSH 必须覆盖整窗", DshFullscreen.coversWindow(ShellTab.DSH))
        for (t in ShellTab.entries.filter { it != ShellTab.DSH }) {
            assertFalse("$t 不该覆盖整窗（否则底栏/顶栏就没法用了）", DshFullscreen.coversWindow(t))
            assertFalse("$t 不该收起系统栏", DshFullscreen.hidesSystemBars(t))
        }
    }

    @Test
    fun `覆盖整窗的页面必须收起系统栏、并且必须有常驻返回出口`() {
        for (t in ShellTab.entries) {
            if (!DshFullscreen.coversWindow(t)) continue
            assertTrue("$t 覆盖整窗却没收起系统栏（网页又被切掉上下两条）", DshFullscreen.hidesSystemBars(t))
            assertTrue("$t 覆盖整窗却没有常驻返回控件（系统栏收起来后看不见出口）", DshFullscreen.needsBackAffordance(t))
        }
    }

    // ─────────────────────────── ② 壳里的接线

    @Test
    fun `壳里必须按 DshFullscreen 判据画浮层，且用 fullBleed`() {
        val text = read(shell)
        assertTrue(
            "AppShell 必须用 DshFullscreen.coversWindow(tab) 判定覆盖整窗（不许写死 ShellTab.DSH —— 判据散落必然漏改）",
            text.contains("DshFullscreen.coversWindow(tab)"),
        )
        assertTrue("壳里画 DSH 时必须 fullBleed = true", text.contains("fullBleed = true"))
        assertTrue("壳里必须挂 ImmersiveBarsEffect（否则系统栏不会收起/恢复）", text.contains("ImmersiveBarsEffect("))
        assertFalse(
            "壳里不该再出现旧的“底栏放内容下方（不遮挡网页）”那种夹心写法",
            text.contains("底栏放内容下方"),
        )
    }

    @Test
    fun `通知栏入口同样整窗 + 沉浸`() {
        val text = read(activity)
        assertTrue("DshWebActivity 必须 fullBleed = true", text.contains("fullBleed = true"))
        assertTrue("DshWebActivity 必须挂 ImmersiveBarsEffect", text.contains("ImmersiveBarsEffect("))
        assertTrue("DshWebActivity 必须保留回退出口（onBack = finish）", text.contains("onBack = { finish() }"))
    }

    @Test
    fun `面板在全屏时给悬浮返回壳，并且只有它避开系统栏`() {
        val text = read(pane)
        assertTrue("面板要有 fullBleed 形参", text.contains("fullBleed: Boolean"))
        assertTrue("面板要有悬浮「返回壳」", text.contains("返回壳"))
        assertTrue(
            "悬浮键必须用 statusBarsPadding 避开状态栏（否则压在最上面点不到）",
            text.contains("statusBarsPadding"),
        )
        assertTrue("面板必须继续消费 IME inset（全屏后键盘更容易盖住输入框）", text.contains("imePadding"))
    }

    // ─────────────────────────── ③ 返回语义

    @Test
    fun `覆盖整窗时返回必须先回网页历史、再回壳`() {
        val text = read(pane)
        assertTrue(
            "面板必须用 PredictiveBackHandler（跟手），且先 canGoBack 再 onBack",
            text.contains("PredictiveBackHandler") && text.contains("canGoBack()"),
        )
    }

    @Test
    fun `策略文件必须写清为什么（下一个人不该把它当多余抽象删掉）`() {
        val text = read(policy)
        assertTrue("策略文件要写明用户诉求原话", text.contains("覆盖应用全屏渲染显示"))
        assertTrue("策略文件要写明回壳的三条路", text.contains("返回壳"))
    }
}
