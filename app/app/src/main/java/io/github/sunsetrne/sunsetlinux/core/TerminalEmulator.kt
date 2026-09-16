package io.github.sunsetrne.sunsetlinux.core

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CoderResult
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 最小 VT 模拟器：把 PTY 的**字节流**变成"屏幕上现在长什么样"。
 *
 * ## 为什么需要它
 *
 * 以前终端把输出按行拼成文本就直接显示，于是 `apt` 的进度条、`python` 的 `>>> ` 提示、
 * `ssh` 的密码提示全是乱的（`\r` 覆盖、`\b` 擦除、`ESC[K` 擦到行尾都当普通字符印出来）。
 * 现在有了真 PTY，程序会**按终端语义**输出，我们必须至少理解这几个控制序列，
 * 否则屏幕会比以前更花。
 *
 * ## 支持范围（刻意克制）
 *
 * | 序列 | 处理 |
 * |---|---|
 * | `\r` `\n` `\b` `\t` | 光标语义（回车、换行、退格、制表位 8 列） |
 * | `ESC [ K` | 擦到行尾 / 行首 / 整行 |
 * | `ESC [ J` | 清屏（2）、清到末尾（0） |
 * | `ESC [ H` `ESC [ <r>;<c> H/f` | 光标定位（全屏程序的画布定位） |
 * | `ESC [ A/B/C/D` `ESC [ G` | 光标上下左右移动、设置列 |
 * | `ESC [ m` … | SGR 颜色/样式：**剥离**（本期不做彩色，颜色需要额外 16 色映射） |
 * | `ESC ] … BEL/ST` | OSC（窗口标题：记下来；其余忽略） |
 * | 其他 CSI/ESC | 吞掉（当不可见控制序列，不污染屏幕） |
 *
 * 不做的：滚动区（`ESC[r`）、备用屏幕（`?1049h/l`）、鼠标上报、像素级字宽 ——
 * 这些等有了真实用户反馈再说；全屏程序在 80/24 与固定滚动下的表现已经"能看"。
 *
 * ## 为什么放在 core（纯 Kotlin、零 Android 依赖）
 *
 * 因为它全部是**可单测的纯逻辑**：喂一段字节、断言屏幕。终端渲染这种"看起来对不对"
 * 的东西，只有能写测试才敢改（见 `TerminalEmulatorTest`）。
 */
