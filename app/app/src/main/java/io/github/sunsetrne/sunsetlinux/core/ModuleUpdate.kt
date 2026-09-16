package io.github.sunsetrne.sunsetlinux.core

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 官方发布站与仓库坐标。
 *
 * 与 `Prefs.Channel.OFFICIAL` 的公钥同类：这是**写死的信任根**，不是用户数据 ——
 * 用户能在设置里加/删自己的频道，但不能把"官方站"指到别处去。模块 zip 只在
 * GitHub Release 上有（187 KB，放 Pages 会让人误以为那是网页），所以这里要知道
 * "哪个站 + 哪个仓库"才能拼出下载地址。
 */
object OfficialSite {
    const val SITE = "https://sunsetrne.github.io/SunsetLinux"
    const val OWNER = "SunsetRNE"
    const val REPO = "SunsetLinux"

    /** 先正式、后预发布（`/stable` 由 main 发，`/beta` 由 beta 发）。 */
    val INDEX_URLS = listOf("$SITE/stable/index.json", "$SITE/beta/index.json")

    fun releaseAsset(tag: String, name: String): String =
        "https://github.com/$OWNER/$REPO/releases/download/$tag/$name"

    fun latestAsset(name: String): String =
        "https://github.com/$OWNER/$REPO/releases/latest/download/$name"
}

/** 一次可刷入的模块产物（版本 + 文件名 + 下载地址 + 校验值）。 */
data class ModuleArtifact(
    val version: String,
    val name: String,
    val url: String,
    val sha256: String?,
    val size: Long?,
    /** 这份信息是从哪个 index.json 读来的（`stable` / `beta`），排障用。 */
    val source: String,
)

/**
 * 模块版本比较：**逐段按数字比**（`1.0.10 > 1.0.9`，纯字符串比会得出相反的结论）。
 *
 * 段落缺失按 0 处理（`1.0` == `1.0.0`）——宁可少提示一次"有新版本"，也不要让用户
 * 每次打开关于页都看到同一个假更新。
 */
internal fun compareModuleVersion(a: String, b: String): Int {
    fun segs(s: String): List<String> =
        s.trim().removePrefix("v").removePrefix("V").split('.', '-', '_', '+')

    val x = segs(a)
    val y = segs(b)
    for (i in 0 until maxOf(x.size, y.size)) {
        val p = x.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
        val q = y.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
        if (p != q) return p.compareTo(q)
    }
    return 0
}

/**
 * 从官方站的 `index.json` 里读出"最新模块是什么"。
 *
 * 为什么认 index.json 而不是自己拼版本号：`index.json` 是**发布流水线同步生成的**
 * （`module_version` + Release tag + 每个资产的 sha256），App 端因此能"版本 + 校验值"
 * 一起拿到，不必猜。`files[]` 里有模块 zip 的 sha256 时，下载完当场核对。
 */
object ModuleRelease {

    data class Result(
        val artifact: ModuleArtifact?,
        val error: String? = null,
    )

    /** 纯函数：解析 index.json 文本；`null` = 这份索引里没有模块信息。 */
    fun parse(indexJson: String, source: String): ModuleArtifact? {
        val o = try {
            JSONObject(indexJson)
        } catch (_: Throwable) {
            return null
        }
        val ver = o.optString("module_version").trim().ifEmpty { null } ?: return null
        val tag = o.optString("github_release_tag").trim().ifEmpty { null }
        val name = "sunsetlinux-module-$ver.zip"

        var sha: String? = null
        var size: Long? = null
        var url: String? = null
        val files = o.optJSONArray("files")
        if (files != null) {
            for (i in 0 until files.length()) {
                val f = files.optJSONObject(i) ?: continue
                val n = f.optString("name").trim()
                if (n != name && !(n.startsWith("sunsetlinux-module-") && n.endsWith(".zip"))) continue
                sha = f.optString("sha256").trim().ifEmpty { null }
                size = f.optLong("size").takeIf { it > 0 }
                url = f.optString("url").trim().ifEmpty { null }
                if (n == name) break
            }
        }
        // 索引里没带 url（当前就是这样）→ 按 Release tag 拼；连 tag 都没有就退到 latest
        val resolved = url
            ?: tag?.let { OfficialSite.releaseAsset(it, name) }
            ?: OfficialSite.latestAsset(name)

        return ModuleArtifact(version = ver, name = name, url = resolved, sha256 = sha, size = size, source = source)
    }

    /** 网络：先看 `/stable/index.json`，再退 `/beta/index.json`。 */
    fun fetch(): Result {
        val errors = mutableListOf<String>()
        for (u in OfficialSite.INDEX_URLS) {
            val label = if (u.contains("/beta/")) "beta" else "stable"
            val text = try {
                Http.getText(u, maxBytes = 1024 * 1024)
            } catch (t: Throwable) {
                errors += "$label：${t.message ?: t.javaClass.simpleName}"
                continue
            }
            val a = parse(text, label)
            if (a != null) return Result(a)
            errors += "$label：索引里没有模块信息"
        }
        return Result(null, errors.joinToString("；"))
    }
}

