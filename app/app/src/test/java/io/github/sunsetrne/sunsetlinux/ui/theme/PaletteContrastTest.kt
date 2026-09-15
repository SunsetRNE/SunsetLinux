package io.github.sunsetrne.sunsetlinux.ui.theme

import androidx.compose.ui.graphics.Color
import io.github.sunsetrne.sunsetlinux.core.EnvState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 配色的**可计算**验收（单色板）。
 *
 * 本机是 aarch64，Compose 的 layoutlib/Preview 截图渲染跑不起来（layoutlib 只有
 * x86-64 native 实现），出不了预览截图。能做的渲染级验证就是这一套：
 *
 * 1. **主题恒为单色深色**（用户实测"浅蓝深蓝混合"的回归点）；
 * 2. 界面里 100+ 处**直接引用**的 token 也必须深浅正确 —— 它们不经过 MaterialTheme，
 *    当初"面变浅"正是没被 colorScheme 兜住；
 * 3. 源码守卫：不许再跟随系统、不许有第二套色板、系统栏图标必须显式浅色；
 * 4. **单色守卫：所有 token 必须 R=G=B**（用户这次要求的核心，谁塞回彩色就该测红）；
 * 5. 真实配对（前景, 背景）的 WCAG 对比度，以及"错误态失去红色后靠什么区分"。
 */
class PaletteContrastTest {

    // 仓库根由 TestPaths 向上查找解析，**不写死开发机绝对路径**（否则别人 clone 跑不了）。
    private val repoDir = io.github.sunsetrne.sunsetlinux.TestPaths.repoRoot

    /** 界面直接引用、不经过 MaterialTheme 的全部 token。 */
    private val directTokens: List<Pair<String, Color>> = listOf(
        "Mono0" to Mono0, "Mono1" to Mono1, "Mono2" to Mono2, "Mono3" to Mono3,
        "Line" to Line, "LineStrong" to LineStrong,
        "Accent" to Accent, "OnAccent" to OnAccent,
        "TextPrimary" to TextPrimary, "TextSecondary" to TextSecondary, "TextMuted" to TextMuted,
        "WarnTone" to WarnTone, "Danger" to Danger, "OnDangerSurface" to OnDangerSurface,
        "StateRunning" to StateRunning, "StateStarting" to StateStarting,
        "StateStopping" to StateStopping, "StateStopped" to StateStopped,
        "StateError" to StateError, "StateUnknown" to StateUnknown,
    )

    // ─────────────────────────────────────── ④ 单色守卫（本次核心）

    @Test
    fun `所有颜色 token 必须是灰阶（R=G=B）`() {
        val violations = directTokens.filter { (_, c) -> !isGray(c) }
        println("灰阶检查（${directTokens.size} 个 token）")
        directTokens.forEach { (name, c) -> println("  ${if (isGray(c)) "✓" else "✗"} $name = ${hex(c)}") }
        assertTrue(
            "用户要求单色黑白：以下 token 不是灰阶（R=G=B）：" +
                violations.joinToString { "${it.first}=${hex(it.second)}" },
            violations.isEmpty(),
        )
    }

