package io.github.sunsetrne.sunsetlinux.core

import android.content.Context
import java.io.File

/**
 * 免 root（proot）模式的**就绪度体检**与引导步骤。
 *
 * ## 为什么需要它（真机 2026-09-16 反馈）
 *
 * 用户的 doctor 输出是：
 * ```
 * # linuxctl doctor · 模式 PROOT · /data/user/0/…/files/sunsetlinux
 * ✗ 没有找到 linuxctl：…/files/sunsetlinux/bin/linuxctl
 *   请先在侧边栏「重新部署 / 首启引导」里完成部署。
 * ```
 * 而当时的「首启引导」里，proot 分支**只有文字**（"到「更新 → 本机包 → 离线安装」点一下"），
 * 没有任何能铺 `bin/linuxctl` 的动作 —— 于是那句话把人指向死胡同：
 *   · `bin/` 下这套宿主脚本得由 **App 内置资产**铺（[ProotRuntime]），界面上却没有按钮；
 *   · `rootfs` 只有"用户自己准备一个 ubuntu-base tarball"这条手工路，而频道/离线包给的
 *     base 是 **erofs 层**，proot 的 linuxctl 原先又只认 tar → 那条路也是死的。
 *
 * 所以这里做两件事：
 *   1. **一次查清** proot 模式到底缺什么（宿主脚本 / proot 运行时 / 一棵 rootfs / 可用的层或种子）；
 *   2. 给出**有序的下一步**（[plan]），每步都带"点哪个按钮 / 等价命令"。
 *
 * 纯文件系统检查：不需要 su、不联网，因此免 root 设备上也能跑（proot 模式的前提就是没有 root）。
 */
object ProotSetup {

    /** 环境根下与 proot 启动有关的相对路径（都相对 `$LINUX_HOME`）。 */
    private const val BIN_LINUXCTL = "bin/linuxctl"
    private const val PROOT_LAUNCH = "proot/proot-launch.sh"
    private const val PROOT_BIN = "proot/proot"
    private const val ROOTFS_SH = "rootfs/bin/sh"
    private const val ROOTFS_ENV = "rootfs/usr/bin/env"
    private const val LAYERS_DIR = "layers"

    /** 引导步骤（顺序即依赖顺序）。 */
    enum class Step { SCRIPTS, RUNTIME, ENVIRONMENT, READY }

    data class StepState(
        val step: Step,
        val title: String,
        val detail: String,
        val done: Boolean,
        /** 没做完时建议的动作文案；做完或无需动作时为 null。 */
        val actionLabel: String? = null,
    )

    data class Readiness(
        /** `bin/linuxctl`（或 `bin/linuxctl.sh`）已就位且可执行。 */
        val scriptsReady: Boolean,
        /** proot 可执行体（随包 bundle 的 `proot/proot-launch.sh` 或 `proot/proot`）。 */
        val prootBinary: String?,
        /** `rootfs/` 里已经有 /bin/sh（proot 模式的可写根）。 */
        val rootfsReady: Boolean,
        /** `layers/` 里的层文件名（erofs 或 tar）。 */
        val layers: List<String>,
        /** `seeds/`、`cache/` 里的 tar 种子文件名。 */
        val seeds: List<String>,
        /** APK 内嵌的离线包（null = 这个组合没带）。 */
        val embeddedBundle: OfflineBundle.Bundle?,
    ) {
        val hasProotBinary: Boolean get() = prootBinary != null

        /** 有没有 base 层（能当根文件系统用）。 */
        val hasBaseLayer: Boolean get() = layers.any { it.startsWith("base") }

        /** 铺 rootfs 的"料"齐不齐：已有的 rootfs、base 层、tar 种子，三者任一即可。 */
        val hasRootSource: Boolean get() = rootfsReady || hasBaseLayer || seeds.isNotEmpty()

        /** 三件必需件都齐 = 可以启动了。 */
        val complete: Boolean get() = scriptsReady && hasProotBinary && rootfsReady

        /** 缺什么（给人看的一句话；齐全时为空串）。 */
        val missingLabel: String
            get() = buildList {
                if (!scriptsReady) add("宿主脚本（bin/linuxctl）")
                if (!hasProotBinary) add("proot 运行时")
                if (!rootfsReady) add("rootfs")
            }.joinToString("、")
    }

