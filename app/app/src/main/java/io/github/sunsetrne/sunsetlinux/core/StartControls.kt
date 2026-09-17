package io.github.sunsetrne.sunsetlinux.core

/**
 * 首页「启动区」五个按钮的**启用矩阵**。
 *
 * ## 为什么抽成纯函数（而不是写在 Compose 里）
 *
 * 这次的改动把"启动"拆成两条互斥的路：**一键启动**（环境 + DSH）与
 * **拆开启动**（先「仅启动环境」、再「启动 DSH」）。两条路一旦混用，模块侧会
 * 直接拒绝命令（退出码 1 + 中文 last_error），用户看到的是"按钮能点但一点就报错"——
 * 这比按钮置灰难懂得多。所以互斥判定必须在**点下去之前**就做对。
 *
 * 判定错一格，界面上完全看不出来（按钮只是亮/灰的差别），真机也很难复现，
 * 所以这里做成 core 里的纯函数：JVM 单测可以穷举状态矩阵（见 `StartControlsTest`），
 * 而 `LauncherHomePane` 只负责画。
 *
 * ## 矩阵（root 版）
 *
 * | 状态 | 一键启动 | 仅启动环境 | 启动 DSH | 停止 DSH | 停止环境 |
 * |---|---|---|---|---|---|
 * | 环境未运行 | ✅ | ✅ | ❌ | ❌ | ❌ |
 * | `env_mode=full` 且在跑 | ❌ | ❌ | ❌ | ❌ | ✅ |
 * | `env_mode=env-only` 且 DSH 没跑 | ❌ | ❌ | ✅ | ❌ | ✅ |
 * | `env_mode=env-only` 且 DSH 在跑 | ❌ | ❌ | ❌ | ✅ | ✅ |
 * | 在跑但 `env_mode` 判不了（旧模块） | ❌ | ❌ | ❌ | ❌ | ✅ |
 *
 * 两条贯穿整张表的理由：
 *   · **环境在跑时不能再来一次"启动"**：一键启动会被脚本按 `env_mode` 拒绝
 *     （`env-only` 时拒绝"带 DSH 的 start"）。所以只要 running，启动类按钮一律置灰；
 *   · **`full` 模式下 DSH 不独立**：DSH 是那次一键启动的一部分，模块会拒绝单独
 *     `dsh stop`（提示先停止环境）。界面必须提前拦住，否则用户看到的是脚本报错。
 */
