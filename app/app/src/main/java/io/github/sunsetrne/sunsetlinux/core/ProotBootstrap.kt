package io.github.sunsetrne.sunsetlinux.core

/**
 * 免 root 版「打开即直接启用内置 Ubuntu 环境」的**判定**（纯函数）。
 *
 * ## 为什么要有这条自动路（用户要求）
 *
 * `docs/module-variants.md` §1.3 把免 root 版的入口写成一条直线：
 *
 * ```
 * 打开免 root 版
 *   → ① 铺 proot 宿主脚本 → ② 解内嵌离线包 → ③ linuxctl provision → ④ linuxctl start
 *   → ⑤ 落到「终端」页
 * ```
 *
 * 而在此之前，用户装完免 root 版打开 App 看到的是"首启引导：铺运行时 / 铺环境 / 启动"
 * 三个按钮 —— 每一步都要自己去点，点错顺序还会走进死胡同。现在默认由 App 自己走完，
 * 引导页退化成**手动入口**（侧边栏「重新部署 / 首启引导」）。
 *
 * ## 为什么判定要抽成纯函数
 *
 * 四个条件里任何一个判错，后果都是用户可见的：
 *   · 在 **root 版**误判成要跑 → 会去铺 proot 的东西（另一个环境，正是最坏的一种"看起来能用"）；
 *   · 没内嵌离线包还硬跑 → 必然以"缺层"失败，把用户从本来正确的引导上拽走；
 *   · 用户已经明确取消过还继续跑 → 用户的选择被无视；
 *   · 环境已就绪还重跑 → 会重新解一遍几百 MB 的离线包（"重活"，真机上要好几分钟）。
 *
 * 所以这里只做**纯决策**（[ProotSetup.plan] 同款的写法），真正的副作用都在
 * `ui/ProotBootstrapState.kt` 里。
 */
object ProotBootstrap {

    enum class Decision {
        /**
         * 不适用：保持既有引导（首启引导页 / 部署向导）。
         *
         * 三种情形都归到这里：Root 版（那是另一个 App 的事）、用户取消过、没内嵌离线包
         * （干净检出或 CI 未附产物时就是这样，硬跑只会失败）。
         */
        NOT_APPLICABLE,

        /** 环境已经就绪：**不做任何重活**，直接进外壳并停在终端页（要不要 start 交给既有的自动启动设置）。 */
        ALREADY_READY,

        /** 需要自动铺：宿主脚本 → 内嵌离线包 → provision → start。 */
        RUN,
    }

    /**
     * @param isRoot 本 APK 是不是 Root 版（`Edition.isRoot`）。
     * @param declined 用户是否**明确取消**过自动启用（`Prefs.prootBootstrapDeclined`）。
     * @param hasEmbeddedBundle 本 APK 是否真的内嵌了离线包（`OfflineBundle.readHeaderOnly` 读得到）。
     * @param ready 环境是否已就绪（`ProotSetup.inspect(...).complete`：宿主脚本 / proot 运行时 / rootfs 三者齐全）。
     */
    fun decide(
        isRoot: Boolean,
        declined: Boolean,
        hasEmbeddedBundle: Boolean,
        ready: Boolean,
    ): Decision = when {
        // Root 版与这条流水线无关：它的环境由 KernelSU 模块铺、且用的是真 chroot
        isRoot -> Decision.NOT_APPLICABLE
        // 用户点过"手动部署"：他的选择优先，别再自作主张
        declined -> Decision.NOT_APPLICABLE
        // 读不到内嵌包 = 这个 APK 没带环境（干净检出/CI 未附产物），自动跑必然以"缺层"失败
        !hasEmbeddedBundle -> Decision.NOT_APPLICABLE
        // 三件都齐：只差"启动"，那是既有 autoStartEnv 的事，不在这里做重活
        ready -> Decision.ALREADY_READY
        else -> Decision.RUN
    }
}
