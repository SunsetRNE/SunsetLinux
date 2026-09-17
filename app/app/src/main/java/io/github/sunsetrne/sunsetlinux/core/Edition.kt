package io.github.sunsetrne.sunsetlinux.core

import io.github.sunsetrne.sunsetlinux.BuildConfig

/**
 * 这个 APK 是哪个 **edition**（两个可共存的 App：Root 版 / 免 root 版）。
 *
 * ## 为什么要有这个文件
 *
 * 用户 2026-09-16 拍板把 App 拆成两个："一个纯 root 流程，一个纯免 root 流程"。
 * 拆开之后，"模式"不再是一个**运行时选择**，而是**这个 App 的身份**：
 *   · Root 版（`io.github.sunsetrne.sunsetlinux.root`）：只走真 chroot 那条路；
 *   · 免 root 版（`io.github.sunsetrne.sunsetlinux.proot`）：只走 proot/proroot 那条路。
 *
 * 好处不只是"界面更干净"：两版的**准备流程完全不同**（Root：装 KernelSU 模块 → 建层；
 * 免 root：铺内置脚本 → 铺 rootfs），合成一个 App 时那套"先选模式再分叉"的引导
 * 一直在误导用户（第 34 条修的就是它）。拆开以后引导只有一条直线。
 *
 * ## 为什么读 BuildConfig 而不是读 Prefs
 *
 * 身份是**构建期**决定的（包名都不同），运行期改不了；读 Prefs 反而会出现
 * "用户上次选了另一个模式，于是这个 App 去操作另一个环境"的荒谬状态。
 */
object Edition {

    /** `root` / `proot`。 */
    val id: String = BuildConfig.EDITION

    /** 界面上的完整名字（"SunsetLinux Root" / "SunsetLinux 免root"）。 */
    val label: String = BuildConfig.EDITION_LABEL

    /** 短标签（"Root 版" / "免 root 版"）—— 塞进 Pill 用。 */
    val labelShort: String = BuildConfig.EDITION_LABEL_SHORT

    /** 本 edition **锁定**的模式（`root` / `proot`，与 `EnvMode` 对齐）。 */
    val lockedMode: EnvMode = if (BuildConfig.EDITION_MODE == "proot") EnvMode.PROOT else EnvMode.ROOT

    val isRoot: Boolean get() = lockedMode == EnvMode.ROOT

    /** 本 APK 的包名（"关于"页显示；也用于告诉用户"两个版本可以同时装"）。 */
    val applicationId: String = BuildConfig.APP_ID

    /**
     * 这个 edition 的**前置条件**（引导与诊断都按它分流，不再问"用户选了哪个模式"）：
     *   · root：需要 su + KernelSU 模块（模块把 `bin/linuxctl` 铺到 `/data/sunsetlinux`）；
     *   · proot：需要 App 内置的宿主脚本 + 一棵 from base 层/种子的 rootfs。
     */
    val needsKernelSuModule: Boolean get() = isRoot

    /** 免 root 版不需要 su（也不该去申请）—— 界面上不要提示"去授权 root"。 */
    val needsSu: Boolean get() = isRoot

    // ── 界面切割（判定本身在 [EditionPolicy]，这里只是给调用点一个短名字）──────────
    //
    // 为什么不让各面板直接写 `Edition.isRoot`：docs/module-variants.md §一 要求
    // "免 root 版不出现任何模块痕迹、root 版不出现 proot 设置"，而每一处各写一遍判断
    // 就会漏（真机已经漏过：侧边栏说明、关于页的"本版不使用模块"、进 App 就探模块）。
    // 统一走这三个属性，源码级契约测试还能钉住"没人绕过去"。

    /** 要不要出现 KernelSU 模块相关的内容（模块版本行 / 模块更新卡片 / 刷入按钮 / 模块探测）。 */
    val showsModuleUi: Boolean get() = EditionPolicy.showsKernelSuModuleUi(isRoot)

    /** 要不要出现 proot / proroot 相关的设置项与说明（免 root 运行时选择、attribution）。 */
    val showsRootlessRuntimeUi: Boolean get() = EditionPolicy.showsRootlessRuntimeUi(isRoot)

    /** 要不要出现层模式（loop / dir）选择 —— 那是 root 模式独有的启动方式。 */
    val showsLayerModeUi: Boolean get() = EditionPolicy.showsLayerModeUi(isRoot)
}
