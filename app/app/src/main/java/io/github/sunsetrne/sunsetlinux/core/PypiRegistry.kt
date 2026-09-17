package io.github.sunsetrne.sunsetlinux.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Python 源（pip index-url）。
 *
 * 与 [NpmRegistry] 同构：预设表 + 自定义校验 + 写盘 + 回读验证，
 * 但**落点不一样**，所以两份实现不能合并：
 *
 * - npm 的用户级配置是 `$HOME/.npmrc`；
 * - pip 的用户级配置是 `$HOME/.config/pip/pip.conf`（`~/.pip/pip.conf` 是更老的路径，
 *   现行 pip 会同时看，但**首选** `$HOME/.config/pip/pip.conf`）。
 *   而 entry.sh 把 `HOME` 设成 `/root`，所以写 `/root/.config/pip/pip.conf`
 *   就是写到**可写层**（overlay 的 upper / proot 的 rootfs），两种模式通用，
 *   也不需要运行时新加环境变量。
 *
 * ## 为什么写两份 conf（与 npm 的理由一样，再加一条 Python 特有的）
 *
 * | 路径 | 角色 |
 * |---|---|
 * | `/root/.config/pip/pip.conf` | **真正生效的那份**：pip 默认就读它，不必依赖任何环境变量 |
 * | `<环境根>/etc/pip.conf` | 环境根的**配置面**（契约 §2.1 的 `etc/`），供运行时用 `PIP_CONFIG_FILE` 注入 |
 *
 * Python 特有的一条：`PIP_CONFIG_FILE` 的优先级**高于**用户级 conf，且某些发行版镜像/
 * 基础层会在 `etc/pip.conf`（系统级）里预置一个指向内网源或 http 源的 index-url ——
 * 那时只写用户级那份会被系统级盖住。两份内容完全一致，既兜住这种情况，
 * 也保证"我在 App 里看到的值"和"环境里任何一条读取路径拿到的值"不会互相矛盾。
 *
 * ## 生效方式
 *
 * **重启环境**后新起的进程才会读到（已经在跑的 dsh web 进程不受影响；
 * 但 `pip install` 是另起进程，装包前切一下即可生效）。
 */
object PypiRegistry {

    data class Preset(val id: String, val name: String, val url: String, val note: String)

    /** 预设源。要加就把这里加一行，UI 自动出现。 */
    val presets: List<Preset> = listOf(
        Preset("pypi", "PyPI 官方", "https://pypi.org/simple", "最全、最新，国内可能慢"),
        Preset("tuna", "清华 TUNA", "https://pypi.tuna.tsinghua.edu.cn/simple", "国内镜像"),
        Preset("aliyun", "阿里云", "https://mirrors.aliyun.com/pypi/simple/", "国内镜像"),
        Preset("tencent", "腾讯云", "https://mirrors.cloud.tencent.com/pypi/simple", "国内镜像"),
        Preset("huawei", "华为云", "https://repo.huaweicloud.com/repository/pypi/simple", "国内镜像"),
    )

    /** 官方预设的 id。**默认档必须落在它上面**（理由见 [selectedPresetId]）。 */
    const val OFFICIAL_ID = "pypi"

    const val CUSTOM_ID = "custom"

    /** 官方预设（默认值 / 回退值的唯一来源）。 */
    val official: Preset get() = presets.first { it.id == OFFICIAL_ID }

    /** 用户级 pip.conf（可写层，真正生效的那份）。 */
    const val USER_PIP_CONF = "/root/.config/pip/pip.conf"

    /** 环境根配置面的一份拷贝（供运行时用 PIP_CONFIG_FILE 注入）。 */
    const val ENV_PIP_CONF_RELATIVE = "etc/pip.conf"

    // ─────────────────────────────────────────────────────────── 纯逻辑（可单测）

