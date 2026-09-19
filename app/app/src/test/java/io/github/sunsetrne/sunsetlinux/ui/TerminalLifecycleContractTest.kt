package io.github.sunsetrne.sunsetlinux.ui

import io.github.sunsetrne.sunsetlinux.TestPaths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 终端会话生命周期的**源码级契约**（与 ShellLayoutContractTest 同一手法：跑不了真 PTY，
 * 能守的就是这些结构事实）。
 *
 * 三条断言全部来自 2026-09-19 的真机核验，不是凭空立的规矩：
 *
 * | 断言 | 对应现象 |
 * |---|---|
 * | `adoptFd` 的产物必须被字段**强引用** | 终端"随机掉线"：那个 `ParcelFileDescriptor` 没有强引用 ⇒ 被 GC 回收时 finalizer `close()` 掉 master fd ⇒ 子进程读到 EOF 后**正常退出**（退出码 0、logcat 无任何异常，极难归因） |
 * | `closeQuietly` 要关 PFD 本体 | 每次重连泄漏一个 master fd |
 * | `starting` 复位必须覆盖异常路径 | 连接途中抛异常 ⇒ 界面永久卡在「连接中…」，而 `connect()` 开头是 `if (running \|\| starting) return` ⇒「连接」按钮彻底失灵，只能切 tab 重建 |
 */
class TerminalLifecycleContractTest {

    private val srcDir = File(
        TestPaths.repoRoot,
        "app/app/src/main/java/io/github/sunsetrne/sunsetlinux",
    )
    private val pty = File(srcDir, "core/PtySession.kt")
    private val pane = File(srcDir, "ui/TerminalPane.kt")

    private fun read(f: File): String {
        assertTrue("找不到 ${f.path}（文件被改名或移动了？）", f.isFile)
        return f.readText()
    }

    @Test
    fun `PTY master 的 PFD 必须被强引用（否则 GC 关掉它，会话随机掉线）`() {
        val text = read(pty)
        assertTrue(
            "PtySession 必须用 `private var pfd: android.os.ParcelFileDescriptor?` 持有 master —— " +
                "只存 .fileDescriptor 会让 PFD 在 GC 时被回收并 close 掉 master（会话随机退出，退出码 0）",
            Regex("""private var pfd:\s*android\.os\.ParcelFileDescriptor\?""").containsMatchIn(text),
        )
        assertFalse(
            "不要写 `adoptFd(...).fileDescriptor` —— 那样 PFD 没有任何强引用",
            Regex("""adoptFd\([^)]*\)\.fileDescriptor""").containsMatchIn(text),
        )
        assertTrue(
            "adoptFd 的产物必须真的落到字段 pfd 上（先存局部变量、再整体赋值）",
            Regex("""val pfdObj = android\.os\.ParcelFileDescriptor\.adoptFd\(""").containsMatchIn(text) &&
                Regex("""(?m)^\s*pfd = pfdObj\s*$""").containsMatchIn(text),
        )
        assertTrue(
            "closeQuietly 里必须 close 掉 PFD 本体（只清引用会泄漏 fd）",
            Regex("""pfd\?\.close\(\)""").containsMatchIn(text),
        )
    }

    @Test
    fun `连接按钮的 starting 必须在任何失败路径上复位（否则永久卡在连接中）`() {
        val connect = read(pane).substringAfter("fun connect(mode: EnvMode)")
        assertTrue(
            "connect() 里要用 try 包住 withContext，异常也要收尾",
            connect.contains("val err = try {"),
        )
        val catchIdx = connect.indexOf("} catch (t: Throwable) {")
        assertTrue(
            "connect() 缺少 Throwable 兜底 —— 抛异常时界面会卡在「连接中…」且按钮永久变灰",
            catchIdx > 0,
        )
        val resetIdx = connect.indexOf("starting = false", catchIdx)
        assertTrue("catch 之后必须复位 starting（放在 withContext 之后的旧写法会漏掉异常路径）", resetIdx > catchIdx)
        // 复位只能在**所有 catch 之后**：try 块里若自己复位，异常路径仍然漏（回到旧 bug）
        val tryIdx = connect.indexOf("val err = try {")
        val firstCatch = connect.indexOf("} catch (", tryIdx)
        assertTrue("connect() 里应当先有 try、后有 catch", tryIdx in 0 until firstCatch)
        assertFalse(
            "try 块里就复位了 starting —— 那只覆盖成功路径，抛异常时界面照样卡在「连接中…」",
            connect.substring(tryIdx, firstCatch).contains("starting = false"),
        )
    }

    @Test
    fun `终端状态条显示实时行列（行列算错时一眼可见）`() {
        val text = read(pane)
        assertTrue(
            "rows/cols 必须是 Compose 状态，才能显示到状态条上",
            text.contains("var rows by mutableStateOf(") && text.contains("var cols by mutableStateOf("),
        )
        assertTrue(
            "状态条要显示「列×行」（真机上就是靠它判定「输出区只显示几行」是不是行列算错了）",
            text.contains("\"\${state.cols}×\${state.rows}\""),
        )
    }
}
