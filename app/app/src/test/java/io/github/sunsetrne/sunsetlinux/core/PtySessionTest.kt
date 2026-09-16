package io.github.sunsetrne.sunsetlinux.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 原生 PTY 的**可用性判定**与降级契约。
 *
 * 为什么值得单测：终端是这个 App 里最"看起来能用就行"的部件，但**缺 .so 的 APK**
 * （自编译漏带、ABI 不匹配）如果在这一步抛异常，终端会直接崩或者整个面板空白 ——
 * 用户只会说"终端坏了"。这里的契约是：
 *   · 加载失败 → [PtyNative.available] == false + 一句人话原因；
 *   · 调用方（TerminalPane）据此退回行缓冲实现，终端仍然可用（能力受限并**明说**）。
 *
 * 在 JVM 单测里永远加载不到 Android 的 .so，所以这里跑到的正是**降级分支**本身。
 */
class PtySessionTest {

    @Test
    fun `JVM 里加载不到原生库时必须优雅降级（不是抛异常）`() {
        assertFalse("JVM 上没有 libsunsetlinux_pty.so，必须判为不可用", PtyNative.available)
        assertNotNull("要给出可展示的原因，而不是让用户猜", PtyNative.loadError)
        assertTrue(
            "原因里要点出库名，便于排障：${PtyNative.loadError}",
            PtyNative.loadError!!.contains("sunsetlinux_pty"),
        )
    }

    @Test
    fun `PtySession 在原生库不可用时返回原因而不是崩`() {
        val session = PtySession()
        val err = session.start(
            PtySpec(cmd = "/system/bin/sh", args = listOf("-c", "true"), env = emptyMap(), cwd = null),
            rows = 24,
            cols = 80,
        )
        assertNotNull("start 要返回可展示的错误串", err)
        assertTrue("错误里要说明是原生 PTY 不可用：$err", err!!.contains("原生 PTY"))
        assertFalse("没起来就不该显示为运行中", session.isRunning)
    }

    @Test
    fun `PtySpec 的展示串会为含空格的参数加引号（排障日志用）`() {
        val spec = PtySpec(
            cmd = "/system/bin/sh",
            args = listOf("-c", "exec su -c 'PATH=/system/bin; linuxctl attach'"),
            env = emptyMap(),
            cwd = null,
        )
        val shown = spec.display
        assertTrue("含空格的参数要加引号，否则日志会误导：$shown", shown.contains("'exec su -c "))
        assertTrue(shown.startsWith("/system/bin/sh"))
    }
}
