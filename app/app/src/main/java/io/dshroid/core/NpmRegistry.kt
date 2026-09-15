package io.dshroid.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * npm / pnpm 源（registry）。
 *
 * 为什么落点选 `linuxctl exec`：
 * npm 的**用户级**配置是 `$HOME/.npmrc`，而 entry.sh 把 `HOME` 设成 `/root`。
 * 所以往 `/root/.npmrc` 写就是写到**可写层**（overlay 的 upper / proot 的 rootfs），
 * 既不用改只读层，也不用依赖运行时新加环境变量 —— 而且天然两模式通用。
 *
 * 另外我们**同时**写一份 `$LINUX_HOME/etc/npmrc`（契约里环境根的配置面），
 * 供运行时将来通过 `NPM_CONFIG_USERCONFIG` 注入；两份内容一致，不会互相矛盾。
 *
 * 生效方式：**需要重启环境**（新起的 node/pnpm 进程才会读到新的 .npmrc；
 * 已经在跑的 dsh web 进程不受影响，但插件安装是另起进程，所以装插件前切换即可生效）。
 */
object NpmRegistry {

    data class Preset(val id: String, val name: String, val url: String, val note: String)

    /** 预设源。要加就把这里加一行，UI 自动出现。 */
    val presets: List<Preset> = listOf(
        Preset("npmjs", "npm 官方", "https://registry.npmjs.org", "最全、最新，国内可能慢"),
        Preset("npmmirror", "npmmirror（淘宝）", "https://registry.npmmirror.com", "国内镜像，同步有延迟"),
        Preset("tencent", "腾讯云", "https://mirrors.cloud.tencent.com/npm/", "国内镜像"),
        Preset("huawei", "华为云", "https://repo.huaweicloud.com/repository/npm/", "国内镜像"),
    )

    const val CUSTOM_ID = "custom"

    /** 环境内的 npmrc 路径（可写层）。 */
    const val USER_NPMRC = "/root/.npmrc"

    const val ENV_NPMRC_RELATIVE = "etc/npmrc"

    /** 校验自定义 URL：必须 http(s)，且看起来是个 registry 根。 */
    fun validateUrl(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return "请填写 registry 地址"
        if (!url.startsWith("http://") && !url.startsWith("https://")) return "地址必须以 http:// 或 https:// 开头"
        if (url.any { it.isWhitespace() }) return "地址里不能有空格"
        return null
    }

    /** 生成 .npmrc 内容（只写 registry 一行：其它字段交给 npm/pnpm 自己的默认值）。 */
    fun npmrcContent(registryUrl: String): String =
        buildString {
            append("# 由 DSHroid 写入（可写层）。修改请走 App 的「设置 → npm 源」。\n")
            append("registry=").append(registryUrl.trim().trimEnd('/')).append('\n')
        }

    /** 在环境里写入 npmrc 并回读验证。 */
    suspend fun apply(
        context: android.content.Context,
        mode: EnvMode,
        ctl: LinuxCtl,
        registryUrl: String,
    ): CtlResult = withContext(Dispatchers.IO) {
        val content = npmrcContent(registryUrl)

        // ① 可写层里的用户级配置（真正生效的那份）
        val script = "umask 077; mkdir -p \"\$(dirname $USER_NPMRC)\" && cat > $USER_NPMRC"
        // 走 exec 是为了进"合并后的 rootfs"——直接写 $LINUX_HOME/rootfs 在 root 模式下会被 overlay 盖住
        val writeUser = ctl.execInEnvWithStdin(listOf("sh", "-c", script), content.toByteArray())
        if (!writeUser.ok) {
            return@withContext CtlResult(
                writeUser.exitCode,
                writeUser.stdout,
                writeUser.stderr,
                "写入 $USER_NPMRC 失败：${writeUser.message}",
            )
        }

        // ② 环境根配置面的一份拷贝（供运行时用 NPM_CONFIG_USERCONFIG 注入）
        val writeEtc = EnvFiles.writeText(context, mode, ENV_NPMRC_RELATIVE, content)
        if (!writeEtc.ok) {
            return@withContext CtlResult(
                writeEtc.exitCode,
                writeEtc.stdout,
                writeEtc.stderr,
                "写入 $ENV_NPMRC_RELATIVE 失败：${writeEtc.message}",
            )
        }

        CtlResult(0, "已写入 $USER_NPMRC 与 $ENV_NPMRC_RELATIVE", "")
    }

    /** 回读生效值：优先问工具自己（npm/pnpm config get registry），拿不到就读文件。 */
    suspend fun verify(ctl: LinuxCtl): NpmStatus = withContext(Dispatchers.IO) {
        val probe = ctl.execInEnvWithStdin(
            listOf(
                "sh",
                "-c",
                "if command -v npm >/dev/null 2>&1; then echo \"npm=\$(npm config get registry 2>/dev/null)\"; " +
                    "elif command -v pnpm >/dev/null 2>&1; then echo \"pnpm=\$(pnpm config get registry 2>/dev/null)\"; " +
                    "else echo 'none'; fi; " +
                    "echo \"file=\$(cat $USER_NPMRC 2>/dev/null | grep -m1 '^registry=' | cut -d= -f2-)\"",
            ),
            ByteArray(0),
        )
        if (!probe.ok) {
            return@withContext NpmStatus(null, null, null, probe.message)
        }
        var fromTool: String? = null
        var fromFile: String? = null
        probe.stdout.lines().forEach { line ->
            when {
                line.startsWith("npm=") -> fromTool = line.removePrefix("npm=").trim()
                line.startsWith("pnpm=") -> fromTool = line.removePrefix("pnpm=").trim()
                line.startsWith("file=") -> fromFile = line.removePrefix("file=").trim()
            }
        }
        NpmStatus(fromTool?.takeIf { it.isNotEmpty() && it != "undefined" }, fromFile, probe.stdout, null)
    }

