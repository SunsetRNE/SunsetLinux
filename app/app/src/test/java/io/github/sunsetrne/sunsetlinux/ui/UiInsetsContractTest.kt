package io.github.sunsetrne.sunsetlinux.ui

import io.github.sunsetrne.sunsetlinux.TestPaths
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UI 契约的**源码级回归**（本机不能跑 Compose 渲染，所以能守的就是这些结构事实）。
 *
 * 每一条都对应一个"真机上被用户看见"的问题，不是凭空立的规矩：
 *
 * | 断言 | 对应现象 |
 * |---|---|
 * | 边到边 + 有输入框 ⇒ 必须有 `imePadding` | 键盘弹出盖住输入框（边到边后窗口不再自动让位） |
 * | 每个 Activity 都要消费 inset | 内容被状态栏/导航栏压住 |
 * | 不允许再写死 `96.dp` 底部留白 | 悬浮胶囊底栏盖住最后一张卡片；魔数散落必然漏改 |
 * | `LauncherActivity` 必须按 `onboarded` 门禁 | 首装"很大的黑屏页面，过一会才有模式引导" |
 * | 每个 Activity 都要 `adjustResize` | IME inset 在部分版本上根本不会派发 |
 */
class UiInsetsContractTest {

    private val repoDir = TestPaths.repoRoot
    private val srcDir = File(repoDir, "app/app/src/main/java/io/github/sunsetrne/sunsetlinux")
    private val uiDir = File(srcDir, "ui")
    private val manifest = File(repoDir, "app/app/src/main/AndroidManifest.xml")

