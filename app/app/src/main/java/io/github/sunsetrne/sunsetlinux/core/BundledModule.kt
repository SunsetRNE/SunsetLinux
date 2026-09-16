package io.github.sunsetrne.sunsetlinux.core

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 这份 APK **自带**的 KernelSU 模块包（`assets/module/sunsetlinux-module.zip`）。
 *
 * ## 为什么模块包要进 APK
 *
 * 首启引导第 2 步原来只写"去 KernelSU 管理器里选 dist/sunsetlinux-module-0.1.0.zip" ——
 * 这句话对用户是**不可执行**的：那个 zip 在开发者的仓库/Release 里，手机上没有。
 * 用户卡在截图里那个勾选框前面，唯一的出路是自己去找包。
 *
 * 现在：`module/mkmodule.sh` 产出的 zip 在构建时被内嵌进 APK（见 app/app/build.gradle.kts
 * 的 `SyncBundledModule`），App 可以直接把它刷进去（`ksud module install`，与「关于 →
 * 更新模块」同一条通道），也可以导出到 Download 让用户手动装。
 *
 * ## 元信息从哪来
 *
 * 内嵌时同时写一份 `module/module.json`（版本 / 文件名 / 大小 / sha256），由构建期从
 * `module/module.prop` 与 zip 本身算出。App 只读这份 JSON，**不猜版本**：版本对不上时
 * 宁可显示"未知"，也不写一个错的版本号让用户以为装的是新版。
 *
 * ## 没有内嵌时怎么办
 *
 * `module.json` 不存在 = 这次构建没带模块包（`dist/` 是 gitignore 的，干净检出时就是这样）。
 * 界面必须如实说"这个包没内嵌模块"，并给出「关于 → 更新模块（走官方站下载）」与
 * 手动安装两条路 —— 绝不显示一个点了会失败的按钮。
 */
data class BundledModuleInfo(
    /** 模块版本（`module.prop` 的 `version=`，如 `v1.0.17`）。 */
    val version: String,
    /** assets 里的 zip 路径（固定 `module/sunsetlinux-module.zip`）。 */
    val assetPath: String,
    /** 刷入/导出时用的文件名（带版本，便于用户分辨）。 */
    val fileName: String,
    /** 构建时算出的 sha256；缺失时不做校验（也如实说明）。 */
    val sha256: String?,
    val size: Long,
) {
    /** 给用户看的短版本（去掉开头的 `v`）。 */
    val shortVersion: String get() = version.removePrefix("v").removePrefix("V")

    val label: String
        get() = buildString {
            append("内置模块 ").append(shortVersion)
            if (sha256 != null) append("（sha256 ").append(sha256.take(8)).append("…）")
        }
}

object BundledModule {

    const val ASSET_DIR = "module"
    const val ASSET_ZIP = "$ASSET_DIR/sunsetlinux-module.zip"
    const val ASSET_META = "$ASSET_DIR/module.json"

    /**
     * 读内嵌元信息。**任何一步不成立都返回 null**（没内嵌 / JSON 坏了 / zip 丢了这个组合）。
     * 界面据此走"未内嵌"分支，而不是给一个坏按钮。
     */
    fun info(context: Context): BundledModuleInfo? = try {
        val raw = context.assets.open(ASSET_META).use { it.readBytes().toString(Charsets.UTF_8) }
        val o = JSONObject(raw)
        val version = o.optString("version").trim()
        val asset = o.optString("asset").trim().ifEmpty { ASSET_ZIP }
        val name = o.optString("file").trim().ifEmpty { "sunsetlinux-module.zip" }
        val sha = o.optString("sha256").trim().ifEmpty { null }
        val size = o.optLong("size")
        if (version.isEmpty()) return null
        // assets 里真有这个 zip 才算数（元信息写了但包没打进去 = 构建坏了）
        try {
            context.assets.open(asset).close()
        } catch (_: Throwable) {
            return null
        }
        BundledModuleInfo(
            version = version,
            assetPath = asset,
            fileName = name,
            sha256 = sha,
            size = size,
        )
    } catch (_: Throwable) {
        null
    }

