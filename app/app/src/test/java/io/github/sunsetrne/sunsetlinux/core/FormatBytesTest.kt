package io.github.sunsetrne.sunsetlinux.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 体积显示：钉住**真机上的三个层大小**对应的文案。
 *
 * 为什么值得单独锁一遍：真机状态页截图里出现过"dsh 1894 MB"的读数（而 `etc/state.json`
 * 里是 `198651904` 字节 = 189.4 MB），当时无法复现 —— 最可能是把小数点看丢了。
 * 这里用设备上的真实数字做判据：谁把 1024 进制改成 1000、或者多乘/少除一个 1024，
 * 这三个断言立刻红。
 */
class FormatBytesTest {

    @Test
    fun `真机三层的大小要显示成 229_2 _ 596_3 _ 189_4 MB`() {
        // 数值来自真机 /data/sunsetlinux/etc/state.json（2026-09-15）
        assertEquals("229.2 MB", formatBytes(240_373_760L))
        assertEquals("596.3 MB", formatBytes(625_295_360L))
        assertEquals("189.4 MB", formatBytes(198_651_904L))
    }

    @Test
    fun `边界：空值、负数、正好 1024 的幂`() {
        assertEquals("—", formatBytes(null))
        assertEquals("—", formatBytes(-1L))
        assertEquals("0 B", formatBytes(0L))
        assertEquals("1023 B", formatBytes(1023L))
        assertEquals("1.0 KB", formatBytes(1024L))
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
        assertEquals("1.0 GB", formatBytes(1024L * 1024 * 1024))
    }
}