    private fun ktFiles(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()

    /** 消费 inset 的写法（任选其一即可：安全区、系统栏、直接 windowInsetsPadding、Scaffold 自带）。 */
    private val insetConsumers = listOf(
        "safeDrawingPadding", "safeContentPadding", "systemBarsPadding",
        "windowInsetsPadding", "statusBarsPadding", "navigationBarsPadding",
    )

    // ─────────────────────────── ① 输入法（键盘遮挡）

    @Test
    fun `有输入框的界面必须处理 IME inset`() {
        val inputMarkers = listOf("OutlinedTextField", "BasicTextField(", "TextField(")
        val offenders = mutableListOf<String>()

        val activities = ktFiles(srcDir).filter { it.name.endsWith("Activity.kt") }
        for (f in activities) {
            val s = f.readText()
            val hasInput = inputMarkers.any { s.contains(it) }
            if (hasInput && !s.contains("imePadding")) offenders += f.name
        }

        // 外壳里的三个面板（插件包名 / 频道 URL / 本地源 URL）都在 AppShell 之下，
        // 所以 AppShell 必须统一消费一次 IME inset。
        val shell = File(uiDir, "AppShell.kt")
        assertTrue("找不到 ui/AppShell.kt", shell.isFile)
        assertTrue("AppShell 没处理 IME inset：外壳里的面板有输入框，键盘会盖住它们", shell.readText().contains("imePadding"))

        // DSH Web 面板有独立 Activity 入口，必须自己处理
        val web = File(uiDir, "DshWebPane.kt")
        assertTrue("DshWebPane 没处理 IME inset：DSH Web 里的输入框会被键盘盖住", web.readText().contains("imePadding"))

        assertTrue(
            "以下带输入框的 Activity 没有 imePadding（键盘会盖住输入框）：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `每个 Activity 都要声明 adjustResize（否则 IME inset 可能不派发）`() {
        assertTrue("找不到 AndroidManifest.xml：$manifest", manifest.isFile)
        val text = manifest.readText()
        val activities = Regex("<activity\\b.*?>", RegexOption.DOT_MATCHES_ALL).findAll(text).map { it.value }.toList()
        assertTrue("manifest 里没解析到 activity", activities.isNotEmpty())
        val bad = activities.filterNot { it.contains("windowSoftInputMode=\"adjustResize\"") }
        assertTrue(
            "以下 activity 没声明 windowSoftInputMode=adjustResize：\n" +
                bad.joinToString("\n") { it.lineSequence().first() },
            bad.isEmpty(),
        )
    }

    // ─────────────────────────── ② 系统栏 inset 的消费

    /**
     * 自己**不**直接写 inset modifier、而是把整屏交给某个 Compose 屏幕的 Activity。
     * 登记成"委托"，然后逐个校验被委托的文件**确实**消费了 inset ——
     * 这样既不误报，也不会因为改写屏幕文件而悄悄失去保护。
     */
    private val delegatesInsets = mapOf(
        // 免 root 首启那一屏（ProotBootstrapScreen）也是 LauncherActivity 的整屏委托，
        // 所以它必须一起登记：否则它自己漏了 safeDrawingPadding 也没人拦（内容会被状态栏压住）
        "LauncherActivity.kt" to listOf("ui/AppShell.kt", "ui/BootPlaceholder.kt", "ui/ProotBootstrapScreen.kt"),
        "WelcomeActivity.kt" to listOf("ui/WelcomeScreen.kt"),
    )

    @Test
    fun `所有 Activity 都要消费系统栏 inset`() {
        val offenders = mutableListOf<String>()
        for (f in ktFiles(srcDir).filter { it.name.endsWith("Activity.kt") }) {
            val s = f.readText()
            // 只看用了边到边（applyDshSystemBars）的页面：不用边到边的页面由系统负责让位
            if (!s.contains("applyDshSystemBars")) continue
            if (insetConsumers.any { s.contains(it) }) continue
            val targets = delegatesInsets[f.name]
            if (targets == null) {
                offenders += "${f.name}（既没自己消费，也没登记委托）"
                continue
            }
            for (rel in targets) {
                val target = File(srcDir, rel)
                if (!target.isFile) { offenders += "${f.name} 委托的 $rel 不存在"; continue }
                if (insetConsumers.none { target.readText().contains(it) }) {
                    offenders += "${f.name} 委托的 $rel 没有消费 inset"
                }
            }
        }
        assertTrue(
            "以下 Activity 开了边到边却没有消费系统栏 inset（内容会被状态栏/导航栏盖住）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    // ─────────────────────────── ③ 悬浮底栏留白必须走常量

    @Test
    fun `悬浮胶囊底栏的留白必须是常量而不是散落的 96dp`() {
        val offenders = mutableListOf<String>()
        for (f in ktFiles(uiDir)) {
            val s = f.readText()
            if (Regex("""height\(96\.dp\)|bottom\s*=\s*96\.dp""").containsMatchIn(s)) offenders += f.name
        }
        assertTrue(
            "以下文件直接写死了 96.dp 留白，请改用 ui/components/Common.kt 的 CapsuleReserve：$offenders",
            offenders.isEmpty(),
        )
        // 常量本身要在，且至少被 3 个面板引用
        val common = File(uiDir, "components/Common.kt").readText()
        assertTrue("Common.kt 里没有 CapsuleReserve 常量", common.contains("val CapsuleReserve"))
        val users = ktFiles(uiDir).count { it.readText().contains("CapsuleReserve") }
        assertTrue("CapsuleReserve 只被 $users 个文件引用（面板至少 3 个 + AppShell）", users >= 4)
    }

    // ─────────────────────────── ④ 首启门禁与首帧（黑屏问题）

    @Test
    fun `LauncherActivity 必须先判引导门禁再组合外壳`() {
        val s = File(srcDir, "LauncherActivity.kt").readText()
        assertTrue("LauncherActivity 没有读取引导状态 onboarded", s.contains("onboarded"))
        assertTrue("LauncherActivity 没有用 BootPlaceholder 画首帧（会退回「纯黑等待」）", s.contains("BootPlaceholder"))
        // 门禁必须在 composition 内生效（否则外壳仍会被组合，首帧照样重）
        assertTrue("门禁没有作用到 setContent 内部：首帧仍会组合整个外壳", s.contains("if (!onboarded)"))
        // 占位屏自身要能消费 inset
        val placeholder = File(uiDir, "BootPlaceholder.kt").readText()
        assertTrue("BootPlaceholder 没消费系统栏 inset", insetConsumers.any { placeholder.contains(it) })
    }

    // ─────────────────────────── ⑤ 系统返回必须"跟手"

    @Test
    fun `返回手势必须用 PredictiveBackHandler（跟手）而不是只在抬手时回调`() {
        // `BackHandler` 只在抬手时回调一次，拖动过程毫无反馈 —— 用户实测的
        // "返回不跟手、对系统返回无任何消费"。凡是自己做返回跳转的地方都要用
        // PredictiveBackHandler 拿进度。
        val mustBePredictive = listOf(
            "ui/DshWebPane.kt" to "网页历史返回",
            "ui/AppShell.kt" to "非首页 tab 回首页",
        )
        val offenders = mutableListOf<String>()
        for ((rel, why) in mustBePredictive) {
            val f = File(srcDir, rel)
            assertTrue("找不到 $rel", f.isFile)
            val text = f.readText()
            if (!text.contains("PredictiveBackHandler")) offenders += "$rel（$why）"
        }
        assertTrue(
            "以下位置还在用只在抬手时回调的返回处理，手势不会跟手：$offenders",
            offenders.isEmpty(),
        )
        // 同时确认没有退回传统 BackHandler（注释里提到不算）
        // ⚠️ 注意 `PredictiveBackHandler {` 里就含有 `BackHandler {` 这段子串，
        //    所以必须用负向后顾，否则这个断言会把自己的写法判成违例（写的时候真踩了）。
        val legacyBackHandler = Regex("""(?<!Predictive)BackHandler\s*\{""")
        for ((rel, _) in mustBePredictive) {
            val code = File(srcDir, rel).readText().lineSequence()
                .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }
                .joinToString("\n")
            assertTrue("$rel 里仍在使用传统 BackHandler（不会跟手）", !legacyBackHandler.containsMatchIn(code))
        }
    }

    @Test
    fun `zstd 能力探测不许在构造器或组合期同步执行`() {
        // 探测 = 写临时文件 + 解一段真实 zstd 帧，是磁盘 IO；放进冷启动首帧会拖长黑屏
        val offenders = mutableListOf<String>()
        for (f in ktFiles(srcDir)) {
            val s = f.readText()
            for (line in s.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
                val callsProbe = trimmed.contains("TransportSupport.zstdUnavailableReason()")
                if (!callsProbe) continue
                // 只拦"成员级属性初始化"（缩进正好 4 空格）——那是在构造器里跑的。
                // 函数体内的局部 val（缩进 ≥8）不拦：调用方负责放到 IO 协程。
                // 另外拦命名参数写法（曾出过事的那种：MutableStateFlow(UiState(zstdReason = …))）。
                val memberLevel = line.length - line.trimStart().length == 4
                val looksLikeProperty = Regex("""^\s*(private\s+|internal\s+|public\s+)?(val|var)\s+\w+.*=.*TransportSupport\.zstdUnavailableReason\(\)""")
                    .containsMatchIn(line)
                val namedArg = Regex("""\w+\s*=\s*TransportSupport\.zstdUnavailableReason\(\)""").containsMatchIn(trimmed)
                if ((memberLevel && looksLikeProperty) || (memberLevel && namedArg)) {
                    offenders += "${f.name}: ${trimmed.take(90)}"
                }
            }
        }
        assertTrue(
            "以下位置在属性初始化（主线程/组合期）里同步跑了 zstd 探测，请挪到 IO 协程：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