    data class NpmStatus(
        /** 工具自己报告的 registry（npm/pnpm config get registry） */
        val effective: String?,
        /** `/root/.npmrc` 里写着的 registry */
        val fromFile: String?,
        val raw: String?,
        val error: String?,
    ) {
        val summary: String
            get() = when {
                error != null -> "读取失败：$error"
                effective != null && fromFile != null && effective != fromFile ->
                    "工具生效值 $effective（文件 $fromFile，两者不一致）"
                effective != null -> "当前生效：$effective"
                fromFile != null -> "仅文件里有 $fromFile（npm/pnpm 未响应，可能需要重启环境）"
                else -> "尚未设置（用默认源）"
            }
    }

    /** 判断一个 registry URL 命中哪套预设。 */
    fun presetOf(url: String?): Preset? {
        val u = url?.trim()?.trimEnd('/') ?: return null
        return presets.firstOrNull { it.url.trimEnd('/') == u }
    }
}

/**
 * npm 包 dist-tag 解析（功能 D：在 App 里选 DSH 的通道）。
 *
 * 直接查 npm registry 的元数据（`GET https://registry.npmjs.org/<pkg>`），
 * 不依赖环境是否在运行 —— 用户只想看看"next 通道现在是哪个版本"时也能用。
 */
object NpmDistTags {

    /** 官方默认包名；频道清单里可以覆盖（`dsh_npm.package`）。 */
    const val DEFAULT_PACKAGE = "@deepseek-ai/dsh"

    val presets = listOf("latest", "next", "alpha")

    fun validateTag(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return "请填写 dist-tag 或版本号"
        if (t.any { it.isWhitespace() }) return "不能包含空格"
        if (t.length > 64) return "太长了"
        return null
    }

    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private var cachedAt = 0L
    private var cachedPkg: String? = null
    private var cachedTags: Map<String, String> = emptyMap()

    /**
     * 取该包的 dist-tags（含 5 分钟内存缓存）。
     * @return (tags, error)
     */
    suspend fun fetch(packageName: String = DEFAULT_PACKAGE): Pair<Map<String, String>, String?> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (cachedPkg == packageName && now - cachedAt < CACHE_TTL_MS) {
                return@withContext cachedTags to null
            }
            try {
                // 作用域名要转义：@scope/name -> @scope%2Fname
                val encoded = packageName.replace("/", "%2F")
                val body = Http.getText("https://registry.npmjs.org/$encoded", 8L * 1024 * 1024)
                val root = JSONObject(body)
                val tags = root.optJSONObject("dist-tags") ?: JSONObject()
                val map = buildMap {
                    tags.keys().forEach { k -> tags.optString(k, "").trim().takeIf { it.isNotEmpty() }?.let { put(k, it) } }
                }
                cachedPkg = packageName
                cachedTags = map
                cachedAt = now
                map to null
            } catch (t: Throwable) {
                (emptyMap<String, String>()) to (t.message ?: t.javaClass.simpleName)
            }
        }

    /**
     * 解析用户输入：既接受 dist-tag（latest/next/alpha），也接受明确版本号（1.2.3）。
     * @return 解析出的版本号；无法解析时 null
     */
    fun resolve(input: String, tags: Map<String, String>): String? {
        val t = input.trim()
        if (t.isEmpty()) return null
        tags[t]?.let { return it }
        // 看起来像版本号就直接用
        if (t.firstOrNull()?.isDigit() == true) return t
        return null
    }
}

/**
 * DSH 通道选择的持久化（功能 D）。
 *
 * 落两处，各有用途：
 * - **App 侧** `Prefs.dshDistTag`：界面回显、与频道清单声明的 `dsh_npm.dist_tag` 比对；
 * - **环境侧** `$LINUX_HOME/etc/config.json` 的 `dsh_dist_tag`：给运行时的 provision/update 读。
 *
 * ⚠️ 运行时目前**还没有**消费这个键（我核过 runtime/ 与 module/：只有
 * `tools/channel/gen-manifest.mjs` 会写 `dsh_npm.dist_tag`）。所以 App 侧能立刻做的是：
 * ① 用 npm registry 把 tag 解析成具体版本给用户看；② 把它落到 config.json 等运行时接。
 * 这条 handshake 需要运行时配合，已在报告里说明。
 */
object DshDistTagStore {

    const val CONFIG_RELATIVE = "etc/config.json"
    const val CONFIG_KEY = "dsh_dist_tag"

    /** 写环境配置（合并，不覆盖其它键）。 */
    suspend fun persist(
        context: android.content.Context,
        mode: EnvMode,
        tag: String?,
    ): CtlResult = EnvFiles.mergeJsonKey(context, mode, CONFIG_RELATIVE, CONFIG_KEY, tag?.trim()?.ifEmpty { null })

    /** 读回环境里记的值（用于界面确认"真的写进去了"）。 */
    suspend fun readBack(context: android.content.Context, mode: EnvMode): String? {
        val r = EnvFiles.readText(context, mode, CONFIG_RELATIVE)
        if (!r.ok) return null
        return runCatching { JSONObject(r.stdout).optString(CONFIG_KEY, "").trim().ifEmpty { null } }.getOrNull()
    }
}
