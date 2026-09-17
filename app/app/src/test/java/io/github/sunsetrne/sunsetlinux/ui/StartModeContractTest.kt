package io.github.sunsetrne.sunsetlinux.ui

import io.github.sunsetrne.sunsetlinux.TestPaths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「操作卡置顶 + 两段式切换 + 终端紧凑 / 胶囊避让」的**源码级契约**。
 *
 * 本机没有设备/模拟器，跑不了 Compose 渲染（与 `ShellLayoutContractTest` /
 * `UiInsetsContractTest` 同一手法）：能守的就是这些**结构事实**。每条断言都对应
 * 需求里一句可验证的话，而不是凭空立的规矩：
 *
 * | 断言 | 对应需求原话 |
 * |---|---|
 * | ① 操作卡 ← ② 状态卡 ← ③ 地址卡 ← ④ 日志卡 | "把操作这个大块，移到最顶部" |
 * | 两个按钮组各只出现一次、且分别在自己那一档 | "两个模式显示不同的按钮组" |
 * | 每个按钮的 enabled 都取自 `controls.` | "启用/禁用仍然只由 StartControls 决定" |
 * | 切换的 enabled = `!switchLocked`（纯函数） | "锁定期间切换 UI 不可点" |
 * | 锁定提示来自 `startModeLockNote(` | "给一句提示：本次已按…请先「停止环境」" |
 * | TerminalPane 最外层 Column 有 `bottom = CapsuleReserve` | "悬浮导航栏也有遮挡它，终端这一块得往上抬一抬" |
 * | TerminalPane 只出现 mode 相关的文案函数 | "终端权限行、root 身份…自动切换" |
 */
class StartModeContractTest {

    private val srcDir = File(
        TestPaths.repoRoot,
        "app/app/src/main/java/io/github/sunsetrne/sunsetlinux",
    )
    private val home = File(srcDir, "ui/LauncherHomePane.kt")
    private val terminal = File(srcDir, "ui/TerminalPane.kt")
    private val shell = File(srcDir, "ui/AppShell.kt")

    private fun read(f: File): String {
        assertTrue("找不到 ${f.path}（文件被改名或移动了？）", f.isFile)
        return f.readText()
    }

    // ─────────────────────────── 批注一 · ① 操作卡置顶

    @Test
    fun `四段卡片的顺序是 操作 状态 地址 日志`() {
        val text = read(home)
        val order = listOf(
            "// ── ① 操作卡",
            "// ── ② 状态卡",
            "// ── ③ 地址卡",
            "// ── ④ 日志卡",
        ).map { marker ->
            val i = text.indexOf(marker)
            assertTrue("源码里找不到分段标记「$marker」—— 卡片顺序被改了？", i >= 0)
            i
        }
        assertTrue(
            "「操作」卡必须在最顶部（真机批注：启动是最高频动作，不该让人先滚过三段只读信息）",
            order == order.sorted(),
        )
        // 真正的渲染入口也要在状态卡之前，光挪注释不算
        assertTrue(
            "OperationCard( 的调用必须排在状态卡那段 DshCard 之前",
            text.indexOf("OperationCard(") < text.indexOf("// ── ② 状态卡"),
        )
    }

    // ─────────────────────────── 批注一 · 两组按钮 + 只由 StartControls 决定

    /** 取出 OperationCard 的函数体（到下一个组件注释为止）。 */
    private fun operationCardBody(): String {
        val text = read(home)
        val start = text.indexOf("private fun OperationCard(")
        assertTrue("找不到 OperationCard —— 结构变了？", start >= 0)
        val end = text.indexOf("两段式切换控件", start)
        assertTrue("找不到 OperationCard 之后的切换控件注释 —— 结构变了？", end > start)
        return text.substring(start, end)
    }

    @Test
    fun `一键启动那组只有主按钮与停止环境，不摊开 DSH 按钮`() {
        val body = operationCardBody()
        val oneShot = body.substringAfter("StartMode.ONE_SHOT ->", "")
            .substringBefore("StartMode.STEPWISE ->")
        assertTrue("一键启动这一档要有主按钮", oneShot.contains("PrimaryActionButton("))
        assertTrue("主按钮文案要写明是「环境 + DSH」", oneShot.contains("一键启动（环境 + DSH）"))
        assertTrue("一键启动这一档要有「停止环境」（唯一的退路）", oneShot.contains("label = \"停止环境\""))
        assertFalse(
            "一键启动这一档**不许**出现 DSH 的启停按钮 —— 那正是「五个一起摊开」的老问题",
            oneShot.contains("label = \"启动 DSH\"") || oneShot.contains("label = \"停止 DSH\""),
        )
    }

    @Test
    fun `分步启动那组是四个按钮的 2×2`() {
        val body = operationCardBody()
        val stepwise = body.substringAfter("StartMode.STEPWISE ->", "")
        for (label in listOf("仅启动环境", "停止环境", "启动 DSH", "停止 DSH")) {
            assertTrue("分步启动这一档要有「$label」", stepwise.contains("label = \"$label\""))
        }
        assertTrue("沿用原来的 2×2：至少两个 Row", Regex("""Row\(""").findAll(stepwise).count() >= 2)
    }

