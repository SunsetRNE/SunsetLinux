package io.github.sunsetrne.sunsetlinux.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * VT 最小模拟器的行为测试。
 *
 * 为什么这块必须单测：终端屏幕是"看起来对不对"的东西，而它**全是纯逻辑**——
 * `\r` 覆盖、`\b` 擦除、`ESC[K` 擦到行尾、滚屏、UTF-8 分片，任何一条错了，
 * 用户看到的就是花屏（apt 进度条乱跳、python 提示符错位），而且**很难复现**。
 * 下面每条断言都对应真实程序会发出的字节序列。
 */
class TerminalEmulatorTest {

    private fun emu(rows: Int = 6, cols: Int = 20) = TerminalEmulator(rows, cols)

    private fun TerminalEmulator.feedText(s: String) = feed(s.toByteArray(Charsets.UTF_8))

    @Test
    fun `回车覆盖同一行（apt 进度条就是这样刷的）`() {
        val e = emu()
        e.feedText("下载中 10%\r下载中 90%\r下载完成")
        assertEquals("下载完成", e.snapshot().lines[0])
    }

    @Test
    fun `CRLF 不会把上一行擦掉（有意区别于裸 CR）`() {
        val e = emu()
        e.feedText("第一行\r\n第二行")
        assertEquals("第一行", e.snapshot().lines[0])
    }

    @Test
    fun `换行推进一行，回车回到行首（回车与换行是两件事）`() {
        val e = emu()
        e.feedText("第一行\r\n第二行")
        val s = e.snapshot()
        assertEquals("第一行", s.lines[0])
        assertEquals("第二行", s.lines[1])
        assertEquals(1, s.cursorRow)
        assertEquals(3, s.cursorCol)
    }

    @Test
    fun `退格擦掉一个字符（y_n 输入回显）`() {
        val e = emu()
        e.feedText("abc\b\bXY")
        assertEquals("aXY", e.snapshot().lines[0])
    }

    @Test
    fun `CSI K 擦到行尾（程序清掉旧进度）`() {
        val e = emu()
        e.feedText("旧的进度条 100%\r新的\u001b[K")
        assertEquals("新的", e.snapshot().lines[0])
    }

    @Test
    fun `CSI 2J 清屏并把光标归位`() {
        val e = emu()
        e.feedText("垃圾\n垃圾\n垃圾")
        e.feedText("\u001b[2J\u001b[H")
        val s = e.snapshot()
        assertEquals(listOf("", "", "", "", "", ""), s.lines)
        assertEquals(0, s.cursorRow)
        assertEquals(0, s.cursorCol)
    }

    @Test
    fun `CSI 定位 + SGR 剥离（全屏程序画格子）`() {
        val e = emu()
        // 光标到第 3 行第 5 列，写红色 "X"，再回第 1 行第 1 列写 "TOP"
        e.feedText("\u001b[3;5H\u001b[31mX\u001b[0m\u001b[1;1HTOP")
        val s = e.snapshot()
        assertEquals("TOP", s.lines[0].trimEnd())
        assertEquals("    X", s.lines[2])
        // SGR 不能被当正文印出来
        assertEquals(false, s.lines.any { it.contains("[31m") })
    }

    @Test
    fun `写满最后一行会滚屏（最上一行丢掉）`() {
        val e = emu(rows = 3, cols = 10)
        e.feedText("1\r\n2\r\n3\r\n4")
        assertEquals(listOf("2", "3", "4"), e.snapshot().lines)
    }

    @Test
    fun `UTF-8 分片到达也能正确解码（一个汉字可能被切成两次 read）`() {
        val e = emu()
        val bytes = "中文测试".toByteArray(Charsets.UTF_8)
        // 故意在汉字中间切开
        e.feed(bytes, 0, 4)
        e.feed(bytes, 4, bytes.size)
        assertEquals("中文测试", e.snapshot().lines[0])
    }

    @Test
    fun `OSC 标题被记下来，且不污染屏幕`() {
        val e = emu()
        e.feedText("\u001b]0;root@sunsetlinux: ~\u0007$ ")
        val s = e.snapshot()
        assertEquals("root@sunsetlinux: ~", s.title)
        // 行尾空白在快照里被 trim（渲染成文本视图时贴边空格没意义）
        assertEquals("$", s.lines[0])
    }

    @Test
    fun `resize 保留已有内容，光标夹回边界`() {
        val e = emu(rows = 4, cols = 10)
        e.feedText("hello")
        e.resize(10, 30)
        val s = e.snapshot()
        assertEquals(10, s.lines.size)
        assertEquals("hello", s.lines[0])
        assertEquals(0, s.cursorRow)
        assertEquals(5, s.cursorCol)
    }

    @Test
    fun `reset 之后是干净屏幕`() {
        val e = emu()
        e.feedText("abc\u001b]0;t\u0007")
        e.reset()
        val s = e.snapshot()
        assertEquals("", s.lines[0])
        assertNull(s.title)
    }
}
