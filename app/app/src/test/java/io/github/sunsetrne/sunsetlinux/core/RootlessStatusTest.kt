package io.github.sunsetrne.sunsetlinux.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `linuxctl status` 里新增的 `rootless:{kind,version}` 解析。
 *
 * 为什么要单独钉住：`mode` 对 App 是**冻结契约**（永远 `proot`），而"实际用的是
 * proroot 还是降级的 proot"只在这个新字段里 —— App 的「设置」页与「关于」页都要显示它。
 * 解析错了用户就会看到"明明是 proroot 却写着 proot"，或者干脆不显示。
 * 另外：**没启动过时必须是 null**（那时脚本还没写 run/rootless），不能编造。
 */
class RootlessStatusTest {

    private val base = """
        {"schema":1,"mode":"proot","state":"running","pid":123,"uptime_sec":5,
         "dsh":{"url":"http://127.0.0.1:3080/?token=x","base_url":"http://127.0.0.1:3080","port":3080,"version":"0.1.5-rc.2","healthy":true},
         "layers":{"base":{"version":"24.04.3-l1","size":240373760,"mounted":true},
                   "runtime":{"version":"1.0.0","size":625295360,"mounted":true},
                   "dsh":{"version":"0.1.5-rc.2","size":198651904,"mounted":true}},
         "storage":{"upper_used":1,"upper_total":2},"last_error":null%s}
    """.trimIndent()

    @Test
    fun `proroot 在用时 mode 仍是 proot，但 rootless 如实报 proroot`() {
        val s = DshStatus.parse(base.replace("%s", ""","rootless":{"kind":"proroot","version":"1.2.8"}"""))
        assertEquals("模式契约不变：mode 仍是 proot", "proot", s.mode)
        assertEquals("proroot", s.rootlessKind)
        assertEquals("1.2.8", s.rootlessVersion)
    }

    @Test
    fun `降级到 proot 时如实报 proot`() {
        val s = DshStatus.parse(base.replace("%s", ""","rootless":{"kind":"proot","version":null}"""))
        assertEquals("proot", s.rootlessKind)
        assertNull(s.rootlessVersion)
    }

    @Test
    fun `老脚本没有这个键时是 null（不编造，也不崩）`() {
        val s = DshStatus.parse(base.replace("%s", ""))
        assertEquals(EnvState.RUNNING, s.state)
        assertNull(s.rootlessKind)
        assertNull(s.rootlessVersion)
    }
}
