package io.github.sunsetrne.sunsetlinux.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端页那几行"跟着状态自动变"的文案（[TerminalStatusUi]）。
 *
 * ## 为什么文案也要单测
 *
 * 真机批注点了两处文案问题：「终端权限行、root 身份」。它们的特点是**必须跟着
 * edition / 连接态自动切换**，而"切了两个分支只改一个"是这类代码最常见的缺陷
 * （改完 root 版那句，免 root 版还留着旧说法）。真机上两种 edition 装在不同手机上，
 * 很容易只验证了手边这一台 —— 单测把每一档都钉住。
 */
class TerminalStatusUiTest {

    // ─────────────────────────── 权限行：两个 edition 各说各的事实

    @Test
    fun `Root 版的权限行说的是 nsenter 加 chroot 的真 root 路径`() {
        val line = TerminalStatusUi.privilegeLine(EnvMode.ROOT)
        assertTrue("要说清是 root 身份：$line", line.contains("root 身份"))
        assertTrue("要说清怎么进的环境（nsenter）：$line", line.contains("nsenter"))
        assertTrue("要说清落到哪个 rootfs：$line", line.contains("chroot"))
        assertTrue("root 版的环境根在 /data/sunsetlinux：$line", line.contains("/data/sunsetlinux/rootfs"))
        // 反方向：Root 版**不许**再提 proot —— 提另一个 edition 的东西就是噪音
        assertFalse("Root 版的权限行不该出现 proot：$line", line.contains("proot"))
    }

    @Test
    fun `免 root 版的权限行必须点明是伪造的 root 且没有真实权限`() {
        val line = TerminalStatusUi.privilegeLine(EnvMode.PROOT)
        assertTrue("要说清身份是 proot 伪造的：$line", line.contains("proot"))
        assertTrue(
            "必须点明『没有真实权限』—— 用户最容易误以为里面那个 root 真有系统权限",
            line.contains("没有真实权限"),
        )
        assertTrue("要说清环境根在 App 私有目录（能力差异的关键）：$line", line.contains("App 私有目录"))
        // 反方向：免 root 版不许提 nsenter/chroot（那台机器上根本没有真 chroot）
        assertFalse("免 root 版的权限行不该出现 nsenter：$line", line.contains("nsenter"))
        assertFalse("免 root 版的权限行不该出现 /data/sunsetlinux：$line", line.contains("/data/sunsetlinux"))
    }

    @Test
    fun `两个 edition 的权限行一定不同（旧版那句同时讲两种模式就是问题本身）`() {
        val root = TerminalStatusUi.privilegeLine(EnvMode.ROOT)
        val proot = TerminalStatusUi.privilegeLine(EnvMode.PROOT)
        assertFalse("权限行必须跟着 mode 变，不能是一句同时讲两种模式的静态文案", root == proot)
    }

    // ─────────────────────────── 身份行：连上之前不许编造

    @Test
    fun `未连接时不显示身份（拿不到就不显示，不编造）`() {
        assertNull("没连上时我们对环境内一无所知", TerminalStatusUi.identityLabel(connected = false, mode = EnvMode.ROOT))
        assertNull(TerminalStatusUi.identityLabel(connected = false, mode = EnvMode.PROOT))
    }

    @Test
    fun `连接后按 mode 给出真实身份`() {
        assertEquals("uid 0（root）", TerminalStatusUi.identityLabel(connected = true, mode = EnvMode.ROOT))
        assertEquals(
            "伪 root（无真实 capabilities）",
            TerminalStatusUi.identityLabel(connected = true, mode = EnvMode.PROOT),
        )
    }

    @Test
    fun `身份行必须反映 mode 而不是恒定的一个 ROOT 标签`() {
        // 旧版顶栏是一个写死的 `Pill(mode.modeLabel)`（"ROOT"）：既没说连接态，
        // 也没说清环境里是什么身份。这里守住"两种 mode 给出不同结论"。
        val root = TerminalStatusUi.identityLabel(connected = true, mode = EnvMode.ROOT)
        val proot = TerminalStatusUi.identityLabel(connected = true, mode = EnvMode.PROOT)
        assertFalse("两种 mode 的身份不能是同一句话", root == proot)
    }

    // ─────────────────────────── 引擎行：降级说明必须保留

    @Test
    fun `PTY 就绪时压成一行，但仍要说清能力`() {
        val line = TerminalStatusUi.engineLine(ptyAvailable = true, ptyUnavailableReason = null)
        assertTrue("要提 Ctrl-C（PTY 最实际的收益）", line.contains("Ctrl-C"))
        assertTrue("要提全屏程序能跑", line.contains("vim") && line.contains("htop"))
        assertTrue("一行说明（不能带换行）", !line.contains("\n"))
    }

    @Test
    fun `降级时必须完整保留能力差异（这是用户必须知道的）`() {
        val line = TerminalStatusUi.engineLine(
            ptyAvailable = false,
            ptyUnavailableReason = "UnsatisfiedLinkError: 加载 sunsetlinux_pty 失败",
        )
        assertTrue("要说清没有 PTY", line.contains("原生 PTY 不可用"))
        assertTrue("要带上原因，否则用户没法排障", line.contains("UnsatisfiedLinkError"))
        assertTrue("要说明全屏程序不可用", line.contains("全屏程序"))
        assertTrue("要说明 Ctrl-C 不可用", line.contains("Ctrl-C"))
    }

    @Test
    fun `降级但拿不到原因时也要能成句（不出现 null 字样）`() {
        val line = TerminalStatusUi.engineLine(ptyAvailable = false, ptyUnavailableReason = null)
        assertTrue("原因缺失时给『未知原因』而不是 null", line.contains("未知原因"))
        assertFalse(line.contains("null"))
    }

    // ─────────────────────────── 副标题：不能是半截话

    @Test
    fun `终端副标题按环境与 mode 自动变，且都是完整的短句`() {
        val down = TerminalStatusUi.subtitle(envRunning = false, mode = EnvMode.ROOT)
        assertTrue("环境没跑时说清前置条件：$down", down.contains("环境未运行"))
        assertTrue("要给下一步（去启动页）：$down", down.contains("启动"))

        val root = TerminalStatusUi.subtitle(envRunning = true, mode = EnvMode.ROOT)
        assertTrue("Root 版要说清以 root 身份进入：$root", root.contains("root"))
        val proot = TerminalStatusUi.subtitle(envRunning = true, mode = EnvMode.PROOT)
        assertTrue("免 root 版不许照抄 root 版的说法：$proot", proot.contains("proot"))
        assertFalse("两句必须不同", root == proot)

        for (s in listOf(down, root, proot)) {
            assertFalse("副标题不能以省略号收尾（截图里那句就是被裁得看不出意思）：$s", s.endsWith("…"))
            assertFalse("也不带换行（顶栏自己控制 maxLines）", s.contains("\n"))
            // 顶栏留给副标题的宽度只有十几个汉字 —— 写长了又会被裁成半句话（2 行也不够）。
            // 这是"那句话会被截断"这个真机问题的可测代理指标，不是随便定的数字。
            assertTrue("副标题必须短到能在顶栏两行内说完（现在 ${s.length} 字）：$s", s.length <= 16)
        }
    }

    @Test
    fun `未连接的提示是一句可操作的话，连上后由界面负责消失`() {
        val hint = TerminalStatusUi.NOT_CONNECTED_HINT
        assertTrue("要告诉用户点哪里：$hint", hint.contains("连接"))
        assertFalse("不能带省略号", hint.endsWith("…"))
    }
}
