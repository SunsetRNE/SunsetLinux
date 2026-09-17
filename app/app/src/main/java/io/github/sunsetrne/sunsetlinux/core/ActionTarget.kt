package io.github.sunsetrne.sunsetlinux.core

/**
 * 一次「启动 / 停止」动作**在设备上要达成的状态**。
 *
 * ## 为什么需要它（真机事故 · 2026-09-18）
 *
 * 用户截图：按「分步启动 → 仅启动环境」之后，**启动区五张卡片全灰、「启动 DSH」点不动**。
 * 根因不在按钮矩阵（矩阵是对的：env-only + DSH 没跑 ⇒ 「启动 DSH」该亮），而在 `busy`：
 *
 * - `runAction` 把 `busy` 置真，一直**等那条 `su -c '… linuxctl start --no-dsh'` 返回**才复位；
 * - 而那条命令当时**永不返回**（守护进程被前台 spawn 卡住，见 `runtime/root/start.sh`
 *   的 `spawn_daemon()` 注释与那里的设备实证）；
 * - 于是 `busy` 永远为真 ⇒ 五张卡片全置灰，且 `if (_ui.value.busy) return` 会把
 *   后续每一次点击**静默丢弃** —— 用户看到的就是"点了没反应"。
 *
 * 修法有两条腿，**必须都站住**：
 *   1. 设备侧：守护进程后台脱离，命令按时返回（那条修掉了大部分问题）；
 *   2. App 侧（这里）：**解锁的判据改成"设备状态已经到位"**，而不是"命令返回了"。
 *      状态每 4 秒轮询一次，所以哪怕以后再出现一条慢命令/卡命令，界面也会在状态
 *      变化被观测到之后自动解锁，而不会把整个启动区锁死。
 *
 * 判定做成纯函数（穷举单测见 `ActionTargetTest`），理由与 [StartControls] 相同：
 * 判定错一格的界面表现只是"按钮亮着/灰着"，真机上极难归因。
 *
 * ## 各档的判据
 *
 * | 目标 | 达成条件 |
 * |---|---|
 * | [ENV_RUNNING] | `state == running`（环境起来了；DSH 在不在不管） |
 * | [ENV_STOPPED] | `state == stopped` |
 * | [DSH_RUNNING] | `dsh.running == true` |
 * | [DSH_STOPPED] | **环境在跑** 且 `dsh.running == false`（环境都停了不算"停掉了 DSH"） |
 * | [NONE] | 从不达成：这类动作（清空可写层、doctor…）只能等命令自己返回 |
 */
enum class ActionTarget {
    NONE,
    ENV_RUNNING,
    ENV_STOPPED,
    DSH_RUNNING,
    DSH_STOPPED,
    ;

    /**
     * 这次观测到的状态是否已经达成动作目标。
     *
     * `status == null`（还没读到状态）**一律不算达成** —— 宁可多等一轮轮询，
     * 也不要因为"读不到状态"就提前解锁，那样用户会看到按钮在命令还没生效时就亮起来。
     */
    fun reached(status: DshStatus?): Boolean = when (this) {
        NONE -> false
        ENV_RUNNING -> status?.state == EnvState.RUNNING
        ENV_STOPPED -> status?.state == EnvState.STOPPED
        DSH_RUNNING -> status?.dshRunning == true
        // 环境必须还在跑：环境一起停了是两个动作的结果，不能拿来当"DSH 已停"的证据
        DSH_STOPPED -> status?.state == EnvState.RUNNING && status.dshRunning == false
    }

    companion object {
        /** 各动作对应的目标（调用点只有这一份映射，别在别处再写一遍 when）。 */
        fun of(action: Action): ActionTarget = when (action) {
            Action.START, Action.RESTART, Action.START_ENV_ONLY -> ENV_RUNNING
            Action.STOP -> ENV_STOPPED
            Action.DSH_START -> DSH_RUNNING
            Action.DSH_STOP -> DSH_STOPPED
            Action.RESET -> NONE
        }
    }

    /** 生命周期动作的种类（与 [of] 一一对应；纯枚举，便于单测穷举）。 */
    enum class Action { START, STOP, RESTART, START_ENV_ONLY, DSH_START, DSH_STOP, RESET }
}