    /**
     * 源码级单色守卫：整个 UI 里除了 EROFS 魔数 `0xE0F5E1E2`（不是颜色）之外，
     * 不允许出现任何 R≠G≠B 的颜色字面量。
     */
    @Test
    fun `全仓 UI 颜色字面量都是灰阶`() {
        val uiDir = File(repoDir, "app/app/src/main/java/io/github/sunsetrne/sunsetlinux")
        assumeTrue("找不到源码目录（${uiDir.path}），跳过", uiDir.isDirectory)

        val colorLiteral = Regex("""0x([0-9A-Fa-f]{8})\b""")
        val offenders = mutableListOf<String>()
        uiDir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            f.readLines().forEachIndexed { idx, line ->
                colorLiteral.findAll(line).forEach { m ->
                    val argb = m.groupValues[1].uppercase()
                    // 只检查看起来像 ARGB 颜色（AA/FF 开头或 App 用色习惯）的字面量
                    val r = argb.substring(2, 4)
                    val g = argb.substring(4, 6)
                    val b = argb.substring(6, 8)
                    val gray = r == g && g == b
                    val isErofsMagic = argb == "E0F5E1E2"
                    if (!gray && !isErofsMagic) {
                        offenders.add("${f.name}:${idx + 1} 0x$argb")
                    }
                }
            }
        }
        assertTrue(
            "这些颜色字面量不是灰阶（单色主题下会破坏整体观感）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    // ─────────────────────────────────────── ① 主题恒为单色深色

    @Test
    fun `配色方案是深色且文字是浅色`() {
        val scheme = DshMonoColors
        listOf(
            "background" to scheme.background,
            "surface" to scheme.surface,
            "surfaceVariant" to scheme.surfaceVariant,
        ).forEach { (name, color) ->
            val l = luminance(color)
            assertTrue("$name 必须是深色（实际相对亮度 $l）", l < 0.10f)
        }
        listOf(
            "onBackground" to scheme.onBackground,
            "onSurface" to scheme.onSurface,
        ).forEach { (name, color) ->
            val l = luminance(color)
            assertTrue("$name 必须是浅色（实际相对亮度 $l）", l > 0.60f)
        }
    }

    // ─────────────────────────────────────── ② 直接引用的 token

    @Test
    fun `界面直接引用的 token 深浅正确`() {
        listOf("Mono0" to Mono0, "Mono1" to Mono1, "Mono2" to Mono2, "Mono3" to Mono3)
            .forEach { (name, c) ->
                val l = luminance(c)
                println("  face $name=${hex(c)} 亮度=$l")
                assertTrue("$name 必须是深色（实际 $l）", l < 0.12f)
            }
        listOf("TextPrimary" to TextPrimary, "TextSecondary" to TextSecondary, "TextMuted" to TextMuted)
            .forEach { (name, c) ->
                val l = luminance(c)
                println("  text $name=${hex(c)} 亮度=$l")
                assertTrue("$name 必须是浅色（实际 $l）", l > 0.15f)
            }
        // 面的层级必须是单调递增的明度（否则"浮起来"的视觉层级就没了）
        assertTrue("Mono0 < Mono1 < Mono2 < Mono3", luminance(Mono0) < luminance(Mono1))
        assertTrue("Mono1 < Mono2", luminance(Mono1) < luminance(Mono2))
        assertTrue("Mono2 < Mono3", luminance(Mono2) < luminance(Mono3))
        assertTrue("描边要比它所在的面亮：Line > Mono1", luminance(Line) > luminance(Mono1))
        assertTrue("强描边更亮：LineStrong > Line", luminance(LineStrong) > luminance(Line))
    }

    // ─────────────────────────────────────── ③ 源码守卫

    @Test
    fun `主题不允许再跟随系统、也不允许出现第二套色板`() {
        val themeFile = File(repoDir, "app/app/src/main/java/io/github/sunsetrne/sunsetlinux/ui/theme/Theme.kt")
        assumeTrue("找不到 Theme.kt（${themeFile.path}），跳过源码守卫", themeFile.isFile)
        // 只检查**代码**：注释里会（也应该）解释为什么不再跟随系统，那不算违规
        val src = stripComments(themeFile.readText())

        assertTrue(
            "Theme.kt 不允许再出现 isSystemInDarkTheme()：整套 UI 基于硬编码单色 token，" +
                "跟随系统浅色会把两套色混在一起（用户实测问题）",
            !src.contains("isSystemInDarkTheme("),
        )
        assertTrue("不允许再有浅色方案 DshLightColors（固定单色后它只会是漂移源）", !src.contains("DshLightColors"))
        assertTrue("Theme.kt 应当固定使用 DshMonoColors", src.contains("colorScheme = DshMonoColors"))
        assertTrue(
            "系统栏图标必须显式用 SystemBarStyle.dark（浅色图标），否则浅色系统下黑底上看不见图标",
            src.contains("SystemBarStyle.dark("),
        )
        assertTrue("系统栏入口必须集中在一个 helper 里", src.contains("fun ComponentActivity.applyDshSystemBars()"))
    }

    /** 8 个 Activity 必须都走 helper，不许各写一遍 enableEdgeToEdge()（否则迟早漂移）。 */
    @Test
    fun `所有 Activity 都走统一的系统栏入口`() {
        val dir = File(repoDir, "app/app/src/main/java/io/github/sunsetrne/sunsetlinux")
        assumeTrue("找不到源码目录，跳过", dir.isDirectory)
        val activities = dir.listFiles { f -> f.isFile && f.name.endsWith("Activity.kt") }?.toList() ?: emptyList()
        assumeTrue("没有找到 Activity 文件，跳过", activities.isNotEmpty())

        val offenders = activities.filter { f ->
            val src = f.readText()
            src.contains("enableEdgeToEdge()") && !src.contains("applyDshSystemBars()")
        }.map { it.name }
        assertTrue("这些 Activity 仍在直接调用 enableEdgeToEdge()：$offenders", offenders.isEmpty())
        println("  ${activities.size} 个 Activity 全部走 applyDshSystemBars()")
    }

    // ─────────────────────────────────────── ⑤ 对比度审计（单色真实配对）

    private data class ContrastCase(val name: String, val fg: Color, val bg: Color, val min: Float)

    @Test
    fun `界面实际使用的配色对比度达标`() {
        val pairs = listOf(
            // 正文（AAA 7:1）
            ContrastCase("正文/页面底", TextPrimary, Mono0, 7f),
            ContrastCase("正文/卡片", TextPrimary, Mono1, 7f),
            ContrastCase("正文/次级面", TextPrimary, Mono2, 7f),
            // 次要文字（AA 4.5:1）
            ContrastCase("次要文字/页面底", TextSecondary, Mono0, 4.5f),
            ContrastCase("次要文字/卡片", TextSecondary, Mono1, 4.5f),
            ContrastCase("次要文字/次级面", TextSecondary, Mono2, 4.5f),
            // 强调色＝白：链接、选中态、按钮底
            ContrastCase("强调色/页面底", Accent, Mono0, 4.5f),
            ContrastCase("强调色/卡片", Accent, Mono1, 4.5f),
            ContrastCase("强调色/次级面", Accent, Mono2, 4.5f),
            // 按钮前景（白底黑字 / 深底白字）
            ContrastCase("启动按钮文字/白底", OnAccent, Accent, 7f),
            ContrastCase("停止按钮文字/渐变起点", TextPrimary, Color(0xFF3D3D3D), 4.5f),
            ContrastCase("停止按钮文字/渐变终点", TextPrimary, Color(0xFF1F1F1F), 4.5f),
            // 警告 tone
            ContrastCase("警告 tone/页面底", WarnTone, Mono0, 4.5f),
            ContrastCase("警告 tone/次级面", WarnTone, Mono2, 4.5f),
            // 错误容器上的白字
            ContrastCase("错误文字/错误容器", Danger, Mono3, 4.5f),
            ContrastCase("错误文字(细)/错误容器", OnDangerSurface, Mono3, 4.5f),
            // 状态语义（明度区分，图形/大字 AA large 3:1）
            ContrastCase("状态·运行(白)/页面底", StateRunning, Mono0, 7f),
            ContrastCase("状态·过渡(中灰)/页面底", StateStarting, Mono0, 3f),
            ContrastCase("状态·停止中(中灰)/页面底", StateStopping, Mono0, 3f),
            ContrastCase("状态·已停止(暗灰)/页面底", StateStopped, Mono0, 3f),
            ContrastCase("状态·错误(白)/页面底", StateError, Mono0, 7f),
            ContrastCase("状态·未知(弱灰)/页面底", StateUnknown, Mono0, 3f),
            // 弱化文字（小号标签；AA large 下限，见下面单独记录的取舍）
            ContrastCase("弱化文字/页面底", TextMuted, Mono0, 3f),
            ContrastCase("弱化文字/卡片", TextMuted, Mono1, 3f),
            ContrastCase("弱化文字/次级面", TextMuted, Mono2, 3f),
            // 层级可见性：面与面、描边与面必须有可辨的明度差
            ContrastCase("面层级 卡片/页面底", Mono1, Mono0, 1.05f),
            ContrastCase("面层级 次级面/页面底", Mono2, Mono0, 1.1f),
            // 装饰性发丝描边：WCAG 没有对应条款，这里取"可辨差异"下限 1.25:1
            // （规格给的 #2A2A2A 压在 #0A0A0A 卡面上实测 1.38:1；若有人把描边设成与面同色，
            //  比值掉到 1.0 就会测红 —— 这条守的是"描边不能消失"，不是文字对比度）
            ContrastCase("描边/卡片", Line, Mono1, 1.25f),
            ContrastCase("强描边/页面底", LineStrong, Mono0, 1.8f),
        )

        val problems = StringBuilder()
        println("配色对比度审计（WCAG 2.1 相对亮度，单色板）")
        pairs.forEach { p ->
            val r = contrast(p.fg, p.bg)
            val ok = r >= p.min
            if (!ok) problems.append("\n  ✗ ${p.name}: ${"%.2f".format(r)} < ${p.min}")
            println("  ${if (ok) "✓" else "✗"} ${p.name.padEnd(24)} ${"%.2f".format(r)}:1  (要求 ≥ ${p.min})")
        }
        assertEquals("对比度不达标：$problems", 0, problems.length)
    }

    /**
     * **单色下的取舍（显式记录，不要偷偷放宽）**
     *
     * 错误态失去红色后，抢眼程度确实下降。补偿手段不是颜色，而是三件事：
     * 1. 状态点画成 **叉号**（形状与"圆点/空心环"完全不同）；
     * 2. 状态文案 **加粗**；
     * 3. 状态卡 **强描边**（[LineStrong]）。
     *
     * 这条测试把三者都钉住 —— 谁把其中任何一个去掉，测试就红。
     */
    @Test
    fun `错误态在没有红色的前提下仍有三种区分手段`() {
        assertTrue("错误态文案必须加粗", stateLabelBold(EnvState.ERROR))
        assertTrue("错误态必须用强描边", stateNeedsStrongOutline(EnvState.ERROR))
        // 错误与运行同亮度是**刻意的**：单色下不靠颜色区分，靠形状/加粗/描边
        assertEquals(
            "错误态与运行态亮度相同（都取最亮），区分靠形状而非颜色",
            luminance(StateError),
            luminance(StateRunning),
            0.0001f,
        )
        // 但与"停止/过渡"必须有明度差，否则状态一眼分不出来
        assertTrue("错误(最亮) 必须比 过渡(中灰) 亮", luminance(StateError) > luminance(StateStarting) * 2f)
        assertTrue("过渡 必须比 已停止 亮", luminance(StateStarting) > luminance(StateStopped))
        // 强描边在纯黑底上要看得见（1.8:1 足够看清一条 1dp 边框）
        val outline = contrast(LineStrong, Mono0)
        println("强描边/页面底 = ${"%.2f".format(outline)}:1（错误态靠它抢注意力）")
        assertTrue("强描边在纯黑底上必须可辨（实际 ${"%.2f".format(outline)}:1）", outline >= 1.8f)
    }

    /** 弱化文字在最浅的面板上的对比度被显式记录（小字号下偏紧，需要时两边一起提亮）。 */
    @Test
    fun `弱化文字的对比度被显式记录`() {
        val ratio = contrast(TextMuted, Mono2)
        println("TextMuted on Mono2 = ${"%.2f".format(ratio)}:1（小字号标签，建议 ≥ 4.5，当前偏紧）")
        assertTrue("TextMuted 在次级面上不能低于 3.0（图形/大字下限），实际 ${"%.2f".format(ratio)}", ratio >= 3f)
    }

    // ─────────────────────────────────────── 工具

    private fun isGray(c: Color): Boolean {
        val r = (c.red * 255).toInt()
        val g = (c.green * 255).toInt()
        val b = (c.blue * 255).toInt()
        return r == g && g == b
    }

    /** 去掉块注释与行注释，避免"注释里提到某个禁用项"被误判成违规。 */
    private fun stripComments(src: String): String =
        src.replace(Regex("""(?s)/\*.*?\*/"""), "")
            .replace(Regex("""(?m)//.*$"""), "")

    /** 相对亮度（WCAG 定义，sRGB 反伽马 + 加权）。 */
    private fun luminance(c: Color): Float {
        fun ch(v: Float): Float = if (v <= 0.03928f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
        return 0.2126f * ch(c.red) + 0.7152f * ch(c.green) + 0.0722f * ch(c.blue)
    }

    /** 对比度 (L1+0.05)/(L2+0.05)。 */
    private fun contrast(a: Color, b: Color): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    private fun hex(c: Color): String =
        "#%02X%02X%02X".format((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
}
