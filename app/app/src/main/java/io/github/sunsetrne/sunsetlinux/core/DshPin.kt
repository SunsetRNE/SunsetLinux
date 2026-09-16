package io.github.sunsetrne.sunsetlinux.core

import android.content.Context

/**
 * 「内置 DSH」与「运行时 DSH」的**版本对账**。
 *
 * ## 为什么要专门做这件事（用户 2026-09-16）
 *
 * 完整离线版把某个 DSH 版本**冻在 APK 里**（离线包的 dsh 部件），而运行时的 DSH 会被
 * 频道/App 更新 —— 两个版本随时可能不一致。用户的原话是"避免更新导致崩了"。要防的不是
 * "版本不同"（那本身是正常的：更新就是让它不同），而是**更新之后不知道自己在跑哪个版本、
 * 也回不去**。
 *
 * 所以这里只做三件事：
 *  1. **看得见**：内置版本（`dsh_version`，随 APK 冻结）与运行时版本（`status.layers.dsh.version`，
 *     真正在跑的那个）都显示出来，并给一句人话结论；
 *  2. **回得去**：装了离线包之后 `layers/dsh-<版本>.erofs` 就在本机，而 `linuxctl update`
 *     **不删旧文件**（回滚语义见 `runtime/root/linuxctl.sh cmd_rollback`），所以
 *     "回滚到内置版本"永远是条可用退路；
 *  3. **不静默**：版本对不上时明确说出来 + 给出该点哪里，而不是等它崩了再排查。
 *
 * ## 有意不做的事
 *
 * 不替用户"自动选一个版本"、也不在版本不同时拒绝启动 —— 那会把"更新"变成"必须重装 APK"，
 * 而运行时更新的全部意义就是不必重装。
 */
object DshPin {

    /** 对账结论。 */
    enum class Verdict {
        /** 这个包没内置 DSH（最小版/Ubuntu 版），或两边都还没有 —— 只看运行时。 */
        NONE,

        /** 内置与运行时一致。 */
        SAME,

        /** 运行时比重置的新（正常升级路径：从频道更新过 DSH）。 */
        RUNTIME_NEWER,

        /** 运行时比内置的旧（用户装过旧层 / 回滚过）—— 能一键回到内置版。 */
        EMBEDDED_NEWER,

        /** 有内置版，但运行时还没有 dsh 层（还没部署/还没装层）。 */
        RUNTIME_MISSING,

        /** 版本号解析不出来（形如 `unknown`）—— 如实说"判不了"。 */
        UNKNOWN,
    }

    data class State(
        /** 内嵌离线包里的 DSH 版本（APK 冻结的那份）；null = 这个包不带 DSH。 */
        val embedded: String?,
        /** 运行时真正生效的 DSH 版本（`status.layers.dsh.version`）。 */
        val runtime: String?,
        val verdict: Verdict,
    ) {
        /** 给 Pill / 一行字用。 */
        val label: String
            get() = when (verdict) {
                Verdict.NONE -> "未涉及"
                Verdict.SAME -> "一致（${runtime ?: "-"}）"
                Verdict.RUNTIME_NEWER -> "运行时更新过"
                Verdict.EMBEDDED_NEWER -> "运行时比内置旧"
                Verdict.RUNTIME_MISSING -> "运行时还没装"
                Verdict.UNKNOWN -> "判不了"
            }

        /** 给人看的一句话（含两个版本号）。 */
        val detail: String
            get() = when (verdict) {
                Verdict.NONE ->
                    if (runtime.isNullOrBlank()) "本包没内置 DSH，运行时也还没有 DSH 层 —— 用「更新」页从频道装"
                    else "本包没内置 DSH；运行时用的是 $runtime（来自频道）"
                Verdict.SAME -> "内置与运行时都是 $runtime（内置那份随 APK 冻结，运行时这份可以被更新）"
                Verdict.RUNTIME_NEWER ->
                    "运行时 $runtime 比 APK 内置的 $embedded 新（正常的更新路径）。" +
                        "出问题可以「回滚到内置版本」，回到随包冻结的那份。"
                Verdict.EMBEDDED_NEWER ->
                    "运行时 $embedded 之前的旧版在跑：APK 内置的是 $embedded，而运行时是 $runtime —— " +
                        "建议回滚到内置版本（一键），或从频道更新到最新。"
                Verdict.RUNTIME_MISSING ->
                    "APK 内置的是 $embedded，但运行时还没有 DSH 层：先部署/安装（「更新」页或首启引导）。"
                Verdict.UNKNOWN -> "版本号读不出来（内置 ${embedded ?: "-"}／运行时 ${runtime ?: "-"}），先去「更新」页刷新一次"
            }

        /** 能不能一键回到内置版本（要同时满足：有内置版、且运行时不是它）。 */
        val canRollbackToEmbedded: Boolean
            get() = !embedded.isNullOrBlank() &&
                (verdict == Verdict.RUNTIME_NEWER || verdict == Verdict.EMBEDDED_NEWER)
    }

    /** 版本号归一（去掉前导 v/V，去空白）。 */
    internal fun norm(v: String?): String? = v?.trim()?.removePrefix("v")?.removePrefix("V")?.ifEmpty { null }

    /**
     * 看起来像版本号吗（至少含一个数字）。
     *
     * 为什么要这一层：`compareModuleVersion` 把读不出来的字符串按全 0 段处理，于是
     * `"0.1.5-rc.1" > "unknown"` 会静默成立 —— 界面就会把"读不出内置版本"说成
     * "运行时更新过"，还给出一个指向不存在的版本的"回滚"按钮。宁可说"判不了"。
     */
    internal fun looksLikeVersion(v: String?): Boolean = v != null && v.any { it.isDigit() }

    /**
     * 纯函数：两个版本号对账（单测直接测它）。
     *
     * 比较用 [compareModuleVersion]（逐段按数字，`0.1.5-rc.2 > 0.1.5-rc.1`；纯字符串比会得出相反的结论）。
     */
    fun reconcile(embeddedRaw: String?, runtimeRaw: String?): State {
        val embedded = norm(embeddedRaw)
        val runtime = norm(runtimeRaw)
        val verdict = when {
            embedded == null && runtime == null -> Verdict.NONE
            embedded == null -> Verdict.NONE          // 只看运行时（本包没内置 DSH）
            runtime == null -> Verdict.RUNTIME_MISSING
            !looksLikeVersion(embedded) || !looksLikeVersion(runtime) -> Verdict.UNKNOWN
            embedded == runtime -> Verdict.SAME
            runCatching { compareModuleVersion(runtime, embedded) }.getOrNull() == null -> Verdict.UNKNOWN
            else -> when {
                compareModuleVersion(runtime, embedded) > 0 -> Verdict.RUNTIME_NEWER
                compareModuleVersion(runtime, embedded) < 0 -> Verdict.EMBEDDED_NEWER
                else -> Verdict.SAME
            }
        }
        return State(embedded = embedded, runtime = runtime, verdict = verdict)
    }

    /**
     * 组装：内置版本来自 APK 内嵌离线包的头（`OfflineBundle.readHeaderOnly` 的 `dshVersion`），
     * 运行时版本来自 `status`。**只读**，不写任何东西。
     */
    fun of(context: Context, runtimeVersion: String?): State {
        val embedded = runCatching { OfflineBundle.readHeaderOnly(context)?.dshVersion }.getOrNull()
        // dsh 版本号形如 "0.1.5-rc.1"；离线包头里给的就是不带 v 的版本
        val normalized = if (embedded.isNullOrBlank() || embedded.equals("null", true)) null else embedded
        return reconcile(normalized, runtimeVersion)
    }
}