internal class TerminalEmulator(
    rows: Int = 24,
    cols: Int = 80,
) {

    data class Snapshot(
        val lines: List<String>,
        val cursorRow: Int,
        val cursorCol: Int,
        /** OSC 设置的窗口标题（`ESC]0;…`），没有就是 null。 */
        val title: String?,
    )

    private var height = rows.coerceAtLeast(1)
    private var width = cols.coerceAtLeast(1)

    private var grid = Array(height) { CharArray(width) { ' ' } }
    private var curRow = 0
    private var curCol = 0

    /** 延迟换行：写满最后一列后先不换行，等下一个可打印字符（真实终端的行为）。 */
    private var pendingWrap = false

    /**
     * `\r` 之后"覆盖前先擦干净"的待决标记。
     *
     * 真实终端里 `\r` 只把光标移到行首、**不擦**（残留归程序自己管，apt 会补 `ESC[K`）。
     * 但我们把屏幕渲染成文本视图，残留对用户就是花屏（`curl`/`dd` 那种 `\r` + 变短文本）。
     * 所以这里做**有意的简化**：`\r` 之后如果来的不是 `\n`（即不是 CRLF），先把该行擦到行尾。
     *
     * 为什么不直接在 `\r` 时擦：`a\r\nb` 这种 CRLF 到处都有（Windows 生成的文件、不少脚本），
     * 一擦就把 "a" 抹了。靠这个"看一眼下一个字符"的标记，两种都保住。
     */
    private var pendingCrErase = false

    private var title: String? = null

    /** 解析状态机 */
    private var state = State.GROUND
    private var csiBuf = StringBuilder()
    private var oscBuf = StringBuilder()
    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteBuffer.allocate(0)

    private enum class State { GROUND, ESC, CSI, OSC, OSC_ESC }

    fun resize(rows: Int, cols: Int) {
        val r = rows.coerceAtLeast(1)
        val c = cols.coerceAtLeast(1)
        if (r == height && c == width) return
        val next = Array(r) { CharArray(c) { ' ' } }
        for (y in 0 until minOf(r, height)) {
            for (x in 0 until minOf(c, width)) next[y][x] = grid[y][x]
        }
        grid = next
        height = r
        width = c
        curRow = curRow.coerceIn(0, height - 1)
        curCol = curCol.coerceIn(0, width - 1)
        pendingWrap = false
    }

    fun reset() {
        grid = Array(height) { CharArray(width) { ' ' } }
        curRow = 0
        curCol = 0
        pendingWrap = false
        pendingCrErase = false
        title = null
        state = State.GROUND
        csiBuf = StringBuilder()
        oscBuf = StringBuilder()
        pending = ByteBuffer.allocate(0)
        decoder.reset()
    }

    /** 屏幕内容（行尾空白去掉；全空行保留为空串）。 */
    fun snapshot(): Snapshot = Snapshot(
        lines = grid.map { row -> String(row).trimEnd() },
        cursorRow = curRow,
        cursorCol = curCol,
        title = title,
    )

    /** 喂字节（增量 UTF-8：多字节字符可以跨调用）。 */
    fun feed(bytes: ByteArray, from: Int = 0, to: Int = bytes.size) {
        val text = decode(bytes, from, to) ?: return
        for (ch in text) consume(ch)
    }

    // ---------------------------------------------------------------- UTF-8

    /** 增量解码：把不完整的多字节序列留到下次（PTY 的一个字符可能分两次到达）。 */
    private fun decode(bytes: ByteArray, from: Int, to: Int): String? {
        val len = to - from
        if (len <= 0) return null
        val carry = pending.remaining()
        val buf = ByteBuffer.allocate(carry + len)
        buf.put(pending)
        buf.put(bytes, from, len)
        buf.flip()
        pending = ByteBuffer.allocate(0)

        val out = CharBuffer.allocate(buf.remaining() + 4)
        val res = decoder.decode(buf, out, false)
        if (res.isOverflow) {
            // 极端长行：扩容重来一次（正常情况下不会发生）
            val bigger = CharBuffer.allocate(out.capacity() * 2)
            decoder.decode(buf, bigger, false)
            bigger.flip()
            val s = bigger.toString()
            return s
        }
        if (buf.hasRemaining()) {
            val rest = ByteBuffer.allocate(buf.remaining())
            rest.put(buf)
            rest.flip()
            pending = rest
        }
        out.flip()
        return out.toString()
    }

    // ---------------------------------------------------------------- 状态机

    private fun consume(ch: Char) {
        when (state) {
            State.GROUND -> ground(ch)
            State.ESC -> esc(ch)
            State.CSI -> csi(ch)
            State.OSC -> osc(ch)
            State.OSC_ESC -> oscEsc(ch)
        }
    }

    private fun ground(ch: Char) {
        if (pendingCrErase) {
            pendingCrErase = false
            // CRLF：不擦（否则 'a\r\nb' 里的 a 会被抹掉）
            if (ch != '\n' && ch != '\r') eraseToEol()
        }
        when (ch) {
            '\u001b' -> state = State.ESC
            '\r' -> { curCol = 0; pendingWrap = false; pendingCrErase = true }
            '\n' -> { lineFeed(); pendingWrap = false }
            '\u000b', '\u000c' -> lineFeed()          // VT / FF 也当换行
            '\b' -> { if (curCol > 0) curCol--; pendingWrap = false }
            '\t' -> { curCol = ((curCol / 8) + 1) * 8; if (curCol >= width) curCol = width - 1; pendingWrap = false }
            '\u0007' -> { /* BEL：忽略（不弹通知） */ }
            '\u000e', '\u000f' -> { /* 字符集切换：忽略 */ }
            else -> if (ch >= ' ') put(ch)
        }
    }

    private fun esc(ch: Char) {
        when (ch) {
            '[' -> { csiBuf = StringBuilder(); state = State.CSI }
            ']' -> { oscBuf = StringBuilder(); state = State.OSC }
            '(', ')', '*', '+' -> state = State.GROUND   // 选字符集：下一个字符会被当正文，可接受
            else -> state = State.GROUND                  // 其他单字符 ESC 序列：忽略
        }
    }

    private fun csi(ch: Char) {
        if (ch in '\u0040'..'\u007e') {
            applyCsi(ch, csiBuf.toString())
            csiBuf = StringBuilder()
            state = State.GROUND
        } else {
            if (csiBuf.length < 64) csiBuf.append(ch)
            if (ch == '\u001b') state = State.ESC        // 容错
        }
    }

    private fun osc(ch: Char) {
        when (ch) {
            '\u0007' -> { finishOsc(); state = State.GROUND }   // BEL 结束
            '\u001b' -> state = State.OSC_ESC                   // 可能是 ST
            else -> if (oscBuf.length < 256) oscBuf.append(ch)
        }
    }

    private fun oscEsc(ch: Char) {
        if (ch == '\\') finishOsc()                            // ESC \ = ST
        state = State.GROUND
    }

    private fun finishOsc() {
        val s = oscBuf.toString()
        val idx = s.indexOf(';')
        val body = if (idx >= 0) s.substring(idx + 1) else s
        if (body.isNotBlank()) title = body.trim()
    }

    private fun applyCsi(final: Char, raw: String) {
        // 参数：数字与 ';'（前缀 '?' '>' '!' 之类的修饰先剥掉）
        val params = raw.trimStart('?', '>', '!', '<', '=')
            .split(';')
            .map { it.takeWhile { c -> c.isDigit() } }
        fun p(i: Int, dflt: Int): Int = params.getOrNull(i)?.toIntOrNull() ?: dflt

        when (final) {
            'A' -> { curRow = (curRow - p(0, 1)).coerceAtLeast(0); pendingWrap = false }
            'B' -> { curRow = (curRow + p(0, 1)).coerceAtMost(height - 1); pendingWrap = false }
            'C' -> { curCol = (curCol + p(0, 1)).coerceAtMost(width - 1); pendingWrap = false }
            'D' -> { curCol = (curCol - p(0, 1)).coerceAtLeast(0); pendingWrap = false }
            'E' -> { curRow = (curRow + p(0, 1)).coerceAtMost(height - 1); curCol = 0; pendingWrap = false }
            'F' -> { curRow = (curRow - p(0, 1)).coerceAtLeast(0); curCol = 0; pendingWrap = false }
            'G' -> { curCol = (p(0, 1) - 1).coerceIn(0, width - 1); pendingWrap = false }
            'H', 'f' -> {
                curRow = (p(0, 1) - 1).coerceIn(0, height - 1)
                curCol = (p(1, 1) - 1).coerceIn(0, width - 1)
                pendingWrap = false
            }
            'K' -> {
                when (p(0, 0)) {
                    0 -> eraseToEol()
                    1 -> for (x in 0..curCol.coerceAtMost(width - 1)) grid[curRow][x] = ' '
                    else -> for (x in 0 until width) grid[curRow][x] = ' '
                }
                pendingWrap = false
            }
            'J' -> {
                when (p(0, 0)) {
                    0 -> {
                        for (x in curCol until width) grid[curRow][x] = ' '
                        for (y in curRow + 1 until height) grid[y].fill(' ')
                    }
                    1 -> {
                        for (y in 0 until curRow) grid[y].fill(' ')
                        for (x in 0..curCol.coerceAtMost(width - 1)) grid[curRow][x] = ' '
                    }
                    else -> grid.forEach { it.fill(' ') }
                }
                pendingWrap = false
            }
            'm', 'h', 'l', 'r', 't', 'n', 's', 'u', 'X', '@', 'P', 'L', 'M' -> {
                // SGR/模式/滚动区等：暂不实现（见类注释的"刻意克制"）
            }
            else -> { /* 未知 CSI：吞掉 */ }
        }
    }

    // ---------------------------------------------------------------- 屏幕操作

    private fun put(ch: Char) {
        if (pendingWrap) {
            curCol = 0
            lineFeed()
            pendingWrap = false
        }
        if (curRow in 0 until height && curCol in 0 until width) {
            grid[curRow][curCol] = ch
        }
        if (curCol >= width - 1) {
            pendingWrap = true
        } else {
            curCol++
        }
    }

    /** 从当前列擦到行尾。 */
    private fun eraseToEol() {
        for (x in curCol until width) grid[curRow][x] = ' '
    }

    private fun lineFeed() {
        if (curRow >= height - 1) {
            // 滚屏：丢掉最上一行
            for (y in 0 until height - 1) grid[y] = grid[y + 1]
            grid[height - 1] = CharArray(width) { ' ' }
        } else {
            curRow++
        }
        curCol = curCol.coerceIn(0, width - 1)
    }
}
