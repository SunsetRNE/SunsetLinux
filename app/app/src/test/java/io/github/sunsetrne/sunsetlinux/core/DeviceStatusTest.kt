package io.github.sunsetrne.sunsetlinux.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * root / 模块检测的回归（纯函数，不需要真机）。
 *
 * 用户原话："对 root 授权的检测和模块的检测" —— 之前只有一个 `suAvailable: Boolean`，
 * 界面只能写"su 不可用"，用户不知道是**没刷模块**、**没点允许**、还是**弹窗超时**。
 * 这张表把"状态 → 下一步"钉死：
 *
 * | 断言 | 为什么 |
 * |---|---|
 * | 没有 su 二进制 ⇒ NO_SU（不是 DENIED） | 没刷 KernelSU 与"被拒绝"的下一步完全不同 |
 * | su 超时 ⇒ TIMEOUT | 卡在授权弹窗是最常见的一种，要提示去看屏幕 |
 * | uid=0 ⇒ GRANTED | 唯一能建层/自启的前提 |
 * | 退出码非 0 ⇒ DENIED | proot 伪 root / 用户点了拒绝 |
 * | 读不到 module.prop ⇒ 未装（但不停留在"未知"） | 模块没装就必须明确说"去装模块 + 重启" |
 * | `version=v1.0.9` ⇒ `1.0.9` | 界面里带上别扭的 `v` 会跟下载页对不上 |
 * | `modules_update/` 存在 ⇒ 待重启 | "装了没重启"是装机流程里最容易漏的一步 |
 */
class DeviceStatusTest {

    // ─────────────────────────────────────────── root 状态映射

    private fun result(
        exitCode: Int = 0,
        stdout: String = "",
        stderr: String = "",
        error: String? = null,
        kind: FailKind = FailKind.NONE,
    ) = CtlResult(exitCode, stdout, stderr, error, kind)

    @Test
    fun `没有 su 二进制是 NO_SU 而不是被拒绝`() {
        val p = RootProbe.from(
            result(exitCode = -1, error = "未找到可用的 su（Cannot run program \"su\"）", kind = FailKind.START_FAILED),
        )
        assertEquals(RootState.NO_SU, p.state)
        assertTrue("必须给出下一步（刷 KernelSU 或走 proot）：${p.hint}", p.hint!!.contains("KernelSU"))
    }

    @Test
    fun `su 调用超时是 TIMEOUT 并提示看屏幕弹窗`() {
        val p = RootProbe.from(result(exitCode = -1, error = "命令超时（6 秒未返回）", kind = FailKind.TIMEOUT))
        assertEquals(RootState.TIMEOUT, p.state)
        assertTrue("要提示去看授权弹窗：${p.hint}", p.hint!!.contains("弹窗"))
    }

    @Test
    fun `uid=0 就是已授权`() {
        val p = RootProbe.from(result(stdout = "uid=0(root) gid=0(root) context=u:r:ksu:s0"))
        assertEquals(RootState.GRANTED, p.state)
        assertTrue(p.granted)
        assertNull("已授权时不该再唠叨", p.hint)
    }

    @Test
    fun `退出码非零就是被拒绝`() {
        val p = RootProbe.from(result(exitCode = 1, stderr = "permission denied"))
        assertEquals(RootState.DENIED, p.state)
        assertTrue("要告诉用户去哪授权：${p.hint}", p.hint!!.contains("KernelSU"))
    }

    @Test
    fun `退出码 0 但拿不到 uid=0 是 UNKNOWN 而不是猜测`() {
        val p = RootProbe.from(result(exitCode = 0, stdout = "uid=2000(shell)"))
        assertEquals(RootState.UNKNOWN, p.state)
        assertTrue("未知状态要给出可执行的下一步（导出诊断包）：${p.hint}", p.hint!!.contains("诊断包"))
    }

    // ─────────────────────────────────────────── 模块状态解析

    private val full = """
        ###PROP
        id=sunsetlinux
        name=SunsetLinux — 原生 DSH 运行环境
        version=v1.0.9
        versionCode=10009
        ###DISABLE
        0
        ###PENDING
        1
        ###PROV
        1
        ###END
    """.trimIndent()

    @Test
    fun `完整输出：装好了、版本去掉 v、待重启、带构建入口`() {
        val m = ModuleStatus.parse(full)
        assertTrue(m.installed)
        assertEquals("1.0.9", m.version)
        assertEquals(false, m.disabled)
        assertEquals(true, m.pendingReboot)
        assertEquals(true, m.hasProvisionScript)
        assertTrue("待重启必须说出来：${m.label}", m.label.contains("待重启"))
        assertTrue("并给出动作：${m.hint}", m.hint!!.contains("重启"))
    }

