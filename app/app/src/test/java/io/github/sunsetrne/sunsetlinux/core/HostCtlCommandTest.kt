package io.github.sunsetrne.sunsetlinux.core

import io.github.sunsetrne.sunsetlinux.TestPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「私有目录里的脚本一律经 `sh` 跑」的契约（App 侧 + 源码级）。
 *
 * ## 为什么值得单测（真机事故 · 2026-09-19）
 *
 * 免 root 版在真机引导页点「安装内嵌离线包」，倒在最后一步：
 *
 * ```
 * ✗ [linuxctl update] base 失败: 无法执行
 *   /data/user/0/io.github.sunsetrne.sunsetlinux.proot/files/sunsetlinux/bin/linuxctl:
 *   Cannot run program "…": error=13, Permission denied
 * ```
 *
 * `bin/linuxctl` 是 [ProotRuntime] 从 assets 铺下去的宿主脚本。App 以前按
 * `File.canExecute()` 决定"直接 exec 还是退化成 `sh <path>`"——执行位**有**，
 * 于是走了直接 exec，而真机上 exec 一个私有目录里的脚本会被挡
 * （`/data` 的 noexec 挂载 / 系统策略，只差一个 ROM 就复现）。
 *
 * 修法不是"再猜一次执行位"，而是把契约收紧：**私有目录里的脚本永远由 `sh` 跑**。
 * 下面两条分别钉住"怎么构造命令"（纯函数）与"没人再写回那个赌执行位的分支"（源码扫描）。
 */
class HostCtlCommandTest {

    private val script = "/data/user/0/io.github.sunsetrne.sunsetlinux.proot/files/sunsetlinux/bin/linuxctl"

    @Test
    fun `可执行的文件也一律经 sh —— 不赌执行位`() {
        assertEquals(
            "构造只由路径决定：执行位在不在都走 /system/bin/sh",
            listOf(DshPaths.HOST_SH, script),
            DshPaths.hostCtlCommand(script),
        )
    }

    @Test
    fun `解释器固定是 system bin sh —— Android 上唯一保证存在的那个`() {
        assertEquals("/system/bin/sh", DshPaths.HOST_SH)
        assertEquals(2, DshPaths.hostCtlCommand(script).size)
    }

    @Test
    fun `不存在的路径也走 sh —— ENOENT 的原文比 EACCES 好懂`() {
        val missing = "/data/user/0/io.github.sunsetrne.sunsetlinux.proot/files/sunsetlinux/bin/nope"
        assertEquals(listOf(DshPaths.HOST_SH, missing), DshPaths.hostCtlCommand(missing))
    }

    @Test
    fun `命令行形式把路径单独引起来，带空格的路径不会被拆成两个参数`() {
        val weird = "/data/user/0/com.example/files/my env/bin/linuxctl"
        assertEquals(
            "'${DshPaths.HOST_SH}' '${weird}'",
            DshPaths.hostCtlCommandLine(weird),
        )
    }

    @Test
    fun `命令行形式可直接拼子命令（ptySpec 就是这么用的）`() {
        assertEquals(
            "'${DshPaths.HOST_SH}' '${script}' attach",
            DshPaths.hostCtlCommandLine(script) + " attach",
        )
    }

    /**
     * 源码级：`prootCommand` / `ptySpec` 必须走 [DshPaths.hostCtlCommand]，
     * 且 **不许** 再出现 `canExecute()` 这种"执行位判据"。
     *
     * 为什么用源码扫描而不是反射：真正会重犯的错误是"有人又写了一遍那个分支"，
     * 而它在 JVM 单测里永远跑不到（这里没有 Android、没有 noexec，直接 exec 一律成功）——
     * 只有把结构本身钉住，才能在 CI 上红。
     *
     * ⚠️ 必须**先去掉注释**再断：这次的修法本身就写了注释解释"为什么不再判执行位"，
     *    直接 `contains` 会把解释也算成违例（写这个测试时真踩了）。
     */
    @Test
    fun `App 侧不再用执行位决定直接 exec 还是走 sh`() {
        val root = File(TestPaths.repoRoot, "app/app/src/main/java/io/github/sunsetrne/sunsetlinux")

        val linuxCtl = codeOnly(File(root, "core/LinuxCtl.kt").readText())
        assertFalse(
            "LinuxCtl 里又出现了 canExecute()：免 root 模式的命令构造必须经 DshPaths.hostCtlCommand",
            linuxCtl.contains("canExecute()"),
        )
        assertTrue(
            "prootCommand 必须经 DshPaths.hostCtlCommand 构造",
            linuxCtl.contains("DshPaths.hostCtlCommand(activeCtlPath)"),
        )
        assertTrue(
            "ptySpec 必须经 DshPaths.hostCtlCommandLine 构造",
            linuxCtl.contains("DshPaths.hostCtlCommandLine(activeCtlPath)"),
        )

        // 就绪判据同理：拿执行位当"铺好了没"在 noexec 机器上会把铺好的判成没铺。
        // 判据钉的是**那个惯用法本身**（`isFile && canExecute()` 作为"可用"的判据），
        // 不是"全文件禁用 canExecute()"—— 这个 API 本身没错（`normalizeExecBits`
        // 就用它决定"要不要补执行位"），错的是拿它当"环境/命令能不能用"的结论。
        // 第一版写成全文件 contains 就把正确用法也判红了，这条注释是那次失败留下的。
        val readiness = Regex("""isFile\s*&&\s*[^\n]{0,40}canExecute\(\)""")
        for (rel in listOf("core/ProotRuntime.kt", "core/ProotSetup.kt")) {
            assertFalse(
                "$rel 里又用「isFile && canExecute()」判就绪：改成只判文件在不在",
                readiness.containsMatchIn(File(root, rel).readText()),
            )
        }
    }