data class StartControls(
    /** 一键启动（环境 + DSH）—— 与下面四个互斥的那条"省事"路径。 */
    val oneShotEnabled: Boolean,
    /** 仅启动环境（`linuxctl start --no-dsh`）。 */
    val startEnvOnlyEnabled: Boolean,
    /** 启动 DSH（`linuxctl dsh start`）—— 只在 env-only 且 DSH 没跑时可用。 */
    val dshStartEnabled: Boolean,
    /** 停止 DSH（`linuxctl dsh stop`）—— 只在 env-only 且 DSH 在跑时可用。 */
    val dshStopEnabled: Boolean,
    /** 停止环境（`linuxctl stop`）—— 环境在跑就总是允许（这是唯一的"退回去重选"的路）。 */
    val envStopEnabled: Boolean,
    /**
     * 为什么有些按钮点不了 / 用户下一步该做什么。直接显示在按钮下方。
     *
     * 用常量而不是各处拼字符串：这几句话是**互斥判定的解释**，改了矩阵却忘了改文案，
     * 用户就会照着一条走不通的提示操作（真机上最容易出这种问题）。
     */
    val note: String?,
    /** [note] 是"受限/走不通"（警示色）还是中性说明（普通色）。 */
    val noteIsWarning: Boolean,
) {
    /** 供源码级/单测断言：五个按钮里有几个可点（用于"至少有一个出口"这类检查）。 */
    val enabledCount: Int
        get() = listOf(oneShotEnabled, startEnvOnlyEnabled, dshStartEnabled, dshStopEnabled, envStopEnabled)
            .count { it }

    companion object {

        const val NOTE_NOT_PROVISIONED =
            "环境尚未部署：先到「部署」跑一次部署向导，再回来启动。"

        /** 环境没起来时 DSH 两个按钮为什么是灰的 —— 说清"先做什么"，而不是只说"不可用"。 */
        const val NOTE_NEED_ENV_FIRST =
            "环境未运行：DSH 跑在环境里，要单独启停 DSH 得先点「仅启动环境」。"

        /** 一键启动与拆开拆解的核心互斥：full 模式下 DSH 不独立，模块会拒绝单独停它。 */
        const val NOTE_FULL_MODE =
            "本次是一键启动（环境 + DSH）：要单独控制 DSH，请先停止环境，再选「仅启动环境」。"

        /** 在跑但模块没报 env_mode（旧模块）：判不了就保守，只留"停止环境"这一条退路。 */
        const val NOTE_MODE_UNKNOWN =
            "无法判定本次启动方式（模块没报 env_mode）：先停止环境，再选「仅启动环境」即可分开控制。"

        const val NOTE_ENV_ONLY_DSH_DOWN =
            "环境按「仅环境」方式运行，DSH 未启动：点「启动 DSH」把它接上。"

        const val NOTE_ENV_ONLY_DSH_UP =
            "环境按「仅环境」方式运行，DSH 正在跑：可以单独停止 DSH（环境会继续运行）。"

        /**
         * 唯一的判定入口。所有入参都是**已经从 status 解析好的事实**，
         * 这里不再读 JSON、不碰 Android API —— 单测能直接穷举。
         *
         * @param notProvisioned 明确没部署（对应 `provisioned == false`）；"还没探测出来"不算。
         * @param envRunning     环境是否在跑（`DshStatus.envRunning`）。
         * @param envMode        本次起法；null = 判不了（没在跑 / 旧模块）。
         * @param dshRunning     DSH 是否在跑（`DshStatus.dshRunning`，已含缺键退化）。
         */
        fun forStatus(
            notProvisioned: Boolean,
            envRunning: Boolean,
            envMode: EnvRunMode?,
            dshRunning: Boolean,
        ): StartControls {
            if (notProvisioned) {
                return StartControls(
                    oneShotEnabled = false,
                    startEnvOnlyEnabled = false,
                    dshStartEnabled = false,
                    dshStopEnabled = false,
                    envStopEnabled = false,
                    note = NOTE_NOT_PROVISIONED,
                    noteIsWarning = true,
                )
            }

            if (!envRunning) {
                // 环境没起来：两条启动路都开着（它们互斥的是"起法"，不是"能不能起"），
                // DSH 的启停全部置灰 —— 环境不存在时 dsh 子命令会被模块拒绝。
                return StartControls(
                    oneShotEnabled = true,
                    startEnvOnlyEnabled = true,
                    dshStartEnabled = false,
                    dshStopEnabled = false,
                    envStopEnabled = false,
                    note = NOTE_NEED_ENV_FIRST,
                    noteIsWarning = true,
                )
            }

            // 环境已在跑：启动类按钮全关（脚本会拒绝重复/混用的 start），只留停止环境。
            return when (envMode) {
                EnvRunMode.FULL -> StartControls(
                    oneShotEnabled = false,
                    startEnvOnlyEnabled = false,
                    dshStartEnabled = false,
                    dshStopEnabled = false,
                    envStopEnabled = true,
                    note = NOTE_FULL_MODE,
                    noteIsWarning = true,
                )

                EnvRunMode.ENV_ONLY -> StartControls(
                    oneShotEnabled = false,
                    startEnvOnlyEnabled = false,
                    // 两个按钮按 dsh.running 互斥：正在跑就只能停，没跑就只能启。
                    dshStartEnabled = !dshRunning,
                    dshStopEnabled = dshRunning,
                    envStopEnabled = true,
                    note = if (dshRunning) NOTE_ENV_ONLY_DSH_UP else NOTE_ENV_ONLY_DSH_DOWN,
                    noteIsWarning = false,
                )

                // 判不了（旧模块 / 键缺失）：不猜是 full 还是 env-only，只给"停止环境"这条
                // 一定能走通的路，并说明为什么其余按钮是灰的。
                null -> StartControls(
                    oneShotEnabled = false,
                    startEnvOnlyEnabled = false,
                    dshStartEnabled = false,
                    dshStopEnabled = false,
                    envStopEnabled = true,
                    note = NOTE_MODE_UNKNOWN,
                    noteIsWarning = true,
                )
            }
        }

        /**
         * 「打开 DSH」卡片的副标题（纯函数，便于穷举单测）。
         *
         * 真机反馈（2026-09-18 截图）：点完「仅启动环境」，这张卡片就一直写着
         * **「正在获取登录地址…」** —— 可 DSH 根本没起，那句话**永远等不到结果**，
         * 用户以为界面卡住了。改成按"为什么打不开"如实说：
         *   · 环境没跑 → 环境未运行
         *   · 仅环境方式且 DSH 没跑 → 明确告诉他点「启动 DSH」
         *   · DSH 在跑但 url 还没写出来 → 那才是真的"正在获取"，等一下就好
         */
        fun openDshSupporting(
            canOpenWeb: Boolean,
            displayUrl: String?,
            envRunning: Boolean,
            dshRunning: Boolean?,
            isEnvOnly: Boolean?,
        ): String = when {
            canOpenWeb -> displayUrl ?: "WebView"
            !envRunning -> "环境未运行"
            dshRunning == true -> "正在获取登录地址…"
            isEnvOnly == true -> "DSH 未启动：点「启动 DSH」"
            else -> "DSH 未在运行：点「启动 DSH」"
        }

        /**
         * DSH 面板里"打不开"时的一句人话（纯函数）。
         *
         * 同样来自那张截图：仅环境方式下点「打开 DSH」，面板报的是
         * 「环境已在运行，但登录地址还没写出来（run/dsh.url 尚未就绪）」——
         * 那是"等一等就好"的口气，而**这次根本没打算起 DSH**，等多久都不会有。
         */
        fun dshPaneNotOpenText(
            envRunning: Boolean,
            dshRunning: Boolean?,
            isEnvOnly: Boolean?,
            envStateLabel: String,
            lastError: String?,
        ): String = when {
            !envRunning -> buildString {
                append("环境当前不是运行状态（").append(envStateLabel).append("），无法打开 DSH 界面。")
                if (!lastError.isNullOrBlank()) append("\n").append(lastError)
            }
            dshRunning != true && isEnvOnly == true ->
                "这次是「仅启动环境」：环境在跑，但 DSH 没启动，所以没有登录地址。\n" +
                    "回启动页点「启动 DSH」把它接上，再回来打开。"
            else ->
                "环境已在运行，但登录地址还没写出来（run/dsh.url 尚未就绪）。稍后点「重新登录」重试。"
        }
    }
}
