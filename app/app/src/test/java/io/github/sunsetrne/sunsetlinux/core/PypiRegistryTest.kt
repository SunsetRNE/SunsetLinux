package io.github.sunsetrne.sunsetlinux.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Python 源（pip）的**纯逻辑**测试：URL 校验、trusted-host 提取、pip.conf 内容。
 *
 * 这里不碰设备、不碰文件系统 —— 落盘路径（两份 conf）与"环境运行中才能写"的判定
 * 分别在 `PypiRegistry.apply/verify`（需要环境）与 UI 里，本文件只守可离线验证的部分。
 */
class PypiRegistryTest {

    // ─────────────────────────────── URL 校验

    @Test
    fun `合法 https 源通过校验`() {
        assertNull(PypiRegistry.validateUrl("https://pypi.org/simple"))
        assertNull(PypiRegistry.validateUrl("https://mirrors.aliyun.com/pypi/simple/"))
        assertNull(PypiRegistry.validateUrl("  https://mirror.local:8080/pypi/simple  "))
        PypiRegistry.presets.forEach {
            assertNull("预设 ${it.id} 的地址自己必须过校验", PypiRegistry.validateUrl(it.url))
        }
    }

    @Test
    fun `http 源被明确拒绝并给出理由`() {
        val err = PypiRegistry.validateUrl("http://mirrors.aliyun.com/pypi/simple/")
        assertNotNull("明文 http 源必须拦下（pip 下载的包会被直接安装执行）", err)
        assertTrue("拒绝理由要说清是 https 的问题：$err", err!!.contains("https"))
    }

    @Test
    fun `空值 非 URL 无主机名都要拦下`() {
        assertNotNull(PypiRegistry.validateUrl(""))
        assertNotNull(PypiRegistry.validateUrl("   "))
        assertNotNull("带空格应被拒", PypiRegistry.validateUrl("https://a b.com"))
        assertNotNull("缺 scheme 应被拒", PypiRegistry.validateUrl("pypi.org/simple"))
        assertNotNull("别的 scheme 应被拒", PypiRegistry.validateUrl("ftp://pypi.org/simple"))
        assertNotNull("没有主机名应被拒", PypiRegistry.validateUrl("https:///simple"))
    }

    // ─────────────────────────────── trusted-host

    @Test
    fun `trusted-host 只取主机名`() {
        assertEquals("pypi.org", PypiRegistry.trustedHostOf("https://pypi.org/simple"))
        assertEquals("mirrors.aliyun.com", PypiRegistry.trustedHostOf("https://mirrors.aliyun.com/pypi/simple/"))
        // 不带端口：pip 的 add_trusted_host 在没给端口时会挂通配端口，host 级已覆盖 :8080
        assertEquals("mirror.local", PypiRegistry.trustedHostOf("https://mirror.local:8080/pypi/simple"))
        assertEquals("mirror.local", PypiRegistry.trustedHostOf("https://user:pw@mirror.local/simple"))
        // IPv6 字面量保留方括号：去掉会让 pip 把冒号当端口分隔符
        assertEquals("[::1]", PypiRegistry.trustedHostOf("https://[::1]:8080/simple"))
    }

    // ─────────────────────────────── pip.conf 内容

    @Test
    fun `pip conf 内容形如 global 段的两行配置`() {
        val content = PypiRegistry.pipConfContent("https://mirrors.aliyun.com/pypi/simple/")
        val lines = content.lines()
        assertTrue("要有 [global] 段", lines.contains("[global]"))
        assertTrue(
            "index-url 原样保留（含尾部斜杠）：pip 的 Simple API 约定以 / 结尾",
            lines.contains("index-url = https://mirrors.aliyun.com/pypi/simple/"),
        )
        assertTrue(lines.contains("trusted-host = mirrors.aliyun.com"))
        // 注释行 + 段头 + 两行配置 = 4 行（末尾换行会让 lines() 多一个空串）
        assertEquals(4, lines.count { it.isNotBlank() })
    }

    @Test
    fun `官方源的 pip conf 内容`() {
        val content = PypiRegistry.pipConfContent(PypiRegistry.official.url)
        assertTrue(content.contains("index-url = https://pypi.org/simple"))
        assertTrue(content.contains("trusted-host = pypi.org"))
    }

    @Test
    fun `pip conf 的注释指向新的独立页`() {
        // 文案要跟着界面走：源设置已经从「设置」页搬到侧边栏「源与镜像」，
        // conf 里的指路如果还写"设置 → npm 源"，用户按它找不到地方
        val content = PypiRegistry.pipConfContent(PypiRegistry.official.url)
        assertTrue(content.contains("源与镜像"))
    }

    @Test
    fun `两份 conf 的落盘路径是写死的契约值`() {
        assertEquals("/root/.config/pip/pip.conf", PypiRegistry.USER_PIP_CONF)
        assertEquals("etc/pip.conf", PypiRegistry.ENV_PIP_CONF_RELATIVE)
    }
}
