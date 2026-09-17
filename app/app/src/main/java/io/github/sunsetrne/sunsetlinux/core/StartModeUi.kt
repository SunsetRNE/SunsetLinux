package io.github.sunsetrne.sunsetlinux.core

/**
 * 首页「操作」卡里的**两种启动方式**（用户可二选一）。
 *
 * ## 为什么是"两种方式"而不是"五个按钮一起摊开"
 *
 * 真机批注：「在一键启动和分步启动两个按钮之间切换」。五个按钮同时摆开时，用户看到的
 * 是一堆灰按钮 + 一句解释，得先读懂矩阵才知道先点哪个。拆成两组之后：
 *   · [ONE_SHOT]：主按钮「一键启动（环境 + DSH）」+「停止环境」—— 只想用 DSH 的人一条路走完；
 *   · [STEPWISE]：仅启动环境 / 启动 DSH / 停止 DSH / 停止环境 —— 要单独维护环境（终端、
 *     apt/npm）的人走这条。
 *
 * ⚠️ **切换只决定"显示哪一组"**：每个按钮的亮/灰仍然全部来自 [StartControls]（纯函数，
 * 单测穷举）。这里绝不能出现第二份"什么时候能点"的判定 —— 两份判定迟早会漂移，
 * 而漂移的表现是"按钮亮着，点下去被模块拒绝"（最难归因的一种故障）。
 */
enum class StartMode(
    /** 落盘用的稳定字符串（[Prefs.startMode]）；改了会让老用户的选择被重置。 */
    val wire: String,
    /** 切换控件上的文案。 */
    val label: String,
) {
    /** 一键启动（环境 + DSH）。 */
    ONE_SHOT("one-shot", "一键启动"),

    /** 分步启动（先仅启动环境，再单独启停 DSH）。 */
    STEPWISE("stepwise", "分步启动");

    companion object {
        /**
         * 读落盘值。**任何异常输入都退化为 [ONE_SHOT]** —— 它是默认档，
         * 也是"最省事、最不容易走错"的那条路（见 [Prefs.startMode]）。
         */
        fun from(raw: String?): StartMode =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) } ?: ONE_SHOT
    }
}

/**
 * 本次**界面实际该显示哪一组**按钮。
 *
 * ## 运行中为什么必须锁定（这是给用户看的理由，不是内部洁癖）
 *
 * "起法"是在 `start` 那一刻定死的，之后**改不了**：`env_mode=full` 时 DSH 是那次启动的
 * 一部分，模块会拒绝单独的 `dsh stop`（提示先停止环境）；`env_mode=env-only` 时环境里
 * 根本没有 DSH 那一层。所以运行中允许切换的话，用户会看到一组**点不动**的按钮，
 * 而原因藏在另一份文案里 —— 他会以为是"没加载好"或"坏了"。
 * 锁死 + 一句「要换方式请先停止环境」，才能把"唯一的出路"讲清楚（那条路就是
 * [StartControls.envStopEnabled]，矩阵里它永远为真）。
 *
 * ## 三种运行中的情况
 *
 * | 状态 | 结果 | 理由 |
 * |---|---|---|
 * | `env_mode=full` | 强制 [StartMode.ONE_SHOT] | 如实反映本次起法 |
 * | `env_mode=env-only` | 强制 [StartMode.STEPWISE] | 同上 |
 * | `env_mode=null`（旧模块） | 停在 `userChoice` | **判不了就不编造**（见下） |
 *
 * `env_mode=null` 时如果硬猜一个（比如按"大多数情况下是一键启动"猜 FULL），用户会看到
 * 「本次已按「一键启动」方式运行」这句**假话**。所以这里只停在用户当时选的那一档，
 * 并由 [startModeLockNote] 明确说出"判不了"。这也与 [StartControls] 的口径一致：
 * 判不了时所有启动类按钮都是灰的，只留「停止环境」。
 *
 * @param envRunning 环境是否真的在跑（`DshStatus.envRunning`，与 DSH 无关）。
 * @param envMode    本次起法；null = 环境没在跑**或**模块没报 → 判不了。
 * @param userChoice 用户持久化的选择（[Prefs.startMode]）。
 */
fun resolveStartMode(
    envRunning: Boolean,
    envMode: EnvRunMode?,
    userChoice: StartMode,
): StartMode {
    // 环境没跑：还没有"起法"可言，完全听用户的（默认一键启动）。
    if (!envRunning) return userChoice
    return when (envMode) {
        EnvRunMode.FULL -> StartMode.ONE_SHOT
        EnvRunMode.ENV_ONLY -> StartMode.STEPWISE
        // 判不了：不编造本次起法，只把切换锁在用户当时的选择上。
        null -> userChoice
    }
}

/**
 * 运行中一律锁住切换控件：起法已经定死，能换的只有"显示哪一组"，而换过去只会让用户
 * 面对一组灰按钮。**解锁的唯一办法是「停止环境」**，那句提示见 [startModeLockNote]。
 */
fun isStartModeLocked(envRunning: Boolean): Boolean = envRunning

/** `env_mode=full` 时的锁定提示（措辞与 [resolveStartMode] 的表格一一对应）。 */
const val NOTE_LOCKED_ONE_SHOT =
    "本次已按「一键启动」方式运行：要换方式请先「停止环境」"

/** `env_mode=env-only` 时的锁定提示。 */
const val NOTE_LOCKED_STEPWISE =
    "本次已按「分步启动」方式运行：要换方式请先「停止环境」"

/**
 * 判不了（旧模块 / 键缺失）时的锁定提示。
 *
 * 必须**如实说"判不了"**，不能借用上面两句 —— 那两句都在断言"本次是怎么起的"，
 * 而这里恰恰不知道。用户拿着这句去跑「诊断」或升级模块才对得上问题。
 */
const val NOTE_LOCKED_UNKNOWN =
    "本次启动方式判不了（模块没报 env_mode）：切换已锁定，要换方式请先「停止环境」"

/**
 * 切换控件下方那句提示；环境没跑时返回 null（没在锁定，不需要解释）。
 */
fun startModeLockNote(envRunning: Boolean, envMode: EnvRunMode?): String? {
    if (!envRunning) return null
    return when (envMode) {
        EnvRunMode.FULL -> NOTE_LOCKED_ONE_SHOT
        EnvRunMode.ENV_ONLY -> NOTE_LOCKED_STEPWISE
        null -> NOTE_LOCKED_UNKNOWN
    }
}
