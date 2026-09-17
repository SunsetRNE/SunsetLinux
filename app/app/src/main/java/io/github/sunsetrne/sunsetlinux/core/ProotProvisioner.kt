package io.github.sunsetrne.sunsetlinux.core

import android.content.Context
import io.github.sunsetrne.sunsetlinux.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 免 root（proot）**铺环境**的可复用流水线：宿主脚本 → 内嵌离线包 → `linuxctl provision`。
 *
 * ## 为什么要把这段从 WelcomeState 里抽出来
 *
 * 同一条流水线现在有**两个**调用方：
 *   1. 首启引导里的「铺环境」按钮（`ui/WelcomeState.provisionProot`，手动入口）；
 *   2. 免 root 版"打开即启用"（`ui/ProotBootstrapState`，自动入口，见 [ProotBootstrap]）。
 *
 * 两份实现迟早会漂移 —— 而它们漂移的代价很具体：手动入口修好了、自动入口还在用老顺序
 * （先 `provision` 后装包），自动流程就会稳定失败，用户看到"自动启用失败"却又不知道手动
 * 那条能成。所以顺序只有这一份。
 *
 * ## 三条刻意的设计（都是真机上踩出来的）
 *
 * 1. **proot 部件必须先于层**：非 root 模式下 `linuxctl` 是脚本，它自己要
 *    `$LINUX_HOME/proot/bin/proot`；[OfflineApplier.order] 保证这一点，这里只管调用。
 * 2. **只装"本机缺的"**：走 [OfflineApplier.plan]，而不是把内嵌包整个重装一遍。
 *    内嵌包解包动辄几百 MB；"重试一次"如果等于"重新解一遍 800 MB"，用户会以为卡死了
 *    （第一次实现就是无脑全装，`UpdatePane` 里的「只装缺的」按钮是同一个理由）。
 * 3. **进度按"阶段/百分比变化"节流**：`OfflineApplier` 的进度回调（每 64 KiB 一次）
 *    直接灌进日志面板会把真正有用的那几行冲走。
 *
 * ⚠️ **阻塞**（`OfflineApplier.apply` / `LinuxCtl.stream` 都是阻塞的），内部已整体丢到
 * IO 线程；[onLine] 因此是从 IO 线程回调的，调用方要么用线程安全的状态容器
 * （`MutableStateFlow`），要么自己切主线程。
 */
object ProotProvisioner {

    /** 流水线里的步骤。失败时用它告诉调用方"卡在哪一步"，而不是只说"失败了"。 */
    enum class Step(val label: String) {
        SCRIPTS("铺宿主脚本"),
        BUNDLE("安装内嵌离线包"),
        PROVISION("provision"),
    }

    data class Outcome(
        val ok: Boolean,
        /** 失败时是卡在哪一步（成功时 null）。 */
        val failedStep: Step? = null,
        /** **原始**错误（脚本报的 / 离线包安装器的 ✗ 行 / 命令的 message），界面直接展示，不做二次翻译。 */
        val error: String? = null,
        /** 本机是否内嵌了离线包（false 时只能走频道）。 */
        val hadBundle: Boolean = false,
        /** 内嵌包本次实际装下去的部件（界面用来播报"装了什么"）。 */
        val installed: List<String> = emptyList(),
        /** `provision` 的结果（没跑到就是 null）。 */
        val provision: CtlResult? = null,
    )

