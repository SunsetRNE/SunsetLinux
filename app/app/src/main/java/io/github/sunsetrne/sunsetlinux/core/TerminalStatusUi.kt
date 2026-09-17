package io.github.sunsetrne.sunsetlinux.core

/**
 * 终端页里**随 mode / 连接态自动切换**的那几行文案（纯函数，JVM 单测可穷举）。
 *
 * ## 为什么必须抽出来、而不是写在 Compose 里
 *
 * 旧版空态那句静态说明是：「命令在环境内执行：root 模式会 chroot 进 rootfs，proot 模式
 * 进 proot 的 bash。」—— 它**同时讲两个 edition 的事**，而用户装的是其中一个：
 *   · 一半是噪音（他这一版根本不会发生那些事）；
 *   · 更糟的是它没说清"现在到底以什么身份在跑"—— 尤其免 root 版，"proot 的 bash"太含糊，
 *     用户会以为里面那个 `root` 有真实权限（**并没有**：proot 只是伪造 uid，没有真实
 *     capabilities，改不了真实系统）。
 *
 * 所以：**权限行的内容由 [EnvMode] 唯一决定**，两个 edition 各自只说自己的事实。
 * 做成纯函数是因为这类"跟着状态变"的文案最容易在改代码时漏掉一个分支（比如只改了
 * root 版那句），单测能把每一档都钉住（见 `TerminalStatusUiTest`）。
 */
object TerminalStatusUi {

    /** 未连接时补的那句**可操作**提示；连上之后必须消失（否则像句废话）。 */
    const val NOT_CONNECTED_HINT = "点上方「连接」开始"

    /**
     * 顶部副标题（外壳顶栏里那一行）。
     *
     * 为什么终端页不直接显示 [Diagnoser] 的 `stage`：那句话是**全页通用**的状态短语
     * （例如「失败（未知，建议跑一键诊断）」），挤在顶栏窄条里会被裁成
     * 「失败（未知，建议跑一键诊…」，用户看不出意思。终端页有自己的前置条件
     * （环境必须在跑），这里就给一句**自成一句、不依赖上下文**的说明。
     *
     * ⚠️ 每句都必须**短**（顶栏给副标题的宽度只有十几个汉字）：`*Test` 里钉住了长度上限 ——
     * 写长了就会再次出现"半句话 + 省略号"，那正是这次要修的问题（写法上别把它当普通文案）。
     */
    fun subtitle(envRunning: Boolean, mode: EnvMode): String = when {
        !envRunning -> "环境未运行：先到「启动」页"
        mode == EnvMode.ROOT -> "环境在跑：以 root 身份进入"
        else -> "环境在跑：进 proot 环境"
    }

    /**
     * 顶栏状态条上的**真实身份**。
     *
     * ## 为什么未连接时返回 null（而不是给个"未知"或默认值）
     *
     * 身份是**会话建立之后**才有的事实：ROOT 版的会话是 `su -c linuxctl attach`
     * → `nsenter` 进环境 ns → `chroot` 进 rootfs，能连上就意味着那条链路成立，
     * 里面就是真 uid 0；而 PROOT 版的 `root` 是 proot 伪造的（没有真实 capabilities）。
     * 没连上时我们对环境内一无所知 —— 这时写任何身份都是在编造。
     *
     * 这也是本文件与 [TerminalStatusUi.privilegeLine] 的分工：那一行讲的是"这一版会怎么
     * 执行"（与连接态无关的架构事实），这一行讲的是"现在这一次是不是这样"。
     */
    fun identityLabel(connected: Boolean, mode: EnvMode): String? {
        if (!connected) return null
        return when (mode) {
            EnvMode.ROOT -> "uid 0（root）"
            EnvMode.PROOT -> "伪 root（无真实 capabilities）"
        }
    }

    /**
     * 权限行：命令在环境内**以什么身份、经哪条路**执行。
     *
     * 两个 edition 各自只说自己的事实（这是批注里"文案有问题"的正解）：
     *   · Root 版：真 root + nsenter 进环境 ns + chroot 到 `/data/sunsetlinux/rootfs`；
     *   · 免 root 版：proot 伪造的 root（**没有真实权限**）+ 环境根在 App 私有目录
     *     （卸载 App 即清除 —— 这是与 root 版最要紧的能力差异）。
     */
    fun privilegeLine(mode: EnvMode): String = when (mode) {
        EnvMode.ROOT ->
            "以 root 身份在环境内执行：nsenter 进环境 ns → chroot 到 /data/sunsetlinux/rootfs"
        EnvMode.PROOT ->
            "以 proot 伪造的 root 身份执行（没有真实权限）；环境根 = App 私有目录"
    }

    /**
     * 引擎行：这一版终端跑在哪个引擎上。
     *
     * 降级分支（[ptyAvailable] = false）**必须完整保留**：全屏程序与 Ctrl-C 不可用是
     * 用户必须知道的能力差异，不能为了"紧凑"把它压没。就绪分支才压缩成一行。
     */
    fun engineLine(ptyAvailable: Boolean, ptyUnavailableReason: String?): String =
        if (ptyAvailable) {
            "原生 PTY 就绪：Ctrl-C / Tab / 方向键、vim / htop 可用，窗口随控件"
        } else {
            "限制：原生 PTY 不可用（${ptyUnavailableReason ?: "未知原因"}）—— " +
                "当前是行缓冲降级：全屏程序与 Ctrl-C 不可用；输出按行刷新。"
        }
}
