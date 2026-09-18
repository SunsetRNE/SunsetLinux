package io.github.sunsetrne.sunsetlinux.ui

/**
 * DSH 网页的**覆盖整窗**策略（纯逻辑，可 JVM 单测 —— 见 `ui/DshFullscreenContractTest.kt`）。
 *
 * ## 为什么要有这么一条策略
 *
 * 原先 DSH 是外壳里的一个 tab，被**夹在顶栏与底栏之间**渲染（`AppShell` 里甚至专门写了
 * "底栏放内容下方（不遮挡网页）"）。真机上这意味着一块网页应用只占了屏幕中间一条：
 * 顶部一条壳的标题栏、底部一条胶囊底栏，网页自己还带一层头部 —— 三层 chrome 叠起来，
 * 真正可用高度所剩不多。用户提的正是这件事：
 *
 * > 原来的约束在应用内改为**覆盖应用全屏渲染显示**，允许**返回"壳"**。
 *
 * 所以现在的形状是：
 *  - **覆盖整窗**（[coversWindow]）：DSH 页画成顶层浮层，铺到屏幕四边（含系统栏之下），
 *    顶栏/底栏都不画；
 *  - **收起系统栏**（[hidesSystemBars]）：沉浸式，网页可用高度最大化；仍可用边缘滑动
 *    临时唤出（`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`）；
 *  - **回壳有明确出口**（[needsBackAffordance]）：悬浮「返回壳」键 + 系统返回手势
 *    （网页有历史时先回网页历史，见 `DshWebPane` 的 `PredictiveBackHandler`）。
 *
 * 这三种模式（root / proot）共用同一套渲染：壳是同一个 App，差异只在 Linux 侧脚本，
 * 所以这条策略不需要按 edition 分叉 —— 这也正是"两类模式都有这个设计问题"的答案。
 */
object DshFullscreen {

    /** 这个 tab 是否要**覆盖整个应用窗口**（而不是当普通页夹在顶栏/底栏之间）。 */
    fun coversWindow(tab: ShellTab): Boolean = tab == ShellTab.DSH

    /** 覆盖整窗时是否连系统栏一起收起来（沉浸式）。 */
    fun hidesSystemBars(tab: ShellTab): Boolean = coversWindow(tab)

    /**
     * 覆盖整窗时是否必须有一颗**常驻的返回控件**（悬浮「返回壳」键）。
     *
     * 为什么是"必须"：系统栏被收起来了，返回手势虽然还在，但**看不见**任何出口；
     * 网页应用（IDE/终端类）常常自己吃掉返回键。所以只要覆盖整窗，就必须有可见出口。
     */
    fun needsBackAffordance(tab: ShellTab): Boolean = coversWindow(tab)
}
