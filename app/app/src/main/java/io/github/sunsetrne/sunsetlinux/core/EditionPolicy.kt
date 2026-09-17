package io.github.sunsetrne.sunsetlinux.core

/**
 * 「哪个界面元素属于哪个 edition」的**纯函数**判定。
 *
 * ## 为什么单开一个文件（而不是各处直接写 `Edition.isRoot`）
 *
 * `docs/module-variants.md` §一 把"免 root 完全切割"写成了决策：免 root 版里
 * **不出现**任何 KernelSU 模块痕迹，反过来 root 版里**不出现** proot/proroot 的设置项。
 * 这条边界以前靠"每个界面各自记得判断"维护，于是真机上漏过好几次：
 *   · 侧边栏「重新部署 / 首启引导」的说明还写着"装模块"（免 root 版点进去一头雾水）；
 *   · 「关于」页在免 root 版里还印着"本版不使用 KernelSU 模块"——提了就等于留了痕迹；
 *   · `LauncherViewModel` 一进 App 就探模块状态（免 root 版纯属白跑，还可能弹授权框）；
 *   · 设置页在 Root 版里还提供"免 root 运行时（proroot / proot）"选择（root 永远用不到）。
 *
 * 收在这里的两个好处：
 *   1. **单测能同时覆盖两个方向** —— 本对象吃 `isRoot` 入参，而不是自己去读
 *      `BuildConfig`；单测是按变体跑的（`testProotFullDebugUnitTest` /
 *      `testRootFullDebugUnitTest`），读 BuildConfig 的话一个变体只能测到一半
 *      （见 `EditionPolicyTest`）；
 *   2. 源码级契约测试（见 `EditionSeparationTest`）可以钉住"UI 用的是这里的判定"，
 *      防止以后有人在某个面板里又手写一份 `if (Edition.isRoot)` 而漏掉另一半。
 */
object EditionPolicy {

    /**
     * KernelSU 模块相关的**任何**内容（模块版本行、模块更新卡片、刷入/导出/打开管理器
     * 按钮、模块状态探测）只在 Root 版出现。
     *
     * 免 root 设备的机器上根本没有 KernelSU：任何"先去装模块"的文案都是一条走不通的路
     * （真机反馈过），所以这里不是"隐藏按钮"而是"整块不存在"。
     */
    fun showsKernelSuModuleUi(isRoot: Boolean): Boolean = isRoot

    /**
     * proot / proroot 相关的设置项（免 root 运行时选择、proroot 的 attribution 说明）
     * 只在免 root 版出现 —— Root 版走真 chroot，`SUNSETLINUX_ROOTLESS` 那个环境变量
     * 递过去也没人读，留着只会让人以为"这里还能切运行时"。
     */
    fun showsRootlessRuntimeUi(isRoot: Boolean): Boolean = !isRoot

    /**
     * 层模式（loop / dir）是 **root 模式独有**的启动方式（`losetup` + erofs + upper.img
     * 对目录 overlay）；proot 模式是把 base 层直接解成一棵 rootfs，没有"层怎么挂"这回事。
     * 所以这一组选项也只对 Root 版有意义。
     */
    fun showsLayerModeUi(isRoot: Boolean): Boolean = isRoot
}
