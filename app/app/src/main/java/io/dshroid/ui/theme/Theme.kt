package io.dshroid.ui.theme

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.dshroid.core.EnvState

// ─────────────────────────────────────────────────────────────────────────────
// 单色板（黑白灰）—— 用户明确要求「颜色能不能改成白黑色？」
//
// 硬约束：**所有颜色必须是灰阶（R = G = B）**。不许出现任何带色相的值，
// 也不许再用带色相的名字（Cyan/Ink/Sky/Mint/Amber/Rose 之类）——
// 名字留色相、值却是灰的，比不改更难维护。
// 该约束由 PaletteContrastTest 逐 token 强校验（断言 R=G=B）。
//
// 层级靠**明度**表达：页面底最黑，越"浮起来"的面越亮；分隔靠描边。
// ─────────────────────────────────────────────────────────────────────────────

/** 面：由深到浅（0=页面底 / 1=卡片 / 2=次级面、入口块 / 3=最高层、对话框） */
val Mono0 = Color(0xFF000000)
val Mono1 = Color(0xFF0A0A0A)
val Mono2 = Color(0xFF141414)
val Mono3 = Color(0xFF1F1F1F)

/** 描边：普通 / 强调（强调用于选中态、错误态边框） */
val Line = Color(0xFF2A2A2A)
val LineStrong = Color(0xFF3D3D3D)

/** 主色＝白。强调、按钮、选中胶囊、状态点都用它。 */
val Accent = Color(0xFFFFFFFF)

/** 主色之上的前景（白底上的文字/图标）。 */
val OnAccent = Color(0xFF000000)

/** 文字三级：正文 / 次要 / 弱化 */
val TextPrimary = Color(0xFFF5F5F5)
val TextSecondary = Color(0xFFA3A3A3)
val TextMuted = Color(0xFF6E6E6E)

/**
 * 「警告/需要注意」在单色下的表达：中亮灰 + 警告图标。
 * 不再有黄色 —— 想表达"注意"只能靠**图标 + 文案 + 描边**。
 */
val WarnTone = Color(0xFFC4C4C4)

/**
 * 「危险/错误」在单色下的表达：**纯白 + 粗描边 + 加粗文案 + 叉号/叹号图标**。
 *
 * 取舍（必须说清楚）：丢掉红色后，错误态的"抢眼程度"确实下降。
 * 补偿手段：白字加粗、描边加亮（[LineStrong]）、强制带叉号图标、
 * 并把"下一步建议"卡片顶到最前面。
 * 若将来要"只给错误留一点红"，只需把这里换成一个红值 ——
 * 其它地方不用动（错误语义只走 [stateColor] / [Danger] 这两个出口）。
 */
val Danger = Color(0xFFFFFFFF)

/** 压在错误底（[Mono3]）上的文字色。 */
val OnDangerSurface = Color(0xFFF5F5F5)

/**
 * 状态语义（原方案靠颜色，单色下靠**明度 + 点形状 + 文案加粗**）：
 *
 * | 状态 | 明度 | 点形状 | 文案 |
 * |---|---|---|---|
 * | 运行中 | 最高（纯白） | 实心 + 呼吸光晕 | 常规 |
 * | 启动/停止中（过渡） | 中灰 | 空心环 | 常规 |
 * | 已停止 | 暗灰 | 实心小点 | 常规 |
 * | 错误 | 纯白 + 强描边 | 叉号 | 加粗 |
 * | 未知 | 弱灰 | 空心环 | 常规 |
 */
val StateRunning = Accent
val StateStarting = Color(0xFF8A8A8A)
val StateStopping = Color(0xFF8A8A8A)
val StateStopped = Color(0xFF5A5A5A)
val StateError = Accent
val StateUnknown = Color(0xFF6E6E6E)

/** 主按钮渐变（灰阶）：启动＝白→浅灰（黑字），停止＝深灰→更深（白字）。 */
val BrushStart = Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFFD4D4D4)))
val BrushStop = Brush.linearGradient(listOf(Color(0xFF3D3D3D), Color(0xFF1F1F1F)))

