package io.github.sunsetrne.sunsetlinux.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * root 的状态（**不是布尔**）。
 *
 * 用户反馈："对 root 授权的检测和模块的检测" —— 之前只有 `suAvailable: true/false`，
 * 而"没有 su"、"su 在但被拒"、"授权弹窗超时"这三种的下一步**完全不同**，
 * 一律显示"su 不可用"等于什么都没说。
 */
enum class RootState {
    GRANTED,
    DENIED,
    NO_SU,
    TIMEOUT,
    UNKNOWN,
}

/**
 * 一次 root 探测的结果：状态 + 给用户看的一句话 + 建议的下一步。
 *
 * [RootProbe.from] 是**纯函数**（拿 [CtlResult] 映射），所以可以直接单测，
 * 不需要真的去弹授权框。
 */
data class RootProbe(
    val state: RootState,
    val detail: String,
) {
    val granted: Boolean get() = state == RootState.GRANTED

    /** 给用户看的短标签（环境检测里那一行）。 */
    val label: String
        get() = when (state) {
            RootState.GRANTED -> "root 已授权"
            RootState.DENIED -> "root 被拒绝"
            RootState.NO_SU -> "没有 root"
            RootState.TIMEOUT -> "root 授权超时"
            RootState.UNKNOWN -> "root 状态未知"
        }

    /** 下一步该做什么（能直说就直说，别让用户猜）。 */
    val hint: String?
        get() = when (state) {
            RootState.GRANTED -> null
            RootState.DENIED -> "去 KernelSU/Magisk 管理器里给「SunsetLinux」授权 root，然后点「重新检测」"
            RootState.NO_SU -> "本机没有 su：先刷 KernelSU（并把模块装上），或改用非 root（proot）模式"
            RootState.TIMEOUT -> "刚才的授权请求超时了：屏幕上看一眼有没有 KernelSU 的弹窗，允许后再点「重新检测」"
            RootState.UNKNOWN -> "把「导出诊断包」发给维护者（${detail}）"
        }

    companion object {
        fun from(r: CtlResult): RootProbe = when {
            r.fail == FailKind.START_FAILED ->
                RootProbe(RootState.NO_SU, r.error ?: "找不到 su")
            r.fail == FailKind.TIMEOUT ->
                RootProbe(RootState.TIMEOUT, r.error ?: "su 调用超时")
            r.ok && r.stdout.contains("uid=0") ->
                RootProbe(RootState.GRANTED, "su 可用（uid=0）")
            r.exitCode != 0 ->
                RootProbe(RootState.DENIED, r.message)
            else ->
                RootProbe(RootState.UNKNOWN, r.message)
        }
    }
}

/**
 * KernelSU 模块的状态。
 *
 * 为什么 App 要自己读：模块装没装、版本多少、是不是"装了没重启"、有没有被停用 ——
 * 这四件事决定了"现在到底能不能建层/能不能开机自启"。
 * 以前 App 一个都不知道，只能靠用户报"我装了呀"。
 *
 * ⚠️ `/data/adb/...` **App 进程读不到**（不同 SELinux 域），所以只能经 `su` 读；
 * 解析部分 [ModuleStatus.parse] 是纯函数，可以直接单测。
 */