/**
 * 把模块 zip 交给设备上的刷入器（KernelSU 的 `ksud`，或 Magisk 的 `magisk`）。
 *
 * ## 为什么这条路能成立
 *
 * 模块 zip 的"刷入"本来只是把 zip 解开、写到 `/data/adb/modules_update/<id>/`，
 * 然后**重启生效**。KernelSU 提供了 CLI：`ksud module install <zip>`
 * （见上游 `userspace/ksud/src/cli.rs` 的 `Module::Install`），而 App 有 root，
 * 所以"在 App 里更新模块"是可行的 —— 以前只能让用户自己去 KernelSU 管理器里点。
 *
 * ## 为什么先复制到 `/data/local/tmp`
 *
 * 缓存目录在 App 私有路径下（`/data/user/0/<pkg>/cache`），SELinux 上下文与你手动
 * 放进去的文件不同；`ksud` 以 root 跑，多数情况下读得到，但"多数情况"不够。
 * 先 `cp` 到 `/data/local/tmp`（toybox 的 cp，root 身份）再装，路径简单、权限确定。
 *
 * ## 装完**必须重启**
 *
 * `ksud module install` 落的是 `modules_update/`（下一次开机才生效）。所以这里
 * 只报告"已刷入，重启后生效"，**绝不替用户重启**。
 */
object ModuleInstaller {

    data class Outcome(val ok: Boolean, val log: String, val error: String? = null)

    /** 设备侧探测脚本：先 ksud，再 magisk；都没有就明确报错（退出码 127）。 */
    internal fun installScript(cachePath: String, name: String): String = buildString {
        // ⚠️ 这里全是**设备侧 shell** 的变量，Kotlin 的 `$` 必须转义成 `\$`，
        //    否则会被当成字符串模板去引用不存在的 Kotlin 变量（编译期就红）。
        append("KSUD=''; ")
        append("for c in /data/adb/ksu/bin/ksud /data/adb/ksud /system/bin/ksud; do ")
        append("[ -x \"\$c\" ] && { KSUD=\"\$c\"; break; }; done; ")
        append("if [ -n \"\$KSUD\" ]; then ")
        append("DST='/data/local/tmp/").append(name).append("'; ")
        append("cp ").append(shQuote(cachePath)).append(" \"\$DST\" || exit 3; ")
        append("chmod 0644 \"\$DST\" 2>/dev/null; ")
        append("echo \"# 用 \$KSUD module install 刷入\"; ")
        append("\"\$KSUD\" module install \"\$DST\"; ")
        append("exit \$?; ")
        append("fi; ")
        append("if [ -x /data/adb/magisk/magisk ]; then ")
        append("echo '# 用 magisk --install-module 刷入'; ")
        append("/data/adb/magisk/magisk --install-module ").append(shQuote(cachePath)).append("; ")
        append("exit \$?; ")
        append("fi; ")
        append("echo '本机既没有 ksud 也没有 magisk：请先在 KernelSU / Magisk 管理器里装一次模块' >&2; ")
        append("exit 127")
    }

    /**
     * 下载 + 校验 + 刷入。**阻塞**，调用方放 IO 线程。
     *
     * @param onStage (阶段文案, 已完成, 总量)；总量未知时给 -1。
     */
    fun install(
        context: Context,
        artifact: ModuleArtifact,
        onStage: (String, Long, Long) -> Unit,
    ): Outcome {
        val log = StringBuilder()
        log.append("=== 模块 ${artifact.version}（来自 ${artifact.source} index.json）===\n")
        log.append("· 产物 ${artifact.name}  ${artifact.sha256?.take(16)?.plus("…") ?: "（索引未给 sha256）"}\n")

        val work = File(context.cacheDir, "module").apply { mkdirs() }
        val zip = File(work, artifact.name.replace('/', '_'))

        onStage("下载模块", 0, artifact.size ?: -1)
        val digest = try {
            Http.download(artifact.url, zip) { done, total -> onStage("下载模块", done, total) }
        } catch (t: Throwable) {
            return Outcome(
                false,
                log.append("✗ 下载失败：${t.message ?: t.javaClass.simpleName}\n  ${artifact.url}\n").toString(),
                t.message,
            )
        }
        log.append("· 下载完成：${formatBytes(zip.length())}\n")
        if (!artifact.sha256.isNullOrBlank()) {
            if (!digest.equals(artifact.sha256, ignoreCase = true)) {
                zip.delete()
                return Outcome(
                    false,
                    log.append(
                        "✗ 模块 zip sha256 不匹配（不刷入）\n  期望 ${artifact.sha256}\n  实际 $digest\n"
                    ).toString(),
                    "sha256 不匹配",
                )
            }
            log.append("· sha256 校验通过\n")
        }

        onStage("刷入模块", 0, 1)
        val result = try {
            Proc.stream(
                cmd = listOf("su", "-c", installScript(zip.absolutePath, artifact.name)),
                onLine = { line -> log.append("  $line\n") },
            )
        } catch (t: Throwable) {
            return Outcome(false, log.append("✗ 起不了 su：${t.message}\n").toString(), t.message)
        } finally {
            runCatching { zip.delete() }
        }

        if (!result.ok) {
            val hint = when {
                result.exitCode == 127 -> "本机没有 ksud / magisk（或模块管理器未安装）"
                result.exitCode == 3 -> "复制到 /data/local/tmp 失败（空间不足？）"
                result.error != null -> result.error!!
                else -> result.message
            }
            return Outcome(false, log.append("✗ 刷入失败：$hint\n").toString(), hint)
        }
        log.append("✓ 已刷入 ${artifact.version}：**重启后生效**（KernelSU 落的是 modules_update/）\n")
        return Outcome(true, log.toString(), null)
    }
}