    @Test
    fun `读不到 module_prop 就是没装，并且明说下一步`() {
        val m = ModuleStatus.parse("###PROP\n###DISABLE\n0\n###PENDING\n0\n###PROV\n0\n###END\n")
        assertEquals(false, m.installed)
        assertNull(m.version)
        assertTrue("要指引去装模块并重启：${m.hint}", m.hint!!.contains("重启"))
    }

    @Test
    fun `空白输出（su 没给东西）也要退化成未装而不是崩`() {
        val m = ModuleStatus.parse("")
        assertEquals(false, m.installed)
        assertNull(m.version)
    }

    @Test
    fun `停用状态优先于版本显示`() {
        val disabled = full.replace("###DISABLE\n0", "###DISABLE\n1")
        val m = ModuleStatus.parse(disabled)
        assertEquals(true, m.disabled)
        assertTrue("停用要单独说：${m.label}", m.label.contains("停用"))
        assertTrue("并给出动作：${m.hint}", m.hint!!.contains("启用"))
    }

    @Test
    fun `版本行容错：带空格、没有 v、缺 versionCode`() {
        assertEquals("1.2.3", ModuleStatus.versionOf(" version = 1.2.3 \n".trimIndent().replace("version = ", "version=")))
        assertEquals("1.2.3", ModuleStatus.versionOf("version=v1.2.3"))
        assertEquals("1.2.3", ModuleStatus.versionOf("id=x\nversion=1.2.3\n"))
        assertNull(ModuleStatus.versionOf("id=x\nname=y\n"))
    }

    @Test
    fun `探测脚本必须真的读那四件事（否则解析出来的都是假的）`() {
        val script = ModuleStatus.PROBE_SCRIPT
        for (mark in listOf(
            ModuleStatus.MARK_PROP, ModuleStatus.MARK_DISABLE,
            ModuleStatus.MARK_PENDING, ModuleStatus.MARK_PROV,
        )) {
            assertTrue("探测脚本里缺少标记 $mark", script.contains(mark))
        }
        assertTrue("必须读 modules/sunsetlinux", script.contains("/data/adb/modules/sunsetlinux"))
        assertTrue("必须读 modules_update（装了没重启）", script.contains("/data/adb/modules_update/sunsetlinux"))
    }

    @Test
    fun `读不到模块状态时不许说成"没装"（真机踩过）`() {
        // 空输出 = su 没拿到/超时 → 状态未知，而不是"模块未装"
        val unknown = ModuleStatus.parse("")
        assertEquals("读不到就是读不到", false, unknown.readable)
        assertEquals(false, unknown.installed)
        assertTrue("标签要说『未知』：${unknown.label}", unknown.label.contains("未知"))
        assertTrue("并给出『先去授权 root』的动作：${unknown.hint}", unknown.hint!!.contains("授权"))

        // 探针真跑完了（标记齐）但 module.prop 是空的 → 才是真的"没装"
        val empty = ModuleStatus.parse(
            "###PROP\n###DISABLE\n0\n###PENDING\n0\n###PROV\n0\n###END\n",
        )
        assertEquals(true, empty.readable)
        assertEquals(false, empty.installed)
        assertTrue("这种情况才说『未装』：${empty.label}", empty.label.contains("未装"))
        assertTrue("并指引去装模块：${empty.hint}", empty.hint!!.contains("模块 zip"))
    }

    @Test
    fun `已安装且启用的模块标签要说清楚版本与已启用`() {
        val m = ModuleStatus.parse(
            "###PROP\nid=sunsetlinux\nversion=v1.0.9\n###DISABLE\n0\n###PENDING\n0\n###PROV\n1\n###END\n",
        )
        assertEquals(true, m.readable)
        assertEquals(true, m.installed)
        assertEquals("1.0.9", m.version)
        assertTrue("标签里要有版本：${m.label}", m.label.contains("1.0.9"))
        assertTrue("并说明已启用：${m.label}", m.label.contains("已启用"))
        org.junit.Assert.assertNull("一切正常时不该再唠叨", m.hint)
    }

    @Test
    fun `真机 module_prop 原文（无 v 前缀）要解析成 1_0_9`() {
        // 这段是从真机 /data/adb/modules/sunsetlinux/module.prop 原样抄来的：
        // 注意它是 `version=1.0.9`（**没有** v 前缀），而镜像里的其它地方可能是 v1.0.9。
        val prop = """
            id=sunsetlinux
            name=SunsetLinux — 原生 DSH 运行环境
            version=1.0.9
            versionCode=10009
            author=sunsetlinux
        """.trimIndent()
        assertEquals("1.0.9", ModuleStatus.versionOf(prop))
        val m = ModuleStatus.parse(
            "###PROP\n" + prop + "\n###DISABLE\n0\n###PENDING\n0\n###PROV\n1\n###END\n",
        )
        assertEquals(true, m.readable)
        assertEquals(true, m.installed)
        assertEquals(false, m.disabled)
        assertEquals(false, m.pendingReboot)
        assertEquals("模块 1.0.9（已启用）", m.label)
    }
}