    /**
     * 跑一遍流水线（幂等、可重入、失败可重试）。
     *
     * @param bundle 内嵌离线包的头；null = 调用方没读到（这里**不会**自己去读，避免在
     *   不知情的情况下多读一次资产；[io.github.sunsetrne.sunsetlinux.ui.ProotBootstrapState]
     *   与引导页都先读好了再传进来）。
     * @param seedDir 传给 `linuxctl provision --seed`（手动入口里用户可指定离线种子目录）。
     */
    suspend fun run(
        context: Context,
        mode: EnvMode,
        bundle: OfflineBundle.Bundle?,
        seedDir: String? = null,
        onLine: (String) -> Unit,
    ): Outcome = withContext(Dispatchers.IO) {
        val home = DshPaths.linuxHome(context, mode)
        val ctl = LinuxCtl(context, mode)

        // ── ① 宿主脚本：`bin/linuxctl` 是后面所有动作的前提（它自己就是 linuxctl） ──
        if (ProotRuntime.isReady(context, home)) {
            onLine("· 宿主脚本已就位（bin/linuxctl）")
        } else {
            onLine("$ 先铺宿主脚本（provision 需要 bin/linuxctl）")
            val laid = runCatching { ProotRuntime.ensure(context, home) }
                .getOrElse { ProotRuntime.Result(false, emptyList(), it.message, false) }
            onLine(if (laid.contractReady) "✓ bin/linuxctl 已就位" else "✗ 宿主脚本未就位：${laid.error}")
            // 铺成了但少文件（内置包不完整）要说出来；没铺成的话下面会带着错误返回
            if (laid.contractReady) laid.error?.let { onLine("  注意：$it") }
            if (!laid.contractReady) {
                return@withContext Outcome(
                    ok = false,
                    failedStep = Step.SCRIPTS,
                    error = laid.error ?: "内置 proot 脚本没铺成（assets/${ProotRuntime.ASSET_DIR} 为空？）",
                )
            }
        }

        // ── ② 内嵌离线包（有就只补缺的；没有就如实说明走频道） ──
        var installed: List<String> = emptyList()
        if (bundle == null) {
            onLine("· 本包没有内嵌离线包（组合 ${BuildConfig.EMBED_VARIANT}）：" +
                "proot 运行时与 base 层需要从频道安装。")
        } else {
            // 先问一遍本机状态：层版本给 plan 用，proot 部件则看"铺没铺下去"。
            // ⚠️ 必须先 `exists()`：它会探测实际可用路径（有的部署只铺了 `bin/linuxctl.sh`），
            //    不探测就直接 status 会去执行不存在的契约路径，得到一个假的"命令失败"。
            //    状态读不到就当"本机什么都没有"（plan 会把缺的都算上，幂等无副作用）。
            val local = if (ctl.exists()) {
                val st = try {
                    ctl.status()
                } catch (_: Throwable) {
                    null
                }
                st?.layers?.associate { it.id to it.version }.orEmpty()
            } else {
                emptyMap()
            }
            // ⚠️ 这里查的是 **proot 可执行体**（`$LINUX_HOME/proot/*`），不是 `ProotRuntime.isReady`
            //    （它查的是 `bin/linuxctl` 那套宿主脚本）。两者在"脚本铺了、二进制还没铺"的中间态
            //    会分岔：用 isReady 顶替会让 plan 误判"proot 已就位"，于是跳过安装，
            //    接着 provision 因为找不到 proot 报一个看不懂的错 —— 而自动启用最常见的就是这个中间态。
            val prootReady = ProotSetup.inspect(context).hasProotBinary
            val only = OfflineApplier.plan(local, bundle, prootReady)
                .filter { it.needed }
                .map { it.key }

            if (only.isEmpty()) {
                // 幂等：重试时这一步必须立刻过，不能又去解一遍几百 MB
                onLine("· 内嵌离线包（变体 ${bundle.variant}）已全部就位，跳过安装")
            } else {
                onLine("$ 安装内嵌离线包（变体 ${bundle.variant}，${only.size}/${bundle.parts.size} 个部件：${only.joinToString("、")}）")
                var lastStage = ""
                var lastPercent = -1
                val outcome = OfflineApplier.apply(context, mode, bundle, only.toSet()) { stage, done, total ->
                    val percent = if (total > 0) ((done * 100) / total).toInt() else -1
                    if (stage != lastStage || percent != lastPercent) {
                        lastStage = stage
                        lastPercent = percent
                        onLine(if (total > 0) "  $stage  ${formatBytes(done)} / ${formatBytes(total)}" else "  $stage")
                    }
                }
                outcome.log.lineSequence().filter { it.isNotBlank() }.forEach { onLine(it.trimEnd()) }
                if (!outcome.ok) {
                    return@withContext Outcome(
                        ok = false,
                        failedStep = Step.BUNDLE,
                        // 原始错误 = 离线包安装器打出的那一行 ✗（含期望/实际的 sha256 之类）
                        error = outcome.log.lineSequence()
                            .map { it.trim() }
                            .lastOrNull { it.startsWith("✗") }
                            ?: "${outcome.failedStage?.label ?: "离线包安装"}失败（详见日志）",
                        hadBundle = true,
                        installed = outcome.installed,
                    )
                }
                installed = outcome.installed
            }
        }

        // ── ③ provision：把 base 层/种子解成 rootfs（linuxctl 1.0.17 起支持从 base 层解） ──
        if (!ctl.exists()) {
            onLine("✗ 仍然没有 bin/linuxctl，无法执行 provision。")
            return@withContext Outcome(
                ok = false,
                failedStep = Step.PROVISION,
                error = "找不到 bin/linuxctl（${ctl.ctlPath}）：这个 APK 可能没内嵌 proot 脚本",
                hadBundle = bundle != null,
                installed = installed,
            )
        }
        val args = buildList {
            add("provision")
            seedDir?.trim()?.takeIf { it.isNotEmpty() }?.let {
                add("--seed")
                add(it)
            }
        }
        onLine("$ linuxctl ${args.joinToString(" ")}")
        val provision = ctl.stream(args, onLine)
        onLine(if (provision.ok) "✓ provision 完成" else "✗ provision：${provision.message}")
        return@withContext Outcome(
            ok = provision.ok,
            failedStep = if (provision.ok) null else Step.PROVISION,
            error = if (provision.ok) null else provision.message,
            hadBundle = bundle != null,
            installed = installed,
            provision = provision,
        )
    }
}
