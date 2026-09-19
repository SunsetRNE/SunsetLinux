package io.github.sunsetrne.sunsetlinux.ui

import io.github.sunsetrne.sunsetlinux.TestPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「免 root 与 KernelSU 模块彻底切割」+「进入即启用」的**源码级契约**。
 *
 * 跑不了真机（这里没有 su、没有 proot、没有 Compose 渲染），能守的就是这些结构事实。
 * 与 `ShellLayoutContractTest` / `UiInsetsContractTest` 同一手法：每条断言都对应一个
 * **会被用户看见**的缺陷，而不是凭空立的规矩。
 *
 * | 断言 | 对应现象 / 为什么值得守 |
 * |---|---|
 * | `ModuleUpdateCard` 只在 `Edition.showsModuleUi` 下渲染 | 免 root 版里出现模块更新卡片 = 决策 1 没做到 |
 * | 模块状态探测在免 root 版**不发起** | 不只是隐藏按钮：后台还在跑 su 读 /data/adb，会弹授权框 |
 * | `BundledModule.info` 在免 root 版不读 | 读的是 root 版才内嵌的 `assets/module/`，白读还可能冒出 UI |
 * | root 版不渲染免 root 运行时 / 层模式 | 决策 1 的反方向："root 运行时不再保留任何 proot 降级分支" |
 * | `SUNSETLINUX_ROOTLESS` 只在 proot 模式递 | 同上：环境变量递过去没人读，但会让人以为还能切运行时 |
 * | 自动启用的三段流水线只有一份实现 | 手动入口与自动入口漂移过一次就会"手动能成、自动不成" |
 */
class EditionSeparationTest {

    private val srcDir = File(
        TestPaths.repoRoot,
        "app/app/src/main/java/io/github/sunsetrne/sunsetlinux",
    )

    private fun read(rel: String): String {
        val f = File(srcDir, rel)
        assertTrue("找不到 ${f.path}（文件被改名或移动了？）", f.isFile)
        return f.readText()
    }

    /** 取两个标记之间的源码片段（用来判断"某段代码是否在某个分支里面"）。 */
    private fun between(text: String, from: String, to: String): String {
        val a = text.indexOf(from)
        assertTrue("源码里找不到标记「$from」—— 结构变了？", a >= 0)
        val b = text.indexOf(to, a)
        assertTrue("源码里找不到标记「$to」—— 结构变了？", b > a)
        return text.substring(a, b)
    }