    /** 去掉整行注释与块注释行后的源码（见上一条测试里的"⚠️"）。 */
    private fun codeOnly(text: String): String = text.lineSequence()
        .filterNot {
            val t = it.trim()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

    /**
     * 脚本自己的**身份**没变：**宿主侧**的那两个入口（`linuxctl.sh`、`start.sh`）
     * 第一行仍是 `#!/system/bin/sh`。
     *
     * 为什么这条也要守：App 现在**总是**用 `/system/bin/sh` 去跑它们 —— 万一哪天脚本
     * 换成 `#!/usr/bin/env bash` 之类，这条"经 sh"的调用就会静默地跑错解释器。
     *
     * ⚠️ 这里**不能**断言"目录下所有 .sh"：`entry.sh` 的 shebang 是 `#!/bin/bash`，
     *    它是**环境内**的 supervisor（由 proot 在 guest 里 `... /opt/sunsetlinux/entry.sh` 起，
     *    那边有 bash），根本不走"宿主侧 `sh <path>`"这条路 —— 第一版断言写成"全部 .sh"
     *    就被它判红，这条注释是那次失败留下的。
     */
    @Test
    fun `宿主侧入口脚本 shebang 仍是 system bin sh`() {
        val runtimeProot = File(TestPaths.repoRoot, "runtime/proot")
        for (name in listOf("linuxctl.sh", "start.sh")) {
            val src = File(runtimeProot, name)
            assertTrue("缺宿主侧入口脚本：${src.path}", src.isFile)
            val first = src.readLines().firstOrNull { it.isNotBlank() }.orEmpty()
            assertEquals(
                "$name 的首行应当是 #!/system/bin/sh（App 侧按这个解释器调用它）",
                "#!/system/bin/sh",
                first.trim(),
            )
        }
    }

    /**
     * `ProotRuntime.normalizeExecBits`：**Android 的 tar 不还原模式**这个坑的回归。
     *
     * 复现的样子就是真机上的样子：`bin/proot` 与 `lib/ld-linux-*.so.1` 解出来都没有执行位
     * （toybox tar 给 0600/0700），于是 `proot-launch.sh` 的 `exec "$loader" …` 死在
     * `exec: …/ld-linux-aarch64.so.1: Permission denied`。断言：
     *   · `bin/`、`lib/` 下的文件被补上执行位（**loader 也算** —— 启动器自己只 chmod 了
     *     `bin/proot`，而 loader 才是那次 exec 的主体）；
     *   · 数据文件（README / license / manifest）**不动**（不该无脑 chmod 整棵树）；
     *   · 目录可进入；
     *   · 打第二遍是空操作（幂等）。
     */
    @Test
    fun `解包后的 proot 部件会被补上执行位（loader 与 proot 本体都要）`() {
        val root = java.nio.file.Files.createTempDirectory("proot-normalize").toFile()
        try {
            val files = listOf(
                "bin/proot",
                "lib/ld-linux-aarch64.so.1",
                "lib/libc.so.6",
                "proot-launch.sh",
                "README",
                "license/GPL-2.0.txt",
                "manifest.txt",
            )
            for (rel in files) {
                val f = File(root, rel)
                f.parentFile?.mkdirs()
                f.writeText("x")
                f.setExecutable(false, false)
            }

            val fixed = ProotRuntime.normalizeExecBits(root)

            assertTrue("bin/proot 必须被补执行位：$fixed", File(root, "bin/proot").canExecute())
            assertTrue(
                "lib/ 下的 loader 也必须被补（启动器只 chmod 了 bin/proot，漏了它）：$fixed",
                File(root, "lib/ld-linux-aarch64.so.1").canExecute(),
            )
            assertTrue("lib/ 下的 so 也要", File(root, "lib/libc.so.6").canExecute())
            assertTrue(
                "根目录的 *.sh 启动器（proot-launch.sh）也要 —— doctor 实测它在真机上没有执行位，" +
                    "导致 proot_binary 检查失败：$fixed",
                File(root, "proot-launch.sh").canExecute(),
            )
            assertFalse("README 是数据文件，不该被 chmod", File(root, "README").canExecute())
            assertFalse("license 不该被 chmod", File(root, "license/GPL-2.0.txt").canExecute())
            assertTrue("目录必须可进入", File(root, "lib").canExecute())

            assertTrue("第二遍应当是空操作（幂等）", ProotRuntime.normalizeExecBits(root).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