/** 顶部氛围：从略亮的面淡出到页面底（纯灰阶）。 */
val BrushHero = Brush.verticalGradient(listOf(Mono3, Mono0, Mono0))

internal val DshMonoColors = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = LineStrong,
    onPrimaryContainer = TextPrimary,
    secondary = TextSecondary,
    onSecondary = Mono0,
    secondaryContainer = Mono2,
    onSecondaryContainer = TextPrimary,
    tertiary = TextPrimary,
    onTertiary = Mono0,
    tertiaryContainer = Mono3,
    onTertiaryContainer = TextPrimary,
    background = Mono0,
    onBackground = TextPrimary,
    surface = Mono1,
    onSurface = TextPrimary,
    surfaceVariant = Mono2,
    onSurfaceVariant = TextSecondary,
    surfaceTint = Accent,
    inverseSurface = TextPrimary,
    inverseOnSurface = Mono0,
    // "错误色"在单色板里就是白色：靠描边/图标/加粗区分
    error = Danger,
    onError = OnAccent,
    errorContainer = Mono3,
    onErrorContainer = OnDangerSurface,
    outline = Line,
    outlineVariant = Line,
    scrim = Color(0xCC000000),
)

private val Base = Typography()

val DshTypography = Base.copy(
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.Medium),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = Base.labelSmall.copy(letterSpacing = 0.6.sp, fontWeight = FontWeight.Medium),
)

/** 等宽字体：URL、日志、命令输出统一用它，视觉上就和"终端"绑定。 */
val MonoFamily = FontFamily.Monospace

/**
 * 应用主题：**固定单色深色**，不跟随系统、不用 Material You 动态取色。
 *
 * 三条硬理由：
 * 1. 整套界面（10 个文件、百余处）直接引用上面这套硬编码的单色 token，
 *    跟随系统浅色会把近白底与深灰卡片混在一起（用户实测反馈"浅蓝和深蓝混合"）；
 * 2. 用户明确要求单色黑白，彩色强调一律去掉；
 * 3. 动态取色会把状态语义改成壁纸色，"一眼看出环境状态"就废了。
 *
 * 这里**没有**浅色方案：强制单色后它只会是漂移源。
 */
@Composable
fun DshroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DshMonoColors,
        typography = DshTypography,
        content = content,
    )
}

/**
 * 沉浸式系统栏，**图标固定为浅色**（黑底上可读）。
 *
 * 不能直接用 enableEdgeToEdge() 的默认值：它的 SystemBarStyle.auto 按系统明暗
 * 决定图标颜色 —— 在**浅色系统**上会选深色图标，而我们的底是纯黑，图标几乎看不见。
 * 所以显式用 SystemBarStyle.dark(...)（黑底 + 浅色图标）。
 *
 * 所有 Activity 都走这一个入口，避免 8 处各写一遍再漂移。
 */
fun ComponentActivity.applyDshSystemBars() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(Color.Transparent.toArgb()),
        // 导航栏在 API 26~28 不支持全透明，给一个纯黑 scrim，保证浅色图标始终可读
        navigationBarStyle = SystemBarStyle.dark(NAV_BAR_SCRIM),
    )
}

/** 导航栏 scrim（半透明黑，与底栏同色系）。 */
private val NAV_BAR_SCRIM = Color(0xCC000000).toArgb()

/**
 * 状态 → 颜色。**单色下这只是"明度"**，语义靠
 * StatusDot 的形状 + [stateLabelBold] 的加粗共同表达。
 */
fun stateColor(state: EnvState): Color = when (state) {
    EnvState.RUNNING -> StateRunning
    EnvState.STARTING -> StateStarting
    EnvState.STOPPING -> StateStopping
    EnvState.STOPPED -> StateStopped
    EnvState.ERROR -> StateError
    EnvState.UNKNOWN -> StateUnknown
}

/** 错误态文案必须加粗（单色下这是主要区分手段）。 */
fun stateLabelBold(state: EnvState): Boolean = state == EnvState.ERROR

/** 状态是否需要"强描边"卡片（错误态靠描边加亮来抢注意力）。 */
fun stateNeedsStrongOutline(state: EnvState): Boolean =
    state == EnvState.ERROR || state == EnvState.UNKNOWN