    /**
     * 查一遍现状。**只读**：不建目录、不写文件、不调 su。
     */
    fun inspect(context: Context): Readiness {
        val home = File(DshPaths.prootLinuxHome(context))

        // 判据是"脚本在不在"，不看执行位：App 调它时一律经 `/system/bin/sh`
        // （[DshPaths.hostCtlCommand]）；拿执行位当就绪在部分机器上会把已铺好的判成没铺。
        val scripts = DshPaths.linuxctlCandidates(context, EnvMode.PROOT)
            .any { File(it).isFile } ||
            File(home, BIN_LINUXCTL).isFile

        val proot = listOf(PROOT_LAUNCH, PROOT_BIN, "bin/proot-launch.sh")
            .map { File(home, it) }
            .firstOrNull { it.isFile }
            ?.absolutePath

        val rootfs = File(home, ROOTFS_SH).isFile || File(home, ROOTFS_ENV).isFile

        val layers = File(home, LAYERS_DIR).listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".erofs") || it.name.contains(".tar")) }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

        val seeds = listOf("seeds", "cache").flatMap { dir ->
            File(home, dir).listFiles()
                ?.filter { it.isFile && it.name.contains(".tar") }
                ?.map { it.name }
                .orEmpty()
                .toList()
        }.sorted()

        return Readiness(
            scriptsReady = scripts,
            prootBinary = proot,
            rootfsReady = rootfs,
            layers = layers,
            seeds = seeds,
            embeddedBundle = runCatching { OfflineBundle.readHeaderOnly(context) }.getOrNull(),
        )
    }

    /**
     * 有序的引导步骤（纯函数，单测直接测它）。
     *
     * 每一步都回答两件事：**做完了没有**、**没做完点哪里** —— 这正是原引导缺的东西。
     */
    fun plan(r: Readiness): List<StepState> = listOf(
        StepState(
            step = Step.SCRIPTS,
            title = "铺宿主脚本（bin/linuxctl）",
            detail = if (r.scriptsReady) {
                "已就位：\$LINUX_HOME/bin/linuxctl（App 与文档都按这条契约路径找它）"
            } else {
                "`bin/linuxctl` 等宿主脚本随 APK 内置（约 50 KB），不需要 root、不需要联网；" +
                    "没有它，doctor 只会说「没有找到 linuxctl」。"
            },
            done = r.scriptsReady,
            actionLabel = if (r.scriptsReady) null else "铺 proot 运行时（内置脚本）",
        ),
        StepState(
            step = Step.RUNTIME,
            title = "proot 可执行体",
            detail = if (r.hasProotBinary) {
                "已就位：${r.prootBinary}"
            } else {
                "proot 可执行体来自内嵌离线包的 proot 部件（解到 \$LINUX_HOME/proot/）；" +
                    "没有它就只能靠设备上已装的 proot/Termux。"
            },
            done = r.hasProotBinary,
            actionLabel = if (r.hasProotBinary) null else "安装内嵌离线包（含 proot 运行时）",
        ),
        StepState(
            step = Step.ENVIRONMENT,
            title = "铺 rootfs（Ubuntu 根文件系统）",
            detail = when {
                r.rootfsReady -> "已就位：\$LINUX_HOME/rootfs（能找到 /bin/sh）"
                r.hasBaseLayer -> "已有 base 层：${r.layers.filter { it.startsWith("base") }.joinToString("、")}；" +
                    "执行一次 provision 即可解成 rootfs（免 root 模式的层与 root 模式是同一份 erofs）。"
                r.seeds.isNotEmpty() -> "已有 tar 种子：${r.seeds.take(3).joinToString("、")}；执行 provision 即可解包。"
                r.embeddedBundle != null -> "本包内嵌离线包（变体 ${r.embeddedBundle.variant}）：先安装它，再执行 provision。"
                else -> "既没有 rootfs，也没有 base 层/种子；可从频道安装 base 层（erofs）或放一个 ubuntu-base tar。"
            },
            done = r.rootfsReady,
            actionLabel = when {
                r.rootfsReady -> null
                r.embeddedBundle != null -> "铺环境（内嵌离线包 + provision）"
                else -> "铺环境（执行 provision）"
            },
        ),
    )

    /** 引导完成后的一句话结论（给界面顶部的 Pill 用）。 */
    fun summary(r: Readiness): String = when {
        r.complete -> "免 root 环境已就绪"
        !r.scriptsReady -> "缺宿主脚本"
        !r.hasProotBinary -> "缺 proot 运行时"
        !r.rootfsReady && r.hasRootSource -> "可解包出 rootfs（执行 provision）"
        else -> "缺 rootfs"
    }
}