    @Test
    fun `每个启动按钮的启用都只来自 StartControls 纯函数`() {
        val body = operationCardBody()
        // 五类按钮的 enabled 必须直接引用 controls 上的字段 —— 出现第二份判定
        // （例如 enabled = running、enabled = true）就说明有人在界面里另写了一套矩阵。
        for (field in listOf(
            "controls.oneShotEnabled",
            "controls.startEnvOnlyEnabled",
            "controls.envStopEnabled",
            "controls.dshStartEnabled",
            "controls.dshStopEnabled",
        )) {
            assertTrue("OperationCard 里必须用 $field 决定按钮亮/灰", body.contains(field))
        }
        assertFalse("不许在界面里另写一份判定（enabled = true 这种）", body.contains("enabled = true"))
    }

    // ─────────────────────────── 批注一 · 运行中锁定

    @Test
    fun `运行中锁定走纯函数，切换控件真的不可点`() {
        val body = operationCardBody()
        assertTrue(
            "锁定判定必须调 isStartModeLocked（纯函数 + 单测），不许在界面里写 running",
            body.contains("isStartModeLocked(running)"),
        )
        assertTrue(
            "切换控件必须接 !switchLocked 作为 enabled（否则锁定期还能点）",
            body.contains("enabled = !switchLocked"),
        )
        // 控件本身：两段都要 clickable(enabled = ...)，disabled 时点不动
        val text = read(home)
        val toggle = text.substringAfter("private fun StartModeToggle(")
        assertTrue(
            "切换控件里两段必须 `clickable(enabled = enabled)` —— 这才是「真的点不动」",
            toggle.contains("clickable(enabled = enabled)"),
        )
        assertTrue("锁定时还要有视觉上的不可用（降透明度）", toggle.contains("alpha(if (enabled) 1f else 0.55f)"))
    }

    @Test
    fun `锁定提示来自纯函数而不是界面里手拼的字符串`() {
        val body = operationCardBody()
        assertTrue(
            "锁定提示必须调 startModeLockNote(...)（内容是纯函数常量，单测钉住了三档措辞）",
            body.contains("startModeLockNote(running, ui.status?.envMode)"),
        )
    }

    @Test
    fun `显示哪一组由 resolveStartMode 决定（界面不自己判）`() {
        val body = operationCardBody()
        assertTrue(
            "必须调 resolveStartMode(envRunning/envMode/userChoice)",
            body.contains("resolveStartMode(") && body.contains("userChoice = ui.startMode"),
        )
    }

    @Test
    fun `免 root 版不渲染切换与分步按钮`() {
        val body = operationCardBody()
        assertTrue(
            "切换控件与分步按钮必须在 `if (Edition.showsSplitStartUi)` 分支里",
            body.contains("if (Edition.showsSplitStartUi) {"),
        )
        val gate = body.indexOf("if (Edition.showsSplitStartUi) {")
        assertTrue("切换控件在 edition 门禁之后", body.indexOf("StartModeToggle(") > gate)
        assertTrue("分步按钮组在 edition 门禁之后", body.indexOf("StartMode.STEPWISE ->") > gate)
        // 免 root 分支仍然保留原来的启动/停止语义
        assertTrue(body.contains("\"启动环境\"") && body.contains("\"停止环境\""))
    }

    // ─────────────────────────── 批注二 · 终端紧凑与胶囊避让

    @Test
    fun `终端页最外层 Column 给胶囊底栏留了位`() {
        val text = read(terminal)
        assertTrue(
            "TerminalPane 最外层 Column 必须有 `bottom = CapsuleReserve` —— " +
                "否则快捷键行与输入框会被悬浮胶囊整块盖住（真机批注）",
            text.contains("bottom = CapsuleReserve"),
        )
        // 常量本身来自 Common.kt，且不许再写死数字
        assertFalse("不许写死 96.dp", Regex("""bottom\s*=\s*96\.dp""").containsMatchIn(text))
    }

    @Test
    fun `终端页的文案跟着 mode 与连接态自动变`() {
        val text = read(terminal)
        assertTrue("身份行要调纯函数", text.contains("TerminalStatusUi.identityLabel(state.running, mode)"))
        assertTrue("权限行要调纯函数", text.contains("TerminalStatusUi.privilegeLine(mode)"))
        assertTrue(
            "引擎行要调纯函数（降级模式的能力差异必须保留）",
            text.contains("TerminalStatusUi.engineLine(PtyNative.available, PtyNative.loadError)"),
        )
        assertTrue("未连接时要给一句可操作提示", text.contains("TerminalStatusUi.NOT_CONNECTED_HINT"))
        // 反面：那句"同时讲两种模式"的旧静态文案必须消失（它就是批注里说的"文案有问题"）
        assertFalse(
            "旧的静态说明（同时讲 root 与 proot 两种模式）必须删掉",
            text.contains("proot 模式进 proot 的 bash"),
        )
    }

    @Test
    fun `终端副标题改成了完整的一句话`() {
        val text = read(shell)
        assertTrue(
            "终端 tab 的副标题必须走 TerminalStatusUi.subtitle（通用的 stage 短语会被顶栏裁成半句）",
            text.contains("TerminalStatusUi.subtitle(ui.state == EnvState.RUNNING, ui.mode)"),
        )
        assertTrue("要按 tab 分岔，只有终端页换这句", text.contains("tab == ShellTab.TERMINAL"))
        assertTrue(
            "放宽 maxLines 只能对终端页（其它页保持原来的一行裁剪，不动既有观感）",
            text.contains("maxLines = if (terminalTab) 2 else 1"),
        )
    }
}