data class ModuleStatus(
    /**
     * **这次到底读到了没有**。
     *
     * 真机踩过：用户明明已经装好模块并重启，App 却显示"模块未装" —— 因为探针要经 `su`，
     * 而那次 `su` 没拿到（授权被收回/超时），旧代码把"读不到"直接当成"没装"报了出去。
     * 从此：读不到就是 [readable] = false（界面说"状态未知 + 先去授权"），
     * **绝不把"不知道"说成"没装"** —— 那会把用户带去卸载重装。
     */
    val readable: Boolean,
    /** `module.prop` 有内容 = 模块已安装。 */
    val installed: Boolean,
    /** `module.prop` 里的 version（去掉开头的 v，例：`1.0.9`）。 */
    val version: String?,
    /** `disable` 文件存在 = 用户在 KernelSU 里停用了它。 */
    val disabled: Boolean,
    /** `modules_update/sunsetlinux` 存在 = 装了但**还没重启**（新版本尚未生效）。 */
    val pendingReboot: Boolean,
    /** 模块里带 `bin/device-provision.sh`（设备侧构建入口）。 */
    val hasProvisionScript: Boolean,
) {
    val label: String
        get() = when {
            !readable -> "模块状态未知"
            !installed -> "模块未装"
            disabled -> "模块已停用"
            pendingReboot -> "模块 ${version ?: "?"}（装了，待重启生效）"
            else -> "模块 ${version ?: "?"}（已启用）"
        }

    val hint: String?
        get() = when {
            !readable -> "读不到模块状态（经 su 读取失败）：先确认 KernelSU 已给本应用授权 root，再点「重新检测」"
            !installed -> "在 KernelSU 管理器里安装模块 zip，装完**重启一次**（下载页：/stable/）"
            disabled -> "KernelSU 里模块被停用了：启用它，然后重启"
            pendingReboot -> "模块已经装好，**重启一次**才会生效（开机还会自动建层/自启）"
            version == null -> "模块版本读不出来：重装模块 zip 看看"
            else -> null
        }

    companion object {
        const val MARK_PROP = "###PROP"
        const val MARK_DISABLE = "###DISABLE"
        const val MARK_PENDING = "###PENDING"
        const val MARK_PROV = "###PROV"
        const val MARK_END = "###END"

        /** 让 `su` 一次性把四件事读回来的脚本（分开读要起四次 su，慢且容易弹授权）。 */
        val PROBE_SCRIPT: String = """
            M=/data/adb/modules/sunsetlinux
            U=/data/adb/modules_update/sunsetlinux
            echo $MARK_PROP
            [ -f "${'$'}M/module.prop" ] && cat "${'$'}M/module.prop"
            echo $MARK_DISABLE
            [ -f "${'$'}M/disable" ] && echo 1 || echo 0
            echo $MARK_PENDING
            [ -d "${'$'}U" ] && echo 1 || echo 0
            echo $MARK_PROV
            [ -f "${'$'}M/bin/device-provision.sh" ] && echo 1 || echo 0
            echo $MARK_END
        """.trimIndent()

        /** 从 `module.prop` 的文本里取 `version=`（去掉开头的 `v`）。 */
        fun versionOf(prop: String): String? = prop.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("version=") }
            ?.removePrefix("version=")
            ?.trim()
            ?.removePrefix("v")
            ?.takeIf { it.isNotEmpty() }

        /**
         * 解析 [PROBE_SCRIPT] 的输出。**容错优先**：任何标记缺失都退化成"不知道"，
         * 绝不因为读到半截输出就断言"模块没装"（那会把用户带去卸载重装）。
         */
        fun parse(raw: String): ModuleStatus {
            if (raw.isBlank()) {
                // 探针没吐东西 = 没读到（不是"没装"）
                return ModuleStatus(readable = false, installed = false, version = null,
                    disabled = false, pendingReboot = false, hasProvisionScript = false)
            }
            val sections = mutableMapOf<String, StringBuilder>()
            var current: String? = null
            for (line in raw.lineSequence()) {
                if (line.startsWith("###")) {
                    current = line.trim()
                    sections.getOrPut(current) { StringBuilder() }
                    continue
                }
                current?.let { sections[it]?.appendLine(line) }
            }
            val prop = sections[MARK_PROP]?.toString()?.trim().orEmpty()
            return ModuleStatus(
                // 标记齐了（脚本真跑完了）才算"读到了"
                readable = MARK_END in sections || MARK_PROP in sections,
                // 标记**总是**会被脚本打印出来，所以判"装没装"要看 module.prop 有没有内容
                installed = prop.isNotEmpty(),
                version = versionOf(prop),
                disabled = sections[MARK_DISABLE]?.toString()?.trim() == "1",
                pendingReboot = sections[MARK_PENDING]?.toString()?.trim() == "1",
                hasProvisionScript = sections[MARK_PROV]?.toString()?.trim() == "1",
            )
        }
    }
}

/**
 * "这台设备现在有什么"的唯一读取处：root 状态 + 模块状态。
 *
 * 首页的「环境检测」、部署向导、诊断页都走这里，避免各界面各读一遍（每人一次 su 调用）。
 */
object DeviceStatus {

    @Volatile
    private var cachedRoot: RootProbe? = null

    @Volatile
    private var cachedAt = 0L

    private const val TTL_MS = 60_000L

    suspend fun root(force: Boolean = false): RootProbe = withContext(Dispatchers.IO) {
        val c = cachedRoot
        if (!force && c != null && System.currentTimeMillis() - cachedAt < TTL_MS) return@withContext c
        val probe = SuShell.probeState()
        cachedRoot = probe
        cachedAt = System.currentTimeMillis()
        probe
    }

    /**
     * 读模块状态。**读不到就说读不到**（[ModuleStatus.readable] = false），
     * 绝不把"没拿到 root / su 超时"说成"模块未装"—— 真机上就是这么误报的。
     */
    suspend fun module(force: Boolean = false): ModuleStatus = withContext(Dispatchers.IO) {
        val r = root(force)
        if (!r.granted) {
            return@withContext ModuleStatus(
                readable = false, installed = false, version = null,
                disabled = false, pendingReboot = false, hasProvisionScript = false,
            )
        }
        val res = SuShell.exec(ModuleStatus.PROBE_SCRIPT, 8000L)
        if (res.stdout.isBlank()) {
            return@withContext ModuleStatus(
                readable = false, installed = false, version = null,
                disabled = false, pendingReboot = false, hasProvisionScript = false,
            )
        }
        ModuleStatus.parse(res.stdout)
    }

    fun invalidate() {
        cachedRoot = null
        cachedAt = 0L
    }
}
