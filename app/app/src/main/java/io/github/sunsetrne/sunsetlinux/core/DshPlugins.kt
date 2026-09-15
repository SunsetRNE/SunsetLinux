package io.github.sunsetrne.sunsetlinux.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 第三方 DSH 插件管理（功能 C）。
 *
 * 命令行事实（实测 `dsh plugin --profile web --help`）：
 * **`dsh plugin` 是把参数透传给 profile 目录里的 pnpm** ——
 * `dsh plugin --profile web add <pkg>` 等价于在该 profile 下 `pnpm add <pkg>`，
 * `remove` / `list` 同理。所以 App 侧只需要：
 *
 * - 安装：`linuxctl exec -- dsh plugin --profile web add <pkg>`
 * - 卸载：`linuxctl exec -- dsh plugin --profile web remove <pkg>`
 * - 已装：读 profile 的 `package.json` 的 `dependencies`（`bundles` 也一起展示）
 *
 * 全部走 `linuxctl exec`，因此天然两模式通用（root 走 chroot，非 root 走 proot），
 * 而且写的是**可写层**。
 */
object DshPlugins {

    const val PROFILE = "web"

    /**
     * profile 目录的候选位置。`$DSH_HOME` 由运行时约定为 `/root/.dsh`（architecture §3.2），
     * `$HOME` 同样是 `/root`，这里三个都试一遍，避免把某一种布局写死。
     */
    private val packageJsonCandidates = listOf(
        "\${DSH_HOME:-/root/.dsh}/profiles/$PROFILE/package.json",
        "/root/.dsh/profiles/$PROFILE/package.json",
        "\$HOME/.dsh/profiles/$PROFILE/package.json",
    )

    data class Plugin(
        val name: String,
        val version: String?,
        /** 出现在 bundles 里（dsh 的插件清单字段） */
        val bundled: Boolean,
    )

    /** 输入校验：npm 包名、本地路径、URL 都放行，但拒绝空串与明显的命令注入。 */
    fun validateTarget(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return "请填写 npm 包名、本地路径或 URL"
        if (t.any { it.isWhitespace() }) return "不能包含空白字符"
        val bad = listOf(";", "|", "&", "`", "\$", ">", "<", "\n", "'", "\"", "\\")
        bad.firstOrNull { t.contains(it) }?.let { return "不允许出现字符 $it（命令注入风险）" }
        if (t.length > 200) return "太长了"
        return null
    }

    /** 安装：`dsh plugin --profile web add <target>`（流式输出，失败时把 pnpm 原文带出来）。 */
    suspend fun install(ctl: LinuxCtl, target: String, onLine: (String) -> Unit): CtlResult =
        runStreaming(ctl, listOf("dsh", "plugin", "--profile", PROFILE, "add", target.trim()), onLine)

    /** 卸载：`dsh plugin --profile web remove <name>`。 */
    suspend fun remove(ctl: LinuxCtl, name: String, onLine: (String) -> Unit): CtlResult =
        runStreaming(ctl, listOf("dsh", "plugin", "--profile", PROFILE, "remove", name.trim()), onLine)

    private suspend fun runStreaming(
        ctl: LinuxCtl,
        args: List<String>,
        onLine: (String) -> Unit,
    ): CtlResult = withContext(Dispatchers.IO) {
        onLine("$ ${args.joinToString(" ")}")
        val result = ctl.stream(listOf("exec", "--") + args) { line -> onLine(line) }
        onLine(if (result.ok) "✓ 完成（退出码 0）" else "✗ 失败：${result.message}")
        result
    }

    /**
     * 读已装插件。先定位 `package.json`（多个候选路径），
     * 再解析 `dependencies` 与 `bundles`。
     */
    suspend fun list(ctl: LinuxCtl): Result = withContext(Dispatchers.IO) {
        val probe = packageJsonCandidates.joinToString("; ") { p ->
            "if [ -f \"$p\" ]; then echo \"###PATH=$p\"; cat \"$p\"; exit 0; fi"
        } + "; exit 3"
        val r = ctl.execInEnvWithStdin(listOf("sh", "-c", probe), ByteArray(0), LinuxCtl.TIMEOUT_LOGS)
        if (!r.ok) {
            return@withContext Result(
                path = null,
                plugins = emptyList(),
                raw = r.stdout,
                error = if (r.exitCode == 3) {
                    "找不到 profile 的 package.json（试过 ${packageJsonCandidates.size} 个位置）。" +
                        "插件可能尚未安装过，或 DSH 布局不同。"
                } else {
                    r.message
                },
            )
        }
        val path = r.stdout.lineSequence().firstOrNull { it.startsWith("###PATH=") }?.removePrefix("###PATH=")
        val json = r.stdout.substringAfter("###PATH=$path\n", r.stdout)
        Result(path, parsePlugins(json), json, null)
    }

    data class Result(
        val path: String?,
        val plugins: List<Plugin>,
        val raw: String,
        val error: String?,
    )

    /** 解析 profile 的 package.json：dependencies + bundles。 */
    fun parsePlugins(json: String): List<Plugin> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val deps = root.optJSONObject("dependencies")
        val bundlesArr = root.optJSONArray("bundles")
        val bundled = buildSet {
            if (bundlesArr != null) {
                for (i in 0 until bundlesArr.length()) {
                    bundlesArr.optString(i, "").trim().takeIf { it.isNotEmpty() }?.let { add(it) }
                }
            }
        }
        val out = LinkedHashMap<String, Plugin>()
        if (deps != null) {
            deps.keys().forEach { k ->
                out[k] = Plugin(k, deps.optString(k, "").trim().ifEmpty { null }, k in bundled)
            }
        }
        // bundles 里出现但不在 dependencies 的也列出来（可能由 profile 自己的清单声明）
        bundled.forEach { k -> out.putIfAbsent(k, Plugin(k, null, true)) }
        return out.values.sortedBy { it.name }
    }

    /** 生成卸载命令的可读形式（界面上展示"将要执行什么"）。 */
    fun installCommand(target: String): String = "dsh plugin --profile $PROFILE add $target"

    fun removeCommand(name: String): String = "dsh plugin --profile $PROFILE remove $name"
}