    /**
     * 校验自定义 index-url。
     *
     * **只接受 https**（判定理由，不是洁癖）：
     * pip 会把 index 里下载到的包**直接安装并执行**其中的 `setup.py` / build backend。
     * 明文 http 源意味着链路上任何人（同一 Wi-Fi、运营商、被劫持的上游）都能替换包内容，
     * 且 pip 对这种替换没有任何校验手段（PyPI 的 hash 校验只在有 `--require-hashes`
     * 或 lock 文件时才生效，我们这份 conf 里并没有）。相比之下 npm 卡允许 http
     * 是因为 npm 侧还有一个"本地内网私有 registry"的现实场景，而 Python 侧
     * 我们把这条风险挡住了：国内镜像全都是 https，用不到 http。
     */
    fun validateUrl(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return "请填写 index-url 地址"
        if (url.any { it.isWhitespace() }) return "地址里不能有空格/换行"
        if (!url.startsWith("https://")) {
            return "只接受 https:// 开头的地址：pip 源里下载的包会被直接安装执行，明文 http 可被链路上任何人替换"
        }
        val uri = runCatching { java.net.URI(url) }.getOrNull()
            ?: return "不是合法的 URL（示例：https://pypi.org/simple）"
        if (uri.host.isNullOrBlank()) return "URL 里缺少主机名"
        return null
    }

    /**
     * 从 index-url 里取 **trusted-host 该写的值**：只有主机名，不带端口与路径。
     *
     * 为什么必须"只有主机名"（这是最容易写错的一处）：
     * pip 的 `add_trusted_host()` 在**没给端口**时会额外挂载通配端口的适配器
     * （`if not parsed_port: mount(host + ":")`，见 pip `network/session.py`），
     * 所以 `trusted-host = mirror.local` 已经覆盖 `https://mirror.local:8080/simple`；
     * 反过来写成 URL（带 scheme 或带路径）会解析不出 host 或匹配不上。
     */
    fun trustedHostOf(url: String): String {
        val trimmed = url.trim()
        val afterScheme = trimmed.substringAfter("://", trimmed)
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostPort = authority.substringAfterLast('@').trim()
        // IPv6 字面量写成 [::1]：去掉方括号会让 pip 解析成一堆冒号分隔的片段
        return if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
    }

    /** 生成 pip.conf 内容（`index-url` 原样保留尾部斜杠：pip 的 Simple API 约定它以此结尾）。 */
    fun pipConfContent(indexUrl: String): String {
        val url = indexUrl.trim()
        return buildString {
            append("# 由 SunsetLinux 写入（可写层）。修改请走 App 的「源与镜像 → Python 源」。\n")
            append("[global]\n")
            append("index-url = ").append(url).append('\n')
            append("trusted-host = ").append(trustedHostOf(url)).append('\n')
        }
    }

    /** 判断一个 index-url 命中哪套预设（尾部斜杠差异不算差异）。 */
    fun presetOf(url: String?): Preset? {
        val u = url?.trim()?.trimEnd('/') ?: return null
        if (u.isEmpty()) return null
        return presets.firstOrNull { it.url.trim().trimEnd('/') == u }
    }

    /**
     * 界面**该选中哪一档**。
     *
     * 与 [NpmRegistry.selectedPresetId] 同一个坑：不能把"没选过"和"自定义"混为一谈。
     * 没选过（null/空）→ 官方；反查到预设 → 该预设；其余 → 自定义。
     */
    fun selectedPresetId(stored: String?): String {
        if (stored.isNullOrBlank()) return OFFICIAL_ID
        return presetOf(stored)?.id ?: CUSTOM_ID
    }

    /** 当前实际会生效的 index-url：选过用选过的，没选过用官方。 */
    fun effectiveIndexUrl(stored: String?): String =
        stored?.trim()?.takeIf { it.isNotEmpty() } ?: official.url

    /** 自定义输入框的初始值：只有存着的是自定义地址时才回填。 */
    fun customSeed(stored: String?): String =
        if (!stored.isNullOrBlank() && presetOf(stored) == null) stored.trim() else ""

    // ─────────────────────────────────────────────────────────── 落盘 / 回读

    /**
     * 写入两份 pip.conf 并回读验证。
     *
     * 写入走 [LinuxCtl.writeFileInEnvAtomic]：先写同目录临时文件再 `mv -f` 覆盖。
     * 直接 `cat >` 写一半被打断（杀进程 / su 超时 / 磁盘满）会留下**半截 conf**，
     * pip 读到时可能报 ConfigParser 错误，也可能静默回落到官方源 —— 后者最难查。
     */
    suspend fun apply(
        context: android.content.Context,
        mode: EnvMode,
        ctl: LinuxCtl,
        indexUrl: String,
    ): CtlResult = withContext(Dispatchers.IO) {
        val invalid = validateUrl(indexUrl)
        if (invalid != null) return@withContext CtlResult.fail("Python 源地址不合法：$invalid")

        val content = pipConfContent(indexUrl)

        // ① 可写层里的用户级配置（pip 默认读的那份）
        val writeUser = ctl.writeFileInEnvAtomic(USER_PIP_CONF, content.toByteArray())
        if (!writeUser.ok) {
            return@withContext CtlResult(
                writeUser.exitCode,
                writeUser.stdout,
                writeUser.stderr,
                "写入 $USER_PIP_CONF 失败：${writeUser.message}",
            )
        }

        // ② 环境根配置面的一份拷贝（供运行时用 PIP_CONFIG_FILE 注入 / 系统级兜底）
        val writeEtc = EnvFiles.writeText(context, mode, ENV_PIP_CONF_RELATIVE, content)
        if (!writeEtc.ok) {
            return@withContext CtlResult(
                writeEtc.exitCode,
                writeEtc.stdout,
                writeEtc.stderr,
                "写入 $ENV_PIP_CONF_RELATIVE 失败：${writeEtc.message}",
            )
        }

        CtlResult(0, "已写入 $USER_PIP_CONF 与 $ENV_PIP_CONF_RELATIVE", "")
    }