    /**
     * 去掉注释行后的源码。
     *
     * 断"某个文案不许再出现"时**必须**用它：这次改动本身就写了注释解释"原来那句
     * 「免 root 版与 KernelSU 模块无关」为什么删掉"，直接 contains 会把解释性注释
     * 也算成违例（写这个测试时真踩了，红了两条）。
     */
    private fun codeOnly(text: String): String = text.lineSequence()
        .filterNot {
            val t = it.trim()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

    // ───────────────────────── 任务 A：免 root 版不留模块痕迹

    @Test
    fun `关于页的模块更新卡片只在 Root 版渲染`() {
        val shell = read("ui/AppShell.kt")
        assertTrue("AppShell 里应当还有 ModuleUpdateCard 的入口", shell.contains("ModuleUpdateCard("))
        assertTrue(
            "ModuleUpdateCard 必须包在 `if (Edition.showsModuleUi) { … }` 里 —— " +
                "免 root 版渲染它等于把模块痕迹留在界面上（决策 1）",
            Regex("""if \(Edition\.showsModuleUi\) \{\s*ModuleUpdateCard\(""").containsMatchIn(shell),
        )
        // 反面：不允许再出现"免 root 版不使用 KernelSU 模块"这类说明（提模块本身就是痕迹）
        assertFalse(
            "关于页不该再有「免 root 版不使用 KernelSU 模块」这种说明文字（§六：不出现任何该字样）",
            codeOnly(shell).contains("免 root 版不使用 KernelSU 模块"),
        )
    }

    @Test
    fun `模块版本行也只在 Root 版出现`() {
        val shell = read("ui/AppShell.kt")
        assertTrue(
            "AboutLine(\"模块\", …) 必须在 Edition.showsModuleUi 之下",
            shell.contains("AboutLine(\"模块\"") &&
                Regex("""if \(Edition\.showsModuleUi\) AboutLine\(""").containsMatchIn(shell),
        )
    }

    @Test
    fun `模块状态探测在免 root 版根本不发起`() {
        val vm = read("ui/LauncherViewModel.kt")
        assertEquals(
            "模块探测只应有一处（多出来的那处必然是没被 edition 挡住的）",
            1,
            Regex("""DeviceStatus\.module\(""").findAll(vm).count(),
        )
        assertTrue(
            "DeviceStatus.module 必须在 `if (Edition.showsModuleUi)` 分支里 —— " +
                "免 root 版只做到「隐藏结果」不够，那台机器上这次探测本身就不该发生",
            vm.indexOf("if (Edition.showsModuleUi)") in 1 until vm.indexOf("DeviceStatus.module("),
        )
        assertTrue(
            "轮询里的 su 探测也要按 edition 短路（免 root 版会起 su 进程、还可能弹授权框）",
            vm.contains("val su = if (Edition.needsSu) DshRuntime.suAvailable() else false"),
        )
    }

    @Test
    fun `免 root 版不读内嵌模块包（BundledModule）`() {
        val welcome = read("ui/WelcomeState.kt")
        assertTrue(
            "BundledModule.info 必须在 `if (Edition.showsModuleUi)` 之下（免 root 版 assets/ 里没有它）",
            Regex("""if \(Edition\.showsModuleUi\) BundledModule\.info\(""").containsMatchIn(welcome),
        )
        for (fn in listOf("flashBundledModule", "exportBundledModule", "openModuleManager")) {
            val body = between(welcome, "fun $fn(", "\n    }")
            assertTrue(
                "$fn 开头必须有 `if (!Edition.showsModuleUi) return` 兜底（防止以后从别处调用）",
                body.contains("if (!Edition.showsModuleUi) return"),
            )
        }
    }

    @Test
    fun `引导日志里的模块那一行只在 Root 版打印`() {
        val welcome = read("ui/WelcomeState.kt")
        assertFalse(
            "免 root 版不该打印「免 root 版与 KernelSU 模块无关」—— 提模块本身就是痕迹",
            codeOnly(welcome).contains("免 root 版与 KernelSU 模块无关"),
        )
        assertTrue(welcome.contains("if (Edition.showsModuleUi) {"))
    }

    @Test
    fun `部署向导的日志同样不留模块字样`() {
        val provision = read("ProvisionActivity.kt")
        assertFalse(
            "向导日志里的模块行必须包在 edition 判断里（这句老文案本身就是模块痕迹）",
            codeOnly(provision).contains("免 root 版与 KernelSU 模块无关"),
        )
        assertTrue(provision.contains("if (Edition.showsModuleUi) {"))
    }

    // ───────────────────────── 任务 A（反方向）：root 版不留 proot 设置

    @Test
    fun `root 版不渲染免 root 运行时与层模式设置`() {
        val settings = read("SettingsActivity.kt")
        assertTrue(settings.contains("if (Edition.showsRootlessRuntimeUi)"))
        assertTrue(
            "「免 root 运行时」那一组选项必须在 showsRootlessRuntimeUi 之下（Root 版永远用不到）",
            settings.indexOf("if (Edition.showsRootlessRuntimeUi)") in 1 until settings.indexOf("只用 proroot"),
        )
        assertTrue(
            "层模式（loop/dir）只对 Root 版有意义，反过来在免 root 版也不该出现",
            settings.indexOf("if (Edition.showsLayerModeUi)") in 1 until settings.indexOf("默认 loop：losetup"),
        )
    }

    @Test
    fun `SUNSETLINUX_ROOTLESS 只在 proot 模式下递进环境`() {
        val ctl = read("core/LinuxCtl.kt")
        val env = between(ctl, "private fun baseEnv()", "private fun workDir()")
        // 注意用 `put("…")` 而不是裸变量名：注释里会引用这个名字解释"为什么只在 proot 递"，
        // 裸名字会让 indexOf 命中注释（写这个测试时真踩了）
        val rootless = env.indexOf("put(\"SUNSETLINUX_ROOTLESS\"")
        val layerMode = env.indexOf("put(\"SUNSETLINUX_LAYER_MODE\"")
        assertTrue("baseEnv 里还应当透传 rootless 运行时选择（给 proot 用）", rootless > 0)
        assertTrue(
            "baseEnv 必须先把 proot 独有的变量挡在 `if (mode == EnvMode.PROOT)` 之后",
            env.indexOf("if (mode == EnvMode.PROOT)") in 1 until rootless,
        )
        assertTrue(
            "层模式是 root 独有，反过来也要挡在 `if (mode == EnvMode.ROOT)` 之后",
            env.indexOf("if (mode == EnvMode.ROOT)") in 1 until layerMode,
        )
    }

    // ───────────────────────── 拆开的启动路径（仅 Root 版）

    @Test
    fun `拆开的启动按钮只在 Root 版渲染`() {
        val home = read("ui/LauncherHomePane.kt")
        assertTrue(
            "启动区必须按 Edition.showsSplitStartUi 分岔 —— 免 root 版保持原样（只有一键启动/停止）",
            home.contains("if (Edition.showsSplitStartUi)"),
        )
        val gate = home.indexOf("if (Edition.showsSplitStartUi)")
        for (call in listOf("vm.startEnvOnly()", "vm.dshStart()", "vm.dshStop()")) {
            assertTrue(
                "$call 必须出现在 showsSplitStartUi 分支里（否则免 root 版会渲染一条走不通的路）",
                home.indexOf(call) > gate,
            )
        }
        // 反方向：proot 那条分支仍然只有原来的启动/停止（一键启动语义不变）
        assertTrue(
            "免 root 分支必须保留原来的启动/停止主按钮",
            home.contains("\"启动环境\"") && home.contains("\"停止环境\""),
        )
    }

    @Test
    fun `免 root 版也能渲染（终端页的仅启动环境入口是可空的）`() {
        val terminal = read("ui/TerminalPane.kt")
        assertTrue(
            "TerminalPane 必须把「仅启动环境」做成可空入口 —— proot 版没有这条路",
            terminal.contains("onStartEnvOnly: (() -> Unit)? = null"),
        )
        val shell = read("ui/AppShell.kt")
        assertTrue(
            "AppShell 传这个回调时也要按 Edition.showsSplitStartUi 分岔",
            Regex("""onStartEnvOnly = if \(Edition\.showsSplitStartUi\)""").containsMatchIn(shell),
        )
    }

    // ───────────────────────── 任务 B：进入即启用

    @Test
    fun `启动器接了免 root 的进入即启用`() {
        val launcher = read("LauncherActivity.kt")
        assertTrue("LauncherActivity 没有创建自动启用状态机", launcher.contains("ProotBootstrapState(this, scope)"))
        assertTrue("LauncherActivity 没有渲染自动启用界面", launcher.contains("ProotBootstrapScreen("))
        assertTrue("自动启用必须在组合后启动（start() 幂等）", launcher.contains("boot.start()"))
        assertTrue("成功后要落到终端页", launcher.contains("startTab = ShellTab.TERMINAL"))
        assertTrue(
            "默认落终端 + 走自动路的静态条件都要有（edition + 用户取消标记）",
            launcher.contains("Edition.isRoot") && launcher.contains("prootBootstrapDeclined"),
        )
        assertTrue("失败时要有重试入口", launcher.contains("boot::retry"))
        assertTrue("失败时要有手动路径入口", launcher.contains("boot.decline()"))
    }

    @Test
    fun `铺环境的流水线只有一份实现（手动入口与自动入口共用）`() {
        val provisioner = File(srcDir, "core/ProotProvisioner.kt")
        assertTrue("core/ProotProvisioner.kt 不见了：那条共享流水线是任务的硬要求", provisioner.isFile)
        val shared = provisioner.readText()
        for (marker in listOf("ProotRuntime.ensure(", "OfflineApplier.apply(", "provision", "ProotRuntime.isReady(")) {
            assertTrue("共享流水线里没找到「$marker」—— 顺序被拆散了？", shared.contains(marker))
        }
        for (rel in listOf("ui/ProotBootstrapState.kt", "ui/WelcomeState.kt")) {
            val text = read(rel)
            assertTrue("$rel 应当调用共享流水线 ProotProvisioner.run(", text.contains("ProotProvisioner.run("))
            assertFalse(
                "$rel 里又出现了一份 OfflineApplier.apply —— 那就是「另写一份解包逻辑」：" +
                    "手动/自动两条路迟早漂移（顺序或 only 参数只会改到一边）",
                text.contains("OfflineApplier.apply("),
            )
        }
    }

    @Test
    fun `自动启用成功后写 onboarded 闸门、失败时不写`() {
        val state = read("ui/ProotBootstrapState.kt")
        assertTrue("成功后必须写 Prefs.onboarded（否则下次冷启动又进自动页）", state.contains("onboarded = true"))
        assertTrue(
            "取消自动必须是独立的标记（否则「自动失败 → 点手动」会被记成已引导，用户面对空环境无路可走）",
            state.contains("prootBootstrapDeclined = true"),
        )
        // 失败路径必须停在 FAILED（界面据此显示原始错误 + 重试），不能直接宣布成功
        assertTrue(state.contains("Phase.FAILED"))
        assertTrue("失败要留下原始错误给界面显示", state.contains("error = "))
    }

    @Test
    fun `自动启用页要消费系统栏 inset 并显示原始错误`() {
        val screen = read("ui/ProotBootstrapScreen.kt")
        assertTrue("ProotBootstrapScreen 没消费 inset（内容会被状态栏压住）", screen.contains("safeDrawingPadding"))
        assertTrue("失败时必须显示原始错误", screen.contains("原始错误"))
        assertTrue("失败时必须给重试按钮", screen.contains("重试"))
        assertTrue("必须保留手动路径", screen.contains("手动部署"))
    }

    @Test
    fun `自动启用只在免 root 版生效（root 版直走既有引导）`() {
        val launcher = read("LauncherActivity.kt")
        assertTrue(
            "defer 条件里必须同时有 `!Edition.isRoot` 与取消标记 —— " +
                "少了前者的名字，Root 版会被送进 proot 自动路",
            launcher.contains("!onboarded && !Edition.isRoot && !prefs.prootBootstrapDeclined"),
        )
    }

    /**
     * FileProvider 的 authority 必须跟着 applicationId 走。
     *
     * 真机实测（2026-09-19）：在已装 Root 版的机器上装免 root 版，安装器直接报
     * **「存在同名的 ContentProvider」** —— 因为 manifest 把 authority 写死成
     * `io.github.sunsetrne.sunsetlinux.files`，而 authority 在系统里是**全局唯一**的。
     * 同一个写死值还让**本 App 自己**对不上：代码请求的是
     * `"${context.packageName}.files"`（core/LogExport.kt、core/BundledModule.kt），
     * 于是导出日志 / 分享排障包在真机上本来就是坏的（找不到该 authority 的 provider）。
     * 改成 `${applicationId}.files` 一处同时修掉"装不上"与"功能坏"。
     */
    @Test
    fun `FileProvider 的 authority 必须带 applicationId（两个 App 才能装在同一台机器上）`() {
        val manifest = File(TestPaths.repoRoot, "app/app/src/main/AndroidManifest.xml").readText()
        assertTrue(
            "authority 没跟着 applicationId 走 —— 第二个 edition 会装不上（同名 ContentProvider）",
            manifest.contains("android:authorities=\"\${applicationId}.files\""),
        )
        assertFalse(
            "manifest 里还留着硬编码的 sunsetlinux.files authority",
            Regex("""android:authorities="io\.github\.sunsetrne\.sunsetlinux\.files"""").containsMatchIn(manifest),
        )
        // 与代码一致：两个调用点都按 packageName 拼 authority
        for (rel in listOf("core/LogExport.kt", "core/BundledModule.kt")) {
            assertTrue(
                "$rel 用的 authority 必须与 manifest 的 \${applicationId}.files 一致",
                read(rel).contains("\"\${context.packageName}.files\""),
            )
        }
    }
}