    /**
     * 把内嵌 zip 落到 App 私有缓存，返回可执行文件路径。
     *
     * 为什么要落盘、并且**落盘时校验 sha256**：`ksud` 是另一个进程（root），
     * 读 `assets`（在 APK 里）是不可能的；而"复制到一半的 zip"刷进去会让模块半死不活，
     * 所以就在这里对着构建期记下的 sha256 验一遍，验不过直接抛错，绝不刷。
     */
    @Throws(java.io.IOException::class)
    fun extract(context: Context, info: BundledModuleInfo): File {
        val dir = File(context.cacheDir, ASSET_DIR).apply { mkdirs() }
        val out = File(dir, info.fileName.replace('/', '_'))

        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(info.assetPath).use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                    output.write(buf, 0, n)
                }
            }
        }
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        val want = info.sha256
        if (!want.isNullOrBlank() && !got.equals(want, ignoreCase = true)) {
            out.delete()
            throw java.io.IOException("内置模块包校验失败（sha256 不匹配：期望 ${want.take(16)}…，实际 ${got.take(16)}…）")
        }
        return out
    }

    /**
     * 导出到 `/sdcard/Download/`（走 `su` 的 `cp`）。
     *
     * 为什么不是 SAF：KernelSU 管理器的「从本地安装」用的是系统文件选择器，
     * 用户看得见的是 `/sdcard/Download` 这种真路径；而 App 本身刻意不要任何存储权限
     * （见 AndroidManifest 注释）。root 模式下 `su cp` 一步到位，也就没有 SAF 的来回。
     */
    fun exportToDownload(context: Context, info: BundledModuleInfo): CtlResult {
        val zip = try {
            extract(context, info)
        } catch (t: Throwable) {
            return CtlResult.fail("导出失败：${t.message ?: t.javaClass.simpleName}")
        }
        val dst = "/sdcard/Download/${info.fileName}"
        val script = buildString {
            append("mkdir -p /sdcard/Download; ")
            append("cp ").append(shQuote(zip.absolutePath)).append(' ').append(shQuote(dst))
            append(" && chmod 0644 ").append(shQuote(dst))
            append(" && echo ").append(shQuote(dst))
        }
        val r = SuShell.exec(script, 20_000L)
        return if (r.ok) {
            CtlResult(
                exitCode = 0,
                stdout = r.stdout.lineSequence().lastOrNull { it.isNotBlank() }?.trim() ?: dst,
                stderr = r.stderr,
            )
        } else {
            CtlResult.fail(
                "导出失败（su cp）：${r.message}。可以把包分享出去后手动保存到 Download（用下面的「分享模块包」）。",
            )
        }
    }

    /** 分享内嵌 zip（FileProvider，不需要存储权限）。导不出时的兜底。 */
    fun share(context: Context, info: BundledModuleInfo): String? = try {
        val zip = extract(context, info)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            zip,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "保存/分享模块包").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        null
    } catch (t: Throwable) {
        t.message ?: t.javaClass.simpleName
    }

    /**
     * 已知的 root 管理器包名（打开它去手动装模块 / 看模块状态）。
     *
     * 顺序 = 本项目用户的常见度：KernelSU 官方 → KernelSU-Next → MMRL → Magisk。
     * 一个都没装就返回 null，由界面提示"手动打开"，不弹一个空 chooser。
     */
    private val MANAGER_PACKAGES = listOf(
        "me.weishu.kernelsu",
        "com.rifsxd.ksunext",
        "com.dergoogler.mmrl",
        "com.topjohnwu.magisk",
    )

    fun openManager(context: Context): Boolean {
        for (pkg in MANAGER_PACKAGES) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(intent)
                true
            } catch (_: Throwable) {
                continue
            }
        }
        return false
    }
}