    /**
     * 回读生效值：优先问 pip 自己（`pip config get global.index-url`），拿不到就读文件。
     *
     * 一次 `linuxctl exec` 里同时拿"pip 报告的值"和"用户级 conf 的内容"，
     * 环境根那份 conf 由 App 侧直接读（它在 chroot 里不一定是同一个路径）。
     */
    suspend fun verify(context: android.content.Context, mode: EnvMode, ctl: LinuxCtl): PypiStatus =
        withContext(Dispatchers.IO) {
            val script = buildString {
                append("echo \"file=\$(sed -n 's/^index-url *= *//p' ")
                append(shQuote(USER_PIP_CONF))
                append(" 2>/dev/null | head -n1)\"; ")
                append("if command -v pip3 >/dev/null 2>&1; then echo \"pip=\$(pip3 config get global.index-url 2>/dev/null)\"; ")
                append("elif command -v pip >/dev/null 2>&1; then echo \"pip=\$(pip config get global.index-url 2>/dev/null)\"; ")
                append("else echo 'pip='; fi")
            }
            val probe = ctl.execInEnv("sh", "-c", script)
            if (!probe.ok) return@withContext PypiStatus(null, null, null, null, probe.message)

            var effective: String? = null
            var fromUserConf: String? = null
            probe.stdout.lines().forEach { line ->
                when {
                    // 旧版 pip 的 `config get` 会把值连引号一起打出来（`'https://…'`），
                    // 顺手剥一层，免得界面把引号当成地址的一部分显示
                    line.startsWith("pip=") ->
                        effective = line.removePrefix("pip=").trim().trim('\'', '"')
                    line.startsWith("file=") ->
                        fromUserConf = line.removePrefix("file=").trim().trim('\'', '"')
                }
            }

            // 环境根那份 conf：App 侧读（root 模式经 su，proot 模式直接读）
            val etc = EnvFiles.readText(context, mode, ENV_PIP_CONF_RELATIVE)
            val fromEnvConf = if (etc.ok) {
                etc.stdout.lines().firstOrNull { it.trimStart().startsWith("index-url") }
                    ?.substringAfter('=')?.trim()?.ifEmpty { null }
            } else {
                null
            }

            PypiStatus(
                effective = effective?.takeIf { it.isNotEmpty() && it != "undefined" },
                fromUserConf = fromUserConf?.takeIf { it.isNotEmpty() },
                fromEnvConf = fromEnvConf,
                raw = probe.stdout,
                error = null,
            )
        }

    data class PypiStatus(
        /** pip 自己报告的 index-url（`pip config get global.index-url`） */
        val effective: String?,
        /** `/root/.config/pip/pip.conf` 里写着的 index-url */
        val fromUserConf: String?,
        /** `<环境根>/etc/pip.conf` 里写着的 index-url */
        val fromEnvConf: String?,
        val raw: String?,
        val error: String?,
    ) {
        val summary: String
            get() = when {
                error != null -> "读取失败：$error"
                effective != null && fromUserConf != null && effective != fromUserConf ->
                    "pip 生效值 $effective（用户级 conf 里是 $fromUserConf，两者不一致）"
                effective != null -> "当前生效：$effective"
                fromUserConf != null ->
                    "仅文件里有 $fromUserConf（pip 未响应，可能需要重启环境）"
                fromEnvConf != null -> "仅环境根 etc/pip.conf 里有 $fromEnvConf（用户级 conf 未写入？）"
                else -> "尚未设置（用默认源）"
            }
    }
}
